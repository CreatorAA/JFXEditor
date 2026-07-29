package org.pigeonshouse.javafx.editor.syntax;

/**
 * Token 类型枚举，采用点分层级命名（如 {@code keyword.control}）。
 *
 * <p><strong>CSS 化配色：</strong>每个枚举值对应一个 CSS 属性
 * {@code -editor-token-<名称点转横线>}（颜色）与 {@code ...-style}
 * （字形）；子类型颜色缺省时经 {@link #getBaseName()} 回退到基础
 * 类型（如 {@code keyword.control} → {@code keyword}），见
 * {@link HighlightTheme#getStyle}。</p>
 */
public enum TokenType {
    /** 普通文本（默认类型）。 */
    TEXT("text"),
    /** 关键字。 */
    KEYWORD("keyword"),
    /** 控制流关键字（if/for/return 等）。 */
    KEYWORD_CONTROL("keyword.control"),
    /** 声明关键字（class/interface 等）。 */
    KEYWORD_DECLARATION("keyword.declaration"),
    /** 修饰符关键字（public/static 等）。 */
    KEYWORD_MODIFIER("keyword.modifier"),
    /** 行注释。 */
    COMMENT("comment"),
    /** 块注释。 */
    COMMENT_BLOCK("comment.block"),
    /** 文档注释（Javadoc 等）。 */
    COMMENT_DOC("comment.doc"),
    /** 字符串字面量。 */
    STRING("string"),
    /** 字符串转义序列。 */
    STRING_ESCAPE("string.escape"),
    /** 数字字面量。 */
    NUMBER("number"),
    /** 整数字面量。 */
    NUMBER_INTEGER("number.integer"),
    /** 浮点字面量。 */
    NUMBER_FLOAT("number.float"),
    /** 十六进制字面量。 */
    NUMBER_HEX("number.hex"),
    /** 运算符。 */
    OPERATOR("operator"),
    /** 标点（括号、逗号等）。 */
    PUNCTUATION("punctuation"),
    /** 分隔符（如 JSON 冒号）。 */
    DELIMITER("delimiter"),
    /** 类型名。 */
    TYPE("type"),
    /** 接口名。 */
    INTERFACE("interface"),
    /** 函数/方法名。 */
    FUNCTION("function"),
    /** 函数声明处的名称。 */
    FUNCTION_DECLARATION("function.declaration"),
    /** 内置函数。 */
    FUNCTION_BUILTIN("function.builtin"),
    /** 变量名。 */
    VARIABLE("variable"),
    /** 内置变量（this/super 等）。 */
    VARIABLE_BUILTIN("variable.builtin"),
    /** 常量（全大写标识符、true/false/null 等）。 */
    CONSTANT("constant"),
    /** 内置常量。 */
    CONSTANT_BUILTIN("constant.builtin"),
    /** 属性/键（如 JSON key）。 */
    PROPERTY("property"),
    /** 参数名。 */
    PARAMETER("parameter"),
    /** 注解（{@code @Xxx}）。 */
    ANNOTATION("annotation"),
    /** 标签。 */
    LABEL("label"),
    /** 错误标记。 */
    ERROR("error"),
    /** 已废弃标记。 */
    DEPRECATED("deprecated"),
    /** 正则字面量。 */
    REGEX("regex");

    /** 默认类型，即 {@link #TEXT}。 */
    public static final TokenType DEFAULT = TEXT;

    /** 小写点分层级名称。 */
    private final String name;

    TokenType(String name) {
        this.name = name;
    }

    /** @return 小写点分名称（如 {@code "keyword.control"}） */
    public String getName() {
        return name;
    }

    /**
     * @return 首个点之前的基础名（无点时返回全名），用于样式回退
     */
    public String getBaseName() {
        int dotIndex = name.indexOf('.');
        return dotIndex < 0 ? name : name.substring(0, dotIndex);
    }

    /**
     * 判断本类型是否属于某基础名（名称相等或以“基础名.”为前缀）。
     *
     * @param baseName 基础名（如 {@code "keyword"}）
     * @return 属于时返回 {@code true}
     */
    public boolean isSubTypeOf(String baseName) {
        return name.startsWith(baseName + ".") || name.equals(baseName);
    }

    /**
     * 按点分名称线性查找枚举值。
     *
     * @param name 点分名称（如 {@code "string.escape"}）
     * @return 对应枚举值；未找到时返回 {@code null}
     */
    public static TokenType fromName(String name) {
        for (TokenType type : values()) {
            if (type.name.equals(name)) {
                return type;
            }
        }
        return null;
    }
}
