/**
 * 界面层 —— 三个子包，边界严格区分，不要混用：
 * <ul>
 *   <li>{@code ui.shell} —— 本应用专属的框架界面（窗口骨架、侧边栏、内容区导航、宫格页）。
 *       <b>不可复用</b>：它绑定了 ToolNest 自己的三层信息架构</li>
 *   <li>{@code ui.components} —— <b>公共 UI 组件层</b>。与业务无关，任何 feature 都能直接用。
 *       需要新的界面元素时优先在这里补，而不是在 feature 里自己拼样式类</li>
 *   <li>{@code ui.spi} —— 功能模块的扩展点契约（{@code ToolModule} / {@code ToolView}）</li>
 * </ul>
 *
 * <p>依赖方向是单向的：{@code features} → {@code ui.components} / {@code ui.spi}。
 * <b>features 不得 import ui.shell</b> —— 那是框架，不是组件；反向引用意味着
 * 一个业务功能开始依赖整个应用外壳，它就再也无法独立测试或复用了。
 */
package com.bytefuture.toolnest.ui;
