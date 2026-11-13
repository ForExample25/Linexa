package com.fem.linexa.service.runtime.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI/UX Phase 6 — the persistence encoding and validation of the two feature
 * settings that have a real backend parameter.
 *
 * The cases worth pinning are the ones that only occur on real data: a
 * pre-Phase-6 workspace with no attributes at all, a workspace that shares its
 * `attributes` map with the install-source/distro/desktop entries, and the
 * *empty* alias selection, which has to survive a round trip distinctly from
 * "never chosen" or the tri-state collapses into the default.
 */
class FeatureSettingsTest {

    @Test
    fun `absent attributes resolve to the pre-Phase-6 behaviour`() {
        val settings = FeatureSettings.from(emptyMap())
        assertEquals(SshdProvisioner.SSH_PORT, settings.sshPort)
        assertEquals(FeatureSettings.ALL_ALIAS_NAMES, settings.clipboardAliases)
        assertEquals(ClipboardBridgeProvisioner.ALIASES, settings.selectedAliases)
    }

    @Test
    fun `a chosen port round trips`() {
        val encoded = FeatureSettings(sshPort = 22222).toAttributes()
        assertEquals(22222, FeatureSettings.from(encoded).sshPort)
    }

    @Test
    fun `an empty alias selection round trips and does NOT fall back to all`() {
        // The tri-state: absent → all, present-but-blank → none. If this ever
        // reads back as ALL_ALIAS_NAMES, choosing "no aliases" becomes
        // impossible and the panel silently reinstalls five shims.
        val encoded = FeatureSettings(clipboardAliases = emptySet()).toAttributes()
        assertTrue(encoded.containsKey(FeatureSettings.KEY_CLIPBOARD_ALIASES))
        val decoded = FeatureSettings.from(encoded)
        assertEquals(emptySet<String>(), decoded.clipboardAliases)
        assertEquals(emptyList<ClipboardAlias>(), decoded.selectedAliases)
    }

    @Test
    fun `a partial alias selection round trips in catalog order`() {
        val chosen = setOf("pbcopy", "xclip")
        val decoded = FeatureSettings.from(FeatureSettings(clipboardAliases = chosen).toAttributes())
        assertEquals(chosen, decoded.clipboardAliases)
        // Order comes from the catalog, not from the persisted string or the
        // set's iteration order — the supervisor installs in this order.
        assertEquals(listOf("xclip", "pbcopy"), decoded.selectedAliases.map { it.name })
    }

    @Test
    fun `selectedAliases returns catalog entries so a selection cannot invent behaviour`() {
        val settings = FeatureSettings(clipboardAliases = setOf("xsel"))
        val alias = settings.selectedAliases.single()
        assertSame(
            "the entry must BE the catalog's, so its kind (and therefore which shim it dispatches to) is owned by the catalog",
            ClipboardBridgeProvisioner.ALIASES.first { it.name == "xsel" },
            alias
        )
        assertEquals(ClipboardAliasKind.DISPATCH_XSEL, alias.kind)
    }

    // ── Reading is fail-open ───────────────────────────────────────────────

    @Test
    fun `a corrupt or out-of-range persisted port degrades to the default`() {
        listOf("", "   ", "not-a-port", "0", "80", "65536", "-1", "99999999999999")
            .forEach { raw ->
                assertEquals(
                    "a stored '$raw' must degrade to the default rather than break the workspace",
                    SshdProvisioner.SSH_PORT,
                    FeatureSettings.from(mapOf(FeatureSettings.KEY_SSH_PORT to raw)).sshPort
                )
            }
    }

    @Test
    fun `a stored port is accepted with surrounding whitespace`() {
        assertEquals(
            2222,
            FeatureSettings.from(mapOf(FeatureSettings.KEY_SSH_PORT to " 2222 ")).sshPort
        )
    }

    @Test
    fun `unknown alias names in the persisted value are dropped`() {
        // An alias name is a FILE the app writes into /usr/local/bin — honouring
        // an unrecognised one would create a guest command no catalog entry
        // defines. Resolution against a fixed table, never free text.
        val decoded = FeatureSettings.from(
            mapOf(FeatureSettings.KEY_CLIPBOARD_ALIASES to "xclip,rm,../../etc/passwd, wl-copy ")
        )
        assertEquals(setOf("xclip", "wl-copy"), decoded.clipboardAliases)
    }

    // ── Writing is validated ──────────────────────────────────────────────

    @Test
    fun `validatePort accepts the whole selectable range and nothing outside it`() {
        assertNull(FeatureSettings.validatePort(FeatureSettings.MIN_PORT))
        assertNull(FeatureSettings.validatePort(FeatureSettings.MAX_PORT))
        assertNull(FeatureSettings.validatePort(SshdProvisioner.SSH_PORT))
        assertEquals(
            FeatureSettings.PortRejection.NOT_A_NUMBER,
            FeatureSettings.validatePort(null)
        )
        assertEquals(
            // Privileged on the HOST, which is the end that matters: the real
            // process performing the bind is not root even though the guest is.
            FeatureSettings.PortRejection.PRIVILEGED,
            FeatureSettings.validatePort(FeatureSettings.MIN_PORT - 1)
        )
        assertEquals(
            FeatureSettings.PortRejection.PRIVILEGED,
            FeatureSettings.validatePort(22)
        )
        assertEquals(
            FeatureSettings.PortRejection.OUT_OF_RANGE,
            FeatureSettings.validatePort(FeatureSettings.MAX_PORT + 1)
        )
    }

    @Test
    fun `normalizeAliases keeps only catalog names`() {
        assertEquals(
            setOf("wl-paste"),
            FeatureSettings.normalizeAliases(listOf(" wl-paste ", "WL-PASTE", "sudo", ""))
        )
    }

    @Test
    fun `ALL_ALIAS_NAMES is exactly the catalog`() {
        assertEquals(
            ClipboardBridgeProvisioner.ALIASES.map { it.name }.toSet(),
            FeatureSettings.ALL_ALIAS_NAMES
        )
        assertEquals(
            "no alias name may contain the persistence separator",
            emptyList<String>(),
            FeatureSettings.ALL_ALIAS_NAMES.filter { it.contains(FeatureSettings.ALIAS_SEPARATOR) }
        )
    }
}
