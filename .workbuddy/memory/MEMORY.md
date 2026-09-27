# ToolNest 工具巢 项目长期记忆

> 项目原名 `DeskTool` / `desk-tool`，2026-09-23 改名为 **ToolNest**（中文「工具巢」），
> 包名 `com.bytefuture.desktool` → `com.bytefuture.toolnest`。
> 项目根目录：`D:\Person\toolnest`（原 `D:\Person\desk-tool`）。

## 环境（务必遵守）

- JDK **21**：`C:\Program Files\Java\jdk-21`；JavaFX 21.0.12（Maven 依赖）
- Maven：`D:\Dev\apache-maven-3.8.5`，本地仓库 `D:\Dev\apache-maven-repository`
- 系统是中文 Windows（**GBK**），源码一律 UTF-8
- **系统 `JAVA_HOME` 默认指向 JDK 1.8**。在 Git Bash 里直接跑 Maven 必须显式覆盖：
  `JAVA_HOME="C:/Program Files/Java/jdk-21" mvn ...`
  否则满屏「未结束的字符串文字 / 非法的表达式开始」，且会波及大量**你没改过**的文件。
  看到这种报错先 `echo $JAVA_HOME`，别急着改代码。
- Git Bash 下调 JDK 工具链：
  - classpath 必须用 **Windows 风格路径** `D:/...`（MSYS 风格 `/d/...` 读不到）
  - 调 `javac.exe` / `java.exe` 前加 `MSYS2_ARG_CONV_EXCL="*"`，阻止 MSYS 二次转换

## 项目约定

- 包名 **`com.bytefuture.toolnest`**（原名 `com.bytefuture.desktool`）。
  改包名/主类要同步 `pom.xml` 的 `main.class` 属性 + `package.cmd` 的 `MAIN_CLASS`，
  始终用 `${main.class}` 引用，只维护一处。
- **所有 `.cmd` / `.bat` 必须纯 ASCII**。cmd.exe 按 GBK 解析脚本，中文会吞掉换行导致
  命令错位（`REM`→`EM`）。中文只放 `.java` / `.md` / `.css`。
- 打包：`build/dist` 被沙箱保护不能直接删。跑 `package.cmd` 前先把旧目录 `mv` 走，
  因为 jpackage 遇到已存在的目标目录会**直接报错而非覆盖**。
- 新增功能 = 新建 `ToolModule` 实现类 + 在 `ToolRegistry.bootstrap()` 加一行注册。
- **验证必须真跑运行态**。编译通过完全不代表功能正常 —— 模块的 `buildContent()`
  是惰性调用的，异常与 CSS 错误只在真正打开界面时才暴露。
  改完跑 `verify.cmd smoke`（全模块 + CSS 错误）+ 对应的逻辑探针。
- **开发期代码放 `verify/src/main/java`，不要放 `src/main`**。探针 / 离屏渲染器
  会被打进 fat jar，它们是脚手架不是产品。`verify.cmd` 负责编译运行它们
  （原理：编译链接到 fat jar + 探针 class 目录）。校验产物纯净度：
  `jar tf target/toolnest.jar | grep -cE "Probe|SmokeTest|Test|Preview"` 应为 **0**。

## 技术要点

### cmd 脚本（`.cmd` / `.bat`）

- **必须纯 ASCII**。cmd.exe 按 GBK 解析，中文会吞掉换行导致命令错位（`REM`→`EM`）。
- **换行符三种都行**，但 `^` 续行在 LF 下也正常 —— 曾误判为换行符问题，实际不是。
- **JDK 21 的 `javac` 不接受单横杠长选项**：写 `--release 21`，
  `-release 21` 会报「无效的标记」。这不是 cmd 的问题，纯 javac 行为。
- **javac 的 `@argfile` 里路径必须用正斜杠**：argfile 中反斜杠是转义符，
  `D:\Person\...` 被吃成 `D:Person...`（`\t` 还变制表符）。
  cmd 里从 `for /r` 拿到的是反斜杠路径，转换要
  `setlocal enabledelayedexpansion` + `set "p=!p:\=/!"`；
  **注意 `endlocal` 会丢掉块内变量**，要在 `setlocal` 之前先把变量设好。
- `MSYS2_ARG_CONV_EXCL="*"` 只在 **Git Bash 里调 JDK 工具**时才需要；
  `.cmd` 文件里 cmd.exe 原样传参，**不要**写这个前缀（cmd 会当成命令名而报错）。

### 代码健壮性（踩过的坑）

- **不要按下标取容器子节点**。`HBox.getChildren().get(n)` 极易取到 `spacer` 等
  `Region` 而非目标 Button，强转直接 `ClassCastException`。
  混入非目标类型节点的容器，一律**直接持有引用**。
- **`HBox` 没有 `setWrapText()`**。需要自动折行必须用 `FlowPane`。

### JavaFX 生命周期与 javafx-swing

- `Application` 子类**不能直接作为 main class**（报 "JavaFX runtime components
  are missing"）。除 `Launcher` 外，开发期探针也要各自包一层
  `XxxProbeLauncher` + `Application.launch(XxxProbe.class, args)`。
- **`javafx-swing` 是运行时依赖**（不再只是探针需要）：图片模块用
  `SwingFXUtils.toFXImage()` 把 `BufferedImage` 转成 JavaFX `Image` 做预览。

### 图片处理（JDK ImageIO，零第三方依赖）

- 支持 PNG / JPEG / BMP / GIF（+ TIFF 输入）；**不支持 WebP**（JDK 无内置解码器）。
- **JPEG 无透明通道，写出时必须自动合成白底**（不是 AWT 默认的黑）。
  这是最容易出 bug 的点，`ImageTest` 专门断言「透明 PNG 转 JPEG 后为白色」。
- 「只填宽或只填高」→ 按比例算另一边；两边都填 → 取**较小**比例，保持不变形。

### JavaFX CSS
**不支持 Web CSS 的自定义属性**（`--x` + `var(--x)` 会静默失效）。
必须用命名颜色（looked-up colors）：`.root` 里定义 `-dt-accent: #2563eb;`，别处裸写。
间距/圆角/字号/高度只能写字面量。
验证方式：跑 `SmokeTest`，其未捕获异常处理器会捕获 CSS 解析错误（应为 **0**）——
只看编译成功完全不够，CSS 错误是运行时才报的。

### 页面导航（ContentPane）

- 历史栈存**页面 key**（`cat:X` / `mod:x` / `__all__`），不存 Node ——
  页面被 LRU 淘汰后仍能凭 key 重建。
- 两条必须遵守的规则：① **返回操作本身不入栈**，否则两页之间来回死循环；
  ② 目标 key 与当前相同则不记史，否则连点后「一次返回只退半步」。
- 详情页缓存是**有界 LRU**（12 项，`LinkedHashMap(accessOrder=true)` +
  `removeEldestEntry`）。模块数持续增长，无上限缓存 = 只增不减的内存泄漏。
- 宫格页也有返回按钮，**有历史时才显示**（无历史时隐藏，不留点了没反应的死按钮）。
  注意 `UiKit.pageHeader` 传了 leading 会把标题样式类从 `page-title` 降级为
  `detail-title`，断言标题时两者都要认。

### TableView 列宽策略
- 列数**固定且已知** → `CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN`（列自适应填满）
- 列数**由数据决定**（CSV 预览、动态表头）→ `UNCONSTRAINED_RESIZE_POLICY`
  + 每列设 `setPrefWidth` 和 `setMinWidth`，内容溢出时横向滚动。
  用 CONSTRAINED 会把列压成省略号，表现为「列丢失」。

### 图片预览的坐标换算（框选 / 取点）

图片用 `ImageView(preserveRatio=true, fitWidth=fitHeight=MAX)` 时，
**实际显示尺寸 ≠ 原图尺寸，也不等于「各自截断到 MAX」**。
必须按等比缩放算：

```java
double scale = Math.min(1.0, Math.min(MAX / srcW, MAX / srcH));
double[] shown = { srcW * scale, srcH * scale };
```

三条铁律（都踩过坑）：

1. **抽出唯一方法 `displaySize()`**，让「设叠加层尺寸」「裁切换算」「探针核算」
   共用 —— 三处各算一遍必然写出不一致的换算。
2. **`javafx.scene.image.Image.getWidth()` 返回原图尺寸，不是显示尺寸。**
   拿它当显示尺寸算缩放比 → `scale` 恒等于 1 → 裁切完全错位。
   要显示尺寸就调 `displaySize()`。
3. **叠加层与 ImageView 同处 StackPane 时，StackPane 取子节点最大 pref**。
   `fitWidth=MAX` 的 ImageView 其 **pref 仍是 MAX**（fit 只是上限不缩 pref），
   若叠加层 pref 更小（小图），会被撑回 MAX。
   修法：把 ImageView 的 `fitWidth/fitHeight` 也设成实际显示尺寸。

> 只用一个尺寸的图测试会漏掉**宽高比 ≠ 1** 的情况
> （正方形图恰好让错误算法「碰巧对上」）。必须覆盖横向 / 纵向 / 正方形 / 小于上限。

### 文本 IO
- 读用户文件（尤其 CSV）**必须剥 UTF-8 BOM**（`U+FEFF`）。用 `Files.readString(UTF_8)`
  会把 BOM 解成 `U+FEFF` 留在开头，JavaFX 渲染成空心方框 `□`。
  统一走 `CsvParser.readText(Path)` / `CsvParser.stripBom(String)`。
- **同一份内容的「解析」与「显示」必须走同一条读取路径**，否则会出现
  「表格对了但编辑框脏」这类不一致 bug。

### Gson 用 JsonWriter 直接控制缩进

不要「先生成 2 空格再用后处理替换成目标缩进」—— 后处理靠「行首空格数 ÷ 2」
反推层级，遇到字符串值内含换行（`"line1\nline2"`）会误判成结构行而套错缩进。
正确做法：`JsonWriter.setIndent(indent)` + `com.google.gson.internal.Streams.write(element, writer)`。
注意 `setSerializeHtmlAsText` 在 `GsonBuilder` 上，`JsonWriter` 这层对应的是
`setSerializeNulls(true)`。

### 二维码（ZXing `core`）

- 只引 **`com.google.zxing:core`**，**不引 `javase`** —— 后者会连带拉进
  `jcommander` + `jai-imageio-core`（本工具用不到）。`core` 运行时零传递依赖。
  `javase` 只提供 `BufferedImageLuminanceSource` / `MatrixToImageConfig` 这类胶水类，
  自己写约 40 行 `LuminanceSource` 子类 + `toBufferedImage(matrix, on, off)` 即可替代。
  （`import com.google.zxing.client.j2se.*` 会在编译期就找不到。）
- **`MultiFormatReader` 没有 `decodeMultiple()`**，只有 `MultipleBarcodeReader` /
  `QRCodeMultiReader` 有。多码检测用 `QRCodeMultiReader`，单码回退用
  `MultiFormatReader.decode()`。
- 解析走**三级降级**：原图 → 放大 3 倍 → 高对比度灰度，救小码 / 浅色码 / 带干扰截图。
- 框选裁切坐标换算 `scaleX = source.width / shown.width`，并**向外留 1/8 边距** ——
  用户框得比码略小时会切掉定位角。
- 剪贴板优先级：图片 → 文件 → 文本，逐级探测给对应提示。
- 生成预览必须 **`setSmooth(false)`**，否则码点边缘插值糊掉。
- **入图必须先合成白底**：截图工具产出的图常带 alpha，而透明像素的 RGB 是 0，
  ZXing 只看亮度 → 整张图被判成全黑 → **100% 识别失败**；
  界面上还表现为「二维码发白」（露出容器浅灰底纹）。
  统一在 `setDecodeImage()` 入口调 `ImageOps.flatten(img, WHITE)`，
  三条入图路径（文件/拖入/粘贴）都汇流到这里，改一处即可覆盖。
- **快捷键（Ctrl+V 之类）必须挂在 Scene 上，不能挂在某个子容器上**。
  键盘焦点实际会停在 TabPane 等控件上，点一下子容器也未必抢得到焦点 ——
  挂在子节点上的过滤器收不到事件。做法：`root.sceneProperty()` 监听，
  入场景时装到 Scene、离场景时摘掉（抽成字段以便同一实例摘除）。
  再用 `e.getTarget() instanceof TextInputControl` 让位给原生文字粘贴。

### JavaFX 颜色换算（易错点）

**`javafx.scene.paint.Color.getRed()/getGreen()/getBlue()` 返回 0.0–1.0，不是 0–255。**
转成 `java.awt.Color` 必须 `Math.round(c.getRed() * 255)`。

漏乘 255 的症状极隐蔽：白色 `(1,1,1)` → `#010101`（看着全黑），
而黑色 `(0,0,0)` → `#000000` **恰好仍然正确** —— 黑对白错，
很容易被误判成渲染 / CSS / ImageView 问题，实际是图像数据本身就坏了。

### 无头截图验证（本项目惯用手法）
在 Java 里直接驱动真实控件（`button.fire()`）+ `scene.snapshot()`，不要依赖鼠标/窗口位置。
要点：
- 需要 `javafx-swing`（`SwingFXUtils`）
- `stage.show()` 后动画脉冲不可靠，用 `root.applyCss(); root.layout();` 强制布局
- **`applyCss() + layout()` 只保证布局，不保证绘制**。刚 `setImage()` 后立刻
  `snapshot()` 会截到未绘制的空区域（表现为整块黑/空）。
  修法：`Platform.runLater` **嵌套 3 层**再截图，给绘制脉冲留时间。
  **这是探针手法的问题，不是产品缺陷**——应用里渲染正常。
- **控制台中文会乱码**（GBK 终端解码 UTF-8）：把输出重定向到文件，
  再用 Python 按 utf-8 解码读回，**断言不要依赖控制台可见的中文**
- CSS 错误通过 `Thread.setDefaultUncaughtExceptionHandler` 捕获
- **临时探针类必须写 `package` 声明**：漏写会编译"成功"但类落到 default package
  （`target/verify-classes/Xxx.class`），运行时报找不到。用完即删。
- **`Application.launch()` 不返回**，直到应用退出。静态输出流要用
  `static {}` 初始化块，不能写在 `main()` 里（那时 `launch` 还没返回）。
- **定位控件不要用「第 N 个」**：同一界面可能有多个同类控件（两个 ImageView、
  两个 TextArea）。按**样式类**（如 `qr-result`、`image-preview-holder`）
  或持有引用定位，否则断言会测到错误的控件。

### 排查心法：隔离复现全过 = 没复现真实链路

本轮「生成图全黑」的排查耗时最久，教训值得固化：

- 隔离复现（自己造 `Color.BLACK/WHITE`、直接调 `QrOps.generate`）**全部通过**，
  因为它**绕过了真实调用链上的 `toAwt()`**。
- 正确做法：**在真实对象上取数据**。在真实模块里生成后，
  直接读 ImageView 那张图的像素，与「同参数干净对照」逐项比对 ——
  `black` 完全相同、`white=0`、`black` 部分全对而白色全错，
  立刻指向「只有白色这一路被改写」。
- 推论：**当简化场景全部通过时，说明简化掉了出问题的那一层。**
  停止继续造简化场景，转而给真实链路加观测。

## 源码结构速查

| 位置 | 职责 |
|---|---|
| `core/ToolModule` + `core/ToolRegistry` | 注册表，功能扩展点 |
| `ui/Sidebar` / `ui/ContentPane` / `ui/GridPanePage` / `ui/ToolCard` | 三层信息架构 |
| `ui/ContentPane` | 详情的返回栈（历史 Deque）+ 详情 LRU 缓存（12 项） |
| `ui/UiKit` | 视觉组件工厂（page/field/section/button…） |
| `ui/JsonCodeArea` | RichTextFX 语法高亮（VS Code 浅色风格） |
| `resources/css/app.css` | 设计系统令牌 + 组件样式 |
| `modules/http/BatchPanel` | HTTP 批处理（CSV 取参 + 占位符 + 并发） |
| `modules/http/CsvParser` | 零依赖 CSV 解析 + 编码/BOM 处理 |
| `modules/text/TextOps` | 纯函数式文本变换（去重/排序/统计…） |
| `modules/file/HashOps` | 流式哈希（64KB 缓冲），`FileHashModule` 跑后台线程 |
| `modules/cron/CronExpression` | cron 解析 + 下次执行时间（向后试探法） |
| `modules/image/ImageOps` | 缩放/裁剪/旋转/翻转/格式转换（JDK ImageIO） |
| `modules/qrcode/QrOps` | 二维码生成/解析/裁切/降级策略（纯逻辑，不依赖 JavaFX） |
| `modules/qrcode/QrModule` | 二维码双页签界面、剪贴板与框选交互 |
| `modules/http/SingleRequestPanel` | 单一请求；Body 页签叠放「高亮 + 纯文本」两视图 |
| `verify/src/main/java/...` | **开发期探针（不进产物）**，由根目录 `verify.cmd` 编译运行 |

## 开发期验证探针（改动后必跑）

**统一入口：根目录 `verify.cmd`**（会先重建 fat jar 再编译运行探针）：

```cmd
verify.cmd cron|image|qr|json|body|nav|qrpic|sel|smoke|preview
verify.cmd run <全类名>
verify.cmd compile
verify.cmd <任意> -nobuild     :: 跳过重建 jar，只改探针代码时用
```

> 探针是**编译链接到 fat jar** 上的，源码改了但 jar 没重建会报
> "cannot find symbol"（很难排查）—— 所以默认每次都重建。

| 探针 | 覆盖 |
|---|---|
| `CronTest` | 纯逻辑：闰年、月末、日月或关系、周日两种写法 |
| `ImageTest` | 纯逻辑：等比缩放、裁剪越界、旋转、透明转 JPEG 不变黑 |
| `QrTest` | 纯逻辑：四级纠错往返、裁切坐标、小码降级、噪声不崩、空文本拒绝 |
| `JsonProbe` | JSON 三种缩进、转义不破坏缩进、错误反馈 |
| `QrModuleProbe` | 界面级：框选坐标换算、Ctrl+V 真实 AWT 剪贴板链路、生成图纯白/无脏色、空输入作废过期图 |
| `SelectionE2EProbe` | 界面级：**四种宽高比**（横向/纵向/正方形/小于上限）各跑「载入→框选→裁切→识别」 |
| `HttpBodyProbe` | 响应体 JSON/非 JSON 分流、格式化往返、超大降级、清空复位 |
| `ContentPaneProbe` | 返回栈逐层回退、原地重进、LRU 重建 |
| `SmokeTest` | 全应用构建 + 截图 + CSS 错误捕获 |
| `DesignPreview` | 离屏渲染设计评审截图 → `build/ui-preview/` |

> 断言基线（2026-09-24 全部修复后）：
> **qrpic 40 + sel 12 + qr 29 + image 27 + cron 31 + json 14 + body 22 + nav 15 + smoke 21
> = 211 条全绿**，CSS 错误 **0**。
> 新增改动后跑一遍，任一探针计数下降就是回归。
>
> **新增断言后必须做「反向验证」**：临时关掉修复重跑，确认断言变红。
> 只跑「修复后全绿」无法区分「断言有效」与「断言恒真」——
> 本项目已两次因断言是摆设而漏过 bug（见当日记忆三十五节）。

截图落在 `target/smoke/`（`SmokeTest`）与 `build/ui-preview/`（`DesignPreview`）。

## 品牌标识

- 英文名 **ToolNest**，中文名 **工具巢**
- 窗口标题：`ToolNest 工具巢`
- 侧边栏：logo 字母 `T` + 品牌名 `ToolNest` + 副标题 `工具巢`
- 打包产物：`build\dist\ToolNest\ToolNest.exe`（fat jar 为 `toolnest.jar`）

## 用户偏好

- 界面中文，视觉要求「大气整洁」，本项目已有完整设计系统（见 `DESIGN.md`）
- 验证要**真实运行态**，不接受「编译通过」当作完成
- 交付要给出可直接双击的 exe
