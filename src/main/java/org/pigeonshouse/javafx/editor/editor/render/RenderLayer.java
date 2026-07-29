package org.pigeonshouse.javafx.editor.editor.render;

import javafx.scene.canvas.GraphicsContext;

import java.util.List;

/**
 * 自定义渲染层 SPI：在编辑器固定内容（背景/选区/文本/装饰/gutter）
 * 绘制完成后叠加额外内容（如幽灵文本、内联提示）。
 *
 * <p><strong>注册方式：</strong>直接向 {@code JFXEditor#renderLayers()}
 * 返回的活列表 add/remove。所有回调均在 JavaFX 应用线程、每帧执行。</p>
 *
 * <p><strong>注意：</strong>Skin 构建偏移表时会以 {@code null} 上下文
 * 调用 {@link #getRenderOffsets}，实现必须容忍 {@code null} 参数；
 * 各层按 {@link #getZOrder()} 升序渲染，同序号保持注册顺序。</p>
 *
 * @see GhostTextRenderLayer
 * @see RenderOffset
 */
public interface RenderLayer {

    /**
     * @return 层名称（用于调试与识别）
     */
    String getName();

    /**
     * 绘制本层内容，在固定内容之后调用。
     *
     * @param gc      画布绘图上下文
     * @param context 本帧渲染快照（同锚叠放时已叠加基准偏移）
     */
    void render(GraphicsContext gc, RenderContext context);

    /**
     * 声明本层需要的视觉偏移（行插入/行内像素推移）。
     *
     * @param context 渲染上下文，<strong>可能为 {@code null}</strong>
     *                （Skin 汇总偏移时传入），实现必须容忍
     * @return 偏移列表；默认无偏移
     */
    default List<RenderOffset> getRenderOffsets(RenderContext context) {
        return List.of();
    }

    /**
     * 每帧渲染前回调（在固定内容绘制之前），可用于准备状态。
     *
     * @param context 本帧渲染快照
     */
    default void beforeRender(RenderContext context) {
    }

    /**
     * 声明层叠放顺序（数值越大越靠上）。
     *
     * <p>Skin 按本值升序稳定排序后渲染，同序号的层保持注册顺序。</p>
     *
     * @return 叠放序号，默认 0
     */
    default int getZOrder() {
        return 0;
    }
}