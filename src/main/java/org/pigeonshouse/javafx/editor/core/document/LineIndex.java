package org.pigeonshouse.javafx.editor.core.document;

import java.util.ArrayList;
import java.util.List;

/**
 * 行起始偏移索引，支持插入/删除后的增量更新。
 *
 * <p><strong>数据结构：</strong>{@code lineStarts} 保存每一行首字符的
 * 全局偏移，严格递增。不变量：空文本时列表为空、行数为 0；
 * 非空文本第 0 项恒为 0，每个 LF 之后的偏移是下一行的行首。</p>
 *
 * <p><strong>增量策略：</strong>单次编辑的维护成本为
 * O(受影响行数 + 后续行首的平移)，避免整篇重扫；仅
 * {@code setText} 或文档清空时才走 {@link #rebuild(String)} 全量重建。</p>
 */
final class LineIndex {

    /** 每行首字符的全局偏移，严格递增；空文本时为空列表。 */
    private final List<Integer> lineStarts;
    /** 行数缓存，始终与 {@code lineStarts.size()} 同步。 */
    private int lineCount;

    LineIndex() {
        this.lineStarts = new ArrayList<>();
        this.lineCount = 0;
    }

    /**
     * O(n) 全量扫描重建索引（{@code setText} 或文档清空时调用）。
     *
     * @param text 新文本（LF 换行）；空串时行数归 0
     */
    void rebuild(String text) {
        lineStarts.clear();
        if (text.isEmpty()) {
            lineCount = 0;
            return;
        }
        lineStarts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lineStarts.add(i + 1);
            }
        }
        lineCount = lineStarts.size();
    }

    /** @return 当前行数；空文本为 0 */
    int getLineCount() {
        return lineCount;
    }

    /**
     * @param lineIndex 行号（0 起，调用方保证合法）
     * @return 该行首字符的全局偏移
     */
    int getLineStart(int lineIndex) {
        return lineStarts.get(lineIndex);
    }

    /**
     * 返回行尾偏移（不含换行符）。
     *
     * @param lineIndex   行号（0 起）
     * @param totalLength 文本总长，末行时作为行尾
     * @return 非末行返回下一行行首减 1（即 LF 位置）；末行返回总长
     */
    int getLineEnd(int lineIndex, int totalLength) {
        if (lineIndex + 1 < lineStarts.size()) {
            return lineStarts.get(lineIndex + 1) - 1;
        }
        return totalLength;
    }

    /**
     * @param lineIndex   行号（0 起）
     * @param totalLength 文本总长
     * @return 该行字符数（不含换行符）
     */
    int getLineLength(int lineIndex, int totalLength) {
        int start = getLineStart(lineIndex);
        int end = getLineEnd(lineIndex, totalLength);
        return end - start;
    }

    /**
     * 二分查找包含指定偏移的行，即“行首不大于 offset 的最后一行”。
     *
     * <p>偏移落在某行首时归属该行；超出范围返回最后一行；
     * 空索引返回 0。</p>
     *
     * @param offset 全局偏移
     * @return 包含该偏移的行号
     */
    int getLineForOffset(int offset) {
        if (lineStarts.isEmpty()) {
            return 0;
        }
        int low = 0;
        int high = lineStarts.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int midVal = lineStarts.get(mid);
            if (midVal <= offset) {
                if (mid + 1 >= lineStarts.size() || lineStarts.get(mid + 1) > offset) {
                    return mid;
                }
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return lineStarts.size() - 1;
    }

    /**
     * 插入后增量更新索引。
     *
     * <p>插入文本不含 LF 时仅将插入行之后的行首整体右移；含 LF 时
     * 把新产生的行首批量插入列表并平移其余后续行首；空索引
     * （空文档）时直接以插入文本重建行首。</p>
     *
     * @param insertOffset 插入点全局偏移
     * @param insertedText 插入的文本；{@code null} 或空串时无操作
     */
    void updateAfterInsert(int insertOffset, String insertedText) {
        if (insertedText == null || insertedText.isEmpty()) {
            return;
        }

        int newlineCount = countNewlines(insertedText);
        int insertLen = insertedText.length();

        if (lineStarts.isEmpty()) {
            lineStarts.add(0);
            if (newlineCount > 0) {
                collectNewLineStarts(0, insertedText, newlineCount).forEach(lineStarts::add);
            }
            lineCount = lineStarts.size();
            return;
        }

        int insertLine = getLineForOffset(insertOffset);

        if (newlineCount == 0) {
            shiftStartsAfter(insertLine, insertLen);
        } else {
            insertNewLines(insertLine, insertOffset, insertedText, newlineCount, insertLen);
        }

        lineCount = lineStarts.size();
    }

    /**
     * 删除后增量更新索引。
     *
     * <p>被删文本不含 LF 时后续行首整体左移；含 N 个 LF 时先从
     * 删除行的下一项起移除 N 个行首，再把剩余后续行首左移。</p>
     *
     * @param deleteOffset 删除起点全局偏移
     * @param deleteLen    删除长度；为 0 时无操作
     * @param deletedText  被删除的文本（用于统计换行数）
     */
    void updateAfterDelete(int deleteOffset, int deleteLen, String deletedText) {
        if (deleteLen == 0 || lineStarts.isEmpty()) {
            return;
        }

        int deleteLine = getLineForOffset(deleteOffset);
        int newlineCount = deletedText != null ? countNewlines(deletedText) : 0;

        if (newlineCount == 0) {
            shiftStartsAfter(deleteLine, -deleteLen);
        } else {
            removeDeletedLines(deleteLine, newlineCount, deleteLen);
        }

        lineCount = lineStarts.size();
    }

    /** 把 {@code afterLine} 之后的所有行首整体平移 {@code delta}。 */
    private void shiftStartsAfter(int afterLine, int delta) {
        for (int i = afterLine + 1; i < lineStarts.size(); i++) {
            lineStarts.set(i, lineStarts.get(i) + delta);
        }
    }

    /**
     * 处理含 LF 的插入：先防御性移除紧随其后且偏移不大于插入点的
     * 行首项，再批量插入新行首，最后把其余后续行首右移插入长度。
     */
    private void insertNewLines(int insertLine, int insertOffset, String insertedText,
                                int newlineCount, int insertLen) {
        int deleteIdx = insertLine + 1;
        int removeCount = 0;
        while (deleteIdx < lineStarts.size() && lineStarts.get(deleteIdx) <= insertOffset) {
            deleteIdx++;
            removeCount++;
        }
        if (removeCount > 0) {
            for (int i = 0; i < removeCount; i++) {
                lineStarts.remove(insertLine + 1);
            }
        }

        List<Integer> newStarts = collectNewLineStarts(insertOffset, insertedText, newlineCount);
        lineStarts.addAll(insertLine + 1, newStarts);

        int shiftStartIdx = insertLine + 1 + newStarts.size();
        for (int i = shiftStartIdx; i < lineStarts.size(); i++) {
            lineStarts.set(i, lineStarts.get(i) + insertLen);
        }
    }

    /**
     * 处理含 LF 的删除：从删除行的下一项起移除 {@code newlineCount}
     * 个行首，再把剩余后续行首左移删除长度。
     */
    private void removeDeletedLines(int deleteLine, int newlineCount, int deleteLen) {
        int removeIdx = deleteLine + 1;
        int remaining = newlineCount;
        while (remaining > 0 && removeIdx < lineStarts.size()) {
            lineStarts.remove(removeIdx);
            remaining--;
        }

        for (int i = deleteLine + 1; i < lineStarts.size(); i++) {
            lineStarts.set(i, lineStarts.get(i) - deleteLen);
        }
    }

    /** 收集插入文本内各 LF 产生的新行首偏移（基于插入偏移换算）。 */
    private List<Integer> collectNewLineStarts(int baseOffset, String text, int expectedCount) {
        List<Integer> starts = new ArrayList<>(expectedCount);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(baseOffset + i + 1);
            }
        }
        return starts;
    }

    /** 统计文本中 LF 的个数。 */
    private static int countNewlines(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }
}