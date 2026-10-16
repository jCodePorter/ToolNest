package com.bytefuture.toolnest.features.http;

import atlantafx.base.theme.PrimerLight;
import com.bytefuture.toolnest.app.ToolRegistry;
import com.bytefuture.toolnest.ui.components.KeyValueTable;
import com.bytefuture.toolnest.ui.shell.ContentPane;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * HTTP <b>单一请求页</b>重设计的端到端探针（对应
 * {@code design/http-single-redesign-spec.md} §9 的阶段 1–5 验收）。
 *
 * <h2>为什么必须跑真实页面而不是裸面板</h2>
 * 本页的核心验收项全是「竖向预算」：chrome = 64、请求页签 = 312、编辑器 = 210、
 * 响应页签 132 → 488。这些数字只有在
 * {@code ContentPane → detail-wrapper(BorderPane) → detail-scroll(ScrollPane,
 * fitToHeight + minHeight=USE_PREF_SIZE) → module-body(16/24 内边距) →
 * http-main-tabs(TabPane) → SingleRequestPanel} 这条真实链路上才会出现 ——
 * 外层 ScrollPane 决定内容拿到多少高，TabPane 决定页签轨道吃掉多少高，
 * 裸面板既没有页头也没有页签轨道，量出来的数一个都对不上。
 *
 * <p>因此这里沿用 {@code httpsect} 的骨架：真实 {@link ContentPane} +
 * 真实 {@link HttpToolModule}，窗口按设计口径 <b>988 × 763</b>
 * （1266 窗宽 − 侧边栏 267 − 竖滚动条 11 = 988 内容宽；内容高 = 763）。
 *
 * <h2>引擎侧断言为什么不打桩</h2>
 * 「Form 真的编码了」「multipart 真的把文件字节发出去了」是<b>字节层面</b>的事实。
 * 打桩只能证明「我调用了那个方法」，证明不了「服务端收到的是这个」。
 * 所以 {@link TinyHttpServer} 收真实 socket 请求，断言直接落在
 * {@code lastBody()} 与收到的 {@code Content-Type} 头之上。
 */
public class HttpSingleUiProbe extends Application {

    private static int passed = 0;
    private static int failed = 0;
    private static final List<String> cssErrors = new ArrayList<>();
    private static final List<String> problems = new ArrayList<>();

    /** 设计评审口径下的内容区尺寸（见类注释）。 */
    private static final double CONTENT_W = 988;
    private static final double CONTENT_H = 763;

    // ---- 设计冻结的高度（design/http-single-redesign-spec.md §2）----
    private static final double CHROME = 64;
    private static final double CHROME_OLD = 134;
    private static final double REQ_TABS_H = 312;
    private static final double RESP_TABS_MIN_H = 132;
    private static final double RESP_TABS_FULL_H = 488;
    private static final double EDITOR_H = 210;
    private static final double STRIP_H = 46;

    // ---- 表格口径：表头 40 / 行高 34（DESIGN.md §5）----
    private static final double TABLE_HEADER_H = 40;
    private static final double ROW_H = 34;

    /** {@code -dt-accent}：一屏只允许一处实底主色。 */
    private static final Color ACCENT = Color.web("#2563eb");

    private Scene scene;
    private ContentPane pane;
    private HttpToolModule module;
    private SingleRequestPanel panel;

    // ================================================================ 断言

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name + "   " + detail);
        } else {
            failed++;
            System.out.println("FAIL  " + name + "  → " + detail);
        }
    }

    private static String rect(Node node) {
        Bounds b = sceneBounds(node);
        return String.format("[%.0f,%.0f %.0f×%.0f]",
                b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight());
    }

    private static Bounds sceneBounds(Node node) {
        return node.localToScene(node.getBoundsInLocal());
    }

    private void relayout() {
        scene.getRoot().applyCss();
        layoutDeep(scene.getRoot());
        scene.getRoot().applyCss();
        layoutDeep(scene.getRoot());
    }

    /**
     * 自上而下逐个 Parent 排一遍。
     *
     * <p>只调一次 {@code root.layout()} 不够：{@code Parent.layout()} 在自身
     * layout flag 为 CLEAN 时不下钻子树，量出来的子节点高度还是上一帧的残值。
     */
    private static void layoutDeep(Parent node) {
        node.layout();
        for (Node child : node.getChildrenUnmodifiable()) {
            if (child instanceof Parent childParent) {
                layoutDeep(childParent);
            }
        }
    }

    // ================================================================ 查节点

    @SuppressWarnings("unchecked")
    private static <T> T findFirst(Parent root, Class<T> type) {
        for (Node child : root.getChildrenUnmodifiable()) {
            if (type.isInstance(child)) {
                return (T) child;
            }
            if (child instanceof Parent parent) {
                T found = findFirst(parent, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** 按样式类找第一个节点（比 lookup 稳：不依赖 CSS 引擎的匹配时机）。 */
    private static Node findFirstByStyle(Parent root, String style) {
        for (Node child : root.getChildrenUnmodifiable()) {
            if (child.getStyleClass().contains(style)) {
                return child;
            }
            if (child instanceof Parent parent) {
                Node found = findFirstByStyle(parent, style);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean containsButtonText(Parent root, String text) {
        for (Node child : root.getChildrenUnmodifiable()) {
            if (child instanceof Button button && text.equals(button.getText())) {
                return true;
            }
            if (child instanceof Parent parent && containsButtonText(parent, text)) {
                return true;
            }
        }
        return false;
    }

    /** 可见且参与布局的子节点数量。 */
    private static int visibleChildCount(Parent parent) {
        int n = 0;
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child.isVisible() && child.isManaged()) {
                n++;
            }
        }
        return n;
    }

    // ================================================================ 行数口径

    /** 当前字号下一行文字的真实高度 —— 不写死常数，字号一改就跟着变。 */
    private static double lineHeightOf(Font font) {
        Text probe = new Text("Ag");
        probe.setFont(font);
        return probe.getLayoutBounds().getHeight();
    }

    /**
     * TextArea 能完整显示几行。
     *
     * <p>量的是内部 {@code .content} 文本容器的高度，不是 TextArea 外框 ——
     * 外框还含着边框与内边距，拿它除以行高会把这部分算成半行。
     */
    private int textRows(TextArea area) {
        Node content = area.lookup(".content");
        double textH = content instanceof Region region
                ? region.getHeight()
                : area.getHeight() - 6;
        return (int) Math.floor(textH / lineHeightOf(area.getFont()));
    }

    private static double headerHeight(TableView<?> table) {
        Node header = table.lookup(".column-header-background");
        if (header instanceof Region region && region.getHeight() > 0) {
            return region.getHeight();
        }
        return TABLE_HEADER_H;
    }

    /** 表体去掉表头后能完整显示几行。 */
    private static int tableRows(double tableHeight, double headerHeight) {
        return (int) Math.floor((tableHeight - headerHeight) / ROW_H);
    }

    /**
     * 外层滚动容器是否真的溢出：内容自然高 &gt; 视口高。
     *
     * <p>不能用 {@code getVmax()}：探针是手动 layout 的（没有真实 pulse），
     * vmax 一直停在默认值，滚动条的 visible 标志也不更新，量出来恒为「不滚」。
     */
    /**
     * 内容是否真的撑出了视口。
     *
     * <p>容差 2px：窗口按 763 建，实际场景高是 <b>763.33</b>（Windows 缩放取整），
     * 于是视口比设计口径矮 0.33px，账面上恒溢出 1.3px —— 那是小数像素的账，
     * 不是布局缺陷。判据取「超过 2px 才算溢出」，既能放过亚像素抖动，
     * 又仍然抓得住真实回归（往工具行里塞一个 38px 的控件立刻就是 +38）。
     */
    private static boolean overflows(ScrollPane outer, Parent inner) {
        return inner.getLayoutBounds().getHeight()
                > outer.getViewportBounds().getHeight() + 2;
    }

    // ================================================================ 主流程

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
        ToolRegistry.bootstrap();

        try (TinyHttpServer server = new TinyHttpServer()) {
            // 引擎侧先跑：它不依赖界面，失败也不会连带把下面的 UI 断言全带红
            enginePacking(server);
            buildAndMeasure(server);
        } catch (Exception ex) {
            failed++;
            System.out.println("FAIL  探针自身异常 → " + ex);
            ex.printStackTrace(System.out);
        }

        check("未出现未捕获异常", problems.isEmpty(),
                problems.isEmpty() ? "" : String.join(" | ", problems));

        System.out.println();
        System.out.println("RESULT passed=" + passed + " failed=" + failed);
        if (!problems.isEmpty()) {
            System.out.println("（捕获到 " + problems.size() + " 条未捕获异常）");
            for (String p : problems) {
                System.out.println("   " + p);
            }
        }
        Platform.exit();
    }

    // ================================================================ 引擎组包

    /**
     * 阶段 4 的验收：Form 真编码 / multipart 真组包 / Binary 走文件流
     * —— 全部落在本地服务器收到的真实字节上。
     */
    private void enginePacking(TinyHttpServer server) throws Exception {
        // ---------- M7 · Form 的百分号编码 ----------
        server.serve("/form", "text/plain; charset=utf-8", "ok".getBytes(StandardCharsets.UTF_8));
        HttpRequestSpec formSpec = new HttpRequestSpec(
                HttpRequestSpec.Method.POST, server.baseUrl() + "/form",
                HttpRequestSpec.BodyType.FORM, "",
                List.of(new AbstractMap.SimpleEntry<>("城市", "北京 上海"),
                        new AbstractMap.SimpleEntry<>("n", "1")),
                List.of(), null);
        HttpEngine.send(formSpec, List.of(), List.of(), Duration.ofSeconds(10));
        byte[] formBytes = server.lastBody();
        String formBody = new String(formBytes, StandardCharsets.UTF_8);

        check("Form 请求体按 x-www-form-urlencoded 编码（百分号序列与规格一致）",
                "%E5%9F%8E%E5%B8%82=%E5%8C%97%E4%BA%AC+%E4%B8%8A%E6%B5%B7&n=1".equals(formBody),
                "服务端收到「" + formBody + "」");
        check("Form 请求体里不再出现未编码的中文原文",
                !formBody.contains("城市") && !formBody.contains("北京"),
                "服务端收到「" + formBody + "」");
        check("Form 的 Content-Type 与所选类型一致",
                "application/x-www-form-urlencoded; charset=UTF-8"
                        .equals(headerOf(server, "content-type")),
                String.valueOf(headerOf(server, "content-type")));
        // 空格必须是「+」而不是 %20：这个媒体类型里 %20 会被服务端当成字面量
        check("空格编码为「+」而不是「%20」",
                formBody.contains("+%E4%B8%8A%E6%B5%B7") && !formBody.contains("%20"),
                formBody);

        // ---------- M8 · multipart 真的把文件字节发出去 ----------
        String payload = "TOOLNEST-BINARY-PAYLOAD-42";
        Path file = Files.createTempFile("toolnest-upload", ".txt");
        Files.writeString(file, payload, StandardCharsets.UTF_8);
        java.io.File asFile = file.toFile();

        server.serve("/upload", "text/plain; charset=utf-8", "ok".getBytes(StandardCharsets.UTF_8));
        HttpRequestSpec multipartSpec = new HttpRequestSpec(
                HttpRequestSpec.Method.POST, server.baseUrl() + "/upload",
                HttpRequestSpec.BodyType.FORM_DATA, "", List.of(),
                List.of(HttpRequestSpec.FormField.text("remark", "标签"),
                        HttpRequestSpec.FormField.file("avatar", file.toString())),
                null);
        HttpEngine.Result upload =
                HttpEngine.send(multipartSpec, List.of(), List.of(), Duration.ofSeconds(10));
        String uploadType = headerOf(server, "content-type");
        String multipartBody = new String(server.lastBody(), StandardCharsets.UTF_8);
        String boundary = uploadType != null && uploadType.contains("boundary=")
                ? uploadType.substring(uploadType.indexOf("boundary=") + "boundary=".length()).trim()
                : "";

        check("multipart 的 Content-Type 带 boundary",
                uploadType != null && uploadType.startsWith("multipart/form-data; boundary=")
                        && !boundary.isBlank(),
                String.valueOf(uploadType));
        check("请求体里出现该 boundary 的分隔行",
                !boundary.isBlank() && multipartBody.contains("--" + boundary),
                "boundary=" + boundary);
        check("文本段带 name=\"remark\"",
                multipartBody.contains("Content-Disposition: form-data; name=\"remark\""),
                firstLine(multipartBody));
        check("文件段带 name=\"avatar\" 与 filename（缺一服务端就收不到文件）",
                multipartBody.contains("Content-Disposition: form-data; name=\"avatar\"")
                        && multipartBody.contains("filename=\"" + asFile.getName() + "\""),
                firstLine(multipartBody));
        check("文件段按扩展名带 Content-Type: text/plain",
                multipartBody.contains("Content-Type: text/plain"),
                firstLine(multipartBody));
        check("文件真实字节进了请求体",
                multipartBody.contains(payload), "未找到 " + payload);
        check("文本段的 UTF-8 内容进了请求体",
                multipartBody.contains("标签"), firstLine(multipartBody));
        check("以 --boundary-- 正确收尾",
                multipartBody.trim().endsWith("--" + boundary + "--"),
                lastLine(multipartBody));
        check("「请求详情」里的 multipart 是人读版本（摊开真实字节毫无意义）",
                upload.requestBody != null
                        && upload.requestBody.contains("[文件内容")
                        && !upload.requestBody.contains(payload),
                firstLine(String.valueOf(upload.requestBody)));

        // ---------- Binary：整份文件原样发出 ----------
        server.serve("/bin", "text/plain; charset=utf-8", "ok".getBytes(StandardCharsets.UTF_8));
        HttpRequestSpec binarySpec = new HttpRequestSpec(
                HttpRequestSpec.Method.POST, server.baseUrl() + "/bin",
                HttpRequestSpec.BodyType.BINARY, "", List.of(), List.of(), file.toString());
        HttpEngine.send(binarySpec, List.of(), List.of(), Duration.ofSeconds(10));
        check("Binary 请求体等于文件原始字节",
                payload.equals(new String(server.lastBody(), StandardCharsets.UTF_8)),
                "服务端收到 " + server.lastBody().length + " 字节");
        check("Binary 的 Content-Type 是 application/octet-stream",
                "application/octet-stream".equals(headerOf(server, "content-type")),
                String.valueOf(headerOf(server, "content-type")));

        Files.deleteIfExists(file);

        // ---------- 枚举扩展（Q3=A / Q4=A）----------
        check("Method 补齐 PATCH 且允许请求体",
                HttpRequestSpec.Method.PATCH.allowsBody()
                        && !HttpRequestSpec.Method.GET.allowsBody()
                        && HttpRequestSpec.Method.DELETE.allowsBody(),
                "PATCH=" + HttpRequestSpec.Method.PATCH.allowsBody()
                        + " GET=" + HttpRequestSpec.Method.GET.allowsBody());
        List<HttpRequestSpec.BodyType> batch = HttpRequestSpec.BodyType.batchTypes();
        check("批处理页的可选类型不含 Form-data / Binary（Q3=A：只有路径没有值）",
                !batch.contains(HttpRequestSpec.BodyType.FORM_DATA)
                        && !batch.contains(HttpRequestSpec.BodyType.BINARY)
                        && batch.containsAll(List.of(HttpRequestSpec.BodyType.NONE,
                        HttpRequestSpec.BodyType.JSON, HttpRequestSpec.BodyType.FORM,
                        HttpRequestSpec.BodyType.TEXT, HttpRequestSpec.BodyType.XML)),
                batch.toString());
    }

    private static String headerOf(TinyHttpServer server, String name) {
        for (String line : server.lastRequest()) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "null";
        }
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }

    private static String lastLine(String text) {
        String[] lines = text.split("\n");
        return lines.length == 0 ? "" : lines[lines.length - 1].trim();
    }

    // ================================================================ 界面

    private void buildAndMeasure(TinyHttpServer server) throws Exception {
        module = new HttpToolModule();
        pane = new ContentPane();
        pane.openDetail(module);

        scene = new Scene(pane, CONTENT_W, CONTENT_H);
        scene.getStylesheets().add(
                HttpSingleUiProbe.class.getResource("/css/app.css").toExternalForm());
        Stage stage = new Stage();
        stage.setScene(scene);
        stage.show();
        relayout();

        panel = findFirst(pane, SingleRequestPanel.class);
        if (panel == null) {
            System.out.println("FAIL  找不到单一请求面板 SingleRequestPanel");
            return;
        }
        Node header = findFirstByStyle(pane, "page-header");
        Node moduleBody = findFirstByStyle(pane, "module-body");
        Node scroller = findFirstByStyle(pane, "detail-scroll");

        System.out.println("[dbg] 场景 " + scene.getWidth() + "×" + scene.getHeight()
                + "  页头=" + rect(header) + "  正文=" + rect(moduleBody));
        System.out.println("[dbg] 面板=" + rect(panel));
        javafx.scene.control.TabPane mainTabs = findFirst(pane,
                javafx.scene.control.TabPane.class);
        System.out.println("[dbg] 外层页签=" + rect(mainTabs)
                + " pref=" + String.format("%.1f", mainTabs.prefHeight(-1))
                + " 选中页正文=" + rect((Node) mainTabs.getTabs().get(0).getContent()));
        System.out.println("[dbg] 面板 pref=" + String.format("%.1f", panel.prefHeight(-1))
                + " | 请求块=" + rect(panel.requestBlockForTest())
                + " pref=" + String.format("%.1f",
                ((Region) panel.requestBlockForTest()).prefHeight(-1)));
        System.out.println("[dbg] 请求页签=" + rect(panel.requestTabsForTest())
                + " pref=" + String.format("%.1f", panel.requestTabsForTest().prefHeight(-1)));
        System.out.println("[dbg] 响应页签=" + rect(panel.responseTabsForTest())
                + " pref=" + String.format("%.1f", panel.responseTabsForTest().prefHeight(-1))
                + " min=" + String.format("%.1f", panel.responseTabsForTest().minHeight(-1)));

        // ==================== A. 几何与竖向预算 ====================
        double chrome = sceneBounds(panel).getMinY() - sceneBounds(header).getMaxY();
        check("阶段1 · chrome（详情页头之后到第一块内容）=" + (int) CHROME + "px",
                Math.abs(chrome - CHROME) < 2,
                "实测 " + String.format("%.1f", chrome) + "px（改造前 " + (int) CHROME_OLD + "）");

        check("阶段1 · 单一请求面板拿到整块可用高（598 = 678 − 16 − 48 − 16）",
                panel.getHeight() >= 590,
                "面板高 " + String.format("%.1f", panel.getHeight()));

        double reqTabsH = panel.requestTabsForTest().getHeight();
        check("阶段1 · 请求页签实际高 = " + (int) REQ_TABS_H,
                reqTabsH >= REQ_TABS_H - 0.5,
                "实测 " + String.format("%.1f", reqTabsH));
        check("阶段1 · 请求体编辑器槽 = " + (int) EDITOR_H,
                Math.abs(panel.editorHolderForTest().getLayoutBounds().getHeight() - EDITOR_H) < 1,
                "实测 " + String.format("%.1f",
                        panel.editorHolderForTest().getLayoutBounds().getHeight()));
        check("阶段1 · 配置态响应页签 ≥ " + (int) RESP_TABS_MIN_H,
                panel.responseTabsForTest().getHeight() >= RESP_TABS_MIN_H - 0.5,
                "实测 " + String.format("%.1f", panel.responseTabsForTest().getHeight()));
        boolean overflows = scroller instanceof ScrollPane outer
                && moduleBody instanceof Parent body && overflows(outer, body);
        double viewportH = scroller instanceof ScrollPane outer
                ? outer.getViewportBounds().getHeight() : -1;
        check("阶段1 · 配置态首屏不出现外层滚动条（内容装得下就不滚）",
                scroller instanceof ScrollPane && !overflows,
                "正文高 " + String.format("%.1f", moduleBody.getLayoutBounds().getHeight())
                        + " 视口高 " + String.format("%.1f", viewportH));
        check("阶段1 · 响应页签底边落在视口内（不被切掉）",
                sceneBounds(panel.responseTabsForTest()).getMaxY() <= CONTENT_H + 0.5,
                "底边 y=" + String.format("%.1f", sceneBounds(panel.responseTabsForTest()).getMaxY())
                        + "（视口高 " + (int) CONTENT_H + "）");
        snapshot("single-config");

        // ==================== B. 阶段 3 · 类型 → 编辑器 ====================
        System.out.println();
        for (HttpRequestSpec.BodyType type : HttpRequestSpec.BodyType.values()) {
            panel.selectBodyTypeForTest(type);
            relayout();
            Node visible = panel.visibleEditorForTest();
            // 编辑器槽里永远只有一个可见节点 —— 多一个就是两个编辑器叠在一起
            check("阶段3 · 类型「" + type.label() + "」的编辑器槽里恰好一个可见节点",
                    visible != null && visibleChildCount((Parent) panel.editorHolderForTest()) == 1,
                    "可见 " + describe(visible) + "，槽内可见数="
                            + visibleChildCount((Parent) panel.editorHolderForTest()));
        }

        panel.selectBodyTypeForTest(HttpRequestSpec.BodyType.NONE);
        relayout();
        Node noneEditor = panel.visibleEditorForTest();
        check("阶段3 · 「无」显示空态（不是灰掉的输入框）",
                noneEditor != null && noneEditor.getStyleClass().contains("empty-state")
                        && noneEditor != panel.bodyAreaForTest(),
                describe(noneEditor));

        for (HttpRequestSpec.BodyType textType : List.of(HttpRequestSpec.BodyType.JSON,
                HttpRequestSpec.BodyType.XML, HttpRequestSpec.BodyType.TEXT)) {
            panel.selectBodyTypeForTest(textType);
            relayout();
            check("阶段3 · 类型「" + textType.label() + "」复用同一个等宽文本区（切换不丢内容）",
                    panel.visibleEditorForTest() == panel.bodyAreaForTest(),
                    describe(panel.visibleEditorForTest()));
        }
        // 文本区三种类型共用 → 内容独立于类型保留（httpfix 已断言，这里确认节点是同一个）
        panel.bodyAreaForTest().setText("{\"keep\":1}");
        panel.selectBodyTypeForTest(HttpRequestSpec.BodyType.XML);
        panel.selectBodyTypeForTest(HttpRequestSpec.BodyType.JSON);
        relayout();
        check("阶段3 · JSON / XML 之间来回切，内容不丢",
                "{\"keep\":1}".equals(panel.bodyAreaForTest().getText()),
                "文本区 = " + panel.bodyAreaForTest().getText());
        panel.bodyAreaForTest().setText("");

        // Form 与 Form-data 现在共用同一张表（Postman 的做法）：
        // 两个类型选项、同一份内容，区别只是「行里能不能选文件」。
        panel.selectBodyTypeForTest(HttpRequestSpec.BodyType.FORM);
        relayout();
        check("阶段3 · Form 用字段表（不是文本框）",
                panel.visibleEditorForTest() == panel.formDataTableForTest(),
                describe(panel.visibleEditorForTest()));
        check("阶段6 · Form 与 Form-data 共用同一个编辑器节点（不再各有一张表）",
                editorFor(HttpRequestSpec.BodyType.FORM)
                        == editorFor(HttpRequestSpec.BodyType.FORM_DATA),
                "FORM=" + describe(editorFor(HttpRequestSpec.BodyType.FORM))
                        + " FORM_DATA=" + describe(editorFor(HttpRequestSpec.BodyType.FORM_DATA)));
        // editorFor() 内部会切类型（不切就拿不到节点），所以断言完要切回 Form
        panel.selectBodyTypeForTest(HttpRequestSpec.BodyType.FORM);
        relayout();
        check("阶段6 · Form 模式隐藏「类型」列（x-www-form-urlencoded 传不了文件）",
                !panel.formDataTableForTest().isFileRowsAllowed()
                        && !columnVisible(panel.formDataTableForTest(), "类型"),
                "fileRowsAllowed=" + panel.formDataTableForTest().isFileRowsAllowed()
                        + " 类型列可见=" + columnVisible(panel.formDataTableForTest(), "类型"));

        // M2：键值表可见行数 152 → 210
        TableView<?> formTableView = findFirst(panel.formDataTableForTest(), TableView.class);
        double formTableH = formTableView == null ? 0 : formTableView.getHeight();
        double formHeaderH = formTableView == null ? TABLE_HEADER_H : headerHeight(formTableView);
        int formRows = tableRows(formTableH, formHeaderH);
        check("阶段3 · M2 键值表 ≥ 5 行可见（表高 ≥ 210）",
                formTableH >= EDITOR_H - 0.5 && formRows >= 5,
                "表高 " + String.format("%.1f", formTableH)
                        + " 表头 " + String.format("%.1f", formHeaderH)
                        + " → " + formRows + " 行");

        panel.selectBodyTypeForTest(HttpRequestSpec.BodyType.FORM_DATA);
        relayout();
        check("阶段3 · Form-data 用逐行「文本 / 文件」表",
                panel.visibleEditorForTest() == panel.formDataTableForTest(),
                describe(panel.visibleEditorForTest()));
        check("阶段3 · Form-data 表存在「类型」列（文本 / 文件的入口）",
                findFirst(panel.formDataTableForTest(), TableView.class) != null
                        && hasColumn(findFirst(panel.formDataTableForTest(), TableView.class), "类型"),
                "列 = " + columnsOf(findFirst(panel.formDataTableForTest(), TableView.class)));
        check("阶段6 · Form-data 模式显示「类型」列",
                panel.formDataTableForTest().isFileRowsAllowed()
                        && columnVisible(panel.formDataTableForTest(), "类型"),
                "fileRowsAllowed=" + panel.formDataTableForTest().isFileRowsAllowed()
                        + " 类型列可见=" + columnVisible(panel.formDataTableForTest(), "类型"));

        panel.selectBodyTypeForTest(HttpRequestSpec.BodyType.BINARY);
        relayout();
        Node binaryEditor = panel.visibleEditorForTest();
        check("阶段3 · Binary 用单文件选择器（不是文本框）",
                binaryEditor instanceof Parent parent
                        && binaryEditor != panel.bodyAreaForTest()
                        && containsButtonText(parent, "选择文件"),
                describe(binaryEditor));

        // M3：请求体文本区可见行数 127 → 210
        panel.selectBodyTypeForTest(HttpRequestSpec.BodyType.JSON);
        relayout();
        int bodyRows = textRows(panel.bodyAreaForTest());
        check("阶段3 · M3 请求体文本区 ≥ 11 行可见（210px）",
                panel.bodyAreaForTest().getHeight() >= EDITOR_H - 0.5 && bodyRows >= 11,
                "文本区高 " + String.format("%.1f", panel.bodyAreaForTest().getHeight())
                        + " → " + bodyRows + " 行");

        // ==================== C. 阶段 2 · 动作槽按类型显隐 ====================
        System.out.println();
        check("阶段2 · 分段控件为 7 个类型各建了一个项",
                allTypesHaveButtons(), "缺失项见上方逐类型断言");
        check("阶段2 · JSON 的动作槽只有「格式化」",
                actionTexts(HttpRequestSpec.BodyType.JSON).equals(List.of("格式化")),
                actionTexts(HttpRequestSpec.BodyType.JSON).toString());
        check("阶段2 · XML 的动作槽为空（旧版此处的「格式化」点了没反应）",
                actionTexts(HttpRequestSpec.BodyType.XML).isEmpty(),
                actionTexts(HttpRequestSpec.BodyType.XML).toString());
        check("阶段2 · Text 的动作槽为空",
                actionTexts(HttpRequestSpec.BodyType.TEXT).isEmpty(),
                actionTexts(HttpRequestSpec.BodyType.TEXT).toString());
        check("阶段2 · Form 的动作槽是「+ 新增字段 / 清空」",
                actionTexts(HttpRequestSpec.BodyType.FORM)
                        .equals(List.of("+ 新增字段", "清空")),
                actionTexts(HttpRequestSpec.BodyType.FORM).toString());
        check("阶段2 · Binary 的动作槽是「更换 / 清除」",
                actionTexts(HttpRequestSpec.BodyType.BINARY).equals(List.of("更换", "清除")),
                actionTexts(HttpRequestSpec.BodyType.BINARY).toString());

        // ==================== D. 阶段 5 · 两条守卫 ====================
        System.out.println();
        panel.selectBodyTypeForTest(HttpRequestSpec.BodyType.FORM);
        panel.setHeadersForTest(new java.util.LinkedHashMap<>());
        relayout();
        Label ctype = panel.ctypeLabelForTest();
        check("阶段5 · 无冲突时工具行显示实际 Content-Type 且无警告",
                ctype.isVisible()
                        && "application/x-www-form-urlencoded; charset=UTF-8".equals(ctype.getText())
                        && !panel.guardTextForTest().isVisible(),
                "ctype=「" + ctype.getText() + "」 guard 可见="
                        + panel.guardTextForTest().isVisible());

        panel.setHeadersForTest(Map.of("Content-Type", "application/json"));
        relayout();
        Label guard = panel.guardTextForTest();
        check("阶段5 · 守卫①：Content-Type 冲突时明说事实（含冲突值与本页类型）",
                guard.isVisible()
                        && guard.getText().contains("application/json")
                        && guard.getText().contains("Form")
                        && guard.getText().contains("将按请求头发送"),
                "「" + guard.getText() + "」");
        check("阶段5 · 守卫①：冲突时不改用户的头（只提示）",
                "application/json".equals(headerValueOf(panel, "Content-Type")),
                "请求头里仍是 " + headerValueOf(panel, "Content-Type"));
        check("阶段5 · 守卫①：冲突时不显示 Content-Type 常驻位（避免自相矛盾）",
                !ctype.isVisible(), "ctype 可见=" + ctype.isVisible());

        panel.setHeadersForTest(new java.util.LinkedHashMap<>());
        panel.selectBodyTypeForTest(HttpRequestSpec.BodyType.NONE);
        panel.bodyAreaForTest().setText("{\"a\":1}");
        panel.selectBodyTypeForTest(HttpRequestSpec.BodyType.NONE);
        relayout();
        check("阶段5 · 守卫②：类型选「无」而编辑区有内容时把推断说出来",
                guard.isVisible() && guard.getText().contains("本次按 JSON 发送")
                        && guard.getText().contains("编辑区有内容"),
                "「" + guard.getText() + "」");
        check("阶段5 · 守卫②：提示可见时编辑器仍是空态（推断只影响发送，不改界面）",
                panel.visibleEditorForTest() != null
                        && panel.visibleEditorForTest().getStyleClass().contains("empty-state"),
                describe(panel.visibleEditorForTest()));

        // 守卫③：GET + 非空请求体（不阻断，只提示）
        panel.methodBoxForTest().getSelectionModel().select(HttpRequestSpec.Method.GET);
        relayout();
        check("阶段5 · GET 带请求体时给信息级提示（不阻断）",
                panel.methodBodyTipForTest().isVisible()
                        && panel.methodBodyTipForTest().getText().contains("GET"),
                "「" + panel.methodBodyTipForTest().getText() + "」");
        panel.methodBoxForTest().getSelectionModel().select(HttpRequestSpec.Method.POST);
        relayout();
        check("阶段5 · POST 带请求体不再提示",
                !panel.methodBodyTipForTest().isVisible(),
                "可见=" + panel.methodBodyTipForTest().isVisible());

        // ==================== E. 一屏只有一处主色实底 ====================
        System.out.println();
        panel.setHeadersForTest(new java.util.LinkedHashMap<>());
        panel.bodyAreaForTest().setText("");
        panel.selectBodyTypeForTest(HttpRequestSpec.BodyType.NONE);
        relayout();
        List<Button> accentConfig = accentButtons();
        check("不变量 · 配置态一屏恰好 1 个主色实底按钮（Send）",
                accentConfig.size() == 1,
                "实底按钮 " + accentConfig.size() + " 个：" + buttonTexts(accentConfig));

        // ==================== E.5 三个实测缺陷的回归 ====================
        regressionKeyValueTable();
        regressionFormMerge();
        regressionAddToken();

        // ==================== F. 阶段 5 · 折叠查看态 ====================
        server.serve("/echo", "application/json; charset=utf-8",
                "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        panel.urlFieldForTest().setText(server.baseUrl() + "/echo");
        panel.methodBoxForTest().getSelectionModel().select(HttpRequestSpec.Method.GET);
        relayout();

        int beforeStrip = panel.stripForTest().isVisible() ? 1 : 0;
        panel.sendButtonForTest().fire();
        relayout();

        check("阶段5 · 按下 Send 的一瞬间配置区就折叠（不等响应）",
                !panel.requestBlockForTest().isVisible()
                        && !panel.requestBlockForTest().isManaged(),
                "requestBlock 可见=" + panel.requestBlockForTest().isVisible()
                        + " 参与布局=" + panel.requestBlockForTest().isManaged());
        check("阶段5 · 折叠前摘要条是隐藏的（不是一直占位）",
                beforeStrip == 0, "折叠前可见=" + (beforeStrip == 1));
        // 按下 Send 之后响应区不能再写着「尚未发送请求」—— 空态与顶栏同时
        // 说两件相反的事，比少一个状态更糟
        check("阶段5 · 按下 Send 后响应区一起进入「请求中…」（不再显示「尚未发送请求」）",
                "请求中…".equals(panel.responsePromptForTest()),
                "响应区提示语 =「" + panel.responsePromptForTest() + "」");

        double stripH = panel.stripForTest().getLayoutBounds().getHeight();
        check("阶段5 · 摘要条实际高 = " + (int) STRIP_H,
                Math.abs(stripH - STRIP_H) < 1,
                "实测 " + String.format("%.1f", stripH));

        double respH = panel.responseTabsForTest().getHeight();
        // 页签正文拿到的高度 = TabPane 高 − 页签头（56）。直接量选中页签的内容节点。
        double respBodyH = panel.responseTabsForTest().getTabs().get(0).getContent()
                .getLayoutBounds().getHeight();
        check("阶段5 · 查看态响应页签吃满 = " + (int) RESP_TABS_FULL_H,
                respH >= RESP_TABS_FULL_H - 6,
                "实测 " + String.format("%.1f", respH));
        check("阶段5 · M4 响应内容区 ≥ 432px（24 行）",
                respBodyH >= 432 - 6, "内容区 " + String.format("%.1f", respBodyH));
        check("阶段5 · 查看态配置区让位后仍不出现外层滚动条",
                sceneBounds(panel.responseTabsForTest()).getMaxY() <= CONTENT_H + 0.5,
                "底边 y=" + String.format("%.1f",
                        sceneBounds(panel.responseTabsForTest()).getMaxY()));
        snapshot("single-collapsed");

        List<Button> accentView = accentButtons();
        check("不变量 · 查看态一屏恰好 1 个主色实底按钮（重发）",
                accentView.size() == 1 && accentView.get(0) == panel.resendButtonForTest(),
                "实底按钮 " + accentView.size() + " 个：" + buttonTexts(accentView));

        // 重发：计数 +1 才算真的发出去了
        waitForRequests(server, 1, 8000);
        int beforeResend = server.requestCount();
        panel.resendButtonForTest().fire();
        boolean resent = waitForRequests(server, beforeResend + 1, 8000);
        check("阶段5 · 摘要条「重发」按一下真的又发了一次请求",
                resent, "服务器请求数 " + beforeResend + " → " + server.requestCount());

        // 展开编辑：回到配置态且内容一个都不丢
        panel.urlFieldForTest().setText(server.baseUrl() + "/echo");
        panel.expandButtonForTest().fire();
        relayout();
        check("阶段5 · 「展开编辑」还原配置区且地址仍在",
                panel.requestBlockForTest().isVisible()
                        && panel.requestBlockForTest().isManaged()
                        && panel.urlFieldForTest().getText().endsWith("/echo"),
                "可见=" + panel.requestBlockForTest().isVisible()
                        + " 地址=" + panel.urlFieldForTest().getText());
        check("阶段5 · 展开后摘要条收起",
                !panel.stripForTest().isVisible() && !panel.stripForTest().isManaged(),
                "可见=" + panel.stripForTest().isVisible());

        // 响应真的回来了：提示语必须复位，否则「请求中…」会跟着空白响应体常驻
        HttpEngine.Result fake = new HttpEngine.Result();
        fake.status = 200;
        fake.body = "{\"ok\":true}";
        panel.applyResultForTest(fake);
        relayout();
        check("阶段5 · 收到响应后响应区提示语复位（不残留「请求中…」）",
                "响应内容会显示在这里".equals(panel.responsePromptForTest()),
                "提示语 =「" + panel.responsePromptForTest() + "」");
        panel.clearResponseForTest();
        relayout();
        // Body 页签的内容是个 StackPane(responseEmpty, bodyHolder)；清空后
        // 必须回到空态，否则用户面对的是一块空白区，看不出是「没发过」还是「坏了」
        Parent bodySlot = (Parent) panel.responseTabsForTest().getTabs().get(0).getContent();
        Node empty = bodySlot.getChildrenUnmodifiable().get(0);
        Node holder = bodySlot.getChildrenUnmodifiable().get(1);
        check("阶段5 · 清空响应后回到空态（不是一块空白区）",
                empty.isVisible() && empty.isManaged() && !holder.isVisible(),
                "空态可见=" + empty.isVisible() + " 结果视图可见=" + holder.isVisible());

        // ==================== G. CSS ====================
        check("未出现 CSS 解析错误", cssErrors.isEmpty(),
                cssErrors.isEmpty() ? "" : String.join(" | ", cssErrors));

        module.shutdown();
    }

    // ================================================================ 小工具

    /**
     * 缺陷①「{@code + JSON} 一次填出 5 行」+ 缺陷②「输入后失焦 / 内容消失 /
     * 加不上行」的回归。
     *
     * <p>两者同源：{@code KeyValueTable} 用 {@code TextFieldTableCell}，
     * 编辑期间行模型还是空串，而「补尾行」的判断读的是模型 ——
     * 于是永远判定「末行是空的」（不加行），而一旦提交就补行、
     * 重建单元格（失焦 + 内容丢失）。修复是换成常驻 TextField（写通模型）
     * 并把补行挪到 {@code runLater}。
     */
    private void regressionKeyValueTable() {
        System.out.println();
        System.out.println("---- E.5 键值表三个缺陷的回归 ----");

        KeyValueTable headers = panel.headersTableForTest();
        headers.clear();
        relayout();

        // ---- 缺陷①：+ JSON 只能得到「1 条 + 1 空行」----
        headers.setEntries(Map.of("Content-Type", "application/json"));
        relayout();
        check("缺陷① · 「+ JSON」后总行数恰好 2（1 条 + 1 尾部空行，不是 5 行）",
                headers.rowCountForTest() == 2,
                "行数 = " + headers.rowCountForTest() + "，条目 = " + headers.entryCount());
        check("缺陷① · 「+ JSON」后有效条目恰好 1",
                headers.entryCount() == 1
                        && "application/json".equals(headers.entries().get(0).getValue()),
                "条目 = " + headers.asMap());
        check("缺陷① · 末尾恰好一行空白行（不多不少）",
                headers.rowKeyForTest(headers.rowCountForTest() - 1) != null
                        && headers.rowKeyForTest(headers.rowCountForTest() - 1).isBlank(),
                "末行 KEY = 「" + headers.rowKeyForTest(headers.rowCountForTest() - 1) + "」");

        // 清空也必须回到「1 空行」——旧版 setAll 两行，是同一个毛病的另一处
        headers.clear();
        relayout();
        check("缺陷① · 「清空」后总行数恰好 1（不是 2 行空行）",
                headers.rowCountForTest() == 1 && headers.entryCount() == 0,
                "行数 = " + headers.rowCountForTest() + "，条目 = " + headers.entryCount());

        // ---- 缺陷②：在末行打字，模型立刻更新、并自动补出新行 ----
        headers.clear();
        relayout();
        TextField keyCell = headers.cellFieldForTest(0, 0);
        check("缺陷② · 末行 KEY 是一格常驻输入框（不是「按下才出现的编辑器」）",
                keyCell != null,
                "cell = " + (keyCell == null ? "null" : keyCell.getClass().getSimpleName()));
        if (keyCell != null) {
            keyCell.requestFocus();
            keyCell.setText("X-Token");
            // 同步执行待办的补行。真实路径是 runLater（必须异步，否则同一次按键
            // 事件里的列表改动会把正在输入的格子重建掉）；这里同步跑反而是
            // 更严的考验 —— 焦点能不能扛住同步的列表变更。
            headers.flushPendingAppendForTest();
            relayout();
            check("缺陷② · 打字直接写通行模型（无需回车提交）",
                    "X-Token".equals(headers.rowKeyForTest(0)),
                    "模型 KEY = 「" + headers.rowKeyForTest(0) + "」");
            check("缺陷② · 末行有内容后自动补出一行新的空行",
                    headers.rowCountForTest() == 2,
                    "行数 = " + headers.rowCountForTest());
            // 不直接断言 isFocused()：探针里 Headers 页签并不可见，
            // requestFocus 拿不到焦点，那条断言会恒假（恒假断言比没有断言更危险）。
            // 这里断言真正导致「内容消失」的机制本身：补行之后，
            // 正在输入的那一格还是<b>同一个节点</b>（没被重建销毁）。
            check("缺陷② · 补行后正在输入的那一格仍是同一个节点（没被重建销毁）",
                    keyCell == headers.cellFieldForTest(0, 0),
                    "同一节点=" + (keyCell == headers.cellFieldForTest(0, 0))
                            + " 文字=「" + keyCell.getText() + "」");

            // 连续录入：第二行也必须能自动补第三行
            TextField valueCell = headers.cellFieldForTest(0, 1);
            if (valueCell != null) {
                valueCell.setText("abc");
                headers.flushPendingAppendForTest();
                relayout();
                check("缺陷② · VALUE 列同样写通模型",
                        "abc".equals(headers.rowValueForTest(0)),
                        "模型 VALUE = 「" + headers.rowValueForTest(0) + "」");

                // 空表上点「+ 新增参数」必须真的多一行 —— 它最容易写成
                // 「保证末行是空」而变成空操作（末行本来就空，什么都不做）。
                headers.clear();
                relayout();
                headers.addRow();
                relayout();
                check("缺陷② · 空表上点「+ 新增参数」确实多出一行（不是空操作）",
                        headers.rowCountForTest() == 2,
                        "清空后 1 行 → 点后 " + headers.rowCountForTest() + " 行");

                headers.setEntries(Map.of("K", "v"));
                relayout();
                int beforeAdd = headers.rowCountForTest();
                headers.addRow();
                relayout();
                check("缺陷② · 末行有内容时「+ 新增参数」再补一格",
                        headers.rowCountForTest() == beforeAdd + 1
                                && headers.rowKeyForTest(beforeAdd) != null
                                && headers.rowKeyForTest(beforeAdd).isBlank(),
                        "行数 " + beforeAdd + " → " + headers.rowCountForTest()
                                + " 新行 KEY=「" + headers.rowKeyForTest(beforeAdd) + "」");
            }
        }

        // ---- 缺陷②的另一半：勾选框能取消勾选并真的不参与请求 ----
        headers.clear();
        relayout();
        headers.setEntries(new java.util.LinkedHashMap<>() {{
            put("A", "1");
            put("B", "2");
        }});
        relayout();
        int before = headers.entryCount();
        headers.setRowEnabledForTest(0, false);
        relayout();
        check("缺陷② · 取消勾选后该行不再参与请求",
                before == 2 && headers.entryCount() == 1
                        && headers.entries().get(0).getKey().equals("B"),
                "条目 = " + headers.asMap());

        panel.setHeadersForTest(new java.util.LinkedHashMap<>());
        relayout();
    }

    /**
     * 缺陷③「Form 与 Form-data 合并为同一张表」的回归。
     *
     * <p>Postman 的 Body 类型里 {@code form-data} 与 {@code x-www-form-urlencoded}
     * 是两个并列选项、共用同一张表，区别只是行里能不能选文件。
     * 这里断言三件事：同一个节点、类型列按模式显隐、<b>内容互通</b>。
     */
    private void regressionFormMerge() {
        System.out.println();
        System.out.println("---- E.5 Form / Form-data 合并的回归 ----");

        FormDataTable table = panel.formDataTableForTest();
        table.clear();
        panel.selectBodyTypeForTest(HttpRequestSpec.BodyType.FORM);
        relayout();

        // 在 Form 模式下填两行
        table.setTextFieldsForTest("城市", "北京");
        table.setTextFieldsForTest("n", "1");
        relayout();
        check("缺陷③ · Form 模式录入 2 项",
                table.entryCount() == 2,
                "条目 = " + table.entryCount());

        // 切到 Form-data：同一张表，内容不该丢
        panel.selectBodyTypeForTest(HttpRequestSpec.BodyType.FORM_DATA);
        relayout();
        check("缺陷③ · 切到 Form-data 后内容仍在（同一份数据，不是两张表）",
                table.entryCount() == 2
                        && "北京".equals(table.fields().get(0).text),
                "条目 = " + table.entryCount() + " 首项 = "
                        + (table.fields().isEmpty() ? "无" : table.fields().get(0).text));

        // Form-data 模式下把第一行改成文件段
        table.setFileForTest(0, "avatar", "C:/tmp/a.png");
        relayout();
        check("缺陷③ · Form-data 可把行切成文件段",
                table.fileCount() == 1 && table.entryCount() == 2,
                "文件段 = " + table.fileCount() + " 条目 = " + table.entryCount());

        // 切回 Form：类型列隐藏，文件段落回文本段
        panel.selectBodyTypeForTest(HttpRequestSpec.BodyType.FORM);
        relayout();
        check("缺陷③ · 切回 Form 后文件段落回文本段（x-www-form-urlencoded 传不了文件）",
                table.fileCount() == 0 && table.entryCount() == 2,
                "文件段 = " + table.fileCount() + " 条目 = " + table.entryCount());
        check("缺陷③ · 切回 Form 后键名仍在（内容没被清掉）",
                "avatar".equals(table.fields().get(0).name),
                "首项 = " + (table.fields().isEmpty() ? "无" : table.fields().get(0).name));

        // 引擎侧：Form 必须真的按 urlencoded 编码这些键值
        HttpRequestSpec formSpec = panel.snapshotSpec();
        check("缺陷③ · Form 的键值真的进了引擎模型（不是空表）",
                formSpec.formFields.size() == 2,
                "formFields = " + formSpec.formFields);
        check("缺陷③ · Form 模式下 formData 为空（两种模型不混用）",
                formSpec.formData.isEmpty(),
                "formData = " + formSpec.formData);

        // 肉眼核对：合并后 Form（无「类型」列）与 Form-data（有「类型」列）
        // 必须先切到 Body 页签，否则拍到的是 Params 页，看不到这张表
        panel.requestTabsForTest().getSelectionModel().select(2);
        table.clear();
        table.setTextFieldsForTest("城市", "北京");
        table.setTextFieldsForTest("n", "1");
        relayout();
        snapshot("single-form-merged");

        // 切到 Form-data 后才允许有文件段 —— 这一步同时验证「类型」列回来
        panel.selectBodyTypeForTest(HttpRequestSpec.BodyType.FORM_DATA);
        table.setFileForTest(0, "avatar", "C:/tmp/avatar.png");
        relayout();
        snapshot("single-formdata-merged");

        table.clear();
        panel.requestTabsForTest().getSelectionModel().select(0);
        relayout();
    }

    /**
     * 「+ Token」快捷按钮的回归。
     *
     * <p>用户从别处粘来的往往只是裸 token（{@code eyJhbGciOi...}），
     * 少了 {@code Bearer } 服务端认不出；而已经带前缀的不该被改成
     * {@code Bearer Bearer xxx}。
     *
     * <p>判据是「整串没有空白 → 裸 token」而不是「含不含 Bearer」：
     * 后者会把 {@code Basic dXNlcjpwYXNz} 错加成 {@code Bearer Basic ...}。
     * 而且必须<b>按 key 分派</b> —— {@code Content-Type: application/json}
     * 里也没有空白，无差别套用会把一条正确的头改坏。
     */
    private void regressionAddToken() {
        System.out.println();
        System.out.println("---- E.5 「+ Token」快捷按钮的回归 ----");

        check("+ Token · 工具行上有这个按钮（与 + JSON 并列）",
                panel.tokenButtonForTest() != null
                        && "+ Token".equals(panel.tokenButtonForTest().getText()),
                "按钮 = " + (panel.tokenButtonForTest() == null
                        ? "null" : panel.tokenButtonForTest().getText()));

        // ---- withBearer 的各个分支（纯函数，直接断）----
        check("+ Token · 裸 JWT 补上 Bearer 前缀",
                "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc"
                        .equals(SingleRequestPanel.withBearer(
                                "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc")),
                SingleRequestPanel.withBearer("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc"));
        check("+ Token · 已带 Bearer 的原样保留（不出现 Bearer Bearer）",
                "Bearer abc".equals(SingleRequestPanel.withBearer("Bearer abc")),
                SingleRequestPanel.withBearer("Bearer abc"));
        check("+ Token · 小写 bearer 同样识别（scheme 大小写不敏感）",
                "bearer abc".equals(SingleRequestPanel.withBearer("bearer abc")),
                SingleRequestPanel.withBearer("bearer abc"));
        check("+ Token · Basic 认证不被污染（这是 contains(\"Bearer\") 判据的错例）",
                "Basic dXNlcjpwYXNz".equals(SingleRequestPanel.withBearer("Basic dXNlcjpwYXNz")),
                SingleRequestPanel.withBearer("Basic dXNlcjpwYXNz"));
        check("+ Token · Token / Digest 等其它 scheme 同样放行",
                "Token abc".equals(SingleRequestPanel.withBearer("Token abc"))
                        && "Digest u=x".equals(SingleRequestPanel.withBearer("Digest u=x")),
                SingleRequestPanel.withBearer("Token abc") + " / "
                        + SingleRequestPanel.withBearer("Digest u=x"));
        check("+ Token · 空值不补前缀（否则凭空多出「Authorization: Bearer 」）",
                "".equals(SingleRequestPanel.withBearer(""))
                        && "".equals(SingleRequestPanel.withBearer("   "))
                        && "".equals(SingleRequestPanel.withBearer(null)),
                "空串/空白/null 均为空");
        check("+ Token · 首尾空白被裁掉（粘贴常带换行）",
                "Bearer abc".equals(SingleRequestPanel.withBearer("  Bearer abc\n")),
                "[" + SingleRequestPanel.withBearer("  Bearer abc\n") + "]");

        // ---- 按 key 分派：非 Authorization 的头不能被加工 ----
        check("+ Token · 非 Authorization 的头值原样保留"
                        + "（否则 application/json 会被改成 Bearer application/json）",
                "application/json".equals(
                        SingleRequestPanel.normalizeHeaderValue("Content-Type", "application/json"))
                        && "abc".equals(SingleRequestPanel.normalizeHeaderValue("X-Api-Key", "abc")),
                "Content-Type → " + SingleRequestPanel.normalizeHeaderValue(
                        "Content-Type", "application/json"));
        check("+ Token · Authorization 的 key 忽略大小写与首尾空格",
                "Bearer t".equals(SingleRequestPanel.normalizeHeaderValue(
                        " authorization ", "t")),
                SingleRequestPanel.normalizeHeaderValue(" authorization ", "t"));

        // ---- 按钮真的落位：key 写对、值留空等用户填 ----
        KeyValueTable headers = panel.headersTableForTest();
        headers.clear();
        panel.requestTabsForTest().getSelectionModel().select(1);
        relayout();
        panel.clickAddTokenForTest();
        relayout();
        // 落位在「尾部空行之前」，那条空行要留着给用户接着打 ——
        // 就地占用它的话表尾就没地方加第二条头了。
        check("+ Token · 点一下落一行 Authorization，值留空等用户填",
                headers.rowCountForTest() == 2
                        && "Authorization".equals(headers.rowKeyForTest(0))
                        && "".equals(headers.rowValueForTest(0))
                        && headers.rowKeyForTest(1).isBlank(),
                "行数 = " + headers.rowCountForTest()
                        + " 各行 KEY = [" + headers.rowKeyForTest(0) + "] ["
                        + headers.rowKeyForTest(1) + "]"
                        + " VALUE = 「" + headers.rowValueForTest(0) + "」");

        // 已有内容时落在最后一条之后，末尾空行依然留着
        headers.setEntries(Map.of("X-Api-Key", "k"));
        relayout();
        panel.clickAddTokenForTest();
        relayout();
        check("+ Token · 表里已有内容时追加一行，末尾仍恰好留一行空行",
                headers.rowCountForTest() == 3
                        && "X-Api-Key".equals(headers.rowKeyForTest(0))
                        && "Authorization".equals(headers.rowKeyForTest(1))
                        && headers.rowKeyForTest(2) != null
                        && headers.rowKeyForTest(2).isBlank(),
                "行数 = " + headers.rowCountForTest()
                        + " 各行 KEY = [" + headers.rowKeyForTest(0) + "] ["
                        + headers.rowKeyForTest(1) + "] [" + headers.rowKeyForTest(2) + "]");

        // ---- 反复点不堆行（两个同名 Authorization 会被服务端按实现不同处理）----
        panel.clickAddTokenForTest();
        relayout();
        panel.clickAddTokenForTest();
        relayout();
        int authRows = 0;
        for (int i = 0; i < headers.rowCountForTest(); i++) {
            if ("Authorization".equalsIgnoreCase(headers.rowKeyForTest(i))) {
                authRows++;
            }
        }
        check("+ Token · 反复点不会堆出多条 Authorization",
                authRows == 1,
                "Authorization 行数 = " + authRows + "（总行数 "
                        + headers.rowCountForTest() + "）");

        // ---- 与 + JSON 并存 ----
        // 注意不能用 setHeadersForTest 来验「互不覆盖」：那个方法的语义本来就是
        // 整表替换（批处理页「用当前请求」走的就是它），换成 Content-Type
        // 当然会把 Authorization 冲掉 —— 那是设计如此，不是缺陷。
        // 这里验的是「两条头能同时存在」。
        headers.clear();
        relayout();
        headers.setEntries(Map.of("Content-Type", "application/json"));
        panel.clickAddTokenForTest();
        relayout();
        boolean hasAuth = headers.entries().stream()
                .anyMatch(e -> "Authorization".equalsIgnoreCase(e.getKey()));
        boolean hasCtype = headers.entries().stream()
                .anyMatch(e -> "Content-Type".equals(e.getKey()));
        check("+ Token · 与 + JSON 的头能同时存在（Authorization 追加而非替换）",
                hasAuth && hasCtype,
                "表内容 = " + headers.asMap());

        // ---- 取值路径也要规范化（兜住「粘完直接点 Send」）----
        // 失焦规范化只在「焦点真的离开过那一格」时才跑得过；取值是一定会
        // 发生的那一刻（点 Send / 导出 / 存模板都走 entries()）。
        headers.clear();
        relayout();
        panel.clickAddTokenForTest();
        relayout();
        headers.setValueForTest(0, "eyJhbGciOiJIUzI1NiJ9.sig");
        check("+ Token · 粘完直接点 Send 也会补上前缀（取值路径兜底，不依赖失焦）",
                "Bearer eyJhbGciOiJIUzI1NiJ9.sig".equals(
                        headers.entries().get(0).getValue()),
                "entries() = " + headers.entries());
        check("+ Token · 取值规范化不写回表格（用户看到的仍是原文）",
                "eyJhbGciOiJIUzI1NiJ9.sig".equals(headers.rowValueForTest(0)),
                "表内 VALUE = 「" + headers.rowValueForTest(0) + "」");
        headers.setValueForTest(0, "Bearer abc");
        check("+ Token · 已带前缀的值在取值路径上也不被改写",
                "Bearer abc".equals(headers.entries().get(0).getValue())
                        && "Bearer Bearer abc".equals(
                                SingleRequestPanel.withBearer("Bearer Bearer abc")),
                "entries() = " + headers.entries());

        headers.clear();
        relayout();

        // 肉眼核对：4 个按钮的工具行放得下吗？说明文字有没有被挤掉？
        snapshot("single-headers-token");
        // 点完 + Token 直接粘一个裸 token 离开该格，看前缀有没有自动补上
        // 无头环境里 requestFocus() 拿不到焦点（scene.getFocusOwner() 恒为 null），
        // 所以「失焦规范化」这条路径在探针里跑不出来 —— 上面已用取值路径断言兜住。
        // 这张截图只用于肉眼核对 4 个按钮的工具行排布。
        headers.clear();
        relayout();
        panel.clickAddTokenForTest();
        relayout();
        snapshot("single-headers-token-filled");

        headers.clear();
        panel.requestTabsForTest().getSelectionModel().select(0);
        relayout();
    }

    private static String describe(Node node) {
        if (node == null) {
            return "null";
        }
        return node.getClass().getSimpleName() + node.getStyleClass();
    }

    private boolean allTypesHaveButtons() {
        for (HttpRequestSpec.BodyType type : HttpRequestSpec.BodyType.values()) {
            if (panel.typeButtonForTest(type) == null) {
                return false;
            }
        }
        return true;
    }

    /** 当前类型下动作槽里按钮的文案。 */
    private List<String> actionTexts(HttpRequestSpec.BodyType type) {
        panel.selectBodyTypeForTest(type);
        relayout();
        List<String> texts = new ArrayList<>();
        for (Node child : panel.actionSlotForTest().getChildren()) {
            if (child instanceof Button button) {
                texts.add(button.getText());
            }
        }
        return texts;
    }

    private static String headerValueOf(SingleRequestPanel panel, String key) {
        for (Map.Entry<String, String> e : panel.snapshotHeaders()) {
            if (key.equalsIgnoreCase(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    private static boolean hasColumn(TableView<?> table, String text) {
        if (table == null) {
            return false;
        }
        for (Object c : table.getColumns()) {
            if (c instanceof javafx.scene.control.TableColumn<?, ?> column
                    && text.equals(column.getText())) {
                return true;
            }
        }
        return false;
    }

    private static String columnsOf(TableView<?> table) {
        if (table == null) {
            return "无表格";
        }
        StringBuilder sb = new StringBuilder();
        for (Object c : table.getColumns()) {
            if (c instanceof javafx.scene.control.TableColumn<?, ?> column) {
                sb.append('[').append(column.getText()).append(' ')
                        .append(String.format("%.0f", column.getWidth())).append("] ");
            }
        }
        return sb.toString();
    }

    /**
     * 某列当前是否<b>可见</b>。
     *
     * <p>与 {@link #hasColumn} 配套：Form 模式要求「类型」列存在但被隐藏 ——
     * 只断言「不存在」会放过「把整列删掉」这种同样能满足需求的错误实现，
     * 而删列会让切回 Form-data 时少一列。
     */
    private static boolean columnVisible(FormDataTable table, String text) {
        TableView<?> view = findFirst(table, TableView.class);
        if (view == null) {
            return false;
        }
        for (Object c : view.getColumns()) {
            if (c instanceof javafx.scene.control.TableColumn<?, ?> column
                    && text.equals(column.getText())) {
                return column.isVisible();
            }
        }
        return false;
    }

    /** 某个类型对应的编辑器节点（探针要断言「两种类型是同一个节点」）。 */
    private Node editorFor(HttpRequestSpec.BodyType type) {
        panel.selectBodyTypeForTest(type);
        return panel.visibleEditorForTest();
    }

    /**
     * 页面上「主色实底」的按钮 —— 断言落在<b>解析后的背景填充</b>上，
     * 而不是「有没有 button-primary 这个类名」：Send 用的是自己的
     * {@code .http-send} 规则，只数类名会漏掉它。
     */
    private List<Button> accentButtons() {
        List<Button> out = new ArrayList<>();
        collectAccent(panel, out);
        return out;
    }

    private static void collectAccent(Parent root, List<Button> out) {
        for (Node child : root.getChildrenUnmodifiable()) {
            if (!child.isVisible() || !child.isManaged()) {
                continue;
            }
            if (child instanceof Button button && isAccent(button)) {
                out.add(button);
            }
            if (child instanceof Parent parent) {
                collectAccent(parent, out);
            }
        }
    }

    private static boolean isAccent(Button button) {
        if (button.getBackground() == null || button.getBackground().getFills().isEmpty()) {
            return false;
        }
        Color fill = button.getBackground().getFills().get(0).getFill() instanceof Color c ? c : null;
        return fill != null && near(fill, ACCENT);
    }

    private static boolean near(Color a, Color b) {
        return Math.abs(a.getRed() - b.getRed()) < 0.02
                && Math.abs(a.getGreen() - b.getGreen()) < 0.02
                && Math.abs(a.getBlue() - b.getBlue()) < 0.02;
    }

    private static List<String> buttonTexts(List<Button> buttons) {
        List<String> texts = new ArrayList<>();
        for (Button b : buttons) {
            texts.add(b.getText() == null || b.getText().isBlank()
                    ? "(无文字)" : b.getText());
        }
        return texts;
    }

    /**
     * 等服务器收到指定数量的请求。
     *
     * <p>请求跑在后台线程上，{@code runLater} 的回调还排在 FX 线程队列里 ——
     * 探针在 FX 线程上轮询计数不会挡住网络那一半。
     */
    private static boolean waitForRequests(TinyHttpServer server, int target, long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (server.requestCount() >= target) {
                return true;
            }
            try {
                Thread.sleep(40);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return server.requestCount() >= target;
    }

    private void snapshot(String name) {
        try {
            WritableImage image = scene.getRoot().snapshot(null, null);
            File out = new File("build/http-single-review/shots");
            out.mkdirs();
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png",
                    new File(out, name + ".png"));
            System.out.println("[shot] " + name + ".png");
        } catch (Exception ex) {
            System.out.println("[截图失败] " + name + "：" + ex.getMessage());
        }
    }
}
