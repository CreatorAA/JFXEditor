package org.pigeonshouse.javafx.editor.editor.input;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

/**
 * 一条按键绑定：组合键与动作标识的不可变映射。
 *
 * <p>同一 {@code actionId} 可被多条绑定引用（一动作多快捷键），
 * 具体处理器由 {@link KeyBindingRegistry#onAction} 挂接。</p>
 *
 * @param combination JavaFX 组合键（主键 + 修饰键）
 * @param actionId    动作唯一标识（如 {@code "caret-left"}）
 * @see KeyBindingRegistry
 */
public record KeyBinding(KeyCombination combination, String actionId) {

    /**
     * 创建绑定，动作标识默认为按键名的小写形式。
     *
     * @param code      主键
     * @param modifiers 修饰键（可变参数）
     * @return 新绑定，{@code actionId} 为 {@code code.name().toLowerCase()}
     */
    public static KeyBinding of(KeyCode code, KeyCombination.Modifier... modifiers) {
        return new KeyBinding(new KeyCodeCombination(code, modifiers), code.name().toLowerCase());
    }

    /**
     * 创建指定动作标识的绑定（用于一键多绑或多键一动作）。
     *
     * @param code      主键
     * @param actionId  动作唯一标识
     * @param modifiers 修饰键（可变参数）
     * @return 新绑定
     */
    public static KeyBinding of(KeyCode code, String actionId, KeyCombination.Modifier... modifiers) {
        return new KeyBinding(new KeyCodeCombination(code, modifiers), actionId);
    }
}
