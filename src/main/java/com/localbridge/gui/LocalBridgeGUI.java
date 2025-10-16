/*
 * =============================================================================
 *  LocalBridge Health Integration Platform
 *  File: LocalBridgeGUI.java
 * -----------------------------------------------------------------------------
 *  Purpose:
 *      JavaFX admin console with:
 *        1) Translate Channels table (file↔file) with Start/Stop.
 *        2) Inbound/Outbound Channels table (MLLP listeners/senders) with
 *           Start/Stop + Processed + Errors.
 *
 *      Translate channels load via ChannelEngineFactory (supports structured YAML).
 *      Inbound/Outbound managed by their runtimes.
 *
 *  Author : LocalBridge Health
 *  Version: 1.8.2 – October 2025
 * =============================================================================
 */
package com.localbridge.gui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Callback;

import com.localbridge.engine.ChannelConfig;
import com.localbridge.engine.ChannelEngine;
import com.localbridge.engine.ChannelEngineFactory;
import com.localbridge.inbound.InboundRuntime;
import com.localbridge.outbound.OutboundRuntime;

import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LocalBridgeGUI extends Application {
    private static final Logger log = Logger.getLogger(LocalBridgeGUI.class.getName());

    // Translate channels
    private final Map<String, ChannelEngine> channels = new HashMap<>();
    private final ObservableList<ChannelRow> channelData = FXCollections.observableArrayList();

    // Inbound/Outbound table data
    private final ObservableList<IOChannelRow> ioData = FXCollections.observableArrayList();

    private TableView<ChannelRow> channelTable;
    private TableView<IOChannelRow> ioTable;

    private TextArea logArea;
    private Label statusLabel;
    private ScheduledExecutorService statsUpdater;

    public static void main(String[] args) { launch(args); }

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("LocalBridge HL7 Channel Engine");

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().add(buildOperationsTab());
        tabs.getTabs().add(buildDesignTab());
        root.setCenter(tabs);

        root.setBottom(createBottomPanel());

        Scene scene = new Scene(root, 1200, 860);
        primaryStage.setScene(scene);
        primaryStage.show();

        reloadConfigs();
        startStatsUpdater();

        primaryStage.setOnCloseRequest(e -> {
            stopAllChannels();
            InboundRuntime.get().stopAll();
            OutboundRuntime.get().stopAll();
            Platform.exit();
        });

        appendLog("LocalBridge GUI started");
    }

    private Tab buildOperationsTab() {
        Tab t = new Tab("Operations");

        ToolBar opsToolbar = createOpsToolbar();
        Node translateTable = createChannelTable();

        Label ioLabel = new Label("Inbound / Outbound Channels");
        ioLabel.setStyle("-fx-font-weight: bold; -fx-padding: 8 0 0 0;");
        Node ioTableNode = createIOTable();

        VBox content = new VBox(8, opsToolbar, translateTable, ioLabel, ioTableNode);
        VBox.setVgrow(translateTable, Priority.ALWAYS);
        VBox.setVgrow(ioTableNode, Priority.ALWAYS);
        content.setPadding(new Insets(4, 0, 0, 0));
        t.setContent(content);
        return t;
    }

    private Tab buildDesignTab() {
        ChannelEditorTab editor = new ChannelEditorTab();
        editor.setText("Design");
        return editor;
    }

    private ToolBar createOpsToolbar() {
        Button startAllBtn = new Button("Start All");
        Button stopAllBtn  = new Button("Stop All");
        Button refreshBtn  = new Button("Refresh");
        Button reloadBtn   = new Button("Reload Configs");

        startAllBtn.setOnAction(e -> {
            startAllChannels();
            refreshIOChannels();
        });
        stopAllBtn.setOnAction(e  -> {
            stopAllChannels();
            InboundRuntime.get().stopAll();
            OutboundRuntime.get().stopAll();
            refreshIOChannels();
        });
        refreshBtn.setOnAction(e  -> {
            refreshChannels();
            refreshIOChannels();
        });
        reloadBtn.setOnAction(e   -> reloadConfigs());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        statusLabel = new Label("Channels: 0");

        return new ToolBar(
                startAllBtn, stopAllBtn, new Separator(),
                refreshBtn, reloadBtn, spacer, statusLabel
        );
    }

    // ========= Translate Table =========

    private Node createChannelTable() {
        channelTable = new TableView<>();
        channelTable.setItems(channelData);
        channelTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<ChannelRow, String> nameCol = new TableColumn<>("Channel");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("name"));
        nameCol.setPrefWidth(260);

        TableColumn<ChannelRow, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(new PropertyValueFactory<>("status"));
        statusCol.setPrefWidth(100);

        TableColumn<ChannelRow, String> typeCol = new TableColumn<>("Type");
        typeCol.setCellValueFactory(new PropertyValueFactory<>("type"));
        typeCol.setPrefWidth(100);

        TableColumn<ChannelRow, String> processedCol = new TableColumn<>("Processed");
        processedCol.setCellValueFactory(new PropertyValueFactory<>("processed"));
        processedCol.setPrefWidth(110);

        TableColumn<ChannelRow, String> errorsCol = new TableColumn<>("Errors");
        errorsCol.setCellValueFactory(new PropertyValueFactory<>("errors"));
        errorsCol.setPrefWidth(90);

        TableColumn<ChannelRow, String> inputCol = new TableColumn<>("Input Directory");
        inputCol.setCellValueFactory(new PropertyValueFactory<>("inputDir"));
        inputCol.setPrefWidth(320);

        TableColumn<ChannelRow, String> lastActivityCol = new TableColumn<>("Last Activity");
        lastActivityCol.setCellValueFactory(new PropertyValueFactory<>("lastActivity"));
        lastActivityCol.setPrefWidth(170);

        TableColumn<ChannelRow, Void> actionsCol = new TableColumn<>("Actions");
        actionsCol.setPrefWidth(180);
        actionsCol.setCellFactory(makeTranslateActionsCellFactory());

        channelTable.getColumns().addAll(
                nameCol, statusCol, typeCol, processedCol, errorsCol,
                inputCol, lastActivityCol, actionsCol
        );
        return channelTable;
    }

    private Callback<TableColumn<ChannelRow, Void>, TableCell<ChannelRow, Void>> makeTranslateActionsCellFactory() {
        return param -> new TableCell<>() {
            private final Button startBtn = new Button("Start");
            private final Button stopBtn  = new Button("Stop");
            private final HBox box = new HBox(6, startBtn, stopBtn);
            {
                box.setAlignment(Pos.CENTER_LEFT);
                startBtn.setOnAction(e -> {
                    ChannelRow row = getTableView().getItems().get(getIndex());
                    if (row != null) startChannel(row.getName());
                });
                stopBtn.setOnAction(e -> {
                    ChannelRow row = getTableView().getItems().get(getIndex());
                    if (row != null) stopChannel(row.getName());
                });
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getIndex() < 0 || getIndex() >= channelData.size()) { setGraphic(null); return; }
                ChannelRow r = channelData.get(getIndex());
                boolean disabled = "DISABLED".equals(r.getStatus());
                startBtn.setDisable(disabled || "RUNNING".equals(r.getStatus()));
                stopBtn.setDisable(disabled || "STOPPED".equals(r.getStatus()));
                setGraphic(box);
            }
        };
    }

    // ========= Inbound/Outbound Table =========

    private Node createIOTable() {
        ioTable = new TableView<>();
        ioTable.setItems(ioData);
        ioTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<IOChannelRow, String> dirCol = new TableColumn<>("Direction");
        dirCol.setCellValueFactory(new PropertyValueFactory<>("direction"));
        dirCol.setPrefWidth(110);

        TableColumn<IOChannelRow, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("name"));
        nameCol.setPrefWidth(260);

        TableColumn<IOChannelRow, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(new PropertyValueFactory<>("status"));
        statusCol.setPrefWidth(100);

        TableColumn<IOChannelRow, String> processedCol = new TableColumn<>("Processed");
        processedCol.setCellValueFactory(new PropertyValueFactory<>("processed"));
        processedCol.setPrefWidth(110);

        TableColumn<IOChannelRow, String> errorsCol = new TableColumn<>("Errors");
        errorsCol.setCellValueFactory(new PropertyValueFactory<>("errors"));
        errorsCol.setPrefWidth(90);

        TableColumn<IOChannelRow, Void> actionsCol = new TableColumn<>("Actions");
        actionsCol.setPrefWidth(180);
        actionsCol.setCellFactory(makeIOActionsCellFactory());

        ioTable.getColumns().addAll(dirCol, nameCol, statusCol, processedCol, errorsCol, actionsCol);
        return ioTable;
    }

    private Callback<TableColumn<IOChannelRow, Void>, TableCell<IOChannelRow, Void>> makeIOActionsCellFactory() {
        return param -> new TableCell<>() {
            private final Button startBtn = new Button("Start");
            private final Button stopBtn  = new Button("Stop");
            private final HBox box = new HBox(6, startBtn, stopBtn);
            {
                box.setAlignment(Pos.CENTER_LEFT);
                startBtn.setOnAction(e -> {
                    IOChannelRow row = getTableView().getItems().get(getIndex());
                    if (row == null) return;
                    startIOChannel(row);
                    refreshIOChannels();
                });
                stopBtn.setOnAction(e -> {
                    IOChannelRow row = getTableView().getItems().get(getIndex());
                    if (row == null) return;
                    stopIOChannel(row);
                    refreshIOChannels();
                });
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getIndex() < 0 || getIndex() >= ioData.size()) { setGraphic(null); return; }
                IOChannelRow r = ioData.get(getIndex());
                startBtn.setDisable("RUNNING".equals(r.getStatus()));
                stopBtn.setDisable("STOPPED".equals(r.getStatus()));
                setGraphic(box);
            }
        };
    }

    private void startIOChannel(IOChannelRow row) {
        try {
            if ("INBOUND".equals(row.getDirection())) {
                InboundRuntime.get().startChannel(row.getName());
            } else {
                OutboundRuntime.get().startChannel(row.getName());
            }
            appendLog("Started " + row.getDirection().toLowerCase() + ": " + row.getName());
        } catch (Exception e) {
            appendLog("ERROR: Failed to start " + row.getDirection().toLowerCase() + " " + row.getName() + " - " + e.getMessage());
        }
    }

    private void stopIOChannel(IOChannelRow row) {
        try {
            if ("INBOUND".equals(row.getDirection())) {
                InboundRuntime.get().stopChannel(row.getName());
            } else {
                OutboundRuntime.get().stopChannel(row.getName());
            }
            appendLog("Stopped " + row.getDirection().toLowerCase() + ": " + row.getName());
        } catch (Exception e) {
            appendLog("ERROR: Failed to stop " + row.getDirection().toLowerCase() + " " + row.getName() + " - " + e.getMessage());
        }
    }

    // ========= Bottom Panel =========

    private Node createBottomPanel() {
        VBox bottomPanel = new VBox(5);
        bottomPanel.setPadding(new Insets(10, 0, 0, 0));

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setPrefRowCount(8);

        bottomPanel.getChildren().addAll(new Label("Log"), logArea);
        return bottomPanel;
    }

    // ========= Translate Loading =========

    private void loadChannels() {
        try {
            Path configDir = Paths.get("conf/channels");
            if (!Files.exists(configDir)) {
                appendLog("ERROR: Config directory not found: " + configDir);
                return;
            }

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(configDir, "*.yaml")) {
                for (Path yaml : stream) {
                    try {
                        ChannelConfig cfg = ChannelEngineFactory.loadConfig(yaml);

                        if (cfg.isEnabled()) {
                            ChannelEngine engine = new ChannelEngine(cfg);
                            channels.put(cfg.getName(), engine);

                            ChannelRow row = new ChannelRow(
                                    cfg.getName(), "STOPPED",
                                    safeUpper(cfg.getTransformerType(), "N/A"),
                                    "0", "0",
                                    cfg.getInputDir() != null ? cfg.getInputDir().toString() : "N/A",
                                    "Never"
                            );
                            channelData.add(row);
                            appendLog("Loaded channel: " + cfg.getName());
                        } else {
                            ChannelRow row = new ChannelRow(
                                    cfg.getName(), "DISABLED",
                                    safeUpper(cfg.getTransformerType(), "N/A"),
                                    "-", "-", "N/A", "Disabled"
                            );
                            channelData.add(row);
                            appendLog("Channel disabled: " + cfg.getName());
                        }
                    } catch (Exception e) {
                        log.log(Level.SEVERE, "Failed to load " + yaml.getFileName(), e);
                        appendLog("ERROR: Failed to load " + yaml.getFileName() + " - " + e.getMessage());
                    }
                }
            }

            updateStatusLabel();
        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to load channels", e);
            appendLog("ERROR: Failed to load channels - " + e.getMessage());
        }
    }

    private static String safeUpper(String v, String dflt) {
        return v == null ? dflt : v.toUpperCase();
    }

    // ========= Translate Start/Stop/Refresh =========

    private void startChannel(String name) {
        ChannelEngine engine = channels.get(name);
        if (engine == null) return;
        try {
            engine.start();
            updateChannelStatus(name, "RUNNING");
            appendLog("Started channel: " + name);
        } catch (Exception e) {
            appendLog("ERROR: Failed to start " + name + " - " + e.getMessage());
        }
    }

    private void stopChannel(String name) {
        ChannelEngine engine = channels.get(name);
        if (engine == null) return;
        try {
            engine.stop();
            updateChannelStatus(name, "STOPPED");
            appendLog("Stopped channel: " + name);
        } catch (Exception e) {
            appendLog("ERROR: Failed to stop " + name + " - " + e.getMessage());
        }
    }

    private void startAllChannels() {
        channelData.stream().filter(r -> !"DISABLED".equals(r.getStatus()))
                .forEach(r -> startChannel(r.getName()));
    }

    private void stopAllChannels() {
        channelData.stream().filter(r -> !"DISABLED".equals(r.getStatus()))
                .forEach(r -> stopChannel(r.getName()));
    }

    // ========= Reload/Refresh =========

    private void reloadConfigs() {
        stopAllChannels();
        InboundRuntime.get().stopAll();
        OutboundRuntime.get().stopAll();

        channels.clear();
        channelData.clear();
        ioData.clear();

        loadChannels();

        try {
            InboundRuntime.get().loadAndStart(Paths.get("conf/channels"));
            OutboundRuntime.get().loadAndStart(Paths.get("conf/channels"));
            refreshIOChannels();

            appendLog("Inbound listeners started: " + InboundRuntime.get().getRunningNames().size()
                    + (InboundRuntime.get().getRunningNames().isEmpty() ? "" : " -> " + String.join(", ", InboundRuntime.get().getRunningNames())));

            appendLog("Outbound senders started: " + OutboundRuntime.get().getRunningNames().size()
                    + (OutboundRuntime.get().getRunningNames().isEmpty() ? "" : " -> " + String.join(", ", OutboundRuntime.get().getRunningNames())));
        } catch (Exception e) {
            appendLog("ERROR: Reload failed - " + e.getMessage());
            log.log(Level.SEVERE, "Reload failed", e);
        }
    }

    private void startStatsUpdater() {
        if (statsUpdater != null) statsUpdater.shutdownNow();
        statsUpdater = Executors.newSingleThreadScheduledExecutor();
        statsUpdater.scheduleAtFixedRate(() -> Platform.runLater(() -> {
            refreshChannels();
            refreshIOChannels();
        }), 5, 5, TimeUnit.SECONDS);
    }

    private void refreshChannels() {
        boolean changed = false;
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        for (ChannelRow row : channelData) {
            ChannelEngine engine = channels.get(row.getName());
            if (engine == null) continue;

            int processed = engine.getProcessedCount();
            int errors = engine.getErrorCount();

            if (!String.valueOf(processed).equals(row.getProcessed())) {
                row.setProcessed(String.valueOf(processed));
                row.setLastActivity(LocalDateTime.now().format(fmt));
                changed = true;
            }
            if (!String.valueOf(errors).equals(row.getErrors())) {
                row.setErrors(String.valueOf(errors));
                row.setLastActivity(LocalDateTime.now().format(fmt));
                changed = true;
            }

            String status = engine.isRunning() ? "RUNNING" : "STOPPED";
            if (!status.equals(row.getStatus())) {
                row.setStatus(status);
                changed = true;
            }
        }
        if (changed) {
            channelTable.refresh();
            updateStatusLabel();
        }
    }

    private void refreshIOChannels() {
        List<String> inNames = InboundRuntime.get().getAllNames();
        List<String> outNames = OutboundRuntime.get().getAllNames();

        Map<String, IOChannelRow> rows = new LinkedHashMap<>();

        for (String n : inNames) {
            String status = InboundRuntime.get().isRunning(n) ? "RUNNING" : "STOPPED";
            int proc = InboundRuntime.get().getProcessed(n);
            int errs = InboundRuntime.get().getErrors(n);
            rows.put("INBOUND|" + n, new IOChannelRow("INBOUND", n, status, String.valueOf(proc), String.valueOf(errs)));
        }
        for (String n : outNames) {
            String status = OutboundRuntime.get().isRunning(n) ? "RUNNING" : "STOPPED";
            int proc = OutboundRuntime.get().getProcessed(n);
            int errs = OutboundRuntime.get().getErrors(n);
            rows.put("OUTBOUND|" + n, new IOChannelRow("OUTBOUND", n, status, String.valueOf(proc), String.valueOf(errs)));
        }

        ioData.setAll(rows.values());
        if (ioTable != null) ioTable.refresh();
    }

    private void updateChannelStatus(String name, String status) {
        for (ChannelRow r : channelData)
            if (r.getName().equals(name))
                r.setStatus(status);
        channelTable.refresh();
        updateStatusLabel();
    }

    private void updateStatusLabel() {
        long running = channelData.stream().filter(r -> "RUNNING".equals(r.getStatus())).count();
        long stopped = channelData.stream().filter(r -> "STOPPED".equals(r.getStatus())).count();
        long disabled = channelData.stream().filter(r -> "DISABLED".equals(r.getStatus())).count();
        statusLabel.setText(String.format(
                "Channels: %d total | %d running | %d stopped | %d disabled",
                channelData.size(), running, stopped, disabled));
    }

    private void appendLog(String message) {
        Platform.runLater(() -> {
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            logArea.appendText(String.format("[%s] %s%n", ts, message));
        });
    }

    // ========= Models =========

    public static class ChannelRow {
        private final SimpleStringProperty name, status, type, processed, errors, inputDir, lastActivity;

        public ChannelRow(String n, String s, String t, String p, String e, String i, String l) {
            name = new SimpleStringProperty(n);
            status = new SimpleStringProperty(s);
            type = new SimpleStringProperty(t);
            processed = new SimpleStringProperty(p);
            errors = new SimpleStringProperty(e);
            inputDir = new SimpleStringProperty(i);
            lastActivity = new SimpleStringProperty(l);
        }

        public String getName() { return name.get(); }
        public String getStatus() { return status.get(); }
        public String getType() { return type.get(); }
        public String getProcessed() { return processed.get(); }
        public String getErrors() { return errors.get(); }
        public String getInputDir() { return inputDir.get(); }
        public String getLastActivity() { return lastActivity.get(); }

        public void setStatus(String v) { status.set(v); }
        public void setProcessed(String v) { processed.set(v); }
        public void setErrors(String v) { errors.set(v); }
        public void setLastActivity(String v) { lastActivity.set(v); }
    }

    public static class IOChannelRow {
        private final SimpleStringProperty direction, name, status, processed, errors;

        public IOChannelRow(String dir, String n, String s, String p, String e) {
            direction = new SimpleStringProperty(dir);
            name = new SimpleStringProperty(n);
            status = new SimpleStringProperty(s);
            processed = new SimpleStringProperty(p);
            errors = new SimpleStringProperty(e);
        }
        public String getDirection() { return direction.get(); }
        public String getName() { return name.get(); }
        public String getStatus() { return status.get(); }
        public String getProcessed() { return processed.get(); }
        public String getErrors() { return errors.get(); }
        public void setStatus(String v) { status.set(v); }
        public void setProcessed(String v) { processed.set(v); }
        public void setErrors(String v) { errors.set(v); }
    }
}
