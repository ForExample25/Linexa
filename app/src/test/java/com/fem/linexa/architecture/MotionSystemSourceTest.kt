package com.fem.linexa.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural guard for the "one motion family" rules that cannot be asserted
 * from a data class: tab indicators share an implementation shape, menus do
 * not stack three effects, and surfaces do not reintroduce local duration
 * literals after the centralisation pass.
 *
 * Compose UI tests do not exist in this repository, so these are wiring/source
 * pins in the same style as the existing navigation guards. The behavioural
 * durations and reduced-motion math live in [MotionContractTest].
 */
class MotionSystemSourceTest {

    private val controls by lazy { source("ui/components/haven/HavenControls.kt") }
    private val chrome by lazy { source("ui/components/haven/HavenChrome.kt") }
    private val surfaces by lazy { source("ui/components/haven/HavenSurfaces.kt") }
    private val workspace by lazy { source("ui/screens/workspace/WorkspaceScreen.kt") }
    private val launcher by lazy { source("ui/screens/workspace/AppLauncherSegment.kt") }

    @Test
    fun segmentedControlAndBottomNavShareTheSlidingIndicatorLanguage() {
        assertTrue(
            "HSegmented must animate one float index, not per-option backgrounds",
            controls.contains("val selectedIndex by androidx.compose.animation.core.animateFloatAsState(")
        )
        assertTrue(
            "HSegmented must draw the moving pill in the draw phase",
            controls.contains(".segSelectionPill(")
        )
        assertTrue(
            "HSegmented and HBottomNav must bind the same segment token",
            controls.contains("animationSpec = motions.segmentSpec()") &&
                chrome.contains("animationSpec = motions.segmentSpec()")
        )
        assertTrue(
            "HBottomNav keeps its own draw-phase pill",
            chrome.contains(".navSelectionPill(")
        )
        assertFalse(
            "the selected segment must not fade a per-item background any more",
            controls.contains("label = \"seg-bg\"")
        )
    }

    @Test
    fun menusUseOneTransformAndNotOpacityScaleAndSlideTogether() {
        val menu = surfaces.substringAfter("fun HMenu(")
            .substringBefore("internal class HavenMenuPositionProvider")
        assertTrue("menu still fades", menu.contains("fadeIn(") && menu.contains("fadeOut("))
        assertTrue("menu still scales from its anchor", menu.contains("scaleIn(") && menu.contains("scaleOut("))
        assertFalse(
            "the menu must not stack a vertical slide on top of fade + scale",
            menu.contains("slideInVertically(") || menu.contains("slideOutVertically(")
        )
    }

    @Test
    fun segmentPagesUseSharedTokensAndNoLocalDurationLiteral() {
        val segmentTransition = workspace.substringAfter("AnimatedContent(")
            .substringBefore("label = \"workspace-segment\"")
        assertTrue("incoming page uses the segment tween", segmentTransition.contains("motions.segment"))
        assertFalse(
            "the segment transition must not re-literalise a duration",
            Regex("""\btween\(\s*\d""").containsMatchIn(segmentTransition)
        )
        assertFalse(
            "the segment transition must not stack a scale on top of fade + slide",
            segmentTransition.contains("scaleIn(") || segmentTransition.contains("scaleOut(")
        )
    }

    @Test
    fun launcherStaggerIsBoundedAndUsesTheSingleCollectionMotion() {
        assertTrue(
            "the first page must be capped by the shared stagger cap",
            launcher.contains("it < HavenMotion.STAGGER_MAX_ITEMS")
        )
        assertTrue(
            "entering tiles use the shared motionItem machinery",
            launcher.contains(".motionItem(")
        )
        assertFalse(
            "the launcher must not own a second duration literal",
            Regex("""Animatable\([^)]*\)[\s\S]{0,200}?tween\(\s*\d""").containsMatchIn(launcher)
        )
    }

    @Test
    fun componentSurfacesDoNotReadTheAnimatorScaleDirectly() {
        for ((name, text) in listOf(
            "HavenControls.kt" to controls,
            "HavenChrome.kt" to chrome,
            "HavenSurfaces.kt" to surfaces,
            "WorkspaceScreen.kt" to workspace,
            "AppLauncherSegment.kt" to launcher
        )) {
            assertFalse(
                "$name must read LocalHavenMotions, never Settings.Global",
                text.contains("Settings.Global")
            )
        }
    }

    @Test
    fun motionTokensStayInOneFile() {
        val motion = source("ui/components/haven/Motion.kt")
        val declaredDurations = Regex("""const val \w+_MS = \d+""").findAll(motion).count()
        assertTrue("the motion token block must remain populated", declaredDurations >= 10)
        assertEquals(
            "exactly one CompositionLocal motion provider is expected",
            1,
            Regex("""val LocalHavenMotions = staticCompositionLocalOf""").findAll(motion).count()
        )
    }

    @Test
    fun fileManagerMutatingSurfacesBindTheSharedTokens() {
        val fileManager = source("ui/screens/filemanager/FileManagerScreen.kt")
        assertTrue(
            "the create FAB rotation must use the shared selection spec",
            fileManager.contains("fileMotions.selectionSpec()")
        )
        // The storage sections are always visible now (their collapsible
        // headers were removed), so there is no section expand transition left
        // to pin. The remaining surfaces must still bind the shared tokens.
        assertFalse(
            "the File Manager must not reintroduce collapsible storage headers",
            fileManager.contains("CollapsibleSectionHeader")
        )
        assertTrue(
            "the toast must use the shared fade/slide tokens",
            fileManager.contains("slideInVertically(motions.segment)")
        )
        assertFalse(
            "no File Manager transition may fall back to a library default",
            fileManager.contains("fadeIn() + expandVertically()") ||
                fileManager.contains("fadeIn() + slideInVertically")
        )
    }

    private fun source(relativePath: String): String =
        File(moduleRoot(), "src/main/java/com/fem/linexa/$relativePath").readText()

    private fun moduleRoot(): File {
        val marker = "src/main/java/com/fem/linexa/MainActivity.kt"
        var dir: File? = File(requireNotNull(System.getProperty("user.dir")))
        while (dir != null) {
            if (File(dir, marker).exists()) return dir
            dir = dir.parentFile
        }
        error("module root not found from ${System.getProperty("user.dir")}")
    }
}
