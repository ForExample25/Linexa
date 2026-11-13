package com.fem.linexa.service.installer

import com.fem.linexa.core.logging.DiagnosticLogManager
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pass 3 of the staged installer — proot-distro-style user/group fixups
 * via the bundled busybox (§1.56). Extracted verbatim from
 * `StagedRootFsInstaller.runRootfsFixups` (Roadmap Item 2 — pure
 * responsibility extraction, no behavior change): every instrumented
 * command ([runBootstrapCommand]), account-file fingerprint
 * ([logAccountFileState]), `fixup.log` event append ([logFixupEvent]),
 * the §1.65 whitelist parsing ([isSafeAccountNameToken]/
 * [parseIdOutput]), and the §1.64/§1.65 pairing rules
 * ([groupFixupPairs]) are byte-for-byte the monolith's. The log TAG is
 * kept as "StagedRootFsInstaller" so every existing logcat line and
 * operator filter keeps its identity.
 */
@Singleton
class RootfsFixupService @Inject constructor(
    private val bootstrapManager: BootstrapManager,
    private val diagnosticLogManager: DiagnosticLogManager,
    /**
     * Transformation spec §19/§20: the host's live DNS servers seed the
     * staged rootfs's resolv.conf — the hardcoded `8.8.8.8`/`8.8.4.4`
     * literal pair is gone. Every workspace start re-provisions DNS from
     * the workspace's own configuration anyway (WorkspaceNetworkProvisioner),
     * so the seed is a convenience for the very first boot, drawn from the
     * same source the boot-time provisioning uses.
     */
    private val hostDnsProvider: com.fem.linexa.domain.network.HostDnsProvider,
    /**
     * `RFS-01`/`RFS-08`: the Android process identity seam. The primary gid
     * MUST come from Android (`Os.getgid()`), never from the host
     * `/etc/passwd` — Android does not guarantee an entry for an app UID.
     * The host passwd/group files are optional name lookups only.
     */
    private val identityProvider: ProcessIdentityProvider
) {

    /**
     * Post-extraction fixups, mirrored from tiny_container (which traces
     * them to proot-distro): make the account files writable by the app,
     * strip `aid_*` system users a base rootfs carries, then append the
     * app's own uid/username and supplementary groups so `id`, getpwnam
     * and group membership inside the guest match the PRoot-emulated
     * environment. Runs through the bundled busybox (absolute paths, no
     * shell involved — every argv is constructed, nothing is concatenated
     * into a script).
     *
     * §1.148: the chmod precedes the sed. It used to follow it, which failed
     * on any rootfs shipping mode-0000 account files (Fedora's `/etc/shadow`
     * and `/etc/gshadow`) — see [fixUpAccounts].
     *
     * §1.56: fully instrumented. Every account file is fingerprinted before
     * sed ([logAccountFileState]), and every command runs through
     * [runBootstrapCommand], which logs the resolved executable (symlink
     * chain), full argv, working directory, PATH/LD_LIBRARY_PATH/
     * PROOT_LOADER/PROOT_TMP_DIR, exit code, separate stdout/stderr and
     * elapsed time. Failure messages carry the exact rc + stderr instead
     * of a bare "sed fixup failed". Everything is also appended to the
     * per-workspace `fixup.log`.
     *
     * Returns null on success, or a user-meaningful failure message.
     *
     * §1.146: the two halves are now individually selectable, because they are
     * requested independently by a profile's
     * [com.fem.linexa.service.rootfs.profile.InstallStep] list —
     * [com.fem.linexa.service.rootfs.profile.InstallStep.ANDROID_ACCOUNT_FIXUP]
     * and [com.fem.linexa.service.rootfs.profile.InstallStep.SEED_RESOLV_CONF].
     * Both default to true so every existing caller and test keeps the
     * pre-§1.146 behaviour verbatim.
     */
    fun fixUp(
        staging: File,
        workspaceId: String,
        accountFixup: Boolean = true,
        seedResolvConf: Boolean = true
    ): String? {
        if (accountFixup) {
            fixUpAccounts(staging, workspaceId)?.let { return it }
        }
        if (seedResolvConf) {
            seedResolvConf(staging, workspaceId)
        }
        if (!accountFixup && !seedResolvConf) {
            logFixupEvent(workspaceId, "FIXUP SKIPPED: profile requested neither account fixup nor resolv.conf")
        }
        return null
    }

    /**
     * §1.27: seeds `/etc/resolv.conf` (proot-distro lineage) so the guest can
     * resolve names without depending on a rootfs-baked config (Alpine ships
     * none; Debian's default is the systemd-resolved stub 127.0.0.53, which is
     * unreachable under PRoot). Always replaced — predictable, and the archive
     * is user-supplied so a pre-baked resolv.conf can't be trusted anyway.
     *
     * §1.148: "always replaced" was only true for a regular file. Both Arch and
     * Fedora ship `/etc/resolv.conf` as a SYMLINK into systemd-resolved's
     * runtime dir — `/run/systemd/resolve/resolv.conf` (Arch) and
     * `/run/systemd/resolve/stub-resolv.conf` (Fedora) — and `/run` does not
     * exist on the Android host, so the link dangles. [File.writeText] follows
     * the link and tries to CREATE the target, which fails with
     * `ENOENT (No such file or directory)` and broke the install. The entry is
     * now unlinked first with [java.nio.file.Files.deleteIfExists], which
     * operates on the link itself and never follows it, so the symlink is
     * genuinely replaced by our regular file.
     */
    fun seedResolvConf(staging: File, workspaceId: String) {
        val resolvConf = File(File(staging, "etc"), "resolv.conf")
        resolvConf.parentFile?.mkdirs()
        // §1.148: unlink whatever is there (regular file, or a dangling symlink
        // to /run/systemd/resolve/...) WITHOUT following it. Best effort: if the
        // entry can't be removed, the write below still gets its chance to
        // report the real reason.
        try {
            java.nio.file.Files.deleteIfExists(resolvConf.toPath())
        } catch (e: Exception) {
            logFixupEvent(workspaceId, "§1.148: could not unlink existing resolv.conf (${e.javaClass.simpleName}: ${e.message}) — writing anyway")
        }
        // §19/§20: seed from the host's live resolver topology — never a
        // hardcoded provider literal. `runBlocking` here is bounded and safe:
        // the installer is already a blocking workflow (extraction/fixups run
        // on its own IO path), and reading LinkProperties is a fast,
        // failure-isolated call whose failure mode (empty list) is handled
        // below, not thrown.
        val servers = kotlinx.coroutines.runBlocking {
            runCatching { hostDnsProvider.dnsServers() }.getOrElse { emptyList() }
        }.take(3)
        if (servers.isEmpty()) {
            // No host DNS answer (offline install): leave NO file rather than
            // inventing a fallback resolver. The boot-time provisioning pass
            // writes resolv.conf on the first workspace start once the device
            // is online; a rootfs tarball's own resolver file is not trusted
            // for user-supplied archives (see the KDoc above).
            logFixupEvent(workspaceId, "FIXUP: resolv.conf not seeded — host DNS unavailable; boot-time provisioning will configure it")
            return
        }
        writeTextNoFollow(
            resolvConf,
            servers.joinToString(separator = "") { "nameserver $it\n" } + "# managed by Linexa\n",
            append = false
        )
        logFixupEvent(workspaceId, "FIXUP OK: resolv.conf seeded from host DNS (${servers.size} server(s))")
    }

    /** The account-file half of [fixUp]. Returns null on success. */
    private fun fixUpAccounts(staging: File, workspaceId: String): String? {
        val passwd = File(File(staging, "etc"), "passwd")
        val shadow = File(File(staging, "etc"), "shadow")
        val group = File(File(staging, "etc"), "group")
        val gshadow = File(File(staging, "etc"), "gshadow")

        val accountFiles = listOf(passwd, shadow, group, gshadow)

        // RFS-03: account files must be regular, non-symlink files that stay
        // inside staging. This check runs before any chmod/sed so a malicious
        // archive cannot turn `etc/passwd -> <outside victim>` into a host
        // chmod/write. `Files.isSymbolicLink` and the `NOFOLLOW_LINKS`
        // attribute calls below never follow the final path component.
        for (file in accountFiles) {
            val issue = validateAccountFilePath(staging, file)
            if (issue != null) return failFixup(workspaceId, issue)
        }

        for (file in accountFiles) {
            // §1.56: fingerprint the target *before* sed so a sed failure
            // can be correlated with the exact on-disk state.
            logAccountFileState(workspaceId, file, staging)
            if (noFollowExists(file)) {
                // §1.148: chmod FIRST, then sed. Fedora ships /etc/shadow and
                // /etc/gshadow as mode 0000 (`---------- root/root` in the
                // archive), so `sed -i` could not even open them and the
                // install died with
                // `sed: .../etc/shadow: Permission denied` (exit=1).
                // The chmod always succeeds — extraction makes the app the
                // owner, and an owner may always chmod its own file regardless
                // of the current bits — it was just running too late to help.
                // Distros whose shadow files are already owner-writable (Arch
                // 0600, Alpine/Ubuntu 0640, Debian 0664) are unaffected: they
                // reached sed fine before and reach it fine now.
                val chmodOk = chmodOwnerReadWrite(file)
                if (!chmodOk) {
                    return failFixup(workspaceId, "chmod fixup failed for ${file.name}: Kotlin chmodOwnerReadWrite returned false")
                }
                val sedOk = sedRemoveAidLines(file)
                if (!sedOk) {
                    return failFixup(workspaceId, "sed fixup failed for ${file.name}: Kotlin sedRemoveAidLines returned false")
                }
            }
        }

        // §1.64 / RFS-01: the uid and PRIMARY GID come from Android, not from
        // the host /etc/passwd. A missing host passwd (or a host passwd with
        // no app-UID entry — the normal Android case) only costs the optional
        // account name; it never fails the install.
        val identity = identityProvider.currentIdentity()
            ?: return failFixup(
                workspaceId,
                "identity resolution failed (RFS-01: Android did not report uid/gid)"
            )
        val uid = identity.uid.toString()
        val gid = identity.primaryGid.toString()
        // §1.65: the optional host account name passes the same strict
        // account-name whitelist as group names — a colon/whitespace-bearing
        // or otherwise unusable token (e.g. SELinux context text) falls back
        // to the numeric uid and is logged verbatim, never written to
        // passwd/shadow.
        val rawUserName = identity.accountName.orEmpty()
        val userName = rawUserName.takeIf { isSafeAccountNameToken(it) }
        if (rawUserName.isNotEmpty() && userName == null) {
            val notice = "§1.65: rejected unusable host account name ('$rawUserName'); using numeric uid"
            Logger.w(TAG, notice)
            logFixupEvent(workspaceId, notice)
        }
        val resolvedName = userName ?: uid

        if (noFollowExists(passwd)) {
            appendLine(passwd, "aid_$resolvedName:x:$uid:$gid:Linexa:/:/sbin/nologin")
        }
        if (noFollowExists(shadow)) {
            appendLine(shadow, "aid_$resolvedName:*:18446:0:99999:7:::")
        }

        // P0-3 fix: id -Gn outputs ALL group names space-separated on ONE line
        // (e.g. "root aid_external_storage aid_inet\n"), not one per line.
        // Using .lines() produces a single element containing the entire group
        // list, and .zip() then creates one malformed entry whose "name" is
        // the whole space-separated string. Fixed by splitting on whitespace.
        //
        // §1.64: Android may have no group-name database for the app uid
        // (0 names vs N numeric gids) — that is a valid environment, NOT an
        // error. Never require equal lengths: pairing is positional and any
        // name that is missing or unusable falls back to the numeric gid, so
        // the guest still receives a well-formed aid_* group with the correct
        // membership. Duplicate gids are collapsed (first wins) so a
        // redundant Android group list can't emit duplicate /etc/group lines.
        // RFS-01/LINUX-001: supplementary gids come from the live kernel set
        // (`/proc/self/status`), never a hard-coded empty list.
        // §1.65: both lists go through a strict whitelist — account-name
        // charset for names, digits-only for gids. Nothing diagnostic
        // (SELinux context text, error fragments, etc.) can reach the name or
        // gid fields of /etc/group. Rejected tokens are logged verbatim so
        // the on-device leak is provable, not papered over.
        val (groupNames, groupIds) = groupInfoFrom(identity)
        val idGnRaw = groupNames.joinToString(" ")
        val idGRaw = groupIds.joinToString(" ")
        val (keptNames, rejectedNames) = parseIdOutput(idGnRaw, numericOnly = false)
        val (keptIds, rejectedGids) = parseIdOutput(idGRaw, numericOnly = true)
        if (rejectedNames.isNotEmpty() || rejectedGids.isNotEmpty()) {
            val notice = "§1.65: rejected non-account tokens from id output (not written to /etc/group): " +
                buildList {
                    if (rejectedGids.isNotEmpty()) add("id -G='${idGRaw.trim()}' -> rejected: ${rejectedGids.joinToString(" ")}")
                    if (rejectedNames.isNotEmpty()) add("id -Gn='${idGnRaw.trim()}' -> rejected: ${rejectedNames.joinToString(" ")}")
                }.joinToString(" | ")
            Logger.w(TAG, notice)
            logFixupEvent(workspaceId, notice)
        }
        groupFixupPairs(keptNames, keptIds).forEach { (name, id) ->
            if (noFollowExists(group)) {
                appendLine(group, "aid_$name:x:$id:root,aid_$resolvedName")
            }
            if (noFollowExists(gshadow)) {
                appendLine(gshadow, "aid_$name:*::root,aid_$resolvedName")
            }
        }

        // P0-3 post-write validation (TDD-1): re-read /etc/group and verify
        // every aid_* line has exactly 4 colon-delimited fields and a
        // whitespace-free group name. This catches malformed entries that the
        // old .lines()/.zip() bug would have produced.
        if (noFollowExists(group)) {
            val groupLines = readLinesNoFollow(group)
            val badLines = groupLines.filter { line ->
                line.startsWith("aid_") && (
                    line.count { it == ':' } != 3 ||
                    line.substringBefore(":").contains(Regex("\\s"))
                )
            }
            if (badLines.isNotEmpty()) {
                return failFixup(workspaceId,
                    "Malformed /etc/group entries after fixup: ${badLines.take(3)}")
            }
        }

        // §1.146: resolv.conf seeding moved to [seedResolvConf] so a profile can
        // request it independently of the account fixup. The default call path
        // still performs both, in this same order.
        logFixupEvent(workspaceId, "FIXUP OK: passwd/shadow/group/gshadow updated")
        return null
    }

    /**
     * RFS-03: rejects an account file that is a symlink, a non-regular file,
     * or a path whose real location leaves the staging rootfs. Runs before
     * any chmod/sed. `NOFOLLOW_LINKS` means the check itself never follows
     * the final component, so a link cannot redirect the check.
     */
    private fun validateAccountFilePath(staging: File, file: File): String? {
        val path = file.toPath()
        if (!noFollowExists(file)) return null
        if (Files.isSymbolicLink(path)) {
            return "account fixup refused: etc/${file.name} is a symlink (RFS-03 no-follow policy)"
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return "account fixup refused: etc/${file.name} is not a regular file"
        }
        val realRoot = try {
            staging.toPath().toRealPath()
        } catch (_: Exception) {
            staging.toPath().toAbsolutePath().normalize()
        }
        val realPath = try {
            path.toRealPath()
        } catch (e: Exception) {
            return "account fixup refused: etc/${file.name} cannot be resolved (${e.javaClass.simpleName})"
        }
        if (!realPath.startsWith(realRoot)) {
            return "account fixup refused: etc/${file.name} resolves outside the rootfs staging directory"
        }
        return null
    }

    private fun noFollowExists(file: File): Boolean = try {
        Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    } catch (_: Exception) {
        false
    }

    /**
     * Reads an account file without following a final symlink. `newByteChannel`
     * with [LinkOption.NOFOLLOW_LINKS] maps to `O_NOFOLLOW` on Linux/Android;
     * if the path was swapped for a link after the pre-check, the open fails
     * instead of reading the linked target.
     */
    private fun readLinesNoFollow(file: File): List<String> {
        val options: Set<OpenOption> = setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        Files.newByteChannel(file.toPath(), options).use { channel ->
            return Channels.newInputStream(channel)
                .bufferedReader(StandardCharsets.UTF_8)
                .readLines()
        }
    }

    /** Bounded, no-follow prefix read used by the account-file fingerprint. */
    private fun readPrefixNoFollow(file: File, maxBytes: Int): String {
        val options: Set<OpenOption> = setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        Files.newByteChannel(file.toPath(), options).use { channel ->
            val buffer = ByteBuffer.allocate(maxBytes)
            var total = 0
            while (buffer.hasRemaining()) {
                val read = channel.read(buffer)
                if (read <= 0) break
                total += read
            }
            return String(buffer.array(), 0, total, StandardCharsets.UTF_8)
        }
    }

    /**
     * Writes [text] without following a final symlink. [append] keeps the
     * existing contents; otherwise the file is truncated. CREATE is included
     * because seedResolvConf may create a missing regular file.
     */
    private fun writeTextNoFollow(file: File, text: String, append: Boolean) {
        val options: Set<OpenOption> = if (append) {
            setOf(
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
                StandardOpenOption.CREATE,
                LinkOption.NOFOLLOW_LINKS
            )
        } else {
            setOf(
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.CREATE,
                LinkOption.NOFOLLOW_LINKS
            )
        }
        Files.newByteChannel(file.toPath(), options).use { channel ->
            val buffer = ByteBuffer.wrap(text.toByteArray(StandardCharsets.UTF_8))
            while (buffer.hasRemaining()) channel.write(buffer)
        }
    }

    /**
     * §1.56-Kotlin: Replace `chmod u+rw <file>` with native Kotlin/JVM API.
     * Returns true on success, false on failure. Mirrors busybox behavior:
     * only adds owner read/write, does not touch other bits. `NOFOLLOW_LINKS`
     * keeps the operation on the account file itself (RFS-03).
     */
    private fun chmodOwnerReadWrite(file: File): Boolean {
        return try {
            if (!noFollowExists(file)) {
                Logger.w(TAG, "chmodOwnerReadWrite: file does not exist: ${file.absolutePath}")
                false
            } else {
                // Use java.nio.file for POSIX permissions (Linux/Android)
                val path = file.toPath()
                // `unix:mode` has no lchmod equivalent on Linux: setting it
                // with NOFOLLOW_LINKS would try to chmod the link itself and
                // fail. The account-file pre-check already rejected symlinks;
                // re-check immediately before the follow, then set the mode
                // on the regular file.
                if (Files.isSymbolicLink(path)) {
                    Logger.e(TAG, "chmodOwnerReadWrite refused symlink: ${file.absolutePath}")
                    return false
                }
                val perms = (Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS) as Number).toInt()
                // Add owner read (0400) and owner write (0200) = 0600 (octal) = 384 decimal
                val newPerms = perms or 384
                Files.setAttribute(path, "unix:mode", newPerms)
                Logger.i(TAG, "chmodOwnerReadWrite ok: ${file.absolutePath} mode=${String.format("0%o", perms)} -> ${String.format("0%o", newPerms)}")
                true
            }
        } catch (e: Exception) {
            Logger.e(TAG, "chmodOwnerReadWrite failed for ${file.absolutePath}: ${e.message}", e)
            false
        }
    }

    /**
     * §1.56-Kotlin: Replace `sed -i '/^aid_/d' <file>` with native Kotlin.
     * Removes all lines starting with "aid_" from the file. Reads and writes
     * with NOFOLLOW so an account-file symlink can never redirect the edit.
     * Returns true on success, false on failure.
     */
    private fun sedRemoveAidLines(file: File): Boolean {
        return try {
            if (!noFollowExists(file)) {
                Logger.w(TAG, "sedRemoveAidLines: file does not exist: ${file.absolutePath}")
                false
            } else {
                val lines = readLinesNoFollow(file)
                val filtered = lines.filter { !it.startsWith("aid_") }
                if (filtered.size == lines.size) {
                    Logger.i(TAG, "sedRemoveAidLines: no aid_ lines to remove in ${file.absolutePath}")
                    true
                } else {
                    writeTextNoFollow(file, filtered.joinToString("\n") + "\n", append = false)
                    Logger.i(TAG, "sedRemoveAidLines ok: ${file.absolutePath} removed ${lines.size - filtered.size} aid_ line(s)")
                    true
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "sedRemoveAidLines failed for ${file.absolutePath}: ${e.message}", e)
            false
        }
    }

    /**
     * §1.64 / RFS-01: builds the positional name/gid lists from the resolved
     * Android identity. The primary group comes first; supplementary gids
     * follow in kernel order, de-duplicated, with the primary gid included
     * exactly once. A missing host group name falls back to the numeric gid,
     * which is always valid in `/etc/group`.
     */
    private fun groupInfoFrom(identity: ProcessIdentity): Pair<List<String>, List<String>> {
        val names = mutableListOf<String>()
        val ids = mutableListOf<String>()

        fun add(gid: Int) {
            ids.add(gid.toString())
            names.add(identity.groupNamesByGid[gid] ?: gid.toString())
        }

        add(identity.primaryGid)
        identity.supplementaryGids.distinct()
            .filter { it != identity.primaryGid }
            .forEach(::add)
        return names to ids
    }

    /**
     * §1.56: runs one fixup command (a bundled busybox applet via
     * [BootstrapManager.resolveBinary] — sed, chmod, id), capturing
     * everything the fixup forensics need: resolved executable (symlink
     * chain), full argv, working directory, PATH/LD_LIBRARY_PATH/
     * PROOT_LOADER/PROOT_TMP_DIR, exit code, stdout, stderr (kept
     * SEPARATE), and elapsed time. Extends the §1.52/§1.53 diagnostics —
     * nothing is removed. On any failure the returned result carries the
     * exact rc + output so callers surface the real reason.
     *
     * Tiny Container parity (§1.56): these commands run OUTSIDE proot, as
     * plain host processes on host absolute paths, exactly like TC's
     * `$BIN_DIR/sed -i '/^aid_/d' $CONTAINER_DIR/etc/passwd` shell lines.
     * TC's env differs (PATH=$BIN_DIR:$PATH, LD_LIBRARY_PATH prepend,
     * PROOT_TMP_DIR set, plus the terminal's HOME/PWD/TERM/TMPDIR); ours is
     * the closed [BootstrapManager.processEnv]. Both use the busybox sed
     * applet; neither uses GNU sed; neither mounts anything extra.
     */
    private fun runBootstrapCommand(args: List<String>, workspaceId: String): BootstrapCommandResult {
        val startedAt = System.currentTimeMillis()
        val env = bootstrapManager.processEnv()
        val exe = bootstrapManager.resolveBinary(args.first())

        // ── §1.56 FORENSIC: command identity (executable + argv + cwd + env)
        val symlinkTarget = try {
            java.nio.file.Files.readSymbolicLink(exe.toPath()).toString()
        } catch (_: Exception) { "" }
        val realPath = try { exe.toPath().toRealPath().toString() } catch (_: Exception) { "<unresolvable>" }
        Logger.i(TAG, "=== §1.56 FIXUP FORENSIC: ${args.first()} ===")
        Logger.i(TAG, "  exe = ${exe.absolutePath} exists=${exe.exists()} isFile=${exe.isFile} canExec=${exe.canExecute()} symlinkTarget=${if (symlinkTarget.isEmpty()) "<not-symlink>" else symlinkTarget} realPath=$realPath")
        args.forEachIndexed { i, arg ->
            val truncated = if (arg.length > 192) " (${arg.length} chars, truncated)" else ""
            Logger.i(TAG, "  argv[$i] = ${arg.take(192)}$truncated")
        }
        Logger.i(TAG, "  cwd = ${File(".").absolutePath}  user.dir=${System.getProperty("user.dir") ?: "<unset>"}")
        Logger.i(TAG, "  env: PATH=${env["PATH"] ?: "<unset>"}")
        Logger.i(TAG, "  env: LD_LIBRARY_PATH=${env["LD_LIBRARY_PATH"] ?: "<unset>"}")
        Logger.i(TAG, "  env: PROOT_LOADER=${env["PROOT_LOADER"] ?: "<unset>"}")
        Logger.i(TAG, "  env: PROOT_TMP_DIR=${env["PROOT_TMP_DIR"] ?: "<unset>"} (processEnv() never carries it — per-invocation scoped, §1.48)")
        Logger.i(TAG, "  env: TMPDIR=${env["TMPDIR"] ?: "<unset>"} HOME=${env["HOME"] ?: "<unset>"} PWD=${env["PWD"] ?: "<unset>"} TERM=${env["TERM"] ?: "<unset>"} USER=${env["USER"] ?: "<unset>"}")
        Logger.i(TAG, "  env keys=${env.size}: ${env.keys.sorted().joinToString(", ")}")

        return try {
            val process = ProcessBuilder(listOf(exe.absolutePath) + args.drop(1))
                .apply {
                    // §1.49: full-replacement env from the single source.
                    environment().clear()
                    environment().putAll(env)
                }
                .start()
            // §1.56: stdout and stderr are drained SEPARATELY (two
            // background threads, each into a bounded tail) so a busybox
            // error like `sed: <path>: Permission denied` is preserved
            // verbatim and a pipe-buffer deadlock is impossible.
            val stdoutTail = BoundedOutputTail()
            val stderrTail = BoundedOutputTail()
            val stdoutDrain = Thread {
                process.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) { val n = input.read(buffer); if (n < 0) break; stdoutTail.feed(buffer, n) }
                }
            }.apply { isDaemon = true; start() }
            val stderrDrain = Thread {
                process.errorStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) { val n = input.read(buffer); if (n < 0) break; stderrTail.feed(buffer, n) }
                }
            }.apply { isDaemon = true; start() }

            val completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            stdoutDrain.join(5000)
            stderrDrain.join(5000)
            val elapsed = System.currentTimeMillis() - startedAt
            if (!completed) {
                process.destroyForcibly()
                val result = BootstrapCommandResult(
                    success = false,
                    stdout = stdoutTail.tailText(),
                    stderr = stderrTail.tailText(),
                    exitCode = null,
                    elapsedMs = elapsed,
                    timedOut = true
                )
                Logger.e(TAG, "fixup command TIMED OUT after $COMMAND_TIMEOUT_SECONDS s: ${args.joinToString(" ")}")
                logFixupEvent(workspaceId, "TIMEOUT: ${args.joinToString(" ")}")
                return result
            }
            val exitCode = process.exitValue()
            val stdout = stdoutTail.tailText()
            val stderr = stderrTail.tailText()
            if (exitCode != 0) {
                Logger.e(TAG, "fixup command FAILED exit=$exitCode elapsed=${elapsed}ms args=${args.joinToString(" ")}")
                Logger.e(TAG, "  stdout: ${stdout.ifEmpty { "<empty>" }}")
                Logger.e(TAG, "  stderr: ${stderr.ifEmpty { "<empty>" }}")
                logFixupEvent(workspaceId, "FAILED exit=$exitCode elapsed=${elapsed}ms cmd=${args.joinToString(" ")} stderr='${stderr.take(512)}'")
            } else {
                Logger.i(TAG, "fixup command ok exit=0 elapsed=${elapsed}ms args=${args.joinToString(" ")} stderr=${stderr.ifEmpty { "<empty>" }}")
                logFixupEvent(workspaceId, "ok exit=0 elapsed=${elapsed}ms cmd=${args.joinToString(" ")}")
            }
            BootstrapCommandResult(
                success = exitCode == 0,
                stdout = stdout,
                stderr = stderr,
                exitCode = exitCode,
                elapsedMs = elapsed
            )
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startedAt
            Logger.e(TAG, "fixup command START FAILED (${args.joinToString(" ")}): ${e.message} | exe=${exe.absolutePath} exists=${exe.exists()} canExec=${exe.canExecute()}", e)
            BootstrapCommandResult(
                success = false,
                stdout = "",
                stderr = "",
                exitCode = null,
                elapsedMs = elapsed,
                startError = e.toString()
            )
        }
    }

    /**
     * §1.56: pre-command fingerprint of an account file so a sed failure
     * can be correlated with the exact on-disk state: exists (both follow
     * and NOFOLLOW — a dangling symlink is visible), type, symlink target,
     * canonical path, size, permission bits, owner, and whether it lies
     * inside the extracted rootfs. Never throws — a metadata error becomes
     * a field, not a crash. Always logged to the persistent `fixup.log`.
     */
    private fun logAccountFileState(workspaceId: String, file: File, staging: File) {
        val followsExists = file.exists()
        val existsNoFollow = noFollowExists(file)
        val symlinkTarget = try {
            java.nio.file.Files.readSymbolicLink(file.toPath()).toString()
        } catch (_: Exception) { "" }
        val canonical = try { file.toPath().toRealPath().toString() } catch (_: Exception) { "<unresolvable>" }
        val stagingCanonical = try { staging.toPath().toRealPath().toString() } catch (_: Exception) { staging.absolutePath }
        val inRootfs = canonical.startsWith(stagingCanonical)
        val type = when {
            Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS) -> "dir"
            Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) -> "file"
            else -> "-"
        }
        val mode = try {
            val path = if (canonical != "<unresolvable>") java.nio.file.Paths.get(canonical) else file.toPath()
            String.format("0%o", (java.nio.file.Files.getAttribute(path, "unix:mode") as Number).toInt())
        } catch (_: Exception) { "<unavailable>" }
        val owner = try { java.nio.file.Files.getOwner(file.toPath()).name } catch (_: Exception) { "<unavailable>" }
        val preview = try {
            val text = readPrefixNoFollow(file, 128)
            if (text.isEmpty()) "<empty>" else text.replace("\n", "\\n").replace("\r", "\\r").take(160)
        } catch (_: Exception) { "<unreadable>" }
        val summary = "  ${file.name}: followsExists=$followsExists noFollowExists=$existsNoFollow type=$type symlinkTarget=${if (symlinkTarget.isEmpty()) "<not-symlink>" else symlinkTarget} canonical=$canonical length=${file.length()} mode=$mode owner=$owner inRootfs=$inRootfs readable=${file.canRead()} writable=${file.canWrite()} preview='$preview'"
        Logger.i(TAG, "=== §1.56 FIXUP TARGET: ${file.absolutePath} ===")
        Logger.i(TAG, summary)
        logFixupEvent(workspaceId, "target: ${file.absolutePath}\n$summary")
    }

    /** §1.56: append one fixup-stage line to the per-workspace `fixup.log`. */
    private fun logFixupEvent(workspaceId: String, content: String) {
        try {
            diagnosticLogManager.appendLog(workspaceId, "fixup.log", content)
        } catch (_: Exception) {
            // best effort — never fail the install for logging
        }
    }

    /** §1.56: log a fixup failure (Logger + `fixup.log`) and return it as the install failure message. */
    private fun failFixup(workspaceId: String, message: String): String {
        Logger.e(TAG, "FIXUP FAILED: $message")
        logFixupEvent(workspaceId, "FIXUP FAILED: $message")
        return message
    }

    /**
     * §1.56: outcome of one instrumented fixup command. [stderr] stays
     * separate from [stdout] so a busybox error such as
     * `sed: /data/.../etc/passwd: Permission denied` is preserved verbatim.
     */
    private data class BootstrapCommandResult(
        val success: Boolean,
        val stdout: String,
        val stderr: String,
        val exitCode: Int?,
        val elapsedMs: Long,
        val timedOut: Boolean = false,
        val startError: String? = null
    ) {
        /** Compact but complete failure description for surfacing to the UI. */
        fun describeFailure(argv: List<String>): String = buildString {
            append("exit=${exitCode?.toString() ?: if (timedOut) "timeout" else "start-failed"}")
            if (startError != null) append(" startError='$startError'")
            append(" cmd=$argv")
            if (stderr.isNotBlank()) append(" stderr='${stderr.take(512)}'")
            if (stdout.isNotBlank()) append(" stdout='${stdout.take(256)}'")
            append(" elapsed=${elapsedMs}ms")
        }
    }

    private fun appendLine(file: File, line: String) {
        try {
            // RFS-03: APPEND + CREATE + NOFOLLOW. A symlink swapped in after
            // the regular-file check fails the open instead of receiving the
            // guest account line at its host-side target.
            writeTextNoFollow(file, "$line\n", append = true)
        } catch (e: Exception) {
            // F16 fix (2026-09-02): a swallowed append used to let fixUp
            // report "FIXUP OK" while the guest account/group DB silently
            // missed the entry. Surface the failure to the caller.
            Logger.e(TAG, "Could not append to ${file.name}", e)
            throw IllegalStateException("failed to append to ${file.name}: ${e.message}", e)
        }
    }

    companion object {
        /**
         * Deliberately kept as "StagedRootFsInstaller": this class is the
         * verbatim extraction of the monolith's Pass 3, and every existing
         * forensic log line (plus any operator logcat filter) keys on this
         * tag. Changing it would silently re-label diagnostics.
         */
        private const val TAG = "StagedRootFsInstaller"

        /** Maximum time in seconds to wait for a single bootstrap command (sed, chmod, id, etc.). */
        private const val COMMAND_TIMEOUT_SECONDS = 30L
    }
}

/**
 * §1.65: whitelist check for one account-name token (user/group name) that
 * may appear in /etc/passwd or /etc/group. POSIX account-name charset only
 * ([A-Za-z0-9_.-], no leading '-', 1..32 chars): rejects colons (field
 * separators), whitespace, slashes, control characters — and therefore any
 * SELinux context string (u:r:untrusted_app:s0:...), which by definition
 * contains colons.
 */
internal fun isSafeAccountNameToken(token: String): Boolean =
    token.length in 1..32 && token.first() != '-' && ACCOUNT_NAME_REGEX.matches(token)

private val ACCOUNT_NAME_REGEX = Regex("[a-zA-Z0-9_.][a-zA-Z0-9_.-]*")

/**
 * §1.65: tokenizes one `id` output line and keeps only whitelisted tokens.
 * [numericOnly] is true for `id -G` (a gid field must be digits-only or the
 * /etc/group line is malformed) and false for `id -Gn` (account names, plus
 * the bare numbers busybox prints when a name can't be resolved). Returns
 * (kept tokens, rejected tokens) — rejected tokens are logged by the caller
 * and never reach any generated file.
 */
internal fun parseIdOutput(raw: String, numericOnly: Boolean): Pair<List<String>, List<String>> {
    val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val kept = ArrayList<String>(tokens.size)
    val rejected = ArrayList<String>(tokens.size)
    for (token in tokens) {
        val ok = if (numericOnly) token.all { it.isDigit() } else isSafeAccountNameToken(token)
        if (ok) kept.add(token) else rejected.add(token)
    }
    return kept to rejected
}

/**
 * §1.64: pairs `id -Gn` group names with `id -G` numeric GIDs for the
 * Pass 3 account fixup, WITHOUT requiring equal list lengths.
 *
 * On Android, busybox `id -Gn` routinely prints NO group names (the
 * device has no group name database for the app uid — 0 names vs N
 * numeric gids). That is a valid environment, not an error. The Linux
 * `id` contract is that `-Gn` and `-G` enumerate groups in the SAME
 * order, so pairing is positional; a name that is missing at an index
 * (or present but unusable: empty, or failing the §1.65 account-name
 * whitelist — colons, whitespace, or any other diagnostic/context text)
 * falls back to the numeric gid again, so the guest still gets a
 * well-formed `aid_*` group entry with correct membership. Duplicate
 * gids are collapsed (first occurrence wins) so a redundant Android
 * group list can't emit duplicate /etc/group lines. (If junk tokens were
 * dropped from either list by the §1.65 whitelist, positions can skew —
 * the file stays well-formed; names are cosmetic.)
 *
 * On a normal Linux host where both lists exist and match, this is
 * exactly the old `names.zip(ids)` behavior.
 */
internal fun groupFixupPairs(groupNames: List<String>, groupIds: List<String>): List<Pair<String, String>> {
    val seenGids = LinkedHashSet<String>()
    return groupIds.mapIndexedNotNull { index, gid ->
        if (!seenGids.add(gid)) return@mapIndexedNotNull null
        val name = groupNames.getOrNull(index)
        if (name == null || !isSafeAccountNameToken(name)) {
            gid to gid
        } else {
            name to gid
        }
    }
}