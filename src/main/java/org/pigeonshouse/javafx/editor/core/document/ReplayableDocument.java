package org.pigeonshouse.javafx.editor.core.document;

import org.pigeonshouse.javafx.editor.core.model.Position;

/**
 * 支持撤销/重做回放的文档内部契约（包私有）。
 *
 * <p>{@link EditCommand} 经 {@link #replaceRange} 回放编辑而不走
 * {@code insert}/{@code delete} 公有路径（不二次入栈、不触发常规事件），
 * 事件由 {@link UndoManager} 经 {@link #fireDocumentChanged} 统一补发。
 * 文档实现（{@link MemoryDocument}、{@link PagedDocument}）实现本接口
 * 即可共享同一套 UndoManager/EditCommand。</p>
 */
interface ReplayableDocument extends Document {

    /**
     * 回放专用的原子替换：删除 {@code [offset, offset+deleteLen)} 后在
     * {@code offset} 处插入 {@code insertText}。
     *
     * <p>实现必须：不入撤销栈、不触发事件、同步维护行索引与
     * 最长行缓存。</p>
     *
     * @param offset     替换起点全局偏移
     * @param deleteLen  删除长度（0 表示纯插入）
     * @param insertText 插入文本（{@code null} 或空串表示纯删除）
     */
    void replaceRange(int offset, int deleteLen, String insertText);

    /**
     * 撤销/重做后由 {@link UndoManager} 调用，广播携带完整新旧文本的
     * 变更事件。
     *
     * @param startOffset   变更起点全局偏移
     * @param oldEndOffset  旧内容结束偏移
     * @param newEndOffset  新内容结束偏移
     * @param oldText       被移除的文本
     * @param newText       新写入的文本
     * @param startPosition 变更起点行列坐标
     * @param lineDelta     行数变化量
     */
    void fireDocumentChanged(int startOffset, int oldEndOffset, int newEndOffset,
                             String oldText, String newText, Position startPosition, int lineDelta);
}
