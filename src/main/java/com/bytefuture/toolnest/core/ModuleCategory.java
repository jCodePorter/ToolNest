package com.bytefuture.toolnest.core;

/**
 * 功能分类。
 *
 * <p>侧边栏中的每个分类表现为一个可折叠的「文件夹」，展开后列出其中的功能模块；
 * 点击文件夹本身，右侧以宫格形式展示该分类下的所有功能。
 *
 * <p>枚举声明顺序即侧边栏中的展示顺序。
 */
public enum ModuleCategory {

    CONVERT("转换类型工具", "switch"),
    ENCODE("编码/解码类型工具", "code"),
    FORMAT("格式化类型工具", "braces"),
    GENERATE("生成类型工具", "sparkle"),
    TEXT("文本处理类型工具", "type"),
    NETWORK("网络请求工具", "globe"),
    IMAGE("图片处理类型工具", "image"),
    FILE("文件处理类型工具", "file"),
    OTHER("其他工具", "grid");

    private final String label;
    private final String iconName;

    ModuleCategory(String label, String iconName) {
        this.label = label;
        this.iconName = iconName;
    }

    /** 侧边栏中显示的分类名。 */
    public String label() {
        return label;
    }

    /**
     * 分类图标名，取自 {@code com.bytefuture.toolnest.ui.components.Icons} 注册的线性图标。
     *
     * <p>这里存的是<b>名字而不是字符或图形</b>：分类枚举属于 core 层，
     * 不该依赖任何具体的图标实现、字体或图片资源。名字由展示层解析。
     */
    public String iconName() {
        return iconName;
    }
}
