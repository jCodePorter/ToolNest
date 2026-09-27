package com.bytefuture.toolnest;

import com.bytefuture.toolnest.modules.image.ImageOps;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 图片处理核心逻辑验证（无需 JavaFX，可直接运行）。
 *
 * <p>构造测试图后逐项验证缩放 / 裁剪 / 旋转 / 翻转 / 格式转换，
 * 重点覆盖两个最容易出错的点：<b>透明图转 JPEG</b> 与
 * <b>非等比缩放时宽高的实际取值</b>。
 */
public final class ImageTest {

    private static int passed = 0;
    private static int failed = 0;

    private ImageTest() {
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name + "  → " + detail);
        }
    }

    /** 造一张带透明区域的测试图。 */
    private static BufferedImage makeArgbImage(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(new Color(255, 0, 0, 255));
            g.fillRect(0, 0, w / 2, h);           // 左半红，不透明
            // 右半保持全透明
        } finally {
            g.dispose();
        }
        return img;
    }

    /** 造一张不透明的测试图。 */
    private static BufferedImage makeOpaqueImage(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.BLUE);
            g.fillRect(0, 0, w, h);
        } finally {
            g.dispose();
        }
        return img;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 图片处理核心逻辑验证 ===");
        Path tmp = Files.createTempDirectory("imgtest");

        // ---------- 等比缩放：只给宽 ----------
        BufferedImage src = makeOpaqueImage(800, 400);
        BufferedImage scaled = ImageOps.scale(src, new ImageOps.Size(400, 0));
        check("只给宽时按比例算高 (800x400 → 400x200)",
                scaled.getWidth() == 400 && scaled.getHeight() == 200,
                scaled.getWidth() + "x" + scaled.getHeight());

        // ---------- 等比缩放：只给高 ----------
        scaled = ImageOps.scale(src, new ImageOps.Size(0, 100));
        check("只给高时按比例算宽 (800x400 → 200x100)",
                scaled.getWidth() == 200 && scaled.getHeight() == 100,
                scaled.getWidth() + "x" + scaled.getHeight());

        // ---------- 宽高都给：contain 语义，不拉伸变形 ----------
        scaled = ImageOps.scale(src, new ImageOps.Size(400, 400));
        check("宽高都给时取较小比例，保持原比例 (800x400 → 400x200)",
                scaled.getWidth() == 400 && scaled.getHeight() == 200,
                scaled.getWidth() + "x" + scaled.getHeight());

        // ---------- 尺寸相同时不重建 ----------
        BufferedImage same = ImageOps.scale(src, new ImageOps.Size(800, 0));
        check("目标尺寸等于原尺寸时返回原对象（不重复开销）", same == src, "返回了新对象");

        // ---------- 倍率缩放 ----------
        scaled = ImageOps.scaleByFactor(src, 0.25);
        check("按倍率缩放 (0.25 → 200x100)",
                scaled.getWidth() == 200 && scaled.getHeight() == 100,
                scaled.getWidth() + "x" + scaled.getHeight());

        // ---------- 裁剪 ----------
        BufferedImage cropped = ImageOps.crop(src, new ImageOps.Crop(10, 20, 100, 50));
        check("裁剪尺寸正确", cropped.getWidth() == 100 && cropped.getHeight() == 50,
                cropped.getWidth() + "x" + cropped.getHeight());

        // 裁剪越界应被拒绝并给出明确原因
        try {
            ImageOps.crop(src, new ImageOps.Crop(700, 0, 200, 100));
            check("裁剪越界应抛异常", false, "却成功了");
        } catch (IllegalArgumentException ex) {
            check("裁剪越界应抛异常且消息可读", ex.getMessage().contains("超出"), ex.getMessage());
        }

        // ---------- 旋转 90 度宽高互换 ----------
        BufferedImage rotated = ImageOps.rotate(makeOpaqueImage(300, 100), 90);
        check("旋转 90 度宽高互换 (300x100 → 100x300)",
                rotated.getWidth() == 100 && rotated.getHeight() == 300,
                rotated.getWidth() + "x" + rotated.getHeight());

        BufferedImage rotated180 = ImageOps.rotate(makeOpaqueImage(300, 100), 180);
        check("旋转 180 度尺寸不变",
                rotated180.getWidth() == 300 && rotated180.getHeight() == 100,
                rotated180.getWidth() + "x" + rotated180.getHeight());

        // 360 度应返回原图
        BufferedImage rotated360 = ImageOps.rotate(src, 360);
        check("旋转 360 度返回原对象", rotated360 == src, "返回了新对象");

        // ---------- 翻转 ----------
        BufferedImage flipped = ImageOps.flipHorizontal(makeOpaqueImage(120, 60));
        check("水平翻转尺寸不变",
                flipped.getWidth() == 120 && flipped.getHeight() == 60,
                flipped.getWidth() + "x" + flipped.getHeight());

        // ---------- 透明检测 ----------
        check("带透明区域的图能被识别出透明", ImageOps.hasTransparency(makeArgbImage(50, 50)),
                "未识别出透明");
        check("纯不透明图不被误判为有透明", !ImageOps.hasTransparency(makeOpaqueImage(50, 50)),
                "被误判为有透明");

        // ---------- 关键路径：透明 PNG 转 JPEG（绝不能失败，也不能变黑块）----------
        Path pngPath = tmp.resolve("transparent.png");
        ImageIO.write(makeArgbImage(100, 100), "png", pngPath.toFile());
        BufferedImage loaded = ImageOps.read(pngPath);
        check("读取 PNG 成功", loaded != null, "");

        Path jpgPath = tmp.resolve("from-transparent.jpg");
        ImageOps.write(loaded, jpgPath, ImageOps.Format.JPEG, 0.85f);
        check("带透明的图转 JPEG 成功（自动合成白底）", Files.exists(jpgPath),
                "文件未生成");

        BufferedImage jpgBack = ImageIO.read(jpgPath.toFile());
        // 检查原本透明的右半区应变成白色而不是黑色
        int rightPixel = jpgBack.getRGB(jpgBack.getWidth() - 5, jpgBack.getHeight() / 2);
        Color c = new Color(rightPixel);
        check("透明区域转 JPEG 后为白色（非黑块）",
                c.getRed() > 200 && c.getGreen() > 200 && c.getBlue() > 200,
                "实际 RGB=" + c.getRed() + "," + c.getGreen() + "," + c.getBlue());

        // ---------- JPEG 质量控制 ----------
        Path qLow = tmp.resolve("q10.jpg");
        Path qHigh = tmp.resolve("q95.jpg");
        // 用噪声图让质量差异体现在文件体积上
        BufferedImage noisy = new BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 300; y++) {
            for (int x = 0; x < 300; x++) {
                int v = (x * 7 + y * 13) % 256;
                noisy.setRGB(x, y, new Color(v, (v * 3) % 256, (v * 5) % 256).getRGB());
            }
        }
        ImageOps.write(noisy, qLow, ImageOps.Format.JPEG, 0.10f);
        ImageOps.write(noisy, qHigh, ImageOps.Format.JPEG, 0.95f);
        long lowSize = Files.size(qLow);
        long highSize = Files.size(qHigh);
        check("JPEG 质量参数生效（低质量文件更小）", lowSize < highSize,
                "q10=" + lowSize + "B, q95=" + highSize + "B");

        // ---------- 各格式转换 ----------
        for (ImageOps.Format f : ImageOps.Format.values()) {
            Path out = tmp.resolve("convert." + f.extension());
            try {
                ImageOps.write(makeOpaqueImage(64, 64), out, f, 0.9f);
                check("转换为 " + f.name() + " 成功", Files.exists(out) && Files.size(out) > 0,
                        "文件为空");
            } catch (Exception ex) {
                check("转换为 " + f.name() + " 成功", false,
                        ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        }

        // ---------- 格式识别 ----------
        check("识别 .JPG（大写）", ImageOps.detectFormat("a.JPG") == ImageOps.Format.JPEG, "");
        check("识别 .jpeg", ImageOps.detectFormat("b.jpeg") == ImageOps.Format.JPEG, "");
        check("未知扩展名返回 null", ImageOps.detectFormat("c.webp") == null, "");
        check("无扩展名返回 null", ImageOps.detectFormat("noext") == null, "");

        // ---------- 扩展名替换 ----------
        check("替换扩展名 a.png → a.jpg",
                "a.jpg".equals(ImageOps.replaceExtension("a.png", ImageOps.Format.JPEG)),
                ImageOps.replaceExtension("a.png", ImageOps.Format.JPEG));
        check("无扩展名时补扩展名",
                "noext.png".equals(ImageOps.replaceExtension("noext", ImageOps.Format.PNG)),
                ImageOps.replaceExtension("noext", ImageOps.Format.PNG));

        System.out.println();
        System.out.println("RESULT passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
