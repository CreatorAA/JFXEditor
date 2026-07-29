package org.pigeonshouse.javafx.editor.editor.decoration;

import org.pigeonshouse.javafx.editor.core.document.DocumentChange;

import java.util.function.Consumer;

/**
 * 装饰悬停监听器。
 *
 * <p>由 Skin 的鼠标移动处理触发，回调发生在 JavaFX 应用线程。
 * 装饰只有在拥有 {@code hoverColor} 或本监听器时才参与悬停命中检测。</p>
 *
 * @see Decoration#withHoverListener(DecorationHoverListener)
 */
public interface DecorationHoverListener {
    /**
     * 鼠标进入装饰命中区域时回调。
     *
     * @param decoration 被悬停的装饰
     */
    void onHoverStart(Decoration decoration);

    /**
     * 鼠标离开装饰命中区域时回调。
     *
     * @param decoration 不再被悬停的装饰
     */
    void onHoverEnd(Decoration decoration);
}
