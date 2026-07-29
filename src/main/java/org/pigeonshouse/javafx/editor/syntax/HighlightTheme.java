package org.pigeonshouse.javafx.editor.syntax;

import javafx.scene.paint.Color;

import java.util.HashMap;
import java.util.Map;

/**
 * 不可变的高亮主题快照：{@link TokenType} 到 {@link HighlightStyle} 的映射表。
 *
 * <p><strong>架构定位：</strong>语法配色已完全 CSS 化，本类不再承担
 * 配置角色——实例由 {@code JFXEditor} 从各 token CSS 属性动态拼装，
 * 并经 {@link HighlightEngine#setTheme} 热替换（无需失效 token 缓存）。</p>
 *
 * <p><strong>查找回退链：</strong>精确匹配 → 基础名回退（如
 * {@code keyword.control} → {@code keyword}）→ 默认样式，见
 * {@link #getStyle(TokenType)}。</p>
 */
public final class HighlightTheme {

    /** 主题名称（如 CSS 拼装主题固定为 {@code "CSS"}）。 */
    private final String name;
    /** 类型到样式的不可变映射（构造时防御性拷贝）。 */
    private final Map<TokenType, HighlightStyle> styles;
    /** 回退链末端的默认样式。 */
    private final HighlightStyle defaultStyle;

    private HighlightTheme(String name, Map<TokenType, HighlightStyle> styles, HighlightStyle defaultStyle) {
        this.name = name;
        this.styles = Map.copyOf(styles);
        this.defaultStyle = defaultStyle;
    }

    /** @return 主题名称 */
    public String getName() {
        return name;
    }

    /** @return 默认样式（回退链末端） */
    public HighlightStyle getDefaultStyle() {
        return defaultStyle;
    }

    /**
     * 按回退链查找样式：{@code null} 类型返回默认 → 精确匹配 →
     * 子类型未命中时用 {@link TokenType#getBaseName()} 找基础类型 →
     * 最终返回默认样式。
     *
     * @param type token 类型，可为 {@code null}
     * @return 命中的样式，永不为 {@code null}
     */
    public HighlightStyle getStyle(TokenType type) {
        if (type == null) {
            return defaultStyle;
        }
        HighlightStyle style = styles.get(type);
        if (style != null) {
            return style;
        }
        String baseName = type.getBaseName();
        if (!baseName.equals(type.getName())) {
            TokenType baseType = TokenType.fromName(baseName);
            if (baseType != null) {
                style = styles.get(baseType);
                if (style != null) {
                    return style;
                }
            }
        }
        return defaultStyle;
    }

    /**
     * 返回追加/覆盖一条映射的新主题（原主题不变）。
     *
     * @param type  token 类型
     * @param style 新样式
     * @return 新主题实例
     */
    public HighlightTheme withStyle(TokenType type, HighlightStyle style) {
        Map<TokenType, HighlightStyle> newStyles = new HashMap<>(styles);
        newStyles.put(type, style);
        return new HighlightTheme(name, newStyles, defaultStyle);
    }

    /**
     * 创建完整主题。
     *
     * @param name         主题名
     * @param styles       类型到样式映射（会被防御性拷贝）
     * @param defaultStyle 默认样式
     * @return 新主题
     */
    public static HighlightTheme of(String name, Map<TokenType, HighlightStyle> styles, HighlightStyle defaultStyle) {
        return new HighlightTheme(name, styles, defaultStyle);
    }

    /**
     * 创建空映射的自定义主题（全部类型都落到默认样式）。
     *
     * @param name         主题名
     * @param defaultStyle 默认样式
     * @return 新主题
     */
    public static HighlightTheme custom(String name, HighlightStyle defaultStyle) {
        return new HighlightTheme(name, Map.of(), defaultStyle);
    }

    /**
     * 创建极简主题：无映射，默认浅灰前景色。
     *
     * @return 名为 {@code "Plain"} 的主题
     */
    public static HighlightTheme plain() {
        return new HighlightTheme("Plain", Map.of(), HighlightStyle.of(Color.rgb(212, 212, 212)));
    }
}
