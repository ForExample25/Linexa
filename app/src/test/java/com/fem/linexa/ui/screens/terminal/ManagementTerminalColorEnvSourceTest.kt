package com.fem.linexa.ui.screens.terminal

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §26.2 (terminal audit) — the Management Terminal's env must advertise
 * truecolour, in step with the workspace launcher.
 *
 * ## Why this is asserted against source
 *
 * [ManagementTerminalViewModel] is an `AndroidViewModel` that builds its env
 * inline inside `start()` and immediately hands it to a real
 * `PtySessionFactory`. There is no seam to observe that map from a JVM test:
 * constructing the ViewModel needs `AndroidViewModel`'s `Application`, and the
 * env is a local `val` that is never exposed. The alternatives were to refactor
 * production code purely to make it observable, or to leave the management
 * shell's half of the fix uncovered — a structural assertion is the cheaper
 * honest option. Same technique and rationale as
 * [TerminalRepaintBridgeSourceTest] (§1.145).
 *
 * ## Why the management terminal needs it too
 *
 * Both terminal surfaces in the app are the same composable over the same
 * terminal-lib emulator (`RealTerminalView`), so the truecolour claim is
 * exactly as true here as in the guest. If only the workspace launcher set it,
 * the same tool would render with different colour depth in the app's two
 * terminals — a difference with no cause a user could reason about.
 *
 * The counterpart end-to-end assertion, through the real launch path, is
 * [com.fem.linexa.service.runtime.container.PRootLaunchColorEnvTest].
 */
class ManagementTerminalColorEnvSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/ui/screens/terminal/ManagementTerminalViewModel.kt")
        var dir: File? = System.getProperty("user.dir")?.let { File(it) }
        while (dir != null) {
            if (File(dir, marker.path).exists()) {
                moduleRoot = dir
                return
            }
            dir = dir.parentFile
        }
        moduleRoot = File(".")
    }

    private fun source(path: String): String =
        File(moduleRoot, "src/main/java/com/fem/linexa/$path").readText()

    private val managementViewModel: String
        get() = source("ui/screens/terminal/ManagementTerminalViewModel.kt")

    private val prootLauncher: String
        get() = source("service/runtime/container/PRootLauncher.kt")

    @Test
    fun theManagementShellEnvAdvertisesTruecolour() {
        assertTrue(
            "the management shell's env must set COLORTERM=truecolor — it renders through the " +
                "same terminal-lib emulator as a workspace terminal, so a tool that gates " +
                "24-bit output on COLORTERM would otherwise downgrade here only (§26.2)",
            managementViewModel.contains("\"COLORTERM\" to \"truecolor\"")
        )
    }

    @Test
    fun theManagementShellKeepsItsOwnTermUntouched() {
        assertTrue(
            "COLORTERM supplements TERM and must not replace it — the host management shell " +
                "resolves TERM against Linexa's own vendored terminfo (§1.75), which has an " +
                "xterm-256color entry and nothing broader",
            managementViewModel.contains("\"TERM\" to \"xterm-256color\"")
        )
    }

    @Test
    fun bothTerminalSurfacesClaimTheSameColourDepth() {
        // The property that matters is agreement between the two env maps, not
        // the literal in either one: a future change to one side alone is the
        // realistic regression, and it is invisible on device except as two
        // terminals rendering the same program differently.
        val claim = "\"COLORTERM\" to \"truecolor\""
        assertTrue(
            "the guest launcher and the management shell must claim the same colour depth — " +
                "found in management=${managementViewModel.contains(claim)}, " +
                "launcher=${prootLauncher.contains(claim)}",
            managementViewModel.contains(claim) && prootLauncher.contains(claim)
        )
    }
}
