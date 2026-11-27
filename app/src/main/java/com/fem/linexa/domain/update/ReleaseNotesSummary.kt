package com.fem.linexa.domain.update

/**
 * Derives the one-line summary the update card shows from a release-notes
 * body — a pure rule so the extraction is testable against real notes
 * (GitHub bodies are markdown: headings, link-only nav lines, horizontal
 * rules, bilingual sections).
 *
 * The heuristic: the first line that is prose — non-empty, not a markdown
 * heading (`#…`), not a horizontal rule, not a bare link/anchor line, and at
 * least [MIN_SUMMARY_LENGTH] characters — with bold/inline-link markup
 * stripped. Returns null when no such line exists (the card then shows no
 * summary; the full notes are still expandable).
 */
object ReleaseNotesSummary {

    private const val MIN_SUMMARY_LENGTH = 40

    fun short(notes: String?): String? {
        if (notes.isNullOrBlank()) return null
        val line = notes.lineSequence()
            .map { it.trim() }
            .firstOrNull(::isProseLine)
            ?: return null
        return stripMarkdown(line).trim().takeIf { it.isNotEmpty() }
    }

    private fun isProseLine(line: String): Boolean {
        if (line.isEmpty()) return false
        if (line.startsWith("#")) return false // markdown heading
        if (line.startsWith("---") || line.startsWith("***") || line.startsWith("===")) return false // rule
        if (line.startsWith("```")) return false // code fence
        val withoutMarkup = stripMarkdown(line)
        // A nav line like "**English** · [فارسی](#فارسی)" reduces to almost nothing.
        if (withoutMarkup.length < MIN_SUMMARY_LENGTH) return false
        // Markdown links only (the whole line is one link) are not prose.
        if (withoutMarkup.none { it.isLetterOrDigit() }) return false
        return true
    }

    /** Strips the inline markdown the summary line tends to carry. */
    private fun stripMarkdown(line: String): String = line
        // [text](target) → text
        .replace(Regex("\\[([^]]*)]\\([^)]*\\)")) { match -> match.groupValues[1] }
        // **bold** / __bold__ / *italic* / _italic_ → content
        .replace(Regex("\\*\\*(.+?)\\*\\*")) { it.groupValues[1] }
        .replace(Regex("__(.+?)__")) { it.groupValues[1] }
        .replace(Regex("\\*(.+?)\\*")) { it.groupValues[1] }
        .replace(Regex("_(.+?)_")) { it.groupValues[1] }
}
