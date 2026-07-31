package org.pigeonshouse.javafx.editor.demo3;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.ToolBar;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.pigeonshouse.javafx.editor.core.model.Position;
import org.pigeonshouse.javafx.editor.editor.JFXEditor;
import org.pigeonshouse.javafx.editor.syntax.TreeSitterHighlighter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * 文档缩略图（minimap）演示程序。
 *
 * 语法着色色块、视口指示框实时联动、点击 / 拖动跳转；
 * 「导出 PNG」按钮演示离屏生成整篇文档的静态缩略图。
 * 切换行数规模可验证大文档下抽样绘制的性能表现。</p>
 *
 * <p>编辑区额外演示 Alt+左键拖拽建列块多光标：基于
 * {@link JFXEditor#hitTest} 与 {@link JFXEditor#selectColumnBlock}
 * 两个公开 API，通过事件过滤器实现，不侵入皮肤默认交互。</p>
 */
public class MinimapDemo extends Application {

    private record SizeOption(String displayName, int classCount) {
        @Override
        public String toString() {
            return displayName;
        }
    }

    /** 每个生成的示例类约 27 行。 */
    private static final List<SizeOption> SIZES = List.of(
            new SizeOption("约 500 行", 18),
            new SizeOption("约 5,000 行", 180),
            new SizeOption("约 50,000 行", 1800));

    private JFXEditor editor;
    private MinimapView minimap;
    private Label statusLabel;

    /** Alt+左键拖拽多光标的锚点（列为虚拟列，可超出行长）；非 null 表示拖拽进行中。 */
    private Position multiCaretAnchor;

    @Override
    public void start(Stage primaryStage) {
        editor = new JFXEditor();
        editor.setHighlighter(TreeSitterHighlighter.forJava());
        installMultiCaretDrag();
        applySize(SIZES.get(0));

        minimap = new MinimapView(editor);
        HBox center = new HBox(editor, minimap);
        HBox.setHgrow(editor, Priority.ALWAYS);
        editor.setMaxHeight(Double.MAX_VALUE);
        minimap.prefHeightProperty().bind(center.heightProperty());

        editor.insertText("    ");

        BorderPane root = new BorderPane();
        root.setTop(buildToolBar(primaryStage));
        root.setCenter(center);
        root.setBottom(buildStatusBar());

        primaryStage.setTitle("JFXEditor - 文档缩略图演示");
        primaryStage.setScene(new Scene(root, 1100, 720));
        primaryStage.show();
    }

    private ToolBar buildToolBar(Stage stage) {
        ComboBox<SizeOption> sizeCombo = new ComboBox<>();
        sizeCombo.getItems().addAll(SIZES);
        sizeCombo.getSelectionModel().selectFirst();
        sizeCombo.setOnAction(e -> applySize(sizeCombo.getValue()));

        CheckBox wrapCheck = new CheckBox("软换行");
        wrapCheck.selectedProperty().bindBidirectional(editor.wrapTextProperty());

        CheckBox highlightCheck = new CheckBox("语法高亮");
        highlightCheck.setSelected(true);
        highlightCheck.selectedProperty().addListener((obs, oldVal, newVal) ->
                editor.setHighlighter(newVal ? TreeSitterHighlighter.forJava() : null));

        Button exportButton = new Button("导出整篇文档缩略图 PNG");
        exportButton.setOnAction(e -> exportPng(stage));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label hint = new Label("点击 / 拖动右侧缩略图可跳转；Alt+左键拖拽建多光标");
        hint.setOpacity(0.65);

        return new ToolBar(
                new Label("文档规模:"), sizeCombo,
                new Separator(),
                wrapCheck, highlightCheck,
                new Separator(),
                exportButton,
                spacer, hint);
    }

    private HBox buildStatusBar() {
        statusLabel = new Label();
        Label caretLabel = new Label("行 1, 列 1");
        editor.addCaretChangeListener((line, col) -> {
            String text = "行 " + (line + 1) + ", 列 " + (col + 1);
            if (editor.hasMultipleCarets()) {
                text += "（" + editor.allCarets().size() + " 个光标）";
            }
            caretLabel.setText(text);
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox statusBar = new HBox(16, caretLabel, spacer, statusLabel);
        statusBar.setPadding(new Insets(2, 4, 2, 4));
        return statusBar;
    }

    /**
     * Alt+左键按下记录锚点，拖拽期间持续按锚点与当前命中位置重建
     * 列块多光标（每行一个光标 / 列区间选区），并滚动跟随；拖拽事件被
     * 消费以屏蔽皮肤默认的连续选区行为。普通左键交互不受影响，
     * Alt+单击（无拖拽）仍走皮肤自带的切换额外光标语义。
     *
     * <p>列号用 {@link #virtualColumnAt} 按像素反推而非 {@code hitTest}：
     * 后者会把列鉗制到行长，在空行 / 短行上按下或经过时两列不等，
     * 垂直拖拽也会误建选区；虚拟列保证同一像素 x 恒得同一列。</p>
     */
    private void installMultiCaretDrag() {
        editor.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.isAltDown()) {
                Position hit = editor.hitTest(e.getX(), e.getY());
                if (hit != null) {
                    multiCaretAnchor = new Position(hit.line(), virtualColumnAt(e.getX(), hit.line()));
                }
            }
        });
        editor.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (e.getButton() != MouseButton.PRIMARY || multiCaretAnchor == null) {
                return;
            }
            Position hit = editor.hitTest(e.getX(), e.getY());
            if (hit != null) {
                Position pos = new Position(hit.line(), virtualColumnAt(e.getX(), hit.line()));
                editor.selectColumnBlock(multiCaretAnchor, pos);
                editor.revealPosition(hit.line(), hit.column());
            }
            e.consume();
        });
        editor.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> multiCaretAnchor = null);
    }

    /**
     * 把像素 x 反推为虚拟列（不受行长鉗制）：以 {@code locate(line, 0)}
     * 的像素原点为基准，按等宽字符宽度折算列号。
     */
    private int virtualColumnAt(double x, int line) {
        var origin = editor.locate(line, 0);
        if (origin == null) {
            return 0;
        }
        Text probe = new Text("0");
        probe.setFont(editor.font());
        double charWidth = probe.getLayoutBounds().getWidth();
        if (charWidth <= 0) {
            return 0;
        }
        return Math.max(0, (int) Math.round((x - origin.getX()) / charWidth));
    }

    private void applySize(SizeOption option) {
        editor.document().setText(generateSample(option.classCount()));
        editor.gotoPosition(0, 0);
        if (statusLabel != null) {
            statusLabel.setText(editor.document().getLineCount() + " 行");
        }
    }

    /** 生成 n 个结构相似的示例类，拼出足量行数供缩略图观察整体轮廓。 */
    private static String generateSample(int n) {
        StringBuilder sb = new StringBuilder(n * 900);
        sb.append("package org.pigeonshouse.demo.generated;\n\n")
                .append("import java.util.List;\n")
                .append("import java.util.Optional;\n\n");
        for (int i = 0; i < n; i++) {
            sb.append("""
                    /**
                     * 示例服务 #%1$d：演示缩略图中注释、关键字、字符串与数字的配色分布。
                     */
                    public class SampleService%1$d {

                        private static final int CAPACITY = %2$d;
                        private static final String NAME = "service-%1$d";

                        private final List<String> entries;

                        public SampleService%1$d(List<String> entries) {
                            this.entries = List.copyOf(entries);
                        }

                        // 查找首个匹配项，未命中时回退默认值
                        public String lookup(String key) {
                            for (String entry : entries) {
                                if (entry.startsWith(key)) {
                                    return entry;
                                }
                            }
                            return Optional.ofNullable(key).orElse(NAME) + "@" + CAPACITY;
                        }
                    }

                    """.formatted(i, (i + 1) * 16));
        }
        return sb.toString();
    }

    /** 离屏生成整篇文档缩略图并保存为 PNG。 */
    private void exportPng(Stage stage) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("保存文档缩略图");
        chooser.setInitialFileName("minimap.png");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG 图片", "*.png"));
        File file = chooser.showSaveDialog(stage);
        if (file == null) {
            return;
        }
        WritableImage image = minimap.exportImage(240, 4096);
        try {
            ImageIO.write(toBufferedImage(image), "png", file);
            statusLabel.setText("已导出 " + (int) image.getWidth() + "x" + (int) image.getHeight()
                    + " 到 " + file.getName());
        } catch (IOException ex) {
            statusLabel.setText("导出失败: " + ex.getMessage());
        }
    }

    private static BufferedImage toBufferedImage(WritableImage image) {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        int[] pixels = new int[w * h];
        image.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), pixels, 0, w);
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        bi.setRGB(0, 0, w, h, pixels, 0, w);
        return bi;
    }

    @Override
    public void stop() throws Exception {
        minimap.dispose();
        editor.setHighlighter(null);
        super.stop();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
