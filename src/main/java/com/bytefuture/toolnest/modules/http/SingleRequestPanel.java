package com.bytefuture.toolnest.modules.http;

import com.bytefuture.toolnest.ui.UiKit;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 「单一请求」面板 —— 仿 Postman 的请求编辑器。
 *
 * <p>布局：
 * <pre>
 * ┌ 方法▼ ┬ URL 输入框 ─────────────┐  ┌ Send ┐
 * ├ Params | Headers | Body ────────┤  └──────┘
 * │  键值编辑表 / 请求体编辑区       │
 * └─────────────────────────────────┘
 * ┌ 响应区：状态码 耗时 大小 ─────────┐
 * │  Body | Headers | 请求详情       │
 * └─────────────────────────────────┘
 * </pre>
 */
public final class SingleRequestPanel extends VBox {

    private final ComboBox<HttpRequestSpec.Method> methodBox = new ComboBox<>();
    private final TextField urlField = new TextField();
    private final Button sendButton = new Button("Send");

    private final KeyValueTable paramsTable = new KeyValueTable(true, 170);
    private final KeyValueTable headersTable = new KeyValueTable(false, 170);

    private final ComboBox<HttpRequestSpec.BodyType> bodyTypeBox = new ComboBox<>();
    private final TextArea bodyArea = new TextArea();

    private final Label statusLabel = new Label("尚未发送请求");
    private final Label timeLabel = new Label("");
    private final Label sizeLabel = new Label("");
    private final Label errorLabel = UiKit.error("");

    private final TextArea responseBody = UiKit.readonlyArea();
    private final TextArea responseHeaders = UiKit.readonlyArea();
    private final TextArea requestDetail = UiKit.readonlyArea();
    private final TabPane responseTabs = new TabPane();

    /**
     * 响应体的高亮视图。与 {@link #responseBody} 叠放在同一个 StackPane 里，
     * 根据内容是否 JSON 决定显示哪一个 —— TextArea 全文单色，
     * 高亮必须用支持按区间着色的 CodeArea。
     */
    private final com.bytefuture.toolnest.ui.JsonCodeArea responseJson =
            new com.bytefuture.toolnest.ui.JsonCodeArea();
    /** 承载 Body 两种视图的容器，运行期在它们之间切换可见性。 */
    private final StackPane bodyHolder = new StackPane();
    /** Body 的格式化按钮，仅在当前内容是 JSON 时可用。 */
    private final Button formatBodyBtn = UiKit.secondaryButton("格式化");

    /**
     * 超过这个大小就不做语法高亮。
     *
     * <p>逐字符跑正则 + 构建 StyleSpans 是 O(n) 但常数很大，几 MB 的响应体会卡住
     * UI 线程。这类大响应本来就该靠搜索定位，纯文本显示更实用。
     */
    private static final int HIGHLIGHT_LIMIT = 256 * 1024;

    /** 当前 Body 是否是 JSON（决定「格式化」按钮与高亮视图是否可用）。 */
    private boolean bodyIsJson = false;
    /** 当前 Body 的原始文本（未格式化），供「格式化」按钮反复切换。 */
    private String rawBody = "";

    private final Spinner<Integer> timeoutSpinner = new Spinner<>(1, 300, 30);

    /** 单线程池：串行发送，避免用户连点造成并发 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "http-single");
        t.setDaemon(true);
        return t;
    });

    public SingleRequestPanel() {
        getStyleClass().add("http-panel");
        setSpacing(14);

        getChildren().addAll(buildRequestBar(), buildRequestTabs(), buildResponseHeader(),
                buildResponseTabs());
        hideError();
    }

    // ---------------------------------------------------------------- 请求区

    private Node buildRequestBar() {
        methodBox.getItems().addAll(HttpRequestSpec.Method.values());
        methodBox.getSelectionModel().select(HttpRequestSpec.Method.GET);
        methodBox.getStyleClass().add("http-method");
        methodBox.setPrefWidth(124);
        methodBox.setMinWidth(124);

        urlField.setPromptText("请求地址，例如 https://api.example.com/order?id={orderId}");
        urlField.getStyleClass().add("http-url");
        HBox.setHgrow(urlField, Priority.ALWAYS);

        sendButton.getStyleClass().add("http-send");
        sendButton.setDefaultButton(true);
        sendButton.setOnAction(e -> send());
        sendButton.setPrefWidth(100);
        sendButton.setMinWidth(100);

        HBox bar = new HBox(8, methodBox, urlField, sendButton);
        bar.setAlignment(Pos.CENTER_LEFT);

        // 超时与占位符说明移到请求行下方：它们属于「怎么发」而不是「发给谁」
        Label timeoutLabel = UiKit.caption("超时(秒)");
        timeoutSpinner.setPrefWidth(84);
        timeoutSpinner.setEditable(true);

        HBox sub = new HBox(8, timeoutLabel, timeoutSpinner, UiKit.spacer(),
                UiKit.caption("URL 与请求体中支持 {列名} 占位符，批处理时按 CSV 行替换。"));
        sub.setAlignment(Pos.CENTER_LEFT);
        sub.getStyleClass().add("http-hint-row");

        return new VBox(8, bar, sub);
    }

    private Node buildRequestTabs() {
        // ---- Params ----
        Button clearParams = UiKit.secondaryButton("清空");
        clearParams.setOnAction(e -> paramsTable.clear());

        VBox paramsBox = new VBox(8,
                KeyValueTable.toolbar(clearParams, UiKit.spacer(),
                        UiKit.caption("这些键值会以 query 参数拼到 URL 之后")),
                paramsTable);
        VBox.setVgrow(paramsTable, Priority.ALWAYS);

        // ---- Headers ----
        Button clearHeaders = UiKit.secondaryButton("清空");
        clearHeaders.setOnAction(e -> headersTable.clear());

        Button jsonHeader = UiKit.secondaryButton("+ JSON");
        jsonHeader.setOnAction(e -> {
            headersTable.setEntries(Map.of("Content-Type", "application/json"));
        });

        VBox headersBox = new VBox(8,
                KeyValueTable.toolbar(jsonHeader, clearHeaders, UiKit.spacer(),
                        UiKit.caption("常用请求头")),
                headersTable);
        VBox.setVgrow(headersTable, Priority.ALWAYS);

        // ---- Body ----
        bodyTypeBox.getItems().addAll(HttpRequestSpec.BodyType.values());
        bodyTypeBox.getSelectionModel().select(HttpRequestSpec.BodyType.NONE);
        bodyTypeBox.setPrefWidth(210);

        bodyArea.setPromptText("请求体内容，例如 {\"orderId\": \"{orderId}\"}");
        bodyArea.getStyleClass().add("mono-area");
        bodyArea.setWrapText(true);
        VBox.setVgrow(bodyArea, Priority.ALWAYS);

        Label bodyHint = UiKit.caption(
                "选择 JSON / XML / Text 会以 UTF-8 发送；Form 会自动编码键值。");
        VBox bodyBox = new VBox(8,
                KeyValueTable.toolbar(new Label("类型"), bodyTypeBox,
                        UiKit.spacer(), jsonBeautifyButton()),
                bodyArea, bodyHint);
        VBox.setVgrow(bodyArea, Priority.ALWAYS);
        bodyBox.setPadding(new Insets(2, 0, 0, 0));

        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("http-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
                new Tab("Params", paramsBox),
                new Tab("Headers", headersBox),
                new Tab("Body", bodyBox));
        tabs.setPrefHeight(248);
        tabs.setMinHeight(160);

        // Params / Headers 的 Tab 文字带数量角标，收起时也能看出填了几项
        paramsTable.setOnRowsCountChanged(n ->
                tabs.getTabs().get(0).setText("Params" + (n > 0 ? " (" + n + ")" : "")));
        headersTable.setOnRowsCountChanged(n ->
                tabs.getTabs().get(1).setText("Headers" + (n > 0 ? " (" + n + ")" : "")));
        return tabs;
    }

    private Button jsonBeautifyButton() {
        Button button = UiKit.secondaryButton("格式化");
        button.setOnAction(e -> {
            String text = bodyArea.getText();
            if (text == null || text.isBlank()) {
                return;
            }
            try {
                com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
                bodyArea.setText(gson.toJson(com.google.gson.JsonParser.parseString(text)));
            } catch (Exception ex) {
                // JSON 不合法就保持原样，不打断输入
            }
        });
        return button;
    }

    // ---------------------------------------------------------------- 响应区

    private Node buildResponseHeader() {
        statusLabel.getStyleClass().add("http-status");
        timeLabel.getStyleClass().add("http-metric");
        sizeLabel.getStyleClass().add("http-metric");

        HBox metrics = new HBox(16, statusLabel, timeLabel, sizeLabel);
        metrics.setAlignment(Pos.CENTER_LEFT);

        Button clearBtn = UiKit.secondaryButton("清空响应");
        clearBtn.setOnAction(e -> clearResponse());

        HBox row = new HBox(10, metrics, UiKit.spacer(), clearBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("http-response-bar");
        return row;
    }

    private Node buildResponseTabs() {
        responseTabs.getStyleClass().add("http-tabs");
        responseTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        responseBody.setPromptText("响应内容会显示在这里");
        responseHeaders.setPromptText("响应头会显示在这里");
        requestDetail.setPromptText("实际发出的请求会显示在这里\n（含替换占位符后的最终 URL）");

        // Body 两个视图叠放：默认显示纯文本，命中 JSON 时切到高亮视图
        responseJson.setPlaceholderText("响应内容会显示在这里");
        responseJson.setVisible(false);
        responseJson.setManaged(false);
        bodyHolder.getChildren().addAll(responseBody, responseJson);
        bodyHolder.setAlignment(Pos.TOP_LEFT);

        formatBodyBtn.setDisable(true);
        formatBodyBtn.setOnAction(e -> toggleBodyFormat());
        Label bodyHint = UiKit.hint("识别到 JSON 时自动高亮");

        HBox bodyToolbar = new HBox(10, bodyHint, UiKit.spacer(), formatBodyBtn);
        bodyToolbar.setAlignment(Pos.CENTER_LEFT);
        bodyToolbar.getStyleClass().add("http-body-toolbar");

        VBox bodyTab = new VBox(bodyToolbar, bodyHolder);
        VBox.setVgrow(bodyHolder, Priority.ALWAYS);

        responseTabs.getTabs().addAll(
                new Tab("Body", bodyTab),
                new Tab("响应头", wrapArea(responseHeaders)),
                new Tab("请求详情", wrapArea(requestDetail)));
        VBox.setVgrow(responseTabs, Priority.ALWAYS);
        return responseTabs;
    }

    private Node wrapArea(TextArea area) {
        StackPane pane = new StackPane(area);
        pane.setAlignment(Pos.TOP_LEFT);
        return pane;
    }

    // ---------------------------------------------------------------- 发送

    /** 组装当前界面上的请求描述。 */
    public HttpRequestSpec currentSpec() {
        HttpRequestSpec.Method method = methodBox.getValue();
        return new HttpRequestSpec(method, urlField.getText(),
                bodyTypeBox.getValue(), bodyArea.isDisabled() ? "" : bodyArea.getText());
    }

    private void send() {
        String url = urlField.getText();
        if (url == null || url.isBlank()) {
            showError("请先填写请求地址");
            return;
        }
        // 单一请求里如果还留着占位符，提醒一下（不阻断）
        var placeholders = TemplateResolver.placeholders(url);
        if (!placeholders.isEmpty()) {
            showError("URL 中仍有未替换的占位符 " + placeholders
                    + "，单一请求模式下会按字面量发送。");
        } else {
            hideError();
        }

        sendButton.setDisable(true);
        sendButton.setText("发送中…");
        statusLabel.setText("请求中…");

        HttpRequestSpec spec = currentSpec();
        List<Map.Entry<String, String>> params = paramsTable.entries();
        List<Map.Entry<String, String>> headers = headersTable.entries();
        Duration timeout = Duration.ofSeconds(timeoutSpinner.getValue());

        executor.submit(() -> {
            HttpEngine.Result result = HttpEngine.send(spec, params, headers, timeout);
            Platform.runLater(() -> {
                render(result, spec, params, headers);
                sendButton.setDisable(false);
                sendButton.setText("Send");
            });
        });
    }

    private void render(HttpEngine.Result result, HttpRequestSpec spec,
                        List<Map.Entry<String, String>> params,
                        List<Map.Entry<String, String>> headers) {
        // 状态徽标
        statusLabel.getStyleClass().removeAll(
                "status-ok", "status-fail", "status-redirect", "status-error");
        if (result.error != null) {
            statusLabel.setText("请求失败");
            statusLabel.getStyleClass().add("status-error");
            showError(result.error);
        } else {
            statusLabel.setText(result.status + " " + reasonPhrase(result.status));
            statusLabel.getStyleClass().add(result.statusClass());
            hideError();
        }

        timeLabel.setText(result.elapsedMs + " ms");
        sizeLabel.setText(formatSize(result.body == null ? 0 : result.body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length));

        renderBody(result.body);
        responseHeaders.setText(joinHeaders(result.headers));
        requestDetail.setText(buildRequestDetail(result, spec, params, headers));

        // 有错误时自动切到请求详情，便于排查
        if (result.error != null) {
            responseTabs.getSelectionModel().select(2);
        } else {
            responseTabs.getSelectionModel().select(0);
        }
    }

    private String buildRequestDetail(HttpEngine.Result result, HttpRequestSpec spec,
                                      List<Map.Entry<String, String>> params,
                                      List<Map.Entry<String, String>> headers) {
        StringBuilder sb = new StringBuilder();
        sb.append(spec.method.name()).append(' ').append(result.finalUrl).append('\n');
        sb.append('\n');
        sb.append("--- 请求头 ---\n");
        // 自动加的 Content-Type 也展示出来，和实际发送保持一致
        if (spec.hasBody() && !containsIgnoreCase(headers, "Content-Type")) {
            sb.append("Content-Type: ").append(spec.bodyType.contentType())
                    .append("; charset=UTF-8\n");
        }
        if (headers != null) {
            for (Map.Entry<String, String> e : headers) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
        }
        if (result.requestBody != null && !result.requestBody.isEmpty()) {
            sb.append('\n').append("--- 请求体 ---\n").append(result.requestBody).append('\n');
        }
        return sb.toString();
    }

    private boolean containsIgnoreCase(List<Map.Entry<String, String>> list, String key) {
        if (list == null) {
            return false;
        }
        return list.stream().anyMatch(e -> e.getKey() != null
                && e.getKey().equalsIgnoreCase(key));
    }

    // ---------------------------------------------------------------- 工具

    private void clearResponse() {
        statusLabel.setText("尚未发送请求");
        statusLabel.getStyleClass().removeAll(
                "status-ok", "status-fail", "status-redirect", "status-error");
        timeLabel.setText("");
        sizeLabel.setText("");
        responseBody.clear();
        responseJson.setJson("");
        rawBody = "";
        bodyIsJson = false;
        formatBodyBtn.setDisable(true);
        formatBodyBtn.setText("格式化");
        showBodyView(false);
        responseHeaders.clear();
        requestDetail.clear();
        hideError();
    }

    /**
     * 渲染响应体。
     *
     * <p>策略：
     * <ol>
     *   <li>内容是 JSON 且未超限 → 用带高亮的代码区显示，格式化后呈现</li>
     *   <li>内容是 JSON 但体积过大 → 退回纯文本，避免 UI 线程被样式计算拖住</li>
     *   <li>不是 JSON → 纯文本</li>
     * </ol>
     */
    private void renderBody(String body) {
        rawBody = body == null ? "" : body;
        String trimmed = rawBody.trim();
        // 只有对象/数组才算「值得高亮的 JSON」；裸字符串/数字走纯文本更合适
        boolean looksLikeJson = (trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"));

        String pretty = null;
        if (looksLikeJson) {
            pretty = tryPrettyJson(trimmed);
        }
        bodyIsJson = pretty != null && rawBody.length() <= HIGHLIGHT_LIMIT;

        if (bodyIsJson) {
            responseJson.setJson(pretty);
            showBodyView(true);
            formatBodyBtn.setDisable(false);
            formatBodyBtn.setText("压缩");
        } else {
            // 超限的 JSON 也走纯文本，但保留原始（未格式化的）内容以免二次开销
            responseBody.setText(rawBody);
            showBodyView(false);
            formatBodyBtn.setDisable(true);
            formatBodyBtn.setText("格式化");
        }
    }

    /** 在「纯文本」和「高亮代码区」两个视图之间切换可见性。 */
    private void showBodyView(boolean json) {
        responseJson.setVisible(json);
        responseJson.setManaged(json);
        responseBody.setVisible(!json);
        responseBody.setManaged(!json);
    }

    /** 「格式化 / 压缩」切换：在高亮视图里交替展示美化与原始文本。 */
    private void toggleBodyFormat() {
        if (!bodyIsJson) {
            return;
        }
        boolean currentlyPretty = formatBodyBtn.getText().equals("压缩");
        String target = currentlyPretty ? rawBody.trim() : tryPrettyJson(rawBody.trim());
        if (target == null) {
            target = rawBody.trim();
        }
        responseJson.setJson(target);
        formatBodyBtn.setText(currentlyPretty ? "格式化" : "压缩");
    }

    /** 尝试格式化为缩进 JSON；不是合法 JSON 时返回 {@code null}。 */
    private String tryPrettyJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            com.google.gson.Gson gson = new com.google.gson.GsonBuilder()
                    .setPrettyPrinting().create();
            return gson.toJson(com.google.gson.JsonParser.parseString(text));
        } catch (Exception notJson) {
            return null;
        }
    }

    private String joinHeaders(Map<String, String> headers) {
        StringBuilder sb = new StringBuilder();
        headers.forEach((k, v) -> sb.append(k).append(": ").append(v).append('\n'));
        return sb.toString();
    }

    private String formatSize(int bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private String reasonPhrase(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 304 -> "Not Modified";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 408 -> "Request Timeout";
            case 409 -> "Conflict";
            case 415 -> "Unsupported Media Type";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> "";
        };
    }

    /** 内联错误提示：紧贴触发它的请求栏下方，而不是页面底部。 */
    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
        if (!getChildren().contains(errorLabel)) {
            getChildren().add(2, errorLabel);
        }
    }

    private void hideError() {
        errorLabel.setText("");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }

    /** 供外部（批处理页）复用当前请求配置。 */
    public HttpRequestSpec snapshotSpec() {
        return currentSpec();
    }

    public List<Map.Entry<String, String>> snapshotParams() {
        return paramsTable.entries();
    }

    public List<Map.Entry<String, String>> snapshotHeaders() {
        return headersTable.entries();
    }

    /** 关闭时释放线程池。 */
    public void shutdown() {
        executor.shutdownNow();
    }

    // ------------------------------------------------------------ 测试钩子

    /**
     * 直接把一份响应结果喂给界面渲染逻辑，跳过真实网络请求。
     *
     * <p>包级可见：仅用于开发期探针验证 Body 高亮 / 视图切换，
     * 不是对外 API，也未在界面上暴露。
     */
    void applyResultForTest(HttpEngine.Result result) {
        render(result, snapshotSpec(), snapshotParams(), snapshotHeaders());
    }

    /** 测试用：当前 Body 是否走了 JSON 高亮视图。 */
    boolean bodyIsJsonForTest() {
        return bodyIsJson;
    }

    /** 测试用：高亮视图当前显示的文本。 */
    String highlightedBodyForTest() {
        return responseJson.getText();
    }

    /** 测试用：纯文本视图当前显示的文本。 */
    String plainBodyForTest() {
        return responseBody.getText();
    }

    /** 测试用：格式化按钮是否可用。 */
    boolean formatButtonEnabledForTest() {
        return !formatBodyBtn.isDisable();
    }

    /** 测试用：触发格式化 / 压缩切换。 */
    void toggleFormatForTest() {
        toggleBodyFormat();
    }

    /** 测试用：触发「清空响应」。 */
    void clearResponseForTest() {
        clearResponse();
    }
}
