package com.bytefuture.toolnest.features.diff;

/**
 * {@link DiffModuleProbe} 的启动包装（Application 子类不能直接作为 main class）。
 * 仅用于开发期验证。
 */
public final class DiffModuleProbeLauncher {

    private DiffModuleProbeLauncher() {
    }

    public static void main(String[] args) {
        javafx.application.Application.launch(DiffModuleProbe.class, args);
    }
}
