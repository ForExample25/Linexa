package com.fem.linexa.service.rootfs.profile

import android.app.Application
import com.fem.linexa.core.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §1.146 — loads the built-in distribution profiles Linexa ships in its assets.
 *
 * ## Why an interface with an asset-backed implementation
 *
 * The profiles are YAML files under `assets/rootfs/profiles/`, which is the
 * right place for shipped declarative data but is unreachable from a plain JVM
 * unit test (`Application.getAssets()` needs an Android runtime). Rather than
 * mock the profiles in tests — which would let the *shipped* files rot
 * undetected while the tests stayed green — the loading is behind
 * [ProfileByteSource], and the JVM tests point it at the very same files on
 * disk. A malformed shipped profile therefore fails a unit test, and CI needs
 * no device.
 *
 * ## Failure posture
 *
 * A built-in profile that fails to parse is a **programming error**, not a user
 * error, but it must not brick the import flow on a user's device: an app
 * update that shipped a broken `alpine.yaml` should degrade Alpine to the
 * generic path, not crash every import. So [load] logs loudly and skips, while
 * `RootFsProfileCatalogTest` asserts that all five parse — the test is where
 * this is fatal.
 */
interface RootFsProfileCatalog {
    /** Every successfully-parsed built-in profile, keyed by [RootFsProfile.id]. */
    fun profiles(): List<RootFsProfile>
}

/** §1.146 — where profile bytes come from. Lets JVM tests read the real assets. */
fun interface ProfileByteSource {
    /**
     * Returns the bytes of `rootfs/profiles/$fileName`, or null when absent.
     * Must not throw for a missing file.
     */
    fun read(fileName: String): ByteArray?
}

/**
 * §1.146 — the asset-backed catalog.
 *
 * The profile list is a compile-time constant rather than a directory listing.
 * `AssetManager.list()` would work, but an explicit list means adding a
 * distribution is a visible, reviewable one-line change next to the file, and a
 * profile that is shipped but not listed fails a test instead of silently
 * activating.
 */
@Singleton
class AssetRootFsProfileCatalog @Inject constructor(
    application: Application
) : RootFsProfileCatalog {

    private val byteSource: ProfileByteSource = ProfileByteSource { fileName ->
        try {
            application.assets.open("$ASSET_DIR/$fileName").use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    private val loaded: List<RootFsProfile> by lazy { load(byteSource) }

    override fun profiles(): List<RootFsProfile> = loaded

    companion object {
        private const val TAG = "RootFsProfileCatalog"

        const val ASSET_DIR = "rootfs/profiles"

        /**
         * The built-in profiles, in **resolution order**.
         *
         * Order is load-bearing for marker-file matching only, and only in the
         * one case where two distributions share a marker: Ubuntu ships
         * `etc/debian_version` just as Debian does. `os-release` `ID` is checked
         * before any marker (see [RootFsProfileResolver]), so a real Ubuntu
         * image resolves to Ubuntu regardless; this order decides the answer
         * only for an image that has `etc/debian_version` and no readable
         * `os-release`, where "Debian" is the correct conservative answer.
         */
        val BUILT_IN_FILES: List<String> = listOf(
            "debian.yaml",
            "ubuntu.yaml",
            "alpine.yaml",
            "arch.yaml",
            "fedora.yaml"
        )

        /**
         * Parses every built-in profile from [source], skipping (and logging)
         * any that is missing or invalid.
         *
         * Public and static so unit tests can run the exact same load against
         * the real asset files.
         */
        fun load(source: ProfileByteSource): List<RootFsProfile> {
            val result = ArrayList<RootFsProfile>(BUILT_IN_FILES.size)
            for (fileName in BUILT_IN_FILES) {
                val bytes = source.read(fileName)
                if (bytes == null) {
                    Logger.e(TAG, "§1.146 built-in profile '$fileName' is missing from assets")
                    continue
                }
                when (val parsed = RootFsProfileParser.parse(bytes, RootFsProfileSource.BUILT_IN)) {
                    is RootFsProfileParser.Result.Invalid ->
                        Logger.e(TAG, "§1.146 built-in profile '$fileName' is invalid: ${parsed.reason}")

                    is RootFsProfileParser.Result.Valid -> {
                        if (result.any { it.id == parsed.profile.id }) {
                            Logger.e(TAG, "§1.146 duplicate profile id '${parsed.profile.id}' in '$fileName'")
                        } else {
                            result += parsed.profile
                        }
                    }
                }
            }
            return result
        }
    }
}
