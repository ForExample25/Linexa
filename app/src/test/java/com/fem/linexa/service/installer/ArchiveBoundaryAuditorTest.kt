package com.fem.linexa.service.installer

import com.fem.linexa.core.logging.DiagnosticLogManager
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/**
 * `RFS-03` post-extraction boundary tests.
 *
 * The real extraction path is bundled GNU tar under PRoot; these fixtures
 * reproduce exactly the host-side shapes that path can materialize after
 * extraction. The audit must rewrite in-root absolute links to relative
 * targets, reject any escaping link, and reject symlinked account files
 * before the account fixup can follow them.
 */
class ArchiveBoundaryAuditorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun auditor(): ArchiveBoundaryAuditor =
        ArchiveBoundaryAuditor(Mockito.mock(DiagnosticLogManager::class.java))

    private fun staging(name: String = "staging"): File = tmp.newFolder(name)

    private fun symlink(link: File, target: Path) {
        link.parentFile?.mkdirs()
        Files.createSymbolicLink(link.toPath(), target)
    }

    @Test
    fun `an absolute link to a host path is contained inside staging and never reaches the victim`() {
        val staging = staging()
        val victim = File(tmp.root, "outside-victim").apply { writeText("host secret\n") }
        val victimMode = Files.getAttribute(victim.toPath(), "unix:mode")
        symlink(File(staging, "etc/abs-marker"), victim.toPath())

        val failure = auditor().auditAndNormalize(staging, WORKSPACE)

        assertNull("a host-absolute link is normalized into the guest root, not rejected: $failure", failure)
        val link = File(staging, "etc/abs-marker")
        val target = Files.readSymbolicLink(link.toPath())
        assertFalse("the rewritten target must be relative: $target", target.isAbsolute)
        assertTrue(
            "the rewritten link must resolve inside staging: ${link.canonicalPath}",
            link.canonicalPath.startsWith(staging.canonicalPath + File.separator)
        )
        assertFalse("the host victim must not be reachable through the link", link.exists())
        assertEquals("the host victim content must be untouched", "host secret\n", victim.readText())
        assertEquals(
            "the host victim mode must be untouched",
            victimMode,
            Files.getAttribute(victim.toPath(), "unix:mode")
        )
    }

    @Test
    fun `an absolute link in a read-only directory is normalized and the mode restored`() {
        // Fedora 44 shape: usr/lib64 is 0555 and libnssckbi.so is an
        // absolute link to its versioned neighbor. Replacing it in place
        // failed on device with EACCES before the parent was widened.
        val staging = staging()
        val lib64 = File(staging, "usr/lib64").apply { mkdirs() }
        File(lib64, "libnssckbi.so.0").writeText("module")
        symlink(File(lib64, "libnssckbi.so"), Path.of("/usr/lib64/libnssckbi.so.0"))

        val original = Files.getPosixFilePermissions(lib64.toPath())
        val readOnly = original - PosixFilePermission.OWNER_WRITE
        Files.setPosixFilePermissions(lib64.toPath(), readOnly)
        try {
            val probe = File(lib64, ".write-probe")
            val canWrite = try {
                probe.writeText("probe")
                probe.delete()
                true
            } catch (_: Exception) {
                false
            }
            Assume.assumeFalse("filesystem/user ignores read-only directories", canWrite)

            assertNull(
                "a read-only library directory must not reject the archive",
                auditor().auditAndNormalize(staging, WORKSPACE)
            )
            assertEquals(
                "the directory mode must be restored exactly",
                readOnly,
                Files.getPosixFilePermissions(lib64.toPath())
            )
            val link = File(lib64, "libnssckbi.so")
            assertEquals(
                "the link must become relative",
                "libnssckbi.so.0",
                Files.readSymbolicLink(link.toPath()).toString()
            )
            assertEquals("the link must still resolve", "module", link.readText())
            assertFalse(
                "no rewrite temporary may remain",
                File(lib64, ".linexa-rfs03-libnssckbi.so").exists()
            )
        } finally {
            Files.setPosixFilePermissions(lib64.toPath(), original)
        }
    }

    @Test
    fun `an absolute target that lexically escapes after guest-root mapping is rejected`() {
        val staging = staging()
        val victim = File(tmp.root, "outside-victim").apply { writeText("host secret\n") }
        symlink(File(staging, "etc/evil"), Path.of("/../../outside-victim"))

        val failure = auditor().auditAndNormalize(staging, WORKSPACE)

        assertNotNull("an absolute target with a leading traversal must reject the archive", failure)
        assertTrue("the failure must name the escape: $failure", failure!!.contains("escapes"))
        assertEquals("host secret\n", victim.readText())
    }

    @Test
    fun `a relative symlink escaping staging is rejected`() {
        val staging = staging()
        val victim = File(tmp.root, "relative-victim").apply { writeText("host secret\n") }
        symlink(File(staging, "etc/evil"), Path.of("../../relative-victim"))

        val failure = auditor().auditAndNormalize(staging, WORKSPACE)

        assertNotNull("an escaping relative link must reject the archive", failure)
        assertTrue("the failure must name the escape: $failure", failure!!.contains("escapes"))
        assertEquals("host secret\n", victim.readText())
    }

    @Test
    fun `a composed link chain that leaves staging is rejected despite a lexical in-root path`() {
        // MV-RF-06: p/q/r resolves to the staging root, so `p/q/r/..` is
        // lexically p/q but physically the staging parent. The old lexical
        // check accepted m.
        val staging = staging()
        File(staging, "p/q").mkdirs()
        symlink(File(staging, "p/q/r"), Path.of("../.."))
        symlink(File(staging, "m"), Path.of("p/q/r/.."))

        val failure = auditor().auditAndNormalize(staging, WORKSPACE)

        assertNotNull("a composed escaping chain must reject the archive", failure)
        assertTrue(
            "the failure must name the resolved escape: $failure",
            failure!!.contains("resolved through symlinks") || failure.contains("escapes")
        )
    }

    @Test
    fun `a composed link chain that stays inside staging is accepted`() {
        val staging = staging()
        File(staging, "p/q").mkdirs()
        File(staging, "x").writeText("payload")
        symlink(File(staging, "p/q/r"), Path.of("../.."))
        symlink(File(staging, "m"), Path.of("p/q/r/x"))

        assertNull(
            "an in-root composed chain is ordinary guest content",
            auditor().auditAndNormalize(staging, WORKSPACE)
        )
    }

    @Test
    fun `a target traversing a link to the staging root and then above is rejected`() {
        val staging = staging()
        val victim = File(tmp.root, "composed-victim").apply { writeText("host secret\n") }
        File(staging, "p/q").mkdirs()
        symlink(File(staging, "p/q/r"), Path.of("../.."))
        symlink(File(staging, "m"), Path.of("p/q/r/../../composed-victim"))

        val failure = auditor().auditAndNormalize(staging, WORKSPACE)

        assertNotNull("the physical resolution must reject the archive", failure)
        assertEquals("the host victim must be untouched", "host secret\n", victim.readText())
    }

    @Test
    fun `a symlink cycle inside staging is not treated as an escape`() {
        val staging = staging()
        symlink(File(staging, "a"), Path.of("b"))
        symlink(File(staging, "b"), Path.of("a"))

        assertNull(
            "an in-root cycle is not a host-boundary escape",
            auditor().auditAndNormalize(staging, WORKSPACE)
        )
    }

    @Test
    fun `a legitimate absolute in-root link is rewritten to a relative target`() {
        val staging = staging()
        File(staging, "usr/bin").mkdirs()
        symlink(File(staging, "bin"), Path.of("/usr/bin"))

        val failure = auditor().auditAndNormalize(staging, WORKSPACE)

        assertNull("a legitimate in-root absolute link must be accepted: $failure", failure)
        val link = File(staging, "bin").toPath()
        val target = Files.readSymbolicLink(link)
        assertFalse("the rewritten target must be relative: $target", target.isAbsolute)
        assertEquals("usr/bin", target.toString())
        assertEquals(
            "the rewritten link must resolve to the same in-root directory",
            File(staging, "usr/bin").canonicalFile,
            File(staging, "bin").canonicalFile
        )
    }

    @Test
    fun `a legitimate relative in-root link is left exactly as extracted`() {
        val staging = staging()
        File(staging, "usr/bin").mkdirs()
        symlink(File(staging, "bin"), Path.of("usr/bin"))

        assertNull(auditor().auditAndNormalize(staging, WORKSPACE))

        assertEquals("usr/bin", Files.readSymbolicLink(File(staging, "bin").toPath()).toString())
    }

    @Test
    fun `a dangling absolute in-root link is rewritten without creating the target`() {
        val staging = staging()
        symlink(File(staging, "etc/resolv.conf"), Path.of("/run/systemd/resolve/stub-resolv.conf"))

        assertNull(auditor().auditAndNormalize(staging, WORKSPACE))

        val target = Files.readSymbolicLink(File(staging, "etc/resolv.conf").toPath())
        assertEquals("../run/systemd/resolve/stub-resolv.conf", target.toString())
        assertFalse(
            "the audit must not create a dangling target",
            Files.exists(staging.toPath().resolve("run/systemd/resolve/stub-resolv.conf"), LinkOption.NOFOLLOW_LINKS)
        )
        assertFalse("the link must remain dangling", File(staging, "etc/resolv.conf").exists())
    }

    @Test
    fun `a symlinked account file is rejected even when it points in-root`() {
        val staging = staging()
        File(staging, "etc").mkdirs()
        File(staging, "etc/shadow").writeText("root:*:18000:0:99999:7:::\n")
        symlink(File(staging, "etc/passwd"), Path.of("shadow"))

        val failure = auditor().auditAndNormalize(staging, WORKSPACE)

        assertNotNull(failure)
        assertTrue("the failure must name the account-file policy: $failure", failure!!.contains("must not be symlinks"))
    }

    @Test
    fun `a non-regular account file is rejected before fixup`() {
        val staging = staging()
        File(staging, "etc/group").mkdirs()

        val failure = auditor().auditAndNormalize(staging, WORKSPACE)

        assertNotNull(failure)
        assertTrue("the failure must name the non-regular file: $failure", failure!!.contains("not a regular file"))
    }

    @Test
    fun `an absolute target that climbs out and back in is normalized lexically`() {
        val staging = staging()
        File(staging, "usr/bin").mkdirs()
        File(staging, "usr/bin/tool").writeText("tool\n")
        symlink(File(staging, "usr/lib/tool"), Path.of("/usr/../usr/bin/tool"))

        assertNull(auditor().auditAndNormalize(staging, WORKSPACE))

        val link = File(staging, "usr/lib/tool").toPath()
        assertEquals("../bin/tool", Files.readSymbolicLink(link).toString())
        assertTrue("the normalized link must resolve to the in-root file", File(staging, "usr/lib/tool").isFile)
    }

    @Test
    fun `the staging root itself is not a valid relative symlink target`() {
        val staging = staging()
        symlink(File(staging, "root-link"), Path.of("/"))

        val failure = auditor().auditAndNormalize(staging, WORKSPACE)

        assertNotNull("a link to the staging root must be rejected", failure)
        assertTrue("the failure must explain the empty relative target: $failure", failure!!.contains("staging root"))
    }

    private companion object {
        const val WORKSPACE = "test-workspace"
    }
}
