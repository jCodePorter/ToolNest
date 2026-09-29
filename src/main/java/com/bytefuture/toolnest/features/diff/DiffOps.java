package com.bytefuture.toolnest.features.diff;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本对比的纯逻辑实现 —— 零 JavaFX 依赖，可脱离界面单独测试。
 *
 * <h2>三类差异是怎么来的</h2>
 * <p>纯行级 diff 会把 {@code port = 8080 → 9090} 拆成「删一行 + 加一行」，
 * 屏幕上同时出现红行与绿行，用户得自己判断它们是不是同一行的前后。
 * 因此这里做两步：
 * <ol>
 *   <li><b>行级</b>：对归一化后的行做 LCS，得到「相同 / 只在左侧 / 只在右侧」；
 *       一段里两侧的行再求<b>单调最优配对</b>，字符相似度达到
 *       {@value #SIMILARITY_THRESHOLD} 的一对判定为 {@link Kind#MOD}（修改），
 *       配不上的各自算删 / 增。</li>
 *   <li><b>行内</b>：{@code MOD} 行再做一次 token 级 LCS，给出词级变化区间
 *       （{@link Granularity#WORD} / {@link Granularity#CHAR}）。</li>
 * </ol>
 *
 * <h2>行对齐</h2>
 * <p>输出的是<b>合并行</b>序列（{@link DiffRow}），每一行同时描述左右两侧：
 * 一侧没有对应行时该侧行号为 0、区间为空 —— 界面据此画斜纹占位行。
 * 正因如此，左右两栏的行数<b>恒等</b>，同一屏的横向扫视永远对得上。
 *
 * <h2>忽略规则</h2>
 * <p>忽略空白 / 忽略大小写 / 忽略空行都是<b>归一化之后再比对，显示仍用原文</b> ——
 * 归一化只影响「这两行算不算相同」的判断，不改变用户看到的内容。
 *
 * <h2>大文本</h2>
 * <p>行数积超过 {@value #DP_CELL_LIMIT} 时不走 LCS（内存与时间都不可控），
 * 退化为按行号逐位比对并在 {@link Result#degraded()} 里标记，界面据此提示。
 */
public final class DiffOps {

    /** 差异类型。{@code MOD} 是「同一行的前后版本」，与「删一行 + 加一行」语义不同。 */
    public enum Kind {
        SAME, ADD, DEL, MOD
    }

    /** 行内比对的粒度。 */
    public enum Granularity {
        /** 只做行级高亮，行内不着色。 */
        LINE,
        /** 行内按词标记（中文逐字，因为中文没有空格分词）。 */
        WORD,
        /** 行内按字符标记。 */
        CHAR
    }

    /** 比对选项。 */
    public record Options(Granularity granularity, boolean ignoreWhitespace,
                          boolean ignoreCase, boolean ignoreBlank) {

        /** 默认：按词标记，不忽略任何差异，同步滚动在界面层默认开启。 */
        public static Options defaults() {
            return new Options(Granularity.WORD, false, false, false);
        }

        public Options withGranularity(Granularity g) {
            return new Options(g == null ? granularity : g, ignoreWhitespace, ignoreCase, ignoreBlank);
        }

        /** 当前生效的忽略规则，用于「一致」结论的文案。 */
        public String describeIgnores() {
            List<String> parts = new ArrayList<>();
            if (ignoreWhitespace) {
                parts.add("忽略空白");
            }
            if (ignoreCase) {
                parts.add("忽略大小写");
            }
            if (ignoreBlank) {
                parts.add("忽略空行");
            }
            return parts.isEmpty() ? "逐字面比对" : "忽略" + String.join("、", parts).substring(2);
        }
    }

    /** 行内片段。{@code changed} 为真表示这段在两行之间发生了变化。 */
    public record Span(String text, boolean changed) {
    }

    /**
     * 合并行 —— 左右两侧的一对（可能一侧缺失）。
     *
     * @param kind      SAME / ADD / DEL / MOD
     * @param leftNo    原文行号，1 起；0 表示该侧没有对应行（画斜纹占位）
     * @param rightNo   修改后行号，同上
     * @param leftSpans 原文行内的片段；该侧缺失时为空列表
     * @param rightSpans 修改后行内的片段；该侧缺失时为空列表
     */
    public record DiffRow(Kind kind, int leftNo, int rightNo,
                          List<Span> leftSpans, List<Span> rightSpans) {

        public boolean hasLeft() {
            return leftNo > 0;
        }

        public boolean hasRight() {
            return rightNo > 0;
        }

        /** 该侧整行文本（拼自片段）。 */
        public String leftText() {
            return join(leftSpans);
        }

        public String rightText() {
            return join(rightSpans);
        }
    }

    /**
     * 连续的一组差异 —— 底部状态条里的「差异 N 处」的一处、补丁里的一个 hunk。
     *
     * @param index     组序号，1 起
     * @param rowFrom   起始合并行下标（含）
     * @param rowTo     结束合并行下标（含）
     * @param rowCount  组内合并行数
     * @param location  位置文案，如「第 3–6 行」「左 9」「右 8–9」
     * @param summary   摘要，如「port · timeout · retry…」
     */
    public record Group(int index, Kind kind, int rowFrom, int rowTo,
                        int leftFrom, int leftTo, int rightFrom, int rightTo,
                        int rowCount, String location, String summary) {
    }

    /**
     * 对比结果。
     *
     * @param addRows          新增行数
     * @param delRows          删除行数
     * @param modRows          修改行数
     * @param leftLines        原文行数
     * @param rightLines       修改后行数
     * @param degraded         文本过大，已退化为逐位比对
     */
    public record Result(List<DiffRow> rows, List<Group> groups,
                         int addRows, int delRows, int modRows,
                         int leftLines, int rightLines, boolean degraded) {

        public boolean equal() {
            return groups.isEmpty();
        }
    }

    // ==================== 常量 ====================

    /** 行级 LCS 的规模上限（行数积）。超过则退化为逐位比对。 */
    static final long DP_CELL_LIMIT = 4_000_000L;

    /** 行内 token 级 LCS 的规模上限。超过则整行标为变化。 */
    static final long SPAN_CELL_LIMIT = 1_000_000L;

    /** 判定「这两行是同一行的前后版本」的字符相似度门槛。 */
    static final double SIMILARITY_THRESHOLD = 0.5;

    /** 单调配对 DP 的规模上限（两侧待配行数之积）。超出则不配对，整块按增删处理。 */
    static final long MATCH_CELL_LIMIT = 40_000L;

    /** 摘要里最多列几个标识符。 */
    private static final int SUMMARY_KEYS = 3;

    /** 摘要最大长度，超出截断。 */
    private static final int SUMMARY_MAX = 46;

    /** 行内 token：连续字母数字下划线 / 连续空白 / 其余单个字符。 */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_]+|\\s+|.");

    /** 行首标识符：用于生成摘要（第一个「词」）。 */
    private static final Pattern IDENT = Pattern.compile("[A-Za-z0-9_\\-]+|[^\\s]+");

    private static final List<Span> NO_SPANS = List.of();

    private DiffOps() {
    }

    // ==================== 对外 API ====================

    /**
     * 对比两份文本。
     *
     * @param leftText  原文，可为 null（按空文本处理）
     * @param rightText 修改后的文本，可为 null
     * @param options   比对选项，null 取 {@link Options#defaults()}
     */
    public static Result diff(String leftText, String rightText, Options options) {
        Options opt = options == null ? Options.defaults() : options;
        List<String> a = splitLines(leftText);
        List<String> b = splitLines(rightText);

        String[] ka = new String[a.size()];
        String[] kb = new String[b.size()];
        for (int i = 0; i < a.size(); i++) {
            ka[i] = key(a.get(i), opt);
        }
        for (int i = 0; i < b.size(); i++) {
            kb[i] = key(b.get(i), opt);
        }

        List<Op> ops;
        boolean degraded = false;
        if ((long) ka.length * kb.length > DP_CELL_LIMIT) {
            ops = naiveOps(ka, kb);
            degraded = true;
        } else {
            ops = lcsOps(ka, kb);
        }

        List<DiffRow> rows = buildRows(ops, a, b, ka, kb, opt);

        int add = 0;
        int del = 0;
        int mod = 0;
        int leftLines = 0;
        int rightLines = 0;
        for (DiffRow row : rows) {
            switch (row.kind()) {
                case ADD -> add++;
                case DEL -> del++;
                case MOD -> mod++;
                default -> {
                }
            }
            if (row.hasLeft()) {
                leftLines++;
            }
            if (row.hasRight()) {
                rightLines++;
            }
        }

        List<Group> groups = buildGroups(rows);
        return new Result(Collections.unmodifiableList(rows), Collections.unmodifiableList(groups),
                add, del, mod, leftLines, rightLines, degraded);
    }

    /**
     * 按行切分文本。
     *
     * <p>两处细节都是踩过的坑：① UTF-8 文件的 BOM 若不剥掉，第一行会渲染成 {@code □}；
     * ② 结尾的换行符不产生「第 N+1 行」—— 否则 {@code "a\n"} 与 {@code "a"} 会被判为有差异。
     */
    public static List<String> splitLines(String text) {
        String s = text == null ? "" : text;
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
            s = s.substring(1);
        }
        if (s.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> lines = new ArrayList<>(List.of(s.split("\r\n|\n|\r", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    /**
     * 读文本文件（剥 UTF-8 BOM）。
     *
     * <p>数据访问很薄，按项目约定并入业务层而不单列 {@code DiffIo}。
     */
    public static String readTextFile(File file) throws IOException {
        if (file == null) {
            return "";
        }
        String s = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        return s.isEmpty() || s.charAt(0) != '\uFEFF' ? s : s.substring(1);
    }

    /**
     * 导出标准补丁文本 —— 可直接喂给 {@code patch}。
     *
     * <p>每个差异组一个 hunk，采用「整块替换」形式（删左块、加右块），
     * 不带上下文行：并排视图本身已经提供了上下文，补丁只负责携带改动。
     */
    public static String patchText(Result result) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- 原文\n+++ 修改后\n");
        if (result == null) {
            return sb.toString();
        }
        for (Group g : result.groups()) {
            int lFrom = g.leftFrom() == 0 ? 0 : g.leftFrom();
            int lCount = g.leftTo() == 0 ? 0 : g.leftTo() - g.leftFrom() + 1;
            int rFrom = g.rightFrom() == 0 ? 0 : g.rightFrom();
            int rCount = g.rightTo() == 0 ? 0 : g.rightTo() - g.rightFrom() + 1;
            sb.append("@@ -").append(lFrom == 0 ? 0 : lFrom).append(',').append(lCount)
                    .append(" +").append(rFrom == 0 ? 0 : rFrom).append(',').append(rCount)
                    .append(" @@\n");
            for (int i = g.rowFrom(); i <= g.rowTo(); i++) {
                DiffRow row = result.rows().get(i);
                if (row.hasLeft()) {
                    sb.append('-').append(row.leftText()).append('\n');
                }
                if (row.hasRight()) {
                    sb.append('+').append(row.rightText()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** 行首标识符（摘要用），取不到则退化为整行。 */
    static String identifier(String line) {
        String s = line == null ? "" : line.strip();
        if (s.isEmpty()) {
            return "";
        }
        Matcher m = IDENT.matcher(s);
        return m.find() ? m.group() : s;
    }

    // ==================== 归一化 ====================

    private static String key(String line, Options opt) {
        String s = line == null ? "" : line;
        if (opt.ignoreWhitespace()) {
            s = s.replaceAll("\\s+", "");
        }
        if (opt.ignoreCase()) {
            s = s.toLowerCase(Locale.ROOT);
        }
        return s;
    }

    private static String join(List<Span> spans) {
        if (spans == null || spans.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Span s : spans) {
            sb.append(s.text());
        }
        return sb.toString();
    }

    private static List<Span> plain(String line) {
        return List.of(new Span(line == null ? "" : line, false));
    }

    // ==================== 行级 diff ====================

    /** 行级操作。{@code no} 为行下标；不适用的一侧为 -1。 */
    private record Op(Kind kind, int leftIndex, int rightIndex) {
    }

    /**
     * LCS 动态规划求最长公共子序列，再回溯成操作序列。
     *
     * <p>规模由调用方用 {@link #DP_CELL_LIMIT} 兜住，因此可以直接开全表：
     * 表项是 LCS 长度，最大为两行数的较小者（受同一个上限约束），{@code short} 足够。
     */
    private static List<Op> lcsOps(String[] a, String[] b) {
        int n = a.length;
        int m = b.length;
        List<Op> ops = new ArrayList<>(n + m);
        if (n == 0) {
            for (int j = 0; j < m; j++) {
                ops.add(new Op(Kind.ADD, -1, j));
            }
            return ops;
        }
        if (m == 0) {
            for (int i = 0; i < n; i++) {
                ops.add(new Op(Kind.DEL, i, -1));
            }
            return ops;
        }

        int w = m + 1;
        short[] dp = new short[(n + 1) * w];
        for (int i = n - 1; i >= 0; i--) {
            int row = i * w;
            int next = (i + 1) * w;
            for (int j = m - 1; j >= 0; j--) {
                dp[row + j] = a[i].equals(b[j])
                        ? (short) (dp[next + j + 1] + 1)
                        : (short) Math.max(dp[next + j], dp[row + j + 1]);
            }
        }

        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a[i].equals(b[j])) {
                ops.add(new Op(Kind.SAME, i, j));
                i++;
                j++;
            } else if (dp[(i + 1) * w + j] >= dp[i * w + j + 1]) {
                ops.add(new Op(Kind.DEL, i, -1));
                i++;
            } else {
                ops.add(new Op(Kind.ADD, -1, j));
                j++;
            }
        }
        while (i < n) {
            ops.add(new Op(Kind.DEL, i++, -1));
        }
        while (j < m) {
            ops.add(new Op(Kind.ADD, -1, j++));
        }
        return ops;
    }

    /**
     * 大文本降级：不做对齐，按行号逐位比较。
     *
     * <p>对齐位置上的两行<b>必须真的比一次</b>：这一路不走 LCS，「行号相同」并不等于
     * 「内容相同」。早期实现在这里无条件发 {@code SAME}，于是行数相同、内容不同的两份
     * 文本会被判成完全一致（实测 2500 行每行都不同 → 报「差异 0 处」）。
     * 这是最坏的一类错：结论是错的，而且看起来像是「没问题」。
     *
     * <p>不同的位置按「删一行 + 加一行」下发，交给 {@code appendChanges} 的单调配对去合成
     * {@code MOD} —— 既能复用「相似度达标才算修改」的判定，也把「忽略空行」那一层
     * 继续留在同一处处理，不在降级路径上另开一套规则。
     *
     * <p>结论会在界面里明写「已简化为逐行对齐」—— 静默降级比降级本身更糟。
     */
    private static List<Op> naiveOps(String[] ka, String[] kb) {
        int n = ka.length;
        int m = kb.length;
        List<Op> ops = new ArrayList<>(n + m);
        int common = Math.min(n, m);
        for (int i = 0; i < common; i++) {
            // 归一化的 key 已带上「忽略空白 / 忽略大小写」，这里比的就是「算不算相同」
            if (ka[i].equals(kb[i])) {
                ops.add(new Op(Kind.SAME, i, i));
            } else {
                ops.add(new Op(Kind.DEL, i, -1));
                ops.add(new Op(Kind.ADD, -1, i));
            }
        }
        for (int i = common; i < n; i++) {
            ops.add(new Op(Kind.DEL, i, -1));
        }
        for (int j = common; j < m; j++) {
            ops.add(new Op(Kind.ADD, -1, j));
        }
        return ops;
    }

    private static List<DiffRow> buildRows(List<Op> ops, List<String> a, List<String> b,
                                           String[] ka, String[] kb, Options opt) {
        List<DiffRow> rows = new ArrayList<>(ops.size());
        int i = 0;
        while (i < ops.size()) {
            Op op = ops.get(i);
            if (op.kind() == Kind.SAME) {
                rows.add(new DiffRow(Kind.SAME, op.leftIndex() + 1, op.rightIndex() + 1,
                        plain(a.get(op.leftIndex())), plain(b.get(op.rightIndex()))));
                i++;
            } else {
                int from = i;
                while (i < ops.size() && ops.get(i).kind() != Kind.SAME) {
                    i++;
                }
                appendChanges(ops.subList(from, i), a, b, ka, kb, opt, rows);
            }
        }
        return rows;
    }

    /**
     * 处理一段连续的非 SAME 操作：在这段里把「只在左侧」与「只在右侧」的行配对，
     * 相似度达标的合成一行「修改」。
     *
     * <p><b>为什么不能按位置逐对比较</b>：左 9 {@code db_pool = 10}（删除）、
     * 左 10 {@code worker = 4}、右 11 {@code worker = 8} 这种错位块里，
     * 逐对比较会拿 {@code db_pool} 去配 {@code worker}（不相似 → 拆成一删一增），
     * 而真正该配对的那两行反而被剩成了「删一行 + 加一行」——
     * 这正是本功能要消灭的东西。求一遍<b>单调最优配对</b>，
     * 让「跨越一个删除行也要认出来的修改」能被认出来。
     *
     * <p>配对是单调的（不能交叉），因此按顺序归并两侧时不会出现行错位。
     */
    private static void appendChanges(List<Op> seg, List<String> a, List<String> b,
                                      String[] ka, String[] kb, Options opt, List<DiffRow> rows) {
        List<Integer> dels = new ArrayList<>();
        List<Integer> adds = new ArrayList<>();
        for (Op op : seg) {
            if (op.kind() == Kind.DEL) {
                dels.add(op.leftIndex());
            } else {
                adds.add(op.rightIndex());
            }
        }

        int nd = dels.size();
        int na = adds.size();
        int[] pairOfDel = new int[nd];
        int[] pairOfAdd = new int[na];
        Arrays.fill(pairOfDel, -1);
        Arrays.fill(pairOfAdd, -1);
        if (nd > 0 && na > 0 && (long) nd * na <= MATCH_CELL_LIMIT) {
            matchPairs(ka, kb, dels, adds, pairOfDel, pairOfAdd);
        }

        int i = 0;
        int j = 0;
        while (i < nd || j < na) {
            if (i < nd && j < na && pairOfDel[i] == j) {
                int di = dels.get(i);
                int aj = adds.get(j);
                boolean blankLeft = opt.ignoreBlank() && a.get(di).isBlank();
                boolean blankRight = opt.ignoreBlank() && b.get(aj).isBlank();
                if (blankLeft || blankRight) {
                    rows.add(new DiffRow(Kind.SAME,
                            blankLeft ? 0 : di + 1, blankRight ? 0 : aj + 1,
                            blankLeft ? NO_SPANS : plain(a.get(di)),
                            blankRight ? NO_SPANS : plain(b.get(aj))));
                } else {
                    rows.add(modRow(di, aj, a, b, opt));
                }
                i++;
                j++;
            } else if (i < nd && pairOfDel[i] < 0) {
                int di = dels.get(i);
                if (opt.ignoreBlank() && a.get(di).isBlank()) {
                    // 被忽略的空行不报成增删，但保留在原位（行号仍然连续可读）
                    rows.add(new DiffRow(Kind.SAME, di + 1, 0, plain(a.get(di)), NO_SPANS));
                } else {
                    rows.add(delRow(di, a));
                }
                i++;
            } else if (j < na && pairOfAdd[j] < 0) {
                int aj = adds.get(j);
                if (opt.ignoreBlank() && b.get(aj).isBlank()) {
                    rows.add(new DiffRow(Kind.SAME, 0, aj + 1, NO_SPANS, plain(b.get(aj))));
                } else {
                    rows.add(addRow(aj, b));
                }
                j++;
            } else if (i < nd) {
                rows.add(delRow(dels.get(i), a));
                i++;
            } else {
                rows.add(addRow(adds.get(j), b));
                j++;
            }
        }
    }

    /**
     * 单调最优配对：在「只在左侧」与「只在右侧」两块之间求一对一对应，
     * 使配对上的相似度总和最大（不达 {@link #SIMILARITY_THRESHOLD} 的一律不配）。
     *
     * <p>规模由 {@link #MATCH_CELL_LIMIT} 兜住；超出时不做配对，
     * 整块按「全部删除 + 全部新增」呈现 —— 整段替换本来就是那个意思。
     */
    private static void matchPairs(String[] ka, String[] kb, List<Integer> dels, List<Integer> adds,
                                   int[] pairOfDel, int[] pairOfAdd) {
        int nd = dels.size();
        int na = adds.size();
        double[][] sim = new double[nd][na];
        for (int i = 0; i < nd; i++) {
            for (int j = 0; j < na; j++) {
                sim[i][j] = similarity(ka[dels.get(i)], kb[adds.get(j)]);
            }
        }

        double[][] best = new double[nd + 1][na + 1];
        for (int i = 1; i <= nd; i++) {
            for (int j = 1; j <= na; j++) {
                double value = Math.max(best[i - 1][j], best[i][j - 1]);
                if (sim[i - 1][j - 1] >= SIMILARITY_THRESHOLD) {
                    value = Math.max(value, best[i - 1][j - 1] + sim[i - 1][j - 1]);
                }
                best[i][j] = value;
            }
        }

        int i = nd;
        int j = na;
        while (i > 0 && j > 0) {
            if (sim[i - 1][j - 1] >= SIMILARITY_THRESHOLD
                    && nearly(best[i][j], best[i - 1][j - 1] + sim[i - 1][j - 1])) {
                pairOfDel[i - 1] = j - 1;
                pairOfAdd[j - 1] = i - 1;
                i--;
                j--;
            } else if (nearly(best[i][j], best[i - 1][j])) {
                i--;
            } else {
                j--;
            }
        }
    }

    private static boolean nearly(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

    private static DiffRow delRow(int index, List<String> a) {
        return new DiffRow(Kind.DEL, index + 1, 0, plain(a.get(index)), NO_SPANS);
    }

    private static DiffRow addRow(int index, List<String> b) {
        return new DiffRow(Kind.ADD, 0, index + 1, NO_SPANS, plain(b.get(index)));
    }

    private static DiffRow modRow(int di, int aj, List<String> a, List<String> b, Options opt) {
        List<Span> left = new ArrayList<>();
        List<Span> right = new ArrayList<>();
        inlineSpans(a.get(di), b.get(aj), opt, left, right);
        return new DiffRow(Kind.MOD, di + 1, aj + 1, List.copyOf(left), List.copyOf(right));
    }

    // ==================== 行内 diff ====================

    /**
     * 词级 / 字符级比对，产出两侧的片段列表。
     *
     * <p>粒度选「按行」时不做行内比对 —— 修改行整行暖底 + 双色左缘条已经说明了情况，
     * 再接一层词级标记只会让这一行变吵。
     */
    private static void inlineSpans(String leftText, String rightText, Options opt,
                                    List<Span> outLeft, List<Span> outRight) {
        if (opt.granularity() == Granularity.LINE) {
            outLeft.add(new Span(leftText, false));
            outRight.add(new Span(rightText, false));
            return;
        }

        List<String> tl = tokenize(leftText, opt.granularity());
        List<String> tr = tokenize(rightText, opt.granularity());
        if (tl.isEmpty() || tr.isEmpty() || (long) tl.size() * tr.size() > SPAN_CELL_LIMIT) {
            // 行太长（例如压缩过的 JSON）：整行标成变化，不做逐 token 比对
            outLeft.add(new Span(leftText, true));
            outRight.add(new Span(rightText, true));
            return;
        }

        String[] kl = new String[tl.size()];
        String[] kr = new String[tr.size()];
        for (int i = 0; i < tl.size(); i++) {
            kl[i] = key(tl.get(i), opt);
        }
        for (int i = 0; i < tr.size(); i++) {
            kr[i] = key(tr.get(i), opt);
        }

        boolean[] matchedLeft = new boolean[tl.size()];
        boolean[] matchedRight = new boolean[tr.size()];
        lcsFlags(kl, kr, matchedLeft, matchedRight);

        merge(tl, matchedLeft, outLeft);
        merge(tr, matchedRight, outRight);
    }

    /** token 级 LCS，只标记「是否被匹配上」，不需要回溯出具体配对。 */
    private static void lcsFlags(String[] a, String[] b, boolean[] matchedA, boolean[] matchedB) {
        int n = a.length;
        int m = b.length;
        int w = m + 1;
        int[] dp = new int[(n + 1) * w];
        for (int i = n - 1; i >= 0; i--) {
            int row = i * w;
            int next = (i + 1) * w;
            for (int j = m - 1; j >= 0; j--) {
                dp[row + j] = a[i].equals(b[j])
                        ? dp[next + j + 1] + 1
                        : Math.max(dp[next + j], dp[row + j + 1]);
            }
        }
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a[i].equals(b[j])) {
                matchedA[i] = true;
                matchedB[j] = true;
                i++;
                j++;
            } else if (dp[(i + 1) * w + j] >= dp[i * w + j + 1]) {
                i++;
            } else {
                j++;
            }
        }
    }

    /** 把相邻的同状态 token 合并成片段（否则按字符粒度会产出上百个节点）。 */
    private static void merge(List<String> tokens, boolean[] matched, List<Span> out) {
        StringBuilder sb = new StringBuilder();
        boolean current = false;
        boolean started = false;
        for (int i = 0; i < tokens.size(); i++) {
            boolean changed = !matched[i];
            if (started && changed != current) {
                out.add(new Span(sb.toString(), current));
                sb.setLength(0);
            }
            sb.append(tokens.get(i));
            current = changed;
            started = true;
        }
        if (started) {
            out.add(new Span(sb.toString(), current));
        }
    }

    private static List<String> tokenize(String text, Granularity g) {
        List<String> tokens = new ArrayList<>();
        String s = text == null ? "" : text;
        if (g == Granularity.CHAR) {
            for (int i = 0; i < s.length(); i++) {
                tokens.add(String.valueOf(s.charAt(i)));
            }
            return tokens;
        }
        Matcher m = TOKEN.matcher(s);
        while (m.find()) {
            tokens.add(m.group());
        }
        return tokens;
    }

    /** 字符级相似度（LCS 长度 / 较长者）。用于判断两行是不是「同一行的前后版本」。 */
    static double similarity(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        int n = a.length();
        int m = b.length();
        if ((long) n * m > SPAN_CELL_LIMIT) {
            return 0;
        }
        short[] dp = new short[(n + 1) * (m + 1)];
        int w = m + 1;
        for (int i = n - 1; i >= 0; i--) {
            int row = i * w;
            int next = (i + 1) * w;
            for (int j = m - 1; j >= 0; j--) {
                dp[row + j] = a.charAt(i) == b.charAt(j)
                        ? (short) (dp[next + j + 1] + 1)
                        : (short) Math.max(dp[next + j], dp[row + j + 1]);
            }
        }
        return dp[0] / (double) Math.max(n, m);
    }

    // ==================== 分组 ====================

    private static List<Group> buildGroups(List<DiffRow> rows) {
        List<Group> groups = new ArrayList<>();
        int i = 0;
        while (i < rows.size()) {
            if (rows.get(i).kind() == Kind.SAME) {
                i++;
                continue;
            }
            int from = i;
            while (i < rows.size() && rows.get(i).kind() != Kind.SAME) {
                i++;
            }
            groups.add(makeGroup(groups.size() + 1, rows.subList(from, i), from, i - 1));
        }
        return groups;
    }

    private static Group makeGroup(int index, List<DiffRow> slice, int rowFrom, int rowTo) {
        int add = 0;
        int del = 0;
        int mod = 0;
        int lf = 0;
        int lt = 0;
        int rf = 0;
        int rt = 0;
        for (DiffRow row : slice) {
            switch (row.kind()) {
                case ADD -> add++;
                case DEL -> del++;
                case MOD -> mod++;
                default -> {
                }
            }
            if (row.hasLeft()) {
                if (lf == 0) {
                    lf = row.leftNo();
                }
                lt = row.leftNo();
            }
            if (row.hasRight()) {
                if (rf == 0) {
                    rf = row.rightNo();
                }
                rt = row.rightNo();
            }
        }
        // 「修改」优先：一个组里只要有一行是同一行的前后版本，这个组的性质就是改写而非增删
        Kind kind = mod > 0 ? Kind.MOD : (add > 0 && del > 0 ? Kind.MOD : (add > 0 ? Kind.ADD : Kind.DEL));
        return new Group(index, kind, rowFrom, rowTo, lf, lt, rf, rt,
                slice.size(), location(lf, lt, rf, rt), summary(slice));
    }

    /** 位置文案。左右行号相同就只说一次，不同则两侧都写清楚。 */
    static String location(int lf, int lt, int rf, int rt) {
        if (lf > 0 && rf > 0) {
            if (lf == rf && lt == rt) {
                return "第 " + (lf == lt ? String.valueOf(lf) : lf + "\u2013" + lt) + " 行";
            }
            return "左 " + range(lf, lt) + " · 右 " + range(rf, rt);
        }
        if (lf > 0) {
            return "左 " + range(lf, lt);
        }
        if (rf > 0) {
            return "右 " + range(rf, rt);
        }
        return "";
    }

    private static String range(int from, int to) {
        return from == to ? String.valueOf(from) : from + "\u2013" + to;
    }

    /**
     * 摘要。单行直接给「旧 → 新」，多行给各行首标识符 ——
     * 摘要只占一行的宽度，给得太细会读不完，给整行又会全被截断。
     */
    static String summary(List<DiffRow> slice) {
        if (slice.size() == 1) {
            DiffRow row = slice.get(0);
            if (row.kind() == Kind.MOD) {
                String left = identifier(row.leftText());
                String right = firstChanged(row.rightSpans());
                if (right.isEmpty()) {
                    right = identifier(row.rightText());
                }
                String text = (left.isEmpty() ? row.leftText().strip() : left) + " \u2192 " + right;
                return clip(text);
            }
            String text = row.kind() == Kind.ADD ? row.rightText() : row.leftText();
            return clip(text.strip());
        }

        Set<String> keys = new LinkedHashSet<>();
        for (DiffRow row : slice) {
            String text = row.kind() == Kind.ADD ? row.rightText() : row.leftText();
            if (text.isBlank() && row.kind() == Kind.ADD) {
                text = row.rightText();
            }
            String id = identifier(text);
            if (!id.isEmpty()) {
                keys.add(id);
            }
            if (keys.size() > SUMMARY_KEYS) {
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String k : keys) {
            if (count == SUMMARY_KEYS) {
                sb.append(" \u00b7 \u2026");
                break;
            }
            if (count > 0) {
                sb.append(" \u00b7 ");
            }
            sb.append(k);
            count++;
        }
        return clip(sb.toString());
    }

    private static String firstChanged(List<Span> spans) {
        for (Span s : spans) {
            if (s.changed() && !s.text().isBlank()) {
                return s.text().strip();
            }
        }
        return "";
    }

    private static String clip(String text) {
        String s = text == null ? "" : text;
        return s.length() <= SUMMARY_MAX ? s : s.substring(0, SUMMARY_MAX) + "\u2026";
    }
}
