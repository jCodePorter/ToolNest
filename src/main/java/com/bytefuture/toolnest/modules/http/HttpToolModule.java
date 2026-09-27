package com.bytefuture.toolnest.modules.http;

import com.bytefuture.toolnest.core.ModuleCategory;
import com.bytefuture.toolnest.core.ToolModule;
import com.bytefuture.toolnest.ui.UiKit;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
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
        return "发送 GET / POST / PUT / DELETE 请求，支持单次调试与 CSV 批量执行";
    }

    @Override
    public String description() {
        return "批处理时以 CSV 表头为变量名，用 {列名} 占位符替换到地址、请求体与请求头中，"
                + "可顺序或并发执行并导出结果";
    }

    @Override
    public String icon() {
        return "\u21AF";
    }

    @Override
    public String keywords() {
        return "http https rest api 请求 get post put delete 接口 调试 批处理 批量 csv 占位符";
    }

    @Override
    public Node buildContent() {
        VBox root = UiKit.page("HTTP 请求工具",
                "支持单次调试与 CSV 批量执行，占位符写法 {列名}。");

        singlePanel = new SingleRequestPanel();
        batchPanel = new BatchPanel();

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getStyleClass().add("http-main-tabs");

        Tab singleTab = new Tab("单一请求", singlePanel);
        Tab batchTab = new Tab("批处理", batchPanel);
        tabs.getTabs().addAll(singleTab, batchTab);

        // 把当前请求配置一键带到批处理页，省去重复录入
        Button carryOver = UiKit.secondaryButton("↑ 用当前请求作为批处理模板");
        carryOver.setOnAction(e -> {
            batchPanel.applyTemplate(singlePanel.snapshotSpec(),
                    singlePanel.snapshotHeaders());
            tabs.getSelectionModel().select(batchTab);
        });

        HBox switcher = new HBox(10, carryOver, UiKit.spacer(),
                UiKit.caption("界面里可直接用 {列名} 占位符，批处理时自动替换。"));
        switcher.setAlignment(Pos.CENTER_LEFT);

        VBox.setVgrow(tabs, Priority.ALWAYS);
        root.getChildren().addAll(switcher, tabs);

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
