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
- **基线（2026-10-03 更新）**：18 个断言型探针，CSS 错误 0，jar 纯净度 0。
  分项：cron 43 · cronui 13 · nav 19 · json 14 · diff 50 · diffui 41 · smoke 22 · image 27 · qr 29 · body 22 · httpfix 35 · **httpsingle 111** · **httplayout 42**· httpsect 22 · **qrlist 27** · sel 20 · tsui 21 · qrpic 40。
  **计数只许升不许降**（下降 = 有断言被跳过；1:1 替换某条可保持不变）
- `httplayout` 那条跑了很久的既存红「配置态结果表 ≥ 6 行」已在 2026-10-03 段①
  改版后**自动转绿**（表高 230.67 → 278.67，5 行 → 8 行）
- **改探针断言的既有语义时，必须在断言名里写清新口径**（例：`qrlist` 由「卡片数 == 结果数」
  改为「卡片数 == min(结果数, 6)」）——光改代码，下一个人看到红条只会以为实现坏了
- **禁止并发跑 mvn/verify**：两个 `mvn clean` 会让另一个拿到半成品 jar（症状：maven-shade「Could not replace original artifact」、探针编译几百个「找不到符号」）
- `tsui` / `qrpic` 走系统剪贴板，**必须最后单独跑**（`image`/`qr` 先跑会占住剪贴板 → tsui 假红 4 条）；`qrpic` 可能 >100s，卡住时先 `Stop-Process` 清陈旧 java 进程
- 新增断言必须**反向验证**（破坏实现确认变红）——**恒真的断言比没有断言更危险**
- 恒真/恒假残留扫描：`grep -rn "true ||\|&& true\|if (false)\|if (true)"`
- **无头环境里 `isFocused()` 是恒假的**（页签不可见 → `requestFocus()` 拿不到焦点，
  `scene.getFocusOwner()` 是 null）。要测「有没有失焦」就断言**节点身份**
  （`cell == table.getXxxCell(...)`，即补行后那格还是不是同一个对象），
  别写 `isFocused()` —— 恒假断言比没有断言更危险
- **探针跑在 FX 线程上，不能用 `CountDownLatch` 等 `Platform.runLater` 排空**（自己锁死自己，
  实测 10s 超时）。要等异步逻辑就加 `flushXxxForTest()` 同步执行同一段代码；
  这对「焦点/节点存活」反而是更严的考验（同步变更都扛得住，异步的更没问题）
- 界面级断言落在**渲染结果**（`getBackground().getFills()` 色值），不落在节点存在
- 无头截图：`button.fire()` + `scene.snapshot()`；绘制延迟用 `Platform.runLater` 嵌套 3 层；控制台中文重定向文件按 UTF-8 读回（PowerShell `*>` 是 UTF-16）
- **窗口按整数建（763），实际场景高是 763.33**（Windows 缩放取整）→ 视口比设计口径矮
  0.33px，「不出现滚动条」这类断言要留 **2px** 容差，0.5 会恒红
- **拍某个页签的表前必须先选中它**（`tabs.getSelectionModel().select(n)`），
  否则拍到的是当前页签 —— 且两张图会大小完全一样，容易误以为「拍到了」

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
- **表格单元格一律用「常驻 TextField」（graphic）而不是 `TextFieldTableCell`**：
  后者是「按下编辑 → 提交」模型，**编辑期间行模型还是旧值（通常空串）**。由此串出三个缺陷：
  ① 判断「末行是否空行」读到未提交的空模型 → **永远不补行**；
  ② 提交瞬间补行 → `ObservableList` 增删让 `TableView` 重建单元格 → **编辑器连同未提交的文字
  一起被销毁**（失焦 + 内容消失）；③ 逐条 `add` 批量填充时每次都触发补行 → **一行变五行**。
  换成常驻输入框（打字即写通模型，没有「提交」这一步）三条同时消失。
  配套：`table.setEditable(false)`（留着 editable 会让点击额外弹一层编辑器与常驻框抢焦点）；
  **补行必须 `Platform.runLater` 异步**（按键事件没走完就改列表 = 同一次布局里重建单元格）；
  **批量替换用 `bulkEdit` 静音 + 一次 `setAll`**（在 `ObservableList` 自己的回调里再改它是重入）；
  不变式「末尾恰好一行空白行」；`updateItem` 里**复用同一个 TextField**，别每次 `new`
  （否则重布局就把用户正在输入的框换掉）
- **`TableView.getRow(int)` / `TableRow.getCell(int)` 不是公开 API**（编译不过）。
  只能 `table.lookupAll(".table-row-cell")` 顺场景图数行、再在行内数 cell
- **`getTableRow()` 是 `TableCell` 的方法**，外层类调不到 → 每个 cell 内部自己写 `currentRow()`
- **「补一行空行」与「新增一行」是两个语义**：前者是「保证末行是空的」（末行本来就空 →
  什么都不做），后者是「多给我一行」。写成同一个方法 → 空表上点「+ 新增参数」毫无反应
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

## HTTP 单一请求页（2026-09-30 已实施，spec v2）
- 「类型 → 编辑器」是真换节点（`editorHolder` 里 4 个候选：空态 / 文本区 / **Form·Form-data 共用表** / 单文件选择器），切类型只切 visible+managed，各类型内容各自持有不丢
- 分段控件 7 项（`ToggleGroup` + `.http-type-seg`）；动作槽只放当前类型可用的动作
- 新增 `FORM_DATA`（`FormDataTable` 逐行文本/文件 + `MultipartBody` 组包）与 `BINARY`，`Method` 补 `PATCH`；`batchTypes()` 不含 FORM_DATA/BINARY（只有路径没有值）
- 守卫两条：Content-Type 冲突（只提示不改用户的头）／`inferBodyType` 推断要说出来；GET+body 只提示不阻断
- 折叠：按 Send 一瞬间折成 46px 摘要条（展开编辑 + 重发）；配置态 763 正好铺满
- 实测：chrome **134 → 64**，请求页签 248 → **312**（键值表 5.0 行 / 文本区 12 行），响应区 187 → **432**（24 行）
- 验收探针 `verify.cmd httpsingle`（**111 条**），含真实 socket 断言 Form 百分号编码与 multipart 字节

## Form 与 Form-data 已合并为同一张表（2026-10-03）
按 Postman：`form-data` 与 `x-www-form-urlencoded` 是**两个并列类型选项、共用一张表**。
- `editorFor(FORM)` 与 `editorFor(FORM_DATA)` **都返回 `formDataTable`**；`formTable` 已删除
- `setFileRowsAllowed(boolean)`：Form 隐藏「类型」列 + 把已有文件段落回文本段
  （urlencoded 传不了文件，但**保留 `path`**，切回 Form-data 还能看到原来选的文件）
- 内容始终是同一份，来回切不丢；`currentSpec()` 里 Form 收 `formEntries()`（键值对）、
  Form-data 收 `fields()`（逐段模型），**两种模型不混用**
- 动作槽：`FORM`/`FORM_DATA` 都是「+ 新增字段 / 清空」；Params/Headers 工具行
  新增「+ 新增参数」「+ 新增请求头」（显式入口，不依赖自动补行那种被动机制）
- 截图 `build/http-single-review/shots/single-form-merged.png`（无「类型」列）·
  `single-formdata-merged.png`（有「类型」列 + 文件行）

## 批处理段①「请求模板」= 请求行 + 三页签 + 工具行（2026-10-03 已实施）
对齐单一请求页的控件词汇，去掉 `field()` 左标签 + `ComboBox` 下拉 + 收起式 `TitledPane`。
- **请求行**：方法 118 + URL + 占位符说明**同行**（说明单起一行整页会超 653 预算）
- **三页签** `Params` / `Headers` / `Body`（与单一请求页同名同序），各带工具行
- **Body 工具行在页签内部**：分段控件（5 项，`batchTypes()`）+ Content-Type + 动作槽
- 段① **376**、内层页签 **277**（表 3 行 × 行高 40 + 表头 40 + 滚动条 14）
- **配置态整页 774.67 > 654，外层出现滚动条** —— 用户 2026-10-03 明确要求
  「整体都可以增加高度，段3也向下挪」。段③ 守 340 保底，段① 收起后段③ 回到 556（12 行）
- **`.batch-tabs` 专用类，不能复用 `.http-req-tabs`**（后者钉死 312，套过来段① 涨到 456）
- **277 = 轨 43.33（实测）+ content-area padding 14 + Params/Headers 内容 220**
  （工具行 38 + 间距 8 + 表 174）。**按最高的那个页签定** —— 三个页签共享一个
  TabPane 高度，按 Body（编辑器 96）定的话，Params/Headers 的表只拿到 50（表头 40 + 10），
  **切过去只看得见表头那一条**（用户报「间距太小，无法输入内容」）
- **段① 表行高 40（`.batch-kv` 作用域，全站其它表格仍 34）**：34 在只有两三行可见的矮表里
  显得挤、行分隔读不出来。行数换算随之变：(表高 − 40) ÷ 40
- 表高 174 里那 **14 是特意留给滚动条的**：行高变大后 3 行就填满，
  不留这一条用户看不到「下面还有」

### 最重要的一条：探针量高度量不到「被父容器裁掉」
TabPane 装不下时裁掉内容，而 `bodyArea.getHeight()` 仍报 50px（节点在、尺寸对、
visible=true）—— **32 条断言全绿，截图上编辑器只剩一条边**。
必须断「编辑器底边 vs **TabPane 的 content-area** 底边」；
**跟 `bodyTabContent` 比是恒真的**（它自己也会被压扁）—— 恒真断言比没有断言更危险。
反向验证：TabPane 154→110 时该断言转红。

### 断言里踩过的两个「恒真/太松」坑
- **占位符测试用错列名**：样例 CSV 只有 `orderId/type/channel`，
  我测 `{city}` → 找不到列、原样保留，误判成「替换没生效」。
  **测替换必须用 CSV 里真实存在的列**，且要断具体值（`235027703726`）不是断「不含 {」。
- **URL 为空时「url 包含 trace=」恒真**：空 URL 拼参数 = `?trace=...`，
  只验「参数拼上了」、没验「原地址还在」→ 断言会通过而实现是坏的。
  必须**同时**断 `startsWith(原地址)`。写断言前先确认前提字段非空。

### 断言里作废的两条（口径随取舍变化，不是实现坏了）
- 「整页正好铺满 653」→ 改为「整页允许超出视口（外层滚动兜底）」+「段③ 守 340 保底」
- 「段① 变矮后余量归段③」→ 段① 现在是**加高**，改为「段③ 始终守住 340」

### 其它实测坑
- `ToggleGroup` **不是 `Iterable`**，遍历不了选中项 → 自己留一份 `List<ToggleButton>`
- `TextArea` **没有 `setPlaceholder`**（那 API 在 `TextField` 上）
- `Streams.write` 抛 `IOException`，`formatJsonBody` 要 catch（不能只 catch RuntimeException）
- 工具行的文案要短：写长了会把 5 个分段项挤开
- **TableView 滚动条的 style class 只有 `scroll-bar`** —— 竖/横是**伪类**
  `:vertical`/`:horizontal`，`getStyleClass().contains("vertical")` **永远 false**。
  判方向要靠 `lookupAll(".scroll-bar")` + 几何（宽 < 20 判竖向）

## 批处理 Body：Form 也用键值表（2026-10-03）
批处理页原先五种类型**共用一个文本区**，选 Form 只能手填 `a=1&b=2`；
单一请求页 Form 是一张 `FormDataTable`。用户报「格式怎么与单一请求时不一样」。
- `bodyTabContent`（StackPane）里叠 `bodyArea` + `formDataTable`，
  `showBodyEditor(type)` 切 visible+managed → **各自内容不丢**（切走再切回还在）
- Form 模式 `setFileRowsAllowed(false)` → 隐藏「类型」列
- 内容区高度跟着换：文本区 `BODY_TEXT_H=50`、Form 表 `BATCH_FORM_H=174`
- **Form 的 body 文本必须置空**（`spec.body=""`），否则键值与文本会**重复发送**
- 键值按 CSV 行替换后进 `spec.formFields` → 引擎 `encodeForm` 做百分号编码
- `applyBodyDegrade` 要**两个编辑器都降级**，否则「选 Form 但表单不降级」

### 顺带修好：Params 的值从来没进过请求
那一页写着「这些键值会以 query 参数拼到 URL 之后」，但 `prepareResultRows`
只读了 url / headers / body 三处 —— 说明是假的。现在 `appendQuery` 真的拼
（URL 已带 `?` 用 `&`，否则 `?`；键值都做百分号编码）。

## 请求头「快捷按钮」与值加工（2026-10-03）
Headers 工具行有 `+ JSON` / `+ Token` / `+ 新增请求头` / `清空`。
- `+ Token` 落一行 `Authorization`，**值留空**并把焦点送进 VALUE 格（不预填 `Bearer `：
  用户会看到半截的值还得自己删，复制出去也带占位前缀）
- `KeyValueTable.addEntry(key, value, transform)`：同名 key 已有 → 复用那一行；
  否则**插在尾部空行之前**（不是 `rows.set(size-1,…)` 就地占用 —— 那样会把空行吃掉，
  表尾没地方再录）。反复点不堆行
- **Bearer 判据不能只查「含不含 Bearer」**：`Basic dXNlcjpwYXNz` 不含 Bearer，
  字面实现会把它改成 `Bearer Basic …`，**把能用的头改坏**。正确两步：
  ① **整串无空白 → 就是裸 token**（JWT 形如 `aaa.bbb.ccc`，全是 scheme 合法字符，
  只按「首段是否合法」判会**漏掉前缀**）② 有空白 → 首段是否合法 scheme（RFC 7235）
- **加工必须按 key 分派**（`(key,value)->value`，只对 Authorization 生效）：
  无差别套用会把 `Content-Type: application/json` 改成 `Bearer application/json`
- 加工跑在**两处**：失焦（给即时反馈，**不能放在按键时** —— 边打边改会把刚敲的字符顶走）
  + 取值 `entries()`（兜住「粘完直接点 Send」）。**取值路径不写回模型** ——
  只影响发出去的值，表格里显示的仍是用户敲的原文

## 设计稿交付（`design/*.html`）
通用规范见 skill `ui-redesign-mockup`。本项目特有的：
- 每张 `.win` 按真实窗口 **1266×763 的 1:1**；窄窗另出 820×560；**面积预算表纵向加总严格 = 视口高**
- 行数口径 `(表体高 − 表头) ÷ 34`（详情 `(分栏高 − 30) ÷ 20`）——**漏减表头会把 5 行写成 6 行**
- **出处表不能写设计稿自身的 sha256/字节数**（自指）→ 以 `build/<name>-review/verify-report.txt` §9 为准
- **正文里也不能出现 `✔`**（卫生检查判成 Unicode 装饰符号）→ 用文字「通过」
- 验证器 `verify-mockup.js` / `.cmd` 在**项目根**（`build/`+`target/` 在 `.gitignore` 里）：
  `node verify-mockup.js [设计稿] [输出目录]`；**Git Bash 下别走 `cmd //c` 包装器（会卡死）**
- 它两条断言各抓过一个真缺陷，务必保留：**① 标尺标签数字 == 该行实测高度**（尺子刻错比画布画错更危险）；
  **② 键值表列宽合计 == 936**（列和超了会被 `overflow:hidden` 悄悄裁掉）
- `.kvrow` 浏览器实测 33.2px 而非 34（`.kv` 的 1px 边框 + 1px 内边距被 flex 平摊，真实 JavaFX Region 同理）—— 口径问题，设计值仍是 34
- 对像素前先看截图 mtime：可能是实施中途的构建，别把「我后来已经改过」当成缺陷

## 待实施的设计
- `design/http-batch-redesign.html` + `-spec.md`：批处理页重排版。三段式与单行执行条已落地；Q1–Q5 收尾未做
- `design/http-batch-template-align.html` + `-spec.md`（2026-10-03 出稿，**待审核**）：
  批处理段①「请求模板」对齐单一请求页样式（请求行 + Params/Headers/Body 页签 + 分段控件）。
  校验器 `verify-mockup-template.js`（静态 19 项）+ `verify-mockup-render.js`（Chrome 实测 11 项）。
  **浏览器实测抓到「预算表把正文当卡片」** —— 段① 真实 244px 不是 232，整页 642 ≤ 653 才成立
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
