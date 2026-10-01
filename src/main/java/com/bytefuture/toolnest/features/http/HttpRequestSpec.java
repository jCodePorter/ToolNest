package com.bytefuture.toolnest.features.http;

import java.util.List;
import java.util.Map;

/**
 * 一个 HTTP 请求的完整描述。
 *
 * <p>设计成不可变的值对象 + 静态工厂，方便在「单一请求」与「批量请求」两条
 * 路径之间传递。除 {@link #formData} / {@link #binaryPath} 外，所有文本字段都
 * 可能含有 {@code {占位符}}，由 {@link TemplateResolver} 在批量模式下按 CSV 行替换。
 *
 * <p>请求体有成对的三种载体，由 {@link #bodyType} 决定读哪一个：
 * <ul>
 *   <li>{@code JSON / XML / TEXT} → {@link #body} 是原文</li>
 *   <li>{@code FORM} → {@link #formFields} 是键值对（引擎负责百分号编码）；
 *       为空时退回读 {@link #body}，兼容批处理页那种「直接敲一行 a=1&amp;b=2」的用法</li>
 *   <li>{@code FORM_DATA} → {@link #formData} 是逐段模型（文本段 / 文件段）</li>
 *   <li>{@code BINARY} → {@link #binaryPath} 是整份文件作请求体</li>
 * </ul>
 */
public final class HttpRequestSpec {

    /** 请求方法。 */
    public enum Method {
        GET, POST, PUT, PATCH, DELETE;

        /** 该方法是否通常带请求体。GET 带体时 JDK 不拦，界面会提示但不阻断。 */
        public boolean allowsBody() {
            return this == POST || this == PUT || this == PATCH || this == DELETE;
        }
    }

    /** 请求体类型。 */
    public enum BodyType {
        NONE("none", "无"),
        JSON("application/json", "JSON"),
        FORM("application/x-www-form-urlencoded", "Form"),
        FORM_DATA("multipart/form-data", "Form-data"),
        XML("application/xml", "XML"),
        TEXT("text/plain", "Text"),
        BINARY("application/octet-stream", "Binary");

        private final String contentType;
        private final String label;

        BodyType(String contentType, String label) {
            this.contentType = contentType;
            this.label = label;
        }

        public String contentType() {
            return contentType;
        }

        /** 界面上的短标签（分段控件的项文字、页签标题摘要）。 */
        public String label() {
            return label;
        }

        /**
         * 批处理页可选的类型。
         *
         * <p>{@code FORM_DATA} 与 {@code BINARY} 只有「路径」没有「值」，
         * {@code {列名}} 占位符在它们身上没有意义 —— 语义需要单独设计一轮，
         * 所以本轮只在单一请求页提供，不进批处理。
         */
        public static List<BodyType> batchTypes() {
            return List.of(NONE, JSON, FORM, TEXT, XML);
        }
    }

    /**
     * multipart 的一段。
     *
     * <p>只有两种形态：文本段（{@link #text} 有值）或文件段（{@link #path} 有值）。
     * 用 {@link #isFile()} 区分 —— 界面上的「类型」列就是它的投影。
     */
    public static final class FormField {
        public final String name;
        /** 文本段的值；文件段为 null。 */
        public final String text;
        /** 文件段的本地路径；文本段为 null。 */
        public final String path;

        /** 文本段。 */
        public static FormField text(String name, String value) {
            return new FormField(name, value == null ? "" : value, null);
        }

        /** 文件段。 */
        public static FormField file(String name, String path) {
            return new FormField(name, null, path);
        }

        private FormField(String name, String text, String path) {
            this.name = name == null ? "" : name;
            this.text = text;
            this.path = path;
        }

        public boolean isFile() {
            return path != null && !path.isBlank();
        }

        /** 文件段的显示名 —— 只取文件名，完整路径挂在 tooltip 上。 */
        public String fileName() {
            if (!isFile()) {
                return "";
            }
            String p = path.replace('\\', '/');
            int slash = p.lastIndexOf('/');
            return slash >= 0 ? p.substring(slash + 1) : p;
        }
    }

    public final Method method;
    public final String url;
    /** 请求体文本，仅当 bodyType ∈ {JSON, XML, TEXT}（或 FORM 的兼容路径）时有意义。 */
    public final String body;
    public final BodyType bodyType;
    /** {@code FORM} 的结构化键值对；空表示退回用 {@link #body}。 */
    public final List<Map.Entry<String, String>> formFields;
    /** {@code FORM_DATA} 的逐段模型。 */
    public final List<FormField> formData;
    /** {@code BINARY} 的本地文件路径。 */
    public final String binaryPath;

    public HttpRequestSpec(Method method, String url, BodyType bodyType, String body) {
        this(method, url, bodyType, body, List.of(), List.of(), null);
    }

    public HttpRequestSpec(Method method, String url, BodyType bodyType, String body,
                           List<Map.Entry<String, String>> formFields,
                           List<FormField> formData,
                           String binaryPath) {
        this.method = method;
        this.url = url == null ? "" : url;
        this.bodyType = bodyType == null ? BodyType.NONE : bodyType;
        this.body = body == null ? "" : body;
        this.formFields = formFields == null ? List.of() : List.copyOf(formFields);
        this.formData = formData == null ? List.of() : List.copyOf(formData);
        this.binaryPath = binaryPath;
    }

    /** 该请求是否应当携带请求体。 */
    public boolean hasBody() {
        return switch (bodyType) {
            case NONE -> false;
            case FORM -> !formFields.isEmpty() || !body.isBlank();
            case FORM_DATA -> !formData.isEmpty();
            case BINARY -> binaryPath != null && !binaryPath.isBlank();
            default -> !body.isBlank();
        };
    }

    /**
     * 推断实际使用的请求体类型。
     *
     * <p>类型选了「无请求体」但编辑区里确实有内容时，按内容的形状挑一个
     * —— 否则用户粘完 JSON 忘了改下拉框，请求会不带 body 发出去，
     * 界面上还看不出任何异常。选定的类型则原样保留。
     *
     * <p><b>它与「界面上选的类型」不是一回事</b>：调用方要把「推断结果 ≠ 所选类型」
     * 这件事说出来（单一请求页会在类型工具行上提示），不能再像以前那样静默生效。
     *
     * @param declared 界面上选择的类型，可为 null
     * @param body     编辑区里的内容，可为 null
     */
    public static BodyType inferBodyType(BodyType declared, String body) {
        if (declared != null && declared != BodyType.NONE) {
            return declared;
        }
        if (body == null || body.isBlank()) {
            return BodyType.NONE;
        }
        String trimmed = body.trim();
        boolean json = (trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"));
        return json ? BodyType.JSON : BodyType.TEXT;
    }
}
