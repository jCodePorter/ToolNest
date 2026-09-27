package com.bytefuture.toolnest.ui;

import com.bytefuture.toolnest.core.ModuleCategory;
import com.bytefuture.toolnest.core.ToolModule;
import com.bytefuture.toolnest.core.ToolRegistry;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;

/**
 * 主窗口骨架：左侧分类菜单（文件夹模式） + 右侧内容区。
 */
public class MainWindow extends BorderPane {

    private final ContentPane content = new ContentPane();
    private final Sidebar sidebar = new Sidebar(
            this::onCategorySelected,
            this::onModuleSelected,
            this::onAllToolsSelected);

    public MainWindow() {
        getStyleClass().add("main-window");

        // 用 SplitPane 让用户能拖动调整左右宽度
        SplitPane split = new SplitPane(sidebar, content);
        split.getStyleClass().add("main-split");
        split.setDividerPositions(0.21);
        SplitPane.setResizableWithParent(sidebar, Boolean.FALSE);

        setCenter(split);

        // 默认展开第一个分类，右侧显示该分类的宫格
        sidebar.initFirstCategory();
    }

    /** 点击分类文件夹 → 右侧显示该分类宫格。 */
    private void onCategorySelected(ModuleCategory category) {
        content.showCategory(category);
    }

    /** 点击文件夹下的子项 → 直接进入详情页。 */
    private void onModuleSelected(ToolModule module) {
        content.openDetail(module);
    }

    /** 点击「所有工具」 → 右侧显示全部工具宫格。 */
    private void onAllToolsSelected() {
        content.showAllTools();
    }

    /** 供外部主动跳转到某个功能详情。 */
    public void openModule(String moduleId) {
        ToolRegistry.byId(moduleId).ifPresent(content::openDetail);
    }

    /** 供外部主动跳转到某个分类宫格（并同步左侧选中态）。 */
    public void openCategory(ModuleCategory category) {
        sidebar.selectCategory(category);
        content.showCategory(category);
    }

    /** 供外部主动跳转到「所有工具」宫格（并同步左侧选中态）。 */
    public void openAllTools() {
        sidebar.selectAllTools();
        content.showAllTools();
    }
}
