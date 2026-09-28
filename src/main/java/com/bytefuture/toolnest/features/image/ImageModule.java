package com.bytefuture.toolnest.features.image;

import com.bytefuture.toolnest.core.ModuleCategory;
import com.bytefuture.toolnest.ui.spi.ToolModule;
import com.bytefuture.toolnest.ui.components.UiKit;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 图片处理工具：格式转换 / 等比缩放 / 裁剪 / 旋转。
 *
 * <p>采用「左侧原图预览 + 右侧参数与结果」的两栏布局，与项目内
 * 文本处理、JSON 格式化保持一致的「输入在左、结果在右」心智。
 *
 * <h2>关键设计</h2>
 * <ul>
 *   <li><b>所见即所得预览</b>：调整参数后点「预览」立即看到效果，
 *       确认没问题再「导出」—— 避免批量处理完才发现参数填错</li>
 *   <li><b>批量处理</b>：支持拖入多个文件，参数对所有文件统一生效，
 *       输出到指定目录，文件名沿用原名只换扩展名</li>
 *   <li><b>后台执行</b>：图片解码/编码是 CPU 密集操作，放在后台线程，
 *       界面不冻结，并显示逐个文件的进度</li>
 *   <li><b>透明处理透明</b>：转 JPEG 时自动合成白底（JPEG 无 alpha 通道），
 *       并在界面明确提示这件事，不让用户困惑「为什么背景变成白的了」</li>
 * </ul>
 *
 * <p>原图永不被覆盖：批量导出要求选择输出目录，默认文件名加后缀区分。
 */
public class ImageModule implements ToolModule {

    /** 当前载入的原图与其来源路径。 */
    private BufferedImage original;
    private Path originalPath;

    /** 预览用的显示尺寸上限，避免超大图撑破界面。 */
    private static final double PREVIEW_MAX = 520;

    /** 预览区空态的说明文案；拖入格式不支持时会被临时替换，重置后回到这句。 */
    private static final String PLACEHOLDER_HINT =
            "点击「选择图片…」，或把图片文件直接拖到这里\n"
                    + "支持 PNG / JPG / BMP / GIF / TIFF";

    /** 拖入不支持格式时的说明文案。 */
    private static final String PLACEHOLDER_HINT_REJECT =
            "这些文件不是支持的图片格式\n支持 PNG / JPG / BMP / GIF / TIFF";

    private ImageView previewView;
    private VBox previewPlaceholder;
    private Label imageInfoLabel;
    private Label statusLabel;
    private Button exportBtn;
    private Button batchBtn;

    // 参数控件
    private ComboBox<ImageOps.Format> formatBox;
    private Label formatNote;
    private TextField widthField;
    private TextField heightField;
    private CheckBox keepRatioBox;
    private Slider qualitySlider;
    private HBox qualityRow;
    private Label qualityValue;
    private Label qualityNote;
    private CheckBox cropBox;
    private TextField cropX, cropY, cropW, cropH;
    private ComboBox<String> rotateBox;
    private CheckBox flipBox;

    /** 当前选中的待批量处理文件。 */
    private final List<Path> batchFiles = new ArrayList<>();

    private boolean busy;

    @Override
    public String id() {
        return "image-tools";
    }

    @Override
    public String displayName() {
        return "图片处理";
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.IMAGE;
    }

    @Override
    public String summary() {
        return "格式转换、等比缩放、裁剪、旋转，支持批量处理";
    }

    @Override
    public String description() {
        return "支持 PNG / JPG / BMP / GIF / TIFF 读取，可拖入多张图片统一处理";
    }


    @Override
    public String keywords() {
        return "image 图片 图像 转换 格式 缩放 压缩 裁剪 旋转 批量 png jpg bmp gif";
    }

    @Override
    public Node buildContent() {
        VBox root = UiKit.page("图片处理", "格式转换、等比缩放、裁剪、旋转，支持批量。");

        // ==================== 左栏：原图预览 ====================
        previewView = new ImageView();
        previewView.setPreserveRatio(true);
        previewView.setFitWidth(PREVIEW_MAX);
        previewView.setFitHeight(PREVIEW_MAX);
        previewView.setSmooth(true);

        StackPane previewHolder = new StackPane(previewView);
        previewHolder.getStyleClass().add("image-preview-holder");
        previewHolder.setMinHeight(300);

        // 空态：图标 + 主文案 + 次文案。此前是预览框正中一句「尚未载入图片」，
        // 框下方再补一条「未载入」和一条提示 —— 三处说同一件事，还都不可点击。
        previewPlaceholder = UiKit.emptyState("image", "尚未载入图片", PLACEHOLDER_HINT);
        previewHolder.getChildren().add(previewPlaceholder);

        // 拖放支持。
        // 注意 setOnDragExited 置回 false 不能省 —— 否则把文件拖进来又拖出去，
        // 高亮会一直留着，看起来像「界面卡住了」。
        previewHolder.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
                UiKit.setDragOver(previewHolder, true);
            }
            e.consume();
        });
        previewHolder.setOnDragExited(e -> {
            UiKit.setDragOver(previewHolder, false);
            e.consume();
        });
        previewHolder.setOnDragDropped(e -> {
            UiKit.setDragOver(previewHolder, false);
            Dragboard board = e.getDragboard();
            if (board.hasFiles()) {
                List<Path> imgs = new ArrayList<>();
                for (File f : board.getFiles()) {
                    Path p = f.toPath();
                    if (isSupported(p)) {
                        imgs.add(p);
                    }
                }
                if (imgs.isEmpty()) {
                    // 反馈落在预览框正中的空态说明上，不额外弹一条会挡住它的文字
                    UiKit.setEmptyStateHint(previewPlaceholder, PLACEHOLDER_HINT_REJECT);
                } else {
                    loadImage(imgs.get(0));
                    // 其余文件自动进入批量队列
                    batchFiles.clear();
                    batchFiles.addAll(imgs.subList(1, imgs.size()));
                    refreshBatchHint();
                }
                e.setDropCompleted(true);
            }
            e.consume();
        });

        // 「选择图片」只是取输入，不是把输入提交出去 —— 入口类动作一律描边。
        // 本页唯一的主色实底留给右下角的「导出图片」，那是用户来这一屏真正要做的事。
        Button openBtn = UiKit.secondaryButton("选择图片…");
        openBtn.setOnAction(e -> chooseImage());

        Button batchAddBtn = UiKit.secondaryButton("添加批量文件…");
        batchAddBtn.setOnAction(e -> chooseBatchFiles());

        imageInfoLabel = UiKit.caption("");

        VBox leftBox = new VBox(10,
                UiKit.buttonRow(openBtn, batchAddBtn),
                previewHolder,
                imageInfoLabel);

        // ==================== 右栏：处理参数 ====================
        formatBox = new ComboBox<>();
        formatBox.getItems().addAll(ImageOps.Format.values());
        formatBox.getSelectionModel().select(ImageOps.Format.PNG);
        formatBox.setPrefWidth(180);
        formatNote = UiKit.caption(ImageOps.Format.PNG.note());
        // 状态同步统一走 syncFormatUi()，不要在这里另写一份判断
        formatBox.setOnAction(e -> syncFormatUi());

        widthField = new TextField();
        widthField.setPromptText("宽");
        widthField.setPrefWidth(90);
        heightField = new TextField();
        heightField.setPromptText("高");
        heightField.setPrefWidth(90);

        keepRatioBox = new CheckBox("等比缩放");
        keepRatioBox.setSelected(true);
        keepRatioBox.setTooltip(new Tooltip(
                "勾选：按较小比例缩放，保持原图比例不变形\n"
                        + "取消：拉伸到精确宽高，可能变形"));

        HBox sizeRow = new HBox(8, widthField, new Label("×"), heightField,
                keepRatioBox);
        sizeRow.setAlignment(Pos.CENTER_LEFT);

        Label sizeHint = UiKit.caption("只填一边 → 另一边自动按比例算；两边都填 → 等比取较小值");

        qualitySlider = new Slider(0.05, 1.0, 0.85);
        qualitySlider.setPrefWidth(200);
        qualityValue = new Label("85%");
        qualityValue.getStyleClass().add("result-value");
        qualitySlider.valueProperty().addListener((o, old, now) ->
                qualityValue.setText(Math.round(now.doubleValue() * 100) + "%"));

        qualityRow = new HBox(10, qualitySlider, qualityValue);
        qualityRow.getStyleClass().add("quality-row");
        qualityRow.setAlignment(Pos.CENTER_LEFT);

        // 只有在「输出 JPEG」时质量才有意义。光把滑块灰掉还不够 ——
        // 用户会以为是自己没找对地方，所以这里把「为什么不能调」明写出来。
        qualityNote = UiKit.caption("");

        cropBox = new CheckBox("启用裁剪");
        cropX = smallField("x");
        cropY = smallField("y");
        cropW = smallField("宽");
        cropH = smallField("高");
        HBox cropInputs = new HBox(6, cropX, cropY, cropW, cropH);
        cropInputs.setAlignment(Pos.CENTER_LEFT);
        cropInputs.setDisable(true);
        cropBox.selectedProperty().addListener((o, old, now) ->
                cropInputs.setDisable(!now));

        rotateBox = new ComboBox<>();
        rotateBox.getItems().addAll("不旋转", "顺时针 90°", "顺时针 180°", "顺时针 270°");
        rotateBox.getSelectionModel().selectFirst();
        rotateBox.setPrefWidth(150);
        rotateBox.setMinWidth(150);

        flipBox = new CheckBox("水平翻转");

        HBox transformRow = new HBox(12, rotateBox, flipBox);
        transformRow.setAlignment(Pos.CENTER_LEFT);

        // ---------- 预览 / 导出 ----------
        Button previewBtn = UiKit.secondaryButton("预览效果");
        previewBtn.setOnAction(e -> applyPreview());

        Button resetBtn = UiKit.secondaryButton("重置参数");
        resetBtn.setOnAction(e -> resetParams());

        exportBtn = UiKit.primaryButton("导出图片");
        exportBtn.setOnAction(e -> exportSingle());

        batchBtn = UiKit.secondaryButton("批量处理");
        batchBtn.setOnAction(e -> runBatch());

        statusLabel = UiKit.caption("等待载入图片");

        HBox actionRow = new HBox(8, previewBtn, resetBtn, UiKit.spacer(),
                batchBtn, exportBtn);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        VBox paramsCard = UiKit.section("处理参数", null,
                UiKit.field("输出格式", formatBox),
                formatNote,
                UiKit.field("尺寸", sizeRow),
                sizeHint,
                UiKit.field("JPEG 质量", qualityRow),
                qualityNote,
                new Separator(),
                UiKit.field("裁剪", new VBox(6, cropBox, cropInputs)),
                UiKit.field("旋转 / 翻转", transformRow),
                new Separator(),
                actionRow,
                statusLabel);

        ScrollPane rightScroll = new ScrollPane(paramsCard);
        rightScroll.setFitToWidth(true);
        rightScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        rightScroll.getStyleClass().add("image-param-scroll");

        VBox.setVgrow(rightScroll, Priority.ALWAYS);

        SplitPane split = new SplitPane(leftBox, rightScroll);
        split.setDividerPositions(0.55);
        VBox.setVgrow(split, Priority.ALWAYS);

        root.getChildren().add(split);

        // 所有控件都建好之后同步一次：默认输出 PNG，质量项应呈现「不生效」
        syncFormatUi();
        return root;
    }

    /**
     * 同步「随输出格式变化」的控件状态。
     *
     * <p>必须由 onAction 之外的路径也能触发。<b>{@code ComboBox} 只有用户交互才派发
     * ActionEvent</b>，程序化地 {@code getSelectionModel().select(...)} 不会 ——
     * 「重置参数」正是后者，因此早先重置回 PNG 之后，JPEG 质量滑块仍留在可拖状态，
     * 用户会拿一个对输出毫无影响的参数去调。这类「状态只在一条路径上更新」的缺陷
     * 不会报错，只会静默地给出错误暗示。
     */
    private void syncFormatUi() {
        ImageOps.Format format = formatBox.getValue();
        boolean jpeg = format == ImageOps.Format.JPEG;

        if (formatNote != null) {
            formatNote.setText(format == null ? "" : format.note());
        }
        if (qualityRow != null) {
            // 整行一起禁用。JavaFX 的 disabled 会向下传播到滑块与数值，
            // 因此 CSS 上一条 .quality-row:disabled 就能让它们整体降级。
            // 只 disable 滑块是不够的：AtlantaFX 的禁用滑块仍是主色拇指，
            // 与启用态几乎分辨不出，用户看不出「这个参数根本不起作用」。
            qualityRow.setDisable(!jpeg);
        }
        if (qualityNote != null) {
            qualityNote.setText(jpeg
                    ? "有损压缩质量：越高越清晰，文件也越大"
                    : "当前输出 " + (format == null ? "该格式" : format.imageIoName())
                            + "，无损或不带质量参数 —— 此项不生效");
        }
    }

    // ==================== 载入 ====================

    private TextField smallField(String prompt) {
        TextField field = new TextField();
        field.setPromptText(prompt);
        field.setPrefWidth(68);
        return field;
    }

    private boolean isSupported(Path path) {
        return ImageOps.detectFormat(path.getFileName().toString()) != null
                || hasReadableExtension(path);
    }

    /** JDK 能读但不在输出格式表里的（如 TIFF），也不该被拖放过滤掉。 */
    private boolean hasReadableExtension(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".tif") || name.endsWith(".tiff");
    }

    private void chooseImage() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择图片");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "图片文件", "*.png", "*.jpg", "*.jpeg", "*.bmp", "*.gif", "*.tif", "*.tiff"));
        File file = chooser.showOpenDialog(windowOf());
        if (file != null) {
            loadImage(file.toPath());
        }
    }

    private void chooseBatchFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择要批量处理的图片");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "图片文件", "*.png", "*.jpg", "*.jpeg", "*.bmp", "*.gif", "*.tif", "*.tiff"));
        List<File> files = chooser.showOpenMultipleDialog(windowOf());
        if (files != null && !files.isEmpty()) {
            batchFiles.clear();
            for (File f : files) {
                batchFiles.add(f.toPath());
            }
            // 顺带把第一张载入预览，方便确认参数
            if (original == null) {
                loadImage(batchFiles.get(0));
                batchFiles.remove(0);
            }
            refreshBatchHint();
        }
    }

    private javafx.stage.Window windowOf() {
        return previewView.getScene() == null ? null : previewView.getScene().getWindow();
    }

    private void loadImage(Path path) {
        try {
            BufferedImage image = ImageOps.read(path);
            original = image;
            originalPath = path;
            updatePreview(image);

            String info = String.format("%s　%d × %d　%s　%s",
                    path.getFileName(), image.getWidth(), image.getHeight(),
                    ImageOps.humanSize(path.toFile().length()),
                    ImageOps.hasTransparency(image) ? "含透明通道" : "无透明");
            imageInfoLabel.setText(info);
            // 宽高框预填原尺寸，便于用户改成想要的尺寸
            widthField.setText(String.valueOf(image.getWidth()));
            heightField.setText(String.valueOf(image.getHeight()));
            setStatus("已载入：" + path.getFileName());
        } catch (Exception ex) {
            setStatus("载入失败：" + ex.getMessage());
        }
    }

    private void updatePreview(BufferedImage image) {
        Image fx = image == null ? null : SwingFXUtils.toFXImage(image, null);
        previewView.setImage(fx);

        // 空态只在没有图的时候露面。此前它是一个常驻的 Label，载入图片后会
        // 一直压在图上；换成一个三层结构的空状态之后必须显式切换，
        // 否则「尚未载入图片」会永久浮在图片正中央。
        boolean empty = fx == null;
        previewPlaceholder.setVisible(empty);
        previewPlaceholder.setManaged(empty);
        if (empty) {
            UiKit.setEmptyStateHint(previewPlaceholder, PLACEHOLDER_HINT);
        }
    }

    private void refreshBatchHint() {
        if (batchFiles.isEmpty()) {
            return;
        }
        // 已载入的原图也算进总数，让用户看到真实待处理张数
        int total = batchFiles.size() + (original != null ? 1 : 0);
        setStatus("批量队列：" + total + " 张（参数对所有图片统一生效）");
    }

    // ==================== 参数 → 变换 ====================

    /** 按当前参数对指定图应用变换（不写盘）。 */
    private BufferedImage applyTransforms(BufferedImage source) {
        BufferedImage result = source;

        // 裁剪优先：先裁再缩放，符合「我只要这块区域，然后调整大小」的直觉
        if (cropBox.isSelected()) {
            ImageOps.Crop crop = readCrop(source);
            result = ImageOps.crop(result, crop);
        }

        int w = parseInt(widthField.getText());
        int h = parseInt(heightField.getText());
        boolean sizeChanged = (w > 0 && w != result.getWidth())
                || (h > 0 && h != result.getHeight());
        if (sizeChanged) {
            ImageOps.Size target = new ImageOps.Size(w, h);
            result = keepRatioBox.isSelected()
                    ? ImageOps.scale(result, target)
                    : ImageOps.resizeExact(result, target);
        }

        int degrees = switch (rotateBox.getSelectionModel().getSelectedIndex()) {
            case 1 -> 90;
            case 2 -> 180;
            case 3 -> 270;
            default -> 0;
        };
        if (degrees != 0) {
            result = ImageOps.rotate(result, degrees);
        }
        if (flipBox.isSelected()) {
            result = ImageOps.flipHorizontal(result);
        }
        return result;
    }

    private ImageOps.Crop readCrop(BufferedImage source) {
        int x = parseInt(cropX.getText());
        int y = parseInt(cropY.getText());
        int w = parseInt(cropW.getText());
        int h = parseInt(cropH.getText());
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException("启用裁剪时，裁剪宽高必须填写");
        }
        ImageOps.Crop crop = new ImageOps.Crop(x, y, w, h);
        if (!crop.isValid(source.getWidth(), source.getHeight())) {
            throw new IllegalArgumentException(String.format(
                    "裁剪区域超出图片范围（图片 %d×%d）",
                    source.getWidth(), source.getHeight()));
        }
        return crop;
    }

    private static int parseInt(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    // ==================== 预览 ====================

    private void applyPreview() {
        if (original == null) {
            setStatus("请先选择一张图片");
            return;
        }
        try {
            BufferedImage result = applyTransforms(original);
            updatePreview(result);
            setStatus(String.format("预览：%d × %d → %d × %d",
                    original.getWidth(), original.getHeight(),
                    result.getWidth(), result.getHeight()));
        } catch (IllegalArgumentException ex) {
            setStatus("参数错误：" + ex.getMessage());
        } catch (Exception ex) {
            setStatus("预览失败：" + ex.getMessage());
        }
    }

    private void resetParams() {
        formatBox.getSelectionModel().select(ImageOps.Format.PNG);
        // 程序化 select 不派发 ActionEvent，必须显式同步一次
        syncFormatUi();
        if (original != null) {
            widthField.setText(String.valueOf(original.getWidth()));
            heightField.setText(String.valueOf(original.getHeight()));
        } else {
            widthField.clear();
            heightField.clear();
        }
        keepRatioBox.setSelected(true);
        qualitySlider.setValue(0.85);
        cropBox.setSelected(false);
        cropX.clear();
        cropY.clear();
        cropW.clear();
        cropH.clear();
        rotateBox.getSelectionModel().selectFirst();
        flipBox.setSelected(false);
        if (original != null) {
            updatePreview(original);
            setStatus("参数已重置");
        }
    }

    // ==================== 导出 ====================

    private void exportSingle() {
        if (original == null) {
            setStatus("请先选择一张图片");
            return;
        }
        ImageOps.Format format = formatBox.getValue();
        if (format == null) {
            setStatus("请选择输出格式");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出图片");
        chooser.setInitialFileName(
                ImageOps.replaceExtension(originalPath.getFileName().toString(), format));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                format.name(), "*." + format.extension()));
        File target = chooser.showSaveDialog(windowOf());
        if (target == null) {
            return;
        }
        try {
            BufferedImage result = applyTransforms(original);
            ImageOps.write(result, target.toPath(), format,
                    (float) qualitySlider.getValue());
            long size = target.length();
            setStatus(String.format("已导出 %s（%d × %d，%s）",
                    target.getName(), result.getWidth(), result.getHeight(),
                    ImageOps.humanSize(size)));
        } catch (IllegalArgumentException ex) {
            setStatus("参数错误：" + ex.getMessage());
        } catch (Exception ex) {
            setStatus("导出失败：" + ex.getMessage());
        }
    }

    /**
     * 批量处理。
     *
     * <p>输出到独立目录而非覆盖原文件 —— 图片处理属于「可能改坏」的操作，
     * 覆盖原图是不可逆的，必须让用户主动选择输出位置。
     */
    private void runBatch() {
        List<Path> all = new ArrayList<>();
        if (originalPath != null) {
            all.add(originalPath);
        }
        all.addAll(batchFiles);

        if (all.isEmpty()) {
            setStatus("请先选择要处理的图片");
            return;
        }
        if (busy) {
            setStatus("正在处理中，请等待当前任务完成");
            return;
        }
        ImageOps.Format format = formatBox.getValue();
        if (format == null) {
            setStatus("请选择输出格式");
            return;
        }

        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择输出目录");
        File outDir = chooser.showDialog(windowOf());
        if (outDir == null) {
            return;
        }

        // 参数在进后台前先校验一次，避免每个文件都失败
        try {
            applyTransforms(original != null ? original : ImageOps.read(all.get(0)));
        } catch (IllegalArgumentException ex) {
            setStatus("参数错误：" + ex.getMessage());
            return;
        } catch (Exception ex) {
            setStatus("无法开始：" + ex.getMessage());
            return;
        }

        busy = true;
        batchBtn.setDisable(true);
        exportBtn.setDisable(true);
        setStatus("开始处理 " + all.size() + " 张…");

        final float quality = (float) qualitySlider.getValue();
        Task<int[]> task = new Task<>() {
            @Override
            protected int[] call() {
                int ok = 0;
                int fail = 0;
                // 记录第一个失败原因，便于用户定位问题
                String firstError = null;
                for (int i = 0; i < all.size(); i++) {
                    Path src = all.get(i);
                    updateMessage("处理中（" + (i + 1) + "/" + all.size() + "）："
                            + src.getFileName());
                    try {
                        BufferedImage image = ImageOps.read(src);
                        BufferedImage result = applyTransforms(image);
                        String name = ImageOps.replaceExtension(
                                src.getFileName().toString(), format);
                        Path dest = outDir.toPath().resolve(name);
                        ImageOps.write(result, dest, format, quality);
                        ok++;
                    } catch (Exception ex) {
                        fail++;
                        if (firstError == null) {
                            firstError = src.getFileName() + "：" + ex.getMessage();
                        }
                    }
                }
                return new int[]{ok, fail, firstError == null ? 0 : 1};
            }
        };

        task.messageProperty().addListener((o, old, now) -> {
            if (now != null) {
                Platform.runLater(() -> statusLabel.setText(now));
            }
        });

        task.setOnSucceeded(e -> {
            int[] r = task.getValue();
            busy = false;
            batchBtn.setDisable(false);
            exportBtn.setDisable(false);
            String msg = "批量完成：成功 " + r[0] + " 张，失败 " + r[1] + " 张"
                    + "　输出目录：" + outDir.getName();
            setStatus(msg);
            if (r[1] > 0) {
                // 有失败时给出提示，但不弹窗打断
                statusLabel.setText(msg + "（部分失败，请检查文件是否损坏）");
            }
        });

        task.setOnFailed(e -> {
            busy = false;
            batchBtn.setDisable(false);
            exportBtn.setDisable(false);
            Throwable ex = task.getException();
            setStatus("批量处理失败：" + (ex == null ? "未知错误" : ex.getMessage()));
        });

        Thread thread = new Thread(task, "image-batch");
        thread.setDaemon(true);
        thread.start();
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }
}
