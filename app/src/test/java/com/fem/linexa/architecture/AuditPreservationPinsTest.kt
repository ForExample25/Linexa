package com.fem.linexa.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-6 source-level pins for the audit findings that are deliberately
 * LEFT UNCHANGED (audit LINEXA_TERMINAL_RUNTIME_SOURCE_AUDIT.md §7-§11, §16,
 * §18, §20): external Android/SELinux platform limits and intentional
 * architecture the remediation must not regress.
 *
 * Each test asserts the relevant source keeps the audited shape, so a future
 * change that quietly alters one of these documented positions fails here
 * and forces the decision to be re-made explicitly.
 */
class AuditPreservationPinsTest {

    private fun repoFile(relative: String): File {
        val f = File("src/main/java/com/fem/linexa/$relative")
        assertTrue("expected source file $relative to exist", f.exists())
        return f
    }

    @Test
    fun realPtyArchitectureIsPreserved() {
        // F-§6: the interactive terminal is a real PTY through the terminal
        // backend's native create_subprocess (since the 2026-08-29 migration:
        // the vendored Apache-2.0 Termux module's JNI, libtermux.so); the
        // one-shot exec bridge is pipes BY DESIGN (§3.2). The remediation must
        // not merge them.
        val pty = repoFile("service/runtime/pty/TermuxPtySession.kt").readText()
        assertTrue(
            "the pty session must still delegate to the terminal backend (real /dev/ptmx fork)",
            pty.contains("TerminalSession")
        )
        val runtime = repoFile("service/runtime/container/PRootContainerRuntime.kt").readText()
        assertTrue(
            "the one-shot bridge must stay the fixed /bin/sh -c bridge (no TTY by design, F-7 scope)",
            runtime.contains("listOf(\"/bin/sh\", \"-c\", effectiveCommand)")
        )
    }

    @Test
    fun procAndSysBindsArePreserved() {
        // F-3/F-4/F-9: /proc and /sys are bound from the host and their
        // SELinux restrictions (proc_stat labels, hidepid=invisible,
        // sysfs denials) are platform limits the app cannot lift. Removing
        // the binds would only hide the trees.
        //
        // R-6 (2026-08-26 runtime parity audit) OVERRIDES the pre-existing
        // "no synthesized /proc/stat shim" pin (formerly §19.4 of the
        // terminal runtime audit, evidence-missing at the time). The new
        // evidence is the Linexa Debian full audit (2026-08-25): 21+ /proc
        // files including /proc/stat return EACCES inside the guest; the
        // reference Tiny Container implementation overlays static snapshot
        // files for /proc/{stat,uptime,loadavg,version,vmstat} and its
        // audits show monitoring tools (btop, top, vmstat) start cleanly
        // with the static (frozen) values rather than aborting on EACCES.
        // That is now the product decision, with evidence — see
        // LINEXA_RUNTIME_IMPROVEMENT_REPORT.md §3 (R-6).
        val launcher = repoFile("service/runtime/container/PRootLauncher.kt").readText()
        assertTrue("the /proc bind must stay", launcher.contains("add(\"-b\"); add(\"/proc\")"))
        assertTrue("the /sys bind must stay", launcher.contains("add(\"-b\"); add(\"/sys\")"))
        // R-6: the /proc bind-overs are now present (one per snapshot file)
        // and are routed through the procBindOvers() helper, not hard-coded
        // add() calls. The presence is asserted by PRootLauncherProcBindOversTest.
        assertTrue(
            "the procBindOvers helper must be present (R-6)",
            launcher.contains("procBindOvers")
        )
        assertTrue(
            "the PROC_BIND_OVER_NAMES companion constant must be present (R-6)",
            launcher.contains("PROC_BIND_OVER_NAMES")
        )
    }

    @Test
    fun devBindAndKillOnExitArePreserved() {
        // §9/§12: whole-/-dev host bind (guarantees /dev/null, /dev/urandom,
        // /dev/ptmx) and --kill-on-exit (orphan prevention) are intentional.
        val launcher = repoFile("service/runtime/container/PRootLauncher.kt").readText()
        assertTrue("the /dev bind must stay", launcher.contains("add(\"-b\"); add(\"/dev\")"))
        assertTrue("kill-on-exit must stay", launcher.contains("add(\"--kill-on-exit\")"))
    }

    @Test
    fun fakeRootModelIsPreserved() {
        // §11 + issue #1 (2026-09-03): identity emulation is what makes
        // dpkg/apt usable under the Android app privilege model. The -0 flag
        // moved into [GuestIdentity.prootIdentityArgs] — root still gets -0,
        // and the normal user gets --change-id=uid:gid. Both paths must stay.
        val identity = repoFile("service/runtime/container/GuestIdentity.kt").readText()
        assertTrue(
            "the root identity must keep -0 (privileged operations, one-shot execs)",
            identity.contains("listOf(\"-0\")")
        )
        assertTrue(
            "the normal-user identity must carry --change-id",
            identity.contains("--change-id=")
        )
        val launcher = repoFile("service/runtime/container/PRootLauncher.kt").readText()
        assertTrue(
            "the launcher must apply the identity args",
            launcher.contains("addAll(identity.prootIdentityArgs)")
        )
    }

    @Test
    fun link2symlinkStaysEnabledEverywhere() {
        // §5: --link2symlink is PRoot's hardlink emulation, required because
        // app storage refuses link(2). F-1 was fixed INSIDE proot (patch
        // 0002), not by disabling the emulation.
        val launcher = repoFile("service/runtime/container/PRootLauncher.kt").readText()
        assertTrue(
            "session launches must keep --link2symlink",
            launcher.contains("add(\"--link2symlink\")")
        )
        val extractor = repoFile("service/installer/RootfsExtractor.kt").readText()
        assertTrue(
            "extraction must keep --link2symlink (families are born here)",
            extractor.contains("--link2symlink")
        )
        // …and the extractor must NOT set the F-8 guest-env filter (its
        // bionic tar tracee needs the host LD_LIBRARY_PATH).
        assertTrue(
            "the extraction env must not scrub LD_LIBRARY_PATH (bionic tar linkage)",
            !extractor.contains("PROOT_GUEST_ENV_FILTER")
        )
    }

    @Test
    fun l2sRewriterStillEmitsRelativeTargets() {
        // §1.168: the rewriter's relative targets are CORRECT with the patched
        // proot (patch 0002 resolves them against the link's own directory);
        // they fix the staging→live rename and are now also safe at runtime.
        val rewriter = repoFile("service/installer/L2sLinkRewriter.kt").readText()
        assertTrue(
            "the rewriter must still produce link-directory-relative targets",
            rewriter.contains("linkDirRel.relativize(targetRel)")
        )
    }

    @Test
    fun archiveValidationGuaranteesArePreserved() {
        // §23: Pass 1's ArchiveValidator (path traversal / zip-slip / bomb
        // cap) is a security boundary the remediation did not weaken.
        val validator = File("src/main/java/com/fem/linexa/core/security/ArchiveValidator.kt")
        assertTrue("ArchiveValidator must exist", validator.exists())
        val installer = repoFile("service/installer/StagedRootFsInstaller.kt").readText()
        assertTrue(
            "the scan must still run ArchiveValidator.checkEntry per entry",
            installer.contains("ArchiveValidator")
        )
    }

    @Test
    fun vendoredPatchesArePresentAndDocumented() {
        // The proot patch set is the F-1/F-2/F-8 remediation vehicle; each
        // patch must exist with its regression test.
        val patches = listOf(
            "0002-l2s-relative-target-resolution.patch",
            "0003-ashmem-memfd-string-include.patch",
            "0004-guest-env-filter.patch"
        )
        for (name in patches) {
            val f = File("../third_party/proot/$name")
            assertTrue("patch $name must be vendored", f.exists())
            assertTrue("patch $name must document its rationale", f.readText().contains("Linexa"))
        }
        assertTrue(
            "the L2S regression test must be vendored",
            File("../third_party/proot/test-l2s-relative-targets.c").exists()
        )
        // 0001 is already built into the termux v5.1.107.89 tag; it stays as
        // the documented provenance record.
        assertTrue(
            "the readlinkat patch record must stay",
            File("../third_party/proot/0001-readlinkat-empty-pathname-fix.patch").exists()
        )
    }
}
