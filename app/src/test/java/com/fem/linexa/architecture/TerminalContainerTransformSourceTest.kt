package com.fem.linexa.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural guard for the terminal ↔ Recent Terminals container transform.
 *
 * This is the one motion in the app where a mistake is not cosmetic: every
 * layer here sits on top of a live pty-backed `AndroidView`. The transform is
 * therefore implemented as a draw-time layer change around the terminal, not
 * as `AnimatedContent`/`AnimatedVisibility` around it, and the terminal view is
 * deliberately not keyed by the active terminal id (the same view re-attaches
 * the new session). These assertions pin that topology; they do not replace
 * the on-device pty tests.
 */
class TerminalContainerTransformSourceTest {

    private val panel by lazy {
        source("ui/screens/terminal/WorkspaceTerminalPanel.kt")
    }

    @Test
    fun theTerminalLayerShrinksIntoTheOverviewInTheDrawPhase() {
        assertTrue(
            "the panel must drive one container progress value from showOverview",
            panel.contains("val overviewProgress by animateFloatAsState(")
        )
        assertTrue(
            "the transform must read the shared container token",
            panel.contains("animationSpec = motions.containerSpec()")
        )
        assertTrue(
            "the terminal layer must be transformed with graphicsLayer",
            panel.contains(".graphicsLayer {")
        )
        assertTrue("the terminal layer must scale", panel.contains("scaleX = scale"))
        assertTrue("the terminal layer must fade", panel.contains("alpha = 1f - progress"))
        assertTrue(
            "the transform origin must keep the terminal shrinking toward the overview header",
            panel.contains("TransformOrigin(0.5f, 0f)")
        )
    }

    @Test
    fun theTerminalViewIsNotKeyedOrRecreatedPerSession() {
        assertFalse(
            "key(window.terminalId) recreated the AndroidView on every session switch",
            panel.contains("key(window.terminalId)")
        )
        assertEquals(
            "the panel must host exactly one RealTerminalView call site",
            1,
            Regex("""RealTerminalView\(""").findAll(panel).count()
        )
        assertTrue(
            "the terminal view must stay outside any AnimatedContent/AnimatedVisibility wrapper",
            panel.indexOf("RealTerminalView(") > panel.indexOf(".graphicsLayer {")
        )
    }

    @Test
    fun theOverviewEntersAndLeavesAsAContainerNotASlide() {
        val layer = panel.substringAfter("private fun TerminalOverviewLayer(")
            .substringBefore("private fun TerminalWindowTopBar(")
        assertTrue("enter must scale", layer.contains("scaleIn("))
        assertTrue("exit must scale", layer.contains("scaleOut("))
        assertTrue(
            "the overview layer must use the shared container token",
            Regex("""motions\.containerSpec\(\)""").findAll(layer).count() >= 2
        )
        assertFalse(
            "the overview must not stack a vertical slide on the container transform",
            layer.contains("slideInVertically(") || layer.contains("slideOutVertically(")
        )
    }

    @Test
    fun selectingACardStillFocusesWithoutRestartingTheSession() {
        assertTrue(
            "a card tap must restore/focus the existing window",
            panel.contains("terminalsViewModel.restoreTerminal(terminalId)")
        )
        assertTrue(
            "a card tap must retarget the hoisted active id",
            panel.contains("onActiveTerminalIdChange(terminalId)")
        )
        assertTrue(
            "a card tap must close the overview, running the transform backwards",
            panel.contains("onShowOverviewChange(false)")
        )
        assertFalse(
            "the motion change must not call createTerminal on a card tap",
            panel.substringAfter("onSelectWindow = { terminalId ->")
                .substringBefore("}")
                .contains("createTerminal")
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
