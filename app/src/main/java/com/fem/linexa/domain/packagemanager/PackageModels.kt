package com.fem.linexa.domain.packagemanager

/**
 * §1.92 — Rich package data model shared by every [PackageProvider].
 *
 * Replaces the old `PackageInfo(name, version, description, isInstalled)`
 * with real fields a production Package Manager needs: sizes (in bytes, so
 * the UI can format them with `FileSizeFormatter`), the originating
 * repository, and — for the Store tab — the latest available version and
 * download size. Every field is nullable unless the provider is guaranteed
 * to have it, because distros differ in what their package tools expose
 * cheaply (e.g. apk exposes no per-package repository).
 */

/** Identity of the detected package manager, e.g. `apt 2.7.14`. */
data class PkgManagerInfo(
    val name: String,
    val version: String?
)

/**
 * A package installed in the workspace.
 *
 * §1.147 — three fields the Installed tab's cards render directly:
 *
 * - [section] is the *category* the distro files the package under (apt's
 *   `Section: admin`, pacman's `Groups`, rpm's `Group`), which is a different
 *   concept from [repository] (the mirror/repo id it was fetched from). Only
 *   one of the two is available on most managers, so the UI shows
 *   `section ?: repository` and neither is fabricated from the other.
 * - [updateVersion] is the candidate version when — and only when — the
 *   manager reports an upgrade for this package; `null` means "no update
 *   available", which is what makes the UP action disappear rather than
 *   render disabled.
 * - [removable] is false only when the manager itself declares the package
 *   protected (dpkg `Essential: yes` / `Priority: required`, pacman's `base`
 *   group, dnf's `protected.d`, zypper's locks) or when it *is* the package
 *   manager. It is never a hardcoded app-side blocklist.
 */
data class InstalledPkg(
    val name: String,
    val version: String,
    val installedSizeBytes: Long?,
    val repository: String?,
    val description: String?,
    val section: String? = null,
    val updateVersion: String? = null,
    val removable: Boolean = true
) {
    /** True when the manager reported a newer candidate version. */
    val hasUpdate: Boolean get() = !updateVersion.isNullOrBlank()

    /** The grouping label the card shows — category first, repo as the fallback. */
    val categoryLabel: String? get() = section?.takeIf { it.isNotBlank() } ?: repository?.takeIf { it.isNotBlank() }
}

/**
 * A package available in the workspace's repositories (Store tab).
 *
 * §1.147 — [homepageUrl] is the upstream project page the manager's own
 * metadata carries (`apt-cache show` → `Homepage`, `pacman -Si` → `URL`,
 * `dnf repoquery %{url}`, `zypper info` → `URL`, `apk info -w`). It backs the
 * Store card's OPEN action, which is only rendered when a real `http(s)` URL
 * exists — no URL is ever guessed from the package name.
 */
data class AvailablePkg(
    val name: String,
    val latestVersion: String,
    val downloadSizeBytes: Long?,
    val installedSizeBytes: Long?,
    val repository: String?,
    val description: String?,
    val section: String? = null,
    val homepageUrl: String? = null
) {
    /** The grouping label the card shows — category first, repo as the fallback. */
    val categoryLabel: String? get() = section?.takeIf { it.isNotBlank() } ?: repository?.takeIf { it.isNotBlank() }

    /** Only an absolute http(s) URL is offered to the browser. */
    val openableUrl: String?
        get() = homepageUrl?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
}

/**
 * One page of store results.
 *
 * `total` is the real total when the provider can compute it cheaply
 * (apt's `apt-cache dumpavail` filters a known set); providers that cannot
 * report it (pacman/dnf/apk/zypper search) return
 * `offset + page.size`, so the caller falls back to the
 * `page.size == limit` "there may be more" heuristic.
 */
data class PackageSearchPage(
    val total: Int,
    val packages: List<AvailablePkg>
)
