package org.pigeonshouse.javafx.editor.syntax;

/**
 * 行内一段有类型的文本区间（不可变）。
 *
 * <p>列坐标为行内 0 起 char 索引（UTF-16 code unit，代理对占 2 个
 * 单位）；区间为左闭右开 {@code [start, end())}。空行可持有零长
 * TEXT token。</p>
 *
 * @param start  行内起始列（0 起）
 * @param length 长度（非负）
 * @param type   token 类型
 * @see LineTokens
 */
public record Token(int start, int length, TokenType type) {

    /**
     * @return 半开区间右端点（{@code start + length}，不含）
     */
    public int end() {
        return start + length;
    }

    /**
     * 判断给定列索引是否落在本 token 区间内（左闭右开）。
     *
     * @param index 行内列索引
     * @return 命中时返回 {@code true}
     */
    public boolean containsIndex(int index) {
        return index >= start && index < end();
    }

    /** @return 形如 {@code Token[start-end, type]} 的调试字符串 */
    @Override
    public String toString() {
        return "Token[" + start + "-" + end() + ", " + type.getName() + "]";
    }
}
