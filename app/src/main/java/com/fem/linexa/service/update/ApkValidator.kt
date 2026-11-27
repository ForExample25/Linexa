package com.fem.linexa.service.update

import android.content.Context
import android.content.pm.PackageManager
import com.fem.linexa.domain.update.GithubReleaseAsset
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the identity of an APK FILE before it is ever offered for install —
 * an interface because `PackageManager.getPackageArchiveInfo` is a platform
 * call the JVM tests must fake.
 */
interface ApkMetadataReader {
    /** The APK's package identity, or null when the file is not a readable APK. */
    fun read(apkFile: File): ApkMetadata?
}

/** The slice of `PackageInfo`/`ApplicationInfo` the validator reasons about. */
data class ApkMetadata(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int,
    val targetSdk: Int,
    /**
     * SHA-256 of every signing certificate in the archive's current signer set,
     * lowercase hex. Empty when the archive carries no readable signer — which the
     * validator treats as unverifiable, never as trusted.
     */
    val signerSha256: List<String> = emptyList()
)

/**
 * A plain-JVM Android implementation — `getPackageArchiveInfo` parses the
 * archive's manifest off the file, no install required. API 28's
 * `versionCode` is the 64-bit `versionCodeCompat` view, and
 * `GET_SIGNING_CERTIFICATES` returns the APK Signing Block's signers.
 */
@Singleton
class AndroidApkMetadataReader @Inject constructor(
    @ApplicationContext private val context: Context
) : ApkMetadataReader {

    override fun read(apkFile: File): ApkMetadata? = runCatching {
        val info = context.packageManager.getPackageArchiveInfo(
            apkFile.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES
        ) ?: return null
        val app = info.applicationInfo ?: return null
        val signers = info.signingInfo
            ?.apkContentsSigners
            ?.mapNotNull { signature -> signature.toByteArray().sha256Hex() }
            .orEmpty()
        ApkMetadata(
            packageName = info.packageName,
            versionCode = info.longVersionCode,
            versionName = info.versionName,
            minSdk = app.minSdkVersion,
            targetSdk = app.targetSdkVersion,
            signerSha256 = signers
        )
    }.getOrNull()

    private fun ByteArray.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

/**
 * Validates a downloaded APK BEFORE installation is offered. Five rules, in
 * order of how loudly they fail:
 *
 *   1. The file must parse as an APK at all.
 *   2. It must be THIS application's package (`com.fem.linexa`) — a foreign
 *      APK is never offered, whatever it claims to be.
 *   3. Its signing certificate must be the published Linexa release identity.
 *      A checksum proves the bytes; this proves the publisher. Android would
 *      reject a mismatch anyway, but doing it here produces a clear failure
 *      instead of a late platform error.
 *   4. It must run on this device (`minSdkVersion` vs the OS level).
 *   5. It must not be a downgrade (`versionCode` < the installed code) —
 *      Android's installer would refuse it; better to say so first.
 *
 * An archive with no readable signer fails closed: "could not verify" is not
 * "verified".
 */
class ApkValidator(
    private val metadataReader: ApkMetadataReader,
    private val appPackageName: String,
    private val deviceSdk: Int,
    private val expectedSignerSha256: Set<String>
) {

    fun validate(apkFile: File, asset: GithubReleaseAsset?, installedVersionCode: Long?): ApkValidationResult {
        val metadata = metadataReader.read(apkFile)
            ?: return ApkValidationResult.Invalid(ValidationKind.UNREADABLE)

        if (metadata.packageName != appPackageName) {
            return ApkValidationResult.Invalid(ValidationKind.WRONG_PACKAGE)
        }
        if (metadata.signerSha256.isEmpty() ||
            metadata.signerSha256.none { it.lowercase() in expectedSignerSha256 }
        ) {
            return ApkValidationResult.Invalid(ValidationKind.WRONG_SIGNATURE)
        }
        if (metadata.minSdk > deviceSdk) {
            return ApkValidationResult.Invalid(ValidationKind.DEVICE_TOO_OLD)
        }
        if (installedVersionCode != null && metadata.versionCode < installedVersionCode) {
            return ApkValidationResult.Invalid(ValidationKind.VERSION_DOWNGRADE)
        }
        if (asset?.sizeBytes != null && apkFile.length() != asset.sizeBytes) {
            return ApkValidationResult.Invalid(ValidationKind.SIZE_MISMATCH)
        }
        return ApkValidationResult.Valid(metadata)
    }

    sealed interface ApkValidationResult {
        data class Valid(val metadata: ApkMetadata) : ApkValidationResult
        data class Invalid(val kind: ValidationKind) : ApkValidationResult
    }

    enum class ValidationKind {
        UNREADABLE,
        WRONG_PACKAGE,
        WRONG_SIGNATURE,
        DEVICE_TOO_OLD,
        VERSION_DOWNGRADE,
        SIZE_MISMATCH
    }
}
