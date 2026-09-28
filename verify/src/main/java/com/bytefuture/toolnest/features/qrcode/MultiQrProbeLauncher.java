package com.bytefuture.toolnest.features.qrcode;

import javafx.application.Application;

/**
 * {@link MultiQrProbe} 的启动包装。
 *
 * <p>JavaFX 会检查主类是否为 {@code Application} 子类；classpath 模式下
 * 直接以 Application 子类启动会报「缺少 JavaFX 运行时组件」。
 */
public final class MultiQrProbeLauncher {

    private MultiQrProbeLauncher() {
    }

    public static void main(String[] args) {
        Application.launch(MultiQrProbe.class, args);
    }
}
