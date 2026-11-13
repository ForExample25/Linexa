package com.fem.linexa.data.repository

import com.fem.linexa.domain.security.SecretAttributeCodec
import com.fem.linexa.domain.security.SecretDecode
import com.fem.linexa.domain.security.SecretEncode
import com.fem.linexa.service.runtime.features.FeatureSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies [SecretAttributeCodec] to the secret entries of a workspace's `attributes`
 * map, at the repository boundary.
 *
 * The contract the rest of the app relies on is unchanged: everything above
 * `WorkspaceRepositoryImpl` sees (and writes) **plaintext** secrets. This class is what
 * makes the persisted form ciphertext. Three operations, one per direction/purpose:
 *
 *  - [protect]  — before a row is written;
 *  - [reveal]   — after a row is read;
 *  - [migrate]  — one-time rewrite of a legacy plaintext value.
 *
 * ## Fail-closed rules
 *
 * An empty VNC password means "start Xvnc with no authentication", so a secret that
 * cannot be read must never surface as a silently-empty password. [reveal] therefore
 * pairs an unreadable value with the `…password.unreadable` marker, and [protect]
 * never discards a stored ciphertext on behalf of that marker alone — a Keystore
 * failure can be transient, and overwriting the ciphertext would turn a recoverable
 * password into a lost one.
 *
 * Pure Kotlin (no `android.*`); the cipher is injected.
 */
@Singleton
class WorkspaceSecretAttributes @Inject constructor(
    private val codec: SecretAttributeCodec
) : WorkspaceSecretStore {

    private class Secret(val key: String, val unreadableMarker: String)

    private val secrets = listOf(
        Secret(
            FeatureSettings.KEY_DESKTOP_VNC_PASSWORD,
            FeatureSettings.KEY_DESKTOP_VNC_PASSWORD_UNREADABLE
        )
    )

    /**
     * Whether writing [attributes] needs the currently stored row to be consulted: it
     * carries a non-empty secret, or the empty-plus-unreadable placeholder whose stored
     * ciphertext [protect] must not overwrite. Lets callers skip the extra read for the
     * common case of a row with no secret at all.
     */
    override fun needsStoredRow(attributes: Map<String, String>): Boolean =
        secrets.any { secret ->
            attributes.containsKey(secret.key) &&
                (!attributes[secret.key].isNullOrEmpty() || attributes[secret.unreadableMarker] == "true")
        }

    /**
     * The form of [incoming] to persist. [existingRaw] is the currently stored raw map
     * (or null when there is none / it was not read); only keys present in [incoming]
     * are touched.
     */
    override fun protect(
        workspaceId: String,
        incoming: Map<String, String>,
        existingRaw: Map<String, String>?
    ): Map<String, String> {
        var out = incoming
        for (secret in secrets) {
            val plain = incoming[secret.key] ?: continue
            val existing = existingRaw?.get(secret.key).orEmpty()
            val existingPlain = existingPlain(workspaceId, secret.key, existing)

            if (plain.isEmpty()) {
                // "Unreadable" placeholder on its way back to the row: keep the stored
                // ciphertext rather than overwrite it with an empty value.
                if (incoming[secret.unreadableMarker] == "true" && codec.isEnvelope(existing)) {
                    out = out + (secret.key to existing) + (secret.unreadableMarker to "true")
                }
                continue
            }

            // Unchanged secret: keep what is already stored (no re-encrypt, no new IV).
            if (existingPlain == plain && codec.isEnvelope(existing)) {
                out = out + (secret.key to existing) + (secret.unreadableMarker to "false")
                continue
            }

            when (val encoded = codec.encode(workspaceId, secret.key, plain)) {
                is SecretEncode.Encoded ->
                    out = out + (secret.key to encoded.stored) + (secret.unreadableMarker to "false")
                SecretEncode.Failed ->
                    out = when {
                        // The cipher is down but the secret did not change (an unrelated
                        // metadata write): leave the stored value exactly as it is.
                        existingPlain == plain -> out + (secret.key to existing)
                        // Cannot encrypt, and cannot even read what is stored: do not
                        // destroy a ciphertext that may be recoverable once the Keystore
                        // is back. Keep it and flag the secret unreadable, so the desktop
                        // refuses to start rather than guess which password is meant.
                        codec.isEnvelope(existing) ->
                            out + (secret.key to existing) + (secret.unreadableMarker to "true")
                        // A new secret that cannot be encrypted is never stored in the
                        // clear. Fail closed: no password + the marker, so the desktop
                        // refuses to start until the user sets a new one.
                        else -> out + (secret.key to "") + (secret.unreadableMarker to "true")
                    }
            }
        }
        return out
    }

    /** [stored] with every secret decrypted for in-memory use. */
    override fun reveal(workspaceId: String, stored: Map<String, String>): Map<String, String> {
        var out = stored
        for (secret in secrets) {
            val raw = stored[secret.key] ?: continue
            if (raw.isEmpty()) continue // nothing to decrypt; any persisted marker stands
            out = when (val decoded = codec.decode(workspaceId, secret.key, raw)) {
                is SecretDecode.Decrypted -> out + (secret.key to decoded.value) + (secret.unreadableMarker to "false")
                is SecretDecode.Legacy -> out + (secret.key to decoded.value) + (secret.unreadableMarker to "false")
                SecretDecode.Unreadable -> out + (secret.key to "") + (secret.unreadableMarker to "true")
            }
        }
        return out
    }

    /**
     * The migrated form of [stored], or null when nothing needs to change. A legacy
     * plaintext secret is encrypted; if the cipher fails the legacy value is left in
     * place (null is returned) so the next start retries — migration never destroys a
     * password.
     */
    override fun migrate(workspaceId: String, stored: Map<String, String>): Map<String, String>? {
        var out = stored
        var changed = false
        for (secret in secrets) {
            val raw = stored[secret.key].orEmpty()
            if (raw.isEmpty() || codec.isEnvelope(raw)) continue
            val encoded = codec.encode(workspaceId, secret.key, raw)
            if (encoded is SecretEncode.Encoded) {
                out = out + (secret.key to encoded.stored) + (secret.unreadableMarker to "false")
                changed = true
            }
        }
        return if (changed) out else null
    }

    private fun existingPlain(workspaceId: String, key: String, stored: String): String? =
        when (val d = codec.decode(workspaceId, key, stored)) {
            is SecretDecode.Decrypted -> d.value
            is SecretDecode.Legacy -> d.value
            SecretDecode.Unreadable -> null
        }
}
