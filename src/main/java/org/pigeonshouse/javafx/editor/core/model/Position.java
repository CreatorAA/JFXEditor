package org.pigeonshouse.javafx.editor.core.model;

/**
 * 文档中的不可变行列坐标。
 *
 * <p>行号与列号均从 {@code 0} 开始计数，列号以 UTF-16 code unit 为单位
 * （代理对占 2 个单位）。作为值对象，{@code equals}/{@code hashCode}
 * 由 record 自动按分量值实现。</p>
 *
 * <p>比较语义遵循文档顺序：先比较行号，行号相同时再比较列号，
 * 见 {@link #compareTo(Position)}。</p>
 *
 * @param line   行号（0 起）
 * @param column 列号（0 起，不含行尾换行符）
 * @see TextRange
 */
public record Position(int line, int column) implements Comparable<Position> {

    /** 文档原点坐标 {@code (0, 0)}，常用作空文档或默认位置。 */
    public static final Position ZERO = new Position(0, 0);

    /**
     * 创建指定行列的坐标。
     *
     * @param line   行号（0 起）
     * @param column 列号（0 起）
     * @return 新的坐标实例
     */
    public static Position of(int line, int column) {
        return new Position(line, column);
    }

    /**
     * 创建指定行行首的坐标（列号为 0）。
     *
     * @param line 行号（0 起）
     * @return 位于该行行首的坐标
     */
    public static Position ofLine(int line) {
        return new Position(line, 0);
    }

    /**
     * 判断本坐标是否严格位于另一坐标之前（文档顺序）。
     *
     * @param other 比较对象
     * @return 严格在前时返回 {@code true}；相等或在后返回 {@code false}
     */
    public boolean isBefore(Position other) {
        return compareTo(other) < 0;
    }

    /**
     * 判断本坐标是否严格位于另一坐标之后（文档顺序）。
     *
     * @param other 比较对象
     * @return 严格在后时返回 {@code true}；相等或在前返回 {@code false}
     */
    public boolean isAfter(Position other) {
        return compareTo(other) > 0;
    }

    /**
     * 判断两个坐标是否处于同一行（忽略列号）。
     *
     * @param other 比较对象
     * @return 行号相同时返回 {@code true}
     */
    public boolean isSameLine(Position other) {
        return line == other.line;
    }

    /**
     * 按文档顺序比较两个坐标：先比行号，行号相同再比列号。
     *
     * @param other 比较对象
     * @return 负数、零、正数分别表示在前、相等、在后
     */
    @Override
    public int compareTo(Position other) {
        if (line != other.line) {
            return Integer.compare(line, other.line);
        }
        return Integer.compare(column, other.column);
    }

    /**
     * @return 形如 {@code (line, column)} 的可读字符串
     */
    @Override
    public String toString() {
        return "(" + line + ", " + column + ")";
    }
}
