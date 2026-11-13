package com.fem.linexa.ui.screens.settings

import com.fem.linexa.core.format.FileSizeFormatter
import com.fem.linexa.domain.settings.ColorTheme
import com.fem.linexa.domain.settings.ThemeMode

/**
 * UI/UX Phase 4 — what the Global Settings rows *say*, as a pure Compose-free type.
 *
 * Same rationale as [com.fem.linexa.ui.components.WorkspaceCardContent] (§1.127(c))
 * and [com.fem.linexa.ui.screens.home.HomeChromeState] (§1.128(c)): the interesting
 * cases here are decided by data the developer's device happens to have, so stating
 * them in a composable means verifying them by looking at a screenshot of one
 * install. The cases that actually matter:
 *
 *  * **A log that does not exist yet.** A fresh install has written nothing, so the
 *    Diagnostics row must read as "nothing to clear" and its action must be
 *    disabled — not "0 B" next to an enabled Clear button that does nothing.
 *  * **A size that rounds to nothing.** `FileSizeFormatter` renders 400 bytes as
 *    "400 B", but the *first* log line already makes the file non-empty; a row that
 *    said "empty" for anything under a KB would claim there is nothing to clear
 *    while a file exists.
 *
 * The terminal font-size row this type used to carry is deliberately gone
 * (2026-08-29 terminal migration): the terminal owns its appearance, and its
 * font is changed at the terminal (pinch, or the Haven ⋯ sheet), not from
 * app-level settings.
 *
 * Sizes are formatted through the app-wide [FileSizeFormatter] rather than a local
 * expression so the Diagnostics row matches the Home card and the file manager.
 */
data class GlobalSettingsContent(
    val themeMode: ThemeMode,
    val colorTheme: ColorTheme,
    val logSummary: LogSummary
) {

    /**
     * The Diagnostics row's two possible readings.
     *
     * A sealed pair rather than a nullable size, because "no log yet" and "a log of
     * 0 bytes" must render differently and only one of them offers a Clear action.
     */
    sealed interface LogSummary {
        /** Nothing has been logged: no files exist. Clear is offered but disabled. */
        data object Empty : LogSummary

        /** [sizeLabel] across [fileCount] files, both real. */
        data class Present(val sizeLabel: String, val fileCount: Int) : LogSummary
    }

    companion object {

        fun from(
            themeMode: ThemeMode,
            colorTheme: ColorTheme,
            logSizeBytes: Long,
            logFileCount: Int
        ): GlobalSettingsContent = GlobalSettingsContent(
            themeMode = themeMode,
            // Pass-through, never re-derived: the row renders the selection, so
            // a value must not be silently normalized here or the picker could
            // disagree with what is persisted.
            colorTheme = colorTheme,
            logSummary = summarizeLog(logSizeBytes, logFileCount)
        )

        /**
         * Emptiness is decided by the **file count**, not by the byte total.
         *
         * A zero-length file still exists, still occupies an inode, and is still
         * something Clear will remove; reporting it as Empty would disable the
         * action while the thing it acts on is there. The reverse guard matters too:
         * a negative or zero size with files present is reported as `0 B` present,
         * because that is the honest reading.
         */
        private fun summarizeLog(sizeBytes: Long, fileCount: Int): LogSummary =
            if (fileCount <= 0) {
                LogSummary.Empty
            } else {
                LogSummary.Present(
                    sizeLabel = FileSizeFormatter.format(sizeBytes),
                    fileCount = fileCount
                )
            }
    }
}
