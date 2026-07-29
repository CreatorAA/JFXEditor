package org.pigeonshouse.javafx.editor.editor.decoration;

/**
 * 文本装饰的线条/外观风格。
 *
 * <p>当前 Skin 在 {@link DecorationType#TEXT_UNDERLINE} 分支中仅区分
 * {@link #WAVY}、{@link #DASHED} 与其余（直线）；{@link #BORDER} 与
 * {@link #HIGHLIGHT} 目前未被特殊处理。</p>
 *
 * @see Decoration
 */
public enum TextDecorationStyle {
    /** 直线下划线。 */
    UNDERLINE,
    /** 波浪线（典型用于错误标记，振幅 2 波长 6）。 */
    WAVY,
    /** 虚线（4-4 点划模式）。 */
    DASHED,
    /** 删除线风格。 */
    STRIKETHROUGH,
    /** 边框风格（预留）。 */
    BORDER,
    /** 背景高亮风格（预留）。 */
    HIGHLIGHT
}
