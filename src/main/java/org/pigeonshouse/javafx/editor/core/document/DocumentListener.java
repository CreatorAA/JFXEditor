package org.pigeonshouse.javafx.editor.core.document;

/**
 * 文档变更监听器。
 *
 * <p>回调在编辑线程（通常为 JavaFX 应用线程）上同步执行，
 * 实现方不应在回调中执行耗时操作。典型消费方包括语法高亮引擎
 * （增量重算）、搜索引擎（结果失效）与编辑器皮肤（重绘）。</p>
 *
 * @see Document#addDocumentListener(DocumentListener)
 */
@FunctionalInterface
public interface DocumentListener {

    /**
     * 文档发生变更时回调。
     *
     * @param change 变更快照，不可为 {@code null}
     */
    void documentChanged(DocumentChange change);
}
