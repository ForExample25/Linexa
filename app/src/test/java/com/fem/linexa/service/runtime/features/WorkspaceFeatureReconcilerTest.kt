package com.fem.linexa.service.runtime.features

import com.fem.linexa.data.WorkspaceContent
import com.fem.linexa.service.runtime.WorkspaceHealthRegistry
import com.fem.linexa.domain.network.HostDnsProvider
import com.fem.linexa.domain.packagemanager.PackageManagerDetector
import com.fem.linexa.domain.runtime.CommandResult
import com.fem.linexa.test.support.InMemoryWorkspaceRepository
import com.fem.linexa.test.support.buildWorkspaceMetadata
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * §1.110: [WorkspaceFeatureReconciler] — the launch path's application of a
 * workspace's *persisted* feature toggles.
 *
 * The gap this closes is what these tests pin: before §1.110 a daemon-backed
 * toggle was only honored while the Features tab was open, so a workspace whose
 * `ssh` toggle was already on (previous run, or an imported archive's
 * `config.yaml linexa.features`) launched with no sshd. The other half is
 * fail-open behavior: nothing here may throw into the launch coroutine, because
 * the session is already Running when it executes.
 *
 * §1.111: the reconciler now applies `notifications` too, so the independence of
 * the two features is pinned here as well — one feature failing must not skip the
 * other's application.
 *
 * §1.112: and `clipboard`, with the same independence rule.
 *
 * §1.113: and `audio`, which completes Phase 5's guest-side consumers.
 */
class WorkspaceFeatureReconcilerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val ws = "ws-1"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationBridge = RecordingNotificationBridge()
    private val hostClipboard = RecordingHostClipboard()
    private val audioSink = RecordingAudioSink()
    private val audioSource = ScriptedAudioSource()

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun notificationSupervisor(guest: RecordingGuestFeatureHost) =
        WorkspaceNotificationSupervisor(
            host = guest,
            bridge = notificationBridge,
            scope = scope,
            spoolDirFor = { File(temp.root, "spool-$it") },
            pollIntervalMillis = 10L
        )

    private fun clipboardSupervisor(guest: RecordingGuestFeatureHost) =
        WorkspaceClipboardSupervisor(
            host = guest,
            clipboard = hostClipboard,
            scope = scope,
            outDirFor = { File(temp.root, "clip-out-$it") },
            mirrorFileFor = { File(temp.root, "clip-in-$it/clipboard") },
            pollIntervalMillis = 10L
        )

    private fun audioSupervisor(guest: RecordingGuestFeatureHost) =
        WorkspaceAudioSupervisor(
            host = guest,
            sink = audioSink,
            scope = scope,
            sourceFor = { audioSource },
            reopenDelayMillis = 10L
        )

    private fun reconciler(
        repository: InMemoryWorkspaceRepository,
        guest: RecordingGuestFeatureHost
    ) = WorkspaceFeatureReconciler(
        repository,
        guest,
        notificationSupervisor(guest),
        clipboardSupervisor(guest),
        audioSupervisor(guest),
        WorkspaceNetworkProvisioner(repository, guest, HostDnsProvider { emptyList() }),
        WorkspaceHealthRegistry()
    )

    private fun repositoryWith(toggles: Map<String, Boolean>) = InMemoryWorkspaceRepository().apply {
        add(buildWorkspaceMetadata(ws, featureToggles = toggles))
    }

    /**
     * Phase 2E-1: the launch path is now two phases — [WorkspaceFeatureReconciler.onSessionStarted]
     * (network, awaited inline) and [WorkspaceFeatureReconciler.onSessionStartedDeferred]
     * (ssh → notifications → clipboard → audio, run by the supervisor once the
     * desktop start settles). Every pre-split test drove the whole application
     * through one call; driving both phases back to back preserves those
     * assertions verbatim while exercising the split API in the production
     * order.
     */
    private fun WorkspaceFeatureReconciler.launchReconcile(workspaceId: String) = runBlocking {
        onSessionStarted(workspaceId)
        onSessionStartedDeferred(workspaceId)
    }

    /**
     * UI/UX Phase 6 — a workspace whose persisted `attributes` also carry feature
     * *settings*, encoded exactly as the Features tab writes them.
     */
    private fun repositoryWith(
        toggles: Map<String, Boolean>,
        settings: FeatureSettings
    ) = InMemoryWorkspaceRepository().apply {
        add(
            buildWorkspaceMetadata(
                ws,
                featureToggles = toggles,
                attributes = settings.toAttributes()
            )
        )
    }

    /**
     * Toggles with every default-on guest feature explicitly off, isolating the
     * ssh assertions. `notifications`, `clipboard` and `audio` all ship enabled, so
     * an ssh test that did not silence them would see their guest execs too.
     */
    private fun sshOnly(sshEnabled: Boolean) = mapOf(
        SshdProvisioner.FEATURE_SSH to sshEnabled,
        NotifySendProvisioner.FEATURE_NOTIFICATIONS to false,
        ClipboardBridgeProvisioner.FEATURE_CLIPBOARD to false,
        AudioBridgeProvisioner.FEATURE_AUDIO to false
    )

    /** Toggles with only `notifications` on, isolating its assertions. */
    private fun notificationsOnly(enabled: Boolean) = mapOf(
        SshdProvisioner.FEATURE_SSH to false,
        NotifySendProvisioner.FEATURE_NOTIFICATIONS to enabled,
        ClipboardBridgeProvisioner.FEATURE_CLIPBOARD to false,
        AudioBridgeProvisioner.FEATURE_AUDIO to false
    )

    /** Toggles with only `clipboard` on, isolating its assertions. */
    private fun clipboardOnly(enabled: Boolean) = mapOf(
        SshdProvisioner.FEATURE_SSH to false,
        NotifySendProvisioner.FEATURE_NOTIFICATIONS to false,
        ClipboardBridgeProvisioner.FEATURE_CLIPBOARD to enabled,
        AudioBridgeProvisioner.FEATURE_AUDIO to false
    )

    /** Toggles with only `audio` on, isolating its assertions. */
    private fun audioOnly(enabled: Boolean) = mapOf(
        SshdProvisioner.FEATURE_SSH to false,
        NotifySendProvisioner.FEATURE_NOTIFICATIONS to false,
        ClipboardBridgeProvisioner.FEATURE_CLIPBOARD to false,
        AudioBridgeProvisioner.FEATURE_AUDIO to enabled
    )

    // ── launch: the toggle is honored without the Features tab ──

    @Test
    fun persistedSshToggleStartsTheDaemonAtLaunch() = runBlocking {
        val guest = RecordingGuestFeatureHost().withSshdInstalled()
        reconciler(repositoryWith(sshOnly(true)), guest).launchReconcile(ws)

        assertEquals(1, guest.startedDaemons.size)
        assertEquals(SshdProvisioner.DAEMON_NAME, guest.startedDaemons.single().first)
        assertEquals("/usr/sbin/sshd -D -e -p ${SshdProvisioner.SSH_PORT}", guest.startedDaemons.single().second)
    }

    @Test
    fun aDisabledToggleRunsNoGuestCommandAtLaunch() = runBlocking {
        val guest = RecordingGuestFeatureHost().withSshdInstalled()
        reconciler(repositoryWith(sshOnly(false)), guest).launchReconcile(ws)

        // Deliberately not even a stop-reconcile: a fresh session has no
        // daemons, so that would be a pointless exec on every launch.
        //
        // Transformation spec §20/§21: the network provisioning step is
        // boot-time, not a feature — its resolv.conf/hosts-file execs now run
        // on every launch (DNS is skipped here: default settings + the fake
        // host DNS provider give it nothing to write, and the modeled guest's
        // default answers leave the deterministic last-resort seed as the
        // only DNS exec). Filter those out.
        val nonNetworkExecs = guest.execCalls.filter { exec ->
            !(exec.contains("/etc/hosts") || exec.contains("hostname") ||
                exec.contains("resolv.conf"))
        }
        assertTrue(nonNetworkExecs.isEmpty())
        assertTrue(guest.startedDaemons.isEmpty())
        assertTrue(guest.stoppedDaemons.isEmpty())
    }

    @Test
    fun theBuiltInDefaultAppliesWhenTheWorkspaceHasNoPersistedToggle() = runBlocking {
        // ssh's built-in default is off, so an empty persisted map must not
        // start a daemon. This pins the resolution path (built-in defaults <
        // persisted), not just the ssh default.
        assertFalse(
            "this test assumes ssh defaults to off",
            WorkspaceContent.builtinFeatureDefaults.getValue(SshdProvisioner.FEATURE_SSH)
        )
        val guest = RecordingGuestFeatureHost().withSshdInstalled()
        reconciler(repositoryWith(emptyMap()), guest).launchReconcile(ws)

        assertTrue(guest.startedDaemons.isEmpty())
    }

    @Test
    fun anUnknownWorkspaceFallsBackToDefaultsInsteadOfFailing() = runBlocking {
        val guest = RecordingGuestFeatureHost().withSshdInstalled()
        // Nothing added to the repository — getById returns null.
        reconciler(InMemoryWorkspaceRepository(), guest).launchReconcile(ws)

        assertTrue(guest.startedDaemons.isEmpty())
    }

    @Test
    fun launchInstallsTheServerWhenTheGuestDoesNotHaveIt() = runBlocking {
        val guest = RecordingGuestFeatureHost().apply {
            responses[PackageManagerDetector.PROBE_COMMAND] = CommandResult(0, "/usr/bin/apt-get\n", "")
            responses["os-release"] = CommandResult(0, "ID=debian\n", "")
        }
        // Probe reports missing until the install has run.
        var installed = false
        val scripted = object : GuestFeatureHost by guest {
            override suspend fun execCommand(workspaceId: String, command: String): CommandResult {
                if (command.contains("apt-get install")) installed = true
                if (command.contains("command -v sshd")) {
                    return if (installed) CommandResult(0, "/usr/sbin/sshd\n", "") else CommandResult(1, "", "")
                }
                return guest.execCommand(workspaceId, command)
            }
        }
        val launchRepository = repositoryWith(sshOnly(true))
        WorkspaceFeatureReconciler(
            launchRepository,
            scripted,
            notificationSupervisor(guest),
            clipboardSupervisor(guest),
            audioSupervisor(guest),
            WorkspaceNetworkProvisioner(launchRepository, guest, HostDnsProvider { emptyList() }),
            WorkspaceHealthRegistry()
        ).launchReconcile(ws)

        assertTrue(
            "the launch path must run the shared install command: ${guest.execCalls}",
            guest.execCalls.any { it == SshdProvisioner.installCommand(PackageManagerDetector.KEY_APT) }
        )
        assertEquals(1, guest.startedDaemons.size)
    }

    // ── UI/UX Phase 6: the launch path applies the persisted settings ──

    @Test
    fun launchStartsSshdOnThePersistedPort() = runBlocking {
        // The defect this prevents: entering a workspace starts sshd on 8022
        // regardless of the setting, and the Features tab then *reports* the live
        // port — so the user sees their configured value silently ignored.
        val guest = RecordingGuestFeatureHost().withSshdInstalled()
        reconciler(
            repositoryWith(sshOnly(true), FeatureSettings(sshPort = 2222)),
            guest
        ).launchReconcile(ws)

        assertEquals("/usr/sbin/sshd -D -e -p 2222", guest.startedDaemons.single().second)
    }

    @Test
    fun launchInstallsOnlyTheSelectedClipboardAliases() = runBlocking {
        val guest = RecordingGuestFeatureHost()
        reconciler(
            repositoryWith(
                clipboardOnly(true),
                FeatureSettings(clipboardAliases = setOf("wl-copy"))
            ),
            guest
        ).launchReconcile(ws)

        assertTrue(guest.execCalls.any { it.contains("/usr/local/bin/wl-copy") })
        assertFalse(
            "a deselected alias must not be installed at launch either",
            guest.execCalls.any { it.contains("/usr/local/bin/xclip") }
        )
    }

    @Test
    fun aPreSettingsWorkspaceLaunchesWithTheOldDefaults() = runBlocking {
        // Every workspace that existed before Phase 6 has no attributes at all.
        // FeatureSettings.from is total and fail-open, so this must behave exactly
        // as it did before rather than refusing to launch.
        val guest = RecordingGuestFeatureHost().withSshdInstalled()
        reconciler(repositoryWith(sshOnly(true)), guest).launchReconcile(ws)

        assertEquals(
            "/usr/sbin/sshd -D -e -p ${SshdProvisioner.SSH_PORT}",
            guest.startedDaemons.single().second
        )
    }

    @Test
    fun aCorruptPersistedPortDoesNotBlockTheLaunch() = runBlocking {
        val guest = RecordingGuestFeatureHost().withSshdInstalled()
        val repository = InMemoryWorkspaceRepository().apply {
            add(
                buildWorkspaceMetadata(
                    ws,
                    featureToggles = sshOnly(true),
                    attributes = mapOf(FeatureSettings.KEY_SSH_PORT to "not-a-port")
                )
            )
        }
        reconciler(repository, guest).launchReconcile(ws)

        assertEquals(
            "/usr/sbin/sshd -D -e -p ${SshdProvisioner.SSH_PORT}",
            guest.startedDaemons.single().second
        )
    }

    // ── fail-open: a feature must never cost the user the session ──

    @Test
    fun anEnabledToggleThatCannotBeInstalledDoesNotThrow() = runBlocking {
        val guest = RecordingGuestFeatureHost().apply {
            responses["command -v sshd"] = CommandResult(1, "", "")
            responses[PackageManagerDetector.PROBE_COMMAND] = CommandResult(1, "", "")
            responses["os-release"] = CommandResult(1, "", "")
        }
        // No supported package manager: reconcileSsh returns Failed, and the
        // reconciler must swallow it (the workspace is already Running).
        reconciler(repositoryWith(sshOnly(true)), guest).launchReconcile(ws)

        assertTrue(guest.startedDaemons.isEmpty())
    }

    @Test
    fun aThrowingGuestDoesNotEscapeIntoTheLaunchCoroutine() = runBlocking {
        val guest = RecordingGuestFeatureHost().withSshdInstalled().apply {
            execThrows = IllegalStateException("proot host died")
        }
        reconciler(repositoryWith(sshOnly(true)), guest).launchReconcile(ws)

        assertTrue(guest.startedDaemons.isEmpty())
    }

    @Test
    fun aThrowingRepositoryFallsBackToDefaults() = runBlocking {
        val guest = RecordingGuestFeatureHost().withSshdInstalled()
        val brokenRepository = object : InMemoryWorkspaceRepository() {
            override suspend fun getById(id: String): com.fem.linexa.domain.workspace.WorkspaceMetadata =
                throw IllegalStateException("db closed")
        }
        WorkspaceFeatureReconciler(
            brokenRepository,
            guest,
            notificationSupervisor(guest),
            clipboardSupervisor(guest),
            audioSupervisor(guest),
            WorkspaceNetworkProvisioner(brokenRepository, guest, HostDnsProvider { emptyList() }),
            WorkspaceHealthRegistry()
        ).launchReconcile(ws)

        // Defaults (ssh off) — never a crash, never an accidental start.
        assertTrue(guest.startedDaemons.isEmpty())
    }

    // ── idempotence and stop ──

    @Test
    fun reconcilingTwiceDoesNotStartASecondDaemon() = runBlocking {
        val guest = RecordingGuestFeatureHost().withSshdInstalled()
        val reconciler = reconciler(repositoryWith(sshOnly(true)), guest)

        reconciler.launchReconcile(ws)
        reconciler.launchReconcile(ws)

        // The second pass sees an alive daemon and executes nothing.
        assertEquals(1, guest.startedDaemons.size)
    }

    @Test
    fun stoppingReleasesTheDaemon() = runBlocking {
        val guest = RecordingGuestFeatureHost().withSshdInstalled()
        val reconciler = reconciler(repositoryWith(sshOnly(true)), guest)

        reconciler.launchReconcile(ws)
        assertTrue(guest.daemonAlive)
        reconciler.onSessionStopping(ws)

        assertEquals(listOf(SshdProvisioner.DAEMON_NAME), guest.stoppedDaemons)
        assertFalse(guest.daemonAlive)
    }

    @Test
    fun stoppingIsIdempotentAndIgnoresTheToggle() = runBlocking {
        // Even with the toggle OFF, stopping must run the (idempotent) stop —
        // that is what reaps a daemon left behind by a toggle flipped off while
        // the app was dead.
        val guest = RecordingGuestFeatureHost().withSshdInstalled()
        val reconciler = reconciler(repositoryWith(sshOnly(false)), guest)

        reconciler.onSessionStopping(ws)
        reconciler.onSessionStopping(ws)

        assertEquals(2, guest.stoppedDaemons.size)
    }

    @Test
    fun stopDoesNotThrowWhenTheGuestIsAlreadyGone() = runBlocking {
        val guest = object : RecordingGuestFeatureHost() {
            override suspend fun stopDaemon(workspaceId: String, name: String) {
                throw IllegalStateException("runtime already torn down")
            }
        }
        // Teardown must continue — this runs in the launch coroutine's finally.
        reconciler(repositoryWith(sshOnly(true)), guest).onSessionStopping(ws)
    }

    // ── §1.111: notifications on the same launch path ──

    @Test
    fun theNotificationShimIsInstalledAtLaunchWhenTheToggleIsOn() = runBlocking {
        val guest = RecordingGuestFeatureHost()
        reconciler(
            repositoryWith(notificationsOnly(true)),
            guest
        ).launchReconcile(ws)

        assertTrue(
            "the launch path must install the notify-send shim: ${guest.execCalls}",
            guest.execCalls.any { it == NotifySendProvisioner.installCommand() }
        )
    }

    @Test
    fun notificationsApplyByDefaultBecauseTheBuiltInDefaultIsOn() = runBlocking {
        // Unlike ssh, this feature ships enabled — so a workspace that never
        // touched the Features tab must still get the shim.
        assertTrue(
            "this test assumes notifications default to on",
            WorkspaceContent.builtinFeatureDefaults.getValue(NotifySendProvisioner.FEATURE_NOTIFICATIONS)
        )
        val guest = RecordingGuestFeatureHost()
        reconciler(repositoryWith(emptyMap()), guest).launchReconcile(ws)

        assertTrue(guest.execCalls.any { it == NotifySendProvisioner.installCommand() })
    }

    @Test
    fun aDisabledNotificationToggleInstallsNothing() = runBlocking {
        val guest = RecordingGuestFeatureHost()
        reconciler(
            repositoryWith(notificationsOnly(false)),
            guest
        ).launchReconcile(ws)

        assertFalse(guest.execCalls.any { it == NotifySendProvisioner.installCommand() })
    }

    @Test
    fun theLaunchPathNamesTheWorkspaceSoItsNotificationsAreAttributable() = runBlocking {
        val guest = RecordingGuestFeatureHost()
        val repository = InMemoryWorkspaceRepository().apply {
            add(
                buildWorkspaceMetadata(
                    ws,
                    displayName = "Debian Dev",
                    featureToggles = notificationsOnly(true)
                )
            )
        }
        reconciler(repository, guest).launchReconcile(ws)

        // The raw id is a UUID; a user with several workspaces must be able to
        // tell which one posted. The launch path is the only place that already
        // holds the metadata, so this is where the name has to come from.
        assertEquals("Debian Dev", notificationBridge.displayNames[ws])
    }

    @Test
    fun aWorkspaceWithNoNameStillGetsItsNotifications() = runBlocking {
        val guest = RecordingGuestFeatureHost()
        val repository = InMemoryWorkspaceRepository().apply {
            add(
                buildWorkspaceMetadata(
                    ws,
                    displayName = "",
                    featureToggles = notificationsOnly(true)
                )
            )
        }
        reconciler(repository, guest).launchReconcile(ws)

        // Attribution is a nicety — never a gate on the feature working.
        assertTrue(guest.execCalls.any { it == NotifySendProvisioner.installCommand() })
    }

    @Test
    fun stoppingWithdrawsTheWorkspacesNotifications() = runBlocking {
        val guest = RecordingGuestFeatureHost()
        val reconciler = reconciler(
            repositoryWith(notificationsOnly(true)),
            guest
        )

        reconciler.launchReconcile(ws)
        reconciler.onSessionStopping(ws)

        // A stopped workspace must not leave notifications implying it runs.
        assertTrue(notificationBridge.cancelled.contains(ws))
        // The shim itself stays for the next session — the toggle is still on.
        assertFalse(guest.execCalls.any { it == NotifySendProvisioner.uninstallCommand() })
    }

    // ── §1.112: clipboard on the same launch path ──

    @Test
    fun theClipboardBridgeIsInstalledAtLaunchWhenTheToggleIsOn() = runBlocking {
        val guest = RecordingGuestFeatureHost()
        reconciler(repositoryWith(clipboardOnly(true)), guest).launchReconcile(ws)

        assertTrue(
            "the launch path must install the clipboard shims: ${guest.execCalls}",
            guest.execCalls.any { it == ClipboardBridgeProvisioner.installCommand() }
        )
    }

    @Test
    fun clipboardAppliesByDefaultBecauseTheBuiltInDefaultIsOn() = runBlocking {
        // Like notifications and unlike ssh, this feature ships enabled — so a
        // workspace that never opened the Features tab must still get the bridge.
        assertTrue(
            "this test assumes clipboard defaults to on",
            WorkspaceContent.builtinFeatureDefaults.getValue(ClipboardBridgeProvisioner.FEATURE_CLIPBOARD)
        )
        val guest = RecordingGuestFeatureHost()
        reconciler(repositoryWith(emptyMap()), guest).launchReconcile(ws)

        assertTrue(guest.execCalls.any { it == ClipboardBridgeProvisioner.installCommand() })
    }

    @Test
    fun aDisabledClipboardToggleInstallsNothing() = runBlocking {
        val guest = RecordingGuestFeatureHost()
        reconciler(repositoryWith(clipboardOnly(false)), guest).launchReconcile(ws)

        assertFalse(guest.execCalls.any { it == ClipboardBridgeProvisioner.installCommand() })
    }

    @Test
    fun launchSeedsThePasteMirrorFromTheHostClipboard() = runBlocking {
        hostClipboard.text = "already on the phone"
        val guest = RecordingGuestFeatureHost()
        reconciler(repositoryWith(clipboardOnly(true)), guest).launchReconcile(ws)

        // The very first paste in a freshly launched workspace must already have
        // the user's clipboard — the mirror is seeded before the watcher starts.
        val mirror = File(temp.root, "clip-in-$ws/clipboard")
        assertTrue("the mirror must exist after launch", mirror.exists())
        assertEquals("already on the phone", mirror.readText())
    }

    @Test
    fun stoppingDeletesThePasteMirrorBecauseItHoldsTheUsersClipboard() = runBlocking {
        hostClipboard.text = "user secret"
        val guest = RecordingGuestFeatureHost()
        val reconciler = reconciler(repositoryWith(clipboardOnly(true)), guest)

        reconciler.launchReconcile(ws)
        val mirror = File(temp.root, "clip-in-$ws/clipboard")
        assertTrue(mirror.exists())

        reconciler.onSessionStopping(ws)

        // Deliberately different from §1.111, which leaves its shim behind: this
        // file is a copy of the user's clipboard, and a stopped workspace has no
        // business keeping one in its rootfs.
        assertFalse("the mirror must not survive the session", mirror.exists())
    }

    @Test
    fun theFeaturesAreAppliedIndependently() = runBlocking {
        // ssh fails (no package manager, no sshd) but the other three must still be
        // applied — they are unrelated capabilities.
        val guest = RecordingGuestFeatureHost().apply {
            responses["command -v sshd"] = CommandResult(1, "", "")
            responses[PackageManagerDetector.PROBE_COMMAND] = CommandResult(1, "", "")
            responses["os-release"] = CommandResult(1, "", "")
        }
        reconciler(
            repositoryWith(
                mapOf(
                    SshdProvisioner.FEATURE_SSH to true,
                    NotifySendProvisioner.FEATURE_NOTIFICATIONS to true,
                    ClipboardBridgeProvisioner.FEATURE_CLIPBOARD to true,
                    AudioBridgeProvisioner.FEATURE_AUDIO to true
                )
            ),
            guest
        ).launchReconcile(ws)

        assertTrue(guest.startedDaemons.isEmpty())
        assertTrue(guest.execCalls.any { it == NotifySendProvisioner.installCommand() })
        assertTrue(guest.execCalls.any { it == ClipboardBridgeProvisioner.installCommand() })
        assertTrue(guest.execCalls.any { it == AudioBridgeProvisioner.prepareCommand() })
    }

    // ── §1.113: audio on the same launch path ──

    @Test
    fun theAudioBridgeIsCreatedAtLaunchWhenTheToggleIsOn() = runBlocking {
        val guest = RecordingGuestFeatureHost()
        reconciler(repositoryWith(audioOnly(true)), guest).launchReconcile(ws)

        assertTrue(
            "the launch path must create the audio FIFO: ${guest.execCalls}",
            guest.execCalls.any { it == AudioBridgeProvisioner.prepareCommand() }
        )
        assertTrue(guest.execCalls.any { it == AudioBridgeProvisioner.installAlsaCommand() })
    }

    @Test
    fun audioAppliesByDefaultBecauseTheBuiltInDefaultIsOn() = runBlocking {
        // Like notifications and clipboard, and unlike ssh, this feature ships
        // enabled — so a workspace that never opened the Features tab still gets it.
        assertTrue(
            "this test assumes audio defaults to on",
            WorkspaceContent.builtinFeatureDefaults.getValue(AudioBridgeProvisioner.FEATURE_AUDIO)
        )
        val guest = RecordingGuestFeatureHost()
        reconciler(repositoryWith(emptyMap()), guest).launchReconcile(ws)

        assertTrue(guest.execCalls.any { it == AudioBridgeProvisioner.prepareCommand() })
    }

    @Test
    fun aDisabledAudioToggleCreatesNothing() = runBlocking {
        val guest = RecordingGuestFeatureHost()
        reconciler(repositoryWith(audioOnly(false)), guest).launchReconcile(ws)

        assertFalse(guest.execCalls.any { it == AudioBridgeProvisioner.prepareCommand() })
    }

    @Test
    fun aDeviceWithNoAudioOutputDoesNotBlockTheLaunch() = runBlocking {
        audioSink.available = false
        val guest = RecordingGuestFeatureHost()
        // Unsupported is not a failure and must not stop the other features.
        reconciler(
            repositoryWith(
                mapOf(
                    AudioBridgeProvisioner.FEATURE_AUDIO to true,
                    ClipboardBridgeProvisioner.FEATURE_CLIPBOARD to true
                )
            ),
            guest
        ).launchReconcile(ws)

        assertFalse(guest.execCalls.any { it == AudioBridgeProvisioner.prepareCommand() })
        assertTrue(guest.execCalls.any { it == ClipboardBridgeProvisioner.installCommand() })
    }

    @Test
    fun stoppingStopsTheAudioPumpButLeavesNothingToDelete() = runBlocking {
        val guest = RecordingGuestFeatureHost()
        val reconciler = reconciler(repositoryWith(audioOnly(true)), guest)

        reconciler.launchReconcile(ws)
        reconciler.onSessionStopping(ws)

        // Unlike §1.112's mirror there is no file holding data at rest — a FIFO's
        // bytes exist only in the kernel buffer while both ends are open — so the
        // teardown must NOT run the guest uninstall (the toggle is still on and
        // the guest may already be gone), only stop the pump.
        assertFalse(guest.execCalls.any { it == AudioBridgeProvisioner.uninstallCommand() })
    }

    // ── scope guard (§1.109's rule, re-asserted for the launch path) ──

    @Test
    fun theLaunchPathTouchesNoOtherDaemonAndNoLifecycleCommand() = runBlocking {
        val guest = RecordingGuestFeatureHost().apply {
            responses["command -v sshd"] = CommandResult(1, "", "")
            responses[PackageManagerDetector.PROBE_COMMAND] = CommandResult(0, "/usr/bin/apt-get\n", "")
            responses["os-release"] = CommandResult(0, "ID=debian\n", "")
        }
        reconciler(repositoryWith(sshOnly(true)), guest).launchReconcile(ws)

        guest.execCalls.forEach { command ->
            assertFalse(command, command.contains("reboot"))
            assertFalse(command, command.contains("shutdown"))
            assertFalse(command, command.contains("halt"))
            assertFalse(command, command.contains("Xvnc"))
        }
        assertTrue(guest.startedDaemons.all { it.first == SshdProvisioner.DAEMON_NAME })
        assertTrue(guest.stoppedDaemons.all { it == SshdProvisioner.DAEMON_NAME })
        // The detector the launch path used is the shared pure one.
        assertTrue(guest.execCalls.any { it == PackageManagerDetector.PROBE_COMMAND })
    }
}
