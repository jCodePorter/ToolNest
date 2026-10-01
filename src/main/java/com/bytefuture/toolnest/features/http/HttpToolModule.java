package com.bytefuture.toolnest.features.http;

import com.bytefuture.toolnest.core.ModuleCategory;
import com.bytefuture.toolnest.ui.spi.ToolModule;
import com.bytefuture.toolnest.ui.components.Icons;
import com.bytefuture.toolnest.ui.components.UiKit;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * HTTP 工具：单一请求 + 批量请求。
 *
 * <p>两个页签共用一个请求模板概念：
 * <ul>
 *   <li><b>单一请求</b>：仿 Postman，手动填 URL / Params / Headers / Body，点发送看响应</li>
 *   <li><b>批处理</b>：导入 CSV，用 {@code {列名}} 占位符渲染请求模板，
 *       顺序或并发执行，结果表格汇总并可导出</li>
 * </ul>
 *
 * <p>用 {@link java.net.http.HttpClient} 实现，零第三方依赖。
 */
public class HttpToolModule implements ToolModule {

    private SingleRequestPanel singlePanel;
    private BatchPanel batchPanel;

    @Override
    public String id() {
        return "http";
    }

    @Override
    public String displayName() {
        return "HTTP 请求工具";
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.NETWORK;
    }

    @Override
    public String summary() {
        // 卡片说明只留两行（252px 宽的横向卡片），批处理规则放在 description()
        return "GET / POST 等请求调试，支持 CSV 批量执行";
    }

    @Override
    public String description() {
        return "批处理时以 CSV 表头为变量名，用 {列名} 占位符替换到地址、请求体与请求头中，"
                + "可顺序或并发执行并导出结果";
    }


    @Override
    public String keywords() {
        return "http https rest api 请求 get post put delete 接口 调试 批处理 批量 csv 占位符";
    }

    @Override
    public Node buildContent() {
        VBox root = UiKit.page("HTTP 请求工具",
                "支持单次调试与 CSV 批量执行，占位符写法 {列名}。");
        // 页高由各段自己算：外层不能再叠加 VBox 间距，否则业务页签行
        // 与下面的请求行之间会多出 16px，整页立刻溢出（见设计稿 §2 的面积预算）
        root.setSpacing(0);

        singlePanel = new SingleRequestPanel();
        batchPanel = new BatchPanel();

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getStyleClass().add("http-main-tabs");

        Tab singleTab = new Tab("单一请求", singlePanel);
        Tab batchTab = new Tab("批处理", batchPanel);
        tabs.getTabs().addAll(singleTab, batchTab);

        // 把当前请求配置一键带到批处理页，省去重复录入。
        // 图标走自绘图标族而不是 Unicode「↑」：后者的字重来自字体本身，
        // 和相邻控件的线性图标摆在一起不是一套东西。
        Button carryOver = UiKit.secondaryButton("用当前请求作为批处理模板");
        carryOver.getStyleClass().add("button-compact");
        carryOver.setGraphic(Icons.of("arrow-right", 14, "icon-muted"));
        carryOver.setOnAction(e -> {
            batchPanel.applyTemplate(singlePanel.snapshotSpec(),
                    singlePanel.snapshotHeaders());
            tabs.getSelectionModel().select(batchTab);
        });

        // 并进业务页签行右侧。
        // 它原来独占一行（按钮 32 + 间距 16 = 48），是 chrome 里最肥的一块；
        // 而 TabPane 的页签区在皮肤内部，没有公开 API 能往这一行追加节点 ——
        // 所以用 StackPane 叠一层，把按钮对齐到「顶部右侧」，
        // 也就是页签轨道的右端（轨道高 38，按钮 32，上留 3 使两者中线重合）。
        HBox tail = new HBox(carryOver);
        tail.setAlignment(Pos.TOP_RIGHT);
        tail.setPadding(new Insets(3, 0, 0, 0));
        tail.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        StackPane stack = new StackPane(tabs, tail);
        StackPane.setAlignment(tail, Pos.TOP_RIGHT);
        VBox.setVgrow(stack, Priority.ALWAYS);
        root.getChildren().add(stack);

        // 面板销毁时释放线程池
        root.sceneProperty().addListener((o, oldScene, newScene) -> {
            if (newScene == null) {
                shutdown();
            }
        });
        return root;
    }

    /** 释放两个面板占用的线程池。 */
    public void shutdown() {
        if (singlePanel != null) {
            singlePanel.shutdown();
        }
        if (batchPanel != null) {
            batchPanel.shutdown();
        }
    }
}
