package com.fem.linexa.ui.screens.workspace

import com.fem.linexa.service.runtime.features.DesktopInstallPolicy
import com.fem.linexa.service.runtime.features.DesktopPreset

/**
 * 2026-09-02 (guided desktop installer, Phase 3): the state machine the
 * Desktop settings panel renders for the guided Debian/Ubuntu + APT desktop
 * installation. Mirrors the install-flow's honest-progress conventions:
 * every field is backed by a real probe, a real apt quote, or real dpkg
 * output — nothing is animated forward and no package name or percentage is
 * ever invented.
 *
 * Flow: [Idle] → [Preparing] → [Quote] (preconditions + real `apt-get -s`
 * sizes + explicit confirmation) → [Installing] → [Completed] / [Failed].
 * The [Quote.alreadyInstalled] path short-circuits to "nothing to do" —
 * the idempotency contract (`DesktopPreset.presentCheckCommand` /
 * static probe first, install only on absence).
 */
sealed interface DesktopInstallUiState {

    /** No guided install in flight. */
    data object Idle : DesktopInstallUiState

    /** Static + guest preconditions are being gathered. */
    data class Preparing(val preset: DesktopPreset) : DesktopInstallUiState

    /**
     * The confirmation screen's facts. [blockReason] non-null disables the
     * confirm action (the flow cannot proceed safely); the UI renders the
     * reason from [blockReason], never from a free-text guess.
     *
     * [downloadBytes]/[installedBytes] are REAL apt-simulated values, null
     * when the simulation did not produce them — the UI must show "size
     * unknown" in that case, never an estimate dressed as a number.
     */
    data class Quote(
        val preset: DesktopPreset,
        val prerequisites: DesktopInstallPolicy.StaticPrerequisites,
        val downloadBytes: Long?,
        val installedBytes: Long?,
        val newPackages: Int?,
        val alreadyInstalled: Boolean,
        val blockReason: DesktopInstallPolicy.BlockReason?
    ) : DesktopInstallUiState

    /**
     * The install is running through the shared streaming exec channel.
     * [phase] reflects deterministic apt/dpkg markers only; [currentPackage]
     * is non-null only while a genuine `Selecting/Unpacking/Setting up` line
     * has been seen — no fabricated names.
     */
    data class Installing(
        val preset: DesktopPreset,
        val phase: DesktopInstallPhase,
        val currentPackage: String?
    ) : DesktopInstallUiState

    /** Install finished; the rootfs was re-probed and the session exists. */
    data class Completed(
        val preset: DesktopPreset,
        /** Rootfs-relative session launcher found by the post-install probe. */
        val sessionPath: String?
    ) : DesktopInstallUiState

    /** Install failed or verification failed; [reason] is the real output tail. */
    data class Failed(
        val reason: String,
        /**
         * 2026-09-02: non-null when the failure is the post-install
         * verification (apt exited 0 but no session appeared) — the UI then
         * renders the localized message for it instead of the raw reason.
         * The ViewModel layer holds no string resources, so the typed fact
         * travels and the UI speaks.
         */
        val verifyFailedPresetName: String? = null
    ) : DesktopInstallUiState
}

/**
 * The phases of the guided install command
 * (`apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y …`).
 * Ordered like the command itself; transitions are driven by deterministic
 * output markers, never by timers.
 */
enum class DesktopInstallPhase {
    /** `apt-get update` is running (Hit:/Get: index lines). */
    UPDATING_INDEX,

    /** apt has moved on to resolving/downloading the install (needs, trees). */
    DOWNLOADING,

    /** dpkg is selecting/unpacking/configuring packages (real package names). */
    INSTALLING
}
