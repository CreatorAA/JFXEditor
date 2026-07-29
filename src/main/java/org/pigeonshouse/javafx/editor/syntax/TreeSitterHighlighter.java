package org.pigeonshouse.javafx.editor.syntax;

import javafx.application.Platform;
import org.pigeonshouse.javafx.editor.core.document.Document;
import org.pigeonshouse.javafx.editor.core.document.DocumentChange;
import org.pigeonshouse.javafx.editor.core.document.DocumentListener;
import org.treesitter.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于 tree-sitter 的异步增量语法高亮器。
 *
 * <p><strong>线程模型：</strong>解析与查询全部在专属单守护工作线程
 * 执行；完成通知经 {@code Platform.runLater} 投递到 FX 线程（无 FX
 * 环境时同步回退）；{@link #tokenizeLine} 在 FX 线程被引擎调用且
 * 从不阻塞——未命中缓存时先返回整行 TEXT 兜底结果，后台完成后
     经监听器刷新。乐观并发控制由 {@code parseVersion} 版本号实现，
 * 陈旧任务在多个检查点被丢弃。</p>
 *
 * <p><strong>坐标与 Unicode：</strong>全文按 UTF-16LE 字节流喂给解析器，
 * tree-sitter 返回的字节列除以 2 换算回 Java char 列（代理对占
 * 4 字节即 2 个 code unit，与 String 索引天然对齐）；增量编辑经
 * {@link DocumentChange#toTSInputEdit()} 同样乘 2 换算。</p>
 *
 * <p><strong>用法示例：</strong></p>
 * <pre>{@code
 * TreeSitterHighlighter hl = TreeSitterHighlighter.forJava();
 * editor.setHighlighter(hl);   // 内部会自动 attachTo(document)
 * // 更换/关闭时：editor.setHighlighter(null); 会自动 dispose 旧高亮器
 * }</pre>
 *
 * @see TreeSitterLanguageRegistry
 * @see AsyncSyntaxHighlighter
 */
public class TreeSitterHighlighter implements AsyncSyntaxHighlighter {

    /** 语言描述（原生绑定 + 高亮查询）。 */
    private final TreeSitterLanguage language;
    /** tree-sitter 解析器（原生资源，dispose 时关闭）。 */
    private final TSParser parser;
    /** 单守护工作线程，承担全部解析与查询。 */
    private final ExecutorService executor;
    /** 行号 → token 列表的并发缓存；查询完成后整体替换。 */
    private volatile ConcurrentHashMap<Integer, List<Token>> highlightCache;
    /** 缓存行数上限，超限按行号从小到大淘汰。 */
    private static final int MAX_CACHE_SIZE = 50000;
    /** 编译后查询的惰性缓存（原生资源）。 */
    private final AtomicReference<TSQuery> compiledQuery;
    /** 乐观并发版本号：任何新变更自增，陈旧任务被丢弃。 */
    private final AtomicInteger parseVersion;
    /** 当前语法树（原生资源，同步块内替换）。 */
    private volatile TSTree currentTree;
    /** 销毁标志，置位后所有操作降级。 */
    private volatile boolean disposed;
    /** 当前绑定的文档；未绑定时为 {@code null}。 */
    private volatile Document attachedDocument;
    /** 最近一次解析的源文本快照（供跨行 token 拆分取行长）。 */
    private volatile String lastSourceText;
    /** 文档变更监听器（绑定到 {@link #onDocumentChanged}）。 */
    private final DocumentListener documentChangeListener;
    /** 高亮更新监听器列表。 */
    private final CopyOnWriteArrayList<HighlightUpdateListener> updateListeners =
            new CopyOnWriteArrayList<>();

    /**
     * 创建指定语言的高亮器（启动专属守护工作线程）。
     *
     * @param language 语言描述，通常取自 {@link TreeSitterLanguageRegistry}
     */
    public TreeSitterHighlighter(TreeSitterLanguage language) {
        this.language = language;
        this.parser = new TSParser();
        parser.setLanguage(language.language());
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "TreeSitter-Worker-" + language.id());
            t.setDaemon(true);
            return t;
        });
        this.highlightCache = new ConcurrentHashMap<>();
        this.compiledQuery = new AtomicReference<>();
        this.parseVersion = new AtomicInteger(0);
        this.disposed = false;
        this.documentChangeListener = this::onDocumentChanged;
    }

    /**
     * 便捷工厂：从注册表取内置 Java 语言创建高亮器。
     *
     * @return Java 高亮器
     */
    public static TreeSitterHighlighter forJava() {
        return new TreeSitterHighlighter(TreeSitterLanguageRegistry.get("java"));
    }

    /** @return 本高亮器的语言描述 */
    public TreeSitterLanguage getLanguage() {
        return language;
    }

    /** 首次全量解析只触发一次的门闩。 */
    private volatile boolean initialParseDone = false;

    /**
     * {@inheritDoc}
     *
     * <p>非阻塞设计：缓存命中直接返回；未命中先放整行 TEXT 兜底
     * token，并在首次调用时调度异步全量解析；后台完成后经监听器
     * 通知刷新。</p>
     */
    @Override
    public LineTokens tokenizeLine(String lineContent, int state, int lineIndex) {
        if (disposed) {
            return LineTokens.of(List.of(new Token(0, lineContent.length(), TokenType.TEXT)), 0);
        }

        List<Token> cached = highlightCache.get(lineIndex);
        if (cached != null && !cached.isEmpty()) {
            return LineTokens.of(cached, 0);
        }

        if (lineContent.isEmpty()) {
            return LineTokens.of(List.of(new Token(0, 0, TokenType.TEXT)), 0);
        }

        List<Token> fallback = List.of(new Token(0, lineContent.length(), TokenType.TEXT));
        highlightCache.putIfAbsent(lineIndex, fallback);

        if (attachedDocument != null && !initialParseDone) {
            initialParseDone = true;
            scheduleAsyncParseAndHighlight(attachedDocument.getText());
        }

        return LineTokens.of(fallback, 0);
    }

    /** @return 恒为 {@code true}（无跨行状态，引擎跳过状态重放） */
    @Override
    public boolean isStateless() {
        return true;
    }

    /**
     * 调度一次仅解析（不跑高亮查询）的后台任务。
     *
     * @param documentText 全文快照
     */
    public void scheduleAsyncParse(String documentText) {
        if (disposed) {
            return;
        }
        final int version = parseVersion.incrementAndGet();
        executor.submit(() -> doParse(documentText, version, true));
    }

    /**
     * 调度解析加高亮查询的后台任务；成功且版本仍最新时通知监听器
     * （变更范围为全文档）。
     *
     * @param documentText 全文快照
     */
    public void scheduleAsyncParseAndHighlight(String documentText) {
        if (disposed) {
            return;
        }
        final int version = parseVersion.incrementAndGet();
        executor.submit(() -> {
            try {
                doParse(documentText, version, true);
                if (disposed || version != parseVersion.get()) {
                    return;
                }
                runHighlightQuery();
                notifyHighlightsUpdated(DocumentChange.ofLineChange(0, 0));
            } catch (Exception e) {
                System.err.println("TreeSitterHighlighter: async parse failed: " + e);
            }
        });
    }

    /** 经 Platform.runLater 投递到 FX 线程；无 FX 环境时同步回退（便于测试）。 */
    private void notifyHighlightsUpdated(DocumentChange change) {
        if (updateListeners.isEmpty()) {
            return;
        }
        try {
            Platform.runLater(() -> fireUpdate(change));
        } catch (IllegalStateException e) {
            fireUpdate(change);
        }
    }

    /** 同步广播更新事件给全部监听器。 */
    private void fireUpdate(DocumentChange change) {
        for (HighlightUpdateListener listener : updateListeners) {
            listener.highlightsUpdated(change);
        }
    }

    /** 整体替换为空缓存。 */
    public void clearCache() {
        highlightCache = new ConcurrentHashMap<>();
    }

    /**
     * 绑定文档：先解绑旧文档，版本自增、在工作线程关闭旧树、清空
     * 缓存，并注册文档监听器以驱动增量解析。
     *
     * @param document 目标文档
     */
    public void attachTo(Document document) {
        if (disposed) {
            return;
        }
        detach();
        this.attachedDocument = document;
        parseVersion.incrementAndGet();
        lastSourceText = null;
        initialParseDone = false;
        submitSafely(this::closeCurrentTree);
        clearCache();
        document.addDocumentListener(documentChangeListener);
    }

    /** 安全关闭并清空当前语法树。 */
    private synchronized void closeCurrentTree() {
        if (currentTree != null) {
            safeCloseTree(currentTree);
            currentTree = null;
        }
    }

    /** 提交任务到工作线程，线程池已关时静默忽略。 */
    private void submitSafely(Runnable task) {
        try {
            executor.submit(task);
        } catch (RejectedExecutionException ignored) {
        }
    }

    /** 解除与当前文档的绑定（移除监听器）；未绑定时无操作。 */
    public void detach() {
        if (attachedDocument != null) {
            attachedDocument.removeDocumentListener(documentChangeListener);
            attachedDocument = null;
        }
    }

    /** @return 当前已绑定文档时返回 {@code true} */
    public boolean isAttached() {
        return attachedDocument != null;
    }

    /**
     * 文档变更入口（FX 线程）：清理越界缓存行、版本自增、快照全文，
     * 在工作线程上依次执行增量编辑 → 增量解析 → 高亮查询 → 通知。
     */
    private void onDocumentChanged(DocumentChange change) {
        if (disposed) {
            return;
        }

        cleanupStaleCacheEntries();

        final int version = parseVersion.incrementAndGet();
        final String snapshotText = attachedDocument.getText();
        executor.submit(() -> {
            try {
                boolean success = applyIncrementalEdit(change, version);
                if (disposed || version != parseVersion.get()) {
                    return;
                }

                doParse(snapshotText, version, success);

                if (disposed || version != parseVersion.get()) {
                    return;
                }
                runHighlightQuery();
                notifyHighlightsUpdated(change);
            } catch (Exception e) {
                System.err.println("TreeSitterHighlighter: incremental update failed: " + e);
            }
        });
    }

    /**
     * 把变更经 {@link DocumentChange#toTSInputEdit()} 应用到旧树；
     * 失败或树不存在时返回 {@code false}（放弃复用，退化为全量解析）。
     */
    private boolean applyIncrementalEdit(DocumentChange change, int version) {
        if (disposed || version != parseVersion.get() || currentTree == null) {
            return false;
        }
        try {
            TSInputEdit inputEdit = change.toTSInputEdit();
            currentTree.edit(inputEdit);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 执行解析：可选复用旧树实现增量解析；双重检查版本号并在
     * 同步块内换树，被替换的旧树安全关闭。
     */
    private void doParse(String text, int version, boolean reuseExistingTree) {
        try {
            if (disposed || version != parseVersion.get()) {
                return;
            }
            this.lastSourceText = text;
            TSTree oldTree;
            synchronized (this) {
                if (disposed || version != parseVersion.get()) {
                    return;
                }
                oldTree = reuseExistingTree ? currentTree : null;
            }
            TSTree newTree = parseUtf16(oldTree, text);
            if (newTree == null || version != parseVersion.get()) {
                safeCloseTree(newTree);
                return;
            }
            TSTree treeToClose = null;
            boolean versionRejected = false;
            synchronized (this) {
                if (disposed || version != parseVersion.get()) {
                    versionRejected = true;
                } else {
                    treeToClose = (currentTree != null && currentTree != newTree) ? currentTree : null;
                    currentTree = newTree;
                }
            }
            if (versionRejected) {
                safeCloseTree(newTree);
                return;
            }
            if (treeToClose != null) {
                safeCloseTree(treeToClose);
            }
        } catch (Exception e) {
            System.err.println("TreeSitterHighlighter: parse failed: " + e);
        }
    }

    /** 把全文按 UTF-16LE 编码为字节流并以流式 reader 喂给解析器。 */
    private TSTree parseUtf16(TSTree oldTree, String text) {
        byte[] source = text.getBytes(StandardCharsets.UTF_16LE);
        byte[] buf = new byte[8192];
        TSReader reader = (b, offset, position) -> {
            if (offset >= source.length) {
                return 0;
            }
            int len = Math.min(b.length, source.length - offset);
            System.arraycopy(source, offset, b, 0, len);
            return len;
        };
        return parser.parse(buf, oldTree, reader, TSInputEncoding.TSInputEncodingUTF16LE);
    }

    /** 安全关闭语法树（异常静默吞没）。 */
    private static void safeCloseTree(TSTree tree) {
        if (tree != null) {
            try {
                tree.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 执行高亮查询：懒编译查询，遍历全部 capture 并经
     * {@link #mapCaptureToType} 映射为 {@link TokenType}；字节列除以 2
     * 换算为 char 列；跨行节点拆为首行尾段、中间整行、末行头段；
     * 每行 token 按起始列排序后整体替换缓存。
     */
    private void runHighlightQuery() {
        if (disposed || currentTree == null) {
            return;
        }
        try {
            TSQuery query = getOrCompileQuery();
            if (query == null) {
                return;
            }
            TSNode rootNode = currentTree.getRootNode();
            if (rootNode.isNull()) {
                return;
            }
            try (TSQueryCursor cursor = new TSQueryCursor()) {
                cursor.exec(query, rootNode);
                Map<Integer, List<Token>> newCache = new ConcurrentHashMap<>();
                String source = lastSourceText;
                String[] sourceLines = source != null ? source.split("\n", -1) : new String[0];

                TSQueryMatch match = new TSQueryMatch();
                while (cursor.nextMatch(match)) {
                    for (TSQueryCapture capture : match.getCaptures()) {
                        TSNode node = capture.getNode();
                        TSPoint start = node.getStartPoint();
                        TSPoint end = node.getEndPoint();
                        int startLine = start.getRow();
                        int endLine = end.getRow();
                        int startCol = (int) (start.getColumn() / 2);
                        int endCol = (int) (end.getColumn() / 2);
                        String captureName = query.getCaptureNameForId(capture.getIndex());
                        TokenType type = mapCaptureToType(captureName);
                        if (type == TokenType.TEXT) continue;

                        if (endLine == startLine) {
                            int length = endCol - startCol;
                            if (length > 0) {
                                Token token = new Token(startCol, length, type);
                                newCache.computeIfAbsent(startLine, k -> new ArrayList<>()).add(token);
                            }
                        } else {
                            int firstLineLength = getSafeLineLength(sourceLines, startLine);
                            int firstTokenLength = firstLineLength - startCol;
                            if (firstTokenLength > 0) {
                                Token firstToken = new Token(startCol, firstTokenLength, type);
                                newCache.computeIfAbsent(startLine, k -> new ArrayList<>()).add(firstToken);
                            }
                            for (int midLine = startLine + 1; midLine < endLine; midLine++) {
                                int midLineLength = getSafeLineLength(sourceLines, midLine);
                                if (midLineLength > 0) {
                                    Token midToken = new Token(0, midLineLength, type);
                                    newCache.computeIfAbsent(midLine, k -> new ArrayList<>()).add(midToken);
                                }
                            }
                            if (endCol > 0) {
                                Token lastToken = new Token(0, endCol, type);
                                newCache.computeIfAbsent(endLine, k -> new ArrayList<>()).add(lastToken);
                            }
                        }
                    }
                }

                for (List<Token> tokenList : newCache.values()) {
                    tokenList.sort(Comparator.comparingInt(Token::start));
                }

                highlightCache = new ConcurrentHashMap<>(newCache);

                enforceCacheSizeLimit();
            }
        } catch (Exception e) {
            System.err.println("TreeSitterHighlighter: highlight query failed: " + e);
        }
    }

    /** 取行长：优先用源码快照按换行切分，越界时回退查文档。 */
    private int getSafeLineLength(String[] sourceLines, int lineIndex) {
        if (lineIndex >= 0 && lineIndex < sourceLines.length) {
            return sourceLines[lineIndex].length();
        }
        Document doc = attachedDocument;
        if (doc != null && lineIndex >= 0 && lineIndex < doc.getLineCount()) {
            return doc.getLineLength(lineIndex);
        }
        return 0;
    }

    /** 清理超出当前文档行数的陈旧缓存条目。 */
    private void cleanupStaleCacheEntries() {
        if (attachedDocument == null || highlightCache.isEmpty()) return;

        int lineCount = attachedDocument.getLineCount();
        highlightCache.entrySet().removeIf(entry -> entry.getKey() >= lineCount);
    }

    /** 超过 {@value #MAX_CACHE_SIZE} 行时按行号从小到大淘汰。 */
    private void enforceCacheSizeLimit() {
        if (highlightCache.size() <= MAX_CACHE_SIZE) return;

        List<Integer> keys = new ArrayList<>(highlightCache.keySet());
        Collections.sort(keys);

        int toRemove = highlightCache.size() - MAX_CACHE_SIZE;
        for (int i = 0; i < toRemove && i < keys.size(); i++) {
            highlightCache.remove(keys.get(i));
        }
    }

    /** 懒编译高亮查询；编译失败返回 {@code null} 静默跳过。 */
    private TSQuery getOrCompileQuery() {
        TSQuery query = compiledQuery.get();
        if (query == null) {
            try {
                query = new TSQuery(language.language(), language.highlightQuery());
                compiledQuery.set(query);
            } catch (TSQueryException e) {
                return null;
            }
        }
        return query;
    }

    /** 把 tree-sitter capture 名映射到 {@link TokenType}；未识别名返回 TEXT（调用方跳过）。 */
    private TokenType mapCaptureToType(String captureName) {
        if (captureName == null) {
            return TokenType.TEXT;
        }
        return switch (captureName) {
            case "keyword" -> TokenType.KEYWORD;
            case "comment" -> TokenType.COMMENT;
            case "string" -> TokenType.STRING;
            case "number" -> TokenType.NUMBER;
            case "type" -> TokenType.TYPE;
            case "function", "function.call" -> TokenType.FUNCTION;
            case "function.macro" -> TokenType.FUNCTION;
            case "variable" -> TokenType.VARIABLE;
            case "variable.builtin" -> TokenType.VARIABLE_BUILTIN;
            case "constant" -> TokenType.CONSTANT;
            case "punctuation" -> TokenType.PUNCTUATION;
            case "operator" -> TokenType.OPERATOR;
            case "annotation" -> TokenType.ANNOTATION;
            case "json.key" -> TokenType.PROPERTY;
            case "tag" -> TokenType.TYPE;
            default -> TokenType.TEXT;
        };
    }

    /**
     * 释放全部资源：置销毁标志、解绑、版本自增使在途任务失效；
     * 优先在工作线程释放原生资源，随后关停线程池（2 秒等待 +
     * 强制关停 + 500ms 兜底），失败则在当前线程兜底释放。
     */
    public void dispose() {
        disposed = true;
        detach();
        parseVersion.incrementAndGet();

        boolean releasedOnWorker = false;
        try {
            executor.submit(this::releaseNativeResources);
            releasedOnWorker = true;
        } catch (RejectedExecutionException ignored) {
        }

        try {
            executor.shutdown();
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    System.err.println("Warning: TreeSitterHighlighter executor did not terminate in time");
                }
                releasedOnWorker = false;
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            releasedOnWorker = false;
        }

        if (!releasedOnWorker) {
            releaseNativeResources();
        }
        clearCache();
    }

    /** 释放原生资源：语法树、编译查询与解析器。 */
    private synchronized void releaseNativeResources() {
        if (currentTree != null) {
            safeCloseTree(currentTree);
            currentTree = null;
        }
        TSQuery q = compiledQuery.getAndSet(null);
        if (q != null) {
            try {
                q.close();
            } catch (Exception ignored) {
            }
        }
        try {
            parser.close();
        } catch (Exception ignored) {
        }
    }

    /** {@inheritDoc} */
    @Override
    public void addUpdateListener(HighlightUpdateListener listener) {
        updateListeners.add(java.util.Objects.requireNonNull(listener, "listener 不能为 null"));
    }

    /** {@inheritDoc} */
    @Override
    public void removeUpdateListener(HighlightUpdateListener listener) {
        updateListeners.remove(listener);
    }
}