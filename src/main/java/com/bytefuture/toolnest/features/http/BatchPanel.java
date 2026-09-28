package com.bytefuture.toolnest.features.http;

import com.bytefuture.toolnest.ui.components.UiKit;
import com.bytefuture.toolnest.ui.components.KeyValueTable;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 「批处理」面板。
 *
 * <p>流程：导入 CSV（文件 / 粘贴）→ 识别表头为变量 → 用 {: 列名} 占位符渲染
 * 请求模板 → 顺序或并发执行 → 表格汇总状态码 / 耗时 / 响应 → 可导出结果。
 */
public final class BatchPanel extends VBox {

    /** 结果表格的一行。 */
    public static final class ResultRow {
        final int index;
        final Map<String, String> vars;
        final HttpRequestSpec spec;
        HttpEngine.Result result;

        ResultRow(int index, Map<String, String> vars, HttpRequestSpec spec) {
            this.index = index;
            this.vars = vars;
            this.spec = spec;
        }

        int status() {
            if (result == null) {
                return -1;
            }
            if (result.error != null) {
                return -1;
            }
            return result.status;
        }

        long elapsed() {
            return result == null ? 0 : result.elapsedMs;
        }

        String stateText() {
            if (result == null) {
                return "等待中";
            }
            if (result.error != null) {
                return "失败";
            }
            return String.valueOf(result.status);
        }

        String stateClass() {
            if (result == null) {
                return "row-pending";
            }
            if (result.error != null) {
                return "row-fail";
            }
            if (result.status >= 200 && result.status < 300) {
                return "row-ok";
            }
            return "row-fail";
        }

        String responsePreview() {
            if (result == null) {
                return "";
            }
            if (result.error != null) {
                return result.error;
            }
            String body = result.body == null ? "" : result.body.replaceAll("\\s+", " ").trim();
            return body.length() > 120 ? body.substring(0, 120) + "…" : body;
        }
    }

    // ---- 请求模板 ----
    private final ComboBox<HttpRequestSpec.Method> methodBox = new ComboBox<>();
    private final TextField urlField = new TextField();
    private final ComboBox<HttpRequestSpec.BodyType> bodyTypeBox = new ComboBox<>();
    private final TextArea bodyArea = new TextArea();
    private final KeyValueTable headersTable = new KeyValueTable(false, 130);

    // ---- CSV ----
    private final TextArea csvArea = new TextArea();
    private final Label csvSummary = new Label("尚未导入 CSV");
    private final TableView<Map<String, String>> previewTable = new TableView<>();
    private CsvParser.Table currentTable;

    // ---- 执行配置 ----
    private final ToggleGroup modeGroup = new ToggleGroup();
    private final RadioButton sequentialRadio = new RadioButton("顺序执行");
    private final RadioButton concurrentRadio = new RadioButton("并发执行");
    private final Spinner<Integer> threadSpinner = new Spinner<>(1, 64, 8);
    private final Spinner<Integer> timeoutSpinner = new Spinner<>(1, 300, 30);
    private final Spinner<Integer> delaySpinner = new Spinner<>(0, 10000, 0);

    // ---- 结果 ----
    private final ObservableList<ResultRow> resultRows = FXCollections.observableArrayList();
    private final TableView<ResultRow> resultTable = new TableView<>(resultRows);
    private final ProgressBar progress = new ProgressBar(0);
    private final Label progressLabel = new Label("");
    private final Label statLabel = new Label("");
    private final Button runButton = new Button("开始执行");
    private final Button stopButton = new Button("停止");

    private final TextArea detailArea = UiKit.readonlyArea();
    private final Label errorLabel = UiKit.error("");

    private ExecutorService executor;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicInteger finished = new AtomicInteger(0);

    public BatchPanel() {
        getStyleClass().add("http-panel");
        setSpacing(12);

        SplitPane split = new SplitPane();
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.getItems().addAll(buildTopSection(), buildBottomSection());
        split.setDividerPositions(0.46);
        split.getStyleClass().add("batch-split");
        VBox.setVgrow(split, Priority.ALWAYS);

        getChildren().addAll(split, errorLabel);
        hideError();
    }

    // ============================================================ 上半部分

    private Node buildTopSection() {
        Node templateColumn = buildTemplateColumn();
        Node csvColumn = buildCsvColumn();
        HBox columns = new HBox(14, templateColumn, csvColumn);
        HBox.setHgrow(templateColumn, Priority.ALWAYS);
        HBox.setHgrow(csvColumn, Priority.ALWAYS);
        return columns;
    }

    /** 左列：请求模板 + 执行配置。 */
    private Node buildTemplateColumn() {
        methodBox.getItems().addAll(HttpRequestSpec.Method.values());
        methodBox.getSelectionModel().select(HttpRequestSpec.Method.GET);
        methodBox.getStyleClass().add("http-method");
        methodBox.setPrefWidth(118);
        methodBox.setMinWidth(118);

        urlField.setPromptText("https://api.example.com/order?orderId={orderId}");
        urlField.getStyleClass().add("http-url");
        HBox.setHgrow(urlField, Priority.ALWAYS);

        HBox urlRow = new HBox(8, methodBox, urlField);
        urlRow.setAlignment(Pos.CENTER_LEFT);

        bodyTypeBox.getItems().addAll(HttpRequestSpec.BodyType.values());
        bodyTypeBox.getSelectionModel().select(HttpRequestSpec.BodyType.NONE);
        bodyTypeBox.setPrefWidth(180);
        bodyTypeBox.valueProperty().addListener((o, old, now) -> {
            boolean enabled = now != HttpRequestSpec.BodyType.NONE;
            bodyArea.setDisable(!enabled);
            bodyArea.setOpacity(enabled ? 1 : 0.5);
        });

        bodyArea.setPromptText("请求体模板，例如 {\"orderId\": \"{orderId}\"}");
        bodyArea.getStyleClass().add("mono-area");
        bodyArea.setWrapText(true);
        bodyArea.setPrefHeight(78);
        bodyArea.setDisable(true);
        bodyArea.setOpacity(0.5);

        TitledPane headersPane = new TitledPane("请求头 (Headers)", headersTable);
        headersPane.getStyleClass().add("folder-pane");
        headersPane.setExpanded(false);

        VBox templateBox = UiKit.section("请求模板",
                "用 {列名} 引用 CSV 列",
                UiKit.field("请求地址模板", urlRow),
                UiKit.fieldRow("请求体类型", bodyTypeBox),
                bodyArea,
                headersPane);

        // ---- 执行配置 ----
        sequentialRadio.setToggleGroup(modeGroup);
        concurrentRadio.setToggleGroup(modeGroup);
        sequentialRadio.setSelected(true);
        sequentialRadio.setUserData(Boolean.FALSE);
        concurrentRadio.setUserData(Boolean.TRUE);

        threadSpinner.setPrefWidth(78);
        threadSpinner.setEditable(true);
        delaySpinner.setPrefWidth(96);
        delaySpinner.setEditable(true);
        timeoutSpinner.setPrefWidth(78);
        timeoutSpinner.setEditable(true);

        threadSpinner.setDisable(true);
        modeGroup.selectedToggleProperty().addListener((o, old, now) ->
                threadSpinner.setDisable(now != concurrentRadio));

        HBox modeRow = new HBox(16, sequentialRadio, concurrentRadio);
        modeRow.setAlignment(Pos.CENTER_LEFT);

        GridPane config = new GridPane();
        config.setHgap(12);
        config.setVgap(10);
        config.add(UiKit.caption("线程数"), 0, 0);
        config.add(threadSpinner, 1, 0);
        config.add(UiKit.caption("超时（秒）"), 2, 0);
        config.add(timeoutSpinner, 3, 0);
        config.add(UiKit.caption("间隔 (ms)"), 0, 1);
        config.add(delaySpinner, 1, 1);

        runButton.getStyleClass().add("http-send");
        runButton.setPrefWidth(116);
        runButton.setOnAction(e -> runBatch());

        // 停止是随时可用、但不该抢主操作视线的动作
        stopButton.getStyleClass().add("flat");
        stopButton.setDisable(true);
        stopButton.setOnAction(e -> cancelled.set(true));

        HBox actions = new HBox(8, runButton, stopButton, UiKit.spacer());
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox configBox = UiKit.section("执行配置",
                "并发适合大批量，顺序便于排查",
                modeRow, config, actions);

        VBox column = new VBox(14,
                sectionLabel("① 请求模板"), templateBox,
                sectionLabel("③ 执行配置"), configBox);
        column.setMinWidth(360);
        return column;
    }

    /** 右列：CSV 导入 + 预览。 */
    private Node buildCsvColumn() {
        // 导入 CSV 是取输入，主色实底留给「开始执行」（见 runButton 的 http-send）
        Button chooseFile = UiKit.secondaryButton("选择 CSV 文件");
        chooseFile.setOnAction(e -> chooseCsvFile());

        Button parsePaste = UiKit.secondaryButton("解析粘贴的文本");
        parsePaste.setOnAction(e -> parseFromTextArea());

        Button sample = UiKit.secondaryButton("载入示例");
        sample.setOnAction(e -> loadSample());

        Button clearCsv = UiKit.secondaryButton("清空");
        clearCsv.setOnAction(e -> {
            csvArea.clear();
            currentTable = null;
            previewTable.getColumns().clear();
            previewTable.getItems().clear();
            csvSummary.setText("尚未导入 CSV");
        });

        HBox tools = new HBox(8, chooseFile, parsePaste, sample, clearCsv);
        tools.setAlignment(Pos.CENTER_LEFT);

        csvArea.setPromptText("在此粘贴 CSV 文本，然后点「解析粘贴的文本」\n\n"
                + "示例：\norderId,type\n235027703726,A\n235027703727,B");
        csvArea.getStyleClass().add("mono-area");
        csvArea.setWrapText(false);
        csvArea.setPrefHeight(96);

        csvSummary.getStyleClass().add("caption");

        previewTable.setPlaceholder(new Label("导入 CSV 后在此预览数据"));
        previewTable.setPrefHeight(150);
        // 预览列数由 CSV 表头决定（可能很多列），必须保持各列固定宽度、
        // 容器装不下时横向滚动，否则 CONSTRAINED 策略会把列挤成省略号。
        previewTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        VBox.setVgrow(previewTable, Priority.ALWAYS);

        VBox column = new VBox(14,
                sectionLabel("② CSV 数据"),
                tools,
                csvArea,
                csvSummary,
                previewTable);
        column.getStyleClass().add("card");
        column.setMinWidth(360);
        return column;
    }

    // ============================================================ 下半部分

    private Node buildBottomSection() {
        buildResultColumns();

        resultTable.setPlaceholder(new Label("执行后在此显示每行的结果"));
        resultTable.setItems(resultRows);
        resultTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        resultTable.setPrefHeight(200);
        resultTable.getSelectionModel().selectedItemProperty().addListener(
                (o, old, now) -> showDetail(now));

        resultTable.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(ResultRow item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("row-ok", "row-fail", "row-pending");
                if (!empty && item != null) {
                    getStyleClass().add(item.stateClass());
                }
            }
        });

        progress.setPrefWidth(220);
        progressLabel.getStyleClass().add("caption");
        statLabel.getStyleClass().add("caption");

        Button exportBtn = UiKit.secondaryButton("导出结果 CSV");
        exportBtn.setOnAction(e -> exportResults());

        HBox statusBar = new HBox(12, progress, progressLabel, statLabel,
                UiKit.spacer(), exportBtn);
        statusBar.setAlignment(Pos.CENTER_LEFT);

        SplitPane resultSplit = new SplitPane();
        resultSplit.setOrientation(javafx.geometry.Orientation.HORIZONTAL);
        resultSplit.getItems().addAll(resultTable, wrapDetail());
        resultSplit.setDividerPositions(0.58);
        resultSplit.getStyleClass().add("batch-split");

        VBox bottom = new VBox(10, sectionLabel("④ 执行结果"), statusBar, resultSplit);
        VBox.setVgrow(resultSplit, Priority.ALWAYS);
        return bottom;
    }

    private void buildResultColumns() {
        resultTable.getColumns().add(col("#", 46, r -> String.valueOf(r.index)));
        resultTable.getColumns().add(col("状态", 62, ResultRow::stateText));
        resultTable.getColumns().add(col("耗时", 80, r -> r.elapsed() + " ms"));
        resultTable.getColumns().add(col("地址", 320,
                r -> r.result == null ? r.spec.url : r.result.finalUrl));
        resultTable.getColumns().add(col("响应预览", 300, ResultRow::responsePreview));
    }

    private TableColumn<ResultRow, String> col(String title, double width,
                                               java.util.function.Function<ResultRow, String> getter) {
        TableColumn<ResultRow, String> column = new TableColumn<>(title);
        column.setCellValueFactory(c -> new SimpleStringProperty(getter.apply(c.getValue())));
        column.setPrefWidth(width);
        column.setSortable(false);
        return column;
    }

    private Node wrapDetail() {
        detailArea.setPromptText("点击上方某一行查看完整响应");
        VBox box = new VBox(8, sectionLabel("响应详情"), detailArea);
        VBox.setVgrow(detailArea, Priority.ALWAYS);
        return box;
    }

    private void showDetail(ResultRow row) {
        if (row == null) {
            detailArea.setText("");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(row.spec.method.name()).append(' ')
                .append(row.result == null ? row.spec.url : row.result.finalUrl).append('\n');
        sb.append('\n').append("CSV 变量：").append('\n');
        row.vars.forEach((k, v) -> sb.append("  ").append(k).append(" = ").append(v).append('\n'));

        if (row.result != null) {
            sb.append('\n');
            if (row.result.error != null) {
                sb.append("错误：").append(row.result.error).append('\n');
                sb.append("耗时：").append(row.result.elapsedMs).append(" ms\n");
            } else {
                sb.append("状态：").append(row.result.status).append('\n');
                sb.append("耗时：").append(row.result.elapsedMs).append(" ms\n");
                sb.append('\n').append("--- 响应头 ---").append('\n');
                row.result.headers.forEach((k, v) ->
                        sb.append(k).append(": ").append(v).append('\n'));
            }
            if (row.result.requestBody != null && !row.result.requestBody.isEmpty()) {
                sb.append('\n').append("--- 请求体 ---").append('\n')
                        .append(row.result.requestBody).append('\n');
            }
            sb.append('\n').append("--- 响应体 ---").append('\n').append(row.result.body);
        }
        detailArea.setText(sb.toString());
    }

    // ============================================================ CSV

    private void chooseCsvFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择 CSV 文件");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("CSV 文件", "*.csv", "*.txt"),
                new FileChooser.ExtensionFilter("所有文件", "*.*"));
        File file = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            String text = CsvParser.readText(file.toPath());
            currentTable = CsvParser.parse(text);
            // 回填给文本编辑框时同样不能带 BOM，否则首行会多出一个空心方框
            csvArea.setText(text);
            renderPreview(file.getName());
        } catch (Exception ex) {
            showError("读取文件失败：" + ex.getMessage());
        }
    }

    private void parseFromTextArea() {
        String text = csvArea.getText();
        if (text == null || text.isBlank()) {
            showError("请先粘贴 CSV 文本，或选择 CSV 文件");
            return;
        }
        try {
            // 粘贴的文本可能从别处连带 BOM 一起复制进来，统一剥掉
            currentTable = CsvParser.parse(text);
            renderPreview("粘贴的文本");
        } catch (Exception ex) {
            showError("解析失败：" + ex.getMessage());
        }
    }

    private void loadSample() {
        String sample = """
                orderId,type,channel
                235027703726,A,web
                235027703727,B,app
                235027703728,A,mini
                """;
        csvArea.setText(sample);
        parseFromTextArea();
    }

    private void renderPreview(String source) {
        previewTable.getColumns().clear();
        previewTable.getItems().clear();
        if (currentTable == null || currentTable.headers.isEmpty()) {
            csvSummary.setText("未识别到表头");
            return;
        }

        for (String header : currentTable.headers) {
            TableColumn<Map<String, String>, String> column = new TableColumn<>(header);
            column.setCellValueFactory(c -> new SimpleStringProperty(
                    c.getValue().getOrDefault(header, "")));
            column.setPrefWidth(140);
            // 固定最小宽度，配合 UNCONSTRAINED 策略保证表头完整可见
            column.setMinWidth(90);
            column.setSortable(false);
            previewTable.getColumns().add(column);
        }
        previewTable.getItems().addAll(currentTable.rows);

        List<String> unresolved = findUnresolvedPlaceholders();
        String warn = unresolved.isEmpty() ? ""
                : "  ⚠ 模板中这些占位符在 CSV 里没有对应列：" + String.join(", ", unresolved);
        csvSummary.setText("来源：" + source + "　表头 "
                + currentTable.headers.size() + " 列，数据 " + currentTable.size() + " 行" + warn);
        hideError();
    }

    /** 检查请求模板里用到、但 CSV 表头没有的占位符。 */
    private List<String> findUnresolvedPlaceholders() {
        if (currentTable == null) {
            return List.of();
        }
        Set<String> declared = new LinkedHashSet<>(currentTable.headers);
        Set<String> used = new LinkedHashSet<>();
        used.addAll(TemplateResolver.placeholders(urlField.getText()));
        used.addAll(TemplateResolver.placeholders(bodyArea.getText()));

        List<String> missing = new ArrayList<>();
        for (String name : used) {
            boolean found = declared.stream().anyMatch(h -> h.equalsIgnoreCase(name));
            if (!found) {
                missing.add(name);
            }
        }
        return missing;
    }

    // ============================================================ 执行

    private void runBatch() {
        if (currentTable == null || currentTable.rows.isEmpty()) {
            showError("请先导入 CSV 数据");
            return;
        }
        String url = urlField.getText();
        if (url == null || url.isBlank()) {
            showError("请填写请求地址模板");
            return;
        }

        List<String> unresolved = findUnresolvedPlaceholders();
        if (!unresolved.isEmpty()) {
            showError("以下占位符在 CSV 中没有对应列，将以字面量形式发送："
                    + String.join(", ", unresolved));
        } else {
            hideError();
        }

        // 准备结果行
        resultRows.clear();
        int index = 1;
        for (Map<String, String> vars : currentTable.rows) {
            HttpRequestSpec spec = new HttpRequestSpec(
                    methodBox.getValue(),
                    TemplateResolver.resolve(url, vars),
                    bodyTypeBox.getValue(),
                    TemplateResolver.resolve(bodyArea.getText(), vars));
            resultRows.add(new ResultRow(index++, vars, spec));
        }

        boolean concurrent = modeGroup.getSelectedToggle() == concurrentRadio;
        int threads = concurrent ? threadSpinner.getValue() : 1;
        Duration timeout = Duration.ofSeconds(timeoutSpinner.getValue());
        long delay = delaySpinner.getValue();
        List<Map.Entry<String, String>> headers = headersTable.entries();

        cancelled.set(false);
        finished.set(0);
        progress.setProgress(0);
        progressLabel.setText("0 / " + resultRows.size());
        statLabel.setText("");
        runButton.setDisable(true);
        stopButton.setDisable(false);

        executor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "http-batch");
            t.setDaemon(true);
            return t;
        });

        long startAll = System.currentTimeMillis();

        for (int i = 0; i < resultRows.size(); i++) {
            final int idx = i;
            executor.submit(() -> {
                if (cancelled.get()) {
                    return;
                }
                ResultRow row = resultRows.get(idx);
                if (delay > 0 && idx > 0) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                HttpEngine.Result result = HttpEngine.send(row.spec, null, headers, timeout);
                row.result = result;

                int done = finished.incrementAndGet();
                Platform.runLater(() -> {
                    resultTable.refresh();
                    progress.setProgress((double) done / resultRows.size());
                    progressLabel.setText(done + " / " + resultRows.size());
                    statLabel.setText(summarize());
                });
            });
        }

        // 顺序模式必须等全部完成才能收尾；并发模式等线程池排空
        Thread watcher = new Thread(() -> {
            executor.shutdown();
            try {
                executor.awaitTermination(2, TimeUnit.HOURS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            long elapsed = System.currentTimeMillis() - startAll;
            Platform.runLater(() -> {
                runButton.setDisable(false);
                stopButton.setDisable(true);
                progressLabel.setText((cancelled.get() ? "已停止 " : "完成 ")
                        + finished.get() + " / " + resultRows.size());
                statLabel.setText(summarize() + "  总耗时 " + elapsed + " ms");
            });
        }, "http-batch-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private String summarize() {
        int ok = 0;
        int fail = 0;
        long total = 0;
        int counted = 0;
        for (ResultRow row : resultRows) {
            if (row.result == null) {
                continue;
            }
            counted++;
            total += row.result.elapsedMs;
            if (row.result.isSuccess()) {
                ok++;
            } else {
                fail++;
            }
        }
        if (counted == 0) {
            return "";
        }
        long avg = total / counted;
        return "成功 " + ok + "　失败 " + fail + "　平均 " + avg + " ms";
    }

    private void exportResults() {
        if (resultRows.isEmpty()) {
            showError("还没有可导出的结果");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出结果");
        chooser.setInitialFileName("http-batch-result.csv");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("CSV 文件", "*.csv"));
        File file = chooser.showSaveDialog(getScene() == null ? null : getScene().getWindow());
        if (file == null) {
            return;
        }

        List<String> headers = new ArrayList<>(List.of(
                "#", "状态", "耗时 (ms)", "最终URL", "错误", "响应预览"));
        headers.addAll(currentTable == null ? List.of() : currentTable.headers);

        List<List<String>> rows = new ArrayList<>();
        for (ResultRow row : resultRows) {
            List<String> line = new ArrayList<>();
            line.add(String.valueOf(row.index));
            line.add(row.result == null ? "" : String.valueOf(row.status()));
            line.add(String.valueOf(row.elapsed()));
            line.add(row.result == null ? row.spec.url : row.result.finalUrl);
            line.add(row.result == null || row.result.error == null ? "" : row.result.error);
            line.add(row.responsePreview());
            if (currentTable != null) {
                for (String header : currentTable.headers) {
                    line.add(row.vars.getOrDefault(header, ""));
                }
            }
            rows.add(line);
        }

        try {
            Files.writeString(file.toPath(), CsvParser.toCsv(headers, rows), StandardCharsets.UTF_8);
            hideError();
            progress.setProgress(1);
            progressLabel.setText("已导出 " + file.getName());
        } catch (Exception ex) {
            showError("导出失败：" + ex.getMessage());
        }
    }

    // ============================================================ 工具

    /** 步骤标题：① ② ③ ④ 这类流程锚点。 */
    private Label sectionLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("section-label");
        return l;
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private void hideError() {
        errorLabel.setText("");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }

    /** 从单一请求页带入当前的请求配置。 */
    public void applyTemplate(HttpRequestSpec spec,
                              List<Map.Entry<String, String>> headers) {
        if (spec == null) {
            return;
        }
        methodBox.getSelectionModel().select(spec.method);
        urlField.setText(spec.url);
        bodyTypeBox.getSelectionModel().select(spec.bodyType);
        if (spec.body != null && !spec.body.isBlank()) {
            bodyArea.setText(spec.body);
        }
        Map<String, String> headerMap = new LinkedHashMap<>();
        if (headers != null) {
            for (Map.Entry<String, String> e : headers) {
                headerMap.put(e.getKey(), e.getValue());
            }
        }
        headersTable.setEntries(headerMap);
        if (currentTable != null) {
            renderPreview("当前数据");
        }
    }

    /** 关闭时停止线程池。 */
    public void shutdown() {
        cancelled.set(true);
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
