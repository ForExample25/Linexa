package com.fem.linexa.domain.packagemanager

import javax.inject.Inject
import javax.inject.Singleton

/**
 * §1.92 — Lookup of a concrete [PackageProvider] by its canonical key.
 *
 * The detector produces a key; the registry resolves it to the provider
 * instance. The UI / ViewModel only ever sees the [PackageProvider]
 * interface through here, so adding a new distro = one new provider class
 * registered in this map (and one probe entry in [PackageManagerDetector]).
 */
@Singleton
class PackageManagerRegistry @Inject constructor(
    private val aptProvider: AptProvider,
    private val pacmanProvider: PacmanProvider,
    private val apkProvider: ApkProvider,
    private val dnfProvider: DnfProvider,
    private val zypperProvider: ZypperProvider
) {

    private val providers: Map<String, PackageProvider> = mapOf(
        PackageManagerDetector.KEY_APT to aptProvider,
        PackageManagerDetector.KEY_PACMAN to pacmanProvider,
        PackageManagerDetector.KEY_APK to apkProvider,
        PackageManagerDetector.KEY_DNF to dnfProvider,
        PackageManagerDetector.KEY_ZYPPER to zypperProvider
    )

    /** Provider for [key], or null when the key is unsupported. */
    fun providerFor(key: String): PackageProvider? = providers[key]

    /** All supported keys, in probe priority order. */
    val supportedKeys: List<String> get() = PackageManagerDetector.supportedKeys
}
