package com.bytefuture.toolnest.features.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * {@code multipart/form-data} 的组包与展示（零 JavaFX，可单独测试）。
 *
 * <p>原本请求体类型里根本没有 multipart，界面上也就无从「选择本地文件」。
 * 这个类补上组包这一半：每个字段写成
 * <pre>
 *   --boundary CRLF
 *   Content-Disposition: form-data; name="avatar"; filename="a.png" CRLF
 *   Content-Type: image/png CRLF
 *   CRLF
 *   文件字节 CRLF
 * </pre>
 * 最后以 {@code --boundary--} 收尾。文本段的区别只是没有 {@code filename}
 * 与 {@code Content-Type} 两行。
 *
 * <p><b>已知限制</b>：整段内容一次性拼进内存（{@code ByteArrayOutputStream}）。
 * 超过约 100MB 的文件未做流式分段 —— 与「Binary 走 {@code ofFile} 流式」不同，
 * multipart 的每一段都要在头部写长度不可知的内容，改流式要自己实现
 * {@code BodyPublisher}，本轮不做。
 */
final class MultipartBody {

    private MultipartBody() {
    }

    /** 生成一个 boundary。前缀与内容都只含 ASCII，不需要引号包裹。 */
    static String newBoundary() {
        return "----ToolNestBoundary" + Long.toHexString(System.nanoTime());
    }

    /**
     * 按 multipart 规则拼出完整请求体。
     *
     * @throws IOException 某个文件段读不出来（路径不存在 / 无权限）
     */
    static byte[] build(List<HttpRequestSpec.FormField> fields, String boundary)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (HttpRequestSpec.FormField field : fields) {
            if (field.name.isBlank()) {
                continue;
            }
            write(out, "--" + boundary + "\r\n");
            if (field.isFile()) {
                String filename = field.fileName();
                write(out, "Content-Disposition: form-data; name=\"" + field.name
                        + "\"; filename=\"" + filename + "\"\r\n");
                write(out, "Content-Type: " + mimeOf(filename) + "\r\n\r\n");
                out.write(Files.readAllBytes(Path.of(field.path)));
            } else {
                write(out, "Content-Disposition: form-data; name=\"" + field.name + "\"\r\n\r\n");
                write(out, field.text);
            }
            write(out, "\r\n");
        }
        write(out, "--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    /**
     * 给「请求详情」页签用的人读版本：文件段不摊开字节，改成一行说明。
     *
     * <p>直接把真实字节塞进文本域毫无意义，还会把真正的元信息冲掉。
     */
    static String describe(List<HttpRequestSpec.FormField> fields, String boundary,
                           long fileBytes) {
        StringBuilder sb = new StringBuilder();
        for (HttpRequestSpec.FormField field : fields) {
            if (field.name.isBlank()) {
                continue;
            }
            sb.append("--").append(boundary).append('\n');
            if (field.isFile()) {
                sb.append("Content-Disposition: form-data; name=\"").append(field.name)
                        .append("\"; filename=\"").append(field.fileName()).append("\"\n");
                sb.append("Content-Type: ").append(mimeOf(field.fileName())).append("\n\n");
                sb.append("[文件内容 ").append(fileBytes).append(" 字节：").append(field.path)
                        .append("]\n");
            } else {
                sb.append("Content-Disposition: form-data; name=\"").append(field.name)
                        .append("\"\n\n");
                sb.append(field.text).append('\n');
            }
        }
        sb.append("--").append(boundary).append("--\n");
        return sb.toString();
    }

    /**
     * 按扩展名猜 MIME。
     *
     * <p>刻意只做最小映射：一张完整的 MIME 表既写不全也维护不动，
     * 猜错反而会让服务端按错误的类型解析。表外一律 {@code application/octet-stream}，
     * 那是「未知二进制」的标准答案。
     */
    static String mimeOf(String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1) : "";
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "pdf" -> "application/pdf";
            case "json" -> "application/json";
            case "xml" -> "application/xml";
            case "txt" -> "text/plain";
            case "csv" -> "text/csv";
            default -> "application/octet-stream";
        };
    }

    private static void write(ByteArrayOutputStream out, String text) {
        byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        out.writeBytes(bytes);
    }
}
