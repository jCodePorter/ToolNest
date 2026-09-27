package com.bytefuture.toolnest;

/**
 * {@link com.bytefuture.toolnest.ui.ContentPaneProbe} 的启动包装。
 *
 * <p>JavaFX 的 Application 子类若直接作为 main class 启动，JVM 会走模块化启动流程，
 * 在纯 classpath 下报 "JavaFX runtime components are missing"。包一层普通类即可规避，
 * 这与 {@link Launcher} 规避同类问题的手法一致。
 *
 * <p>仅用于开发期无头验证，不参与正式打包产物。
 */
public final class ContentPaneProbeLauncher {

    private ContentPaneProbeLauncher() {
    }

    public static void main(String[] args) {
        javafx.application.Application.launch(ContentPaneProbe.class, args);
    }
}
