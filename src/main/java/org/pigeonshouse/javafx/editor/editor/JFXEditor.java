package org.pigeonshouse.javafx.editor.editor;

import javafx.beans.InvalidationListener;
import javafx.beans.property.*;
import javafx.css.*;
import javafx.scene.control.Control;
import javafx.scene.control.Skin;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.util.Duration;
import org.pigeonshouse.javafx.editor.core.document.Document;
import org.pigeonshouse.javafx.editor.core.document.MemoryDocument;
import org.pigeonshouse.javafx.editor.core.model.Position;
import org.pigeonshouse.javafx.editor.core.model.TextRange;
import org.pigeonshouse.javafx.editor.editor.caret.EditorCaret;
import org.pigeonshouse.javafx.editor.editor.decoration.DecorationModel;
import org.pigeonshouse.javafx.editor.editor.indent.IndentStrategies;
import org.pigeonshouse.javafx.editor.editor.indent.IndentStrategy;
import org.pigeonshouse.javafx.editor.editor.input.KeyBindingRegistry;
import org.pigeonshouse.javafx.editor.editor.render.RenderLayer;
import org.pigeonshouse.javafx.editor.syntax.*;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * JavaFX 代码编辑器控件：公共 API 门面与状态容器（Control/Skin 模式）。
 *
 * <p>本类不含任何绘制逻辑；全部视觉与交互由 {@link JFXEditorSkin} 实现。
 * 持有文档、装饰模型、按键注册表、渲染层列表、主光标与语法高亮引擎，
 * 并注册 40 余项 CSS 元数据（默认样式类 {@code "editor"}）。</p>
 *
 * <p><strong>CSS 属性一览（节选）：</strong></p>
 * <ul>
 *   <li>{@code -editor-background} / {@code -editor-text-color} /
 *       {@code -editor-selection-color} / {@code -editor-current-line-color} /
 *       {@code -editor-caret-color} 等颜色属性；</li>
 *   <li>{@code -editor-gutter-width}、{@code -editor-line-height-multiplier}、
 *       {@code -editor-caret-width}、{@code -editor-caret-blink-rate}、
 *       {@code -editor-gutter-font-scale} 等尺寸属性；</li>
 *   <li>每个 {@link TokenType} 对应 {@code -editor-token-<名称点转横线>}
 *       （颜色）与 {@code ...-style}（值为 bold/italic/underline 组合串），
 *       语法配色完全 CSS 化。</li>
 * </ul>
 *
 * <p>伪类：{@code :read-only}（随 {@link #readOnlyProperty()}）与
 * {@code :focused}（由 Skin 的 Canvas 焦点同步）。</p>
 *
 * <p><strong>线程：</strong>涉及属性与剪贴板的方法均须在 JavaFX
 * 应用线程调用。坐标一律 0 起行/列。</p>
 *
 * <p><strong>用法示例：</strong></p>
 * <pre>{@code
 * JFXEditor editor = new JFXEditor();
 * editor.document().setText("public class Demo {}\n");
 * editor.setHighlighter(TreeSitterHighlighter.forJava());  // 启用语法高亮
 * editor.gotoPosition(0, 6);                               // 定位并滚动
 * editor.decorationModel().addDecoration(
 *         Decoration.lineBackground(0, Color.web("#332200")));
 * }</pre>
 *
 * @see JFXEditorSkin
 * @see Document
 */
public class JFXEditor extends Control {

    /** 组件版本号。 */
    public static final String VERSION = "1.4-preview";

    /** 文档模型，构造后终身不换。 */
    private final Document document;
    /** 装饰模型（线程安全）。 */
    private final DecorationModel decorationModel;
    /** 按键绑定注册表，所有预设/自定义快捷键统一经它分发。 */
    private final KeyBindingRegistry keyBindingRegistry;
    /** 渲染层活列表：直接 add/remove 即注册/注销。 */
    private final List<RenderLayer> renderLayers;
    /** 主光标。 */
    private final EditorCaret primaryCaret;
    /** 额外光标列表（多光标编辑，主光标不在其中）。 */
    private final List<EditorCaret> extraCarets;
    /** 高亮引擎；未设置高亮器时为 {@code null}。 */
    private HighlightEngine highlightEngine;
    /** 当前语法高亮器。 */
    private SyntaxHighlighter highlighter;
    /** 由 CSS token 属性拼装的主题快照。 */
    private HighlightTheme highlightTheme;

    /** gutter 可见性（非 CSS 属性，默认 true）。 */
    private final SimpleBooleanProperty gutterVisible;
    /** 只读模式（联动 {@code :read-only} 伪类，默认 false）。 */
    private final SimpleBooleanProperty readOnly;
    /** 缩进策略（非 CSS 属性，默认 BASIC；置 null 时归一化为 NONE）。 */
    private final SimpleObjectProperty<IndentStrategy> indentStrategy;
    /** 光标变化监听器列表。 */
    private final List<CaretChangeListener> caretListeners;

    /** 只读重绘计数器：自增即触发 Skin 重绘，外部通过 {@link #requestRepaint()} 驱动。 */
    private final ReadOnlyLongWrapper repaints = new ReadOnlyLongWrapper(this, "repaints", 0);

    /** 导航目标属性：{@link #gotoPosition} 写入以通知 Skin 滚动定位。 */
    private final SimpleObjectProperty<Position> navigateToPosition = new SimpleObjectProperty<>(this, "navigateToPosition");

    /** 高亮更新 → 请求重绘的桥接监听器。 */
    private final HighlightUpdateListener repaintOnHighlightUpdate = change -> requestRepaint();

    private static final String DEFAULT_STYLE_CLASS = "editor";


    private static final PseudoClass READ_ONLY_PSEUDO = PseudoClass.getPseudoClass("read-only");
    private static final PseudoClass FOCUSED_PSEUDO = PseudoClass.getPseudoClass("focused");

    private static final CssMetaData<JFXEditor, Color> BACKGROUND_META =
            colorMeta("-editor-background", Color.rgb(30, 30, 30), e -> e.backgroundColor);
    private static final CssMetaData<JFXEditor, Color> TEXT_COLOR_META =
            colorMeta("-editor-text-color", Color.rgb(212, 212, 212), e -> e.textColor);
    private static final CssMetaData<JFXEditor, Color> SELECTION_COLOR_META =
            colorMeta("-editor-selection-color", Color.color(0.15, 0.31, 0.47, 0.27), e -> e.selectionColor);
    private static final CssMetaData<JFXEditor, Color> CURRENT_LINE_META =
            colorMeta("-editor-current-line-color", Color.rgb(42, 40, 40), e -> e.currentLineColor);
    private static final CssMetaData<JFXEditor, Color> CARET_COLOR_META =
            colorMeta("-editor-caret-color", Color.rgb(253, 252, 252), e -> e.caretColor);
    private static final CssMetaData<JFXEditor, Color> GUTTER_BACKGROUND_META =
            colorMeta("-editor-gutter-background", Color.rgb(48, 44, 44), e -> e.gutterBackgroundColor);
    private static final CssMetaData<JFXEditor, Color> GUTTER_TEXT_META =
            colorMeta("-editor-gutter-text-color", Color.rgb(100, 98, 98), e -> e.gutterTextColor);
    private static final CssMetaData<JFXEditor, Color> AFTER_TEXT_META =
            colorMeta("-editor-after-text-color", Color.rgb(128, 128, 128), e -> e.afterTextColor);
    private static final CssMetaData<JFXEditor, Number> GUTTER_WIDTH_META =
            sizeMeta("-editor-gutter-width", 50.0, e -> e.gutterWidth);
    private static final CssMetaData<JFXEditor, Number> LINE_HEIGHT_MULTIPLIER_META =
            sizeMeta("-editor-line-height-multiplier", 1.5, e -> e.lineHeightMultiplier);
    private static final CssMetaData<JFXEditor, Number> CARET_WIDTH_META =
            sizeMeta("-editor-caret-width", 2.0, e -> e.caretWidth);
    private static final CssMetaData<JFXEditor, Duration> CARET_BLINK_RATE_META =
            new CssMetaData<>("-editor-caret-blink-rate", StyleConverter.getDurationConverter(), Duration.millis(530)) {
                @Override
                public boolean isSettable(JFXEditor editor) {
                    return !editor.caretBlinkRate.isBound();
                }

                @Override
                public StyleableProperty<Duration> getStyleableProperty(JFXEditor editor) {
                    return editor.caretBlinkRate;
                }
            };
    private static final CssMetaData<JFXEditor, Number> GUTTER_FONT_SCALE_META =
            sizeMeta("-editor-gutter-font-scale", 0.85, e -> e.gutterFontScale);
    private static final CssMetaData<JFXEditor, Boolean> CARET_VISIBLE_META =
            new CssMetaData<>("-editor-caret-visible", StyleConverter.getBooleanConverter(), Boolean.TRUE) {
                @Override
                public boolean isSettable(JFXEditor editor) {
                    return !editor.caretVisible.isBound();
                }

                @Override
                public StyleableProperty<Boolean> getStyleableProperty(JFXEditor editor) {
                    return editor.caretVisible;
                }
            };
    private static final CssMetaData<JFXEditor, Color> GHOST_TEXT_COLOR_META =
            colorMeta("-editor-ghost-text-color", Color.rgb(110, 110, 115, 0.6), e -> e.ghostTextColor);
    private static final FontCssMetaData<JFXEditor> FONT_META =
            new FontCssMetaData<>("-fx-font", Font.font("Consolas", 14)) {
                @Override
                public boolean isSettable(JFXEditor editor) {
                    return !editor.font.isBound();
                }

                @Override
                public StyleableProperty<Font> getStyleableProperty(JFXEditor editor) {
                    return editor.font;
                }
            };


    private static final Map<TokenType, HighlightStyle> DEFAULT_TOKEN_STYLES = buildDefaultTokenStyles();

    private static final Map<TokenType, CssMetaData<JFXEditor, Color>> TOKEN_COLOR_METAS = buildTokenColorMetas();
    private static final Map<TokenType, CssMetaData<JFXEditor, String>> TOKEN_STYLE_METAS = buildTokenStyleMetas();

    /** 构造默认 token 样式表（作为各 token CSS 属性的初始值）。 */
    private static Map<TokenType, HighlightStyle> buildDefaultTokenStyles() {
        Map<TokenType, HighlightStyle> styles = new EnumMap<>(TokenType.class);
        styles.put(TokenType.TEXT, HighlightStyle.of(Color.rgb(212, 212, 212)));
        styles.put(TokenType.KEYWORD, HighlightStyle.ofBold(Color.rgb(86, 156, 214)));
        styles.put(TokenType.KEYWORD_CONTROL, HighlightStyle.ofBold(Color.rgb(197, 134, 192)));
        styles.put(TokenType.KEYWORD_DECLARATION, HighlightStyle.ofBold(Color.rgb(86, 156, 214)));
        styles.put(TokenType.KEYWORD_MODIFIER, HighlightStyle.ofBold(Color.rgb(86, 156, 214)));
        styles.put(TokenType.COMMENT, HighlightStyle.ofItalic(Color.rgb(106, 153, 85)));
        styles.put(TokenType.COMMENT_BLOCK, HighlightStyle.ofItalic(Color.rgb(106, 153, 85)));
        styles.put(TokenType.COMMENT_DOC, HighlightStyle.ofItalic(Color.rgb(106, 153, 85)));
        styles.put(TokenType.STRING, HighlightStyle.of(Color.rgb(206, 145, 120)));
        styles.put(TokenType.STRING_ESCAPE, HighlightStyle.of(Color.rgb(215, 186, 125)));
        styles.put(TokenType.NUMBER, HighlightStyle.of(Color.rgb(181, 206, 168)));
        styles.put(TokenType.NUMBER_INTEGER, HighlightStyle.of(Color.rgb(181, 206, 168)));
        styles.put(TokenType.NUMBER_FLOAT, HighlightStyle.of(Color.rgb(181, 206, 168)));
        styles.put(TokenType.NUMBER_HEX, HighlightStyle.of(Color.rgb(181, 206, 168)));
        styles.put(TokenType.OPERATOR, HighlightStyle.of(Color.rgb(212, 212, 212)));
        styles.put(TokenType.PUNCTUATION, HighlightStyle.of(Color.rgb(212, 212, 212)));
        styles.put(TokenType.TYPE, HighlightStyle.of(Color.rgb(78, 201, 176)));
        styles.put(TokenType.FUNCTION, HighlightStyle.of(Color.rgb(220, 220, 170)));
        styles.put(TokenType.FUNCTION_DECLARATION, HighlightStyle.of(Color.rgb(220, 220, 170)));
        styles.put(TokenType.CONSTANT, HighlightStyle.of(Color.rgb(79, 193, 255)));
        styles.put(TokenType.ERROR, HighlightStyle.ofUnderline(Color.rgb(244, 71, 71)));
        return Collections.unmodifiableMap(styles);
    }

    /** 把 token 名称映射为 CSS 属性名：点转横线，前缀 {@code -editor-token-}。 */
    private static String tokenCssName(TokenType type) {
        return "-editor-token-" + type.getName().replace('.', '-');
    }

    /** 把样式对象的字形旗标序列化为 {@code "bold italic underline"} 组合串。 */
    private static String flagsOf(HighlightStyle style) {
        if (style == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (style.bold()) sb.append("bold ");
        if (style.italic()) sb.append("italic ");
        if (style.underline()) sb.append("underline ");
        return sb.toString().trim();
    }

    /** 为每个 TokenType 生成颜色 CSS 元数据。 */
    private static Map<TokenType, CssMetaData<JFXEditor, Color>> buildTokenColorMetas() {
        Map<TokenType, CssMetaData<JFXEditor, Color>> metas = new EnumMap<>(TokenType.class);
        for (TokenType type : TokenType.values()) {
            HighlightStyle def = DEFAULT_TOKEN_STYLES.get(type);
            metas.put(type, colorMeta(tokenCssName(type), def != null ? def.color() : null,
                    e -> e.tokenColors.get(type)));
        }
        return Collections.unmodifiableMap(metas);
    }

    /** 为每个 TokenType 生成字形旗标（{@code ...-style}）CSS 元数据。 */
    private static Map<TokenType, CssMetaData<JFXEditor, String>> buildTokenStyleMetas() {
        Map<TokenType, CssMetaData<JFXEditor, String>> metas = new EnumMap<>(TokenType.class);
        for (TokenType type : TokenType.values()) {
            String initial = flagsOf(DEFAULT_TOKEN_STYLES.get(type));
            metas.put(type, new CssMetaData<>(tokenCssName(type) + "-style",
                    StyleConverter.getStringConverter(), initial) {
                @Override
                public boolean isSettable(JFXEditor editor) {
                    return !editor.tokenStyleFlags.get(type).isBound();
                }

                @Override
                public StyleableProperty<String> getStyleableProperty(JFXEditor editor) {
                    return editor.tokenStyleFlags.get(type);
                }
            });
        }
        return Collections.unmodifiableMap(metas);
    }

    private static final List<CssMetaData<? extends Styleable, ?>> CSS_META_DATA = buildCssMetaData();

    /** 聚合基类、编辑器专属与全部 token 的 CSS 元数据。 */
    private static List<CssMetaData<? extends Styleable, ?>> buildCssMetaData() {
        List<CssMetaData<? extends Styleable, ?>> list = new ArrayList<>(Control.getClassCssMetaData());
        list.addAll(List.of(BACKGROUND_META, TEXT_COLOR_META, SELECTION_COLOR_META, CURRENT_LINE_META,
                CARET_COLOR_META, GUTTER_BACKGROUND_META, GUTTER_TEXT_META, AFTER_TEXT_META,
                GUTTER_WIDTH_META, LINE_HEIGHT_MULTIPLIER_META, FONT_META,
                CARET_WIDTH_META, CARET_BLINK_RATE_META, CARET_VISIBLE_META, GUTTER_FONT_SCALE_META,
                GHOST_TEXT_COLOR_META));
        list.addAll(TOKEN_COLOR_METAS.values());
        list.addAll(TOKEN_STYLE_METAS.values());
        return Collections.unmodifiableList(list);
    }

    /** 构造颜色型 CSS 元数据的通用工厂。 */
    private static CssMetaData<JFXEditor, Color> colorMeta(String property, Color initial,
                                                           Function<JFXEditor, StyleableProperty<Color>> extractor) {
        return new CssMetaData<>(property, StyleConverter.getColorConverter(), initial) {
            @Override
            public boolean isSettable(JFXEditor editor) {
                return !((Property<?>) extractor.apply(editor)).isBound();
            }

            @Override
            public StyleableProperty<Color> getStyleableProperty(JFXEditor editor) {
                return extractor.apply(editor);
            }
        };
    }

    /** 构造尺寸型 CSS 元数据的通用工厂。 */
    private static CssMetaData<JFXEditor, Number> sizeMeta(String property, Number initial,
                                                           Function<JFXEditor, StyleableProperty<Number>> extractor) {
        return new CssMetaData<>(property, StyleConverter.getSizeConverter(), initial) {
            @Override
            public boolean isSettable(JFXEditor editor) {
                return !((Property<?>) extractor.apply(editor)).isBound();
            }

            @Override
            public StyleableProperty<Number> getStyleableProperty(JFXEditor editor) {
                return extractor.apply(editor);
            }
        };
    }

    private final StyleableObjectProperty<Color> backgroundColor =
            new SimpleStyleableObjectProperty<>(BACKGROUND_META, this, "backgroundColor", BACKGROUND_META.getInitialValue(null));
    private final StyleableObjectProperty<Color> textColor =
            new SimpleStyleableObjectProperty<>(TEXT_COLOR_META, this, "textColor", TEXT_COLOR_META.getInitialValue(null));
    private final StyleableObjectProperty<Color> selectionColor =
            new SimpleStyleableObjectProperty<>(SELECTION_COLOR_META, this, "selectionColor", SELECTION_COLOR_META.getInitialValue(null));
    private final StyleableObjectProperty<Color> currentLineColor =
            new SimpleStyleableObjectProperty<>(CURRENT_LINE_META, this, "currentLineColor", CURRENT_LINE_META.getInitialValue(null));
    private final StyleableObjectProperty<Color> caretColor =
            new SimpleStyleableObjectProperty<>(CARET_COLOR_META, this, "caretColor", CARET_COLOR_META.getInitialValue(null));
    private final StyleableObjectProperty<Color> gutterBackgroundColor =
            new SimpleStyleableObjectProperty<>(GUTTER_BACKGROUND_META, this, "gutterBackgroundColor", GUTTER_BACKGROUND_META.getInitialValue(null));
    private final StyleableObjectProperty<Color> gutterTextColor =
            new SimpleStyleableObjectProperty<>(GUTTER_TEXT_META, this, "gutterTextColor", GUTTER_TEXT_META.getInitialValue(null));
    private final StyleableObjectProperty<Color> afterTextColor =
            new SimpleStyleableObjectProperty<>(AFTER_TEXT_META, this, "afterTextColor", AFTER_TEXT_META.getInitialValue(null));
    private final StyleableDoubleProperty gutterWidth =
            new SimpleStyleableDoubleProperty(GUTTER_WIDTH_META, this, "gutterWidth", 50.0);
    private final StyleableDoubleProperty lineHeightMultiplier =
            new SimpleStyleableDoubleProperty(LINE_HEIGHT_MULTIPLIER_META, this, "lineHeightMultiplier", 1.5);
    private final StyleableObjectProperty<Font> font =
            new SimpleStyleableObjectProperty<>(FONT_META, this, "font", Font.font("Consolas", 14));
    private final StyleableDoubleProperty caretWidth =
            new SimpleStyleableDoubleProperty(CARET_WIDTH_META, this, "caretWidth", 2.0);
    private final StyleableObjectProperty<Duration> caretBlinkRate =
            new SimpleStyleableObjectProperty<>(CARET_BLINK_RATE_META, this, "caretBlinkRate", Duration.millis(530));
    /** 光标可见性（CSS {@code -editor-caret-visible}，默认 true；关闭后皮肤不绘制任何光标）。 */
    private final SimpleStyleableBooleanProperty caretVisible =
            new SimpleStyleableBooleanProperty(CARET_VISIBLE_META, this, "caretVisible", true);
    private final StyleableDoubleProperty gutterFontScale =
            new SimpleStyleableDoubleProperty(GUTTER_FONT_SCALE_META, this, "gutterFontScale", 0.85);
    private final StyleableObjectProperty<Color> ghostTextColor =
            new SimpleStyleableObjectProperty<>(GHOST_TEXT_COLOR_META, this, "ghostTextColor", GHOST_TEXT_COLOR_META.getInitialValue(null));

    private final Map<TokenType, StyleableObjectProperty<Color>> tokenColors = createTokenColorProperties();
    private final Map<TokenType, StyleableStringProperty> tokenStyleFlags = createTokenStyleProperties();

    /** 为每个 TokenType 创建可样式化颜色属性。 */
    private Map<TokenType, StyleableObjectProperty<Color>> createTokenColorProperties() {
        Map<TokenType, StyleableObjectProperty<Color>> map = new EnumMap<>(TokenType.class);
        for (TokenType type : TokenType.values()) {
            CssMetaData<JFXEditor, Color> meta = TOKEN_COLOR_METAS.get(type);
            map.put(type, new SimpleStyleableObjectProperty<>(meta, this,
                    "tokenColor:" + type.getName(), meta.getInitialValue(null)));
        }
        return Collections.unmodifiableMap(map);
    }

    /** 为每个 TokenType 创建可样式化字形旗标属性。 */
    private Map<TokenType, StyleableStringProperty> createTokenStyleProperties() {
        Map<TokenType, StyleableStringProperty> map = new EnumMap<>(TokenType.class);
        for (TokenType type : TokenType.values()) {
            CssMetaData<JFXEditor, String> meta = TOKEN_STYLE_METAS.get(type);
            map.put(type, new javafx.css.SimpleStyleableStringProperty(meta, this,
                    "tokenStyle:" + type.getName(), meta.getInitialValue(null)));
        }
        return Collections.unmodifiableMap(map);
    }

    /** 以默认的 {@link MemoryDocument} 创建编辑器。 */
    public JFXEditor() {
        this(new MemoryDocument());
    }

    /**
     * 以指定文档创建编辑器。
     *
     * @param document 文档模型，构造后不可更换
     */
    public JFXEditor(Document document) {
        this.document = document;
        this.decorationModel = new DecorationModel();
        this.keyBindingRegistry = new KeyBindingRegistry();
        this.renderLayers = new CopyOnWriteArrayList<>();
        this.primaryCaret = new EditorCaret();
        this.extraCarets = new CopyOnWriteArrayList<>();
        this.highlightTheme = buildThemeSnapshot();
        this.highlightEngine = null;
        this.highlighter = null;

        this.gutterVisible = new SimpleBooleanProperty(this, "gutterVisible", true);
        this.readOnly = new SimpleBooleanProperty(this, "readOnly", false);
        this.readOnly.addListener((obs, oldVal, newVal) -> pseudoClassStateChanged(READ_ONLY_PSEUDO, newVal));
        this.indentStrategy = new SimpleObjectProperty<>(this, "indentStrategy", IndentStrategies.BASIC);
        this.indentStrategy.addListener((obs, oldVal, newVal) -> {
            if (newVal == null) {
                this.indentStrategy.set(IndentStrategies.NONE);
            }
        });
        this.caretListeners = new CopyOnWriteArrayList<>();

        InvalidationListener tokenRestyle = obs -> onTokenStyleChanged();
        for (TokenType type : TokenType.values()) {
            tokenColors.get(type).addListener(tokenRestyle);
            tokenStyleFlags.get(type).addListener(tokenRestyle);
        }

        getStyleClass().add(DEFAULT_STYLE_CLASS);
    }

    /** {@inheritDoc} 返回 {@link JFXEditorSkin}。 */
    @Override
    protected Skin<?> createDefaultSkin() {
        return new JFXEditorSkin(this);
    }

    /**
     * {@inheritDoc} 加载同包资源 {@code editor.css}。
     *
     * @throws NullPointerException 资源缺失时
     */
    @Override
    public String getUserAgentStylesheet() {
        return Objects.requireNonNull(JFXEditor.class.getResource("editor.css"),
                "editor.css 资源缺失").toExternalForm();
    }

    /** {@inheritDoc} */
    @Override
    public List<CssMetaData<? extends Styleable, ?>> getControlCssMetaData() {
        return CSS_META_DATA;
    }

    /** @return 本控件类的全部 CSS 元数据（含基类与 token 属性） */
    public static List<CssMetaData<? extends Styleable, ?>> getClassCssMetaData() {
        return CSS_META_DATA;
    }

    /** @return 文档模型 */
    public Document document() {
        return document;
    }

    /** @return 装饰模型 */
    public DecorationModel decorationModel() {
        return decorationModel;
    }

    /** @return 按键绑定注册表 */
    public KeyBindingRegistry keyBindingRegistry() {
        return keyBindingRegistry;
    }

    /**
     * 返回渲染层活列表：直接 add/remove 即注册/注销渲染层。
     *
     * @return 可变的渲染层列表（写时复制，可安全遍历）
     */
    public List<RenderLayer> renderLayers() {
        return renderLayers;
    }

    /** @return 主光标 */
    public EditorCaret primaryCaret() {
        return primaryCaret;
    }

    /** @return 额外光标列表（不含主光标，写时复制可安全遍历） */
    public List<EditorCaret> extraCarets() {
        return extraCarets;
    }

    /**
     * 返回全部光标的只读快照：主光标恒在首位，额外光标
     * 按添加顺序在后。
     *
     * @return 全部光标的不可变列表
     */
    public List<EditorCaret> allCarets() {
        List<EditorCaret> all = new ArrayList<>(extraCarets.size() + 1);
        all.add(primaryCaret);
        all.addAll(extraCarets);
        return Collections.unmodifiableList(all);
    }

    /** @return 存在额外光标（多光标状态）时返回 {@code true} */
    public boolean hasMultipleCarets() {
        return !extraCarets.isEmpty();
    }

    /**
     * 在指定位置添加一个额外光标（坐标自动鉗制到文档范围）。
     *
     * @param line 目标行（0 起，自动鉗制）
     * @param col  目标列（0 起，自动鉗制）
     * @return 新增的光标；与既有光标（含主光标）重合时返回 {@code null}
     */
    public EditorCaret addCaret(int line, int col) {
        int[] clamped = clampToDocument(line, col);
        if (isCaretAt(clamped[0], clamped[1])) {
            return null;
        }
        EditorCaret caret = new EditorCaret();
        caret.moveTo(clamped[0], clamped[1]);
        extraCarets.add(caret);
        return caret;
    }

    /**
     * 移除一个额外光标（主光标不可移除）。
     *
     * @param caret 待移除的光标
     * @return 确实移除时返回 {@code true}
     */
    public boolean removeCaret(EditorCaret caret) {
        return extraCarets.remove(caret);
    }

    /**
     * 在指定位置切换额外光标（Alt+点击语义）：命中既有额外
     * 光标则移除，命中主光标则无操作，否则添加。
     *
     * @param line 目标行（自动鉗制）
     * @param col  目标列（自动鉗制）
     * @return 光标集合确实发生变化时返回 {@code true}
     */
    public boolean toggleCaretAt(int line, int col) {
        int[] clamped = clampToDocument(line, col);
        for (EditorCaret c : extraCarets) {
            if (c.line() == clamped[0] && c.column() == clamped[1]) {
                extraCarets.remove(c);
                return true;
            }
        }
        if (primaryCaret.line() == clamped[0] && primaryCaret.column() == clamped[1]) {
            return false;
        }
        return addCaret(clamped[0], clamped[1]) != null;
    }

    /**
     * 清除全部额外光标，回到单光标状态。
     *
     * @return 确实清除了至少一个时返回 {@code true}
     */
    public boolean clearExtraCarets() {
        if (extraCarets.isEmpty()) {
            return false;
        }
        extraCarets.clear();
        return true;
    }

    /** 合并与主光标或彼此重合的额外光标（编辑/移动后调用）。 */
    void dedupeCarets() {
        if (extraCarets.isEmpty()) {
            return;
        }
        List<EditorCaret> kept = new ArrayList<>();
        kept.add(primaryCaret);
        List<EditorCaret> duplicated = new ArrayList<>();
        for (EditorCaret c : extraCarets) {
            boolean dup = false;
            for (EditorCaret k : kept) {
                if (k.line() == c.line() && k.column() == c.column()) {
                    dup = true;
                    break;
                }
            }
            if (dup) {
                duplicated.add(c);
            } else {
                kept.add(c);
            }
        }
        if (!duplicated.isEmpty()) {
            extraCarets.removeAll(duplicated);
        }
    }

    /** 判断指定位置是否已有光标（含主光标）。 */
    private boolean isCaretAt(int line, int col) {
        if (primaryCaret.line() == line && primaryCaret.column() == col) {
            return true;
        }
        for (EditorCaret c : extraCarets) {
            if (c.line() == line && c.column() == col) {
                return true;
            }
        }
        return false;
    }

    /** 把行列鉗制到文档合法范围，返回 {行, 列} 数组。 */
    private int[] clampToDocument(int line, int col) {
        int lineCount = document.getLineCount();
        if (lineCount == 0) {
            return new int[]{0, 0};
        }
        int clampedLine = Math.max(0, Math.min(line, lineCount - 1));
        int maxCol = document.getLineLength(clampedLine);
        return new int[]{clampedLine, Math.max(0, Math.min(col, maxCol))};
    }

    /** @return 高亮引擎；未设置高亮器时为 {@code null} */
    public HighlightEngine highlightEngine() {
        return highlightEngine;
    }

    /** @return 当前主题快照（由 CSS token 属性拼装） */
    public HighlightTheme highlightTheme() {
        return highlightTheme;
    }

    /** @return 当前语法高亮器，可能为 {@code null} */
    public SyntaxHighlighter highlighter() {
        return highlighter;
    }

    /** 请求 Skin 重绘（重绘计数器自增）——外部触发重绘的唯一正途。 */
    public void requestRepaint() {
        repaints.set(repaints.get() + 1);
    }

    /** @return 只读重绘计数属性（Skin 监听它触发重绘） */
    public ReadOnlyLongProperty repaintsProperty() {
        return repaints.getReadOnlyProperty();
    }

    /**
     * 设置语法高亮器并重建高亮引擎。
     *
     * <p>先释放旧高亮器（TreeSitter 高亮器会 dispose）与旧引擎，
     * 再新建 {@link HighlightEngine} 并挂接重绘监听；TreeSitter
     * 高亮器还会 attach 到文档以启用增量解析。</p>
     *
     * @param highlighter 新高亮器；{@code null} 表示关闭高亮
     */
    public void setHighlighter(SyntaxHighlighter highlighter) {
        disposeCurrentHighlighter();
        this.highlighter = highlighter;

        if (highlighter != null && document != null) {
            this.highlightEngine = new HighlightEngine(document, highlighter, highlightTheme);
            this.highlightEngine.addUpdateListener(repaintOnHighlightUpdate);
            attachHighlighterToDocument(highlighter);
        }
        requestRepaint();
    }

    /** 释放当前高亮器（TreeSitter 需 dispose 原生资源）与高亮引擎。 */
    private void disposeCurrentHighlighter() {
        if (this.highlighter instanceof TreeSitterHighlighter tsh) {
            tsh.dispose();
        }
        if (highlightEngine != null) {
            highlightEngine.dispose();
            highlightEngine = null;
        }
    }

    /** TreeSitter 高亮器需绑定文档以监听变更驱动增量解析。 */
    private void attachHighlighterToDocument(SyntaxHighlighter hl) {
        if (hl instanceof TreeSitterHighlighter tsh) {
            tsh.attachTo(document);
        }
    }

    /**
     * 从当前全部 token CSS 属性拼装名为 {@code "CSS"} 的主题快照；
     * 字形旗标串按是否包含 bold/italic/underline 解析。
     */
    private HighlightTheme buildThemeSnapshot() {
        Map<TokenType, HighlightStyle> styles = new EnumMap<>(TokenType.class);
        for (TokenType type : TokenType.values()) {
            Color color = tokenColors.get(type).get();
            if (color == null) {
                continue;
            }
            String flags = tokenStyleFlags.get(type).get();
            String normalized = flags != null ? flags.toLowerCase(Locale.ROOT) : "";
            styles.put(type, new HighlightStyle(color,
                    normalized.contains("bold"),
                    normalized.contains("italic"),
                    normalized.contains("underline")));
        }
        HighlightStyle defaultStyle = styles.getOrDefault(TokenType.TEXT,
                HighlightStyle.of(Color.rgb(212, 212, 212)));
        return HighlightTheme.of("CSS", styles, defaultStyle);
    }

    /** token 颜色/字形属性变化时：重建主题快照、同步到引擎并请求重绘。 */
    private void onTokenStyleChanged() {
        this.highlightTheme = buildThemeSnapshot();
        if (highlightEngine != null) {
            highlightEngine.setTheme(highlightTheme);
        }
        requestRepaint();
    }

    /**
     * @param type token 类型
     * @return 该类型的颜色属性（对应 CSS {@code -editor-token-...}）
     */
    public ObjectProperty<Color> tokenColorProperty(TokenType type) {
        return tokenColors.get(type);
    }

    /**
     * @param type token 类型
     * @return 该类型的字形旗标属性（值为 bold/italic/underline 组合串）
     */
    public StringProperty tokenStyleProperty(TokenType type) {
        return tokenStyleFlags.get(type);
    }

    /** @return 当前字体（CSS {@code -fx-font}，默认 Consolas 14） */
    public Font font() {
        return font.get();
    }

    /** @return 字体属性 */
    public ObjectProperty<Font> fontProperty() {
        return font;
    }

    /** @param f 新字体 */
    public void setFont(Font f) {
        font.set(f);
    }

    /** @return 行高倍数（CSS {@code -editor-line-height-multiplier}，默认 1.5） */
    public double lineHeightMultiplier() {
        return lineHeightMultiplier.get();
    }

    /** @return 行高倍数属性 */
    public DoubleProperty lineHeightMultiplierProperty() {
        return lineHeightMultiplier;
    }

    /** @param m 新行高倍数 */
    public void setLineHeightMultiplier(double m) {
        lineHeightMultiplier.set(m);
    }


    /** @return 背景色（CSS {@code -editor-background}） */
    public Color backgroundColor() {
        return backgroundColor.get();
    }

    /** @return 背景色属性 */
    public ObjectProperty<Color> backgroundColorProperty() {
        return backgroundColor;
    }

    /** @return 正文颜色（CSS {@code -editor-text-color}） */
    public Color textColor() {
        return textColor.get();
    }

    /** @return 正文颜色属性 */
    public ObjectProperty<Color> textColorProperty() {
        return textColor;
    }

    /** @return 选区背景色（CSS {@code -editor-selection-color}） */
    public Color selectionColor() {
        return selectionColor.get();
    }

    /** @return 选区背景色属性 */
    public ObjectProperty<Color> selectionColorProperty() {
        return selectionColor;
    }

    /** @return 当前行高亮色（CSS {@code -editor-current-line-color}） */
    public Color currentLineColor() {
        return currentLineColor.get();
    }

    /** @return 当前行高亮色属性 */
    public ObjectProperty<Color> currentLineColorProperty() {
        return currentLineColor;
    }

    /** @return 光标颜色（CSS {@code -editor-caret-color}） */
    public Color caretColor() {
        return caretColor.get();
    }

    /** @return 光标颜色属性 */
    public ObjectProperty<Color> caretColorProperty() {
        return caretColor;
    }

    /** @return gutter 背景色（CSS {@code -editor-gutter-background}） */
    public Color gutterBackgroundColor() {
        return gutterBackgroundColor.get();
    }

    /** @return gutter 背景色属性 */
    public ObjectProperty<Color> gutterBackgroundColorProperty() {
        return gutterBackgroundColor;
    }

    /** @return gutter 行号颜色（CSS {@code -editor-gutter-text-color}） */
    public Color gutterTextColor() {
        return gutterTextColor.get();
    }

    /** @return gutter 行号颜色属性 */
    public ObjectProperty<Color> gutterTextColorProperty() {
        return gutterTextColor;
    }

    /** @return 行尾附注颜色（CSS {@code -editor-after-text-color}） */
    public Color afterTextColor() {
        return afterTextColor.get();
    }

    /** @return 行尾附注颜色属性 */
    public ObjectProperty<Color> afterTextColorProperty() {
        return afterTextColor;
    }

    /** @return gutter 宽度像素（CSS {@code -editor-gutter-width}，默认 50） */
    public double gutterWidth() {
        return gutterWidth.get();
    }

    /** @return gutter 宽度属性 */
    public DoubleProperty gutterWidthProperty() {
        return gutterWidth;
    }

    /** @param width 新 gutter 宽度（像素） */
    public void setGutterWidth(double width) {
        gutterWidth.set(width);
    }

    /** @return gutter 是否可见（非 CSS 属性，默认 true） */
    public boolean isGutterVisible() {
        return gutterVisible.get();
    }

    /** @return gutter 可见性属性 */
    public SimpleBooleanProperty gutterVisibleProperty() {
        return gutterVisible;
    }

    /** @param visible 是否显示 gutter */
    public void setGutterVisible(boolean visible) {
        gutterVisible.set(visible);
    }

    /** @return 光标是否显示（CSS {@code -editor-caret-visible}，默认 true） */
    public boolean isCaretVisible() {
        return caretVisible.get();
    }

    /** @return 光标可见性属性（CSS 可样式化） */
    public BooleanProperty caretVisibleProperty() {
        return caretVisible;
    }

    /**
     * 设置光标是否显示（等价于 CSS {@code -editor-caret-visible}，
     * 代码设置为 USER 起源，不会被 UA 默认样式覆盖）。
     *
     * <p>关闭后皮肤会隐藏主光标矩形、停止闪烁，且不再绘制
     * 额外光标；适用于只读预览等无需光标的场景。</p>
     *
     * @param visible 是否显示光标
     */
    public void setCaretVisible(boolean visible) {
        caretVisible.set(visible);
    }

    /** @return 是否只读（只读时所有编辑操作静默忽略） */
    public boolean isReadOnly() {
        return readOnly.get();
    }

    /** @return 只读模式属性（联动 {@code :read-only} 伪类） */
    public SimpleBooleanProperty readOnlyProperty() {
        return readOnly;
    }

    /** @param readOnly 是否只读 */
    public void setReadOnly(boolean readOnly) {
        this.readOnly.set(readOnly);
    }

    /** @return 当前缩进策略（默认 {@link IndentStrategies#BASIC}，永不为 {@code null}） */
    public IndentStrategy getIndentStrategy() {
        return indentStrategy.get();
    }

    /** @return 缩进策略属性 */
    public ObjectProperty<IndentStrategy> indentStrategyProperty() {
        return indentStrategy;
    }

    /**
     * 设置缩进策略（不占用任何按键绑定，TAB 键仍由使用方支配）。
     *
     * @param strategy 新策略；{@code null} 归一化为 {@link IndentStrategies#NONE}
     */
    public void setIndentStrategy(IndentStrategy strategy) {
        indentStrategy.set(strategy != null ? strategy : IndentStrategies.NONE);
    }

    /** 由 Skin 同步 Canvas 焦点到控件焦点与 {@code :focused} 伪类。 */
    void updateFocusFromSkin(boolean focused) {
        setFocused(focused);
        pseudoClassStateChanged(FOCUSED_PSEUDO, focused);
    }

    /** @return 光标宽度像素（CSS {@code -editor-caret-width}，默认 2） */
    public double caretWidth() {
        return caretWidth.get();
    }

    /** @return 光标宽度属性 */
    public DoubleProperty caretWidthProperty() {
        return caretWidth;
    }

    /** @param width 新光标宽度（像素） */
    public void setCaretWidth(double width) {
        caretWidth.set(width);
    }

    /** @return 光标闪烁周期（CSS {@code -editor-caret-blink-rate}，默认 530ms） */
    public Duration caretBlinkRate() {
        return caretBlinkRate.get();
    }

    /** @return 光标闪烁周期属性 */
    public ObjectProperty<Duration> caretBlinkRateProperty() {
        return caretBlinkRate;
    }

    /** @param rate 新闪烁周期 */
    public void setCaretBlinkRate(Duration rate) {
        caretBlinkRate.set(rate);
    }

    /** @return gutter 字体缩放比（CSS {@code -editor-gutter-font-scale}，默认 0.85） */
    public double gutterFontScale() {
        return gutterFontScale.get();
    }

    /** @return gutter 字体缩放比属性 */
    public DoubleProperty gutterFontScaleProperty() {
        return gutterFontScale;
    }

    /** @param scale 新缩放比 */
    public void setGutterFontScale(double scale) {
        gutterFontScale.set(scale);
    }

    /** @return 幽灵文本颜色（CSS {@code -editor-ghost-text-color}） */
    public Color ghostTextColor() {
        return ghostTextColor.get();
    }

    /** @return 幽灵文本颜色属性 */
    public ObjectProperty<Color> ghostTextColorProperty() {
        return ghostTextColor;
    }

    /** @param color 新幽灵文本颜色 */
    public void setGhostTextColor(Color color) {
        ghostTextColor.set(color);
    }

    /**
     * 计算像素行高：字号 × 行高倍数。
     *
     * @return 行高（像素）
     */
    public double calculateLineHeight() {
        Font f = font.get();
        return f.getSize() * lineHeightMultiplier.get();
    }

    /**
     * 注册光标变化监听器。
     *
     * @param listener 监听器（回调参数为 0 起行列）
     */
    public void addCaretChangeListener(CaretChangeListener listener) {
        caretListeners.add(listener);
    }

    /**
     * 移除光标变化监听器；未注册时静默忽略。
     *
     * @param listener 待移除的监听器
     */
    public void removeCaretChangeListener(CaretChangeListener listener) {
        caretListeners.remove(listener);
    }

    /** 广播光标变化事件（Skin 与编辑操作内部调用）。 */
    void fireCaretChanged(int line, int col) {
        for (CaretChangeListener l : caretListeners) {
            l.onCaretChanged(line, col);
        }
    }

    /**
     * 编程式导航：移动主光标并通知 Skin 滚动定位。
     *
     * <p>行钳制到 {@code [0, lineCount-1]}、列钳制到 {@code [0, 行长]}；
     * 空文档归零。随后触发光标监听器并写入导航属性。</p>
     *
     * @param line   目标行（0 起，自动钳制）
     * @param column 目标列（0 起，自动钳制）；额外光标会被同步清除
     */
    public void gotoPosition(int line, int column) {
        int[] clamped = clampToDocument(line, column);
        clearExtraCarets();
        primaryCaret.moveTo(clamped[0], clamped[1]);
        fireCaretChanged(primaryCaret.line(), primaryCaret.column());
        navigateToPosition.set(new Position(clamped[0], clamped[1]));
    }

    /** @return 导航目标属性（Skin 监听它执行滚动定位） */
    public SimpleObjectProperty<Position> navigateToPositionProperty() {
        return navigateToPosition;
    }

    /**
     * 在光标处插入文本并把光标移到插入结束位置。
     *
     * <p>多光标状态下会对每个光标先删除其选区再插入，并自动
     * 重映射后续光标位置（见 {@link #insertTextAtAllCarets}）。</p>
     *
     * @param text 待插入文本
     * @return 主光标插入结束位置的折叠区间；只读时返回光标处空区间且不做任何事
     */
    public TextRange insertText(String text) {
        if (isReadOnly()) return TextRange.fromPosition(new Position(primaryCaret.line(), primaryCaret.column()));
        if (!extraCarets.isEmpty()) {
            return insertTextAtAllCarets(text);
        }
        int line = primaryCaret.line();
        int col = primaryCaret.column();
        TextRange result = document.insert(line, col, text);
        Position endPos = result.end();
        primaryCaret.moveTo(endPos.line(), endPos.column());
        fireCaretChanged(primaryCaret.line(), primaryCaret.column());
        return result;
    }

    /**
     * 多光标插入：按文档序升序处理每个光标（先删其选区再插入），
     * 每次编辑后把尚未处理的光标按坐标映射规则平移；全程用
     * 复合撤销单元与批量事务包裹（一次 undo 整体回滚），结束后
     * 合并重合光标。
     */
    private TextRange insertTextAtAllCarets(String text) {
        List<EditorCaret> sorted = sortedCaretsByPosition();
        TextRange primaryResult = null;
        document.beginCompoundEdit();
        document.beginBatch();
        try {
            for (int i = 0; i < sorted.size(); i++) {
                EditorCaret caret = sorted.get(i);
                deleteCaretSelection(caret, sorted, i + 1);
                int startLine = caret.line();
                int startCol = caret.column();
                TextRange r = document.insert(startLine, startCol, text);
                Position end = r.end();
                caret.moveTo(end.line(), end.column());
                for (int j = i + 1; j < sorted.size(); j++) {
                    remapCaret(sorted.get(j), (l, c) ->
                            mapAfterInsert(l, c, startLine, startCol, end.line(), end.column()));
                }
                if (caret == primaryCaret) {
                    primaryResult = r;
                }
            }
        } finally {
            document.endBatch();
            document.endCompoundEdit();
        }
        dedupeCarets();
        fireCaretChanged(primaryCaret.line(), primaryCaret.column());
        return primaryResult != null ? primaryResult
                : TextRange.fromPosition(new Position(primaryCaret.line(), primaryCaret.column()));
    }

    /**
     * 删除当前选区：光标落在选区起点并清除选区。
     *
     * <p>多光标状态下会删除每个光标各自的选区并重映射
     * 后续光标位置。</p>
     *
     * @return 删除后的主光标位置（折叠区间）；只读或无选区时返回零区间
     */
    public TextRange deleteSelection() {
        if (isReadOnly()) return TextRange.fromPosition(Position.ZERO);
        if (!extraCarets.isEmpty()) {
            return deleteSelectionAtAllCarets();
        }
        if (!primaryCaret.hasSelection()) return TextRange.fromPosition(Position.ZERO);
        TextRange range = TextRange.of(
                primaryCaret.selectionStartLine(), primaryCaret.selectionStartCol(),
                primaryCaret.selectionEndLine(), primaryCaret.selectionEndCol());
        TextRange result = document.delete(range);
        primaryCaret.moveTo(primaryCaret.selectionStartLine(), primaryCaret.selectionStartCol());
        primaryCaret.clearSelection();
        fireCaretChanged(primaryCaret.line(), primaryCaret.column());
        return result;
    }

    /** 多光标删选区：升序删除每个光标的选区并重映射后续光标；整体为单个撤销单元。 */
    private TextRange deleteSelectionAtAllCarets() {
        List<EditorCaret> sorted = sortedCaretsByPosition();
        boolean any = false;
        for (EditorCaret c : sorted) {
            if (c.hasSelection()) {
                any = true;
                break;
            }
        }
        if (!any) return TextRange.fromPosition(Position.ZERO);
        document.beginCompoundEdit();
        document.beginBatch();
        try {
            for (int i = 0; i < sorted.size(); i++) {
                deleteCaretSelection(sorted.get(i), sorted, i + 1);
            }
        } finally {
            document.endBatch();
            document.endCompoundEdit();
        }
        dedupeCarets();
        fireCaretChanged(primaryCaret.line(), primaryCaret.column());
        return TextRange.fromPosition(new Position(primaryCaret.line(), primaryCaret.column()));
    }

    /**
     * 退格删除：有选区删选区；列 0 时合并到上一行尾部。
     *
     * <p>多光标状态下对每个光标独立执行，重合光标自动合并。</p>
     *
     * @return 文档确实发生变化时返回 {@code true}；只读时返回 {@code false}
     */
    public boolean deleteBackward() {
        if (isReadOnly()) return false;
        if (extraCarets.isEmpty()) {
            return deleteBackwardSingle();
        }
        List<EditorCaret> sorted = sortedCaretsByPosition();
        boolean changed = false;
        document.beginCompoundEdit();
        document.beginBatch();
        try {
            for (int i = 0; i < sorted.size(); i++) {
                EditorCaret caret = sorted.get(i);
                if (caret.hasSelection()) {
                    deleteCaretSelection(caret, sorted, i + 1);
                    changed = true;
                    continue;
                }
                int line = caret.line();
                int col = caret.column();
                int startLine;
                int startCol;
                if (col > 0) {
                    startLine = line;
                    startCol = col - 1;
                } else if (line > 0) {
                    startLine = line - 1;
                    startCol = document.getLineLength(line - 1);
                } else {
                    continue;
                }
                document.delete(TextRange.of(startLine, startCol, line, col));
                caret.moveTo(startLine, startCol);
                for (int j = i + 1; j < sorted.size(); j++) {
                    remapCaret(sorted.get(j), (l, c) ->
                            mapAfterDelete(l, c, startLine, startCol, line, col));
                }
                changed = true;
            }
        } finally {
            document.endBatch();
            document.endCompoundEdit();
        }
        dedupeCarets();
        if (changed) {
            fireCaretChanged(primaryCaret.line(), primaryCaret.column());
        }
        return changed;
    }

    /** 单光标退格删除。 */
    private boolean deleteBackwardSingle() {
        EditorCaret c = primaryCaret;
        if (c.hasSelection()) {
            deleteSelection();
            return true;
        }
        if (c.column() > 0) {
            document.delete(TextRange.of(c.line(), c.column() - 1, c.line(), c.column()));
            c.moveTo(c.line(), c.column() - 1);
            fireCaretChanged(c.line(), c.column());
            return true;
        }
        if (c.line() > 0) {
            int prevLineLen = document.getLineLength(c.line() - 1);
            TextRange result = document.delete(
                    TextRange.of(c.line() - 1, prevLineLen, c.line(), 0));
            Position newPos = result.end();
            c.moveTo(newPos.line(), newPos.column());
            fireCaretChanged(c.line(), c.column());
            return true;
        }
        return false;
    }

    /**
     * 前向删除：有选区删选区；行尾时合并下一行。
     *
     * <p>多光标状态下对每个光标独立执行，重合光标自动合并。</p>
     *
     * @return 文档确实发生变化时返回 {@code true}；只读时返回 {@code false}
     */
    public boolean deleteForward() {
        if (isReadOnly()) return false;
        if (extraCarets.isEmpty()) {
            return deleteForwardSingle();
        }
        List<EditorCaret> sorted = sortedCaretsByPosition();
        boolean changed = false;
        document.beginCompoundEdit();
        document.beginBatch();
        try {
            for (int i = 0; i < sorted.size(); i++) {
                EditorCaret caret = sorted.get(i);
                if (caret.hasSelection()) {
                    deleteCaretSelection(caret, sorted, i + 1);
                    changed = true;
                    continue;
                }
                int line = caret.line();
                int col = caret.column();
                int endLine;
                int endCol;
                if (col < document.getLineLength(line)) {
                    endLine = line;
                    endCol = col + 1;
                } else if (line < document.getLineCount() - 1) {
                    endLine = line + 1;
                    endCol = 0;
                } else {
                    continue;
                }
                document.delete(TextRange.of(line, col, endLine, endCol));
                for (int j = i + 1; j < sorted.size(); j++) {
                    remapCaret(sorted.get(j), (l, c) ->
                            mapAfterDelete(l, c, line, col, endLine, endCol));
                }
                changed = true;
            }
        } finally {
            document.endBatch();
            document.endCompoundEdit();
        }
        dedupeCarets();
        if (changed) {
            fireCaretChanged(primaryCaret.line(), primaryCaret.column());
        }
        return changed;
    }

    /** 单光标前向删除。 */
    private boolean deleteForwardSingle() {
        EditorCaret c = primaryCaret;
        if (c.hasSelection()) {
            deleteSelection();
            return true;
        }
        if (c.column() < document.getLineLength(c.line())) {
            document.delete(TextRange.of(c.line(), c.column(), c.line(), c.column() + 1));
            fireCaretChanged(c.line(), c.column());
            return true;
        }
        if (c.line() < document.getLineCount() - 1) {
            document.delete(TextRange.of(c.line(), c.column(), c.line() + 1, 0));
            fireCaretChanged(c.line(), c.column());
            return true;
        }
        return false;
    }

    /** 删除单个光标的选区并重映射后续光标；无选区时无操作。 */
    private void deleteCaretSelection(EditorCaret caret, List<EditorCaret> sorted, int fromIndex) {
        if (!caret.hasSelection()) {
            return;
        }
        int sl = caret.selectionStartLine();
        int sc = caret.selectionStartCol();
        int el = caret.selectionEndLine();
        int ec = caret.selectionEndCol();
        document.delete(TextRange.of(sl, sc, el, ec));
        caret.moveTo(sl, sc);
        for (int j = fromIndex; j < sorted.size(); j++) {
            remapCaret(sorted.get(j), (l, c) -> mapAfterDelete(l, c, sl, sc, el, ec));
        }
    }

    /** 返回按文档序（选区起点或光标位置）升序排列的全部光标。 */
    private List<EditorCaret> sortedCaretsByPosition() {
        List<EditorCaret> sorted = new ArrayList<>(extraCarets.size() + 1);
        sorted.add(primaryCaret);
        sorted.addAll(extraCarets);
        sorted.sort(Comparator
                .comparingInt((EditorCaret c) -> c.hasSelection() ? c.selectionStartLine() : c.line())
                .thenComparingInt(c -> c.hasSelection() ? c.selectionStartCol() : c.column()));
        return sorted;
    }

    /** 行列坐标映射函数（多光标编辑后的位置平移）。 */
    @FunctionalInterface
    private interface PositionMapper {
        /**
         * 把一个行列坐标映射到编辑后的新坐标。
         *
         * @param line 原行号
         * @param col  原列号
         * @return {新行, 新列} 数组
         */
        int[] map(int line, int col);
    }

    /**
     * 按映射函数平移一个光标的全部坐标（位置与选区锚/焦点）；
     * 选区被映射后塔缩为空时自动折叠。
     */
    private static void remapCaret(EditorCaret caret, PositionMapper mapper) {
        boolean hadSelection = caret.hasSelection();
        int[] newPos = mapper.map(caret.line(), caret.column());
        if (!hadSelection) {
            caret.moveTo(newPos[0], newPos[1]);
            return;
        }
        int anchorLine = caret.anchorLine();
        int anchorCol = caret.anchorCol();
        boolean anchorAtStart = anchorLine == caret.selectionStartLine()
                && anchorCol == caret.selectionStartCol();
        int focusLine = anchorAtStart ? caret.selectionEndLine() : caret.selectionStartLine();
        int focusCol = anchorAtStart ? caret.selectionEndCol() : caret.selectionStartCol();
        int[] newAnchor = mapper.map(anchorLine, anchorCol);
        int[] newFocus = mapper.map(focusLine, focusCol);
        caret.moveTo(newPos[0], newPos[1]);
        if (newAnchor[0] != newFocus[0] || newAnchor[1] != newFocus[1]) {
            caret.select(newAnchor[0], newAnchor[1], newFocus[0], newFocus[1]);
        }
    }

    /**
     * 插入后的坐标映射：插入发生在 {@code (sl,sc)}，插入结束于
     * {@code (el,ec)}；插入点之后（含同点）的坐标向后平移。
     */
    private static int[] mapAfterInsert(int line, int col, int sl, int sc, int el, int ec) {
        if (line == sl && col >= sc) {
            return new int[]{el, ec + (col - sc)};
        }
        if (line > sl) {
            return new int[]{line + (el - sl), col};
        }
        return new int[]{line, col};
    }

    /**
     * 删除后的坐标映射：删除区间为 {@code (sl,sc)-(el,ec)}；
     * 区间前坐标不变，区间内塔缩到起点，区间后向前平移。
     */
    private static int[] mapAfterDelete(int line, int col, int sl, int sc, int el, int ec) {
        if (line < sl || (line == sl && col <= sc)) {
            return new int[]{line, col};
        }
        if (line < el || (line == el && col <= ec)) {
            return new int[]{sl, sc};
        }
        if (line == el) {
            return new int[]{sl, sc + (col - ec)};
        }
        return new int[]{line - (el - sl), col};
    }

    /**
     * 撤销最近一次编辑并把光标移到结果区间末尾。
     *
     * <p>撤销后额外光标位置不再可信，会被同步清除。</p>
     *
     * @return 成功撤销返回 {@code true}；无可撤销内容返回 {@code false}
     */
    public boolean undo() {
        if (!document.canUndo()) return false;
        TextRange result = document.undo();
        Position end = result.end();
        clearExtraCarets();
        primaryCaret.moveTo(end.line(), end.column());
        fireCaretChanged(primaryCaret.line(), primaryCaret.column());
        return true;
    }

    /**
     * 重做最近一次被撤销的编辑并把光标移到结果区间末尾。
     *
     * <p>重做后额外光标位置不再可信，会被同步清除。</p>
     *
     * @return 成功重做返回 {@code true}；无可重做内容返回 {@code false}
     */
    public boolean redo() {
        if (!document.canRedo()) return false;
        TextRange result = document.redo();
        Position end = result.end();
        clearExtraCarets();
        primaryCaret.moveTo(end.line(), end.column());
        fireCaretChanged(primaryCaret.line(), primaryCaret.column());
        return true;
    }

    /** @return 文档存在可撤销内容时返回 {@code true} */
    public boolean canUndo() {
        return document.canUndo();
    }

    /** @return 文档存在可重做内容时返回 {@code true} */
    public boolean canRedo() {
        return document.canRedo();
    }

    /**
     * @return 当前选区的文本；无选区时返回空串
     */
    public String getSelectedText() {
        if (!primaryCaret.hasSelection()) return "";
        TextRange range = TextRange.of(
                primaryCaret.selectionStartLine(), primaryCaret.selectionStartCol(),
                primaryCaret.selectionEndLine(), primaryCaret.selectionEndCol());
        return document.getText(range);
    }

    /** 把选区文本写入系统剪贴板；无选区时静默返回。 */
    public void copy() {
        String selectedText = getSelectedText();
        if (selectedText.isEmpty()) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(selectedText);
        Clipboard.getSystemClipboard().setContent(content);
    }

    /**
     * 粘贴指定文本：先删除选区再在光标处插入。
     *
     * <p>多光标状态下“删选区 + 插入”合并为单个复合撤销单元。</p>
     *
     * @param text 待粘贴文本；只读、{@code null} 或空串时静默返回
     */
    public void paste(String text) {
        if (isReadOnly()) return;
        if (text == null || text.isEmpty()) return;
        if (!extraCarets.isEmpty()) {
            document.beginCompoundEdit();
            try {
                deleteSelection();
                insertText(text);
            } finally {
                document.endCompoundEdit();
            }
            return;
        }
        if (primaryCaret.hasSelection()) {
            deleteSelection();
        }
        insertText(text);
    }

    /** 从系统剪贴板读取文本并粘贴（剪贴板无文本时静默返回）。 */
    public void paste() {
        String clipboardText = Clipboard.getSystemClipboard().getString();
        if (clipboardText != null) {
            paste(clipboardText);
        }
    }

    /**
     * 光标位置变化监听器。
     *
     * @see #addCaretChangeListener(CaretChangeListener)
     */
    @FunctionalInterface
    public interface CaretChangeListener {
        /**
         * 光标位置变化时回调。
         *
         * @param line 新行号（0 起）
         * @param col  新列号（0 起）
         */
        void onCaretChanged(int line, int col);
    }
}
