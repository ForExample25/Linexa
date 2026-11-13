package com.fem.linexa.domain.runtime

/**
 * §1.97/W1: the default terminal of a workspace. W1 preserved the then-current
 * single-terminal behavior by having the existing call path open this terminal;
 * additional terminals are W3 work.
 *
 * Lives in `domain.runtime` alongside [RuntimeState]/[TerminalState] rather than
 * in `service.runtime.container` (where it was originally declared next to
 * `ContainerRuntime`) because it is a *terminal-identity* value, not part of the
 * container seam. The practical consequence: `ui.screens.workspace`
 * (`WorkspaceNavGraph`, which resolves a requested terminal id against the
 * workspace default) can name it without importing from
 * `service.runtime.container` — an interface the UI is not supposed to reach
 * into at all, and whose concrete implementation the layering test now forbids
 * the UI from touching. Kept as a top-level `const` under the same name so the
 * move is import-only at every one of its call sites.
 */
const val DEFAULT_TERMINAL_ID = "T0"
