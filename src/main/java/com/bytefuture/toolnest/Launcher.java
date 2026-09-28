package com.bytefuture.toolnest;

import com.bytefuture.toolnest.app.ToolNestApp;

/**
 * 程序唯一入口。
 *
 * <p>刻意不继承 {@code javafx.application.Application} —— 如果把 Application 子类
 * 直接当作 mainClass，JVM 会走 JavaFX 的模块启动流程，在纯 classpath（fat jar）模式下
 * 会报 "JavaFX runtime components are missing"。多包一层普通类即可规避。
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        ToolNestApp.main(args);
    }
}
