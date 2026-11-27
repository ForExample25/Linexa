package com.fem.linexa.ui.screens.terminal

/**
 * Why the workspace terminal desktop was asked to focus a window.
 *
 * The source is logged on every authoritative selection change
 * (`TerminalChain`) so a first Root Shell launch can be diagnosed without
 * guessing which surface wrote the active id:
 *
 *  - [RootShellLaunch] — the Apps toolbar's explicit Root Shell action.
 *  - [ExternalOpenRequest] — an app launch, notification deep link, or a
 *    runtime-created/replacement terminal announcing itself.
 *  - [WindowRestore] — an explicit window focus (Recent Sessions card, the
 *    Sessions tab, or one-shot process-death restore).
 *  - [DesktopActiveWindowUpdate] — the one-way mirror of the window the panel
 *    is actually rendering back to the hoisted saved id. It is presentation
 *    bookkeeping, never a focus request.
 *  - [PendingTerminalResolution] — a remembered request that has now been
 *    applied because its window synced.
 *  - [FallbackSelection] — the requested/previous window is gone, so the
 *    ViewModel deterministically focused a live sibling.
 *  - [TerminalRemoval] — a terminal left the runtime list; its presentation
 *    window and app identity are being dropped.
 */
enum class TerminalSelectionSource {
    RootShellLaunch,
    ExternalOpenRequest,
    WindowRestore,
    DesktopActiveWindowUpdate,
    PendingTerminalResolution,
    FallbackSelection,
    TerminalRemoval
}
