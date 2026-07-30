package org.pigeonshouse.javafx.editor.editor.wrap;

/**
 * 软换行断点策略：决定一段视觉行在何处折断。
 *
 * <p><strong>调用时机：</strong>布局引擎按像素宽度贪心排布字符，当
 * 第一个放不下的字符出现时，先算出「字符级最大可容纳列」{@code maxFitCol}
 * （即 {@code [segmentStartCol, maxFitCol)} 恰好塞满可用宽度），再交由本
 * 策略在 {@code (segmentStartCol, maxFitCol]} 区间内回退挑选真正的断点。</p>
 *
 * <p><strong>边界约定：</strong>返回值必须落在 {@code (segmentStartCol, maxFitCol]}
 * 内——引擎会把越界值钳回 {@code maxFitCol}，因此实现可以放心地在找不到
 * 合适断点时直接返回 {@code maxFitCol}。实现必须容忍任意入参、不得抛异常，
 * 且必须保证返回值严格大于 {@code segmentStartCol}（否则会导致空段死循环，
 * 引擎同样会兜底钳制）。</p>
 *
 * @see LineWrapStrategies
 */
@FunctionalInterface
public interface LineWrapStrategy {

    /**
     * 在字符级可容纳范围内挑选实际断点列（即当前段的结束列 /
     * 下一段的起始列）。
     *
     * @param lineText        整行文本
     * @param segmentStartCol 当前段起始列（0 起）
     * @param maxFitCol       字符级最大可容纳列（当前段最多排到此列，不含）
     * @return 实际断点列，须在 {@code (segmentStartCol, maxFitCol]} 内
     */
    int adjustBreakColumn(String lineText, int segmentStartCol, int maxFitCol);
}
