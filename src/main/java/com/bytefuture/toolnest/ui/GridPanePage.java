package com.bytefuture.toolnest.ui;

import com.bytefuture.toolnest.core.ModuleCategory;
import com.bytefuture.toolnest.core.ToolModule;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/**
 * 宫格页面：以卡片宫格形式展示一组功能模块。
 *
 * <p>两种用途：
 * <ul>
 *   <li>点击侧边栏的<b>分类文件夹</b> → 展示该分类下的所有功能</li>
 *   <li>展示<b>全部工具</b> → 按分类分段展示所有功能</li>
 * </ul>
 *
 * <p>页面分三层：固定页头（位置与上下文） → 可滚动正文（宫格） →
 * 随内容增长的底部留白。页头不随滚动消失，用户随时知道自己在看哪一类。
 *
 * <p><b>返回按钮</b>：宫格页同样可能有来路（如 宫格 → 工具 → 宫格），
 * 因此当浏览历史非空时页头左侧会出现返回按钮。它由 {@code ContentPane}
 * 在切换页面时通过 {@link #setOnBack(Runnable, boolean)} 注入，
 * 宫格页自身不持有导航状态。
 */
public class GridPanePage extends ScrollPane {

    private final Consumer<ToolModule> onOpen;

    /**
     * 页头左侧的返回按钮。
     *
     * <p>必须<b>在构造时</b>就创建好，不能等到 {@code setOnBack} 再惰性创建 ——
     * 宫格内容只在首次构建时生成一次，{@code pageHeader} 在那时就引用了这个
     * 节点；若此刻为 null，之后创建的按钮永远不会出现在已构建的页头里。
     * 可见性由 {@link #setOnBack} 控制（无历史时隐藏）。
     */
    private final Button backButton = new Button("\u2190  返回");

    public GridPanePage(Consumer<ToolModule> onOpen) {
        this.onOpen = onOpen;
        getStyleClass().add("grid-scroll");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);

        backButton.getStyleClass().add("back-button");
        backButton.setAccessibleText("返回上一页");
        // 默认不可见：首次渲染时历史通常为空
        backButton.setVisible(false);
        backButton.setManaged(false);
    }

    /**
     * 注入返回行为并同步可见性。
     *
     * @param onBack        点击返回时的动作，可为 null（仅更新可见性）
     * @param historyExists 当前是否存在可回退的历史；false 时隐藏按钮
     */
    public void setOnBack(Runnable onBack, boolean historyExists) {
        if (onBack != null) {
            backButton.setOnAction(e -> onBack.run());
        }
        backButton.setVisible(historyExists);
        backButton.setManaged(historyExists);
    }

    /** 展示单个分类的宫格页。 */
    public void showCategory(ModuleCategory category, List<ToolModule> modules) {
        VBox shell = new VBox();
        shell.getStyleClass().add("grid-body");

        String count = modules.isEmpty() ? null : modules.size() + " 个功能";
        shell.getChildren().add(UiKit.pageHeader(backButton, category.label(), count, null));

        VBox body = UiKit.pageBody(22);
        if (modules.isEmpty()) {
            body.getChildren().add(UiKit.caption("该分类下暂无工具。"));
        } else {
            body.getChildren().add(grid(modules));
        }
        shell.getChildren().add(body);

        setContent(shell);
        setVvalue(0);
    }

    /** 展示全部工具，按分类分段。 */
    public void showAll(java.util.Map<ModuleCategory, List<ToolModule>> grouped) {
        VBox shell = new VBox();
        shell.getStyleClass().add("grid-body");

        int total = grouped.values().stream().mapToInt(List::size).sum();
        shell.getChildren().add(UiKit.pageHeader(backButton, "所有工具",
                grouped.isEmpty() ? null : total + " 个功能", null));

        VBox body = UiKit.pageBody(30);
        if (grouped.isEmpty()) {
            body.getChildren().add(UiKit.caption("还没有注册任何工具。"));
        } else {
            grouped.forEach((category, modules) -> {
                // 分段标题带一条贯穿的分隔线，比单纯的加粗文字更容易定位分段边界
                Label sectionTitle = new Label(category.icon() + "   " + category.label());
                sectionTitle.getStyleClass().add("section-title");

                VBox section = new VBox(14, sectionTitle, grid(modules));
                body.getChildren().add(section);
            });
        }
        shell.getChildren().add(body);

        setContent(shell);
        setVvalue(0);
    }

    /** 构建自适应换行的卡片宫格。 */
    private FlowPane grid(List<ToolModule> modules) {
        FlowPane pane = new FlowPane();
        pane.getStyleClass().add("tool-grid");
        pane.setHgap(20);
        pane.setVgap(20);
        pane.setPadding(new Insets(0));
        for (ToolModule module : modules) {
            pane.getChildren().add(new ToolCard(module, onOpen));
        }
        return pane;
    }
}
