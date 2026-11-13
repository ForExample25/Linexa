package com.fem.linexa.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.81: feature-toggle resolution verification.
 *
 * The feature subsystem used to be decorative — the Features tab kept
 * toggle state in a local `mutableStateMapOf`, `WorkspaceMetadata.featureToggles`
 * was created empty and never written, and config.yaml `linexa.features` was
 * parsed but never applied. [resolveFeatureToggles] is the single pure
 * resolution point behind the whole subsystem; these tests pin its precedence
 * (built-in default < config override < persisted user toggle) so a change to
 * one call site can't silently change the merge semantics of the others.
 */
class FeatureToggleResolutionTest {

    private val defaults = linkedMapOf(
        "audio" to true,
        "clipboard" to true,
        "ssh" to false,
        "storage" to true,
        "notifications" to true,
        // Desktop prerequisites (2026-09-02): Desktop is NOT enabled by
        // default — a fresh workspace starts OFF and enabling is gated on a
        // live rootfs probe.
        "desktop" to false,
        // 2026-09 GPU work: hardware GL via VirGL, default OFF (the binaries
        // are not bundled in this build; the supervisor reports a precise
        // Degraded state when the toggle is on without them).
        "gpu" to false
    )

    @Test
    fun noOverridesKeepsEveryDefault() {
        val resolved = resolveFeatureToggles(defaults, emptyMap())
        assertEquals(defaults, resolved)
        // Five original defaults + desktop (2026-08) + gpu (2026-09).
        assertEquals(7, resolved.size)
    }

    @Test
    fun configOverrideTurnsOnADefaultOffFeature() {
        val resolved = resolveFeatureToggles(defaults, mapOf("ssh" to true))
        assertTrue(resolved.getValue("ssh"))
        assertTrue(resolved.getValue("audio"))
    }

    @Test
    fun configOverrideTurnsOffADefaultOnFeature() {
        val resolved = resolveFeatureToggles(defaults, mapOf("audio" to false))
        assertFalse(resolved.getValue("audio"))
        assertTrue(resolved.getValue("clipboard"))
    }

    @Test
    fun laterOverlayAlwaysWinsOverEarlierOne() {
        // A persisted user toggle must win over the config default baked into
        // the persisted map (built-in < config < user precedence).
        val withConfig = resolveFeatureToggles(defaults, mapOf("audio" to false))
        val withUser = resolveFeatureToggles(withConfig, mapOf("audio" to true))
        assertTrue(withUser.getValue("audio"))
        assertFalse(withConfig.getValue("audio"))
    }

    @Test
    fun multipleOverridesResolveIndependently() {
        val resolved = resolveFeatureToggles(
            defaults,
            mapOf("ssh" to true, "storage" to false, "notifications" to true)
        )
        assertTrue(resolved.getValue("ssh"))
        assertFalse(resolved.getValue("storage"))
        assertTrue(resolved.getValue("notifications"))
        assertTrue(resolved.getValue("clipboard"))
    }

    @Test
    fun unknownOverrideKeysAreCarriedThroughLosslessly() {
        val resolved = resolveFeatureToggles(defaults, mapOf("camera" to true, "audio" to true))
        assertTrue(resolved.getValue("camera"))
        // GPU work (2026-09): 7 built-in defaults + the unknown "camera" override.
        assertEquals(8, resolved.size)
    }

    @Test
    fun builtinDefaultsMatchTheShippedFeatureCatalog() {
        // Keeps WorkspaceContent.builtinFeatures and its defaults map from
        // drifting apart silently — adding/removing a feature must update both.
        val catalog = WorkspaceContent.builtinFeatures
        assertEquals(catalog.size, WorkspaceContent.builtinFeatureDefaults.size)
        catalog.forEach { feature ->
            assertEquals(feature.enabledByDefault, WorkspaceContent.builtinFeatureDefaults.getValue(feature.id))
        }
    }

    /**
     * Desktop prerequisites (2026-09-02): a FRESH workspace (no persisted
     * toggles, no config overrides) must resolve Desktop to **off**. The
     * feature is not enabled by default; availability comes only from the
     * user's explicit enable, which the workspace screen gates on a live
     * rootfs capability probe.
     */
    @Test
    fun freshWorkspaceResolvesDesktopOff() {
        val resolved = resolveFeatureToggles(WorkspaceContent.builtinFeatureDefaults, emptyMap())
        assertFalse("Desktop must not be enabled by default", resolved.getValue("desktop"))
        assertFalse(
            "the shipped catalog must default Desktop to off",
            WorkspaceContent.builtinFeatures.first { it.id == "desktop" }.enabledByDefault
        )
    }

    /**
     * §1.117: the catalog must contain ONLY features with a real mechanism.
     *
     * `mic`, `gpu`, `usb`, `camera` and `location` used to render as ordinary
     * working switches that persisted a Room row no code ever read, so flipping
     * one implied a capability that did not exist (§5's own recommendation was
     * to remove or clearly label them). This is the guard: re-adding a feature
     * id here without wiring a consumer fails, rather than shipping a decorative
     * toggle again.
     *
     * The expected set is written out literally, not derived, so that widening
     * it is a deliberate edit reviewed against a real mechanism.
     */
    @Test
    fun theCatalogContainsOnlyImplementedFeatures() {
        val implemented = setOf(
            // WorkspaceAudioSupervisor (§1.113)
            "audio",
            // WorkspaceClipboardSupervisor (§1.112)
            "clipboard",
            // WorkspaceFeatureSupervisor.reconcileSsh (§1.109)
            "ssh",
            // WorkspaceNotificationSupervisor (§1.111)
            "notifications",
            // PRootContainerRuntime.isSharedStorageEnabled (§1.82)
            "storage",
            // WorkspaceCapabilities.desktopEnabled (top-bar gating) + the
            // Desktop screen's run-mode settings — Desktop Modes (2026-08)
            "desktop",
            // PRootContainerRuntime.isGpuEnabled + WorkspaceGpuSupervisor
            // (host VirGL server, launch-path env, health reporting) —
            // 2026-09 GPU work
            "gpu"
        )
        val shipped = WorkspaceContent.builtinFeatures.map { it.id }.toSet()

        assertEquals(
            "the feature catalog must contain exactly the features that have a real consumer",
            implemented,
            shipped
        )
    }

    @Test
    fun theRemovedDecorativeTogglesStayOutOfTheCatalog() {
        // `gpu` left this list in the 2026-09 GPU work: it now has a real
        // mechanism (WorkspaceGpuSupervisor + VirglHostServer) and is listed
        // in [theCatalogContainsOnlyImplementedFeatures] instead.
        val removed = listOf("mic", "usb", "camera", "location")
        val shipped = WorkspaceContent.builtinFeatures.map { it.id }

        removed.forEach { id ->
            assertFalse(
                "$id has no mechanism behind it and must not render as a toggle (§1.117)",
                shipped.contains(id)
            )
            assertFalse(
                "$id must not appear in the defaults map either",
                WorkspaceContent.builtinFeatureDefaults.containsKey(id)
            )
        }
    }

    @Test
    fun aWorkspaceThatAlreadyPersistedARemovedToggleKeepsItWithoutRendering() {
        // §1.117: removing the ids from the catalog must not corrupt an existing
        // workspace's persisted map. resolveFeatureToggles carries unknown keys
        // through losslessly; the UI simply never renders them (it iterates the
        // catalog, not the map), and nothing reads them. `gpu` was re-added in
        // the 2026-09 GPU work, so `camera` stands in for the removed id here.
        val persisted = mapOf("camera" to true, "audio" to false)

        val resolved = resolveFeatureToggles(WorkspaceContent.builtinFeatureDefaults, persisted)

        assertTrue("a legacy persisted key must survive the merge", resolved.containsKey("camera"))
        // The real feature still resolves from the persisted value.
        assertFalse(resolved.getValue("audio"))
        // And the catalog the UI renders still excludes the removed id.
        assertFalse(WorkspaceContent.builtinFeatures.any { it.id == "camera" })
    }
}
