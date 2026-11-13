package com.fem.linexa.domain.packagemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.92 — JVM tests for the apt output parsers.
 *
 * These pin the parsing against real `dpkg-query`/`apt-cache` output shapes,
 * including size units (KiB → bytes) and status filtering.
 */
class AptOutputParserTest {

    @Test
    fun dpkgQueryParsesNameVersionSizeAndSection() {
        val out = """
            bash	5.2.15-2+b2	3128	install ok installed	shells
            curl	8.5.0-2	340	install ok installed	web
            nano	7.2-1	1420	install ok installed	editors
        """.trimIndent()
        val parsed = AptOutputParser.parseInstalled(out)
        assertEquals(3, parsed.size)
        val bash = parsed[0]
        assertEquals("bash", bash.name)
        assertEquals("5.2.15-2+b2", bash.version)
        assertEquals(3128L * 1024L, bash.installedSizeBytes)
        // §1.147: dpkg's Section is the *category*, not a mirror; repository
        // stays null because dpkg has no such field, and the card's label
        // falls back through `section ?: repository`.
        assertEquals("shells", bash.section)
        assertNull(bash.repository)
        assertEquals("shells", bash.categoryLabel)
    }

    /**
     * §1.147 — the 5-column format predates the Essential/Priority columns.
     * Its absence must mean "not marked protected" (dpkg's default), not a
     * parse failure.
     */
    @Test
    fun dpkgQueryFiveColumnRowsAreRemovable() {
        val parsed = AptOutputParser.parseInstalled("nano\t7.2-1\t1420\tinstall ok installed\teditors")
        assertTrue(parsed.single().removable)
    }

    /**
     * §1.147 — `Essential: yes` (bash) and `Priority: required` (libc6) are
     * dpkg's own declarations that a package must not be removed, and are the
     * only reason the DEL action renders disabled.
     */
    @Test
    fun dpkgQueryMarksEssentialAndRequiredPackagesUnremovable() {
        val out = """
            bash	5.2.15-2+b2	3128	install ok installed	shells	yes	required
            libc6	2.36-9	13000	install ok installed	libs	no	required
            nano	7.2-1	1420	install ok installed	editors	no	optional
        """.trimIndent()
        val parsed = AptOutputParser.parseInstalled(out)
        assertEquals(3, parsed.size)
        assertFalse(parsed[0].removable)
        assertFalse(parsed[1].removable)
        assertTrue(parsed[2].removable)
    }

    @Test
    fun dpkgQuerySkipsNotInstalledAndDeinstallRows() {
        val out = """
            bash	5.2.15-2+b2	3128	install ok installed	shells
            foo	1.0-1	10	deinstall ok config-files	admin
            bar	2.0-1	10	install ok not-installed	admin
        """.trimIndent()
        val parsed = AptOutputParser.parseInstalled(out)
        assertEquals(1, parsed.size)
        assertEquals("bash", parsed[0].name)
    }

    @Test
    fun dpkgQueryHandlesMissingSectionAndBadSize() {
        val out = "weirdpkg\t1.0\tnotanumber\tinstall ok installed\t\n"
        val parsed = AptOutputParser.parseInstalled(out)
        assertEquals(1, parsed.size)
        assertEquals("weirdpkg", parsed[0].name)
        assertNull(parsed[0].installedSizeBytes)
        assertNull(parsed[0].repository)
        // An empty Section column is absent data — the card shows no category
        // rather than an empty separator.
        assertNull(parsed[0].section)
        assertNull(parsed[0].categoryLabel)
    }

    @Test
    fun aptCacheSearchParsesNameDescriptionPairs() {
        val out = """
            bash - GNU Bourne Again SHell
            curl - command line tool for transferring data with URL syntax
        """.trimIndent()
        val parsed = AptOutputParser.parseSearch(out)
        assertEquals(2, parsed.size)
        assertEquals("bash" to "GNU Bourne Again SHell", parsed[0])
        assertEquals("curl" to "command line tool for transferring data with URL syntax", parsed[1])
    }

    @Test
    fun aptCachePolicyParsesCandidateVersionAndRepo() {
        val out = """
            bash:
              Installed: 5.2.15-2+b2
              Candidate: 5.2.21-2
              Version table:
             *** 5.2.21-2 500
                    500 http://deb.debian.org/debian bookworm/main amd64 Packages
                    100 /var/lib/dpkg/status
            curl:
              Installed: (none)
              Candidate: 8.5.0-2
              Version table:
                 8.5.0-2 500
                    500 https://deb.debian.org/debian bookworm/main amd64 Packages
        """.trimIndent()
        val parsed = AptOutputParser.parsePolicy(out)
        assertEquals("5.2.21-2", parsed["bash"]!!.version)
        assertEquals("http://deb.debian.org/debian", parsed["bash"]!!.repository)
        assertEquals("8.5.0-2", parsed["curl"]!!.version)
        assertEquals("https://deb.debian.org/debian", parsed["curl"]!!.repository)
    }

    @Test
    fun aptCachePolicyIgnoresDpkgStatusLineAsRepo() {
        val out = """
            bash:
              Candidate: 5.2.21-2
              Version table:
             *** 5.2.21-2 500
                    500 http://deb.debian.org/debian bookworm/main amd64 Packages
                    100 /var/lib/dpkg/status
        """.trimIndent()
        val parsed = AptOutputParser.parsePolicy(out)
        assertEquals("http://deb.debian.org/debian", parsed["bash"]!!.repository)
    }

    @Test
    fun aptCachePolicyEmptyForUnmatchedName() {
        assertTrue(AptOutputParser.parsePolicy("").isEmpty())
    }

    // ── apt-cache show (the only source of Store sizes) ────────────────────

    /**
     * Verbatim `apt-cache show nano` field order on apt 2.6.1 — note
     * `Description-en` precedes `Section`, and `Size` comes *after*
     * `Filename`, so the parser cannot assume a fixed ordering.
     * `Size` is download bytes; `Installed-Size` is KiB.
     */
    @Test
    fun aptCacheShowParsesBothSizesSectionAndDescription() {
        val out = """
            Package: nano
            Version: 7.2-1+deb12u1
            Installed-Size: 2848
            Maintainer: Jordi Mallach <jordi@debian.org>
            Architecture: arm64
            Description-en: small, friendly text editor inspired by Pico
             GNU nano is an easy-to-use text editor originally designed
             as a replacement for Pico.
            Section: editors
            Filename: pool/main/n/nano/nano_7.2-1+deb12u1_arm64.deb
            Size: 679956
        """.trimIndent()
        val parsed = AptOutputParser.parseShow(out)
        assertEquals(1, parsed.size)
        val nano = parsed["nano"]!!
        assertEquals("7.2-1+deb12u1", nano.version)
        assertEquals(679956L, nano.downloadSizeBytes)
        // Installed-Size is KiB per dpkg's documented unit.
        assertEquals(2848L * 1024L, nano.installedSizeBytes)
        assertEquals("editors", nano.section)
        assertEquals("small, friendly text editor inspired by Pico", nano.description)
    }

    /** The indented long-description body must not be read as fields. */
    @Test
    fun aptCacheShowIgnoresContinuationLines() {
        val out = """
            Package: nano
            Description-en: small, friendly text editor inspired by Pico
             Section: not-a-real-field
             Size: 999
        """.trimIndent()
        val nano = AptOutputParser.parseShow(out)["nano"]!!
        assertNull(nano.section)
        assertNull(nano.downloadSizeBytes)
        assertEquals("small, friendly text editor inspired by Pico", nano.description)
    }

    /** apt prints the candidate block first; later versions must not overwrite it. */
    @Test
    fun aptCacheShowKeepsTheFirstBlockPerPackage() {
        val out = """
            Package: bash
            Version: 5.2.15-2+b13
            Size: 1490000

            Package: bash
            Version: 5.2.15-2
            Size: 1480000
        """.trimIndent()
        val parsed = AptOutputParser.parseShow(out)
        assertEquals(1, parsed.size)
        assertEquals("5.2.15-2+b13", parsed["bash"]!!.version)
        assertEquals(1490000L, parsed["bash"]!!.downloadSizeBytes)
    }

    /** Several packages in one invocation, separated by blank lines. */
    @Test
    fun aptCacheShowParsesMultiplePackages() {
        val out = """
            Package: bash
            Version: 5.2.15-2+b13
            Installed-Size: 6996
            Size: 1490000

            Package: curl
            Version: 7.88.1-10+deb12u14
            Installed-Size: 442
            Size: 315000
        """.trimIndent()
        val parsed = AptOutputParser.parseShow(out)
        assertEquals(setOf("bash", "curl"), parsed.keys)
        assertEquals(6996L * 1024L, parsed["bash"]!!.installedSizeBytes)
        assertEquals(315000L, parsed["curl"]!!.downloadSizeBytes)
    }

    /** `Description` (no `-en`) is the older field name and is equally valid. */
    @Test
    fun aptCacheShowAcceptsPlainDescriptionField() {
        val out = """
            Package: bash
            Description: GNU Bourne Again SHell
        """.trimIndent()
        assertEquals("GNU Bourne Again SHell", AptOutputParser.parseShow(out)["bash"]!!.description)
    }

    @Test
    fun aptCacheShowEmptyForNoOutput() {
        assertTrue(AptOutputParser.parseShow("").isEmpty())
    }

    // ── version ────────────────────────────────────────────────────────────

    /** Verbatim first line of `apt-get --version` on Debian bookworm arm64. */
    @Test
    fun parsesAptVersion() {
        assertEquals("2.6.1", AptOutputParser.parseVersion("apt 2.6.1 (arm64)"))
        assertNull(AptOutputParser.parseVersion(""))
    }
}
