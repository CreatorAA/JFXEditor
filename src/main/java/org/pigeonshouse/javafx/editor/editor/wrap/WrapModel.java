package org.pigeonshouse.javafx.editor.editor.wrap;

import javafx.scene.text.Font;
import javafx.scene.text.Text;
import org.pigeonshouse.javafx.editor.core.document.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 软换行布局模型：把「一个文档行」映射为「若干视觉行段」，并提供
 * 视觉行 ↔ (文档行, 段号) 的双向换算。皮肤层持有唯一实例。
 *
 * <p><strong>段（segment）语义：</strong>每个文档行折成 1..N 段，段 s
 * 覆盖列区间 {@code [start_s, start_{s+1})}（末段到行尾）；每段占一个
 * 视觉行，从画布左缘（gutter 之后）起绘。关闭软换行时一律每行一段。</p>
 *
 * <p><strong>性能约束（关键）：</strong>断点按<em>字符宽度累加</em>计算，
 * 字符宽度经缓存（ASCII 直查表 + 宽字符 Map），每个字符至多实测一次；
 * 严禁在布局路径里逐行做整段像素实测。文档变更走增量重算（仅重算受影响
 * 行），仅字体/宽度/策略/开关变化才整表重建；段数前缀和按 {@value #BLOCK_SIZE}
 * 行分块聚合，视觉行换算为 O(块数 + 块内行数)。</p>
 *
 * <p><strong>线程：</strong>非线程安全，仅限 JavaFX 应用线程使用
 * （与皮肤渲染同线程，共享测量节点须同步访问）。</p>
 *
 * @see LineWrapStrategy
 */
public class WrapModel {

    /** 段数前缀和的分块大小（行）。 */
    private static final int BLOCK_SIZE = 4096;

    private final Document document;
    /** 私有字符宽度测量节点（不与皮肤共享，避免字体状态串扰）。 */
    private final Text measurer = new Text();

    private boolean enabled = false;
    private LineWrapStrategy strategy = LineWrapStrategies.WORD_BOUNDARY;
    private double wrapWidth = 0;
    private Font font;

    /** ASCII 字符宽度直查表（字体变更时失效重建）。 */
    private final double[] asciiWidth = new double[128];
    private boolean asciiReady = false;
    /** 非 ASCII 字符宽度缓存。 */
    private final Map<Character, Double> wideWidth = new HashMap<>();

    /** 每文档行的段起始列（升序，{@code [0]==0}）；{@code null} 表示单段 {@code [0,len)}。 */
    private final List<int[]> segments = new ArrayList<>();

    /** 分块前缀和：{@code blockPrefix[b]} = 第 b 块之前的视觉行数。 */
    private int[] blockPrefix = {0};
    /** 总视觉行数（软换行展开后，不含渲染层插入行）。 */
    private int totalVisual = 0;

    /** 需整表重建（字体/宽度/策略/开关变化）。 */
    private boolean layoutDirty = true;
    /** 段数已变，仅需重算前缀和。 */
    private boolean prefixDirty = true;

    /**
     * @param document 绑定文档（终身不换）
     */
    public WrapModel(Document document) {
        this.document = document;
    }

    /**
     * 同步配置：开关、策略、字体、折行像素宽度。任一相关项变化时
     * 标记整表重建（惰性，下次查询时执行）；字体变化额外清空宽度缓存。
     *
     * @param enabled   是否启用软换行
     * @param strategy  断点策略（{@code null} 归一化为默认单词边界）
     * @param font      文本字体
     * @param wrapWidth 折行可用像素宽度（画布宽 − gutter 宽）
     */
    public void configure(boolean enabled, LineWrapStrategy strategy, Font font, double wrapWidth) {
        LineWrapStrategy s = strategy != null ? strategy : LineWrapStrategies.WORD_BOUNDARY;
        boolean fontChanged = !Objects.equals(this.font, font);
        boolean changed = this.enabled != enabled
                || this.strategy != s
                || fontChanged
                || Math.abs(this.wrapWidth - wrapWidth) > 0.5;
        this.enabled = enabled;
        this.strategy = s;
        this.wrapWidth = wrapWidth;
        if (fontChanged) {
            this.font = font;
            this.measurer.setFont(font);
            this.asciiReady = false;
            this.wideWidth.clear();
        }
        if (changed) {
            layoutDirty = true;
        }
    }

    /** @return 软换行是否启用 */
    public boolean isEnabled() {
        return enabled;
    }

    /** @return 绑定文档的总行数 */
    public int lineCount() {
        return document.getLineCount();
    }

    /**
     * 文档变更回调：粗粒度变更（{@code setText}/批量，新旧文本皆空）标记
     * 整表重建；细粒度插入/删除仅平移行数组并重算受影响行 {@code [startLine,
     * startLine+max(0,lineDelta)]}，再置前缀和失效。
     *
     * @param startLine 变更起始行
     * @param lineDelta 行数增量（正增负减）
     * @param coarse    是否为粗粒度全量变更
     */
    public void onDocumentChanged(int startLine, int lineDelta, boolean coarse) {
        if (!enabled || layoutDirty) {
            return;
        }
        if (coarse) {
            layoutDirty = true;
            return;
        }
        int n = document.getLineCount();
        if (segments.size() != n - lineDelta) {
            layoutDirty = true;
            return;
        }
        if (lineDelta > 0) {
            int insertAt = Math.min(startLine + 1, segments.size());
            for (int i = 0; i < lineDelta; i++) {
                segments.add(insertAt, null);
            }
        } else if (lineDelta < 0) {
            for (int i = 0; i < -lineDelta; i++) {
                int removeAt = startLine + 1;
                if (removeAt < segments.size()) {
                    segments.remove(removeAt);
                } else {
                    break;
                }
            }
        }
        int last = Math.min(n - 1, startLine + Math.max(0, lineDelta));
        for (int line = Math.max(0, startLine); line <= last && line < segments.size(); line++) {
            segments.set(line, computeSegments(line));
        }
        prefixDirty = true;
    }

    // ---- 查询 API（均先 ensureLayout） ----

    /** @return 总视觉行数（关闭软换行时等于文档行数） */
    public int totalVisualLines() {
        if (!enabled) {
            return document.getLineCount();
        }
        ensureLayout();
        return totalVisual;
    }

    /** @return 指定文档行的段数（越界或关闭时为 1） */
    public int segmentCount(int line) {
        if (!enabled) {
            return 1;
        }
        ensureLayout();
        if (line < 0 || line >= segments.size()) {
            return 1;
        }
        return segCountRaw(line);
    }

    /** @return 段 {@code seg} 的起始列（钳制到合法范围；关闭时恒 0） */
    public int segmentStart(int line, int seg) {
        if (!enabled) {
            return 0;
        }
        ensureLayout();
        if (line < 0 || line >= segments.size()) {
            return 0;
        }
        int[] s = segments.get(line);
        if (s == null || seg <= 0) {
            return 0;
        }
        return seg >= s.length ? s[s.length - 1] : s[seg];
    }

    /** @return 段 {@code seg} 的结束列（末段到行尾；关闭时为行长；行越界时为 0） */
    public int segmentEndCol(int line, int seg) {
        if (line < 0 || line >= document.getLineCount()) {
            return 0;
        }
        int len = document.getLineLength(line);
        if (!enabled) {
            return len;
        }
        ensureLayout();
        if (line >= segments.size()) {
            return len;
        }
        int[] s = segments.get(line);
        if (s == null) {
            return len;
        }
        return seg + 1 < s.length ? s[seg + 1] : len;
    }

    /**
     * 定位列所属的段号。边界列（恰为某段起始列）归属<em>较后</em>的段，
     * 即软换行处光标显示在下一视觉行行首（约定 A）。
     *
     * @param line 文档行
     * @param col  列号
     * @return 段号（关闭时恒 0）
     */
    public int segmentOf(int line, int col) {
        if (!enabled) {
            return 0;
        }
        ensureLayout();
        if (line < 0 || line >= segments.size()) {
            return 0;
        }
        int[] s = segments.get(line);
        if (s == null) {
            return 0;
        }
        int lo = 0;
        int hi = s.length - 1;
        int ans = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (s[mid] <= col) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    /**
     * @param line 文档行
     * @return 该行首段的视觉行号（软换行展开，<strong>不含</strong>渲染层插入行）
     */
    public int firstVisualLine(int line) {
        if (!enabled) {
            return line;
        }
        ensureLayout();
        int n = segments.size();
        if (line <= 0) {
            return 0;
        }
        if (line >= n) {
            return totalVisual;
        }
        int b = line / BLOCK_SIZE;
        int acc = blockPrefix[b];
        for (int l = b * BLOCK_SIZE; l < line; l++) {
            acc += segCountRaw(l);
        }
        return acc;
    }

    /**
     * 视觉行反解为 {@code (文档行, 段号)}（软换行展开，<strong>不含</strong>
     * 渲染层插入行）；入参与结果均钳制到合法范围。
     *
     * @param visualLine 视觉行号
     * @return 长度 2 数组 {@code {docLine, segment}}
     */
    public int[] locate(int visualLine) {
        if (!enabled) {
            return new int[]{Math.max(0, visualLine), 0};
        }
        ensureLayout();
        int n = segments.size();
        if (n == 0 || visualLine <= 0) {
            return new int[]{0, 0};
        }
        if (visualLine >= totalVisual) {
            int last = n - 1;
            return new int[]{last, segCountRaw(last) - 1};
        }
        int numBlocks = (n + BLOCK_SIZE - 1) / BLOCK_SIZE;
        int b = 0;
        while (b + 1 < numBlocks && blockPrefix[b + 1] <= visualLine) {
            b++;
        }
        int acc = blockPrefix[b];
        int end = Math.min((b + 1) * BLOCK_SIZE, n);
        for (int line = b * BLOCK_SIZE; line < end; line++) {
            int sc = segCountRaw(line);
            if (acc + sc > visualLine) {
                return new int[]{line, visualLine - acc};
            }
            acc += sc;
        }
        int last = n - 1;
        return new int[]{last, segCountRaw(last) - 1};
    }

    // ---- 内部：布局构建 ----

    /** 惰性布局：整表重建 > 行数失配兜底重建 > 仅前缀和重算。 */
    private void ensureLayout() {
        if (layoutDirty) {
            rebuildAll();
            layoutDirty = false;
            prefixDirty = false;
        } else if (segments.size() != document.getLineCount()) {
            rebuildAll();
            prefixDirty = false;
        } else if (prefixDirty) {
            rebuildPrefix();
            prefixDirty = false;
        }
    }

    /** 整表重建：逐行计算段划分（字符宽度走缓存，无整段像素实测）。 */
    private void rebuildAll() {
        segments.clear();
        if (!enabled) {
            totalVisual = document.getLineCount();
            blockPrefix = new int[]{0};
            return;
        }
        ensureAscii();
        int n = document.getLineCount();
        for (int line = 0; line < n; line++) {
            segments.add(computeSegments(line));
        }
        rebuildPrefix();
    }

    /** 仅重算分块前缀和与总视觉行数。 */
    private void rebuildPrefix() {
        int n = segments.size();
        int numBlocks = (n + BLOCK_SIZE - 1) / BLOCK_SIZE;
        blockPrefix = new int[numBlocks + 1];
        int acc = 0;
        for (int b = 0; b < numBlocks; b++) {
            blockPrefix[b] = acc;
            int end = Math.min((b + 1) * BLOCK_SIZE, n);
            for (int line = b * BLOCK_SIZE; line < end; line++) {
                acc += segCountRaw(line);
            }
        }
        blockPrefix[numBlocks] = acc;
        totalVisual = acc;
    }

    /** 前置条件：{@code enabled} 且 {@code line} 合法。 */
    private int segCountRaw(int line) {
        int[] s = segments.get(line);
        return s == null ? 1 : s.length;
    }

    /**
     * 贪心计算单行的段起始列：按字符宽度累加，放不下时先得字符级
     * 可容纳边界，再交策略回退挑断点；返回 {@code null} 表示整行单段。
     */
    private int[] computeSegments(int line) {
        String text = document.getLine(line);
        int len = text == null ? 0 : text.length();
        if (wrapWidth <= 0 || len == 0) {
            return null;
        }
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        int segStart = 0;
        double acc = 0;
        int i = 0;
        while (i < len) {
            double cw = charWidth(text.charAt(i));
            if (acc + cw > wrapWidth && i > segStart) {
                int maxFit = i;
                int brk = strategy.adjustBreakColumn(text, segStart, maxFit);
                if (brk <= segStart || brk > maxFit) {
                    brk = maxFit;
                }
                starts.add(brk);
                segStart = brk;
                acc = 0;
                i = brk;
            } else {
                acc += cw;
                i++;
            }
        }
        if (starts.size() <= 1) {
            return null;
        }
        int[] result = new int[starts.size()];
        for (int k = 0; k < result.length; k++) {
            result[k] = starts.get(k);
        }
        return result;
    }

    // ---- 内部：字符宽度缓存 ----

    private double charWidth(char c) {
        if (c < 128) {
            return asciiWidth[c];
        }
        Double w = wideWidth.get(c);
        if (w == null) {
            w = measureChar(c);
            wideWidth.put(c, w);
        }
        return w;
    }

    private void ensureAscii() {
        if (asciiReady) {
            return;
        }
        for (int c = 0; c < 128; c++) {
            asciiWidth[c] = measureChar((char) c);
        }
        asciiReady = true;
    }

    private double measureChar(char c) {
        measurer.setText(String.valueOf(c));
        return measurer.getLayoutBounds().getWidth();
    }
}
