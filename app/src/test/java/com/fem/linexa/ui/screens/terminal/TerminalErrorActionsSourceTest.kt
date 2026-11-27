package com.fem.linexa.ui.screens.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Finding 011 — structural guard for the error card's recovery wiring.
 *
 * The behaviour is pinned where it is observable: the pure report in
 * [com.fem.linexa.domain.runtime.TerminalErrorReportTest], the manager's
 * replace/refuse contract in
 * [com.fem.linexa.service.runtime.WorkspaceTerminalManagerTest], and the
 * ViewModel's per-id dedupe in [TerminalsViewModelTest]. What only the source
 * can prove is that the panel actually offers the three actions, that they are
 * wired to those behaviours, and that the SIGKILL guidance was not displaced.
 *
 * This repo runs no Compose UI test framework on the JVM; the established
 * source-invariant idiom is used (same rationale as
 * [TerminalExitHintWiringSourceTest]).
 */
class TerminalErrorActionsSourceTest {

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

    private val viewModel: String
        get() = File(moduleRoot, "src/main/java/com/fem/linexa/ui/screens/terminal/TerminalsViewModel.kt").readText()

    private val manager: String
        get() = File(moduleRoot, "src/main/java/com/fem/linexa/service/runtime/WorkspaceTerminalManager.kt").readText()

    private val binder: String
        get() = File(moduleRoot, "src/main/java/com/fem/linexa/service/runtime/RuntimeBinder.kt").readText()

    @Test
    fun theErrorCardOffersRestartDiagnosticsAndCopy() {
        assertTrue("the panel must render the action row", panel.contains("TerminalErrorActions("))
        assertTrue(
            "Restart must call the ViewModel (never the manager directly): the ViewModel owns the per-id dedupe",
            panel.contains("onRestart = { terminalsViewModel.restartTerminal(terminalId) }")
        )
        assertTrue(
            "Diagnostics must build the terminal-scoped report",
            panel.contains("TerminalErrorReport.diagnosticsText(")
        )
        assertTrue(
            "Copy must use the same report as Diagnostics",
            panel.contains("TerminalErrorReport.copyText(")
        )
        assertTrue(
            "Copy must actually reach the clipboard",
            panel.contains("clipboard.setText(")
        )
        assertTrue(
            "the actions must be attached to the Error placeholder",
            panel.contains("actions = errorActions")
        )
        assertTrue(
            "the existing Diagnostics dialog surface must be used",
            panel.contains("HDialog(")
        )
    }

    @Test
    fun sigkillKeepsItsBatteryGuidanceAndGainsTheSameActions() {
        assertTrue(
            "the SIGKILL footer must survive finding 011",
            panel.contains("footer = if (errorState?.killedBySigkill == true)")
        )
        assertTrue(
            "the battery notice must still be the footer",
            panel.contains("{ TerminalKilledNotice() }")
        )
        // The actions slot is independent of the SIGKILL condition, so a -9
        // screen shows Restart/Diagnostics/Copy above the battery notice.
        assertTrue(
            "the action row must not be gated on killedBySigkill",
            panel.contains("val errorActions: (@Composable () -> Unit)? = if (errorState != null)")
        )
    }

    @Test
    fun restartIsExplicitAndNeverAutomatic() {
        // Finding 019 adds a second *explicit* restart affordance to the
        // no-output banner. Both are user-initiated buttons; a third call site
        // would be the auto-restart path this guard exists to catch.
        assertEquals(
            "restart may be wired only from the error card and the no-output banner",
            2,
            Regex("restartTerminal\\(").findAll(panel).count()
        )
        assertTrue(
            "the error-card restart trigger is an explicit button",
            panel.contains("onClick = onRestart")
        )
        assertTrue(
            "the no-output banner restart is an explicit callback",
            panel.contains("onNoOutputRestart = {")
        )
        assertTrue(
            "the no-output banner must opt into replacing a live silent pty",
            panel.contains("allowRunning = true")
        )
    }

    @Test
    fun theViewModelGuardsOneRestartPerTerminal() {
        assertTrue(
            "the ViewModel must refuse a second in-flight restart for the same id",
            viewModel.contains("if (restartJobs[terminalId]?.isActive == true) return")
        )
        assertTrue(
            "the restart must be reclaimed when it finishes",
            viewModel.contains("restartJobs.remove(terminalId)")
        )
        assertTrue(
            "a workspace switch must cancel pending restarts",
            viewModel.contains("restartJobs.values.forEach { it.cancel() }")
        )
    }

    @Test
    fun theManagerOnlyRestartsAnErroredTerminalByDefault() {
        assertTrue(
            "allowRunning must stay opt-in (default false) so live shells are not killed implicitly",
            manager.contains("allowRunning: Boolean = false")
        )
        assertTrue(
            "the manager must enforce Error-only unless the explicit opt-in is set",
            manager.contains(
                "check(state is TerminalState.Error || (allowRunning && state is TerminalState.Running))"
            )
        )
        assertTrue(
            "the no-output restart must require a live Running pty, not any non-error state",
            manager.contains("allowRunning && state is TerminalState.Running")
        )
        assertTrue(
            "elevation must be preserved across a restart",
            manager.contains("asRoot = rootTerminals[workspaceId]?.contains(terminalId) == true")
        )
        assertTrue(
            "the retained dead pty must be readable for diagnostics without being renderable",
            manager.contains("fun getRetainedPtySession(")
        )
        assertTrue(
            "the binder must read the retained output defensively",
            binder.contains("pty.recentOutput(maxChars)")
        )
    }
}
