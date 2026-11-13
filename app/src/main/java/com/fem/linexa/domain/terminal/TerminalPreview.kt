package com.fem.linexa.domain.terminal

/**
 * Bounded, display-safe terminal preview text.
 *
 * The preview is produced from a real session's emulator screen (or, for
 * backends without one, the session's own recent output). It is always a
 * snapshot of ONE session — there is no shared buffer and no synthetic
 * placeholder output.
 *
 * The rules are intentionally conservative because this text is painted inside
 * a small card:
 *  - normalize CRLF/CR to LF;
 *  - strip ANSI/OSC escape sequences and remaining C0 controls;
 *  - trim trailing whitespace per line and drop blank leading/trailing lines;
 *  - keep at most [maxLines] of the most RECENT output and [maxChars] chars.
 */
object TerminalPreview {

    /** Default line budget for an overview card's preview. */
    const val DEFAULT_MAX_LINES: Int = 12

    /** Default character budget for an overview card's preview. */
    const val DEFAULT_MAX_CHARS: Int = 1_600

    private val ANSI_CSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
    private val ANSI_OSC = Regex("\u001B].*?(?:\u0007|\u001B\\\\)")
    private val OTHER_ESCAPE = Regex("\u001B[@-_]")
    private val C0_CONTROLS = Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]")
    private val TRAILING_SPACES = Regex("[ \\t]+$")
    private val EXCESS_BLANK_LINES = Regex("\n{3,}")

    fun sanitize(
        raw: String,
        maxLines: Int = DEFAULT_MAX_LINES,
        maxChars: Int = DEFAULT_MAX_CHARS
    ): String {
        if (raw.isEmpty() || maxLines <= 0 || maxChars <= 0) return ""
        var text = raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(ANSI_OSC, "")
            .replace(ANSI_CSI, "")
            .replace(OTHER_ESCAPE, "")
            .replace("\t", "    ")
            .replace(C0_CONTROLS, "")
        text = text.lineSequence()
            .map { it.replace(TRAILING_SPACES, "") }
            .joinToString("\n")
            .replace(EXCESS_BLANK_LINES, "\n\n")
            .trim('\n')

        if (text.isBlank()) return ""
        val lines = text.split('\n')
        text = lines.takeLast(maxLines).joinToString("\n").trim('\n')
        if (text.length > maxChars) text = text.takeLast(maxChars)
        return text
    }
}
