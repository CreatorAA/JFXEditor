package org.pigeonshouse.javafx.editor.editor;

import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Orientation;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SkinBase;
import javafx.scene.input.InputMethodRequests;
import javafx.scene.input.InputMethodTextRun;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.util.Duration;
import org.pigeonshouse.javafx.editor.core.model.Position;
import org.pigeonshouse.javafx.editor.core.model.TextRange;
import org.pigeonshouse.javafx.editor.editor.caret.EditorCaret;
import org.pigeonshouse.javafx.editor.editor.decoration.Decoration;
import org.pigeonshouse.javafx.editor.editor.decoration.DecorationHoverListener;
import org.pigeonshouse.javafx.editor.editor.decoration.DecorationType;
import org.pigeonshouse.javafx.editor.editor.decoration.TextDecorationStyle;
import org.pigeonshouse.javafx.editor.editor.input.KeyBinding;
import org.pigeonshouse.javafx.editor.editor.input.KeyBindingRegistry;
import org.pigeonshouse.javafx.editor.editor.render.LineOffsetMap;
import org.pigeonshouse.javafx.editor.editor.render.RenderContext;
import org.pigeonshouse.javafx.editor.editor.render.RenderLayer;
import org.pigeonshouse.javafx.editor.editor.render.RenderOffset;
import org.pigeonshouse.javafx.editor.syntax.HighlightStyle;
import org.pigeonshouse.javafx.editor.syntax.Token;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

/**
 * {@link JFXEditor} 的默认皮肤：全部视觉与交互实现。
 *
 * <p><strong>结构：</strong>子节点为 Canvas（立即模式绘制）、光标矩形、
 * 垂直/水平原生 ScrollBar。渲染采用“可见行裁剪”的虚拟化策略，
 * 每帧只绘可见区间。</p>
 *
 * <p><strong>坐标系约定（关键）：</strong></p>
 * <ul>
 *   <li>垂直 ScrollBar 的 value/max/visibleAmount 单位是
 *       <strong>视觉行号</strong>（非像素，混用会导致滚动错乱）；
 *       水平 ScrollBar 单位是像素；</li>
 *   <li>视觉行 = 文档行 + 之前锚点插入的额外行（见 {@link LineOffsetMap}）；</li>
 *   <li>行顶 y = (视觉行 + 基准偏移 − scrollY) × 行高，文本基线在
 *       y + 0.8 × 行高；</li>
 *   <li>文本宽度一律经 {@code helperText} 实测，点击定位用纯二分查找。</li>
 * </ul>
 *
 * <p><strong>绘制顺序：</strong>背景+当前行 → 选区 → 文本（逐 token 上色，
 * 超长行走视口裁剪路径）→ 装饰 → gutter → 光标 → IME 组合文本 →
 * 自定义渲染层（同锚叠放时叠加基准偏移）。</p>
 *
 * <p><strong>按键：</strong>全部预设动作经 {@link KeyBindingRegistry} 统一
 * 注册与分发，{@link #dispose()} 时逐一注销——这是 Skin 可替换性的
 * 关键不变量。所有方法均须在 JavaFX 应用线程执行。</p>
 *
 * @see JFXEditor
 * @see RenderLayer
 */
public class JFXEditorSkin extends SkinBase<JFXEditor> {

    /** 文本绘制画布（非托管，文本光标形状鼠标指针）。 */
    private final Canvas canvas;
    /** 垂直滚动条，单位为视觉行号。 */
    private final ScrollBar verticalScrollBar;
    /** 水平滚动条，单位为像素。 */
    private final ScrollBar horizontalScrollBar;
    /** 光标矩形节点（非托管）。 */
    private final Rectangle cursorNode;
    /** 光标闪烁时间线。 */
    private final Timeline caretBlink;
    /** 光标移动后延迟恢复闪烁的暂停过渡（实现“移动时常亮”）。 */
    private final PauseTransition caretPause;
    /** 当前闪烁相位的可见性。 */
    private boolean caretVisible;
    /** 当前被悬停的装饰；绘制时用 hoverColor 替代本色。 */
    private Decoration hoveredDecoration;
    /** IME 组合（未提交）文本，在光标处半透明绘制。 */
    private String compositionText = "";

    /** gutter 行号与行尾附注使用的缩小字体。 */
    private Font smallFont;

    private final ChangeListener<Font> fontListener;
    private final ChangeListener<Boolean> gutterVisibleListener;
    private final ChangeListener<Number> layoutSizeListener;
    private final InvalidationListener refreshListener;
    private final ChangeListener<org.pigeonshouse.javafx.editor.core.model.Position> navigateListener;
    private final InvalidationListener restyleListener;
    private final InvalidationListener caretStyleListener;
    private final InvalidationListener gutterFontScaleListener;
    private final ChangeListener<Boolean> canvasFocusListener;

    /** 返回当前生效的 gutter 宽度（像素）；隐藏时为 0。 */
    private int gutterWidth() {
        JFXEditor editor = getSkinnable();
        return editor.isGutterVisible() ? (int) editor.gutterWidth() : 0;
    }

    /** 共享文本测量节点（所有像素宽度实测都经由它）。 */
    private final Text helperText;

    /** 预设动作记录（actionId + 处理器），供 dispose 时注销。 */
    private record DefaultAction(String actionId, Runnable handler) {
    }

    /** 已注册的全部预设动作，dispose 时逐一移除。 */
    private final List<DefaultAction> defaultActions = new ArrayList<>();


    /** 光标移动后保持常亮的时长（毫秒）。 */
    private static final long CARET_PAUSE_DURATION_MS = 600;
    /** 滚轮每格滚动的视觉行数。 */
    private static final int SCROLL_LINES_PER_NOTCH = 3;

    /** 超过此字符数的行走视口裁剪绘制路径。 */
    private static final int LONG_LINE_THRESHOLD = 5000;

    /** 首选尺寸计算的内边距（像素）。 */
    private static final double CONTENT_PADDING = 16;

    /**
     * 创建皮肤：搭建子节点、挂接约 20 个属性监听器、注册全部
     * 预设按键与输入处理器，并启动光标闪烁。
     *
     * @param control 宿主控件
     */
    public JFXEditorSkin(JFXEditor control) {
        super(control);
        this.canvas = new Canvas();
        this.canvas.setCursor(Cursor.TEXT);
        this.helperText = new Text();
        this.helperText.setFont(control.font());

        this.fontListener = (obs, old, val) -> {
            smallFont = Font.font(val.getFamily(), val.getSize() * control.gutterFontScale());
            helperText.setFont(val);
            updateScrollBarBounds();
            redraw();
        };
        control.fontProperty().addListener(fontListener);
        this.gutterVisibleListener = (obs, old, val) -> {
            if (!Objects.equals(old, val)) {
                updateScrollBarBounds();
                redraw();
            }
        };
        control.gutterVisibleProperty().addListener(gutterVisibleListener);
        smallFont = Font.font(control.font().getFamily(), control.font().getSize() * control.gutterFontScale());

        this.verticalScrollBar = new ScrollBar();
        this.horizontalScrollBar = new ScrollBar();
        this.cursorNode = new Rectangle(0, 0, control.caretWidth(), 0);
        this.cursorNode.setFill(control.caretColor());
        this.cursorNode.setManaged(false);
        this.caretVisible = true;
        this.caretBlink = new Timeline(
                new KeyFrame(Duration.ZERO, e -> {
                    caretVisible = !caretVisible;
                    cursorNode.setVisible(caretVisible);
                }),
                new KeyFrame(control.caretBlinkRate())
        );
        this.caretPause = new PauseTransition(Duration.millis(CARET_PAUSE_DURATION_MS));
        this.caretPause.setOnFinished(e -> caretBlink.playFromStart());
        verticalScrollBar.setOrientation(Orientation.VERTICAL);
        horizontalScrollBar.setOrientation(Orientation.HORIZONTAL);
        verticalScrollBar.setManaged(false);
        horizontalScrollBar.setManaged(false);
        verticalScrollBar.setMin(0);
        horizontalScrollBar.setMin(0);

        canvas.setManaged(false);

        getChildren().addAll(canvas, cursorNode, verticalScrollBar, horizontalScrollBar);

        setupScrollBars();
        setupInputHandlers();
        registerDefaultKeyBindings();

        caretBlink.setCycleCount(Timeline.INDEFINITE);
        caretBlink.play();

        this.layoutSizeListener = (obs, old, val) -> getSkinnable().requestLayout();
        control.widthProperty().addListener(layoutSizeListener);
        control.heightProperty().addListener(layoutSizeListener);
        this.refreshListener = obs -> redraw();
        control.repaintsProperty().addListener(refreshListener);
        this.navigateListener = (obs, old, pos) -> {
            if (pos != null) {
                scrollToCursor();
                redraw();
            }
        };
        control.navigateToPositionProperty().addListener(navigateListener);

        this.restyleListener = obs -> {
            cursorNode.setFill(control.caretColor());
            updateScrollBarBounds();
            redraw();
        };
        control.backgroundColorProperty().addListener(restyleListener);
        control.textColorProperty().addListener(restyleListener);
        control.selectionColorProperty().addListener(restyleListener);
        control.currentLineColorProperty().addListener(restyleListener);
        control.caretColorProperty().addListener(restyleListener);
        control.gutterBackgroundColorProperty().addListener(restyleListener);
        control.gutterTextColorProperty().addListener(restyleListener);
        control.afterTextColorProperty().addListener(restyleListener);
        control.gutterWidthProperty().addListener(restyleListener);
        control.lineHeightMultiplierProperty().addListener(restyleListener);
        control.ghostTextColorProperty().addListener(restyleListener);

        this.caretStyleListener = obs -> {
            cursorNode.setWidth(control.caretWidth());
            applyCaretBlinkRate(control.caretBlinkRate());
        };
        control.caretWidthProperty().addListener(caretStyleListener);
        control.caretBlinkRateProperty().addListener(caretStyleListener);

        this.gutterFontScaleListener = obs -> {
            smallFont = Font.font(control.font().getFamily(), control.font().getSize() * control.gutterFontScale());
            redraw();
        };
        control.gutterFontScaleProperty().addListener(gutterFontScaleListener);

        this.canvasFocusListener = (obs, old, focused) -> control.updateFocusFromSkin(focused);
        canvas.focusedProperty().addListener(canvasFocusListener);

        redraw();
    }

    /** 重建闪烁时间线以应用新的闪烁周期，并重置为可见相位。 */
    private void applyCaretBlinkRate(Duration interval) {
        caretBlink.stop();
        caretBlink.getKeyFrames().setAll(
                new KeyFrame(Duration.ZERO, e -> {
                    caretVisible = !caretVisible;
                    cursorNode.setVisible(caretVisible);
                }),
                new KeyFrame(interval)
        );
        caretVisible = true;
        cursorNode.setVisible(true);
        caretBlink.playFromStart();
    }

    /** 滚动条数值变化直接触发重绘。 */
    private void setupScrollBars() {
        verticalScrollBar.valueProperty().addListener((obs, old, val) -> redraw());
        horizontalScrollBar.valueProperty().addListener((obs, old, val) -> redraw());
    }

    /** 挂接键盘/鼠标/滚轮/IME 全部输入处理器。 */
    private void setupInputHandlers() {
        canvas.setFocusTraversable(true);
        canvas.setOnKeyPressed(this::handleKeyPress);
        canvas.setOnKeyTyped(this::handleKeyTyped);
        canvas.setOnMousePressed(this::handleMousePressed);
        canvas.setOnMouseDragged(this::handleMouseDragged);
        canvas.setOnScroll(this::handleScroll);
        canvas.setOnMouseMoved(this::handleMouseMoved);
        canvas.setOnMouseExited(this::handleMouseExited);
        setupInputMethodSupport();
    }

    /**
     * 配置输入法支持：提供光标屏幕坐标给 IME 候选窗，接收提交/
     * 组合文本（只读模式直接清空并消费事件）。
     */
    private void setupInputMethodSupport() {
        canvas.setInputMethodRequests(new InputMethodRequests() {
            @Override
            public Point2D getTextLocation(int offset) {
                JFXEditor editor = getSkinnable();
                EditorCaret caret = editor.primaryCaret();
                LineOffsetMap offsetMap = buildOffsetMap();
                double inlineOffset = offsetMap.getInlineOffsetAt(caret.line(), caret.column());
                double x = gutterWidth() + measureWidthUpToCol(caret.line(), caret.column())
                        + inlineOffset - horizontalScrollBar.getValue();
                double visualLine = offsetMap.getVisualLine(caret.line());
                double y = (visualLine - verticalScrollBar.getValue()) * editor.calculateLineHeight()
                        + editor.calculateLineHeight();
                Point2D screen = canvas.localToScreen(x, y);
                return screen != null ? screen : new Point2D(0, 0);
            }

            @Override
            public int getLocationOffset(int x, int y) {
                return 0;
            }

            @Override
            public void cancelLatestCommittedText() {
            }

            @Override
            public String getSelectedText() {
                return getSkinnable().getSelectedText();
            }
        });

        canvas.setOnInputMethodTextChanged(e -> {
            JFXEditor editor = getSkinnable();
            if (editor.isReadOnly()) {
                compositionText = "";
                e.consume();
                return;
            }
            String committed = e.getCommitted();
            if (!committed.isEmpty()) {
                if (editor.primaryCaret().hasSelection()) {
                    editor.deleteSelection();
                }
                editor.insertText(committed);
            }
            StringBuilder composed = new StringBuilder();
            if (e.getComposed() != null) {
                for (InputMethodTextRun run : e.getComposed()) {
                    composed.append(run.getText());
                }
            }
            compositionText = composed.toString();
            redraw();
            updateScrollBarBounds();
            scrollToCursor();
            e.consume();
        });
    }

    /** {@inheritDoc} 按滚动条可见性扣减画布尺寸、摆放滚动条并刷新范围。 */
    @Override
    protected void layoutChildren(double contentX, double contentY, double contentWidth, double contentHeight) {
        double sw = verticalScrollBar.isVisible() ? verticalScrollBar.prefWidth(-1) : 0;
        double sh = horizontalScrollBar.isVisible() ? horizontalScrollBar.prefHeight(-1) : 0;

        double canvasW = Math.max(0, contentWidth - sw);
        double canvasH = Math.max(0, contentHeight - sh);

        canvas.resizeRelocate(contentX, contentY, canvasW, canvasH);
        canvas.setWidth(canvasW);
        canvas.setHeight(canvasH);

        verticalScrollBar.resizeRelocate(contentX + contentWidth - sw, contentY, sw, canvasH);
        horizontalScrollBar.resizeRelocate(contentX, contentY + contentHeight - sh, canvasW, sh);

        updateScrollBarBounds(canvasW, canvasH);
        redraw();
    }

    /** {@inheritDoc} 基于全文实测最宽行加内边距，最小 200。 */
    @Override
    protected double computePrefWidth(double height, double topInset, double rightInset, double bottomInset, double leftInset) {
        JFXEditor editor = getSkinnable();
        int totalLines = editor.document().getLineCount();

        double maxContentWidth = 0;
        for (int i = 0; i < totalLines; i++) {
            String lineText = editor.document().getLine(i);
            if (lineText != null) {
                double lineW = measureWidth(lineText);
                if (lineW > maxContentWidth) maxContentWidth = lineW;
            }
        }
        double contentBased = gutterWidth() + maxContentWidth + CONTENT_PADDING * 2;
        return Math.max(contentBased, computeMinWidth(height, topInset, rightInset, bottomInset, leftInset));
    }

    /** {@inheritDoc} 基于总行数乘行高加内边距，最小 100。 */
    @Override
    protected double computePrefHeight(double width, double topInset, double rightInset, double bottomInset, double leftInset) {
        JFXEditor editor = getSkinnable();
        int totalLines = Math.max(1, editor.document().getLineCount());
        double contentBased = totalLines * editor.calculateLineHeight() + CONTENT_PADDING * 2;
        return Math.max(contentBased, computeMinHeight(width, topInset, rightInset, bottomInset, leftInset));
    }

    /** {@inheritDoc} 固定最小宽 200 像素。 */
    @Override
    protected double computeMinWidth(double height, double topInset, double rightInset, double bottomInset, double leftInset) {
        return 200;
    }

    /** {@inheritDoc} 固定最小高 100 像素。 */
    @Override
    protected double computeMinHeight(double width, double topInset, double rightInset, double bottomInset, double leftInset) {
        return 100;
    }

    /** 以当前画布尺寸刷新滚动条范围。 */
    private void updateScrollBarBounds() {
        updateScrollBarBounds(canvas.getWidth(), canvas.getHeight());
    }

    /**
     * 刷新滚动条范围与可见性：总视觉行 = 文档行 + 偏移表额外行；
     * 垂直条按视觉行、水平条按实测最宽行像素，并钳制当前值。
     */
    private void updateScrollBarBounds(double canvasW, double canvasH) {
        if (canvasW <= 0 || canvasH <= 0) return;
        JFXEditor editor = getSkinnable();
        int totalLines = editor.document().getLineCount();

        int totalExtraLines = buildOffsetMap().totalExtraLines();

        double lineH = editor.calculateLineHeight();
        double visibleLines = Math.max(1, canvasH / lineH);
        int totalVisualLines = totalLines + totalExtraLines;

        boolean needV = totalVisualLines > visibleLines;
        verticalScrollBar.setVisible(needV);
        if (!needV) {
            verticalScrollBar.setMax(0);
            verticalScrollBar.setVisibleAmount(0);
            verticalScrollBar.setValue(0);
        } else {
            double scrollableLines = totalVisualLines - visibleLines;
            verticalScrollBar.setMax(scrollableLines);
            verticalScrollBar.setVisibleAmount(scrollableLines * visibleLines / totalVisualLines);
            if (verticalScrollBar.getValue() > scrollableLines) {
                verticalScrollBar.setValue(scrollableLines);
            }
        }

        double maxContentWidth = 0;
        for (int i = 0; i < totalLines; i++) {
            String lineText = editor.document().getLine(i);
            if (lineText != null) {
                double lineW = measureWidth(lineText);
                if (lineW > maxContentWidth) maxContentWidth = lineW;
            }
        }
        double visibleWidth = Math.max(0, canvasW - gutterWidth());
        boolean needH = maxContentWidth > visibleWidth;
        horizontalScrollBar.setVisible(needH);
        if (!needH) {
            horizontalScrollBar.setMax(0);
            horizontalScrollBar.setVisibleAmount(0);
            horizontalScrollBar.setValue(0);
        } else {
            double scrollableWidth = maxContentWidth - visibleWidth;
            horizontalScrollBar.setMax(scrollableWidth);
            horizontalScrollBar.setVisibleAmount(scrollableWidth * visibleWidth / maxContentWidth);
            if (horizontalScrollBar.getValue() > scrollableWidth) {
                horizontalScrollBar.setValue(scrollableWidth);
            }
        }
    }

    /** 重绘入口：画布尺寸合法时执行全量重绘。 */
    private void redraw() {
        JFXEditor editor = getSkinnable();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;
        GraphicsContext gc = canvas.getGraphicsContext2D();
        performFullRedraw(gc, w, h, editor);
    }


    /**
     * 返回按 {@link RenderLayer#getZOrder()} 升序稳定排序的渲染层快照
     * （同 zOrder 保持注册顺序），beforeRender/render/偏移汇总均经由此顺序。
     */
    private List<RenderLayer> sortedLayers() {
        List<RenderLayer> layers = new ArrayList<>(getSkinnable().renderLayers());
        layers.sort(Comparator.comparingInt(RenderLayer::getZOrder));
        return layers;
    }

    /**
     * 汇总所有渲染层的偏移声明构建行偏移表。
     *
     * <p>注意此处以 {@code null} 上下文调用 {@code getRenderOffsets}，
     * 自定义层实现必须容忍 {@code null} 参数。</p>
     */
    private LineOffsetMap buildOffsetMap() {
        LineOffsetMap map = new LineOffsetMap();
        for (RenderLayer layer : sortedLayers()) {
            for (RenderOffset offset : layer.getRenderOffsets(null)) {
                map.add(offset);
            }
        }
        return map;
    }

    /**
     * 全量重绘主流程：清屏 → 构建偏移表 → 换算可见窗口（仅绘可见行，
     * 实现虚拟滚动）→ 构造 {@link RenderContext} 并回调 beforeRender →
     * 按固定顺序绘制 → 渲染层循环（用 TreeMap 记录每锚点已消耗插入行，
     * 后续层同锚插入时叠加基准偏移，避免多层幽灵行互相覆盖）。
     */
    private void performFullRedraw(GraphicsContext gc, double w, double h, JFXEditor editor) {
        gc.clearRect(0, 0, w, h);

        double lineH = editor.calculateLineHeight();
        Font font = editor.font();

        int totalLines = editor.document().getLineCount();

        gc.setFont(font);
        gc.setFill(editor.textColor());
        gc.setStroke(editor.textColor());

        LineOffsetMap offsetMap = buildOffsetMap();
        int firstVisual = (int) verticalScrollBar.getValue();
        int lastVisual = (int) (verticalScrollBar.getValue() + h / lineH);
        int firstLine = Math.max(0, offsetMap.getDocumentLine(firstVisual));
        int lastLine = firstLine;
        for (int dl = firstLine; dl < totalLines; dl++) {
            if (offsetMap.getVisualLine(dl) > lastVisual) break;
            lastLine = dl;
        }
        lastLine = Math.min(totalLines - 1, lastLine);

        RenderContext ctx = RenderContext.of(
                editor.document(), editor.highlightEngine(), helperText, lineH,
                firstLine, lastLine, horizontalScrollBar.getValue(), verticalScrollBar.getValue(), w, h, offsetMap,
                gutterWidth(), editor.ghostTextColor()
        );

        List<RenderLayer> layers = sortedLayers();
        for (RenderLayer layer : layers) {
            layer.beforeRender(ctx);
        }

        drawBackground(gc, ctx, editor);
        drawSelections(gc, ctx, editor);
        drawText(gc, ctx, editor);
        drawDecorations(gc, ctx, editor);
        drawGutter(gc, ctx, editor);
        updateCursorPosition(editor, ctx);
        drawCompositionText(gc, ctx, editor);

        TreeMap<Integer, Integer> consumedPerAnchor = new TreeMap<>();
        for (RenderLayer layer : layers) {
            List<RenderOffset> offsets = layer.getRenderOffsets(ctx);

            int anchorConsumed = 0;
            for (RenderOffset offset : offsets) {
                if (offset instanceof RenderOffset.LineInsertion li) {
                    anchorConsumed += consumedPerAnchor.getOrDefault(li.anchorLine(), 0);
                }
            }

            RenderContext layerCtx = anchorConsumed > 0
                    ? ctx.withVisualLineBaseOffset(anchorConsumed)
                    : ctx;

            layer.render(gc, layerCtx);

            for (RenderOffset offset : offsets) {
                if (offset instanceof RenderOffset.LineInsertion(int anchorLine, int extraLines)) {
                    consumedPerAnchor.merge(anchorLine, extraLines, Integer::sum);
                }
            }
        }
    }

    /** 用共享测量节点实测文本像素宽度。 */
    private double measureWidth(String text) {
        if (text == null || text.isEmpty()) return 0;
        helperText.setText(text);
        return helperText.getLayoutBounds().getWidth();
    }

    /** 实测某行前 {@code col} 列的像素宽度（列会钳到行长）。 */
    private double measureWidthUpToCol(int line, int col) {
        JFXEditor editor = getSkinnable();
        if (line < 0 || line >= editor.document().getLineCount() || col <= 0) return 0;
        int lineLen = editor.document().getLineLength(line);
        int safeCol = Math.min(col, lineLen);
        if (safeCol <= 0) return 0;
        return measureWidth(editor.document().getLineSegment(line, 0, safeCol));
    }

    /**
     * 像素 x 坐标反解为列号：纯二分查找（基于真实文本测量），
     * 最后归到距离较近的一侧边界。
     */
    private int getColFromX(int line, double x) {
        JFXEditor editor = getSkinnable();
        if (line < 0 || line >= editor.document().getLineCount()) return 0;
        int lineLen = editor.document().getLineLength(line);
        if (lineLen == 0 || x <= 0) return 0;

        double fullWidth = measureWidthUpToCol(line, lineLen);
        if (x >= fullWidth) return lineLen;

        int low = 0;
        int high = lineLen;
        while (high - low > 1) {
            int mid = (low + high) >>> 1;
            double midX = measureWidthUpToCol(line, mid);
            if (midX <= x) {
                low = mid;
            } else {
                high = mid;
            }
        }

        double lowX = measureWidthUpToCol(line, low);
        double highX = measureWidthUpToCol(line, high);
        if (x - lowX < highX - x) {
            return low;
        } else {
            return high;
        }
    }

    /** 超长行专用：按 200 字符分段累计宽度定位目标像素偏移对应的列。 */
    private int findColForOffset(int line, double targetOffset) {
        if (targetOffset <= 0) return 0;
        JFXEditor editor = getSkinnable();
        int lineLen = editor.document().getLineLength(line);
        if (lineLen == 0) return 0;

        int step = 200;
        double accWidth = 0;
        int pos = 0;
        while (pos < lineLen) {
            int end = Math.min(pos + step, lineLen);
            String seg = editor.document().getLineSegment(line, pos, end);
            double segW = measureWidth(seg);
            if (accWidth + segW >= targetOffset) {
                for (int i = 0; i < seg.length(); i++) {
                    String c = seg.substring(i, i + 1);
                    double cw = measureWidth(c);
                    if (accWidth + cw >= targetOffset) return pos + i;
                    accWidth += cw;
                }
                return end;
            }
            accWidth += segW;
            pos = end;
        }
        return lineLen;
    }

    /** 绘制背景色与当前行高亮。 */
    private void drawBackground(GraphicsContext gc, RenderContext ctx, JFXEditor editor) {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        gc.setFill(editor.backgroundColor());
        gc.fillRect(0, 0, w, h);
        drawCurrentLineHighlight(gc, ctx, editor, ctx.firstVisibleLine(), ctx.lastVisibleLine());
    }

    /** 绘制可见区间内的选区背景。 */
    private void drawSelections(GraphicsContext gc, RenderContext ctx, JFXEditor editor) {
        drawSelectionsInRange(gc, ctx, editor, ctx.firstVisibleLine(), ctx.lastVisibleLine());
    }

    /** 绘制可见区间内的文本。 */
    private void drawText(GraphicsContext gc, RenderContext ctx, JFXEditor editor) {
        drawTextInRange(gc, ctx, editor, ctx.firstVisibleLine(), ctx.lastVisibleLine());
    }

    /** 边界安全的子串截取（越界自动钳制，不抛异常）。 */
    private String safeSubstring(String str, int start, int end) {
        if (str == null || str.isEmpty()) {
            return "";
        }
        if (start < 0) start = 0;
        if (start >= str.length()) return "";
        if (end > str.length()) end = str.length();
        if (end < start) end = start;
        return str.substring(start, end);
    }

    /** 绘制与可见区间相交的全部装饰。 */
    private void drawDecorations(GraphicsContext gc, RenderContext ctx, JFXEditor editor) {
        drawDecorationsInRange(gc, ctx, editor, ctx.firstVisibleLine(), ctx.lastVisibleLine());
    }

    /**
     * 按类型绘制单个装饰：整行背景、列区间高亮、下划线（直线/波浪/
     * 虚线）、删除线（行高 50%）、行尾附注（缩小字体）；被悬停时
     * 用 hoverColor 替代本色。
     */
    private void drawSingleDecoration(GraphicsContext gc, RenderContext ctx, JFXEditor editor, Decoration d) {
        if (d.line() < 0 || d.line() >= editor.document().getLineCount()) {
            return;
        }

        double y = ctx.getVisualLineY(d.line());

        switch (d.type()) {
            case LINE_BACKGROUND -> {
                Color effectiveColor = (d == hoveredDecoration && d.hoverColor() != null)
                        ? d.hoverColor() : d.color();
                gc.setFill(effectiveColor);
                gc.fillRect(0, y, canvas.getWidth(), ctx.lineHeight());
            }
            case TEXT_HIGHLIGHT -> {
                Color effectiveColor = (d == hoveredDecoration && d.hoverColor() != null)
                        ? d.hoverColor() : d.color();
                gc.setFill(effectiveColor);
                double inlineOffset = ctx.lineOffsetMap().getInlineOffsetAt(d.line(), d.startCol());
                double x = gutterWidth() + measureWidthUpToCol(d.line(), d.startCol()) + inlineOffset - horizontalScrollBar.getValue();
                double w = measureWidthUpToCol(d.line(), d.endCol()) - measureWidthUpToCol(d.line(), d.startCol());
                gc.fillRect(x, y, w, ctx.lineHeight());
            }
            case TEXT_UNDERLINE -> {
                Color effectiveColor = (d == hoveredDecoration && d.hoverColor() != null)
                        ? d.hoverColor() : d.color();
                gc.setStroke(effectiveColor);
                gc.setLineWidth(1.5);
                double inlineOffset = ctx.lineOffsetMap().getInlineOffsetAt(d.line(), d.startCol());
                double x1 = gutterWidth() + measureWidthUpToCol(d.line(), d.startCol()) + inlineOffset - horizontalScrollBar.getValue();
                double x2 = gutterWidth() + measureWidthUpToCol(d.line(), d.endCol()) + inlineOffset - horizontalScrollBar.getValue();
                double lineY = y + ctx.lineHeight() - 2;
                if (d.decorationStyle() == TextDecorationStyle.WAVY) {
                    drawWavyLine(gc, x1, lineY, x2, lineY);
                } else if (d.decorationStyle() == TextDecorationStyle.DASHED) {
                    gc.setLineDashes(4, 4);
                    gc.strokeLine(x1, lineY, x2, lineY);
                    gc.setLineDashes();
                } else {
                    gc.strokeLine(x1, lineY, x2, lineY);
                }
            }
            case TEXT_STRIKETHROUGH -> {
                Color effectiveColor = (d == hoveredDecoration && d.hoverColor() != null)
                        ? d.hoverColor() : d.color();
                gc.setStroke(effectiveColor);
                gc.setLineWidth(1);
                double inlineOffset = ctx.lineOffsetMap().getInlineOffsetAt(d.line(), d.startCol());
                double x1 = gutterWidth() + measureWidthUpToCol(d.line(), d.startCol()) + inlineOffset - horizontalScrollBar.getValue();
                double x2 = gutterWidth() + measureWidthUpToCol(d.line(), d.endCol()) + inlineOffset - horizontalScrollBar.getValue();
                double lineY = y + ctx.lineHeight() * 0.5;
                gc.strokeLine(x1, lineY, x2, lineY);
            }
            case AFTER_TEXT -> {
                Color effectiveColor = (d == hoveredDecoration && d.hoverColor() != null)
                        ? d.hoverColor() : editor.afterTextColor();
                gc.setFill(effectiveColor);
                String lineText = editor.document().getLine(d.line());
                if (lineText == null || lineText.isEmpty()) {
                    lineText = "";
                }
                gc.setFont(smallFont);
                int lineLen = lineText.length();
                double inlineOffset = ctx.lineOffsetMap().getInlineOffsetAt(d.line(), lineLen);
                double x = gutterWidth() + measureWidthUpToCol(d.line(), lineLen) + inlineOffset + measureWidth("  ") - horizontalScrollBar.getValue();
                gc.fillText(d.afterText(), x, y + ctx.lineHeight() * 0.8);
                gc.setFont(editor.font());
            }
        }
    }

    /** 用锁齿折线绘制波浪线（振幅 2，波长 6）。 */
    private void drawWavyLine(GraphicsContext gc, double x1, double y, double x2, double y2) {
        double amplitude = 2;
        double wavelength = 6;
        double dx = x2 - x1;
        if (dx <= 0) return;
        int segments = (int) (dx / (wavelength / 2));
        gc.beginPath();
        gc.moveTo(x1, y);
        for (int i = 0; i <= segments; i++) {
            double sx = x1 + i * (wavelength / 2);
            double sy = y + ((i % 2 == 0) ? amplitude : -amplitude);
            gc.lineTo(sx, sy);
        }
        gc.stroke();
        gc.closePath();
    }

    /**
     * 更新光标矩形位置：越界即隐藏；有 IME 组合文本时右移；
     * 停止闪烁并延迟 600ms 恢复，实现“移动时常亮”。
     */
    private void updateCursorPosition(JFXEditor editor, RenderContext ctx) {
        caretBlink.stop();
        caretPause.stop();
        caretVisible = true;
        EditorCaret caret = editor.primaryCaret();
        double inlineOffset = ctx.lineOffsetMap().getInlineOffsetAt(caret.line(), caret.column());
        double x = gutterWidth() + measureWidthUpToCol(caret.line(), caret.column()) + inlineOffset - horizontalScrollBar.getValue();
        if (compositionText != null && !compositionText.isEmpty()) {
            x += measureWidth(compositionText);
        }
        double y = ctx.getVisualLineY(caret.line());
        double lineH = editor.calculateLineHeight();
        if (x < gutterWidth() - 1 || x > canvas.getWidth() || y + lineH < 0 || y > canvas.getHeight()) {
            cursorNode.setVisible(false);
            return;
        }
        cursorNode.setX(x);
        cursorNode.setY(y);
        cursorNode.setHeight(lineH);
        cursorNode.setVisible(true);
        caretPause.playFromStart();
    }

    /** 绘制 gutter 背景与内容（隐藏时直接返回）。 */
    private void drawGutter(GraphicsContext gc, RenderContext ctx, JFXEditor editor) {
        if (!editor.isGutterVisible()) return;
        gc.setFill(editor.gutterBackgroundColor());
        gc.fillRect(0, 0, gutterWidth(), canvas.getHeight());
        drawGutterContent(gc, ctx, editor, ctx.firstVisibleLine(), ctx.lastVisibleLine());
    }

    /** 无选区时才绘制光标所在行的高亮背景。 */
    private void drawCurrentLineHighlight(GraphicsContext gc, RenderContext ctx, JFXEditor editor, int startLine, int endLine) {
        int currentLine = editor.primaryCaret().line();
        for (int i = startLine; i <= endLine; i++) {
            if (i == currentLine && !editor.primaryCaret().hasSelection()) {
                if (!ctx.isVisualLineVisible(i)) continue;
                double y = ctx.getVisualLineY(i);
                gc.setFill(editor.currentLineColor());
                gc.fillRect(0, y, canvas.getWidth(), ctx.lineHeight());
            }
        }
    }

    /** 绘制选区：跨行选区首尾行取实际列，中间行整行。 */
    private void drawSelectionsInRange(GraphicsContext gc, RenderContext ctx, JFXEditor editor, int startLine, int endLine) {
        EditorCaret caret = editor.primaryCaret();
        if (!caret.hasSelection()) return;

        if (editor.document().getLineCount() == 0) return;

        gc.setFill(editor.selectionColor());
        int selStart = Math.max(startLine, caret.selectionStartLine());
        int selEnd = Math.min(endLine, caret.selectionEndLine());

        for (int line = selStart; line <= selEnd; line++) {
            int startCol = caret.selectionStartCol();
            int endCol = caret.selectionEndCol();
            if (line != caret.selectionStartLine()) startCol = 0;
            if (line != caret.selectionEndLine()) endCol = editor.document().getLineLength(line);

            double y = ctx.getVisualLineY(line);
            double inlineOffset = ctx.lineOffsetMap().getInlineOffsetAt(line, startCol);
            double x = gutterWidth() + measureWidthUpToCol(line, startCol) + inlineOffset - horizontalScrollBar.getValue();
            double width = measureWidthUpToCol(line, endCol) - measureWidthUpToCol(line, startCol);
            gc.fillRect(x, y, Math.max(width, 1), ctx.lineHeight());
        }
    }

    /**
     * 逐行绘制文本：有高亮引擎时逐 token 上色（token 间隙与尾巴用
     * 默认文本色）；超过 {@value #LONG_LINE_THRESHOLD} 字符的长行走
     * 视口裁剪路径。
     */
    private void drawTextInRange(GraphicsContext gc, RenderContext ctx, JFXEditor editor, int startLine, int endLine) {
        gc.setFont(editor.font());
        double viewportWidth = canvas.getWidth() - gutterWidth();

        for (int line = startLine; line <= endLine; line++) {
            if (line >= editor.document().getLineCount()) {
                break;
            }

            if (!ctx.isVisualLineVisible(line)) {
                continue;
            }

            double y = ctx.getVisualLineY(line) + ctx.lineHeight() * 0.8;
            String lineText = editor.document().getLine(line);

            if (lineText == null || lineText.isEmpty()) {
                continue;
            }

            int lineLen = lineText.length();
            if (lineLen > LONG_LINE_THRESHOLD) {
                drawLongLineViewport(gc, ctx, editor, line, lineText, lineLen, viewportWidth, y);
                continue;
            }

            if (editor.highlightEngine() != null) {
                List<Token> tokens = editor.highlightEngine().getTokens(line);
                int lastEnd = 0;

                for (Token token : tokens) {
                    int tokenStart = Math.min(token.start(), lineLen);
                    int tokenEnd = Math.min(token.end(), lineLen);

                    if (tokenStart > lastEnd) {
                        gc.setFill(editor.textColor());
                        String gapText = safeSubstring(lineText, lastEnd, tokenStart);
                        double inlineOffset = ctx.lineOffsetMap().getInlineOffsetAt(line, lastEnd);
                        double x = gutterWidth() + measureWidthUpToCol(line, lastEnd) + inlineOffset - horizontalScrollBar.getValue();
                        gc.fillText(gapText, x, y);
                    }

                    HighlightStyle style = editor.highlightEngine().getStyle(token.type());
                    gc.setFill(style.color());

                    if (tokenEnd > tokenStart) {
                        String text = safeSubstring(lineText, tokenStart, tokenEnd);
                        double inlineOffset = ctx.lineOffsetMap().getInlineOffsetAt(line, tokenStart);
                        double x = gutterWidth() + measureWidthUpToCol(line, tokenStart) + inlineOffset - horizontalScrollBar.getValue();
                        gc.fillText(text, x, y);
                    }
                    lastEnd = tokenEnd;
                }

                if (lastEnd < lineText.length()) {
                    gc.setFill(editor.textColor());
                    String tailText = safeSubstring(lineText, lastEnd, lineText.length());
                    double inlineOffset = ctx.lineOffsetMap().getInlineOffsetAt(line, lastEnd);
                    double x = gutterWidth() + measureWidthUpToCol(line, lastEnd) + inlineOffset - horizontalScrollBar.getValue();
                    gc.fillText(tailText, x, y);
                }
            } else {
                gc.setFill(editor.textColor());
                double x = gutterWidth() - horizontalScrollBar.getValue();
                gc.fillText(lineText, x, y);
            }
        }
    }

    /** 超长行视口裁剪绘制：只绘可见列区间附近的文本与 token。 */
    private void drawLongLineViewport(GraphicsContext gc, RenderContext ctx, JFXEditor editor,
                                       int line, String lineText, int lineLen,
                                       double viewportWidth, double y) {
        int startCol = findColForOffset(line, horizontalScrollBar.getValue());
        int endCol = findColForOffset(line, horizontalScrollBar.getValue() + viewportWidth + 100);
        endCol = Math.min(endCol + 10, lineLen);

        if (startCol >= endCol) return;

        if (editor.highlightEngine() != null) {
            List<Token> tokens = editor.highlightEngine().getTokens(line);

            for (Token token : tokens) {
                int tokenStart = Math.min(token.start(), lineLen);
                int tokenEnd = Math.min(token.end(), lineLen);

                if (tokenEnd <= startCol) continue;
                if (tokenStart >= endCol) break;

                int tStart = Math.max(tokenStart, startCol);
                int tEnd = Math.min(tokenEnd, endCol);
                if (tEnd <= tStart) continue;

                HighlightStyle style = editor.highlightEngine().getStyle(token.type());
                gc.setFill(style.color());

                String text = lineText.substring(tStart, tEnd);
                double inlineOffset = ctx.lineOffsetMap().getInlineOffsetAt(line, tStart);
                double x = gutterWidth() + measureWidthUpToCol(line, tStart) + inlineOffset - horizontalScrollBar.getValue();
                gc.fillText(text, x, y);
            }
            gc.setFill(editor.textColor());
        } else {
            String text = editor.document().getLineSegment(line, startCol, endCol);
            double inlineOffset = ctx.lineOffsetMap().getInlineOffsetAt(line, startCol);
            double x = gutterWidth() + measureWidthUpToCol(line, startCol) + inlineOffset - horizontalScrollBar.getValue();
            gc.setFill(editor.textColor());
            gc.fillText(text, x, y);
        }
    }

    /** 遍历全部装饰，跳过与可见区间无交集者。 */
    private void drawDecorationsInRange(GraphicsContext gc, RenderContext ctx, JFXEditor editor, int startLine, int endLine) {
        List<Decoration> allDecos = editor.decorationModel().getDecorations();
        for (Decoration d : allDecos) {
            if (d.endLine() < startLine || d.startLine() > endLine) continue;
            drawSingleDecoration(gc, ctx, editor, d);
        }
    }

    /** 绘制 gutter 行号（缩小字体）与 GUTTER_ICON 装饰（右缘内 14px）。 */
    private void drawGutterContent(GraphicsContext gc, RenderContext ctx, JFXEditor editor, int startLine, int endLine) {
        gc.setFont(smallFont);
        gc.setFill(editor.gutterTextColor());

        for (int line = startLine; line <= endLine; line++) {
            if (!ctx.isVisualLineVisible(line)) continue;
            double y = ctx.getVisualLineY(line) + ctx.lineHeight() * 0.8;
            String num = String.valueOf(line + 1);
            gc.fillText(num, 4, y);
        }

        List<Decoration> gutterDecos = editor.decorationModel().getByType(DecorationType.GUTTER_ICON);
        for (Decoration d : gutterDecos) {
            if (d.line() < startLine || d.line() > endLine) continue;
            double y = ctx.getVisualLineY(d.line()) + ctx.lineHeight() * 0.8;
            gc.setFill(d.color());
            String symbol = d.userData() instanceof String s ? s : "●";
            gc.fillText(symbol, gutterWidth() - 14, y);
        }
    }

    /** 把光标滚入可视区：垂直按视觉行、水平按像素。 */
    private void scrollToCursor() {
        JFXEditor editor = getSkinnable();
        EditorCaret caret = editor.primaryCaret();
        double lineH = editor.calculateLineHeight();
        LineOffsetMap offsetMap = buildOffsetMap();
        int visualLine = offsetMap.getVisualLine(caret.line());

        double vVal = verticalScrollBar.getValue();
        double visibleLineCount = canvas.getHeight() / lineH;

        if (visualLine < vVal) {
            verticalScrollBar.setValue(visualLine);
        } else if (visualLine + 1 > vVal + visibleLineCount) {
            verticalScrollBar.setValue(visualLine + 1 - visibleLineCount);
        }

        double cursorX = measureWidthUpToCol(caret.line(), caret.column());
        double hVal = horizontalScrollBar.getValue();
        double viewportW = canvas.getWidth() - gutterWidth();

        if (cursorX < hVal) {
            horizontalScrollBar.setValue(cursorX);
        } else if (cursorX > hVal + viewportW) {
            horizontalScrollBar.setValue(cursorX - viewportW);
        }
    }

    /**
     * 按键处理：全权交给 {@link KeyBindingRegistry#handle}；命中且
     * 标记需重绘时执行重绘、刷新滚动范围并滚到光标。
     */
    private void handleKeyPress(KeyEvent event) {
        KeyBindingRegistry registry = getSkinnable().keyBindingRegistry();
        if (registry.handle(event) && registry.isRedrawRequested()) {
            redraw();
            updateScrollBarBounds();
            scrollToCursor();
        }
    }

    /**
     * 注册全部预设按键：四向移动/选择、按词移动、Home/End、
     * 退格/删除/换行、复制/剪切/粘贴/撤销/重做/全选。
     * 全部经 {@link KeyBindingRegistry} 统一分发，不硬编码。
     */
    private void registerDefaultKeyBindings() {
        JFXEditor editor = getSkinnable();

        presetAction("caret-left", "光标左移", redrawing(() -> moveLeft(false, false)),
                KeyBinding.of(KeyCode.LEFT, "caret-left"));
        presetAction("caret-left-select", "向左选择", redrawing(() -> moveLeft(true, false)),
                KeyBinding.of(KeyCode.LEFT, "caret-left-select", KeyCombination.SHIFT_DOWN));
        presetAction("caret-left-word", "按词左移", redrawing(() -> moveLeft(false, true)),
                KeyBinding.of(KeyCode.LEFT, "caret-left-word", KeyCombination.SHORTCUT_DOWN));
        presetAction("caret-left-word-select", "按词向左选择", redrawing(() -> moveLeft(true, true)),
                KeyBinding.of(KeyCode.LEFT, "caret-left-word-select", KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));

        presetAction("caret-right", "光标右移", redrawing(() -> moveRight(false, false)),
                KeyBinding.of(KeyCode.RIGHT, "caret-right"));
        presetAction("caret-right-select", "向右选择", redrawing(() -> moveRight(true, false)),
                KeyBinding.of(KeyCode.RIGHT, "caret-right-select", KeyCombination.SHIFT_DOWN));
        presetAction("caret-right-word", "按词右移", redrawing(() -> moveRight(false, true)),
                KeyBinding.of(KeyCode.RIGHT, "caret-right-word", KeyCombination.SHORTCUT_DOWN));
        presetAction("caret-right-word-select", "按词向右选择", redrawing(() -> moveRight(true, true)),
                KeyBinding.of(KeyCode.RIGHT, "caret-right-word-select", KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));

        presetAction("caret-up", "光标上移", redrawing(() -> moveUp(false)),
                KeyBinding.of(KeyCode.UP, "caret-up"));
        presetAction("caret-up-select", "向上选择", redrawing(() -> moveUp(true)),
                KeyBinding.of(KeyCode.UP, "caret-up-select", KeyCombination.SHIFT_DOWN));
        presetAction("caret-down", "光标下移", redrawing(() -> moveDown(false)),
                KeyBinding.of(KeyCode.DOWN, "caret-down"));
        presetAction("caret-down-select", "向下选择", redrawing(() -> moveDown(true)),
                KeyBinding.of(KeyCode.DOWN, "caret-down-select", KeyCombination.SHIFT_DOWN));

        presetAction("caret-line-start", "移至行首", redrawing(() -> moveHome(false)),
                KeyBinding.of(KeyCode.HOME, "caret-line-start"));
        presetAction("caret-line-start-select", "选择至行首", redrawing(() -> moveHome(true)),
                KeyBinding.of(KeyCode.HOME, "caret-line-start-select", KeyCombination.SHIFT_DOWN));
        presetAction("caret-line-end", "移至行尾", redrawing(() -> moveEnd(false)),
                KeyBinding.of(KeyCode.END, "caret-line-end"));
        presetAction("caret-line-end-select", "选择至行尾", redrawing(() -> moveEnd(true)),
                KeyBinding.of(KeyCode.END, "caret-line-end-select", KeyCombination.SHIFT_DOWN));

        presetAction("delete-backward", "向前删除", redrawing(() -> !editor.isReadOnly() && deleteBackward()),
                KeyBinding.of(KeyCode.BACK_SPACE, "delete-backward"),
                KeyBinding.of(KeyCode.BACK_SPACE, "delete-backward", KeyCombination.SHIFT_DOWN));
        presetAction("delete-forward", "向后删除", redrawing(() -> !editor.isReadOnly() && deleteForward()),
                KeyBinding.of(KeyCode.DELETE, "delete-forward"));
        presetAction("insert-newline", "插入换行", redrawing(() -> !editor.isReadOnly() && insertNewline()),
                KeyBinding.of(KeyCode.ENTER, "insert-newline"),
                KeyBinding.of(KeyCode.ENTER, "insert-newline", KeyCombination.SHIFT_DOWN));

        presetAction("copy", "复制", editor::copy,
                KeyBinding.of(KeyCode.C, "copy", KeyCombination.SHORTCUT_DOWN));
        presetAction("cut", "剪切", redrawing(this::cutSelection),
                KeyBinding.of(KeyCode.X, "cut", KeyCombination.SHORTCUT_DOWN));
        presetAction("paste", "粘贴", redrawing(this::pasteClipboard),
                KeyBinding.of(KeyCode.V, "paste", KeyCombination.SHORTCUT_DOWN));
        presetAction("undo", "撤销", redrawing(editor::undo),
                KeyBinding.of(KeyCode.Z, "undo", KeyCombination.SHORTCUT_DOWN));
        presetAction("redo", "重做", redrawing(editor::redo),
                KeyBinding.of(KeyCode.Y, "redo", KeyCombination.SHORTCUT_DOWN),
                KeyBinding.of(KeyCode.Z, "redo", KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        presetAction("select-all", "全选", redrawing(this::selectAll),
                KeyBinding.of(KeyCode.A, "select-all", KeyCombination.SHORTCUT_DOWN));
    }

    /** 把绑定注册为 default 级、挂接处理器并记录供 dispose 清理。 */
    private void presetAction(String actionId, String label, Runnable action, KeyBinding... keys) {
        KeyBindingRegistry registry = getSkinnable().keyBindingRegistry();
        for (KeyBinding key : keys) {
            registry.registerDefault(key, label);
        }
        registry.onAction(actionId, action);
        defaultActions.add(new DefaultAction(actionId, action));
    }

    /** 包装动作：仅当状态确实变化（返回 true）才请求重绘，避免无效重绘。 */
    private Runnable redrawing(BooleanSupplier action) {
        return () -> {
            if (action.getAsBoolean()) {
                getSkinnable().keyBindingRegistry().requestRedraw();
            }
        };
    }

    /** 剪切：先复制，非只读时再删除选区。 */
    private boolean cutSelection() {
        JFXEditor editor = getSkinnable();
        if (!editor.primaryCaret().hasSelection()) {
            return false;
        }
        editor.copy();
        if (editor.isReadOnly()) {
            return false;
        }
        editor.deleteSelection();
        return true;
    }

    /** 粘贴剪贴板内容（只读时返回 false）。 */
    private boolean pasteClipboard() {
        JFXEditor editor = getSkinnable();
        if (editor.isReadOnly()) {
            return false;
        }
        compositionText = "";
        editor.paste();
        return true;
    }

    /** 全选：只设选区不动光标位置（遵循全选光标不动规范）。 */
    private boolean selectAll() {
        JFXEditor editor = getSkinnable();
        int lineCount = editor.document().getLineCount();
        if (lineCount == 0) {
            return false;
        }
        return editor.primaryCaret().select(
                0, 0, lineCount - 1, editor.document().getLineLength(lineCount - 1));
    }

    /**
     * 字符输入处理：过滤控制字符与快捷键组合（兼容 AltGr），
     * 替换选区后插入字符。
     */
    private void handleKeyTyped(KeyEvent event) {
        if (getSkinnable().isReadOnly()) return;
        String text = event.getCharacter();
        boolean ctrlLike = event.isControlDown() || event.isMetaDown();
        boolean altGr = event.isControlDown() && event.isAltDown();
        if (text.isEmpty() || text.charAt(0) == '\b' || text.charAt(0) == '\r'
                || (ctrlLike && !altGr) || (event.isAltDown() && !altGr)) return;

        compositionText = "";

        JFXEditor editor = getSkinnable();
        if (editor.primaryCaret().hasSelection()) {
            editor.deleteSelection();
        }
        editor.insertText(text);
        applyIndentAdjustment(editor, text);
        redraw();
        updateScrollBarBounds();
        scrollToCursor();
    }

    /**
     * 单字符输入后应用缩进策略的行首调整：策略返回非 {@code null}
     * 时整体替换该行行首空白，光标列随替换后的偏差同步。
     */
    private void applyIndentAdjustment(JFXEditor editor, String text) {
        if (text.length() != 1) {
            return;
        }
        int line = editor.primaryCaret().line();
        String newIndent = editor.getIndentStrategy()
                .adjustIndentOnType(editor.document(), line, text.charAt(0));
        if (newIndent == null) {
            return;
        }
        String content = editor.document().getLine(line);
        int leadingLen = 0;
        while (leadingLen < content.length()
                && (content.charAt(leadingLen) == ' ' || content.charAt(leadingLen) == '\t')) {
            leadingLen++;
        }
        if (newIndent.contentEquals(content.subSequence(0, leadingLen))) {
            return;
        }
        int caretCol = editor.primaryCaret().column();
        editor.document().beginBatch();
        try {
            editor.document().delete(TextRange.of(line, 0, line, leadingLen));
            if (!newIndent.isEmpty()) {
                editor.document().insert(line, 0, newIndent);
            }
        } finally {
            editor.document().endBatch();
        }
        int newCol = Math.max(0, caretCol - leadingLen + newIndent.length());
        editor.primaryCaret().moveTo(line, newCol);
        editor.fireCaretChanged(line, newCol);
    }

    /**
     * 鼠标按下：像素 y 换算视觉行再反解文档行，x 二分定位列；
     * Shift+点击扩展选区，否则移动光标。
     */
    private void handleMousePressed(MouseEvent event) {
        canvas.requestFocus();
        compositionText = "";
        int visualLine = (int) (event.getY() / getSkinnable().calculateLineHeight() + verticalScrollBar.getValue());
        int line = buildOffsetMap().getDocumentLine(visualLine);
        double textX = event.getX() - gutterWidth() + horizontalScrollBar.getValue();
        int col = getColFromX(line, textX);
        int[] clamped = clampPositionToDocumentBounds(line, col);
        EditorCaret caret = getSkinnable().primaryCaret();
        if (event.isShiftDown()) {
            caret.selectTo(clamped[0], clamped[1]);
        } else {
            caret.moveTo(clamped[0], clamped[1]);
        }
        getSkinnable().fireCaretChanged(caret.line(), caret.column());
        scrollToCursor();
        redraw();
    }

    /** 鼠标拖动：持续扩展选区并滚动跟随。 */
    private void handleMouseDragged(MouseEvent event) {
        int visualLine = (int) (event.getY() / getSkinnable().calculateLineHeight() + verticalScrollBar.getValue());
        int line = buildOffsetMap().getDocumentLine(visualLine);
        double textX = event.getX() - gutterWidth() + horizontalScrollBar.getValue();
        int col = getColFromX(line, textX);
        int[] clamped = clampPositionToDocumentBounds(line, col);
        getSkinnable().primaryCaret().selectTo(clamped[0], clamped[1]);
        getSkinnable().fireCaretChanged(clamped[0], clamped[1]);
        scrollToCursor();
        redraw();
    }

    /** 滚轮：每格 {@value #SCROLL_LINES_PER_NOTCH} 视觉行，钳制到 {@code [0, max]}（max 已是可滚动上限）。 */
    private void handleScroll(ScrollEvent event) {
        if (!verticalScrollBar.isVisible()) return;
        double delta = event.getDeltaY() > 0 ? -SCROLL_LINES_PER_NOTCH : SCROLL_LINES_PER_NOTCH;
        double newValue = verticalScrollBar.getValue() + delta;
        verticalScrollBar.setValue(Math.max(0, Math.min(verticalScrollBar.getMax(), newValue)));
    }

    /** 把行列钳制到文档合法范围，返回 {行, 列} 数组。 */
    private int[] clampPositionToDocumentBounds(int line, int col) {
        JFXEditor editor = getSkinnable();
        int lineCount = editor.document().getLineCount();

        if (lineCount == 0) {
            return new int[]{0, 0};
        }

        int clampedLine = Math.max(0, Math.min(line, lineCount - 1));
        int maxCol = editor.document().getLineLength(clampedLine);
        int clampedCol = Math.max(0, Math.min(col, maxCol));

        return new int[]{clampedLine, clampedCol};
    }

    /** 鼠标移动：在当前行装饰中查找可悬停且命中的最高优先级装饰。 */
    private void handleMouseMoved(MouseEvent event) {
        JFXEditor editor = getSkinnable();
        int visualLine = (int) (event.getY() / editor.calculateLineHeight() + verticalScrollBar.getValue());
        int line = buildOffsetMap().getDocumentLine(visualLine);
        double textX = event.getX() - gutterWidth() + horizontalScrollBar.getValue();
        int col = getColFromX(line, textX);

        if (line < 0 || line >= editor.document().getLineCount()) {
            return;
        }

        List<Decoration> lineDecos = editor.decorationModel().getDecorationsOnLine(line);
        Decoration newHover = findDecorationAt(lineDecos, line, col, editor);

        if (newHover != hoveredDecoration) {
            updateHover(newHover);
        }
    }

    /** 鼠标离开画布：清除悬停状态。 */
    private void handleMouseExited(MouseEvent event) {
        updateHover(null);
    }

    /** 切换悬停装饰：触发 onHoverEnd/onHoverStart 并按需重绘。 */
    private void updateHover(Decoration newHover) {
        boolean hasRedraw = false;
        if (hoveredDecoration != null) {
            DecorationHoverListener listener = hoveredDecoration.hoverListener();
            if (listener != null) {
                listener.onHoverEnd(hoveredDecoration);
            }
            hasRedraw = true;
        }

        hoveredDecoration = newHover;

        if (hoveredDecoration != null) {
            DecorationHoverListener listener = hoveredDecoration.hoverListener();
            if (listener != null) {
                listener.onHoverStart(hoveredDecoration);
            }
            hasRedraw = true;
        }

        if (hasRedraw) redraw();
    }

    /**
     * 悬停命中检测：LINE_BACKGROUND 整行命中；文本类按列区间
     * {@code [startCol, endCol)}；AFTER_TEXT 按行尾后虚拟区间；
     * 多个命中时取优先级最高者。
     */
    private static Decoration findDecorationAt(List<Decoration> lineDecos,
                                               int line, int col, JFXEditor editor) {
        Decoration best = null;
        int bestPriority = Integer.MIN_VALUE;

        for (Decoration d : lineDecos) {
            if (!isHoverable(d)) continue;

            boolean hit = switch (d.type()) {
                case LINE_BACKGROUND -> true;
                case TEXT_HIGHLIGHT, TEXT_UNDERLINE, TEXT_STRIKETHROUGH -> col >= d.startCol() && col < d.endCol();
                case AFTER_TEXT -> {
                    String lineText = editor.document().getLine(line);
                    int endCol = lineText != null ? lineText.length() : 0;
                    yield col >= endCol && col < endCol + d.afterText().length();
                }
                default -> false;
            };

            if (hit && d.priority() > bestPriority) {
                best = d;
                bestPriority = d.priority();
            }
        }

        return best;
    }

    /** 装饰拥有悬停色或悬停监听器时才参与悬停检测。 */
    private static boolean isHoverable(Decoration d) {
        return d.hoverColor() != null || d.hoverListener() != null;
    }

    /** 光标左移；ctrl 时按词移动，shift 时扩展选区。 */
    private boolean moveLeft(boolean shift, boolean ctrl) {
        EditorCaret c = getSkinnable().primaryCaret();
        int newCol;
        if (ctrl) {
            newCol = findWordStartBackward(c.line(), c.column());
        } else {
            newCol = Math.max(0, c.column() - 1);
        }
        boolean changed = shift ? c.selectTo(c.line(), newCol) : c.moveTo(c.line(), newCol);
        if (changed) {
            getSkinnable().fireCaretChanged(c.line(), c.column());
        }
        return changed;
    }

    /** 光标右移；ctrl 时按词移动，shift 时扩展选区。 */
    private boolean moveRight(boolean shift, boolean ctrl) {
        EditorCaret c = getSkinnable().primaryCaret();
        int maxCol = getSkinnable().document().getLineLength(c.line());
        int newCol;
        if (ctrl) {
            newCol = findWordEndForward(c.line(), c.column());
        } else {
            newCol = Math.min(maxCol, c.column() + 1);
        }
        boolean changed = shift ? c.selectTo(c.line(), newCol) : c.moveTo(c.line(), newCol);
        if (changed) {
            getSkinnable().fireCaretChanged(c.line(), c.column());
        }
        return changed;
    }

    /** 向后查找词起点（字母数字下划线为词字符）。 */
    private int findWordStartBackward(int line, int col) {
        String lineText = getSkinnable().document().getLine(line);
        if (lineText == null || lineText.isEmpty() || col <= 0) {
            return Math.max(0, col - 1);
        }
        int pos = col - 1;
        while (pos > 0 && isWordChar(lineText.charAt(pos)) == isWordChar(lineText.charAt(pos - 1))) {
            pos--;
        }
        return pos;
    }

    /** 向前查找词终点（字母数字下划线为词字符）。 */
    private int findWordEndForward(int line, int col) {
        String lineText = getSkinnable().document().getLine(line);
        int maxCol = getSkinnable().document().getLineLength(line);
        if (lineText == null || lineText.isEmpty() || col >= maxCol) {
            return Math.min(maxCol, col + 1);
        }
        int pos = col + 1;
        while (pos < maxCol && isWordChar(lineText.charAt(pos)) == isWordChar(lineText.charAt(pos - 1))) {
            pos++;
        }
        return pos;
    }

    /** 判断是否为词字符：字母、数字或下划线。 */
    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** 光标上移：目标列取期望列与目标行长的较小者。 */
    private boolean moveUp(boolean shift) {
        EditorCaret c = getSkinnable().primaryCaret();
        int newLine = Math.max(0, c.line() - 1);
        int newCol = Math.min(c.preferredColumn(), getSkinnable().document().getLineLength(newLine));
        boolean changed = shift ? c.selectTo(newLine, newCol) : c.moveTo(newLine, newCol);
        if (changed) {
            getSkinnable().fireCaretChanged(c.line(), c.column());
        }
        return changed;
    }

    /** 光标下移：目标列取期望列与目标行长的较小者。 */
    private boolean moveDown(boolean shift) {
        EditorCaret c = getSkinnable().primaryCaret();
        int newLine = Math.min(getSkinnable().document().getLineCount() - 1, c.line() + 1);
        int newCol = Math.min(c.preferredColumn(), getSkinnable().document().getLineLength(newLine));
        boolean changed = shift ? c.selectTo(newLine, newCol) : c.moveTo(newLine, newCol);
        if (changed) {
            getSkinnable().fireCaretChanged(c.line(), c.column());
        }
        return changed;
    }

    /** 移到行首（shift 时选择到行首）。 */
    private boolean moveHome(boolean shift) {
        EditorCaret c = getSkinnable().primaryCaret();
        boolean changed = shift ? c.selectTo(c.line(), 0) : c.moveTo(c.line(), 0);
        if (changed) {
            getSkinnable().fireCaretChanged(c.line(), c.column());
        }
        return changed;
    }

    /** 移到行尾（shift 时选择到行尾）。 */
    private boolean moveEnd(boolean shift) {
        EditorCaret c = getSkinnable().primaryCaret();
        int maxCol = getSkinnable().document().getLineLength(c.line());
        boolean changed = shift ? c.selectTo(c.line(), maxCol) : c.moveTo(c.line(), maxCol);
        if (changed) {
            getSkinnable().fireCaretChanged(c.line(), c.column());
        }
        return changed;
    }

    /** 退格删除：有选区删选区；列 0 时合并到上一行尾部。 */
    private boolean deleteBackward() {
        JFXEditor editor = getSkinnable();
        EditorCaret c = editor.primaryCaret();
        if (c.hasSelection()) {
            editor.deleteSelection();
            return true;
        } else if (c.column() > 0) {
            editor.document().delete(TextRange.of(c.line(), c.column() - 1, c.line(), c.column()));
            c.moveTo(c.line(), c.column() - 1);
            editor.fireCaretChanged(c.line(), c.column());
            return true;
        } else if (c.line() > 0) {
            int prevLineLen = editor.document().getLineLength(c.line() - 1);
            TextRange result = editor.document().delete(
                    TextRange.of(c.line() - 1, prevLineLen, c.line(), 0)
            );
            Position newPos = result.end();
            c.moveTo(newPos.line(), newPos.column());
            editor.fireCaretChanged(c.line(), c.column());
            return true;
        }
        return false;
    }

    /** 前向删除：有选区删选区；行尾时合并下一行。 */
    private boolean deleteForward() {
        JFXEditor editor = getSkinnable();
        EditorCaret c = editor.primaryCaret();
        if (c.hasSelection()) {
            editor.deleteSelection();
            return true;
        } else if (c.column() < editor.document().getLineLength(c.line())) {
            editor.document().delete(TextRange.of(c.line(), c.column(), c.line(), c.column() + 1));
            editor.fireCaretChanged(c.line(), c.column());
            return true;
        } else if (c.line() < editor.document().getLineCount() - 1) {
            editor.document().delete(TextRange.of(c.line(), c.column(), c.line() + 1, 0));
            editor.fireCaretChanged(c.line(), c.column());
            return true;
        }
        return false;
    }

    /** 在光标处插入换行，并按当前缩进策略追加新行起始缩进。 */
    private boolean insertNewline() {
        JFXEditor editor = getSkinnable();
        EditorCaret caret = editor.primaryCaret();
        String indent = editor.getIndentStrategy()
                .computeIndent(editor.document(), caret.line(), caret.column());
        editor.insertText("\n" + (indent != null ? indent : ""));
        return true;
    }

    /** 在光标处绘制 IME 组合文本：70% 透明度加虚线下划线。 */
    private void drawCompositionText(GraphicsContext gc, RenderContext ctx, JFXEditor editor) {
        if (compositionText == null || compositionText.isEmpty()) return;

        EditorCaret caret = editor.primaryCaret();
        double lineH = ctx.lineHeight();
        double y = ctx.getVisualLineY(caret.line());
        double inlineOffset = ctx.lineOffsetMap().getInlineOffsetAt(caret.line(), caret.column());
        double x = gutterWidth() + measureWidthUpToCol(caret.line(), caret.column())
                + inlineOffset - horizontalScrollBar.getValue();

        Color textColor = editor.textColor();
        gc.setFont(editor.font());
        gc.setFill(textColor.deriveColor(0, 1, 1, 0.7));
        gc.fillText(compositionText, x, y + lineH * 0.8);

        double compWidth = measureWidth(compositionText);
        gc.setStroke(textColor.deriveColor(0, 1, 0.8, 0.6));
        gc.setLineWidth(1);
        gc.setLineDashes(3, 2);
        double underlineY = y + lineH - 2;
        gc.strokeLine(x, underlineY, x + compWidth, underlineY);
        gc.setLineDashes(null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>逐一移除全部属性监听器、注销全部预设按键绑定与事件处理器——
     * 保证 Skin 可被安全替换而不泄漏监听。</p>
     */
    @Override
    public void dispose() {
        caretBlink.stop();
        caretPause.stop();

        JFXEditor control = getSkinnable();
        if (control != null) {
            KeyBindingRegistry registry = control.keyBindingRegistry();
            for (DefaultAction action : defaultActions) {
                registry.removeAction(action.actionId(), action.handler());
                registry.unregisterDefault(action.actionId());
            }
            defaultActions.clear();

            control.fontProperty().removeListener(fontListener);
            control.gutterVisibleProperty().removeListener(gutterVisibleListener);
            control.widthProperty().removeListener(layoutSizeListener);
            control.heightProperty().removeListener(layoutSizeListener);
            control.repaintsProperty().removeListener(refreshListener);
            control.navigateToPositionProperty().removeListener(navigateListener);
            control.backgroundColorProperty().removeListener(restyleListener);
            control.textColorProperty().removeListener(restyleListener);
            control.selectionColorProperty().removeListener(restyleListener);
            control.currentLineColorProperty().removeListener(restyleListener);
            control.caretColorProperty().removeListener(restyleListener);
            control.gutterBackgroundColorProperty().removeListener(restyleListener);
            control.gutterTextColorProperty().removeListener(restyleListener);
            control.afterTextColorProperty().removeListener(restyleListener);
            control.gutterWidthProperty().removeListener(restyleListener);
            control.lineHeightMultiplierProperty().removeListener(restyleListener);
            control.ghostTextColorProperty().removeListener(restyleListener);
            control.caretWidthProperty().removeListener(caretStyleListener);
            control.caretBlinkRateProperty().removeListener(caretStyleListener);
            control.gutterFontScaleProperty().removeListener(gutterFontScaleListener);
        }

        canvas.focusedProperty().removeListener(canvasFocusListener);

        canvas.setOnKeyPressed(null);
        canvas.setOnKeyTyped(null);
        canvas.setOnMousePressed(null);
        canvas.setOnMouseDragged(null);
        canvas.setOnScroll(null);
        canvas.setOnMouseMoved(null);
        canvas.setOnMouseExited(null);
        canvas.setOnInputMethodTextChanged(null);
        canvas.setInputMethodRequests(null);

        super.dispose();
    }
}