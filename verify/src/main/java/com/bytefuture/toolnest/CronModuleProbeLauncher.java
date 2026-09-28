package com.bytefuture.toolnest;

/**
 * {@link CronModuleProbe} 的启动包装（Application 子类不能直接作为 main class）。
 * 仅用于开发期验证。
 */
public final class CronModuleProbeLauncher {

    private CronModuleProbeLauncher() {
    }

    public static void main(String[] args) {
        javafx.application.Application.launch(CronModuleProbe.class, args);
    }
}
