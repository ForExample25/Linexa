package com.fem.linexa.domain.packagemanager

import kotlinx.coroutines.flow.Flow

/**
 * §1.92 — A package-manager operation the user can run (install/remove/
 * update/upgrade/refresh) and the stream of events it produces.
 *
 * [PackageProvider] exposes long-running operations as [Flow]s of
 * [OperationEvent], so the ViewModel can drive a live progress panel with
 * the package manager's own output without ever blocking the UI thread.
 */
enum class PackageOperation {
    INSTALL,
    REMOVE,
    UPDATE,
    UPGRADE,
    REFRESH
}

sealed interface OperationEvent {
    /** New line(s) of the package manager's own output (live tail). */
    data class Progress(val outputTail: String) : OperationEvent

    /** Operation completed successfully; [output] is the full captured output. */
    data class Success(val output: String) : OperationEvent

    /** Operation failed; [message] is a human-readable reason, [output] the captured output. */
    data class Failure(val message: String, val output: String) : OperationEvent
}
