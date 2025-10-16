/*
 * =============================================================================
 *  LocalBridge Health Integration Platform
 *  File: ChannelEditorTab.java
 * -----------------------------------------------------------------------------
 *  Purpose:
 *    "Design" tab for configuring channels.
 *    - Lists available channel YAMLs from conf/channels
 *    - Loads YAML into form fields
 *    - Supports multiple destinations (add/remove)
 *    - Mirrors first destination to legacy output-dir on save (compat)
 *    - Read-only WRAPI script viewer with live path resolution
 *    - Saves form back to YAML (and WRAPI content if needed)
 * -----------------------------------------------------------------------------
 *  Notes:
 *    - Keeps UI logic self-contained so LocalBridgeGUI can just add this Tab.
 *    - Parser/writer is intentionally simple and indentation-aware.
 * =============================================================================
 */

package com.localbridge.gui;

import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public final class ChannelEditorTab extends Tab {

    // Channel selector
    private final ComboBox<Path> cbChannels = new ComboBox<>();
    private final Button btnRefreshList = new Button("Refresh List");

    // Basic fields
    private final TextField tfName = new TextField();
    private final CheckBox cbEnabled = new CheckBox("Enabled");

    private final TextField tfInputDir = new TextField();
    private final TextField tfOutputDirLegacy = new TextField(); // read-only mirror of first destination
    private final TextField tfErrorDir = new TextField();
    private final TextField tfArchiveDir = new TextField();

    private final TextField tfInputPattern = new TextField();
    private final TextField tfPollMs = new TextField();
    private final TextField tfBatchSize = new TextField();

    // Transformer
    private final ChoiceBox<String> chTransformerType = new ChoiceBox<>();
    private final TextField tfTransformerScript = new TextField(); // wrapi mode
    private final TextField tfTransformerClass = new TextField();  // java mode
    private final CheckBox cbCreateMissing = new CheckBox("create-missing");
    private final CheckBox cbValidateProfile = new CheckBox("validate-profile");

    // Error handling
    private final TextField tfRetryCount = new TextField();
    private final TextField tfRetryDelayMs = new TextField();
    private final CheckBox cbMoveToError = new CheckBox("move-to-error");

    // Archive
    private final CheckBox cbArchiveEnabled = new CheckBox("archive.enabled");
    private final CheckBox cbArchiveCompress = new CheckBox("archive.compress");

    // Destinations UI
    private final ListView<String> lvDestinations = new ListView<>();
    private final Button btnAddDest = new Button("Add…");
    private final Button btnRemoveDest = new Button("Remove");

    // Right: WRAPI viewer
    private final Label rightTitle = new Label("WRAPI Script (read-only)");
    private final Label resolvedPathLabel = new Label();
    private final TextArea wrapiViewer = new TextArea();
    private final VBox rightPane = new VBox(8);

    // Footer buttons
    private final Button btnLoadYaml = new Button("Load YAML…");
    private final Button btnSave = new Button("Save");
    private final Button btnBrowseScript = new Button("Browse Script…");

    // State
    private Path currentYamlPath;
    private Path currentScriptPath;
    private Path channelsDir = Paths.get("conf", "channels");

    public ChannelEditorTab() {
        super("Design");
        setClosable(false);
        setContent(buildUI());
        configureBehavior();
        showWrapiViewer(false);

        refreshChannelList();
        if (!cbChannels.getItems().isEmpty()) {
            cbChannels.getSelectionModel().selectFirst();
            selectChannel(cbChannels.getValue());
        }
    }

    // ---------------- UI BUILD ----------------

    private Node buildUI() {
        cbChannels.setMinWidth(420);
        cbChannels.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(Path item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getFileName().toString());
            }
        });
        cbChannels.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(Path item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getFileName().toString());
            }
        });

        HBox selector = new HBox(8, new Label("Channel:"), cbChannels, btnRefreshList);
        selector.setAlignment(Pos.CENTER_LEFT);

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(8);
        form.setPadding(new Insets(12));
        int r = 0;

        form.add(sectionLabel("Channel"), 0, r++, 2, 1);
        form.add(new Label("Name:"), 0, r); form.add(tfName, 1, r++);
        form.add(cbEnabled, 1, r++);

        form.add(line(), 0, r++, 2, 1);
        form.add(sectionLabel("Directories"), 0, r++, 2, 1);
        form.add(new Label("input-dir:"), 0, r); form.add(tfInputDir, 1, r++);
        form.add(new Label("error-dir:"), 0, r); form.add(tfErrorDir, 1, r++);
        form.add(new Label("archive-dir:"), 0, r); form.add(tfArchiveDir, 1, r++);

        // Destinations + legacy output-dir mirror
        form.add(line(), 0, r++, 2, 1);
        form.add(sectionLabel("Destinations"), 0, r++, 2, 1);
        lvDestinations.setPrefHeight(120);
        HBox destBtns = new HBox(6, btnAddDest, btnRemoveDest);
        destBtns.setAlignment(Pos.CENTER_LEFT);
        form.add(lvDestinations, 1, r++);
        form.add(destBtns, 1, r++);
        tfOutputDirLegacy.setEditable(false);
        form.add(new Label("output-dir (legacy, mirrors first destination):"), 0, r); form.add(tfOutputDirLegacy, 1, r++);

        form.add(line(), 0, r++, 2, 1);
        form.add(sectionLabel("Polling"), 0, r++, 2, 1);
        form.add(new Label("input-pattern:"), 0, r); form.add(tfInputPattern, 1, r++);
        form.add(new Label("poll-interval-ms:"), 0, r); form.add(tfPollMs, 1, r++);
        form.add(new Label("batch-size:"), 0, r); form.add(tfBatchSize, 1, r++);

        form.add(line(), 0, r++, 2, 1);
        form.add(sectionLabel("Transformer"), 0, r++, 2, 1);
        chTransformerType.getItems().addAll("wrapi", "java");
        chTransformerType.setValue("wrapi");
        form.add(new Label("type:"), 0, r); form.add(chTransformerType, 1, r++);
        form.add(new Label("script (wrapi):"), 0, r);
        HBox scriptRow = new HBox(6, tfTransformerScript, btnBrowseScript);
        form.add(scriptRow, 1, r++);
        form.add(new Label("class (java):"), 0, r); form.add(tfTransformerClass, 1, r++);
        form.add(cbCreateMissing, 1, r++); cbCreateMissing.setSelected(true);
        form.add(cbValidateProfile, 1, r++); cbValidateProfile.setSelected(false);

        form.add(line(), 0, r++, 2, 1);
        form.add(sectionLabel("Error Handling"), 0, r++, 2, 1);
        form.add(new Label("retry-count:"), 0, r); form.add(tfRetryCount, 1, r++);
        form.add(new Label("retry-delay-ms:"), 0, r); form.add(tfRetryDelayMs, 1, r++);
        form.add(cbMoveToError, 1, r++); cbMoveToError.setSelected(true);

        form.add(line(), 0, r++, 2, 1);
        form.add(sectionLabel("Archive"), 0, r++, 2, 1);
        form.add(cbArchiveEnabled, 1, r++); cbArchiveEnabled.setSelected(true);
        form.add(cbArchiveCompress, 1, r++); cbArchiveCompress.setSelected(true);

        VBox left = new VBox(10, selector, form, new HBox(10, btnLoadYaml, btnSave));
        left.setPadding(new Insets(8));
        ((HBox) left.getChildren().get(left.getChildren().size() - 1)).setAlignment(Pos.CENTER_RIGHT);

        ScrollPane leftScroll = new ScrollPane(left);
        leftScroll.setFitToWidth(true);
        leftScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        leftScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        leftScroll.setPrefViewportHeight(600);

        // Right pane – WRAPI viewer
        rightTitle.setStyle("-fx-font-weight: bold;");
        resolvedPathLabel.setStyle("-fx-text-fill: -fx-text-inner-color; -fx-font-size: 11px;");
        wrapiViewer.setEditable(false);
        wrapiViewer.setWrapText(false);
        wrapiViewer.setPrefRowCount(40);
        rightPane.setPadding(new Insets(8));
        rightPane.getChildren().setAll(rightTitle, resolvedPathLabel, wrapiViewer);
        VBox.setVgrow(wrapiViewer, Priority.ALWAYS);

        SplitPane split = new SplitPane(leftScroll, rightPane);
        split.setDividerPositions(0.47);
        VBox container = new VBox(split);
        VBox.setVgrow(split, Priority.ALWAYS);
        return container;
    }

    // ---------------- BEHAVIOR ----------------

    private void configureBehavior() {
        ChangeListener<String> typeListener = (obs, o, n) -> {
            boolean wrapi = "wrapi".equalsIgnoreCase(chTransformerType.getValue());
            showWrapiViewer(wrapi);
            if (wrapi) tryLoadWrapiScript(); else clearRightPaneForJava();
        };
        chTransformerType.valueProperty().addListener(typeListener);
        showWrapiViewer("wrapi".equalsIgnoreCase(chTransformerType.getValue()));

        tfTransformerScript.textProperty().addListener((obs, oldVal, newVal) -> {
            if ("wrapi".equalsIgnoreCase(chTransformerType.getValue())) tryLoadWrapiScript();
        });

        btnBrowseScript.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select WRAPI Script");
            if (currentYamlPath != null) {
                try { fc.setInitialDirectory(currentYamlPath.getParent().toFile()); } catch (Exception ignore) {}
            }
            var f = fc.showOpenDialog(getTabPane().getScene().getWindow());
            if (f != null) {
                tfTransformerScript.setText(relativizeIfUnderYaml(f.toPath()));
                currentScriptPath = f.toPath();
                tryLoadWrapiScript();
            }
        });

        // Destinations add/remove
        btnAddDest.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Choose Destination Folder");
            var f = dc.showDialog(getTabPane().getScene().getWindow());
            if (f != null) {
                lvDestinations.getItems().add(f.getAbsolutePath());
                mirrorLegacyOutput();
            }
        });
        btnRemoveDest.setOnAction(e -> {
            int idx = lvDestinations.getSelectionModel().getSelectedIndex();
            if (idx >= 0) {
                lvDestinations.getItems().remove(idx);
                mirrorLegacyOutput();
            }
        });

        btnLoadYaml.setOnAction(e -> doLoadYamlDialog());
        btnSave.setOnAction(e -> doSaveAll());

        cbChannels.setOnAction(e -> {
            Path p = cbChannels.getValue();
            if (p != null) selectChannel(p);
        });

        btnRefreshList.setOnAction(e -> refreshChannelList());

        // mirror legacy output-dir on list changes
        lvDestinations.getItems().addListener((ListChangeListener<String>) c -> mirrorLegacyOutput());
    }

    // ---------------- ACTIONS ----------------

    private void refreshChannelList() {
        try {
            if (!Files.exists(channelsDir)) {
                showError("Channels folder not found: " + channelsDir);
                cbChannels.getItems().clear();
                return;
            }
            List<Path> found = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(channelsDir, "*.yaml")) {
                for (Path p : ds) found.add(p.toAbsolutePath().normalize());
            }
            cbChannels.getItems().setAll(found);
        } catch (IOException ex) {
            showError("Failed to scan channels folder: " + ex.getMessage());
        }
    }

    private void selectChannel(Path yamlPath) {
        currentYamlPath = yamlPath;
        currentScriptPath = null;
        try {
            String yaml = Files.readString(currentYamlPath, StandardCharsets.UTF_8);
            parseYamlIntoForm(yaml);
            if ("wrapi".equalsIgnoreCase(chTransformerType.getValue())) tryLoadWrapiScript();
            else clearRightPaneForJava();
        } catch (IOException ex) {
            showError("Failed to read YAML: " + ex.getMessage());
        }
    }

    private void doLoadYamlDialog() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Open Channel YAML");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("YAML", "*.yaml", "*.yml"));
        if (Files.exists(channelsDir)) {
            try { fc.setInitialDirectory(channelsDir.toFile()); } catch (Exception ignore) {}
        }
        var f = fc.showOpenDialog(getTabPane().getScene().getWindow());
        if (f == null) return;

        selectChannel(f.toPath());
        if (!cbChannels.getItems().contains(f.toPath())) {
            cbChannels.getItems().add(f.toPath());
            cbChannels.getSelectionModel().select(f.toPath());
        }
    }

    private void doSaveAll() {
        if (currentYamlPath == null) {
            showError("No YAML file selected.");
            return;
        }

        // Persist WRAPI script from viewer if present (viewer is read-only to user, but we keep save-on-YAML save)
        if ("wrapi".equalsIgnoreCase(chTransformerType.getValue())) {
            String scriptPathText = tfTransformerScript.getText().trim();
            if (!scriptPathText.isEmpty()) {
                Path scriptPath = resolveExistingOrRelative(scriptPathText);
                if (scriptPath == null) scriptPath = resolveRelativeToYaml(scriptPathText);
                try {
                    Files.createDirectories(scriptPath.getParent());
                    Files.writeString(scriptPath, wrapiViewer.getText(), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    currentScriptPath = scriptPath;
                    updateResolvedPathLabel(scriptPath, true);
                } catch (IOException ex) {
                    showError("Failed to save WRAPI script: " + ex.getMessage());
                    return;
                }
            }
        }

        String yaml = buildYamlFromForm();
        try {
            Files.createDirectories(currentYamlPath.getParent());
            Files.writeString(currentYamlPath, yaml, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            showInfo("Saved channel.");
        } catch (IOException ex) {
            showError("Failed to save YAML: " + ex.getMessage());
        }
    }

    // ---------------- YAML I/O ----------------

    private String buildYamlFromForm() {
        String indent = "  ";
        String indent2 = "    ";
        StringBuilder sb = new StringBuilder();
        sb.append("channel:\n");
        sb.append(indent).append("name: ").append(quote(tfName.getText())).append("\n");
        sb.append(indent).append("enabled: ").append(cbEnabled.isSelected()).append("\n\n");

        sb.append(indent).append("input-dir: ").append(quote(tfInputDir.getText())).append("\n");
        sb.append(indent).append("error-dir: ").append(quote(tfErrorDir.getText())).append("\n");
        sb.append(indent).append("archive-dir: ").append(quote(tfArchiveDir.getText())).append("\n\n");

        // destinations
        sb.append(indent).append("destinations:\n");
        List<String> dests = new ArrayList<>(lvDestinations.getItems());
        if (dests.isEmpty() && !tfOutputDirLegacy.getText().isBlank()) {
            dests.add(tfOutputDirLegacy.getText().trim());
        }
        for (String d : dests) {
            sb.append(indent2).append("- path: ").append(quote(d)).append("\n");
        }
        // legacy mirror for compatibility
        sb.append(indent).append("output-dir: ").append(quote(dests.isEmpty() ? "" : dests.get(0))).append("\n\n");

        sb.append(indent).append("input-pattern: ").append(quote(tfInputPattern.getText())).append("\n");
        sb.append(indent).append("poll-interval-ms: ").append(parseOrDefault(tfPollMs.getText(), 1000)).append("\n");
        sb.append(indent).append("batch-size: ").append(parseOrDefault(tfBatchSize.getText(), 10)).append("\n\n");

        sb.append(indent).append("transformer:\n");
        sb.append(indent2).append("type: ").append(quote(chTransformerType.getValue())).append("\n");
        if ("wrapi".equalsIgnoreCase(chTransformerType.getValue())) {
            sb.append(indent2).append("script: ").append(quote(tfTransformerScript.getText())).append("\n");
        } else {
            sb.append(indent2).append("class: ").append(quote(tfTransformerClass.getText())).append("\n");
        }
        sb.append(indent2).append("create-missing: ").append(cbCreateMissing.isSelected()).append("\n");
        sb.append(indent2).append("validate-profile: ").append(cbValidateProfile.isSelected()).append("\n\n");

        sb.append(indent).append("error-handling:\n");
        sb.append(indent2).append("retry-count: ").append(parseOrDefault(tfRetryCount.getText(), 3)).append("\n");
        sb.append(indent2).append("retry-delay-ms: ").append(parseOrDefault(tfRetryDelayMs.getText(), 5000)).append("\n");
        sb.append(indent2).append("move-to-error: ").append(cbMoveToError.isSelected()).append("\n\n");

        sb.append(indent).append("archive:\n");
        sb.append(indent2).append("enabled: ").append(cbArchiveEnabled.isSelected()).append("\n");
        sb.append(indent2).append("compress: ").append(cbArchiveCompress.isSelected()).append("\n");

        return sb.toString();
    }

    private void parseYamlIntoForm(String yaml) {
        // reset form fields that are array-based
        lvDestinations.getItems().clear();
        tfOutputDirLegacy.setText("");

        try {
            String[] lines = yaml.replace("\r\n", "\n").split("\n");
            for (int i=0; i<lines.length; i++) {
                String raw = lines[i];
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;

                if (line.startsWith("name:")) tfName.setText(unq(val(line)));
                else if (line.startsWith("enabled:")) cbEnabled.setSelected(bool(val(line)));

                else if (line.startsWith("input-dir:")) tfInputDir.setText(unq(val(line)));
                else if (line.startsWith("error-dir:")) tfErrorDir.setText(unq(val(line)));
                else if (line.startsWith("archive-dir:")) tfArchiveDir.setText(unq(val(line)));

                else if (line.startsWith("input-pattern:")) tfInputPattern.setText(unq(val(line)));
                else if (line.startsWith("poll-interval-ms:")) tfPollMs.setText(val(line));
                else if (line.startsWith("batch-size:")) tfBatchSize.setText(val(line));

                else if (line.equals("transformer:")) {
                    int base = leadSpaces(lines[i]);
                    int j = i + 1;
                    while (j < lines.length && leadSpaces(lines[j]) > base) {
                        String t = lines[j].strip();
                        if (t.startsWith("type:")) chTransformerType.setValue(unq(val(t)));
                        else if (t.startsWith("script:")) tfTransformerScript.setText(unq(val(t)));
                        else if (t.startsWith("class:")) tfTransformerClass.setText(unq(val(t)));
                        else if (t.startsWith("create-missing:")) cbCreateMissing.setSelected(bool(val(t)));
                        else if (t.startsWith("validate-profile:")) cbValidateProfile.setSelected(bool(val(t)));
                        j++;
                    }
                    i = j - 1;
                }

                else if (line.equals("error-handling:")) {
                    int base = leadSpaces(lines[i]);
                    int j = i + 1;
                    while (j < lines.length && leadSpaces(lines[j]) > base) {
                        String t = lines[j].strip();
                        if (t.startsWith("retry-count:")) tfRetryCount.setText(val(t));
                        else if (t.startsWith("retry-delay-ms:")) tfRetryDelayMs.setText(val(t));
                        else if (t.startsWith("move-to-error:")) cbMoveToError.setSelected(bool(val(t)));
                        j++;
                    }
                    i = j - 1;
                }

                else if (line.equals("archive:")) {
                    int base = leadSpaces(lines[i]);
                    int j = i + 1;
                    while (j < lines.length && leadSpaces(lines[j]) > base) {
                        String t = lines[j].strip();
                        if (t.startsWith("enabled:")) cbArchiveEnabled.setSelected(bool(val(t)));
                        else if (t.startsWith("compress:")) cbArchiveCompress.setSelected(bool(val(t)));
                        j++;
                    }
                    i = j - 1;
                }

                else if (line.equals("destinations:")) {
                    int baseIndent = leadSpaces(lines[i]);
                    int j = i + 1;
                    while (j < lines.length) {
                        String rr = lines[j];
                        if (rr.trim().isEmpty()) { j++; continue; }
                        if (leadSpaces(rr) <= baseIndent) break;
                        String t = rr.strip();
                        if (t.startsWith("-")) {
                            // nested "path:" or shorthand "- /dir"
                            int k = j + 1;
                            String pathStr = null;
                            while (k < lines.length && leadSpaces(lines[k]) > leadSpaces(rr)) {
                                String ts = lines[k].strip();
                                if (ts.startsWith("path:")) pathStr = unq(val(ts));
                                k++;
                            }
                            if (pathStr == null) {
                                String after = t.substring(1).trim();
                                if (!after.isEmpty() && !after.contains(":")) pathStr = unq(after);
                            }
                            if (pathStr != null && !pathStr.isBlank()) lvDestinations.getItems().add(pathStr);
                            j = k;
                        } else j++;
                    }
                    i = j - 1;
                }

                else if (line.startsWith("output-dir:")) {
                    String legacy = unq(val(line));
                    if (lvDestinations.getItems().isEmpty() && !legacy.isBlank()) {
                        lvDestinations.getItems().add(legacy);
                    }
                }
            }
            mirrorLegacyOutput();
            boolean wrapi = "wrapi".equalsIgnoreCase(chTransformerType.getValue());
            showWrapiViewer(wrapi);
        } catch (Exception ex) {
            showError("YAML parse warning (non-fatal): " + ex.getMessage());
        }
    }

    // ---------------- VIEWER ----------------

    private void showWrapiViewer(boolean show) {
        rightPane.setManaged(true);
        rightPane.setVisible(true);
        rightTitle.setText(show ? "WRAPI Script (read-only)" : "No script for Java transformer");
        if (!show) {
            wrapiViewer.clear();
            updateResolvedPathLabel(null, false);
        }
    }

    private void tryLoadWrapiScript() {
        if (!"wrapi".equalsIgnoreCase(chTransformerType.getValue())) {
            clearRightPaneForJava();
            return;
        }
        String scriptText = tfTransformerScript.getText() == null ? "" : tfTransformerScript.getText().trim();
        if (scriptText.isEmpty()) {
            wrapiViewer.clear();
            updateResolvedPathLabel(null, false);
            return;
        }
        Path existing = resolveExistingOrRelative(scriptText);
        if (existing != null && Files.exists(existing)) {
            try {
                wrapiViewer.setText(Files.readString(existing, StandardCharsets.UTF_8));
                updateResolvedPathLabel(existing, true);
                currentScriptPath = existing;
            } catch (IOException e) {
                wrapiViewer.clear();
                updateResolvedPathLabel(existing, false);
                showError("Failed to read WRAPI script: " + e.getMessage());
            }
        } else {
            wrapiViewer.clear();
            updateResolvedPathLabel(null, false);
        }
    }

    private void clearRightPaneForJava() {
        rightTitle.setText("No script for Java transformer");
        wrapiViewer.clear();
        updateResolvedPathLabel(null, false);
    }

    private void updateResolvedPathLabel(Path p, boolean ok) {
        if (p == null) {
            resolvedPathLabel.setText("Resolved path: Not found");
            resolvedPathLabel.setStyle("-fx-text-fill: #cc3b3b; -fx-font-size: 11px;");
        } else {
            resolvedPathLabel.setText("Resolved path: " + p.toString() + (ok ? "" : " (Not found)"));
            resolvedPathLabel.setStyle(ok
                    ? "-fx-text-fill: -fx-text-inner-color; -fx-font-size: 11px;"
                    : "-fx-text-fill: #cc3b3b; -fx-font-size: 11px;");
        }
    }

    // ---------------- HELPERS ----------------

    private void mirrorLegacyOutput() {
        if (lvDestinations.getItems().isEmpty()) tfOutputDirLegacy.setText("");
        else tfOutputDirLegacy.setText(lvDestinations.getItems().get(0));
    }

    private Label sectionLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        return l;
    }

    private Separator line() { return new Separator(); }

    private static String val(String line) {
        int i = line.indexOf(':');
        return i < 0 ? "" : line.substring(i + 1).trim();
    }
    private static boolean bool(String lineVal) {
        String v = lineVal == null ? "" : lineVal.trim();
        return v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes");
    }
    private static String quote(String s) {
        if (s == null) return "\"\"";
        String t = s.trim();
        if (t.isEmpty()) return "\"\"";
        if (t.matches(".*[\\s:#?*|].*")) return "\"" + t.replace("\"", "\\\"") + "\"";
        return t;
    }
    private static String unq(String s) {
        if (s == null) return "";
        String t = s.trim();
        if ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))) {
            return t.substring(1, t.length() - 1).replace("\\\"", "\"");
        }
        return t;
    }
    private static int parseOrDefault(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception ignore) { return def; }
    }

    private Path resolveExistingOrRelative(String pathText) {
        List<Path> candidates = new ArrayList<>();
        Path asTyped = Paths.get(pathText).normalize();
        candidates.add(asTyped.isAbsolute() ? asTyped : asTyped.toAbsolutePath().normalize());
        if (currentYamlPath != null) candidates.add(currentYamlPath.getParent().resolve(pathText).normalize());
        candidates.add(Paths.get("conf", "transformers").resolve(pathText).normalize());
        for (Path c : candidates) {
            try { if (Files.exists(c) && Files.isRegularFile(c)) return c; } catch (Exception ignore) {}
        }
        return (currentYamlPath != null) ? currentYamlPath.getParent().resolve(pathText).normalize() : null;
    }

    private Path resolveRelativeToYaml(String pathText) {
        Path p = Paths.get(pathText);
        if (!p.isAbsolute() && currentYamlPath != null) return currentYamlPath.getParent().resolve(p).normalize();
        return p.normalize();
    }

    private String relativizeIfUnderYaml(Path p) {
        if (currentYamlPath == null) return p.toString();
        Path base = currentYamlPath.getParent();
        try { return base.relativize(p).toString(); } catch (Exception ignore) { return p.toString(); }
    }

    private static int leadSpaces(String s) {
        if (s == null) return 0;
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == ' ') n++; else break;
        }
        return n;
    }

    private void showError(String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        a.setHeaderText("Channel Editor");
        a.showAndWait();
    }

    private void showInfo(String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
        a.setHeaderText("Channel Editor");
        a.showAndWait();
    }
}
