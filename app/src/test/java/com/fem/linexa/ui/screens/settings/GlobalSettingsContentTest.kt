package com.fem.linexa.ui.screens.settings

import com.fem.linexa.domain.settings.ColorTheme
import com.fem.linexa.domain.settings.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI/UX Phase 4 — what the Global Settings rows say.
 *
 * Same reason [com.fem.linexa.ui.components.WorkspaceCardContent] has tests
 * (§1.127(c)): the cases that matter are decided by data the developer's own device
 * happens to have, so leaving these decisions inside composables means verifying them
 * by looking at one install's screenshot. There is no Compose UI harness in this repo
 * (§1.124(a)), which is why the decisions are a pure type in the first place.
 *
 * The font-row tests this class used to carry were removed with the row itself in
 * the 2026-08-29 terminal migration: the terminal owns its appearance, its size is
 * persisted terminal-locally in [com.fem.linexa.domain.settings.TerminalFontPreference],
 * and the clamp/step rules live (and are tested) with the terminal, not with an
 * app-level settings row.
 */
class GlobalSettingsContentTest {

    private fun content(
        themeMode: ThemeMode = ThemeMode.SYSTEM,
        colorTheme: ColorTheme = ColorTheme.ALPINE_BLUE,
        logSizeBytes: Long = 0L,
        logFileCount: Int = 0
    ) = GlobalSettingsContent.from(themeMode, colorTheme, logSizeBytes, logFileCount)

    @Test
    fun theThemeModeIsPassedThroughUnchanged() {
        // The row renders the selection; it must not re-derive or normalize it, or
        // the selected segment could disagree with what is persisted.
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, content(themeMode = mode).themeMode)
        }
    }

    @Test
    fun noLogFilesReadsAsEmptyRatherThanZeroBytes() {
        // A fresh install has written nothing. "0 B" next to an enabled Clear button
        // claims there is something to remove.
        assertEquals(
            GlobalSettingsContent.LogSummary.Empty,
            content(logSizeBytes = 0L, logFileCount = 0).logSummary
        )
    }

    @Test
    fun anEmptyButExistingLogFileIsPresentNotEmpty() {
        // Emptiness is the FILE COUNT, not the byte total: a zero-length file still
        // exists and is still something Clear removes, so the action must stay
        // available. This is the case that would break if the check were on bytes.
        val summary = content(logSizeBytes = 0L, logFileCount = 1).logSummary
        assertTrue(summary is GlobalSettingsContent.LogSummary.Present)
        summary as GlobalSettingsContent.LogSummary.Present
        assertEquals("0 B", summary.sizeLabel)
        assertEquals(1, summary.fileCount)
    }

    @Test
    fun aSmallLogIsReportedInBytesRatherThanRoundedAwayToEmpty() {
        // The first log line makes the file tiny, not absent; a row that said
        // "No logs written yet" for 400 bytes would be wrong and would disable Clear
        // while a file exists.
        val summary = content(logSizeBytes = 400L, logFileCount = 1).logSummary
        assertEquals(
            GlobalSettingsContent.LogSummary.Present(sizeLabel = "400 B", fileCount = 1),
            summary
        )
    }

    @Test
    fun sizesAreFormattedTheSameWayAsEverywhereElseInTheApp() {
        // Through FileSizeFormatter, so this row agrees with the Home card and the
        // file manager instead of inventing its own units.
        assertEquals(
            GlobalSettingsContent.LogSummary.Present(sizeLabel = "512 KB", fileCount = 2),
            content(logSizeBytes = 512L * 1024L, logFileCount = 2).logSummary
        )
        assertEquals(
            GlobalSettingsContent.LogSummary.Present(sizeLabel = "1.5 GB", fileCount = 4),
            content(logSizeBytes = (1.5 * 1024 * 1024 * 1024).toLong(), logFileCount = 4).logSummary
        )
    }

    @Test
    fun theFullRotatedSetIsReportedAsOneRowWithItsFileCount() {
        // Rotation spreads the log over several files and clearing removes all of
        // them, so the count is part of what the row has to say.
        val files = com.fem.linexa.core.logging.AppLogFiles.MAX_GENERATIONS + 1
        val summary = content(logSizeBytes = 2L * 1024 * 1024, logFileCount = files).logSummary
        assertEquals(
            GlobalSettingsContent.LogSummary.Present(sizeLabel = "2 MB", fileCount = files),
            summary
        )
    }

    @Test
    fun aNegativeSizeIsNotAllowedToRenderAsGarbage() {
        // Defensive but cheap: totalSizeBytes sums File.length(), which is 0 for an
        // unreadable file, so a negative should be impossible — and if one ever
        // arrives it must read as 0 B rather than "-1 B".
        val summary = content(logSizeBytes = -1L, logFileCount = 1).logSummary
        assertEquals(
            GlobalSettingsContent.LogSummary.Present(sizeLabel = "0 B", fileCount = 1),
            summary
        )
    }

    @Test
    fun aNegativeFileCountReadsAsEmpty() {
        assertEquals(
            GlobalSettingsContent.LogSummary.Empty,
            content(logSizeBytes = 100L, logFileCount = -1).logSummary
        )
    }
}
