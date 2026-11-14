package com.fem.linexa.domain.attribution

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §1.131 guard: Linexa's own `LICENSE` exists, is at the repository root under that exact
 * name, and is a **byte-identical** copy of the official GNU GPL v3 text.
 *
 * Why a test for a text file. §1.130's [AttributionCatalogTest] guards the mirror-image
 * obligation (every third-party project Linexa distributes is attributed); this guards
 * Linexa's own grant. The failure mode is specific and silent: `LICENSE` is the one file in
 * the repository that must never be "improved". A Markdown-lint pass, an editor configured
 * to trim trailing whitespace, a CRLF checkout on Windows, or a well-meaning reflow of the
 * long paragraphs all produce a file that still *reads* like the GPL while no longer being
 * the licence document — and §12 of the licence itself forbids altering its terms. No
 * compile step, and no other test in this repo, notices.
 *
 * Pinning the sha256 is what makes that detectable, so the constants below are load-bearing
 * rather than incidental: they are the published `https://www.gnu.org/licenses/gpl-3.0.txt`.
 * They should only ever change if the FSF publishes a new version of the document, which is
 * a deliberate relicensing decision, not a maintenance edit.
 */
class LicenseFileTest {

    private companion object {
        /** sha256 of https://www.gnu.org/licenses/gpl-3.0.txt (GPL-3.0, 29 June 2007). */
        const val GPL3_SHA256 = "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986"
        const val GPL3_BYTES = 35149L
        const val GPL3_LINES = 674

        /** Recorded in §1.131 and reproduced in `README.md`; not the FSF's placeholder. */
        const val COPYRIGHT_HOLDER = "ForExample25"
    }

    private lateinit var repoRoot: File

    /**
     * Unit tests run with the *module* directory as the working directory, so the repository
     * root has to be located by walking up. The marker is the module itself, not `.git` —
     * a source export or a vendored copy has no `.git` but still has to carry the licence.
     */
    @Before
    fun locateRepoRoot() {
        val workingDir = System.getProperty("user.dir") ?: "."
        var dir: File? = File(workingDir).absoluteFile
        while (dir != null) {
            if (File(dir, "app/src/main/java/com/fem/linexa/MainActivity.kt").exists()) {
                repoRoot = dir
                return
            }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the repository root from $workingDir")
    }

    private val licenseFile: File get() = File(repoRoot, "LICENSE")

    @Test
    fun theRepositoryRootCarriesALicenseFile() {
        assertTrue(
            "LICENSE is missing from the repository root: without it the project is " +
                "\"all rights reserved\" by default, which contradicts distributing " +
                "GPL-licensed binaries (§1.131)",
            licenseFile.isFile
        )
    }

    @Test
    fun theFileNameIsExactlyLicense() {
        // GitHub, Gradle tooling and licence scanners key on this exact name. `LICENSE.md`
        // or `COPYING.txt` is a file humans can still read and machines silently miss.
        val candidates = repoRoot.listFiles()
            ?.map { it.name }
            ?.filter { it.uppercase().startsWith("LICEN") || it.uppercase().startsWith("COPYING") }
            .orEmpty()
        assertEquals(
            "the licence must be a single file named exactly LICENSE (found: $candidates)",
            listOf("LICENSE"),
            candidates.sorted()
        )
    }

    @Test
    fun theTextIsTheVerbatimOfficialGpl3Document() {
        val bytes = licenseFile.readBytes()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

        assertEquals(
            "LICENSE is not byte-identical to https://www.gnu.org/licenses/gpl-3.0.txt. " +
                "A reflowed, EOL-normalized, whitespace-trimmed or annotated GPL is not " +
                "the GPL (§1.131, and §12 of the licence itself). If this failed after a " +
                "formatting/lint pass, restore the file rather than updating this hash.",
            GPL3_SHA256,
            digest
        )
        assertEquals("unexpected size", GPL3_BYTES, licenseFile.length())
    }

    @Test
    fun theDocumentIsPlainTextWithUnixLineEndings() {
        // Redundant with the hash, but it names the two most likely causes directly so a
        // failure is actionable instead of just "the bytes changed".
        val bytes = licenseFile.readBytes()
        assertFalse("CRLF line endings: check .gitattributes / the checkout", bytes.contains('\r'.code.toByte()))
        assertTrue("the document must end with a trailing newline", bytes.last() == '\n'.code.toByte())
        assertEquals("unexpected line count", GPL3_LINES, bytes.count { it == '\n'.code.toByte() })
    }

    @Test
    fun theDocumentStillContainsItsStructuralLandmarks() {
        // A hash mismatch says only "different". These say *what* is expected to be there,
        // so a future intentional update can be reviewed rather than just re-hashed.
        val text = licenseFile.readText()
        assertTrue(text.contains("GNU GENERAL PUBLIC LICENSE"))
        assertTrue("the version line pins v3, not v2 or the LGPL", text.contains("Version 3, 29 June 2007"))
        assertTrue(text.contains("TERMS AND CONDITIONS"))
        assertTrue(text.contains("END OF TERMS AND CONDITIONS"))
        assertTrue(text.contains("How to Apply These Terms to Your New Programs"))
    }

    @Test
    fun theLicenseTextIsNotPersonalizedWithLinexasCopyright() {
        // The GPL is a document that must be reproduced unmodified; the project's own
        // copyright line belongs in README/source headers, NOT pasted into the licence
        // text. Filling in the FSF's `<one line to give the program's name...>` template
        // inside LICENSE is the classic way this file gets altered.
        val text = licenseFile.readText()
        assertFalse(
            "LICENSE must not be edited to name the project; it is a verbatim document",
            text.contains("Linexa")
        )
        assertFalse(
            "LICENSE must not be edited to carry the copyright holder",
            text.contains(COPYRIGHT_HOLDER)
        )
    }

    @Test
    fun theReadmeStatesTheLicenseAndTheCopyrightHolder() {
        // The licence file grants the terms; the README is where a reader learns which
        // licence applies and to whom. A LICENSE nothing points at is the §1.130(entry
        // point) problem again — present, but not communicated.
        val readme = File(repoRoot, "README.md")
        assertTrue("README.md is missing", readme.isFile)
        val text = readme.readText()

        assertTrue(
            "the README must name the licence as GPL-3.0-only (§1.131)",
            text.contains("GPL-3.0-only")
        )
        assertTrue(
            "the README must carry the copyright line",
            text.contains("Copyright (C) 2026 $COPYRIGHT_HOLDER")
        )
        assertTrue(
            "the README must link the licence file",
            text.contains("(./LICENSE)")
        )
        assertTrue(
            "the GPL's warranty disclaimer must be reproduced, not just referenced",
            // Whitespace-insensitive: the README is hard-wrapped, so the phrase spans lines.
            text.replace(Regex("\\s+"), " ").contains("WITHOUT ANY WARRANTY")
        )
    }

    @Test
    fun theReadmeStatesWhyTheLicenseIsNotOptional() {
        // §1.131's rationale must survive its own history: the licence was compelled
        // by linking terminal-lib (GPL-3.0-only). That dependency is GONE since the
        // 2026-08-29 terminal migration (replaced by the vendored Apache-2.0 Termux
        // modules), so the README now records the whole story — the original binding,
        // the removal, and why the grant stands anyway (published grant + the still-
        // bundled copyleft runtime). A future "let's relicense to Apache" edit must
        // run into this record rather than quietly deleting it.
        val text = File(repoRoot, "README.md").readText()
        assertTrue(
            "the README must record that terminal-lib's GPL-3.0-only is what originally " +
                "bound this choice",
            text.contains("terminal-lib") && text.contains("GPL-3.0-only")
        )
        assertTrue(
            "the README must record that the GPL terminal dependency was removed in the " +
                "2026-08-29 migration and what replaced it",
            text.contains("2026-08-29") &&
                text.contains("Apache-2.0 Termux terminal modules")
        )
        assertTrue(
            "the -or-later asymmetry must still be explained: a combined work with the " +
                "GPL-2.0/GPL-3.0 runtime is effectively GPLv3",
            text.contains("effectively")
        )
    }

    @Test
    fun theReadmeKeepsLinexasLicenseDistinctFromTheVendoredOnes() {
        // Linexa's grant covers Linexa's source only; the vendored binaries keep theirs
        // (§1.130). Conflating the two would misstate someone else's licence.
        val text = File(repoRoot, "README.md").readText()
        assertTrue(
            "the README must point at the in-app attribution surface (§1.130)",
            text.contains("About")
        )
        assertTrue(
            "and say that third-party components keep their own licences",
            text.contains("own** license") || text.contains("own license")
        )
    }
}
