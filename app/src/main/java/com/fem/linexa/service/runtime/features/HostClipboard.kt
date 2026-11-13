package com.fem.linexa.service.runtime.features

/**
 * §1.112 — the narrow seam [WorkspaceClipboardSupervisor] uses to reach the
 * Android system clipboard.
 *
 * Exists for the same reason [GuestFeatureHost] and [GuestNotificationBridge]
 * do: the real implementation ([AndroidHostClipboard]) needs a `ClipboardManager`
 * and an `Application`, so a supervisor that depended on it directly would be
 * untestable off-device. The whole shim → spool → clipboard chain is therefore
 * driven by a fake in JVM tests.
 *
 * Deliberately three operations and one listener. There is no API here to read
 * *clip metadata*, to enumerate history, to inspect the clip's source package,
 * or to address anything other than the primary clip — a workspace feature must
 * be able to exchange **text** with the user's clipboard and nothing else. In
 * particular [read] returns a plain `String?`: a guest can never see a URI in the
 * clip (which on Android is a readable content handle, and would hand a
 * workspace a grant into another app's provider).
 */
interface HostClipboard {

    /**
     * The primary clip's text, or null when the clipboard is empty, holds
     * non-text content, or cannot be read right now.
     *
     * **"Cannot be read right now" is the normal case on API 29+**: Android only
     * lets an app read the clipboard while it has focus (or is the default IME).
     * A null here is therefore not an error — see
     * [WorkspaceClipboardSupervisor]'s mirror policy, which is built around it.
     */
    fun read(): String?

    /** Replace the primary clip with [text]. */
    fun write(text: String)

    /**
     * Observe primary-clip changes, replacing any previously set listener.
     *
     * A single listener (rather than add/remove of many) because there is
     * exactly one supervisor: the clipboard is process-wide state, and two
     * observers writing the same mirror files would race. Passing null stops
     * observing.
     */
    fun setChangeListener(listener: (() -> Unit)?)
}
