package com.fem.linexa.ui.screens.terminal

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §1.145 structural guard for the terminal's **repaint bridge** — the half of
 * the freeze that was a UI-side defect rather than a dispatch one.
 *
 * `TermuxPtySession` is the library session's `SessionChangedCallback`, so
 * terminal-lib's `notifyScreenUpdate` is forwarded to a Kotlin flow
 * (`PtyEvent.ScreenUpdated`) instead of to a `View`. That makes
 * `RealTerminalView`'s collector the ONLY thing in the app that repaints the
 * terminal, and it repaints whatever `TerminalView` the holder it reads is
 * pointing at. So the holder being non-null for the life of the view is a
 * correctness property, not bookkeeping:
 *
 *  * The holder used to be `remember(ptySession.id)`. `AndroidView`'s factory
 *    runs once per composition instance, so switching the rendered terminal
 *    (new `ptySession`, same composable — which is exactly what
 *    `WorkspaceTerminalPanel` does when the session overview selects another
 *    terminal) re-keyed the holder to `null` while the factory did NOT re-run.
 *    Nothing could ever repopulate it, so the bridge became a permanent no-op:
 *    input still reached the pty and the emulator still consumed output, but the
 *    view painted once on attach and never again. On-device that reads as a
 *    frozen terminal after "new session", not as a dead shell — which is why it
 *    survived so long.
 *
 * This is asserted against source because the failure is a `remember` **key**,
 * and there is no Compose UI test harness in this repo (§1.124(a)). The
 * alternative is no coverage: the bug compiles, every ViewModel test passes, and
 * the only observable is pixels that stop changing. Same technique and rationale
 * as `DesktopInputWiringSourceTest` (§1.138) and `SettingsWiringSourceTest`
 * (§1.129).
 */
class TerminalRepaintBridgeSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/ui/screens/terminal/RealTerminalView.kt")
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

    private fun source(path: String): String =
        File(moduleRoot, "src/main/java/com/fem/linexa/$path").readText()

    private val realTerminalView: String get() = source("ui/screens/terminal/RealTerminalView.kt")
    private val ptySession: String get() = source("service/runtime/pty/TermuxPtySession.kt")
    private val workspacePanel: String get() = source("ui/screens/terminal/WorkspaceTerminalPanel.kt")

    // ── the holder outlives a session switch ──────────────────────────────

    @Test
    fun theViewHolderIsNotKeyedOnTheSession() {
        // The regression in one line. `remember(ptySession.id)` resets the holder
        // on a switch; the factory that fills it does not re-run.
        assertFalse(
            "the TerminalView holder must NOT be keyed on the session — AndroidView's factory " +
                "runs once per composition instance, so a session switch would null the holder " +
                "with nothing left to repopulate it, permanently killing the repaint bridge",
            realTerminalView.contains("remember(ptySession.id) { mutableStateOf<TerminalView?>(null) }")
        )
        assertTrue(
            "the holder's lifetime must match the view instance it tracks (unkeyed remember)",
            realTerminalView.contains("remember { mutableStateOf<TerminalView?>(null) }")
        )
    }

    @Test
    fun theFactoryFillsTheHolderAndOnlyOnReleaseClearsIt() {
        // Both halves matter: a holder nothing fills is the same no-op as a holder
        // that is reset, and a holder cleared anywhere other than onRelease would
        // reintroduce the bug through a different door. (2026-08-30: onRelease also
        // LOGS the released view identity — the invariant is that exactly ONE
        // assignment nulls the holder and it lives inside the release block.)
        assertTrue(
            "the AndroidView factory must publish the view it built",
            realTerminalView.contains("terminalViewState.value = terminalView")
        )
        val nullings = Regex("terminalViewState\\.value\\s*=\\s*null").findAll(realTerminalView).count()
        assertTrue(
            "exactly one code path may null the holder (found $nullings)",
            nullings == 1
        )
        val releaseBlock = realTerminalView.substringAfter("onRelease")
        assertTrue(
            "that one path must be the AndroidView onRelease block",
            releaseBlock.contains("terminalViewState.value = null")
        )
        assertFalse(
            "nothing before onRelease may null the holder — the view outlives every session switch",
            realTerminalView.split("onRelease").first().contains("terminalViewState.value = null")
        )
    }

    // ── the bridge exists, and is deterministic ───────────────────────────

    @Test
    fun screenUpdatesArePaintedThroughTheHolder() {
        // 2026-08-30 (session-lifecycle fix): the repaint is driven by the
        // session's CONFLATED screenRevision StateFlow, not by the replay-less
        // PtyEvent flow. A StateFlow delivers its CURRENT value to every
        // subscriber at subscribe time, so the view repaints the emulator's
        // current state deterministically no matter how late it binds — the
        // lossy event path dropped notifications emitted before/around
        // subscription and left new sessions frozen on their empty bind-time
        // frame ("new session → black until the session exits").
        assertTrue(
            "the repaint must be driven by the conflated screenRevision StateFlow",
            realTerminalView.contains("provider.screenRevision.collect")
        )
        assertTrue(
            "and the repaint call must still land on the view holder",
            realTerminalView.contains("terminalViewState.value?.onScreenUpdated()")
        )
        assertFalse(
            "the replay-less PtyEvent flow must no longer drive repaints — " +
                "notifications emitted before/around subscription were dropped, " +
                "which is the black-new-session root cause",
            realTerminalView.contains("is PtyEvent.ScreenUpdated -> terminalViewState.value?.onScreenUpdated()")
        )
    }

    @Test
    fun theBackendReallyRoutesRepaintsThroughTheRevision() {
        // The premise of the bridge: the pty session bumps a conflated revision
        // on every rendering-relevant emulator callback (screen output, OSC
        // colors) — the two TerminalSessionClient callbacks that change what
        // the view draws.
        assertTrue(
            "TermuxPtySession must implement the terminal module's TerminalSessionClient",
            ptySession.contains("TerminalSessionClient")
        )
        assertTrue(
            "the screen revision must be conflated state (MutableStateFlow), not an event",
            ptySession.contains("MutableStateFlow(0)")
        )
        val textBumps = Regex("onTextChanged\\(changedSession: TerminalSession\\?\\) \\{[\\s\\S]{0,120}bumpScreenRevision\\(\\)")
            .containsMatchIn(ptySession)
        val colorsBumps = Regex("onColorsChanged\\(session: TerminalSession\\?\\) \\{[\\s\\S]{0,120}bumpScreenRevision\\(\\)")
            .containsMatchIn(ptySession)
        assertTrue(
            "both rendering-relevant callbacks must bump the revision (text=$textBumps, colors=$colorsBumps)",
            textBumps && colorsBumps
        )
    }

    // ── the switch this fixes really is a switch, not a recreate ──────────

    @Test
    fun switchingTerminalsReusesTheSameComposableInstance() {
        // If selecting another terminal recreated the composable, the factory
        // would re-run and the old keying would have been harmless. It does not:
        // the panel re-attaches by re-running an effect keyed on the active
        // terminal id, deliberately leaving the old pty alive.
        assertTrue(
            "the panel must re-attach on a terminal switch via an effect, not by rebuilding",
            realTerminalView.contains("terminalView.currentSession !== nativeSession")
        )
        assertTrue(
            "and the update block must re-attach the new session to the existing view",
            realTerminalView.contains("terminalView.attachSession(nativeSession)")
        )
        assertTrue(
            "switching must be driven by the desktop's focused window rather than by disposal",
            workspacePanel.contains("LaunchedEffect(workspaceId, activeWindow?.terminalId, binder)")
        )
    }

    // ── the render escape is contained (fullscreen black overlay fix) ─────

    @Test
    fun theAndroidViewClipsTheTerminalToItsLayoutBounds() {
        // 2026-08-31 (fullscreen black overlay fix). The Compose interop
        // holder (ViewFactoryHolder) constructs itself with
        // setClipChildren(false), and Compose draws interop views on the RAW,
        // unclipped window canvas. The vendored TerminalView.onDraw fills its
        // canvas with the terminal-owned default background, so the unclipped
        // fill painted the ENTIRE window — over the app bar, the terminal bar
        // and the status strip — while those controls stayed laid out,
        // clickable and invisible (hit-testing is bounds-based, not
        // pixel-based). Everything drawn AFTER the terminal (extra-keys bar)
        // stayed visible, matching the "black above the shortcut bar" report.
        // The AndroidView node must therefore clip its drawing to its own
        // bounds: the terminal may render ONLY inside its content region.
        assertTrue(
            "the AndroidView hosting the TerminalView must clip to bounds — without " +
                "it the terminal's own background fill escapes the interop holder " +
                "(setClipChildren(false) + unclipped window canvas) and paints the " +
                "whole screen the terminal background color while the chrome " +
                "remains clickable underneath",
            realTerminalView.contains("modifier.clipToBounds()")
        )
        // The clip must sit ON the AndroidView's modifier chain (the fill
        // escapes exactly at that node), not somewhere arbitrary in the file.
        val androidViewBlock = realTerminalView.substringAfter("AndroidView(")
            .substringBefore("factory =")
        assertTrue(
            "clipToBounds must be applied to the AndroidView's own modifier",
            androidViewBlock.contains("modifier.clipToBounds()")
        )
    }
}
