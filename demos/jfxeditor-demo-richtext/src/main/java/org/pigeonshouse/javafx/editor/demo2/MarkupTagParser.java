package org.pigeonshouse.javafx.editor.demo2;

import org.pigeonshouse.javafx.editor.core.document.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 扫描文档里已闭合、属性合法的自闭合标记（单行内）。
 * 半成品直接跳过，所以“闭合的一瞬间才出组件”这个交互是免费的。
 */
public final class MarkupTagParser {

    private static final Pattern TAG_PATTERN = Pattern.compile(
            "<(fileindex|img|showFile|link|table|list)\\b([^<>]*?)/>");

    private static final Pattern ATTR_PATTERN = Pattern.compile(
            "([A-Za-z][A-Za-z0-9]*)\\s*=\\s*\"([^\"]*)\"");

    private static final Set<String> SHOW_TYPES = Set.of("ERROR", "WARNING", "INFO");

    private MarkupTagParser() {
    }

    public static List<MarkupTag> parse(Document document) {
        List<MarkupTag> result = new ArrayList<>();
        int lineCount = document.getLineCount();
        for (int line = 0; line < lineCount; line++) {
            String text = document.getLine(line);
            if (text.indexOf('<') < 0) {
                continue;
            }
            Matcher matcher = TAG_PATTERN.matcher(text);
            while (matcher.find()) {
                String name = matcher.group(1);
                Map<String, String> attrs = parseAttributes(matcher.group(2));
                if (!isValid(name, attrs)) {
                    continue;
                }
                result.add(new MarkupTag(name, Map.copyOf(attrs), line,
                        matcher.start(), matcher.end(), matcher.group()));
            }
        }
        return result;
    }

    /** 解析属性区；重复属性后者覆盖。 */
    private static Map<String, String> parseAttributes(String attrText) {
        Map<String, String> attrs = new HashMap<>();
        Matcher matcher = ATTR_PATTERN.matcher(attrText);
        while (matcher.find()) {
            attrs.put(matcher.group(1), matcher.group(2));
        }
        return attrs;
    }

    private static boolean isValid(String name, Map<String, String> attrs) {
        return switch (name) {
            case "fileindex" -> nonBlank(attrs.get("src"))
                    && positiveInt(attrs.get("line"))
                    && positiveInt(attrs.get("col"))
                    && (attrs.get("showType") == null || SHOW_TYPES.contains(attrs.get("showType")));
            case "img" -> nonBlank(attrs.get("src"))
                    && optionalPositiveInt(attrs.get("width"))
                    && optionalPositiveInt(attrs.get("height"));
            case "showFile" -> nonBlank(attrs.get("src"))
                    && positiveInt(attrs.get("startLine"))
                    && positiveInt(attrs.get("endLine"))
                    && Integer.parseInt(attrs.get("startLine").trim())
                    <= Integer.parseInt(attrs.get("endLine").trim());
            case "link" -> nonBlank(attrs.get("href")) && nonBlank(attrs.get("text"));
            case "table" -> nonBlank(attrs.get("headers")) && nonBlank(attrs.get("rows"));
            case "list" -> ("ordered".equals(attrs.get("type")) || "unordered".equals(attrs.get("type")))
                    && nonBlank(attrs.get("items"));
            default -> false;
        };
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean positiveInt(String value) {
        if (value == null) return false;
        try {
            return Integer.parseInt(value.trim()) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean optionalPositiveInt(String value) {
        return value == null || positiveInt(value);
    }
}
