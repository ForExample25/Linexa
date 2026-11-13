package com.fem.linexa.domain.hostinfo

/**
 * One network interface as observed through app-accessible Android APIs —
 * the unit of exposure for the guest's `/proc/net/{dev,route,if_inet6}`
 * synthesis (Phase 8 of the host-bridge expansion).
 *
 * Every counter here is REAL kernel-accounted data obtained via public
 * platform surfaces: [android.net.LinkProperties] for names/addresses/MTU,
 * [android.net.NetworkCapabilities] for transport/state, and
 * [android.net.TrafficStats] for byte/packet counters. A `null` counter
 * means TrafficStats refused or did not track that cell for this interface
 * on this OEM build — it is rendered as "unknown", never as a fabricated
 * value. In `/proc/net/dev` synthesis, per-cell nulls collapse to 0 (the
 * file has no notion of N/A), which is documented honestly in the bridge's
 * capability map rather than hidden.
 *
 * Looped-back traffic is represented by the synthetic-but-always-real
 * `lo` row the bridge emits itself; this model carries only interfaces the
 * OS actually reported.
 *
 * Security envelope (why this is SAFE_READ_ONLY / READ_ONLY_SANITIZED and
 * not more): interface NAMES and COUNTERS describe transport, not identity —
 * no SSID/BSSID/MAC is ever carried here, per the standing no-position-data
 * rule of docs/HOST_SYSTEM_INFO.md §10.
 */
data class HostNetInterface(
    /** Kernel-visible name from LinkProperties ("wlan0", "rmnet_data0"…). */
    val name: String,
    /** Transport label from NetworkCapabilities ("wifi","cellular","ethernet","vpn"…). */
    val transport: String?,
    /** True when this belongs to the system default/active network. */
    val isActiveNetwork: Boolean,
    /** Link MTU bytes from LinkProperties; null when withheld. */
    val mtu: Int?,
    /** IPv4 addresses WITH prefix length, e.g. "192.168.1.7/24". */
    val ipv4WithPrefix: List<String>,
    /** IPv6 addresses WITH prefix length + scope flag, e.g. "fe80::…/64". */
    val ipv6WithPrefix: List<String>,
    /** IPv4 default gateway of this link when one exists. */
    val gatewayV4: String?,
    /** RX bytes since boot (TrafficStats); null when untracked. */
    val rxBytes: Long?,
    /** TX bytes since boot; null when untracked. */
    val txBytes: Long?,
    /** RX packets; null when untracked. */
    val rxPackets: Long?,
    /** TX packets; null when untracked. */
    val txPackets: Long?
) {
    /** Stable sort key: active first, then by name — deterministic overlays. */
    internal fun orderKey(): Int = if (isActiveNetwork) 0 else 1
}
