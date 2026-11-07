/*
 * "Hepatocyte Settings" window. Opened from Extensions → Hepatocyte → Settings…
 *
 * Edits the shared java.util.prefs node (see {@link HepatocyteConfig}) that the
 * bundled Groovy workflow scripts also read. Every control writes straight back
 * to the persisted preference, so changes survive QuPath restarts and are
 * visible to the next inference run immediately.
 */
package io.github.sbalci.qupath.hepatocyte;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import qupath.fx.dialogs.Dialogs;

public final class HepatocyteSettingsDialog {

    private HepatocyteSettingsDialog() {}

    private static Stage stage;
    private static final Map<String, TextField> textFields = new LinkedHashMap<>();
    private static final Map<String, Label> statusBadges = new LinkedHashMap<>();
    private static ChoiceBox<String> deviceChoice;
    private static Label readinessBanner;

    public static void show() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(HepatocyteSettingsDialog::show);
            return;
        }
        HepatocyteConfig.autoDetectIfNeeded();

        if (stage != null) {
            refreshFieldsFromConfig();
            stage.show();
            stage.toFront();
            return;
        }

        textFields.clear();
        statusBadges.clear();

        VBox content = new VBox(12);
        content.setPadding(new Insets(14));

        Label header = new Label("Hepatocyte Segmentation — Settings");
        header.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        Label sub = new Label(
            "Configure your local hepatocyte-app paths once. Settings are saved automatically "
            + "and remembered across QuPath restarts."
        );
        sub.setWrapText(true);
        sub.setStyle("-fx-text-fill: #555; -fx-font-size: 11.5px;");

        // Quick auto-fill bar for non-technical users
        Button autoFillBtn = new Button("📁 Select hepatocyte-app folder (auto-fill paths)…");
        autoFillBtn.setTooltip(new Tooltip(
            "Pick your local hepatocyte-app directory to automatically fill the Python venv, "
            + "bridge script, and model checkpoint paths."
        ));
        autoFillBtn.setOnAction(ev -> {
            Window owner = stage != null ? stage : null;
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Select your hepatocyte-app folder");
            File curBridgeParent = existingParent(HepatocyteConfig.get(HepatocyteConfig.KEY_BRIDGE));
            if (curBridgeParent != null && curBridgeParent.getParentFile() != null
                    && curBridgeParent.getParentFile().isDirectory()) {
                dc.setInitialDirectory(curBridgeParent.getParentFile());
            }
            File chosen = dc.showDialog(owner);
            if (chosen != null) {
                String msg = HepatocyteConfig.applyRepoRoot(chosen, true);
                refreshFieldsFromConfig();
                Dialogs.showMessageDialog("Auto-fill from folder", msg);
            }
        });
        Label autoFillHint = new Label("Recommended for first-time setup");
        autoFillHint.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");
        HBox quickBar = new HBox(10, autoFillBtn, autoFillHint);
        quickBar.setAlignment(Pos.CENTER_LEFT);

        // Required paths grid
        GridPane pathGrid = new GridPane();
        pathGrid.setHgap(8);
        pathGrid.setVgap(8);
        pathGrid.setPadding(new Insets(6, 4, 6, 4));
        int row = 0;

        row = addFileRow(
            pathGrid, row,
            "1. Python (venv):",
            HepatocyteConfig.KEY_PYTHON,
            false,
            true,
            "Path to python.exe inside your hepatocyte-app virtual environment (e.g. K:/hepatocyte-app/venv/Scripts/python.exe)",
            new FileChooser.ExtensionFilter("Python executable", "*.exe", "python*"),
            new FileChooser.ExtensionFilter("All files", "*.*")
        );
        row = addFileRow(
            pathGrid, row,
            "2. Bridge script (.py):",
            HepatocyteConfig.KEY_BRIDGE,
            false,
            true,
            "Path to qupath/hepatocyte_qupath_inference.py in your hepatocyte-app checkout",
            new FileChooser.ExtensionFilter("Python script (*.py)", "*.py"),
            new FileChooser.ExtensionFilter("All files", "*.*")
        );
        row = addFileRow(
            pathGrid, row,
            "3. Model weights (.pth):",
            HepatocyteConfig.KEY_MODEL,
            false,
            true,
            "Path to the TorchScript model checkpoint (e.g. weights/baseline-1-jit.pth — available on request)",
            new FileChooser.ExtensionFilter("PyTorch / TorchScript model (*.pth, *.pt)", "*.pth", "*.pt"),
            new FileChooser.ExtensionFilter("All files", "*.*")
        );
        row = addFileRow(
            pathGrid, row,
            "Slides folder (optional):",
            HepatocyteConfig.KEY_SLIDES_ROOT,
            true,
            false,
            "Default folder opened by '1. Import slides from folder…'"
        );

        // Device selection
        pathGrid.add(new Label("Compute device:"), 0, row);
        deviceChoice = new ChoiceBox<>();
        deviceChoice.getItems().addAll(HepatocyteConfig.DEVICE_OPTIONS);
        String curDevice = HepatocyteConfig.get(HepatocyteConfig.KEY_DEVICE);
        deviceChoice.setValue(contains(HepatocyteConfig.DEVICE_OPTIONS, curDevice) ? curDevice : "auto");
        deviceChoice.setTooltip(new Tooltip(
            "auto: use NVIDIA GPU (CUDA) if available, otherwise CPU.\n"
            + "cuda: force GPU.\n"
            + "cpu: force CPU (slower, several minutes per region)."
        ));
        deviceChoice.valueProperty().addListener((o, a, b) ->
                HepatocyteConfig.put(HepatocyteConfig.KEY_DEVICE, b == null ? "auto" : b));
        Label deviceHint = new Label("('auto' uses GPU when available, else CPU)");
        deviceHint.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");
        HBox deviceBox = new HBox(8, deviceChoice, deviceHint);
        deviceBox.setAlignment(Pos.CENTER_LEFT);
        pathGrid.add(deviceBox, 1, row++, 3, 1);

        // Readiness banner
        readinessBanner = new Label();
        readinessBanner.setWrapText(true);
        readinessBanner.setMaxWidth(Double.MAX_VALUE);
        readinessBanner.setPadding(new Insets(6, 10, 6, 10));

        // Advanced & Tissue Detection parameters (collapsible)
        GridPane advGrid = new GridPane();
        advGrid.setHgap(10);
        advGrid.setVgap(8);
        advGrid.setPadding(new Insets(8));
        int advRow = 0;

        advRow = addTextRow(advGrid, advRow, "Patch size (px):", HepatocyteConfig.KEY_PATCH,
                "Model input tile size in pixels (default: 512). Keep at 512 for baseline-1-jit.pth.");
        advRow = addTextRow(advGrid, advRow, "Min cell polygon area (px²):", HepatocyteConfig.KEY_MIN_AREA,
                "Polygons smaller than this area (in slide level-0 pixels²) are filtered out as noise (default: 15).");
        advRow = addTextRow(advGrid, advRow, "Min ROI side (px):", HepatocyteConfig.KEY_MIN_ROI,
                "Annotations narrower or shorter than this (default: 512 px = 1 patch) are skipped.");
        advRow = addTextRow(advGrid, advRow, "Target MPP (µm/px):", HepatocyteConfig.KEY_TARGET_MPP,
                "Resolution the model was trained at (default: 0.25 µm/px = 40×). 20× slides (~0.50 µm/px) are automatically rescaled.");

        Label tissueHeader = new Label("Tissue Auto-Detection (Steps 4 & 5)");
        tissueHeader.setStyle("-fx-font-weight: bold; -fx-padding: 6 0 2 0;");
        advGrid.add(tissueHeader, 0, advRow++, 2, 1);

        advRow = addTextRow(advGrid, advRow, "Intensity threshold (0–255):", HepatocyteConfig.KEY_TISSUE_THRESHOLD,
                "Brightfield background threshold for QuPath's SimpleTissueDetection2 (default: 220).");
        advRow = addTextRow(advGrid, advRow, "Analysis pixel size (µm):", HepatocyteConfig.KEY_TISSUE_PIXEL_SIZE,
                "Downsampled pixel size for tissue detection (default: 20.0 µm). Larger = faster.");
        advRow = addTextRow(advGrid, advRow, "Min tissue island area (µm²):", HepatocyteConfig.KEY_TISSUE_MIN_AREA,
                "Minimum physical area for detected tissue regions (default: 10000 µm²; automatically raised to ≥ 1 model patch).");
        advRow = addTextRow(advGrid, advRow, "Max hole area to fill (µm²):", HepatocyteConfig.KEY_TISSUE_MAX_HOLE,
                "Holes smaller than this inside tissue are filled (default: 1000000 µm²).");

        TitledPane advPane = new TitledPane("Advanced Inference & Tissue Detection Parameters", advGrid);
        advPane.setExpanded(false);

        content.getChildren().addAll(header, sub, quickBar, pathGrid, readinessBanner, advPane);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent;");

        // Footer bar
        CheckBox keepOnTop = new CheckBox("Keep on top");
        keepOnTop.setSelected(true);
        keepOnTop.selectedProperty().addListener((obs, oldV, newV) -> {
            if (stage != null) {
                stage.setAlwaysOnTop(newV);
            }
        });

        Button reset = new Button("↺ Reset to defaults");
        reset.setTooltip(new Tooltip("Reset all parameters to default values and re-run path auto-detection"));
        reset.setOnAction(ev -> {
            HepatocyteConfig.resetAll();
            refreshFieldsFromConfig();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button close = new Button("Close");
        close.setOnAction(ev -> stage.hide());

        HBox footer = new HBox(10, keepOnTop, reset, spacer, close);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(10, 14, 10, 14));

        VBox root = new VBox(scroll, footer);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        refreshFieldsFromConfig();

        stage = new Stage();
        stage.initModality(Modality.NONE);
        stage.setAlwaysOnTop(true);
        stage.setTitle("Hepatocyte Segmentation — Settings");
        stage.setScene(new Scene(root, 720, 480));
        stage.show();
    }

    private static void refreshFieldsFromConfig() {
        for (Map.Entry<String, TextField> entry : textFields.entrySet()) {
            String key = entry.getKey();
            TextField tf = entry.getValue();
            String val = HepatocyteConfig.get(key);
            if (!val.equals(tf.getText())) {
                tf.setText(val);
            }
            updateStatusBadge(key, val);
        }
        if (deviceChoice != null) {
            String curDevice = HepatocyteConfig.get(HepatocyteConfig.KEY_DEVICE);
            deviceChoice.setValue(contains(HepatocyteConfig.DEVICE_OPTIONS, curDevice) ? curDevice : "auto");
        }
        updateReadinessBanner();
    }

    private static void updateStatusBadge(String key, String path) {
        Label badge = statusBadges.get(key);
        if (badge == null) {
            return;
        }
        boolean ok = HepatocyteConfig.isFile(path);
        if (ok) {
            badge.setText("✅ Found");
            badge.setStyle("-fx-text-fill: #1b7e3c; -fx-font-weight: bold; -fx-font-size: 11px;");
        } else {
            badge.setText("⚠ Missing");
            badge.setStyle("-fx-text-fill: #b85c00; -fx-font-weight: bold; -fx-font-size: 11px;");
        }
    }

    private static void updateReadinessBanner() {
        if (readinessBanner == null) {
            return;
        }
        if (HepatocyteConfig.isComplete()) {
            readinessBanner.setText("✅ Ready: Python environment, bridge script, and model checkpoint all exist on disk.");
            readinessBanner.setStyle(
                "-fx-background-color: #eaf7ee; -fx-text-fill: #165c2d; "
                + "-fx-border-color: #a3d9b1; -fx-border-radius: 4; -fx-background-radius: 4; -fx-font-weight: bold;"
            );
        } else {
            readinessBanner.setText(
                "⚠ Setup incomplete: set the missing path(s) above or click "
                + "\"Select hepatocyte-app folder\" to auto-detect them."
            );
            readinessBanner.setStyle(
                "-fx-background-color: #fff6e6; -fx-text-fill: #8a4b00; "
                + "-fx-border-color: #f0c987; -fx-border-radius: 4; -fx-background-radius: 4; -fx-font-weight: bold;"
            );
        }
    }

    /** Adds "label | textfield | Browse… | [status badge]" and wires the field to a prefs key. */
    private static int addFileRow(
            GridPane grid,
            int row,
            String label,
            String key,
            boolean directory,
            boolean requiredFile,
            String tooltipText,
            FileChooser.ExtensionFilter... filters) {
        Label lbl = new Label(label);
        if (tooltipText != null) {
            lbl.setTooltip(new Tooltip(tooltipText));
        }

        TextField tf = new TextField(HepatocyteConfig.get(key));
        tf.setPrefColumnCount(34);
        if (tooltipText != null) {
            tf.setTooltip(new Tooltip(tooltipText));
        }
        GridPane.setHgrow(tf, Priority.ALWAYS);
        textFields.put(key, tf);

        if (requiredFile) {
            Label badge = new Label();
            statusBadges.put(key, badge);
            updateStatusBadge(key, tf.getText());
            grid.add(badge, 3, row);
        }

        tf.textProperty().addListener((obs, oldV, newV) -> {
            HepatocyteConfig.put(key, newV);
            updateStatusBadge(key, newV);
            updateReadinessBanner();
        });

        Button browse = new Button("Browse…");
        browse.setOnAction(ev -> {
            Window owner = grid.getScene() != null ? grid.getScene().getWindow() : null;
            File chosen;
            if (directory) {
                DirectoryChooser dc = new DirectoryChooser();
                dc.setTitle(label);
                File cur = existingDir(tf.getText());
                if (cur != null) {
                    dc.setInitialDirectory(cur);
                }
                chosen = dc.showDialog(owner);
            } else {
                FileChooser fc = new FileChooser();
                fc.setTitle(label);
                if (filters != null && filters.length > 0) {
                    fc.getExtensionFilters().addAll(filters);
                }
                File cur = existingParent(tf.getText());
                if (cur != null) {
                    fc.setInitialDirectory(cur);
                }
                chosen = fc.showOpenDialog(owner);
            }
            if (chosen != null) {
                tf.setText(chosen.getAbsolutePath());
                HepatocyteConfig.put(key, chosen.getAbsolutePath());
                updateStatusBadge(key, chosen.getAbsolutePath());
                updateReadinessBanner();
            }
        });

        grid.add(lbl, 0, row);
        grid.add(tf, 1, row);
        grid.add(browse, 2, row);
        return row + 1;
    }

    private static int addTextRow(GridPane grid, int row, String label, String key, String tooltipText) {
        Label lbl = new Label(label);
        TextField tf = new TextField(HepatocyteConfig.get(key));
        tf.setPrefColumnCount(10);
        if (tooltipText != null) {
            lbl.setTooltip(new Tooltip(tooltipText));
            tf.setTooltip(new Tooltip(tooltipText));
        }
        textFields.put(key, tf);
        tf.textProperty().addListener((obs, oldV, newV) -> {
            String normalized = newV == null ? "" : newV.replace(',', '.');
            HepatocyteConfig.put(key, normalized);
        });
        grid.add(lbl, 0, row);
        grid.add(tf, 1, row);
        return row + 1;
    }

    private static boolean contains(String[] arr, String v) {
        for (String s : arr) {
            if (s.equals(v)) {
                return true;
            }
        }
        return false;
    }

    private static File existingDir(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        File f = new File(path.trim());
        return f.isDirectory() ? f : null;
    }

    private static File existingParent(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        File f = new File(path.trim());
        if (f.isDirectory()) {
            return f;
        }
        File parent = f.getParentFile();
        return (parent != null && parent.isDirectory()) ? parent : null;
    }
}
