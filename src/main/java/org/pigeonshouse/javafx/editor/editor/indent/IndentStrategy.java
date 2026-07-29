package org.pigeonshouse.javafx.editor.editor.indent;

import org.pigeonshouse.javafx.editor.core.document.Document;

/**
 * 缩进策略：决定换行时新行的起始缩进，以及特定字符输入后的
 * 行首缩进调整。
 *
 * <p><strong>调用时机：</strong>{@link #computeIndent} 在 Enter
 * （insert-newline 预设动作）插入换行前调用，返回值直接拼在
 * {@code "\n"} 之后；{@link #adjustIndentOnType} 在字符插入完成后
 * 调用，返回非 {@code null} 时该行行首空白被整体替换为返回值。</p>
 *
 * <p><strong>边界约定：</strong>本接口不占用任何按键绑定（TAB 键
 * 留给使用方自由支配）；实现必须容忍越界行号并返回安全值，
 * 不得抛异常。</p>
 *
 * @see IndentStrategies
 */
public interface IndentStrategy {

    /**
     * 计算在 {@code (line, col)} 处换行后新行的起始缩进。
     *
     * @param doc  文档
     * @param line 换行发生的行号（0 起）
     * @param col  换行发生的列号（0 起）
     * @return 新行缩进字符串；无缩进时返回空串，不得为 {@code null}
     */
    String computeIndent(Document doc, int line, int col);

    /**
     * 字符插入完成后计算该行行首空白的调整结果。
     *
     * @param doc   文档（已包含刚插入的字符）
     * @param line  输入发生的行号（0 起）
     * @param typed 刚插入的字符
     * @return 新的行首空白（整体替换原行首空白），{@code null} 表示不调整
     */
    default String adjustIndentOnType(Document doc, int line, char typed) {
        return null;
    }
}
