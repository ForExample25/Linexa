package com.fem.linexa.data

import com.fem.linexa.R
import com.fem.linexa.domain.runtime.FEATURE_DESKTOP
import com.fem.linexa.domain.runtime.FEATURE_GPU

/**
 * Static content shipped with every workspace (Phase 0: plugin-system cut).
 *
 * Replaces the ExtensionPoint/ShortcutProvider/FeatureToggleProvider machinery
 * with plain lists consumed directly by the workspace detail screen. The
 * extension-point interfaces are intentionally absent — the extension surface
 * can be reintroduced later without changing the UI layer, which only depends
 * on the [Shortcut] / [Feature] models.
 *
 * The BUILT-IN COMMANDS no longer live here. They moved to the project's own
 * Quick Commands catalog (`assets/commands/quick_commands.yaml`), resolved per
 * workspace by
 * [com.fem.linexa.service.commands.QuickCommandsResolver] — the old
 * `builtinShortcuts` list was a hard-coded, distribution-blind fallback
 * (apt-flavoured commands on every image), which is exactly what the catalog
 * replaces with per-distribution branches and version rules.
 */
object WorkspaceContent {

    /**
     * The workspace feature catalog.
     *
     * §1.117: this list contains **only features with a real mechanism behind
     * them**. Every entry here is honored by production code:
     *
     * | Feature | Honored by |
     * |---|---|
     * | `audio` | `WorkspaceAudioSupervisor` (§1.113) |
     * | `clipboard` | `WorkspaceClipboardSupervisor` (§1.112) |
     * | `ssh` | `WorkspaceFeatureSupervisor.reconcileSsh` (§1.109) |
     * | `notifications` | `WorkspaceNotificationSupervisor` (§1.111) |
     * | `storage` | `PRootContainerRuntime.isSharedStorageEnabled` (§1.82) |
     * | `desktop` | `WorkspaceCapabilities.desktopEnabled` (top-bar gating) +
     *   `DesktopViewModel` (run-mode selection and its settings) — Desktop Modes |
     *
     * The `desktop` row's **toggle** is the persisted `desktop` key
     * [com.fem.linexa.domain.workspace.WorkspaceCapabilities.desktopEnabled]
     * already reads: `false` (the default since 2026-09-02) hides the
     * Desktop entry until the user explicitly enables it, and enabling is
     * gated on a live rootfs probe that offers the guided installation flow
     * when no desktop environment exists. The WebView run mode needs no X
     * client at all, so it enables without a desktop environment. Its
     * **settings panel** chooses the run mode and that mode's parameters.
     *
     * `mic`, `gpu`, `usb`, `camera`, and `location` were removed from the v1 UI
     * per §5's own recommendation: they rendered as ordinary working switches
     * that persisted a Room row **no code ever read**, so flipping one implied
     * a capability that did not exist. They stay out of this list until a real
     * mechanism ships; §3.3 keeps them out of v1 scope. Their drawables are
     * deliberately retained for that future reintroduction.
     *
     * Adding a feature here without a consumer reintroduces exactly the
     * decorative-toggle defect §1.117 removed — wire the mechanism first.
     * [resolveFeatureToggles] carries unknown persisted keys through
     * losslessly, so a workspace that already stored one of the removed ids
     * keeps it in its map; it simply is not rendered and still is not read.
     */
    val builtinFeatures: List<Feature> = listOf(
        Feature(
            id = "audio",
            titleRes = R.string.feature_audio_title,
            descriptionRes = R.string.feature_audio_desc,
            iconRes = R.drawable.ic_haven_audio,
            enabledByDefault = true
        ),
        Feature(
            id = "clipboard",
            titleRes = R.string.feature_clipboard_title,
            descriptionRes = R.string.feature_clipboard_desc,
            iconRes = R.drawable.ic_haven_clipboard,
            enabledByDefault = true
        ),
        Feature(
            id = "ssh",
            titleRes = R.string.feature_ssh_title,
            descriptionRes = R.string.feature_ssh_desc,
            iconRes = R.drawable.ic_haven_ssh,
            enabledByDefault = false
        ),
        Feature(
            id = "notifications",
            titleRes = R.string.feature_notifications_title,
            descriptionRes = R.string.feature_notifications_desc,
            iconRes = R.drawable.ic_haven_info,
            enabledByDefault = true
        ),
        Feature(
            id = "storage",
            titleRes = R.string.feature_storage_title,
            descriptionRes = R.string.feature_storage_desc,
            iconRes = R.drawable.ic_haven_storage,
            enabledByDefault = true
        ),
        Feature(
            id = FEATURE_DESKTOP,
            titleRes = R.string.feature_desktop_title,
            descriptionRes = R.string.feature_desktop_desc,
            iconRes = R.drawable.ic_haven_monitor,
            // Desktop prerequisites (2026-09-02): Desktop is NOT enabled by
            // default. A fresh workspace starts with Desktop OFF; the user
            // enables it explicitly, and the enable is gated on a live rootfs
            // probe (WorkspaceDetailViewModel.setFeatureToggle) that offers
            // the guided installation flow when no desktop environment
            // exists. The capability attribute (ATTR_DESKTOP) is independent
            // of this toggle and is probed from the real filesystem.
            enabledByDefault = false
        ),
        Feature(
            id = FEATURE_GPU,
            titleRes = R.string.feature_gpu_title,
            descriptionRes = R.string.feature_gpu_desc,
            iconRes = R.drawable.ic_haven_gpu,
            enabledByDefault = false
        )
    )

    /**
     * The built-in feature set's id → default-enabled map, for callers that
     * need the defaults without the Compose [Feature] model (the import/UI
     * merge paths via [resolveFeatureToggles]).
     */
    val builtinFeatureDefaults: Map<String, Boolean> =
        builtinFeatures.associate { it.id to it.enabledByDefault }
}

/**
 * Resolves the *effective* feature-toggle map for a workspace: the built-in
 * defaults overlaid by a set of overrides.
 *
 * Precedence is deliberate and stable across call sites:
 * `built-in default < config.yaml linexa.features < persisted user toggle`.
 * The two import paths (ImportService/DownloadService) call this with the
 * archive's `config.yaml` `features:` block so a guest-authored archive can
 * set its own defaults; the workspace screen later re-resolves with the
 * persisted `WorkspaceMetadata.featureToggles` (which already contains those
 * config defaults) overlaid by whatever the user changed. A later overlay
 * always wins over an earlier one, so a user toggle can never be silently
 * reverted by a config default at render time.
 *
 * Override keys that match no built-in feature are kept in the result —
 * they may be features from a newer build or extension points, and the UI
 * only renders ids it knows about, so carrying them is harmless and lossless.
 *
 * Pure function on maps (no Compose dependency) so it is JVM-testable.
 */
fun resolveFeatureToggles(
    defaults: Map<String, Boolean>,
    overrides: Map<String, Boolean>
): Map<String, Boolean> {
    val result = linkedMapOf<String, Boolean>()
    defaults.forEach { (id, enabled) -> result[id] = enabled }
    overrides.forEach { (id, enabled) -> result[id] = enabled }
    return result
}
