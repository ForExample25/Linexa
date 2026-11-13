package com.fem.linexa.service.installer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM tests for [RootFsIdentityReader] — the live-rootfs bridge that resolves
 * a workspace's catalog id from the extracted files on disk (used by the
 * startup backfill and import path so existing workspaces get branded icons).
 */
class RootFsIdentityReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun rootfsWith(osRelease: String?, marker: Pair<String, String>? = null): File {
        val rootfs = tmp.newFolder("rootfs")
        val etc = File(rootfs, "etc").apply { mkdirs() }
        if (osRelease != null) File(etc, "os-release").writeText(osRelease)
        if (marker != null) File(etc, marker.first).writeText(marker.second)
        return rootfs
    }

    @Test
    fun detectsDebianFromLiveOsRelease() {
        val rootfs = rootfsWith(
            """
            NAME="Debian GNU/Linux"
            VERSION_ID="12"
            ID=debian
            """.trimIndent()
        )
        assertEquals("debian", RootFsIdentityReader.catalogDistroId(rootfs))
    }

    @Test
    fun detectsUbuntuFromLiveOsRelease() {
        val rootfs = rootfsWith("ID=ubuntu\nVERSION_ID=\"22.04\"\n")
        assertEquals("ubuntu", RootFsIdentityReader.catalogDistroId(rootfs))
    }

    @Test
    fun detectsAlpineFromLiveMarkerFile() {
        val rootfs = rootfsWith(osRelease = null, marker = "alpine-release" to "3.24.1")
        assertEquals("alpine", RootFsIdentityReader.catalogDistroId(rootfs))
    }

    @Test
    fun detectsDebianFromDebianVersionMarker() {
        val rootfs = rootfsWith(osRelease = null, marker = "debian_version" to "12.5\n")
        assertEquals("debian", RootFsIdentityReader.catalogDistroId(rootfs))
    }

    @Test
    fun mapsArchToCatalogId() {
        assertEquals("archlinux", RootFsIdentityReader.catalogIdFor(RootFsProbe.DistroId.ARCH))
    }

    @Test
    fun unknownRootfsGivesNullCatalogId() {
        val rootfs = rootfsWith(osRelease = null)
        assertNull(RootFsIdentityReader.catalogDistroId(rootfs))
    }

    @Test
    fun missingRootfsDirectoryGivesNull() {
        assertNull(RootFsIdentityReader.catalogDistroId(File(tmp.root, "does-not-exist")))
    }

    @Test
    fun otherDistroMapsToNull() {
        assertNull(RootFsIdentityReader.catalogIdFor(RootFsProbe.DistroId.OTHER))
    }
}
