package com.fem.linexa.service.update

import com.fem.linexa.domain.update.GithubReleaseAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The pre-install identity gate. Each test names one rule and proves it fails
 * closed — a bad APK must never reach Android's installer with a "verified" label.
 */
class ApkValidatorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val expectedSigner = ReleaseSigningIdentity.SHA256

    private fun metadata(
        packageName: String = "com.fem.linexa",
        versionCode: Long = 1002,
        minSdk: Int = 28,
        signerSha256: List<String> = listOf(expectedSigner)
    ) = ApkMetadata(
        packageName = packageName,
        versionCode = versionCode,
        versionName = "0.2.0",
        minSdk = minSdk,
        targetSdk = 34,
        signerSha256 = signerSha256
    )

    private fun validator(metadata: ApkMetadata?) = ApkValidator(
        metadataReader = object : ApkMetadataReader {
            override fun read(apkFile: File): ApkMetadata? = metadata
        },
        appPackageName = "com.fem.linexa",
        deviceSdk = 36,
        expectedSignerSha256 = ReleaseSigningIdentity.ACCEPTED_SHA256
    )

    private fun apk(bytes: Int = 16): File =
        temp.newFile("update.apk").apply { writeBytes(ByteArray(bytes)) }

    @Test
    fun aMatchingPackageVersionAndSignerIsValid() {
        val result = validator(metadata()).validate(apk(), asset = null, installedVersionCode = 1001)
        assertTrue(result is ApkValidator.ApkValidationResult.Valid)
    }

    @Test
    fun anUnreadableArchiveIsRejected() {
        val result = validator(null).validate(apk(), asset = null, installedVersionCode = 1001)
        assertEquals(
            ApkValidator.ValidationKind.UNREADABLE,
            (result as ApkValidator.ApkValidationResult.Invalid).kind
        )
    }

    @Test
    fun aForeignPackageIsRejectedBeforeAnythingElse() {
        val result = validator(metadata(packageName = "com.other.app"))
            .validate(apk(), asset = null, installedVersionCode = 1001)
        assertEquals(
            ApkValidator.ValidationKind.WRONG_PACKAGE,
            (result as ApkValidator.ApkValidationResult.Invalid).kind
        )
    }

    @Test
    fun anArchiveWithNoReadableSignerFailsClosed() {
        val result = validator(metadata(signerSha256 = emptyList()))
            .validate(apk(), asset = null, installedVersionCode = 1001)
        assertEquals(
            ApkValidator.ValidationKind.WRONG_SIGNATURE,
            (result as ApkValidator.ApkValidationResult.Invalid).kind
        )
    }

    @Test
    fun aDifferentSigningCertificateIsRejected() {
        val result = validator(metadata(signerSha256 = listOf("00".repeat(32))))
            .validate(apk(), asset = null, installedVersionCode = 1001)
        assertEquals(
            ApkValidator.ValidationKind.WRONG_SIGNATURE,
            (result as ApkValidator.ApkValidationResult.Invalid).kind
        )
    }

    @Test
    fun theExpectedCertificateComparisonIsCaseInsensitive() {
        val result = validator(metadata(signerSha256 = listOf(expectedSigner.uppercase())))
            .validate(apk(), asset = null, installedVersionCode = 1001)
        assertTrue(result is ApkValidator.ApkValidationResult.Valid)
    }

    @Test
    fun anApkForANewerAndroidIsRejectedBeforeInstall() {
        val result = validator(metadata(minSdk = 37))
            .validate(apk(), asset = null, installedVersionCode = 1001)
        assertEquals(
            ApkValidator.ValidationKind.DEVICE_TOO_OLD,
            (result as ApkValidator.ApkValidationResult.Invalid).kind
        )
    }

    @Test
    fun aDowngradeIsRejected() {
        val result = validator(metadata(versionCode = 1001))
            .validate(apk(), asset = null, installedVersionCode = 1002)
        assertEquals(
            ApkValidator.ValidationKind.VERSION_DOWNGRADE,
            (result as ApkValidator.ApkValidationResult.Invalid).kind
        )
    }

    @Test
    fun anEqualVersionCodeIsAllowed() {
        // Android permits reinstalling the same code; the updater may legitimately
        // repair a corrupted download of the running release.
        val result = validator(metadata(versionCode = 1002))
            .validate(apk(), asset = null, installedVersionCode = 1002)
        assertTrue(result is ApkValidator.ApkValidationResult.Valid)
    }

    @Test
    fun aDeclaredSizeMismatchIsRejected() {
        val file = apk(bytes = 8)
        val asset = GithubReleaseAsset(
            name = "app-release.apk",
            downloadUrl = "https://github.com/ForExample25/Linexa/releases/download/v0.2.0/app-release.apk",
            sizeBytes = 9
        )
        val result = validator(metadata()).validate(file, asset, installedVersionCode = 1001)
        assertEquals(
            ApkValidator.ValidationKind.SIZE_MISMATCH,
            (result as ApkValidator.ApkValidationResult.Invalid).kind
        )
    }
}
