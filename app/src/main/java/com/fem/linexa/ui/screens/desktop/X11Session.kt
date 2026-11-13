package com.fem.linexa.ui.screens.desktop

/**
 * The X11 readiness lifecycle and its pure detection/environment builders
 * moved to the runtime layer (transformation spec §13/§14 — "X11 readiness"
 * must be a runtime fact, not ViewModel-internal state):
 *
 * - [com.fem.linexa.service.runtime.desktop.X11SessionState]
 * - [com.fem.linexa.service.runtime.desktop.X11SocketReadiness]
 * - [com.fem.linexa.service.runtime.desktop.X11Environment]
 * - [com.fem.linexa.service.runtime.desktop.X11Provider] / [com.fem.linexa.service.runtime.desktop.TermuxX11]
 *
 * This file keeps the UI-side aliases the Desktop screen was already reading
 * (`X11SessionState`) so the state machine's consumer surface is unchanged;
 * the runtime owns the transitions via [com.fem.linexa.service.runtime.desktop.X11SessionManager].
 */
typealias X11SessionState = com.fem.linexa.service.runtime.desktop.X11SessionState
