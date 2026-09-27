package com.bytefuture.toolnest.modules.http;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 占位符替换器。
 *
 * <p>约定：模板里写 <code>{列名}</code>（单花括号），批量执行时用当前 CSV 行的
 * 同名列值替换。例如 URL 为
 * <pre>http://host/api/order?orderId={orderId}&amp;type={type}</pre>
 * CSV 表头为 {@code orderId,type}，则每行会替换出不同的请求。
 *
 * <p>为兼容 Postman 习惯，同时识别 <code>{{列名}}</code> 双花括号写法。
 * 但单花括号优先匹配，因此 <code>{{x}}</code> 会被解析为「花括号包裹的 {x}」——
 * 为避免歧义，双花括号会在扫描前先归一化为单花括号。
 *
 * <p>未在 CSV 中找到的列名<b>原样保留</b>，便于用户发现拼写错误，
 * 并在结果里以「未解析变量」提示。
 */
public final class TemplateResolver {

    /** 匹配 {name}，name 允许字母数字下划线点横线。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\s*([A-Za-z0-9_.\\-]+)\\s*}");
    /** 匹配 {{name}}，在归一化阶段处理。 */
    private static final Pattern DOUBLE = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.\\-]+)\\s*}}");

    private TemplateResolver() {
    }

    /**
     * 用给定的变量表替换模板中的占位符。
     *
     * @param template 含占位符的文本，可为 null
     * @param vars     变量表（CSV 一行），可为 null
     * @return 替换后的文本；template 为 null 时返回空串
     */
    public static String resolve(String template, Map<String, String> vars) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        String normalized = normalize(template);
        if (vars == null || vars.isEmpty()) {
            return normalized;
        }

        Matcher matcher = PLACEHOLDER.matcher(normalized);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = lookup(vars, key);
            // 找不到就原样保留，让用户能看出变量名写错了
            matcher.appendReplacement(sb, Matcher.quoteReplacement(
                    value != null ? value : matcher.group(0)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 找出模板中引用、但在变量表里<b>不存在</b>的占位符名。
     * 用于批量执行前的预检提示。
     */
    public static Set<String> unresolved(String template, Map<String, String> vars) {
        Set<String> missing = new LinkedHashSet<>();
        if (template == null || template.isEmpty()) {
            return missing;
        }
        Matcher matcher = PLACEHOLDER.matcher(normalize(template));
        while (matcher.find()) {
            String key = matcher.group(1);
            if (vars == null || lookup(vars, key) == null) {
                missing.add(key);
            }
        }
        return missing;
    }

    /** 模板中出现的全部占位符名（不论是否可解析）。 */
    public static Set<String> placeholders(String template) {
        Set<String> names = new LinkedHashSet<>();
        if (template == null || template.isEmpty()) {
            return names;
        }
        Matcher matcher = PLACEHOLDER.matcher(normalize(template));
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /**
     * 变量查表：先精确匹配，再忽略大小写匹配。
     * 这样 CSV 里叫 {@code OrderId}、模板里写 {@code {orderid}} 也能工作。
     */
    private static String lookup(Map<String, String> vars, String key) {
        if (vars.containsKey(key)) {
            return vars.get(key);
        }
        for (Map.Entry<String, String> e : vars.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** 把 {{x}} 归一化成 {x}，保证后续只需处理一种语法。 */
    private static String normalize(String template) {
        if (template.indexOf("{{") < 0) {
            return template;
        }
        return DOUBLE.matcher(template).replaceAll("{$1}");
    }
}
