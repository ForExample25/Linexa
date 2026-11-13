package com.fem.linexa.service.installer

import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.launcher.LauncherApp
import com.fem.linexa.domain.packagemanager.PackageManagerDetector
import com.fem.linexa.domain.packagemanager.PackageManagerRegistry
import com.fem.linexa.domain.packagemanager.PackageProvider
import com.fem.linexa.domain.packagemanager.PackageShell
import com.fem.linexa.service.rootfs.WorkspaceRootFsMetadata
import com.fem.linexa.service.runtime.RuntimeBinder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * The single authoritative install-state probe shared by the App Launcher's
 * tracker and install coordinator.
 *
 * §1.92 gave the app one package-manager abstraction; this service is the one
 * place the launcher asks it "is this app installed?". It resolves the
 * workspace's actual manager through [PackageManagerDetector] (honouring the
 * rootfs declaration when present) and then uses
 * [PackageProvider.installedNames] — one authoritative query for the complete
 * installed set — instead of hardcoding `dpkg-query`. That distinction is what
 * makes absence measurable: a package missing from the complete set is
 * positively absent, whereas `dpkg-query -W <names>` prints nothing for an
 * unknown package and used to leave the launcher stuck on "Unknown state".
 *
 * ## Strictness
 *
 * Installed means **every** declared verification package is present. A
 * partial install (one of two packages present) is NOT installed, never
 * "installed enough". Removal verification mirrors this: every declared
 * package must be absent. Apps without a package source fall back to the
 * launch command's executable (`command -v`), and an app with neither source
 * stays [State.UNKNOWN] — no guessed state.
 *
 * The provider is cached per workspace (detection is a guest probe); the
 * installed-name set itself is never cached, so an install issued from another
 * surface is seen on the next refresh.
 */
@Singleton
class AppInstallProbe @Inject constructor(
    private val registry: PackageManagerRegistry,
    private val workspaceRootFsMetadata: WorkspaceRootFsMetadata
) {

    /** The measured state of one app. */
    enum class State { INSTALLED, NOT_INSTALLED, UNKNOWN }

    private val providerCache = ConcurrentHashMap<String, PackageProvider>()

    /**
     * The complete installed-package set for [workspaceId], or null when the
     * manager could not be resolved/queried. Null means "not measured", never
     * "nothing installed".
     */
    suspend fun installedNames(runtimeBinder: RuntimeBinder, workspaceId: String): Set<String>? {
        val provider = providerFor(runtimeBinder, workspaceId) ?: return null
        return try {
            provider.installedNames(runtimeBinder, workspaceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "installed-name probe failed in $workspaceId: ${e.message}")
            null
        }
    }

    /**
     * Measures [app] against the workspace's real package set, falling back to
     * its launch executables when no package verdict is available.
     */
    suspend fun measure(runtimeBinder: RuntimeBinder, workspaceId: String, app: LauncherApp): State {
        val packages = app.verificationPackages.filter(PackageShell::isSafePackageName)
        val installed = if (packages.isNotEmpty()) installedNames(runtimeBinder, workspaceId) else null
        val packagePresence = AppInstallMeasurements.presenceOf(installed, packages)
        if (packagePresence != AppInstallMeasurements.Presence.UNKNOWN) {
            return if (packagePresence == AppInstallMeasurements.Presence.PRESENT) {
                State.INSTALLED
            } else {
                State.NOT_INSTALLED
            }
        }
        return when (executablesPresent(runtimeBinder, workspaceId, app.verificationExecutables)) {
            true -> State.INSTALLED
            false -> State.NOT_INSTALLED
            null -> State.UNKNOWN
        }
    }

    /**
     * Whether EVERY declared executable resolves in [workspaceId]. Null when
     * there is no safe executable to probe or the probe itself failed.
     */
    suspend fun executablesPresent(
        runtimeBinder: RuntimeBinder,
        workspaceId: String,
        executables: List<String>
    ): Boolean? {
        val command = AppInstallMeasurements.executableProbeCommand(executables) ?: return null
        return try {
            runtimeBinder.execCommand(workspaceId, command).exitCode == 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "executable probe failed in $workspaceId: ${e.message}")
            null
        }
    }

    private suspend fun providerFor(runtimeBinder: RuntimeBinder, workspaceId: String): PackageProvider? {
        providerCache[workspaceId]?.let { return it }
        return try {
            val declared = workspaceRootFsMetadata.of(workspaceId)?.packageManager?.type
            val key = PackageManagerDetector.detect(runtimeBinder, workspaceId, declared) ?: return null
            registry.providerFor(key)?.also { providerCache[workspaceId] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "package-manager detection failed in $workspaceId: ${e.message}")
            null
        }
    }

    private companion object {
        const val TAG = "AppInstallProbe"
    }
}

/**
 * Pure verdict rules of [AppInstallProbe] (JVM-tested).
 *
 * Kept separate from the binder-facing service so the "all declared packages
 * present" contract and the executable fallback are testable without a guest.
 */
object AppInstallMeasurements {

    enum class Presence { PRESENT, ABSENT, UNKNOWN }

    /**
     * [Presence.PRESENT] only when [installedNames] is a trustworthy complete
     * set and every safe name is in it; [Presence.ABSENT] when the set is
     * trustworthy and at least one name is missing; [Presence.UNKNOWN] when
     * there is no safe name or the set could not be measured.
     */
    fun presenceOf(installedNames: Set<String>?, packages: List<String>): Presence {
        val safe = packages.filter(PackageShell::isSafePackageName)
        if (safe.isEmpty() || installedNames == null) return Presence.UNKNOWN
        return if (safe.all { it in installedNames }) Presence.PRESENT else Presence.ABSENT
    }

    /**
     * Builds the one-shot `command -v` probe for [executables], or null when
     * no name passes the shell-safety gate.
     *
     * Executable names come from catalog launch commands, so they are gated
     * exactly like package names before interpolation. The regex additionally
     * accepts absolute/relative paths (the catalog may write `/usr/bin/tool`)
     * but still excludes every shell metacharacter.
     */
    fun executableProbeCommand(executables: List<String>): String? {
        val safe = executables.asSequence()
            .map { it.trim() }
            .filter { isSafeExecutable(it) }
            .distinct()
            .toList()
        if (safe.isEmpty()) return null
        return safe.joinToString(" && ") { "command -v $it >/dev/null 2>&1" }
    }

    /** Shell-safe executable token: letters/digits plus `_ . / + -`, no leading dash. */
    fun isSafeExecutable(name: String): Boolean =
        name.isNotEmpty() &&
            !name.startsWith("-") &&
            EXECUTABLE_REGEX.matches(name)

    private val EXECUTABLE_REGEX = Regex("^[A-Za-z0-9_./+][A-Za-z0-9_./+\\-]*$")
}
