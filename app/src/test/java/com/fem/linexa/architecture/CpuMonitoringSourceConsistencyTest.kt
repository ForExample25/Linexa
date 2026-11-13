package com.fem.linexa.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Structural guard for the CPU-monitoring consolidation.
 *
 * The behavioural tests pin each calculator/tracker; this test pins the wiring
 * that makes them one authority. Without it, a future screen can quietly grow a
 * fourth private `/proc/stat` delta or keep polling in the background while all
 * the unit tests still pass.
 */
class CpuMonitoringSourceConsistencyTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/data/resource/DeviceCpuMonitor.kt")
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            if (File(dir, marker.path).exists()) {
                moduleRoot = dir
                return
            }
            dir = dir.parentFile
        }
        moduleRoot = File(".")
    }

    private fun source(path: String): String =
        File(moduleRoot, "src/main/java/com/fem/linexa/$path").readText()

    @Test
    fun `device cpu has one authority consumed by both samplers`() {
        val hostSampler = source("data/resource/HostResourceSampler.kt")
        val deviceInfo = source("data/hostinfo/AndroidHostInfoSources.kt")

        assertTrue(
            "HostResourceSampler must delegate to DeviceCpuMonitor",
            hostSampler.contains("deviceCpuMonitor.snapshot()")
        )
        assertTrue(
            "Device Info must delegate to the same DeviceCpuMonitor",
            deviceInfo.contains("deviceCpuMonitor.snapshot()")
        )
        assertFalse(
            "Device Info must not keep a second /proc/stat baseline",
            deviceInfo.contains("lastCpuTickSample") ||
                deviceInfo.contains("lastPerCoreTickSample") ||
                deviceInfo.contains("parseProcStatAggregate") ||
                deviceInfo.contains("parsePerCoreStat")
        )
        assertFalse(
            "HostResourceSampler must not parse /proc/stat itself",
            hostSampler.contains("parseProcStat")
        )
    }

    @Test
    fun `process cpu baselines are shared by the workspace and host scanners`() {
        val monitor = source("data/resource/ProcResourceMonitor.kt")
        val scanner = source("data/resource/HostProcessScanner.kt")

        for ((name, body) in listOf("ProcResourceMonitor" to monitor, "HostProcessScanner" to scanner)) {
            assertTrue(
                "$name must use the shared ProcessCpuTracker",
                body.contains("processCpuTracker.observe(")
            )
            assertFalse(
                "$name must not own a private previous-tick map",
                body.contains("previous[pid]") || body.contains("ConcurrentHashMap<Int, Reading>")
            )
        }
        assertTrue(
            "workspace snapshots must coalesce simultaneous consumers",
            monitor.contains("SNAPSHOT_CACHE_MS") && monitor.contains("cachedSnapshot")
        )
    }

    @Test
    fun `per-core usage is indexed by kernel core number`() {
        val mapper = source("ui/screens/device/DeviceInfoMapper.kt")
        val deviceInfo = source("data/hostinfo/AndroidHostInfoSources.kt")

        assertTrue(
            "the device screen must pair usage with core.index, not list position",
            mapper.contains("perCore.getOrNull(core.index)")
        )
        assertFalse(
            "positional pairing shifts every value after an offline core",
            mapper.contains("mapIndexed") && mapper.contains("perCore.getOrNull(i)")
        )
        assertTrue(
            "the source list must be padded by core index",
            deviceInfo.contains("list index == kernel core number") ||
                deviceInfo.contains("coreIndexedUsages")
        )
    }

    @Test
    fun `every screen-level cpu poller is foreground gated`() {
        val screens = mapOf(
            "ui/screens/workspace/WorkspaceScreen.kt" to "performanceViewModel.pause()",
            "ui/screens/taskmanager/TaskManagerScreen.kt" to "viewModel.stop()",
            "ui/screens/activity/ActivityScreen.kt" to "viewModel.pause()"
        )
        for ((path, pauseCall) in screens) {
            val body = source(path)
            assertTrue("$path must observe the lifecycle", body.contains("rememberIsForeground()"))
            assertTrue(
                "$path must stop its poller when the app leaves the foreground",
                body.contains("if (foreground") && body.contains(pauseCall)
            )
        }
    }

    @Test
    fun `app thread monitoring is self-scoped and exposed to the task manager`() {
        val threadTracker = source("data/resource/AppThreadCpuTracker.kt")
        val hostSampler = source("data/resource/HostResourceSampler.kt")
        val taskManager = source("ui/screens/taskmanager/TaskManagerViewModel.kt")
        val appModule = source("di/AppModule.kt")

        assertTrue(
            "thread stats must be read from this app's own task directory",
            threadTracker.contains("/proc/\$pid/task")
        )
        assertTrue(
            "the per-thread stat path must stay under the task directory",
            threadTracker.contains("/proc/\$pid/task/\$tid/stat")
        )
        assertTrue(
            "HostResourceSampler must expose the app thread snapshot",
            hostSampler.contains("fun appThreads()")
        )
        assertTrue(
            "Task Manager must sample app threads",
            taskManager.contains("hostSampler.appThreads()")
        )
        assertTrue(
            "Hilt must bind the CPU counter reader",
            appModule.contains("DeviceCpuCounterReader")
        )
        assertTrue(
            "Hilt must bind the thread stat reader",
            appModule.contains("ThreadStatReader")
        )
    }

    @Test
    fun `workspace threads do not confuse process and thread counter tables`() {
        val threadTracker = source("data/resource/AppThreadCpuTracker.kt")
        assertTrue(
            "the thread tracker must own a separate table: main-thread TID equals a pid",
            threadTracker.contains("ProcessCpuTracker()")
        )
        assertFalse(
            "the thread tracker must not be handed the process tracker singleton",
            threadTracker.contains("processCpuTracker:")
        )
    }
}
