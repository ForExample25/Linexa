package com.fem.linexa.architecture

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Phase 1 regression guard — terminal rendering stays animation-free.
 *
 * The terminal is an `AndroidView` hosted by Compose. Wrapping it in
 * `AnimatedVisibility`/`AnimatedContent`, or animating its container's alpha,
 * scale or size, forces the vendored terminal view to re-measure/redraw on a
 * frame clock it does not own (and can repaint the window background during
 * the transition — the 2026-08-31 clipping defect). This is a bytecode-level
 * guard: it fails on *real typed dependencies* on `androidx.compose.animation`
 * from the two rendering classes, not on a source-string search. Aliased
 * imports, fully-qualified calls and future refactors are all visible.
 *
 * Scope is deliberately narrow:
 *
 *  - `RealTerminalViewKt` — the Compose/AndroidView interop that hosts the
 *    terminal (the rendering surface itself).
 *  - `HavenTerminalKt` — the opaque terminal surface + terminal chrome file.
 *
 * It does **not** include `WorkspaceTerminalPanelKt` or
 * `TerminalSessionOverviewKt`: those are overlay/chrome layers that
 * legitimately animate the panel's *entrance*, never the terminal view. The
 * rendering classes above must stay free of animation dependencies even
 * though their callers animate around them.
 */
class TerminalAnimationIsolationTest {

    companion object {

        private lateinit var productionClasses: JavaClasses

        /**
         * Same production-only import as `ImportBoundaryTest`: AGP puts unit
         * test classes under `…/debugUnitTest/…`, which ArchUnit's predefined
         * test filter does not recognise.
         */
        private object ExcludeAndroidUnitTestOutput : ImportOption {
            override fun includes(location: com.tngtech.archunit.core.importer.Location): Boolean =
                !location.contains("UnitTest") &&
                    ImportOption.Predefined.DO_NOT_INCLUDE_TESTS.includes(location)
        }

        private val TERMINAL_RENDERING_CLASSES = setOf(
            "com.fem.linexa.ui.screens.terminal.RealTerminalViewKt",
            "com.fem.linexa.ui.components.haven.HavenTerminalKt"
        )

        @BeforeClass
        @JvmStatic
        fun importProductionClasses() {
            // Import ONLY the two rendering classes. The previous
            // `importPackages("com.fem.linexa")` scan pulled the whole app
            // into the heap and could OOM when the full suite shares one test
            // JVM. ArchUnit still resolves these classes' dependencies from
            // the test classpath, so the animation check is unchanged.
            // `initialize = false`: loading the facade for inspection must not
            // run any class initialization.
            val loader = TerminalAnimationIsolationTest::class.java.classLoader
            val classes = TERMINAL_RENDERING_CLASSES
                .map { Class.forName(it, false, loader) }
                .toTypedArray()
            productionClasses = ClassFileImporter()
                .withImportOption(ExcludeAndroidUnitTestOutput)
                .importClasses(*classes)
        }
    }

    @Test
    fun terminalRenderingClassesExistInTheProductionScan() {
        val present = productionClasses.map { it.name }.filter { it in TERMINAL_RENDERING_CLASSES }
        assertEquals(
            "The isolated terminal surface classes moved or were renamed. Update this guard only " +
                "after confirming the new class still owns the AndroidView/terminal surface.",
            TERMINAL_RENDERING_CLASSES,
            present.toSet()
        )
    }

    @Test
    fun terminalRenderingHasNoComposeAnimationDependency() {
        val targets: List<JavaClass> = productionClasses
            .filter { it.name in TERMINAL_RENDERING_CLASSES }
            .toList()
        assertTrue("terminal rendering classes missing from the scan", targets.size == 2)

        val animationPrefix = "androidx.compose.animation"
        val offenders = targets.flatMap { target ->
            target.directDependenciesFromSelf
                .map { it.targetClass }
                .filter { it.packageName.startsWith(animationPrefix) }
                .map { dependency -> "${target.name} -> ${dependency.name}" }
        }

        assertTrue(
            "Terminal rendering must never depend on Compose animation: an animated wrapper " +
                "re-measures/redraws the vendored terminal view and can escape its clip. " +
                "Offenders: $offenders",
            offenders.isEmpty()
        )
    }
}
