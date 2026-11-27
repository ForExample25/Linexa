package com.fem.linexa.service.update

/**
 * The published Linexa release signing identity, as a SHA-256 certificate digest.
 *
 * This is public information (it is printed by `apksigner verify --print-certs` and
 * published beside every release), not a secret. The update checker compares the
 * downloaded APK's signer against this value before handing it to Android's installer:
 * a checksum proves the bytes match what GitHub served, but only the signing identity
 * proves they were built by Linexa's release key. Android enforces the same rule at
 * install time; checking first turns a late `INSTALL_FAILED_UPDATE_INCOMPATIBLE` into a
 * clear, actionable failure before the system dialog appears.
 *
 * The value is the certificate of the published 0.1.0-beta APK:
 * `CN=Linexa, OU=Development, O=FemProject, C=US`
 * SHA-256 `aaab44eccf4db15c8816bd34f60864d7705432bf4f605d520c00d573ee63c520`.
 *
 * If the project ever rotates its signing key, both this constant and the existing
 * installed base require a deliberate migration plan — do not edit it to match a
 * locally generated debug key.
 */
object ReleaseSigningIdentity {

    const val SHA256: String =
        "aaab44eccf4db15c8816bd34f60864d7705432bf4f605d520c00d573ee63c520"

    /** Every certificate accepted for an in-app update (currently exactly one). */
    val ACCEPTED_SHA256: Set<String> = setOf(SHA256)
}
