package com.fem.linexa.service.installer

import com.fem.linexa.test.support.L2sExtraction
import com.fem.linexa.test.support.L2sExtraction.Member
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * §1.168 — keeps [L2sExtraction] honest: the artifact shape the unit tests are
 * written against must be the shape a real `proot --link2symlink tar` produces.
 *
 * [L2sLinkRewriterTest] necessarily emulates the extraction, because `src/test`
 * has neither an aarch64 `libproot.so` nor an Android runtime. That emulation is
 * a claim about production behaviour, and an unchecked claim is how a test suite
 * ends up passing against a shape that no longer exists — the §1.19 defect class
 * this project keeps running into.
 *
 * So this test runs the real thing whenever the host happens to have `proot` and
 * `tar` on `PATH` (a Termux device does; a CI container usually does not) and
 * asserts that the emulation and the real extraction agree on the two properties
 * the rewriter depends on:
 *
 *  1. every hardlink member becomes a symlink whose target is **absolute** and
 *     inside the staging directory;
 *  2. the target's file name starts with `.l2s.` — the only predicate the
 *     production rewriter keys on.
 *
 * It deliberately does **not** assert the counter digits: their width and value
 * are PRoot internals (a two-name family was observed to use `.0002`, a
 * four-name one `.0004`), and pinning them would make a PRoot upgrade fail this
 * test for no user-visible reason.
 *
 * A run without `proot` on `PATH` ends in an assumption failure and is reported
 * as **skipped**, never as passed-by-vacuity.
 */
class L2sLinkRewriterRealProotTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** First executable named [name] on `PATH`, or null. */
    private fun onPath(name: String): File? =
        System.getenv("PATH")?.split(File.pathSeparator)?.asSequence()
            ?.map { File(it, name) }
            ?.firstOrNull { it.isFile && it.canExecute() }

    @Test
    fun aRealProotExtractionProducesTheShapeTheEmulationClaims() {
        val proot = onPath("proot")
        val tar = onPath("tar")
        // assumeTrue, not assumeNotNull: the latter takes `Object...`, so the
        // message would be treated as one more value to null-check.
        assumeTrue("no proot on PATH — real-PRoot cross-check skipped", proot != null)
        assumeTrue("no tar on PATH — real-PRoot cross-check skipped", tar != null)

        val members = arrayOf(
            Member.Dir("./usr/"),
            Member.Dir("./usr/bin/"),
            Member.Regular("./usr/bin/gunzip", "gunzip-payload"),
            Member.Hardlink("./usr/bin/uncompress", "./usr/bin/gunzip"),
            Member.Hardlink("./usr/bin/zcat", "./usr/bin/gunzip")
        )
        val archiveBytes = L2sExtraction.archive(*members)
        val archive = File(tmp.root, "hardlinks.tar").apply { writeBytes(archiveBytes) }

        val realStaging = tmp.newFolder("real-staging")
        val exit = run(
            listOf(
                proot!!.absolutePath,
                "--link2symlink",
                tar!!.absolutePath,
                "-xf", archive.absolutePath,
                "-C", realStaging.absolutePath,
                "--delay-directory-restore",
                "--preserve-permissions"
            )
        )
        // A proot that cannot run in this environment (no ptrace in a container,
        // seccomp refusal) is not a failure of the code under test.
        assumeTrue("proot could not run here (exit $exit) — cross-check skipped", exit == 0)

        val emulatedStaging = tmp.newFolder("emulated-staging")
        L2sExtraction.extractWithLink2Symlink(archiveBytes, emulatedStaging)

        val realShape = shape(realStaging)
        val emulatedShape = shape(emulatedStaging)

        assertEquals(
            "the emulation must reproduce the real member set",
            emulatedShape.keys,
            realShape.keys
        )
        for ((member, real) in realShape) {
            val emulated = emulatedShape.getValue(member)
            assertEquals("$member: absoluteness must match", emulated.absolute, real.absolute)
            assertEquals("$member: inside-staging must match", emulated.insideStaging, real.insideStaging)
            assertEquals("$member: '.l2s.' target prefix must match", emulated.l2sTarget, real.l2sTarget)
        }

        // And the production predicate must actually fire on the real output.
        assertTrue(
            "the real extraction must produce artifacts the rewriter recognises",
            realShape.values.any { it.absolute && it.insideStaging && it.l2sTarget }
        )
    }

    /** The three properties the rewriter's predicate is built from, per member. */
    private data class LinkShape(
        val absolute: Boolean,
        val insideStaging: Boolean,
        val l2sTarget: Boolean
    )

    /**
     * Describes the members `usr/bin/{gunzip,uncompress,zcat}` in [staging]. Only
     * the archive's own names are inspected: the sentinel/data-file names carry
     * PRoot's counters, which this test deliberately does not pin.
     */
    private fun shape(staging: File): Map<String, LinkShape> {
        val stagingPath = staging.absoluteFile.toPath().normalize()
        return listOf("usr/bin/gunzip", "usr/bin/uncompress", "usr/bin/zcat").associateWith { relative ->
            val path = File(staging, relative).toPath()
            assertTrue("$relative must be a symlink after --link2symlink", Files.isSymbolicLink(path))
            val target = Files.readSymbolicLink(path)
            LinkShape(
                absolute = target.isAbsolute,
                insideStaging = target.isAbsolute && target.normalize().startsWith(stagingPath),
                l2sTarget = (target.fileName?.toString() ?: "").startsWith(L2sLinkRewriter.L2S_PREFIX)
            )
        }
    }

    /** Runs [argv], draining output so a chatty proot cannot block on the pipe. */
    private fun run(argv: List<String>): Int {
        val process = ProcessBuilder(argv).redirectErrorStream(true).start()
        val drain = Thread { process.inputStream.use { it.readBytes() } }.apply { isDaemon = true; start() }
        val finished = process.waitFor(2, TimeUnit.MINUTES)
        if (!finished) {
            process.destroyForcibly()
            return -1
        }
        drain.join(TimeUnit.SECONDS.toMillis(10))
        return process.exitValue()
    }
}
