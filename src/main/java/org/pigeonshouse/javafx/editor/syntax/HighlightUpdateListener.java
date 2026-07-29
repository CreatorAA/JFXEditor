package org.pigeonshouse.javafx.editor.syntax;

import org.pigeonshouse.javafx.editor.core.document.DocumentChange;

/**
 * 异步高亮更新回调。
 *
 * <p>{@link TreeSitterHighlighter} 完成后台解析后经
 * {@code Platform.runLater} 在 JavaFX 应用线程回调（无 FX 环境时
 * 同步回退，便于单元测试）。</p>
 *
 * @see AsyncSyntaxHighlighter
 */
@FunctionalInterface
public interface HighlightUpdateListener {

    /**
     * 高亮结果更新完成时回调。
     *
     * @param change 需要重绘的起始范围（全量更新时 startLine 为 0）
     */
    void highlightsUpdated(DocumentChange change);
}
