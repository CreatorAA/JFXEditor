package org.pigeonshouse.javafx.editor.demo;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.pigeonshouse.javafx.editor.editor.JFXEditor;
import org.pigeonshouse.javafx.editor.editor.indent.IndentStrategies;

import java.io.IOException;
import java.util.Objects;

/**
 * FXML 内联 CSS 样式演示程序。
 *
 * <p>界面结构与全部样式均声明在 {@code fxml-inline-style-demo.fxml} 中：
 * 两个 JFXEditor 通过标签上的 &lt;style&gt; 元素各自携带一整套内联
 * -editor-* 配置（Ocean / Sunset 配色），不依赖任何外部样式表。</p>
 *
 * <p>启动方式：本工程根目录下 {@code mvn javafx:run}</p>
 */
public class FxmlInlineStyleDemo extends Application {

    private FxmlInlineStyleDemoController controller;

    @Override
    public void start(Stage primaryStage) throws IOException {
        FXMLLoader loader = new FXMLLoader(Objects.requireNonNull(
                FxmlInlineStyleDemo.class.getResource("fxml-inline-style-demo.fxml"),
                "fxml-inline-style-demo.fxml 资源缺失"));
        Parent root = loader.load();
        controller = loader.getController();

        primaryStage.setTitle("JFXEditor - FXML 内联 CSS 样式演示");
        primaryStage.setScene(new Scene(root));
        primaryStage.show();
    }

    @Override
    public void stop() {
        if (controller != null) {
            controller.dispose();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
