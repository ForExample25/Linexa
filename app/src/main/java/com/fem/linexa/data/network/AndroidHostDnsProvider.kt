package com.fem.linexa.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.network.HostDnsProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The production [HostDnsProvider]: reads the DNS servers Android itself is
 * using for the networks the device is actually on
 * ([ConnectivityManager.getAllNetworks] → [LinkProperties.dnsServers]).
 *
 * Transformation spec §19/§20: this is what `DnsMode.SYSTEM` means — the
 * host's live resolver topology (VPN DNS, Private DNS, carrier DNS), read at
 * provision time, never a compiled-in constant. Validation happens on the
 * consumer side (the same IP-literal gate every DNS write already passes);
 * here we only report what the platform says, in platform order.
 *
 * Every failure mode returns an empty list rather than throwing: no DNS
 * information is strictly better than a half-written resolv.conf, and the
 * network provisioner treats empty as "the guest's own resolver stays".
 *
 * 2026-08-29 workspace DNS failure fix: the empty result is no longer
 * silent. This provider is the FIRST link in the chain that ends in the
 * guest's /etc/resolv.conf, and its historical failure mode —
 * `getAllNetworks()` throwing SecurityException because the manifest was
 * missing ACCESS_NETWORK_STATE — degraded to emptyList() with no log at
 * all, which is exactly how every workspace ended up resolver-less with
 * only "Temporary failure resolving" to show for it. Every degradation now
 * logs WHY (which layer failed), so a workspace DNS incident can be
 * diagnosed from logcat without guessing.
 */
@Singleton
class AndroidHostDnsProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : HostDnsProvider {

    override suspend fun dnsServers(): List<String> = withContext(Dispatchers.IO) {
        val cm = runCatching {
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        }.getOrElse { e ->
            // Layer 1 failure: the connectivity service itself is unreachable.
            // Practically impossible on Android, but a silent emptyList here
            // would again hide a real defect behind a resolver-less guest.
            Logger.w(TAG, "host DNS unavailable: ConnectivityManager lookup failed (${e.javaClass.simpleName}: ${e.message})")
            return@withContext emptyList()
        }
        if (cm == null) {
            Logger.w(TAG, "host DNS unavailable: ConnectivityManager is null on this platform")
            return@withContext emptyList()
        }
        val servers = LinkedHashSet<String>()
        try {
            for (network in cm.allNetworks) {
                val lp = cm.getLinkProperties(network) ?: continue
                for (address in lp.dnsServers) {
                    val literal = address.hostAddress ?: continue
                    // Strip any IPv6 zone id (%wlan0) — not valid in resolv.conf.
                    val clean = literal.substringBefore('%')
                    if (clean.isNotEmpty()) servers.add(clean)
                }
            }
        } catch (e: Exception) {
            // The historic silent killer: without ACCESS_NETWORK_STATE,
            // getAllNetworks() throws SecurityException. Now visible.
            Logger.w(
                TAG,
                "host DNS unavailable: network enumeration failed (${e.javaClass.simpleName}: ${e.message}) — " +
                    "check that ACCESS_NETWORK_STATE is granted"
            )
            return@withContext emptyList()
        }
        val ordered = servers.toList()
        if (ordered.isEmpty()) {
            // Layer 2: permission fine, enumeration fine, but the platform
            // reports no DNS for any network — the genuinely-offline case.
            Logger.i(TAG, "host DNS unavailable: the platform reports no DNS servers for any active network (offline or VPN still settling)")
        } else {
            Logger.d(TAG, "host DNS: ${ordered.size} server(s) from active networks: $ordered")
        }
        ordered
    }

    private companion object {
        const val TAG = "HostDns"
    }
}
