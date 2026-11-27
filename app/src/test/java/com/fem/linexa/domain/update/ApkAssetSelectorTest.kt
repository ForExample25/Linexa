package com.fem.linexa.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * APK-asset selection: only `.apk` files are candidates (never the source
 * zip/tar or `SHA256SUMS` sidecar), a single candidate is taken as-is, an
 * explicit `universal` wins among many, and several ABI-suffixed candidates
 * with no universal one is REFUSED rather than guessed.
 */
class ApkAssetSelectorTest {

    private fun asset(name: String): GithubReleaseAsset = GithubReleaseAsset(
        name = name,
        downloadUrl = "https://github.com/ForExample25/Linexa/releases/download/v0.1.0-beta/$name"
    )

    @Test
    fun aReleaseWithoutApkFilesSelectsNothing() {
        assertNull(
            ApkAssetSelector.select(
                listOf(asset("Source code (zip)"), asset("SHA256SUMS"), asset("rootfs.tar.gz"))
            )
        )
        assertNull(ApkAssetSelector.select(emptyList()))
    }

    @Test
    fun theSingleApkIsTakenAsIs() {
        val apk = asset("app-release.apk")
        assertEquals(apk, ApkAssetSelector.select(listOf(asset("SHA256SUMS"), apk)))
        assertEquals(apk, ApkAssetSelector.select(listOf(apk)))
    }

    @Test
    fun anExplicitUniversalAssetWinsAmongMany() {
        val universal = asset("linexa-universal-release.apk")
        val arm64 = asset("linexa-arm64-v8a-release.apk")
        val armv7 = asset("linexa-armeabi-v7a-release.apk")
        assertEquals(universal, ApkAssetSelector.select(listOf(arm64, armv7, universal)))
    }

    @Test
    fun thePlainUnmarkedApkWinsWhenThereIsNoUniversal() {
        val plain = asset("app-release.apk")
        val arm64 = asset("app-arm64-v8a-release.apk")
        val x8664 = asset("app-x86_64-release.apk")
        assertEquals(plain, ApkAssetSelector.select(listOf(arm64, x8664, plain)))
    }

    @Test
    fun severalAbiSplitsAndNoUniversalIsRefusedRatherThanGuessed() {
        val arm64 = asset("app-arm64-v8a.apk")
        val armv7 = asset("app-armeabi-v7a.apk")
        assertNull(ApkAssetSelector.select(listOf(arm64, armv7)))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        val universal = asset("Linexa-UNIVERSAL.apk")
        assertEquals(
            universal,
            ApkAssetSelector.select(listOf(universal, asset("app-ARM64-V8A.apk")))
        )
    }
}
