package com.fem.linexa.domain.workspace

/**
 * Resolution of a workspace's real capabilities from its persisted metadata
 * (§ UI consistency pass — "never show a fake Desktop button").
 *
 * Today the only capability we gate is **desktop** (VNC). There is no
 * persisted desktop-capability flag yet (`WorkspaceMetadata.desktopSettings`
 * is never populated), so we resolve it honestly from what *is* persisted:
 * - `featureToggles["desktop"]` / `featureToggles["desktop.enabled"]` — set
 *   from the `linexa.features` block of the workspace's config.yaml at
 *   import, or by the Features tab toggle.
 * - `attributes["desktop"]` — a structured attribute, if one is ever written.
 *
 * Everything defaults to **off**. Do not infer capability from display-name
 * string matching or distro name guessing — that is how fake buttons creep in.
 */
object WorkspaceCapabilities {

    /**
     * Persisted attribute key recording whether a workspace's rootfs contains
     * a supported package manager (the dependency Xvnc auto-install needs).
     * Written by install/import success paths and the startup backfill
     * ([com.fem.linexa.service.installer.WorkspaceMetadataBackfillManager]);
     * read here via [desktopEnabled].
     */
    const val ATTR_DESKTOP = "desktop"

    /**
     * Persisted **intent** marker: the user (or the imported `config.yaml`)
     * explicitly chose the desktop on or off. Written by
     * [com.fem.linexa.ui.screens.workspace.WorkspaceDetailViewModel.setFeatureToggle]
     * and by the import path only when `linexa.features` declares a desktop
     * key.
     *
     * Why this exists: import persists the *defaults-merged* toggle map, so
     * `featureToggles["desktop"] == false` is the default for every imported
     * workspace and cannot be told apart from an explicit "off". Treating the
     * bare default as a choice defeated auto-detection for every import
     * (Desktop stayed hidden even when the rootfs really carried a desktop).
     * [desktopAvailable] therefore honours an off toggle only when this marker
     * records that a choice was actually made.
     */
    const val ATTR_DESKTOP_INTENT = "desktop.enabled"

    private val DESKTOP_KEYS = listOf(ATTR_DESKTOP, ATTR_DESKTOP_INTENT)

    fun desktopEnabled(workspace: WorkspaceMetadata): Boolean {
        val toggles = workspace.featureToggles
        for (key in DESKTOP_KEYS) {
            when (toggles[key]) {
                true -> return true
                false -> return false
                null -> Unit
            }
        }
        for (key in DESKTOP_KEYS) {
            when (workspace.attributes[key]) {
                "true", "1", "yes", "enabled" -> return true
                "false", "0", "no", "disabled" -> return false
                else -> Unit
            }
        }
        return false
    }

    fun desktopEnabled(
        featureToggles: Map<String, Boolean>,
        attributes: Map<String, String> = emptyMap()
    ): Boolean = desktopEnabledFor(featureToggles, attributes)

    /**
     * 2026-09-02 (desktop prerequisites, Phase 2): the effective Desktop
     * *button* condition — user intent AND actual desktop capability.
     *
     * [desktopEnabled] resolves the persisted *intent* (toggle wins over the
     * capability attribute, as pinned by its tests). But a persisted toggle
     * is not proof a usable desktop exists: a user may have enabled Desktop
     * on a workspace whose rootfs carries no session launcher, or removed
     * the desktop afterwards. The button therefore lights only when BOTH
     * hold — the intent resolution above AND the capability attribute
     * ([ATTR_DESKTOP] == "true", written from the live rootfs by the
     * install/import paths and refreshed by the startup backfill).
     *
     * When this returns false the user keeps a clear route to fix it: the
     * Features tab's desktop toggle still works as the intent surface and
     * offers the guided installation flow when the capability is missing.
     * Nothing here auto-installs anything.
     */
    fun desktopUsable(
        featureToggles: Map<String, Boolean>,
        attributes: Map<String, String> = emptyMap()
    ): Boolean =
        desktopEnabledFor(featureToggles, attributes) &&
            attributes[ATTR_DESKTOP] == "true"

    /**
     * Whether the Desktop action is available for a VNC-mode workspace, given
     * the rootfs's live [capable] result.
     *
     * Previously the action required an explicit "intent" toggle that defaults
     * to OFF, so an imported rootfs that already contained a full desktop still
     * showed Desktop disabled until the user found and flipped it. Now:
     * - not [capable] → never available (a toggle cannot conjure a desktop);
     * - an explicit ON — a persisted `true` (defaults are off, so it can only
     *   come from the user or `config.yaml`) or an `desktop.enabled` attribute
     *   — is honoured;
     * - an explicit OFF — a persisted `false` **only when** [ATTR_DESKTOP_INTENT]
     *   records that a choice was made — is honoured in the other direction;
     * - no explicit choice → capability alone decides.
     *
     * A bare persisted `false` is NOT a choice: import stores the
     * defaults-merged map, so it is the default value on every imported
     * workspace. See [ATTR_DESKTOP_INTENT].
     *
     * [persistedToggles] must be the workspace's persisted overrides, NOT the
     * defaults-merged map (a default is not a choice). The `desktop` attribute
     * is the capability cache, never an intent signal, so it is not read here.
     */
    fun desktopAvailable(
        persistedToggles: Map<String, Boolean>,
        attributes: Map<String, String>,
        capable: Boolean
    ): Boolean {
        if (!capable) return false
        for (key in DESKTOP_KEYS) {
            when (persistedToggles[key]) {
                true -> return true
                false -> Unit
                null -> Unit
            }
        }
        return when (attributes[ATTR_DESKTOP_INTENT]) {
            "false", "0", "no", "disabled" -> false
            else -> true
        }
    }

    /**
     * The [ATTR_DESKTOP_INTENT] value to persist for an imported workspace:
     * `"true"`/`"false"` only when `config.yaml`'s `linexa.features` block
     * explicitly declares a desktop key, otherwise `null` (no choice — let
     * capability auto-detection decide).
     *
     * [declaredFeatures] must be the **raw** config overrides, never the
     * defaults-merged map.
     */
    fun desktopIntentAttribute(declaredFeatures: Map<String, Boolean>): String? =
        DESKTOP_KEYS.firstNotNullOfOrNull { declaredFeatures[it] }?.toString()

    private fun desktopEnabledFor(
        featureToggles: Map<String, Boolean>,
        attributes: Map<String, String>
    ): Boolean {
        for (key in DESKTOP_KEYS) {
            when (featureToggles[key]) {
                true -> return true
                false -> return false
                null -> Unit
            }
        }
        for (key in DESKTOP_KEYS) {
            when (attributes[key]) {
                "true", "1", "yes", "enabled" -> return true
                "false", "0", "no", "disabled" -> return false
                else -> Unit
            }
        }
        return false
    }
}
