package com.bytefuture.toolnest.modules.uuid;

import com.bytefuture.toolnest.core.ModuleCategory;
import com.bytefuture.toolnest.core.ToolModule;
import com.bytefuture.toolnest.ui.UiKit;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.UUID;

/**
 * UUID 生成器 —— 最简模块示范。
 *
 * <p>演示要点：一个功能模块的完整骨架只需实现 {@link ToolModule} 的
 * {@code id / displayName / category / buildContent} 四个方法。
 */
public class UuidModule implements ToolModule {

    @Override
    public String id() {
        return "uuid";
    }

    @Override
    public String displayName() {
        return "UUID 生成";
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.GENERATE;
    }

    @Override
    public String summary() {
        return "批量生成随机 UUID（版本 4）";
    }

    @Override
    public String description() {
        return "可选择大小写与是否保留连字符，一次最多生成 100 个";
    }

    @Override
    public String icon() {
        return "\u2606";
    }

    @Override
    public String keywords() {
        return "uuid guid 唯一标识 随机 生成";
    }

    @Override
    public Node buildContent() {
        VBox root = UiKit.page("UUID 生成", "批量生成随机 UUID（版本 4）。");

        TextArea area = UiKit.readonlyArea();
        area.setPrefRowCount(14);
        area.setPromptText("点击「生成」后结果会出现在这里");
        VBox.setVgrow(area, Priority.ALWAYS);

        Spinner<Integer> countSpinner = new Spinner<>(1, 100, 5);
        countSpinner.setPrefWidth(90);
        countSpinner.setEditable(true);

        ToggleGroup caseGroup = new ToggleGroup();
        RadioButton upperRadio = new RadioButton("大写");
        RadioButton lowerRadio = new RadioButton("小写");
        upperRadio.setToggleGroup(caseGroup);
        lowerRadio.setToggleGroup(caseGroup);
        lowerRadio.setSelected(true);
        HBox caseBox = new HBox(12, upperRadio, lowerRadio);
        caseBox.setAlignment(Pos.CENTER_LEFT);

        ToggleGroup dashGroup = new ToggleGroup();
        RadioButton withDash = new RadioButton("保留连字符");
        RadioButton noDash = new RadioButton("去掉连字符");
        withDash.setToggleGroup(dashGroup);
        noDash.setToggleGroup(dashGroup);
        withDash.setSelected(true);
        HBox dashBox = new HBox(12, withDash, noDash);
        dashBox.setAlignment(Pos.CENTER_LEFT);

        Button generateBtn = UiKit.primaryButton("生成");
        Button copyAllBtn = UiKit.secondaryButton("复制全部");

        Runnable generate = () -> {
            int count = countSpinner.getValue() == null ? 1 : countSpinner.getValue();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < count; i++) {
                String uuid = UUID.randomUUID().toString();
                if (noDash.isSelected()) {
                    uuid = uuid.replace("-", "");
                }
                if (upperRadio.isSelected()) {
                    uuid = uuid.toUpperCase();
                }
                sb.append(uuid);
                if (i < count - 1) {
                    sb.append(System.lineSeparator());
                }
            }
            area.setText(sb.toString());
        };

        generateBtn.setOnAction(e -> generate.run());
        copyAllBtn.setOnAction(e -> UiKit.copyToClipboard(area.getText()));

        VBox configCard = UiKit.card(12,
                UiKit.field("生成数量", countSpinner),
                UiKit.field("大小写", caseBox),
                UiKit.field("连字符", dashBox),
                UiKit.buttonRow(generateBtn, copyAllBtn, UiKit.spacer(),
                        UiKit.caption("生成结果可直接全选复制"))
        );

        root.getChildren().addAll(configCard, area);
        generate.run();
        return root;
    }
}
