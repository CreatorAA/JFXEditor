package org.pigeonshouse.javafx.editor.editor;

import java.net.URL;
import java.util.Objects;

/**
 * 编辑器内置主题样式表。
 *
 * <p>每个枚举值对应一份打包在 classpath 的完整主题 CSS
 * （{@code editor/themes/*.css}），覆盖全部基础颜色与 token 配色属性。
 * 本枚举只暴露样式表 URL，应用方式由使用方决定：</p>
 *
 * <pre>{@code
 * // 场景级：整个窗口内的编辑器统一换肤
 * scene.getStylesheets().setAll(EditorTheme.DEFAULT.getStylesheet());
 *
 * // 控件级：单个编辑器独立换肤
 * editor.getStylesheets().setAll(EditorTheme.LIGHT.getStylesheet());
 * }</pre>
 *
 * @see JFXEditor
 */
public enum EditorTheme {

    /** 暗色主题。 */
    DEFAULT("editor.css"),
    /** 亮色主题。 */
    LIGHT("themes/light.css"),
    /** 高对比度主题。 */
    HIGH_CONTRAST("themes/high-contrast.css"),
    /** 紫色主题。 */
    PURPLE("themes/purple.css");

    /** 相对本类所在包的样式表资源路径。 */
    private final String resourcePath;
    /** 解析后的样式表 external-form URL（枚举构造时解析并校验存在）。 */
    private final String stylesheet;

    EditorTheme(String resourcePath) {
        this.resourcePath = resourcePath;
        URL resource = EditorTheme.class.getResource(resourcePath);
        this.stylesheet = Objects.requireNonNull(resource, "内置主题资源缺失: " + resourcePath)
                .toExternalForm();
    }

    /**
     * 返回主题样式表的 URL（external form），可直接加入
     * {@code Scene#getStylesheets()} 或控件的 {@code getStylesheets()}。
     *
     * @return 样式表 URL，永不为 {@code null}
     */
    public String getStylesheet() {
        return stylesheet;
    }

    /**
     * 返回样式表相对本类所在包的资源路径（如 {@code themes/dark.css}）。
     *
     * @return 资源相对路径
     */
    public String getResourcePath() {
        return resourcePath;
    }
}
