package com.bytefuture.toolnest.features.qrcode;

import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
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
        // 【口径已于 2026-10-01 变更】原断言是「卡片数 == 解码条数」。
        // 设计定案后列表只画 6 张整卡（超出靠滚动，不画半截卡），
        // 所以正确的是 min(结果数, 6)——2 个码时仍应为 2。
        // 不能放宽成「卡片数 <= 结果数」：那等于把断言放空，
        // 「第 2 个码有没有被丢弃」这件事就再也守不住了。
        check("界面卡片数 == min(解码条数, 6)（第 2 个码没有被丢弃）",
                module.resultCardCountForTest() == Math.min(decoded.size(), 6),
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

        // ==================== 列表上限口径（2026-10-01 定案）====================
        // 只画 6 张整卡：超出的结果仍在数据里（可复制、可导出），只是不进节点。
        // 不画半截卡 —— 一张被裁掉一半的卡片看着仍像可点的完整卡。
        try {
            module.setSyntheticResultsForTest(9);
            root.applyCss();
            root.layout();
            check("数据侧仍是 9 条（没有在数据层截断）",
                    module.rawResultCountForTest() == 9,
                    "实际 " + module.rawResultCountForTest());
            check("列表只画 6 张整卡（上限真的在起作用，不是恒等于结果数）",
                    module.resultCardCountForTest() == 6,
                    "实际 " + module.resultCardCountForTest());
            check("计数徽标显示总数 9（不是显示 6）",
                    "9".equals(module.resultCountBadgeForTest()),
                    String.valueOf(module.resultCountBadgeForTest()));
            check("标题行汇总写明「共 9 个二维码 · 显示 6 个」",
                    "共 9 个二维码 · 显示 6 个".equals(module.resultSummaryForTest()),
                    String.valueOf(module.resultSummaryForTest()));

            // 反向验证：刚好 6 条时不应出现「显示 6 个」这种冗余说明
            module.setSyntheticResultsForTest(6);
            root.applyCss();
            root.layout();
            check("恰好 6 条时卡片数 == 6 且汇总不写「显示」（不是恒写上限）",
                    module.resultCardCountForTest() == 6
                            && "共 6 个二维码".equals(module.resultSummaryForTest()),
                    module.resultCardCountForTest() + " / " + module.resultSummaryForTest());

            module.clearDecodeResultForTest();
            root.applyCss();
            root.layout();
            check("清空后卡片数 == 0 且汇总为空（上限断言不是恒真）",
                    module.resultCardCountForTest() == 0
                            && "".equals(module.resultSummaryForTest()),
                    module.resultCardCountForTest() + " / " + module.resultSummaryForTest());
        } catch (Exception ex) {
            check("列表上限口径验证", false, ex.toString());
        }

        // ==================== 批注 A / B / C 的结构落点 ====================
        // 这三条守的是「布局改造后各部件还在它该在的位置」。
        // 全部落在**真实节点树**上，不读模块的布尔字段。
        try {
            module.clearDecodeResultForTest();
            module.decodeImageForTest(sample);
            root.applyCss();
            root.layout();

            // 批注 A：三个取输入动作 + 移除图片，共处页签行右端的动作行
            javafx.scene.layout.HBox actions = module.decodeActionsForTest();
            check("页签行右端存在动作行", actions != null, "null");
            if (actions != null) {
                List<String> labels = new ArrayList<>();
                int buttonCount = 0;
                for (Node n : actions.getChildren()) {
                    if (n instanceof javafx.scene.control.Button b) {
                        labels.add(b.getText());
                        buttonCount++;
                    }
                }
                check("动作行含 4 个按钮（选择图片 / 粘贴剪贴板 / 清除框选 / 移除图片）",
                        buttonCount == 4, labels.toString());
                check("动作行第 1 个是「选择图片…」（取输入组在前）",
                        labels.size() >= 1 && labels.get(0).startsWith("选择图片"), labels.toString());
                check("动作行最末一个是「移除图片」（丢弃组在后）",
                        labels.size() >= 4 && "移除图片".equals(labels.get(3)), labels.toString());
                // 第 4 个按钮必须带图标（批注 C：图标 + 文字，不是纯文字）
                Button rm = module.removeImgBtnForTest();
                check("「移除图片」带图标（不是纯文字按钮）",
                        rm != null && rm.getGraphic() != null, "graphic=null");
                check("「移除图片」不是危险色描边按钮（已降为静默动作）",
                        rm != null && !rm.getStyleClass().contains("danger-button"),
                        rm == null ? "null" : rm.getStyleClass().toString());
                // 分组竖线：1px 宽、18px 高，夹在第 3 与第 4 个按钮之间
                boolean hasSep = actions.getChildren().stream()
                        .anyMatch(n -> n.getStyleClass().contains("qr-act-sep"));
                check("动作行内有一条分隔线隔开两组", hasSep, "未找到 .qr-act-sep");
            }

            // 批注 B：底部按钮行已删除，且动作已落到每张卡片内
            javafx.scene.Parent rightPane = module.resultHeadForTest();
            check("结果区标题行存在", rightPane != null, "null");
            if (rightPane != null) {
                long headButtons = rightPane.lookupAll(".button").stream()
                        .filter(n -> n instanceof javafx.scene.control.Button).count();
                check("结果区标题行内不含任何按钮（清空与复制都已移走）",
                        headButtons == 0, "找到 " + headButtons + " 个");
            }
            // 卡内两个动作：复制 / 打开
            javafx.scene.Scene sc = scene;
            long cardCopy = sc.getRoot().lookupAll(".qr-result-card .button").stream()
                    .filter(n -> n instanceof javafx.scene.control.Button b
                            && "复制".equals(b.getText())).count();
            check("每张结果卡内部都有「复制」动作（卡片数 2 → 应有 2 个）",
                    cardCopy == 2, "找到 " + cardCopy + " 个");
            long cardOpen = sc.getRoot().lookupAll(".qr-result-card .button").stream()
                    .filter(n -> n instanceof javafx.scene.control.Button b
                            && "打开".equals(b.getText())).count();
            check("每张结果卡内部都有「打开」动作（不因内容非网址而隐藏）",
                    cardOpen == 2, "找到 " + cardOpen + " 个");
            boolean anyDisabledOpen = sc.getRoot().lookupAll(".qr-result-card .button").stream()
                    .filter(n -> n instanceof javafx.scene.control.Button b
                            && "打开".equals(b.getText()))
                    .anyMatch(n -> ((javafx.scene.control.Button) n).isDisable());
            check("没有任何一张卡的「打开」处于禁用态（Q6 定案）",
                    !anyDisabledOpen, "存在禁用的「打开」");
            // 旧的底部按钮行已不存在于右栏末位
            boolean bottomRowGone = sc.getRoot().lookupAll(".button").stream()
                    .filter(n -> n instanceof javafx.scene.control.Button b)
                    .noneMatch(n -> "复制结果".equals(((javafx.scene.control.Button) n).getText())
                            || "在浏览器打开".equals(((javafx.scene.control.Button) n).getText())
                            || "清空".equals(((javafx.scene.control.Button) n).getText()));
            check("「复制结果 / 在浏览器打开 / 清空」三个旧按钮已全部移除",
                    bottomRowGone, "仍能找到旧按钮");

            // 批注 A 的运行态陷阱：切到生成页签后动作行必须跟着消失
            module.selectTabForTest(1);
            root.applyCss();
            root.layout();
            check("切到「生成二维码」后动作行隐藏（visible 与 managed 同时为 false）",
                    !module.decodeActionsVisibleForTest(),
                    "visible=" + module.decodeActionsVisibleForTest());
            module.selectTabForTest(0);
            root.applyCss();
            root.layout();
            check("切回「解析二维码」后动作行恢复显示",
                    module.decodeActionsVisibleForTest(), "仍为 false");
        } catch (Exception ex) {
            check("批注 A/B/C 结构验证", false, ex.toString());
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
