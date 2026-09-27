package com.bytefuture.toolnest.modules.file;

import com.bytefuture.toolnest.core.ModuleCategory;
import com.bytefuture.toolnest.core.ToolModule;
import com.bytefuture.toolnest.ui.UiKit;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件哈希校验工具。
 *
 * <p>两类输入：
 * <ol>
 *   <li><b>文本</b>：直接输入字符串算摘要，用于快速校验一小段内容</li>
 *   <li><b>文件</b>：选择或拖入文件，流式计算摘要 —— 拖动多选时批量计算</li>
 * </ol>
 *
 * <p>关键设计：
 * <ul>
 *   <li><b>后台线程计算</b>：哈希大文件是纯 CPU + IO 操作，放在 JavaFX 线程会
 *       卡死界面。这里用 {@link Task} 跑在后台，通过 {@code messageProperty}
 *       回传进度文案，界面始终可响应</li>
 *   <li><b>一次性算全部算法</b>：校验时经常需要在 MD5 / SHA-256 之间切换对比，
 *       与其切换一次算一次，不如一次遍历把常用摘要都算出来，避免重复读盘</li>
 * </ul>
 */
public class FileHashModule implements ToolModule {

    /** 一次遍历同时计算的算法集合，避免反复读同一文件。 */
    private static final HashOps.Algorithm[] ALL_ALGORITHMS = {
            HashOps.Algorithm.MD5,
            HashOps.Algorithm.SHA1,
            HashOps.Algorithm.SHA256,
            HashOps.Algorithm.SHA512
    };

    /** 单个文件的全部摘要结果。 */
    private record MultiHash(String name, long size, List<String> digests) {
    }

    private final List<MultiHash> results = new ArrayList<>();

    private TextArea outputArea;
    private Label statusLabel;
    private Button pickButton;
    private Button clearButton;
    private boolean running;

    @Override
    public String id() {
        return "file-hash";
    }

    @Override
    public String displayName() {
        return "文件哈希";
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.FILE;
    }

    @Override
    public String summary() {
        return "计算文件或文本的 MD5 / SHA-1 / SHA-256 / SHA-512 摘要";
    }

    @Override
    public String description() {
        return "支持拖入多个文件批量计算，大文件采用流式读取并显示进度，界面不会卡顿";
    }

    @Override
    public String icon() {
        return "\u25A4";
    }

    @Override
    public String keywords() {
        return "hash 哈希 md5 sha1 sha256 sha512 摘要 校验 指纹 文件校验";
    }

    @Override
    public Node buildContent() {
        VBox root = UiKit.page("文件哈希", "计算文件的 MD5 / SHA-1 / SHA-256 / SHA-512 摘要。");

        // ---------- 文本摘要 ----------
        TextField textField = new TextField();
        textField.setPromptText("输入要计算摘要的文本…");

        ToggleGroup upperGroup = new ToggleGroup();
        RadioButton lowerRadio = new RadioButton("小写");
        RadioButton upperRadio = new RadioButton("大写");
        lowerRadio.setToggleGroup(upperGroup);
        upperRadio.setToggleGroup(upperGroup);
        lowerRadio.setSelected(true);
        HBox caseBox = new HBox(12, lowerRadio, upperRadio);
        caseBox.setAlignment(Pos.CENTER_LEFT);

        ComboBox<HashOps.Algorithm> algoBox = new ComboBox<>();
        algoBox.getItems().addAll(HashOps.Algorithm.values());
        algoBox.getSelectionModel().select(HashOps.Algorithm.SHA256);
        algoBox.setPrefWidth(140);

        Label algoNote = UiKit.caption(HashOps.Algorithm.SHA256.note());
        algoBox.setOnAction(e -> {
            HashOps.Algorithm a = algoBox.getValue();
            algoNote.setText(a == null ? "" : a.note());
        });

        TextField textResult = new TextField();
        textResult.setEditable(false);
        textResult.getStyleClass().add("mono-area");
        textResult.setPromptText("摘要结果");

        Button calcTextBtn = UiKit.primaryButton("计算文本摘要");
        calcTextBtn.setOnAction(e -> {
            HashOps.Algorithm algo = algoBox.getValue() == null
                    ? HashOps.Algorithm.SHA256 : algoBox.getValue();
            textResult.setText(HashOps.ofText(textField.getText(), algo,
                    upperRadio.isSelected()));
            setStatus("文本摘要已计算（" + algo.label() + "）");
        });

        // 文本摘要实时跟随输入，减少一次点击
        textField.textProperty().addListener((o, old, now) -> {
            if (now == null || now.isEmpty()) {
                textResult.clear();
                return;
            }
            HashOps.Algorithm algo = algoBox.getValue() == null
                    ? HashOps.Algorithm.SHA256 : algoBox.getValue();
            textResult.setText(HashOps.ofText(now, algo, upperRadio.isSelected()));
        });
        upperGroup.selectedToggleProperty().addListener((o, old, now) -> {
            String t = textField.getText();
            if (t != null && !t.isEmpty()) {
                HashOps.Algorithm algo = algoBox.getValue() == null
                        ? HashOps.Algorithm.SHA256 : algoBox.getValue();
                textResult.setText(HashOps.ofText(t, algo, upperRadio.isSelected()));
            }
        });

        HBox textResultRow = new HBox(8, textResult, UiKit.copyButton(textResult, "复制摘要"));
        HBox.setHgrow(textResult, Priority.ALWAYS);
        textResultRow.setAlignment(Pos.CENTER_LEFT);

        VBox textCard = UiKit.section("文本摘要", null,
                UiKit.field("文本", textField),
                new HBox(12, new Label("算法"), algoBox, new Label("输出"), caseBox) {{
                    setAlignment(Pos.CENTER_LEFT);
                }},
                algoNote,
                UiKit.buttonRow(calcTextBtn),
                UiKit.field("结果", textResultRow));

        // ---------- 文件摘要 ----------
        outputArea = UiKit.readonlyArea();
        outputArea.setPrefRowCount(12);
        outputArea.setPromptText("选择或拖入文件后，各算法的摘要会显示在这里");
        outputArea.getStyleClass().add("mono-area");
        VBox.setVgrow(outputArea, Priority.ALWAYS);

        // 用结果区本身当作拖放目标，符合「拖到看得见结果的地方」的直觉
        outputArea.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        outputArea.setOnDragDropped(e -> {
            Dragboard board = e.getDragboard();
            if (board.hasFiles()) {
                List<Path> paths = new ArrayList<>();
                for (File f : board.getFiles()) {
                    paths.add(f.toPath());
                }
                startHashing(paths);
                e.setDropCompleted(true);
            }
            e.consume();
        });

        pickButton = UiKit.secondaryButton("选择文件…");
        pickButton.setOnAction(e -> chooseFiles());

        clearButton = UiKit.secondaryButton("清空结果");
        clearButton.setOnAction(e -> {
            results.clear();
            outputArea.clear();
            setStatus("已清空");
        });

        Button copyAllBtn = UiKit.secondaryButton("复制全部结果");
        copyAllBtn.setOnAction(e -> {
            UiKit.copyToClipboard(outputArea.getText());
            setStatus("结果已复制到剪贴板");
        });

        statusLabel = UiKit.caption("等待选择文件");

        ProgressBar progress = new ProgressBar(0);
        progress.setPrefWidth(220);
        progress.setVisible(false);
        progress.setManaged(false);

        HBox fileToolbar = new HBox(8, pickButton, clearButton, copyAllBtn,
                UiKit.spacer(), progress);
        fileToolbar.setAlignment(Pos.CENTER_LEFT);

        Label dropHint = UiKit.caption("提示：可直接把文件拖到下方结果区，支持一次拖入多个。");

        VBox fileCard = UiKit.section("文件摘要", null,
                fileToolbar, dropHint, outputArea, statusLabel);

        root.getChildren().addAll(textCard, fileCard);
        return root;
    }

    // ==================== 文件选择与计算 ====================

    private void chooseFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择要计算哈希的文件");
        List<File> files = chooser.showOpenMultipleDialog(
                outputArea.getScene() == null ? null : outputArea.getScene().getWindow());
        if (files == null || files.isEmpty()) {
            return;
        }
        List<Path> paths = new ArrayList<>();
        for (File f : files) {
            paths.add(f.toPath());
        }
        startHashing(paths);
    }

    /**
     * 后台批量计算哈希。
     *
     * <p>每个文件只读一遍，同时喂给全部算法 —— 校验场景经常需要在不同算法间
     * 切换比对，一次算全比切换一次算一次省掉大量重复 IO。
     */
    private void startHashing(List<Path> paths) {
        if (running) {
            setStatus("正在计算中，请等待当前任务完成");
            return;
        }
        if (paths.isEmpty()) {
            return;
        }
        running = true;
        pickButton.setDisable(true);
        setStatus("正在计算 " + paths.size() + " 个文件…");

        Task<List<MultiHash>> task = new Task<>() {
            @Override
            protected List<MultiHash> call() {
                List<MultiHash> list = new ArrayList<>();
                for (int i = 0; i < paths.size(); i++) {
                    Path path = paths.get(i);
                    updateMessage("正在计算（" + (i + 1) + "/" + paths.size() + "）："
                            + path.getFileName());
                    try {
                        list.add(hashOne(path));
                    } catch (Exception ex) {
                        // 单个文件失败不应中断整批：记录一条失败结果继续
                        list.add(new MultiHash(
                                path.getFileName() == null ? path.toString()
                                        : path.getFileName().toString(),
                                -1, List.of("读取失败：" + ex.getMessage())));
                    }
                }
                return list;
            }
        };

        task.messageProperty().addListener((o, old, now) -> {
            if (now != null) {
                setStatus(now);
            }
        });

        task.setOnSucceeded(e -> {
            results.clear();
            results.addAll(task.getValue());
            renderResults();
            running = false;
            pickButton.setDisable(false);
            setStatus("完成，共 " + results.size() + " 个文件");
        });

        task.setOnFailed(e -> {
            running = false;
            pickButton.setDisable(false);
            Throwable ex = task.getException();
            setStatus("计算失败：" + (ex == null ? "未知错误" : ex.getMessage()));
        });

        Thread thread = new Thread(task, "file-hash");
        thread.setDaemon(true);
        thread.start();
    }

    /** 单文件：一次读取，同时算出全部算法的摘要。 */
    private MultiHash hashOne(Path path) throws Exception {
        java.security.MessageDigest[] digests = new java.security.MessageDigest[
                ALL_ALGORITHMS.length];
        for (int i = 0; i < ALL_ALGORITHMS.length; i++) {
            digests[i] = java.security.MessageDigest.getInstance(
                    ALL_ALGORITHMS[i].jcaName());
        }

        long size = 0;
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                for (java.security.MessageDigest d : digests) {
                    d.update(buffer, 0, read);
                }
                size += read;
            }
        }

        List<String> hexList = new ArrayList<>(digests.length);
        for (java.security.MessageDigest d : digests) {
            hexList.add(java.util.HexFormat.of().formatHex(d.digest()));
        }
        String name = path.getFileName() == null ? path.toString()
                : path.getFileName().toString();
        return new MultiHash(name, size, hexList);
    }

    /** 把结果渲染成等宽对整齐的文本，便于直接比对或粘贴。 */
    private void renderResults() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            MultiHash r = results.get(i);
            if (i > 0) {
                sb.append('\n');
            }
            sb.append("文件：").append(r.name()).append('\n');
            sb.append("大小：").append(r.size() < 0 ? "未知"
                    : HashOps.humanSize(r.size())).append('\n');
            for (int j = 0; j < ALL_ALGORITHMS.length && j < r.digests().size(); j++) {
                sb.append(String.format("%-8s", ALL_ALGORITHMS[j].label()))
                        .append(r.digests().get(j)).append('\n');
            }
        }
        outputArea.setText(sb.toString());
    }

    private void setStatus(String text) {
        Platform.runLater(() -> statusLabel.setText(text));
    }
}
