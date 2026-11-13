package com.fem.linexa.ui.screens.terminal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager
import com.fem.linexa.service.runtime.pty.PtySession
import com.fem.linexa.service.runtime.pty.PtySessionFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * The app-level Management Terminal (§4 Phase 2, §1.29) — a second call
 * site of the same pty mechanism as the per-workspace Terminal, but with no
 * workspace attached: it runs a host-side `/system/bin/sh` directly in a
 * `PtySession`.
 *
 * §1.43: Uses Android's system shell (`/system/bin/sh`) instead of the
 * bootstrap busybox `sh` symlink. The bootstrap binaries live in filesDir
 * where Android SELinux (W^X enforcement, targetSdk 29+) blocks execve.
 * The system shell is always available and always executable.
 *
 * Nothing here goes through RuntimeService/SessionManager — management is
 * app-level and deliberately outside the workspace session-boundary (§1.2).
 * It reuses the same [PtySessionFactory]/`RealTerminalView` machinery as the
 * workspace Terminal, so the two terminals are literally the same screen
 * surface with different backing sessions.
 *
 * 2026-08-31 (Phase 2 — exit = fresh terminal, Termux-like): the management
 * terminal is a SINGLE-terminal experience, so a real shell exit (the user
 * typed `exit`) must not strand the screen on a dead view. One exit observer
 * per session awaits [PtySession.awaitExit] (a real process exit — never a
 * redraw, focus change or activity recreation) and, for the session the
 * StateFlow still publishes:
 *
 *  1. creates the fresh replacement shell first,
 *  2. publishes it through the SAME [session] StateFlow — one atomic swap;
 *     `RealTerminalView`'s update block re-attaches the new session to the
 *     existing view (§1.145 lifetime rule), so there is no intermediate
 *     null/loading frame,
 *  3. destroys the old pty afterwards (a no-op on an already-exited child;
 *     releases pty resources),
 *  4. re-arms the observer on the replacement.
 *
 * Guards: an in-flight [replacing] flag plus a `_session.value === dead`
 * identity check make the replacement exactly-once per exit event; a
 * consecutive rapid-exit counter stops the loop when the shell dies instantly
 * on every start (fork/exec failure — the dead terminal's transcript keeps
 * the failure output visible instead of churning processes). A guard-tripped
 * screen stays recoverable: [start] on a dead session performs one manual
 * restart through the same atomic-swap path.
 */
@HiltViewModel
class ManagementTerminalViewModel @Inject constructor(
    application: Application,
    private val bootstrapManager: BootstrapManager,
    private val ptySessionFactory: PtySessionFactory
) : AndroidViewModel(application) {

    private val _session = MutableStateFlow<PtySession?>(null)
    val session: StateFlow<PtySession?> = _session.asStateFlow()

    private var sessionId: String? = null
    private var exitObserver: Job? = null

    /** U7 fix (2026-09-02): serializes start() bodies now that the bootstrap repair runs off-main. */
    private val startMutex = Mutex()

    /** Timestamp of the moment the current session's exit observer was armed. */
    private var sessionArmedAtMs: Long = 0L

    /** One replacement in flight at a time (exactly-once per exit event). */
    private var replacing = false

    /** Consecutive sub-[RAPID_EXIT_MS] lifetimes — the restart-loop guard. */
    private var rapidExits = 0

    /**
     * Starts the management shell once. Idempotent for a live session; a dead
     * session (guard-tripped restart loop, or a ViewModel kept alive across
     * the overlay being closed and reopened) is restarted once, manually,
     * through the same atomic-swap path the automatic replacement uses.
     */
    fun start() {
        // Pre-release audit fix (2026-09-02, finding U7/M-4): the whole body
        // moved into the ViewModel scope. The bootstrap self-heal below
        // (forceSetup) hashes every bundled artifact and spawns applet
        // processes — SECONDS of main-thread work when it ran synchronously
        // from the LaunchedEffect that opens the management terminal (ANR
        // risk on the first open). The heavy repair now runs on Dispatchers.IO
        // under [startMutex] (serializing what the main thread's synchronous
        // execution used to serialize); the pty construction itself stays on
        // Main per the SessionCreationDispatcher contract.
        viewModelScope.launch {
            startMutex.withLock {
                val current = _session.value
                if (current != null && current.isAlive) return@withLock

                if (current != null && !current.isAlive) {
                    exitObserver?.cancel()
                    exitObserver = null
                    current.destroy()
                    if (!bootstrapManager.isReady) {
                        withContext(Dispatchers.IO) { bootstrapManager.forceSetup() }
                    }
                    val fresh = try {
                        createSession()
                    } catch (e: Exception) {
                        Logger.e(TAG, "Management shell restart failed: ${e.message}", e)
                        return@withLock
                    }
                    sessionId = fresh.id
                    rapidExits = 0
                    _session.value = fresh
                    armExitObserver(fresh)
                    Logger.d(TAG, "Management shell restarted manually (pid=${fresh.pid})")
                    return@withLock
                }

                if (sessionId != null) return@withLock

                if (!bootstrapManager.isReady) {
                    withContext(Dispatchers.IO) { bootstrapManager.forceSetup() }
                }
                val pty = try {
                    createSession()
                } catch (e: Exception) {
                    Logger.e(TAG, "Management shell failed to start: ${e.message}", e)
                    return@withLock
                }
                sessionId = pty.id
                Logger.d("ManagementTerminal", "Management shell started (pid=${pty.pid})")
                _session.value = pty
                armExitObserver(pty)
            }
        }
    }

    /**
     * The single session-construction path (first start, automatic
     * replacement and manual restart all build the identical shell).
     * Runs on the ViewModel's Main scope — the documented main-Looper
     * contract for [PtySessionFactory] creation (§1.145).
     */
    private fun createSession(): PtySession {
        // §16.3.2: `forceSetup`, not `setup`. The `!isReady` guard has already
        // stat'd every managed binary and found the layout broken, so this is a
        // repair request, not a first-run initialization — and the memoised
        // `setup()` would answer it from a cached "converged" state and skip the
        // self-heal this call exists to trigger.
        if (!bootstrapManager.isReady) {
            bootstrapManager.forceSetup()
        }

        // §1.43: Use Android's system shell — bootstrap binaries in filesDir
        // cannot be executed due to SELinux W^X enforcement (app_data_file
        // label blocks execve on Android 10+). /system/bin/sh is always
        // available and executable.
        val sh = "/system/bin/sh"
        val filesDir = getApplication<Application>().filesDir
        val env = bootstrapManager.processEnv() +
            mapOf(
                // §1.66: management-shell PATH — bootstrap binDir first (the
                // §1.57 applet layer guarantees the ls/clear/whoami/sed/…
                // symlinks), then Android's system dirs this host shell
                // needs. Deliberately management-only: processEnv() stays
                // the closed §1.49 host env for proot/installer invocations.
                "PATH" to "${bootstrapManager.binDir.absolutePath}:/system/bin:/system/xbin:/vendor/bin",
                "TERM" to "xterm-256color",
                // §26.2 (terminal audit): both terminal surfaces render through
                // the same vendored terminal emulator, so the truecolour capability
                // hint is equally true here — see PRootLauncher.buildLaunchSpec
                // for why `TERM` cannot express it. Kept in sync with the guest
                // launcher deliberately: a management shell that advertised
                // fewer colours than a workspace shell would make the same tool
                // render differently in the two terminals of one app.
                "COLORTERM" to "truecolor",
                "HOME" to filesDir.absolutePath
            )
        return ptySessionFactory.create(
            workspaceId = "management",
            executable = sh,
            // §1.30: lone "-l" here (the backend now prepends the executable
            // path as argv[0]; see TermuxPtySession).
            args = listOf("-l"),
            env = env,
            workingDirectory = filesDir.absolutePath
        )
    }

    /**
     * Arms the one exit observer for [pty]. Cancels any previous observer
     * first (a replaced session must never drive a second replacement).
     */
    private fun armExitObserver(pty: PtySession) {
        exitObserver?.cancel()
        sessionArmedAtMs = System.currentTimeMillis()
        exitObserver = viewModelScope.launch {
            val exitCode = pty.awaitExit()
            onSessionExited(pty, exitCode)
        }
    }

    /**
     * A real shell exit. Replaces the dead session exactly once — publish
     * first, destroy after — with no intermediate null/loading frame.
     */
    private suspend fun onSessionExited(dead: PtySession, exitCode: Int) {
        // Stale observer: this pty is no longer the published session (it was
        // replaced or cleared) — it must not trigger anything.
        if (_session.value !== dead) return
        if (replacing) return
        replacing = true
        try {
            val lifetimeMs = System.currentTimeMillis() - sessionArmedAtMs
            if (lifetimeMs < RAPID_EXIT_MS) {
                rapidExits++
            } else {
                rapidExits = 0
            }
            if (rapidExits > MAX_RAPID_EXITS) {
                // The shell dies instantly on every start — a fork/exec-level
                // failure, not a user `exit`. Stop churning processes and keep
                // the last terminal (its transcript shows the failure output);
                // start() on the closed/reopened overlay performs a manual retry.
                Logger.w(
                    TAG,
                    "Management shell exited $rapidExits consecutive times within " +
                        "$RAPID_EXIT_MS ms — restart-loop guard tripped, not replacing again"
                )
                return
            }
            Logger.i(
                TAG,
                "Management shell exited (code=$exitCode, lifetime=${lifetimeMs}ms) — opening a fresh replacement"
            )
            val fresh = createSession()
            sessionId = fresh.id
            // Atomic swap: the terminal view re-attaches to the fresh session
            // in place — no null intermediate, no loading screen.
            _session.value = fresh
            // Cleanup after the swap: a no-op on the already-exited child, but
            // it releases the pty's resources deterministically.
            dead.destroy()
            armExitObserver(fresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep the dead terminal visible (its transcript holds the last
            // output); the manual restart path in start() can retry.
            Logger.e(TAG, "Replacement management shell failed to start: ${e.message}", e)
        } finally {
            replacing = false
        }
    }

    override fun onCleared() {
        exitObserver?.cancel()
        exitObserver = null
        _session.value?.destroy()
        _session.value = null
        super.onCleared()
    }

    private companion object {
        const val TAG = "ManagementTerminal"

        /**
         * A session that dies faster than this did not run an interactive
         * shell — it counts toward the restart-loop guard.
         */
        const val RAPID_EXIT_MS = 1_500L

        /** Consecutive rapid deaths tolerated before the loop guard trips. */
        const val MAX_RAPID_EXITS = 2
    }
}
