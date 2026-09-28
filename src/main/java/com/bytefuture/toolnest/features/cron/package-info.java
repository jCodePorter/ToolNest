/**
 * Cron 表达式解析与下次执行时间推算。
 *
 * <ul>
 *   <li><b>界面层</b> {@link CronModule} —— 表达式输入、字段解读表、
 *       下次执行列表、示例 chip</li>
 *   <li><b>业务层</b> {@link CronExpression} —— 解析、合法性校验、
 *       向后试探求下次执行时间。纯逻辑，零 JavaFX、零 IO</li>
 * </ul>
 *
 * <p>本包是「业务逻辑已正确抽到独立类」的样板：界面文件里没有任何
 * 日历推算代码，{@code CronExpression} 可以被纯逻辑探针完整覆盖。
 */
package com.bytefuture.toolnest.features.cron;
