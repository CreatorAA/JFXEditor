package org.pigeonshouse.javafx.editor.editor.decoration;

import javafx.scene.paint.Color;

import java.util.Comparator;
import java.util.Objects;

/**
 * 不可变的编辑器装饰值对象（行背景、文本高亮、下划线、行尾附注等）。
 *
 * <p><strong>坐标约定：</strong>行区间 {@code [startLine, endLine]} 为
 * 闭区间；列区间 {@code [startCol, endCol)} 仅文本类装饰使用。
 * 当前全部静态工厂都创建单行装饰。</p>
 *
 * <p><strong>相等性：</strong>双方都有 {@code id} 时按 id 判等，
 * 否则按类型+区间判等。排序规则：{@code startLine} 升序 →
 * {@code priority} 降序 → 类型 → {@code startCol}。</p>
 *
 * <p><strong>用法示例（带悬停效果的错误波浪线）：</strong></p>
 * <pre>{@code
 * Decoration deco = Decoration
 *         .textUnderline(5, 4, 12, TextDecorationStyle.WAVY, Color.RED)
 *         .withId("error-42")
 *         .withHoverColor(Color.ORANGE)
 *         .withHoverListener(new DecorationHoverListener() {
 *             public void onHoverStart(Decoration d) { showTooltip(); }
 *             public void onHoverEnd(Decoration d) { hideTooltip(); }
 *         });
 * editor.decorationModel().addDecoration(deco);
 *
 * // 文档编辑后维护位置：
 * Decoration moved = deco.adjustForEdit(editStartLine, lineDelta);
 * if (moved == null) { model.removeById("error-42"); }        // 被删除吞没
 * else if (moved != deco) { model.replaceDecoration(moved); } // 需要平移
 * }</pre>
 *
 * @see DecorationModel
 * @see DecorationType
 */
public final class Decoration implements Comparable<Decoration> {

    /** 装饰类型，决定绘制方式与命中检测规则。 */
    private final DecorationType type;
    /** 起始行（闭区间，0 起）。 */
    private final int startLine;
    /** 结束行（闭区间）；工厂方法目前均创建单行装饰。 */
    private final int endLine;
    /** 起始列（含），仅文本类装饰使用。 */
    private final int startCol;
    /** 结束列（不含），仅文本类装饰使用。 */
    private final int endCol;
    /** 主颜色（AFTER_TEXT 默认改用编辑器 afterTextColor）。 */
    private final Color color;
    /** 下划线风格（仅 TEXT_UNDERLINE 使用）。 */
    private final TextDecorationStyle decorationStyle;
    /** 行尾附注文本（仅 AFTER_TEXT 使用）。 */
    private final String afterText;
    /** 优先级，越大越优先（悬停命中与排序均使用）。 */
    private final int priority;
    /** 可选唯一标识；非空时参与按 id 判等与索引。 */
    private final String id;
    /** 任意载荷（GUTTER_ICON 存符号字符串，节点类存节点）。 */
    private final Object userData;
    /** 悬停时的替换色；非空即使装饰可悬停。 */
    private final Color hoverColor;
    /** 悬停回调；非空即使装饰可悬停。 */
    private final DecorationHoverListener hoverListener;

    /** 排序器：startLine 升序 → priority 降序 → 类型 → startCol。 */
    private static final Comparator<Decoration> COMPARATOR =
            Comparator.comparingInt(Decoration::startLine)
                    .thenComparing(Comparator.comparingInt(Decoration::priority).reversed())
                    .thenComparing(Decoration::type)
                    .thenComparingInt(Decoration::startCol);

    private Decoration(DecorationType type, int startLine, int endLine, int startCol, int endCol,
                       Color color, TextDecorationStyle decorationStyle, String afterText,
                       int priority, String id, Object userData) {
        this(type, startLine, endLine, startCol, endCol, color, decorationStyle,
                afterText, priority, id, userData, null, null);
    }

    private Decoration(DecorationType type, int startLine, int endLine, int startCol, int endCol,
                       Color color, TextDecorationStyle decorationStyle, String afterText,
                       int priority, String id, Object userData,
                       Color hoverColor, DecorationHoverListener hoverListener) {
        this.type = type;
        this.startLine = startLine;
        this.endLine = endLine;
        this.startCol = startCol;
        this.endCol = endCol;
        this.color = color;
        this.decorationStyle = decorationStyle;
        this.afterText = afterText;
        this.priority = priority;
        this.id = id;
        this.userData = userData;
        this.hoverColor = hoverColor;
        this.hoverListener = hoverListener;
    }

    /** @return 装饰类型 */
    public DecorationType type() {
        return type;
    }

    /** @return 起始行（闭区间） */
    public int startLine() {
        return startLine;
    }

    /** @return 结束行（闭区间） */
    public int endLine() {
        return endLine;
    }

    /** @return 起始列（含，仅文本类装饰有意义） */
    public int startCol() {
        return startCol;
    }

    /** @return 结束列（不含，仅文本类装饰有意义） */
    public int endCol() {
        return endCol;
    }

    /** @return 主颜色，可能为 {@code null} */
    public Color color() {
        return color;
    }

    /** @return 下划线风格，可能为 {@code null} */
    public TextDecorationStyle decorationStyle() {
        return decorationStyle;
    }

    /** @return 行尾附注文本，可能为 {@code null} */
    public String afterText() {
        return afterText;
    }

    /** @return 优先级（越大越优先） */
    public int priority() {
        return priority;
    }

    /** @return 唯一标识，可能为 {@code null} */
    public String id() {
        return id;
    }

    /** @return 任意用户载荷，可能为 {@code null} */
    public Object userData() {
        return userData;
    }

    /** @return 悬停替换色，可能为 {@code null} */
    public Color hoverColor() {
        return hoverColor;
    }

    /** @return 悬停监听器，可能为 {@code null} */
    public DecorationHoverListener hoverListener() {
        return hoverListener;
    }

    /** @return {@link #startLine()} 的别名（单行装饰的习惯取法） */
    public int line() {
        return startLine;
    }

    /**
     * @param newId 新的唯一标识
     * @return 仅 id 不同的新副本
     */
    public Decoration withId(String newId) {
        return new Decoration(type, startLine, endLine, startCol, endCol,
                color, decorationStyle, afterText, priority, newId, userData,
                hoverColor, hoverListener);
    }

    /**
     * @param newPriority 新优先级
     * @return 仅优先级不同的新副本
     */
    public Decoration withPriority(int newPriority) {
        return new Decoration(type, startLine, endLine, startCol, endCol,
                color, decorationStyle, afterText, newPriority, id, userData,
                hoverColor, hoverListener);
    }

    /**
     * @param data 新的用户载荷
     * @return 仅载荷不同的新副本
     */
    public Decoration withUserData(Object data) {
        return new Decoration(type, startLine, endLine, startCol, endCol,
                color, decorationStyle, afterText, priority, id, data,
                hoverColor, hoverListener);
    }

    /**
     * @param newHoverColor 悬停替换色（非空即使装饰可悬停）
     * @return 仅悬停色不同的新副本
     */
    public Decoration withHoverColor(Color newHoverColor) {
        return new Decoration(type, startLine, endLine, startCol, endCol,
                color, decorationStyle, afterText, priority, id, userData,
                newHoverColor, hoverListener);
    }

    /**
     * @param newHoverListener 悬停回调（非空即使装饰可悬停）
     * @return 仅悬停监听器不同的新副本
     */
    public Decoration withHoverListener(DecorationHoverListener newHoverListener) {
        return new Decoration(type, startLine, endLine, startCol, endCol,
                color, decorationStyle, afterText, priority, id, userData,
                hoverColor, newHoverListener);
    }

    /**
     * 根据行级编辑调整装饰位置。
     *
     * <ul>
     *   <li>编辑点在装饰之后：原样返回自身；</li>
     *   <li>删除吞没整个装饰：返回 {@code null}（调用方应移除）；</li>
     *   <li>装饰起点在编辑点之后：整体平移；</li>
     *   <li>仅终点跨过编辑点：只移终点。</li>
     * </ul>
     *
     * @param editStartLine 编辑起始行
     * @param lineDelta     行数增量（增为正、删为负）
     * @return 调整后的装饰；被删除吞没时返回 {@code null}
     */
    public Decoration adjustForEdit(int editStartLine, int lineDelta) {
        if (editStartLine > endLine) {
            return this;
        }
        if (lineDelta < 0 && endLine + lineDelta < editStartLine) {
            return null;
        }
        int newStartLine = startLine;
        int newEndLine = endLine;
        if (startLine >= editStartLine) {
            newStartLine = startLine + lineDelta;
            newEndLine = endLine + lineDelta;
        } else if (endLine >= editStartLine) {
            newEndLine = endLine + lineDelta;
        }
        return new Decoration(type, newStartLine, newEndLine, startCol, endCol,
                color, decorationStyle, afterText, priority, id, userData,
                hoverColor, hoverListener);
    }

    /** 按预定义排序器比较（见类注释中的排序规则）。 */
    @Override
    public int compareTo(Decoration other) {
        return COMPARATOR.compare(this, other);
    }

    /** 双方都有 id 时按 id 判等，否则按类型与行列区间判等。 */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Decoration that)) return false;
        if (id != null && that.id != null) return id.equals(that.id);
        return type == that.type && startLine == that.startLine
                && endLine == that.endLine && startCol == that.startCol
                && endCol == that.endCol;
    }

    /** 与 {@link #equals(Object)} 一致：有 id 时取 id 散列，否则取类型与区间散列。 */
    @Override
    public int hashCode() {
        if (id != null) return id.hashCode();
        return Objects.hash(type, startLine, endLine, startCol, endCol);
    }

    @Override
    public String toString() {
        return "Decoration[type=" + type + ", line=" + startLine + ", id=" + id + "]";
    }

    /**
     * 创建整行背景装饰。
     *
     * @param line  目标行
     * @param color 背景色
     * @return 新装饰
     */
    public static Decoration lineBackground(int line, Color color) {
        return new Decoration(DecorationType.LINE_BACKGROUND, line, line, 0, 0,
                color, null, null, 0, null, null);
    }

    /**
     * 创建行内文本背景高亮装饰。
     *
     * @param line     目标行
     * @param startCol 起始列（含）
     * @param endCol   结束列（不含）
     * @param color    高亮色
     * @return 新装饰
     */
    public static Decoration textHighlight(int line, int startCol, int endCol, Color color) {
        return new Decoration(DecorationType.TEXT_HIGHLIGHT, line, line, startCol, endCol,
                color, null, null, 0, null, null);
    }

    /**
     * 创建行内文本下划线装饰。
     *
     * @param line     目标行
     * @param startCol 起始列（含）
     * @param endCol   结束列（不含）
     * @param style    线条风格（直线/波浪/虚线）
     * @param color    线色
     * @return 新装饰
     */
    public static Decoration textUnderline(int line, int startCol, int endCol,
                                           TextDecorationStyle style, Color color) {
        return new Decoration(DecorationType.TEXT_UNDERLINE, line, line, startCol, endCol,
                color, style, null, 0, null, null);
    }

    /**
     * 创建行内文本删除线装饰。
     *
     * @param line     目标行
     * @param startCol 起始列（含）
     * @param endCol   结束列（不含）
     * @param color    线色
     * @return 新装饰
     */
    public static Decoration textStrikethrough(int line, int startCol, int endCol, Color color) {
        return new Decoration(DecorationType.TEXT_STRIKETHROUGH, line, line, startCol, endCol,
                color, null, null, 0, null, null);
    }

    /**
     * 创建行尾附注装饰（颜色默认取编辑器 afterTextColor）。
     *
     * @param line 目标行
     * @param text 附注文本
     * @return 新装饰
     */
    public static Decoration afterText(int line, String text) {
        return new Decoration(DecorationType.AFTER_TEXT, line, line, 0, 0,
                null, null, text, 0, null, null);
    }

    /**
     * 创建 gutter 图标装饰。
     *
     * @param line   目标行
     * @param symbol 符号字符串（存入 {@code userData}，如 {@code "●"}）
     * @param color  图标色
     * @return 新装饰
     */
    public static Decoration gutterIcon(int line, String symbol, Color color) {
        return new Decoration(DecorationType.GUTTER_ICON, line, line, 0, 0,
                color, null, null, 0, null, symbol);
    }

    /**
     * 创建行内嵌入节点装饰（预留类型，当前 Skin 未实现绘制）。
     *
     * @param line 目标行
     * @param node 嵌入节点（存入 {@code userData}）
     * @return 新装饰
     */
    public static Decoration inlineNode(int line, Object node) {
        return new Decoration(DecorationType.INLINE_NODE, line, line, 0, 0,
                null, null, null, 0, null, node);
    }

    /**
     * 创建 gutter 嵌入节点装饰（预留类型，当前 Skin 未实现绘制）。
     *
     * @param line 目标行
     * @param node 嵌入节点（存入 {@code userData}）
     * @return 新装饰
     */
    public static Decoration gutterNode(int line, Object node) {
        return new Decoration(DecorationType.GUTTER_NODE, line, line, 0, 0,
                null, null, null, 0, null, node);
    }
}
