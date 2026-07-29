package org.pigeonshouse.javafx.editor.syntax;

/**
 * JSON 语言的 {@link RegexHighlighter} 工厂（不可实例化）。
 *
 * <p>状态模型：0 = 默认；1 = 字符串内部（含转义与普通段规则）。
 * {@code true}/{@code false}/{@code null} 为 KEYWORD，冒号为
 * DELIMITER，括号与逗号为 PUNCTUATION。</p>
 */
public final class JsonRegexHighlighter {

    private JsonRegexHighlighter() {
    }

    /**
     * 构建 JSON 高亮器。
     *
     * @return 新的 JSON 正则高亮器
     */
    public static RegexHighlighter create() {
        return RegexHighlighter.builder()
            .addRule("\\b(true|false|null)\\b", TokenType.KEYWORD)
            .addRule("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?", TokenType.NUMBER)
            .addRuleInState(0, "\"", 1, TokenType.STRING)
            .addRule("[\\{\\}\\[\\]]", TokenType.PUNCTUATION)
            .addRule(":", TokenType.DELIMITER)
            .addRule(",", TokenType.PUNCTUATION)
            .addRuleInState(1, "\"", 0, TokenType.STRING)
            .addRuleInState(1, "\\\\[\\s\\S]", 1, TokenType.STRING)
            .addRuleInState(1, "[^\"\\\\]+", 1, TokenType.STRING)
            .build();
    }
}
