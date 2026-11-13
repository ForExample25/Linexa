package com.fem.linexa.ui.screens.workspace

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §26/§27 structural guard: the workspace Network settings are actually
 * **wired**, end to end, and the two rules that make them honest cannot be
 * quietly removed.
 *
 * This exists because of §1.19's defect class: a panel, a ViewModel and a
 * screen parameter can all be correct while nothing connects them — the app
 * compiles, the UI renders, and the setting reaches no workspace record. A
 * screenshot cannot catch that, and this repo has no Compose UI harness, so
 * the chain is asserted against the module source the way
 * `FeatureSettingsWiringSourceTest` does for the sibling surface.
 *
 * The chain that must hold (HAVEN rebuild: the tab is
 * `HavenWorkspaceSettingsTab` inside the Settings segment):
 * `WorkspaceDetailViewModel` (resolved state + merge-write) →
 * `WorkspaceNavGraph` → `WorkspaceScreen` → `HavenWorkspaceSettingsTab` → the
 * panel, whose commits pass through `NetworkSettings.validateDnsServers` /
 * `validateMirrorUrl` so the persisted `network.*` attributes are only ever a
 * value the validators accepted.
 */
class NetworkSettingsPanelWiringSourceTest {

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

    private fun source(relative: String): String = File(moduleRoot, relative).readText()

    private val panel: String
        get() = source("src/main/java/com/fem/linexa/ui/screens/workspace/NetworkSettingsPanel.kt")
    private val settingsTab: String
        get() = source("src/main/java/com/fem/linexa/ui/screens/workspace/HavenWorkspaceSettingsTab.kt")
    private val detailViewModel: String
        get() = source("src/main/java/com/fem/linexa/ui/screens/workspace/WorkspaceDetailViewModel.kt")

    // ── The panel: exists, is controlled, and validates on the write path ──

    @Test
    fun theNetworkPanelExistsAndIsControlledWithValidatedWrites() {
        assertTrue(
            "NetworkSettingsPanel.kt must exist next to the other workspace settings sources",
            File(
                moduleRoot,
                "src/main/java/com/fem/linexa/ui/screens/workspace/NetworkSettingsPanel.kt"
            ).exists()
        )
        assertTrue(
            "the panel receives the resolved settings from its caller — a panel with its own state would look identical and persist nothing",
            panel.contains("onSettingsChange")
        )
        assertTrue(
            "every DNS edit must go through the pure validator — an invalid entry must be refused where a person can be told, not persisted and silently ignored at start",
            panel.contains("validateDnsServers(")
        )
        assertTrue(
            "both mirror URLs must go through the same structural guard before they can end up in guest shell commands or download requests",
            panel.contains("validateMirrorUrl(")
        )
    }

    // ── The tab: renders the panel and the persistence row ────────────────

    @Test
    fun theSettingsTabRendersTheNetworkPanelAndThePersistenceRow() {
        assertTrue(
            "the Settings tab must compose the Network panel — a panel nothing renders is dead code",
            settingsTab.contains("NetworkSettingsPanel(")
        )
        assertTrue(
            "and the terminal session-persistence row must sit on the same tab, bound to the workspace's FeatureSettings",
            settingsTab.contains("settings_persistence_label")
        )
    }

    // ── The ViewModel: resolved state + merge-write through updateAttributes ──

    @Test
    fun theDetailViewModelResolvesAndPersistsNetworkSettingsByMerging() {
        assertTrue(
            "the ViewModel must own a setNetworkSettings entry point mirroring setFeatureSettings",
            detailViewModel.contains("fun setNetworkSettings(")
        )
        assertTrue(
            "the network settings type must flow through the detail ViewModel (resolved into the UI state)",
            detailViewModel.contains("NetworkSettings")
        )
        assertTrue(
            "the write must land through updateAttributes, which MERGES — `attributes` also holds the install source, the catalog distro id and the desktop-capability flag",
            detailViewModel.contains("updateAttributes(workspaceId")
        )
        assertTrue(
            "the persistence toggle overlays the one field on the resolved FeatureSettings and writes the full encoding back",
            detailViewModel.contains("terminalPersistence")
        )
    }
}
