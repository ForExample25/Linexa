package com.fem.linexa.ui.screens.filemanager

/**
 * Issue #9 (2026-09-03): the editor's syntax-aware presentation — a small,
 * pure tokenizer that maps a [FileTypes.Language] source buffer to typed
 * spans the Compose layer colors.
 *
 * Design constraints, on purpose:
 *
 *  - **Regex-free scanning.** A per-character single pass with explicit
 *    states (comment/string/kind) is O(n), allocation-light, and cannot
 *    catastrophic-backtrace on a hostile file — a plain regex highlighter
 *    on user-controlled content can.
 *  - **Conservative by design.** This is presentation, not parsing: a
 *    construct it does not recognize renders as plain text. The requirement
 *    is "text displayed cleanly with basic syntax-aware presentation", not
 *    an IDE frontend, so there are no scopes, no folding, no LSP anything.
 *  - **Pure + JVM-testable.** The output is a list of [Span]s (kind + half-open
 *    range over the input); the Compose layer's only job is coloring them.
 *    Every rule below is unit-pinned.
 *
 * The language families share token classes: [TokenKind.COMMENT],
 * [TokenKind.STRING], [TokenKind.NUMBER], [TokenKind.KEYWORD],
 * [TokenKind.KEY] (config/YAML keys, JSON object keys), [TokenKind.TAG]
 * (markup element names). Plain text produces zero spans.
 */
object SyntaxHighlighter {

    enum class TokenKind { COMMENT, STRING, NUMBER, KEYWORD, KEY, TAG }

    data class Span(val start: Int, val end: Int, val kind: TokenKind)

    /**
     * Tokenizes [text] for [language]. Returns non-overlapping, ordered
     * spans; adjacent same-kind runs are merged. Never throws on any input.
     */
    fun highlight(text: String, language: FileTypes.Language): List<Span> {
        if (text.isEmpty()) return emptyList()
        val raw = when (language) {
            FileTypes.Language.PLAIN -> return emptyList()
            FileTypes.Language.JSON -> json(text)
            FileTypes.Language.YAML -> yaml(text)
            FileTypes.Language.MARKUP -> markup(text)
            FileTypes.Language.CSS -> css(text)
            FileTypes.Language.JS -> cLike(text, JS_KEYWORDS, lineComment = "//", hashLineComment = false)
            FileTypes.Language.JAVA -> cLike(text, JAVA_KEYWORDS, lineComment = "//", hashLineComment = false)
            FileTypes.Language.KOTLIN -> cLike(text, KOTLIN_KEYWORDS, lineComment = "//", hashLineComment = false)
            FileTypes.Language.PYTHON -> python(text)
            FileTypes.Language.SHELL -> shell(text)
            FileTypes.Language.C_LIKE -> cLike(text, C_KEYWORDS, lineComment = "//", hashLineComment = false)
            FileTypes.Language.MARKDOWN -> markdown(text)
            FileTypes.Language.CONFIG -> config(text)
        }
        return merge(raw, text.length)
    }

    // ── shared helpers ────────────────────────────────────────────────

    private fun isIdentStart(c: Char) = c.isLetter() || c == '_'
    private fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_'

    private fun merge(spans: MutableList<Span>, length: Int): List<Span> {
        if (spans.isEmpty()) return emptyList()
        spans.sortWith(compareBy({ it.start }, { it.end }))
        val out = ArrayList<Span>(spans.size)
        for (span in spans) {
            if (span.start < 0 || span.end > length || span.start >= span.end) continue
            val last = out.lastOrNull()
            if (last != null && last.kind == span.kind && last.end >= span.start) {
                out[out.size - 1] = last.copy(end = maxOf(last.end, span.end))
            } else if (last == null || span.start >= last.end) {
                out += span
            }
        }
        return out
    }

    private fun keywordAt(text: String, start: Int, end: Int, keywords: Set<String>): Boolean {
        val word = text.substring(start, end).lowercase()
        return word in keywords
    }

    /** Scans a quoted string starting at [i] (the quote char); returns the end index (inclusive). */
    private fun stringEnd(text: String, i: Int, quote: Char): Int {
        var j = i + 1
        while (j < text.length) {
            val c = text[j]
            if (c == '\\') {
                j += 2
                continue
            }
            if (c == quote) return j
            if (c == '\n' && quote != '"' && quote != '\'') return j // safety
            if (c == '\n' && (quote == '\'' )) {
                // Single-quoted strings do not span lines in the C family;
                // JavaScript/Python differ, but not highlighting a rare
                // multi-line literal is safe (it renders plain).
                return j
            }
            j++
        }
        return text.length - 1
    }

    private fun numberSpan(text: String, start: Int): Int {
        var j = start
        while (j < text.length && (text[j].isDigit() || text[j] == '.' ||
                text[j].lowercase() == "x" && j == start + 1 || text[j].lowercaseChar() in "abcdef" && j > start)
        ) j++
        return j
    }

    private fun addWordTokens(
        text: String,
        start: Int,
        end: Int,
        keywords: Set<String>?,
        out: MutableList<Span>,
        keyKind: TokenKind? = null,
        lineStart: Int = start
    ) {
        var i = start
        while (i < end) {
            val c = text[i]
            when {
                isIdentStart(c) -> {
                    var j = i + 1
                    while (j < end && isIdentPart(text[j])) j++
                    if (keywords != null && keywordAt(text, i, j, keywords)) {
                        out += Span(i, j, TokenKind.KEYWORD)
                    } else if (keyKind != null) {
                        // A key is an identifier immediately followed by ':'
                        // (or '=') with only blanks between.
                        var k = j
                        while (k < end && text[k] == ' ') k++
                        if (k < end && (text[k] == ':' || text[k] == '=')) {
                            out += Span(i, j, keyKind)
                        }
                    }
                    i = j
                }
                c.isDigit() -> {
                    val j = numberEnd(text, i, end)
                    out += Span(i, j, TokenKind.NUMBER)
                    i = j
                }
                else -> i++
            }
        }
    }

    private fun numberEnd(text: String, start: Int, end: Int): Int {
        var j = start
        while (j < end && (text[j].isDigit() || text[j] == '.' || text[j] == '_')) j++
        return j
    }

    // ── per-language scanners ─────────────────────────────────────────

    private fun json(text: String): MutableList<Span> {
        val out = mutableListOf<Span>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> {
                    val end = stringEnd(text, i, '"')
                    val isKey = text.drop(end + 1).firstOrNull { it != ' ' && it != '\t' } == ':'
                    out += Span(i, end + 1, if (isKey) TokenKind.KEY else TokenKind.STRING)
                    i = end + 1
                }
                c.isDigit() || (c == '-' && i + 1 < text.length && text[i + 1].isDigit()) -> {
                    val j = numberEnd(text, i, text.length)
                    out += Span(i, j, TokenKind.NUMBER)
                    i = j
                }
                else -> i++
            }
        }
        return out
    }

    private fun yaml(text: String): MutableList<Span> {
        val out = mutableListOf<Span>()
        var lineStart = 0
        while (lineStart <= text.length) {
            if (lineStart == text.length) break
            var i = lineStart
            var lineEnd = text.indexOf('\n', lineStart).let { if (it == -1) text.length else it }
            // Full-line comment?
            var scan = i
            while (scan < lineEnd && text[scan] == ' ') scan++
            if (scan < lineEnd && text[scan] == '#') {
                out += Span(scan, lineEnd, TokenKind.COMMENT)
                lineStart = lineEnd + 1
                continue
            }
            // Key: "identifier:" at the start of the line (indentation allowed).
            var keyEnd = scan
            while (keyEnd < lineEnd && (isIdentPart(text[keyEnd]) || text[keyEnd] == '-' || text[keyEnd] == '.')) keyEnd++
            if (keyEnd > scan && keyEnd < lineEnd && text[keyEnd] == ':') {
                out += Span(scan, keyEnd, TokenKind.KEY)
                // Inline value after the colon
                addWordTokens(text, keyEnd + 1, lineEnd, null, out)
            } else {
                addWordTokens(text, scan, lineEnd, null, out)
            }
            lineStart = lineEnd + 1
        }
        return out
    }

    private fun markup(text: String): MutableList<Span> {
        val out = mutableListOf<Span>()
        var i = 0
        while (i < text.length) {
            if (text.startsWith("<!--", i)) {
                val end = text.indexOf("-->", i).let { if (it == -1) text.length else it + 3 }
                out += Span(i, end, TokenKind.COMMENT)
                i = end
                continue
            }
            if (text[i] == '<') {
                val tagEnd = text.indexOf('>', i)
                if (tagEnd == -1) break
                val inner = i + 1
                var nameStart = inner
                if (nameStart < text.length && text[nameStart] == '/') nameStart++
                var nameEnd = nameStart
                while (nameEnd < tagEnd && isIdentPart(text[nameEnd])) nameEnd++
                if (nameEnd > nameStart) out += Span(nameStart, nameEnd, TokenKind.TAG)
                // Strings inside the tag
                var j = nameEnd
                while (j < tagEnd) {
                    val c = text[j]
                    if (c == '"' || c == '\'') {
                        val strEnd = stringEnd(text, j, c).coerceAtMost(tagEnd)
                        out += Span(j, strEnd + 1, TokenKind.STRING)
                        j = strEnd + 1
                    } else {
                        j++
                    }
                }
                i = tagEnd + 1
            } else {
                i++
            }
        }
        return out
    }

    private fun css(text: String): MutableList<Span> {
        val out = mutableListOf<Span>()
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("/*", i) -> {
                    val end = text.indexOf("*/", i + 2).let { if (it == -1) text.length else it + 2 }
                    out += Span(i, end, TokenKind.COMMENT)
                    i = end
                }
                text[i] == '"' || text[i] == '\'' -> {
                    val end = stringEnd(text, i, text[i])
                    out += Span(i, end + 1, TokenKind.STRING)
                    i = end + 1
                }
                text[i] == '#' && i + 1 < text.length && text[i + 1].isLetterOrDigit() -> {
                    var j = i + 1
                    while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '-')) j++
                    out += Span(i, j, TokenKind.KEYWORD)
                    i = j
                }
                else -> i++
            }
        }
        return out
    }

    private fun python(text: String): MutableList<Span> {
        val out = mutableListOf<Span>()
        var i = 0
        var lineStart = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '#' -> {
                    val end = text.indexOf('\n', i).let { if (it == -1) text.length else it }
                    out += Span(i, end, TokenKind.COMMENT)
                    i = end
                }
                c == '"' || c == '\'' -> {
                    // Triple-quoted strings
                    val triple = text.startsWith("$c$c$c", i)
                    if (triple) {
                        val close = text.indexOf("$c$c$c", i + 3)
                        val end = if (close == -1) text.length else close + 3
                        out += Span(i, end, TokenKind.STRING)
                        i = end
                    } else {
                        val end = stringEnd(text, i, c)
                        out += Span(i, end + 1, TokenKind.STRING)
                        i = end + 1
                    }
                }
                isIdentStart(c) -> {
                    var j = i + 1
                    while (j < text.length && isIdentPart(text[j])) j++
                    if (keywordAt(text, i, j, PYTHON_KEYWORDS)) out += Span(i, j, TokenKind.KEYWORD)
                    i = j
                }
                c.isDigit() -> {
                    val j = numberEnd(text, i, text.length)
                    out += Span(i, j, TokenKind.NUMBER)
                    i = j
                }
                c == '\n' -> {
                    lineStart = i + 1
                    i++
                }
                else -> i++
            }
        }
        return out
    }

    private fun shell(text: String): MutableList<Span> {
        val out = mutableListOf<Span>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '#' -> {
                    val end = text.indexOf('\n', i).let { if (it == -1) text.length else it }
                    out += Span(i, end, TokenKind.COMMENT)
                    i = end
                }
                c == '"' || c == '\'' -> {
                    val end = stringEnd(text, i, c)
                    out += Span(i, end + 1, TokenKind.STRING)
                    i = end + 1
                }
                isIdentStart(c) -> {
                    var j = i + 1
                    while (j < text.length && isIdentPart(text[j])) j++
                    if (keywordAt(text, i, j, SHELL_KEYWORDS)) out += Span(i, j, TokenKind.KEYWORD)
                    i = j
                }
                c == '$' && i + 1 < text.length && (isIdentStart(text[i + 1]) || text[i + 1] == '{') -> {
                    var j = i + 1
                    while (j < text.length && (isIdentPart(text[j]) || text[j] == '{' || text[j] == '}')) j++
                    out += Span(i, j, TokenKind.KEY)
                    i = j
                }
                c.isDigit() -> {
                    val j = numberEnd(text, i, text.length)
                    out += Span(i, j, TokenKind.NUMBER)
                    i = j
                }
                else -> i++
            }
        }
        return out
    }

    private fun cLike(
        text: String,
        keywords: Set<String>,
        lineComment: String,
        @Suppress("UNUSED_PARAMETER") hashLineComment: Boolean
    ): MutableList<Span> {
        val out = mutableListOf<Span>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                text.startsWith(lineComment, i) -> {
                    val end = text.indexOf('\n', i).let { if (it == -1) text.length else it }
                    out += Span(i, end, TokenKind.COMMENT)
                    i = end
                }
                text.startsWith("/*", i) -> {
                    val end = text.indexOf("*/", i + 2).let { if (it == -1) text.length else it + 2 }
                    out += Span(i, end, TokenKind.COMMENT)
                    i = end
                }
                c == '"' || c == '\'' -> {
                    val end = stringEnd(text, i, c)
                    out += Span(i, end + 1, TokenKind.STRING)
                    i = end + 1
                }
                isIdentStart(c) -> {
                    var j = i + 1
                    while (j < text.length && isIdentPart(text[j])) j++
                    if (keywordAt(text, i, j, keywords)) out += Span(i, j, TokenKind.KEYWORD)
                    i = j
                }
                c.isDigit() -> {
                    val j = numberEnd(text, i, text.length)
                    out += Span(i, j, TokenKind.NUMBER)
                    i = j
                }
                else -> i++
            }
        }
        return out
    }

    private fun markdown(text: String): MutableList<Span> {
        val out = mutableListOf<Span>()
        var lineStart = 0
        while (lineStart < text.length) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it == -1) text.length else it }
            // ATX headings: the whole line is a "keyword" (rendered accent).
            var scan = lineStart
            while (scan < lineEnd && text[scan] == ' ') scan++
            if (scan < lineEnd && text[scan] == '#') {
                out += Span(scan, lineEnd, TokenKind.KEYWORD)
            } else {
                // Inline code spans
                var i = scan
                while (i < lineEnd) {
                    if (text[i] == '`') {
                        val close = text.indexOf('`', i + 1)
                        if (close != -1 && close <= lineEnd) {
                            out += Span(i, close + 1, TokenKind.STRING)
                            i = close + 1
                            continue
                        }
                    }
                    i++
                }
            }
            lineStart = lineEnd + 1
        }
        return out
    }

    private fun config(text: String): MutableList<Span> {
        val out = mutableListOf<Span>()
        var lineStart = 0
        while (lineStart < text.length) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it == -1) text.length else it }
            var scan = lineStart
            while (scan < lineEnd && (text[scan] == ' ' || text[scan] == '\t')) scan++
            if (scan < lineEnd && (text[scan] == '#' || text[scan] == ';')) {
                out += Span(scan, lineEnd, TokenKind.COMMENT)
            } else {
                addWordTokens(text, scan, lineEnd, CONFIG_KEYWORDS, out, keyKind = TokenKind.KEY, lineStart = lineStart)
            }
            lineStart = lineEnd + 1
        }
        return out
    }

    // ── keyword tables ────────────────────────────────────────────────

    private val JS_KEYWORDS = setOf(
        "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete",
        "do", "else", "export", "extends", "false", "finally", "for", "function", "if",
        "import", "in", "instanceof", "let", "new", "null", "of", "return", "static", "super",
        "switch", "this", "throw", "true", "try", "typeof", "undefined", "var", "void",
        "while", "yield", "async", "await", "interface", "type", "enum", "implements",
        "private", "public", "protected", "readonly", "namespace", "declare", "as"
    )

    private val JAVA_KEYWORDS = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
        "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
        "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
        "interface", "long", "native", "new", "package", "private", "protected", "public",
        "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
        "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null",
        "var", "record", "sealed"
    )

    private val KOTLIN_KEYWORDS = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
        "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
        "true", "try", "typealias", "val", "var", "when", "while", "by", "get", "set",
        "import", "where", "abstract", "actual", "annotation", "companion", "const",
        "crossinline", "data", "enum", "expect", "external", "final", "infix", "inline",
        "inner", "internal", "lateinit", "noinline", "open", "operator", "out", "override",
        "private", "protected", "public", "reified", "sealed", "suspend", "tailrec", "vararg"
    )

    private val C_KEYWORDS = setOf(
        "auto", "break", "case", "char", "const", "continue", "default", "do", "double",
        "else", "enum", "extern", "float", "for", "goto", "if", "inline", "int", "long",
        "register", "restrict", "return", "short", "signed", "sizeof", "static", "struct",
        "switch", "typedef", "union", "unsigned", "void", "volatile", "while", "class",
        "namespace", "template", "typename", "public", "private", "protected", "new",
        "delete", "bool", "true", "false", "nullptr", "using", "catch", "try", "throw"
    )

    private val PYTHON_KEYWORDS = setOf(
        "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
        "elif", "else", "except", "false", "finally", "for", "from", "global", "if", "import",
        "in", "is", "lambda", "none", "nonlocal", "not", "or", "pass", "raise", "return",
        "true", "try", "while", "with", "yield", "self"
    )

    private val SHELL_KEYWORDS = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case",
        "esac", "function", "in", "return", "exit", "local", "export", "readonly", "shift",
        "break", "continue", "echo", "printf", "set", "unset", "source", "alias", "exec",
        "trap", "eval", "cd", "true", "false", "set", "declare"
    )

    private val CONFIG_KEYWORDS = setOf(
        "true", "false", "yes", "no", "on", "off", "null", "none"
    )
}
