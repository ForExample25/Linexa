package com.fem.linexa.service.runtime.bootstrap

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths

/**
 * §1.57: Explicit BusyBox applet compatibility layer.
 *
 * tiny_container's bin dir exposes busybox applet symlinks (its termux
 * package runs `busybox --install`); Linexa originally relied on the same
 * mechanism via [BootstrapManager]'s `installBusyboxApplets()`, but
 * `busybox --install -s` is non-fatal by design and can silently produce
 * NOTHING on device — the observed failure: `Cannot run program
 * ".../bootstrap/bin/sed": error=2 No such file or directory` (§1.57).
 *
 * This layer creates the REQUIRED applet symlinks directly
 * (`binDir/<applet> -> busybox`, a relative target resolved at execve
 * time through `binDir/busybox` → `applib/lib__bin__busybox__.so` →
 * nativeLibraryDir, the §1.45 chain), idempotently, and repairs stale
 * real files and dangling/wrong links left by earlier layouts.
 *
 * Pure java.io/java.nio — no Android APIs — so the entire layer is
 * exercisable in JVM unit tests (`BusyboxAppletLayerTest`), including
 * real ProcessBuilder execution of the applet symlinks against a stub
 * busybox dispatcher.
 */
object BusyboxAppletLayer {

    /**
     * §1.57: Applets the app itself executes through `binDir`. The §1.56
     * fixup instrumentation found `binDir/sed` missing (error=2 ENOENT);
     * `id` is required by `StagedRootFsInstaller.runRootfsFixups`
     * (`id -un/-u/-g/-Gn/-G`) and was added to the requested set for that
     * reason — same busybox applet mechanism, and tiny_container executes
     * the identical `id` applet from its bin dir.
     *
     * §1.66: `ls`, `clear`, `whoami` added for the Management Terminal —
     * its host `/system/bin/sh` (mksh) has none of these as builtins and
     * its PATH starts with binDir, so the commands were `not found` when
     * the (silently-failing) `busybox --install -s` produced no links.
     * All three are compiled into the termux busybox 1.38.0 build.
     *
     * `gzip` and `xz` are REQUIRED for rootfs extraction, not for the
     * terminal: GNU tar does not decompress internally — for `tar -xf` it
     * detects the compression and spawns an external helper by bare name
     * (`gzip`, `xz`, `zstd`), resolved through PATH, which for
     * [BootstrapManager.processEnv] is binDir alone. `zstd` is a real
     * vendored binary (`BIN_ALIASES`), but `gzip`/`xz` existed only as a
     * side effect of `busybox --install -s` — the very mechanism this
     * layer exists to replace, because it silently produces nothing on
     * device (§1.57). Without them `RootfsExtractor` fails with `tar:
     * gzip: Cannot exec: No such file or directory`, and every distro in
     * `DistroManifest` ships `.tar.gz` or `.tar.xz` — none ship
     * `.tar.zst`. Both applets are compiled into the termux busybox
     * 1.38.0 build and were verified to satisfy tar 1.35's helper spawn
     * for both formats.
     */
    val REQUIRED_APPLETS: List<String> = listOf(
        "chmod", "sed", "id", "ls", "clear", "whoami",
        "gzip", "xz"
    )

    /**
     * The applet names GNU tar spawns as decompression helpers, mapped
     * from the archive format it detects. `zstd` is intentionally absent:
     * it ships as a real vendored binary rather than a busybox applet.
     * Kept as a named constant so the [REQUIRED_APPLETS] coupling is
     * explicit and testable rather than implied by two string literals.
     */
    val TAR_DECOMPRESSION_APPLETS: List<String> = listOf("gzip", "xz")

    /** §1.57: what one [installAppletLinks] pass did. */
    data class InstallResult(
        val created: List<String>,
        val repaired: List<String>,
        val kept: List<String>,
        val failed: List<Pair<String, Exception>>
    ) {
        /** True when every required applet is a symlink to `busybox`. */
        val allPresent: Boolean get() = failed.isEmpty()

        val summary: String
            get() = "created=${created.size} repaired=${repaired.size} kept=${kept.size} failed=${failed.size}" +
                (if (failed.isEmpty()) "" else " failed=[${failed.joinToString { it.first }}]")
    }

    /**
     * §1.57: Ensure every [REQUIRED_APPLETS] entry in [binDir] is a
     * symlink to [busyboxName] (relative — resolves within binDir, so it
     * keeps working if the app's filesDir moves between app updates).
     * Idempotent: an existing valid link is kept; a stale real file or a
     * symlink pointing anywhere else (including dangling) is replaced.
     * Never throws for per-link problems — they are collected in
     * [InstallResult.failed] so callers can log them.
     */
    fun installAppletLinks(binDir: File, busyboxName: String = "busybox"): InstallResult {
        val created = mutableListOf<String>()
        val repaired = mutableListOf<String>()
        val kept = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, Exception>>()

        if (!binDir.exists() && !binDir.mkdirs()) {
            REQUIRED_APPLETS.forEach {
                failed.add(it to IOException("binDir does not exist and could not be created: ${binDir.absolutePath}"))
            }
            return InstallResult(created, repaired, kept, failed)
        }

        for (applet in REQUIRED_APPLETS) {
            val link = File(binDir, applet)
            try {
                when {
                    existsNoFollow(link) && readLinkTarget(link) == busyboxName -> kept.add(applet)
                    existsNoFollow(link) -> {
                        if (!link.delete()) {
                            throw IOException("could not delete stale entry $applet in ${binDir.absolutePath}")
                        }
                        Files.createSymbolicLink(link.toPath(), Paths.get(busyboxName))
                        repaired.add(applet)
                    }
                    else -> {
                        Files.createSymbolicLink(link.toPath(), Paths.get(busyboxName))
                        created.add(applet)
                    }
                }
            } catch (e: Exception) {
                failed.add(applet to e)
            }
        }
        return InstallResult(created, repaired, kept, failed)
    }

    /**
     * §1.57: per-required-applet diagnostic lines: existence (NOFOLLOW —
     * a dangling link still shows as present), symlink target, validity
     * (points to [busyboxName]), the resolved real path through the whole
     * chain, and canExecute. Never throws.
     */
    fun appletDiagnostics(binDir: File, busyboxName: String = "busybox"): String = buildString {
        val busybox = File(binDir, busyboxName)
        appendLine("  busybox: exists=${existsNoFollow(busybox)} resolvesTo=${resolve(busybox)}")
        for (applet in REQUIRED_APPLETS) {
            val link = File(binDir, applet)
            val target = readLinkTarget(link)
            val valid = target == busyboxName
            appendLine(
                "  $applet: exists=${existsNoFollow(link)} isSymlink=${target != null} " +
                    "target=${target ?: "<not-symlink>"} valid=$valid " +
                    "resolvesTo=${resolve(link)} canExec=${try { link.canExecute() } catch (_: Exception) { false }}"
            )
        }
    }

    private fun existsNoFollow(file: File): Boolean = try {
        Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    } catch (_: Exception) {
        false
    }

    /** Symlink target string, or null when [file] is not a symlink (or unreadable). */
    private fun readLinkTarget(file: File): String? = try {
        Files.readSymbolicLink(file.toPath()).toString()
    } catch (_: Exception) {
        null
    }

    /** Full-chain real path, or a marker when unresolvable. */
    private fun resolve(file: File): String = try {
        file.toPath().toRealPath().toString()
    } catch (_: Exception) {
        "<unresolvable>"
    }
}
