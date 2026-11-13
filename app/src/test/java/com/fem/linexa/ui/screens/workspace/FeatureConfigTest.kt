package com.fem.linexa.ui.screens.workspace

import com.fem.linexa.data.WorkspaceContent
import com.fem.linexa.domain.runtime.FEATURE_DESKTOP
import com.fem.linexa.service.runtime.features.ClipboardAliasKind
import com.fem.linexa.service.runtime.features.ClipboardBridgeProvisioner
import com.fem.linexa.service.runtime.features.FeatureSettings
import com.fem.linexa.service.runtime.features.SshdProvisioner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI/UX Phase 6 — the Features tab's configuration rules.
 *
 * The rule this file exists for is the one a screenshot cannot check: **which**
 * features may expand at all. Phase 6's binding constraint is "do not add
 * configuration options that are not actually supported by the backend", and the
 * only durable enforcement of that is a whitelist plus a test that every other
 * built-in feature is *deliberately* absent from it — so adding a sixth feature,
 * or a panel for an existing one, forces a decision instead of drifting.
 */
class FeatureConfigTest {

    // ── The whitelist, in both directions ──────────────────────────────────

    @Test
    fun `exactly the backends that take a parameter are configurable`() {
        // Desktop Modes (2026-08) adds `desktop` as the third: its panel's every
        // field is read by DesktopViewModel at desktop-start time (run mode,
        // resolution preset, ZRLE opt-in, optional VNC password, web port and
        // command) — a parameterised backend in exactly the whitelist's sense.
        assertEquals(
            setOf(
                SshdProvisioner.FEATURE_SSH,
                ClipboardBridgeProvisioner.FEATURE_CLIPBOARD,
                FEATURE_DESKTOP
            ),
            FeatureConfig.CONFIGURABLE_FEATURE_IDS
        )
    }

    @Test
    fun `every built-in feature is either configurable or has a recorded reason not to be`() {
        // Both directions: a new feature cannot be silently left out of both, and
        // a rationale cannot go stale for a feature that no longer exists.
        val builtinIds = WorkspaceContent.builtinFeatureDefaults.keys
        val classified = FeatureConfig.CONFIGURABLE_FEATURE_IDS +
            FeatureConfig.NON_CONFIGURABLE_RATIONALE.keys
        assertEquals(
            "every built-in feature must be classified — add it to the whitelist (with a real backend parameter) or record why it has none",
            builtinIds,
            classified
        )
        assertTrue(
            "no feature may be both configurable and listed as non-configurable",
            FeatureConfig.CONFIGURABLE_FEATURE_IDS
                .none { it in FeatureConfig.NON_CONFIGURABLE_RATIONALE }
        )
    }

    @Test
    fun `configurability is independent of the toggle and of anything else`() {
        assertTrue(FeatureConfig.isConfigurable(SshdProvisioner.FEATURE_SSH))
        assertTrue(FeatureConfig.isConfigurable(ClipboardBridgeProvisioner.FEATURE_CLIPBOARD))
        assertFalse(FeatureConfig.isConfigurable("audio"))
        assertFalse(FeatureConfig.isConfigurable("notifications"))
        assertFalse(FeatureConfig.isConfigurable("storage"))
        assertFalse(FeatureConfig.isConfigurable("not-a-feature"))
    }

    // ── Collapsed summaries ───────────────────────────────────────────────

    @Test
    fun `a non-configurable feature has no summary so its row renders as before`() {
        assertNull(FeatureConfig.summary("audio", FeatureSettings()))
        assertNull(FeatureConfig.summary("storage", FeatureSettings()))
    }

    @Test
    fun `the ssh summary reports the configured port`() {
        assertEquals(
            FeatureConfig.Summary.Port(2222),
            FeatureConfig.summary(SshdProvisioner.FEATURE_SSH, FeatureSettings(sshPort = 2222))
        )
    }

    @Test
    fun `all-aliases is distinguished from a full count`() {
        // "All 5 commands" and "5 of 5 commands" say the same thing, but the
        // second invites the user to check whether something is missing. The
        // default state should need no scrutiny.
        assertEquals(
            FeatureConfig.Summary.AllAliases,
            FeatureConfig.summary(ClipboardBridgeProvisioner.FEATURE_CLIPBOARD, FeatureSettings())
        )
    }

    @Test
    fun `an empty selection is its own summary, not a count of zero`() {
        assertEquals(
            FeatureConfig.Summary.NoAliases,
            FeatureConfig.summary(
                ClipboardBridgeProvisioner.FEATURE_CLIPBOARD,
                FeatureSettings(clipboardAliases = emptySet())
            )
        )
    }

    @Test
    fun `a partial selection reports both numbers`() {
        assertEquals(
            FeatureConfig.Summary.SomeAliases(2, FeatureSettings.ALL_ALIAS_NAMES.size),
            FeatureConfig.summary(
                ClipboardBridgeProvisioner.FEATURE_CLIPBOARD,
                FeatureSettings(clipboardAliases = setOf("xclip", "xsel"))
            )
        )
    }

    // ── Alias rows ────────────────────────────────────────────────────────

    @Test
    fun `alias rows come from the catalog, in catalog order, with their kinds`() {
        val rows = FeatureConfig.aliasRows(FeatureSettings(clipboardAliases = setOf("xsel")))
        assertEquals(
            ClipboardBridgeProvisioner.ALIASES.map { it.name },
            rows.map { it.name }
        )
        assertEquals(
            ClipboardBridgeProvisioner.ALIASES.map { it.kind },
            rows.map { it.kind }
        )
        // Every catalog entry is offered, selected or not — the panel is a view
        // of the backend's capability, not of the current selection.
        assertEquals(listOf("xsel"), rows.filter { it.selected }.map { it.name })
        assertEquals(
            ClipboardAliasKind.DISPATCH_XSEL,
            rows.first { it.name == "xsel" }.kind
        )
    }

    @Test
    fun `withAlias adds and removes, and cannot introduce an unknown name`() {
        val base = FeatureSettings(clipboardAliases = setOf("xclip"))
        assertEquals(
            setOf("xclip", "pbcopy"),
            FeatureConfig.withAlias(base, "pbcopy", selected = true).clipboardAliases
        )
        assertEquals(
            emptySet<String>(),
            FeatureConfig.withAlias(base, "xclip", selected = false).clipboardAliases
        )
        assertEquals(
            "an unknown name must not enter the set even from the UI — it would become a file written into the guest's /usr/local/bin",
            setOf("xclip"),
            FeatureConfig.withAlias(base, "rm", selected = true).clipboardAliases
        )
    }

    // ── Port editing ──────────────────────────────────────────────────────

    @Test
    fun `a blank field is mid-edit, not an error`() {
        // It is what the field looks like the instant the user clears it to type
        // a new value. Not committable either.
        assertEquals(FeatureConfig.PortEdit.Empty, FeatureConfig.editPort(""))
        assertEquals(FeatureConfig.PortEdit.Empty, FeatureConfig.editPort("   "))
    }

    @Test
    fun `a valid port is committable`() {
        assertEquals(FeatureConfig.PortEdit.Valid(8022), FeatureConfig.editPort("8022"))
        assertEquals(FeatureConfig.PortEdit.Valid(2222), FeatureConfig.editPort(" 2222 "))
    }

    @Test
    fun `a rejected port carries the reason rather than a message`() {
        assertEquals(
            FeatureConfig.PortEdit.Invalid(FeatureSettings.PortRejection.PRIVILEGED),
            FeatureConfig.editPort("22")
        )
        assertEquals(
            FeatureConfig.PortEdit.Invalid(FeatureSettings.PortRejection.OUT_OF_RANGE),
            FeatureConfig.editPort("70000")
        )
        assertEquals(
            FeatureConfig.PortEdit.Invalid(FeatureSettings.PortRejection.NOT_A_NUMBER),
            FeatureConfig.editPort("80a")
        )
        // Larger than Int.MAX_VALUE: toIntOrNull returns null, which must read as
        // "not a number" rather than crashing or wrapping to a valid port.
        assertEquals(
            FeatureConfig.PortEdit.Invalid(FeatureSettings.PortRejection.NOT_A_NUMBER),
            FeatureConfig.editPort("99999999999")
        )
    }
}
