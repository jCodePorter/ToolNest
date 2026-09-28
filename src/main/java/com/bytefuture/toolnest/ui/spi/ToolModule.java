package com.bytefuture.toolnest.ui.spi;

import com.bytefuture.toolnest.core.ToolDescriptor;

/**
 * 功能模块 —— 元数据（{@link ToolDescriptor}）与视图（{@link ToolView}）的汇合点。
 *
 * <p>这是「新增功能只需改一个地方」的那个契约。完整流程：
 * <ol>
 *   <li>在 {@code features/<业务>/} 下新建 {@code XxxFeature} 实现本接口</li>
 *   <li>在 {@code app.ToolRegistry.bootstrap()} 里加一行
 *       {@code register(new XxxFeature());}</li>
 * </ol>
 * 无需改动 Sidebar / ContentPane / ToolCard 等任何界面代码。
 *
 * <p><b>本接口住在 ui 层而不是 core 层</b>，因为它继承了含 JavaFX {@code Node}
 * 的 {@link ToolView}。领域层只认 {@link ToolDescriptor}，不该知道界面的存在——
 * 这正是本次分层改造要守住的边界。
 *
 * <p>功能模块的内部结构应当是三层：
 * <pre>
 *   XxxFeature   元数据 + 视图入口（本接口的实现）  ← 很薄，只做接线
 *   XxxView      界面组装与事件接线                ← 只依赖 ui.components
 *   XxxService   业务编排（内含薄数据访问）         ← 零 JavaFX 依赖
 * </pre>
 */
public interface ToolModule extends ToolDescriptor, ToolView {
}
