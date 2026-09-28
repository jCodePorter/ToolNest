package com.bytefuture.toolnest;

import com.bytefuture.toolnest.features.timestamp.TimestampModule;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 时间戳转换模块「结果行复制按钮」的界面级验证。
 *
 * <p>验的是按钮真的能拿到**当前**的值：填一个时间戳 → 点复制 → 读系统剪贴板比对；
 * 再改输入 → 再点同一个按钮 → 剪贴板必须跟着变。
 * 「构造时把文本交给按钮」这种写法在第二次断言上就会露馅（复制到的还是旧值）。
 *
 * <p>需由 {@code TimestampModuleProbeLauncher} 启动。
 */
public class TimestampModuleProbe extends Application {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name + "  -> " + detail);
        }
    }

    private static <T extends Node> T find(Node node, Class<T> type,
                                           java.util.function.Predicate<T> filter) {
        if (type.isInstance(node) && filter.test(type.cast(node))) {
            return type.cast(node);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                T found = find(child, type, filter);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void collectRows(Node node, List<HBox> out) {
        if (node instanceof HBox box && box.getStyleClass().contains("result-row")) {
            out.add(box);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectRows(child, out);
            }
        }
    }

    /** 按行首标签取结果行（不按下标取子节点）。 */
    private static HBox row(Node root, String labelText) {
        List<HBox> rows = new ArrayList<>();
        collectRows(root, rows);
        for (HBox r : rows) {
            for (Node child : r.getChildrenUnmodifiable()) {
                if (child instanceof Label l && l.getStyleClass().contains("result-label")
                        && labelText.equals(l.getText())) {
                    return r;
                }
            }
        }
        return null;
    }

    private static Label valueOf(HBox row) {
        if (row == null) {
            return null;
        }
        for (Node child : row.getChildrenUnmodifiable()) {
            if (child instanceof Label l && l.getStyleClass().contains("result-value")) {
                return l;
            }
        }
        return null;
    }

    private static Button copyOf(HBox row) {
        if (row == null) {
            return null;
        }
        for (Node child : row.getChildrenUnmodifiable()) {
            if (child instanceof Button b) {
                return b;
            }
        }
        return null;
    }

    private static String clipboard() {
        try {
            String s = Clipboard.getSystemClipboard().getString();
            return s == null ? "<null>" : s;
        } catch (Exception ex) {
            return "<剪贴板不可用：" + ex.getMessage() + ">";
        }
    }

    @Override
    public void start(Stage stage) {
        Node content = new TimestampModule().buildContent();
        StackPane root = new StackPane(content);
        Scene scene = new Scene(root, 1100, 900);
        scene.getStylesheets().add(
                TimestampModuleProbe.class.getResource("/css/app.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
        root.applyCss();
        root.layout();

        System.out.println("=== 时间戳转换 结果行复制 界面级验证 ===");

        TextField tsField = find(content, TextField.class,
                t -> t.getPromptText() != null && t.getPromptText().contains("输入时间戳"));
        check("找到时间戳输入框", tsField != null, "未找到");
        if (tsField == null) {
            finish(scene);
            return;
        }

        List<HBox> all = new ArrayList<>();
        collectRows(content, all);
        check("结果行共 6 行", all.size() == 6, "实际 " + all.size());

        // ---- 1. 按钮只长在该长的行上 ----
        check("「日期时间」行有复制按钮", copyOf(row(content, "日期时间")) != null, "无按钮");
        check("「标准格式」行有复制按钮", copyOf(row(content, "标准格式")) != null, "无按钮");
        check("「秒级时间戳」行有复制按钮", copyOf(row(content, "秒级时间戳")) != null, "无按钮");
        check("「毫秒时间戳」行有复制按钮", copyOf(row(content, "毫秒时间戳")) != null, "无按钮");
        check("「星期」行不带复制按钮（派生描述，复制无意义）",
                copyOf(row(content, "星期")) == null, "却长了按钮");
        check("「相对现在」行不带复制按钮",
                copyOf(row(content, "相对现在")) == null, "却长了按钮");

        // ---- 2. 空值时应禁用它 ----
        HBox dateRow = row(content, "日期时间");
        Label dateValue = valueOf(dateRow);
        Button dateCopy = copyOf(dateRow);
        check("空值行显示占位破折号",
                dateValue != null && "\u2014".equals(dateValue.getText()),
                dateValue == null ? "未找到数值" : dateValue.getText());
        check("空值行复制按钮为禁用态",
                dateCopy != null && dateCopy.isDisabled(), "按钮仍可点");

        // ---- 3. 填入时间戳 → 复制到的就是当前值 ----
        tsField.setText("1700000000");
        root.applyCss();
        root.layout();

        String dateText = dateValue.getText();
        check("填入后日期时间行有值（不再是破折号）",
                dateText != null && !"\u2014".equals(dateText), "实际 " + dateText);
        check("填入后复制按钮可用", dateCopy != null && !dateCopy.isDisabled(), "仍禁用");

        dateCopy.fire();
        check("点复制 → 剪贴板等于该行当前文本",
                dateText != null && dateText.equals(clipboard()),
                "期望 " + dateText + " 实际 " + clipboard());

        // ---- 4. 改输入后同一个按钮必须复制到新值（关键的「点击时读数」验证） ----
        tsField.setText("1600000000");
        root.applyCss();
        root.layout();
        String newDateText = dateValue.getText();
        check("改输入后日期时间行已刷新",
                newDateText != null && !newDateText.equals(dateText),
                "仍是 " + newDateText);
        dateCopy.fire();
        check("同一个复制按钮复制到的是新值而不是旧值",
                newDateText != null && newDateText.equals(clipboard()),
                "期望 " + newDateText + " 实际 " + clipboard());

        // ---- 5. 日期 → 时间戳的秒 / 毫秒两行 ----
        Button toTs = find(content, Button.class,
                b -> "转换为时间戳".equals(b.getText()));
        check("找到「转换为时间戳」按钮", toTs != null, "未找到");
        if (toTs != null) {
            toTs.fire();
            root.applyCss();
            root.layout();

            HBox secRow = row(content, "秒级时间戳");
            HBox msRow = row(content, "毫秒时间戳");
            Label secValue = valueOf(secRow);
            Label msValue = valueOf(msRow);
            Button secCopy = copyOf(secRow);
            Button msCopy = copyOf(msRow);

            String secText = secValue == null ? null : secValue.getText();
            String msText = msValue == null ? null : msValue.getText();
            check("秒级时间戳行有值", secText != null && !"\u2014".equals(secText), "实际 " + secText);
            check("毫秒时间戳行有值", msText != null && !"\u2014".equals(msText), "实际 " + msText);

            if (secCopy != null) {
                secCopy.fire();
                check("复制秒级时间戳 → 剪贴板正确",
                        secText != null && secText.equals(clipboard()),
                        "期望 " + secText + " 实际 " + clipboard());
            } else {
                check("复制秒级时间戳 → 剪贴板正确", false, "无复制按钮");
            }
            if (msCopy != null) {
                msCopy.fire();
                check("复制毫秒时间戳 → 剪贴板正确",
                        msText != null && msText.equals(clipboard()),
                        "期望 " + msText + " 实际 " + clipboard());
            } else {
                check("复制毫秒时间戳 → 剪贴板正确", false, "无复制按钮");
            }
        }

        // ---- 6. 清空输入 → 按钮回到禁用 ----
        tsField.setText("");
        root.applyCss();
        root.layout();
        check("清空输入后日期时间行回到破折号且按钮禁用",
                dateValue != null && "\u2014".equals(dateValue.getText())
                        && dateCopy != null && dateCopy.isDisabled(),
                "值 " + (dateValue == null ? "?" : dateValue.getText())
                        + " 禁用 " + (dateCopy != null && dateCopy.isDisabled()));

        // 截图留档（重新填一个值，好看清复制按钮）
        tsField.setText("1700000000");
        root.applyCss();
        root.layout();
        finish(scene);
    }

    private void finish(Scene scene) {
        Platform.runLater(() -> Platform.runLater(() -> Platform.runLater(() -> {
            try {
                WritableImage img = scene.snapshot(null);
                File dir = new File("target/timestamp-probe");
                dir.mkdirs();
                File out = new File(dir, "timestamp-copy.png");
                ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", out);
                System.out.println("SCREENSHOT " + out.getPath());
            } catch (Exception ex) {
                System.out.println("SCREENSHOT FAILED " + ex);
            }
            System.out.println();
            System.out.println("RESULT passed=" + passed + " failed=" + failed);
            Platform.exit();
            System.exit(failed == 0 ? 0 : 1);
        })));
    }
}
