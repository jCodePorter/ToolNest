package com.bytefuture.toolnest.core;

/**
 * 功能元数据契约 —— 领域层对「一个工具是什么」的完整描述。
 *
 * <p><b>本接口刻意不依赖 JavaFX。</b>它只描述数据，不描述界面：
 * 「叫什么、属于哪个分类、卡片上写什么、搜索命中哪些词」都属于领域知识，
 * 与用什么 GUI 技术无关。
 *
 * <p>「这个工具长什么样」由 {@code ui.spi.ToolView} 负责，两者在
 * {@code ui.spi.ToolModule} 汇合，由 {@code app.ToolRegistry} 统一装配。
 * 这样 core 包可以脱离 JavaFX 独立编译与测试，而新增工具时
 * 元数据的改动与界面的改动互不牵连。
 *
 * <p>界面呈现位置有两处：
 * <ul>
 *   <li><b>宫格卡片</b>：分类宫格页里展示 {@code displayName}、
 *       {@code summary}、{@code description} 与 {@code iconName}</li>
 *   <li><b>侧边栏子项</b>：展示 {@code displayName}，并用 {@code keywords} 参与搜索过滤</li>
 * </ul>
 */
public interface ToolDescriptor {

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
     * 线性图标的名称，取自 {@code com.bytefuture.toolnest.ui.components.Icons}
     * 注册的名字，例如 {@code "clock"}、{@code "braces"}、{@code "qr"}。
     *
     * <p>这里存的是<b>名字而不是图形</b>：本接口属于 core 层，不该依赖任何
     * 具体的图标实现、字体或图片资源。名字由展示层解析。
     * 留空（默认）会回退到所属分类的图标，因此新模块即使不实现本方法也不会出现空白图标。
     */
    default String iconName() {
        return "";
    }

    /** 搜索关键词，空格分隔，用于侧边栏搜索框过滤。 */
    default String keywords() {
        return "";
    }
}
