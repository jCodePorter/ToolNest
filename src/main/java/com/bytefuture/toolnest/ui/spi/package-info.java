/**
 * 功能模块扩展点。
 *
 * <p>三个契约的关系：{@code ToolView} 是界面工厂（含 JavaFX 的 Node，
 * 因此必须住在 ui 层）；{@code core.ToolDescriptor} 是纯元数据；
 * {@code ToolModule} 继承两者，是功能模块需要实现的唯一接口。
 *
 * <p>新增一个功能时只需要实现 {@code ToolModule} 并在
 * {@code app.ToolRegistry#bootstrap()} 加一行注册。
 */
package com.bytefuture.toolnest.ui.spi;
