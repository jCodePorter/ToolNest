package com.bytefuture.toolnest.features.image;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;

/**
 * 图片处理核心逻辑（缩放 / 裁剪 / 格式转换）。
 *
 * <p>用 JDK 内置的 {@link ImageIO} 与 AWT，<b>零第三方依赖</b>。
 * 支持读取 PNG / JPG / BMP / GIF / TIFF（JDK 自带解码器范围）。
 *
 * <h2>不引入 ImageIO 之外的库的取舍</h2>
 * WebP 需要额外依赖，JDK 原生不支持。这里选择不引库，因此在界面上
 * 明确标注支持格式，避免用户拖入 WebP 后得到含糊的失败提示。
 *
 * <h2>两个容易出错的点</h2>
 * <ol>
 *   <li><b>带透明通道的图转 JPEG 会失败</b>：JPEG 不支持 alpha 通道。
 *       本类在写 JPEG 时会自动把图合成到白底上，而不是抛异常 ——
 *       这是用户最常见的操作路径（PNG 转 JPG），必须让它能直接成功。</li>
 *   <li><b>缩放要用高质量插值</b>：默认的最近邻插值在缩小照片时会出现
 *       严重锯齿，必须显式指定 {@link RenderingHints} 的双线性/双三次插值。</li>
 * </ol>
 */
public final class ImageOps {

    private ImageOps() {
    }

    /** 支持的输出格式。 */
    public enum Format {
        PNG("PNG", "png", "无损，支持透明"),
        JPEG("JPEG", "jpg", "有损，体积小，不支持透明"),
        BMP("BMP", "bmp", "无压缩，体积大"),
        GIF("GIF", "gif", "最多 256 色，支持简单透明");

        private final String imageIoName;
        private final String extension;
        private final String note;

        Format(String imageIoName, String extension, String note) {
            this.imageIoName = imageIoName;
            this.extension = extension;
            this.note = note;
        }

        public String imageIoName() {
            return imageIoName;
        }

        public String extension() {
            return extension;
        }

        public String note() {
            return note;
        }

        /** 该格式是否支持透明通道。 */
        public boolean supportsAlpha() {
            return this == PNG || this == GIF;
        }

        @Override
        public String toString() {
            return name() + "（." + extension + "）";
        }
    }

    /** 尺寸规格：指定宽高，null 或 0 表示「按比例自适应」。 */
    public record Size(int width, int height) {

        public boolean isEmpty() {
            return width <= 0 && height <= 0;
        }

        @Override
        public String toString() {
            return width + " × " + height;
        }
    }

    /** 裁剪矩形，坐标相对原图。 */
    public record Crop(int x, int y, int width, int height) {

        public boolean isValid(int imageWidth, int imageHeight) {
            return width > 0 && height > 0
                    && x >= 0 && y >= 0
                    && x + width <= imageWidth
                    && y + height <= imageHeight;
        }
    }

    // ==================== 读取 ====================

    /**
     * 读取图片。
     *
     * @throws IOException 格式不支持或文件损坏
     */
    public static BufferedImage read(Path path) throws IOException {
        try {
            BufferedImage image = ImageIO.read(path.toFile());
            if (image == null) {
                throw new IOException("无法识别的图片格式，或该格式不被当前环境支持");
            }
            return image;
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("读取失败：" + ex.getMessage(), ex);
        }
    }

    // ==================== 变换 ====================

    /**
     * 等比缩放到目标尺寸。
     *
     * <p>若只给宽或只给高，另一边按原图比例自动算出；
     * 若宽高都给了，则<b>按较小的缩放比</b>缩放，保证整图完整落在目标范围内
     * （即 contain 语义，不会裁掉内容也不会拉伸变形）。
     *
     * @return 缩放后的图；尺寸与目标一致时返回原图
     */
    public static BufferedImage scale(BufferedImage source, Size target) {
        if (target == null || target.isEmpty()) {
            return source;
        }
        int srcW = source.getWidth();
        int srcH = source.getHeight();
        double ratio;
        if (target.width() <= 0) {
            ratio = (double) target.height() / srcH;
        } else if (target.height() <= 0) {
            ratio = (double) target.width() / srcW;
        } else {
            ratio = Math.min((double) target.width() / srcW,
                    (double) target.height() / srcH);
        }
        int newW = Math.max(1, (int) Math.round(srcW * ratio));
        int newH = Math.max(1, (int) Math.round(srcH * ratio));
        if (newW == srcW && newH == srcH) {
            return source;
        }
        return draw(source, newW, newH, source.getType());
    }

    /** 按精确尺寸重绘（可能变形，由调用方确认）。 */
    public static BufferedImage resizeExact(BufferedImage source, Size target) {
        if (target == null || target.isEmpty()) {
            return source;
        }
        int w = target.width() > 0 ? target.width() : source.getWidth();
        int h = target.height() > 0 ? target.height() : source.getHeight();
        return draw(source, w, h, source.getType());
    }

    /** 按倍率缩放（0.5 = 缩小一半）。 */
    public static BufferedImage scaleByFactor(BufferedImage source, double factor) {
        if (factor <= 0) {
            throw new IllegalArgumentException("缩放倍率必须大于 0");
        }
        int w = Math.max(1, (int) Math.round(source.getWidth() * factor));
        int h = Math.max(1, (int) Math.round(source.getHeight() * factor));
        return draw(source, w, h, source.getType());
    }

    /**
     * 按目标画布重绘，使用高质量插值。
     *
     * <p>把源图先转成带透明通道的 {@code TYPE_INT_ARGB} 再绘制，
     * 避免索引色（GIF/BMP 常见）在缩放时出现配色错乱。
     */
    private static BufferedImage draw(BufferedImage source, int width, int height, int type) {
        int imageType = (type == BufferedImage.TYPE_CUSTOM || type == 0)
                ? BufferedImage.TYPE_INT_ARGB : type;
        // 统一用 ARGB 承载，保证透明通道不丢
        imageType = BufferedImage.TYPE_INT_ARGB;

        BufferedImage result = new BufferedImage(width, height, imageType);
        Graphics2D g = result.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return result;
    }

    /**
     * 裁剪。
     *
     * @throws IllegalArgumentException 裁剪区域超出原图范围
     */
    public static BufferedImage crop(BufferedImage source, Crop crop) {
        if (crop == null) {
            return source;
        }
        if (!crop.isValid(source.getWidth(), source.getHeight())) {
            throw new IllegalArgumentException(String.format(
                    "裁剪区域超出图片范围（图片 %d×%d，裁剪 x=%d y=%d w=%d h=%d）",
                    source.getWidth(), source.getHeight(),
                    crop.x(), crop.y(), crop.width(), crop.height()));
        }
        BufferedImage result = new BufferedImage(
                crop.width(), crop.height(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        try {
            g.drawImage(source, -crop.x(), -crop.y(), null);
        } finally {
            g.dispose();
        }
        return result;
    }

    /** 顺时针旋转 90 的整数倍。 */
    public static BufferedImage rotate(BufferedImage source, int degrees) {
        int normalized = ((degrees % 360) + 360) % 360;
        if (normalized == 0) {
            return source;
        }
        double radians = Math.toRadians(normalized);
        int w = source.getWidth();
        int h = source.getHeight();
        // 90 / 270 度时宽高互换
        int newW = (normalized == 90 || normalized == 270) ? h : w;
        int newH = (normalized == 90 || normalized == 270) ? w : h;

        BufferedImage result = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.translate(newW / 2.0, newH / 2.0);
            g.rotate(radians);
            g.translate(-w / 2.0, -h / 2.0);
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return result;
    }

    /**
     * 水平翻转。
     */
    public static BufferedImage flipHorizontal(BufferedImage source) {
        BufferedImage result = new BufferedImage(
                source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        try {
            g.drawImage(source, source.getWidth(), 0, -source.getWidth(),
                    source.getHeight(), null);
        } finally {
            g.dispose();
        }
        return result;
    }

    /**
     * 把带透明通道的图合成到纯色背景上。
     *
     * <p>转 JPEG 前必须做这一步 —— JPEG 无 alpha 通道，
     * 直接写会让透明区域变成黑块。
     */
    public static BufferedImage flatten(BufferedImage source, java.awt.Color background) {
        BufferedImage result = new BufferedImage(
                source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        try {
            g.setColor(background == null ? java.awt.Color.WHITE : background);
            g.fillRect(0, 0, result.getWidth(), result.getHeight());
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return result;
    }

    /** 该图是否含有实际使用的透明像素。 */
    public static boolean hasTransparency(BufferedImage image) {
        if (!image.getColorModel().hasAlpha()) {
            return false;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        // 抽样检测：全图逐像素在大图上太慢，按步长采样足够判断
        int step = Math.max(1, Math.min(w, h) / 200);
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                if (((image.getRGB(x, y) >>> 24) & 0xFF) < 255) {
                    return true;
                }
            }
        }
        return false;
    }

    // ==================== 写出 ====================

    /**
     * 写出图片。
     *
     * @param format 输出格式
     * @param quality JPEG 质量（0.0–1.0），仅对 JPEG 生效；其他格式忽略
     * @throws IOException 写出失败
     */
    public static void write(BufferedImage image, Path target, Format format, float quality)
            throws IOException {
        BufferedImage out = image;

        // JPEG 不支持透明：先合成白底，避免透明区域变黑块
        if (!format.supportsAlpha() && hasTransparency(image)) {
            out = flatten(image, java.awt.Color.WHITE);
        } else if (format == Format.JPEG && out.getType() != BufferedImage.TYPE_INT_RGB) {
            // JPEG 编码器对部分图像类型会抛异常，统一转成 RGB
            out = flatten(image, java.awt.Color.WHITE);
        }
        // GIF 走索引色更稳，但保留 ARGB 也能写出；这里统一交给编码器处理

        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        if (format == Format.JPEG) {
            writeJpegWithQuality(out, target, quality);
            return;
        }

        // 其余格式用默认写出器即可
        if (!ImageIO.write(out, format.imageIoName(), target.toFile())) {
            throw new IOException("当前环境没有 " + format.imageIoName() + " 编码器");
        }
    }

    /**
     * 按指定质量写 JPEG。
     *
     * <p>{@code ImageIO.write} 不提供质量控制入口，必须显式取
     * {@link ImageWriteParam} 才能设压缩质量。
     */
    private static void writeJpegWithQuality(BufferedImage image, Path target, float quality)
            throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("当前环境没有 JPEG 编码器");
        }
        ImageWriter writer = writers.next();
        float q = Math.max(0.05f, Math.min(1.0f, quality <= 0 ? 0.85f : quality));

        try (OutputStream os = Files.newOutputStream(target);
             ImageOutputStream ios = ImageIO.createImageOutputStream(os)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(q);
            }
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    /** 从文件名推断格式，无法识别返回 null。 */
    public static Format detectFormat(String fileName) {
        if (fileName == null) {
            return null;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        return switch (lower.substring(dot + 1)) {
            case "png" -> Format.PNG;
            case "jpg", "jpeg" -> Format.JPEG;
            case "bmp" -> Format.BMP;
            case "gif" -> Format.GIF;
            default -> null;
        };
    }

    /** 替换文件扩展名。 */
    public static String replaceExtension(String fileName, Format format) {
        if (fileName == null || fileName.isBlank()) {
            return "output." + format.extension();
        }
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        return base + "." + format.extension();
    }

    /** 人类可读的文件大小。 */
    public static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB"};
        double value = bytes;
        int i = -1;
        while (value >= 1024 && i < units.length - 1) {
            value /= 1024;
            i++;
        }
        return String.format("%.2f %s", value, units[i]);
    }
}
