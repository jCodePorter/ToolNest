package com.bytefuture.toolnest.features.http;

import atlantafx.base.theme.PrimerLight;
import com.bytefuture.toolnest.app.ToolRegistry;
import com.bytefuture.toolnest.ui.components.CollapsibleSection;
import com.bytefuture.toolnest.ui.components.UiKit;
import com.bytefuture.toolnest.ui.shell.ContentPane;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Toggle;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP 批处理页「三段式折叠」的<b>真实页面</b>探针。
 *
 * <h2>为什么不能再用 toggle() 测</h2>
 * {@code HttpBatchLayoutProbe} 里全是 {@code section.toggle()} —— 直接调方法，
 * 绕过了整套鼠标投递。它证明不了「用户点得动」，而 bug 恰好长在链路上：
 * 头带是不是真的收到了点击、点击落点是不是被别的节点挡住、<b>折叠让布局位移后
 * 同一个落点第二次还打不打得到头带</b>。所以这里自己实现一套 JavaFX 的
 * 拾取（pick）逻辑，按真实落点派发 PRESSED / RELEASED / CLICKED，
 * 让事件沿真实的父子链冒泡到头带。
 *
 * <h2>为什么用 ContentPane 而不是裸 BatchPanel</h2>
 * 真实页面是 {@code ContentPane → detail-wrapper → detail-scroll(ScrollPane)
 * → module-body(带 22/24 padding) → TabPane → BatchPanel → outer(ScrollPane)}。
 * 外层 ScrollPane 的 {@code fitToHeight + minHeight=USE_PREF_SIZE} 会改变内容
 * 拿到的尺寸，TabPane 又只给选中页签的正文参加布局 —— 这些都不是 BatchPanel
 * 自己能决定的。裸面板测出来的尺寸与真实窗口对不上，样式问题也就复现不出来。
 *
 * <h2>窗口尺寸从哪来</h2>
 * 用户截图 1668 × 979 像素：卡片左边缘在第 17 列、右边缘在第 1656 列，而
 * {@code .module-body} 的左右留白是 24 —— 于是内容区宽 = 17 − 24 = −7 起、
 * 到 1656 + 24 止，即截图相对内容区左移了 7px（窗口不可见边框）。反推
 * <b>内容区 ≈ 1688 × 979</b>。这个尺寸比设计稿的 998 × 653 宽得多，
 * 也正是「换个窗口大小就出问题」这类缺陷最该覆盖的那一档。
 */
public class HttpSectionsProbe extends Application {

    private static int passed = 0;
    private static int failed = 0;
    private static final List<String> cssErrors = new ArrayList<>();
    private static final List<String> problems = new ArrayList<>();

    /** 用户窗口的内容区尺寸（见类注释的推算过程）。 */
    private static final double CONTENT_W = 1688;
    private static final double CONTENT_H = 979;

    /**
     * 设计稿冻结的窗口（1266）下的内容区宽。
     *
     * <p>来自 {@code design/http-batch-redesign-spec.md} §410 的对照表
     * （1266 窗宽 → 内容宽 950）。执行条的单行预算就是按这一档做的，
     * 用户窗口宽得多，光在他那一档量是量不出「挤扁」的。
     */
    private static final double DESIGN_CONTENT_W = 950;

    private Scene scene;
    private ContentPane pane;
    private HttpToolModule module;
    private BatchPanel panel;

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name + "   " + detail);
        } else {
            failed++;
            System.out.println("FAIL  " + name + "  → " + detail);
        }
    }

    private void relayout() {
        scene.getRoot().applyCss();
        layoutDeep(scene.getRoot());
        scene.getRoot().applyCss();
        layoutDeep(scene.getRoot());
    }

    private static void layoutDeep(Parent node) {
        node.layout();
        for (Node child : node.getChildrenUnmodifiable()) {
            if (child instanceof Parent childParent) {
                layoutDeep(childParent);
            }
        }
    }

    /**
     * 按类型找第一个后代节点。
     *
     * <p>不用 {@code lookup(".css-class")}：它在本探针里对
     * {@code .http-main-tabs} 返回 null（同样的选择器在 {@code httplayout}
     * 那种「裸面板」结构下是能查到的），而类型是自己可控的，不依赖 CSS 引擎
     * 的匹配时机。节点类型本来也比赛类名更不容易写错。
     */
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

    /**
     * 收集所有「提示文案含换行」的输入控件。
     *
     * <p>{@code TextArea} 的 promptText 不渲染 {@code \n}：多行提示会被压成
     * 一行、行与行之间只剩一个空格，表头和数据行直接粘在一起。这是控件行为，
     * 不是样式能修的东西，只能在这一层拦住 —— 所以做成对全页的扫描，
     * 而不是只盯已知的那一处。
     */
    private static void collectNewlinePrompts(Parent root, StringBuilder out) {
        for (Node child : root.getChildrenUnmodifiable()) {
            if (child instanceof TextInputControl input
                    && input.getPromptText() != null && input.getPromptText().contains("\n")) {
                out.append(input.getClass().getSimpleName()).append('：')
                        .append(input.getPromptText().replace("\n", "\\n")).append("  ");
            }
            if (child instanceof Parent parent) {
                collectNewlinePrompts(parent, out);
            }
        }
    }

    // ==================== 拾取与点击 ====================

    /**
     * 按 JavaFX 的规则找出该落点会派发到哪个节点：从顶层的孩子倒序（后画的在上）
     * 递归下去，取第一个命中的。
     */
    private static Node pick(Parent parent, double sx, double sy) {
        List<Node> children = parent.getChildrenUnmodifiable();
        for (int i = children.size() - 1; i >= 0; i--) {
            Node hit = pickNode(children.get(i), sx, sy);
            if (hit != null) {
                return hit;
            }
        }
        return parent;
    }

    private static Node pickNode(Node node, double sx, double sy) {
        if (node.isMouseTransparent() || !node.isVisible()) {
            return null;
        }
        Point2D p = node.sceneToLocal(sx, sy);
        if (!node.contains(p.getX(), p.getY())) {
            return null;
        }
        if (node instanceof Parent parent) {
            List<Node> children = parent.getChildrenUnmodifiable();
            for (int i = children.size() - 1; i >= 0; i--) {
                Node hit = pickNode(children.get(i), sx, sy);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return node;
    }

    /**
     * 真实点击：先拾取落点，再把三个鼠标事件派发给命中的节点。
     *
     * <p>{@code Event.fireEvent} 会自建捕获 + 冒泡链（目标 → 各级父 → Scene），
     * 与真实鼠标事件的投递路径一致 —— 唯一的差别是我们自己做了拾取。
     */
    private String clickAt(double sx, double sy) {
        Node target = pick(scene.getRoot(), sx, sy);
        StringBuilder path = new StringBuilder();
        for (Node n = target; n != null; n = n.getParent()) {
            path.append('/').append(n.getClass().getSimpleName());
            if (n instanceof CollapsibleSection) {
                path.append("#").append(System.identityHashCode(n));
            }
            if (n == scene.getRoot()) {
                break;
            }
        }
        for (EventType<MouseEvent> type : List.of(MouseEvent.MOUSE_PRESSED,
                MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
            Event.fireEvent(target, mouseEvent(type, sx, sy));
        }
        return describe(target) + "  链路" + path;
    }

    private static MouseEvent mouseEvent(EventType<MouseEvent> type, double sx, double sy) {
        return new MouseEvent(type, sx, sy, sx, sy, MouseButton.PRIMARY, 1,
                false, false, false, false,
                true, false, false, false, false, false, null);
    }

    private static String describe(Node node) {
        if (node == null) {
            return "null";
        }
        return node.getClass().getSimpleName() + node.getStyleClass();
    }

    /** 节点在 Scene 坐标系里的矩形，用于算落点。 */
    private static Bounds sceneBounds(Node node) {
        return node.localToScene(node.getBoundsInLocal());
    }

    private void snapshot(String name) {
        snapshotImage(name);
    }

    private WritableImage snapshotImage(String name) {
        WritableImage image = scene.getRoot().snapshot(null, null);
        try {
            File out = new File("build/style-review");
            out.mkdirs();
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", new File(out, name + ".png"));
            System.out.println("[shot] " + name + ".png");
        } catch (Exception ex) {
            System.out.println("[截图失败] " + name + "：" + ex.getMessage());
        }
        return image;
    }

    /** 按文字找 Label —— 用内容定位比按下标取容器的子节点稳。 */
    private static Label findLabel(Parent root, String text) {
        for (Node child : root.getChildrenUnmodifiable()) {
            if (child instanceof Label label && text.equals(label.getText())) {
                return label;
            }
            if (child instanceof Parent parent) {
                Label found = findLabel(parent, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 节点在快照里的最大墨迹强度（0 = 纯白、1 = 全黑）。
     *
     * <p>降级态这件事只有像素能回答：设计要求的是「整组降透明」，
     * 而「有没有真的同比例压暗」是渲染结果，不是「有没有调 setDisable」。
     *
     * @param fx0 采样横向区间起点（占节点宽的比例）
     * @param fx1 采样横向区间终点；数值框取左半段以避开右侧的上下箭头
     */
    private static double ink(Node node, Image image, double fx0, double fx1) {
        Bounds b = sceneBounds(node);
        PixelReader reader = image.getPixelReader();
        double x0 = b.getMinX() + b.getWidth() * fx0;
        double x1 = b.getMinX() + b.getWidth() * fx1;
        double best = 0;
        for (int y = (int) Math.ceil(b.getMinY()); y <= (int) Math.floor(b.getMaxY()); y++) {
            for (int x = (int) Math.ceil(x0); x <= (int) Math.floor(x1); x++) {
                if (x < 0 || y < 0 || x >= image.getWidth() || y >= image.getHeight()) {
                    continue;
                }
                Color c = reader.getColor(x, y);
                // Color 的分量是 0..1 而不是 0..255，忘乘 255 会把白读成黑
                double lum = 0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue();
                best = Math.max(best, 1 - lum);
            }
        }
        return best;
    }

    // ==================== 主流程 ====================

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

        // ContentPane 是按 moduleId 回查注册表的，没注册过就只拿到一句
        // 「找不到该功能模块」的兜底文案 —— 页签、三段全都不会出现。
        ToolRegistry.bootstrap();

        module = new HttpToolModule();
        pane = new ContentPane();
        pane.openDetail(module);

        scene = new Scene(pane, CONTENT_W, CONTENT_H);
        scene.getStylesheets().add(
                HttpSectionsProbe.class.getResource("/css/app.css").toExternalForm());
        stage.setScene(scene);
        stage.show();

        // 切到批处理页签：只有被选中的页签正文才参加布局
        relayout();
        TabPane tabs = findFirst(pane, TabPane.class);
        if (tabs == null) {
            System.out.println("FAIL  找不到页签容器 TabPane");
            Platform.exit();
            return;
        }
        tabs.getSelectionModel().select(1);
        relayout();

        panel = findFirst(pane, BatchPanel.class);
        if (panel == null) {
            System.out.println("FAIL  找不到批处理面板 BatchPanel");
            Platform.exit();
            return;
        }

        CollapsibleSection tpl = panel.templateSectionForTest();
        CollapsibleSection csv = panel.csvSectionForTest();
        CollapsibleSection res = panel.resultSectionForTest();

        // ---------- 1. 几何：与用户截图逐段对齐 --------------------------
        System.out.println("[dbg] 场景 " + scene.getWidth() + "×" + scene.getHeight()
                + "  TabPane=" + rect(tabs)
                + "  BatchPanel=" + rect(panel));
        System.out.println("[dbg] 段①=" + rect(tpl) + " 段②=" + rect(csv) + " 段③=" + rect(res));
        System.out.println("[dbg] 头带①=" + rect(tpl.getChildrenUnmodifiable().get(0))
                + " 头带②=" + rect(csv.getChildrenUnmodifiable().get(0)));
        System.out.println("[dbg] 内容区左留白=" + (sceneBounds(tpl).getMinX())
                + "  右留白=" + (scene.getWidth() - sceneBounds(tpl).getMaxX()));

        check("①②③ 三段卡片左右边缘对齐",
                Math.abs(sceneBounds(tpl).getMinX() - sceneBounds(res).getMinX()) < 0.5
                        && Math.abs(sceneBounds(tpl).getMaxX() - sceneBounds(res).getMaxX()) < 0.5,
                "① x=" + sceneBounds(tpl).getMinX() + ".." + sceneBounds(tpl).getMaxX()
                        + "  ③ x=" + sceneBounds(res).getMinX() + ".." + sceneBounds(res).getMaxX());

        check("左右留白一致（24 / 24）",
                Math.abs(sceneBounds(tpl).getMinX() - 24) < 1.5
                        && Math.abs(scene.getWidth() - sceneBounds(tpl).getMaxX() - 24) < 1.5,
                "左=" + sceneBounds(tpl).getMinX()
                        + " 右=" + (scene.getWidth() - sceneBounds(tpl).getMaxX()));

        // CSV 粘贴框的多行提示：TextArea 的 prompt 不渲染换行，四行示例会被
        // 压成一行「orderId,type235027703726,A235027703727,B」，表头与数据行
        // 连在一起完全读不出来 —— 只能改成不含换行的写法。
        String csvPrompt = panel.csvAreaForTest().getPromptText();
        check("CSV 粘贴框提示不含换行（TextArea 的 prompt 不渲染 \\n）",
                csvPrompt == null || !csvPrompt.contains("\n"),
                "prompt=" + (csvPrompt == null ? "null" : csvPrompt.replace("\n", "\\n")));

        // 同一类缺陷不该只在 HTTP 页存在：把两个页签的输入控件都扫一遍。
        StringBuilder newline = new StringBuilder();
        for (int i = 0; i < tabs.getTabs().size(); i++) {
            tabs.getSelectionModel().select(i);
            relayout();
            collectNewlinePrompts(pane, newline);
        }
        tabs.getSelectionModel().select(1);
        relayout();
        check("页面上没有「提示文案带换行」的输入控件",
                newline.length() == 0,
                newline.length() == 0 ? "" : newline.toString());

        snapshot("render-full-page");

        // ---------- 2. 真实点击：折叠 → 展开 → 折叠 ------------------------
        check("初始：①展开 / ②收起", tpl.isExpanded() && !csv.isExpanded(),
                "①=" + tpl.isExpanded() + " ②=" + csv.isExpanded());

        // 落点取头带正中间（摘要区），与用户点的地方一致
        Node bar1 = tpl.getChildrenUnmodifiable().get(0);
        double barCx = sceneBounds(bar1).getMinX() + sceneBounds(bar1).getWidth() / 2;
        double barCy = sceneBounds(bar1).getMinY() + sceneBounds(bar1).getHeight() / 2;

        // 记录 setExpanded 被调用的次数与参数，并打印调用栈：
        // 「头带没收到点击」与「收到了、但被别的调用改回去」根因完全不同，
        // 只记 count 会把这两种情况混在一起。
        final List<String> toggleLog = new ArrayList<>();
        tpl.setOnToggle(v -> {
            toggleLog.add(String.valueOf(v));
            if (toggleLog.size() <= 5) {
                System.out.println("[trace] setExpanded(" + v + ") 由谁调用：");
                StackTraceElement[] st = new Throwable().getStackTrace();
                for (int i = 0; i < Math.min(st.length, 8); i++) {
                    if (st[i].getClassName().contains("javafx")) {
                        continue;
                    }
                    System.out.println("          " + st[i]);
                }
            }
        });

        String hit = clickAt(barCx, barCy);
        relayout();
        boolean first = !tpl.isExpanded();
        check("第 1 次点头带 → 收起", first, "命中 " + hit + "，①=" + tpl.isExpanded());

        clickAt(barCx, barCy);
        relayout();
        check("第 2 次点同一落点 → 重新展开（用户报的「再点没反应」）",
                tpl.isExpanded(), "①=" + tpl.isExpanded()
                        + " 落点=(" + barCx + "," + barCy + ")");

        clickAt(barCx, barCy);
        relayout();
        check("第 3 次点同一落点 → 再次收起", !tpl.isExpanded(),
                "①=" + tpl.isExpanded());

        // 箭头那一点（头带右端 20px）也要能点
        double arrowX = sceneBounds(bar1).getMaxX() - 20;
        clickAt(arrowX, barCy);
        relayout();
        check("点箭头位置也能展开", tpl.isExpanded(), "①=" + tpl.isExpanded());
        System.out.println("[dbg] ① setExpanded 值序列 " + toggleLog
                + "  最终 isExpanded=" + tpl.isExpanded()
                + "  实例=" + System.identityHashCode(tpl));
        snapshot("render-after-click");

        // ---------- 3. ②段同理 ------------------------------------------
        Node bar2 = csv.getChildrenUnmodifiable().get(0);
        double bar2Cx = sceneBounds(bar2).getMinX() + sceneBounds(bar2).getWidth() / 2;
        double bar2Cy = sceneBounds(bar2).getMinY() + sceneBounds(bar2).getHeight() / 2;
        clickAt(bar2Cx, bar2Cy);
        relayout();
        boolean csvFirst = csv.isExpanded();
        clickAt(bar2Cx, bar2Cy);
        relayout();
        check("②段：连点两次能来回切（展开 → 收起）",
                csvFirst && !csv.isExpanded(),
                "第一次=" + csvFirst + " 第二次=" + csv.isExpanded());

        // ---------- 4. ③ 常驻段不该被点动 ---------------------------------
        Parent bar3 = (Parent) res.getChildrenUnmodifiable().get(0);
        clickAt(sceneBounds(bar3).getMinX() + sceneBounds(bar3).getWidth() / 2,
                sceneBounds(bar3).getMinY() + sceneBounds(bar3).getHeight() / 2);
        relayout();
        check("③常驻段点不动", res.isExpanded(), "③=" + res.isExpanded());

        // ---------- 5. 导出按钮与折叠箭头不得重叠 --------------------------
        Node arrow = null;
        for (Node c : bar3.getChildrenUnmodifiable()) {
            if (c.getStyleClass().contains("stage-bar-toggle")) {
                arrow = c;
            }
        }
        check("③头带折叠指示已隐藏（常驻段）",
                arrow == null || !arrow.isVisible(),
                arrow == null ? "无" : "visible=" + arrow.isVisible());

        // ---------- 6. 复现用户截图那一刻的形态 ---------------------------
        // 用户截图是「①收起 / ②展开 / 尚未导入 CSV」。把界面摆成同一形态
        // 再截一张，供与截图逐像素比对 —— 几何量测只能证明「数字对」，
        // 证明不了「看起来对」。
        tpl.setExpanded(false);
        csv.setExpanded(true);
        relayout();
        System.out.println("[dbg] 用户形态 段①=" + rect(tpl) + " 段②=" + rect(csv)
                + " 段③=" + rect(res) + " 头带③=" + rect(bar3));
        check("用户形态：①②头带都在视口内、且未被裁切",
                sceneBounds(tpl).getMaxY() < CONTENT_H && sceneBounds(csv).getMaxY() < CONTENT_H,
                "①底=" + sceneBounds(tpl).getMaxY() + " ②底=" + sceneBounds(csv).getMaxY());
        snapshot("render-user-state");

        // ---------- 6.5 降级态：整组降透明 + 明写原因 ----------------------
        // 用户在批处理页红框标出的第二处「样式错乱」就是这里：线程数只被
        // setDisable，于是 11px 的标签仍是全黑、数值却淡到几乎看不见，而且
        // 没有一个字说明为什么。设计稿 §06-5 把正确做法写成了不变量 ——
        // 「整行降透明（42%）+ 明写原因」「禁用态必须配文字，不能只做 setDisable」。
        // 这里量的是渲染墨迹：断「标签与数值是不是被同比例压暗」，只有像素能回答。
        HBox threadField = panel.threadFieldForTest();
        Label threadNote = panel.threadNoteForTest();
        Label threadLabel = findLabel(threadField, "线程数");
        Spinner<?> threadSpinner = findFirst(threadField, Spinner.class);
        Label timeoutLabel = findLabel((Parent) threadField.getParent().getParent(), "超时");

        WritableImage degradedShot = snapshotImage("render-degraded");
        double labelOff = ink(threadLabel, degradedShot, 0, 1);
        double valueOff = ink(threadSpinner, degradedShot, 0, 0.55);
        double refOff = ink(timeoutLabel, degradedShot, 0, 1);

        check("「顺序执行」下线程数组整组降透明（设计稿 42%）",
                Math.abs(threadField.getOpacity() - UiKit.DEGRADE_OPACITY) < 1e-9,
                "opacity=" + threadField.getOpacity());
        check("降级态明写原因「并发时生效」",
                threadNote.isVisible() && "并发时生效".equals(threadNote.getText()),
                "note=" + threadNote.getText() + " visible=" + threadNote.isVisible());
        check("降级态：标签也一起变淡（不再是「标签全黑 + 数值极淡」）",
                refOff > 0.3 && labelOff < refOff * 0.7 && valueOff > labelOff,
                "线程数标签墨迹=" + String.format("%.3f", labelOff)
                        + " 数值=" + String.format("%.3f", valueOff)
                        + " 同排未降级的「超时」标签=" + String.format("%.3f", refOff));
        check("降级态拦住了交互（不吃鼠标 + 编辑器只读）",
                threadSpinner.isMouseTransparent() && !threadSpinner.isEditable(),
                "mouseTransparent=" + threadSpinner.isMouseTransparent()
                        + " editable=" + threadSpinner.isEditable());

        // 切到「并发执行」：整组复员、原因文字收起
        for (Toggle t : panel.modeGroupForTest().getToggles()) {
            if (Boolean.TRUE.equals(t.getUserData())) {
                t.setSelected(true);
            }
        }
        relayout();
        WritableImage enabledShot = snapshotImage("render-enabled");
        double labelOn = ink(threadLabel, enabledShot, 0, 1);
        double valueOn = ink(threadSpinner, enabledShot, 0, 0.55);

        check("「并发执行」下线程数组复员（不透明、可编辑、原因文字收起）",
                Math.abs(threadField.getOpacity() - 1) < 1e-9
                        && threadSpinner.isEditable() && !threadNote.isVisible(),
                "opacity=" + threadField.getOpacity() + " editable=" + threadSpinner.isEditable()
                        + " noteVisible=" + threadNote.isVisible());

        // 核心断言：两个状态之间的压暗倍率，标签与数值必须一致。
        // 旧实现（只 setDisable 数值框）在同一个量上会给出「标签 ≈1.0 / 数值 ≈0.2」，
        // 差值 0.8 —— 这正是用户说的「样式错乱」。
        double labelRatio = labelOn > 0.05 ? labelOff / labelOn : 0;
        double valueRatio = valueOn > 0.05 ? valueOff / valueOn : 0;
        check("压暗倍率：标签与数值同步（≈42%，两者差 < 0.12）",
                Math.abs(labelRatio - UiKit.DEGRADE_OPACITY) < 0.12
                        && Math.abs(valueRatio - UiKit.DEGRADE_OPACITY) < 0.12
                        && Math.abs(labelRatio - valueRatio) < 0.12,
                "标签 " + String.format("%.2f", labelRatio)
                        + " 数值 " + String.format("%.2f", valueRatio)
                        + "（期望 ≈ " + UiKit.DEGRADE_OPACITY + "）");

        // 切回「顺序执行」：再次降级
        for (Toggle t : panel.modeGroupForTest().getToggles()) {
            if (Boolean.FALSE.equals(t.getUserData())) {
                t.setSelected(true);
            }
        }
        relayout();
        check("切回「顺序执行」线程数组再次降级",
                Math.abs(threadField.getOpacity() - UiKit.DEGRADE_OPACITY) < 1e-9
                        && threadNote.isVisible() && !threadSpinner.isEditable(),
                "opacity=" + threadField.getOpacity() + " noteVisible=" + threadNote.isVisible());

        // ---------- 7. CSS -------------------------------------------------
        check("未出现 CSS 解析错误", cssErrors.isEmpty(),
                cssErrors.isEmpty() ? "" : String.join(" | ", cssErrors));

        // ---------- 8. 设计纵深下的执行条预算 -------------------------------
        // 降级态给线程数后面加了一句「并发时生效」，执行条因此变宽 —— 在用户窗口
        // （1688）富余很大，看不出问题；设计稿冻结的是「1266 窗宽下单行放得下」
        // （内容宽 950，见 redesign-spec §410）。
        //
        // 注意：这里**不能**用 stage.setWidth 去改窗口再量 —— 探针里改 Stage 尺寸
        // 不会同步到 Scene（实测场景宽仍是 1688），断言就变成恒真的摆设。
        // 改法是把当前布局实测出的「横向 chrome」投影到设计纵深：
        // chrome = 场景宽 − 执行条实际拿到宽（模块留白 + 卡片描边 + body padding）。
        Parent execRow = (Parent) threadField.getParent().getParent();
        double rowGot = ((Region) execRow).getWidth();
        double rowPref = execRow.prefWidth(-1);
        double chrome = scene.getWidth() - rowGot;
        double designAvail = DESIGN_CONTENT_W - chrome;
        check("设计内容宽 " + (int) DESIGN_CONTENT_W + " 下执行条单行放得下（含「并发时生效」）",
                rowGot > 0 && rowPref <= designAvail,
                "执行条需要=" + String.format("%.0f", rowPref)
                        + " 设计纵深下可用=" + String.format("%.0f", designAvail)
                        + "（chrome=" + String.format("%.0f", chrome)
                        + "，用户窗口实测拿到=" + String.format("%.0f", rowGot) + "）");

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

    private static String rect(Node node) {
        Bounds b = sceneBounds(node);
        return String.format("[%.0f,%.0f %.0f×%.0f]",
                b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight());
    }
}
