/**
 * HTTP 请求工具：单发请求 + CSV 驱动的批处理。
 *
 * <ul>
 *   <li><b>界面层</b> {@link HttpToolModule}（入口与页签）、
 *       {@link SingleRequestPanel}（单发）、{@link BatchPanel}（批处理）</li>
 *   <li><b>业务层</b> {@link TemplateResolver}（变量占位符替换）、
 *       {@link HttpRequestSpec}（请求描述模型）</li>
 *   <li><b>数据层</b> {@link HttpEngine}（网络传输与响应收集）、
 *       {@link CsvParser}（CSV 读取）</li>
 * </ul>
 *
 * <p>本包与 {@code image} 是全项目仅有的两个数据层足够厚的功能，
 * 因此 {@code HttpEngine} 与 {@code CsvParser} 都独立成类，只做传输与解析。
 *
 * <p><b>CSV 必须剥 UTF-8 BOM</b>：否则 Excel 导出的文件首列会渲染成空心方框。
 * 同一份内容的「解析」与「显示」必须走同一条读取路径。
 */
package com.bytefuture.toolnest.features.http;
