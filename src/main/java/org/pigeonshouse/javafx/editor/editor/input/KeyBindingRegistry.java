package org.pigeonshouse.javafx.editor.editor.input;

import javafx.scene.input.KeyEvent;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 按键绑定的注册与分发中枢。
 *
 * <p><strong>分发顺序：</strong>{@link #handle(KeyEvent)} 先匹配用户绑定
 * 再匹配预设（default）绑定，因此用户绑定天然可覆盖预设快捷键。
 * 项目规范要求所有预设按键均经本注册表统一分发，不得在 Skin
 * 中硬编码。</p>
 *
 * <p><strong>线程：</strong>内部集合均为并发安全结构，但
 * {@link #handle(KeyEvent)} 应在 JavaFX 应用线程调用（处理器会操作 UI）。</p>
 *
 * <p><strong>用法示例（注册自定义快捷键）：</strong></p>
 * <pre>{@code
 * KeyBindingRegistry registry = editor.keyBindingRegistry();
 * registry.register(KeyBinding.of(KeyCode.D, "duplicate-line",
 *                                 KeyCombination.SHORTCUT_DOWN), "复制当前行")
 *         .onAction("duplicate-line", () -> {
 *             // ... 修改文档 ...
 *             registry.requestRedraw();   // 告知 Skin 需要重绘
 *         });
 * }</pre>
 *
 * @see KeyBinding
 */
public class KeyBindingRegistry {

    /** 用户绑定（分发优先级高于预设绑定）。 */
    private final List<KeyBindingEntry> bindings;
    /** 预设（default）绑定，由 Skin 在构造时注册、dispose 时注销。 */
    private final List<KeyBindingEntry> defaultBindings;
    /** 动作标识到处理器列表的映射（支持多播）。 */
    private final Map<String, List<Runnable>> actionHandlers;
    /** “取后即清”的一次性重绘请求标志。 */
    private volatile boolean needsRedraw;

    public KeyBindingRegistry() {
        this.bindings = new CopyOnWriteArrayList<>();
        this.defaultBindings = new CopyOnWriteArrayList<>();
        this.actionHandlers = new ConcurrentHashMap<>();
        this.needsRedraw = false;
    }

    /**
     * 注册用户级绑定（优先于预设绑定参与分发）。
     *
     * @param binding 按键绑定
     * @param label   人类可读标签（供将来的快捷键面板展示）
     * @return 本注册表（链式调用）
     */
    public KeyBindingRegistry register(KeyBinding binding, String label) {
        bindings.add(new KeyBindingEntry(binding, label));
        return this;
    }

    /**
     * 注册预设（default）级绑定，分发优先级低于用户绑定。
     *
     * @param binding 按键绑定
     * @param label   人类可读标签
     * @return 本注册表（链式调用）
     */
    public KeyBindingRegistry registerDefault(KeyBinding binding, String label) {
        defaultBindings.add(new KeyBindingEntry(binding, label));
        return this;
    }

    /**
     * 移除指定动作的全部用户绑定，<em>同时删除该动作的全部处理器</em>。
     *
     * @param actionId 动作标识
     * @return 本注册表（链式调用）
     */
    public KeyBindingRegistry unregister(String actionId) {
        bindings.removeIf(e -> e.binding().actionId().equals(actionId));
        actionHandlers.remove(actionId);
        return this;
    }

    /**
     * 仅移除指定动作的预设绑定（不动处理器）。
     *
     * @param actionId 动作标识
     * @return 本注册表（链式调用）
     */
    public KeyBindingRegistry unregisterDefault(String actionId) {
        defaultBindings.removeIf(e -> e.binding().actionId().equals(actionId));
        return this;
    }

    /**
     * 为动作挂接处理器（同一动作可挂多个，按注册顺序依次执行）。
     *
     * @param actionId 动作标识
     * @param handler  处理器（在 JavaFX 应用线程上执行）
     * @return 本注册表（链式调用）
     */
    public KeyBindingRegistry onAction(String actionId, Runnable handler) {
        actionHandlers.computeIfAbsent(actionId, k -> new CopyOnWriteArrayList<>()).add(handler);
        return this;
    }

    /**
     * 移除动作的指定处理器；处理器清空后移除整个映射键。
     *
     * @param actionId 动作标识
     * @param handler  待移除的处理器（需与注册时同一引用）
     * @return 本注册表（链式调用）
     */
    public KeyBindingRegistry removeAction(String actionId, Runnable handler) {
        List<Runnable> handlers = actionHandlers.get(actionId);
        if (handlers != null) {
            handlers.remove(handler);
            if (handlers.isEmpty()) {
                actionHandlers.remove(actionId);
            }
        }
        return this;
    }

    /**
     * 分发按键事件：先清重绘标志，再依次尝试用户绑定与预设绑定。
     *
     * @param event 按键事件（命中时会被消费）
     * @return 命中任一绑定时返回 {@code true}（即使该动作无处理器）
     */
    public boolean handle(KeyEvent event) {
        needsRedraw = false;
        return dispatch(bindings, event) || dispatch(defaultBindings, event);
    }

    /**
     * 在给定绑定列表中找到首个匹配项：依次运行该动作的全部
     * 处理器并消费事件；无处理器也算命中。
     */
    private boolean dispatch(List<KeyBindingEntry> source, KeyEvent event) {
        for (KeyBindingEntry entry : source) {
            if (entry.binding().combination().match(event)) {
                String actionId = entry.binding().actionId();
                List<Runnable> handlers = actionHandlers.get(actionId);
                if (handlers != null) {
                    for (Runnable handler : handlers) {
                        handler.run();
                    }
                }
                event.consume();
                return true;
            }
        }
        return false;
    }

    /** 由动作处理器调用，声明本次动作需要 Skin 重绘。 */
    public void requestRedraw() {
        needsRedraw = true;
    }

    /**
     * 读取并清除重绘请求标志（“取后即清”的一次性语义）。
     *
     * @return 自上次读取后是否有过重绘请求
     */
    public boolean isRedrawRequested() {
        boolean result = needsRedraw;
        needsRedraw = false;
        return result;
    }

    /** @return 用户绑定的不可修改视图 */
    public List<KeyBindingEntry> getAllBindings() {
        return Collections.unmodifiableList(bindings);
    }

    /** @return 预设绑定的不可修改视图 */
    public List<KeyBindingEntry> getDefaultBindings() {
        return Collections.unmodifiableList(defaultBindings);
    }

    /**
     * 注册表内部条目：绑定加人类可读标签。
     *
     * @param binding 按键绑定
     * @param label   人类可读标签
     */
    public record KeyBindingEntry(KeyBinding binding, String label) {
    }
}
