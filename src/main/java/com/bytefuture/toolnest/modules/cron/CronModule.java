package com.bytefuture.toolnest.modules.cron;

import com.bytefuture.toolnest.core.ModuleCategory;
import com.bytefuture.toolnest.core.ToolModule;
import com.bytefuture.toolnest.ui.UiKit;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Cron 表达式解析工具。
 *
 * <p>解决的是「拿到一个 cron 表达式，不确定它到底什么时候跑」这个高频困惑：
 * 实时给出中文逐字段说明 + 未来若干次执行时间，表达式有误时明确指出问题字段。
 *
 * <p>界面刻意保持单栏：表达式输入 → 解析结果，信息密度集中在「下次执行时间」上。
 * 预览条数可调（因为有的表达式一天触发多次，看几条才能确认节奏）。
 */
public class CronModule implements ToolModule {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FMT_WEEK =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public String id() {
        return "cron";
    }

    @Override
    public String displayName() {
        return "Cron 表达式解析";
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.CONVERT;
    }

    @Override
    public String summary() {
        return "解析 cron 表达式并预览未来执行时间，支持 5 段与含秒的 6 段写法";
    }

    @Override
    public String description() {
        return "支持 * / 区间 / 枚举 / 步长 / 别名，给出中文说明与最近若干次执行时间";
    }

    @Override
    public String icon() {
        return "\u23F2";
    }

    @Override
    public String keywords() {
        return "cron crontab 定时 表达式 计划任务 调度 quartz 下次执行";
    }

    @Override
    public Node buildContent() {
        VBox root = UiKit.page("Cron 表达式解析",
                "解析 cron 表达式，预览未来执行时间。");

        // ---------- 表达式输入 ----------
        TextField exprField = new TextField();
        exprField.setPromptText("例如 0 9 * * 1-5  或  0 0 12 * * *");
        exprField.getStyleClass().add("mono-area");

        Spinner<Integer> countSpinner = new Spinner<>(1, 50, 10);
        countSpinner.setPrefWidth(96);
        countSpinner.setEditable(true);

        Button parseBtn = UiKit.primaryButton("解析");
        Button clearBtn = UiKit.secondaryButton("清空");

        HBox inputRow = new HBox(10, exprField, parseBtn, clearBtn);
        inputRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(exprField, Priority.ALWAYS);

        HBox countRow = new HBox(10, new Label("预览条数"), countSpinner,
                UiKit.spacer(),
                UiKit.caption("6 段格式：秒 分 时 日 月 周　·　5 段格式：分 时 日 月 周"));
        countRow.setAlignment(Pos.CENTER_LEFT);

        // ---------- 结果区 ----------
        Label formatLabel = new Label("-");
        formatLabel.getStyleClass().add("result-value");

        TextArea describeArea = UiKit.readonlyArea();
        describeArea.setPrefRowCount(7);
        describeArea.getStyleClass().add("mono-area");
        describeArea.setPromptText("字段说明会显示在这里");

        TextArea nextArea = UiKit.readonlyArea();
        nextArea.setPrefRowCount(11);
        nextArea.getStyleClass().add("mono-area");
        nextArea.setPromptText("未来执行时间会显示在这里");
        VBox.setVgrow(nextArea, Priority.ALWAYS);

        Label errorLabel = UiKit.error("");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        Label summaryLabel = UiKit.caption("");

        // ---------- 解析动作 ----------
        Runnable parse = () -> {
            String expr = exprField.getText() == null ? "" : exprField.getText().trim();
            hideError(errorLabel);
            if (expr.isEmpty()) {
                formatLabel.setText("-");
                describeArea.clear();
                nextArea.clear();
                summaryLabel.setText("");
                return;
            }
            try {
                CronExpression cron = CronExpression.parse(expr);
                formatLabel.setText(cron.hasSeconds() ? "6 段（含秒）" : "5 段");
                describeArea.setText(cron.describe());

                int count = countSpinner.getValue() == null ? 10 : countSpinner.getValue();
                LocalDateTime now = LocalDateTime.now();
                List<LocalDateTime> nextList = cron.nextList(now, count);

                if (nextList.isEmpty()) {
                    nextArea.setText("该表达式在当前时间之后永不触发。\n"
                            + "常见原因：指定的日期不存在（如 2 月 30 日），"
                            + "或日月组合无法同时满足。");
                    summaryLabel.setText("永不触发");
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < nextList.size(); i++) {
                        LocalDateTime t = nextList.get(i);
                        sb.append(String.format("%2d. %s  %s", i + 1,
                                t.format(FMT), weekName(t)));
                        // 与上一次的间隔，帮助确认触发节奏
                        if (i > 0) {
                            long minutes = java.time.Duration.between(
                                    nextList.get(i - 1), t).toMinutes();
                            sb.append("   (+").append(intervalText(minutes)).append(")");
                        }
                        sb.append('\n');
                    }
                    nextArea.setText(sb.toString());
                    summaryLabel.setText("已解析，以下为未来 " + nextList.size() + " 次执行时间");
                }
            } catch (IllegalArgumentException ex) {
                formatLabel.setText("-");
                describeArea.clear();
                nextArea.clear();
                summaryLabel.setText("");
                showError(errorLabel, ex.getMessage());
            }
        };

        parseBtn.setOnAction(e -> parse.run());
        exprField.setOnAction(e -> parse.run());
        exprField.textProperty().addListener((o, old, now) -> parse.run());
        countSpinner.valueProperty().addListener((o, old, now) -> parse.run());
        clearBtn.setOnAction(e -> {
            exprField.clear();
            hideError(errorLabel);
        });

        // ---------- 常用示例 ----------
        VBox examples = new VBox(2);
        String[][] samples = {
                {"每分钟", "* * * * *"},
                {"每 5 分钟", "*/5 * * * *"},
                {"每小时整点", "0 * * * *"},
                {"每天零点", "0 0 * * *"},
                {"每个工作日 9 点", "0 9 * * 1-5"},
                {"每周一零点", "0 0 * * 1"},
                {"每月 1 号零点", "0 0 1 * *"},
                {"每年 1 月 1 日", "0 0 1 1 *"},
                {"每 30 秒（6 段）", "*/30 * * * * *"},
        };
        FlowPane chips = new FlowPane(8, 8);
        for (String[] sample : samples) {
            Button chip = UiKit.secondaryButton(sample[0] + "  " + sample[1]);
            chip.setOnAction(e -> {
                exprField.setText(sample[1]);
                parse.run();
            });
            chips.getChildren().add(chip);
        }

        VBox exampleCard = UiKit.section("常用示例", "点击直接填入", chips);

        VBox resultCard = UiKit.section("解析结果", null,
                UiKit.resultRow("格式", formatLabel, null),
                UiKit.field("字段说明", describeArea),
                UiKit.field("未来执行时间", nextArea),
                summaryLabel);

        root.getChildren().addAll(
                UiKit.card(12, UiKit.field("表达式", inputRow), countRow),
                exampleCard,
                resultCard,
                errorLabel);

        // 默认填一个常见的例子，打开即有内容可看
        exprField.setText("0 9 * * 1-5");
        parse.run();
        return root;
    }

    private String weekName(LocalDateTime t) {
        String[] names = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return names[t.getDayOfWeek().getValue() - 1];
    }

    /** 把分钟数转成人类可读的间隔描述。 */
    private String intervalText(long minutes) {
        if (minutes < 60) {
            return minutes + " 分钟";
        }
        if (minutes < 60 * 24) {
            long h = minutes / 60;
            long m = minutes % 60;
            return m == 0 ? h + " 小时" : h + " 小时 " + m + " 分";
        }
        long d = minutes / (60 * 24);
        long h = (minutes % (60 * 24)) / 60;
        return h == 0 ? d + " 天" : d + " 天 " + h + " 小时";
    }

    private void showError(Label label, String message) {
        label.setText(message);
        label.setVisible(true);
        label.setManaged(true);
    }

    private void hideError(Label label) {
        label.setText("");
        label.setVisible(false);
        label.setManaged(false);
    }
}
