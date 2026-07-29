package org.pigeonshouse.javafx.editor.syntax;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于正则表达式的有限状态机高亮器（同步、有状态、支持跨行）。
 *
 * <p><strong>多行机制原理：</strong>状态编号即“跨行上下文”。例如
 * Java 配置中状态 2 表示“块注释内部”——行尾 {@code endState} 为 2
 * 时下一行从注释规则集继续，直到匹配结束定界符转回 0；不允许
 * 跨行的结构（如普通字符串）通过 {@link Builder#resetStateAtLineEnd}
 * 在行尾强制复位。</p>
 *
 * <p><strong>匹配策略：</strong>对当前状态的每条规则在当前位置做
 * {@code lookingAt}（透明边界，支持负向后行断言跨区域左界），
 * 取最长匹配、并列时先注册者优先；未匹配段落收为 TEXT，
 * 相邻同类型 token 自动合并。</p>
 *
 * <p><strong>用法示例（带跨行块注释的迷你语言）：</strong></p>
 * <pre>{@code
 * RegexHighlighter hl = RegexHighlighter.builder()
 *     .addRule("\\b(if|else)\\b", TokenType.KEYWORD)   // 状态 0 规则
 *     .addRuleInState(0, "/\\*", 1, TokenType.COMMENT) // 进入状态 1
 *     .addRuleInState(1, "\\*[/]", 0, TokenType.COMMENT) // 回到状态 0
 *     .addRuleInState(1, "[\\s\\S]", 1, TokenType.COMMENT)
 *     .build();
 * }</pre>
 *
 * @see JavaRegexHighlighter
 * @see JsonRegexHighlighter
 */
public final class RegexHighlighter implements SyntaxHighlighter {

    /** 状态列表，下标即状态编号（不可变）。 */
    private final List<State> states;
    /** 行尾强制状态转移表：源状态 → 目标状态。 */
    private final Map<Integer, Integer> lineEndTransitions;

    private RegexHighlighter(List<State> states, Map<Integer, Integer> lineEndTransitions) {
        this.states = List.copyOf(states);
        this.lineEndTransitions = Map.copyOf(lineEndTransitions);
    }

    /**
     * {@inheritDoc}
     *
     * <p>空行输出零长 TEXT token 并仅应用行尾转移；非法状态号
     * 钳回 0。</p>
     */
    @Override
    public LineTokens tokenizeLine(String lineContent, int state, int lineIndex) {
        List<Token> rawTokens = new ArrayList<>();
        int resultState = state;

        if (lineContent.isEmpty()) {
            rawTokens.add(new Token(0, 0, TokenType.TEXT));
            return new LineTokens(rawTokens, applyLineEndTransition(resultState));
        }

        int pos = 0;
        int currentState = state;
        int textStart = 0;

        while (pos < lineContent.length()) {
            if (currentState < 0 || currentState >= states.size()) {
                currentState = 0;
            }
            State st = states.get(currentState);
            RuleMatch bestMatch = findBestMatch(lineContent, pos, st);

            if (bestMatch != null) {
                if (pos > textStart) {
                    addToken(rawTokens, new Token(textStart, pos - textStart, TokenType.TEXT));
                }
                addToken(rawTokens, new Token(pos, bestMatch.length, bestMatch.type));
                pos += bestMatch.length;
                textStart = pos;
                currentState = bestMatch.nextState;
                resultState = currentState;
            } else {
                pos++;
            }
        }

        if (textStart < lineContent.length()) {
            addToken(rawTokens, new Token(textStart, lineContent.length() - textStart, TokenType.TEXT));
        }

        if (rawTokens.isEmpty()) {
            rawTokens.add(new Token(0, 0, TokenType.TEXT));
        }

        return new LineTokens(rawTokens, applyLineEndTransition(resultState));
    }

    /** 应用行尾强制转移；无配置时原状态直传。 */
    private int applyLineEndTransition(int state) {
        return lineEndTransitions.getOrDefault(state, state);
    }

    /** 追加 token，相邻同类型且连续的 token 自动合并。 */
    private static void addToken(List<Token> tokens, Token newToken) {
        if (!tokens.isEmpty()) {
            Token last = tokens.get(tokens.size() - 1);
            if (last.type() == newToken.type() && last.end() == newToken.start()) {
                Token merged = new Token(last.start(), last.length() + newToken.length(), last.type());
                tokens.set(tokens.size() - 1, merged);
                return;
            }
        }
        tokens.add(newToken);
    }

    /**
     * 在当前位置对状态内全部规则做 lookingAt（透明边界），
     * 取最长匹配；并列时先注册者优先。
     */
    private RuleMatch findBestMatch(String line, int startPos, State state) {
        RuleMatch best = null;
        for (Rule rule : state.rules) {
            Matcher m = rule.pattern.matcher(line)
                    .region(startPos, line.length())
                    .useTransparentBounds(true)
                    .useAnchoringBounds(false);
            if (m.lookingAt()) {
                int len = m.end() - m.start();
                if (best == null || len > best.length) {
                    best = new RuleMatch(len, rule.type, rule.nextState);
                }
            }
        }
        return best;
    }

    /** @return 恒为 {@code 0}（默认状态） */
    @Override
    public int getInitialState() {
        return 0;
    }

    /** @return 新的构建器 */
    public static Builder builder() {
        return new Builder();
    }

    /** 一个状态：该状态下可用的规则集。 */
    private record State(List<Rule> rules) {
    }

    /** 一条规则：编译后的正则、token 类型与匹配后的目标状态。 */
    private record Rule(Pattern pattern, TokenType type, int nextState) {
    }

    /** 一次匹配结果：长度、类型与目标状态。 */
    private record RuleMatch(int length, TokenType type, int nextState) {
    }

    /**
     * 流式构建器。状态 0 为默认状态（名为 {@code "default"}）。
     *
     * <p><strong>注意：</strong>{@link #addState} 中目标状态名尚未注册
     * （前向引用）时会落回本状态自身，声明顺序需留意。</p>
     */
    public static final class Builder {
        private final List<List<Rule>> stateRules = new ArrayList<>();
        private final Map<String, Integer> stateNames = new HashMap<>();
        private final Map<Integer, Integer> lineEndTransitions = new HashMap<>();

        private Builder() {
            stateRules.add(new ArrayList<>());
            stateNames.put("default", 0);
        }

        /**
         * 在默认状态 0 添加规则（匹配后仍回到状态 0）。
         *
         * @param regex 正则表达式
         * @param type  token 类型
         * @return 本构建器（链式调用）
         */
        public Builder addRule(String regex, TokenType type) {
            return addRuleInState(0, regex, 0, type);
        }

        /**
         * 按名字添加命名状态及其规则集。
         *
         * <p>规则目标用状态名解析；前向引用未注册的名字会落回本
         * 状态自身（边界行为，需留意声明顺序）。</p>
         *
         * @param name  状态名
         * @param rules 规则定义列表
         * @return 本构建器（链式调用）
         */
        public Builder addState(String name, List<RuleDef> rules) {
            int stateIndex = stateRules.size();
            stateNames.put(name, stateIndex);
            List<Rule> compiledRules = new ArrayList<>();
            for (RuleDef def : rules) {
                int nextIdx = 0;
                if (def.nextStateName != null) {
                    nextIdx = stateNames.getOrDefault(def.nextStateName, stateIndex);
                }
                compiledRules.add(new Rule(Pattern.compile(def.regex), def.type, nextIdx));
            }
            stateRules.add(compiledRules);
            return this;
        }

        /**
         * 在指定状态号下添加规则（状态列表按需自动扩容）。
         *
         * @param stateIndex 所属状态号
         * @param regex      正则表达式
         * @param nextState  匹配后转入的状态号
         * @param type       token 类型
         * @return 本构建器（链式调用）
         */
        public Builder addRuleInState(int stateIndex, String regex, int nextState, TokenType type) {
            while (stateRules.size() <= stateIndex) {
                stateRules.add(new ArrayList<>());
            }
            stateRules.get(stateIndex).add(new Rule(Pattern.compile(regex), type, nextState));
            return this;
        }

        /**
         * 声明行尾强制状态转移，用于不允许跨行的结构
         * （如普通字符串未闭合时行尾复位）。
         *
         * @param fromState 源状态号
         * @param toState   行尾转入的状态号
         * @return 本构建器（链式调用）
         */
        public Builder resetStateAtLineEnd(int fromState, int toState) {
            lineEndTransitions.put(fromState, toState);
            return this;
        }

        /**
         * 构建不可变的高亮器实例。
         *
         * @return 新高亮器
         */
        public RegexHighlighter build() {
            List<State> states = new ArrayList<>();
            for (List<Rule> rules : stateRules) {
                states.add(new State(List.copyOf(rules)));
            }
            return new RegexHighlighter(states, lineEndTransitions);
        }
    }

    /**
     * 供 {@link Builder#addState} 使用的规则定义。
     *
     * @param regex         正则表达式
     * @param type          token 类型
     * @param nextStateName 匹配后转入的状态名；{@code null} 表示回默认状态
     */
    public record RuleDef(String regex, TokenType type, String nextStateName) {
        /**
         * 创建匹配后回默认状态的规则。
         *
         * @param regex 正则表达式
         * @param type  token 类型
         * @return 新规则定义
         */
        public static RuleDef of(String regex, TokenType type) {
            return new RuleDef(regex, type, null);
        }

        /**
         * 创建指定目标状态名的规则。
         *
         * @param regex     正则表达式
         * @param type      token 类型
         * @param nextState 目标状态名
         * @return 新规则定义
         */
        public static RuleDef of(String regex, TokenType type, String nextState) {
            return new RuleDef(regex, type, nextState);
        }
    }
}
