package com.fem.linexa.navigation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Structural guard for the top-level tab transitions.
 *
 * Why a source test: the tab-switch jank this pins was caused by the
 * *library default* transition on the three tab destinations — a fade-out
 * with a 90ms enter delay before a 220ms fade-in — which showed a blank gap
 * between tabs and kept both full-screen trees (two Scaffolds, two bottom
 * bars) composed and animated for ~310ms per switch. The fix scopes a short
 * simultaneous crossfade to exactly the three top-level routes; asserting the
 * wiring here means a refactor that drops one of the four transition
 * parameters on one of the tabs (restoring the default for that direction)
 * fails loudly, instead of resurfacing as a visual-only regression no unit
 * test can see.
 *
 * Same technique and rationale as `DistroAvatarPreviewSourceTest` and the
 * other wiring source tests in this module.
 */
class TabTransitionsSourceTest {

    private lateinit var moduleRoot: File
    private lateinit var source: String

    @Before
    fun locateModuleRoot() {
        val marker = "src/main/java/com/fem/linexa/navigation/NavGraph.kt"
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, marker).exists()) {
                moduleRoot = dir
                break
            }
            dir = dir.parentFile
        }
        source = File(moduleRoot ?: File("."), marker).readText()
    }

    @Test
    fun everyTopLevelTabOverridesAllFourTransitions() {
        for (route in listOf("Routes.WORKSPACES", "Routes.ACTIVITY", "Routes.SETTINGS")) {
            val call = composableCallFor(route)
            assertTrue(
                "$route must override enterTransition with the shared tab fade",
                call.contains("enterTransition = tabEnter")
            )
            assertTrue(
                "$route must override exitTransition with the shared tab fade",
                call.contains("exitTransition = tabExit")
            )
            assertTrue(
                "$route must override popEnterTransition with the shared tab fade",
                call.contains("popEnterTransition = tabEnter")
            )
            assertTrue(
                "$route must override popExitTransition with the shared tab fade",
                call.contains("popExitTransition = tabExit")
            )
        }
    }

    @Test
    fun theTabFadeIsAShortSimultaneousFadeNotTheLibraryDefault() {
        // The default's defect was the enter delay (blank gap) and the long
        // double-composition window. 2026-11 motion pass: the value moved to
        // `TabTransition.kt` so the tab guard can assert it behaviourally;
        // it must still be a plain tween fade — no delay parameter — and
        // short (<= 120ms).
        val transition = File(
            moduleRoot ?: File("."),
            "src/main/java/com/fem/linexa/navigation/TabTransition.kt"
        ).readText()
        assertFalse(
            "the tab fade must not carry an enter delay (the blank-gap defect)",
            transition.contains("delayMillis")
        )
        val duration = Regex("TAB_FADE_MS = (\\d+)")
            .find(transition)?.groupValues?.get(1)?.toIntOrNull()
        assertTrue("TAB_FADE_MS must be declared in TabTransition.kt", duration != null)
        assertTrue(
            "the tab fade must stay short (was: $duration ms)",
            duration in 1..120
        )
    }

    @Test
    fun pushedDestinationsKeepTheLibraryDefaultTransition() {
        // Scoped fix: only the three tabs opt in. The workspace graph, install
        // flow, about and device-info destinations must not carry the
        // tab fade, so stack navigation keeps its default feel. (The
        // Performance destination was removed in UI/UX Phase 3; the RootFS
        // Builder destination was removed when the feature was retired.)
        for (route in listOf(
            "Routes.WORKSPACE",
            "Routes.INSTALL",
            "Routes.ABOUT",
            "Routes.DEVICE_INFO"
        )) {
            val call = composableCallFor(route)
            assertFalse(
                "$route must keep the library default (no tab transition overrides)",
                call.contains("tabEnter") || call.contains("tabExit")
            )
        }
        assertFalse(
            "the removed Performance route must not return",
            source.contains("Routes.PERFORMANCE")
        )
    }

    @Test
    fun theWorkspaceGraphOverridesAllFourTransitionsWithTheSharedPushMotion() {
        // 2026-11 motion pass: the WORKSPACE graph deliberately does NOT keep
        // the library crossfade — its NavHost binds the shared Haven motion
        // tokens at graph level so every push inside a workspace (Packages /
        // Files / Desktop / Task Manager) is one consistent motion: the
        // incoming page slides over a page that stays put (no fade on the
        // moving page — the terminal overlay's lesson) and the pull fades the
        // page being revealed under the departing one. All four parameters
        // must be present, or one direction silently falls back to the
        // library default.
        val file = File(
            moduleRoot ?: File("."),
            "src/main/java/com/fem/linexa/ui/screens/workspace/WorkspaceNavGraph.kt"
        )
        val graph = file.readText()
        assertTrue(
            "the workspace graph's NavHost must override enterTransition (push slides in)",
            graph.contains("enterTransition = {") &&
                graph.contains("slideInHorizontally(animationSpec = motions.push)")
        )
        assertTrue(
            "the workspace graph's NavHost must override exitTransition (the page underneath stays put)",
            graph.contains("exitTransition = { ExitTransition.None }")
        )
        assertTrue(
            "the workspace graph's NavHost must override popEnterTransition (the revealed page fades in)",
            graph.contains("popEnterTransition = {") &&
                graph.contains("fadeIn(animationSpec = motions.fade)")
        )
        assertTrue(
            "the workspace graph's NavHost must override popExitTransition (the leaving page slides out)",
            graph.contains("popExitTransition = {") &&
                graph.contains("slideOutHorizontally(animationSpec = motions.push)")
        )
        assertTrue(
            "the motion must come from the shared tokens (Motion.kt), not a local spec",
            graph.contains("LocalHavenMotions.current")
        )
    }

    /**
     * The argument list of the `composable(...)` call for [route], from the
     * route literal through the closing `) {` that opens the content lambda.
     * The route token is matched exactly (trailing `,` or `)`), so
     * `Routes.WORKSPACE` cannot alias the `Routes.WORKSPACES` call.
     */
    private fun composableCallFor(route: String): String {
        val multiLine = source.indexOf("route = $route,")
        val startIndex = if (multiLine >= 0) {
            source.lastIndexOf("composable(", multiLine)
        } else {
            val single = source.indexOf("composable($route)")
            if (single >= 0) single else source.indexOf("composable(\n            route = $route)")
        }
        assertTrue("no composable() call found for $route", startIndex >= 0)
        val endIndex = source.indexOf(") {", startIndex)
        assertTrue("malformed composable() call for $route", endIndex > startIndex)
        return source.substring(startIndex, endIndex)
    }
}
