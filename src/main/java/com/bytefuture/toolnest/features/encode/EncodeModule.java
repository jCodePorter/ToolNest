package com.bytefuture.toolnest.features.encode;

import com.bytefuture.toolnest.core.ModuleCategory;
import com.bytefuture.toolnest.ui.spi.ToolModule;
import com.bytefuture.toolnest.ui.components.UiKit;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 编解码工具：Base64 / URL / Hex 编码与解码。
 *
 * <p>演示要点：零第三方依赖，纯 JDK 实现。
 */
public class EncodeModule implements ToolModule {

    /** 具体编解码算法。 */
    private enum Mode {
        BASE64("Base64"),
        URL("URL"),
        HEX("Hex");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    @Override
    public String id() {
        return "encode";
    }

    @Override
    public String displayName() {
        return "编解码工具";
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.ENCODE;
    }

    @Override
    public String summary() {
        return "Base64、URL、Hex 三种编码的相互转换";
    }

    @Override
    public String description() {
        return "上下两栏实时互转，支持 UTF-8 与 GBK 字符集";
    }


    @Override
    public String keywords() {
        return "base64 url hex 编码 解码 转换 encode decode";
    }

    @Override
    public Node buildContent() {
        VBox root = UiKit.page("编解码工具", "支持 Base64、URL、Hex 三种编码，上下两侧实时互转。");

        ComboBox<Mode> modeBox = new ComboBox<>();
        modeBox.getItems().addAll(Mode.values());
        modeBox.getSelectionModel().selectFirst();
        modeBox.setPrefWidth(120);

        ComboBox<Charset> charsetBox = new ComboBox<>();
        charsetBox.getItems().addAll(StandardCharsets.UTF_8, Charset.forName("GBK"));
        charsetBox.getSelectionModel().selectFirst();
        charsetBox.setPrefWidth(120);

        HBox options = new HBox(10, new Label("模式"), modeBox, new Label("字符集"), charsetBox);
        options.setAlignment(Pos.CENTER_LEFT);

        TextArea plainArea = new TextArea();
        plainArea.setPromptText("输入原文…");
        plainArea.getStyleClass().add("mono-area");
        plainArea.setWrapText(true);
        VBox.setVgrow(plainArea, Priority.ALWAYS);

        TextArea codedArea = new TextArea();
        codedArea.setPromptText("输入编码后的文本…");
        codedArea.getStyleClass().add("mono-area");
        codedArea.setWrapText(true);
        VBox.setVgrow(codedArea, Priority.ALWAYS);

        Label errorLabel = UiKit.error("");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        // 防止双向监听的无限递归
        boolean[] updating = {false};

        Runnable encode = () -> {
            if (updating[0]) {
                return;
            }
            updating[0] = true;
            try {
                String result = encodeText(plainArea.getText(), modeBox.getValue(),
                        charsetBox.getValue());
                codedArea.setText(result);
                hideError(errorLabel);
            } catch (Exception ex) {
                showError(errorLabel, "编码失败：" + ex.getMessage());
            } finally {
                updating[0] = false;
            }
        };

        Runnable decode = () -> {
            if (updating[0]) {
                return;
            }
            updating[0] = true;
            try {
                String result = decodeText(codedArea.getText(), modeBox.getValue(),
                        charsetBox.getValue());
                plainArea.setText(result);
                hideError(errorLabel);
            } catch (Exception ex) {
                showError(errorLabel, "解码失败：" + ex.getMessage());
            } finally {
                updating[0] = false;
            }
        };

        plainArea.textProperty().addListener((o, old, now) -> encode.run());
        codedArea.textProperty().addListener((o, old, now) -> decode.run());
        modeBox.setOnAction(e -> encode.run());
        charsetBox.setOnAction(e -> encode.run());

        // 编解码是实时互转的，没有「执行」按钮 —— 主操作让位给交换，
        // 工具栏右侧保留复制结果
        Button swapBtn = UiKit.primaryButton("交换上下");
        Button clearBtn = UiKit.secondaryButton("清空");
        swapBtn.setOnAction(e -> {
            String tmp = plainArea.getText();
            updating[0] = true;
            plainArea.setText(codedArea.getText());
            codedArea.setText(tmp);
            updating[0] = false;
            encode.run();
        });
        clearBtn.setOnAction(e -> {
            updating[0] = true;
            plainArea.clear();
            codedArea.clear();
            updating[0] = false;
            hideError(errorLabel);
        });

        HBox toolbar = new HBox(8, swapBtn, clearBtn, UiKit.spacer(),
                UiKit.caption("输入任一栏，另一栏实时跟着变"),
                UiKit.copyButton(codedArea, "复制编码结果"));
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox plainBox = new VBox(6, fieldLabel("原文"), plainArea);
        VBox.setVgrow(plainArea, Priority.ALWAYS);
        VBox codedBox = new VBox(6, fieldLabel("编码结果"), codedArea);
        VBox.setVgrow(codedArea, Priority.ALWAYS);

        SplitPane split = new SplitPane(plainBox, codedBox);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.5);
        VBox.setVgrow(split, Priority.ALWAYS);

        root.getChildren().addAll(options, toolbar, split, errorLabel);
        return root;
    }

    private Label fieldLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("field-label");
        return label;
    }

    private String encodeText(String text, Mode mode, Charset charset) {
        String source = text == null ? "" : text;
        if (source.isEmpty()) {
            return "";
        }
        return switch (mode) {
            case BASE64 -> Base64.getEncoder().encodeToString(source.getBytes(charset));
            case URL -> URLEncoder.encode(source, charset);
            case HEX -> toHex(source.getBytes(charset));
        };
    }

    private String decodeText(String text, Mode mode, Charset charset) {
        String source = text == null ? "" : text.trim();
        if (source.isEmpty()) {
            return "";
        }
        return switch (mode) {
            case BASE64 -> new String(Base64.getDecoder().decode(source), charset);
            case URL -> URLDecoder.decode(source, charset);
            case HEX -> new String(fromHex(source), charset);
        };
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    private byte[] fromHex(String hex) {
        String cleaned = hex.replaceAll("\\s+", "");
        if (cleaned.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex 长度必须为偶数");
        }
        byte[] result = new byte[cleaned.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(cleaned.charAt(i * 2), 16);
            int low = Character.digit(cleaned.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("包含非法的 Hex 字符");
            }
            result[i] = (byte) ((high << 4) + low);
        }
        return result;
    }

    private void showError(Label label, String message) {
        label.setText(message);
        label.setVisible(true);
        label.setManaged(true);
    }

    private void hideError(Label label) {
        label.setText("");
        label.setVisible(false);
        label.setManaged(false);
    }
}
