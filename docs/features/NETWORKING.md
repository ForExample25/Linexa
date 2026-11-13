# Workspace Networking

**Status:** Current — describes the implemented network configuration feature, verified against source.

**Project:** Linexa Linux Workspace (`com.fem.linexa`)

**Source locations:** `app/src/main/java/com/fem/linexa/service/runtime/features/`, `app/src/main/java/com/fem/linexa/data/network/`, `app/src/main/java/com/fem/linexa/domain/network/`, `app/src/main/res/xml/network_security_config.xml`

---

## 1. Purpose

This document is the feature-level reference for Linexa's workspace network configuration: per-workspace DNS, /etc/hosts management, package-mirror provisioning, and RootFS download mirror override.

---

## 2. Scope

In scope:

- Per-workspace DNS (system/custom, IP-literal validation)
- /etc/hosts merging behind managed markers
- Package mirrors per package manager (apt/dnf/pacman/zypper/apk)
- RootFS download mirror (host side, SHA-256 from official catalog)
- Network security configuration (cleartext loopback only, WebView origin pinning)

Out of scope:

- PRoot runtime: [`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md)
- VNC/Web desktop networking: [`features/DESKTOP_AND_VNC.md`](./DESKTOP_AND_VNC.md)

---

## 3. Current implementation status

| Area | Status | Evidence |
|---|---|---|
| Per-workspace DNS (system/custom) | Implemented | `service/runtime/features/`, `AndroidHostDnsProvider` |
| DNS from Android host resolver topology (`ConnectivityManager`) | Implemented | host info subsystem |
| Custom DNS IP-literal validation | Implemented | config validation |
| Old hardcoded 8.8.8.8/8.8.4.4 seed removed | Implemented | replaced by host resolver |
| /etc/hosts merging behind `# BEGIN Linexa managed` markers | Implemented | `HostsFileMerger` |
| Package mirrors per package manager (apt/dnf/pacman/zypper/apk) | Implemented | `PackageMirrorPlanner` |
| `.linexa-bak` backups, fully reversible | Implemented | mirror provisioning |
| RootFS download mirror override per workspace | Implemented | `network.mirror.rootfs.url` |
| SHA-256 sidecar always from official catalog | Implemented | checksum verification, fail-closed |
| Cleartext limited to 127.0.0.1/localhost | Implemented | `network_security_config.xml` |
| WebView pins loopback origin | Implemented | desktop Web mode |

---

## 4. Technical overview

### 4.1 DNS

Per-workspace network settings live in **Settings → Network** and are applied at every workspace start by `WorkspaceNetworkProvisioner`.

- **System (default)** — the guest's `/etc/resolv.conf` is seeded from the Android host's live resolver topology (`ConnectivityManager` link properties). The old hardcoded `8.8.8.8/8.8.4.4` seed is gone.
- **Custom** — user-entered IP literals, validated before persistence; invalid entries are rejected.

### 4.2 /etc/hosts

User entries are merged into the guest's `/etc/hosts` behind managed markers (`# BEGIN Linexa managed` / `# END Linexa managed`) at every start; guest-owned lines outside the markers are preserved.

### 4.3 Package mirrors (guest)

Per package manager (apt/dnf/pacman/zypper/apk), a mirror can be set for the guest's own package manager configuration. The original files are backed up as `.linexa-bak` and are fully reversible.

### 4.4 RootFS download mirror (host side)

The RootFS download URL can be overridden per workspace (`network.mirror.rootfs.url`). Only the archive URL is overridden: the SHA-256 sidecar always comes from the official catalog, and the checksum lookup always uses the official archive filename — a mirror that serves different bytes fails verification and the install aborts. A mirror can be slow or hostile, but never silently wrong. If the sidecar itself cannot be fetched, the install fails closed (nothing unverified is installed) unless the catalog entry honestly declares no checksum at all.

### 4.5 Network security configuration

Cleartext is limited to `127.0.0.1`/`localhost`; Web mode pins the loopback origin. See [`app/src/main/res/xml/network_security_config.xml`](../../app/src/main/res/xml/network_security_config.xml).

---

## 5. Limitations and known issues

- network flows still need on-device verification; see [`engineering/TESTING_STRATEGY.md`](../engineering/TESTING_STRATEGY.md).
- Per-package-manager mirror support depends on the guest's package manager config file formats being correctly targeted.

---

## 6. Related documentation

- [`features/DESKTOP_AND_VNC.md`](./DESKTOP_AND_VNC.md) — VNC loopback, Web mode
- [`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md) — PRoot runtime, networking context
- [`engineering/TESTING_STRATEGY.md`](../engineering/TESTING_STRATEGY.md) — verification status
