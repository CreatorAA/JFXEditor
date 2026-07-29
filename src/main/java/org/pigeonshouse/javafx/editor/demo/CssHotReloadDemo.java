package org.pigeonshouse.javafx.editor.demo;

import javafx.animation.FadeTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.pigeonshouse.javafx.editor.editor.EditorTheme;
import org.pigeonshouse.javafx.editor.editor.JFXEditor;
import org.pigeonshouse.javafx.editor.syntax.JavaRegexHighlighter;
import org.pigeonshouse.javafx.editor.syntax.JsonRegexHighlighter;
import org.pigeonshouse.javafx.editor.syntax.SyntaxHighlighter;
import org.pigeonshouse.javafx.editor.syntax.TreeSitterHighlighter;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.function.Supplier;

/**
 * CSS 主题热加载演示程序。
 *
 * <p>展示 JFXEditor 的完全 CSS 化样式系统：四套主题（暗色 / 亮色 / 紫色 / 高对比度）
 * 以场景级样式表形式在运行时切换，所有 -editor-* 属性（含全部语法 token 配色）
 * 随主题即时生效，且切换过程不触碰文档 / 光标 / 滚动等编辑器状态。</p>
 *
 * <p>热加载：若从项目根目录启动（IDE 或 mvn javafx:run），主题 CSS 直接读取
 * {@code src/main/resources/.../editor/themes} 下的源文件，并由 WatchService 监视——
 * 运行期间编辑并保存当前主题的 CSS 文件，界面会自动重新加载生效。</p>
 */
public class CssHotReloadDemo extends Application {

    /** 内置主题 CSS 源文件目录（存在时启用文件监视热加载，否则回退到 classpath 资源）。 */
    private static final Path THEME_SOURCE_DIR = Path.of(
            "src", "main", "resources", "org", "pigeonshouse", "javafx", "editor", "editor", "themes");

    private record Theme(String displayName, EditorTheme editorTheme) {
        /** 主题 CSS 源文件名（取内置资源路径的最后一段）。 */
        String fileName() {
            String path = editorTheme.getResourcePath();
            return path.substring(path.lastIndexOf('/') + 1);
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private record LanguageOption(String displayName, Supplier<SyntaxHighlighter> highlighterFactory,
                                  String sampleText) {
        @Override
        public String toString() {
            return displayName;
        }
    }

    private static final List<Theme> THEMES = List.of(
            new Theme("暗色主题 (Dark)", EditorTheme.DARK),
            new Theme("亮色主题 (Light)", EditorTheme.LIGHT),
            new Theme("紫色主题 (Purple)", EditorTheme.PURPLE),
            new Theme("高对比度主题 (High Contrast)", EditorTheme.HIGH_CONTRAST));

    private static final String JAVA_SAMPLE = """
            package org.pigeonshouse.demo;

            import java.util.List;
            import java.util.stream.Collectors;

            /**
             * 主题演示示例类：涵盖注解、泛型、常量、数字与字符串等多种 token。
             *
             * @author JFXEditor
             * @since 1.1
             */
            @SuppressWarnings("unchecked")
            public final class ThemeShowcase<T extends Comparable<T>> {

                /** 最大重试次数。 */
                public static final int MAX_RETRIES = 3;
                private static final double GOLDEN_RATIO = 1.618_033_988;
                private static final long MASK = 0xFF00FF00L;

                private final List<T> items;
                private volatile boolean dirty = false;

                public ThemeShowcase(List<T> items) {
                    this.items = List.copyOf(items);
                }

                // 单行注释：演示控制流关键字与运算符
                public String render(int width) {
                    if (width <= 0) {
                        throw new IllegalArgumentException("width must be positive: " + width);
                    }
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < items.size(); i++) {
                        T item = items.get(i);
                        sb.append(i).append('\\t').append(item).append('\\n');
                    }
                    return switch (width % MAX_RETRIES) {
                        case 0 -> sb.toString().strip();
                        case 1 -> sb.reverse().toString();
                        default -> "ratio=" + GOLDEN_RATIO;
                    };
                }

                public List<String> names() {
                    return items.stream()
                            .map(Object::toString)
                            .filter(s -> !s.isBlank())
                            .collect(Collectors.toList());
                }
            }
            """;

    private static final String JSON_SAMPLE = """
            {
              "name": "JFXEditor",
              "version": "1.1-preview",
              "description": "A JavaFX code editor with CSS-driven theming",
              "features": {
                "syntaxHighlighting": true,
                "hotReload": true,
                "themes": ["dark", "light", "purple", "high-contrast"],
                "maxFileSize": 1048576,
                "renderScale": 1.25,
                "fallback": null
              },
              "dependencies": [
                { "id": "javafx-controls", "version": "23.0.1" },
                { "id": "tree-sitter", "version": "0.26.6" }
              ],
              "escaped": "tab:\\t newline:\\n quote:\\" unicode:\\u4f60\\u597d"
            }
            """;

    private static final String PLAIN_SAMPLE = """
            JFXEditor 主题热加载演示 —— 纯文本模式
            =====================================

            这是一段没有语法高亮的纯文本内容。
            切换上方工具栏中的语言下拉框可以体验 Java / JSON 的语法着色，
            切换主题下拉框可以在四套配色之间实时热切换：

              1. 暗色主题     —— VS Code Dark+ 风格
              2. 亮色主题     —— VS Code Light+ 风格
              3. 紫色主题     —— Shades of Purple 风格
              4. 高对比度主题 —— 纯黑底 + 高饱和前景色

            小技巧：从项目根目录启动时，直接编辑并保存
            src/main/resources/org/pigeonshouse/javafx/editor/editor/themes/ 下
            当前主题的 CSS 文件，界面会自动热加载，无需重启程序。
            """;

    private final List<LanguageOption> languages = List.of(
            new LanguageOption("Java (Tree-sitter)", TreeSitterHighlighter::forJava, JAVA_SAMPLE),
            new LanguageOption("Java (Regex)", JavaRegexHighlighter::create, JAVA_SAMPLE),
            new LanguageOption("JSON", JsonRegexHighlighter::create, JSON_SAMPLE),
            new LanguageOption("纯文本", () -> null, PLAIN_SAMPLE));

    private JFXEditor editor;
    private Scene scene;
    private BorderPane root;
    private Label statusCaretLabel;
    private Label statusThemeLabel;
    private Label statusLanguageLabel;
    private Label statusModeLabel;

    private volatile Theme currentTheme;
    private volatile long lastReloadAt;
    private WatchService watchService;
    private Path lastTempStylesheet;

    @Override
    public void start(Stage primaryStage) {
        editor = new JFXEditor();
        applyLanguage(languages.get(0));

        root = new BorderPane();
        root.setTop(buildToolBar());
        root.setCenter(editor);
        root.setBottom(buildStatusBar());

        scene = new Scene(root, 1000, 700);
        applyTheme(THEMES.get(0));
        startThemeFileWatcher();

        primaryStage.setTitle("JFXEditor - CSS 主题热加载演示");
        primaryStage.setScene(scene);
        primaryStage.show();
        editor.requestFocus();
    }

    /* ==================== 工具栏 ==================== */

    private ToolBar buildToolBar() {
        ComboBox<LanguageOption> languageCombo = new ComboBox<>();
        languageCombo.getItems().addAll(languages);
        languageCombo.getSelectionModel().selectFirst();
        languageCombo.setOnAction(e -> applyLanguage(languageCombo.getValue()));

        ComboBox<Theme> themeCombo = new ComboBox<>();
        themeCombo.getItems().addAll(THEMES);
        themeCombo.getSelectionModel().selectFirst();
        themeCombo.setOnAction(e -> applyTheme(themeCombo.getValue()));

        Spinner<Integer> fontSizeSpinner = new Spinner<>();
        fontSizeSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(8, 32, 14));
        fontSizeSpinner.setPrefWidth(70);
        fontSizeSpinner.setEditable(true);
        // 内联样式优先级高于场景样式表，字号设置在主题切换后依然保留
        fontSizeSpinner.valueProperty().addListener((obs, oldSize, newSize) ->
                editor.setStyle("-fx-font-size: " + newSize + "px;"));

        CheckBox gutterCheck = new CheckBox("显示行号");
        gutterCheck.setSelected(true);
        gutterCheck.selectedProperty().bindBidirectional(editor.gutterVisibleProperty());

        CheckBox readOnlyCheck = new CheckBox("只读模式");
        readOnlyCheck.selectedProperty().bindBidirectional(editor.readOnlyProperty());
        readOnlyCheck.selectedProperty().addListener((obs, oldVal, newVal) ->
                statusModeLabel.setText(newVal ? "只读" : "编辑"));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label hint = new Label("编辑并保存主题 CSS 源文件可触发热加载");
        hint.setOpacity(0.65);

        ToolBar toolBar = new ToolBar(
                new Label("语言:"), languageCombo,
                new Separator(),
                new Label("主题:"), themeCombo,
                new Separator(),
                new Label("字号:"), fontSizeSpinner,
                new Separator(),
                gutterCheck, readOnlyCheck,
                spacer, hint);
        toolBar.getStyleClass().add("demo-toolbar");
        return toolBar;
    }

    /* ==================== 状态栏 ==================== */

    private HBox buildStatusBar() {
        statusCaretLabel = new Label("行 1, 列 1");
        statusLanguageLabel = new Label(languages.get(0).displayName());
        statusThemeLabel = new Label(THEMES.get(0).displayName());
        statusModeLabel = new Label("编辑");

        editor.addCaretChangeListener((line, col) ->
                statusCaretLabel.setText("行 " + (line + 1) + ", 列 " + (col + 1)));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox statusBar = new HBox(16, statusCaretLabel, statusModeLabel, spacer,
                statusLanguageLabel, statusThemeLabel);
        statusBar.setPadding(new Insets(2, 4, 2, 4));
        statusBar.getStyleClass().add("demo-status-bar");
        return statusBar;
    }

    /* ==================== 语言切换 ==================== */

    private void applyLanguage(LanguageOption option) {
        editor.document().setText(option.sampleText());
        editor.setHighlighter(option.highlighterFactory().get());
        editor.gotoPosition(0, 0);
        if (statusLanguageLabel != null) {
            statusLanguageLabel.setText(option.displayName());
        }
    }

    /* ==================== 主题切换与热加载 ==================== */

    private void applyTheme(Theme theme) {
        String stylesheet = resolveStylesheet(theme);
        if (stylesheet == null) {
            return;
        }
        // 仅替换场景样式表，文档 / 光标 / 滚动等编辑器状态完全不受影响
        scene.getStylesheets().setAll(stylesheet);
        currentTheme = theme;
        lastReloadAt = System.currentTimeMillis();
        if (statusThemeLabel != null) {
            statusThemeLabel.setText(theme.displayName());
        }
        playThemeTransition();
    }

    /** 切换瞬间做一次轻微淡入，让主题过渡更平滑。 */
    private void playThemeTransition() {
        FadeTransition fade = new FadeTransition(Duration.millis(160), root);
        fade.setFromValue(0.72);
        fade.setToValue(1.0);
        fade.play();
    }

    /**
     * 解析主题样式表 URL。优先读取源码目录下的 CSS 文件（拷贝为一次性临时文件，
     * 绕过 JavaFX 按 URL 缓存已解析样式表的机制，保证热加载拿到最新内容）；
     * 源文件不存在时（如打包运行）回退到内置主题 {@link EditorTheme#getStylesheet()}。
     */
    private String resolveStylesheet(Theme theme) {
        Path source = THEME_SOURCE_DIR.resolve(theme.fileName());
        if (Files.isReadable(source)) {
            try {
                Path temp = Files.createTempFile("jfxeditor-theme-", ".css");
                Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
                temp.toFile().deleteOnExit();
                cleanupTempStylesheet();
                lastTempStylesheet = temp;
                return temp.toUri().toString();
            } catch (IOException e) {
                System.err.println("读取主题源文件失败，回退到 classpath: " + e);
            }
        }
        return theme.editorTheme().getStylesheet();
    }

    private void cleanupTempStylesheet() {
        if (lastTempStylesheet != null) {
            try {
                Files.deleteIfExists(lastTempStylesheet);
            } catch (IOException ignored) {
            }
            lastTempStylesheet = null;
        }
    }

    /** 监视主题源目录，当前主题的 CSS 文件被修改时自动重新加载。 */
    private void startThemeFileWatcher() {
        if (!Files.isDirectory(THEME_SOURCE_DIR)) {
            return;
        }
        try {
            watchService = FileSystems.getDefault().newWatchService();
            THEME_SOURCE_DIR.register(watchService,
                    StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);
        } catch (IOException e) {
            System.err.println("无法监视主题目录，热加载不可用: " + e);
            return;
        }
        Thread watcher = new Thread(this::watchLoop, "Theme-CSS-Watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void watchLoop() {
        while (true) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException | java.nio.file.ClosedWatchServiceException e) {
                return;
            }
            boolean currentChanged = false;
            for (WatchEvent<?> event : key.pollEvents()) {
                Theme theme = currentTheme;
                if (theme != null && event.context() instanceof Path changed
                        && changed.getFileName().toString().equals(theme.fileName())) {
                    currentChanged = true;
                }
            }
            key.reset();
            // 编辑器保存往往触发多个连续事件，200ms 去抖避免重复加载
            if (currentChanged && System.currentTimeMillis() - lastReloadAt > 200) {
                Platform.runLater(() -> {
                    Theme theme = currentTheme;
                    if (theme != null) {
                        applyTheme(theme);
                    }
                });
            }
        }
    }

    @Override
    public void stop() throws Exception {
        if (watchService != null) {
            watchService.close();
        }
        editor.setHighlighter(null);
        cleanupTempStylesheet();
        super.stop();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
