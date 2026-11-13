package com.fem.linexa.service.runtime.bootstrap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * §1.57: JVM tests for the BusyBox applet compatibility layer.
 *
 * A stub busybox (a POSIX shell script that dispatches on argv[0]
 * basename — the same applet-selection semantics as the real BusyBox)
 * stands in for the Android ELF, so the SYMLINK LAYER and real
 * ProcessBuilder execution of the applet links are verifiable on any
 * host, including CI (ubuntu-latest).
 */
class BusyboxAppletLayerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val binDir: File by lazy { tmp.newFolder("bootstrap-bin") }

    /**
     * A dispatcher faithful to real BusyBox semantics: the applet is
     * chosen from the basename of argv[0]; `sed`/`grep` answer `--help`,
     * `sh` forwards to the host shell.
     */
    private fun stubBusybox(): File {
        val stub = File(binDir, "busybox")
        stub.writeText(
            "#!/bin/sh\n" +
                "applet=\$(basename \"\$0\")\n" +
                "case \"\$applet\" in\n" +
                "  sed)  echo \"BusyBox v1.38.0 (sed applet)\"; exit 0 ;;\n" +
                "  chmod) echo \"BusyBox v1.38.0 (chmod applet)\"; exit 0 ;;\n" +
                "  id)   echo \"BusyBox v1.38.0 (id applet)\"; exit 0 ;;\n" +
                "  ls)   echo \"BusyBox v1.38.0 (ls applet)\"; exit 0 ;;\n" +
                "  clear) echo \"BusyBox v1.38.0 (clear applet)\"; exit 0 ;;\n" +
                "  whoami) echo \"BusyBox v1.38.0 (whoami applet)\"; exit 0 ;;\n" +
                "  gzip) echo \"BusyBox v1.38.0 (gzip applet)\"; exit 0 ;;\n" +
                "  xz)   echo \"BusyBox v1.38.0 (xz applet)\"; exit 0 ;;\n" +
                "  sh)   if [ \"\$1\" = \"-c\" ]; then shift; exec /bin/sh -c \"\$@\"; fi; exec /bin/sh \"\$@\" ;;\n" +
                "  *)    echo \"busybox: applet \$applet not in test stub\" >&2; exit 127 ;;\n" +
                "esac\n"
        )
        assertTrue("stub busybox must be executable", stub.setExecutable(true))
        return stub
    }

    private fun run(vararg cmd: String): Pair<Int, String> {
        val process = ProcessBuilder(*cmd).start()
        val out = process.inputStream.bufferedReader().readText()
        val rc = process.waitFor()
        return rc to out
    }

    // ── installation ────────────────────────────────────────────────────

    @Test
    fun `install creates every required applet symlink pointing at busybox`() {
        stubBusybox()
        val result = BusyboxAppletLayer.installAppletLinks(binDir)

        assertTrue("no failures, got: ${result.failed}", result.failed.isEmpty())
        assertEquals(BusyboxAppletLayer.REQUIRED_APPLETS, result.created)
        for (applet in BusyboxAppletLayer.REQUIRED_APPLETS) {
            val link = File(binDir, applet)
            assertTrue("$applet must exist (NOFOLLOW)", Files.exists(link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertTrue("$applet must be a symlink", Files.isSymbolicLink(link.toPath()))
            assertEquals("busybox", Files.readSymbolicLink(link.toPath()).toString())
        }
    }

    @Test
    fun `install is idempotent`() {
        stubBusybox()
        BusyboxAppletLayer.installAppletLinks(binDir)
        val second = BusyboxAppletLayer.installAppletLinks(binDir)

        assertTrue("second pass must not fail: ${second.failed}", second.failed.isEmpty())
        assertTrue("second pass must not create: ${second.created}", second.created.isEmpty())
        assertTrue("second pass must not repair: ${second.repaired}", second.repaired.isEmpty())
        assertEquals(BusyboxAppletLayer.REQUIRED_APPLETS.size, second.kept.size)
    }

    @Test
    fun `stale real file is replaced by a symlink`() {
        stubBusybox()
        val stale = File(binDir, "sed")
        stale.writeText("not a real busybox applet")

        val result = BusyboxAppletLayer.installAppletLinks(binDir)

        assertTrue(result.repaired.contains("sed"))
        assertTrue(Files.isSymbolicLink(stale.toPath()))
        assertEquals("busybox", Files.readSymbolicLink(stale.toPath()).toString())
    }

    @Test
    fun `dangling symlink is repaired`() {
        stubBusybox()
        // The fixture must name a REQUIRED applet — repair iterates
        // REQUIRED_APPLETS only. The old `echo` fixture was stale: `echo` (and
        // `sh`) left the required set when §1.66 redefined what the app itself
        // executes, so the old test could only fail with "echo not in repaired".
        val dangling = File(binDir, "chmod")
        Files.createSymbolicLink(dangling.toPath(), Paths.get("/nonexistent/target"))

        val result = BusyboxAppletLayer.installAppletLinks(binDir)

        assertTrue(result.repaired.contains("chmod"))
        assertEquals("busybox", Files.readSymbolicLink(dangling.toPath()).toString())
    }

    // ── ProcessBuilder execution through the applet links ───────────────

    @Test
    fun `ProcessBuilder can execute sed --help through the applet symlink`() {
        stubBusybox()
        BusyboxAppletLayer.installAppletLinks(binDir)

        val (rc, out) = run(File(binDir, "sed").absolutePath, "--help")

        assertEquals("sed --help exit code", 0, rc)
        assertTrue("sed --help output should name the sed applet, got: '$out'", out.contains("sed"))
    }

    // 10A: the old `ProcessBuilder can execute sh -c echo ok through the applet
    // symlink` test is gone — `sh` is deliberately NOT in REQUIRED_APPLETS
    // (§1.66: the Management Terminal uses the host mksh plus the applets the
    // app itself executes), so no bin/sh link exists and the old fixture could
    // only fail with ENOENT. `install creates every required applet symlink`
    // already pins created == REQUIRED_APPLETS, i.e. no extra links either.

    @Test
    fun `ProcessBuilder can execute chmod --help through the applet symlink`() {
        stubBusybox()
        BusyboxAppletLayer.installAppletLinks(binDir)

        val (rc, out) = run(File(binDir, "chmod").absolutePath, "--help")

        assertEquals("chmod --help exit code", 0, rc)
        assertTrue("chmod --help output should name the chmod applet, got: '$out'", out.contains("chmod"))
    }

    @Test
    fun `ProcessBuilder can execute id through the applet symlink`() {
        stubBusybox()
        BusyboxAppletLayer.installAppletLinks(binDir)

        val (rc, out) = run(File(binDir, "id").absolutePath)

        assertEquals("id exit code", 0, rc)
        assertTrue("id output should name the id applet, got: '$out'", out.contains("id"))
    }

    // ── §1.66: the Management Terminal command set (ls/clear/whoami) ─────

    @Test
    fun `ProcessBuilder can execute ls through the applet symlink`() {
        stubBusybox()
        BusyboxAppletLayer.installAppletLinks(binDir)

        val (rc, out) = run(File(binDir, "ls").absolutePath)

        assertEquals("ls exit code", 0, rc)
        assertTrue("ls output should name the ls applet, got: '$out'", out.contains("ls"))
    }

    @Test
    fun `ProcessBuilder can execute clear through the applet symlink`() {
        stubBusybox()
        BusyboxAppletLayer.installAppletLinks(binDir)

        val (rc, out) = run(File(binDir, "clear").absolutePath)

        assertEquals("clear exit code", 0, rc)
        assertTrue("clear output should name the clear applet, got: '$out'", out.contains("clear"))
    }

    @Test
    fun `ProcessBuilder can execute whoami through the applet symlink`() {
        stubBusybox()
        BusyboxAppletLayer.installAppletLinks(binDir)

        val (rc, out) = run(File(binDir, "whoami").absolutePath)

        assertEquals("whoami exit code", 0, rc)
        assertTrue("whoami output should name the whoami applet, got: '$out'", out.contains("whoami"))
    }

    @Test
    fun `every tar decompression helper is a required applet`() {
        for (applet in BusyboxAppletLayer.TAR_DECOMPRESSION_APPLETS) {
            assertTrue(
                "$applet must be in REQUIRED_APPLETS — GNU tar spawns it by bare " +
                    "name through PATH (binDir) for tar -xf, so a missing link " +
                    "fails rootfs extraction with 'tar: $applet: Cannot exec'",
                applet in BusyboxAppletLayer.REQUIRED_APPLETS
            )
        }
    }

    @Test
    fun `install creates the gzip and xz links tar needs for extraction`() {
        stubBusybox()
        BusyboxAppletLayer.installAppletLinks(binDir)

        for (applet in BusyboxAppletLayer.TAR_DECOMPRESSION_APPLETS) {
            val link = File(binDir, applet)
            assertTrue("$applet link must exist", Files.isSymbolicLink(link.toPath()))
            assertEquals("busybox", Files.readSymbolicLink(link.toPath()).toString())
            val (rc, out) = run(link.absolutePath, "--help")
            assertEquals("$applet exit code", 0, rc)
            assertTrue("$applet must dispatch on argv[0], got: '$out'", out.contains(applet))
        }
    }

    // ── diagnostics ─────────────────────────────────────────────────────

    @Test
    fun `appletDiagnostics reports every required applet without throwing`() {
        stubBusybox()
        BusyboxAppletLayer.installAppletLinks(binDir)

        val report = BusyboxAppletLayer.appletDiagnostics(binDir)

        for (applet in BusyboxAppletLayer.REQUIRED_APPLETS) {
            assertTrue("report must mention $applet", report.contains("$applet:"))
        }
        assertFalse(report.contains("<unresolvable>"))
    }

    @Test
    fun `missing busybox is reported as unresolvable, not a crash`() {
        val report = BusyboxAppletLayer.appletDiagnostics(binDir)

        assertTrue(report.contains("busybox: exists=false"))
    }
}
