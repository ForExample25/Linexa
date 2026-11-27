package com.fem.linexa.service.runtime.container

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for the RootFS finding-018 gate. No runtime or PRoot is involved:
 * each case builds a real directory tree (including minimal ELF headers) and
 * asserts the exact accept/reject contract that `resolveLaunchShell` relies on.
 */
class GuestShellValidatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A fresh rootfs per call: the ELF matrix test builds four in one method. */
    private fun rootfs(): File = tmp.newFolder()

    private fun executable(file: File, body: String = "#!/bin/sh\n") {
        file.parentFile?.mkdirs()
        file.writeText(body)
        check(file.setExecutable(true)) { "could not chmod +x $file" }
    }

    private fun assertRejected(rootfs: File, path: String, fragment: String) {
        val reason = GuestShellValidator.validate(rootfs, path)
        assertTrue(
            "'$path' must be rejected with a reason containing '$fragment', got: $reason",
            reason != null && reason.contains(fragment)
        )
    }

    @Test
    fun `a valid executable script with an existing interpreter is accepted`() {
        val rootfs = rootfs()
        executable(File(rootfs, "bin/sh"))
        executable(File(rootfs, "bin/zsh"))

        assertNull(GuestShellValidator.validate(rootfs, "/bin/zsh"))
    }

    @Test
    fun `a missing path is rejected`() {
        assertRejected(rootfs(), "/bin/zsh", "does not exist inside the rootfs")
    }

    @Test
    fun `a directory is rejected`() {
        val rootfs = rootfs()
        File(rootfs, "bin/zsh").mkdirs()
        assertRejected(rootfs, "/bin/zsh", "is not a regular file")
    }

    @Test
    fun `a non-executable file is rejected`() {
        val rootfs = rootfs()
        val file = File(rootfs, "bin/zsh")
        file.parentFile?.mkdirs()
        file.writeText("#!/bin/sh\n")
        file.setExecutable(false, false)
        assertTrue("the fixture must really lack +x", !file.canExecute())
        assertRejected(rootfs, "/bin/zsh", "is not executable")
    }

    @Test
    fun `a non-absolute path is rejected`() {
        assertRejected(rootfs(), "bin/zsh", "is not an absolute guest path")
    }

    @Test
    fun `a traversal out of the rootfs is rejected`() {
        assertRejected(rootfs(), "/../etc/passwd", "escapes the rootfs")
    }

    @Test
    fun `a script with a missing shebang interpreter is rejected`() {
        val rootfs = rootfs()
        executable(File(rootfs, "bin/zsh"), "#!/bin/missing-zsh\n")
        assertRejected(rootfs, "/bin/zsh", "shebang interpreter '/bin/missing-zsh' does not exist")
    }

    @Test
    fun `a script with a non-absolute shebang interpreter is rejected`() {
        val rootfs = rootfs()
        executable(File(rootfs, "bin/zsh"), "#!bin/sh\n")
        assertRejected(rootfs, "/bin/zsh", "is not an absolute guest path")
    }

    @Test
    fun `an executable non-script non-ELF file is left to exec`() {
        val rootfs = rootfs()
        executable(File(rootfs, "bin/zsh"), "not a script\n")
        assertNull(GuestShellValidator.validate(rootfs, "/bin/zsh"))
    }

    @Test
    fun `a shebang naming the rootfs itself is rejected`() {
        val rootfs = rootfs()
        executable(File(rootfs, "bin/zsh"), "#!/\n")
        assertRejected(rootfs, "/bin/zsh", "names the rootfs root itself")
    }

    @Test
    fun `an absolute guest symlink is resolved inside the rootfs`() {
        val rootfs = rootfs()
        executable(File(rootfs, "bin/sh"))
        executable(File(rootfs, "usr/bin/zsh"))
        File(rootfs, "bin").mkdirs()
        Files.createSymbolicLink(File(rootfs, "bin/zsh").toPath(), Paths.get("/usr/bin/zsh"))

        assertNull(GuestShellValidator.validate(rootfs, "/bin/zsh"))
    }

    @Test
    fun `a relative guest symlink is resolved inside the rootfs`() {
        val rootfs = rootfs()
        executable(File(rootfs, "bin/sh"))
        executable(File(rootfs, "usr/bin/zsh"))
        File(rootfs, "bin").mkdirs()
        Files.createSymbolicLink(File(rootfs, "bin/zsh").toPath(), Paths.get("../usr/bin/zsh"))

        assertNull(GuestShellValidator.validate(rootfs, "/bin/zsh"))
    }

    @Test
    fun `a symlink that escapes the rootfs is rejected`() {
        val rootfs = rootfs()
        File(rootfs, "bin").mkdirs()
        Files.createSymbolicLink(
            File(rootfs, "bin/zsh").toPath(),
            Paths.get("/../../../../etc/passwd")
        )

        assertRejected(rootfs, "/bin/zsh", "escapes the rootfs")
    }

    @Test
    fun `a symlink loop is rejected instead of followed forever`() {
        val rootfs = rootfs()
        File(rootfs, "bin").mkdirs()
        Files.createSymbolicLink(File(rootfs, "bin/zsh").toPath(), Paths.get("/bin/other"))
        Files.createSymbolicLink(File(rootfs, "bin/other").toPath(), Paths.get("/bin/zsh"))

        assertRejected(rootfs, "/bin/zsh", "too many symbolic links")
    }

    @Test
    fun `a static ELF without an interpreter is accepted`() {
        val rootfs = rootfs()
        val elf = File(rootfs, "bin/sh")
        writeElf(elf, interpreter = null)
        assertNull(GuestShellValidator.validate(rootfs, "/bin/sh"))
    }

    @Test
    fun `an ELF whose PT_INTERP is missing is rejected`() {
        val rootfs = rootfs()
        val elf = File(rootfs, "bin/sh")
        writeElf(elf, interpreter = "/lib/ld-linexa-missing.so.1")

        assertRejected(
            rootfs,
            "/bin/sh",
            "ELF interpreter '/lib/ld-linexa-missing.so.1' does not exist"
        )
    }

    @Test
    fun `an ELF whose PT_INTERP exists is accepted for 32- and 64-bit, both endiannesses`() {
        for (elf32 in listOf(true, false)) {
            for (littleEndian in listOf(true, false)) {
                val rootfs = rootfs()
                val elf = File(rootfs, "bin/sh")
                writeElf(elf, interpreter = "/lib/ld-test.so.1", elf32 = elf32, littleEndian = littleEndian)
                executable(File(rootfs, "lib/ld-test.so.1"))

                assertNull(
                    "ELF32=$elf32 littleEndian=$littleEndian must be accepted",
                    GuestShellValidator.validate(rootfs, "/bin/sh")
                )
            }
        }
    }

    @Test
    fun `a malformed ELF is rejected`() {
        val rootfs = rootfs()
        val elf = File(rootfs, "bin/sh")
        executable(elf, "")
        // ELF magic + class 2 + data 1, but shorter than a 64-bit header.
        elf.writeBytes(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 0, 0))

        assertRejected(rootfs, "/bin/sh", "malformed program headers")
    }

    /**
     * Minimal ELF with one `PT_INTERP` (or none). The header table is the only
     * structure the validator reads; section headers and executable code are
     * deliberately absent.
     */
    private fun writeElf(
        file: File,
        interpreter: String?,
        elf32: Boolean = false,
        littleEndian: Boolean = true
    ) {
        val headerSize = if (elf32) 52 else 64
        val phentSize = if (elf32) 32 else 56
        val phnum = if (interpreter == null) 0 else 1
        val interpBytes = interpreter?.let { it.toByteArray(Charsets.UTF_8) + 0.toByte() } ?: ByteArray(0)
        val interpOffset = headerSize + if (phnum == 1) phentSize else 0
        val total = interpOffset + interpBytes.size
        val buffer = ByteBuffer.allocate(total).order(
            if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        )
        buffer.put(0, 0x7F.toByte())
        buffer.put(1, 'E'.code.toByte())
        buffer.put(2, 'L'.code.toByte())
        buffer.put(3, 'F'.code.toByte())
        buffer.put(4, (if (elf32) 1 else 2).toByte())
        buffer.put(5, (if (littleEndian) 1 else 2).toByte())
        buffer.put(6, 1.toByte())
        if (elf32) {
            buffer.putInt(28, headerSize) // e_phoff
            buffer.putShort(42, phentSize.toShort()) // e_phentsize
            buffer.putShort(44, phnum.toShort()) // e_phnum
            if (phnum == 1) {
                buffer.putInt(headerSize, 3) // PT_INTERP
                buffer.putInt(headerSize + 4, interpOffset) // p_offset
                buffer.putInt(headerSize + 16, interpBytes.size) // p_filesz
            }
        } else {
            buffer.putLong(32, headerSize.toLong()) // e_phoff
            buffer.putShort(54, phentSize.toShort()) // e_phentsize
            buffer.putShort(56, phnum.toShort()) // e_phnum
            if (phnum == 1) {
                buffer.putInt(headerSize, 3) // PT_INTERP
                buffer.putLong(headerSize + 8, interpOffset.toLong()) // p_offset
                buffer.putLong(headerSize + 32, interpBytes.size.toLong()) // p_filesz
            }
        }
        buffer.position(interpOffset)
        buffer.put(interpBytes)
        file.parentFile?.mkdirs()
        file.writeBytes(buffer.array())
        check(file.setExecutable(true)) { "could not chmod +x $file" }
    }
}
