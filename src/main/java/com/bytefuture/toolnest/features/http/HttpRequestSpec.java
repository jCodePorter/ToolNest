package com.bytefuture.toolnest.features.http;

/**
 * 一个 HTTP 请求的完整描述。
 *
 * <p>设计成不可变的值对象 + 静态工厂，方便在「单一请求」与「批量请求」两条
 * 路径之间传递。所有字段都可能含有 {@code {占位符}}，由
 * {@link TemplateResolver} 在批量模式下按 CSV 行替换。
 */
public final class HttpRequestSpec {

    /** 请求方法。 */
    public enum Method {
        GET, POST, PUT, DELETE;

        /** 该方法是否通常带请求体。 */
        public boolean allowsBody() {
            return this == POST || this == PUT || this == DELETE;
        }
    }

    /** 请求体类型。 */
    public enum BodyType {
        NONE("none"),
        JSON("application/json"),
        FORM("application/x-www-form-urlencoded"),
        TEXT("text/plain"),
        XML("application/xml");

        private final String contentType;

        BodyType(String contentType) {
            this.contentType = contentType;
        }

        public String contentType() {
            return contentType;
        }
    }

    public final Method method;
    public final String url;
    /** 请求体文本，仅当 bodyType != NONE 时有意义。 */
    public final String body;
    public final BodyType bodyType;

    public HttpRequestSpec(Method method, String url, BodyType bodyType, String body) {
        this.method = method;
        this.url = url;
        this.bodyType = bodyType == null ? BodyType.NONE : bodyType;
        this.body = body == null ? "" : body;
    }

    /** 该请求是否应当携带请求体。 */
    public boolean hasBody() {
        return bodyType != BodyType.NONE && !body.isBlank();
    }
}
