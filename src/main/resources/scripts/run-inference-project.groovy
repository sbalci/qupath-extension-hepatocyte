/**
 * run-inference-project.groovy — Whole-project hepatocyte segmentation
 * --------------------------------------------------------------------
 * Bundled in qupath-extension-hepatocyte; launched from
 * Extensions → Hepatocyte → "3. Run inference on whole project (unlabelled / tissue)…".
 *
 * WHAT IT DOES:
 *   Scans every slide in the open QuPath project for candidate area annotations
 *   (unlabelled user-drawn annotations or auto-detected "Tissue" regions that do
 *   not yet have a `Hepatocyte: total` measurement and are ≥ minRoiSize).
 *   For each slide with candidate regions:
 *     1. Writes a request JSON (level-0 coordinates + WKT geometry).
 *     2. Calls `hepatocyte_qupath_inference.py` once per slide.
 *     3. Imports the predicted cell polygons and writes per-class count
 *        measurements (`Hepatocyte: <class>` and `Hepatocyte: total`) to each
 *        parent ROI.
 *     4. Saves the updated slide hierarchy back to the project (`entry.saveImageData`)
 *        and writes a multi-sheet Excel report under `<project>/reports/<slide>/`.
 *
 *   Idempotent: re-running skips annotations that already have `Hepatocyte: total`.
 *
 * ⚠️ Research and education use only. Not for clinical decision-making.
 */

import qupath.lib.gui.QuPathGUI
import qupath.lib.io.GsonTools
import qupath.lib.io.PathIO
import qupath.lib.scripting.QP
import qupath.fx.dialogs.Dialogs
import org.locationtech.jts.io.WKTWriter
import com.google.gson.JsonParser
import javafx.application.Platform
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

def gui = QuPathGUI.getInstance()
def project = QP.getProject()
if (project == null) {
    Platform.runLater {
        Dialogs.showErrorMessage("No project open",
            "Open a QuPath project first via File → Project → Open project…")
    }
    return
}

try {
    Class.forName('io.github.sbalci.qupath.hepatocyte.HepatocyteConfig')
        .getMethod('autoDetectIfNeeded').invoke(null)
} catch (Throwable ignore) {}

def prefs = java.util.prefs.Preferences.userRoot().node('io/github/sbalci/qupath/hepatocyte')
def pythonExe = prefs.get('python', '').trim()
def bridgePy  = prefs.get('bridge', '').trim()
def modelPath = prefs.get('model',  '').trim()

def missing = []
if (!pythonExe || !new File(pythonExe).isFile()) missing << "Python (venv): " + (pythonExe ?: "(not set)")
if (!bridgePy  || !new File(bridgePy).isFile())  missing << "Bridge script: " + (bridgePy  ?: "(not set)")
if (!modelPath || !new File(modelPath).isFile()) missing << "Model weights: " + (modelPath ?: "(not set)")
if (!missing.isEmpty()) {
    Platform.runLater {
        Dialogs.showErrorMessage("Configuration incomplete",
            "The following required paths are not configured or missing on disk:\n\n  • " +
            missing.join("\n  • ") +
            "\n\nConfigure them in Extensions → Hepatocyte → Settings… first.")
    }
    return
}

def parseIntOr = { s, int d -> try { return Integer.parseInt((s ?: '').toString().trim()) } catch (Throwable t) { return d } }
def parseDoubleOr = { s, double d -> try { return Double.parseDouble((s ?: '').toString().replace(',', '.').trim()) } catch (Throwable t) { return d } }

int patchSize    = parseIntOr(prefs.get('patch',   '512'), 512)
int minAreaPx    = parseIntOr(prefs.get('minArea', '15'),  15)
int minRoiSize   = parseIntOr(prefs.get('minRoi',  '512'), 512)
String device    = prefs.get('device', 'auto') ?: 'auto'
double targetMpp = parseDoubleOr(prefs.get('targetMpp', '0.25'), 0.25d)

def PREDICTION_CLASSES = ['Balloon dystrophy', 'Mesenchymal', 'Non-nuclear', 'Normal', 'Steatosis'] as Set
def isInferenceCandidate = { ann ->
    if (ann == null || !ann.isAnnotation() || !ann.hasROI() || !ann.getROI().isArea()) return false
    def cls = ann.getPathClass()
    return cls == null || !PREDICTION_CLASSES.contains(cls.toString())
}

def resolveSlidePath = { imageData ->
    def server = imageData.getServer()
    def uris = []
    try { uris = server.getURIs() ?: [] } catch (Throwable ignore) {}
    for (def uri : uris) {
        try { def f = new File(uri); if (f.exists()) return f.getAbsolutePath() } catch (Throwable ignore) {}
    }
    def sp = null
    try { sp = server.getPath() } catch (Throwable ignore) {}
    if (sp != null) {
        def m = (~"file:/+(.+?\\.(?:svs|tiff?|ndpi|mrxs|scn|bif|vms|vmu|png))\$").matcher(sp)
        if (m.find()) {
            try { return java.net.URLDecoder.decode(m.group(1), 'UTF-8').replace('/', File.separator) } catch (Throwable ignore) {}
        }
    }
    return null
}

def imageList = project.getImageList()
if (imageList.isEmpty()) {
    Platform.runLater {
        Dialogs.showMessageDialog("Empty project",
            "This project has no slides yet. Run '1. Import slides from folder…' first.")
    }
    return
}

println "=== Scanning project for candidate annotations ==="
def workItems = []
int totalCandidates = 0, alreadyProcessed = 0, tooSmall = 0

for (def entry : imageList) {
    def imgData
    try {
        imgData = entry.readImageData()
    } catch (Throwable t) {
        println "  " + entry.getImageName() + ": failed to read image data — " + t.getMessage()
        continue
    }
    def candidates = []
    for (def ann : imgData.getHierarchy().getAnnotationObjects()) {
        if (!isInferenceCandidate(ann)) continue
        if (ann.getMeasurementList().containsKey("Hepatocyte: total")) {
            alreadyProcessed++
            continue
        }
        def roi = ann.getROI()
        if ((int) Math.ceil(roi.getBoundsWidth()) < minRoiSize ||
            (int) Math.ceil(roi.getBoundsHeight()) < minRoiSize) {
            tooSmall++
            continue
        }
        candidates << ann
    }
    if (!candidates.isEmpty()) {
        workItems << [entry: entry, imgData: imgData, annotations: candidates]
        totalCandidates += candidates.size()
    }
}

if (workItems.isEmpty()) {
    Platform.runLater {
        Dialogs.showMessageDialog("Nothing to process",
            String.format(java.util.Locale.US,
                "Scanned %d slide(s) in project.\n\n" +
                "Candidate unprocessed annotations (≥ %d×%d px): 0\n" +
                "Already processed annotations (skipped)        : %d\n" +
                "Too-small annotations (skipped)                : %d\n\n" +
                "Draw new annotations or run '5. Auto-detect tissue (whole project)…' first.",
                imageList.size(), minRoiSize, minRoiSize, alreadyProcessed, tooSmall))
    }
    return
}

def confirmed = new AtomicBoolean(false)
def latch = new CountDownLatch(1)
Platform.runLater {
    try {
        def msg = String.format(java.util.Locale.US,
            "Scanned %d slide(s).\n\n" +
            "Will segment %d candidate annotation(s) across %d slide(s).\n" +
            (alreadyProcessed > 0 ? "Skipping " + alreadyProcessed + " already-processed annotation(s).\n" : "") +
            (tooSmall > 0 ? "Skipping " + tooSmall + " too-small annotation(s).\n" : "") +
            "\nPython will be invoked once per slide.\nContinue?",
            imageList.size(), totalCandidates, workItems.size())
        confirmed.set(Dialogs.showConfirmDialog("Whole-project inference", msg))
    } finally { latch.countDown() }
}
latch.await()
if (!confirmed.get()) {
    println "Whole-project inference cancelled."
    return
}

def projDir = project.getPath() != null ? project.getPath().getParent().toFile() : new File(System.getProperty('java.io.tmpdir'))
def tempDir = new File(projDir, 'hepatocyte_tmp'); tempDir.mkdirs()
def wktWriter = new WKTWriter()
def gson = GsonTools.getInstance(true)

long batchStart = System.currentTimeMillis()
int processedSlides = 0, processedAnnotations = 0, totalPolygons = 0, errorSlides = 0
def successfulEntries = [] as Set

for (int i = 0; i < workItems.size(); i++) {
    def item = workItems[i]
    def entry = item.entry
    def imgData = item.imgData
    def annotations = item.annotations

    println String.format(java.util.Locale.US, "%n[%d/%d] %s: %d annotation(s)",
        i + 1, workItems.size(), entry.getImageName(), annotations.size())

    try {
        def slidePath = resolveSlidePath(imgData)
        if (slidePath == null) {
            println "  ERROR: could not resolve local slide path"
            errorSlides++
            continue
        }

        def rois = []
        annotations.each { ann ->
            def roi = ann.getROI()
            def shapeWkt = null
            try { def g = roi.getGeometry(); if (g != null) shapeWkt = wktWriter.write(g).toString() } catch (Throwable ignore) {}
            rois << [ id: ann.getID().toString(),
                      x: (int) Math.floor(roi.getBoundsX()),
                      y: (int) Math.floor(roi.getBoundsY()),
                      w: (int) Math.ceil(roi.getBoundsWidth()),
                      h: (int) Math.ceil(roi.getBoundsHeight()),
                      shape_wkt: shapeWkt ]
        }

        def runId = UUID.randomUUID().toString().take(8)
        def requestFile = new File(tempDir, 'hepatocyte_request_' + runId + '.json')
        def outputFile  = new File(tempDir, 'hepatocyte_result_' + runId + '.geojson')

        def slideStem = (entry.getImageName() ?: 'slide').replaceAll(/\.[^.\/\\]+$/, '').replaceAll('[\\\\/:*?"<>|]', '_')
        def stamp = new java.text.SimpleDateFormat('yyyyMMdd_HHmmss').format(new Date())
        def repDir = new File(new File(projDir, 'reports'), slideStem); repDir.mkdirs()
        def reportFile = new File(repDir, stamp + '_' + runId + '.xlsx')

        def request = [
            slide_path       : slidePath,
            model_path       : modelPath,
            rois             : rois,
            patch_size       : patchSize,
            min_area         : minAreaPx,
            min_roi_size     : minRoiSize,
            device           : device,
            target_mpp       : targetMpp,
            output_xlsx_path : reportFile.getAbsolutePath()
        ]
        requestFile.setText(gson.toJson(request), 'UTF-8')

        def cmd = [pythonExe, bridgePy, requestFile.getAbsolutePath(), outputFile.getAbsolutePath()]*.toString()
        def proc = new ProcessBuilder(cmd).redirectErrorStream(true).start()
        proc.getInputStream().eachLine('UTF-8') { line -> println "    [py] " + line }
        int exit = proc.waitFor()
        if (exit != 0 || !outputFile.isFile()) {
            println "  ERROR: Python exited with code " + exit
            try { requestFile.delete() } catch (Throwable ignore) {}
            errorSlides++
            continue
        }

        def imported = []
        outputFile.withInputStream { is -> imported = PathIO.readObjectsFromGeoJSON(is) }
        if (imported != null && !imported.isEmpty()) {
            imgData.getHierarchy().addObjects(imported)
        }

        def countsPerRoi = [:]
        try {
            def reader = new java.io.InputStreamReader(new java.io.FileInputStream(outputFile), java.nio.charset.StandardCharsets.UTF_8)
            try {
                def root = JsonParser.parseReader(reader).getAsJsonObject()
                if (root.has('metadata') && root.get('metadata').isJsonObject()) {
                    def md = root.getAsJsonObject('metadata')
                    if (md.has('counts_per_roi') && md.get('counts_per_roi').isJsonObject()) {
                        md.getAsJsonObject('counts_per_roi').entrySet().each { e ->
                            def per = [:]
                            e.getValue().getAsJsonObject().entrySet().each { sub -> per[sub.getKey()] = sub.getValue().getAsInt() }
                            countsPerRoi[e.getKey()] = per
                        }
                    }
                }
            } finally { reader.close() }
        } catch (Throwable ignore) {}

        annotations.each { ann ->
            def counts = countsPerRoi[ann.getID().toString()] ?: [:]
            int total = 0
            def ml = ann.getMeasurementList()
            counts.each { cls, n ->
                ml.put(('Hepatocyte: ' + cls).toString(), (double) ((int) n))
                total += (int) n
            }
            ml.put('Hepatocyte: total'.toString(), (double) total)
        }

        try { requestFile.delete(); outputFile.delete() } catch (Throwable ignore) {}

        entry.saveImageData(imgData)
        successfulEntries << entry
        processedSlides++
        processedAnnotations += annotations.size()
        totalPolygons += (imported ? imported.size() : 0)
        println "  -> " + (imported ? imported.size() : 0) + " polygon(s) imported"
    } catch (Throwable t) {
        println "  ERROR: " + (t.getMessage() ?: t.getClass().getSimpleName())
        errorSlides++
    }
}

project.syncChanges()
double elapsed = (System.currentTimeMillis() - batchStart) / 1000.0d

Platform.runLater {
    try {
        gui?.refreshProject()
        def displayed = gui?.getImageData()
        if (displayed != null && !successfulEntries.isEmpty()) {
            def dispEntry = project.getEntry(displayed)
            if (dispEntry != null && successfulEntries.contains(dispEntry)) {
                gui.openImageEntry(dispEntry)
            }
        }
    } catch (Throwable ignore) {}
    Dialogs.showMessageDialog("Whole-project inference complete",
        String.format(java.util.Locale.US,
            "Slides processed     : %d / %d\n" +
            "Annotations segmented: %d\n" +
            "Polygons generated   : %,d\n" +
            (errorSlides > 0 ? "Slide errors         : " + errorSlides + "\n" : "") +
            "Elapsed time         : %.1f s\n\n" +
            "Per-slide Excel reports saved under <project>/reports/<slide>/.\n" +
            "⚠️ Research and education measurements only.",
            processedSlides, workItems.size(), processedAnnotations, totalPolygons, elapsed))
}

