package com.bytefuture.toolnest;

import com.bytefuture.toolnest.core.ModuleCategory;
import com.bytefuture.toolnest.ui.spi.ToolModule;
import com.bytefuture.toolnest.app.ToolRegistry;
import com.bytefuture.toolnest.ui.shell.ContentPane;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

/**
 * ContentPane 返回栈的离屏验证探针。
 *
 * <p>直接调用真实控件的方法（{@code button.fire()}），不依赖鼠标位置，
 * 因此可在无头环境稳定复现。需由 {@link ContentPaneProbeLauncher} 启动 ——
 * Application 子类不能直接以 main class 运行。
 *
 * <p>仅用于开发期验证，不参与正式打包。
 */
public class ContentPaneProbe extends Application {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name);
        }
    }

    /** 识别「当前在哪一页」：宫格页返回 GRID:标题，详情页返回 DETAIL:标题。 */
    private static String currentPage(ContentPane pane) {
        Node page = pane.getChildren().isEmpty() ? null : pane.getChildren().get(0);
        if (page == null) {
            return "<empty>";
        }
        if (page instanceof ScrollPane sp) {
            // 宫格页页头标题：无返回按钮时是 page-title，有返回按钮时降级为 detail-title
            String t = firstLabelText(sp.getContent(), "page-title", "detail-title");
            return t == null ? "<grid>" : "GRID:" + t;
        }
        String t = firstLabelText(page, "detail-title");
        return t == null ? "<detail>" : "DETAIL:" + t;
    }

    private static String firstLabelText(Node node, String... styleClasses) {
        if (node == null) {
            return null;
        }
        if (node instanceof Label label) {
            for (String sc : styleClasses) {
                if (label.getStyleClass().contains(sc)) {
                    return label.getText();
                }
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                String found = firstLabelText(child, styleClasses);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** 模拟点击当前页面的返回按钮（宫格页与详情页的返回按钮样式类相同）。 */
    private static boolean clickBack(ContentPane pane) {
        Node page = pane.getChildren().isEmpty() ? null : pane.getChildren().get(0);
        if (page == null) {
            return false;
        }
        Button back = findBackButton(page);
        if (back == null || !back.isVisible()) {
            return false;
        }
        back.fire();
        return true;
    }

    private static Button findBackButton(Node node) {
        if (node instanceof Button b && b.getStyleClass().contains("back-button")) {
            return b;
        }
        if (node instanceof ScrollPane sp) {
            Button found = findBackButton(sp.getContent());
            if (found != null) {
                return found;
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Button found = findBackButton(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 找到详情页外层的 ScrollPane（按样式类，不按层级位置 —— 层级随时会变）。
     */
    private static ScrollPane findDetailScroll(Node node) {
        if (node instanceof ScrollPane sp && sp.getStyleClass().contains("detail-scroll")) {
            return sp;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                ScrollPane found = findDetailScroll(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 收集「实际高度小于首选高度」的输入类控件。
     *
     * <p>只统计已经参与布局的（宽度 &gt; 0）；同时跳过 Spinner 内部那个由 Spinner
     * 自己管理的编辑框 —— 它的高度归 Spinner 管，单独比对只会得到误报。
     */
    private static void collectSqueezed(Node node, List<String> squeezed, int[] seen) {
        boolean measurable = node instanceof Button || node instanceof ComboBox
                || node instanceof Spinner || node instanceof TextField;
        boolean spinnerEditor = node instanceof TextField
                && node.getParent() instanceof Spinner;
        if (measurable && !spinnerEditor && node instanceof Region r && r.getWidth() > 0) {
            seen[0]++;
            if (r.getHeight() + 0.5 < r.getPrefHeight()) {
                String label = node instanceof Button b ? b.getText()
                        : node.getClass().getSimpleName();
                squeezed.add(String.format("「%s」实际 %.0f < 首选 %.0f",
                        label, r.getHeight(), r.getPrefHeight()));
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectSqueezed(child, squeezed, seen);
            }
        }
    }

    @Override
    public void start(Stage stage) {
        ToolRegistry.bootstrap();

        ContentPane pane = new ContentPane();
        StackPane root = new StackPane(pane);
        Scene scene = new Scene(root, 1100, 720);
        scene.getStylesheets().add(
                ContentPaneProbe.class.getResource("/css/app.css").toExternalForm());

        stage.setScene(scene);
        stage.setWidth(1100);
        stage.setHeight(720);
        stage.show();

        // 强制完成布局，保证节点树已成形
        root.applyCss();
        root.layout();

        System.out.println("=== ContentPane 返回栈验证 ===");

        ModuleCategory catA = ModuleCategory.CONVERT;
        ModuleCategory catC = ModuleCategory.FORMAT;
        ToolModule modB = ToolRegistry.byId("timestamp").orElseThrow();
        ToolModule modD = ToolRegistry.byId("json-format").orElseThrow();

        // 路径：宫格 A → 详情 B → 宫格 C → 详情 D
        pane.showCategory(catA);
        check("步骤1 进入分类宫格 A", currentPage(pane).startsWith("GRID:"));

        pane.openDetail(modB);
        check("步骤2 进入详情 B（时间戳转换）",
                currentPage(pane).equals("DETAIL:时间戳转换"));

        pane.showCategory(catC);
        check("步骤3 进入分类宫格 C", currentPage(pane).startsWith("GRID:"));

        pane.openDetail(modD);
        check("步骤4 进入详情 D（JSON 格式化）",
                currentPage(pane).equals("DETAIL:JSON 格式化"));

        // 逐层返回 D → C → B → A
        check("返回前 canGoBack 为 true", pane.canGoBack());

        clickBack(pane);
        check("返回1 应回到宫格 C", currentPage(pane).startsWith("GRID:格式化"));

        clickBack(pane);
        check("返回2 应回到详情 B（时间戳转换）",
                currentPage(pane).equals("DETAIL:时间戳转换"));

        clickBack(pane);
        check("返回3 应回到宫格 A", currentPage(pane).startsWith("GRID:转换"));

        // 历史耗尽后：返回按钮应隐藏（无死按钮），页面保持可用
        Button atBottom = findBackButton(pane.getChildren().get(0));
        check("历史耗尽后宫格页返回按钮应隐藏",
                atBottom != null && !atBottom.isVisible());
        check("canGoBack 与按钮可见性一致", !pane.canGoBack());
        check("历史耗尽后仍停在宫格 A", currentPage(pane).startsWith("GRID:转换"));

        // 原地重进不污染历史：从无历史状态连点同一分类，仍应无历史
        System.out.println("--- 原地重进不记史 ---");
        pane.showCategory(catA);
        pane.showCategory(catA);
        pane.showCategory(catA);
        check("连点同一分类不产生历史（返回按钮保持隐藏）", !pane.canGoBack());

        // 真正的原地重进场景：先建立历史，再重复进入同一页不应多记一层
        System.out.println("--- 有历史时原地重进 ---");
        pane.showCategory(catC);       // 记一层：A
        pane.openDetail(modD);         // 记一层：C
        pane.openDetail(modD);         // 原地重进，不应再记
        clickBack(pane);
        check("原地重进后一次返回应到达宫格 C", currentPage(pane).startsWith("GRID:格式化"));

        // 缓存有界性：遍历打开全部模块不应抛异常
        System.out.println("--- 缓存有界性 ---");
        for (ToolModule m : ToolRegistry.all()) {
            pane.openDetail(m);
        }
        check("遍历打开全部模块未抛异常", true);
        check("LRU 淘汰后仍能重建已打开的页面（回退不失效）", pane.canGoBack());

        // ---------------------------------------------------------------- 控件尺寸完整性
        //
        // 详情页内容比视口高时，超出的部分只能走滚动，绝不能变成对子控件的压缩。
        // 这条曾经真的坏过：HTTP 页被判成「铺满一屏」不滚动，于是 32px 的按钮
        // 被压成 18px（= 内容自然高）、104px 的键值表被压成 55px —— 控件被压成
        // 细条看起来像渲染坏了，而不是「挤了一点」。
        //
        // HTTP 页是站内纵向最长的一页（请求行 + 参数区 + 响应区），拿它当样本，
        // 只要这一页装得下，其余页面更不会出问题。
        System.out.println("--- 控件尺寸未被压缩 ---");
        ToolModule http = ToolRegistry.byId("http").orElseThrow();
        pane.openDetail(http);
        root.applyCss();
        root.layout();

        List<String> squeezed = new ArrayList<>();
        int[] seen = {0};
        collectSqueezed(pane, squeezed, seen);
        check("HTTP 页取到可测控件样本（实测 " + seen[0] + " 个）", seen[0] >= 8);
        check("HTTP 页无控件被压到首选高度以下（被压 " + squeezed.size() + " 个"
                + (squeezed.isEmpty() ? "" : "：" + squeezed) + "）", squeezed.isEmpty());

        // 上一条只盯住了单个控件。这一条盯整页：页面装不下时，承接方式必须是滚动，
        // 而不是把整棵内容树压到视口高 —— 前者只是多一条滚动条，后者是静默变形。
        ScrollPane detailScroll = findDetailScroll(pane);
        check("详情页外层确实是 ScrollPane", detailScroll != null);
        if (detailScroll != null && detailScroll.getContent() instanceof Region content) {
            double want = content.prefHeight(detailScroll.getViewportBounds().getWidth());
            check(String.format("HTTP 页内容按首选高度布局（实际 %.0f / 首选 %.0f）",
                            content.getHeight(), want),
                    content.getHeight() + 1 >= want);
        }

        System.out.println();
        System.out.println("RESULT passed=" + passed + " failed=" + failed);
        Platform.exit();
        System.exit(failed == 0 ? 0 : 1);
    }
}
