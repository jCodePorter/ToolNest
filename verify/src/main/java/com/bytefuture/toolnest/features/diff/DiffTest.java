package com.bytefuture.toolnest.features.diff;

import java.util.List;

/**
 * 文本对比的纯逻辑验证（无界面）。
 *
 * <p>验的是 {@link DiffOps} 的四件事：
 * <ol>
 *   <li><b>三类差异的归类</b> —— {@code port = 8080 → 9090} 必须判成「修改」，
 *       而不是拆成一删一增。这是整个功能成立的前提。
 *       反向验证：把 {@code SIMILARITY_THRESHOLD} 调到 2.0，本组断言立刻变红。</li>
 *   <li><b>行对齐</b> —— 合并行数、左右行数三者必须自洽
 *       （左栏行数 = 原文行数，右栏行数 = 修改后行数，缺失的一侧画占位行）。</li>
 *   <li><b>行内区间</b> —— 词级 / 字符级给出的变化片段是不是那一段。</li>
 *   <li><b>归一化</b> —— 三种忽略规则、BOM、尾换行：都只影响「算不算相同」，
 *       不影响显示用的原文。</li>
 * </ol>
 */
public class DiffTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name + "  -> " + detail);
        }
    }

    private static final String LEFT = """
            # 服务端配置
            host = 127.0.0.1
            port = 8080
            timeout = 30
            retry = 3
            log_level = info
            cache = false
            max_conn = 100
            db_pool = 10
            worker = 4
            enable_gzip = true
            db_url = jdbc:mysql://127.0.0.1:3306/app
            pool_min = 2
            pool_max = 20
            idle_timeout = 600
            charset = utf8mb4
            ssl_mode = disable
            read_only = false
            slow_query_ms = 500
            slow_log = /var/log/slow.log
            max_packet = 16M
            net_read_timeout = 30
            """;

    private static final String RIGHT = """
            # 服务端配置
            host = 127.0.0.1
            port = 9090
            timeout = 60
            retry = 5
            log_level = debug
            cache = false
            tls_enabled = true
            cert_path = /etc/ssl/app.pem
            max_conn = 100
            worker = 8
            enable_gzip = true
            db_url = jdbc:mysql://127.0.0.1:3306/app
            pool_min = 2
            pool_max = 20
            idle_timeout = 600
            charset = utf8mb4
            ssl_mode = require
            read_only = false
            slow_query_ms = 500
            slow_log = /var/log/slow.log
            max_packet = 16M
            net_read_timeout = 30
            """;

    public static void main(String[] args) {
        System.out.println("=== 文本对比 纯逻辑验证 ===");

        DiffOps.Options word = new DiffOps.Options(DiffOps.Granularity.WORD, false, false, false);
        DiffOps.Result r = DiffOps.diff(LEFT, RIGHT, word);

        // ---------- 1. 分类与统计 ----------
        check("新增 2 行", r.addRows() == 2, "实际 " + r.addRows());
        check("删除 1 行", r.delRows() == 1, "实际 " + r.delRows());
        check("修改 6 行（4+1+1，而不是被拆成 6 删 + 6 增）",
                r.modRows() == 6, "实际 " + r.modRows());
        check("差异组数 4（4 行连续改写 / 2 行新增 / 删一行 + 改一行 / 单行改写）",
                r.groups().size() == 4, "实际 " + r.groups().size());
        check("不相等", !r.equal(), "equal() 返回了 true");

        // ---------- 2. 行对齐 ----------
        check("原文 22 行", r.leftLines() == 22, "实际 " + r.leftLines());
        check("修改后 23 行", r.rightLines() == 23, "实际 " + r.rightLines());
        check("合并行数 = 22 + 2 = 24（左栏每一行 + 右侧新增行）",
                r.rows().size() == 24, "实际 " + r.rows().size());

        int hasLeft = 0;
        int hasRight = 0;
        for (DiffOps.DiffRow row : r.rows()) {
            hasLeft += row.hasLeft() ? 1 : 0;
            hasRight += row.hasRight() ? 1 : 0;
        }
        check("左栏行数 == 原文行数（22）", hasLeft == 22, "实际 " + hasLeft);
        check("右栏行数 == 修改后行数（23）", hasRight == 23, "实际 " + hasRight);
        check("左右行数严格对齐（同一屏横向扫视对得上）",
                hasLeft == r.leftLines() && hasRight == r.rightLines(), hasLeft + " / " + hasRight);

        boolean modBothSides = true;
        for (DiffOps.DiffRow row : r.rows()) {
            if (row.kind() == DiffOps.Kind.MOD && !(row.hasLeft() && row.hasRight())) {
                modBothSides = false;
                break;
            }
        }
        check("「修改」行两侧都有行号（否则就不是同一行的前后版本）",
                modBothSides, "存在只有一侧的 MOD 行");

        // ---------- 3. 组的类型 / 位置 / 摘要 ----------
        List<DiffOps.Group> groups = r.groups();
        check("#1 修改 · 第 3–6 行 · 4 行",
                groups.get(0).kind() == DiffOps.Kind.MOD
                        && "第 3\u20136 行".equals(groups.get(0).location())
                        && groups.get(0).rowCount() == 4,
                groups.get(0).kind() + " " + groups.get(0).location() + " " + groups.get(0).rowCount());
        check("#1 摘要取各行首标识符：port · timeout · retry · …",
                "port \u00b7 timeout \u00b7 retry \u00b7 \u2026".equals(groups.get(0).summary()),
                groups.get(0).summary());
        check("#2 新增 · 右 8–9 · 摘要 tls_enabled · cert_path",
                groups.get(1).kind() == DiffOps.Kind.ADD
                        && "右 8\u20139".equals(groups.get(1).location())
                        && "tls_enabled \u00b7 cert_path".equals(groups.get(1).summary()),
                groups.get(1).kind() + " " + groups.get(1).location() + " " + groups.get(1).summary());
        check("#3 是「删一行 + 改一行」的混合组（db_pool 删除 · worker 修改）",
                groups.get(2).kind() == DiffOps.Kind.MOD
                        && "左 9\u201310 \u00b7 右 11".equals(groups.get(2).location()),
                groups.get(2).kind() + " " + groups.get(2).location());
        check("#3 摘要同时列出两组行首标识符：db_pool · worker",
                "db_pool \u00b7 worker".equals(groups.get(2).summary()),
                groups.get(2).summary());
        check("#4 单行修改的摘要为「旧 → 新」：ssl_mode → require",
                groups.get(3).kind() == DiffOps.Kind.MOD
                        && "左 17 \u00b7 右 18".equals(groups.get(3).location())
                        && "ssl_mode \u2192 require".equals(groups.get(3).summary()),
                groups.get(3).kind() + " " + groups.get(3).location() + " " + groups.get(3).summary());

        // ---------- 3b. 错位的修改必须被认出来（单调配对的价值） ----------
        check("跨越一个删除行的修改仍然认得出：worker 4 → 8 是一行「修改」，不是一删一增",
                hasMod(r, 10, 11), "未找到左 10 / 右 11 的修改行");
        check("被跨过的那一行仍然是删除：db_pool = 10",
                hasDel(r, 9), "未找到左 9 的删除行");
        check("最长的一组 = 4 行（第 3–6 行那组，其余组都更短）",
                groups.stream().mapToInt(DiffOps.Group::rowCount).max().orElse(0) == 4,
                "实际 " + groups.stream().mapToInt(DiffOps.Group::rowCount).max().orElse(0));

        // ---------- 4. 行内区间 ----------
        check("port 行：左侧变化片段是 8080",
                "8080".equals(changedText(r, 3, true)), changedText(r, 3, true));
        check("port 行：右侧变化片段是 9090",
                "9090".equals(changedText(r, 3, false)), changedText(r, 3, false));
        check("ssl_mode 行：左 disable / 右 require",
                "disable".equals(changedText(r, 17, true))
                        && "require".equals(changedText(r, 17, false)),
                changedText(r, 17, true) + " / " + changedText(r, 17, false));
        check("未变化的行没有任何词级标记",
                changedText(r, 2, true).isEmpty() && changedText(r, 2, false).isEmpty(),
                "第 2 行出现了标记：" + changedText(r, 2, true));

        DiffOps.Result byLine = DiffOps.diff(LEFT, RIGHT,
                new DiffOps.Options(DiffOps.Granularity.LINE, false, false, false));
        check("粒度「按行」时行内不着色（只做行级高亮）",
                changedText(byLine, 3, true).isEmpty() && changedText(byLine, 3, false).isEmpty(),
                changedText(byLine, 3, true) + " / " + changedText(byLine, 3, false));

        DiffOps.Result byChar = DiffOps.diff("ssl_mode = disable", "ssl_mode = require",
                new DiffOps.Options(DiffOps.Granularity.CHAR, false, false, false));
        // 按字符比对时，两侧散落的公共字符（disable / require 里的 i、e）会被认作未变，
        // 因此变化片段是「若干段」而不是一个完整词 —— 这是字符级 diff 的固有行为，
        // 断言只验它确实逐字符标出了变化，且两侧互不相同。
        check("粒度「按字符」时两侧都逐字符标出了变化",
                !changedText(byChar, 1, true).isEmpty()
                        && !changedText(byChar, 1, false).isEmpty()
                        && !changedText(byChar, 1, true).equals(changedText(byChar, 1, false)),
                changedText(byChar, 1, true) + " / " + changedText(byChar, 1, false));
        check("粒度只影响行内标记，不影响行级判定（仍是 1 组 1 行修改）",
                byChar.modRows() == 1 && byChar.groups().size() == 1,
                byChar.modRows() + " / " + byChar.groups().size());

        // ---------- 5. 相似度与「修改」的判定 ----------
        check("port = 8080 与 port = 9090 的相似度达到修改门槛",
                DiffOps.similarity("port = 8080", "port = 9090") >= DiffOps.SIMILARITY_THRESHOLD,
                String.valueOf(DiffOps.similarity("port = 8080", "port = 9090")));
        check("毫不相干的两行不判成修改",
                DiffOps.similarity("aaa", "zzzzz") < DiffOps.SIMILARITY_THRESHOLD,
                String.valueOf(DiffOps.similarity("aaa", "zzzzz")));

        DiffOps.Result apart = DiffOps.diff("aaa\nbbb", "zzzzz\nbbb", word);
        check("不相似的一对被拆成一删一增（两行，而不是一行修改）",
                apart.delRows() == 1 && apart.addRows() == 1 && apart.modRows() == 0,
                apart.delRows() + " / " + apart.addRows() + " / " + apart.modRows());

        // ---------- 6. 忽略规则（归一化后再比对，显示仍用原文） ----------
        check("忽略大小写后 A = 1 与 a = 1 一致",
                DiffOps.diff("A = 1", "a = 1",
                        new DiffOps.Options(DiffOps.Granularity.WORD, false, true, false)).equal(),
                "判出了差异");
        check("不忽略大小写时它们是一处修改",
                DiffOps.diff("A = 1", "a = 1", word).groups().size() == 1,
                "组数 " + DiffOps.diff("A = 1", "a = 1", word).groups().size());
        check("忽略空白后 a=1 与 a = 1 一致",
                DiffOps.diff("a=1", "a = 1",
                        new DiffOps.Options(DiffOps.Granularity.WORD, true, false, false)).equal(),
                "判出了差异");
        check("忽略空行后 x + 两个空行 + y 与 x + 一个空行 + y 一致",
                DiffOps.diff("x\n\n\ny", "x\ny",
                        new DiffOps.Options(DiffOps.Granularity.WORD, false, false, true)).equal(),
                "判出了差异");
        check("不忽略空行时同样的输入有差异",
                !DiffOps.diff("x\n\n\ny", "x\ny", word).equal(), "判成了一致");
        check("忽略规则文案写明当前规则",
                "忽略空白、忽略大小写".equals(
                        new DiffOps.Options(DiffOps.Granularity.WORD, true, true, false).describeIgnores()),
                new DiffOps.Options(DiffOps.Granularity.WORD, true, true, false).describeIgnores());

        // ---------- 7. 文本边界 ----------
        check("UTF-8 BOM 被剥掉，不影响首行比对",
                DiffOps.diff("\uFEFFa\nb", "a\nb", word).equal(), "首行被判成不同");
        check("结尾换行不产生第 N+1 行",
                DiffOps.diff("a\n", "a", word).equal(), "被判成了差异");
        check("两侧都空时无差异且行数为 0",
                DiffOps.diff("", "", word).equal() && DiffOps.diff("", "", word).leftLines() == 0,
                "leftLines=" + DiffOps.diff("", "", word).leftLines());
        check("空行参与普通比对（不算被忽略）",
                DiffOps.diff("a\n\nb", "a\nb", word).groups().size() == 1,
                "组数 " + DiffOps.diff("a\n\nb", "a\nb", word).groups().size());

        DiffOps.Result onlyLeft = DiffOps.diff("a\nb", "", word);
        check("右侧为空时全部判为删除（2 行、1 组）",
                onlyLeft.delRows() == 2 && onlyLeft.groups().size() == 1,
                onlyLeft.delRows() + " / " + onlyLeft.groups().size());
        DiffOps.Result onlyRight = DiffOps.diff("", "a\nb", word);
        check("左侧为空时全部判为新增（2 行、1 组）",
                onlyRight.addRows() == 2 && onlyRight.groups().size() == 1,
                onlyRight.addRows() + " / " + onlyRight.groups().size());

        // ---------- 9. 大文本降级（不走 LCS，按行号逐位比对） ----------
        //
        // 这一路的坑是「行号相同 ≠ 内容相同」：曾经在这里无条件发 SAME，
        // 于是行数相同、内容不同的两份文本被判成完全一致
        // （2500 行每行都不同 → 底部报「差异 0 处」）。
        // 这类错最坏：结论是错的，却看起来像是「没问题」，所以这四条必须钉死。
        int big = 2500;
        check("降级前提：行数积超过 DP_CELL_LIMIT 才走逐位比对",
                big * (long) big > DiffOps.DP_CELL_LIMIT, "阈值 " + DiffOps.DP_CELL_LIMIT);

        String bigLeft = numbered(big, "line");
        DiffOps.Result bigSame = DiffOps.diff(bigLeft, bigLeft, word);
        check("大文本降级：两侧完全一致仍判一致（不因降级乱报差异）",
                bigSame.degraded() && bigSame.equal() && bigSame.groups().isEmpty(),
                "degraded=" + bigSame.degraded() + " groups=" + bigSame.groups().size());

        DiffOps.Result bigMod = DiffOps.diff(bigLeft, bigLeft.replace("line 1201\n", "line 1201 changed\n"), word);
        check("大文本降级：只改一行也要报出来（不能吞成 0 处）",
                bigMod.degraded() && bigMod.groups().size() == 1 && bigMod.modRows() == 1,
                "degraded=" + bigMod.degraded() + " groups=" + bigMod.groups().size()
                        + " mod=" + bigMod.modRows());

        DiffOps.Result bigAll = DiffOps.diff(numbered(big, "beta"), numbered(big, "alpha"), word);
        check("大文本降级：每行都不同时报出差异，且两侧行数一个不丢",
                bigAll.degraded() && !bigAll.equal() && bigAll.groups().size() >= 1
                        && bigAll.delRows() == big && bigAll.addRows() == big
                        && bigAll.leftLines() == big && bigAll.rightLines() == big,
                "degraded=" + bigAll.degraded() + " groups=" + bigAll.groups().size()
                        + " del=" + bigAll.delRows() + " add=" + bigAll.addRows()
                        + " 左行 " + bigAll.leftLines() + " 右行 " + bigAll.rightLines());

        // ---------- 8. 补丁导出 ----------
        String patch = DiffOps.patchText(r);
        check("补丁带文件头", patch.startsWith("--- 原文\n+++ 修改后\n"), patch.substring(0, Math.min(20, patch.length())));
        check("补丁带 @@ 段头（第 3–6 行、左右各 4 行）",
                patch.contains("@@ -3,4 +3,4 @@"), firstHunk(patch));
        check("补丁携带删除行与新增行",
                patch.contains("-port = 8080") && patch.contains("+port = 9090"),
                firstHunk(patch));

        System.out.println();
        System.out.println("RESULT passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    /** 造一份 {@code count} 行的文本（{@code prefix 1} … {@code prefix count}），用于触发降级路径。 */
    private static String numbered(int count, String prefix) {
        StringBuilder sb = new StringBuilder(count * 12);
        for (int i = 1; i <= count; i++) {
            sb.append(prefix).append(' ').append(i).append('\n');
        }
        return sb.toString();
    }

    private static boolean hasMod(DiffOps.Result result, int leftNo, int rightNo) {
        for (DiffOps.DiffRow row : result.rows()) {
            if (row.kind() == DiffOps.Kind.MOD && row.leftNo() == leftNo && row.rightNo() == rightNo) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDel(DiffOps.Result result, int leftNo) {
        for (DiffOps.DiffRow row : result.rows()) {
            if (row.kind() == DiffOps.Kind.DEL && row.leftNo() == leftNo) {
                return true;
            }
        }
        return false;
    }

    /** 取某个原文行号上的 MOD 行，拼出该侧所有被标记为变化的片段。 */
    private static String changedText(DiffOps.Result result, int leftNo, boolean left) {
        for (DiffOps.DiffRow row : result.rows()) {
            if (row.kind() == DiffOps.Kind.MOD && row.leftNo() == leftNo) {
                return changed(row, left);
            }
            if (row.kind() == DiffOps.Kind.SAME && row.leftNo() == leftNo && row.hasRight()) {
                return changed(row, left);
            }
        }
        return "<未找到行 " + leftNo + ">";
    }

    private static String changed(DiffOps.DiffRow row, boolean left) {
        StringBuilder sb = new StringBuilder();
        for (DiffOps.Span span : left ? row.leftSpans() : row.rightSpans()) {
            if (span.changed()) {
                sb.append(span.text());
            }
        }
        return sb.toString();
    }

    private static String firstHunk(String patch) {
        int at = patch.indexOf("@@");
        return at < 0 ? "<无 hunk>" : patch.substring(at);
    }
}
