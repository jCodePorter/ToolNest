package com.bytefuture.toolnest;

import com.bytefuture.toolnest.core.ToolRegistry;
import com.bytefuture.toolnest.ui.MainWindow;
import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.InputStream;

/**
 * JavaFX 应用主类。
 */
public class ToolNestApp extends Application {

    private static final double DEFAULT_WIDTH = 1100;
    private static final double DEFAULT_HEIGHT = 720;
    private static final double MIN_WIDTH = 820;
    private static final double MIN_HEIGHT = 560;

    @Override
    public void start(Stage stage) {
        // 现代主题皮肤（换成 NordLight / Dracula / PrimerDark 即可换肤）
        setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());

        // 注册所有功能模块
        ToolRegistry.bootstrap();

        MainWindow root = new MainWindow();
        Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        scene.getStylesheets().add(
                ToolNestApp.class.getResource("/css/app.css").toExternalForm());

        applyIcon(stage);

        stage.setTitle("ToolNest 工具巢");
        stage.setScene(scene);
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);
        stage.centerOnScreen();
        stage.show();
    }

    private void applyIcon(Stage stage) {
        try (InputStream in = ToolNestApp.class.getResourceAsStream("/icon/app.png")) {
            if (in != null) {
                stage.getIcons().add(new Image(in));
            }
        } catch (Exception ignored) {
            // 图标缺失不影响运行
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
