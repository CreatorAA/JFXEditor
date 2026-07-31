package org.pigeonshouse.javafx.editor.editor;

/**
 * 视口变化监听器：编辑器发生滚动（水平或垂直）时回调。
 *
 * <p>用于滚动联动的外部内容，如 minimap、随滚动重定位的浮层等。
 * 内容变化可另行监听文档，视口尺寸变化可监听控件的
 * {@code widthProperty()}/{@code heightProperty()}。</p>
 *
 * <p><strong>线程：</strong>回调在 JavaFX 应用线程上同步触发。</p>
 *
 * @see JFXEditor#addViewportChangeListener(ViewportChangeListener)
 */
@FunctionalInterface
public interface ViewportChangeListener {

    /** 滚动位置变化时回调（可经 {@link EditorGeometry} 读取最新视口/滚动量）。 */
    void onViewportChanged();
}
