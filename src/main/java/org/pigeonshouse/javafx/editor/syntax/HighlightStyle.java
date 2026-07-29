package org.pigeonshouse.javafx.editor.syntax;

import javafx.scene.paint.Color;

/**
 * 高亮样式值对象：颜色加粗体/斜体/下划线三个字形旗标（不可变）。
 *
 * @param color     前景色
 * @param bold      是否粗体
 * @param italic    是否斜体
 * @param underline 是否下划线
 * @see HighlightTheme
 */
public record HighlightStyle(Color color, boolean bold, boolean italic, boolean underline) {

    /**
     * @param color 前景色
     * @return 无任何字形旗标的样式
     */
    public static HighlightStyle of(Color color) {
        return new HighlightStyle(color, false, false, false);
    }

    /**
     * @param color 前景色
     * @return 仅粗体的样式
     */
    public static HighlightStyle ofBold(Color color) {
        return new HighlightStyle(color, true, false, false);
    }

    /**
     * @param color 前景色
     * @return 仅斜体的样式
     */
    public static HighlightStyle ofItalic(Color color) {
        return new HighlightStyle(color, false, true, false);
    }

    /**
     * @param color 前景色
     * @return 仅下划线的样式
     */
    public static HighlightStyle ofUnderline(Color color) {
        return new HighlightStyle(color, false, false, true);
    }

    /** @return 打开粗体旗标的新副本 */
    public HighlightStyle withBold() {
        return new HighlightStyle(color, true, italic, underline);
    }

    /** @return 打开斜体旗标的新副本 */
    public HighlightStyle withItalic() {
        return new HighlightStyle(color, bold, true, underline);
    }

    /** @return 打开下划线旗标的新副本 */
    public HighlightStyle withUnderline() {
        return new HighlightStyle(color, bold, italic, true);
    }
}
