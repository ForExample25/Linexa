package com.fem.linexa.ui.screens.workspace

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Workspace gutter contract.
 *
 * The Workspace screen owns one horizontal `HavenDim.screenH` inset. Every
 * segment (Apps, Sessions, Features, Settings) and every pushed diagnostic
 * surface renders INSIDE that inset, so none of them may add a second one:
 * a nested 20 dp pad made cards sit at 40 dp while the segment selector above
 * sat at 20 dp, which reads as a squeezed, misaligned page.
 *
 * Cards follow the same rule vertically: a non-tight `HCard` already applies
 * `HavenDim.cardPad`, while a `tight` card owns its internal padding. This is
 * the source-level half of the spacing audit; the on-device Settings swatch
 * alignment was confirmed during the QA pass recorded in the report.
 */
class WorkspaceSpacingSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/ui/screens/workspace/WorkspaceScreen.kt")
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, marker.path).exists()) {
                moduleRoot = dir
                return
            }
            dir = dir.parentFile
        }
        moduleRoot = File(".")
    }

    private fun source(relative: String): String =
        File(moduleRoot, "src/main/java/com/fem/linexa/ui/screens/workspace/$relative").readText()

    private val segments = listOf(
        "FeaturesTab.kt",
        "AppLauncherSegment.kt",
        "SessionsSegment.kt",
        "HavenWorkspaceSettingsTab.kt",
        "DiagnosticViewerScreen.kt"
    )

    @Test
    fun theWorkspaceScreenIsTheOnlyOwnerOfTheHorizontalGutter() {
        val screen = source("WorkspaceScreen.kt")
        assertTrue(
            "the segment body must sit in the shared screen gutter",
            screen.contains(".padding(horizontal = HavenDim.screenH)")
        )
        segments.forEach { file ->
            val text = source(file)
            assertFalse(
                "$file must not add a second horizontal screen gutter — " +
                    "WorkspaceScreen already applies HavenDim.screenH",
                text.contains("padding(horizontal = HavenDim.screenH)")
            )
        }
    }

    @Test
    fun featuresRenderInOneTightCardWithoutASecondInset() {
        val features = source("FeaturesTab.kt")
        val column = features.substringAfter("Column(modifier = modifier.fillMaxWidth())")
            .substringBefore("HCard(tight = true)")
        assertFalse(
            "the Features column must not add its own horizontal padding",
            column.contains(".padding(")
        )
        assertTrue(
            "the Features rows live in one tight card with hairline dividers",
            features.contains("HCard(tight = true)") &&
                features.contains("HHairline()")
        )
    }

    @Test
    fun launcherAndDiagnosticCardsDoNotDoublePadTheirContents() {
        val launcher = source("AppLauncherSegment.kt")
        // Tight cards own their own padding (16 dp here); the outer segment
        // must never wrap them in a second 20 dp inset.
        assertFalse(
            "the launcher grid must not add a horizontal 20 dp page inset",
            launcher.contains(".padding(horizontal = 20.dp)\n        ) {")
        )
        val diagnostics = source("DiagnosticViewerScreen.kt")
        assertFalse(
            "the diagnostic page must not add a second screen gutter",
            diagnostics.contains("padding(horizontal = HavenDim.screenH)")
        )

        val settingsTab = source("HavenWorkspaceSettingsTab.kt")
        assertFalse(
            "the workspace settings tab must not add a second screen gutter",
            settingsTab.contains("padding(horizontal = HavenDim.screenH)")
        )
        val sessions = source("SessionsSegment.kt")
        assertFalse(
            "the sessions segment must not add a second screen gutter",
            sessions.contains("padding(horizontal = HavenDim.screenH)")
        )
    }

    @Test
    fun theColorSwatchRowsNoLongerDoublePadInsideTheirCard() {
        // Settings is not a Workspace file, but it shares the same HCard
        // contract; the duplicate inset lived there and was fixed in this pass.
        val settings = File(
            moduleRoot,
            "src/main/java/com/fem/linexa/ui/screens/settings/SettingsScreen.kt"
        ).readText()
        val swatch = settings.substringAfter("private fun ColorSwatchRow(")
        assertFalse(
            "a swatch row inside a padded HCard must not add a second 20 dp inset",
            swatch.contains(".padding(horizontal = 20.dp")
        )
        assertTrue(
            "the swatch row relies on the card's own gutter",
            swatch.contains(".padding(vertical = 10.dp)")
        )
    }
}
