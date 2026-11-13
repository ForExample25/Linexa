package com.fem.linexa.domain.resource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessAttributionTest {

    private val marker = "/data/user/0/com.fem.linexa/files/workspaces/ws1/rootfs"
    private val otherMarker = "/data/user/0/com.fem.linexa/files/workspaces/ws2/rootfs"

    @Test
    fun `rootfs path is read from the proot -r argument`() {
        val cmdline = "/data/app/libproot.so\u0000-r\u0000$marker\u0000-w\u0000/root\u0000/bin/bash"
        assertEquals(marker, ProcessAttribution.rootfsMarkerFromCmdline(cmdline))
    }

    @Test
    fun `rootfs path is read from --rootfs and its equals form`() {
        assertEquals(marker, ProcessAttribution.rootfsMarkerFromCmdline("proot\u0000--rootfs\u0000$marker\u0000sh"))
        assertEquals(marker, ProcessAttribution.rootfsMarkerFromCmdline("proot\u0000--rootfs=$marker\u0000sh"))
    }

    @Test
    fun `a traced guest host path recovers the rootfs prefix`() {
        val cmdline = "$marker/usr/bin/bash\u0000--login"
        assertEquals(marker, ProcessAttribution.rootfsMarkerFromCmdline(cmdline))
    }

    @Test
    fun `no rootfs marker is null rather than a guess`() {
        assertNull(ProcessAttribution.rootfsMarkerFromCmdline(null))
        assertNull(ProcessAttribution.rootfsMarkerFromCmdline(""))
        assertNull(ProcessAttribution.rootfsMarkerFromCmdline("proot\u0000-w\u0000/root\u0000sh"))
    }

    @Test
    fun `descendants and marker-matched daemons and orphans are included`() {
        val parents = mapOf(
            100 to 1,     // workspace proot
            101 to 100,   // shell
            102 to 101,   // child
            200 to 1,     // daemon reparented/orphaned
            300 to 1      // another workspace
        )
        val cmdlines = mapOf(
            100 to "proot\u0000-r\u0000$marker\u0000sh",
            101 to "$marker/usr/bin/bash",
            102 to "$marker/usr/bin/apt",
            200 to "$marker/usr/sbin/sshd",
            300 to "$otherMarker/usr/bin/bash"
        )
        val pids = ProcessAttribution.workspacePids(setOf(100), parents, cmdlines, setOf(marker))
        assertEquals(setOf(100, 101, 102, 200), pids)
        assertFalse(300 in pids)
    }

    @Test
    fun `without a marker only the descendant tree is attributed`() {
        val parents = mapOf(100 to 1, 101 to 100, 200 to 1)
        val cmdlines = mapOf(200 to "$marker/usr/sbin/sshd")
        assertEquals(
            setOf(100, 101),
            ProcessAttribution.workspacePids(setOf(100), parents, cmdlines, emptySet())
        )
    }

    @Test
    fun `every proot supervisor for the workspace is protected, not just the first root`() {
        assertTrue(
            ProcessAttribution.isRootfsSupervisor(
                "/data/app/libproot.so\u0000-r\u0000$marker\u0000sh",
                marker
            )
        )
        assertTrue(
            ProcessAttribution.isRootfsSupervisor(
                "tmux\u0000-S\u0000sock\u0000new-session\u0000$marker/../libproot.so\u0000-r\u0000$marker\u0000bash",
                marker
            )
        )
        assertFalse(
            ProcessAttribution.isRootfsSupervisor(
                "proot\u0000-r\u0000$otherMarker\u0000sh",
                marker
            )
        )
        assertFalse(
            ProcessAttribution.isRootfsSupervisor("$marker/usr/bin/bash\u0000--login", marker)
        )
    }

    @Test
    fun `marker matching is path-segment safe`() {
        assertTrue(ProcessAttribution.matchesMarker("$marker/usr/bin/bash", marker))
        assertTrue(ProcessAttribution.matchesMarker("proot\u0000-r\u0000$marker", marker))
        assertFalse(ProcessAttribution.matchesMarker("$marker-suffix/usr/bin/bash", marker))
        assertFalse(ProcessAttribution.matchesMarker("/tmp/not-the-rootfs/usr/bin/bash", marker))
        assertFalse(ProcessAttribution.matchesMarker("x", "/"))
    }
}
