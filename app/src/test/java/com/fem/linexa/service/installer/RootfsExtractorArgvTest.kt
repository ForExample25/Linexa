package com.fem.linexa.service.installer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 04: the extraction argv's containment contract.
 *
 * - a `setsid` binary, when the bootstrap ships one, is the first argv so the
 *   child becomes a session/process-group leader (a hardening layer for
 *   [com.fem.linexa.core.filesystem.ProcessTreeTerminator]);
 * - `--kill-on-exit` is present as a second layer;
 * - without `setsid` (the current busybox), the argv stays valid — proot
 *   first, flag still on, tree containment from the PPID snapshot.
 */
class RootfsExtractorArgvTest {

    @Test
    fun `the containment layer wraps the proot argv`() {
        val argv = buildExtractionArgv(
            proot = File("/lib/libproot.so"),
            tar = File("/bin/tar"),
            setsid = File("/bin/setsid"),
            tempArchive = File("/cache/archive.tar"),
            staging = File("/ws/.staging-rootfs"),
            stripComponents = 1
        )

        assertEquals(
            listOf(
                "/bin/setsid",
                "/lib/libproot.so",
                "-v", "0",
                "--link2symlink",
                "--kill-on-exit",
                "/bin/tar",
                "-v",
                "--strip-components=1",
                "-xf", "/cache/archive.tar",
                "-C", "/ws/.staging-rootfs",
                "--delay-directory-restore",
                "--preserve-permissions",
                "--checkpoint=100",
                "--warning=no-timestamp"
            ),
            argv
        )
    }

    @Test
    fun `without setsid proot still runs first and kill-on-exit stays`() {
        val argv = buildExtractionArgv(
            proot = File("/lib/libproot.so"),
            tar = File("/bin/tar"),
            setsid = null,
            tempArchive = File("/cache/archive.tar"),
            staging = File("/ws/.staging-rootfs"),
            stripComponents = 0
        )

        assertEquals("/lib/libproot.so", argv.first())
        assertFalse(argv.contains("/bin/setsid"))
        assertTrue(argv.contains("--kill-on-exit"))
    }

    @Test
    fun `proot syscall tracing is requested only when debug info is enabled`() {
        fun argv(verbose: Boolean) = buildExtractionArgv(
            proot = File("/lib/libproot.so"),
            tar = File("/bin/tar"),
            setsid = null,
            tempArchive = File("/cache/archive.tar"),
            staging = File("/ws/.staging-rootfs"),
            stripComponents = 0,
            verbose = verbose
        )

        val quiet = argv(verbose = false)
        assertEquals(
            "F-004: release installs must not request proot's syscall trace",
            "0",
            quiet[quiet.indexOf("-v") + 1]
        )

        val traced = argv(verbose = true)
        assertEquals(
            "debug info restores the full translation trace",
            "2",
            traced[traced.indexOf("-v") + 1]
        )
    }
}
