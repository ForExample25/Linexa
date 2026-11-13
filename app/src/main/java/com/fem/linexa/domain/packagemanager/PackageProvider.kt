package com.fem.linexa.domain.packagemanager

import com.fem.linexa.domain.runtime.CommandExecutor
import kotlinx.coroutines.flow.Flow

/**
 * §1.92 — Unified package-manager abstraction over the workspace's native
 * distro package manager.
 *
 * One implementation per distro family ([AptProvider], [PacmanProvider],
 * [ApkProvider], [DnfProvider], [ZypperProvider]), selected at runtime by
 * [PackageManagerDetector]. The UI never talks to a concrete provider — the
 * whole Package Manager feature communicates only with this interface, so
 * adding a new distro means implementing one more provider.
 *
 * Every command a provider builds must go through its own shell-safety
 * guard (alnum + `+ - . _` for package names, restricted charset for search
 * queries) before it reaches the [CommandExecutor] port (§9A) — no user input is ever
 * interpolated unvalidated into a shell command (§1.14 hardening, carried
 * forward from the Phase 0 `AptPackageManager`).
 */
interface PackageProvider {

    /** Stable identifier, matching [PackageManagerDetector]'s keys (`apt`, `pacman`, …). */
    val key: String

    /** Human-readable manager name + optional version (`apt`, `6.0.2`, …). */
    suspend fun metadata(runtimeBinder: CommandExecutor, workspaceId: String): PkgManagerInfo

    /** Every package currently installed in the workspace. */
    suspend fun listInstalled(runtimeBinder: CommandExecutor, workspaceId: String): List<InstalledPkg>

    /**
     * Names of every package the manager reports as fully installed, or
     * `null` when the query failed and absence cannot be concluded.
     *
     * Install-state detection needs the difference between "measured absent"
     * and "not measured": an empty-map answer is not evidence that a package
     * is missing. Providers with a cheaper exact query (apt's `dpkg-query`)
     * override this; the default reads [listInstalled] and treats an empty
     * result as unknown rather than as a trustworthy "nothing installed".
     */
    suspend fun installedNames(runtimeBinder: CommandExecutor, workspaceId: String): Set<String>? =
        listInstalled(runtimeBinder, workspaceId)
            .mapTo(LinkedHashSet()) { it.name }
            .takeIf { it.isNotEmpty() }

    /**
     * Search the Store for [query], paged by [limit]/[offset].
     * [PackageSearchPage.total] is the real total when computable, otherwise
     * `offset + packages.size`.
     */
    suspend fun search(
        runtimeBinder: CommandExecutor,
        workspaceId: String,
        query: String,
        limit: Int,
        offset: Int
    ): PackageSearchPage

    /** Refresh repository metadata (`apt-get update`, `pacman -Sy`, …). */
    suspend fun refreshSources(runtimeBinder: CommandExecutor, workspaceId: String): Flow<OperationEvent>

    /** Install (or update) a single package. */
    suspend fun install(runtimeBinder: CommandExecutor, workspaceId: String, packageName: String): Flow<OperationEvent>

    /** Remove a single installed package. */
    suspend fun remove(runtimeBinder: CommandExecutor, workspaceId: String, packageName: String): Flow<OperationEvent>

    /** Upgrade every installed package. */
    suspend fun upgrade(runtimeBinder: CommandExecutor, workspaceId: String): Flow<OperationEvent>

    /** Whether [packageName] is currently installed. */
    suspend fun isInstalled(runtimeBinder: CommandExecutor, workspaceId: String, packageName: String): Boolean
}
