package org.pigeonshouse.javafx.editor.demo2;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.pigeonshouse.javafx.editor.editor.EditorTheme;
import org.pigeonshouse.javafx.editor.editor.JFXEditor;

import java.nio.file.Path;

/**
 * demo2：标记语法实时渲染为行间组件。
 *
 * <p>流程很短：文档变更 → 全量重扫标记 → overlay 按原文调和组件。
 * 标记闭合且属性合法的一瞬间出组件，改坏即消失；同一文档任意多个。
 * 运行：本工程根目录下 mvn javafx:run（文件路径按相对解析）。</p>
 */
public class Demo2App extends Application {

    private static final String SAMPLE = """
            JFXEditor 富文本增强演示（demo2）
            ================================================================
            在任意位置输入六种自闭合标记，闭合且属性合法的瞬间立即渲染为活组件；
            编辑 / 删除标记原文，组件会实时更新或消失。

            一、文件错误索引（内嵌只读编辑器 + 波浪线装饰，会自动滚动定位到 23:13）：
            我觉得这个代码有问题 <fileindex src="src/main/java/org/pigeonshouse/javafx/editor/demo2/sample/BuggySample.java" line="23" col="13" showType="ERROR"/>

            二、图片：
            <img src="imgs/demo.gif" width="480" height="260"/>

            三、文件片段（1 起闭区间行号）：
            <showFile src="src/main/java/org/pigeonshouse/javafx/editor/demo2/MarkupTag.java" startLine="1" endLine="15"/>

            四、链接（可点击，浏览器打开）：
            参考 <link href="https://github.com/CreatorAA/JFXEditor" text="JFXEditor 仓库"/>

            五、表格：
            <table headers="模块,说明" rows="core.document,文档模型与撤销重做|syntax,Tree-sitter 语法高亮|search,文本搜索引擎"/>

            六、列表：
            <list type="ordered" items="解析标记|创建组件|预留行空间|每帧定位"/>

            ================================================================
            试一试：
              1. 已渲染标记的原文会折叠成胶囊（如 ◆ img）；把光标移到该行即可展开编辑。
              2. 把某个标记的结尾 "/>" 删掉一个字符——组件立即消失；补回来立即恢复。
              3. 把 showType="ERROR" 改成 "WARNING"，索引卡片的装饰颜色随之变化。
              4. 在下面空行自己敲一个新标记，例如：
                 <list type="unordered" items="第一项|第二项"/>

            """;

    private JFXEditor editor;
    private LineWidgetOverlay overlay;
    private Label statusLabel;

    @Override
    public void start(Stage primaryStage) {
        editor = new JFXEditor();

        overlay = LineWidgetOverlay.install(editor);
        EmbeddedWidgetFactory factory = new EmbeddedWidgetFactory(
                Path.of("").toAbsolutePath(),
                url -> getHostServices().showDocument(url));

        editor.document().addDocumentListener(change -> resync(factory));
        editor.document().setText(SAMPLE);

        BorderPane root = new BorderPane();
        root.setTop(buildHeader());
        root.setCenter(overlay.getRoot());
        root.setBottom(buildStatusBar());
        refreshStatus();

        Scene scene = new Scene(root, 1200, 820);
        scene.getStylesheets().setAll(EditorTheme.DARK.getStylesheet());
        primaryStage.setTitle("JFXEditor Demo2 — 标记实时渲染为活组件");
        primaryStage.setScene(scene);
        primaryStage.show();
        editor.requestFocus();
    }

    /** 重扫标记并调和组件。 */
    private void resync(EmbeddedWidgetFactory factory) {
        overlay.sync(MarkupTagParser.parse(editor.document()), factory::create);
        refreshStatus();
    }

    private HBox buildHeader() {
        Label title = new Label("富文本增强：<fileindex/> <img/> <showFile/> <link/> <table/> <list/>");
        title.setStyle("-fx-text-fill: #cfe3f5; -fx-font-size: 13px; -fx-font-weight: bold;");
        Label hint = new Label("标记闭合即渲染 · 原文自动折叠为胶囊 · 光标入行展开");
        hint.setStyle("-fx-text-fill: #6e7681; -fx-font-size: 12px;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(12, title, spacer, hint);
        header.setPadding(new Insets(8, 12, 8, 12));
        header.setStyle("-fx-background-color: #161c26; -fx-border-color: #3d4a5c; -fx-border-width: 0 0 1 0;");
        return header;
    }

    private HBox buildStatusBar() {
        statusLabel = new Label();
        statusLabel.setStyle("-fx-text-fill: #8ab4f8; -fx-font-size: 12px;");
        HBox bar = new HBox(statusLabel);
        bar.setPadding(new Insets(6, 12, 6, 12));
        bar.setStyle("-fx-background-color: #161c26; -fx-border-color: #3d4a5c; -fx-border-width: 1 0 0 0;");
        return bar;
    }

    private void refreshStatus() {
        if (statusLabel != null) {
            statusLabel.setText("已渲染组件：" + overlay.widgetCount()
                    + "  |  文档行数：" + editor.document().getLineCount());
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
