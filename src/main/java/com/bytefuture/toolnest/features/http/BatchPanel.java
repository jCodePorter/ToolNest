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
     * 段① 内层页签（Params / Headers / Body）的高度。
     *
     * <p>154 = Body 页签内容 96（工具行 38 + 间距 8 + 编辑器 50）
     * + {@code tab-content-area} 的 padding-top 14 + 页签轨道 43.33。
     * 三个数都是实测的。
     * <b>CSS 里必须写同一个值</b>（{@code .batch-tabs}）—— CSS 的 min/pref-height
     * 一旦命中就会覆盖 Java 侧的 setter，两边不同步的话量到的是 CSS 那个值。
     *
     * <p>为什么不是单一请求页那个 312：那是给 210px 的请求体编辑器留的，
     * 且段① 的 Params/Headers 要放得下 3 行 + 滚动条。
     * 套 312 会让段① 涨到 411、整页 810，段③ 被整段推出视口更远。
     */
    private static final double TEMPLATE_TABS_H = 277;

    /**
     * 段① 键值表（Params / Headers）的高度。
     *
     * <p>174 = 表头 40 + 3 行 × 40 + 14（滚动条）。
     * <b>行高 40</b> 是段① 专用（{@code .batch-kv .table-row-cell}），
     * 全站其它表格仍是 34 —— 34 在只有两三行可见的矮表里显得挤，
     * 行与行的分隔读不出来。
     * 那 14 是<b>特意留给滚动条的</b>：行高变大后 3 行就填满，
     * 不留这一条就看不到「下面还有」，用户会以为表就这么高。
     */
    private static final double BATCH_KV_H = 174;

    /**
     * Body 页签里 Form 键值表的高度。
     *
     * <p>与 {@link #BATCH_KV_H} 同档（表头 40 + 3 行 × 40 + 滚动条 14）——
     * 同一页里两张表用同一个高度，切页签时表格不会「跳一下」。
     */
    private static final double BATCH_FORM_H = 174;

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
    /** 请求体类型：分段控件（不是下拉）—— 与单一请求页同一套控件词汇。 */
    private final ToggleGroup bodyTypeGroup = new ToggleGroup();
    private final TextArea bodyArea = new TextArea();
    /** 请求体降级的原因文字，挂在分段控件右侧（同一行，不新增行）。 */
    private Label bodyNote;
    private final KeyValueTable headersTable = new KeyValueTable(BATCH_KV_H);
    /** query 参数表（Params 页签）。 */
    private final KeyValueTable paramsTable = new KeyValueTable(BATCH_KV_H);
    /** Body 页签的实际内容（Form 用键值表，其余用文本区，切类型只切 visible）。 */
    private final StackPane bodyTabContent = new StackPane();
    /** 文本区类型（JSON / XML / Text / 无）下的请求体编辑区高度。 */
    private static final double BODY_TEXT_H = 50;
    private final Tab bodyTab = new Tab("Body");
    private final Label placeholderTip = UiKit.caption(
            "URL 与请求体中支持 {列名} 占位符，按 CSV 行替换。");
    /** 请求体实际类型 → Content-Type 常驻显示。 */
    private final Label ctypeLabel = UiKit.caption("");
    /** Body 工具行右侧的动作槽（只有 JSON 类型有内容）。 */
    private final HBox bodyActionSlot = new HBox(8);
    /**
     * 分段控件的按钮列表。
     *
     * <p>{@link ToggleGroup} 不是 {@code Iterable}，遍历不了选中项 ——
     * 「用当前请求作为批处理模板」要按类型选中对应那一项，只能自己留一份列表。
     */
    private final List<ToggleButton> bodyTypeItems = new ArrayList<>();
    /**
     * Body 页签在 <b>Form</b> 类型下的编辑器 —— 与单一请求页同一张表。
     *
     * <p>批处理页原先五种类型共用一个文本区，选中 Form 也只能手填 {@code a=1&b=2}。
     * 那与单一请求页「每个类型有自己专属的编辑器」的约定不一致，用户切过去
     * 看到的东西完全变了样。现在 Form 也用表，填键值对。
     */
    private final FormDataTable formDataTable = new FormDataTable(0);
    /** 段① 内层页签（探针要断言「三页签同名同序」与竖向高度）。 */
    private TabPane templateTabs;
    /** 请求体类型的分段控件（探针要断言「不是下拉」）。 */
    private Node bodyTypeSegment;

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
        // 段① 竖向预算（浏览器实测 244px）：
        //   请求行 36 + 页签轨道 42 + 工具行 38 + 请求体 48 + 间距 8×3=24
        //   + 正文上下内边距 16 = 正文 204；加卡片头 38 + 上下边框 2 = 244。
        // 整页：244 + 10 + ②38 + 10 + ③340 = 642 ≤ 653，余 11px。
        //
        // 两次踩过的坑（都在浏览器里量出来、不是推算出来的）：
        // ① 预算表曾把「正文 204」当成「卡片 204」，漏了头 38 和边框 2 ——
        //    真实值 244，整页 642 才成立。尺子刻错比画布画错更危险。
        // ② 请求体只能给 3 行（48）。给 4 行（76）时卡片 272 → 整页 670，
        //    **超 653 预算 17px**，段③ 底部被切掉一小截。
        // 所以：不是压 max-height（会把 TextArea 挤成细条），是减内容本身。
        body.setSpacing(8);
        body.getStyleClass().add("stage-body-compact");

        body.getChildren().addAll(
                buildTemplateRequestRow(),
                buildTemplateTabs());

        // 段收起后正文看不见了，头带摘要必须能读出「请求长什么样」
        methodBox.valueProperty().addListener((o, a, b) -> refreshTemplateSummary());
        urlField.textProperty().addListener((o, a, b) -> {
            refreshTemplateSummary();
            // 地址模板改了，占位符对不对得上的提示也要跟着变
            refreshPlaceholderWarning();
        });
        bodyArea.textProperty().addListener((o, a, b) -> refreshPlaceholderWarning());
        // 请求头 / query 参数里也可能写 {列名}，增删行时同步刷新提示
        headersTable.setOnRowsCountChanged(n -> refreshPlaceholderWarning());
        paramsTable.setOnRowsCountChanged(n -> refreshPlaceholderWarning());
        refreshTemplateSummary();
    }

    /**
     * 请求行：方法 + URL 占位符说明<b>同一行</b>，去掉 {@code field()} 左标签。
     *
     * <p>占位符说明不单起一行 —— 单起一行整页会超 653 预算（见 buildTemplateBody
     * 开头）。单一请求页本来就这么做（{@code statusBox} 与请求行同行），
     * 这里沿用同一做法。
     */
    private Node buildTemplateRequestRow() {
        methodBox.getItems().addAll(HttpRequestSpec.Method.values());
        methodBox.getSelectionModel().select(HttpRequestSpec.Method.GET);
        methodBox.getStyleClass().add("http-method");
        methodBox.setPrefWidth(118);
        methodBox.setMinWidth(118);

        urlField.setPromptText("https://api.example.com/order?orderId={orderId}");
        urlField.getStyleClass().add("http-url");
        HBox.setHgrow(urlField, Priority.ALWAYS);
        urlField.setMaxWidth(Double.MAX_VALUE);

        HBox row = new HBox(8, methodBox, urlField, placeholderTip);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("http-hint-row");
        return row;
    }

    /**
     * 三页签：Params / Headers / Body —— 与单一请求页同名同序。
     *
     * <p>{@link #bodyTabContent} 是一个 {@link StackPane}：切页签只切
     * visible+managed，各页签内容各自持有，来回切不丢（与单一请求页同一个道理）。
     */
    private Node buildTemplateTabs() {
        Button addParams = compactSecondary("+ 新增参数");
        addParams.setOnAction(e -> paramsTable.addRow());
        Button clearParams = compactSecondary("清空");
        clearParams.setOnAction(e -> paramsTable.clear());
        // .batch-kv 是段① 表的样式作用域：行高 40 + 显式滚动条（见 app.css）
        paramsTable.getStyleClass().add("batch-kv");
        VBox paramsBox = new VBox(8,
                toolRow(addParams, clearParams, UiKit.spacer(),
                        UiKit.caption("这些键值会以 query 参数拼到 URL 之后")),
                paramsTable);
        VBox.setVgrow(paramsTable, Priority.ALWAYS);

        Button addHeaders = compactSecondary("+ 新增请求头");
        addHeaders.setOnAction(e -> headersTable.addRow());
        Button clearHeaders = compactSecondary("清空");
        clearHeaders.setOnAction(e -> headersTable.clear());
        headersTable.getStyleClass().add("batch-kv");
        VBox headersBox = new VBox(8,
                toolRow(addHeaders, clearHeaders, UiKit.spacer(),
                        UiKit.caption("常用请求头")),
                headersTable);
        VBox.setVgrow(headersTable, Priority.ALWAYS);

        // Body 页签的内容 = 工具行 + 编辑器。
        // 工具行必须**在页签内部**：它是「这一页的工具行」，与 Params / Headers
        // 页签里的工具行同一个位置。放到 TabPane 外面会变成
        // 「页签+编辑器」一块、「工具行」一块 —— 工具行跑到编辑器下面去了，
        // 而用户切页签时它也不该跟着换页。
        Node bodyToolRow = buildBodyToolRow();

        // 编辑器 slot：Form 用键值表，其余类型用文本区。
        // 二者叠在同一个 StackPane 里，切类型只切 visible+managed ——
        // 与单一请求页 editorHolder 同一个做法，**各自内容不丢**：
        // 在 Form 里填的键值对，切到 JSON 再切回来还在。
        formDataTable.getStyleClass().add("batch-kv");
        formDataTable.setFileRowsAllowed(false);
        formDataTable.setOnChanged(this::refreshPlaceholderWarning);
        bodyTabContent.getChildren().addAll(bodyArea, formDataTable);
        VBox.setVgrow(bodyArea, Priority.ALWAYS);
        VBox.setVgrow(formDataTable, Priority.ALWAYS);

        VBox bodyBox = new VBox(8, bodyToolRow, bodyTabContent);
        VBox.setVgrow(bodyTabContent, Priority.ALWAYS);
        // bodyBox 不写死高度，让它按内容算（工具行 38 + 间距 8 + 编辑器）。
        // 写死一个与内容差 2px 的值，那 2px 正好把编辑器顶出可视区 ——
        // 探针量编辑器仍报 50px（节点在、尺寸对），但截图上完全看不见。
        // 这就是「结构断言全绿 ≠ 长得对」：量高度量不到「被父容器裁掉」。
        bodyBox.setMaxHeight(Double.MAX_VALUE);
        bodyTab.setContent(bodyBox);
        showBodyEditor(HttpRequestSpec.BodyType.NONE);

        Tab paramsTab = new Tab("Params", paramsBox);
        Tab headersTab = new Tab("Headers", headersBox);

        TabPane tabs = new TabPane(paramsTab, headersTab, bodyTab);
        templateTabs = tabs;
        // 不能用 .http-req-tabs —— 那个类钉死 312（单一请求页的档），
        // 套过来段① 会从 244 涨到 456，整页 855，段③ 被推出视口。
        tabs.getStyleClass().add("batch-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        // 段① 只有 Body 一个数据页签，默认就选中它
        tabs.getSelectionModel().select(bodyTab);
        // 内层数据页签要在段① 剩余高度里自然生长，不能按内容算完就缩回去。
        // CSS 里也写了同一个值 277：CSS 一旦命中就会覆盖 Java 侧的 setter，
        // 两边不同步的话量到的就是 CSS 那个值。
        tabs.setPrefHeight(TEMPLATE_TABS_H);
        tabs.setMinHeight(TEMPLATE_TABS_H);
        tabs.setMaxHeight(Double.MAX_VALUE);

        paramsTab.textProperty().addListener((o, a, b) ->
                paramsTab.setText("Params" + suffixOf(paramsTable.entryCount())));
        headersTab.textProperty().addListener((o, a, b) ->
                headersTab.setText("Headers" + suffixOf(headersTable.entryCount())));
        return tabs;
    }

    /** 页签标题上的项数后缀（0 项时不带括号）。 */
    private static String suffixOf(int count) {
        return count > 0 ? " (" + count + ")" : "";
    }

    /**
     * Body 工具行：分段控件 + Content-Type + 降级说明 + 动作槽。
     *
     * <p>只列 {@link HttpRequestSpec.BodyType#batchTypes()} 的 5 项 ——
     * {@code FORM_DATA} / {@code BINARY} 只有「路径」没有「值」，
     * {@code {列名}} 占位符在它们身上没有意义，列出来等于给一个点了会出错的入口。
     */
    private Node buildBodyToolRow() {
        // 文案要短 —— 它与分段控件同一行，写长了会把 5 个项挤到换行
        bodyNote = UiKit.degradeNote("此项不生效（类型为「无」）");

        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("http-toolbar");
        row.getChildren().addAll(buildBodyTypeSegment(), ctypeLabel, bodyNote,
                UiKit.spacer(), bodyActionSlot);
        refreshBodyActions();
        refreshCtypeLabel();

        // 降级态下 TextArea 没有 placeholder 可用（那 API 在 TextField 上），
        // 所以提示写进 promptText：淡到 42% 时它仍然读得到，
        // 用户能看出「下面还有个框，只是还没启用」而不是以为根本没这一栏。
        bodyArea.setPromptText("请求体模板，例如 {\"orderId\": \"{orderId}\"}");
        bodyArea.getStyleClass().add("mono-area");
        bodyArea.setWrapText(true);
        // 48px ≈ 3 行：给 4 行整页会超预算（见 buildTemplateBody 开头的记账）
        bodyArea.setPrefHeight(48);
        applyBodyDegrade(false);
        return row;
    }

    /** 请求体类型的分段控件（与单一请求页 .http-type-seg 同一档）。 */
    private Node buildBodyTypeSegment() {
        HBox seg = new HBox(2);
        seg.getStyleClass().addAll("segmented", "http-type-seg");
        for (HttpRequestSpec.BodyType type : HttpRequestSpec.BodyType.batchTypes()) {
            ToggleButton item = new ToggleButton(type.label());
            item.setToggleGroup(bodyTypeGroup);
            item.setUserData(type);
            item.getStyleClass().add("segmented-item");
            bodyTypeItems.add(item);
            if (type == HttpRequestSpec.BodyType.NONE) {
                item.setSelected(true);
            }
            item.selectedProperty().addListener((o, was, now) -> {
                if (Boolean.TRUE.equals(now)) {
                    applyBodyDegrade(type != HttpRequestSpec.BodyType.NONE);
                    // 切类型要同时切编辑器：Form 用键值表，其余用文本区
                    showBodyEditor(type);
                    refreshBodyTabTitle();
                    refreshCtypeLabel();
                }
            });
            seg.getChildren().add(item);
        }
        bodyTypeSegment = seg;
        return seg;
    }

    /** 动作槽：JSON 类型给「格式化」，其余为空（旧版此处的按钮点了没反应）。 */
    private void refreshBodyActions() {
        HBox slot = bodyActionSlot;
        slot.getChildren().clear();
        if (declaredType() == HttpRequestSpec.BodyType.JSON) {
            Button format = compactSecondary("格式化");
            format.setOnAction(e -> formatJsonBody());
            slot.getChildren().add(format);
        }
    }

    /** Body 页签标题带上项数与类型（段收起后仍能读出「请求长什么样」）。 */
    private void refreshBodyTabTitle() {
        HttpRequestSpec.BodyType type = declaredType();
        // 批处理页的请求体是**一段模板**，不是「若干项」——
        // 写「(1 项)」会让人以为是表单字段数。写「有内容 / 空」才读得对。
        boolean hasBody = bodyArea.getText() != null && !bodyArea.getText().isBlank();
        bodyTab.setText("Body · " + type.label() + (hasBody ? "（有内容）" : ""));
    }

    /** Content-Type 常驻位：把这一类型的真实 Content-Type 说出来。 */
    private void refreshCtypeLabel() {
        HttpRequestSpec.BodyType type = declaredType();
        ctypeLabel.setText(type == HttpRequestSpec.BodyType.NONE ? "" : type.contentType());
        ctypeLabel.setVisible(!ctypeLabel.getText().isEmpty());
        ctypeLabel.setManaged(ctypeLabel.isVisible());
    }

    /**
     * 按请求体类型切 Body 页签的编辑器。
     *
     * <p>Form → 键值表（与单一请求页同一张 {@link FormDataTable}），
     * 其余 → 文本区。二者都在 {@link #bodyTabContent} 这个 StackPane 里，
     * 只切 visible+managed，**各自内容各自持有**（与单一请求页
     * {@code editorHolder} 同一个做法）——在 Form 里填的键值对，
     * 切到 JSON 再切回来还在。
     *
     * <p>内容区高度也跟着换：文本区 50、Form 表 174（3 行 × 40 + 表头 + 滚动条）。
     */
    private void showBodyEditor(HttpRequestSpec.BodyType type) {
        boolean useForm = type == HttpRequestSpec.BodyType.FORM;
        setShown(bodyArea, !useForm);
        setShown(formDataTable, useForm);
        double h = useForm ? BATCH_FORM_H : BODY_TEXT_H;
        bodyTabContent.setPrefHeight(h);
        bodyTabContent.setMinHeight(h);
    }

    /** visible + managed 必须成对切 —— 只切 visible 会留下空白带。 */
    private static void setShown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    /** 选中分段控件里对应类型的那一项（供「用当前请求作为批处理模板」调用）。 */
    private void selectBodyType(HttpRequestSpec.BodyType type) {
        for (ToggleButton item : bodyTypeItems) {
            if (item.getUserData() == type) {
                item.setSelected(true);
                return;
            }
        }
    }

    /** 界面上选的请求体类型（分段控件的选中项）。 */
    private HttpRequestSpec.BodyType declaredType() {
        Toggle selected = (Toggle) bodyTypeGroup.getSelectedToggle();
        if (selected == null || selected.getUserData() == null) {
            return HttpRequestSpec.BodyType.NONE;
        }
        return (HttpRequestSpec.BodyType) selected.getUserData();
    }

    /** 格式化请求体（JSON 缩进）。批处理页的占位符是合法的 JSON 字符串值。 */
    private void formatJsonBody() {
        String text = bodyArea.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        java.io.StringWriter out = new java.io.StringWriter();
        try {
            com.google.gson.JsonElement parsed =
                    com.google.gson.JsonParser.parseString(text);
            // 缩进由 JsonWriter 负责，不要后处理替换（会连字符串值里的空白一起改）
            com.google.gson.stream.JsonWriter writer =
                    new com.google.gson.stream.JsonWriter(out);
            writer.setIndent("  ");
            com.google.gson.internal.Streams.write(parsed, writer);
            bodyArea.setText(out.toString());
        } catch (java.io.IOException | RuntimeException notJson) {
            // 不是合法 JSON 就不动它 —— 静默改写用户的模板比不格式化更糟
        }
    }

    /** 工具行：按钮 + 弹性空隙 + 右侧说明文字（38px 那一档）。 */
    private static HBox toolRow(Node... nodes) {
        HBox row = new HBox(8, nodes);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("http-toolbar");
        return row;
    }

    /** 比标准档矮一档的次要按钮（段控件 32 / 标准 32 那一档）。 */
    private static Button compactSecondary(String text) {
        Button button = UiKit.secondaryButton(text);
        button.getStyleClass().add("button-compact");
        return button;
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
        // 两个编辑器都要降级 —— 切到 Form 时文本区是隐藏的那个，
        // 只降 bodyArea 会出现「选 Form 但表单不降级」的不一致。
        UiKit.setDegraded(bodyArea, null, !enabled);
        UiKit.setDegraded(formDataTable, null, !enabled);
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
        HttpRequestSpec.BodyType declared = declaredType();
        List<Map.Entry<String, String>> baseHeaders = headersTable.entries();
        List<Map.Entry<String, String>> baseParams = paramsTable.entries();
        String url = urlField.getText();
        int index = 1;
        for (Map<String, String> vars : currentTable.rows) {
            // 键值按 CSV 行替换后进 formFields —— 引擎的 FORM 分支
            // 会对它们做百分号编码（空格 → +）。不这么做的话，
            // Form 的键值就永远进不了请求体。
            List<Map.Entry<String, String>> formFields =
                    resolveFormFields(declared, vars);
            String body = declared == HttpRequestSpec.BodyType.FORM
                    ? ""
                    : TemplateResolver.resolve(bodyArea.getText(), vars);
            HttpRequestSpec.BodyType bodyType = HttpRequestSpec.inferBodyType(declared, body);
            HttpRequestSpec spec = new HttpRequestSpec(
                    methodBox.getValue(),
                    appendQuery(TemplateResolver.resolve(url, vars),
                            resolveFormFieldsEntries(baseParams, vars)),
                    bodyType,
                    body,
                    formFields,
                    List.of(),
                    null);
            resultRows.add(new ResultRow(index++, vars, spec, resolveHeaders(baseHeaders, vars)));
        }
    }

    /** Form 类型下、按键替换后的键值对；其它类型返回空列表。 */
    private List<Map.Entry<String, String>> resolveFormFields(
            HttpRequestSpec.BodyType declared, Map<String, String> vars) {
        if (declared != HttpRequestSpec.BodyType.FORM) {
            return List.of();
        }
        return resolveFormFieldsEntries(formDataTableTextFields(), vars);
    }

    /** Form 表里的**文本段**键值（batchTypes 里没有 Form-data，所以全是文本段）。 */
    private List<Map.Entry<String, String>> formDataTableTextFields() {
        List<Map.Entry<String, String>> list = new ArrayList<>();
        for (HttpRequestSpec.FormField field : formDataTable.fields()) {
            if (!field.isFile()) {
                list.add(new java.util.AbstractMap.SimpleEntry<>(
                        field.name, field.text == null ? "" : field.text));
            }
        }
        return list;
    }

    private List<Map.Entry<String, String>> resolveFormFieldsEntries(
            List<Map.Entry<String, String>> src, Map<String, String> vars) {
        List<Map.Entry<String, String>> list = new ArrayList<>();
        for (Map.Entry<String, String> e : src) {
            list.add(new java.util.AbstractMap.SimpleEntry<>(
                    TemplateResolver.resolve(e.getKey(), vars),
                    TemplateResolver.resolve(e.getValue(), vars)));
        }
        return list;
    }

    /**
     * 把 query 参数拼到 URL 后面。
     *
     * <p>Params 页签一直存在，但值以前<b>根本没有进请求</b> ——
     * {@code prepareResultRows} 只读了 url / headers / body 三处。
     * 那一页写着「这些键值会以 query 参数拼到 URL 之后」，说的却没做到。
     * 顺带在这里补上：URL 已带 {@code ?} 就用 {@code &}，否则用 {@code ?}。
     */
    private static String appendQuery(String url, List<Map.Entry<String, String>> params) {
        if (params.isEmpty()) {
            return url;
        }
        StringBuilder sb = new StringBuilder(url);
        boolean first = !url.contains("?");
        for (Map.Entry<String, String> p : params) {
            if (p.getKey() == null || p.getKey().isBlank()) {
                continue;
            }
            sb.append(first ? '?' : '&');
            first = false;
            sb.append(encodeQuery(p.getKey())).append('=')
                    .append(encodeQuery(p.getValue() == null ? "" : p.getValue()));
        }
        return sb.toString();
    }

    /** query 参数的百分号编码（与引擎的 encodeForm 同一套，空格 → +）。 */
    private static String encodeQuery(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
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
        selectBodyType(type);
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

    /** 测试用：方法下拉（探针要断言 URL 是请求行里最宽的元素）。 */
    ComboBox<HttpRequestSpec.Method> methodBoxForTest() {
        return methodBox;
    }

    /** 测试用：段① 的内层页签（Params / Headers / Body）。 */
    TabPane templateTabsForTest() {
        return templateTabs;
    }

    /** 测试用：Body 页签。 */
    Tab bodyTabForTest() {
        return bodyTab;
    }

    /** 测试用：请求体类型的分段控件。 */
    Node bodyTypeSegmentForTest() {
        return bodyTypeSegment;
    }

    /** 测试用：界面上选的请求体类型。 */
    HttpRequestSpec.BodyType declaredBodyTypeForTest() {
        return declaredType();
    }

    /** 测试用：选中分段控件里对应类型的那一项。 */
    void selectBodyTypeForTest(HttpRequestSpec.BodyType type) {
        selectBodyType(type);
    }

    /** 测试用：query 参数表。 */
    KeyValueTable paramsTableForTest() {
        return paramsTable;
    }

    /** 测试用：Form 用的那张键值表。 */
    FormDataTable bodyFormTableForTest() {
        return formDataTable;
    }

    /** 测试用：当前是否显示 Form 键值表（而不是文本区）。 */
    boolean bodyFormTableVisibleForTest() {
        return formDataTable.isVisible() && formDataTable.isManaged();
    }

    /** 测试用：按当前界面生成待执行行（占位符替换在这里发生）。 */
    void prepareRowsForTest() {
        prepareResultRows();
    }

    /** 测试用：第一条待执行行的请求描述（断「键值真的进了引擎模型」）。 */
    HttpRequestSpec firstSpecForTest() {
        return resultRows.isEmpty() ? null : resultRows.get(0).spec;
    }

    /** 测试用：请求头键值表。 */
    KeyValueTable headersTableForTest() {
        return headersTable;
    }

    /**
     * 测试用：段① 的表能显示几行（行高 40 口径：{@code (表高 − 表头 40) ÷ 40}）。
     *
     * <p>上一版只有 0.14 行 —— 用户报「间距太小，无法输入内容」时实测就是 0。
     * 判据要的是「至少 2 行」，因为 1 行只能看、不能改。
     */
    int headersVisibleRowsForTest() {
        javafx.scene.control.TableView<?> view = headersTable.tableForTest();
        double header = 40;
        return (int) Math.floor((view.getHeight() - header) / 40);
    }

    /** 测试用：段① 表的行高（用户要求 34 → 40）。 */
    double headersRowHeightForTest() {
        javafx.scene.control.TableView<?> view = headersTable.tableForTest();
        for (javafx.scene.Node node : view.lookupAll(".table-row-cell")) {
            return node.getLayoutBounds().getHeight();
        }
        return -1;
    }

    /**
     * 测试用：段① 的表在有多余行时**能滚**吗。
     *
     * <p>行高抬到 40 后 3 行就填满，不显式开滚动条用户就看不到「下面还有」。
     * 判据是「有内容超出可视高度 且 竖滚动条可见」。
     */
    boolean headersTableScrollableForTest() {
        javafx.scene.control.TableView<?> view = headersTable.tableForTest();
        int rows = 0;
        for (javafx.scene.Node node : view.lookupAll(".table-row-cell")) {
            rows++;
        }
        if (rows <= headersVisibleRowsForTest()) {
            return true;   // 内容没超出，不需要滚
        }
        // TableView 的滚动条 style class 只有 "scroll-bar" ——
        // 竖/横是**伪类** :vertical / :horizontal，不是 style class，
        // 所以 contains("vertical") 永远为 false。别用 style class 判方向。
        for (javafx.scene.Node bar : view.lookupAll(".scroll-bar")) {
            if (bar.isVisible() && bar.getOpacity() > 0.9
                    && bar.getLayoutBounds().getWidth() < 20) {
                return true;
            }
        }
        return false;
    }

    /** 测试用：请求体编辑区。 */
    TextArea bodyAreaForTest() {
        return bodyArea;
    }

    /**
     * 测试用：请求体编辑器<b>完整落在 Body 页签的可视区内</b>吗。
     *
     * <p>为什么需要这条：{@code TabPane} 的 content-area 装不下时会把内容
     * <b>裁掉</b>，而 {@code bodyArea.getHeight()} 仍然是 50px ——
     * 节点在、尺寸对，探针量高度<b>量不到「被父容器裁掉」</b>。
     * 本轮就这样翻过车：32 条断言全绿，截图上编辑器只剩一条边。
     * 判据必须是「编辑器的底边没有超出它父容器的底边」。
     */
    boolean bodyEditorFullyVisibleForTest() {
        if (bodyArea.getParent() == null) {
            return false;
        }
        javafx.scene.control.TabPane tabs = templateTabs;
        if (tabs == null) {
            return false;
        }
        javafx.scene.layout.Region contentArea = null;
        for (javafx.scene.Node node : tabs.lookupAll(".tab-content-area")) {
            if (node instanceof javafx.scene.layout.Region region) {
                contentArea = region;
            }
        }
        if (contentArea == null) {
            return false;
        }
        // 判据要跟 **TabPane 的 content-area** 比，不能跟 bodyTabContent 比：
        // bodyTabContent 自己也会被压扁，跟它比永远「没超出」——
        // 这条断言在 TabPane 只有 110 时实测仍为 true，等于恒真。
        javafx.geometry.Bounds editor =
                bodyArea.localToScene(bodyArea.getBoundsInLocal());
        javafx.geometry.Bounds host =
                contentArea.localToScene(contentArea.getBoundsInLocal());
        return editor.getMaxY() <= host.getMaxY() + 0.5
                && bodyArea.getHeight() >= 40;
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
