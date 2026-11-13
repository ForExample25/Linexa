package com.fem.linexa.ui.screens.packagemanager

import com.fem.linexa.domain.packagemanager.PackageOperationCoordinator

/**
 * PKG-1 — the queue state the Package Manager screen renders.
 *
 * This is a read-only projection of [PackageOperationCoordinator.Snapshot].
 * There is no second queue, no scheduling and no polling: the ViewModel
 * collects the coordinator's existing `StateFlow` and maps each emission here.
 *
 * [waiting] is the coordinator's own waiter count. The one adjustment is the
 * pre-acquisition frame: `runExclusive` increments `waiting` just before it
 * takes the mutex, so for a moment the screen's own operation appears as
 * "1 waiting" with no active owner. That is not a queued operation — it is
 * the operation about to start — and showing it would make the panel flash a
 * misleading "1 queued".
 */
data class PackageQueueUi(
    val activeCommand: String? = null,
    val waiting: Int = 0,
    val busy: Boolean = false
) {
    /** Whether there is anything from other surfaces worth a strip. */
    val hasOtherActivity: Boolean get() = busy || waiting > 0
}

internal object PackageQueuePresentation {

    /**
     * @param ownOperationRunning true while THIS screen's operation has a
     *   [PackageManagerUiState.Ready] operation in `RUNNING`; used only to
     *   suppress the one-frame pre-acquisition count described above.
     */
    fun from(
        snapshot: PackageOperationCoordinator.Snapshot,
        ownOperationRunning: Boolean
    ): PackageQueueUi {
        val ownAcquiring = ownOperationRunning &&
            snapshot.activeCommand == null &&
            snapshot.waiting <= 1
        return PackageQueueUi(
            activeCommand = snapshot.activeCommand,
            waiting = if (ownAcquiring) 0 else snapshot.waiting,
            busy = snapshot.isBusy
        )
    }
}
