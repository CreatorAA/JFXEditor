package org.pigeonshouse.javafx.editor.core.text;

/**
 * 面向文本编辑器的间隙缓冲区（Gap Buffer）字符存储。
 *
 * <p><strong>原理：</strong>在字符数组中维护一段空闲“间隙”
 * {@code [gapStart, gapEnd)}，逻辑文本为间隙左右两段的拼接；
 * 在间隙处插入/删除为 O(1)，仅当编辑点移动时才需搬移字符，
 * 非常契合编辑器“局部连续编辑”的访问模式。</p>
 *
 * <p><strong>不变量：</strong>{@code 0 <= gapStart <= gapEnd <= buffer.length}；
 * 逻辑长度 = 物理容量 − 间隙大小。所有偏移均为逻辑偏移
 * （UTF-16 code unit，0 起）。</p>
 *
 * <p>本类非线程安全，需由调用方（如 {@code MemoryDocument}）保证串行访问。</p>
 */
public final class GapBuffer {

    /** 默认初始容量（2048 字符），构造传入更小的容量会被抬升到此值。 */
    private static final int DEFAULT_CAPACITY = 1024 * 2;

    /** 底层字符数组，扩容时整体替换。 */
    private char[] buffer;
    /** 间隙起始下标（含），也是下一次插入的写入位置。 */
    private int gapStart;
    /** 间隙结束下标（不含）。 */
    private int gapEnd;

    /** 以默认容量创建空缓冲区。 */
    public GapBuffer() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * 以指定初始容量创建空缓冲区。
     *
     * @param initialCapacity 期望容量；小于默认容量时会被抬升到默认值
     */
    public GapBuffer(int initialCapacity) {
        int capacity = Math.max(initialCapacity, DEFAULT_CAPACITY);
        this.buffer = new char[capacity];
        this.gapStart = 0;
        this.gapEnd = capacity;
    }

    /**
     * 返回逻辑文本长度（不含间隙）。
     *
     * @return 当前字符数（UTF-16 code unit 计）
     */
    public int length() {
        return buffer.length - (gapEnd - gapStart);
    }

    /**
     * 判断缓冲区是否为空。
     *
     * @return 逻辑长度为 0 时返回 {@code true}
     */
    public boolean isEmpty() {
        return length() == 0;
    }

    /**
     * 返回指定逻辑下标处的字符。
     *
     * <p>下标小于 {@code gapStart} 时直接取值，否则加上间隙宽度跳过间隙。</p>
     *
     * @param index 逻辑下标（{@code [0, length())}）
     * @return 该位置的字符
     * @throws IndexOutOfBoundsException 下标越界时
     */
    public char charAt(int index) {
        checkCharAtRange(index);
        if (index < gapStart) {
            return buffer[index];
        }
        return buffer[index + (gapEnd - gapStart)];
    }

    /**
     * 返回区间 {@code [start, end)} 的文本。
     *
     * <p>内部分三种情况处理：整段在间隙左侧、整段在间隙右侧（下标平移）、
     * 横跨间隙（两次拷贝拼接）。</p>
     *
     * @param start 起始逻辑偏移（含）
     * @param end   结束逻辑偏移（不含）
     * @return 区间文本；空区间返回空串
     * @throws IndexOutOfBoundsException 任一端点越界时
     * @throws IllegalArgumentException  {@code start > end} 时
     */
    public String getText(int start, int end) {
        checkGetTextRange(start, end);
        int len = end - start;
        if (len == 0) {
            return "";
        }
        if (end <= gapStart) {
            return new String(buffer, start, len);
        }
        if (start >= gapStart) {
            int adjustStart = start + (gapEnd - gapStart);
            return new String(buffer, adjustStart, len);
        }
        int leftLen = gapStart - start;
        int rightLen = end - gapStart;
        char[] result = new char[len];
        System.arraycopy(buffer, start, result, 0, leftLen);
        System.arraycopy(buffer, gapEnd, result, leftLen, rightLen);
        return new String(result);
    }

    /**
     * 在指定逻辑偏移处插入文本。
     *
     * <p>流程：把间隙移动到插入点 → 必要时扩容 → 把字符写入间隙头部
     * 并前移 {@code gapStart}。</p>
     *
     * @param offset 插入位置（{@code [0, length()]}，等于长度时为末尾追加）
     * @param text   待插入文本；{@code null} 或空串为无操作
     * @throws IndexOutOfBoundsException 插入位置越界时
     */
    public void insert(int offset, String text) {
        checkInsertIndex(offset);
        if (text == null || text.isEmpty()) {
            return;
        }
        moveGapTo(offset);
        int textLen = text.length();
        ensureGapCapacity(textLen);
        text.getChars(0, textLen, buffer, gapStart);
        gapStart += textLen;
    }

    /**
     * 删除从指定偏移起的 {@code len} 个字符。
     *
     * <p><strong>惰性删除：</strong>仅把间隙移到删除点后将 {@code gapEnd}
     * 右移，被删字符仍留在数组中但已被间隙吞没不可见；复杂度取决于
     * 删除点到间隙的距离而非删除长度。</p>
     *
     * @param offset 删除起点逻辑偏移
     * @param len    删除长度；为 0 时无操作
     * @throws IndexOutOfBoundsException 参数为负或区间超出逻辑长度时
     */
    public void delete(int offset, int len) {
        checkDeleteRange(offset, len);
        if (len == 0) {
            return;
        }
        moveGapTo(offset);
        gapEnd += len;
    }

    /**
     * 清空全部内容：把整个数组变为间隙，O(1)，不释放已分配内存。
     */
    public void clear() {
        gapStart = 0;
        gapEnd = buffer.length;
    }

    /**
     * @return 完整逻辑文本（等价于 {@code getText(0, length())}）
     */
    @Override
    public String toString() {
        return getText(0, length());
    }

    /**
     * 把间隙移动到目标逻辑偏移处：根据目标在间隙左/右侧，
     * 单次 {@code arraycopy} 搬移中间段字符。
     */
    private void moveGapTo(int targetOffset) {
        if (targetOffset == gapStart) {
            return;
        }
        if (targetOffset < gapStart) {
            int shift = gapStart - targetOffset;
            System.arraycopy(buffer, targetOffset, buffer, gapEnd - shift, shift);
            gapStart = targetOffset;
            gapEnd -= shift;
        } else {
            int shift = targetOffset - gapStart;
            System.arraycopy(buffer, gapEnd, buffer, gapStart, shift);
            gapStart = targetOffset;
            gapEnd += shift;
        }
    }

    /**
     * 确保间隙至少能容纳 {@code needed} 个字符，不足时扩容：
     * 左段原位复制、右段贴到新数组末尾，新间隙居中扩大。
     */
    private void ensureGapCapacity(int needed) {
        int gapSize = gapEnd - gapStart;
        if (gapSize >= needed) {
            return;
        }
        int newCapacity = computeNewCapacity(needed, gapSize);
        char[] newBuffer = new char[newCapacity];
        System.arraycopy(buffer, 0, newBuffer, 0, gapStart);
        int rightLen = buffer.length - gapEnd;
        int newGapEnd = newCapacity - rightLen;
        System.arraycopy(buffer, gapEnd, newBuffer, newGapEnd, rightLen);
        gapEnd = newGapEnd;
        buffer = newBuffer;
    }

    /**
     * 计算扩容后的新容量：取“翻倍”与“现有长度+需求+64 冗余”中的较大者，
     * 上限 {@code Integer.MAX_VALUE - 8}，超限抛 {@link OutOfMemoryError}。
     */
    private int computeNewCapacity(int needed, int gapSize) {
        long maxCapacity = Integer.MAX_VALUE - 8;
        long doubled = (long) buffer.length * 2;
        long padded = (long) buffer.length + needed - gapSize + 64L;
        if (padded > maxCapacity) {
            throw new OutOfMemoryError("GapBuffer exceeds maximum capacity");
        }
        long raw = Math.max(Math.min(doubled, maxCapacity), padded);
        return (int) raw;
    }

    private void checkCharAtRange(int index) {
        int len = length();
        if (index < 0 || index >= len) {
            throw new IndexOutOfBoundsException(
                    "Index " + index + " out of bounds for length " + len);
        }
    }

    private void checkGetTextRange(int start, int end) {
        int len = length();
        if (start < 0 || start > len || end < 0 || end > len) {
            throw new IndexOutOfBoundsException(
                    "Range [" + start + ", " + end + ") out of bounds for length " + len);
        }
        if (start > end) {
            throw new IllegalArgumentException("start > end: " + start + " > " + end);
        }
    }

    private void checkInsertIndex(int index) {
        if (index < 0 || index > length()) {
            throw new IndexOutOfBoundsException(
                    "Insert index " + index + " out of bounds for length " + length());
        }
    }

    private void checkDeleteRange(int offset, int len) {
        if (offset < 0 || len < 0 || offset + len > length()) {
            throw new IndexOutOfBoundsException(
                    "Delete range [" + offset + ", " + (offset + len) + ") out of bounds for length " + length());
        }
    }
}
