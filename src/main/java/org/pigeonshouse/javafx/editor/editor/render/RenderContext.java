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
 * <p><strong>软换行（给 {@link RenderLayer} 作者）：</strong>开启软换行后一个
 * 文档行会被折成若干「段」，每段占一个视觉行。要把内容对位到某文档行时
 * <strong>不要</strong>假设它只占一行：用 {@link #segmentCount(int)} 取段数、
 * {@link #getSegmentY(int, int)} 取某段行顶、{@link #getColumnY(int, int)} 取
 * 某列所在段的行顶；要在整行<em>下方</em>放置 view zone（配合
 * {@link RenderOffset#lineInsertion}）时用 {@link #getLineBottomY(int)} 取末段底部。
 * 某列的 x 需相对其所在段起始列实测（见 {@link #segmentStartColumn(int, int)}），
 * 因为每段都从左缘重新起绘。关闭软换行时以上 API 均退化为每行一段，与旧行为一致。</p>
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
     * 计算文档行首段顶部的 y 像素坐标：
     * {@code (首段视觉行 + 基准偏移 - scrollY) * 行高}。
     *
     * @param documentLine 文档行号
     * @return 该行首段顶部的 y 像素坐标（可为负，表示在视口上方）
     */
    public double getVisualLineY(int documentLine) {
        return (lineOffsetMap.getVisualLine(documentLine) + visualLineBaseOffset - scrollY) * lineHeight;
    }

    /**
     * 计算文档行指定<strong>软换行段</strong>顶部的 y 像素坐标：
     * {@code (首段视觉行 + 段号 + 基准偏移 - scrollY) * 行高}。
     *
     * @param documentLine 文档行号
     * @param segment      段号（0 起；无软换行时恒 0，等价于 {@link #getVisualLineY(int)}）
     * @return 该段顶部的 y 像素坐标
     */
    public double getSegmentY(int documentLine, int segment) {
        return (lineOffsetMap.getVisualLine(documentLine) + segment + visualLineBaseOffset - scrollY) * lineHeight;
    }

    /**
     * 计算某列所在<strong>软换行段</strong>顶部的 y 像素坐标（content widget
     * 对位到某列时使用）。
     *
     * @param documentLine 文档行号
     * @param column       列号
     * @return 该列所在段顶部的 y 像素坐标
     */
    public double getColumnY(int documentLine, int column) {
        return getSegmentY(documentLine, lineOffsetMap.segmentIndexAt(documentLine, column));
    }

    /**
     * 计算文档行<strong>末段底部</strong>的 y 像素坐标，即紧邻其后由
     * {@link RenderOffset#lineInsertion} 腾出的空间起点（view zone 对位）。
     *
     * @param documentLine 文档行号
     * @return 该行末段底部的 y 像素坐标
     */
    public double getLineBottomY(int documentLine) {
        return getSegmentY(documentLine, segmentCount(documentLine) - 1) + lineHeight;
    }

    /** @return 文档行的软换行段数（无软换行时恒 1） */
    public int segmentCount(int documentLine) {
        return lineOffsetMap.segmentCount(documentLine);
    }

    /** @return 列所在的段号（无软换行时恒 0） */
    public int segmentIndexAt(int documentLine, int column) {
        return lineOffsetMap.segmentIndexAt(documentLine, column);
    }

    /** @return 段的起始列（无软换行时恒 0） */
    public int segmentStartColumn(int documentLine, int segment) {
        return lineOffsetMap.segmentStartColumn(documentLine, segment);
    }

    /** @return 段的结束列（末段到行尾；无软换行时为行长） */
    public int segmentEndColumn(int documentLine, int segment) {
        return lineOffsetMap.segmentEndColumn(documentLine, segment);
    }

    /**
     * 按视觉行区间 {@code [scrollY, scrollY + height/lineHeight]}
     * 判断文档行是否可见——软换行下只要该行<strong>任一段</strong>落在
     * 可见区间内即返回 {@code true}。
     *
     * @param documentLine 文档行号
     * @return 对应任一视觉行落在可见区间内时返回 {@code true}
     */
    public boolean isVisualLineVisible(int documentLine) {
        int first = lineOffsetMap.getVisualLine(documentLine);
        int last = first + lineOffsetMap.segmentCount(documentLine) - 1;
        int top = (int) scrollY;
        int bottom = (int) (scrollY + height / lineHeight);
        return last >= top && first <= bottom;
    }
}