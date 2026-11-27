package com.fem.linexa.service.runtime

import android.app.Application
import com.fem.linexa.domain.workspace.RootfsCheck
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/**
 * RF-003 — launch-time rootfs validation must agree with the installer.
 *
 * The old check asked only whether five directory names existed. A rootfs
 * whose shell had been removed by a package operation or a storage fault still
 * passed, reached PRoot, and failed with a bare exit code. These tests pin the
 * classification (`Missing` vs `Damaged` vs `Ready`) without starting a guest.
 */
class WorkspaceFileManagerRootfsCheckTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var manager: WorkspaceFileManager

    @Before
    fun setUp() {
        val app = Mockito.mock(Application::class.java)
        Mockito.`when`(app.filesDir).thenReturn(tmp.newFolder("files"))
        manager = WorkspaceFileManager(app)
    }

    private fun rootfs(id: String): File = manager.getRootfsPath(id).apply { mkdirs() }

    private fun layout(rootfs: File, shellPath: String = "bin/sh"): File {
        for (dir in listOf("etc", "usr", "var", "bin", "lib", "usr/bin", "usr/lib")) {
            File(rootfs, dir).mkdirs()
        }
        return File(rootfs, shellPath).apply {
            parentFile?.mkdirs()
            writeText("#!/bin/sh\nexit 0\n")
            setExecutable(true)
        }
    }

    @Test
    fun aMissingRootfsIsMissing() {
        val check = manager.checkRootfs("absent")
        assertTrue("expected Missing, got $check", check is RootfsCheck.Missing)
        assertFalse(manager.hasValidRootfs("absent"))
    }

    @Test
    fun aRootfsWithoutTheSharedLayoutIsMissing() {
        val rootfs = rootfs("no-usr")
        File(rootfs, "etc").mkdirs()
        File(rootfs, "bin").mkdirs()
        File(rootfs, "lib").mkdirs()

        assertTrue(manager.checkRootfs("no-usr") is RootfsCheck.Missing)
    }

    @Test
    fun aRootfsWithNoShellIsDamagedNotMissing() {
        val rootfs = rootfs("no-shell")
        for (dir in listOf("etc", "usr", "var", "bin", "lib")) File(rootfs, dir).mkdirs()

        val check = manager.checkRootfs("no-shell")
        assertTrue("expected Damaged, got $check", check is RootfsCheck.Damaged)
        assertTrue(
            "the reason must name the missing shell: ${(check as RootfsCheck.Damaged).reason}",
            check.reason.contains("no launch shell")
        )
    }

    @Test
    fun aNonExecutableShellIsDamaged() {
        val rootfs = rootfs("not-exec")
        val shell = layout(rootfs)
        shell.setExecutable(false)
        Assume.assumeFalse("cannot test exec bits on this filesystem", shell.canExecute())

        val check = manager.checkRootfs("not-exec")
        assertTrue("expected Damaged, got $check", check is RootfsCheck.Damaged)
        assertTrue(
            "the reason must name executability: ${(check as RootfsCheck.Damaged).reason}",
            check.reason.contains("cannot run")
        )
    }

    @Test
    fun aUsrMergedRootfsWithoutTopLevelBinAndLibIsReady() {
        val rootfs = rootfs("usr-merged")
        val shell = File(rootfs, "usr/bin/sh").apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\nexit 0\n")
            setExecutable(true)
        }
        for (dir in listOf("etc", "usr", "var", "usr/lib")) File(rootfs, dir).mkdirs()
        // The real merged-usr shape: /bin and /lib are links into usr.
        Files.createSymbolicLink(File(rootfs, "bin").toPath(), File("usr/bin").toPath())
        Files.createSymbolicLink(File(rootfs, "lib").toPath(), File("usr/lib").toPath())
        Assume.assumeTrue("fixture filesystem must support exec bits", shell.canExecute())

        assertEquals(
            "a merged-usr layout is exactly the Arch/Fedora shape the installer accepts",
            RootfsCheck.Ready,
            manager.checkRootfs("usr-merged")
        )
        assertTrue(manager.hasValidRootfs("usr-merged"))
    }

    @Test
    fun anAbsoluteGuestShellLinkResolvesInsideTheRootfs() {
        val rootfs = rootfs("absolute-link")
        val dash = layout(rootfs, "bin/dash")
        Files.deleteIfExists(File(rootfs, "bin/sh").toPath())
        Files.createSymbolicLink(File(rootfs, "bin/sh").toPath(), File("/bin/dash").toPath())
        Assume.assumeTrue("fixture filesystem must support exec bits", dash.canExecute())

        assertEquals(
            "an absolute guest link must be resolved against the rootfs, not the host /bin",
            RootfsCheck.Ready,
            manager.checkRootfs("absolute-link")
        )
    }

    @Test
    fun aDanglingShellLinkIsDamaged() {
        val rootfs = rootfs("dangling-link")
        layout(rootfs)
        Files.deleteIfExists(File(rootfs, "bin/sh").toPath())
        Files.createSymbolicLink(
            File(rootfs, "bin/sh").toPath(),
            File("missing-dash").toPath()
        )

        val check = manager.checkRootfs("dangling-link")
        assertTrue("expected Damaged, got $check", check is RootfsCheck.Damaged)
    }

    @Test
    fun aShellWithAMissingElfInterpreterNamesTheInterpreter() {
        val rootfs = rootfs("missing-loader")
        // Deliberately a LATER candidate: `bin/sh` and the other earlier
        // candidates are absent, so an implementation that reports the first
        // non-null reason would return the generic absent-candidate text.
        // Only the exact-reason filter surfaces the loader below.
        val shell = layout(rootfs, "usr/bin/bash")
        // The shell itself exists and is executable; only its PT_INTERP is
        // absent. The reason must name the loader instead of falling back to
        // "no launch shell", which would wrongly imply the shell was removed.
        shell.writeBytes(elf64WithInterpreter("/lib/ld-musl-aarch64.so.1"))
        shell.setExecutable(true)
        Assume.assumeTrue("fixture filesystem must support exec bits", shell.canExecute())

        val check = manager.checkRootfs("missing-loader")
        assertTrue("expected Damaged, got $check", check is RootfsCheck.Damaged)
        val reason = (check as RootfsCheck.Damaged).reason
        assertTrue(
            "the reason must name the shell and the missing loader: $reason",
            reason.contains("/usr/bin/bash") &&
                reason.contains("cannot run") &&
                reason.contains("/lib/ld-musl-aarch64.so.1")
        )
        assertFalse(
            "a present shell with a missing loader is not the generic no-shell case: $reason",
            reason.contains("no launch shell")
        )
    }

    @Test
    fun aShellWithAMissingShebangInterpreterNamesTheInterpreter() {
        val rootfs = rootfs("missing-shebang")
        // Same later-candidate shape as the ELF test: earlier candidates are
        // absent, so dropping every "does not exist" reason would produce the
        // generic no-shell message and lose the interpreter detail.
        val shell = layout(rootfs, "usr/bin/ash")
        shell.writeText("#!/lib/ld-musl-missing.so.1\nexit 0\n")
        shell.setExecutable(true)
        Assume.assumeTrue("fixture filesystem must support exec bits", shell.canExecute())

        val check = manager.checkRootfs("missing-shebang")
        assertTrue("expected Damaged, got $check", check is RootfsCheck.Damaged)
        val reason = (check as RootfsCheck.Damaged).reason
        assertTrue(
            "the reason must name the shebang interpreter: $reason",
            reason.contains("/usr/bin/ash") &&
                reason.contains("/lib/ld-musl-missing.so.1")
        )
        assertFalse(
            "a present shell with a missing shebang interpreter is not the generic no-shell case: $reason",
            reason.contains("no launch shell")
        )
    }

    /**
     * Minimal ELF64/LE executable with one `PT_INTERP` program header. No code
     * is needed: [GuestShellValidator] only inspects the header table and the
     * interpreter string.
     */
    private fun elf64WithInterpreter(interpreter: String): ByteArray {
        val interp = (interpreter + "\u0000").toByteArray(Charsets.UTF_8)
        val phoff = 64
        val interpOffset = phoff + 56
        val out = ByteArray(interpOffset + interp.size)
        out[0] = 0x7F
        out[1] = 'E'.code.toByte()
        out[2] = 'L'.code.toByte()
        out[3] = 'F'.code.toByte()
        out[4] = 2 // ELFCLASS64
        out[5] = 1 // little-endian
        out[6] = 1 // EV_CURRENT
        le16(out, 16, 2) // ET_EXEC
        le16(out, 18, 183) // EM_AARCH64
        le32(out, 20, 1L) // e_version
        le64(out, 32, phoff.toLong()) // e_phoff
        le16(out, 52, 64) // e_ehsize
        le16(out, 54, 56) // e_phentsize
        le16(out, 56, 1) // e_phnum
        le32(out, phoff, 3L) // PT_INTERP
        le32(out, phoff + 4, 4L) // PF_R
        le64(out, phoff + 8, interpOffset.toLong()) // p_offset
        le64(out, phoff + 32, interp.size.toLong()) // p_filesz
        le64(out, phoff + 40, interp.size.toLong()) // p_memsz
        le64(out, phoff + 48, 1L) // p_align
        interp.copyInto(out, interpOffset)
        return out
    }

    private fun le16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun le32(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 4) bytes[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }

    private fun le64(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) bytes[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }
}
