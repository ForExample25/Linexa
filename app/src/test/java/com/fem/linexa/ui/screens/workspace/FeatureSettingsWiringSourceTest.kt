package com.fem.linexa.ui.screens.workspace

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * UI/UX Phase 6 structural guard: the feature settings are actually **wired**,
 * end to end, and the two rules that make them honest cannot be quietly removed.
 *
 * This exists because of §1.19's defect class: a panel, a ViewModel and a
 * supervisor parameter can all be correct while nothing connects them — the app
 * compiles, the UI renders, and the setting reaches no guest. A screenshot cannot
 * catch that, and this repo has no Compose UI harness (§1.124(a)), so the chain
 * is asserted against the module source the way `AboutWiringSourceTest` and
 * `SettingsWiringSourceTest` do.
 *
 * The chain that must hold:
 * `WorkspaceNavGraph` → `WorkspaceScreen` → `FeaturesTab` → the panels, and
 * separately `FeaturesTab` → `FeaturesViewModel` → the supervisors → the guest.
 * Plus the *launch* path (`WorkspaceFeatureReconciler`), which must read the same
 * persisted settings as the tab — otherwise entering a workspace silently starts
 * sshd on the default port and the tab's number is decoration.
 */
class FeatureSettingsWiringSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/ui/screens/workspace/FeaturesTab.kt")
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

    private fun source(relative: String): String = File(moduleRoot, relative).readText()

    private val navGraph: String
        get() = source("src/main/java/com/fem/linexa/ui/screens/workspace/WorkspaceNavGraph.kt")
    private val workspaceScreen: String
        get() = source("src/main/java/com/fem/linexa/ui/screens/workspace/WorkspaceScreen.kt")
    private val featuresTab: String
        get() = source("src/main/java/com/fem/linexa/ui/screens/workspace/FeaturesTab.kt")
    private val panels: String
        get() = source("src/main/java/com/fem/linexa/ui/screens/workspace/FeatureSettingsPanels.kt")
    private val detailViewModel: String
        get() = source("src/main/java/com/fem/linexa/ui/screens/workspace/WorkspaceDetailViewModel.kt")
    private val featuresViewModel: String
        get() = source("src/main/java/com/fem/linexa/ui/screens/workspace/FeaturesViewModel.kt")
    private val reconciler: String
        get() = source("src/main/java/com/fem/linexa/service/runtime/features/WorkspaceFeatureReconciler.kt")
    private val featureItem: String
        get() = source("src/main/java/com/fem/linexa/ui/components/FeatureItem.kt")

    // ── The read path: persisted value → panel ────────────────────────────

    @Test
    fun theNavGraphPassesTheResolvedSettingsAndTheWriteCallbackDown() {
        assertTrue(
            "WorkspaceNavGraph must pass the ViewModel's resolved settings into WorkspaceScreen — a panel with a locally-remembered default would look identical and persist nothing",
            navGraph.contains("featureSettings = state.featureSettings")
        )
        assertTrue(
            "and the write must land on the ViewModel that owns the repository",
            navGraph.contains("onFeatureSettingsChange = viewModel::setFeatureSettings")
        )
    }

    @Test
    fun theWorkspaceScreenForwardsBothIntoTheFeaturesTab() {
        assertTrue(
            workspaceScreen.contains("featureSettings: FeatureSettings") &&
                workspaceScreen.contains("onFeatureSettingsChange: (FeatureSettings) -> Unit")
        )
        assertTrue(
            "FeaturesTab must receive the settings, not resolve them itself",
            workspaceScreen.contains("settings = featureSettings") &&
                workspaceScreen.contains("onSettingsChange = onFeatureSettingsChange")
        )
    }

    @Test
    fun theDetailViewModelResolvesFromPersistedAttributesAndWritesByMerging() {
        assertTrue(
            "the tab's value must come from the workspace's own record through the total resolver",
            detailViewModel.contains("FeatureSettings.from(metadata.attributes)")
        )
        assertTrue(
            "and must be written through updateAttributes, which MERGES — `attributes` also holds the install source, the catalog distro id and the desktop-capability flag",
            detailViewModel.contains("repository.updateAttributes(workspaceId, settings.toAttributes())")
        )
    }

    @Test
    fun bothPanelsAreReachedFromTheFeaturesTab() {
        assertTrue(featuresTab.contains("SshSettingsPanel("))
        assertTrue(featuresTab.contains("ClipboardSettingsPanel("))
        assertTrue(
            "the panels must be handed the same controlled value/callback pair, never their own state",
            panels.contains("onSettingsChange: (FeatureSettings) -> Unit")
        )
    }

    // ── The apply path: panel → guest ─────────────────────────────────────

    @Test
    fun theTabAppliesTheAliasSelectionAndCarriesThePortIntoEverySshCall() {
        assertTrue(
            "an alias edit must reach the guest immediately (writing a shim disturbs nothing)",
            featuresTab.contains("viewModel.applyClipboardAliases(")
        )
        // The port must be carried on every path that can START sshd, or the
        // setting is honoured on one route and ignored on another.
        assertTrue(featuresTab.contains("viewModel.syncSshOnAttach(sshToggled, settings.sshPort)"))
        assertTrue(featuresTab.contains("viewModel.setSshEnabled(enabled, settings.sshPort)"))
        assertTrue(
            featuresTab.contains("viewModel.syncClipboardOnAttach(clipboardToggled, settings.selectedAliases)")
        )
        assertTrue(
            featuresTab.contains("viewModel.setClipboardEnabled(enabled, settings.selectedAliases)")
        )
    }

    @Test
    fun anAliasEditCanNeverEnableAFeatureTheUserSwitchedOff() {
        // A checkbox is reachable while the switch is off (FeatureConfig
        // .isConfigurable is deliberately independent of the toggle), so the
        // apply path must be gated — otherwise the panel is a second, hidden
        // toggle that installs shims into the guest.
        assertTrue(
            "applyClipboardAliases must refuse when the toggle is off",
            featuresViewModel.contains("if (!toggleEnabled) return")
        )
        assertTrue(
            "and the tab must pass the real toggle state, not `true`",
            featuresTab.contains("toggleEnabled = clipboardToggled")
        )
    }

    @Test
    fun theLaunchPathReadsTheSameSettingsAsTheTab() {
        // §1.110's rule, extended to configuration: if the launch reconcile used
        // defaults, entering a workspace would start sshd on 8022 no matter what
        // the tab says — and the tab would then *report* the live port, so the
        // user would see their setting silently ignored.
        assertTrue(
            reconciler.contains("FeatureSettings.from(metadata?.attributes.orEmpty())")
        )
        assertTrue(reconciler.contains("port = settings.sshPort"))
        assertTrue(reconciler.contains("aliases = settings.selectedAliases"))
    }

    // ── The two interaction rules ─────────────────────────────────────────

    @Test
    fun theRowIsExpandableWithoutTheTapEverReachingTheSwitch() {
        assertTrue(
            "the row body carries the expand click",
            featureItem.contains("onExpandedChange?.invoke(!expanded)")
        )
        assertFalse(
            "the row's click must not toggle the feature — 'configure' and 'turn on' are different intents, and conflating them disables SSH for a user reaching for its port field",
            featureItem.contains("clickable { onCheckedChange")
        )
        assertTrue(
            "the switch keeps its own handler",
            featureItem.contains("onCheckedChange = onCheckedChange")
        )
    }

    @Test
    fun onlyAWhitelistedFeatureGetsAPanel() {
        assertTrue(
            "expandability must be decided by FeatureConfig, never by a condition written in the tab",
            featuresTab.contains("FeatureConfig.isConfigurable(feature.id)")
        )
        assertTrue(
            "and the component must treat a missing panel as not-expandable rather than rendering an empty one",
            featureItem.contains("val expandable = onExpandedChange != null && settingsContent != null")
        )
    }

    @Test
    fun thePortPanelOnlyCommitsAValidValue() {
        assertTrue(
            "every keystroke goes through the pure classifier",
            panels.contains("FeatureConfig.editPort(raw)")
        )
        assertTrue(
            "and only a Valid one is persisted — the panel never stores a rejected port and never rewrites what the user typed",
            panels.contains("if (next is FeatureConfig.PortEdit.Valid)")
        )
    }

    @Test
    fun aLiveDaemonsPortIsReportedRatherThanTheSettingWhenTheyDisagree() {
        // A running sshd is deliberately not restarted to adopt a new port
        // (killing it would drop the user's open connections), so the one state
        // where setting and reality differ has to be stated.
        assertTrue(
            panels.contains("R.string.feature_settings_ssh_port_pending")
        )
        assertTrue(
            "and the live port must come from the RUNNING state, not from the setting",
            featuresTab.contains("(sshState as? SshFeatureState.Running)?.port")
        )
    }
}
