package com.bytefuture.toolnest.features.http;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量 CSV 解析器。
 *
 * <p>不引第三方依赖，覆盖批量测试场景的常见需求：
 * <ul>
 *   <li>分隔符自动识别：逗号 / 分号 / 制表符（按首行出现次数判断）</li>
 *   <li>引号转义：{@code "a,b"} 作为一个字段；{@code ""} 表示一个引号字符</li>
 *   <li>字段内换行（被引号包裹时）</li>
 *   <li>首行表头即变量名，后续每行是一组变量</li>
 *   <li>编码自动识别：UTF-8 BOM / UTF-8 / GBK（中文 Excel 导出常见）</li>
 * </ul>
 */
public final class CsvParser {

    /** 解析结果：表头 + 数据行。 */
    public static final class Table {
        public final List<String> headers;
        public final List<Map<String, String>> rows;

        Table(List<String> headers, List<Map<String, String>> rows) {
            this.headers = headers;
            this.rows = rows;
        }

        public int size() {
            return rows.size();
        }
    }

    private CsvParser() {
    }

    /** 从文件读取并解析，自动识别编码。 */
    public static Table parseFile(Path path) throws IOException {
        return parse(readText(path));
    }

    /**
     * 按自动识别的编码读取文件**文本**，并去除 UTF-8 BOM。
     *
     * <p>注意：不能直接用 {@code Files.readString(path, UTF_8)} —— 它会把 BOM
     * 解成 {@code U+FEFF} 留在字符串开头，填进 {@code TextArea} 后会渲染成一个
     * 空心方框，看起来像「多了一个框」。
     */
    public static String readText(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return stripBom(new String(bytes, detectCharset(bytes)));
    }

    /** 去掉字符串开头的 UTF-8 BOM（{@code U+FEFF}）。 */
    public static String stripBom(String text) {
        if (text == null) {
            return "";
        }
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    /**
     * 猜测字符编码。
     *
     * <p>策略：有 UTF-8 BOM 则用 UTF-8；否则按 UTF-8 严格解码试探，
     * 成功即认为是 UTF-8；失败则回退 GBK（中文 Windows 下 Excel 另存 CSV 的默认编码）。
     */
    public static Charset detectCharset(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        // 严格模式解码，遇到非法字节序列会抛异常（注意需用 CodingErrorAction.REPORT）
        java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes));
            return StandardCharsets.UTF_8;
        } catch (Exception notUtf8) {
            return Charset.forName("GBK");
        }
    }

    /** 解析 CSV 文本。首行作表头。 */
    public static Table parse(String text) {
        // 去掉 UTF-8 BOM，否则第一个表头名会带上不可见字符
        String content = stripBom(text);
        if (content.isBlank()) {
            return new Table(new ArrayList<>(), new ArrayList<>());
        }

        char delimiter = detectDelimiter(content);
        List<List<String>> records = splitRecords(content, delimiter);
        if (records.isEmpty()) {
            return new Table(new ArrayList<>(), new ArrayList<>());
        }

        List<String> headers = new ArrayList<>();
        for (String h : records.get(0)) {
            headers.add(h.trim());
        }

        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> record = records.get(i);
            // 整行皆为空的记录直接跳过（常见于文件末尾空行）
            if (record.stream().allMatch(String::isBlank)) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < headers.size(); c++) {
                String value = c < record.size() ? record.get(c) : "";
                row.put(headers.get(c), value.trim());
            }
            rows.add(row);
        }
        return new Table(headers, rows);
    }

    /**
     * 自动识别分隔符：比较首行（非引号内的）逗号、分号、制表符的出现次数，取最多者。
     */
    private static char detectDelimiter(String content) {
        int[] counts = new int[3];
        char[] candidates = {',', ';', '\t'};
        boolean inQuotes = false;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch == '"') {
                inQuotes = !inQuotes;
            } else if (ch == '\n' && !inQuotes) {
                break;
            } else if (!inQuotes) {
                for (int c = 0; c < candidates.length; c++) {
                    if (ch == candidates[c]) {
                        counts[c]++;
                    }
                }
            }
        }
        int best = 0;
        for (int c = 1; c < counts.length; c++) {
            if (counts[c] > counts[best]) {
                best = c;
            }
        }
        return candidates[best];
    }

    /**
     * 按 CSV 规则切分为「记录 → 字段」两层结构。
     * 支持引号包裹、引号内换行、{@code ""} 转义。
     */
    private static List<List<String>> splitRecords(String content, char delimiter) {
        List<List<String>> records = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);

            if (inQuotes) {
                if (ch == '"') {
                    // "" → 一个字面量引号；否则引号区间结束
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(ch);
                }
                continue;
            }

            if (ch == '"') {
                inQuotes = true;
            } else if (ch == delimiter) {
                current.add(field.toString());
                field.setLength(0);
            } else if (ch == '\r') {
                // 忽略，交给 \n 处理
            } else if (ch == '\n') {
                current.add(field.toString());
                field.setLength(0);
                records.add(current);
                current = new ArrayList<>();
            } else {
                field.append(ch);
            }
        }

        // 收尾：最后一行可能没有换行符
        if (field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            records.add(current);
        }
        return records;
    }

    /** 把变量行转成 CSV 文本（用于导出结果）。 */
    public static String toCsv(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(joinRow(headers)).append("\r\n");
        for (List<String> row : rows) {
            sb.append(joinRow(row)).append("\r\n");
        }
        return sb.toString();
    }

    private static String joinRow(List<String> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(cells.get(i)));
        }
        return sb.toString();
    }

    /** 含逗号、引号、换行的字段需要加引号包裹，内部引号翻倍。 */
    private static String escape(String value) {
        String v = value == null ? "" : value;
        boolean needQuote = v.indexOf(',') >= 0 || v.indexOf('"') >= 0
                || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
        if (!needQuote) {
            return v;
        }
        return '"' + v.replace("\"", "\"\"") + '"';
    }
}
