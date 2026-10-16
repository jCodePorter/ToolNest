package com.bytefuture.toolnest.features.http;

import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 表单字段表 —— <b>同时</b>充当 {@code application/x-www-form-urlencoded}
 * 与 {@code multipart/form-data} 两种请求体的编辑器（仿 Postman）。
 *
 * <h2>为什么 Form 与 Form-data 合并成一张表</h2>
 * <p>Postman 的 Body 类型里 {@code form-data} 与 {@code x-www-form-urlencoded}
 * 是<b>两个并列选项、共用同一张表</b>：区别只在于「行里能不能选文件」，
 * 以及按哪种方式编码。旧版给 Form 单独做了一张纯键值表，于是：
 * <ul>
 *   <li>切类型就换节点，两份内容互不相通，来回切要重打一遍；</li>
 *   <li>「填到一半发现要传文件」只能从头再来；</li>
 *   <li>两张表两套列宽、两套行高，改一处漏一处。</li>
 * </ul>
 * 现在只有这一张表：选 <b>Form</b> 时隐藏「类型」列（整表按文本段编码），
 * 选 <b>Form-data</b> 时显示「类型」列（逐行切文本 / 文件）。内容始终是同一份。
 *
 * <h2>为什么不是给 KeyValueTable 加一列</h2>
 * <p>KeyValueTable 的语义是「名字 → 值」，而 multipart 的一行有两种
 * 完全不同的形态：文本段的「值」是一个可编辑的字符串，文件段的「值」是一个
 * 本地路径 + 大小 + 一个选择器。<b>它们的编辑方式不同，就不该是同一列控件。</b>
 *
 * <h2>列宽</h2>
 * <p>勾选 38 / KEY 216 / 类型 76 / VALUE 606，合计 936 —— 与面板同宽
 * （936 = 面板 940 − 外框 1×2 − 内边距 1×2）。类型列定宽 76 是因为
 * 「文本 / 文件」两字最宽不过 24px，加上内边距与下拉箭头正好；
 * 再窄会把「文件」的下拉箭头挤没。
 *
 * <h2>KEY 列为什么也是常驻 TextField</h2>
 * <p>与 {@code KeyValueTable} 同一个理由：{@code TextFieldTableCell} 在
 * 「按下编辑 → 提交」之间，行模型还是旧值（空串）。补尾行的判断读的是模型，
 * 于是永远认为末行是空的（<b>加不上行</b>）；而提交瞬间补行又会重建单元格，
 * 把没提交的文字和焦点一起销毁（<b>打一个字就失焦、内容消失</b>）。
 *
 * <h2>文件行不参与降级</h2>
 * <p>文件路径单元格<b>不</b>走 {@code UiKit.setDegraded} —— 它没有「当前不生效」
 * 这个状态，只有「存在 / 不存在」。文件丢失时明写一行「文件不存在」，
 * 而不是把它压暗（设计稿 §7：「颜色永不是唯一线索」）。
 */
public final class FormDataTable extends VBox {

    /** 行类型：文本段 / 文件段。 */
    public static final String KIND_TEXT = "text";
    public static final String KIND_FILE = "file";

    private static final String LABEL_TEXT = "文本";
    private static final String LABEL_FILE = "文件";

    /** 一行字段。 */
    public static final class Row {
        final SimpleBooleanProperty enabled = new SimpleBooleanProperty(true);
        final StringProperty name = new SimpleStringProperty("");
        final StringProperty kind = new SimpleStringProperty(KIND_TEXT);
        final StringProperty text = new SimpleStringProperty("");
        final StringProperty path = new SimpleStringProperty("");

        Row(String name, String kind, String text, String path) {
            this.name.set(name == null ? "" : name);
            this.kind.set(KIND_FILE.equals(kind) ? KIND_FILE : KIND_TEXT);
            this.text.set(text == null ? "" : text);
            this.path.set(path == null ? "" : path);
        }
    }

    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final TableView<Row> table = new TableView<>(rows);
    /** 「类型」列。声明在 {@link #table} 之后，读起来才是一张表的正常顺序。 */
    private final TableColumn<Row, String> kindCol = kindColumn();
    private Runnable onChanged = () -> {
    };

    /**
     * 是否允许行内出现文件段 —— Form（{@code x-www-form-urlencoded}）为
     * {@code false}，此时隐藏「类型」列，整表按文本段编码。
     */
    private boolean fileRowsAllowed = true;

    /** 批量替换期间静音（与 {@code KeyValueTable} 同一个理由：列表回调里重入改列表）。 */
    private boolean bulkEdit;

    /** 已排了一个「补尾行」任务在队列里。 */
    private boolean appendScheduled;

    public FormDataTable(double height) {
        setSpacing(0);
        getStyleClass().add("kv-table-wrap");

        table.getStyleClass().add("kv-table");
        // 每格自带常驻控件，不再走 TableView 的「编辑」生命周期 ——
        // 那条路上打一个字就会被补行操作重建单元格（失焦 + 内容丢失）。
        table.setEditable(false);
        table.setPrefHeight(height);
        table.setMinHeight(120);
        table.setPlaceholder(new Label("暂无字段"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        table.getColumns().add(checkColumn());
        table.getColumns().add(nameColumn());
        table.getColumns().add(kindCol);
        table.getColumns().add(valueColumn());

        // 起始恰好一行空白行（与 KeyValueTable 的不变量一致）
        rows.add(new Row("", KIND_TEXT, "", ""));
        rows.addListener((javafx.collections.ListChangeListener<Row>) c -> fireChanged());

        VBox.setVgrow(table, Priority.ALWAYS);
        getChildren().add(table);
    }

    /**
     * 切换「这一行能不能是文件段」。
     *
     * <p>Form 模式隐藏「类型」列并把已有文件行按文本段处理
     * （{@code x-www-form-urlencoded} 本来就传不了文件）。
     */
    public void setFileRowsAllowed(boolean allowed) {
        if (fileRowsAllowed == allowed) {
            return;
        }
        fileRowsAllowed = allowed;
        kindCol.setVisible(allowed);
        if (!allowed) {
            // 切到 Form：文件段在这一类型下无法表达，落回文本段，
            // 但保留 path，切回 Form-data 时还能看到原来选的文件。
            for (Row row : rows) {
                if (KIND_FILE.equals(row.kind.get())) {
                    row.kind.set(KIND_TEXT);
                }
            }
        }
        table.refresh();
        fireChanged();
    }

    /** 当前是否允许文件段。 */
    public boolean isFileRowsAllowed() {
        return fileRowsAllowed;
    }

    /** 行内容变化时的回调（用于刷新 Body 页签标题上的 "(2 项, 含 1 个文件)"）。 */
    public void setOnChanged(Runnable listener) {
        this.onChanged = listener == null ? () -> {
        } : listener;
    }

    private void fireChanged() {
        onChanged.run();
    }

    // ------------------------------------------------------------ 列

    private TableColumn<Row, Boolean> checkColumn() {
        TableColumn<Row, Boolean> col = new TableColumn<>("");
        col.setCellValueFactory(c -> c.getValue().enabled);
        col.setCellFactory(c -> new TableCell<>() {
            private final CheckBox box = new CheckBox();

            {
                box.setOnAction(e -> {
                    Row row = getTableRow() == null ? null : getTableRow().getItem();
                    if (row != null) {
                        row.enabled.set(box.isSelected());
                        fireChanged();
                    }
                });
            }

            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                    return;
                }
                box.setSelected(item == null || item);
                setGraphic(box);
                setAlignment(Pos.CENTER);
            }
        });
        col.setPrefWidth(38);
        col.setMinWidth(38);
        col.setMaxWidth(38);
        col.setSortable(false);
        return col;
    }

    private TableColumn<Row, String> nameColumn() {
        TableColumn<Row, String> col = new TableColumn<>("KEY");
        col.setCellValueFactory(c -> c.getValue().name);
        col.setCellFactory(c -> new NameCell());
        col.setPrefWidth(216);
        col.setMinWidth(120);
        col.setSortable(false);
        return col;
    }

    /**
     * KEY 列的常驻输入格。
     *
     * <p>不用 {@code TextFieldTableCell}：它在「按下编辑 → 提交」之间行模型
     * 还是空串，补尾行读不到刚打进去的字（<b>加不上行</b>），而提交瞬间补行
     * 又会重建单元格（<b>失焦 + 内容消失</b>）。
     */
    private final class NameCell extends TableCell<Row, String> {

        private final TextField field = new TextField();
        private Row bound;

        NameCell() {
            field.getStyleClass().add("http-cell-field");
            field.textProperty().addListener((o, old, now) -> {
                if (bound == null) {
                    return;
                }
                String typed = now == null ? "" : now;
                if (!typed.equals(bound.name.get())) {
                    bound.name.set(typed);
                    onRowEdited();
                }
            });
            setGraphic(field);
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            Row row = empty || getTableRow() == null ? null : getTableRow().getItem();
            if (row == null) {
                bound = null;
                field.clear();
                return;
            }
            bound = row;
            if (!row.name.get().equals(field.getText())) {
                field.setText(row.name.get());
            }
        }
    }

    private TableColumn<Row, String> kindColumn() {
        TableColumn<Row, String> col = new TableColumn<>("类型");
        col.setCellValueFactory(c -> c.getValue().kind);
        col.setCellFactory(c -> new TableCell<>() {
            private final ComboBox<String> box = new ComboBox<>();

            {
                box.getItems().addAll(LABEL_TEXT, LABEL_FILE);
                box.getStyleClass().add("button-inline");
                box.setPrefWidth(72);
                box.setMinWidth(72);
                // ComboBox 只在用户交互时派发 ActionEvent；select() 不触发。
                // 所以状态同步只能靠这个监听，不能靠 onAction。
                box.valueProperty().addListener((o, old, now) -> {
                    Row row = getTableRow() == null ? null : getTableRow().getItem();
                    if (row == null || now == null) {
                        return;
                    }
                    String kind = LABEL_FILE.equals(now) ? KIND_FILE : KIND_TEXT;
                    if (!kind.equals(row.kind.get())) {
                        row.kind.set(kind);
                        // 换了形态就得重画 VALUE 单元格：文本段是一个输入框，
                        // 文件段是「选择文件 + 路径 + 大小 + 清除」
                        table.refresh();
                        fireChanged();
                    }
                });
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                    return;
                }
                box.setValue(KIND_FILE.equals(item) ? LABEL_FILE : LABEL_TEXT);
                setGraphic(box);
                setAlignment(Pos.CENTER_LEFT);
            }
        });
        col.setPrefWidth(76);
        col.setMinWidth(76);
        col.setMaxWidth(76);
        col.setSortable(false);
        return col;
    }

    private TableColumn<Row, String> valueColumn() {
        TableColumn<Row, String> col = new TableColumn<>("VALUE");
        col.setCellValueFactory(c -> c.getValue().kind.get().equals(KIND_FILE)
                ? c.getValue().path
                : c.getValue().text);
        col.setCellFactory(c -> new ValueCell());
        col.setPrefWidth(606);
        col.setSortable(false);
        return col;
    }

    /**
     * 一个单元格两种形态。
     *
     * <p>文本形态用<b>复用</b>的 {@link TextField}：{@code updateItem} 会被反复
     * 调用（滚动、刷新、行复用），每次都 {@code new} 一个输入框的话，光是
     * 「补一行空白行」引起的重布局就会把用户正在输入的那个框连同焦点一起换掉
     * —— 又变成「打一个字就失焦」。只有切换形态（文本 ↔ 文件）才真的重建。
     */
    private final class ValueCell extends TableCell<Row, String> {

        private final TextField field = new TextField();
        private Row textRow;

        ValueCell() {
            field.getStyleClass().add("http-cell-field");
            field.setPrefHeight(26);
            field.setMinHeight(26);
            HBox.setHgrow(field, Priority.ALWAYS);
            field.textProperty().addListener((o, old, now) -> {
                if (textRow == null) {
                    return;
                }
                String typed = now == null ? "" : now;
                if (!typed.equals(textRow.text.get())) {
                    textRow.text.set(typed);
                    onRowEdited();
                }
            });
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            Row row = empty || getTableRow() == null ? null : getTableRow().getItem();
            if (row == null) {
                textRow = null;
                setGraphic(null);
                return;
            }
            if (row.kind.get().equals(KIND_FILE)) {
                // 形态切换：文件段要重建（选择器 + 路径 + 大小 + 清除）
                if (textRow != null) {
                    textRow = null;
                }
                setGraphic(fileEditor(row));
                return;
            }
            if (textRow != row) {
                textRow = row;
            }
            if (!row.text.get().equals(field.getText())) {
                field.setText(row.text.get());
            }
            setGraphic(field);
        }

        private HBox fileEditor(Row row) {
            HBox box = new HBox(8);
            box.setAlignment(Pos.CENTER_LEFT);

            Button pick = new Button("选择文件");
            pick.getStyleClass().addAll("button", "button-secondary", "button-inline");
            pick.setOnAction(e -> chooseFile(row));

            Label pathLabel = new Label();
            pathLabel.getStyleClass().add("http-file-name");
            pathLabel.setText(row.path.get().isBlank() ? "（未选择）" : fileName(row.path.get()));
            pathLabel.setTooltip(row.path.get().isBlank() ? null
                    : new Tooltip(row.path.get()));
            HBox.setHgrow(pathLabel, Priority.ALWAYS);

            box.getChildren().addAll(pick, pathLabel);

            Path p = row.path.get().isBlank() ? null : Path.of(row.path.get());
            if (p != null && !Files.isRegularFile(p)) {
                Label missing = new Label("文件不存在");
                missing.getStyleClass().add("http-file-missing");
                box.getChildren().add(missing);
            } else if (p != null) {
                Label size = new Label(sizeText(p));
                size.getStyleClass().add("http-file-size");
                box.getChildren().add(size);
            }

            if (!row.path.get().isBlank()) {
                Button remove = new Button();
                remove.getStyleClass().addAll("button", "button-quiet", "button-inline");
                remove.setGraphic(com.bytefuture.toolnest.ui.components.Icons.of("x", 13, "icon-muted"));
                remove.setAccessibleText("清除文件");
                remove.setOnAction(e -> {
                    row.path.set("");
                    table.refresh();
                    fireChanged();
                });
                box.getChildren().add(remove);
            }
            return box;
        }
    }

    // ------------------------------------------------------------ 行为

    /** 某一行被改动：通知外部刷新页签标题，并在需要时补一行空白行。 */
    private void onRowEdited() {
        fireChanged();
        scheduleTrailingBlank();
    }

    /**
     * 排队补一行空白行。
     *
     * <p>异步是必须的：按键事件没走完就改列表，{@code TableView} 会在同一趟
     * 布局里重建单元格，把用户正在输入的那一格连同焦点与未显示的文字换掉。
     */
    private void scheduleTrailingBlank() {
        if (bulkEdit || appendScheduled) {
            return;
        }
        appendScheduled = true;
        javafx.application.Platform.runLater(() -> {
            appendScheduled = false;
            appendTrailingBlank();
        });
    }

    /**
     * 保证末尾恰好有一行空白行。
     *
     * <p>「空白」的判定看三列：只填了值没填名字的行不能占着尾行，
     * 否则用户会发现「明明填了内容却加不上下一行」。
     */
    private void appendTrailingBlank() {
        if (bulkEdit) {
            return;
        }
        if (rows.isEmpty()) {
            rows.add(new Row("", KIND_TEXT, "", ""));
            fireChanged();
            return;
        }
        Row last = rows.get(rows.size() - 1);
        boolean lastBlank = last.name.get().isBlank()
                && last.text.get().isBlank()
                && last.path.get().isBlank();
        if (!lastBlank) {
            rows.add(new Row("", KIND_TEXT, "", ""));
        }
        fireChanged();
    }

    /**
     * 追加一行并把焦点送进 KEY 格 —— 显式的「新增字段」入口。
     *
     * <p>不复用 {@link #appendTrailingBlank()}：那个是「保证末行是空的」，
     * 末行本来就空时什么都不做；而这里是「<b>多给我一行</b>」——
     * 空表上点它必须真的多出一行。
     */
    public void addRow() {
        if (!bulkEdit) {
            rows.add(new Row("", KIND_TEXT, "", ""));
            fireChanged();
        }
        javafx.application.Platform.runLater(() -> {
            TextField field = cellField(rows.size() - 1, 0);
            if (field != null) {
                field.requestFocus();
            }
        });
    }

    /**
     * 取某一行某一列的常驻输入框。
     *
     * <p>{@code TableView.getRow(int)} 与 {@code TableRow.getCell(int)} 都不是
     * 公开 API，只能顺着场景图找：数 {@code TableRow} 定行号，再在行内按列序
     * 数 {@link TableCell} 取它的 graphic。
     */
    private TextField cellField(int rowIndex, int column) {
        if (rowIndex < 0) {
            return null;
        }
        int seenRows = 0;
        for (javafx.scene.Node node : table.lookupAll(".table-row-cell")) {
            if (!(node instanceof TableRow<?> row)) {
                continue;
            }
            if (seenRows++ != rowIndex) {
                continue;
            }
            int seenCells = 0;
            for (javafx.scene.Node child : row.getChildrenUnmodifiable()) {
                if (!(child instanceof TableCell<?, ?> cell)) {
                    continue;
                }
                if (seenCells++ == column + 1 && cell.getGraphic() instanceof TextField field) {
                    return field;
                }
            }
            return null;
        }
        return null;
    }

    private void chooseFile(Row row) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择要上传的文件");
        String current = row.path.get();
        if (!current.isBlank()) {
            Path parent = Path.of(current).getParent();
            if (parent != null && Files.isDirectory(parent)) {
                chooser.setInitialDirectory(parent.toFile());
            }
        }
        File file = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (file != null) {
            row.path.set(file.getAbsolutePath());
            if (row.name.get().isBlank()) {
                row.name.set(file.getName());
            }
            table.refresh();
            fireChanged();
        }
    }

    private static String fileName(String path) {
        String p = path.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    private static String sizeText(Path path) {
        try {
            return formatSize(Files.size(path));
        } catch (Exception unreadable) {
            return "";
        }
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** 勾选且名字非空的字段。 */
    public List<HttpRequestSpec.FormField> fields() {
        List<HttpRequestSpec.FormField> list = new ArrayList<>();
        for (Row row : rows) {
            if (!row.enabled.get() || row.name.get().isBlank()) {
                continue;
            }
            if (KIND_FILE.equals(row.kind.get())) {
                list.add(HttpRequestSpec.FormField.file(row.name.get().trim(), row.path.get()));
            } else {
                list.add(HttpRequestSpec.FormField.text(row.name.get().trim(), row.text.get()));
            }
        }
        return list;
    }

    /** 有效字段数（不含尾部空行与未勾选行）。 */
    public int entryCount() {
        return fields().size();
    }

    /** 其中文件段的数量 —— 页签标题上的「含 N 个文件」用它。 */
    public int fileCount() {
        int n = 0;
        for (HttpRequestSpec.FormField f : fields()) {
            if (f.isFile()) {
                n++;
            }
        }
        return n;
    }

    /** 清空并只留一行空行（与 KeyValueTable 的不变量一致）。 */
    public void clear() {
        replaceRows(List.of());
    }

    /** 整表替换：批量期间静音，收尾时只留一行空白行。 */
    private void replaceRows(List<Row> next) {
        bulkEdit = true;
        try {
            List<Row> target = new ArrayList<>(next);
            target.add(new Row("", KIND_TEXT, "", ""));
            rows.setAll(target);
        } finally {
            bulkEdit = false;
        }
        table.refresh();
        fireChanged();
    }

    TableView<Row> tableForTest() {
        return table;
    }

    /** 测试用：立刻执行待办的「补尾行」（理由同 {@code KeyValueTable}）。 */
    public void flushPendingAppendForTest() {
        appendScheduled = false;
        appendTrailingBlank();
    }

    /** 测试用：总行数（含尾部空行）。 */
    public int rowCountForTest() {
        return rows.size();
    }

    /** 测试用：某一行是否勾选。 */
    public boolean rowEnabledForTest(int index) {
        return index >= 0 && index < rows.size() && rows.get(index).enabled.get();
    }

    /** 测试用：取某一行某一列的常驻输入框（0 = KEY，1 = VALUE）。 */
    public TextField cellFieldForTest(int index, int column) {
        return cellField(index, column);
    }

    /** 测试用：某一行的行类型（{@link #KIND_TEXT} / {@link #KIND_FILE}）。 */
    public String rowKindForTest(int index) {
        return index >= 0 && index < rows.size() ? rows.get(index).kind.get() : null;
    }

    /**
     * 测试用：塞一行文本字段。
     *
     * <p>探针要断言「Form 模式下这些键值真的被百分号编码发出去」，
     * 而 Form 与 Form-data 现在共用这张表 —— 入口自然也只有一个。
     */
    void setTextFieldsForTest(String key, String value) {
        // 插在尾部空行「之前」：直接 add 会排到空行后面，表首就多出一个空行，
        // 后面按行号取值的断言会全部错位。
        rows.add(Math.max(0, rows.size() - 1),
                new Row(key == null ? "" : key, KIND_TEXT, value == null ? "" : value, ""));
        table.refresh();
        fireChanged();
    }

    /**
     * 测试用：把某一行直接设成文件段。
     *
     * <p>探针跑在无头环境里，不能弹系统文件对话框 —— 但「文件行能不能被
     * 断言、文件字节能不能进 multipart」正是本轮要验的东西，所以留一个
     * 绕开对话框的入口。
     */
    void setFileForTest(int index, String name, String path) {
        while (rows.size() <= index) {
            rows.add(new Row("", KIND_TEXT, "", ""));
        }
        Row row = rows.get(index);
        row.name.set(name == null ? "" : name);
        row.kind.set(KIND_FILE);
        row.path.set(path == null ? "" : path);
        table.refresh();
        fireChanged();
    }
}
