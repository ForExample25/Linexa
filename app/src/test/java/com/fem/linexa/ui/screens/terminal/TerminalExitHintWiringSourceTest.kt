package com.fem.linexa.ui.screens.terminal

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Finding 027 — structural guard for the review-visible half of the fix.
 *
 * The table itself is pure and pinned in
 * [com.fem.linexa.domain.runtime.TerminalExitDescriptionTest]; the manager is
 * pinned behaviourally in
 * [com.fem.linexa.service.runtime.WorkspaceTerminalManagerTest]. What neither
 * can reach is whether the panel actually *renders* the hint, and whether the
 * raw reason is still the primary message. This repo runs no Compose UI test
 * framework on the JVM, so the established source-invariant idiom is used
 * (same rationale as [TerminalKilledNoticeSourceTest]).
 *
 * The failure these tests are designed to catch is silent: a later refactor can
 * drop the `hint` argument and every behavioural test still passes, because
 * the state object is correct — the user just sees the pre-027 generic text
 * again.
 */
class TerminalExitHintWiringSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = "src/main/java/com/fem/linexa/ui/screens/terminal/WorkspaceTerminalPanel.kt"
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

    private val panel: String
        get() = File(moduleRoot, "src/main/java/com/fem/linexa/ui/screens/terminal/WorkspaceTerminalPanel.kt").readText()

    private val manager: String
        get() = File(moduleRoot, "src/main/java/com/fem/linexa/service/runtime/WorkspaceTerminalManager.kt").readText()

    @Test
    fun placeholderReceivesTheHintFromTheErrorState() {
        assertTrue(
            "the panel must resolve the Error state once, for the notice and the hint",
            panel.contains("val errorState = terminalState as? TerminalState.Error")
        )
        assertTrue(
            "the placeholder must receive the finding-027 hint: a dropped argument would " +
                "silently restore the pre-027 dead-end message",
            panel.contains("hint = errorState?.takeUnless { it.killedBySigkill }?.hint")
        )
        assertTrue(
            "TerminalStatusPlaceholder must accept an optional hint",
            panel.contains("hint: String? = null")
        )
        assertTrue(
            "and must render it only when present",
            panel.contains("if (hint != null)")
        )
    }

    @Test
    fun theHintRendersUnderTheReasonRatherThanReplacingIt() {
        val placeholder = panel.substringAfter("private fun TerminalStatusPlaceholder(")
        val messageIndex = placeholder.indexOf("text = message")
        val hintIndex = placeholder.indexOf("text = hint")
        assertTrue("the reason must still be rendered", messageIndex >= 0)
        assertTrue("the hint must be rendered", hintIndex > 0)
        assertTrue(
            "the reason is the primary message; the hint belongs underneath it",
            messageIndex < hintIndex
        )
        assertTrue(
            "the localized error prefix must still wrap the reason",
            panel.contains("stringResource(R.string.terminal_session_error, terminalState.reason)")
        )
    }

    @Test
    fun theManagerBuildsReasonAndHintFromOneTable() {
        assertTrue(
            "the manager must derive the diagnosis from the pure table",
            manager.contains("val description = exitCode?.let(TerminalExitDescription::of)")
        )
        assertTrue(
            "the reason and hint must travel together on the Error state",
            manager.contains("TerminalState.Error(reason, exitCode, description?.hint)")
        )
        assertFalse(
            "the pre-027 ad-hoc formatting must stay gone",
            manager.contains("\"Terminal \$terminalId exited unexpectedly (code \$exitCode)\"")
        )
    }
}
