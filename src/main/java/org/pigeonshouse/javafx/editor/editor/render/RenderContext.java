package org.pigeonshouse.javafx.editor.editor.render;

import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import org.pigeonshouse.javafx.editor.core.document.Document;
import org.pigeonshouse.javafx.editor.syntax.HighlightEngine;

/**
 * 每帧渲染的不可变快照，传递给各 {@link RenderLayer}。
 *
 * <p><strong>坐标系约定（关键）：</strong></p>
 * <ul>
 *   <li>{@code firstVisibleLine}/{@code lastVisibleLine} 为文档行号（含端点）；</li>
 *   <li>{@code scrollX} 单位为像素；{@code scrollY} 单位为<strong>视觉行数</strong>
 *       （非像素！与垂直滚动条一致）；</li>
 *   <li>{@code lineHeight}/{@code width}/{@code height}/{@code gutterWidth}
 *       均为像素（gutter 隐藏时宽度为 0）。</li>
 * </ul>
 *
 * <p>{@code helperText} 为共享测量节点，非线程安全，仅限 JavaFX
 * 应用线程使用；{@code visualLineBaseOffset} 供多层同锚叠放时叠加
 * 额外视觉行基准。</p>
 *
 * @param document             文档模型
 * @param highlightEngine      高亮引擎，可能为 {@code null}
 * @param helperText           共享文本测量节点（仅 FX 线程）
 * @param lineHeight           行高（像素）
 * @param firstVisibleLine     首个可见文档行（含）
 * @param lastVisibleLine      末个可见文档行（含）
 * @param scrollX              水平滚动量（像素）
 * @param scrollY              垂直滚动量（视觉行数，非像素）
 * @param width                画布宽（像素）
 * @param height               画布高（像素）
 * @param lineOffsetMap        本帧行偏移映射
 * @param visualLineBaseOffset 多层同锚叠放的额外视觉行基准
 * @param gutterWidth          gutter 宽（像素，隐藏时为 0）
 * @param ghostTextColor       主题幽灵文本色
 * @see RenderLayer
 */
public record RenderContext(
        Document document,
        HighlightEngine highlightEngine,
        Text helperText,
        double lineHeight,
        int firstVisibleLine,
        int lastVisibleLine,
        double scrollX,
        double scrollY,
        double width,
        double height,
        LineOffsetMap lineOffsetMap,
        int visualLineBaseOffset,
        double gutterWidth,
        Color ghostTextColor
) {

    /** 默认 gutter 宽度（像素）。 */
    public static final double DEFAULT_GUTTER_WIDTH = 50;

    /** 默认幽灵文本颜色（半透明灰）。 */
    public static final Color DEFAULT_GHOST_TEXT_COLOR = Color.rgb(110, 110, 115, 0.6);

    /**
     * 创建快照，gutter 宽与幽灵文本色取默认值，基准偏移为 0。
     *
     * @return 新快照
     */
    public static RenderContext of(
            Document document, HighlightEngine highlightEngine, Text helperText,
            double lineHeight,
            int firstVisibleLine, int lastVisibleLine,
            double scrollX, double scrollY, double width, double height,
            LineOffsetMap lineOffsetMap
    ) {
        return of(document, highlightEngine, helperText, lineHeight,
                firstVisibleLine, lastVisibleLine, scrollX, scrollY, width, height,
                lineOffsetMap, DEFAULT_GUTTER_WIDTH);
    }

    /**
     * 创建快照，幽灵文本色取默认值，基准偏移为 0。
     *
     * @return 新快照
     */
    public static RenderContext of(
            Document document, HighlightEngine highlightEngine, Text helperText,
            double lineHeight,
            int firstVisibleLine, int lastVisibleLine,
            double scrollX, double scrollY, double width, double height,
            LineOffsetMap lineOffsetMap, double gutterWidth
    ) {
        return of(document, highlightEngine, helperText, lineHeight,
                firstVisibleLine, lastVisibleLine, scrollX, scrollY, width, height,
                lineOffsetMap, gutterWidth, DEFAULT_GHOST_TEXT_COLOR);
    }

    /**
     * 创建完整参数快照，基准偏移恒为 0。
     *
     * @return 新快照
     */
    public static RenderContext of(
            Document document, HighlightEngine highlightEngine, Text helperText,
            double lineHeight,
            int firstVisibleLine, int lastVisibleLine,
            double scrollX, double scrollY, double width, double height,
            LineOffsetMap lineOffsetMap, double gutterWidth, Color ghostTextColor
    ) {
        return new RenderContext(document, highlightEngine, helperText, lineHeight,
                firstVisibleLine, lastVisibleLine, scrollX, scrollY, width, height,
                lineOffsetMap, 0, gutterWidth, ghostTextColor);
    }

    /**
     * 派生替换视觉行基准偏移的副本（多层同锚叠放时使用）。
     *
     * @param offset 新的基准偏移（视觉行数）
     * @return 仅基准偏移不同的新快照
     */
    public RenderContext withVisualLineBaseOffset(int offset) {
        return new RenderContext(document, highlightEngine, helperText, lineHeight,
                firstVisibleLine, lastVisibleLine, scrollX, scrollY, width, height,
                lineOffsetMap, offset, gutterWidth, ghostTextColor);
    }

    /**
     * 计算文档行顶部的 y 像素坐标：
     * {@code (视觉行 + 基准偏移 - scrollY) * 行高}。
     *
     * @param documentLine 文档行号
     * @return 该行顶部的 y 像素坐标（可为负，表示在视口上方）
     */
    public double getVisualLineY(int documentLine) {
        return (lineOffsetMap.getVisualLine(documentLine) + visualLineBaseOffset - scrollY) * lineHeight;
    }

    /**
     * 按视觉行区间 {@code [scrollY, scrollY + height/lineHeight]}
     * 判断文档行是否可见。
     *
     * @param documentLine 文档行号
     * @return 对应视觉行落在可见区间内时返回 {@code true}
     */
    public boolean isVisualLineVisible(int documentLine) {
        int visualLine = lineOffsetMap.getVisualLine(documentLine);
        return visualLine >= (int) scrollY && visualLine <= (int) (scrollY + height / lineHeight);
    }
}