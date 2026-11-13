package com.fem.linexa.domain.hostinfo

/**
 * Process information capability boundary + what IS collectible today.
 *
 * Android's public APIs — and hidepid-backed /proc on the target devices —
 * do NOT let an untrusted_app enumerate other applications' processes: no
 * arbitrary PIDs, no other apps' memory/CPU. Fabricating a process list
 * would be exactly the class of lie this subsystem forbids, so the model is
 * explicit about the platform's answer instead:
 *
 * - [otherAppsVisibility] is permanently [HostInfoAvailability.RESTRICTED].
 *   A future extension (a companion debug service, root/ADB-only flows) can
 *   upgrade it by swapping the collector; the snapshot schema does not move.
 *
 * What remains genuinely useful and collectible:
 * - THIS app's own process identity and footprint (readable via /proc/self).
 * - The WORKSPACE process trees already served by
 *   [com.fem.linexa.domain.resource.ResourceMonitor] — that interface stays
 *   the workspace-scoped authority; nothing here duplicates it.
 *
 * @property selfPid Linexa's own pid.
 * @property selfProcessName applicationId process label.
 * @property selfRssKb resident set of the app process (/proc/self/status
 *   VmRSS via the shared parsers); null when unreadable.
 * @property selfPssKb proportional set from smaps_rollup when readable.
 * @property selfCpuTicks cumulative utime+stime ticks since start — callers
 *   diff two samples to get usage; always present when stat parses.
 * @property otherAppsVisibility see above.
 */
data class HostProcessInfo(
    val selfPid: Int,
    val selfProcessName: String?,
    val selfRssKb: Long?,
    val selfPssKb: Long?,
    val selfCpuTicks: Long?,
    val otherAppsVisibility: HostInfoAvailability
) {
    companion object {
        /** The constant the contract fixes for third-party process data. */
        val OTHER_APPS_RESTRICTED: HostInfoAvailability = HostInfoAvailability.RESTRICTED

        /**
         * The honest zero-knowledge response for JVM tests and any context
         * where even self-identity failed: pid 0 with RESTRICTED visibility,
         * no fabricated names or sizes.
         */
        fun unknown(): HostProcessInfo = HostProcessInfo(
            selfPid = 0,
            selfProcessName = null,
            selfRssKb = null,
            selfPssKb = null,
            selfCpuTicks = null,
            otherAppsVisibility = OTHER_APPS_RESTRICTED
        )
    }
}
