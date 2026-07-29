package org.pigeonshouse.javafx.editor.core.document;

import org.pigeonshouse.javafx.editor.core.model.Position;
import org.pigeonshouse.javafx.editor.core.model.TextRange;

/**
 * 编辑器文档模型的公共契约。
 *
 * <p>渲染层（{@code JFXEditorSkin}）、语法高亮（{@code HighlightEngine} /
 * {@code TreeSitterHighlighter}）、搜索（{@code SearchEngine}）均依赖本接口。
 * 唯一内置实现为 {@link MemoryDocument}。</p>
 *
 * <p><strong>坐标约定：</strong></p>
 * <ul>
 *   <li>行号、列号、全局偏移均从 {@code 0} 开始，单位为 UTF-16 code unit；</li>
 *   <li>所有写入文本会被统一规范化为 {@code \n} 换行；</li>
 *   <li>空文档的行数为 {@code 0}（而非 1）。</li>
 * </ul>
 *
 * <p><strong>用法示例（批量编辑与撤销）：</strong></p>
 * <pre>{@code
 * Document doc = new MemoryDocument();
 * doc.setText("hello\nworld");
 *
 * doc.beginBatch();                       // 合并事件通知（不合并撤销单元）
 * try {
 *     doc.insert(0, 5, "!");
 *     doc.delete(TextRange.of(1, 0, 1, 5));
 * } finally {
 *     doc.endBatch();                     // 仅发一次聚合变更事件
 * }
 *
 * if (doc.canUndo()) {
 *     TextRange caret = doc.undo();       // 返回建议的光标位置
 * }
 * }</pre>
 *
 * @see MemoryDocument
 * @see DocumentListener
 */
public interface Document {

    /**
     * 返回全文文本（LF 换行）。
     *
     * @return 完整文本；空文档返回空串
     */
    String getText();

    /**
     * 返回指定区间的文本。
     *
     * @param range 目标区间，反向区间会先被归一化
     * @return 区间文本；空区间返回空串
     */
    String getText(TextRange range);

    /**
     * 返回指定行的内容（不含行尾换行符）。
     *
     * @param lineIndex 行号（0 起）
     * @return 行内容；空文档直接返回空串
     * @throws IndexOutOfBoundsException 非空文档且行号越界时
     */
    String getLine(int lineIndex);

    /**
     * 返回行内 {@code [startCol, endCol)} 子串。
     *
     * <p>宽容边界：{@code startCol} 超过行长时返回空串，
     * {@code endCol} 会自动截断到行长，不抛异常。</p>
     *
     * @param lineIndex 行号（0 起）
     * @param startCol  起始列（含）
     * @param endCol    结束列（不含）
     * @return 行内子串
     * @throws IndexOutOfBoundsException 行号越界时
     */
    String getLineSegment(int lineIndex, int startCol, int endCol);

    /**
     * 返回总行数。
     *
     * @return 行数；空文档为 {@code 0}
     */
    int getLineCount();

    /**
     * 返回指定行的字符数（不含换行符）。
     *
     * @param lineIndex 行号（0 起）
     * @return 行长
     * @throws IndexOutOfBoundsException 行号越界时
     */
    int getLineLength(int lineIndex);

    /**
     * 返回全文最长行的长度（实现侧缓存，供横向滚动条计算使用）。
     *
     * @return 最长行字符数；空文档为 {@code 0}
     */
    int getMaxLineLength();

    /**
     * 返回总字符数（UTF-16 code unit 计，含换行符）。
     *
     * @return 字符总数
     */
    int getCharCount();

    /**
     * 行列坐标转全局偏移。
     *
     * <p>列号超过行长时会被静默鉗制到行长；空文档仅接受
     * {@code (0, 0)} 并返回 {@code 0}。</p>
     *
     * @param line 行号（0 起）
     * @param col  列号（0 起，超长时鉗制）
     * @return 全局偏移
     * @throws IndexOutOfBoundsException 行号越界时
     */
    int getOffset(int line, int col);

    /**
     * 全局偏移转行列坐标。
     *
     * <p>宽容边界：偏移不大于 0 或空文档时返回原点；超出总长时
     * 鉗制到文档末尾，不抛异常。</p>
     *
     * @param offset 全局偏移
     * @return 对应的行列坐标
     */
    Position getPosition(int offset);

    /**
     * 整体替换文档文本。
     *
     * <p>副作用：清空撤销/重做历史，并触发一次全量变更事件。
     * 文本中的 CRLF/CR 会被规范化为 LF。</p>
     *
     * @param text 新文本；{@code null} 视为空串
     */
    void setText(String text);

    /**
     * 在指定行列处插入文本。
     *
     * @param line 插入行号（0 起；空文档仅允许 0）
     * @param col  插入列号（{@code [0, 行长]}）
     * @param text 待插入文本；空文本为无操作
     * @return 落在插入结束位置的折叠区间，可直接用作新光标位置
     * @throws IndexOutOfBoundsException 插入位置非法时
     */
    TextRange insert(int line, int col, String text);

    /**
     * 删除指定区间的文本。
     *
     * @param range 待删区间（反向区间会先归一化）；空区间为无操作
     * @return 落在区间起点的折叠区间，可直接用作新光标位置
     */
    TextRange delete(TextRange range);

    /**
     * @return 撤销栈非空时返回 {@code true}
     */
    boolean canUndo();

    /**
     * @return 重做栈非空时返回 {@code true}
     */
    boolean canRedo();

    /**
     * 撤销最近一次编辑。
     *
     * <p>调用前应先用 {@link #canUndo()} 判断，避免异常。</p>
     *
     * @return 建议的光标位置（折叠区间）
     * @throws java.util.NoSuchElementException 无可撤销内容时
     */
    TextRange undo();

    /**
     * 重做最近一次被撤销的编辑。
     *
     * <p>调用前应先用 {@link #canRedo()} 判断，避免异常。</p>
     *
     * @return 建议的光标位置（折叠区间）
     * @throws java.util.NoSuchElementException 无可重做内容时
     */
    TextRange redo();

    /**
     * 开启批量编辑事务（支持嵌套）。
     *
     * <p><strong>注意：</strong>批量仅合并“事件通知”——期间的各次
     * insert/delete 不发独立事件，结束时统一发一次聚合事件；
     * 但撤销栈中每次编辑仍是独立命令，撤销一次批量操作需多次
     * {@link #undo()}。应与 {@link #endBatch()} 成对调用（建议用
     * {@code try-finally}）。</p>
     */
    void beginBatch();

    /**
     * 结束批量编辑事务；最外层结束时发送一次聚合变更事件。
     *
     * @throws IllegalStateException 无匹配的 {@link #beginBatch()} 时
     */
    void endBatch();

    /**
     * 注册文档变更监听器（回调在编辑线程上同步执行）。
     *
     * @param listener 监听器，不可为 {@code null}
     */
    void addDocumentListener(DocumentListener listener);

    /**
     * 移除已注册的文档变更监听器；未注册时静默忽略。
     *
     * @param listener 待移除的监听器
     */
    void removeDocumentListener(DocumentListener listener);
}
