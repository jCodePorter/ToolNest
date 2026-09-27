# ToolNest 工具巢

JavaFX 桌面端效率工具集。

界面采用**三层信息架构**：

1. **左侧文件夹菜单** —— 功能按分类折叠成文件夹（转换 / 编码 / 格式化 / 生成 / 文本 / 网络请求 / 图片 / 文件 / 其他）
2. **右侧宫格页** —— 点击文件夹（或顶部「所有工具」），右侧以大卡片宫格展示该分类下的全部功能
3. **功能详情页** —— 点击宫格卡片，**或**直接在左侧展开的文件夹里点子项，跳转到详情页

## 主要依赖

| 依赖 | 用途 |
|---|---|
| JavaFX 21.0.12 | 界面框架（Maven 自动下载） |
| AtlantaFX 2.1.0 | 主题皮肤 |
| Gson 2.11.0 | JSON 解析 |
| RichTextFX 0.11.2 | JSON 语法高亮的代码编辑控件 |

## 环境要求

| 项 | 版本 | 说明 |
|---|---|---|
| JDK | **21** | 路径 `C:\Program Files\Java\jdk-21` |
| Maven | 3.8.5 | 路径 `D:\Dev\apache-maven-3.8.5` |
| JavaFX | 21.0.12 | 由 Maven 自动下载，无需手动安装 |

> 本机 `JAVA_HOME` 默认指向 JDK 8，**必须**通过 `build.cmd` 或手动指定 `JAVA_HOME` 构建。

## 快速开始

```bat
REM 开发期直接运行
build.cmd javafx:run

REM 仅编译打包
build.cmd
```

在 Git Bash 下也可以手动指定 JDK：

```bash
JAVA_HOME="/c/Program Files/Java/jdk-21" mvn javafx:run
```

## 打包 exe

```bat
package.cmd
```

产物：`build\dist\ToolNest\ToolNest.exe` —— **双击即可运行**。

分发时把整个 `build\dist\ToolNest\` 文件夹拷给别人即可（内含精简 JRE，目标机器无需安装 Java）。

> ⚠️ **重新打包前要先把旧产物移走**。jpackage 不会覆盖已存在的目标目录，会直接
> 报 `错误：应用程序目标目录 ...\build\dist\ToolNest 已存在` 并失败。
> 处理方式：`move build\dist\ToolNest build\dist_old`，再跑 `package.cmd`，成功后可删掉 `dist_old`。

## 新增一个功能（核心流程）

只需两步，**不用改任何界面代码** —— 侧边栏文件夹、宫格页、详情页都会自动生成：

**1. 新建模块类**，实现 `ToolModule` 接口。例如新建
`src/main/java/com/bytefuture/toolnest/modules/myfeature/MyFeatureModule.java`：

```java
package com.bytefuture.toolnest.modules.myfeature;

import com.bytefuture.toolnest.core.ModuleCategory;
import com.bytefuture.toolnest.core.ToolModule;
import com.bytefuture.toolnest.ui.UiKit;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

public class MyFeatureModule implements ToolModule {

    /** 唯一标识，详情页缓存与定位用 */
    @Override
    public String id() {
        return "my-feature";
    }

    /** 显示名称：左侧子项、卡片标题都用它 */
    @Override
    public String displayName() {
        return "我的功能";
    }

    /** 分类：决定归到哪个文件夹、出现在哪张宫格页 */
    @Override
    public ModuleCategory category() {
        return ModuleCategory.OTHER;
    }

    /** 卡片副标题（一行说明，宫格里显示） */
    @Override
    public String summary() {
        return "一句话描述这个功能干什么";
    }

    /** 卡片正文说明（可选，宫格里跟在副标题后面） */
    @Override
    public String description() {
        return "补充说明，比如支持哪些格式、有什么注意事项";
    }

    /** 卡片 / 子项图标，可选，默认圆点 */
    @Override
    public String icon() {
        return "\u2605";
    }

    /** 搜索关键词，可选，空格分隔 */
    @Override
    public String keywords() {
        return "my feature 关键词";
    }

    /** 构建详情页内容（用户首次打开该功能时才执行） */
    @Override
    public Node buildContent() {
        VBox root = UiKit.page("我的功能", "这里写功能说明");
        root.getChildren().add(new Label("Hello!"));
        return root;
    }
}
```

**2. 在 `ToolRegistry.bootstrap()` 里注册一行。**
打开 `src/main/java/com/bytefuture/toolnest/core/ToolRegistry.java`：

```java
public static void bootstrap() {
    register(new TimestampModule());
    register(new JsonFormatModule());
    register(new EncodeModule());
    register(new UuidModule());
    register(new MyFeatureModule());   // ← 加这行，并在文件顶部加对应的 import
}
```

完成。重新运行后：
- 左侧 `其他工具` 文件夹里会自动出现「我的功能」子项
- 点击该文件夹，右侧宫格会自动多出一张卡片
- 若该分类此前没有功能，文件夹也会自动创建

> **分类已存在但没功能时不会显示**：`usedCategories()` 只返回至少有一个功能的分类，
> 所以不必担心空文件夹。想加新分类，直接在 `ModuleCategory` 枚举里加一项即可。

## 已内置的功能

| 功能 | 分类 | 说明 |
|---|---|---|
| 时间戳转换 | 转换 | 时间戳 ↔ 日期时间双向转换，支持秒/毫秒 |
| **Cron 表达式解析** | 转换 | 解析 cron 表达式，中文说明 + 未来执行时间预览（5 段 / 6 段） |
| JSON 格式化 | 格式化 | 格式化、压缩、校验、转义，**带语法高亮** |
| 编解码工具 | 编码 | Base64 / URL / Hex 编码解码 |
| UUID 生成 | 生成 | 批量生成随机 UUID（版本 4） |
| **文本处理** | 文本 | 去重、排序、去空行、大小写、加行号、正则替换与统计 |
| **HTTP 请求工具** | 网络 | 单一请求调试 + CSV 批量执行，见下节 |
| **文件哈希** | 文件 | MD5 / SHA-1 / SHA-256 / SHA-512，支持拖入多文件批量计算 |
| **图像处理** | 生成 | 缩放、旋转、格式转换、裁剪、灰度，支持拖入与批量导出 |
| **二维码** | 生成 | 生成二维码（纠错级别/尺寸/颜色可调），解析支持本地文件与**剪贴板直接粘贴**（可框选裁切） |

## JSON 格式化

左侧输入、右侧输出，提供**格式化 / 压缩 / 转义 / 反转义**四个动作。

- **语法高亮**：输出区基于 RichTextFX 的 `CodeArea` 实现按字符着色（原生 `TextArea`
  只支持单一颜色，做不到），配色参考 VS Code 浅色主题：

  | 元素 | 颜色 |
  |---|---|
  | 键名 | 深蓝加粗 |
  | 字符串值 | 绿色 |
  | 数字 | 橙色 |
  | `true` / `false` / `null` | 紫色加粗 |
  | 标点 | 深灰 |

  同时带行号栏，便于定位。

- **缩进自动重排**：切换「缩进」下拉框（2 空格 / 4 空格 / 制表符）会**立即重新格式化**，
  不需要再点「格式化」按钮。注意只在当前输出是格式化结果时才触发 —— 压缩或转义的结果
  套用缩进没有意义。

- 语法错误会在底部给出提示，输出区自动清空。

## Cron 表达式解析

解决「拿到一个 cron 表达式，不确定它到底什么时候跑」这个高频困惑。

- **两种格式**：6 段（秒 分 时 日 月 周）与 5 段（分 时 日 月 周）自动识别
- **语法支持**：`*` / 单值 / `1-5` 区间 / `1,3,5` 枚举 / `*/15` 步长 /
  `1-30/5` 区间步长 / 别名 `MON`~`SUN`、`JAN`~`DEC` /
  `@daily` 等快捷别名
- **中文说明**：逐字段给出可读描述，例如 `0 9 * * 1-5` → 「时：9 时；周：周一~周五」
- **执行预览**：列出未来 N 次（1–50 可调）执行时间，并标注**与上次的间隔**，
  便于一眼确认触发节奏。上面的例子里 9-25（周五）到 9-28（周一）会显示 `+3 天`，
  正确跳过了周末
- **错误定位**：表达式非法时明确指出问题字段，例如「分 字段的取值 60 超出范围 [0, 59]」
- **永不触发检测**：如 `0 0 0 30 2 *`（2 月 30 日）会明确回报「永不触发」，
  而不是静默给出错误答案

> **一个容易踩的坑**：日与周同时被限定时是**或**关系（cron 通行语义）。
> `0 0 1 * 1` 表示「每月 1 号**或**每周一」，而不是「每月 1 号且是周一」。

底层实现见 `modules/cron/CronExpression.java`，零依赖纯 JDK 实现。

### 为什么用「向后试探」而不是纯数学推导

数学推导需要处理日/周的或关系、闰年、各月天数等大量边界，容易在个案上出错且难以验证。
本实现改为从给定时间起按最小粒度向前试探（有秒字段逐秒，否则逐分钟），
最多扫描 4 年（完整闰年周期）后判定无解。逻辑简单、边界清晰 ——
代价是极端表达式要扫满上限，但这恰恰保证了「永不触发」能被明确回报。

## 文本处理

左侧原文、右侧结果，原文永不被修改，便于反复尝试不同组合。

- **就地变换**：行去重、行排序（升/降序、区分大小写、按数字大小）、
  去空行、去首尾空行、去行首尾空白、压缩连续空格、大小写转换
- **生成类**：加行号（可设起始值与分隔符）、正则替换（支持 `$1` 分组引用，
  可关闭正则按纯文本字面量替换）
- **文本统计**：实时显示行数（含非空行）、字符数（含不含空白）、词数、UTF-8 字节数

**变换可连续叠加**：每一步都作用在**结果区**上（结果区为空时才退回原文），
所以可以先「去空行」再「去重」再「排序」，逐步逼近目标。
用「用结果替换原文」把结果固化回原文，开始新一轮处理。

## 文件哈希

- **两类输入**：直接输入文本算摘要，或选择 / **拖入文件**（支持多选批量）
- **四种算法**：MD5 / SHA-1 / SHA-256 / SHA-512
- **一次读取算全部算法**：校验场景经常要在算法间切换比对，
  一次遍历把常用摘要全算出来，省掉重复读盘
- **大文件不卡界面**：流式读取（64KB 缓冲，内存占用恒定），
  计算跑在后台线程，界面全程可响应并显示进度
- **单个文件失败不中断整批**：某个文件读不了会记一条失败结果继续处理其余文件

> 默认输出**小写**十六进制（与 `sha256sum`、`certutil` 等命令行工具一致），
> 避免比对时因大小写不一致误判。

## 二维码

生成与解析双向功能，基于 ZXing 的 **`core` 纯算法模块**（不含 `javase`）实现。

### 生成

- **可调项**：纠错级别（L / M / Q / H，附容量提示与适用场景说明）、
  输出尺寸、四周留白（模块数）、前景色与背景色
- **实时预览**：改参数即时重绘。预览关闭了平滑插值（`setSmooth(false)`），
  保证码点边缘锐利不错位，所见即所得的才是真正要被扫的那张
- **导出**：导出 PNG 或直接复制到剪贴板

> **纠错级别的取舍**：L 约 7% / M 约 15% / Q 约 25% / H 约 30%。
> 级别越高越耐污损，但同样内容需要更多码点，图会更「密」。
> 想在码中间叠 logo 必须用 H 级。

### 解析

三种输入方式，都走同一套解析管线：

1. **选择图片** — 打开本地图片文件
2. **粘贴剪贴板（Ctrl+V）** — 这是**截图场景的主力入口**：微信/QQ 截图工具
   `Ctrl+Alt+A` 框完直接复制，切到本工具 `Ctrl+V` 即可，全程不落盘。
   快捷键挂在**整个窗口**上，不需要先用鼠标点一下预览区
   （焦点停在页签上也能生效）；结果框等输入控件内的 `Ctrl+V` 仍保留原生
   文字粘贴行为，不会被抢占
3. **拖入文件** — 直接拖图进预览区

剪贴板优先级为 图片 → 文件 → 文本，逐级探测并给出对应提示，
避免「复制了文本却提示无法识别图片」这类误导。

- **框选裁切**：在预览区按住鼠标拖出矩形，只识别框内区域。
  这解决截图常见的困境 —— 截多了带进无关内容，整图解析失败或识别到错误的码。
  坐标换算按**源图尺寸 ÷ 显示尺寸**的比例还原，并向外留 1/8 边距，
  避免用户框得比码略小时切掉定位角。
- **多码支持**：一张图里有多个二维码时全部列出（`QRCodeMultiReader`）
- **降级策略**：原图 → 放大 3 倍 → 高对比度灰度，逐级重试。
  小尺寸码、浅色码、带干扰的截图靠这一步救回来
- **失败归因**：区分「未找到二维码」「格式不支持」「校验失败」并给出中文原因，
  而不是笼统报「识别失败」

底层实现见 `modules/qrcode/QrOps.java`（纯逻辑，不依赖 JavaFX）+ `QrModule.java`（界面）。

> **为什么不引 `javase`**：ZXing 的 `javase` 模块会连带拉进 `jcommander`
> 与 `jai-imageio-core` 两个本工具用不到的依赖。它只提供了
> `BufferedImageLuminanceSource` 这类胶水类，自己写约 40 行即可替代，
> 换来的是打包体积与依赖面都保持可控。

## HTTP 请求工具

用 JDK 内置的 `java.net.http.HttpClient` 实现，**零第三方依赖**。

### 单一请求

仿 Postman 的布局，支持 `GET` / `POST` / `PUT` / `DELETE`：

- **方法下拉 + URL 输入框 + Send 按钮**
- **Params**：键值对表格，自动以 query 参数拼到 URL 之后（URL 自带查询串会保留，用 `&` 连接）
- **Headers**：键值对表格，带勾选框控制是否参与本次请求
- **Body**：支持 `JSON` / `XML` / `Text` / `Form` 四种类型，选 JSON 时带「格式化」按钮
- **响应区**：状态码（按 2xx/3xx/4xx 着色）、耗时、响应大小，三个页签分别看 Body / 响应头 / 请求详情
- 超时时间可调（默认 30 秒）

**响应体 JSON 语法高亮**（响应区 Body 页签）：

- 响应体是 JSON 对象/数组时**自动高亮**（键名深蓝、字符串绿、数字橙、布尔/null 紫），
  与 JSON 格式化模块共用同一套配色与正则
- 工具栏右侧提供「**格式化 / 压缩**」按钮，在美化与原始文本之间往返切换
- 非 JSON（HTML、纯文本等）走普通文本框，「格式化」按钮置灰
- **超过 256KB 的响应不做高亮**：逐字符跑正则 + 构建样式区间常数很大，
  几 MB 的响应会卡住界面线程；这类响应本来也该靠搜索定位，纯文本更实用

> 实现要点：`TextArea` 全文只能有一个颜色，高亮必须用支持按区间着色的
> `JsonCodeArea`（RichTextFX）。因此 Body 页签里叠放了**两个视图**，
> 按内容在它们之间切换可见性 —— 这也是 `HttpBodyProbe` 主要验证的行为。

### 批处理

**1. 准备 CSV**，第一行是表头，即变量名：

```csv
orderId,type,channel
235027703726,A,web
235027703727,B,app
235027703728,A,mini
```

- 支持逗号 / 分号 / 制表符，分隔符自动识别
- 支持引号包裹（`"含,逗号"`）与内部引号转义（`""`）
- 自动识别编码：UTF-8（含 BOM）/ GBK
- 导入方式：**选择 CSV 文件** 或 **直接粘贴文本**，另有「载入示例」一键体验

**2. 写请求模板**，用 `{列名}` 占位符引用 CSV 列：

| 位置 | 示例 |
|---|---|
| 请求地址 | `https://api.example.com/order?orderId={orderId}&type={type}` |
| 请求体 | `{"id": "{orderId}", "channel": "{channel}"}` |
| 请求头 | `{"X-Order-Id": "{orderId}"}` |

> 占位符规则：主用单花括号 `{orderId}`；同时兼容 Postman 习惯的 `{{orderId}}` 双花括号。
> 匹配列名时忽略大小写。**CSV 里没有对应列时会原样发出**，界面会给出黄色提示，
> 便于发现拼写错误（不会静默替换成空，那样很难排查）。
**3. 配置执行方式**

- **顺序执行**：逐条发送，稳定、易排查，可设「间隔(ms)」做限速
- **并发执行**：线程池并发（1–64 线程可选），适合大批量
- 可随时点「停止」中断

**4. 看结果 / 导出**

结果表格逐行显示 `状态码 / 耗时 / 最终 URL / 响应预览`，点某行在右侧看完整响应
（含 CSV 变量、响应头、请求体）。顶部显示 `成功 N　失败 M　平均 X ms`，可**导出结果 CSV**。

实测参考：3 个 `httpbin.org` 请求，顺序执行总耗时 2352ms，4 线程并发 308ms。

### 小技巧

单一请求页顶部有「**↑ 用当前请求作为批处理模板**」按钮 —— 先在单一请求页把
接口调通，再一键带入批处理页，省去重复录入。

## 项目结构

```
src/main/java/com/bytefuture/toolnest/
├── Launcher.java              # 唯一 main 入口（打包时用这个类）
├── ToolNestApp.java           # Application 主类，设置主题
├── core/
│   ├── ToolModule.java        # ★ 功能模块接口
│   ├── ModuleCategory.java    # 分类枚举（文件夹标题、图标）
│   └── ToolRegistry.java      # ★ 注册表（新增功能改这里）
├── ui/
│   ├── MainWindow.java        # 主窗口骨架（SplitPane + 三方接线）
│   ├── Sidebar.java           # 左侧文件夹菜单（TitledPane，由注册表自动生成）
│   ├── GridPanePage.java      # 右侧宫格页（单分类 / 所有工具分段）
│   ├── ToolCard.java          # 宫格大卡片组件
│   ├── ContentPane.java       # 右侧内容区（宫格/详情切换 + 懒加载缓存 + 返回栈）
│   ├── JsonCodeArea.java      # JSON 语法高亮显示区（RichTextFX）
│   └── UiKit.java             # 详情页构件工厂
└── modules/                   # 各功能模块
    ├── timestamp/
    ├── cron/                  # Cron 解析（零依赖）
    │   ├── CronModule.java          # 模块入口与界面
    │   └── CronExpression.java      # 解析 + 下次执行时间推算
    ├── json/
    ├── encode/
    ├── uuid/
    ├── text/                  # 文本处理
    │   ├── TextModule.java          # 模块入口与界面
    │   └── TextOps.java             # 纯函数式的文本变换与统计
    ├── file/                  # 文件哈希
    │   ├── FileHashModule.java      # 模块入口、界面、后台计算调度
    │   └── HashOps.java             # 流式哈希计算
    ├── image/                 # 图片处理（零第三方依赖，走 JDK ImageIO）
    │   ├── ImageModule.java         # 模块入口、参数面板、导出与批处理
    │   └── ImageOps.java            # 缩放/裁剪/旋转/翻转/格式转换
    ├── qrcode/                # 二维码（仅引 ZXing core 纯算法模块）
    │   ├── QrModule.java            # 模块入口、双页签界面、剪贴板与框选交互
    │   └── QrOps.java               # 生成/解析/裁切/降级策略（不依赖 JavaFX）
    └── http/                  # HTTP 工具（拆成多个类，便于维护）
        ├── HttpToolModule.java      # 模块入口，Tab 容器
        ├── HttpRequestSpec.java     # 请求值对象（方法 / URL / 请求体类型与内容）
        ├── HttpEngine.java          # 基于 java.net.http 的请求执行引擎
        ├── TemplateResolver.java    # {列名} 占位符替换
        ├── CsvParser.java           # CSV 解析（分隔符/编码/引号自动处理）
        ├── KeyValueTable.java       # 键值对编辑表格（Params/Headers 复用）
        ├── SingleRequestPanel.java  # 单一请求面板
        └── BatchPanel.java          # 批处理面板

src/main/resources/
├── css/app.css                # 全部样式
└── icon/app.ico / app.png     # 应用图标

verify/src/main/java/com/bytefuture/toolnest/   # 开发期探针（不进产物）
├── CronTest.java / ImageTest.java              # 纯逻辑测试
├── SmokeTest.java / SmokeTestLauncher.java     # 全模块冒烟 + 截图
├── JsonProbe.java / ContentPaneProbe.java      # 界面级探针（含 Launcher）
├── QrTest.java                                 # 二维码纯逻辑（往返/裁切/降级）
├── DesignPreview.java                          # 离屏设计评审截图
├── modules/qrcode/QrModuleProbe.java           # 二维码界面级探针（含 Launcher）
└── modules/http/HttpBodyProbe.java             # HTTP 响应体渲染探针
```

> `verify/` 与 `src/` 平级，由根目录的 `verify.cmd` 编译运行。
> 放这里的原因见下文「开发期验证探针」。

### 关键设计

- **注册表驱动**：所有界面元素都由 `ToolRegistry` 推导，加功能零界面改动。
- **懒加载 + 缓存**：`ContentPane` 用 `Map` 缓存宫格页与详情页，切回时保留输入状态。
  详情页缓存是**有界 LRU**（12 项）—— 模块数量会持续增长，无上限缓存等于只增不减的
  内存泄漏；被淘汰的页面下次打开重建，代价可接受。
- **返回栈**：`ContentPane` 维护浏览历史，宫格 A → 工具 B → 宫格 C → 工具 D 时，
  连点返回依次回到 C → B → A（早期实现只能退回「上一次的宫格」）。
  两条规则：返回操作本身不再入栈（否则来回死循环）；原地重进不记史（避免连点后退半步）。
  宫格页在**有历史时**才显示返回按钮，无历史时隐藏，不出现点了没反应的死按钮。
- **双类入口**：`Launcher`（普通类）→ `ToolNestApp`（Application 子类），
  规避 JavaFX 的 "runtime components are missing" 检查。
- **不用 FXML**：纯 Java 构建界面，加功能时无需维护 fxml + controller 两份文件。
- **不做 JPMS**：没有 `module-info.java`，走 classpath + shade fat jar，减少打包摩擦。

### 开发期验证探针

`buildContent()` 是**惰性调用**的，所以「编译通过」完全不代表功能正常 ——
运行时的异常、CSS 解析错误都只在真正打开界面时才暴露。项目因此内建了几个探针，
它们直接驱动**真实控件**（`button.fire()`、`scene.snapshot()`），不依赖鼠标坐标，
可在无头环境稳定复现：

| 探针 | 验证内容 |
|---|---|
| `ContentPaneProbe` | 返回栈逐层回退、原地重进不记史、LRU 淘汰后仍能重建页面 |
| `JsonProbe` | 三种缩进在界面上真的生效、字符串内 `\n` 不破坏缩进、非法 JSON 有错误反馈 |
| `CronTest` | 纯逻辑：闰年、月末、日月或关系、周日两种写法、非法表达式拒绝 |
| `ImageTest` | 纯逻辑：等比缩放、裁剪越界、旋转宽高互换、透明图转 JPEG 不出现黑块 |
| `QrTest` | 纯逻辑：四种纠错级别往返、裁切坐标、小码降级、噪声图不崩、空文本拒绝 |
| `QrModuleProbe` | 界面级：框选坐标换算、剪贴板链路、空输入作废过期生成图、CSS 无错 |
| `HttpBodyProbe` | 响应体 JSON/非 JSON 分流、格式化往返、超大响应降级、清空后状态复位 |
| `SmokeTest` | 启动完整应用骨架，逐个构建全部模块界面 + 截图 + 捕获 CSS 错误 |
| `DesignPreview` | 离屏渲染设计评审截图（真实主题 + 样式表） |

这些类**刻意放在 `verify/src/main/java`，不放在 `src/main`**：它们是开发脚手架而非
产品功能，混进 fat jar 只会让包体变大、也让「什么是要发布的东西」变得含糊。
因此正常构建的产物里不会出现任何一个探针类。

运行方式（一条命令，`verify.cmd` 会先编译探针再运行）：

```cmd
verify.cmd cron        :: CronTest   —— 纯逻辑，无界面
verify.cmd image       :: ImageTest  —— 纯逻辑，无界面
verify.cmd qr          :: QrTest     —— 纯逻辑，无界面
verify.cmd json        :: JsonProbe  —— 界面级
verify.cmd qrpic       :: QrModuleProbe —— 界面级
verify.cmd body        :: HttpBodyProbe —— 界面级
verify.cmd nav         :: ContentPaneProbe
verify.cmd smoke       :: SmokeTest  —— 全模块截图 + CSS 错误统计
verify.cmd preview     :: DesignPreview
verify.cmd compile     :: 只编译不运行
verify.cmd run <Class> :: 运行任意探针类
```

默认每次都会先重建 fat jar（探针是编译链接到 jar 上的，用旧 jar 会报
莫名其妙的 "cannot find symbol"）。只想改探针代码、不想等打包时加 `-nobuild`：

```cmd
verify.cmd smoke -nobuild
```

`verify.cmd` 的工作原理：探针只用到应用的公开 API，因此先 `mvn package` 打出
`target/toolnest.jar`，再把 `verify/` 下的源码编译进 `target/verify-classes`，
以「fat jar + 探针 class 目录」作为 classpath 运行 —— **不需要任何额外的 Maven 插件**。

`SmokeTest` 会把每个模块界面截图输出到 `target/smoke/`，便于人工核对视觉。

> Application 子类**不能**直接作为 main class 启动（会报
> "JavaFX runtime components are missing"），因此每个界面级探针都配了一个
> `XxxProbeLauncher` 普通类包装，与 `Launcher` 规避同类问题的手法一致。
>
> **cmd 脚本的坑**：`verify.cmd` 里的 `javac` 必须写 `--release 21`（两个横杠）。
> 本机 JDK 21 的 javac 不接受单横杠长选项 `-release`，会报「无效的标记」。
> 另外传给 javac 的 `@argfile` 里路径要用正斜杠 —— argfile 中反斜杠是转义符，
> `D:\Person\...` 会被吃成 `D:Person...` 且 `\t` 变制表符。


## 常见问题

**Q: `mvn javafx:run` 报 `找不到或无法加载主类`**
`pom.xml` 中 `javafx-maven-plugin` 的 `mainClass` 必须与实际主类一致。本项目已用
`${main.class}` 属性引用，只需在 `<properties>` 里维护一处（当前为
`com.bytefuture.toolnest.Launcher`）。`package.cmd` 里的 `MAIN_CLASS` 也要同步。

**Q: 编译报 `release version 21 not supported`**
Maven 跑在了 JDK 8 上。用 `build.cmd`，或确认 `mvn -v` 显示 `Java version: 21.0.5`。

**Q: 界面中文显示乱码**
确认 `pom.xml` 里 `project.build.sourceEncoding=UTF-8` 存在，运行时带
`-Dfile.encoding=UTF-8`（`build.cmd` / `package.cmd` 已配置）。

**Q: 执行 `.cmd` 脚本时提示 `'EM' 不是内部或外部命令`**
`.cmd` 文件里不能出现中文（含中文注释）。cmd.exe 按系统 ANSI 代码页（中文 Windows 是 GBK）
解析批处理文件，UTF-8 中文的第一个字节会吞掉后面的命令。**保持脚本纯 ASCII。**

**Q: 依赖下载失败**
本机 Maven 本地仓库在 `D:\Dev\apache-maven-repository`（不是 `~/.m2`）。

**Q: 需要带安装向导的安装包**
先安装 **WiX Toolset v3.14**（必须 v3.x，v4/v5 与 jpackage 不兼容）并加入 PATH，
然后把 `package.cmd` 里的 `--type app-image` 换成 `--type exe`，
并追加 `--win-menu --win-shortcut --win-dir-chooser`。

## 换主题

本项目用 [AtlantaFX](https://github.com/mkpaz/atlantafx) 主题库。在 `ToolNestApp.java` 中改一行即可：

```java
setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());
// 可选：PrimerLight / PrimerDark / NordLight / NordDark /
//       CupertinoLight / CupertinoDark / Dracula
```

`app.css` 中所有 `-color-*` 变量都写了 fallback 值（如 `-color-bg-default, #ffffff`），
即使主题加载失败，界面也会降级为默认配色而不是崩溃。
