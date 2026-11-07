/**
 * detect-tissue-slide.groovy — Auto-detect tissue on the currently open slide
 * ---------------------------------------------------------------------------
 * Bundled in qupath-extension-hepatocyte; launched from
 * Extensions → Hepatocyte → "4. Auto-detect tissue (current slide)".
 *
 * WHAT IT DOES:
 *   Runs QuPath's built-in SimpleTissueDetection2 on the active slide using an
 *   MPP-aware minimum tissue area (always at least one model patch in physical
 *   micrometers: e.g. 16,384 µm² on 40× slides, 65,536 µm² on 20× slides).
 *   Pre-existing annotations are preserved and newly detected regions are
 *   assigned the "Tissue" PathClass so whole-project or single-slide inference
 *   can process them directly.
 *
 * ⚠️ Research and education use only.
 */

import qupath.lib.gui.QuPathGUI
import qupath.lib.gui.scripting.QPEx
import qupath.lib.objects.classes.PathClass
import qupath.lib.scripting.QP
import qupath.fx.dialogs.Dialogs
import javafx.application.Platform

def imageData = QP.getCurrentImageData()
if (imageData == null) {
    Platform.runLater {
        Dialogs.showErrorMessage("No slide open",
            "Open a slide from the Project panel before running tissue detection.")
    }
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

double effectiveMinArea = minAreaUm2Cfg
try {
    def cal = imageData.getServer().getPixelCalibration()
    double mpp = cal.getAveragedPixelSizeMicrons()
    if (mpp > 0) {
        double sideUm = minRoiSize * mpp
        effectiveMinArea = Math.max(minAreaUm2Cfg, sideUm * sideUm)
    }
} catch (Throwable ignore) {}

def paramJson = String.format(java.util.Locale.US,
    '{"threshold": %d, "requestedPixelSizeMicrons": %.2f, "minAreaMicrons": %.2f, ' +
    '"maxHoleAreaMicrons": %.2f, "darkBackground": false, "smoothImage": true, ' +
    '"medianCleanup": true, "dilateBoundaries": false, "smoothCoordinates": true, ' +
    '"excludeOnBoundary": false, "singleAnnotation": false}',
    threshold, pixelSizeUm, effectiveMinArea, maxHoleUm2)

println "=== Auto-detecting tissue (current slide) ==="
println "Slide : " + imageData.getServer().getMetadata().getName()
println "Params: " + paramJson

def hierarchy = imageData.getHierarchy()
def existingAnnotations = new ArrayList(hierarchy.getAnnotationObjects())
def existingSet = existingAnnotations as Set

try {
    QPEx.runPlugin('qupath.imagej.detect.tissue.SimpleTissueDetection2', imageData, paramJson)
} catch (Throwable t) {
    Platform.runLater {
        Dialogs.showErrorMessage("Tissue detection failed", t.getMessage() ?: t.getClass().getSimpleName())
    }
    return
}

def afterAnnotations = new ArrayList(hierarchy.getAnnotationObjects())
def afterSet = afterAnnotations as Set
def newAnnotations = afterAnnotations.findAll { !existingSet.contains(it) }
def deletedAnnotations = existingAnnotations.findAll { !afterSet.contains(it) }

int restored = 0
if (!deletedAnnotations.isEmpty()) {
    hierarchy.addObjects(deletedAnnotations)
    restored = deletedAnnotations.size()
}

def tissueClass = PathClass.fromString("Tissue")
int classified = 0
for (def ann : newAnnotations) {
    if (ann.getROI() != null && ann.getPathClass() == null) {
        ann.setPathClass(tissueClass)
        classified++
    }
}
QP.fireHierarchyUpdate()

println String.format(java.util.Locale.US,
    "Created %d tissue annotation(s) (classified %d as 'Tissue', restored %d existing).",
    newAnnotations.size(), classified, restored)

Platform.runLater {
    try { QuPathGUI.getInstance()?.getViewer()?.repaintEntireImage() } catch (Throwable ignore) {}
    Dialogs.showMessageDialog("Tissue detection complete",
        String.format(java.util.Locale.US,
            "Created %d tissue region(s) (≥ 1 model patch: %.0f µm²).\n" +
            "Classified %d region(s) as 'Tissue'.\n" +
            (restored > 0 ? "Preserved %d pre-existing annotation(s).\n" : "") +
            "\nNext step: run '2. Run inference on selected annotations…' or\n" +
            "'3. Run inference on whole project…'.",
            newAnnotations.size(), effectiveMinArea, classified, restored))
}

