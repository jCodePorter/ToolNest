package com.bytefuture.toolnest.features.diff;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 文本对比的界面级验证。
 *
 * <p>核心是「算出来的数 == 界面染出来的行」—— <b>纯逻辑 {@link DiffOps} 给出的
 * 新增 / 修改 / 删除行数与差异组数，必须在行视图与底部文案上一一对上</b>。
 * 任一处丢数据都会被这组交叉断言抓到（做法参照 {@code qrlist} 对「不丢数据」的处理）：
 * 算法说 6 行修改，界面上就必须真的染出 6 行。
 *
 * <p>另有一类断言是「左右严格对齐」：两栏的行节点数必须相等（占位行也是节点），
 * 否则下面的行会整体错位 —— 而错位是这种并排视图最致命的缺陷。
 *
 * <p>第三类断言是「面板内部平铺」：编辑态的 {@code TextArea} 与对比态的行视图
 * 都要一直铺到面板底边，中间不能留一段空白。这一条<b>只量渲染后的场景坐标</b>，
 * 不读 {@code vgrow} / {@code maxHeight} 之类的属性 —— 属性设了但被父容器的
 * {@code maxHeight} 卡住时，节点照样「看起来设对了」，只有量边界才看得出来。
 *
 * <p>需由 {@code DiffModuleProbeLauncher} 启动。
 */
public class DiffModuleProbe extends Application {

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

    private static <T extends Node> T find(Node node, Class<T> type, Predicate<T> filter) {
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

    /** 收集挂有某个样式类的节点。 */
    private static List<Node> collect(Node node, String styleClass) {
        List<Node> out = new ArrayList<>();
        if (node.getStyleClass().contains(styleClass)) {
            out.add(node);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                out.addAll(collect(child, styleClass));
            }
        }
        return out;
    }

    /**
     * 读一个 Region 渲染后的填充色，返回 {@code #rrggbb}；没有背景则返回 {@code <无>}。
     *
     * <p>这是「节点存在」与「节点真的上了色」的分界线：CSS 选择器写错时，
     * 节点会照常布局、照常有尺寸，只有填充是空的 —— 数节点数的断言发现不了。
     *
     * <p>注意 {@code Color.getRed()} 返回的是 0.0–1.0，转成 {@code #rrggbb} 必须乘 255。
     */
    private static String fillOf(Region region) {
        javafx.scene.layout.Background bg = region.getBackground();
        if (bg == null || bg.getFills().isEmpty()) {
            return "<无>";
        }
        javafx.scene.paint.Paint paint = bg.getFills().get(0).getFill();
        if (paint instanceof javafx.scene.paint.Color c) {
            return String.format("#%02x%02x%02x",
                    (int) Math.round(c.getRed() * 255),
                    (int) Math.round(c.getGreen() * 255),
                    (int) Math.round(c.getBlue() * 255));
        }
        return paint.toString();
    }

    /**
     * 某一类差异行（{@code diff-add} 等）的渲染填充色。
     *
     * <p>必须与 {@code diff-row} 组合起来取：光按类名收集会把行内的边条、
     * 标记列一起算进来，行数就数不准了。
     */
    private List<String> rowFills(String styleClass) {
        List<String> out = new ArrayList<>();
        for (Node node : collect(content, "diff-row")) {
            if (node.getStyleClass().contains(styleClass) && node instanceof Region region) {
                out.add(fillOf(region));
            }
        }
        return out;
    }

    /** 保留一位小数的数值，用于把「差了几像素」写进失败详情。 */
    private static String f(double value) {
        return String.format("%.1f", value);
    }

    /** 统计挂有某个样式类的节点数。 */
    private static int count(Node node, String styleClass) {
        int n = node.getStyleClass().contains(styleClass) ? 1 : 0;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                n += count(child, styleClass);
            }
        }
        return n;
    }

    /**
     * 只数「差异行」。
     *
     * <p>必须与 {@code diff-row} 组合起来判：{@code diff-add} 这类类名在行内也有，
     * 单独按类名数会把行内的边条与标记列一起算进来。
     *
     * @param styleClass 类型类名，{@code diff-add} 等；null 表示不限类型
     * @param sideClass  {@code diff-side-left} / {@code diff-side-right}；null 表示两栏都算
     */
    private static int countRows(Node node, String styleClass, String sideClass) {
        boolean hit = node.getStyleClass().contains("diff-row")
                && (styleClass == null || node.getStyleClass().contains(styleClass))
                && (sideClass == null || node.getStyleClass().contains(sideClass));
        int n = hit ? 1 : 0;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                n += countRows(child, styleClass, sideClass);
            }
        }
        return n;
    }

    private Node content;
    private Scene scene;
    private TextArea left;
    private TextArea right;

    /** 界面当前用的比对选项：与界面默认态（按词、三个忽略项都不勾）一致。 */
    private static final DiffOps.Options WORD_OPTIONS =
            new DiffOps.Options(DiffOps.Granularity.WORD, false, false, false);

    private static final String LEFT = """
            # 服务端配置
            host = 127.0.0.1
            port = 8080
            timeout = 30
            retry = 3
            log_level = info
            cache = false
            max_conn = 100
            db_pool = 10
            worker = 4
            enable_gzip = true
            db_url = jdbc:mysql://127.0.0.1:3306/app
            pool_min = 2
            pool_max = 20
            idle_timeout = 600
            charset = utf8mb4
            ssl_mode = disable
            read_only = false
            slow_query_ms = 500
            slow_log = /var/log/slow.log
            max_packet = 16M
            net_read_timeout = 30
            """;

    private static final String RIGHT = """
            # 服务端配置
            host = 127.0.0.1
            port = 9090
            timeout = 60
            retry = 5
            log_level = debug
            cache = false
            tls_enabled = true
            cert_path = /etc/ssl/app.pem
            max_conn = 100
            worker = 8
            enable_gzip = true
            db_url = jdbc:mysql://127.0.0.1:3306/app
            pool_min = 2
            pool_max = 20
            idle_timeout = 600
            charset = utf8mb4
            ssl_mode = require
            read_only = false
            slow_query_ms = 500
            slow_log = /var/log/slow.log
            max_packet = 16M
            net_read_timeout = 30
            """;

    @Override
    public void start(Stage stage) {
        content = new TextDiffModule().buildContent();
        StackPane root = new StackPane(content);
        scene = new Scene(root, 1266, 763);
        scene.getStylesheets().add(
                DiffModuleProbe.class.getResource("/css/app.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
        root.applyCss();
        root.layout();

        System.out.println("=== 文本对比 界面级验证 ===");

        left = find(content, TextArea.class, t -> t.getStyleClass().contains("diff-editor-left"));
        right = find(content, TextArea.class, t -> t.getStyleClass().contains("diff-editor-right"));
        check("找到两侧的编辑区", left != null && right != null,
                (left == null ? "左缺失 " : "") + (right == null ? "右缺失" : ""));
        if (left == null || right == null) {
            finish();
            return;
        }

        left.setText(LEFT);
        right.setText(RIGHT);
        after(450, this::phaseCounts);
    }

    // ---------- 一：算出来的数 == 染出来的行 ----------

    private void phaseCounts() {
        root().applyCss();
        root().layout();

        DiffOps.Result expect = DiffOps.diff(LEFT, RIGHT, WORD_OPTIONS);

        check("两侧都有内容后自动进入对比态（不必再点一次「完成」）",
                !left.isVisible() && !right.isVisible(),
                "左侧编辑区可见=" + left.isVisible() + "，右侧编辑区可见=" + right.isVisible());

        Label leftCount = find(content, Label.class,
                l -> l.getStyleClass().contains("diff-pane-count")
                        && isUnder(l, "diff-left-pane"));
        Label rightCount = find(content, Label.class,
                l -> l.getStyleClass().contains("diff-pane-count")
                        && isUnder(l, "diff-right-pane"));
        check("左栏面板头写「22 行」", leftCount != null && "22 行".equals(leftCount.getText()),
                leftCount == null ? "未找到" : leftCount.getText());
        check("右栏面板头写「23 行」", rightCount != null && "23 行".equals(rightCount.getText()),
                rightCount == null ? "未找到" : rightCount.getText());

        int leftLines = countRows(content, null, "diff-side-left");
        int rightLines = countRows(content, null, "diff-side-right");
        check("两栏行节点数相同（占位行也是节点，左右严格对齐）",
                leftLines == rightLines && leftLines == 24,
                leftLines + " / " + rightLines);
        check("类型不会跑错栏：左栏没有新增行、右栏没有删除行",
                countRows(content, "diff-add", "diff-side-left") == 0
                        && countRows(content, "diff-del", "diff-side-right") == 0,
                countRows(content, "diff-add", "diff-side-left") + " / "
                        + countRows(content, "diff-del", "diff-side-right"));

        // 交叉咬合：算法算出的行数，界面上必须真的染出那么多行
        check("咬合：算出 2 行新增，右栏真的染出 2 行",
                expect.addRows() == 2 && countRows(content, "diff-add", "diff-side-right") == 2,
                "算法 " + expect.addRows() + "，界面 "
                        + countRows(content, "diff-add", "diff-side-right"));
        check("咬合：算出 6 行修改，两栏各染出 6 行",
                expect.modRows() == 6 && countRows(content, "diff-mod", null) == 12,
                "算法 " + expect.modRows() + "，界面 "
                        + countRows(content, "diff-mod", null));
        check("咬合：算出 1 行删除，左栏真的染出 1 行",
                expect.delRows() == 1 && countRows(content, "diff-del", "diff-side-left") == 1,
                "算法 " + expect.delRows() + "，界面 "
                        + countRows(content, "diff-del", "diff-side-left"));
        check("占位行总数 = 新增行数 + 删除行数（缺失的一侧必须补行）",
                countRows(content, "diff-ph", null) == 3
                        && countRows(content, "diff-ph", "diff-side-left") == 2
                        && countRows(content, "diff-ph", "diff-side-right") == 1,
                "总 " + countRows(content, "diff-ph", null)
                        + "，左 " + countRows(content, "diff-ph", "diff-side-left")
                        + "，右 " + countRows(content, "diff-ph", "diff-side-right"));

        // 光「节点在、样式类在」还不够 —— 选择器与类名对不上时，节点照样布局、照样
        // visible，只是填充是透明的，数节点数完全看不出来。所以直接读渲染后的填充色。
        List<String> addFills = rowFills("diff-add");
        List<String> modFills = rowFills("diff-mod");
        List<String> delFills = rowFills("diff-del");
        check("三类差异行都真的上了底色（不是只有空壳节点）",
                addFills.size() == 2 && modFills.size() == 12 && delFills.size() == 1
                        && addFills.stream().noneMatch("<无>"::equals)
                        && modFills.stream().noneMatch("<无>"::equals)
                        && delFills.stream().noneMatch("<无>"::equals),
                "新增 " + addFills + "；修改 " + modFills + "；删除 " + delFills);
        check("行底色跟类型对得上（新增=绿 #eaf6ef，删除=红 #fdeeee，修改=暖 #fdf7ec）",
                addFills.contains("#eaf6ef") && delFills.contains("#fdeeee")
                        && modFills.contains("#fdf7ec"),
                "新增 " + addFills + "；删除 " + delFills + "；修改 " + modFills);

        // 底部状态条
        Label foot = find(content, Label.class,
                l -> l.getStyleClass().contains("diff-foot-stats"));
        check("底部状态写明差异处数，且与算法算出的组数一致",
                foot != null && foot.getText().contains("差异 " + expect.groups().size() + " 处")
                        && expect.groups().size() == 4,
                foot == null ? "未找到" : foot.getText());

        // 词级标记
        check("两侧都出现了词级标记（左侧删除线 / 右侧下划线）",
                count(content, "diff-word-del") > 0 && count(content, "diff-word-add") > 0,
                count(content, "diff-word-del") + " / " + count(content, "diff-word-add"));

        checkEqualWidth("两侧都有内容");

        // 对比态：行视图必须一直铺到面板底边（面板下半部分不能是一片空白）
        checkFillsPane("对比态：行视图铺满面板", leftScroll(), true);

        after(0, this::phaseOneSide);
    }

    /**
     * 断言左栏的内容区（编辑态的 {@code TextArea} / 对比态的行视图 {@code ScrollPane}）
     * <b>真的铺到了面板底边</b>。
     *
     * <p>量的是场景坐标里的上下边界，不是 {@code vgrow} / {@code maxHeight} 属性：
     * 属性设全了但被父容器的 {@code maxHeight} 卡住时，节点照样「看起来设对了」，
     * 只有边界会对不上 —— 这正是「面板下半部分全是空白」那个 bug 的形态。
     *
     * <p>另外要求高度显著大于 200px：否则「缩成一小条但上下边界偶然对齐」也会假绿。
     *
     * @param visibleNode 当前态下可见的那个内容节点
     * @param editorState true=编辑态（量 TextArea），false=对比态（量 ScrollPane）
     */
    private void checkFillsPane(String name, Region visibleNode, boolean editorState) {
        Region pane = find(content, Region.class, r -> r.getStyleClass().contains("diff-left-pane"));
        Region head = find(content, Region.class, r -> r.getStyleClass().contains("diff-pane-head")
                && isUnder(r, "diff-left-pane"));
        if (pane == null || head == null || visibleNode == null) {
            check(name, false, "面板=" + (pane != null) + " 头部=" + (head != null)
                    + " 内容区=" + (visibleNode != null));
            return;
        }
        double headBottom = head.localToScene(0, head.getHeight()).getY();
        double paneBottom = pane.localToScene(0, pane.getHeight()).getY();
        double top = visibleNode.localToScene(0, 0).getY();
        double bottom = visibleNode.localToScene(0, visibleNode.getHeight()).getY();
        double height = bottom - top;
        check(name + "（顶边接头部底边、底边接面板底边，且高度 > 200）",
                Math.abs(top - headBottom) <= 2 && Math.abs(bottom - paneBottom) <= 2
                        && height > 200,
                (editorState ? "编辑区" : "行视图") + " 顶 " + f(top) + " 底 " + f(bottom)
                        + " 高 " + f(height) + "；头部底 " + f(headBottom)
                        + "，面板底 " + f(paneBottom));
    }

    private Region leftScroll() {
        return find(content, Region.class, r -> r.getStyleClass().contains("diff-scroll-left"));
    }

    /** 两栏的宽度必须只由窗口宽度决定，不由内容决定。 */
    private void checkEqualWidth(String state) {
        double leftW = paneWidth("diff-left-pane");
        double rightW = paneWidth("diff-right-pane");
        check(state + "：两栏等宽（栏宽只由窗口决定，不由内容决定）",
                Math.abs(leftW - rightW) <= 1 && leftW > 300,
                "左 " + f(leftW) + " / 右 " + f(rightW));
    }

    // ---------- 一·补：只填了一侧 ----------

    private void phaseOneSide() {
        right.setText("");
        after(450, () -> {
            root().applyCss();
            root().layout();

            check("只填一侧时两栏都回到编辑态（此时行级高亮没有可比对象）",
                    left.isVisible() && right.isVisible(),
                    "左编辑器可见=" + left.isVisible() + "，右编辑器可见=" + right.isVisible());
            check("只填一侧时底部不报差异处数、复制按钮置灰",
                    labelText(null, "diff-foot-stats").contains("还差一侧")
                            && isDisabled("复制差异"),
                    "底部「" + labelText(null, "diff-foot-stats")
                            + "」；复制差异禁用=" + isDisabled("复制差异"));

            // 编辑态：TextArea 必须一直铺到面板底边
            checkFillsPane("编辑态：编辑区铺满面板", left, true);
            checkEqualWidth("只填一侧");

            shot("diff-one-side.png", this::restoreAndContinue);
        });
    }

    /** 按钮是否禁用（找不到也算禁用 —— 找不到按钮本身就是问题，不该算通过）。 */
    private boolean isDisabled(String buttonText) {
        Button button = find(content, Button.class, b -> buttonText.equals(b.getText()));
        return button == null || button.isDisabled();
    }

    private void restoreAndContinue() {
        right.setText(RIGHT);
        after(450, this::phaseGranularity);
    }

    // ---------- 二：粒度切换与忽略规则 ----------

    private void phaseGranularity() {
        ToggleButton byLine = find(content, ToggleButton.class, t -> "按行".equals(t.getText()));
        check("找到粒度分段项「按行」", byLine != null, "未找到");
        if (byLine != null) {
            byLine.fire();
            root().applyCss();
            root().layout();
            check("切到「按行」后行内不再着色（只做行级高亮）",
                    count(content, "diff-word-del") == 0 && count(content, "diff-word-add") == 0,
                    count(content, "diff-word-del") + " / " + count(content, "diff-word-add"));
        }

        CheckBox ignoreCase = find(content, CheckBox.class, c -> "忽略大小写".equals(c.getText()));
        check("找到「忽略大小写」勾选项", ignoreCase != null, "未找到");
        after(0, this::phaseSwap);
    }

    // ---------- 三：交换左右 ----------

    private void phaseSwap() {
        Button swap = find(content, Button.class, b -> "交换左右".equals(b.getText()));
        check("找到「交换左右」", swap != null, "未找到");
        if (swap == null) {
            after(0, this::phaseEqual);
            return;
        }
        swap.fire();
        root().applyCss();
        root().layout();
        check("交换后左右行数互换（左 23 / 右 22）",
                labelText("diff-left-pane", "diff-pane-count").equals("23 行")
                        && labelText("diff-right-pane", "diff-pane-count").equals("22 行"),
                labelText("diff-left-pane", "diff-pane-count") + " / "
                        + labelText("diff-right-pane", "diff-pane-count"));
        check("交换后染色随之互换：右栏 1 行新增 / 左栏 2 行删除",
                countRows(content, "diff-add", "diff-side-right") == 1
                        && countRows(content, "diff-del", "diff-side-left") == 2,
                "右新增 " + countRows(content, "diff-add", "diff-side-right")
                        + "，左删除 " + countRows(content, "diff-del", "diff-side-left"));

        swap.fire();
        root().applyCss();
        root().layout();
        after(0, this::phaseDegraded);
    }

    // ---------- 三·补：大文本降级 ----------
    //
    // 降级路径不走 LCS，只按行号逐位比对 —— 结论是简化过的，必须写出来，
    // 否则用户会以为「已经全对上了」（静默降级比降级本身更糟）。
    // 这里用「两侧只差一行」的大文本触发：行数积 > DP_CELL_LIMIT 即降级，
    // 又不会像「每行都不同」那样产生整块替换的上万行节点。

    private void phaseDegraded() {
        int big = 2200;
        String base = numbered(big, "line");
        left.setText(base);
        right.setText(base.replace("line 1201\n", "line 1201 changed\n"));
        after(600, () -> {
            root().applyCss();
            root().layout();
            String foot = labelText(null, "diff-foot-stats");
            check("大文本降级时底部明写「已按行号逐位比对」（不静默降级）",
                    foot.contains("已按行号逐位比对"), "底部「" + foot + "」");
            check("大文本降级时仍报出真实的差异处数（不能吞成 0 处）",
                    foot.contains("差异 1 处"), "底部「" + foot + "」");
            // 「复制差异」的置灰逻辑只验「一致时置灰」是不够的 —— 一路灰到底也算通过。
            // 所以这里补正向一脚：真有差异时必须可点，否则降级态虽然有差异却复制不出去。
            check("降级态：有差异时「复制差异」可用（置灰不能一路灰到底）",
                    !isDisabled("复制差异"), "复制差异禁用=" + isDisabled("复制差异"));
            shot("diff-degraded.png", this::restoreAndEqual);
        });
    }

    /**
     * 退回小文本：降级提示必须跟着消失。
     *
     * <p>提示粘住比提示缺失更糟 —— 用户会以为后面看到的所有结论都被简化过，
     * 从而不信任本来是精确算出来的结果。所以「降级时不静默」与「退回后不粘住」
     * 必须成对钉死，缺一条都只是半个保证。
     */
    private void restoreAndEqual() {
        left.setText(LEFT);
        right.setText(RIGHT);
        after(600, () -> {
            root().applyCss();
            root().layout();
            String foot = labelText(null, "diff-foot-stats");
            check("退回小文本后降级提示消失（提示不能粘住）",
                    !foot.contains("已按行号逐位比对"), "底部「" + foot + "」");
            phaseEqual();
        });
    }

    /** 造一份 {@code count} 行的文本（{@code prefix 1} … {@code prefix count}），用于触发降级路径。 */
    private static String numbered(int count, String prefix) {
        StringBuilder sb = new StringBuilder(count * 12);
        for (int i = 1; i <= count; i++) {
            sb.append(prefix).append(' ').append(i).append('\n');
        }
        return sb.toString();
    }

    // ---------- 四：一致 / 空 ----------

    private void phaseEqual() {
        right.setText(LEFT);
        after(450, () -> {
            root().applyCss();
            root().layout();
            check("两侧一致时底部报「差异 0 处」",
                    labelText(null, "diff-foot-stats").contains("差异 0 处"),
                    "底部「" + labelText(null, "diff-foot-stats") + "」");
            check("两侧一致时一行都不染色",
                    countRows(content, "diff-add", null) == 0
                            && countRows(content, "diff-del", null) == 0
                            && countRows(content, "diff-mod", null) == 0,
                    "新增 " + countRows(content, "diff-add", null)
                            + "，删除 " + countRows(content, "diff-del", null)
                            + "，修改 " + countRows(content, "diff-mod", null));
            check("两侧一致时「复制差异」置灰（没有差异可复制）", isDisabled("复制差异"),
                    "复制差异禁用=" + isDisabled("复制差异"));

            shot("diff-equal.png", () -> {
                Button clear = find(content, Button.class, b -> "清空".equals(b.getText()));
                if (clear != null) {
                    clear.fire();
                }
                after(150, this::phaseEmpty);
            });
        });
    }

    private void phaseEmpty() {
        root().applyCss();
        root().layout();
        check("清空后两侧内容真的没了",
                "".equals(left.getText()) && "".equals(right.getText()),
                "左 " + left.getText().length() + " 字符 / 右 " + right.getText().length() + " 字符");
        check("清空后回到编辑态（随时可以重新粘贴）",
                left.isVisible() && right.isVisible(),
                "左编辑器可见=" + left.isVisible() + "，右编辑器可见=" + right.isVisible());

        // 截图留档：重新填一份有差异的文本，让截图里有内容
        shot("diff-both-empty.png", () -> {
            left.setText(LEFT);
            right.setText(RIGHT);
            after(450, this::phaseShot);
        });
    }

    /**
     * 截一张全窗截图，再接着跑下一步。
     *
     * <p>嵌套三层 {@code runLater} 是必要的：{@code applyCss + layout} 只保证布局，
     * 不保证绘制完成 —— 内容刚变过时立刻 snapshot 会截到空块。
     */
    private void shot(String fileName, Runnable next) {
        root().applyCss();
        root().layout();
        Platform.runLater(() -> Platform.runLater(() -> Platform.runLater(() -> {
            try {
                WritableImage img = scene.snapshot(null);
                File dir = new File("target/diff-probe");
                dir.mkdirs();
                File out = new File(dir, fileName);
                ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", out);
                check("截图已生成 " + out.getPath(), out.isFile() && out.length() > 0, "");
            } catch (Exception ex) {
                check("截图生成 " + fileName, false, ex.toString());
            }
            next.run();
        })));
    }

    private void phaseShot() {
        root().applyCss();
        root().layout();
        Platform.runLater(() -> Platform.runLater(() -> Platform.runLater(() -> {
            try {
                WritableImage img = scene.snapshot(null);
                File dir = new File("target/diff-probe");
                dir.mkdirs();
                File out = new File(dir, "diff-two-columns.png");
                ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", out);
                check("截图已生成 " + out.getPath(), out.isFile() && out.length() > 0, "");
                check("截图体积合理（说明渲染出了内容）", out.length() > 20000,
                        out.length() + " 字节");
            } catch (Exception ex) {
                check("截图生成", false, ex.toString());
            }
            finish();
        })));
    }

    // ==================== 辅助 ====================

    private StackPane root() {
        return (StackPane) scene.getRoot();
    }

    private static void after(double ms, Runnable action) {
        if (ms <= 0) {
            Platform.runLater(action);
            return;
        }
        PauseTransition pause = new PauseTransition(Duration.millis(ms));
        pause.setOnFinished(e -> action.run());
        pause.play();
    }

    private static boolean isUnder(Node node, String paneClass) {
        Node current = node;
        while (current != null) {
            if (current.getStyleClass().contains(paneClass)) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private String labelText(String paneClass, String styleClass) {
        Label label = find(content, Label.class,
                l -> l.getStyleClass().contains(styleClass)
                        && (paneClass == null || isUnder(l, paneClass)));
        return label == null ? "" : label.getText();
    }

    private double paneWidth(String paneClass) {
        Region pane = find(content, Region.class, r -> r.getStyleClass().contains(paneClass));
        return pane == null ? -1 : pane.getWidth();
    }

    private void finish() {
        System.out.println();
        System.out.println("RESULT passed=" + passed + " failed=" + failed);
        Platform.exit();
        System.exit(failed == 0 ? 0 : 1);
    }
}
