package org.pigeonshouse.javafx.editor.editor.indent;

import org.pigeonshouse.javafx.editor.core.document.Document;

/**
 * 内置缩进策略工厂。
 *
 * <p>提供四族策略：{@link #NONE}（不缩进）、{@link #BASIC}（继承
 * 上一行前导空白）、{@link #bracket()}（花括号感知，适合 C 系语言）、
 * {@link #colon()}（冒号感知，适合 Python 风格）。带缩进单位参数的
 * 工厂方法可自定义每级缩进（默认 {@value #DEFAULT_INDENT_UNIT} 即 4 空格）。</p>
 *
 * <p><strong>用法示例：</strong></p>
 * <pre>{@code
 * editor.setIndentStrategy(IndentStrategies.bracket());      // Java/C 风格
 * editor.setIndentStrategy(IndentStrategies.colon("  "));    // Python 风格，2 空格
 * }</pre>
 *
 * @see IndentStrategy
 */
public final class IndentStrategies {

    /** 默认缩进单位：4 个空格。 */
    public static final String DEFAULT_INDENT_UNIT = "    ";

    /** 不缩进：新行永远从第 0 列开始。 */
    public static final IndentStrategy NONE = (doc, line, col) -> "";

    /** 继承缩进：新行继承当前行的前导空白（截断到光标列）。 */
    public static final IndentStrategy BASIC = IndentStrategies::basicIndent;

    private IndentStrategies() {
    }

    /**
     * 花括号感知策略（默认缩进单位）。
     *
     * @return 新策略实例
     */
    public static IndentStrategy bracket() {
        return bracket(DEFAULT_INDENT_UNIT);
    }

    /**
     * 花括号感知策略：光标前文本以 <code>{</code> 结尾时增加一级缩进；
     * 输入 <code>}</code> 且整行仅有它时回退一级缩进。
     *
     * @param indentUnit 每级缩进字符串
     * @return 新策略实例
     */
    public static IndentStrategy bracket(String indentUnit) {
        return new IndentStrategy() {
            @Override
            public String computeIndent(Document doc, int line, int col) {
                String base = basicIndent(doc, line, col);
                String beforeCaret = textBeforeCaret(doc, line, col);
                if (beforeCaret.stripTrailing().endsWith("{")) {
                    return base + indentUnit;
                }
                return base;
            }

            @Override
            public String adjustIndentOnType(Document doc, int line, char typed) {
                if (typed != '}' || !isValidLine(doc, line)) {
                    return null;
                }
                String content = doc.getLine(line);
                if (!content.strip().equals("}")) {
                    return null;
                }
                String leading = leadingWhitespace(content);
                if (leading.isEmpty()) {
                    return null;
                }
                int cut = Math.min(indentUnit.length(), leading.length());
                return leading.substring(0, leading.length() - cut);
            }
        };
    }

    /**
     * 冒号感知策略（默认缩进单位）。
     *
     * @return 新策略实例
     */
    public static IndentStrategy colon() {
        return colon(DEFAULT_INDENT_UNIT);
    }

    /**
     * 冒号感知策略：光标前文本以 {@code :} 结尾时增加一级缩进
     * （Python 风格，无回退调整）。
     *
     * @param indentUnit 每级缩进字符串
     * @return 新策略实例
     */
    public static IndentStrategy colon(String indentUnit) {
        return (doc, line, col) -> {
            String base = basicIndent(doc, line, col);
            String beforeCaret = textBeforeCaret(doc, line, col);
            if (beforeCaret.stripTrailing().endsWith(":")) {
                return base + indentUnit;
            }
            return base;
        };
    }

    /** BASIC 语义：当前行前导空白截断到光标列；非法行号返回空串。 */
    private static String basicIndent(Document doc, int line, int col) {
        if (!isValidLine(doc, line)) {
            return "";
        }
        String leading = leadingWhitespace(doc.getLine(line));
        int cap = Math.max(0, Math.min(col, leading.length()));
        return leading.substring(0, cap);
    }

    /** 返回光标前的行内文本；非法行号返回空串。 */
    private static String textBeforeCaret(Document doc, int line, int col) {
        if (!isValidLine(doc, line) || col <= 0) {
            return "";
        }
        return doc.getLineSegment(line, 0, col);
    }

    /** 提取行首的空格/制表符前缀。 */
    private static String leadingWhitespace(String content) {
        int i = 0;
        while (i < content.length() && (content.charAt(i) == ' ' || content.charAt(i) == '\t')) {
            i++;
        }
        return content.substring(0, i);
    }

    /** 行号是否落在文档合法范围内。 */
    private static boolean isValidLine(Document doc, int line) {
        return line >= 0 && line < doc.getLineCount();
    }
}
