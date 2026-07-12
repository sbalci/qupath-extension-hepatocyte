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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.prefs.Preferences;

public final class HepatocyteConfig {

    private HepatocyteConfig() {}

    /** Preference node shared with the bundled Groovy scripts. Keep in sync. */
    public static final String NODE = "io/github/sbalci/qupath/hepatocyte";

    // --- keys -----------------------------------------------------------------
    public static final String KEY_PYTHON      = "python";      // python(.exe) of the hepatocyte-app venv
    public static final String KEY_BRIDGE      = "bridge";      // hepatocyte_qupath_inference.py
    public static final String KEY_MODEL       = "model";       // TorchScript checkpoint (.pth)
    public static final String KEY_SLIDES_ROOT = "slidesRoot";  // folder to default the import picker to
    public static final String KEY_DEVICE      = "device";      // auto | cuda | cpu
    public static final String KEY_PATCH       = "patch";       // model patch size (px)
    public static final String KEY_MIN_AREA    = "minArea";     // min polygon area (px^2)
    public static final String KEY_MIN_ROI     = "minRoi";      // min ROI bbox side (px)
    public static final String KEY_TARGET_MPP  = "targetMpp";   // model training MPP (um/px)

    /** Keys in display order, with their default values. */
    public static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put(KEY_PYTHON,      "");
        DEFAULTS.put(KEY_BRIDGE,      "");
        DEFAULTS.put(KEY_MODEL,       "");
        DEFAULTS.put(KEY_SLIDES_ROOT, "");
        DEFAULTS.put(KEY_DEVICE,      "auto");
        DEFAULTS.put(KEY_PATCH,       "512");
        DEFAULTS.put(KEY_MIN_AREA,    "15");
        DEFAULTS.put(KEY_MIN_ROI,     "512");
        DEFAULTS.put(KEY_TARGET_MPP,  "0.25");
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
        prefs().put(key, value == null ? "" : value);
    }

    public static void resetAll() {
        Preferences p = prefs();
        for (String key : DEFAULTS.keySet()) {
            p.put(key, DEFAULTS.get(key));
        }
    }

    /** True when the three mandatory paths (python, bridge, model) all point at existing files. */
    public static boolean isComplete() {
        return isFile(get(KEY_PYTHON)) && isFile(get(KEY_BRIDGE)) && isFile(get(KEY_MODEL));
    }

    private static boolean isFile(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        return new java.io.File(path).isFile();
    }
}
