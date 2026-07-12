/*
 * QuPath Hepatocyte Extension
 *
 * Registers a top-level "Hepatocyte" menu under QuPath's Extensions menu, with
 * one item per workflow step. Each runnable item loads a bundled Groovy script
 * (from classpath resources under /scripts/) and evaluates it in QuPath's
 * Groovy runtime on a background thread.
 *
 * The extension is a packaging + distribution layer around the hepatocyte-app
 * QuPath workflow (https://github.com/sbalci/hepatocyte-app). The actual model
 * inference runs OUTSIDE QuPath: a bundled wizard writes a request JSON, calls
 * the repo's Python bridge (hepatocyte_qupath_inference.py) in the project's
 * venv, and imports the returned GeoJSON polygons back as classified
 * annotations. Point the extension at your local checkout via Settings.
 *
 * Modeled after https://github.com/qupath/qupath-extension-template and the
 * sibling qupath-extension-workshop.
 */
package io.github.sbalci.qupath.hepatocyte;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.zip.ZipException;

import javafx.application.Platform;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;

import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.GeneralTools;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;

/**
 * Main entry point for the Hepatocyte extension.
 *
 * Discovered by QuPath via the
 * {@code META-INF/services/qupath.lib.gui.extensions.QuPathExtension} resource
 * file (ServiceLoader mechanism).
 */
public class HepatocyteExtension implements QuPathExtension, GitHubProject {

    private static final Logger logger = LoggerFactory.getLogger(HepatocyteExtension.class);

    private static final String MENU_PATH = "Extensions>Hepatocyte";
    private static final String SCRIPT_RESOURCE_ROOT = "/scripts/";
    private static final String BUILD_INFO_RESOURCE = "/build-info.properties";

    private static final String EXTENSION_VERSION = "0.1.0";
    private static final String QUPATH_BASELINE = "0.6.0";

    /** Build timestamp injected by Gradle's processResources at JAR assembly time. */
    private static final String BUILD_TIMESTAMP = loadBuildProperty("build.timestamp", "unknown");

    /**
     * Runnable workflow items in display order. Each maps a menu label to a
     * bundled Groovy script under {@code /scripts/}. {@code needsImage} greys
     * the item out while no slide is open (mirrors how sibling extensions grey
     * their items via {@code imageDataProperty().isNull()}); project-scope
     * helpers pass {@code false} so they stay clickable with no image open.
     */
    private static final List<ScriptEntry> SCRIPTS = List.of(
        new ScriptEntry("1. Import slides from folder…",         "import-slides.groovy", false),
        new ScriptEntry("2. Run inference on selected annotations",   "run-inference.groovy", true)
    );

    /**
     * Items prepared for a later release. Bundled with the standalone menu in
     * the hepatocyte-app repo but not yet packaged here — shown greyed-out so
     * users can see the roadmap without a half-wired action.
     */
    private static final List<String> UPCOMING = List.of(
        "Run inference on whole project (unlabelled / tissue)",
        "Auto-detect tissue (current slide / whole project)"
    );

    private boolean alreadyInstalled = false;

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (alreadyInstalled) {
            logger.warn("Hepatocyte extension already installed; skipping duplicate install.");
            return;
        }
        alreadyInstalled = true;

        try {
            var menu = qupath.getMenu(MENU_PATH, true);

            var header = new MenuItem("— Hepatocyte segmentation —");
            header.setDisable(true);
            menu.getItems().add(header);
            menu.getItems().add(new SeparatorMenuItem());

            for (ScriptEntry entry : SCRIPTS) {
                MenuItem item = new MenuItem(entry.label);
                item.setOnAction(e -> runScriptSafely(entry));
                if (entry.needsImage) {
                    item.disableProperty().bind(qupath.imageDataProperty().isNull());
                }
                menu.getItems().add(item);
            }

            if (!UPCOMING.isEmpty()) {
                menu.getItems().add(new SeparatorMenuItem());
                Menu upcomingMenu = new Menu("Coming in a later release");
                for (String label : UPCOMING) {
                    MenuItem item = new MenuItem(label);
                    item.setDisable(true);
                    upcomingMenu.getItems().add(item);
                }
                menu.getItems().add(upcomingMenu);
            }

            menu.getItems().add(new SeparatorMenuItem());

            var settings = new MenuItem("Settings…");
            settings.setOnAction(e -> HepatocyteSettingsDialog.show());
            menu.getItems().add(settings);

            var envCheck = new MenuItem("Environment check…");
            envCheck.setOnAction(e -> showEnvironmentCheck(qupath));
            menu.getItems().add(envCheck);

            menu.getItems().add(new SeparatorMenuItem());

            var about = new MenuItem("About / Help…");
            about.setOnAction(e -> showAboutDialog());
            menu.getItems().add(about);

            logger.info("Hepatocyte extension installed with {} runnable + {} upcoming (disabled) items.",
                    SCRIPTS.size(), UPCOMING.size());
        } catch (Exception ex) {
            logger.error("Failed to install Hepatocyte extension menu", ex);
        }
    }

    /**
     * Loads the named script from JAR resources and evaluates it through a fresh
     * {@link GroovyShell} on a background thread, so the FX thread isn't blocked
     * while the script displays its own dialogs.
     */
    private void runScriptSafely(ScriptEntry entry) {
        String scriptBody;
        try {
            scriptBody = readScriptResource(entry.resource);
        } catch (JarCorruptedException jce) {
            Dialogs.showErrorMessage(
                "Extension needs a reload",
                "QuPath read this extension JAR at startup, but the file appears to have changed "
                + "since then (most likely the extension JAR was replaced with a newer version "
                + "while QuPath was running). Java's internal ZIP index no longer matches the "
                + "file on disk.\n\n"
                + "Fix:\n"
                + "  1. Quit QuPath completely (not just the project)\n"
                + "  2. Reopen QuPath\n"
                + "  3. Try the Hepatocyte menu again"
            );
            return;
        }
        if (scriptBody == null) {
            Dialogs.showErrorMessage(
                "Script not found",
                "Could not find the bundled script resource:\n  " + entry.resource
                + "\n\nPlease make sure you are using an up-to-date build of the extension."
            );
            return;
        }

        Thread runner = new Thread(() -> {
            try {
                Binding binding = new Binding();
                binding.setVariable("EXTENSION_NAME", "qupath-extension-hepatocyte");
                // Use the EXTENSION's classloader (a child of QuPath's, so it still
                // resolves all qupath.* classes) so a running script can reach sibling
                // bundled resources under /scripts/ via this class's getResource(...).
                GroovyShell shell = new GroovyShell(HepatocyteExtension.class.getClassLoader(), binding);
                shell.evaluate(scriptBody, entry.resource);
            } catch (Throwable t) {
                logger.error("Script execution failed: {}", entry.label, t);
                String msg = t.getMessage();
                String detail = t.getClass().getSimpleName() + ": " + (msg != null ? msg : "(no message)");
                Platform.runLater(() -> Dialogs.showErrorMessage(
                    "Script error — " + entry.label,
                    "The script failed while running:\n\n" + detail
                    + "\n\nSee View → Show log for details."
                ));
            }
        }, "HepatocyteScript-" + entry.resource);
        runner.setDaemon(true);
        runner.start();
    }

    private String readScriptResource(String filename) {
        String path = SCRIPT_RESOURCE_ROOT + filename;
        try (InputStream in = HepatocyteExtension.class.getResourceAsStream(path)) {
            if (in == null) {
                logger.error("Missing script resource: {}", path);
                return null;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (UncheckedIOException ex) {
            // BufferedReader.lines() wraps IOException as UncheckedIOException.
            // ZipException here almost always means the JAR was replaced on disk
            // while QuPath had it open — the stale central directory points at
            // offsets that no longer contain valid local file headers.
            Throwable cause = ex.getCause();
            if (cause instanceof ZipException) {
                logger.error("JAR corruption reading {} — likely JAR replaced while QuPath running", path, ex);
                throw new JarCorruptedException(ex);
            }
            logger.error("Unchecked I/O reading script resource: {}", path, ex);
            return null;
        } catch (IOException ex) {
            if (ex instanceof ZipException) {
                logger.error("JAR corruption reading {} — likely JAR replaced while QuPath running", path, ex);
                throw new JarCorruptedException(ex);
            }
            logger.error("Failed to read script resource: {}", path, ex);
            return null;
        }
    }

    /** Marker exception: JAR central directory and on-disk content disagree. */
    private static final class JarCorruptedException extends RuntimeException {
        JarCorruptedException(Throwable cause) { super(cause); }
    }

    private static String loadBuildProperty(String key, String fallback) {
        try (InputStream in = HepatocyteExtension.class.getResourceAsStream(BUILD_INFO_RESOURCE)) {
            if (in == null) {
                return fallback;
            }
            Properties props = new Properties();
            props.load(in);
            String value = props.getProperty(key);
            return (value == null || value.isBlank() || value.startsWith("@")) ? fallback : value;
        } catch (IOException ex) {
            return fallback;
        }
    }

    /**
     * Diagnostic dialog: reports the running QuPath version, image/project
     * state, and whether the configured python / bridge / model paths resolve
     * to existing files — so a user can see at a glance what still needs
     * configuring before "Run inference" will work.
     */
    private void showEnvironmentCheck(QuPathGUI qupath) {
        String ok = "✅ found";
        String missing = "—  not set / missing";
        boolean hasImage = qupath.getImageData() != null;
        boolean hasProject = qupath.getProject() != null;
        String python = HepatocyteConfig.get(HepatocyteConfig.KEY_PYTHON);
        String bridge = HepatocyteConfig.get(HepatocyteConfig.KEY_BRIDGE);
        String model = HepatocyteConfig.get(HepatocyteConfig.KEY_MODEL);
        boolean pyOk = isFile(python);
        boolean brOk = isFile(bridge);
        boolean mdOk = isFile(model);
        Dialogs.showMessageDialog(
            "Hepatocyte — Environment check",
            "QuPath version:    " + GeneralTools.getVersion() + "\n"
            + "Extension:         v" + EXTENSION_VERSION + "  (built " + BUILD_TIMESTAMP + ")\n"
            + "QuPath baseline:   " + QUPATH_BASELINE + "+\n\n"
            + "Open image:        " + (hasImage ? "yes" : "no — use File → Open") + "\n"
            + "Open project:      " + (hasProject ? "yes" : "no") + "\n\n"
            + "Inference configuration (Settings…):\n"
            + "  • Python (venv):        " + (pyOk ? ok : missing) + "\n"
            + "  • Bridge (inference.py): " + (brOk ? ok : missing) + "\n"
            + "  • Model checkpoint:     " + (mdOk ? ok : missing) + "\n\n"
            + (HepatocyteConfig.isComplete()
                ? "All three inference paths resolve — you're ready to run inference.\n"
                : "Set the missing paths in Extensions → Hepatocyte → Settings…\n"
                  + "You need a local checkout of https://github.com/sbalci/hepatocyte-app\n"
                  + "with its Python venv, and the TorchScript model (🔒 on request).\n")
            + "\nFor research and education only."
        );
    }

    private static boolean isFile(String path) {
        return path != null && !path.isBlank() && new java.io.File(path).isFile();
    }

    private void showAboutDialog() {
        Dialogs.showMessageDialog(
            "QuPath Hepatocyte Extension",
            "Runs the hepatocyte-app deep-learning liver H&E segmentation model on\n"
            + "QuPath annotations, importing predictions back as classified polygons.\n\n"
            + "Menu items:\n"
            + "  1 — Import slides from folder (recursive .svs → project, set H&E)\n"
            + "  2 — Run inference on selected annotations (per-ROI model round-trip)\n\n"
            + "The model runs OUTSIDE QuPath via the repo's Python bridge. Point the\n"
            + "extension at your local checkout in Settings… (Python venv, inference\n"
            + "script, model checkpoint). The model emits five morphological classes:\n"
            + "Balloon dystrophy, Mesenchymal, Non-nuclear, Normal, Steatosis.\n\n"
            + "Version:        " + EXTENSION_VERSION + "\n"
            + "Build date:     " + BUILD_TIMESTAMP + "\n"
            + "QuPath baseline: " + QUPATH_BASELINE + "+\n\n"
            + "🌐 Repository: https://github.com/sbalci/hepatocyte-app\n"
            + "✉️  Contact:    drserdarbalci@gmail.com\n\n"
            + "For research and education only. Not for clinical decision-making.\n"
            + "Output is a deep-learning prediction — verify visually."
        );
    }

    // --- QuPathExtension contract --------------------------------------------

    @Override
    public String getName() {
        return "Hepatocyte";
    }

    @Override
    public String getDescription() {
        return "Run the hepatocyte-app liver segmentation model on QuPath annotations "
             + "(import slides, per-ROI inference, per-class counts).";
    }

    @Override
    public Version getVersion() {
        return Version.parse(EXTENSION_VERSION);
    }

    @Override
    public Version getQuPathVersion() {
        return Version.parse(QUPATH_BASELINE);
    }

    // --- GitHubProject contract ----------------------------------------------
    // Lets QuPath check GitHub Releases and notify the user when a newer version
    // is available. NOTE: this is the extension's OWN repo — unrelated to
    // getQuPathVersion()'s 0.6.0 baseline.

    @Override
    public GitHubProject.GitHubRepo getRepository() {
        return GitHubProject.GitHubRepo.create(getName(), "sbalci", "qupath-extension-hepatocyte");
    }

    // --- helpers -------------------------------------------------------------

    private static final class ScriptEntry {
        final String label;
        final String resource;
        /** When true the item greys out while no image is open. */
        final boolean needsImage;

        ScriptEntry(String label, String resource, boolean needsImage) {
            this.label = label;
            this.resource = resource;
            this.needsImage = needsImage;
        }
    }
}
