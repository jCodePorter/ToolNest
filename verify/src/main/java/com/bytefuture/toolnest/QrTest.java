package com.bytefuture.toolnest;

import com.bytefuture.toolnest.modules.qrcode.QrOps;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 二维码生成 / 解析核心逻辑验证（无需 JavaFX，可直接运行）。
 *
 * <p>重点覆盖三件容易出错的事：
 * <ol>
 *   <li><b>往返一致性</b>：生成的码必须能被同一条链路解析回原文，
 *       含中文、emoji、换行等非 ASCII 内容</li>
 *   <li><b>裁切解析</b>：从大图里裁出二维码区域后仍能解析
 *       —— 这是「截图后框选解析」这条功能的根基</li>
 *   <li><b>容错与降级</b>：空白图/噪声图不能抛异常，只返回空结果</li>
 * </ol>
 */
public final class QrTest {

    private static int passed = 0;
    private static int failed = 0;

    private QrTest() {
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name + (detail.isEmpty() ? "" : "  → " + detail));
        }
    }

    /** 生成一个二维码并解析回来，返回解出的文本；失败返回 null。 */
    private static String roundTrip(String text, int size, QrOps.Level level) {
        try {
            BufferedImage img = QrOps.generate(text,
                    new QrOps.GenerateOptions(size, level, 4, Color.BLACK, Color.WHITE));
            List<QrOps.Decoded> decoded = QrOps.decodeRobust(img);
            return decoded.isEmpty() ? null : decoded.get(0).text();
        } catch (Exception ex) {
            return null;
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 二维码生成 / 解析验证 ===");
        Path outDir = Path.of("target/qr");
        Files.createDirectories(outDir);

        // ---------- 1. 往返一致性 ----------
        String[] samples = {
                "https://toolnest.example.com/order?id=12345",
                "Hello, ToolNest!",
                "订单编号：A20260924001",
                "多行内容\n第二行\n第三行",
                "混合 mixed 中英文 English 123",
        };
        for (String s : samples) {
            String got = roundTrip(s, 400, QrOps.Level.M);
            String label = s.length() > 22 ? s.substring(0, 22) + "…" : s;
            check("往返解析: " + label,
                    s.equals(got),
                    got == null ? "解析失败" : "得到「" + got + "」");
        }

        // ---------- 2. 生成图像的规格 ----------
        BufferedImage img = QrOps.generate("size-check",
                new QrOps.GenerateOptions(600, QrOps.Level.M, 4, Color.BLACK, Color.WHITE));
        check("生成尺寸符合请求 (600x600)",
                img.getWidth() == 600 && img.getHeight() == 600,
                img.getWidth() + "x" + img.getHeight());
        check("图像有透明通道 (ARGB)",
                img.getType() == BufferedImage.TYPE_INT_ARGB,
                "type=" + img.getType());

        // ---------- 3. 四种纠错级别都能往返 ----------
        for (QrOps.Level level : QrOps.Level.values()) {
            String got = roundTrip("level-" + level.tag(), 400, level);
            check("纠错级别 " + level.tag() + " 可往返",
                    ("level-" + level.tag()).equals(got),
                    got == null ? "解析失败" : "得到「" + got + "」");
        }

        // ---------- 4. 纠错级别越高，码点越密 ----------
        // 注意必须在**足够大的边长**上测：
        // 400px 时像素网格取整的噪声会盖过密度差异，实测 L/M/Q/H 甚至不单调
        // （L=0.293、M=0.271、Q=0.276、H=0.284）；
        // 1200px 上噪声被摊薄，才呈现理论上的单调递增。
        BufferedImage lowL = QrOps.generate("https://example.com/abcdefghij",
                new QrOps.GenerateOptions(1200, QrOps.Level.L, 4, Color.BLACK, Color.WHITE));
        BufferedImage highH = QrOps.generate("https://example.com/abcdefghij",
                new QrOps.GenerateOptions(1200, QrOps.Level.H, 4, Color.BLACK, Color.WHITE));
        double darkL = darkRatio(lowL);
        double darkH = darkRatio(highH);
        check("H 级比 L 级码点更密（容错换来密度）",
                darkH > darkL,
                String.format("L=%.4f H=%.4f", darkL, darkH));

        // 四级全部单调递增（同样在大边长上验证）
        double prev = -1;
        boolean monotonic = true;
        StringBuilder order = new StringBuilder();
        for (QrOps.Level lv : QrOps.Level.values()) {
            double d = darkRatio(QrOps.generate("https://example.com/abcdefghij",
                    new QrOps.GenerateOptions(1200, lv, 4, Color.BLACK, Color.WHITE)));
            order.append(lv.tag()).append('=').append(String.format("%.4f", d)).append(' ');
            if (d <= prev) {
                monotonic = false;
            }
            prev = d;
        }
        check("L→M→Q→H 密度单调递增", monotonic, order.toString().trim());

        // ---------- 5. 自定义颜色 ----------
        BufferedImage colored = QrOps.generate("color-test",
                new QrOps.GenerateOptions(400, QrOps.Level.M, 4,
                        new Color(0x1E, 0x3A, 0x8A), new Color(0xFA, 0xFA, 0xFA)));
        List<QrOps.Decoded> coloredDecoded = QrOps.decodeRobust(colored);
        check("自定义前景色仍可解析",
                !coloredDecoded.isEmpty() && "color-test".equals(coloredDecoded.get(0).text()),
                coloredDecoded.isEmpty() ? "解析失败" : coloredDecoded.get(0).text());

        // ---------- 6. 边距参数生效 ----------
        // 同样用大边长，避免取整噪声 —— 边距差异（4 vs 16 模块）在小图上只差几个像素
        BufferedImage noMargin = QrOps.generate("margin-test",
                new QrOps.GenerateOptions(1200, QrOps.Level.M, 0, Color.BLACK, Color.WHITE));
        BufferedImage bigMargin = QrOps.generate("margin-test",
                new QrOps.GenerateOptions(1200, QrOps.Level.M, 16, Color.BLACK, Color.WHITE));
        check("大边距版本非白像素占比更低",
                darkRatio(bigMargin) < darkRatio(noMargin),
                String.format("margin0=%.4f margin16=%.4f",
                        darkRatio(noMargin), darkRatio(bigMargin)));

        // ---------- 7. 裁切解析（核心：框选后解析） ----------
        // 造一张「大图里嵌一个小二维码」的图，模拟截图场景
        BufferedImage big = new BufferedImage(900, 700, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = big.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 900, 700);
        // 画一些干扰内容（模拟界面截图上的文字与线条）
        g.setColor(new Color(0x33, 0x33, 0x33));
        g.fillRect(40, 40, 400, 30);
        g.fillRect(40, 90, 300, 20);
        g.drawLine(40, 620, 860, 620);
        // 二维码放在右下角一块区域
        int qrX = 560, qrY = 380, qrSize = 260;
        BufferedImage qr = QrOps.generate("cropped-target",
                new QrOps.GenerateOptions(qrSize, QrOps.Level.M, 4, Color.BLACK, Color.WHITE));
        g.drawImage(qr, qrX, qrY, null);
        g.dispose();

        BufferedImage cropped = QrOps.crop(big, qrX, qrY, qrSize, qrSize);
        check("裁切尺寸正确",
                cropped.getWidth() == qrSize && cropped.getHeight() == qrSize,
                cropped.getWidth() + "x" + cropped.getHeight());
        List<QrOps.Decoded> cropDecoded = QrOps.decodeRobust(cropped);
        check("裁切后可解析出目标内容",
                !cropDecoded.isEmpty() && "cropped-target".equals(cropDecoded.get(0).text()),
                cropDecoded.isEmpty() ? "解析失败" : cropDecoded.get(0).text());

        // 整张图直接解析 —— 有小码也有干扰，降级策略应该也能命中
        List<QrOps.Decoded> wholeDecoded = QrOps.decodeRobust(big);
        check("整图（含干扰）也能解析",
                !wholeDecoded.isEmpty() && "cropped-target".equals(wholeDecoded.get(0).text()),
                wholeDecoded.isEmpty() ? "解析失败" : wholeDecoded.get(0).text());

        // ---------- 8. 裁切越界要夹取而非崩溃 ----------
        BufferedImage oob = QrOps.crop(big, 850, 650, 500, 500);
        check("裁切越界不崩溃且尺寸被夹取",
                oob.getWidth() == 50 && oob.getHeight() == 50,
                oob.getWidth() + "x" + oob.getHeight());
        check("负坐标裁切不崩溃",
                QrOps.crop(big, -50, -50, 200, 200) != null, "");

        // ---------- 9. 小尺寸码靠放大策略救回 ----------
        BufferedImage tiny = QrOps.generate("tiny-code",
                new QrOps.GenerateOptions(60, QrOps.Level.L, 2, Color.BLACK, Color.WHITE));
        List<QrOps.Decoded> tinyDecoded = QrOps.decodeRobust(tiny);
        check("60px 小码经降级策略仍可解析",
                !tinyDecoded.isEmpty() && "tiny-code".equals(tinyDecoded.get(0).text()),
                tinyDecoded.isEmpty() ? "解析失败" : tinyDecoded.get(0).text());

        // ---------- 10. 失败路径：空白图 ----------
        BufferedImage blank = new BufferedImage(300, 300, BufferedImage.TYPE_INT_ARGB);
        Graphics2D bg = blank.createGraphics();
        bg.setColor(Color.WHITE);
        bg.fillRect(0, 0, 300, 300);
        bg.dispose();
        check("空白图不抛异常且返回空结果",
                QrOps.decodeAll(blank).isEmpty(), "应返回空列表");
        check("空白图经降级策略仍返回空",
                QrOps.decodeRobust(blank).isEmpty(), "应返回空列表");

        // ---------- 11. 失败路径：噪声图 ----------
        BufferedImage noise = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        java.util.Random rnd = new java.util.Random(42);
        for (int y = 0; y < 200; y++) {
            for (int x = 0; x < 200; x++) {
                noise.setRGB(x, y, rnd.nextInt(0xFFFFFF));
            }
        }
        check("噪声图不抛异常",
                QrOps.decodeAll(noise).isEmpty() || !QrOps.decodeAll(noise).isEmpty(), "");

        // ---------- 12. decodeOne 在失败时应给出可读原因 ----------
        boolean threw = false;
        String msg = "";
        try {
            QrOps.decodeOne(blank);
        } catch (QrOps.QrException ex) {
            threw = true;
            msg = ex.getMessage();
        }
        check("decodeOne 失败时抛带中文原因的异常",
                threw && msg != null && msg.contains("没有在图片中找到二维码"),
                msg);

        // ---------- 13. 空文本生成应被拒绝 ----------
        boolean rejected = false;
        try {
            QrOps.generate("", QrOps.GenerateOptions.defaults());
        } catch (IllegalArgumentException ex) {
            rejected = true;
        }
        check("空文本被拒绝", rejected, "应抛 IllegalArgumentException");

        // ---------- 14. 结果元信息 ----------
        BufferedImage meta = QrOps.generate("meta-check",
                new QrOps.GenerateOptions(400, QrOps.Level.M, 4, Color.BLACK, Color.WHITE));
        QrOps.Decoded d = QrOps.decodeOne(meta);
        check("解析结果带格式标识", "QR_CODE".equals(d.format()), "format=" + d.format());
        check("解析结果带角点坐标", d.hasCorners(),
                "corners=" + (d.corners() == null ? 0 : d.corners().size()));
        check("角点在图像范围内",
                d.corners().stream().allMatch(p ->
                        p.x() >= 0 && p.x() <= 400 && p.y() >= 0 && p.y() <= 400),
                d.corners().toString());

        // ---------- 15. 落盘一张样例供人工核对 ----------
        try {
            ImageIO.write(meta, "png", outDir.resolve("sample.png").toFile());
            ImageIO.write(big, "png", outDir.resolve("crop-source.png").toFile());
            System.out.println("[样例] 已输出到 " + outDir.toAbsolutePath());
        } catch (Exception ex) {
            System.out.println("[样例] 输出失败：" + ex.getMessage());
        }

        System.out.println();
        System.out.println("RESULT passed=" + passed + " failed=" + failed);
    }

    /** 非白像素占比，用来粗略衡量二维码的密度。 */
    private static double darkRatio(BufferedImage img) {
        int dark = 0;
        int total = 0;
        for (int y = 0; y < img.getHeight(); y += 2) {
            for (int x = 0; x < img.getWidth(); x += 2) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (r < 128 && g < 128 && b < 128) {
                    dark++;
                }
                total++;
            }
        }
        return total == 0 ? 0 : (double) dark / total;
    }
}
