package org.pigeonshouse.javafx.editor.syntax;

/**
 * 异步语法高亮器：在后台完成计算后通过监听器回调通知刷新。
 *
 * <p>{@link HighlightEngine} 检测到本接口时会自动挂接桥接监听，
 * 在异步完成后失效缓存并转发事件给上层（如 Skin 触发重绘）。
 * 唯一内置实现为 {@link TreeSitterHighlighter}。</p>
 *
 * @see HighlightUpdateListener
 */
public interface AsyncSyntaxHighlighter extends SyntaxHighlighter {

    /**
     * 注册异步高亮完成监听器。
     *
     * @param listener 监听器，不可为 {@code null}
     */
    void addUpdateListener(HighlightUpdateListener listener);

    /**
     * 移除已注册的监听器；未注册时静默忽略。
     *
     * @param listener 待移除的监听器
     */
    void removeUpdateListener(HighlightUpdateListener listener);
}
