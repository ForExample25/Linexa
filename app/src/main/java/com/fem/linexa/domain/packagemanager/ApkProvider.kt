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
 * §1.92 — Alpine Linux package provider, backed by apk.
 *
 * §1.146 — reworked against real apk output from both generations still in
 * the wild (Alpine 3.18 / apk-tools 2.14.4, Alpine 3.24 / apk-tools 3.0.6):
 *
 * - Installed: on apk-tools 3 a single `apk query --installed --format yaml`
 *   returns name/version/description/installed-size (exact bytes). apk-tools
 *   2 has no `query` applet, so the provider falls back to
 *   `apk info -e -v` (names+versions) enriched by `apk info -s -v` (sizes,
 *   formatted) and `apk info -d -v` (descriptions) — the `-v` form is what
 *   makes apk emit one line per package instead of multi-line blocks.
 *   Repository is genuinely not tracked per installed package by apk, so it
 *   stays null. §1.147: `apk version -l '<'` adds which packages have an
 *   update, so the UP action appears only where apk would really upgrade.
 * - Store: `apk search -v <query>` returns `name-version - description` in
 *   one command, which is all apk's index exposes; download size is not in
 *   the index, so it stays null. §1.147: `apk info -w` over the visible page
 *   supplies the upstream URL behind the card's OPEN action.
 * - Operations: `apk update`, `apk add`, `apk del`, `apk upgrade`, streamed
 *   live.
 *
 * Every user-supplied name/query passes [PackageShell] before interpolation.
 */
@Singleton
class ApkProvider @Inject constructor() : PackageProvider {

    override val key: String = PackageManagerDetector.KEY_APK

    override suspend fun metadata(runtimeBinder: CommandExecutor, workspaceId: String): PkgManagerInfo {
        val result = runtimeBinder.execCommand(workspaceId, "apk --version")
        return PkgManagerInfo(name = "apk", version = ApkOutputParser.parseVersion(result.output))
    }

    override suspend fun listInstalled(runtimeBinder: CommandExecutor, workspaceId: String): List<InstalledPkg> {
        // Primary (apk-tools 3+): one command, exact byte sizes. The `'*'`
        // pattern is required — `apk query` with no pattern matches nothing.
        val query = runtimeBinder.execCommand(
            workspaceId,
            "apk query --installed --format yaml --fields ${ApkOutputParser.QUERY_FIELDS} '*'"
        )
        if (query.exitCode == 0 && !ApkOutputParser.isQueryUnsupported(query.output)) {
            val parsed = ApkOutputParser.parseQueryYaml(query.output)
            if (parsed.isNotEmpty()) return decorate(runtimeBinder, workspaceId, parsed)
        }
        // Fallback (apk-tools 2): three cheap `apk info` calls. `-e -v` is the
        // authoritative installed set; sizes/descriptions are best-effort
        // enrichment and stay null if their call fails.
        val base = runtimeBinder.execCommand(workspaceId, "apk info -e -v")
        if (base.exitCode != 0) return emptyList()
        val packages = ApkOutputParser.parseInstalled(base.output)
        if (packages.isEmpty()) return emptyList()
        // Pre-release audit fix (2026-09-02, security S5): re-gate names
        // parsed out of guest output before interpolating them into new
        // shell strings — the same rule the user-input paths apply.
        val names = packages.map { it.name }.filter { PackageShell.isSafePackageName(it) }
        if (names.isEmpty()) return emptyList()
        val namesArg = names.joinToString(" ")
        val sizes = runtimeBinder.execCommand(workspaceId, "apk info -s -v $namesArg")
            .takeIf { it.exitCode == 0 }
            ?.let { ApkOutputParser.parseSizes(it.output) }
            .orEmpty()
        val descriptions = runtimeBinder.execCommand(workspaceId, "apk info -d -v $namesArg")
            .takeIf { it.exitCode == 0 }
            ?.let { ApkOutputParser.parseDescriptions(it.output) }
            .orEmpty()
        return decorate(
            runtimeBinder,
            workspaceId,
            packages.map { pkg ->
                pkg.copy(
                    installedSizeBytes = sizes[pkg.name],
                    description = descriptions[pkg.name]
                )
            }
        )
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
        // `-v -d` gives `name-version - description` on both apk generations,
        // so no second per-page enrichment call is needed.
        val result = runtimeBinder.execCommand(workspaceId, "apk search -v -d $query")
        if (result.exitCode != 0) {
            return PackageSearchPage(total = 0, packages = emptyList())
        }
        // F-038 / R-27: keep only the requested page in memory.
        val parsed = ApkOutputParser.parseSearchPage(result.output, offset, limit)
        val page = parsed.packages
        if (page.isEmpty()) return PackageSearchPage(total = parsed.total, packages = page)
        // §1.147: `apk info -w` is the only apk command that reports the
        // upstream project page, and it is what backs the Store card's OPEN
        // action. Queried for the visible page only; a failure leaves the URLs
        // null and the action simply is not rendered.
        val names = page.map { it.name }.filter { PackageShell.isSafePackageName(it) }
        val webpages = if (names.isEmpty()) {
            emptyMap()
        } else {
            runtimeBinder.execCommand(workspaceId, "apk info -w ${names.joinToString(" ")}")
                .takeIf { it.exitCode == 0 }
                ?.let { ApkOutputParser.parseWebpages(it.output) }
                .orEmpty()
        }
        return PackageSearchPage(
            total = parsed.total,
            packages = page.map { it.copy(homepageUrl = webpages[it.name]) }
        )
    }

    override suspend fun refreshSources(runtimeBinder: CommandExecutor, workspaceId: String): Flow<OperationEvent> =
        streamOperation(runtimeBinder, workspaceId, "apk update", PackageOperation.REFRESH)

    override suspend fun install(runtimeBinder: CommandExecutor, workspaceId: String, packageName: String): Flow<OperationEvent> {
        if (!PackageShell.isSafePackageName(packageName)) {
            return flow { emit(OperationEvent.Failure("Invalid package name", "")) }
        }
        return streamOperation(runtimeBinder, workspaceId, "apk add $packageName", PackageOperation.INSTALL)
    }

    override suspend fun remove(runtimeBinder: CommandExecutor, workspaceId: String, packageName: String): Flow<OperationEvent> {
        if (!PackageShell.isSafePackageName(packageName)) {
            return flow { emit(OperationEvent.Failure("Invalid package name", "")) }
        }
        return streamOperation(runtimeBinder, workspaceId, "apk del $packageName", PackageOperation.REMOVE)
    }

    override suspend fun upgrade(runtimeBinder: CommandExecutor, workspaceId: String): Flow<OperationEvent> =
        streamOperation(runtimeBinder, workspaceId, "apk upgrade", PackageOperation.UPGRADE)

    override suspend fun isInstalled(runtimeBinder: CommandExecutor, workspaceId: String, packageName: String): Boolean {
        if (!PackageShell.isSafePackageName(packageName)) return false
        // Verified on apk-tools 2.14.4 and 3.0.6: `apk info -e <name>` exits 0
        // and echoes the name when installed, exits 1 with no output otherwise
        // — including for a package that merely exists in the repositories.
        return runtimeBinder.execCommand(workspaceId, "apk info -e $packageName").exitCode == 0
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * §1.147 — attach the two per-package UI facts apk reports separately:
     * which packages have an update (`apk version -l '<'`, apk's own upgrade
     * report) and which must not be removed (`apk info -e` over the
     * `.make-deps`-free world file is not a thing; apk's protected set is the
     * `alpine-base` chain, which is why the check below is membership in the
     * world file's base packages plus apk itself).
     *
     * Both extra commands are best-effort: a failure means no package claims
     * an update and only apk-tools itself is protected — never the inverse,
     * which would silently hide working actions.
     */
    private suspend fun decorate(
        runtimeBinder: CommandExecutor,
        workspaceId: String,
        packages: List<InstalledPkg>
    ): List<InstalledPkg> {
        val upgradable = runtimeBinder.execCommand(workspaceId, "apk version -l '<'")
            .takeIf { it.exitCode == 0 }
            ?.let { ApkOutputParser.parseUpgradable(it.output) }
            .orEmpty()
        return packages.map { pkg ->
            pkg.copy(
                updateVersion = upgradable[pkg.name]?.takeIf { it != pkg.version },
                removable = pkg.name !in PROTECTED_NAMES
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
         * The apk toolchain and the Alpine base chain it depends on. apk
         * exposes no `Essential`/`Priority` equivalent — `apk del musl` will
         * happily destroy the workspace — so this is the one place where the
         * protected set is named rather than read from the manager. It is
         * deliberately minimal: only packages whose removal makes the
         * workspace unbootable.
         */
        val PROTECTED_NAMES = setOf(
            "apk-tools", "musl", "busybox", "alpine-baselayout", "alpine-base", "alpine-keys"
        )
    }
}
