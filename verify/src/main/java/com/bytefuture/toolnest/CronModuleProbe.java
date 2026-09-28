package com.bytefuture.toolnest;

import com.bytefuture.toolnest.features.cron.CronModule;
import com.bytefuture.toolnest.features.cron.CronExpression;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.File;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Cron 模块「解析结果」的界面级验证。
 *
 * <p>验的是「用户真正看到的那些单元格」，分两层：
 * <ol>
 *   <li><b>纯逻辑层</b> —— {@link CronModule#buildNextText} 的文本投影，
 *       断言每一行都带等待时长标注且逐行递增（旧实现是「与上一次的间隔」，
 *       固定周期的表达式会渲染出 9 个一模一样的 +1 天，且首行没有标注）。</li>
 *   <li><b>界面层</b> —— 真实 {@code TableView} 的行数、列数、以及每一行
 *       「距现在」单元格的值。这是本轮从「只读 TextArea」改造成表格之后
 *       必须补上的断言：文本层的正确不能推出表格装配的正确
 *       （列顺序、取值下标、行数据来源都可能单独出错）。</li>
 * </ol>
 *
 * <p>需由 {@code CronModuleProbeLauncher} 启动。
 */
public class CronModuleProbe extends Application {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name + "  -> " + detail);
        }
    }

    private static <T extends Node> T find(Node node, Class<T> type,
                                           java.util.function.Predicate<T> filter) {
        if (type.isInstance(node) && filter.test(type.cast(node))) {
            return type.cast(node);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                T found = find(child, type, filter);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** 从一行里抠出 (…) 里那段等待时长。 */
    private static String durationOf(String line) {
        int from = line.indexOf("(+");
        if (from < 0) {
            return "";
        }
        int to = line.indexOf(')', from);
        return to < 0 ? "" : line.substring(from + 2, to);
    }

    @Override
    public void start(Stage stage) {
        Node content = new CronModule().buildContent();
        StackPane root = new StackPane(content);
        Scene scene = new Scene(root, 1300, 980);
        scene.getStylesheets().add(
                CronModuleProbe.class.getResource("/css/app.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
        root.applyCss();
        root.layout();

        System.out.println("=== Cron 解析结果 界面级验证 ===");

        // 表达式输入框（Spinner 的行编辑器 promptText 为 null，不会误命中）
        TextField expr = find(content, TextField.class,
                t -> t.getPromptText() != null && t.getPromptText().contains("例如"));
        check("找到表达式输入框", expr != null, "未找到");
        if (expr == null) {
            finish();
            return;
        }

        // 未来执行时间表格
        TableView<?> nextTable = find(content, TableView.class,
                t -> t.getStyleClass().contains("cron-next-table"));
        check("找到「未来执行时间」表格", nextTable != null, "未找到");
        if (nextTable == null) {
            finish();
            return;
        }

        // 用户报的那个表达式：每天零点。setText 会触发 textProperty 监听自动解析
        expr.setText("0 0 * * *");
        root.applyCss();
        root.layout();

        // ---------- 第一层：纯逻辑层的文本投影 ----------
        LocalDateTime now = LocalDateTime.of(2026, 9, 27, 17, 57, 0);
        List<LocalDateTime> list = CronExpression.parse("0 0 * * *").nextList(now, 3);
        String text = CronModule.buildNextText(list, now);
        String[] lines = text.split("\n");
        System.out.println("---- 文本投影 ----");
        System.out.println(text);
        System.out.println("------------------");

        check("文本投影首行 = 2026-09-28 00:00:00 周一",
                lines.length == 3 && lines[0].startsWith(" 1. 2026-09-28 00:00:00  周一"),
                lines.length > 0 ? lines[0] : "空");
        check("文本投影首行也标注等待时长（+6 小时 3 分）",
                lines.length > 0 && lines[0].contains("(+6 小时 3 分)"),
                lines.length > 0 ? lines[0] : "空");
        check("文本投影次行为距现在的 1 天 6 小时，而非与上次的间隔 +1 天",
                lines.length > 1 && lines[1].contains("(+1 天 6 小时)"),
                lines.length > 1 ? lines[1] : "空");

        // ---------- 第二层：真实表格 ----------
        check("表格列出 10 行（预览条数默认 10）",
                nextTable.getItems().size() == 10,
                "实际 " + nextTable.getItems().size());
        check("表格为四列：序号 / 执行时间 / 星期 / 距现在",
                nextTable.getColumns().size() == 4
                        && "执行时间".equals(nextTable.getColumns().get(1).getText())
                        && "距现在".equals(nextTable.getColumns().get(3).getText()),
                nextTable.getColumns().toString());

        // 每行的「距现在」单元格都必须带 + 号，且互不相同
        Set<String> distinct = new LinkedHashSet<>();
        boolean allSigned = true;
        boolean ordered = true;
        String prev = "";
        for (Object item : nextTable.getItems()) {
            String[] row = (String[]) item;
            String duration = row[3];
            allSigned &= duration.startsWith("+");
            distinct.add(duration);
            // 时间列必须是递增的（表格按 nextList 的顺序铺，不做排序）
            if (!prev.isEmpty() && row[1].compareTo(prev) <= 0) {
                ordered = false;
            }
            prev = row[1];
        }
        check("每一行都带等待时长标注（含首行）", allSigned, "存在不带 + 号的行");
        check("各行等待时长互不相同（故为逐行递增，而非与上次的间隔）",
                distinct.size() == nextTable.getItems().size(),
                "不同值 " + distinct.size() + " / 行数 " + nextTable.getItems().size()
                        + " -> " + distinct);
        check("表格按时间升序铺开（未做二次排序）", ordered, "存在逆序行");

        // ---------- 字段说明：从文本块改为键值对结果行 ----------
        VBox describeBox = find(content, VBox.class,
                v -> v.getStyleClass().contains("result-table"));
        check("字段说明为结果行网格（0 0 * * * 是 5 段，故 5 行）",
                describeBox != null && describeBox.getChildren().size() == 5,
                describeBox == null ? "未找到" : "实际 "
                        + describeBox.getChildren().size() + " 行");

        // 6 段表达式应多出「秒」这一行 —— 证明行数确实来自 describeRows() 而非写死
        expr.setText("*/30 * * * * *");
        root.applyCss();
        root.layout();
        check("切到 6 段表达式后字段说明变为 6 行",
                describeBox != null && describeBox.getChildren().size() == 6,
                describeBox == null ? "未找到" : "实际 "
                        + describeBox.getChildren().size() + " 行");

        // 截图留档（用 6 段表达式，能同时看到「秒」行与表格）
        Platform.runLater(() -> Platform.runLater(() -> Platform.runLater(() -> {
            try {
                WritableImage img = scene.snapshot(null);
                File dir = new File("target/cron-probe");
                dir.mkdirs();
                File out = new File(dir, "cron-table.png");
                ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", out);
                check("截图已生成 " + out.getPath(), out.isFile() && out.length() > 0, "");
            } catch (Exception ex) {
                check("截图生成", false, ex.toString());
            }
            finish();
        })));
    }

    private void finish() {
        System.out.println();
        System.out.println("RESULT passed=" + passed + " failed=" + failed);
        Platform.exit();
        System.exit(failed == 0 ? 0 : 1);
    }
}
