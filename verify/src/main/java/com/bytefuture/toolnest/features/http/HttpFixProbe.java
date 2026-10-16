package com.bytefuture.toolnest.features.http;

import atlantafx.base.theme.PrimerLight;
import com.bytefuture.toolnest.ui.components.UiKit;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * HTTP 工具缺陷修复的运行时验证探针。
 *
 * <p>覆盖的都是「编译通过但行为是错的」那一类问题：
 * <ul>
 *   <li>页面被缓存复用，切走时线程池被关掉，切回来 Send 点不动 —— 状态问题；</li>
 *   <li>响应体一律按 UTF-8 解码，GBK 接口全乱码 —— 只在真实字节流上才暴露；</li>
 *   <li>导出结果 CSV 少了 BOM，Excel 打开中文乱码 —— 只在落盘字节上才看得出来；</li>
 *   <li>请求头里的占位符没被替换 —— 要跑一遍行生成逻辑才能发现。</li>
 * </ul>
 *
 * <p>引擎侧断言跑在真实的本地 HTTP 服务器上（{@link TinyHttpServer}），
 * 不做打桩 —— 解码、重定向、请求体这些行为都发生在 socket 之上。
 */
public class HttpFixProbe extends Application {

    private static int passed = 0;
    private static int failed = 0;
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

    private static String bodyOf(HttpEngine.Result r) {
        return r.error == null ? r.body : ("错误：" + r.error);
    }

    @Override
    public void start(Stage stage) {
        Thread.setDefaultUncaughtExceptionHandler((t, ex) -> problems.add(String.valueOf(ex)));
        setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());

        try (TinyHttpServer server = new TinyHttpServer()) {
            // ================= 1. 切页后仍能发请求 =================
            singleRequestLifecycle();

            // ================= 2. 响应体编码 =================
            bodyDecoding(server);

            // ================= 3. 重定向后的最终地址 =================
            redirect(server);

            // ================= 4. 请求头占位符替换 / 导出 / 预览 =================
            batchPipeline(server);
        } catch (Exception ex) {
            failed++;
            System.out.println("FAIL  探针自身异常 → " + ex);
            ex.printStackTrace(System.out);
        }

        // ================= 5. 请求体类型推断（纯逻辑）=================
        bodyTypeInference();

        // ================= 6. 界面联动 =================
        bodyAreaBinding();

        check("未出现未捕获异常", problems.isEmpty(),
                problems.isEmpty() ? "" : String.join(" | ", problems));

        System.out.println();
        System.out.println("RESULT passed=" + passed + " failed=" + failed);
        Platform.exit();
    }

    /**
     * 单一请求页被 ContentPane 缓存复用：切走时 scene 变 null 会触发 shutdown，
     * 切回来用的是同一个面板实例。这里复现「切走 → 切回 → 再点 Send」。
     */
    private void singleRequestLifecycle() {
        SingleRequestPanel panel = new SingleRequestPanel();

        // 线程池是懒创建的：不先提交一次，shutdown 时它还是 null，
        // 「池被关掉」这个状态根本不会出现，后面的断言就成了恒真。
        check("首次提交成功（前置条件：池已真正创建）",
                panel.canSubmitForTest(), "未使用过的面板就不能提交");

        // 模拟切走时请求还没回来（按钮停在发送中）
        panel.sendButtonForTest().setDisable(true);
        panel.sendButtonForTest().setText("发送中…");
        panel.shutdown();

        check("切页后 Send 按钮复位为可点",
                !panel.sendButtonForTest().isDisable(), "按钮仍是禁用态");
        check("切页后 Send 文案复位",
                "Send".equals(panel.sendButtonForTest().getText()),
                panel.sendButtonForTest().getText());
        check("切页再回来仍能提交请求（线程池已重建）",
                panel.canSubmitForTest(), "线程池停在已关闭状态");

        panel.shutdown();
        panel.shutdown();
        check("重复 shutdown 不抛异常", true, "");
    }

    /** 响应体解码：显式声明 GBK / 未声明 charset 两种情况。 */
    private void bodyDecoding(TinyHttpServer server) {
        String chinese = "订单已受理：张三 13800138000";
        byte[] gbk = chinese.getBytes(Charset.forName("GBK"));

        server.serve("/gbk", "text/plain; charset=gbk", gbk);
        HttpEngine.Result declared = HttpEngine.send(
                new HttpRequestSpec(HttpRequestSpec.Method.GET, server.baseUrl() + "/gbk",
                        HttpRequestSpec.BodyType.NONE, ""),
                Duration.ofSeconds(10));
        check("声明 charset=gbk 时中文不乱码",
                chinese.equals(declared.body), bodyOf(declared));
        check("响应大小用真实字节数（不是重新按 UTF-8 估的）",
                declared.bodyBytes == gbk.length,
                declared.bodyBytes + " vs " + gbk.length);

        server.serve("/gbk-nohint", "text/plain", gbk);
        HttpEngine.Result guessed = HttpEngine.send(
                new HttpRequestSpec(HttpRequestSpec.Method.GET, server.baseUrl() + "/gbk-nohint",
                        HttpRequestSpec.BodyType.NONE, ""),
                Duration.ofSeconds(10));
        check("未声明 charset 时自动探测出 GBK",
                chinese.equals(guessed.body), bodyOf(guessed));

        server.serve("/utf8", "application/json; charset=utf-8",
                "{\"msg\":\"中文正常\"}".getBytes(StandardCharsets.UTF_8));
        HttpEngine.Result utf8 = HttpEngine.send(
                new HttpRequestSpec(HttpRequestSpec.Method.GET, server.baseUrl() + "/utf8",
                        HttpRequestSpec.BodyType.NONE, ""),
                Duration.ofSeconds(10));
        check("UTF-8 响应不受影响",
                "{\"msg\":\"中文正常\"}".equals(utf8.body), bodyOf(utf8));
    }

    /** 跟随重定向后，finalUrl 应当是真正取到响应的地址。 */
    private void redirect(TinyHttpServer server) {
        server.serve("/target", "text/plain; charset=utf-8",
                "final".getBytes(StandardCharsets.UTF_8));
        server.redirect("/go", server.baseUrl() + "/target");

        HttpEngine.Result result = HttpEngine.send(
                new HttpRequestSpec(HttpRequestSpec.Method.GET, server.baseUrl() + "/go",
                        HttpRequestSpec.BodyType.NONE, ""),
                Duration.ofSeconds(10));
        check("重定向后 finalUrl 指向真实地址",
                result.finalUrl.endsWith("/target"), result.finalUrl);
        check("重定向后仍拿到目标响应体",
                "final".equals(result.body), bodyOf(result));
    }

    /** 批处理链路：请求头占位符替换、导出 CSV、响应预览缓存。 */
    private void batchPipeline(TinyHttpServer server) throws Exception {
        server.serve("/echo", "application/json; charset=utf-8",
                "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));

        BatchPanel batch = new BatchPanel();
        batch.applyTemplate(
                new HttpRequestSpec(HttpRequestSpec.Method.GET,
                        server.baseUrl() + "/echo?orderId={orderId}",
                        HttpRequestSpec.BodyType.NONE, ""),
                List.of(
                        new AbstractMap.SimpleEntry<>("Authorization", "Bearer {orderId}"),
                        new AbstractMap.SimpleEntry<>("X-Static", "plain")));
        batch.loadSampleForTest();          // 内置示例：orderId 235027703726 / 235027703727 …
        batch.prepareResultRowsForTest();   // 只生成待执行行，不发请求

        List<BatchPanel.ResultRow> rows = List.copyOf(batch.resultRowsForTest());
        check("示例数据生成了 3 个待执行行", rows.size() == 3, "实际 " + rows.size());

        BatchPanel.ResultRow first = rows.get(0);
        check("地址模板里的占位符已替换",
                first.spec.url.contains("orderId=235027703726"), first.spec.url);
        check("请求头值里的占位符已替换",
                headerValue(first.headers, "Authorization").equals("Bearer 235027703726"),
                headerValue(first.headers, "Authorization"));
        check("不含占位符的请求头原样保留",
                headerValue(first.headers, "X-Static").equals("plain"),
                headerValue(first.headers, "X-Static"));
        check("第二行用的是第二行的变量",
                rows.get(1).spec.url.contains("orderId=235027703727"),
                rows.get(1).spec.url);

        // ---- 导出 CSV ----
        String csv = batch.buildResultCsv();
        check("导出 CSV 带 UTF-8 BOM", csv.startsWith("\uFEFF"), "首字符不是 BOM");

        Path temp = Files.createTempFile("toolnest-http-export", ".csv");
        try {
            Files.writeString(temp, csv, StandardCharsets.UTF_8);
            byte[] bytes = Files.readAllBytes(temp);
            check("落盘首三字节是 EF BB BF（Excel 据此认 UTF-8）",
                    bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                            && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF,
                    "实际 " + (bytes.length >= 3 ? String.format("%02X %02X %02X",
                            bytes[0], bytes[1], bytes[2]) : "长度不足"));

            String readBack = CsvParser.readText(temp);
            check("读回来没有多余 BOM 字符", !readBack.startsWith("\uFEFF"), "BOM 未被剥掉");
            CsvParser.Table table = CsvParser.parse(readBack);
            check("中文表头往返不乱码",
                    table.headers.contains("状态") && table.headers.contains("响应预览"),
                    String.join("|", table.headers));
            check("未执行的行在导出的状态列里显示「等待中」",
                    "等待中".equals(table.rows.get(0).getOrDefault("状态", "")),
                    table.rows.get(0).getOrDefault("状态", ""));
        } finally {
            Files.deleteIfExists(temp);
        }

        // ---- 响应预览缓存 ----
        HttpEngine.Result fake = new HttpEngine.Result();
        fake.status = 200;
        fake.body = "  {\n  \"a\": 1\n}  ";
        first.setResult(fake);
        check("预览把连续空白折成单行",
                "{ \"a\": 1 }".equals(first.responsePreview()), first.responsePreview());
        check("预览结果被缓存（重复取值不重新折叠）",
                first.responsePreview() == first.responsePreview(), "两次拿到不同实例");

        batch.shutdown();
    }

    private static String headerValue(List<Map.Entry<String, String>> headers, String key) {
        for (Map.Entry<String, String> e : headers) {
            if (key.equalsIgnoreCase(e.getKey())) {
                return e.getValue() == null ? "" : e.getValue();
            }
        }
        return "";
    }

    /** 请求体类型推断：只按 NONE 处理会让用户粘完 JSON 忘了选类型时静默丢 body。 */
    private void bodyTypeInference() {
        check("NONE + JSON 内容 → 推断为 JSON",
                HttpRequestSpec.inferBodyType(HttpRequestSpec.BodyType.NONE, "{\"a\":1}")
                        == HttpRequestSpec.BodyType.JSON, "未推断");
        check("NONE + 非 JSON 内容 → 推断为 TEXT",
                HttpRequestSpec.inferBodyType(HttpRequestSpec.BodyType.NONE, "abc=1")
                        == HttpRequestSpec.BodyType.TEXT, "未推断");
        check("NONE + 空内容 → 保持 NONE",
                HttpRequestSpec.inferBodyType(HttpRequestSpec.BodyType.NONE, "   ")
                        == HttpRequestSpec.BodyType.NONE, "空内容不应推断出类型");
        check("显式选定的类型不被覆盖",
                HttpRequestSpec.inferBodyType(HttpRequestSpec.BodyType.XML, "{\"a\":1}")
                        == HttpRequestSpec.BodyType.XML, "显式类型被改写");

        HttpRequestSpec inferred = new HttpRequestSpec(HttpRequestSpec.Method.POST, "http://x",
                HttpRequestSpec.inferBodyType(HttpRequestSpec.BodyType.NONE, "{\"a\":1}"),
                "{\"a\":1}");
        check("推断后请求体不会再被丢掉", inferred.hasBody(), "hasBody 仍为 false");
    }

    /**
     * 界面联动：类型选「无请求体」时编辑区进入降级态，切到 JSON 后复员。
     *
     * <p>断言落在<b>渲染结果</b>上（整组透明度 + 可编辑性），不落在「有没有调
     * {@code setDisable}」—— 设计稿 §06-5 的不变量明确要求降级不能只做
     * {@code setDisable}：那样只把文本压到约 19%，旁边的标签仍是全黑，
     * 用户看到的就是「控件坏了」（批处理页线程数原文如此，实测标签
     * {@code (90,96,104)} 对数值 {@code (209,211,215)}）。
     */
    private void bodyAreaBinding() {
        SingleRequestPanel panel = new SingleRequestPanel();
        TextArea area = panel.bodyAreaForTest();
        javafx.scene.Node holder = panel.editorHolderForTest();

        check("默认「无请求体」时编辑区整组进入降级态（整组降透明）",
                degraded(holder, area), "holder.opacity=" + holder.getOpacity());
        check("默认「无请求体」时显示的是空态，而不是一个灰掉的输入框",
                panel.visibleEditorForTest() != null
                        && !(panel.visibleEditorForTest() instanceof TextArea),
                "可见编辑器 = " + panel.visibleEditorForTest());

        area.setText("{\"a\":1}");
        panel.selectBodyTypeForTest(HttpRequestSpec.BodyType.JSON);
        check("切到 JSON 后编辑区复员（不透明且可编辑）",
                !degraded(holder, area) && area.isEditable(),
                "opacity=" + holder.getOpacity() + " editable=" + area.isEditable());
        check("切到 JSON 后可见编辑器就是那个文本区",
                panel.visibleEditorForTest() == area,
                "可见编辑器 = " + panel.visibleEditorForTest());

        panel.selectBodyTypeForTest(HttpRequestSpec.BodyType.NONE);
        check("切回「无请求体」后编辑区再次降级", degraded(holder, area),
                "opacity=" + holder.getOpacity());
        check("切回「无请求体」后内容仍然保留（不是清空）",
                "{\"a\":1}".equals(area.getText()), "文本区 = " + area.getText());
        panel.shutdown();
    }

    /**
     * 降级态判定：<b>整组</b>降到设计稿规定的透明度，且其中的编辑器不再可编辑。
     *
     * <p>注意读的是容器（editorHolder）的不透明度而不是文本区自己的 ——
     * 降级的对象是「这一组」，文本区的 opacity 始终是 1。
     */
    private static boolean degraded(javafx.scene.Node holder, TextArea area) {
        return Math.abs(holder.getOpacity() - UiKit.DEGRADE_OPACITY) < 1e-9
                && !area.isEditable();
    }
}
