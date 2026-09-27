package com.bytefuture.toolnest.modules.timestamp;

import com.bytefuture.toolnest.core.ModuleCategory;
import com.bytefuture.toolnest.core.ToolModule;
import com.bytefuture.toolnest.ui.UiKit;
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
        return "将 Unix 时间戳转换为人类可读的日期，亦可将日期转回时间戳";
    }

    @Override
    public String description() {
        return "支持秒级与毫秒级时间戳，同时显示星期与相对时间";
    }

    @Override
    public String icon() {
        return "\u23F1";
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

        Button nowButton = new Button("当前时间");
        Button toDateButton = new Button("转换为日期");

        Label zoneLabel = UiKit.caption("时区：" + ZoneId.systemDefault());

        Label dateOut = new Label("-");
        dateOut.getStyleClass().add("result-value");
        Label isoOut = new Label("-");
        isoOut.getStyleClass().add("result-value");
        Label weekOut = new Label("-");
        weekOut.getStyleClass().add("result-value");
        Label relativeOut = new Label("-");
        relativeOut.getStyleClass().add("result-value");
        Label errorOut = UiKit.error("");
        errorOut.setVisible(false);
        errorOut.setManaged(false);

        VBox tsCard = UiKit.card(12,
                UiKit.field("时间戳", tsField),
                UiKit.field("单位", unitBox),
                UiKit.buttonRow(nowButton, toDateButton),
                new Separator(),
                UiKit.resultRow("日期时间", dateOut, null),
                UiKit.resultRow("标准格式", isoOut, null),
                UiKit.resultRow("星期", weekOut, null),
                UiKit.resultRow("相对现在", relativeOut, null),
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

        Button toTsButton = new Button("转换为时间戳");
        Label secTsOut = new Label("-");
        secTsOut.getStyleClass().add("result-value");
        Label msTsOut = new Label("-");
        msTsOut.getStyleClass().add("result-value");

        VBox dateCard = UiKit.card(12,
                UiKit.field("日期", datePicker),
                UiKit.field("时间", timeBox),
                UiKit.buttonRow(toTsButton),
                new Separator(),
                UiKit.resultRow("秒级时间戳", secTsOut, null),
                UiKit.resultRow("毫秒时间戳", msTsOut, null)
        );

        toTsButton.setOnAction(e -> {
            LocalDate date = datePicker.getValue();
            if (date == null) {
                secTsOut.setText("-");
                msTsOut.setText("-");
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
            label.setText("-");
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
