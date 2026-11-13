package com.fem.linexa.data.resource

import android.os.Process
import java.io.File
import javax.inject.Inject

/**
 * The filesystem/process-identity seam for host discovery.
 *
 * Android's `/proc` is the only production source, but hiding it behind three
 * calls makes the discovery rules — inaccessible PIDs, self protection, UID
 * scope, and command-line display — assertable on the JVM with a fake table.
 */
interface ProcFsReader {
    /** Numeric pids visible in `/proc`, or null when the directory itself is unreadable. */
    fun listPids(): List<Int>?

    /** Contents of `/proc/<pid>/<name>`, or null for exit/SELinux denial. */
    fun readText(pid: Int, name: String): String?

    fun selfPid(): Int

    fun selfUid(): Int?
}

class AndroidProcFsReader @Inject constructor() : ProcFsReader {

    override fun listPids(): List<Int>? =
        runCatching {
            File("/proc").list()
                ?.mapNotNull { it.toIntOrNull() }
        }.getOrNull()

    override fun readText(pid: Int, name: String): String? =
        runCatching { File("/proc/$pid/$name").readText() }.getOrNull()

    override fun selfPid(): Int = Process.myPid()

    override fun selfUid(): Int? = runCatching { Process.myUid() }.getOrNull()
}
