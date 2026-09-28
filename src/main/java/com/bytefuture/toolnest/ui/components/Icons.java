package com.bytefuture.toolnest.ui.components;

import com.bytefuture.toolnest.core.ModuleCategory;
import com.bytefuture.toolnest.ui.spi.ToolModule;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

import java.util.Map;

/**
 * 线性图标族。
 *
 * <h2>为什么替换掉 Unicode 符号</h2>
 * <p>早期实现直接用 Unicode 字符（如 {@code \u21BB}、{@code \u2263}）当图标。
 * 它们来自不同字体的不同码位，描边粗细、视觉重量、包围盒全都不一致 ——
 * 放进同一尺寸的底托里，节奏是散的。
 *
 * <p>这里换成一套自绘的线性图标：统一 24 网格、圆头圆角、同一描边公式。
 * 不需要任何图片资源与第三方图标库，只靠 path 数据。
 *
 * <h2>颜色由 CSS 决定</h2>
 * <p>图标描边不在这里硬编码色值，而是通过样式类交给 {@code app.css} 的
 * 设计令牌（{@code .icon}、{@code .icon-accent} 等）。这样换主题、
 * 调令牌时图标会跟着走，不必改 Java 代码。
 *
 * <h2>尺寸与描边</h2>
 * <p>path 数据按 24 x 24 书写，渲染时整体缩放到目标尺寸。
 * 描边宽度按尺寸等比计算（约 {@code size * 0.095}，下限 1.25px），
 * 再除以缩放系数补偿 —— 否则小图标会被缩成糊线，大图标会显得过细。
 */
public final class Icons {

    /** path 数据使用的设计网格尺寸。 */
    private static final double GRID = 24.0;

    /** 描边宽度占图标尺寸的比例。调它等于整体调粗/调细所有图标。 */
    private static final double STROKE_RATIO = 0.095;

    /** 描边宽度下限，避免小尺寸下细到看不见。 */
    private static final double STROKE_MIN = 1.25;

    private Icons() {
    }

    // ==================== path 数据 ====================

    /** 未知图标时的兜底：一个实心圆点，明显但不刺眼。 */
    public static final String DOT = "M14 12a2 2 0 1 0-4 0a2 2 0 1 0 4 0";

    private static final Map<String, String> PATHS = Map.ofEntries(
            // ---------- 功能图标 ----------
            Map.entry("clock", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0 M12 7.5V12l3 2"),
            Map.entry("repeat", "M17 2l4 4-4 4 M3 11V9a4 4 0 0 1 4-4h14 M7 22l-4-4 4-4 "
                    + "M21 13v2a4 4 0 0 1-4 4H3"),
            Map.entry("braces", "M8 3H7a2 2 0 0 0-2 2v4a2 2 0 0 1-2 2a2 2 0 0 1 2 2v4"
                    + "a2 2 0 0 0 2 2h1 M16 3h1a2 2 0 0 1 2 2v4a2 2 0 0 0 2 2a2 2 0 0 0-2 2"
                    + "v4a2 2 0 0 1-2 2h-1"),
            Map.entry("code", "M9 6l-6 6 6 6 M15 6l6 6-6 6"),
            Map.entry("hash", "M4 9h16 M4 15h16 M10 3L8 21 M16 3l-2 18"),
            Map.entry("type", "M4 7V4h16v3 M9 20h6 M12 4v16"),
            Map.entry("globe", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0 M3 12h18 "
                    + "M12 3c-2.7 3.6-2.7 14.4 0 18 M12 3c2.7 3.6 2.7 14.4 0 18"),
            Map.entry("file", "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z "
                    + "M14 3v5h5"),
            Map.entry("image", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z "
                    + "M10.1 8.5a1.6 1.6 0 1 0-3.2 0a1.6 1.6 0 1 0 3.2 0 "
                    + "M21 15l-4.5-4.5L5 21"),
            Map.entry("qr", "M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h3v3h-3z "
                    + "M20 14v2 M14 20h3 M20 19v1"),

            // ---------- 分类图标 ----------
            Map.entry("switch", "M8 3L4 7l4 4 M4 7h16 M16 21l4-4-4-4 M20 17H4"),
            Map.entry("sparkle", "M12 3l2.2 6.8L21 12l-6.8 2.2L12 21l-2.2-6.8L3 12l6.8-2.2z"),
            Map.entry("grid", "M4 4h6v6H4z M14 4h6v6h-6z M4 14h6v6H4z M14 14h6v6h-6z"),
            Map.entry("layers", "M12 3l9 5-9 5-9-5 9-5z M3 13l9 5 9-5"),

            // ---------- 界面图标 ----------
            Map.entry("search", "M4 11a7 7 0 1 0 14 0a7 7 0 1 0-14 0 M20 20l-4-4"),
            Map.entry("chevron-down", "M6 9l6 6 6-6"),
            Map.entry("chevron-right", "M9 6l6 6-6 6"),
            Map.entry("arrow-left", "M19 12H5 M12 19l-7-7 7-7"),
            Map.entry("arrow-right", "M5 12h14 M13 5l7 7-7 7"),
            Map.entry("arrow-up", "M12 19V5 M5 12l7-7 7 7"),
            Map.entry("check", "M20 6L9 17l-5-5"),
            Map.entry("plus", "M12 5v14 M5 12h14"),
            Map.entry("x", "M6 6l12 12 M18 6L6 18"),
            Map.entry("copy", "M9 8h9a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2z "
                    + "M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"),
            Map.entry("eye", "M2 12s3.5-7 10-7s10 7 10 7s-3.5 7-10 7s-10-7-10-7z "
                    + "M15 12a3 3 0 1 0-6 0a3 3 0 1 0 6 0"),
            Map.entry("external", "M7 17L17 7 M8 7h9v9"),
            Map.entry("flip", "M12 3v18 M7 7l-4 5 4 5 M17 7l4 5-4 5"),
            Map.entry("folder", "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"),
            Map.entry("info", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0 M12 11v5 "
                    + "M13 8a1 1 0 1 0-2 0a1 1 0 1 0 2 0"),
            Map.entry("warn", "M12 3l9.5 17H2.5z M12 9v5 M13 17.5a1 1 0 1 0-2 0a1 1 0 1 0 2 0"),
            Map.entry("sliders", "M4 6h10 M18 6h2 M4 12h4 M12 12h8 M4 18h12 M20 18h0 "
                    + "M17 8.5a2.5 2.5 0 1 0-5 0a2.5 2.5 0 1 0 5 0 "
                    + "M11 14.5a2.5 2.5 0 1 0-5 0a2.5 2.5 0 1 0 5 0"));
    /**
     * 模块 id 到图标名的映射。
     *
     * <p>这是一张<b>过渡期对照表</b>：新增模块时更推荐直接重写
     * {@link ToolModule#iconName()}，本表只是让既有模块无需逐个改动即可获得图标。
     * 表里查不到的模块会回退到所属分类的图标，因此新模块不会变成空白。
     */
    private static final Map<String, String> BY_MODULE_ID = Map.ofEntries(
            Map.entry("timestamp", "clock"),
            Map.entry("cron", "repeat"),
            Map.entry("json-format", "braces"),
            Map.entry("encode", "code"),
            Map.entry("uuid", "hash"),
            Map.entry("text-tools", "type"),
            Map.entry("http", "globe"),
            Map.entry("file-hash", "file"),
            Map.entry("image-tools", "image"),
            Map.entry("qrcode", "qr"));

    // ==================== 对外 API ====================

    /**
     * 按名字取图标。
     *
     * @param name       图标名，见本类的 path 常量名；未知名字回退为圆点
     * @param size       图标边长（px），推荐 14 / 15 / 16 / 19
     * @param colorClass 颜色样式类，可为 null。可选 {@code icon-accent} /
     *                   {@code icon-muted} / {@code icon-subtle} / {@code icon-invert}
     * @return 一个边长固定的居中容器，可直接放进任何布局
     */
    public static Node of(String name, double size, String colorClass) {
        return render(PATHS.getOrDefault(name == null ? "" : name, DOT), size, colorClass);
    }

    /** 按名字取图标，使用默认的中性色。 */
    public static Node of(String name, double size) {
        return of(name, size, null);
    }

    /** 取某个功能模块的图标。 */
    public static Node forModule(ToolModule module, double size, String colorClass) {
        String name = module.iconName();
        if (name == null || name.isBlank()) {
            name = BY_MODULE_ID.get(module.id());
        }
        if (name == null) {
            name = module.category().iconName();
        }
        return of(name, size, colorClass);
    }

    /** 取某个分类的图标。 */
    public static Node forCategory(ModuleCategory category, double size, String colorClass) {
        return of(category.iconName(), size, colorClass);
    }

    /** 图标名是否已知（供探针与静态检查使用）。 */
    public static boolean exists(String name) {
        return PATHS.containsKey(name);
    }

    // ==================== 渲染 ====================

    /**
     * 把 path 数据渲染成目标尺寸的图标节点。
     *
     * <p>缩放用节点 transform 而非改写 path 数据：描边宽度按 {@code 1/scale}
     * 补偿，保证不同尺寸下的视觉线宽一致。
     */
    private static Node render(String pathData, double size, String colorClass) {
        double scale = size / GRID;

        SVGPath path = new SVGPath();
        path.setContent(pathData);
        path.setFill(null);
        path.setStrokeWidth(Math.max(STROKE_MIN, size * STROKE_RATIO) / scale);
        path.setStrokeLineCap(StrokeLineCap.ROUND);
        path.setStrokeLineJoin(StrokeLineJoin.ROUND);
        path.getStyleClass().add("icon");
        if (colorClass != null && !colorClass.isBlank()) {
            path.getStyleClass().add(colorClass);
        }
        path.setScaleX(scale);
        path.setScaleY(scale);

        StackPane holder = new StackPane(path);
        holder.setMinSize(size, size);
        holder.setPrefSize(size, size);
        holder.setMaxSize(size, size);
        holder.setAlignment(Pos.CENTER);
        // 图标永远不拦截鼠标：卡片与侧边栏整行都是点击热区
        holder.setMouseTransparent(true);
        return holder;
    }
}
