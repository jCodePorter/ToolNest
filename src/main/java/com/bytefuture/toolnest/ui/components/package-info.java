/**
 * 公共 UI 组件层 —— 与业务无关、可被任何 feature 直接复用的界面构件。
 *
 * <ul>
 *   <li>{@code UiKit} —— 页面骨架、区块、字段、结果行、提示条、按钮四级、chip</li>
 *   <li>{@code Icons} —— 自绘线性图标族（24 网格 SVG path，无图片资源）</li>
 *   <li>{@code JsonCodeArea} —— 基于 RichTextFX 的语法高亮编辑区</li>
 *   <li>{@code KeyValueTable} —— 仿 Postman 的键值对编辑表格（原住在
 *       {@code features.http} 内，导致其他功能无法复用，本次重构迁入）</li>
 * </ul>
 *
 * <p>组件只负责「长什么样」，不读业务数据、不做业务判断。
 * 各功能模块不得自行写 {@code setPadding} / {@code setStyle}，一律走这里 ——
 * 全站视觉一致性与换肤能力都依赖这一点。
 *
 * <p>设计令牌与规则见 {@code DESIGN.md}。
 */
package com.bytefuture.toolnest.ui.components;
