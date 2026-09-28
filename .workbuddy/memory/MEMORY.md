# ToolNest 工具巢 · 项目长期记忆

项目根 `D:\Person\ToolNest`（原名 DeskTool/desk-tool，2026-09-23 改名），包名 `com.bytefuture.toolnest`。

## 环境（务必遵守）

- JDK 21：`C:\Program Files\Java\jdk-21`；JavaFX 21.0.12；Maven `D:\Dev\apache-maven-3.8.5`，仓库 `D:\Dev\apache-maven-repository`
- 系统中文 Windows（**GBK**），源码一律 UTF-8
- **系统 `JAVA_HOME` 默认是 JDK 1.8**。Git Bash 跑 Maven 必须
  `JAVA_HOME="C:/Program Files/Java/jdk-21" mvn ...`，
  否则满屏「未结束的字符串文字 / 非法的表达式开始」并波及大量**你没改过**的文件。先 `echo $JAVA_HOME` 再改代码。
- Git Bash 调 JDK：classpath 用 Windows 风格 `D:/...`（`/d/...` 读不到）；调 `javac.exe`/`java.exe` 前加 `MSYS2_ARG_CONV_EXCL="*"`

## 项目约定

- 包名/主类变更要同步 `pom.xml` 的 `main.class` 与 `package.cmd` 的 `MAIN_CLASS`，统一用 `${main.class}` 引用
- **所有 `.cmd`/`.bat` 必须纯 ASCII**：cmd.exe 按 GBK 解析，中文会吞掉换行导致命令错位（`REM`→`EM`）。中文只放 `.java`/`.md`/`.css`
- `MSYS2_ARG_CONV_EXCL="*"` 只在 Git Bash 里需要；`.cmd` 内**不要**写（cmd 会当命令名报错）
- javac 长选项用双横杠（`--release 21`）；`@argfile` 内路径必须正斜杠（反斜杠是转义符，会被吃掉）
- **`*/` 不能出现在 Javadoc/块注释里**（如 `{@code */30 * * * * *}`）——它会提前闭合注释，
  报错却出现在几十行之后（「非法字符: '\uff09'」「未命名类不应有程序包声明」），极易误判为括号不匹配。
  注释里要写就拆成 `{@code *}{@code /30}`
- 打包：jpackage 不覆盖已存在目录，跑 `package.cmd` 前先把旧 `build/dist` mv 走
- 新增功能 = 新建 `ToolModule` 实现 + `ToolRegistry.bootstrap()` 注册一行
- **开发期代码放 `verify/src/main/java`**，别放 `src/main`（探针/离屏渲染器会被打进 fat jar）。
  纯净度自检（**必须限定到本项目包**，裸 `grep -E "Probe|Test|Preview"` 会命中 JavaFX 自带的一堆
  `*_AlphaTest.obj` 而报出 214，纯属噪音）：
  `jar tf target/toolnest.jar | grep -cE "^com/bytefuture/toolnest/.*(Probe|Test|Preview)"` 应为 **0**
- **验证必须跑运行态**：`buildContent()` 惰性调用，编译通过 ≠ 功能正常。改完跑 `verify.cmd smoke` + 对应探针

## 核心技术要点

### JavaFX
- CSS **不支持** `--x` / `var(--x)`（静默失效）。必须用 looked-up colors：`.root { -dt-accent: #2563eb; }`，间距/圆角/字号只能写字面量。
  CSS 错误只在运行时暴露，靠 `SmokeTest` 的 uncaught handler 捕获（应为 **0**）
- **`Color.getRed()/getGreen()/getBlue()` 返回 0.0–1.0**，转 AWT 必须 `Math.round(c.getRed()*255)`。
  漏乘 255 的症状：白 `(1,1,1)`→`#010101`（看着全黑），而黑 `(0,0,0)` 恰好仍对 —— 极易误判为渲染/CSS 问题
- `Application` 子类不能直接当 main class（报 "JavaFX runtime components are missing"），须包一层 `Launcher`；
  `Application.launch()` 不返回，静态输出流要用 `static {}` 初始化
- 不要按下标取容器子节点（`HBox.getChildren().get(n)` 易取到 spacer 等 Region → CCE），直接持有引用
- `HBox` 没有 `setWrapText()`，折行用 `FlowPane`
- 快捷键（Ctrl+V 等）**必须挂 Scene**（`root.sceneProperty()` 装卸），挂子节点收不到事件；`e.getTarget() instanceof TextInputControl` 让位给原生粘贴
- `javafx-swing` 是**运行时依赖**（图片模块用 `SwingFXUtils.toFXImage`）
- **`:drag-over` 不是内置伪类**（内置只有 `:hover`/`:focused`/`:pressed`/`:disabled`/`:selected`）。
  写 `.drop-zone:drag-over` 不报错也永不生效 → 拖放高亮怎么调都不亮。必须
  `PseudoClass.getPseudoClass("drag-over")` + `pseudoClassStateChanged`，且 DragOver=true / DragExited=false / DragDropped=false 三处都要切
- **两个样式类特异性相同时，胜负只看规则在文件里的先后**。容器同时挂 `.image-preview-holder` 与 `.drop-zone` 时，
  后者定义更靠前 → 虚线改不动。用组合选择器 `.image-preview-holder.drop-zone` 提高特异性，别依赖章节顺序
- **`ComboBox` 只在用户交互时派发 ActionEvent**，`getSelectionModel().select()` / `setValue()` 都不触发。
  于是「重置参数」后依赖 onAction 更新的控件状态会留在旧值 → 把状态同步抽成一个方法，两条路径都调它
- **AtlantaFX（PrimerLight）与内置 modena 的默认样式差别很大**：禁用滑块仍是主色拇指（禁用态看不出来）；
  modena 下 `.root` 被覆写后 CheckBox 文字会被 ladder 算成浅色而「看不见」。
  因此**探针必须与产品一样设 `setUserAgentStylesheet(new PrimerLight()...)`**，否则截图像素级不可信
- 载入内容后要**显式隐藏空态节点**（它不会自动消失，会一直压在图片/结果正中央）
- **`-fx-pref-height` 必须与 `-fx-min-height` 成对出现**。pref 只是「建议值」，
  父容器（VBox）空间不足时会一路压到 min，而 min 的默认值是内容自然高 ——
  12.5px 字的按钮被压成 **18px 细条**（实测 `pref=32.0 min=18.0 实际=18.0`）。
  症状看起来像「渲染坏了」而不是「挤了一点」。补了 min 之后压缩无处可去，只能变成滚动条
- **控件高度只取四档**：标准 32px（按钮 / 输入框 / 下拉 / 数字框 / 分段项）、
  强调 36px（HTTP 请求行）、页头 30px（返回按钮 / 内层页签）、紧凑 28px（chip）
- **`ScrollPane` 在 `fitToHeight` 下摆内容是取 `max(视口高, 内容最小高)`**。
  因此 `content.setMinHeight(Region.USE_PREF_SIZE)` 就是「可滚动、不可压缩」的标准表达；
  不设它，页面会被径直压进视口，**滚动条直接消失**（反向验证：撤掉后 HTTP 页内容高 1054 → 596）
- **不要用「直接子节点有没有 `VBox.setVgrow(..., ALWAYS)`」判断页面要不要滚动**。
  `VGrow` 只说明「空间够时想长大」，不说明「空间不够时不需要滚动」。
  HTTP 页正是这样被判成「铺满一屏」，进而整页被压变形
- **滚动条必须显式写 `-fx-opacity: 1`**：AtlantaFX 给滚动条叠了一层整体不透明度，
  thumb 实测渲染成 `#e0e4e9`，与内容底 `#f8f9fb` 无法分辨 → 用户不知道这页还能往下滚

### 页面导航（ContentPane）
- 历史栈存**页面 key**（`cat:X`/`mod:x`/`__all__`），不存 Node；① 返回操作本身不入栈 ② 目标 key == 当前则不记史
- 详情页缓存有界 LRU（12 项，`LinkedHashMap(accessOrder=true)`）；宫格页返回按钮**有历史时才显示**
- `UiKit.pageHeader` 传了 leading 会把标题样式类从 `page-title` 降级为 `detail-title`，断言时两者都要认
- 详情页**统一包 `ScrollPane`**（`.detail-scroll`）+ `fitToWidth/fitToHeight`，
  内容 `minHeight = USE_PREF_SIZE`。旧的 `isFillLayout`（按 VGrow 分流）已删除

### TableView 列宽
- 列数固定已知 → `CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN`
- 列数由数据决定 → `UNCONSTRAINED_RESIZE_POLICY` + 每列 `setPrefWidth`/`setMinWidth`
  （用 CONSTRAINED 会把列压成省略号，表现为「列丢失」）

### 文本 IO
- 读用户文件（尤其 CSV）**必须剥 UTF-8 BOM**：`CsvParser.readText(Path)` / `stripBom(String)`，否则渲染成空心方框 `□`
- 同一份内容的「解析」与「显示」必须走同一条读取路径

### 图片预览坐标换算（框选 / 取点）
- 抽出**唯一方法 `displaySize()`**：`scale = min(1, min(MAX/srcW, MAX/srcH))`，显示尺寸 = 源尺寸 × scale
- **`Image.getWidth()` 是原图尺寸，不是显示尺寸**。拿它算 scale → 恒等于 1 → 裁切完全错位
- StackPane 取子节点最大 pref，而 `fitWidth=MAX` 的 ImageView 其 **pref 仍是 MAX** → 叠加层被撑回 MAX。
  修法：把 ImageView 的 `fitWidth/fitHeight` 也设成实际显示尺寸
- 测试必须覆盖**横向/纵向/正方形/小于上限**四种宽高比（正方形会让错误算法「碰巧对上」）

### 图片处理（JDK ImageIO，零依赖）
- 支持 PNG/JPEG/BMP/GIF（+TIFF 输入），**不支持 WebP**
- **JPEG 无透明通道，写出必须自动合白底**（不是 AWT 默认的黑）—— 最易出 bug 处，`ImageTest` 有断言
- 「只填宽或只填高」→ 按比例算另一边；两边都填 → 取**较小**比例，保持不变形

### Gson 缩进
- 用 `JsonWriter.setIndent(indent)` + `com.google.gson.internal.Streams.write(element, writer)`。
  不要「先生成 2 空格再后处理替换」——字符串值内含换行会误判层级

### 二维码（ZXing 只引 `core`，**不引 `javase`**）
- `javase` 会连带拉进 `jcommander` + `jai-imageio-core`；自写 ~40 行 `LuminanceSource` + `toBufferedImage` 替代
- `MultiFormatReader` 没有 `decodeMultiple()`，多码检测用 `QRCodeMultiReader`
- 解析三级降级：原图 → 放大 3 倍 → 高对比度灰度
- **入图必须先 `ImageOps.flatten(img, WHITE)`**：截图常带 alpha，透明像素 RGB=0 → ZXing 只看亮度 → 判全黑 → **100% 识别失败**（界面表现为「二维码发白」）
- 框选裁切 `scaleX = source.width / shown.width`，并向外留 **1/8 边距**
- 生成预览必须 `setSmooth(false)`；剪贴板优先级：图片 → 文件 → 文本

### 无头截图验证（本项目惯用手法）
- 直接 `button.fire()` + `scene.snapshot()`，不依赖鼠标/窗口位置
- `applyCss()+layout()` **只保证布局不保证绘制**：`setImage()` 后立刻 snapshot 会截到空块 →
  用 `Platform.runLater` **嵌套 3 层**再截图（探针手法问题，非产品缺陷）
- 控制台中文乱码（GBK）：输出重定向到文件再按 UTF-8 读回，断言别依赖控制台可见中文
- 临时探针类**必须写 `package` 声明**，用完即删；定位控件按**样式类**或持有引用，不要按「第 N 个」
- 排查心法：隔离复现全过 = 简化掉了出问题的那一层 → 改为在真实对象上取数据比对

## 源码结构速查

**分层：`core`（领域契约，零 JavaFX）／`app`（组合根）／`ui`（spi·shell·components）／`features`（按业务分包）**
完整依赖规则、数据层判定与关键决策（ADR-001~006）见 `ARCHITECTURE.md`；每个包有 `package-info.java` 说明各自约定。

| 位置 | 职责 |
|---|---|
| `core/ToolDescriptor` / `ModuleCategory` | 工具元数据 + 分类枚举（**不得出现 `javafx.`**） |
| `app/ToolRegistry` / `ToolNestApp` | 组合根：注册全部模块 / 启动（唯一可 import 全部具体实现的包） |
| `ui/spi/ToolView` · `ToolModule` | 扩展点契约（`ToolModule extends ToolDescriptor, ToolView`） |
| `ui/shell/MainWindow` · `Sidebar` · `ContentPane` · `GridPanePage` · `ToolCard` | 框架界面（**features 不得引用**） |
| `ui/shell/ContentPane` | 返回栈 + 详情 LRU(12) |
| `ui/components/UiKit` | 视觉组件工厂（按钮四级 / chip / 结果行 / 空态 / 拖放伪类） |
| `ui/components/Icons` | 线性图标族（24 网格 SVGPath，`of/forModule/forCategory`） |
| `ui/components/JsonCodeArea` | RichTextFX 语法高亮 |
| `ui/components/KeyValueTable` | 键值对编辑表格（原在 `features/http`，已迁入公共组件层） |
| `resources/css/app.css` | 设计系统令牌 + 组件样式 |
| `features/cron/CronExpression` / `CronModule` | cron 解析 + 下次执行（向后试探）/ 界面 + `describeRows`、`nextRows`、`humanDuration` |
| `features/http/HttpEngine` · `CsvParser` · `TemplateResolver` · `HttpRequestSpec` | 传输 / CSV / 变量替换 / 请求模型 |
| `features/http/SingleRequestPanel` · `BatchPanel` | 单请求 / 批处理界面 |
| `features/text/TextOps` | 纯函数式文本变换 |
| `features/filehash/HashOps` | 流式哈希（64KB 缓冲） |
| `features/image/ImageOps` | 缩放/裁剪/旋转/翻转/格式转换（含 ImageIO 读写，待拆 `ImageIo`） |
| `features/qrcode/QrOps` / `QrModule` | 二维码纯逻辑 / 双页签界面（1506 行，待拆） |
| `verify/src/main/java/...` | 开发期探针（不进产物） |

**feature 内部分层**：`XxxModule`（界面）／`XxxOps`（业务，零 JavaFX）／`XxxIo`（数据，薄则并入业务）。
**包表达业务边界，类名后缀表达层次 —— 不建三层子包**（层间需互相访问，分包反而要放宽可见性）。

## 验证探针（改动后必跑）

`verify.cmd cron|cronui|tsui|image|qr|json|body|nav|qrpic|qrlist|sel|smoke|preview`；`verify.cmd run <全类名>`；加 `-nobuild` 跳过重建 jar。
探针是**编译链接到 fat jar** 上的，源码改了没重建 jar 会报 "cannot find symbol"（很难排查）。

- `cron` = `CronTest`（纯逻辑）／`cronui` = `CronModuleProbe`（界面级：真实**表格单元格** + 卡片行数 + 截图 `target/cron-probe/`）
- `tsui` = `TimestampModuleProbe`（界面级：**点复制后读系统剪贴板比对** + 改输入后必须复制到新值，`target/timestamp-probe/`）
- `qrpic`/`qrlist`/`sel`/`json`/`body`/`nav`/`smoke` 为界面级；`image`/`qr` 为纯逻辑
- `qrlist` = `MultiQrProbe`（界面级：合成双码图 → 断言「界面卡片数 == 解码条数」，`target/qr-list-probe/`）

基线（2026-09-28）：**smoke 21 + cron 43 + cronui 13 + tsui 21 + image 27 + qr 29 + qrpic 40 + qrlist 6 + sel 20 + json 14 + body 22 + nav 19 = 275 条全绿**，CSS 错误 **0**。计数下降即回归。
**新增断言后必须做「反向验证」**（临时破坏实现确认变红），否则区分不出「断言有效」与「断言恒真」。
截图：`target/smoke/`、`build/ui-preview/`。
**同一批连续跑多个 JavaFX 探针时，`qrpic` / `tsui` 会因 `cannot open system clipboard` 失败** ——
系统剪贴板被别的进程独占（实测常驻的 Gradle daemon 也会占），**与本轮改动无关**。
`qrpic` 症状是 40 条全失败、或**整个探针连 `RESULT` 行都不打印**（进程直接挂掉）；
`tsui` 是固定那 4 条剪贴板断言变红。单独重跑通常可恢复。
**判别铁证**：写一个**不含项目代码**的 20 行程序读写剪贴板，同样报错即可确认是环境问题。
**必须区分两种失败模式**：剪贴板被占 → `getString()` 返回 `<null>`（剪贴板是空的）；
逻辑写错（如构造时快照）→ `实际 —`（复制到旧值）。含义完全不同，别混为一谈。

## UI 组件约定（`UiKit` 工厂，模块侧不要自拼样式）

- 按钮四级：`primaryButton`（**一屏只允许一处**主色实底）/ `secondaryButton`（描边）/ `quietButton`（纯文字）/ `dangerButton`（危险色描边）
- 入口动作（选择文件、粘贴）**一律 secondary** —— 它们只取输入，不提交；主色留给导出/发送/执行
- `chip(label, code, action)`：示例条目，白底描边 + 名称 + **等宽淡色表达式**，悬停转主色。承载的是数据不是动作，别用 secondaryButton 凑
- `resultRow(label, 值, divided, copyable)`：标签定宽 96px 的纵向对齐网格，空值统一 `—`（破折号）
- `copyableResultRow(label, Label, divided)`：**值随输入实时刷新**的行专用 —— 复制按钮点击时才读 Label 文本；
  构造时快照会把初始的 `—` 复制出去。值为空时按钮 `disable`（不隐藏），保住右侧按钮列对齐
- **`HBox.setHgrow(node, ALWAYS)` 必须配 `node.setMaxWidth(Double.MAX_VALUE)`**，否则弹性生长静默失效：
  `Control`/`Label` 的 `maxWidth` 默认是 `USE_PREF_SIZE`，宽度长不上去。症状是行尾按钮紧贴数值后面、
  各行参差不齐（而不是「按钮跑到左边」这种明显的坏样子）
- `emptyState(icon, 标题, 说明)`：整块内容区用；**可输入区域内部仍用 promptText**
- `vspacer()`：VBox 里的垂直弹性占位（`spacer()` 设的是 Hgrow，放 VBox 里**完全不起作用**）
- 结果可能有多条时用**结果行卡片列表**，不要用只读文本域 —— 文本域结构上只装得下一个，多出来的会被静默丢弃

## 品牌与偏好

- 英文名 ToolNest / 中文名 工具巢；窗口标题 `ToolNest 工具巢`；产物 `build\dist\ToolNest\ToolNest.exe`（fat jar `toolnest.jar`）
- 界面中文，视觉「大气整洁」（设计系统见 `DESIGN.md`）
- 验证要**真实运行态**，不接受「编译通过」当完成；交付要给出可直接双击的 exe
