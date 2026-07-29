package org.pigeonshouse.javafx.editor.search;

import org.pigeonshouse.javafx.editor.core.document.Document;
import org.pigeonshouse.javafx.editor.core.document.DocumentListener;
import org.pigeonshouse.javafx.editor.core.model.TextRange;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 文档搜索引擎：逐行正则匹配，支持大小写/全词/正则选项、
 * 渐进式视口优先搜索、环绕导航与替换。
 *
 * <p><strong>选项：</strong>非正则模式下关键字经 {@code Pattern.quote}
 * 转义；全词模式包裹词边界；非大小写敏感时附加
 * {@code CASE_INSENSITIVE | UNICODE_CASE}。零长匹配自动跳过，
 * 非法正则静默返回空结果。</p>
 *
 * <p><strong>文档联动：</strong>注册 {@link DocumentListener} 监听变更，
 * 外部编辑会自动失效结果；自身替换操作通过 {@code internalEdit}
 * 标志避免递归失效。不再使用时应调用 {@link #dispose()}。</p>
 *
 * <p><strong>用法示例（搜索并遍历结果）：</strong></p>
 * <pre>{@code
 * SearchEngine engine = new SearchEngine(document);
 * engine.setSearchTerm("TODO");
 * engine.setWholeWord(true);
 *
 * // 方式一：全量同步搜索
 * List<SearchResult> all = engine.search();
 *
 * // 方式二：视口优先的渐进式搜索（大文档推荐）
 * engine.searchFromViewport(firstVisibleLine, lastVisibleLine);
 * while (!engine.continueSearch(500)) { }   // 每批 500 行
 *
 * SearchResult next = engine.findNext(caretLine, caretCol); // 到尾环绕
 * engine.replaceCurrent("DONE");                            // 替换当前项
 * int n = engine.replaceAll("DONE");                        // 批量替换
 * engine.dispose();
 * }</pre>
 *
 * @see SearchResult
 */
public class SearchEngine {

    /** 数据源文档。 */
    private final Document document;
    /** 文档变更监听器：外部编辑时失效结果。 */
    private final DocumentListener documentListener;
    /** 自身替换操作的内部编辑标志，避免递归失效。 */
    private boolean internalEdit;

    /** 当前搜索关键字（永不为 {@code null}）。 */
    private String searchTerm = "";
    /** 大小写敏感开关（默认 false）。 */
    private boolean caseSensitive = false;
    /** 全词匹配开关（默认 false）。 */
    private boolean wholeWord = false;
    /** 正则模式开关（默认 false，关闭时关键字会被转义）。 */
    private boolean useRegex = false;

    /** 当前结果列表（按行/列有序，渐进搜索完成前可能乱序）。 */
    private List<SearchResult> results = Collections.emptyList();
    /** 当前选中结果的下标；-1 表示无。 */
    private int currentIndex = -1;

    /** 搜索结果监听器列表。 */
    private final List<SearchListener> listeners = new ArrayList<>();

    /** 搜索结果/当前索引变化监听器。 */
    public interface SearchListener {
        /**
         * 结果或当前索引变化时回调。
         *
         * @param results      结果快照（不可修改）
         * @param currentIndex 当前选中下标；-1 表示无
         */
        void searchResultsChanged(List<SearchResult> results, int currentIndex);
    }

    /**
     * 创建搜索引擎并注册文档变更监听。
     *
     * @param document 数据源文档
     */
    public SearchEngine(Document document) {
        this.document = document;
        this.documentListener = change -> {
            if (!internalEdit) {
                invalidateResults();
            }
        };
        document.addDocumentListener(documentListener);
    }

    /** 释放资源：反注册文档监听并清空监听器。 */
    public void dispose() {
        document.removeDocumentListener(documentListener);
        listeners.clear();
    }

    /** 文档变更后失效全部结果并广播。 */
    private void invalidateResults() {
        results = Collections.emptyList();
        currentIndex = -1;
        progressiveComplete = true;
        fireResultsChanged();
    }


    /** @return 当前搜索关键字（永不为 {@code null}） */
    public String getSearchTerm() {
        return searchTerm;
    }

    /** @param searchTerm 新关键字；{@code null} 视为空串（需重新 search 生效） */
    public void setSearchTerm(String searchTerm) {
        this.searchTerm = searchTerm != null ? searchTerm : "";
    }

    /** @return 是否大小写敏感 */
    public boolean isCaseSensitive() {
        return caseSensitive;
    }

    /** @param caseSensitive 是否大小写敏感（需重新 search 生效） */
    public void setCaseSensitive(boolean caseSensitive) {
        this.caseSensitive = caseSensitive;
    }

    /** @return 是否全词匹配 */
    public boolean isWholeWord() {
        return wholeWord;
    }

    /** @param wholeWord 是否全词匹配（需重新 search 生效） */
    public void setWholeWord(boolean wholeWord) {
        this.wholeWord = wholeWord;
    }

    /** @return 是否正则模式 */
    public boolean isUseRegex() {
        return useRegex;
    }

    /** @param useRegex 是否正则模式（关闭时关键字会被转义；需重新 search 生效） */
    public void setUseRegex(boolean useRegex) {
        this.useRegex = useRegex;
    }


    /**
     * 全文同步搜索：逐行匹配，结果有序，首个结果设为当前项。
     *
     * @return 结果的不可修改视图；关键字为空或正则非法时为空列表
     */
    public List<SearchResult> search() {
        results = new ArrayList<>();
        currentIndex = -1;

        if (searchTerm.isEmpty()) {
            fireResultsChanged();
            return results;
        }

        try {
            Pattern pattern = buildPattern();
            for (int lineIdx = 0; lineIdx < document.getLineCount(); lineIdx++) {
                collectMatches(pattern, document.getLine(lineIdx), lineIdx, results);
            }
        } catch (PatternSyntaxException e) {
            results = new ArrayList<>();
        }

        if (!results.isEmpty()) {
            currentIndex = 0;
        }

        fireResultsChanged();
        return Collections.unmodifiableList(results);
    }

    /** 收集单行内全部匹配，零长匹配前进一格跳过。 */
    private static void collectMatches(Pattern pattern, String lineText, int lineIdx, List<SearchResult> out) {
        if (lineText == null || lineText.isEmpty()) {
            return;
        }
        Matcher matcher = pattern.matcher(lineText);
        int searchFrom = 0;
        while (searchFrom <= lineText.length() && matcher.find(searchFrom)) {
            if (matcher.end() == matcher.start()) {
                searchFrom = matcher.start() + 1;
                continue;
            }
            out.add(new SearchResult(lineIdx, matcher.start(), matcher.end(), matcher.group()));
            searchFrom = matcher.end();
        }
    }

    /**
     * 在指定行区间内搜索（不影响主结果集与当前索引）。
     *
     * @param startLine 起始行（含，自动钳制）
     * @param endLine   结束行（含，自动钳制）
     * @return 区间内的匹配列表
     */
    public List<SearchResult> searchInRange(int startLine, int endLine) {
        List<SearchResult> rangeResults = new ArrayList<>();

        if (searchTerm.isEmpty()) return rangeResults;

        try {
            Pattern pattern = buildPattern();
            for (int lineIdx = Math.max(0, startLine); lineIdx <= Math.min(endLine, document.getLineCount() - 1); lineIdx++) {
                collectMatches(pattern, document.getLine(lineIdx), lineIdx, rangeResults);
            }
        } catch (PatternSyntaxException e) {
        }

        return rangeResults;
    }

    /**
     * 渐进式搜索起点：先搜视口附近区域（缓冲 = max(100, 视口高度)）
     * 并立即返回初始结果，余下行通过 {@link #continueSearch(int)}
     * 分批完成。
     *
     * @param viewportStartLine 视口首行
     * @param viewportEndLine   视口末行
     * @return 初始结果的不可修改视图
     */
    public List<SearchResult> searchFromViewport(int viewportStartLine, int viewportEndLine) {
        results = new ArrayList<>();
        currentIndex = -1;
        progressiveComplete = false;

        if (searchTerm.isEmpty()) {
            progressiveComplete = true;
            fireResultsChanged();
            return results;
        }

        int buffer = Math.max(100, (viewportEndLine - viewportStartLine));
        int searchStart = Math.max(0, viewportStartLine - buffer);
        int searchEnd = Math.min(document.getLineCount() - 1, viewportEndLine + buffer);

        try {
            Pattern pattern = buildPattern();
            for (int lineIdx = searchStart; lineIdx <= searchEnd; lineIdx++) {
                collectMatches(pattern, document.getLine(lineIdx), lineIdx, results);
            }
        } catch (PatternSyntaxException e) {
            results = new ArrayList<>();
        }

        progressiveLine = searchEnd + 1;
        frontRegionEnd = searchStart;
        frontLine = 0;
        if (progressiveLine >= document.getLineCount() && frontLine >= frontRegionEnd) {
            progressiveComplete = true;
        }

        if (!results.isEmpty()) {
            currentIndex = 0;
        }

        fireResultsChanged();
        return Collections.unmodifiableList(results);
    }

    /** 渐进搜索：视口后方的下一行。 */
    private int progressiveLine = 0;
    /** 渐进搜索：前置区域的下一行。 */
    private int frontLine = 0;
    /** 渐进搜索：前置区域的结束行（不含）。 */
    private int frontRegionEnd = 0;
    /** 渐进搜索是否已完成。 */
    private boolean progressiveComplete = true;

    /**
     * 继续渐进式搜索：每次处理 {@code batchSize} 行（先后方区域再
     * 前置区域）；完成时对全部结果排序并保持当前项不变。
     *
     * @param batchSize 本批处理行数
     * @return 搜索已全部完成时返回 {@code true}
     */
    public boolean continueSearch(int batchSize) {
        if (progressiveComplete || searchTerm.isEmpty()) {
            return true;
        }

        try {
            Pattern pattern = buildPattern();
            int budget = batchSize;

            int lineCount = document.getLineCount();
            while (budget > 0 && progressiveLine < lineCount) {
                collectMatches(pattern, document.getLine(progressiveLine), progressiveLine, results);
                progressiveLine++;
                budget--;
            }

            while (budget > 0 && frontLine < frontRegionEnd) {
                collectMatches(pattern, document.getLine(frontLine), frontLine, results);
                frontLine++;
                budget--;
            }
        } catch (PatternSyntaxException e) {
            progressiveComplete = true;
        }

        if (progressiveLine >= document.getLineCount() && frontLine >= frontRegionEnd) {
            progressiveComplete = true;
            finishProgressiveSearch();
        }

        if (!results.isEmpty() && currentIndex < 0) {
            currentIndex = 0;
        }

        fireResultsChanged();
        return progressiveComplete;
    }

    /** 渐进搜索完成：按行/列排序全部结果并修正当前索引。 */
    private void finishProgressiveSearch() {
        SearchResult current = getCurrentResult();
        results.sort(Comparator.comparingInt(SearchResult::line)
                .thenComparingInt(SearchResult::startCol));
        if (current != null) {
            currentIndex = results.indexOf(current);
        }
    }

    /** @return 渐进式搜索已完成时返回 {@code true} */
    public boolean isProgressiveSearchComplete() {
        return progressiveComplete;
    }

    /**
     * 从指定位置向后查找下一个匹配，到尾部后环绕到首部。
     *
     * @param fromLine 起点行
     * @param fromCol  起点列（严格大于此位置的才算下一个）
     * @return 下一个匹配；无结果时返回 {@code null}
     */
    public SearchResult findNext(int fromLine, int fromCol) {
        if (results.isEmpty()) return null;

        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            if (r.line() > fromLine || (r.line() == fromLine && r.startCol() > fromCol)) {
                currentIndex = i;
                fireResultsChanged();
                return r;
            }
        }

        currentIndex = 0;
        fireResultsChanged();
        return results.getFirst();
    }

    /**
     * 从指定位置向前查找上一个匹配，到首部后环绕到尾部。
     *
     * @param fromLine 起点行
     * @param fromCol  起点列（严格小于此位置的才算上一个）
     * @return 上一个匹配；无结果时返回 {@code null}
     */
    public SearchResult findPrevious(int fromLine, int fromCol) {
        if (results.isEmpty()) return null;

        for (int i = results.size() - 1; i >= 0; i--) {
            SearchResult r = results.get(i);
            if (r.line() < fromLine || (r.line() == fromLine && r.startCol() < fromCol)) {
                currentIndex = i;
                fireResultsChanged();
                return r;
            }
        }

        currentIndex = results.size() - 1;
        fireResultsChanged();
        return results.getLast();
    }


    /**
     * 替换当前匹配项：内部标记 {@code internalEdit} 避免递归失效，
     * 替换后重新 {@link #search()}。
     *
     * @param replacement 替换文本
     * @return 插入结果区间；无当前项时返回 {@code null}
     */
    public TextRange replaceCurrent(String replacement) {
        if (currentIndex < 0 || currentIndex >= results.size()) return null;

        SearchResult current = results.get(currentIndex);
        TextRange sel = current.toTextRange();
        internalEdit = true;
        TextRange result;
        try {
            document.delete(sel);
            result = document.insert(current.line(), current.startCol(), replacement);
        } finally {
            internalEdit = false;
        }

        search();
        return result;
    }

    /**
     * 替换全部匹配项：从后往前替换（避免位移问题），包裹在
     * {@code beginBatch}/{@code endBatch} 中，完成后重新 {@link #search()}。
     *
     * @param replacement 替换文本
     * @return 替换的匹配数
     */
    public int replaceAll(String replacement) {
        if (results.isEmpty()) return 0;

        int count = results.size();
        internalEdit = true;
        try {
            document.beginBatch();

            for (int i = results.size() - 1; i >= 0; i--) {
                SearchResult r = results.get(i);
                TextRange sel = r.toTextRange();
                document.delete(sel);
                document.insert(r.line(), r.startCol(), replacement);
            }

            document.endBatch();
        } finally {
            internalEdit = false;
        }

        search();
        return count;
    }


    /** @return 当前结果的不可修改视图 */
    public List<SearchResult> getResults() {
        return Collections.unmodifiableList(results);
    }

    /** @return 当前结果数 */
    public int getResultCount() {
        return results.size();
    }

    /** @return 当前选中下标；-1 表示无 */
    public int getCurrentIndex() {
        return currentIndex;
    }

    /** @return 当前选中的结果；无时返回 {@code null} */
    public SearchResult getCurrentResult() {
        if (currentIndex >= 0 && currentIndex < results.size()) {
            return results.get(currentIndex);
        }
        return null;
    }

    /** 清空关键字与结果并广播。 */
    public void clear() {
        searchTerm = "";
        results = Collections.emptyList();
        currentIndex = -1;
        fireResultsChanged();
    }


    /**
     * 注册结果变化监听器。
     *
     * @param listener 监听器
     */
    public void addListener(SearchListener listener) {
        listeners.add(listener);
    }

    /**
     * 移除已注册的监听器；未注册时静默忽略。
     *
     * @param listener 待移除的监听器
     */
    public void removeListener(SearchListener listener) {
        listeners.remove(listener);
    }

    /** 以不可修改快照广播结果与当前索引。 */
    private void fireResultsChanged() {
        List<SearchResult> snapshot = Collections.unmodifiableList(results);
        for (SearchListener listener : listeners) {
            listener.searchResultsChanged(snapshot, currentIndex);
        }
    }


    /**
     * 构建最终正则：非正则模式用 {@code Pattern.quote} 转义，
     * 全词模式包裹词边界，非大小写敏感附加 UNICODE_CASE。
     */
    private Pattern buildPattern() {
        String regex;
        if (useRegex) {
            regex = searchTerm;
        } else {
            regex = Pattern.quote(searchTerm);
        }

        if (wholeWord) {
            regex = "\\b(?:" + regex + ")\\b";
        }

        int flags = 0;
        if (!caseSensitive) {
            flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        }

        return Pattern.compile(regex, flags);
    }
}
