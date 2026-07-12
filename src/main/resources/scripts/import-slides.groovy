/**
 * import-slides.groovy — Import slides from a folder into the QuPath project
 * ------------------------------------------------------------------------
 * Bundled in qupath-extension-hepatocyte; launched from
 * Extensions → Hepatocyte → "1. Import slides from folder…".
 *
 * WHAT IT DOES:
 *   1. Prompts for a folder (defaults to the "Slides root" configured in
 *      Extensions → Hepatocyte → Settings…).
 *   2. Recursively finds every *.svs under it.
 *   3. Adds the new ones to the open project (skips slides already present,
 *      matched by image name — idempotent).
 *   4. Sets every project image to brightfield H&E so QuPath uses H&E colour
 *      deconvolution (already-H&E images are skipped).
 *
 * Combines the standalone add_images_to_project.groovy + set_all_images_as_HE.groovy
 * from the hepatocyte-app repo. Runs from the extension's GroovyShell (no
 * project-script context), so it talks to QuPathGUI directly.
 */

import qupath.lib.gui.QuPathGUI
import qupath.lib.images.servers.ImageServerProvider
import qupath.lib.images.ImageData
import qupath.fx.dialogs.Dialogs

import javafx.application.Platform
import javafx.stage.DirectoryChooser

import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.Collectors

def gui = QuPathGUI.getInstance()
if (gui == null) {
    println "ERROR: no QuPath GUI — run this from within QuPath."
    return
}

def project = gui.getProject()
if (project == null) {
    Platform.runLater {
        Dialogs.showErrorMessage("No project open",
            "Open a QuPath project first (File → Project → Create/Open project…), then import slides.")
    }
    return
}

// Shared preference node — MUST match HepatocyteConfig.NODE in the Java code.
def prefs = java.util.prefs.Preferences.userRoot().node('io/github/sbalci/qupath/hepatocyte')
def slidesRoot = prefs.get('slidesRoot', '')

// --- pick the folder on the FX thread, block until chosen -------------------
def folderRef = new AtomicReference(null)
def latch = new CountDownLatch(1)
Platform.runLater {
    try {
        def dc = new DirectoryChooser()
        dc.setTitle("Pick a folder of .svs slides (searched recursively)")
        def init = (slidesRoot?.trim()) ? new File(slidesRoot.trim()) : null
        if (init != null && init.isDirectory()) dc.setInitialDirectory(init)
        folderRef.set(dc.showDialog(gui.getStage()))
    } finally { latch.countDown() }
}
latch.await()
def folder = folderRef.get()
if (folder == null) {
    println "Import cancelled (no folder chosen)."
    return
}
if (!folder.isDirectory()) {
    Platform.runLater { Dialogs.showErrorMessage("Not a folder", "Not a directory:\n" + folder.getAbsolutePath()) }
    return
}
// Remember the chosen root for next time.
prefs.put('slidesRoot', folder.getAbsolutePath()); try { prefs.flush() } catch (Throwable ignore) {}

// --- add slides -------------------------------------------------------------
def existingNames = project.getImageList().collect { it.getImageName() } as Set
def files = Files.walk(Paths.get(folder.toURI()))
    .filter { Files.isRegularFile(it) }
    .filter { it.toString().toLowerCase().endsWith(".svs") }
    .collect(Collectors.toList())

println "Source folder : " + folder.getAbsolutePath()
println "Found         : " + files.size() + " .svs file(s)"
println "In project    : " + existingNames.size() + " image(s) already"

int added = 0, skipped = 0, errors = 0
for (Path path : files) {
    File file = path.toFile()
    String name = file.getName()
    if (existingNames.contains(name)) { println "  SKIP (already in project): " + name; skipped++; continue }
    try {
        def uri = file.toURI()
        def support = ImageServerProvider.getPreferredUriImageSupport(BufferedImage.class, uri.toString())
        if (support == null) { println "  ERROR (no image support): " + name; errors++; continue }
        def builders = support.getBuilders()
        if (builders.isEmpty()) { println "  ERROR (no builders): " + name; errors++; continue }
        def entry = project.addImage(builders.get(0))
        entry.setImageName(name)
        println "  ADDED: " + name
        added++
    } catch (Exception e) {
        println "  ERROR (" + name + "): " + e.getMessage()
        errors++
    }
}
project.syncChanges()

// --- set every image to brightfield H&E -------------------------------------
int heUpdated = 0, heSkipped = 0, heErrors = 0
project.getImageList().eachWithIndex { entry, i ->
    try {
        def imageData = entry.readImageData()
        if (imageData.getImageType() != ImageData.ImageType.BRIGHTFIELD_H_E) {
            imageData.setImageType(ImageData.ImageType.BRIGHTFIELD_H_E)
            entry.saveImageData(imageData)
            heUpdated++
        } else {
            heSkipped++
        }
    } catch (Exception e) {
        println "  H&E ERROR (" + entry.getImageName() + "): " + e.getMessage()
        heErrors++
    }
}

def summary = new StringBuilder()
summary << "IMPORT SLIDES — SUMMARY\n"
summary << "═══════════════════════\n\n"
summary << "Folder            : " << folder.getAbsolutePath() << "\n"
summary << ".svs found        : " << files.size() << "\n"
summary << "Added to project  : " << added << "\n"
summary << "Skipped (existing): " << skipped << "\n"
summary << "Add errors        : " << errors << "\n\n"
summary << "Set to H&E        : " << heUpdated << "\n"
summary << "Already H&E       : " << heSkipped << "\n"
summary << "H&E errors        : " << heErrors << "\n"
println ""
println summary.toString()

Platform.runLater {
    try { gui.refreshProject() } catch (Throwable t) { println "WARNING: refreshProject() failed: " + t.message }
    Dialogs.showMessageDialog("Import complete", summary.toString())
}
