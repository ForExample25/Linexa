package com.fem.linexa.test.support

import android.app.Application
import android.content.pm.ApplicationInfo
import com.fem.linexa.core.logging.DiagnosticLogManager
import com.fem.linexa.domain.workspace.DistroInfo
import com.fem.linexa.domain.workspace.RuntimeConfig
import com.fem.linexa.domain.workspace.TerminalSettings
import com.fem.linexa.domain.workspace.WorkspaceMetadata
import com.fem.linexa.domain.workspace.WorkspaceRepository
import com.fem.linexa.domain.workspace.WorkspaceStatus
import com.fem.linexa.service.installer.GuestRootfsInitializer
import com.fem.linexa.service.installer.RootfsExtractor
import com.fem.linexa.service.rootfs.RootFsInspector
import com.fem.linexa.service.rootfs.WorkspaceRootFsMetadata
import com.fem.linexa.service.runtime.RuntimeTrace
import com.fem.linexa.service.runtime.SessionManager
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.runtime.WorkspaceHealthRegistry
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager
import com.fem.linexa.service.runtime.container.PRootContainerRuntime
import com.fem.linexa.service.runtime.container.PRootLauncher
import com.fem.linexa.data.hostinfo.AndroidHostInfoSources
import com.fem.linexa.data.hostinfo.AndroidHostSystemInfoProvider
import com.fem.linexa.service.runtime.hostinfo.HostInfoRuntimeBridge
import com.fem.linexa.domain.runtime.TerminalState
import com.fem.linexa.service.runtime.pty.PtyEvent
import com.fem.linexa.service.runtime.pty.PtySession
import com.fem.linexa.service.runtime.pty.PtySessionFactory
import com.fem.linexa.service.workspaceconfig.WorkspaceConfigParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File
import com.fem.linexa.service.runtime.graphics.GpuProcess
import com.fem.linexa.service.runtime.graphics.GpuProcessFactory
import com.fem.linexa.service.runtime.graphics.WorkspaceGpuSupervisor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito

/**
 * §1.70 — Roadmap Item 1: JVM-level wiring that lets the REAL
 * [PRootContainerRuntime], [PRootLauncher] and [BootstrapManager] run
 * against the fake PRoot stand-in ([FakeProot]) instead of the real
 * `libproot.so`.
 *
 * The only substitution is `ApplicationInfo.nativeLibraryDir` on a
 * Mockito-faked [Application], pointed at a temporary directory that holds
 * the fake `libproot.so` script. Every other class is the real production
 * class, so these tests exercise the actual argv/env construction
 * (`PRootLauncher.buildLaunchSpec`), the real seccomp detector
 * (`BootstrapManager.isSeccompKernelBugFailure`) and the real session code
 * paths in `PRootContainerRuntime`.
 *
 * This file lives under `src/test` — the mock can only be constructed from
 * test code, making the fake-PRoot substitution unreachable in production.
 */
class ProotRuntimeTestHarness(
    val prootDir: File,
    val filesDir: File,
    val cacheDir: File
) {

    lateinit var bootstrapManager: BootstrapManager
    lateinit var launcher: PRootLauncher
    lateinit var runtime: PRootContainerRuntime
    lateinit var rootfsExtractor: RootfsExtractor
    lateinit var ptyFactory: RecordingPtySessionFactory
    lateinit var workspaceRepository: InMemoryWorkspaceRepository

    /** Register a rootfs directory the harness should create for a test. */
    fun fakeRootfs(): File {
        val dir = File(filesDir, "rootfs-${Math.abs(System.nanoTime())}")
        require(dir.mkdirs()) { "could not create fake rootfs $dir" }
        return dir
    }

    fun create() {
        val app = Mockito.mock(Application::class.java)
        val appInfo = Mockito.mock(ApplicationInfo::class.java)
        appInfo.nativeLibraryDir = prootDir.absolutePath
        Mockito.`when`(app.applicationInfo).thenReturn(appInfo)
        Mockito.`when`(app.filesDir).thenReturn(filesDir)
        Mockito.`when`(app.cacheDir).thenReturn(cacheDir)
        // No shared-storage bind: getExternalFilesDir returns a dir that does
        // not exist, matching a fresh install with no shared folder yet.
        Mockito.`when`(app.getExternalFilesDir(any())).thenReturn(File(filesDir, "shared"))

        // §1.80: the real runtime now runs `chmod 1777` through the bundled
        // busybox `chmod` applet when preparing guest /tmp — so the harness
        // must provide a working applet chain. FakeProot writes a dispatcher
        // at nativeLibraryDir/lib__bin__busybox__.so that forwards `chmod`
        // to the host's real /bin/chmod.
        FakeProot.writeFakeBusybox(prootDir)

        bootstrapManager = BootstrapManager(app)
        launcher = PRootLauncher(app, bootstrapManager)
        ptyFactory = RecordingPtySessionFactory()
        rootfsExtractor = RootfsExtractor(app, bootstrapManager, DiagnosticLogManager(app))
        workspaceRepository = InMemoryWorkspaceRepository()
        val fileManager = WorkspaceFileManager(app)
        val configParser = WorkspaceConfigParser()
        val harnessHealth = WorkspaceHealthRegistry()
        runtime = PRootContainerRuntime(
            launcher = launcher,
            ptySessionFactory = ptyFactory,
            fileManager = fileManager,
            configParser = configParser,
            bootstrapManager = bootstrapManager,
            runtimeTrace = RuntimeTrace(),
            guestRootfsInitializer = GuestRootfsInitializer(bootstrapManager, DiagnosticLogManager(app)),
            workspaceRepository = workspaceRepository,
            // §1.118: real (not mocked) — with no config.yaml on disk it
            // returns null for every workspace, so the shell resolution keeps
            // its pre-§1.118 probe behaviour unless a test writes a declaring
            // config.yaml.
            workspaceRootFsMetadata = WorkspaceRootFsMetadata(
                fileManager,
                RootFsInspector(configParser)
            ),
            // Host System Information bridge (2026-08-26 track): REAL
            // provider against the faked Application. Construction does no
            // I/O and every collection is individually guarded, so on a JVM
            // this yields honest "unknown" categories — exactly what the
            // bridge's fail-open contract requires under unit tests.
            hostInfoBridge = HostInfoRuntimeBridge(
                hostSystemInfoProvider = AndroidHostSystemInfoProvider(
                    sources = AndroidHostInfoSources(app)
                ),
                fileManager = fileManager
            ),
            // Transformation spec §6/§8: real registry + real SessionManager —
            // daemon-exit watchers report through the real path under tests.
            healthRegistry = WorkspaceHealthRegistry(),
            sessionManager = SessionManager(RuntimeTrace(), WorkspaceHealthRegistry()),
            // 2026-08-30 (session-lifecycle fix): production binds the app's
            // main-Looper dispatcher for session construction (the vendored
            // TerminalSession constructor captures the constructing thread's
            // Looper). JVM tests have no Main dispatcher; Unconfined keeps the
            // create call inline, exactly like the pre-dispatch-hop behaviour
            // the assertions were written against.
            sessionCreationDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            // 2026-09 GPU work: the harness never enables the GPU toggle and
            // the fake factory never spawns a real process — the supervisor
            // still runs (its env merge is a no-op at empty), so the launch
            // path exercises the same code the production runtime does.
            gpuSupervisor = WorkspaceGpuSupervisor(
                runtimeTrace = RuntimeTrace(),
                healthRegistry = harnessHealth,
                nativeLibraryDir = "/nonexistent-native-dir",
                processFactory = object : GpuProcessFactory {
                    override fun start(
                        command: List<String>,
                        env: Map<String, String>,
                        logFile: File
                    ): GpuProcess = throw java.io.IOException("GPU binaries are not available in tests")
                },
                logDirFor = { java.io.File(System.getProperty("java.io.tmpdir")) },
                scope = kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
                )
            ),
            // Issue #2 (2026-09-03): the real coordinator — it is pure
            // coroutines + JDK, so JVM tests exercise the same queue the
            // production runtime uses.
            packageOpCoordinator = com.fem.linexa.domain.packagemanager.PackageOperationCoordinator()
        )
    }

    /** List of every `create` call the fake pty factory received. */
    val createCalls: List<RecordingPtySessionFactory.CreateCall> get() = ptyFactory.calls

    companion object {

        /**
         * §1.50 memoization lives in BootstrapManager's companion object
         * (shared since Roadmap Item 3 by PRootContainerRuntime AND
         * RootfsExtractor) and is deliberately shared process-wide in
         * production. Tests must reset it between cases, or one test's
         * `needsNoSeccomp=true` silently changes every later test's
         * behavior. Test-only access — production never resets it.
         */
        fun resetSeccompMemo() {
            val root = BootstrapManager::class.java
            val companion = root.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
                ?: error("no companion object")
            // @Volatile boolean memo fields are JVM-mangled (and may be hoisted
            // onto the enclosing class as statics), so we do not know their
            // exact container or name up front. Collect every matching boolean
            // field from BOTH the companion instance and the class statics and
            // reset each hit; if nothing matches, fail loudly so a future
            // Kotlin/compiler change surfaces as this harness error instead of
            // cross-test memo leakage.
            val candidates = mutableListOf<java.lang.reflect.Field>()
            candidates += companion.javaClass.declaredFields
            candidates += root.declaredFields
            val hits = candidates
                .filter { it.type == java.lang.Boolean::class.java || it.type == java.lang.Boolean.TYPE }
                .filter { f ->
                    val n = f.name.lowercase()
                    n.contains("needsnoseccomp") || n.contains("seccompprobedone")
                }
                .distinctBy { it }
            check(hits.size >= 2) {
                "seccomp memo reset found ${hits.size} candidate(s) on companion+class; " +
                    "fields are: ${(candidates.map { it.name }).distinct().sorted()}"
            }
            for (field in hits) {
                field.isAccessible = true
                if (field.type == java.lang.Boolean::class.javaPrimitiveType) {
                    field.setBoolean(companion, false)
                } else {
                    field.set(companion, false)
                }
            }
        }

        /**
         * §1.83: mark the one-time seccomp probe as already-done WITHOUT
         * enabling the fallback. Daemon-host tests that use the
         * [FakeProot.Scenario.DAEMON_STAYS_ALIVE] scenario must call this
         * first: [PRootContainerRuntime.startDaemon] runs
         * `resolveSeccompFallback`, whose probe would otherwise launch the
         * long-lived fake proot and hang for the whole probe timeout. Marking
         * the probe done short-circuits it (needsNoSeccomp stays false, so
         * the daemon's env is unchanged — the thing these tests assert on).
         */
        fun markSeccompProbeDone() {
            val root = BootstrapManager::class.java
            val companion = root.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
                ?: error("no companion object")
            val candidates = mutableListOf<java.lang.reflect.Field>()
            candidates += companion.javaClass.declaredFields
            candidates += root.declaredFields
            val probeDone = candidates
                .filter { it.type == java.lang.Boolean::class.java || it.type == java.lang.Boolean.TYPE }
                .filter { it.name.lowercase().contains("seccompprobedone") }
                .distinctBy { it }
            check(probeDone.size >= 1) {
                "seccomp probe-done field not found; fields are: ${(candidates.map { it.name }).distinct().sorted()}"
            }
            val needsNoSeccomp = candidates
                .filter { it.type == java.lang.Boolean::class.java || it.type == java.lang.Boolean.TYPE }
                .filter { it.name.lowercase().contains("needsnoseccomp") }
                .distinctBy { it }
            for (field in probeDone) {
                field.isAccessible = true
                if (field.type == java.lang.Boolean::class.javaPrimitiveType) {
                    field.setBoolean(companion, true)
                } else {
                    field.set(companion, true)
                }
            }
            for (field in needsNoSeccomp) {
                field.isAccessible = true
                if (field.type == java.lang.Boolean::class.javaPrimitiveType) {
                    field.setBoolean(companion, false)
                } else {
                    field.set(companion, false)
                }
            }
        }
    }
}

/** A pty factory that records what it was asked to launch and returns a stub session. */
class RecordingPtySessionFactory : PtySessionFactory {
    val calls = mutableListOf<CreateCall>()
    private var nextPid = 1000

    /**
     * §1.97/W2: when true, [create] hands back [ControllableStubPtySession]
     * stand-ins whose exit the test drives explicitly. Default false keeps the
     * inert [StubPtySession] (isAlive=true, awaitExit()=0) used by the pre-W2
     * runtime tests.
     */
    var controllable: Boolean = false

    /**
     * §1.145: run before [create] records anything, on whatever thread the
     * runtime calls the factory from.
     *
     * Two uses: observing the *thread* the pty fork would happen on (the fix's
     * central invariant — terminal-lib may only be constructed on the caller's
     * Looper thread), and simulating a failure/cancellation exactly at the fork
     * so the scratch-dir release path can be asserted.
     */
    var beforeCreate: (() -> Unit)? = null

    data class CreateCall(
        val workspaceId: String,
        val executable: String,
        val args: List<String>,
        val env: Map<String, String>,
        val workingDirectory: String,
        /**
         * 2026-08-31: the terminal configuration home the session was created
         * with — the guest HOME resolved to its HOST rootfs path, i.e. the
         * directory `TermuxPtySession` reads `.termux/colors.properties` and
         * `.termux/font.ttf` from. Recorded so tests can pin that terminal
         * appearance provisioning happens at exactly the path the pty reads.
         */
        val terminalConfigHome: String? = null,
        /**
         * §1.145: name of the thread [create] was invoked on. `terminal-lib`'s
         * `TerminalSession` constructor adopts the constructing thread's Looper
         * and then owns emulator state from it, so "which thread forks the pty"
         * is a correctness property of the runtime, not an implementation
         * detail — and it is invisible in argv/env alone.
         */
        val callThread: String = ""
    ) {
        val envHadNoSeccomp: Boolean get() = env["PROOT_NO_SECCOMP"] == "1"
    }

    override fun create(
        workspaceId: String,
        executable: String,
        args: List<String>,
        env: Map<String, String>,
        workingDirectory: String,
        columns: Int,
        rows: Int,
        terminalConfigHome: String?
    ): PtySession {
        beforeCreate?.invoke()
        calls += CreateCall(
            workspaceId,
            executable,
            args,
            env,
            workingDirectory,
            terminalConfigHome = terminalConfigHome,
            callThread = Thread.currentThread().name
        )
        val id = "pty-${calls.size}"
        return if (controllable) {
            ControllableStubPtySession(id = id, workspaceId = workspaceId, pid = nextPid++)
        } else {
            StubPtySession(id = id, workspaceId = workspaceId, pid = nextPid++)
        }
    }
}

/** Minimal inert [PtySession] stand-in for JVM tests. */
class StubPtySession(
    override val id: String,
    override val workspaceId: String,
    override val pid: Int
) : PtySession {
    override val isAlive: Boolean = true
    override val exitCode: Int? = null
    private val _events = MutableSharedFlow<PtyEvent>()
    override val events: SharedFlow<PtyEvent> = _events
    override fun write(data: ByteArray) = Unit
    override fun write(text: String) = Unit
    override fun resize(columns: Int, rows: Int) = Unit
    override fun currentTranscript(): String = ""
    override fun recentOutput(maxChars: Int): String = ""
    override suspend fun awaitExit(): Int = 0
    override fun destroy() = Unit
}

/**
 * §1.97/W2: [PtySession] stand-in whose exit the test triggers via
 * [completeExit]. [awaitExit] suspends until the exit completes, so the
 * WorkspaceTerminalManager's exit observer stays parked exactly like a real
 * session's would; [isAlive]/[exitCode] follow the completed exit so the
 * container-side `isTerminalAlive` checks behave realistically too.
 */
class ControllableStubPtySession(
    override val id: String,
    override val workspaceId: String,
    override val pid: Int
) : PtySession {
    private val exitSignal = CompletableDeferred<Int>()

    /** Test-controlled screen/transcript content (default empty = no output yet). */
    var transcriptOutput: String = ""

    /** Test-controlled OSC title, as a real terminal would report it. */
    var sessionTitle: String? = null

    /** Test-controlled foreground process-group leader name. */
    var foregroundProcess: String? = null

    /** Every text write, in order — used to prove a command reached this pty. */
    val writes: MutableList<String> = mutableListOf()

    override val isAlive: Boolean get() = !exitSignal.isCompleted
    override val exitCode: Int? get() = if (exitSignal.isCompleted) exitSignal.getCompleted() else null
    override val title: String? get() = sessionTitle
    private val _events = MutableSharedFlow<PtyEvent>()
    override val events: SharedFlow<PtyEvent> = _events
    override fun foregroundProcessName(): String? = foregroundProcess
    override fun write(data: ByteArray) = Unit
    override fun write(text: String) {
        writes += text
    }
    override fun resize(columns: Int, rows: Int) = Unit
    override fun currentTranscript(): String = transcriptOutput
    override fun recentOutput(maxChars: Int): String = transcriptOutput.takeLast(maxChars.coerceAtLeast(0))
    override suspend fun awaitExit(): Int = exitSignal.await()

    /**
     * §1.191: destroying a pty SIGKILLs the child, exactly like the real
     * backend — terminal-lib's `TerminalSession.finishIfRunning` sends
     * `SIGKILL` via `Os.kill`, and its `getExitStatus()` then reports the
     * negated signal number (`-9`).
     *
     * Modelling that is what makes an intentional close testable: the manager
     * relies on cancelling a terminal's exit observer *before* destroying its
     * pty, so a user-initiated close is not misread as an OS kill. With an inert
     * `destroy()` a broken cancel-then-destroy ordering looked identical to a
     * correct one, so the guard was untested.
     */
    override fun destroy() {
        completeExit(TerminalState.SIGKILL_EXIT_STATUS)
    }

    /** Trigger the exit the manager observer is awaiting. */
    fun completeExit(code: Int) {
        if (exitSignal.isCompleted) return
        exitSignal.complete(code)
    }
}

/**
 * In-memory [WorkspaceRepository] for JVM runtime tests. Only the surface the
 * runtime actually reads ([getById], [updateFeatureToggles]) is populated; the
 * flow-based observers are inert so the fake stays minimal. Seed workspaces via
 * [add] before launching.
 *
 * §1.110: `open` so a test can override one method to simulate a failing store
 * (the feature reconciler must fall back to defaults rather than propagate a
 * repository failure into the launch coroutine).
 */
open class InMemoryWorkspaceRepository : WorkspaceRepository {
    private val records = mutableMapOf<String, WorkspaceMetadata>()

    fun add(metadata: WorkspaceMetadata) {
        records[metadata.id] = metadata
    }

    override fun observeAll(): Flow<List<WorkspaceMetadata>> = emptyFlow()
    override fun observeById(id: String): Flow<WorkspaceMetadata?> = emptyFlow()
    override suspend fun getById(id: String): WorkspaceMetadata? = records[id]

    override suspend fun insert(metadata: WorkspaceMetadata) {
        records[metadata.id] = metadata
    }

    override suspend fun update(metadata: WorkspaceMetadata) {
        records[metadata.id] = metadata
    }

    override suspend fun deleteById(id: String) {
        records.remove(id)
    }

    override suspend fun updateStatus(id: String, status: WorkspaceStatus) {
        records[id]?.let { records[id] = it.copy(status = status) }
    }

    override suspend fun updateFeatureToggles(id: String, toggles: Map<String, Boolean>) {
        records[id]?.let { records[id] = it.copy(featureToggles = toggles) }
    }

    override suspend fun updateAttributes(id: String, attributes: Map<String, String>) {
        records[id]?.let { records[id] = it.copy(attributes = it.attributes + attributes) }
    }
}

/**
 * Builds a minimal [WorkspaceMetadata] with the given feature toggles.
 *
 * [displayName] defaults to the id (as most tests don't care) but is
 * parameterized because §1.111 attributes a workspace's notifications with it,
 * so "the launch path names the workspace" needs a name distinguishable from the
 * id to be a real assertion.
 */
fun buildWorkspaceMetadata(
    id: String,
    featureToggles: Map<String, Boolean> = emptyMap(),
    displayName: String = id,
    attributes: Map<String, String> = emptyMap()
): WorkspaceMetadata = WorkspaceMetadata(
    id = id,
    status = WorkspaceStatus.Ready,
    distro = DistroInfo(name = "test", version = "1", arch = "arm64"),
    displayName = displayName,
    avatarLetter = "T",
    avatarColorHex = "#000000",
    rootfsPath = "",
    createdAt = 0L,
    runtimeConfig = RuntimeConfig(),
    terminalSettings = TerminalSettings(),
    featureToggles = featureToggles,
    attributes = attributes
)
