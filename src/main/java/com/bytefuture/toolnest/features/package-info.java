/**
 * 业务功能层 —— 按业务能力分包，每个子包是一个可以直接注册进
 * {@code app.ToolRegistry} 的功能模块。
 *
 * <h2>包内分层约定</h2>
 * <p>每个 feature 包用<b>统一的类名后缀</b>表达三层职责，而不是再建三层子包 ——
 * 层与层之间需要互相访问，强行分包只会迫使可见性被放宽，反而削弱边界：
 * <ul>
 *   <li><b>界面层</b> {@code XxxModule} —— 实现 {@code ui.spi.ToolModule}：
 *       元数据、{@code buildContent()} 界面组装、事件接线。
 *       界面元素一律取自 {@code ui.components}</li>
 *   <li><b>业务层</b> {@code XxxOps} / {@code XxxExpression} —— 纯逻辑，
 *       <b>零 JavaFX 依赖</b>，可脱离界面单独测试</li>
 *   <li><b>数据层</b> {@code XxxIo} —— 文件 / 网络 / 剪贴板访问</li>
 * </ul>
 *
 * <h2>为什么没有统一的 data 包</h2>
 * <p>十一个功能里，六个完全没有 IO（timestamp / cron / json / encode / uuid / text），
 * 三个的数据访问只有几十行（filehash / qrcode / 其余），
 * 只有 image 与 http 的编解码与传输足够厚。建一个全局 data 包的结果是
 * 「两个类的空壳＋八处空目录」，那是<b>假分层</b>。
 * 因此数据访问就近放在需要它的 feature 里，薄到不值得单列时就并入业务层。
 */
package com.bytefuture.toolnest.features;
