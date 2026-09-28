package com.bytefuture.toolnest.app;

import com.bytefuture.toolnest.core.ModuleCategory;
import com.bytefuture.toolnest.ui.spi.ToolModule;

import com.bytefuture.toolnest.features.cron.CronModule;
import com.bytefuture.toolnest.features.encode.EncodeModule;
import com.bytefuture.toolnest.features.filehash.FileHashModule;
import com.bytefuture.toolnest.features.http.HttpToolModule;
import com.bytefuture.toolnest.features.image.ImageModule;
import com.bytefuture.toolnest.features.json.JsonFormatModule;
import com.bytefuture.toolnest.features.qrcode.QrModule;
import com.bytefuture.toolnest.features.text.TextModule;
import com.bytefuture.toolnest.features.timestamp.TimestampModule;
import com.bytefuture.toolnest.features.uuid.UuidModule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 全局功能注册表 —— 应用的组合根（composition root）。
 *
 * <p>这是「新增功能只需改一个地方」的那个地方。所有模块在这里集中登记，
 * 侧边栏与内容区都从本表读取，因此新增功能不会牵动任何界面代码。
 *
 * <p><b>它为什么住在 app 层而不是 core 层</b>：本类必须 import 全部具体功能模块，
 * 是整个应用中依赖面最广的类。把它放在 core 会让最内层的领域包反向依赖
 * 最外层的全部实现（依赖倒置违规）—— 每加一个工具都要改动 core。
 * 放在 app 层则符合「只有组合根知道具体实现」的原则：core 保持纯净，
 * app 成为唯一允许 aware-of-everything 的地方。
 */
public final class ToolRegistry {

    private static final List<ToolModule> MODULES = new ArrayList<>();

    private ToolRegistry() {
    }

    /** 注册一个功能模块。 */
    public static void register(ToolModule module) {
        if (module == null) {
            return;
        }
        boolean duplicated = MODULES.stream().anyMatch(m -> m.id().equals(module.id()));
        if (duplicated) {
            throw new IllegalStateException("功能模块 id 重复: " + module.id());
        }
        MODULES.add(module);
    }

    /** 全部已注册模块，保持注册顺序。 */
    public static List<ToolModule> all() {
        return Collections.unmodifiableList(MODULES);
    }

    /** 按分组归类，分组之间保持枚举声明顺序。 */
    public static Map<ModuleCategory, List<ToolModule>> grouped() {
        Map<ModuleCategory, List<ToolModule>> map = new LinkedHashMap<>();
        for (ModuleCategory category : ModuleCategory.values()) {
            List<ToolModule> inCategory = MODULES.stream()
                    .filter(m -> m.category() == category)
                    .collect(Collectors.toList());
            if (!inCategory.isEmpty()) {
                map.put(category, inCategory);
            }
        }
        return map;
    }

    /** 按 id 查找模块。 */
    public static Optional<ToolModule> byId(String id) {
        return MODULES.stream().filter(m -> m.id().equals(id)).findFirst();
    }

    /** 某个分类下的全部模块，保持注册顺序。 */
    public static List<ToolModule> byCategory(ModuleCategory category) {
        return MODULES.stream()
                .filter(m -> m.category() == category)
                .collect(Collectors.toList());
    }

    /** 当前实际有模块的分类，按枚举声明顺序。 */
    public static List<ModuleCategory> usedCategories() {
        return Arrays.stream(ModuleCategory.values())
                .filter(c -> !byCategory(c).isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * 注册所有功能模块 —— ★ 新增功能在这里加一行即可。
     */
    public static void bootstrap() {
        register(new TimestampModule());
        register(new CronModule());
        register(new JsonFormatModule());
        register(new EncodeModule());
        register(new UuidModule());
        register(new TextModule());
        register(new HttpToolModule());
        register(new FileHashModule());
        register(new ImageModule());
        register(new QrModule());
    }
}
