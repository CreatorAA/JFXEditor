package org.pigeonshouse.javafx.editor.syntax;

/**
 * 语法高亮器顶层接口：逐行分词契约。
 *
 * <p><strong>状态机制：</strong>有状态实现（如 {@link RegexHighlighter}）
 * 通过整数 {@code state} 在行间传递跨行结构（块注释、文本块等）；
 * 无状态实现（如 {@link TreeSitterHighlighter}）忽略 state，
 * {@link #isStateless()} 返回 {@code true} 时 {@link HighlightEngine}
 * 跳过状态重放、单行独立计算。</p>
 *
 * @see HighlightEngine
 * @see AsyncSyntaxHighlighter
 */
public interface SyntaxHighlighter {

    /**
     * 对单行文本分词。
     *
     * @param lineContent 行内容（不含换行符）
     * @param state       进入该行前的状态（上一行的 {@code endState}）
     * @param lineIndex   行号（0 起）
     * @return 该行的 token 列表与行尾状态
     */
    LineTokens tokenizeLine(String lineContent, int state, int lineIndex);

    /**
     * @return 文档首行之前的初始状态，默认 {@code 0}
     */
    default int getInitialState() {
        return 0;
    }

    /**
     * @return 无跨行状态传递时返回 {@code true}（引擎可跳过状态重放），默认 {@code false}
     */
    default boolean isStateless() {
        return false;
    }
}
