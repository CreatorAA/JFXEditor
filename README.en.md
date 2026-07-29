# JFXEditor

English | [简体中文](README.md)

[![Maven Central](https://img.shields.io/maven-central/v/io.github.creatoraa/jfxeditor)](https://central.sonatype.com/artifact/io.github.creatoraa/jfxeditor)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

A native JavaFX code editor component rendered entirely on **Canvas**, highly extensible, with full support for **FXML syntax** and **CSS stylesheets**.

![Demo](imgs/demo.gif)

## ✨ Features

- 🎨 **Pure Canvas rendering** — no TextArea/WebView dependency, high-performance self-drawn editor
- 🧩 **Control/Skin architecture** — a standard JavaFX control, usable directly in FXML
- 🎭 **Fully CSS-driven styling** — 40+ `-editor-*` CSS properties covering all syntax token colors, with runtime theme hot-switching
- 🌈 **Syntax highlighting** — built-in Tree-sitter (Java) and regex-based (Java / JSON) engines, asynchronous and non-blocking
- 🔌 **Highly extensible** — pluggable render layers, decoration model, key bindings, and indent strategies
- 📄 **Multiple document models** — in-memory / paged large-file documents backed by GapBuffer, with undo/redo
- 🎯 **Four built-in themes** — Dark / Light / Purple / High Contrast

## 📦 Requirements

| Dependency | Version |
| ---------- | ------- |
| JDK | 21+ |
| JavaFX | 23.0.1 |
| Maven | 3.x |

## 📥 Maven Dependency

```xml
<dependency>
    <groupId>io.github.creatoraa</groupId>
    <artifactId>jfxeditor</artifactId>
    <version>1.2-preview</version>
</dependency>
```

## 🚀 Quick Start

### In Java

```java
JFXEditor editor = new JFXEditor();
editor.document().setText("public class Demo {}\n");
editor.setHighlighter(TreeSitterHighlighter.forJava()); // enable syntax highlighting

// Apply a built-in theme (scene-level or control-level)
scene.getStylesheets().setAll(EditorTheme.DARK.getStylesheet());
```

### In FXML

```xml
<?import org.pigeonshouse.javafx.editor.editor.JFXEditor?>

<JFXEditor fx:id="editor" VBox.vgrow="ALWAYS">
    <style>
        -editor-background: #0f1c2e;
        -editor-text-color: #cfe3f5;
        -editor-token-keyword: #58a6ff;
        -editor-token-keyword-style: 'bold';
        <!-- more -editor-* properties... -->
    </style>
</JFXEditor>
```

## 🧱 Built-in Modules

| Module | Description |
| ------ | ----------- |
| `core.document` | Document model: GapBuffer, in-memory/paged documents, undo/redo, line index |
| `editor` | Editor control: Control/Skin, caret, decorations, key bindings, render layers, indent strategies |
| `syntax` | Syntax highlighting: Tree-sitter & regex highlighters, async engine, themes |
| `search` | Text search engine |
| `demo` | Demo apps: CSS theme hot-reload, FXML inline styles |

## 🖥️ Run the Demo

```bash
# CSS theme hot-reload demo (launch from project root to edit & hot-reload theme CSS live)
mvn javafx:run
```

## 📄 License

This project is licensed under the [MIT License](LICENSE).
