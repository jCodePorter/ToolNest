package com.bytefuture.toolnest.ui.components;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.css.PseudoClass;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * 功能模块公用的界面构件工厂。
 *
 * <p>把「标题 + 说明 + 区块 + 按钮 + 结果行」这类到处重复的写法收敛到这里，
 * 让新增功能时只关注业务逻辑本身，同时保证全站视觉一致 ——
 * 各功能模块不直接写 {@code setPadding} / {@code setStyle}，一律走这里。
 *
 * <p>样式令牌与规则见 {@code DESIGN.md}，本类只负责组装结构。
 *
 * <h2>按钮的四个级别</h2>
 * <p>按钮是这套界面里最容易失控的元素，因此这里把它收敛成四个语义级别，
 * 模块侧只从这四个工厂方法里挑，不要自行拼样式类：
 * <ul>
 *   <li>{@link #primaryButton} —— 实底主色，<b>一屏只允许一处</b>。
 *       判断标准是「用户来这一屏最主要想做的那件事」。</li>
 *   <li>{@link #secondaryButton} —— 白底描边，可并列多个。可逆的普通动作。</li>
 *   <li>{@link #quietButton} —— 纯文字，无底无框。辅助动作，
 *       例如「用当前请求作为批处理模板」这种跨页面的快捷入口。</li>
 *   <li>{@link #dangerButton} —— 危险色描边。会丢数据的动作，
 *       单独配色以免和普通按钮混在一起。</li>
 * </ul>
 */
public final class UiKit {

    /** 结果行标签的定宽，与 app.css 的 .result-label 严格一致。 */
    private static final double RESULT_LABEL_WIDTH = 96;

    /** 空值的表示：破折号而非连字符，见 {@link #resultRow} 的说明。 */
    private static final String EMPTY_VALUE = "\u2014";

    private UiKit() {
    }

    // ==================== 页面骨架 ====================

    /**
     * 模块页面的根容器：垂直排列，带标准内边距。
     *
     * <p>{@code title} 与 {@code description} 只用于无障碍标签，不渲染成可见文字。
     * 原因：模块的一句话说明已由详情页页头统一呈现（页头是唯一的位置与身份来源），
     * 正文再重复一遍会让同一句话在同一屏出现两次，白白吃掉首屏空间。
     *
     * <p>若某个模块需要在正文里补充额外说明，请在其 {@code buildContent()} 内
     * 显式添加 {@link #caption(String)} 或 {@link #notice(String, String)}，
     * 这类信息与页头说明是不同用途，不属于重复。
     *
     * <h2>页面高度由谁决定</h2>
     * <p>这里不预设页面的高度策略，而是让模块自己表达：<b>直接子节点里只要有一个
     * 设了 {@code VBox.setVgrow(node, Priority.ALWAYS)}，这一页就是「铺满一屏」
     * 型</b> —— 说明那块内容本就该吸收剩余高度（JSON 的左右编辑区、HTTP 的请求页签、
     * UUID 的结果列表都属此类），页面按视口高布局，不出现滚动条。
     *
     * <p>反之，整页都是固定高度的卡片堆叠时（时间戳、Cron、文件哈希），
     * 内容超出视口就由外层整体滚动，而不是把控件压扁 —— 见
     * {@code ContentPane#buildDetail} 的分流逻辑。
     */
    public static VBox page(String title, String description) {
        VBox root = new VBox(16);
        root.getStyleClass().add("module-body");
        root.setAccessibleText(title + "。" + (description == null ? "" : description));
        return root;
    }

    /**
     * 内容区页头（宫格页 / 详情页共用）。
     *
     * <p>结构：标题行（可含前置控件、标题、计数） + 可选说明行，
     * 底部一条 1px 分隔线。页头保持白底，而内容区是浅灰底 ——
     * 分层靠底色差完成，分隔线只是补充，不必承担全部重量。
     *
     * @param leading 标题左侧的控件（如返回按钮），可为 null
     * @param title   页面标题
     * @param count   计数徽标文本，可为 null
     * @param desc    标题下方的说明，可为 null
     */
    public static VBox pageHeader(Node leading, String title, String count, String desc) {
        VBox header = new VBox(6);
        header.getStyleClass().addAll("page-header");
        if (leading != null) {
            header.getStyleClass().add("page-header-detail");
        }

        HBox titleRow = new HBox(10);
        titleRow.getStyleClass().add("page-header-title-row");
        if (leading != null) {
            titleRow.getChildren().add(leading);
        }

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add(leading != null ? "detail-title" : "page-title");
        titleRow.getChildren().add(titleLabel);

        if (count != null && !count.isBlank()) {
            Label countLabel = new Label(count);
            countLabel.getStyleClass().add("page-title-sub");
            titleRow.getChildren().addAll(spacer(), countLabel);
        } else {
            titleRow.getChildren().add(spacer());
        }
        header.getChildren().add(titleRow);

        if (desc != null && !desc.isBlank()) {
            Label descLabel = new Label(desc);
            descLabel.getStyleClass().add("page-desc");
            descLabel.setWrapText(true);
            header.getChildren().add(descLabel);
        }
        return header;
    }

    /** 页面正文容器：带标准留白的 VBox，用于包裹宫格或功能内容。 */
    public static VBox pageBody(double spacing, Node... children) {
        VBox body = new VBox(spacing, children);
        body.getStyleClass().add("page-body");
        return body;
    }

    /**
     * 详情页的返回按钮。
     *
     * <p>图标与文字用同一套线性图标族，而不是 Unicode 箭头字符 ——
     * 箭头字符在不同字体下的粗细与箭头比例差别很大。
     */
    public static Button backButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("back-button");
        button.setGraphic(Icons.of("arrow-left", 14, "icon-muted"));
        button.setAccessibleText("返回上一页");
        return button;
    }

    // ==================== 区块 ====================

    /**
     * 区块容器：白底 + 1px 描边 + 可选的内凹头带。
     *
     * <p>一整页的区块数量应保持克制 —— 用一条头带分隔标题与内容，
     * 比给每个小节都套一个灰底方框更容易阅读。
     */
    public static VBox card(double spacing, Node... children) {
        VBox box = new VBox(spacing, children);
        box.getStyleClass().add("card");
        return box;
    }

    /**
     * 带标题头带的区块。
     *
     * @param title 区块标题
     * @param note  标题右侧的补充说明，可为 null
     */
    public static VBox section(String title, String note, Node... children) {
        VBox box = new VBox(14);
        box.getStyleClass().add("card");

        HBox head = new HBox(8);
        head.getStyleClass().add("card-head");

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("card-head-title");
        head.getChildren().add(titleLabel);

        head.getChildren().add(spacer());
        if (note != null && !note.isBlank()) {
            Label noteLabel = new Label(note);
            noteLabel.getStyleClass().add("card-head-note");
            head.getChildren().add(noteLabel);
        }

        box.getChildren().add(head);
        box.getChildren().addAll(children);
        return box;
    }

    // ==================== 字段与结果 ====================

    /** 带标签的字段块。 */
    public static VBox field(String labelText, Node control) {
        Label label = new Label(labelText);
        label.getStyleClass().add("field-label");
        return new VBox(6, label, control);
    }

    /** 横向字段行：标签在左、控件在右，用于配置项密集排列。 */
    public static HBox fieldRow(String labelText, Node control) {
        Label label = new Label(labelText);
        label.getStyleClass().add("field-label");
        label.setMinWidth(64);

        HBox row = new HBox(12, label, control);
        row.getStyleClass().add("field-row");
        return row;
    }

    /** 小号灰色说明文字。 */
    public static Label caption(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("caption");
        label.setWrapText(true);
        return label;
    }

    /**
     * 结果行：定宽标签 + 等宽数值 + 可选复制按钮。
     *
     * <p>标签定宽 {@value #RESULT_LABEL_WIDTH}px 是为了建立纵向对齐网格 ——
     * 若标签宽度随文字伸缩，一列结果读下来就要不停地左右找基准。
     *
     * <p>空值统一显示为破折号 {@code —} 而不是连字符 {@code -}：
     * 连字符在等宽字体里太短，混在 13px 的数值中间看起来像一个待填的输入格。
     *
     * @param labelText 行首标签
     * @param valueText 数值，为 null 或空白时显示破折号
     * @param divided   是否在顶部画一条极轻的分隔线（组内第一行传 false）
     * @param copyable  可复制的文本，为 null 则不显示复制按钮
     */
    public static HBox resultRow(String labelText, String valueText,
                                 boolean divided, String copyable) {
        boolean empty = valueText == null || valueText.isBlank();
        Label value = new Label(empty ? EMPTY_VALUE : valueText);
        if (empty) {
            value.getStyleClass().add("result-value-empty");
        }
        // 结果行高固定，长值用省略号截断；完整内容挂在 tooltip 上
        if (!empty && valueText.length() > 34) {
            value.setTooltip(new Tooltip(valueText));
        }
        return resultRow(labelText, value, divided, copyable);
    }

    /**
     * 结果行（数值由调用方自己持有）。
     *
     * <p>与上面的 {@link #resultRow(String, String, boolean, String)} 是同一套排版，
     * 区别只在于数值是一个现成的 {@link Node}。时间戳、Cron 这类结果需要随输入
     * 实时刷新，模块必须自己拿着那个 Label 去 {@code setText}，因此不能把值收成 String。
     * 两条路径共用这一份装配代码，避免出现「静态结果行」与「实时结果行」
     * 两套长得不一样的排版。
     */
    public static HBox resultRow(String labelText, Node value,
                                 boolean divided, String copyable) {
        Label label = new Label(labelText);
        label.getStyleClass().add("result-label");

        if (!value.getStyleClass().contains("result-value")) {
            value.getStyleClass().add("result-value");
        }
        HBox.setHgrow(value, Priority.ALWAYS);
        // 光设 Hgrow 不够：Control/Label 的 maxWidth 默认是 USE_PREF_SIZE，
        // 宽度上不去，弹性生长拿不到富余空间 —— 行尾的复制按钮就会紧贴在数值后面，
        // 而不是右对齐成一列；超长数值也不会走省略号（改用 tooltip）那条路。
        if (value instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }

        HBox row = new HBox(10, label, value);
        row.getStyleClass().add("result-row");
        if (divided) {
            row.getStyleClass().add("result-row-divided");
        }
        row.setAlignment(Pos.CENTER_LEFT);

        if (copyable != null && !copyable.isBlank()) {
            Button copy = quietButton("复制");
            copy.setOnAction(e -> copyToClipboard(copyable));
            copy.setTooltip(new Tooltip("复制「" + labelText + "」"));
            row.getChildren().add(copy);
        }
        return row;
    }

    /**
     * 结果行（动态数值 + 行内复制按钮）。
     *
     * <p>与上面两个版本是同一套排版，区别在于复制按钮**点击的那一刻才去读** value 的文本。
     * 时间戳、Cron 这类结果的数值随输入实时刷新，若在构造时就把文本交给按钮，
     * 复制到的永远是初始的占位破折号 —— 这是「静态结果行」直接套用到实时结果上最容易踩的坑。
     *
     * <p>值为空（占位破折号）时按钮置灰而不是隐藏：置灰能保住右侧按钮列的对齐，
     * 同时明确告诉用户「现在没东西可复制」，隐藏则会让这一行看起来少了点什么。
     */
    public static HBox copyableResultRow(String labelText, Label value, boolean divided) {
        HBox row = resultRow(labelText, value, divided, null);

        Button copy = quietButton("复制");
        copy.setTooltip(new Tooltip("复制「" + labelText + "」"));
        copy.setOnAction(e -> copyToClipboard(value.getText()));
        copy.disableProperty().bind(value.textProperty().isEqualTo(EMPTY_VALUE));
        row.getChildren().add(copy);
        return row;
    }

    /** 把若干结果行包成一个圆角描边的整体。 */
    public static VBox resultTable(Node... rows) {
        VBox box = new VBox(rows);
        box.getStyleClass().add("result-table");
        return box;
    }

    // ==================== 空状态 ====================

    /**
     * 空状态：图标 + 主文案 + 次文案（+ 可选操作）。
     *
     * <p>全站统一这一种语法。旧版各页空态写法不一致 ——
     * 有的是正中间一句灰字，有的是左上角一个占位符，
     * 用户分不清「这里现在是空的」还是「这里坏了」。
     *
     * @param iconName Icons 中注册的图标名
     * @param title    主文案，说明当前状态
     * @param hint     次文案，说明下一步能做什么，可为 null
     * @param actions  可选的操作按钮
     */
    public static VBox emptyState(String iconName, String title, String hint, Node... actions) {
        VBox box = new VBox();
        box.getStyleClass().add("empty-state");
        box.setAlignment(Pos.CENTER);
        box.getChildren().add(Icons.of(iconName, 26, "icon-subtle"));

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("empty-state-title");
        // 必须撑满内容宽度：Label 的 maxWidth 默认是 USE_PREF_SIZE，于是标签按「文字原宽」
        // 摆在容器里 —— 文案一旦比容器宽，右端就溢出、而且 VBox 的居中也随之失效
        // （居中一个比容器还宽的节点等于没居中）。文字自身的水平对齐由 CSS 的
        // -fx-text-alignment: center 负责，两者缺一不可。
        titleLabel.setMaxWidth(Double.MAX_VALUE);
        box.getChildren().add(titleLabel);

        if (hint != null && !hint.isBlank()) {
            Label hintLabel = new Label(hint);
            hintLabel.getStyleClass().add("empty-state-hint");
            hintLabel.setWrapText(true);
            hintLabel.setMaxWidth(Double.MAX_VALUE);
            // 存下引用而不是让调用方按下标去 children 里翻 —— 空状态的节点顺序
            // 一旦调整（比如标题和说明互换），按下标的代码会静默改错元素。
            box.getProperties().put(EMPTY_HINT_KEY, hintLabel);
            box.getChildren().add(hintLabel);
        }

        if (actions.length > 0) {
            HBox row = new HBox(8, actions);
            row.setAlignment(Pos.CENTER);
            box.getChildren().add(row);
        }
        return box;
    }

    /** {@link #emptyState} 中说明标签在节点属性表里的键。 */
    private static final String EMPTY_HINT_KEY = "toolnest.emptyHint";

    /**
     * 替换空状态里的说明文字（如拖入的文件格式不支持时给出即时反馈）。
     *
     * @return 是否替换成功（创建空状态时未传 hint 则返回 false）
     */
    public static boolean setEmptyStateHint(VBox emptyState, String text) {
        Object hint = emptyState == null ? null : emptyState.getProperties().get(EMPTY_HINT_KEY);
        if (hint instanceof Label label) {
            label.setText(text);
            return true;
        }
        return false;
    }

    // ==================== 状态提示 ====================

    /**
     * 语义提示条：浅色底 + 左侧 3px 语义色边。
     *
     * <p>用于需要跨行说明的错误与警告；单行短提示直接用
     * {@link #error(String)} / {@link #hint(String)} 即可。
     *
     * @param kind {@code "error"} / {@code "warning"} / {@code "info"}
     */
    public static HBox notice(String kind, String text) {
        Node icon = switch (kind == null ? "" : kind) {
            case "error" -> Icons.of("x", 15, "icon-danger");
            case "warning" -> Icons.of("warn", 15, "icon-warning");
            default -> Icons.of("info", 15, "icon-accent");
        };

        Label message = new Label(text);
        message.setWrapText(true);
        message.getStyleClass().add(switch (kind == null ? "" : kind) {
            case "error" -> "error-text";
            case "warning" -> "hint-text";
            default -> "caption";
        });
        HBox.setHgrow(message, Priority.ALWAYS);

        HBox box = new HBox(8, icon, message);
        box.getStyleClass().addAll("notice", "notice-" + (kind == null ? "info" : kind));
        box.setAlignment(Pos.CENTER_LEFT);
        box.setVisible(false);
        box.setManaged(false);
        return box;
    }

    /** 显示提示条内容。 */
    public static void showNotice(HBox notice, String text) {
        if (notice.getChildren().size() < 2) {
            return;
        }
        ((Label) notice.getChildren().get(1)).setText(text);
        notice.setVisible(true);
        notice.setManaged(true);
    }

    /** 隐藏提示条。 */
    public static void hideNotice(HBox notice) {
        notice.setVisible(false);
        notice.setManaged(false);
    }

    /** 红色错误提示文字（单行内联错误用）。 */
    public static Label error(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("error-text");
        label.setWrapText(true);
        return label;
    }

    /** 黄色提醒文字（不阻断操作，但需要用户看见）。 */
    public static Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("hint-text");
        label.setWrapText(true);
        return label;
    }

    /** 可复制的只读文本域（等宽字体）。 */
    public static TextArea readonlyArea() {
        TextArea area = new TextArea();
        area.setEditable(false);
        area.setWrapText(true);
        area.getStyleClass().add("mono-area");
        return area;
    }

    // ==================== 布局辅助 ====================

    /** 弹性占位，用于把右侧元素推到行尾。 */
    public static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    /**
     * 垂直弹性占位，用于把元素推到容器中部或底部。
     *
     * <p>与 {@link #spacer()} 是两个不同的东西：{@code spacer()} 设的是
     * {@code HBox.setHgrow}，放进 VBox 里完全不起作用（常见误区）。
     */
    public static Region vspacer() {
        Region region = new Region();
        VBox.setVgrow(region, Priority.ALWAYS);
        return region;
    }

    // ==================== 拖放悬停 ====================

    /**
     * 「拖拽物正悬停在此节点上」的自定义伪类。
     *
     * <p>JavaFX 内置伪类只有 {@code :hover} / {@code :focused} / {@code :pressed} /
     * {@code :disabled} / {@code :selected} 这几种，<b>没有 {@code :drag-over}</b>。
     * 样式表里写 {@code .drop-zone:drag-over} 不会报错，只会永远不生效 ——
     * 这正是「拖放高亮怎么调都不亮」这类问题的成因，因此必须由 Java 侧
     * 用 {@link #setDragOver} 主动切换。
     */
    public static final PseudoClass DRAG_OVER = PseudoClass.getPseudoClass("drag-over");

    /**
     * 切换拖放悬停态。
     *
     * <p>调用时机是三处，缺一处高亮就会卡住不消失：
     * {@code setOnDragOver} 置 true，{@code setOnDragExited} 与
     * {@code setOnDragDropped} 置 false。
     */
    public static void setDragOver(Node node, boolean over) {
        if (node != null) {
            node.pseudoClassStateChanged(DRAG_OVER, over);
        }
    }

    /** 横向排列的按钮行；可继续追加 {@link #spacer()} 把后续按钮推到行尾。 */
    public static HBox buttonRow(Node... buttons) {
        HBox box = new HBox(8, buttons);
        box.setPadding(new Insets(0));
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    // ==================== 示例 chip ====================

    /**
     * 示例条目（chip）：白底描边，左侧自然语言名称 + 右侧等宽表达式。
     *
     * <p>与「次级按钮」的区别在于它承载的是**数据**（一段可直接使用的表达式），
     * 不是一个动作 —— 因此表达式用等宽字并压低色阶，让名称保持视觉主体；
     * 悬停时两者一起转主色，暗示「点一下就能用」。
     *
     * <p>用按钮而不是带点击事件的 {@code HBox}，是为了免费拿到键盘焦点、
     * 回车触发与无障碍角色。Graphic 里的两个 Label 已经是子节点，
     * 因此按钮本身不需要文字。
     *
     * @param label  自然语言名称，如「每个工作日 9 点」
     * @param code   等宽表达式，如 {@code 0 9 * * 1-5}
     * @param action 点击后的动作，可为 null
     */
    public static Button chip(String label, String code, Runnable action) {
        Label name = new Label(label);
        name.getStyleClass().add("chip-label");

        Label expr = new Label(code);
        expr.getStyleClass().add("chip-code");

        HBox content = new HBox(8, name, expr);
        content.setAlignment(Pos.CENTER_LEFT);

        Button button = new Button();
        button.setGraphic(content);
        button.getStyleClass().add("chip");
        button.setAccessibleText(label + " " + code);
        if (action != null) {
            button.setOnAction(e -> action.run());
        }
        return button;
    }

    // ==================== 按钮四级 ====================

    /**
     * 主操作按钮 —— <b>每个功能页只应出现一处</b>主色实底。
     *
     * <p>主操作是「这一屏用户最可能做的那件事」（发送请求、开始执行、导出图片）。
     * 入口类动作（选择文件、粘贴剪贴板）不算主操作 —— 它们只是取输入，
     * 不是把输入提交出去。
     */
    public static Button primaryButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("button-primary");
        button.setDefaultButton(true);
        return button;
    }

    /** 次级按钮（描边），用于清空、切换、复制等可逆操作。 */
    public static Button secondaryButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("button-secondary");
        return button;
    }

    /** 静默按钮（纯文字），用于不抢视线的辅助动作。 */
    public static Button quietButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("button-quiet");
        return button;
    }

    /** 破坏性动作按钮（危险色描边），用于会丢数据的操作。 */
    public static Button dangerButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("button-danger");
        return button;
    }

    /** 创建一个点击后把文本复制到剪贴板的按钮。 */
    public static Button copyButton(javafx.scene.control.TextInputControl source, String tip) {
        Button button = new Button("复制");
        button.setOnAction(e -> copyToClipboard(source.getText()));
        button.setTooltip(new Tooltip(tip));
        return button;
    }

    /** 写系统剪贴板。 */
    public static void copyToClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text == null ? "" : text);
        Clipboard.getSystemClipboard().setContent(content);
    }
}
