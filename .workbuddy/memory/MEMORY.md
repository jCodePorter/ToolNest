# ToolNest 工具巢 · 项目长期记忆

项目根 `D:\Person\ToolNest`（原 DeskTool，2026-09-23 改名），包名 `com.bytefuture.toolnest`。

## 环境
- JDK 21 `C:\Program Files\Java\jdk-21`；JavaFX 21.0.12；Maven `D:\Dev\apache-maven-3.8.5`，仓库 `D:\Dev\apache-maven-repository`
- 中文 Windows（**GBK**），源码一律 UTF-8
- 系统 `JAVA_HOME` 默认 JDK 1.8 → Git Bash 跑 Maven 必须 `JAVA_HOME="C:/Program Files/Java/jdk-21" mvn ...`，否则满屏「未结束的字符串文字」。先 `echo $JAVA_HOME`
- Git Bash 调 JDK：classpath 用 `D:/...`；调 `javac.exe`/`java.exe` 前加 `MSYS2_ARG_CONV_EXCL="*"`（`.cmd` 内不要写）

## 项目约定
- 包名/主类变更同步 `pom.xml` 的 `main.class` 与 `package.cmd`（统一 `${main.class}`）
- 所有 `.cmd`/`.bat` **纯 ASCII**（cmd.exe 按 GBK 解析）；中文只放 `.java`/`.md`/`.css`
- javac 长选项双横杠；`@argfile` 内路径用正斜杠；注释里**不能出现 `*/`**
- jpackage 不覆盖已存在目录，先 `mv build/dist` 走
- 新增功能 = 新建 `ToolModule` + `ToolRegistry.bootstrap()` 注册一行
- 开发期代码放 `verify/src/main/java`（放 `src/main` 会进 fat jar）；纯净度：`jar tf target/toolnest.jar | grep -cE "^com/bytefuture/toolnest/.*(Probe|Test|Preview)"` 应为 0
- **验证必须跑运行态**：`buildContent()` 惰性调用，编译通过 ≠ 功能正常

## JavaFX 要点
- CSS 不支持 `var()`，用 looked-up colors；CSS 错误运行时才报，靠 SmokeTest uncaught handler 捕获
- **`Color.getRed()` 返回 0.0–1.0**，转 `#rrggbb` 必须 ×255（漏乘：白→黑，极易误判渲染问题）
- `Application` 子类须包一层 `Launcher`；不要按下标取容器子节点；`HBox` 折行用 `FlowPane`
- 快捷键**必须挂 Scene**；`e.getTarget() instanceof TextInputControl` 时让位原生粘贴
- `:drag-over` 非内置伪类：PseudoClass + DragOver/DragExited/DragDropped 三处切换
- 同特异性看规则先后；容器挂两个类时用组合选择器 `.a.b` 提特异性
- `ComboBox` 只在用户交互时派发 ActionEvent，`select()` 不触发 → 状态同步抽方法，两条路径都调
- 探针必须同样 `setUserAgentStylesheet(new PrimerLight())`，否则截图像素级不可信
- 载入内容后**显式隐藏空态节点**（不会自动消失）
- `-fx-pref-height` 必须与 `-fx-min-height` 成对；控件高度四档：标准 32 / 强调 36（HTTP 请求行）/ 页头 30 / 紧凑 28（chip）
- `ScrollPane` + `fitToHeight`：`content.setMinHeight(USE_PREF_SIZE)` = 可滚动不可压缩；不设则滚动条消失
- 滚动条显式 `-fx-opacity: 1`（AtlantaFX 叠不透明度）
- `Label` 居中要 `-fx-alignment`（整块）+ `-fx-text-alignment`（折行间）两条；空态/居中块必须 `setMaxWidth(Double.MAX_VALUE)`
- HBox 等宽：`setMinWidth(0)` + **显式 `setPrefWidth(同一值)`**
- **纵向铺满要三层都松**：父容器给 `VBox.setVgrow(child, ALWAYS)` + `child.setMaxHeight(MAX_VALUE)`，且 **child 自身内部的可见子节点也要 `setMaxHeight(MAX_VALUE)`**（Control 默认 max=USE_PREF_SIZE）—— 只设 Vgrow 会静默失效，表现为「内容只占上半截，下方全是空白」。实测编辑态悬空 398px

## 页面导航（ContentPane）
- 历史栈存页面 key 不存 Node；返回操作不入栈；目标==当前不记史
- 详情 LRU(12)；宫格页返回按钮有历史才显示
- `UiKit.pageHeader` 传 leading 会把 `page-title` 降级 `detail-title`，断言两者都认
- 详情页统一包 `.detail-scroll` ScrollPane（fitToWidth/fitToHeight + minHeight=USE_PREF_SIZE）

## 专项要点
- TableView：列固定 → `CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN`；列随数据 → UNCONSTRAINED + 每列 pref/min 宽
- 读用户文件**必须剥 UTF-8 BOM**；解析与显示走同一条读取路径
- 图片：唯一 `displaySize()`，`Image.getWidth()` 是原图尺寸；StackPane 叠加层被 fitWidth=MAX 的 ImageView 撑回 → fit 设实际显示尺寸；测试覆盖横/纵/方/小于上限；JPEG 写出必须合白底；不支持 WebP
- Gson 缩进：`JsonWriter.setIndent` + `Streams.write`，不要后处理替换
- 二维码：ZXing 只引 `core` 不引 `javase`（自写 LuminanceSource）；入图先 `flatten(img, WHITE)`（透明像素→全黑→识别失败）；多码用 `QRCodeMultiReader`；框选裁切外留 1/8 边距

## 文本对比（diff）· 2026-09-28 起为**两栏**（中列已删除）
- 三段式：行级 LCS → 块内「相似度+单调」最优配对（绝不能按位置配对）→ 行内 token LCS
- 行内变化用形状区分（删除线 vs 下划线）；左侧缺失必须有占位行
- 两栏页滚动留栏内：模块根 min 220 / pref 260（中列删除后仍适用）
- 每侧「编辑态 TextArea / 对比态 行 VBox」切 visible+managed；**两侧都要初始化**
- 中列（统计/变更密度图/差异清单/上一处下一处跳转）已按用户要求**整列删除**；底部 footStats 保留行数·字符数·差异处数，并追加降级声明「文本过大，已按行号逐位比对（不做对齐）」

## 源码结构
分层：`core`（零 JavaFX）／`app`（ToolRegistry/ToolNestApp 组合根）／`ui`（spi·shell·components）／`features`（按业务）。详见 `ARCHITECTURE.md`。
- `ui/shell/MainWindow·Sidebar·ContentPane·GridPanePage·ToolCard`（features 不得引用）；`ui/components/UiKit·Icons·JsonCodeArea·KeyValueTable`
- features：cron、http、text、filehash、diff（DiffOps/DiffView/TextDiffModule）、image、qrcode（QrModule 1506 行待拆）
- feature 内分层：`XxxModule`（界面）/`XxxOps`（业务，零 JavaFX）；不建三层子包

## 验证探针（改动后必跑）
`verify.cmd cron|cronui|tsui|image|qr|json|body|nav|qrpic|qrlist|sel|smoke|preview|diff|diffui`；`run <全类名>`；`-nobuild` 跳过重建。探针编译链接 fat jar，改源码没重建会报 cannot find symbol。
- 基线（2026-09-28 傍晚·两栏改版后）：**diff 50 + diffui 41 + smoke 22 + nav 19 + json 14 + body 22 + cron 43 + cronui 13 + tsui 21 + image 27 + qr 29 + qrpic 40 + qrlist 6 + sel 20 = 367 全绿**，CSS 错误 0。计数下降即回归（1:1 替换断言可保持计数不变）
- 新增断言后必须**反向验证**（破坏实现确认变红）；界面级断言落在**渲染结果**（getBackground().getFills() 色值等），不落在节点存在
- 无头截图：`button.fire()` + `scene.snapshot()`；绘制延迟用 `Platform.runLater` 嵌套 3 层；控制台中文重定向文件按 UTF-8 读回；PowerShell `*>` 是 UTF-16
- 截图看不出优先怀疑样式类与选择器没对上；放大 4x 局部再判断；按坐标猜位置是陷阱 → 让程序扫像素定位
- 多探针连跑 `qrpic`/`tsui` 因 `cannot open system clipboard` 失败，单独重跑恢复；`getString()` 返回 null=被占，复制到旧值=逻辑错
- **禁止并发跑 mvn/verify**：两个进程同时 `mvn clean` 会让另一个拿到半成品 jar（症状：maven-shade「Could not replace original artifact」、探针编译 469 个「找不到符号」JavaFX 类全丢）。构建串行化，否则结果不可信

## UiKit 组件约定
- 按钮四级：primary（一屏一处）/ secondary（入口动作：选择文件、粘贴）/ quiet / danger
- `chip` 示例条目；`resultRow` 空值统一 —；`copyableResultRow` 值实时刷新（复制读 Label 文本）
- `HBox.setHgrow` 必须配 `setMaxWidth(Double.MAX_VALUE)`
- `emptyState(icon,标题,说明)` 整块内容区用；可输入区域内部用 promptText
- 多条结果用结果行卡片列表，不用只读文本域

## 品牌与偏好
- ToolNest / 工具巢；窗口标题 `ToolNest 工具巢`；产物 `build\dist\ToolNest\ToolNest.exe`
- 界面中文，视觉「大气整洁」（见 `DESIGN.md`）；设计图放 `design/*.html`（单文件零依赖，按真实窗口 1266×763 绘制）
- 验证要真实运行态；交付可直接双击的 exe（`package.cmd`，jpackage 不覆盖已有目录 → 先 `mv build/dist build/dist_prev_<时间>`）
