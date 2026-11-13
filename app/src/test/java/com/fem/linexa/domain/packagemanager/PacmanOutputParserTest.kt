package com.fem.linexa.domain.packagemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.92 — JVM tests for the pacman output parsers.
 */
class PacmanOutputParserTest {

    @Test
    fun pacmanSsParsesRepoNameVersionAndDescription() {
        val out = """
            extra/git 2.45.0-1
                the fast distributed version control system
            core/bash 5.2.026-1
                the GNU Bourne Again shell
        """.trimIndent()
        val parsed = PacmanOutputParser.parseSearch(out)
        assertEquals(2, parsed.size)
        val git = parsed[0]
        assertEquals("git", git.name)
        assertEquals("2.45.0-1", git.latestVersion)
        assertEquals("extra", git.repository)
        assertEquals("the fast distributed version control system", git.description)
        assertEquals("bash", parsed[1].name)
        assertEquals("core", parsed[1].repository)
    }

    @Test
    fun pacmanSsStripsInstalledSuffix() {
        val out = """
            core/bash 5.2.026-1 [installed]
                the GNU Bourne Again shell
        """.trimIndent()
        val parsed = PacmanOutputParser.parseSearch(out)
        assertEquals("5.2.026-1", parsed[0].latestVersion)
    }

    /**
     * `pacman -Qi` reports size and description but **no** `Repository` field
     * (verified on pacman 7.1.0 — the installed-package view has no repo
     * attribution). Repository comes from [PacmanOutputParser.parseRepoMap].
     */
    @Test
    fun pacmanQiParsesSizeAndDescription() {
        val out = """
            Name            : git
            Version         : 2.45.0-1
            Description     : the fast distributed version control system
            Installed Size  : 16.35 MiB
            Install Date    : Wed Aug  5 12:37:25 2026

            Name            : bash
            Version         : 5.2.026-1
            Description     : the GNU Bourne Again shell
            Installed Size  : 1.05 MiB
            Install Date    : Wed Aug  5 12:37:25 2026
        """.trimIndent()
        val parsed = PacmanOutputParser.parseInstalledInfo(out)
        assertEquals(2, parsed.size)
        assertEquals(16.35 * 1024 * 1024, parsed["git"]!!.installedSizeBytes!!.toDouble(), 1.0)
        assertEquals("the fast distributed version control system", parsed["git"]!!.description)
        assertEquals(1.05 * 1024 * 1024, parsed["bash"]!!.installedSizeBytes!!.toDouble(), 1.0)
    }

    /** Repository attribution for installed packages comes from `pacman -Sl`. */
    @Test
    fun pacmanSlMapsInstalledPackagesToRepositories() {
        val out = """
            core acl 2.4.0-1 [installed]
            core amd-ucode 20260810-2
            extra git 2.45.0-1 [installed]
            extra htop 3.3.0-2 [installed: 3.2.9-1]
        """.trimIndent()
        val map = PacmanOutputParser.parseRepoMap(out)
        assertEquals(3, map.size)
        assertEquals("core", map["acl"])
        assertEquals("extra", map["git"])
        // `[installed: <other-version>]` still means installed.
        assertEquals("extra", map["htop"])
        assertNull(map["amd-ucode"])
    }

    /**
     * §1.147 — `Groups` is pacman's category concept. `None` is the literal
     * pacman prints for a package in no group and must not become a label.
     */
    @Test
    fun pacmanQiParsesGroupsAndTreatsNoneAsAbsent() {
        val out = """
            Name            : bash
            Version         : 5.2.026-1
            Groups          : base
            Installed Size  : 1.05 MiB

            Name            : git
            Version         : 2.45.0-1
            Groups          : None
            Installed Size  : 16.35 MiB

            Name            : gcc
            Version         : 14.1.0-1
            Groups          : base-devel devel
            Installed Size  : 100.00 MiB
        """.trimIndent()
        val parsed = PacmanOutputParser.parseInstalledInfo(out)
        assertEquals(listOf("base"), parsed["bash"]!!.groups)
        assertTrue(parsed["git"]!!.groups.isEmpty())
        assertEquals(listOf("base-devel", "devel"), parsed["gcc"]!!.groups)
    }

    /**
     * §1.147 — `pacman -Qu` is pacman's own upgrade report, and the only
     * source for the UP action.
     */
    @Test
    fun pacmanQuParsesArrowUpgradeReport() {
        val out = """
            bash 5.2.032-1 -> 5.3.009-1
            pacman 7.0.0-8 -> 7.1.0-1
        """.trimIndent()
        val parsed = PacmanOutputParser.parseUpgradable(out)
        assertEquals(mapOf("bash" to "5.3.009-1", "pacman" to "7.1.0-1"), parsed)
    }

    /**
     * `-Qu` exits 1 and prints nothing when everything is current; that is an
     * empty map, so no package claims an update.
     */
    @Test
    fun pacmanQuIsEmptyWhenNothingIsUpgradable() {
        assertTrue(PacmanOutputParser.parseUpgradable("").isEmpty())
        assertTrue(PacmanOutputParser.parseUpgradable("there is nothing to do\n").isEmpty())
    }

    /** `pacman -Si` does carry Repository, and it precedes Name. */
    @Test
    fun pacmanSiParsesRepositoryVersionAndBothSizes() {
        val out = """
            Repository      : core
            Name            : nano
            Version         : 9.2-1
            Description     : Pico editor clone with enhancements
            Download Size   : 605.91 KiB
            Installed Size  : 2948.54 KiB
        """.trimIndent()
        val parsed = PacmanOutputParser.parseAvailableInfo(out)
        assertEquals(1, parsed.size)
        val nano = parsed["nano"]!!
        assertEquals("core", nano.repository)
        assertEquals("9.2-1", nano.version)
        assertEquals(605.91 * 1024, nano.downloadSizeBytes!!.toDouble(), 1.0)
        assertEquals(2948.54 * 1024, nano.installedSizeBytes!!.toDouble(), 1.0)
    }

    /**
     * §1.147 — `-Si`'s `URL` backs the Store card's OPEN action and `Groups`
     * its category. A blank URL leaves the action unrendered.
     */
    @Test
    fun pacmanSiParsesUrlAndGroups() {
        val out = """
            Repository      : core
            Name            : bash
            Version         : 5.2.026-1
            URL             : https://www.gnu.org/software/bash/
            Groups          : base
            Download Size   : 1.00 MiB

            Repository      : extra
            Name            : htop
            Version         : 3.3.0-2
            URL             :
            Groups          : None
        """.trimIndent()
        val parsed = PacmanOutputParser.parseAvailableInfo(out)
        assertEquals("https://www.gnu.org/software/bash/", parsed["bash"]!!.homepageUrl)
        assertEquals(listOf("base"), parsed["bash"]!!.groups)
        assertNull(parsed["htop"]!!.homepageUrl)
        assertTrue(parsed["htop"]!!.groups.isEmpty())
    }

    /**
     * §1.147 — a `URL` value contains `://`, so the "first colon" field split
     * must not truncate it at the scheme.
     */
    @Test
    fun pacmanSiKeepsTheWholeUrlIncludingItsScheme() {
        val out = """
            Name            : git
            URL             : https://git-scm.com/
        """.trimIndent()
        assertEquals("https://git-scm.com/", PacmanOutputParser.parseAvailableInfo(out)["git"]!!.homepageUrl)
    }

    @Test
    fun pacmanSizeUnits() {
        assertEquals(42L, PacmanOutputParser.parseSize("42 B"))
        assertEquals(512L * 1024L, PacmanOutputParser.parseSize("512.00 KiB"))
        assertEquals((1.5 * 1024 * 1024).toLong(), PacmanOutputParser.parseSize("1.50 MiB"))
        assertEquals(1024L * 1024L * 1024L, PacmanOutputParser.parseSize("1 GiB"))
        assertNull(PacmanOutputParser.parseSize(""))
        assertNull(PacmanOutputParser.parseSize("nope"))
    }

    @Test
    fun pacmanSsSkipsNonPackageLines() {
        val out = "    some indented orphan line with no repo slash\n"
        assertTrue(PacmanOutputParser.parseSearch(out).isEmpty())
    }
}
