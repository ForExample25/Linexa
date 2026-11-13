package com.fem.linexa.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 2026-08-29 terminal-migration guard: the GPL terminal stack is gone and the
 * vendored Apache-2.0 Termux modules are the only terminal implementation.
 *
 * ## Why a test, and why source text
 *
 * The migration's core legal claim — "no GPL terminal library remains" — is
 * exactly the kind of statement that rots silently: reverting one line of
 * `build.gradle.kts` re-imports the GPL stack and changes nothing else a
 * reviewer would notice. The build resolves dependencies at assembly time and
 * nothing else in the suite reads the build files' dependency block.
 *
 * The assertions are against **source text** (the same read-the-module-source
 * idiom as `ImportBoundaryTest` Rule 3 and `SettingsWiringSourceTest`), because
 * the claims are about declarations and imports:
 *
 *  1. the old dependency coordinate and its JitPack repository are gone from
 *     the build files;
 *  2. no production source imports the old `com.offsec.nhterm.*` packages;
 *  3. the vendored modules are present, include the pinned upstream commit in
 *     their provenance records, and carry the Apache-2.0 license text;
 *  4. `com.termux.*` imports in Linexa's own code stay confined to the two
 *     sanctioned adapter files (the bytecode-level enforcement lives in
 *     `ImportBoundaryTest`'s `com.termux` rule; this source scan additionally
 *     catches comment-adjacent slips and files the bytecode scan's import
 *     filter could miss).
 */
class TerminalStackLicenseGuardTest {

    private lateinit var moduleRoot: File
    private lateinit var repoRoot: File

    @Before
    fun locateRoots() {
        val marker = File("src/main/java/com/fem/linexa/core/security/ArchiveValidator.kt")
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, marker.path).exists()) {
                moduleRoot = dir
                break
            }
            dir = dir.parentFile
        }
        repoRoot = requireNotNull(moduleRoot.parentFile) {
            "app module root found at $moduleRoot but its parent must be the repo root"
        }
    }

    private fun appSource(relPath: String): File = File(moduleRoot, "src/main/java/$relPath")

    private fun repoFile(relPath: String): File = File(repoRoot, relPath)

    private fun mainSources(): Sequence<File> =
        File(moduleRoot, "src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }

    // ── the GPL stack is gone from the build ───────────────────────────────

    @Test
    fun theGplTerminalLibCoordinateIsGoneFromTheBuildFiles() {
        val appBuild = repoFile("app/build.gradle.kts").readText()
        // Prose comments may narrate the migration's history; a *dependency
        // declaration* may not name the old coordinate.
        val dependencyLines = appBuild.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("implementation(") || it.startsWith("api(") || it.startsWith("testImplementation(") || it.startsWith("debugImplementation(") }
            .toList()
        assertFalse(
            "the GPL terminal-lib dependency declaration must not return (offenders: " +
                dependencyLines.filter { it.contains("terminal-lib") || it.contains("tiny-computer") } + ")",
            dependencyLines.any { it.contains("terminal-lib") || it.contains("tiny-computer") }
        )
    }

    @Test
    fun theJitPackRepositoryIsGoneFromSettings() {
        val settings = repoFile("settings.gradle.kts").readText()
        assertFalse(
            "the JitPack repository existed only for terminal-lib; every remaining " +
                "dependency must resolve from google()/mavenCentral()",
            settings.contains("jitpack.io")
        )
        assertTrue(
            "the vendored terminal modules must be part of the build",
            settings.contains("include(\":terminal-emulator\")") &&
                settings.contains("include(\":terminal-view\")")
        )
    }

    @Test
    fun theAppDependsOnTheVendoredModules() {
        val appBuild = repoFile("app/build.gradle.kts").readText()
        assertTrue(
            "the app must consume the vendored Apache-2.0 terminal modules",
            appBuild.contains("project(\":terminal-emulator\")") &&
                appBuild.contains("project(\":terminal-view\")")
        )
    }

    // ── no production source imports the old packages ──────────────────────

    @Test
    fun noProductionSourceImportsTheOldGplPackages() {
        val offenders = mainSources()
            .flatMap { file ->
                file.readLines().filter { it.trimStart().startsWith("import ") }
                    .filter { it.contains("com.offsec.nhterm") }
                    .map { "${file.relativeTo(moduleRoot)}: ${it.trim()}" }
            }
            .toList()
        assertTrue(
            "com.offsec.nhterm.* was the GPL terminal-lib's namespace — every import of it " +
                "must be gone. Offenders: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun noProductionSourceNamesTheOldCoordinateOutsideProvenanceNotes() {
        // History is allowed in exactly the files whose job is to document the
        // migration (kdoc explaining what replaced what); live code is not.
        val allowed = setOf(
            "com/fem/linexa/service/runtime/pty/TermuxPtySession.kt",
            "com/fem/linexa/ui/screens/terminal/RealTerminalView.kt",
            "com/fem/linexa/LinexaApplication.kt",
            "com/fem/linexa/ui/screens/about/AboutScreen.kt",
            "com/fem/linexa/domain/attribution/AttributionCatalog.kt"
        )
        val offenders = mainSources()
            .filter { it.readText().contains("com.offsec.nhterm") }
            .map { it.relativeTo(moduleRoot).path.replace("src/main/java/", "") }
            .filterNot { it in allowed }
            .toSortedSet()
        assertTrue(
            "only migration-documenting files may name the old namespace. Offenders: $offenders",
            offenders.isEmpty()
        )
    }

    // ── com.termux imports stay confined to the sanctioned adapters ────────

    @Test
    fun comTermuxImportsAreConfinedToTheSanctionedAdapters() {
        val allowed = setOf(
            "com/fem/linexa/service/runtime/pty/TermuxPtySession.kt",
            "com/fem/linexa/service/runtime/pty/TermuxTerminalSessionProvider.kt",
            "com/fem/linexa/ui/screens/terminal/RealTerminalView.kt"
        )
        val offenders = mainSources()
            .flatMap { file ->
                file.readLines().filter { it.trimStart().startsWith("import ") }
                    .filter { it.contains("com.termux") }
                    .map { file }
            }
            .map { it.relativeTo(moduleRoot).path.replace("src/main/java/", "") }
            .filterNot { it in allowed }
            .toSortedSet()
        assertTrue(
            "com.termux.* (the vendored terminal modules) may be imported only by the PTY " +
                "implementation and the terminal screen's adapter — the UI and runtime stay " +
                "on the Linexa seams. Offenders: $offenders",
            offenders.isEmpty()
        )
    }

    // ── the vendored modules carry their license and provenance ────────────

    @Test
    fun theVendoredModulesCarryTheApacheLicense() {
        listOf("terminal-emulator", "terminal-view").forEach { module ->
            val license = repoFile("$module/LICENSE").readText()
            assertTrue(
                "$module/LICENSE must be the Apache-2.0 text",
                license.contains("Apache License") && license.contains("Version 2.0, January 2004")
            )
            assertTrue(
                "$module/NOTICE must record the upstream provenance",
                repoFile("$module/NOTICE").readText().contains("termux/termux-app")
            )
        }
    }

    @Test
    fun theVendoredSourcesArePinnedToTheRecordedUpstreamCommit() {
        val pinnedCommit = "5b657c6adf4304e5198951ce815fe0205dcac29c"
        listOf("terminal-emulator", "terminal-view").forEach { module ->
            assertTrue(
                "$module/PROVENANCE.md must record the exact upstream commit the sources " +
                    "were copied from — bumping the vendored sources without updating the " +
                    "provenance record is precisely the drift this test exists to catch",
                repoFile("$module/PROVENANCE.md").readText().contains(pinnedCommit)
            )
        }
    }

    @Test
    fun theVendoredEmulatorShipsTheUpstreamTestSuite() {
        val testDir = repoFile("terminal-emulator/src/test/java/com/termux/terminal")
        assertTrue(
            "the upstream emulator test suite must be vendored and run — it is the JVM " +
                "verification of the ANSI/CSI/cursor/scrollback behaviour we claim",
            testDir.isDirectory && testDir.listFiles()?.size ?: 0 >= 15
        )
    }
}
