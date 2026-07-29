package org.pigeonshouse.javafx.editor.core.document;

import org.pigeonshouse.javafx.editor.core.model.Position;
import org.pigeonshouse.javafx.editor.core.model.TextRange;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.NoSuchElementException;

/**
 * 双栈撤销/重做管理器。
 *
 * <p>新命令入撤销栈时会清空重做栈（任何新编辑使重做历史失效）；
 * 容量上限 {@value #MAX_UNDO_SIZE}，超限时从栈底淘汰最老命令。</p>
 *
 * <p><strong>无合并策略：</strong>连续单字符输入各自成为独立撤销单元，
 * 不做 IDE 常见的连续输入聚合。</p>
 *
 * @see EditCommand
 */
final class UndoManager {

    /** 撤销栈容量上限，超出后从栈底淘汰最老命令。 */
    private static final int MAX_UNDO_SIZE = 500;

    /** 撤销栈，栈顶为最近一次编辑。 */
    private final Deque<EditCommand> undoStack;
    /** 重做栈，仅在撤销后非空，新编辑入栈时被清空。 */
    private final Deque<EditCommand> redoStack;

    UndoManager() {
        this.undoStack = new ArrayDeque<>();
        this.redoStack = new ArrayDeque<>();
    }

    /**
     * 压入新编辑命令：清空重做栈，并在超限时淘汰最老命令。
     *
     * @param command 新编辑命令
     */
    void push(EditCommand command) {
        undoStack.push(command);
        redoStack.clear();
        while (undoStack.size() > MAX_UNDO_SIZE) {
            undoStack.removeLast();
        }
    }

    /** @return 撤销栈非空时返回 {@code true} */
    boolean canUndo() {
        return !undoStack.isEmpty();
    }

    /** @return 重做栈非空时返回 {@code true} */
    boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /**
     * 撤销最近一次编辑：命令从撤销栈迁入重做栈并回放其反向操作。
     *
     * @param document 目标文档
     * @return 建议的光标位置（折叠区间）
     * @throws NoSuchElementException 撤销栈为空时
     */
    TextRange undo(ReplayableDocument document) {
        if (!canUndo()) {
            throw new NoSuchElementException("Nothing to undo");
        }
        EditCommand command = undoStack.pop();
        redoStack.push(command);
        return execute(command::undo, document);
    }

    /**
     * 重做最近一次被撤销的编辑：命令从重做栈迁回撤销栈并回放正向操作。
     *
     * @param document 目标文档
     * @return 建议的光标位置（折叠区间）
     * @throws NoSuchElementException 重做栈为空时
     */
    TextRange redo(ReplayableDocument document) {
        if (!canRedo()) {
            throw new NoSuchElementException("Nothing to redo");
        }
        EditCommand command = redoStack.pop();
        undoStack.push(command);
        return execute(command::redo, document);
    }

    /**
     * 回放命令并补发事件：记录执行前行数 → 调用命令 → 用行数差算
     * {@code lineDelta} → 广播携带新旧文本的完整变更事件 →
     * 把建议光标偏移换算为折叠区间返回。
     */
    private TextRange execute(java.util.function.Function<ReplayableDocument, EditResult> action, ReplayableDocument document) {
        int oldLineCount = document.getLineCount();
        EditResult result = action.apply(document);
        int lineDelta = document.getLineCount() - oldLineCount;
        Position startPos = document.getPosition(result.changeStartOffset());
        document.fireDocumentChanged(result.changeStartOffset(), result.oldEndOffset(), result.newEndOffset(),
                result.oldText(), result.newText(), startPos, lineDelta);
        return TextRange.fromPosition(document.getPosition(result.caretOffset()));
    }

    /** 清空两栈（{@code setText} 整体替换时调用）。 */
    void clear() {
        undoStack.clear();
        redoStack.clear();
    }
}
