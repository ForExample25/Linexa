package com.fem.linexa.service.rootfs.profile

import com.fem.linexa.service.installer.RootFsProbe

/**
 * §1.146 — a declarative description of how one distribution's rootfs
 * archives identify themselves and what installing one requires.
 *
 * ## Why profiles instead of `when (distro)`
 *
 * Before §1.146 the knowledge "how do I recognize Alpine" lived as literals
 * inside the scan loop, "what package manager does Alpine use" lived in
 * `PackageManagerDetector`, and "does Alpine need a /tmp" lived in a comment
 * in `GuestRootfsInitializer`. Adding a distribution meant touching every one
 * of those places and hoping none were missed — which is exactly what happened
 * to Arch Linux ARM, whose rootfs carries no glibc loader path and so had no
 * way to report its architecture at all.
 *
 * A profile collects those facts in one value, per distribution, and the
 * pipeline reads them instead of branching on identity. The rule the rest of
 * this package follows: **the installer asks the profile a question; it never
 * asks which distro it is talking to.**
 *
 * ## What a profile may and may not do
 *
 * A profile is *data*. Every field below is either a path to look at, a string
 * to compare, or an enum the installer already knows how to act on. There is
 * deliberately no field carrying a command, a script, or a shell fragment, and
 * no field naming a host-side path — because profiles can arrive from inside
 * an untrusted archive ([RootFsProfileSource.EMBEDDED]) and a profile must
 * never become a code-execution vector. See [RootFsProfileParser] for the
 * checks that keep that true, and [InstallStep] for the closed set of
 * behaviours a profile can request.
 *
 * @param id stable identifier, matched against `os-release`'s `ID` and used as
 *   the profile's asset file name. Lower-case, `[a-z0-9._-]`.
 * @param name human-readable distribution name for the confirmation screen.
 * @param aliases additional `os-release` `ID` values that resolve to this
 *   profile (Arch ARM reports `archarm`, not `arch`).
 * @param probeId which [RootFsProbe.DistroId] this profile maps onto, kept so
 *   the existing UI/iconography enum keeps working unchanged.
 * @param detection how an archive of this distribution is recognized.
 * @param supportedArchitectures architectures this distribution publishes a
 *   rootfs for. Advisory: used to sanity-check a detected architecture, never
 *   to reject an archive (a user may have built their own).
 * @param layout structural expectations of the extracted tree.
 * @param install the ordered, closed-set setup steps this distribution needs.
 * @param environment guest-side defaults (login shell, package manager).
 * @param source where this profile came from, for precedence and diagnostics.
 */
data class RootFsProfile(
    val id: String,
    val name: String,
    val aliases: Set<String> = emptySet(),
    val probeId: RootFsProbe.DistroId = RootFsProbe.DistroId.OTHER,
    val detection: DetectionRules = DetectionRules(),
    val supportedArchitectures: Set<String> = emptySet(),
    val layout: LayoutRules = LayoutRules(),
    val install: List<InstallStep> = emptyList(),
    val environment: EnvironmentDefaults = EnvironmentDefaults(),
    val source: RootFsProfileSource = RootFsProfileSource.BUILT_IN
) {
    /** Every `os-release` ID that resolves to this profile. */
    val allIds: Set<String> get() = aliases + id
}

/** §1.146 — where a resolved profile came from. Drives precedence. */
enum class RootFsProfileSource {
    /** Declared by the archive itself (`.linexa.yaml`). Highest precedence. */
    EMBEDDED,

    /** Shipped in the app's assets. Used when the archive declares nothing. */
    BUILT_IN,

    /**
     * Synthesized because nothing matched — the generic safe path. Detection
     * failed, so the user's manual selection stands, but installation still
     * runs a real (conservative) plan rather than a Debian-shaped one.
     */
    GENERIC
}

/**
 * §1.146 — how an archive of this distribution is recognized.
 *
 * All paths are **guest-relative, normalized** ([com.fem.linexa.service.rootfs.ArchiveEntryPath]),
 * so a `./`-packed archive matches the same rules as a bare-packed one — the
 * bug that made Alpine and Arch undetectable in the first place.
 *
 * @param osReleaseIds `os-release` `ID` values that identify this
 *   distribution. Checked first because `os-release` is the only
 *   cross-distribution standard (os-release(5)) and carries a version too.
 * @param markerFiles files whose mere presence identifies this distribution,
 *   for images that predate or omit `os-release` (`etc/alpine-release`,
 *   `etc/arch-release`). Read as text when small, so they can supply a version.
 * @param versionFrom which marker file, if any, carries a bare version string.
 * @param architectureHints substrings of entry names that reveal the target
 *   CPU, *per distribution*. This is the field that fixes Arch: it ships no
 *   `ld-linux-aarch64.so.1` and no `aarch64-linux-gnu/` directory, so the
 *   shared glibc/musl heuristics find nothing and it needs its own hints.
 * @param elfProbePaths guest paths of ELF binaries whose machine header is
 *   read to determine architecture when name-based hints find nothing. This is
 *   the authoritative signal — an `e_machine` of `0xB7` *is* aarch64 — and is
 *   what lets Arch be detected at all.
 */
data class DetectionRules(
    val osReleaseIds: Set<String> = emptySet(),
    val markerFiles: Set<String> = emptySet(),
    val versionFrom: String? = null,
    val architectureHints: Map<String, String> = emptyMap(),
    val elfProbePaths: List<String> = emptyList()
)

/**
 * §1.146 — structural expectations of the *extracted* tree.
 *
 * Exists because the pre-§1.146 post-extraction check hardcoded
 * `listOf("bin", "etc", "lib", "usr", "var")` for every distribution. That
 * list is a Debian assumption dressed as a universal one, and a distribution
 * that legitimately lacks one of those directories would be rejected with
 * "Extracted rootfs is incomplete" despite being perfectly installable.
 *
 * @param requiredDirectories directories that must exist after extraction.
 * @param shellCandidates guest paths any one of which satisfies "this rootfs
 *   has a shell". Ordered: the first that exists is the preferred login shell.
 *   Alpine ships no bash at all, so a bash-only list would reject it.
 * @param archiveKind the container shape of the *downloaded file*, which is
 *   not always a plain rootfs tarball — see [ArchiveKind].
 */
data class LayoutRules(
    val requiredDirectories: Set<String> = setOf("etc", "usr"),
    val shellCandidates: List<String> = listOf("bin/sh", "usr/bin/sh", "bin/bash", "usr/bin/bash"),
    val archiveKind: ArchiveKind = ArchiveKind.ROOTFS_TAR
)

/**
 * §1.146 — the container shape of the file the user supplied.
 *
 * Distinct from [com.fem.linexa.service.rootfs.RootFsArchiveFormat], which is
 * the *compression* (gz/xz/zst). This is what the decompressed tar *contains*.
 */
enum class ArchiveKind {
    /** The tar is the rootfs. Debian, Ubuntu, Alpine, Arch. */
    ROOTFS_TAR,

    /**
     * The tar is an OCI image layout (`oci-layout` + `index.json` +
     * `blobs/sha256/…`) whose rootfs is a **nested** tar inside a layer blob.
     * Fedora publishes its container base image this way, and it is what a
     * user who downloads "the Fedora rootfs" actually gets.
     */
    OCI_IMAGE_LAYOUT
}

/**
 * §1.146 — the closed set of setup actions a profile may request.
 *
 * Deliberately an **enum, not a command string**. An embedded profile arrives
 * from an untrusted archive; if this were `List<String>` of shell commands,
 * importing a hostile rootfs would be arbitrary code execution as the app.
 * Every constant here maps to code that already exists in the installer and
 * was already audited — a profile chooses *whether* and *in what order*, never
 * *what*.
 *
 * Adding a distribution that needs something genuinely new means adding a
 * constant here and implementing it, in a reviewed change — which is the
 * intended cost, not an oversight.
 */
enum class InstallStep {
    /**
     * Create `/tmp` with mode 1777 if absent
     * ([com.fem.linexa.service.installer.GuestRootfsInitializer.ensureGuestTmp]).
     * Debian and Ubuntu tarballs ship no `/tmp` (it is a boot-time tmpfs);
     * Alpine ships one, so for Alpine this is an idempotent no-op that costs a
     * stat. Every profile requests it — the step is cheap and the failure it
     * prevents (`apt` / `apk` unable to mkstemp) is not.
     */
    ENSURE_TMP,

    /**
     * Create `/dev/shm` with mode 1777 if absent
     * ([com.fem.linexa.service.installer.GuestRootfsInitializer.ensureGuestDevShm]).
     * F-5 (2026-08-25 audit): the guest's `/dev` is the Android HOST's `/dev`
     * (PRoot bind), which has no `/dev/shm` — so `shm_open`-dependent software
     * (Chromium/Electron, Qt WebEngine, some postgres workloads) degraded or
     * failed. PRoot cannot mount tmpfs, but glibc's `shm_open` works file-backed
     * under a plain 1777 directory: the runtime binds this rootfs-owned
     * directory over the guest `/dev/shm` path (the specific bind wins over the
     * broader `/dev` bind), giving every distro a working POSIX shm without a
     * privileged mount and without touching the host's `/dev`. Idempotent; a
     * distribution that ships its own `/dev/shm` pays only a stat.
     */
    ENSURE_DEV_SHM,

    /**
     * Create `/run` with mode 1777 if absent
     * ([com.fem.linexa.service.installer.GuestRootfsInitializer.ensureGuestRun]).
     * R-5 (2026-08-26 runtime parity audit): the guest's per-session runtime
     * surface. Debian/Ubuntu/Fedora userspace writes pid files, socket dirs,
     * XDG_RUNTIME_DIR, and utmp to `/run`; without a working `/run` anything
     * that expects `/run/user/$UID` or `/run/utmp` fails (dbus, sshd,
     * pipewire, systemd-runtime-dir consumers). Same shape as ENSURE_DEV_SHM.
     * Idempotent; a distribution that ships its own `/run` pays only a stat +
     * chmod.
     */
    ENSURE_RUN,

    /**
     * Write static snapshot files for `/proc/stat`, `/proc/uptime`,
     * `/proc/loadavg`, `/proc/version`, `/proc/vmstat`
     * ([com.fem.linexa.service.installer.GuestRootfsInitializer.ensureProcSnapshots]).
     * R-6 (2026-08-26 runtime parity audit): Android's `untrusted_app`
     * SELinux domain + `hidepid=invisible` on `/proc` means the guest reads
     * `EACCES` on these files and `btop`/`top`/`vmstat`/`uptime` refuse to
     * start. The runtime binds the snapshot files over the Android-restricted
     * `/proc` paths. Idempotent: re-runs write the same static content (no
     * live values are possible, since the host refuses to serve them too).
     */
    ENSURE_PROC_SNAPSHOTS,

    /**
     * Seed `/etc/resolv.conf` with public resolvers
     * ([com.fem.linexa.service.installer.RootfsFixupService]). Alpine ships
     * none; Debian's points at a systemd-resolved stub that is unreachable
     * under PRoot.
     */
    SEED_RESOLV_CONF,

    /**
     * Add the Android app's uid/gids to `/etc/passwd` + `/etc/group`
     * (proot-distro lineage; [com.fem.linexa.service.installer.RootfsFixupService]).
     */
    ANDROID_ACCOUNT_FIXUP,

    /**
     * Unwrap an OCI image layout to its rootfs layer before extraction. Only
     * meaningful with [ArchiveKind.OCI_IMAGE_LAYOUT].
     */
    /**
     * Install `/usr/local/bin/linexa-hostinfo` (0755)
     * ([com.fem.linexa.service.installer.GuestRootfsInitializer.ensureGuestHostInfo]).
     * 2026-08-27 host-bridge expansion, Phase 15: the guest-side viewer over
     * `/run/linexa/hostinfo.conf`. Idempotent byte-compare; permission set is
     * the contract (fixes the observed `-rw-------` helper class of bug).
     */
    ENSURE_HOSTINFO_BIN,

    /**
     * Unwrap an OCI image layout to its rootfs layer before extraction. Only
     * meaningful with [ArchiveKind.OCI_IMAGE_LAYOUT].
     */
    UNWRAP_OCI_LAYER
}

/**
 * §1.146 — guest-side defaults this distribution needs.
 *
 * @param loginShell absolute guest path of the preferred login shell, or null
 *   to let the runtime probe. Alpine's `/bin/sh` (busybox ash) is the case
 *   that matters: the runtime's probe order tries bash first and Alpine has
 *   none.
 * @param shellArgs arguments passed to the login shell. `--login` is the
 *   default everywhere; busybox ash accepts it.
 * @param packageManager one of
 *   [com.fem.linexa.domain.packagemanager.PackageManagerDetector]'s keys, or
 *   null to probe. Declaring it lets the Desktop/SSH provisioners pick the
 *   right install command without a guest round-trip.
 */
data class EnvironmentDefaults(
    val loginShell: String? = null,
    val shellArgs: List<String> = listOf("--login"),
    val packageManager: String? = null
)
