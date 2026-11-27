# Workspace Networking

**Source locations:** `app/src/main/java/com/fem/linexa/service/runtime/features/WorkspaceNetworkProvisioner.kt`,
`app/src/main/java/com/fem/linexa/domain/workspace/NetworkSettings.kt`,
`app/src/main/java/com/fem/linexa/domain/network/`,
`app/src/main/java/com/fem/linexa/data/network/`,
`app/src/main/java/com/fem/linexa/domain/packagemanager/PackageMirrorPlanner.kt`,
`app/src/main/java/com/fem/linexa/service/distro/DownloadService.kt`,
`app/src/main/res/xml/network_security_config.xml`

---

## 1. Purpose

Workspace networking covers the settings that make a guest's network usable
and reproducible: DNS, `/etc/hosts`, package mirrors, and the RootFS download
mirror. It also defines the app's cleartext-HTTP policy.

---

## 2. Scope

In scope:

- Per-workspace DNS mode and custom resolver list
- `/etc/hosts` merging with managed markers
- Package-manager mirror configuration for apt, dnf, pacman, zypper, and apk
- RootFS archive download mirror and checksum handling
- Network security configuration and WebView origin policy

Out of scope:

- PRoot runtime: [`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md)
- Desktop/VNC networking: [`features/DESKTOP_AND_VNC.md`](./DESKTOP_AND_VNC.md)

---

## 3. Settings model

`NetworkSettings` is a per-workspace value object persisted in workspace
attributes.

| Setting | Values |
|---|---|
| `dnsMode` | `SYSTEM` (default) or `CUSTOM` |
| `dnsServers` | Up to four IPv4/IPv6 literals, validated before persistence |
| `packageMirrorMode` | `DEFAULT` or `CUSTOM` |
| `packageMirrorUrl` | Mirror base URL, length- and character-validated |
| `rootfsMirrorUrl` | Optional archive URL override |

Validation rejects malformed IP literals, unsupported URL characters, and
overlong values. The UI surfaces the rejection rather than storing an invalid
setting.

`WorkspaceNetworkProvisioner` applies the settings at every workspace start.
DNS, hosts, and mirror failures are reported in the provisioning result but do
not prevent the session from starting.

---

## 4. DNS

- **System (default).** The guest's `/etc/resolv.conf` is seeded from the
  Android host's live resolver topology through `ConnectivityManager` link
  properties (`AndroidHostDnsProvider`). This follows the network the device
  is actually using, including VPNs and private DNS.
- **Custom.** The user's IP literals are written instead. Entries are
  validated before they are persisted.

There is no hardcoded public resolver fallback. If the host exposes no
resolver, the guest's file is left without fabricated servers.

---

## 5. `/etc/hosts`

`HostsFileMerger` merges user entries into the guest's `/etc/hosts` between
managed markers:

```
# BEGIN Linexa managed
...
# END Linexa managed
```

Lines outside the markers are preserved. Re-applying is idempotent: the
managed block is replaced, not appended repeatedly.

---

## 6. Package mirrors

`PackageMirrorPlanner` maps a custom mirror URL to the configuration file
format of the guest's package manager:

| Manager | Configuration target |
|---|---|
| apt | `sources.list.d` entry |
| dnf | repository file |
| pacman | `mirrorlist` |
| zypper | repository file |
| apk | `repositories` |

The original files are backed up with a `.linexa-bak` suffix before rewrite,
which makes the change reversible. Mirror provisioning is skipped when the
same mirror is already applied.

The planner targets the package manager the guest actually uses; a guest with
multiple package managers gets the detected primary manager configured rather
than every possible one.

---

## 7. RootFS download mirror

The RootFS archive URL can be overridden per workspace
(`network.mirror.rootfs.url`). Only the archive URL is overridden:

- The SHA-256 sidecar always comes from the official catalog.
- The checksum lookup always uses the official archive filename.
- A mirror serving different bytes fails verification, and the install aborts.
- If the sidecar cannot be fetched, the install fails closed unless the
  catalog entry explicitly declares that no checksum exists.

A mirror can therefore be slow or unavailable, but it cannot cause unverified
content to be installed silently.

---

## 8. Network security configuration

`network_security_config.xml` keeps the platform default
(`cleartextTrafficPermitted="false"`) and permits cleartext only for:

- `127.0.0.1`
- `localhost`

This is what allows WebView mode to load a guest HTTP server on loopback.
HTTPS traffic for downloads and update checks uses the default configuration.
Adding another cleartext host requires an explicit change to this file.

---

## 9. Limitations

- **Guest-side enforcement is limited.** Linexa configures DNS and mirrors; it
  does not run a firewall or intercept guest traffic. A guest process can
  change its own resolver or hosts file while running.
- **Mirror formats vary by distribution version.** Package-manager
  configuration syntax changes between releases. The planner targets the
  detected manager and its standard file layout; an unusual or heavily
  customized guest configuration may need manual repair.
- **Custom DNS is a static list.** It does not follow network changes while
  the workspace runs. A workspace restart re-applies the current setting.
- **RootFS mirror override applies at download time.** It does not affect
  package downloads inside the guest, which use the package mirror setting.

---

## 10. Related documentation

- [`features/DESKTOP_AND_VNC.md`](./DESKTOP_AND_VNC.md) — VNC loopback and WebView mode
- [`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md) — runtime provisioning order
- [`architecture/DATA_AND_PERSISTENCE.md`](../architecture/DATA_AND_PERSISTENCE.md) — RootFS catalog and checksums
