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

    private val DESKTOP_KEYS = listOf(ATTR_DESKTOP, "desktop.enabled")

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
