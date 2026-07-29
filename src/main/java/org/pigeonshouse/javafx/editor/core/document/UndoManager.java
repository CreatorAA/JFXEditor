package org.pigeonshouse.javafx.editor.core.document;

import org.pigeonshouse.javafx.editor.core.model.Position;
import org.pigeonshouse.javafx.editor.core.model.TextRange;

import java.util.*;

/**
 * 双栈撤销/重做管理器。
 *
 * <p>新命令入撤销栈时会清空重做栈（任何新编辑使重做历史失效）；
 * 容量上限 {@value #MAX_UNDO_SIZE}，超限时从栈底淘汰最老命令。</p>
 *
 * <p><strong>无合并策略：</strong>连续单字符输入各自成为独立撤销单元，
 * 不做 IDE 常见的连续输入聚合。</p>
 *
 * <p><strong>复合收集：</strong>{@link #beginCompound()}/{@link #endCompound()}
 * 期间压入的命令不直接入栈而是进入缓冲区，最外层结束时聚合为
 * 单个 {@link CompoundEdit} 入栈（多光标编辑的批量撤销依赖此机制）；
 * 空复合不产生撤销条目，单命令复合退化为普通命令。</p>
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
    /** 复合收集嵌套深度；0 表示未在复合中。 */
    private int compoundLevel;
    /** 复合期间收集的子命令（按执行顺序）。 */
    private final List<EditCommand> compoundBuffer;

    UndoManager() {
        this.undoStack = new ArrayDeque<>();
        this.redoStack = new ArrayDeque<>();
        this.compoundLevel = 0;
        this.compoundBuffer = new ArrayList<>();
    }

    /**
     * 压入新编辑命令：清空重做栈；复合收集中则先进缓冲区，
     * 否则直接入栈并在超限时淘汰最老命令。
     *
     * @param command 新编辑命令
     */
    void push(EditCommand command) {
        redoStack.clear();
        if (compoundLevel > 0) {
            compoundBuffer.add(command);
            return;
        }
        pushToStack(command);
    }

    /** 直接入撤销栈并执行容量淘汰。 */
    private void pushToStack(EditCommand command) {
        undoStack.push(command);
        while (undoStack.size() > MAX_UNDO_SIZE) {
            undoStack.removeLast();
        }
    }

    /** 开启一层复合收集（支持嵌套，嵌套层合并到最外层）。 */
    void beginCompound() {
        compoundLevel++;
    }

    /**
     * 结束一层复合收集；最外层结束时把缓冲区聚合入栈：
     * 空缓冲无操作，单命令退化为普通命令，多命令聚合为
     * {@link CompoundEdit}。
     *
     * @throws IllegalStateException 无匹配的 {@link #beginCompound()} 时
     */
    void endCompound() {
        if (compoundLevel <= 0) {
            throw new IllegalStateException("endCompoundEdit called without matching beginCompoundEdit");
        }
        compoundLevel--;
        if (compoundLevel > 0 || compoundBuffer.isEmpty()) {
            return;
        }
        EditCommand unit = compoundBuffer.size() == 1
                ? compoundBuffer.get(0)
                : new CompoundEdit(List.copyOf(compoundBuffer));
        compoundBuffer.clear();
        pushToStack(unit);
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
        return execute(command, command::undo, document);
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
        return execute(command, command::redo, document);
    }

    /**
     * 回放命令并补发事件：记录执行前行数 → 调用命令 → 用行数差算
     * {@code lineDelta} → 广播变更事件 → 把建议光标偏移换算为折叠
     * 区间返回。普通命令携带完整新旧文本；复合命令涉及多处离散
     * 变更，改发一次粗粒度行级聚合事件（与批量结束事件同模式）。
     */
    private TextRange execute(EditCommand command,
                              java.util.function.Function<ReplayableDocument, EditResult> action,
                              ReplayableDocument document) {
        int oldLineCount = document.getLineCount();
        EditResult result = action.apply(document);
        int lineDelta = document.getLineCount() - oldLineCount;
        if (command instanceof CompoundEdit) {
            document.fireDocumentChanged(0, 0, 0, "", "", Position.ZERO, lineDelta);
        } else {
            Position startPos = document.getPosition(result.changeStartOffset());
            document.fireDocumentChanged(result.changeStartOffset(), result.oldEndOffset(), result.newEndOffset(),
                    result.oldText(), result.newText(), startPos, lineDelta);
        }
        return TextRange.fromPosition(document.getPosition(result.caretOffset()));
    }

    /** 清空两栈与复合缓冲（{@code setText} 整体替换时调用）。 */
    void clear() {
        undoStack.clear();
        redoStack.clear();
        compoundBuffer.clear();
    }
}
