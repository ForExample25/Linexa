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
 * §1.92 — openSUSE/SUSE provider, backed by zypper.
 *
 * §1.146 — rebuilt against real zypper (openSUSE Leap 16.0 arm64, zypper
 * 1.14.98) after several of the original assumptions were verified wrong:
 *
 * - Installed: `zypper packages --installed-only` prints its table on
 *   stdout even when repositories are unreachable, but **exits 106**
 *   (`ZYPPER_EXIT_ERR_ZYPP` / repo problem). The old code bailed on any
 *   non-zero exit, so an offline workspace showed an empty Installed tab
 *   despite zypper having produced the full list. Repository access is not
 *   needed to enumerate installed packages, so the command is run with
 *   `--disable-repositories`, which returns the same table with exit 0.
 * - `zypper search --installed-only` does not exist — zypper answers
 *   "The flag --installed-only is not known." and exits non-zero. The
 *   installed-only search flag is `-i`. [isInstalled] no longer relies on
 *   either: `rpm -q` is exact-match and needs no metadata.
 * - Store: `zypper search` alone yields `S | Name | Summary | Type` with no
 *   version at all; `-s` is required for versions. This provider asks for
 *   `-s` and, for the page actually shown, enriches it via `zypper info`,
 *   the only command that reports a size (`Installed Size : 1.2 MiB`).
 * - Exit 104 means "no matching items found", which is an empty result, not
 *   a failure.
 *
 * Every user-supplied name/query passes [PackageShell] before interpolation.
 */
@Singleton
class ZypperProvider @Inject constructor() : PackageProvider {

    override val key: String = PackageManagerDetector.KEY_ZYPPER

    override suspend fun metadata(runtimeBinder: CommandExecutor, workspaceId: String): PkgManagerInfo {
        val result = runtimeBinder.execCommand(workspaceId, "zypper --version")
        return PkgManagerInfo(name = "zypper", version = ZypperOutputParser.parseVersion(result.output))
    }

    override suspend fun listInstalled(runtimeBinder: CommandExecutor, workspaceId: String): List<InstalledPkg> {
        // `--disable-repositories` keeps zypper off the network: the installed
        // list comes from the rpmdb, and without it an unreachable repo turns
        // exit 0 into 106 even though the table was printed.
        val result = runtimeBinder.execCommand(
            workspaceId,
            "zypper --quiet --disable-repositories packages --installed-only"
        )
        val parsed = ZypperOutputParser.parseInstalled(result.output)
        if (parsed.isNotEmpty()) return decorate(runtimeBinder, workspaceId, parsed)
        // Fallback: rpm needs no zypp metadata at all and carries real sizes.
        // openSUSE is an rpm distro, so this path always exists.
        val rpm = runtimeBinder.execCommand(
            workspaceId,
            "rpm -qa --queryformat '%{NAME}|%{EVR}|%{SIZE}|%{GROUP}\\n'"
        )
        if (rpm.exitCode != 0) return emptyList()
        return decorate(runtimeBinder, workspaceId, ZypperOutputParser.parseRpmQuery(rpm.output))
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
        // `-s` is what adds Version/Arch/Repository; plain `se` has none of them.
        val result = runtimeBinder.execCommand(workspaceId, "zypper --quiet search -s $query")
        if (result.exitCode != 0 && result.exitCode != EXIT_NO_MATCH && result.exitCode != EXIT_ZYPP_ERROR) {
            return PackageSearchPage(total = 0, packages = emptyList())
        }
        val all = ZypperOutputParser.parseSearch(result.output)
        val page = all.drop(offset).take(limit)
        return PackageSearchPage(total = all.size, packages = enrich(runtimeBinder, workspaceId, page))
    }

    override suspend fun refreshSources(runtimeBinder: CommandExecutor, workspaceId: String): Flow<OperationEvent> =
        streamOperation(runtimeBinder, workspaceId, "zypper --non-interactive refresh", PackageOperation.REFRESH)

    override suspend fun install(runtimeBinder: CommandExecutor, workspaceId: String, packageName: String): Flow<OperationEvent> {
        if (!PackageShell.isSafePackageName(packageName)) {
            return flow { emit(OperationEvent.Failure("Invalid package name", "")) }
        }
        return streamOperation(runtimeBinder, workspaceId, "zypper --non-interactive install -y $packageName", PackageOperation.INSTALL)
    }

    override suspend fun remove(runtimeBinder: CommandExecutor, workspaceId: String, packageName: String): Flow<OperationEvent> {
        if (!PackageShell.isSafePackageName(packageName)) {
            return flow { emit(OperationEvent.Failure("Invalid package name", "")) }
        }
        return streamOperation(runtimeBinder, workspaceId, "zypper --non-interactive remove -y $packageName", PackageOperation.REMOVE)
    }

    override suspend fun upgrade(runtimeBinder: CommandExecutor, workspaceId: String): Flow<OperationEvent> =
        streamOperation(runtimeBinder, workspaceId, "zypper --non-interactive update -y", PackageOperation.UPGRADE)

    override suspend fun isInstalled(runtimeBinder: CommandExecutor, workspaceId: String, packageName: String): Boolean {
        if (!PackageShell.isSafePackageName(packageName)) return false
        // Exact match, no repo metadata, exit 1 when absent — verified on
        // Leap 16.0. The previous `zypper search --installed-only <name>` was
        // both an unknown flag and a substring match.
        return runtimeBinder.execCommand(workspaceId, "rpm -q $packageName").exitCode == 0
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * §1.147 — attach the two per-package UI facts zypper reports separately.
     *
     * - Updates come from `zypper list-updates`, zypper's own answer to "what
     *   would `zypper update` change". Exit 106 (unreachable repository) still
     *   prints whatever resolved locally, so its output is parsed; a genuinely
     *   empty result means no package claims an update.
     * - The protected set is `zypper locks` — the guest's own lock list — plus
     *   the zypper/rpm toolchain, which must never be removable regardless of
     *   whether the image bothered to lock it.
     */
    private suspend fun decorate(
        runtimeBinder: CommandExecutor,
        workspaceId: String,
        packages: List<InstalledPkg>
    ): List<InstalledPkg> {
        val updates = runtimeBinder.execCommand(workspaceId, "zypper --quiet list-updates")
            .takeIf { it.exitCode == 0 || it.exitCode == EXIT_NO_MATCH || it.exitCode == EXIT_ZYPP_ERROR }
            ?.let { ZypperOutputParser.parseUpdates(it.output) }
            .orEmpty()
        val locked = runtimeBinder.execCommand(workspaceId, "zypper --quiet locks")
            .takeIf { it.exitCode == 0 }
            ?.let { ZypperOutputParser.parseLocks(it.output) }
            .orEmpty()
        return packages.map { pkg ->
            pkg.copy(
                updateVersion = updates[pkg.name]?.takeIf { it != pkg.version },
                removable = pkg.name !in locked && pkg.name !in PROTECTED_NAMES
            )
        }
    }

    /**
     * Fill in sizes and summaries for the packages on the visible page using
     * `zypper info`, which is the only zypper command that reports a size.
     * Only the page is queried, not the whole result set, to keep one extra
     * command per page. Failure leaves the rows exactly as the search
     * returned them — never fabricated.
     */
    private suspend fun enrich(
        runtimeBinder: CommandExecutor,
        workspaceId: String,
        page: List<AvailablePkg>
    ): List<AvailablePkg> {
        if (page.isEmpty()) return page
        val names = page.map { it.name }.filter { PackageShell.isSafePackageName(it) }
        if (names.isEmpty()) return page
        val info = runtimeBinder.execCommand(
            workspaceId,
            "zypper --quiet info ${names.joinToString(" ")}"
        )
        if (info.exitCode != 0 && info.exitCode != EXIT_NO_MATCH && info.exitCode != EXIT_ZYPP_ERROR) return page
        val details = ZypperOutputParser.parseInfo(info.output)
        if (details.isEmpty()) return page
        return page.map { pkg ->
            val d = details[pkg.name] ?: return@map pkg
            pkg.copy(
                latestVersion = pkg.latestVersion.ifEmpty { d.version.orEmpty() },
                installedSizeBytes = pkg.installedSizeBytes ?: d.installedSizeBytes,
                repository = pkg.repository ?: d.repository,
                description = pkg.description ?: d.description,
                homepageUrl = d.homepageUrl
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
        /** `ZYPPER_EXIT_INF_CAP_NOT_FOUND` — "No matching items found." */
        const val EXIT_NO_MATCH = 104

        /**
         * `ZYPPER_EXIT_ERR_ZYPP` — raised for an unreachable/invalid
         * repository. zypper still prints whatever it could resolve locally,
         * so read-only queries parse the output instead of discarding it.
         */
        const val EXIT_ZYPP_ERROR = 106

        /**
         * The zypper/rpm toolchain. [decorate] reads the guest's own
         * `zypper locks` first; this set only covers an image that locked
         * nothing.
         */
        val PROTECTED_NAMES = setOf("zypper", "rpm", "libzypp", "glibc", "bash")
    }
}
