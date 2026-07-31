package org.pigeonshouse.javafx.editor.demo3;

import javafx.application.Platform;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import org.pigeonshouse.javafx.editor.core.document.Document;
import org.pigeonshouse.javafx.editor.core.document.DocumentListener;
import org.pigeonshouse.javafx.editor.editor.JFXEditor;
import org.pigeonshouse.javafx.editor.editor.ViewportChangeListener;
import org.pigeonshouse.javafx.editor.syntax.HighlightEngine;
import org.pigeonshouse.javafx.editor.syntax.HighlightUpdateListener;
import org.pigeonshouse.javafx.editor.syntax.Token;

import java.util.List;

/**
 * 文档缩略图（minimap）组件：只依赖 JFXEditor 公开 API，不触碰任何内部实现。
 *
 * <ul>
 *   <li>数据：{@code editor.document()} 逐行取文本，横向缩放用
 *       {@code getMaxLineLength()} 缓存（严禁全文逐行实测像素）；</li>
 *   <li>配色：{@code editor.highlightEngine()} 的行级 token 缓存 + 主题样式，
 *       无高亮器时按非空白游程画统一色块；</li>
 *   <li>联动：文档变更 / 视口滚动 / 异步高亮完成三类事件合并为一次重绘；</li>
 *   <li>交互：点击或拖动缩略图，编辑器滚动到对应行并尽量居中。</li>
 * </ul>
 *
 * <p>行数超出画布高度时按比例整体压缩并抽样绘制，保证单次重绘的
 * 工作量与画布像素高度同阶，与文档行数无关。</p>
 */
public class MinimapView extends Region {

    /** 每字符最大宽度（px），行短时不放大。 */
    private static final double MAX_CHAR_W = 1.0;
    /** 每行最大高度（px），行少时不放大。 */
    private static final double MAX_LINE_H = 2.0;

    private final JFXEditor editor;
    private final Canvas canvas = new Canvas();

    private final DocumentListener docListener = change -> requestRedraw();
    private final ViewportChangeListener viewportListener = this::requestRedraw;
    private final HighlightUpdateListener highlightListener = change -> requestRedraw();
    /** 当前挂接了高亮监听的引擎；setHighlighter 会重建引擎，重绘时按需换挂。 */
    private HighlightEngine hookedEngine;

    private boolean redrawQueued;

    public MinimapView(JFXEditor editor) {
        this.editor = editor;
        getChildren().add(canvas);
        setPrefWidth(110);
        setMinWidth(60);

        editor.document().addDocumentListener(docListener);
        editor.addViewportChangeListener(viewportListener);

        setOnMousePressed(e -> scrollEditorTo(e.getY()));
        setOnMouseDragged(e -> scrollEditorTo(e.getY()));
    }

    /** 反注册全部监听器（组件不再使用时调用）。 */
    public void dispose() {
        editor.document().removeDocumentListener(docListener);
        editor.removeViewportChangeListener(viewportListener);
        if (hookedEngine != null) {
            hookedEngine.removeUpdateListener(highlightListener);
            hookedEngine = null;
        }
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        redraw();
    }

    /** 合并同一脉冲内的多次触发（如批量编辑 + 滚动）为一次重绘。 */
    private void requestRedraw() {
        if (redrawQueued) {
            return;
        }
        redrawQueued = true;
        Platform.runLater(() -> {
            redrawQueued = false;
            redraw();
        });
    }

    /** 缩略图 y 坐标 → 文档行，滚动编辑器使该行尽量居中。 */
    private void scrollEditorTo(double y) {
        Document doc = editor.document();
        int lineCount = doc.getLineCount();
        if (lineCount == 0) {
            return;
        }
        int line = Math.max(0, Math.min((int) (y / lineScale()), lineCount - 1));
        int first = editor.firstVisibleLine();
        int last = editor.lastVisibleLine();
        // 垂直滚动单位是视觉行：软换行关闭时与文档行一致，开启时为近似居中
        double half = first >= 0 && last >= first ? (last - first + 1) / 2.0 : 0;
        editor.setScrollY(line - half);
    }

    /** 每文档行占用的像素高（行数超出画布时整体压缩）。 */
    private double lineScale() {
        int lines = Math.max(1, editor.document().getLineCount());
        return Math.min(MAX_LINE_H, canvas.getHeight() / lines);
    }

    /** 高亮引擎可能被 setHighlighter 重建，重绘前校正监听挂接。 */
    private HighlightEngine currentEngine() {
        HighlightEngine engine = editor.highlightEngine();
        if (engine != hookedEngine) {
            if (hookedEngine != null) {
                hookedEngine.removeUpdateListener(highlightListener);
            }
            if (engine != null) {
                engine.addUpdateListener(highlightListener);
            }
            hookedEngine = engine;
        }
        return engine;
    }

    private void redraw() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(editor.backgroundColor());
        g.fillRect(0, 0, w, h);

        double rowH = lineScale();
        paintLines(g, w, rowH, currentEngine());
        paintViewportIndicator(g, w, rowH);
    }

    /** 逐行画色块；行高不足 1px 时抽样，工作量与画布高度同阶。 */
    private void paintLines(GraphicsContext g, double width, double rowH, HighlightEngine engine) {
        Document doc = editor.document();
        int lineCount = doc.getLineCount();
        double colW = Math.min(MAX_CHAR_W, width / Math.max(1, doc.getMaxLineLength()));
        double blockH = Math.max(1, rowH * 0.8);
        int step = Math.max(1, (int) Math.ceil(1.0 / rowH));

        for (int line = 0; line < lineCount; line += step) {
            String text = doc.getLine(line);
            if (text.isBlank()) {
                continue;
            }
            double y = line * rowH;
            if (engine != null) {
                for (Token token : engine.getTokens(line)) {
                    g.setFill(engine.getStyle(token.type()).color());
                    fillRuns(g, text, token.start(), Math.min(token.end(), text.length()), y, colW, blockH);
                }
            } else {
                g.setFill(editor.textColor());
                fillRuns(g, text, 0, text.length(), y, colW, blockH);
            }
        }
    }

    /** 在 [from, to) 内按非空白游程画色块（空白留空更接近真实排版）。 */
    private void fillRuns(GraphicsContext g, String text, int from, int to,
                          double y, double colW, double blockH) {
        int runStart = -1;
        for (int i = from; i <= to; i++) {
            boolean ws = i == to || Character.isWhitespace(text.charAt(i));
            if (!ws && runStart < 0) {
                runStart = i;
            } else if (ws && runStart >= 0) {
                g.fillRect(runStart * colW, y, (i - runStart) * colW, blockH);
                runStart = -1;
            }
        }
    }

    /** 当前视口指示框（半透明填充 + 描边）。 */
    private void paintViewportIndicator(GraphicsContext g, double width, double rowH) {
        int first = editor.firstVisibleLine();
        int last = editor.lastVisibleLine();
        if (first < 0 || last < first) {
            return;
        }
        double y = first * rowH;
        double h = Math.max(2, (last - first + 1) * rowH);
        g.setFill(Color.color(1, 1, 1, 0.08));
        g.fillRect(0, y, width, h);
        g.setStroke(Color.color(1, 1, 1, 0.3));
        g.strokeRect(0.5, y + 0.5, width - 1, h - 1);
    }

    /**
     * 生成整篇文档的静态缩略图（与屏上组件无关，可用于导出 / 预览）。
     *
     * @param width     图片宽度（px）
     * @param maxHeight 图片高度上限（px，行数过多时整体压缩到该高度内）
     * @return 缩略图快照
     */
    public WritableImage exportImage(int width, int maxHeight) {
        Document doc = editor.document();
        int lineCount = Math.max(1, doc.getLineCount());
        double rowH = Math.min(MAX_LINE_H, (double) maxHeight / lineCount);
        int height = (int) Math.ceil(lineCount * rowH);

        Canvas off = new Canvas(width, Math.max(1, height));
        GraphicsContext g = off.getGraphicsContext2D();
        g.setFill(editor.backgroundColor());
        g.fillRect(0, 0, width, off.getHeight());
        paintLines(g, width, rowH, currentEngine());

        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        return off.snapshot(params, null);
    }
}
