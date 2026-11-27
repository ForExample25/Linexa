package com.fem.linexa.service.runtime

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * P0-2 (2026-11-23 performance remediation): cancellable, single-flight,
 * generation-safe measurement of one workspace directory.
 *
 * ## Why this replaced the plain `ConcurrentHashMap.computeIfAbsent` caches
 *
 * The old caches held a `ConcurrentHashMap` bin lock for the entire recursive
 * `Files.walkFileTree` of a multi-GB rootfs. Every concurrent caller for the
 * same workspace blocked on that bin, and — worse — an interrupted caller had
 * no way to stop the walk: cancellation was impossible until the traversal
 * finished, and a cancelled computation could still store a partial sum.
 *
 * This cache:
 *  - keeps **no map lock across I/O**: the map only holds an entry object,
 *    the traversal runs on a worker;
 *  - is **single-flight per workspace** for concurrent callers, like the old
 *    `computeIfAbsent`;
 *  - **counts waiters**, so one interrupted caller never cancels a walk that
 *    another caller is still waiting for;
 *  - checks a **cooperative cancellation flag** in every directory/file
 *    visit, so the last waiter leaving terminates the walk promptly;
 *  - never caches a failure or a cancelled/partial result — the entry is
 *    removed and the next caller starts a fresh generation;
 *  - measures the workspace and its rootfs in **one traversal** of the
 *    workspace base (the workspace total is a superset of the rootfs total,
 *    so the second recursive walk was pure duplicate I/O).
 *
 * Invalidation is generation-safe: [invalidate] removes and cancels the
 * current entry; a task from that generation can never re-insert or overwrite
 * a newer generation's result.
 */
internal class SizeMeasurementCache(
    private val executor: Executor = defaultExecutor,
    /**
     * F-010/F-037: called on the measurement thread after a successful walk,
     * so the caller can persist the answer without holding up the UI.
     */
    private val onMeasured: ((String, Measurements) -> Unit)? = null,
    /** Test seam; production always uses the no-follow tree walk below. */
    private val measurer: ((File, File, AtomicBoolean) -> Measurements?)? = null,
    /**
     * DRA-005 closure (2026-11-26): delay before a stale-while-revalidate
     * [warm] actually starts its walk. Production passes
     * [WorkspaceFileManager.WARM_DELAY_MS] so the first reader after a stop
     * gets the persisted value immediately and the 2–4 s traversal starts only
     * after the workspace entry/runtime start has had a head start. Tests use
     * the 0 ms default.
     */
    private val warmDelayMs: Long = 0L,
    /**
     * DRA-005 scheduling seam. A daemon ScheduledExecutorService is used
     * instead of CompletableFuture.delayedExecutor, which is API 31+ while the
     * app supports API 28; this path must not throw NoSuchMethodError on
     * Android 9–11.
     */
    private val scheduler: java.util.concurrent.ScheduledExecutorService = defaultScheduler
) {
    /** One workspace directory's two answers, from a single walk. */
    data class Measurements(
        val workspaceBytes: Long,
        val rootfsBytes: Long
    )

    private class Entry {
        val future = CompletableFuture<Measurements>()
        val cancelled = AtomicBoolean(false)
        val waiters = AtomicInteger(0)
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * Returns the measurements for [workspaceId], or null when the walk was
     * cancelled/failed. A successful result is retained until [invalidate];
     * a failed/cancelled result never is.
     *
     * [base] must be the workspace directory and [rootfs] its live rootfs
     * child. Both may be missing (the caller normally short-circuits that):
     * a missing base yields `Measurements(0, 0)`.
     */
    fun get(workspaceId: String, base: File, rootfs: File): Measurements? {
        while (true) {
            val existing = entries[workspaceId]
            val entry = if (existing != null && !existing.cancelled.get()) {
                existing
            } else {
                val fresh = Entry()
                val installed = if (existing == null) {
                    entries.putIfAbsent(workspaceId, fresh) == null
                } else {
                    // A cancelled generation is being replaced. Only the
                    // thread that removes/replaces it may launch the walk.
                    entries.replace(workspaceId, existing, fresh)
                }
                if (installed) {
                    launch(workspaceId, fresh, base, rootfs)
                    fresh
                } else {
                    // Another caller installed its own generation first.
                    continue
                }
            }

            entry.waiters.incrementAndGet()
            return try {
                entry.future.get()
            } catch (_: CancellationException) {
                null
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            } catch (_: ExecutionException) {
                // The worker already removed its entry; a retry would just
                // perform the same failing walk again.
                null
            } finally {
                if (entry.waiters.decrementAndGet() == 0 && !entry.future.isDone) {
                    entry.cancelled.set(true)
                }
            }
        }
    }

    /**
     * The completed measurement for [workspaceId], or null when none exists
     * yet. Never blocks; used for stale-while-revalidate reads.
     */
    fun cached(workspaceId: String): Measurements? {
        val entry = entries[workspaceId] ?: return null
        if (!entry.future.isDone || entry.future.isCompletedExceptionally || entry.future.isCancelled) {
            return null
        }
        return entry.future.getNow(null)
    }

    /**
     * Start (or join) a background measurement without waiting for it.
     * F-010/F-037: after a persisted size is served, this refreshes it.
     *
     * DRA-005: when [warmDelayMs] is positive the walk is scheduled instead of
     * started inline. The entry is installed immediately, so concurrent
     * readers still see a single in-flight generation and keep using the
     * persisted value. If the generation is invalidated/replaced before the
     * delay expires, the scheduled task completes that entry exceptionally so
     * no waiter (there should be none for a warm-only entry) can hang.
     */
    fun warm(workspaceId: String, base: File, rootfs: File) {
        if (cached(workspaceId) != null) return
        val fresh = Entry()
        val installed = entries.putIfAbsent(workspaceId, fresh) == null
        if (!installed) return
        if (warmDelayMs <= 0L) {
            launch(workspaceId, fresh, base, rootfs)
            return
        }
        com.fem.linexa.core.logging.Logger.d(
            TAG,
            "size warm scheduled ws=$workspaceId delay=${warmDelayMs}ms"
        )
        scheduler.schedule({
            if (entries[workspaceId] === fresh && !fresh.cancelled.get() && !fresh.future.isDone) {
                launch(workspaceId, fresh, base, rootfs)
            } else {
                fresh.future.completeExceptionally(
                    CancellationException("size warm superseded before start")
                )
            }
        }, warmDelayMs, TimeUnit.MILLISECONDS)
    }

    /**
     * DEEP-AUDIT (2026-11-26): stale-while-revalidate marker.
     *
     * Unlike [invalidate], this never makes the previous answer unreadable:
     * the persisted value stays in [SizeMeasurementStore], so the next reader
     * gets the last known size immediately and [warm] starts the new walk off
     * the critical path. Used when the on-disk size *may* have changed but the
     * old number is still a useful approximation (a workspace runtime stop can
     * let the guest write or delete files, but it does not make the previous
     * measurement wrong for the purpose of painting a card).
     *
     * Deliberately does NOT start the walk here. Stopping the runtime is a
     * lifecycle event, not a request to read the size: on the audited device
     * the walk of the XFCE workspace (74 377 files / 2.2 GB) took 3 927 ms, and
     * running it immediately on every stop spent that IO while the user was
     * navigating back — for a number no screen was waiting on. The next
     * `get()`/`warm()` revalidates it, and that caller already has the
     * persisted answer to return.
     *
     * The alternative — [invalidate] on stop — erased the persisted value too,
     * forcing the next workspace entry to run that walk synchronously inside
     * the detail-state build (the measured "first entry fast, re-entry slow"
     * asymmetry).
     */
    fun markStale(workspaceId: String) {
        val removed = entries.remove(workspaceId) ?: return
        removed.cancelled.set(true)
        removed.future.cancel(false)
    }

    /** Drops the current measurement and asks any in-flight walk to stop. */
    fun invalidate(workspaceId: String) {
        val removed = entries.remove(workspaceId) ?: return
        removed.cancelled.set(true)
        removed.future.cancel(false)
    }

    fun invalidateAll() {
        entries.keys.toList().forEach(::invalidate)
    }

    /** Test/diagnostic visibility only. */
    internal fun isCached(workspaceId: String): Boolean {
        val entry = entries[workspaceId] ?: return false
        return entry.future.isDone && !entry.future.isCompletedExceptionally && !entry.future.isCancelled
    }

    /** Test/diagnostic visibility only: callers currently waiting on the walk. */
    internal fun waiterCount(workspaceId: String): Int =
        entries[workspaceId]?.waiters?.get() ?: 0

    private fun launch(workspaceId: String, entry: Entry, base: File, rootfs: File) {
        executor.execute {
            val startedNanos = System.nanoTime()
            val result = try {
                (measurer ?: ::measureTree)(base, rootfs, entry.cancelled)
            } catch (_: Exception) {
                null
            }
            val elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L
            if (result != null && !entry.cancelled.get()) {
                // DEEP-AUDIT (2026-11-26): one line per completed walk. This is
                // the only durable evidence for how expensive the traversal is
                // on a given workspace/device, and the tool used to prove the
                // stale-while-revalidate fix (the walk must no longer be on the
                // workspace-entry critical path).
                com.fem.linexa.core.logging.Logger.d(
                    TAG,
                    "size walk ws=$workspaceId files-bytes=${result.workspaceBytes} " +
                        "rootfs-bytes=${result.rootfsBytes} elapsed=${elapsedMs}ms"
                )
                entry.future.complete(result)
                onMeasured?.invoke(workspaceId, result)
            } else {
                // Failures and cancellations are never retained: remove THIS
                // generation only, so a newer one installed meanwhile is not
                // disturbed.
                entries.remove(workspaceId, entry)
                entry.future.completeExceptionally(
                    if (entry.cancelled.get()) CancellationException("size measurement cancelled")
                    else IOException("size measurement failed")
                )
            }
        }
    }

    /**
     * One no-follow walk of [base]. Rootfs bytes are attributed by path
     * prefix, so symlinks (including a symlinked rootfs) are skipped exactly
     * like the old `calculateDirSize`.
     */
    private fun measureTree(base: File, rootfs: File, cancelled: AtomicBoolean): Measurements? {
        if (!base.exists()) return Measurements(0L, 0L)
        val basePath = base.toPath()
        val rootfsPath = rootfs.toPath()
        var workspaceBytes = 0L
        var rootfsBytes = 0L

        Files.walkFileTree(
            basePath,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes
                ): FileVisitResult =
                    if (cancelled.get()) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (cancelled.get()) return FileVisitResult.TERMINATE
                    if (attrs.isRegularFile) {
                        workspaceBytes += attrs.size()
                        if (file.startsWith(rootfsPath)) {
                            rootfsBytes += attrs.size()
                        }
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                    if (cancelled.get()) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
            }
        )

        // A terminated walk accumulated only a prefix; returning it would
        // cache a partial number, so cancellation always yields null.
        if (cancelled.get()) return null
        return Measurements(workspaceBytes, rootfsBytes)
    }

    private companion object {
        const val TAG = "SizeMeasurement"

        /**
         * LINEXA-PERF-007: bounded concurrency. The previous
         * `Executors.newCachedThreadPool` created one thread per concurrent
         * walk with a 60 s idle keep-alive — on the audited cold start that
         * pool was the busiest non-main thread group (12 034 trace events in
         * 6 s). Measurements are per-workspace single-flight, so two workers
         * saturate the useful parallelism (one app-private volume) without
         * letting a burst of callers spawn a thread each.
         */
        private const val MEASUREMENT_THREADS = 2

        val defaultScheduler: java.util.concurrent.ScheduledExecutorService by lazy {
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread({
                    runCatching {
                        android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_BACKGROUND
                        )
                    }
                    runnable.run()
                }, "linexa-size-warm").apply { isDaemon = true }
            }
        }

        val defaultExecutor: ExecutorService by lazy {
            Executors.newFixedThreadPool(MEASUREMENT_THREADS) { runnable ->
                Thread({
                    // DRA-005/DRA-006 closure: this walk can consume seconds of
                    // CPU (measured 7.1 s of CPU in a 12 s entry trace on a
                    // 2.2 GB workspace). Linux nice 10 keeps it behind the UI
                    // and render threads instead of competing with them frame
                    // for frame. Best-effort: a non-Android JVM (unit tests)
                    // may not implement it.
                    runCatching {
                        android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_BACKGROUND
                        )
                    }
                    runnable.run()
                }, "linexa-size-measure").apply { isDaemon = true }
            }
        }
    }
}
