package com.fem.linexa.service.runtime

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * P0-2 (2026-11-23 performance remediation): the cancellable single-flight
 * size cache. The behavioural properties here are the ones the old
 * `ConcurrentHashMap.computeIfAbsent` implementation could not provide:
 * one walk per workspace, no lock held across I/O, one waiter's interruption
 * never cancels another's work, and no partial/failed result ever cached.
 */
class SizeMeasurementCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val executors = mutableListOf<java.util.concurrent.ExecutorService>()

    @After
    fun tearDown() {
        executors.forEach { it.shutdownNow() }
    }

    private fun singleThread(): java.util.concurrent.ExecutorService =
        Executors.newSingleThreadExecutor().also { executors += it }

    private fun direct(): java.util.concurrent.Executor = java.util.concurrent.Executor { it.run() }

    @Test
    fun `one traversal reports workspace and rootfs totals`() {
        val base = tmp.newFolder("ws")
        val rootfs = File(base, "rootfs").apply { mkdirs() }
        File(rootfs, "a.bin").writeBytes(ByteArray(11))
        File(rootfs, "nested").mkdirs()
        File(rootfs, "nested/b.bin").writeBytes(ByteArray(13))
        File(base, "config.yaml").writeBytes(ByteArray(7))

        val cache = SizeMeasurementCache(direct())
        val measured = cache.get("ws", base, rootfs)

        assertNotNull(measured)
        assertEquals(11L + 13L, measured!!.rootfsBytes)
        assertEquals(11L + 13L + 7L, measured.workspaceBytes)
    }

    @Test
    fun `symlinks are not followed or counted`() {
        val outside = tmp.newFolder("outside")
        File(outside, "huge.bin").writeBytes(ByteArray(4096))
        val base = tmp.newFolder("ws")
        val rootfs = File(base, "rootfs").apply { mkdirs() }
        File(rootfs, "real.bin").writeBytes(ByteArray(5))
        val link = File(rootfs, "link")
        assertTrue(link.createNewFile())
        assertTrue(link.delete())
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (_: Exception) {
            return // fixture filesystem cannot create symlinks
        }

        val measured = SizeMeasurementCache(direct()).get("ws", base, rootfs)
        assertNotNull(measured)
        assertEquals(5L, measured!!.rootfsBytes)
        assertEquals(5L, measured.workspaceBytes)
    }

    @Test
    fun `a successful measurement is cached until invalidation`() {
        val calls = AtomicInteger()
        val cache = SizeMeasurementCache(direct(), measurer = { _, _, _ ->
            calls.incrementAndGet()
            SizeMeasurementCache.Measurements(100, 90)
        })
        val base = tmp.newFolder("ws")
        val rootfs = File(base, "rootfs").apply { mkdirs() }

        assertEquals(100L, cache.get("ws", base, rootfs)!!.workspaceBytes)
        assertEquals(100L, cache.get("ws", base, rootfs)!!.workspaceBytes)
        assertEquals("a cache hit must not walk again", 1, calls.get())

        cache.invalidate("ws")
        assertEquals(100L, cache.get("ws", base, rootfs)!!.workspaceBytes)
        assertEquals("invalidation starts a new generation", 2, calls.get())
    }

    @Test
    fun `concurrent callers share one measurement`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val cache = SizeMeasurementCache(singleThread(), measurer = { _, _, _ ->
            calls.incrementAndGet()
            started.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            SizeMeasurementCache.Measurements(42, 40)
        })
        val base = tmp.newFolder("ws")
        val rootfs = File(base, "rootfs").apply { mkdirs() }
        val pool = Executors.newFixedThreadPool(2).also { executors += it }

        val first = pool.submit<SizeMeasurementCache.Measurements?> { cache.get("ws", base, rootfs) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val second = pool.submit<SizeMeasurementCache.Measurements?> { cache.get("ws", base, rootfs) }
        // Let the second caller join the in-flight generation.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (cache.waiterCount("ws") < 2 && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertEquals("both callers must be waiting on the same walk", 2, cache.waiterCount("ws"))

        release.countDown()
        assertEquals(42L, first.get(5, TimeUnit.SECONDS)!!.workspaceBytes)
        assertEquals(42L, second.get(5, TimeUnit.SECONDS)!!.workspaceBytes)
        assertEquals("one walk for both callers", 1, calls.get())
    }

    @Test
    fun `interrupting one waiter does not cancel another`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = AtomicReference<AtomicBoolean>()
        val cache = SizeMeasurementCache(singleThread(), measurer = { _, _, flag ->
            cancelled.set(flag)
            started.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            SizeMeasurementCache.Measurements(9, 8)
        })
        val base = tmp.newFolder("ws")
        val rootfs = File(base, "rootfs").apply { mkdirs() }

        val first = Thread {
            cache.get("ws", base, rootfs)
        }
        first.isDaemon = true
        first.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))

        var secondResult: SizeMeasurementCache.Measurements? = null
        val second = Thread {
            secondResult = cache.get("ws", base, rootfs)
        }
        second.isDaemon = true
        second.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (cache.waiterCount("ws") < 2 && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(2, cache.waiterCount("ws"))

        first.interrupt()
        first.join(5_000)
        assertFalse("the remaining waiter still needs the walk", cancelled.get()!!.get())

        release.countDown()
        second.join(5_000)
        assertEquals("the surviving waiter must get the measurement", 9L, secondResult!!.workspaceBytes)
    }

    @Test
    fun `the last waiter leaving cancels the walk`() {
        val started = CountDownLatch(1)
        val cancelled = AtomicReference<AtomicBoolean>()
        val finished = CountDownLatch(1)
        val cache = SizeMeasurementCache(singleThread(), measurer = { _, _, flag ->
            cancelled.set(flag)
            started.countDown()
            while (!flag.get()) {
                Thread.sleep(5)
            }
            finished.countDown()
            null
        })
        val base = tmp.newFolder("ws")
        val rootfs = File(base, "rootfs").apply { mkdirs() }

        val caller = Thread { cache.get("ws", base, rootfs) }
        caller.isDaemon = true
        caller.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        caller.interrupt()
        caller.join(5_000)
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertTrue("the only waiter left — the walk must be cancelled", cancelled.get()!!.get())
    }

    @Test
    fun `a cancelled measurement is not cached`() {
        val calls = AtomicInteger()
        val cache = SizeMeasurementCache(direct(), measurer = { _, _, _ ->
            if (calls.incrementAndGet() == 1) null
            else SizeMeasurementCache.Measurements(5, 4)
        })
        val base = tmp.newFolder("ws")
        val rootfs = File(base, "rootfs").apply { mkdirs() }

        assertNull(cache.get("ws", base, rootfs))
        assertFalse(cache.isCached("ws"))
        assertEquals(5L, cache.get("ws", base, rootfs)!!.workspaceBytes)
        assertEquals(2, calls.get())
    }

    @Test
    fun `a failed measurement is not cached`() {
        val calls = AtomicInteger()
        val cache = SizeMeasurementCache(direct(), measurer = { _, _, _ ->
            if (calls.incrementAndGet() == 1) error("boom")
            else SizeMeasurementCache.Measurements(6, 5)
        })
        val base = tmp.newFolder("ws")
        val rootfs = File(base, "rootfs").apply { mkdirs() }

        assertNull(cache.get("ws", base, rootfs))
        assertFalse(cache.isCached("ws"))
        assertEquals(6L, cache.get("ws", base, rootfs)!!.workspaceBytes)
        assertEquals(2, calls.get())
    }

    @Test
    fun `invalidation is generation safe`() {
        val calls = AtomicInteger()
        val releaseFirst = CountDownLatch(1)
        val cache = SizeMeasurementCache(singleThread(), measurer = { _, _, _ ->
            val call = calls.incrementAndGet()
            if (call == 1) {
                assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
                SizeMeasurementCache.Measurements(1, 1)
            } else {
                SizeMeasurementCache.Measurements(2, 2)
            }
        })
        val base = tmp.newFolder("ws")
        val rootfs = File(base, "rootfs").apply { mkdirs() }

        val oldCaller = Thread { cache.get("ws", base, rootfs) }
        oldCaller.isDaemon = true
        oldCaller.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (calls.get() < 1 && System.nanoTime() < deadline) Thread.sleep(10)

        cache.invalidate("ws")
        releaseFirst.countDown()
        oldCaller.join(5_000)

        assertEquals(
            "the new generation must not see the cancelled generation's value",
            2L,
            cache.get("ws", base, rootfs)!!.workspaceBytes
        )
    }

    @Test
    fun `markStale defers the walk until the next reader warms it`() {
        val calls = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cache = SizeMeasurementCache(singleThread(), measurer = { _, _, _ ->
            val call = calls.incrementAndGet()
            if (call >= 2) {
                started.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
            SizeMeasurementCache.Measurements(
                if (call == 1) 100 else 200,
                if (call == 1) 90 else 190
            )
        })
        val base = tmp.newFolder("ws")
        val rootfs = File(base, "rootfs").apply { mkdirs() }

        assertEquals(100L, cache.get("ws", base, rootfs)!!.workspaceBytes)

        cache.markStale("ws")
        assertNull("a stale entry is no longer served from memory", cache.cached("ws"))
        assertEquals("markStale must not walk eagerly", 1, calls.get())

        cache.warm("ws", base, rootfs)
        assertTrue("the next reader starts the revalidation", started.await(5, TimeUnit.SECONDS))
        // A second warm while the walk is in flight must not start another.
        cache.warm("ws", base, rootfs)

        release.countDown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (cache.cached("ws") == null && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertEquals("the refreshed value replaces the old one", 200L, cache.cached("ws")!!.workspaceBytes)
        assertEquals("warm must not duplicate an in-flight walk", 2, calls.get())
    }

    @Test
    fun `warm delay starts the walk only after the delay`() {
        val started = CountDownLatch(1)
        val cache = SizeMeasurementCache(
            singleThread(),
            measurer = { _, _, _ ->
                started.countDown()
                SizeMeasurementCache.Measurements(50, 40)
            },
            warmDelayMs = 150L
        )
        val base = tmp.newFolder("ws")
        val rootfs = File(base, "rootfs").apply { mkdirs() }

        cache.warm("ws", base, rootfs)
        assertFalse(
            "the walk must not start synchronously when a delay is configured",
            started.await(50, TimeUnit.MILLISECONDS)
        )
        assertTrue("the scheduled walk starts after the delay", started.await(5, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (cache.cached("ws") == null && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(50L, cache.cached("ws")!!.workspaceBytes)
    }

    @Test
    fun `a missing base measures zero`() {
        val base = File(tmp.root, "absent")
        val rootfs = File(base, "rootfs")
        val measured = SizeMeasurementCache(direct()).get("ws", base, rootfs)
        assertNotNull(measured)
        assertEquals(0L, measured!!.workspaceBytes)
        assertEquals(0L, measured.rootfsBytes)
    }
}
