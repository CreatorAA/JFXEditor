package org.pigeonshouse.javafx.editor.syntax;

import org.treesitter.TSLanguage;

import java.util.List;

/**
 * tree-sitter 语言描述：封装语言标识、原生绑定与高亮查询。
 *
 * <p>相等性与散列仅基于 {@code id}。通过
 * {@link TreeSitterLanguageRegistry#register} 注册后可按 id 或扩展名查找。</p>
 *
 * @param id             语言唯一标识（如 {@code "java"}）
 * @param name           显示名（如 {@code "Java"}）
 * @param fileExtensions 支持的文件扩展名（不含点）
 * @param language       tree-sitter 原生语言绑定
 * @param highlightQuery 高亮查询源码（S 表达式）
 * @see TreeSitterHighlighter
 */
public record TreeSitterLanguage(
        String id,
        String name,
        String[] fileExtensions,
        TSLanguage language,
        String highlightQuery
) {

    /** @return 扩展名的不可变列表 */
    public List<String> getExtensions() {
        return List.of(fileExtensions);
    }

    /**
     * 忽略大小写判断是否支持某扩展名。
     *
     * @param extension 扩展名（不含点）
     * @return 支持时返回 {@code true}
     */
    public boolean supportsExtension(String extension) {
        for (String ext : fileExtensions) {
            if (ext.equalsIgnoreCase(extension)) {
                return true;
            }
        }
        return false;
    }

    /** @return 形如 {@code TreeSitterLanguage[id:name]} 的调试字符串 */
    @Override
    public String toString() {
        return "TreeSitterLanguage[" + id + ":" + name + "]";
    }

    /** 仅基于 {@code id} 判等。 */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TreeSitterLanguage that)) return false;
        return id.equals(that.id);
    }

    /** 仅基于 {@code id} 散列。 */
    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
