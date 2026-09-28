package com.bytefuture.toolnest.features.qrcode;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;

/**
 * 框选端到端探针：复刻「用户截图后框选二维码」的真实链路。
 *
 * <p>用不同**宽高比**与**尺寸**的图各跑一遍，确认：
 * <ol>
 *   <li>框选层尺寸与图片等比显示尺寸一致</li>
 *   <li>鼠标按下点 → 框选起点 精确对应</li>
 *   <li>框选 → 裁切 → 识别 全链路正确</li>
 * </ol>
 *
 * <p>这张表的每一行都对应一类真实截图（手机截屏 16:9、微信截图、
 * 正方形二维码图……）。历史 bug 只在「原图长宽比 ≠ 1」时暴露，
 * 所以必须覆盖多种宽高比。
 */
public class SelectionE2EProbe extends Application {

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

    /** 造一张 w×h 的图，在 (qrX, qrY) 处画一个 size×size 的二维码。 */
    private static BufferedImage withQr(int w, int h, String payload,
                                       int qrX, int qrY, int size) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(0xDD, 0xDD, 0xDD));
        g.fillRect(0, 0, w, 30);
        BufferedImage qr = QrOps.generate(payload,
                new QrOps.GenerateOptions(size, QrOps.Level.M, 4, Color.BLACK, Color.WHITE));
        g.drawImage(qr, qrX, qrY, null);
        g.dispose();
        return img;
    }

    /** 对一张图跑完整框选链路，并逐一断言。 */
    private static void runCase(com.bytefuture.toolnest.features.qrcode.QrModule module,
                                javafx.scene.Parent root,
                                String label, BufferedImage img,
                                int qrX, int qrY, int qrSize, String expected) throws Exception {
        module.decodeImageForTest(img);
        // 关键：selectionLayer 的 getWidth() 只有在**布局完成后**才反映
        // setPrefWidth 的值。decodeImageForTest 刚设完 pref，必须跑一遍
        // applyCss + layout，否则读到的还是上一轮的旧尺寸（这是探针手法问题，
        // 不是产品缺陷 —— 真实应用里布局由 JavaFX 自动完成）。
        root.applyCss();
        root.layout();

        double[] shown = com.bytefuture.toolnest.features.qrcode.QrModule
                .displaySizePublic(img.getWidth(), img.getHeight());
        double shownW = shown[0];
        double shownH = shown[1];
        double scale = shownW / img.getWidth();

        // 断言 1：框选层尺寸 == 等比显示尺寸
        double lw = module.selectionLayerWidthForTest();
        double lh = module.selectionLayerHeightForTest();
        check(label + " 框选层尺寸 == 等比显示尺寸（" + (int) shownW + "×" + (int) shownH + "）",
                Math.abs(lw - shownW) <= 1.5 && Math.abs(lh - shownH) <= 1.5,
                "框选层=" + lw + "×" + lh);

        // 断言 2：鼠标按下点 → 框选起点（走真实 handler + 换算）
        double px = qrX * scale + 3;
        double py = qrY * scale + 3;
        double endX = px + qrSize * scale - 6;
        double endY = py + qrSize * scale - 6;
        module.simulateDragForTest(px, py, endX, endY);
        check(label + " 框选起点跟随鼠标落点",
                Math.abs(module.selectionXForTest() - px) <= 1.0
                        && Math.abs(module.selectionYForTest() - py) <= 1.0,
                "起点=(" + module.selectionXForTest() + "," + module.selectionYForTest()
                        + ") 应为 (" + px + "," + py + ")");

        // 断言 2b：跑一遍布局后，框选矩形**渲染出来的**位置仍等于鼠标落点。
        //
        // 这一步不能省：断言 2 读的是矩形的 x/y 属性（逻辑值），而真正被用户
        // 看到的框是由父容器布局后决定的位置。曾经的 bug 就是属性值完全正确、
        // 渲染位置却被父容器（StackPane）在布局期 relocate() 抵消成 (0,0)，
        // 表现为「无论从哪拖，框都从图片左上角长出来」。
        // 只断言属性值的测试对它 100% 无感，所以这里必须改几何后再布局一次，
        // 再断言 boundsInParent（= 真实渲染位置）。
        root.applyCss();
        root.layout();
        check(label + " 框选框渲染位置 == 鼠标落点（布局后仍不漂移）",
                Math.abs(module.selectionRenderedXForTest() - px) <= 1.5
                        && Math.abs(module.selectionRenderedYForTest() - py) <= 1.5,
                "渲染位置=(" + module.selectionRenderedXForTest() + ","
                        + module.selectionRenderedYForTest() + ") 应为 (" + px + "," + py + ")");
        check(label + " 框选框渲染尺寸 == 拖拽范围",
                Math.abs(module.selectionRenderedWidthForTest() - (endX - px)) <= 2.0
                        && Math.abs(module.selectionRenderedHeightForTest() - (endY - py)) <= 2.0,
                "渲染尺寸=" + module.selectionRenderedWidthForTest() + "×"
                        + module.selectionRenderedHeightForTest()
                        + " 应为 " + (endX - px) + "×" + (endY - py)
                        + "（容差 2px：boundsInParent 含 1.5px 描边，两侧各 0.75）");

        module.simulateDragReleaseForTest();

        // 断言 3：框选 → 裁切 → 识别
        String got = module.decodeWithSelectionForTest();
        check(label + " 框选后能识别出二维码内容",
                expected.equals(got),
                got == null ? "识别失败" : ("得到「" + got + "」"));
    }

    @Override
    public void start(Stage stage) {
        Thread.setDefaultUncaughtExceptionHandler((t, ex) -> problems.add(String.valueOf(ex)));

        com.bytefuture.toolnest.features.qrcode.QrModule module =
                new com.bytefuture.toolnest.features.qrcode.QrModule();

        // 把模块界面挂到一个真实 Scene 上（框选层需要布局才有尺寸）
        StackPane root = new StackPane(module.buildContent());
        Scene scene = new Scene(root, 1200, 800);
        stage.setScene(scene);
        stage.show();
        root.applyCss();
        root.layout();

        try {
            // 用例 1：横向长方形（模拟桌面截图 1200×800）
            runCase(module, root, "[1200×800 横向]",
                    withQr(1200, 800, "CASE-WIDE", 900, 500, 260),
                    900, 500, 260, "CASE-WIDE");

            // 用例 2：纵向长方形（模拟手机截屏 1080×1920 的一半）
            runCase(module, root, "[900×1400 纵向]",
                    withQr(900, 1400, "CASE-TALL", 120, 1000, 240),
                    120, 1000, 240, "CASE-TALL");

            // 用例 3：正方形但超过预览上限（900×900）
            runCase(module, root, "[900×900 正方形]",
                    withQr(900, 900, "CASE-SQUARE", 560, 120, 280),
                    560, 120, 280, "CASE-SQUARE");

            // 用例 4：小图（不触发缩放，350×350）
            runCase(module, root, "[350×350 小于上限]",
                    withQr(350, 350, "CASE-SMALL", 30, 200, 110),
                    30, 200, 110, "CASE-SMALL");
        } catch (Exception ex) {
            check("框选端到端用例执行", false,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
            ex.printStackTrace();
        }

        System.out.println();
        System.out.println("RESULT passed=" + passed + " failed=" + failed);
        if (!problems.isEmpty()) {
            System.out.println("（捕获到 " + problems.size() + " 条未捕获异常）");
            for (String p : problems) {
                System.out.println("   " + p);
            }
        }
        System.out.flush();
        System.exit(failed > 0 ? 1 : 0);
    }
}
