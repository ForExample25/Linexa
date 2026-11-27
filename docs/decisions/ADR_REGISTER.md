# Architectural Decision Register

This register records the architectural decisions that shape the current
codebase. Each entry states the decision, why it was made, and what it costs.
It is a reference for maintainers, not a plan: implementation detail lives in
[`architecture/`](../architecture/) and [`features/`](../features/).

---

## 1. Runtime and platform

### ADR-01 — PRoot is the only container backend

**Decision.** Linux userlands run under the vendored PRoot binary through the
single `ContainerRuntime` implementation. There is no chroot, Droidspaces, or
other backend in the tree.

**Rationale.** PRoot performs path translation in userspace and needs no root,
so a stock Android device can install and run a distribution. Root-dependent
backends would introduce a different privilege model, a second lifecycle, and a
separate security and test matrix.

**Consequences.** Workspace isolation is path translation, not kernel namespace
or cgroup isolation: guest processes run as the app's Android UID. Root inside a
workspace is emulated (`-0`), never Android root. Only `arm64-v8a` and
`armeabi-v7a` are supported because no x86_64 PRoot is bundled (see ADR-17).

### ADR-02 — Vendored Termux terminal modules

**Decision.** The terminal stack is the vendored Apache-2.0
`terminal-emulator` and `terminal-view` modules from termux/termux-app at commit
`5b657c6adf4304e5198951ce815fe0205dcac29c`, including their JNI PTY code.

**Rationale.** A real PTY with full ANSI/VT emulation is central to the product.
The Termux modules provide that behavior under a permissive license, keep the
emulator and view testable in-tree, and avoid a runtime JitPack dependency.

**Consequences.** The modules are maintained in-tree and their Java tests run
through the module's own test task. Upgrading the terminal requires
re-vendoring and re-running the terminal suite.

### ADR-03 — Hilt with KSP

**Decision.** Dependency injection uses Hilt, with KSP for annotation
processing.

**Rationale.** Constructor injection keeps services, view models, and runtime
components testable without service locators. KSP avoids kapt's build cost and
its stability problems on the pinned Kotlin version, while Hilt test support
allows substituting fakes.

**Consequences.** The KSP version tracks Kotlin, and a DI graph change requires a
Hilt compilation pass. Generated code is not checked in.

---

## 2. Data and navigation

### ADR-04 — Room stores workspace metadata as one aggregate row

**Decision.** Room persists one `workspaces` row per workspace.
`WorkspaceMetadata` is both the Room entity and the domain model. The schema is
exported under `app/schemas/`.

**Rationale.** The workspace list and detail screens need typed, observable
state, and a workspace is naturally one metadata aggregate. A single row avoids
join-heavy tables for settings that are always read together. Exporting the
schema makes migrations reviewable and testable.

**Consequences.** Structured settings are stored through type converters and the
`attributes` map. A column change is a schema change and follows ADR-06. The
current database version is 1, so no migration exists yet.

### ADR-05 — Single-activity Navigation Compose shell

**Decision.** One `ComponentActivity` hosts a Navigation Compose graph with
three permanent top-level destinations — Workspaces, Activity, and Settings —
plus nested workspace and settings routes. Tablets at 840 dp width or wider use
a persistent sidebar instead of the bottom bar. The terminal is an overlay
managed by `TerminalDeepLinkBus`, not a navigation destination.

**Rationale.** A real back stack matches Android navigation expectations and
lets nested screens return to their parent. `saveState`/`restoreState`
preserves each tab's scroll position and nested destination. A terminal overlay
stays reachable from any screen without registering a route for every workspace
session.

**Consequences.** Back behavior comes from the navigation stack; there are no
"jump home" shortcuts. Terminal visibility is controlled outside the NavHost.

### ADR-06 — Schema changes require explicit migrations

**Decision.** Every `@Database(version = N)` bump ships a `Migration(N-1, N)`
registered with the database builder, plus the exported `N.json`. Destructive
fallback is never allowed. A schema file that has shipped is never edited or
regenerated.

**Rationale.** A user's database outlives any single build. A missing migration
crashes at open; a destructive fallback silently deletes workspaces. An
exported schema makes both failures visible before release.

**Consequences.** A schema change needs a version bump, a migration, and a
migration test. Adding a column for no functional gain is avoided because it
still costs a migration. The policy is documented in
[`ROOM_MIGRATIONS.md`](../ROOM_MIGRATIONS.md).

---

## 3. Desktop and input

### ADR-07 — Embedded RFB client, WebView mode, and external X11 providers

**Decision.** The Desktop screen has two run modes:

- **VNC** — an in-repo RFB 3.8 client renders a guest X server's framebuffer.
  VNC mode has two backends: X11 (Termux:X11 when both the Android app and the
  guest helper are present, otherwise the guest's own Xvnc) and VNC (direct
  Xvnc).
- **WebView** — an in-app WebView renders a user-configured guest HTTP service.

No X server or VNC client is forked into this repository.

**Rationale.** The RFB client and platform WebView cover framebuffer and
web-based desktops without bundling a large native X server or a remote-desktop
SDK. Using the user's Termux:X11 app when present keeps the preferred X11 path
optional, and Xvnc remains a compatibility path the guest already provides.

**Consequences.** Termux:X11 is an external dependency, not a bundled feature.
VNC geometry is fixed when Xvnc starts; a guest-side resize is followed only
when the server announces it. The client negotiates no cursor pseudo-encoding,
so cursor latency follows frame latency. ZRLE is implemented but offered only
when the workspace opts in.

### ADR-08 — The phantom-process limit is reported, never changed

**Decision.** Settings → Access reads the platform's
`settings_enable_monitor_phantom_procs` value where the setting is readable
(API 34+) and shows monitoring on, off, or unknown. On affected API levels it
offers copy-only `adb` guidance. The app never executes that command or modifies
the setting.

**Rationale.** Android 12+ can kill an app that holds too many child processes.
The monitor is platform-global state that requires `adb` or root to change.
Silently altering it from the app is neither possible with ordinary permissions
nor appropriate without the user's decision. Showing the state and the exact
command keeps the user in control.

**Consequences.** Linexa does not prevent the limit. A desktop session with
Xvnc, sshd, audio, notification and clipboard bridges, a GPU supervisor, and
multiple terminals can approach it. The status is unknown on API 31–33 because
the global setting is not readable there.

---

## 4. Runtime behavior

### ADR-09 — Workspace process attribution uses the tree plus the rootfs path

**Decision.** A workspace's processes are the union of the `ppid` descendant
tree rooted at its known PIDs and every process whose `/proc/<pid>/cmdline`
contains the workspace's absolute rootfs path. PRoot supervisors whose argv
names the rootfs are marked `isRoot` and protected from termination.

**Rationale.** `CONFIG_PROC_CHILDREN` is not available on target kernels, so the
parent map is built from `/proc/*/stat`. A parent walk alone misses a daemon
started outside the tree, a reparented orphan, or a `setsid`-detached process.
PRoot writes the rootfs path into the host argv of every traced exec, which
recovers those processes without merging another workspace.

**Consequences.** A daemon that rewrites its argv may escape live attribution
until the startup sweep. Termination re-reads the PID and `starttime` before
signalling so a recycled PID is not killed.

### ADR-10 — Wake locks are opt-in and time-bounded

**Decision.** A workspace may take a `PARTIAL_WAKE_LOCK` only on explicit user
action. A single hold has a hard 60-minute ceiling, enforced by both the
platform timeout and an internal timer. The lock is never acquired
automatically.

**Rationale.** A long guest operation (`apt full-upgrade`, a compile, a large
install) should survive the screen turning off. An unbounded lock would drain
the battery for as long as a session stays open, which can be hours. A hard
ceiling forces a deliberate re-arm instead of hiding that cost.

**Consequences.** Work that runs longer than 60 minutes can be suspended when
the device sleeps. The UI shows the remaining hold time.

### ADR-11 — Typed failures, no fabricated success

**Decision.** Operations that can fail return typed results — `CommandResult`,
`InstallResult`, `FileOpResult`, `WorkspaceStatus.Broken(reason)`. Guest stderr
is preserved when it is actionable. Fail-open applies to presentation state
(an unavailable metric renders as unknown), never to data integrity (a failed
write is a failure).

**Rationale.** A broken runtime must not look like an empty directory, a blank
file, or a successful save. Honest failure states are actionable and prevent
silent data loss.

**Consequences.** Callers handle explicit failure branches. Success is reported
only after the underlying process exits and its exit code is checked. Unknown
values are distinct from zero throughout the UI.

### ADR-12 — UI state uses `collectAsState()`

**Decision.** Compose screens collect view-model `StateFlow`s with
`collectAsState()`. `collectAsStateWithLifecycle()` is not used in production
code.

**Rationale.** With the pinned lifecycle-runtime-compose, Compose UI, and
Navigation versions, `collectAsStateWithLifecycle()` crashes through
`LocalLifecycleOwner` delegation. Plain collection is the app-wide,
reproducible pattern until those dependencies move together.

**Consequences.** A flow stays hot while its screen is backgrounded unless the
producer is gated. Pollers and samplers therefore use explicit
`start`/`stop` or `DisposableEffect` gating. Migrating to lifecycle-aware
collection requires a coordinated dependency bump and a re-test of the affected
screens.

### ADR-13 — Root shell and `sudo` use a separate root-emulated process

**Decision.** A guest `sudo` is a helper script that writes a request into a
workspace-local mailbox under `/tmp`. The app's `RootExecBridge` claims the
request and executes it in a separate PRoot `-0` one-shot process, streams
output, and writes the exit status. Apps → Root shell is an interactive `-0`
PTY rather than a mailbox client.

**Rationale.** PRoot cannot honor setuid from an emulated non-root identity:
`setuid(0)` returns `EPERM`. A setuid binary would either do nothing or lie
about the result, and running the command as the caller would break package
management while appearing to succeed. A separate root-emulated process is the
genuine elevation the runtime already supports.

**Consequences.** The bridge has no stdin or TTY forwarding, so interactive
programs and prompts need Root shell. Commands are capped at 64 KiB, a request
must be claimed within 15 s, and a stopped heartbeat fails after 30 s with exit
code 125. Any guest process that can execute the helper has workspace-level
root emulation; no host privilege is added.

### ADR-14 — PRoot is pinned upstream plus four patches

**Decision.** The vendored PRoot is `termux/proot` tag `v5.1.107.89` with four
patches applied in order:

1. `readlinkat` empty-pathname fix,
2. link2symlink relative-target resolution,
3. ashmem/memfd `<string.h>` include,
4. guest environment filter.

**Rationale.** The tag is the base recorded in the binary provenance. Each patch
fixes a reproduced failure or closes host-environment leakage into the guest.
The patch set is deliberately small and ordered so the corresponding source can
be reconstructed.

**Consequences.** Rebuilding requires the documented toolchain, patch order, and
`ARG_MAX=131072` define. Patch files and the rebuild recipe are part of the
provenance record in `third_party/proot/BUILD-NOTES.md`.

---

## 5. Security and storage

### ADR-15 — VNC passwords are encrypted at rest

**Decision.** A workspace's VNC password is stored in its `attributes` map as an
AES-256-GCM envelope under an Android Keystore key. Legacy plaintext values are
migrated at app start. If the Keystore cannot decrypt a value, the row is marked
unreadable and the desktop refuses to start unauthenticated.

**Rationale.** A password stored in a Room column or preferences would be
readable from a backup or a database dump. Keystore-backed encryption keeps the
key out of app storage and fails closed rather than silently dropping
authentication.

**Consequences.** A restore to a new device leaves the ciphertext unreadable
until the user sets a new password. Encryption failure never falls back to
plaintext. While a desktop runs, the password is also present in memory and in
the guest's `~/.vnc/linexa-vnc-passwd`; a RootFS export includes that file.

---

## 6. Packaging and presentation

### ADR-16 — `HavenDim` and `Spacing` are the active token scales

**Decision.** Haven components take radii, rhythm, and control sizes from
`HavenDim`; layout spacing uses the 4-dp `Dimens.Spacing` scale. New UI uses the
Haven component layer. `Dimens.Corners` and `Dimens.Elevation` remain declared
but have no call sites.

**Rationale.** Two shape vocabularies would drift. Haven components already
consume `HavenDim`, and HAVEN expresses depth with tone steps and hairlines, so
a separate elevation scale is unnecessary. The 4-dp spacing grid keeps dense
technical layouts consistent.

**Consequences.** `Dimens.Corners`/`Elevation` are unused and must not become a
third scale. Inline `.dp` values still exist in older screens.

### ADR-17 — Native programs are vendored jniLibs with remapped names

**Decision.** PRoot, BusyBox, GNU tar, Zstandard, tmux, the loader, and their
dependencies are vendored in `app/src/main/jniLibs/` for `arm64-v8a` and
`armeabi-v7a`. They use `lib__*__.so` file names, are packaged with
`useLegacyPackaging = true`, and are remapped to friendly names under
`{filesDir}/bootstrap/{bin,lib}` at runtime.

**Rationale.** AGP auto-packages only `lib*.so` entries. Static executables
therefore need the naming shim, and `nativeLibraryDir` is the only location the
app can execute from under its SELinux label. App-private bootstrap paths keep
the runtime writable and independent of external storage.

**Consequences.** No x86_64 PRoot exists, so x86_64 emulators cannot launch a
workspace. The APK carries every bundled binary. Adding a binary requires a
manifest entry, attribution, and a bootstrap mapping.

### ADR-18 — Layout is always LTR; only text is bidi

**Decision.** The application chrome is laid out left-to-right in every
language. `android:supportsRtl` is `false`, `AppLocale.localizedContext` applies
the selected locale and then forces `Configuration.setLayoutDirection(Locale.ROOT)`,
and the root composition provides `LocalLayoutDirection = LayoutDirection.Ltr`
unconditionally. Persian text direction is not overridden: prose styles leave
`TextDirection.Unspecified`, so Android's first-strong bidi heuristic renders
Persian runs RTL inside the LTR layout.

**Rationale.** The interface is designed as one LTR structure; mirroring rows,
toolbars, navigation, padding, and icons for Persian creates layout defects and
visual drift without improving Persian text. Locale should select resources and
text, not rearrange the workspace UI. Text bidi is a separate concern and must
remain correct for readability.

**Consequences.** No screen may derive `LocalLayoutDirection` from the selected
language or provide its own layout direction. Persian prose still aligns and
orders itself by its own bidi base direction inside LTR containers. Technical
styles continue to pin `TextDirection.Ltr`. Changing this policy requires
updating the manifest, `AppLocale.localizedContext`, the root provider, and the
source guards together; see
[`../design/ACCESSIBILITY_AND_LOCALIZATION.md`](../design/ACCESSIBILITY_AND_LOCALIZATION.md)
§4.2.1.

---

## 7. Audit remediation decisions (2026-11)

These decisions answer findings in `LINEXA_COMPLETE_ENGINEERING_AUDIT/`. Each
entry records what was chosen, what was rejected, and what remains open.

### ADR-19 — Read-only host fast path for the file manager

**Decision.** `WorkspaceFileManagerImpl` answers `list`, `read`, `stat`,
`exists` and regular-file `size` directly from the workspace rootfs through
`HostFileAccess`; every mutation and every path under a PRoot bind mount
(`/dev`, `/proc`, `/sys`, `/run`, `/mnt/shared`, `linexa.launch.bindMounts`)
still goes through the guest. Guest-absolute symlinks are re-anchored at the
rootfs, relative links and `..` are resolved component-by-component with
`NOFOLLOW`, and expansion is bounded at 40 hops.

**Rationale.** F-003 measured hundreds of milliseconds per PRoot launch. The
rootfs is app-private, so NIO is both faster and sufficient. A persistent guest
file agent (R-17) was rejected: it adds a protocol, a lifecycle and a second
security surface for a read path that does not need guest semantics.

**Consequences.** Host and guest can disagree under a bind mount, so those
prefixes are explicitly excluded and fall back to exec. The resolver is a
TOCTOU-sensitive read path; it never writes, and mutations remain on the guest.
Tests pin absolute/relative symlink escapes, cycles and `..` clamping.

### ADR-20 — CI overrides the constrained Gradle profile

**Decision.** `gradle.properties` keeps the 4 GB Termux profile (one worker, no
daemon, 1.5 GB heap). CI and the release workflow override it with
`--parallel --max-workers=4`, a daemon and `-Xmx3g`.

**Rationale.** F-016: the local constraint is real, but CI runners are larger.
Changing the committed profile would risk OOM on the maintainer's device;
overriding in the workflow keeps both environments honest.

**Consequences.** A CI failure caused by memory now points at the workflow
override, not at the local profile. Clean-build timing should be recorded when
the runner changes.

### ADR-21 — Keep MANAGE_EXTERNAL_STORAGE, document the Play declaration

**Decision.** The permission stays, gated behind the explicit "All files
access" opt-in. The app continues to expose SAF and the app-specific shared
folder for users who do not opt in.

**Rationale.** F-018: the direct-storage browser is a shipped feature that SAF
does not replace for arbitrary paths. Removing the permission would regress it.
The risk is a Play review requirement, not a runtime defect.

**Consequences.** Publishing to Play requires the All files access declaration
and review. If the feature is ever removed, the permission, the settings row,
the file-manager host mode and this ADR must be removed together.

### ADR-22 — Universal APK stays the default; ABI splits are opt-in

**Decision.** The release artifact remains `app-release.apk` (universal) and the
AAB remains the Play artifact. `-Plinexa.abiSplits=true` enables per-ABI APKs
with a universal fallback for GitHub distribution.

**Rationale.** F-022: many docs and release steps name `app-release.apk`; the
AAB already gives Play per-ABI delivery. A property keeps the smaller artifacts
available without breaking the documented pipeline.

**Consequences.** The default GitHub APK still carries both ABIs. Maintainers
who enable splits must upload the per-ABI files and keep the universal one for
older devices.

### ADR-23 — No certificate pinning; rely on signatures and digests

**Decision.** Update and distro downloads keep the platform trust store. APK
updates are verified by Android's package signature check; archives use
published SHA-256 where available and the F-017 unverified-import consent
otherwise.

**Rationale.** F-026: pinning GitHub or rotating distro CDNs without a backup
pin creates a self-inflicted outage, and the update path already has a stronger
end-to-end check (the signing certificate). A pin would only defend metadata
confidentiality, which is not sensitive here.

**Consequences.** A compromised CA can observe or block update metadata, but
cannot install an APK signed by a different key. Revisit only if a stable,
backup-pinned endpoint becomes a hard requirement.

### ADR-24 — Keep the existing release key identity

**Decision.** The `CN=Linexa, OU=Development` key remains the update key. Play
distribution, if it happens, enrolls Play App Signing and documents this key as
the upload key; it is never regenerated.

**Rationale.** F-027: regenerating would break updates for every installed
beta. The organizational unit is cosmetic; continuity is not.

**Consequences.** Play Console must show the expected upload certificate. This
is a documentation/process task, not a code change.

### ADR-25 — Root bridge session token and tight control-directory modes

**Decision.** Every bridge start generates a UUID token, writes it in the
marker, and requires each request to echo it. The control directory is 0700 and
the marker/heartbeat are 0600.

**Rationale.** F-035/R-39: the guest runs as the app uid, so a token cannot stop
a hostile same-uid process that can read the marker. It does reject requests
from a previous bridge session, hand-crafted directories and stale mailboxes,
which is the replay/stranger case the mailbox actually exposes. The real
boundary remains: the app validates a real child of the rootfs `/tmp` and the
app — not the guest — executes the command.

**Consequences.** The guest helper must read the marker token; an old helper
without that step is rejected loudly. This is documented in
`docs/features/ROOT_SHELL_AND_SUDO.md`. A true authorization boundary would
require a different uid or a kernel primitive PRoot does not provide.

### ADR-26 — Vendored terminal modules stay Java

**Decision.** `terminal-emulator` and `terminal-view` remain the pinned
upstream Java sources. Security fixes are small patches against the pinned
commit; no Kotlin conversion.

**Rationale.** F-041: the JNI bindings depend on exact class names and the
upstream tests are the compatibility contract. A rewrite adds risk with no user
benefit.

**Consequences.** No Kotlin null-safety in that subsystem. Provenance, the
upstream commit and the library hash pin are the maintenance tools.

### ADR-27 — Seeded fuzz smoke tests in CI, Jazzer deferred

**Decision.** Archive entry classification, the symlink guard and the ZRLE
decoder run deterministic seeded fuzz smoke tests in the JVM suite. A
coverage-guided Jazzer campaign is not wired into CI.

**Rationale.** F-043/R-36: the property that matters for untrusted input is
"no unexpected throw, hang or out-of-bounds write", and a bounded seeded corpus
pins that in every existing test run. Jazzer needs a separate toolchain and a
time budget that the current single-runner CI does not have.

**Consequences.** The corpus is a regression net, not a discovery engine. A
future Jazzer job can reuse the same entry points and seeds.

### ADR-28 — Structural refactors and toolchain upgrades are staged, not rushed

**Decision.** F-006 (baseline profile/macrobenchmark), F-011/F-012 (god-class
and module splits), F-020 (single-pass extraction), F-024 (coverage gate),
F-032/R-09 (AGP/Gradle upgrade) and F-036 (PID parsing) remain open with the
roadmap entries unchanged.

**Rationale.** Each is a multi-day structural change whose verification needs
the very benchmarks and device matrix this audit found missing. Doing them in
the same pass as the P1 fixes would make a regression impossible to attribute
and would risk the installable-app constraint.

**Consequences.** The remediation verdict is "complete with limitations", not
"complete". The open items and their prerequisites are listed in
`audit/remediation/BLOCKED_ITEMS.md`; none is silently dropped.

---

## 8. Alternatives not adopted

These alternatives were considered and are not part of the current design. They
are recorded so they are not revisited without new information.

| Alternative | Reason not adopted |
|---|---|
| General app-store integration (Flathub and similar) | Each distribution's catalog and packaging model differs; the guest's native package manager already covers software installation. |
| Bundled or forked X server for per-app Android windows | Adds a large native component and a GPL fork; the external Termux:X11 provider plus the embedded RFB client cover the supported desktop paths. |
| Turnip/Zink or direct DRM GPU stack | The validation target is ARM Mali, which Turnip does not support, and the DRM render node is not usable by an app under SELinux. The code retains an optional VirGL vtest path, but this build does not bundle its host binaries and reports a degraded state instead. |
| chroot or Droidspaces container backends | They require root, add a different security surface, and double the runtime and test matrix; PRoot is the supported backend. |
| USB/Bluetooth HID and Cast support | Out of scope for the current product, and each would add permissions plus a hardware-specific test matrix. |

---

## 9. Related documentation

- [`architecture/SYSTEM_ARCHITECTURE.md`](../architecture/SYSTEM_ARCHITECTURE.md) — subsystem layout
- [`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md) — PRoot, PTY, sessions
- [`architecture/DATA_AND_PERSISTENCE.md`](../architecture/DATA_AND_PERSISTENCE.md) — Room and RootFS
- [`engineering/CODE_CONVENTIONS.md`](../engineering/CODE_CONVENTIONS.md) — layering and conventions
- [`ROOM_MIGRATIONS.md`](../ROOM_MIGRATIONS.md) — migration procedure
- [`features/ROOT_SHELL_AND_SUDO.md`](../features/ROOT_SHELL_AND_SUDO.md) — root shell protocol
