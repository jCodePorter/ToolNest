package com.bytefuture.toolnest.features.cron;

import com.bytefuture.toolnest.core.ModuleCategory;
import com.bytefuture.toolnest.ui.spi.ToolModule;
import com.bytefuture.toolnest.ui.components.UiKit;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Cron 表达式解析工具。
 *
 * <p>解决的是「拿到一个 cron 表达式，不确定它到底什么时候跑」这个高频困惑：
 * 实时给出中文逐字段说明 + 未来若干次执行时间，表达式有误时明确指出问题字段。
 *
 * <p>界面刻意保持单栏：表达式输入 → 解析结果，信息密度集中在「下次执行时间」上。
 * 预览条数可调（因为有的表达式一天触发多次，看几条才能确认节奏）。
 *
 * <h2>两处「文本块 → 结构化数据」的改造</h2>
 * <p>「字段说明」与「未来执行时间」原先各是一个只读 {@code TextArea}，
 * 也就是把结构化数据先拼成字符串、再交给一个文本控件去显示。这带来两个问题：
 * 说明区只有 6 行却自带滚动条（像一块能滚的代码），执行时间挤在等宽文本里、
 * 每行的「星期」「距现在」只能靠空格对齐，读不出列。
 *
 * <p>现在两者都恢复成它们本来的形态：字段说明是一组**键值对**
 * （{@link UiKit#resultRow}，标签定宽对齐），执行时间是**四列表格**
 * （序号 / 时间 / 星期 / 距现在）。数据源分别是
 * {@link CronExpression#describeRows()} 与 {@link #nextRows}，
 * 而 {@link CronExpression#describe()} 与 {@link #buildNextText} 退化为
 * 同一份数据的文本投影 —— 供复制、控制台与探针使用，不再有独立的拼装逻辑。
 */
public class CronModule implements ToolModule {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 无结果时的占位符（破折号），与 UiKit.resultRow 的空值表示保持一致。 */
    private static final String EMPTY = "\u2014";

    /** 未来执行时间表格的高度：表头 + 约 9 行，条数更多时由表格自己滚动。 */
    private static final double TABLE_HEIGHT = 336;

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
        // 卡片说明只留两行（252px 宽的横向卡片），支持的写法放在 description()
        return "解析 cron 表达式并预览未来执行时间";
    }

    @Override
    public String description() {
        return "支持 * / 区间 / 枚举 / 步长 / 别名，给出中文说明与最近若干次执行时间";
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
        Label formatLabel = new Label(EMPTY);
        formatLabel.getStyleClass().add("result-value");

        // 字段说明：一组键值对，行数随「5 段 / 6 段」在 5～6 之间变化，故整块重建
        VBox describeBox = UiKit.resultTable();

        TableView<String[]> nextTable = buildNextTable();

        Label errorLabel = UiKit.error("");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        Label summaryLabel = UiKit.caption("");

        // ---------- 解析动作 ----------
        Runnable parse = () -> {
            String expr = exprField.getText() == null ? "" : exprField.getText().trim();
            hideError(errorLabel);
            if (expr.isEmpty()) {
                formatLabel.setText(EMPTY);
                describeBox.getChildren().clear();
                nextTable.getItems().clear();
                nextTable.setPlaceholder(placeholder("输入表达式后显示未来执行时间"));
                summaryLabel.setText("");
                return;
            }
            try {
                CronExpression cron = CronExpression.parse(expr);
                formatLabel.setText(cron.hasSeconds() ? "6 段（含秒）" : "5 段");
                fillDescribe(describeBox, cron);

                int count = countSpinner.getValue() == null ? 10 : countSpinner.getValue();
                LocalDateTime now = LocalDateTime.now();
                List<LocalDateTime> nextList = cron.nextList(now, count);

                if (nextList.isEmpty()) {
                    nextTable.getItems().clear();
                    nextTable.setPlaceholder(placeholder(
                            "该表达式在当前时间之后永不触发：指定的日期不存在，"
                                    + "或日月组合无法同时满足。"));
                    summaryLabel.setText("永不触发");
                } else {
                    nextTable.setItems(FXCollections.observableArrayList(
                            nextRows(nextList, now)));
                    nextTable.setPlaceholder(placeholder("输入表达式后显示未来执行时间"));
                    summaryLabel.setText("已解析，以下为未来 " + nextList.size() + " 次执行时间");
                }
            } catch (IllegalArgumentException ex) {
                formatLabel.setText(EMPTY);
                describeBox.getChildren().clear();
                nextTable.getItems().clear();
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
            chips.getChildren().add(UiKit.chip(sample[0], sample[1], () -> {
                exprField.setText(sample[1]);
                parse.run();
            }));
        }

        VBox exampleCard = UiKit.section("常用示例", "点击直接填入", chips);

        VBox resultCard = UiKit.section("解析结果", null,
                UiKit.resultRow("格式", formatLabel, false, null),
                UiKit.field("字段说明", describeBox),
                UiKit.field("未来执行时间", nextTable),
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

    // ==================== 未来执行时间表格 ====================

    /**
     * 构建四列的执行时间表：序号 / 时间 / 星期 / 距现在。
     *
     * <p>列数固定，因此用「约束到最后剩余空间」的策略，前 3 列给足宽度、
     * 末列吃掉剩余 —— 若用非约束策略，窗口变宽时表格右侧会留出空白带。
     */
    private static TableView<String[]> buildNextTable() {
        TableView<String[]> table = new TableView<>();
        table.getStyleClass().add("cron-next-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(TABLE_HEIGHT);
        table.setMinHeight(TABLE_HEIGHT);
        table.setPlaceholder(placeholder("输入表达式后显示未来执行时间"));

        table.getColumns().addAll(
                column("#", 56, 0),
                column("执行时间", 210, 1),
                column("星期", 90, 2),
                column("距现在", 0, 3));
        return table;
    }

    private static TableColumn<String[], String> column(String title,
                                                       double width, int index) {
        TableColumn<String[], String> col = new TableColumn<>(title);
        col.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue()[index]));
        col.setSortable(false);
        // 序号列窄且居中，其余左对齐
        if (index == 0) {
            col.setStyle("-fx-alignment: CENTER;");
        }
        if (width > 0) {
            col.setPrefWidth(width);
            col.setMinWidth(width);
        }
        return col;
    }

    private static Label placeholder(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("caption");
        label.setWrapText(true);
        label.setMaxWidth(420);
        return label;
    }

    /** 用结构化字段说明重建「字段说明」区块。 */
    private static void fillDescribe(VBox box, CronExpression cron) {
        box.getChildren().clear();
        List<String[]> rows = cron.describeRows();
        for (int i = 0; i < rows.size(); i++) {
            box.getChildren().add(
                    UiKit.resultRow(rows.get(i)[0], rows.get(i)[1], i > 0, null));
        }
    }

    // ==================== 结构化数据（界面与文本同源） ====================

    /**
     * 「未来执行时间」的结构化形式：每行为 {@code {序号, 时间, 星期, 距现在}}。
     *
     * <p>等待时长取的是**距当前时刻**的差（不是与上一次的间隔），
     * 因此每行数值逐行递增，读者一眼就能知道最近几次分别还要等多久。
     *
     * <p>界面表格与 {@link #buildNextText} 都消费这一份数据，
     * 保证「看到的」和「复制走的」永远一致。
     */
    public static List<String[]> nextRows(List<LocalDateTime> nextList, LocalDateTime now) {
        List<String[]> rows = new ArrayList<>(nextList.size());
        for (int i = 0; i < nextList.size(); i++) {
            LocalDateTime t = nextList.get(i);
            rows.add(new String[]{
                    String.valueOf(i + 1),
                    t.format(FMT),
                    weekName(t),
                    "+" + humanDuration(java.time.Duration.between(now, t))
            });
        }
        return rows;
    }

    /**
     * 渲染「未来执行时间」列表文本：序号 + 时间 + 星期 + 距当前时刻的等待时长。
     *
     * <p>这是 {@link #nextRows} 的文本投影，供复制到剪贴板、控制台输出与探针断言使用。
     * 抽成静态方法是为了能被探针直接断言 —— 界面只是它的薄壳。
     */
    public static String buildNextText(List<LocalDateTime> nextList, LocalDateTime now) {
        StringBuilder sb = new StringBuilder();
        for (String[] row : nextRows(nextList, now)) {
            sb.append(String.format("%2d. %s  %s", Integer.parseInt(row[0]), row[1], row[2]));
            sb.append("   (").append(row[3]).append(")");
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String weekName(LocalDateTime t) {
        String[] names = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return names[t.getDayOfWeek().getValue() - 1];
    }

    /**
     * 把时间差转成人类可读描述。
     *
     * <p>秒级也要保留精度：6 段表达式（如「每 30 秒」）的间隔不足 1 分钟，
     * 早期用「分钟数取整」会把 30 秒显示成「+0 分钟」。
     */
    public static String humanDuration(java.time.Duration d) {
        long totalSeconds = Math.abs(d.getSeconds());
        if (totalSeconds < 60) {
            return totalSeconds + " 秒";
        }
        long totalMinutes = totalSeconds / 60;
        long sec = totalSeconds % 60;
        if (totalMinutes < 60) {
            return sec == 0 ? totalMinutes + " 分钟" : totalMinutes + " 分钟 " + sec + " 秒";
        }
        long totalHours = totalMinutes / 60;
        long min = totalMinutes % 60;
        if (totalHours < 24) {
            return min == 0 ? totalHours + " 小时" : totalHours + " 小时 " + min + " 分";
        }
        long days = totalHours / 24;
        long hour = totalHours % 24;
        return hour == 0 ? days + " 天" : days + " 天 " + hour + " 小时";
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
