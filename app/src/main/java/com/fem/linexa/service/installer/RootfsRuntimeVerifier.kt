package com.fem.linexa.service.installer

import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.packagemanager.PackageManagerDetector
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager
import com.fem.linexa.service.runtime.container.GuestIdentity
import com.fem.linexa.service.runtime.container.PRootLauncher
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §1.147 — the pre-activation smoke test: prove the extracted tree can actually
 * execute under Linexa's PRoot before it replaces a working rootfs.
 *
 * ## Why this runs in staging
 *
 * Every existing check before activation was static: required directories
 * exist, a shell path exists, the archive boundary is safe. None of them can
 * catch the failure users actually report — "the install completed but the
 * terminal exits immediately" — because that is an *execution* failure: a
 * wrong-architecture binary, a missing ELF interpreter, a musl/glibc mismatch,
 * a rootfs whose `/bin/sh` is a dangling `--link2symlink` artifact.
 *
 * The test runs the resolved profile's login shell through the exact
 * [PRootLauncher] argv/env the terminal will use, with the extracted tree as
 * the guest root. It then starts the declared package manager binary with
 * `--version` (a harmless, non-mutating invocation) so "the shell works but
 * `apt` is missing" is caught at install time too.
 *
 * A failure discards staging and leaves the previous live rootfs untouched.
 * That is the entire point: a broken import must never be promoted over a
 * working one.
 */
@Singleton
class RootfsRuntimeVerifier @Inject constructor(
    private val launcher: PRootLauncher,
    private val bootstrapManager: BootstrapManager
) {

    /**
     * Returns null when the staged rootfs boots, or a user-facing reason.
     *
     * @param staging the extracted, fixed-up tree (still `.staging-rootfs`).
     * @param analysis the resolved plan — supplies the declared shell and
     *   package manager, both of which are data from a validated profile.
     */
    suspend fun verify(
        staging: File,
        workspaceId: String,
        analysis: AnalyzeResult.Success
    ): String? {
        if (!staging.isDirectory) return "Staged rootfs is missing before the runtime smoke test"

        val shell = resolveShell(staging, analysis)
            ?: return "Runtime smoke test could not find a shell in the staged rootfs " +
                "(looked for ${analysis.shellCandidates.joinToString(", ") { "/$it" }})"

        val packageManager = analysis.packageManager?.let { key ->
            PackageManagerDetector.binaryFor(key)?.let { binary -> key to binary }
        }
        val script = buildScript(packageManager)

        val context = "distro=${analysis.identity.distroName} version=${analysis.identity.version} " +
            "arch=${analysis.identity.arch.label} profile=${analysis.profileId}"

        Logger.i(TAG, "pre-activation smoke test for $workspaceId: shell=$shell pm=${packageManager?.second ?: "<none>"} ($context)")
        val first = runOnce(staging, workspaceId, shell, script)
        if (first.exitCode == 0 && first.output.contains(MARKER)) {
            Logger.i(TAG, "pre-activation smoke test passed for $workspaceId ($context)")
            return null
        }

        // Same §1.50 fallback the extractor/runtime use: a known kernel seccomp
        // incompatibility must be retried once with PROOT_NO_SECCOMP=1 rather
        // than reported as a broken rootfs. Only a genuine signature retries.
        if (!BootstrapManager.needsNoSeccomp && bootstrapManager.isSeccompKernelBugFailure(first.output)) {
            Logger.w(
                TAG,
                "smoke test hit the known seccomp/kernel incompatibility for $workspaceId — " +
                    "retrying once with PROOT_NO_SECCOMP=1"
            )
            BootstrapManager.needsNoSeccomp = true
            val retry = runOnce(staging, workspaceId, shell, script)
            if (retry.exitCode == 0 && retry.output.contains(MARKER)) {
                Logger.i(TAG, "pre-activation smoke test passed on retry for $workspaceId ($context)")
                return null
            }
            return failure("retry", shell, packageManager?.second, retry, context)
        }

        return failure("first attempt", shell, packageManager?.second, first, context)
    }

    internal fun resolveShell(staging: File, analysis: AnalyzeResult.Success): String? {
        val candidates = buildList {
            analysis.loginShell?.let(::add)
            addAll(analysis.shellCandidates)
            addAll(listOf("bin/sh", "usr/bin/sh", "bin/bash", "usr/bin/bash", "bin/ash"))
        }.map { it.removePrefix("/") }.distinct()
        return candidates.firstOrNull { relative ->
            val file = File(staging, relative)
            file.exists() && (!file.isDirectory)
        }?.let { "/$it" }
    }

    /**
     * A tiny POSIX script: busybox ash, dash and bash all accept it. `set -e` is
     * deliberately absent — each failure prints its own diagnosis and exits
     * with a distinct code, which makes the returned reason specific.
     */
    internal fun buildScript(packageManager: Pair<String, String>?): String = buildString {
        append("printf '").append(MARKER).append("\\n'\n")
        if (packageManager != null) {
            val (key, binary) = packageManager
            append("command -v ").append(binary).append(" >/dev/null 2>&1\n")
            append("if [ $? -ne 0 ]; then echo 'linexa-smoke: ").append(key)
                .append(" binary ").append(binary).append(" not found'; exit 42; fi\n")
            append(binary).append(" --version >/dev/null 2>&1\n")
            append("if [ $? -ne 0 ]; then echo 'linexa-smoke: ").append(key)
                .append(" did not start'; exit 43; fi\n")
        }
        append("exit 0\n")
    }

    private fun failure(
        attempt: String,
        shell: String,
        packageManager: String?,
        result: RunResult,
        context: String
    ): String {
        val tail = result.output.trim().takeLast(800).ifBlank { "<no output>" }
        val detail = when {
            result.output.contains("linexa-smoke:") ->
                result.output.lineSequence().firstOrNull { it.contains("linexa-smoke:") }?.trim()

            result.exitCode == null -> "the runtime did not finish within ${TIMEOUT_SECONDS}s"
            else -> "exit ${result.exitCode}"
        }
        Logger.w(
            TAG,
            "pre-activation smoke test FAILED on $attempt: shell=$shell pm=${packageManager ?: "<none>"} " +
                "detail=$detail output=$tail ($context)"
        )
        return "Rootfs failed its pre-activation runtime smoke test ($detail). " +
            "Shell: $shell; package manager: ${packageManager ?: "none"}. Output: $tail ($context)"
    }

    private data class RunResult(val exitCode: Int?, val output: String)

    private fun runOnce(staging: File, workspaceId: String, shell: String, script: String): RunResult {
        val scope = "smoke-$workspaceId-${java.util.UUID.randomUUID()}"
        val tmpDir = bootstrapManager.scopedProotTmpDir(scope)
        try {
            val spec = launcher.buildLaunchSpec(
                rootfsPath = staging.absolutePath,
                command = listOf(shell, "-c", script),
                workingDirectory = "/",
                homeDirectory = "/",
                prootTmpDir = tmpDir.absolutePath,
                includeSharedStorage = false,
                identity = GuestIdentity.root("/", shell),
                shellPathForEnv = shell
            )

            val env = spec.env.toMutableMap()
            if (BootstrapManager.needsNoSeccomp) env += BootstrapManager.NO_SECCOMP_ENV

            val argv = listOf(spec.executable) + spec.args
            Logger.i(TAG, "smoke argv: ${argv.joinToString(" ")}")
            val process = try {
                ProcessBuilder(argv)
                    .redirectErrorStream(true)
                    .apply {
                        environment().clear()
                        environment().putAll(env)
                    }
                    .start()
            } catch (e: Exception) {
                return RunResult(null, "Could not start PRoot: ${e.message}")
            }

            val reader = Executors.newSingleThreadExecutor()
            val outputFuture = reader.submit<String> {
                process.inputStream.bufferedReader().use { it.readText().take(MAX_OUTPUT_CHARS) }
            }
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                reader.shutdownNow()
                return RunResult(null, "Timed out after ${TIMEOUT_SECONDS}s")
            }
            val output = try {
                outputFuture.get(5, TimeUnit.SECONDS)
            } catch (_: Exception) {
                ""
            }
            reader.shutdownNow()
            return RunResult(process.exitValue(), output)
        } finally {
            bootstrapManager.releaseProotTmpDir(scope)
        }
    }

    companion object {
        private const val TAG = "RootfsRuntimeVerifier"
        private const val MARKER = "linexa-smoke-ok"
        private const val TIMEOUT_SECONDS = 120L
        private const val MAX_OUTPUT_CHARS = 64 * 1024
    }
}
