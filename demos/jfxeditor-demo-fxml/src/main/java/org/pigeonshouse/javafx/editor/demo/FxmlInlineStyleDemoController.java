package org.pigeonshouse.javafx.editor.demo;

import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.text.Font;
import org.pigeonshouse.javafx.editor.editor.JFXEditor;
import org.pigeonshouse.javafx.editor.editor.indent.IndentStrategies;
import org.pigeonshouse.javafx.editor.syntax.JsonRegexHighlighter;
import org.pigeonshouse.javafx.editor.syntax.TreeSitterHighlighter;

/**
 * {@code fxml-inline-style-demo.fxml} 的控制器。
 *
 * <p>视觉样式完全由 FXML 标签上的内联 CSS 决定，控制器只负责
 * 无法在 FXML 中声明的部分：示例文本、语法高亮器、控件与编辑器
 * 属性的绑定，以及状态栏刷新。</p>
 */
public class FxmlInlineStyleDemoController {

    private static final String JAVA_SAMPLE = """
            package org.pigeonshouse.demo;

            import java.util.List;

            /**
             * Ocean 配色示例：注解、泛型、常量、数字与字符串等 token 一览。
             */
            @FunctionalInterface
            interface Renderer<T> {
                String render(T value);
            }

            public final class OceanShowcase {

                public static final int MAX_DEPTH = 0x7F;
                private static final double SCALE = 2.5e-3;

                private final List<String> lines;

                public OceanShowcase(List<String> lines) {
                    this.lines = List.copyOf(lines);
                }

                // 控制流与运算符演示
                public String join(char sep) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < lines.size(); i++) {
                        if (i > 0) {
                            sb.append(sep);
                        }
                        sb.append(lines.get(i).strip());
                    }
                    return sb.isEmpty() ? "<empty>\\n" : sb.toString();
                }
            }
            """;

    private static final String JSON_SAMPLE = """
            {
              "theme": "sunset",
              "declaredIn": "fxml-inline-style-demo.fxml",
              "inlineStyle": true,
              "palette": {
                "background": "#2b1d16",
                "keyword": "#ff9e64",
                "string": "#e5c07b",
                "number": "#f78c6c"
              },
              "tokens": ["keyword", "string", "number", "punctuation", "delimiter"],
              "contrastRatio": 8.75,
              "maxLines": 100000,
              "fallback": null,
              "escaped": "quote:\\" tab:\\t unicode:\\u65e5\\u843d"
            }
            """;

    @FXML
    private JFXEditor oceanEditor;
    @FXML
    private JFXEditor sunsetEditor;
    @FXML
    private CheckBox gutterCheck;
    @FXML
    private CheckBox readOnlyCheck;
    @FXML
    private Spinner<Integer> fontSizeSpinner;
    @FXML
    private Label statusLabel;

    @FXML
    private void initialize() {
        oceanEditor.document().setText(JAVA_SAMPLE);
        oceanEditor.setIndentStrategy(IndentStrategies.bracket());
        oceanEditor.setHighlighter(TreeSitterHighlighter.forJava());

        sunsetEditor.document().setText(JSON_SAMPLE);
        sunsetEditor.setIndentStrategy(IndentStrategies.bracket());
        sunsetEditor.setHighlighter(JsonRegexHighlighter.create());

        oceanEditor.gutterVisibleProperty().bind(gutterCheck.selectedProperty());
        sunsetEditor.gutterVisibleProperty().bind(gutterCheck.selectedProperty());
        oceanEditor.readOnlyProperty().bind(readOnlyCheck.selectedProperty());
        sunsetEditor.readOnlyProperty().bind(readOnlyCheck.selectedProperty());

        fontSizeSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(8, 32, 14));
        fontSizeSpinner.valueProperty().addListener((obs, oldSize, newSize) -> {
            Font f = Font.font("Consolas", newSize);
            oceanEditor.setFont(f);
            sunsetEditor.setFont(f);
        });

        oceanEditor.addCaretChangeListener((line, col) -> updateStatus("Ocean/Java", line, col));
        sunsetEditor.addCaretChangeListener((line, col) -> updateStatus("Sunset/JSON", line, col));
    }

    private void updateStatus(String editorName, int line, int col) {
        statusLabel.setText(editorName + "  行 " + (line + 1) + ", 列 " + (col + 1));
    }

    /** 释放两个编辑器持有的高亮器资源（Tree-sitter 本地句柄等）。 */
    void dispose() {
        oceanEditor.setHighlighter(null);
        sunsetEditor.setHighlighter(null);
    }
}
