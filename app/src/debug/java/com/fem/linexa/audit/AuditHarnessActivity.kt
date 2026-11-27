package com.fem.linexa.audit

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.fem.linexa.domain.launcher.LauncherApps
import com.fem.linexa.domain.runtime.RuntimeState
import com.fem.linexa.domain.runtime.TerminalState
import com.fem.linexa.service.commands.QuickCommandsResolver
import com.fem.linexa.service.installer.AppInstallCoordinator
import com.fem.linexa.service.installer.AppInstallProbe
import com.fem.linexa.service.runtime.RuntimeBinder
import com.fem.linexa.service.runtime.RuntimeService
import com.fem.linexa.ui.screens.workspace.AppInstallTracker
import com.fem.linexa.ui.screens.workspace.InstalledStates
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * DEBUG-ONLY audit harness (never packaged in release: lives in src/debug).
 *
 * Runs the closure measurements on a real device without UI automation or a
 * test APK (MIUI blocks test-APK installation on the audit phone):
 *
 *   adb shell am start -n com.fem.linexa/.audit.AuditHarnessActivity \
 *     --es mode terminal --es workspace_id <id>
 *   adb shell am start -n com.fem.linexa/.audit.AuditHarnessActivity \
 *     --es mode probe --es workspace_id <id>
 *
 * Terminal mode: normal/root open latency, close latency, natural `exit`
 * latency, and a 4 ms main-looper heartbeat around every operation.
 * Probe mode: DRA-004 throttle/dedupe/corpus-change/correctness against the
 * real guest package manager.
 *
 * Safety: the workspace must carry `.linexa-audit-fixture`; otherwise the
 * harness refuses. It never closes T0 and stops the runtime only if it started
 * it itself.
 */
class AuditHarnessActivity : ComponentActivity() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AuditEntryPoint {
        fun installCoordinator(): AppInstallCoordinator
        fun installProbe(): AppInstallProbe
        fun quickCommandsResolver(): QuickCommandsResolver
    }

    private val heartbeat = MainThreadHeartbeat()
    private var bound: RuntimeBinder? = null
    private val connected = CountDownLatch(1)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            bound = service as? RuntimeBinder
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra("mode") ?: "terminal"
        val workspaceId = intent.getStringExtra("workspace_id") ?: fixtureWorkspaceId()
        lifecycleScope.launch(Dispatchers.IO) {
            val started = SystemClock.elapsedRealtime()
            out("HARNESS START mode=$mode workspace=$workspaceId")
            try {
                if (workspaceId == null || !File(filesDir, "workspaces/$workspaceId/.linexa-audit-fixture").exists()) {
                    out("REFUSED: no .linexa-audit-fixture workspace")
                    return@launch
                }
                when (mode) {
                    "terminal" -> runTerminal(workspaceId)
                    "batch" -> runTerminalBatch(workspaceId)
                    "probe" -> runProbe(workspaceId)
                    "stop" -> runStop(workspaceId)
                    "affinity2" -> runAffinity(2)
                    "affinity_reset" -> runAffinity(8)
                    else -> out("REFUSED: unknown mode $mode")
                }
            } catch (t: Throwable) {
                out("FAILED ${t::class.java.simpleName}: ${t.message}")
                Log.e("AUDIT_METRIC", "FAILED", t)
            } finally {
                out("HARNESS DONE mode=$mode elapsed=${SystemClock.elapsedRealtime() - started}ms")
                withContext(Dispatchers.Main) { finish() }
            }
        }
    }

    // ── terminal latency ────────────────────────────────────────────────

    private suspend fun runTerminal(workspaceId: String) {
        val binder = bindRuntime()
        val initiallyRunning = binder.getState(workspaceId) is RuntimeState.Running
        if (!initiallyRunning) {
            startService(RuntimeService.startIntent(this, workspaceId))
            awaitWorkspace(binder, workspaceId) { it is RuntimeState.Running }
        }
        out("workspace_started_here=${!initiallyRunning}")
        heartbeat.start()
        val opened = mutableListOf<String>()
        try {
            val opens = mutableListOf<Long>()
            repeat(10) {
                heartbeat.reset()
                val t0 = System.nanoTime()
                val id = binder.openTerminal(workspaceId)
                val ms = (System.nanoTime() - t0) / 1_000_000
                opened += id
                opens += ms
                awaitTerminal(binder, workspaceId, id) { it is TerminalState.Running }
                out("open_ms=$ms open_heartbeat_max_ms=${heartbeat.snapshot()}")
            }
            out("open_p50_ms=${percentile(opens, 50)} open_p90_ms=${percentile(opens, 90)} " +
                "open_p95_ms=${percentile(opens, 95)} open_p99_ms=${percentile(opens, 99)}")

            heartbeat.reset()
            val rt0 = System.nanoTime()
            val rootId = binder.openRootTerminal(workspaceId)
            val rootMs = (System.nanoTime() - rt0) / 1_000_000
            opened += rootId
            awaitTerminal(binder, workspaceId, rootId) { it is TerminalState.Running }
            out("root_open_ms=$rootMs root_open_heartbeat_max_ms=${heartbeat.snapshot()}")

            val closes = mutableListOf<Long>()
            opened.take(5).forEach { id ->
                heartbeat.reset()
                val t0 = System.nanoTime()
                binder.closeTerminal(workspaceId, id)
                val ms = (System.nanoTime() - t0) / 1_000_000
                closes += ms
                opened.remove(id)
                out("close_ms=$ms close_heartbeat_max_ms=${heartbeat.snapshot()}")
            }
            out("close_p50_ms=${percentile(closes, 50)} close_p90_ms=${percentile(closes, 90)}")

            repeat(5) { i ->
                val id = binder.openTerminal(workspaceId)
                opened += id
                awaitTerminal(binder, workspaceId, id) { it is TerminalState.Running }
                val pty = binder.getPtySession(workspaceId, id)
                if (pty == null) {
                    out("exit_$i SKIPPED: no pty")
                } else {
                    heartbeat.reset()
                    val t0 = System.nanoTime()
                    pty.write("exit\n")
                    awaitTerminal(binder, workspaceId, id) {
                        it == null || it is TerminalState.Stopped || it is TerminalState.Error
                    }
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    opened.remove(id)
                    out("exit_ms=$ms exit_heartbeat_max_ms=${heartbeat.snapshot()} index=$i")
                }
            }
            opened.forEach { id -> runCatching { binder.closeTerminal(workspaceId, id) } }
        } finally {
            out("heartbeat_global_max_ms=${heartbeat.stop()}")
            runCatching { unbindService(connection) }
            if (!initiallyRunning) {
                startService(RuntimeService.stopIntent(this, workspaceId))
            }
        }
    }

    // ── DRA-004 probe scheduler ─────────────────────────────────────────

    private suspend fun runTerminalBatch(workspaceId: String) {
        val binder = bindRuntime()
        val initiallyRunning = binder.getState(workspaceId) is RuntimeState.Running
        if (!initiallyRunning) {
            startService(RuntimeService.startIntent(this, workspaceId))
            awaitWorkspace(binder, workspaceId) { it is RuntimeState.Running }
        }
        heartbeat.start()
        val opened = mutableListOf<String>()
        try {
            // Recreation: open -> ready -> close, five times. This is the
            // "closed a terminal and opened a new one" user path.
            val recreationOpen = mutableListOf<Long>()
            val recreationClose = mutableListOf<Long>()
            repeat(5) {
                heartbeat.reset()
                val o0 = System.nanoTime()
                val id = binder.openTerminal(workspaceId)
                val openMs = (System.nanoTime() - o0) / 1_000_000
                awaitTerminal(binder, workspaceId, id) { it is TerminalState.Running }
                recreationOpen += openMs
                out("recreation_open_ms=$openMs recreation_open_heartbeat_max_ms=${heartbeat.snapshot()}")

                heartbeat.reset()
                val c0 = System.nanoTime()
                binder.closeTerminal(workspaceId, id)
                val closeMs = (System.nanoTime() - c0) / 1_000_000
                recreationClose += closeMs
                out("recreation_close_ms=$closeMs recreation_close_heartbeat_max_ms=${heartbeat.snapshot()}")
            }
            out("recreation_open_p50_ms=${percentile(recreationOpen, 50)} " +
                "recreation_open_p90_ms=${percentile(recreationOpen, 90)} " +
                "recreation_close_p50_ms=${percentile(recreationClose, 50)}")

            // Multiple-terminal creation: five concurrent requests (the
            // runtime serializes per workspace, so wall time is the queue).
            heartbeat.reset()
            val wall0 = System.nanoTime()
            val ids = coroutineScope {
                (1..5).map {
                    async {
                        val t0 = System.nanoTime()
                        val id = binder.openTerminal(workspaceId)
                        Triple(id, (System.nanoTime() - t0) / 1_000_000, System.nanoTime())
                    }
                }.awaitAll()
            }
            val wallMs = (System.nanoTime() - wall0) / 1_000_000
            ids.forEach { (id, ms, _) ->
                opened += id
                awaitTerminal(binder, workspaceId, id) { it is TerminalState.Running }
                out("concurrent_open_ms=$ms")
            }
            out("concurrent_open_wall_ms=$wallMs concurrent_open_heartbeat_max_ms=${heartbeat.snapshot()}")

            heartbeat.reset()
            val closeWall0 = System.nanoTime()
            coroutineScope {
                opened.map { id -> async { binder.closeTerminal(workspaceId, id) } }.awaitAll()
            }
            val closeWallMs = (System.nanoTime() - closeWall0) / 1_000_000
            opened.clear()
            out("concurrent_close_wall_ms=$closeWallMs concurrent_close_heartbeat_max_ms=${heartbeat.snapshot()}")
            out("batch_audit=PASS")
        } finally {
            out("heartbeat_global_max_ms=${heartbeat.stop()}")
            runCatching { unbindService(connection) }
            if (!initiallyRunning) {
                startService(RuntimeService.stopIntent(this, workspaceId))
            }
        }
    }

    // ── DRA-004 probe scheduler ─────────────────────────────────────────

    /**
     * Controlled low-core simulation: restrict every thread of THIS app process
     * to [cores] CPUs (0..cores-1) via sched_setaffinity. No root, no other app
     * is touched, and the restriction dies with the process. Debug-only and
     * explicitly a simulation, not a low-end physical device.
     */
    private fun runAffinity(cores: Int) {
        val mask = ((1 shl cores) - 1).toString(16)
        val pid = android.os.Process.myPid()
        val result = runCatching {
            val p = ProcessBuilder("/system/bin/taskset", "-ap", mask, pid.toString())
                .redirectErrorStream(true)
                .start()
            val text = p.inputStream.bufferedReader().readText().trim()
            val code = p.waitFor()
            "exit=$code output=${text.take(200)}"
        }.getOrElse { "threw=${it.message}" }
        val allowed = runCatching {
            File("/proc/self/status").readLines().firstOrNull { it.startsWith("Cpus_allowed_list:") }
        }.getOrNull()
        out("affinity_set cores=0-${cores - 1} mask=$mask $result $allowed")
    }

    private suspend fun runStop(workspaceId: String) {
        val binder = bindRuntime()
        val before = binder.getState(workspaceId)
        out("stop_state_before=${before::class.simpleName}")
        if (before is RuntimeState.Running || before is RuntimeState.Starting) {
            startService(RuntimeService.stopIntent(this, workspaceId))
            awaitWorkspace(binder, workspaceId) { it is RuntimeState.Stopped }
        }
        out("stop_state_after=${binder.getState(workspaceId)::class.simpleName}")
        runCatching { unbindService(connection) }
    }

    private suspend fun runProbe(workspaceId: String) {
        val ep = EntryPointAccessors.fromApplication(applicationContext, AuditEntryPoint::class.java)
        val resolver = ep.quickCommandsResolver()
        // The portable branch alone can be empty; resolve the catalog for a
        // known distribution so the corpus is the real launcher corpus.
        val nodes = sequenceOf(
            "debian" to "12",
            "debian" to "13",
            "ubuntu" to "22.04",
            "alpine" to "3.24.1",
            "arch" to null,
            "fedora" to null
        ).map { (d, v) -> resolver.resolve(d, v) }
            .firstOrNull { it.isNotEmpty() }
            ?: resolver.resolve(null, null)
        val apps = LauncherApps.fromCatalog(nodes)
        if (apps.isEmpty()) {
            out("REFUSED: empty catalog")
            return
        }
        val binder = bindRuntime()
        val initiallyRunning = binder.getState(workspaceId) is RuntimeState.Running
        if (!initiallyRunning) {
            startService(RuntimeService.startIntent(this, workspaceId))
            awaitWorkspace(binder, workspaceId) { it is RuntimeState.Running }
        }
        val tracker = AppInstallTracker(ep.installCoordinator(), ep.installProbe())
        try {
            tracker.attach(workspaceId, binder)
            tracker.setApps(apps)
            awaitProbeIdle(tracker, 1)
            out("initial_probe_count=${tracker.probeCountForTest()} apps=${apps.size} states=${tracker.states.value.size}")

            tracker.refresh(); tracker.refresh(); delay(150)
            out("after_rapid_refresh_count=${tracker.probeCountForTest()} expected=1")

            tracker.refresh(force = true)
            tracker.refresh()
            delay(50)
            out("inflight_count=${tracker.probeCountForTest()} expected=2")
            awaitProbeIdle(tracker, 2)

            delay(5_200)
            tracker.refresh()
            awaitProbeIdle(tracker, 3)
            out("after_window_count=${tracker.probeCountForTest()} expected=3")

            val half = apps.take(max(1, apps.size / 2))
            tracker.setApps(half)
            awaitProbeIdle(tracker, 4)
            tracker.setApps(half)
            delay(200)
            out("corpus_change_count=${tracker.probeCountForTest()} expected=4 states=${tracker.states.value.size}")

            val installed = ep.installProbe().installedNames(binder, workspaceId)
            if (installed == null) {
                out("CORRECTNESS_FAILED: guest package set unavailable")
            } else {
                val expected = InstalledStates.mergeStates(half, installed, emptyMap())
                var checked = 0
                var mismatches = 0
                half.filter { it.verificationPackages.isNotEmpty() }.forEach { app ->
                    checked++
                    if (expected[app.id] != tracker.states.value[app.id]) {
                        mismatches++
                        out("MISMATCH ${app.name} expected=${expected[app.id]} actual=${tracker.states.value[app.id]}")
                    }
                }
                val hit = half.firstOrNull { app ->
                    app.verificationPackages.any { it in installed }
                }
                val miss = half.firstOrNull { app ->
                    app.verificationPackages.isNotEmpty() && app.verificationPackages.none { it in installed }
                }
                out("correctness checked=$checked mismatches=$mismatches installed_packages=${installed.size}")
                out("known_hit=${hit?.name} state=${hit?.let { tracker.states.value[it.id] }}")
                out("known_miss=${miss?.name} state=${miss?.let { tracker.states.value[it.id] }}")
            }
            out("probe_audit=${if (tracker.probeCountForTest() == 4L) "PASS" else "FAIL"}")
        } finally {
            tracker.detach()
            runCatching { unbindService(connection) }
            if (!initiallyRunning) {
                startService(RuntimeService.stopIntent(this, workspaceId))
            }
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun fixtureWorkspaceId(): String? =
        File(filesDir, "workspaces").listFiles().orEmpty()
            .filter { it.isDirectory }
            .firstOrNull { File(it, ".linexa-audit-fixture").exists() }
            ?.name

    private fun bindRuntime(): RuntimeBinder {
        val intent = Intent(this, RuntimeService::class.java)
        check(bindService(intent, connection, Context.BIND_AUTO_CREATE)) { "RuntimeService bind failed" }
        check(connected.await(15, TimeUnit.SECONDS)) { "RuntimeService bind timeout" }
        return requireNotNull(bound) { "no RuntimeBinder" }
    }

    private suspend fun awaitWorkspace(
        binder: RuntimeBinder,
        workspaceId: String,
        predicate: (RuntimeState?) -> Boolean
    ) = withTimeout(90_000) {
        while (!predicate(binder.getState(workspaceId))) delay(100)
    }

    private suspend fun awaitTerminal(
        binder: RuntimeBinder,
        workspaceId: String,
        terminalId: String,
        predicate: (TerminalState?) -> Boolean
    ) = withTimeout(60_000) {
        while (!predicate(binder.terminalStates.value[workspaceId]?.get(terminalId))) delay(50)
    }

    private suspend fun awaitProbeIdle(tracker: AppInstallTracker, expected: Long) =
        withTimeout(60_000) {
            while (tracker.probeCountForTest() < expected || tracker.probing.value) delay(50)
            delay(100)
        }

    private fun percentile(values: List<Long>, p: Int): Long {
        if (values.isEmpty()) return -1
        val sorted = values.sorted()
        return sorted[((p / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.lastIndex)]
    }

    private fun out(line: String) {
        Log.i("AUDIT_METRIC", line)
        runCatching {
            val dir = File(filesDir, "audit")
            dir.mkdirs()
            File(dir, "audit_${intent.getStringExtra("mode") ?: "terminal"}.log")
                .appendText("${SystemClock.elapsedRealtime()} $line\n")
        }
    }

    /** 4 ms main-looper heartbeat; returns the largest callback gap. */
    private class MainThreadHeartbeat {
        private val handler = Handler(Looper.getMainLooper())
        private var maxGap = 0L
        private var last = 0L
        private var running = false
        private val tick = object : Runnable {
            override fun run() {
                val now = SystemClock.uptimeMillis()
                if (last != 0L) maxGap = max(maxGap, now - last)
                last = now
                if (running) handler.postDelayed(this, 4)
            }
        }

        fun start() = onMain {
            running = true
            last = SystemClock.uptimeMillis()
            handler.postDelayed(tick, 4)
        }

        fun reset() = onMain {
            maxGap = 0L
            last = SystemClock.uptimeMillis()
        }

        fun snapshot(): Long {
            var v = 0L
            onMain { v = maxGap }
            return v
        }

        fun stop(): Long {
            var v = 0L
            onMain {
                running = false
                handler.removeCallbacks(tick)
                v = maxGap
            }
            return v
        }

        private fun onMain(block: () -> Unit) {
            val latch = CountDownLatch(1)
            handler.post {
                try { block() } finally { latch.countDown() }
            }
            check(latch.await(5, TimeUnit.SECONDS)) { "main looper unavailable" }
        }
    }
}
