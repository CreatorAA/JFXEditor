package org.pigeonshouse.javafx.editor.editor.decoration;

/**
 * 装饰集合变更监听器。
 *
 * <p>回调在触发变更的线程上同步执行，且执行时 {@link DecorationModel}
 * 正持有写锁，回调内不得再调用模型的写方法（会重入）。</p>
 *
 * @see DecorationModel#addDecorationListener(DecorationChangeListener)
 */
@FunctionalInterface
public interface DecorationChangeListener {
    /**
     * 装饰集合发生变更时回调。
     *
     * @param change 变更事件
     */
    void decorationsChanged(DecorationChange change);
}
