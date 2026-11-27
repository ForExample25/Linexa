package com.fem.linexa.service.runtime.graphics

import com.fem.linexa.domain.runtime.RuntimeDegradation
import com.fem.linexa.service.runtime.RuntimeTrace
import com.fem.linexa.service.runtime.WorkspaceHealthRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 2026-09 GPU work: the supervisor's lifecycle contract, against fakes —
 * start/stop determinism, the empty-env fallback contract, socket-verified
 * activation, crash reporting without restart loops, teardown idempotence,
 * and the stale-process sweep.
 */
class WorkspaceGpuSupervisorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var health: WorkspaceHealthRegistry
    private lateinit var factory: FakeGpuProcessFactory
    private lateinit var supervisor: WorkspaceGpuSupervisor
    private val watcherScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class FakeGpuProcessFactory : GpuProcessFactory {
        val started = mutableListOf<List<String>>()
        val created = mutableListOf<FakeProcess>()
        var neverBind = false
        var exitImmediately = false
        private val counter = AtomicInteger(0)

        override fun start(command: List<String>, env: Map<String, String>, logFile: File): GpuProcess {
            started.add(command)
            val pid = counter.incrementAndGet()
            val socketPath = command[command.indexOf("--socket-path") + 1]
            if (!exitImmediately && !neverBind) File(socketPath).createNewFile()
            val process = FakeProcess(pid, socketPath, aliveInitially = !exitImmediately)
            created.add(process)
            return process
        }
    }

    private class FakeProcess(private val pid: Int, private val socketPath: String, aliveInitially: Boolean) : GpuProcess {
        @Volatile private var alive = aliveInitially
        override val isAlive: Boolean get() = alive
        override fun pid(): Int = pid

        /** Emulate an UNCLEAN death (no destroy() was called). */
        fun dieUncleanly() {
            alive = false
            File(socketPath).delete()
        }

        override fun destroy() {
            alive = false
            File(socketPath).delete()
        }
    }

    @Before
    fun setUp() {
        health = WorkspaceHealthRegistry()
        factory = FakeGpuProcessFactory()
        supervisor = WorkspaceGpuSupervisor(
            runtimeTrace = RuntimeTrace(),
            healthRegistry = health,
            nativeLibraryDir = "",
            processFactory = factory,
            logDirFor = { tmp.newFolder() },
            scope = watcherScope
        )
    }

    @After
    fun tearDown() {
        watcherScope.coroutineContext.cancelChildren()
    }

    private fun bundledSupervisor(): WorkspaceGpuSupervisor {
        // Simulates the shipped build: point the probe at a directory
        // containing the full triplet (we cannot write into the real
        // nativeLibraryDir from a JVM test, so the fake factory stands in for
        // exec while the probe sees real files).
        val native = tmp.newFolder("native")
        File(native, VirglHostServer.BINARY_FILE).writeText("x")
        File(native, VirglHostServer.LIB_VIRGL_FILE).writeText("x")
        File(native, VirglHostServer.LIB_EPOXY_FILE).writeText("x")
        return WorkspaceGpuSupervisor(
            runtimeTrace = RuntimeTrace(),
            healthRegistry = health,
            nativeLibraryDir = native.absolutePath,
            processFactory = factory,
            logDirFor = { tmp.newFolder() },
            scope = watcherScope,
            watcherPollMs = 20
        )
    }

    // ── Toggle / availability gate ────────────────────────────────────────

    @Test
    fun `toggle off returns empty env and starts nothing`() = runTest {
        val env = supervisor.provisionForLaunch("ws1", tmp.root.absolutePath, enabled = false)

        assertTrue(env.isEmpty())
        assertTrue(factory.started.isEmpty())
        assertTrue(health.degradations("ws1").isEmpty())
    }

    @Test
    fun `binaries absent returns empty env with a precise degradation`() = runTest {
        val env = supervisor.provisionForLaunch("ws1", tmp.root.absolutePath, enabled = true)

        assertTrue(env.isEmpty())
        assertTrue(factory.started.isEmpty())
        val degradations = health.degradations("ws1")
        assertEquals(1, degradations.size)
        assertEquals("gpu", degradations[0].component)
        assertTrue(degradations[0].detail.contains("not bundled"))
        assertTrue(degradations[0].detail.contains(VirglHostServer.BINARY_FILE))
    }

    // ── Happy path ────────────────────────────────────────────────────────

    @Test
    fun `provision with binaries starts one server and returns the guest env`() = runTest {
        val s = bundledSupervisor()
        val env = s.provisionForLaunch("ws1", tmp.root.absolutePath, enabled = true)

        assertEquals(VirglHostServer.guestLaunchEnv(), env)
        assertEquals(1, factory.started.size)
        assertTrue(s.isRunning("ws1"))
        assertTrue(health.degradations("ws1").isEmpty())
        // The socket dir was placed under the rootfs's /run (the R-5 bind).
        val expectedDir = File(tmp.root, "run/linexa-gpu")
        assertTrue(expectedDir.isDirectory)
    }

    @Test
    fun `second provision for a live server is idempotent`() = runTest {
        val s = bundledSupervisor()
        s.provisionForLaunch("ws1", tmp.root.absolutePath, enabled = true)
        val env2 = s.provisionForLaunch("ws1", tmp.root.absolutePath, enabled = true)

        assertEquals(VirglHostServer.guestLaunchEnv(), env2)
        assertEquals(1, factory.started.size)
    }

    // ── Failure / fallback paths ──────────────────────────────────────────

    @Test
    fun `server that exits before binding returns empty env and degrades`() = runTest {
        factory.exitImmediately = true
        val s = bundledSupervisor()

        val env = s.provisionForLaunch("ws1", tmp.root.absolutePath, enabled = true)

        assertTrue(env.isEmpty())
        assertFalse(s.isRunning("ws1"))
        assertEquals(1, health.degradations("ws1").size)
        assertTrue(health.degradations("ws1")[0].detail.contains("exited before binding"))
    }

    @Test
    fun `server that never binds times out deterministically`() = runTest {
        factory.neverBind = true
        val s = bundledSupervisor()

        val env = s.provisionForLaunch("ws1", tmp.root.absolutePath, enabled = true)

        assertTrue(env.isEmpty())
        assertFalse(s.isRunning("ws1"))
        assertTrue(health.degradations("ws1")[0].detail.contains("did not bind"))
    }

    @Test
    fun `unexpected server crash degrades once and does not restart`() = runTest {
        val s = bundledSupervisor()
        s.provisionForLaunch("ws1", tmp.root.absolutePath, enabled = true)
        assertTrue(s.isRunning("ws1"))
        assertTrue(health.degradations("ws1").isEmpty())

        // Unclean death: no destroy() was called, the process simply dies —
        // the watcher (REAL time, in watcherScope) must observe it exactly
        // once. The clock below is real; runTest's virtual clock must not
        // outrun the watcher's wall-clock poll.
        val watcher = requireNotNull(s.watcherFor("ws1")) { "no exit watcher was registered" }
        factory.created.single().dieUncleanly()
        // Join the watcher itself: the degradation is written before the job
        // completes, so this is the exact "crash observed and reported" edge.
        // `runBlocking` keeps the wait on real time — a `withTimeout` on the
        // runTest dispatcher would advance virtual time instead and abandon the
        // real-time watcher. The test injects a 20 ms poll interval, so this
        // completes in milliseconds even on a throttled device.
        runBlocking {
            kotlinx.coroutines.withTimeout(10_000) { watcher.join() }
        }
        val degradations = health.degradations("ws1")
        assertEquals(1, degradations.size)
        assertEquals("gpu", degradations[0].component)
        assertTrue(degradations[0].detail.contains("exited unexpectedly"))
        // No restart loop: no second process was ever spawned.
        assertEquals(1, factory.started.size)
        assertFalse(s.isRunning("ws1"))
    }

    @Test
    fun `provision throw degrades instead of crashing the launch path`() = runTest {
        val throwing = WorkspaceGpuSupervisor(
            runtimeTrace = RuntimeTrace(),
            healthRegistry = health,
            nativeLibraryDir = bundledNativeDir().absolutePath,
            processFactory = object : GpuProcessFactory {
                override fun start(command: List<String>, env: Map<String, String>, logFile: File): GpuProcess =
                    throw java.io.IOException("exec failed")
            },
            logDirFor = { tmp.newFolder() },
            scope = watcherScope
        )

        val env = throwing.provisionForLaunch("ws1", tmp.root.absolutePath, enabled = true)

        assertTrue(env.isEmpty())
        assertEquals(1, health.degradations("ws1").size)
        assertTrue(health.degradations("ws1")[0].detail.contains("could not start"))
    }

    // ── Teardown / one-shot env / sweep ───────────────────────────────────

    @Test
    fun `stopFor tears down the server and removes the socket`() = runTest {
        val rootfs = tmp.newFolder("rootfs")
        val s = bundledSupervisor()
        s.provisionForLaunch("ws1", rootfs.absolutePath, enabled = true)
        assertTrue(s.isRunning("ws1"))

        s.stopFor("ws1")

        assertFalse(s.isRunning("ws1"))
        val socket = File(VirglHostServer.hostSocketDir(rootfs.absolutePath), VirglHostServer.SOCKET_FILE_NAME)
        assertFalse(socket.exists())
        // Second stop is a no-op (idempotent).
        s.stopFor("ws1")
        assertFalse(s.isRunning("ws1"))
    }

    @Test
    fun `one-shot execs get env only while the server runs`() = runTest {
        val s = bundledSupervisor()
        assertTrue(s.envIfRunning("ws1").isEmpty())

        s.provisionForLaunch("ws1", tmp.root.absolutePath, enabled = true)
        assertEquals(VirglHostServer.guestLaunchEnv(), s.envIfRunning("ws1"))

        s.stopFor("ws1")
        assertTrue(s.envIfRunning("ws1").isEmpty())
    }

    @Test
    fun `workspaces are isolated — env for one does not leak to another`() = runTest {
        val s = bundledSupervisor()
        s.provisionForLaunch("ws1", tmp.root.absolutePath, enabled = true)

        assertEquals(VirglHostServer.guestLaunchEnv(), s.envIfRunning("ws1"))
        assertTrue(s.envIfRunning("ws2").isEmpty())
        // And a second workspace provisions its own server (own socket dir).
        s.provisionForLaunch("ws2", tmp.newFolder().absolutePath, enabled = true)
        assertEquals(2, factory.started.size)
    }

    @Test
    fun `per-workspace provision is serialized — no double server on concurrent opens`() = runTest {
        val s = bundledSupervisor()
        val envs = coroutineScope {
            (1..8).map {
                async(Dispatchers.IO) {
                    s.provisionForLaunch("ws1", tmp.root.absolutePath, enabled = true)
                }
            }.awaitAll()
        }
        // Exactly one server was started; every caller got the guest env.
        assertEquals(1, factory.started.size)
        assertEquals(8, envs.size)
        envs.forEach { assertEquals(VirglHostServer.guestLaunchEnv(), it) }
    }

    @Test
    fun `recovery sweep reports zero kills with no stale processes`() {
        // The JVM test environment has no /proc entries matching the binary
        // name; the sweep must return 0 without throwing.
        assertEquals(0, supervisor.killStaleServers())
    }

    private fun bundledNativeDir(): File {
        val native = tmp.newFolder("native" + System.nanoTime())
        File(native, VirglHostServer.BINARY_FILE).writeText("x")
        File(native, VirglHostServer.LIB_VIRGL_FILE).writeText("x")
        File(native, VirglHostServer.LIB_EPOXY_FILE).writeText("x")
        return native
    }
}
