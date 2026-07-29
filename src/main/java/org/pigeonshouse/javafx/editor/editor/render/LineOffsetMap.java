package org.pigeonshouse.javafx.editor.editor.render;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * 文档行↔视觉行的双向映射聚合器。
 *
 * <p>汇总所有渲染层的 {@link RenderOffset}：同锚点的行插入求和合并，
 * 行内推移逐条收集。<strong>每帧重建，非线程安全，仅限 JavaFX
 * 应用线程使用。</strong></p>
 *
 * <p><strong>核心语义：</strong>锚点行自身不动，仅锚点行<em>之后</em>的
 * 文档行被下推；视觉行 = 文档行 + 之前所有锚点的插入量。
 * 垂直滚动条的单位即视觉行号。</p>
 *
 * @see RenderOffset
 * @see RenderContext
 */
public class LineOffsetMap {

    /** 锚点行 → 合并后的插入行数（按锚点有序）。 */
    private final TreeMap<Integer, Integer> lineInsertions = new TreeMap<>();

    /** 全部行内像素推移（逐条保存，不合并）。 */
    private final List<RenderOffset.InlinePush> inlinePushes = new ArrayList<>();

    /** 总插入行数的惰性缓存；-1 表示失效。 */
    private int cachedTotalExtraLines = -1;

    /**
     * 添加一条偏移：同锚点的行插入求和合并，行内推移直接收集；
     * 同时失效总行数缓存。
     *
     * @param offset 待添加的偏移
     */
    public void add(RenderOffset offset) {
        if (offset instanceof RenderOffset.LineInsertion li) {
            lineInsertions.merge(li.anchorLine(), li.extraLines(), Integer::sum);
        } else if (offset instanceof RenderOffset.InlinePush ip) {
            inlinePushes.add(ip);
        }
        cachedTotalExtraLines = -1;
    }

    /** 清空全部偏移。 */
    public void clear() {
        lineInsertions.clear();
        inlinePushes.clear();
        cachedTotalExtraLines = 0;
    }

    /**
     * 计算文档行的累计下推量：只累加锚点行<em>严格小于</em>目标行
     * 的插入量（锚点行自身不动）。
     *
     * @param documentLine 文档行号
     * @return 该行被下推的视觉行数
     */
    public int getLineOffset(int documentLine) {
        int offset = 0;
        for (var entry : lineInsertions.entrySet()) {
            if (entry.getKey() < documentLine) {
                offset += entry.getValue();
            }
        }
        return offset;
    }

    /**
     * 文档行转视觉行。
     *
     * @param documentLine 文档行号
     * @return 视觉行号（文档行 + 累计下推量）
     */
    public int getVisualLine(int documentLine) {
        return documentLine + getLineOffset(documentLine);
    }

    /**
     * 视觉行反解为文档行（逆映射）。
     *
     * <p>落在插入块内部的视觉行归属到锚点行；入参可为任意值，
     * 结果钳制为非负。</p>
     *
     * @param visualLine 视觉行号
     * @return 对应的文档行号（非负）
     */
    public int getDocumentLine(int visualLine) {
        if (lineInsertions.isEmpty()) {
            return Math.max(0, visualLine);
        }
        int cumulativeOffset = 0;
        int lastAnchor = -1;
        int bestDoc = 0;
        for (var entry : lineInsertions.entrySet()) {
            int anchorLine = entry.getKey();
            int maxDocInSegment = Math.min(anchorLine, visualLine - cumulativeOffset);
            if (maxDocInSegment > lastAnchor) {
                bestDoc = maxDocInSegment;
            }
            cumulativeOffset += entry.getValue();
            lastAnchor = anchorLine;
        }
        int afterLast = visualLine - cumulativeOffset;
        if (afterLast > lastAnchor) {
            bestDoc = afterLast;
        }
        return Math.max(0, bestDoc);
    }

    /**
     * 计算某行某列处的行内像素推移：累加同行且锚列不大于目标列
     * 的全部推移量。
     *
     * @param line   文档行号
     * @param column 目标列
     * @return 累计推移像素数
     */
    public double getInlineOffsetAt(int line, int column) {
        double offset = 0.0;
        for (var ip : inlinePushes) {
            if (ip.anchorLine() == line && ip.anchorColumn() <= column) {
                offset += ip.extraPixels();
            }
        }
        return offset;
    }

    /**
     * @return 全部插入行数之和（带惰性缓存）
     */
    public int totalExtraLines() {
        if (cachedTotalExtraLines < 0) {
            cachedTotalExtraLines = lineInsertions.values().stream()
                    .mapToInt(Integer::intValue)
                    .sum();
        }
        return cachedTotalExtraLines;
    }

    /**
     * @param documentLineCount 文档总行数
     * @return 总视觉行数（文档行数 + 总插入行数，即垂直滚动条的 max）
     */
    public int getTotalVisualLineCount(int documentLineCount) {
        return documentLineCount + totalExtraLines();
    }

    /** @return 合并后的行插入快照列表（按锚点有序） */
    public List<RenderOffset.LineInsertion> getLineInsertions() {
        return lineInsertions.entrySet().stream()
                .map(e -> new RenderOffset.LineInsertion(e.getKey(), e.getValue()))
                .toList();
    }

    /** @return 行内推移的不可变快照列表 */
    public List<RenderOffset.InlinePush> getInlinePushes() {
        return List.copyOf(inlinePushes);
    }
}