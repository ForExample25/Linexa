package com.fem.linexa.ui.screens.desktop

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * UI/UX Phase 8 (§1.138) structural guard for the desktop's input and window
 * handling.
 *
 * Every defect this phase fixes was a *wiring* defect that compiled perfectly and
 * could only be found by using the feature on a device: the gesture machine lived
 * inline in the `View`, the keyboard's only trigger was a tap that was also a
 * click, the composing callback typed its whole region, and the activity was
 * recreated by the rotation the desktop itself requested. There is no Compose UI
 * harness in this repo (§1.124(a)), so the alternative to a source test is no
 * coverage at all of the parts that are not pure functions.
 *
 * Same technique and rationale as `AboutWiringSourceTest` (§1.130),
 * `SettingsWiringSourceTest` (§1.129) and `DebugInfoWiringSourceTest` (§1.137).
 *
 * 2026-11-13 (10D): the MotionEvent action→kind mapping moved to `VncTouchKind`
 * and its totality is asserted behaviourally in VncTouchKindTest; the tests
 * here keep the view-side wiring.
 */
class DesktopInputWiringSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/MainActivity.kt")
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
        File(moduleRoot, "src/main/java/com/fem/linexa/ui/screens/desktop/$path").readText()

    private val surfaceView: String get() = source("VncSurfaceView.kt")
    private val viewer: String get() = source("VncViewer.kt")
    private val screen: String get() = source("DesktopScreen.kt")
    private val manifest: String
        get() = File(moduleRoot, "src/main/AndroidManifest.xml").readText()

    // ── the gesture machine is used, and is not duplicated ───────────────

    @Test
    fun theSurfaceDelegatesToTheGestureStateMachine() {
        // A pure machine nothing calls is the §1.19 defect class: every rule in
        // VncTouchGestureTest would pass while the view kept its old inline logic.
        //
        // Desktop Touch work (2026-08): there are now TWO machines behind one
        // contract ([VncGestureMachine]) — the touchpad-relative
        // [VncTouchGesture] and the direct/absolute [VncDirectGesture] — and
        // the view dispatches through the single `currentMachine()` seam so
        // neither machine's rules are duplicated in the view.
        assertTrue(
            "VncSurfaceView must own the relative machine",
            surfaceView.contains("VncTouchGesture()")
        )
        assertTrue(
            "and the direct machine (Desktop Touch work)",
            surfaceView.contains("VncDirectGesture()")
        )
        assertTrue(
            "and must feed touch events through the shared seam",
            surfaceView.contains("currentMachine().onTouch(")
        )
    }

    @Test
    fun theViewDispatchesThroughTheSharedTouchKindMapping() {
        // 10D: the action→kind mapping moved into VncTouchKind.kt and its
        // totality is asserted behaviourally in VncTouchKindTest (MotionEvent
        // constants are compile-time values, safe on the JVM). The wiring half
        // stays: the view must classify through that one function, so a kind
        // added to the enum without a view branch no longer compiles (the when
        // is exhaustive), and a device action the mapping does not recognize
        // lands in `Other`, which the view refuses rather than mistranslates.
        assertTrue(
            "VncSurfaceView must dispatch through the shared action→kind mapping",
            surfaceView.contains("when (vncTouchKindOf(event.actionMasked))")
        )
        assertFalse(
            "the view must not re-classify MotionEvent actions inline",
            Regex("MotionEvent\\.ACTION_[A-Z_]+ ->").containsMatchIn(surfaceView)
        )
    }

    @Test
    fun theViewDoesNotDecideGesturesItself() {
        // The rules must exist in exactly one place. A threshold compared in the
        // view would be a second, untested classifier.
        assertFalse(
            "the view must not classify by elapsed time",
            surfaceView.contains("LONG_PRESS_MS")
        )
        assertFalse(
            "the view must not compare the move slop",
            surfaceView.contains("MOVE_SLOP_PX")
        )
    }

    @Test
    fun theOldClassifierIsGoneRatherThanUnused() {
        // Left in place it would be a working, tested, wrong alternative for the
        // next edit to reach for.
        assertFalse(
            "VncPointer.classify must be deleted",
            source("VncPointer.kt").contains("fun classify(")
        )
        assertFalse(
            "and its Gesture enum with it",
            source("VncPointer.kt").contains("enum class Gesture")
        )
    }

    @Test
    fun theHoldTimerIsBothArmedAndCancelled() {
        // A ScheduleHold with no CancelHold is the racing-timer version of the bug
        // this phase fixes: a finger that started moving would still be pressed.
        assertTrue(surfaceView.contains("postDelayed(holdRunnable"))
        assertTrue(surfaceView.contains("removeCallbacks(holdRunnable)"))
    }

    @Test
    fun tearingDownTheViewReleasesAHeldButton() {
        // Desktop Touch work (2026-08): the reset goes through the shared
        // machine seam, so whichever mode's gesture is in flight is released.
        assertTrue(
            "onDetachedFromWindow must reset the gesture",
            surfaceView.contains("currentMachine().reset()")
        )
    }

    // ── pointing is relative (§1.139) ────────────────────────────────────

    @Test
    fun theViewOwnsARelativeCursor() {
        // The §1.139 fix. Forwarding the touch position teleported the remote cursor
        // under the fingertip on the first sample of every gesture, so the absolute
        // coordinate RFB requires has to be synthesised from finger deltas instead.
        assertTrue(
            "VncSurfaceView must own a VncRelativeCursor",
            surfaceView.contains("VncRelativeCursor()")
        )
        assertTrue(
            "and must send its position, not the finger's",
            surfaceView.contains("cursor.frameX") && surfaceView.contains("cursor.frameY")
        )
    }

    @Test
    fun theFingerPositionIsNeverTranslatedToACursorPosition() {
        // The old helpers converted a touch *position* into a framebuffer position.
        // Deleted rather than left unused: they are exactly what the next edit would
        // reach for, and they compile.
        assertFalse(
            "toFrameX must be gone — a finger position is not a cursor position",
            surfaceView.contains("fun toFrameX(")
        )
        assertFalse(
            "toFrameY must be gone too",
            surfaceView.contains("fun toFrameY(")
        )
        assertTrue(
            "only the finger's movement is scaled",
            surfaceView.contains("fun toFrameDelta(")
        )
    }

    @Test
    fun theGestureMachineEmitsNoCoordinates() {
        // A coordinate on the action would be a second, wrong answer to "where does
        // this click land?" that the view could pick up again.
        val gesture = source("VncTouchGesture.kt")
        assertTrue(
            "Pointer carries only a button mask",
            gesture.contains("data class Pointer(val buttonMask: Int)")
        )
        assertTrue(
            "Wheel carries only a delta",
            gesture.contains("data class Wheel(val deltaY: Float)")
        )
    }

    @Test
    fun movementIsIntegratedOnlyWhenAMessageIsActuallySent() {
        // Applying a delta the gesture machine then drops would move this client's
        // cursor while the server's stayed put — a divergence that grows for the rest
        // of the session.
        val perform = surfaceView.substringAfter("private fun perform(")
        assertTrue(
            "the Pointer branch must flush pending movement first",
            perform.substringAfter("is VncPointerAction.Pointer ->")
                .substringBefore("is VncPointerAction.Wheel")
                .contains("flushPendingMovement()")
        )
    }

    @Test
    fun contactAloneDoesNotMoveTheCursor() {
        // Touching the glass must leave the cursor exactly where the user left it;
        // ACTION_DOWN only records an origin to measure movement from.
        val down = surfaceView.substringAfter("VncTouchKind.Down ->")
            .substringBefore("VncTouchKind.Move")
        assertTrue("ACTION_DOWN must only begin a gesture: $down", down.contains("beginTouch("))
        assertFalse(
            "ACTION_DOWN must not flush movement: $down",
            down.contains("flushPendingMovement()")
        )
    }

    @Test
    fun aFingerCountChangeResyncsTheOriginRatherThanReadingAsMovement() {
        // The reported primary position jumps when a second finger lands, which as a
        // delta would fling the cursor across the desktop.
        assertTrue(surfaceView.contains("fun resyncTouchOrigin("))
        val pointerDown = surfaceView.substringAfter("VncTouchKind.SecondaryDown ->")
            .substringBefore("VncTouchKind.SecondaryUp")
        assertTrue(
            "ACTION_POINTER_DOWN must resync: $pointerDown",
            pointerDown.contains("resyncTouchOrigin(")
        )
    }

    @Test
    fun aFrameGeometryChangeIsGivenToTheCursor() {
        // Without this the cursor is clamped to 0,0 forever: it has no coordinate
        // space until a frame arrives.
        assertTrue(
            "submitFrame must tell the cursor the framebuffer size",
            surfaceView.contains("cursor.resize(")
        )
    }

    // ── the keyboard has an explicit control ─────────────────────────────

    @Test
    fun tapToShowTheKeyboardIsGone() {
        // A tap is a left click on the remote desktop, so raising the IME on it threw
        // a keyboard over the window the user had just clicked into — and it was
        // still not a way to dismiss one.
        assertFalse(
            "onTouchEvent must not show the soft input",
            surfaceView.contains("showSoftInput(")
        )
    }

    @Test
    fun theSurfaceExposesAKeyboardToggle() {
        assertTrue(surfaceView.contains("fun toggleSoftKeyboard()"))
        assertTrue(
            "a toggle must be able to close the keyboard too",
            surfaceView.contains("toggleSoftInputFromWindow")
        )
    }

    @Test
    fun theViewerActuallyOffersTheToggle() {
        // The fullscreen desktop hides the app bar, so this is the only affordance
        // there is; an unreferenced toggle leaves the keyboard unreachable.
        assertTrue(viewer.contains("toggleSoftKeyboard()"))
        assertTrue(
            "the control needs an accessible name",
            viewer.contains("R.string.desktop_keyboard_toggle")
        )
    }

    // ── IME text is diffed, not retyped ─────────────────────────────────

    @Test
    fun theComposingRegionIsTracked() {
        // Typing the whole region on every update sent `hhehelhellhello` for `hello`
        // on any predictive or CJK keyboard.
        assertTrue(surfaceView.contains("VncComposingText()"))
        assertTrue(surfaceView.contains("composing.compose("))
        assertTrue(surfaceView.contains("composing.commit("))
    }

    @Test
    fun composingUpdatesDoNotTypeTheWholeRegion() {
        assertFalse(
            "setComposingText must not call keysymsFor directly",
            surfaceView.contains("VncTextInput.keysymsFor(")
        )
    }

    @Test
    fun abandoningARegionForgetsItSoTheNextOneDiffsFromEmpty() {
        assertTrue(surfaceView.contains("composing.finish()"))
    }

    @Test
    fun deletingUsesTheRegionAwareRetraction() {
        // beforeLength is UTF-16 chars while one BackSpace deletes a character, so an
        // emoji asked for 2 and would have eaten the character before it.
        assertTrue(surfaceView.contains("composing.retract("))
    }

    // ── the window is taken over as a value, and given back ──────────────

    @Test
    fun theScreenDerivesTheWindowModeRatherThanBranchingInline() {
        assertTrue(screen.contains("DesktopWindowMode.forState("))
    }

    @Test
    fun theWindowModeIsAppliedFromADisposableEffect() {
        // A LaunchedEffect only runs its else branch on a state *change*, so leaving
        // a running desktop left the bars hidden on every later screen (§1.125).
        assertTrue(screen.contains("DisposableEffect(windowMode)"))
        assertTrue(screen.contains("onDispose"))
    }

    @Test
    fun theOrientationIsRestoredToWhatItWasNotCleared() {
        // This activity is shared by every screen, so clearing to UNSPECIFIED would
        // silently override an orientation requested anywhere else.
        assertTrue(screen.contains("previousOrientation"))
        assertTrue(screen.contains("requestedOrientation = previousOrientation"))
    }

    @Test
    fun theBarsAreRestoredUnconditionallyOnDispose() {
        // Restoring only when the mode had hidden them would leak hidden bars out of
        // any state transition the effect did not see.
        // §1.140: anchored inside the window-mode effect specifically. The screen has
        // more than one DisposableEffect now (the VNC client is released on dispose
        // too), so the first `onDispose` in the file is not necessarily this one.
        val onDispose = screen
            .substringAfter("DisposableEffect(windowMode)")
            .substringAfter("onDispose {")
            .substringBefore("}")
        assertTrue(
            "onDispose must show the system bars: $onDispose",
            onDispose.contains("show(WindowInsetsCompat.Type.systemBars())")
        )
    }

    @Test
    fun theBarsAreTransientRatherThanStickyHidden() {
        // On a gesture-navigation device the bars are the only way back.
        assertTrue(screen.contains("BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE"))
    }

    @Test
    fun theRunningSurfaceIsNotInsetByTheScaffoldPadding() {
        // Insetting the one state that is supposed to be fullscreen would restore the
        // letterbox this phase removes.
        // Anchored on the *last* Running branch, cut at the NEXT DesktopUiState
        // branch: the sibling WebView run-mode panel (WebRunning) legitimately
        // takes the scaffold padding — it is not the fullscreen surface — so the
        // window must not sweep it in. Comments are stripped, because the
        // comment stating this rule names the call it forbids.
        val runningBranch = screen
            .substringAfterLast("is DesktopUiState.Running -> {")
            .substringBefore("is DesktopUiState.")
            .lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertTrue(runningBranch.contains("VncViewer("))
        assertFalse(
            "the running desktop must not take the scaffold padding: $runningBranch",
            runningBranch.contains("padding(padding)")
        )
    }

    // ── the connection is released when the screen goes away (§1.140) ─────

    @Test
    fun theVncClientIsDisconnectedOnDispose() {
        // §1.140: the connect/disconnect effect is keyed on the state, so its else
        // branch runs only on a *transition* — leaving a running desktop (the back
        // arrow) is not one, so the socket, its two buffered streams and the
        // server-side client connection leaked on every visit, and the next visit
        // opened another on top. Same defect class as §1.125's fullscreen effect.
        assertTrue(
            "DesktopScreen must release the VNC client on dispose",
            screen.contains("DisposableEffect(vncClient)")
        )
        val onDispose = screen
            .substringAfter("DisposableEffect(vncClient)")
            .substringAfter("onDispose {")
            .substringBefore("}")
        assertTrue(
            "and that dispose must disconnect: $onDispose",
            onDispose.contains("vncClient.disconnect()")
        )
    }

    @Test
    fun leavingTheDesktopDoesNotStopTheGuestDaemons() {
        // Xvnc and the X session are workspace-owned guest daemons (§1.83), so the
        // dispose above must close the *client* only — stopping them here would make
        // backing out of the screen kill the user's desktop, and §1.114's
        // reattach-on-re-entry would have nothing to reattach to.
        val onDispose = screen
            .substringAfter("DisposableEffect(vncClient)")
            .substringAfter("onDispose {")
            .substringBefore("}")
        assertFalse(
            "dispose must not stop the desktop: $onDispose",
            onDispose.contains("stopDesktop(")
        )
        assertTrue(
            "re-entry must still be able to reattach",
            source("DesktopViewModel.kt").contains("resumeIfAlreadyRunning(")
        )
    }

    // ── the rotation must not restart the activity ───────────────────────

    @Test
    fun theActivityHandlesTheConfigChangesItsOwnRotationCauses() {
        // Without this the activity is destroyed and recreated on entering *and*
        // leaving the desktop, restarting the nav graph and dropping the VNC socket.
        val activity = manifest.substringAfter("android:name=\".MainActivity\"")
            .substringBefore("</activity>")
        listOf("orientation", "screenSize", "screenLayout", "keyboardHidden").forEach {
            assertTrue("configChanges must include $it", activity.contains(it))
        }
    }

    @Test
    fun theAppItselfIsNotLockedToAnOrientation() {
        // The landscape request belongs to the desktop surface. A manifest-level
        // lock would rotate every screen in the app.
        val activity = manifest.substringAfter("android:name=\".MainActivity\"")
            .substringBefore("</activity>")
        assertTrue(
            "the app default must follow the user's rotation preference",
            activity.contains("android:screenOrientation=\"fullUser\"")
        )
    }

    @Test
    fun theKeyboardStillResizesTheWindow() {
        assertTrue(manifest.contains("android:windowSoftInputMode=\"adjustResize\""))
    }

    // ── unchanged invariants ─────────────────────────────────────────────

    @Test
    fun theSurfaceIsStillATextEditorSoTheImeHasADestination() {
        assertTrue(surfaceView.contains("onCheckIsTextEditor"))
        assertTrue(surfaceView.contains("onCreateInputConnection"))
    }

    @Test
    fun backIsNeverForwardedToTheRemoteDesktop() {
        // It is the only way off a fullscreen surface with no app bar.
        assertTrue(surfaceView.contains("KeyEvent.KEYCODE_BACK"))
    }

    @Test
    fun typedTextAndHardwareKeysStayOnSeparatePaths() {
        // Soft input down the hardware path types `a` for `A`; hardware input down
        // the text path doubles every modifier.
        assertTrue(viewer.contains("sendKeysymPress("))
        assertTrue(viewer.contains("sendKeysym("))
    }
}
