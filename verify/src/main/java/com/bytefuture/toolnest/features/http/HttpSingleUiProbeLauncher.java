package com.bytefuture.toolnest.features.http;

import javafx.application.Application;

/**
 * {@link HttpSingleUiProbe} 的启动包装。
 *
 * <p>JavaFX 会检查主类是否为 {@code Application} 子类；classpath 模式下直接以
 * Application 子类启动会报「缺少 JavaFX 运行时组件」。用普通类转交即可绕开，
 * 与产品入口 {@code Launcher} 的做法一致。
 */
public final class HttpSingleUiProbeLauncher {

    private HttpSingleUiProbeLauncher() {
    }

    public static void main(String[] args) {
        Application.launch(HttpSingleUiProbe.class, args);
    }
}
