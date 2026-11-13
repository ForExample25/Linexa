package com.fem.linexa.ui.misc

/**
 * Phase 08 (audit OPT-PERF-004): the ONE home of the app's `/proc` poll
 * cadence.
 *
 * The six screen pollers (`ActivityViewModel`, `SessionsViewModel`,
 * `TaskManagerViewModel`, `DeviceInfoViewModel`, `SessionTaskManagerViewModel`,
 * `WorkspacePerformanceViewModel`) each used to declare their own
 * `POLL_INTERVAL_MS = 2_000L` companion constant; a cadence change meant six
 * coordinated edits and a drift risk. They now delegate to [INTERVAL_MS] —
 * the constants keep their public names, so callers and tests are unchanged.
 *
 * The visible-lifetime law the pollers follow is unchanged and stays enforced
 * per ViewModel: ONE timer runs only while the owning screen is visible,
 * `start` is idempotent, and `stop`/`pause` cancels the polling job
 * (`onCleared` cancels unconditionally). The `/proc` reads themselves were
 * already delegated to shared samplers (`ResourceMonitor`,
 * `HostResourceSampler`, `HostSystemInfoProvider`) — no duplicate
 * `ProcessSnapshotSource` was needed.
 */
object ProcessPolling {
    /** 2 s — the interval every sampler in this app shares. */
    const val INTERVAL_MS = 2_000L
}
