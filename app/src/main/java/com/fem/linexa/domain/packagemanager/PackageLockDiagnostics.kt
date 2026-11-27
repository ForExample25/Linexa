package com.fem.linexa.domain.packagemanager

/**
 * Finding 021 — recognise a package-manager lock held by another process.
 *
 * The UI queue serialises every package command Linexa starts, but a command
 * typed into a terminal goes through the pty and cannot be queued by the app.
 * Native apt/dpkg locking remains the authority; what was missing was the
 * explanation. When an operation fails because a terminal (or any other
 * process) holds the lock, this parser turns the tool's own message into a
 * structured holder so the UI can say **why** the operation failed.
 *
 * Deliberately pure and conservative:
 *
 *  - it only reads output from a failed operation, never probes or kills a
 *    process;
 *  - it recognises the apt/dpkg messages that name a lock and, when present,
 *    the holder's pid/name;
 *  - an unrecognised error returns null, so the UI keeps the raw output rather
 *    than guessing.
 */
object PackageLockDiagnostics {

    /**
     * The process the package manager named as the lock holder.
     *
     * @param pid the holder's pid when the tool printed one.
     * @param process the holder's command name (for example `apt`), when known.
     * @param lockPath the lock file the tool complained about, when known.
     */
    data class Holder(
        val pid: Int?,
        val process: String?,
        val lockPath: String?
    )

    /** `E: Could not get lock /var/lib/… . It is held by process 1234 (apt)` */
    private val COULD_NOT_GET_LOCK = Regex(
        pattern = """(?i)could not get lock\s+(\S+).*?held by process\s+(\d+)(?:\s*\(([^)]+)\))?"""
    )

    /** `E: Unable to acquire the dpkg frontend lock (/var/lib/dpkg/lock-frontend), …` */
    private val DPKG_FRONTEND_LOCK = Regex(
        pattern = """(?i)unable to acquire the dpkg frontend lock(?:\s*\(([^)]+)\))?"""
    )

    /** `dpkg: error: dpkg frontend lock was locked by another process with pid 1234` */
    private val DPKG_NAMED_PID = Regex(
        pattern = """(?i)(?:dpkg|apt)(?: frontend)? lock was locked by another process with pid\s+(\d+)"""
    )

    /**
     * Returns the first recognised lock-contention holder in [output], or null
     * when the output is not a lock-contention message.
     */
    fun detect(output: String): Holder? {
        if (output.isBlank()) return null

        COULD_NOT_GET_LOCK.find(output)?.let { match ->
            return Holder(
                pid = match.groupValues[2].toIntOrNull(),
                process = match.groupValues[3].trim().ifBlank { null },
                lockPath = match.groupValues[1].trimEnd('.', ':', ',')
            )
        }

        DPKG_NAMED_PID.find(output)?.let { match ->
            return Holder(
                pid = match.groupValues[1].toIntOrNull(),
                process = "dpkg",
                lockPath = null
            )
        }

        DPKG_FRONTEND_LOCK.find(output)?.let { match ->
            return Holder(
                pid = null,
                process = null,
                lockPath = match.groupValues[1].trim().ifBlank { null }
            )
        }

        return null
    }
}
