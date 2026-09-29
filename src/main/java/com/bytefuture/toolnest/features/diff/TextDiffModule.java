package com.bytefuture.toolnest.features.diff;

import com.bytefuture.toolnest.core.ModuleCategory;
import com.bytefuture.toolnest.ui.spi.ToolModule;
import javafx.scene.Node;

/**
 * 文本对比工具。
 *
 * <p>与「文本处理」是同一层的两个不同问题：那边是「把一批文本改一改」，
 * 这边是「看清两份文本差在哪里」。因此它不做任何变换，只做对齐与标注。
 *
 * <p>两栏的取舍：左侧原文、右侧修改后，<b>差异画在两侧的行上</b>。
 * 判断「这处改得对不对」的锚点永远在原文与结果上，中间再插一列统计或清单，
 * 只会让眼睛在两份文本之外多一次跳转；「有多少」交给底部状态条一句话说完。
 *
 * <p>三类差异各有独立展现：「修改」被合成为第三类（行级配对 + 行内词级比对），
 * 而不是拆成「删一行 + 加一行」让用户自己去认。
 *
 * <p>纯逻辑在 {@link DiffOps}（零 JavaFX），界面在 {@link DiffView}。
 */
public class TextDiffModule implements ToolModule {

    @Override
    public String id() {
        return "text-diff";
    }

    @Override
    public String displayName() {
        return "文本对比";
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.TEXT;
    }

    @Override
    public String summary() {
        return "左原文、右修改后，逐行标出新增 / 删除 / 修改";
    }

    @Override
    public String description() {
        return "两侧按行严格对齐，差异分色标注；可按词或按字符标记行内变化，并能导出标准补丁文本";
    }

    @Override
    public String iconName() {
        return "diff";
    }

    @Override
    public String keywords() {
        return "diff 对比 比较 差异 文本 补丁 patch 新增 删除 修改 逐行 compare";
    }

    @Override
    public Node buildContent() {
        return new DiffView().build();
    }
}
