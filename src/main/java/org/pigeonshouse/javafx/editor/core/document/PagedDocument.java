package org.pigeonshouse.javafx.editor.core.document;

import org.pigeonshouse.javafx.editor.core.model.Position;
import org.pigeonshouse.javafx.editor.core.model.TextRange;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link Document} 的分页文件实现：块按行分割文档，
 * {@link #openFile} 只扫描元数据，行内容按需从文件同步加载；
 * clean 块超过缓存上限时按 LRU 驱逐，dirty 块驻留内存受保护。
 *
 * <p><strong>字符模型（关键）：</strong>块字符数统一按“每行拥有一个
 * 虚拟尾 LF”计（{@code sum(行长) + 行数}），文档真实字符数为前缀和
 * 减一——由此全局偏移换算无需对末块做特判。</p>
 *
 * <p><strong>契约对齐：</strong>与 {@link MemoryDocument} 完全一致——
 * 空文档 0 行、写入统一 LF 规范化、读路径钳制/写路径抛越界、
 * 批量仅聚合事件不合并撤销单元；撤销复用同一套
 * {@link UndoManager}/{@link EditCommand}（经 {@link ReplayableDocument}），
 * 无平行实现。</p>
 *
 * <p><strong>注意：</strong>{@link #getText()} 物化全文，对大文件昂贵；
 * 文件 IO 为同步读取（每次独立开闭句柄），{@link #close()} 仅释放
 * 内存缓存。所有编辑操作应在 JavaFX 应用线程串行调用。</p>
 *
 * @see MemoryDocument
 */
public class PagedDocument implements ReplayableDocument, AutoCloseable {

    /** 默认每块行数。 */
    private static final int DEFAULT_BLOCK_SIZE = 256;
    /** 默认最大缓存块数（仅约束可驱逐的 clean 已加载块）。 */
    private static final int DEFAULT_MAX_CACHED_BLOCKS = 16;
    /** 每块行数下限。 */
    private static final int MIN_BLOCK_SIZE = 16;
    /** 缓存块数下限。 */
    private static final int MIN_CACHED_BLOCKS = 2;

    /**
     * 行块：文档行的连续分片。
     *
     * <p>{@code lines == null} 表示未加载，此时必须可按
     * {@code byteOffset/byteLength} 从关联文件重读（reloadable）；
     * 编辑后转 dirty 且不可重载（内存为唯一数据源），保存后恢复
     * clean 可驱逐。</p>
     */
    private static final class Block {
        /** 关联文件内的起始字节偏移（仅 reloadable 时有效）。 */
        long byteOffset;
        /** 关联文件内的字节长度（仅 reloadable 时有效）。 */
        int byteLength;
        /** 块内行数。 */
        int lineCount;
        /** 块字符数：sum(行长) + 行数（虚拟尾 LF 模型）。 */
        int charCount;
        /** 块内最长行长度（文档级最长行按块聚合）。 */
        int maxLineLength;
        /** 行内容；{@code null} 表示未加载。 */
        List<String> lines;
        /** 是否被编辑过（驻留内存、免驱逐、保存时从内存写出）。 */
        boolean dirty;
        /** 是否可从关联文件按字节区间重载。 */
        boolean reloadable;
        /** LRU 访问时钟（越大越新）。 */
        long lastAccess;
    }

    /** 每块行数（构造时钳制，之后不变）。 */
    private final int blockSize;
    /** 最大缓存块数（仅约束可驱逐的 clean 已加载块）。 */
    private int maxCachedBlocks;
    /** 全部行块，按文档顺序排列；空文档时为空列表。 */
    private final List<Block> blocks;
    /** 撤销/重做双栈管理器（与 MemoryDocument 共享实现）。 */
    private final UndoManager undoManager;
    /** 变更监听器列表（写时复制）。 */
    private final List<DocumentListener> listeners;
    /** 关联文件路径；未打开/未另存时为 {@code null}。 */
    private Path filePath;
    /** LRU 访问时钟源。 */
    private long accessCounter;
    /** 文档级最长行缓存（按块元数据聚合维护）。 */
    private int cachedMaxLineLength;

    /** 行数前缀和：{@code prefixLines[i]} 为前 i 个块的行数之和。 */
    private int[] prefixLines = {0};
    /** 字符前缀和（虚拟尾 LF 模型）。 */
    private int[] prefixChars = {0};
    /** 前缀和脏标记，块结构或行内容变化后置位。 */
    private boolean prefixDirty;

    /** 批量编辑嵌套深度；0 表示非批量状态。 */
    private int batchLevel;
    /** 批量期间发生变更的最小行号。 */
    private int batchMinLine;
    /** 批量开始时的行数。 */
    private int batchOldLineCount;

    /** 全文惰性缓存。 */
    private String cachedText;
    /** 全文缓存脏标记。 */
    private boolean textDirty;

    /** 以默认配置创建空文档。 */
    public PagedDocument() {
        this(DEFAULT_BLOCK_SIZE, DEFAULT_MAX_CACHED_BLOCKS);
    }

    /**
     * 以指定配置创建空文档。
     *
     * @param blockSize       每块行数（低于 {@value #MIN_BLOCK_SIZE} 时钳制）
     * @param maxCachedBlocks 最大缓存块数（低于 {@value #MIN_CACHED_BLOCKS} 时钳制）
     */
    public PagedDocument(int blockSize, int maxCachedBlocks) {
        this.blockSize = Math.max(MIN_BLOCK_SIZE, blockSize);
        this.maxCachedBlocks = Math.max(MIN_CACHED_BLOCKS, maxCachedBlocks);
        this.blocks = new ArrayList<>();
        this.undoManager = new UndoManager();
        this.listeners = new CopyOnWriteArrayList<>();
        this.prefixDirty = true;
        this.batchMinLine = Integer.MAX_VALUE;
    }

    // ==================== 文件打开 / 保存 ====================

    /**
     * 打开文件：一次顺序扫描（UTF-8，CR/CRLF/LF 均识别为换行）建立
     * 每块的字节区间、行数、字符数与最长行元数据，<em>不保留行内容</em>。
     * 清空既有内容与撤销历史，并广播一次全量变更事件。
     *
     * @param path 目标文件
     * @throws IOException 读取失败时
     */
    public void openFile(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        int oldLineCount = getLineCount();

        blocks.clear();
        undoManager.clear();
        this.filePath = path;
        this.cachedMaxLineLength = 0;

        if (bytes.length > 0) {
            scanBytes(bytes);
        }

        prefixDirty = true;
        textDirty = true;
        fireDocumentFullChange(0, getLineCount() - oldLineCount);
    }

    /**
     * 逐字节扫描建块：在行终止符（LF、CR、CRLF）处计行，攒满
     * {@code blockSize} 行封块；文件末尾必然存在最后一行（可能为空）。
     * 每块封块时临时解码一次以计算字符数与最长行，随后丢弃内容。
     */
    private void scanBytes(byte[] bytes) {
        int blockStart = 0;
        int linesInBlock = 0;
        int i = 0;
        while (i < bytes.length) {
            byte b = bytes[i];
            if (b == '\r' || b == '\n') {
                if (b == '\r' && i + 1 < bytes.length && bytes[i + 1] == '\n') {
                    i++;
                }
                linesInBlock++;
                if (linesInBlock == blockSize) {
                    addScannedBlock(bytes, blockStart, i + 1, linesInBlock);
                    blockStart = i + 1;
                    linesInBlock = 0;
                }
            }
            i++;
        }
        // 末块：最后一个终止符之后的内容构成最后一行（可能为空行）
        addScannedBlock(bytes, blockStart, bytes.length, linesInBlock + 1);
    }

    /** 由扫描区间构造 clean 未加载块，元数据经一次临时解码计算。 */
    private void addScannedBlock(byte[] bytes, int start, int end, int lineCount) {
        Block block = new Block();
        block.byteOffset = start;
        block.byteLength = end - start;
        block.lineCount = lineCount;
        block.reloadable = true;
        List<String> lines = decodeLines(bytes, start, end - start, lineCount);
        updateBlockMetadata(block, lines);
        block.lines = null;
        blocks.add(block);
        if (block.maxLineLength > cachedMaxLineLength) {
            cachedMaxLineLength = block.maxLineLength;
        }
    }

    /** 解码字节区间为行列表：UTF-8 解码 → LF 规范化 → 取前 lineCount 行。 */
    private static List<String> decodeLines(byte[] bytes, int offset, int length, int lineCount) {
        String content = new String(bytes, offset, length, StandardCharsets.UTF_8);
        String normalized = MemoryDocument.normalizeLineEndings(content);
        String[] parts = normalized.split("\n", -1);
        List<String> lines = new ArrayList<>(lineCount);
        for (int i = 0; i < lineCount; i++) {
            lines.add(i < parts.length ? parts[i] : "");
        }
        return lines;
    }

    /**
     * 保存到当前关联文件。
     *
     * @throws IOException           写入失败时
     * @throws IllegalStateException 未关联文件时（应改用 {@link #saveAs}）
     */
    public void save() throws IOException {
        if (filePath == null) {
            throw new IllegalStateException("尚未关联文件，请使用 saveAs(Path)");
        }
        saveAs(filePath);
    }

    /**
     * 另存为指定文件：逐块写出 LF 规范化的 UTF-8 内容（clean 块从
     * 原文件临时读取、dirty 块从内存写出），经临时文件原子替换目标；
     * 完成后全部块转 clean 并指向新文件的字节区间。
     *
     * @param path 目标文件
     * @throws IOException 写入失败时
     */
    public void saveAs(Path path) throws IOException {
        Path dir = path.toAbsolutePath().getParent();
        Path temp = Files.createTempFile(dir, "jfxeditor-save-", ".tmp");
        long[] newOffsets = new long[blocks.size()];
        int[] newLengths = new int[blocks.size()];
        try {
            long written = 0;
            try (OutputStream out = new BufferedOutputStream(
                    Files.newOutputStream(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))) {
                for (int i = 0; i < blocks.size(); i++) {
                    Block block = blocks.get(i);
                    List<String> lines = block.lines != null ? block.lines : readBlockLines(block);
                    boolean lastBlock = (i == blocks.size() - 1);
                    byte[] data = joinLines(lines, !lastBlock).getBytes(StandardCharsets.UTF_8);
                    out.write(data);
                    newOffsets[i] = written;
                    newLengths[i] = data.length;
                    written += data.length;
                }
            }
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }

        this.filePath = path;
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            block.byteOffset = newOffsets[i];
            block.byteLength = newLengths[i];
            block.dirty = false;
            block.reloadable = true;
        }
        evictIfNeeded();
    }

    /** 驱逐全部 clean 已加载块释放内存；dirty 块不受影响。 */
    public void compact() {
        for (Block block : blocks) {
            if (block.lines != null && !block.dirty && block.reloadable) {
                block.lines = null;
            }
        }
    }

    /** @return 关联文件路径；未打开/未另存时为 {@code null} */
    public Path getFilePath() {
        return filePath;
    }

    /** @return 存在未保存修改（任一 dirty 块）时返回 {@code true} */
    public boolean isDirty() {
        for (Block block : blocks) {
            if (block.dirty) {
                return true;
            }
        }
        return false;
    }

    /** @return 配置的每块行数 */
    public int getBlockSizeConfig() {
        return blockSize;
    }

    /** @return 当前最大缓存块数 */
    public int getMaxCachedBlocks() {
        return maxCachedBlocks;
    }

    /**
     * 动态调整缓存上限并立即按新上限驱逐多余 clean 块。
     *
     * @param newMax 新上限（低于 {@value #MIN_CACHED_BLOCKS} 时钳制）
     */
    public void setMaxCachedBlocks(int newMax) {
        this.maxCachedBlocks = Math.max(MIN_CACHED_BLOCKS, newMax);
        evictIfNeeded();
    }

    /** @return 已加载（lines 非空，含 dirty）的块数 */
    public int getLoadedBlockCount() {
        int count = 0;
        for (Block block : blocks) {
            if (block.lines != null) {
                count++;
            }
        }
        return count;
    }

    /** @return 总块数 */
    public int getTotalBlockCount() {
        return blocks.size();
    }

    /** {@inheritDoc} 释放全部可重载块的内存缓存（不写盘、不清 dirty 块）。 */
    @Override
    public void close() {
        compact();
    }

    // ==================== Document 读取契约 ====================

    /** {@inheritDoc} 物化全文（大文件昂贵）；clean 未加载块经临时解码，不进缓存。 */
    @Override
    public String getText() {
        if (!textDirty && cachedText != null) {
            return cachedText;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            List<String> lines = block.lines != null ? block.lines : readBlockLines(block);
            sb.append(joinLines(lines, i < blocks.size() - 1));
        }
        cachedText = sb.toString();
        textDirty = false;
        return cachedText;
    }

    /** {@inheritDoc} */
    @Override
    public String getText(TextRange range) {
        TextRange normalized = range.normalize();
        if (getLineCount() == 0) {
            return "";
        }
        int startLine = normalized.start().line();
        int endLine = normalized.end().line();
        checkLineIndex(startLine);
        checkLineIndex(endLine);
        if (startLine == endLine) {
            String line = getLine(startLine);
            int from = Math.min(normalized.start().column(), line.length());
            int to = Math.min(normalized.end().column(), line.length());
            return from >= to ? "" : line.substring(from, to);
        }
        StringBuilder sb = new StringBuilder();
        String first = getLine(startLine);
        sb.append(first.substring(Math.min(normalized.start().column(), first.length())));
        for (int i = startLine + 1; i < endLine; i++) {
            sb.append('\n').append(getLine(i));
        }
        String last = getLine(endLine);
        sb.append('\n').append(last, 0, Math.min(normalized.end().column(), last.length()));
        return sb.toString();
    }

    /** {@inheritDoc} 触发所在块的惰性加载。 */
    @Override
    public String getLine(int lineIndex) {
        if (getLineCount() == 0) {
            return "";
        }
        checkLineIndex(lineIndex);
        int blockIdx = blockIndexForLine(lineIndex);
        Block block = ensureLoaded(blockIdx);
        return block.lines.get(lineIndex - prefixLines[blockIdx]);
    }

    /** {@inheritDoc} */
    @Override
    public String getLineSegment(int lineIndex, int startCol, int endCol) {
        String line = getLine(lineIndex);
        if (startCol >= line.length()) {
            return "";
        }
        int actualEnd = Math.min(endCol, line.length());
        return line.substring(startCol, actualEnd);
    }

    /** {@inheritDoc} */
    @Override
    public int getLineCount() {
        rebuildPrefixIfNeeded();
        return prefixLines[blocks.size()];
    }

    /** {@inheritDoc} 触发所在块的惰性加载。 */
    @Override
    public int getLineLength(int lineIdx) {
        checkLineIndex(lineIdx);
        return getLine(lineIdx).length();
    }

    /** {@inheritDoc} */
    @Override
    public int getMaxLineLength() {
        return cachedMaxLineLength;
    }

    /** {@inheritDoc} 真实字符数 = 虚拟尾 LF 前缀和 − 1（空文档为 0）。 */
    @Override
    public int getCharCount() {
        rebuildPrefixIfNeeded();
        int total = prefixChars[blocks.size()];
        return total == 0 ? 0 : total - 1;
    }

    /** {@inheritDoc} 空文档仅 {@code (0,0)} 合法；列超长时静默钳制到行长。 */
    @Override
    public int getOffset(int line, int col) {
        if (getLineCount() == 0 && line == 0 && col == 0) {
            return 0;
        }
        checkLineIndex(line);
        int blockIdx = blockIndexForLine(line);
        Block block = ensureLoaded(blockIdx);
        int offset = prefixChars[blockIdx];
        int local = line - prefixLines[blockIdx];
        for (int i = 0; i < local; i++) {
            offset += block.lines.get(i).length() + 1;
        }
        return offset + Math.min(col, block.lines.get(local).length());
    }

    /** {@inheritDoc} 偏移不大于 0 或空文档返回原点；超长钳制到末尾。 */
    @Override
    public Position getPosition(int offset) {
        if (getLineCount() == 0 || offset <= 0) {
            return Position.ZERO;
        }
        int clamped = Math.min(offset, getCharCount());
        int blockIdx = blockIndexForChar(clamped);
        Block block = ensureLoaded(blockIdx);
        int rel = clamped - prefixChars[blockIdx];
        int line = prefixLines[blockIdx];
        for (String lineText : block.lines) {
            if (rel <= lineText.length()) {
                return Position.of(line, rel);
            }
            rel -= lineText.length() + 1;
            line++;
        }
        // 理论不可达：偏移已钳制在本块前缀区间内
        return Position.of(line - 1, block.lines.get(block.lines.size() - 1).length());
    }

    // ==================== Document 写入契约 ====================

    /**
     * {@inheritDoc}
     *
     * <p>副作用：清空撤销历史；内容以 dirty 块驻留内存（无文件回源），
     * 并广播一次全量变更事件。</p>
     */
    @Override
    public void setText(String text) {
        String normalized = MemoryDocument.normalizeLineEndings(text);
        int oldLineCount = getLineCount();
        blocks.clear();
        undoManager.clear();
        if (!normalized.isEmpty()) {
            String[] parts = normalized.split("\n", -1);
            for (int start = 0; start < parts.length; start += blockSize) {
                int count = Math.min(blockSize, parts.length - start);
                List<String> lines = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    lines.add(parts[start + i]);
                }
                blocks.add(newDirtyBlock(lines));
            }
        }
        prefixDirty = true;
        textDirty = true;
        recomputeMaxLineLength();
        fireDocumentFullChange(0, getLineCount() - oldLineCount);
    }

    /** {@inheritDoc} */
    @Override
    public TextRange insert(int line, int col, String text) {
        checkPosition(line, col);
        String normalized = MemoryDocument.normalizeLineEndings(text);
        if (normalized.isEmpty()) {
            return TextRange.fromPosition(
                    getLineCount() == 0 ? Position.of(0, 0) : Position.of(line, col));
        }

        int oldLineCount = getLineCount();
        int offset = getOffset(getLineCount() == 0 ? 0 : line, getLineCount() == 0 ? 0 : col);

        Position endPos = doInsert(line, col, normalized);

        int lineDelta = getLineCount() - oldLineCount;
        undoManager.push(new InsertEdit(offset, normalized));

        if (batchLevel == 0) {
            fireDocumentInserted(line, lineDelta, offset, offset + normalized.length(),
                    normalized, Position.of(line, col));
        } else {
            batchMinLine = Math.min(batchMinLine, line);
        }
        return TextRange.fromPosition(endPos);
    }

    /** {@inheritDoc} */
    @Override
    public TextRange delete(TextRange range) {
        TextRange normalized = range.normalize();
        if (getLineCount() == 0) {
            return TextRange.fromPosition(normalized.start());
        }
        int startOffset = getOffset(normalized.start().line(), normalized.start().column());
        int endOffset = getOffset(normalized.end().line(), normalized.end().column());
        if (endOffset <= startOffset) {
            return TextRange.fromPosition(normalized.start());
        }

        String deletedText = getText(normalized);
        int oldLineCount = getLineCount();
        Position start = Position.of(normalized.start().line(),
                Math.min(normalized.start().column(), getLineLength(normalized.start().line())));

        doDelete(normalized);

        int lineDelta = getLineCount() - oldLineCount;
        undoManager.push(new DeleteEdit(startOffset, deletedText));

        if (batchLevel == 0) {
            fireDocumentDeleted(start.line(), lineDelta, startOffset, endOffset, deletedText, start);
        } else {
            batchMinLine = Math.min(batchMinLine, start.line());
        }
        return TextRange.fromPosition(start);
    }

    /** {@inheritDoc} */
    @Override
    public boolean canUndo() {
        return undoManager.canUndo();
    }

    /** {@inheritDoc} */
    @Override
    public boolean canRedo() {
        return undoManager.canRedo();
    }

    /** {@inheritDoc} */
    @Override
    public TextRange undo() {
        return undoManager.undo(this);
    }

    /** {@inheritDoc} */
    @Override
    public TextRange redo() {
        return undoManager.redo(this);
    }

    /** {@inheritDoc} 最外层开始时记录基线行数并重置最小变更行。 */
    @Override
    public void beginBatch() {
        if (batchLevel == 0) {
            batchMinLine = Integer.MAX_VALUE;
            batchOldLineCount = getLineCount();
        }
        batchLevel++;
    }

    /** {@inheritDoc} 最外层结束时统一发一次行级聚合事件。 */
    @Override
    public void endBatch() {
        if (batchLevel <= 0) {
            throw new IllegalStateException("endBatch called without matching beginBatch");
        }
        batchLevel--;
        if (batchLevel == 0) {
            textDirty = true;
            int lineDelta = getLineCount() - batchOldLineCount;
            int startLine = (batchMinLine == Integer.MAX_VALUE) ? 0 : batchMinLine;
            fireDocumentFullChange(startLine, lineDelta);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void addDocumentListener(DocumentListener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    /** {@inheritDoc} */
    @Override
    public void removeDocumentListener(DocumentListener listener) {
        listeners.remove(listener);
    }

    // ==================== ReplayableDocument（撤销回放） ====================

    /** {@inheritDoc} 经内部编辑原语回放，不入撤销栈、不触发事件。 */
    @Override
    public void replaceRange(int offset, int deleteLen, String insertText) {
        if (deleteLen > 0) {
            Position start = getPosition(offset);
            Position end = getPosition(offset + deleteLen);
            doDelete(TextRange.of(start.line(), start.column(), end.line(), end.column()));
        }
        if (insertText != null && !insertText.isEmpty()) {
            Position insertAt = getPosition(offset);
            doInsert(insertAt.line(), insertAt.column(), insertText);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void fireDocumentChanged(int startOffset, int oldEndOffset, int newEndOffset,
                                    String oldText, String newText, Position startPosition, int lineDelta) {
        if (listeners.isEmpty()) {
            return;
        }
        DocumentChange change = new DocumentChange(startPosition.line(), lineDelta,
                startOffset, oldEndOffset, newEndOffset, oldText, newText, startPosition);
        for (DocumentListener listener : listeners) {
            listener.documentChanged(change);
        }
    }

    // ==================== 内部编辑原语 ====================

    /**
     * 无副作用插入原语（不入栈、不发事件）：定位块并加载，按换行数
     * 展开为块内行替换，随后维护块元数据、必要时分裂超大块。
     *
     * @return 插入结束位置
     */
    private Position doInsert(int line, int col, String text) {
        if (getLineCount() == 0) {
            List<String> seed = new ArrayList<>();
            seed.add("");
            blocks.add(newDirtyBlock(seed));
            prefixDirty = true;
            line = 0;
            col = 0;
        }

        int blockIdx = blockIndexForLine(line);
        Block block = ensureLoaded(blockIdx);
        int local = line - prefixLines[blockIdx];
        String current = block.lines.get(local);
        int safeCol = Math.min(col, current.length());

        String[] parts = text.split("\n", -1);
        Position endPos;
        if (parts.length == 1) {
            block.lines.set(local, current.substring(0, safeCol) + text + current.substring(safeCol));
            endPos = Position.of(line, safeCol + text.length());
        } else {
            List<String> replacement = new ArrayList<>(parts.length);
            replacement.add(current.substring(0, safeCol) + parts[0]);
            for (int i = 1; i < parts.length - 1; i++) {
                replacement.add(parts[i]);
            }
            replacement.add(parts[parts.length - 1] + current.substring(safeCol));
            block.lines.remove(local);
            block.lines.addAll(local, replacement);
            endPos = Position.of(line + parts.length - 1, parts[parts.length - 1].length());
        }

        markBlockEdited(block);
        maybeSplitBlock(blockIdx);
        afterMutation();
        if (block.maxLineLength > cachedMaxLineLength) {
            cachedMaxLineLength = block.maxLineLength;
        }
        return endPos;
    }

    /**
     * 无副作用删除原语（不入栈、不发事件）：合并首尾边界行、整块移除
     * 中间块；删空全部内容时坍缩为空文档（0 行）。
     */
    private void doDelete(TextRange normalized) {
        int startLine = normalized.start().line();
        int endLine = normalized.end().line();
        int firstIdx = blockIndexForLine(startLine);
        int lastIdx = blockIndexForLine(endLine);

        Block first = ensureLoaded(firstIdx);
        int firstLocal = startLine - prefixLines[firstIdx];
        String startText = first.lines.get(firstLocal);
        int startCol = Math.min(normalized.start().column(), startText.length());

        Block last = ensureLoaded(lastIdx);
        int lastLocal = endLine - prefixLines[lastIdx];
        String endText = last.lines.get(lastLocal);
        int endCol = Math.min(normalized.end().column(), endText.length());

        String merged = startText.substring(0, startCol) + endText.substring(endCol);

        if (firstIdx == lastIdx) {
            first.lines.subList(firstLocal, lastLocal + 1).clear();
            first.lines.add(firstLocal, merged);
        } else {
            // 尾块剩余行并入首块，中间块与尾块整体移除
            List<String> tail = new ArrayList<>(last.lines.subList(lastLocal + 1, last.lines.size()));
            first.lines.subList(firstLocal, first.lines.size()).clear();
            first.lines.add(merged);
            first.lines.addAll(tail);
            blocks.subList(firstIdx + 1, lastIdx + 1).clear();
        }

        markBlockEdited(first);
        maybeSplitBlock(firstIdx);
        afterMutation();

        // 与 MemoryDocument 对齐：删空全部内容后行数归 0
        if (getCharCount() == 0) {
            blocks.clear();
            prefixDirty = true;
        }
        recomputeMaxLineLength();
    }

    /** 编辑后的公共状态维护：前缀和与全文缓存置脏。 */
    private void afterMutation() {
        prefixDirty = true;
        textDirty = true;
    }

    /** 把块标记为已编辑：转 dirty、断开文件回源并刷新其元数据。 */
    private void markBlockEdited(Block block) {
        block.dirty = true;
        block.reloadable = false;
        updateBlockMetadata(block, block.lines);
    }

    /** 以给定行列表重算块的行数/字符数/最长行元数据。 */
    private static void updateBlockMetadata(Block block, List<String> lines) {
        int chars = 0;
        int maxLen = 0;
        for (String line : lines) {
            chars += line.length() + 1;
            if (line.length() > maxLen) {
                maxLen = line.length();
            }
        }
        block.lineCount = lines.size();
        block.charCount = chars;
        block.maxLineLength = maxLen;
    }

    /** 创建携带给定行的 dirty 块（无文件回源）。 */
    private Block newDirtyBlock(List<String> lines) {
        Block block = new Block();
        block.lines = lines;
        block.dirty = true;
        block.reloadable = false;
        block.lastAccess = ++accessCounter;
        updateBlockMetadata(block, lines);
        return block;
    }

    /** 编辑后块行数超过 2×blockSize 时按 blockSize 分裂为多个 dirty 块。 */
    private void maybeSplitBlock(int blockIdx) {
        Block block = blocks.get(blockIdx);
        if (block.lines == null || block.lines.size() <= blockSize * 2) {
            return;
        }
        List<String> all = block.lines;
        List<Block> replacements = new ArrayList<>();
        for (int start = 0; start < all.size(); start += blockSize) {
            int end = Math.min(start + blockSize, all.size());
            replacements.add(newDirtyBlock(new ArrayList<>(all.subList(start, end))));
        }
        blocks.remove(blockIdx);
        blocks.addAll(blockIdx, replacements);
        prefixDirty = true;
    }

    /** O(块数) 聚合各块元数据重算文档级最长行。 */
    private void recomputeMaxLineLength() {
        int max = 0;
        for (Block block : blocks) {
            if (block.maxLineLength > max) {
                max = block.maxLineLength;
            }
        }
        cachedMaxLineLength = max;
    }

    // ==================== 块加载与 LRU ====================

    /** 确保块已加载并刷新其 LRU 时间戳；加载后按需驱逐其他 clean 块。 */
    private Block ensureLoaded(int blockIdx) {
        Block block = blocks.get(blockIdx);
        block.lastAccess = ++accessCounter;
        if (block.lines == null) {
            block.lines = readBlockLines(block);
            evictIfNeeded();
        }
        return block;
    }

    /** 按字节区间从关联文件同步读取并解码块内容（不改变缓存状态）。 */
    private List<String> readBlockLines(Block block) {
        if (!block.reloadable || filePath == null) {
            throw new IllegalStateException("block has no file backing and no in-memory lines");
        }
        try (SeekableByteChannel channel = Files.newByteChannel(filePath, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(block.byteLength);
            channel.position(block.byteOffset);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // 循环读满字节区间
            }
            return decodeLines(buffer.array(), 0, buffer.position(), block.lineCount);
        } catch (IOException e) {
            throw new UncheckedIOException("加载块失败: " + filePath, e);
        }
    }

    /** 可驱逐（clean 已加载）块超过上限时，按 LRU 逐个驱逐最旧者。 */
    private void evictIfNeeded() {
        while (true) {
            Block oldest = null;
            int evictable = 0;
            for (Block block : blocks) {
                if (block.lines != null && !block.dirty && block.reloadable) {
                    evictable++;
                    if (oldest == null || block.lastAccess < oldest.lastAccess) {
                        oldest = block;
                    }
                }
            }
            if (evictable <= maxCachedBlocks || oldest == null) {
                return;
            }
            oldest.lines = null;
        }
    }

    // ==================== 前缀和与定位 ====================

    /** 前缀和失效时 O(块数) 重建行数/字符数前缀数组。 */
    private void rebuildPrefixIfNeeded() {
        if (!prefixDirty) {
            return;
        }
        int n = blocks.size();
        prefixLines = new int[n + 1];
        prefixChars = new int[n + 1];
        for (int i = 0; i < n; i++) {
            prefixLines[i + 1] = prefixLines[i] + blocks.get(i).lineCount;
            prefixChars[i + 1] = prefixChars[i] + blocks.get(i).charCount;
        }
        prefixDirty = false;
    }

    /** 二分查找覆盖指定行的块下标（调用方保证行号合法）。 */
    private int blockIndexForLine(int line) {
        rebuildPrefixIfNeeded();
        int low = 0;
        int high = blocks.size() - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (prefixLines[mid + 1] <= line) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /** 二分查找覆盖指定字符偏移的块下标（偏移已钳制）。 */
    private int blockIndexForChar(int offset) {
        rebuildPrefixIfNeeded();
        int low = 0;
        int high = blocks.size() - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (prefixChars[mid + 1] <= offset) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /** 行拼接：行间以 LF 连接；{@code trailingNewline} 时末尾追加 LF。 */
    private static String joinLines(List<String> lines, boolean trailingNewline) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        if (trailingNewline) {
            sb.append('\n');
        }
        return sb.toString();
    }

    // ==================== 校验与事件 ====================

    /** 校验行号合法性，越界抛带合法区间提示的 {@link IndexOutOfBoundsException}。 */
    private void checkLineIndex(int lineIdx) {
        int count = getLineCount();
        if (lineIdx < 0 || (count == 0 ? lineIdx > 0 : lineIdx >= count)) {
            throw new IndexOutOfBoundsException(
                    "Line index " + lineIdx + " out of bounds [0, " + (count - 1) + "]");
        }
    }

    /** 严格校验插入位置：空文档只允许 {@code (0,0)}，否则列必须在 {@code [0, 行长]} 内。 */
    private void checkPosition(int line, int col) {
        if (getLineCount() == 0) {
            if (line != 0 || col != 0) {
                throw new IndexOutOfBoundsException(
                        "Position (" + line + ", " + col + ") out of bounds for empty document");
            }
            return;
        }
        checkLineIndex(line);
        int maxCol = getLineLength(line);
        if (col < 0 || col > maxCol) {
            throw new IndexOutOfBoundsException(
                    "Column " + col + " out of bounds [0, " + maxCol + "] at line " + line);
        }
    }

    /** 广播纯插入事件（非批量插入时调用）。 */
    private void fireDocumentInserted(int startLine, int lineDelta, int startOffset,
                                      int newEndOffset, String newText, Position startPosition) {
        if (listeners.isEmpty()) {
            return;
        }
        DocumentChange change = DocumentChange.ofInsert(startLine, lineDelta, startOffset,
                newEndOffset, newText, startPosition);
        for (DocumentListener listener : listeners) {
            listener.documentChanged(change);
        }
    }

    /** 广播纯删除事件（非批量删除时调用）。 */
    private void fireDocumentDeleted(int startLine, int lineDelta, int startOffset,
                                     int oldEndOffset, String oldText, Position startPosition) {
        if (listeners.isEmpty()) {
            return;
        }
        DocumentChange change = DocumentChange.ofDelete(startLine, lineDelta, startOffset,
                oldEndOffset, oldText, startPosition);
        for (DocumentListener listener : listeners) {
            listener.documentChanged(change);
        }
    }

    /** 广播行级全量变更事件（{@code setText}/{@code openFile}/批量结束时调用）。 */
    private void fireDocumentFullChange(int startLine, int lineDelta) {
        if (listeners.isEmpty()) {
            return;
        }
        DocumentChange change = DocumentChange.ofLineChange(startLine, lineDelta);
        for (DocumentListener listener : listeners) {
            listener.documentChanged(change);
        }
    }
}
