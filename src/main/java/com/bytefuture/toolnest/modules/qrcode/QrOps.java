package com.bytefuture.toolnest.modules.qrcode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.qrcode.QRCodeMultiReader;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 二维码生成与解析的核心逻辑（纯函数，不依赖 JavaFX）。
 *
 * <p>编解码交给 ZXing 的 {@code core} 模块 —— 二维码的 Reed-Solomon 纠错、
 * 掩码选择、版本计算是一套很容易写错的算法，自己实现风险远大于收益。
 * 这里只负责「参数整理 + 图像格式转换 + 结果包装」。
 *
 * <h2>为什么自写 LuminanceSource</h2>
 * ZXing 官方把 {@code BufferedImageLuminanceSource} 放在 {@code javase} 模块里，
 * 那个模块会顺带引入 {@code jcommander}（命令行解析）与 {@code jai-imageio-core}
 * （TIFF 支持）两个运行时依赖。我们只用得上那一个类，自己实现一份约 40 行，
 * 换来少两个依赖。
 */
public final class QrOps {

    /** 纠错级别。级别越高越耐污损，但同样内容需要的码点更多、码更大。 */
    public enum Level {
        L("L", "约 7%", "最小尺寸，适合屏幕内清晰展示"),
        M("M", "约 15%", "默认级别，通用场景推荐"),
        Q("Q", "约 25%", "有轻微遮挡或反光时更稳"),
        H("H", "约 30%", "最高容错，中间可叠 logo 或图标");

        private final String tag;
        private final String ratio;
        private final String note;

        Level(String tag, String ratio, String note) {
            this.tag = tag;
            this.ratio = ratio;
            this.note = note;
        }

        /** ZXing 的级别标识。 */
        public String tag() {
            return tag;
        }

        /** 可恢复的信息量占比。 */
        public String ratio() {
            return ratio;
        }

        /** 使用场景说明。 */
        public String note() {
            return note;
        }

        ErrorCorrectionLevel toZxing() {
            return switch (this) {
                case L -> ErrorCorrectionLevel.L;
                case M -> ErrorCorrectionLevel.M;
                case Q -> ErrorCorrectionLevel.Q;
                case H -> ErrorCorrectionLevel.H;
            };
        }

        @Override
        public String toString() {
            return tag + "（" + ratio + "）";
        }
    }

    /** 生成参数。 */
    public record GenerateOptions(
            int size,
            Level level,
            int margin,
            java.awt.Color foreground,
            java.awt.Color background) {

        /** 默认参数：512px、M 级、4 模块边距、黑底白。 */
        public static GenerateOptions defaults() {
            return new GenerateOptions(512, Level.M, 4,
                    java.awt.Color.BLACK, java.awt.Color.WHITE);
        }
    }

    /** 一条识别结果。 */
    public record Decoded(String text, String format, List<Point> corners) {

        /** 二维码在图中的角点坐标（可能为空 —— 某些情况 ZXing 不返回位置）。 */
        public record Point(double x, double y) {
        }

        /** 是否定位到了四个角点，用于界面画框高亮。 */
        public boolean hasCorners() {
            return corners != null && corners.size() >= 3;
        }
    }

    /** 解析失败的原因分类，便于给出可操作的中文提示。 */
    public enum Failure {
        NOT_FOUND("没有在图片中找到二维码。请确认二维码完整、清晰、未严重倾斜。"),
        FORMAT("图片格式无法识别，或文件已损坏。"),
        CHECKSUM("二维码能定位但内容校验失败，图片可能过糊或有遮挡。"),
        UNKNOWN("解析失败。");

        private final String message;

        Failure(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    /** 解析异常，携带可读中文原因。 */
    public static final class QrException extends Exception {
        private final Failure failure;

        QrException(Failure failure, String detail) {
            super(failure.message() + (detail == null ? "" : "（" + detail + "）"));
            this.failure = failure;
        }

        public Failure failure() {
            return failure;
        }
    }

    private QrOps() {
    }

    // ------------------------------------------------------------------ 生成

    /**
     * 生成二维码图像。
     *
     * @param text 要编码的文本（URL、纯文本、vCard 等均可）
     * @param opts 尺寸 / 纠错级别 / 边距 / 前景背景色
     * @return 可直接显示的 {@link BufferedImage}
     * @throws IllegalArgumentException 文本为空时
     * @throws QrException              内容超出二维码容量时
     */
    public static BufferedImage generate(String text, GenerateOptions opts)
            throws QrException {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("内容不能为空");
        }
        GenerateOptions o = opts == null ? GenerateOptions.defaults() : opts;

        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, o.level().toZxing());
        hints.put(EncodeHintType.MARGIN, Math.max(0, o.margin()));

        try {
            // MultiFormatWriter 会在编码前自动选择最合适的版本号
            BitMatrix matrix = new MultiFormatWriter().encode(
                    text, BarcodeFormat.QR_CODE, o.size(), o.size(), hints);
            return toBufferedImage(matrix,
                    o.foreground().getRGB(), o.background().getRGB());
        } catch (com.google.zxing.WriterException ex) {
            // 最常见的原因是内容超过所选纠错级别下的最大容量
            throw new QrException(Failure.UNKNOWN, "内容过长，超出二维码容量上限");
        }
    }

    /**
     * 把 ZXing 的位矩阵画成图像。
     *
     * <p>自己实现而非用 javase 模块的 {@code MatrixToImageWriter}，
     * 同样是为了不引入额外的运行时依赖。
     */
    private static BufferedImage toBufferedImage(BitMatrix matrix,
                                                 int onColor, int offColor) {
        int width = matrix.getWidth();
        int height = matrix.getHeight();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                row[x] = matrix.get(x, y) ? onColor : offColor;
            }
            image.setRGB(0, y, width, 1, row, 0, width);
        }
        return image;
    }

    /** 内容能生成二维码时的最大字符数估算（仅作界面提示，非精确值）。 */
    public static int capacityHint(Level level) {
        return switch (level) {
            case L -> 2953;
            case M -> 2331;
            case Q -> 1663;
            case H -> 1273;
        };
    }

    // ------------------------------------------------------------------ 解析

    /**
     * 解析单张图中的所有二维码 / 条码。
     *
     * <p>两段式尝试：
     * <ol>
     *   <li>{@link QRCodeMultiReader} —— 能一次找出图中**所有**二维码，
     *       适合截图里同时有好几个码的情况</li>
     *   <li>失败则退回 {@link MultiFormatReader} —— 它能识别二维码之外的
     *       条码（Code128、EAN-13 等）。用户拿到的码未必是 QR，
     *       顺手支持没有额外成本，却能省掉一次「为什么扫不出来」</li>
     * </ol>
     *
     * @param image 待解析图像
     * @return 识别结果列表；找不到时返回空列表（不抛异常）
     */
    public static List<Decoded> decodeAll(BufferedImage image) {
        if (image == null) {
            return List.of();
        }
        BinaryBitmap bitmap = new BinaryBitmap(
                new HybridBinarizer(new BufferedImageLuminanceSource(image)));

        // ---------- 第一段：多二维码 ----------
        Map<DecodeHintType, Object> qrHints = new EnumMap<>(DecodeHintType.class);
        qrHints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        qrHints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);

        try {
            Result[] raw = new QRCodeMultiReader().decodeMultiple(bitmap, qrHints);
            if (raw != null && raw.length > 0) {
                List<Decoded> results = new ArrayList<>();
                for (Result r : raw) {
                    results.add(toDecoded(r));
                }
                return results;
            }
        } catch (NotFoundException notFound) {
            // 正常的「没找到」，继续尝试下一段
        } catch (Exception ignored) {
            // 解码异常同样降级处理
        }

        // ---------- 第二段：单码（含一维条码） ----------
        Map<DecodeHintType, Object> anyHints = new EnumMap<>(DecodeHintType.class);
        anyHints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        anyHints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        List<BarcodeFormat> formats = new ArrayList<>();
        formats.add(BarcodeFormat.QR_CODE);
        formats.add(BarcodeFormat.DATA_MATRIX);
        formats.add(BarcodeFormat.AZTEC);
        formats.add(BarcodeFormat.PDF_417);
        formats.add(BarcodeFormat.CODE_128);
        formats.add(BarcodeFormat.CODE_39);
        formats.add(BarcodeFormat.EAN_13);
        formats.add(BarcodeFormat.EAN_8);
        formats.add(BarcodeFormat.UPC_A);
        anyHints.put(DecodeHintType.POSSIBLE_FORMATS, formats);

        try {
            Result single = new MultiFormatReader().decode(bitmap, anyHints);
            return single == null ? List.of() : List.of(toDecoded(single));
        } catch (NotFoundException notFound) {
            return List.of();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    /**
     * 解析图中单个二维码，失败时抛出带中文原因的异常。
     *
     * <p>与 {@link #decodeAll} 的区别是「失败要报错」——
     * 界面上用户点了「解析」却什么都没发生是最糟的反馈。
     */
    public static Decoded decodeOne(BufferedImage image) throws QrException {
        List<Decoded> all = decodeAll(image);
        if (all.isEmpty()) {
            throw new QrException(Failure.NOT_FOUND, null);
        }
        return all.get(0);
    }

    private static Decoded toDecoded(Result result) {
        List<Decoded.Point> points = new ArrayList<>();
        ResultPoint[] raw = result.getResultPoints();
        if (raw != null) {
            for (ResultPoint p : raw) {
                if (p != null) {
                    points.add(new Decoded.Point(p.getX(), p.getY()));
                }
            }
        }
        String format = result.getBarcodeFormat() == null
                ? "未知" : result.getBarcodeFormat().name();
        return new Decoded(result.getText(), format, points);
    }

    // ------------------------------------------------------------------ 图像辅助

    /**
     * 从大图中裁出一块区域，用于「框选后只解析选中的部分」。
     *
     * <p>实际用途：用户截了一张带界面的图，二维码只占一个小角落，
     * 整图直接解析容易受干扰；裁出来再解析命中率高得多。
     *
     * @param image  源图
     * @param x      左上角 x（可越界，内部会夹取到合法范围）
     * @param y      左上角 y
     * @param width  宽
     * @param height 高
     */
    public static BufferedImage crop(BufferedImage image,
                                     int x, int y, int width, int height) {
        if (image == null) {
            throw new IllegalArgumentException("源图为空");
        }
        int imgW = image.getWidth();
        int imgH = image.getHeight();

        // 夹取到合法范围：用户手输/拖拽的坐标常常会越界一点点
        int cx = Math.max(0, Math.min(x, imgW - 1));
        int cy = Math.max(0, Math.min(y, imgH - 1));
        int cw = Math.max(1, Math.min(width, imgW - cx));
        int ch = Math.max(1, Math.min(height, imgH - cy));

        BufferedImage out = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.drawImage(image, 0, 0, cw, ch, cx, cy, cx + cw, cy + ch, null);
        g.dispose();
        return out;
    }

    /**
     * 按比例放大图像，提升小尺寸二维码的识别率。
     *
     * <p>ZXing 的定位依赖足够的像素采样，一张 40×40 的二维码直接扫常常失败，
     * 放大 4 倍后往往就能识别。
     */
    public static BufferedImage upscale(BufferedImage image, int factor) {
        if (image == null || factor <= 1) {
            return image;
        }
        int w = image.getWidth() * factor;
        int h = image.getHeight() * factor;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(image, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /**
     * 多策略解析：原图 → 放大 → 高对比度灰度。
     *
     * <p>单次解析失败不代表图里没有码 —— 小尺寸、低对比度、偏色都会让
     * 单次尝试落空。逐级加重处理能显著提升「拖进来就能扫出来」的成功率，
     * 代价只是一次额外的解码尝试（毫秒级）。
     */
    public static List<Decoded> decodeRobust(BufferedImage image) {
        List<Decoded> direct = decodeAll(image);
        if (!direct.isEmpty()) {
            return direct;
        }
        // 放大 3 倍再试：对小码最有效
        List<Decoded> upscaled = decodeAll(upscale(image, 3));
        if (!upscaled.isEmpty()) {
            return upscaled;
        }
        // 高对比度灰度化再试：对付偏色/低对比
        return decodeAll(toHighContrastGray(image));
    }

    /** 转高对比度灰度，弱化偏色与低对比对识别的干扰。 */
    static BufferedImage toHighContrastGray(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                // 按人眼感知权重转灰度，再做一个简单的阈值拉伸
                int gray = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                gray = gray < 128 ? Math.max(0, gray - 40) : Math.min(255, gray + 40);
                int v = gray << 16 | gray << 8 | gray;
                out.setRGB(x, y, v);
            }
        }
        return out;
    }

    /**
     * 从图像中提取亮度矩阵供 ZXing 使用。
     *
     * <p>这是官方 {@code BufferedImageLuminanceSource} 的等价实现（见类注释说明）。
     * 要点：<b>必须保持行序</b>，ZXing 的算法假设 data 是逐行排列的灰度值。
     */
    static final class BufferedImageLuminanceSource extends LuminanceSource {

        private final BufferedImage image;
        private final int left;
        private final int top;
        /** 缓存的灰度数据，避免每次 getMatrix 都重算。 */
        private byte[] luminances;

        BufferedImageLuminanceSource(BufferedImage image) {
            this(image, 0, 0, image.getWidth(), image.getHeight());
        }

        BufferedImageLuminanceSource(BufferedImage image, int left, int top,
                                     int width, int height) {
            super(width, height);
            if (image.getType() == BufferedImage.TYPE_BYTE_GRAY
                    && left == 0 && top == 0
                    && width == image.getWidth() && height == image.getHeight()) {
                this.image = image;
            } else {
                // 统一成灰度图，省得在 getRow 里反复做色彩转换
                BufferedImage gray = new BufferedImage(width, height,
                        BufferedImage.TYPE_BYTE_GRAY);
                java.awt.Graphics2D g = gray.createGraphics();
                g.drawImage(image, 0, 0, width, height,
                        left, top, left + width, top + height, null);
                g.dispose();
                this.image = gray;
            }
            this.left = 0;
            this.top = 0;
        }

        @Override
        public byte[] getRow(int y, byte[] row) {
            if (y < 0 || y >= getHeight()) {
                throw new IllegalArgumentException("请求的行超出图像范围: " + y);
            }
            int width = getWidth();
            if (row == null || row.length < width) {
                row = new byte[width];
            }
            byte[] all = getMatrix();
            System.arraycopy(all, y * width, row, 0, width);
            return row;
        }

        @Override
        public byte[] getMatrix() {
            if (luminances != null) {
                return luminances;
            }
            int width = getWidth();
            int height = getHeight();
            byte[] out = new byte[width * height];
            // getRGB 一次性取整行比逐像素调用快得多
            int[] pixels = new int[width];
            for (int y = 0; y < height; y++) {
                image.getRGB(left, top + y, width, 1, pixels, 0, width);
                int offset = y * width;
                for (int x = 0; x < width; x++) {
                    int p = pixels[x];
                    int r = (p >> 16) & 0xFF;
                    int g = (p >> 8) & 0xFF;
                    int b = p & 0xFF;
                    // 标准灰度权重，与 ZXing 官方实现一致
                    out[offset + x] = (byte) ((306 * r + 601 * g + 117 * b) >> 10);
                }
            }
            luminances = out;
            return out;
        }

        @Override
        public boolean isCropSupported() {
            return true;
        }

        @Override
        public LuminanceSource crop(int left, int top, int width, int height) {
            return new BufferedImageLuminanceSource(image,
                    this.left + left, this.top + top, width, height);
        }
    }

    /** 供界面展示的「解析尝试顺序」说明。 */
    public static Map<String, String> strategyNotes() {
        Map<String, String> notes = new LinkedHashMap<>();
        notes.put("原图直接解析", "绝大多数清晰截图与标准二维码在这一步就成功");
        notes.put("放大后解析", "针对小尺寸二维码，放大提升像素采样密度");
        notes.put("高对比度灰度解析", "针对偏色、低对比、有渐变背景的图片");
        return notes;
    }
}
