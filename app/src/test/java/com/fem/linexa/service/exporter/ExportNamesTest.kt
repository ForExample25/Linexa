package com.fem.linexa.service.exporter

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * § export — the backup file-name rules. The name is the only identification
 * a backup carries on the user's storage, so the tests pin exactly what goes
 * into it, what is refused, and that the same inputs always produce the same
 * name.
 */
class ExportNamesTest {

    private val zone = ZoneId.of("Asia/Tehran")
    private val fixed = Instant.parse("2026-08-29T10:30:52Z") // = 14:00:52 in Tehran (UTC+3:30)

    @Test
    fun distroVersionAndArchComposeIntoTheName() {
        val name = ExportNames.suggestedFileName(
            distroName = "Debian",
            distroVersion = "12.9",
            distroArch = "arm64-v8a",
            workspaceId = "a1b2c3d4-e5f6-7890-abcd-ef0123456789",
            now = fixed,
            zone = zone
        )
        assertEquals("linexa-debian-12.9-arm64-v8a-20260829-140052-a1b2c3d4.tar.gz", name)
    }

    @Test
    fun unknownAndBlankComponentsAreDroppedNotEncoded() {
        val name = ExportNames.suggestedFileName(
            distroName = "Debian",
            distroVersion = "unknown",
            distroArch = "",
            workspaceId = "a1b2c3d4-0000",
            now = fixed,
            zone = zone
        )
        assertFalse("the literal unknown must not appear", name.contains("unknown"))
        assertTrue(name.startsWith("linexa-debian-"))
        assertTrue(name.endsWith("-a1b2c3d4.tar.gz"))
    }

    @Test
    fun displayFormsAreSlugifiedToSafeCharacters() {
        val name = ExportNames.suggestedFileName(
            distroName = "Arch Linux ARM (rolling)",
            distroVersion = "2026.08.01",
            distroArch = "aarch64",
            workspaceId = "ffffffff-ffff",
            now = fixed,
            zone = zone
        )
        assertEquals(
            "linexa-arch-linux-arm-rolling-2026.08.01-aarch64-20260829-140052-ffffffff.tar.gz",
            name
        )
    }

    @Test
    fun aWorkspaceWithNoIdentityStillGetsAStableName() {
        val name = ExportNames.suggestedFileName(
            distroName = null,
            distroVersion = null,
            distroArch = null,
            workspaceId = "12345678-abcd",
            now = fixed,
            zone = zone
        )
        assertEquals("linexa-20260829-140052-12345678.tar.gz", name)
    }

    @Test
    fun shortIdsAreUsedWholeAndPunctuationIsTolerated() {
        val name = ExportNames.suggestedFileName(
            distroName = "!!!",
            distroVersion = null,
            distroArch = null,
            workspaceId = "ab",
            now = fixed,
            zone = zone
        )
        // "!!!" slugifies to blank → dropped; the id is kept whole.
        assertEquals("linexa-20260829-140052-ab.tar.gz", name)
    }

    @Test
    fun successiveExportsOfTheSameWorkspaceDiffer() {
        val a = ExportNames.suggestedFileName("Debian", "12", null, "a1b2c3d4-x", now = fixed, zone = zone)
        val b = ExportNames.suggestedFileName(
            "Debian", "12", null, "a1b2c3d4-x",
            now = fixed.plusSeconds(1), zone = zone
        )
        assertTrue("a one-second difference must change the name", a != b)
    }
}
