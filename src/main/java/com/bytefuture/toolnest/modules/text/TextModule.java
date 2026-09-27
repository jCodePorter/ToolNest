package com.bytefuture.toolnest.modules.text;

import com.bytefuture.toolnest.core.ModuleCategory;
import com.bytefuture.toolnest.core.ToolModule;
import com.bytefuture.toolnest.ui.UiKit;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * 文本处理工具。
 *
 * <p>定位是「一批零散的文本清洗动作」：去重、排序、去空行、大小写、加行号、
 * 正则替换、统计。这些动作单独看都很小，但日常处理日志 / 名单 / 数据时
 * 反复需要，值得有一个统一入口。
 *
 * <p>交互上刻意区分两类动作：
 * <ul>
 *   <li><b>就地变换</b>（去重、排序、大小写…）：直接改写右侧结果区</li>
 *   <li><b>生成类</b>（加行号、正则替换）：需要参数，走各自的输入控件</li>
 * </ul>
 * 所有变换都是「左侧原文 → 右侧结果」，原文永不被修改，便于反复尝试不同组合。
 *
 * <p>零第三方依赖，全部逻辑在 {@link TextOps} 中，本类只负责组装界面与反馈。
 */
public class TextModule implements ToolModule {

    @Override
    public String id() {
        return "text-tools";
    }

    @Override
    public String displayName() {
        return "文本处理";
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.TEXT;
    }

    @Override
    public String summary() {
        return "去重、排序、去空行、大小写转换、加行号、正则替换与文本统计";
    }

    @Override
    public String description() {
        return "左侧粘贴原文，右侧得到处理结果；原文本始终不被修改，可反复尝试不同组合";
    }

    @Override
    public String icon() {
        return "\u270E";
    }

    @Override
    public String keywords() {
        return "text 文本 去重 排序 去空行 大小写 行号 正则 替换 统计 字数 行数";
    }

    @Override
    public Node buildContent() {
        VBox root = UiKit.page("文本处理", "左侧原文，右侧结果；原文不被修改。");

        TextArea input = new TextArea();
        input.setPromptText("在此粘贴需要处理的文本…");
        input.getStyleClass().add("mono-area");
        input.setWrapText(false);
        VBox.setVgrow(input, Priority.ALWAYS);

        TextArea output = new TextArea();
        output.setPromptText("处理结果会显示在这里");
        output.getStyleClass().add("mono-area");
        output.setWrapText(false);
        output.setEditable(false);
        VBox.setVgrow(output, Priority.ALWAYS);

        Label statLabel = UiKit.caption("行数 0");
        // 操作反馈行：始终占位（不用 notice 条的显隐，避免每次变换都引起布局跳动）
        Label notice = UiKit.caption("");
        notice.getStyleClass().add("hint-text");

        // ---------- 排序选项 ----------
        ToggleGroup orderGroup = new ToggleGroup();
        RadioButton ascRadio = new RadioButton("升序");
        RadioButton descRadio = new RadioButton("降序");
        ascRadio.setToggleGroup(orderGroup);
        descRadio.setToggleGroup(orderGroup);
        ascRadio.setSelected(true);

        CheckBox caseSensitiveBox = new CheckBox("区分大小写");
        CheckBox naturalBox = new CheckBox("按数字大小");
        naturalBox.setSelected(true);
        naturalBox.setTooltip(new Tooltip("编号行按数值排序，例如 2 排在 10 之前"));

        HBox sortOptions = new HBox(12, ascRadio, descRadio, caseSensitiveBox, naturalBox);
        sortOptions.setAlignment(Pos.CENTER_LEFT);

        // ---------- 行号选项 ----------
        Spinner<Integer> startSpinner = new Spinner<>(0, 999999, 1);
        startSpinner.setPrefWidth(96);
        startSpinner.setEditable(true);

        ComboBox<String> sepBox = new ComboBox<>();
        sepBox.getItems().addAll("空格", "点号 .", "右括号 )", "制表符", "冒号 :");
        sepBox.getSelectionModel().selectFirst();
        sepBox.setPrefWidth(110);

        HBox numberOptions = new HBox(12,
                new Label("起始"), startSpinner,
                new Label("分隔"), sepBox);
        numberOptions.setAlignment(Pos.CENTER_LEFT);
        numberOptions.getStyleClass().add("field-row");

        // ---------- 正则替换 ----------
        TextField patternField = new TextField();
        patternField.setPromptText("正则表达式，例如 \\d+");
        patternField.getStyleClass().add("mono-area");

        TextField replacementField = new TextField();
        replacementField.setPromptText("替换为，支持 $1 分组引用");
        replacementField.getStyleClass().add("mono-area");

        CheckBox regexModeBox = new CheckBox("按正则处理");
        regexModeBox.setSelected(true);
        regexModeBox.setTooltip(new Tooltip("关闭时按纯文本字面量替换，不解析正则"));

        VBox replaceBox = new VBox(8,
                UiKit.field("查找", patternField),
                UiKit.field("替换为", replacementField),
                new HBox(12, regexModeBox) {{
                    setAlignment(Pos.CENTER_LEFT);
                }});

        // ==================== 动作接线 ====================

        Runnable updateStats = () -> {
            String src = input.getText() == null ? "" : input.getText();
            statLabel.setText(TextOps.stats(src).describe());
        };
        input.textProperty().addListener((o, old, now) -> updateStats.run());

        // 把原文放入结果区作为起点（用户可在此基础上连续变换）
        Button copyToOutput = UiKit.primaryButton("原文 → 结果");
        copyToOutput.setOnAction(e -> {
            output.setText(input.getText());
            showNotice(notice, "已把原文复制到结果区，可继续叠加变换");
        });

        Button distinctBtn = UiKit.secondaryButton("行去重");
        distinctBtn.setOnAction(e -> {
            output.setText(TextOps.distinctLines(sourceOf(output, input)));
            reportDedup(notice, sourceOf(output, input), output.getText());
        });

        Button sortBtn = UiKit.secondaryButton("行排序");
        sortBtn.setOnAction(e -> {
            output.setText(TextOps.sortLines(sourceOf(output, input),
                    ascRadio.isSelected(), caseSensitiveBox.isSelected(),
                    naturalBox.isSelected()));
            showNotice(notice, "已排序：" + (ascRadio.isSelected() ? "升序" : "降序"));
        });

        Button removeEmptyBtn = UiKit.secondaryButton("去空行");
        removeEmptyBtn.setOnAction(e -> {
            output.setText(TextOps.removeEmptyLines(sourceOf(output, input)));
            showNotice(notice, "已删除空行");
        });

        Button trimEdgesBtn = UiKit.secondaryButton("去首尾空行");
        trimEdgesBtn.setOnAction(e -> {
            output.setText(TextOps.trimBlankEdges(sourceOf(output, input)));
            showNotice(notice, "已去除首尾空白行");
        });

        Button upperBtn = UiKit.secondaryButton("转大写");
        upperBtn.setOnAction(e -> {
            output.setText(sourceOf(output, input).toUpperCase());
            showNotice(notice, "已转为大写");
        });

        Button lowerBtn = UiKit.secondaryButton("转小写");
        lowerBtn.setOnAction(e -> {
            output.setText(sourceOf(output, input).toLowerCase());
            showNotice(notice, "已转为小写");
        });

        Button stripBtn = UiKit.secondaryButton("去行首尾空白");
        stripBtn.setOnAction(e -> {
            output.setText(TextOps.stripTrailing(
                    TextOps.stripLeading(sourceOf(output, input))));
            showNotice(notice, "已去除每行首尾空白");
        });

        Button collapseBtn = UiKit.secondaryButton("压缩连续空格");
        collapseBtn.setOnAction(e -> {
            output.setText(TextOps.collapseSpaces(sourceOf(output, input)));
            showNotice(notice, "连续的空白已压缩为单个空格");
        });

        Button numberBtn = UiKit.secondaryButton("加行号");
        numberBtn.setOnAction(e -> {
            int start = startSpinner.getValue() == null ? 1 : startSpinner.getValue();
            output.setText(TextOps.addLineNumbers(sourceOf(output, input), start,
                    separatorOf(sepBox)));
            showNotice(notice, "已添加行号，起始 " + start);
        });

        Button replaceBtn = UiKit.secondaryButton("执行替换");
        replaceBtn.setOnAction(e -> {
            String pattern = patternField.getText() == null ? "" : patternField.getText();
            if (pattern.isEmpty()) {
                showNotice(notice, "请先填写「查找」内容");
                return;
            }
            String target = sourceOf(output, input);
            String replacement = replacementField.getText() == null
                    ? "" : replacementField.getText();
            try {
                String result = regexModeBox.isSelected()
                        ? TextOps.replaceAll(target, pattern, replacement)
                        : target.replace(pattern, replacement);
                output.setText(result);
                showNotice(notice, "替换完成");
            } catch (Exception ex) {
                showNotice(notice, "正则表达式无效：" + ex.getMessage());
            }
        });

        // ---------- 工具栏 ----------
        // 用 FlowPane 而非 HBox：按钮较多，窄窗口下需要自动折行而不是被挤出可视区
        FlowPane toolbar = new FlowPane(8, 8, copyToOutput, distinctBtn, sortBtn,
                removeEmptyBtn, trimEdgesBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        FlowPane caseRow = new FlowPane(8, 8, upperBtn, lowerBtn, stripBtn,
                collapseBtn, numberBtn);
        caseRow.setAlignment(Pos.CENTER_LEFT);

        // ---------- 右侧结果区 ----------
        VBox rightBox = new VBox(8,
                fieldLabel("处理结果"),
                output,
                UiKit.section("正则替换", "关闭「按正则处理」即按纯文本字面量替换", replaceBox));

        // 结果区操作行：直接持有按钮引用，不靠下标取 ——
        // HBox 里混入了 spacer 等非按钮节点，按下标取极易取错且改布局就会失效
        Button useResultBtn = UiKit.secondaryButton("用结果替换原文");
        useResultBtn.setOnAction(e -> {
            input.setText(output.getText());
            showNotice(notice, "已用结果替换原文");
        });

        Button clearResultBtn = UiKit.secondaryButton("清空");
        clearResultBtn.setOnAction(e -> {
            input.clear();
            output.clear();
            hideNotice(notice);
        });

        HBox resultActions = new HBox(8,
                useResultBtn,
                UiKit.copyButton(output, "复制结果到剪贴板"),
                UiKit.spacer(),
                clearResultBtn);
        resultActions.setAlignment(Pos.CENTER_LEFT);

        rightBox.getChildren().add(resultActions);
        VBox.setVgrow(output, Priority.ALWAYS);

        VBox leftBox = new VBox(8, fieldLabel("原文"), input, statLabel);
        VBox.setVgrow(input, Priority.ALWAYS);

        SplitPane split = new SplitPane(leftBox, rightBox);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.5);
        VBox.setVgrow(split, Priority.ALWAYS);

        VBox sortCard = UiKit.section("排序选项", null, sortOptions);
        VBox numberCard = UiKit.section("行号选项", null, numberOptions);

        HBox optionsRow = new HBox(16, sortCard, numberCard);
        HBox.setHgrow(sortCard, Priority.ALWAYS);
        HBox.setHgrow(numberCard, Priority.ALWAYS);

        root.getChildren().addAll(toolbar, caseRow, optionsRow, split, notice);
        updateStats.run();
        return root;
    }

    /**
     * 取变换的输入源。
     *
     * <p>规则：结果区非空则以结果区为源，否则退回到原文。
     * 这样才能「连续叠加变换」——每一步都作用在上一步的结果上。
     */
    private String sourceOf(TextArea output, TextArea input) {
        String out = output.getText();
        if (out != null && !out.isEmpty()) {
            return out;
        }
        return input.getText() == null ? "" : input.getText();
    }

    private String separatorOf(ComboBox<String> box) {
        return switch (box.getSelectionModel().getSelectedIndex()) {
            case 1 -> ". ";
            case 2 -> ") ";
            case 3 -> "\t";
            case 4 -> ": ";
            default -> "  ";
        };
    }

    /** 去重后给出「删掉了多少行」，便于确认效果。 */
    private void reportDedup(Label notice, String before, String after) {
        int b = TextOps.lineCount(before);
        int a = TextOps.lineCount(after);
        int removed = Math.max(0, b - a);
        showNotice(notice, removed == 0
                ? "没有重复行"
                : "已去除 " + removed + " 行重复内容");
    }

    private Label fieldLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("field-label");
        return label;
    }

    private void showNotice(Label label, String text) {
        label.setText(text);
    }

    private void hideNotice(Label label) {
        label.setText("");
    }
}
