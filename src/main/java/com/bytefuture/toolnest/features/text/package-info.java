/**
 * 文本处理：去重、排序、去空行、大小写、行号、正则替换、统计。
 *
 * <ul>
 *   <li><b>界面层</b> {@link TextModule} —— 左右分栏、动作工具栏、结果区与提示行</li>
 *   <li><b>业务层</b> {@link TextOps} —— 全部变换都是纯函数，
 *       零 JavaFX、零 IO，输入字符串输出字符串</li>
 * </ul>
 *
 * <p>本包是分层的正面样板：界面类里找不到一处文本算法，
 * 所有变换都能被纯逻辑探针直接覆盖，不需要启动界面。
 */
package com.bytefuture.toolnest.features.text;
