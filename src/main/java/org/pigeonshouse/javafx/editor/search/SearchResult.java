package org.pigeonshouse.javafx.editor.search;

import org.pigeonshouse.javafx.editor.core.model.TextRange;

/**
 * 单次搜索匹配结果（不可变，单行匹配）。
 *
 * @param line        匹配所在行（0 起）
 * @param startCol    起始列（含，0 起）
 * @param endCol      结束列（不含）
 * @param matchedText 匹配到的文本
 * @see SearchEngine
 */
public record SearchResult(int line, int startCol, int endCol, String matchedText) {

    /**
     * @return 对应的同行文本区间（可直接用于选中/删除）
     */
    public TextRange toTextRange() {
        return TextRange.of(line, startCol, line, endCol);
    }

    /** @return 形如 {@code SearchResult{line:start-end, text='...'}} 的调试字符串 */
    @Override
    public String toString() {
        return "SearchResult{" + line + ":" + startCol + "-" + endCol +
                ", text='" + matchedText + "'" + '}';
    }
}
