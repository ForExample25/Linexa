package com.fem.linexa.ui.screens.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §1.191 — structural source-invariant test for the SIGKILL notice wiring.
 *
 * The behavioural half of §1.191 is already covered where it is observable:
 * [com.fem.linexa.service.runtime.WorkspaceTerminalManagerTest] pins the exit
 * status onto `TerminalState.Error`, pins `killedBySigkill` to `-9` only, and
 * pins the §P0 guarantee that a killed terminal never ends the workspace.
 *
 * What that cannot reach is the *presentation* decision, which is the part most
 * likely to be quietly undone by a later refactor. This repo runs no Compose UI
 * test framework on the JVM, so the same source-invariant approach used by
 * [com.fem.linexa.ui.screens.workspace.WorkspaceNotificationRecheckSourceTest]
 * is used here, guarding three things a reviewer would otherwise have to notice
 * by eye:
 *
 *  1. the notice is reached **only** through `killedBySigkill`, so it never
 *     becomes the generic treatment for every terminal failure;
 *  2. it stays an inline per-terminal block and is never "promoted" to an
 *     Activity/route/dialog — the tiny_container `Signal9Activity` pattern that
 *     §1.191 explicitly rejects, because a full-screen takeover would contradict
 *     Linexa's per-terminal error model (§P0);
 *  3. it stays diagnosis-only — it must not restart, close, or otherwise touch
 *     the terminal.
 */
class TerminalKilledNoticeSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = "src/main/java/com/fem/linexa/ui/screens/terminal/TerminalKilledNotice.kt"
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            if (File(dir, marker).exists()) {
                moduleRoot = dir
                return
            }
            dir = dir.parentFile
        }
        moduleRoot = File(".")
    }

    private fun source(relative: String): String = File(moduleRoot, relative).readText()

    /**
     * Comments are documentation, not behaviour. The lifecycle-safety assertion
     * below looks for *calls*, and §1.191's kdoc legitimately discusses what the
     * notice must not do ("never restarts, closes, ..."). Matching prose would
     * make the guard fire on the very comment explaining it, so strip comments
     * before asserting on code.
     */
    private fun code(relative: String): String = source(relative)
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .lines()
        .filterNot { it.trimStart().startsWith("//") }
        .joinToString("\n")

    private val notice: String
        get() = source("src/main/java/com/fem/linexa/ui/screens/terminal/TerminalKilledNotice.kt")

    private val panel: String
        get() = source("src/main/java/com/fem/linexa/ui/screens/terminal/WorkspaceTerminalPanel.kt")

    private val terminalState: String
        get() = source("src/main/java/com/fem/linexa/domain/runtime/TerminalState.kt")

    // ── 1. gated on SIGKILL, and only on SIGKILL ────────────────────────────

    @Test
    fun noticeIsReachedOnlyThroughKilledBySigkill() {
        assertTrue(
            "the panel must gate the notice on TerminalState.Error.killedBySigkill",
            panel.contains("killedBySigkill")
        )
        assertEquals(
            "TerminalKilledNotice must have exactly one call site — the SIGKILL gate. " +
                "A second, ungated one would show the memory guidance for unrelated failures.",
            1,
            Regex("TerminalKilledNotice\\(").findAll(panel).count()
        )
    }

    @Test
    fun sigkillIsDerivedFromTheExitStatusConstantNotAHardcodedNumber() {
        assertTrue(
            "killedBySigkill must compare against the named SIGKILL_EXIT_STATUS",
            terminalState.contains("exitCode == SIGKILL_EXIT_STATUS")
        )
        assertTrue(
            "terminal-lib reports a signal death as the negated signal number, so SIGKILL is -9",
            terminalState.contains("const val SIGKILL_EXIT_STATUS: Int = -9")
        )
    }

    @Test
    fun errorStateStillCarriesADefaultedExitCode() {
        // Defaulted, because a failure-to-open has no child and therefore no exit
        // status to report. Dropping the default would force every Error
        // construction site to invent one.
        assertTrue(
            "Error must expose an optional exitCode alongside its reason",
            terminalState.contains("data class Error(val reason: String, val exitCode: Int? = null)")
        )
    }

    // ── 2. inline, never a screen ───────────────────────────────────────────

    @Test
    fun noticeIsAnInlineBlockAndNotAScreen() {
        val src = notice
        assertTrue(
            "the notice must be a composable block",
            src.contains("fun TerminalKilledNotice(")
        )
        // The rejected pattern: a dedicated Activity/route/dialog for one
        // terminal's death (tiny_container's Signal9Activity).
        listOf(
            ": Activity(",
            ": ComponentActivity(",
            "setContent {",
            "AlertDialog(",
            "Dialog(",
            "navController.navigate("
        ).forEach { forbidden ->
            assertFalse(
                "§1.191 is deliberately inline and per-terminal: found \"$forbidden\" in " +
                    "TerminalKilledNotice. Do not promote it to a screen or dialog — " +
                    "that would make one terminal's death look like the workspace's (§P0).",
                src.contains(forbidden)
            )
        }
    }

    @Test
    fun noticeIsRenderedInsideThePerTerminalPlaceholder() {
        assertTrue(
            "the notice must render through StatusPlaceholder's footer, i.e. inside the " +
                "same per-terminal surface as every other terminal state",
            panel.contains("footer = if ((terminalState as? TerminalState.Error)?.killedBySigkill == true)")
        )
        assertTrue(
            "StatusPlaceholder must accept the optional footer",
            panel.contains("footer: (@Composable () -> Unit)? = null")
        )
        assertTrue(
            "a null footer must leave the pre-§1.191 layout untouched",
            panel.contains("if (footer != null)")
        )
    }

    @Test
    fun noSigkillActivityOrRouteWasIntroducedAnywhere() {
        // Belt-and-braces across the whole module: the rejected pattern must not
        // appear under a different name either.
        val offenders = File(moduleRoot, "src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val name = file.name.lowercase()
                name.contains("signal9") || name.contains("sigkillactivity")
            }
            .map { it.name }
            .toList()
        assertTrue(
            "§1.191 must not introduce a Signal9-style takeover screen, found: $offenders",
            offenders.isEmpty()
        )
    }

    // ── 3. diagnosis only ───────────────────────────────────────────────────

    @Test
    fun noticeNeverTouchesTheTerminalLifecycle() {
        val src = code("src/main/java/com/fem/linexa/ui/screens/terminal/TerminalKilledNotice.kt")
        listOf(
            "closeTerminal",
            "openTerminal",
            "restart",
            "requestStart",
            "closeWorkspace"
        ).forEach { forbidden ->
            assertFalse(
                "the notice is diagnosis-only — it must not call \"$forbidden\"; §P0 " +
                    "per-terminal error handling must stay exactly as it was",
                src.contains(forbidden)
            )
        }
    }

    @Test
    fun noticeOffersTheOneSettingTheUserCanActuallyChange() {
        val src = notice
        assertTrue(
            "the action must launch the battery-optimization exemption flow",
            src.contains("requestIgnoreBatteryOptimizations(context)")
        )
        assertTrue(
            "the current exemption state must be read so the action can be relabelled",
            src.contains("isIgnoringBatteryOptimizations(context)")
        )
        assertTrue(
            "already-exempt users keep an affordance (the exemption is not the only cause)",
            src.contains("terminal_killed_action_granted")
        )
    }

    @Test
    fun exemptionStateIsRequeriedOnResume() {
        // The exemption has no change notification and the action leaves for a
        // system screen, so returning is the only moment a grant becomes visible.
        val src = notice
        assertTrue(
            "must observe the lifecycle to re-query the exemption",
            src.contains("LifecycleEventObserver")
        )
        assertTrue(
            "the re-query must be tied to ON_RESUME",
            src.contains("Lifecycle.Event.ON_RESUME")
        )
        assertTrue(
            "the observer must be removed on dispose",
            src.contains("lifecycle.removeObserver(observer)")
        )
        assertTrue(
            "the exemption read must be keyed on the resume counter, or it would never refresh",
            src.contains("remember(refreshKey)")
        )
    }

    @Test
    fun noticeUsesTheComposeUiLifecycleOwner() {
        // §1.105: androidx.lifecycle.compose's identically-named LocalLifecycleOwner
        // is not provided by compose-ui 1.6.x and throws at read time, taking the
        // screen down. AccessSettingsGroup and WorkspaceNavGraph already learned this.
        val src = notice
        assertTrue(
            "must import compose-ui's LocalLifecycleOwner (§1.105)",
            src.contains("import androidx.compose.ui.platform.LocalLifecycleOwner")
        )
        assertFalse(
            "must not import androidx.lifecycle.compose's LocalLifecycleOwner (§1.105 crash)",
            src.contains("import androidx.lifecycle.compose.LocalLifecycleOwner")
        )
    }

    // ── copy: hedged, because a -9 cannot prove its own cause ───────────────

    @Test
    fun copyHedgesTheCauseItCannotProve() {
        val strings = source("src/main/res/values/strings.xml")
        listOf(
            "terminal_killed_title",
            "terminal_killed_body",
            "terminal_killed_action",
            "terminal_killed_action_granted"
        ).forEach { key ->
            assertTrue("missing string $key", strings.contains("name=\"$key\""))
        }
        val body = Regex("<string name=\"terminal_killed_body\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .find(strings)?.groupValues?.get(1).orEmpty()
        assertTrue(
            "lowmemorykiller, the freezer, a guest-side kill -9 and adb are " +
                "indistinguishable in the exit status, so the copy must hedge rather " +
                "than assert a cause — body was: $body",
            body.contains("may have")
        )
    }
}
