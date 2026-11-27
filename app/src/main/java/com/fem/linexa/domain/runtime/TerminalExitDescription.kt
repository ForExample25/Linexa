package com.fem.linexa.domain.runtime

/**
 * Terminal audit finding 027 — one pure table that turns a PTY exit status
 * into a **user-facing summary and an actionable hint**.
 *
 * The terminal's exit status is the only evidence a dead PTY leaves behind,
 * and the pre-027 code rendered all of them as the same string
 * ("exited unexpectedly (code N)"). The conventional meanings (`127` = command
 * or shell not found, `137` = 128 + SIGKILL, …) are useful diagnostics precisely
 * because they narrow where to look, but they cannot *prove* a cause: a guest
 * may `exit 127` itself. The table therefore names the conventional meaning and
 * puts the concrete next step in [hint]; it never asserts why the status arose.
 *
 * ## Two conventions, one table
 *
 * [com.fem.linexa.service.runtime.pty.PtySession.awaitExit] returns the status
 * from `waitpid(2)` (terminal-emulator's JNI `waitFor`):
 *
 * - a normal exit is `WEXITSTATUS` in `0..255` — this is where the shell
 *   conventions `126`/`127`/`137`/`139` appear;
 * - a signal death is `-WTERMSIG` — so `-9` is SIGKILL, not `137`.
 *
 * Both rows exist because both can be observed through this one method. [of]
 * keeps the raw code in [exitCode] and in [reason], so the UI can always show
 * exactly what the kernel reported.
 *
 * This type is deliberately pure: the manager owns the state transition, the
 * UI owns rendering, and only the vocabulary lives here.
 */
data class TerminalExitDescription(
    /** The raw status from [com.fem.linexa.service.runtime.pty.PtySession.awaitExit]. */
    val exitCode: Int,
    /** The predicate phrase, e.g. `exited: command or shell not found`. */
    val summary: String,
    /**
     * The concrete next step, or null when there is nothing honest to suggest.
     * Never a claim of cause — only where to look.
     */
    val hint: String?
) {
    /**
     * The manager's reason string. Carries the terminal id for the session log
     * and the raw exit code, because the code is the one fact that is certain.
     */
    fun reason(terminalId: String): String = "Terminal $terminalId $summary (code $exitCode)"

    companion object {
        /**
         * Finding 027's table. Unknown codes stay generic — a made-up meaning
         * would be worse than the raw number — and the hint points at the
         * session output instead.
         */
        fun of(exitCode: Int): TerminalExitDescription = when (exitCode) {
            0 -> TerminalExitDescription(
                exitCode = 0,
                summary = "closed",
                hint = null
            )
            126 -> TerminalExitDescription(
                exitCode = 126,
                summary = "exited: command not executable",
                hint = "Check that the file is executable and that its interpreter (shebang) exists."
            )
            127 -> TerminalExitDescription(
                exitCode = 127,
                summary = "exited: command or shell not found",
                hint = "Check launch.shell and environment.shell, and that the command exists in this image."
            )
            137 -> TerminalExitDescription(
                exitCode = 137,
                summary = "exited as if killed by SIGKILL",
                hint = "A guest process may have been killed; Android may have reclaimed memory."
            )
            139 -> TerminalExitDescription(
                exitCode = 139,
                summary = "exited as if killed by SIGSEGV",
                hint = "A guest binary or its interpreter crashed. Check the image and the command."
            )
            in 1..Int.MAX_VALUE -> TerminalExitDescription(
                exitCode = exitCode,
                summary = "exited unexpectedly",
                hint = "Open diagnostics for the session output."
            )
            else -> signal(exitCode)
        }

        /**
         * Signal deaths arrive negated. Only the signals a terminal user can
         * act on get a name; the rest keep the number.
         */
        private fun signal(exitCode: Int): TerminalExitDescription {
            val number = -exitCode
            return when (number) {
                9 -> TerminalExitDescription(
                    exitCode = exitCode,
                    summary = "was killed by SIGKILL",
                    hint = "Android may have reclaimed the terminal to free memory."
                )
                11 -> TerminalExitDescription(
                    exitCode = exitCode,
                    summary = "was killed by SIGSEGV",
                    hint = "A guest binary crashed with a segmentation fault."
                )
                15 -> TerminalExitDescription(
                    exitCode = exitCode,
                    summary = "was killed by SIGTERM",
                    hint = "The guest process was asked to terminate."
                )
                else -> TerminalExitDescription(
                    exitCode = exitCode,
                    summary = "was killed by signal $number",
                    hint = null
                )
            }
        }
    }
}
