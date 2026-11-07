/**
 * detect-tissue-project.groovy — Auto-detect tissue across all slides in the project
 * ----------------------------------------------------------------------------------
 * Bundled in qupath-extension-hepatocyte; launched from
 * Extensions → Hepatocyte → "5. Auto-detect tissue (whole project)…".
 *
 * WHAT IT DOES:
 *   Iterates every slide in the open QuPath project and runs QuPath's built-in
 *   SimpleTissueDetection2 with a per-slide MPP-aware minimum tissue area
 *   (always ≥ 1 model patch). Preserves any existing user annotations on each
 *   slide and classifies newly detected regions as "Tissue".
 *
 * ⚠️ Research and education use only.
 */

import qupath.lib.gui.QuPathGUI
import qupath.lib.gui.scripting.QPEx
import qupath.lib.objects.classes.PathClass
import qupath.lib.scripting.QP
import qupath.fx.dialogs.Dialogs
import javafx.application.Platform
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

def imageList = project.getImageList()
if (imageList.isEmpty()) {
    Platform.runLater {
        Dialogs.showMessageDialog("Empty project",
            "This project has no slides yet. Run '1. Import slides from folder…' first.")
    }
    return
}

def confirmed = new AtomicBoolean(false)
def latch = new CountDownLatch(1)
Platform.runLater {
    try {
        confirmed.set(Dialogs.showConfirmDialog("Auto-detect tissue (whole project)",
            "Run automatic tissue detection on all " + imageList.size() + " slide(s) in this project?\n\n" +
            "• Each slide uses an MPP-aware minimum area (≥ 1 model patch).\n" +
            "• Existing user-drawn annotations are preserved.\n" +
            "• Detected regions are classified as 'Tissue' for batch inference.\n\n" +
            "Continue?"))
    } finally { latch.countDown() }
}
latch.await()
if (!confirmed.get()) {
    println "Whole-project tissue detection cancelled."
    return
}

def prefs = java.util.prefs.Preferences.userRoot().node('io/github/sbalci/qupath/hepatocyte')
def parseIntOr = { s, int d -> try { return Integer.parseInt((s ?: '').toString().trim()) } catch (Throwable t) { return d } }
def parseDoubleOr = { s, double d -> try { return Double.parseDouble((s ?: '').toString().replace(',', '.').trim()) } catch (Throwable t) { return d } }

int minRoiSize       = parseIntOr(prefs.get('minRoi', '512'), 512)
int threshold        = parseIntOr(prefs.get('tissueThreshold', '220'), 220)
double pixelSizeUm   = parseDoubleOr(prefs.get('tissuePixelSize', '20.0'), 20.0d)
double minAreaUm2Cfg = parseDoubleOr(prefs.get('tissueMinArea', '10000.0'), 10000.0d)
double maxHoleUm2    = parseDoubleOr(prefs.get('tissueMaxHole', '1000000.0'), 1000000.0d)
def tissueClass      = PathClass.fromString("Tissue")

long t0 = System.currentTimeMillis()
int processed = 0, totalNew = 0, totalRestored = 0, errorCount = 0
def successfulEntries = [] as Set

for (int i = 0; i < imageList.size(); i++) {
    def entry = imageList[i]
    println String.format(java.util.Locale.US, "[%d/%d] %s", i + 1, imageList.size(), entry.getImageName())
    try {
        def imgData = entry.readImageData()
        QPEx.setBatchProjectAndImage(project, imgData)

        double effMinArea = minAreaUm2Cfg
        try {
            def cal = imgData.getServer().getPixelCalibration()
            double mpp = cal.getAveragedPixelSizeMicrons()
            if (mpp > 0) {
                double sideUm = minRoiSize * mpp
                effMinArea = Math.max(minAreaUm2Cfg, sideUm * sideUm)
            }
        } catch (Throwable ignore) {}

        def paramJson = String.format(java.util.Locale.US,
            '{"threshold": %d, "requestedPixelSizeMicrons": %.2f, "minAreaMicrons": %.2f, ' +
            '"maxHoleAreaMicrons": %.2f, "darkBackground": false, "smoothImage": true, ' +
            '"medianCleanup": true, "dilateBoundaries": false, "smoothCoordinates": true, ' +
            '"excludeOnBoundary": false, "singleAnnotation": false}',
            threshold, pixelSizeUm, effMinArea, maxHoleUm2)

        def hierarchy = imgData.getHierarchy()
        def existingAnnotations = new ArrayList(hierarchy.getAnnotationObjects())
        def existingSet = existingAnnotations as Set

        QPEx.runPlugin('qupath.imagej.detect.tissue.SimpleTissueDetection2', imgData, paramJson)

        def afterAnnotations = new ArrayList(hierarchy.getAnnotationObjects())
        def afterSet = afterAnnotations as Set
        def newAnnotations = afterAnnotations.findAll { !existingSet.contains(it) }
        def deletedAnnotations = existingAnnotations.findAll { !afterSet.contains(it) }

        int restored = 0
        if (!deletedAnnotations.isEmpty()) {
            hierarchy.addObjects(deletedAnnotations)
            restored = deletedAnnotations.size()
        }

        for (def ann : newAnnotations) {
            if (ann.getROI() != null && ann.getPathClass() == null) {
                ann.setPathClass(tissueClass)
            }
        }
        entry.saveImageData(imgData)
        successfulEntries << entry
        processed++
        totalNew += newAnnotations.size()
        totalRestored += restored
        println String.format(java.util.Locale.US, "  -> %d new tissue region(s), %d restored", newAnnotations.size(), restored)
    } catch (Throwable t) {
        println "  ERROR: " + (t.getMessage() ?: t.getClass().getSimpleName())
        errorCount++
    }
}
project.syncChanges()

double elapsed = (System.currentTimeMillis() - t0) / 1000.0d
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
    Dialogs.showMessageDialog("Whole-project tissue detection complete",
        String.format(java.util.Locale.US,
            "Slides processed    : %d / %d\n" +
            "Tissue regions added: %d\n" +
            (totalRestored > 0 ? "Annotations restored: " + totalRestored + "\n" : "") +
            (errorCount > 0 ? "Slide errors        : " + errorCount + "\n" : "") +
            "Elapsed time        : %.1f s\n\n" +
            "Next step: run '3. Run inference on whole project…' to segment all tissue regions.",
            processed, imageList.size(), totalNew, elapsed))
}

