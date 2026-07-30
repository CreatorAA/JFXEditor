package org.pigeonshouse.javafx.editor.editor.wrap;

/**
 * 内置软换行断点策略工厂。
 *
 * <p>提供两族策略：{@link #CHARACTER}（字符级硬折，塞满即断）与
 * {@link #WORD_BOUNDARY}（单词边界优先，默认策略）。第三方可实现
 * {@link LineWrapStrategy} 自定义断行规则并经
 * {@code editor.setLineWrapStrategy(...)} 注入。</p>
 *
 * @see LineWrapStrategy
 */
public final class LineWrapStrategies {

    /** 字符级硬折：直接在字符级可容纳边界断开，不回退。 */
    public static final LineWrapStrategy CHARACTER = (text, start, maxFit) -> maxFit;

    /**
     * 单词边界优先：从 {@code maxFit} 向前回退到最近的可断点
     * （空白之后、或 CJK 字符前后皆可断）；整段无可断点时退化为
     * 字符级硬折，保证每段至少一个字符。
     */
    public static final LineWrapStrategy WORD_BOUNDARY = LineWrapStrategies::wordBoundaryBreak;

    private LineWrapStrategies() {
    }

    /** 从 maxFit 向前找可断列：优先在空白后断，其次在 CJK 边界断。 */
    private static int wordBoundaryBreak(String text, int start, int maxFit) {
        for (int p = maxFit; p > start + 1; p--) {
            char prev = text.charAt(p - 1);
            if (isWhitespace(prev)) {
                return p;
            }
            char cur = p < text.length() ? text.charAt(p) : '\0';
            if (isCjk(prev) || isCjk(cur)) {
                return p;
            }
        }
        return maxFit;
    }

    /** 空格或制表符视为可断空白。 */
    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t';
    }

    /** 覆盖常见 CJK / 假名 / 谚文 / 全角区段，用于判定可任意断开的表意文字。 */
    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)   // CJK 统一表意文字
                || (c >= 0x3400 && c <= 0x4DBF)   // 扩展 A
                || (c >= 0x3040 && c <= 0x30FF)   // 平假名 + 片假名
                || (c >= 0xAC00 && c <= 0xD7AF)   // 谚文音节
                || (c >= 0xFF00 && c <= 0xFFEF);  // 全角/半角形式
    }
}
