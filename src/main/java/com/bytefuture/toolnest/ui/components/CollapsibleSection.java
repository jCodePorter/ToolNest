package com.bytefuture.toolnest.ui.components;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * 纵向「一段」：一条 36px 头带 + 可收起的正文。
 *
 * <p>用于把一页拆成几个流程阶段（例如 HTTP 批处理：请求模板 / CSV / 执行与结果）。
 * 头带从左到右是「标题 → 摘要 → 尾部控件 → 折叠指示」：
 * <ul>
 *   <li><b>摘要</b>：折叠时承担「这一段的当前值是什么」的任务 —— 因为段被收起后
 *       正文看不见了，若头带上什么都不写，折叠就等于丢失信息；</li>
 *   <li><b>尾部控件</b>：与该段强相关的常驻动作（如导出、进度），见
 *       {@link #addTrailing(Node...)}；</li>
 *   <li><b>折叠指示</b>：箭头图标，不用 Unicode 字符，与站内线性图标同一套。</li>
 * </ul>
 *
 * <p>头带整条可点击切换。{@link #setOnToggle(Consumer)} 给的回调在<b>展开之后</b>
 * 触发，供调用方做「一段展开则另一段收起」这类互斥编排；回调里再调
 * {@link #setExpanded(boolean)} 是安全的（重入由 {@code expanded} 字段兜住）。
 *
 * <p>{@link #setCollapsible(boolean)} 置 false 后箭头隐藏、点击不再切换，
 * 用于「必须常驻的一段」（如结果区），这样三段头带的视觉仍然一致。
 * 常驻段同时挡住 {@link #toggle()} 与 {@link #setExpanded(boolean)} 的收起请求 ——
 * 只挡头带点击是不够的：调用方（含布局探针）仍能把它收起来，语义就漏了。
 */
public final class CollapsibleSection extends VBox {

    private final HBox bar = new HBox(8);
    private final Label summaryLabel = new Label();
    private final Label toggleLabel = new Label();
    private final VBox body = new VBox(10);

    private boolean expanded = true;
    private boolean collapsible = true;
    private Consumer<Boolean> onToggle;

    public CollapsibleSection(String title) {
        this(title, true);
    }

    /**
     * @param startExpanded 初始是否展开。
     *
     * <p>参数名刻意不叫 {@code expanded} —— 与字段同名会让写在构造器里的
     * lambda（头带点击、{@code addTrailing}）读到参数而不是字段，见点击处理器
     * 里的注释。
     */
    public CollapsibleSection(String title, boolean startExpanded) {
        setFillWidth(true);
        // 卡片外观（底、圆角、描边、可折叠段的手型光标）全挂在 .stage-section 上，
        // 根节点不加这个类，app.css 里那组 .stage-section... 规则一条都不生效。
        getStyleClass().add("stage-section");

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("stage-bar-title");

        // 摘要要自己吃掉剩余宽度才能在长文本上出省略号：
        // Label 的 maxWidth 默认是 USE_PREF_SIZE，不显式放开会被内容撑宽。
        summaryLabel.getStyleClass().add("stage-bar-summary");
        summaryLabel.setMinWidth(0);
        summaryLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(summaryLabel, Priority.ALWAYS);

        toggleLabel.getStyleClass().add("stage-bar-toggle");

        bar.getStyleClass().add("stage-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getChildren().addAll(titleLabel, summaryLabel, toggleLabel);
        bar.setOnMouseClicked(e -> {
            if (collapsible) {
                // 这里写 this.expanded 而不是 expanded：本类早期把构造器参数
                // 也叫 expanded，而 lambda 是写在构造器里的 —— 于是它读到的
                // 是那个参数（true），不是字段。后果是每一段头带只会朝<b>一个
                // 方向</b>切换：① 永远只收起、② 永远只展开，点一下管用、
                // 再点就没反应，且编译期一声不响。
                // 参数已改名 startExpanded，这里再加 this. 双保险。
                setExpanded(!this.expanded);
            }
        });

        body.getStyleClass().add("stage-body");
        body.setFillWidth(true);
        // 段被外层拉高时，正文必须跟着长高三层都松（父 VGrow + 自身 maxHeight
        // 放开 + 内部可见子节点也放开），否则内容只占上半截。
        VBox.setVgrow(body, Priority.ALWAYS);
        body.setMaxHeight(Double.MAX_VALUE);

        getChildren().addAll(bar, body);
        setExpanded(startExpanded);
    }

    /** 正文容器，调用方往里 addAll 自己的内容。 */
    public VBox content() {
        return body;
    }

    /** 头带右侧、折叠箭头之前插入常驻控件（进度条、导出按钮等）。 */
    public void addTrailing(Node... nodes) {
        int at = bar.getChildren().size() - 1;
        bar.getChildren().addAll(Math.max(at, 0), Arrays.asList(nodes));
    }

    /** 折叠时显示在标题右侧的当前值摘要；传 null 或空串则清空。 */
    public void setSummary(String text) {
        summaryLabel.setText(text == null ? "" : text);
    }

    public String getSummary() {
        return summaryLabel.getText();
    }

    public boolean isExpanded() {
        return expanded;
    }

    /** 反转当前状态，等价于点击头带。常驻段（不可折叠）什么也不做。 */
    public void toggle() {
        if (!collapsible) {
            return;
        }
        setExpanded(!expanded);
    }

    /**
     * 展开 / 收起正文。收起时正文既不占位也不参与布局。
     *
     * <p>常驻段只接受展开请求：{@code setExpanded(false)} 直接丢弃，
     * 因此 {@link #isExpanded()} 对常驻段恒为 true。
     */
    public void setExpanded(boolean value) {
        if (!collapsible && !value) {
            return;
        }
        expanded = value;
        body.setVisible(value);
        body.setManaged(value);

        getStyleClass().removeAll("expanded", "collapsed");
        getStyleClass().add(value ? "expanded" : "collapsed");
        refreshToggle();

        if (onToggle != null) {
            onToggle.accept(value);
        }
    }

    /** 是否为常驻段（false 时隐藏箭头、头带点击无效）。 */
    public void setCollapsible(boolean value) {
        collapsible = value;
        toggleLabel.setVisible(value);
        toggleLabel.setManaged(value);
        refreshStyle();
    }

    /**
     * 展开状态变化回调。注意：{@link #setExpanded(boolean)} 无论值是否变化都会触发，
     * 互斥编排里应先判断 {@code value} 再决定要不要收起另一段，避免互相抖动。
     */
    public void setOnToggle(Consumer<Boolean> handler) {
        this.onToggle = handler;
    }

    private void refreshToggle() {
        toggleLabel.setGraphic(Icons.of(expanded ? "chevron-down" : "chevron-right",
                14, "icon-muted"));
        refreshStyle();
    }

    private void refreshStyle() {
        getStyleClass().removeAll("fixed", "foldable");
        getStyleClass().add(collapsible ? "foldable" : "fixed");
    }
}
