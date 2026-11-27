package com.fem.linexa.di

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.fem.linexa.core.logging.AppLogStore
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.core.security.ArchiveValidator
import com.fem.linexa.core.security.RealArchiveValidator
import com.fem.linexa.data.hostinfo.AndroidHostInfoSources
import com.fem.linexa.data.hostinfo.AndroidHostSystemInfoProvider
import com.fem.linexa.data.network.AndroidHostDnsProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import com.fem.linexa.data.resource.AndroidDeviceCpuCounterReader
import com.fem.linexa.data.resource.AndroidProcessSignals
import com.fem.linexa.data.resource.AndroidProcFsReader
import com.fem.linexa.data.resource.AndroidThreadStatReader
import com.fem.linexa.data.resource.DeviceCpuCounterReader
import com.fem.linexa.data.resource.DeviceCpuMonitor
import com.fem.linexa.data.resource.ProcFsReader
import com.fem.linexa.data.resource.ProcResourceMonitor
import com.fem.linexa.data.resource.ProcessSignals
import com.fem.linexa.data.resource.ThreadStatReader
import com.fem.linexa.domain.commands.MetadataShortcutPinsStore
import com.fem.linexa.domain.commands.ShortcutPinsStore
import com.fem.linexa.data.room.LinexaDatabase
import com.fem.linexa.data.room.WorkspaceDao
import com.fem.linexa.data.security.AndroidKeystoreSecretCipher
import com.fem.linexa.domain.security.SecretAttributeCodec
import com.fem.linexa.domain.security.SecretCipher
import com.fem.linexa.data.repository.WorkspaceDiskStore
import com.fem.linexa.data.repository.WorkspaceRepositoryImpl
import com.fem.linexa.data.repository.WorkspaceSecretAttributes
import com.fem.linexa.data.repository.WorkspaceSecretStore
import com.fem.linexa.data.settings.SettingsRepositoryImpl
import com.fem.linexa.data.version.BuildConfigAppVersionProvider
import com.fem.linexa.domain.attribution.LicenseTextLoader
import com.fem.linexa.domain.attribution.LicenseTextSource
import com.fem.linexa.domain.resource.ResourceMonitor
import com.fem.linexa.domain.version.AppVersionProvider
import com.fem.linexa.domain.workspace.WorkspaceRepository
import com.fem.linexa.domain.settings.SettingsRepository
import com.fem.linexa.domain.filemanager.FileManager
import com.fem.linexa.domain.filemanager.WorkspaceFileManagerImpl
import com.fem.linexa.domain.filemanager.WorkspaceRootfsLocator
import com.fem.linexa.domain.distro.DistroSourceResolver
import com.fem.linexa.service.distro.DistroDownloader
import com.fem.linexa.service.distro.HttpDistroDownloader
import com.fem.linexa.service.distro.LinuxContainersResolver
import com.fem.linexa.service.exporter.RootFsExporter
import com.fem.linexa.service.exporter.TarRootFsExporter
import com.fem.linexa.service.installer.AndroidProcessIdentityProvider
import com.fem.linexa.service.installer.ProcessIdentityProvider
import com.fem.linexa.service.installer.RootFsInstaller
import com.fem.linexa.service.installer.StagedRootFsInstaller
import com.fem.linexa.service.rootfs.profile.AssetRootFsProfileCatalog
import com.fem.linexa.service.rootfs.profile.RootFsProfileCatalog
import com.fem.linexa.service.update.AndroidApkMetadataReader
import com.fem.linexa.service.update.AndroidUpdateInstaller
import com.fem.linexa.service.update.ApkMetadataReader
import com.fem.linexa.service.update.ApkUpdateDownloader
import com.fem.linexa.service.update.ApkValidator
import com.fem.linexa.service.update.AppUpdateCoordinator
import com.fem.linexa.service.update.GithubReleaseApi
import com.fem.linexa.service.update.HttpApkUpdateDownloader
import com.fem.linexa.service.update.HttpGithubReleaseApi
import com.fem.linexa.service.update.PrefsUpdatePreferences
import com.fem.linexa.service.update.UpdateInstaller
import com.fem.linexa.service.update.UpdatePreferences
import com.fem.linexa.service.manager.DefaultLinexaManager
import com.fem.linexa.service.manager.LinexaManager
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.runtime.WorkspaceRootfsViewLocator
import com.fem.linexa.domain.workspace.RootfsInspector
import com.fem.linexa.service.runtime.RuntimeTrace
import com.fem.linexa.service.runtime.WorkspaceHealthRegistry
import com.fem.linexa.service.runtime.container.ContainerRuntime
import com.fem.linexa.service.runtime.container.PRootContainerRuntime
import com.fem.linexa.service.runtime.features.AndroidAudioSink
import com.fem.linexa.service.runtime.features.AndroidGuestNotificationBridge
import com.fem.linexa.service.runtime.features.AndroidHostClipboard
import com.fem.linexa.service.runtime.features.AudioBridgeProvisioner
import com.fem.linexa.service.runtime.features.AudioSink
import com.fem.linexa.service.runtime.features.ClipboardBridgeProvisioner
import com.fem.linexa.service.runtime.features.FifoAudioSource
import com.fem.linexa.service.runtime.features.GuestFeatureHost
import com.fem.linexa.service.runtime.features.GuestNotificationBridge
import com.fem.linexa.service.runtime.features.HostClipboard
import com.fem.linexa.service.runtime.features.NotifySendProvisioner
import com.fem.linexa.service.runtime.features.RuntimeGuestFeatureHost
import com.fem.linexa.service.runtime.features.WorkspaceClipboardSupervisor
import com.fem.linexa.service.runtime.features.WorkspaceAudioSupervisor
import com.fem.linexa.service.runtime.features.WorkspaceNotificationSupervisor
import com.fem.linexa.service.runtime.hostinfo.EnvTickSampler
import com.fem.linexa.service.runtime.hostinfo.HostInfoRuntimeBridge
import com.fem.linexa.domain.hostinfo.HostSystemInfoProvider
import com.fem.linexa.domain.network.HostDnsProvider
import java.io.File
import com.fem.linexa.service.runtime.pty.PtySessionFactory
import com.fem.linexa.service.runtime.pty.TermuxPtySessionFactory
import com.fem.linexa.service.runtime.wakelock.AndroidWakeLock
import com.fem.linexa.service.runtime.wakelock.HostWakeLock
import com.fem.linexa.service.runtime.wakelock.WorkspaceWakeLockManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindContainerRuntime(impl: PRootContainerRuntime): ContainerRuntime

    @Binds
    @Singleton
    abstract fun bindPtySessionFactory(impl: TermuxPtySessionFactory): PtySessionFactory

    @Binds
    @Singleton
    abstract fun bindWorkspaceRepository(impl: WorkspaceRepositoryImpl): WorkspaceRepository

    // Phase 9C (OPT-ARCH-003): the repository's two collaborators behind the
    // same interface-refinement pattern — WorkspaceFileManager serves the
    // disk primitives, WorkspaceSecretAttributes the reveal/seal/migration.
    // The DB-facing impl (and its mutex) stays WorkspaceRepositoryImpl.
    @Binds
    @Singleton
    abstract fun bindWorkspaceDiskStore(impl: WorkspaceFileManager): WorkspaceDiskStore

    @Binds
    @Singleton
    abstract fun bindWorkspaceSecretStore(impl: WorkspaceSecretAttributes): WorkspaceSecretStore

    // P11: the Keystore-backed cipher behind encrypted workspace secrets.
    @Binds
    @Singleton
    abstract fun bindSecretCipher(impl: AndroidKeystoreSecretCipher): SecretCipher

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    // What's New: the running build's identity and how it was installed. Behind an
    // interface because neither half can be read in a JVM test — BuildConfig is
    // generated per variant, and firstInstallTime/lastUpdateTime come from
    // PackageManager — so without this seam the update-detection rule could only be
    // verified by reinstalling the APK by hand.
    @Binds
    @Singleton
    abstract fun bindAppVersionProvider(impl: BuildConfigAppVersionProvider): AppVersionProvider

    @Binds
    @Singleton
    abstract fun bindResourceMonitor(impl: ProcResourceMonitor): ResourceMonitor

    // One device-CPU counter source for every screen. The monitor itself is
    // @Inject/@Singleton; only its platform reader needs an interface binding
    // so the proc-stat/cpuidle fallback ladder is JVM-testable.
    @Binds
    @Singleton
    abstract fun bindDeviceCpuCounterReader(
        impl: AndroidDeviceCpuCounterReader
    ): DeviceCpuCounterReader

    // Same seam for the app's own thread table (`/proc/<pid>/task`).
    @Binds
    @Singleton
    abstract fun bindThreadStatReader(impl: AndroidThreadStatReader): ThreadStatReader

    // App-grid redesign: the Sessions task manager's per-process kill —
    // interface/impl seam so the ViewModel's decision logic stays JVM-testable.
    @Binds
    @Singleton
    abstract fun bindProcessSignals(impl: AndroidProcessSignals): ProcessSignals

    /**
     * Host process discovery's `/proc` seam. The production reader is the
     * Android filesystem; JVM tests supply a fake table to assert the
     * inaccessible-PID and self-protection rules.
     */
    @Binds
    @Singleton
    abstract fun bindProcFsReader(impl: AndroidProcFsReader): ProcFsReader

    // App-grid redesign: the Commands grid's pinned/favorited ids persist in
    // the workspace attributes map, behind the same interface seam.
    @Binds
    @Singleton
    abstract fun bindShortcutPinsStore(impl: MetadataShortcutPinsStore): ShortcutPinsStore

    @Binds
    @Singleton
    abstract fun bindFileManager(impl: WorkspaceFileManagerImpl): FileManager

    // F-003: the host-side read fast path needs the workspace rootfs and the
    // guest paths that PRoot bind-mounts over it. The port keeps the file
    // manager in the domain layer.
    @Binds
    @Singleton
    abstract fun bindWorkspaceRootfsLocator(
        impl: WorkspaceRootfsViewLocator
    ): WorkspaceRootfsLocator

    // Phase 9A: WorkspaceLaunchValidator asks the file manager through the
    // domain port (domain.workspace.RootfsInspector), not the service type —
    // the second half of the domain→service import cycle. WorkspaceFileManager
    // is the single implementation; the validator's Hilt factory now
    // references a domain type.
    @Binds
    @Singleton
    abstract fun bindRootfsInspector(impl: WorkspaceFileManager): RootfsInspector

    @Binds
    @Singleton
    abstract fun bindRootFsInstaller(impl: StagedRootFsInstaller): RootFsInstaller

    // RFS-01/RFS-08: the Android uid/gid/supplementary-group seam. The primary
    // gid must come from Android, never from host /etc/passwd; JVM tests use
    // this interface to exercise the production fixup path without syscalls.
    @Binds
    @Singleton
    abstract fun bindProcessIdentityProvider(
        impl: AndroidProcessIdentityProvider
    ): ProcessIdentityProvider

    // § export: the RootFS backup writer, behind the same interface/impl seam
    // the installer uses — JVM tests construct [TarRootFsExporter] directly
    // against a fake validator, production binds it once here.
    @Binds
    @Singleton
    abstract fun bindRootFsExporter(impl: TarRootFsExporter): RootFsExporter

    // §1.146: the built-in distribution profiles, read from `assets/rootfs/profiles/`.
    // Behind an interface so the installer can be constructed in a JVM test with a
    // catalog backed by those same YAML files on disk — a shipped profile that stops
    // parsing then fails a unit test rather than silently degrading a real import to
    // the generic path.
    @Binds
    @Singleton
    abstract fun bindRootFsProfileCatalog(impl: AssetRootFsProfileCatalog): RootFsProfileCatalog

    @Binds
    @Singleton
    abstract fun bindDistroDownloader(impl: HttpDistroDownloader): DistroDownloader

    // In-app update checker (2026-11-16): GitHub Releases API, APK download,
    // APK validation, Android install path. All interface/impl seams — the
    // coordinator's state machine is JVM-testable against fakes, and each
    // platform surface (HTTP, PackageManager archive reads, PackageInstaller,
    // SharedPreferences) is swappable.
    @Binds
    @Singleton
    abstract fun bindGithubReleaseApi(impl: HttpGithubReleaseApi): GithubReleaseApi

    @Binds
    @Singleton
    abstract fun bindUpdatePreferences(impl: PrefsUpdatePreferences): UpdatePreferences

    @Binds
    @Singleton
    abstract fun bindApkUpdateDownloader(impl: HttpApkUpdateDownloader): ApkUpdateDownloader

    @Binds
    @Singleton
    abstract fun bindApkMetadataReader(impl: AndroidApkMetadataReader): ApkMetadataReader

    @Binds
    @Singleton
    abstract fun bindUpdateInstaller(impl: AndroidUpdateInstaller): UpdateInstaller

    @Binds
    @IntoSet
    abstract fun bindLinuxContainersResolver(impl: LinuxContainersResolver): DistroSourceResolver

    // §1.72 (Roadmap Item 6): the LinexaManager facade — one injectable seam
    // grouping BootstrapManager / InstallRecoveryManager / the distro-catalog
    // resolvers for future Manager-UI code. Purely organizational; the grouped
    // classes keep their own bindings, so existing injection sites are unchanged.
    @Binds
    @Singleton
    abstract fun bindLinexaManager(impl: DefaultLinexaManager): LinexaManager

    // §1.110: the feature layer's guest seam is now a process-wide singleton
    // rather than a capability of RuntimeBinder — the workspace launch path
    // reconciles feature toggles too, and it has no binder (an android.os.Binder
    // is also not injectable). RuntimeBinder delegates to this same instance, so
    // the Features tab and the launch path act on one host.
    @Binds
    @Singleton
    abstract fun bindGuestFeatureHost(impl: RuntimeGuestFeatureHost): GuestFeatureHost

    // §1.111: the Notifications feature's Android half. Behind an interface so
    // the spool → parse → post chain is JVM-testable against a fake bridge.
    @Binds
    @Singleton
    abstract fun bindGuestNotificationBridge(impl: AndroidGuestNotificationBridge): GuestNotificationBridge

    // §1.113: the Audio feature's Android half (AudioTrack). Behind an interface
    // so the FIFO → stream → playback chain is JVM-testable against a fake sink.
    @Binds
    @Singleton
    abstract fun bindAudioSink(impl: AndroidAudioSink): AudioSink

    @Module
    @InstallIn(SingletonComponent::class)
    object Providers {

        @Provides
        @Singleton
        fun provideSecretAttributeCodec(cipher: SecretCipher): SecretAttributeCodec =
            SecretAttributeCodec(cipher)

        /**
         * 2026-08-30 (session-lifecycle fix): the dispatcher the container
         * runtime must use to CONSTRUCT terminal sessions. The vendored
         * `TerminalSession` constructor captures the constructing thread's
         * Looper via a no-arg `Handler()` and drives ALL emulator state
         * changes through it, so the constructing thread must be the app's
         * main-Looper thread — enforced in
         * [com.fem.linexa.service.runtime.container.PRootContainerRuntime.openTerminal]
         * with `withContext(sessionCreationDispatcher)`. Provided here (rather
         * than hardcoded at the call site) so JVM tests can substitute a
         * non-Looper dispatcher; production binds
         * `Dispatchers.Main.immediate`.
         */
        @Provides
        @SessionCreationDispatcher
        fun provideSessionCreationDispatcher(): kotlinx.coroutines.CoroutineDispatcher =
            kotlinx.coroutines.Dispatchers.Main.immediate

        @Provides
        @Singleton
        fun provideLogger(application: Application): Logger {
            Logger.init(application)
            return Logger
        }

        // ── In-app update checker (2026-11-16) ─────────────────────────────

        /**
         * The update coordinator's long-lived scope: a download started in
         * Settings must survive screen recreation and navigation, so it can
         * NOT live on a ViewModel scope. Same convention as the workspace
         * supervisors above (§1.111–§1.113): dedicated
         * `SupervisorJob() + Dispatchers.IO`, injected rather than built
         * inside the coordinator so tests substitute a controllable scope.
         */
        @Provides
        @UpdateScope
        @Singleton
        fun provideAppUpdateScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * The APK validator with the app's real identity: the package name
         * from the Application (not a constant — the package IS the app) and
         * the device's OS level, so a release built for a newer Android is
         * rejected before Android's installer has to say no.
         */
        @Provides
        @Singleton
        fun provideApkValidator(
            metadataReader: ApkMetadataReader,
            @ApplicationContext context: Context
        ): ApkValidator = ApkValidator(
            metadataReader = metadataReader,
            appPackageName = context.packageName,
            deviceSdk = android.os.Build.VERSION.SDK_INT,
            expectedSignerSha256 = com.fem.linexa.service.update.ReleaseSigningIdentity.ACCEPTED_SHA256
        )

        /**
         * The coordinator itself. A @Provides factory (not constructor
         * injection) because it needs the [UpdateScope] and its private
         * updates directory (`filesDir/updates` — internal storage, no
         * storage permission, and the only place a FileProvider-free
         * PackageInstaller session can stream from).
         */
        @Provides
        @Singleton
        fun provideAppUpdateCoordinator(
            api: GithubReleaseApi,
            preferences: UpdatePreferences,
            downloader: ApkUpdateDownloader,
            validator: ApkValidator,
            installer: UpdateInstaller,
            appVersionProvider: AppVersionProvider,
            @ApplicationContext context: Context,
            @UpdateScope scope: CoroutineScope
        ): AppUpdateCoordinator = AppUpdateCoordinator(
            api = api,
            prefs = preferences,
            downloader = downloader,
            validator = validator,
            installer = installer,
            appVersionProvider = appVersionProvider,
            updatesDir = java.io.File(context.filesDir, "updates"),
            scope = scope
        )

        /**
         * UI/UX Phase 4: the read/clear half of the app log.
         *
         * A provider rather than constructor injection because [AppLogStore] takes a
         * plain [java.io.File] on purpose — that is what makes it JVM-testable
         * against a temp folder — so the `Application` → `{filesDir}/logs`
         * resolution has to happen somewhere, and it belongs next to
         * [provideLogger] which resolves the *same* directory for the sink. The two
         * must not drift: a store pointed at a different folder would report 0 B and
         * clear nothing.
         *
         * Depends on [Logger] so the directory exists before anything reads it
         * (`Logger.init` → `FileLogSink` → `mkdirs`), and so a graph that injects
         * only the store still initializes logging.
         */
        @Provides
        @Singleton
        fun provideAppLogStore(application: Application, logger: Logger): AppLogStore {
            // `logger` is required for ordering, not for its value — see the doc above.
            @Suppress("UNUSED_EXPRESSION") logger
            return AppLogStore(java.io.File(application.filesDir, "logs"))
        }

        /**
         * §1.130: reads the bundled license documents for the About screen.
         *
         * A provider because [LicenseTextLoader] deliberately takes a
         * [LicenseTextSource] lambda rather than an `AssetManager`: reading is the
         * only Android-coupled part of the attribution feature, and keeping the
         * coupling here is what makes "a missing document degrades instead of
         * crashing" assertable on the JVM. `assets.open` is the production source.
         */
        @Provides
        @Singleton
        fun provideLicenseTextLoader(application: Application): LicenseTextLoader =
            LicenseTextLoader(LicenseTextSource { path -> application.assets.open(path) })

        @Provides
        @Singleton
        fun provideArchiveValidator(impl: RealArchiveValidator): ArchiveValidator = impl

        @Provides
        @Singleton
        fun provideDatabase(application: Application): LinexaDatabase {
            return Room.databaseBuilder(
                application,
                LinexaDatabase::class.java,
                "linexa.db"
            ).build()
        }

        @Provides
        @Singleton
        fun provideWorkspaceDao(database: LinexaDatabase): WorkspaceDao {
            return database.workspaceDao()
        }

        /**
         * §1.111: the Notifications feature's supervisor.
         *
         * Two things it cannot get by constructor injection, hence a provider:
         *
         * - **Its own scope.** The spool watcher is a long-lived poll tied to a
         *   *workspace session*, not to a screen or a bound service, so it must
         *   not live on a ViewModel scope (a Features tab that closes would kill
         *   it) nor on `RuntimeService.serviceScope` (which is
         *   `Dispatchers.Main`; this is file I/O). A dedicated
         *   `SupervisorJob() + Dispatchers.IO` scope matches the existing
         *   convention (`WorkspaceTerminalManager`, `InstallCoordinator`) and
         *   the reconciler cancels each workspace's watcher explicitly on stop.
         * - **The HOST path of the guest spool.** Injected as a lambda over
         *   [WorkspaceFileManager] so the supervisor needs no `Application` and
         *   stays JVM-testable against a temp folder.
         */
        @Provides
        @Singleton
        fun provideWorkspaceNotificationSupervisor(
            guestFeatureHost: GuestFeatureHost,
            notificationBridge: GuestNotificationBridge,
            fileManager: WorkspaceFileManager
        ): WorkspaceNotificationSupervisor = WorkspaceNotificationSupervisor(
            host = guestFeatureHost,
            bridge = notificationBridge,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            spoolDirFor = { workspaceId ->
                java.io.File(
                    fileManager.getRootfsPath(workspaceId),
                    NotifySendProvisioner.SPOOL_RELATIVE_PATH
                )
            }
        )

        /**
         * §1.112: the Clipboard feature's Android half.
         *
         * A provider rather than a `@Binds` because the clip *label* is a
         * constructor parameter: some system UIs surface it ("Copied from …"),
         * and it is the only signal a user gets that a clip came from inside a
         * workspace — which matters because a guest can write the clipboard
         * without the user acting.
         */
        @Provides
        @Singleton
        fun provideHostClipboard(application: Application): HostClipboard =
            AndroidHostClipboard(application)

        /**
         * §1.112: the Clipboard feature's supervisor.
         *
         * Provided (not constructor-injected) for the same two reasons as
         * §1.111's notification supervisor: it needs **its own long-lived
         * `SupervisorJob() + Dispatchers.IO` scope** (the bridge is tied to a
         * workspace *session*, so a closed Features tab must not kill it, and
         * `RuntimeService.serviceScope` is `Dispatchers.Main` while this is file
         * I/O), and it needs the HOST paths of the guest bridge as lambdas over
         * [WorkspaceFileManager] so it stays JVM-testable against a temp folder.
         *
         * Singleton, and shared with the Features tab, so a toggle tap can never
         * create a second poller draining one workspace's spool — and so the
         * single process-wide clipboard change listener has exactly one owner.
         */
        @Provides
        @Singleton
        fun provideWorkspaceClipboardSupervisor(
            guestFeatureHost: GuestFeatureHost,
            hostClipboard: HostClipboard,
            fileManager: WorkspaceFileManager
        ): WorkspaceClipboardSupervisor = WorkspaceClipboardSupervisor(
            host = guestFeatureHost,
            clipboard = hostClipboard,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            outDirFor = { workspaceId ->
                java.io.File(
                    fileManager.getRootfsPath(workspaceId),
                    ClipboardBridgeProvisioner.OUT_RELATIVE_PATH
                )
            },
            mirrorFileFor = { workspaceId ->
                java.io.File(
                    fileManager.getRootfsPath(workspaceId),
                    ClipboardBridgeProvisioner.IN_FILE_RELATIVE_PATH
                )
            }
        )

        /**
         * §1.113: the Audio feature's supervisor.
         *
         * Provided (not constructor-injected) for the same two reasons as
         * §1.111/§1.112: it needs **its own long-lived `SupervisorJob() +
         * Dispatchers.IO` scope**, and it needs the HOST path of the guest FIFO
         * as a lambda over [WorkspaceFileManager] so it stays JVM-testable.
         *
         * The scope matters more here than for the other two: the pump spends
         * essentially all of its time *blocked* in a FIFO read waiting for a guest
         * writer, so it must be on `Dispatchers.IO` (a blocking-friendly pool),
         * never on `RuntimeService.serviceScope`, which is `Dispatchers.Main` —
         * that would freeze the UI thread for as long as no workspace played
         * anything.
         *
         * Singleton, and shared with the Features tab, so a toggle tap can never
         * create a second reader on one workspace's FIFO — two readers would not
         * merely waste a thread, they would split the PCM stream between them and
         * play garbage.
         */
        @Provides
        @Singleton
        fun provideWorkspaceAudioSupervisor(
            guestFeatureHost: GuestFeatureHost,
            audioSink: AudioSink,
            fileManager: WorkspaceFileManager
        ): WorkspaceAudioSupervisor = WorkspaceAudioSupervisor(
            host = guestFeatureHost,
            sink = audioSink,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            sourceFor = { workspaceId ->
                FifoAudioSource(
                    java.io.File(
                        fileManager.getRootfsPath(workspaceId),
                        AudioBridgeProvisioner.SINK_RELATIVE_PATH
                    )
                )
            }
        )
        /**
         * §1.142: the Workspace-runtime wake lock (§1.100(5)).
         *
         * A provider for the same two reasons as §1.111–§1.113's supervisors: it
         * needs **its own long-lived scope** and it needs an injected clock.
         *
         * The scope must not be `RuntimeService.serviceScope`. That scope is
         * cancelled in `onDestroy`, which would cancel the expiry timer of a hold
         * the platform still holds — the one state this feature must never reach.
         * `Dispatchers.Default` rather than `IO`: the coroutine only sleeps and
         * then calls `release()`, so it needs no blocking-friendly pool.
         *
         * Singleton because the lock is process-wide by construction
         * ([WakeLockPolicy] rule 1 — at most one holder, since §3.2 runs exactly
         * one workspace at a time). Two instances would each track their own
         * holder against one platform lock, so a release by either would drop the
         * other's hold while its bookkeeping still claimed it.
         */
        @Provides
        @Singleton
        fun provideHostWakeLock(application: Application): HostWakeLock =
            AndroidWakeLock(application)

        @Provides
        @Singleton
        fun provideWorkspaceWakeLockManager(hostWakeLock: HostWakeLock): WorkspaceWakeLockManager =
            WorkspaceWakeLockManager(
                wakeLock = hostWakeLock,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            )

        /**
         * 2026-09 GPU work: the Workspace GPU feature's supervisor — the
         * per-workspace owner of the host `virgl_test_server_android` process.
         *
         * Provided (not constructor-injected) for the same reasons as the
         * §1.111–§1.113 supervisors: it needs **its own long-lived
         * `SupervisorJob() + Dispatchers.IO` scope** (the exit watcher is a
         * process-lifetime poll that must survive any screen, and a
         * `RuntimeService.serviceScope` cancel on service destroy must not
         * kill the teardown bookkeeping), and its workspace log directory
         * comes as a lambda over [WorkspaceFileManager] so the class stays
         * JVM-testable against a temp folder — tests construct it directly
         * with a fake [GpuProcessFactory].
         */
        @Provides
        @Singleton
        fun provideWorkspaceGpuSupervisor(
            runtimeTrace: RuntimeTrace,
            healthRegistry: WorkspaceHealthRegistry,
            application: Application,
            fileManager: WorkspaceFileManager
        ): com.fem.linexa.service.runtime.graphics.WorkspaceGpuSupervisor =
            com.fem.linexa.service.runtime.graphics.WorkspaceGpuSupervisor(
                runtimeTrace = runtimeTrace,
                healthRegistry = healthRegistry,
                nativeLibraryDir = application.applicationInfo.nativeLibraryDir,
                processFactory = com.fem.linexa.service.runtime.graphics.DefaultGpuProcessFactory(),
                logDirFor = { workspaceId ->
                    java.io.File(fileManager.getWorkspaceBasePath(workspaceId), "logs")
                },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            )

        // ── Host System Information (2026-08-26 track) ─────────────────────
        //
        // The single source of truth for Android-host facts, plus the bridge
        // every RootFS launch path consults. Provided (not @Inject-ctor) because
        // both take an injectable wall clock / plain deps rather than leaking
        // Application into more classes than the module already resolves; the
        // defaults (IO dispatcher, System::currentTimeMillis) are overridden by
        // tests that build these types directly.

        @Provides
        @Singleton
        fun provideHostSystemInfoProvider(
            application: Application,
            deviceCpuMonitor: DeviceCpuMonitor
        ): HostSystemInfoProvider {
            return AndroidHostSystemInfoProvider(
                // Share the process-wide monitor: Device Info then shows the
                // same device CPU sample as Activity/Sessions/Task Manager,
                // instead of advancing a private baseline of its own.
                sources = AndroidHostInfoSources(application, deviceCpuMonitor)
            )
        }

        /**
         * Transformation spec §19/§20: the host's live DNS resolver topology,
         * backing `DnsMode.SYSTEM` provisioning. Bound as an interface so the
         * network provisioner stays JVM-testable against a fake.
         */
        @Provides
        @Singleton
        fun provideHostDnsProvider(@ApplicationContext context: Context): HostDnsProvider {
            return AndroidHostDnsProvider(context)
        }

        /**
         * 2026-09-03 (issue #11): production byte source for the bundled
         * RootFS metadata — the app's own assets. The class takes the source
         * as a constructor seam so JVM tests can point the SAME pipeline at
         * the shipped files on disk (the schema a test validates is the one
         * an install consumes).
         */
        @Provides
        @Singleton
        fun provideBundledRootfsMetadataProvider(
            @ApplicationContext context: Context
        ): com.fem.linexa.service.rootfs.BundledRootfsMetadataProvider {
            return com.fem.linexa.service.rootfs.BundledRootfsMetadataProvider { fileName ->
                try {
                    context.assets.open(
                        com.fem.linexa.service.rootfs.BundledRootfsMetadataProvider.ASSET_DIR + fileName
                    ).use { it.readBytes() }
                } catch (e: java.io.FileNotFoundException) {
                    null
                } catch (e: Exception) {
                    Logger.w("BundledRootfsMeta", "bundled metadata read failed for $fileName: ${e.message}")
                    null
                }
            }
        }

        /**
         * Quick Commands re-architecture: the production byte source for the
         * ONE built-in Quick Commands catalog — the app's own assets
         * (`commands/quick_commands.yaml`). The loader takes the source as a
         * constructor seam so JVM tests read the very same shipped file; the
         * catalog a test validates is the one the Commands screen consumes.
         */
        @Provides
        @Singleton
        fun provideQuickCommandsCatalogSource(
            @ApplicationContext context: Context
        ): com.fem.linexa.service.commands.QuickCommandsCatalogSource {
            return com.fem.linexa.service.commands.QuickCommandsCatalogSource {
                try {
                    context.assets.open(
                        com.fem.linexa.service.commands.QuickCommandsCatalogLoader.ASSET_PATH
                    ).use { it.readBytes() }
                } catch (e: java.io.FileNotFoundException) {
                    null
                } catch (e: Exception) {
                    Logger.w("QuickCommandsCatalog", "catalog read failed: ${e.message}")
                    null
                }
            }
        }

        @Provides
        @Singleton
        fun provideHostInfoRuntimeBridge(
            hostSystemInfoProvider: HostSystemInfoProvider,
            fileManager: WorkspaceFileManager,
            sessionManager: com.fem.linexa.service.runtime.SessionManager
        ): HostInfoRuntimeBridge {
            return HostInfoRuntimeBridge(
                hostSystemInfoProvider = hostSystemInfoProvider,
                fileManager = fileManager,
                // Issue #9 (2026-09-03): workspace-relative uptime. The real
                // recorded session start (SessionManager) is the source; a
                // running workspace's /proc/uptime and hostinfo feed report
                // how long THIS environment has been up, refreshed live by
                // the overlay ticker. No session → host uptime, unchanged.
                sessionStartProvider = { workspaceId ->
                    sessionManager.getSession(workspaceId)?.startedAt
                },
                // LXCFS-style environment accounting for the restricted-host
                // /proc/stat mode. CLK_TCK from bionic; the conventional 100
                // fallback covers exotic sysconf refusals (never fabricates a
                // different tick rate silently — 100 IS the bionic constant).
                envTickSampler = EnvTickSampler(
                    procDir = File("/proc"),
                    clockTicksPerSecond = runCatching {
                        android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK)
                    }.getOrNull()?.takeIf { it > 0 } ?: 100L,
                    nowMs = { System.nanoTime() / 1_000_000L }
                )
            )
        }
    }
}
