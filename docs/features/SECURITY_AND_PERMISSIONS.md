# Security and Permissions

**Status:** Current — describes the implemented security model, verified against source.

**Project:** Linexa Linux Workspace (`com.fem.linexa`)

**Source locations:** `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/fem/linexa/core/security/`, `app/src/main/java/com/fem/linexa/domain/workspace/WorkspaceMetadata.kt`, `app/src/main/java/com/fem/linexa/domain/workspace/WorkspaceCapabilities.kt`, `app/src/main/java/com/fem/linexa/domain/security/`, `app/src/main/java/com/fem/linexa/data/security/AndroidKeystoreSecretCipher.kt`, `app/src/main/java/com/fem/linexa/data/repository/WorkspaceSecretAttributes.kt`, `app/src/main/java/com/fem/linexa/service/workspace/VncPasswordMigrationManager.kt`, `app/src/main/java/com/fem/linexa/service/runtime/features/FeatureSettings.kt`, `app/src/main/res/xml/backup_rules.xml`, `app/src/main/res/xml/data_extraction_rules.xml`, `app/src/main/res/xml/network_security_config.xml`

---

## 1. Purpose

This document is the feature-level reference for Linexa's security model: security boundaries, enforcement levels, VNC password handling, backup rules, manifest permissions, and archive validation.

---

## 2. Scope

In scope:

- Security boundaries (Android host / Linexa app / PRoot environment / shared storage / network)
- Enforcement levels (ENFORCED / ADVISORY / UNAVAILABLE)
- VNC password handling (AES-GCM/Keystore at rest, legacy migration, backup exclusion, redaction)
- Manifest permissions
- Backup rules (`backup_rules.xml`, `data_extraction_rules.xml`)
- Network security config (cleartext loopback only)
- Archive validation (path traversal, zip-slip, archive bomb)
- PRoot isolation limits (path translation, not kernel namespaces/cgroups; same UID)

Out of scope:

- PRoot runtime internals: [`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md)
- Desktop/VNC behavior: [`features/DESKTOP_AND_VNC.md`](./DESKTOP_AND_VNC.md)

---

## 3. Current implementation status

| Area | Status | Evidence |
|---|---|---|
| Security boundaries documented | Implemented | source, `AndroidManifest.xml` |
| Process isolation = ADVISORY (PRoot path translation, same UID) | Implemented | `WorkspaceCapabilities.kt` |
| VNC password encrypted at rest (AES-GCM/AndroidKeyStore) | Implemented | `AndroidKeystoreSecretCipher.kt`, `WorkspaceSecretAttributes.kt` |
| Legacy plaintext VNC rows migrated at startup | Implemented | `VncPasswordMigrationManager.kt` (idempotent, fail-open with retry) |
| Backup rules exclude DB, workspaces, logs, bootstrap | Implemented | `backup_rules.xml`, `data_extraction_rules.xml` |
| VNC password redacted in forensic argv dumps | Implemented | `VNC_PASSWD_SECRET_REGEX` |
| Archive path-traversal/zip-slip/bomb validation | Implemented | `RealArchiveValidator`, `ArchiveEntryPath` |
| `allowBackup="true"` scoped | Implemented | `AndroidManifest.xml` |
| Cleartext limited to loopback | Implemented | `network_security_config.xml` |
| Permissions: INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE(+SPECIAL_USE,+DATA_SYNC), POST_NOTIFICATIONS, MANAGE_EXTERNAL_STORAGE (opt-in), REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, WAKE_LOCK | Implemented | `AndroidManifest.xml` |
| VNC password unreadable-state handling | Implemented (fail-closed) | `KEY_DESKTOP_VNC_PASSWORD_UNREADABLE`; desktop refuses to start with an unknown password |
| Enforcement labels UI on Shared Storage/Network panels | Not implemented | `WorkspaceCapabilities.kt` exists, no user-facing labels |

---

## 4. Technical overview

### 4.1 Security boundaries

```
Android Host (trusted computing base)
  ├── Android OS kernel
  ├── Android Permissions (INTERNET, FOREGROUND_SERVICE, WAKE_LOCK, POST_NOTIFICATIONS, MANAGE_EXTERNAL_STORAGE opt-in)
  ├── Android Keystore (hardware-backed) — wraps workspace secret attributes
  └── SELinux (apk_data_file label allows execve on nativeLibraryDir)

Linexa Application (app_data_file, single UID)
  ├── App process (decrypted secret in memory only)
  ├── Room DB (linexa.db — attributes incl. AES-GCM/Keystore-encrypted VNC password)
  ├── SharedPreferences (settings)
  ├── app-private logs
  └── bootstrap binaries (jniLibs)

PRoot Environment (guest, same UID as app)
  ├── PRoot binary (nativeLibraryDir, apk_data_file)
  ├── Guest rootfs (/tmp, /dev/shm, /run ensured 1777)
  └── Guest processes (shell, sshd, Xvnc, daemons)

Shared Storage (opt-in, user-selected SAF DocumentsProvider/DocumentTree URI)
Network (Android host network stack; no guest firewall by default)
```

- Process isolation (workspace ↔ workspace): **ADVISORY** — PRoot is path translation, not kernel namespaces/cgroups. Same UID. Do not claim kernel-level isolation.
- VNC password at rest: **AES-GCM ciphertext** under an `AndroidKeyStore` key, applied at the repository boundary (`WorkspaceSecretAttributes`). Legacy plaintext rows are migrated at startup (`VncPasswordMigrationManager`); the plaintext exists in memory and in the guest `~/.vnc/linexa-vnc-passwd` file (chmod 600) while the desktop runs. Password generated via `SecureRandom`; the user never chose it.
- Backup exposure: **ENFORCED (scoped)** — `allowBackup="true"` scoped by `backup_rules`/`data_extraction_rules` excluding DB, workspaces tree, logs, bootstrap.
- RootFS archive extraction: **ENFORCED** — path-traversal validation in `RealArchiveValidator`, `ArchiveEntryPath`.
- Shared storage access: **ADVISORY** — opt-in user-selected SAF tree; app cannot access without user grant.
- Network exposure: **ADVISORY** — guest networking uses Android host stack; no firewall rules inside guest by default.
- Phantom-process limit: **ADVISORY** — detection + guidance, not prevention.
- SELinux execve: **ENFORCED** — `apk_data_file` on nativeLibraryDir allows execve; filesDir blocked.
- Root-emulated execution: **ADVISORY (workspace trust)** — Root shell and the guest `sudo` bridge run a separate PRoot process with `-0`. That process still uses the app's real Android uid; it gains no host privilege and cannot bypass SELinux. Any process in the workspace can invoke the bridge. See [`features/ROOT_SHELL_AND_SUDO.md`](./ROOT_SHELL_AND_SUDO.md).

### 4.2 VNC password

- Field: `FeatureSettings.desktopVncPassword: String = ""` (default empty = `SecurityTypes None` — upgrades don't lock users out).
- Storage: `WorkspaceMetadata.attributes` map, key `feature.desktop.vnc.password`. The persisted value is an **AES-GCM envelope** (`iv:ciphertext`, `AndroidKeyStore` key, `AES/GCM/NoPadding`) written by `WorkspaceSecretAttributes`; the repository decrypts on read. A legacy plaintext value is rewritten by `VncPasswordMigrationManager` at app start, idempotently.
- Unreadable state: if the Keystore cannot decrypt (e.g. restored database on a new device), the row carries `feature.desktop.vnc.password.unreadable=true`; the desktop refuses to start rather than silently running with an empty password. The stored ciphertext is not discarded.
- Generation: `java.security.SecureRandom`; user never chose it.
- Guest provisioning: `DesktopVncAuthProvisioner.provisionCommand` — `printf '%s\n' '<pw>' | vncpasswd -f`, single-quoted; passwd file `~/.vnc/linexa-vnc-passwd` inside guest rootfs, chmod 600.
- Xvnc auth: started with `-rfbauth`; client performs RFB VNC-Auth challenge/response.
- Secret redaction: `VNC_PASSWD_SECRET_REGEX` masks `printf '%s\n' .*? | vncpasswd` in forensic argv dumps.
- Logs: `RuntimeTrace` records state transitions but not password values.
- Exports: RootFS export includes guest FS — passwd file at `~/.vnc/linexa-vnc-passwd` inside rootfs.

### 4.3 VNC binding

Xvnc binds `-localhost yes` when the guest Xvnc advertises support (TigerVNC 1.11+). On older Xvnc the command omits the flag and the server's default bind behavior applies. Loopback-only is **conditional** on Xvnc version — see [`features/DESKTOP_AND_VNC.md`](./DESKTOP_AND_VNC.md).

Web mode: cleartext HTTP allowed for loopback origins only; WebView pins navigation to the exact loopback origin.

### 4.4 Keystore integration

Implemented (P11). `AndroidKeystoreSecretCipher` provides `AES/GCM/NoPadding` envelopes under an `AndroidKeyStore` key; `WorkspaceSecretAttributes` applies protect/reveal at the repository boundary; `VncPasswordMigrationManager` rewrites legacy plaintext rows on every app start (idempotent, one read when there is nothing to do). No Room schema change was needed — the `attributes` column is retained and the stored string is simply an envelope now.

Failure semantics are deliberate: an encryption failure never stores a new secret in the clear; an unreadable existing ciphertext is kept and marked unreadable so a transient Keystore failure does not destroy a recoverable password. `WorkspaceSecretAttributes` is fail-closed, and `FeatureSettings.toString` redacts the value.

The manifest and backup XML **comments** still describe the old plaintext design. They are stale and do not describe current behaviour; the sections above are authoritative.

### 4.5 Root-emulated execution

Linexa's guest `sudo` is not a setuid binary: PRoot rejects `setuid(0)` from an
emulated non-root identity with `EPERM`, so a real `sudo` cannot work inside a
`linexa`-identity session. Instead, `/usr/local/bin/sudo` asks the app through a
workspace-local mailbox (`/tmp/.linexa-root-req.*`) and `RootExecBridge` runs
the command in a separate `-0` PRoot one-shot process — the same root-emulated
execution path used by the Package Manager. The interactive counterpart,
**Apps → Root shell**, is a genuine `-0` PTY.

Security properties:

- The caller's uid, environment and cwd are unchanged; nothing switches the
  whole session to root.
- The bridge accepts only regular, non-symlinked request directories inside the
  workspace's own already-world-writable `/tmp`; commands are capped at 64 KiB
  and claimed atomically before execution to prevent replay.
- No host privilege, setuid bit, or Android permission is added.
- Failure is explicit: a missing bridge fails within 15 s, a dead heartbeat
  within 30 s, both with exit code 125 and actionable guidance; there is no
  silent fallback to running the command as the caller.

The remaining exposure is workspace-level trust: any guest process that can
execute `/usr/local/bin/sudo` can run root-emulated commands. That is inherent
to PRoot, where the app uid already owns the entire rootfs. See
[`features/ROOT_SHELL_AND_SUDO.md`](./ROOT_SHELL_AND_SUDO.md) for the protocol,
limits and device evidence.

---

## 5. Limitations and known issues

- The VNC password is encrypted at rest, but it is still plaintext in memory while the desktop runs, in the guest `~/.vnc/linexa-vnc-passwd` file (chmod 600), and in a RootFS export; backup exclusion does not cover an explicit export.
- Legacy migration is fail-open with retry, not a guarantee: a row is encrypted only when the migration sweep can read and rewrite it.
- Backup exclusion mitigates cloud/device-transfer leakage only.
- No user-facing enforcement labels (ENFORCED/ADVISORY/UNAVAILABLE) on Shared Storage / Network panels despite `WorkspaceCapabilities.kt` existing.
- VNC binding loopback-only is conditional on Xvnc ≥ 1.11; on older guests the desktop may be reachable beyond the device when `-SecurityTypes None` applies.
- Manifest/backup XML comments still say "plaintext"; the current documentation above is the source of truth for storage.
- security flows still need on-device verification; see [`engineering/TESTING_STRATEGY.md`](../engineering/TESTING_STRATEGY.md).

---

## 6. Related documentation

- [`features/DESKTOP_AND_VNC.md`](./DESKTOP_AND_VNC.md) — VNC auth, binding
- [`features/ROOT_SHELL_AND_SUDO.md`](./ROOT_SHELL_AND_SUDO.md) — Root shell, guest `sudo` bridge, root-emulated identity and limits
- [`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md) — PRoot, fake root, seccomp
- [`architecture/DATA_AND_PERSISTENCE.md`](../architecture/DATA_AND_PERSISTENCE.md) — Room schema, `attributes` column
- [`engineering/TESTING_STRATEGY.md`](../engineering/TESTING_STRATEGY.md) — verification status
