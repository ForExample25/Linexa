package com.fem.linexa.ui.screens.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #9 (2026-09-03): file-type detection and the syntax highlighter —
 * the pure halves of the QuickEdit/ZArchiver-style editor.
 */
class FileTypesTest {

    @Test
    fun `the requirement's editor targets are editable`() {
        for (name in listOf(
            "notes.txt", "data.json", "config.yaml", "layout.xml", "index.html",
            "style.css", "app.js", "app.ts", "Main.java", "App.kt",
            "main.py", "script.sh", "README.md", "subtitles.srt", "server.conf"
        )) {
            assertTrue("must be editable: $name", FileTypes.isEditable(name))
        }
    }

    @Test
    fun `common dotfiles are editable and binaries are not`() {
        assertTrue(FileTypes.isEditable(".gitignore"))
        assertTrue(FileTypes.isEditable("Dockerfile"))
        assertTrue(FileTypes.isEditable("Makefile"))
        assertFalse("a binary must not open in the editor", FileTypes.isEditable("photo.jpg"))
        assertFalse(FileTypes.isEditable("archive.zip"))
        assertFalse(FileTypes.isEditable("libnative.so"))
        assertFalse(FileTypes.isEditable("sound.mp3"))
    }

    @Test
    fun `languages map from extensions`() {
        assertEquals(FileTypes.Language.JSON, FileTypes.languageOf("data.json"))
        assertEquals(FileTypes.Language.YAML, FileTypes.languageOf("compose.yml"))
        assertEquals(FileTypes.Language.MARKUP, FileTypes.languageOf("index.html"))
        assertEquals(FileTypes.Language.JS, FileTypes.languageOf("index.tsx"))
        assertEquals(FileTypes.Language.KOTLIN, FileTypes.languageOf("App.kt"))
        assertEquals(FileTypes.Language.PYTHON, FileTypes.languageOf("main.py"))
        assertEquals(FileTypes.Language.SHELL, FileTypes.languageOf("run.sh"))
        assertEquals(FileTypes.Language.SHELL, FileTypes.languageOf("Dockerfile"))
        assertEquals(FileTypes.Language.PLAIN, FileTypes.languageOf("blob.bin"))
    }
}

class SyntaxHighlighterTest {

    private fun kinds(text: String, language: FileTypes.Language) =
        SyntaxHighlighter.highlight(text, language).map { it.kind }

    @Test
    fun `plain text produces no spans`() {
        assertTrue(SyntaxHighlighter.highlight("just plain text\n", FileTypes.Language.PLAIN).isEmpty())
    }

    @Test
    fun `json distinguishes keys from strings and numbers`() {
        val spans = SyntaxHighlighter.highlight("""{"name": "linexa", "count": 42}""", FileTypes.Language.JSON)
        val kindAt = { index: Int ->
            spans.firstOrNull { index >= it.start && index < it.end }?.kind
        }
        // "name" is a KEY (followed by colon), "linexa" a STRING, 42 a NUMBER.
        assertEquals(SyntaxHighlighter.TokenKind.KEY, kindAt(1))
        assertEquals(SyntaxHighlighter.TokenKind.STRING, kindAt(9))
        assertEquals(SyntaxHighlighter.TokenKind.NUMBER, kindAt(29))
    }

    @Test
    fun `shell comments and strings are tagged`() {
        val kinds = kinds("# a comment\necho \"hello\"\n", FileTypes.Language.SHELL)
        assertTrue(SyntaxHighlighter.TokenKind.COMMENT in kinds)
        assertTrue(SyntaxHighlighter.TokenKind.STRING in kinds)
    }

    @Test
    fun `python triple-quoted strings stay one span`() {
        val text = "x = \"\"\"\nblock\n\"\"\"\n"
        val spans = SyntaxHighlighter.highlight(text, FileTypes.Language.PYTHON)
        val strings = spans.filter { it.kind == SyntaxHighlighter.TokenKind.STRING }
        assertEquals("the whole block is one STRING span", 1, strings.size)
    }

    @Test
    fun `keywords are found in c-like and python sources`() {
        assertTrue(SyntaxHighlighter.TokenKind.KEYWORD in kinds("fun main() { val x = 1 }", FileTypes.Language.KOTLIN))
        assertTrue(SyntaxHighlighter.TokenKind.KEYWORD in kinds("def main():\n    return None\n", FileTypes.Language.PYTHON))
        assertTrue(SyntaxHighlighter.TokenKind.KEYWORD in kinds("public static void main()", FileTypes.Language.JAVA))
    }

    @Test
    fun `markup tags and comments are tagged`() {
        val kinds = kinds("<!-- note --><html><body></body></html>", FileTypes.Language.MARKUP)
        assertTrue(SyntaxHighlighter.TokenKind.COMMENT in kinds)
        assertTrue(SyntaxHighlighter.TokenKind.TAG in kinds)
    }

    @Test
    fun `spans never overlap and stay in bounds`() {
        val text = "# c\n\"s\" 42 key: value\n<!--x-->\"unclosed\n"
        for (language in FileTypes.Language.entries) {
            val spans = SyntaxHighlighter.highlight(text, language)
            var lastEnd = 0
            for (span in spans) {
                assertTrue("span starts in bounds", span.start >= 0)
                assertTrue("span ends in bounds", span.end <= text.length)
                assertTrue("no overlap", span.start >= lastEnd)
                lastEnd = span.end
            }
        }
    }

    @Test
    fun `yaml keys at line starts are tagged`() {
        val spans = SyntaxHighlighter.highlight("user: linexa\nhome: /home/linexa\n", FileTypes.Language.YAML)
        val keys = spans.filter { it.kind == SyntaxHighlighter.TokenKind.KEY }
        assertEquals(2, keys.size)
    }
}
