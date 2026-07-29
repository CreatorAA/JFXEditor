package org.pigeonshouse.javafx.editor.syntax;

import org.pigeonshouse.javafx.editor.core.document.Document;
import org.pigeonshouse.javafx.editor.core.document.DocumentChange;
import org.pigeonshouse.javafx.editor.core.document.DocumentListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 高亮引擎：文档与高亮器之间的桥梁，提供行级 token 缓存与
 * checkpoint 增量重算。
 *
 * <p><strong>增量机制：</strong>每 {@value #CHECKPOINT_INTERVAL} 行保存
 * 一个行尾状态 checkpoint；未命中缓存时从最近的已缓存行或
 * checkpoint 连续重算，单次回溯上限 {@value #MAX_RECOMPUTE_RANGE} 行
 * （超限时强制截断，可能局部不精确但保证开销上限）；无状态
 * 高亮器直接单行计算。</p>
 *
 * <p><strong>事件链：</strong>作为 {@link DocumentListener} 监听文档变更
 * 并从变更行失效缓存；若高亮器为 {@link AsyncSyntaxHighlighter}，
 * 异步完成事件经桥接监听失效缓存后转发给上层（如 Skin 重绘）。
 * 主题可经 {@link #setTheme} 热替换，无需失效 token 缓存。</p>
 *
 * <p><strong>线程：</strong>本类无同步，设计上在 JavaFX 应用线程使用；
 * 异步高亮器的完成回调已经 {@code Platform.runLater} 回到 FX 线程。</p>
 *
 * @see SyntaxHighlighter
 * @see HighlightTheme
 */
public class HighlightEngine implements DocumentListener {

    /** checkpoint 间隔（行）：每 64 行保存一次行尾状态。 */
    private static final int CHECKPOINT_INTERVAL = 64;
    /** 单次重算的最大回溯行数。 */
    private static final int MAX_RECOMPUTE_RANGE = 200;

    /** 数据源文档。 */
    private final Document document;
    /** 实际执行分词的高亮器。 */
    private final SyntaxHighlighter highlighter;
    /** 当前主题（volatile 支持热替换）。 */
    private volatile HighlightTheme theme;
    /** 行级缓存，与文档行数等长；null 元素表示未计算。 */
    private final List<LineTokens> lineCache;
    /** 行号（64 的倍数）→ 该行结束状态；失效时清除尾部条目。 */
    private final TreeMap<Integer, Integer> checkpoints;
    /** 构造时缓存的无状态标志。 */
    private final boolean stateless;
    /** 异步高亮完成事件的上层监听器列表。 */
    private final java.util.concurrent.CopyOnWriteArrayList<HighlightUpdateListener> updateListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    /** 绑定到自身处理方法的异步桥接监听器。 */
    private final HighlightUpdateListener asyncBridge = this::onAsyncHighlightsUpdated;

    /**
     * 以极简主题创建引擎。
     *
     * @param document    数据源文档
     * @param highlighter 语法高亮器
     */
    public HighlightEngine(Document document, SyntaxHighlighter highlighter) {
        this(document, highlighter, HighlightTheme.plain());
    }

    /**
     * 创建引擎：注册为文档监听器，异步高亮器时挂接桥接监听，
     * 并按行数初始化空缓存。
     *
     * @param document    数据源文档
     * @param highlighter 语法高亮器
     * @param theme       初始主题
     */
    public HighlightEngine(Document document, SyntaxHighlighter highlighter, HighlightTheme theme) {
        this.document = document;
        this.highlighter = highlighter;
        this.theme = theme;
        this.lineCache = new ArrayList<>();
        this.checkpoints = new TreeMap<>();
        this.stateless = highlighter.isStateless();
        document.addDocumentListener(this);
        if (highlighter instanceof AsyncSyntaxHighlighter async) {
            async.addUpdateListener(asyncBridge);
        }
        initializeCache();
    }

    /** 异步高亮完成：先从变更行失效缓存，再广播给上层监听器。 */
    private void onAsyncHighlightsUpdated(DocumentChange change) {
        invalidateFrom(change.startLine());
        for (HighlightUpdateListener listener : updateListeners) {
            listener.highlightsUpdated(change);
        }
    }

    /**
     * 注册异步高亮完成监听器（典型用途：触发重绘）。
     *
     * @param listener 监听器，不可为 {@code null}
     */
    public void addUpdateListener(HighlightUpdateListener listener) {
        updateListeners.add(java.util.Objects.requireNonNull(listener, "listener 不能为 null"));
    }

    /**
     * 移除已注册的监听器；未注册时静默忽略。
     *
     * @param listener 待移除的监听器
     */
    public void removeUpdateListener(HighlightUpdateListener listener) {
        updateListeners.remove(listener);
    }

    /**
     * 获取指定行的 token 列表（命中缓存直接返回，否则惰性计算）。
     *
     * @param lineIndex 行号（0 起）；越界时返回含单个零长 TEXT token
     *                  的列表（不抛异常）
     * @return 该行 token 列表
     */
    public List<Token> getTokens(int lineIndex) {
        if (lineIndex < 0 || lineIndex >= lineCache.size()) {
            return List.of(new Token(0, 0, TokenType.TEXT));
        }
        LineTokens cached = lineCache.get(lineIndex);
        if (cached != null) {
            return cached.tokens();
        }
        computeLine(lineIndex);
        LineTokens result = lineCache.get(lineIndex);
        return result != null ? result.tokens() : List.of(new Token(0, 0, TokenType.TEXT));
    }

    /**
     * 经当前主题查找 token 类型的样式（含基础名回退）。
     *
     * @param type token 类型
     * @return 命中的样式，永不为 {@code null}
     */
    public HighlightStyle getStyle(TokenType type) {
        return theme.getStyle(type);
    }

    /** @return 当前主题 */
    public HighlightTheme getTheme() {
        return theme;
    }

    /**
     * 热替换主题（无需失效 token 缓存）。
     *
     * @param theme 新主题，不可为 {@code null}
     * @throws NullPointerException 传入 {@code null} 时
     */
    public void setTheme(HighlightTheme theme) {
        this.theme = java.util.Objects.requireNonNull(theme, "theme 不能为 null");
    }

    /**
     * 从指定行起失效缓存：该行及之后置 null，并清除尾部 checkpoint。
     *
     * @param startLine 起始行（负数钳到 0）
     */
    public void invalidateFrom(int startLine) {
        if (startLine < 0) {
            startLine = 0;
        }
        if (startLine >= lineCache.size()) {
            return;
        }
        for (int i = startLine; i < lineCache.size(); i++) {
            lineCache.set(i, null);
        }
        checkpoints.tailMap(startLine).clear();
    }

    /** 全量失效缓存。 */
    public void invalidateAll() {
        invalidateFrom(0);
    }

    /** {@inheritDoc} 先把缓存长度对齐到新行数，再从变更起始行失效。 */
    @Override
    public void documentChanged(DocumentChange change) {
        int lineCount = document.getLineCount();
        while (lineCache.size() > lineCount) {
            lineCache.removeLast();
        }
        while (lineCache.size() < lineCount) {
            lineCache.add(null);
        }

        invalidateFrom(change.startLine());
    }

    /** 释放资源：反注册文档与异步监听，清空全部集合。 */
    public void dispose() {
        document.removeDocumentListener(this);
        if (highlighter instanceof AsyncSyntaxHighlighter async) {
            async.removeUpdateListener(asyncBridge);
        }
        updateListeners.clear();
        lineCache.clear();
        checkpoints.clear();
    }

    /** 按当前文档行数填充空缓存。 */
    private void initializeCache() {
        int lineCount = document.getLineCount();
        for (int i = 0; i < lineCount; i++) {
            lineCache.add(null);
        }
    }

    /** 二分查找不大于目标行的最近 checkpoint 行号；无时返回 -1。 */
    private int findClosestCheckpoint(int line) {
        if (checkpoints.isEmpty()) {
            return -1;
        }
        Map.Entry<Integer, Integer> floor = checkpoints.floorEntry(line);
        return floor != null ? floor.getKey() : -1;
    }

    /**
     * 在目标行前 {@value #MAX_RECOMPUTE_RANGE} 行窗口内找最近的已缓存
     * 行，并以最近 checkpoint 作为保底起点。
     */
    private int findNearestCachedLine(int targetLine) {
        int bestLine = findClosestCheckpoint(targetLine);

        for (int i = Math.max(0, targetLine - MAX_RECOMPUTE_RANGE);
             i < targetLine && i < lineCache.size(); i++) {
            if (lineCache.get(i) != null) {
                bestLine = i;
            }
        }

        return bestLine;
    }

    /**
     * 增量计算目标行：从最近有效起点逐行 tokenize 到目标行，
     * 传递行尾状态并在 64 行边界写入 checkpoint；起点距目标超过
     * {@value #MAX_RECOMPUTE_RANGE} 行时强制截断（优先用最近
     * checkpoint 状态，否则退化为初始状态）。
     */
    private void computeLine(int lineIndex) {
        if (stateless) {
            computeLineStateless(lineIndex);
            return;
        }

        int startLine = findNearestCachedLine(lineIndex);
        int state;

        if (startLine >= 0) {
            LineTokens cached = lineCache.get(startLine);
            state = cached != null ? cached.endState() : checkpoints.getOrDefault(startLine, highlighter.getInitialState());
            startLine++;

            if (lineIndex - startLine > MAX_RECOMPUTE_RANGE) {
                startLine = Math.max(0, lineIndex - MAX_RECOMPUTE_RANGE);
                state = highlighter.getInitialState();

                int checkpointLine = findClosestCheckpoint(startLine);
                if (checkpointLine >= 0) {
                    startLine = checkpointLine + 1;
                    state = checkpoints.get(checkpointLine);
                }
            }
        } else {
            startLine = 0;
            state = highlighter.getInitialState();
        }

        for (int i = startLine; i <= lineIndex && i < document.getLineCount(); i++) {
            String lineContent = document.getLine(i);
            LineTokens lineTokens = highlighter.tokenizeLine(lineContent, state, i);
            lineCache.set(i, lineTokens);
            state = lineTokens.endState();

            if (i > 0 && i % CHECKPOINT_INTERVAL == 0) {
                checkpoints.put(i, state);
            }
        }
    }

    /** 无状态路径：state 固定传 0，单行独立计算。 */
    private void computeLineStateless(int lineIndex) {
        if (lineIndex < 0 || lineIndex >= document.getLineCount()) return;
        String lineContent = document.getLine(lineIndex);
        LineTokens lineTokens = highlighter.tokenizeLine(lineContent, 0, lineIndex);
        lineCache.set(lineIndex, lineTokens);
    }
}
