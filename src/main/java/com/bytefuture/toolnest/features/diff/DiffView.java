package com.bytefuture.toolnest.features.diff;

import com.bytefuture.toolnest.ui.components.Icons;
import com.bytefuture.toolnest.ui.components.UiKit;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 文本对比的两栏界面。
 *
 * <h2>两栏各自承担什么</h2>
 * <ul>
 *   <li><b>左 / 右</b>：原文与修改后，<b>按行严格对齐</b>（该侧没有对应行时画斜纹占位行），
 *       差异染色直接画在行上 —— 判断「这处改得对不对」的锚点就在这里。</li>
 *   <li><b>底部状态条</b>：只回答「有多少」—— 两侧的行数 / 字符数 / 差异处数。</li>
 * </ul>
 *
 * <h2>输入方式：面板双态</h2>
 * <p>JavaFX 的 {@code TextArea} 无法做行级染色，所以两侧面板有「编辑」与「对比」两态：
 * 编辑态是等宽文本域（空面板进来即可粘贴），对比态是染色行视图。
 * 两者共用同一份文本，切换不丢内容，切换时也不重算。
 */
final class DiffView {

    /** 击键后多久重算 —— 太短会在长文本上卡顿，太长会让人以为没反应。 */
    private static final double DEBOUNCE_MS = 250;

    /** 左右两栏的首选宽度。取值本身无所谓，关键是两栏必须一样（见 {@code region()}）。 */
    private static final double PANE_PREF_WIDTH = 320;

    // ==================== 状态 ====================

    private DiffOps.Options options = DiffOps.Options.defaults();
    private DiffOps.Result result;
    private boolean syncGuard = false;
    private final PauseTransition debounce = new PauseTransition(Duration.millis(DEBOUNCE_MS));

    // ==================== 控件 ====================

    private final TextArea leftEditor = editor("在此粘贴或拖入原文…", "diff-editor-left");
    private final TextArea rightEditor = editor("在此粘贴或拖入修改后的文本…", "diff-editor-right");

    private final VBox leftRows = rowContainer("diff-code-left");
    private final VBox rightRows = rowContainer("diff-code-right");
    private final ScrollPane leftScroll = rowScroll(leftRows, "diff-scroll-left");
    private final ScrollPane rightScroll = rowScroll(rightRows, "diff-scroll-right");
    private final VBox leftBody = new VBox(leftScroll, leftEditor);
    private final VBox rightBody = new VBox(rightScroll, rightEditor);

    private final Label leftCount = countLabel();
    private final Label rightCount = countLabel();

    private final CheckBox syncBox = new CheckBox("同步滚动");
    private final Button leftToggle = paneAction();
    private final Button rightToggle = paneAction();

    private final Label footStats = new Label();
    private final Button copyPatchButton = UiKit.secondaryButton("复制差异");
    private final Button clearButton = UiKit.dangerButton("清空");

    private final List<CheckBox> optionBoxes = new ArrayList<>();

    private boolean leftEditing = true;
    private boolean rightEditing = true;
    private boolean bothFilled = false;

    // ==================== 装配 ====================

    Node build() {
        VBox root = UiKit.page("文本对比", "左原文、右修改后，逐行对齐着色。");
        root.getChildren().addAll(toolbar(), region(), footer());

        debounce.setOnFinished(e -> recompute());
        leftEditor.textProperty().addListener((o, old, now) -> debounce.playFromStart());
        rightEditor.textProperty().addListener((o, old, now) -> debounce.playFromStart());
        bindSyncScroll();
        // 两侧都要初始化：只调一次的话另一侧两个视图会同时可见（叠在一起）
        setEditing(true, true);
        setEditing(false, true);
        recomputeNow();
        return root;
    }

    private Node toolbar() {
        ToggleGroup group = new ToggleGroup();
        ToggleButton byLine = grain("按行", group, DiffOps.Granularity.LINE, false);
        ToggleButton byWord = grain("按词", group, DiffOps.Granularity.WORD, true);
        ToggleButton byChar = grain("按字符", group, DiffOps.Granularity.CHAR, false);

        HBox segmented = new HBox(byLine, byWord, byChar);
        segmented.getStyleClass().add("segmented");

        CheckBox ignoreSpace = option("忽略空白");
        CheckBox ignoreCase = option("忽略大小写");
        CheckBox ignoreBlank = option("忽略空行");

        syncBox.setSelected(true);

        Button swap = UiKit.quietButton("交换左右");
        swap.setGraphic(Icons.of("flip", 14, "icon-accent"));
        swap.setOnAction(e -> {
            String l = text(leftEditor);
            String r = text(rightEditor);
            leftEditor.setText(r);
            rightEditor.setText(l);
            recomputeNow();
        });

        HBox bar = new HBox(12, segmented, ignoreSpace, ignoreCase, ignoreBlank,
                UiKit.spacer(), syncBox, swap);
        bar.getStyleClass().add("diff-toolbar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    /** 忽略规则的勾选项。三项任一变化都要立刻重算 —— 它改变的是「算不算相同」的定义。 */
    private CheckBox option(String text) {
        CheckBox box = new CheckBox(text);
        box.selectedProperty().addListener((o, was, now) -> recomputeNow());
        optionBoxes.add(box);
        return box;
    }

    /**
     * 粒度分段项。
     *
     * <p>{@code ToggleButton} 在已选中状态再点一次会取消选中，而「粒度」必须恒有取值
     * （没有「不比对」这一档），因此取消选中的点击要原样选回来。
     */
    private ToggleButton grain(String text, ToggleGroup group,
                               DiffOps.Granularity value, boolean selected) {
        ToggleButton button = new ToggleButton(text);
        button.setToggleGroup(group);
        button.setSelected(selected);
        button.getStyleClass().add("segmented-item");
        button.setOnAction(e -> {
            if (!button.isSelected()) {
                button.setSelected(true);
                return;
            }
            options = options.withGranularity(value);
            recomputeNow();
        });
        return button;
    }

    private Node region() {
        VBox leftPane = pane(true);
        VBox rightPane = pane(false);

        HBox.setHgrow(leftPane, Priority.ALWAYS);
        HBox.setHgrow(rightPane, Priority.ALWAYS);
        leftPane.setMaxWidth(Double.MAX_VALUE);
        rightPane.setMaxWidth(Double.MAX_VALUE);
        // 两栏的最小宽度与首选宽度都必须显式拉齐，否则栏宽由内容决定 ——
        // 只在一侧贴了长文本时，那一栏的 TextArea 会把它的首选宽撑大、抢走另一栏的宽度，
        // 分隔线随之整条横向平移（实测同样的窗口下分隔线 x 会在 517 / 523 / 573 之间跳）。
        // 注意光把 minWidth 归零不够：HBox 是按「各子节点首选宽 + 均摊剩余」分配的，
        // 首选宽不同就仍然不等宽。两处都拉齐后两栏恒等宽、分隔线恒居中。
        leftPane.setMinWidth(0);
        rightPane.setMinWidth(0);
        leftPane.setPrefWidth(PANE_PREF_WIDTH);
        rightPane.setPrefWidth(PANE_PREF_WIDTH);

        HBox region = new HBox(leftPane, divider(), rightPane);
        region.getStyleClass().add("diff-region");
        // 首选高度刻意压小：详情页的统一 ScrollPane 用「容器首选高度」当页面高度，
        // 这里若不压低，两栏会按内容高度把整页撑出一条外层滚动条，
        // 而滚动本该发生在每一栏的内部。
        region.setMinHeight(220);
        region.setPrefHeight(260);
        VBox.setVgrow(region, Priority.ALWAYS);
        return region;
    }

    private VBox pane(boolean left) {
        TextArea area = left ? leftEditor : rightEditor;
        Label count = left ? leftCount : rightCount;
        Button toggle = left ? leftToggle : rightToggle;
        VBox body = left ? leftBody : rightBody;
        ScrollPane scroll = left ? leftScroll : rightScroll;

        Label title = new Label(left ? "原文" : "修改后");
        title.getStyleClass().add("diff-pane-title");

        toggle.setOnAction(e -> setEditing(left, !(left ? leftEditing : rightEditing)));

        Button load = UiKit.quietButton("载入");
        load.setGraphic(Icons.of("folder", 13, "icon-accent"));
        load.setOnAction(e -> loadFile(left));

        HBox head = new HBox(8, title, UiKit.spacer(), count, load, toggle);
        head.getStyleClass().add("diff-pane-head");
        head.setAlignment(Pos.CENTER_LEFT);

        // 高度必须从面板一路放开到叶子：VBox 的 maxHeight 是「各子节点 max 之和」，
        // 而 Control（这里是 TextArea / ScrollPane）的 max 默认是 USE_PREF_SIZE ——
        // 只给子节点设 Vgrow 而不放开 max，body 会被自己的 max 卡在自然高度上，
        // 面板下半部分就留出一片空白（与「32px 按钮被压成 18px」是同一类坑，方向相反）。
        VBox.setVgrow(body, Priority.ALWAYS);
        body.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox.setVgrow(area, Priority.ALWAYS);
        scroll.setMaxHeight(Double.MAX_VALUE);
        area.setMaxHeight(Double.MAX_VALUE);
        body.getStyleClass().add("diff-body");

        // 拖入文件即载入 —— 高亮用自定义伪类（:drag-over 不是 JavaFX 内置伪类），
        // 三处都要切换，缺一处高亮就会卡住不消失。
        body.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
                UiKit.setDragOver(body, true);
            }
            e.consume();
        });
        body.setOnDragExited(e -> UiKit.setDragOver(body, false));
        body.setOnDragDropped(e -> {
            UiKit.setDragOver(body, false);
            Dragboard board = e.getDragboard();
            boolean ok = false;
            if (board.hasFiles() && !board.getFiles().isEmpty()) {
                ok = applyFile(left, board.getFiles().get(0));
            }
            e.setDropCompleted(ok);
            e.consume();
        });

        VBox pane = new VBox(head, body);
        pane.getStyleClass().add(left ? "diff-left-pane" : "diff-right-pane");

        // 行底色会一路铺到面板边缘，不裁切的话 8px 圆角会被盖成直角
        Rectangle clip = new Rectangle();
        clip.setArcWidth(16);
        clip.setArcHeight(16);
        clip.widthProperty().bind(pane.widthProperty());
        clip.heightProperty().bind(pane.heightProperty());
        pane.setClip(clip);
        return pane;
    }

    private Node footer() {
        footStats.getStyleClass().add("diff-foot-stats");

        copyPatchButton.setGraphic(Icons.of("copy", 13, "icon-accent"));
        copyPatchButton.setOnAction(e -> UiKit.copyToClipboard(
                DiffOps.patchText(result)));
        clearButton.setGraphic(Icons.of("x", 13, "icon-danger"));
        clearButton.setOnAction(e -> {
            debounce.stop();
            leftEditor.clear();
            rightEditor.clear();
            recomputeNow();
        });

        HBox foot = new HBox(8, footStats, UiKit.spacer(), copyPatchButton, clearButton);
        foot.getStyleClass().add("diff-foot");
        foot.setAlignment(Pos.CENTER_LEFT);
        return foot;
    }

    // ==================== 交互 ====================

    private void setEditing(boolean left, boolean editing) {
        if (left) {
            leftEditing = editing;
        } else {
            rightEditing = editing;
        }
        VBox body = left ? leftBody : rightBody;
        ScrollPane scroll = left ? leftScroll : rightScroll;
        TextArea area = left ? leftEditor : rightEditor;
        Button toggle = left ? leftToggle : rightToggle;

        scroll.setVisible(!editing);
        scroll.setManaged(!editing);
        area.setVisible(editing);
        area.setManaged(editing);
        toggle.setText(editing ? "完成" : "编辑");
        body.setAccessibleText((left ? "原文" : "修改后") + (editing ? "编辑区" : "对比区"));
    }

    private void loadFile(boolean left) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(left ? "载入原文" : "载入修改后的文本");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "文本文件", "*.txt", "*.md", "*.json", "*.java", "*.xml", "*.yml", "*.yaml",
                "*.csv", "*.log", "*.properties", "*.ini", "*.conf", "*.sql", "*.html"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("所有文件", "*.*"));
        Window window = leftEditor.getScene() == null ? null : leftEditor.getScene().getWindow();
        File file = chooser.showOpenDialog(window);
        if (file != null) {
            applyFile(left, file);
        }
    }

    private boolean applyFile(boolean left, File file) {
        try {
            String content = DiffOps.readTextFile(file);
            (left ? leftEditor : rightEditor).setText(content);
            recomputeNow();
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /** 两侧按对齐行同步滚动：合并行数相同，所以镜像 vvalue 即可。 */
    private void bindSyncScroll() {
        leftScroll.vvalueProperty().addListener((o, old, now) -> mirror(leftScroll, rightScroll, now.doubleValue()));
        rightScroll.vvalueProperty().addListener((o, old, now) -> mirror(rightScroll, leftScroll, now.doubleValue()));
    }

    private void mirror(ScrollPane from, ScrollPane to, double value) {
        if (syncGuard || !syncBox.isSelected()) {
            return;
        }
        syncGuard = true;
        to.setVvalue(value);
        syncGuard = false;
    }

    private void recomputeNow() {
        debounce.stop();
        recompute();
    }

    /**
     * 两侧都有内容才算「可以比对」。
     *
     * <p>只有一侧时，算法会老老实实算出「另一侧全没了」—— 于是整栏被染成删除色、
     * 底部报出「差异 N 处」。这个结论在事实上成立，在<b>表意上毫无意义</b>：
     * 用户还没输入完，任何差异数字都只会误导。所以凡是差异结论的出口
     * （行级高亮、底部数字、复制按钮）都要先过这一关。
     */
    private boolean readyToCompare() {
        return !text(leftEditor).isEmpty() && !text(rightEditor).isEmpty();
    }

    private void recompute() {
        options = new DiffOps.Options(options.granularity(),
                isSelected("忽略空白"), isSelected("忽略大小写"), isSelected("忽略空行"));
        result = DiffOps.diff(text(leftEditor), text(rightEditor), options);
        renderAll();

        // 对比态的前提是两侧都有内容：满足就自动切进去（用户刚粘完两份文本，想看的就是差异，
        // 不该再让他多点一次「完成」）；一旦退化成只有一侧，就自动退回编辑态。
        // 只在「可以比对」这个条件本身翻转时才动模式，所以用户手动切的模式不会被反复抢走。
        boolean ready = readyToCompare();
        if (ready != bothFilled) {
            setEditing(true, !ready);
            setEditing(false, !ready);
        }
        bothFilled = ready;
    }

    private boolean isSelected(String text) {
        CheckBox box = findCheckBox(text);
        return box != null && box.isSelected();
    }

    private CheckBox findCheckBox(String text) {
        for (CheckBox box : optionBoxes) {
            if (box.getText().equals(text)) {
                return box;
            }
        }
        return null;
    }

    // ==================== 渲染 ====================

    private void renderAll() {
        leftCount.setText(result.leftLines() + " 行");
        rightCount.setText(result.rightLines() + " 行");
        buildRows(true);
        buildRows(false);
        renderFooter();
    }

    private void buildRows(boolean left) {
        VBox container = left ? leftRows : rightRows;
        container.getChildren().clear();
        for (DiffOps.DiffRow row : result.rows()) {
            container.getChildren().add(buildRow(row, left));
        }
    }

    private HBox buildRow(DiffOps.DiffRow row, boolean left) {
        boolean has = left ? row.hasLeft() : row.hasRight();

        // 左缘 3px 色条：「修改」用上红下绿双色，一眼看出这一行两端都动过
        Region edge = new Region();
        edge.getStyleClass().add("diff-edge");

        Label mark = new Label(has ? markOf(row.kind()) : "");
        mark.getStyleClass().add("diff-mk");
        Label number = new Label(has ? String.valueOf(left ? row.leftNo() : row.rightNo()) : "");
        number.getStyleClass().add("diff-ln");
        HBox text = textNode(left ? row.leftSpans() : row.rightSpans(), left);

        HBox line = new HBox(edge, mark, number, text);
        // diff-side-* 是给探针用的定位标记（「这一行属于哪一栏」），不承载视觉
        line.getStyleClass().addAll("diff-row", left ? "diff-side-left" : "diff-side-right");
        if (has) {
            line.getStyleClass().add(kindClass(row.kind()));
        } else {
            line.getStyleClass().add("diff-ph");
        }
        return line;
    }

    private static HBox textNode(List<DiffOps.Span> spans, boolean left) {
        HBox box = new HBox();
        box.getStyleClass().add("diff-tx");
        for (DiffOps.Span span : spans) {
            Label piece = new Label(span.text());
            piece.getStyleClass().add("diff-piece");
            if (span.changed()) {
                piece.getStyleClass().add(left ? "diff-word-del" : "diff-word-add");
            }
            box.getChildren().add(piece);
        }
        // 行不换行也不横向滚动：超出部分直接裁掉，否则会溢出到相邻行上
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(box.widthProperty());
        clip.heightProperty().bind(box.heightProperty());
        box.setClip(clip);
        HBox.setHgrow(box, Priority.ALWAYS);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    private void renderFooter() {
        footStats.setText("原文 " + result.leftLines() + " 行 \u00b7 "
                + lengthOf(leftEditor) + " 字符\u3000|\u3000修改后 " + result.rightLines() + " 行 \u00b7 "
                + lengthOf(rightEditor) + " 字符\u3000|\u3000"
                + (readyToCompare() ? "差异 " + result.groups().size() + " 处" : "还差一侧，暂不比对")
                + degradedNote());
        // 不能比对时复制一律置灰：一侧为空算出来的那一堆「删除」没有意义，
        // 还让用户能复制出去，只会把这层误导做实。
        copyPatchButton.setDisable(!(readyToCompare() && !result.groups().isEmpty()));
    }

    /**
     * 降级必须写出来 —— 静默降级比降级本身更糟。
     *
     * <p>逐位比对给出的是<b>简化过的结论</b>（不做对齐，只按行号一对一对着看），
     * 不声明就是在拿简化的结果冒充精确结果：用户会以为「已经全对上了」。
     * 这是唯一还留在界面上的降级出口，所以宁可占几个字也不能省。
     *
     * <p>判定用 {@link DiffOps.Result#degraded()}，<b>不能</b>图省事写成
     * 「两侧都填了就算降级」：那样退回小文本后这句话会粘住不放，用户会以为
     * 后面看到的每个结论都被简化过，反而不信任本来精确算出来的结果。
     */
    private String degradedNote() {
        return result.degraded()
                ? "\u3000|\u3000文本过大，已按行号逐位比对（不做对齐）"
                : "";
    }

    // ==================== 工具 ====================

    private static String text(TextArea area) {
        return area.getText() == null ? "" : area.getText();
    }

    private static int lengthOf(TextArea area) {
        return text(area).length();
    }

    private static String kindClass(DiffOps.Kind kind) {
        return switch (kind) {
            case ADD -> "diff-add";
            case DEL -> "diff-del";
            case MOD -> "diff-mod";
            default -> "diff-same";
        };
    }

    private static String markOf(DiffOps.Kind kind) {
        return switch (kind) {
            case ADD -> "+";
            case DEL -> "\u2212";
            case MOD -> "~";
            default -> "";
        };
    }

    private static Label countLabel() {
        Label label = new Label("0 行");
        label.getStyleClass().add("diff-pane-count");
        return label;
    }

    private static Button paneAction() {
        Button button = UiKit.quietButton("编辑");
        button.getStyleClass().add("diff-pane-action");
        return button;
    }

    private static TextArea editor(String prompt, String styleClass) {
        TextArea area = new TextArea();
        area.setPromptText(prompt);
        area.setWrapText(false);
        area.getStyleClass().addAll("mono-area", "diff-editor", styleClass);
        return area;
    }

    private static VBox rowContainer(String styleClass) {
        VBox box = new VBox();
        box.getStyleClass().add(styleClass);
        return box;
    }

    private static ScrollPane rowScroll(VBox content, String styleClass) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.getStyleClass().add(styleClass);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return scroll;
    }

    private static Region divider() {
        Region region = new Region();
        region.getStyleClass().add("diff-divider");
        return region;
    }
}
