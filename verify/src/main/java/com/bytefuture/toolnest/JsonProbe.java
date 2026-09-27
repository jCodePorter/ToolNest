package com.bytefuture.toolnest;

import com.bytefuture.toolnest.modules.json.JsonFormatModule;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON 格式化模块的界面级验证。
 *
 * <p>直接驱动真实控件：填入带嵌套结构的 JSON，依次点「格式化 / 压缩 /
 * 切换缩进」，断言输出区的实际文本。这样验证的是「用户点下去会发生什么」，
 * 而不是「某个内部方法返回什么」—— 缩进算法被替换后，必须确认界面上
 * 三种缩进（2 空格 / 4 空格 / 制表符）都真的生效。
 *
 * <p>需由 {@code JsonProbeLauncher} 启动。
 */
public class JsonProbe extends Application {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name + "  → " + detail);
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

    private static Button button(String text, Node root) {
        return find(root, Button.class, b -> text.equals(b.getText()));
    }

    private static List<String> styleClassesNode(Class<?> type, Node root) {
        return new ArrayList<>();
    }

    /** 取输出区（RichTextFX CodeArea）的文本。 */
    private static String outputText(Node root) {
        CodeArea area = find(root, CodeArea.class, a -> true);
        return area == null ? "<未找到输出区>" : area.getText();
    }

    @Override
    public void start(Stage stage) {
        JsonFormatModule module = new JsonFormatModule();
        Node content = module.buildContent();
        StackPane root = new StackPane(content);
        Scene scene = new Scene(root, 1100, 720);
        scene.getStylesheets().add(
                JsonProbe.class.getResource("/css/app.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
        root.applyCss();
        root.layout();

        System.out.println("=== JSON 格式化 界面级验证 ===");

        // 找输入区（唯一可编辑的 TextArea）
        TextArea input = find(content, TextArea.class, TextArea::isEditable);
        if (input == null) {
            check("找到输入区", false, "未找到可编辑 TextArea");
            finish();
            return;
        }
        check("找到输入区", true, "");

        // 含嵌套对象、数组、字符串内含转义换行、null 的样本
        String sample = "{\"name\":\"test\",\"nested\":{\"a\":1,\"b\":[1,2,3]},"
                + "\"multiline\":\"line1\\nline2\",\"nil\":null}";
        input.setText(sample);

        // ---- 1. 点击「格式化」→ 2 空格缩进 ----
        Button formatBtn = button("格式化", content);
        check("找到「格式化」按钮", formatBtn != null, "");
        if (formatBtn == null) {
            finish();
            return;
        }
        formatBtn.fire();

        String twoSpace = outputText(content);
        check("格式化后输出非空", !twoSpace.isBlank(), "输出：" + twoSpace);
        check("2 空格缩进生效",
                twoSpace.contains("\n  \"name\""), "输出：\n" + twoSpace);
        check("嵌套层级缩进为 4 空格",
                twoSpace.contains("\n    \"a\""), "输出：\n" + twoSpace);
        check("字符串内的 \\n 未被当成结构换行（未破坏缩进层级）",
                twoSpace.contains("line1\\nline2"), "输出：\n" + twoSpace);
        check("null 值被保留", twoSpace.contains("null"), "输出：\n" + twoSpace);

        // ---- 2. 切换缩进为 4 空格 → 应触发自动重新格式化 ----
        ComboBox<?> indentBox = find(content, ComboBox.class,
                c -> c.getItems().size() == 3);
        check("找到缩进下拉框", indentBox != null, "");
        if (indentBox != null) {
            @SuppressWarnings("unchecked")
            ComboBox<String> box = (ComboBox<String>) indentBox;
            box.getSelectionModel().select(1);   // 4 空格
            root.applyCss();
            root.layout();

            String fourSpace = outputText(content);
            check("切换为 4 空格后自动重新格式化",
                    fourSpace.contains("\n    \"name\""), "输出：\n" + fourSpace);
            check("4 空格下嵌套为 8 空格",
                    fourSpace.contains("\n        \"a\""), "输出：\n" + fourSpace);

            // ---- 3. 制表符 ----
            box.getSelectionModel().select(2);   // 制表符
            root.applyCss();
            root.layout();
            String tabbed = outputText(content);
            check("制表符缩进生效",
                    tabbed.contains("\n\t\"name\""), "输出：\n" + tabbed);
        }

        // ---- 4. 压缩 ----
        Button minifyBtn = button("压缩", content);
        if (minifyBtn != null) {
            minifyBtn.fire();
            String compact = outputText(content);
            check("压缩后为单行",
                    !compact.contains("\n"), "输出：" + compact);
            check("压缩后内容完整",
                    compact.contains("\"nested\"") && compact.contains("[1,2,3]"),
                    "输出：" + compact);
        }

        // ---- 5. 非法 JSON 应给出错误而非崩溃 ----
        input.setText("{ this is not json }");
        formatBtn.fire();
        root.applyCss();
        root.layout();
        check("非法 JSON 时输出区被清空",
                outputText(content).isBlank(), "输出：" + outputText(content));

        finish();
    }

    private void finish() {
        System.out.println();
        System.out.println("RESULT passed=" + passed + " failed=" + failed);
        Platform.exit();
        System.exit(failed == 0 ? 0 : 1);
    }
}
