package com.fem.linexa.service.runtime.features

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.fem.linexa.core.logging.Logger

/**
 * §1.112 — the Android half of the Workspace Clipboard feature.
 *
 * Kept behind [HostClipboard] so [WorkspaceClipboardSupervisor]'s mirror/spool
 * logic stays JVM-testable, exactly as [AndroidGuestNotificationBridge] does for
 * §1.111.
 *
 * Design decisions (recorded in `Linexa-Roadmap.md` §1.112):
 *
 * - **Text only, and text is *extracted*, never handed over.** [read] goes
 *   through `ClipData.Item.coerceToText`, which yields the item's text or a URI's
 *   *string form* — it deliberately never resolves a `content://` URI. Resolving
 *   one would read another app's provider through Linexa's identity and hand the
 *   result to code running in a workspace; a clipboard bridge must move text, not
 *   grants.
 *
 * - **A failed read is expected, not exceptional.** From API 29 Android returns
 *   null (and logs a denial) unless the app has window focus or is the default
 *   IME. Every accessor is therefore null-tolerant and non-throwing, and the
 *   supervisor treats null as "nothing new to mirror" rather than as an error —
 *   see [WorkspaceClipboardSupervisor]'s "never clear the mirror on a null read"
 *   rule.
 *
 * - **The clip written for a guest copy is labelled.** `ClipData.newPlainText`
 *   takes a label that some system UIs surface ("Copied from …"); using the
 *   workspace's name rather than a generic string is the only signal the user
 *   gets that the content came from inside a workspace, which matters because a
 *   guest can write the clipboard without the user acting.
 */
class AndroidHostClipboard(
    private val application: Application,
    /**
     * Label for clips this bridge writes. A supplier rather than a constant so
     * the workspace that produced a copy can name it — the supervisor knows
     * which workspace drained the record, this class does not.
     */
    private val clipLabel: () -> CharSequence = { DEFAULT_LABEL }
) : HostClipboard {

    private val manager: ClipboardManager?
        get() = try {
            application.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        } catch (e: Exception) {
            Logger.w(TAG, "clipboard service unavailable: ${e.message}")
            null
        }

    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null

    override fun read(): String? {
        val clip = try {
            manager?.primaryClip
        } catch (e: Exception) {
            // API 29+ denies a read without focus; a SecurityException or a
            // vendor-specific failure must never propagate into the poll.
            Logger.d(TAG, "clipboard read refused: ${e.message}")
            return null
        } ?: return null
        if (clip.itemCount <= 0) return null
        // coerceToText yields a URI's string form rather than resolving it — see
        // the class kdoc: a guest must never receive a content:// grant.
        val text = try {
            clip.getItemAt(0)?.coerceToText(application)?.toString()
        } catch (e: Exception) {
            Logger.d(TAG, "clipboard item could not be coerced to text: ${e.message}")
            null
        }
        return text?.takeIf { it.isNotEmpty() }
    }

    override fun write(text: String) {
        val clipboard = manager ?: return
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText(clipLabel(), text))
        } catch (e: Exception) {
            Logger.w(TAG, "clipboard write refused: ${e.message}")
        }
    }

    override fun setChangeListener(listener: (() -> Unit)?) {
        val clipboard = manager ?: return
        this.listener?.let { existing ->
            runCatching { clipboard.removePrimaryClipChangedListener(existing) }
        }
        this.listener = null
        if (listener == null) return
        val wrapped = ClipboardManager.OnPrimaryClipChangedListener {
            // Fail-open: a throwing consumer must not break the system callback.
            runCatching { listener() }
                .onFailure { Logger.w(TAG, "clipboard change listener failed: ${it.message}") }
        }
        try {
            clipboard.addPrimaryClipChangedListener(wrapped)
            this.listener = wrapped
        } catch (e: Exception) {
            Logger.w(TAG, "could not observe the clipboard: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "HostClipboard"
        const val DEFAULT_LABEL = "Linexa workspace"
    }
}
