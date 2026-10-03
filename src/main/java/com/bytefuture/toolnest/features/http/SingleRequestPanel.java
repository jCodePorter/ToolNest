package com.bytefuture.toolnest.features.http;

import com.bytefuture.toolnest.ui.components.Icons;
import com.bytefuture.toolnest.ui.components.KeyValueTable;
import com.bytefuture.toolnest.ui.components.UiKit;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 「单一请求」面板 —— 仿 Postman 的请求编辑器。
 *
 * <h2>布局（配置态）</h2>
 * <pre>
 * ┌ 方法▼ ┬ URL ────────────────┐ ┌ Send ┐   36
 * │ 超时(秒)[30]  …  GET 提示 / 占位符说明  │   32
 * ├ Params │ Headers(1) │ Body·Form ───────┤   56
 * │ [无|JSON|Form|Form-data|XML|Text|Binary]   │   38
 * │ （按类型换的编辑器）                 │  210
 * ├───────────────────────────────────────┤  312
 * │ [200 OK] 128ms 1.2KB ─ 格式化 清空响应 │   36
 * ├ Body │ 响应头 │ 请求详情 ──────────────┤   56
 * │ （空态）                              │   76
 * └───────────────────────────────────────┘  132
 * </pre>
 *
 * <h2>这个类在改什么</h2>
 * <p>改版前，五种语义完全不同的请求体压在同一个 {@code TextArea} 上：
 * 「类型」下拉只改编辑区的不透明度，不换编辑器形态。切到 {@code Form} 也只是
 * 让你在一个大文本框里手敲 {@code a=1&amp;b=2}，文件上传更是完全没有入口
 * （{@code BodyType} 枚举里没有 multipart）。
 *
 * <p>现在<b>每种类型有自己的编辑器</b>，切换时真的换节点（见 {@link #editorFor}）；
 * 「无」显示空态而不是一个灰掉的输入框。每种类型的编辑内容各自独立持有，
 * 切换只换可见节点，一个字符都不会丢。
 *
 * <p>另一条根因是纵向 chrome 占了 134px，把响应区挤出了首屏。现在按钮并进
 * 业务页签行、留白收紧，chrome 降到 64px，配置态正好铺满一屏不出滚动条。
 */
public final class SingleRequestPanel extends VBox {

    // ---------------------------------------------------------------- 请求行

    private final ComboBox<HttpRequestSpec.Method> methodBox = new ComboBox<>();
    private final TextField urlField = new TextField();
    private final Button sendButton = new Button("Send");
    private final Spinner<Integer> timeoutSpinner = new Spinner<>(1, 300, 30);
    /** GET / DELETE 带请求体时的信息级提示（不阻断）。 */
    private final Label methodBodyTip = UiKit.caption("");

    // ---------------------------------------------------------------- 请求页签

    private final KeyValueTable paramsTable = new KeyValueTable(210);
    private final KeyValueTable headersTable = new KeyValueTable(210);
    private final TabPane requestTabs = new TabPane();
    private final Tab bodyTab = new Tab("Body");

    /** 请求体类型分段控件：每个类型的项。 */
    private final ToggleGroup bodyTypeGroup = new ToggleGroup();
    private final Map<HttpRequestSpec.BodyType, ToggleButton> typeButtons =
            new EnumMap<>(HttpRequestSpec.BodyType.class);

    // ---------------------------------------------------------------- 编辑器

    /** JSON / XML / Text 共用同一个文本区 —— 切类型只换 promptText 与动作槽，
     *  否则「切到 XML 再切回 JSON，内容没了」。 */
    private final TextArea bodyArea = new TextArea();
    /** 类型 = NONE 时的空态。 */
    private final VBox bodyNoneState;
    /**
     * Form / Form-data <b>共用</b>的字段表（仿 Postman：两个类型选项，同一张表）。
     *
     * <p>选 Form 时隐藏「类型」列并整表按文本段编码；选 Form-data 时显示
     * 「类型」列，可逐行切文本 / 文件。两种类型的内容始终是同一份 ——
     * 旧版给 Form 单独一张键值表，切类型就换节点，来回切要重打一遍。
     */
    private final FormDataTable formDataTable = new FormDataTable(210);
    /** 类型 = Binary 的单文件选择器。 */
    private final Label binaryName = new Label("尚未选择文件");
    private final Label binaryMeta = UiKit.caption("");
    private final VBox binaryEditor;
    /** BINARY 选中的文件路径；null 表示尚未选择。 */
    private String binaryPath;
    /** 承载五种编辑器的槽，固定 210px。 */
    private final VBox editorHolder = new VBox();

    // ---------------------------------------------------------------- 工具行

    private final Label ctypeLabel = new Label("");
    private final Label guardText = new Label("");
    private final Node guardIcon = Icons.of("warn", 13, "icon-warning");
    private final HBox statusBox = new HBox(6);
    private final HBox actionSlot = new HBox(8);
    private final Button jsonFormatBtn = compactSecondary("格式化");
    /** 「+ Token」快捷按钮（Headers 工具行）。 */
    private Button tokenHeader;
    private final Button formAddBtn = compactSecondary("+ 新增字段");
    private final Button formClearBtn = compactSecondary("清空");
    private final Button binaryPickBtn = compactSecondary("更换");
    private final Button binaryClearBtn = compactSecondary("清除");

    // ---------------------------------------------------------------- 响应区

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
    private final com.bytefuture.toolnest.ui.components.JsonCodeArea responseJson =
            new com.bytefuture.toolnest.ui.components.JsonCodeArea();
    /** 承载 Body 两种视图的容器，运行期在它们之间切换可见性。 */
    private final StackPane bodyHolder = new StackPane();
    /** 还没发过请求时响应区显示的空态（比整页空态矮一档，见 .empty-state-compact）。 */
    private final VBox responseEmpty;
    /** 是否已经有过一次响应（决定响应区显示空态还是结果）。 */
    private boolean hasResponse;
    /** Body 的格式化按钮，仅在当前内容是 JSON 时可用。 */
    private final Button formatBodyBtn = compactSecondary("格式化");

    // ---------------------------------------------------------------- 折叠查看态

    private final VBox requestBlock = new VBox(14);
    private final HBox strip = new HBox(10);
    private final Label stripMethod = new Label("");
    private final Label stripUrl = new Label("");
    private final Label stripMeta = new Label("");
    private final Button resendButton = new Button("重发");
    private final Button expandButton = compactSecondary("展开编辑");

    /**
     * 超过这个大小就不做语法高亮。
     *
     * <p>逐字符跑正则 + 构建 StyleSpans 是 O(n) 但常数很大，几 MB 的响应体会卡住
     * UI 线程。这类大响应本来就该靠搜索定位，纯文本显示更实用。
     */
    private static final int HIGHLIGHT_LIMIT = 256 * 1024;

    /** 响应体文本区的常态提示语（「请求中…」用完要还回来）。 */
    private static final String RESPONSE_PROMPT = "响应内容会显示在这里";

    /** 当前 Body 是否是 JSON（决定「格式化」按钮与高亮视图是否可用）。 */
    private boolean bodyIsJson = false;
    /** 当前 Body 的原始文本（未格式化），供「格式化」按钮反复切换。 */
    private String rawBody = "";

    /**
     * 单线程池：串行发送，避免用户连点造成并发。
     *
     * <p>不能建成 final 单例：这个页面被 {@code ContentPane} 缓存复用，
     * 切走时 {@link #shutdown()} 会把池关掉，再切回来用的还是同一个面板实例，
     * 拿已关闭的池去 {@code submit} 会抛 {@code RejectedExecutionException}，
     * 表现为「Send 按钮永久卡在发送中」。
     */
    private ExecutorService executor;

    /** 取当前可用的线程池，已关闭或尚未创建就新建一个。 */
    private ExecutorService executor() {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "http-single");
                t.setDaemon(true);
                return t;
            });
        }
        return executor;
    }

    public SingleRequestPanel() {
        getStyleClass().add("http-panel");
        setSpacing(14);

        bodyNoneState = buildNoneState();
        binaryEditor = buildBinaryEditor();
        responseEmpty = buildResponseEmpty();
        buildEditorSlot();
        buildStrip();

        requestBlock.getChildren().addAll(buildRequestTop(), buildRequestTabs());

        getChildren().addAll(requestBlock, strip, errorLabel,
                buildResponseBar(), buildResponseTabs());
        strip.setVisible(false);
        strip.setManaged(false);
        hideError();

        selectBodyType(HttpRequestSpec.BodyType.NONE);
        refreshToolRow();
        setResponded(false);
    }

    private static Button compactSecondary(String text) {
        Button button = UiKit.secondaryButton(text);
        button.getStyleClass().add("button-compact");
        return button;
    }

    // ---------------------------------------------------------------- 请求区

    private Node buildRequestTop() {
        methodBox.getItems().addAll(HttpRequestSpec.Method.values());
        methodBox.getSelectionModel().select(HttpRequestSpec.Method.GET);
        methodBox.getStyleClass().add("http-method");
        methodBox.setPrefWidth(124);
        methodBox.setMinWidth(124);
        methodBox.valueProperty().addListener((o, old, now) -> refreshToolRow());

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

        // 超时与占位符说明属于「怎么发」而不是「发给谁」，放请求行下方
        Label timeoutLabel = UiKit.caption("超时（秒）");
        timeoutSpinner.setPrefWidth(84);
        timeoutSpinner.setEditable(true);

        methodBodyTip.getStyleClass().add("http-guard");
        methodBodyTip.setVisible(false);
        methodBodyTip.setManaged(false);

        Label placeholderTip = UiKit.caption(
                "URL 与请求体中支持 {列名} 占位符，批处理时按 CSV 行替换。");

        HBox sub = new HBox(8, timeoutLabel, timeoutSpinner, methodBodyTip,
                UiKit.spacer(), placeholderTip);
        sub.setAlignment(Pos.CENTER_LEFT);
        sub.getStyleClass().add("http-hint-row");

        // 15px 的说明行已并入工具行（Content-Type 常驻位），这里只剩「怎么发」
        return new VBox(8, bar, sub);
    }

    private Node buildRequestTabs() {
        // ---- Params ----
        Button addParams = compactSecondary("+ 新增参数");
        addParams.setOnAction(e -> paramsTable.addRow());
        Button clearParams = compactSecondary("清空");
        clearParams.setOnAction(e -> paramsTable.clear());

        VBox paramsBox = new VBox(8,
                toolRow(addParams, clearParams, UiKit.spacer(),
                        UiKit.caption("这些键值会以 query 参数拼到 URL 之后")),
                paramsTable);
        VBox.setVgrow(paramsTable, Priority.ALWAYS);

        // ---- Headers ----
        // Authorization 的值在失焦时补 Bearer 前缀：用户粘贴裸 token 后
        // 点别处即可，不用记得自己敲前缀。
        // 必须按 key 分派 —— Content-Type 的值不能被套上 Bearer 前缀。
        headersTable.setValueNormalizer(SingleRequestPanel::normalizeHeaderValue);

        Button addHeaders = compactSecondary("+ 新增请求头");
        addHeaders.setOnAction(e -> headersTable.addRow());
        Button clearHeaders = compactSecondary("清空");
        clearHeaders.setOnAction(e -> headersTable.clear());

        Button jsonHeader = compactSecondary("+ JSON");
        jsonHeader.setOnAction(e -> {
            headersTable.setEntries(Map.of("Content-Type", "application/json"));
            refreshToolRow();
        });

        tokenHeader = compactSecondary("+ Token");
        tokenHeader.setAccessibleText("添加 Authorization 请求头");
        tokenHeader.setOnAction(e -> {
            // 先落 key、把值留空，焦点送进 VALUE 格 —— 用户直接打 token 即可。
            // 不预填 "Bearer "：那会让用户看到一条半截的值，还得自己删掉再打，
            // 而且复制出去时也会带上这个占位前缀。
            int row = headersTable.addEntry("Authorization", "", null);
            if (row >= 0) {
                headersTable.focusCell(row, 1);
            }
            refreshToolRow();
        });

        VBox headersBox = new VBox(8,
                toolRow(jsonHeader, tokenHeader, addHeaders, clearHeaders, UiKit.spacer(),
                        UiKit.caption("常用请求头")),
                headersTable);
        VBox.setVgrow(headersTable, Priority.ALWAYS);

        // ---- Body ----
        bodyArea.setPromptText("请求体内容，例如 {\"orderId\": \"{orderId}\"}");
        bodyArea.getStyleClass().add("mono-area");
        bodyArea.setWrapText(true);
        bodyArea.textProperty().addListener((o, old, now) -> {
            refreshToolRow();
            refreshEmptyHint();
        });

        Tab paramsTab = new Tab("Params", paramsBox);
        Tab headersTab = new Tab("Headers", headersBox);
        bodyTab.setContent(new VBox(8, buildToolbar(), editorHolder));

        requestTabs.getStyleClass().addAll("http-tabs", "http-req-tabs");
        requestTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        requestTabs.getTabs().addAll(paramsTab, headersTab, bodyTab);

        // Params / Headers 的 Tab 文字带数量角标，收起时也能看出填了几项
        paramsTable.setOnRowsCountChanged(n ->
                paramsTab.setText("Params" + (n > 0 ? " (" + n + ")" : "")));
        headersTable.setOnRowsCountChanged(n ->
                headersTab.setText("Headers" + (n > 0 ? " (" + n + ")" : "")));
        formDataTable.setOnChanged(this::refreshBodyTabTitle);
        return requestTabs;
    }

    /**
     * 页签正文顶部那条工具行（38px）。
     *
     * <p>Params / Headers 用它放「清空」与说明；Body 用它放类型分段控件 +
     * Content-Type / 守卫提示 + 动作槽。三条都必须正好 38 —— 它与下面的表体
     * 合计成 256，是 312 页签的内部账。
     */
    private HBox toolRow(Node... nodes) {
        HBox row = new HBox(8, nodes);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("http-toolbar");
        return row;
    }

    // ---------------------------------------------------------------- Body 工具行

    private Node buildToolbar() {
        HBox seg = new HBox(2);
        seg.getStyleClass().addAll("segmented", "http-type-seg");
        seg.setAlignment(Pos.CENTER_LEFT);
        for (HttpRequestSpec.BodyType type : HttpRequestSpec.BodyType.values()) {
            ToggleButton item = new ToggleButton(type.label());
            item.getStyleClass().add("segmented-item");
            item.setToggleGroup(bodyTypeGroup);
            item.setOnAction(e -> {
                if (!item.isSelected()) {
                    // 已选中的项再点一次不应该变成「什么都没选」
                    item.setSelected(true);
                    return;
                }
                selectBodyType(type);
            });
            typeButtons.put(type, item);
            seg.getChildren().add(item);
        }

        ctypeLabel.getStyleClass().add("http-ctype");
        guardText.getStyleClass().add("http-guard");
        guardText.setMaxWidth(Region.USE_PREF_SIZE);
        statusBox.setAlignment(Pos.CENTER_LEFT);
        statusBox.getChildren().addAll(guardIcon, ctypeLabel, guardText);

        // 动作槽：只放「当前类型真的可用」的动作。
        // 旧版把「格式化」常量显示在非 JSON 类型下，点了没反应。
        jsonFormatBtn.setOnAction(e -> toggleRequestFormat());
        formAddBtn.setOnAction(e -> formDataTable.addRow());
        formClearBtn.setOnAction(e -> formDataTable.clear());
        binaryPickBtn.setOnAction(e -> chooseBinaryFile());
        binaryClearBtn.setOnAction(e -> {
            binaryPath = null;
            refreshBinaryMeta();
            refreshToolRow();
        });
        actionSlot.setAlignment(Pos.CENTER_LEFT);

        HBox row = toolRow(seg, UiKit.spacer(), statusBox, actionSlot);
        return row;
    }

    // ---------------------------------------------------------------- 编辑器

    private void buildEditorSlot() {
        editorHolder.getStyleClass().add("http-editor");
        editorHolder.setFillWidth(true);
        editorHolder.getChildren().addAll(bodyNoneState, bodyArea, formDataTable,
                binaryEditor);
        VBox.setVgrow(bodyArea, Priority.ALWAYS);
        VBox.setVgrow(formDataTable, Priority.ALWAYS);
    }

    private VBox buildNoneState() {
        VBox box = UiKit.emptyState("slash-circle", "本次请求不带请求体",
                "选择 JSON / Form / Form-data 等类型即可开始编辑。");
        box.setMaxWidth(Double.MAX_VALUE);
        box.setMaxHeight(Double.MAX_VALUE);
        return box;
    }

    /**
     * 响应区的空态。
     *
     * <p>用紧凑档：配置态里响应页签正文只有 76px（页签 132 − 页签头 56），
     * 而 {@code .empty-state} 的内边距 26 + 间距 9 单独就吃掉 78 ——
     * 直接放进去会把页签顶高，整页竖向预算当场破。
     */
    private VBox buildResponseEmpty() {
        VBox box = UiKit.emptyState("globe", "尚未发送请求",
                "填写地址后点 Send，响应会显示在这里");
        box.getStyleClass().add("empty-state-compact");
        box.setMaxWidth(Double.MAX_VALUE);
        box.setMaxHeight(Double.MAX_VALUE);
        return box;
    }

    private VBox buildBinaryEditor() {
        binaryName.getStyleClass().add("http-file-name");
        binaryName.setMaxWidth(Double.MAX_VALUE);

        Button pick = UiKit.secondaryButton("选择文件");
        pick.setOnAction(e -> chooseBinaryFile());

        HBox row = new HBox(10, pick, binaryName);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(binaryName, Priority.ALWAYS);

        VBox box = new VBox(10, row, binaryMeta);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /** 当前选中的请求体类型。 */
    private HttpRequestSpec.BodyType selectedBodyType() {
        Toggle toggle = bodyTypeGroup.getSelectedToggle();
        for (Map.Entry<HttpRequestSpec.BodyType, ToggleButton> e : typeButtons.entrySet()) {
            if (e.getValue() == toggle) {
                return e.getKey();
            }
        }
        return HttpRequestSpec.BodyType.NONE;
    }

    /**
     * 切换请求体类型 —— <b>真的换编辑器节点</b>。
     *
     * <p>这是本轮改造的核心。五种类型的编辑方式不同：
     * <ul>
     *   <li>{@code NONE} → 空态（不是「灰掉的输入框」）</li>
     *   <li>{@code JSON / XML / TEXT} → 共用一个等宽文本区，只换提示语</li>
     *   <li>{@code FORM} → 键值表</li>
     *   <li>{@code FORM_DATA} → 逐行「文本 / 文件」表</li>
     *   <li>{@code BINARY} → 单文件选择器</li>
     * </ul>
     * 每种类型的模型各自独立持有，切换只换可见节点，内容一个都不丢。
     */
    private void selectBodyType(HttpRequestSpec.BodyType type) {
        ToggleButton item = typeButtons.get(type);
        if (item != null) {
            item.setSelected(true);
        }
        bodyArea.setPromptText(switch (type) {
            case JSON -> "请求体内容，例如 {\"orderId\": \"{orderId}\"}";
            case XML -> "请求体内容，例如 <order id=\"{orderId}\"/>";
            case TEXT -> "纯文本请求体";
            default -> "请求体内容";
        });
        // Form（x-www-form-urlencoded）传不了文件 → 隐藏「类型」列；
        // Form-data 逐行可选文本 / 文件。两种类型共用同一张表。
        formDataTable.setFileRowsAllowed(type == HttpRequestSpec.BodyType.FORM_DATA);
        showEditor(type);
        // 类型 = 无时整组降透明 + 空态文案说明原因（不是 setDisable）：
        // 设计稿 §7「禁用态必须配文字，不能只做 setDisable」
        UiKit.setDegraded(editorHolder, null, type == HttpRequestSpec.BodyType.NONE);
        refreshToolRow();
        refreshBodyTabTitle();
        refreshEmptyHint();
    }

    /** 逐类型切换可见的编辑器。 */
    private void showEditor(HttpRequestSpec.BodyType type) {
        Node target = editorFor(type);
        for (Node node : editorHolder.getChildren()) {
            boolean on = node == target;
            node.setVisible(on);
            node.setManaged(on);
        }
    }

    /** 类型 → 编辑器节点的映射（对探针断言可见）。 */
    private Node editorFor(HttpRequestSpec.BodyType type) {
        return switch (type) {
            case NONE -> bodyNoneState;
            // Form 与 Form-data 共用一张表（Postman 的做法）：
            // 区别只是「行里能不能选文件」，由 setFileRowsAllowed 控制。
            case FORM, FORM_DATA -> formDataTable;
            case BINARY -> binaryEditor;
            default -> bodyArea;
        };
    }

    /** 空态里那行说明 —— 「文本区还留着 JSON」时告诉用户切回去就能拿回来。 */
    private void refreshEmptyHint() {
        UiKit.setEmptyStateHint(bodyNoneState, bodyArea.getText().isBlank()
                ? "选择 JSON / Form / Form-data 等类型即可开始编辑。"
                : "已保留之前输入的 JSON 内容，切回 JSON 即恢复。");
    }

    /** 补上 Form-data 用到的文件选择（选择路径 + 同步大小）。 */
    private void chooseBinaryFile() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("选择要作为请求体发送的文件");
        java.io.File file = chooser.showOpenDialog(
                getScene() == null ? null : getScene().getWindow());
        if (file != null) {
            binaryPath = file.getAbsolutePath();
            refreshBinaryMeta();
            refreshToolRow();
        }
    }

    private void refreshBinaryMeta() {
        if (binaryPath == null || binaryPath.isBlank()) {
            binaryName.setText("尚未选择文件");
            binaryMeta.setText("整份文件会原样作为请求体发出。");
            return;
        }
        java.nio.file.Path path = java.nio.file.Path.of(binaryPath);
        binaryName.setText(path.getFileName().toString());
        binaryName.setTooltip(new Tooltip(binaryPath));
        try {
            binaryMeta.setText(FormDataTable.formatSize(java.nio.file.Files.size(path))
                    + " · " + binaryPath);
        } catch (Exception unreadable) {
            binaryMeta.setText("文件不存在或无法读取：" + binaryPath);
        }
    }

    // ---------------------------------------------------------------- 工具行刷新

    /** 请求体当前的真实形态（用户所选类型）。 */
    private HttpRequestSpec.BodyType declaredType() {
        return selectedBodyType();
    }

    /** 实际会发出去的类型 —— 「无」而文本区有内容时会按内容形状推断。 */
    private HttpRequestSpec.BodyType effectiveType() {
        return HttpRequestSpec.inferBodyType(declaredType(), bodyArea.getText());
    }

    /**
     * 刷新工具行右侧：Content-Type 常驻位 / 守卫提示 + 动作槽。
     *
     * <p>两条守卫（都对应一个实测到的缺陷）：
     * <ol>
     *   <li><b>Content-Type 冲突</b>：请求头里已设了一个与当前类型不一致的值
     *       —— 实测它会真的赢（引擎只在没手设时才补），界面必须说出来，
     *       但不替用户改（自己改了更难排查）。</li>
     *   <li><b>按推断类型发送</b>：类型选了「无」而编辑区有内容，
     *       {@code inferBodyType} 会按内容形状挑一个 —— 以前是静默生效的，
     *       现在明写一句。</li>
     * </ol>
     */
    /**
     * 请求头值的失焦规范化 —— <b>只对 {@code Authorization} 生效</b>。
     *
     * <p>其余头一律原样返回：{@code Content-Type: application/json} 这种值
     * 里没有空白，若也套「裸 token 就补前缀」的规则，会被改成
     * {@code Bearer application/json} —— 把一条本来正确的头改坏。
     */
    static String normalizeHeaderValue(String key, String value) {
        if (key == null || !"Authorization".equalsIgnoreCase(key.trim())) {
            return value;
        }
        return withBearer(value);
    }

    /**
     * 补上 {@code Authorization} 的认证方案前缀。
     *
     * <p>用户从别处粘来的 token 往往只有裸串（{@code eyJhbGciOi...}），
     * 直接发出去服务端会认不出。而已经带前缀的不该被改写成
     * {@code Bearer Bearer xxx}。
     *
     * <h2>为什么判「有没有 scheme」而不是判「有没有 Bearer」</h2>
     * <p>{@code Authorization} 的 scheme 是 HTTP 规范里的
     * <b>token</b>（{@code Basic} / {@code Bearer} / {@code Digest} / {@code Token} ...），
     * 不是只有 Bearer 一种。用 {@code contains("Bearer")} 判断会漏掉
     * {@code Basic dXNlcjpwYXNz} 这种 —— 它不含 "Bearer"，于是被错加成
     * {@code Bearer Basic dXNlcjpwYXNz}，把一个能用的头改成必然被拒的头。
     *
     * <p>所以判据是「首个空白之前是否已经是一个合法 scheme」：有就原样保留，
     * 没有就把裸串当作 token，补上 {@code Bearer }。
     *
     * <p>所以判据分两步：<b>整串没有空白 → 它就是一个裸 token</b>（JWT 形如
     * {@code aaa.bbb.ccc}，没有空格），直接补前缀；有空白才看首段是不是合法 scheme。
     * 第一步不能省 —— JWT 里全是 {@code . _ -} 这些 scheme 合法字符，
     * 只按「首段是否合法」判会把整个 JWT 误当成一个 scheme 而漏掉前缀。
     *
     * <p>空串返回 {@code ""}（不补）：那是用户还没打字的空行，补上前缀会
     * 让表里凭空出现一条 {@code Authorization: Bearer }。
     */
    static String withBearer(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return "";
        }
        int space = trimmed.indexOf(' ');
        if (space < 0) {
            return "Bearer " + trimmed;
        }
        String head = trimmed.substring(0, space);
        // RFC 7235 token：首字符必须是字母，其余是字母数字与 - . _ ~ + /
        boolean hasScheme = !head.isEmpty()
                && Character.isLetter(head.charAt(0))
                && head.chars().skip(1).allMatch(c -> Character.isLetterOrDigit(c)
                        || c == '-' || c == '.' || c == '_' || c == '~' || c == '+' || c == '/');
        return hasScheme ? trimmed : "Bearer " + trimmed;
    }

    private void refreshToolRow() {
        HttpRequestSpec.BodyType declared = declaredType();
        HttpRequestSpec.BodyType effective = effectiveType();

        // ---- 动作槽 ----
        actionSlot.getChildren().clear();
        switch (declared) {
            case JSON -> actionSlot.getChildren().add(jsonFormatBtn);
            case FORM, FORM_DATA -> actionSlot.getChildren().addAll(formAddBtn, formClearBtn);
            case BINARY -> actionSlot.getChildren().addAll(binaryPickBtn, binaryClearBtn);
            default -> {
                // XML / Text / 无：没有只属于这一种类型的动作，槽就空着
            }
        }

        // ---- 右侧状态位 ----
        String headerCtype = headerContentType();
        boolean conflict = headerCtype != null
                && declared != HttpRequestSpec.BodyType.NONE
                && !sameHeaderValue(headerCtype, declared);
        String guard;
        if (conflict) {
            guard = "请求头里已设 Content-Type: " + headerCtype
                    + "，与当前类型 " + declared.label() + " 不一致，将按请求头发送";
        } else if (declared == HttpRequestSpec.BodyType.NONE
                && effective != HttpRequestSpec.BodyType.NONE) {
            guard = "本次按 " + effective.label()
                    + " 发送（类型选了「无」，编辑区有内容）";
        } else {
            guard = null;
        }

        guardText.setText(guard == null ? "" : guard);
        guardText.setTooltip(guard == null ? null : new Tooltip(guard));
        setShown(guardText, guard != null);
        setShown(guardIcon, guard != null);

        String ctype = guard == null ? effectiveContentType(effective) : "";
        ctypeLabel.setText(ctype);
        setShown(ctypeLabel, guard == null && !ctype.isBlank());

        refreshMethodTip(effective);
    }

    /** 条上常驻显示的实际 Content-Type。 */
    private String effectiveContentType(HttpRequestSpec.BodyType type) {
        return switch (type) {
            case NONE -> "";
            case FORM -> "application/x-www-form-urlencoded; charset=UTF-8";
            case BINARY -> "application/octet-stream";
            default -> type.contentType() + "; charset=UTF-8";
        };
    }

    /** 请求头表里手设的 Content-Type，没有则 null。 */
    private String headerContentType() {
        for (Map.Entry<String, String> e : headersTable.entries()) {
            if ("Content-Type".equalsIgnoreCase(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    private static boolean sameHeaderValue(String headerValue, HttpRequestSpec.BodyType type) {
        if (headerValue == null) {
            return false;
        }
        String base = headerValue.split(";")[0].trim();
        return base.equalsIgnoreCase(type.contentType());
    }

    /**
     * 守卫②的另一半：GET / DELETE 带请求体。
     *
     * <p>实测 JDK 的 {@code HttpClient} 不拦它，请求会带着 body 真的发出去
     * （{@code status=200}），而多数服务端会忽略 —— 行为不可预期，
     * 所以提示但<b>不阻断</b>。
     */
    private void refreshMethodTip(HttpRequestSpec.BodyType effective) {
        boolean carriesBody = switch (effective) {
            case NONE -> false;
            case FORM_DATA -> !formDataTable.fields().isEmpty();
            case BINARY -> binaryPath != null && !binaryPath.isBlank();
            default -> !bodyArea.getText().isBlank();
        };
        HttpRequestSpec.Method method = methodBox.getValue();
        boolean risky = carriesBody && method != null && !method.allowsBody();
        methodBodyTip.setText(risky
                ? method.name() + " 带请求体会照常发出，多数服务端会忽略。"
                : "");
        setShown(methodBodyTip, risky);
    }

    /** Body 页签标题带摘要 —— 切到别的页签时也知道请求体是什么形态。 */
    private void refreshBodyTabTitle() {
        HttpRequestSpec.BodyType type = declaredType();
        String title = switch (type) {
            case NONE -> {
                HttpRequestSpec.BodyType inferred = effectiveType();
                yield inferred == HttpRequestSpec.BodyType.NONE
                        ? "Body" : "Body · 按 " + inferred.label();
            }
            case FORM -> "Body · Form (" + formDataTable.entryCount() + " 项)";
            case FORM_DATA -> {
                int n = formDataTable.entryCount();
                int files = formDataTable.fileCount();
                yield "Body · Form-data (" + n + " 项"
                        + (files > 0 ? ", 含 " + files + " 个文件" : "") + ")";
            }
            case BINARY -> "Body · Binary"
                    + (binaryPath == null || binaryPath.isBlank() ? "" : " (1 个文件)");
            default -> "Body · " + type.label();
        };
        bodyTab.setText(title);
    }

    /** 「格式化 / 压缩」——只对 JSON 有意义，动作槽里也只在 JSON 下出现。 */
    private void toggleRequestFormat() {
        String text = bodyArea.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        String trimmed = text.trim();
        boolean alreadyPretty = trimmed.contains("\n");
        try {
            com.google.gson.Gson pretty = new com.google.gson.GsonBuilder()
                    .setPrettyPrinting().create();
            com.google.gson.JsonElement parsed =
                    com.google.gson.JsonParser.parseString(trimmed);
            String target = alreadyPretty ? parsed.toString() : pretty.toJson(parsed);
            String before = text;
            bodyArea.setText(target);
            jsonFormatBtn.setText(alreadyPretty ? "格式化" : "压缩");
            if (target.equals(before)) {
                // 内容没变（本来就是紧凑的）——把按钮文案摆回「格式化」，
                // 否则会出现「点了没反应、按钮却说下次是压缩」
                jsonFormatBtn.setText("格式化");
            }
        } catch (Exception notJson) {
            // JSON 不合法就保持原样，不打断输入
        }
    }

    // ---------------------------------------------------------------- 响应区

    private Node buildResponseBar() {
        statusLabel.getStyleClass().add("http-status");
        timeLabel.getStyleClass().add("http-metric");
        sizeLabel.getStyleClass().add("http-metric");

        HBox metrics = new HBox(16, statusLabel, timeLabel, sizeLabel);
        metrics.setAlignment(Pos.CENTER_LEFT);

        Button clearBtn = compactSecondary("清空响应");
        clearBtn.setOnAction(e -> clearResponse());

        Label hint = UiKit.caption("识别到 JSON 时自动高亮");

        HBox row = new HBox(10, metrics, UiKit.spacer(), hint, formatBodyBtn, clearBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("http-response-bar");
        return row;
    }

    private Node buildResponseTabs() {
        responseTabs.getStyleClass().addAll("http-tabs", "http-resp-tabs");
        responseTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        responseBody.setPromptText(RESPONSE_PROMPT);
        responseHeaders.setPromptText("响应头会显示在这里");
        // 提示写一行：TextArea 的 promptText 不渲染换行，写成两行只会被
        // 压成一行（中间凭空多一个空格），看起来像排版坏了。
        requestDetail.setPromptText("实际发出的请求会显示在这里（含替换占位符后的最终 URL）");

        // Body 两个视图叠放：默认显示纯文本，命中 JSON 时切到高亮视图
        responseJson.setPlaceholderText("响应内容会显示在这里");
        responseJson.setVisible(false);
        responseJson.setManaged(false);
        bodyHolder.getChildren().addAll(responseBody, responseJson);
        bodyHolder.setAlignment(Pos.TOP_LEFT);

        formatBodyBtn.setDisable(true);
        formatBodyBtn.setOnAction(e -> toggleBodyFormat());

        StackPane bodySlot = new StackPane(responseEmpty, bodyHolder);
        bodySlot.setAlignment(Pos.TOP_LEFT);

        responseTabs.getTabs().addAll(
                new Tab("Body", bodySlot),
                new Tab("响应头", wrapArea(responseHeaders)),
                new Tab("请求详情", wrapArea(requestDetail)));
        // 配置态由 .http-resp-tabs 保底 132；查看态要把 maxHeight 放开，
        // 否则 VBox 只会给它 pref 高，剩下的空间全变成空白
        responseTabs.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(responseTabs, Priority.ALWAYS);
        return responseTabs;
    }

    /** 是否已发过请求 —— 决定响应区显示空态还是结果。 */
    private void setResponded(boolean responded) {
        hasResponse = responded;
        setShown(responseEmpty, !responded);
        setShown(bodyHolder, responded);
    }

    private Node wrapArea(TextArea area) {
        StackPane pane = new StackPane(area);
        pane.setAlignment(Pos.TOP_LEFT);
        return pane;
    }

    // ---------------------------------------------------------------- 折叠查看态

    private void buildStrip() {
        stripMethod.getStyleClass().add("http-method-badge");
        stripUrl.getStyleClass().add("http-strip-url");
        stripUrl.setTextOverrun(OverrunStyle.ELLIPSIS);
        HBox.setHgrow(stripUrl, Priority.ALWAYS);
        stripMeta.getStyleClass().add("http-strip-meta");

        expandButton.setOnAction(e -> setCollapsed(false));
        resendButton.getStyleClass().addAll("button", "button-primary", "button-compact");
        resendButton.setOnAction(e -> send());

        strip.getChildren().addAll(stripMethod, stripUrl, stripMeta, UiKit.spacer(),
                expandButton, resendButton);
        strip.setAlignment(Pos.CENTER_LEFT);
        strip.getStyleClass().add("http-strip");
    }

    /**
     * 配置区折叠 / 展开。
     *
     * <p>折叠的时机是<b>按下 Send 的一瞬间</b>（不是等响应回来）——
     * 这样「请求中…」也直接出现在被放大的响应区里，不用等一个来回。
     */
    private void setCollapsed(boolean collapsed) {
        requestBlock.setVisible(!collapsed);
        requestBlock.setManaged(!collapsed);
        setShown(strip, collapsed);
        if (collapsed) {
            refreshStrip();
        }
    }

    private void refreshStrip() {
        HttpRequestSpec.Method method = methodBox.getValue();
        stripMethod.setText(method == null ? "GET" : method.name());
        stripUrl.setText(urlField.getText() == null ? "" : urlField.getText());
        stripUrl.setTooltip(new Tooltip(stripUrl.getText()));
        stripMeta.setText(bodySummary() + "  ·  " + timeoutSpinner.getValue() + "s");
    }

    /** 摘要条上的请求体描述。 */
    private String bodySummary() {
        HttpRequestSpec.BodyType type = declaredType();
        return switch (type) {
            case NONE -> {
                HttpRequestSpec.BodyType inferred = effectiveType();
                yield inferred == HttpRequestSpec.BodyType.NONE
                        ? "无请求体" : "按 " + inferred.label();
            }
            case FORM -> "Form (" + formDataTable.entryCount() + " 项)";
            case FORM_DATA -> {
                int n = formDataTable.entryCount();
                int files = formDataTable.fileCount();
                yield "Form-data (" + n + " 项" + (files > 0 ? ", 含 " + files + " 个文件" : "") + ")";
            }
            case BINARY -> binaryPath == null || binaryPath.isBlank()
                    ? "Binary（未选文件）"
                    : "Binary · " + java.nio.file.Path.of(binaryPath).getFileName();
            default -> type.label() + " · " + bodyArea.getText().length() + " 字符";
        };
    }

    // ---------------------------------------------------------------- 发送

    /** 把共用字段表里的文本段转成引擎要的键值对（Form 模式用）。 */
    private List<Map.Entry<String, String>> formEntries() {
        List<Map.Entry<String, String>> list = new java.util.ArrayList<>();
        for (HttpRequestSpec.FormField field : formDataTable.fields()) {
            if (field.isFile()) {
                // Form 模式已隐藏「类型」列，正常到不了这里；
                // 真出现（探针直接塞文件行）也不能把本地路径当值发出去。
                continue;
            }
            list.add(new java.util.AbstractMap.SimpleEntry<>(
                    field.name, field.text == null ? "" : field.text));
        }
        return list;
    }

    /** 组装当前界面上的请求描述。 */
    public HttpRequestSpec currentSpec() {
        HttpRequestSpec.BodyType declared = declaredType();
        HttpRequestSpec.BodyType effective = effectiveType();
        HttpRequestSpec.Method method = methodBox.getValue();

        String body = switch (effective) {
            case FORM, FORM_DATA, BINARY -> "";
            default -> bodyArea.getText();
        };
        // Form 与 Form-data 共用一张表，但引擎要的是两种不同的模型：
        // Form 收键值对（引擎负责百分号编码），Form-data 收逐段模型。
        List<Map.Entry<String, String>> formFields = effective == HttpRequestSpec.BodyType.FORM
                ? formEntries() : List.of();
        List<HttpRequestSpec.FormField> formData = effective == HttpRequestSpec.BodyType.FORM_DATA
                ? formDataTable.fields() : List.of();

        return new HttpRequestSpec(method, urlField.getText(), effective, body,
                formFields, formData, binaryPath);
    }

    private void send() {
        String url = urlField.getText();
        if (url == null || url.isBlank()) {
            showError("请先填写请求地址");
            return;
        }
        // 单一请求里如果还留着占位符，提醒一下（不阻断）。请求体同样要查，
        // 否则「URL 干净、Body 里漏改占位符」这种最常见的疏忽会静默发出去。
        Set<String> placeholders = new java.util.LinkedHashSet<>();
        placeholders.addAll(TemplateResolver.placeholders(url));
        placeholders.addAll(TemplateResolver.placeholders(bodyArea.getText()));
        if (!placeholders.isEmpty()) {
            showError("仍有未替换的占位符 " + placeholders
                    + "，单一请求模式下会按字面量发送。");
        } else {
            hideError();
        }

        HttpRequestSpec spec = currentSpec();
        List<Map.Entry<String, String>> params = paramsTable.entries();
        List<Map.Entry<String, String>> headers = headersTable.entries();
        Duration timeout = Duration.ofSeconds(timeoutSpinner.getValue());

        // 按下 Send 的一瞬间就折叠配置区：响应区立刻拿到 432px，
        // 「请求中…」直接出现在放大后的区域里
        setCollapsed(true);
        refreshStrip();

        sendButton.setDisable(true);
        sendButton.setText("发送中…");
        statusLabel.setText("请求中…");
        // 「尚未发送请求」在这句话刚说出口时就已经不成立了 —— 让响应区一起
        // 进入请求中状态，否则上半条写着「请求中…」、下半块还写着「尚未发送请求」。
        responseBody.setPromptText("请求中…");
        setResponded(true);

        try {
            executor().submit(() -> {
                HttpEngine.Result result = HttpEngine.send(spec, params, headers, timeout);
                Platform.runLater(() -> {
                    render(result, spec, params, headers);
                    sendButton.setDisable(false);
                    sendButton.setText("Send");
                });
            });
        } catch (RuntimeException rejected) {
            // 提交不进去也要把界面还原，不能留下一个点不动的 Send
            sendButton.setDisable(false);
            sendButton.setText("Send");
            statusLabel.setText("无法发送");
            showError("请求无法提交：" + rejected.getMessage());
        }
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
        // 优先用响应体的真实字节数；bodyBytes 为 0 说明结果不是引擎产出的
        // （如探针直接构造），退回按 UTF-8 估算
        int byteCount = result.bodyBytes > 0
                ? result.bodyBytes
                : (result.body == null ? 0
                        : result.body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        sizeLabel.setText(formatSize(byteCount));

        responseBody.setPromptText(RESPONSE_PROMPT);
        renderBody(result.body);
        responseHeaders.setText(joinHeaders(result.headers));
        requestDetail.setText(buildRequestDetail(result, spec, params, headers));
        setResponded(true);

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
        responseBody.setPromptText(RESPONSE_PROMPT);
        responseJson.setJson("");
        rawBody = "";
        bodyIsJson = false;
        formatBodyBtn.setDisable(true);
        formatBodyBtn.setText("格式化");
        showBodyView(false);
        responseHeaders.clear();
        requestDetail.clear();
        setResponded(false);
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

    /** visible 与 managed 必须一起切 —— 只切 visible 会留下一个占位的空洞。 */
    private static void setShown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    /** 内联错误提示：紧贴触发它的请求栏下方，而不是页面底部。 */
    private void showError(String message) {
        errorLabel.setText(message);
        setShown(errorLabel, true);
    }

    private void hideError() {
        errorLabel.setText("");
        setShown(errorLabel, false);
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

    /** 关闭时释放线程池，并把可能停在「发送中…」的按钮复位。 */
    public void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        // 切页时请求可能还没回来（runLater 回调随之失效），不复位按钮的话
        // 再次进入这个被缓存复用的页面，Send 会永远是禁用态
        if (sendButton != null) {
            sendButton.setDisable(false);
            sendButton.setText("Send");
        }
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

    /** 测试用：Send 按钮。 */
    Button sendButtonForTest() {
        return sendButton;
    }

    /** 测试用：地址输入框。 */
    TextField urlFieldForTest() {
        return urlField;
    }

    /** 测试用：方法下拉框。 */
    ComboBox<HttpRequestSpec.Method> methodBoxForTest() {
        return methodBox;
    }

    /** 测试用：GET / DELETE 带请求体的信息级提示。 */
    Label methodBodyTipForTest() {
        return methodBodyTip;
    }

    /** 测试用：某个类型的分段项（断言分段控件真的存在这一项）。 */
    ToggleButton typeButtonForTest(HttpRequestSpec.BodyType type) {
        return typeButtons.get(type);
    }

    /** 测试用：请求体文本区（JSON / XML / Text 共用的那个）。 */
    TextArea bodyAreaForTest() {
        return bodyArea;
    }

    /**
     * 测试用：选中某个请求体类型。
     *
     * <p>用 ToggleButton 的 {@code setSelected} 而不是 {@code fire()} ——
     * fire 会连带把「再点一次不取消」的分支也跑一遍，探针要的是「用户点了这一项」
     * 这个结果状态，不是点击过程的副作用。
     */
    void selectBodyTypeForTest(HttpRequestSpec.BodyType type) {
        selectBodyType(type);
    }

    /** 测试用：当前选中的请求体类型。 */
    HttpRequestSpec.BodyType declaredTypeForTest() {
        return declaredType();
    }

    /** 测试用：当前实际会发出去的类型（含推断）。 */
    HttpRequestSpec.BodyType effectiveTypeForTest() {
        return effectiveType();
    }

    /** 测试用：当前可见的编辑器节点（逐类型断言它就是「类型 → 编辑器」的证据）。 */
    Node visibleEditorForTest() {
        for (Node node : editorHolder.getChildren()) {
            if (node.isVisible()) {
                return node;
            }
        }
        return null;
    }

    /** 测试用：编辑器槽（断言键值表整体高度 / 降级透明度用）。 */
    Node editorHolderForTest() {
        return editorHolder;
    }

    /** 测试用：Form / Form-data 共用的字段表。 */
    FormDataTable formDataTableForTest() {
        return formDataTable;
    }

    /** 测试用：点一下「+ Token」（探针跑在无头环境，点不到真实按钮）。 */
    void clickAddTokenForTest() {
        tokenHeader.fire();
    }

    /** 测试用：「+ Token」按钮是否存在（无头环境断言不了点击，但断得到存在与文案）。 */
    Button tokenButtonForTest() {
        return tokenHeader;
    }

    /** 测试用：请求头键值表（「+ JSON」「+ 新增请求头」都作用在它上面）。 */
    KeyValueTable headersTableForTest() {
        return headersTable;
    }

    /** 测试用：query 参数键值表。 */
    KeyValueTable paramsTableForTest() {
        return paramsTable;
    }

    /** 测试用：工具行右侧的状态位与守卫文字。 */
    Label guardTextForTest() {
        return guardText;
    }

    /** 测试用：工具行动作槽。 */
    HBox actionSlotForTest() {
        return actionSlot;
    }

    /** 测试用：Form 工具行里正在显示的 Content-Type 文本。 */
    Label ctypeLabelForTest() {
        return ctypeLabel;
    }

    /** 测试用：折叠摘要条。 */
    Node stripForTest() {
        return strip;
    }

    /** 测试用：请求配置区（展开态）。 */
    Node requestBlockForTest() {
        return requestBlock;
    }

    /** 测试用：响应页签。 */
    TabPane responseTabsForTest() {
        return responseTabs;
    }

    /** 测试用：响应体文本区的提示语（「请求中…」在响应区里的落点）。 */
    String responsePromptForTest() {
        return responseBody.getPromptText();
    }

    /** 测试用：请求页签。 */
    TabPane requestTabsForTest() {
        return requestTabs;
    }

    /** 测试用：Body 页签的标题（含类型摘要）。 */
    String bodyTabTitleForTest() {
        return bodyTab.getText();
    }

    /** 测试用：摘要条上的「重发」按钮。 */
    Button resendButtonForTest() {
        return resendButton;
    }

    /** 测试用：摘要条上的「展开编辑」按钮。 */
    Button expandButtonForTest() {
        return expandButton;
    }

    /** 测试用：把某个文件塞进 Form-data 的第一行（绕开系统文件对话框）。 */
    void setFormDataFileForTest(int index, String name, String path) {
        formDataTable.setFileForTest(index, name, path);
    }

    /** 测试用：往 Form 键值表里塞一行。 */
    void addFormFieldForTest(String key, String value) {
        formDataTable.setTextFieldsForTest(key, value);
    }

    /** 测试用：设置请求头表。 */
    void setHeadersForTest(Map<String, String> headers) {
        headersTable.setEntries(headers);
        refreshToolRow();
    }

    /**
     * 测试用：往线程池提交一个空任务，成功返回 true。
     *
     * <p>对应「切走再切回这个被缓存的页面后还能不能发请求」——
     * 池若停在已关闭状态，这里会抛 {@code RejectedExecutionException}。
     */
    boolean canSubmitForTest() {
        try {
            executor().submit(() -> {
            });
            return true;
        } catch (RuntimeException rejected) {
            return false;
        }
    }
}
