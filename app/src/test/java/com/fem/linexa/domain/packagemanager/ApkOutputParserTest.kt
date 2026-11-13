package com.fem.linexa.domain.packagemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.92 / §1.146 — JVM tests for the apk output parsers.
 *
 * Fixtures are verbatim output from both apk generations still in use:
 * Alpine 3.18 / apk-tools 2.14.4 and Alpine 3.24 / apk-tools 3.0.6 (aarch64).
 * The previous fixtures asserted a `name-version (bytes)` shape for
 * `apk info -v` that apk never emits, and a first-dash name/version split
 * that breaks on every dashed package name.
 */
class ApkOutputParserTest {

    // ── name/version split ─────────────────────────────────────────────────

    /**
     * The regression this parser exists for: Alpine names contain dashes, so
     * only the trailing `<pkgver>-r<pkgrel>` is the version.
     */
    @Test
    fun splitsDashedPackageNamesAtTheReleaseSuffix() {
        assertEquals("alpine-baselayout" to "3.4.3-r1", ApkOutputParser.splitNameVersion("alpine-baselayout-3.4.3-r1"))
        assertEquals("alpine-baselayout-data" to "3.7.2-r1", ApkOutputParser.splitNameVersion("alpine-baselayout-data-3.7.2-r1"))
        assertEquals("acf-alpine-baselayout" to "0.13.3-r2", ApkOutputParser.splitNameVersion("acf-alpine-baselayout-0.13.3-r2"))
        assertEquals("apk-tools" to "2.14.4-r0", ApkOutputParser.splitNameVersion("apk-tools-2.14.4-r0"))
        assertEquals("musl" to "1.2.6-r2", ApkOutputParser.splitNameVersion("musl-1.2.6-r2"))
    }

    /** Without a trailing `-r<n>` release there is no defensible split point. */
    @Test
    fun refusesToGuessWhenReleaseSuffixIsMissing() {
        assertNull(ApkOutputParser.splitNameVersion("notapackage"))
        assertNull(ApkOutputParser.splitNameVersion("bash-5.2.21"))
        assertNull(ApkOutputParser.splitNameVersion(""))
    }

    // ── apk info -v (installed names/versions) ─────────────────────────────

    /** Verbatim `apk info -v` output — names and versions only, no sizes. */
    @Test
    fun parsesInstalledNamesAndVersions() {
        val out = """
            alpine-baselayout-3.7.2-r1
            alpine-baselayout-data-3.7.2-r1
            alpine-keys-2.6-r0
            apk-tools-3.0.6-r0
        """.trimIndent()
        val parsed = ApkOutputParser.parseInstalled(out)
        assertEquals(4, parsed.size)
        assertEquals("alpine-baselayout", parsed[0].name)
        assertEquals("3.7.2-r1", parsed[0].version)
        assertEquals("alpine-baselayout-data", parsed[1].name)
        assertEquals("apk-tools", parsed[3].name)
        assertEquals("3.0.6-r0", parsed[3].version)
    }

    /** `apk info -v` carries no size and no repository — neither is invented. */
    @Test
    fun installedListHasNoSizeOrRepository() {
        val parsed = ApkOutputParser.parseInstalled("musl-1.2.6-r2")
        assertEquals(1, parsed.size)
        assertNull(parsed[0].installedSizeBytes)
        assertNull(parsed[0].repository)
    }

    @Test
    fun installedSkipsBlankMalformedAndWarningLines() {
        val out = """
              
            notapackage
            WARNING: opening from cache https://dl-cdn.alpinelinux.org/alpine/v3.24/main/aarch64/APKINDEX.tar.gz: No such file or directory
            ERROR: unable to select packages
        """.trimIndent()
        assertTrue(ApkOutputParser.parseInstalled(out).isEmpty())
    }

    // ── apk info -s -v (sizes) ─────────────────────────────────────────────

    /** Verbatim `apk info -s -v musl busybox` — a formatted size, not bytes. */
    @Test
    fun parsesFormattedSizesFromInfoS() {
        val out = """
            busybox: 1024 KiB
            musl: 660 KiB
        """.trimIndent()
        val sizes = ApkOutputParser.parseSizes(out)
        assertEquals(2, sizes.size)
        assertEquals(1024L * 1024, sizes["busybox"])
        assertEquals(660L * 1024, sizes["musl"])
    }

    @Test
    fun convertsEachApkSizeUnitAsBinary() {
        assertEquals(6552L, ApkOutputParser.parseFormattedSize("6552 B"))
        assertEquals(906L * 1024, ApkOutputParser.parseFormattedSize("906 KiB"))
        assertEquals((1.2 * 1024 * 1024).toLong(), ApkOutputParser.parseFormattedSize("1.2 MiB"))
        assertEquals(1024L * 1024 * 1024, ApkOutputParser.parseFormattedSize("1 GiB"))
        assertNull(ApkOutputParser.parseFormattedSize(""))
        assertNull(ApkOutputParser.parseFormattedSize("nope"))
        assertNull(ApkOutputParser.parseFormattedSize("12 MB"))
    }

    // ── apk info -d -v (descriptions) ──────────────────────────────────────

    /** Verbatim `apk info -d -v musl zlib`. */
    @Test
    fun parsesDescriptionsFromInfoD() {
        val out = """
            musl: the musl c library (libc) implementation
            zlib: A compression/decompression Library
        """.trimIndent()
        val parsed = ApkOutputParser.parseDescriptions(out)
        assertEquals(2, parsed.size)
        assertEquals("the musl c library (libc) implementation", parsed["musl"])
        assertEquals("A compression/decompression Library", parsed["zlib"])
    }

    /** A size line must never be mistaken for a description. */
    @Test
    fun descriptionsRejectSizeShapedValues() {
        val parsed = ApkOutputParser.parseDescriptions("busybox: 1024 KiB\nmusl: 660 KiB")
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun descriptionsSkipLinesWithoutSeparator() {
        assertTrue(ApkOutputParser.parseDescriptions("zlib-1.3.1-r0\nno description here\n").isEmpty())
    }

    // ── apk search -v -d ───────────────────────────────────────────────────

    /** Verbatim `apk search -v bash` on 2.14.4 — dashed names throughout. */
    @Test
    fun parsesSearchWithDashedNamesAndDescriptions() {
        val out = """
            acme-redirect-bash-completion-0.6.2-r1 - Bash completions for acme-redirect
            afl++-4.06c-r0 - Fuzzer relying on genetic algorithms instead of brute force
            alacritty-bash-completion-0.12.1-r0 - Bash completions for alacritty
        """.trimIndent()
        val parsed = ApkOutputParser.parseSearch(out)
        assertEquals(3, parsed.size)
        assertEquals("acme-redirect-bash-completion", parsed[0].name)
        assertEquals("0.6.2-r1", parsed[0].latestVersion)
        assertEquals("Bash completions for acme-redirect", parsed[0].description)
        // `++` in a name must survive intact.
        assertEquals("afl++", parsed[1].name)
        assertEquals("4.06c-r0", parsed[1].latestVersion)
        assertEquals("alacritty-bash-completion", parsed[2].name)
    }

    /** Plain `apk search -v` (no `-d`) omits the description. */
    @Test
    fun parsesSearchWithoutDescriptions() {
        val parsed = ApkOutputParser.parseSearch("bash-5.3.9-r1\nzlib-1.3.2-r0")
        assertEquals(2, parsed.size)
        assertEquals("bash", parsed[0].name)
        assertEquals("5.3.9-r1", parsed[0].latestVersion)
        assertNull(parsed[0].description)
    }

    /** apk's index exposes no sizes, so search results must not claim any. */
    @Test
    fun searchResultsHaveNoSizes() {
        val parsed = ApkOutputParser.parseSearch("bash-5.3.9-r1 - The GNU Bourne Again shell")
        assertEquals(1, parsed.size)
        assertNull(parsed[0].downloadSizeBytes)
        assertNull(parsed[0].installedSizeBytes)
    }

    /**
     * apk's index carries no category/section either, so the Store card's
     * category label falls back to nothing rather than to an invented group.
     */
    @Test
    fun searchResultsHaveNoCategoryOrUrl() {
        val parsed = ApkOutputParser.parseSearch("bash-5.3.9-r1 - The GNU Bourne Again shell").single()
        assertNull(parsed.section)
        assertNull(parsed.categoryLabel)
        assertNull(parsed.homepageUrl)
        assertNull(parsed.openableUrl)
    }

    // ── apk info -w (§1.147 OPEN action) ───────────────────────────────────

    /**
     * §1.147 — verbatim `apk info -w bash musl`. apk prints a
     * `<name>-<version> webpage:` header and the URL on the following line, so
     * the name must come from the header and be stripped of its version.
     */
    @Test
    fun parsesWebpagesKeyedByPackageName() {
        val out = """
            bash-5.3.9-r1 webpage:
            https://www.gnu.org/software/bash/bash.html

            musl-1.2.6-r2 webpage:
            https://musl.libc.org/
        """.trimIndent()
        val parsed = ApkOutputParser.parseWebpages(out)
        assertEquals(2, parsed.size)
        assertEquals("https://www.gnu.org/software/bash/bash.html", parsed["bash"])
        assertEquals("https://musl.libc.org/", parsed["musl"])
    }

    /**
     * Only an absolute http(s) URL is kept — a package whose index entry has no
     * usable URL must leave the OPEN action unrendered rather than hand the
     * browser a broken link.
     */
    @Test
    fun webpagesRejectNonHttpValues() {
        val out = """
            weird-1.0-r0 webpage:
            ftp://example.invalid/weird

            alsoweird-1.0-r0 webpage:
            (none)
        """.trimIndent()
        assertTrue(ApkOutputParser.parseWebpages(out).isEmpty())
    }

    /** A header with no URL line after it contributes nothing. */
    @Test
    fun webpagesSkipHeadersWithNoFollowingUrl() {
        assertTrue(ApkOutputParser.parseWebpages("bash-5.3.9-r1 webpage:\n\n").isEmpty())
        assertTrue(ApkOutputParser.parseWebpages("").isEmpty())
    }

    // ── apk version -l '<' (§1.147 UP action) ──────────────────────────────

    /**
     * §1.147 — apk's own upgrade report. The *right-hand* (available) version
     * is what the UP action offers, and both sides are `name-version` tokens
     * whose dashed names must survive the split.
     */
    @Test
    fun parsesUpgradableFromApkVersionReport() {
        val out = """
            Installed:                Available:
            busybox-1.36.1-r5       < busybox-1.36.1-r6
            alpine-baselayout-3.4.3-r1 < alpine-baselayout-3.7.2-r1
        """.trimIndent()
        val parsed = ApkOutputParser.parseUpgradable(out)
        assertEquals(mapOf("busybox" to "1.36.1-r6", "alpine-baselayout" to "3.7.2-r1"), parsed)
    }

    /** Nothing upgradable is an empty map, so no package claims an update. */
    @Test
    fun upgradableIsEmptyWhenEverythingIsCurrent() {
        assertTrue(ApkOutputParser.parseUpgradable("Installed:                Available:").isEmpty())
        assertTrue(ApkOutputParser.parseUpgradable("").isEmpty())
        // `=` (same version) and `>` (newer than the repo) are not upgrades.
        assertTrue(ApkOutputParser.parseUpgradable("musl-1.2.6-r2 = musl-1.2.6-r2").isEmpty())
    }

    // ── apk query (apk-tools 3+) ───────────────────────────────────────────

    /**
     * Verbatim `apk query --format yaml --fields
     * name,version,description,installed-size musl zlib` on 3.0.6, including
     * the cache warnings apk writes when the index is stale and the
     * `# n items` comment.
     */
    @Test
    fun parsesQueryYamlWithExactByteSizes() {
        val out = """
            WARNING: opening from cache https://dl-cdn.alpinelinux.org/alpine/v3.24/main/aarch64/APKINDEX.tar.gz: No such file or directory
            # 2 items
            - name: musl
              version: 1.2.6-r2
              description: the musl c library (libc) implementation
              installed-size: 723480
            - name: zlib
              version: 1.3.2-r0
              description: A compression/decompression Library
              installed-size: 133008
        """.trimIndent()
        val parsed = ApkOutputParser.parseQueryYaml(out)
        assertEquals(2, parsed.size)
        assertEquals("musl", parsed[0].name)
        assertEquals("1.2.6-r2", parsed[0].version)
        assertEquals(723480L, parsed[0].installedSizeBytes)
        assertEquals("the musl c library (libc) implementation", parsed[0].description)
        assertEquals("zlib", parsed[1].name)
        assertEquals(133008L, parsed[1].installedSizeBytes)
    }

    @Test
    fun queryYamlHandlesEmptyResult() {
        assertTrue(ApkOutputParser.parseQueryYaml("# 0 items").isEmpty())
        assertTrue(ApkOutputParser.parseQueryYaml("").isEmpty())
    }

    /** apk-tools 2 rejects `apk query`, which selects the `apk info` path. */
    @Test
    fun detectsQueryUnsupportedOnApkTools2() {
        assertTrue(ApkOutputParser.isQueryUnsupported("apk: unrecognized option: fields"))
        assertTrue(ApkOutputParser.isQueryUnsupported("Usage: apk [<OPTIONS>...] COMMAND [<ARGUMENTS>...]"))
        assertTrue(!ApkOutputParser.isQueryUnsupported("# 2 items"))
    }

    // ── version ────────────────────────────────────────────────────────────

    /** Verbatim `apk --version` from both generations. */
    @Test
    fun parsesApkToolsVersion() {
        assertEquals("2.14.4", ApkOutputParser.parseVersion("apk-tools 2.14.4, compiled for aarch64."))
        assertEquals("3.0.6", ApkOutputParser.parseVersion("apk-tools 3.0.6-r0, compiled for aarch64."))
    }
}
