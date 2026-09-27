package com.bytefuture.toolnest.ui;

import com.bytefuture.toolnest.core.ToolModule;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * 宫格中的单张功能卡片。
 *
 * <p>内部节奏自上而下，用固定高度区间保证整墙卡片底边对齐：
 * <pre>
 *  [图标底托 40×40]        固定高 40
 *  （间距 14）
 *  功能名称                固定 2 行位置，避免标题长短不一导致后续错位
 *  副标题                  固定 3 行位置
 *  （弹性空间）
 *  打开 →                  悬停才出现，不参与固定高度
 * </pre>
 *
 * <p>卡片尺寸固定、内容用 {@code setMinHeight} 预留行数，是刻意的选择：
 * 宫格的价值在于横向扫视，如果每张卡片高度随文案浮动，整页就会失去对齐基准。
 * 代价是长文案会被裁切，因此模块的 {@code summary} 应控制在一到两行内。
 *
 * <p>悬停只改变描边与阴影，不改变尺寸 —— 鼠标移过时卡片不会跳动。
 */
public class ToolCard extends VBox {

    private static final double CARD_WIDTH = 246;
    private static final double CARD_HEIGHT = 214;

    /** 卡片内容左右内边距，文字最大宽度据此计算。 */
    private static final double CONTENT_PADDING = 18;

    /** 名称与副标题的预留高度，按各自字号 × 行数 + 行距估算。 */
    private static final double NAME_MIN_HEIGHT = 42;
    private static final double SUMMARY_MIN_HEIGHT = 54;

    public ToolCard(ToolModule module, Consumer<ToolModule> onOpen) {
        getStyleClass().add("tool-card");
        setPrefSize(CARD_WIDTH, CARD_HEIGHT);
        setMinSize(CARD_WIDTH, CARD_HEIGHT);
        setMaxSize(CARD_WIDTH, CARD_HEIGHT);
        setSpacing(0);
        setAlignment(Pos.TOP_LEFT);

        double textWidth = CARD_WIDTH - CONTENT_PADDING * 2;

        // ---------- 图标底托 ----------
        // Unicode 符号在不同字体下的视觉重量差异很大，统一放进主色浅底方块，
        // 让整墙卡片的图标节奏一致（也不用给每张卡换一种颜色）
        Label iconLabel = new Label(module.icon());
        iconLabel.getStyleClass().add("tool-card-icon");

        StackPane iconBox = new StackPane(iconLabel);
        iconBox.getStyleClass().add("tool-card-icon-box");

        // ---------- 名称 ----------
        Label nameLabel = new Label(module.displayName());
        nameLabel.getStyleClass().add("tool-card-name");
        nameLabel.setWrapText(true);
        nameLabel.setMaxWidth(textWidth);
        nameLabel.setMinHeight(NAME_MIN_HEIGHT);
        nameLabel.setAlignment(Pos.TOP_LEFT);

        // ---------- 副标题 ----------
        Label summaryLabel = new Label(module.summary());
        summaryLabel.getStyleClass().add("tool-card-summary");
        summaryLabel.setWrapText(true);
        summaryLabel.setMaxWidth(textWidth);
        summaryLabel.setMinHeight(SUMMARY_MIN_HEIGHT);
        summaryLabel.setAlignment(Pos.TOP_LEFT);

        // ---------- 弹性空间 + 悬停提示 ----------
        Region glue = new Region();
        VBox.setVgrow(glue, Priority.ALWAYS);

        Label hint = new Label("打开 →");
        hint.getStyleClass().add("tool-card-hint");

        HBox footer = new HBox(hint);
        footer.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(iconBox, spacer(14), nameLabel, spacer(6),
                summaryLabel, glue, footer);

        // 整卡可点，不再额外放按钮 —— 卡片本身就是入口
        setOnMouseClicked(e -> onOpen.accept(module));
        setCursor(javafx.scene.Cursor.HAND);
        setAccessibleText(module.displayName() + "：" + module.summary());
    }

    /** 垂直间距占位。 */
    private static Region spacer(double height) {
        Region region = new Region();
        region.setMinHeight(height);
        region.setPrefHeight(height);
        return region;
    }
}
