package com.bytefuture.toolnest.features.qrcode;

import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

/**
 * 一张图里存在多个二维码时，「识别结果」是否为每个码都给出条目。
 *
 * <h2>为什么需要这个探针</h2>
 * <p>旧实现是 {@code decodeResult.setText(results.get(0).text())} —— 一个只读文本域，
 * 结构上只装得下一个结果。解码器明明返回了多个码，界面却只显示第一个，
 * 另外几个被静默丢弃（状态栏还会写「共发现 N 个」，等于自己承认丢了数据）。
 *
 * <p>本轮把结果改成卡片列表后，「不丢数据」这件事第一次变得可以被破坏，
 * 因此必须有一条断言守着它。注意断言的方向：<b>比对的是「真实渲染出的卡片数」
 * 与「解码器返回的条数」</b>，而不是去读模块自己的结果集合 ——
 * 后者等于拿数据断言数据自己，恒真。
 *
 * <p>需由 {@link MultiQrProbeLauncher} 启动。
 */
public class MultiQrProbe extends Application {

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

    /** 把两个二维码横向拼到一张白底图上，形成「一张图多个码」。 */
    private static BufferedImage sideBySide(BufferedImage left, BufferedImage right) {
        int gap = 60;
        BufferedImage canvas = new BufferedImage(
                left.getWidth() + gap + right.getWidth(),
                Math.max(left.getHeight(), right.getHeight()),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        g.drawImage(left, 0, 0, null);
        g.drawImage(right, left.getWidth() + gap, 0, null);
        g.dispose();
        return canvas;
    }

    @Override
    public void start(Stage stage) {
        setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());

        QrModule module = new QrModule();
        Node content = module.buildContent();
        StackPane root = new StackPane(content);
        Scene scene = new Scene(root, 1300, 900);
        scene.getStylesheets().add(
                MultiQrProbe.class.getResource("/css/app.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
        root.applyCss();
        root.layout();

        System.out.println("=== 多码图片的识别结果列表 ===");

        final String textA = "https://example.com/alpha";
        final String textB = "https://example.com/beta";

        BufferedImage sample;
        List<QrOps.Decoded> decoded;
        try {
            // 用默认生成参数（512px / M 级纠错 / 4 模块边距），识别更稳
            QrOps.GenerateOptions opts = QrOps.GenerateOptions.defaults();
            BufferedImage a = QrOps.generate(textA, opts);
            BufferedImage b = QrOps.generate(textB, opts);
            sample = sideBySide(a, b);
            decoded = QrOps.decodeRobust(sample);

            File dir = new File("target/qr-list-probe");
            dir.mkdirs();
            ImageIO.write(sample, "png", new File(dir, "two-codes.png"));
        } catch (Exception ex) {
            check("构造两码样例图", false, ex.toString());
            finish();
            return;
        }

        check("样例图能解码出 2 个码（前置条件）",
                decoded.size() == 2, "实际 " + decoded.size());

        // 走真实界面链路：载入图片 → 渲染结果列表
        String first = module.decodeImageForTest(sample);
        root.applyCss();
        root.layout();

        check("返回的是第一个码的正文",
                textA.equals(first), String.valueOf(first));
        check("界面卡片数 == 解码条数（第 2 个码没有被丢弃）",
                module.resultCardCountForTest() == decoded.size(),
                "卡片 " + module.resultCardCountForTest() + " / 解码 " + decoded.size());

        String joined = module.decodeResultTextForTest();
        check("复制出来的文本同时包含两个码",
                joined != null && joined.contains(textA) && joined.contains(textB),
                String.valueOf(joined));

        // 单码图：卡片数必须回落到 1，证明断言不是「恒为 2」
        try {
            BufferedImage single = QrOps.generate(textA, QrOps.GenerateOptions.defaults());
            module.decodeImageForTest(single);
            root.applyCss();
            root.layout();
            check("换成单码图后卡片数回落到 1（证明上一条不是恒真）",
                    module.resultCardCountForTest() == 1,
                    "实际 " + module.resultCardCountForTest());
            // 再载回两码图，供截图留档
            module.decodeImageForTest(sample);
            root.applyCss();
            root.layout();
        } catch (Exception ex) {
            check("单码回落验证", false, ex.toString());
        }

        Platform.runLater(() -> Platform.runLater(() -> Platform.runLater(() -> {
            try {
                WritableImage img = scene.snapshot(null);
                File dir = new File("target/qr-list-probe");
                dir.mkdirs();
                File out = new File(dir, "multi-code-result.png");
                ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", out);
                check("截图已生成 " + out.getPath(), out.isFile() && out.length() > 0, "");
            } catch (Exception ex) {
                check("截图生成", false, ex.toString());
            }
            finish();
        })));
    }

    private void finish() {
        System.out.println();
        System.out.println("RESULT passed=" + passed + " failed=" + failed);
        Platform.exit();
        System.exit(failed == 0 ? 0 : 1);
    }
}
