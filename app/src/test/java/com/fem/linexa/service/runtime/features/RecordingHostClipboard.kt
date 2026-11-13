package com.fem.linexa.service.runtime.features

/**
 * §1.112 — a recording [HostClipboard] for the JVM tests.
 *
 * Lives next to [RecordingGuestFeatureHost]/[RecordingNotificationBridge] for the
 * same reason: it fakes a *seam* of the feature layer (the Android clipboard
 * half), so the shim → spool → clipboard and clipboard → mirror → paste chains
 * can both be asserted without a device.
 *
 * [readable] models the case the real bridge has to live with: from API 29
 * Android refuses a clipboard read unless the app has focus, so `read()`
 * returning null is *normal*, and the supervisor's "never clear the mirror on a
 * null read" rule is only assertable with a fake that can do it.
 */
open class RecordingHostClipboard(
    /** Current host clipboard text, or null for an empty clipboard. */
    var text: String? = null,
    /** When false, [read] returns null as Android does without window focus. */
    var readable: Boolean = true
) : HostClipboard {

    /** Every write, in order — the guest→host assertion. */
    val writes = mutableListOf<String>()

    /** Reads attempted, so "the poll does read" is observable. */
    var readCount: Int = 0
        private set

    var readThrows: Exception? = null
    var writeThrows: Exception? = null

    /** The listener currently registered, or null. Set by [setChangeListener]. */
    var listener: (() -> Unit)? = null
        private set

    /** How many times a listener was registered/cleared (idempotence checks). */
    var listenerRegistrations: Int = 0
        private set
    var listenerClears: Int = 0
        private set

    override fun read(): String? {
        readCount++
        readThrows?.let { throw it }
        if (!readable) return null
        return text?.takeIf { it.isNotEmpty() }
    }

    override fun write(text: String) {
        writeThrows?.let { throw it }
        writes += text
        this.text = text
    }

    override fun setChangeListener(listener: (() -> Unit)?) {
        this.listener = listener
        if (listener == null) listenerClears++ else listenerRegistrations++
    }

    /** Simulate the user copying [value] on the phone, notifying the observer. */
    fun userCopies(value: String) {
        text = value
        listener?.invoke()
    }
}
