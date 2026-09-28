package com.bytefuture.toolnest.features.json;

import com.bytefuture.toolnest.core.ModuleCategory;
import com.bytefuture.toolnest.ui.spi.ToolModule;
import com.bytefuture.toolnest.ui.components.JsonCodeArea;
import com.bytefuture.toolnest.ui.components.UiKit;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.internal.Streams;
import com.google.gson.stream.JsonWriter;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.io.StringWriter;

/**
 * JSON 格式化 / 压缩 / 校验。
 *
 * <p>演示要点：新增功能引入第三方库（Gson）时，只需在 pom.xml 加依赖，
 * 注册流程与其它模块完全一致。
 */
public class JsonFormatModule implements ToolModule {

    @Override
    public String id() {
        return "json-format";
    }

    @Override
    public String displayName() {
        return "JSON 格式化";
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.FORMAT;
    }

    @Override
    public String summary() {
        return "格式化、压缩与校验 JSON，支持转义与反转义";
    }

    @Override
    public String description() {
        return "可自定义缩进风格，语法错误会给出提示";
    }


    @Override
    public String keywords() {
        return "json 格式化 format 压缩 校验 美化";
    }

    @Override
    public Node buildContent() {
        VBox root = UiKit.page("JSON 格式化", "格式化、压缩与校验 JSON，支持转义与反转义。");

        TextArea input = new TextArea();
        input.setPromptText("在此粘贴 JSON…");
        input.getStyleClass().add("mono-area");
        input.setWrapText(false);
        VBox.setVgrow(input, Priority.ALWAYS);

        JsonCodeArea output = new JsonCodeArea();
        output.setPlaceholderText("结果将显示在这里");
        VBox.setVgrow(output, Priority.ALWAYS);

        // 缩进选项
        ComboBox<String> indentBox = new ComboBox<>();
        indentBox.getItems().addAll("2 空格", "4 空格", "制表符");
        indentBox.getSelectionModel().selectFirst();
        indentBox.setPrefWidth(120);

        // 主操作只给「格式化」；压缩、转义、反转义都是次要动作，用描边按钮；
        // 「清空」是可逆的辅助动作，用静默按钮，不进主视觉层
        Button formatBtn = UiKit.primaryButton("格式化");
        Button minifyBtn = UiKit.secondaryButton("压缩");
        Button escapeBtn = UiKit.secondaryButton("转义");
        Button unescapeBtn = UiKit.secondaryButton("反转义");
        Button clearBtn = UiKit.quietButton("清空");

        // 「复制结果」贴着输出区，而不是掉在整页底部 ——
        // 它的作用对象就是右边那块结果，离得越近指代越清楚
        Button copyBtn = UiKit.quietButton("复制结果");

        Label status = UiKit.caption("");
        Label errorLabel = UiKit.error("");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        HBox toolbar = new HBox(8, formatBtn, minifyBtn, escapeBtn, unescapeBtn, clearBtn,
                UiKit.spacer(), new Label("缩进"), indentBox);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        HBox bottomBar = new HBox(8, status);
        bottomBar.setAlignment(Pos.CENTER_LEFT);

        formatBtn.setOnAction(e -> {
            lastAction[0] = Action.FORMAT;
            run(input, output, status, errorLabel,
                    () -> pretty(input.getText(), indentOf(indentBox)), true);
        });
        minifyBtn.setOnAction(e -> {
            lastAction[0] = Action.MINIFY;
            run(input, output, status, errorLabel,
                    () -> compact(input.getText()), true);
        });
        escapeBtn.setOnAction(e -> {
            lastAction[0] = Action.ESCAPE;
            output.setJson(toEscaped(input.getText()));
            setStatus(status, errorLabel, "已转义", true);
        });
        unescapeBtn.setOnAction(e -> {
            lastAction[0] = Action.UNESCAPE;
            output.setJson(fromEscaped(input.getText()));
            setStatus(status, errorLabel, "已反转义", true);
        });
        clearBtn.setOnAction(e -> {
            input.clear();
            output.setJson("");
            setStatus(status, errorLabel, "", true);
        });
        copyBtn.setOnAction(e -> {
            UiKit.copyToClipboard(output.getText());
            setStatus(status, errorLabel, "已复制到剪贴板", true);
        });

        // 切换缩进时自动重新格式化。
        // 只在「当前输出就是格式化结果」时触发 —— 压缩/转义的结果套用缩进没有意义，
        // 所以用 lastAction 记住上一次的操作类型。
        indentBox.valueProperty().addListener((obs, oldIndent, newIndent) -> {
            if (newIndent == null || newIndent.equals(oldIndent)) {
                return;
            }
            if (lastAction[0] == Action.FORMAT && !input.getText().isBlank()) {
                run(input, output, status, errorLabel,
                        () -> pretty(input.getText(), indentOf(indentBox)), true);
                setStatus(status, errorLabel, "已按「" + newIndent + "」重新格式化", true);
            }
        });

        VBox leftBox = new VBox(6, label("输入"), input);
        VBox.setVgrow(input, Priority.ALWAYS);

        HBox outputHead = new HBox(8, label("输出"), UiKit.spacer(), copyBtn);
        outputHead.setAlignment(Pos.CENTER_LEFT);
        VBox rightBox = new VBox(6, outputHead, output);
        VBox.setVgrow(output, Priority.ALWAYS);

        SplitPane split = new SplitPane(leftBox, rightBox);
        split.setDividerPositions(0.5);
        VBox.setVgrow(split, Priority.ALWAYS);

        root.getChildren().addAll(toolbar, split, bottomBar, errorLabel);
        return root;
    }

    /** 上一次执行的操作类型，决定切换缩进时是否自动重排。 */
    private enum Action {FORMAT, MINIFY, ESCAPE, UNESCAPE}

    private final Action[] lastAction = {Action.FORMAT};

    private Label label(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("field-label");
        return label;
    }

    private String indentOf(ComboBox<String> box) {
        return switch (box.getSelectionModel().getSelectedIndex()) {
            case 1 -> "    ";
            case 2 -> "\t";
            default -> "  ";
        };
    }

    /** 统一处理「解析 -> 输出」流程与错误展示。 */
    private void run(TextArea input, JsonCodeArea output, Label status, Label errorLabel,
                     java.util.function.Supplier<String> action, boolean highlight) {
        String text = input.getText();
        if (text == null || text.isBlank()) {
            output.setJson("");
            setStatus(status, errorLabel, "请输入 JSON 内容", false);
            return;
        }
        try {
            String result = action.get();
            output.setJson(highlight ? result : result);
            setStatus(status, errorLabel, "处理成功", true);
        } catch (JsonSyntaxException ex) {
            output.setJson("");
            setStatus(status, errorLabel, "JSON 语法错误：" + ex.getMessage(), false);
        } catch (Exception ex) {
            output.setJson("");
            setStatus(status, errorLabel, "处理失败：" + ex.getMessage(), false);
        }
    }

    private void setStatus(Label status, Label errorLabel, String message, boolean ok) {
        status.setText(ok ? message : "");
        errorLabel.setText(ok ? "" : message);
        errorLabel.setVisible(!ok);
        errorLabel.setManaged(!ok);
    }

    /**
     * 格式化为带缩进的美化文本。
     *
     * <p>用 {@link JsonWriter#setIndent(String)} 让 Gson 直接按目标缩进输出，
     * 不做「先生成两空格再整体替换」的后处理。
     *
     * <p>后处理方案有个隐患：它只能靠「行首空格数 ÷ 2」反推层级，一旦某个
     * 字符串值内部含换行（JSON 里写作 {@code \n} 转义，但 {@code Gson} 的
     * pretty 输出在某些配置下会真的换行），该行会被误判成结构行而套错缩进。
     * 交给 JsonWriter 则缩进由写出器内部维护，与内容无关。
     */
    private String pretty(String json, String indent) {
        JsonElement element = JsonParser.parseString(json);
        StringWriter buffer = new StringWriter();
        try (JsonWriter writer = new JsonWriter(buffer)) {
            writer.setIndent(indent);
            writer.setSerializeNulls(true);
            Streams.write(element, writer);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("格式化失败：" + ex.getMessage(), ex);
        }
        return buffer.toString();
    }

    /** 压缩为单行。 */
    private String compact(String json) {
        return new Gson().toJson(JsonParser.parseString(json));
    }

    /** 转成 JSON 字符串字面量（带外层引号）。 */
    private String toEscaped(String text) {
        return new Gson().toJson(text == null ? "" : text);
    }

    /** 去掉 JSON 字符串字面量的转义。 */
    private String fromEscaped(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
            return new Gson().fromJson(trimmed, String.class);
        }
        return trimmed;
    }
}
