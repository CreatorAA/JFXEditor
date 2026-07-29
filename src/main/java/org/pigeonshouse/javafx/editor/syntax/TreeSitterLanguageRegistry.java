package org.pigeonshouse.javafx.editor.syntax;

import org.treesitter.TreeSitterJava;
import org.treesitter.TreeSitterJson;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * tree-sitter 语言静态注册表（不可实例化）。
 *
 * <p>内置注册 Java 与 JSON 两种语言（含完整高亮查询）；同 id 或
 * 同扩展名后注册者覆盖先注册者。</p>
 *
 * <p><strong>用法示例（注册自定义语言）：</strong></p>
 * <pre>{@code
 * TreeSitterLanguageRegistry.register(new TreeSitterLanguage(
 *         "python", "Python",
 *         new String[]{"py"},
 *         new TreeSitterPython(),
 *         """
 *         (identifier) @variable
 *         (string) @string
 *         """));
 * TreeSitterHighlighter hl = new TreeSitterHighlighter(
 *         TreeSitterLanguageRegistry.get("python"));
 * }</pre>
 *
 * @see TreeSitterLanguage
 * @see TreeSitterHighlighter
 */
public final class TreeSitterLanguageRegistry {

    /** id → 语言（保持插入序）。 */
    private static final Map<String, TreeSitterLanguage> LANGUAGES = new LinkedHashMap<>();
    /** 扩展名 → 语言（区分大小写，不含点）。 */
    private static final Map<String, TreeSitterLanguage> EXTENSION_MAP = new ConcurrentHashMap<>();

    private TreeSitterLanguageRegistry() {
    }

    /**
     * 注册语言并为每个扩展名建索引（同 id/扩展名后注册者覆盖）。
     *
     * @param language 待注册语言
     */
    public static void register(TreeSitterLanguage language) {
        LANGUAGES.put(language.id(), language);
        for (String ext : language.fileExtensions()) {
            EXTENSION_MAP.put(ext, language);
        }
    }

    /**
     * 按 id 查找语言。
     *
     * @param id 语言标识（如 {@code "java"}）
     * @return 对应语言；未注册时返回 {@code null}
     */
    public static TreeSitterLanguage get(String id) {
        return LANGUAGES.get(id);
    }

    /**
     * 按文件扩展名查找语言（区分大小写，不含点）。
     *
     * @param extension 扩展名（如 {@code "json"}）
     * @return 对应语言；未注册时返回 {@code null}
     */
    public static TreeSitterLanguage getByExtension(String extension) {
        return EXTENSION_MAP.get(extension);
    }

    /** @return 全部已注册语言的不可修改视图（插入序） */
    public static Collection<TreeSitterLanguage> all() {
        return Collections.unmodifiableCollection(LANGUAGES.values());
    }

    /** @return 全部已注册语言 id 的不可修改视图 */
    public static Set<String> supportedLanguages() {
        return Collections.unmodifiableSet(LANGUAGES.keySet());
    }

    static {
        registerJava();
        registerJson();
    }

    /** 内置注册 Java：查询覆盖标识符、方法、注解、类型、常量断言、字面量、注释与完整关键字表。 */
    private static void registerJava() {
        register(new TreeSitterLanguage(
                "java", "Java",
                new String[]{"java"},
                new TreeSitterJava(),
                """
                            (identifier) @variable
                            (method_declaration
                              name: (identifier) @function)
                            (method_invocation
                              name: (identifier) @function)
                            (super) @variable.builtin
                            (annotation
                              name: (identifier) @annotation)
                            (marker_annotation
                              name: (identifier) @annotation)
                            "@" @operator
                            (type_identifier) @type
                            (interface_declaration
                              name: (identifier) @type)
                            (class_declaration
                              name: (identifier) @type)
                            (enum_declaration
                              name: (identifier) @type)
                            (constructor_declaration
                              name: (identifier) @type)
                            [
                              (boolean_type)
                              (integral_type)
                              (floating_point_type)
                              (void_type)
                            ] @type
                            ((identifier) @constant
                             (#match? @constant "^_*[A-Z][A-Z\\d_]+$"))
                            (this) @variable.builtin
                            [
                              (hex_integer_literal)
                              (decimal_integer_literal)
                              (octal_integer_literal)
                              (decimal_floating_point_literal)
                              (hex_floating_point_literal)
                            ] @number
                            [
                              (character_literal)
                              (string_literal)
                            ] @string
                            (escape_sequence) @string.escape
                            [
                              (true)
                              (false)
                              (null_literal)
                            ] @constant
                            [
                              (line_comment)
                              (block_comment)
                            ] @comment
                            [
                              "abstract" "assert" "break" "case" "catch" "class"
                              "continue" "default" "do" "else" "enum" "exports"
                              "extends" "final" "finally" "for" "if" "implements"
                              "import" "instanceof" "interface" "module" "native"
                              "new" "non-sealed" "open" "opens" "package" "permits"
                              "private" "protected" "provides" "public" "requires"
                              "record" "return" "sealed" "static" "strictfp"
                              "switch" "synchronized" "throw" "throws" "to"
                              "transient" "transitive" "try" "uses" "volatile"
                              "when" "while" "with" "yield"
                            ] @keyword
                        """));
    }

    /** 内置注册 JSON：键字符串捕获为 {@code json.key}，字面量与标点各归其类。 */
    private static void registerJson() {
        register(new TreeSitterLanguage(
                "json", "JSON",
                new String[]{"json"},
                new TreeSitterJson(),
                """
                            (pair key: (string) @json.key)
                            (string) @string
                            (number) @number
                            (true) @constant
                            (false) @constant
                            (null) @constant
                            "{" @punctuation
                            "}" @punctuation
                            "[" @punctuation
                            "]" @punctuation
                            "," @punctuation
                            ":" @punctuation
                        """));
    }
}
