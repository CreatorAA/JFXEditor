package org.pigeonshouse.javafx.editor.editor;

import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import org.pigeonshouse.javafx.editor.core.model.Position;
import org.pigeonshouse.javafx.editor.core.model.TextRange;

/**
 *
 * <p>{@link JFXEditor} 的空间类 API（{@code hitTest}/{@code locate}/视口/滚动/
 * {@code wordRangeAt}）全部委派到<em>当前皮肤</em>——仅当该皮肤实现本接口时才有
 * 有效返回值，否则控件侧返回文档化的兜底值（对象 {@code null}、整型 {@code -1}、
 * setter/滚动为无操作）。如此控件只依赖本接口而非任何具体皮肤类，
 * 自定义皮肤可选择是否提供几何能力。</p>
 *
 * <p><strong>坐标系：</strong>所有像素坐标均为 <em>editor 本地坐标</em>，与在
 * {@link JFXEditor} 控件上收到的 {@code MouseEvent#getX()}/{@code getY()} 同一空间；
 * 行、列一律 0 起。垂直滚动单位为<strong>视觉行数</strong>，水平滚动单位为<strong>像素</strong>
 * （与 {@link org.pigeonshouse.javafx.editor.editor.render.RenderContext} 一致）。</p>
 *
 * <p><strong>线程：</strong>全部方法须在 JavaFX 应用线程调用。</p>
 *
 * @see JFXEditor
 * @see JFXEditorSkin
 */
public interface EditorGeometry {

    /**
     * 像素坐标反解为文档位置（结果钳制到文档合法范围）。
     *
     * @param x editor 本地 x（像素）
     * @param y editor 本地 y（像素）
     * @return 命中的文档位置；空文档返回 {@link Position#ZERO}
     */
    Position hitTest(double x, double y);

    /**
     * 仅按像素 y 反解文档行（结果钳制）。
     *
     * @param y editor 本地 y（像素）
     * @return 文档行号（0 起）；空文档返回 {@code -1}
     */
    int lineAtY(double y);

    /**
     * 文档位置转像素坐标：返回该 (行, 列) 处字符格左上角（视口相对，
     * 可为负或超出视口，表示在可视区域之外）。
     *
     * @param line 文档行（0 起）
     * @param col  列（0 起）
     * @return 左上角 editor 本地坐标；行越界返回 {@code null}
     */
    Point2D locate(int line, int col);

    /**
     * 文档位置处的光标外接矩形：左上角同 {@link #locate}，宽为光标宽、
     * 高为行高（用于把浮窗/弹层锚定到光标）。
     *
     * @param line 文档行（0 起）
     * @param col  列（0 起）
     * @return 光标矩形（editor 本地坐标）；行越界返回 {@code null}
     */
    Bounds caretBounds(int line, int col);

    /** @return 首个可见文档行（0 起）；空文档返回 {@code -1} */
    int firstVisibleLine();

    /** @return 末个可见文档行（0 起，含）；空文档返回 {@code -1} */
    int lastVisibleLine();

    /** @return 水平滚动量（像素） */
    double getScrollX();

    /** @return 垂直滚动量（视觉行数） */
    double getScrollY();

    /**
     * 设置水平滚动量（像素，自动钳制到可滚动范围）。
     *
     * @param pixels 目标像素偏移
     */
    void setScrollX(double pixels);

    /**
     * 设置垂直滚动量（视觉行数，自动钳制到可滚动范围）。
     *
     * @param visualLines 目标视觉行偏移
     */
    void setScrollY(double visualLines);

    /**
     * 垂直滚动使指定文档行进入视口（<strong>不移动光标</strong>）；已可见时不动。
     *
     * @param line 目标文档行（自动钳制）
     */
    void scrollToLine(int line);

    /**
     * 滚动使指定文档位置进入视口（垂直 + 水平，<strong>不移动光标</strong>）。
     *
     * @param line 目标行（自动钳制）
     * @param col  目标列（自动钳制）
     */
    void revealPosition(int line, int col);

    /**
     * 返回指定位置处的「词」区间：以该位置字符的类别（词字符 = 字母/数字/
     * 下划线，或与之相对的非词字符）向两侧扩展到同类字符游程边界
     * （双击选词语义）。
     *
     * @param line 文档行（0 起）
     * @param col  列（0 起）
     * @return 词区间（同一行内）；行越界返回 {@code null}
     */
    TextRange wordRangeAt(int line, int col);
}
