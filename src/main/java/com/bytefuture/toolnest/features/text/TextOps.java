package com.bytefuture.toolnest.features.text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * 文本行处理的核心逻辑。
 *
 * <p>从界面中抽离出来是刻意的：这些变换是纯函数（输入字符串 → 输出字符串），
 * 与 JavaFX 无关，因此可以独立验证，也方便后续在批处理场景复用。
 *
 * <p>统一约定：
 * <ul>
 *   <li>行分隔符同时接受 {@code \n} 与 {@code \r\n}，输出一律用 {@code \n}
 *       —— 混用换行符是后续 diff / 比对时的常见坑</li>
 *   <li>变换保持「行数 → 行数」的直觉，除了过滤类操作（去重 / 去空行）</li>
 * </ul>
 */
public final class TextOps {

    private TextOps() {
    }

    /** 按 \r\n 或 \n 切分为行，保留空行。 */
    public static List<String> toLines(String text) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(List.of(text.split("\r\n|\n|\r", -1)));
    }

    /** 行列表合并回文本，统一用 \n。 */
    public static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    /** 文本行数（空文本算 0 行）。 */
    public static int lineCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return toLines(text).size();
    }

    /** 去掉首尾空白行（不影响行内空白）。 */
    public static String trimBlankEdges(String text) {
        List<String> lines = toLines(text);
        int start = 0;
        int end = lines.size();
        while (start < end && lines.get(start).isBlank()) {
            start++;
        }
        while (end > start && lines.get(end - 1).isBlank()) {
            end--;
        }
        return join(lines.subList(start, end));
    }

    /** 删除所有空行（含只含空白的行）。 */
    public static String removeEmptyLines(String text) {
        List<String> kept = new ArrayList<>();
        for (String line : toLines(text)) {
            if (!line.isBlank()) {
                kept.add(line);
            }
        }
        return join(kept);
    }

    /** 行去重，保留首次出现的顺序。 */
    public static String distinctLines(String text) {
        return join(new ArrayList<>(new LinkedHashSet<>(toLines(text))));
    }

    /**
     * 行排序。
     *
     * @param text     源文本
     * @param asc      升序 / 降序
     * @param caseSensitive 是否区分大小写
     * @param natural  是否按数字大小排序（否则按字符串字典序）
     */
    public static String sortLines(String text, boolean asc, boolean caseSensitive,
                                   boolean natural) {
        List<String> lines = toLines(text);
        Comparator<String> base;
        if (natural) {
            base = Comparator.comparing(TextOps::leadingNumber);
            // 数字相等时回退到字符串比较，保证排序稳定可预期
            base = base.thenComparing(s -> caseSensitive ? s : s.toLowerCase());
        } else if (caseSensitive) {
            base = Comparator.naturalOrder();
        } else {
            base = Comparator.comparing(String::toLowerCase);
        }
        lines.sort(asc ? base : base.reversed());
        return join(lines);
    }

    /**
     * 提取行首数字用于自然排序。
     *
     * <p>没有数字的行一律返回 {@link Long#MAX_VALUE}，排在末尾 ——
     * 让「有编号的行」优先按编号排列，未编号的行集中收尾，
     * 比把无数字行当成 0 混在开头更符合预期。
     */
    private static long leadingNumber(String line) {
        String trimmed = line == null ? "" : line.stripLeading();
        int i = 0;
        while (i < trimmed.length() && Character.isDigit(trimmed.charAt(i))) {
            i++;
        }
        if (i == 0) {
            return Long.MAX_VALUE;
        }
        try {
            return Long.parseLong(trimmed.substring(0, i));
        } catch (NumberFormatException ex) {
            // 数字过长（超过 long）时退化为末尾，不抛异常打断整个操作
            return Long.MAX_VALUE;
        }
    }

    /** 对每一行执行变换。 */
    public static String mapLines(String text, UnaryOperator<String> op) {
        List<String> lines = toLines(text);
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(op.apply(line));
        }
        return join(out);
    }

    /** 每行加行号。 */
    public static String addLineNumbers(String text, int start, String separator) {
        List<String> lines = toLines(text);
        int width = String.valueOf(start + Math.max(0, lines.size() - 1)).length();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(String.format("%" + width + "d", start + i)).append(separator)
                    .append(lines.get(i));
        }
        return sb.toString();
    }

    /** 正则替换（支持 $1 分组引用，且不抛异常，交给调用方处理）。 */
    public static String replaceAll(String text, String pattern, String replacement) {
        Pattern p = Pattern.compile(pattern);
        return p.matcher(text == null ? "" : text).replaceAll(replacement);
    }

    /** 移除行首空白。 */
    public static String stripLeading(String text) {
        return mapLines(text, String::stripLeading);
    }

    /** 移除行尾空白。 */
    public static String stripTrailing(String text) {
        return mapLines(text, String::stripTrailing);
    }

    /** 压缩连续空白为单个空格。 */
    public static String collapseSpaces(String text) {
        return mapLines(text, line -> line.replaceAll("[ \\t]+", " "));
    }

    // ==================== 统计 ====================

    /**
     * 文本统计结果。
     *
     * @param lines      总行数
     * @param nonBlank   非空行数
     * @param chars      字符总数（含空白）
     * @param charsNoSpace 字符数（不含空白）
     * @param words      词数（按空白切分）
     * @param bytesUtf8  UTF-8 编码后的字节数
     */
    public record Stats(int lines, int nonBlank, int chars, int charsNoSpace,
                        int words, int bytesUtf8) {

        public String describe() {
            return "行数 " + lines + "（非空 " + nonBlank + "）"
                    + "　字符 " + chars + "（不含空白 " + charsNoSpace + "）"
                    + "　词数 " + words
                    + "　UTF-8 字节 " + bytesUtf8;
        }
    }

    /** 统计文本各项指标。 */
    public static Stats stats(String text) {
        String src = text == null ? "" : text;
        if (src.isEmpty()) {
            return new Stats(0, 0, 0, 0, 0, 0);
        }
        List<String> lines = toLines(src);
        int nonBlank = 0;
        for (String line : lines) {
            if (!line.isBlank()) {
                nonBlank++;
            }
        }
        int noSpace = 0;
        for (int i = 0; i < src.length(); i++) {
            if (!Character.isWhitespace(src.charAt(i))) {
                noSpace++;
            }
        }
        // split 在「全是空白」时会产出空串，需过滤后再计数
        int words = 0;
        for (String w : src.split("\\s+")) {
            if (!w.isEmpty()) {
                words++;
            }
        }
        return new Stats(lines.size(), nonBlank, src.length(), noSpace, words,
                src.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }
}
