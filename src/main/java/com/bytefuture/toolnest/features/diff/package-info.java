/**
 * 文本对比 —— 左原文、右修改后，逐行对齐着色。
 *
 * <h2>包内分层（按类名后缀识别）</h2>
 * <ul>
 *   <li>{@code TextDiffModule} —— 界面层入口：元数据 + 界面组装</li>
 *   <li>{@code DiffView} —— 界面层：两栏布局与交互（只依赖 {@code ui.components}）</li>
 *   <li>{@code DiffOps} —— 业务层：<b>零 JavaFX 依赖</b>的行级 / 行内 diff 算法。
 *       读文本文件很薄，按项目约定并入业务层，不单列 {@code DiffIo}</li>
 * </ul>
 *
 * <h2>为什么差异画在两侧的行上，而不是中间列</h2>
 * <p>判断「这处改得对不对」的锚点永远在原文与结果上。中间列即便只回答
 * <b>有多少 · 在哪里 · 是什么</b>，也会让眼睛在两份文本之外多一次跳转；
 * 因此这里只保留左右两栏，「有多少」由底部状态条一句话说完。
 */
package com.bytefuture.toolnest.features.diff;
