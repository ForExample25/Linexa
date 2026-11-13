package com.fem.linexa.service.installer

import com.fem.linexa.core.logging.DiagnosticLogManager
import com.fem.linexa.core.logging.Logger
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §1.168 — Pass 2.5 of the staged installer: makes PRoot's `--link2symlink`
 * hardlink emulation survive the staging → live rename.
 *
 * ## The defect this exists for
 *
 * App-private storage refuses `link(2)` outright (`ln` reports
 * `Permission denied` on `{app-files}`), which is why [RootfsExtractor] runs
 * `proot --link2symlink … tar`: PRoot intercepts the hardlink syscall and
 * emulates each hardlink family with
 *
 *   - one **data file** — `.l2s.<base><NNNN>.<NNNN>`, the real bytes;
 *   - one **sentinel link** — `.l2s.<base><NNNN>` → the data file;
 *   - one symlink per member of the family (including the original member
 *     name) → the sentinel link.
 *
 * Every emitted target is an **absolute host path**, and PRoot bakes in the
 * path it saw at extraction time — the staging directory:
 *
 * ```
 * usr/bin/perl -> {workspace}/.staging-rootfs/usr/bin/.l2s.perl5.36.00001
 * ```
 *
 * [com.fem.linexa.service.runtime.WorkspaceFileManager.activateStagedRootfs]
 * then promotes staging with a rename to `rootfs`, and every one of those
 * targets becomes a dangling link — verified on-device shape: after the
 * rename `perl`, `gunzip` and `uncompress` all fail to resolve on the host
 * *and* inside the guest (`proot error: '/usr/bin/perl' not found`).
 * Recreating the old staging directory makes them resolve again, which
 * isolates the rename rather than the extraction as the cause.
 *
 * Blast radius by bundled image (hardlink members in the archive):
 * Arch 1835, Fedora 1353, Debian 2 (`perl`, `gunzip`/`uncompress`),
 * Ubuntu 2, Alpine 0.
 *
 * ## The fix, and why it is a rewrite rather than a re-anchor
 *
 * Every rewritten target becomes **relative to the link's own directory**,
 * so it carries no host path at all and is therefore correct both before and
 * after the rename — the staging → live promotion stays a plain rename and
 * nothing has to be repaired afterwards. A verified control: the same
 * three-member family written with relative targets resolves unchanged
 * across the rename.
 *
 * The pass runs inside the staging tree, between extraction and
 * [StagedRootFsInstaller.validateExtractedRootfs], so a failure discards
 * staging exactly like an extraction failure does and the live rootfs is
 * never involved.
 *
 * ## What is treated as an artifact — and what is deliberately not
 *
 * A symlink is rewritten only when **all** of these hold:
 *
 *  1. it is a symbolic link (read with `NOFOLLOW`, never followed);
 *  2. its target is absolute — a relative target already survives a rename;
 *  3. the target's file name starts with [L2S_PREFIX] (`.l2s.`), PRoot's
 *     emulation prefix;
 *  4. the target lies inside the staging tree being installed.
 *
 * An ordinary absolute rootfs symlink (`/etc/os-release`,
 * `/usr/bin/python3 -> /usr/bin/python3.11`) satisfies none of (3) and (4)
 * and is left byte-identical: this pass never rewrites a symlink merely for
 * being absolute. The counter digits are matched only as "some suffix" and
 * never parsed — their width and the data file's `.NNNN` value are PRoot
 * implementation details (a three-member family was observed to use
 * `.0004`), and a predicate that decoded them would silently stop matching
 * on a PRoot upgrade.
 *
 * A symlink satisfying (1)-(3) whose target points **outside** staging is
 * not skipped — it is a failure. Silently ignoring it is precisely how a
 * broken rootfs would ship: the whole point of the pass is that no absolute
 * `.l2s.` target may survive into the live tree.
 *
 * ## Security posture
 *
 * The rewrite is **target-preserving**: the relative path written is derived to
 * resolve to the very same location the absolute path named, and that identity
 * is asserted per link before anything is written. So this pass cannot widen
 * what a rootfs can reach — an archive that shipped a hostile
 * artifact-shaped link gets a link with the same reachability it already had,
 * and §1.14's [com.fem.linexa.core.security.ArchiveValidator] (which ran over
 * every entry during Pass 1) remains the thing that decides whether such an
 * entry may exist at all.
 *
 * Nothing from the archive is used as a path to write: the only paths this class
 * opens are ones it reached by walking [staging] itself, and the only name it
 * creates is [TEMP_PREFIX] plus an existing file name in that file's own
 * directory. The walk never follows symbolic links, so a symlinked directory —
 * including one pointing outside the rootfs, and including a link cycle — is
 * visited as a link and never descended into.
 *
 * That last property is also what makes a `..` segment in a rewritten target
 * safe. The kernel resolves `..` after resolving the symlinks preceding it, so a
 * relative target could in principle land somewhere the absolute one did not —
 * but only if a directory component of the *link's own path* were a symlink, and
 * such a link is never visited at all, because the walk would not have descended
 * through it. Every directory between [staging] and a rewritten link is a real
 * directory.
 *
 * ## Fail-closed
 *
 * [rewrite] returns null only when every collected artifact was rewritten
 * *and* re-resolved afterwards. Anything else — a target outside staging, an
 * artifact that still does not resolve once relative (a family whose data file
 * never landed), a directory whose permissions cannot be widened or restored —
 * returns a message, and the caller discards staging. A partially-rewritten
 * tree is never promoted.
 *
 * ## Read-only parent directories
 *
 * A distribution may ship a directory without owner-write — Fedora's
 * `/usr/bin` is mode `0555` — and POSIX blocks both `symlink(2)` and the
 * replacing `rename(2)` in such a directory. Replacing a link cannot change
 * that directory's contents, so the pass temporarily adds `OWNER_WRITE` to the
 * one parent it needs to write in, performs the replacement, and restores the
 * exact original permission set in `finally`. If restoration fails the pass
 * fails: leaving a rootfs directory more writable than its archive declared
 * would silently change the installed system's security posture.
 */
@Singleton
class L2sLinkRewriter @Inject constructor(
    private val diagnosticLogManager: DiagnosticLogManager
) {

    /**
     * Rewrites every `--link2symlink` artifact under [staging] to a relative
     * target. Returns null on success, or a user-meaningful failure message.
     *
     * The caller owns [staging] exclusively at this point: `install()` cleared
     * and recreated it, and the extraction process has fully exited before
     * this runs — so no concurrent writer can be racing the walk.
     */
    fun rewrite(staging: File, workspaceId: String): String? {
        if (!staging.isDirectory) {
            val msg = "Cannot repair hardlink symlinks: staging is not a directory (${staging.absolutePath})"
            Logger.e(TAG, msg)
            return msg
        }

        val startedAt = System.currentTimeMillis()
        val walkRoot = staging.absoluteFile.toPath().normalize()

        // Both spellings of the staging path are accepted as "inside staging".
        // On Android the app's filesDir is reachable as /data/data/<pkg>/… and
        // as /data/user/0/<pkg>/…; PRoot writes whichever spelling it was
        // handed. Comparing against one only would misjudge an artifact as
        // out-of-tree and fail an install that is in fact repairable.
        val roots = LinkedHashSet<Path>()
        roots.add(walkRoot)
        try {
            roots.add(staging.canonicalFile.toPath().normalize())
        } catch (_: IOException) {
            // Unresolvable canonical path — the absolute spelling stands.
        }

        val collected = try {
            collectArtifacts(walkRoot)
        } catch (e: Exception) {
            val msg = "Could not scan the extracted rootfs for hardlink symlinks: ${e.message}"
            Logger.e(TAG, msg, e)
            return msg
        }

        if (collected.isEmpty()) {
            // Alpine's archive carries no hardlinks at all, so this is a
            // legitimate outcome and not a sign the scan missed something.
            Logger.i(TAG, "§1.168 no --link2symlink artifacts in $workspaceId staging — nothing to rewrite")
            return null
        }

        val plan = ArrayList<Rewrite>(collected.size)
        for (artifact in collected) {
            val targetRel = relativizeInsideStaging(artifact.target, roots)
                ?: return failure(
                    workspaceId,
                    "hardlink symlink '${walkRoot.relativize(artifact.link)}' points outside the extracted " +
                        "rootfs (${artifact.target}) — refusing to install a rootfs with unresolvable links"
                )

            val linkRel = walkRoot.relativize(artifact.link)
            val linkDirRel = linkRel.parent ?: EMPTY_PATH
            val relativeTarget = linkDirRel.relativize(targetRel)

            // Containment is already established by the prefix match above;
            // this asserts the arithmetic that derives the replacement, so a
            // relativize() surprise can never write an escaping target.
            val resolved = linkDirRel.resolve(relativeTarget).normalize()
            if (resolved != targetRel.normalize() || resolved.startsWith(PARENT_PATH)) {
                return failure(
                    workspaceId,
                    "refusing to rewrite hardlink symlink '$linkRel': computed target '$relativeTarget' " +
                        "does not resolve back to '$targetRel'"
                )
            }
            plan += Rewrite(artifact.link, linkRel, relativeTarget)
        }

        // Deterministic order: the plan is sorted by staging-relative path, so
        // two runs over the same tree perform the same writes in the same
        // sequence and a diagnostic log is comparable across runs.
        plan.sortBy { it.linkRel.toString() }

        for (item in plan) {
            val error = replaceSymlink(item)
            if (error != null) return failure(workspaceId, error)
        }

        // Verify pass — every rewritten link must now be relative AND resolve.
        // Run after the whole plan because a family is a chain (member →
        // sentinel → data file) and a member checked mid-plan could still be
        // pointing at a not-yet-rewritten sentinel.
        for (item in plan) {
            val verifyError = verify(item)
            if (verifyError != null) return failure(workspaceId, verifyError)
        }

        val elapsed = System.currentTimeMillis() - startedAt
        val summary = "§1.168 rewrote ${plan.size} --link2symlink artifact(s) in $workspaceId staging " +
            "to relative targets in ${elapsed}ms"
        Logger.i(TAG, summary)
        logEvent(workspaceId, summary)
        return null
    }

    /**
     * One walk of the staging tree collecting the artifacts to rewrite.
     *
     * Collection and mutation are separate phases on purpose: the walk must
     * not observe the temporary links [replaceSymlink] creates, and a plan
     * built before any write is what makes the pass deterministic.
     *
     * `Files.walkFileTree` does not follow symbolic links unless asked, so a
     * symlink — including one pointing at a directory, and including a
     * symlink cycle — arrives at `visitFile` as the link itself and is never
     * descended into.
     */
    private fun collectArtifacts(walkRoot: Path): List<Artifact> {
        val artifacts = ArrayList<Artifact>()
        Files.walkFileTree(
            walkRoot,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isSymbolicLink) return FileVisitResult.CONTINUE
                    val target = try {
                        Files.readSymbolicLink(file)
                    } catch (_: Exception) {
                        // Unreadable link — not something this pass can judge
                        // as an artifact, so it is left exactly as it is.
                        return FileVisitResult.CONTINUE
                    }
                    // A relative target already survives the rename; only the
                    // absolute ones PRoot bakes in are this pass's business.
                    if (!target.isAbsolute) return FileVisitResult.CONTINUE
                    val targetName = target.fileName?.toString() ?: return FileVisitResult.CONTINUE
                    if (!targetName.startsWith(L2S_PREFIX)) return FileVisitResult.CONTINUE
                    artifacts += Artifact(link = file, target = target.normalize())
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    // A file that cannot be stat'ed cannot be an artifact this
                    // pass must repair; extraction validation is what judges an
                    // incomplete tree.
                    return FileVisitResult.CONTINUE
                }
            }
        )
        return artifacts
    }

    /**
     * Returns [target] expressed relative to whichever accepted staging root
     * contains it, or null when it is not inside the staging tree at all.
     */
    private fun relativizeInsideStaging(target: Path, roots: Set<Path>): Path? {
        for (root in roots) {
            if (target.startsWith(root)) return root.relativize(target)
        }
        return null
    }

    /**
     * Replaces [Rewrite.link] with a symlink to [Rewrite.relativeTarget].
     *
     * The new link is created under a temporary name in the same directory and
     * then `ATOMIC_MOVE`d over the old one, so the path is never momentarily
     * absent: a delete-then-create would leave a window in which the rootfs on
     * disk has fewer entries than the archive it came from. A filesystem
     * without atomic move falls back to `REPLACE_EXISTING`, which is still a
     * single `rename` on every filesystem Android uses for app storage.
     *
     * If the parent directory lacks owner-write (Fedora's `/usr/bin` is
     * `0555`), it is temporarily made owner-writable and restored to its exact
     * original mode in `finally` — see the class kdoc.
     */
    private fun replaceSymlink(item: Rewrite): String? {
        val parent = item.link.parent
            ?: return "refusing to rewrite '${item.linkRel}': it has no parent directory"
        val temp = parent.resolve("$TEMP_PREFIX${item.link.fileName}")

        val originalPermissions = try {
            Files.getPosixFilePermissions(parent)
        } catch (_: UnsupportedOperationException) {
            null
        } catch (_: IOException) {
            null
        }

        var widened = false
        if (originalPermissions != null && PosixFilePermission.OWNER_WRITE !in originalPermissions) {
            val writable = originalPermissions + PosixFilePermission.OWNER_WRITE
            try {
                Files.setPosixFilePermissions(parent, writable)
                widened = true
            } catch (e: Exception) {
                Logger.e(TAG, "§1.168 could not make parent of '${item.linkRel}' writable", e)
                return "Could not repair hardlink symlink '${item.linkRel}': parent directory " +
                    "'${item.linkRel.parent}' is not writable: ${e.message}"
            }
        }

        var failure: String? = null
        try {
            Files.deleteIfExists(temp)
            Files.createSymbolicLink(temp, item.relativeTarget)
            try {
                Files.move(temp, item.link, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, item.link, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            try {
                Files.deleteIfExists(temp)
            } catch (_: Exception) {
                // The move failed; a leftover temp is discarded with staging.
            }
            Logger.e(TAG, "§1.168 could not rewrite '${item.linkRel}' -> '${item.relativeTarget}'", e)
            failure = "Could not repair hardlink symlink '${item.linkRel}': ${e.message}"
        } finally {
            if (widened) {
                try {
                    // Restore the archive's exact mode, not merely remove the
                    // bit this pass added: another mode bit cannot have
                    // changed legitimately while the pass owns staging.
                    Files.setPosixFilePermissions(parent, originalPermissions!!)
                } catch (e: Exception) {
                    Logger.e(TAG, "§1.168 could not restore permissions of '${item.linkRel.parent}'", e)
                    val restoreFailure = "Could not restore permissions of directory " +
                        "'${item.linkRel.parent}' after repairing hardlink symlink '${item.linkRel}': ${e.message}"
                    failure = failure?.let { "$it; $restoreFailure" } ?: restoreFailure
                }
            }
        }
        return failure
    }

    /**
     * Confirms one rewritten link is relative and resolves to something that
     * exists — the two properties that were false before this pass existed.
     */
    private fun verify(item: Rewrite): String? {
        val actual = try {
            Files.readSymbolicLink(item.link)
        } catch (e: Exception) {
            return "Repaired hardlink symlink '${item.linkRel}' is not readable back: ${e.message}"
        }
        if (actual.isAbsolute || actual != item.relativeTarget) {
            return "Repaired hardlink symlink '${item.linkRel}' has unexpected target '$actual' " +
                "(expected '${item.relativeTarget}')"
        }
        // Follows the link deliberately: resolution through the whole
        // member → sentinel → data file chain is the property under test.
        if (!Files.exists(item.link)) {
            return "Repaired hardlink symlink '${item.linkRel}' -> '${item.relativeTarget}' does not resolve " +
                "inside the extracted rootfs"
        }
        return null
    }

    /** Logs [reason] as the pass's verdict and returns it for the caller. */
    private fun failure(workspaceId: String, reason: String): String {
        Logger.e(TAG, "§1.168 $reason")
        logEvent(workspaceId, "§1.168 FAILED: $reason")
        return reason
    }

    /** Best-effort append to the workspace's `extraction.log` (§1.53). */
    private fun logEvent(workspaceId: String, content: String) {
        try {
            diagnosticLogManager.appendLog(workspaceId, LOG_FILE, "$content\n")
        } catch (_: Exception) {
            // Never fail an install for logging.
        }
    }

    /** A collected `--link2symlink` symlink and the absolute target it carries. */
    private class Artifact(val link: Path, val target: Path)

    /** One planned replacement: [link] must become a symlink to [relativeTarget]. */
    private class Rewrite(
        val link: Path,
        /** [link] relative to the staging root — the identity used in messages. */
        val linkRel: Path,
        val relativeTarget: Path
    )

    companion object {
        private const val TAG = "L2sLinkRewriter"

        /**
         * PRoot's `--link2symlink` name prefix for both the sentinel link and
         * the data file it points at (`link2symlink.c`). Matching the prefix
         * alone is deliberate — see the class kdoc on why the counters are not
         * parsed.
         */
        const val L2S_PREFIX = ".l2s."

        /**
         * Temporary name used for the atomic replacement. Distinct from
         * [L2S_PREFIX] so a temp file left behind by a killed process can never
         * be collected as an artifact by a later run.
         */
        private const val TEMP_PREFIX = ".l2s-rewrite."

        /** Per-workspace log the extraction phase already writes to (§1.53). */
        private const val LOG_FILE = "extraction.log"

        /** `Paths.get("")` — the relative path of a link sitting at the staging root. */
        private val EMPTY_PATH: Path = Paths.get("")

        /** Guards the derived target against escaping the staging tree. */
        private val PARENT_PATH: Path = Paths.get("..")
    }
}
