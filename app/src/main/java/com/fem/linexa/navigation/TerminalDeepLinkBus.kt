package com.fem.linexa.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 6b bug-fix — in-app re-delivery channel for the runtime
 * notification's Open deep link, so the workspace screen does not have to be
 * destroyed and recreated to retarget its terminal overlay.
 *
 * **The bug this exists to fix:** the FGS notification's Open action carries
 * `linexa://workspace/{workspaceId}?terminalId={terminalId}` (built by
 * `RuntimeNotificationContent.openDeepLink`). When the user taps it while the
 * workspace screen is *already* on top, the previous code path called
 * `NavController.handleDeepLink(intent)`. nav-compose 2.7.7 parses the deep
 * link's URI and route-args, then compares them against the current
 * back-stack entry — and because the existing entry was created by
 * `Routes.workspace(id)` (no `?terminalId=…`), the deep-link entry's args
 * differ, so nav-compose treats them as different destinations: it pops the
 * existing entry — which destroys the `WorkspaceNavGraph` composable — and
 * pushes a fresh one. Destroying the graph fires `RuntimeViewModel.onCleared`,
 * which sends `ACTION_STOP` to `RuntimeService`, which calls
 * `closeWorkspace`, which destroys every pty for the workspace. The user
 * taps the notification to *open* the last terminal they were in, and every
 * terminal they had open closes instead.
 *
 * **The fix:** intercept the deep link in `MainActivity.onNewIntent` BEFORE
 * handing it to `NavController.handleDeepLink`. If the current back-stack
 * top is already the workspace destination for the SAME workspace id, the deep
 * link is delivered through this bus instead of through nav-compose's
 * argument-replacement path. The existing `LaunchedEffect(workspaceId,
 * requestedTerminalId)` in `WorkspaceNavGraph` still handles the cold-start
 * case (where there is no existing entry to retarget), and a new
 * `LaunchedEffect(workspaceId)` collects from this bus to handle the in-app
 * re-delivery case without touching the back stack.
 *
 * The bus is a `StateFlow<TerminalRequest?>` (not a `SharedFlow`) so a
 * collector that subscribes after the emit still observes the value — the
 * workspace composable subscribes on its own `LaunchedEffect(workspaceId)`
 * lifecycle, which may race ahead of the bus's emit when the activity is
 * brought to the foreground. Each emit is followed by a `consume()` so the
 * value does not loop — a stale request cannot fire twice.
 *
 * Process-wide singleton on purpose: there is exactly one MainActivity, one
 * NavController, and one workspace screen at a time, so there is no
 * multiplicity to worry about. The bus is reset to null after the workspace
 * composable consumes the request, which is also what makes a re-subscribe
 * (e.g. after a config change) NOT replay the request — the request has
 * already been applied to the hoisted `activeTerminalId` state, which is
 * itself `rememberSaveable`d.
 */
object TerminalDeepLinkBus {

    /**
     * One in-app re-delivery request: a `(workspaceId, terminalId)` pair.
     *
     * `terminalId` is null when the deep link was the no-terminal form
     * `linexa://workspace/{workspaceId}` (i.e. the user just wanted to bring
     * the workspace to the foreground, not a specific terminal). The consumer
     * keeps the current `activeTerminalId` in that case.
     */
    data class TerminalRequest(
        val workspaceId: String,
        val terminalId: String?
    )

    private val _request = MutableStateFlow<TerminalRequest?>(null)

    /** The current unconsumed request, or null. */
    val request: StateFlow<TerminalRequest?> = _request.asStateFlow()

    /**
     * Publish a request. Replaces any previously-unconsumed request — the
     * latest tap is the one the user means.
     */
    fun emit(workspaceId: String, terminalId: String?) {
        _request.value = TerminalRequest(workspaceId, terminalId)
    }

    /**
     * Clear the current request. Called by the workspace composable after it
     * has applied the request to its hoisted `activeTerminalId` /
     * `terminalOpen` state, so a re-subscribe (e.g. on a config change) does
     * not replay it.
     */
    fun consume() {
        _request.value = null
    }
}
