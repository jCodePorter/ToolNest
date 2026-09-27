package com.bytefuture.toolnest.ui;

import com.bytefuture.toolnest.core.ModuleCategory;
import com.bytefuture.toolnest.core.ToolModule;
import com.bytefuture.toolnest.core.ToolRegistry;
import javafx.animation.FadeTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 右侧内容区。
 *
 * <p>承载两类内容：
 * <ul>
 *   <li><b>宫格页</b>：点击侧边栏分类文件夹时，展示该分类下的工具卡片宫格</li>
 *   <li><b>详情页</b>：点击卡片或侧边栏子项时，展示的功能界面</li>
 * </ul>
 *
 * <h2>浏览历史与返回</h2>
 * <p>维护一个历史栈记录「从哪里来」，因此返回是真正逐层回退的：
 * 宫格 A → 工具 B → 宫格 C → 工具 D 时，连点返回会依次回到 C → B → A，
 * 而不是像早期实现那样只能退回「上一次的宫格」。
 *
 * <p>两个刻意的规则：
 * <ul>
 *   <li><b>回退目标不入栈</b>：返回操作本身只弹出历史，不产生新的历史项，
 *       否则会在两个页面之间来回死循环。</li>
 *   <li><b>原地重进不记史</b>：目标 key 与当前 key 相同时（重复点同一个分类）
 *       不重复入栈，避免连点造成「一次返回只退半步」的错觉。</li>
 * </ul>
 *
 * <h2>缓存与内存</h2>
 * <p>详情页与宫格页都按 key 缓存，切走再切回时保留用户输入。详情页缓存是
 * <b>有界 LRU</b>（{@link #DETAIL_CACHE_LIMIT} 项）—— 模块数量会持续增长，
 * 无上限的缓存等于只增不减的内存泄漏；被淘汰的页面下次打开会重建，
 * 代价可接受。历史栈只存 key 不存 Node，因此即使页面已被淘汰，
 * 备份时也能凭 key 重新构建出来。
 */
public class ContentPane extends StackPane {

    private static final String ALL_TOOLS_KEY = "__all__";

    private static final String CAT_PREFIX = "cat:";
    private static final String MOD_PREFIX = "mod:";

    /**
     * 详情页缓存上限。取值需大于「用户在一次会话里会来回切换的页面数」，
     * 否则刚离开的页面立刻被淘汰、返回后输入就丢了。
     */
    private static final int DETAIL_CACHE_LIMIT = 12;

    /**
     * 详情页缓存：moduleId -> Node，按访问顺序的 LRU。
     *
     * <p>用 {@link LinkedHashMap} 的 {@code accessOrder=true} 模式：
     * 每次 {@code get} 都把该项移到队尾，队首即最久未用项，淘汰它即可。
     */
    private final Map<String, Node> detailCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Node> eldest) {
            return size() > DETAIL_CACHE_LIMIT;
        }
    };

    /** 宫格页缓存：categoryName -> Node（含「所有工具」）。宫格页轻量，不设上限。 */
    private final Map<String, Node> gridCache = new LinkedHashMap<>();

    /** 浏览历史栈，存页面 key（不存 Node，见类注释）。 */
    private final Deque<String> history = new ArrayDeque<>();

    /** 当前页面 key，null 表示尚未展示任何页面。 */
    private String currentKey;

    public ContentPane() {
        getStyleClass().add("content-pane");
    }

    // ==================== 页面 key ====================

    private static String categoryKey(ModuleCategory category) {
        return CAT_PREFIX + category.name();
    }

    private static String detailKey(ToolModule module) {
        return MOD_PREFIX + module.id();
    }

    /** 标记「来自导航点击」，会记入历史。 */
    private static final class FromNav {
    }

    /** 标记「来自返回操作」，不记入历史。 */
    private static final class FromBack {
    }

    // ==================== 宫格页 ====================

    /** 展示指定分类的宫格页。 */
    public void showCategory(ModuleCategory category) {
        if (category == null) {
            return;
        }
        show(categoryKey(category), new FromNav());
    }

    /** 展示「所有工具」宫格页（按分类分段）。 */
    public void showAllTools() {
        show(ALL_TOOLS_KEY, new FromNav());
    }

    // ==================== 详情页 ====================

    /** 打开某个功能的详情页。 */
    public void openDetail(ToolModule module) {
        if (module == null) {
            return;
        }
        show(detailKey(module), new FromNav());
    }

    // ==================== 核心切换 ====================

    /**
     * 切到目标页面，并按需维护历史。
     *
     * @param key  目标页面 key
     * @param from {@code FromNav} 记入历史；{@code FromBack} 不记入
     */
    private void show(String key, Object from) {
        boolean isBack = from instanceof FromBack;
        if (!isBack && currentKey != null && !currentKey.equals(key)) {
            history.push(currentKey);
        }
        currentKey = key;
        switchTo(pageOf(key));
    }

    /** 按 key 取（或惰性构建）页面。 */
    private Node pageOf(String key) {
        if (ALL_TOOLS_KEY.equals(key)) {
            return gridPage(key, p -> p.showAll(ToolRegistry.grouped()));
        }
        if (key.startsWith(CAT_PREFIX)) {
            ModuleCategory category = parseCategory(key);
            if (category == null) {
                return missingPage("未知分类：" + key);
            }
            return gridPage(key,
                    p -> p.showCategory(category, ToolRegistry.byCategory(category)));
        }
        if (key.startsWith(MOD_PREFIX)) {
            String moduleId = key.substring(MOD_PREFIX.length());
            Node cached = detailCache.get(moduleId);
            if (cached != null) {
                return cached;
            }
            ToolModule module = ToolRegistry.byId(moduleId).orElse(null);
            if (module == null) {
                return missingPage("找不到该功能模块：" + moduleId);
            }
            Node built = buildDetail(module);
            detailCache.put(moduleId, built);
            return built;
        }
        return missingPage("未知页面：" + key);
    }

    /**
     * 取（或构建）宫格页，并同步返回按钮状态。
     *
     * <p>宫格页是缓存复用的，内容只在首次构建时生成一次，因此返回按钮的
     * 可见性必须在每次取用时重新注入 —— 它取决于「此刻历史是否非空」，
     * 而历史是随浏览不断变化的。
     */
    private Node gridPage(String key, java.util.function.Consumer<GridPanePage> render) {
        GridPanePage page = (GridPanePage) gridCache.computeIfAbsent(key, k -> {
            GridPanePage p = new GridPanePage(this::openDetail);
            render.accept(p);
            return p;
        });
        // 历史非空时宫格页也能返回；否则隐藏按钮，避免出现点了没反应的死按钮
        boolean canBack = !history.isEmpty();
        page.setOnBack(canBack ? this::goBack : null, canBack);
        return page;
    }

    /** 容错：历史里的分类名若因枚举调整而失效，不应该抛异常打断界面。 */
    private ModuleCategory parseCategory(String key) {
        try {
            return ModuleCategory.valueOf(key.substring(CAT_PREFIX.length()));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Node missingPage(String message) {
        return UiKit.pageBody(0, UiKit.caption(message));
    }

    /**
     * 构建详情页：顶部页头（返回 + 面包屑 + 功能标题）+ 功能界面。
     *
     * <p>页头承担三件事，缺一不可：
     * ① 明确的返回入口（键盘可达，保留原页面输入）；
     * ② 当前位置 —— 分类 / 功能名的完整路径；
     * ③ 功能身份 —— 醒目的标题与来源模块的一句话说明。
     */
    private Node buildDetail(ToolModule module) {
        BorderPane wrapper = new BorderPane();
        wrapper.getStyleClass().add("detail-wrapper");

        wrapper.setTop(buildDetailHeader(module));

        Node content = module.buildContent();
        VBox.setVgrow(content, Priority.ALWAYS);
        wrapper.setCenter(content);
        return wrapper;
    }

    /** 详情页页头。 */
    private VBox buildDetailHeader(ToolModule module) {
        Button back = new Button("\u2190  返回");
        back.getStyleClass().add("back-button");
        back.setOnAction(e -> goBack());
        back.setAccessibleText("返回上一页");
        // 详情页永远有确定的返回落点（goBack 内置兜底），因此按钮始终可用
        back.setVisible(true);
        back.setManaged(true);

        VBox header = UiKit.pageHeader(back, module.displayName(), null, null);

        // 面包屑插到标题下方：分类 › 功能名
        Label categoryLabel = new Label(module.category().label());
        categoryLabel.getStyleClass().add("breadcrumb");

        Label separator = new Label("\u203A");
        separator.getStyleClass().add("breadcrumb-sep");

        Label currentLabel = new Label(module.displayName());
        currentLabel.getStyleClass().add("breadcrumb-current");

        HBox crumb = new HBox(6, categoryLabel, separator, currentLabel);
        crumb.setAlignment(Pos.CENTER_LEFT);
        header.getChildren().add(crumb);

        return header;
    }

    /**
     * 返回上一页。
     *
     * <p>历史非空则弹出一项回退；为空时兜底到「第一个有内容的分类」或
     * 「所有工具」，保证返回按钮在任何状态下都有确定行为。
     */
    private void goBack() {
        if (!history.isEmpty()) {
            show(history.pop(), new FromBack());
            return;
        }
        var used = ToolRegistry.usedCategories();
        show(used.isEmpty() ? ALL_TOOLS_KEY : categoryKey(used.get(0)), new FromBack());
    }

    /** 当前是否还能返回上一层，供快捷键处理等外部逻辑判断。 */
    public boolean canGoBack() {
        return !history.isEmpty();
    }

    // ==================== 切换与动画 ====================

    private void switchTo(Node node) {
        if (getChildren().size() == 1 && getChildren().get(0) == node) {
            return;
        }
        getChildren().setAll(node);
        FadeTransition ft = new FadeTransition(Duration.millis(140), node);
        ft.setFromValue(0.25);
        ft.setToValue(1.0);
        ft.play();
    }
}
