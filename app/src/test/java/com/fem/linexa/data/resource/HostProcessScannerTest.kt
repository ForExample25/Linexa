package com.fem.linexa.data.resource

import com.fem.linexa.domain.resource.MetricAvailability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostProcessScannerTest {

    private class FakeProcFs(
        val pids: List<Int>,
        val files: Map<Pair<Int, String>, String>
    ) : ProcFsReader {
        override fun listPids(): List<Int>? = pids
        override fun readText(pid: Int, name: String): String? = files[pid to name]
        override fun selfPid(): Int = 5
        override fun selfUid(): Int? = 1000
    }

    private class FakeSignals : ProcessSignals {
        val killed = mutableListOf<Int>()
        override fun terminate(pid: Int): Boolean {
            killed += pid
            return true
        }
    }

    /** Counts every `/proc/<pid>/<file>` read so the cache can be asserted. */
    private class CountingProcFs(private val delegate: FakeProcFs) : ProcFsReader {
        val reads = mutableMapOf<Pair<Int, String>, Int>()
        override fun listPids(): List<Int>? = delegate.listPids()
        override fun readText(pid: Int, name: String): String? {
            reads[pid to name] = (reads[pid to name] ?: 0) + 1
            return delegate.readText(pid, name)
        }
        override fun selfPid(): Int = delegate.selfPid()
        override fun selfUid(): Int? = delegate.selfUid()
    }

    private fun stat(
        pid: Int,
        name: String,
        parent: Int,
        uid: Int,
        cmdline: String,
        start: Long = 100L + pid
    ): String {
        @Suppress("UNUSED_PARAMETER") uid
        @Suppress("UNUSED_PARAMETER") cmdline
        val fields = MutableList(37) { "0" }
        fields[0] = "S"
        fields[1] = parent.toString()
        fields[11] = "100"
        fields[12] = "20"
        fields[19] = start.toString()
        fields[36] = "2"
        return "$pid ($name) ${fields.joinToString(" ")}"
    }

    private fun status(uid: Int, threads: Int = 2, rssKb: Long = 512) = listOf(
        "Name:\tprocess",
        "Uid:\t$uid\t$uid\t$uid\t$uid",
        "Threads:\t$threads",
        "VmRSS:\t$rssKb kB"
    ).joinToString("\n")

    private val files = mapOf(
        (5 to "stat") to stat(5, "linexa", 1, 1000, "/data/app/linexa"),
        (5 to "status") to status(1000),
        (5 to "cmdline") to "/data/app/linexa\u0000--main",
        (6 to "stat") to stat(6, "proot", 5, 1000, "proot\u0000-r\u0000/rootfs"),
        (6 to "status") to status(1000, threads = 4),
        (6 to "cmdline") to "proot\u0000-r\u0000/rootfs\u0000/bin/sh",
        (6 to "smaps_rollup") to "Pss:\t2048 kB\n",
        (7 to "stat") to stat(7, "other", 1, 2000, "/system/bin/other"),
        (7 to "status") to status(2000),
        (7 to "cmdline") to "/system/bin/other\u0000--daemon"
    )

    private val reader = FakeProcFs(listOf(5, 6, 7, 8), files)

    @Test
    fun `readable and inaccessible pids are counted honestly`() = runBlocking {
        val snapshot = HostProcessScanner(reader, CpuTopologyProvider(), FakeSignals()).snapshot()
        assertEquals(3, snapshot.readableProcesses)
        assertEquals(1, snapshot.inaccessibleProcesses)
        assertTrue(snapshot.procListReadable)
        assertTrue(snapshot.scopeLimited)
        assertEquals(3, snapshot.processes.size)
        assertTrue(snapshot.topology.capacityCores >= 1)
    }

    @Test
    fun `self and other uid cannot be signalled but own uid can`() = runBlocking {
        val signals = FakeSignals()
        val scanner = HostProcessScanner(reader, CpuTopologyProvider(), signals)
        val snapshot = scanner.snapshot()
        val self = snapshot.processes.single { it.pid == 5 }
        val own = snapshot.processes.single { it.pid == 6 }
        val other = snapshot.processes.single { it.pid == 7 }

        assertTrue(self.isSelf)
        assertFalse(self.canSignal)
        assertTrue(own.canSignal)
        assertEquals(1000, own.uid)
        assertFalse(other.canSignal)
        assertEquals(2000, other.uid)

        assertFalse(scanner.terminate(5))
        assertFalse(scanner.terminate(7))
        assertTrue(scanner.terminate(6))
        assertEquals(listOf(6), signals.killed)
    }

    @Test
    fun `a recycled pid is rejected when its start time no longer matches`() = runBlocking {
        val signals = FakeSignals()
        val scanner = HostProcessScanner(reader, CpuTopologyProvider(), signals)
        val row = scanner.snapshot().processes.single { it.pid == 6 }

        assertFalse(scanner.terminate(row.pid, row.startTimeTicks + 1L))
        assertTrue(signals.killed.isEmpty())
        assertTrue(scanner.terminate(row.pid, row.startTimeTicks))
        assertEquals(listOf(6), signals.killed)
    }

    @Test
    fun `rows carry command threads memory and pending-cpu state`() = runBlocking {
        val snapshot = HostProcessScanner(reader, CpuTopologyProvider(), FakeSignals()).snapshot()
        val own = snapshot.processes.single { it.pid == 6 }
        val other = snapshot.processes.single { it.pid == 7 }

        assertEquals("proot -r /rootfs /bin/sh", own.command)
        assertEquals(4, own.threads)
        assertEquals(2048L, own.memKb)
        assertEquals(true, own.memoryIsProportional)
        assertEquals(512L, other.memKb)
        assertEquals(false, other.memoryIsProportional)
        assertNull(own.cpuPercentOfDevice)
        assertEquals(MetricAvailability.PENDING, own.cpuAvailability)
        assertNull(own.parentPid?.takeIf { it <= 0 })
    }

    @Test
    fun `an inaccessible pid is not re-read until the periodic probe`() = runBlocking {
        val counting = CountingProcFs(reader)
        val scanner = HostProcessScanner(counting, CpuTopologyProvider(), FakeSignals())

        val first = scanner.snapshot()
        assertEquals(1, first.inaccessibleProcesses)
        assertEquals(1, counting.reads[8 to "stat"] ?: 0)

        val second = scanner.snapshot()
        assertEquals(
            "PERF-006: a denied pid must not be probed again on every tick",
            1,
            counting.reads[8 to "stat"] ?: 0
        )
        assertEquals("the cached verdict is still reported honestly", 1, second.inaccessibleProcesses)
        // Readable rows are never cached away.
        assertEquals(2, counting.reads[6 to "stat"] ?: 0)
    }

    @Test
    fun `a collapsed process section skips the pss page-table walk`() = runBlocking {
        val counting = CountingProcFs(reader)
        val scanner = HostProcessScanner(counting, CpuTopologyProvider(), FakeSignals())

        val snapshot = scanner.snapshot(includePss = false)
        assertNull("PERF-006: no smaps_rollup while nothing renders it", counting.reads[6 to "smaps_rollup"])
        val own = snapshot.processes.single { it.pid == 6 }
        assertEquals("status VmRSS is still reported", 512L, own.memKb)
        assertEquals(false, own.memoryIsProportional)
    }

    @Test
    fun `failed proc listing is unavailable rather than an empty device`() = runBlocking {
        val snapshot = HostProcessScanner(
            FakeProcFs(emptyList(), emptyMap()).let { broken ->
                object : ProcFsReader by broken {
                    override fun listPids(): List<Int>? = null
                }
            },
            CpuTopologyProvider(),
            FakeSignals()
        ).snapshot()
        assertFalse(snapshot.procListReadable)
        assertTrue(snapshot.scopeLimited)
        assertTrue(snapshot.processes.isEmpty())
    }
}
