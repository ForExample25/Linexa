package com.fem.linexa.domain.packagemanager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2 (2026-09-03): the ONE package-operation queue — classification
 * agreement and per-workspace serialization, waiter visibility, and
 * cancellation semantics.
 */
class PackageOperationClassifierTest {

    @Test
    fun `mutating package binaries are classified`() {
        assertTrue(PackageOperationClassifier.isPackageOperation("apt-get update && apt-get install -y curl"))
        assertTrue(PackageOperationClassifier.isPackageOperation("apt-get update"))
        assertTrue(PackageOperationClassifier.isPackageOperation("dpkg --configure -a"))
        assertTrue(PackageOperationClassifier.isPackageOperation("DEBIAN_FRONTEND=noninteractive apt-get install -y foo"))
        assertTrue(PackageOperationClassifier.isPackageOperation("/usr/bin/apt-get install foo"))
        assertTrue(PackageOperationClassifier.isPackageOperation("pacman -Syu"))
        assertTrue(PackageOperationClassifier.isPackageOperation("apk add git"))
        assertTrue(PackageOperationClassifier.isPackageOperation("dnf install -y git"))
        assertTrue(PackageOperationClassifier.isPackageOperation("zypper in git"))
    }

    @Test
    fun `read-only queries are NOT classified`() {
        assertFalse(PackageOperationClassifier.isPackageOperation("dpkg-query -W -f='\${Version}' git"))
        assertFalse(PackageOperationClassifier.isPackageOperation("apt-cache policy git"))
        assertFalse(PackageOperationClassifier.isPackageOperation("cat /etc/os-release"))
        // Note: bare `apt` IS in the conservative set (its mutating
        // subcommands lock the same dpkg database), so `apt list` waits too —
        // an accepted trade for never missing a mutation.
    }

    @Test
    fun `whole-token matching never splits a name`() {
        // 'aptitude' contains 'apt' as a substring but is a different binary.
        assertFalse(PackageOperationClassifier.isPackageOperation("aptitude --help"))
    }

    @Test
    fun `shortLabel bounds and sanitizes`() {
        assertEquals("apt-get update", PackageOperationClassifier.shortLabel("apt-get update"))
        assertTrue(PackageOperationClassifier.shortLabel("a".repeat(500)).length <= 64)
        assertFalse(PackageOperationClassifier.shortLabel("apt\nget\tinstall").contains('\n'))
    }
}

class PackageOperationCoordinatorTest {

    @Test
    fun `concurrent operations serialize — the second waits for the first`() = runBlocking {
        val coordinator = PackageOperationCoordinator()
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val firstStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseFirst = kotlinx.coroutines.CompletableDeferred<Unit>()
        withTimeout(15_000) {
            val first = launch(Dispatchers.Default) {
                coordinator.runExclusive("ws", "apt-get update") {
                    order += "first-start"
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                    order += "first-end"
                }
            }
            // Deterministic gate: the second op only launches once the first
            // provably holds the queue.
            firstStarted.await()
            val second = launch(Dispatchers.Default) {
                coordinator.runExclusive("ws", "apt-get upgrade -y") {
                    order += "second-start"
                }
            }
            // Poll until the second is counted as WAITING (never started).
            var waited = 0L
            while (coordinator.currentOf("ws").waiting == 0 && waited < 10_000) {
                Thread.sleep(10)
                waited += 10
            }
            assertTrue("the second op must be queued, not running", order.count { it == "second-start" } == 0)
            releaseFirst.complete(Unit)
            first.join()
            second.join()
        }
        // Serialization: the second STARTED only after the first ENDED.
        assertTrue(order.containsAll(listOf("first-start", "first-end", "second-start")))
        assertTrue("second must start after first ends", order.indexOf("second-start") > order.indexOf("first-end"))
    }

    @Test
    fun `different workspaces never block each other`() = runBlocking {
        val coordinator = PackageOperationCoordinator()
        // Deterministic: a HOLDS ws-a's queue on a gate. If b could run at
        // all while a still holds, the queues are independent.
        val bRan = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseA = kotlinx.coroutines.CompletableDeferred<Unit>()
        withTimeout(15_000) {
            val a = launch(Dispatchers.Default) {
                coordinator.runExclusive("ws-a", "apt-get update") {
                    releaseA.await()
                }
            }
            val b = launch(Dispatchers.Default) {
                coordinator.runExclusive("ws-b", "apt-get update") {
                    bRan.complete(Unit)
                }
            }
            // b must complete while a is still holding its gate.
            bRan.await()
            releaseA.complete(Unit)
            a.join()
            b.join()
        }
    }

    @Test
    fun `the snapshot publishes owner and waiter state`() = runBlocking {
        val coordinator = PackageOperationCoordinator()
        val observed = kotlinx.coroutines.CompletableDeferred<Unit>()
        withTimeout(15_000) {
            val owner = launch(Dispatchers.Default) {
                coordinator.runExclusive("ws", "apt-get update") {
                    // Hold until the assertions below have observed the busy
                    // state (a gate, not a sleep — deterministic under load).
                    observed.await()
                }
            }
            // Poll STATE, not time: worker threads are not synchronized with
            // this test thread, so the published snapshot is the only latch.
            fun awaitState(expect: (PackageOperationCoordinator.Snapshot) -> Boolean) {
                var waited = 0L
                while (!expect(coordinator.currentOf("ws")) && waited < 10_000) {
                    Thread.sleep(10)
                    waited += 10
                }
            }
            awaitState { it.activeCommand == "apt-get update" }
            val waiter = async(Dispatchers.Default) {
                coordinator.runExclusive("ws", "apt-get install -y git") {}
            }
            awaitState { it.waiting == 1 }
            val snapshot = coordinator.currentOf("ws")
            assertTrue("owner must be visible", snapshot.isBusy)
            assertEquals("apt-get update", snapshot.activeCommand)
            assertEquals(1, snapshot.waiting)
            observed.complete(Unit)
            owner.join()
            waiter.await()
            val idle = coordinator.currentOf("ws")
            assertFalse(idle.isBusy)
            assertNull(idle.activeCommand)
            assertEquals(0, idle.waiting)
        }
    }

    @Test
    fun `a cancelled waiter never becomes the owner`() = runBlocking {
        val coordinator = PackageOperationCoordinator()
        withTimeout(10_000) {
            val releaseGate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val owner = launch(Dispatchers.Default) {
                coordinator.runExclusive("ws", "apt-get update") {
                    releaseGate.await()
                }
            }
            repeat(100) { yield() }
            val waiter = launch(Dispatchers.Default) {
                coordinator.runExclusive("ws", "apt-get install -y git") {}
            }
            repeat(100) { yield() }
            waiter.cancelAndJoin()
            assertEquals(0, coordinator.currentOf("ws").waiting)
            releaseGate.complete(Unit)
            owner.join()
        }
    }

    @Test
    fun `an owner that throws releases the queue`() = runBlocking {
        val coordinator = PackageOperationCoordinator()
        withTimeout(10_000) {
            try {
                coordinator.runExclusive("ws", "apt-get update") {
                    throw IllegalStateException("boom")
                }
            } catch (_: IllegalStateException) {
            }
            // The next operation must acquire immediately — the mutex was
            // released in finally.
            val result = kotlinx.coroutines.CompletableDeferred<String>()
            launch(Dispatchers.Default) {
                coordinator.runExclusive("ws", "dpkg --configure -a") { result.complete("acquired") }
            }
            assertEquals("acquired", result.await())
        }
    }
}
