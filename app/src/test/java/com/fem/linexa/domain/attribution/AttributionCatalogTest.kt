package com.fem.linexa.domain.attribution

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §1.130 — the catalog's job is to be *true*, so these tests check it against the two
 * files that record what is actually shipped rather than against itself.
 *
 * This is the whole mitigation for the catalog being hand-written (see
 * [AttributionCatalog]'s doc on why it cannot be generated): vendoring a binary or adding
 * a dependency without an attribution entry fails here, in CI, rather than shipping a
 * component the app does not name.
 */
class AttributionCatalogTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/MainActivity.kt")
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, marker.path).exists()) {
                moduleRoot = dir
                return
            }
            dir = dir.parentFile
        }
        moduleRoot = File(".")
    }

    private val binaryManifest: String
        get() = File(moduleRoot, "src/main/jniLibs/BINARY-MANIFEST.md").readText()

    private val buildFile: String get() = File(moduleRoot, "build.gradle.kts").readText()

    private fun licenseAsset(name: String) = File(moduleRoot, "src/main/assets/licenses/$name")

    // ── The catalog is internally coherent ─────────────────────────────────

    @Test
    fun everyComponentHasAUniqueId() {
        val ids = AttributionCatalog.all.map { it.id }
        assertEquals("component ids must be unique — they key the tests and lookups", ids.size, ids.toSet().size)
    }

    @Test
    fun everyComponentNamesAtLeastOneLicense() {
        // Enforced by AttributionComponent's init, so this asserts the construction of
        // the real catalog rather than the guard in isolation.
        AttributionCatalog.all.forEach {
            assertTrue("${it.id} names no license", it.licenses.isNotEmpty())
        }
    }

    @Test
    fun everyCopyleftComponentNamesItsSource() {
        // The obligation that actually binds: a conveyed GPL binary without its
        // corresponding source is the classic violation, and it is the one an
        // attribution screen most often misses.
        AttributionCatalog.all
            .filter { component -> component.licenses.any { it.isCopyleft } }
            .forEach { component ->
                assertFalse(
                    "${component.id} is copyleft and names no source",
                    component.sourceUrl.isNullOrBlank()
                )
            }
    }

    @Test
    fun theCopyleftComponentsAreTheOnesWeExpect() {
        // Pinned deliberately: if this set shrinks, either a license was silently
        // downgraded or a component was dropped from the catalog — both are the kind of
        // change that must be noticed rather than absorbed.
        val copyleft = AttributionCatalog.all
            .filter { component -> component.licenses.any { it.isCopyleft } }
            .map { it.id }
            .toSet()
        assertEquals(
            setOf(
                "proot", "busybox", "gnu-tar", "libacl", "libiconv", "libtalloc"
            ),
            copyleft
        )
    }

    @Test
    fun theTwoGplGrantsAreKeptApart() {
        // The distinction LicenseRef exists for: BusyBox states version 2 is the ONLY
        // version it may be distributed under, while PRoot's headers offer "version 2 or
        // (at your option) any later version". Same document, different grants.
        assertEquals(
            listOf(LicenseRef.GPL_2_0_ONLY),
            AttributionCatalog.findById("busybox")?.licenses
        )
        assertEquals(
            listOf(LicenseRef.GPL_2_0_OR_LATER),
            AttributionCatalog.findById("proot")?.licenses
        )
        // …and they still resolve to one text, which is the reason assetName is not a key.
        assertEquals(
            LicenseRef.GPL_2_0_ONLY.assetName,
            LicenseRef.GPL_2_0_OR_LATER.assetName
        )
    }

    @Test
    fun zstandardIsRecordedAsTheLicenseWeSelectedNotBoth() {
        // Zstandard is BSD-3-Clause OR GPL-2.0 at the recipient's option. Recording both
        // would read as "both apply" (which is what libandroid-support genuinely is) and
        // would import a copyleft obligation Linexa has no reason to take on.
        val zstd = AttributionCatalog.findById("zstd")
        assertEquals(listOf(LicenseRef.BSD_3_CLAUSE), zstd?.licenses)
        assertTrue(
            "the selection must be stated, or the entry looks like it ignores the GPL option",
            zstd?.note?.contains("Dual-licensed") == true
        )
    }

    @Test
    fun aComponentWithTwoRealLicensesKeepsBoth() {
        // libandroid-support carries Apache-2.0 code plus an MIT wcwidth. Collapsing to
        // one would misstate the terms of whichever half lost.
        assertEquals(
            listOf(LicenseRef.APACHE_2_0, LicenseRef.MIT),
            AttributionCatalog.findById("libandroid-support")?.licenses
        )
    }

    // ── The catalog matches what is actually shipped ───────────────────────

    @Test
    fun everyProjectInTheBinaryManifestHasAnAttributionEntry() {
        // The manifest is the pinning record for jniLibs/ (§1.25(b)/§1.71). One entry per
        // upstream project, so the mapping is stated here rather than parsed out of
        // filenames — `lib__lib__libz.so.1__.so` does not spell "zlib".
        val expected = mapOf(
            "lib__bin__busybox__.so" to "busybox",
            "lib__bin__tar__.so" to "gnu-tar",
            "lib__bin__zstd__.so" to "zstd",
            "lib__bin__tmux__.so" to "tmux",
            "lib__bin__loader__.so" to "proot",
            "libproot.so" to "proot",
            "lib__lib__libbusybox.so.1.38.0__.so" to "busybox",
            "lib__lib__libacl.so__.so" to "libacl",
            "lib__lib__libandroid-glob.so__.so" to "libandroid-glob",
            "lib__lib__libandroid-selinux.so__.so" to "libandroid-selinux",
            "lib__lib__libandroid-support.so__.so" to "libandroid-support",
            "lib__lib__libiconv.so__.so" to "libiconv",
            "lib__lib__libpcre2-8.so__.so" to "pcre2",
            "lib__lib__libzstd.so.1__.so" to "zstd",
            "lib__lib__libz.so.1__.so" to "zlib",
            "lib__lib__liblzma.so.5__.so" to "liblzma",
            "lib__lib__libncursesw.so.6__.so" to "ncurses",
            "lib__lib__libevent_core-2.1.so__.so" to "libevent",
            "lib__lib__libtalloc.so.2__.so" to "libtalloc",
            "lib__lib__libandroid-shmem.so__.so" to "libandroid-shmem"
        )
        val manifest = binaryManifest
        expected.forEach { (file, componentId) ->
            assertTrue(
                "$file is no longer in BINARY-MANIFEST.md — this mapping is stale",
                manifest.contains(file)
            )
            assertNotNull(
                "$file is shipped but no attribution entry claims it (expected id '$componentId')",
                AttributionCatalog.findById(componentId)
            )
        }
    }

    @Test
    fun theManifestContainsNoVendoredFileTheCatalogDoesNotAccountFor() {
        // The reverse direction, which is the one that catches a NEW binary: every
        // `lib…__.so` row in the manifest must be one of the files mapped above.
        val known = setOf(
            "lib__bin__busybox__.so", "lib__bin__tar__.so", "lib__bin__zstd__.so",
            "lib__bin__tmux__.so", "lib__bin__loader__.so", "libproot.so",
            "lib__lib__libbusybox.so.1.38.0__.so", "lib__lib__libacl.so__.so",
            "lib__lib__libandroid-glob.so__.so", "lib__lib__libandroid-selinux.so__.so",
            "lib__lib__libandroid-support.so__.so", "lib__lib__libiconv.so__.so",
            "lib__lib__libpcre2-8.so__.so", "lib__lib__libzstd.so.1__.so",
            "lib__lib__libz.so.1__.so", "lib__lib__liblzma.so.5__.so",
            "lib__lib__libncursesw.so.6__.so", "lib__lib__libevent_core-2.1.so__.so",
            "lib__lib__libtalloc.so.2__.so", "lib__lib__libandroid-shmem.so__.so"
        )
        // Only the two artifact tables use the `| \`file\` |` row shape.
        val rows = Regex("\\|\\s*`(lib[^`]+\\.so)`\\s*\\|")
            .findAll(binaryManifest)
            .map { it.groupValues[1] }
            .toSet()
        assertTrue("no artifact rows found — the manifest's table shape changed", rows.isNotEmpty())
        val unaccounted = rows - known
        assertTrue(
            "these vendored files have no attribution mapping: $unaccounted",
            unaccounted.isEmpty()
        )
    }

    @Test
    fun everyPackagedDependencyHasAnAttributionEntry() {
        // Keyed on the coordinate as written in build.gradle.kts, so bumping a version
        // does not break the test but *removing* an attribution entry does.
        val expected = mapOf(
            // 2026-08-29: the GPL terminal-lib coordinate is gone; the vendored
            // Apache-2.0 Termux terminal modules are Gradle module dependencies.
            "project(\":terminal-emulator\")" to "termux-terminal-emulator",
            "project(\":terminal-view\")" to "termux-terminal-view",
            "com.github.luben:zstd-jni" to "zstd-jni",
            "org.apache.commons:commons-compress" to "commons-compress",
            "org.yaml:snakeyaml" to "snakeyaml",
            "org.tukaani:xz" to "xz-java",
            "com.google.dagger:hilt-android" to "hilt",
            "org.jetbrains.kotlinx:kotlinx-serialization-json" to "kotlinx-serialization",
            "org.jetbrains.kotlinx:kotlinx-coroutines-android" to "kotlinx-coroutines",
            "androidx.room:room-runtime" to "androidx",
            "androidx.navigation:navigation-compose" to "androidx",
            "androidx.compose.material3:material3" to "compose"
        )
        val build = buildFile
        expected.forEach { (coordinate, componentId) ->
            assertTrue(
                "$coordinate is no longer a dependency — this mapping is stale",
                build.contains(coordinate)
            )
            assertNotNull(
                "$coordinate is packaged but no attribution entry claims it",
                AttributionCatalog.findById(componentId)
            )
        }
    }

    @Test
    fun testScopeDependenciesAreNotAttributed() {
        // Attribution follows distribution. JUnit/Mockito/coroutines-test and the JVM
        // zstd-jni jar never reach the APK, and listing them would make the list
        // unfalsifiable — a reader could no longer tell which entries are bytes on their
        // device.
        val names = AttributionCatalog.all.map { it.name.lowercase() }
        listOf("junit", "mockito").forEach { testOnly ->
            assertFalse(
                "$testOnly is test-scope only and must not be attributed",
                names.any { it.contains(testOnly) }
            )
        }
    }

    // ── Licenses and their texts ───────────────────────────────────────────

    @Test
    fun everyReferencedLicenseWithATextHasThatTextBundled() {
        AttributionCatalog.licenses.forEach { ref ->
            val asset = ref.assetName ?: return@forEach
            val file = licenseAsset(asset)
            assertTrue("${ref.spdxId} references a missing asset: $asset", file.isFile)
            assertTrue("${asset} is empty", file.length() > 0L)
        }
    }

    @Test
    fun noLicenseTextIsBundledThatNothingReferences() {
        // A stray document is dead weight in the APK and, worse, implies an obligation
        // Linexa does not have.
        val referenced = AttributionCatalog.licenses.mapNotNull { it.assetName }.toSet()
        val present = File(moduleRoot, "src/main/assets/licenses")
            .listFiles()
            ?.map { it.name }
            ?.toSet()
            .orEmpty()
        assertEquals(
            "bundled license texts and referenced ones must agree",
            referenced,
            present
        )
    }

    @Test
    fun theLicenseListIsBuiltFromTheCatalogNotFromTheEnum() {
        // An enum constant nothing references is not a license Linexa is bound by. This
        // asserts both directions: every entry is referenced, and nothing referenced is
        // missing.
        assertEquals(
            AttributionCatalog.licenses.toSet(),
            AttributionCatalog.all.flatMap { it.licenses }.toSet()
        )
        LicenseRef.entries.forEach { ref ->
            val referenced = AttributionCatalog.all.any { ref in it.licenses }
            assertEquals(
                "${ref.spdxId} is ${if (referenced) "referenced but absent from" else "unreferenced but present in"} the license list",
                referenced,
                ref in AttributionCatalog.licenses
            )
        }
    }

    @Test
    fun bundledBinariesComeFirst() {
        // The vendored native runtime carries the obligations that bind; it must not be
        // buried under a wall of Apache-2.0 Google libraries.
        assertEquals(AttributionKind.BUNDLED_BINARY, AttributionCatalog.all.first().kind)
        val lastBundled = AttributionCatalog.all.indexOfLast { it.isVendoredBinary }
        val firstDependency =
            AttributionCatalog.all.indexOfFirst { it.kind == AttributionKind.APP_DEPENDENCY }
        assertTrue("bundled entries must precede dependencies", lastBundled < firstDependency)
    }

    @Test
    fun everySectionIsNonEmpty() {
        AttributionKind.entries.forEach { kind ->
            assertTrue(
                "$kind has no components — an empty section would render as a bare heading",
                AttributionCatalog.byKind(kind).isNotEmpty()
            )
        }
    }

    @Test
    fun theComponentCountsAreThoseTheDocsClaim()  {
        // Pinned because several doc comments and the roadmap state these numbers, and a
        // count that has quietly drifted makes the surrounding rationale ("20 artifacts
        // from 17 projects") read as an estimate when it is meant as a fact.
        // 2026-08-29: 11 → 12 APP_DEPENDENCY rows — the GPL terminal-lib entry was
        // replaced by the two vendored Apache-2.0 Termux terminal module entries.
        // BUNDLED_ICON corrected 2 → 4 and the total 30 → 33: the catalog had been
        // carrying four icon sets (material-symbols, devicon, simple-icons,
        // distro-logos) while the count pin still claimed two — pre-existing drift
        // the terminal migration surfaced.
        assertEquals(17, AttributionCatalog.byKind(AttributionKind.BUNDLED_BINARY).size +
            AttributionCatalog.byKind(AttributionKind.BUNDLED_LIBRARY).size)
        assertEquals(12, AttributionCatalog.byKind(AttributionKind.APP_DEPENDENCY).size)
        assertEquals(4, AttributionCatalog.byKind(AttributionKind.BUNDLED_ICON).size)
        assertEquals(33, AttributionCatalog.all.size)
    }

    @Test
    fun lookupIsTotal() {
        assertNotNull(AttributionCatalog.findById("proot"))
        assertEquals(null, AttributionCatalog.findById("not-a-component"))
        assertEquals(null, AttributionCatalog.findById(null))
        assertEquals(null, AttributionCatalog.findById(""))
    }
}
