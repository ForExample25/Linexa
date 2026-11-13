package com.fem.linexa.domain.packagemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.92 / §1.146 — JVM tests for the zypper output parsers.
 *
 * Every fixture below is verbatim output captured from zypper 1.14.98 on
 * openSUSE Leap 16.0 (aarch64), not hand-written approximations. The previous
 * fixtures used an invented 6-column `S | Name | Type | Version | Arch |
 * Repository` layout for `zypper packages`, which is why the old parser
 * passed its tests while returning nothing on a real device.
 */
class ZypperOutputParserTest {

    // ── zypper packages --installed-only ───────────────────────────────────

    /** Real header + rows: Repository is column 1, Name is column 2, 5 columns. */
    private val installedFixture = """
        S  | Repository | Name                | Version                               | Arch
        ---+------------+---------------------+---------------------------------------+--------
        i+ | @System    | aaa_base            | 84.87+git20260610.3b5a868c-160000.1.1 | aarch64
        i  | @System    | alts                | 1.2+30.a5431e9-160000.2.2             | aarch64
        i+ | @System    | bash                | 5.2.37-160000.2.2                     | aarch64
        i  | @System    | bash-sh             | 5.2.37-160000.2.2                     | noarch
        i  | @System    | branding-openSUSE   | 16.0.20240405-lp160.10.1              | aarch64
    """.trimIndent()

    @Test
    fun parsesInstalledFromRealFiveColumnTable() {
        val parsed = ZypperOutputParser.parseInstalled(installedFixture)
        assertEquals(5, parsed.size)
        assertEquals("aaa_base", parsed[0].name)
        assertEquals("84.87+git20260610.3b5a868c-160000.1.1", parsed[0].version)
        assertEquals("bash", parsed[2].name)
        assertEquals("5.2.37-160000.2.2", parsed[2].version)
        assertEquals("branding-openSUSE", parsed[4].name)
    }

    /** `@System` is the rpmdb pseudo-repo, not a repository worth showing. */
    @Test
    fun dropsSystemPseudoRepository() {
        val parsed = ZypperOutputParser.parseInstalled(installedFixture)
        assertTrue(parsed.all { it.repository == null })
    }

    /** A real repository name is kept as-is. */
    @Test
    fun keepsRealRepositoryName() {
        val out = """
            S  | Repository        | Name | Version           | Arch
            ---+-------------------+------+-------------------+--------
            i+ | openSUSE:repo-oss | curl | 8.12.1-160000.1.1 | aarch64
        """.trimIndent()
        val parsed = ZypperOutputParser.parseInstalled(out)
        assertEquals(1, parsed.size)
        assertEquals("openSUSE:repo-oss", parsed[0].repository)
    }

    /** Only `i`/`i+` rows are installed; `v` is an available other version. */
    @Test
    fun installedIgnoresNonInstalledStatusRows() {
        val out = """
            S  | Repository        | Name | Version | Arch
            ---+-------------------+------+---------+--------
            i+ | @System           | bash | 5.2.37  | aarch64
            v  | openSUSE:repo-oss | bash | 5.3.0   | aarch64
        """.trimIndent()
        val parsed = ZypperOutputParser.parseInstalled(out)
        assertEquals(1, parsed.size)
        assertEquals("5.2.37", parsed[0].version)
    }

    /** The installed table carries no size column — it must not be invented. */
    @Test
    fun installedTableHasNoSizes() {
        assertTrue(ZypperOutputParser.parseInstalled(installedFixture).all { it.installedSizeBytes == null })
    }

    // ── zypper search (both real layouts) ──────────────────────────────────

    /** Plain `zypper se`: 4 columns, Summary present, no version. */
    @Test
    fun parsesPlainSearchWithSummaryAndNoVersion() {
        val out = """
            S  | Name    | Summary                     | Type
            ---+---------+-----------------------------+--------
            i+ | bash    | The GNU Bourne-Again Shell  | package
            i  | bash-sh | Handle behaviour of /bin/sh | package
        """.trimIndent()
        val parsed = ZypperOutputParser.parseSearch(out)
        assertEquals(2, parsed.size)
        assertEquals("bash", parsed[0].name)
        assertEquals("", parsed[0].latestVersion)
        assertEquals("The GNU Bourne-Again Shell", parsed[0].description)
        assertNull(parsed[0].repository)
        assertEquals("bash-sh", parsed[1].name)
    }

    /** `zypper se -s`: 6 columns, Version present, no summary. */
    @Test
    fun parsesDetailedSearchWithVersionAndRepository() {
        val out = """
            S  | Name    | Type    | Version           | Arch    | Repository
            ---+---------+---------+-------------------+---------+------------------
            i+ | bash    | package | 5.2.37-160000.2.2 | aarch64 | (System Packages)
            v  | htop    | package | 3.3.0-160000.1.5  | aarch64 | openSUSE:repo-oss
        """.trimIndent()
        val parsed = ZypperOutputParser.parseSearch(out)
        assertEquals(2, parsed.size)
        assertEquals("bash", parsed[0].name)
        assertEquals("5.2.37-160000.2.2", parsed[0].latestVersion)
        // "(System Packages)" is the rpmdb label, not a repository.
        assertNull(parsed[0].repository)
        assertNull(parsed[0].description)
        assertEquals("htop", parsed[1].name)
        assertEquals("openSUSE:repo-oss", parsed[1].repository)
    }

    /** Header/rule rows and blank lines never become packages. */
    @Test
    fun skipsSeparatorsHeaderAndBlankLines() {
        val out = """

            S  | Name | Type | Version | Arch | Repository
            ---+------+------+---------+------+------------

        """.trimIndent()
        assertTrue(ZypperOutputParser.parseSearch(out).isEmpty())
        assertTrue(ZypperOutputParser.parseInstalled(out).isEmpty())
    }

    /** zypper prints this (exit 104) when nothing matches. */
    @Test
    fun handlesNoMatchingItemsOutput() {
        assertTrue(ZypperOutputParser.parseSearch("No matching items found.").isEmpty())
        assertTrue(ZypperOutputParser.parseInstalled("No matching items found.").isEmpty())
    }

    /**
     * With unreachable repos zypper prefixes warnings to stdout and still
     * prints the table (exit 106). Warnings must be ignored, rows kept.
     */
    @Test
    fun ignoresRepositoryWarningsPrecedingTheTable() {
        val out = """
            Warning: Skipping repository 'repo-openh264 (16.0)' because of the above error.
            Warning: Skipping repository 'repo-oss (16.0)' because of the above error.
            S  | Repository | Name | Version           | Arch
            ---+------------+------+-------------------+--------
            i+ | @System    | bash | 5.2.37-160000.2.2 | aarch64
        """.trimIndent()
        val parsed = ZypperOutputParser.parseInstalled(out)
        assertEquals(1, parsed.size)
        assertEquals("bash", parsed[0].name)
    }

    // ── zypper info ────────────────────────────────────────────────────────

    /** Verbatim two-package `zypper info` output, including the blank prefix. */
    private val infoFixture = """


        Information for package bash:
        -----------------------------
        Repository     : @System
        Name           : bash
        Version        : 5.2.37-160000.2.2
        Arch           : aarch64
        Vendor         : SUSE LLC <https://www.suse.com/>
        Installed Size : 1.2 MiB
        Installed      : Yes
        Status         : up-to-date
        Source package : bash-5.2.37-160000.2.2.src
        Upstream URL   : https://www.gnu.org/software/bash/bash.html
        Summary        : The GNU Bourne-Again Shell
        Description    : 
            Bash is an sh-compatible command interpreter that executes commands
            read from standard input or from a file.


        Information for package htop:
        -----------------------------
        Repository     : openSUSE:repo-oss
        Name           : htop
        Version        : 3.3.0-160000.1.5
        Arch           : aarch64
        Installed Size : 259.1 KiB
        Installed      : No
        Status         : not installed
        Summary        : An Interactive text-mode Process Viewer
    """.trimIndent()

    @Test
    fun parsesInfoBlocksKeyedByName() {
        val details = ZypperOutputParser.parseInfo(infoFixture)
        assertEquals(setOf("bash", "htop"), details.keys)
        assertEquals("5.2.37-160000.2.2", details["bash"]!!.version)
        assertEquals("The GNU Bourne-Again Shell", details["bash"]!!.description)
        assertEquals("3.3.0-160000.1.5", details["htop"]!!.version)
    }

    /** `Repository` precedes `Name`, so it must be attributed to the right block. */
    @Test
    fun attributesRepositoryToItsOwnBlock() {
        val details = ZypperOutputParser.parseInfo(infoFixture)
        assertNull(details["bash"]!!.repository)
        assertEquals("openSUSE:repo-oss", details["htop"]!!.repository)
    }

    /** `Installed Size` is a formatted 1024-based value, converted to bytes. */
    @Test
    fun convertsInstalledSizeToBytes() {
        val details = ZypperOutputParser.parseInfo(infoFixture)
        assertEquals((1.2 * 1024 * 1024).toLong(), details["bash"]!!.installedSizeBytes)
        assertEquals((259.1 * 1024).toLong(), details["htop"]!!.installedSizeBytes)
    }

    /** The indented Description body must not be mistaken for a field. */
    @Test
    fun ignoresIndentedDescriptionBody() {
        val details = ZypperOutputParser.parseInfo(infoFixture)
        // Summary wins; the wrapped body lines are dropped entirely.
        assertEquals("The GNU Bourne-Again Shell", details["bash"]!!.description)
    }

    @Test
    fun infoHandlesNotFoundOutput() {
        val out = """


            package 'zzzznomatch' not found.
            No matching items found.
        """.trimIndent()
        assertTrue(ZypperOutputParser.parseInfo(out).isEmpty())
    }

    /**
     * §1.147 — the OPEN action's URL. This capture labels the field
     * `Upstream URL`; older zypper prints plain `URL`. Both must be read, and
     * the `://` in the value must survive the `Key : value` split.
     */
    @Test
    fun parsesUpstreamUrlForTheOpenAction() {
        val details = ZypperOutputParser.parseInfo(infoFixture)
        assertEquals("https://www.gnu.org/software/bash/bash.html", details["bash"]!!.homepageUrl)
        // htop's block carries no URL field at all.
        assertNull(details["htop"]!!.homepageUrl)
    }

    /** Older zypper's plain `URL` label parses identically. */
    @Test
    fun parsesLegacyUrlFieldLabel() {
        val out = """
            Information for package htop:
            -----------------------------
            Name           : htop
            URL            : https://htop.dev/
        """.trimIndent()
        assertEquals("https://htop.dev/", ZypperOutputParser.parseInfo(out)["htop"]!!.homepageUrl)
    }

    /** rpm's `(none)` is the absence of a URL, so no OPEN action is offered. */
    @Test
    fun dropsRpmNoneUrl() {
        val out = """
            Information for package foo:
            ----------------------------
            Name           : foo
            Upstream URL   : (none)
        """.trimIndent()
        assertNull(ZypperOutputParser.parseInfo(out)["foo"]!!.homepageUrl)
    }

    // ── zypper list-updates / locks (§1.147) ───────────────────────────────

    /**
     * `zypper --quiet list-updates` — the only source for the UP action. The
     * *available* version (column 4), not the current one, is what the action
     * offers.
     */
    @Test
    fun parsesListUpdatesAvailableVersion() {
        val out = """
            S | Repository        | Name | Current Version   | Available Version | Arch
            --+-------------------+------+-------------------+-------------------+--------
            v | openSUSE:repo-oss | bash | 5.2.37-160000.2.2 | 5.2.37-160000.3.1 | aarch64
            v | openSUSE:repo-oss | curl | 8.12.1-160000.1.1 | 8.13.0-160000.1.1 | aarch64
        """.trimIndent()
        val parsed = ZypperOutputParser.parseUpdates(out)
        assertEquals(mapOf("bash" to "5.2.37-160000.3.1", "curl" to "8.13.0-160000.1.1"), parsed)
    }

    /** No updates means no package claims one. */
    @Test
    fun listUpdatesIsEmptyWhenEverythingIsCurrent() {
        assertTrue(ZypperOutputParser.parseUpdates("No updates found.").isEmpty())
        assertTrue(ZypperOutputParser.parseUpdates("").isEmpty())
    }

    /**
     * `zypper --quiet locks` is zypper's own declaration of packages that must
     * not be changed, and is what disables the DEL action. Its first column is
     * a lock *number*, not a status letter.
     */
    @Test
    fun parsesLockedPackageNames() {
        val out = """
            # | Name   | Type    | Repository
            --+--------+---------+-----------
            1 | zypper | package | (any)
            2 | glibc  | package | (any)
        """.trimIndent()
        assertEquals(setOf("zypper", "glibc"), ZypperOutputParser.parseLocks(out))
    }

    /** No locks configured is an empty set, not a parse failure. */
    @Test
    fun locksAreEmptyWhenNoneAreConfigured() {
        assertTrue(ZypperOutputParser.parseLocks("There are no package locks defined.").isEmpty())
        assertTrue(ZypperOutputParser.parseLocks("").isEmpty())
    }

    // ── size parsing ───────────────────────────────────────────────────────

    @Test
    fun parsesEachSizeUnitAsBinary() {
        assertEquals(512L, ZypperOutputParser.parseSize("512 B"))
        assertEquals(1024L, ZypperOutputParser.parseSize("1 KiB"))
        assertEquals(1024L * 1024, ZypperOutputParser.parseSize("1 MiB"))
        assertEquals(1024L * 1024 * 1024, ZypperOutputParser.parseSize("1 GiB"))
        assertEquals(1024L * 1024 * 1024 * 1024, ZypperOutputParser.parseSize("1 TiB"))
    }

    @Test
    fun rejectsUnparseableOrZeroSizes() {
        assertNull(ZypperOutputParser.parseSize("0 B"))
        assertNull(ZypperOutputParser.parseSize(""))
        assertNull(ZypperOutputParser.parseSize("unknown"))
        assertNull(ZypperOutputParser.parseSize("12 MB"))
    }

    // ── rpm fallback ───────────────────────────────────────────────────────

    /** Verbatim `rpm -qa --queryformat '%{NAME}|%{EVR}|%{SIZE}\n'` output. */
    @Test
    fun parsesRpmFallbackWithRealSizes() {
        val out = """
            boost-license1_86_0|1.86.0-160000.2.2|1338
            gpg-pubkey|25db7ae0-645bae34|0
            cracklib-dict-small|2.9.11-160000.2.2|259081
        """.trimIndent()
        val parsed = ZypperOutputParser.parseRpmQuery(out)
        // gpg-pubkey is a key, not software.
        assertEquals(2, parsed.size)
        assertEquals("boost-license1_86_0", parsed[0].name)
        assertEquals("1.86.0-160000.2.2", parsed[0].version)
        assertEquals(1338L, parsed[0].installedSizeBytes)
        assertEquals("cracklib-dict-small", parsed[1].name)
        assertEquals(259081L, parsed[1].installedSizeBytes)
    }

    @Test
    fun rpmFallbackToleratesMissingSizeColumn() {
        val parsed = ZypperOutputParser.parseRpmQuery("bash|5.2.37-160000.2.2")
        assertEquals(1, parsed.size)
        assertNull(parsed[0].installedSizeBytes)
    }

    // ── version ────────────────────────────────────────────────────────────

    @Test
    fun parsesZypperVersion() {
        assertEquals("1.14.98", ZypperOutputParser.parseVersion("zypper 1.14.98"))
        assertNull(ZypperOutputParser.parseVersion(""))
    }
}
