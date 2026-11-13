package com.fem.linexa.domain.network

/**
 * §21 — Idempotent /etc/hosts merging for a workspace's hostname entries.
 *
 * The naive approach — writing a fresh /etc/hosts with Linexa's entries —
 * destroys whatever the user (or another tool) put there, and re-running
 * it duplicates entries on every start. This merger instead owns *only*
 * its own clearly marked block:
 *
 * - any previous `BEGIN…END` block written by this class is removed
 *   (inclusive of the markers) and re-appended freshly — never overwrite
 *   user-managed configuration, remove only entries Linexa owns;
 * - every non-managed line is preserved byte-for-byte, in order;
 * - merging the same entries twice yields exactly the same output
 *   (idempotent), so start/stop/start cycles never grow the file.
 *
 * Pure string work (no Android, no exec): the caller writes the result
 * into the guest through the existing file/exec channels.
 */
object HostsFileMerger {

    /** First line of the block this class owns. */
    const val BEGIN_MARKER = "# BEGIN Linexa managed entries"

    /** Last line of the block this class owns. */
    const val END_MARKER = "# END Linexa managed entries"

    /**
     * Merge [managedEntries] into [existingContent]:
     *
     * 1. the previous managed block (from the first [BEGIN_MARKER] line
     *    through its matching [END_MARKER] line, both inclusive; an
     *    unpaired/truncated `BEGIN` strips to end of file, an unpaired
     *    `END` is treated as user content and left alone) is removed;
     * 2. what remains is kept byte-for-byte, with trailing whitespace
     *    trimmed so the join point is deterministic;
     * 3. the fresh managed block is appended — after one blank line when
     *    the remaining content is non-empty, directly otherwise (and with
     *    no block at all when [managedEntries] sanitizes to nothing).
     *
     * Entries are sanitized first: trimmed, blank entries dropped, and any
     * entry containing a newline dropped outright — a multi-line "entry"
     * would smuggle arbitrary extra lines into /etc/hosts (hosts files are
     * line-oriented; there is no way to quote a newline into one entry).
     */
    fun merge(existingContent: String?, managedEntries: List<String>): String {
        val base = stripManagedBlock(existingContent).trimEnd()
        val entries = managedEntries.mapNotNull { sanitizeEntry(it) }
        if (entries.isEmpty()) return base
        val block = managedBlock(entries)
        return if (base.isEmpty()) block else "$base\n\n$block"
    }

    /**
     * The managed entries for a workspace advertising [hostname]:
     * loopback v4/v6 localhost lines (always — the pre-merger behaviour
     * hardcoded them, and a rootfs without them breaks sudo and X) plus
     * one line pointing [hostname] at 127.0.0.1. The hostname is
     * lowercased and stripped to hostname-legal characters (letters,
     * digits, hyphen, dot); if nothing survives — or the caller had no
     * hostname — the hostname line is simply absent rather than written
     * as garbage.
     */
    fun managedEntriesFor(hostname: String): List<String> {
        val entries = mutableListOf("127.0.0.1 localhost", "::1 localhost")
        val clean = hostname.lowercase().filter { it.isLetterOrDigit() || it == '-' || it == '.' }
        if (clean.isNotEmpty()) entries.add("127.0.0.1 $clean")
        return entries
    }

    /** Trim one entry; null when it is blank or spans lines (see [merge]). */
    private fun sanitizeEntry(entry: String): String? {
        val trimmed = entry.trim()
        if (trimmed.isEmpty() || trimmed.contains('\n') || trimmed.contains('\r')) return null
        return trimmed
    }

    /** BEGIN + one entry per line + END. */
    private fun managedBlock(entries: List<String>): String =
        BEGIN_MARKER + "\n" + entries.joinToString("\n") + "\n" + END_MARKER

    /**
     * Remove the previously owned block from [existingContent] (null →
     * empty string), leaving every other line — including line endings —
     * untouched.
     */
    private fun stripManagedBlock(existingContent: String?): String {
        if (existingContent == null) return ""
        val lines = existingContent.split('\n').toMutableList()
        val begin = lines.indexOfFirst { it.trim() == BEGIN_MARKER }
        if (begin < 0) return existingContent
        val relativeEnd = lines.subList(begin, lines.size).indexOfFirst { it.trim() == END_MARKER }
        val end = if (relativeEnd >= 0) begin + relativeEnd else lines.size - 1
        lines.subList(begin, end + 1).clear()
        // Collapse the removed region to a single blank line: the user lines
        // that surrounded the old block keep a separator between them, so the
        // surviving layout ("before", blank, "after") is stable across
        // merge cycles instead of gluing the neighbours together.
        lines.add(begin, "")
        return lines.joinToString("\n")
    }
}
