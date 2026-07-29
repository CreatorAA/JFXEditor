package org.pigeonshouse.javafx.editor.editor.caret;

/**
 * 编辑器光标与选区的纯数据模型（不依赖 JavaFX）。
 *
 * <p><strong>不变量：</strong>无选区时锚点（anchor）与焦点（focus）
 * 均等于光标位置；有选区时锚点为选区固定端、焦点为活动端
 * （锚点可能在焦点之后，对应从后向前拖选）。</p>
 *
 * <p><strong>边界：</strong>本类不做坐标校验，调用方必须先把行列
 * 钳制到文档合法范围内。坐标均 0 起。</p>
 */
public class EditorCaret {

    /** 光标所在行（0 起）。 */
    private int line;
    /** 光标所在列（0 起）。 */
    private int column;
    /**
     * 垂直移动时的期望列：上下移动经过短行后能回到原列。
     * 仅 {@link #moveTo} 更新它，{@link #selectTo} 不更新。
     */
    private int preferredColumn;
    /** 选区锚点行（选区的固定端）。 */
    private int anchorLine;
    /** 选区锚点列。 */
    private int anchorCol;
    /** 选区焦点行（选区的活动端，随光标移动）。 */
    private int focusLine;
    /** 选区焦点列。 */
    private int focusCol;
    /** 是否存在选区。 */
    private boolean hasSelection;

    /** 创建位于文档原点、无选区的光标。 */
    public EditorCaret() {
        this.line = 0;
        this.column = 0;
        this.preferredColumn = 0;
        this.anchorLine = 0;
        this.anchorCol = 0;
        this.focusLine = 0;
        this.focusCol = 0;
        this.hasSelection = false;
    }

    /** @return 光标行号（0 起） */
    public int line() {
        return line;
    }

    /** @return 光标列号（0 起） */
    public int column() {
        return column;
    }

    /** @return 垂直移动时的期望列（只由 {@link #moveTo} 更新） */
    public int preferredColumn() {
        return preferredColumn;
    }

    /** @return 选区锚点行号 */
    public int anchorLine() {
        return anchorLine;
    }

    /** @return 选区锚点列号 */
    public int anchorCol() {
        return anchorCol;
    }

    /** @return 存在选区时返回 {@code true} */
    public boolean hasSelection() {
        return hasSelection;
    }

    /** @return 按文档顺序归一化后的选区起始行（锚点与焦点中靠前者） */
    public int selectionStartLine() {
        return Math.min(anchorLine, focusLine);
    }

    /** @return 按文档顺序归一化后的选区结束行 */
    public int selectionEndLine() {
        return Math.max(anchorLine, focusLine);
    }

    /** @return 按文档顺序归一化后的选区起始列 */
    public int selectionStartCol() {
        if (anchorLine < focusLine) return anchorCol;
        if (anchorLine > focusLine) return focusCol;
        return Math.min(anchorCol, focusCol);
    }

    /** @return 按文档顺序归一化后的选区结束列 */
    public int selectionEndCol() {
        if (anchorLine < focusLine) return focusCol;
        if (anchorLine > focusLine) return anchorCol;
        return Math.max(anchorCol, focusCol);
    }

    /**
     * 移动光标到指定位置并折叠选区。
     *
     * <p>同步更新期望列、锚点与焦点到同一位置。</p>
     *
     * @param newLine 目标行（调用方保证已钳制）
     * @param newCol  目标列
     * @return 可见状态是否变化（位置变了或原来存在选区都算变化）
     */
    public boolean moveTo(int newLine, int newCol) {
        boolean changed = this.line != newLine
                || this.column != newCol
                || this.hasSelection;
        this.line = newLine;
        this.column = newCol;
        this.preferredColumn = newCol;
        this.anchorLine = newLine;
        this.anchorCol = newCol;
        this.focusLine = newLine;
        this.focusCol = newCol;
        this.hasSelection = false;
        return changed;
    }

    /**
     * 扩展选择到指定位置（Shift+点击/方向键语义）。
     *
     * <p>无选区时以当前光标位置为锚点开启选区，然后移动光标与
     * 焦点端；不更新期望列。</p>
     *
     * @param newLine 目标行
     * @param newCol  目标列
     * @return 可见状态是否变化
     */
    public boolean selectTo(int newLine, int newCol) {
        boolean changed = this.line != newLine
                || this.column != newCol
                || this.focusLine != newLine
                || this.focusCol != newCol
                || !this.hasSelection;
        if (!hasSelection) {
            this.anchorLine = this.line;
            this.anchorCol = this.column;
            this.hasSelection = true;
        }
        this.line = newLine;
        this.column = newCol;
        this.focusLine = newLine;
        this.focusCol = newCol;
        return changed;
    }

    /**
     * 直接设定选区的锚点与焦点。
     *
     * <p><strong>注意：</strong>不移动光标位置（{@code line}/{@code column}
     * 保持不变）——“全选时光标不动”的规范即依赖此语义。</p>
     *
     * @param startLine 锚点行
     * @param startCol  锚点列
     * @param endLine   焦点行
     * @param endCol    焦点列
     * @return 选区状态是否变化
     */
    public boolean select(int startLine, int startCol, int endLine, int endCol) {
        boolean changed = this.anchorLine != startLine
                || this.anchorCol != startCol
                || this.focusLine != endLine
                || this.focusCol != endCol
                || !this.hasSelection;
        this.anchorLine = startLine;
        this.anchorCol = startCol;
        this.focusLine = endLine;
        this.focusCol = endCol;
        this.hasSelection = true;
        return changed;
    }

    /** 折叠选区到光标处（锚点与焦点回到光标位置）。 */
    public void clearSelection() {
        this.anchorLine = this.line;
        this.anchorCol = this.column;
        this.focusLine = this.line;
        this.focusCol = this.column;
        this.hasSelection = false;
    }

    /**
     * 深拷贝光标全部状态（多光标场景使用）。
     *
     * @return 状态完全相同的新实例
     */
    public EditorCaret copy() {
        EditorCaret c = new EditorCaret();
        c.line = this.line;
        c.column = this.column;
        c.preferredColumn = this.preferredColumn;
        c.anchorLine = this.anchorLine;
        c.anchorCol = this.anchorCol;
        c.focusLine = this.focusLine;
        c.focusCol = this.focusCol;
        c.hasSelection = this.hasSelection;
        return c;
    }

    /** @return 形如 {@code EditorCaret[(line,col) sel]} 的调试字符串 */
    @Override
    public String toString() {
        return "EditorCaret[(" + line + "," + column + ")" + (hasSelection ? " sel" : "") + "]";
    }
}
