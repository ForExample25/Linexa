package com.fem.linexa.domain.hostinfo

/**
 * Host network posture, from ConnectivityManager's active network — the
 * minimum useful answer without touching location-gated or user-identifying
 * data.
 *
 * Deliberately excluded and documented as such:
 * - SSID/BSSID/Wi-Fi identity: needs location permission; it IS device
 *   positioning data. Never collected here.
 * - IP addresses are included at network-grain (what LinkProperties reports
 *   for the active network) because a terminal workspace legitimately needs
 *   to know its host-side addressing for server-bind diagnostics — but
 *   consumers relaying them onward should treat them as private-adjacent.
 *
 * @property isActive whether an app-accessible default network exists right now.
 * @property transports ordered transport names of the active network
 *   ("wifi", "cellular", "ethernet", "vpn"…), empty when offline/unknown.
 * @property primaryTransport first real transport with VPN stripped off, so
 *   "cellular under VPN" still reads as cellular.
 * @property isValidated NetworkCapabilities.NET_CAPABILITY_VALIDATED —
 *   Android actually reached the internet, not just association.
 * @property isMetered NET_CAPABILITY_NOT_METERED inverted; null when caps
 *   unreadable.
 * @property downlinkKbps/uplinkKbps modem-reported link bandwidth estimates;
 *   null when not provided. These are RADIO ESTIMATES, not throughput tests.
 * @property interfaceName active network's first non-loopback interface name.
 * @property ipv4Addresses / ipv6Addresses site addresses on that interface.
 * @property dnsServers dotted-quad/host strings of the active DNS set.
 * @property isPrivateDnsActive whether the profile enforces Private DNS.
 * @property interfaces EVERY app-visible network (not just the active one)
 *   with per-interface telemetry — the feed for the guest proc-net
 *   compatibility files (dev/route/if_inet6). Android only surfaces networks the app may know about; this
 *   list can never leak unrelated sockets or other profiles' interfaces.
 *   Additive-nullable so every pre-existing construction site compiles.
 */
data class HostNetworkInfo(
    val isActive: Boolean,
    val transports: List<String>,
    val primaryTransport: String?,
    val isValidated: Boolean?,
    val isMetered: Boolean?,
    val downlinkKbps: Int?,
    val uplinkKbps: Int?,
    val interfaceName: String?,
    val ipv4Addresses: List<String>,
    val ipv6Addresses: List<String>,
    val dnsServers: List<String>,
    val isPrivateDnsActive: Boolean?,
    val interfaces: List<HostNetInterface> = emptyList()
) {
    /** The interface belonging to the active network, when known by name. */
    fun activeInterface(): HostNetInterface? =
        interfaces.firstOrNull { it.isActiveNetwork }
            ?: interfaceName?.let { name -> interfaces.firstOrNull { it.name == name } }

    companion object {
        /** Canonical offline snapshot so consumers never see a null model. */
        fun offline(): HostNetworkInfo = HostNetworkInfo(
            isActive = false,
            transports = emptyList(),
            primaryTransport = null,
            isValidated = null,
            isMetered = null,
            downlinkKbps = null,
            uplinkKbps = null,
            interfaceName = null,
            ipv4Addresses = emptyList(),
            ipv6Addresses = emptyList(),
            dnsServers = emptyList(),
            isPrivateDnsActive = null,
            interfaces = emptyList()
        )
    }
}
