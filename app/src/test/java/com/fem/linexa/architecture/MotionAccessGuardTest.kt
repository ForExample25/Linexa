package com.fem.linexa.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 2 (MOT-1) — exactly one motion provider.
 *
 * `rememberHavenMotions()` performs a `ContentResolver` read of the system
 * animator scale. The whole point of `LocalHavenMotions` is that this happens
 * once at the theme root, not once per animated component (and never during
 * scrolling). These are boundary assertions, not implementation-style checks:
 *
 *  - no production source outside `Motion.kt` may touch
 *    `Settings.Global.getFloat` / `ANIMATOR_DURATION_SCALE`;
 *  - `Theme.kt` must be the only provider of `LocalHavenMotions`;
 *  - no component may call `rememberHavenMotions()` or construct
 *    `HavenMotions(...)` directly.
 *
 * `Motion.kt`'s own KDoc names the symbols, so the first rule strips comments
 * before matching; the other two match code-only strings that do not appear in
 * the documentation.
 */
class MotionAccessGuardTest {

    @Test
    fun animatorScaleIsReadOnlyInsideMotionKt() {
        val offenders = productionSources()
            .filter { (path, text) ->
                path != "ui/components/haven/Motion.kt" && stripComments(text).contains("Settings.Global.getFloat")
            }
            .map { it.first }

        assertTrue(
            "Only Motion.kt may read Settings.Global.getFloat. Offenders: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun themeIsTheSingleMotionLocalProvider() {
        // Strip comments: the file's KDoc names rememberHavenMotions() to
        // explain the architecture, and only the real call may count.
        val theme = stripComments(source("ui/theme/Theme.kt"))
        assertEquals(
            "Theme.kt must provide LocalHavenMotions exactly once (above MaterialTheme).",
            1,
            Regex("""LocalHavenMotions provides motions""").findAll(theme).count()
        )
        assertEquals(
            "Theme.kt must call rememberHavenMotions exactly once.",
            1,
            Regex("""rememberHavenMotions\(\)""").findAll(theme).count()
        )
    }

    @Test
    fun componentsDoNotConstructMotionsAdHoc() {
        val offenders = productionSources()
            .filter { (path, text) ->
                path != "ui/components/haven/Motion.kt" &&
                    path != "ui/theme/Theme.kt" &&
                    stripComments(text).let { code ->
                        code.contains("rememberHavenMotions(") || code.contains("HavenMotions(")
                    }
            }
            .map { it.first }

        assertTrue(
            "Components must read LocalHavenMotions.current, never call " +
                "rememberHavenMotions() or construct HavenMotions() themselves. " +
                "Offenders: $offenders",
            offenders.isEmpty()
        )
    }

    private fun productionSources(): List<Pair<String, String>> {
        val root = File(moduleRoot(), "src/main/java/com/fem/linexa")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { file ->
                file.relativeTo(root).invariantSeparatorsPath to file.readText()
            }
            .toList()
    }

    private fun source(relativePath: String): String =
        File(moduleRoot(), "src/main/java/com/fem/linexa/$relativePath").readText()

    private fun stripComments(source: String): String =
        source
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//.*"""), "")

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
