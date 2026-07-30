package org.pigeonshouse.javafx.editor.demo2;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import org.pigeonshouse.javafx.editor.editor.JFXEditor;
import org.pigeonshouse.javafx.editor.editor.render.RenderContext;
import org.pigeonshouse.javafx.editor.editor.render.RenderLayer;
import org.pigeonshouse.javafx.editor.editor.render.RenderOffset;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 行间组件叠加层，思路等同 Monaco 的 view zone + content widget：
 * lineInsertion 负责在锚点行后腾出视觉行，真实节点浮在编辑器上方的
 * 透明面板里，每帧跟着 {@link RenderContext#getVisualLineY} 对位。
 * 已渲染标记的原文在画布上折叠为胶囊，光标进入所在行时展开。
 *
 * <p>垂直方向一律以视觉行为单位（与滚动条一致），像素换算只能走
 * RenderContext。仅限 FX 线程。</p>
 */
public final class LineWidgetOverlay implements RenderLayer {

    private static final double GAP = 8;
    private static final double LEFT_INSET = 12;
    private static final double RIGHT_INSET = 24;

    private static final Color PILL_BACKGROUND = Color.web("#2c3a4f");
    private static final Color PILL_TEXT = Color.web("#8ab4f8");
    private static final Color FALLBACK_BACKGROUND = Color.web("#1e1e1e");

    private static final class Entry {
        final Node node;
        MarkupTag tag;
        int extraLines;

        Entry(Node node, MarkupTag tag) {
            this.node = node;
            this.tag = tag;
        }
    }

    private final JFXEditor editor;
    private final Pane overlayPane;
    private final StackPane root;
    /** key = 标记原文 + 出现序号；LinkedHashMap 维持文档序，同锚点叠放靠它。 */
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private double lineHeight = -1;
    private double lineHeightUsedForOffsets = -1;
    private boolean repaintQueued;
    private int caretLine = -1;

    private LineWidgetOverlay(JFXEditor editor) {
        this.editor = editor;
        this.overlayPane = new Pane();
        overlayPane.setPickOnBounds(false);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(overlayPane.widthProperty());
        clip.heightProperty().bind(overlayPane.heightProperty());
        overlayPane.setClip(clip);

        this.root = new StackPane(editor, overlayPane);
        root.setAlignment(Pos.TOP_LEFT);
    }

    public static LineWidgetOverlay install(JFXEditor editor) {
        LineWidgetOverlay overlay = new LineWidgetOverlay(editor);
        editor.renderLayers().add(overlay);
        editor.addCaretChangeListener((line, col) -> {
            if (line != overlay.caretLine) {
                overlay.caretLine = line;
                editor.requestRepaint();
            }
        });
        return overlay;
    }

    public StackPane getRoot() {
        return root;
    }

    public int widgetCount() {
        return entries.size();
    }

    /**
     * 按最新标记列表调和组件：原文没变就复用节点（锚点行照常跟走），
     * 新标记建节点，没了的移除。全程不重建未变化的组件。
     */
    public void sync(List<MarkupTag> tags, Function<MarkupTag, Node> factory) {
        Map<String, Entry> next = new LinkedHashMap<>();
        Map<String, Integer> occurrence = new HashMap<>();
        boolean changed = false;

        for (MarkupTag tag : tags) {
            String key = tag.raw() + "#" + occurrence.merge(tag.raw(), 1, Integer::sum);
            Entry entry = entries.remove(key);
            if (entry == null) {
                entry = new Entry(factory.apply(tag), tag);
                overlayPane.getChildren().add(entry.node);
                changed = true;
            } else if (entry.tag.line() != tag.line()) {
                changed = true;
            }
            entry.tag = tag;
            next.put(key, entry);
        }
        for (Entry removed : entries.values()) {
            overlayPane.getChildren().remove(removed.node);
            changed = true;
        }
        entries.clear();
        entries.putAll(next);

        if (changed) {
            editor.requestRepaint();
        }
    }

    @Override
    public String getName() {
        return "LineWidgetOverlay";
    }

    @Override
    public int getZOrder() {
        return 200; // 压在内置 GhostText(100) 之上
    }

    @Override
    public List<RenderOffset> getRenderOffsets(RenderContext context) {
        if (entries.isEmpty()) {
            return List.of();
        }
        // context 可能为 null（Skin 汇总偏移表时），行高按最近一帧的兜底
        double lh = effectiveLineHeight(context);
        lineHeightUsedForOffsets = lh;
        List<RenderOffset> offsets = new ArrayList<>(entries.size());
        for (Entry entry : entries.values()) {
            double height = entry.node.prefHeight(-1);
            entry.extraLines = Math.max(1, (int) Math.ceil((height + 2 * GAP) / lh));
            offsets.add(RenderOffset.lineInsertion(entry.tag.line(), entry.extraLines));
        }
        return offsets;
    }

    @Override
    public void beforeRender(RenderContext context) {
        lineHeight = context.lineHeight();
        // 首帧前行高只能估算，拿到真值后补一帧让占位收敛
        if (!entries.isEmpty() && Math.abs(lineHeight - lineHeightUsedForOffsets) > 0.01
                && !repaintQueued) {
            repaintQueued = true;
            Platform.runLater(() -> {
                repaintQueued = false;
                editor.requestRepaint();
            });
        }
    }

    @Override
    public void render(GraphicsContext gc, RenderContext ctx) {
        if (entries.isEmpty()) {
            return;
        }
        double lh = ctx.lineHeight();
        double x = ctx.gutterWidth() + LEFT_INSET;
        double maxWidth = Math.max(80, ctx.width() - x - RIGHT_INSET);
        boolean revealAll = !editor.getSelectedText().isEmpty();

        Map<Integer, Integer> consumedByAnchor = new HashMap<>();
        for (Entry entry : entries.values()) {
            int anchor = entry.tag.line();
            int consumed = consumedByAnchor.getOrDefault(anchor, 0);
            double y = ctx.getLineBottomY(anchor) + consumed * lh + GAP;
            consumedByAnchor.put(anchor, consumed + entry.extraLines);

            double height = entry.node.prefHeight(-1);
            double width = Math.min(entry.node.prefWidth(-1), maxWidth);

            boolean visible = y + height > 0 && y < ctx.height();
            entry.node.setVisible(visible);
            if (visible) {
                entry.node.resizeRelocate(snap(x), snap(y), snap(width), snap(height));
            }

            if (!revealAll && anchor != caretLine) {
                collapseTagText(gc, ctx, entry.tag);
            }
        }
    }

    /** 背景色盖掉标记原文（按软换行段逐段覆盖），在起始段画一枚胶囊；点进该行即展开。 */
    private void collapseTagText(GraphicsContext gc, RenderContext ctx, MarkupTag tag) {
        double lh = ctx.lineHeight();
        int line = tag.line();
        String lineText = ctx.document().getLine(line);
        if (lineText == null || tag.endCol() > lineText.length()
                || !lineText.startsWith(tag.raw(), tag.startCol())) {
            return;
        }

        int startSeg = ctx.segmentIndexAt(line, tag.startCol());
        int endSeg = ctx.segmentIndexAt(line, Math.max(tag.startCol(), tag.endCol() - 1));
        double topY = ctx.getSegmentY(line, startSeg);
        double bottomY = ctx.getSegmentY(line, endSeg) + lh;
        if (bottomY < 0 || topY > ctx.height()) {
            return;
        }

        gc.save();
        gc.beginPath();
        gc.rect(ctx.gutterWidth(), 0, ctx.width() - ctx.gutterWidth(), ctx.height());
        gc.clip(); // 别盖到 gutter

        Color background = editor.backgroundColorProperty().get();
        gc.setFill(background != null ? background : FALLBACK_BACKGROUND);
        for (int seg = startSeg; seg <= endSeg; seg++) {
            int segStartCol = ctx.segmentStartColumn(line, seg);
            int segEndCol = ctx.segmentEndColumn(line, seg);
            int coverStart = Math.max(tag.startCol(), segStartCol);
            int coverEnd = Math.min(tag.endCol(), segEndCol);
            if (coverEnd <= coverStart) {
                continue;
            }
            double x1 = ctx.gutterWidth() + measureWidth(ctx, lineText.substring(segStartCol, coverStart)) - ctx.scrollX();
            double x2 = ctx.gutterWidth() + measureWidth(ctx, lineText.substring(segStartCol, coverEnd)) - ctx.scrollX();
            gc.fillRect(x1, ctx.getSegmentY(line, seg), x2 - x1, lh);
        }

        // 胶囊画在标记起始段的起点，宽度不超过首段内的标记宽
        int startSegCol = ctx.segmentStartColumn(line, startSeg);
        int firstCoverEnd = Math.min(tag.endCol(), ctx.segmentEndColumn(line, startSeg));
        double tagX = ctx.gutterWidth() + measureWidth(ctx, lineText.substring(startSegCol, tag.startCol())) - ctx.scrollX();
        double firstSegTagWidth = measureWidth(ctx, lineText.substring(tag.startCol(), firstCoverEnd));

        Font pillFont = Font.font(editor.font().getFamily(), editor.font().getSize() * 0.85);
        String pillLabel = "\u25c6 " + tag.name();
        Text probe = ctx.helperText();
        Font savedFont = probe.getFont();
        probe.setFont(pillFont);
        probe.setText(pillLabel);
        double labelWidth = probe.getLayoutBounds().getWidth();
        probe.setFont(savedFont);

        double pillHeight = Math.max(10, lh - 6);
        double pillWidth = Math.min(firstSegTagWidth, labelWidth + 16);
        double pillY = ctx.getSegmentY(line, startSeg);
        gc.setFill(PILL_BACKGROUND);
        gc.fillRoundRect(tagX, pillY + (lh - pillHeight) / 2, pillWidth, pillHeight, pillHeight, pillHeight);
        gc.setFill(PILL_TEXT);
        gc.setFont(pillFont);
        gc.fillText(pillLabel, tagX + 8, pillY + lh * 0.72);
        gc.restore();
    }

    private static double measureWidth(RenderContext ctx, String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        Text helper = ctx.helperText();
        helper.setText(text);
        return helper.getLayoutBounds().getWidth();
    }

    private double effectiveLineHeight(RenderContext context) {
        if (context != null) {
            return context.lineHeight();
        }
        if (lineHeight > 0) {
            return lineHeight;
        }
        Text helperText = new Text();
        helperText.setText("Ag");
        return Math.max(1, helperText.getLayoutBounds().getHeight() * editor.lineHeightMultiplier());
    }

    private static double snap(double v) {
        return Math.round(v);
    }
}
