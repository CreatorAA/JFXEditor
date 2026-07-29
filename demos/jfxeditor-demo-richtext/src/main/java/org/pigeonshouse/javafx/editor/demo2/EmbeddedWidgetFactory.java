package org.pigeonshouse.javafx.editor.demo2;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.pigeonshouse.javafx.editor.editor.JFXEditor;
import org.pigeonshouse.javafx.editor.editor.decoration.Decoration;
import org.pigeonshouse.javafx.editor.editor.decoration.TextDecorationStyle;
import org.pigeonshouse.javafx.editor.syntax.JsonRegexHighlighter;
import org.pigeonshouse.javafx.editor.syntax.TreeSitterHighlighter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * 标记 → 卡片节点的工厂。文件类路径相对 baseDir 解析；
 * 文件缺失、图片损坏一律降级为卡片内提示，不抛异常。
 */
public final class EmbeddedWidgetFactory {

    private static final double CARD_WIDTH = 640;
    private static final double VIEWER_LINE_HEIGHT = 22;
    private static final double VIEWER_MAX_HEIGHT = 300;

    private static final String CARD_STYLE = """
            -fx-background-color: #1b2330;
            -fx-background-radius: 8;
            -fx-border-color: #3d4a5c;
            -fx-border-radius: 8;
            -fx-border-width: 1;
            """;
    private static final String TITLE_STYLE = "-fx-text-fill: #8ab4f8; -fx-font-size: 12px; -fx-font-weight: bold;";
    private static final String ERROR_STYLE = "-fx-text-fill: #f28b82; -fx-font-size: 12px;";

    /** 文件类标记的相对路径基准目录。 */
    private final Path baseDir;
    private final Consumer<String> linkOpener;

    public EmbeddedWidgetFactory(Path baseDir, Consumer<String> linkOpener) {
        this.baseDir = baseDir;
        this.linkOpener = linkOpener;
    }

    public Node create(MarkupTag tag) {
        return switch (tag.name()) {
            case "fileindex" -> createFileIndex(tag);
            case "img" -> createImage(tag);
            case "showFile" -> createShowFile(tag);
            case "link" -> createLink(tag);
            case "table" -> createTable(tag);
            case "list" -> createList(tag);
            default -> card("未知标记", new Label(tag.raw()));
        };
    }

    // ==================== fileindex ====================

    private Node createFileIndex(MarkupTag tag) {
        String src = tag.attr("src");
        int line = tag.intAttr("line", 1);
        int col = tag.intAttr("col", 1);
        String showType = tag.attr("showType") == null ? "ERROR" : tag.attr("showType");
        String title = "⚠ " + fileName(src) + " · " + showType + " @ " + line + ":" + col;

        Path file = baseDir.resolve(src);
        if (!Files.isRegularFile(file)) {
            return card(title, errorLabel("文件不存在：" + file));
        }
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            return card(title, errorLabel("读取失败：" + e.getMessage()));
        }

        JFXEditor viewer = createViewer(content, src);
        int line0 = Math.min(line - 1, Math.max(0, viewer.document().getLineCount() - 1));
        int lineLength = viewer.document().getLineCount() == 0
                ? 0 : viewer.document().getLineLength(line0);
        int startCol = Math.min(col - 1, Math.max(0, lineLength - 1));

        Color markColor = switch (showType) {
            case "WARNING" -> Color.web("#f0b429");
            case "INFO" -> Color.web("#58a6ff");
            default -> Color.web("#f25555");
        };
        viewer.decorationModel().addDecorations(List.of(
                Decoration.lineBackground(line0, markColor.deriveColor(0, 1, 1, 0.14)),
                Decoration.textUnderline(line0, startCol, lineLength,
                        TextDecorationStyle.WAVY, markColor),
                Decoration.gutterIcon(line0, "●", markColor),
                Decoration.afterText(line0, "   ← " + showType)));

        viewer.setPrefHeight(220);
        // 等 Skin 装好再导航，否则滚不到位
        Platform.runLater(() -> viewer.gotoPosition(line0, Math.max(0, col - 1)));
        return card(title, viewer);
    }

    // ==================== img ====================

    private Node createImage(MarkupTag tag) {
        String src = tag.attr("src");
        double width = tag.intAttr("width", 320);
        double height = tag.intAttr("height", 200);
        String title = "🖼 " + fileName(src) + " (" + (int) width + "×" + (int) height + ")";

        Path file = baseDir.resolve(src);
        if (!Files.isRegularFile(file)) {
            return card(title, errorLabel("图片不存在：" + file));
        }
        Image image = new Image(file.toUri().toString(), width, height, true, true);
        if (image.isError()) {
            return card(title, errorLabel("图片加载失败：" + src));
        }
        ImageView view = new ImageView(image);
        view.setPreserveRatio(true);
        return card(title, view);
    }

    // ==================== showFile ====================

    /** startLine..endLine 为 1 起闭区间。 */
    private Node createShowFile(MarkupTag tag) {
        String src = tag.attr("src");
        int startLine = tag.intAttr("startLine", 1);
        int endLine = tag.intAttr("endLine", startLine);
        String title = "📄 " + fileName(src) + " · L" + startLine + "–L" + endLine;

        Path file = baseDir.resolve(src);
        if (!Files.isRegularFile(file)) {
            return card(title, errorLabel("文件不存在：" + file));
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            return card(title, errorLabel("读取失败：" + e.getMessage()));
        }
        int from = Math.min(startLine - 1, lines.size());
        int to = Math.min(endLine, lines.size());
        String segment = String.join("\n", lines.subList(from, to));

        JFXEditor viewer = createViewer(segment, src);
        int shown = Math.max(1, to - from);
        viewer.setPrefHeight(Math.min(VIEWER_MAX_HEIGHT, shown * VIEWER_LINE_HEIGHT + 16));
        return card(title, viewer);
    }

    // ==================== link ====================

    private Node createLink(MarkupTag tag) {
        String href = tag.attr("href");
        Hyperlink hyperlink = new Hyperlink("🔗 " + tag.attr("text"));
        hyperlink.setStyle("-fx-text-fill: #58a6ff; -fx-font-size: 13px; -fx-border-color: transparent;");
        hyperlink.setOnAction(e -> linkOpener.accept(href));
        Label hrefLabel = new Label(href);
        hrefLabel.setStyle("-fx-text-fill: #6e7681; -fx-font-size: 11px;");
        HBox box = new HBox(8, hyperlink, hrefLabel);
        box.setStyle("-fx-alignment: center-left;");
        VBox card = card(null, box);
        card.setPrefWidth(Math.min(CARD_WIDTH, 480));
        return card;
    }

    // ==================== table ====================

    /** headers 按逗号、rows 按“|”分行再按逗号分列。 */
    private Node createTable(MarkupTag tag) {
        String[] headers = tag.attr("headers").split(",", -1);
        String[] rows = tag.attr("rows").split("\\|", -1);

        GridPane grid = new GridPane();
        for (int c = 0; c < headers.length; c++) {
            grid.add(cell(headers[c].trim(), true), c, 0);
        }
        for (int r = 0; r < rows.length; r++) {
            String[] cols = rows[r].split(",", -1);
            for (int c = 0; c < headers.length; c++) {
                String value = c < cols.length ? cols[c].trim() : "";
                grid.add(cell(value, false), c, r + 1);
            }
        }
        return card("▦ 表格 (" + rows.length + " 行 × " + headers.length + " 列)", grid);
    }

    private Label cell(String text, boolean header) {
        Label label = new Label(text);
        label.setPadding(new Insets(4, 12, 4, 12));
        label.setMaxWidth(Double.MAX_VALUE);
        label.setStyle(header
                ? "-fx-text-fill: #cfe3f5; -fx-font-weight: bold; -fx-background-color: #2c3a4f;"
                + " -fx-border-color: #3d4a5c; -fx-border-width: 0 1 1 0;"
                : "-fx-text-fill: #aeb9c8; -fx-border-color: #3d4a5c; -fx-border-width: 0 1 1 0;");
        GridPane.setHgrow(label, Priority.ALWAYS);
        return label;
    }

    // ==================== list ====================

    private Node createList(MarkupTag tag) {
        boolean ordered = "ordered".equals(tag.attr("type"));
        String[] items = tag.attr("items").split("\\|", -1);

        VBox box = new VBox(4);
        for (int i = 0; i < items.length; i++) {
            String prefix = ordered ? (i + 1) + ". " : "• ";
            Label label = new Label(prefix + items[i].trim());
            label.setStyle("-fx-text-fill: #aeb9c8; -fx-font-size: 13px;");
            box.getChildren().add(label);
        }
        String title = (ordered ? "有序" : "无序") + "列表 (" + items.length + " 项)";
        return card("☰ " + title, box);
    }

    // ==================== 公共构件 ====================

    /** 只读内嵌编辑器，按扩展名挂高亮。 */
    private JFXEditor createViewer(String content, String src) {
        JFXEditor viewer = new JFXEditor();
        viewer.setReadOnly(true);
        viewer.document().setText(content);
        if (src.endsWith(".java")) {
            viewer.setHighlighter(TreeSitterHighlighter.forJava());
        } else if (src.endsWith(".json")) {
            viewer.setHighlighter(JsonRegexHighlighter.create());
        }
        return viewer;
    }

    private VBox card(String title, Node content) {
        VBox card = new VBox(6);
        card.setPadding(new Insets(8, 10, 8, 10));
        card.setStyle(CARD_STYLE);
        card.setPrefWidth(CARD_WIDTH);
        if (title != null) {
            Label titleLabel = new Label(title);
            titleLabel.setStyle(TITLE_STYLE);
            card.getChildren().add(titleLabel);
        }
        if (content instanceof JFXEditor viewer) {
            VBox.setVgrow(viewer, Priority.ALWAYS);
        }
        card.getChildren().add(content);
        return card;
    }

    private Label errorLabel(String message) {
        Label label = new Label("✖ " + message);
        label.setStyle(ERROR_STYLE);
        label.setWrapText(true);
        return label;
    }

    private static String fileName(String src) {
        int slash = Math.max(src.lastIndexOf('/'), src.lastIndexOf('\\'));
        return slash >= 0 ? src.substring(slash + 1) : src;
    }
}
