package com.fem.linexa.audit

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fem.linexa.domain.runtime.RuntimeState
import com.fem.linexa.domain.runtime.TerminalState
import com.fem.linexa.service.runtime.RuntimeBinder
import com.fem.linexa.service.runtime.RuntimeService
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DEEP_RESOURCE_AUDIT_CLOSURE — terminal latency and main-thread blocking.
 *
 * Runs inside the target app process, binds the real `RuntimeService` and uses
 * the same `RuntimeBinder` API the terminal UI uses. No UI automation, no
 * synthetic timer around the UI: the numbers are the runtime's own pty
 * creation/teardown latency, and a main-looper heartbeat proves whether those
 * operations block the UI thread.
 *
 * Safety: gated on a workspace directory carrying `.linexa-audit-fixture`.
 * A normal install has no such marker, so this test SKIPS rather than opening
 * terminals in a user's workspace. Provision it explicitly with:
 *
 *   adb shell run-as com.fem.linexa touch \
 *     files/workspaces/<workspaceId>/.linexa-audit-fixture
 *
 * Metrics are emitted as single lines with the `AUDIT_METRIC` tag so they can
 * be extracted from logcat without parsing test XML.
 */
@RunWith(AndroidJUnit4::class)
class TerminalLatencyAuditTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun terminalLifecycleLatencyAndMainThreadHeartbeat() = runBlocking {
        val fixture = fixtureWorkspace()
        assumeTrue(
            "skip: no workspace with .linexa-audit-fixture under files/workspaces",
            fixture != null
        )
        val workspaceId = fixture!!.name

        val binder = bindRuntime()
        val initiallyRunning = binder.getState(workspaceId) is RuntimeState.Running
        if (!initiallyRunning) {
            context.startService(RuntimeService.startIntent(context, workspaceId))
            awaitWorkspaceState(binder, workspaceId, 90_000) { it is RuntimeState.Running }
        }
        metric("workspace_started_here=${!initiallyRunning}")

        val heartbeat = MainThreadHeartbeat()
        heartbeat.start()
        val opened = mutableListOf<String>()
        try {
            // 10 normal terminals, sequential: warm pty creation latency.
            val open = mutableListOf<Long>()
            repeat(10) {
                heartbeat.reset()
                val t0 = System.nanoTime()
                val id = binder.openTerminal(workspaceId)
                val ms = (System.nanoTime() - t0) / 1_000_000
                opened += id
                open += ms
                awaitTerminalState(binder, workspaceId, id) { it is TerminalState.Running }
                metric("open_ms=$ms")
                metric("open_heartbeat_max_ms=${heartbeat.snapshot()}")
            }
            metric("open_p50_ms=${percentile(open, 50)}")
            metric("open_p90_ms=${percentile(open, 90)}")
            metric("open_p95_ms=${percentile(open, 95)}")
            metric("open_p99_ms=${percentile(open, 99)}")

            // Root terminal on the same live workspace.
            heartbeat.reset()
            val rootT0 = System.nanoTime()
            val rootId = binder.openRootTerminal(workspaceId)
            val rootMs = (System.nanoTime() - rootT0) / 1_000_000
            opened += rootId
            awaitTerminalState(binder, workspaceId, rootId) { it is TerminalState.Running }
            metric("root_open_ms=$rootMs")
            metric("root_open_heartbeat_max_ms=${heartbeat.snapshot()}")

            // Close 5 of the normal terminals, sequential.
            val close = mutableListOf<Long>()
            opened.take(5).forEach { id ->
                heartbeat.reset()
                val t0 = System.nanoTime()
                binder.closeTerminal(workspaceId, id)
                val ms = (System.nanoTime() - t0) / 1_000_000
                close += ms
                opened.remove(id)
                metric("close_ms=$ms")
                metric("close_heartbeat_max_ms=${heartbeat.snapshot()}")
            }
            metric("close_p50_ms=${percentile(close, 50)}")
            metric("close_p90_ms=${percentile(close, 90)}")

            // Natural shell exit: type `exit`, then watch the main looper while
            // TerminalSession drains the final output and reports completion.
            repeat(5) { i ->
                val id = binder.openTerminal(workspaceId)
                opened += id
                awaitTerminalState(binder, workspaceId, id) { it is TerminalState.Running }
                val pty = binder.getPtySession(workspaceId, id)
                assertTrue("pty for $id must resolve", pty != null)
                heartbeat.reset()
                val t0 = System.nanoTime()
                pty!!.write("exit\n")
                awaitTerminalState(binder, workspaceId, id) {
                    // A clean exit removes the terminal from the state map, so
                    // `null` after a confirmed Running state means "gone".
                    it == null || it is TerminalState.Stopped || it is TerminalState.Error
                }
                val ms = (System.nanoTime() - t0) / 1_000_000
                // The runtime may already have opened a replacement if this was
                // the last terminal; the id itself is gone from `opened`.
                opened.remove(id)
                metric("exit_ms=$ms")
                metric("exit_heartbeat_max_ms=${heartbeat.snapshot()} index=$i")
            }

            // Explicit close of everything this test opened (never T0).
            opened.forEach { id ->
                runCatching { binder.closeTerminal(workspaceId, id) }
            }
        } finally {
            val maxGap = heartbeat.stop()
            metric("heartbeat_global_max_ms=$maxGap")
            runCatching { context.unbindService(connection) }
            if (!initiallyRunning) {
                // Leave the device as found: stop only what this test started.
                context.startService(RuntimeService.stopIntent(context, workspaceId))
                withTimeoutOrNull(30_000) {
                    awaitWorkspaceState(binder, workspaceId, 30_000) { it is RuntimeState.Stopped }
                }
            }
        }
    }

    private fun metric(line: String) {
        android.util.Log.i("AUDIT_METRIC", line)
    }

    private fun percentile(values: List<Long>, p: Int): Long {
        if (values.isEmpty()) return -1
        val sorted = values.sorted()
        val index = ((p / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private var binder: RuntimeBinder? = null
    private var connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service as? RuntimeBinder
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }
    }
    private val connected = CountDownLatch(1)

    private fun bindRuntime(): RuntimeBinder {
        val intent = Intent(context, RuntimeService::class.java)
        assertTrue(
            "RuntimeService must bind",
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        )
        assertTrue(
            "RuntimeService did not return its binder within 15 s",
            connected.await(15, TimeUnit.SECONDS)
        )
        return requireNotNull(binder) { "RuntimeService returned no RuntimeBinder" }
    }

    private fun fixtureWorkspace(): File? =
        File(context.filesDir, "workspaces").listFiles().orEmpty()
            .filter { it.isDirectory }
            .firstOrNull { File(it, ".linexa-audit-fixture").exists() }

    private suspend fun awaitWorkspaceState(
        binder: RuntimeBinder,
        workspaceId: String,
        timeoutMs: Long,
        predicate: (RuntimeState?) -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate(binder.getState(workspaceId))) return
            delay(100)
        }
        error("workspace $workspaceId never reached the expected state")
    }

    private suspend fun awaitTerminalState(
        binder: RuntimeBinder,
        workspaceId: String,
        terminalId: String,
        predicate: (TerminalState?) -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate(binder.terminalStates.value[workspaceId]?.get(terminalId))) return
            delay(50)
        }
        error("terminal $workspaceId/$terminalId never reached the expected state")
    }

    /**
     * A 4 ms main-looper heartbeat. `reset` starts a new observation window,
     * `snapshot` stops it and returns the largest gap between two consecutive
     * callbacks — i.e. how long the UI thread was unable to run.
     */
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
            var value = 0L
            onMain { value = maxGap }
            return value
        }

        fun stop(): Long {
            var value = 0L
            onMain {
                running = false
                handler.removeCallbacks(tick)
                value = maxGap
            }
            return value
        }

        private fun onMain(block: () -> Unit) {
            val latch = CountDownLatch(1)
            handler.post {
                try {
                    block()
                } finally {
                    latch.countDown()
                }
            }
            check(latch.await(5, TimeUnit.SECONDS)) { "main looper did not service heartbeat control" }
        }
    }
}
