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

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
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

public final class HepatocyteSettingsDialog {

    private HepatocyteSettingsDialog() {}

    private static Stage stage;

    public static void show() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(HepatocyteSettingsDialog::show);
            return;
        }
        if (stage != null) {
            stage.show();
            stage.toFront();
            return;
        }

        VBox root = new VBox(10);
        root.setPadding(new Insets(12));

        Label header = new Label("Hepatocyte Settings");
        header.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Label sub = new Label("Point the extension at your local hepatocyte-app checkout. Changes are "
                + "remembered across sessions.  Research/education only.");
        sub.setWrapText(true);
        sub.setStyle("-fx-text-fill: #555; -fx-font-size: 11px;");

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(8));
        int row = 0;

        // --- paths (with browse buttons) --------------------------------------
        row = addFileRow(grid, row, "Python (venv python.exe):", HepatocyteConfig.KEY_PYTHON, false);
        row = addFileRow(grid, row, "Bridge (hepatocyte_qupath_inference.py):", HepatocyteConfig.KEY_BRIDGE, false);
        row = addFileRow(grid, row, "Model checkpoint (.pth):", HepatocyteConfig.KEY_MODEL, false);
        row = addFileRow(grid, row, "Slides root (import default):", HepatocyteConfig.KEY_SLIDES_ROOT, true);

        // --- device ----------------------------------------------------------
        grid.add(new Label("Device:"), 0, row);
        ChoiceBox<String> deviceChoice = new ChoiceBox<>();
        deviceChoice.getItems().addAll(HepatocyteConfig.DEVICE_OPTIONS);
        String curDevice = HepatocyteConfig.get(HepatocyteConfig.KEY_DEVICE);
        deviceChoice.setValue(contains(HepatocyteConfig.DEVICE_OPTIONS, curDevice) ? curDevice : "auto");
        deviceChoice.valueProperty().addListener((o, a, b) ->
                HepatocyteConfig.put(HepatocyteConfig.KEY_DEVICE, b == null ? "auto" : b));
        grid.add(deviceChoice, 1, row++);

        // --- numeric parameters ----------------------------------------------
        row = addTextRow(grid, row, "Patch size (px):", HepatocyteConfig.KEY_PATCH);
        row = addTextRow(grid, row, "Min polygon area (px²):", HepatocyteConfig.KEY_MIN_AREA);
        row = addTextRow(grid, row, "Min ROI side (px):", HepatocyteConfig.KEY_MIN_ROI);
        row = addTextRow(grid, row, "Target MPP (µm/px):", HepatocyteConfig.KEY_TARGET_MPP);

        Label hint = new Label("Patch 512, min ROI 512 (one patch), target MPP 0.25 = model training scale. "
                + "Device \"auto\" uses CUDA if a GPU is present, otherwise CPU (slower).");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: #555; -fx-font-size: 11px;");

        Button reset = new Button("↺ Reset to defaults");
        reset.setOnAction(ev -> { HepatocyteConfig.resetAll(); rebuild(); });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("Close");
        close.setOnAction(ev -> stage.hide());
        HBox footer = new HBox(8, reset, spacer, close);
        footer.setAlignment(Pos.CENTER_LEFT);

        root.getChildren().addAll(header, sub, grid, hint, footer);

        stage = new Stage();
        stage.initModality(Modality.NONE);
        stage.setTitle("Hepatocyte Settings");
        stage.setScene(new Scene(root, 640, 420));
        stage.show();
    }

    private static void rebuild() {
        if (stage != null) {
            stage.close();
            stage = null;
            show();
        }
    }

    /** Adds "label | textfield | Browse" and wires the field to a prefs key. */
    private static int addFileRow(GridPane grid, int row, String label, String key, boolean directory) {
        TextField tf = new TextField(HepatocyteConfig.get(key));
        tf.setPrefColumnCount(38);
        commitOnEdit(tf, key);
        Button browse = new Button("…");
        browse.setOnAction(ev -> {
            Window owner = grid.getScene() != null ? grid.getScene().getWindow() : null;
            File chosen;
            if (directory) {
                DirectoryChooser dc = new DirectoryChooser();
                File cur = existingDir(tf.getText());
                if (cur != null) {
                    dc.setInitialDirectory(cur);
                }
                chosen = dc.showDialog(owner);
            } else {
                FileChooser fc = new FileChooser();
                File cur = existingParent(tf.getText());
                if (cur != null) {
                    fc.setInitialDirectory(cur);
                }
                chosen = fc.showOpenDialog(owner);
            }
            if (chosen != null) {
                tf.setText(chosen.getAbsolutePath());
                HepatocyteConfig.put(key, chosen.getAbsolutePath());
            }
        });
        grid.add(new Label(label), 0, row);
        grid.add(tf, 1, row);
        grid.add(browse, 2, row);
        return row + 1;
    }

    private static int addTextRow(GridPane grid, int row, String label, String key) {
        TextField tf = new TextField(HepatocyteConfig.get(key));
        tf.setPrefColumnCount(8);
        commitOnEdit(tf, key);
        grid.add(new Label(label), 0, row);
        grid.add(tf, 1, row);
        return row + 1;
    }

    private static void commitOnEdit(TextField tf, String key) {
        tf.focusedProperty().addListener((o, was, now) -> {
            if (!now) {
                HepatocyteConfig.put(key, tf.getText());
            }
        });
        tf.setOnAction(ev -> HepatocyteConfig.put(key, tf.getText()));
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
        File f = new File(path);
        return f.isDirectory() ? f : null;
    }

    private static File existingParent(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        File f = new File(path);
        File parent = f.getParentFile();
        return (parent != null && parent.isDirectory()) ? parent : null;
    }
}
