package com.fem.linexa.ui.screens.workspace

import com.fem.linexa.domain.commands.ShortcutPins
import com.fem.linexa.domain.launcher.LauncherApp
import com.fem.linexa.ui.screens.workspace.AppInstallTracker.InstalledState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Apps grid's pure arrangement rules.
 *
 * The category filter's "All" state is `null`, never the empty string. That
 * distinction is load-bearing: the chip row used to send `""` for "All" and
 * toggle the selected category off by comparing against it, so re-tapping
 * "All" installed an empty-string filter that matched no category and blanked
 * the grid. These tests pin the model half of that contract; the chip wiring
 * is pinned by `LinexaRegressionFixesSourceTest`.
 */
class LauncherGridModelTest {

    private fun app(id: String, name: String, category: String?) = LauncherApp(
        id = id,
        name = name,
        description = null,
        iconKey = null,
        category = category,
        launchCommand = null,
        installCommand = null,
        removeCommand = null,
        verificationPackages = emptyList(),
        verificationExecutables = emptyList(),
        launchAsRoot = false
    )

    private fun row(id: String, name: String, category: String?) =
        AppLauncherViewModel.AppRow(app(id, name, category), InstalledState.UNKNOWN)

    private val rows = listOf(
        row("builtin:gimp", "GIMP", "Graphics"),
        row("builtin:firefox", "Firefox", "Browsers"),
        row("builtin:inkscape", "Inkscape", "Graphics")
    )

    @Test
    fun nullCategoryIsAllAndNeverFilters() {
        assertEquals(
            rows,
            LauncherGridModel.arrange(rows, query = "", category = null, pins = ShortcutPins())
        )
    }

    @Test
    fun aCategoryKeepsOnlyThatCategoryInCatalogOrder() {
        assertEquals(
            listOf(rows[0], rows[2]),
            LauncherGridModel.arrange(rows, query = "", category = "Graphics", pins = ShortcutPins())
        )
    }

    @Test
    fun anEmptyCategoryIsNotTheAllFilter() {
        // The old All chip sent "". That must keep matching nothing: treating
        // it as "all" would hide the regression instead of fixing it.
        assertEquals(
            emptyList<AppLauncherViewModel.AppRow>(),
            LauncherGridModel.arrange(rows, query = "", category = "", pins = ShortcutPins())
        )
    }

    @Test
    fun queryAndCategoryCompose() {
        assertEquals(
            listOf(rows[2]),
            LauncherGridModel.arrange(rows, query = "ink", category = "Graphics", pins = ShortcutPins())
        )
    }

    @Test
    fun categoriesAreDistinctInCatalogOrder() {
        assertEquals(
            listOf("Graphics", "Browsers"),
            LauncherGridModel.categoriesOf(rows)
        )
    }
}
