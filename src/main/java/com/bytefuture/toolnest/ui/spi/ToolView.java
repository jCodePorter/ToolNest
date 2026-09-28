package com.bytefuture.toolnest.ui.spi;

import javafx.scene.Node;

/**
 * 功能视图契约 —— 「这个工具长什么样」。
 *
 * <p>这是整个项目中<b>唯一</b>必须由功能模块实现的界面契约。它之所以住在
 * ui 层而不是 core 层，是因为它的返回值 {@link Node} 本身就是 JavaFX 类型：
 * 把界面工厂放进领域契约，等于让领域层反向依赖 GUI 技术。
 *
 * <p>实现约定：
 * <ul>
 *   <li>构建方法必须是<b>惰性</b>的 —— 只有用户首次打开该功能时才被调用，
 *       返回值由 {@code ui.shell.ContentPane} 缓存，再次打开直接复用
 *       （保留用户输入状态）。因此不要在构造期做耗时工作</li>
 *   <li>界面组装应委托给 {@code ui.components} 下的公共组件，不要自行拼样式常量</li>
 * </ul>
 */
public interface ToolView {

    /**
     * 构建功能详情界面。
     *
     * <p>本方法为惰性调用 —— 只有用户首次打开该功能时才会执行，
     * 返回值会被内容区缓存，再次打开直接复用（保留用户输入状态）。
     */
    Node buildContent();
}
