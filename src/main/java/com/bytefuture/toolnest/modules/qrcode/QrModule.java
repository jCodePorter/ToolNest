package com.bytefuture.toolnest.modules.qrcode;

import com.bytefuture.toolnest.core.ModuleCategory;
import com.bytefuture.toolnest.core.ToolModule;
import com.bytefuture.toolnest.ui.UiKit;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.List;

/**
 * 二维码工具：生成 + 解析。
 *
 * <p>做成两个页签，因为这两件事的使用节奏完全不同 ——
 * 生成是一次性输入输出，解析是「找图 → 看结果 → 复制」的反复尝试。
 *
 * <h2>解析页的三种输入方式</h2>
 * <ol>
 *   <li><b>拖放</b>：把图片文件拖到预览区</li>
 *   <li><b>选择文件</b>：本地图片文件</li>
 *   <li><b>粘贴（核心）</b>：直接 {@code Ctrl+V} 粘贴剪贴板里的图 ——
 *       这条路径针对的是「截图工具框选二维码 → 粘贴 → 直接出结果」，
 *       不用先存成文件。剪贴板里的图往往已经在截图时裁好，
 *       所以这是最快的路径，默认就启用</li>
 * </ol>
 *
 * <h2>框选裁切</h2>
 * 预览区支持鼠标拖拽画矩形，只解析框内的部分。用途：一张大截图里
 * 二维码只占一角，且周围有文字干扰时，裁出来识别率显著更高。
 * 裁切坐标要经过「显示坐标 → 原图像素坐标」的换算（预览是等比缩放过的）。
 */
public class QrModule implements ToolModule {

    // ==================== 生成页 ====================
    private TextArea genInput;
    private ComboBox<QrOps.Level> genLevel;
    private Spinner<Integer> genSize;
    private Spinner<Integer> genMargin;
    private ColorPicker genForeground;
    private ColorPicker genBackground;
    private ImageView genPreview;
    private Label genStatus;
    private Button genExportBtn;
    private Button copyImgButton;
    private HBox genNotice;

    /** 最近一次生成的图像与建议文件名。 */
    private BufferedImage generated;
    private String generatedName = "qrcode.png";

    // ==================== 解析页 ====================
    /** 本模块的根节点，用于判断「当前是否还在展示本模块」。 */
    private Node rootNode;
    private ImageView decodePreview;
    private StackPane decodeStage;
    private Label decodePlaceholder;
    private Rectangle selectionRect;
    private StackPane selectionLayer;
    private TextArea decodeResult;
    private Label decodeMeta;
    private Button decodeClearSelBtn;
    private HBox decodeNotice;

    /** 当前载入待解析的原图（未裁切）。 */
    private BufferedImage decodeSource;
    /** 待解析图的来源描述，显示在元信息里。 */
    private String decodeSourceLabel = "";

    private double dragStartX;
    private double dragStartY;
    private boolean dragging;

    /** 预览区显示尺寸上限。 */
    private static final double PREVIEW_MAX = 460;

    @Override
    public String id() {
        return "qrcode";
    }

    @Override
    public String displayName() {
        return "二维码";
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.GENERATE;
    }

    @Override
    public String summary() {
        return "生成二维码，或从图片/剪贴板解析二维码内容";
    }

    @Override
    public String description() {
        return "解析支持本地文件、直接粘贴剪贴板截图、鼠标框选裁切后识别";
    }

    @Override
    public String icon() {
        return "\u25A6";
    }

    @Override
    public String keywords() {
        return "qr qrcode 二维码 扫一扫 扫码 生成 解析 识别 条码 barcode 粘贴 截图 剪贴板";
    }

    @Override
    public Node buildContent() {
        VBox root = UiKit.page("二维码", "生成二维码，或解析图片中的二维码。");

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getStyleClass().add("qr-tabs");

        tabs.getTabs().addAll(
                new Tab("解析二维码", buildDecodeTab()),
                new Tab("生成二维码", buildGenerateTab()));
        VBox.setVgrow(tabs, Priority.ALWAYS);

        root.getChildren().add(tabs);
        // Ctrl+V 装到根容器：焦点停在哪都能收到（原先挂在预览区上收不到）
        installPasteShortcutOn(root);
        return root;
    }

    // ================================================================ 解析页

    private Node buildDecodeTab() {
        // ---------- 左栏：图片预览 + 框选 ----------
        decodePreview = new ImageView();
        decodePreview.setPreserveRatio(true);
        decodePreview.setFitWidth(PREVIEW_MAX);
        decodePreview.setFitHeight(PREVIEW_MAX);
        decodePreview.setSmooth(true);

        // 框选层：与图片严格同尺寸叠放，保证坐标换算一一对应
        selectionRect = new Rectangle();
        selectionRect.setFill(Color.web("#2563eb", 0.16));
        selectionRect.setStroke(Color.web("#2563eb"));
        selectionRect.setStrokeWidth(1.5);
        selectionRect.getStrokeDashArray().addAll(6.0, 4.0);
        selectionRect.setVisible(false);
        selectionRect.setMouseTransparent(true);

        // 框选层用 StackPane（Pane 没有 alignment，StackPane 才有）：
        // 图片与框选矩形都相对它左上角定位，鼠标坐标因此可以直接换算
        selectionLayer = new StackPane(decodePreview, selectionRect);
        selectionLayer.getStyleClass().add("selection-layer");
        StackPane.setAlignment(decodePreview, Pos.TOP_LEFT);
        StackPane.setAlignment(selectionRect, Pos.TOP_LEFT);
        selectionLayer.setPickOnBounds(false);
        // 尺寸随图片走，这样鼠标坐标可以直接当作图片显示坐标
        selectionLayer.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        decodePreview.getStyleClass().add("decode-image-view");
        selectionRect.getStyleClass().add("selection-rect");
        decodePlaceholder = UiKit.caption("尚未载入图片");

        decodeStage = new StackPane(selectionLayer, decodePlaceholder);
        decodeStage.getStyleClass().add("image-preview-holder");
        decodeStage.setMinHeight(320);
        decodeStage.setAlignment(Pos.CENTER);

        installDropTarget();
        installPasteShortcut();
        installSelectionDrag();

        // ---------- 输入按钮 ----------
        Button openBtn = UiKit.primaryButton("选择图片…");
        openBtn.setOnAction(e -> chooseDecodeFile());

        Button pasteBtn = UiKit.secondaryButton("粘贴剪贴板 (Ctrl+V)");
        pasteBtn.setOnAction(e -> pasteFromClipboard());

        decodeClearSelBtn = UiKit.secondaryButton("清除框选");
        decodeClearSelBtn.setDisable(true);
        decodeClearSelBtn.setOnAction(e -> clearSelection());

        decodeMeta = UiKit.caption("等待载入");
        decodeMeta.getStyleClass().add("decode-meta");

        VBox leftBox = new VBox(10,
                UiKit.buttonRow(openBtn, pasteBtn, decodeClearSelBtn),
                decodeStage,
                decodeMeta,
                UiKit.caption("提示：可用鼠标在图上拖拽框选，只识别框内区域。")
                );

        // ---------- 右栏：结果 ----------
        decodeResult = UiKit.readonlyArea();
        decodeResult.setPromptText("识别结果会显示在这里");
        decodeResult.getStyleClass().add("qr-result");
        VBox.setVgrow(decodeResult, Priority.ALWAYS);

        Button copyBtn = UiKit.secondaryButton("复制结果");
        copyBtn.setOnAction(e -> UiKit.copyToClipboard(decodeResult.getText()));

        Button openUrlBtn = UiKit.secondaryButton("在浏览器打开");
        openUrlBtn.setOnAction(e -> openInBrowser());
        openUrlBtn.setTooltip(new Tooltip("内容看起来是网址时才有意义"));

        Button clearBtn = UiKit.secondaryButton("清空");
        clearBtn.setOnAction(e -> clearDecode());

        decodeNotice = UiKit.notice("warning", "");

        VBox rightBox = new VBox(12,
                decodeResult,
                UiKit.buttonRow(copyBtn, openUrlBtn, UiKit.spacer(), clearBtn),
                decodeNotice);

        SplitPane split = new SplitPane(leftBox, rightBox);
        split.setDividerPositions(0.52);
        VBox.setVgrow(split, Priority.ALWAYS);

        VBox wrapper = new VBox(12, split);
        wrapper.setPadding(new javafx.geometry.Insets(4, 0, 0, 0));
        return wrapper;
    }

    /** 拖放整张图片文件。 */
    private void installDropTarget() {
        decodeStage.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles() || e.getDragboard().hasImage()) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        decodeStage.setOnDragDropped(e -> {
            Dragboard board = e.getDragboard();
            boolean done = false;
            if (board.hasFiles()) {
                for (File f : board.getFiles()) {
                    if (isImageFile(f)) {
                        loadDecodeFile(f.toPath());
                        done = true;
                        break;
                    }
                }
                if (!done) {
                    UiKit.showNotice(decodeNotice, "拖入的文件不是支持的图片格式。");
                }
            } else if (board.hasImage()) {
                // 从浏览器等直接拖图（非文件）
                Image img = board.getImage();
                if (img != null) {
                    setDecodeImage(SwingFXUtils.fromFXImage(img, null), "拖入的图片");
                    done = true;
                }
            }
            e.setDropCompleted(done);
            e.consume();
        });
    }

    /**
     * 支持在整个二维码界面按 {@code Ctrl+V} 粘贴剪贴板里的图。
     *
     * <p>挂在 {@code decodeStage} 上是不行的：那个 StackPane 只是容器，
     * 键盘焦点实际会停在 TabPane / VBox 上（点一下预览区也未必抢得到焦点），
     * 结果用户按 Ctrl+V 毫无反应。改为在**根容器**上装事件过滤器 ——
     * 只要焦点还在本模块界面内就能收到，这才是用户预期。
     *
     * <p>用 {@code addEventFilter}（而非 {@code setOnKeyPressed}）：
     * 过滤器在捕获阶段触发，不会被内层控件（如结果 TextArea）的默认
     * 粘贴行为抢先消费掉。
     */
    private void installPasteShortcut() {
        // 预览区仍保留点击聚焦，便于框选
        decodeStage.setFocusTraversable(true);
        decodeStage.setOnMouseClicked(e -> decodeStage.requestFocus());
    }

    /**
     * 把 Ctrl+V 装到**场景**上（{@link #buildContent()} 里调用）。
     *
     * <p>为什么不装在预览区或根容器上：两者都挡住「焦点在哪」这个变量。
     * 实测点一下预览区，焦点仍可能停在 TabPane 上，按键事件根本到不了
     * 我们挂过滤器的那个节点。装在 Scene 上则只要窗口在前台就一定收到，
     * 这是最不容易出错的位置。
     *
     * <p>代价是要自己判断「该不该响应」，所以加了两道闸：
     * <ol>
     *   <li>根容器已不在场景里（模块被切走）→ 不响应，免得抢别的模块的粘贴</li>
     *   <li>事件目标是输入控件（结果框 / 参数框）→ 不响应，
     *       把 Ctrl+V 让给原生文字粘贴，否则用户想粘文本却触发了识图</li>
     * </ol>
     */
    private void installPasteShortcutOn(Node root) {
        this.rootNode = root;
        // root 此刻还没进场景，等它被挂上去再装
        root.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (oldScene != null) {
                oldScene.removeEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED,
                        pasteFilter);
            }
            if (newScene != null) {
                newScene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED,
                        pasteFilter);
            }
        });
        // 若调用时已经挂在场景上（例如重建页面），直接装上
        if (root.getScene() != null) {
            root.getScene().addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED,
                    pasteFilter);
        }
    }

    /** 装在 Scene 上的 Ctrl+V 过滤器；抽成字段是因为摘除时要同一个实例。 */
    private final javafx.event.EventHandler<javafx.scene.input.KeyEvent> pasteFilter =
            e -> {
                if (!e.isControlDown() || e.getCode() != javafx.scene.input.KeyCode.V) {
                    return;
                }
                // 输入框里的 Ctrl+V 留给原生文字粘贴，别打断用户粘文本
                if (e.getTarget() instanceof javafx.scene.control.TextInputControl) {
                    return;
                }
                // 本模块已切走（根节点离开场景）时不响应，免得抢别的模块的粘贴
                if (rootNode == null || rootNode.getScene() == null) {
                    return;
                }
                pasteFromClipboard();
                e.consume();
            };

    /**
     * 从系统剪贴板取图。
     *
     * <p>顺序很关键：<b>先看有没有图</b>，再看有没有文件。
     * 截图工具复制进来的通常只有 {@code Image}；从资源管理器复制的才是文件列表。
     * 两者都支持，用户不用关心自己用的是哪种复制方式。
     *
     * <h2>为什么优先走 AWT 取图（重要，别改回去）</h2>
     * 截图工具是**以原生 / AWT 方式**把图放进剪贴板的。对这种剪贴板内容，
     * JavaFX 的 {@code Clipboard.getImage()} 会返回一张**损坏的图**：
     * <ul>
     *   <li><b>整张变全透明</b> —— 每个像素的 alpha 都被清零。
     *       实测一张 300×300 的透明背景二维码读回来是
     *       {@code 黑=0 白=0 透明=89401}，连 alpha=255 的不透明黑码点都变成透明。
     *       <b>注意这不是「预乘 alpha」造成的</b>（预乘对 {@code 0xFF000000} 是恒等变换），
     *       而是 AWT ↔ JavaFX 互操作层把 alpha 通道整体丢了。</li>
     *   <li><b>尺寸掉 1 像素</b>（300×300 → 299×299），会切掉二维码定位图案的一边。</li>
     * </ul>
     * 全透明的图交给 {@link #normalizeForDecode} 后会被忠实地合成成**纯白**，
     * 于是用户看到「预览区一片空白 + 未识别到二维码」—— 这就是曾经的用户上报现象。
     *
     * <p>改用 AWT 取图可**同时解决**上面两个问题（实测透明背景图与不透明 RGB 图
     * 均能正常识别、尺寸完整）。仅在 AWT 不可用时才退回 JavaFX 路径。
     */
    private void pasteFromClipboard() {
        // ---------- 首选：AWT 取图，绕开 FX 互操作层的有损转换 ----------
        BufferedImage awtImage = readAwtClipboardImage();
        if (awtImage != null) {
            setDecodeImage(awtImage, "剪贴板图片");
            setDecodeStatus("已从剪贴板粘贴图片，正在识别…");
            decodeCurrent();
            return;
        }

        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (clipboard.hasImage()) {
            Image img = clipboard.getImage();
            if (img != null) {
                setDecodeImage(SwingFXUtils.fromFXImage(img, null), "剪贴板图片");
                setDecodeStatus("已从剪贴板粘贴图片，正在识别…");
                decodeCurrent();
                return;
            }
        }
        if (clipboard.hasFiles()) {
            for (File f : clipboard.getFiles()) {
                if (isImageFile(f)) {
                    loadDecodeFile(f.toPath());
                    return;
                }
            }
        }
        if (clipboard.hasString()) {
            String s = clipboard.getString();
            if (s != null && !s.isBlank()) {
                UiKit.showNotice(decodeNotice,
                        "剪贴板里是文本而不是图片。如果想识别文本里的链接，"
                                + "它不是二维码 —— 请复制图片后再粘贴。");
                return;
            }
        }
        UiKit.showNotice(decodeNotice, "剪贴板里没有图片。请先用截图工具复制图片，再按 Ctrl+V。");
    }

    /**
     * 从 AWT 系统剪贴板取一张图。
     *
     * <p>为什么不用 JavaFX 的 {@code Clipboard.getImage()}：见
     * {@link #pasteFromClipboard()} 的说明 —— 原生/AWT 写入的剪贴板内容
     * 经 FX 读取后会变成全透明图且掉 1 像素。AWT 直接读则完好无损，
     * 因为它不需要跨过那条有损的互操作转换。
     *
     * @return 取到的图；剪贴板里没有图、或剪贴板不可用时返回 {@code null}
     *         （调用方据此退回 JavaFX 路径，不允许因 AWT 失败而整体失败）
     */
    private static BufferedImage readAwtClipboardImage() {
        try {
            java.awt.datatransfer.Clipboard cb =
                    java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
            if (cb == null || !cb.isDataFlavorAvailable(
                    java.awt.datatransfer.DataFlavor.imageFlavor)) {
                return null;
            }
            Object data = cb.getData(java.awt.datatransfer.DataFlavor.imageFlavor);
            if (!(data instanceof java.awt.Image awtImage)) {
                return null;
            }
            if (awtImage instanceof BufferedImage bi) {
                return bi;
            }
            // 其它 Image 实现：画到非预乘的 INT_ARGB 上
            int w = awtImage.getWidth(null);
            int h = awtImage.getHeight(null);
            if (w <= 0 || h <= 0) {
                return null;
            }
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = out.createGraphics();
            g.drawImage(awtImage, 0, 0, null);
            g.dispose();
            return out;
        } catch (Exception | LinkageError ex) {
            // 无头环境 / 剪贴板被占用 / AWT 未就绪 —— 一律降级，不打断用户操作
            return null;
        }
    }

    /** 鼠标拖拽画框选。 */
    private void installSelectionDrag() {
        selectionLayer.setOnMousePressed(e -> {
            if (decodeSource == null) {
                return;
            }
            // 把鼠标位置换算成「图片显示区域」内坐标
            Point2D local = imageLocalPoint(e);
            if (local == null) {
                return;
            }
            dragStartX = local.getX();
            dragStartY = local.getY();
            dragging = true;
            selectionRect.setX(dragStartX);
            selectionRect.setY(dragStartY);
            selectionRect.setWidth(0);
            selectionRect.setHeight(0);
            selectionRect.setVisible(true);
            UiKit.hideNotice(decodeNotice);
            selectionLayer.setCursor(Cursor.CROSSHAIR);
            e.consume();
        });

        selectionLayer.setOnMouseDragged(e -> {
            if (!dragging) {
                return;
            }
            Point2D local = imageLocalPoint(e);
            if (local == null) {
                return;
            }
            double x = Math.min(dragStartX, local.getX());
            double y = Math.min(dragStartY, local.getY());
            double w = Math.abs(local.getX() - dragStartX);
            double h = Math.abs(local.getY() - dragStartY);
            selectionRect.setX(x);
            selectionRect.setY(y);
            selectionRect.setWidth(w);
            selectionRect.setHeight(h);
            e.consume();
        });

        selectionLayer.setOnMouseReleased(e -> {
            if (!dragging) {
                return;
            }
            dragging = false;
            selectionLayer.setCursor(Cursor.DEFAULT);
            // 太小的框视为误触，直接清掉
            if (selectionRect.getWidth() < 8 || selectionRect.getHeight() < 8) {
                clearSelection();
                return;
            }
            decodeClearSelBtn.setDisable(false);
            decodeCurrent();
            e.consume();
        });
    }

    /**
     * 把鼠标事件坐标换算到「图片实际显示区域」的坐标系（原点在图片左上角）。
     *
     * <p>{@code selectionLayer} 是一个 StackPane，尺寸由 {@link #displaySize}
     * 设为图片的等比显示尺寸，且 {@code decodePreview} 在其中被
     * {@code TOP_LEFT} 对齐 —— 所以图片左上角就是 selectionLayer 的原点，
     * 鼠标在 selectionLayer 内的坐标**直接就是**图片内坐标，无需再减偏移。
     *
     * <p>用 {@code decodePreview.getBoundsInParent()} 只作一次**稳健性校正**：
     * 它给出图片相对 selectionLayer 的真实偏移（正常情况下恒为 0）。
     * 之所以保留，是因为 FlushMode / 布局细节可能让图文间产生亚像素位移，
     * 直接用事件坐标会在极端情况下差之毫厘。
     */
    private Point2D imageLocalPoint(MouseEvent e) {
        if (decodePreview.getImage() == null) {
            return null;
        }
        Bounds imgBounds = decodePreview.getBoundsInParent();
        double imgX = e.getX() - imgBounds.getMinX();
        double imgY = e.getY() - imgBounds.getMinY();
        // 夹取到图片范围内，拖出边界也不会产生越界坐标
        imgX = Math.max(0, Math.min(imgX, imgBounds.getWidth()));
        imgY = Math.max(0, Math.min(imgY, imgBounds.getHeight()));
        return new Point2D(imgX, imgY);
    }

    // ================================================================ 解析执行

    /** 载入本地文件并立即解析。 */
    private void loadDecodeFile(Path path) {
        try {
            BufferedImage img = javax.imageio.ImageIO.read(path.toFile());
            if (img == null) {
                UiKit.showNotice(decodeNotice,
                        "无法读取该图片（格式不受支持或文件已损坏）。");
                return;
            }
            setDecodeImage(img, path.getFileName().toString());
            decodeCurrent();
        } catch (Exception ex) {
            UiKit.showNotice(decodeNotice, "读取图片失败：" + ex.getMessage());
        }
    }

    /** 设置待解析图并刷新预览。 */
    private void setDecodeImage(BufferedImage img, String label) {
        // 透明图必须先合成到白底再往下走，两个理由：
        //   1) 预览：透明区域会露出容器的浅灰底纹，二维码看着「发白看不清」
        //   2) 识别：ZXing 只看亮度，透明像素的 RGB 是 0（黑），
        //      整张图会被当成全黑 → 100% 识别失败
        // 截图工具复制的图常带透明（圆角、无背景截图），所以这步不能省。
        img = normalizeForDecode(img);

        decodeSource = img;
        decodeSourceLabel = label;
        clearSelection();

        Image fx = SwingFXUtils.toFXImage(img, null);
        decodePreview.setImage(fx);
        decodePlaceholder.setVisible(false);

        // 选中层必须与图片**实际显示尺寸**严格一致，坐标换算才能直接对应。
        //
        // 【历史 bug】这里原先是 Math.min(原图宽, PREVIEW_MAX) ×
        // Math.min(原图高, PREVIEW_MAX)，即把原图尺寸各自独立地截到上限。
        // 但 decodePreview 是 preserveRatio=true 的**等比缩放**：
        // 长方形图（如 800×600）按 fitWidth/fitHeight=460 缩放后实际显示
        // 是 460×345，而旧算法给出 460×460 —— selectionLayer 比图片高 115px，
        // 图片在其中居中 → 框选坐标系被整体下移半个差值，
        // 表现为「框选起点不跟随鼠标落点」。
        // 现在统一用 displaySize() 算等比缩放结果，与 ImageView 的行为一致。
        double[] shown = displaySize(fx.getWidth(), fx.getHeight());
        selectionLayer.setPrefWidth(shown[0]);
        selectionLayer.setPrefHeight(shown[1]);

        // 同时把 ImageView 的 fit 尺寸也收紧到实际显示尺寸。
        //
        // 为什么要这一步：fitWidth/fitHeight 固定为 PREVIEW_MAX 时，
        // ImageView 的 **pref 尺寸仍是 460**（fit 只是上限，不缩小 pref），
        // 而 selectionLayer 的 pref 是 shown（小图时只有 350）。
        // 二者同处一个 StackPane，StackPane 取子节点的最大 pref →
        // selectionLayer 被撑回 460×460，小图又会错位。
        // 把 fit 设成 shown 后，ImageView 的真实占用 = shown，
        // 与 selectionLayer 天然一致，不再有谁撑谁的问题。
        decodePreview.setFitWidth(shown[0]);
        decodePreview.setFitHeight(shown[1]);

        decodeStage.getChildren().remove(decodePlaceholder);
        UiKit.hideNotice(decodeNotice);
    }

    /**
     * 按 {@code decodePreview} 的等比缩放规则，算出图片**实际显示**的宽高。
     *
     * <p>规则与 {@link ImageView} 在 {@code preserveRatio=true} +
     * {@code fitWidth=fitHeight=PREVIEW_MAX} 时一致：
     * <ul>
     *   <li>两边都不超上限 → 原尺寸</li>
     *   <li>有一边超上限 → 按**较小**的那个比例整体缩放（保持不变形）</li>
     * </ul>
     *
     * <p>抽成单一方法，是为了让「设置框选层尺寸」与「鼠标坐标换算」
     * 共用同一套算法 —— 两处各算一遍极易写出不一致的换算，
     * 这正是本次偏移 bug 的成因。
     *
     * @return 长度为 2 的数组：{宽, 高}（单位 px）
     */
    private static double[] displaySize(double srcW, double srcH) {
        if (srcW <= 0 || srcH <= 0) {
            return new double[]{0, 0};
        }
        double scale = Math.min(1.0, Math.min(PREVIEW_MAX / srcW, PREVIEW_MAX / srcH));
        return new double[]{srcW * scale, srcH * scale};
    }

    /** 测试用：暴露 {@link #displaySize} 供探针独立核算等比显示尺寸。 */
    public static double[] displaySizePublic(double srcW, double srcH) {
        return displaySize(srcW, srcH);
    }

    /**
     * 把带透明通道的图合成到白底上，消除 alpha。
     *
     * <p>没有透明像素时原样返回，不做多余的复制 —— 全图重绘一次在
     * 大截图上是要花时间的。
     */
    private static BufferedImage normalizeForDecode(BufferedImage img) {
        if (img == null) {
            return null;
        }
        if (!img.getColorModel().hasAlpha()) {
            return img;
        }
        return com.bytefuture.toolnest.modules.image.ImageOps.flatten(
                img, java.awt.Color.WHITE);
    }

    /** 解析当前图（若存在框选则只解析框内）。 */
    private void decodeCurrent() {
        if (decodeSource == null) {
            return;
        }
        BufferedImage target = decodeSource;
        String scope = "整图";

        if (selectionRect.isVisible() && selectionRect.getWidth() >= 8) {
            BufferedImage cropped = cropFromSelection();
            if (cropped != null) {
                target = cropped;
                scope = "框选区域";
            }
        }

        decodeMeta.setText(String.format("%s｜%s %d×%d", decodeSourceLabel, scope,
                target.getWidth(), target.getHeight()));
        decodeResult.setText("");
        setDecodeStatus("识别中…");

        final BufferedImage toDecode = target;
        final String scopeLabel = scope;

        // 解码含多轮降级尝试，放后台避免界面卡顿
        Task<List<QrOps.Decoded>> task = new Task<>() {
            @Override
            protected List<QrOps.Decoded> call() {
                return QrOps.decodeRobust(toDecode);
            }
        };
        task.setOnSucceeded(e -> {
            List<QrOps.Decoded> results = task.getValue();
            if (results.isEmpty()) {
                decodeResult.setText("");
                setDecodeStatus("未识别到二维码");
                UiKit.showNotice(decodeNotice, QrOps.Failure.NOT_FOUND.message()
                        + " 可尝试：用鼠标框选二维码所在区域后松开，或换一张更清晰的图。");
            } else {
                decodeResult.setText(results.get(0).text());
                UiKit.hideNotice(decodeNotice);
                String extra = results.size() > 1
                        ? "（共发现 " + results.size() + " 个码，已显示第 1 个）" : "";
                setDecodeStatus("识别成功｜" + scopeLabel + "｜格式 "
                        + results.get(0).format() + extra);
            }
        });
        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            setDecodeStatus("识别失败");
            UiKit.showNotice(decodeNotice, "识别过程出错："
                    + (ex == null ? "未知原因" : ex.getMessage()));
        });

        Thread t = new Thread(task, "qr-decode");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 把框选矩形换算成原图像素坐标并裁切。
     *
     * <p>预览图是被等比缩放过的（可能缩放，也可能 1:1），
     * 所以要按「原图尺寸 ÷ 显示尺寸」的倍率还原 —— 漏了这一步，
     * 框选的区域和实际裁出来的区域会错位。
     */
    private BufferedImage cropFromSelection() {
        Image fx = decodePreview.getImage();
        if (fx == null || decodeSource == null) {
            return null;
        }
        // 【历史 bug】这里原先写的是 fx.getWidth()/fx.getHeight()，
        // 但 JavaFX Image 的 getWidth() 返回的是**原图尺寸**，不是显示尺寸！
        // 于是 scaleX/scaleY 恒等于 1，裁切完全没做缩放换算 ——
        // 用户框选的是「显示坐标系」（最多 PREVIEW_MAX=460）里的区域，
        // 却被当成「原图坐标系」去裁，对于超过 460px 的图必然错位。
        // 必须用 displaySize() 拿到等比缩放后的真实显示尺寸。
        double[] shown = displaySize(fx.getWidth(), fx.getHeight());
        double shownW = shown[0];
        double shownH = shown[1];
        if (shownW <= 0 || shownH <= 0) {
            return null;
        }
        double scaleX = decodeSource.getWidth() / shownW;
        double scaleY = decodeSource.getHeight() / shownH;

        int x = (int) Math.round(selectionRect.getX() * scaleX);
        int y = (int) Math.round(selectionRect.getY() * scaleY);
        int w = (int) Math.round(selectionRect.getWidth() * scaleX);
        int h = (int) Math.round(selectionRect.getHeight() * scaleY);

        // 框选往往比码本身略紧，四周各留一点余量能显著提升识别率
        int pad = Math.max(4, Math.min(w, h) / 8);
        try {
            return QrOps.crop(decodeSource, x - pad, y - pad, w + pad * 2, h + pad * 2);
        } catch (Exception ex) {
            return null;
        }
    }

    private void clearSelection() {
        selectionRect.setVisible(false);
        selectionRect.setWidth(0);
        selectionRect.setHeight(0);
        if (decodeClearSelBtn != null) {
            decodeClearSelBtn.setDisable(true);
        }
    }

    private void clearDecode() {
        decodeSource = null;
        decodeSourceLabel = "";
        decodePreview.setImage(null);
        decodeResult.setText("");
        decodeMeta.setText("等待载入");
        clearSelection();
        UiKit.hideNotice(decodeNotice);
        if (!decodeStage.getChildren().contains(decodePlaceholder)) {
            decodeStage.getChildren().add(decodePlaceholder);
        }
        decodePlaceholder.setVisible(true);
    }

    private void setDecodeStatus(String text) {
        decodeMeta.setText(text);
    }

    private void chooseDecodeFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择包含二维码的图片");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "图片文件", "*.png", "*.jpg", "*.jpeg", "*.bmp", "*.gif", "*.tif", "*.tiff"));
        File f = chooser.showOpenDialog(null);
        if (f != null) {
            loadDecodeFile(f.toPath());
        }
    }

    private boolean isImageFile(File f) {
        String n = f.getName().toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".bmp") || n.endsWith(".gif")
                || n.endsWith(".tif") || n.endsWith(".tiff");
    }

    private void openInBrowser() {
        String text = decodeResult.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        String url = text.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            UiKit.showNotice(decodeNotice, "当前内容不是网址，无法在浏览器打开。");
            return;
        }
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception ex) {
            UiKit.showNotice(decodeNotice, "打开浏览器失败：" + ex.getMessage());
        }
    }

    // ================================================================ 生成页

    private Node buildGenerateTab() {
        // ---------- 左栏：参数 ----------
        genInput = new TextArea();
        genInput.setPromptText("要编码的内容，例如网址、文本、WiFi 配置…");
        genInput.getStyleClass().add("mono-area");
        genInput.setWrapText(true);
        genInput.setPrefRowCount(6);

        genLevel = new ComboBox<>();
        genLevel.getItems().addAll(QrOps.Level.values());
        genLevel.getSelectionModel().select(QrOps.Level.M);
        genLevel.setPrefWidth(180);
        Label levelNote = UiKit.caption(QrOps.Level.M.note());
        genLevel.setOnAction(e -> {
            QrOps.Level lv = genLevel.getValue();
            levelNote.setText(lv == null ? "" : lv.note()
                    + "；该级别约可容纳 " + QrOps.capacityHint(lv) + " 个英文字符");
        });

        genSize = new Spinner<>(128, 2048, 512, 64);
        genSize.setEditable(true);
        genSize.setPrefWidth(120);

        genMargin = new Spinner<>(0, 32, 4, 1);
        genMargin.setEditable(true);
        genMargin.setPrefWidth(120);

        genForeground = new ColorPicker(Color.BLACK);
        genForeground.setPrefWidth(140);
        genBackground = new ColorPicker(Color.WHITE);
        genBackground.setPrefWidth(140);

        HBox sizeRow = new HBox(10, genSize, UiKit.caption("px"));
        sizeRow.setAlignment(Pos.CENTER_LEFT);
        HBox marginRow = new HBox(10, genMargin, UiKit.caption("模块"));
        marginRow.setAlignment(Pos.CENTER_LEFT);
        HBox colorRow = new HBox(10,
                UiKit.caption("前景"), genForeground,
                UiKit.caption("背景"), genBackground);
        colorRow.setAlignment(Pos.CENTER_LEFT);

        Button genBtn = UiKit.primaryButton("生成二维码");
        genBtn.setOnAction(e -> generate());

        genExportBtn = UiKit.secondaryButton("导出 PNG");
        genExportBtn.setDisable(true);
        genExportBtn.setOnAction(e -> exportGenerated());

        copyImgButton = UiKit.secondaryButton("复制图片");
        copyImgButton.setDisable(true);
        copyImgButton.setOnAction(e -> copyGeneratedToClipboard());

        genStatus = UiKit.caption("等待生成");
        genNotice = UiKit.notice("warning", "");

        VBox paramsCard = UiKit.section("生成参数", null,
                UiKit.field("内容", genInput),
                UiKit.field("纠错级别", genLevel),
                levelNote,
                UiKit.field("输出尺寸", sizeRow),
                UiKit.field("四周留白", marginRow),
                UiKit.field("颜色", colorRow),
                new Separator(),
                UiKit.buttonRow(genBtn, UiKit.spacer(), copyImgButton, genExportBtn),
                genStatus,
                genNotice);

        ScrollPane leftScroll = new ScrollPane(paramsCard);
        leftScroll.setFitToWidth(true);
        leftScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        leftScroll.getStyleClass().add("image-param-scroll");

        // ---------- 右栏：预览 ----------
        genPreview = new ImageView();
        genPreview.setPreserveRatio(true);
        genPreview.setFitWidth(PREVIEW_MAX);
        genPreview.setFitHeight(PREVIEW_MAX);
        genPreview.setSmooth(false); // 二维码放大时保持锐利，不要平滑

        Label genPlaceholder = UiKit.caption("生成的二维码会显示在这里");
        StackPane genStage = new StackPane(genPreview, genPlaceholder);
        genStage.getStyleClass().add("image-preview-holder");
        genStage.setMinHeight(320);
        // 生成后隐藏占位文字
        genPreview.imageProperty().addListener((o, old, now) ->
                genPlaceholder.setVisible(now == null));

        VBox rightBox = new VBox(10, genStage,
                UiKit.caption("提示：纠错级别 H 可在码中间叠加 logo，仍能正常识别。"));

        SplitPane split = new SplitPane(leftScroll, rightBox);
        split.setDividerPositions(0.48);
        VBox.setVgrow(split, Priority.ALWAYS);

        VBox wrapper = new VBox(12, split);
        wrapper.setPadding(new javafx.geometry.Insets(4, 0, 0, 0));
        return wrapper;
    }

    private void generate() {
        String text = genInput.getText();
        if (text == null || text.isEmpty()) {
            UiKit.showNotice(genNotice, "请先输入要编码的内容。");
            genStatus.setText("等待生成");
            // 必须一并作废旧图：否则用户清空输入后仍能「导出」上一次的码，
            // 界面上看着是空的、导出的却是旧内容，是个很容易踩的坑
            discardGenerated();
            return;
        }
        try {
            java.awt.Color fg = toAwt(genForeground.getValue());
            java.awt.Color bg = toAwt(genBackground.getValue());
            QrOps.GenerateOptions opts = new QrOps.GenerateOptions(
                    genSize.getValue(), genLevel.getValue(),
                    genMargin.getValue(), fg, bg);
            BufferedImage img = QrOps.generate(text, opts);
            generated = img;
            generatedName = suggestFileName(text);

            genPreview.setImage(SwingFXUtils.toFXImage(img, null));
            genStatus.setText("已生成 " + img.getWidth() + "×" + img.getHeight()
                    + "｜" + genLevel.getValue().tag() + " 级");
            genExportBtn.setDisable(false);
            copyImgButton.setDisable(false);
            UiKit.hideNotice(genNotice);
        } catch (QrOps.QrException ex) {
            discardGenerated();
            genStatus.setText("生成失败");
            UiKit.showNotice(genNotice, ex.getMessage());
        } catch (Exception ex) {
            discardGenerated();
            genStatus.setText("生成失败");
            UiKit.showNotice(genNotice, "生成失败：" + ex.getMessage());
        }
    }

    /** 丢弃当前生成的图并同步禁用导出/复制，避免导出到过期内容。 */
    private void discardGenerated() {
        generated = null;
        genPreview.setImage(null);
        genExportBtn.setDisable(true);
        copyImgButton.setDisable(true);
    }

    /**
     * JavaFX 的 {@link Color} 用的是 0.0–1.0 的归一化分量，
     * 而 {@link java.awt.Color} 要 0–255 的整数 —— 必须乘 255 再取整。
     *
     * <p>漏掉这个换算是极隐蔽的 bug：白 (1.0,1.0,1.0) 会变成 {@code #010101}，
     * 而黑 (0.0,0.0,0.0) 恰好仍是 {@code #000000}，所以「黑对了」会让人误判整段逻辑没问题。
     */
    private java.awt.Color toAwt(Color c) {
        return c == null ? java.awt.Color.BLACK : new java.awt.Color(
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }

    /** 按内容猜一个合适的文件名。 */
    private String suggestFileName(String text) {
        String base = text.trim();
        int nl = base.indexOf('\n');
        if (nl > 0) {
            base = base.substring(0, nl);
        }
        // 去掉协议与不适合做文件名的字符
        base = base.replaceFirst("^https?://", "");
        base = base.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        if (base.length() > 40) {
            base = base.substring(0, 40);
        }
        if (base.isBlank()) {
            base = "qrcode";
        }
        return base + ".png";
    }

    private void exportGenerated() {
        if (generated == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出二维码");
        chooser.setInitialFileName(generatedName);
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PNG 图片", "*.png"));
        File f = chooser.showSaveDialog(null);
        if (f == null) {
            return;
        }
        try {
            javax.imageio.ImageIO.write(generated, "png", f);
            genStatus.setText("已导出到 " + f.getName());
            UiKit.hideNotice(genNotice);
        } catch (Exception ex) {
            UiKit.showNotice(genNotice, "导出失败：" + ex.getMessage());
        }
    }

    private void copyGeneratedToClipboard() {
        if (generated == null) {
            return;
        }
        try {
            WritableImage fx = SwingFXUtils.toFXImage(generated, null);
            javafx.scene.input.ClipboardContent content =
                    new javafx.scene.input.ClipboardContent();
            content.putImage(fx);
            Clipboard.getSystemClipboard().setContent(content);
            genStatus.setText("二维码图片已复制到剪贴板");
        } catch (Exception ex) {
            UiKit.showNotice(genNotice, "复制失败：" + ex.getMessage());
        }
    }

    // ================================================================ 测试钩子

    /**
     * 测试用：载入一张图，**同步**跑一遍解析链路并把结果写回界面。
     *
     * <p>为什么不直接调 {@code decodeCurrent()}：那个方法把解码放进后台线程、
     * 用 {@code Platform.runLater} 回写结果。在探针的 {@code start()} 里
     * 等待 UI 线程消费该队列会死锁 —— 当前就在 UI 线程上。
     * 所以这里走「同步版」：同样的裁切判定 + 同样的结果写入代码路径，
     * 只是不起线程，验证目的完全一致。
     *
     * @return 结果框最终显示的文本（无结果返回 null）
     */
    String decodeImageForTest(BufferedImage img) {
        setDecodeImage(img, "test");

        BufferedImage target = decodeSource;
        String scope = "整图";
        if (selectionRect.isVisible() && selectionRect.getWidth() >= 8) {
            BufferedImage cropped = cropFromSelection();
            if (cropped != null) {
                target = cropped;
                scope = "框选区域";
            }
        }

        List<QrOps.Decoded> results = QrOps.decodeRobust(target);
        decodeMeta.setText(String.format("%s｜%s %d×%d", decodeSourceLabel, scope,
                target.getWidth(), target.getHeight()));
        if (results.isEmpty()) {
            decodeResult.setText("");
            return null;
        }
        decodeResult.setText(results.get(0).text());
        return results.get(0).text();
    }

    /** 测试用：当前框选矩形宽度（用于验证框选逻辑）。 */
    double selectionWidthForTest() {
        return selectionRect.getWidth();
    }

    /** 测试用：当前框选矩形左上角 X（显示坐标）。 */
    double selectionXForTest() {
        return selectionRect.getX();
    }

    /** 测试用：当前框选矩形左上角 Y（显示坐标）。 */
    double selectionYForTest() {
        return selectionRect.getY();
    }

    /** 测试用：当前框选矩形高度（用于验证框选逻辑）。 */
    double selectionHeightForTest() {
        return selectionRect.getHeight();
    }

    /**
     * 测试用：模拟在框选层上按下并拖动到目标点，**走真实的事件处理器实现**。
     *
     * <p>为什么不直接 {@code setSelectionForTest}：那个方法只是往属性里塞值，
     * 绕过了 {@code imageLocalPoint()} 的坐标换算 —— 而本次要复现的
     * 「框选起点不跟随鼠标落点」bug 恰好就藏在换算里。
     * 只塞值等于测了个假场景。
     *
     * <p>为什么不用 {@code Event.fireEvent}：JavaFX 的鼠标事件派发依赖
     * 完整的 Scene/Window 与 pick 结果，在探针的离屏环境下
     * {@code fireEvent} 出来的事件到不了 handler（实测 handler 完全没被调用）。
     * 所以这里**直接调用 handler 本体** —— handler 内部仍是
     * {@code imageLocalPoint()} + 真实的 {dragStart, selectionRect} 逻辑，
     * 待验证的换算代码一行都没绕过。
     */
    void simulateDragForTest(double startX, double startY, double endX, double endY) {
        selectionLayer.getOnMousePressed().handle(
                mouseEvent(javafx.scene.input.MouseEvent.MOUSE_PRESSED, startX, startY));
        selectionLayer.getOnMouseDragged().handle(
                mouseEvent(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, endX, endY));
    }

    /** 测试用：结束一次拖拽（模拟松开鼠标）。 */
    void simulateDragReleaseForTest() {
        if (selectionLayer.getOnMouseReleased() != null) {
            selectionLayer.getOnMouseReleased().handle(
                    mouseEvent(javafx.scene.input.MouseEvent.MOUSE_RELEASED, 0, 0));
        }
    }

    private static javafx.scene.input.MouseEvent mouseEvent(
            javafx.event.EventType<javafx.scene.input.MouseEvent> type, double x, double y) {
        return new javafx.scene.input.MouseEvent(type, x, y, x, y,
                javafx.scene.input.MouseButton.PRIMARY, 1,
                false, false, false, false,
                true, false, false, false, false, false, null);
    }

    /** 测试用：模拟一次框选（显示坐标，单位为预览图上的像素）。 */
    void setSelectionForTest(double x, double y, double w, double h) {
        selectionRect.setX(x);
        selectionRect.setY(y);
        selectionRect.setWidth(w);
        selectionRect.setHeight(h);
        selectionRect.setVisible(true);
        decodeClearSelBtn.setDisable(false);
    }

    /** 测试用：按框选区域解析（走真实的 cropFromSelection 换算）。 */
    String decodeWithSelectionForTest() {
        BufferedImage cropped = cropFromSelection();
        if (cropped == null) {
            return null;
        }
        List<QrOps.Decoded> r = QrOps.decodeRobust(cropped);
        return r.isEmpty() ? null : r.get(0).text();
    }

    /** 测试用：当前解析结果框的文本。 */
    String decodeResultTextForTest() {
        return decodeResult == null ? null : decodeResult.getText();
    }

    /**
     * 测试用：清空解析结果框。
     *
     * <p>供异步回归断言（7b）使用 —— 重放粘贴前必须先清空，否则上一轮遗留的
     * 文本会让「结果非空」这个完成判据立刻为真，断言就退化成读旧值。
     */
    void clearDecodeResultForTest() {
        if (decodeResult != null) {
            decodeResult.clear();
        }
    }

    /** 测试用：生成一张码并返回是否成功。 */
    boolean generateForTest(String text, int size, QrOps.Level level) {
        genInput.setText(text);
        genSize.getValueFactory().setValue(size);
        genLevel.getSelectionModel().select(level);
        generate();
        return generated != null;
    }

    /** 测试用：生成的图像。 */
    BufferedImage generatedForTest() {
        return generated;
    }

    /** 测试用：当前解析图。 */
    BufferedImage decodeSourceForTest() {
        return decodeSource;
    }

    /** 测试用：生成页 ImageView 当前挂载的图（用于确认预览已真正更新）。 */
    javafx.scene.image.Image genPreviewImageForTest() {
        return genPreview == null ? null : genPreview.getImage();
    }

    /**
     * 测试用：预览图在界面上**实际显示的宽度**（已按等比缩放换算）。
     *
     * <p>注意不是原图宽度 —— 原图会被 {@code PREVIEW_MAX} 等比缩到上限内。
     * 早先这里错误地返回了 {@code img.getWidth()}（原图宽），
     * 导致依赖它的框选坐标断言实际上退化成了「原图坐标 == 显示坐标」，
     * 恒等成立，测不出任何缩放/偏移问题。
     */
    double previewShownWidthForTest() {
        javafx.scene.image.Image img = decodePreview.getImage();
        if (img == null) {
            return 0;
        }
        return displaySize(img.getWidth(), img.getHeight())[0];
    }

    /** 测试用：预览图在界面上**实际显示的高度**（已按等比缩放换算）。 */
    double previewShownHeightForTest() {
        javafx.scene.image.Image img = decodePreview.getImage();
        if (img == null) {
            return 0;
        }
        return displaySize(img.getWidth(), img.getHeight())[1];
    }

    /** 测试用：框选层（selectionLayer）当前的实际尺寸，用于校验与图片显示尺寸一致。 */
    double selectionLayerWidthForTest() {
        return selectionLayer == null ? -1 : selectionLayer.getWidth();
    }

    /** 测试用：框选层（selectionLayer）当前的实际高度。 */
    double selectionLayerHeightForTest() {
        return selectionLayer == null ? -1 : selectionLayer.getHeight();
    }

    /** 测试用：图片（decodePreview）相对框选层左上角的偏移 X（正常应为 0）。 */
    double previewOffsetXForTest() {
        return decodePreview == null ? -1 : decodePreview.getBoundsInParent().getMinX();
    }

    /** 测试用：图片（decodePreview）相对框选层左上角的偏移 Y（正常应为 0）。 */
    double previewOffsetYForTest() {
        return decodePreview == null ? -1 : decodePreview.getBoundsInParent().getMinY();
    }

    /** 测试用：解析页预览 ImageView 当前挂载的图。 */
    javafx.scene.image.Image decodePreviewImageForTest() {
        return decodePreview == null ? null : decodePreview.getImage();
    }

    /**
     * 测试用：模拟一次 Ctrl+V。
     *
     * <p>刻意走**真实的过滤器链路**：把按键事件派发到本模块所在的 Scene 上，
     * 而非直接调用 {@link #pasteFromClipboard()}。这样断言才覆盖得住
     * 「过滤器到底装没装对节点」—— 历史 bug 正是装错了节点，
     * 直接调 pasteFromClipboard() 是测不出来的。
     *
     * @return 是否真的触发了粘贴（以「预览区是否新挂上图」为判据，
     *         比 {@code KeyEvent.isConsumed()} 可靠 —— 事件派发结束后
     *         消费标记未必还读得到）
     */
    boolean firePasteShortcutForTest() {
        if (rootNode == null || rootNode.getScene() == null) {
            return false;
        }
        javafx.scene.image.Image before = decodePreview.getImage();
        javafx.scene.input.KeyEvent ev = new javafx.scene.input.KeyEvent(
                javafx.scene.input.KeyEvent.KEY_PRESSED,
                "", "", javafx.scene.input.KeyCode.V,
                false, true, false, false);
        javafx.event.Event.fireEvent(rootNode.getScene(), ev);
        // 过滤器是同步执行的，事件派发返回时粘贴已完成
        return decodePreview.getImage() != before && decodePreview.getImage() != null;
    }

    /** 测试用：把一张图放进系统剪贴板（模拟截图工具复制）。 */
    static void putImageOnClipboardForTest(BufferedImage img) {
        javafx.scene.image.WritableImage wi = SwingFXUtils.toFXImage(img, null);
        javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
        cc.putImage(wi);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
    }

    /**
     * 测试用：以 <b>AWT 方式</b>把一张图放进系统剪贴板。
     *
     * <p><b>为什么必须有这个钩子</b>：{@link #putImageOnClipboardForTest} 走的是
     * JavaFX 通道，而 FX 通道本身是完好的 —— 用它做的回归断言**测不出**
     * 「AWT ↔ FX 互操作把 alpha 丢光」这个 bug（矩阵实验已证实：FX 写 / FX 读
     * 像素完好）。只有用 AWT 写入，才能复刻真实截图工具的行为，
     * 让「FX 读取会得到全透明图」这一缺陷在测试中真实发生，
     * 从而保证 {@link #readAwtClipboardImage()} 这条修复路径被真正覆盖到。
     *
     * <p>刻意用 {@code TYPE_INT_ARGB}（非预乘）承载数据，与截图工具产物一致。
     */
    static void putImageOnAwtClipboardForTest(BufferedImage img) {
        if (img == null) {
            return;
        }
        java.awt.datatransfer.Transferable transferable =
                new java.awt.datatransfer.Transferable() {
                    @Override
                    public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() {
                        return new java.awt.datatransfer.DataFlavor[]{
                                java.awt.datatransfer.DataFlavor.imageFlavor};
                    }

                    @Override
                    public boolean isDataFlavorSupported(
                            java.awt.datatransfer.DataFlavor flavor) {
                        return java.awt.datatransfer.DataFlavor.imageFlavor.equals(flavor);
                    }

                    @Override
                    public Object getTransferData(
                            java.awt.datatransfer.DataFlavor flavor)
                            throws java.awt.datatransfer.UnsupportedFlavorException {
                        if (!java.awt.datatransfer.DataFlavor.imageFlavor.equals(flavor)) {
                            throw new java.awt.datatransfer.UnsupportedFlavorException(flavor);
                        }
                        return img;
                    }
                };
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(transferable, null);
    }
}
