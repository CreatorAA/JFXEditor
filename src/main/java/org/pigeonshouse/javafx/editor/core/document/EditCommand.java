package org.pigeonshouse.javafx.editor.core.document;

import java.util.List;

/**
 * 一次撤销/重做执行后的结果。
 *
 * <p>供 {@link UndoManager} 构造 {@link DocumentChange} 事件与光标区间。</p>
 *
 * @param changeStartOffset 变更起点全局偏移
 * @param caretOffset       建议的光标全局偏移
 * @param oldEndOffset      旧内容结束偏移
 * @param newEndOffset      新内容结束偏移
 * @param oldText           被移除的文本
 * @param newText           新写入的文本
 */
record EditResult(int changeStartOffset, int caretOffset, int oldEndOffset, int newEndOffset, String oldText, String newText) {
}

/**
 * 可撤销编辑命令（命令模式）。
 *
 * <p>命令内部通过包私有的 {@link ReplayableDocument#replaceRange} 回放，
 * 不经过 {@code insert}/{@code delete} 公有方法，因此不会二次入栈、
 * 也不触发常规事件（事件由 {@link UndoManager} 统一补发）。</p>
 */
interface EditCommand {

    /**
     * 在文档上执行反向操作（撤销）。
     *
     * @param document 目标文档
     * @return 执行结果
     */
    EditResult undo(ReplayableDocument document);

    /**
     * 在文档上重新执行正向操作（重做）。
     *
     * @param document 目标文档
     * @return 执行结果
     */
    EditResult redo(ReplayableDocument document);
}

/**
 * 插入命令：记录插入偏移与文本。
 *
 * <p>撤销时删除该段文本（光标回到插入点），重做时重新插入
 * （光标到插入末尾）。</p>
 *
 * @param offset 插入点全局偏移
 * @param text   插入的文本
 */
record InsertEdit(int offset, String text) implements EditCommand {

    @Override
    public EditResult undo(ReplayableDocument document) {
        document.replaceRange(offset, text.length(), "");
        return new EditResult(offset, offset, offset + text.length(), offset, text, "");
    }

    @Override
    public EditResult redo(ReplayableDocument document) {
        document.replaceRange(offset, 0, text);
        return new EditResult(offset, offset + text.length(), offset, offset + text.length(), "", text);
    }
}

/**
 * 删除命令：记录删除偏移与被删文本。
 *
 * <p>撤销时重新插入被删文本，重做时再次删除。</p>
 *
 * @param offset      删除起点全局偏移
 * @param deletedText 被删除的文本
 */
record DeleteEdit(int offset, String deletedText) implements EditCommand {

    @Override
    public EditResult undo(ReplayableDocument document) {
        document.replaceRange(offset, 0, deletedText);
        return new EditResult(offset, offset + deletedText.length(), offset, offset + deletedText.length(), "", deletedText);
    }

    @Override
    public EditResult redo(ReplayableDocument document) {
        document.replaceRange(offset, deletedText.length(), "");
        return new EditResult(offset, offset, offset + deletedText.length(), offset, deletedText, "");
    }
}

/**
 * 复合命令：把多条子命令聚合为单个撤销单元（多光标编辑使用）。
 *
 * <p>撤销时按子命令入组的<em>逆序</em>逐个回放反向操作，重做时按
 * <em>正序</em>逐个重放——与子命令当初的执行顺序互逆，保证各子
 * 命令记录的全局偏移始终在当时的文档快照上成立。单次回放的
 * 聚合事件由 {@link UndoManager} 统一补发（粗粒度行级事件），
 * 返回的 {@link EditResult} 仅供定位建议光标。</p>
 *
 * @param commands 子命令列表（按原始执行顺序，至少两条）
 */
record CompoundEdit(List<EditCommand> commands) implements EditCommand {

    @Override
    public EditResult undo(ReplayableDocument document) {
        EditResult last = null;
        for (int i = commands.size() - 1; i >= 0; i--) {
            last = commands.get(i).undo(document);
        }
        return last;
    }

    @Override
    public EditResult redo(ReplayableDocument document) {
        EditResult last = null;
        for (EditCommand command : commands) {
            last = command.redo(document);
        }
        return last;
    }
}
