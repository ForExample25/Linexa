package com.fem.linexa.domain.packagemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.92 / §1.146 — JVM tests for the dnf output parsers.
 *
 * Fixtures are verbatim output from dnf5 5.4.2.1 (Fedora 44, aarch64) plus
 * the dnf4 dialects the parsers still accept. The previously untested
 * [DnfOutputParser.parseRepoquery] / [DnfOutputParser.parseAvailable] are the
 * paths the provider actually prefers, and dnf5's **tab**-separated search
 * output was not covered at all.
 */
class DnfOutputParserTest {

    // ── dnf list --installed (fallback table) ──────────────────────────────

    /**
     * Verbatim dnf5 `dnf list --installed`. dnf5 prints "Installed packages"
     * (lower-case p) and a bare repo id; dnf4 prints "Installed Packages" and
     * prefixes the id with `@`.
     */
    @Test
    fun parsesDnf5InstalledTable() {
        val out = """
            Installed packages
            alternatives.aarch64                     1.33-5.fc44                a864130b726c4a0bb00241ec1633424d
            audit-libs.aarch64                       4.2.1-1.fc44               54595fefa154427fae893a11f3a33fed
        """.trimIndent()
        val parsed = DnfOutputParser.parseInstalled(out)
        assertEquals(2, parsed.size)
        assertEquals("alternatives", parsed[0].name)
        assertEquals("1.33-5.fc44", parsed[0].version)
        assertEquals("a864130b726c4a0bb00241ec1633424d", parsed[0].repository)
        assertEquals("audit-libs", parsed[1].name)
    }

    /** dnf4's `@fedora` marker is stripped; the header is skipped. */
    @Test
    fun parsesDnf4InstalledTableAndStripsAtMarker() {
        val out = """
            Installed Packages
            bash.x86_64                    5.2.15-1.fc39          @fedora
            curl.x86_64                    8.5.0-1.fc39           @updates
        """.trimIndent()
        val parsed = DnfOutputParser.parseInstalled(out)
        assertEquals(2, parsed.size)
        assertEquals("fedora", parsed[0].repository)
        assertEquals("updates", parsed[1].repository)
    }

    /** The table has no size column, so none may be reported. */
    @Test
    fun installedTableReportsNoSizes() {
        val out = "bash.aarch64  5.3.9-3.fc44  fedora"
        assertNull(DnfOutputParser.parseInstalled(out).single().installedSizeBytes)
    }

    @Test
    fun installedTableSkipsHeaderAndShortLines() {
        assertTrue(DnfOutputParser.parseInstalled("Installed Packages\nbash.x86_64\nonlytwo").isEmpty())
    }

    // ── dnf repoquery --installed (primary path) ───────────────────────────

    /**
     * Verbatim `dnf repoquery --installed --queryformat
     * '%{name}|%{evr}|%{installsize}|%{from_repo}|%{group}\n'`. `installsize`
     * is already bytes. `from_repo` is an opaque build-time id for packages
     * baked into the image — reported as-is rather than blanked or renamed.
     */
    @Test
    fun parsesRepoqueryWithExactByteSizes() {
        val out = """
            alternatives|1.33-5.fc44|92240|a864130b726c4a0bb00241ec1633424d|System Environment/Base
            audit-libs|4.2.1-1.fc44|498388|54595fefa154427fae893a11f3a33fed|System Environment/Libraries
            authselect-libs|1.7.1-1.fc44|957795|a864130b726c4a0bb00241ec1633424d|Unspecified
        """.trimIndent()
        val parsed = DnfOutputParser.parseRepoquery(out)
        assertEquals(3, parsed.size)
        assertEquals("alternatives", parsed[0].name)
        assertEquals("1.33-5.fc44", parsed[0].version)
        assertEquals(92240L, parsed[0].installedSizeBytes)
        assertEquals("a864130b726c4a0bb00241ec1633424d", parsed[0].repository)
        assertEquals("System Environment/Base", parsed[0].section)
        assertEquals(957795L, parsed[2].installedSizeBytes)
    }

    /**
     * §1.147 — rpm's `Unspecified`/`(none)` group is the absence of a category,
     * so it must not be rendered as one on the card.
     */
    @Test
    fun repoqueryDropsUnspecifiedGroup() {
        assertNull(DnfOutputParser.parseRepoquery("bash|5.3.9-3.fc44|8822851|fedora|Unspecified").single().section)
        assertNull(DnfOutputParser.parseRepoquery("bash|5.3.9-3.fc44|8822851|fedora|(none)").single().section)
    }

    /** `@System` is dnf's rpmdb pseudo-repo and carries no information. */
    @Test
    fun repoqueryDropsSystemPseudoRepo() {
        val parsed = DnfOutputParser.parseRepoquery("bash|5.3.9-3.fc44|8822851|@System")
        assertNull(parsed.single().repository)
    }

    /** A zero or unparseable size is absent data, not a size of zero. */
    @Test
    fun repoqueryTreatsZeroOrBadSizeAsUnknown() {
        assertNull(DnfOutputParser.parseRepoquery("gpg-pubkey|25db7ae0-645bae34|0|").single().installedSizeBytes)
        assertNull(DnfOutputParser.parseRepoquery("weird|1.0|notanumber|fedora").single().installedSizeBytes)
    }

    /** `rpm -qa --queryformat '%{NAME}|%{EVR}|%{SIZE}\n'` shares this parser. */
    @Test
    fun repoqueryParsesRpmFallbackRowsWithoutRepoColumn() {
        val parsed = DnfOutputParser.parseRepoquery("bash|5.3.9-3.fc44|8822851")
        assertEquals(1, parsed.size)
        assertEquals(8822851L, parsed[0].installedSizeBytes)
        assertNull(parsed[0].repository)
    }

    @Test
    fun repoquerySkipsBlankAndSingleColumnLines() {
        assertTrue(DnfOutputParser.parseRepoquery("\n\nnoseparator\n").isEmpty())
    }

    // ── dnf repoquery --available (Store path) ─────────────────────────────

    /**
     * Verbatim `dnf repoquery --available --latest-limit 1 --queryformat
     * '%{name}|%{evr}|%{downloadsize}|%{installsize}|%{repoid}|%{url}|%{group}|%{summary}\n'`.
     */
    @Test
    fun parsesAvailableWithBothSizesRepoAndSummary() {
        val out = "bash|5.3.9-3.fc44|1930272|8822851|fedora|" +
            "https://www.gnu.org/software/bash/|System Environment/Shells|The GNU Bourne Again shell"
        val parsed = DnfOutputParser.parseAvailable(out)
        assertEquals(1, parsed.size)
        val bash = parsed[0]
        assertEquals("bash", bash.name)
        assertEquals("5.3.9-3.fc44", bash.latestVersion)
        assertEquals(1930272L, bash.downloadSizeBytes)
        assertEquals(8822851L, bash.installedSizeBytes)
        assertEquals("fedora", bash.repository)
        assertEquals("The GNU Bourne Again shell", bash.description)
        assertEquals("https://www.gnu.org/software/bash/", bash.homepageUrl)
        assertEquals("System Environment/Shells", bash.section)
    }

    /**
     * §1.147 — the OPEN action must only appear for a real page. rpm prints
     * `(none)` for a package that set no URL tag, which is not a URL.
     */
    @Test
    fun availableDropsRpmNoneUrlAndUnspecifiedGroup() {
        val parsed = DnfOutputParser.parseAvailable("foo|1.0-1.fc44|100|200|fedora|(none)|Unspecified|A tool")
            .single()
        assertNull(parsed.homepageUrl)
        assertNull(parsed.section)
        assertNull(parsed.openableUrl)
    }

    /** A non-http URL is never handed to the browser. */
    @Test
    fun availableOpenableUrlRequiresHttpScheme() {
        val ftp = DnfOutputParser.parseAvailable("foo|1.0|1|2|fedora|ftp://example.invalid/foo|Unspecified|A tool")
            .single()
        assertEquals("ftp://example.invalid/foo", ftp.homepageUrl)
        assertNull(ftp.openableUrl)
    }

    /**
     * The summary is the last field precisely because it can contain `|`;
     * a pipe inside it must not shift the columns or truncate the text.
     */
    @Test
    fun availableKeepsPipesInsideTheSummary() {
        val out = "foo|1.0-1.fc44|100|200|fedora|https://example.invalid|Unspecified|A tool | with a pipe | inside"
        val parsed = DnfOutputParser.parseAvailable(out)
        assertEquals("A tool | with a pipe | inside", parsed.single().description)
        assertEquals("fedora", parsed.single().repository)
    }

    // ── dnf upgrade reporting (§1.147) ─────────────────────────────────────

    /** `dnf repoquery --upgrades --queryformat '%{name}|%{evr}\n'`. */
    @Test
    fun parsesUpgradesFromQueryformatPairs() {
        val out = """
            bash|5.3.9-3.fc44
            curl|8.11.0-2.fc44
        """.trimIndent()
        val parsed = DnfOutputParser.parseUpgrades(out)
        assertEquals(mapOf("bash" to "5.3.9-3.fc44", "curl" to "8.11.0-2.fc44"), parsed)
    }

    /** `dnf check-update`'s whitespace table, whose names carry an arch suffix. */
    @Test
    fun parsesUpgradesFromCheckUpdateTable() {
        val out = """
            Last metadata expiration check: 0:01:02 ago on Mon 17 Aug 2026.

            bash.aarch64            5.3.9-3.fc44        updates
            curl.aarch64            8.11.0-2.fc44       updates
        """.trimIndent()
        val parsed = DnfOutputParser.parseUpgrades(out)
        assertEquals("5.3.9-3.fc44", parsed["bash"])
        assertEquals("8.11.0-2.fc44", parsed["curl"])
    }

    /** No upgrades means an empty map — never a package that claims one. */
    @Test
    fun upgradesEmptyWhenNothingIsUpgradable() {
        assertTrue(DnfOutputParser.parseUpgrades("").isEmpty())
        assertTrue(DnfOutputParser.parseUpgrades("Last metadata expiration check: 0:00:01 ago.").isEmpty())
    }

    /** dnf's `protected.d` conf files are a bare name list with `#` comments. */
    @Test
    fun parsesProtectedNameList() {
        val out = """
            # dnf's own protected packages
            dnf
            systemd

        """.trimIndent()
        assertEquals(setOf("dnf", "systemd"), DnfOutputParser.parseNameList(out))
    }

    // ── dnf search (both dialects) ─────────────────────────────────────────

    /**
     * Verbatim dnf5 search output: rows are indented, name and summary are
     * separated by a **tab**, and results are grouped under
     * `Matched fields:` headers.
     */
    @Test
    fun parsesDnf5TabSeparatedSearch() {
        val out = "Matched fields: name (exact)\n" +
            " bash.aarch64\tThe GNU Bourne Again shell\n" +
            "Matched fields: name, summary\n" +
            " SwayNotificationCenter-bash-completion.noarch\tBash completion files for SwayNotificationCenter\n"
        val parsed = DnfOutputParser.parseSearch(out)
        assertEquals(2, parsed.size)
        assertEquals("bash" to "The GNU Bourne Again shell", parsed[0])
        assertEquals(
            "SwayNotificationCenter-bash-completion" to "Bash completion files for SwayNotificationCenter",
            parsed[1]
        )
    }

    /** dnf4 uses ` : ` and `=====` section rules. */
    @Test
    fun parsesDnf4ColonSeparatedSearch() {
        val out = """
            =============== Name Matched: git ===============
            git.x86_64 : Fast Version Control System
            =============== Summary Matched: git ===============
            git-all.x86_64 : Meta-package for pulling in all git tools
        """.trimIndent()
        val parsed = DnfOutputParser.parseSearch(out)
        assertEquals(2, parsed.size)
        assertEquals("git" to "Fast Version Control System", parsed[0])
        assertEquals("git-all" to "Meta-package for pulling in all git tools", parsed[1])
    }

    /** dnf4's metadata notice is prose, not a result row. */
    @Test
    fun searchSkipsMetadataExpirationNotice() {
        val out = "Last metadata expiration check: 0:12:34 ago on Mon 17 Aug 2026.\n" +
            "git.x86_64 : Fast Version Control System"
        assertEquals(1, DnfOutputParser.parseSearch(out).size)
    }

    @Test
    fun searchSkipsRulesAndSeparatorlessLines() {
        assertTrue(DnfOutputParser.parseSearch("===== \nplain line with no separator\n").isEmpty())
    }

    // ── name.arch handling ────────────────────────────────────────────────

    /** Only a real arch suffix is stripped — package names contain dots too. */
    @Test
    fun stripsOnlyKnownArchSuffixes() {
        assertEquals("bash", DnfOutputParser.stripArch("bash.aarch64"))
        assertEquals("git", DnfOutputParser.stripArch("git.x86_64"))
        assertEquals("SwayNotificationCenter-bash-completion", DnfOutputParser.stripArch("SwayNotificationCenter-bash-completion.noarch"))
        // Not an arch: must survive untouched.
        assertEquals("perl-Test.pm", DnfOutputParser.stripArch("perl-Test.pm"))
        assertEquals("nodejs20.x", DnfOutputParser.stripArch("nodejs20.x"))
        assertEquals("bash", DnfOutputParser.stripArch("bash"))
    }

    // ── version ────────────────────────────────────────────────────────────

    /**
     * dnf5 prints `dnf5 version 5.4.2.1`; dnf4 prints a bare `4.14.0`. The
     * `dnf5` program-name token must not be read as the version.
     */
    @Test
    fun parsesVersionFromBothGenerations() {
        assertEquals("5.4.2.1", DnfOutputParser.parseVersion("dnf5 version 5.4.2.1\ndnf5 plugin API version 2.0"))
        assertEquals("4.14.0", DnfOutputParser.parseVersion("4.14.0"))
        assertNull(DnfOutputParser.parseVersion(""))
    }

    /** The queryformat strings the provider sends must stay in sync with the parsers. */
    @Test
    fun queryformatsMatchTheParsedColumnOrder() {
        assertEquals(
            "%{name}|%{evr}|%{installsize}|%{from_repo}|%{group}\\n",
            DnfOutputParser.INSTALLED_QUERYFORMAT
        )
        assertEquals(
            "%{name}|%{evr}|%{downloadsize}|%{installsize}|%{repoid}|%{url}|%{group}|%{summary}\\n",
            DnfOutputParser.AVAILABLE_QUERYFORMAT
        )
        // dnf5 expands \n but not \t in a queryformat, hence a pipe separator.
        assertFalse(DnfOutputParser.AVAILABLE_QUERYFORMAT.contains("\\t"))
    }
}
