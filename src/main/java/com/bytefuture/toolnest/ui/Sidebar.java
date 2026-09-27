package com.bytefuture.toolnest.ui;

import com.bytefuture.toolnest.core.ModuleCategory;
import com.bytefuture.toolnest.core.ToolModule;
import com.bytefuture.toolnest.core.ToolRegistry;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/**
 * 左侧功能菜单（文件夹模式）。
 *
 * <p>结构自上而下：
 * <ol>
 *   <li><b>品牌区</b> —— 标记 + 产品名，固定在顶部不随列表滚动</li>
 *   <li><b>「所有工具」入口</b> —— 全量视图，与分类文件夹区分开</li>
 *   <li><b>搜索框</b> —— 按名称 / 说明 / 关键词过滤</li>
 *   <li><b>分类文件夹</b> —— 可滚动列表；点击文件夹标题打开分类宫格页，
 *       展开后点击子项直接进入功能详情页</li>
 * </ol>
 *
 * <p>内容完全由 {@link ToolRegistry} 驱动自动生成 —— 新增模块无需改动本类。
 *
 * <p>可滚动区域独立于固定头部，是为了分类变多时品牌区与搜索框仍然常驻可见。
 */
public class Sidebar extends VBox {

    private static final double PREF_WIDTH = 252;

    private final Consumer<ModuleCategory> onCategorySelected;
    private final Consumer<ToolModule> onModuleSelected;
    private final Runnable onAllToolsSelected;

    private final TextField searchField = new TextField();
    private final VBox foldersBox = new VBox(1);
    private final Button allToolsButton = new Button("\u2630   所有工具");

    /** 当前展开的分类，用于搜索后保持展开状态 */
    private ModuleCategory expandedCategory;

    public Sidebar(Consumer<ModuleCategory> onCategorySelected,
                   Consumer<ToolModule> onModuleSelected,
                   Runnable onAllToolsSelected) {
        this.onCategorySelected = onCategorySelected;
        this.onModuleSelected = onModuleSelected;
        this.onAllToolsSelected = onAllToolsSelected;

        getStyleClass().add("sidebar");
        setPrefWidth(PREF_WIDTH);
        setMinWidth(200);

        // ---------- 品牌区（固定） ----------
        Label logoText = new Label("T");
        logoText.getStyleClass().add("sidebar-logo-text");

        StackPane logo = new StackPane(logoText);
        logo.getStyleClass().add("sidebar-logo");

        Label brandName = new Label("ToolNest");
        brandName.getStyleClass().add("sidebar-brand-name");

        Label brandSub = new Label("工具巢");
        brandSub.getStyleClass().add("sidebar-brand-sub");

        VBox brandTextBox = new VBox(1, brandName, brandSub);
        brandTextBox.setAlignment(Pos.CENTER_LEFT);

        HBox brandBox = new HBox(10, logo, brandTextBox);
        brandBox.getStyleClass().add("sidebar-brand-box");
        brandBox.setAlignment(Pos.CENTER_LEFT);

        // ---------- 「所有工具」入口 ----------
        allToolsButton.getStyleClass().add("all-tools-button");
        allToolsButton.setMaxWidth(Double.MAX_VALUE);
        allToolsButton.setAlignment(Pos.CENTER_LEFT);
        allToolsButton.setOnAction(e -> {
            setAllToolsActive(true);
            onAllToolsSelected.run();
        });

        // ---------- 搜索框 ----------
        searchField.setPromptText("搜索功能…");
        searchField.getStyleClass().add("sidebar-search");
        searchField.textProperty().addListener((obs, old, now) -> rebuildFolders());

        Label groupsLabel = new Label("功能分类");
        groupsLabel.getStyleClass().add("sidebar-section-label");

        VBox head = new VBox(10, allToolsButton, searchField, groupsLabel);
        head.getStyleClass().add("sidebar-head");

        // ---------- 分类列表（可滚动） ----------
        foldersBox.getStyleClass().add("folders-box");

        VBox foldersInner = new VBox(foldersBox);
        foldersInner.getStyleClass().add("folders-scroll-inner");

        ScrollPane foldersScroll = new ScrollPane(foldersInner);
        foldersScroll.getStyleClass().add("folders-scroll");
        foldersScroll.setFitToWidth(true);
        foldersScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(foldersScroll, Priority.ALWAYS);

        getChildren().addAll(brandBox, head, foldersScroll);
        rebuildFolders();
    }

    /** 依据当前搜索词重建分类文件夹。 */
    private void rebuildFolders() {
        foldersBox.getChildren().clear();
        String keyword = searchField.getText() == null
                ? "" : searchField.getText().trim().toLowerCase();
        boolean searching = !keyword.isEmpty();

        List<ModuleCategory> categories = ToolRegistry.usedCategories();

        // 决定本次要展开哪个分类：
        // 搜索时全部展开；否则优先保持用户/初始化时选中的那个；都没有则取第一个。
        ModuleCategory toExpand = searching ? null
                : (expandedCategory != null ? expandedCategory
                    : (categories.isEmpty() ? null : categories.get(0)));

        ModuleCategory firstVisible = null;

        for (ModuleCategory category : categories) {
            List<ToolModule> matched = ToolRegistry.byCategory(category).stream()
                    .filter(m -> matches(m, keyword))
                    .toList();
            if (matched.isEmpty()) {
                continue;
            }
            if (firstVisible == null) {
                firstVisible = category;
            }

            VBox items = new VBox(1);
            items.getStyleClass().add("folder-items");
            for (ToolModule module : matched) {
                items.getChildren().add(buildModuleItem(module));
            }

            // 子项缩进 + 一条竖线，把「属于上面这个文件夹」表达清楚
            VBox itemsIndented = new VBox(items);
            itemsIndented.getStyleClass().add("folder-items-line");

            TitledPane folder = new TitledPane();
            folder.getStyleClass().add("folder-pane");
            HBox header = buildFolderHeader(category, matched.size());
            folder.setGraphic(header);
            folder.setContent(itemsIndented);
            folder.setText("");

            // 点击文件夹标题 → 右侧显示该分类宫格（展开/折叠由 TitledPane 自己处理）。
            // 用事件过滤器而非 setOnMouseClicked：TitledPane 的 skin 会消费标题区
            // 的鼠标事件用于展开/折叠，普通监听器在部分情况下收不到。
            header.setCursor(javafx.scene.Cursor.HAND);
            header.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, e -> {
                expandedCategory = category;
                setAllToolsActive(false);
                onCategorySelected.accept(category);
            });

            // 展开状态：搜索时全展开，否则只展开 toExpand（若它不可见则展开第一个可见的）
            boolean isTarget = searching
                    || category == toExpand
                    || (toExpand == null && category == firstVisible);
            folder.setExpanded(isTarget);

            foldersBox.getChildren().add(folder);
        }

        if (foldersBox.getChildren().isEmpty()) {
            foldersBox.getChildren().add(UiKit.caption("没有匹配的功能"));
        }
    }

    /** 文件夹标题：图标 + 分类名 + 数量。 */
    private HBox buildFolderHeader(ModuleCategory category, int count) {
        Label iconLabel = new Label(category.icon());
        iconLabel.getStyleClass().add("folder-icon");

        Label nameLabel = new Label(category.label());
        nameLabel.getStyleClass().add("folder-name");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label countLabel = new Label(String.valueOf(count));
        countLabel.getStyleClass().add("folder-count");

        HBox header = new HBox(8, iconLabel, nameLabel, spacer, countLabel);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setMaxWidth(Double.MAX_VALUE);
        return header;
    }

    /** 切换「所有工具」按钮的高亮状态。 */
    private void setAllToolsActive(boolean active) {
        if (active) {
            if (!allToolsButton.getStyleClass().contains("active")) {
                allToolsButton.getStyleClass().add("active");
            }
        } else {
            allToolsButton.getStyleClass().remove("active");
        }
    }

    /** 文件夹里的功能子项：点击直接进入详情页。 */
    private HBox buildModuleItem(ToolModule module) {
        Label iconLabel = new Label(module.icon());
        iconLabel.getStyleClass().add("module-item-icon");

        Label nameLabel = new Label(module.displayName());
        nameLabel.getStyleClass().add("module-item-name");

        HBox item = new HBox(8, iconLabel, nameLabel);
        item.getStyleClass().add("module-item");
        item.setAlignment(Pos.CENTER_LEFT);
        item.setCursor(javafx.scene.Cursor.HAND);
        item.setOnMouseClicked(e -> {
            setAllToolsActive(false);
            onModuleSelected.accept(module);
            e.consume();
        });
        return item;
    }

    private boolean matches(ToolModule module, String keyword) {
        if (keyword.isEmpty()) {
            return true;
        }
        return module.displayName().toLowerCase().contains(keyword)
                || module.summary().toLowerCase().contains(keyword)
                || module.keywords().toLowerCase().contains(keyword)
                || module.id().toLowerCase().contains(keyword);
    }

    /** 初始化：展开第一个分类并让右侧显示它。 */
    public void initFirstCategory() {
        List<ModuleCategory> categories = ToolRegistry.usedCategories();
        if (!categories.isEmpty()) {
            expandedCategory = categories.get(0);
            rebuildFolders();
            onCategorySelected.accept(categories.get(0));
        } else {
            onAllToolsSelected.run();
        }
    }

    /** 外部驱动：选中某个分类（展开它 + 高亮同步 + 不触发右侧回调）。 */
    public void selectCategory(ModuleCategory category) {
        expandedCategory = category;
        setAllToolsActive(false);
        rebuildFolders();
    }

    /** 外部驱动：选中「所有工具」。 */
    public void selectAllTools() {
        setAllToolsActive(true);
    }

    /** 外部驱动：切换搜索词。 */
    public void setSearchText(String text) {
        searchField.setText(text == null ? "" : text);
    }
}
