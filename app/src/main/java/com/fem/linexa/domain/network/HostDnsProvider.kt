package com.fem.linexa.domain.network

/**
 * The Android host's live DNS servers (transformation spec §19/§20).
 *
 * ## Why this exists — the end of hardcoded DNS
 *
 * Linexa used to seed `/etc/resolv.conf` with `8.8.8.8` / `8.8.4.4` at
 * install time and never again. The spec forbids replacing those literals
 * with *another* hardcoded provider and requires boot-time provisioning
 * "based on the workspace configuration", where DNS mode `SYSTEM`/`DEFAULT`
 * must mean **the host's actual resolver topology** — the VPN/captive/
 * private-DNS-aware servers Android itself is using, not a global constant.
 *
 * The `SYSTEM` mode therefore resolves through this seam at every workspace
 * start (and install seed). Returns an empty list when the platform gives no
 * answer (airplane mode, no active network) — callers then leave the guest's
 * existing resolver untouched rather than writing an empty file, which keeps
 * offline workspaces resolvable through whatever their image shipped.
 *
 * Pure interface (no Android types) so the network provisioner stays
 * JVM-testable against a fake.
 */
fun interface HostDnsProvider {

    /**
     * The host's current DNS server address literals (IPv4/IPv6), in
     * platform preference order. Empty when unknown/unavailable.
     */
    suspend fun dnsServers(): List<String>
}
