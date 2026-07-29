package org.pigeonshouse.javafx.editor.editor.decoration;

/**
 * 装饰类型枚举。
 *
 * <p>当前 Skin 已实现绘制前五种与 {@link #GUTTER_ICON}；
 * {@link #INLINE_NODE} 与 {@link #GUTTER_NODE} 为预留类型。</p>
 *
 * @see Decoration
 */
public enum DecorationType {
    /** 整行背景色（覆盖画布全宽）。 */
    LINE_BACKGROUND,
    /** 行内 {@code [startCol, endCol)} 文本背景高亮。 */
    TEXT_HIGHLIGHT,
    /** 行内文本下划线（风格由 {@link TextDecorationStyle} 控制）。 */
    TEXT_UNDERLINE,
    /** 行内文本删除线（画在行高 50% 处）。 */
    TEXT_STRIKETHROUGH,
    /** 行尾附注文本（缩小字体，画在行尾两空格之后）。 */
    AFTER_TEXT,
    /** 行内嵌入节点（预留，当前 Skin 未实现绘制）。 */
    INLINE_NODE,
    /** gutter 图标（{@code userData} 存符号字符串，画在 gutter 右缘）。 */
    GUTTER_ICON,
    /** gutter 嵌入节点（预留，当前 Skin 未实现绘制）。 */
    GUTTER_NODE
}
