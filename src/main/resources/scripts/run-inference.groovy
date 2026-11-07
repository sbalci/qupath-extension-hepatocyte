/**
 * run-inference.groovy — Hepatocyte segmentation (single-window wizard)
 * ---------------------------------------------------------------------
 * Bundled in qupath-extension-hepatocyte; launched from
 * Extensions → Hepatocyte → "2. Run inference on selected annotations…".
 *
 * WHAT IT DOES:
 *   Runs the hepatocyte-app deep-learning (TorchScript) model on the selected
 *   area annotation(s) (or all non-prediction area annotations on the slide if
 *   none is explicitly selected):
 *     1. Writes each candidate area annotation's level-0 coordinates + WKT
 *        geometry to a request JSON.
 *     2. Runs hepatocyte-app's `hepatocyte_qupath_inference.py` in a Python venv
 *        (OUTSIDE QuPath); the bridge opens the slide with openslide, runs the
 *        model with MPP-aware scaling, separates touching cells via Watershed,
 *        and returns cell polygons as GeoJSON.
 *     3. Imports the GeoJSON back as classified QuPath annotations and writes
 *        per-class cell count measurements (`Hepatocyte: <class>` and
 *        `Hepatocyte: total`) to each parent ROI. If a project is open, an
 *        Excel report is written under `<project>/reports/<slide>/`.
 *
 * ⚠️ Research and education use only. Not for clinical decision-making.
 */

import qupath.fx.dialogs.Dialogs
import qupath.lib.scripting.QP
import qupath.lib.io.GsonTools
import qupath.lib.io.PathIO
import org.locationtech.jts.io.WKTWriter
import com.google.gson.JsonParser
import java.io.File

def gui = qupath.lib.gui.QuPathGUI.getInstance()
def isHeadless = gui == null

// ── Constants ───────────────────────────────────────────────────────────────
long PYTHON_TIMEOUT_SECONDS = 1800L          // WSI region inference — generous ceiling
def MONO = "-fx-font-family: 'Consolas', 'Menlo', 'Courier New', monospace; -fx-font-size: 12px;"
def DEVICE_OPTIONS = ['auto', 'cpu', 'cuda']
def PREDICTION_CLASSES = ['Balloon dystrophy', 'Mesenchymal', 'Non-nuclear', 'Normal', 'Steatosis'] as Set

def HEPATO_REPO_ZIP   = 'https://github.com/sbalci/hepatocyte-app/archive/refs/heads/main.zip'
def HEPATO_ZIP_TOP    = 'hepatocyte-app-main'
def HEPATO_BRIDGE_SUB = 'qupath/hepatocyte_qupath_inference.py'

// ── Persistent config: java.util.prefs (MUST match HepatocyteConfig.NODE) ────
def prefs = java.util.prefs.Preferences.userRoot().node('io/github/sbalci/qupath/hepatocyte')
def PREF_PYTHON  = 'python'
def PREF_BRIDGE  = 'bridge'
def PREF_MODEL   = 'model'
def PREF_DEVICE  = 'device'
def PREF_PATCH   = 'patch'
def PREF_MINAREA = 'minArea'
def PREF_MINROI  = 'minRoi'
def PREF_MPP     = 'targetMpp'

// Auto-detect standard hepatocyte-app locations if paths are unset
try {
    Class.forName('io.github.sbalci.qupath.hepatocyte.HepatocyteConfig')
        .getMethod('autoDetectIfNeeded').invoke(null)
} catch (Throwable ignore) {}

def loadConfig = { ->
    [ python    : prefs.get(PREF_PYTHON,  ''),
      bridge    : prefs.get(PREF_BRIDGE,  ''),
      model     : prefs.get(PREF_MODEL,   ''),
      device    : prefs.get(PREF_DEVICE,  'auto'),
      patch     : prefs.get(PREF_PATCH,   '512'),
      minArea   : prefs.get(PREF_MINAREA, '15'),
      minRoi    : prefs.get(PREF_MINROI,  '512'),
      targetMpp : prefs.get(PREF_MPP,     '0.25') ]
}

// Required: python(.exe) + bridge script + model weights
def configMissing = { cfg ->
    def miss = []
    if (!cfg.python?.trim() || !(new File(cfg.python)).isFile()) miss << 'Python executable (venv python.exe)'
    if (!cfg.bridge?.trim() || !(new File(cfg.bridge)).isFile()) miss << 'Bridge script (qupath/hepatocyte_qupath_inference.py)'
    if (!cfg.model?.trim()  || !(new File(cfg.model)).isFile())  miss << 'Model weights (.pth — 🔒 available on request)'
    return miss
}
def configComplete = { cfg -> configMissing(cfg).isEmpty() }

def parseIntOr = { s, int d -> try { return Integer.parseInt((s ?: '').toString().trim()) } catch (Throwable t) { return d } }
def parseDoubleOr = { s, double d -> try { return Double.parseDouble((s ?: '').toString().replace(',', '.').trim()) } catch (Throwable t) { return d } }

// ── Resolve slide path (local file only; Python opens it with openslide) ─────
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

def imageNameOf = { imageData ->
    def nm = imageData.getServer().getMetadata().getName() ?: 'slide'
    return nm.replaceAll(/\.[^.\/\\]+$/, '')
}

def isCandidateAnnotation = { ann ->
    if (ann == null || !ann.isAnnotation() || !ann.hasROI() || !ann.getROI().isArea()) return false
    def cls = ann.getPathClass()
    return cls == null || !PREDICTION_CLASSES.contains(cls.toString())
}

// Target area annotations: selected candidate ones if any, else all candidate area annotations.
def targetAnnotations = { imageData ->
    def sel = QP.getSelectedObjects().findAll { isCandidateAnnotation(it) }
    if (!sel.isEmpty()) return sel
    return imageData.getHierarchy().getAnnotationObjects().findAll { isCandidateAnnotation(it) }
}

// ── Build request JSON (level-0 coords + WKT) ────────────────────────────────
def buildRequest = { imageData, slidePath, cfg, File reportFile ->
    def wkt = new WKTWriter()
    int minRoi = parseIntOr(cfg.minRoi, 512)
    def anns = targetAnnotations(imageData)
    def rois = []; int skippedSmall = 0
    anns.each { ann ->
        def roi = ann.getROI()
        int w = (int) Math.ceil(roi.getBoundsWidth())
        int h = (int) Math.ceil(roi.getBoundsHeight())
        if (w < minRoi || h < minRoi) { skippedSmall++; return }
        def shapeWkt = null
        try { def g = roi.getGeometry(); if (g != null) shapeWkt = wkt.write(g).toString() } catch (Throwable ignore) {}
        rois << [ id: ann.getID().toString(),
                  x: (int) Math.floor(roi.getBoundsX()),
                  y: (int) Math.floor(roi.getBoundsY()),
                  w: w, h: h, shape_wkt: shapeWkt ]
    }
    def request = [ slide_path   : slidePath,
                    model_path   : cfg.model,
                    rois         : rois,
                    patch_size   : parseIntOr(cfg.patch, 512),
                    min_area     : parseIntOr(cfg.minArea, 15),
                    min_roi_size : minRoi,
                    device       : (cfg.device ?: 'auto'),
                    target_mpp   : parseDoubleOr(cfg.targetMpp, 0.25d) ]
    if (reportFile != null) request.output_xlsx_path = reportFile.getAbsolutePath()
    return [request: request, nAnns: anns.size(), nRois: rois.size(), skippedSmall: skippedSmall]
}

def writeJson = { obj, File f ->
    if (f.getParentFile() != null) f.getParentFile().mkdirs()
    f.setText(GsonTools.getInstance(true).toJson(obj), 'UTF-8')
}

// ── Import GeoJSON (PathIO) + per-class count measurements ───────────────────
def importResult = { File outputFile, imageData, List targetAnns ->
    if (outputFile == null || !outputFile.isFile())
        return [ok: false, error: 'Output GeoJSON not found:\n' + (outputFile?.getAbsolutePath() ?: '(no path)')]
    def imported = []
    try { outputFile.withInputStream { is -> imported = PathIO.readObjectsFromGeoJSON(is) } }
    catch (Throwable t) { return [ok: false, error: 'Could not read GeoJSON:\n' + (t.getMessage() ?: t.getClass().getSimpleName())] }
    if (imported != null && !imported.isEmpty()) {
        imageData.getHierarchy().addObjects(imported)
        QP.fireHierarchyUpdate()
    }
    def countsPerRoi = [:]
    def classTotals = new TreeMap<String, Integer>()
    try {
        def reader = new java.io.InputStreamReader(new java.io.FileInputStream(outputFile), java.nio.charset.StandardCharsets.UTF_8)
        try {
            def root = JsonParser.parseReader(reader).getAsJsonObject()
            if (root.has('metadata') && root.get('metadata').isJsonObject()) {
                def md = root.getAsJsonObject('metadata')
                if (md.has('counts_per_roi') && md.get('counts_per_roi').isJsonObject()) {
                    md.getAsJsonObject('counts_per_roi').entrySet().each { e ->
                        def per = [:]
                        e.getValue().getAsJsonObject().entrySet().each { sub ->
                            int n = sub.getValue().getAsInt()
                            per[sub.getKey()] = n
                            classTotals[sub.getKey()] = (classTotals.getOrDefault(sub.getKey(), 0)) + n
                        }
                        countsPerRoi[e.getKey()] = per
                    }
                }
            }
        } finally { reader.close() }
    } catch (Throwable ignore) {}
    int annUpdated = 0
    targetAnns.each { ann ->
        def per = countsPerRoi[ann.getID().toString()]
        if (per == null) return
        int total = 0
        def ml = ann.getMeasurementList()
        per.each { cls, n -> ml.put(('Hepatocyte: ' + cls).toString(), (double) ((int) n)); total += (int) n }
        ml.put('Hepatocyte: total'.toString(), (double) total)
        annUpdated++
    }
    return [ok: true, nImported: (imported ? imported.size() : 0), classTotals: classTotals, annUpdated: annUpdated]
}

// ── Result text ──────────────────────────────────────────────────────────────
def buildResultText = { slideName, exp, imp, File reportFile ->
    def sb = new StringBuilder()
    sb << "HEPATOCYTE SEGMENTATION — SUMMARY\n"
    sb << "═════════════════════════════════\n\n"
    sb << "Slide       : " << (slideName ?: '?') << "\n"
    sb << String.format(java.util.Locale.US, "Annotations : %,d   ·   ROIs processed: %,d%n", (int) (exp?.nAnns ?: 0), (int) (exp?.nRois ?: 0))
    if ((exp?.skippedSmall ?: 0) > 0)
        sb << String.format(java.util.Locale.US, "Skipped     : %,d (bbox < min ROI, smaller than one patch)%n", (int) exp.skippedSmall)
    sb << String.format(java.util.Locale.US, "Polygons    : %,d%n", (int) (imp?.nImported ?: 0))
    sb << "\nClass distribution (cell counts):\n"
    if (imp != null && imp.ok && !imp.classTotals.isEmpty()) {
        int total = 0
        imp.classTotals.each { cn, n -> sb << String.format(java.util.Locale.US, "  %-26s : %,d%n", cn, (int) n); total += (int) n }
        sb << String.format(java.util.Locale.US, "  %-26s : %,d%n", '(total)', total)
    } else {
        sb << "  (no class counts — the model may have predicted only background here)\n"
    }
    sb << "\n'Hepatocyte: <class>' and 'Hepatocyte: total' measurements were written to each parent annotation (Measurements tab).\n"
    if (reportFile != null && reportFile.isFile())
        sb << "Excel report: " << reportFile.getAbsolutePath() << "\n"
    sb << "\nOutput is a deep-learning prediction — verify visually.\n"
    sb << "⚠️ Research and education only."
    return sb.toString()
}

// ── Headless: report configuration, no interaction ───────────────────────────
if (isHeadless) {
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { println "Open a slide first."; return }
    def cfg = loadConfig()
    def miss = configMissing(cfg)
    println "Hepatocyte config: python=${cfg.python ?: '(unset)'} bridge=${cfg.bridge ?: '(unset)'} model=${cfg.model ?: '(unset — 🔒 on request)'} device=${cfg.device}"
    if (!miss.isEmpty()) println "Missing config: ${miss.join(', ')}"
    def sp = resolveSlidePath(imageData)
    println "Slide path: ${sp ?: '(not a local file — openslide cannot open it)'}"
    println "Candidate area annotations: ${targetAnnotations(imageData).size()}"
    println "This wizard requires the QuPath GUI."
    println "⚠️ Research and education only."
    return
}

// ── State-machine fields ──────────────────────────────────────────────────────
def stage = null
def step          = new java.util.concurrent.atomic.AtomicReference('READY')
def alwaysTop     = new java.util.concurrent.atomic.AtomicBoolean(true)
def cancelledRef  = new java.util.concurrent.atomic.AtomicBoolean(false)
def selftestOkRef = new java.util.concurrent.atomic.AtomicBoolean(true)
def processRef    = new java.util.concurrent.atomic.AtomicReference(null)
def logAreaRef    = new java.util.concurrent.atomic.AtomicReference(null)
def runPhaseRef   = new java.util.concurrent.atomic.AtomicReference('')
def busyLabelRef  = new java.util.concurrent.atomic.AtomicReference('')
def resultTextRef = new java.util.concurrent.atomic.AtomicReference('')
def errorTextRef  = new java.util.concurrent.atomic.AtomicReference('')
// CONFIG edit fields
def pyFieldRef     = new java.util.concurrent.atomic.AtomicReference(null)
def bridgeFieldRef = new java.util.concurrent.atomic.AtomicReference(null)
def modelFieldRef  = new java.util.concurrent.atomic.AtomicReference(null)
def deviceChoiceRef= new java.util.concurrent.atomic.AtomicReference(null)
def patchFieldRef  = new java.util.concurrent.atomic.AtomicReference(null)
def minAreaFieldRef= new java.util.concurrent.atomic.AtomicReference(null)
def minRoiFieldRef = new java.util.concurrent.atomic.AtomicReference(null)
def mppFieldRef    = new java.util.concurrent.atomic.AtomicReference(null)
def render  // forward declaration

def navButton = { String text, Closure action, String tooltip = null ->
    def b = new javafx.scene.control.Button(text)
    b.setOnAction({ action() })
    if (tooltip) b.setTooltip(new javafx.scene.control.Tooltip(tooltip))
    return b
}
def busyBar = { -> def pb = new javafx.scene.control.ProgressBar(); pb.setProgress(-1.0); pb.setMaxWidth(Double.MAX_VALUE); return pb }
def copyToClipboard = { String txt ->
    def cb = javafx.scene.input.Clipboard.getSystemClipboard()
    def content = new javafx.scene.input.ClipboardContent(); content.putString(txt ?: ""); cb.setContent(content)
}

// ── Helper: Select hepatocyte-app folder to auto-fill paths ──────────────────
def pickRepoFolder = { ->
    def dc = new javafx.stage.DirectoryChooser()
    dc.setTitle('Select your hepatocyte-app repository folder')
    def chosen = dc.showDialog(stage)
    if (chosen != null && chosen.isDirectory()) {
        try {
            def msg = Class.forName('io.github.sbalci.qupath.hepatocyte.HepatocyteConfig')
                .getMethod('applyRepoRoot', File.class, boolean.class)
                .invoke(null, chosen, true)
            def cfg = loadConfig()
            if (pyFieldRef.get() != null) pyFieldRef.get().setText(cfg.python ?: '')
            if (bridgeFieldRef.get() != null) bridgeFieldRef.get().setText(cfg.bridge ?: '')
            if (modelFieldRef.get() != null) modelFieldRef.get().setText(cfg.model ?: '')
            Dialogs.showMessageDialog('Auto-fill from folder', msg.toString())
            step.set(configComplete(cfg) ? 'READY' : 'CONFIG')
            render()
        } catch (Throwable t) {
            Dialogs.showErrorMessage('Auto-fill error', t.getMessage() ?: t.getClass().getSimpleName())
        }
    }
}

// ── Helper: Download bridge from GitHub (no git required) ────────────────────
def downloadBridgeFromGitHub = { ->
    busyLabelRef.set('Downloading hepatocyte-app bridge from GitHub…')
    step.set('BUSY'); render()
    def worker = new Thread({
        try {
            def baseDir = new File(new File(System.getProperty('user.home'), '.hepatocyte-app'), 'repo')
            baseDir.mkdirs()
            def zipFile = new File(baseDir, 'hepatocyte-app-main.zip')
            String curUrl = HEPATO_REPO_ZIP
            int hops = 0
            while (true) {
                if (hops++ > 8) throw new RuntimeException('Too many HTTP redirects')
                def u = new java.net.URL(curUrl)
                def conn = (java.net.HttpURLConnection) u.openConnection()
                conn.setInstanceFollowRedirects(false)
                conn.setConnectTimeout(30000); conn.setReadTimeout(120000)
                conn.setRequestProperty('User-Agent', 'qupath-extension-hepatocyte')
                int code = conn.getResponseCode()
                if (code >= 300 && code < 400) {
                    def loc = conn.getHeaderField('Location'); conn.disconnect()
                    curUrl = new java.net.URL(u, loc).toString(); continue
                }
                if (code != 200) { conn.disconnect(); throw new RuntimeException('HTTP ' + code) }
                conn.getInputStream().withCloseable { ins ->
                    new java.io.FileOutputStream(zipFile).withCloseable { out -> ins.transferTo(out) }
                }
                conn.disconnect()
                break
            }
            def zf = new java.util.zip.ZipFile(zipFile)
            try {
                def rootCanonical = baseDir.getCanonicalPath() + File.separator
                def entries = zf.entries()
                while (entries.hasMoreElements()) {
                    def e = entries.nextElement()
                    def outF = new File(baseDir, e.getName())
                    if (!outF.getCanonicalPath().startsWith(rootCanonical)) continue
                    if (e.isDirectory()) { outF.mkdirs() }
                    else {
                        outF.getParentFile()?.mkdirs()
                        zf.getInputStream(e).withCloseable { ins ->
                            new java.io.FileOutputStream(outF).withCloseable { out -> ins.transferTo(out) }
                        }
                    }
                }
            } finally { zf.close(); zipFile.delete() }
            def bridgeFile = new File(new File(baseDir, HEPATO_ZIP_TOP), HEPATO_BRIDGE_SUB)
            if (!bridgeFile.isFile()) throw new RuntimeException('Bridge script not found in archive: ' + bridgeFile.getAbsolutePath())
            prefs.put(PREF_BRIDGE, bridgeFile.getAbsolutePath())
            try { prefs.flush() } catch (Throwable ignore) {}
            javafx.application.Platform.runLater {
                Dialogs.showMessageDialog('Bridge downloaded', 'Saved bridge script to:\n' + bridgeFile.getAbsolutePath())
                step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG')
                render()
            }
        } catch (Throwable t) {
            javafx.application.Platform.runLater {
                errorTextRef.set('Could not download bridge from GitHub:\n' + (t.getMessage() ?: t.getClass().getSimpleName()))
                step.set('ERROR'); render()
            }
        }
    }, 'Hepatocyte-DownloadBridge')
    worker.setDaemon(true); worker.start()
}

// ── Python process (ProcessBuilder) → line stream ────────────────────────────
def runPython = { List cmd, Closure onLine ->
    def pb = new ProcessBuilder(cmd); pb.redirectErrorStream(true)
    def proc
    try { proc = pb.start() }
    catch (Throwable e) { return [ok: false, exitCode: -1, error: 'Could not start Python: ' + (e.getMessage() ?: e.getClass().getSimpleName())] }
    processRef.set(proc)
    def last = new java.util.ArrayDeque()
    try {
        def reader = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))
        String line
        while ((line = reader.readLine()) != null) {
            last.addLast(line); while (last.size() > 60) last.pollFirst()
            onLine(line)
            if (cancelledRef.get()) break
        }
        reader.close()
    } catch (Throwable ignore) {}
    boolean finished
    try { finished = proc.waitFor(PYTHON_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS) }
    catch (InterruptedException ie) { proc.destroyForcibly(); return [ok: false, exitCode: -3, error: 'Cancelled'] }
    if (!finished) { proc.destroyForcibly(); return [ok: false, exitCode: -2, error: 'Timeout (' + PYTHON_TIMEOUT_SECONDS + ' s)'] }
    if (cancelledRef.get()) { proc.destroyForcibly(); return [ok: false, exitCode: -3, error: 'Cancelled'] }
    int code = proc.exitValue()
    return [ok: (code == 0), exitCode: code, lastLines: last.join('\n')]
}

// Dependency + model check
def selftestCmd = { cfg ->
    def code = 'import sys, os; import numpy, pandas, torch, cv2, openslide, albumentations, openpyxl, shapely; from PIL import Image; ' +
        'print("Python:", sys.executable); ' +
        'print("PyTorch:", torch.__version__, "CUDA:", torch.cuda.is_available()); ' +
        'print("deps OK"); ' +
        'm = sys.argv[1] if len(sys.argv) > 1 else ""; ' +
        'torch.jit.load(m, map_location="cpu") if (m and os.path.isfile(m)) else print("model UNSET (dependencies verified only)"); ' +
        'print("SELFTEST OK")'
    return [cfg.python, '-c', code, (cfg.model ?: '')]*.toString()
}

// ── Write CONFIG fields to prefs ─────────────────────────────────────────────
def textOf = { ref -> def f = ref.get(); return (f != null ? f.getText() : '').trim() }
def persistFields = {
    if (pyFieldRef.get() != null)     prefs.put(PREF_PYTHON,  textOf(pyFieldRef))
    if (bridgeFieldRef.get() != null) prefs.put(PREF_BRIDGE,  textOf(bridgeFieldRef))
    if (modelFieldRef.get() != null)  prefs.put(PREF_MODEL,   textOf(modelFieldRef))
    def dv = deviceChoiceRef.get()
    if (dv != null && dv.getValue() != null) prefs.put(PREF_DEVICE, dv.getValue().toString())
    if (patchFieldRef.get() != null)   prefs.put(PREF_PATCH,   textOf(patchFieldRef)   ?: '512')
    if (minAreaFieldRef.get() != null) prefs.put(PREF_MINAREA, textOf(minAreaFieldRef) ?: '15')
    if (minRoiFieldRef.get() != null)  prefs.put(PREF_MINROI,  textOf(minRoiFieldRef)  ?: '512')
    if (mppFieldRef.get() != null)     prefs.put(PREF_MPP,     (textOf(mppFieldRef) ?: '0.25').replace(',', '.'))
    try { prefs.flush() } catch (Throwable ignore) {}
}

// ── Dependency check ──────────────────────────────────────────────────────────
def startSelftest = {
    persistFields()
    def cfg = loadConfig()
    if (!cfg.python?.trim() || !(new File(cfg.python)).isFile()) {
        errorTextRef.set('Set the Python executable first (Configure).'); step.set('ERROR'); render(); return
    }
    cancelledRef.set(false)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO)
    logAreaRef.set(la)
    runPhaseRef.set('Dependency check'); step.set('CHECK_RUNNING'); render()
    def worker = new Thread({
        def appendLine = { String ln -> javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        def r = runPython(selftestCmd(cfg), appendLine)
        javafx.application.Platform.runLater { selftestOkRef.set(r.ok); step.set('CHECK_DONE'); render() }
    }, 'Hepatocyte-Check')
    worker.setDaemon(true); worker.start()
}

// ── Segmentation flow ─────────────────────────────────────────────────────────
def startRun = {
    def imageData = QP.getCurrentImageData()
    if (imageData == null) { errorTextRef.set('No image open.'); step.set('ERROR'); render(); return }
    def cfg = loadConfig()
    def slidePath = resolveSlidePath(imageData)
    if (slidePath == null) {
        errorTextRef.set('The slide is not a local file — Python (openslide) cannot open it.\nOpen a local SVS/TIFF slide.'); step.set('ERROR'); render(); return
    }
    def project = QP.getProject()
    File workDir; File reportFile = null
    if (project != null && project.getPath() != null) {
        def projDir = project.getPath().getParent().toFile()
        workDir = new File(projDir, 'hepatocyte_tmp')
        def stamp = new java.text.SimpleDateFormat('yyyyMMdd_HHmmss').format(new Date())
        def stem = imageNameOf(imageData).replaceAll('[\\\\/:*?"<>|]', '_')
        def repDir = new File(new File(projDir, 'reports'), stem); repDir.mkdirs()
        reportFile = new File(repDir, stamp + '.xlsx')
    } else {
        workDir = new File(System.getProperty('java.io.tmpdir'), 'hepatocyte_tmp')
    }
    workDir.mkdirs()
    def runId = UUID.randomUUID().toString().take(8)
    def requestFile = new File(workDir, 'hepatocyte_request_' + runId + '.json')
    def outputFile  = new File(workDir, 'hepatocyte_result_' + runId + '.geojson')

    def built = buildRequest(imageData, slidePath, cfg, reportFile)
    if (built.nRois == 0) {
        errorTextRef.set('No candidate area annotation to segment, or all are too small (bbox < ' + cfg.minRoi + ' px = one patch).\nDraw at least one area annotation ≥ ' + cfg.minRoi + '×' + cfg.minRoi + ' px (or run 4. Auto-detect tissue).'); step.set('ERROR'); render(); return
    }
    def targetAnns = targetAnnotations(imageData)
    try { writeJson(built.request, requestFile) }
    catch (Throwable t) { errorTextRef.set('Could not write request:\n' + (t.getMessage() ?: t.getClass().getSimpleName())); step.set('ERROR'); render(); return }

    cancelledRef.set(false)
    def la = new javafx.scene.control.TextArea(); la.setEditable(false); la.setWrapText(false); la.setStyle(MONO)
    logAreaRef.set(la)
    runPhaseRef.set('Hepatocyte segmentation running…'); step.set('RUN_RUNNING'); render()
    def worker = new Thread({
        def appendLine = { String ln -> javafx.application.Platform.runLater { def a = logAreaRef.get(); if (a != null) a.appendText(ln + '\n') } }
        appendLine('Slide: ' + slidePath)
        appendLine('ROIs : ' + built.nRois + (built.skippedSmall > 0 ? ('  (skipped small: ' + built.skippedSmall + ')') : ''))
        def cmd = [cfg.python, cfg.bridge, requestFile.getAbsolutePath(), outputFile.getAbsolutePath()]*.toString()
        def r = runPython(cmd, appendLine)
        if (!r.ok) {
            javafx.application.Platform.runLater { errorTextRef.set('Segmentation failed (exit: ' + r.exitCode + ')\n' + (r.error ?: '') + '\n' + (r.lastLines ?: '')); step.set('ERROR'); render() }; return
        }
        javafx.application.Platform.runLater { busyLabelRef.set('Importing results into QuPath…'); step.set('BUSY'); render() }
        def imp = importResult(outputFile, QP.getCurrentImageData(), targetAnns)
        try { requestFile.delete(); outputFile.delete() } catch (Throwable ignore) {}
        javafx.application.Platform.runLater {
            try { gui.getViewer()?.repaintEntireImage() } catch (Throwable ignore) {}
            if (!imp.ok) { errorTextRef.set(imp.error); step.set('ERROR'); render() }
            else { resultTextRef.set(buildResultText(imageNameOf(imageData), built, imp, reportFile)); step.set('RESULT'); render() }
        }
    }, 'Hepatocyte-Run')
    worker.setDaemon(true); worker.start()
}

// ── Render: rebuild the scene on every state change ──────────────────────────
render = { ->
    if (stage == null) return
    stage.setAlwaysOnTop(alwaysTop.get())
    def cur = step.get()
    def imageData = QP.getCurrentImageData()
    def cfg = loadConfig()

    def title = new javafx.scene.control.Label()
    title.setStyle('-fx-font-size: 15px; -fx-font-weight: bold;')
    def center = new javafx.scene.layout.VBox(10)
    center.setPadding(new javafx.geometry.Insets(14))
    center.getChildren().add(title)
    def actions = new ArrayList()

    def addGuidance = { String txt -> def lbl = new javafx.scene.control.Label(txt); lbl.setWrapText(true); lbl.setMaxWidth(Double.MAX_VALUE); center.getChildren().add(lbl) }
    def addMonoArea = { String txt ->
        def ta = new javafx.scene.control.TextArea(txt ?: ''); ta.setEditable(false); ta.setWrapText(false); ta.setStyle(MONO)
        javafx.scene.layout.VBox.setVgrow(ta, javafx.scene.layout.Priority.ALWAYS); center.getChildren().add(ta)
    }
    def addWarnLabel = { String txt ->
        def lbl = new javafx.scene.control.Label(txt); lbl.setWrapText(true); lbl.setMaxWidth(Double.MAX_VALUE)
        lbl.setStyle('-fx-text-fill: #b85c00; -fx-font-weight: bold;'); center.getChildren().add(lbl)
    }
    def addLiveLog = { -> def la = logAreaRef.get(); if (la != null) { javafx.scene.layout.VBox.setVgrow(la, javafx.scene.layout.Priority.ALWAYS); center.getChildren().add(la) } }

    if (cur == 'CONFIG_INCOMPLETE') {
        title.setText('First-Time Setup Required')
        def miss = configMissing(cfg)
        addGuidance('Point the wizard at your local hepatocyte-app folder (or configure the 3 paths individually).\n\nMissing / invalid:\n  • ' + (miss.isEmpty() ? '(none)' : miss.join('\n  • ')))

        def quickBox = new javafx.scene.layout.HBox(10,
            navButton('📁 Select hepatocyte-app folder (auto-fill)…', { pickRepoFolder() }, 'Pick your hepatocyte-app folder to auto-fill Python, bridge, and model paths'),
            navButton('⬇ Download bridge from GitHub', { downloadBridgeFromGitHub() }, 'Download hepatocyte_qupath_inference.py from GitHub without git')
        )
        center.getChildren().add(quickBox)

        def resArea = new javafx.scene.control.TextArea(
            'CURRENT STATUS:\n' +
            '① Python venv : ' + (cfg.python?.trim() && new File(cfg.python).isFile() ? ('✅ ' + cfg.python) : '⚠ (not set) — e.g. <repo>/venv/Scripts/python.exe') + '\n' +
            '② Bridge (.py): ' + (cfg.bridge?.trim() && new File(cfg.bridge).isFile() ? ('✅ ' + cfg.bridge) : '⚠ (not set) — <repo>/qupath/hepatocyte_qupath_inference.py') + '\n' +
            '③ Model (.pth): ' + (cfg.model?.trim()  && new File(cfg.model).isFile()  ? ('✅ ' + cfg.model)  : '⚠ (not set — 🔒 on request) — baseline-1-jit.pth (~180 MB)') + '\n\n' +
            'Repository: https://github.com/sbalci/hepatocyte-app')
        resArea.setEditable(false); resArea.setWrapText(false); resArea.setStyle(MONO); resArea.setPrefRowCount(7); resArea.setMaxHeight(165)
        center.getChildren().add(resArea)
        actions.add(navButton('Close', { stage.close() }))
        actions.add(navButton('Configure paths manually ▶', { step.set('CONFIG'); render() }))
    } else if (cur == 'CONFIG') {
        title.setText('Hepatocyte Configuration')
        def quickBox = new javafx.scene.layout.HBox(10,
            navButton('📁 Select hepatocyte-app folder (auto-fill)…', { pickRepoFolder() }),
            navButton('⬇ Download bridge from GitHub', { downloadBridgeFromGitHub() })
        )
        center.getChildren().add(quickBox)

        def grid = new javafx.scene.layout.GridPane(); grid.setHgap(8); grid.setVgap(8)
        def pyField = new javafx.scene.control.TextField(cfg.python ?: '')
        def brField = new javafx.scene.control.TextField(cfg.bridge ?: '')
        def mdField = new javafx.scene.control.TextField(cfg.model ?: '')
        [pyField, brField, mdField].each { it.setPrefColumnCount(34) }
        def deviceChoice = new javafx.scene.control.ChoiceBox()
        DEVICE_OPTIONS.each { deviceChoice.getItems().add(it) }
        deviceChoice.setValue(DEVICE_OPTIONS.contains(cfg.device) ? cfg.device : 'auto')
        def psField = new javafx.scene.control.TextField(cfg.patch ?: '512')
        def maField = new javafx.scene.control.TextField(cfg.minArea ?: '15')
        def mrField = new javafx.scene.control.TextField(cfg.minRoi ?: '512')
        def mpField = new javafx.scene.control.TextField(cfg.targetMpp ?: '0.25')
        [psField, maField, mrField, mpField].each { it.setPrefColumnCount(8) }
        pyFieldRef.set(pyField); bridgeFieldRef.set(brField); modelFieldRef.set(mdField); deviceChoiceRef.set(deviceChoice)
        patchFieldRef.set(psField); minAreaFieldRef.set(maField); minRoiFieldRef.set(mrField); mppFieldRef.set(mpField)
        def browseFile = { f, cap -> def x = qupath.fx.dialogs.FileChoosers.promptForFile(stage, cap); if (x != null) f.setText(x.getAbsolutePath()) }
        int row = 0
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Python (python.exe):'), pyField, navButton('Browse…', { browseFile(pyField, 'Select python.exe') }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Bridge (hepatocyte_qupath_inference.py):'), brField, navButton('Browse…', { browseFile(brField, 'Select bridge script') }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Model (.pth — 🔒):'), mdField, navButton('Browse…', { browseFile(mdField, 'Select model weights (.pth)') }))
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Device:'), deviceChoice)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Patch size (px):'), psField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Min polygon area (px²):'), maField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Min ROI side (px):'), mrField)
        qupath.fx.utils.GridPaneUtils.addGridRow(grid, row++, 0, null, new javafx.scene.control.Label('Target MPP (µm/px):'), mpField)
        center.getChildren().add(grid)
        addGuidance('Defaults: Patch 512 px, Min ROI 512 px (1 patch), Target MPP 0.25 µm/px (40× scale; 20× slides are rescaled automatically). Device "auto" uses NVIDIA CUDA GPU when available.')
        actions.add(navButton('Cancel', { step.set(configComplete(cfg) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
        actions.add(navButton('✓ Check Environment', { startSelftest() }, 'Verify Python packages and TorchScript model loading'))
        actions.add(navButton('Save ▶', { persistFields(); step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
    } else if (cur == 'CHECK_RUNNING') {
        title.setText('Environment check running…')
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('Cancel', { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {} }))
    } else if (cur == 'CHECK_DONE') {
        title.setText(selftestOkRef.get() ? 'Environment check passed ✅' : '⚠ Environment check FAILED — see log below')
        addLiveLog()
        actions.add(navButton('◀ Back to configuration', { step.set('CONFIG'); render() }))
        if (selftestOkRef.get() && configComplete(loadConfig())) {
            actions.add(navButton('Continue ▶', { step.set('READY'); render() }))
        }
        actions.add(navButton('Close', { stage.close() }))
    } else if (cur == 'READY') {
        if (imageData == null) {
            title.setText('No slide open')
            addGuidance('Open a local liver H&E slide in QuPath and draw an area annotation (or run "4. Auto-detect tissue"), then click "⟳ Refresh".')
            actions.add(navButton('Close', { stage.close() }))
            actions.add(navButton('Configure…', { step.set('CONFIG'); render() }))
            actions.add(navButton('⟳ Refresh', { render() }))
        } else {
            def slidePath = resolveSlidePath(imageData)
            def anns = targetAnnotations(imageData)
            int minRoi = parseIntOr(cfg.minRoi, 512)
            int usable = anns.count { def r = it.getROI(); (int) Math.ceil(r.getBoundsWidth()) >= minRoi && (int) Math.ceil(r.getBoundsHeight()) >= minRoi }
            title.setText('Hepatocyte Segmentation — Ready')
            def sb = new StringBuilder()
            sb << "Slide                 : " << imageNameOf(imageData) << "\n"
            sb << "Slide path            : " << (slidePath ?: '(not a local file — openslide cannot open it)') << "\n"
            sb << "Python                : " << (cfg.python ?: '(unset)') << "\n"
            sb << "Model                 : " << (cfg.model ?: '(unset — 🔒 on request)') << "\n"
            sb << "Device                : " << cfg.device << "\n"
            sb << String.format(java.util.Locale.US, "Candidate annotations : %,d   ·   usable (≥%d×%d px): %,d%n", anns.size(), minRoi, minRoi, usable)
            addMonoArea(sb.toString())
            addGuidance('Selected area annotations are processed (or all non-prediction area annotations if none is selected). Output is classified cell polygons + per-class counts.')
            if (slidePath == null) addWarnLabel('⚠ The slide is not a local file — Python (openslide) cannot open remote/OMERO slides. Open a local SVS/TIFF slide.')
            if (usable == 0) addWarnLabel('⚠ Draw at least one area annotation ≥ ' + minRoi + '×' + minRoi + ' px (Rectangle, Polygon, or Wand), or run "4. Auto-detect tissue (current slide)", then click "⟳ Refresh".')
            boolean canRun = configComplete(cfg) && (slidePath != null) && usable >= 1
            actions.add(navButton('Close', { stage.close() }))
            actions.add(navButton('Configure…', { step.set('CONFIG'); render() }))
            actions.add(navButton('⟳ Refresh', { render() }))
            def runBtn = navButton('Segment ▶', { startRun() }, 'Run the hepatocyte segmentation model on the candidate area annotations')
            runBtn.setDisable(!canRun)
            actions.add(runBtn)
        }
    } else if (cur == 'RUN_RUNNING') {
        title.setText(runPhaseRef.get())
        addGuidance('The Python bridge is running (opens slide with openslide, runs TorchScript model + Watershed separation). Live progress streams below.')
        center.getChildren().add(busyBar()); addLiveLog()
        actions.add(navButton('Cancel', { cancelledRef.set(true); try { processRef.get()?.destroyForcibly() } catch (Throwable ignore) {} }))
    } else if (cur == 'BUSY') {
        title.setText(busyLabelRef.get()); addGuidance('Please wait…'); center.getChildren().add(busyBar())
    } else if (cur == 'RESULT') {
        title.setText('Segmentation Complete ✅')
        addMonoArea(resultTextRef.get())
        actions.add(navButton('Close', { stage.close() }))
        actions.add(navButton('Copy Summary', { copyToClipboard(resultTextRef.get()) }))
        actions.add(navButton('↻ Run Again', { step.set('READY'); render() }))
    } else { // ERROR
        title.setText('Error')
        addMonoArea(errorTextRef.get())
        actions.add(navButton('◀ Back', { step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE'); render() }))
        actions.add(navButton('Configure…', { step.set('CONFIG'); render() }))
        actions.add(navButton('Close', { stage.close() }))
    }

    // Bottom bar: "Keep on top" (left) + disclaimer + action buttons (right)
    def topChk = new javafx.scene.control.CheckBox('Keep on top')
    topChk.setSelected(alwaysTop.get())
    topChk.selectedProperty().addListener({ obs, o, n -> alwaysTop.set(n); if (stage != null) stage.setAlwaysOnTop(n) } as javafx.beans.value.ChangeListener)
    def spacer = new javafx.scene.layout.Region()
    javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS)
    def bar = new javafx.scene.layout.HBox(8)
    bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT)
    bar.getChildren().add(topChk); bar.getChildren().add(spacer); bar.getChildren().addAll(actions)

    def disclaimer = new javafx.scene.control.Label('Produces research/education measurements only; not for clinical diagnosis.')
    disclaimer.setWrapText(true); disclaimer.setMaxWidth(Double.MAX_VALUE)
    disclaimer.setStyle('-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.6; -fx-font-style: italic; -fx-padding: 4 2 4 2; -fx-font-size: 11px;')
    def bottom = new javafx.scene.layout.VBox(8, disclaimer, bar)
    bottom.setPadding(new javafx.geometry.Insets(10))

    def root = new javafx.scene.layout.BorderPane()
    root.setCenter(center); root.setBottom(bottom)
    stage.setScene(new javafx.scene.Scene(root, 880, 640))
}

// ── Opening state ─────────────────────────────────────────────────────────────
step.set(configComplete(loadConfig()) ? 'READY' : 'CONFIG_INCOMPLETE')

javafx.application.Platform.runLater {
    try {
        stage = new javafx.stage.Stage()
        stage.initModality(javafx.stage.Modality.NONE)
        stage.setTitle('Hepatocyte Segmentation')
        stage.setAlwaysOnTop(alwaysTop.get())
        render()
        stage.show()
    } catch (Throwable t) {
        Dialogs.showErrorMessage('Could not open the wizard', t.getClass().getSimpleName() + ': ' + (t.getMessage() ?: ''))
    }
}
println "✓ Hepatocyte segmentation wizard opened."
