# ToolNest 架构说明

> 本文记录分层设计、依赖规则与关键决策的**理由**。
> 视觉设计与组件规范见 `DESIGN.md`；功能清单与「新增一个功能」的操作步骤见 `README.md`。

---

## 一、这版分层要解决的问题

改造前的核心问题是 **`ToolModule` 一个接口同时承担了三个角色** —— 元数据契约、
界面工厂、业务入口 —— 所有功能模块都被它带着往下混。

| 问题 | 证据 | 现状 |
|---|---|---|
| 抽象被 UI 技术绑架 | `core/ToolModule` `import javafx.scene.Node` | 已解 |
| 依赖倒置违规 | `core/ToolRegistry` import 了 10 个具体模块，最内层依赖最外层 | 已解 |
| 公共组件层缺位 | `KeyValueTable`（纯 UI 控件）住在 `features/http` 内 | 已解 |
| 分包维度是「技术」不是「业务」 | `modules/file/` 里界面类（`FileHashModule`）与数据类（`HashOps`）并列 | 已解 |
| 巨型混合类 | `QrModule` 1502 行 / `BatchPanel` 1108 行 / `ImageModule` 710 行 | **仍在** |
| 测试钩子污染生产码 | 三个界面类里 62 处 `*ForTest`（59 个方法名），探针侧 152 处调用 | **仍在** |
| 数据访问与纯算法同层 | `HashOps`/`ImageOps`/`HttpEngine` 与 `TextOps`/`CronExpression` 平铺 | 部分 |

表中行数与钩子数为**当次实测**；其余为改造前状态。未解的集中在 ADR-006。

---

## 二、分层结构

```
com.bytefuture.toolnest
├── core/           领域契约（零 JavaFX）
│   ├── ToolDescriptor        工具元数据：id / 名称 / 分类 / 说明 / 图标名 / 关键词
│   └── ModuleCategory        功能分类枚举
│
├── app/            装配层（组合根）—— 唯一知道所有具体实现的地方
│   ├── ToolRegistry          注册表 + bootstrap()
│   └── ToolNestApp           JavaFX 应用主类（Launcher 在根包）
│
├── ui/
│   ├── spi/         扩展点：ToolView（`Node buildContent()`）+ ToolModule
│   ├── shell/       框架界面，不可复用
│   │                MainWindow / Sidebar / ContentPane / GridPanePage / ToolCard
│   └── components/  公共组件，任何 feature 可复用
│                    UiKit / Icons / JsonCodeArea / KeyValueTable / CollapsibleSection
│
└── features/        业务功能（11 个包，按能力划分）
    ├── timestamp/   TimestampModule
    ├── cron/        CronModule + CronExpression
    ├── json/        JsonFormatModule
    ├── encode/      EncodeModule
    ├── uuid/        UuidModule
    ├── text/        TextModule + TextOps
    ├── diff/        TextDiffModule + DiffView + DiffOps
    ├── filehash/    FileHashModule + HashOps
    ├── image/       ImageModule + ImageOps
    ├── qrcode/      QrModule + QrOps
    └── http/        HttpToolModule + SingleRequestPanel + BatchPanel
                     + FormDataTable（multipart 字段表）+ HttpEngine + MultipartBody
                     + HttpRequestSpec + CsvParser + TemplateResolver + TextCodec
```

### 依赖方向（单向，不可反向）

实际依赖边（`grep 'import com.bytefuture.toolnest' <包>` 实测）：

| 包 | 依赖 |
|---|---|
| `core` | 无 |
| `ui/spi` | `core` |
| `ui/components` | `core`、`ui/spi` |
| `features/*` | `core`、`ui/components`、`ui/spi` |
| `ui/shell` | `core`、`ui/components`、`ui/spi`、**`app`** ←例外 |
| `app` | 以上全部 + 所有具体 feature 模块 |

规则：

1. **`core` 不依赖任何其他包**，尤其不得出现 `javafx.*`。
   判断标准：把 `core` 单独拿来，能否在没有 JavaFX 的环境里编译。
2. **`features/*` 不得 import `ui.shell`**，组件从 `ui.components` 取。
   反向引用意味着一个业务功能开始依赖整个应用外壳，它就再也无法独立测试。
3. **`app` 是唯一允许 import 全部具体模块的包** —— 这是组合根本身的定义。
4. `ui.shell` 可依赖 `ui.components` 与 `core`。

> ⚠️ **已知例外**：`ContentPane` / `Sidebar` / `MainWindow` 直接读
> `app.ToolRegistry` 的静态方法（`grouped()` / `byCategory()` / `byId()` /
> `usedCategories()`），于是 `app → ui.shell → app` 形成了一个**静态环**。
> 它不炸是因为注册表是纯静态状态，但方向是反的。
> 彻底修法是把注册表抽到中性包，或让 `MainWindow` 以构造参数接收它；
> 两者都会触及全部界面类，故留待与 ADR-006 一并处理。

---

## 三、feature 包内部分层

每个 feature 用**统一的类名后缀**表达三层职责：

| 层 | 命名 | 约束 |
|---|---|---|
| 界面 | `XxxModule` | 实现 `ui.spi.ToolModule`；元数据 + 界面组装 + 事件接线；元素一律取自 `ui.components` |
| 业务 | `XxxOps` / `XxxExpression` | **零 JavaFX 依赖**，可脱离界面单独测试 |
| 数据 | `XxxIo` | 文件 / 网络 / 剪贴板访问 |

**不建 `ui/` `service/` `data/` 三层子包**：层与层之间需要互相访问（界面要调业务、
业务要调数据），强行分包只会迫使可见性被放宽，反而削弱边界。
包表达**业务边界**，类名表达**层次**。约定写在每个包的 `package-info.java` 里（共 18 个）。

**不设全局 `data` 包**：11 个功能里多数没有 IO，数据访问普遍只有几十行，
建全局 `data` 包的结果是「两个类的空壳 + 一堆空目录」，那是假分层。
数据访问就近放在需要它的 feature 内，厚到值得单列才起 `XxxIo`（目前只有
`image`、`http` 两条链路足够厚）。

---

## 四、关键决策记录（ADR）

| # | 决策 | 后果 |
|---|---|---|
| **001** 剥离 JavaFX | 拆成 `core.ToolDescriptor`（纯元数据，零 JavaFX）+ `ui.spi.ToolView`（界面工厂，含 `Node`），`ui.spi.ToolModule` 继承两者，是模块实现的唯一接口 | `core` 可脱离 JavaFX 编译与测试；「工具是什么」与「工具长什么样」可独立演进。契约变三个，但入口仍只有一个 |
| **002** 组合根移入 `app` | `ToolRegistry` 从 `core` 整体移到 `app`，语义不变，只修正依赖方向。`Launcher` 留在根包，`pom.xml` / `package.cmd` 无需改动 | `core` 恢复纯净，「新增功能只改一个地方」的特性保留。代价是注册表不再能被 `core` 复用 —— 本来也不该 |
| **003** `modules/` → `features/` | 每个业务能力一个包，按能力命名（`modules/file` → `features/filehash`，而非笼统的「文件」组） | 包名直接对应业务能力。触及 10 个探针文件 + `verify.cmd` 类名映射表，须同步 |
| **004** 不设全局数据层 | 数据访问就近放置；薄到不值得单列时并入 `XxxOps` | 避免假分层与空壳目录。代价是「数据访问」没有统一入口，靠 `XxxIo` 命名约定识别 |
| **005** `ui/` 三分 | 拆为 `shell`（框架，不可复用）/ `components`（公共组件）/ `spi`（扩展点）；`KeyValueTable` 迁入 `components` | 「能复用什么」有了明确答案并由包边界强制，新组件有唯一去处。`features` 不得引用 `ui.shell` 这条需人工守 |
| **006** 巨型类拆分 + 钩子清理 | 见下节 | **未完成** |

### ADR-006：巨型界面类拆分与测试钩子清理（待专项）

**为什么还没做**：探针对这些钩子的依赖是 **3 个探针文件、152 处调用**，
且钩子暴露的是内部状态（框选坐标、预览偏移）。清理它们等于重写探针的驱动方式
（从「调测试钩子」改为「构造真实事件 + 断言渲染结果」）。
把「分层搬迁」与「探针重写」混在一起，一旦基线变红将无法判断是哪一件造成的。

**已识别的切口**：

| 类 | 现状 | 拆分目标 |
|---|---|---|
| `QrModule` | 1502 行 / 33 处钩子 | `QrModule`（入口+装配）+ `QrDecodeView` + `QrGenerateView` + `QrIo`（剪贴板/文件） |
| `ImageModule` | 710 行 | `ImageModule`（入口）+ 界面 + 编排 + `ImageIo`（编解码） |
| `ImageOps` | 428 行 | 变换算法留下；`read`/`write` + JPEG 质量控制移到 `ImageIo` |
| `BatchPanel` | 1128 行 / 18 处钩子 | 按三段流程（请求模板 / CSV / 执行与结果）拆 |
| `SingleRequestPanel` | 1342 行 / 36 处钩子（改造后） | **本轮已把「请求体编辑器」抽成独立节点与映射（见下）**，但装配逻辑仍全在一个类里 —— 下一步按「请求行 / 页签正文 / 响应区 / 折叠条」四块拆 |

`ImageModule` 的拆分是**连锁**的：编排逻辑现在住在界面类里，要抽数据层必须先立业务编排层。

---

## 五、未完成

- ⏳ **ADR-006**：巨型界面类拆分 + `*ForTest` 钩子清理（`SingleRequestPanel` 本轮从
  685 → 1342 行，钩子 11 → 36 处，该项的优先级上升）
- ⏳ 数据层抽取：`ImageIo` / `QrIo`
- ✅ HTTP 单一请求页重做（2026-09-30 已实施）：`design/http-single-redesign-spec.md`。
  `features/http` 内新增 `FormDataTable`（multipart 字段表）与 `MultipartBody`（组包 + 人读描述）；
  `HttpRequestSpec.Method` 补 `PATCH`，`BodyType` 补 `FORM_DATA` / `BINARY`（后两者的
  `batchTypes()` 不含自身 —— 只有路径没有值，占位符语义待单独设计）；
  `HttpEngine` 增加 form 百分号编码、multipart 组包、binary 文件流
- ⏳ HTTP 批处理页重排版：`design/http-batch-redesign-spec.md`（三段式与单行执行条已落地，Q1–Q5 收尾未做）

---

## 六、新增一个功能

见 `README.md`「新增一个功能（核心流程）」。一句话版本：

1. `features/<能力>/` 下新建 `XxxModule implements ui.spi.ToolModule`
2. 界面元素全部取自 `ui.components`；纯逻辑抽到零 JavaFX 的 `XxxOps`
3. 文件/网络/剪贴板访问薄则写在 `XxxOps` 里，厚则单列 `XxxIo`
4. 在 `app.ToolRegistry.bootstrap()` 加一行 `register(new XxxModule());`

不用改任何界面代码 —— 侧边栏、宫格页、详情页都会自动生成。

---

## 七、验证

```cmd
verify.cmd <探针名> [-nobuild]     rem 编译探针 + 运行；探针编译链接 fat jar
```

探针住在 `verify/src/main/java`（刻意放在 `src/main` 之外，永不进 fat jar），
只用应用的公开 API，因此不需要任何额外 Maven 插件。

| 类别 | 探针 |
|---|---|
| 纯逻辑 | `cron` `diff` `qr` `image` `json` |
| 界面级 | `cronui` `tsui` `diffui` `nav` `smoke` `preview` `body` `httpfix` `httpsingle` `httpsect` `httplayout` |
| 图片 / 二维码 | `qrpic` `qrlist` `sel` |
| 其他 | `run <全类名>` · `compile`（只编译探针） |

**基线**：以下为 2026-09-30 全量连跑（`-nobuild`，串行）的实测值，CSS 解析错误 0。

```
（2026-09-30 全量连跑，18 个断言型探针 515 条全绿；CSS 解析错误 0，jar 纯净度 0）
cron 43 / cronui 13 / nav 19 / json 14 / diff 50 / diffui 41 / smoke 22
/ image 27 / qr 29 / body 22 / httpfix 35 / httpsingle 72 / httplayout 22
/ httpsect 22 / qrlist 6 / sel 20 / tsui 21 / qrpic 40            = 515
```

`preview`（`DesignPreview`）只渲染截图、不产生断言，不计入上表。
历史：275（新增 `features/diff` 前）→ 359 → 375（2026-09-28）→ 443 → **515**（2026-09-30，新增 `httpsingle` 72 条、`httpfix` +3）。

**计数只许升不许降** —— 某条数下降就说明有断言被跳过（通常是探针提前退出）。
用 1:1 替换某条断言可以使计数保持不变，这是唯一允许的「不增」。
`smoke` 会逐模块断言「界面可构建」，新增功能模块会让它自动多一条。

### 三条踩过的坑

**界面级断言要落在「渲染结果」，不是「节点存在」。** `diffui` 里变更图条目的类名是
`diff-seg-diff-mod`，而 CSS 选择器写的是 `.diff-seg.diff-mod` —— 两者对不上，
节点照常布局、照常 `visible`，只是填充透明。只数节点数的断言全绿，放大截图才看出来。
判据应是渲染后的填充色（`Region.getBackground().getFills()`；注意
`Color.getRed()` 返回 0.0–1.0，转 `#rrggbb` 必须 ×255）或样式类与选择器的一致性。

**「属性写对了」不等于「位置对了」。** 空态文案偏左那次，断言查的是
`Label.getTextAlignment()`（CSS 的 `-fx-text-alignment`），它确实是 `CENTER`，
断言绿着，而文字仍贴在左边 —— JavaFX 里居中要两个属性：`-fx-alignment` 决定整块文字
在标签里的位置，`-fx-text-alignment` 只管**折行之间**的相对对齐。
真正的判据是几何：量皮肤内部 `label.lookup(".text")` 节点的中心与容器中心是否重合。

**容器等宽要同时锁 `min` 和 `pref`。** 文本对比的两栏曾出现栏宽 530 / 430、
分隔线随内容横移：`Region` 的 minWidth / prefWidth 默认都由**内容**决定，
而 HBox 是按「各子节点首选宽 + 均摊剩余」分配的 —— 只把 minWidth 归零，
两栏首选宽不同，仍然不等宽。两处都显式设成同一个值。

**已知环境问题**：多个界面级探针连跑时系统剪贴板可能被独占，症状是
`cannot open system clipboard`，`tsui` 的 4 条剪贴板断言与 `qrpic` 会失败 —— 与代码无关。
这类失败会**中断探针**（`qrpic` 正常 40 条，被占时只跑出 36 条），
看到总数变少不要当成「断言被删了」。`tsui` / `qrpic` 应最后单独跑。
