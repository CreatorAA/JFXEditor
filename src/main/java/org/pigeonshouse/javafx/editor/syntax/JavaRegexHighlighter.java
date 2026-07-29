package org.pigeonshouse.javafx.editor.syntax;

/**
 * Java 语言的 {@link RegexHighlighter} 工厂（不可实例化）。
 *
 * <p><strong>状态模型：</strong>0 = 默认代码；1 = 普通字符串（行尾
 * 复位到 0，不跨行）；2 = 块注释（可跨行）；3 = 文本块（三引号，
 * 可跨行，闭合优先于单引号规则因取最长匹配）。</p>
 */
public final class JavaRegexHighlighter {

    private JavaRegexHighlighter() {
    }

    /** 关键字集（含 var/yield/record/sealed 等及常用包装类名与字面量），词边界包裹。 */
    private static final String KEYWORDS = "\\b(" + String.join("|",
        "abstract", "assert", "boolean", "break", "byte", "case", "catch",
        "char", "class", "const", "continue", "default", "do", "double",
        "else", "enum", "extends", "final", "finally", "float", "for",
        "goto", "if", "implements", "import", "instanceof", "int",
        "interface", "long", "native", "new", "package", "private",
        "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws",
        "transient", "try", "void", "volatile", "while", "var", "yield",
        "record", "sealed", "permits",
        "String", "Integer", "Long", "Double", "Float", "Boolean",
        "true", "false", "null"
    ) + ")\\b";

    /** 数字字面量：十六/二进制、小数与科学计数加类型后缀，负向后行断言排除标识符内部。 */
    private static final String NUMBERS =
        "(?<![\\w$])(0[xX][0-9a-fA-F]+|0[bB][01]+|(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?)[lLfFdD]?";

    /** 运算符集，长运算符优先排列。 */
    private static final String OPERATORS =
        ">>>=|<<=|>>=|>>>|<<|>>|->|::|&&|\\|\\||<=|>=|==|!=" +
        "|\\+=|-=|\\*=|/=|%=|&=|\\|=|\\^=" +
        "|--|\\+\\+|[-+*/%<>=!&|^~?:]";

    /**
     * 构建 Java 高亮器（见类注释中的状态模型）。
     *
     * @return 新的 Java 正则高亮器
     */
    public static RegexHighlighter create() {
        return RegexHighlighter.builder()
            .addRule(KEYWORDS, TokenType.KEYWORD)
            .addRule(NUMBERS, TokenType.NUMBER)
            .addRuleInState(0, "\"\"\"", 3, TokenType.STRING)
            .addRuleInState(0, "\"", 1, TokenType.STRING)
            .addRuleInState(0, "/\\*", 2, TokenType.COMMENT)
            .addRule("//.*", TokenType.COMMENT)
            .addRule("@[a-zA-Z_]\\w*", TokenType.ANNOTATION)
            .addRule(OPERATORS, TokenType.OPERATOR)
            .addRule("[.,;{}()\\[\\]]", TokenType.PUNCTUATION)
            .addRuleInState(1, "\"", 0, TokenType.STRING)
            .addRuleInState(1, "\\\\[\\s\\S]", 1, TokenType.STRING)
            .addRuleInState(1, "[^\"\\\\]+", 1, TokenType.STRING)
            .addRuleInState(2, "\\*/", 0, TokenType.COMMENT)
            .addRuleInState(2, "[\\s\\S]", 2, TokenType.COMMENT)
            .addRuleInState(3, "\"\"\"", 0, TokenType.STRING)
            .addRuleInState(3, "\\\\[\\s\\S]", 3, TokenType.STRING)
            .addRuleInState(3, "[^\"\\\\]+", 3, TokenType.STRING)
            .addRuleInState(3, "\"", 3, TokenType.STRING)
            .resetStateAtLineEnd(1, 0)
            .build();
    }
}
