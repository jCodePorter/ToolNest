package com.bytefuture.toolnest;

import com.bytefuture.toolnest.core.ModuleCategory;
import com.bytefuture.toolnest.app.ToolRegistry;
import com.bytefuture.toolnest.features.http.HttpToolModule;
import com.bytefuture.toolnest.features.json.JsonFormatModule;
import com.bytefuture.toolnest.features.timestamp.TimestampModule;
import com.bytefuture.toolnest.ui.shell.MainWindow;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.util.Duration;
import atlantafx.base.theme.PrimerLight;

import javax.imageio.ImageIO;
import java.io.File;

/**
 * 设计验收用的离屏渲染器（不参与打包产物，仅用于生成视觉评审截图）。
 *
 * <p>以真实主题、真实样式表、真实窗口尺寸构建界面，把若干状态分别渲染成 PNG，
 * 供人眼检查层级、间距、对齐与配色 —— 比只读 CSS 更接近真实效果。
 *
 * <p>用法：{@code java -cp <fat-jar> com.bytefuture.toolnest.DesignPreview [输出目录]}
 */
public class DesignPreview {

    private static final double WIDTH = 1280;
    private static final double HEIGHT = 800;

    private static File outDir = new File("build/ui-preview");

    /**
     * 入口放在普通类里，而不是继承 {@link Application}。
     *
     * <p>JavaFX 会检查主类是否为 {@code Application} 子类；若直接以它启动，
     * 在 classpath 模式（无 module-path）下会报「缺少 JavaFX 运行时组件」。
     * 因此与产品入口 {@code Launcher} 一样，用一个普通类转交。
     */
    public static void main(String[] args) {
        if (args.length > 0) {
            outDir = new File(args[0]);
        }
        Application.launch(PreviewApp.class, args);
    }

    /** 实际的渲染流程。 */
    public static class PreviewApp extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());
        ToolRegistry.bootstrap();
        outDir.mkdirs();
        System.out.println("[preview] outDir=" + outDir.getAbsolutePath());

        MainWindow root = new MainWindow();
        Scene scene = new Scene(root, WIDTH, HEIGHT);
        scene.getStylesheets().add(
                DesignPreview.class.getResource("/css/app.css").toExternalForm());

        stage.setScene(scene);
        stage.setWidth(WIDTH);
        stage.setHeight(HEIGHT);
        stage.show();
        System.out.println("[preview] window shown");

        // 不用等待动画帧（无头环境下动画脉冲不可靠），
        // 直接对根节点做一次强制布局，让所有尺寸落定后再截图
        forceLayout(root);
        shoot(scene, "01-grid.png");

        // 详情页：时间戳转换
        jump(root, "timestamp");
        forceLayout(root);
        shoot(scene, "02-detail-timestamp.png");

        // 详情页：JSON 格式化（左右分栏 + 语法高亮）
        jump(root, "json-format");
        forceLayout(root);
        fillJsonDemo(scene);
        shoot(scene, "03-detail-json.png");

        // 详情页：HTTP 单一请求（最复杂的界面）
        jump(root, "http");
        forceLayout(root);
        shoot(scene, "04-detail-http.png");

        // 某分类宫格
        root.openCategory(ModuleCategory.CONVERT);
        forceLayout(root);
        shoot(scene, "05-grid-convert.png");

        // 「所有工具」：唯一会渲染分组分隔带的页面，必须单独出一张，
        // 否则分组标题的层级只能靠读 CSS 判断
        root.openAllTools();
        forceLayout(root);
        shoot(scene, "06-all-tools.png");

        // 图片处理 / 二维码：左右分栏 + 预览区，是「参数密集页」的代表，
        // 按钮层级与空态都在这两页上验证
        jump(root, "image-tools");
        forceLayout(root);
        shoot(scene, "07-detail-image.png");

        jump(root, "qrcode");
        forceLayout(root);
        shoot(scene, "08-detail-qrcode.png");

        // 结果行密集页与按钮密集页：对齐网格和按钮层级在这两页最容易露馅
        jump(root, "cron");
        forceLayout(root);
        shoot(scene, "09-detail-cron.png");

        jump(root, "encode");
        forceLayout(root);
        shoot(scene, "10-detail-encode.png");

        jump(root, "text-tools");
        forceLayout(root);
        shoot(scene, "11-detail-text.png");

        // 两栏并排：唯一「不以外壳为骨架」的界面，
        // 两栏等宽分配、栏内各自滚动都只在这一页验证。
        // 对比态要等一次 250ms 防抖重算才有内容，所以这一屏单独等一拍再截。
        jump(root, "text-diff");
        forceLayout(root);
        fillDiffDemo(scene);
        PauseTransition wait = new PauseTransition(Duration.millis(600));
        wait.setOnFinished(e -> {
            forceLayout(root);
            try {
                shoot(scene, "12-detail-diff.png");
            } catch (Exception ex) {
                System.out.println("[preview] diff shot failed: " + ex);
            }
            System.out.println("[preview] all shots done");
            System.out.flush();
            Platform.exit();
        });
        wait.play();
    }

    /** 两侧示例文本：左边是被改的服务端配置，右边是改后版本。 */
    private static final String DIFF_LEFT = """
            # 服务端配置
            host = 127.0.0.1
            port = 8080
            timeout = 30
            retry = 3
            log_level = info
            cache = false
            max_conn = 100
            db_pool = 10
            worker = 4
            enable_gzip = true
            db_url = jdbc:mysql://127.0.0.1:3306/app
            pool_min = 2
            pool_max = 20
            idle_timeout = 600
            charset = utf8mb4
            ssl_mode = disable
            read_only = false
            slow_query_ms = 500
            slow_log = /var/log/slow.log
            max_packet = 16M
            net_read_timeout = 30
            """;

    private static final String DIFF_RIGHT = """
            # 服务端配置
            host = 127.0.0.1
            port = 9090
            timeout = 60
            retry = 5
            log_level = debug
            cache = false
            tls_enabled = true
            cert_path = /etc/ssl/app.pem
            max_conn = 100
            worker = 8
            enable_gzip = true
            db_url = jdbc:mysql://127.0.0.1:3306/app
            pool_min = 2
            pool_max = 20
            idle_timeout = 600
            charset = utf8mb4
            ssl_mode = require
            read_only = false
            slow_query_ms = 500
            slow_log = /var/log/slow.log
            max_packet = 16M
            net_read_timeout = 30
            """;

    /**
     * 往文本对比页两侧填入示例文本，让截图落在「对比态」而不是两个空输入框。
     *
     * <p>两侧都填上后界面<b>自己</b>会切到对比态 —— 走的就是用户路径，
     * 不额外开后门去调内部方法。空界面截图验证不了行底配色与两栏对齐。
     */
    private void fillDiffDemo(Scene scene) {
        javafx.scene.control.TextArea left = null;
        javafx.scene.control.TextArea right = null;
        for (javafx.scene.Node node : scene.getRoot().lookupAll(".diff-editor")) {
            if (node instanceof javafx.scene.control.TextArea area) {
                if (area.getStyleClass().contains("diff-editor-left")) {
                    left = area;
                } else if (area.getStyleClass().contains("diff-editor-right")) {
                    right = area;
                }
            }
        }
        if (left == null || right == null) {
            System.out.println("[preview] warning: 未找到两个对比输入区，截图将是空状态");
            return;
        }
        left.setText(DIFF_LEFT);
        right.setText(DIFF_RIGHT);
    }

    private void jump(MainWindow window, String moduleId) {
        window.openModule(moduleId);
    }

    /**
     * 强制完成一次完整布局。
     *
     * <p>{@code applyCss} 解析样式表并计算样式类；{@code layout} 让所有
     * Region 按当前尺寸摆放子节点。这样无需等待 FX 的动画脉冲，在无头 /
     * 无显示器环境里也能得到稳定的渲染结果。
     */
    private void forceLayout(javafx.scene.Parent root) {
        root.applyCss();
        root.layout();
    }

    /**
     * 往 JSON 页填入示例内容。
     *
     * <p>空界面截图无法验证语法高亮配色、长行折行与行号栏对齐 ——
     * 这几项恰恰是设计系统里最容易出问题的部分，所以评审截图必须带真实数据。
     * 截图用的示例数据仅用于视觉评审，不是产品功能的一部分。
     */
    private void fillJsonDemo(Scene scene) {
        String sample = """
                {"orderId":"235027703726","type":"A","channel":"web",""" 
                + """
                "amount":1299.00,"paid":true,"items":[{"sku":"SKU-001","qty":2}]}""";

        // 输入区：场景里唯一的可编辑文本区
        for (javafx.scene.Node node : scene.getRoot().lookupAll(".text-area")) {
            if (node instanceof javafx.scene.control.TextArea area && area.isEditable()) {
                area.setText(sample);
                return;
            }
        }
        System.out.println("[preview] warning: 未找到 JSON 输入区，截图将为空状态");
    }

    private void shoot(Scene scene, String name) throws Exception {
        System.out.println("[preview] snapshot " + name + " ...");
        WritableImage image = scene.snapshot(null);
        File file = new File(outDir, name);
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file);
        System.out.println("[preview] wrote " + file.getName()
                + "  (" + (int) image.getWidth() + "x" + (int) image.getHeight() + ")");
        System.out.flush();
    }
    }
}
