package com.bytefuture.toolnest.modules.http;

import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP 响应体渲染验证探针。
 *
 * <p>验证的核心是「同一份内容被正确分流到两个视图」这条运行时逻辑：
 * JSON 走 {@code JsonCodeArea} 高亮，非 JSON 及超大响应走纯文本 TextArea。
 * 这些行为只编译是看不出来的 —— 视图切换、按钮可用态、格式化往返都属于运行时状态。
 *
 * <p>本类位于 {@code modules.http} 包内，因为被测的测试钩子是包级可见的。
 */
public class HttpBodyProbe extends Application {

    private static int passed = 0;
    private static int failed = 0;
    private static final List<String> cssErrors = new ArrayList<>();
    private static final List<String> problems = new ArrayList<>();

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name + "  → " + detail);
        }
    }

    /** 造一份响应结果，只填 Body 相关字段。 */
    private static HttpEngine.Result resultWith(String body) {
        HttpEngine.Result r = new HttpEngine.Result();
        r.status = 200;
        r.body = body;
        r.finalUrl = "https://example.com/api";
        r.elapsedMs = 12;
        r.headers.put("Content-Type", "application/json; charset=utf-8");
        return r;
    }

    /** 把界面当前状态落盘，便于人工核对高亮与排版。 */
    private static void snapshot(javafx.scene.Node node, String name) {
        try {
            WritableImage image = node.snapshot(null, null);
            File out = new File("target/smoke");
            out.mkdirs();
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png",
                    new File(out, name + ".png"));
            System.out.println("[shot] " + name + ".png");
        } catch (Exception ex) {
            System.out.println("[截图失败] " + name + "：" + ex.getMessage());
        }
    }

    @Override
    public void start(Stage stage) {
        Thread.setDefaultUncaughtExceptionHandler((t, ex) -> {
            String msg = String.valueOf(ex);
            problems.add(msg);
            if (msg.contains("Could not resolve") || msg.contains("CSS Error")) {
                cssErrors.add(msg);
            }
        });

        setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());

        SingleRequestPanel panel = new SingleRequestPanel();

        StackPane root = new StackPane(panel);
        Scene scene = new Scene(root, 1100, 760);
        scene.getStylesheets().add(
                HttpBodyProbe.class.getResource("/css/app.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
        root.applyCss();
        root.layout();

        // ---- 1. JSON 对象 -> 高亮视图 ----------------------------------
        panel.applyResultForTest(resultWith(
                "{\"name\":\"toolnest\",\"version\":\"1.0.0\",\"ok\":true,"
                        + "\"count\":42,\"ratio\":0.75,\"tags\":[\"tool\",\"desktop\"],"
                        + "\"nested\":{\"deep\":null},\"msg\":\"hello world\"}"));
        root.applyCss();
        root.layout();
        snapshot(root, "http-body-json-highlight");
        check("JSON 对象走高亮视图", panel.bodyIsJsonForTest(), "应识别为 JSON");
        check("高亮视图已格式化（含换行缩进）",
                panel.highlightedBodyForTest().contains("\n"),
                "格式化后应有多行");
        check("高亮视图保留键名",
                panel.highlightedBodyForTest().contains("\"name\""),
                panel.highlightedBodyForTest());
        check("格式化按钮可用", panel.formatButtonEnabledForTest(), "JSON 时应可点");

        // ---- 2. 压缩往返 ----------------------------------------------
        panel.toggleFormatForTest();
        check("压缩后变成单行",
                !panel.highlightedBodyForTest().contains("\n"),
                panel.highlightedBodyForTest());
        check("压缩后内容不丢",
                panel.highlightedBodyForTest().contains("toolnest"),
                panel.highlightedBodyForTest());
        panel.toggleFormatForTest();
        check("再点一次回到格式化（往返幂等）",
                panel.highlightedBodyForTest().contains("\n"),
                "第二次点击应恢复美化");

        // ---- 3. JSON 数组 -> 高亮视图 ----------------------------------
        panel.applyResultForTest(resultWith("[1,2,3,{\"a\":\"b\"}]"));
        check("JSON 数组走高亮视图", panel.bodyIsJsonForTest(), "应以 [ 开头命中");
        check("数组元素进入高亮文本",
                panel.highlightedBodyForTest().contains("\"a\""),
                panel.highlightedBodyForTest());

        // ---- 4. 非 JSON -> 纯文本 -------------------------------------
        panel.applyResultForTest(resultWith("<html><body>hello</body></html>"));
        root.applyCss();
        root.layout();
        snapshot(root, "http-body-plain-text");
        check("HTML 不走高亮视图", !panel.bodyIsJsonForTest(), "不应误判为 JSON");
        check("HTML 内容进入纯文本视图",
                panel.plainBodyForTest().contains("hello"),
                panel.plainBodyForTest());
        check("非 JSON 时格式化按钮禁用", !panel.formatButtonEnabledForTest(), "应禁用");

        // ---- 5. 裸字符串 -> 纯文本（不算值得高亮的 JSON）----------------
        panel.applyResultForTest(resultWith("\"just-a-string\""));
        check("裸字符串走纯文本", !panel.bodyIsJsonForTest(), "只有对象/数组才高亮");

        // ---- 6. 非法 JSON -> 纯文本（不抛异常）--------------------------
        panel.applyResultForTest(resultWith("{\"broken\": }"));
        check("非法 JSON 不抛异常且退回纯文本",
                !panel.bodyIsJsonForTest(), "解析失败应安全降级");

        // ---- 7. 超大 JSON -> 纯文本（避免 UI 卡顿）----------------------
        StringBuilder big = new StringBuilder("[");
        for (int i = 0; i < 40000; i++) {
            if (i > 0) {
                big.append(',');
            }
            big.append("{\"i\":").append(i).append(",\"v\":\"xxxxxxxxxxxxxxxx\"}");
        }
        big.append(']');
        String hugeBody = big.toString();
        panel.applyResultForTest(resultWith(hugeBody));
        check("超大 JSON 退回纯文本（超 256KB 阈值）",
                !panel.bodyIsJsonForTest(),
                "实际长度 " + hugeBody.length());
        check("超大响应内容仍完整保留",
                panel.plainBodyForTest().length() == hugeBody.length(),
                "长度 " + panel.plainBodyForTest().length() + " vs " + hugeBody.length());

        // ---- 8. 空响应 -------------------------------------------------
        panel.applyResultForTest(resultWith(""));
        check("空响应不误判为 JSON", !panel.bodyIsJsonForTest(), "空内容应走纯文本");

        // ---- 9. 清空后状态复位 -----------------------------------------
        panel.applyResultForTest(resultWith("{\"a\":1}"));
        check("复位前是 JSON", panel.bodyIsJsonForTest(), "前置条件");
        panel.clearResponseForTest();
        check("清空后不再标记为 JSON", !panel.bodyIsJsonForTest(), "状态应复位");
        check("清空后格式化按钮禁用", !panel.formatButtonEnabledForTest(), "状态应复位");
        check("清空后纯文本视图为空", panel.plainBodyForTest().isEmpty(), "应清空");

        // ---- 10. CSS 错误 ----------------------------------------------
        check("未出现 CSS 解析错误", cssErrors.isEmpty(),
                cssErrors.isEmpty() ? "" : String.join(" | ", cssErrors));

        System.out.println();
        System.out.println("RESULT passed=" + passed + " failed=" + failed);
        if (!problems.isEmpty()) {
            System.out.println("（捕获到 " + problems.size() + " 条未捕获异常）");
        }
        Platform.exit();
    }
}
