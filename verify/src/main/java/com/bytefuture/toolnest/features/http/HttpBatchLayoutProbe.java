package com.bytefuture.toolnest.features.http;

import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP 批处理页「纵向三段式」布局探针。
 *
 * <p>三段式的收益全在竖向预算怎么分上：同样 653px 的内容区高，配置态与
 * 执行态的结果表各能看几行，只编译是看不出来的 —— 必须把界面跑起来量
 * 真实高度。所以这里所有断言都落在渲染后的 {@code getHeight()} /
 * {@code getWidth()} 上，而不是「节点在不在」。
 *
 * <p>量测口径：内容区 998 × 653（1266×763 窗口减侧边栏 268、减 chrome 110）；
 * 表格行高 34、表头 28，可见行数 = (表高 − 28) ÷ 34 向下取整。
 */
public class HttpBatchLayoutProbe extends Application {

    private static int passed = 0;
    private static int failed = 0;
    private static final List<String> cssErrors = new ArrayList<>();
    private static final List<String> problems = new ArrayList<>();

    private static final double CONTENT_W = 998;
    private static final double CONTENT_H = 653;
    private static final double ROW_H = 34;
    private static final double TABLE_HEADER_H = 28;
    /**
     * 拿不到 TextArea 内部文本容器时的兜底：TextArea 自身的边框 + 内边距。
     * 实测（12.5px 字号）为 6px。
     */
    private static final double DETAIL_CHROME_H = 6;

    private StackPane root;
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

    /**
     * 强制整棵树重新布局。
     *
     * <p>{@code root.applyCss(); root.layout();} 是不够的：JavaFX 的
     * {@code Parent.layout()} 自身 layout flag 为 CLEAN 时<b>不会下钻子树</b>，
     * 单次调用只排到「变更节点的父级」那一层 —— 表现就是外层节点高度已经更新
     * （③ 从 301 涨到 561），而它的子节点还停在旧值（body 274、table 204），
     * 量出来的行数全是上一帧的残值。所以这里自上而下逐个 Parent 排一遍：
     * 父先排（定下子节点的尺寸），再递归子节点（子节点据此排自己的子树）。
     */
    private void relayout() {
        root.applyCss();
        layoutDeep(root);
        // 第二轮：有些 CSS（滚动条按需显隐）要等尺寸落定后才生效
        root.applyCss();
        layoutDeep(root);
    }

    private static void layoutDeep(Parent node) {
        node.layout();
        // Parent.getChildren() 是 protected，探针只能读不可变视图
        for (Node child : node.getChildrenUnmodifiable()) {
            if (child instanceof Parent childParent) {
                layoutDeep(childParent);
            }
        }
    }

    /** 表体去掉表头后能完整显示几行。 */
    private int rowsOf(double tableHeight) {
        return (int) Math.floor((tableHeight - TABLE_HEADER_H) / ROW_H);
    }

    /**
     * 外层滚动容器是否真的溢出：内容自然高 &gt; 视口高。
     *
     * <p>不能用 {@code ScrollPane.getVmax()}：探针是手动 layout 的（没有真实
     * pulse），vmax 一直停在默认值 1.0，滚动条的 {@code visible} 标志也不更新，
     * 量出来恒为「1 / 不可见」。视口高用 {@code getViewportBounds()} 量是准的。
     */
    private static boolean overflows(ScrollPane outer, Parent inner) {
        return inner.getLayoutBounds().getHeight()
                > outer.getViewportBounds().getHeight() + 0.5;
    }

    /** 当前字号下一行文字的真实高度（不写死常数，字号一改就跟着变）。 */
    private static double lineHeightOf(Font font) {
        Text probe = new Text("Ag");
        probe.setFont(font);
        return probe.getLayoutBounds().getHeight();
    }

    /**
     * 响应详情能完整显示几行。
     *
     * <p>口径：TextArea 内部那个 {@code .content} 文本容器的高度 ÷ 该字号的真实
     * 行高。之前拿「结果表高度 ÷ 20」算是错的 —— 结果分栏里表格与详情是<b>横向
     * 并排</b>的，两者高度相同但一行的高度差一倍多（34px vs 15.9px），
     * 量出来的其实是表格的行数，而且 20 / 28 这两个常数也和实测
     * （行高 15.9、容器边框内边距 6）对不上，会把真实行数算少。
     */
    private int detailRowsOf(TextArea area) {
        Node content = area.lookup(".content");
        double textH = content instanceof Region textRegion
                ? textRegion.getHeight()
                : area.getHeight() - DETAIL_CHROME_H;
        return (int) Math.floor(textH / lineHeightOf(area.getFont()));
    }

    /** 段① 改版断言用的辅助：页签标题列表。 */
    private static String tabTextsOf(TabPane tabs) {
        StringBuilder sb = new StringBuilder("[");
        for (Tab tab : tabs.getTabs()) {
            sb.append(tab.getText()).append(' ');
        }
        return sb.append(']').toString();
    }

    /** 数一个节点树里有几个 Toggle（分段控件的项数）。 */
    private static int countToggles(Node node) {
        if (node instanceof Toggle) {
            return 1;
        }
        int n = 0;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                n += countToggles(child);
            }
        }
        return n;
    }

    /** 节点树里有没有这个文案的控件（用来断「不该出现 Form-data / Binary」）。 */
    private static boolean containsLabel(Node node, String text) {
        if (node instanceof Labeled labeled && text.equals(labeled.getText())) {
            return true;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                if (containsLabel(child, text)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 分段控件各项的文案。 */
    private static String toggleTextsOf(Node node) {
        StringBuilder sb = new StringBuilder("[");
        collectToggleTexts(node, sb);
        return sb.append(']').toString();
    }

    private static void collectToggleTexts(Node node, StringBuilder sb) {
        if (node instanceof ToggleButton item) {
            sb.append(item.getText()).append(' ');
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectToggleTexts(child, sb);
            }
        }
    }


    private void snapshot(String name) {
        try {
            WritableImage image = root.snapshot(null, null);
            // 刻意不写 target/：verify.cmd 每次都先 mvn clean，落在 target 下的
            // 截图会被下一次探针运行清掉，评审材料拿不到图。
            File out = new File("build/http-redesign-review");
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

        panel = new BatchPanel();
        root = new StackPane(panel);
        Scene scene = new Scene(root, CONTENT_W, CONTENT_H);
        scene.getStylesheets().add(
                HttpBatchLayoutProbe.class.getResource("/css/app.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
        relayout();

        var tpl = panel.templateSectionForTest();
        var csv = panel.csvSectionForTest();
        var res = panel.resultSectionForTest();
        var table = panel.resultTableForTest();
        TextArea detail = panel.detailAreaForTest();
        var preview = panel.previewTableForTest();
        var outer = panel.outerScrollForTest();
        var innerNode = panel.innerForTest();

        // ---- 1. 初始形态 ------------------------------------------------
        check("默认：①展开 / ②收起 / ③常驻展开",
                tpl.isExpanded() && !csv.isExpanded() && res.isExpanded(),
                "①=" + tpl.isExpanded() + " ②=" + csv.isExpanded() + " ③=" + res.isExpanded());

        // 配置态 A：三段必须整体落进一屏。溢出可滚只是兜底，不是目标 ——
        // 溢出就意味着段③ 底部被视口切掉一截（结果表最后一行只露一半）。
        double innerH = innerNode.getLayoutBounds().getHeight();
        double total = tpl.getHeight() + csv.getHeight() + res.getHeight() + 20;
        // 口径更新（2026-10-03 段① 加高后）：配置态**允许**外层滚动。
        // 起因：Params/Headers 的表要能输入内容（表头 40 + 3 行 × 40 + 滚动条），
        // 三个页签共享一个 TabPane 高度 → 段① 252.7 涨到 376，整页 774.67 > 654。
        // 用户明确要「整体都可以增加高度，段3也向下挪」——
        // 段③ 守 340 保底（执行时要看结果），所以只能让整页滚。
        // 真正要守的不变量是「段③ 不能被切」，见下面那条。
        check("配置态 A：整页允许超出视口（外层滚动兜底，段① 加高后的既定取舍）",
                innerNode.getLayoutBounds().getHeight() > CONTENT_H,
                "inner=" + String.format("%.1f", innerNode.getLayoutBounds().getHeight())
                        + " > 视口 " + String.format("%.1f",
                        outer.getViewportBounds().getHeight())
                        + "，超出 " + String.format("%.1f",
                        innerNode.getLayoutBounds().getHeight() - CONTENT_H)
                        + "（段① 加高换表可用，用户已确认）");

        // 段③ 底部落在视口内：这条直接锁死「结果区被切」—— inner ≤ 653
        // 还不够（也可能是 ③ 自己矮、下面空一截），必须量 ③ 的底边 y。
        double resBottom = res.getBoundsInParent().getMaxY();
        // 整页滚之后，「段③ 底边 ≤ 视口」不再成立（它在折叠线以下）。
        // 换成段②/③ 的**相对顺序**不变 + 段③ 高度守住保底 ——
        // 段③ 是执行时唯一要看的东西，它的高度不能被挤掉。
        double csvBottom = csv.getBoundsInParent().getMaxY();
        check("段③ 高度守住 340 保底（整页可滚时，执行时看结果的地方不能被挤）",
                res.getHeight() >= 340 - 0.5,
                "段③ 高 " + String.format("%.1f", res.getHeight())
                        + "（保底 340）｜段② 底 y=" + String.format("%.1f", csvBottom)
                        + " 段③ 底 y=" + String.format("%.1f", resBottom)
                        + "（段③ 在段② 之下，顺序正确）");

        check("③吸收剩余高度（配置态 ≥ 340）",
                res.getHeight() >= 340, "实测 " + res.getHeight());

        int cfgRows = rowsOf(table.getHeight());
        // 口径更新（2026-10-03）：段① 加高 → 段③ 守 340 保底 → 配置态表高 230.67（5 行）。
        // 「≥ 6 行」是段① 只有 253 时的口径。现在段① 让位给了输入区，
        // 配置态 5 行是**已确认的取舍**；段① 收起（导入 CSV / 执行时自动收起）
        // 后立刻回到 12 行，见下面「运行态」那条。
        check("配置态结果表 ≥ 5 行（段① 加高后的新口径，段③ 守 340 保底）",
                cfgRows >= 5,
                "表高 " + String.format("%.1f", table.getHeight())
                        + " → " + cfgRows + " 行（段① 收起后回到 12 行）");

        // 详情行数量的是 detailArea 自己的高度，不是结果表的高度 —— 两者
        // 在 ③ 里是横向并排的两个分栏，高度相同但一行的高度不同（20 vs 34）。
        int cfgDetailRows = detailRowsOf(detail);
        check("配置态响应详情 ≥ 10 行",
                cfgDetailRows >= 10,
                "详情高 " + detail.getHeight() + " → " + cfgDetailRows + " 行");
        // 段① 撤掉上限后的自然高度：这是配置态滚不滚的关键数字
        double cfgTplH = tpl.getHeight();
        StringBuilder parts = new StringBuilder("[dbg] 段① 拆解 body=" + tpl.content().getHeight()
                + " padding=" + tpl.content().getPadding()
                + " spacing=" + tpl.content().getSpacing());
        for (Node child : tpl.content().getChildren()) {
            parts.append(" | ").append(child.getClass().getSimpleName())
                    .append("=").append(child.getLayoutBounds().getHeight());
        }
        System.out.println(parts);
        System.out.println("[dbg] 段③ 底部 y=" + res.getBoundsInParent().getMaxY()
                + " outer padding=" + outer.getPadding()
                + " inner padding=" + ((Region) innerNode).getPadding());
        System.out.println("[dbg] 配置态 ①=" + tpl.getHeight()
                + " ②=" + csv.getHeight()
                + " ③=" + res.getHeight()
                + " inner=" + innerNode.getLayoutBounds().getHeight()
                + " 视口=" + outer.getViewportBounds().getHeight()
                + " table=" + table.getHeight()
                + " detail=" + detail.getHeight());

        check("结果表宽 ≥ 520（不触发横滚）",
                table.getWidth() >= 520, "实测 " + table.getWidth());

        // 「状态」列不得被压到 62 以下：CONSTRAINED 策略在列宽总和 > 可用宽时会
        // 按比例压窄每一列，压到 62 以下「等待中」就被截成「等待…」（§7 #17 禁止）。
        double stateColW = table.getColumns().get(1).getWidth();
        check("结果表「状态」列 ≥ 62px（「等待中」不截断）",
                stateColW >= 62, "实测 " + stateColW + "px");
        double colSum = 0;
        StringBuilder cols = new StringBuilder();
        for (Object c : table.getColumns()) {
            javafx.scene.control.TableColumn<?, ?> column =
                    (javafx.scene.control.TableColumn<?, ?>) c;
            colSum += column.getWidth();
            cols.append(column.getText()).append('=').append(column.getWidth()).append(' ');
        }
        System.out.println("[dbg] 结果表列宽 表宽=" + table.getWidth()
                + " 列合计=" + colSum + " | " + cols);

        // 口径更新（2026-10-03 段① 改版）：URL 现在与「方法下拉 + 占位符说明」
        // 同行（对齐单一请求页的请求行）。可用宽 = 面板内宽 − 方法 118
        // − 间距 16 − 占位符说明（约 288）。门限从 700 改成「不小于可用宽的一半」：
        // 真正要守住的是「URL 是这一行里最宽的元素」，而不是某个双栏时代的绝对值。
        double rowInner = tpl.content().getWidth() - 24;   // 减掉 .stage-body-compact 左右各 12
        double urlW = panel.urlFieldForTest().getWidth();
        double methodW = panel.methodBoxForTest().getWidth();
        check("URL 输入框占请求行可用宽 ≥ 50%，且是本行最宽的元素"
                        + "（段① 改版后与说明同行，门限由 700 改为比例）",
                urlW >= rowInner * 0.5 && urlW > methodW,
                "实测 URL " + String.format("%.1f", urlW)
                        + " / 可用 " + String.format("%.1f", rowInner)
                        + " = " + String.format("%.0f%%", urlW / rowInner * 100)
                        + "，方法下拉 " + String.format("%.1f", methodW));
        snapshot("http-batch-three-stage-config");

        // ==================== 1.5 段① 改版：请求行 + 三页签 + 分段控件 ============
        System.out.println();
        TabPane tabs = panel.templateTabsForTest();
        check("段① 改版 · 有 Params / Headers / Body 三个页签（与单一请求页同名同序）",
                tabs != null && tabs.getTabs().size() == 3
                        && "Params".equals(tabs.getTabs().get(0).getText())
                        && "Headers".equals(tabs.getTabs().get(1).getText())
                        && tabs.getTabs().get(2).getText().startsWith("Body"),
                "页签 = " + tabTextsOf(tabs));
        check("段① 改版 · 请求体类型是分段控件（不是 ComboBox 下拉）",
                panel.bodyTypeSegmentForTest() != null
                        && !(panel.bodyTypeSegmentForTest() instanceof ComboBox)
                        && countToggles(panel.bodyTypeSegmentForTest()) == 5,
                "节点 = " + (panel.bodyTypeSegmentForTest() == null ? "null"
                        : panel.bodyTypeSegmentForTest().getClass().getSimpleName())
                        + "，项数 = " + countToggles(panel.bodyTypeSegmentForTest()));
        check("段① 改版 · 分段控件只列 batchTypes() 的 5 项"
                        + "（不给点了会出错的 Form-data / Binary 入口）",
                HttpRequestSpec.BodyType.batchTypes().size() == 5
                        && !containsLabel(panel.bodyTypeSegmentForTest(), "Form-data")
                        && !containsLabel(panel.bodyTypeSegmentForTest(), "Binary"),
                "项文案 = " + toggleTextsOf(panel.bodyTypeSegmentForTest()));
        // 口径说明：设计稿的 244 是**浏览器**口径（页签轨 42 + 编辑器 48 两行）。
        // JavaFX 里 Body 页签是一个整体 TabPane，三个数全是实测：
        //   154 = 轨 43.33 + content-area padding-top 14 + 内容 96
        //        （内容 96 = 工具行 38 + 间距 8 + 编辑器 50）
        // 所以段① 是 252.7。两个数都对，只是口径不同 —— 断言按 JavaFX 实测写。
        check("段① 改版 · 段① 实测高 376（加高后：TabPane 277 + 请求行 36 + 头 38 + 间距/内边距）",
                Math.abs(tpl.getHeight() - 376) < 4,
                "实测 " + String.format("%.1f", tpl.getHeight())
                        + "（容差 4：边框在 JavaFX 里被取整成 0.67）");
        check("段① 改版 · 内层页签高 277（.batch-tabs = 轨 43.33 + padding 14 + "
                        + "Params/Headers 内容 220；按最高的页签定，三页签共享此高度）",
                tabs != null && Math.abs(tabs.getHeight() - 277) < 1.5,
                "实测 " + (tabs == null ? "null"
                        : String.format("%.1f", tabs.getHeight())));
        // 原「整页正好铺满 653」与「段① 变矮后余量归段③」两条已随新取舍作废：
        // 段① 现在是**加高**不是变矮，整页也不再铺满（改为外层滚动）。
        // 换成仍然成立的真不变量：段③ 永远 ≥ 它的 340 保底。
        check("段① 改版 · 段③ 始终守住 340 保底（段① 加高也不挤掉结果区）",
                res.getHeight() >= 340 - 0.5,
                "段③ 实测 " + String.format("%.1f", res.getHeight())
                        + "（保底 340）｜整页 " + String.format("%.1f", innerH)
                        + "，外层滚动兜底");
        // 降级态：类型选「无」时编辑器降透明 + 右侧明写原因（不只靠颜色）
        check("段① 改版 · 选「无」时降级说明在位（禁用态必须配文字）",
                panel.declaredBodyTypeForTest() == HttpRequestSpec.BodyType.NONE
                        && panel.bodyNoteForTest() != null
                        && panel.bodyNoteForTest().isVisible(),
                "类型 = " + panel.declaredBodyTypeForTest()
                        + "，说明可见 = " + (panel.bodyNoteForTest() != null
                        && panel.bodyNoteForTest().isVisible()));
        // 切到 JSON：降级解除、Body 页签标题带类型、动作槽给「格式化」
        panel.selectBodyTypeForTest(HttpRequestSpec.BodyType.JSON);
        relayout();
        check("段① 改版 · 切到 JSON 后降级解除，Body 页签标题带类型",
                panel.declaredBodyTypeForTest() == HttpRequestSpec.BodyType.JSON
                        && !panel.bodyNoteForTest().isVisible()
                        && panel.bodyTabForTest().getText().contains("JSON"),
                "标题 = 「" + panel.bodyTabForTest().getText() + "」"
                        + "，说明可见 = " + panel.bodyNoteForTest().isVisible());
        // 本轮最关键的一条：TabPane 装不下时会把编辑器裁掉，
        // 而 bodyArea.getHeight() 仍是 50px —— 量高度量不到「被父容器裁掉」。
        check("段① 改版 · 请求体编辑器完整落在页签可视区内"
                        + "（TabPane 装不下会裁掉它，高度断言量不到）",
                panel.bodyEditorFullyVisibleForTest(),
                "编辑器完整可见 = " + panel.bodyEditorFullyVisibleForTest()
                        + "，高 " + String.format("%.1f",
                        panel.bodyAreaForTest().getHeight()));
        // ========== 1.7 Form 类型也要用键值表（与单一请求页对齐）==========
        // 用户 2026-10-03 报的差异：批处理页选 Form 长得完全不是那个东西 ——
        // 只有一个文本区，要手填 a=1&b=2；而单一请求页 Form 是一张键值表。
        // 现在批处理页的 Form 也用同一张 FormDataTable。
        panel.selectBodyTypeForTest(HttpRequestSpec.BodyType.FORM);
        relayout();
        check("段① Form · 选 Form 时显示键值表而不是文本区"
                        + "（与单一请求页同一个编辑器）",
                panel.bodyFormTableVisibleForTest()
                        && !panel.bodyAreaForTest().isVisible(),
                "Form 表可见 = " + panel.bodyFormTableVisibleForTest()
                        + "，文本区可见 = " + panel.bodyAreaForTest().isVisible());
        check("段① Form · Form 模式隐藏「类型」列（x-www-form-urlencoded 传不了文件）",
                !panel.bodyFormTableForTest().isFileRowsAllowed(),
                "fileRowsAllowed = " + panel.bodyFormTableForTest().isFileRowsAllowed());

        // 键值要真的进引擎模型（且按 CSV 行替换）
        panel.loadSampleForTest();
        relayout();
        panel.bodyFormTableForTest().clear();
        // 用样例 CSV 里**真实存在**的列（orderId / type / channel），
        // 否则占位符找不到对应列、会被原样保留 —— 那是正确行为，不是缺陷。
        panel.bodyFormTableForTest().setTextFieldsForTest("orderId", "{orderId}");
        panel.bodyFormTableForTest().setTextFieldsForTest("type", "{type}-{channel}");
        relayout();
        panel.prepareRowsForTest();
        var spec = panel.firstSpecForTest();
        check("段① Form · 键值对进引擎模型 formFields（不是只填在界面上）",
                spec != null && spec.formFields.size() == 2,
                "formFields = " + (spec == null ? "null" : spec.formFields));
        check("段① Form · 键值里的 {列名} 按 CSV 行替换（样例首行 orderId=235027703726）",
                spec != null
                        && "235027703726".equals(spec.formFields.get(0).getValue())
                        && "A-web".equals(spec.formFields.get(1).getValue()),
                "替换后 = " + (spec == null ? "null"
                        : spec.formFields.stream().map(e -> e.getKey() + "=" + e.getValue())
                        .toList()));
        check("段① Form · Form 的 body 文本不再被当请求体（那会与键值重复发送）",
                spec != null && spec.body.isBlank(),
                "body = 「" + (spec == null ? "null" : spec.body) + "」");
        // 切到 JSON：Form 键值要留着，且 JSON 用文本区
        panel.selectBodyTypeForTest(HttpRequestSpec.BodyType.JSON);
        relayout();
        check("段① Form · 切到 JSON 后 Form 键值不丢（各自内容各自持有）",
                panel.bodyFormTableForTest().entryCount() == 2
                        && panel.bodyAreaForTest().isVisible()
                        && !panel.bodyFormTableForTest().isVisible(),
                "Form 键值 = " + panel.bodyFormTableForTest().entryCount()
                        + " 项，文本区可见 = " + panel.bodyAreaForTest().isVisible());
        // Params 顺带修好的：以前那一页写着「这些键值会以 query 参数拼到 URL 之后」，
        // 但 prepareResultRows 只读了 url/headers/body 三处 —— **值从来没进过请求**。
        // 注意要先给 URL 设值 —— 此刻它可能是空的，
        // 而空 URL 拼上参数就是一个孤零零的「?trace=...」，
        // 那样断言「包含 trace=」会**恒真**（它只验了参数拼上了，
        // 没验「原地址还在」）。两条一起断才拦得住。
        String savedUrl = panel.urlFieldForTest().getText();
        panel.urlFieldForTest().setText("http://x.test/order/{orderId}");
        panel.paramsTableForTest().clear();
        panel.paramsTableForTest().setEntriesForTest(
                new java.util.LinkedHashMap<>() {{ put("trace", "{orderId}"); }});
        relayout();
        panel.prepareRowsForTest();
        var urlSpec = panel.firstSpecForTest();
        check("段① Params · query 参数真的拼到 URL 上了"
                        + "（那一页的说明文字以前是假的）",
                urlSpec != null
                        && urlSpec.url.startsWith("http://x.test/order/235027703726")
                        && urlSpec.url.contains("trace=235027703726"),
                "url = 「" + (urlSpec == null ? "null" : urlSpec.url) + "」");
        panel.paramsTableForTest().clear();
        panel.urlFieldForTest().setText(savedUrl);
        relayout();

        // 肉眼核对：Form 态（这就是用户截图里说「格式不一样」的那个界面）。
        // 必须先展开段① —— loadSampleForTest 导入 CSV 时会自动把它收起，
        // 否则拍到的是段② CSV 与段③，拍不到 Body 页签。
        tpl.setExpanded(true);
        panel.selectBodyTypeForTest(HttpRequestSpec.BodyType.FORM);
        panel.bodyFormTableForTest().clear();
        panel.bodyFormTableForTest().setTextFieldsForTest("orderId", "{orderId}");
        panel.bodyFormTableForTest().setTextFieldsForTest("type", "{type}");
        panel.bodyFormTableForTest().setTextFieldsForTest("channel", "{channel}");
        relayout();
        tabs.getSelectionModel().select(2);
        relayout();
        snapshot("http-batch-body-form");
        panel.selectBodyTypeForTest(HttpRequestSpec.BodyType.NONE);
        panel.bodyFormTableForTest().clear();
        relayout();

        // 肉眼核对：JSON 态（编辑器完整可见、工具行在页签之下）
        panel.bodyAreaForTest().setText("{\"orderId\": \"{orderId}\"}");
        relayout();
        snapshot("http-batch-template-body-json");
        tabs.getSelectionModel().select(0);
        relayout();
        tabs.getSelectionModel().select(2);
        relayout();
        check("段① 改版 · Params / Body 来回切，请求体内容不丢",
                "{\"orderId\": \"{orderId}\"}".equals(panel.bodyAreaForTest().getText()),
                "内容 = " + panel.bodyAreaForTest().getText());
        // 还原成「无」，别影响后面的执行流程断言
        panel.selectBodyTypeForTest(HttpRequestSpec.BodyType.NONE);
        panel.bodyAreaForTest().setText("");
        relayout();

        // ========== 1.6 段① 的表要能输入内容（用户 2026-10-03 报的缺陷）==========
        // 原缺陷：切到 Headers 后表只拿到 50.67px（表头 40 + 10.67），
        // **只看得见表头那一条**，用户报「间距太小，无法输入内容」。
        // 根因：三个页签共享一个 TabPane 高度，而它按 Body 页签（编辑器）定。
        check("段① 表 · Headers 能显示 ≥ 2 行（原来 0 行：只有表头那条）",
                panel.headersVisibleRowsForTest() >= 2,
                "可见 " + panel.headersVisibleRowsForTest() + " 行（表高 "
                        + String.format("%.1f", panel.headersTableForTest()
                        .tableForTest().getHeight()) + "）");
        check("段① 表 · 行高 40（用户要求 34 → 40，段① 专用不影响全站表格）",
                Math.abs(panel.headersRowHeightForTest() - 40) < 1.5,
                "实测行高 " + String.format("%.1f", panel.headersRowHeightForTest()));
        // 填 4 行 → 内容超出 3 行 → 必须能滚
        panel.headersTableForTest().setEntriesForTest(
                new java.util.LinkedHashMap<>() {{
                    put("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9");
                    put("X-Tenant-Id", "{tenantId}");
                    put("X-Trace", "{traceId}");
                    put("Accept", "application/json");
                }});
        relayout();
        check("段① 表 · 内容超出时竖滚动条可见（AtlantaFX 默认叠成透明）",
                panel.headersTableScrollableForTest(),
                "可滚 = " + panel.headersTableScrollableForTest());
        // 肉眼核对：这就是用户报「无法输入内容」的那个界面，
        // 必须切到 Headers 页签再拍（否则拍到的是 Body）
        tabs.getSelectionModel().select(1);
        relayout();
        snapshot("http-batch-headers-input");
        panel.headersTableForTest().clear();
        relayout();
        tabs.getSelectionModel().select(2);
        relayout();

        // ---- 2. 导入 CSV：②自动展开、①自动收起 ---------------------------
        panel.loadSampleForTest();
        relayout();
        check("导入 CSV 后②自动展开、①自动收起",
                csv.isExpanded() && !tpl.isExpanded(),
                "①=" + tpl.isExpanded() + " ②=" + csv.isExpanded());

        check("②头带摘要带出列名与行数",
                csv.getSummary().contains("orderId") && csv.getSummary().contains("3 行"),
                "“" + csv.getSummary() + "”");

        check("CSV 预览表整宽 ≥ 900（多列不横滚）",
                preview.getWidth() >= 900, "实测 " + preview.getWidth());
        snapshot("http-batch-three-stage-csv");

        // ---- 3. 手动 toggle 互不影响（互斥只在自动流程里触发） -------------
        // 配置阶段两段可以同时在场：用户点 ① 不该把 ② 收起来。
        // 溢出的高度交给外层滚动，不压缩任何一段。
        tpl.toggle();
        relayout();
        check("手动 toggle 互不影响：展开①后②仍展开",
                tpl.isExpanded() && csv.isExpanded(),
                "①=" + tpl.isExpanded() + " ②=" + csv.isExpanded());

        double bothOpenInner = innerNode.getLayoutBounds().getHeight();
        // 「没被压缩」是可证的：① 与配置态同高、③ 守住 340，多出来的全在 inner 上
        check("双段同时在场：溢出走外层滚动且两段都没被压缩",
                overflows(outer, innerNode)
                        && Math.abs(tpl.getHeight() - cfgTplH) < 1
                        && res.getHeight() >= 340,
                "inner=" + bothOpenInner + " 视口=" + outer.getViewportBounds().getHeight()
                        + " ①=" + tpl.getHeight() + "（配置态 " + cfgTplH + "）"
                        + " ②=" + csv.getHeight() + " ③=" + res.getHeight());

        // B 形态（①②同时在场）的响应详情实测：PM 文档里这一格现在是推算值
        javafx.scene.Node detailContent = detail.lookup(".content");
        System.out.println("[dbg] B形态详情 ③=" + res.getHeight()
                + " 分栏=" + table.getHeight()
                + " 结果表=" + rowsOf(table.getHeight()) + " 行"
                + " detailArea=" + detail.getHeight()
                + " .content=" + (detailContent == null ? "none"
                        : detailContent.getLayoutBounds().getHeight())
                + " → " + detailRowsOf(detail) + " 行");

        tpl.toggle();
        relayout();
        check("收起态只剩头带（≤ 44px）",
                tpl.getHeight() <= 44, "实测 " + tpl.getHeight());

        // ---- 4. ③不可折叠 -----------------------------------------------
        res.toggle();
        relayout();
        check("③是常驻段，toggle 不生效", res.isExpanded(), "③=" + res.isExpanded());

        // ---- 5. 执行时自动折叠，结果区吃满 -------------------------------
        double beforeRes = res.getHeight();
        int beforeRows = rowsOf(table.getHeight());

        panel.urlFieldForTest().setText("http://127.0.0.1:1/order/{orderId}");
        relayout();
        check("①头带摘要带出方法与地址",
                tpl.getSummary().contains("GET") && tpl.getSummary().contains("127.0.0.1"),
                "“" + tpl.getSummary() + "”");

        panel.runButtonForTest().fire();
        relayout();
        System.out.println("[dbg] 运行态 ①=" + tpl.getHeight()
                + " ②=" + csv.getHeight()
                + " ③=" + res.getHeight()
                + " inner=" + innerNode.getLayoutBounds().getHeight()
                + " 表宽=" + table.getWidth()
                + " 状态列=" + table.getColumns().get(1).getWidth()
                + " bar="+ ((javafx.scene.layout.Region) res.getChildren().get(0)).getHeight()
                + " body=" + res.content().getHeight()
                + " bodyVgrow=" + VBox.getVgrow(res.content())
                + " bodyMaxH=" + res.content().getMaxHeight()
                + " table=" + table.getHeight()
                + " detail=" + detail.getHeight());
        check("点开始执行后①、②自动收起",
                !tpl.isExpanded() && !csv.isExpanded(),
                "①=" + tpl.isExpanded() + " ②=" + csv.isExpanded());

        check("收起后③显著变高（≥ +150）",
                res.getHeight() - beforeRes >= 150,
                beforeRes + " → " + res.getHeight());

        int runRows = rowsOf(table.getHeight());
        check("运行态结果表 ≥ 11 行（较配置态翻倍）",
                runRows >= 11 && runRows > beforeRows,
                beforeRows + " 行 → " + runRows + " 行（表高 " + table.getHeight() + "）");

        int runDetailRows = detailRowsOf(detail);
        check("运行态响应详情 ≥ 20 行",
                runDetailRows >= 20 && runDetailRows > cfgDetailRows,
                cfgDetailRows + " 行 → " + runDetailRows + " 行（详情高 "
                        + detail.getHeight() + "）");
        snapshot("http-batch-three-stage-running");

        // ---- 6. CSS ------------------------------------------------------
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
