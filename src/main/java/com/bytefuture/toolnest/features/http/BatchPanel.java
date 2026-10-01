package com.bytefuture.toolnest.features.http;

import com.bytefuture.toolnest.ui.components.CollapsibleSection;
import com.bytefuture.toolnest.ui.components.UiKit;
import com.bytefuture.toolnest.ui.components.KeyValueTable;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
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
 *
 * <h2>为什么是纵向三段</h2>
 * <p>一页竖向预算只有 653px（1266×763 窗口），而「请求模板 / CSV / 结果」三块
 * 都想看得见。横向切分只会把结果表压到 520px 必需宽以下、逼出横向滚动 ——
 * 表格横滚时列头跟着滚走，比纵向滚动更难用。所以三段一律占整幅内容宽，
 * 纵向靠折叠来腾：
 * <ul>
 *   <li>① 请求模板 与 ② CSV 数据<b>不互斥、不封顶</b>：默认流程里的
 *       「一展一收」由业务代码显式触发（导入 CSV 展开 ② 收起 ①、开始执行
 *       两段都收起），用户手动点头带时另一段保持原样 —— 两段可同时在场；</li>
 *   <li>三段按内容自然生长，装不下时由<b>全页唯一的一条外层滚动容器</b>
 *       竖向滚动 —— 段内不套小滚动条，也不给任何一段压 max-height
 *       （压高会把 TextArea 挤成细条，违反「装不下只滚动不压缩」）；</li>
 *   <li>③ 执行配置与结果常驻不可折叠，并<b>预留 340px</b>：自然高小于一屏时
 *       它靠 VGrow 吸收剩余高度，大于一屏时它守住 340 让多出去的部分去滚动。</li>
 * </ul>
 */
public final class BatchPanel extends VBox {

    /**
     * 段③ 的预留高度：低于这个高度结果表连 6 行都凑不出来。
     *
     * <p>③ 的 min 与 pref 是同一个值（Region 的 prefHeight 会被 min 兜住）：
     * 自然高小于一屏时 ③ 靠 VGrow 吃掉剩余高度，大于一屏时它守住 340，
     * 超出的部分走<b>外层滚动</b>，而不是把 ① 压扁。
     * 段① / 段② 都不设上限 —— 高度按内容自然生长，装不下就整页滚。
     */
    private static final double RESULT_MIN_H = 340;

    /**
     * 结果表五列的 pref 宽，合计 **520** —— 正好是规格里写的「结果表最小必需宽」。
     *
     * <p>实测可用宽只有 557（B 形态有竖滚动条）/ 563（A 形态），而 CONSTRAINED
     * 策略在「列宽总和 &gt; 可用宽」时会按比例压窄每一列：原来
     * 46+62+80+320+300 = 808 被压掉近三分之一，「状态」列连「等待中」三个字都
     * 装不下，表头与单元格全渲染成「...」。所以 pref 先收进 520，富余由
     * FLEX_LAST_COLUMN 给最后一列；地址 / 响应预览这类长文本本来就要省略号，
     * 完整值看右侧「响应详情」或导出 CSV。
     */
    private static final double RESULT_COL_INDEX_W = 40;
    private static final double RESULT_COL_STATE_W = 62;
    private static final double RESULT_COL_COST_W = 80;
    private static final double RESULT_COL_URL_W = 150;
    private static final double RESULT_COL_PREVIEW_W = 188;

    /** 结果表格的一行。 */
    public static final class ResultRow {
        final int index;
        final Map<String, String> vars;
        final HttpRequestSpec spec;
        /**
         * 本行实际发出的请求头（占位符已按该行变量替换）。
         *
         * <p>请求头也要参与占位符替换 —— 把 token 之类的东西写进 CSV、
         * 表头里引用 {@code Authorization: Bearer {token}} 是批量测试的常规用法，
         * 界面上的说明也是这么承诺的。
         */
        final List<Map.Entry<String, String>> headers;
        HttpEngine.Result result;
        /**
         * 响应预览文本的缓存。
         *
         * <p>表格每次刷新都会重新取一遍所有可见单元格的值，而预览要对响应体
         * 跑一遍空白折叠正则 —— 不缓存的话，每完成一行就对全表已完成的
         * 大响应重算一次，几百 KB 的响应就能把界面拖住。
         */
        private String preview;

        ResultRow(int index, Map<String, String> vars, HttpRequestSpec spec,
                  List<Map.Entry<String, String>> headers) {
            this.index = index;
            this.vars = vars;
            this.spec = spec;
            this.headers = headers;
        }

        /** 写入结果，并顺手把预览算一次存下来。 */
        void setResult(HttpEngine.Result result) {
            this.result = result;
            this.preview = computePreview();
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
            if (preview == null) {
                preview = computePreview();
            }
            return preview;
        }

        private String computePreview() {
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

    // ---- 段① 请求模板 ----
    private final ComboBox<HttpRequestSpec.Method> methodBox = new ComboBox<>();
    private final TextField urlField = new TextField();
    private final ComboBox<HttpRequestSpec.BodyType> bodyTypeBox = new ComboBox<>();
    private final TextArea bodyArea = new TextArea();
    /** 请求体降级的原因文字，挂在「请求体类型」那一行右侧。 */
    private Label bodyNote;
    private final KeyValueTable headersTable = new KeyValueTable(130);

    // ---- 段② CSV ----
    private final TextArea csvArea = new TextArea();
    private final Label warnLabel = UiKit.error("");
    private final TableView<Map<String, String>> previewTable = new TableView<>();
    private CsvParser.Table currentTable;

    // ---- 段③ 执行配置 ----
    private final ToggleGroup modeGroup = new ToggleGroup();
    private final RadioButton sequentialRadio = new RadioButton("顺序执行");
    private final RadioButton concurrentRadio = new RadioButton("并发执行");
    private final Spinner<Integer> threadSpinner = new Spinner<>(1, 64, 8);
    private final Spinner<Integer> timeoutSpinner = new Spinner<>(1, 300, 30);
    private final Spinner<Integer> delaySpinner = new Spinner<>(0, 10000, 0);
    /** 线程数组：降级时整组降透明的是它内部的字段行，不含右侧的原因文字。 */
    private HBox threadGroup;
    /** 线程数组里被降透明的那一层（标签 + 数值框）。 */
    private HBox threadField;
    /** 线程数降级的原因文字（「并发时生效」）。 */
    private Label threadNote;

    // ---- 段③ 结果 ----
    private final ObservableList<ResultRow> resultRows = FXCollections.observableArrayList();
    private final TableView<ResultRow> resultTable = new TableView<>(resultRows);
    private final ProgressBar progress = new ProgressBar(0);
    private final Label progressLabel = new Label("");
    private final Label statLabel = new Label("");
    private final Button runButton = new Button("开始执行");
    private final Button stopButton = new Button("停止");

    private final TextArea detailArea = UiKit.readonlyArea();
    private final Label errorLabel = UiKit.error("");

    // ---- 三段 ----
    private final CollapsibleSection templateSection = new CollapsibleSection("① 请求模板");
    private final CollapsibleSection csvSection = new CollapsibleSection("② CSV 数据", false);
    private final CollapsibleSection resultSection = new CollapsibleSection("③ 执行配置与结果");

    /** 全页唯一滚动容器：三段装不下时由它竖向滚动，段内不再有小滚动条。 */
    private final ScrollPane outer;
    /** 外层滚动容器里的唯一内容：装 ① ② ③ 与错误条。 */
    private final VBox inner;

    private ExecutorService executor;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicInteger finished = new AtomicInteger(0);

    public BatchPanel() {
        getStyleClass().add("http-panel");
        // 间距挪到 inner 上：本 VBox 只装一条外层滚动容器，自身不留间距
        setSpacing(0);
        // 这一页是「铺满一屏」型：自身高度也必须放开，否则外层滚动容器的
        // VGrow 会被父容器按 pref 高度截断，表现为结果区下面空一大截。
        setMaxHeight(Double.MAX_VALUE);
        // 首选高压平到 0：本页自带内层滚动，只需要「拿到父容器给的剩余高度」。
        // 不压的话它的自然高（649）会被同一 TabPane 里「取各页签最大首选高」
        // 的规则算进去 —— 单一请求页因此被顺带撑到 743，而 763 的窗口只给得起
        // 678，整页白白多出一条外层滚动条（实测溢出 65px）。
        // 压平后两个页签都是「填满可用高度、装不下自己滚」，谁也不需要外层滚动。
        setPrefHeight(0);

        buildTemplateBody(templateSection.content());
        buildCsvBody(csvSection.content());
        resultSection.setCollapsible(false);
        buildResultBody();

        // 段① / 段② 不互斥、也不封顶：用户手动点头带时另一段保持原样，
        // 两段可同时在场。默认流程里的「一展一收」由业务代码显式触
        // 发（导入 CSV 见 revealCsv，开始执行见 runBatch），不是全局规则。
        VBox.setVgrow(resultSection, Priority.ALWAYS);
        resultSection.setMaxHeight(Double.MAX_VALUE);
        // ③ 的 min 与 pref 是同一个 340：Region 的 prefHeight 会被 min 兜住，
        // 所以这一行同时等于「预留 340」—— 自然高小于一屏时 ③ 靠 VGrow 吃掉
        // 剩余高度，大于一屏时它守住 340，多出去的部分交给外层滚动。
        resultSection.setMinHeight(RESULT_MIN_H);

        inner = new VBox(10, templateSection, csvSection, resultSection, errorLabel);
        inner.setFillWidth(true);
        // 这条是外层滚动能生效的关键：不设的话 VBox 会被 ScrollPane 按可用
        // 高度压扁（min 默认走 computed，可以压），段① 的 TextArea 会被挤成
        // 细条；设成 USE_PREF_SIZE 才是「自然高 < 视口就撑满、> 视口就滚动」。
        inner.setMinHeight(Region.USE_PREF_SIZE);

        // 全页唯一的滚动容器：任何一段变高都在这里滚，段内不再有小滚动条。
        // 样式复用站内详情页那条外层滚动容器（ContentPane#buildDetail），
        // 不另起一个类 —— 底色与滚动条表现全站一致。
        outer = new ScrollPane(inner);
        outer.getStyleClass().add("detail-scroll");
        outer.setFitToWidth(true);
        outer.setFitToHeight(true);
        outer.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        outer.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        outer.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(outer, Priority.ALWAYS);

        getChildren().add(outer);
        hideError();
    }

    // ============================================================ 段① 请求模板

    private void buildTemplateBody(VBox body) {
        // 段① 竖向瘦身三处，合计 −30px（280 → 250）：
        //   正文间距 10→8（−6）、正文 padding 12→8（−8，见 .stage-body-compact）、
        //   请求体 TextArea 64→48（−16）。
        // 目的：让「① + 10 + ②38 + 10 + ③」落回 653 的一屏内 —— 段① 280 时
        // 整页会溢出 25px，段③ 底部被切掉一小截，结果表第 6 行只露一半。
        // 注意：不是压 max-height（那会把 TextArea 挤成细条），是减内容本身。
        body.setSpacing(8);
        body.getStyleClass().add("stage-body-compact");

        methodBox.getItems().addAll(HttpRequestSpec.Method.values());
        methodBox.getSelectionModel().select(HttpRequestSpec.Method.GET);
        methodBox.getStyleClass().add("http-method");
        methodBox.setPrefWidth(118);
        methodBox.setMinWidth(118);

        urlField.setPromptText("https://api.example.com/order?orderId={orderId}");
        urlField.getStyleClass().add("http-url");
        HBox.setHgrow(urlField, Priority.ALWAYS);
        urlField.setMaxWidth(Double.MAX_VALUE);

        HBox urlRow = new HBox(8, methodBox, urlField);
        urlRow.setAlignment(Pos.CENTER_LEFT);

        // 只列批处理页真能处理的类型。Form-data / Binary 只有「路径」没有「值」，
        // {列名} 占位符在它们身上没有意义 —— 列出来等于给一个点了会出错的入口。
        bodyTypeBox.getItems().addAll(HttpRequestSpec.BodyType.batchTypes());
        bodyTypeBox.getSelectionModel().select(HttpRequestSpec.BodyType.NONE);
        bodyTypeBox.setPrefWidth(180);
        // 请求体类型 = NONE 时整行降级。这里同样不能只把编辑区压暗（设计稿 §06-5
        // 「禁用态必须配文字」）：用户填了内容却发不出去，界面上必须有一句话说明。
        // 说明挂在「请求体类型」这一行右侧 —— 不新增一行，段① 的竖向预算刚好卡在
        // 653 的一屏内（见 buildTemplateBody 开头的记账），多一行就要整页滚动。
        bodyNote = UiKit.degradeNote("此项不生效（请求体类型为 NONE）");
        bodyTypeBox.valueProperty().addListener((o, old, now) ->
                applyBodyDegrade(now != HttpRequestSpec.BodyType.NONE));

        bodyArea.setPromptText("请求体模板，例如 {\"orderId\": \"{orderId}\"}");
        bodyArea.getStyleClass().add("mono-area");
        bodyArea.setWrapText(true);
        // 48px ≈ 3 行：请求体模板通常就是一行 JSON，且默认降级（未选 body 类型）
        bodyArea.setPrefHeight(48);
        applyBodyDegrade(false);

        TitledPane headersPane = new TitledPane("请求头 (Headers)", headersTable);
        headersPane.getStyleClass().add("folder-pane");
        headersPane.setExpanded(false);

        HBox bodyTypeRow = UiKit.fieldRow("请求体类型", bodyTypeBox);
        bodyTypeRow.getChildren().add(bodyNote);

        // 段① 不封顶、不套内层滚动：四个节点按 pref 自然生长，装不下时
        // 由外层唯一的 ScrollPane 整页滚（见类注释的「竖向预算」）。
        body.getChildren().addAll(
                UiKit.field("请求地址模板", urlRow),
                bodyTypeRow,
                bodyArea,
                headersPane);

        // 段收起后正文看不见了，头带摘要必须能读出「请求长什么样」
        methodBox.valueProperty().addListener((o, a, b) -> refreshTemplateSummary());
        urlField.textProperty().addListener((o, a, b) -> {
            refreshTemplateSummary();
            // 地址模板改了，占位符对不对得上的提示也要跟着变
            refreshPlaceholderWarning();
        });
        bodyArea.textProperty().addListener((o, a, b) -> refreshPlaceholderWarning());
        // 请求头里也可能写 {列名}，增删行时同步刷新提示
        headersTable.setOnRowsCountChanged(n -> refreshPlaceholderWarning());
        refreshTemplateSummary();
    }

    /**
     * 请求体编辑区的降级 / 复员。
     *
     * <p>设计稿 §06-5 的不变量：「整行降透明（42%）+ 明写原因」、
     * 「禁用态必须配文字，不能只做 {@code setDisable}」—— 所以走
     * {@link UiKit#setDegraded} 而不是 {@code bodyArea.setDisable(true)}，
     * 并同步切换右侧那句原因文字。
     */
    private void applyBodyDegrade(boolean enabled) {
        UiKit.setDegraded(bodyArea, null, !enabled);
        if (bodyNote != null) {
            bodyNote.setVisible(!enabled);
            bodyNote.setManaged(!enabled);
        }
    }

    private void refreshTemplateSummary() {
        String url = urlField.getText();
        if (url == null || url.isBlank()) {
            templateSection.setSummary("尚未填写请求地址");
            return;
        }
        templateSection.setSummary(methodBox.getValue() + "  " + url.trim());
    }

    // ============================================================ 段② CSV

    private void buildCsvBody(VBox body) {
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
            warnLabel.setText("");
            warnLabel.setVisible(false);
            warnLabel.setManaged(false);
            csvSection.setSummary("尚未导入 CSV");
        });

        HBox tools = new HBox(8, chooseFile, parsePaste, sample, clearCsv);
        tools.setAlignment(Pos.CENTER_LEFT);

        // TextArea 的 promptText <b>不渲染换行</b>：多行提示会被压成一行，
        // 「示例：/ orderId,type / 235027703726,A」会连成
        // 「示例：orderId,type235027703726,A235027703727,B」—— 表头与数据行
        // 粘在一起，读不出格式，看起来就像样式坏了。所以提示只写一行，
        // 具体示例交给「载入示例」按钮（点了就能看到真实数据）。
        csvArea.setPromptText("在此粘贴 CSV 文本（首行为表头，如 orderId,type），"
                + "然后点「解析粘贴的文本」");
        csvArea.getStyleClass().add("mono-area");
        csvArea.setWrapText(false);
        csvArea.setPrefHeight(64);

        previewTable.setPlaceholder(new Label("导入 CSV 后在此预览数据"));
        previewTable.setPrefHeight(120);
        previewTable.setMinHeight(80);
        // 预览列数由 CSV 表头决定（可能很多列），必须保持各列固定宽度、
        // 容器装不下时横向滚动，否则 CONSTRAINED 策略会把列挤成省略号。
        previewTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        VBox.setVgrow(previewTable, Priority.ALWAYS);
        previewTable.setMaxHeight(Double.MAX_VALUE);

        warnLabel.setVisible(false);
        warnLabel.setManaged(false);

        body.getChildren().addAll(tools, csvArea, previewTable, warnLabel);
        csvSection.setSummary("尚未导入 CSV");
    }

    // ============================================================ 段③ 执行配置与结果

    private void buildResultBody() {
        sequentialRadio.setToggleGroup(modeGroup);
        concurrentRadio.setToggleGroup(modeGroup);
        sequentialRadio.setSelected(true);
        sequentialRadio.setUserData(Boolean.FALSE);
        concurrentRadio.setUserData(Boolean.TRUE);

        threadSpinner.setPrefWidth(64);
        threadSpinner.setMinWidth(64);
        threadSpinner.setEditable(true);
        timeoutSpinner.setPrefWidth(64);
        timeoutSpinner.setMinWidth(64);
        timeoutSpinner.setEditable(true);
        delaySpinner.setPrefWidth(64);
        delaySpinner.setMinWidth(64);
        delaySpinner.setEditable(true);

        // 线程数只在「并发」下生效。降级必须走 UiKit.setDegraded（整组降透明 +
        // 明写「并发时生效」），不是 setDisable —— 设计稿 §06-5 的不变量：
        // 「禁用态必须配文字，不能只做 setDisable」。早先只禁数值框的后果是
        // 标签全黑、数值淡到几乎看不见，看着就像控件坏了（用户在批处理页
        // 红框标出的就是这一处）。
        threadNote = UiKit.degradeNote("并发时生效");
        threadField = numField("线程数", threadSpinner, null);
        threadGroup = new HBox(6, threadField, threadNote);
        threadGroup.setAlignment(Pos.CENTER_LEFT);
        UiKit.setDegraded(threadField, threadNote, true);
        modeGroup.selectedToggleProperty().addListener((o, old, now) ->
                UiKit.setDegraded(threadField, threadNote, now != concurrentRadio));

        HBox modeRow = new HBox(16, sequentialRadio, concurrentRadio);
        modeRow.setAlignment(Pos.CENTER_LEFT);

        runButton.getStyleClass().add("http-send");
        runButton.setPrefWidth(116);
        runButton.setMinWidth(116);
        runButton.setOnAction(e -> runBatch());

        // 停止是随时可用、但不该抢主操作视线的动作
        stopButton.getStyleClass().add("flat");
        stopButton.setDisable(true);
        stopButton.setOnAction(e -> cancelled.set(true));

        progress.setPrefWidth(140);
        progress.setMinWidth(140);
        progressLabel.getStyleClass().add("caption");
        // 未开跑时不占执行条宽度，跑起来才出现
        progress.setVisible(false);
        progress.setManaged(false);
        progressLabel.setVisible(false);
        progressLabel.setManaged(false);

        HBox execRow = new HBox(12,
                modeRow,
                threadGroup,
                numField("超时", timeoutSpinner, "秒"),
                numField("间隔", delaySpinner, "ms"),
                UiKit.spacer(),
                progress, progressLabel,
                runButton, stopButton);
        execRow.setAlignment(Pos.CENTER_LEFT);

        buildResultColumns();

        resultTable.setPlaceholder(new Label("执行后在此显示每行的结果"));
        resultTable.setItems(resultRows);
        resultTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        resultTable.setPrefHeight(200);
        resultTable.setMaxHeight(Double.MAX_VALUE);
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

        statLabel.getStyleClass().add("caption");

        Button exportBtn = UiKit.secondaryButton("导出结果 CSV");
        exportBtn.setOnAction(e -> exportResults());

        SplitPane resultSplit = new SplitPane();
        resultSplit.setOrientation(javafx.geometry.Orientation.HORIZONTAL);
        resultSplit.getItems().addAll(resultTable, wrapDetail());
        resultSplit.setDividerPositions(0.58);
        resultSplit.getStyleClass().add("batch-split");
        VBox.setVgrow(resultSplit, Priority.ALWAYS);
        resultSplit.setMaxHeight(Double.MAX_VALUE);

        resultSection.content().getChildren().addAll(execRow, resultSplit);
        // 头带常驻：汇总与导出放在这里，正文就只剩「执行条 + 结果」，省一整行
        resultSection.addTrailing(statLabel, exportBtn);
    }

    /** 执行条里的数值项：标签 + 输入框 + 等宽单位后缀。 */
    private HBox numField(String labelText, Node field, String unit) {
        Label label = new Label(labelText);
        label.getStyleClass().add("field-label");
        if (unit == null) {
            return new HBox(6, label, field);
        }
        Label unitLabel = new Label(unit);
        unitLabel.getStyleClass().add("field-unit");
        return new HBox(6, label, field, unitLabel);
    }

    private void buildResultColumns() {
        TableColumn<ResultRow, String> indexCol =
                col("#", RESULT_COL_INDEX_W, r -> String.valueOf(r.index));
        TableColumn<ResultRow, String> stateCol =
                col("状态", RESULT_COL_STATE_W, ResultRow::stateText);
        TableColumn<ResultRow, String> costCol =
                col("耗时", RESULT_COL_COST_W, r -> r.elapsed() + " ms");
        TableColumn<ResultRow, String> urlCol = col("地址", RESULT_COL_URL_W,
                r -> r.result == null ? r.spec.url : r.result.finalUrl);
        TableColumn<ResultRow, String> previewCol =
                col("响应预览", RESULT_COL_PREVIEW_W, ResultRow::responsePreview);

        // 每列都钉 min = pref：CONSTRAINED 策略只在「总宽超出可用宽」时才压列，
        // 压到 62 以下「等待中」就会被截成「等待…」（DESIGN 不变量 §7 #17 禁止）。
        // 钉住 min 后，窗口再窄也是横向滚动，而不是把「状态」压成省略号。
        indexCol.setMinWidth(RESULT_COL_INDEX_W);
        stateCol.setMinWidth(RESULT_COL_STATE_W);
        costCol.setMinWidth(RESULT_COL_COST_W);
        urlCol.setMinWidth(RESULT_COL_URL_W);
        previewCol.setMinWidth(RESULT_COL_PREVIEW_W);

        resultTable.getColumns().addAll(indexCol, stateCol, costCol, urlCol, previewCol);
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
        detailArea.setMaxHeight(Double.MAX_VALUE);
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

        // 展示替换后的实际请求头，方便确认 {token} 这类占位符替换成了什么
        if (!row.headers.isEmpty()) {
            sb.append('\n').append("--- 请求头 ---").append('\n');
            row.headers.forEach(h -> sb.append(h.getKey()).append(": ")
                    .append(h.getValue()).append('\n'));
        }

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
            revealCsv();
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
            revealCsv();
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

    /**
     * 导入成功即展开 CSV 段、收起模板段。
     *
     * <p>这是<b>自动</b>行为（这一步的产物就是要给人看的），不是互斥规则：
     * 用户之后手动点 ① 的头带，② 不会被强制收起 —— 配置阶段两段可以同时
     * 在场，装不下就整页滚动，由用户自己决定看哪一段。
     */
    private void revealCsv() {
        csvSection.setExpanded(true);
        templateSection.setExpanded(false);
    }

    private void renderPreview(String source) {
        previewTable.getColumns().clear();
        previewTable.getItems().clear();
        if (currentTable == null || currentTable.headers.isEmpty()) {
            csvSection.setSummary("未识别到表头");
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

        refreshPlaceholderWarning();
        // 段折叠时头带要能读出「有哪些列、多少行」
        csvSection.setSummary(String.join(" · ", currentTable.headers)
                + "　" + currentTable.size() + " 行　来源：" + source);
        hideError();
    }

    /** 检查请求模板（地址 / 请求体 / 请求头）里用到、但 CSV 表头没有的占位符。 */
    private List<String> findUnresolvedPlaceholders() {
        if (currentTable == null) {
            return List.of();
        }
        Set<String> declared = new LinkedHashSet<>(currentTable.headers);
        Set<String> used = new LinkedHashSet<>();
        used.addAll(TemplateResolver.placeholders(urlField.getText()));
        used.addAll(TemplateResolver.placeholders(bodyArea.getText()));
        for (Map.Entry<String, String> h : headersTable.entries()) {
            used.addAll(TemplateResolver.placeholders(h.getKey()));
            used.addAll(TemplateResolver.placeholders(h.getValue()));
        }

        List<String> missing = new ArrayList<>();
        for (String name : used) {
            boolean found = declared.stream().anyMatch(h -> h.equalsIgnoreCase(name));
            if (!found) {
                missing.add(name);
            }
        }
        return missing;
    }

    /** 按当前模板与 CSV 表头刷新「占位符对不上」的提示条。 */
    private void refreshPlaceholderWarning() {
        if (currentTable == null || currentTable.headers.isEmpty()) {
            return;
        }
        List<String> unresolved = findUnresolvedPlaceholders();
        boolean show = !unresolved.isEmpty();
        warnLabel.setText(show
                ? "模板中这些占位符在 CSV 里没有对应列：" + String.join(", ", unresolved)
                : "");
        warnLabel.setVisible(show);
        warnLabel.setManaged(show);
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

        // 跑起来之后要看的是结果，不是参数：两段输入收起，竖向预算整块让给 ③
        templateSection.setExpanded(false);
        csvSection.setExpanded(false);

        // 准备结果行
        prepareResultRows();

        boolean concurrent = modeGroup.getSelectedToggle() == concurrentRadio;
        int threads = concurrent ? threadSpinner.getValue() : 1;
        Duration timeout = Duration.ofSeconds(timeoutSpinner.getValue());
        long delay = delaySpinner.getValue();

        cancelled.set(false);
        finished.set(0);
        progress.setProgress(0);
        progress.setVisible(true);
        progress.setManaged(true);
        progressLabel.setVisible(true);
        progressLabel.setManaged(true);
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
                HttpEngine.Result result = HttpEngine.send(row.spec, null, row.headers, timeout);
                row.setResult(result);

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

    /**
     * 按当前界面的请求模板 / CSV / 请求头生成待执行的结果行。
     *
     * <p>占位符替换发生在这一步：地址、请求体、请求头三处都要按当前 CSV 行
     * 替换。抽成独立方法是为了让探针能检查「到底替换成了什么」——
     * 这段逻辑只在点了「开始执行」之后才跑，编译通过证明不了替换结果。
     */
    private void prepareResultRows() {
        resultRows.clear();
        if (currentTable == null) {
            return;
        }
        HttpRequestSpec.BodyType bodyType = HttpRequestSpec.inferBodyType(
                bodyTypeBox.getValue(), bodyArea.getText());
        List<Map.Entry<String, String>> baseHeaders = headersTable.entries();
        String url = urlField.getText();
        int index = 1;
        for (Map<String, String> vars : currentTable.rows) {
            HttpRequestSpec spec = new HttpRequestSpec(
                    methodBox.getValue(),
                    TemplateResolver.resolve(url, vars),
                    bodyType,
                    TemplateResolver.resolve(bodyArea.getText(), vars));
            resultRows.add(new ResultRow(index++, vars, spec, resolveHeaders(baseHeaders, vars)));
        }
    }

    /**
     * 生成导出用的 CSV 文本，含 UTF-8 BOM。
     *
     * <p>抽出来是为了让探针能直接断言 BOM 与列内容 —— 导出要经过系统文件
     * 选择框，自动化测试点不到。
     */
    String buildResultCsv() {
        List<String> headers = new ArrayList<>(List.of(
                "#", "状态", "耗时 (ms)", "最终URL", "错误", "响应预览"));
        headers.addAll(currentTable == null ? List.of() : currentTable.headers);

        List<List<String>> rows = new ArrayList<>();
        for (ResultRow row : resultRows) {
            List<String> line = new ArrayList<>();
            line.add(String.valueOf(row.index));
            // 用界面上显示的状态文本：未执行完的行在表格里是「等待中」，
            // 导出成 -1 会和界面对不上，看起来像失败
            line.add(row.stateText());
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

        // 必须带 UTF-8 BOM：中文 Windows 的 Excel 打开不带 BOM 的 UTF-8 CSV
        // 会按 GBK 解析，中文列全变乱码。CsvParser 读回来时会剥掉 BOM，闭环。
        return "\uFEFF" + CsvParser.toCsv(headers, rows);
    }

    /**
     * 把一行 CSV 变量替换进请求头的键与值。
     *
     * <p>键名一般不含占位符，但既然模板允许多处引用，就一并替换，不搞特殊。
     */
    private List<Map.Entry<String, String>> resolveHeaders(
            List<Map.Entry<String, String>> base, Map<String, String> vars) {
        List<Map.Entry<String, String>> resolved = new ArrayList<>(base.size());
        for (Map.Entry<String, String> e : base) {
            resolved.add(new AbstractMap.SimpleEntry<>(
                    TemplateResolver.resolve(e.getKey(), vars),
                    TemplateResolver.resolve(e.getValue(), vars)));
        }
        return resolved;
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

        try {
            Files.writeString(file.toPath(), buildResultCsv(), StandardCharsets.UTF_8);
            hideError();
            progress.setProgress(1);
            progressLabel.setText("已导出 " + file.getName());
        } catch (Exception ex) {
            showError("导出失败：" + ex.getMessage());
        }
    }

    // ============================================================ 工具

    /** 小标题（响应详情这类二级分区）。 */
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
        // 批处理页只支持 batchTypes()。单一请求页若选的是 Form-data / Binary，
        // 这里没有对应的模型可以承载（它们的字段是「路径」而不是「值」），
        // 因此退回「无请求体」并在下方说明 —— 静默保留一个 ComboBox 里
        // 不存在的选项，会让用户以为模板带过来了。
        HttpRequestSpec.BodyType type = HttpRequestSpec.BodyType.batchTypes()
                .contains(spec.bodyType) ? spec.bodyType : HttpRequestSpec.BodyType.NONE;
        bodyTypeBox.getSelectionModel().select(type);
        if (type != spec.bodyType) {
            showError("单一请求页选的是「" + spec.bodyType.label()
                    + "」，批处理暂不支持该类型（文件字段没有可替换的值），已改为「无请求体」。");
        } else {
            hideError();
        }
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

    // ============================================================ 测试钩子
    //
    // 纵向三段式的收益全在「竖向预算怎么分」上 —— 编译通过证明不了结果表
    // 拿到几行。下面这些方法只把运行时节点暴露给同包的探针去量，不参与
    // 业务逻辑，也不改变行为。

    CollapsibleSection templateSectionForTest() {
        return templateSection;
    }

    CollapsibleSection csvSectionForTest() {
        return csvSection;
    }

    CollapsibleSection resultSectionForTest() {
        return resultSection;
    }

    TableView<ResultRow> resultTableForTest() {
        return resultTable;
    }

    TextArea detailAreaForTest() {
        return detailArea;
    }

    TableView<Map<String, String>> previewTableForTest() {
        return previewTable;
    }

    TextArea csvAreaForTest() {
        return csvArea;
    }

    /** 线程数组里被降透明的那一层（探针量渲染墨迹用）。 */
    HBox threadFieldForTest() {
        return threadField;
    }

    /** 顺序 / 并发 的分组，探针用它切换模式（走的就是用户点单选的那条链路）。 */
    ToggleGroup modeGroupForTest() {
        return modeGroup;
    }

    /** 线程数降级的原因文字。 */
    Label threadNoteForTest() {
        return threadNote;
    }

    /** 请求体降级的原因文字。 */
    Label bodyNoteForTest() {
        return bodyNote;
    }

    TextField urlFieldForTest() {
        return urlField;
    }

    ScrollPane outerScrollForTest() {
        return outer;
    }

    Parent innerForTest() {
        return inner;
    }

    Button runButtonForTest() {
        return runButton;
    }

    void loadSampleForTest() {
        loadSample();
    }

    ObservableList<ResultRow> resultRowsForTest() {
        return resultRows;
    }

    /** 只生成待执行的结果行、不发任何请求，供探针检查占位符替换结果。 */
    void prepareResultRowsForTest() {
        prepareResultRows();
    }
}
