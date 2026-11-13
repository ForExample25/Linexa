package com.fem.linexa.service.runtime.features

import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.network.HostDnsProvider
import com.fem.linexa.domain.network.HostsFileMerger
import com.fem.linexa.domain.packagemanager.PackageManagerDetector
import com.fem.linexa.domain.packagemanager.PackageMirrorPlanner
import com.fem.linexa.domain.workspace.NetworkSettings
import com.fem.linexa.domain.workspace.WorkspaceRepository
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Network Configuration — applies a workspace's persisted network settings
 * to its *running* guest at every session start (§20 of the transformation
 * spec: "boot-time/application-time network provisioning").
 *
 * ## Custom DNS is an explicit, optional feature (2026-08-29 redesign)
 *
 * The workspace Network section exposes **Custom DNS [Enabled/Disabled]**:
 *
 * - **Disabled** ([NetworkSettings.DnsMode.SYSTEM] — the default, and what
 *   every workspace that never touched the setting resolves to): no custom
 *   servers are forced. The guest resolver follows the *host's* live DNS
 *   ([HostDnsProvider] — VPN/Private-DNS/carrier aware), which is the
 *   normal/default Linexa networking path: the guest shares the Android
 *   network stack (PRoot creates no network namespace), so the servers the
 *   host is using are by definition the correct ones for the workspace.
 *   When the host has no answer (offline / VPN settling), the guest's
 *   EXISTING resolver is left untouched — with one deterministic exception
 *   below.
 * - **Enabled** ([NetworkSettings.DnsMode.CUSTOM]): [NetworkSettings.dnsServers]
 *   are applied verbatim at every start. Servers configured while the
 *   feature is disabled are ignored.
 *
 * ## The deterministic-start guarantee (the hole this closes)
 *
 * The 2026-08-29 workspace DNS incident: a workspace whose rootfs shipped
 * NO /etc/resolv.conf (install-time seed skipped) AND whose host DNS read
 * failed at every start (missing ACCESS_NETWORK_STATE → empty provider
 * answer) ended with a resolver-less guest — "Temporary failure resolving"
 * in the terminal AND in apt, because both are the same resolver path.
 * "Leave the guest resolver untouched" is only safe when there IS one.
 *
 * Therefore every start ends in a KNOWN resolver state:
 *
 * 1. host DNS available → write it (SYSTEM) or the configured servers
 *    (CUSTOM), then read the file back and verify.
 * 2. host DNS unavailable (SYSTEM/disabled):
 *    - resolv.conf exists → leave it (the rootfs's own resolver is honored).
 *    - resolv.conf does NOT exist → seed a documented LAST-RESORT pair of
 *      public anycast resolvers, loudly logged and surfaced as
 *      [ProvisionResult.DnsSource.LAST_RESORT]. A missing file is not
 *      "the guest's resolver" — seeding it is the only way a disabled
 *      feature still leaves working default networking. Never a silent
 *      resolver-less start.
 *
 * What it does, in order, each step independent and fail-open:
 *
 * 1. **DNS** — provisioned per the mode above, never seeded once. The write
 *    is atomic (`rm -f` first — the Arch/Fedora systemd-resolved
 *    dangling-symlink case is exactly the one RootfsFixupService handled at
 *    install time — then write+rename) and a Linexa-ownership header marks
 *    the file as managed.
 * 2. **the hosts file** (`/etc/hosts`): reads the current file, merges a
 *    Linexa-managed section ([HostsFileMerger] — marker-delimited,
 *    idempotent, user lines preserved), writes it back with root ownership
 *    parity with resolv.conf. Only entries Linexa previously wrote are ever
 *    removed (§21). This runs on EVERY start — including fully-default
 *    workspaces — so loopback entries are re-established even after an
 *    image/config wipe (the old `isEffectivelyDefault` early-return skipped
 *    them, contradicting §21).
 * 3. **Package mirror** ([PackageMirrorMode.CUSTOM] only): applies the ([PackageMirrorMode.CUSTOM] only): applies the
 *    per-PM mirror switch ([PackageMirrorPlanner], distribution-aware with
 *    backup/restore). The PM is resolved through the SAME detection the
 *    Package Manager tab uses ([PackageManagerDetector]'s probe + os-release
 *    tie-break — the old ad-hoc `which | head -1` could pick the wrong
 *    manager on multi-PM guests). Skipped when the mirror is already applied
 *    (the managed marker file exists), so a start is free the second time.
 *
 * Everything runs through [GuestFeatureHost] one-shot execs — the same
 * seam the other features use — and the whole provisioner is fail-open:
 * a DNS or mirror failure must never cost the user their session (§28:
 * "preserve workspace runtime, expose diagnostic, allow retry"). The last
 * outcome is returned from [provisionOnStart] and kept in memory
 * ([lastResultFor]) for the workspace diagnostics surface (§29) and the
 * workspace health registry (§6 — Degraded health on failure).
 */
@Singleton
class WorkspaceNetworkProvisioner @Inject constructor(
    private val workspaceRepository: WorkspaceRepository,
    private val guestHost: GuestFeatureHost,
    /**
     * Transformation spec §19/§20: the host's live DNS servers for the
     * custom-DNS-DISABLED (default) path. Replaces every hardcoded resolver
     * literal Linexa used to seed.
     */
    private val hostDnsProvider: HostDnsProvider
) {

    /** Per-workspace last provisioning outcome, for the diagnostics surface. */
    private val lastResults = java.util.concurrent.ConcurrentHashMap<String, ProvisionResult>()

    /** The last known outcome for [workspaceId], or null before the first start. */
    fun lastResultFor(workspaceId: String): ProvisionResult? = lastResults[workspaceId]

    /**
     * Provision the guest's network configuration. Called once per session,
     * right after the session reaches `Running`. Never throws; returns the
     * outcome for the health registry / diagnostics surface.
     */
    suspend fun provisionOnStart(workspaceId: String, settings: NetworkSettings): ProvisionResult {
        val result = runCatching { provision(workspaceId, settings) }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                Logger.w(TAG, "$workspaceId: network provisioning threw — workspace keeps running: ${e.message}")
                ProvisionResult(
                    dnsApplied = false,
                    dnsFailure = e.message,
                    hostsApplied = false,
                    hostsFailure = null,
                    mirrorApplied = false,
                    mirrorFailure = "provisioning error"
                )
            }
        lastResults[workspaceId] = result
        Logger.i(
            TAG,
            "$workspaceId: network provisioning done — dns=${result.dnsApplied} (${result.dnsSource.label}" +
                (result.dnsServersApplied.takeIf { it.isNotEmpty() }?.let { ", servers=$it" } ?: "") +
                ") hosts=${result.hostsApplied} mirror=${result.mirrorApplied} " +
                "(failures: dns=${result.dnsFailure} hosts=${result.hostsFailure} mirror=${result.mirrorFailure})"
        )
        return result
    }

    private suspend fun provision(workspaceId: String, settings: NetworkSettings): ProvisionResult {
        val dnsResult = applyDns(workspaceId, settings)
        val hostsResult = applyHosts(workspaceId, settings)
        val mirrorResult = applyPackageMirror(workspaceId, settings)
        return ProvisionResult(
            dnsApplied = dnsResult.first,
            dnsSource = dnsResult.third.source,
            dnsServersApplied = dnsResult.third.servers,
            dnsFailure = dnsResult.second,
            hostsApplied = hostsResult.first,
            hostsFailure = hostsResult.second,
            mirrorApplied = mirrorResult.first,
            mirrorFailure = mirrorResult.second
        )
    }

    /** Returns (applied, failure-reason, source+servers) — failure reason null on success/skip. */
    private suspend fun applyDns(
        workspaceId: String,
        settings: NetworkSettings
    ): Triple<Boolean, String?, DnsOutcome> {
        when (settings.dnsMode) {
            // ── Custom DNS ENABLED: apply the user's configured servers ──
            NetworkSettings.DnsMode.CUSTOM -> {
                if (settings.dnsServers.isEmpty()) {
                    Logger.w(
                        TAG,
                        "$workspaceId: custom DNS enabled but no valid server is configured — " +
                            "guest resolver left untouched (configure servers in Workspace → Network)"
                    )
                    return Triple(false, null, DnsOutcome(ProvisionResult.DnsSource.SKIPPED))
                }
                probeReachability(workspaceId, settings.dnsServers)
                val body = settings.dnsServers.joinToString("\n") { "nameserver $it" }
                val result = guestHost.execCommand(workspaceId, DnsResolvConfCommand.build(body))
                if (result.exitCode != 0) {
                    return Triple(
                        false,
                        "custom DNS: resolv.conf write failed (exit ${result.exitCode})",
                        DnsOutcome(ProvisionResult.DnsSource.CUSTOM)
                    )
                }
                val verification = verifyResolvConf(workspaceId, settings.dnsServers, "custom DNS")
                return Triple(
                    verification == null,
                    verification,
                    DnsOutcome(ProvisionResult.DnsSource.CUSTOM, settings.dnsServers)
                )
            }

            // ── Custom DNS DISABLED (default): follow the host's live DNS ──
            //
            // Rewritten at every start (a phone moves networks; the guest
            // must follow). No host answer (offline) → leave an EXISTING
            // guest resolver untouched; an empty resolv.conf would break an
            // otherwise working offline guest, and inventing a fallback
            // server for an EXISTING resolver would be the exact
            // hardcoded-DNS the spec removed. But a MISSING resolv.conf is
            // not "the guest's resolver" — leaving it absent is how a
            // workspace ends up resolver-less with "Temporary failure
            // resolving", so the missing-file case is deterministically
            // self-healed (see the class KDoc).
            NetworkSettings.DnsMode.SYSTEM -> {
                val hostServers = runCatching { hostDnsProvider.dnsServers() }
                    .getOrElse { emptyList() }
                    .filter { NetworkSettings.validateDnsServers(it) == null }
                    .distinct()
                if (hostServers.isNotEmpty()) {
                    probeReachability(workspaceId, hostServers)
                    val body = hostServers.joinToString("\n") { "nameserver $it" }
                    val result = guestHost.execCommand(workspaceId, DnsResolvConfCommand.build(body))
                    if (result.exitCode != 0) {
                        return Triple(
                            false,
                            "host DNS: resolv.conf write failed (exit ${result.exitCode})",
                            DnsOutcome(ProvisionResult.DnsSource.SYSTEM, hostServers)
                        )
                    }
                    val verification = verifyResolvConf(workspaceId, hostServers, "host DNS")
                    return Triple(
                        verification == null,
                        verification,
                        DnsOutcome(ProvisionResult.DnsSource.SYSTEM, hostServers)
                    )
                }
                // Host DNS unavailable. Honor an existing resolver…
                val exists = resolvConfExists(workspaceId)
                if (exists) {
                    Logger.i(
                        TAG,
                        "$workspaceId: custom DNS disabled and host DNS unavailable — " +
                            "leaving the guest's existing /etc/resolv.conf untouched"
                    )
                    return Triple(false, null, DnsOutcome(ProvisionResult.DnsSource.SKIPPED))
                }
                // …but never leave a resolver-less guest behind: no file AND
                // no host answer means the default path cannot produce a
                // working resolver on its own. Seed the documented
                // last-resort anycast pair, loudly. This is the one place a
                // well-known literal may appear, it is only ever used when
                // the guest has NO resolver at all, and it is reported as
                // its own source so the diagnostics surface shows it.
                Logger.w(
                    TAG,
                    "$workspaceId: custom DNS disabled, host DNS unavailable AND the guest has no " +
                        "/etc/resolv.conf — seeding last-resort public resolvers $LAST_RESORT_SERVERS " +
                        "(enable custom DNS in Workspace → Network to override)"
                )
                val body = LAST_RESORT_SERVERS.joinToString("\n") { "nameserver $it" }
                val result = guestHost.execCommand(workspaceId, DnsResolvConfCommand.build(body))
                if (result.exitCode != 0) {
                    return Triple(
                        false,
                        "last-resort DNS: resolv.conf write failed (exit ${result.exitCode})",
                        DnsOutcome(ProvisionResult.DnsSource.LAST_RESORT)
                    )
                }
                val verification = verifyResolvConf(workspaceId, LAST_RESORT_SERVERS, "last-resort DNS")
                return Triple(
                    verification == null,
                    verification,
                    DnsOutcome(ProvisionResult.DnsSource.LAST_RESORT, LAST_RESORT_SERVERS)
                )
            }
        }
    }

    /**
     * Whether the guest currently has a resolver file at all. A dangling
     * symlink (systemd-resolved stub) answers "absent" — it is not a usable
     * resolver, and the seed path's `rm -f` handles replacing it.
     */
    private suspend fun resolvConfExists(workspaceId: String): Boolean {
        val result = guestHost.execCommand(workspaceId, DnsResolvConfCommand.existsCommand())
        if (result.exitCode != 0) {
            // Cannot ask the guest — assume absent so the deterministic
            // self-heal still runs (a false "absent" only ever ADDS a
            // resolver; a false "present" could leave none).
            Logger.w(TAG, "$workspaceId: resolv.conf existence probe failed (exit ${result.exitCode}) — treating as absent")
            return false
        }
        return result.output.contains(DnsResolvConfCommand.EXISTS_MARKER_PRESENT)
    }

    /**
     * Read the just-written resolver back and verify it took effect.
     * Returns null on success, or a failure reason that names the layer.
     * This is what turns "DNS configured but silently not applied" into an
     * observable, diagnosable outcome instead of a later "Temporary failure
     * resolving" with no provenance.
     */
    private suspend fun verifyResolvConf(
        workspaceId: String,
        expectedServers: List<String>,
        layer: String
    ): String? {
        val read = guestHost.execCommand(workspaceId, DnsResolvConfCommand.readCommand())
        if (read.exitCode != 0) {
            return "$layer: resolv.conf read-back failed (exit ${read.exitCode})"
        }
        val content = read.output
        if (!content.contains("nameserver")) {
            Logger.w(TAG, "$workspaceId: $layer verification — resolv.conf has no nameserver line, content: ${content.take(200)}")
            return "$layer: resolv.conf read-back contains no nameserver entry"
        }
        val missing = expectedServers.filter { !content.contains(it) }
        if (missing.isNotEmpty()) {
            Logger.w(
                TAG,
                "$workspaceId: $layer verification FAILED — expected $expectedServers but read back: ${content.take(200)}"
            )
            return "$layer: resolv.conf read-back is missing $missing — the write did not take effect"
        }
        Logger.d(TAG, "$workspaceId: $layer verification OK — resolv.conf: ${content.lineSequence().filter { it.isNotBlank() }.joinToString(" | ")}")
        return null
    }

    /**
     * Best-effort reachability probe of the resolvers about to be written.
     * The guest shares the app's network stack (no network namespace under
     * PRoot), so a TCP connect from this process to server:53 is the same
     * reachability the guest will see. Strictly diagnostic — a probe
     * failure is logged with the layer it indicts, never gating the write
     * (the user's explicit servers, or the platform's own answer, are still
     * the thing to apply; a blocked :53 says WHY DNS will be slow/dead).
     */
    private suspend fun probeReachability(workspaceId: String, servers: List<String>) {
        for (server in servers.take(REACHABILITY_PROBE_LIMIT)) {
            val reachable = withContext(Dispatchers.IO) {
                runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(server, DNS_PORT), REACHABILITY_PROBE_TIMEOUT_MS)
                        true
                    }
                }.getOrElse { false }
            }
            if (reachable) {
                Logger.d(TAG, "$workspaceId: resolver $server reachable on TCP/$DNS_PORT")
            } else {
                Logger.w(
                    TAG,
                    "$workspaceId: resolver $server did NOT answer TCP/$DNS_PORT within " +
                        "$REACHABILITY_PROBE_TIMEOUT_MS ms — DNS through it will be slow or failing " +
                        "(network blocking port 53, wrong network, or the resolver is on a network the device left)"
                )
            }
        }
    }

    private suspend fun applyHosts(workspaceId: String, settings: NetworkSettings): Pair<Boolean, String?> {
        val read = guestHost.execCommand(workspaceId, HOSTS_READ_COMMAND)
        if (read.exitCode != 0) {
            return false to "hosts read failed (exit ${read.exitCode})"
        }
        val hostname = guestHost.execCommand(workspaceId, HOSTNAME_READ_COMMAND)
            .output.lineSequence().firstOrNull()?.trim().orEmpty()
        val merged = HostsFileMerger.merge(
            existingContent = read.output,
            managedEntries = HostsFileMerger.managedEntriesFor(hostname)
        )
        val write = guestHost.execCommand(workspaceId, DnsResolvConfCommand.writeHostsCommand(merged))
        if (write.exitCode != 0) {
            return false to "hosts write failed (exit ${write.exitCode})"
        }
        return true to null
    }

    private suspend fun applyPackageMirror(workspaceId: String, settings: NetworkSettings): Pair<Boolean, String?> {
        if (settings.packageMirrorMode != NetworkSettings.PackageMirrorMode.CUSTOM) {
            return false to null
        }
        val url = settings.packageMirrorUrl.trim()
        if (!NetworkSettings.validateMirrorUrl(url)) {
            return false to "mirror url rejected"
        }
        // §B fix: the SAME probe + os-release tie-break the Package Manager
        // tab uses — the previous ad-hoc `which … | head -1` could mirror the
        // wrong manager on a multi-PM guest (e.g. apk present on a Debian).
        val which = guestHost.execCommand(workspaceId, PackageManagerDetector.PROBE_COMMAND)
        val osRelease = guestHost.execCommand(workspaceId, PackageManagerDetector.OS_RELEASE_COMMAND).output
        val pmKey = PackageManagerDetector.resolve(which.output, osRelease)
        if (pmKey == null) {
            return false to "no supported package manager for mirror apply"
        }
        val apply = PackageMirrorPlanner.applyCommand(pmKey, url)
        if (apply == null) {
            return false to "no supported package manager for mirror apply"
        }
        // Idempotence marker: the planner's commands are backup-guarded, but
        // the marker makes "already applied" observable and cheap to check.
        // Includes the dnf/zypper managed files (previously only the
        // apt/apk/pacman files were checked, so dnf/zypper re-applied —
        // and zypper's re-apply clobbered repos the user re-added).
        val marker = guestHost.execCommand(workspaceId, MIRROR_MARKER_CHECK)
        if (marker.exitCode == 0 && marker.output.contains(url)) {
            Logger.d(TAG, "$workspaceId: package mirror already applied")
            return true to null
        }
        val result = guestHost.execCommand(workspaceId, apply)
        if (result.exitCode != 0) {
            return false to "mirror apply failed via '$pmKey' (exit ${result.exitCode})"
        }
        return true to null
    }

    /** Where the DNS step's servers came from plus the servers actually applied. */
    private data class DnsOutcome(
        val source: ProvisionResult.DnsSource,
        val servers: List<String> = emptyList()
    )

    /**
     * The outcome of one [provisionOnStart] run. All fields are
     * observability facts for the workspace diagnostics surface (§29) —
     * "Network configured? DNS applied?" must be answerable without logcat.
     */
    data class ProvisionResult(
        val dnsApplied: Boolean,
        val dnsFailure: String? = null,
        val hostsApplied: Boolean,
        val hostsFailure: String? = null,
        val mirrorApplied: Boolean,
        val mirrorFailure: String? = null,
        /** Where the DNS provisioning came from (§20 observability). */
        val dnsSource: DnsSource = DnsSource.SKIPPED,
        /**
         * The resolver list actually written this run (empty when nothing
         * was written) — the single place the diagnostics surface reads
         * "which servers is this workspace using".
         */
        val dnsServersApplied: List<String> = emptyList()
    ) {
        val hasFailure: Boolean
            get() = dnsFailure != null || hostsFailure != null || mirrorFailure != null

        /** Which resolver source the DNS step used. */
        enum class DnsSource(val label: String) {
            /** User-configured servers (custom DNS enabled). */
            CUSTOM("custom"),

            /** The host's live DNS servers (custom DNS disabled — default). */
            SYSTEM("system"),

            /**
             * Public last-resort resolvers, seeded ONLY because the guest
             * had no resolv.conf at all and the host had no DNS answer.
             */
            LAST_RESORT("last-resort"),

            /** Nothing written this run (existing resolver honored / nothing configured). */
            SKIPPED("skipped")
        }
    }

    private companion object {
        const val TAG = "NetworkProvisioner"

        /**
         * The documented last-resort resolver pair: two independent public
         * anycast services. Used ONLY for the "guest has no resolv.conf AND
         * the host has no DNS answer" case (see the class KDoc) — never as
         * the default source, never while an existing resolver exists.
         */
        val LAST_RESORT_SERVERS = listOf("1.1.1.1", "8.8.8.8")

        const val DNS_PORT = 53
        const val REACHABILITY_PROBE_TIMEOUT_MS = 2_000
        const val REACHABILITY_PROBE_LIMIT = 2

        /** Read the guest's hosts file (exit 0 always — a missing file reads empty). */
        const val HOSTS_READ_COMMAND = "cat /etc/hosts 2>/dev/null || true"

        const val HOSTNAME_READ_COMMAND = "hostname 2>/dev/null || true"

        /** The planner drops a marker with the applied URL into the managed file. */
        const val MIRROR_MARKER_CHECK =
            "cat /etc/apt/sources.list.d/linexa-mirror.sources /etc/apk/repositories " +
                "/etc/pacman.d/mirrorlist /etc/yum.repos.d/linexa-mirror.repo " +
                "/etc/zypp/repos.d/linexa-mirror.repo 2>/dev/null | head -20"
    }
}

/**
 * Pure builders for the guest-side resolver/hosts file writes. Kept separate
 * from the provisioner so the exact shell (quoting, atomicity, ownership
 * header) is unit-testable without a guest.
 */
object DnsResolvConfCommand {

    /** [existsCommand] output token when the resolver file is present. */
    const val EXISTS_MARKER_PRESENT = "LINEXA_RESOLV_PRESENT"

    /** [existsCommand] output token when it is absent (incl. dangling symlink). */
    const val EXISTS_MARKER_ABSENT = "LINEXA_RESOLV_ABSENT"

    /**
     * Atomically rewrite `/etc/resolv.conf` with [body]:
     * `rm -f` first (it may be a dangling symlink to systemd-resolved's
     * stub — writing through it would fail), then write a sibling and rename
     * it into place, then strip the sibling on failure paths with `|| true`.
     */
    fun build(body: String): String {
        val safeBody = body.replace("'", "'\\''")
        return "rm -f /etc/resolv.conf && printf '%b' '$safeBody\n' > /etc/resolv.conf.linexa && " +
            "chown 0:0 /etc/resolv.conf.linexa 2>/dev/null; " +
            "mv -f /etc/resolv.conf.linexa /etc/resolv.conf && echo '# managed by Linexa' >> /etc/resolv.conf"
    }

    /**
     * Probe whether the guest HAS a usable resolver path: a regular file (or
     * a live symlink) at /etc/resolv.conf. A dangling symlink answers
     * "absent" on purpose — it resolves to nothing under PRoot.
     */
    fun existsCommand(): String =
        "if [ -e /etc/resolv.conf ]; then echo $EXISTS_MARKER_PRESENT; else echo $EXISTS_MARKER_ABSENT; fi"

    /** Read the resolver file back (verification; exit 0 always, empty when absent). */
    fun readCommand(): String = "cat /etc/resolv.conf 2>/dev/null || true"

    /**
     * Atomically write [content] to `/etc/hosts` — same symlink-safe shape
     * as [build]; [content] comes from [HostsFileMerger] and is written
     * through a single-quoted heredoc-safe printf.
     *
     * §B parity fix: `chown 0:0` now matches [build] — the hosts file is a
     * root-owned system file on every distribution, and a write that leaves
     * it with a different owner than resolv.conf is an inconsistent posture.
     */
    fun writeHostsCommand(content: String): String {
        val safe = content.replace("\\", "\\\\").replace("%", "%%").replace("'", "'\\''")
        return "rm -f /etc/hosts && printf '%b' '$safe' > /etc/hosts.linexa && " +
            "chown 0:0 /etc/hosts.linexa 2>/dev/null; " +
            "mv -f /etc/hosts.linexa /etc/hosts"
    }
}
