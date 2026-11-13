package com.fem.linexa.service.installer

import android.os.Process
import android.system.Os
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Android process identity the guest account files are generated from.
 *
 * `RFS-01`: the installer used to read the host `/etc/passwd` to discover the
 * app's primary gid. Android does not guarantee an entry for an app UID there
 * (the audit device has only `6100–6120`), so every fresh install aborted at
 * `id -g failed (Kotlin getCurrentGid)`. The primary gid must come from the
 * kernel/Android API instead; `/etc/passwd` is only an optional name lookup
 * and its absence, unreadability, or malformed content must never fail an
 * install.
 *
 * The interface is the `RFS-08` test seam: JVM tests inject a fake instead of
 * calling `android.os.Process`/`android.system.Os`, which are not available
 * outside an Android runtime.
 */
interface ProcessIdentityProvider {
    /**
     * Returns the current process identity, or null only when Android cannot
     * report the uid or the primary gid at all. Optional name/group lookups
     * degrade to null/empty rather than failing.
     */
    fun currentIdentity(): ProcessIdentity?
}

/**
 * @property uid the real Android app UID (`Process.myUid()`)
 * @property primaryGid the Android primary gid (`Os.getgid()`); never parsed
 *   from host `/etc/passwd`
 * @property supplementaryGids the live kernel group set, from
 *   `/proc/self/status` `Groups:`; empty when the process genuinely has none
 * @property accountName optional host account name for [uid]; null when the
 *   host passwd database has no usable entry
 * @property groupNamesByGid optional host group names keyed by gid; missing
 *   gids fall back to the numeric gid in the guest files
 */
data class ProcessIdentity(
    val uid: Int,
    val primaryGid: Int,
    val supplementaryGids: List<Int>,
    val accountName: String?,
    val groupNamesByGid: Map<Int, String>
)

/**
 * Production identity source. Only the Android syscalls and the three host
 * text files are platform-specific; all parsing lives in the pure
 * [resolveProcessIdentity] helper so it is exercisable on the JVM.
 */
@Singleton
class AndroidProcessIdentityProvider @Inject constructor() : ProcessIdentityProvider {

    override fun currentIdentity(): ProcessIdentity? {
        val status = readTextOrNull(PROC_STATUS)
        return resolveProcessIdentity(
            uid = runCatching { Process.myUid() }.getOrNull(),
            apiGid = runCatching { Os.getgid() }.getOrNull(),
            procStatusText = status,
            passwdText = readTextOrNull(HOST_PASSWD),
            groupText = readTextOrNull(HOST_GROUP)
        )
    }

    private fun readTextOrNull(file: File): String? = try {
        if (file.isFile) file.readText() else null
    } catch (_: Exception) {
        null
    }

    private companion object {
        val PROC_STATUS = File("/proc/self/status")
        val HOST_PASSWD = File("/etc/passwd")
        val HOST_GROUP = File("/etc/group")
    }
}

/**
 * Pure `RFS-01` resolver. [apiGid] is the value from Android
 * (`Os.getgid()`); the `/proc/self/status` `Gid:` field is only a fallback
 * for platforms where the API call fails. The host passwd/group text is
 * optional and malformed lines are ignored — this function never throws and
 * never turns a missing text file into a failed install.
 */
internal fun resolveProcessIdentity(
    uid: Int?,
    apiGid: Int?,
    procStatusText: String?,
    passwdText: String?,
    groupText: String?
): ProcessIdentity? {
    val resolvedUid = uid ?: return null
    val resolvedGid = apiGid ?: parseProcStatusGid(procStatusText) ?: return null
    return ProcessIdentity(
        uid = resolvedUid,
        primaryGid = resolvedGid,
        supplementaryGids = parseProcStatusGroups(procStatusText),
        accountName = parsePasswdAccountName(passwdText, resolvedUid),
        groupNamesByGid = parseGroupNamesByGid(groupText)
    )
}

/** Parses `/proc/self/status` `Groups:` into a de-duplicated gid list. */
internal fun parseProcStatusGroups(statusText: String?): List<Int> {
    if (statusText == null) return emptyList()
    val line = statusText.lineSequence().firstOrNull { it.startsWith("Groups:") } ?: return emptyList()
    return line.substringAfter(':')
        .trim()
        .split(Regex("\\s+"))
        .mapNotNull { it.toIntOrNull() }
        .distinct()
}

/**
 * Parses the real gid from `/proc/self/status` `Gid: real effective saved fs`.
 * Used only when `Os.getgid()` is unavailable; the real gid is the same value
 * `Os.getgid()` reports.
 */
internal fun parseProcStatusGid(statusText: String?): Int? {
    if (statusText == null) return null
    val line = statusText.lineSequence().firstOrNull { it.startsWith("Gid:") } ?: return null
    return line.substringAfter(':').trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
}

/** Optional host account-name lookup by uid; tolerant of every malformed line. */
internal fun parsePasswdAccountName(passwdText: String?, uid: Int): String? {
    if (passwdText == null) return null
    for (line in passwdText.lineSequence()) {
        val parts = line.split(':')
        // A usable passwd record has all seven fields. A truncated line is
        // malformed and must not supply a name, even when its uid field
        // happens to match.
        if (parts.size < 7) continue
        if (parts[2].trim().toIntOrNull() != uid) continue
        val name = parts[0].trim()
        if (name.isNotEmpty()) return name
    }
    return null
}

/** Optional host `gid -> name` map; first usable name wins, malformed lines ignored. */
internal fun parseGroupNamesByGid(groupText: String?): Map<Int, String> {
    if (groupText == null) return emptyMap()
    val names = LinkedHashMap<Int, String>()
    for (line in groupText.lineSequence()) {
        val parts = line.split(':')
        if (parts.size < 3) continue
        val gid = parts[2].trim().toIntOrNull() ?: continue
        val name = parts[0].trim()
        if (name.isNotEmpty()) names.putIfAbsent(gid, name)
    }
    return names
}
