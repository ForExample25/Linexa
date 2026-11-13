package com.fem.linexa.service.rootfs

import com.fem.linexa.core.logging.Logger
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 2026-09-01 — the Linexa-bundled `linexa.yaml` install profiles for the
 * distros Linexa itself downloads (currently Debian and Ubuntu).
 *
 * ## What problem this solves
 *
 * The download catalog's tarballs (debuerreotype Debian, ubuntu-base) carry
 * NO `.linexa.yaml`, so a catalog install used to finish as an UNDECLARED
 * workspace: no declared package manager, no presentation, and only the
 * generic fallback shortcuts. Linexa now ships the install profile itself —
 * one YAML per distro under `assets/rootfs/metadata/` — and the install
 * pipeline applies it whenever the archive carries no *native Linexa*
 * metadata of its own. Precedence (issue #10, deterministic by design):
 *
 * 1. an archive-carried native `.linexa.yaml` / `linexa.yaml` / `config.yaml`
 *    (the image author's own Linexa declaration),
 * 2. a **version-specific** bundled profile (`<distro>-<version>.linexa.yaml`,
 *    e.g. `debian-12.linexa.yaml`), selected against the catalog's version,
 * 3. a **generic** bundled profile (`<distro>.linexa.yaml`),
 * 4. an archive-carried legacy `.tiny.yaml` (compatibility source — read,
 *    but never promoted merely because it exists),
 * 5. undeclared (probe-based detection).
 *
 * `forDistro` implements steps 2–3: the caller hands in the distro id and
 * (optionally) the catalog version, and this provider resolves the most
 * specific compatible bundled file, logging WHICH file won and WHY — issue
 * #11's "log which metadata file was selected" requirement. A version that
 * does not match any shipped `<distro>-<version>` file falls back to the
 * generic profile, so a generic file that safely works across major versions
 * never needs per-version duplicates (issue #3's "no unnecessary
 * version-specific files" rule).
 *
 * ## Version matching
 *
 * Versions are matched on dot-separated components: the catalog version
 * `22.04.5` tries `22.04.5`, then `22.04`, then `22` — the same convention
 * distro publishers use for point releases sharing one branch. A version
 * prefix must be followed by a component boundary (so `12` does not match
 * `127`), and `sid`-style non-numeric versions match only their literal
 * spelling. Never matches across distros; the id is always the file's first
 * component.
 *
 * ## Shape
 *
 * A byte-source seam mirrors [com.fem.linexa.service.rootfs.profile.ProfileByteSource]:
 * the production source reads `rootfs/metadata/<fileName>` from assets, JVM
 * tests point it at the very same files on disk, so the SHIPPED files are
 * what the unit tests validate (a bundled profile that fails the §1.118
 * schema fails a test, not a user's install).
 *
 * @see com.fem.linexa.service.installer.RootFsInstaller.analyze — the
 *   `bundledMetadata` parameter this feeds.
 */
fun interface BundledRootfsMetadataSource {
    /** Bytes of `rootfs/metadata/$fileName`, or null when absent. Must not throw. */
    fun read(fileName: String): ByteArray?
}

@Singleton
class BundledRootfsMetadataProvider @Inject constructor(
    /**
     * 2026-09-03 (issue #11 testability): the byte source is the constructor
     * seam, mirroring [com.fem.linexa.service.rootfs.profile.ProfileByteSource].
     * The production binding (AppModule) serves the app's assets; JVM tests
     * point the SAME constructor at the shipped files on disk or at fixtures,
     * so the schema that unit tests validate is the one installs consume.
     */
    private val byteSource: BundledRootfsMetadataSource
) {

    /**
     * The bundled `linexa.yaml` bytes for [distroId], or null when this build
     * ships no profile for the id (unknown ids, or a locked distro without a
     * bundled profile). Null is the NORMAL case for file imports and for any
     * distro the app does not itself download — callers treat it as "no
     * bundled profile", never as an error. Version-agnostic: resolves the
     * generic profile only.
     */
    fun forDistro(distroId: String): ByteArray? = forDistro(distroId, null)

    /**
     * The most specific bundled profile for [distroId] at [version]:
     * `<id>-<version-component>.linexa.yaml` (most specific first) falls back
     * to `<id>.linexa.yaml`. Returns null — with the selection logged — when
     * this build ships neither.
     *
     * @param version the distro version to prefer (catalog's VERSION_ID, e.g.
     *   "12" or "22.04.5"). Null/blank skips straight to the generic profile.
     */
    fun forDistro(distroId: String, version: String?): ByteArray? {
        val id = distroId.trim().lowercase(Locale.US)
        if (id.isEmpty() || !SAFE_ID.matches(id)) return null

        val candidates = buildList {
            for (prefix in versionCandidates(version)) {
                if (!SAFE_VERSION_PREFIX.matches(prefix)) continue // keep asset paths closed
                add("$id-$prefix$FILE_SUFFIX")
            }
            add("$id$FILE_SUFFIX")
        }

        for ((index, fileName) in candidates.withIndex()) {
            val bytes = byteSource.read(fileName) ?: continue
            val why = when {
                index == candidates.lastIndex -> "generic profile"
                else -> "version-specific match for '$id' at '$version' (candidate '$fileName')"
            }
            Logger.i(TAG, "bundled metadata selected for $distroId: $fileName ($why)")
            return bytes
        }
        Logger.i(
            TAG,
            "no bundled metadata for $distroId" +
                (version?.takeIf { it.isNotBlank() }?.let { " (version '$it')" } ?: "") +
                " — tried: ${candidates.joinToString(", ")}"
        )
        return null
    }

    companion object {
        private const val TAG = "BundledRootfsMeta"

        /** Assets directory holding the bundled profiles (trailing slash). */
        const val ASSET_DIR = "rootfs/metadata/"

        /** Canonical per-distro file name suffix (§1.118 native spelling). */
        const val FILE_SUFFIX = ".linexa.yaml"

        /** Distros ship lower-case ids; the guard keeps asset paths closed. */
        private val SAFE_ID = Regex("[a-z0-9]+")

        /**
         * Version prefixes may carry dots (22.04.5) — they land BETWEEN the
         * distro id and the file-name suffix, so the dotted components stay
         * inside the closed asset path.
         */
        private val SAFE_VERSION_PREFIX = Regex("[a-z0-9.]+")

        /**
         * Candidate version prefixes for [version], most specific first:
         * "22.04.5" → ["22.04.5", "22.04", "22"]; "12" → ["12"]; null/blank
         * → empty. Non-numeric components ("sid", "trixie/sid") match only
         * their literal spelling — the literal is always the first candidate.
         */
        internal fun versionCandidates(version: String?): List<String> {
            val v = version?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
            val out = mutableListOf(v)
            if (!v.matches(NUMERIC_DOT_VERSION)) return out
            var current = v
            while (current.contains('.')) {
                current = current.substringBeforeLast('.')
                if (current.isNotEmpty() && current !in out) out.add(current)
            }
            return out
        }

        private val NUMERIC_DOT_VERSION = Regex("[0-9]+(\\.[0-9]+)*")
    }
}
