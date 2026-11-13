package com.fem.linexa.service.runtime

/**
 * Typed events emitted by RuntimeService (§1.6).
 * Each event corresponds to a lifecycle change or output from a running process.
 */
sealed interface RuntimeEvent {
    /** A workspace's PRoot process has started. */
    data class Started(val workspaceId: String, val pid: Int) : RuntimeEvent

    /** A workspace's PRoot process has stopped normally. */
    data class Stopped(val workspaceId: String) : RuntimeEvent

    /** A workspace's PRoot process crashed. */
    data class Crashed(val workspaceId: String, val reason: String) : RuntimeEvent

    /**
     * An exec command completed. (OutputLine was removed in §1.58: the
     * Terminal now attaches the real emulator to the pty directly, so no
     * line-oriented output bridge exists anymore.)
     */
    data class ExecCompleted(val workspaceId: String, val exitCode: Int) : RuntimeEvent
}
