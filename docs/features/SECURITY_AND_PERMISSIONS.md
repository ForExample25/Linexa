# Security and Permissions

**Source locations:** `app/src/main/AndroidManifest.xml`,
`app/src/main/java/com/fem/linexa/core/security/`,
`app/src/main/java/com/fem/linexa/domain/workspace/WorkspaceMetadata.kt`,
`app/src/main/java/com/fem/linexa/domain/workspace/WorkspaceCapabilities.kt`,
`app/src/main/java/com/fem/linexa/data/security/AndroidKeystoreSecretCipher.kt`,
`app/src/main/java/com/fem/linexa/data/repository/WorkspaceSecretAttributes.kt`,
`app/src/main/java/com/fem/linexa/service/workspace/VncPasswordMigrationManager.kt`,
`app/src/main/java/com/fem/linexa/service/runtime/features/FeatureSettings.kt`,
`app/src/main/res/xml/backup_rules.xml`,
`app/src/main/res/xml/data_extraction_rules.xml`,
`app/src/main/res/xml/network_security_config.xml`

---

## 1. Purpose

This document describes Linexa's security boundaries, the permissions it
requests, how workspace secrets are stored, what backups exclude, how archives
are validated, and what the PRoot isolation model does and does not provide.

---

## 2. Security boundaries

```
Android host (trusted computing base)
  ├── Android kernel and SELinux
  ├── Android permissions
  ├── Android Keystore
  └── App-private storage

Linexa app (single Android UID)
  ├── App process and in-memory secrets
  ├── Room database (workspace metadata)
  ├── SharedPreferences
  ├── App-private logs
  └── Bootstrap binaries in nativeLibraryDir

PRoot guest (same Android UID)
  ├── PRoot binary
  ├── Guest rootfs (app-private directory)
  └── Guest processes (shell, sshd, Xvnc, feature daemons)

Shared storage (opt-in SAF grant)
Network (Android host stack)
```

PRoot is path translation, not kernel namespace or cgroup isolation. A guest
process runs as the app's Android UID. Workspace-to-workspace isolation is
therefore **advisory**: it prevents accidental cross-workspace file access
through the normal UI and runtime paths, but it is not a kernel security
boundary. The app does not claim otherwise.

---

## 3. Enforcement levels

| Area | Level | Mechanism |
|---|---|---|
| RootFS archive extraction | Enforced | Path and entry validation before extraction (`RealArchiveValidator`, `ArchiveEntryPath`) |
| Backup exclusion | Enforced (scoped) | `backup_rules.xml`, `data_extraction_rules.xml` |
| VNC password at rest | Enforced | AES-GCM envelope under an Android Keystore key |
| Shared-storage access | Advisory | User-selected SAF grant; no access without it |
| Guest network exposure | Advisory | Host network stack; no guest firewall by default |
| Workspace-to-workspace isolation | Advisory | PRoot path translation, same UID |
| Root-emulated commands | Advisory (workspace trust) | Separate `-0` PRoot process, no host privilege |
| SELinux execution of bundled binaries | Enforced | `nativeLibraryDir` is executable under the app's SELinux label |

`WorkspaceCapabilities` models these levels for code that needs to reason about
them. The UI does not currently render enforcement labels on every panel.

---

## 4. Permissions

Declared in `AndroidManifest.xml`:

| Permission | Use |
|---|---|
| `INTERNET` | RootFS downloads, update checks, guest network access |
| `ACCESS_NETWORK_STATE` | Network posture and DNS discovery |
| `FOREGROUND_SERVICE` | Workspace runtime service |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Runtime service type for the workspace session |
| `FOREGROUND_SERVICE_DATA_SYNC` | Import/export and download work |
| `POST_NOTIFICATIONS` | Runtime and download notifications (runtime permission on Android 13+) |
| `MANAGE_EXTERNAL_STORAGE` | Opt-in all-files access for the file manager on Android 11+ |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Opt-in request to keep long sessions alive |
| `WAKE_LOCK` | Opt-in partial wake lock with a time limit |

The app does not request root. `MANAGE_EXTERNAL_STORAGE` is not required for
normal operation; the SAF file manager works with a user-selected tree.

---

## 5. Workspace secrets

### 5.1 VNC password

- Field: `FeatureSettings.desktopVncPassword`, default empty; enabling the
  desktop generates a password unless the user has explicitly allowed a
  no-password desktop (`feature.desktop.vnc.allowUnauthenticated`).
- Stored in `WorkspaceMetadata.attributes` under
  `feature.desktop.vnc.password` as an AES-GCM envelope
  (`iv:ciphertext`, `AES/GCM/NoPadding`) written by
  `WorkspaceSecretAttributes`. The repository decrypts on read.
- Legacy plaintext values are rewritten at app start by
  `VncPasswordMigrationManager`. The migration is idempotent.
- If the Keystore cannot decrypt a value (for example after a restore to a new
  device), the row is marked
  `feature.desktop.vnc.password.unreadable=true`. The desktop refuses to start
  rather than running with an empty password; the ciphertext is kept.
- An empty password is not, by itself, permission to run unauthenticated:
  the start gates require the explicit consent flag
  (`desktopVncAllowUnauthenticated`, default false) and otherwise fail closed
  before Xvnc starts. The consent panel warns that any app on the device can
  reach a loopback-bound desktop.
- Encryption failure never falls back to storing plaintext.
- `FeatureSettings.toString` redacts the password.

While a desktop runs, the password is also present in memory and in the guest
file `~/.vnc/linexa-vnc-passwd` (mode 600). A RootFS export warns about guest
credentials and removes that file by default; the user can explicitly choose to
include credentials (finding 022).

### 5.2 Secret redaction

`VNC_PASSWD_SECRET_REGEX` masks a legacy `printf … | vncpasswd` payload in
forensic argv dumps as defence in depth. VNC provisioning no longer puts the
password in the command: it is written to the guest process's stdin
(`execWithInput`), so the current path has no argv secret to redact. Runtime
trace records state transitions, not secret values.

---

## 6. Backup and device transfer

`allowBackup="true"` is scoped by `backup_rules.xml` (Android 11 and below) and
`data_extraction_rules.xml` (Android 12+). Both exclude:

- `linexa.db`, `linexa.db-wal`, `linexa.db-shm`
- `workspaces/`
- `logs/`
- `bootstrap/`
- `linexa_settings.xml`

The exclusions cover cloud backup and device-to-device transfer. They do not
cover a user-initiated RootFS export or a manual copy of app-private files.

---

## 7. Network policy

Cleartext HTTP is disabled by default and allowed only for `127.0.0.1` and
`localhost`, which supports the WebView desktop mode. WebView navigation is
pinned to the loopback origin it was opened with; file and content access are
disabled. The server behind that origin is user-supplied: Linexa does not
authenticate it or control which interface it binds, so its own access controls
and bind address remain the user's responsibility.

See [`features/NETWORKING.md`](./NETWORKING.md) for DNS, hosts, and mirror
provisioning.

---

## 8. Archive validation

RootFS archives are validated before extraction:

- Entry paths are normalized and checked for traversal (`..`, absolute paths,
  empty segments).
- Symlink and hardlink targets are checked so a link cannot escape the
  extraction root.
- Entry counts and expanded sizes are bounded to reject archive bombs.
- Extraction uses the bundled GNU tar under PRoot after the JVM-side
  format/validation pass.

Invalid archives fail installation; no partial archive is activated as a
workspace.

---

## 9. Root-emulated execution

The guest `sudo` is not a setuid binary. PRoot rejects `setuid(0)` from an
emulated non-root identity, so `/usr/local/bin/sudo` asks the app through a
workspace-local mailbox, and `RootExecBridge` runs the command in a separate
`-0` PRoot one-shot process. The interactive counterpart is **Apps → Root
shell**, a real `-0` PTY.

Properties:

- The caller's UID, environment, and working directory are unchanged.
- The bridge accepts only regular, non-symlinked request directories inside
  the workspace's own `/tmp`.
- Commands are capped at 64 KiB and claimed atomically before execution.
- No host privilege, setuid bit, or Android permission is added.
- A missing bridge or dead heartbeat fails explicitly with exit code 125; there
  is no silent fallback to running the command as the caller.

The remaining exposure is workspace-level trust: any guest process that can
execute `/usr/local/bin/sudo` can run root-emulated commands. That is inherent
to PRoot, where the app UID already owns the entire rootfs. See
[`features/ROOT_SHELL_AND_SUDO.md`](./ROOT_SHELL_AND_SUDO.md).

---

## 10. Limitations

- **PRoot is not a sandbox.** It does not create a separate kernel UID,
  namespace, or cgroup. A guest process can reach any file the app can reach.
- **Guest password file.** The VNC password is plaintext in the guest while
  the desktop runs. A RootFS export offers to remove it (the default), along
  with SSH private keys and GnuPG; "export as-is" is the user's explicit
  choice.
- **Backup exclusion is not export protection.** A user-initiated "export
  as-is" contains the guest filesystem, including the VNC passwd file; the
  default export removes credential paths but other guest secrets may remain.
- **Loopback binding depends on Xvnc version, and is not authentication.**
  Android has no per-app loopback isolation, so a no-password desktop is
  reachable by every app on the device; it is refused unless the user grants
  explicit consent. On guests without `-localhost` support the server may also
  listen beyond loopback; the post-start tri-state bind check stops a routable
  listener, and an unverifiable bind cannot pass as loopback-only.
- **Enforcement labels are not shown.** `WorkspaceCapabilities` exists in the
  domain layer, but the Shared Storage and Network panels do not display
  enforced/advisory badges.
- **Phantom-process limit is not managed.** Android may kill the app when it
  holds too many child processes. Settings → Access reports whether the
  platform monitor is on, off, or unknown and shows copy-only `adb` guidance;
  Linexa does not change the setting or prevent the kill.

---

## 11. Related documentation

- [`features/DESKTOP_AND_VNC.md`](./DESKTOP_AND_VNC.md) — VNC authentication and binding
- [`features/ROOT_SHELL_AND_SUDO.md`](./ROOT_SHELL_AND_SUDO.md) — root shell and bridge protocol
- [`features/NETWORKING.md`](./NETWORKING.md) — DNS, mirrors, cleartext policy
- [`architecture/DATA_AND_PERSISTENCE.md`](../architecture/DATA_AND_PERSISTENCE.md) — Room schema and `attributes`
