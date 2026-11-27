package com.fem.linexa.service.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** F-010/F-037: the persisted size cache round-trips and invalidates per id. */
class SizeMeasurementStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = SizeMeasurementStore(File(tmp.root, "sizes.properties"))

    @Test
    fun `a written measurement survives a new store instance`() {
        store().write("ws-1", SizeMeasurementCache.Measurements(workspaceBytes = 500, rootfsBytes = 400))

        val reopened = store().read("ws-1")

        assertEquals(500L, reopened?.workspaceBytes)
        assertEquals(400L, reopened?.rootfsBytes)
    }

    @Test
    fun `invalidation removes only the requested workspace`() {
        val store = store()
        store.write("a", SizeMeasurementCache.Measurements(10, 5))
        store.write("b", SizeMeasurementCache.Measurements(20, 15))

        store.invalidate("a")

        assertNull(store.read("a"))
        assertEquals(20L, store.read("b")?.workspaceBytes)
    }

    @Test
    fun `an impossible record is rejected`() {
        val file = File(tmp.root, "sizes.properties")
        file.writeText("ws.rootfs=999\nws.workspace=1\nws.measuredAt=0\n")

        assertNull(SizeMeasurementStore(file).read("ws"))
    }

    @Test
    fun `a corrupt file reads as absent instead of throwing`() {
        val file = File(tmp.root, "sizes.properties")
        file.writeText("not a properties file \u0000\u0001")

        assertNull(SizeMeasurementStore(file).read("ws"))
    }

    @Test
    fun `age is derived from the persisted measuredAt`() {
        val file = File(tmp.root, "sizes.properties")
        file.writeText("ws.workspace=10\nws.rootfs=5\nws.measuredAt=1000000\n")
        val store = SizeMeasurementStore(file)

        assertEquals(5_000L, store.ageMillis("ws", nowMs = 1_005_000L))
        assertNull("unknown workspace has no age", store.ageMillis("absent", nowMs = 1_005_000L))
    }

    @Test
    fun `the file is written atomically and leaves no temp file`() {
        val file = File(tmp.root, "sizes.properties")
        SizeMeasurementStore(file).write("ws", SizeMeasurementCache.Measurements(1, 1))

        assertTrue(file.isFile)
        assertTrue(!File(tmp.root, "sizes.properties.tmp").exists())
    }
}
