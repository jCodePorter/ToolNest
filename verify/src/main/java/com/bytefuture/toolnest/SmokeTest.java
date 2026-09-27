package com.bytefuture.toolnest;

import com.bytefuture.toolnest.core.ModuleCategory;
import com.bytefuture.toolnest.core.ToolModule;
import com.bytefuture.toolnest.core.ToolRegistry;
import com.bytefuture.toolnest.ui.ContentPane;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 全应用冒烟验证探针。
 *
 * <p>做三件事：
 * <ol>
 *   <li>启动真实应用骨架（含 app.css），逐个构建全部模块的界面，
 *       捕获任何异常 —— 模块的 buildContent() 是懒加载的，
 *       只编译不运行根本发现不了运行时错误</li>
 *   <li>把每个模块界面截图落盘，供人工核对视觉</li>
 *   <li>统计 CSS 解析错误 —— JavaFX 的 CSS 错误只在运行时上报，
 *       编译通过完全不代表样式正确</li>
 * </ol>
 *
 * <p>需由 {@code SmokeTestLauncher} 启动。仅用于开发期验证。
 */
public class SmokeTest extends Application {

    private static final File OUT_DIR = new File("target/smoke");

    private static int passed = 0;
    private static int failed = 0;
    private static final List<String> cssErrors = new ArrayList<>();

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name + (detail.isEmpty() ? "" : "  → " + detail));
        }
    }

    @Override
    public void start(Stage stage) {
        ToolRegistry.bootstrap();
        OUT_DIR.mkdirs();

        // 捕获 CSS 解析错误：JavaFX 通过未捕获异常处理器上报 CSS 问题
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            cssErrors.add(e.getMessage());
            System.out.println("[CSS/运行时异常] " + e.getMessage());
        });

        ContentPane pane = new ContentPane();
        StackPane root = new StackPane(pane);
        Scene scene = new Scene(root, 1280, 820);
        scene.getStylesheets().add(
                SmokeTest.class.getResource("/css/app.css").toExternalForm());

        stage.setScene(scene);
        stage.setWidth(1280);
        stage.setHeight(820);
        stage.show();
        root.applyCss();
        root.layout();

        System.out.println("=== 全应用冒烟验证 ===");
        System.out.println("已注册模块数：" + ToolRegistry.all().size());

        // ---------- 1. 逐个模块构建界面并截图 ----------
        for (ToolModule module : ToolRegistry.all()) {
            String id = module.id();
            try {
                pane.openDetail(module);
                root.applyCss();
                root.layout();

                // 强制完成一次布局与渲染，确保截图内容真实
                Node page = pane.getChildren().get(0);
                snapshot(page, id);

                check("模块界面构建成功：" + module.displayName(), true, "");
            } catch (Exception ex) {
                check("模块界面构建成功：" + module.displayName(), false,
                        ex.getClass().getSimpleName() + ": " + ex.getMessage());
                ex.printStackTrace();
            }
        }

        // ---------- 2. 分类宫格页构建 ----------
        for (ModuleCategory category : ToolRegistry.usedCategories()) {
            try {
                pane.showCategory(category);
                root.applyCss();
                root.layout();
                snapshot(pane.getChildren().get(0), "category-" + category.name());
                check("分类宫格页构建成功：" + category.label(), true, "");
            } catch (Exception ex) {
                check("分类宫格页构建成功：" + category.label(), false,
                        ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        }

        // ---------- 3. 所有工具页 ----------
        try {
            pane.showAllTools();
            root.applyCss();
            root.layout();
            snapshot(pane.getChildren().get(0), "all-tools");
            check("「所有工具」页构建成功", true, "");
        } catch (Exception ex) {
            check("「所有工具」页构建成功", false, ex.getMessage());
        }

        // ---------- 4. 分类覆盖：哪些分类已有内容 ----------
        System.out.println();
        System.out.println("--- 分类覆盖情况 ---");
        for (ModuleCategory category : ModuleCategory.values()) {
            int count = ToolRegistry.byCategory(category).size();
            System.out.println(String.format("%-20s %d 个功能%s",
                    category.label(), count, count == 0 ? "   <-- 仍为空" : ""));
        }
        check("空分类数应比改造前减少",
                ToolRegistry.usedCategories().size() >= 5,
                "当前有内容的分类数：" + ToolRegistry.usedCategories().size());

        // ---------- 5. CSS 错误 ----------
        System.out.println();
        check("未出现未捕获异常（含 CSS 解析错误）", cssErrors.isEmpty(),
                cssErrors.toString());

        System.out.println();
        System.out.println("截图已输出到：" + OUT_DIR.getAbsolutePath());
        System.out.println("RESULT passed=" + passed + " failed=" + failed);
        Platform.exit();
        System.exit(failed == 0 ? 0 : 1);
    }

    /** 把节点渲染成 PNG 落盘，便于人工核对视觉。 */
    private static void snapshot(Node node, String name) {
        try {
            WritableImage image = node.snapshot(null, null);
            File file = new File(OUT_DIR, name + ".png");
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file);
        } catch (Exception ex) {
            System.out.println("[截图失败] " + name + "：" + ex.getMessage());
        }
    }

    /** 供 Launcher 调用。 */
    public static void run(String[] args) {
        launch(args);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
