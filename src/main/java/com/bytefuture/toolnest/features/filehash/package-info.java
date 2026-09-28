/**
 * 文件哈希计算（MD5 / SHA-1 / SHA-256 等）。
 *
 * <ul>
 *   <li><b>界面层</b> {@link FileHashModule} —— 文件选择、拖放、结果行</li>
 *   <li><b>业务 + 数据层</b> {@link HashOps} —— 流式哈希（64KB 缓冲）
 *       与文件字节读取。数据访问只有「读字节流」一种，规模薄，
 *       按「薄则合并」原则与业务同处一类</li>
 * </ul>
 *
 * <p>用流式而非一次性读入：大文件（数 GB）一次性读入会直接 OOM。
 */
package com.bytefuture.toolnest.features.filehash;
