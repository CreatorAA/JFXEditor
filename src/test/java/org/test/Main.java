package org.test;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.pigeonshouse.javafx.editor.editor.EditorTheme;
import org.pigeonshouse.javafx.editor.editor.JFXEditor;
import org.pigeonshouse.javafx.editor.syntax.TreeSitterHighlighter;

/**
 * 库开发用的最小启动器。完整演示见 demos/ 下的三个独立工程。
 */
public class Main extends Application {

    @Override
    public void start(Stage stage) {
        JFXEditor editor = new JFXEditor();
        editor.document().setText("public class Demo {\n    // JFXEditor quick check\n}\n");
        editor.setHighlighter(TreeSitterHighlighter.forJava());

        Scene scene = new Scene(editor, 900, 600);
        scene.getStylesheets().setAll(EditorTheme.DARK.getStylesheet());
        stage.setTitle("JFXEditor - dev playground");
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
