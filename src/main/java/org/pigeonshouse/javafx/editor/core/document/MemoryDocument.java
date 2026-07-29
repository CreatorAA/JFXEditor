package org.pigeonshouse.javafx.editor.core.document;

import org.pigeonshouse.javafx.editor.core.model.Position;
import org.pigeonshouse.javafx.editor.core.model.TextRange;
import org.pigeonshouse.javafx.editor.core.text.GapBuffer;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link Document} 的内存实现，core 包的中枢。
 *
 * <p><strong>职责分工：</strong>字符存储委托 {@link GapBuffer}，
 * 行定位委托 {@link LineIndex}（增量维护），历史委托 {@link UndoManager}，
 * 事件用 {@code CopyOnWriteArrayList} 分发。</p>
 *
 * <p><strong>约定：</strong>所有写入文本统一规范化为 LF 换行；
 * 空文档行数为 0；行/列/偏移均 0 起。</p>
 *
 * <p><strong>线程模型：</strong>监听器列表与文本缓存字段具备并发可见性，
 * 但编辑操作本身无锁，应在 JavaFX 应用线程上串行调用。</p>
 *
 * @see Document
 */
public class MemoryDocument implements ReplayableDocument {

    /** 底层字符存储（间隙缓冲区）。 */
    private final GapBuffer buffer;
    /** 撤销/重做双栈管理器。 */
    private final UndoManager undoManager;
    /** 变更监听器列表（写时复制，回调在编辑线程同步执行）。 */
    private final List<DocumentListener> listeners;
    /** 行首偏移索引，随编辑增量维护。 */
    private final LineIndex lineIndex;
    /** 批量编辑嵌套深度；0 表示非批量状态。 */
    private int batchLevel;
    /** 批量期间发生变更的最小行号，用作聚合事件的 startLine。 */
    private int batchMinLine;
    /** 批量开始时的行数，用于计算聚合事件的 lineDelta。 */
    private int batchOldLineCount;
    /** 全文惰性缓存，任何写操作置脏后重新拼接。 */
    private volatile String cachedText;
    /** 全文缓存脏标记。 */
    private volatile boolean textDirty;
    /** 最长行长度缓存，供横向滚动条计算。 */
    private int cachedMaxLineLength;

    /** 创建空文档（行数为 0）。 */
    public MemoryDocument() {
        this.buffer = new GapBuffer();
        this.undoManager = new UndoManager();
        this.listeners = new CopyOnWriteArrayList<>();
        this.lineIndex = new LineIndex();
        this.batchLevel = 0;
        this.batchMinLine = Integer.MAX_VALUE;
        this.textDirty = false;
        this.cachedMaxLineLength = 0;
    }

    /** {@inheritDoc} 全文采用惰性缓存，仅在脏时重新拼接。 */
    @Override
    public String getText() {
        if (textDirty || cachedText == null) {
            cachedText = buffer.toString();
            textDirty = false;
        }
        return cachedText;
    }

    /** {@inheritDoc} 区间先归一化再经 {@link #getOffset} 换算，列鉗制语义随之生效。 */
    @Override
    public String getText(TextRange range) {
        TextRange normalized = range.normalize();
        int startOffset = getOffset(normalized.start().line(), normalized.start().column());
        int endOffset = getOffset(normalized.end().line(), normalized.end().column());
        return buffer.getText(startOffset, endOffset);
    }

    @Override
    public String getLine(int lineIndex) {
        if (getLineCount() == 0) {
            return "";
        }
        checkLineIndex(lineIndex);
        int lineStart = this.lineIndex.getLineStart(lineIndex);
        int lineEnd = this.lineIndex.getLineEnd(lineIndex, buffer.length());
        return buffer.getText(lineStart, lineEnd);
    }

    @Override
    public String getLineSegment(int lineIndex, int startCol, int endCol) {
        String line = getLine(lineIndex);
        if (startCol >= line.length()) {
            return "";
        }
        int actualEnd = Math.min(endCol, line.length());
        return line.substring(startCol, actualEnd);
    }

    @Override
    public int getLineCount() {
        return lineIndex.getLineCount();
    }

    @Override
    public int getLineLength(int lineIdx) {
        checkLineIndex(lineIdx);
        return lineIndex.getLineLength(lineIdx, buffer.length());
    }

    @Override
    public int getMaxLineLength() {
        return cachedMaxLineLength;
    }

    @Override
    public int getCharCount() {
        return buffer.length();
    }

    /**
     * {@inheritDoc}
     *
     * <p>副作用：换行规范化（CRLF/CR → LF）、重建行索引、
     * 清空撤销历史、重算最长行，并广播一次全量变更事件。</p>
     */
    @Override
    public void setText(String text) {
        String normalized = normalizeLineEndings(text);
        int oldLineCount = getLineCount();
        buffer.clear();
        buffer.insert(0, normalized);
        lineIndex.rebuild(normalized);
        textDirty = true;
        undoManager.clear();
        int newLineCount = lineIndex.getLineCount();
        int lineDelta = newLineCount - oldLineCount;
        recomputeMaxLineLength();
        fireDocumentFullChange(0, lineDelta);
    }

    /**
     * {@inheritDoc}
     *
     * <p>正常路径：缓冲区插入 → 行索引增量更新 → 压入撤销命令 →
     * 增量维护最长行 → 非批量时发插入事件（批量中仅记录最小行号）。</p>
     */
    @Override
    public TextRange insert(int line, int col, String text) {
        checkPosition(line, col);
        int offset = getOffset(line, col);
        String normalized = normalizeLineEndings(text);

        if (normalized.isEmpty()) {
            if (getLineCount() == 0) {
                buffer.insert(0, "");
                lineIndex.rebuild("");
                textDirty = true;
                return TextRange.fromPosition(Position.of(0, 0));
            }
            return TextRange.fromPosition(Position.of(line, col));
        }

        int oldLineCount = getLineCount();
        buffer.insert(offset, normalized);
        lineIndex.updateAfterInsert(offset, normalized);
        textDirty = true;

        int newLineCount = getLineCount();
        int lineDelta = newLineCount - oldLineCount;

        int endOffset = offset + normalized.length();
        Position endPos = getPosition(endOffset);

        undoManager.push(new InsertEdit(offset, normalized));

        updateMaxLineLengthForInsert(line, col, normalized);

        if (batchLevel == 0) {
            fireDocumentInserted(line, lineDelta, offset, endOffset, normalized, Position.of(line, col));
        } else {
            batchMinLine = Math.min(batchMinLine, line);
        }

        return TextRange.fromPosition(endPos);
    }

    /**
     * {@inheritDoc}
     *
     * <p>正常路径：先取出被删文本 → 缓冲区删除 → 行索引增量更新
     * （缓冲区清空则行数归 0）→ 压入撤销命令 → 全量重算最长行 →
     * 非批量时发删除事件。</p>
     */
    @Override
    public TextRange delete(TextRange range) {
        TextRange normalized = range.normalize();
        int startOffset = getOffset(normalized.start().line(), normalized.start().column());
        int endOffset = getOffset(normalized.end().line(), normalized.end().column());
        int length = endOffset - startOffset;
        if (length == 0) {
            return TextRange.fromPosition(normalized.start());
        }

        String deletedText = buffer.getText(startOffset, endOffset);
        int oldLineCount = getLineCount();

        buffer.delete(startOffset, length);
        lineIndex.updateAfterDelete(startOffset, length, deletedText);
        if (buffer.length() == 0) {
            lineIndex.rebuild("");
        }
        textDirty = true;

        int newLineCount = getLineCount();
        int lineDelta = newLineCount - oldLineCount;

        undoManager.push(new DeleteEdit(startOffset, deletedText));

        updateMaxLineLengthForDelete();

        if (batchLevel == 0) {
            fireDocumentDeleted(normalized.start().line(), lineDelta, startOffset, endOffset, deletedText, normalized.start());
        } else {
            batchMinLine = Math.min(batchMinLine, normalized.start().line());
        }

        return TextRange.fromPosition(normalized.start());
    }

    @Override
    public boolean canUndo() {
        return undoManager.canUndo();
    }

    @Override
    public boolean canRedo() {
        return undoManager.canRedo();
    }

    @Override
    public TextRange undo() {
        return undoManager.undo(this);
    }

    @Override
    public TextRange redo() {
        return undoManager.redo(this);
    }

    /** {@inheritDoc} 最外层开始时记录基线行数并重置最小变更行。 */
    @Override
    public void beginBatch() {
        if (batchLevel == 0) {
            batchMinLine = Integer.MAX_VALUE;
            batchOldLineCount = lineIndex.getLineCount();
        }
        batchLevel++;
    }

    /** {@inheritDoc} 最外层结束时统一发一次行级聚合事件。 */
    @Override
    public void endBatch() {
        if (batchLevel <= 0) {
            throw new IllegalStateException("endBatch called without matching beginBatch");
        }
        batchLevel--;
        if (batchLevel == 0) {
            textDirty = true;
            int newLineCount = lineIndex.getLineCount();
            int lineDelta = newLineCount - batchOldLineCount;
            int startLine = (batchMinLine == Integer.MAX_VALUE) ? 0 : batchMinLine;
            fireDocumentFullChange(startLine, lineDelta);
        }
    }

    /** {@inheritDoc} 委托 {@link UndoManager} 收集后续命令。 */
    @Override
    public void beginCompoundEdit() {
        undoManager.beginCompound();
    }

    /** {@inheritDoc} 委托 {@link UndoManager} 聚合入栈。 */
    @Override
    public void endCompoundEdit() {
        undoManager.endCompound();
    }

    @Override
    public void addDocumentListener(DocumentListener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    @Override
    public void removeDocumentListener(DocumentListener listener) {
        listeners.remove(listener);
    }

    /**
     * 供 {@link EditCommand} 回放的包私有替换操作：直接操作缓冲区与
     * 行索引并全量重算最长行，<em>不入撤销栈、不触发事件</em>
     * （事件由 {@link UndoManager} 统一补发）。
     */
    @Override
    public void replaceRange(int offset, int deleteLen, String insertText) {
        if (deleteLen > 0) {
            String deleted = buffer.getText(offset, offset + deleteLen);
            buffer.delete(offset, deleteLen);
            lineIndex.updateAfterDelete(offset, deleteLen, deleted);
        }
        if (insertText != null && !insertText.isEmpty()) {
            buffer.insert(offset, insertText);
            lineIndex.updateAfterInsert(offset, insertText);
        }
        if (buffer.length() == 0) {
            lineIndex.rebuild("");
        }
        textDirty = true;
        recomputeMaxLineLength();
    }

    /** 撤销/重做后由 {@link UndoManager} 调用，广播携带完整新旧文本的变更事件。 */
    @Override
    public void fireDocumentChanged(int startOffset, int oldEndOffset, int newEndOffset, String oldText, String newText, Position startPosition, int lineDelta) {
        if (listeners.isEmpty()) {
            return;
        }
        DocumentChange change = new DocumentChange(startPosition.line(), lineDelta, startOffset, oldEndOffset, newEndOffset, oldText, newText, startPosition);
        for (DocumentListener listener : listeners) {
            listener.documentChanged(change);
        }
    }

    /** 广播纯插入事件（非批量插入时调用）。 */
    void fireDocumentInserted(int startLine, int lineDelta, int startOffset, int newEndOffset, String newText, Position startPosition) {
        if (listeners.isEmpty()) {
            return;
        }
        DocumentChange change = DocumentChange.ofInsert(startLine, lineDelta, startOffset, newEndOffset, newText, startPosition);
        for (DocumentListener listener : listeners) {
            listener.documentChanged(change);
        }
    }

    /** 广播纯删除事件（非批量删除时调用）。 */
    void fireDocumentDeleted(int startLine, int lineDelta, int startOffset, int oldEndOffset, String oldText, Position startPosition) {
        if (listeners.isEmpty()) {
            return;
        }
        DocumentChange change = DocumentChange.ofDelete(startLine, lineDelta, startOffset, oldEndOffset, oldText, startPosition);
        for (DocumentListener listener : listeners) {
            listener.documentChanged(change);
        }
    }

    /** 广播行级全量变更事件（{@code setText} 与批量结束时调用）。 */
    void fireDocumentFullChange(int startLine, int lineDelta) {
        if (listeners.isEmpty()) {
            return;
        }
        DocumentChange change = DocumentChange.ofLineChange(startLine, lineDelta);
        for (DocumentListener listener : listeners) {
            listener.documentChanged(change);
        }
    }

    /** {@inheritDoc} 空文档仅 {@code (0,0)} 合法；列超长时静默鉗制到行长。 */
    @Override
    public int getOffset(int line, int col) {
        if (getLineCount() == 0 && line == 0 && col == 0) {
            return 0;
        }
        checkLineIndex(line);
        int lineStart = lineIndex.getLineStart(line);
        int lineLength = getLineLength(line);
        int actualCol = Math.min(col, lineLength);
        return lineStart + actualCol;
    }

    /** {@inheritDoc} 偏移不大于 0 或空文档返回原点；超长时鉗制到末尾后二分定位。 */
    @Override
    public Position getPosition(int offset) {
        if (getLineCount() == 0 || offset <= 0) {
            return Position.ZERO;
        }
        int clamped = Math.min(offset, buffer.length());
        int line = lineIndex.getLineForOffset(clamped);
        int lineStart = lineIndex.getLineStart(line);
        return Position.of(line, clamped - lineStart);
    }

    /** 校验行号合法性，越界抛带合法区间提示的 {@link IndexOutOfBoundsException}。 */
    private void checkLineIndex(int lineIdx) {
        int count = getLineCount();
        if (lineIdx < 0 || (count == 0 ? lineIdx > 0 : lineIdx >= count)) {
            throw new IndexOutOfBoundsException(
                    "Line index " + lineIdx + " out of bounds [0, " + (count - 1) + "]");
        }
    }

    /** 严格校验插入位置：空文档只允许 {@code (0,0)}，否则列必须在 {@code [0, 行长]} 内。 */
    private void checkPosition(int line, int col) {
        if (getLineCount() == 0) {
            if (line != 0 || col != 0) {
                throw new IndexOutOfBoundsException(
                        "Position (" + line + ", " + col + ") out of bounds for empty document");
            }
            return;
        }
        checkLineIndex(line);
        int maxCol = getLineLength(line);
        if (col < 0 || col > maxCol) {
            throw new IndexOutOfBoundsException(
                    "Column " + col + " out of bounds [0, " + maxCol + "] at line " + line);
        }
    }

    /** 把 CRLF 与孤立 CR 统一规范化为 LF；{@code null} 视为空串。 */
    static String normalizeLineEndings(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** O(n) 全量重算最长行缓存。 */
    private void recomputeMaxLineLength() {
        int max = 0;
        for (int i = 0; i < getLineCount(); i++) {
            int len = getLineLength(i);
            if (len > max) {
                max = len;
            }
        }
        cachedMaxLineLength = max;
    }

    /**
     * 插入后增量维护最长行缓存：只检查插入行到“插入行 + 换行数”
     * 这几行，避免全文扫描。
     */
    private void updateMaxLineLengthForInsert(int line, int col, String insertedText) {
        int newlineCount = 0;
        for (int i = 0; i < insertedText.length(); i++) {
            if (insertedText.charAt(i) == '\n') {
                newlineCount++;
            }
        }
        int lastAffected = Math.min(line + newlineCount, getLineCount() - 1);
        for (int i = line; i <= lastAffected; i++) {
            int len = getLineLength(i);
            if (len > cachedMaxLineLength) {
                cachedMaxLineLength = len;
            }
        }
    }

    /** 删除后无法增量判断最长行是否被移除，只能全量重算。 */
    private void updateMaxLineLengthForDelete() {
        recomputeMaxLineLength();
    }
}
