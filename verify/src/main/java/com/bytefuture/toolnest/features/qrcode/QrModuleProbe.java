package com.bytefuture.toolnest.features.qrcode;

import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
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
 * 二维码模块的界面级验证探针。
 *
 * <p>校验的是「编译通过看不到、只有真渲染才暴露」的那部分：
 * 模块能否构建出界面、两个页签是否都在、CSS 有没有解析错误。
 * 逻辑部分（编解码、裁切）由 {@code QrTest} 覆盖，这里不重复。
 *
 * <p>本类位于 {@code modules.qrcode} 包内，因为被测的测试钩子是包级可见的。
 */
public class QrModuleProbe extends Application {

    private static int passed = 0;
    private static int failed = 0;
    private static final List<String> cssErrors = new ArrayList<>();
    private static final List<String> problems = new ArrayList<>();

    /** 异步回归断言（7b）所需的上下文：等 Ctrl+V 的后台识别回写完成再断言。 */
    private static QrModule pendingPasteModule = null;
    private static BufferedImage pendingPasteSource = null;
    private static String pendingPasteExpected = null;

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name + "  → " + detail);
        }
    }

    private static void snapshot(javafx.scene.Node node, String name) {
        try {
            WritableImage image = node.snapshot(null, null);
            File out = new File("target/smoke");
            out.mkdirs();
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png",
                    new File(out, name + ".png"));
        } catch (Exception ex) {
            System.out.println("[截图失败] " + name + "：" + ex.getMessage());
        }
    }

    /** 造一张「大图里嵌一个二维码」，模拟截图场景。 */
    private static BufferedImage screenshotLike(String payload) throws Exception {
        BufferedImage big = new BufferedImage(900, 660, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = big.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 900, 660);
        // 干扰内容
        g.setColor(new Color(0x22, 0x22, 0x22));
        g.fillRect(50, 40, 420, 34);
        g.fillRect(50, 95, 300, 22);
        g.fillRect(50, 140, 380, 22);
        g.setColor(new Color(0xCC, 0xCC, 0xCC));
        g.drawLine(50, 600, 850, 600);
        // 右下角的二维码
        BufferedImage qr = QrOps.generate(payload,
                new QrOps.GenerateOptions(280, QrOps.Level.M, 4, Color.BLACK, Color.WHITE));
        g.drawImage(qr, 560, 340, null);
        g.dispose();
        return big;
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

        QrModule module = new QrModule();

        // ---------- 1. 元信息 ----------
        check("模块 id 正确", "qrcode".equals(module.id()), "id=" + module.id());
        check("归入生成类型工具",
                module.category() == com.bytefuture.toolnest.core.ModuleCategory.GENERATE,
                String.valueOf(module.category()));
        check("关键词覆盖常用说法",
                module.keywords().contains("二维码") && module.keywords().contains("粘贴"),
                module.keywords());

        // ---------- 2. 界面构建 ----------
        javafx.scene.Node content = null;
        try {
            content = module.buildContent();
            check("界面构建成功", content != null, "");
        } catch (Exception ex) {
            check("界面构建成功", false, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            ex.printStackTrace();
        }
        if (content == null) {
            finish();
            return;
        }

        StackPane root = new StackPane(content);
        Scene scene = new Scene(root, 1180, 780);
        scene.getStylesheets().add(
                QrModuleProbe.class.getResource("/css/app.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
        root.applyCss();
        root.layout();

        // 两个页签都要在
        javafx.scene.control.TabPane tp = findTabPane(content);
        check("找到页签容器", tp != null, "");
        if (tp != null) {
            check("页签数量为 2", tp.getTabs().size() == 2, "实际 " + tp.getTabs().size());
            check("含「解析二维码」页签",
                    tp.getTabs().stream().anyMatch(t -> "解析二维码".equals(t.getText())),
                    tp.getTabs().toString());
            check("含「生成二维码」页签",
                    tp.getTabs().stream().anyMatch(t -> "生成二维码".equals(t.getText())),
                    tp.getTabs().toString());
        }

        // ---------- 3. 解析页：载入图并识别 ----------
        try {
            BufferedImage shot = screenshotLike("https://toolnest.example.com/probe");
            String got = module.decodeImageForTest(shot);
            check("界面链路可识别整图中的二维码",
                    "https://toolnest.example.com/probe".equals(got),
                    got == null ? "解析失败" : got);
            check("识别结果写回了结果框",
                    "https://toolnest.example.com/probe"
                            .equals(module.decodeResultTextForTest()),
                    String.valueOf(module.decodeResultTextForTest()));
            check("原图已记录为待解析源", module.decodeSourceForTest() != null, "");
            root.applyCss();
            root.layout();
            snapshot(root, "qr-decode-tab");
        } catch (Exception ex) {
            check("界面链路可识别整图中的二维码", false, "异常：" + ex.getMessage());
        }

        // ---------- 3b. 框选裁切：坐标换算是否正确 ----------
        // 二维码在原图 (560,340)-(840,620)；预览按 PREVIEW_MAX 等比缩放
        try {
            BufferedImage shot = screenshotLike("crop-probe-target");
            module.decodeImageForTest(shot);
            // 显示尺寸与原图同宽？取实际缩放比来算框选坐标
            double shownW = module.previewShownWidthForTest();
            double shownH = module.previewShownHeightForTest();
            if (shownW > 0 && shownH > 0) {
                double sx = shownW / shot.getWidth();
                double sy = shownH / shot.getHeight();
                // 把二维码区域换算成显示坐标
                module.setSelectionForTest(560 * sx, 340 * sy, 280 * sx, 280 * sy);
                check("框选矩形已建立", module.selectionWidthForTest() > 8,
                        "宽=" + module.selectionWidthForTest());
                String cropped = module.decodeWithSelectionForTest();
                check("按框选区域裁切后解析正确（坐标换算无误）",
                        "crop-probe-target".equals(cropped),
                        cropped == null ? "解析失败" : cropped);
            } else {
                check("框选矩形已建立", false, "预览尺寸无效");
            }
        } catch (Exception ex) {
            check("按框选区域裁切后解析正确（坐标换算无误）", false,
                    "异常：" + ex.getMessage());
        }

        // ---------- 3c. 框选坐标系必须与图片显示区严格对齐 ----------
        // 【历史 bug】用户上报「框选起点不跟随鼠标落点」。
        // 成因：selectionLayer 的尺寸按 Math.min(原图宽, 460) × Math.min(原图高, 460)
        // 各自独立截断，而 decodePreview 是 preserveRatio 的**等比缩放**。
        // 对长方形图（本用例 900×660）：
        //   等比显示 = 460 × 337
        //   旧算法   = 460 × 460  ← 高了 123px
        // 图片在框选层里居中 → 坐标系整体偏移 (460-337)/2 ≈ 61.5px，
        // 鼠标按住的位置和框选起点对不上。
        //
        // 本段用**长方形图**专门锁死这个 bug 的三个不变量。
        try {
            BufferedImage rect = screenshotLike("aspect-ratio-guard");
            module.decodeImageForTest(rect);
            double srcW = rect.getWidth();
            double srcH = rect.getHeight();
            double scale = Math.min(460.0 / srcW, 460.0 / srcH);
            double expW = srcW * scale;
            double expH = srcH * scale;

            double layerW = module.selectionLayerWidthForTest();
            double layerH = module.selectionLayerHeightForTest();
            // 容差 1.5px：JavaFX 布局会把尺寸取整到整数像素
            // （337.33 → 338），这属于正常的像素对齐，不是缺陷。
            check("3c 框选层宽度 == 图片等比显示宽度（长方形图不失配）",
                    Math.abs(layerW - expW) <= 1.5,
                    "框选层=" + layerW + " 应为 " + expW);
            check("3c 框选层高度 == 图片等比显示高度（长方形图不失配）",
                    Math.abs(layerH - expH) <= 1.5,
                    "框选层=" + layerH + " 应为 " + expH);
            check("3c 图片相对框选层左上角无偏移（坐标系原点重合）",
                    Math.abs(module.previewOffsetXForTest()) < 0.5
                            && Math.abs(module.previewOffsetYForTest()) < 0.5,
                    "偏移=(" + module.previewOffsetXForTest() + ", "
                            + module.previewOffsetYForTest() + ")");

            // 起点跟随：走**真实鼠标事件链路**在指定点按下并拖动，
            // 框选起点必须落在同一位置（这条才是用户上报现象的直接防线）。
            module.simulateDragForTest(30, 40, 150, 160);
            check("3c 框选起点跟随鼠标落点（走真实事件处理器）",
                    Math.abs(module.selectionXForTest() - 30) < 0.5
                            && Math.abs(module.selectionYForTest() - 40) < 0.5,
                    "起点=(" + module.selectionXForTest() + ", "
                            + module.selectionYForTest() + ") 应为 (30.0, 40.0)");
            check("3c 框选宽高跟随拖拽终点",
                    Math.abs(module.selectionWidthForTest() - 120) < 0.5
                            && Math.abs(module.selectionHeightForTest() - 120) < 0.5,
                    "尺寸=" + module.selectionWidthForTest() + "×"
                            + module.selectionHeightForTest() + " 应为 120×120");
            module.simulateDragReleaseForTest();
        } catch (Exception ex) {
            check("3c 框选层宽度 == 图片等比显示宽度（长方形图不失配）", false,
                    "异常：" + ex.getMessage());
        }

        // ---------- 4. 生成页：生成并核对 ----------
        boolean generated = module.generateForTest(
                "ToolNest 工具巢 · 二维码探针", 400, QrOps.Level.H);
        check("界面链路可生成二维码", generated, "生成失败");
        if (generated) {
            BufferedImage img = module.generatedForTest();
            check("生成尺寸符合请求 (400x400)",
                    img.getWidth() == 400 && img.getHeight() == 400,
                    img.getWidth() + "x" + img.getHeight());
            // 生成的码必须能被解析回原文（端到端闭环）
            List<QrOps.Decoded> back = QrOps.decodeRobust(img);
            check("生成的码可被解析回原内容",
                    !back.isEmpty()
                            && "ToolNest 工具巢 · 二维码探针".equals(back.get(0).text()),
                    back.isEmpty() ? "解析失败" : back.get(0).text());
            // 切到生成页。截图推迟到 start() 返回之后（见下方 deferred snapshot），
            // 因为 ImageView 换图后需要经过一个绘制脉冲才会真正画上去 ——
            // 在 start() 里立即 snapshot 会拍到一整块未填充的纯色。
            if (tp != null) {
                tp.getSelectionModel().select(1);
            }
            javafx.scene.image.Image fxImg = module.genPreviewImageForTest();
            check("生成页预览图已挂载到 ImageView",
                    fxImg != null && fxImg.getWidth() > 0 && !fxImg.isError(),
                    fxImg == null ? "未挂载" : ("尺寸=" + fxImg.getWidth() + "x" + fxImg.getHeight()));
            root.applyCss();
            root.layout();
            // 延后若干次 runLater 再截，确保绘制脉冲已经跑过
            scheduleDeferredSnapshot(root);
        }

        // ---------- 5. 空输入应作废旧图，而非留下过期内容 ----------
        boolean regenerated = module.generateForTest("stale-check", 400, QrOps.Level.M);
        check("前置：先成功生成一张", regenerated, "");
        boolean emptyRejected = !module.generateForTest("", 400, QrOps.Level.M);
        check("清空输入后不再持有可导出的图（不残留过期内容）",
                emptyRejected, "旧图未被作废，导出会拿到过期内容");

        // ---------- 6. 生成图必须是「纯黑 + 纯白」，不能是脏色 ----------
        // 历史 bug：toAwt() 忘了把 JavaFX 的 0..1 分量乘 255，
        // 导致白色变成 #010101 —— 整张图看着就是全黑（因为黑仍是纯黑，
        // 所以只有白色部分坏掉，很容易被误判成「渲染问题」）。
        // 注意：上一步刚把 generated 作废，这里必须重新生成一张。
        if (module.generateForTest("color-check", 400, QrOps.Level.M)) {
            BufferedImage gen = module.generatedForTest();
            int pureWhite = 0;
            int nearBlackDirty = 0;
            for (int y = 0; y < gen.getHeight(); y++) {
                for (int x = 0; x < gen.getWidth(); x++) {
                    int c = gen.getRGB(x, y);
                    int r = (c >> 16) & 0xFF;
                    int g = (c >> 8) & 0xFF;
                    int b = c & 0xFF;
                    if (r == 255 && g == 255 && b == 255) {
                        pureWhite++;
                    }
                    // #010101 这类「非纯黑又几乎全黑」的脏色是本次 bug 的特征值
                    if (r == 1 && g == 1 && b == 1) {
                        nearBlackDirty++;
                    }
                }
            }
            check("生成图含真正的纯白像素（背景未被压成 #010101）",
                    pureWhite > 0, "纯白像素数=" + pureWhite);
            check("生成图不含 #010101 脏色（颜色换算未漏乘 255）",
                    nearBlackDirty == 0, "#010101 像素数=" + nearBlackDirty);
        } else {
            check("生成图含真正的纯白像素（背景未被压成 #010101）", false,
                    "前置生成失败");
        }

        // ---------- 7. Ctrl+V 必须在「焦点不在预览区」时也能粘贴 ----------
        // 历史 bug：过滤器挂在预览区 StackPane 上，而实际焦点会停在 TabPane，
        // 按键事件根本到不了那个节点 —— 用户按 Ctrl+V 毫无反应。
        // 现在挂在 Scene 上，从 Scene 派发事件即可触发（并返回 consumed）。
        //
        // 7b 追加（本 bug 回归防线）：不仅断言「图粘进来了」，还要断言
        // **真实异步链路最终把识别结果写进了结果框**。历史 bug 恰恰落在这条
        // 断言的缝隙里 —— 图贴进去了，但异步回写的识别结果是空。
        // 异步等待不能在 FX 线程上阻塞，所以放在 start() 返回之后的轮询里做。
        try {
            BufferedImage pasteSrc = QrOps.generate("PASTE-VIA-CTRL-V",
                    new QrOps.GenerateOptions(240, QrOps.Level.M, 4,
                            java.awt.Color.BLACK, java.awt.Color.WHITE));
            com.bytefuture.toolnest.features.qrcode.QrModule
                    .putImageOnClipboardForTest(pasteSrc);

            boolean consumed = module.firePasteShortcutForTest();
            check("Ctrl+V 经真实过滤器链路能粘贴（过滤器挂在 Scene 上）", consumed,
                    "粘贴未发生 —— 过滤器没生效，用户按 Ctrl+V 会没反应");
            check("Ctrl+V 后预览区已载入剪贴板图片",
                    module.decodePreviewImageForTest() != null,
                    "预览区仍为空");

            // 记录异步断言所需上下文，等 start() 结束后再单独跑一遍
            // 「Ctrl+V → 等后台识别回写 → 断言结果框文本」。
            // 之所以放到最后单独跑：section 8 会同步覆盖 decodeResult，
            // 若在 7 处就地等待会被后一轮覆盖，断言就失真了。
            pendingPasteModule = module;
            pendingPasteSource = pasteSrc;
            pendingPasteExpected = "PASTE-VIA-CTRL-V";
        } catch (Exception ex) {
            check("Ctrl+V 经真实过滤器链路能粘贴（过滤器挂在 Scene 上）", false,
                    "异常：" + ex.getMessage());
        }

        // ---------- 8. 透明背景图必须自动合成白底 ----------
        // 历史 bug：截图工具复制的图常带透明通道，透明像素的 RGB 是 0（黑），
        // ZXing 只看亮度 → 整张图被判成全黑 → 100% 识别失败；
        // 界面上还表现为「二维码发白看不清」（露出容器浅灰底）。
        // 修法：入图时统一 flatten 到白底。
        try {
            BufferedImage solid = QrOps.generate("TRANSPARENT-CASE",
                    new QrOps.GenerateOptions(280, QrOps.Level.M, 4,
                            java.awt.Color.BLACK, java.awt.Color.WHITE));
            // 把白底改成全透明，复刻截图工具产物
            BufferedImage transparent = new BufferedImage(
                    280, 280, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 280; y++) {
                for (int x = 0; x < 280; x++) {
                    int c = solid.getRGB(x, y);
                    boolean isBlack = ((c >> 16) & 0xFF) < 128;
                    transparent.setRGB(x, y, isBlack ? 0xFF000000 : 0x00000000);
                }
            }
            check("前置：透明图直接解析会失败（复现用户现象）",
                    QrOps.decodeRobust(transparent).isEmpty(),
                    "透明图竟然能直接解析，用例设计有误");

            String got = module.decodeImageForTest(transparent);
            check("透明背景图经界面链路仍能识别（已自动合成白底）",
                    "TRANSPARENT-CASE".equals(got),
                    got == null ? "识别失败" : got);

            BufferedImage src = module.decodeSourceForTest();
            check("预览源图已消除透明通道（alpha 不再丢失）",
                    src != null && !src.getColorModel().hasAlpha(),
                    src == null ? "无源图" : ("type=" + src.getType()));
        } catch (Exception ex) {
            check("透明背景图经界面链路仍能识别（已自动合成白底）", false,
                    "异常：" + ex.getMessage());
        }

        // ---------- 9. 【本 bug 的核心回归防线】AWT 写入剪贴板必须能识别 ----------
        // 真实截图工具是以原生 / AWT 方式写剪贴板的。对这种内容，
        // JavaFX 的 Clipboard.getImage() 会返回一张**全透明 + 掉 1 像素**的坏图
        // （实测 300x300 读回 299x299、所有像素 alpha 清零），
        // 模块把它忠实合成成纯白 → 预览区空白 + 「未识别到二维码」。
        //
        // 所以断言必须用 **AWT 写入**：FX 写入通道是完好的，用它测不出这个 bug。
        // 下面两条分别覆盖「识别成功」与「尺寸不掉像素」。
        try {
            // --- 9a. 不透明 RGB 二维码（AWT 写入） ---
            BufferedImage opaqueQr = QrOps.generate("AWT-OPAQUE-QR",
                    new QrOps.GenerateOptions(300, QrOps.Level.M, 4,
                            java.awt.Color.BLACK, java.awt.Color.WHITE));
            com.bytefuture.toolnest.features.qrcode.QrModule
                    .putImageOnAwtClipboardForTest(opaqueQr);
            boolean consumedOpaque = module.firePasteShortcutForTest();
            check("9a AWT 写入的图经 Ctrl+V 能粘贴", consumedOpaque, "粘贴未触发");
            String opaqueGot = module.decodeImageForTest(module.decodeSourceForTest());
            check("9a AWT 写入的不透明二维码经粘贴路径能识别（修复前必失败）",
                    "AWT-OPAQUE-QR".equals(opaqueGot),
                    opaqueGot == null ? "识别失败 —— 可能又退回 FX 读图了" : opaqueGot);
            BufferedImage opaqueSrc = module.decodeSourceForTest();
            check("9a 粘贴后源图尺寸与原图一致（不掉 1 像素）",
                    opaqueSrc != null && opaqueSrc.getWidth() == 300
                            && opaqueSrc.getHeight() == 300,
                    opaqueSrc == null ? "无源图"
                            : (opaqueSrc.getWidth() + "x" + opaqueSrc.getHeight()));

            // --- 9b. 透明背景二维码（AWT 写入）—— 同时覆盖「带 alpha」与「AWT 写」 ---
            BufferedImage solidQr = QrOps.generate("AWT-ALPHA-QR",
                    new QrOps.GenerateOptions(300, QrOps.Level.M, 4,
                            java.awt.Color.BLACK, java.awt.Color.WHITE));
            BufferedImage alphaQr = new BufferedImage(
                    300, 300, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 300; y++) {
                for (int x = 0; x < 300; x++) {
                    int c = solidQr.getRGB(x, y);
                    boolean isBlack = ((c >> 16) & 0xFF) < 128;
                    alphaQr.setRGB(x, y, isBlack ? 0xFF000000 : 0x00000000);
                }
            }
            com.bytefuture.toolnest.features.qrcode.QrModule
                    .putImageOnAwtClipboardForTest(alphaQr);
            boolean consumedAlpha = module.firePasteShortcutForTest();
            check("9b AWT 写入的透明背景图经 Ctrl+V 能粘贴", consumedAlpha, "粘贴未触发");
            String alphaGot = module.decodeImageForTest(module.decodeSourceForTest());
            check("9b AWT 写入的透明背景二维码经粘贴路径能识别",
                    "AWT-ALPHA-QR".equals(alphaGot),
                    alphaGot == null ? "识别失败" : alphaGot);

            // 注：原计划在这里加一条「FX 直接读同一张剪贴板图会得到全透明结果」的
            // 反向断言，用来锁住根因。**已放弃**——实测在 AWT 刚写入剪贴板之后
            // 再用 FX 的 Clipboard.getImage() 去读，会让 JVM 直接崩溃退出
            // （无堆栈、无 RESULT 汇总、后续 CSS 断言全部丢失）。
            // 这正是根因本身的一种表现：FX 与 AWT 两条剪贴板链路混用不安全。
            // 与其为一条"锦上添花"的断言赔上整轮探针，不如让修复路径的
            // 正向断言（9a / 9b）来把关 —— 它们已经足够区分修复前后的行为。
        } catch (Exception ex) {
            check("9a AWT 写入的不透明二维码经粘贴路径能识别（修复前必失败）", false,
                    "异常：" + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }

        // ---------- 10. CSS 错误 ----------
        check("未出现 CSS 解析错误", cssErrors.isEmpty(),
                cssErrors.isEmpty() ? "" : String.join(" | ", cssErrors));

        // 收尾在 scheduleDeferredSnapshot 里做（等绘制脉冲后再截图并退出）
    }

    /**
     * 把生成页的截图推迟到当前脉冲之后执行。
     *
     * <p>为什么要延迟：{@code root.applyCss()+layout()} 只保证**布局**算完，
     * 不保证图像已经**绘制**到画布。{@code ImageView} 刚换图就立即 snapshot，
     * 会拍到尚未填充的区域（本项目在二维码预览上正是踩到这个 ——
     * 截图里整个预览区是一块纯黑，但同一张图直接写 PNG 完全正常）。
     *
     * <p>做法：连续排几个 {@code runLater}，等它们执行时说明至少过了
     * 一个完整的渲染脉冲，此时再截图就是真实画面。
     * 最后一批回调里做收尾（输出结果、退出）。
     */
    private static void scheduleDeferredSnapshot(javafx.scene.Node root) {
        Platform.runLater(() -> Platform.runLater(() -> Platform.runLater(() -> {
            root.applyCss();
            if (root instanceof javafx.scene.Parent p) {
                p.layout();
            }
            snapshot(root, "qr-generate-tab");
            // 先完成 7b 的异步断言（等后台识别回写），再收尾
            awaitPasteDecodeThenFinish(0);
        })));
    }

    /**
     * 异步等待 Ctrl+V 的背景识别结果写回结果框，然后做断言并收尾。
     *
     * <p>为什么必须异步：{@code pasteFromClipboard()} 把解码放进后台线程、
     * 用 {@code task.setOnSucceeded} 在 FX 线程回写结果。若在 FX 线程上
     * 阻塞等待（sleep/latch），FX 线程就无法消费回写事件 → 死锁。
     * 所以这里用「排一个 runLater 检查一次」的轮询方式，让 FX 线程始终空闲。
     *
     * <p>最多轮询 {@link #PASTE_POLL_LIMIT} 次（每次间隔一个事件循环脉冲），
     * 仍未拿到结果则判失败，避免无限等待。
     */
    private static final int PASTE_POLL_LIMIT = 200;

    private static void awaitPasteDecodeThenFinish(int attempt) {
        if (pendingPasteModule == null || pendingPasteSource == null) {
            // 没有待断言的粘贴（前置失败），直接收尾
            finish();
            return;
        }
        if (attempt == 0) {
            // 重新投递剪贴板 + 重放一次真实 Ctrl+V（section 8 已覆盖过 decodeResult，
            // 必须重放才能拿到本轮粘贴的、未被污染的异步结果）。
            //
            // 【关键】这里刻意用 **AWT 方式** 投递（putImageOnAwtClipboardForTest），
            // 而不是 FX 方式：只有 AWT 通道才能复刻真实截图工具写入的剪贴板内容，
            // 让「FX 读取得到全透明坏图」这个缺陷在真实异步链路里真实发生。
            // 若用 FX 通道投递，图本身完好，本断言在**修复前也会通过**，
            // 等于没有防线 —— 这正是历史 bug 溜过去的缝隙。
            com.bytefuture.toolnest.features.qrcode.QrModule
                    .putImageOnAwtClipboardForTest(pendingPasteSource);
            // 必须先清空结果框：否则 section 8 遗留的文本会让下面的
            // 「结果非空」完成判据立刻为真，断言退化成读旧值。
            pendingPasteModule.clearDecodeResultForTest();
            boolean consumed = pendingPasteModule.firePasteShortcutForTest();
            check("Ctrl+V(AWT 剪贴板) 重放：过滤器链路仍生效", consumed, "重放未触发粘贴");
        }
        String got = pendingPasteModule.decodeResultTextForTest();
        boolean done = got != null && !got.isEmpty();
        if (done || attempt >= PASTE_POLL_LIMIT) {
            check("Ctrl+V(AWT 剪贴板) 粘贴后能识别出二维码（真实异步链路 + AWT 剪贴板路径最终结果）",
                    pendingPasteExpected.equals(got),
                    "结果框为空或内容不符：得到「" + (got == null ? "" : got) + "」");
            check("Ctrl+V(AWT 剪贴板) 粘贴的图确实进入了待解析源（decodeSource 非空）",
                    pendingPasteModule.decodeSourceForTest() != null,
                    "decodeSource 为空，说明图根本没载入成功");
            finish();
            return;
        }
        // 再排一个 runLater 检查一次：间接收获一个事件循环脉冲，
        // 后台线程的 setOnSucceeded 回写才有机会执行。
        Platform.runLater(() -> awaitPasteDecodeThenFinish(attempt + 1));
    }

    private static void finish() {
        System.out.println();
        System.out.println("RESULT passed=" + passed + " failed=" + failed);
        if (!problems.isEmpty()) {
            System.out.println("（捕获到 " + problems.size() + " 条未捕获异常）");
            for (String p : problems) {
                System.out.println("   " + p);
            }
        }
        System.out.flush();
        // 必须强制退出，不能只 Platform.exit()：本次新增的 AWT 剪贴板读写
        // （putImageOnAwtClipboardForTest 走 Toolkit.getDefaultToolkit()）
        // 会拉起 AWT 事件分发线程，而它是非守护线程 —— Platform.exit() 之后
        // 进程仍会挂着不退，实测卡满 4 分钟不返回，表现为「探针跑不完」。
        System.exit(failed > 0 ? 1 : 0);
    }

    /**
     * 等一个 JavaFX 绘制脉冲过去（供非 FX 线程调用）。
     */
    private static void waitForPulse() {
        if (Platform.isFxApplicationThread()) {
            return;
        }
        java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        Platform.runLater(latch::countDown);
        try {
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static javafx.scene.control.TabPane findTabPane(javafx.scene.Node node) {
        if (node instanceof javafx.scene.control.TabPane tp) {
            return tp;
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                javafx.scene.control.TabPane found = findTabPane(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
