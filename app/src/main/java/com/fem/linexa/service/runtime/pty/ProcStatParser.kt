package com.fem.linexa.service.runtime.pty

/**
 * Pure parser for the foreground process group of a pty child.
 *
 * `/proc/<pid>/stat` is space-separated except for the executable name in
 * parentheses, which may itself contain spaces and parentheses. Parsing from
 * the LAST `)` avoids every such case; the next fields are:
 *
 *   `state ppid pgrp session tty_nr tpgid ...`
 *
 * `tpgid` is the id of the foreground process group of the terminal, or `-1`
 * when no process group is in the foreground. The shell's own pid is the
 * common tpgid while it is waiting; an interactive program (`python3`, `node`,
 * `htop`, `vim`) becomes the tpgid while it runs.
 */
object ProcStatParser {

    private val TRAILING_WHITESPACE = Regex("\\s+")

    /**
     * The foreground process-group id in [stat], or null when the text is
     * malformed or the process has no foreground group (`tpgid <= 0`).
     */
    fun foregroundProcessGroupId(stat: String): Int? {
        val close = stat.lastIndexOf(')')
        if (close < 0 || close + 1 >= stat.length) return null
        val fields = stat.substring(close + 1)
            .trim()
            .split(TRAILING_WHITESPACE)
        // fields[0]=state, [1]=ppid, [2]=pgrp, [3]=session, [4]=tty_nr, [5]=tpgid
        val tpgid = fields.getOrNull(5)?.toIntOrNull() ?: return null
        return tpgid.takeIf { it > 0 }
    }

    /** Clean a `/proc/<pid>/comm` value for a window subtitle. */
    fun processName(comm: String?): String? = comm
        ?.substringBefore('\n')
        ?.trim()
        ?.take(64)
        ?.ifBlank { null }
}

/**
 * Reads the foreground process of one pty child from procfs.
 *
 * Kept as a small, dependency-free helper so [TermuxPtySession] stays the only
 * `com.termux.*` importer and the parser above remains JVM-testable. Every
 * read is best-effort: a dead process, a permission boundary or a racing
 * process-group change simply yields null.
 */
object ProcForegroundProcess {

    fun foregroundProcessName(pid: Int): String? {
        if (pid <= 0) return null
        val stat = readFile("/proc/$pid/stat") ?: return null
        val tpgid = ProcStatParser.foregroundProcessGroupId(stat) ?: return null
        val comm = readFile("/proc/$tpgid/comm") ?: return null
        return ProcStatParser.processName(comm)
    }

    private fun readFile(path: String): String? = try {
        val file = java.io.File(path)
        if (file.isFile) file.readText() else null
    } catch (_: Exception) {
        null
    }
}
