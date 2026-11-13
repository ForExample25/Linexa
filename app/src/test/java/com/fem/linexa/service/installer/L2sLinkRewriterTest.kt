package com.fem.linexa.service.installer

import android.app.Application
import com.fem.linexa.core.logging.DiagnosticLogManager
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.test.support.L2sExtraction
import com.fem.linexa.test.support.L2sExtraction.Member
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/**
 * §1.168 — regression tests for [L2sLinkRewriter], the pass that makes PRoot's
 * `--link2symlink` hardlink emulation survive the staging → live rename.
 *
 * ## The bug these lock down
 *
 * `--link2symlink` writes symlinks whose targets are **absolute staging paths**.
 * `WorkspaceFileManager.activateStagedRootfs` promotes staging with a rename, at
 * which point every one of those targets names a directory that no longer
 * exists: on a real device `usr/bin/perl`, `usr/bin/gunzip` and
 * `usr/bin/uncompress` all stopped resolving after activation, on the host and
 * inside the guest alike (`proot error: '/usr/bin/perl' not found`). Arch ships
 * 1835 hardlink members, Fedora 1353, Debian and Ubuntu 2 each.
 *
 * ## What is real here
 *
 * Real: the production [L2sLinkRewriter], the production
 * [WorkspaceFileManager.activateStagedRootfs] rename (so the test renames the
 * way the app does, not the way the test would like to), real files and real
 * symlinks on a real filesystem, and a real tar archive walked by
 * commons-compress.
 *
 * Emulated: the `proot --link2symlink tar` extraction itself
 * ([L2sExtraction]) — `src/test` has no aarch64 PRoot and no Android runtime.
 * The emulation reproduces the on-device artifact shape byte-for-byte in the
 * two properties that matter (absolute targets containing the staging path, and
 * the original member name demoted to a symlink), and
 * [L2sLinkRewriterRealProotTest] cross-checks it against a genuine PRoot run
 * wherever one exists.
 *
 * ## Lifecycle asserted
 *
 * `extract → rewrite → activate → verify` — the assertions are made *after* the
 * activation rename, because before it even the unrepaired tree resolves fine.
 * That ordering is the entire point: a test that verified inside staging would
 * have passed against the broken code.
 */
class L2sLinkRewriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var fileManager: WorkspaceFileManager
    private lateinit var rewriter: L2sLinkRewriter

    private val workspaceId = "l2s-ws"

    @Before
    fun setUp() {
        val filesDir = tmp.newFolder("files")
        val app = Mockito.mock(Application::class.java)
        Mockito.`when`(app.filesDir).thenReturn(filesDir)
        Mockito.`when`(app.cacheDir).thenReturn(tmp.newFolder("cache"))
        fileManager = WorkspaceFileManager(app)
        // The real log manager: the pass appends to the workspace's
        // extraction.log, and a mock would hide a crash in that call.
        rewriter = L2sLinkRewriter(DiagnosticLogManager(app))
    }

    private fun staging(): File = fileManager.getStagingRootfsPath(workspaceId).apply { mkdirs() }

    private fun live(): File = fileManager.getRootfsPath(workspaceId)

    /** Extracts [members] into a fresh staging tree the way Pass 2 does. */
    private fun extract(vararg members: Member): File {
        val staging = staging()
        L2sExtraction.extractWithLink2Symlink(L2sExtraction.archive(*members), staging)
        return staging
    }

    /** The staging-relative target of [relative], read without following it. */
    private fun linkTarget(root: File, relative: String): String =
        Files.readSymbolicLink(File(root, relative).toPath()).toString()

    private fun isSymlink(root: File, relative: String): Boolean =
        Files.isSymbolicLink(File(root, relative).toPath())

    // ── the regression: full lifecycle across the activation rename ───────

    /**
     * The headline case, in the order the installer runs it. Every assertion is
     * made against the **activated** rootfs, and the staging directory is gone
     * by then — so a target that still mentioned `.staging-rootfs` could not
     * resolve even by accident.
     */
    @Test
    fun aHardlinkSurvivesTheActivationRename() {
        val staging = extract(
            Member.Dir("./usr/"),
            Member.Dir("./usr/bin/"),
            Member.Regular("./usr/bin/perl5.36.0", "#!/bin/sh\necho perl\n"),
            Member.Hardlink("./usr/bin/perl", "./usr/bin/perl5.36.0")
        )
        val stagingPath = staging.absolutePath

        // Precondition: this is genuinely the broken shape, i.e. the test is
        // testing the thing. Without it a no-op rewriter would pass.
        assertTrue(
            "the emulated extraction must produce an absolute staging target",
            linkTarget(staging, "usr/bin/perl").startsWith(stagingPath)
        )

        assertNull("rewrite must succeed", rewriter.rewrite(staging, workspaceId))
        assertTrue("activation must succeed", fileManager.activateStagedRootfs(workspaceId))

        val rootfs = live()
        assertFalse("staging must be gone after activation", staging.exists())

        val perl = File(rootfs, "usr/bin/perl")
        assertTrue("the symlink must still exist after activation", isSymlink(rootfs, "usr/bin/perl"))
        assertTrue(
            "the target must resolve after the rename, but '${linkTarget(rootfs, "usr/bin/perl")}' did not",
            perl.exists()
        )
        assertFalse(
            "the target must not depend on the old .staging-rootfs path",
            linkTarget(rootfs, "usr/bin/perl").contains(".staging-rootfs")
        )
        assertFalse(
            "the target must not be absolute at all",
            Paths.get(linkTarget(rootfs, "usr/bin/perl")).isAbsolute
        )
        assertEquals(
            "the file's content must be reachable through the link chain",
            "#!/bin/sh\necho perl\n",
            perl.readText()
        )
        assertTrue("the target must be executable from the activated rootfs", perl.canExecute())
    }

    /**
     * Debian's actual shape: `gunzip` and `uncompress` are two names for one
     * inode, so both members point at one sentinel and one data file. Both must
     * resolve, and both must resolve to the *same* file — a rewrite that fixed
     * the links but broke the sharing would still be wrong.
     */
    @Test
    fun multipleHardlinkMembersOfOneFamilyAllResolveToTheSameFile() {
        val staging = extract(
            Member.Dir("./usr/"),
            Member.Dir("./usr/bin/"),
            Member.Regular("./usr/bin/gunzip", "gunzip-payload"),
            Member.Hardlink("./usr/bin/uncompress", "./usr/bin/gunzip"),
            Member.Hardlink("./usr/bin/zcat", "./usr/bin/gunzip")
        )

        assertNull(rewriter.rewrite(staging, workspaceId))
        assertTrue(fileManager.activateStagedRootfs(workspaceId))

        val rootfs = live()
        val members = listOf("usr/bin/gunzip", "usr/bin/uncompress", "usr/bin/zcat")
        for (member in members) {
            assertTrue("$member must still be a symlink", isSymlink(rootfs, member))
            assertTrue("$member must resolve after activation", File(rootfs, member).exists())
            assertFalse(
                "$member must not carry an absolute target",
                Paths.get(linkTarget(rootfs, member)).isAbsolute
            )
            assertEquals("$member must expose the shared payload", "gunzip-payload", File(rootfs, member).readText())
        }
        // One inode behind all three names: the sharing --link2symlink emulates
        // is the reason the pass may not simply copy the data per member.
        val canonicals = members.map { File(rootfs, it).canonicalPath }.toSet()
        assertEquals("all members must resolve to one file, got $canonicals", 1, canonicals.size)
    }

    /**
     * A hardlink member in a different directory from its data file. The
     * rewritten target must climb out with `..` and still resolve — the case
     * where a naive "same directory" rewrite would produce a wrong path.
     */
    @Test
    fun aHardlinkMemberInAnotherDirectoryResolvesThroughAParentSegment() {
        val staging = extract(
            Member.Dir("./usr/"),
            Member.Dir("./usr/bin/"),
            Member.Dir("./usr/sbin/"),
            Member.Regular("./usr/bin/busybox", "busybox-payload"),
            Member.Hardlink("./usr/sbin/ifconfig", "./usr/bin/busybox")
        )

        assertNull(rewriter.rewrite(staging, workspaceId))
        assertTrue(fileManager.activateStagedRootfs(workspaceId))

        val rootfs = live()
        val target = linkTarget(rootfs, "usr/sbin/ifconfig")
        assertTrue("a cross-directory member must climb with '..', got '$target'", target.startsWith(".."))
        assertTrue("it must still resolve after activation", File(rootfs, "usr/sbin/ifconfig").exists())
        assertEquals("busybox-payload", File(rootfs, "usr/sbin/ifconfig").readText())
    }

    /**
     * A hardlink family at the very top of the rootfs. The link's parent is the
     * staging root itself, which is the one case where the relative path is
     * computed against an empty path rather than a directory — worth pinning
     * because a `null` parent there would be an NPE, not a wrong target.
     */
    @Test
    fun aHardlinkAtTheRootOfTheRootfsIsRewrittenToo() {
        val staging = extract(
            Member.Regular("./init", "#!/bin/sh\n"),
            Member.Hardlink("./sbin-init", "./init")
        )

        assertNull(rewriter.rewrite(staging, workspaceId))
        assertTrue(fileManager.activateStagedRootfs(workspaceId))

        val rootfs = live()
        for (member in listOf("init", "sbin-init")) {
            val target = linkTarget(rootfs, member)
            assertFalse("$member must not be absolute, got '$target'", Paths.get(target).isAbsolute)
            assertFalse("$member must not contain a path separator, got '$target'", target.contains("/"))
            assertTrue("$member must resolve after activation", File(rootfs, member).exists())
        }
    }

    // ── unrelated symlinks must come out byte-identical ───────────────────

    /**
     * Requirement 9/10: an ordinary absolute symlink is **not** an artifact and
     * must survive untouched even though it is absolute — the pass must key on
     * the `.l2s.` emulation prefix, never on absoluteness.
     *
     * `/etc/os-release` and `bin -> usr/bin` are both shapes the bundled images
     * really ship (Arch's `etc/os-release` symlink is what made it detectable at
     * all — see [StagedRootFsInstallerScanTest]).
     */
    @Test
    fun ordinarySymlinksIncludingAbsoluteOnesAreLeftExactlyAsTheyAre() {
        val staging = extract(
            Member.Dir("./usr/"),
            Member.Dir("./usr/bin/"),
            Member.Dir("./usr/lib/"),
            Member.Dir("./etc/"),
            Member.Regular("./usr/lib/os-release", "ID=archarm\n"),
            Member.Regular("./usr/bin/python3.11", "py"),
            // Relative, and relative upward — the merged-usr shape.
            Member.Symlink("./etc/os-release", "../usr/lib/os-release"),
            Member.Symlink("./bin", "usr/bin"),
            // Absolute, and a perfectly ordinary rootfs link.
            Member.Symlink("./usr/bin/python3", "/usr/bin/python3.11"),
            // Absolute and DANGLING on the host: /proc is bind-mounted only
            // inside a session, so this link is unresolvable at install time and
            // must still be left alone rather than "repaired".
            Member.Symlink("./etc/mtab", "/proc/self/mounts"),
            // A real hardlink in the same tree, so the pass has work to do and
            // the untouched-ness below is not the trivial "nothing ran" case.
            Member.Regular("./usr/bin/perl5.36.0", "perl"),
            Member.Hardlink("./usr/bin/perl", "./usr/bin/perl5.36.0")
        )

        val before = snapshotSymlinks(staging)
        assertNull(rewriter.rewrite(staging, workspaceId))
        val after = snapshotSymlinks(staging)

        for (unrelated in listOf("etc/os-release", "bin", "usr/bin/python3", "etc/mtab")) {
            assertEquals(
                "$unrelated must be byte-identical after the pass",
                before[unrelated],
                after[unrelated]
            )
        }
        assertEquals(
            "an absolute non-artifact target must stay absolute",
            "/usr/bin/python3.11",
            after["usr/bin/python3"]
        )
        assertEquals(
            "a dangling non-artifact target must stay as it is",
            "/proc/self/mounts",
            after["etc/mtab"]
        )
        // …and the artifact really was rewritten, so the above is meaningful.
        assertFalse(
            "the artifact must have changed",
            before["usr/bin/perl"] == after["usr/bin/perl"]
        )
    }

    /** Alpine ships zero hardlinks: the pass must be a clean no-op, not a failure. */
    @Test
    fun anArchiveWithoutHardlinksIsACleanNoOp() {
        val staging = extract(
            Member.Dir("./etc/"),
            Member.Dir("./bin/"),
            Member.Regular("./bin/busybox", "busybox"),
            Member.Symlink("./bin/sh", "busybox"),
            Member.Regular("./etc/os-release", "ID=alpine\n")
        )

        val before = snapshotSymlinks(staging)
        assertNull("no hardlinks is a success, not a failure", rewriter.rewrite(staging, workspaceId))
        assertEquals("nothing may be touched", before, snapshotSymlinks(staging))

        assertTrue(fileManager.activateStagedRootfs(workspaceId))
        assertTrue(File(live(), "bin/sh").exists())
    }

    /**
     * A symlinked directory in the tree (`lib64 -> lib`, which Debian's
     * merged-usr layout really ships) must not change the outcome: the walk
     * never descends through it, so the directories between staging and any
     * rewritten link are all real — which is what makes a `..` segment in a
     * rewritten target resolve to the same place the absolute target named.
     */
    @Test
    fun aSymlinkedDirectoryDoesNotAffectRewrittenTargets() {
        val staging = extract(
            Member.Dir("./usr/"),
            Member.Dir("./usr/lib/"),
            Member.Dir("./usr/bin/"),
            Member.Regular("./usr/lib/libc.so", "libc"),
            Member.Hardlink("./usr/bin/libc-alias", "./usr/lib/libc.so"),
            // The merged-usr shape: a directory reachable under two names.
            Member.Symlink("./usr/lib64", "lib")
        )

        assertNull(rewriter.rewrite(staging, workspaceId))
        assertTrue(fileManager.activateStagedRootfs(workspaceId))

        val rootfs = live()
        assertEquals(
            "the symlinked directory must be untouched",
            "lib",
            linkTarget(rootfs, "usr/lib64")
        )
        assertTrue("the rewritten member must resolve", File(rootfs, "usr/bin/libc-alias").exists())
        assertEquals("libc", File(rootfs, "usr/bin/libc-alias").readText())
        // The link resolves to the real file, not to a path laundered through
        // the directory symlink.
        assertEquals(
            File(rootfs, "usr/lib/libc.so").canonicalPath,
            File(rootfs, "usr/bin/libc-alias").canonicalPath
        )
    }

    // ── scale ─────────────────────────────────────────────────────────────

    /**
     * Requirement 8: Arch's image carries 1835 hardlink members and Fedora's
     * 1353, so the pass is exercised at that order of magnitude — 2000 members
     * spread over 20 directories, i.e. ~4000 symlinks to walk and 4000 to
     * rewrite (each family contributes its member links plus a sentinel).
     *
     * The assertion is correctness at scale, not a wall-clock budget: a timing
     * threshold on a shared CI machine is a flaky test, and the property that
     * actually matters is that no member is missed. The walk is a single
     * `walkFileTree` and the rewrite is one `symlink`+`rename` per artifact, so
     * the cost is linear in artifacts by construction.
     */
    @Test
    fun aFedoraSizedHardlinkCountIsRewrittenCompletely() {
        val members = ArrayList<Member>()
        members += Member.Dir("./usr/")
        val familyCount = 20
        val membersPerFamily = 100
        for (family in 0 until familyCount) {
            members += Member.Dir("./usr/d$family/")
            members += Member.Regular("./usr/d$family/base", "payload-$family")
            for (index in 0 until membersPerFamily) {
                members += Member.Hardlink("./usr/d$family/link$index", "./usr/d$family/base")
            }
        }

        val staging = extract(*members.toTypedArray())
        val startedAt = System.nanoTime()
        assertNull(rewriter.rewrite(staging, workspaceId))
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue(fileManager.activateStagedRootfs(workspaceId))

        val rootfs = live()
        val unresolved = ArrayList<String>()
        val absolute = ArrayList<String>()
        for (family in 0 until familyCount) {
            for (index in 0 until membersPerFamily) {
                val relative = "usr/d$family/link$index"
                if (Paths.get(linkTarget(rootfs, relative)).isAbsolute) absolute += relative
                if (!File(rootfs, relative).exists()) unresolved += relative
            }
        }

        assertTrue("no member may keep an absolute target: ${absolute.take(5)}", absolute.isEmpty())
        assertTrue("every member must resolve: ${unresolved.take(5)}", unresolved.isEmpty())
        assertEquals(
            "payload must be reachable through a rewritten member",
            "payload-7",
            File(rootfs, "usr/d7/link42").readText()
        )
        // Informational: on the development device this runs in tens of
        // milliseconds for ~4000 artifacts. Not asserted — see the kdoc.
        println("§1.168 rewrote ${familyCount * (membersPerFamily + 2)} artifacts in ${elapsedMs}ms")
    }

    // ── read-only parent directories (Fedora shape) ──────────────────────

    /**
     * A rootfs may ship a directory without owner-write — Fedora 44's
     * `/usr/bin` is mode `0555` — and POSIX blocks both `symlink(2)` and the
     * replacing `rename(2)` there. The pass must widen the one parent
     * temporarily, finish the rewrite, and restore the archive's exact mode.
     *
     * Skipped when the JVM ignores directory permissions (root), because then
     * the fixture cannot reproduce the condition the device hit.
     */
    @Test
    fun aReadOnlyDirectoryIsWidenedForTheRewriteAndRestored() {
        val staging = extract(
            Member.Dir("./usr/"),
            Member.Dir("./usr/bin/"),
            Member.Regular("./usr/bin/perl5.36.0", "perl"),
            Member.Hardlink("./usr/bin/perl", "./usr/bin/perl5.36.0")
        )
        val dir = File(staging, "usr/bin")
        val original = Files.getPosixFilePermissions(dir.toPath())
        try {
            val readOnly = original - PosixFilePermission.OWNER_WRITE
            Files.setPosixFilePermissions(dir.toPath(), readOnly)
            org.junit.Assume.assumeFalse(
                "running as a user that ignores directory permissions",
                canCreateEntryIn(dir)
            )
            assertEquals(
                "fixture must start from the archive's read-only mode",
                readOnly,
                Files.getPosixFilePermissions(dir.toPath())
            )

            assertNull(
                "a read-only parent directory must not fail the pass",
                rewriter.rewrite(staging, workspaceId)
            )
            assertEquals(
                "the parent must be restored to its exact archive mode",
                readOnly,
                Files.getPosixFilePermissions(dir.toPath())
            )

            assertTrue("activation must succeed", fileManager.activateStagedRootfs(workspaceId))
            val rootfs = live()
            assertEquals("the rewritten link must resolve", "perl", File(rootfs, "usr/bin/perl").readText())
            assertEquals(
                "the activated directory must keep the archive's mode",
                readOnly,
                Files.getPosixFilePermissions(File(rootfs, "usr/bin").toPath())
            )
        } finally {
            if (dir.exists()) Files.setPosixFilePermissions(dir.toPath(), original)
        }
    }

    /**
     * The exact Fedora 44 failure: `/usr/bin` is `0555` and `pigz`/`unpigz`
     * are a hardlink family PRoot left as
     * `pigz -> .l2s.pigz0001 -> .l2s.pigz0001.0002`. On device the rewrite
     * failed with `AccessDeniedException` on
     * `usr/bin/.l2s-rewrite..l2s.pigz0001` because the directory refused the
     * temporary symlink.
     */
    @Test
    fun aFedoraShapedReadOnlyUsrBinIsRepaired() {
        val staging = extract(
            Member.Dir("./usr/"),
            Member.Dir("./usr/bin/"),
            Member.Regular("./usr/bin/pigz", "pigz-binary"),
            Member.Hardlink("./usr/bin/unpigz", "./usr/bin/pigz")
        )
        val dir = File(staging, "usr/bin")
        val original = Files.getPosixFilePermissions(dir.toPath())
        try {
            val fedoraMode = original - PosixFilePermission.OWNER_WRITE
            Files.setPosixFilePermissions(dir.toPath(), fedoraMode)
            org.junit.Assume.assumeFalse(
                "running as a user that ignores directory permissions",
                canCreateEntryIn(dir)
            )
            // Precondition: genuinely the on-device artifact shape.
            assertTrue(
                "the emulated extraction must leave an absolute target",
                linkTarget(staging, "usr/bin/pigz").startsWith(staging.absolutePath)
            )
            assertTrue(isSymlink(staging, "usr/bin/.l2s.pigz0001"))

            assertNull(
                "Fedora's read-only usr/bin must be repairable",
                rewriter.rewrite(staging, workspaceId)
            )
            assertEquals(
                "the parent must be restored to its exact archive mode",
                fedoraMode,
                Files.getPosixFilePermissions(dir.toPath())
            )
            assertTrue(
                "no rewrite temporaries may remain: ${leftoverTemps(staging)}",
                leftoverTemps(staging).isEmpty()
            )

            assertTrue("activation must succeed", fileManager.activateStagedRootfs(workspaceId))
            val rootfs = live()
            assertEquals("pigz must resolve", "pigz-binary", File(rootfs, "usr/bin/pigz").readText())
            assertEquals("unpigz must resolve", "pigz-binary", File(rootfs, "usr/bin/unpigz").readText())
            assertEquals(
                "the activated directory must keep Fedora's mode",
                fedoraMode,
                Files.getPosixFilePermissions(File(rootfs, "usr/bin").toPath())
            )
        } finally {
            if (dir.exists()) Files.setPosixFilePermissions(dir.toPath(), original)
        }
    }

    /**
     * An artifact-shaped link whose target lies **outside** staging cannot be
     * expressed relative to the tree, and must fail rather than be skipped: the
     * invariant the pass sells is that no absolute `.l2s.` target survives into
     * the live rootfs.
     */
    @Test
    fun anArtifactPointingOutsideStagingFailsThePass() {
        val staging = staging()
        val bin = File(staging, "usr/bin").apply { mkdirs() }
        // The realistic shape: a target left over from a *different* staging
        // directory — an artifact by name, but not one this tree can express
        // relatively.
        val otherTree = tmp.newFolder("other-staging", "usr", "bin")
        val outside = File(otherTree, "${L2sExtraction.L2S_PREFIX}stray0001").apply { writeText("stray") }
        Files.createSymbolicLink(
            File(bin, "stray").toPath(),
            outside.absoluteFile.toPath()
        )

        val failure = rewriter.rewrite(staging, workspaceId)

        assertNotNull("an out-of-tree artifact must fail the pass", failure)
        assertTrue(
            "the message must explain the link points outside the rootfs, got: $failure",
            failure!!.contains("points outside")
        )
        assertTrue("the offending link must be named, got: $failure", failure.contains("stray"))
    }

    /**
     * A family whose data file never landed (a truncated extraction) leaves the
     * sentinel dangling. The rewrite itself succeeds — the target is inside
     * staging and expressible — so it is the **verify** phase that must catch it,
     * which is exactly the "silently partially-invalid rootfs" case requirement
     * 11 forbids.
     */
    @Test
    fun anArtifactChainThatCannotResolveFailsThePassAtVerification() {
        val staging = extract(
            Member.Dir("./usr/"),
            Member.Dir("./usr/bin/"),
            Member.Regular("./usr/bin/perl5.36.0", "perl"),
            Member.Hardlink("./usr/bin/perl", "./usr/bin/perl5.36.0")
        )
        // Delete the data file the sentinel points at, keeping the links.
        val dataFile = File(staging, "usr/bin").listFiles()!!
            .single { it.name.startsWith(L2sExtraction.L2S_PREFIX) && !Files.isSymbolicLink(it.toPath()) }
        assertTrue("fixture must remove the data file", dataFile.delete())

        val failure = rewriter.rewrite(staging, workspaceId)

        assertNotNull("an unresolvable chain must fail the pass", failure)
        assertTrue(
            "the message must say the link does not resolve, got: $failure",
            failure!!.contains("does not resolve")
        )
    }

    /** Staging that is not a directory at all is a failure, never a silent pass. */
    @Test
    fun missingStagingFailsThePass() {
        val absent = File(tmp.root, "no-such-staging")
        val failure = rewriter.rewrite(absent, workspaceId)
        assertNotNull("a missing staging tree must fail", failure)
        assertTrue(failure!!.contains("not a directory"))
    }

    // ── determinism ───────────────────────────────────────────────────────

    /**
     * Requirement 6: the pass is deterministic and idempotent. A second run over
     * the already-rewritten tree must find nothing to do (the targets are
     * relative now, so they are no longer artifacts) and must not perturb the
     * tree — which is also what makes a retried install safe.
     */
    @Test
    fun aSecondRunIsAnIdempotentNoOp() {
        val staging = extract(
            Member.Dir("./usr/"),
            Member.Dir("./usr/bin/"),
            Member.Dir("./usr/sbin/"),
            Member.Regular("./usr/bin/busybox", "bb"),
            Member.Hardlink("./usr/bin/sh", "./usr/bin/busybox"),
            Member.Hardlink("./usr/sbin/init", "./usr/bin/busybox")
        )

        assertNull(rewriter.rewrite(staging, workspaceId))
        val afterFirst = snapshotSymlinks(staging)
        assertNull(rewriter.rewrite(staging, workspaceId))

        assertEquals("a second pass must change nothing", afterFirst, snapshotSymlinks(staging))
        // No temporary file may be left behind by either run.
        assertTrue(
            "no rewrite temporaries may remain: ${leftoverTemps(staging)}",
            leftoverTemps(staging).isEmpty()
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** Every symlink under [root], as `staging-relative path` → `raw target`. */
    private fun snapshotSymlinks(root: File): Map<String, String> {
        val rootPath = root.toPath()
        return root.walkTopDown()
            .onEnter { dir -> !Files.isSymbolicLink(dir.toPath()) }
            .filter { Files.isSymbolicLink(it.toPath()) }
            .associate {
                rootPath.relativize(it.toPath()).toString() to Files.readSymbolicLink(it.toPath()).toString()
            }
    }

    /** Any `.l2s-rewrite.` temporary the pass failed to clean up. */
    private fun leftoverTemps(root: File): List<String> =
        root.walkTopDown()
            .onEnter { dir -> !Files.isSymbolicLink(dir.toPath()) }
            .filter { it.name.startsWith(".l2s-rewrite.") }
            .map { it.name }
            .toList()

    /**
     * Whether this JVM can still create an entry inside [dir] despite its mode —
     * true when running as root, which makes the unwritable-directory test
     * vacuous rather than failing.
     */
    private fun canCreateEntryIn(dir: File): Boolean {
        val probe = File(dir, ".write-probe")
        return try {
            Files.createSymbolicLink(probe.toPath(), Paths.get("probe-target"))
            Files.deleteIfExists(probe.toPath())
            true
        } catch (_: Exception) {
            false
        }
    }
}
