/**
 * 图片处理：缩放、精确重绘、裁剪、旋转、翻转、格式转换。
 *
 * <ul>
 *   <li><b>界面层</b> {@link ImageModule} —— 参数表单、预览、批量导出、后台线程</li>
 *   <li><b>业务层</b> {@link ImageOps} —— 变换算法与格式推断</li>
 *   <li><b>数据层</b> —— 编解码（{@code ImageIO} 读写与 JPEG 质量控制）
 *       目前与业务同处 {@code ImageOps}，约八十行，按「薄则合并」暂不单列</li>
 * </ul>
 *
 * <h2>两个高发 bug 点</h2>
 * <ol>
 *   <li><b>转 JPEG 必须自动合白底</b>：JPEG 无透明通道，直接写会让透明区域
 *       变成黑块。这是用户最常见的操作路径（PNG 转 JPG），必须无感成功</li>
 *   <li><b>只填宽或只填高</b>要按比例算另一边；两边都填时取<b>较小</b>比例，
 *       保证不变形</li>
 * </ol>
 */
package com.bytefuture.toolnest.features.image;
