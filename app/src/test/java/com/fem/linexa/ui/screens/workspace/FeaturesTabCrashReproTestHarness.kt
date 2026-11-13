package com.fem.linexa.ui.screens.workspace

import android.app.Application
import com.fem.linexa.domain.resource.WorkspaceResourceSample
import com.fem.linexa.service.runtime.features.AudioSink
import com.fem.linexa.service.runtime.features.AudioSource
import com.fem.linexa.service.runtime.features.AudioStream
import com.fem.linexa.service.runtime.features.ClipboardFeatureState
import com.fem.linexa.service.runtime.features.GuestFeatureHost
import com.fem.linexa.service.runtime.features.GuestNotification
import com.fem.linexa.service.runtime.features.GuestNotificationBridge
import com.fem.linexa.service.runtime.features.HostClipboard
import com.fem.linexa.service.runtime.features.NotificationFeatureState
import com.fem.linexa.service.runtime.features.SshFeatureState
import com.fem.linexa.service.runtime.features.WorkspaceAudioSupervisor
import com.fem.linexa.service.runtime.features.WorkspaceClipboardSupervisor
import com.fem.linexa.service.runtime.features.WorkspaceNotificationSupervisor
import com.fem.linexa.domain.runtime.CommandResult
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.InputStream

/**
 * Shared wiring for the Features/workspace crash-repro harnesses: the REAL
 * [FeaturesViewModel] with supervisors built exactly like AppModule's
 * providers, over fakes for the three process edges (exec host, host
 * clipboard, audio sink). Kept in one place so both harnesses exercise the
 * same production shape.
 */
internal object FeaturesTabCrashReproTestHarness {

    fun realFeaturesViewModel(): FeaturesViewModel {
        val host = FakeGuestFeatureHost()
        val spool = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, "test-spool")
            .apply { mkdirs() }
        return FeaturesViewModel(
            fileManager = org.mockito.Mockito.mock(com.fem.linexa.service.runtime.WorkspaceFileManager::class.java),
            notificationSupervisor = WorkspaceNotificationSupervisor(
                host = host,
                bridge = FakeNotificationBridge,
                scope = kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
                ),
                spoolDirFor = { File(spool, it) }
            ),
            clipboardSupervisor = WorkspaceClipboardSupervisor(
                host = host,
                clipboard = FakeHostClipboard,
                scope = kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
                ),
                outDirFor = { File(spool, "$it-out") },
                mirrorFileFor = { File(spool, "$it-in") }
            ),
            audioSupervisor = WorkspaceAudioSupervisor(
                host = host,
                sink = FakeAudioSink,
                scope = kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
                ),
                sourceFor = { FakeAudioSource }
            )
        )
    }
}

internal class FakeGuestFeatureHost : GuestFeatureHost {
    override fun hasActiveSession(workspaceId: String): Boolean = false
    override suspend fun execCommand(workspaceId: String, command: String): CommandResult =
        CommandResult(0, "", "")
    override suspend fun startDaemon(workspaceId: String, name: String, command: String): Boolean = false
    override suspend fun stopDaemon(workspaceId: String, name: String) = Unit
    override fun isDaemonAlive(workspaceId: String, name: String): Boolean = false
}

internal object FakeNotificationBridge : GuestNotificationBridge {
    override fun canPost(): Boolean = true
    override fun post(workspaceId: String, notification: GuestNotification) = Unit
    override fun cancelWorkspace(workspaceId: String) = Unit
    override fun setDisplayName(workspaceId: String, displayName: String) = Unit
}

internal object FakeHostClipboard : HostClipboard {
    override fun read(): String? = null
    override fun write(text: String) = Unit
    override fun setChangeListener(listener: (() -> Unit)?) = Unit
}

internal object FakeAudioSink : AudioSink {
    override fun open(): AudioStream? = null
}

internal object FakeAudioSource : AudioSource {
    override fun open(): InputStream? = null
}

/** No-op monitor — the panel never samples while the workspace is stopped. */
internal object NoopResourceMonitor : com.fem.linexa.domain.resource.ResourceMonitor {
    override suspend fun sample(pid: Int): WorkspaceResourceSample =
        WorkspaceResourceSample.unavailable(cpuCores = 0)

    override fun forget(pid: Int) = Unit
}

/**
 * App-grid redesign: no-op pins store — the segments crash-repro composes the
 * Commands grid without a Hilt graph or a Room database, so the pins
 * ViewModel attaches over this empty store (nothing pinned, nothing
 * persisted; the store seam exists exactly for this).
 */
internal object NoopShortcutPinsStore : com.fem.linexa.domain.commands.ShortcutPinsStore {
    override suspend fun load(workspaceId: String) =
        com.fem.linexa.domain.commands.ShortcutPins()

    override suspend fun persist(
        workspaceId: String,
        updated: com.fem.linexa.domain.commands.ShortcutPins,
        fallback: com.fem.linexa.domain.commands.ShortcutPins
    ) = updated
}

// Quiet "unused" reference keeper for ClipboardFeatureState (imported for the
// harness's publish call sites in the crash tests).
@Suppress("unused")
private val clipboardStateRef: ClipboardFeatureState = ClipboardFeatureState.Disabled
