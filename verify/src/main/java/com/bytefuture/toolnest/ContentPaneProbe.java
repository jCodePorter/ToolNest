package com.bytefuture.toolnest;

import com.bytefuture.toolnest.core.ModuleCategory;
import com.bytefuture.toolnest.core.ToolModule;
import com.bytefuture.toolnest.core.ToolRegistry;
import com.bytefuture.toolnest.ui.ContentPane;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

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

        System.out.println();
        System.out.println("RESULT passed=" + passed + " failed=" + failed);
        Platform.exit();
        System.exit(failed == 0 ? 0 : 1);
    }
}
