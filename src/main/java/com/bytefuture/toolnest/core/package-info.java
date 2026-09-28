/**
 * 领域契约层 —— 全应用最内层，<b>零 JavaFX 依赖</b>。
 *
 * <p>本包只描述「一个工具是什么」以及工具如何分类，不涉及任何界面技术。
 * 它被 {@code ui} 与 {@code features} 共同依赖，自己<b>不依赖</b>任何其他包。
 *
 * <p>判断一个类是否该放这里，只有一个标准：把它单独拿出来，
 * 能否在没有 JavaFX 的环境里编译与测试。能，才放这里。
 *
 * <p>「这个工具长什么样」不属于本包 —— 那是 {@code ui.spi.ToolView} 的职责。
 * 两者在 {@code ui.spi.ToolModule} 汇合。
 */
package com.bytefuture.toolnest.core;
