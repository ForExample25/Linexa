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
 * §1.92 — Fedora/RHEL/CentOS family provider, backed by dnf.
 *
 * §1.146 — rebuilt against real dnf5 (Fedora 44, dnf5 5.4.2.1) after the
 * dnf4-era commands were verified to return **nothing** on dnf5:
 *
 * - Installed: `dnf repoquery --installed --queryformat …` is the primary
 *   path — it is the only dnf command that reports a real per-package
 *   installed size (bytes), and it works on dnf4 and dnf5 alike. When
 *   repoquery is unavailable the provider degrades to `dnf list --installed`
 *   (option form — dnf5 rejects the positional `installed`) and finally to
 *   `rpm -qa --queryformat`, which needs no repo metadata at all. §1.147: the
 *   queryformat also carries rpm's `%{group}` as the card's category, and two
 *   extra queries supply the per-package update state
 *   (`dnf repoquery --upgrades`) and the protected set (`/etc/dnf/protected.d`).
 * - Store: `dnf repoquery --available --latest-limit 1` over a `*query*`
 *   glob yields name/version/download size/install size/repo/url/group/summary
 *   in one command. `dnf search` is the fallback and supplies only name +
 *   summary, so sizes and the OPEN URL stay null there rather than being
 *   fabricated.
 * - Operations: `dnf makecache --refresh` (refresh), `dnf install -y`,
 *   `dnf remove -y`, `dnf upgrade -y`, streamed live.
 *
 * Every user-supplied name/query passes [PackageShell] before interpolation.
 */
@Singleton
class DnfProvider @Inject constructor() : PackageProvider {

    override val key: String = PackageManagerDetector.KEY_DNF

    override suspend fun metadata(runtimeBinder: CommandExecutor, workspaceId: String): PkgManagerInfo {
        val result = runtimeBinder.execCommand(workspaceId, "dnf --version")
        return PkgManagerInfo(name = "dnf", version = DnfOutputParser.parseVersion(result.output))
    }

    override suspend fun listInstalled(runtimeBinder: CommandExecutor, workspaceId: String): List<InstalledPkg> {
        // Primary: repoquery — the only dnf path that carries installed size.
        val repoquery = runtimeBinder.execCommand(
            workspaceId,
            "dnf repoquery --quiet --installed --queryformat '${DnfOutputParser.INSTALLED_QUERYFORMAT}'"
        )
        if (repoquery.exitCode == 0) {
            val parsed = DnfOutputParser.parseRepoquery(repoquery.output)
            if (parsed.isNotEmpty()) return decorate(runtimeBinder, workspaceId, parsed)
        }
        // Fallback 1: the plain table (no sizes). Option form, not positional:
        // dnf5's `dnf list installed` matches a *package named* "installed".
        val table = runtimeBinder.execCommand(workspaceId, "dnf list --installed")
        if (table.exitCode == 0) {
            val parsed = DnfOutputParser.parseInstalled(table.output)
            if (parsed.isNotEmpty()) return decorate(runtimeBinder, workspaceId, parsed)
        }
        // Fallback 2: rpm needs no repo metadata and cannot be disabled on an
        // rpm distro, so an offline guest still gets a populated Installed tab.
        // The empty 4th column is deliberate: rpm records no repository, and
        // the column must stay in place so `%{GROUP}` lands on the section
        // field [DnfOutputParser.parseRepoquery] expects.
        val rpm = runtimeBinder.execCommand(
            workspaceId,
            "rpm -qa --queryformat '%{NAME}|%{EVR}|%{SIZE}||%{GROUP}\\n'"
        )
        if (rpm.exitCode != 0) return emptyList()
        return decorate(runtimeBinder, workspaceId, DnfOutputParser.parseRepoquery(rpm.output))
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
        // Primary: repoquery glob — carries sizes and repo id. `--latest-limit 1`
        // collapses the per-repo duplicates (fedora + updates) into one row.
        val repoquery = runtimeBinder.execCommand(
            workspaceId,
            "dnf repoquery --quiet --available --latest-limit 1 " +
                "--queryformat '${DnfOutputParser.AVAILABLE_QUERYFORMAT}' '*$query*'"
        )
        if (repoquery.exitCode == 0) {
            val all = DnfOutputParser.parseAvailable(repoquery.output)
            if (all.isNotEmpty()) {
                return PackageSearchPage(total = all.size, packages = all.drop(offset).take(limit))
            }
        }
        // Fallback: `dnf search` (name + summary only, no sizes).
        val result = runtimeBinder.execCommand(workspaceId, "dnf search --quiet $query")
        if (result.exitCode != 0) {
            return PackageSearchPage(total = 0, packages = emptyList())
        }
        val all = DnfOutputParser.parseSearch(result.output)
        return PackageSearchPage(
            total = all.size,
            packages = all.drop(offset).take(limit).map { (name, summary) ->
                AvailablePkg(
                    name = name,
                    latestVersion = "",
                    downloadSizeBytes = null,
                    installedSizeBytes = null,
                    repository = null,
                    description = summary
                )
            }
        )
    }

    override suspend fun refreshSources(runtimeBinder: CommandExecutor, workspaceId: String): Flow<OperationEvent> = callbackFlow {
        send(OperationEvent.Progress(""))
        val result = runtimeBinder.execCommandStreaming(workspaceId, "dnf makecache --refresh") { line ->
            trySend(OperationEvent.Progress(line))
        }
        // dnf's update-check family exits 100 to mean "updates available" —
        // still a successful refresh, never an error for this operation.
        if (result.exitCode == 0 || result.exitCode == 100) {
            send(OperationEvent.Success(result.output))
        } else {
            send(OperationEvent.Failure(messageFor(result, PackageOperation.REFRESH), result.output))
        }
        close()
    }.flowOn(Dispatchers.IO)

    override suspend fun install(runtimeBinder: CommandExecutor, workspaceId: String, packageName: String): Flow<OperationEvent> {
        if (!PackageShell.isSafePackageName(packageName)) {
            return flow { emit(OperationEvent.Failure("Invalid package name", "")) }
        }
        return streamOperation(runtimeBinder, workspaceId, "dnf install -y $packageName", PackageOperation.INSTALL)
    }

    override suspend fun remove(runtimeBinder: CommandExecutor, workspaceId: String, packageName: String): Flow<OperationEvent> {
        if (!PackageShell.isSafePackageName(packageName)) {
            return flow { emit(OperationEvent.Failure("Invalid package name", "")) }
        }
        return streamOperation(runtimeBinder, workspaceId, "dnf remove -y $packageName", PackageOperation.REMOVE)
    }

    override suspend fun upgrade(runtimeBinder: CommandExecutor, workspaceId: String): Flow<OperationEvent> =
        streamOperation(runtimeBinder, workspaceId, "dnf upgrade -y", PackageOperation.UPGRADE)

    override suspend fun isInstalled(runtimeBinder: CommandExecutor, workspaceId: String, packageName: String): Boolean {
        if (!PackageShell.isSafePackageName(packageName)) return false
        // `rpm -q` is exact-match, needs no repo metadata, and returns 1 for a
        // missing package — verified on Fedora 44. dnf5's `repoquery` exits 0
        // with empty output for an absent package, so it cannot be used here.
        return runtimeBinder.execCommand(workspaceId, "rpm -q $packageName").exitCode == 0
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * §1.147 — attach the two per-package UI facts dnf reports separately.
     *
     * - Updates come from `dnf repoquery --upgrades`, dnf's own answer to
     *   "what would `dnf upgrade` change", falling back to `dnf check-update`
     *   (exit 100 = "updates available", which is success for a *query*).
     * - The protected set is read from `/etc/dnf/protected.d/`, which is
     *   exactly the list dnf itself refuses to remove. Reading the guest's
     *   configuration keeps the DEL button's disabled state the distro's
     *   declaration; only dnf/rpm themselves are added unconditionally.
     *
     * Both commands are best-effort: on failure no package claims an update
     * and only the toolchain is protected.
     */
    private suspend fun decorate(
        runtimeBinder: CommandExecutor,
        workspaceId: String,
        packages: List<InstalledPkg>
    ): List<InstalledPkg> {
        val upgrades = runtimeBinder.execCommand(
            workspaceId,
            "dnf repoquery --quiet --upgrades --queryformat '%{name}|%{evr}\\n'"
        ).takeIf { it.exitCode == 0 && it.output.isNotBlank() }
            ?.let { DnfOutputParser.parseUpgrades(it.output) }
            ?: runtimeBinder.execCommand(workspaceId, "dnf --quiet check-update")
                // 100 is dnf's documented "updates are available" code.
                .takeIf { it.exitCode == 0 || it.exitCode == 100 }
                ?.let { DnfOutputParser.parseUpgrades(it.output) }
                .orEmpty()
        val protected = runtimeBinder.execCommand(workspaceId, "cat /etc/dnf/protected.d/*.conf")
            .takeIf { it.exitCode == 0 }
            ?.let { DnfOutputParser.parseNameList(it.output) }
            .orEmpty()
        return packages.map { pkg ->
            pkg.copy(
                updateVersion = upgrades[pkg.name]?.takeIf { it != pkg.version },
                removable = pkg.name !in protected && pkg.name !in PROTECTED_NAMES
            )
        }
    }

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
         * The dnf/rpm toolchain itself. Fedora ships these in
         * `/etc/dnf/protected.d/`, which [decorate] reads — this set only
         * covers a guest whose protected.d is missing or unreadable.
         */
        val PROTECTED_NAMES = setOf("dnf", "dnf5", "rpm", "glibc", "systemd")
    }
}
