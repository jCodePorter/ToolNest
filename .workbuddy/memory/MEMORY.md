# ToolNest 工具巢 · 项目长期记忆

`D:\Person\ToolNest`，包名 `com.bytefuture.toolnest`。
架构见 `ARCHITECTURE.md`，视觉规范见 `DESIGN.md`，功能清单与新增流程见 `README.md`。

## 环境（Git Bash + 中文 Windows）
- JDK 21 `C:\Program Files\Java\jdk-21`；JavaFX 21.0.12；Maven `D:\Dev\apache-maven-3.8.5`（仓库 `D:\Dev\apache-maven-repository`）
- 源码 UTF-8，但 **`.cmd`/`.bat` 必须纯 ASCII**（cmd.exe 按 GBK 解析），中文只放 `.java`/`.md`/`.css`
- 系统 `JAVA_HOME` 默认 JDK 1.8 → 跑 Maven 必须前置 `JAVA_HOME="C:/Program Files/Java/jdk-21"`，否则满屏「未结束的字符串文字」
- 调 `javac.exe`/`java.exe` 前加 `MSYS2_ARG_CONV_EXCL="*"`；classpath 用 `D:/...` 正斜杠
- javac 长选项双横杠；`@argfile` 内路径用正斜杠；Java 注释里**不能出现 `*/`**

## 项目约定
- 新增功能 = 新建 `ToolModule` + `app.ToolRegistry.bootstrap()` 注册一行
- 开发期代码放 `verify/src/main/java`（放 `src/main` 会进 fat jar）；纯净度必须为 **0**：
  `jar tf target/toolnest.jar | grep -cE "^com/bytefuture/toolnest/.*(Probe|Test|Preview)"`
- 包名/主类变更同步 `pom.xml` 的 `main.class` 与 `package.cmd`
- **验证必须跑运行态**：`buildContent()` 惰性调用，编译通过 ≠ 功能正常
- jpackage 不覆盖已存在目录 → 先 `mv build/dist build/dist_prev_<时间>`

## 探针与基线
- `verify.cmd <名> [-nobuild]`，清单见 `verify.cmd` 头部。**改源码必须重建 jar**，否则探针编译报 cannot find symbol
- **基线（2026-10-01 更新）**：18 个断言型探针，CSS 错误 0，jar 纯净度 0。
  分项：cron 43 · cronui 13 · nav 19 · json 14 · diff 50 · diffui 41 · smoke 22 · image 27 · qr 29 · body 22 · httpfix 35 · **httpsingle 72** · httplayout 22 · httpsect 22 · **qrlist 27** · sel 20 · tsui 21 · qrpic 40。
  **计数只许升不许降**（下降 = 有断言被跳过；1:1 替换某条可保持不变）
- **改探针断言的既有语义时，必须在断言名里写清新口径**（例：`qrlist` 由「卡片数 == 结果数」
  改为「卡片数 == min(结果数, 6)」）——光改代码，下一个人看到红条只会以为实现坏了
- **禁止并发跑 mvn/verify**：两个 `mvn clean` 会让另一个拿到半成品 jar（症状：maven-shade「Could not replace original artifact」、探针编译几百个「找不到符号」）
- `tsui` / `qrpic` 走系统剪贴板，**必须最后单独跑**（`image`/`qr` 先跑会占住剪贴板 → tsui 假红 4 条）；`qrpic` 可能 >100s，卡住时先 `Stop-Process` 清陈旧 java 进程
- 新增断言必须**反向验证**（破坏实现确认变红）——**恒真的断言比没有断言更危险**
- 恒真/恒假残留扫描：`grep -rn "true ||\|&& true\|if (false)\|if (true)"`
- 界面级断言落在**渲染结果**（`getBackground().getFills()` 色值），不落在节点存在
- 无头截图：`button.fire()` + `scene.snapshot()`；绘制延迟用 `Platform.runLater` 嵌套 3 层；控制台中文重定向文件按 UTF-8 读回（PowerShell `*>` 是 UTF-16）

## JavaFX 要点
- CSS 不支持 `var()`，用 looked-up colors；CSS 错误只在运行时上报，靠 SmokeTest 的 uncaught handler 捕获
- **`Color.getRed()` 返回 0.0–1.0**，转 `#rrggbb` 必须 ×255（漏乘：白→黑，极易误判成渲染缺陷）
- `-fx-pref-height` 必须与 `-fx-min-height` 成对；高度四档：标准 32 / 强调 36（HTTP 请求行）/ 页头 30 / 紧凑 28（chip）
- **纵向铺满要三层都松**：父容器 `VBox.setVgrow(ALWAYS)` + `child.setMaxHeight(MAX_VALUE)` + **child 内部可见子节点也要 maxHeight=MAX_VALUE**（Control 默认 max=USE_PREF_SIZE）—— 只设 Vgrow 会静默失效
- `Region` 的 min/pref 默认由内容决定 → 不显式写死就会随内容跳动
- `ScrollPane` + `fitToHeight`：`content.setMinHeight(USE_PREF_SIZE)` = 可滚动不可压缩；滚动条显式 `-fx-opacity: 1`（AtlantaFX 会叠不透明度）
- `Label` 居中要 `-fx-alignment`（整块）+ `-fx-text-alignment`（折行间）两条；空态/居中块必须 `setMaxWidth(MAX_VALUE)`
- HBox 等宽：`setMinWidth(0)` + **显式 `setPrefWidth(同一值)`**；`setHgrow` 必须配 `setMaxWidth(MAX_VALUE)`
- `ComboBox` 只在用户交互时派发 ActionEvent，`select()` 不触发 → 状态同步抽方法，两条路径都调
- 快捷键**必须挂 Scene**；`:drag-over` 非内置伪类，要在 DragOver/DragExited/DragDropped 三处切换
- **`TabPane` 的首选高取「各页签的最大值」**：同一 TabPane 里有一个页签自然高偏大，整页就被顺带撑高、把别人顶出视口。自带内层滚动的页签要把首选高压平（`setPrefHeight(0)`）表达「填满父容器给的高度，装不下自己滚」
- **`.tab-pane > .tab-content-area` 默认带 `-fx-padding: 14 0 0 0`**：算 chrome 必须算进去（`.module-body` 上留白 16 + 页签头区 48 + 这 14 = 78），漏掉就多做 14px 预算。内层数据页签的总高（312 / 132）本来就是「页签头 42 + 14 + 正文」一起算的，别再叠加一次
- **`Region.getInsets()` 已经包含 border**：CSS 写 `padding:14` + `border-width:1`，`getInsets()` 实测给 **15/15**（不是 14）。要算内容区宽度就 `getWidth() − insets.left − insets.right`，**不要再减一次 border**（重复减 → 少 2px，肉眼看不出来但断言会红）。边框宽度还会被 CSS 改（`.drop-zone` 是 1.5）→ 一律读实测，别写常数
- **凡「按常量算出的显示尺寸」都要用运行时实宽校一次**：`PREVIEW_MAX=460` 是**愿望值不是保证值** —— 容器只有 456 宽时，按 460 算出的尺寸与 JavaFX 实际渲染的（425.33）分叉，框选/裁切坐标会整体错位。正确写法：`cap = min(愿望值, 容器内容区实宽)`，并**在 widthProperty 上挂监听重算**（首次布局前实宽为 0，只算一次必错）
- **改一个静态算法的口径时，要 grep 出它所有公开包装**（`*Public` / `*ForTest` 后缀）一起改：漏掉一个旧重载，探针就会按老口径算期望值 → **假红**（`displaySizePublic` 漏了第三参，`sel` 白红 5 条）
- **「结构断言全绿」≠「长得对」**：节点都在、数量对、图标在，但按钮可能被压成「选…」或飘到页面中部。断言建在结构上，布局缺陷它抓不到 —— **每个界面改动都要肉眼过一遍截图**（`target/smoke/*.png`、`target/*-probe/*.png`）
- **同排两个兄弟元素的高度**：`HBox` 会让子节点都撑到行高。要把右边那组压在「页签头那一档」，必须显式 `-fx-min/pref/max-height` 三条全写同值；只靠 alignment 会被拉满
- **同排宽度分配**：先给右边固定组 `setMinWidth(Region.USE_PREF_SIZE)`，再让左边弹性组 `Hgrow=ALWAYS`。反了的话弹性组会**先抢光宽度**，固定组被省略号吃掉

## 降级态（「此项当前不生效」）
一律走 `UiKit.setDegraded(target, note, degraded)` + `UiKit.degradeNote(text)`，**不要自己写 `setDisable`**。
后果：它仅压被禁的那一个子节点 → 标签保持全黑、数值淡到 17%，看着像控件坏了。
说明文字**内联挂同一行**（不另起一行），竖向预算卡死。`Spinner` 的 `editor.setEditable()` 被 SpinnerSkin 绑定，赋值前先判 `editableProperty().isBound()`。

## 专项
- TableView：列固定 → `CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN`；列随数据 → UNCONSTRAINED + 每列 pref/min 宽
- 读用户文件**必须剥 UTF-8 BOM**；解析与显示走同一条读取路径
- 图片：唯一 `displaySize()`；`Image.getWidth()` 是原图尺寸；JPEG 写出必须合白底；不支持 WebP
- Gson 缩进：`JsonWriter.setIndent` + `Streams.write`，不要后处理替换
- 二维码：ZXing 只引 `core`（自写 LuminanceSource）；入图先 `flatten(img, WHITE)`；多码用 `QRCodeMultiReader`；框选裁切外留 1/8 边距
- 文本对比（**两栏**，中列已删）：行级 LCS → 块内「相似度+单调」最优配对（**绝不能按位置配对**）→ 行内 token LCS；每侧「编辑态 TextArea / 对比态 行 VBox」切 visible+managed，**两侧都要初始化**
- 页面导航：历史栈存页面 key 不存 Node；返回不入栈；目标==当前不记史；详情 LRU(12)；详情页统一包 `.detail-scroll`

## 设计稿交付（`design/*.html`）
通用规范见 skill `ui-redesign-mockup`。本项目特有的：
- 每张 `.win` 按真实窗口 **1266×763 的 1:1**；窄窗另出 820×560；**面积预算表纵向加总严格 = 视口高**
- 行数口径 `(表体高 − 表头) ÷ 34`（详情 `(分栏高 − 30) ÷ 20`）——**漏减表头会把 5 行写成 6 行**
- **出处表不能写设计稿自身的 sha256/字节数**（自指）→ 以 `build/<name>-review/verify-report.txt` §9 为准
- **正文里也不能出现 `✔`**（卫生检查判成 Unicode 装饰符号）→ 用文字「通过」
- 验证器 `verify-mockup.js` / `.cmd` 在**项目根**（`build/`+`target/` 在 `.gitignore` 里）：
  `node verify-mockup.js [设计稿] [输出目录]`；**Git Bash 下别走 `cmd //c` 包装器（会卡死）**
- 它两条断言各抓过一个真缺陷，务必保留：**① 标尺标签数字 == 该行实测高度**（尺子刻错比画布画错更危险）；**② 键值表列宽合计 == 936**（列和超了会被 `overflow:hidden` 悄悄裁掉）
- `.kvrow` 浏览器实测 33.2px 而非 34（`.kv` 的 1px 边框 + 1px 内边距被 flex 平摊，真实 JavaFX Region 同理）—— 口径问题，设计值仍是 34
- 对像素前先看截图 mtime：可能是实施中途的构建，别把「我后来已经改过」当成缺陷

## HTTP 单一请求页（2026-09-30 已实施，spec v2）
- 「类型 → 编辑器」是真换节点（`editorHolder` 里 5 个候选：空态 / 文本区 / 键值表 / Form-data 表 / 单文件选择器），切类型只切 visible+managed，各类型内容各自持有不丢
- 分段控件 7 项（`ToggleGroup` + `.http-type-seg`）；动作槽只放当前类型可用的动作
- 新增 `FORM_DATA`（`FormDataTable` 逐行文本/文件 + `MultipartBody` 组包）与 `BINARY`，`Method` 补 `PATCH`；`batchTypes()` 不含 FORM_DATA/BINARY（只有路径没有值）
- 守卫两条：Content-Type 冲突（只提示不改用户的头）／`inferBodyType` 推断要说出来；GET+body 只提示不阻断
- 折叠：按 Send 一瞬间折成 46px 摘要条（展开编辑 + 重发）；配置态 763 正好铺满
- 实测：chrome **134 → 64**，请求页签 248 → **312**（键值表 5.0 行 / 文本区 12 行），响应区 187 → **432**（24 行）
- 验收探针 `verify.cmd httpsingle`（72 条），含真实 socket 断言 Form 百分号编码与 multipart 字节

## 待实施的设计
- `design/http-batch-redesign.html` + `-spec.md`：批处理页重排版。三段式与单行执行条已落地；Q1–Q5 收尾未做
- **ADR-006 巨型类拆分**：`SingleRequestPanel` 本轮 685 → **1342 行**、钩子 11 → 36 处，优先级已上升
- `design/qrcode-decode-redesign.html` + `-spec.md`：**已实施完毕**（2026-10-01），设计稿保留作档

## 二维码解析页（2026-10-01 改版后）
- 页签行右端**动作行**（`decodeActions`，4 个按钮）：选择图片… / 粘贴剪贴板 (Ctrl+V) / 清除框选 /
  1px 分隔线（`.qr-act-sep`）/ **移除图片**（`Icons.trash`，静默按钮）
- **动作行不是 `TabPane` 的子节点**（Tab 头由 skin 生成，塞不进外来节点）→ 只能做页签行的兄弟，
  放进 `HBox tabRow`；因此**必须**监听 `selectedItemProperty` 手工跟随显隐
  （`visible` 与 `managed` 要一起切，否则留下空白带）。这是本页最易漏的运行态缺陷
- 结果卡：卡内自带 `复制` + `打开`（各 28px 紧凑档）；**底部按钮行已删除**
- **`MAX_RESULT_CARDS = 6`**：`renderDecodeResults` 只画 `min(结果数, 6)` 张整卡，超出靠滚动，
  **不画半截卡**。数据不截断（`decodeResults` 仍有全部，可复制）；标题行计数徽标显总数 +
  只读汇总「共 N 个二维码 · 显示 N 个」
- 「打开」**常驻不禁用**：非网址时点了给一句提示（`openInBrowserIn`），不靠预先猜测禁用按钮

## 品牌
ToolNest / 工具巢；窗口标题 `ToolNest 工具巢`；产物 `build\dist\ToolNest\ToolNest.exe`；界面中文，视觉「大气整洁」。
