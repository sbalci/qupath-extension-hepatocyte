/*
 * Shared configuration for the Hepatocyte extension.
 *
 * Stored in a java.util.prefs node so BOTH the Java settings dialog and the
 * bundled Groovy workflow scripts read/write the exact same values. The Groovy
 * scripts open the node by the same literal path ({@link #NODE}); if you change
 * the node string or any key here, update src/main/resources/scripts/*.groovy
 * to match — they are not compiled against this class.
 *
 * Values are persisted as strings (java.util.prefs stores strings); numeric
 * fields are parsed at point of use with a fallback to the default, so a blank
 * or malformed entry never crashes a run.
 */
package io.github.sbalci.qupath.hepatocyte;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;

public final class HepatocyteConfig {

    private HepatocyteConfig() {}

    /** Preference node shared with the bundled Groovy scripts. Keep in sync. */
    public static final String NODE = "io/github/sbalci/qupath/hepatocyte";

    // --- keys -----------------------------------------------------------------
    public static final String KEY_PYTHON            = "python";            // python(.exe) of the hepatocyte-app venv
    public static final String KEY_BRIDGE            = "bridge";            // hepatocyte_qupath_inference.py
    public static final String KEY_MODEL             = "model";             // TorchScript checkpoint (.pth)
    public static final String KEY_SLIDES_ROOT       = "slidesRoot";        // folder to default the import picker to
    public static final String KEY_DEVICE            = "device";            // auto | cuda | cpu
    public static final String KEY_PATCH             = "patch";             // model patch size (px)
    public static final String KEY_MIN_AREA          = "minArea";           // min polygon area (px^2)
    public static final String KEY_MIN_ROI           = "minRoi";            // min ROI bbox side (px)
    public static final String KEY_TARGET_MPP        = "targetMpp";         // model training MPP (um/px)
    public static final String KEY_TISSUE_THRESHOLD  = "tissueThreshold";   // 0-255 (SimpleTissueDetection2)
    public static final String KEY_TISSUE_PIXEL_SIZE = "tissuePixelSize";   // analysis pixel size (um)
    public static final String KEY_TISSUE_MIN_AREA   = "tissueMinArea";     // min tissue island area (um^2)
    public static final String KEY_TISSUE_MAX_HOLE   = "tissueMaxHole";     // max hole area to fill (um^2)

    /** Keys in display order, with their default values. */
    public static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put(KEY_PYTHON,            "");
        DEFAULTS.put(KEY_BRIDGE,            "");
        DEFAULTS.put(KEY_MODEL,             "");
        DEFAULTS.put(KEY_SLIDES_ROOT,       "");
        DEFAULTS.put(KEY_DEVICE,            "auto");
        DEFAULTS.put(KEY_PATCH,             "512");
        DEFAULTS.put(KEY_MIN_AREA,          "15");
        DEFAULTS.put(KEY_MIN_ROI,           "512");
        DEFAULTS.put(KEY_TARGET_MPP,        "0.25");
        DEFAULTS.put(KEY_TISSUE_THRESHOLD,  "220");
        DEFAULTS.put(KEY_TISSUE_PIXEL_SIZE, "20.0");
        DEFAULTS.put(KEY_TISSUE_MIN_AREA,   "10000.0");
        DEFAULTS.put(KEY_TISSUE_MAX_HOLE,   "1000000.0");
    }

    /** Device dropdown options. */
    public static final String[] DEVICE_OPTIONS = { "auto", "cpu", "cuda" };

    public static Preferences prefs() {
        return Preferences.userRoot().node(NODE);
    }

    public static String get(String key) {
        return prefs().get(key, DEFAULTS.getOrDefault(key, ""));
    }

    public static void put(String key, String value) {
        Preferences p = prefs();
        p.put(key, value == null ? "" : value.trim());
        try {
            p.flush();
        } catch (Exception ignored) {
        }
    }

    public static void resetAll() {
        Preferences p = prefs();
        for (Map.Entry<String, String> entry : DEFAULTS.entrySet()) {
            p.put(entry.getKey(), entry.getValue());
        }
        try {
            p.flush();
        } catch (Exception ignored) {
        }
        autoDetectIfNeeded();
    }

    /** True when the three mandatory paths (python, bridge, model) all point at existing files. */
    public static boolean isComplete() {
        return isFile(get(KEY_PYTHON)) && isFile(get(KEY_BRIDGE)) && isFile(get(KEY_MODEL));
    }

    /**
     * Auto-populates blank/missing Python, Bridge, and Model paths if a standard
     * local checkout of {@code hepatocyte-app} is found on disk.
     */
    public static void autoDetectIfNeeded() {
        if (isComplete()) {
            return;
        }
        String home = System.getProperty("user.home", "");
        List<File> candidates = new ArrayList<>();
        if (!home.isBlank()) {
            candidates.add(new File(home, "hepatocyte-app"));
            candidates.add(new File(new File(home, ".hepatocyte-app"), "hepatocyte-app-main"));
            candidates.add(new File(new File(new File(home, ".atolye"), "hepatocyte"), "hepatocyte-app-main"));
            candidates.add(new File(new File(home, "Documents"), "hepatocyte-app"));
            candidates.add(new File(new File(home, "Desktop"), "hepatocyte-app"));
        }
        candidates.add(new File("K:/hepatocyte-app"));
        candidates.add(new File("D:/hepatocyte-app"));
        candidates.add(new File("C:/hepatocyte-app"));

        for (File repoDir : candidates) {
            if (repoDir.isDirectory()) {
                applyRepoRoot(repoDir, false);
            }
        }

        // Also check shared workshop venv if python is still unset
        if (!isFile(get(KEY_PYTHON)) && !home.isBlank()) {
            File atolyeVenv = new File(new File(new File(home, ".atolye"), "runtimes"), "hepatocyte/.venv");
            File winPy = new File(atolyeVenv, "Scripts/python.exe");
            File nixPy = new File(atolyeVenv, "bin/python");
            if (winPy.isFile()) {
                put(KEY_PYTHON, winPy.getAbsolutePath());
            } else if (nixPy.isFile()) {
                put(KEY_PYTHON, nixPy.getAbsolutePath());
            }
        }
    }

    /**
     * Given a {@code hepatocyte-app} repository root directory, locates and sets
     * the Python executable, inference bridge script, and model checkpoint.
     *
     * @param repoDir   the chosen repository directory
     * @param overwrite if true, overwrites existing valid paths; if false, only fills missing paths
     * @return human-readable summary of detected paths
     */
    public static String applyRepoRoot(File repoDir, boolean overwrite) {
        if (repoDir == null || !repoDir.isDirectory()) {
            return "Selected path is not a valid folder.";
        }
        List<String> found = new ArrayList<>();

        // 1. Python venv executable
        if (overwrite || !isFile(get(KEY_PYTHON))) {
            File[] pyCandidates = new File[] {
                new File(repoDir, "venv/Scripts/python.exe"),
                new File(repoDir, ".venv/Scripts/python.exe"),
                new File(repoDir, "venv/bin/python"),
                new File(repoDir, ".venv/bin/python")
            };
            for (File f : pyCandidates) {
                if (f.isFile()) {
                    put(KEY_PYTHON, f.getAbsolutePath());
                    found.add("Python: " + f.getAbsolutePath());
                    break;
                }
            }
        }

        // 2. Bridge script
        if (overwrite || !isFile(get(KEY_BRIDGE))) {
            File[] bridgeCandidates = new File[] {
                new File(repoDir, "qupath/hepatocyte_qupath_inference.py"),
                new File(repoDir, "hepatocyte_qupath_inference.py")
            };
            for (File f : bridgeCandidates) {
                if (f.isFile()) {
                    put(KEY_BRIDGE, f.getAbsolutePath());
                    found.add("Bridge: " + f.getAbsolutePath());
                    break;
                }
            }
        }

        // 3. Model weights (.pth)
        if (overwrite || !isFile(get(KEY_MODEL))) {
            File defaultModel = new File(repoDir, "weights/baseline-1-jit.pth");
            if (defaultModel.isFile()) {
                put(KEY_MODEL, defaultModel.getAbsolutePath());
                found.add("Model: " + defaultModel.getAbsolutePath());
            } else {
                File weightsDir = new File(repoDir, "weights");
                File[] pths = weightsDir.isDirectory()
                        ? weightsDir.listFiles((d, name) -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".pth"))
                        : null;
                if (pths != null && pths.length > 0 && pths[0].isFile()) {
                    put(KEY_MODEL, pths[0].getAbsolutePath());
                    found.add("Model: " + pths[0].getAbsolutePath());
                }
            }
        }

        return found.isEmpty()
                ? "No standard files (venv, qupath/hepatocyte_qupath_inference.py, weights/*.pth) found in " + repoDir.getAbsolutePath()
                : "Detected:\n  • " + String.join("\n  • ", found);
    }

    public static boolean isFile(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        return new File(path.trim()).isFile();
    }
}
