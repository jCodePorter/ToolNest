package com.bytefuture.toolnest.features.timestamp;

import com.bytefuture.toolnest.core.ModuleCategory;
import com.bytefuture.toolnest.ui.spi.ToolModule;
import com.bytefuture.toolnest.ui.components.UiKit;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * 时间戳转换：时间戳 ↔ 日期时间 双向转换。
 */
public class TimestampModule implements ToolModule {

    private static final DateTimeFormatter FMT_DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FMT_DATE =
            DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final DateTimeFormatter FMT_TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 无结果时的占位符。用破折号而不是连字符：连字符在等宽字体里太短，
     *  混在一列 13px 的数值中看起来像一个待填的输入格。 */
    private static final String EMPTY = "\u2014";

    @Override
    public String id() {
        return "timestamp";
    }

    @Override
    public String displayName() {
        return "时间戳转换";
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.CONVERT;
    }

    @Override
    public String summary() {
        // 卡片说明只留两行（252px 宽的横向卡片），文案需控制在此之内
        return "Unix 时间戳与本地日期时间双向互转";
    }

    @Override
    public String description() {
        return "支持秒级与毫秒级时间戳，同时显示星期与相对时间";
    }


    @Override
    public String keywords() {
        return "timestamp unix 时间 日期 转换 epoch";
    }

    @Override
    public Node buildContent() {
        VBox root = UiKit.page("时间戳转换", "时间戳与日期时间双向转换，支持秒 / 毫秒。");

        // ---------- 时间戳 -> 日期 ----------
        TextField tsField = new TextField();
        tsField.setPromptText("输入时间戳，例如 1700000000");

        ToggleGroup unitGroup = new ToggleGroup();
        RadioButton secRadio = new RadioButton("秒");
        RadioButton msRadio = new RadioButton("毫秒");
        secRadio.setToggleGroup(unitGroup);
        msRadio.setToggleGroup(unitGroup);
        secRadio.setSelected(true);
        HBox unitBox = new HBox(12, secRadio, msRadio);
        unitBox.setAlignment(Pos.CENTER_LEFT);

        // 整页只有一处主色实底：本工具的正向用途是「时间戳 → 日期」。
        // 「当前时间」只是取输入的快捷方式，不算提交动作；
        // 「转换为时间戳」是反方向，降为描边，避免和正向抢注意力。
        Button nowButton = UiKit.secondaryButton("当前时间");
        Button toDateButton = UiKit.primaryButton("转换为日期");

        Label zoneLabel = UiKit.caption("时区：" + ZoneId.systemDefault());

        Label dateOut = new Label(EMPTY);
        dateOut.getStyleClass().add("result-value");
        Label isoOut = new Label(EMPTY);
        isoOut.getStyleClass().add("result-value");
        Label weekOut = new Label(EMPTY);
        weekOut.getStyleClass().add("result-value");
        Label relativeOut = new Label(EMPTY);
        relativeOut.getStyleClass().add("result-value");
        Label errorOut = UiKit.error("");
        errorOut.setVisible(false);
        errorOut.setManaged(false);

        // 四行结果用同一个圆角描边的结果表包起来，而不是散落在卡片里 ——
        // 它们属于同一组输出，读的时候应当被当成一个整体。
        // 日期时间与标准格式是会被摘出去用的值，行内给复制按钮；
        // 星期、相对现在只是辅助阅读的派生描述，复制它们没有意义，保持干净。
        VBox tsResult = UiKit.resultTable(
                UiKit.copyableResultRow("日期时间", dateOut, false),
                UiKit.copyableResultRow("标准格式", isoOut, true),
                UiKit.resultRow("星期", weekOut, true, null),
                UiKit.resultRow("相对现在", relativeOut, true, null));

        VBox tsCard = UiKit.card(12,
                UiKit.field("时间戳", tsField),
                UiKit.field("单位", unitBox),
                UiKit.buttonRow(nowButton, toDateButton),
                tsResult,
                zoneLabel,
                errorOut
        );

        Runnable convert = () -> {
            errorOut.setVisible(false);
            errorOut.setManaged(false);
            String raw = tsField.getText() == null ? "" : tsField.getText().trim();
            if (raw.isEmpty()) {
                clear(dateOut, isoOut, weekOut, relativeOut);
                return;
            }
            try {
                long value = Long.parseLong(raw);
                Instant instant = secRadio.isSelected()
                        ? Instant.ofEpochSecond(value)
                        : Instant.ofEpochMilli(value);
                ZonedDateTime zdt = instant.atZone(ZoneId.systemDefault());
                render(zdt, dateOut, isoOut, weekOut, relativeOut);
            } catch (NumberFormatException ex) {
                showError(errorOut, "时间戳必须是整数");
                clear(dateOut, isoOut, weekOut, relativeOut);
            } catch (Exception ex) {
                showError(errorOut, "数值超出可表示范围");
                clear(dateOut, isoOut, weekOut, relativeOut);
            }
        };

        tsField.textProperty().addListener((o, old, now) -> convert.run());
        unitGroup.selectedToggleProperty().addListener((o, old, now) -> convert.run());
        toDateButton.setOnAction(e -> convert.run());
        nowButton.setOnAction(e -> {
            boolean isMs = msRadio.isSelected();
            tsField.setText(String.valueOf(isMs ? System.currentTimeMillis()
                    : System.currentTimeMillis() / 1000));
        });

        // ---------- 日期 -> 时间戳 ----------
        DatePicker datePicker = new DatePicker(LocalDate.now());

        Spinner<Integer> hourSpinner = timeSpinner(0, 23, LocalTime.now().getHour());
        Spinner<Integer> minuteSpinner = timeSpinner(0, 59, LocalTime.now().getMinute());
        Spinner<Integer> secondSpinner = timeSpinner(0, 59, LocalTime.now().getSecond());
        HBox timeBox = new HBox(6, hourSpinner, new Label(":"),
                minuteSpinner, new Label(":"), secondSpinner);
        timeBox.setAlignment(Pos.CENTER_LEFT);

        Button toTsButton = UiKit.secondaryButton("转换为时间戳");
        Label secTsOut = new Label(EMPTY);
        secTsOut.getStyleClass().add("result-value");
        Label msTsOut = new Label(EMPTY);
        msTsOut.getStyleClass().add("result-value");

        VBox tsOutResult = UiKit.resultTable(
                UiKit.copyableResultRow("秒级时间戳", secTsOut, false),
                UiKit.copyableResultRow("毫秒时间戳", msTsOut, true));

        VBox dateCard = UiKit.card(12,
                UiKit.field("日期", datePicker),
                UiKit.field("时间", timeBox),
                UiKit.buttonRow(toTsButton),
                tsOutResult
        );

        toTsButton.setOnAction(e -> {
            LocalDate date = datePicker.getValue();
            if (date == null) {
                secTsOut.setText(EMPTY);
                msTsOut.setText(EMPTY);
                return;
            }
            LocalDateTime ldt = LocalDateTime.of(date, LocalTime.of(
                    hourSpinner.getValue(), minuteSpinner.getValue(), secondSpinner.getValue()));
            long seconds = ldt.atZone(ZoneId.systemDefault()).toEpochSecond();
            secTsOut.setText(String.valueOf(seconds));
            msTsOut.setText(String.valueOf(seconds * 1000));
        });

        root.getChildren().addAll(tsCard, dateCard);
        return root;
    }

    private void render(ZonedDateTime zdt, Label dateOut, Label isoOut,
                        Label weekOut, Label relativeOut) {
        dateOut.setText(zdt.format(FMT_DATETIME));
        isoOut.setText(zdt.toOffsetDateTime().toString());

        String[] weekNames = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        weekOut.setText(weekNames[zdt.getDayOfWeek().getValue() - 1]);

        relativeOut.setText(relative(zdt));
    }

    /** 计算相对当前时间的自然语言描述。 */
    private String relative(ZonedDateTime zdt) {
        Instant target = zdt.toInstant();
        Instant now = Instant.now();
        long abs = Math.abs(ChronoUnit.SECONDS.between(target, now));
        boolean future = target.isAfter(now);

        String amount;
        if (abs < 60) {
            amount = abs + " 秒";
        } else if (abs < 3600) {
            amount = (abs / 60) + " 分钟";
        } else if (abs < 86400) {
            amount = (abs / 3600) + " 小时";
        } else if (abs < 2592000) {
            amount = (abs / 86400) + " 天";
        } else if (abs < 31536000) {
            amount = (abs / 2592000) + " 个月";
        } else {
            amount = (abs / 31536000) + " 年";
        }
        return future ? amount + "后" : amount + "前";
    }

    private void clear(Label... labels) {
        for (Label label : labels) {
            label.setText(EMPTY);
        }
    }

    private void showError(Label errorLabel, String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private Spinner<Integer> timeSpinner(int min, int max, int initial) {
        Spinner<Integer> spinner = new Spinner<>(min, max, initial);
        spinner.setPrefWidth(78);
        spinner.setEditable(true);
        return spinner;
    }
}
