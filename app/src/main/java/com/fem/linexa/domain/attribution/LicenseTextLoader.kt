package com.fem.linexa.domain.attribution

import java.io.IOException
import java.io.InputStream

/**
 * §1.130 — reads a bundled license document.
 *
 * **Why the texts are assets and not string resources.** They are verbatim legal
 * documents up to ~41 KB (LGPL-3.0) that must not be translated, reflowed or
 * re-punctuated. A `<string>` would have to escape them, would be a translation target
 * that a translator could legitimately "fix", and would be loaded into the resource
 * table on every launch for a screen almost nobody opens. As assets they are byte-exact
 * and read on demand.
 *
 * **Why the source is a lambda rather than an `AssetManager`.** Reading is the only
 * Android-coupled part of this feature, and coupling it to `AssetManager` would make the
 * one thing worth asserting — that a missing document degrades instead of crashing, and
 * that a text is never silently truncated — untestable on the JVM. The production
 * caller passes `application.assets::open`.
 */
fun interface LicenseTextSource {
    /**
     * Opens an asset by path.
     *
     * @throws IOException if it does not exist — which is exactly what `AssetManager`
     *   does, so [LicenseTextLoader] handles that case rather than assuming presence.
     */
    @Throws(IOException::class)
    fun open(path: String): InputStream
}

/**
 * Loads verbatim license texts from `assets/licenses/`.
 *
 * Reading is **fail-soft and reports the failure as a value**: [load] returns null for a
 * license with no document ([LicenseRef.assetName] is null, e.g. public domain) *and*
 * for a document that could not be read. The caller renders the difference — the first
 * is a normal, correct state, the second is a real problem — but neither may throw,
 * because this runs behind a settings tap and an unreadable asset must not be able to
 * crash the app. What it must never do is show a *partial* document, which is why the
 * read is all-or-nothing.
 */
class LicenseTextLoader(private val source: LicenseTextSource) {

    /**
     * The verbatim text of [ref], or null when there is none to show.
     *
     * Null covers two distinct situations by design, and they are distinguishable by the
     * caller without a second call: [LicenseRef.assetName] being null means there is no
     * document (correct, normal), while a non-null asset name plus a null return means
     * the read failed.
     */
    fun load(ref: LicenseRef): String? {
        val path = ref.assetPath ?: return null
        return try {
            source.open(path).use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            }
        } catch (_: IOException) {
            // A packaging mistake or a corrupt APK. Reported as absence rather than a
            // crash; the screen says the text is unavailable and still shows the SPDX
            // identifier and the component list, which is more useful than nothing.
            null
        } catch (_: OutOfMemoryError) {
            // Defensive, and cheap: these are ≤41 KB files, so this cannot happen from
            // their size — but reading an asset into memory on a device already at its
            // limit must not take the app down from an informational screen.
            null
        }
    }
}
