package com.fem.linexa.architecture

import com.fem.linexa.domain.runtime.CommandResult
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Phase 6a — the import-boundary guard (Phase 6b — Phase 6a's frozen debt
 * retired; the `CommandResult`/`FEATURE_STORAGE` layering fixes landed so
 * every rule below runs with **zero** `ignoreDependency(...)` / frozen-exception
 * carve-outs).
 *
 * ## What this asserts, and why it is a *test* and not a review convention
 *
 * The rule enforced here is the one `ContainerRuntime`'s own KDoc already
 * states: it is "the **internal** seam between
 * [com.fem.linexa.service.runtime.RuntimeService] and the concrete container
 * backend (§1.20)". "Internal" is the whole claim — the package holds the
 * PRoot argv building, the launcher, the tmux persistence helper and the one
 * `ContainerRuntime` implementation, and only the runtime service layer (plus
 * the DI graph that binds it) is supposed to name any of it.
 *
 * A layering claim like that decays silently. Nothing about adding
 * `import com.fem.linexa.service.runtime.container.…` to a domain file fails
 * to compile, fails a test, or looks wrong in a diff — which is exactly how
 * the two violations this test was originally written to freeze arrived
 * (Phase 6a deliberately froze rather than fixed; Phase 6b did the fix). This
 * test makes the claim executable, so the boundary can only be crossed
 * deliberately, by editing this file.
 *
 * ## Rule 1 — `domain` may not reach into the container seam
 *
     * [domainMustNotDependOnTheContainerSeam]. Encoded as a two-layer
     * [layeredArchitecture] whose considered dependencies are narrowed to the
     * seam package, so the rule means exactly "no domain class may depend on
     * anything in `service.runtime.container`". Classes outside both layers
     * (`service.*`, `ui.*`, `di.*`, `data.*`, `core.*`) are

 * deliberately unassigned and therefore unconstrained by this rule — the
 * runtime service layer and the Hilt module are the seam's legitimate callers,
 * and `ui`'s relationship to the seam is Rule 2's business, not this one's.
 *
 * ## Rule 2 — `ui` may not reach past `ContainerRuntime` to an implementation
 *
 * [uiMustNotDependOnConcreteContainerImplementations]. The UI may hold the
 * `ContainerRuntime` *interface* (it is the documented seam type) but never
 * `PRootContainerRuntime`, `PRootLauncher` or `TmuxPersist`. Those are the
 * PRoot-specific mechanics; a screen that names one has reached through two
 * layers to the backend.
 *
 * ## The `const val` blind spot, and why Rule 3 exists in source form
 *
 * ArchUnit reads **bytecode**, and the Kotlin compiler *inlines* `const val`
 * reads at the call site. A UI file that does
 * `import …container.PRootContainerRuntime` purely to name
 * `PRootContainerRuntime.FEATURE_STORAGE` therefore leaves **no trace
 * whatsoever** in its own class file — verified directly against
 * `FeatureConfig.class`, whose constant pool contains the string `"storage"`
 * and not one reference to `PRootContainerRuntime`. Rule 2 cannot see that
 * dependency and never will.
 *
 * This was not a hypothetical: it was the exact shape of one of the two
 * violations this test was originally written to freeze. Phase 6b fixed it
 * by moving `FEATURE_STORAGE` to `com.fem.linexa.domain.runtime.FeatureIds`,
 * so `FeatureConfig` no longer names `PRootContainerRuntime` at all. The
 * source-level [uiSourceMustNotImportConcreteContainerImplementations]
 * remains as the backstop, using the same read-the-module-source idiom as
 * `DebugInfoWiringSourceTest`/`FeatureSettingsWiringSourceTest` (this repo
 * has no Robolectric and no Compose UI harness, §1.124(a)). Rule 2 and
 * Rule 3 overlap on purpose: Rule 2 catches real typed usage the source scan
 * could miss (aliased imports, fully-qualified references), Rule 3 catches
 * inlined constants the bytecode scan cannot see — and that blind spot
 * applies to every future `const val` exposed from the seam, not just the
 * one Phase 6b retired.
 */
class ImportBoundaryTest {

    companion object {

        /**
         * Imported once — a full classpath scan is the expensive part, and every
         * rule below evaluates against the same snapshot.
         *
         * Scoped to `com.fem.linexa` and to **production** classes. The
         * production filter is [ExcludeAndroidUnitTestOutput] rather than
         * ArchUnit's own [ImportOption.DoNotIncludeTests]: that predefined
         * option matches plain-JVM Gradle/Maven layouts
         * (`build/classes/<lang>/test/`, `out/test/`, `target/test-classes/`),
         * and the Android Gradle Plugin puts this module's unit-test classes in
         * `build/tmp/kotlin-classes/debugUnitTest/` — which matches none of
         * them. Left on the default, every rule here reports test classes:
         * `TerminalViewModelW3Test` lives in `com.fem.linexa.ui.screens.terminal`
         * and legitimately drives a real `PRootContainerRuntime`, so Rule 2 was
         * violated 3 times by tests alone. The boundary is a claim about
         * production code; tests are free to reach for the backend.
         */
        private lateinit var productionClasses: com.tngtech.archunit.core.domain.JavaClasses

        /**
         * Drops the Android Gradle Plugin's unit-test class output
         * (`…/debugUnitTest/…`, `…/releaseUnitTest/…`, plus the ASM-transformed
         * copies AGP feeds the test runtime) while keeping `…/debug/…`
         * production output. Layered over ArchUnit's own test filter rather
         * than replacing it, so a future plain-JVM module still behaves.
         */
        private object ExcludeAndroidUnitTestOutput : ImportOption {
            override fun includes(location: com.tngtech.archunit.core.importer.Location): Boolean =
                !location.contains("UnitTest") &&
                    ImportOption.Predefined.DO_NOT_INCLUDE_TESTS.includes(location)
        }

        @BeforeClass
        @JvmStatic
        fun importProductionClasses() {
            productionClasses = ClassFileImporter()
                .withImportOption(ExcludeAndroidUnitTestOutput)
                .importPackages("com.fem.linexa")

            // A rule that scans nothing passes. Anchor on two classes that must
            // be present for any of the rules below to mean anything: the seam
            // itself, and a known domain violator.
            assertTrue(
                "the production class scan found no container seam — the import is misconfigured " +
                    "and every rule in this class would vacuously pass",
                productionClasses.any { it.name == CommandResult::class.java.name }
            )
            assertTrue(
                "the production class scan found no domain classes — the import is misconfigured",
                productionClasses.any { it.name.startsWith("com.fem.linexa.domain.") }
            )
            assertTrue(
                "the production scan must EXCLUDE unit-test classes; it included some, so the " +
                    "AGP test-output filter has stopped matching",
                productionClasses.none { it.name.endsWith("Test") }
            )
        }

        private const val CONTAINER_SEAM = "com.fem.linexa.service.runtime.container.."
        private const val DOMAIN = "com.fem.linexa.domain.."

        /**
         * Matches exactly one class *and the synthetic classes the Kotlin
         * compiler generates from its own body* (`Foo$bar$1` — suspend-lambda
         * and continuation classes), and nothing else.
         *
         * Kept on after Phase 6b retired the last frozen exception that named
         * it: the rule does not currently carry any `ignoreDependency(...)`
         * line, but the helper documents the *shape* such an exception would
         * have to take if one is ever reintroduced. A bare class-literal would
         * leave the continuations of a `suspend` member reported as
         * violations and the test red; widening to the *package* instead
         * would exempt every future file in the same package — defeating the
         * point of the test. `Foo` + `Foo$…` is the narrowest predicate that
         * means "this one class, including the code the compiler lifted out
         * of it".
         */
        private fun classWithItsOwnLambdas(fullyQualifiedName: String): DescribedPredicate<JavaClass> =
            DescribedPredicate.describe(
                "$fullyQualifiedName (and the lambda/continuation classes compiled out of its own body)"
            ) { javaClass ->
                javaClass.name == fullyQualifiedName ||
                    javaClass.name.startsWith("$fullyQualifiedName$")
            }

        /**
         * The `CommandResult` DTO itself — now living at
         * `com.fem.linexa.domain.runtime.CommandResult`. Kept as the
         * positive-presence anchor in [importProductionClasses] (the
         * `BeforeClass` sanity assertion that the production-class scan found
         * the seam vocabulary, without which every rule below would vacuously
         * pass). It was the target side of every Rule-1 exception before
         * Phase 6b; with the move into `domain.runtime`, those exceptions are
         * gone and this predicate is only used by the sanity check.
         */
        private val commandResult: DescribedPredicate<JavaClass> =
            DescribedPredicate.describe(CommandResult::class.java.name) { javaClass ->
                javaClass.name == CommandResult::class.java.name
            }

        /**
         * The PRoot-specific mechanics inside the seam. `ContainerRuntime`
         * (the interface), `RecoverySweepReport` and `TerminalKey` are
         * deliberately absent — they are the seam's public vocabulary, not
         * its implementation. (`CommandResult` was on this list until Phase
         * 6b moved it to `com.fem.linexa.domain.runtime.CommandResult`; it
         * is no longer a container-seam type at all, so the rule does not
         * need to exclude it from the implementation set.)
         */
        private val concreteContainerImplementations = listOf(
            "com.fem.linexa.service.runtime.container.PRootContainerRuntime",
            "com.fem.linexa.service.runtime.container.PRootLauncher",
            "com.fem.linexa.service.runtime.container.TmuxPersist"
        )
    }

    // ── Rule 1 ──────────────────────────────────────────────────────────────

    /**
     * `domain` must not depend on `service.runtime.container`.
     *
     * The domain layer describes *what* a package operation or a file
     * operation is; the container seam is *how* a command is run under PRoot.
     * A domain type that names a seam type cannot be reasoned about — or
     * tested — without the PRoot backend in the picture.
     */
    @Test
    fun domainMustNotDependOnTheContainerSeam() {
        val rule = layeredArchitecture()
            // BOTH packages, not just the seam. `consideringOnlyDependenciesInAnyPackage`
            // filters on *both* endpoints of a dependency, so passing only the
            // seam discards every dependency whose ORIGIN is a domain class —
            // i.e. precisely the ones this rule exists to find. That mistake
            // does not fail: it makes the rule vacuously green. It was caught
            // by compiling a deliberate new violation into
            // `domain.packagemanager` and observing the rule still pass.
            //
            // Naming both packages keeps the rule narrow in the way that
            // matters: `domain -> RuntimeBinder` and
            // `domain -> WorkspaceFileManager` (9 files plus their Dagger
            // factories) stay out of scope, since `service.runtime` itself is
            // not listed. Those are a real but separate layering question and
            // explicitly not this phase's business. — Phase 9A note
            // (2026-11-13): that "separate layering question" is now THIS
            // suite's business: the two tests under "Phase 9A rules" below
            // enforce `domain → service` (and `domain → android`) with
            // documented, narrow exceptions.
            .consideringOnlyDependenciesInAnyPackage(CONTAINER_SEAM, DOMAIN)
            .layer("Container seam").definedBy(CONTAINER_SEAM)
            .layer("Domain").definedBy(DOMAIN)
            // "Domain may not access any layer" — combined with the scoping
            // above this is exactly "no domain class may depend on anything in
            // `service.runtime.container`". Intra-layer (`domain -> domain`)
            // dependencies are not violations; ArchUnit excludes a layer's
            // access to itself. `service`, `ui`, `di`, `data` and `core` are
            // deliberately not layers here: the runtime service layer and the
            // Hilt module are the seam's legitimate callers, and `ui`'s
            // relationship to the seam is Rule 2's business.
            //
            // Expressed from the Domain side rather than as
            // `whereLayer("Container seam").mayOnlyBeAccessedByLayers()`, which
            // looks equivalent and is not: that overload rejects an empty layer
            // list outright ("At least 1 layer name must be provided").
            .whereLayer("Domain").mayNotAccessAnyLayer()

            // Phase 6b retired the seven frozen exceptions this rule used to
            // carry: `CommandResult` moved to
            // `com.fem.linexa.domain.runtime.CommandResult` (the same Phase 6a
            // precedent that moved `DEFAULT_TERMINAL_ID`), so the five
            // package-manager providers, `WorkspaceFileManagerImpl` (which
            // referenced it by fully-qualified name with no import line) and
            // `PackageManagerDetector` (which named it only via
            // local-variable type inference on `RuntimeBinder.execCommand(...)`
            // — invisible to a grep for `import …CommandResult`, fully visible
            // to this rule) now all reference a domain-package type and the
            // dependency is intra-domain, outside this rule's scope entirely.
            // No `ignoreDependency(...)` lines remain; this rule is green with
            // zero carve-outs.

            .because(
                "ContainerRuntime documents itself as the INTERNAL seam between RuntimeService " +
                    "and the PRoot backend (§1.20); the domain layer must not name anything inside it"
            )

        rule.check(productionClasses)
    }

    // ── Rule 2 ──────────────────────────────────────────────────────────────

    /**
     * `ui` may hold the `ContainerRuntime` interface, never an implementation.
     *
     * Currently green with **no** exceptions. Was green even before Phase 6b —
     * `FeatureConfig`'s pre-Phase-6b `PRootContainerRuntime` import was real
     * but invisible here, because it read an inlined `const val` (see this
     * class' KDoc); Rule 3 was what held that case. Phase 6b moved
     * `FEATURE_STORAGE` to `domain.runtime.FeatureIds`, so `FeatureConfig`
     * no longer imports `PRootContainerRuntime` at all and the gap Rule 3
     * was covering is closed at the source.
     */
    @Test
    fun uiMustNotDependOnConcreteContainerImplementations() {
        val rule = com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
            .that().resideInAPackage("com.fem.linexa.ui..")
            .should().dependOnClassesThat(
                DescribedPredicate.describe(
                    "a concrete container implementation ${concreteContainerImplementations.map { it.substringAfterLast('.') }}"
                ) { javaClass -> javaClass.name in concreteContainerImplementations }
            )
            .because(
                "the UI talks to the runtime through the ContainerRuntime interface and the " +
                    "RuntimeBinder/PtySessionSource seams; naming PRoot mechanics reaches through two layers"
            )

        rule.check(productionClasses)
    }

    // ── Rule 4 — the terminal-implementation confinement (2026-08-29) ───────

    /**
     * `com.termux.*` (the vendored Apache-2.0 Termux terminal modules) may be
     * depended on by exactly three Linexa production classes: the PTY
     * implementation, its marker/provider interface, and the terminal screen's
     * adapter. Everything else — UI, runtime, domain — stays on the Linexa
     * seams ([com.fem.linexa.service.runtime.pty.PtySession] and friends), so a
     * future terminal implementation is still a two-file swap plus this test.
     *
     * This is the 2026-08-29 counterpart of the old `com.offsec.nhterm`
     * confinement: the namespace changed (GPL terminal-lib → vendored
     * Apache-2.0 Termux modules), the boundary did not. Source-level backstop
     * lives in [TerminalStackLicenseGuardTest.comTermuxImportsAreConfinedToTheSanctionedAdapters].
     */
    @Test
    fun onlyTheSanctionedTerminalAdaptersMayDependOnTheVendoredTerminalModules() {
        val allowed = listOf(
            "com.fem.linexa.service.runtime.pty.TermuxPtySession",
            "com.fem.linexa.service.runtime.pty.TermuxTerminalSessionProvider",
            "com.fem.linexa.ui.screens.terminal.RealTerminalView"
        )

        // Manual evaluation rather than ArchRule.check(): the exemption is on the
        // ORIGIN side (the allowed adapters, the `<Name>Kt` file-classes Kotlin
        // compiles top-level functions into, and the synthetic lambda/continuation
        // classes compiled out of all of those). Hilt-generated classes may
        // reference the adapters but not com.termux types directly, so they are
        // not exempted.
        fun sanctioned(name: String): Boolean = allowed.any { ref ->
            name == ref ||
                name.startsWith("$ref$") ||
                name == "${ref}Kt" ||
                name.startsWith("${ref}Kt$")
        }

        val violations = productionClasses
            .filter { it.packageName.startsWith("com.fem.linexa") }
            .filterNot { javaClass -> sanctioned(javaClass.name) }
            .flatMap { origin ->
                origin.directDependenciesFromSelf
                    .filter { it.targetClass.name.startsWith("com.termux.") }
                    .map { "${origin.name} -> ${it.targetClass.name}" }
            }
            .toList()

        assertTrue(
            "only the sanctioned terminal adapters may import com.termux.* (the vendored " +
                "Apache-2.0 terminal modules) — a new consumer means a second leak into the " +
                "terminal implementation; the UI and runtime stay on the Linexa " +
                "PtySession/TerminalHandle seams. Offenders: $violations",
            violations.isEmpty()
        )
    }

    // ── Phase 9A rules (2026-11-13, OPT-ARCH-001) ───────────────────────────

    /**
     * The one documented `domain → service` exception: LauncherApp.kt (the
     * launcher's data model AND its catalog→app derivation object) names
     * three pure service.commands model types (`QuickActionKind`,
     * `QuickCommandNode`, `QuickNodeType`). They are plain Kotlin
     * data/sealed types (zero android imports); moving them into `domain`
     * is the mechanically correct fix but touches 12 files, 6 of them UI
     * screens outside the 9A write set — so it is RECORDED here instead of
     * silently pending. Everything else must go through the ports
     * (`domain.runtime.CommandExecutor`, `domain.workspace.RootfsInspector`).
     *
     * TWO origin classes, one file: the `LauncherApp` data class and the
     * `LauncherApps` object declared in the same file share its imports —
     * an import-line scan sees only `LauncherApp.kt`'s three lines, while
     * the bytecode names both classes. Both are excepted explicitly (the
     * first rule run caught `LauncherApps`; this list is the corrected,
     * complete version).
     */
    private val domainServiceExceptions: Map<String, Set<String>> = mapOf(
        "com.fem.linexa.domain.launcher.LauncherApp" to setOf(
            "com.fem.linexa.service.commands.QuickActionKind",
            "com.fem.linexa.service.commands.QuickCommandNode",
            "com.fem.linexa.service.commands.QuickNodeType"
        ),
        "com.fem.linexa.domain.launcher.LauncherApps" to setOf(
            "com.fem.linexa.service.commands.QuickActionKind",
            "com.fem.linexa.service.commands.QuickCommandNode",
            "com.fem.linexa.service.commands.QuickNodeType"
        )
    )

    /**
     * The documented `domain → android.*` exception: the two terminal
     * preference models carry platform value types. `TerminalFontPreference`
     * is a platform-BACKED preference model: dp↔px conversion through
     * `Context`/`TypedValue`, plus `SharedPreferences` persistence read on
     * every terminal render — the FULL surface below was completed by the
     * rule's own first run (member-access chains like
     * `context.getSharedPreferences(...)` need no import lines, so an
     * import scan under-reports; the bytecode does not).
     * `TerminalUiPreferences`' `KeyEvent` is a platform-typed API value.
     * `WorkspaceMetadata`'s `androidx.room` annotations do NOT appear here —
     * `androidx.*` is a different root from `android.*` and is not a
     * dependency on the Android framework.
     */
    private val domainAndroidExceptions: Map<String, Set<String>> = mapOf(
        "com.fem.linexa.domain.settings.TerminalFontPreference" to setOf(
            "android.content.Context",
            "android.util.TypedValue",
            "android.util.DisplayMetrics",
            "android.content.res.Resources",
            "android.content.SharedPreferences",
            "android.content.SharedPreferences\$Editor"
        ),
        "com.fem.linexa.domain.settings.TerminalUiPreferences" to setOf(
            "android.view.KeyEvent"
        )
    )

    /**
     * An origin class counts as "the same file" as its plain name if it is
     * exactly that class, a Kotlin-file class (`FooKt` for top-level
     * functions), or a synthetic lambda/continuation class the compiler
     * lifted out of its body (`Foo$…`, `FooKt$…`). Mirrors the idiom of
     * Rule 4's `sanctioned(...)` and Rule 1's `classWithItsOwnLambdas`.
     */
    private fun sameOrigin(name: String, fqn: String): Boolean =
        name == fqn ||
            name.startsWith("$fqn$") ||
            name == "${fqn}Kt" ||
            name.startsWith("${fqn}Kt$")

    /**
     * Phase 9A: `domain` may not depend on any `com.fem.linexa.service`
     * class, except the documented [domainServiceExceptions].
     *
     * This is the layering question Rule 1 deliberately scoped out (see the
     * companion KDoc's Phase 9A note): the container seam is only a slice of
     * `service`, and the domain's reach used to be `RuntimeBinder` +
     * `WorkspaceFileManager` (9 files). Phase 9A replaced those with the
     * `CommandExecutor`/`RootfsInspector` ports implemented BY the service
     * types, so a domain file that names `service.*` again has re-created
     * the cycle by hand — and this rule names it.
     *
     * Manual evaluation (Rule 4's idiom) rather than a DSL rule: the
     * exception is on BOTH sides (one origin class, three targets), and a
     * per-dependency predicate reads clearer than `ignoreDependency` chains.
     */
    @Test
    fun domainMustNotDependOnTheServiceLayer() {
        val violations = productionClasses
            .filter { it.packageName.startsWith("com.fem.linexa.domain") }
            .flatMap { origin ->
                origin.directDependenciesFromSelf
                    .filter { it.targetClass.packageName.startsWith("com.fem.linexa.service") }
                    .map { origin.name to it.targetClass.name }
            }
            .filterNot { (origin, target) ->
                domainServiceExceptions.entries.any { (exceptionOrigin, targets) ->
                    sameOrigin(origin, exceptionOrigin) && target in targets
                }
            }
            .map { "${it.first} -> ${it.second}" }
            .toList()

        assertTrue(
            "domain must not depend on com.fem.linexa.service — the domain→service cycle was " +
                "broken in Phase 9A via the CommandExecutor/RootfsInspector ports; a new domain→service " +
                "dependency re-creates the cycle by hand (documented exceptions: " +
                "$domainServiceExceptions). Offenders: $violations",
            violations.isEmpty()
        )
    }

    /**
     * Phase 9A: `domain` may not depend on `android.*` framework types,
     * except the documented [domainAndroidExceptions] (the terminal
     * preference models' platform value types).
     */
    @Test
    fun domainMustNotDependOnAndroidTypes() {
        val violations = productionClasses
            .filter { it.packageName.startsWith("com.fem.linexa.domain") }
            .flatMap { origin ->
                origin.directDependenciesFromSelf
                    .filter { it.targetClass.packageName.startsWith("android.") }
                    .map { origin.name to it.targetClass.name }
            }
            .filterNot { (origin, target) ->
                domainAndroidExceptions.entries.any { (exceptionOrigin, targets) ->
                    sameOrigin(origin, exceptionOrigin) && target in targets
                }
            }
            .map { "${it.first} -> ${it.second}" }
            .toList()

        assertTrue(
            "domain must not depend on android.* framework types — the domain layer is meant to be " +
                "reasoned about without the platform in the picture (documented exceptions: " +
                "$domainAndroidExceptions). Offenders: $violations",
            violations.isEmpty()
        )
    }

    /**
     * Phase 9A source backstop for [domainMustNotDependOnTheServiceLayer]:
     * the same const-inlining blindness that motivated Rule 3 applies here
     * (an import that only feeds an inlined `const val` leaves no bytecode
     * trace), so the import lines themselves are pinned, with exactly the
     * documented exception set.
     */
    @Test
    fun domainSourceMustNotImportServiceExceptTheDocumentedExceptions() {
        val domainSourceRoot = moduleRoot().resolve("src/main/java/com/fem/linexa/domain")
        assertTrue(
            "domain source root not found at ${domainSourceRoot.absolutePath} — this test cannot " +
                "silently pass by finding no files to scan",
            domainSourceRoot.isDirectory
        )

        val exceptions = domainServiceExceptions.values.flatten().toSet()
        val offenders = domainSourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines()
                    .filter { it.trimStart().startsWith("import com.fem.linexa.service") }
                    .map { it.trim().removePrefix("import ") }
                    .filterNot { it in exceptions }
                    .map { "${file.relativeTo(moduleRoot()).path} imports $it" }
            }
            .toList()

        assertTrue(
            "domain source must not import com.fem.linexa.service except the documented " +
                "exceptions ($exceptions). Offenders: $offenders",
            offenders.isEmpty()
        )
    }

    /**
     * Phase 9A source backstop for [domainMustNotDependOnAndroidTypes].
     */
    @Test
    fun domainSourceMustNotImportAndroidExceptTheDocumentedExceptions() {
        val domainSourceRoot = moduleRoot().resolve("src/main/java/com/fem/linexa/domain")
        assertTrue(
            "domain source root not found at ${domainSourceRoot.absolutePath} — this test cannot " +
                "silently pass by finding no files to scan",
            domainSourceRoot.isDirectory
        )

        val exceptions = domainAndroidExceptions.values.flatten().toSet()
        val offenders = domainSourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines()
                    .filter { it.trimStart().startsWith("import android.") }
                    .map { it.trim().removePrefix("import ") }
                    .filterNot { it in exceptions }
                    .map { "${file.relativeTo(moduleRoot()).path} imports $it" }
            }
            .toList()

        assertTrue(
            "domain source must not import android.* except the documented exceptions " +
                "($exceptions). Offenders: $offenders",
            offenders.isEmpty()
        )
    }

    // ── Rule 3 — the const-inlining backstop ────────────────────────────────

    /**
     * Source-level counterpart to Rule 2, covering the `const val` reads that
     * leave no bytecode trace.
     *
     * Asserted against module source for the reason spelled out in this class'
     * KDoc: the dependency being guarded is erased by the compiler before
     * ArchUnit can see it, so source text is the only place it exists.
     */
    @Test
    fun uiSourceMustNotImportConcreteContainerImplementations() {
        val uiSourceRoot = moduleRoot().resolve("src/main/java/com/fem/linexa/ui")
        assertTrue(
            "ui source root not found at ${uiSourceRoot.absolutePath} — this test cannot " +
                "silently pass by finding no files to scan",
            uiSourceRoot.isDirectory
        )

        val offenders = uiSourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val imports = file.readLines().filter { it.trimStart().startsWith("import ") }
                concreteContainerImplementations
                    .filter { impl -> imports.any { it.trim() == "import $impl" } }
                    .map { impl -> "${file.relativeTo(moduleRoot()).path} imports ${impl.substringAfterLast('.')}" }
            }
            .toList()

        // Phase 6b retired the one frozen exception this rule carried:
        // `FEATURE_STORAGE` moved to
        // `com.fem.linexa.domain.runtime.FeatureIds` (the same Phase 6a
        // precedent that moved `DEFAULT_TERMINAL_ID`), so `FeatureConfig`
        // now imports the constant from `domain.runtime` and no longer names
        // `PRootContainerRuntime` at all. The source-scan rule below is
        // green with zero carve-outs — no `frozenPhase6bExceptions` set, no
        // stale-exception guard. A future ui file that imports a concrete
        // container implementation for *any* reason fails immediately.

        assertTrue(
            "ui source must not import concrete container implementations — the UI's seam is the " +
                "ContainerRuntime interface. Offenders: $offenders",
            offenders.isEmpty()
        )
    }

    /**
     * Walks up from the working directory to the `app` module root, keyed off a
     * file that is certain to exist. Same locator idiom as
     * `DebugInfoWiringSourceTest`.
     */
    private fun moduleRoot(): java.io.File {
        val marker = "src/main/java/com/fem/linexa/core/security/ArchiveValidator.kt"
        val workingDirectory = requireNotNull(System.getProperty("user.dir")) {
            "user.dir is unset, so the module root cannot be located"
        }
        var dir: java.io.File? = java.io.File(workingDirectory)
        while (dir != null) {
            if (java.io.File(dir, marker).exists()) return dir
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the app module root from $workingDirectory")
    }
}
