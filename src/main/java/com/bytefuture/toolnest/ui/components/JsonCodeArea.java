package com.bytefuture.toolnest.ui.components;

import javafx.scene.paint.Color;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 带 JSON 语法高亮的只读代码显示区。
 *
 * <p>基于 RichTextFX 的 {@link CodeArea} —— 它支持按字符区间着色，
 * 这是原生 {@code TextArea} 做不到的（TextArea 全文只有一个颜色）。
 *
 * <p>类型参数取 {@code Collection<String>}：RichTextFX 用「一组样式类名」
 * 表示一个区间的样式，最终由 CSS 决定颜色，因此配色写在 app.css 里。
 *
 * <p>配色参考 VS Code 浅色主题：
 * <ul>
 *   <li>键名 —— 深蓝加粗</li>
 *   <li>字符串值 —— 绿</li>
 *   <li>数字 —— 橙</li>
 *   <li>布尔 / null —— 紫加粗</li>
 *   <li>标点 —— 深灰</li>
 * </ul>
 */
public final class JsonCodeArea extends CodeArea {

    /** 样式类名，与 app.css 中的 .json-* 规则一一对应。 */
    private static final String STYLE_KEY = "json-key";
    private static final String STYLE_STRING = "json-string";
    private static final String STYLE_NUMBER = "json-number";
    private static final String STYLE_KEYWORD = "json-keyword";
    private static final String STYLE_PUNCT = "json-punct";

    /**
     * 一条正则同时匹配全部词法单元，命名捕获组区分类型。
     *
     * <p>顺序很关键（正则按最左优先匹配，先出现的分支先尝试）：
     * <ol>
     *   <li>先匹配「字符串 + 紧跟的冒号」以便识别键名 —— 键名的特征是后面跟冒号</li>
     *   <li>再匹配普通字符串（含转义）</li>
     *   <li>数字（负号、小数、指数）</li>
     *   <li>true / false / null</li>
     *   <li>标点</li>
     * </ol>
     */
    private static final Pattern TOKEN = Pattern.compile(
            "(?<KEY>\"(?:\\\\.|[^\"\\\\])*\"\\s*:)"
                    + "|(?<STRING>\"(?:\\\\.|[^\"\\\\])*\")"
                    + "|(?<NUMBER>-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)"
                    + "|(?<KEYWORD>\\btrue\\b|\\bfalse\\b|\\bnull\\b)"
                    + "|(?<PUNCT>[\\{\\}\\[\\],:])");

    public JsonCodeArea() {
        // 行号用固定宽度的右对齐 Label：数字位数变化时列宽不抖，
        // 也不会因为外层 8px 内边距把行号挤到分隔线上
        setParagraphGraphicFactory(line -> {
            javafx.scene.Node gutter = LineNumberFactory.get(this).apply(line);
            if (gutter instanceof javafx.scene.control.Label label) {
                label.setMinWidth(46);
                label.setPrefWidth(46);
                label.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
            }
            return gutter;
        });
        setEditable(false);
        setWrapText(false);
        getStyleClass().addAll("json-code-area", "mono-area");

        // 输入框保持只读，但允许选中复制；撤销历史也用不上
        getUndoManager().forgetHistory();
    }

    /**
     * 占位提示文字。
     *
     * <p>{@code CodeArea} 不是 {@code TextInputControl}，没有 {@code setPromptText}，
     * 只能通过 {@code setPlaceholder(Node)} 放一个灰色标签进去。
     */
    public void setPlaceholderText(String text) {
        javafx.scene.control.Label placeholder = new javafx.scene.control.Label(text);
        placeholder.getStyleClass().add("json-placeholder");
        placeholder.setMouseTransparent(true);
        setPlaceholder(placeholder);
    }

    /** 设置文本并立即着色。 */
    public void setJson(String json) {
        String text = json == null ? "" : json;
        replaceText(text);
        highlight();
        moveTo(0);
        getUndoManager().forgetHistory();
    }

    /** 对当前文本重新着色（文本变化后调用）。 */
    public void highlight() {
        String text = getText();
        if (text == null || text.isEmpty()) {
            // 空文本也要给一个空 span，否则 RichTextFX 会因模型不一致报错
            StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
            builder.add(Collections.emptyList(), 0);
            setStyleSpans(0, builder.create());
            return;
        }
        setStyleSpans(0, computeHighlighting(text));
    }

    /** 逐段计算样式，返回 RichTextFX 需要的 StyleSpans。 */
    private static StyleSpans<Collection<String>> computeHighlighting(String text) {
        Matcher matcher = TOKEN.matcher(text);
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        int lastEnd = 0;
        boolean matchedAny = false;

        while (matcher.find()) {
            matchedAny = true;
            // 两个匹配之间的普通文本（空白、换行、缩进）不加样式
            appendPlain(builder, lastEnd, matcher.start());

            String keyGroup = matcher.group("KEY");
            if (keyGroup != null) {
                // 键名分组把冒号也吃进来了，但冒号要按标点着色，
                // 所以拆成「引号内的键名」+「冒号」两段分别上色
                int colon = keyGroup.lastIndexOf(':');
                String keyPart = keyGroup.substring(0, colon);
                String colonPart = keyGroup.substring(colon);
                builder.add(List.of(STYLE_KEY), keyPart.length());
                builder.add(List.of(STYLE_PUNCT), colonPart.length());
            } else {
                String style;
                if (matcher.group("STRING") != null) {
                    style = STYLE_STRING;
                } else if (matcher.group("NUMBER") != null) {
                    style = STYLE_NUMBER;
                } else if (matcher.group("KEYWORD") != null) {
                    style = STYLE_KEYWORD;
                } else {
                    style = STYLE_PUNCT;
                }
                builder.add(List.of(style), matcher.end() - matcher.start());
            }
            lastEnd = matcher.end();
        }
        appendPlain(builder, lastEnd, text.length());

        if (!matchedAny) {
            // 整段都不是 JSON 词法单元时，给整段一个无样式区间
            builder = new StyleSpansBuilder<>();
            builder.add(Collections.emptyList(), text.length());
        }
        return builder.create();
    }

    /** 把区间 [from, to) 作为无样式文本追加。 */
    private static void appendPlain(StyleSpansBuilder<Collection<String>> builder,
                                    int from, int to) {
        if (to > from) {
            builder.add(Collections.emptyList(), to - from);
        }
    }

    /** 供测试或外部查询的样式类名，避免与 CSS 两处不同步。 */
    public static List<String> styleClassNames() {
        return List.of(STYLE_KEY, STYLE_STRING, STYLE_NUMBER, STYLE_KEYWORD, STYLE_PUNCT);
    }

    /** 供主题切换时参考的基准色。 */
    public static Color keyColor() {
        return Color.web("#0451a5");
    }
}
