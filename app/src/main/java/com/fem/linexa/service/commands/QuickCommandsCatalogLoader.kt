package com.fem.linexa.service.commands

import com.fem.linexa.core.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the catalog's bytes come from. The production source reads
 * `commands/quick_commands.yaml` from the app's assets; JVM tests point the
 * same seam at the shipped file on disk or at fixtures — the schema a test
 * validates is the one the UI consumes. Same pattern as
 * [com.fem.linexa.service.rootfs.BundledRootfsMetadataSource] and
 * [com.fem.linexa.service.rootfs.profile.ProfileByteSource].
 */
fun interface QuickCommandsCatalogSource {
    /** The catalog file's bytes, or null when it cannot be read. Must not throw. */
    fun read(): ByteArray?
}

/**
 * Loads, validates and memoizes the ONE built-in Quick Commands catalog.
 *
 * - The catalog is read **once** per process and memoized: the file ships
 *   with the APK, never changes at runtime, and sits on the workspace
 *   screen's state-build path.
 * - A structurally invalid catalog is logged and surfaced as `null` — the
 *   Commands screen then renders without built-ins (plus the user's own
 *   commands) instead of crashing. Per-node defects do not reach this class:
 *   the parser already skipped them and reported them in [issues].
 * - `.tiny.yaml` has no role here: built-in Quick Commands come from this
 *   project-owned file alone.
 */
@Singleton
class QuickCommandsCatalogLoader @Inject constructor(
    private val source: QuickCommandsCatalogSource
) {

    private val cached: QuickCommandsCatalog? by lazy { load() }

    /** The parsed catalog, or null when the file is absent or unusable as a whole. */
    fun catalog(): QuickCommandsCatalog? = cached

    private fun load(): QuickCommandsCatalog? {
        val bytes = try {
            source.read()
        } catch (e: Exception) {
            Logger.w(TAG, "catalog source read failed: ${e.message}")
            null
        }
        if (bytes == null) {
            Logger.w(TAG, "built-in Quick Commands catalog not available — Commands screen will show no built-ins")
            return null
        }
        return when (val result = QuickCommandsCatalogParser.parse(bytes)) {
            is QuickCommandsCatalogParser.Result.Invalid -> {
                Logger.e(TAG, "built-in Quick Commands catalog rejected: ${result.reason}")
                null
            }

            is QuickCommandsCatalogParser.Result.Parsed -> {
                result.issues.forEach { Logger.w(TAG, "catalog issue: $it") }
                Logger.i(
                    TAG,
                    "Quick Commands catalog loaded: ${result.catalog.distributions.size} distribution " +
                        "branch(es), ${result.issues.size} skipped entr${if (result.issues.size == 1) "y" else "ies"}"
                )
                result.catalog
            }
        }
    }

    companion object {
        private const val TAG = "QuickCommandsCatalog"

        /** The catalog's asset path, relative to the assets root. */
        const val ASSET_PATH = "commands/quick_commands.yaml"
    }
}
