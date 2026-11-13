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
 * §1.92 — Debian/Ubuntu package provider, backed by apt/dpkg.
 *
 * - Installed: `dpkg-query -W` with a tab-separated `-f` format carrying
 *   name/version/size(installed)/status/section/essential/priority, so one
 *   command yields every field the Installed tab needs. §1.147: `Section` is
 *   surfaced as the package's *category* (`admin`, `shells`) — dpkg tracks the
 *   archive section, not the mirror — and `Essential`/`Priority` are what mark
 *   a package unremovable. A second `apt list --upgradable` call marks which
 *   packages actually have an update, so the UP action is only offered where
 *   apt would really upgrade.
 * - Store: `apt-cache search` for name+description, paged in-process; then
 *   `apt-cache policy` (batch) enriches the visible page with the candidate
 *   version and repository, and `apt-cache show` (batch) adds the real
 *   download size (`Size`, bytes), installed size (`Installed-Size`, KiB),
 *   `Section` and the `Homepage` URL behind the card's OPEN action.
 * - Operations: `apt-get update/install/remove/upgrade`, streamed live.
 *
 * LX-4 (2026-09-01): every MUTATING operation carries an explicit
 * `Lock::Timeout` pair. The guest's `/etc/apt/apt.conf.d/99linexa-lock-wait`
 * (installed by GuestRootfsInitializer on every launch) already provides the
 * same values system-wide; passing them on the command line keeps the
 * wait-instead-of-fail behavior even if that drop-in could not be written
 * (full disk, older image). While a lock is held, apt streams
 * "Waiting for cache lock …" progress into the operation panel — which is
 * exactly the queueing UX the multi-session apt report asked for.
 *
 * §1.146: verified against Debian bookworm arm64 (apt 2.6.1). The
 * `dpkg-query`, `apt-cache search` and `apt-cache policy` shapes were
 * already correct; the added `apt-cache show` pass replaces the previous
 * "download size is not exposed by apt" assumption, which was wrong — apt
 * does publish it, it simply is not in `policy` output.
 *
 * Every user-supplied name/query passes [PackageShell] before interpolation.
 */
@Singleton
class AptProvider @Inject constructor() : PackageProvider {

    override val key: String = PackageManagerDetector.KEY_APT

    override suspend fun metadata(runtimeBinder: CommandExecutor, workspaceId: String): PkgManagerInfo {
        val result = runtimeBinder.execCommand(workspaceId, "apt-get --version")
        return PkgManagerInfo(name = "apt", version = AptOutputParser.parseVersion(result.output))
    }

    override suspend fun listInstalled(runtimeBinder: CommandExecutor, workspaceId: String): List<InstalledPkg> {
        val result = runtimeBinder.execCommand(
            workspaceId,
            "dpkg-query -W -f='${'$'}{Package}\\t${'$'}{Version}\\t${'$'}{Installed-Size}\\t" +
                "${'$'}{Status}\\t${'$'}{Section}\\t${'$'}{Essential}\\t${'$'}{Priority}\\n'"
        )
        val installed = AptOutputParser.parseInstalled(result.output)
        if (installed.isEmpty()) return installed
        // §1.147: `apt list --upgradable` is apt's own answer to "what has an
        // update", so the UP action appears for exactly the packages apt would
        // upgrade. Best-effort: an unreachable mirror leaves the map empty and
        // no package claims an update, rather than every package claiming one.
        val upgradable = runtimeBinder.execCommand(workspaceId, "apt list --upgradable")
            .takeIf { it.exitCode == 0 }
            ?.let { AptOutputParser.parseUpgradable(it.output) }
            .orEmpty()
        return installed.map { pkg ->
            val candidate = upgradable[pkg.name]
            pkg.copy(
                updateVersion = candidate?.takeIf { it != pkg.version },
                // The package manager itself is never offered for removal —
                // uninstalling apt strands the workspace with no way back.
                removable = pkg.removable && pkg.name !in PROTECTED_NAMES
            )
        }
    }

    /**
     * Install-state detection's fast path: one `dpkg-query` for the whole
     * installed set, carrying the status column so only fully-configured
     * (`ii`) packages count. Unlike [listInstalled] this does not run the
     * `apt list --upgradable` enrichment — the launcher already refreshes
     * often and does not need update candidates.
     */
    override suspend fun installedNames(runtimeBinder: CommandExecutor, workspaceId: String): Set<String>? {
        val result = runtimeBinder.execCommand(
            workspaceId,
            "dpkg-query -W -f='${'$'}{Package}\\t${'$'}{db:Status-Abbrev}\\n'"
        )
        if (result.exitCode != 0) return null
        return result.output.lineSequence()
            .mapNotNull { raw ->
                val parts = raw.trim().split('\t', limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val name = parts[0].trim()
                if (name.isEmpty() || !parts[1].startsWith("ii")) return@mapNotNull null
                name
            }
            .toSet()
            .takeIf { it.isNotEmpty() }
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
        val result = runtimeBinder.execCommand(workspaceId, "apt-cache search $query")
        if (result.exitCode != 0) {
            return PackageSearchPage(total = 0, packages = emptyList())
        }
        val all = AptOutputParser.parseSearch(result.output)
        val pageNames = all.drop(offset).take(limit).map { it.first }
        val enriched = if (pageNames.isEmpty()) {
            emptyList()
        } else {
            enrichWithPolicy(runtimeBinder, workspaceId, pageNames, all.toMap())
        }
        return PackageSearchPage(total = all.size, packages = enriched)
    }

    override suspend fun refreshSources(runtimeBinder: CommandExecutor, workspaceId: String): Flow<OperationEvent> =
        streamOperation(runtimeBinder, workspaceId, "apt-get update $LOCK_WAIT_OPTS", PackageOperation.REFRESH)

    override suspend fun install(runtimeBinder: CommandExecutor, workspaceId: String, packageName: String): Flow<OperationEvent> {
        if (!PackageShell.isSafePackageName(packageName)) {
            return flow { emit(OperationEvent.Failure("Invalid package name", "")) }
        }
        return streamOperation(runtimeBinder, workspaceId, "apt-get install -y $packageName $LOCK_WAIT_OPTS", PackageOperation.INSTALL)
    }

    override suspend fun remove(runtimeBinder: CommandExecutor, workspaceId: String, packageName: String): Flow<OperationEvent> {
        if (!PackageShell.isSafePackageName(packageName)) {
            return flow { emit(OperationEvent.Failure("Invalid package name", "")) }
        }
        return streamOperation(runtimeBinder, workspaceId, "apt-get remove -y $packageName $LOCK_WAIT_OPTS", PackageOperation.REMOVE)
    }

    override suspend fun upgrade(runtimeBinder: CommandExecutor, workspaceId: String): Flow<OperationEvent> =
        streamOperation(runtimeBinder, workspaceId, "apt-get upgrade -y $LOCK_WAIT_OPTS", PackageOperation.UPGRADE)

    override suspend fun isInstalled(runtimeBinder: CommandExecutor, workspaceId: String, packageName: String): Boolean {
        if (!PackageShell.isSafePackageName(packageName)) return false
        val result = runtimeBinder.execCommand(workspaceId, "dpkg -l $packageName")
        return result.exitCode == 0 && result.output.contains("ii")
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun streamOperation(
        runtimeBinder: CommandExecutor,
        workspaceId: String,
        command: String,
        operation: PackageOperation,
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

    /**
     * Batch-enrich a page of package names with `apt-cache policy` (candidate
     * version + repository) and `apt-cache show` (download/installed size and
     * section). Both are single batched calls over the visible page only.
     */
    private suspend fun enrichWithPolicy(
        runtimeBinder: CommandExecutor,
        workspaceId: String,
        names: List<String>,
        base: Map<String, String>
    ): List<AvailablePkg> {
        // Pre-release audit fix (2026-09-02, security S5): these names were
        // parsed OUT of guest/repo output (apt-cache policy/show), not typed
        // by the user, so the PackageShell gate that protects user input
        // paths never saw them. Re-gate before they are interpolated back
        // into a new shell string — the same defense-in-depth rule every
        // other interpolation site applies.
        val safeNames = names.filter { PackageShell.isSafePackageName(it) }
        if (safeNames.isEmpty()) {
            return names.map { name ->
                AvailablePkg(
                    name = name,
                    latestVersion = base[name].orEmpty(),
                    downloadSizeBytes = null,
                    installedSizeBytes = null,
                    repository = null,
                    description = null
                )
            }
        }
        val nameArgs = safeNames.joinToString(" ")
        val policyResult = runtimeBinder.execCommand(workspaceId, "apt-cache policy $nameArgs")
        val byName = if (policyResult.exitCode == 0) AptOutputParser.parsePolicy(policyResult.output) else emptyMap()
        // `apt-cache show` exits 100 when *any* name is unknown but still
        // prints the blocks it resolved, so its output is parsed either way.
        val showResult = runtimeBinder.execCommand(workspaceId, "apt-cache show $nameArgs")
        val showByName = AptOutputParser.parseShow(showResult.output)
        return names.map { name ->
            val policy = byName[name]
            val show = showByName[name]
            AvailablePkg(
                name = name,
                latestVersion = policy?.version?.takeIf { it.isNotEmpty() } ?: show?.version.orEmpty(),
                downloadSizeBytes = show?.downloadSizeBytes,
                installedSizeBytes = show?.installedSizeBytes,
                repository = policy?.repository,
                description = base[name] ?: show?.description,
                section = show?.section,
                homepageUrl = show?.homepageUrl
            )
        }
    }

    private companion object {
        /**
         * The apt/dpkg toolchain itself. dpkg already flags these
         * `Priority: required`, so this is belt-and-braces for a workspace
         * whose dpkg database understates them — never the primary source of
         * the DEL button's disabled state.
         */
        val PROTECTED_NAMES = setOf("apt", "dpkg")

        /**
         * LX-4: explicit lock-wait for app-initiated mutations (mirrors the
         * guest drop-in `99linexa-lock-wait`). 300 s then fail — never an
         * unbounded hang in a UI without a visible cancel.
         */
        const val LOCK_WAIT_OPTS = "-o APT::Lock::Timeout=300 -o DPkg::Lock::Timeout=300"
    }
}
