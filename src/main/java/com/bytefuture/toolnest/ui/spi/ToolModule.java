package com.bytefuture.toolnest.ui.spi;

import com.bytefuture.toolnest.core.ToolDescriptor;

/**
 * 功能模块 —— 元数据（{@link ToolDescriptor}）与视图（{@link ToolView}）的汇合点。
 *
 * <p>这是「新增功能只需改一个地方」的那个契约。完整流程：
 * <ol>
 *   <li>在 {@code features/<业务>/} 下新建 {@code XxxModule} 实现本接口</li>
 *   <li>在 {@code app.ToolRegistry.bootstrap()} 里加一行
 *       {@code register(new XxxModule());}</li>
 * </ol>
 * 无需改动 Sidebar / ContentPane / ToolCard 等任何界面代码。
 *
 * <p><b>本接口住在 ui 层而不是 core 层</b>，因为它继承了含 JavaFX {@code Node}
 * 的 {@link ToolView}。领域层只认 {@link ToolDescriptor}，不该知道界面的存在——
 * 这正是本次分层改造要守住的边界。
 *
 * <p>功能模块的内部结构应当是三层（详见 {@code ARCHITECTURE.md} 第三节）：
 * <pre>
 *   XxxModule   元数据 + 视图入口（本接口的实现）  ← 很薄，只做接线
 *   XxxOps      业务逻辑                          ← 零 JavaFX 依赖，可单独测试
 *   XxxIo       文件 / 网络 / 剪贴板访问           ← 薄到不值得单列时并入 XxxOps
 * </pre>
 */
public interface ToolModule extends ToolDescriptor, ToolView {
}
