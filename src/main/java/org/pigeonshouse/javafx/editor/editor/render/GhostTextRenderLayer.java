package org.pigeonshouse.javafx.editor.editor.render;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 内置幽灵文本渲染层（AI 补全预览的典型实现）。
 *
 * <p>在锚点行列处以半透明颜色绘制预览文本；多行文本时通过
 * {@link RenderOffset.LineInsertion} 把后续文档行下推让位。
 * 颜色优先级：显式 {@link #setColor(Color)} &gt; 上下文
 * {@code ghostTextColor} &gt; 默认灰。</p>
 *
 * <p><strong>线程：</strong>状态字段均为 {@code volatile}，
 * {@link #setText} 可从工作线程调用，但渲染仍在 JavaFX 应用线程。</p>
 *
 * <p><strong>用法示例：</strong></p>
 * <pre>{@code
 * GhostTextRenderLayer ghost = new GhostTextRenderLayer();
 * editor.renderLayers().add(ghost);              // 注册渲染层
 *
 * ghost.setText("suggestedCode();", caretLine, caretCol);
 * editor.requestRepaint();                       // 触发重绘才会显示
 *
 * ghost.clear();                                 // 清除预览
 * editor.requestRepaint();
 * }</pre>
 *
 * @see RenderLayer
 */
public class GhostTextRenderLayer implements RenderLayer {

    /** 显式覆盖色；{@code null} 时退化到上下文/默认色。 */
    private volatile Color color;

    /** 幽灵文本内容；{@code null} 即无幽灵文本。 */
    private volatile String text;
    /** 锚点文档行（首行从此行的锚点列后绘制）。 */
    private volatile int anchorLine;
    /** 锚点列（超过行长时绘制前会钳制）。 */
    private volatile int anchorColumn;

    /** 创建无幽灵文本的空层。 */
    public GhostTextRenderLayer() {
        this.text = null;
        this.anchorLine = 0;
        this.anchorColumn = 0;
    }

    /** @return 固定层名 {@code "GhostText"} */
    @Override
    public String getName() {
        return "GhostText";
    }

    /** @return 叠放序号 100（当前 Skin 按注册顺序渲染，暂未使用） */
    @Override
    public int getZOrder() {
        return 100;
    }

    /**
     * {@inheritDoc}
     *
     * <p>多行幽灵文本时返回一条行插入（锚点行，行数减 1），
     * 使后续文档行让位；单行不产生偏移。容忍 {@code null} 上下文。</p>
     */
    @Override
    public List<RenderOffset> getRenderOffsets(RenderContext context) {
        if (!hasGhostText()) {
            return List.of();
        }
        int extraLines = getExtraLinesForOffset();
        if (extraLines <= 0) {
            return List.of();
        }
        return List.of(RenderOffset.lineInsertion(anchorLine, extraLines));
    }

    /** @return 需要插入的额外视觉行数（总行数减 1，最小 0） */
    private int getExtraLinesForOffset() {
        List<String> lines = getTextLinesInternal();
        return Math.max(0, lines.size() - 1);
    }

    /**
     * {@inheritDoc}
     *
     * <p>首行内联在光标列所在的软换行段（x = gutter + 段内前缀实测宽 − scrollX），
     * 续行落到整行末段下方由行插入腾出的空间（从 gutter 左缘起）；每行做视口
     * 裁剪，基线与 Skin 一致（0.8 × 行高）。关闭软换行时退化为按文档行逐行下推。</p>
     */
    @Override
    public void render(GraphicsContext gc, RenderContext ctx) {
        if (!hasGhostText()) {
            return;
        }
    
        List<String> lines = getTextLinesInternal();
        Color explicit = color;
        Color contextColor = ctx.ghostTextColor();
        gc.setFill(explicit != null ? explicit
                : contextColor != null ? contextColor : RenderContext.DEFAULT_GHOST_TEXT_COLOR);
    
        double gutterWidth = ctx.gutterWidth();
        String anchorText = ctx.document().getLine(anchorLine);
        int col = (anchorText != null) ? Math.min(anchorColumn, anchorText.length()) : 0;
        int seg = ctx.segmentIndexAt(anchorLine, col);
        int segStartCol = ctx.segmentStartColumn(anchorLine, seg);
        double firstY = ctx.getSegmentY(anchorLine, seg);
        double bottomY = ctx.getLineBottomY(anchorLine);
    
        for (int i = 0; i < lines.size(); i++) {
            double y = (i == 0) ? firstY : bottomY + (i - 1) * ctx.lineHeight();
    
            if (y + ctx.lineHeight() < 0 || y > ctx.height()) {
                continue;
            }
    
            double x;
            if (i == 0) {
                String prefix = (anchorText != null) ? anchorText.substring(segStartCol, col) : "";
                x = gutterWidth + measureWidth(ctx, prefix) - ctx.scrollX();
            } else {
                x = gutterWidth - ctx.scrollX();
            }
    
            gc.fillText(lines.get(i), x, y + ctx.lineHeight() * 0.8);
        }
    }

    /**
     * 设置显式覆盖色。
     *
     * @param color 覆盖色；{@code null} 时退化到主题/默认色
     */
    public void setColor(Color color) {
        this.color = color;
    }

    /** @return 显式覆盖色，可能为 {@code null} */
    public Color getColor() {
        return color;
    }

    /**
     * 设置幽灵文本与锚点（可从工作线程调用，需另行触发重绘）。
     *
     * @param text   预览文本；{@code null} 或空串时清除幽灵文本
     * @param line   锚点文档行（负值钳为 0）
     * @param column 锚点列（绘制前钳到行长）
     */
    public void setText(String text, int line, int column) {
        if (text == null || text.isEmpty()) {
            this.text = null;
        } else {
            this.text = text;
        }
        this.anchorLine = Math.max(line, 0);
        this.anchorColumn = column;
    }

    /** 清除幽灵文本并把锚点归零（需另行触发重绘）。 */
    public void clear() {
        this.text = null;
        this.anchorLine = 0;
        this.anchorColumn = 0;
    }

    /** @return 当前存在非空幽灵文本时返回 {@code true} */
    public boolean hasGhostText() {
        return text != null && !text.isEmpty();
    }

    /** @return 原始幽灵文本；无时为 {@code null} */
    public String getText() {
        return text;
    }

    /**
     * @return 按行拆分后的幽灵文本拷贝；无幽灵文本时返回空列表
     */
    public List<String> getTextLines() {
        if (!hasGhostText()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(getTextLinesInternal());
    }

    /** @return 占用的额外视觉行数（行数减 1 与 0 取大） */
    public int getExtraLineCount() {
        return getExtraLinesForOffset();
    }

    /** @return 锚点文档行 */
    public int getAnchorLine() {
        return anchorLine;
    }

    /** @return 锚点列 */
    public int getAnchorColumn() {
        return anchorColumn;
    }

    /** 按 LF 拆分文本，先剥除尾部连续换行符。 */
    private List<String> getTextLinesInternal() {
        String trimmed = text.replaceAll("\\n+$", "");
        if (trimmed.isEmpty()) {
            return Collections.singletonList(text.replace("\n", ""));
        }
        List<String> result = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= trimmed.length(); i++) {
            if (i == trimmed.length() || trimmed.charAt(i) == '\n') {
                result.add(trimmed.substring(start, i));
                start = i + 1;
            }
        }
        return result;
    }

    /** 用上下文共享测量节点实测文本像素宽度。 */
    private double measureWidth(RenderContext context, String text) {
        if (text == null || text.isEmpty()) return 0;
        Text helperText = context.helperText();
        helperText.setText(text);
        return helperText.getLayoutBounds().getWidth();
    }
}