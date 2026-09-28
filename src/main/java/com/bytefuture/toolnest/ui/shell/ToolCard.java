package com.bytefuture.toolnest.ui.shell;

import com.bytefuture.toolnest.ui.spi.ToolModule;
import com.bytefuture.toolnest.ui.components.Icons;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * 宫格中的单张功能卡片。
 *
 * <h2>尺寸与布局</h2>
 * <p>252 x 106 的横向紧凑型：图标底托在左，标题与说明在右。
 * 旧版是 246 x 214 的纵向布局（图标居中置顶、文字在下），但内容只占
 * 卡片高度的一半多一点，底部常年有近 90px 死白，一屏也只放得下 2.7 行。
 *
 * <p>改成横向后有三处连带收益：死白消失；扫视路径从「上下跳」变成
 * 「一条横线」（图标 → 标题 → 说明）；同一屏可见卡片数从 8 张提到 18 张左右。
 *
 * <h2>固定尺寸的理由</h2>
 * <p>卡片尺寸固定、标题限定一行、说明文字预留两行高度，是刻意的取舍：
 * 宫格的价值在于横向扫视，如果每张卡片高度随文案浮动，整页就失去对齐基准。
 * 代价是超长标题与说明会被截断，因此模块的 {@code displayName()} /
 * {@code summary()} 应控制在一行 / 两行内，完整标题由 tooltip 兜底。
 *
 * <h2>垂直居中</h2>
 * <p>卡片高度按「两行说明」定死（上下内边距 + 标题 + 间距 + 两行说明），
 * 内容整体垂直居中。旧版顶端对齐时，说明只有一行的卡片底部会多出十几像素
 * 空档，视觉上像是没画完。
 *
 * <h2>悬停</h2>
 * <p>只改变描边、阴影与图标底托的填充，<b>尺寸恒定</b> —— 鼠标移过时
 * 卡片不会跳动。图标底托填满主色是「这张卡已经就绪」的瞬时反馈，
 * 它取代了旧版底部那行静止时完全不可见的「打开 →」。
 *
 * <p>刻意不在卡片右侧单开一列放箭头：那会吃掉约 26px 文字宽度，
 * 在 252px 宽的卡片里等于每行少排两个字。
 */
public class ToolCard extends HBox {

    /** 卡片宽高，与 app.css 的 .tool-card 尺寸约束严格一致。 */
    private static final double CARD_WIDTH = 252;
    private static final double CARD_HEIGHT = 106;

    /** 卡片内边距，与 .tool-card 的 -fx-padding 保持一致。 */
    private static final double PADDING = 16;

    /** 图标底托边长，与 .tool-card-icon-box 一致。 */
    private static final double ICON_BOX = 38;

    /** 图标底托尺寸与图标本身尺寸的差值，用来算图标的视觉大小。 */
    private static final double ICON_SIZE = 19;

    /** 图标底托与文字列之间的横向间距。 */
    private static final double GAP = 14;

    /** 标题限定一行。13.5px 字号的行盒高度。 */
    private static final double TITLE_HEIGHT = 21;

    /** 说明文字预留两行。12px 字号配 1.6 行高时，单行约 19.2px。 */
    private static final double SUMMARY_LINES_HEIGHT = 39;

    public ToolCard(ToolModule module, Consumer<ToolModule> onOpen) {
        getStyleClass().add("tool-card");
        setPrefSize(CARD_WIDTH, CARD_HEIGHT);
        setMinSize(CARD_WIDTH, CARD_HEIGHT);
        setMaxSize(CARD_WIDTH, CARD_HEIGHT);
        setSpacing(GAP);
        setAlignment(Pos.CENTER_LEFT);

        // 文字列可用的净宽度：卡片宽 - 两侧内边距 - 图标底托 - 间距
        double textWidth = CARD_WIDTH - PADDING * 2 - ICON_BOX - GAP;

        // ---------- 图标底托 ----------
        StackPane iconBox = new StackPane(Icons.forModule(module, ICON_SIZE, "icon-accent"));
        iconBox.getStyleClass().add("tool-card-icon-box");
        iconBox.setAlignment(Pos.CENTER);
        iconBox.setMouseTransparent(true);

        // ---------- 标题 ----------
        // 限定一行：标题一旦折行，同一行卡片的说明基线就会错开
        Label nameLabel = new Label(module.displayName());
        nameLabel.getStyleClass().add("tool-card-name");
        nameLabel.setWrapText(false);
        nameLabel.setMaxWidth(textWidth);
        nameLabel.setMinHeight(TITLE_HEIGHT);
        nameLabel.setMaxHeight(TITLE_HEIGHT);
        nameLabel.setAlignment(Pos.CENTER_LEFT);
        nameLabel.setTooltip(new Tooltip(module.displayName()));

        // ---------- 说明 ----------
        // 预留两行高度而不是按内容自适应：同一行卡片的文字基线才会齐平。
        // 同时把文案按两行宽度预算截断 —— 只给固定高度是不够的，
        // 超出的第三行会被 Label 画出上半截，看起来像渲染坏了。
        Label summaryLabel = new Label(fitTwoLines(module.summary(), textWidth));
        summaryLabel.getStyleClass().add("tool-card-summary");
        summaryLabel.setWrapText(true);
        summaryLabel.setMaxWidth(textWidth);
        summaryLabel.setMinHeight(SUMMARY_LINES_HEIGHT);
        summaryLabel.setMaxHeight(SUMMARY_LINES_HEIGHT);
        summaryLabel.setAlignment(Pos.TOP_LEFT);
        summaryLabel.setTooltip(new Tooltip(module.summary()));

        VBox textColumn = new VBox(4, nameLabel, summaryLabel);
        textColumn.setAlignment(Pos.CENTER_LEFT);
        textColumn.setMouseTransparent(true);

        getChildren().addAll(iconBox, textColumn);

        // 整卡可点，不再额外放按钮 —— 卡片本身就是入口
        setOnMouseClicked(e -> onOpen.accept(module));
        setCursor(javafx.scene.Cursor.HAND);
        setAccessibleText(module.displayName() + "：" + module.summary());
    }

    // ==================== 两行预算 ====================

    /** 全角字符（中日韩文字与全角标点）的估算宽度，12px 字号下约等于字号本身。 */
    private static final int FULL_WIDTH = 12;

    /** 其余字符（拉丁字母、数字、半角标点）的估算宽度。 */
    private static final int HALF_WIDTH = 7;

    /** 省略号占位，截断时给它留出一格。 */
    private static final String ELLIPSIS = "\u2026";

    /**
     * 把说明文字压到两行以内。
     *
     * <p>卡片高度固定，说明区只留两行；固定高度并不会让 Label 自动收敛 ——
     * 第三行仍然会被画出来上半截，看起来像渲染坏了。所以这里按可用宽度
     * 估算两行能排下的字符数，超出部分截断加省略号；完整文案始终挂在
     * tooltip 上，信息不会丢。
     *
     * <p>用估算而不是精确排版：此处尚未应用 CSS，拿不到真实字体度量；
     * 估算宁可保守一点（少排一个字），也不能让文字溢出行高。
     */
    private static String fitTwoLines(String text, double textWidth) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int budget = (int) (textWidth * 2);
        if (visualWidth(text) <= budget) {
            return text;
        }
        StringBuilder kept = new StringBuilder();
        int used = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int width = charWidth(c);
            // 给省略号留一格，否则截断后会多挤出半行
            if (used + width > budget - FULL_WIDTH) {
                break;
            }
            kept.append(c);
            used += width;
        }
        return kept.toString().stripTrailing() + ELLIPSIS;
    }

    private static int visualWidth(String text) {
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            total += charWidth(text.charAt(i));
        }
        return total;
    }

    private static int charWidth(char c) {
        // 0x2E80 起为中日韩文字与全角标点区
        return c > 0x2E80 ? FULL_WIDTH : HALF_WIDTH;
    }
}
