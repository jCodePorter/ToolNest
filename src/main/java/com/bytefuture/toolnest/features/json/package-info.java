/**
 * JSON 格式化与压缩。
 *
 * <ul>
 *   <li><b>界面 + 逻辑</b> {@link JsonFormatModule} —— 依赖 Gson 完成
 *       格式化 / 压缩 / 校验，逻辑薄，与界面同处一类</li>
 * </ul>
 *
 * <p>缩进处理使用 {@code JsonWriter.setIndent}，
 * <b>不要</b>用「先生成两空格再逐行后处理」的做法 ——
 * 字符串值里含换行时会把层级判错。
 */
package com.bytefuture.toolnest.features.json;
