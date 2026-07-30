package org.pigeonshouse.javafx.editor.editor.render;

import org.pigeonshouse.javafx.editor.editor.wrap.WrapModel;

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
 * <p><strong>核心语义：</strong>视觉行由两层叠加而成——先由 {@link WrapModel}
 * 把每个文档行按软换行展开为若干「段」（每段占一个视觉行），再由渲染层的
 * 行插入在锚点行<em>末段之后</em>垫入空白视觉行（锚点行自身不动，仅其后的
 * 文档行整体下推）。视觉行 = 该行首段的软换行前缀 + 之前所有锚点的插入量。
 * 未设置 {@link WrapModel} 或其未启用时退化为「每行一段」，语义与纯行插入一致。
 * 垂直滚动条的单位即视觉行号。</p>
 *
 * @see RenderOffset
 * @see RenderContext
 * @see WrapModel
 */
public class LineOffsetMap {

    /** 视觉行反解结果：文档行号 + 段号（无软换行时段号恒为 0）。 */
    public record VisualPosition(int docLine, int segmentIndex) {
    }

    /** 锚点行 → 合并后的插入行数（按锚点有序）。 */
    private final TreeMap<Integer, Integer> lineInsertions = new TreeMap<>();

    /** 全部行内像素推移（逐条保存，不合并）。 */
    private final List<RenderOffset.InlinePush> inlinePushes = new ArrayList<>();

    /** 总插入行数的惰性缓存；-1 表示失效。 */
    private int cachedTotalExtraLines = -1;

    /** 软换行布局模型；{@code null} 或其未启用时按每行一段处理。 */
    private WrapModel wrapModel;

    /** 设置软换行布局模型（{@code null} 关闭软换行展开）。 */
    public void setWrapModel(WrapModel wrapModel) {
        this.wrapModel = wrapModel;
    }

    /** @return 软换行是否生效（模型存在且已启用） */
    private boolean wrapActive() {
        return wrapModel != null && wrapModel.isEnabled();
    }

    /** @return 文档行的段数（无软换行时恒为 1） */
    public int segmentCount(int documentLine) {
        return wrapActive() ? wrapModel.segmentCount(documentLine) : 1;
    }

    /** @return 列所在的段号（无软换行时恒 0；边界列归属较后的段） */
    public int segmentIndexAt(int documentLine, int column) {
        return wrapModel != null ? wrapModel.segmentOf(documentLine, column) : 0;
    }

    /** @return 段的起始列（无软换行时恒 0） */
    public int segmentStartColumn(int documentLine, int segment) {
        return wrapModel != null ? wrapModel.segmentStart(documentLine, segment) : 0;
    }

    /** @return 段的结束列（末段到行尾；无软换行时为行长，模型缺失时为 {@link Integer#MAX_VALUE}） */
    public int segmentEndColumn(int documentLine, int segment) {
        return wrapModel != null ? wrapModel.segmentEndCol(documentLine, segment) : Integer.MAX_VALUE;
    }

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
     * 文档行转首段视觉行。
     *
     * @param documentLine 文档行号
     * @return 首段视觉行号（软换行前缀 + 累计下推量）
     */
    public int getVisualLine(int documentLine) {
        int base = wrapActive() ? wrapModel.firstVisualLine(documentLine) : documentLine;
        return base + getLineOffset(documentLine);
    }

    /**
     * 文档行列转具体段的视觉行（首段视觉行 + 该列所在段号）。
     *
     * @param documentLine 文档行号
     * @param column       列号
     * @return 该列所在段的视觉行号
     */
    public int getVisualLine(int documentLine, int column) {
        int seg = wrapActive() ? wrapModel.segmentOf(documentLine, column) : 0;
        return getVisualLine(documentLine) + seg;
    }

    /**
     * 视觉行反解为文档行（逆映射，取所在段的文档行）。
     *
     * @param visualLine 视觉行号
     * @return 对应的文档行号（非负）
     */
    public int getDocumentLine(int visualLine) {
        return getVisualPosition(visualLine).docLine();
    }

    /**
     * 视觉行反解为 {@code (文档行, 段号)}。
     *
     * <p>无软换行时段号恒为 0，行映射沿用旧版纯行插入逆映射（落在插入块
     * 内部的视觉行归属锚点行）；启用软换行且无行插入时直接由
     * {@link WrapModel#locate} 反解；两者兼有时对文档行二分、再定段。
     * 入参可为任意值，结果钳制为非负。</p>
     *
     * @param visualLine 视觉行号
     * @return 文档行与段号
     */
    public VisualPosition getVisualPosition(int visualLine) {
        if (!wrapActive()) {
            return new VisualPosition(getDocumentLineNoWrap(visualLine), 0);
        }
        if (lineInsertions.isEmpty()) {
            int[] loc = wrapModel.locate(visualLine);
            return new VisualPosition(loc[0], loc[1]);
        }
        int lineCount = wrapModel.lineCount();
        if (lineCount <= 0) {
            return new VisualPosition(0, 0);
        }
        int lo = 0;
        int hi = lineCount - 1;
        int best = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (getVisualLine(mid) <= visualLine) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        int local = visualLine - getVisualLine(best);
        int segCount = wrapModel.segmentCount(best);
        int seg = Math.max(0, Math.min(local, segCount - 1));
        return new VisualPosition(best, seg);
    }

    /**
     * 无软换行的视觉行→文档行逆映射（仅行插入）。
     *
     * <p>落在插入块内部的视觉行归属到锚点行；入参可为任意值，
     * 结果钳制为非负。</p>
     */
    private int getDocumentLineNoWrap(int visualLine) {
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
     * @return 总视觉行数（软换行展开后的视觉行数 + 总插入行数，即垂直滚动条的 max）
     */
    public int getTotalVisualLineCount(int documentLineCount) {
        int base = wrapActive() ? wrapModel.totalVisualLines() : documentLineCount;
        return base + totalExtraLines();
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
