package com.fem.linexa.architecture

import com.fem.linexa.navigation.TabTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 1 regression guard — the top-level tab crossfade stays the documented,
 * measured 90 ms simultaneous fade.
 *
 * Why this is two assertions and not one source scan:
 *
 *  - [TabTransition.spec] is production code. Its `TweenSpec.durationMillis`
 *    and `delay` are public properties, so the behaviour can be asserted on
 *    the JVM without mounting a Compose UI harness (the project has none — see
 *    `ImportBoundaryTest`'s note on §1.124(a)).
 *  - Navigation wiring is private lambdas inside `NavGraph.kt`; there is no
 *    public seam to invoke without a real `NavHost`. The source check therefore
 *    asserts the *wiring fact* (all three tab destinations still point at the
 *    two shared lambdas, and both lambdas still call the frozen spec), not an
 *    arbitrary implementation string.
 */
class TabTransitionGuardTest {

    @Test
    fun tabCrossfadeIsTheDocumentedNinetyMilliseconds() {
        assertEquals(
            "The top-level tab crossfade is a measured regression fix; changing it reintroduces " +
                "the blank-gap/double-composition behaviour documented in NavGraph.kt.",
            90,
            TabTransition.TAB_FADE_MS
        )
        val spec = TabTransition.spec()
        assertEquals("tab crossfade duration", 90, spec.durationMillis)
        assertEquals(
            "The tab crossfade must stay simultaneous: a positive delay is the blank-gap defect.",
            0,
            spec.delay
        )
    }

    @Test
    fun allThreeTopLevelTabsStillUseTheSharedFrozenSpec() {
        val navGraph = source("com/fem/linexa/navigation/NavGraph.kt")

        assertEquals(
            "Both tab transition lambdas must be built from TabTransition.spec(); a third call " +
                "site would mean a destination drifted to its own spec.",
            2,
            Regex("""TabTransition\.spec\(\)""").findAll(navGraph).count()
        )
        assertEquals(
            "All three top-level tab destinations must keep enterTransition = tabEnter.",
            3,
            Regex("""enterTransition = tabEnter""").findAll(navGraph).count()
        )
        assertEquals(
            "All three top-level tab destinations must keep exitTransition = tabExit.",
            3,
            Regex("""exitTransition = tabExit""").findAll(navGraph).count()
        )
        assertFalse(
            "The frozen 90 ms value must not be re-literalised in NavGraph.kt.",
            navGraph.contains("TAB_FADE_MS")
        )
        assertTrue(
            "NavGraph must not create a second tab tween outside TabTransition.",
            !Regex("""fade(In|Out)\(animationSpec = tween\(""").containsMatchIn(navGraph)
        )
    }

    private fun source(relativePath: String): String =
        File(moduleRoot(), "src/main/java/$relativePath").readText()

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
