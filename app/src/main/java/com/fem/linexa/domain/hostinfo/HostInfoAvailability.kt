package com.fem.linexa.domain.hostinfo

/**
 * Why a piece of Android host information is (or is not) available.
 *
 * The provider's central contract is "never fabricate": a value that cannot
 * be measured is absent, and every absence carries a *reason* so consumers
 * (the Terminal Workspace UI, the RootFS bridge, diagnostics) can tell the
 * platform limitation apart from an unexpected failure.
 *
 * The states map directly onto how Android restricts app-uid access to
 * hardware and system information:
 *
 * - [AVAILABLE] — measured on this call (or read from the OS), trustworthy.
 * - [UNAVAILABLE] — the platform gave no value for a field that normally has
 *   one (e.g. a sticky battery intent without temperature). Not an error and
 *   not permission-related; `null` fields inside a category model are this
 *   state at field granularity.
 * - [PERMISSION_DENIED] — a runtime permission the app has not been granted
 *   gates the information (e.g. location-gated Wi-Fi identity). Never thrown:
 *   reported, so UI can prompt for the permission while the bridge reports an
 *   honest gap instead of guessing.
 * - [UNSUPPORTED] — the device/API level lacks the feature entirely (e.g.
 *   thermal status below API 29).
 * - [RESTRICTED] — the feature exists but SELinux/hidepid refuses it to any
 *   untrusted_app process regardless of permissions (the /proc/stat,
 *   /proc/loadavg case on modern Android). Distinguishing RESTRICTED from
 *   PERMISSION_DENIED matters because no prompt can ever fix a RESTRICTED
 *   field — documenting that prevents "just ask for permission" advice being
 *   followed into a dead end.
 * - [ERROR] — collection threw unexpectedly. The category keeps its last
 *   known-good cached values where one exists; the status still reports why
 *   THIS refresh carries nothing new.
 */
enum class HostInfoAvailability {
    AVAILABLE,
    UNAVAILABLE,
    PERMISSION_DENIED,
    UNSUPPORTED,
    RESTRICTED,
    ERROR;

    /** True when at least some values of the category can be trusted. */
    val isUsable: Boolean
        get() = this == AVAILABLE

    /** True when the condition may resolve itself later (retry is meaningful). */
    val isTransient: Boolean
        get() = this == AVAILABLE || this == UNAVAILABLE || this == ERROR || this == PERMISSION_DENIED
}
