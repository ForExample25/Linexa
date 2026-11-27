package com.fem.linexa.domain.distro

/**
 * One entry in [DistroCatalog]: everything needed to download and verify a
 * distro's rootfs archive without the caller needing to know which real-
 * world source it comes from.
 *
 * Adding a new distribution later is meant to be exactly this: one more
 * [DistroManifest] in [DistroCatalog.all]. Two [DistroSource] shapes cover
 * what's actually out there — [DistroSource.Direct] for a source that
 * publishes a stable versioned URL (Alpine, Ubuntu Base, Arch Linux ARM,
 * Debian's debuerreotype branch), and [DistroSource.Resolved] for one that
 * only exposes a timestamped/rotating build directory and needs a small
 * lookup first (linuxcontainers.org, used here for Fedora — see
 * [LinuxContainersResolver]). A distro with a source shaped like neither
 * just needs a new small [DistroSourceResolver], not a rewrite of anything
 * that consumes [DistroManifest].
 */
data class DistroManifest(
    val id: String,
    val displayName: String,
    val version: String,
    val avatarLetter: String,
    val avatarColorHex: String,
    val source: DistroSource,
    /** Shown in the picker so a choice made from real information, not a guess at what "minimal" means for five different build systems. */
    val approxDownloadSizeLabel: String,
    /**
     * 2026-09-01 product gate: only Debian and Ubuntu were open for install;
     * every other catalog entry stayed visible but LOCKED in the UI (dimmed,
     * non-clickable, "locked" badge) and was rejected by the
     * download/install paths.
     *
     * 2026-09-04 (user request): the gate is OPEN again — every catalog
     * entry is enabled, so the download/add-distro section offers all of
     * them. The flag and its consumers (catalog pane, install flow,
     * download service) are kept so restricting an entry later stays a
     * one-flag change here.
     */
    val enabled: Boolean = true
)

sealed interface DistroSource {
    /** A URL that's already the real, ready-to-download archive — no extra HTTP round trip needed to find it. */
    data class Direct(
        val downloadUrl: String,
        val checksumUrl: String? = null,
        val checksumFormat: ChecksumFormat = ChecksumFormat.NONE
    ) : DistroSource

    /**
     * A source whose real download URL isn't fixed (e.g. it lives under a
     * build-timestamped directory that changes every rebuild) and has to be
     * looked up through [resolverId] first — see [DistroSourceResolver].
     */
    data class Resolved(
        val resolverId: String,
        val distro: String,
        val release: String,
        val arch: String = "arm64"
    ) : DistroSource
}

/** How to turn a checksum sidecar file's contents into the one hex digest that matters for this download. */
enum class ChecksumFormat {
    /** No sidecar available from this source — checksum verification is skipped for it, honestly, rather than faked. */
    NONE,

    /** Sidecar's entire content is one hash (optionally followed by the filename), e.g. Alpine's `*.sha256` files. */
    SINGLE_HASH_LINE,

    /** Sidecar lists many "hash  filename" lines (e.g. Ubuntu's `SHA256SUMS`); the matching filename's line is the one that counts. */
    MULTI_HASH_LIST
}

/**
 * Built-in distros. Real, currently-live sources as of the last time this
 * catalog was checked against them (Jul 2026) — not placeholders. Where a
 * source doesn't publish a checksum this honestly says so via
 * [ChecksumFormat.NONE] rather than inventing one; see each entry's comment
 * for exactly what was and wasn't verifiable from this sandbox (no network
 * access here to actually fetch and confirm each checksum file's current
 * content — the URL patterns themselves are confirmed real and current).
 */
object DistroCatalog {
    val all: List<DistroManifest> = listOf(
        DistroManifest(
            id = "alpine",
            displayName = "Alpine Linux",
            version = "3.24.1",
            avatarLetter = "A",
            avatarColorHex = "#0D597F",
            approxDownloadSizeLabel = "~4 MB",
            // 2026-09-01: locked — Debian/Ubuntu were the only supported
            // install targets.
            // 2026-09-04 (user request): UNLOCKED — the download/add-distro
            // section is open again; the entry's source/checksum data below
            // was retained for exactly this flip.
            enabled = true,
            source = DistroSource.Direct(
                downloadUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.1-aarch64.tar.gz",
                // Alpine publishes a matching "<file>.sha256" next to every release, format: "<hexdigest>  <filename>".
                checksumUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.1-aarch64.tar.gz.sha256",
                checksumFormat = ChecksumFormat.SINGLE_HASH_LINE
            )
        ),
        DistroManifest(
            id = "ubuntu",
            displayName = "Ubuntu",
            version = "22.04.5 LTS (Jammy)",
            avatarLetter = "U",
            avatarColorHex = "#E95420",
            approxDownloadSizeLabel = "~26 MB",
            source = DistroSource.Direct(
                downloadUrl = "https://cdimage.ubuntu.com/ubuntu-base/releases/jammy/release/ubuntu-base-22.04.5-base-arm64.tar.gz",
                // Confirmed present in the same release/ directory as the tarball itself.
                checksumUrl = "https://cdimage.ubuntu.com/ubuntu-base/releases/jammy/release/SHA256SUMS",
                checksumFormat = ChecksumFormat.MULTI_HASH_LIST
            )
        ),
        DistroManifest(
            id = "archlinux",
            displayName = "Arch Linux ARM",
            // Deliberately not a fixed version string: ALARM only ships a
            // floating "latest" tarball for its rolling release, not
            // versioned snapshots the way the others below are. Resuming a
            // partial download of it is still safe (whatever bytes were
            // already fetched came from the same current build, since it
            // doesn't change within the seconds-to-minutes a download
            // takes), but repeatedly *re-downloading* it a month apart can
            // legitimately fetch two different underlying builds.
            version = "latest (rolling)",
            avatarLetter = "AR",
            avatarColorHex = "#1793D1",
            approxDownloadSizeLabel = "~500 MB",
            // 2026-09-01: locked — see the alpine entry.
            // 2026-09-04 (user request): UNLOCKED — see the alpine entry.
            enabled = true,
            source = DistroSource.Direct(
                // Phase 0 hardening: the primary os.archlinuxarm.org endpoint
                // serves plain HTTP only (302 → http:// mirror, no TLS), so a
                // ~500 MB archive downloaded through it could be tampered with
                // in transit. The fl.us mirror is an official ALARM mirror that
                // serves the same "latest" tarball over HTTPS (verified
                // reachable Jul 2026; pick a geographically closer official
                // mirror if one is ever verified the same way).
                downloadUrl = "https://fl.us.mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz"
                // No ChecksumFormat that fits: ALARM only publishes an .md5
                // (32-hex, not a 64-hex SHA-256 the pipeline verifies) and a
                // detached .sig next to this file. Verifying via GPG would
                // need a bundled public key + a real OpenPGP implementation,
                // out of scope here — TLS integrity at rest+in-transit is the
                // mitigation for now, tracked as a known gap rather than
                // silently claiming a verification that isn't happening.
                // Phase 06 (OPT-SEC-006): that gap is now EXPLICIT — "archlinux"
                // is the sole member of DistroCatalog.CHECKSUM_WAIVED, and
                // checksumPolicyViolations() (pinned by test) fails any new
                // checksumless Direct entry that isn't waived the same way.
            )
        ),
        DistroManifest(
            id = "debian",
            displayName = "Debian",
            version = "12 (Bookworm)",
            avatarLetter = "D",
            avatarColorHex = "#A80030",
            approxDownloadSizeLabel = "~95 MB",
            source = DistroSource.Resolved(
                // Acceptance finding (2026-11-26, fresh-install run): the
                // previous Direct URL
                // (github.com/debuerreotype/docker-debian-artifacts/raw/
                // dist-arm64v8/bookworm/rootfs.tar.xz) now answers HTTP 404,
                // so every "Download a Distribution → Debian" attempt failed
                // during preparation on the acceptance device. The image
                // server used for Fedora already publishes Debian with a
                // per-build SHA256SUMS, so Debian now resolves through the
                // same verified path instead of a stale static URL.
                resolverId = DistroResolverIds.LINUX_CONTAINERS,
                distro = "debian",
                release = "bookworm",
                arch = "arm64"
            )
        ),
        DistroManifest(
            id = "fedora",
            displayName = "Fedora",
            version = "42",
            avatarLetter = "F",
            avatarColorHex = "#3C6EB4",
            approxDownloadSizeLabel = "~180 MB",
            // 2026-09-01: locked — see the alpine entry.
            // 2026-09-04 (user request): UNLOCKED — see the alpine entry.
            enabled = true,
            // Fedora doesn't publish its own minimal-rootfs tarball the way
            // Alpine/Ubuntu do; linuxcontainers.org's LXC image server
            // mirrors one per release, but under a build-timestamped
            // directory that changes on every rebuild — hence Resolved
            // rather than Direct. See LinuxContainersResolver.
            source = DistroSource.Resolved(
                resolverId = DistroResolverIds.LINUX_CONTAINERS,
                distro = "fedora",
                release = "42",
                arch = "arm64"
            )
        )
    )

    /**
     * Phase 06 (OPT-SEC-006): the no-checksum policy for [DistroCatalog.all].
     *
     * Every [DistroSource.Direct] entry must carry a usable SHA-256 sidecar
     * (`checksumUrl != null` **and** `checksumFormat != NONE`). Resolved
     * sources are exempt by construction — their resolver
     * ([com.fem.linexa.service.distro.LinuxContainersResolver]) always
     * returns a `SHA256SUMS` sidecar, so the guarantee lives one hop away.
     *
     * An entry with neither is only acceptable when its id is in
     * [CHECKSUM_WAIVED]: a deliberate, documented gap, not a silent default.
     * Enforcement is at validation time — [checksumPolicyViolations] is
     * pinned by `DistroCatalogChecksumPolicyTest`, so adding a checksumless
     * entry without a waiver fails the build instead of shipping an
     * unverified download path.
     */
    private val CHECKSUM_WAIVED = setOf("archlinux")

    /**
     * Returns one human-readable violation per catalog entry that downloads
     * over a [DistroSource.Direct] URL without a SHA-256 sidecar and without
     * a waiver — an empty list means the catalog satisfies the policy.
     * [catalog] defaults to [all]; tests pass synthetic lists to exercise
     * the validator itself.
     */
    fun checksumPolicyViolations(catalog: List<DistroManifest> = all): List<String> =
        catalog.mapNotNull { entry ->
            val direct = entry.source as? DistroSource.Direct ?: return@mapNotNull null
            val hasUsableChecksum = direct.checksumUrl != null &&
                direct.checksumFormat != ChecksumFormat.NONE
            if (hasUsableChecksum || entry.id in CHECKSUM_WAIVED) {
                null
            } else {
                "${entry.id}: Direct source without a SHA-256 sidecar and no waiver " +
                    "(set a checksumUrl/checksumFormat, or document the gap in CHECKSUM_WAIVED)"
            }
        }

    fun findById(id: String): DistroManifest? = all.firstOrNull { it.id == id }

    /**
     * The subset the UI may offer for install. Since the 2026-09-04 unlock
     * (user request) this is the whole catalog — every entry is enabled —
     * but the filter stays as the single gate the UI and the download
     * service go through, so re-locking an entry remains a one-flag change.
     */
    val installable: List<DistroManifest> = all.filter { it.enabled }
}

/** Result of [DistroSourceResolver.resolve]: a [DistroSource.Resolved] turned into something as concrete as a [DistroSource.Direct]. */
data class ResolvedDownload(
    val downloadUrl: String,
    val checksumUrl: String?,
    val checksumFormat: ChecksumFormat
)

/**
 * Looks up the real, current download URL for a [DistroSource.Resolved].
 * One implementation per distinct "kind of index" out there — see
 * [com.fem.linexa.service.distro.LinuxContainersResolver] for
 * the one this catalog actually uses (Fedora). Registered into a
 * [com.fem.linexa.service.distro.DistroSourceResolverRegistry]
 * by [id], so adding a new one is a new class + one Hilt multibinding line,
 * not a change to anything that already exists.
 */
interface DistroSourceResolver {
    val id: String
    suspend fun resolve(source: DistroSource.Resolved): ResolvedDownload
}

/**
 * Resolver ids live here (domain layer), not on the service-layer resolver
 * classes themselves, so [DistroCatalog] never needs to import
 * `com.fem.linexa.service.distro.*` just to reference which
 * resolver a [DistroSource.Resolved] entry wants — domain stays the thing
 * service depends on, never the other way around.
 */
object DistroResolverIds {
    const val LINUX_CONTAINERS = "linuxcontainers"
}
