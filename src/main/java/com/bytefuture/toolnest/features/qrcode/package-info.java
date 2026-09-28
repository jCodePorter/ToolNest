/**
 * 二维码生成与解析。
 *
 * <ul>
 *   <li><b>界面层</b> {@link QrModule} —— 双页签（解析 / 生成）、拖放、框选、
 *       剪贴板粘贴、导出、浏览器打开</li>
 *   <li><b>业务层</b> {@link QrOps} —— ZXing 编解码与三级降级解析
 *       （原图 → 放大三倍 → 高对比度灰度）。纯逻辑，零 JavaFX</li>
 *   <li><b>数据层</b> —— 文件读写与剪贴板访问目前仍在 {@code QrModule} 内，
 *       是本包下一步的拆分目标</li>
 * </ul>
 *
 * <h2>两个必须记住的坑</h2>
 * <ol>
 *   <li><b>入图前必须 {@code flatten(WHITE)}</b>：截图常带 alpha 通道，
 *       透明像素的 RGB 为 0，而 ZXing 只看亮度 —— 会被判为全黑，
 *       解析 100% 失败。界面上表现为「二维码发白」</li>
 *   <li>只引 ZXing 的 {@code core}，<b>不引 {@code javase}</b>：
 *       后者会连带拉进 jcommander 与 jai-imageio-core 两个运行时依赖，
 *       而我们只需要其中约四十行的能力</li>
 * </ol>
 */
package com.bytefuture.toolnest.features.qrcode;
