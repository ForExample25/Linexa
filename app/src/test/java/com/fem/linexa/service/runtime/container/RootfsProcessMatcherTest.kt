package com.fem.linexa.service.runtime.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 04: the `/proc` sweep matcher matrix. The sweep used to be
 * `cmdline.contains(rootfsPath)`; these pin the path boundary, the UID guard
 * and the PID-reuse start-time guard instead.
 */
class RootfsProcessMatcherTest {

    private val marker = "/data/data/com.fem.linexa/files/workspaces/ws-1/rootfs"
    private val selfUid = 10123
    private val goodUid = "Uid:\t10123\t10123\t10123\t10123\n"
    private val otherUid = "Uid:\t99999\t99999\t99999\t99999\n"
    private val goodStat = stat(start = 5000L)

    private fun cmdline(vararg tokens: String) = tokens.joinToString("\u0000")
    private fun stat(start: Long) =
        "1234 (proot) S 1 1234 1234 0 -1 0 0 0 0 0 0 0 0 0 20 0 1 0 $start 0"

    @Test
    fun `the rootfs token itself matches`() {
        assertTrue(RootfsProcessMatcher.referencesRootfs(cmdline("proot", "-r", marker), marker))
    }

    @Test
    fun `a path below the rootfs matches`() {
        assertTrue(
            RootfsProcessMatcher.referencesRootfs(
                cmdline("sh", "${marker}/usr/bin/tmux"),
                marker
            )
        )
    }

    @Test
    fun `a suffix path like rootfs2 does not match`() {
        assertFalse(
            RootfsProcessMatcher.referencesRootfs(
                cmdline("cp", "${marker}2/file", "/dest"),
                marker
            )
        )
        assertFalse(
            RootfsProcessMatcher.referencesRootfs(
                cmdline("editor", "${marker}-backup"),
                marker
            )
        )
    }

    @Test
    fun `a quoted rootfs path still matches`() {
        assertTrue(
            RootfsProcessMatcher.referencesRootfs(
                cmdline("sh", "-c", "\"$marker\""),
                marker
            )
        )
    }

    @Test
    fun `an unrelated cmdline never matches`() {
        assertFalse(RootfsProcessMatcher.referencesRootfs(cmdline("vi", "notes.txt"), marker))
    }

    @Test
    fun `a marker that is a substring of a longer argument never matches`() {
        // marker appears inside "/opt/rootfs-helper" — the substring match of
        // old code killed the helper; the boundary match must not.
        assertFalse(
            RootfsProcessMatcher.referencesRootfs(
                cmdline("sh", "/opt/${marker.substringAfterLast('/')}-helper"),
                marker
            )
        )
    }

    @Test
    fun `uid must equal the app uid`() {
        assertEquals(selfUid, RootfsProcessMatcher.uid(goodUid))
        assertEquals(99999, RootfsProcessMatcher.uid(otherUid))
        assertEquals(null, RootfsProcessMatcher.uid(null))
        assertEquals(null, RootfsProcessMatcher.uid("Name:\tproot\n"))
    }

    @Test
    fun `start time parses field 22`() {
        assertEquals(5000L, RootfsProcessMatcher.startTimeTicks(goodStat))
        assertEquals(null, RootfsProcessMatcher.startTimeTicks(null))
        assertEquals(null, RootfsProcessMatcher.startTimeTicks("1234 (proot) S 1 1234"))
    }

    @Test
    fun `shouldSignal requires path uid and start alignment`() {
        assertTrue(
            RootfsProcessMatcher.shouldSignal(
                cmdline = cmdline("proot", "-r", marker),
                status = goodUid,
                stat = goodStat,
                marker = marker,
                selfUid = selfUid,
                notBeforeTicks = 4000L
            )
        )
        // wrong UID
        assertFalse(
            RootfsProcessMatcher.shouldSignal(
                cmdline = cmdline("proot", "-r", marker),
                status = otherUid,
                stat = goodStat,
                marker = marker,
                selfUid = selfUid,
                notBeforeTicks = null
            )
        )
        // started before the launch (PID reuse)
        assertFalse(
            RootfsProcessMatcher.shouldSignal(
                cmdline = cmdline("proot", "-r", marker),
                status = goodUid,
                stat = stat(start = 3000L),
                marker = marker,
                selfUid = selfUid,
                notBeforeTicks = 4000L
            )
        )
        // no floor: the fresh-app startup sweep (own UID still enforced)
        assertTrue(
            RootfsProcessMatcher.shouldSignal(
                cmdline = cmdline("proot", "-r", marker),
                status = goodUid,
                stat = goodStat,
                marker = marker,
                selfUid = selfUid,
                notBeforeTicks = null
            )
        )
        // unreadable start time with a floor fails closed
        assertFalse(
            RootfsProcessMatcher.shouldSignal(
                cmdline = cmdline("proot", "-r", marker),
                status = goodUid,
                stat = null,
                marker = marker,
                selfUid = selfUid,
                notBeforeTicks = 4000L
            )
        )
        // empty cmdline never signals
        assertFalse(
            RootfsProcessMatcher.shouldSignal(
                cmdline = "",
                status = goodUid,
                stat = goodStat,
                marker = marker,
                selfUid = selfUid,
                notBeforeTicks = null
            )
        )
    }
}
