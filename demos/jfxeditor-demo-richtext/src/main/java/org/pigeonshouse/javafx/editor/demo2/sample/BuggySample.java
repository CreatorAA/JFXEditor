package org.pigeonshouse.javafx.editor.demo2.sample;

import java.util.ArrayList;
import java.util.List;

/**
 * demo2 的 fileindex 示例目标文件，23:13 处会被打上 ERROR 波浪线。
 */
public class BuggySample {

    private final List<String> items = new ArrayList<>();

    public void add(String item) {
        if (item == null) {
            return;
        }
        items.add(item);
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            sb.append(item).append('\n');
        }
        return sb.toString();
    }
}
