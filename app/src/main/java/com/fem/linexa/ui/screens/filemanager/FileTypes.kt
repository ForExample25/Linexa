package com.fem.linexa.ui.screens.filemanager

/**
 * Issue #9 (2026-09-03): file-type knowledge for the QuickEdit/ZArchiver-style
 * file manager — which names the built-in text editor handles, and the
 * starter templates the "New File" dialog offers.
 *
 * Pure and JVM-testable: everything keys off the file NAME (extension, with
 * the common dotfile cases), no Android types.
 *
 * The editor target list is exactly the requirement's set — TXT, JSON, YAML,
 * XML, HTML, CSS, JavaScript, TypeScript, Java, Kotlin, Python, shell,
 * Markdown, SRT, configuration files — plus the adjacent extensions users
 * actually hit (ts/kt/js variants, ini/toml/conf...). Anything not listed
 * still opens the read-only viewer; the editor never claims a binary.
 */
object FileTypes {

    /** Extensions (lowercase, without dot) the editor opens. */
    private val EDITABLE_EXTENSIONS: Set<String> = setOf(
        "txt", "text", "log", "md", "markdown", "srt", "csv",
        "json", "jsonc", "geojson",
        "yaml", "yml",
        "xml", "svg", "plist", "xhtml",
        "html", "htm", "css", "scss", "less",
        "js", "mjs", "cjs", "jsx", "ts", "tsx",
        "java", "kt", "kts",
        "py", "pyw", "rb", "php", "pl", "lua", "rs", "go", "c", "h", "cpp", "hpp", "cs",
        "sh", "bash", "zsh", "fish",
        "properties", "ini", "cfg", "conf", "config", "toml", "env", "service", "desktop",
        "gradle", "sql", "gitignore", "dockerfile"
    )

    /** Dotfiles that are editable regardless of extension. */
    private val EDITABLE_NAMES: Set<String> = setOf(
        "gitignore", "dockerfile", "makefile", "profile", "bashrc", "zshrc", "vimrc", "htaccess"
    )

    /** Whether the built-in editor should open [fileName]. */
    fun isEditable(fileName: String): Boolean {
        val name = fileName.substringAfterLast('/').lowercase()
        if (name.isEmpty()) return false
        if (name.removePrefix(".") in EDITABLE_NAMES) return true
        val ext = name.substringAfterLast('.', missingDelimiterValue = "")
        return ext in EDITABLE_EXTENSIONS
    }

    /**
     * The language family used for the editor's syntax-aware presentation —
     * [Language.PLAIN] when nothing matches (plain unstructured text renders
     * plain, honestly).
     */
    fun languageOf(fileName: String): Language {
        val name = fileName.substringAfterLast('/').lowercase()
        val ext = name.substringAfterLast('.', missingDelimiterValue = "")
        val base = name.removePrefix(".")
        return when {
            name == "dockerfile" || ext == "dockerfile" -> Language.SHELL
            name == "makefile" -> Language.SHELL
            ext in setOf("json", "jsonc", "geojson") -> Language.JSON
            ext in setOf("yaml", "yml") -> Language.YAML
            ext in setOf("xml", "svg", "plist", "xhtml", "html", "htm") -> Language.MARKUP
            ext in setOf("css", "scss", "less") -> Language.CSS
            ext in setOf("js", "mjs", "cjs", "jsx", "ts", "tsx") -> Language.JS
            ext in setOf("java", "cs") -> Language.JAVA
            ext in setOf("kt", "kts") -> Language.KOTLIN
            ext in setOf("py", "pyw", "rb") -> Language.PYTHON
            ext in setOf("sh", "bash", "zsh", "fish") -> Language.SHELL
            ext in setOf("c", "h", "cpp", "hpp") -> Language.C_LIKE
            ext == "md" || ext == "markdown" -> Language.MARKDOWN
            ext in setOf("properties", "ini", "cfg", "conf", "config", "toml", "env", "service", "desktop") -> Language.CONFIG
            else -> Language.PLAIN
        }.let { if (base in EDITABLE_NAMES && it == Language.PLAIN) Language.CONFIG else it }
    }

    /** Editor presentation families (see [SyntaxHighlighter]). */
    enum class Language { PLAIN, JSON, YAML, MARKUP, CSS, JS, JAVA, KOTLIN, PYTHON, SHELL, C_LIKE, MARKDOWN, CONFIG }

    /**
     * A "New File" starter: the extension it implies and the seed content
     * written once on creation (an empty file when [content] is null).
     * The seeds are the minimal honest scaffold for each type — a shebang
     * for scripts, `{}` for JSON — never a pretend project.
     */
    data class FileTemplate(
        val label: String,
        val extension: String,
        val content: String?
    )

    /** The New-File dialog's presets, in dialog order. */
    val templates: List<FileTemplate> = listOf(
        FileTemplate("Plain text", "txt", null),
        FileTemplate("Shell script", "sh", "#!/bin/bash\nset -euo pipefail\n"),
        FileTemplate("Python", "py", "#!/usr/bin/env python3\n\n\ndef main() -> None:\n    pass\n\n\nif __name__ == \"__main__\":\n    main()\n"),
        FileTemplate("JSON", "json", "{\n    \n}\n"),
        FileTemplate("YAML", "yaml", "---\n"),
        FileTemplate("HTML", "html", "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n    <meta charset=\"utf-8\">\n    <title></title>\n</head>\n<body>\n</body>\n</html>\n"),
        FileTemplate("CSS", "css", null),
        FileTemplate("JavaScript", "js", null),
        FileTemplate("TypeScript", "ts", null),
        FileTemplate("Java", "java", null),
        FileTemplate("Kotlin", "kt", null),
        FileTemplate("Markdown", "md", "# \n"),
        FileTemplate("Subtitles", "srt", "1\n00:00:00,000 --> 00:00:02,000\n\n"),
        FileTemplate("Config", "conf", null)
    )
}
