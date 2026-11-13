package com.fem.linexa.domain.packagemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 2026-09-02 (guided desktop installer, Phase 3) — JVM tests for
 * [AptSimulateParser]: the read-only `apt-get -s` quote parser and the
 * streaming install-progress classifier.
 *
 * Sample text follows apt 2.6.x's real output shape (the same family the
 * existing AptOutputParserTest samples were verified against on Debian
 * bookworm arm64).
 */
class AptSimulateParserTest {

    private val xfceSimulate = """
        NOTE: This is only a simulation!
              apt-get needs root access for real execution.
              Keep also in mind that unlocking is deactivated,
              packages aren't really downloaded, installed
              and removed until you use the command `install`!
        Reading package lists...
        Building dependency tree...
        Reading state information...
        The following additional packages will be installed:
          exo-utils gtk-update-icon-cache liberr0 libexo-2-0 libgarcon-1-0 ...
        The following NEW packages will be installed:
          dbus-x11 exo-utils gtk-update-icon-cache xfce4 xfce4-terminal ...
        0 upgraded, 127 newly installed, 0 to remove and 4 not upgraded.
        Need to get 84.5 MB of archives.
        After this operation, 284 MB of additional disk space will be used.
        Get:1 http://deb.debian.org/debian bookworm/main arm64 liberr0 arm64 0.6.10-1 [11.2 kB]
        Inst dbus-x11 (1.14.6-1 Debian:12.5/stable [arm64])
        Conf xfce4 (4.18.2 Debian:12.5/stable [arm64])
    """.trimIndent()

    // ── parseSimulate ─────────────────────────────────────────────────────

    @Test
    fun simulationQuoteParsesBothSizes() {
        val info = AptSimulateParser.parseSimulate(xfceSimulate)
        // 84.5 MB (SI, apt's own unit) → 84_500_000 bytes.
        assertEquals(84_500_000L, info.downloadSizeBytes)
        // 284 MB → 284_000_000 bytes.
        assertEquals(284_000_000L, info.installedSizeBytes)
        assertEquals(127, info.newPackages)
    }

    @Test
    fun missingLinesYieldNullsNeverZeros() {
        val info = AptSimulateParser.parseSimulate("Reading package lists...\nDone.")
        assertNull(info.downloadSizeBytes)
        assertNull(info.installedSizeBytes)
        assertNull(info.newPackages)
    }

    @Test
    fun emptyOutputYieldsNulls() {
        val info = AptSimulateParser.parseSimulate("")
        assertNull(info.downloadSizeBytes)
        assertNull(info.installedSizeBytes)
        assertNull(info.newPackages)
    }

    @Test
    fun kilobyteQuoteParses() {
        val out = "Need to get 512 kB of archives.\nAfter this operation, 2,400 kB will be used."
        val info = AptSimulateParser.parseSimulate(out)
        assertEquals(512_000L, info.downloadSizeBytes)
    }

    @Test
    fun gibibyteQuoteParses() {
        val out = "Need to get 1.2 GB of archives."
        assertEquals(1_200_000_000L, AptSimulateParser.parseSimulate(out).downloadSizeBytes)
    }

    // ── parseAptSize ──────────────────────────────────────────────────────

    @Test
    fun sizeUnitsParseSiStyle() {
        assertEquals(1000L, AptSimulateParser.parseAptSize("1 kB"))
        assertEquals(84_500_000L, AptSimulateParser.parseAptSize("84.5 MB"))
        assertEquals(1L, AptSimulateParser.parseAptSize("1 B"))
        assertNull(AptSimulateParser.parseAptSize("84.5 MiB"))
        assertNull(AptSimulateParser.parseAptSize("garbage"))
        assertNull(AptSimulateParser.parseAptSize(""))
    }

    // ── parseInstallProgress ──────────────────────────────────────────────

    @Test
    fun selectingLineParses() {
        val ev = AptSimulateParser.parseInstallProgress(
            "Selecting previously unselected package xfce4."
        )
        assertEquals("xfce4", ev?.packageName)
        assertEquals(AptSimulateParser.InstallStage.SELECTED, ev?.stage)
    }

    @Test
    fun unpackingLineParses() {
        val ev = AptSimulateParser.parseInstallProgress(
            "Unpacking xfce4 (4.18.2) ..."
        )
        assertEquals("xfce4", ev?.packageName)
        assertEquals(AptSimulateParser.InstallStage.UNPACKING, ev?.stage)
    }

    @Test
    fun settingUpLineParses() {
        val ev = AptSimulateParser.parseInstallProgress(
            "Setting up dbus-x11 (1.14.6-1) ..."
        )
        assertEquals("dbus-x11", ev?.packageName)
        assertEquals(AptSimulateParser.InstallStage.CONFIGURING, ev?.stage)
    }

    @Test
    fun progressBarsAndPercentagesAreNeverParsedAsPackages() {
        // The honesty contract: formats vary across apt/dpkg versions and
        // locales — a misparse would fabricate a package name.
        listOf(
            "Get:1 http://deb.debian.org/debian bookworm/main arm64 xfce4 arm64 4.18.2 [11.2 MB]",
            " 98% [11 Packages / 127 in queue]",
            "[##.................] 12%",
            "Preparing to unpack .../dbus-x11_1.14.6-1_arm64.deb ...",
            "update-alternatives: using /usr/bin/xterm to provide x-terminal-emulator",
            "Processing triggers for man-db (2.11.2-2) ...",
            "",
            "garbage"
        ).forEach { line ->
            assertNull("line must not parse as a package event: '$line'",
                AptSimulateParser.parseInstallProgress(line))
        }
    }
}
