package org.pigeonshouse.javafx.editor.syntax;

import java.util.List;

/**
 * 一行的分词结果：token 列表加行尾状态。
 *
 * <p>{@code endState} 作为下一行 {@code tokenizeLine} 的入参 state，
 * 承载跨行结构（如块注释）；无状态高亮器约定恒为 {@code 0}。</p>
 *
 * @param tokens   该行 token 列表（按起始列有序）
 * @param endState 该行结束时的状态
 * @see SyntaxHighlighter#tokenizeLine
 */
public record LineTokens(List<Token> tokens, int endState) {

    /**
     * 静态工厂。
     *
     * @param tokens   token 列表
     * @param endState 行尾状态
     * @return 新实例
     */
    public static LineTokens of(List<Token> tokens, int endState) {
        return new LineTokens(tokens, endState);
    }
}
