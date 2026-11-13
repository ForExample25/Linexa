package com.fem.linexa.service.runtime.features

import com.fem.linexa.core.logging.Logger
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * §1.112 — the guest-side consumer of the `clipboard` workspace feature toggle:
 * a copy inside the workspace reaches the Android clipboard, and a paste inside
 * the workspace yields what the user copied on the phone.
 *
 * **Reconciliation, not event handling** — the same contract as
 * [WorkspaceFeatureSupervisor] (§1.109) and [WorkspaceNotificationSupervisor]
 * (§1.111). [reconcileClipboard] takes the *desired* state and converges to it,
 * so it is correct on a toggle tap, on workspace launch (§1.110), and after a
 * `config.yaml` import that pre-enabled the feature; calling it twice is
 * harmless. Nothing here is authoritative state — desired = the persisted toggle,
 * actual = "are the shims installed / is a watcher running".
 *
 * **Two directions, two mechanisms** (the rationale is in
 * [ClipboardBridgeProvisioner]'s kdoc): guest→host is a *spool* of copy records
 * that this class drains, host→guest is a single *mirror* file this class keeps
 * current. Both live inside the rootfs, so — as in §1.111 — there is no socket,
 * no port and no bind mount, and the guest's entire capability is reading and
 * writing files in one directory.
 *
 * **Why the mirror is refreshed by a poll *and* a change listener.** Android
 * only lets an app read the clipboard while it has window focus (API 29+), so a
 * `OnPrimaryClipChangedListener` alone is not sufficient — the interesting copy
 * usually happens in *another* app, and the callback that fires while Linexa is
 * backgrounded reads null. The listener therefore captures the case where the
 * clipboard changes while Linexa is foregrounded, and the poll re-reads on the
 * same tick as the spool drain (one `list()` plus one clipboard read) to pick up
 * whatever happened while it was not. A null read never clears the mirror: the
 * common cause is the focus restriction, and blanking a guest's paste buffer
 * because Android declined to answer would be worse than a slightly stale one.
 *
 * **It cannot touch the Workspace lifecycle.** Its collaborators are
 * [GuestFeatureHost] (exec only — §1.109's scope limit) and [HostClipboard]
 * (primary-clip text only). There is no path from here to a pty, a terminal, or
 * a session start/stop (§1.2: the graph owns that).
 *
 * **Fail-open.** Every failure becomes a [ClipboardFeatureState] and a log line;
 * nothing throws except [CancellationException].
 */
class WorkspaceClipboardSupervisor(
    private val host: GuestFeatureHost,
    private val clipboard: HostClipboard,
    private val scope: CoroutineScope,
    /**
     * Resolves the HOST view of a workspace's copy spool
     * (`{workspace}/rootfs/var/spool/linexa-clip/out`). Injected rather than
     * derived so this class needs no Android `Application` and stays
     * JVM-testable against a temp folder.
     */
    private val outDirFor: (String) -> File,
    /**
     * Resolves the HOST view of a workspace's paste mirror
     * (`{workspace}/rootfs/var/spool/linexa-clip/in/clipboard`).
     */
    private val mirrorFileFor: (String) -> File,
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MS
) {

    /** Live watchers, keyed by workspace. Guarded by [stateLock]. */
    private val watchers = mutableMapOf<String, Job>()

    /**
     * Workspaces whose bridge is currently enabled. The clipboard is
     * *process-wide* state while a mirror is *per workspace*, so a single
     * primary-clip change must fan out to every enabled workspace — this set is
     * that fan-out list, and it is also what decides whether the change listener
     * is registered at all.
     */
    private val enabledWorkspaces = linkedSetOf<String>()

    private val stateLock = Any()

    /** Per-workspace drain locks — see [WorkspaceNotificationSupervisor]. */
    private val drainLocks = mutableMapOf<String, Any>()

    /**
     * Per-workspace *mirror* locks, separate from [drainLocks] because they guard
     * opposite directions and must not serialize against each other (a drain and a
     * mirror refresh legitimately happen on the same poll tick).
     *
     * These exist to close a real race, not for tidiness: coroutine cancellation
     * is cooperative, so [stopWatching] cancelling the poller does **not**
     * guarantee that a poller already inside [writeMirror] has finished. Without
     * this lock a watcher could commit the mirror *after* [clearMirror] deleted
     * it, leaving a copy of the user's clipboard in the rootfs of a workspace
     * whose Clipboard feature is off — precisely what §1.112 forbids. Every mirror
     * write and every mirror delete therefore happens under this lock, and a write
     * additionally re-checks that the workspace is still enabled.
     */
    private val mirrorLocks = mutableMapOf<String, Any>()

    /**
     * Last text written to each workspace's mirror, so an unchanged clipboard
     * costs no file write. Purely an optimization: correctness comes from the
     * write-then-rename in [writeMirror].
     */
    private val mirrored = mutableMapOf<String, String>()

    /**
     * Bumped by every [clearMirror]. A mirror write reads this before it starts
     * and, under [mirrorLocks], refuses to commit if it changed meanwhile — that
     * is what stops an in-flight poller from resurrecting a mirror the disable
     * path just deleted.
     */
    private val mirrorEpochs = mutableMapOf<String, Int>()

    private fun drainLockFor(workspaceId: String): Any =
        synchronized(stateLock) { drainLocks.getOrPut(workspaceId) { Any() } }

    private fun mirrorLockFor(workspaceId: String): Any =
        synchronized(stateLock) { mirrorLocks.getOrPut(workspaceId) { Any() } }

    private fun mirrorEpochOf(workspaceId: String): Int =
        synchronized(stateLock) { mirrorEpochs[workspaceId] ?: 0 }

    /**
     * Converge [workspaceId]'s clipboard bridge to [enabled].
     *
     * Enabling installs (or refreshes) the guest shims, seeds the mirror and
     * starts the watcher; disabling removes the shims, drains what the guest
     * already copied, stops the watcher and deletes the mirror.
     *
     * @param aliases which of [ClipboardBridgeProvisioner.ALIASES] to provide in
     *   the guest (UI/UX Phase 6 — the workspace's persisted
     *   [FeatureSettings.selectedAliases]). Defaults to the whole catalog, the
     *   pre-Phase-6 behaviour.
     *
     *   Only *installation* is filtered. The **uninstall** path deliberately
     *   still sweeps the entire catalog
     *   ([ClipboardBridgeProvisioner.uninstallCommand]), because a name the user
     *   just deselected is exactly the shim that has to be removed — filtering
     *   the removal by the current selection would strand every alias the user
     *   ever turned off. That is safe to do unconditionally because the sweep is
     *   already content-guarded on the §1.112 marker, so it can only ever delete
     *   a shim Linexa wrote.
     */
    suspend fun reconcileClipboard(
        workspaceId: String,
        enabled: Boolean,
        aliases: List<ClipboardAlias> = ClipboardBridgeProvisioner.ALIASES
    ): ClipboardFeatureState = try {
        if (!enabled) disable(workspaceId) else enable(workspaceId, aliases)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.e(TAG, "Clipboard reconcile failed for $workspaceId (enabled=$enabled)", e)
        ClipboardFeatureState.Failed(e.message ?: "Unknown error")
    }

    private suspend fun enable(
        workspaceId: String,
        aliasSelection: List<ClipboardAlias>
    ): ClipboardFeatureState {
        if (!host.hasActiveSession(workspaceId)) {
            // Not a failure — the toggle stays on and §1.110's launch reconcile
            // installs the shims once a guest exists.
            return ClipboardFeatureState.Unavailable
        }

        val install = host.execCommand(workspaceId, ClipboardBridgeProvisioner.installCommand())
        if (install.exitCode != 0) {
            val detail = install.error.ifBlank { install.output }.trim().take(DETAIL_CHARS)
            Logger.e(TAG, "clipboard shim install failed for $workspaceId (rc=${install.exitCode}): $detail")
            return ClipboardFeatureState.Failed("Could not install the clipboard bridge: $detail")
        }

        // Aliases are best-effort and individually guarded: each one is skipped
        // in the guest when a real tool of that name already exists (so a
        // desktop's working xclip is never shadowed), and one alias failing must
        // not cost the others or the canonical shims that already installed.
        //
        // Phase 6: the set iterated is the workspace's selection, not the whole
        // catalog. A deselected alias is simply never installed here; removing
        // one already present is the uninstall sweep's job (see the KDoc).
        val aliases = mutableListOf<String>()
        aliasSelection.forEach { alias ->
            val result = runCatching {
                host.execCommand(workspaceId, ClipboardBridgeProvisioner.aliasInstallCommand(alias))
            }.getOrNull()
            if (result?.exitCode == 0) {
                aliases += alias.name
            } else {
                Logger.w(TAG, "$workspaceId: clipboard alias ${alias.name} not installed (rc=${result?.exitCode})")
            }
        }

        // Seed the mirror before the watcher starts so the very first paste in a
        // freshly launched workspace already has the user's clipboard.
        mirrorOnce(workspaceId)
        synchronized(stateLock) { enabledWorkspaces += workspaceId }
        syncChangeListener()
        startWatcher(workspaceId)

        Logger.i(TAG, "Clipboard bridge enabled for $workspaceId (aliases: ${aliases.joinToString().ifEmpty { "none" }})")
        return ClipboardFeatureState.Active(aliases)
    }

    /**
     * Remove the guest shims, stop watching, and delete the mirror. Idempotent —
     * correct for "already off", which is what makes it safe to call on every
     * teardown and what reaps shims left behind by a toggle flipped off while the
     * app was dead.
     */
    private suspend fun disable(workspaceId: String): ClipboardFeatureState {
        stopWatching(workspaceId)
        // Drain what the guest already copied before tearing the shims out, so a
        // copy made moments before the toggle flipped still reaches the user.
        drainOnce(workspaceId)
        if (host.hasActiveSession(workspaceId)) {
            host.execCommand(workspaceId, ClipboardBridgeProvisioner.uninstallCommand())
        }
        // Belt-and-braces: the uninstall already removes the mirror, but it is
        // skipped when the guest is gone and the mirror holds the USER's
        // clipboard text — it must not survive the feature being turned off.
        clearMirror(workspaceId)
        Logger.i(TAG, "Clipboard bridge disabled for $workspaceId (shims removed, mirror cleared)")
        return ClipboardFeatureState.Disabled
    }

    /**
     * Release [workspaceId]'s host-side state without touching the guest.
     *
     * Called from the launch path's teardown (§1.110's `onSessionStopping`): by
     * then the guest may already be gone, so the uninstall would only log a
     * failure. The watcher must stop and — unlike §1.111, which deliberately
     * leaves its shim in place — the **mirror must be deleted**, because it holds
     * a copy of the user's clipboard and a stopped workspace has no reason to
     * keep one lying in its rootfs. The shims themselves stay for the next
     * session; they carry no data.
     */
    fun releaseHostState(workspaceId: String) {
        stopWatching(workspaceId)
        clearMirror(workspaceId)
    }

    private fun stopWatching(workspaceId: String) {
        val job = synchronized(stateLock) {
            enabledWorkspaces -= workspaceId
            mirrored.remove(workspaceId)
            watchers.remove(workspaceId)
        }
        job?.cancel()
        syncChangeListener()
    }

    /**
     * Start (or keep) the watcher for [workspaceId]. Idempotent: an
     * already-running watcher is left alone rather than replaced, so a repeated
     * enable cannot end up with two pollers draining the same spool.
     */
    private fun startWatcher(workspaceId: String) {
        synchronized(stateLock) {
            val existing = watchers[workspaceId]
            if (existing != null && existing.isActive) return
            watchers[workspaceId] = scope.launch(Dispatchers.IO) {
                Logger.i(TAG, "Watching clipboard bridge for $workspaceId")
                while (isActive) {
                    drainOnce(workspaceId)
                    // Same tick as the drain: the change listener cannot see a
                    // copy made while Linexa was backgrounded (API 29+ denies
                    // the read), so the poll is what eventually picks it up.
                    mirrorOnce(workspaceId)
                    delay(pollIntervalMillis)
                }
            }
        }
    }

    /**
     * Number of live watchers for [workspaceId] — 0 or 1, never more. Visible for
     * tests: "enabling twice does not start a second poller" is a resource
     * invariant that cannot be observed through clipboard writes.
     */
    internal fun activeWatcherCount(workspaceId: String): Int =
        synchronized(stateLock) { watchers[workspaceId]?.takeIf { it.isActive } }?.let { 1 } ?: 0

    /**
     * Register the change listener exactly when at least one workspace has the
     * bridge enabled, and drop it otherwise. Idempotent, and the reason
     * [HostClipboard.setChangeListener] takes a single listener: the clipboard is
     * process-wide, so one observer fans out to every enabled workspace rather
     * than each workspace racing to write its own mirror.
     */
    private fun syncChangeListener() {
        val shouldObserve = synchronized(stateLock) { enabledWorkspaces.isNotEmpty() }
        runCatching {
            if (shouldObserve) {
                clipboard.setChangeListener { onPrimaryClipChanged() }
            } else {
                clipboard.setChangeListener(null)
            }
        }.onFailure { Logger.w(TAG, "could not update the clipboard observer: ${it.message}") }
    }

    /**
     * The host clipboard changed — mirror it into every enabled workspace.
     *
     * Visible for tests: this is the host→guest half's fast path, and driving it
     * directly is what makes "a copy on the phone becomes a paste in the guest"
     * assertable without a live poller or an Android callback.
     */
    internal fun onPrimaryClipChanged() {
        val targets = synchronized(stateLock) { enabledWorkspaces.toList() }
        targets.forEach { mirrorOnce(it) }
    }

    /**
     * Refresh [workspaceId]'s mirror from the host clipboard.
     *
     * A null/blank read leaves the mirror **untouched**: on API 29+ the usual
     * reason is that Linexa does not have focus, and blanking the guest's paste
     * buffer because Android declined to answer would turn a working paste into
     * an empty one. The mirror is only ever replaced by real content, or removed
     * outright when the feature is turned off ([clearMirror]).
     */
    internal fun mirrorOnce(workspaceId: String) {
        // Read the epoch BEFORE the clipboard read: everything from here on is
        // work that a concurrent disable must be able to invalidate.
        val epoch = mirrorEpochOf(workspaceId)
        val text = try {
            ClipboardBridgeProvisioner.boundForGuest(clipboard.read())
        } catch (e: Exception) {
            Logger.d(TAG, "$workspaceId: clipboard read failed: ${e.message}")
            null
        } ?: return

        val unchanged = synchronized(stateLock) { mirrored[workspaceId] == text }
        if (unchanged) return

        val target = try {
            mirrorFileFor(workspaceId)
        } catch (e: Exception) {
            Logger.w(TAG, "$workspaceId: could not resolve the clipboard mirror: ${e.message}")
            return
        }
        synchronized(mirrorLockFor(workspaceId)) {
            // A [clearMirror] that landed while this poller was reading the host
            // clipboard means the feature is off now; committing would put the
            // user's clipboard back into a workspace that must not hold it.
            if (mirrorEpochOf(workspaceId) != epoch) {
                Logger.d(TAG, "$workspaceId: dropping a stale clipboard mirror write")
                return
            }
            if (writeMirror(target, text)) {
                synchronized(stateLock) { mirrored[workspaceId] = text }
            }
        }
    }

    /**
     * Write [text] to [target] as an atomic replacement.
     *
     * Write-then-rename, mirroring the guest shim's own commit discipline: a
     * paste is a `cat` of this file, so a guest must never be able to observe it
     * half-written. The temp file is a sibling (same directory, hence the same
     * filesystem) because a cross-device rename would silently fall back to a
     * copy.
     */
    private fun writeMirror(target: File, text: String): Boolean {
        return try {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "${target.name}${ClipboardBridgeProvisioner.RECORD_TEMP_EXTENSION}")
            temp.writeBytes(text.toByteArray(Charsets.UTF_8))
            if (!temp.renameTo(target)) {
                // Some filesystems refuse a rename onto an existing file.
                target.delete()
                if (!temp.renameTo(target)) {
                    temp.delete()
                    Logger.w(TAG, "could not commit the clipboard mirror at ${target.path}")
                    return false
                }
            }
            true
        } catch (e: Exception) {
            Logger.w(TAG, "clipboard mirror write failed at ${target.path}: ${e.message}")
            false
        }
    }

    /**
     * Delete [workspaceId]'s mirror. Called when the feature is disabled and when
     * a session ends: the mirror holds the *user's* clipboard text, which is
     * exactly the data that must not sit in a workspace's rootfs once the
     * workspace has no business reading it.
     */
    private fun clearMirror(workspaceId: String) {
        synchronized(mirrorLockFor(workspaceId)) {
            synchronized(stateLock) {
                mirrorEpochs[workspaceId] = (mirrorEpochs[workspaceId] ?: 0) + 1
                mirrored.remove(workspaceId)
            }
            runCatching {
                val file = mirrorFileFor(workspaceId)
                if (file.exists()) file.delete()
            }.onFailure { Logger.w(TAG, "$workspaceId: could not clear the clipboard mirror: ${it.message}") }
        }
    }

    /**
     * Consume every complete copy record in [workspaceId]'s spool and put the
     * newest one on the host clipboard. Visible for tests — this is the whole
     * guest→host half.
     *
     * **Only the last record wins**, deliberately: a clipboard holds one value,
     * so writing each record in turn would flash intermediate content through the
     * system clipboard (and through any clipboard-history UI) for no benefit.
     * Every record is still consumed and deleted, whether or not it parsed: an
     * undecodable record will never become decodable, so keeping it would make
     * the poll leak forever. Deletion is also what bounds the spool.
     */
    fun drainOnce(workspaceId: String) {
        val dir = try {
            outDirFor(workspaceId)
        } catch (e: Exception) {
            Logger.w(TAG, "$workspaceId: could not resolve the clipboard spool: ${e.message}")
            return
        }
        if (!dir.isDirectory) return

        // Serialized per workspace: the poll tick and an explicit drain (from
        // disable, or a UI toggle) can otherwise both read the same record before
        // either deletes it.
        val text = synchronized(drainLockFor(workspaceId)) {
            val records = dir.listFiles { file ->
                file.isFile && file.name.endsWith(ClipboardBridgeProvisioner.RECORD_EXTENSION)
            } ?: return

            // Oldest first, bounded per drain so one runaway producer cannot
            // monopolize the poll (the remainder is picked up next tick).
            records.sortedBy { it.lastModified() }
                .take(MAX_RECORDS_PER_DRAIN)
                .fold<File, String?>(null) { newest, file -> consumeRecord(workspaceId, file) ?: newest }
        } ?: return

        try {
            clipboard.write(text)
            Logger.d(TAG, "$workspaceId: guest copy placed on the host clipboard (${text.length} chars)")
        } catch (e: Exception) {
            // The record is already gone; a refused write must not loop.
            Logger.w(TAG, "$workspaceId: host clipboard write failed: ${e.message}")
        }
    }

    /** Reads and deletes one record, returning its sanitized text or null. */
    private fun consumeRecord(workspaceId: String, file: File): String? = try {
        // Read before delete so a failure can't apply the same record twice;
        // delete before writing the clipboard so a refused write can't loop.
        val bytes = if (file.length() > ClipboardBridgeProvisioner.MAX_CLIP_BYTES) {
            Logger.w(TAG, "$workspaceId: dropping oversized clipboard record (${file.length()} bytes)")
            null
        } else {
            file.readBytes()
        }
        file.delete()
        bytes?.let { ClipboardBridgeProvisioner.decodeRecord(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Never let one bad record stop the drain (or the workspace).
        Logger.w(TAG, "$workspaceId: clipboard record ${file.name} failed: ${e.message}")
        runCatching { file.delete() }
        null
    }

    private companion object {
        const val TAG = "ClipboardSupervisor"

        /** Bounded length of a guest error tail carried into a UI reason. */
        const val DETAIL_CHARS = 300

        /**
         * Poll interval. A tick is one `list()` of a normally empty directory
         * plus one clipboard read, and a clipboard round-trip has to feel
         * immediate to be usable, so 1s matches §1.111's spool poll.
         */
        const val DEFAULT_POLL_INTERVAL_MS = 1_000L

        /**
         * Records consumed per tick. A guest looping on `xclip` is misbehaving;
         * a bounded slice keeps the poll's worst case bounded.
         */
        const val MAX_RECORDS_PER_DRAIN = 32
    }
}

/**
 * §1.112 — observable state of a workspace's Clipboard feature. Separate from
 * the persisted toggle for the same reason [SshFeatureState] and
 * [NotificationFeatureState] are (§1.16's rule at feature scope): the toggle is
 * the user's *intent*, this is the actual condition.
 */
sealed interface ClipboardFeatureState {

    /** The toggle is off: no shims in the guest, no mirror, nothing watching. */
    data object Disabled : ClipboardFeatureState

    /**
     * The toggle is on but the workspace session isn't running, so there is no
     * guest to install the shims into. Not an error — §1.110's launch reconcile
     * handles it once a session exists.
     */
    data object Unavailable : ClipboardFeatureState

    /**
     * Shims installed and the bridge is live. [aliases] are the extra command
     * names that were provided (a name already taken by a real tool is skipped,
     * so this is genuinely variable per workspace) — surfaced so the UI can tell
     * the user which commands actually work rather than promising `xclip`
     * everywhere.
     */
    data class Active(val aliases: List<String> = emptyList()) : ClipboardFeatureState

    /** Enabling failed; [reason] is user-facing. */
    data class Failed(val reason: String) : ClipboardFeatureState
}
