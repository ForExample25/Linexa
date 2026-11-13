package com.fem.linexa.ui.screens.desktop

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.fem.linexa.R

/**
 * The VNC viewer surface.
 *
 * §1.79 established the coordinate translation and key forwarding; §1.114 fixed
 * the four silent decode defects and made the viewer consume a whole [VncFrame]
 * rather than one rectangle's bytes.
 *
 * §1.125 replaced the Compose `Canvas` + `pointerInput` + `focusable()` surface
 * with a real [VncSurfaceView] (`AndroidView`), because the Compose version could
 * never accept typed input:
 *
 * - Its `FocusRequester` was created and attached but **never requested**, so the
 *   Box was not focused and `onKeyEvent` received nothing.
 * - Even focused, a Compose `Box` has no `InputConnection`, so a soft keyboard has
 *   nowhere to deliver text and `showSoftInput` is a no-op. On a phone with no
 *   physical keyboard — every target device — the desktop could be *looked at and
 *   touched* but not typed into, and there was no way to summon a keyboard at all.
 * - Touch offered left-button only, so the root-window menu (the launcher in
 *   openbox/fluxbox/jwm/twm, §1.114's preferred window managers) and every context
 *   menu were unreachable.
 *
 * Rendering moved into the same view deliberately: with input owned by a `View`,
 * leaving drawing in Compose would give one surface two coordinate systems, which
 * is precisely the drift §1.114 removed between the screen's hardcoded port and
 * the ViewModel's.
 *
 * **UI/UX Phase 8 (§1.138)** adds the keyboard control and removes
 * tap-to-show-keyboard. Raising the IME on tap meant every left click on the
 * remote desktop threw a keyboard over the window that had just been clicked, and
 * it was still not a way to *dismiss* one. The control is a single small toggle
 * pinned to the corner, which is also the only visible affordance a fullscreen
 * surface with no app bar can offer.
 *
 * **Desktop Zoom / Touch work (2026-08)** adds the zoom affordance — a reset
 * control that appears only while the viewer is zoomed in (pinch to zoom,
 * two-finger drag to pan; [VncSurfaceView] owns the maths) — and forwards the
 * workspace's touch mode to the surface, so the RELATIVE/DIRECT setting takes
 * effect on the next touch without recreating the view.
 */
@Composable
fun VncViewer(
    vncClient: VncClient,
    touchMode: com.fem.linexa.service.runtime.features.DesktopTouchMode =
        com.fem.linexa.service.runtime.features.DesktopTouchMode.RELATIVE,
    modifier: Modifier = Modifier
) {
    val frame by vncClient.frame.collectAsState()
    val isConnected by vncClient.isConnected.collectAsState()
    val error by vncClient.error.collectAsState()

    // Desktop Clipboard work (2026-08): a remote copy becomes an Android copy.
    // The client publishes every RFB ServerCutText here; this effect mirrors it
    // onto the platform clipboard. One-directional (remote → Android) by
    // design, so there is no loop and no echo: the Android side never sends
    // ClientCutText, and the host→guest direction is the §1.112 file-spool
    // bridge, which reaches the desktop's own X selection through real `xclip`.
    // Set-on-change semantics: RFB has no "clipboard cleared" event, so null
    // simply leaves the last copy in place.
    val context = LocalContext.current
    val remoteClipboard by vncClient.remoteClipboard.collectAsState()
    LaunchedEffect(remoteClipboard) {
        val text = remoteClipboard ?: return@LaunchedEffect
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager ?: return@LaunchedEffect
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("linexa-remote", text))
    }

    val surfaceRef = remember { mutableStateOf<VncSurfaceView?>(null) }

    // Desktop Zoom: track "is the user zoomed in" for the reset affordance.
    // The surface reports the multiplier; a zoom of exactly 1 is the fit view.
    var isZoomed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                VncSurfaceView(
                    context = ctx,
                    // Typed text: one press/release pair per character keysym.
                    onKeysym = { keysym -> vncClient.sendKeysymPress(keysym) },
                    onPointer = { x, y, mask -> vncClient.sendPointerEvent(x, y, mask) },
                    onWheel = { x, y, deltaY -> vncClient.sendWheel(x, y, deltaY) }
                ).apply {
                    // Desktop Zoom: the reset pill reads this.
                    onZoomChanged = { userZoom -> isZoomed = userZoom > VncZoom.MIN_ZOOM }
                    // `this.` disambiguates: the unqualified name is the viewer's
                    // parameter, and assigning to it would be a val reassignment.
                    this.touchMode = touchMode
                    // Hardware keys stay press/release-separate so a held key
                    // repeats and a held modifier modifies (§1.79).
                    onKeyDownKeysym = { keysym -> vncClient.sendKeysym(keysym, pressed = true) }
                    onKeyUpKeysym = { keysym -> vncClient.sendKeysym(keysym, pressed = false) }
                    // §1.147: closes the coalescing loop. The view reports which
                    // generation reached its bitmap and the client then releases that
                    // dirty region. Without this the region only grows and every
                    // upload stays full-framebuffer — slower, never wrong.
                    onFrameUploaded = { generation -> vncClient.acknowledgeFrame(generation) }
                    // §1.153: the measurement half. The view is the only thing that
                    // knows how many pixels were actually copied — the region is the
                    // published one union'd with the back buffer's debt (§1.150) — and
                    // the client is where the other two counters live, so the three
                    // can be read together. A no-op unless debug capture is on.
                    onFrameUploadedArea = { uploaded, total ->
                        vncClient.recordUploadedArea(uploaded, total)
                    }
                    surfaceRef.value = this
                    requestFocus()
                }
            },
            // Desktop Touch work: the touch mode is read per gesture by the
            // surface, so a settings change only needs to reach the property —
            // no view recreation, no lost session state.
            update = { surface -> surface.touchMode = touchMode },
            onRelease = { surfaceRef.value = null }
        )

        // Push each new frame into the view. Keyed on the frame so an unrelated
        // recomposition cannot re-upload the same megabytes (§1.114's rule).
        //
        // §1.147 made the pixel copy scoped and §1.150 moved it to a worker thread, so
        // this effect hands over a reference and returns — the copy is not on this
        // thread. §1.152 removed the vsync hop that used to sit between the two: a
        // burst is coalesced by the view's single in-flight copy rather than by the
        // display's rate, which is one display frame less latency per update. `frame`
        // is a StateFlow, so bursts are already conflated here too; the view's dirty
        // region is cumulative precisely because of that (see VncDirtyRegion).
        LaunchedEffect(frame, isConnected) {
            val surface = surfaceRef.value ?: return@LaunchedEffect
            val current = frame
            if (isConnected && current != null) {
                surface.submitFrame(current)
            } else {
                surface.clearFrame()
            }
        }

        if (!isConnected || frame == null) {
            Text(
                // §1.114: a failed connection is reported instead of an
                // indistinguishable, permanent "Connecting…".
                text = error?.let { stringResource(R.string.desktop_vnc_error, it) }
                    ?: if (isConnected) {
                        stringResource(R.string.desktop_connecting)
                    } else {
                        stringResource(R.string.desktop_not_connected)
                    },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
        }

        // §1.138: the keyboard toggle. Small, corner-pinned and translucent
        // because it sits on top of a remote desktop whose pixels belong to the
        // user — and shown only once there is something to type into, so it does
        // not decorate a "Connecting…" screen.
        if (isConnected && frame != null) {
            IconButton(
                onClick = { surfaceRef.value?.toggleSoftKeyboard() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .size(44.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.45f),
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Filled.Keyboard,
                    contentDescription = stringResource(R.string.desktop_keyboard_toggle),
                    tint = Color.White
                )
            }

            // Desktop Zoom: the reset affordance, shown only while actually
            // zoomed — at the fit view there is nothing to reset, and the
            // desktop's pixels belong to the user, so this stays out of the way
            // (same translucent corner style as the keyboard toggle, opposite
            // corner so the two never stack).
            if (isZoomed) {
                IconButton(
                    onClick = { surfaceRef.value?.resetZoom() },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                        .size(44.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.45f),
                            shape = CircleShape
                        )
                ) {
                    Text(
                        text = stringResource(R.string.desktop_zoom_reset),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}
