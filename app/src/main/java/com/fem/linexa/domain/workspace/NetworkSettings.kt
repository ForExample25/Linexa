package com.fem.linexa.domain.workspace

/**
 * Network Configuration — the per-workspace network settings as a pure value
 * plus its persistence encoding (§19/§26/§27 of the Core Runtime & Desktop
 * Transformation spec).
 *
 * Replaces the hardcoded `nameserver 8.8.8.8 / 8.8.4.4` story with an
 * explicit model:
 *
 * - **DNS — Custom DNS is an explicit optional feature** (2026-08-29
 *   redesign). The Workspace → Network section exposes it as an
 *   **Enabled/Disabled** switch: [DnsMode.CUSTOM] = enabled — [dnsServers]
 *   are applied at every workspace start, so the setting is *repeatable
 *   boot-time provisioning*, not a one-time install seed (§20); servers
 *   configured while disabled are ignored. [DnsMode.SYSTEM] = disabled (the
 *   default, and what every workspace that never recorded the setting
 *   resolves to) — no custom servers are forced and the guest resolver
 *   follows the host's live DNS (the normal/default Linexa networking
 *   path), with the WorkspaceNetworkProvisioner guaranteeing a deterministic
 *   final resolver state (existing resolvers honored; a missing one
 *   self-healed).
 * - **Guest package mirror** — [PackageMirrorMode.DEFAULT] leaves the
 *   guest's repository configuration untouched; [PackageMirrorMode.CUSTOM]
 *   switches it to [packageMirrorUrl] through the per-PM planner
 *   (`PackageMirrorPlanner`), distribution-aware.
 * - **RootFS download mirror** — [rootfsMirrorUrl], when set, overrides the
 *   catalog download URL for this workspace's (re)installs; the integrity
 *   story is unchanged because checksums still resolve from the catalog's
 *   official checksum source (`RootfsMirrorResolver`).
 *
 * ## Where it lives
 *
 * The same encoding rule as `FeatureSettings`: dotted keys inside
 * `WorkspaceMetadata.attributes` (the `Map<String, String>` Room column) —
 * **not** new columns, because the database is version 1 with no migrations
 * and a schema change would break every existing install.
 *
 * ## Reading is fail-open, writing is validated
 *
 * [from] never throws: an invalid entry degrades to the safe default
 * (SYSTEM — custom DNS disabled / no mirror). Validation lives on the write
 * path ([validateDnsServers], [validateMirrorUrl]) where a person is present
 * to be told.
 */
data class NetworkSettings(
    /** How the guest resolver is configured at workspace start. */
    val dnsMode: DnsMode = DnsMode.SYSTEM,
    /**
     * The custom DNS servers applied when [dnsMode] is [DnsMode.CUSTOM], in
     * order. Parsed from a comma/semicolon/space-separated attribute string;
     * unparseable entries are dropped on read (fail-open) and rejected on
     * write ([validateDnsServers]).
     */
    val dnsServers: List<String> = emptyList(),
    /** Whether the guest package repositories are switched to a custom mirror. */
    val packageMirrorMode: PackageMirrorMode = PackageMirrorMode.DEFAULT,
    /** The guest package mirror base URL (used when the mode is CUSTOM). */
    val packageMirrorUrl: String = "",
    /**
     * The RootFS download mirror override, or empty for the catalog default.
     * Global-effect by nature (installs happen through services, not the
     * runtime) — persisted per workspace so each workspace documents the
     * source it was installed from, and the install services read it for
     * their (re)install/retry paths.
     */
    val rootfsMirrorUrl: String = ""
) {

    /** Whether any persisted value would actually change runtime behavior. */
    val isEffectivelyDefault: Boolean
        get() = dnsMode == DnsMode.SYSTEM &&
            packageMirrorMode == PackageMirrorMode.DEFAULT &&
            packageMirrorUrl.isBlank() &&
            rootfsMirrorUrl.isBlank()

    /** Encoded back into attribute entries, for merging into `attributes`. */
    fun toAttributes(): Map<String, String> = mapOf(
        KEY_DNS_MODE to dnsMode.id,
        KEY_DNS_SERVERS to dnsServers.joinToString(SERVERS_SEPARATOR),
        KEY_PACKAGE_MIRROR_MODE to packageMirrorMode.id,
        KEY_PACKAGE_MIRROR_URL to packageMirrorUrl.trim(),
        KEY_ROOTFS_MIRROR_URL to rootfsMirrorUrl.trim()
    )

    /** DNS resolution mode for a workspace's guest (§19). */
    enum class DnsMode(val id: String) {
        /**
         * Custom DNS **disabled** (default): the guest resolver follows the
         * host's live DNS — the normal/default networking path. Any
         * persisted [NetworkSettings.dnsServers] are ignored.
         */
        SYSTEM("system"),

        /**
         * Custom DNS **enabled**: apply [NetworkSettings.dnsServers] at
         * every workspace start.
         */
        CUSTOM("custom");

        companion object {
            /** Total, fail-open: unknown/absent ids resolve to [SYSTEM]. */
            fun fromId(raw: String?): DnsMode = when (raw?.trim()?.lowercase()) {
                CUSTOM.id -> CUSTOM
                else -> SYSTEM
            }
        }
    }

    /** Guest package-mirror selection (§22). */
    enum class PackageMirrorMode(val id: String) {
        /** Keep the guest's own repository configuration. */
        DEFAULT("default"),

        /** Switch the guest's package repositories to the custom mirror. */
        CUSTOM("custom");

        companion object {
            /** Total, fail-open: unknown/absent ids resolve to [DEFAULT]. */
            fun fromId(raw: String?): PackageMirrorMode = when (raw?.trim()?.lowercase()) {
                CUSTOM.id -> CUSTOM
                else -> DEFAULT
            }
        }
    }

    companion object {

        const val KEY_DNS_MODE = "network.dns.mode"
        const val KEY_DNS_SERVERS = "network.dns.servers"
        const val KEY_PACKAGE_MIRROR_MODE = "network.mirror.package.mode"
        const val KEY_PACKAGE_MIRROR_URL = "network.mirror.package.url"
        const val KEY_ROOTFS_MIRROR_URL = "network.mirror.rootfs.url"

        /** Separator for [KEY_DNS_SERVERS]. Never valid inside an IP literal. */
        const val SERVERS_SEPARATOR = ","

        /**
         * Resolve a workspace's network settings from its persisted
         * attributes. Total and fail-open — every invalid value degrades to
         * the default rather than failing the workspace.
         */
        fun from(attributes: Map<String, String>): NetworkSettings = NetworkSettings(
            dnsMode = DnsMode.fromId(attributes[KEY_DNS_MODE]),
            dnsServers = parseDnsServers(attributes[KEY_DNS_SERVERS]),
            packageMirrorMode = PackageMirrorMode.fromId(attributes[KEY_PACKAGE_MIRROR_MODE]),
            packageMirrorUrl = attributes[KEY_PACKAGE_MIRROR_URL]?.trim().orEmpty(),
            rootfsMirrorUrl = attributes[KEY_ROOTFS_MIRROR_URL]?.trim().orEmpty()
        )

        /**
         * Parse the persisted server list. Each entry is kept only if it is
         * a syntactically valid IP literal — a hostname would silently
         * depend on the very resolver being configured (circular), so it is
         * not accepted here (the UI's write path says so explicitly).
         */
        fun parseDnsServers(raw: String?): List<String> =
            raw.orEmpty()
                .split(SERVERS_SEPARATOR, ";", " ", "\n", "\t")
                .map { it.trim() }
                .filter { it.isNotEmpty() && isValidIpAddress(it) }

        /**
         * Why the proposed DNS server list cannot be used, or null when it
         * can. A list must contain at least one valid IP literal, and every
         * non-blank entry must be one — mixed garbage is a typo, not intent.
         */
        fun validateDnsServers(raw: String): DnsRejection? {
            val entries = raw.split(SERVERS_SEPARATOR, ";", " ", "\n", "\t")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (entries.isEmpty()) return DnsRejection.EMPTY
            val invalid = entries.firstOrNull { !isValidIpAddress(it) }
                ?: return if (entries.size > MAX_DNS_SERVERS) DnsRejection.TOO_MANY else null
            return DnsRejection.INVALID_SERVER(invalid)
        }

        /**
         * Why the proposed mirror URL cannot be used, or null when it can.
         * Both mirrors end up embedded in guest shell commands (the package
         * mirror) or download requests (the rootfs mirror), so the guard is
         * structural, not cosmetic.
         */
        fun validateMirrorUrl(url: String): Boolean {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return false
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return false
            if (trimmed.length > MAX_MIRROR_URL_LENGTH) return false
            // Any whitespace, quote, shell metacharacter or delimiter would
            // break the guest-side command embedding or the HTTP request.
            if (trimmed.any { it.isWhitespace() || it in INVALID_URL_CHARS }) return false
            val host = trimmed.removePrefix("http://").removePrefix("https://")
                .substringBefore('/').substringBefore(':')
            return host.isNotEmpty()
        }

        /** Why a DNS server list was refused. Rendered by the UI, never here. */
        sealed class DnsRejection {
            data object EMPTY : DnsRejection()
            data object TOO_MANY : DnsRejection()
            data class INVALID_SERVER(val entry: String) : DnsRejection()
        }

        /** characters that must never appear in a mirror URL */
        private const val INVALID_URL_CHARS = "\"'`$&|;<>(){}[]!#\\"

        const val MAX_MIRROR_URL_LENGTH = 2048
        const val MAX_DNS_SERVERS = 4

        /**
         * Whether [value] is an IP literal (IPv4 or IPv6). Implemented
         * structurally rather than via `InetAddress.getByName` — that would
         * perform a DNS lookup for hostnames and cannot be used on a value
         * that is *supposed* to be an address.
         */
        fun isValidIpAddress(value: String): Boolean = isIpv4(value) || isIpv6(value)

        private fun isIpv4(value: String): Boolean {
            val parts = value.split('.')
            if (parts.size != 4) return false
            return parts.all { part ->
                part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } &&
                    part.toInt() in 0..255 &&
                    // no leading zeros (except the literal "0" itself)
                    (part == "0" || !part.startsWith("0"))
            }
        }

        private fun isIpv6(value: String): Boolean {
            if (value.isEmpty() || value.length > 45) return false
            val doubleColonCount = value.split("::").size - 1
            if (doubleColonCount > 1) return false
            val groups = if (doubleColonCount == 1) {
                val (head, tail) = value.split("::", limit = 2).let { it[0] to it[1] }
                val headGroups = head.split(':').filter { it.isNotEmpty() }
                val tailGroups = tail.split(':').filter { it.isNotEmpty() }
                headGroups + tailGroups
            } else {
                value.split(':')
            }
            if (groups.isEmpty() || groups.size > 8) return false
            return groups.all { it.isNotEmpty() && it.length <= 4 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }
        }
    }
}
