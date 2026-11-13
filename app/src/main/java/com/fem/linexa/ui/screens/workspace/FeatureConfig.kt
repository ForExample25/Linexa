package com.fem.linexa.ui.screens.workspace

import com.fem.linexa.domain.runtime.FEATURE_DESKTOP
import com.fem.linexa.domain.runtime.FEATURE_GPU
import com.fem.linexa.domain.runtime.FEATURE_STORAGE
import com.fem.linexa.service.runtime.features.AudioBridgeProvisioner
import com.fem.linexa.service.runtime.features.ClipboardBridgeProvisioner
import com.fem.linexa.service.runtime.features.ClipboardAliasKind
import com.fem.linexa.service.runtime.features.DesktopRunMode
import com.fem.linexa.service.runtime.features.FeatureSettings
import com.fem.linexa.service.runtime.features.NotifySendProvisioner
import com.fem.linexa.service.runtime.features.SshdProvisioner

/**
 * Everything the Features tab's *configuration* half decides, as pure functions
 * (UI/UX Phase 6).
 *
 * Same rationale as [com.fem.linexa.ui.components.WorkspacePerformanceContent]
 * and `WorkspaceCardContent`: this repo has no Compose UI harness and no
 * Robolectric (§1.124(a)), so a rule expressed as an `if` inside a `@Composable`
 * is only ever checked by looking at a screenshot. And the rules here are
 * precisely the ones a screenshot cannot check — *which* features may expand at
 * all, and whether a port the user typed is one the backend can actually bind.
 */
object FeatureConfig {

    /**
     * The features that have an expandable configuration panel.
     *
     * A **whitelist of exactly the backends that take a parameter**, which is
     * the mechanism enforcing Phase 6's "do not add configuration options that
     * are not actually supported by the backend": a UI that derived
     * expandability from anything else — a per-feature `configurable` flag, or
     * simply which rows a developer remembered to make tappable — could drift
     * into offering a panel for Audio, Shared Storage or Notifications, none of
     * which parameterise anything (the audit is recorded in
     * [FeatureSettings]'s KDoc). Adding an id here without a real parameter
     * behind it is the failure mode; `FeatureConfigTest` pins the set.
     *
     * Desktop Modes (2026-08) adds `desktop` as the third: its panel's every
     * field is read by [com.fem.linexa.ui.screens.desktop.DesktopViewModel] at
     * desktop-start time (run mode, resolution preset, ZRLE opt-in, optional
     * VNC password, web port and command), so it is a parameterised backend in
     * exactly the sense the whitelist demands.
     */
    val CONFIGURABLE_FEATURE_IDS: Set<String> = setOf(
        SshdProvisioner.FEATURE_SSH,
        ClipboardBridgeProvisioner.FEATURE_CLIPBOARD,
        FEATURE_DESKTOP
    )

    /**
     * Whether [featureId]'s row can be expanded to reveal settings.
     *
     * Deliberately independent of the feature's *toggle*: a disabled feature's
     * settings stay readable and editable, because choosing a port before
     * switching SSH on is the natural order, and hiding the panel would make the
     * setting reachable only in the state where changing it restarts nothing.
     */
    fun isConfigurable(featureId: String): Boolean = featureId in CONFIGURABLE_FEATURE_IDS

    /**
     * The one-line summary shown on a configurable row, so the current
     * configuration is legible **without** expanding it.
     *
     * Null for a feature with no panel — the row then renders exactly as it did
     * before Phase 6.
     */
    fun summary(featureId: String, settings: FeatureSettings): Summary? = when (featureId) {
        SshdProvisioner.FEATURE_SSH -> Summary.Port(settings.sshPort)
        FEATURE_DESKTOP -> Summary.DesktopMode(settings.desktopMode)
        ClipboardBridgeProvisioner.FEATURE_CLIPBOARD -> {
            val selected = settings.clipboardAliases
            when {
                // Distinguished from "5 of 5" on purpose: "all" is the default
                // and needs no scrutiny, whereas a count invites the user to
                // work out whether something is missing.
                selected.size == FeatureSettings.ALL_ALIAS_NAMES.size -> Summary.AllAliases
                selected.isEmpty() -> Summary.NoAliases
                else -> Summary.SomeAliases(selected.size, FeatureSettings.ALL_ALIAS_NAMES.size)
            }
        }
        else -> null
    }

    /**
     * The rows of the clipboard panel: every alias the backend knows, in catalog
     * order, each with whether this workspace selected it.
     *
     * Built from [ClipboardBridgeProvisioner.ALIASES] rather than from a list in
     * the UI, so an alias added to the catalog appears here automatically and one
     * that does not exist cannot be offered. [AliasRow.kind] is carried through
     * so the panel can say what an alias *does* — `xsel` reading by default while
     * `xclip` writes by default is the sort of asymmetry a bare checkbox list
     * would hide.
     */
    fun aliasRows(settings: FeatureSettings): List<AliasRow> =
        ClipboardBridgeProvisioner.ALIASES.map { alias ->
            AliasRow(
                name = alias.name,
                kind = alias.kind,
                selected = alias.name in settings.clipboardAliases
            )
        }

    /**
     * Apply a checkbox change to [settings], returning the new value.
     *
     * Pure, and it goes through [FeatureSettings.normalizeAliases] so an unknown
     * name cannot enter the set even from here.
     */
    fun withAlias(settings: FeatureSettings, name: String, selected: Boolean): FeatureSettings {
        val next = if (selected) settings.clipboardAliases + name else settings.clipboardAliases - name
        return settings.copy(clipboardAliases = FeatureSettings.normalizeAliases(next))
    }

    /**
     * Interpret the SSH port text field.
     *
     * Blank is [PortEdit.Empty] rather than an error: it is what the field looks
     * like the instant the user clears it to type a new value, and shouting at
     * them mid-edit is noise. It is still not committable.
     */
    fun editPort(raw: String): PortEdit {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return PortEdit.Empty
        val parsed = trimmed.toIntOrNull()
        val rejection = FeatureSettings.validatePort(parsed)
        return if (rejection == null) PortEdit.Valid(parsed!!) else PortEdit.Invalid(rejection)
    }

    /** A configurable row's collapsed summary. Text is resolved by the UI. */
    sealed interface Summary {
        data class Port(val port: Int) : Summary
        data object AllAliases : Summary
        data object NoAliases : Summary
        data class SomeAliases(val selected: Int, val total: Int) : Summary

        /** Desktop Modes: which run mode the Desktop screen will use. */
        data class DesktopMode(val mode: DesktopRunMode) : Summary
    }

    /** One row of the clipboard alias panel. */
    data class AliasRow(
        val name: String,
        val kind: ClipboardAliasKind,
        val selected: Boolean
    )

    /** The state of the SSH port field as the user types. */
    sealed interface PortEdit {
        /** Committable. */
        data class Valid(val port: Int) : PortEdit

        /** Mid-edit; not an error, not committable. */
        data object Empty : PortEdit

        /** Refused, with the reason to render. */
        data class Invalid(val reason: FeatureSettings.PortRejection) : PortEdit
    }

    /**
     * Feature ids with a *deliberately* absent panel, and why.
     *
     * Not used for rendering — nothing is displayed from this map. It exists so
     * the audit behind [CONFIGURABLE_FEATURE_IDS] is asserted rather than only
     * described in a comment: `FeatureConfigTest` requires every built-in
     * feature to be either configurable or listed here, so a future feature
     * cannot be silently left out of both.
     */
    val NON_CONFIGURABLE_RATIONALE: Map<String, String> = mapOf(
        AudioBridgeProvisioner.FEATURE_AUDIO to
            "Sample rate and channel count are one wire format shared by the guest's " +
            "ALSA config and the host's AudioTrack; they are not independent settings.",
        NotifySendProvisioner.FEATURE_NOTIFICATIONS to
            "Every constant is a safety bound or a path; urgency comes from the guest's own argv.",
        FEATURE_STORAGE to
            "The mount is {externalFilesDir}/shared → /mnt/shared, both app-owned; " +
            "config.yaml launch.bindMounts (§1.73) already covers binding anything else.",
        FEATURE_GPU to
            "The bridge has one configuration: on or off. The driver selection " +
            "(virpipe), socket path and server backend are fixed by the " +
            "device-verified implementation (2026-09 GPU work); exposing them " +
            "would offer choices the backend cannot honor."
    )
}
