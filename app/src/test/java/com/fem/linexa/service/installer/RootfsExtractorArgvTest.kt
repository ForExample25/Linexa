package com.fem.linexa.service.installer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 04: the extraction argv's containment contract.
 *
 * - the bundled `setsid` is the first argv so the child becomes a session/
 *   process-group leader (what `ProcessContainment` can kill as a group);
 * - `--kill-on-exit` is present as a second layer;
 * - without `setsid`, the argv stays valid (proot first, flag still on).
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
                "-v", "2",
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
}
