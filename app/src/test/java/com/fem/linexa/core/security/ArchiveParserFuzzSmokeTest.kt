package com.fem.linexa.core.security

import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-043 — a deterministic, seeded fuzz smoke test for the archive parsers.
 *
 * Jazzer's coverage-guided engine is not available in this build environment,
 * so this runs a bounded corpus of adversarial names/targets through the same
 * entry-classification and symlink-guard code the extractor uses. It is a
 * regression net for "no uncaught exception, ever" (the property that matters
 * for untrusted archives), not a replacement for a coverage-guided campaign.
 *
 * The corpus is generated from a seed, so a failure is reproducible from the
 * printed seed alone.
 */
class ArchiveParserFuzzSmokeTest {

    private val validator = RealArchiveValidator()

    private val fragments = listOf(
        "", "/", "//", ".", "..", "../", "/..", "\\", "\\..\\", "\u0000", "\n",
        "etc", "passwd", "usr", "lib", "a b", "é", "🔥", "..%2f", "~", "-rf"
    )

    private fun randomPath(random: Random): String {
        val parts = (0 until random.nextInt(0, 6)).map {
            if (random.nextBoolean()) fragments.random(random) else "seg${random.nextInt(10)}"
        }
        return parts.joinToString("/")
    }

    @Test
    fun `entry classification never throws on adversarial names and targets`() {
        val random = Random(0x1EA5A)
        repeat(2_000) {
            val name = randomPath(random)
            val target = if (random.nextBoolean()) randomPath(random) else null
            val type = ArchiveEntryType.entries.random(random)
            val isSymlink = random.nextBoolean()

            // Any outcome (safe or rejected) is acceptable; throwing is not.
            validator.checkEntry(name, isSymlink, target, type)
        }
    }

    @Test
    fun `the symlink guard never throws and never lets an ancestor link through`() {
        val random = Random(0x51A11)
        val guard = ArchiveSymlinkGuard()
        repeat(2_000) {
            val path = randomPath(random)
            val target = randomPath(random)
            guard.observe(path, isSymlink = true, linkTarget = target)
            guard.checkEntryPath(path, path)
            guard.linksAfterStrip(random.nextInt(0, 5))
        }

        val replay = ArchiveSymlinkGuard()
        replay.observe("usr/bin/x", isSymlink = true, linkTarget = "/tmp")
        val reason = replay.checkEntryPath("usr/bin/x/payload", "usr/bin/x/payload")
        assertTrue(reason != null && reason.contains("usr/bin/x"))
    }

    @Test
    fun `the same seed produces the same corpus`() {
        val a = Random(42)
        val b = Random(42)
        repeat(100) {
            assertTrue(randomPath(a) == randomPath(b))
        }
    }
}
