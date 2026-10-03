package com.bytefuture.toolnest.ui.components;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 键值对编辑表格（仿 Postman 的 Params / Headers 面板）。
 *
 * <p>带勾选框控制该行是否参与请求，空行自动跳过。表格永远保留一行空白，
 * 方便连续录入。
 *
 * <h2>为什么不用 {@code TextFieldTableCell}</h2>
 * <p>旧版 KEY / VALUE 两列都用 {@code TextFieldTableCell} —— 它是
 * <b>「按下编辑 → 改完提交」</b>的模型：编辑期间文字只存在于编辑器里，
 * <b>行模型仍然是编辑前的旧值（通常是空串）</b>。由此串出三个实测缺陷：
 * <ol>
 *   <li><b>输入后失焦、内容消失</b>：{@code onEditCommit} 写模型时会触发
 *       「末尾补一行空行」，而 {@code ObservableList} 的增删会让
 *       {@code TableView} 重建单元格 —— 正在编辑的编辑器连同未提交的文字
 *       一起被销毁。用户表现为「刚打完字光标就跑了、字没了」。</li>
 *   <li><b>无法新增参数</b>：{@code setOnKeyTyped} 里判断「末行是不是空行」，
 *       读的是<b>未提交的空模型</b>，永远得到「是空行」→ 永远不补行。</li>
 *   <li><b>清空 / 批量填充后行数失控</b>：{@code setEntries} 逐条 {@code add}，
 *       每条都触发一次「补空行」，再叠上末尾两行显式空行 → 一条
 *       {@code Content-Type} 变成 5 行。</li>
 * </ol>
 *
 * <p>现在每格是一个<b>常驻</b> {@link TextField}（graphic，不是 editor）：
 * 打字即写通行模型，不存在「提交」这一步，上面三条同时消失。
 * 补行改为「发现末行有内容 → {@link Platform#runLater} 异步补」，
 * 绝不在按键事件里改动列表。
 */
public final class KeyValueTable extends VBox {

    /** 一行数据。 */
    public static final class Row {
        final BooleanProperty enabled = new SimpleBooleanProperty(true);
        final StringProperty key = new SimpleStringProperty("");
        final StringProperty value = new SimpleStringProperty("");

        public Row(String key, String value) {
            this.key.set(key == null ? "" : key);
            this.value.set(value == null ? "" : value);
        }

        /** KEY 与 VALUE 都为空才算空行（只看 KEY 会让「填了值没填名」的行吃掉尾行）。 */
        boolean isBlank() {
            return key.get().isBlank() && value.get().isBlank();
        }
    }

    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final TableView<Row> table = new TableView<>(rows);
    private final Label placeholderLabel = new Label("暂无内容");
    private final javafx.beans.property.IntegerProperty rowsCount =
            new javafx.beans.property.SimpleIntegerProperty(0);

    /**
     * 批量替换期间 suppresses 掉「补尾行」与计数刷新。
     *
     * <p>在 {@link javafx.collections.ObservableList} 自己的变更回调里
     * 再改这个列表是重入操作（{@code setAll} → 回调 → {@code add} → 又一次回调），
     * 既可能抛并发异常，也会算出错误的行数。批量替换一律「先拼好完整列表，
     * 一次性 {@code setAll}，最后统一收尾」。
     */
    private boolean bulkEdit;

    /** 已排了一个「补尾行」任务在队列里，避免连按字符时排一堆。 */
    private boolean appendScheduled;

    /**
     * VALUE 列失焦时的值规范化（如给 {@code Authorization} 补 Bearer 前缀）。
     *
     * <p>只在失焦那一刻跑，不在按键时跑 —— 边打边改会把用户刚敲的字符顶走。
     * 传 null（默认）表示不做任何加工。
     *
     * <p><b>必须按 key 分派</b>，不能对每一行都套同一条规则：
     * {@code Content-Type: application/json} 里没有空白，按「裸 token」判会变成
     * {@code Bearer application/json} —— 把一条完全正确的头改坏。
     */
    private java.util.function.BiFunction<String, String, String> valueNormalizer;

    /**
     * 设置 VALUE 列的失焦规范化。
     *
     * @param normalizer 形如 {@code (key, value) -> 加工后的值}；
     *                   返回与 value 相同表示不改
     */
    public void setValueNormalizer(java.util.function.BiFunction<String, String, String> normalizer) {
        this.valueNormalizer = normalizer;
    }

    /** 有效行数变化时的回调，用于刷新 Tab 上的数量角标。 */
    public void setOnRowsCountChanged(java.util.function.IntConsumer listener) {
        rowsCount.addListener((obs, old, now) -> listener.accept(now.intValue()));
    }

    /** 空表提示文字。Params 说「暂无参数」，Headers 说「暂无内容」。 */
    public void setPlaceholderText(String text) {
        placeholderLabel.setText(text == null ? "" : text);
    }

    /**
     * @param height 表格高度（可见行数 = {@code (height − 表头 40) ÷ 行高 34}）
     */
    public KeyValueTable(double height) {
        setSpacing(0);
        getStyleClass().add("kv-table-wrap");

        table.getStyleClass().add("kv-table");
        // 不再依赖 TableView 的「编辑」生命周期：每格自带常驻 TextField。
        // 留着 editable 会让点击额外弹出一层编辑器，与常驻输入框抢焦点。
        table.setEditable(false);
        table.setPrefHeight(height);
        table.setMinHeight(120);
        table.setPlaceholder(placeholderLabel);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<Row, Boolean> checkCol = new TableColumn<>("");
        checkCol.setCellValueFactory(c -> c.getValue().enabled);
        checkCol.setCellFactory(col -> new CheckCell());
        checkCol.setPrefWidth(38);
        checkCol.setMinWidth(38);
        checkCol.setMaxWidth(38);
        checkCol.setSortable(false);

        TableColumn<Row, String> keyCol = new TableColumn<>("KEY");
        keyCol.setCellValueFactory(c -> c.getValue().key);
        keyCol.setCellFactory(col -> new BoundTextCell(r -> r.key));
        // 340 不是随手取的：面板内宽 936，末列（VALUE）按 FLEX_LAST_COLUMN
        // 吃掉剩下的 558，于是两列正好落在设计稿的「KEY 340 / VALUE 558」上。
        keyCol.setPrefWidth(340);
        keyCol.setMinWidth(120);
        keyCol.setSortable(false);

        TableColumn<Row, String> valueCol = new TableColumn<>("VALUE");
        valueCol.setCellValueFactory(c -> c.getValue().value);
        valueCol.setCellFactory(col -> new BoundTextCell(r -> r.value));
        valueCol.setPrefWidth(260);
        valueCol.setMinWidth(120);
        valueCol.setSortable(false);

        table.getColumns().add(checkCol);
        table.getColumns().add(keyCol);
        table.getColumns().add(valueCol);

        // 始终保留一行空行供录入
        rows.add(new Row("", ""));
        // 监听器只负责刷新计数。补尾行由「内容变化」与「批量替换收尾」两条
        // 显式路径触发，不在列表变更回调里重入修改列表。
        rows.addListener((javafx.collections.ListChangeListener<Row>) c -> refreshCount());

        VBox.setVgrow(table, Priority.ALWAYS);
        getChildren().add(table);
    }

    // ---------------------------------------------------------------- 单元格

    /** 勾选列。 */
    private final class CheckCell extends TableCell<Row, Boolean> {

        private final CheckBox box = new CheckBox();

        CheckCell() {
            box.setOnAction(e -> {
                Row row = currentRow();
                if (row != null) {
                    row.enabled.set(box.isSelected());
                    refreshCount();
                }
            });
            setAlignment(Pos.CENTER);
        }

        /** 当前格所属的行 —— 布局早期 {@code getTableRow()} 会是 null。 */
        private Row currentRow() {
            return getTableRow() == null ? null : getTableRow().getItem();
        }

        @Override
        protected void updateItem(Boolean item, boolean empty) {
            super.updateItem(item, empty);
            Row row = empty ? null : currentRow();
            if (row == null) {
                setGraphic(null);
                return;
            }
            box.setSelected(row.enabled.get());
            setGraphic(box);
        }
    }

    /**
     * 常驻输入格：文字与行模型<b>双向写通</b>，没有「提交」这一步。
     *
     * <p>{@code updateItem} 会被反复调用（滚动、刷新、行复用），所以每次都按
     * 当前行重新绑定；换行时必须把上一个行的引用摘掉，否则旧行会被幽灵输入改写。
     */
    private final class BoundTextCell extends TableCell<Row, String> {

        private final TextField field = new TextField();
        private final Function<Row, StringProperty> accessor;
        private Row bound;

        BoundTextCell(Function<Row, StringProperty> accessor) {
            this.accessor = accessor;
            // 复用 .table-view .table-cell .text-field 的行内编辑样式：
            // 平时透明无框，聚焦才描主色边 —— 视觉上与旧版编辑器完全一致，
            // 但它常驻、不销毁。
            field.textProperty().addListener((o, old, now) -> {
                if (bound == null) {
                    return;
                }
                String typed = now == null ? "" : now;
                StringProperty target = accessor.apply(bound);
                // 相等时不再回写：updateItem 里的 setText 会触发这里，
                // 不加这道闸就会无限递归。
                if (!typed.equals(target.get())) {
                    target.set(typed);
                    onRowEdited();
                }
            });
            // 失焦 = 用户这一格填完了。规范化只在这时候做，不能在按键时做 ——
            // 边打边改会把用户刚敲进去的字符顶走（「b」变「Bearer b」）。
            field.focusedProperty().addListener((o, wasFocused, isFocused) -> {
                if (Boolean.TRUE.equals(wasFocused) && Boolean.FALSE.equals(isFocused)) {
                    normalizeValueOnBlur();
                }
            });
            setGraphic(field);
        }

        /** 失焦时对该行的值做一次规范化（只对 VALUE 列有意义）。 */
        private void normalizeValueOnBlur() {
            if (bound == null || valueNormalizer == null) {
                return;
            }
            String current = bound.value.get();
            String fixed = valueNormalizer.apply(bound.key.get().trim(), current);
            if (fixed != null && !fixed.equals(current)) {
                bound.value.set(fixed);
                // 焦点已经在这格之外，直接同步显示新值；不 requestFocus，
                // 否则会把用户从下一格里又拽回来。
                field.setText(fixed);
                onRowEdited();
            }
        }

        /** 当前格所属的行 —— 布局早期 {@code getTableRow()} 会是 null。 */
        private Row currentRow() {
            return getTableRow() == null ? null : getTableRow().getItem();
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            Row row = empty ? null : currentRow();
            if (row == null) {
                bound = null;
                field.clear();
                return;
            }
            bound = row;
            String current = accessor.apply(row).get();
            if (!current.equals(field.getText())) {
                field.setText(current);
            }
        }
    }

    // ---------------------------------------------------------------- 内容变化

    /** 某一行被改动：刷计数，并在需要时补一行空白行。 */
    private void onRowEdited() {
        refreshCount();
        scheduleTrailingBlank();
    }

    /**
     * 排队补一行空白行。
     *
     * <p>必须异步：按键事件还没走完时改列表，{@code TableView} 会在同一趟
     * 布局里重建单元格，把正在输入的那一格连同焦点一起换掉 —— 这正是旧版
     * 「打一个字就失焦」的直接成因。
     */
    private void scheduleTrailingBlank() {
        if (bulkEdit || appendScheduled) {
            return;
        }
        appendScheduled = true;
        Platform.runLater(() -> {
            appendScheduled = false;
            appendTrailingBlank();
        });
    }

    /**
     * 保证末尾恰好有一行空白行。
     *
     * <p>只在「末行有内容」时才补；已是空行则什么都不做（因此可以反复调用）。
     */
    private void appendTrailingBlank() {
        if (bulkEdit) {
            return;
        }
        if (rows.isEmpty()) {
            rows.add(new Row("", ""));
            refreshCount();
            return;
        }
        if (!rows.get(rows.size() - 1).isBlank()) {
            rows.add(new Row("", ""));
        }
        refreshCount();
    }

    /** 重新计算有效行数并通知监听者。 */
    private void refreshCount() {
        rowsCount.set(entryCount());
    }

    // ---------------------------------------------------------------- 对外操作

    /**
     * 追加一行并把焦点送进去。
     *
     * <p>「新增参数」不该依赖「在末行打完字后它自己冒出来」这种被动机制 ——
     * 空格键、粘贴、勾选都不触发补行。给一个显式入口更可靠。
     *
     * <p>注意这里<b>不能</b>复用 {@link #appendTrailingBlank()}：它是「保证末行
     * 是空的」，末行本来就空时什么都不做。而「+ 新增参数」要的是
     * <b>「多给我一行」</b> —— 空表上点它必须真的多出一行，否则这个按钮在
     * 最常见的场景（刚打开就点）下毫无反应。
     */
    public void addRow() {
        if (!bulkEdit) {
            rows.add(new Row("", ""));
            refreshCount();
        }
        focusTrailingRow();
    }

    /**
     * 快捷填入一个键值对（{@code + JSON} / {@code + Token} 这类按钮用）。
     *
     * <p>三段式落位，<b>不新增行</b>：
     * <ol>
     *   <li>已经有同名 key（忽略大小写）→ 复用那一行，只覆盖它的值；</li>
     *   <li>没有但末尾正好是空行 → 填进那一行（表里本来就留着这一行给人打字的）；</li>
     *   <li>都没有 → 插在尾部空行<b>之前</b>新增一条。</li>
     * </ol>
     *
     * <p>反复点同一个按钮不会堆出多行 {@code Authorization} —— 那会让服务端
     * 收到两个同名头，行为取决于框架 unpredictably。
     *
     * @param valueTransform 值的前置加工（如补 {@code Bearer } 前缀），
     *                       返回 null 表示这一行不填值、只落 key
     * @return 落位那一行的行号，调用方可用它把焦点送过去
     */
    public int addEntry(String key, String value,
                        java.util.function.UnaryOperator<String> valueTransform) {
        if (bulkEdit) {
            return -1;
        }
        String trimmedKey = key == null ? "" : key.trim();
        Row target = findByKey(trimmedKey);
        if (target == null) {
            target = new Row(trimmedKey, "");
            // 插在尾部空行<b>之前</b>，而不是就地占用它。
            // 就地占用（rows.set(size-1, target)）会把那条空行吃掉，
            // 于是表尾没有可继续录入的行了 —— 下一次想加头就没地方落。
            int insertAt = rows.isEmpty() ? 0 : rows.size();
            if (!rows.isEmpty() && rows.get(insertAt - 1).isBlank()) {
                insertAt--;
            }
            rows.add(insertAt, target);
        }
        if (value != null) {
            target.value.set(valueTransform == null ? value : valueTransform.apply(value));
        }
        target.enabled.set(true);
        table.refresh();
        refreshCount();
        return rows.indexOf(target);
    }

    /** 按 key 找行（忽略大小写与首尾空格）。 */
    private Row findByKey(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        for (Row row : rows) {
            if (key.equalsIgnoreCase(row.key.get().trim())) {
                return row;
            }
        }
        return null;
    }

    private void focusTrailingRow() {
        focusCell(rows.size() - 1, 0);
    }

    /**
     * 把焦点送进某一行的某一格，并把光标放到内容末尾。
     *
     * <p>末尾而不是开头：{@code + Token} 填好 {@code Bearer } 之后，
     * 光标应当在 {@code Bearer } <b>后面</b>，用户直接接着打 token 即可 ——
     * 放到开头会把前缀顶到 token 前面，变成 {@code Bearer xxxBearer}。
     */
    public void focusCell(int rowIndex, int column) {
        Platform.runLater(() -> {
            TextField field = cellField(rowIndex, column);
            if (field == null) {
                return;
            }
            field.requestFocus();
            field.end();
        });
    }

    /**
     * 取某一行某一列的常驻输入框。
     *
     * <p>{@code TableView.getRow(int)} 与 {@code TableRow.getCell(int)} 都不是
     * 公开 API，只能顺着场景图找：{@code TableRow} 里按列序数
     * {@link TableCell}，再取它的 graphic。
     */
    private TextField cellField(int rowIndex, int column) {
        if (rowIndex < 0) {
            return null;
        }
        int seenRows = 0;
        for (Node node : table.lookupAll(".table-row-cell")) {
            if (!(node instanceof TableRow<?> row)) {
                continue;
            }
            if (seenRows++ != rowIndex) {
                continue;
            }
            int seenCells = 0;
            for (Node child : row.getChildrenUnmodifiable()) {
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

    /**
     * 取出所有勾选且 key 非空的行。
     *
     * <p>取值时顺手把每一行的值过一遍 {@link #valueNormalizer}：失焦规范化
     * 只在「焦点真的离开过那一格」时才跑得过，而取值是<b>一定会</b>发生的
     * 那一刻（点 Send、导出 cURL、另存为批处理模板都走这里）。
     * 两处都做，前者给即时反馈，后者兜住「粘完直接点 Send」这条路径 ——
     * 否则用户会发出一个缺前缀的请求，且界面上看不出任何异常。
     *
     * <p>规范化<b>不写回模型</b>：这里只影响发出去的值，表格里显示的仍是
     * 用户敲的原文（写回会在读取时改 UI，用户没准许过）。
     */
    public List<Map.Entry<String, String>> entries() {
        List<Map.Entry<String, String>> list = new ArrayList<>();
        for (Row row : rows) {
            if (!row.enabled.get()) {
                continue;
            }
            String key = row.key.get();
            if (key == null || key.isBlank()) {
                continue;
            }
            String value = row.value.get();
            if (valueNormalizer != null) {
                String fixed = valueNormalizer.apply(key.trim(), value);
                if (fixed != null) {
                    value = fixed;
                }
            }
            list.add(new java.util.AbstractMap.SimpleEntry<>(key.trim(), value));
        }
        return list;
    }

    /** 清空并只留一个空行。 */
    public void clear() {
        replaceRows(List.of());
    }

    /**
     * 批量填充。
     *
     * <p>逐条 {@code add} 会让「补尾行」在每一步都跑一遍 —— 一条
     * {@code Content-Type} 能滚出 4 行空行。改为一次 {@code setAll}，
     * 期间关掉补行，收尾时只补一行。
     */
    public void setEntries(Map<String, String> map) {
        List<Row> next = new ArrayList<>();
        if (map != null) {
            map.forEach((k, v) -> next.add(new Row(k, v)));
        }
        replaceRows(next);
    }

    /** 整表替换：批量期间静音，收尾时统一补一行空白行。 */
    private void replaceRows(List<Row> next) {
        bulkEdit = true;
        try {
            List<Row> target = new ArrayList<>(next);
            target.add(new Row("", ""));
            rows.setAll(target);
        } finally {
            bulkEdit = false;
        }
        refreshCount();
    }

    /** 当前行数（不含尾部空行）。 */
    public int entryCount() {
        return entries().size();
    }

    /** 是否有任何有效内容。 */
    public boolean isEmpty() {
        return entries().isEmpty();
    }

    /** 转成有序 Map（用于保存/展示）。 */
    public Map<String, String> asMap() {
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : entries()) {
            map.put(e.getKey(), e.getValue());
        }
        return map;
    }

    /** 提供一个占位图标的工具栏（粘贴 cURL 等扩展点预留）。 */
    public static HBox toolbar(Node... nodes) {
        HBox box = new HBox(8, nodes);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    // ---------------------------------------------------------------- 测试钩子

    /** 测试用：表格控件。 */
    public TableView<Row> tableForTest() {
        return table;
    }

    /** 测试用：总行数（含尾部空行）。 */
    public int rowCountForTest() {
        return rows.size();
    }

    /**
     * 测试用：某一行的常驻输入框。
     *
     * @param rowIndex  行号（含尾部空行）
     * @param column    0 = KEY，1 = VALUE
     */
    public TextField cellFieldForTest(int rowIndex, int column) {
        return cellField(rowIndex, column);
    }

    /**
     * 测试用：立刻执行待办的「补尾行」。
     *
     * <p>真实路径是 {@code runLater}（必须异步）；探针跑在 FX 线程上，
     * 用 latch 等队列排空会把自己锁死。这里同步执行同一段逻辑 ——
     * 对焦点是<b>更严</b>的考验：同步的列表变更都扛得住，异步的更没问题。
     */
    public void flushPendingAppendForTest() {
        appendScheduled = false;
        appendTrailingBlank();
    }

    /** 测试用：直接写某一行 VALUE 的模型值（模拟用户粘了一串 token）。 */
    public void setValueForTest(int rowIndex, String value) {
        if (rowIndex < 0 || rowIndex >= rows.size()) {
            return;
        }
        rows.get(rowIndex).value.set(value == null ? "" : value);
        table.refresh();
        refreshCount();
    }

    /** 测试用：批量填入（探针跑在无头环境，走不了真实的「+ 新增」按钮流程）。 */
    public void setEntriesForTest(Map<String, String> map) {
        setEntries(map);
    }

    /** 测试用：设置某一行是否勾选（探针跑在无头环境，点不到真实 CheckBox）。 */
    public void setRowEnabledForTest(int rowIndex, boolean enabled) {
        if (rowIndex < 0 || rowIndex >= rows.size()) {
            return;
        }
        rows.get(rowIndex).enabled.set(enabled);
        table.refresh();
        refreshCount();
    }

    /** 测试用：某一行是否勾选。 */
    public boolean rowEnabledForTest(int rowIndex) {
        return rowIndex >= 0 && rowIndex < rows.size() && rows.get(rowIndex).enabled.get();
    }

    /** 测试用：某一行 KEY 的模型值。 */
    public String rowKeyForTest(int rowIndex) {
        return rowIndex >= 0 && rowIndex < rows.size() ? rows.get(rowIndex).key.get() : null;
    }

    /** 测试用：某一行 VALUE 的模型值。 */
    public String rowValueForTest(int rowIndex) {
        return rowIndex >= 0 && rowIndex < rows.size() ? rows.get(rowIndex).value.get() : null;
    }
}
