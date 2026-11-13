package com.fem.linexa.ui.screens.desktop

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §1.166 structural guard for the **§16.5 geometry diagnostics**.
 *
 * ## Why a source test
 *
 * The two values §16.5 needs are produced in the two places this repo cannot
 * instantiate on the JVM: `DesktopViewModel.xServerCommand` needs a real
 * `Application` for its `displayMetrics`, and `VncClient`'s framebuffer size comes
 * from a socket handshake. There is no Robolectric or Compose harness in this module
 * (§1.124(a)), so the alternative to a source test is no coverage of the wiring —
 * which is precisely where this class of defect lives. `DesktopGeometry` itself is
 * pure and covered by [DesktopGeometryTest]; what is asserted here is that the
 * numbers reach a surface a user can read, and that adding that reach changed
 * nothing else.
 *
 * Same technique and rationale as [DesktopRenderWiringSourceTest] (§1.147/§1.165),
 * [DesktopInputWiringSourceTest] (§1.138) and `DebugInfoWiringSourceTest` (§1.137).
 *
 * ## What is asserted, and why each property is the one that would silently regress
 *
 *  - both halves of the comparison are traced — a geometry request without the
 *    server's answer cannot settle the letterbox question, so a single-sided trace
 *    would look like coverage and answer nothing;
 *  - the trace records the values the decision actually used, not a recomputation —
 *    a second call to `geometryFor` could drift from the one Xvnc was given;
 *  - the geometry algorithm and its inputs are untouched, which is the constraint
 *    this whole change is under: a diagnostic that alters what it measures is worse
 *    than none;
 *  - no new logging mechanism appeared, and `VncClient` stayed dependency-free.
 */
class DesktopGeometryTraceWiringSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/MainActivity.kt")
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
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

    private val viewModel: String get() = source("DesktopViewModel.kt")
    private val screen: String get() = source("DesktopScreen.kt")
    private val client: String get() = source("VncClient.kt")

    /** The body of `xServerCommand`, where the requested geometry is decided. */
    private val xServerCommandBody: String
        get() = viewModel
            .substringAfter("private fun xServerCommand(")
            .substringBefore("fun stopDesktop(")

    // ── both halves of the §16.5 comparison are recorded ──

    @Test
    fun theRequestedGeometryReachesTheDebugTrace() {
        // The number Xvnc is started with. Logged since §1.125, but only through
        // Logger — logcat is UID-restricted and the app log file is app-private, so on
        // an unrooted device without adb this value did not exist for the reader.
        assertTrue(
            "xServerCommand must record the geometry to RuntimeTrace: $xServerCommandBody",
            xServerCommandBody.contains("runtimeTrace.record(")
        )
        assertTrue(
            "the traced line must carry the resolved geometry",
            xServerCommandBody.contains("geometry=\$geometry")
        )
    }

    @Test
    fun theTracedMetricsAreTheOnesTheDecisionUsed() {
        // Not a recomputation: the trace interpolates the same `metrics` instance and
        // the same `geometry` value that were passed to Xvnc. A second read of
        // displayMetrics, or a second geometryFor call, could report a different
        // measurement than the session is actually running.
        assertTrue(
            "the traced width must be the measured one: $xServerCommandBody",
            xServerCommandBody.contains("\${metrics.widthPixels}")
        )
        assertTrue(
            "and the traced height likewise",
            xServerCommandBody.contains("\${metrics.heightPixels}")
        )
        assertEquals(
            "displayMetrics must be read exactly once",
            1,
            Regex("resources\\.displayMetrics").findAll(xServerCommandBody).count()
        )
        assertEquals(
            "geometryFor must be called exactly once",
            1,
            Regex("DesktopGeometry\\.geometryFor\\(").findAll(xServerCommandBody).count()
        )
    }

    @Test
    fun theTraceCarriesTheDensityThatDistinguishesAnInsetReducedMeasurement() {
        // §16.5's surviving hypothesis is that the metrics may report an
        // inset-reduced window rather than the full display. Pixels alone cannot
        // settle that on an unknown device; density is what makes the reading
        // comparable to a dp-denominated window size.
        assertTrue(
            "density must be traced: $xServerCommandBody",
            xServerCommandBody.contains("density=\${metrics.density}")
        )
    }

    @Test
    fun theStateTheMeasurementWasTakenInIsRecorded() {
        // The audit's finding is about ORDERING, not just values: the same number read
        // before and after the window enters fullscreen-landscape is different
        // evidence. `DesktopWindowMode.forState` only requests landscape for `Running`,
        // so the state at measurement time is what says which side of the transition
        // the reading came from.
        assertTrue(
            "the measurement state must be traced: $xServerCommandBody",
            xServerCommandBody.contains("measuredInState=")
        )
    }

    @Test
    fun theServerAnnouncedFramebufferAlsoReachesTheTrace() {
        // The other half. The letterbox mechanism is a relationship between the
        // requested geometry and what ServerInit came back with, so a trace that
        // recorded only the request could not falsify it.
        assertTrue(
            "the ViewModel must expose a framebuffer-geometry recorder: $viewModel",
            viewModel.contains("fun recordFramebufferGeometry(")
        )
        assertTrue(
            "and the screen must feed it the client's published size: $screen",
            screen.contains("viewModel.recordFramebufferGeometry(")
        )
    }

    @Test
    fun theFramebufferTraceSkipsThePreHandshakeZero() {
        // frameWidth/frameHeight start at 0 and are set by ServerInit. Recording the
        // initial 0x0 would put a line that is not a measurement above the real one,
        // in a panel whose only job is to be read correctly.
        val effect = screen
            .substringAfter("LaunchedEffect(workspaceId, frameWidth, frameHeight)")
            .substringBefore("DisposableEffect(vncClient)")
        assertTrue(
            "the recorder must be guarded on a real geometry: $effect",
            effect.contains("frameWidth > 0") && effect.contains("frameHeight > 0")
        )
    }

    // ── the geometry itself is unchanged ──

    @Test
    fun theGeometryAlgorithmWasNotTouched() {
        // The constraint the whole change is under. `DesktopGeometry` is pure and this
        // diagnostic must not have edged into it — an instrument that alters its
        // subject is worse than no instrument.
        val geometry = source("DesktopGeometry.kt")
        assertFalse(
            "DesktopGeometry must not know about tracing: $geometry",
            geometry.contains("RuntimeTrace") || geometry.contains("runtimeTrace")
        )
        assertFalse(
            "and must not log either — it is a pure decision",
            geometry.contains("Logger.")
        )
    }

    @Test
    fun theMetricsSourceAndTheXvncArgumentsAreUnchanged() {
        // Desktop Zoom work (2026-08) — UPDATED deliberately, and this is the
        // §16.5 fix the original pin deferred ("§16.5's *fix* would change
        // exactly these two things"): the framebuffer geometry is now derived
        // from the display's REAL bounds (maximumWindowMetrics on API 30+,
        // getRealMetrics below), which are a property of the panel rather than
        // of the window's transient configuration. The forensic audit's §4.2
        // proved the old displayMetrics source is read before the window
        // enters fullscreen-landscape, so any inset/orientation difference
        // reappeared as a permanent letterbox. The old source is still read —
        // exactly once, for the side-by-side trace comparison — and remains
        // the fallback when the real bounds are unreadable.
        //
        // What is still forbidden is everything the original pin was actually
        // protecting against: measuring from *window* bounds that depend on
        // insets (currentWindowMetrics), or introducing WindowInsets padding
        // into the desktop surface.
        assertTrue(
            "the real display must be the geometry source: $xServerCommandBody",
            xServerCommandBody.contains("realDisplayBounds()")
        )
        assertTrue(
            "and it must feed the geometry decision",
            xServerCommandBody.contains("DesktopGeometry.geometryFor(")
        )
        assertEquals(
            "the legacy displayMetrics reading survives exactly once, for the trace comparison: $xServerCommandBody",
            1,
            Regex("resources\\.displayMetrics").findAll(xServerCommandBody).count()
        )
        assertFalse(
            "no *window*-scoped metrics may measure the desktop (currentWindowMetrics): $viewModel",
            viewModel.contains("currentWindowMetrics")
        )
        assertFalse(
            "and no WindowInsets source may have been introduced: $viewModel",
            viewModel.contains("WindowInsets")
        )
        assertTrue(
            "the command must still come from DesktopGeometry.xvncCommand, now with the " +
                "per-workspace display, port, security args, derived DPI and the EARNED " +
                "-localhost capability (\u00a77/\u00a713; 2026-09-05 TinyContainer parity): $xServerCommandBody",
            xServerCommandBody.contains(
                "DesktopGeometry.xvncCommand(\n" +
                "            geometry,\n" +
                "            display = display,\n" +
                "            rfbPort = port,\n" +
                "            securityArgs = securityArgs,\n" +
                "            dpi = dpi,\n" +
                "            localhostSupported = localhostSupported\n" +
                "        )"
            )
        )
    }

    @Test
    fun theFullscreenOrderWasNotChanged() {
        // The ordering the audit found is the thing under investigation; changing it
        // here would destroy the evidence. Xvnc is still started from the coroutine
        // while the state is `Starting`, and the window still follows `Running`.
        assertTrue(
            "Xvnc must still be started with the state-derived command — the signature " +
                "gained the Desktop-Modes port/security args, the \u00a77 per-workspace display and " +
                "the 2026-09-05 localhost-capability parameter, but the state-derived call " +
                "shape stands: $viewModel",
            viewModel.contains("xServerCommand(\n                        workspaceId,\n                        port,\n                        securityArgs,\n                        display,\n                        discovery.localhostSupported\n                    )") ||
                viewModel.contains("xServerCommand(workspaceId, port, securityArgs, display, discovery.localhostSupported)")
        )
        assertTrue(
            "the window mode must still be derived from the desktop state: $screen",
            screen.contains("DesktopWindowMode.forState(desktopState)")
        )
    }

    // ── no new mechanism, and the client stays clean ──

    @Test
    fun noNewLoggingSystemWasIntroduced() {
        // The task's constraint, and the reason RuntimeTrace was chosen: the Workspace
        // Debug panel already renders and copies it, and its append is already gated on
        // DebugCapture inside `record`.
        assertTrue(
            "the ViewModel must use the existing RuntimeTrace: $viewModel",
            viewModel.contains("import com.fem.linexa.service.runtime.RuntimeTrace")
        )
        assertEquals(
            "exactly six trace call sites expected (X11 provider selection + X11 READY + " +
                "preset install + Xvnc geometry request + framebuffer answer + the 2026-09-05 " +
                "Xvnc-start failure forensics, shared by both start paths)",
            6,
            Regex("runtimeTrace\\.record\\(").findAll(viewModel).count()
        )
    }

    @Test
    fun theVncClientWasNotGivenADependency() {
        // VncClient is constructed with `remember { VncClient() }` and owns nothing but
        // its socket and frame state. Injecting a trace into it would mean making the
        // render path Hilt-managed — an architectural change §16.5 does not justify.
        // The framebuffer size is already published as a StateFlow, so nothing had to
        // change in there at all.
        assertFalse(
            "VncClient must not reference RuntimeTrace: $client",
            client.contains("RuntimeTrace")
        )
        assertTrue(
            "the framebuffer size must still be published for the screen to read",
            client.contains("val frameWidth: StateFlow<Int>") &&
                client.contains("val frameHeight: StateFlow<Int>")
        )
    }

    @Test
    fun theDiagnosticsAddNoWorkToTheFramePath() {
        // Same rule §1.165 followed: the trace is written once per desktop start and
        // once per framebuffer geometry, never per frame. The receive loop and the
        // upload report must not mention it.
        val readBody = client
            .substringAfter("private suspend fun readServerMessage()")
            .substringBefore("private fun readRectangle(")
        assertFalse(
            "the receive loop must not trace: $readBody",
            readBody.contains("runtimeTrace") || readBody.contains("RuntimeTrace")
        )
        assertFalse(
            "and neither must the viewer: ",
            source("VncViewer.kt").contains("RuntimeTrace")
        )
    }
}
