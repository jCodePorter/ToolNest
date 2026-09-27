package com.bytefuture.toolnest.core;

import javafx.scene.Node;

/**
 * 功能模块接口 —— 整个工具集的核心扩展点。
 *
 * <p>一个模块在界面上有两处呈现：
 * <ul>
 *   <li><b>宫格卡片</b>：出现在所属分类的宫格页里，展示 {@code displayName}、
 *       {@code summary}、{@code description} 与 {@code icon}</li>
 *   <li><b>详情页面</b>：由 {@link #buildContent()} 构建的实际功能界面</li>
 * </ul>
 *
 * <p>新增功能的完整流程：
 * <ol>
 *   <li>在 {@code modules/xxx/} 下新建 {@code XxxModule.java} 实现本接口</li>
 *   <li>在 {@link ToolRegistry#bootstrap()} 里加一行 {@code register(new XxxModule());}</li>
 * </ol>
 * 无需改动 Sidebar / ContentPane / ToolCard 等任何界面代码。
 */
public interface ToolModule {

    /** 唯一标识，用于缓存与定位。建议全小写、无空格，如 "timestamp"。 */
    String id();

    /** 功能名称，显示在卡片标题与侧边栏子项上。 */
    String displayName();

    /** 所属分类，决定归到哪个文件夹。 */
    ModuleCategory category();

    /**
     * 卡片副标题，一到两行，说明这个工具「做什么」。
     * 例如 "将 Unix 时间戳转换成日期时间并支持反向转换"。
     */
    String summary();

    /**
     * 卡片正文说明，可稍长一些。返回空串则在卡片上不显示该段。
     */
    default String description() {
        return "";
    }

    /**
     * 卡片图标。用 emoji 或单个 Unicode 字符最省事，无需图片资源。
     * 建议使用观感一致的符号类字符。
     */
    default String icon() {
        return "\u2022";
    }

    /** 搜索关键词，空格分隔，用于侧边栏搜索框过滤。 */
    default String keywords() {
        return "";
    }

    /**
     * 构建功能详情界面。
     *
     * <p>本方法为惰性调用 —— 只有用户首次打开该功能时才会执行，
     * 返回值会被 {@code ContentPane} 缓存，再次打开直接复用（保留用户输入状态）。
     */
    Node buildContent();
}
