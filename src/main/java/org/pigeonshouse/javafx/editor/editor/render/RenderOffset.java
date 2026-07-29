package org.pigeonshouse.javafx.editor.editor.render;

/**
 * 渲染层声明的视觉偏移（密封接口，两种实现）：
 * <ul>
 *   <li>{@link LineInsertion}：在锚点文档行之后插入若干视觉空行，
 *       把后续文档行整体下推（典型：多行幽灵文本）；</li>
 *   <li>{@link InlinePush}：把某行从某列起的内容向右推若干像素
 *       （典型：行内嵌入提示）。</li>
 * </ul>
 *
 * <p>静态工厂会把负值钳为 0。由 {@link LineOffsetMap} 汇总消费。</p>
 *
 * @see RenderLayer#getRenderOffsets
 */
public sealed interface RenderOffset {

    /** @return 锚点文档行号（0 起） */
    int anchorLine();

    /** @return 本偏移是否为行插入类型 */
    boolean isLineInsertion();

    /** @return 本偏移是否为行内推移类型 */
    boolean isInlinePush();

    /** @return 插入的额外视觉行数；非行插入类型默认 0 */
    default int extraLines() {
        return 0;
    }

    /** @return 行内推移的锚点列；非行内推移类型默认 0 */
    default int anchorColumn() {
        return 0;
    }

    /** @return 行内推移的像素数；非行内推移类型默认 0 */
    default double extraPixels() {
        return 0.0;
    }

    /**
     * 创建行插入偏移。
     *
     * @param anchorLine 锚点文档行（锚点行自身不动，其后行被下推）
     * @param extraLines 插入的视觉空行数（负值钳为 0）
     * @return 行插入偏移
     */
    static RenderOffset lineInsertion(int anchorLine, int extraLines) {
        return new LineInsertion(anchorLine, Math.max(0, extraLines));
    }

    /**
     * 创建行内像素推移偏移。
     *
     * @param anchorLine   锚点文档行
     * @param anchorColumn 锚点列（从该列起的内容被右推）
     * @param extraPixels  推移像素数（负值钳为 0）
     * @return 行内推移偏移
     */
    static RenderOffset inlinePush(int anchorLine, int anchorColumn, double extraPixels) {
        return new InlinePush(anchorLine, anchorColumn, Math.max(0.0, extraPixels));
    }

    /**
     * 行插入偏移：在锚点行之后插入 {@code extraLines} 个视觉空行。
     *
     * @param anchorLine 锚点文档行
     * @param extraLines 插入的视觉空行数
     */
    record LineInsertion(int anchorLine, int extraLines) implements RenderOffset {
        @Override
        public boolean isLineInsertion() {
            return true;
        }

        @Override
        public boolean isInlinePush() {
            return false;
        }
    }

    /**
     * 行内推移偏移：把锚点行从锚点列起的内容向右推 {@code extraPixels} 像素。
     *
     * @param anchorLine   锚点文档行
     * @param anchorColumn 锚点列
     * @param extraPixels  推移像素数
     */
    record InlinePush(int anchorLine, int anchorColumn, double extraPixels) implements RenderOffset {
        @Override
        public boolean isLineInsertion() {
            return false;
        }

        @Override
        public boolean isInlinePush() {
            return true;
        }
    }
}