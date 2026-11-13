package com.fem.linexa.service.runtime.bootstrap

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * §1.71 — Roadmap Item 5: the versioned, machine-readable record of the
 * bundled bootstrap binaries, extending `jniLibs/BINARY-MANIFEST.md`'s
 * human-readable provenance into something the app checks itself.
 *
 * Bundled as `assets/bootstrap-manifest.json` (asset name in
 * [BootstrapManifestSupport.ASSET_PATH]) and validated by
 * [BootstrapManager] during `setup()` on every launch. The validation is an
 * ADDITIONAL check layered on top of the existing symlink self-heal — it is
 * NOT a replacement for it (Item 5's must-not-break).
 *
 * Per-artifact fields:
 * - `file` — the `lib*.so` name as packaged in `jniLibs/{abi}/` and
 *   extracted to `nativeLibraryDir` (the `lib__bin__*.so` / `lib__lib__*.so`
 *   re-mapped names from §1.45).
 * - `abi` — `arm64-v8a` | `armeabi-v7a`.
 * - `version` — human-readable upstream version (mirrors BINARY-MANIFEST.md).
 * - `buildId` — identifies the build line (e.g. §1.55's linexa static-musl
 *   rebuild vs the termux lineage); paired artifacts must share one.
 * - `sha256` — of the file bytes as committed under `jniLibs/{abi}/`.
 *
 * `pairs` — paired-artifact requirements (e.g. "loader must come from the
 * same build as proot"): all members' resolved entries must carry the same
 * `buildId`.
 */
@Serializable
data class BootstrapManifest(
    @SerialName("schemaVersion") val schemaVersion: Int = 1,
    @SerialName("note") val note: String? = null,
    @SerialName("artifacts") val artifacts: List<Artifact> = emptyList(),
    @SerialName("pairs") val pairs: List<PairedArtifacts> = emptyList()
) {
    @Serializable
    data class Artifact(
        @SerialName("file") val file: String,
        @SerialName("abi") val abi: String,
        @SerialName("version") val version: String,
        @SerialName("buildId") val buildId: String,
        @SerialName("sha256") val sha256: String
    )

    @Serializable
    data class PairedArtifacts(
        @SerialName("label") val label: String,
        @SerialName("members") val members: List<String>
    )
}

/** §1.71: parsing + hashing helpers for the bootstrap manifest. */
object BootstrapManifestSupport {

    /** The only schema version [BootstrapManager] accepts (§1.71). */
    const val SCHEMA_VERSION = 1

    /** Asset path of the bundled manifest (relative to the app's `assets/`). */
    const val ASSET_PATH = "bootstrap-manifest.json"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * §1.71: parse manifest JSON; null on any malformed input (never throws —
     * a corrupt manifest must degrade to a loud validation failure, not a
     * new crash source).
     */
    fun parse(text: String): BootstrapManifest? = try {
        json.decodeFromString<BootstrapManifest>(text)
    } catch (_: Exception) {
        null
    }

    /** §1.71: lowercase hex SHA-256 of a file; null when the file is unreadable. */
    fun sha256Hex(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        null
    }
}

/**
 * §1.71: JVM-test seam — supplies the manifest JSON instead of reading the
 * bundled asset, because `Application.getAssets()` is final and cannot be
 * stubbed with plain Mockito. Test-only; production never writes it.
 */
internal object BootstrapManifestTestSeam {
    var overrideJson: String? = null
}
