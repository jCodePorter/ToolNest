package com.bytefuture.toolnest.ui.shell;

import com.bytefuture.toolnest.core.ModuleCategory;
import com.bytefuture.toolnest.ui.spi.ToolModule;
import com.bytefuture.toolnest.ui.components.Icons;
import com.bytefuture.toolnest.ui.components.UiKit;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
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
    private final Button backButton = UiKit.backButton("返回");

    public GridPanePage(Consumer<ToolModule> onOpen) {
        this.onOpen = onOpen;
        getStyleClass().add("grid-scroll");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);

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

        VBox body = UiKit.pageBody(28);
        if (grouped.isEmpty()) {
            body.getChildren().add(UiKit.caption("还没有注册任何工具。"));
        } else {
            grouped.forEach((category, modules) -> {
                VBox section = new VBox(14, sectionHead(category, modules.size()), grid(modules));
                body.getChildren().add(section);
            });
        }
        shell.getChildren().add(body);

        setContent(shell);
        setVvalue(0);
    }

    /**
     * 分组分隔带：分类图标 + 分类名 + 一条贯穿的细线 + 数量。
     *
     * <p>旧版这一行只是 12px 的极淡灰文字 —— 它是整页视觉重量最弱的元素，
     * 却干着「分段边界在哪里」这个最该被看见的活，扫视时几乎注意不到。
     *
     * <p>现在把它升格为一条分隔带：左边图标给身份、名称给分区名，
     * 中间那条线把边界一直画到右缘，右边的数量先给一个规模预期
     * （「这一类有 4 项」）。三者各司其职，不靠加粗字号硬撑。
     */
    private HBox sectionHead(ModuleCategory category, int count) {
        Label title = new Label(category.label());
        title.getStyleClass().add("section-title");

        Region rule = new Region();
        rule.getStyleClass().add("section-rule");
        HBox.setHgrow(rule, Priority.ALWAYS);

        Label countLabel = new Label(count + " 项");
        countLabel.getStyleClass().add("section-count");

        HBox head = new HBox(
                Icons.forCategory(category, 15, "icon-subtle"),
                title, rule, countLabel);
        head.getStyleClass().add("section-head");
        head.setAlignment(Pos.CENTER_LEFT);
        return head;
    }

    /** 构建自适应换行的卡片宫格。 */
    private FlowPane grid(List<ToolModule> modules) {
        FlowPane pane = new FlowPane();
        pane.getStyleClass().add("tool-grid");
        pane.setHgap(16);
        pane.setVgap(16);
        pane.setPadding(new Insets(0));
        for (ToolModule module : modules) {
            pane.getChildren().add(new ToolCard(module, onOpen));
        }
        return pane;
    }
}
