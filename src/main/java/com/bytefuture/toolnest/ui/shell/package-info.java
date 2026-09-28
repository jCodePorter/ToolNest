/**
 * 框架界面 —— 应用外壳，绑定 ToolNest 的三层信息架构（分类 → 功能宫格 → 功能详情）。
 *
 * <p>包含窗口骨架（{@code MainWindow}）、侧边栏（{@code Sidebar}）、
 * 内容区与返回导航（{@code ContentPane}）、宫格页（{@code GridPanePage}）、
 * 功能卡片（{@code ToolCard}）。
 *
 * <p><b>本包不对外复用</b>：具体功能模块（{@code features.*}）一律不得 import 这里。
 * 需要展示用的构件请从 {@code ui.components} 取。
 */
package com.bytefuture.toolnest.ui.shell;
