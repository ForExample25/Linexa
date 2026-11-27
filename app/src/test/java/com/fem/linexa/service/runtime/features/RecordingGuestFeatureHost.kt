package com.fem.linexa.service.runtime.features

import com.fem.linexa.domain.runtime.CommandResult

/**
 * §1.110 — a scripted [GuestFeatureHost] shared by the feature-layer tests and
 * by [com.fem.linexa.service.runtime.WorkspaceSessionSupervisorTest] (whose
 * launch path now reconciles feature toggles).
 *
 * Lives in the `features` test package rather than `test.support` because it is
 * a fake of *this* seam; the harness in `test.support` fakes the PRoot binary.
 * Records call order so a test can assert not only the outcome but that nothing
 * ran when nothing should have.
 */
open class RecordingGuestFeatureHost(
    var sessionAlive: Boolean = true,
    var daemonAlive: Boolean = false,
    var daemonStartSucceeds: Boolean = true
) : GuestFeatureHost {

    val execCalls = mutableListOf<String>()
    val startedDaemons = mutableListOf<Pair<String, String>>()
    val stoppedDaemons = mutableListOf<String>()
    var execThrows: Exception? = null

    /** command-substring → result. First match wins; unmatched → [defaultResult]. */
    val responses = linkedMapOf<String, CommandResult>()
    var defaultResult = CommandResult(0, "", "")

    override fun hasActiveSession(workspaceId: String) = sessionAlive

    override suspend fun execCommand(workspaceId: String, command: String): CommandResult {
        execCalls += command
        execThrows?.let { throw it }
        val key = responses.keys.firstOrNull { command.contains(it) }
        return key?.let { responses.getValue(it) } ?: defaultResult
    }

    override suspend fun execCommandWithInput(
        workspaceId: String,
        command: String,
        stdin: String
    ): CommandResult = execCommand(workspaceId, command)

    override suspend fun startDaemon(workspaceId: String, name: String, command: String): Boolean {
        startedDaemons += name to command
        if (daemonStartSucceeds) daemonAlive = true
        return daemonStartSucceeds
    }

    override suspend fun stopDaemon(workspaceId: String, name: String) {
        stoppedDaemons += name
        daemonAlive = false
    }

    override fun isDaemonAlive(workspaceId: String, name: String) = daemonAlive

    /** Scripts the sshd probe to report an installed server at the usual path. */
    fun withSshdInstalled(path: String = "/usr/sbin/sshd") = apply {
        responses["command -v sshd"] = CommandResult(0, "$path\n", "")
    }
}
