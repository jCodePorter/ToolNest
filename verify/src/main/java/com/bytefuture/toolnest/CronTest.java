package com.bytefuture.toolnest;

import com.bytefuture.toolnest.features.cron.CronExpression;
import com.bytefuture.toolnest.features.cron.CronModule;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Cron 解析的纯逻辑验证（无 JavaFX 依赖，可直接运行）。
 *
 * <p>覆盖：5 段 / 6 段、步长、区间、枚举、别名、日月周或关系、
 * 闰年、月末、永不触发的表达式。
 */
public final class CronTest {

    private static int passed = 0;
    private static int failed = 0;

    private CronTest() {
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

    private static void expectNext(String expr, LocalDateTime from, String expected) {
        try {
            CronExpression cron = CronExpression.parse(expr);
            LocalDateTime next = cron.next(from);
            String actual = next == null ? "null" : next.toString();
            check(expr + "  从 " + from, expected.equals(actual),
                    "期望 " + expected + " 实际 " + actual);
        } catch (Exception ex) {
            check(expr + "  从 " + from, false, "抛异常：" + ex.getMessage());
        }
    }

    private static void expectInvalid(String expr) {
        try {
            CronExpression.parse(expr);
            check("非法表达式应被拒绝：" + expr, false, "却解析成功了");
        } catch (IllegalArgumentException ex) {
            check("非法表达式应被拒绝：" + expr, true, "");
        }
    }

    public static void main(String[] args) {
        System.out.println("=== Cron 表达式解析验证 ===");

        // ---------- 基础 5 段 ----------
        expectNext("0 0 * * *", LocalDateTime.of(2026, 9, 24, 10, 30, 0),
                "2026-09-25T00:00");
        expectNext("30 9 * * *", LocalDateTime.of(2026, 9, 24, 10, 30, 0),
                "2026-09-25T09:30");
        expectNext("*/15 * * * *", LocalDateTime.of(2026, 9, 24, 10, 31, 0),
                "2026-09-24T10:45");
        expectNext("0 */2 * * *", LocalDateTime.of(2026, 9, 24, 10, 30, 0),
                "2026-09-24T12:00");

        // ---------- 小时步长 ----------
        // 6 段写法：秒=0 分=0 时=*/6 → 0/6/12/18 点
        expectNext("0 0 */6 * * *", LocalDateTime.of(2026, 9, 24, 10, 0, 0),
                "2026-09-24T12:00");
        // 5 段写法：分=0 时=*/6 → 同样应该是当天 12 点
        expectNext("0 */6 * * *", LocalDateTime.of(2026, 9, 24, 10, 0, 0),
                "2026-09-24T12:00");

        // ---------- 枚举与区间 ----------
        expectNext("0 9 * * 1-5", LocalDateTime.of(2026, 9, 24, 10, 0, 0),
                "2026-09-25T09:00");
        // 2026-09-24 是周四，下一个周一应为 9-28
        expectNext("0 9 * * MON", LocalDateTime.of(2026, 9, 24, 10, 0, 0),
                "2026-09-28T09:00");

        // ---------- 6 段含秒 ----------
        expectNext("*/30 * * * * *", LocalDateTime.of(2026, 9, 24, 10, 30, 10),
                "2026-09-24T10:30:30");
        expectNext("0 0 12 * * *", LocalDateTime.of(2026, 9, 24, 10, 30, 10),
                "2026-09-24T12:00");

        // ---------- 指定日期 ----------
        expectNext("0 0 1 1 *", LocalDateTime.of(2026, 9, 24, 10, 0, 0),
                "2027-01-01T00:00");

        // ---------- 闰年：2028-02-29 存在 ----------
        expectNext("0 0 0 29 2 *", LocalDateTime.of(2026, 9, 24, 10, 0, 0),
                "2028-02-29T00:00");

        // ---------- 月末：31 号只在有 31 天的月份触发 ----------
        // 2026-09-24 之后，9 月无 31 号（9 月 30 天），下一个 31 号是 10-31
        expectNext("0 0 0 31 * *", LocalDateTime.of(2026, 9, 24, 10, 0, 0),
                "2026-10-31T00:00");

        // ---------- 日与周都限定时是「或」关系 ----------
        // 0 0 1 * 1 = 每月 1 号 或 每周一。9-24 是周四，最近的周一 9-28
        expectNext("0 0 1 * 1", LocalDateTime.of(2026, 9, 24, 10, 0, 0),
                "2026-09-28T00:00");

        // ---------- 别名 ----------
        expectNext("@daily", LocalDateTime.of(2026, 9, 24, 10, 0, 0),
                "2026-09-25T00:00");
        expectNext("@hourly", LocalDateTime.of(2026, 9, 24, 10, 30, 0),
                "2026-09-24T11:00");

        // ---------- 周日的两种写法应一致 ----------
        String viaZero = String.valueOf(
                CronExpression.parse("0 9 * * 0").next(LocalDateTime.of(2026, 9, 24, 10, 0)));
        String viaSeven = String.valueOf(
                CronExpression.parse("0 9 * * 7").next(LocalDateTime.of(2026, 9, 24, 10, 0)));
        check("周日的 0 与 7 写法结果一致", viaZero.equals(viaSeven),
                "0 → " + viaZero + "，7 → " + viaSeven);

        // ---------- 永不触发：2 月 30 日 ----------
        try {
            CronExpression cron = CronExpression.parse("0 0 0 30 2 *");
            check("2 月 30 日应判定为永不触发", cron.next(LocalDateTime.of(2026, 1, 1, 0, 0)) == null,
                    "却给出了下次时间");
        } catch (Exception ex) {
            check("2 月 30 日应判定为永不触发", false, "抛异常：" + ex.getMessage());
        }

        // ---------- 非法输入 ----------
        expectInvalid("");
        expectInvalid("1 2 3");
        expectInvalid("60 0 * * *");        // 分超出 59
        expectInvalid("0 24 * * *");        // 时超出 23
        expectInvalid("0 0 32 * *");        // 日超出 31
        expectInvalid("0 0 * 13 *");        // 月超出 12
        expectInvalid("0 0 * * 8");         // 周超出 7
        expectInvalid("*/0 * * * *");       // 步长为 0
        expectInvalid("abc * * * *");       // 非数字
        expectInvalid("0 0 5-1 * *");       // 区间倒置

        // ---------- 批量推算 ----------
        try {
            CronExpression cron = CronExpression.parse("0 0 * * *");
            List<LocalDateTime> list = cron.nextList(LocalDateTime.of(2026, 9, 24, 10, 0), 3);
            check("批量推算返回 3 条", list.size() == 3, "实际 " + list.size());
            check("批量推算时间递增",
                    list.size() == 3 && list.get(0).isBefore(list.get(1))
                            && list.get(1).isBefore(list.get(2)),
                    list.toString());
        } catch (Exception ex) {
            check("批量推算", false, "抛异常：" + ex.getMessage());
        }

        // ---------- 中文描述不抛异常 ----------
        try {
            String desc = CronExpression.parse("0 9 * * 1-5").describe();
            check("中文描述生成成功", desc != null && !desc.isBlank(),
                    String.valueOf(desc));
            System.out.println("---- 描述示例（0 9 * * 1-5）----");
            System.out.println(desc);
            System.out.println("------------------------------");
        } catch (Exception ex) {
            check("中文描述生成成功", false, "抛异常：" + ex.getMessage());
        }

        // ---------- 等待时长文案 ----------
        expectDuration("30 秒", Duration.ofSeconds(30), "30 秒");
        expectDuration("90 秒", Duration.ofSeconds(90), "1 分钟 30 秒");
        expectDuration("5 分钟整", Duration.ofMinutes(5), "5 分钟");
        expectDuration("2 小时整", Duration.ofHours(2), "2 小时");
        expectDuration("3 天整", Duration.ofDays(3), "3 天");
        expectDuration("1 天 6 小时", Duration.ofHours(30), "1 天 6 小时");

        // ---------- 未来执行时间列表渲染（界面只是它的薄壳） ----------
        try {
            LocalDateTime now = LocalDateTime.of(2026, 9, 27, 17, 57, 0);
            List<LocalDateTime> list = CronExpression.parse("0 0 * * *").nextList(now, 3);
            String text = CronModule.buildNextText(list, now);
            String[] lines = text.split("\n");

            check("列表首行 = 2026-09-28 00:00:00 周一",
                    lines.length == 3 && lines[0].startsWith(" 1. 2026-09-28 00:00:00  周一"),
                    lines.length > 0 ? lines[0] : "空");
            check("首行也标注等待时长（+6 小时 3 分）",
                    lines.length > 0 && lines[0].contains("(+6 小时 3 分)"),
                    lines.length > 0 ? lines[0] : "空");
            check("次行为距现在的 1 天 6 小时，而非与上次的间隔 +1 天",
                    lines.length > 1 && lines[1].contains("(+1 天 6 小时)"),
                    lines.length > 1 ? lines[1] : "空");
            check("等待时长逐行递增",
                    lines.length == 3 && lines[0].contains("6 小时")
                            && lines[1].contains("1 天") && lines[2].contains("2 天"),
                    java.util.Arrays.toString(lines));
        } catch (Exception ex) {
            check("未来执行时间列表渲染", false, "抛异常：" + ex.getMessage());
        }

        // 秒级表达式：间隔不足 1 分钟时不能退化成「+0 分钟」
        try {
            LocalDateTime now = LocalDateTime.of(2026, 9, 27, 17, 57, 0);
            List<LocalDateTime> list = CronExpression.parse("*/30 * * * * *").nextList(now, 2);
            String text = CronModule.buildNextText(list, now);
            String[] lines = text.split("\n");
            check("30 秒表达式首行显示 +30 秒",
                    lines.length == 2 && lines[0].contains("(+30 秒)"),
                    lines.length > 0 ? lines[0] : "空");
            check("30 秒表达式次行显示 +1 分钟",
                    lines.length == 2 && lines[1].contains("(+1 分钟)"),
                    lines.length > 1 ? lines[1] : "空");
        } catch (Exception ex) {
            check("秒级表达式等待时长", false, "抛异常：" + ex.getMessage());
        }

        System.out.println();
        System.out.println("RESULT passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void expectDuration(String name, Duration d, String expected) {
        String actual = CronModule.humanDuration(d);
        check("等待时长文案 " + name, expected.equals(actual), "期望 " + expected + " 实际 " + actual);
    }
}
