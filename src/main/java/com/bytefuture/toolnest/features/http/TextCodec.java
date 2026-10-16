package com.bytefuture.toolnest.features.http;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 字节 → 文本的解码工具。
 *
 * <p>HTTP 与 CSV 两条链路都会碰到「拿到一堆字节，不知道是什么编码」的问题：
 * <ul>
 *   <li>响应体：优先信 {@code Content-Type: ...; charset=xxx}，没写时靠猜；
 *       中文接口返回 GBK 却不带 charset 的非常常见，一律按 UTF-8 解会全是乱码；</li>
 *   <li>CSV 文件：中文 Excel「另存为 CSV」默认写 GBK，同样没有声明。</li>
 * </ul>
 *
 * <p>猜测策略（{@link #detectCharset}）：有 UTF-8 BOM 或能通过<b>严格</b>
 * UTF-8 解码就按 UTF-8，失败才回退 GBK —— 顺序不能反，
 * 否则纯 ASCII 的 UTF-8 文件会被 GBK 解成乱码。
 */
final class TextCodec {

    private TextCodec() {
    }

    /** 去掉字符串开头的 UTF-8 BOM（{@code U+FEFF}）。 */
    static String stripBom(String text) {
        if (text == null) {
            return "";
        }
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    /** 猜测字节序列的编码。 */
    static Charset detectCharset(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return StandardCharsets.UTF_8;
        }
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        // 严格模式解码，遇到非法字节序列会抛异常（注意需用 CodingErrorAction.REPORT）
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(bytes));
            return StandardCharsets.UTF_8;
        } catch (Exception notUtf8) {
            return Charset.forName("GBK");
        }
    }

    /**
     * 解码响应体。
     *
     * @param bytes          原始字节
     * @param contentType    响应里的 {@code Content-Type} 头，可为 null
     * @return 解码后的文本；字节为空时返回空串
     */
    static String decode(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        Charset charset = charsetOf(contentType);
        if (charset == null) {
            charset = detectCharset(bytes);
        }
        return new String(bytes, charset);
    }

    /**
     * 从 {@code Content-Type} 头里取 charset，取不到或不被支持时返回 {@code null}
     * （调用方据此退回到猜测）。
     */
    static Charset charsetOf(String contentType) {
        if (contentType == null || contentType.isEmpty()) {
            return null;
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        int at = lower.indexOf("charset");
        if (at < 0) {
            return null;
        }
        int eq = lower.indexOf('=', at);
        if (eq < 0) {
            return null;
        }
        String name = contentType.substring(eq + 1).trim();
        int semi = name.indexOf(';');
        if (semi >= 0) {
            name = name.substring(0, semi);
        }
        name = name.trim().replace("\"", "").replace("'", "");
        if (name.isEmpty()) {
            return null;
        }
        try {
            return Charset.forName(name);
        } catch (Exception unsupported) {
            return null;
        }
    }
}
