# ToolNest 架构说明

> 本文记录分层设计、依赖规则与关键决策的**理由**。
> 视觉设计与组件规范见 `DESIGN.md`；功能清单见 `README.md`。

---

## 一、为什么要改

改造前的核心问题是：**`ToolModule` 这一个接口同时承担了三个角色** ——
元数据契约、界面工厂、业务入口。所有功能模块都被它带着往下混。

有实证的七个问题：

| 问题 | 证据 |
|---|---|
| 抽象被 UI 技术绑架 | `core/ToolModule` 第 3 行 `import javafx.scene.Node` |
| 依赖倒置违规 | `core/ToolRegistry` import 了 10 个具体模块，最内层依赖最外层 |
| 巨型混合类 | `QrModule` 1506 行 / 49 处 JavaFX；`ImageModule` 714 行，`buildContent()` 单方法 200 行 |
| 测试钩子污染生产码 | `QrModule` 内 31 个 `*ForTest` 方法、2 个剪贴板钩子、伪造鼠标事件工厂 |
| 公共组件层缺位 | `KeyValueTable`（纯 UI 控件）住在 `features/http` 内，别处无法复用 |
| 数据访问与纯算法同层 | `HashOps`/`ImageOps`/`CsvParser`/`HttpEngine` 与 `TextOps`/`CronExpression` 平铺 |
| 分包维度是「技术」不是「业务」 | `modules/file/` 里 `FileHashModule`(界面) 与 `HashOps`(数据) 并列 |

---

## 二、分层结构

```
com.bytefuture.toolnest
├── core/           领域契约（零 JavaFX）
│   ├── ToolDescriptor        工具元数据：id / 名称 / 分类 / 说明 / 图标名 / 关键词
│   └── ModuleCategory        功能分类枚举
│
├── app/            装配层（组合根）—— 唯一知道所有具体实现的地方
│   ├── ToolRegistry          功能注册表 + bootstrap()
│   └── ToolNestApp           JavaFX 应用主类
│
├── ui/             界面层
│   ├── spi/                  扩展点契约
│   │   ├── ToolView          Node buildContent()  ← JavaFX 只在这里出现
│   │   └── ToolModule        extends ToolDescriptor, ToolView
│   ├── shell/                框架界面（不可复用）
│   │   ├── MainWindow        窗口骨架
│   │   ├── Sidebar           分类侧边栏
│   │   ├── ContentPane       内容区 + 返回栈 + 详情缓存
│   │   ├── GridPanePage      分类 / 全部工具宫格页
│   │   └── ToolCard          功能卡片
│   └── components/           公共 UI 组件层（任何 feature 可复用）
│       ├── UiKit             页面骨架 / 区块 / 字段 / 结果行 / 提示 / 按钮四级
│       ├── Icons             自绘线性图标族
│       ├── JsonCodeArea      语法高亮编辑区
│       └── KeyValueTable     键值对编辑表格（本次从 features/http 迁入）
│
└── features/       业务功能层 —— 按业务能力分包
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
                     + HttpEngine + CsvParser + TemplateResolver + HttpRequestSpec
```

### 依赖方向（单向，不可反向）

```
        app  ──────────────┬──────────────┐
         │                 │              │
         ▼                 ▼              │
   ui/shell          features/*           │
         │                 │              │
         ├────────────────►│              │
         │  只用 components │              │
         ▼                 ▼              │
   ui/components ────► ui/spi             │
                           │              │
                           ▼              ▼
                        core  ◄───────────┘
```

规则：

1. **`core` 不依赖任何其他包**，尤其不得出现 `javafx.*`
   —— 判断标准：把 `core` 单独拿来，能否在没有 JavaFX 的环境里编译。
2. **`features/*` 不得 import `ui.shell`**。组件从 `ui.components` 取。
   反向引用意味着一个业务功能开始依赖整个应用外壳，它就再也无法独立测试。
3. **`app` 是唯一允许 import 全部具体模块的包** —— 这是组合根本身的定义。
4. `ui.shell` 可以依赖 `ui.components` 与 `core`。

---

## 三、feature 包内部分层

每个 feature 包用**统一的类名后缀**表达三层职责：

| 层 | 命名 | 约束 |
|---|---|---|
| 界面层 | `XxxModule` | 实现 `ui.spi.ToolModule`；元数据 + 界面组装 + 事件接线；界面元素一律取自 `ui.components` |
| 业务层 | `XxxOps` / `XxxExpression` | **零 JavaFX 依赖**，可脱离界面单独测试 |
| 数据层 | `XxxIo` | 文件 / 网络 / 剪贴板访问 |

**为什么不建 `ui/` `service/` `data/` 三层子包？**
层与层之间需要互相访问（界面要调业务、业务要调数据），强行分包只会迫使可见性被放宽，
反而削弱边界。包表达**业务边界**，类名表达**层次**。约定写在每个包的
`package-info.java` 里，随代码一起演进。

**为什么不设统一的 `data` 包？**
十个功能里六个完全没有 IO，三个的数据访问只有几十行，只有 `image` 与 `http`
足够厚。建全局 `data` 包的结果是「两个类的空壳 + 八处空目录」，那是假分层。
这正是「数据层很薄就并入业务层」原则的落地。

---

## 四、关键决策记录（ADR）

### ADR-001：把 JavaFX 从领域契约中剥离

**状态**：已采用

**背景**：`core/ToolModule.buildContent()` 返回 `javafx.scene.Node`，
使领域契约依赖具体 GUI 技术。

**决策**：拆成两个契约。
- `core.ToolDescriptor` —— 纯元数据，零 JavaFX
- `ui.spi.ToolView` —— 界面工厂，含 `Node`
- `ui.spi.ToolModule` 继承两者，是功能模块实现的唯一接口

**后果**：
- ✅ `core` 可脱离 JavaFX 编译与测试
- ✅ 「工具是什么」与「工具长什么样」可以独立演进
- ⚠️ 契约从一个变成三个，新功能的入口类需要 `implements ToolModule`
  （实际只是一个接口，因为另两个是它的父接口）

### ADR-002：组合根从 `core` 移到 `app`

**状态**：已采用

**背景**：`core.ToolRegistry.bootstrap()` 里 import 了 10 个具体模块 ——
最内层的领域包反向依赖最外层的全部实现。

**决策**：`ToolRegistry` 整体移到 `app` 包，语义不变（它确实是注册表），
只修正依赖方向。`Launcher` 保持在根包不动，`pom.xml` 与 `package.cmd` 无需改动。

**后果**：
- ✅ `core` 恢复纯净
- ✅ 「新增功能只改一个地方」的特性保留
- ⚠️ 代价是注册表无法被 `core` 复用 —— 但它本来就不该被复用

### ADR-003：业务分包用 `features/`，废弃 `modules/`

**状态**：已采用

**背景**：`modules/file/` 里同时住着界面类（`FileHashModule`）和数据类（`HashOps`），
feature 边界与 layer 边界正交，两者都没被表达。

**决策**：`modules/` → `features/`，每个业务能力一个包。
`modules/file` → `features/filehash`（按实际能力命名，而非笼统的「文件」组）。

**后果**：
- ✅ 包名直接对应业务能力
- ⚠️ 触及 10 个探针文件 + `verify.cmd` 类名映射表，须同步（已完成）

### ADR-004：不设全局数据层，数据访问就近放置

**状态**：已采用

**背景**：见第二节的实证统计。

**决策**：数据访问放在需要它的 feature 内；薄到不值得单列时并入业务层。

**后果**：
- ✅ 避免假分层与空壳目录
- ⚠️ 代价是「数据访问」没有统一入口，需要靠 `XxxIo` 命名约定识别

### ADR-005：公共组件层与框架界面分离

**状态**：已采用

**背景**：`ui/` 下同时住着框架界面（`MainWindow`/`Sidebar`/`ContentPane`）
与可复用组件（`UiKit`/`Icons`/`JsonCodeArea`），
「哪些能复用」没有任何界限；而 `KeyValueTable` 这个明确可复用的控件
却住在 `features/http` 内。

**决策**：`ui/` 拆为 `shell`（框架，不可复用）、`components`（公共组件）、
`spi`（扩展点）。`KeyValueTable` 迁入 `components`。

**后果**：
- ✅ 「能复用什么」有了明确答案，且由包边界强制
- ✅ 新组件有唯一去处，不会散落各处
- ⚠️ `features` 不得引用 `ui.shell` 这条规则需要人工守住（无工具强制）

### ADR-006：巨型界面类拆分与测试钩子清理（**未完成，待专项**）

**状态**：提议中

**背景**：`QrModule` 1506 行（含 31 个 `*ForTest` 钩子）、`ImageModule` 714 行、
`BatchPanel` 726 行、`SingleRequestPanel` 584 行。
生产代码里出现 `ForTest` 后缀，说明测试关注点没有出口。

**为什么不与本次分层一并完成**：实测探针对这些钩子的依赖为
**4 个探针文件、100+ 处调用**（`decodeImageForTest` 11 次、`highlightedBodyForTest` 10 次…），
且这些钩子暴露的是内部状态（框选坐标、预览偏移）。清理它们等于重写探针的驱动方式
（从「调测试钩子」改为「构造真实事件 + 断言渲染结果」）。

把「分层搬迁」与「探针重写」两件高风险改动混在一起，一旦基线变红将无法判断
是哪一件造成的。因此拆开处理。

**建议方案**：按页签/职责拆分，已识别的切口：

| 类 | 现状 | 拆分目标 |
|---|---|---|
| `QrModule` | 1506 行 | `QrModule`（入口+装配）+ `QrDecodeView` + `QrGenerateView` + `QrIo`（剪贴板/文件） |
| `ImageModule` | 714 行 | `ImageModule`（入口）+ `ImageView`（界面）+ `ImageService`（编排）+ `ImageIo`（编解码） |
| `ImageOps` | 428 行 | 变换算法留在 `ImageOps`；`read`/`write` + JPEG 质量控制移到 `ImageIo` |

注意 `ImageModule` 的拆分是**连锁**的：编排逻辑现在住在界面类里，
要抽数据层必须先立业务编排层。

---

## 五、本次改造完成情况

### 已完成

- ✅ `core` 剥离 JavaFX（`ToolDescriptor` + `ModuleCategory`）
- ✅ 组合根移入 `app`（`ToolRegistry` + `ToolNestApp`）
- ✅ `ui` 拆为 `spi` / `shell` / `components`，公共组件层成立
- ✅ `KeyValueTable` 从业务包迁入公共组件层
- ✅ `modules/` → `features/`，10 个业务包按能力重命名
- ✅ 17 个 `package-info.java`，分层约定编码进代码
- ✅ 删除死代码：10 个模块里零调用的 `icon()`、零调用的 `UiKit.segmented()`、
  未使用的 `BODY_PADDING` 常量
- ✅ 探针 10 个文件 + `verify.cmd` 类名映射同步完成

### 未完成

- ⏳ ADR-006：巨型界面类拆分 + 31 个 `*ForTest` 钩子清理
- ⏳ 数据层抽取：`ImageIo` / `QrIo`

### 验证基线

```
（2026-09-28 全量连跑，14 个探针 375 条断言全绿，CSS 解析错误 0）
diff 46 / diffui 53 / smoke 22 / nav 19 / json 14 / body 22
/ cron 43 / cronui 13 / tsui 21 / image 27 / qr 29 / qrpic 40
/ qrlist 6 / sel 20                                          = 375
```

历史：275（新增 `features/diff` 前）→ 359 → 375。`smoke` 由 21 变 22 是因为它逐模块
断言「界面可构建」，新增功能模块会让它自动多一条。

**计数只许升不许降** —— 某条数下降就说明有断言没跑（通常是探针提前退出）。
`qrpic` 的 40 条依赖系统剪贴板：同批连跑时若报 `cannot open system clipboard`，
会因为异常中断而只跑出 36 条 —— 这是环境问题，单独重跑即恢复。

---

## 六、新增一个功能要做什么

1. 在 `features/<业务能力>/` 下新建 `XxxModule implements ui.spi.ToolModule`
2. 界面元素全部取自 `ui.components`（`UiKit` / `Icons` / ...）
3. 纯逻辑抽到 `XxxOps`，保证零 JavaFX 依赖
4. 需要文件/网络/剪贴板时，薄就直接写在 `XxxOps` 里，厚则单列 `XxxIo`
5. 在 `app.ToolRegistry.bootstrap()` 加一行 `register(new XxxModule());`
6. 本次无需改动任何界面代码

---

## 七、验证手法

```cmd
verify.cmd smoke     全应用冒烟（构建全部模块界面 + CSS 错误检查）
verify.cmd cron      纯逻辑
verify.cmd cronui    界面级
verify.cmd tsui      界面级（读系统剪贴板比对）
verify.cmd qrpic     界面级（依赖系统剪贴板）
verify.cmd nav       内容区导航
verify.cmd diff      纯逻辑（文本对比算法）
verify.cmd diffui    界面级（两栏文本对比）
```

**界面级探针要断言到「渲染结果」而不是「节点存在」**。`diffui` 里踩过一次：
变更图的条用 `"diff-seg-" + kindClass(...)` 拼出的类名是 `diff-seg-diff-mod`，
而 CSS 选择器写的是 `.diff-seg.diff-mod` —— 两者对不上，节点照常布局、照常
`visible`，只是填充透明。只数节点数的断言全绿，放大截图才看出来。
（变更图已随文本对比的中列一起删除，`.diff-seg*` 规则不复存在 —— 这段保留为
「类名与 CSS 选择器对不上」的通用教训，同样的坑在 `.diff-item*` / `.diff-band*` 上也一样会踩。）

修法有二：读渲染后的填充色（`Region.getBackground().getFills()`，注意
`Color.getRed()` 返回 0.0–1.0，转 `#rrggbb` 必须乘 255），或断言样式类名与
CSS 选择器一致。前者更能覆盖「类名写错」「CSS 被覆盖」两类原因。

**「属性写对了」不等于「位置对了」**。空态文案偏左那次，第一版断言查的是
`Label.getTextAlignment()`（CSS 的 `-fx-text-alignment`），它<b>确实</b>是
`CENTER`，断言绿着，而画面上文字仍贴在左边。原因是 JavaFX 里居中要两个属性：
`-fx-alignment` 决定整块文字摆在标签里的位置（Label 默认 `CENTER_LEFT`），
`-fx-text-alignment` 只管<b>折行之间</b>的相对对齐。真正的判据是几何：读皮肤内部的
`label.lookup(".text")` 节点，量它的中心与容器中心是否重合 —— 量标签矩形的中心没用，
标签被撑满宽度后矩形永远居中，只有里面的文字会偏。

**容器等宽要同时锁「最小宽」和「首选宽」**。文本对比的左右两栏曾出现栏宽 530 / 430、
栏间分隔线随内容整条横移（同样的窗口下 x 在 517/523/573 之间跳）。原因有两层：
`Region` 的 minWidth / prefWidth 默认都由<b>内容</b>决定，而 HBox 是按
「各子节点首选宽 + 均摊剩余」分配的 —— 只把 minWidth 归零，两栏的首选宽不同，
仍然不等宽。两处都显式设成同一个值，栏宽才只由窗口宽度决定。

**已知环境问题**：多个界面级探针连跑时，系统剪贴板可能被其他进程独占，
症状是 `cannot open system clipboard`。此时 `tsui` 的 4 条剪贴板断言与
`qrpic` 会失败 —— 这与代码无关。判别方法：用一个不含项目代码的
最小程序读写剪贴板，同样失败即可确认是环境问题；单独重跑通常可恢复。
注意这类失败会<b>中断探针</b>：`qrpic` 正常 40 条，剪贴板被占时只跑出 36 条，
看到总数变少不要当成「断言被删了」。
