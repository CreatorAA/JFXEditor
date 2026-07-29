package org.pigeonshouse.javafx.editor.core.model;

/**
 * 由起止两个 {@link Position} 构成的不可变文本区间。
 *
 * <p>允许构造“反向”区间（{@code start} 位于 {@code end} 之后，
 * 对应从后向前拖选的选区），因此涉及偏移换算时应先调用
 * {@link #normalize()} 归一化。{@link #contains(Position)} 与
 * {@link #intersects(TextRange)} 内部已自动处理反向区间。</p>
 *
 * <p>折叠（空）区间广泛用作“光标位置”返回值，见
 * {@link #fromPosition(Position)}。</p>
 *
 * @param start 区间起点（可能在 end 之后）
 * @param end   区间终点
 * @see Position
 */
public record TextRange(Position start, Position end) implements Comparable<TextRange> {

    /**
     * 由四个行列分量创建区间。
     *
     * @param startLine 起点行号（0 起）
     * @param startCol  起点列号（0 起）
     * @param endLine   终点行号（0 起）
     * @param endCol    终点列号（0 起）
     * @return 新的区间实例（不自动归一化）
     */
    public static TextRange of(int startLine, int startCol, int endLine, int endCol) {
        return new TextRange(Position.of(startLine, startCol), Position.of(endLine, endCol));
    }

    /**
     * 创建起止点重合的折叠（空）区间，通常表示一个光标位置。
     *
     * @param pos 光标坐标
     * @return 以该坐标为两端的空区间
     */
    public static TextRange fromPosition(Position pos) {
        return new TextRange(pos, pos);
    }

    /**
     * 判断区间是否为空（两端点相等）。
     *
     * @return 起止点相等时返回 {@code true}
     */
    public boolean isEmpty() {
        return start.equals(end);
    }

    /**
     * 判断区间是否位于单一行内（两端点行号相同）。
     *
     * @return 同行时返回 {@code true}
     */
    public boolean isSingleLine() {
        return start.line() == end.line();
    }

    /**
     * 返回区间长度（字符数）。
     *
     * <p><strong>约定：</strong>仅对单行区间有意义，返回列差的绝对值；
     * 跨行区间无法仅凭坐标计算长度，固定返回哨兵值 {@code -1}。</p>
     *
     * @return 空区间返回 {@code 0}；单行区间返回列差绝对值；跨行区间返回 {@code -1}
     */
    public int length() {
        if (isEmpty()) {
            return 0;
        }
        if (isSingleLine()) {
            return Math.abs(end.column() - start.column());
        }
        return -1;
    }

    /**
     * 判断某坐标是否落在区间内（两端均为闭区间，含边界）。
     *
     * <p>反向区间会先在内部交换端点后再判定，无需调用方归一化。</p>
     *
     * @param position 待判定坐标
     * @return 坐标在 {@code [start, end]} 内时返回 {@code true}
     */
    public boolean contains(Position position) {
        Position ns = start;
        Position ne = end;
        if (ns.compareTo(ne) > 0) {
            Position temp = ns;
            ns = ne;
            ne = temp;
        }
        return position.compareTo(ns) >= 0 && position.compareTo(ne) <= 0;
    }

    /**
     * 判断两区间是否相交（端点接触也算相交）。
     *
     * <p>双方均会先在内部归一化后再按区间相交规则判定。</p>
     *
     * @param other 另一区间
     * @return 存在重叠（含端点接触）时返回 {@code true}
     */
    public boolean intersects(TextRange other) {
        Position a1 = start;
        Position a2 = end;
        if (a1.compareTo(a2) > 0) {
            Position temp = a1;
            a1 = a2;
            a2 = temp;
        }
        Position b1 = other.start;
        Position b2 = other.end;
        if (b1.compareTo(b2) > 0) {
            Position temp = b1;
            b1 = b2;
            b2 = temp;
        }
        return a1.compareTo(b2) <= 0 && b1.compareTo(a2) <= 0;
    }

    /**
     * 归一化区间，保证 {@code start} 不晚于 {@code end}。
     *
     * @return 正向区间直接返回自身；反向区间返回交换端点后的新实例
     */
    public TextRange normalize() {
        if (start.compareTo(end) <= 0) {
            return this;
        }
        return new TextRange(end, start);
    }

    /**
     * 按原始端点比较两区间：先比 {@code start}，相同再比 {@code end}。
     *
     * <p>注意比较前不会归一化，反向区间按其字面端点参与比较。</p>
     *
     * @param other 比较对象
     * @return 负数、零、正数分别表示在前、相等、在后
     */
    @Override
    public int compareTo(TextRange other) {
        int cmp = start.compareTo(other.start);
        if (cmp != 0) {
            return cmp;
        }
        return end.compareTo(other.end);
    }

    /**
     * @return 形如 {@code [(l1, c1) -> (l2, c2)]} 的可读字符串
     */
    @Override
    public String toString() {
        return "[" + start + " -> " + end + "]";
    }
}
