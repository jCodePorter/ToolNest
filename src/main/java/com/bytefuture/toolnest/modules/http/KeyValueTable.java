package com.bytefuture.toolnest.modules.http;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 键值对编辑表格（仿 Postman 的 Params / Headers 面板）。
 *
 * <p>带勾选框控制该行是否参与请求，空行自动跳过。
 * 表格永远保留一行空白，方便连续录入。
 */
public final class KeyValueTable extends VBox {

    /** 一行数据。 */
    public static final class Row {
        final SimpleStringProperty enabled = new SimpleStringProperty("true");
        final SimpleStringProperty key = new SimpleStringProperty("");
        final SimpleStringProperty value = new SimpleStringProperty("");

        public Row(String key, String value) {
            this.key.set(key == null ? "" : key);
            this.value.set(value == null ? "" : value);
        }
    }

    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final TableView<Row> table = new TableView<>(rows);
    private final boolean withDescription;
    private final javafx.beans.property.IntegerProperty rowsCount =
            new javafx.beans.property.SimpleIntegerProperty(0);

    /** 有效行数变化时的回调，用于刷新 Tab 上的数量角标。 */
    public void setOnRowsCountChanged(java.util.function.IntConsumer listener) {
        rowsCount.addListener((obs, old, now) -> listener.accept(now.intValue()));
    }

    /**
     * @param withDescription 是否显示 DESCRIPTION 列（Params 面板用，Headers 不用）
     * @param height          表格高度
     */
    public KeyValueTable(boolean withDescription, double height) {
        this.withDescription = withDescription;
        setSpacing(0);
        getStyleClass().add("kv-table-wrap");

        table.getStyleClass().add("kv-table");
        table.setEditable(true);
        table.setPrefHeight(height);
        table.setMinHeight(120);
        table.setPlaceholder(new Label(withDescription ? "暂无参数" : "暂无内容"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<Row, String> checkCol = new TableColumn<>("");
        checkCol.setCellValueFactory(c -> c.getValue().enabled);
        checkCol.setCellFactory(col -> new TableCell<>() {
            private final CheckBox box = new CheckBox();

            {
                box.setOnAction(e -> {
                    Row row = getTableRow() == null ? null : getTableRow().getItem();
                    if (row != null) {
                        row.enabled.set(box.isSelected() ? "true" : "false");
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
                box.setSelected(!"false".equals(item));
                setGraphic(box);
                setAlignment(Pos.CENTER);
            }
        });
        checkCol.setPrefWidth(38);
        checkCol.setMinWidth(38);
        checkCol.setMaxWidth(38);
        checkCol.setSortable(false);

        TableColumn<Row, String> keyCol = new TableColumn<>("KEY");
        keyCol.setCellValueFactory(c -> c.getValue().key);
        keyCol.setCellFactory(TextFieldTableCell.forTableColumn());
        keyCol.setOnEditCommit(e -> e.getRowValue().key.set(e.getNewValue()));
        keyCol.setPrefWidth(220);
        keyCol.setSortable(false);

        TableColumn<Row, String> valueCol = new TableColumn<>("VALUE");
        valueCol.setCellValueFactory(c -> c.getValue().value);
        valueCol.setCellFactory(TextFieldTableCell.forTableColumn());
        valueCol.setOnEditCommit(e -> e.getRowValue().value.set(e.getNewValue()));
        valueCol.setPrefWidth(260);
        valueCol.setSortable(false);

        table.getColumns().add(checkCol);
        table.getColumns().add(keyCol);
        table.getColumns().add(valueCol);

        if (withDescription) {
            TableColumn<Row, String> descCol = new TableColumn<>("DESCRIPTION");
            descCol.setCellValueFactory(c -> new SimpleStringProperty(""));
            descCol.setPrefWidth(180);
            descCol.setSortable(false);
            table.getColumns().add(descCol);
        }

        // 始终保留一行空行供录入
        rows.add(new Row("", ""));
        rows.addListener((javafx.collections.ListChangeListener<Row>) c -> {
            ensureTrailingBlank();
            refreshCount();
        });

        // 在空白行输入内容后自动补新行
        table.setOnKeyTyped(e -> ensureTrailingBlank());

        // 单元格编辑提交后也要更新计数
        table.addEventFilter(javafx.scene.input.KeyEvent.KEY_RELEASED, e -> refreshCount());
        table.setOnMouseClicked(e -> refreshCount());
        refreshCount();

        VBox.setVgrow(table, Priority.ALWAYS);
        getChildren().add(table);
    }

    /** 重新计算有效行数并通知监听者。 */
    private void refreshCount() {
        rowsCount.set(entryCount());
    }

    /** 保证末尾始终有一行完全空白的行。 */
    private void ensureTrailingBlank() {
        if (rows.isEmpty()) {
            rows.add(new Row("", ""));
            return;
        }
        Row last = rows.get(rows.size() - 1);
        boolean lastBlank = last.key.get().isBlank() && last.value.get().isBlank();
        if (!lastBlank) {
            rows.add(new Row("", ""));
        }
    }

    /** 取出所有勾选且 key 非空的行。 */
    public List<Map.Entry<String, String>> entries() {
        List<Map.Entry<String, String>> list = new ArrayList<>();
        for (Row row : rows) {
            if ("false".equals(row.enabled.get())) {
                continue;
            }
            String key = row.key.get();
            if (key == null || key.isBlank()) {
                continue;
            }
            list.add(new java.util.AbstractMap.SimpleEntry<>(key.trim(), row.value.get()));
        }
        return list;
    }

    /** 清空并只留一个空行。 */
    public void clear() {
        rows.setAll(new Row("", ""), new Row("", ""));
    }

    /** 批量填充。 */
    public void setEntries(Map<String, String> map) {
        rows.clear();
        if (map != null) {
            map.forEach((k, v) -> rows.add(new Row(k, v)));
        }
        rows.add(new Row("", ""));
        rows.add(new Row("", ""));
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
}
