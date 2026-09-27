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

    CONVERT("转换类型工具", "\u21C4"),
    ENCODE("编码/解码类型工具", "\u2295"),
    FORMAT("格式化类型工具", "\u2263"),
    GENERATE("生成类型工具", "\u2726"),
    TEXT("文本处理类型工具", "\u270E"),
    NETWORK("网络请求工具", "\u21AF"),
    IMAGE("图片处理类型工具", "\u25A3"),
    FILE("文件处理类型工具", "\u25A4"),
    OTHER("其他工具", "\u25CF");

    private final String label;
    private final String icon;

    ModuleCategory(String label, String icon) {
        this.label = label;
        this.icon = icon;
    }

    /** 侧边栏中显示的分类名。 */
    public String label() {
        return label;
    }

    /** 侧边栏中显示的分类图标。 */
    public String icon() {
        return icon;
    }
}
