package org.pigeonshouse.javafx.editor.core.document;

import org.pigeonshouse.javafx.editor.core.model.Position;
import org.treesitter.TSInputEdit;
import org.treesitter.TSPoint;

/**
 * 一次文档变更的不可变快照事件。
 *
 * <p>由 {@link MemoryDocument} 在插入/删除/全量替换/批量结束时构造，
 * 并经 {@link DocumentListener} 广播给高亮引擎、搜索引擎、渲染层等消费方。</p>
 *
 * <p>偏移与列号均以 UTF-16 code unit 为单位（0 起）。纯插入时
 * {@code oldEndOffset == startOffset} 且 {@code oldText} 为空串；
 * 纯删除时 {@code newEndOffset == startOffset} 且 {@code newText} 为空串。</p>
 *
 * @param startLine     变更起始行号（0 起）
 * @param lineDelta     行数增量（新增为正、删除为负）
 * @param startOffset   变更起始全局偏移
 * @param oldEndOffset  旧内容的结束偏移
 * @param newEndOffset  新内容的结束偏移
 * @param oldText       被删除的文本（插入时为空串）
 * @param newText       插入的文本（删除时为空串）
 * @param startPosition 变更起始行列坐标
 * @see DocumentListener
 */
public record DocumentChange(
        int startLine,
        int lineDelta,
        int startOffset,
        int oldEndOffset,
        int newEndOffset,
        String oldText,
        String newText,
        Position startPosition
) {

    /**
     * 构造仅携带行级信息的“全量/粗粒度”变更事件。
     *
     * <p>{@code setText} 与批量结束时使用，偏移与文本字段置空。</p>
     *
     * @param startLine 变更起始行号
     * @param lineDelta 行数增量
     * @return 行级变更事件
     */
    public static DocumentChange ofLineChange(int startLine, int lineDelta) {
        return new DocumentChange(startLine, lineDelta, 0, 0, 0, "", "", Position.ZERO);
    }

    /**
     * 构造纯插入事件（{@code oldEndOffset == startOffset}，{@code oldText} 为空）。
     *
     * @param startLine     插入起始行号
     * @param lineDelta     新增行数
     * @param startOffset   插入点全局偏移
     * @param newEndOffset  插入内容的结束偏移
     * @param newText       插入的文本
     * @param startPosition 插入点行列坐标
     * @return 插入变更事件
     */
    public static DocumentChange ofInsert(
            int startLine,
            int lineDelta,
            int startOffset,
            int newEndOffset,
            String newText,
            Position startPosition
    ) {
        return new DocumentChange(startLine, lineDelta, startOffset, startOffset, newEndOffset, "", newText, startPosition);
    }

    /**
     * 构造纯删除事件（{@code newEndOffset == startOffset}，{@code newText} 为空）。
     *
     * @param startLine     删除起始行号
     * @param lineDelta     行数增量（负数或 0）
     * @param startOffset   删除起点全局偏移
     * @param oldEndOffset  被删内容的结束偏移
     * @param oldText       被删除的文本
     * @param startPosition 删除起点行列坐标
     * @return 删除变更事件
     */
    public static DocumentChange ofDelete(
            int startLine,
            int lineDelta,
            int startOffset,
            int oldEndOffset,
            String oldText,
            Position startPosition
    ) {
        return new DocumentChange(startLine, lineDelta, startOffset, oldEndOffset, startOffset, oldText, "", startPosition);
    }

    /**
     * 转换为 Tree-sitter 增量编辑对象，驱动语法树增量重解析。
     *
     * <p><strong>坐标换算关键点：</strong>文档内部按 UTF-16 code unit
     * 计数，而 Tree-sitter 以字节计（UTF-16 编码下每 code unit 占 2 字节），
     * 因此所有偏移与列号均乘以 2。</p>
     *
     * @return 对应的 {@link TSInputEdit}
     */
    public TSInputEdit toTSInputEdit() {
        TSPoint startPoint = new TSPoint(startPosition.line(), startPosition.column() * 2);
        TSPoint oldEndPoint = calculateEndPoint(oldText, startPosition);
        TSPoint newEndPoint = calculateEndPoint(newText, startPosition);

        return new TSInputEdit(
                startOffset * 2,
                oldEndOffset * 2,
                newEndOffset * 2,
                startPoint,
                oldEndPoint,
                newEndPoint
        );
    }

    /**
     * 逐字符扫描文本计算结束点：遇 LF 行号加一、列号清零；
     * 列号同样乘以 2 换算为字节单位。
     */
    private TSPoint calculateEndPoint(String text, Position startPos) {
        if (text == null || text.isEmpty()) {
            return new TSPoint(startPos.line(), startPos.column() * 2);
        }

        int line = startPos.line();
        int col = startPos.column();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                line++;
                col = 0;
            } else {
                col++;
            }
        }

        return new TSPoint(line, col * 2);
    }
}
