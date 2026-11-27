package com.fem.linexa.service.runtime

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 2026-08-30 — session construction MUST run on the app's main Looper thread.
 *
 * Why a source test: the defect is a thread-affinity contract, and the failing
 * operation is a library constructor — a JVM-only harness (§1.124(a)) can
 * neither construct the Android types nor spawn the offending dispatcher.
 *
 * The device-proven failure: the vendored `TerminalSession` constructor
 * assigns `mMainThreadHandler = new MainThreadHandler()`, whose no-arg
 * `Handler()` super adopts `Looper.myLooper()` — null on any coroutines worker.
 * The never-zero-terminals replacement open after a natural exit ran from
 * `WorkspaceTerminalManager`'s `Dispatchers.Default` scope, so the constructor
 * threw `RuntimeException("Can't create handler inside thread
 * Thread[DefaultDispatcher-worker-N,5,main] that has not called
 * Looper.prepare()")` — surfaced verbatim as the terminal's "Session error:".
 *
 * The fix enforces the contract at the single choke point every pty creation
 * passes through (`PRootContainerRuntime.openTerminal` →
 * `ptySessionFactory.create`): the create call is wrapped in
 * `withContext(sessionCreationDispatcher)` — bound to
 * `Dispatchers.Main.immediate` in production — so EVERY caller (the service
 * start path, the UI's create path, the exit-driven replacement path) is
 * correct by construction, and the emulator's single-threaded write/read model
 * (writer = the session Handler, reader = the UI thread) stays intact.
 */
class SessionConstructionThreadingSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/service/runtime/container/PRootContainerRuntime.kt")
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

    private fun source(relative: String): String =
        File(moduleRoot, "src/main/java/$relative").readText()

    private val runtime: String
        get() = source("com/fem/linexa/service/runtime/container/PRootContainerRuntime.kt")

    private val manager: String
        get() = source("com/fem/linexa/service/runtime/WorkspaceTerminalManager.kt")

    private val provider: String
        get() = source("com/fem/linexa/service/runtime/pty/TermuxTerminalSessionProvider.kt")

    /** The create-call slice of openTerminal, from the try to the catch. */
    private val createCall: String
        get() = runtime.substringAfter("val session = try {")
            .substringBefore("} catch (e: Exception) {\n                sessionCounter.decrementAndGet()")

    @Test
    fun thePtyCreateCallIsDispatchedToTheMainLooper() {
        assertTrue(
            "ptySessionFactory.create must run inside withContext(sessionCreationDispatcher) — " +
                "the injected dispatcher binds Dispatchers.Main.immediate in production, and the " +
                "vendored TerminalSession constructor captures the constructing thread's Looper " +
                "(no-arg Handler), which does not exist on coroutines workers",
            createCall.contains("withContext(sessionCreationDispatcher + NonCancellable)") &&
                createCall.contains("ptySessionFactory.create(")
        )
    }

    @Test
    fun theProductionDispatcherIsTheMainLooperDispatcher() {
        // The DI binding is what makes the hop meaningful in production: the
        // injected dispatcher must be Dispatchers.Main.immediate (inline when
        // already on Main, dispatched to Main from any worker — including the
        // exit observer's Dispatchers.Default scope).
        val di = source("com/fem/linexa/di/AppModule.kt")
        assertTrue(
            "the SessionCreationDispatcher binding must provide Dispatchers.Main.immediate",
            di.contains("provideSessionCreationDispatcher") &&
                di.contains("Dispatchers.Main.immediate")
        )
        assertTrue(
            "the runtime must take the dispatcher through the qualified DI seam",
            runtime.contains("@SessionCreationDispatcher")
        )
        assertTrue(
            "the qualifier must exist",
            source("com/fem/linexa/di/SessionCreationDispatcher.kt").contains("@Qualifier")
        )
    }

    @Test
    fun theCreateCallStaysInsideTheSessionRegistrationBlock() {
        // The Main hop must not swallow the F-6 slot bookkeeping: the
        // increment happens before, the decrement-on-throw after.
        val before = runtime.substringBefore("val session = try {")
        assertTrue(
            "the live-session slot must be registered before the create call (F-6)",
            before.contains("liveSessions")
        )
        val after = runtime.substringAfter("} catch (e: Exception) {\n                sessionCounter.decrementAndGet()")
        assertTrue(
            "a failed create must release its slot and rethrow",
            after.contains("throw e")
        )
    }

    @Test
    fun theExitDrivenReplacementFunnelStaysOnOpenTerminal() {
        // Both never-zero entry points (explicit close + natural exit) must go
        // through openTerminal — the choke point that now enforces the Main
        // dispatch — rather than constructing sessions by other means.
        assertTrue(
            manager.contains("ensureTerminalPresence") &&
                Regex("ensureTerminalPresence[\\s\\S]{0,900}?openTerminal\\(workspaceId, rootfsPath, replacementId\\)")
                    .containsMatchIn(manager)
        )
        assertTrue(
            "the exit observer's replacement must reuse openTerminal (the Main-dispatched create)",
            manager.contains("ensureTerminalPresence(workspaceId)")
        )
    }

    @Test
    fun theExitObserverMayStayOnDefaultBecauseConstructionIsGuarded() {
        // The observer scope is background work (awaitExit, state pruning,
        // container teardown) — none of it touches View/Handler APIs. The
        // contract is enforced at the create choke point, not by re-dispatching
        // the whole observer to Main.
        assertTrue(
            "the manager's observer scope remains Dispatchers.Default (background work only)",
            manager.contains("CoroutineScope(SupervisorJob() + Dispatchers.Default)")
        )
    }

    @Test
    fun theScreenRevisionIsPartOfTheTerminalScreenSeam() {
        assertTrue(
            "TermuxTerminalSessionProvider must expose the conflated repaint revision",
            provider.contains("val screenRevision: StateFlow<Int>")
        )
    }
}
