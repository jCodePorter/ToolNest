package com.bytefuture.toolnest.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * 功能模块公用的界面构件工厂。
 *
 * <p>把「标题 + 说明 + 卡片 + 复制按钮」这类到处重复的写法收敛到这里，
 * 让新增功能时只关注业务逻辑本身，同时保证全站视觉一致 ——
 * 各功能模块不直接写 {@code setPadding} / {@code setStyle}，一律走这里。
 *
 * <p>样式令牌与规则见 {@code DESIGN.md}，本类只负责组装结构。
 */
public final class UiKit {

    /** 详情页 / 功能页内容区统一左右留白，与内容区页头对齐。 */
    private static final double BODY_PADDING = 26;

    private UiKit() {
    }

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
     * 底部一条 1px 分隔线。页头本身不加深色背景，靠留白与分隔线分层。
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
     * 语义提示条：浅色底 + 左侧 3px 语义色边。
     *
     * <p>用于需要跨行说明的错误与警告；单行短提示直接用
     * {@link #error(String)} / {@link #hint(String)} 即可。
     *
     * @param kind {@code "error"} / {@code "warning"} / {@code "info"}
     */
    public static HBox notice(String kind, String text) {
        Label icon = new Label(switch (kind == null ? "" : kind) {
            case "error" -> "\u2715";     // ✕
            case "warning" -> "\u26A0";   // ⚠
            default -> "\u2139";          // ℹ
        });
        icon.getStyleClass().add(switch (kind == null ? "" : kind) {
            case "error" -> "error-text";
            case "warning" -> "hint-text";
            default -> "caption";
        });

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

    /** 弹性占位，用于把右侧元素推到行尾。 */
    public static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    /** 横向排列的按钮行；可继续追加 {@link #spacer()} 把后续按钮推到行尾。 */
    public static HBox buttonRow(Node... buttons) {
        HBox box = new HBox(8, buttons);
        box.setPadding(new Insets(0));
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /**
     * 主操作按钮 —— 每个功能页只应出现一处主色实底按钮。
     *
     * <p>主操作是「这一屏用户最可能做的那件事」（发送请求、开始执行、生成），
     * 次要动作用普通按钮，破坏性动作保留文字并单独配色。
     */
    public static Button primaryButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("button-primary");
        button.setDefaultButton(true);
        return button;
    }

    /** 次级按钮（描边样式），用于清空、切换、复制等可逆操作。 */
    public static Button secondaryButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("flat");
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

    /** 结果展示行：左边说明，右边可复制的值。 */
    public static HBox resultRow(String captionText, Node valueNode, String copyableText) {
        Label caption = new Label(captionText);
        caption.getStyleClass().add("field-label");
        caption.setMinWidth(110);
        caption.setPrefWidth(110);

        HBox row = new HBox(10, caption, valueNode, spacer());
        if (copyableText != null) {
            Button copy = new Button("复制");
            copy.getStyleClass().add("flat");
            copy.setOnAction(e -> copyToClipboard(copyableText));
            row.getChildren().add(copy);
        }
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }
}
