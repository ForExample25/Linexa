package com.fem.linexa

import android.app.Application
import com.fem.linexa.core.logging.DebugCaptureGate
import com.fem.linexa.service.installer.InstallRecoveryManager
import com.fem.linexa.service.installer.WorkspaceMetadataBackfillManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class LinexaApplication : Application() {

    // Hilt injects @HiltAndroidApp Application fields before onCreate() runs.
    @Inject lateinit var installRecoveryManager: InstallRecoveryManager
    @Inject lateinit var metadataBackfillManager: WorkspaceMetadataBackfillManager
    // P11: encrypts any legacy plaintext workspace secret still on disk.
    @Inject lateinit var vncPasswordMigrationManager: com.fem.linexa.service.workspace.VncPasswordMigrationManager

    /**
     * UI/UX Phase 7: mirrors the persisted "Enable debug info" setting into
     * `DebugCapture`, which is what the log sink and the runtime trace check
     * before recording anything. See [DebugCaptureGate] — capture is fail-closed,
     * so if this is not started the app collects nothing at all.
     */
    @Inject lateinit var debugCaptureGate: DebugCaptureGate

    /**
     * Phase 2D live-run fix (Run 0 finding): [Logger.init] previously ran only via
     * the lazy `provideAppLogStore` provider, so until something injected
     * [com.fem.linexa.core.logging.AppLogStore] the sink list was empty and EVERY
     * `Logger.*` call app-wide — including the Phase 2C DesktopTiming markers —
     * was a silent no-op on logcat. Injecting it here forces `provideLogger` at
     * every process start, restoring the facade's documented unconditional-logcat
     * contract. Logging-only change; no behavioral logic touched.
     */
    @Inject lateinit var logger: com.fem.linexa.core.logging.Logger

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // UI/UX Phase 7: FIRST, before anything else here can log. The gate only
        // starts a collector (the first value arrives on the application scope), but
        // starting it before the recovery/backfill work below is what makes a
        // cold-start capture possible at all when the user has debug enabled.
        debugCaptureGate.start(applicationScope)

        // 2026-08-29: the old `TerminalSession.setResources(resources)` call is
        // gone with the GPL terminal-lib it configured — the vendored
        // Apache-2.0 terminal modules need no application-level wiring.

        // §1.20: clean up/mark-Broken any workspace whose staged install was
        // interrupted by the app or process dying — see InstallRecoveryManager.
        applicationScope.launch {
            installRecoveryManager.recoverInterruptedInstalls()
            // After recovery, backfill metadata attributes for Ready
            // workspaces that predate attribute persistence (distroId for
            // branded icons, desktop capability for the Desktop button) —
            // see WorkspaceMetadataBackfillManager.
            metadataBackfillManager.backfillReadyWorkspaces()
            // P11: after the backfill so both see a settled row; idempotent, every start.
            vncPasswordMigrationManager.migrate()
        }
    }
}
