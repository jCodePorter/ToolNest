/**
 * 装配层（组合根）—— 全应用唯一允许「知道所有具体实现」的地方。
 *
 * <p>职责只有两件事：注册全部功能模块（{@code ToolRegistry}）、启动应用
 * （{@code ToolNestApp}）。它依赖 {@code ui} 与 {@code features}，
 * 但不被任何层依赖。
 *
 * <p>这个包存在的意义是<b>把依赖倒置纠正回来</b>：原先功能注册表住在 {@code core}，
 * 导致最内层的领域包反向 import 十个具体模块 —— 每加一个工具都要改动核心。
 * 移到本包后，{@code core} 保持纯净，而「新增功能要改的那一个地方」依然只有一处。
 */
package com.bytefuture.toolnest.app;
