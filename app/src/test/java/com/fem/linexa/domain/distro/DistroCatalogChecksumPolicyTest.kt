package com.fem.linexa.domain.distro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 06 (OPT-SEC-006): the no-checksum policy for the bundled catalog.
 *
 * Every Direct-source entry must ship a usable SHA-256 sidecar; the only
 * waiver is `archlinux` (ALARM publishes an .md5 and a detached signature,
 * neither of which the SHA-256 pipeline can consume — the gap is documented
 * on the entry instead of silently faked). The validator is pinned by this
 * test: adding a checksumless entry WITHOUT a waiver fails here, at
 * validation time, instead of shipping an unverified download path.
 */
class DistroCatalogChecksumPolicyTest {

    @Test
    fun theBundledCatalogSatisfiesTheChecksumPolicy() {
        assertEquals(
            "the bundled catalog must be waiver-clean",
            emptyList<String>(),
            DistroCatalog.checksumPolicyViolations()
        )
    }

    @Test
    fun aChecksumlessDirectEntryIsReportedAsAViolation() {
        val orphan = DistroManifest(
            id = "mystery",
            displayName = "Mystery",
            version = "1",
            avatarLetter = "M",
            avatarColorHex = "#000000",
            approxDownloadSizeLabel = "~1 MB",
            source = DistroSource.Direct(downloadUrl = "https://example.invalid/mystery.tar.gz")
        )
        val violations = DistroCatalog.checksumPolicyViolations(listOf(orphan))
        assertEquals(1, violations.size)
        assertTrue(
            "the violation must name the entry: ${violations.firstOrNull()}",
            violations.single().contains("mystery")
        )
    }

    @Test
    fun aChecksumlessEntryWithAWaiverIdIsNotAViolation() {
        val waived = DistroManifest(
            id = "archlinux",
            displayName = "Waived",
            version = "1",
            avatarLetter = "W",
            avatarColorHex = "#000000",
            approxDownloadSizeLabel = "~1 MB",
            source = DistroSource.Direct(downloadUrl = "https://example.invalid/waived.tar.gz")
        )
        assertTrue(
            DistroCatalog.checksumPolicyViolations(listOf(waived)).isEmpty()
        )
    }

    @Test
    fun archlinuxIsTheSoleWaiverAndEveryOtherDirectEntryCarriesAHttpsSidecar() {
        val alarm = DistroCatalog.findById("archlinux")!!
        val alarmSource = alarm.source as DistroSource.Direct
        // The gap as documented on the entry: no SHA-256 sidecar exists for
        // ALARM's rolling tarball — the waiver is what keeps the catalog
        // policy-clean, nothing else.
        assertNull(alarmSource.checksumUrl)
        assertEquals(ChecksumFormat.NONE, alarmSource.checksumFormat)
        assertTrue(DistroCatalog.checksumPolicyViolations().isEmpty())

        DistroCatalog.all
            .filter { it.id != "archlinux" }
            .forEach { entry ->
                val direct = entry.source as? DistroSource.Direct ?: return@forEach
                assertNotNull("${entry.id} must publish a checksum sidecar", direct.checksumUrl)
                assertNotEquals(
                    "${entry.id} must declare how to parse its sidecar",
                    ChecksumFormat.NONE,
                    direct.checksumFormat
                )
                assertTrue(
                    "checksum sidecars must be https (Phase 0 hardening): ${direct.checksumUrl}",
                    direct.checksumUrl!!.startsWith("https://")
                )
            }
    }
}
