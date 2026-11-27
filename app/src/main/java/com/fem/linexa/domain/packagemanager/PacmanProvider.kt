package com.fem.linexa.domain.packagemanager

import com.fem.linexa.domain.runtime.CommandExecutor
import com.fem.linexa.domain.runtime.CommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §1.92 — Arch Linux package provider, backed by pacman.
 *
 * §1.146 — reworked against real pacman output (Arch Linux ARM, pacman
 * v7.1.0 / libalpm v16.0.1):
 *
 * - Installed: `pacman -Q` for name/version, `pacman -Qi` (batch) for size +
 *   description, and `pacman -Sl` for the repository — `-Qi` has **no**
 *   `Repository` field, so the old single-`-Qi` design could never fill it.
 *   The batch `-Qi` over all installed names was verified to work (158
 *   packages, 1.4 KB of argv), so it stays one command.
 * - Store: `pacman -Ss <query>` for the candidate list, then `pacman -Si`
 *   over the visible page for real download/installed sizes. `-Si` exits 1
 *   when *any* requested name is unknown but still prints the blocks it did
 *   resolve, so its output is parsed regardless of exit code.
 * - Operations: `pacman -Sy` (refresh), `-S --noconfirm` (install), `-Rns
 *   --noconfirm` (remove), `-Syu --noconfirm` (upgrade), streamed live.
 *
 * Every user-supplied name/query passes [PackageShell] before interpolation.
 */
@Singleton
class PacmanProvider @Inject constructor() : PackageProvider {

    override val key: String = PackageManagerDetector.KEY_PACMAN

    override suspend fun metadata(runtimeBinder: CommandExecutor, workspaceId: String): PkgManagerInfo {
        val result = runtimeBinder.execCommand(workspaceId, "pacman --version")
        return PkgManagerInfo(name = "pacman", version = PacmanOutputParser.parseVersion(result.output))
    }

    override suspend fun listInstalled(runtimeBinder: CommandExecutor, workspaceId: String): List<InstalledPkg> {
        val base = runtimeBinder.execCommand(workspaceId, "pacman -Q")
        if (base.exitCode != 0) return emptyList()
        val nameVersions = base.output.lines().mapNotNull { raw ->
            val parts = raw.trim().split(' ').filter { it.isNotEmpty() }
            if (parts.size < 2) null else parts[0] to parts[1]
        }
        if (nameVersions.isEmpty()) return emptyList()
        // Pre-release audit fix (2026-09-02, security S5): re-gate parsed
        // names (repo output) before interpolating them into a new shell
        // string — defense-in-depth identical to the user-input paths.
        val names = nameVersions.map { it.first }.filter { PackageShell.isSafePackageName(it) }
        if (names.isEmpty()) return emptyList()
        val info = runtimeBinder.execCommand(workspaceId, "pacman -Qi ${names.joinToString(" ")}")
        val byName = if (info.exitCode == 0) PacmanOutputParser.parseInstalledInfo(info.output) else emptyMap()
        // Repository attribution: `-Sl` is the only local source that maps an
        // installed package to the repo it came from. Best-effort — a guest
        // with no synced databases yields an empty map and null repositories
        // rather than a fabricated value.
        val repoByName = runtimeBinder.execCommand(workspaceId, "pacman -Sl")
            .takeIf { it.exitCode == 0 }
            ?.let { PacmanOutputParser.parseRepoMap(it.output) }
            .orEmpty()
        // §1.147: `-Qu` is pacman's own upgrade report and exits 1 when there
        // is nothing to upgrade, so a non-zero exit is an empty map (no UP
        // actions), not a failure.
        val upgradable = PacmanOutputParser.parseUpgradable(
            runtimeBinder.execCommand(workspaceId, "pacman -Qu").output
        )
        return nameVersions.map { (name, version) ->
            val details = byName[name]
            val groups = details?.groups.orEmpty()
            InstalledPkg(
                name = name,
                version = version,
                installedSizeBytes = details?.installedSizeBytes,
                repository = repoByName[name],
                description = details?.description,
                // pacman's group is its category concept; a package in no
                // group shows its repository instead (handled by the model).
                section = groups.firstOrNull(),
                updateVersion = upgradable[name]?.takeIf { it != version },
                // §1.147: `base` is the group pacman itself defines as the
                // minimal system set, and pacman is in it — removing any of it
                // breaks the workspace. That membership is pacman's own
                // declaration, read from `-Qi`, not an app-side list.
                removable = PROTECTED_GROUP !in groups && name !in PROTECTED_NAMES
            )
        }
    }

    override suspend fun search(
        runtimeBinder: CommandExecutor,
        workspaceId: String,
        query: String,
        limit: Int,
        offset: Int
    ): PackageSearchPage {
        if (!PackageShell.isSafeSearchQuery(query)) {
            return PackageSearchPage(total = 0, packages = emptyList())
        }
        val result = runtimeBinder.execCommand(workspaceId, "pacman -Ss $query")
        if (result.exitCode != 0) {
            return PackageSearchPage(total = 0, packages = emptyList())
        }
        // F-038 / R-27: page while parsing.
        val parsed = PacmanOutputParser.parseSearchPage(result.output, offset, limit)
        val page = parsed.packages
        if (page.isEmpty()) return PackageSearchPage(total = parsed.total, packages = page)
        // Enrich only the visible page with the sizes `-Ss` doesn't carry.
        // `-Si` exits 1 if any name is unresolvable yet still prints the rest,
        // so parse the output regardless of exit code.
        // Pre-release audit fix (2026-09-02, security S5): re-gate the parsed
        // names before shell interpolation.
        val safePageNames = page.map { it.name }.filter { PackageShell.isSafePackageName(it) }
        val info = if (safePageNames.isEmpty()) {
            runtimeBinder.execCommand(workspaceId, "pacman -Si")
        } else {
            runtimeBinder.execCommand(workspaceId, "pacman -Si ${safePageNames.joinToString(" ")}")
        }
        val byName = PacmanOutputParser.parseAvailableInfo(info.output)
        return PackageSearchPage(
            total = parsed.total,
            packages = page.map { pkg ->
                val details = byName[pkg.name] ?: return@map pkg
                pkg.copy(
                    downloadSizeBytes = details.downloadSizeBytes,
                    installedSizeBytes = details.installedSizeBytes,
                    repository = details.repository ?: pkg.repository,
                    description = pkg.description ?: details.description,
                    section = details.groups.firstOrNull(),
                    homepageUrl = details.homepageUrl
                )
            }
        )
    }

    override suspend fun refreshSources(runtimeBinder: CommandExecutor, workspaceId: String): Flow<OperationEvent> =
        streamOperation(runtimeBinder, workspaceId, "pacman -Sy", PackageOperation.REFRESH)

    override suspend fun install(runtimeBinder: CommandExecutor, workspaceId: String, packageName: String): Flow<OperationEvent> {
        if (!PackageShell.isSafePackageName(packageName)) {
            return flow { emit(OperationEvent.Failure("Invalid package name", "")) }
        }
        return streamOperation(runtimeBinder, workspaceId, "pacman -S --noconfirm $packageName", PackageOperation.INSTALL)
    }

    override suspend fun remove(runtimeBinder: CommandExecutor, workspaceId: String, packageName: String): Flow<OperationEvent> {
        if (!PackageShell.isSafePackageName(packageName)) {
            return flow { emit(OperationEvent.Failure("Invalid package name", "")) }
        }
        return streamOperation(runtimeBinder, workspaceId, "pacman -Rns --noconfirm $packageName", PackageOperation.REMOVE)
    }

    override suspend fun upgrade(runtimeBinder: CommandExecutor, workspaceId: String): Flow<OperationEvent> =
        streamOperation(runtimeBinder, workspaceId, "pacman -Syu --noconfirm", PackageOperation.UPGRADE)

    override suspend fun isInstalled(runtimeBinder: CommandExecutor, workspaceId: String, packageName: String): Boolean {
        if (!PackageShell.isSafePackageName(packageName)) return false
        return runtimeBinder.execCommand(workspaceId, "pacman -Q $packageName").exitCode == 0
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun streamOperation(
        runtimeBinder: CommandExecutor,
        workspaceId: String,
        command: String,
        operation: PackageOperation
    ): Flow<OperationEvent> = callbackFlow {
        send(OperationEvent.Progress(""))
        val result = runtimeBinder.execCommandStreaming(workspaceId, command) { line ->
            trySend(OperationEvent.Progress(line))
        }
        if (result.exitCode == 0) {
            send(OperationEvent.Success(result.output))
        } else {
            send(OperationEvent.Failure(messageFor(result, operation), result.output))
        }
        close()
    }.flowOn(Dispatchers.IO)

    private fun messageFor(result: CommandResult, operation: PackageOperation): String {
        if (result.exitCode == -1) return "Command timed out"
        val errorLine = result.output.lines().firstOrNull { it.isNotBlank() } ?: "unknown error"
        return when (operation) {
            PackageOperation.INSTALL -> "Install failed: $errorLine"
            PackageOperation.REMOVE -> "Remove failed: $errorLine"
            PackageOperation.UPDATE -> "Update failed: $errorLine"
            PackageOperation.UPGRADE -> "Upgrade failed: $errorLine"
            PackageOperation.REFRESH -> "Refresh failed: $errorLine"
        }
    }

    private companion object {
        /**
         * pacman's own name for the minimal system package set. Membership is
         * read from `pacman -Qi`'s `Groups` field — the distro's declaration,
         * not an app-side blocklist.
         */
        const val PROTECTED_GROUP = "base"

        /** The package manager itself; removing it strands the workspace. */
        val PROTECTED_NAMES = setOf("pacman")
    }
}
