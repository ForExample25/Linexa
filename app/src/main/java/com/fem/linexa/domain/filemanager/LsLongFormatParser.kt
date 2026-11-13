package com.fem.linexa.domain.filemanager

/**
 * §1.115 — pure parser for `ls -la` long-format output.
 *
 * Extracted from `WorkspaceFileManagerImpl` (which parsed by fixed field
 * index and got the columns wrong) so it can be JVM-tested against real
 * output from both GNU coreutils and busybox.
 *
 * ## Why this is not an index-based split
 *
 * The old implementation did `parts.drop(7)` for the name. The real layout of
 * a coreutils/busybox long listing is:
 *
 * ```
 * -rw-------. 1 u0_a511 u0_a511 12 Aug 10 19:31 name with spaces.txt
 *  [0]        [1] [2]    [3]    [4] [5] [6] [7]  [8...]
 * ```
 *
 * so the name starts at index **8**, and index 7 is the time-or-year column.
 * Every entry therefore came out named `"19:31 name with spaces.txt"` and
 * every derived path was wrong — which made directory taps list nothing and
 * made `rm -rf` "succeed" (exit 0 on a nonexistent path) while deleting
 * nothing.
 *
 * A fixed index is not merely off by one here, it cannot work at all:
 *
 * - **Names contain spaces**, and joining split tokens back with a single
 *   space corrupts any name with a run of spaces. The name is therefore taken
 *   as a raw substring of the original line, not as re-joined tokens.
 * - **Device nodes print `major, minor` where a file prints one size**
 *   (`crw-rw-rw-. 1 root root 1, 3 Jan 1 1970 null`), shifting every later
 *   column by one.
 * - **Time style varies**: the classic format is three columns
 *   (`Aug 10 19:31` / `Jan  1  1970`), while `--time-style=long-iso` is two
 *   (`2026-08-10 19:31`).
 * - **Symlinks append `-> target`**, which is not part of the name.
 * - GNU appends an ACL/SELinux marker (`.`/`+`) to the mode string, busybox
 *   does not.
 *
 * So the parser locates the **date block by pattern** and derives the size
 * and name from its position, instead of trusting any absolute index. A line
 * whose mode field is not a valid mode string is skipped, which also drops
 * the `total 15` header without a special case for it.
 */
internal object LsLongFormatParser {

    /**
     * Mode string: type char, 9 permission chars, optional GNU ACL/SELinux
     * marker. Type chars cover regular/dir/link/block/char/fifo/socket and
     * Solaris-style door, matching what coreutils can emit.
     */
    private val MODE = Regex("^[-bcdlpsDC][-rwxsStTlL]{9}[.+@]?$")

    private val MONTHS = setOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )

    private val DAY = Regex("^\\d{1,2}$")
    private val TIME_OR_YEAR = Regex("^(\\d{1,2}:\\d{2}(:\\d{2})?|\\d{4})$")
    private val ISO_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val ISO_TIME = Regex("^\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?([+-]\\d{2}:?\\d{2})?$")
    private val DIGITS = Regex("^\\d+$")

    /** A device-node size column, either `1,` + `3` or a fused `1,3`. */
    private val DEVICE_FUSED = Regex("^\\d+,\\d+$")

    /** The separator `ls` puts between a symlink's name and its target. */
    private const val LINK_ARROW = " -> "

    private data class Token(val text: String, val start: Int, val end: Int)

    /**
     * Parse [output] (the stdout of `ls -la <directory>`) into entries whose
     * [FileInfo.path] is resolved against [directory].
     *
     * `.` and `..` are dropped: the UI navigates with breadcrumbs, and a
     * `..` entry that a user could delete is a footgun.
     */
    fun parse(directory: String, output: String): List<FileInfo> =
        output.lines().mapNotNull { parseLine(directory, it) }

    private fun parseLine(directory: String, rawLine: String): FileInfo? {
        val line = rawLine.trimEnd('\r')
        if (line.isBlank()) return null

        val tokens = tokenize(line)
        if (tokens.size < 6) return null

        val mode = tokens[0].text
        if (!MODE.matches(mode)) return null

        // Link count is always present in a long listing; its absence means
        // this is not a listing line (a `ls -R` directory header, say).
        if (!DIGITS.matches(tokens[1].text)) return null

        val dateStart = findDateStart(tokens) ?: return null
        val dateEnd = dateStart + dateLength(tokens, dateStart)
        if (dateEnd > tokens.size) return null

        // The size column is whatever sits directly before the date block.
        // Device nodes have `major, minor` there and no meaningful byte size.
        val sizeColumn = readSizeColumn(tokens, dateStart)

        val lastModified = (dateStart until dateEnd).joinToString(" ") { tokens[it].text }

        val nameField = nameAfter(line, tokens[dateEnd - 1].end) ?: return null
        val isSymlink = mode.startsWith("l")
        val name: String
        val linkTarget: String?
        if (isSymlink) {
            val arrow = nameField.indexOf(LINK_ARROW)
            if (arrow >= 0) {
                name = nameField.substring(0, arrow)
                linkTarget = nameField.substring(arrow + LINK_ARROW.length).takeIf { it.isNotEmpty() }
            } else {
                name = nameField
                linkTarget = null
            }
        } else {
            name = nameField
            linkTarget = null
        }
        if (name.isEmpty() || name == "." || name == "..") return null

        return FileInfo(
            name = name,
            path = joinPath(directory, name),
            isDirectory = mode.startsWith("d"),
            isSymlink = isSymlink,
            linkTarget = linkTarget,
            size = sizeColumn ?: 0L,
            permissions = mode,
            lastModified = lastModified,
            hasByteSize = sizeColumn != null
        )
    }

    /** Split on whitespace runs, keeping each token's offsets in the line. */
    private fun tokenize(line: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < line.length) {
            while (i < line.length && line[i].isWhitespace()) i++
            if (i >= line.length) break
            val start = i
            while (i < line.length && !line[i].isWhitespace()) i++
            tokens += Token(line.substring(start, i), start, i)
        }
        return tokens
    }

    /**
     * Index of the first token of the date block, searched from the earliest
     * position it can legally occupy (mode, links, owner, size at minimum).
     */
    private fun findDateStart(tokens: List<Token>): Int? {
        for (i in 3 until tokens.size) {
            if (dateLength(tokens, i) > 0) return i
        }
        return null
    }

    /** Length in tokens of a date block starting at [i], or 0 if none does. */
    private fun dateLength(tokens: List<Token>, i: Int): Int {
        // Classic: "Aug 10 19:31" or "Jan  1  1970"
        if (i + 2 < tokens.size &&
            tokens[i].text in MONTHS &&
            DAY.matches(tokens[i + 1].text) &&
            TIME_OR_YEAR.matches(tokens[i + 2].text)
        ) {
            return 3
        }
        // Reversed order used by some locales: "10 Aug 19:31"
        if (i + 2 < tokens.size &&
            DAY.matches(tokens[i].text) &&
            tokens[i + 1].text in MONTHS &&
            TIME_OR_YEAR.matches(tokens[i + 2].text)
        ) {
            return 3
        }
        // long-iso / full-iso: "2026-08-10 19:31"
        if (i + 1 < tokens.size &&
            ISO_DATE.matches(tokens[i].text) &&
            ISO_TIME.matches(tokens[i + 1].text)
        ) {
            return 2
        }
        return 0
    }

    /**
     * Byte size, taken from the column before the date block. Returns null for
     * a device node (`major, minor` — no byte count exists) so the UI can say
     * so instead of printing "0 B" for a node that is not empty.
     */
    private fun readSizeColumn(tokens: List<Token>, dateStart: Int): Long? {
        val prev = tokens.getOrNull(dateStart - 1)?.text ?: return null
        if (DEVICE_FUSED.matches(prev)) return null
        // `1,` `3` split across two tokens.
        if (tokens.getOrNull(dateStart - 2)?.text?.endsWith(",") == true) return null
        if (DIGITS.matches(prev)) return prev.toLongOrNull()
        return null
    }

    /**
     * The name field: everything after the date block, minus the single run of
     * column padding. Taken from the raw line so runs of spaces inside a name
     * survive.
     */
    private fun nameAfter(line: String, dateEndOffset: Int): String? {
        var i = dateEndOffset
        while (i < line.length && line[i].isWhitespace()) i++
        if (i >= line.length) return null
        return line.substring(i)
    }

    /** Join [directory] and [name] without producing a `//` or dropping a `/`. */
    fun joinPath(directory: String, name: String): String {
        val base = if (directory.isEmpty()) "/" else directory
        return if (base.endsWith("/")) "$base$name" else "$base/$name"
    }
}
