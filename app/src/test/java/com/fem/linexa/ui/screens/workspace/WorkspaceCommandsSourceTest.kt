package com.fem.linexa.ui.screens.workspace

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Structural guard for the re-architected Commands section (Quick Commands
 * catalog + persistent custom commands).
 *
 * Why a source test: the section's contract is largely *wiring and
 * provenance* — that the built-in commands come from the project catalog via
 * the resolver, that the UI performs NO distribution/version selection of its
 * own, that no command text or category name lives in Kotlin UI code, that
 * the `+` saves a user command (and only running it happens through the
 * existing terminal infrastructure). None of that is reachable from a JVM
 * unit test, and a screenshot cannot show *where a string came from*. Same
 * technique and rationale as the pre-existing source guards in this package.
 *
 * The value-level rules are asserted behaviourally elsewhere and deliberately
 * not duplicated here: the hierarchy in `CommandTreeTest` +
 * `WorkspaceCommandsAssemblerTest`, the catalog rules in
 * `QuickCommandsCatalogParserTest`, the selection rules in
 * `QuickCommandsResolverTest`, persistence in `CustomCommandsTest`, and the
 * execution paths in `ShortcutsViewModelTest`.
 */
class WorkspaceCommandsSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/ui/screens/workspace/WorkspaceScreen.kt")
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            if (File(dir, marker.path).exists()) {
                moduleRoot = dir
                return
            }
            dir = dir.parentFile
        }
        moduleRoot = File(".")
    }

    private fun source(path: String): String =
        File(moduleRoot, "src/main/java/com/fem/linexa/$path").readText()

    private fun exists(path: String): Boolean =
        File(moduleRoot, "src/main/java/com/fem/linexa/$path").isFile

    /**
     * Comments are where this suite's own reasoning lives, and the KDoc on the
     * resolver necessarily *quotes* the shapes it produces to explain itself. A
     * literal-token search over raw text would therefore fail on the very
     * documentation that justifies the rule, so the hardcoding checks run over
     * code only.
     */
    private fun String.withoutComments(): String = this
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("(?<!:)//.*"), "")

    private val screen: String get() = source("ui/screens/workspace/WorkspaceScreen.kt")
    private val assembler: String get() = source("ui/screens/workspace/WorkspaceCommandsAssembler.kt")
    private val tree: String get() = source("ui/screens/workspace/CommandTree.kt")
    private val shortcutsMapper: String get() = source("ui/screens/workspace/WorkspaceShortcuts.kt")
    private val detailViewModel: String get() = source("ui/screens/workspace/WorkspaceDetailViewModel.kt")
    private val shortcutsViewModel: String get() = source("ui/screens/workspace/ShortcutsViewModel.kt")
    private val resolver: String get() = source("service/commands/QuickCommandsResolver.kt")
    private val catalogYaml: String
        get() = File(moduleRoot, "src/main/assets/commands/quick_commands.yaml").readText()

    // ── ONE catalog, owned by the project ─────────────────────────────────────

    @Test
    fun theSingleCatalogFileExistsAndIsAssetOwned() {
        val file = File(moduleRoot, "src/main/assets/commands/quick_commands.yaml")
        assertTrue("the ONE canonical catalog must exist", file.isFile)
        val text = file.readText()
        for (distro in listOf("ubuntu:", "debian:", "fedora:", "arch:", "alpine:", "common:")) {
            assertTrue("the catalog must carry the $distro branch", text.contains(distro))
        }
    }

    @Test
    fun builtInCommandsAreNoLongerHardcodedInKotlin() {
        // The old Kotlin fallback catalog is gone — commands live in the
        // project-owned catalog file now.
        val content = source("data/WorkspaceContent.kt").withoutComments()
        assertFalse("builtinShortcuts must be removed", content.contains("builtinShortcuts"))
        // And no other UI/main Kotlin file re-declares package-manager commands
        // for the Commands screen.
        for (token in listOf("apt-get install", "apt-get update &&", "pacman -Syu", "dnf install", "apk add")) {
            for (path in listOf(
                "ui/screens/workspace/WorkspaceScreen.kt",
                "ui/screens/workspace/WorkspaceDetailViewModel.kt",
                "ui/screens/workspace/WorkspaceCommandsAssembler.kt",
                "ui/screens/workspace/CommandTree.kt",
                "ui/screens/workspace/WorkspaceShortcuts.kt",
                "ui/screens/workspace/ShortcutsViewModel.kt"
            )) {
                assertFalse(
                    "$token must live in the catalog, not in $path",
                    source(path).withoutComments().contains(token)
                )
            }
        }
    }

    @Test
    fun theResolverOwnsEveryDistributionAndVersionDecision() {
        // The resolution rules (exact → most specific prefix → default; never
        // another distro's set) live in the resolver, in one place.
        assertTrue(resolver.contains("matchingRule"))
        assertTrue(resolver.contains("versionCandidates"))
        assertTrue(resolver.contains("KEY_COMMON"))
    }

    @Test
    fun theUiPerformsNoDistributionSelectionOfItsOwn() {
        // Requirement: the Commands UI must not branch on distributions. There
        // is no `if Debian` anywhere in the workspace UI files.
        for (token in listOf("\"debian\"", "\"ubuntu\"", "\"fedora\"", "\"arch\"", "\"alpine\"")) {
            assertFalse(
                "the UI must not hardcode a distro key: $token",
                screen.withoutComments().contains(token) ||
                    assembler.withoutComments().contains(token) ||
                    detailViewModel.withoutComments().contains(token)
            )
        }
    }

    // ── the old implementations are gone ──────────────────────────────────────

    @Test
    fun theLegacyTinyFlattenerIsRemoved() {
        assertFalse(
            "TinyQuickCommands must be deleted",
            exists("service/rootfs/TinyQuickCommands.kt")
        )
        // The adapter keeps presentation only — no quick_commands key anywhere.
        val adapter = source("service/rootfs/TinyMetadataAdapter.kt")
        assertFalse(
            "the adapter must no longer read quick_commands",
            adapter.withoutComments().contains("KEY_QUICK_COMMANDS") ||
                adapter.withoutComments().contains("\"quick_commands\"")
        )
        val descriptor = source("service/rootfs/RootFsDescriptor.kt")
        assertFalse(
            "legacyShortcuts must be removed from the descriptor",
            descriptor.withoutComments().contains("legacyShortcuts")
        )
    }

    @Test
    fun theDeadShortcutsTabIsRemoved() {
        assertFalse(
            "the HAVEN screen replaced ShortcutsTab; the dead file must go",
            exists("ui/screens/workspace/ShortcutsTab.kt")
        )
    }

    // ── custom-command identity and persistence ───────────────────────────────

    @Test
    fun customCommandIdentityIsTheStableIdNeverTheNameOrCommand() {
        val model = source("domain/commands/CustomCommands.kt")
        assertTrue(
            "CustomCommand carries a stable id field",
            model.contains("val id: String")
        )
        val store = source("domain/commands/CustomCommandsStore.kt")
        assertTrue(
            "an edit updates in place by id",
            store.contains("editId") && store.contains("it.id == editId")
        )
        assertTrue(
            "new records get a UUID",
            store.contains("UUID.randomUUID()")
        )
    }

    @Test
    fun customCommandsPersistInTheWorkspaceAttributesNotANewDatabase() {
        val store = source("domain/commands/CustomCommandsStore.kt")
        assertTrue(
            "persistence goes through WorkspaceRepository.updateAttributes",
            store.contains("repository.updateAttributes")
        )
        val database = source("data/room/LinexaDatabase.kt")
        assertTrue(
            "no schema change for this feature",
            database.contains("version = 1") && !database.contains("CustomCommand")
        )
    }

    @Test
    fun theCustomSectionTitleIsSourcedFromStrings() {
        assertTrue(
            "the assembler's custom-section title must come from the localized resource",
            detailViewModel.contains("R.string.workspace_commands_mine")
        )
        val strings = File(moduleRoot, "src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("<string name=\"workspace_commands_mine\">My Commands</string>"))
    }

    // ── execution stays on the ONE existing path ──────────────────────────────

    @Test
    fun customCommandsRunThroughTheExistingTerminalPath() {
        assertTrue(
            "the session comes from the existing binder/allocator path",
            shortcutsViewModel.contains("b.openTerminal(ws)") &&
                shortcutsViewModel.contains("b.openRootTerminal(ws)") &&
                shortcutsViewModel.contains("pty.write(command + \"\\n\")")
        )
        assertFalse(
            "the Commands layer must not touch the container runtime directly",
            shortcutsViewModel.contains("ContainerRuntime") || shortcutsViewModel.contains("PRoot")
        )
        assertTrue(
            "package-operation classification stays in the ONE classifier",
            shortcutsViewModel.contains("PackageOperationClassifier.isPackageOperation")
        )
    }

    @Test
    fun theOneOffRunResultCanBeDismissed() {
        assertTrue(
            "the run-once affordance keeps its dismiss path",
            shortcutsViewModel.contains("fun clearCustomRun()")
        )
    }

    // ── provenance ────────────────────────────────────────────────────────────

    @Test
    fun theDistributionLineStillComesFromTheDeclarationNotTheProbe() {
        // For a DECLARING image the card attributes the command to the
        // distribution the image declared; the probed Room row must not feed it.
        assertTrue(
            "the label must be sourced from the descriptor's declared metadata",
            detailViewModel.contains("descriptor.metadata?.distro?.displayLabel")
        )
        assertFalse(
            "the probed Room row's distro must not feed the distribution line",
            detailViewModel.contains("metadata.distro.displayLabel")
        )
    }

    @Test
    fun theDeclarationIsReadOnceForBothHalvesOfTheList() {
        assertTrue(
            "the descriptor must be held in a local and reused",
            detailViewModel.contains("val descriptor = rootFsMetadata.descriptorOf(workspaceId)")
        )
    }

    @Test
    fun theCatalogResolverIsDomainLayerAndTheLoaderHasAByteSourceSeam() {
        val loader = source("service/commands/QuickCommandsCatalogLoader.kt")
        assertTrue(
            "the loader takes the byte source as a constructor seam",
            loader.contains("private val source: QuickCommandsCatalogSource")
        )
        assertTrue(
            "the resolver must be a domain-layer singleton, not UI code",
            exists("service/commands/QuickCommandsResolver.kt")
        )
    }
}
