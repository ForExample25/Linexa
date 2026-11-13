package com.fem.linexa.domain.packagemanager

import com.fem.linexa.core.logging.Logger
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The ONE package-operation queue (issue #2, 2026-09-03 rework).
 *
 * ## The problem this replaces
 *
 * Linexa starts guest package operations from many surfaces — the Package
 * Manager UI, feature provisioners (SSH, Xvnc, desktop presets), declared
 * first-boot, the recovery preflight, metadata/shortcut commands — and all
 * of them mutate the SAME dpkg database of the SAME rootfs. Two running at
 * once is the reported "E: Could not get lock /var/lib/apt/lists/lock. It
 * is held by process NNNN (apt-get)" failure. The previous fix serialized
 * the runtime's one-shot execs, but the Commands tab typed its package
 * commands into an interactive terminal, so half of Linexa's own package
 * traffic never passed a lock at all.
 *
 * ## The design
 *
 * A single [Singleton] owns the per-workspace [Mutex] set (moved verbatim
 * out of `PRootContainerRuntime` — there is exactly one lock
 * implementation, and every mutating surface funnels through it):
 *
 *  - the runtime's one-shot exec path wraps classified package commands
 *    in [runExclusive] — unchanged semantics, plus observability;
 *  - the Shortcuts tab routes package-operation leaf commands through the
 *    same `execCommandStreaming` bridge (which locks here) instead of
 *    typing them into a terminal — so "two Linexa package operations"
 *    becomes a queue by construction, whatever surface each came from;
 *  - user-typed terminal commands are NOT queueable (Linexa cannot see
 *    them) — native apt/dpkg locking plus the guest's
 *    `APT::Lock::Timeout` drop-in remain the authority there, so a
 *    queued Linexa op and a terminal op wait on each other instead of
 *    failing.
 *
 * ## Observability (requirement: "log the process that currently owns")
 *
 * Every transition publishes a [Snapshot] ([activeCommand], since when,
 * how many waiters). A waiter that is still queued after [CONTENDED_LOG_MS]
 * logs the current owner — the diagnostic the APT error message used to
 * force the user to guess. `snapshotOf` also feeds the UI's "queued"
 * state without any module keeping its own bookkeeping.
 *
 * ## Failure, cancellation and restart semantics
 *
 *  - success/failure: ownership is released in `finally` — the result
 *    itself never changes queue behavior;
 *  - cancellation: `Mutex.lock()` is cancellable; a cancelled waiter
 *    never becomes the owner, and a cancelled owner releases its slot;
 *    the guest process it may have already started is handled by the
 *    runtime's existing timeout + orphan sweep, as before;
 *  - app restart: the queue is in-process state; a restart drops it by
 *    design. Interrupted *guest* work is recovered by the existing
 *    `dpkg --configure -a` idle-recovery preflight and stale-lock
 *    handling (F-6), which do not depend on this object;
 *  - deadlock: the mutex is a leaf — nothing holds it while taking
 *    another lock; `runExclusive` wraps execution only.
 *
 * Pure coroutines + JDK, no Android imports, so the serialization
 * behavior is unit-testable on the JVM.
 */
@Singleton
class PackageOperationCoordinator @Inject constructor() {

    /** One workspace's queue state, published for UI + diagnostics. */
    data class Snapshot(
        val workspaceId: String,
        /** [PackageOperationClassifier.shortLabel] of the running command, or null when idle. */
        val activeCommand: String? = null,
        val activeSinceElapsedMs: Long? = null,
        val waiting: Int = 0,
        val completedOk: Long = 0,
        val completedFailed: Long = 0
    ) {
        val isBusy: Boolean get() = activeCommand != null
    }

    private val locks = ConcurrentHashMap<String, Mutex>()
    private val states = ConcurrentHashMap<String, MutableStateFlow<Snapshot>>()
    private val ownerSince = ConcurrentHashMap<String, Long>()

    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Monotonic clock source — JDK-only so JVM unit tests run this class as-is. */
    internal var clock: () -> Long = { System.nanoTime() / 1_000_000L }

    /**
     * Runs [block] as the exclusive package operation of [workspaceId].
     * Concurrent callers queue; each publishes its wait in the snapshot.
     * The lock is NOT re-entrant — never call this while already holding
     * the workspace's package lock (the runtime guarantees that: the lock
     * wraps the process run only).
     */
    suspend fun <T> runExclusive(
        workspaceId: String,
        command: String,
        block: suspend () -> T
    ): T {
        val label = PackageOperationClassifier.shortLabel(command)
        val mutex = lockFor(workspaceId)
        publish(workspaceId) { it.copy(waiting = it.waiting + 1) }
        val waiterEpoch = clock()
        try {
            mutex.lock()
        } catch (e: Throwable) {
            publish(workspaceId) { it.copy(waiting = (it.waiting - 1).coerceAtLeast(0)) }
            throw e
        }
        // Contended-wait diagnostics: a queued op older than the threshold
        // means some owner is holding the queue — say WHICH, at the moment
        // the waiter gets in (so the log names both sides of the wait).
        val waitedMs = clock() - waiterEpoch
        if (waitedMs >= CONTENDED_LOG_MS) {
            Logger.w(
                TAG,
                "package queue: '$label' entered after ${waitedMs}ms for $workspaceId — " +
                    "previous owner held the queue; check runtime trace for its completion"
            )
        }
        publish(workspaceId) {
            it.copy(
                activeCommand = label,
                activeSinceElapsedMs = 0,
                waiting = (it.waiting - 1).coerceAtLeast(0)
            )
        }
        val startedAt = clock()
        ownerSince[workspaceId] = startedAt
        watchActive(workspaceId, startedAt)
        try {
            return block()
        } finally {
            val busyWatch = ownerSince.remove(workspaceId)
            ownerWatchers.remove(workspaceId)?.cancel()
            if (busyWatch != null && (clock() - busyWatch) >= CONTENDED_LOG_MS) {
                // Long-owned operations get a completion line: diagnostics can
                // pair "who owned it" with "how long it kept the queue".
                Logger.i(
                    TAG,
                    "package queue: '$label' released the queue for $workspaceId " +
                        "after ${(clock() - busyWatch)}ms"
                )
            }
            publish(workspaceId) {
                it.copy(
                    activeCommand = null,
                    activeSinceElapsedMs = null,
                    waiting = it.waiting
                )
            }
            mutex.unlock()
        }
    }

    /** The workspace's queue snapshot as a cold-start-safe [StateFlow]. */
    fun snapshotOf(workspaceId: String): StateFlow<Snapshot> = states(workspaceId).asStateFlow()

    /** Current snapshot value without installing a flow (diagnostics path). */
    fun currentOf(workspaceId: String): Snapshot = states(workspaceId).value

    fun lockFor(workspaceId: String): Mutex = locks.computeIfAbsent(workspaceId) { Mutex() }

    /**
     * Starts the elapsed-time ticker for the active owner (kept out of the
     * hot path: one coroutine per BUSY workspace, cancelled on release).
     */
    private fun watchActive(workspaceId: String, startedAt: Long) {
        val job = watchScope.launch {
            while (true) {
                delay(SNAPSHOT_TICK_MS)
                val since = ownerSince[workspaceId] ?: break
                publish(workspaceId) { it.copy(activeSinceElapsedMs = clock() - since) }
            }
        }
        ownerWatchers[workspaceId]?.cancel()
        ownerWatchers[workspaceId] = job
    }

    private val ownerWatchers = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    private fun publish(workspaceId: String, transform: (Snapshot) -> Snapshot) {
        states(workspaceId).value = transform(states(workspaceId).value)
    }

    private fun states(workspaceId: String): MutableStateFlow<Snapshot> =
        states.computeIfAbsent(workspaceId) {
            MutableStateFlow(Snapshot(workspaceId = workspaceId))
        }

    companion object {
        private const val TAG = "PkgOpQueue"

        /** Wait/hold duration at which diagnostics are logged. */
        const val CONTENDED_LOG_MS = 10_000L

        /** Snapshot elapsed-time tick while an owner is active. */
        const val SNAPSHOT_TICK_MS = 1_000L
    }
}
