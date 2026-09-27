package com.bytefuture.toolnest.modules.http;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP 请求执行引擎，基于 JDK 内置的 {@link HttpClient}（零第三方依赖）。
 *
 * <p>负责：拼装 URL（合并已存在的查询串与 Params 表）、设置请求头与请求体、
 * 发送请求、采集状态码 / 耗时 / 响应头 / 响应体。
 */
public final class HttpEngine {

    /** 一次请求的执行结果。 */
    public static final class Result {
        /** HTTP 状态码；-1 表示请求未成功发出（如 DNS 失败、超时）。 */
        public int status = -1;
        /** 耗时（毫秒）。 */
        public long elapsedMs;
        /** 响应头，大小写不敏感合并后的结果。 */
        public final Map<String, String> headers = new LinkedHashMap<>();
        /** 响应体文本。 */
        public String body = "";
        /** 实际发出的最终 URL（替换占位符、合并 Params 之后）。 */
        public String finalUrl = "";
        /** 实际发出的请求体。 */
        public String requestBody = "";
        /** 错误信息；为 null 表示没有错误。 */
        public String error;

        public boolean isSuccess() {
            return error == null && status >= 200 && status < 300;
        }

        /** 状态码分类，用于界面着色。 */
        public String statusClass() {
            if (error != null) {
                return "status-error";
            }
            if (status >= 200 && status < 300) {
                return "status-ok";
            }
            if (status >= 300 && status < 400) {
                return "status-redirect";
            }
            return "status-fail";
        }
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private HttpEngine() {
    }

    /**
     * 发送一个请求。
     *
     * @param spec    请求描述（已替换好占位符）
     * @param params  查询参数键值对（值可为 null 表示跳过该行）
     * @param headers 请求头键值对
     * @param timeout 超时时间
     */
    public static Result send(HttpRequestSpec spec,
                              List<Map.Entry<String, String>> params,
                              List<Map.Entry<String, String>> headers,
                              Duration timeout) {
        Result result = new Result();
        long start = System.nanoTime();

        try {
            String url = mergeQuery(spec.url, params);
            result.finalUrl = url;

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout);

            // 请求头：先放用户自定义的
            if (headers != null) {
                for (Map.Entry<String, String> e : headers) {
                    String key = e.getKey();
                    if (key == null || key.isBlank()) {
                        continue;
                    }
                    try {
                        builder.header(key.trim(), e.getValue() == null ? "" : e.getValue());
                    } catch (IllegalArgumentException badHeader) {
                        // 非法头名（含空格、非 ASCII 等）跳过，不让整次请求失败
                    }
                }
            }

            // 请求体
            HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.noBody();
            if (spec.hasBody()) {
                result.requestBody = spec.body;
                publisher = HttpRequest.BodyPublishers.ofString(spec.body, StandardCharsets.UTF_8);
                if (spec.bodyType != HttpRequestSpec.BodyType.NONE
                        && !hasHeader(result, headers, "Content-Type")) {
                    builder.header("Content-Type", spec.bodyType.contentType() + "; charset=UTF-8");
                }
            }

            String method = spec.method.name();
            builder.method(method, publisher);

            HttpResponse<String> response = CLIENT.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            result.status = response.statusCode();
            response.headers().map().forEach((k, v) -> {
                if (v != null && !v.isEmpty()) {
                    result.headers.put(k, String.join(", ", v));
                }
            });
            result.body = response.body() == null ? "" : response.body();

        } catch (Exception ex) {
            result.error = describe(ex);
            if (result.body == null) {
                result.body = "";
            }
        } finally {
            result.elapsedMs = (System.nanoTime() - start) / 1_000_000;
        }
        return result;
    }

    /** 便捷方法：只有一个 URL，无额外参数与头。 */
    public static Result send(HttpRequestSpec spec, Duration timeout) {
        return send(spec, null, null, timeout);
    }

    /**
     * 把 Params 表合并进 URL。
     *
     * <p>URL 自带的查询串与 Params 表会同时保留，并用 {@code &} 连接，
     * 这样「直接从浏览器复制的完整 URL」和「结构化填写的参数」两种习惯都能用。
     */
    private static String mergeQuery(String url, List<Map.Entry<String, String>> params) {
        String base = url == null ? "" : url.trim();
        if (params == null || params.isEmpty()) {
            return base;
        }
        StringBuilder extra = new StringBuilder();
        for (Map.Entry<String, String> e : params) {
            String key = e.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            if (extra.length() > 0) {
                extra.append('&');
            }
            extra.append(encode(key.trim())).append('=').append(encode(e.getValue()));
        }
        if (extra.length() == 0) {
            return base;
        }
        return base + (base.contains("?") ? "&" : "?") + extra;
    }

    private static String encode(String value) {
        return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean hasHeader(Result result,
                                     List<Map.Entry<String, String>> headers,
                                     String name) {
        if (headers != null) {
            for (Map.Entry<String, String> e : headers) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 把异常翻译成人能看懂的一句话。 */
    private static String describe(Exception ex) {
        // 顺着 cause 链找出最具体的那个异常，否则外层包装异常的信息量很低
        String detail = collectMessages(ex);
        String chain = collectTypes(ex) + " " + detail;
        String lower = chain.toLowerCase(Locale.ROOT);

        // UnresolvedAddressException / UnknownHostException 都是域名解析不出来
        if (lower.contains("unresolvedaddress")
                || lower.contains("unknownhost")
                || lower.contains("nodename")
                || lower.contains("no such host")) {
            return "域名解析失败，请检查地址是否正确";
        }
        if (lower.contains("connect timed out") || lower.contains("connection timed out")) {
            return "连接超时，目标服务无响应";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "请求超时，可在上方调整超时时间";
        }
        if (lower.contains("refused")) {
            // DNS 劫持场景也会走到这里，提示里给出两种可能
            return "连接被拒绝：目标端口未开放，或域名不存在（被 DNS 劫持到本地）";
        }
        if (lower.contains("ssl") || lower.contains("certificate") || lower.contains("pkix")) {
            return "SSL 证书校验失败：该站点证书不被信任";
        }
        if (lower.contains("unsupported") || lower.contains("illegalargument")
                || lower.contains("no protocol") || lower.contains("url")) {
            return "URL 格式不正确，请确认以 http:// 或 https:// 开头";
        }

        String brief = detail.length() > 160 ? detail.substring(0, 160) + "…" : detail;
        return ex.getClass().getSimpleName()
                + (brief.isBlank() ? "" : "：" + brief);
    }

    /** 汇总整条 cause 链的消息，供关键字匹配。 */
    private static String collectMessages(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        Throwable current = ex;
        int guard = 0;
        while (current != null && guard++ < 10) {
            if (current.getMessage() != null) {
                sb.append(current.getMessage()).append(' ');
            }
            current = current.getCause();
        }
        return sb.toString();
    }

    /** 汇总整条 cause 链的异常类名 —— 有些 JDK 异常 message 为 null，只能靠类名判断。 */
    private static String collectTypes(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        Throwable current = ex;
        int guard = 0;
        while (current != null && guard++ < 10) {
            sb.append(current.getClass().getSimpleName()).append(' ');
            current = current.getCause();
        }
        return sb.toString();
    }
}
