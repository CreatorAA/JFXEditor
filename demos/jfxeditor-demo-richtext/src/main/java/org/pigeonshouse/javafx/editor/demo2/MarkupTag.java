package org.pigeonshouse.javafx.editor.demo2;

import java.util.Map;

/**
 * 一个已闭合且属性合法的标记。raw 即原文，兼作组件复用的身份键。
 */
public record MarkupTag(
        String name,
        Map<String, String> attrs,
        int line,
        int startCol,
        int endCol,
        String raw
) {

    public String attr(String key) {
        return attrs.get(key);
    }

    public int intAttr(String key, int defaultValue) {
        String value = attrs.get(key);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
