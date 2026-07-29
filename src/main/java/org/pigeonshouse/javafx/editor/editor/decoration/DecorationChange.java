package org.pigeonshouse.javafx.editor.editor.decoration;

import java.util.Set;

/**
 * 装饰集合变更事件（不可变）。
 *
 * <p>由 {@link DecorationModel} 在增删改清时构造并广播给
 * {@link DecorationChangeListener}。</p>
 *
 * @param type      变更类型
 * @param startLine 受影响起始行（含）
 * @param endLine   受影响结束行（含）
 */
public record DecorationChange(ChangeType type, int startLine, int endLine) {

    /** 变更类型：新增、移除、替换、清空。 */
    public enum ChangeType {ADDED, REMOVED, REPLACED, CLEARED}

    /**
     * 展开受影响行号为有序集合。
     *
     * <p><strong>警告：</strong>{@link #cleared()} 事件的区间为
     * {@code [0, Integer.MAX_VALUE]}，对 CLEARED 事件调用本方法会尝试
     * 展开天文数量的元素导致内存耗尽，勿对其调用。</p>
     *
     * @return 受影响行号的有序集合
     */
    public Set<Integer> affectedLines() {
        Set<Integer> lines = new java.util.TreeSet<>();
        for (int i = startLine; i <= endLine; i++) {
            lines.add(i);
        }
        return lines;
    }

    /**
     * 构造新增事件。
     *
     * @param startLine 受影响起始行
     * @param endLine   受影响结束行
     * @return ADDED 事件
     */
    public static DecorationChange added(int startLine, int endLine) {
        return new DecorationChange(ChangeType.ADDED, startLine, endLine);
    }

    /**
     * 构造移除事件。
     *
     * @param startLine 受影响起始行
     * @param endLine   受影响结束行
     * @return REMOVED 事件
     */
    public static DecorationChange removed(int startLine, int endLine) {
        return new DecorationChange(ChangeType.REMOVED, startLine, endLine);
    }

    /**
     * 构造替换事件。
     *
     * @param startLine 受影响起始行
     * @param endLine   受影响结束行
     * @return REPLACED 事件
     */
    public static DecorationChange replaced(int startLine, int endLine) {
        return new DecorationChange(ChangeType.REPLACED, startLine, endLine);
    }

    /**
     * 构造清空事件，区间为 {@code [0, Integer.MAX_VALUE]}。
     *
     * @return CLEARED 事件（勿对其调用 {@link #affectedLines()}）
     */
    public static DecorationChange cleared() {
        return new DecorationChange(ChangeType.CLEARED, 0, Integer.MAX_VALUE);
    }
}
