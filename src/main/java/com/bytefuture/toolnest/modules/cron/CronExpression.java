package com.bytefuture.toolnest.modules.cron;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Cron 表达式解析与下次执行时间推算。
 *
 * <p>零依赖实现，支持标准 5 段与含秒的 6 段两种写法：
 * <pre>
 *   秒 分 时 日 月 周     （6 段，秒可选）
 *     分 时 日 月 周     （5 段）
 * </pre>
 *
 * <p>支持的语法：
 * <ul>
 *   <li>{@code *} 任意值</li>
 *   <li>{@code 5} 单值</li>
 *   <li>{@code 1-5} 区间</li>
 *   <li>{@code 1,3,5} 枚举</li>
 *   <li>{@code *}{@code /15} 步长（自最小值起）</li>
 *   <li>{@code 1-30/5} 区间 + 步长</li>
 *   <li>{@code 0} 或 {@code 7} 表示周日（Quartz / Linux cron 兼容）</li>
 *   <li>别名 {@code MON} ~ {@code SUN}、{@code JAN} ~ {@code DEC}</li>
 *   <li>{@code @yearly} / {@code @monthly} / {@code @weekly} / {@code @daily} / {@code @hourly}</li>
 * </ul>
 *
 * <h2>为什么用「逐分钟向后试探」而不是纯数学推导</h2>
 * <p>数学推导需要处理「日」与「周」的或关系、闰年、月份天数等大量边界，
 * 容易在个案上出错且难以验证。本实现改为从给定时间起按最小粒度向前试探
 * （有秒字段时逐秒、否则逐分钟），配合各字段的集合判断，最多扫描有限步数即返回。
 * 逻辑简单、边界清晰，代价是极端表达式（如 2 月 30 日）需要扫满上限才判定无解 ——
 * 这恰恰是我们想要的：能明确回报「该表达式永不触发」，而不是静默给出错误答案。
 */
public final class CronExpression {

    /** 各字段的取值范围（闭区间）。 */
    private static final int MINUTE_MIN = 0, MINUTE_MAX = 59;
    private static final int HOUR_MIN = 0, HOUR_MAX = 23;
    private static final int DAY_MIN = 1, DAY_MAX = 31;
    private static final int MONTH_MIN = 1, MONTH_MAX = 12;
    /** 周：0=周日 … 6=周六。 */
    private static final int DOW_MIN = 0, DOW_MAX = 6;
    private static final int SECOND_MIN = 0, SECOND_MAX = 59;

    /** 向后试探的最大天数，超过则判定「永不触发」。4 年为闰年完整周期。 */
    private static final int MAX_SCAN_DAYS = 366 * 4 + 1;

    private final boolean hasSeconds;
    private final String raw;

    private final Set<Integer> seconds;
    private final Set<Integer> minutes;
    private final Set<Integer> hours;
    private final Set<Integer> daysOfMonth;
    private final Set<Integer> months;
    private final Set<Integer> daysOfWeek;

    /** 原本表达式里「日」或「周」是否为 *，用于确定二者的组合语义。 */
    private final boolean dayOfMonthRestricted;
    private final boolean dayOfWeekRestricted;

    private CronExpression(String raw, boolean hasSeconds, Set<Integer> seconds,
                           Set<Integer> minutes, Set<Integer> hours,
                           Set<Integer> daysOfMonth, Set<Integer> months,
                           Set<Integer> daysOfWeek,
                           boolean dayOfMonthRestricted, boolean dayOfWeekRestricted) {
        this.raw = raw;
        this.hasSeconds = hasSeconds;
        this.seconds = seconds;
        this.minutes = minutes;
        this.hours = hours;
        this.daysOfMonth = daysOfMonth;
        this.months = months;
        this.daysOfWeek = daysOfWeek;
        this.dayOfMonthRestricted = dayOfMonthRestricted;
        this.dayOfWeekRestricted = dayOfWeekRestricted;
    }

    // ==================== 解析 ====================

    /**
     * 解析表达式。
     *
     * @throws IllegalArgumentException 表达式非法时抛出，消息可直接展示给用户
     */
    public static CronExpression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("表达式不能为空");
        }
        String text = expression.trim();

        // 别名展开
        String expanded = expandAlias(text);
        String[] parts = expanded.split("\\s+");

        String secPart;
        String minPart;
        String hourPart;
        String domPart;
        String monthPart;
        String dowPart;
        boolean withSeconds;

        if (parts.length == 6) {
            withSeconds = true;
            secPart = parts[0];
            minPart = parts[1];
            hourPart = parts[2];
            domPart = parts[3];
            monthPart = parts[4];
            dowPart = parts[5];
        } else if (parts.length == 5) {
            withSeconds = false;
            secPart = "0";
            minPart = parts[0];
            hourPart = parts[1];
            domPart = parts[2];
            monthPart = parts[3];
            dowPart = parts[4];
        } else {
            throw new IllegalArgumentException(
                    "需要 5 段（分 时 日 月 周）或 6 段（秒 分 时 日 月 周），"
                            + "当前为 " + parts.length + " 段");
        }

        Set<Integer> seconds = parseField(secPart, SECOND_MIN, SECOND_MAX, "秒", null);
        Set<Integer> minutes = parseField(minPart, MINUTE_MIN, MINUTE_MAX, "分", null);
        Set<Integer> hours = parseField(hourPart, HOUR_MIN, HOUR_MAX, "时", null);
        Set<Integer> days = parseField(domPart, DAY_MIN, DAY_MAX, "日", null);
        Set<Integer> months = parseField(monthPart, MONTH_MIN, MONTH_MAX, "月", MONTH_NAMES);
        Set<Integer> dows = parseField(dowPart, DOW_MIN, DOW_MAX, "周", DOW_NAMES);

        boolean domRestricted = !"*".equals(domPart.trim()) && !"?".equals(domPart.trim());
        boolean dowRestricted = !"*".equals(dowPart.trim()) && !"?".equals(dowPart.trim());

        return new CronExpression(text, withSeconds, seconds, minutes, hours,
                days, months, dows, domRestricted, dowRestricted);
    }

    private static final String[] MONTH_NAMES = {
            "JAN", "FEB", "MAR", "APR", "MAY", "JUN",
            "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"
    };

    private static final String[] DOW_NAMES = {
            "SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"
    };

    /** 展开 @ 开头的别名。 */
    private static String expandAlias(String text) {
        return switch (text.toLowerCase()) {
            case "@yearly", "@annually" -> "0 0 1 1 *";
            case "@monthly" -> "0 0 1 * *";
            case "@weekly" -> "0 0 * * 0";
            case "@daily", "@midnight" -> "0 0 * * *";
            case "@hourly" -> "0 * * * *";
            default -> text;
        };
    }

    /**
     * 解析单个字段为取值集合。
     *
     * @param field     字段原文
     * @param min       最小值
     * @param max       最大值
     * @param fieldName 字段中文名，用于错误提示
     * @param names     别名数组（如月份 MON、星期 MON），无别名传 null
     */
    private static Set<Integer> parseField(String field, int min, int max,
                                           String fieldName, String[] names) {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 字段不能为空");
        }
        Set<Integer> result = new TreeSet<>();
        String normalized = field.trim().toUpperCase();

        // ? 等同于 *（Quartz 习惯）
        if ("?".equals(normalized)) {
            normalized = "*";
        }

        for (String piece : normalized.split(",")) {
            String token = piece.trim();
            if (token.isEmpty()) {
                throw new IllegalArgumentException(fieldName + " 字段存在空的枚举项");
            }

            // 拆出步长
            int step = 1;
            int slash = token.indexOf('/');
            if (slash >= 0) {
                String stepText = token.substring(slash + 1).trim();
                token = token.substring(0, slash).trim();
                try {
                    step = Integer.parseInt(stepText);
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException(
                            fieldName + " 字段的步长不是整数：" + stepText);
                }
                if (step <= 0) {
                    throw new IllegalArgumentException(fieldName + " 字段的步长必须大于 0");
                }
            }

            int from;
            int to;
            if ("*".equals(token)) {
                from = min;
                to = max;
            } else {
                int dash = token.indexOf('-');
                if (dash > 0) {
                    from = resolveValue(token.substring(0, dash).trim(), fieldName, names);
                    to = resolveValue(token.substring(dash + 1).trim(), fieldName, names);
                    if (from > to) {
                        throw new IllegalArgumentException(
                                fieldName + " 字段的区间起点大于终点：" + token);
                    }
                } else {
                    from = resolveValue(token, fieldName, names);
                    // 无步长时单值就是单值；有步长时「5/10」按「从 5 开始到上界」理解，
                    // 这与 Quartz 的行为一致
                    to = slash >= 0 ? max : from;
                }
            }

            for (int v = from; v <= to; v += step) {
                result.add(normalizeValue(v, fieldName, min, max));
            }
        }

        if (result.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " 字段没有解析出任何取值");
        }
        return result;
    }

    /** 把别名或数字转成数值。 */
    private static int resolveValue(String token, String fieldName, String[] names) {
        if (names != null) {
            for (int i = 0; i < names.length; i++) {
                if (names[i].equalsIgnoreCase(token)) {
                    // 月份别名下标 0 = JAN = 1 月；星期别名下标 0 = SUN = 0
                    return names == DOW_NAMES ? i : i + 1;
                }
            }
        }
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    fieldName + " 字段包含无法识别的值：" + token);
        }
    }

    /** 规范化特殊值：周字段的 7 表示周日，需折回 0。 */
    private static int normalizeValue(int value, String fieldName, int min, int max) {
        if ("周".equals(fieldName) && value == 7) {
            return 0;
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    fieldName + " 字段的取值 " + value + " 超出范围 [" + min + ", " + max + "]");
        }
        return value;
    }

    // ==================== 匹配与推算 ====================

    /**
     * 判断给定时间是否命中该表达式。
     *
     * <p>秒字段在 5 段表达式中固定为 0，因此逐秒判断时只有第 0 秒会命中。
     */
    public boolean matches(LocalDateTime time) {
        if (!seconds.contains(time.getSecond())) {
            return false;
        }
        if (!minutes.contains(time.getMinute())) {
            return false;
        }
        if (!hours.contains(time.getHour())) {
            return false;
        }
        if (!months.contains(time.getMonthValue())) {
            return false;
        }
        return matchesDay(time);
    }

    /**
     * 判断「日」与「周」是否命中。
     *
     * <p>这里遵循 cron 的通行语义（Vixie cron / Quartz）：
     * <ul>
     *   <li>两者都为 {@code *} → 每天都命中</li>
     *   <li>只有其中一个被限定 → 按被限定的那个判断</li>
     *   <li>两者都被限定 → <b>或</b>关系（满足任一即命中）</li>
     * </ul>
     * 最后一条容易踩坑：{@code 0 0 1 * 1} 表示「每月 1 号<b>或</b>每周一」，
     * 而不是「每月 1 号且是周一」。
     */
    private boolean matchesDay(LocalDateTime time) {
        // DayOfWeek.getValue(): 周一=1 … 周日=7；cron 里周日=0
        int cronDow = time.getDayOfWeek().getValue() % 7;
        boolean domHit = daysOfMonth.contains(time.getDayOfMonth());
        boolean dowHit = daysOfWeek.contains(cronDow);

        if (!dayOfMonthRestricted && !dayOfWeekRestricted) {
            return true;
        }
        if (dayOfMonthRestricted && !dayOfWeekRestricted) {
            return domHit;
        }
        if (!dayOfMonthRestricted) {
            return dowHit;
        }
        return domHit || dowHit;
    }

    /**
     * 推算从 {@code from} 之后的下一次执行时间。
     *
     * @return 下次执行时间；表达式永不触发时返回 null
     */
    public LocalDateTime next(LocalDateTime from) {
        LocalDateTime cursor = from;
        if (hasSeconds) {
            cursor = cursor.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
        } else {
            cursor = cursor.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        }

        LocalDateTime limit = from.plusDays(MAX_SCAN_DAYS);
        long stepSeconds = hasSeconds ? 1 : 60;

        while (!cursor.isAfter(limit)) {
            // 月份不命中时直接跳到下个月 1 号 0 点，避免逐秒空转
            if (!months.contains(cursor.getMonthValue())) {
                cursor = cursor.plusMonths(1).withDayOfMonth(1)
                        .withHour(0).withMinute(0).withSecond(0);
                continue;
            }
            if (matches(cursor)) {
                return cursor;
            }
            cursor = cursor.plusSeconds(stepSeconds);
        }
        return null;
    }

    /** 批量推算未来 n 次执行时间。 */
    public List<LocalDateTime> nextList(LocalDateTime from, int count) {
        List<LocalDateTime> list = new ArrayList<>(count);
        LocalDateTime cursor = from;
        for (int i = 0; i < count; i++) {
            LocalDateTime next = next(cursor);
            if (next == null) {
                break;
            }
            list.add(next);
            cursor = next;
        }
        return list;
    }

    public boolean hasSeconds() {
        return hasSeconds;
    }

    public String raw() {
        return raw;
    }

    // ==================== 中文描述 ====================

    /**
     * 生成中文可读描述。
     *
     * <p>刻意不追求逐字精确的散文式描述（那需要处理无穷多的组合），
     * 而是给出「结构化的逐字段说明」，这是运维场景下更实用的形式：
     * 一眼能看出每个位置限定了什么。
     */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (hasSeconds) {
            sb.append("秒：").append(describeSet(seconds, 0, 59, "秒")).append('\n');
        }
        sb.append("分：").append(describeSet(minutes, 0, 59, "分")).append('\n');
        sb.append("时：").append(describeHours()).append('\n');
        sb.append("日：").append(describeDayOfMonth()).append('\n');
        sb.append("月：").append(describeMonths()).append('\n');
        sb.append("周：").append(describeDayOfWeek());
        return sb.toString();
    }

    private String describeSet(Set<Integer> set, int min, int max, String unit) {
        if (set.size() == max - min + 1) {
            return "每" + unit;
        }
        Integer step = uniformStep(set, min, max);
        if (step != null && step > 1) {
            return "每 " + step + " " + unit + "（" + join(set) + "）";
        }
        return join(set);
    }

    private String describeHours() {
        if (hours.size() == 24) {
            return "每小时";
        }
        Integer step = uniformStep(hours, 0, 23);
        if (step != null && step > 1) {
            return "每 " + step + " 小时（" + join(hours) + " 时）";
        }
        return join(hours) + " 时";
    }

    private String describeDayOfMonth() {
        if (daysOfMonth.size() == 31) {
            return "每天";
        }
        return "每月 " + join(daysOfMonth) + " 日";
    }

    private String describeMonths() {
        if (months.size() == 12) {
            return "每月";
        }
        StringBuilder sb = new StringBuilder();
        for (Integer m : months) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(m).append(" 月");
        }
        return sb.toString();
    }

    private String describeDayOfWeek() {
        if (daysOfWeek.size() == 7) {
            return "每天";
        }
        String[] names = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        StringBuilder sb = new StringBuilder();
        for (Integer d : daysOfWeek) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(names[d]);
        }
        return sb.toString();
    }

    /** 若集合是等步长等差数列则返回步长，否则返回 null。 */
    private Integer uniformStep(Set<Integer> set, int min, int max) {
        if (set.size() < 2) {
            return null;
        }
        List<Integer> list = new ArrayList<>(set);
        int first = list.get(0);
        // 只有从取值下界开始才算「整齐的步长」，否则描述会误导
        if (first != min) {
            return null;
        }
        int step = list.get(1) - first;
        if (step <= 1) {
            return null;
        }
        for (int i = 1; i < list.size(); i++) {
            if (list.get(i) - list.get(i - 1) != step) {
                return null;
            }
        }
        return step;
    }

    private String join(Set<Integer> set) {
        StringBuilder sb = new StringBuilder();
        for (Integer v : set) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(v);
        }
        return sb.toString();
    }
}
