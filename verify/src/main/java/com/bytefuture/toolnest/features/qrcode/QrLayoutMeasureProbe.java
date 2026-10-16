package com.bytefuture.toolnest.features.qrcode;

import atlantafx.base.theme.PrimerLight;
import com.bytefuture.toolnest.app.ToolRegistry;
import com.bytefuture.toolnest.ui.shell.MainWindow;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.File;

/**
 * 一次性测量用（非产品功能，不含断言）：把二维码解析页在真实 1266×763
 * 窗口下渲染出来，逐节点打印实测高度/宽度，供设计稿「改造前」面积预算表取证。
 *
 * <p>之所以要它：设计稿里的「改造前」数字必须是量出来的，不能推。凡是从
 * 源码读出来的值都不算通过。
 */
public class QrLayoutMeasureProbe extends Application {

    private static final double W = 1266;
    private static final double H = 763;

    @Override
    public void start(Stage stage) {
        setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());
        ToolRegistry.bootstrap();

        MainWindow root = new MainWindow();
        Scene scene = new Scene(root, W, H);
        scene.getStylesheets().add(
                QrLayoutMeasureProbe.class.getResource("/css/app.css").toExternalForm());
        stage.setScene(scene);
        stage.setWidth(W);
        stage.setHeight(H);
        stage.show();

        root.openModule("qrcode");
        forceLayout(root);
        // 只保留解析页签（index 0）的测量：把生成页签内容摘掉，
        // 避免同一 TabPane 里两个页签的节点混在一起看不清结构。
        dump(scene);
        shoot(scene, "qr-decode-measure.png");

        Platform.exit();
    }

    private static void forceLayout(Parent p) {
        p.applyCss();
        p.layout();
    }

    private static String path(Node n) {
        StringBuilder sb = new StringBuilder();
        Node cur = n;
        while (cur != null) {
            String cls = cur.getStyleClass().isEmpty()
                    ? cur.getClass().getSimpleName()
                    : cur.getClass().getSimpleName() + "." + String.join(".", cur.getStyleClass());
            sb.insert(0, " > " + cls);
            cur = cur.getParent();
        }
        return sb.substring(Math.min(3, sb.length()));
    }

    private static void dump(Scene scene) {
        System.out.println("== scene " + (int) scene.getWidth() + "x" + (int) scene.getHeight());
        Parent r = scene.getRoot();
        walk(r, 0);
    }

    private static void walk(Node n, int depth) {
        Bounds b = n.localToScene(n.getBoundsInLocal());
        String cls = String.join(".", n.getStyleClass());
        boolean interesting = n instanceof javafx.scene.layout.Region
                || n instanceof javafx.scene.control.Control;
        boolean named = cls.contains("qr") || cls.contains("card") || cls.contains("split")
                || cls.contains("module-body") || cls.contains("page-header")
                || cls.contains("detail") || cls.contains("tab") || cls.contains("notice")
                || cls.contains("action") || cls.contains("preview") || cls.contains("image")
                || n.getClass().getSimpleName().equals("Button")
                || n.getClass().getSimpleName().equals("Label")
                || n.getClass().getSimpleName().equals("HBox");
        boolean inDecodeTab = b.getMinY() >= 150 && b.getMinY() < 790;
        if (interesting && named && inDecodeTab && b.getMinY() < 800) {
            System.out.printf("y=%-7.1f h=%-7.1f w=%-7.1f  %-18s [%s]%n",
                    b.getMinY(), b.getHeight(), b.getWidth(),
                    n.getClass().getSimpleName(), cls);
        }
        if (n instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                walk(c, depth + 1);
            }
        }
    }

    private static void shoot(Scene scene, String name) {
        try {
            WritableImage img = scene.snapshot(null);
            File out = new File("build/qr-measure");
            out.mkdirs();
            ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", new File(out, name));
            System.out.println("[shot] build/qr-measure/" + name);
        } catch (Exception ex) {
            System.out.println("[shot failed] " + ex);
        }
    }
}
