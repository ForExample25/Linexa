package com.fem.linexa.service.runtime.container

import java.io.File

/**
 * Issue #1 (2026-09-03): the resolved Linux account an interactive session
 * runs as — the thing `proot --change-id=<uid>:<gid>`, `USER`, `LOGNAME`,
 * `HOME`, `SHELL` and the shell prompt (`\u@\h:\w\$`) are all derived from.
 *
 * ## Why this exists as a model
 *
 * The guest used to launch EVERYTHING through PRoot `-0` (emulated root),
 * with `USER=root`/`HOME=/root`, and only the working directory pretended a
 * user environment existed. The requirement is the opposite: normal
 * terminal/desktop usage must actually run as the `linexa` account —
 * `linexa@localhost:~$` — while root emulation (`-0`) stays reserved for
 * the operations that need it (rootfs initialization, package management,
 * system repair, app-initiated privileged operations).
 *
 * `id -u` / `whoami` / the bash prompt all resolve through `/etc/passwd`,
 * so the account must really exist there with the uid the launcher fakes.
 * [GuestRootfsInitializer.ensureGuestUser] guarantees that (creating the
 * account when the rootfs lacks it); this class is the pure read/parse
 * side, JVM-testable, that resolves the account from the account files.
 *
 * All parsing is deliberately tolerant: a malformed or missing account
 * database never blocks a launch — the caller falls back to the root
 * identity, which is the pre-change behavior.
 */
data class GuestIdentity(
    /** Account name as it must appear in `USER`/`LOGNAME` and the prompt. */
    val name: String,
    /** The uid `--change-id` fakes; 0 for the root identity. */
    val uid: Int,
    /** The primary gid `--change-id` fakes. */
    val gid: Int,
    /** Account home — `HOME` for the session, absolute, no trailing slash. */
    val home: String,
    /** Account login shell (from passwd); used for `SHELL` and the launch argv. */
    val shell: String,
    /** True for the emulated-root identity (launched with `-0`). */
    val isRoot: Boolean
) {
    /** Proot identity flag for this account: `-0` for root, `--change-id=u:g` otherwise. */
    val prootIdentityArgs: List<String>
        get() = if (isRoot) listOf("-0") else listOf("--change-id=$uid:$gid")

    companion object {
        const val DEFAULT_USER_NAME = "linexa"
        const val DEFAULT_USER_HOME = "/home/linexa"
        const val DEFAULT_USER_SHELL = "/bin/bash"
        const val DEFAULT_USER_GECOS = "Linexa User"
        const val ROOT_HOME = "/root"
        const val ROOT_SHELL = "/bin/bash"

        /** The emulated-root identity (pre-change launch behavior). */
        fun root(home: String = ROOT_HOME, shell: String = ROOT_SHELL) = GuestIdentity(
            name = "root",
            uid = 0,
            gid = 0,
            home = home,
            shell = shell,
            isRoot = true
        )

        // ── /etc/passwd + /etc/group reading (host-side, JVM-pure) ────────

        /**
         * One passwd line: `name:x:uid:gid:gecos:home:shell`. Returns null
         * for malformed lines instead of throwing — a rootfs with a broken
         * entry must not take the whole account resolution down.
         */
        fun parsePasswdLine(line: String): PasswdEntry? {
            val f = line.split(":")
            if (f.size < 7) return null
            val uid = f[2].trim().toIntOrNull() ?: return null
            val gid = f[3].trim().toIntOrNull() ?: return null
            if (f[0].isEmpty()) return null
            return PasswdEntry(
                name = f[0],
                uid = uid,
                gid = gid,
                gecos = f[4],
                home = f[5].ifEmpty { "/" },
                shell = f[6]
            )
        }

        /** One group line: `name:x:gid:members`. */
        fun parseGroupLine(line: String): GroupEntry? {
            val f = line.split(":")
            if (f.size < 3) return null
            val gid = f[2].trim().toIntOrNull() ?: return null
            if (f[0].isEmpty()) return null
            return GroupEntry(
                name = f[0],
                gid = gid,
                members = f.getOrNull(3)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?: emptyList()
            )
        }

        fun readPasswd(rootfs: File): List<PasswdEntry> {
            val file = File(rootfs, "etc/passwd")
            if (!file.isFile) return emptyList()
            return try {
                file.readLines().mapNotNull { parsePasswdLine(it) }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun readGroup(rootfs: File): List<GroupEntry> {
            val file = File(rootfs, "etc/group")
            if (!file.isFile) return emptyList()
            return try {
                file.readLines().mapNotNull { parseGroupLine(it) }
            } catch (_: Exception) {
                emptyList()
            }
        }

        /**
         * Resolves [userName] in [rootfs]'s passwd. When the account exists
         * its own record wins (home/shell included) — the declaration in
         * metadata picked the NAME, the account database owns the details.
         */
        fun resolveNamedUser(rootfs: File, userName: String): GuestIdentity? {
            val entry = readPasswd(rootfs).firstOrNull { it.name == userName } ?: return null
            return GuestIdentity(
                name = entry.name,
                uid = entry.uid,
                gid = entry.gid,
                home = entry.home.trimEnd('/').ifEmpty { "/" },
                shell = entry.shell.ifBlank { DEFAULT_USER_SHELL },
                isRoot = entry.uid == 0
            )
        }

        /**
         * Finds the first free uid (and matching personal-group gid) at or
         * above [startAt], skipping every uid/gid already taken in the
         * account databases. Imported images sometimes ship a stock
         * uid-1000 user (Ubuntu cloud images carry `ubuntu`), so the
         * created Linexa account moves to the next free pair instead of
         * colliding.
         */
        fun firstFreeUidGid(rootfs: File, startAt: Int = 1000): Int {
            val takenUids = readPasswd(rootfs).map { it.uid }.toSet()
            val takenGids = readGroup(rootfs).map { it.gid }.toSet()
            var candidate = startAt
            while (candidate in takenUids || candidate in takenGids) candidate++
            return candidate
        }
    }

    /** Parsed `/etc/passwd` record. */
    data class PasswdEntry(
        val name: String,
        val uid: Int,
        val gid: Int,
        val gecos: String,
        val home: String,
        val shell: String
    )

    /** Parsed `/etc/group` record. */
    data class GroupEntry(val name: String, val gid: Int, val members: List<String>)
}
