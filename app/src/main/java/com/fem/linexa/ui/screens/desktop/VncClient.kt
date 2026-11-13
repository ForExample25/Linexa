package com.fem.linexa.ui.screens.desktop

import com.fem.linexa.core.logging.Logger
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Socket

/**
 * Minimal VNC/RFB 3.8 client for desktop viewing (Phase 4, Path B).
 *
 * Audit fix (2026-07-26, §1.19): the original version skipped ClientInit/
 * ServerInit entirely and never sent a FramebufferUpdateRequest, so against a
 * spec-compliant server it never received a frame.
 *
 * §1.94: an explicit SetEncodings message is sent after SetPixelFormat.
 *
 * §1.114 — the decode path was audited against RFB 3.8 and four defects that
 * are invisible at compile time were fixed:
 *
 * 1. **Server message types were mis-numbered.** `Bell` was 3; in RFB 3.8 the
 *    types are 0 FramebufferUpdate, 1 SetColourMapEntries, **2 Bell**,
 *    **3 ServerCutText**. A real ServerCutText (which a server sends whenever
 *    the remote clipboard changes — an ordinary event, especially with §1.112's
 *    clipboard bridge running) was logged as a Bell, and *neither* it nor
 *    SetColourMapEntries had its variable-length payload consumed. Every
 *    subsequent read was then misaligned: the session silently decoded garbage.
 *    Both payloads are now consumed by length, and an unknown type is a hard
 *    failure instead of a silent desync (the stream cannot be resynchronised
 *    once its framing is lost, so continuing is strictly worse than a clean
 *    reconnect).
 *
 * 2. **Rectangles ignored their own position.** Each rectangle's pixels were
 *    published as if they were the entire framebuffer, so the first (full-screen)
 *    update looked right and every incremental dirty rectangle after it produced
 *    an undersized buffer that the viewer's guard dropped — the desktop drew once
 *    and then froze. There is now a persistent framebuffer that rectangles are
 *    decoded into at `(x, y)` ([VncPixels.decodeArgbInto]).
 *
 * 3. **Alpha came from an undefined wire byte.** See [VncPixels.decodeArgbInto].
 *
 * 4. **CopyRect is supported and advertised.** Servers emit CopyRect for window
 *    moves and scrolls, and with a persistent framebuffer it is a few lines
 *    ([VncPixels.copyRect]) — while *not* supporting it meant taking the
 *    fail-loud path on completely ordinary server behaviour.
 *
 * §1.151 added **ZRLE** ([VncZrle]) as a third encoding, on the reasoning that Raw
 * sends every changed pixel uncompressed — 8.3 MB on the wire for a full 1920×1080
 * repaint.
 *
 * §1.152 **stops advertising it**, while keeping the decoder and its tests. The
 * bandwidth argument does not apply to this transport: the server is `Xvnc` on
 * `127.0.0.1` inside the same guest ([DesktopViewModel.VNC_HOST]), so the "wire" is
 * the loopback interface and its cost is a memory copy, not a link. Both ends also
 * share one phone CPU, so the deflate the server performs and the inflate plus tile
 * walk this client performs are *added* to the frame's critical path rather than
 * traded against transfer time — and they are added to the same little cores the X
 * server, the window manager and this app's own render path are already competing
 * for. Compression here buys nothing and is paid for twice.
 *
 * The offer is what is withdrawn, not the implementation: [ENCODING_ZRLE],
 * [VncZrle] and [VncZlibStream] remain, tested and wired, because
 * `buildSetEncodingsMessage` is the only thing that decides whether a server ever
 * sends a ZRLE rectangle. A future non-loopback transport (a real remote host) makes
 * the original reasoning correct again, and re-enabling it is then this one list.
 *
 * Still deliberately out of scope: RRE/Hextile/Tight encodings, and any
 * authentication beyond `None` (the daemon is started with `-SecurityTypes None`
 * on host loopback, §1.83; [SecurityUnsupportedException] makes an
 * authenticating server a clear error rather than a garbled handshake).
 *
 * §1.153 — **this client owns its own IO-dispatched scope.** Every version before
 * it was constructed from `DesktopScreen`'s `rememberCoroutineScope()`, whose
 * dispatcher is `AndroidUiDispatcher.Main`, and that single fact bounded the whole
 * desktop's frame rate:
 *
 *  - [startReceiving] launched its `while` loop in that scope, so each iteration
 *    body was a `withContext(Dispatchers.IO)` hop out and back. Every RFB message
 *    therefore cost **two dispatches and one Main-thread turn** — message *N+1*
 *    could not begin being read until a Main-queue task had run.
 *  - Main is simultaneously running the frame collector, the buffer swap, Compose
 *    recomposition and the traversal that this loop's own output causes. So frame
 *    delivery was gated by the availability of the thread that draws the frames,
 *    and competed with the drawing it produced.
 *  - [sendPointerEvent], [sendKeysym] and [sendWheel] all `scope.launch`, so a
 *    pointer message also waited for a Main-queue turn *before* reaching the
 *    socket. §1.152's `tcpNoDelay` could not help with that: the delay was before
 *    the write, not inside it.
 *
 * The loop and the writes now run on [Dispatchers.IO] from the outset, and the
 * per-message `withContext` hops on the hot path ([readServerMessage],
 * [requestFramebufferUpdate], the three `sendX` writes) are gone with them — they
 * were re-entering the dispatcher their caller is already on. [connect] keeps its
 * own `withContext(Dispatchers.IO)`, because that one is real: it is called from a
 * composition, on Main. The once-per-connection handshake writes keep theirs too,
 * deliberately — the hop is free at that frequency and it keeps each of them
 * correct if it is ever called from anywhere else.
 *
 * The lifecycle consequence is the reason this is not a one-line change: the
 * client's coroutines are no longer cancelled by the composition going away, so
 * [disconnect] is now the only thing that stops them, and `DesktopScreen`'s
 * `DisposableEffect(vncClient)` (§1.140) is the only thing that calls it on the way
 * out. That effect already existed — §1.140 added it because `disconnect` was
 * already the only thing that closed the socket — so the ownership was in fact
 * already there and only the scope had not caught up.
 *
 * §1.153 also adds [stats]: the audit that produced this change could prove the
 * mechanism from source but could not measure its share of the symptom, because the
 * render path emits no timing at all. See [VncRenderStats].
 */
class VncClient(
    /**
     * The scope the receive loop and every input write run in.
     *
     * Defaults to this client's own IO scope ([createReceiveScope]) and is a
     * parameter only so a test can substitute a controlled one. **Do not pass a
     * composition scope** — see the class kdoc for what that cost.
     */
    private val scope: CoroutineScope = createReceiveScope()
) {

    /** Thrown when the server offers no security type this client implements. */
    class SecurityUnsupportedException(val offered: List<Int>) :
        IllegalStateException("VNC server requires authentication (types $offered); only 'None' is supported")

    private var socket: Socket? = null
    private var inputStream: BufferedInputStream? = null
    private var outputStream: BufferedOutputStream? = null
    private var receiveJob: Job? = null

    /**
     * Desktop Modes: the connection's authentication credential and encoding
     * preference, set by [connect] from the Desktop run-mode settings. Both
     * default to the §1.152 behaviour (no auth, no ZRLE offer), so a plain
     * `connect(host, port)` is byte-identical to the pre-Desktop-Modes client.
     */
    private var authPassword: String? = null
    private var preferZrle: Boolean = false

    /** The persistent framebuffer rectangles are composed into. */
    private var pixels: IntArray = IntArray(0)
    private var generation = 0L
    // Phase 2C instrumentation: one-shot flag for the T4 first-frame log line (reset in disconnect()).
    private var firstUpdateLogged = false

    /**
     * §1.147 — the region of [pixels] the consumer has not yet uploaded.
     *
     * Owned here rather than derived per-update because it must survive frames
     * the `StateFlow` conflates away; the consumer clears it via
     * [acknowledgeFrame]. See [VncDirtyRegion].
     */
    private val dirty = VncDirtyRegion()

    /**
     * §1.153 — the render path's measurement. Off unless debug capture is on.
     *
     * Not private because the consumer supplies one of the three quantities: the copy
     * the view actually performs is not the region this client published (§1.150
     * unions it with the back buffer's debt), and the cost worth measuring is the
     * copy that happened. [VncViewer] routes [VncSurfaceView]'s report here.
     *
     * `internal` rather than public, and therefore *not* reachable through a public
     * flow: this is a diagnostic instrument for this package, not part of the frame
     * contract [frame] publishes. Same rule as [VncDirtyRegion] — with the opposite
     * conclusion for [VncRect], which had to be public because a public flow carries
     * it.
     */
    internal val stats = VncRenderStats()

    private val _frame = MutableStateFlow<VncFrame?>(null)
    val frame: StateFlow<VncFrame?> = _frame.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _frameWidth = MutableStateFlow(0)
    val frameWidth: StateFlow<Int> = _frameWidth.asStateFlow()

    private val _frameHeight = MutableStateFlow(0)
    val frameHeight: StateFlow<Int> = _frameHeight.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /**
     * §1.151 — the connection's single zlib stream for ZRLE, or null before the
     * first ZRLE rectangle.
     *
     * **Per connection, not per rectangle** — see [VncZlibStream], which exists as
     * its own type precisely so that rule is assertable without a socket. Created
     * lazily because a server may never send a ZRLE rectangle (and since §1.152 it
     * is not even asked to — the encoding is no longer advertised, so in practice
     * this stays null for the whole session unless a server sends ZRLE unasked), and
     * ended by [closeSocketQuietly].
     *
     * Only ever touched from the receive loop (`Dispatchers.IO`, one coroutine), so
     * it needs no synchronisation.
     */
    private var zlibStream: VncZlibStream? = null

    /**
     * §1.114: the last connection/stream failure, or null. Previously written
     * and never read — a refused connection left the screen showing "Connecting…"
     * forever with no way to tell it apart from a slow one. [DesktopScreen] now
     * renders this.
     */
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Desktop Clipboard work (2026-08): the most recent remote clipboard text
     * (RFB ServerCutText), or null when nothing has arrived yet.
     *
     * One-way by construction — this client never sends ClientCutText — so
     * there is no loop: a remote copy becomes an Android-clipboard write, and
     * the Android clipboard's own changes never flow back onto the wire. The
     * host→guest direction remains the §1.112 file-spool bridge, which serves
     * the terminal and, via real `xclip`, the desktop's own X selection.
     */
    private val _remoteClipboard = MutableStateFlow<String?>(null)
    val remoteClipboard: StateFlow<String?> = _remoteClipboard.asStateFlow()

    companion object {
        private const val TAG = "VncClient"
        private const val RFB_VERSION_3_8 = "RFB 003.008\n"
        private const val RFB_VERSION_3_3 = "RFB 003.003\n"
        private const val CONNECT_TIMEOUT_MS = 5000

        /** A1 cap (2026-09-02): 16 MP framebuffer ceiling — see [resizeFramebuffer]. */
        private const val MAX_FRAMEBUFFER_PIXELS = 16_000_000L

        /** A1 cap (2026-09-02): the desktop name is log-only; anything beyond 4 KiB is skipped. */
        private const val MAX_DESKTOP_NAME_BYTES = 4096

        /**
         * §1.153 — the scope a [VncClient] owns by default.
         *
         * `SupervisorJob` so that a failed input write cannot cancel the receive
         * loop (they are siblings, and a socket write failing is already surfaced
         * through `_error`); [Dispatchers.IO] because every coroutine in it does
         * blocking socket IO and nothing in it touches a `View` or Compose state
         * directly — the frames leave through a `StateFlow`, which is safe to write
         * from any thread and is collected on Main by [VncViewer].
         *
         * A [CoroutineName] because this scope now outlives the composition that
         * created the client, so it is worth being identifiable in a thread dump or
         * a coroutine debugger.
         */
        internal fun createReceiveScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("VncClient"))

        /**
         * §1.114: a read timeout, where the pre-§1.114 client set `soTimeout = 0`
         * (block forever). A wedged server would otherwise pin the receive
         * coroutine permanently with no error and no recovery. Generous, because
         * an *idle* desktop legitimately sends nothing for a long time — this is
         * a liveness backstop, not a latency budget.
         */
        private const val READ_TIMEOUT_MS = 60_000

        private const val MSG_SET_PIXEL_FORMAT = 0
        private const val MSG_SET_ENCODINGS = 2
        private const val MSG_FRAMEBUFFER_UPDATE_REQUEST = 3
        private const val MSG_KEY_EVENT = 4
        private const val MSG_POINTER_EVENT = 5

        // RFB 3.8 §7.6 server → client message types. The pre-§1.114 constant
        // `SERVER_MSG_BELL = 3` was wrong; 3 is ServerCutText.
        private const val SERVER_MSG_FRAMEBUFFER_UPDATE = 0
        private const val SERVER_MSG_SET_COLOUR_MAP_ENTRIES = 1
        private const val SERVER_MSG_BELL = 2
        private const val SERVER_MSG_SERVER_CUT_TEXT = 3

        internal const val ENCODING_RAW = 0
        internal const val ENCODING_COPY_RECT = 1

        /** A3 (2026-09-02): the DesktopSize pseudo-encoding — a zero-payload resize announcement. */
        internal const val ENCODING_DESKTOP_SIZE = -311

        /**
         * §1.151 — ZRLE (RFB 3.8 §7.7.6). See [VncZrle] for the decoder.
         *
         * §1.152: **decoded but no longer advertised** — see
         * [buildSetEncodingsMessage]. The branch in [readRectangle] is kept because a
         * server is not obliged to restrict itself to the advertised set, and a
         * rectangle this client can decode must not become a fatal desync; and
         * because the offer is a one-line change for a future remote transport.
         */
        internal const val ENCODING_ZRLE = 16

        private const val SECURITY_TYPE_NONE = 1

        /**
         * Desktop Clipboard work (2026-08): the largest ServerCutText payload
         * accepted. The length is a wire-supplied allocation size, so it is
         * bounded — 64 KiB matches ClipboardBridgeProvisioner's clipboard
         * transfer cap, which is the app's existing "a clipboard is not a bulk
         * channel" bound.
         */
        private const val MAX_CUT_TEXT_BYTES = 64 * 1024

        /**
         * §1.151 — upper bound on a single ZRLE rectangle's *compressed* length.
         *
         * The length arrives on the wire and is allocated immediately, so it is
         * checked rather than trusted: 32 MB is far above any real rectangle (a
         * full-screen 1920×1080 ZRLE update compresses to a few hundred KB) and far
         * below a size that would take the process down.
         */
        internal const val MAX_ZRLE_RECTANGLE_BYTES = 32 * 1024 * 1024

        /**
         * RFB security type 2 — VNC authentication (RFC 6143 §7.2.2), implemented
         * from scratch in [VncAuth] (Desktop Modes work). Previously this client
         * spoke only `None`, which is why the server had to be started with
         * `-SecurityTypes None` and any local app could reach the desktop.
         */
        internal const val SECURITY_TYPE_VNC_AUTH = 2

        /** The VNC-Auth challenge length the protocol mandates. */
        internal const val VNC_AUTH_CHALLENGE_BYTES = 16

        /**
         * RFB wheel buttons live in the pointer message's button *mask*:
         * bit 3 (0x08) is wheel-up, bit 4 (0x10) is wheel-down (RFB 3.8 §7.5.5,
         * buttons 4 and 5). §1.114: the pre-§1.114 code wrote the *values* 4 and
         * 5 into the mask, which the server reads as "buttons 3+4 pressed" and
         * "buttons 1+4 pressed" — a scroll gesture therefore also sent phantom
         * left/middle clicks.
         */
        internal const val BUTTON_MASK_WHEEL_UP = 0x08
        internal const val BUTTON_MASK_WHEEL_DOWN = 0x10

        /**
         * §1.94 + §1.114 + §1.151 + §1.152 + Desktop Modes: pure builder for the RFB
         * SetEncodings message.
         *
         * byte 0 = message-type (2), byte 1 = padding,
         * U16 big-endian at 2 = number-of-encodings,
         * then one S32 big-endian per encoding, in preference order.
         *
         * Order is load-bearing. CopyRect stays first because it is not really in
         * competition with a pixel encoding — a server treats it as a capability flag
         * and uses it for moves and scrolls whatever else it picks — and it is
         * cheaper than any of them for the same region. Raw follows and is never
         * dropped: it is mandatory per the spec.
         *
         * §1.152 kept [ENCODING_ZRLE] out of the default offer: on the loopback
         * transport the deflate and the inflate both run on the one phone CPU, so
         * advertising it is a measured pessimisation *for local use*. Desktop Modes
         * turns the offer into a parameter ([preferZrle]) rather than reverting the
         * decision: the default call — what [sendSetEncodings] sends with the
         * setting off — is byte-identical to §1.152, and the opt-in exists for the
         * non-loopback transports (an `adb reverse` tunnel to a PC, a future remote
         * viewer) where the bandwidth ZRLE saves is real. The decoder branch was
         * never removed, so the opt-in needs no other change.
         */
        internal fun buildSetEncodingsMessage(preferZrle: Boolean = false): ByteArray {
            val encodings = if (preferZrle)
                listOf(ENCODING_COPY_RECT, ENCODING_ZRLE, ENCODING_RAW)
            else
                listOf(ENCODING_COPY_RECT, ENCODING_RAW)
            val msg = ByteArray(4 + encodings.size * 4)
            msg[0] = MSG_SET_ENCODINGS.toByte()
            // msg[1] padding = 0
            msg[2] = ((encodings.size shr 8) and 0xFF).toByte()
            msg[3] = (encodings.size and 0xFF).toByte()
            encodings.forEachIndexed { index, encoding ->
                val offset = 4 + index * 4
                msg[offset] = ((encoding shr 24) and 0xFF).toByte()
                msg[offset + 1] = ((encoding shr 16) and 0xFF).toByte()
                msg[offset + 2] = ((encoding shr 8) and 0xFF).toByte()
                msg[offset + 3] = (encoding and 0xFF).toByte()
            }
            return msg
        }

        /**
         * §1.114: pure selection of the client's ProtocolVersion reply.
         *
         * The pre-§1.114 client always answered `RFB 003.008` regardless of what
         * the server announced. Answering with a version *above* the server's is
         * a protocol violation, and the security handshake differs between 3.3
         * and 3.8 (3.3 sends a bare U32 security type with no list and no
         * SecurityResult for `None`), so a 3.3 server would desync immediately.
         * Reply with min(server, 3.8).
         */
        internal fun negotiateVersion(serverVersion: String): String {
            val match = Regex("""RFB (\d{3})\.(\d{3})""").find(serverVersion.trim())
                ?: return RFB_VERSION_3_8
            val major = match.groupValues[1].toInt()
            val minor = match.groupValues[2].toInt()
            return if (major == 3 && minor < 7) RFB_VERSION_3_3 else RFB_VERSION_3_8
        }

        /** Whether [version] (a reply from [negotiateVersion]) is RFB 3.7+. */
        internal fun isVersion37OrLater(version: String): Boolean = version == RFB_VERSION_3_8

        /**
         * §1.114: pure security-type selection, extended by Desktop Modes to
         * know VNC-Auth.
         *
         * Selection rules, in order:
         * - a **non-empty [password]** prefers [SECURITY_TYPE_VNC_AUTH] when the
         *   server offers it — the caller starting Xvnc with `-rfbauth` asked for
         *   an authenticated desktop, so the client must not silently downgrade;
         * - otherwise [SECURITY_TYPE_NONE] when offered (the §1.114 behaviour —
         *   including the no-password case where the server offers both, because
         *   falling back can only happen when the *user* left the password empty);
         * - otherwise [SECURITY_TYPE_VNC_AUTH] when offered with no password is a
         *   failure, not a downgrade: the client has no credential, and guessing
         *   one would just produce a misleading "server rejected connection";
         * - anything else throws [SecurityUnsupportedException] instead of the
         *   pre-§1.114 behaviour of picking `securityTypes[0]`, writing it, and
         *   then reading the *first four bytes of the auth challenge* as a
         *   SecurityResult — which produced a misleading "server rejected
         *   connection" for what is really an unimplemented auth type.
         */
        internal fun chooseSecurityType(offered: List<Int>, password: String? = null): Int {
            if (!password.isNullOrEmpty() && offered.contains(SECURITY_TYPE_VNC_AUTH)) {
                return SECURITY_TYPE_VNC_AUTH
            }
            if (offered.contains(SECURITY_TYPE_NONE)) return SECURITY_TYPE_NONE
            throw SecurityUnsupportedException(offered)
        }
    }

    /**
     * Open the connection.
     *
     * Desktop Modes adds [password] (VNC-Auth; null/blank = the §1.152 `None`
     * behaviour) and [preferZrle] (advertise ZRLE for non-loopback transports).
     * Both are per-connection: [disconnect] ends the session but the instance
     * is reused by the screen, so the values are re-read on every [connect].
     */
    suspend fun connect(
        host: String = "127.0.0.1",
        port: Int = 5901,
        password: String? = null,
        preferZrleEncodings: Boolean = false
    ) {
        // Pre-release audit fix (2026-09-02, finding C1): connect used to
        // overwrite socket/streams and launch a SECOND receive loop without
        // tearing down the previous session — two overlapping connects (the
        // auto-retry effect racing a state-driven connect) left two IO
        // coroutines reading the same BufferedInputStream: interleaved RFB
        // framing, garbage decode, and a leaked orphaned socket. disconnect()
        // is idempotent and cheap; running it first serializes the session.
        disconnect()
        authPassword = password?.takeIf { it.isNotEmpty() }
        preferZrle = preferZrleEncodings
        withContext(Dispatchers.IO) {
            try {
                Logger.d(TAG, "Connecting to VNC server at $host:$port")
                _error.value = null
                // Desktop Clipboard work (2026-08): a new session starts with an
                // empty remote-clipboard view — stale text from the previous
                // connection must not masquerade as the remote's clipboard.
                _remoteClipboard.value = null
                // §1.153: the counters are per session. The same client instance is
                // reconnected by the screen (Running → not-Running → Running), and
                // carrying the previous session's totals into the next one would mix
                // two different configurations' numbers into one mean — which is
                // exactly the mistake this instrumentation exists to prevent.
                // `disconnect` has already logged the summary by this point.
                stats.reset()
                // Phase 2C instrumentation: per-session flag must reset here (not only in
                // disconnect()) — the §12 auto-reconnect path calls connect() directly.
                firstUpdateLogged = false
                // §1.165: publish these counters as the ones the debug panel reads.
                // After the reset, so the window a reader sees starts at this
                // connection. Two volatile writes per connection — see
                // [VncRenderStatsRegistry] for why this is a registry and not a
                // parameter threaded through the workspace UI.
                VncRenderStatsRegistry.attach(stats)
                socket = Socket().apply {
                    connect(java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    soTimeout = READ_TIMEOUT_MS
                    // §1.152: disable Nagle. Every message this client sends is tiny
                    // and latency-critical — a PointerEvent is 6 bytes, a KeyEvent 8,
                    // a FramebufferUpdateRequest 10 — and Nagle holds a small write
                    // back until the previous segment is acknowledged. That is exactly
                    // the wrong trade here: a pointer move whose send is deferred
                    // delays the server's cursor repaint, which delays the
                    // FramebufferUpdate this client is waiting to draw, so the finger
                    // and the cursor visibly diverge. There is no bandwidth to save in
                    // return — these writes are already one message per event and the
                    // link is loopback.
                    tcpNoDelay = true
                }
                inputStream = BufferedInputStream(socket?.getInputStream())
                outputStream = BufferedOutputStream(socket?.getOutputStream())

                performHandshake()
                sendSetPixelFormat()
                sendSetEncodings()
                requestFramebufferUpdate(incremental = false)

                _isConnected.value = true
                Logger.d(TAG, "Connected to VNC server (${_frameWidth.value}x${_frameHeight.value})")

                startReceiving()
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to connect", e)
                _error.value = e.message ?: e::class.simpleName
                _isConnected.value = false
                // §1.114: a half-open socket from a failed handshake was leaked
                // before; close it so a retry starts clean.
                closeSocketQuietly()
            }
        }
    }

    /**
     * RFB handshake: version exchange, security handshake, ClientInit,
     * ServerInit. Populates the real framebuffer geometry from ServerInit.
     */
    private suspend fun performHandshake() {
        withContext(Dispatchers.IO) {
            val input = requireNotNull(inputStream)
            val output = requireNotNull(outputStream)

            // Version exchange
            val versionBuffer = ByteArray(12)
            readFully(input, versionBuffer)
            val serverVersion = String(versionBuffer).trim()
            val clientVersion = negotiateVersion(serverVersion)
            Logger.d(TAG, "Server version: $serverVersion → replying ${clientVersion.trim()}")
            output.write(clientVersion.toByteArray())
            output.flush()

            if (isVersion37OrLater(clientVersion)) {
                handshakeSecurity38(input, output)
            } else {
                handshakeSecurity33(input, output)
            }

            // ClientInit: shared-flag = 1 (share desktop with other clients)
            output.write(1)
            output.flush()

            // ServerInit: width(2) height(2) pixel-format(16) name-len(4) name(name-len)
            val serverInit = ByteArray(2 + 2 + 16 + 4)
            readFully(input, serverInit)
            resizeFramebuffer(u16(serverInit, 0), u16(serverInit, 2))

            val nameLength = u32(serverInit, 20)
            // Pre-release audit cap (2026-09-02, finding A1): the server name
            // length is wire-controlled (u32 up to 2^31). A hostile value
            // would have allocated a 2 GB byte array from one ServerInit.
            // Names beyond the cap are skipped (the name is log-only).
            if (nameLength > 0) {
                if (nameLength > MAX_DESKTOP_NAME_BYTES) {
                    skipFully(input, nameLength.toLong())
                    Logger.d(TAG, "Server desktop name skipped (implausible length $nameLength)")
                } else {
                    val nameBytes = ByteArray(nameLength)
                    readFully(input, nameBytes)
                    Logger.d(TAG, "Server desktop name: ${String(nameBytes)}")
                }
            }
        }
    }

    /**
     * RFB 3.7/3.8 security handshake: number-of-types, the list, our choice,
     * then SecurityResult (with the failure reason string on 3.8).
     *
     * Desktop Modes: choosing VNC-Auth inserts the challenge/response exchange
     * between the choice and the SecurityResult — the result arrives after our
     * answer either way, so the framing below is identical for both types.
     */
    private fun handshakeSecurity38(input: BufferedInputStream, output: BufferedOutputStream) {
        val numSecurityTypes = input.read()
        if (numSecurityTypes == -1) {
            throw IllegalStateException("Connection closed during security handshake")
        }
        if (numSecurityTypes == 0) {
            // §1.114: a zero count means the server is REFUSING the connection
            // and a reason string follows. The pre-§1.114 code fell through and
            // read that reason's length as a SecurityResult.
            throw IllegalStateException("VNC server refused the connection: ${readReasonString(input)}")
        }
        val securityTypes = ByteArray(numSecurityTypes)
        readFully(input, securityTypes)
        val offered = securityTypes.map { it.toInt() and 0xFF }
        val chosen = chooseSecurityType(offered, authPassword)
        output.write(chosen)
        output.flush()

        if (chosen == SECURITY_TYPE_VNC_AUTH) {
            performVncAuthExchange(input, output)
        }

        val result = ByteArray(4)
        readFully(input, result)
        if (u32(result, 0) != 0) {
            throw IllegalStateException("VNC security handshake failed: ${readReasonString(input)}")
        }
    }

    /**
     * RFB 3.3 security handshake: the server dictates a single U32 security
     * type and, for `None`, sends **no** SecurityResult — reading one (as the
     * 3.8 path does) would consume the first four bytes of ServerInit.
     *
     * Desktop Modes: a dictated VNC-Auth is answered with the same
     * challenge/response exchange, after which a 4-byte authentication result
     * IS sent (RFB 3.3 carries the result for every authenticated type), so
     * the auth branch reads it and the `None` branch still reads nothing.
     */
    private fun handshakeSecurity33(input: BufferedInputStream, output: BufferedOutputStream) {
        val typeBytes = ByteArray(4)
        readFully(input, typeBytes)
        val type = u32(typeBytes, 0)
        if (type == 0) {
            throw IllegalStateException("VNC server refused the connection: ${readReasonString(input)}")
        }
        if (type != SECURITY_TYPE_NONE && type != SECURITY_TYPE_VNC_AUTH) {
            throw SecurityUnsupportedException(listOf(type))
        }
        if (type == SECURITY_TYPE_VNC_AUTH) {
            if (authPassword == null) {
                // The server demands a credential this connection does not have.
                throw SecurityUnsupportedException(listOf(type))
            }
            performVncAuthExchange(input, output)
            val result = ByteArray(4)
            readFully(input, result)
            if (u32(result, 0) != 0) {
                throw IllegalStateException("VNC authentication failed: wrong password?")
            }
        }
    }

    /**
     * The VNC-Auth exchange shared by both protocol versions: read the
     * server's 16-byte challenge, answer with [VncAuth.challengeResponse],
     * flush. The SecurityResult is read by each caller (their framing differs).
     */
    private fun performVncAuthExchange(input: BufferedInputStream, output: BufferedOutputStream) {
        val password = requireNotNull(authPassword) { "VNC-Auth selected without a credential" }
        val challenge = ByteArray(VNC_AUTH_CHALLENGE_BYTES)
        readFully(input, challenge)
        output.write(VncAuth.challengeResponse(password, challenge))
        output.flush()
    }

    /**
     * Reads an RFB reason-string (U32 length + UTF-8 bytes). Bounded and
     * failure-tolerant: this only ever runs on a path that is already throwing,
     * so it must never itself throw and mask the real error.
     */
    private fun readReasonString(input: BufferedInputStream): String = try {
        val lengthBytes = ByteArray(4)
        readFully(input, lengthBytes)
        val length = u32(lengthBytes, 0).coerceIn(0, 512)
        if (length == 0) {
            "(no reason given)"
        } else {
            val reason = ByteArray(length)
            readFully(input, reason)
            String(reason)
        }
    } catch (_: Exception) {
        "(no reason given)"
    }

    /**
     * Allocates the framebuffer for a new geometry. Called from ServerInit and
     * from a rectangle that reports a larger frame than we hold.
     *
     * Pre-release audit cap (2026-09-02, finding A1): the RFB wire controls
     * the framebuffer geometry, so the allocation must be bounded — a
     * hostile/desynced server announcing 40000×40000 would have forced a
     * 6.4 GB IntArray here (an OutOfMemoryError, which the surrounding
     * catch(Exception) handlers cannot catch → hard crash). 16 MP is 4× a
     * 1920×1080 desktop and far above every supported device geometry; the
     * error is an IllegalStateException so the session fails with a visible
     * reason instead of killing the app.
     */
    private fun resizeFramebuffer(width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            throw IllegalStateException("Server announced an unusable framebuffer size ${width}x$height")
        }
        if (width.toLong() * height.toLong() > MAX_FRAMEBUFFER_PIXELS) {
            throw IllegalStateException(
                "Server announced an implausible framebuffer ${width}x$height " +
                    "(cap ${MAX_FRAMEBUFFER_PIXELS / 1_000_000} MP) — refusing to allocate"
            )
        }
        _frameWidth.value = width
        _frameHeight.value = height
        pixels = IntArray(width * height)
        // §1.147: a resize invalidates every pixel the consumer holds (its bitmap
        // is reallocated for the new geometry), so the whole framebuffer is dirty
        // and the first frame after one is necessarily a full upload.
        dirty.resize(width, height)
        generation++
        _frame.value = VncFrame(pixels, width, height, generation, dirty.publish(generation))
    }

    /**
     * Ask the server for 32bpp true-colour pixel data so the decode below
     * (4 bytes/pixel, BGRX) is correct regardless of the server's native format.
     */
    private suspend fun sendSetPixelFormat() {
        withContext(Dispatchers.IO) {
            val output = requireNotNull(outputStream)
            val msg = ByteArray(20)
            msg[0] = MSG_SET_PIXEL_FORMAT.toByte()
            // msg[1..3] padding = 0
            msg[4] = 32   // bits-per-pixel
            msg[5] = 24   // depth
            msg[6] = 0    // big-endian-flag = false
            msg[7] = 1    // true-colour-flag = true
            writeU16(msg, 8, 255)   // red-max
            writeU16(msg, 10, 255)  // green-max
            writeU16(msg, 12, 255)  // blue-max
            msg[14] = 16  // red-shift
            msg[15] = 8   // green-shift
            msg[16] = 0   // blue-shift
            // msg[17..19] padding = 0
            output.write(msg)
            output.flush()
        }
    }

    /**
     * Advertise the encodings this client asks the server to use (CopyRect,
     * Raw — plus ZRLE when the run-mode setting opts in). Per RFB 3.8 §6.3.3
     * this must follow SetPixelFormat and precede the first
     * FramebufferUpdateRequest; without it the server uses its own default set,
     * which may include encodings we cannot parse.
     *
     * §1.152: the advertised set is always a subset of the *decoded* set —
     * ZRLE is still decoded if a server sends it unasked. See
     * [buildSetEncodingsMessage].
     */
    private suspend fun sendSetEncodings() {
        withContext(Dispatchers.IO) {
            val output = outputStream ?: return@withContext
            output.write(buildSetEncodingsMessage(preferZrle))
            output.flush()
        }
    }

    private suspend fun requestFramebufferUpdate(incremental: Boolean) {
        // §1.153: no withContext. Both callers — `connect` (inside its own
        // Dispatchers.IO block) and the receive loop (an IO-dispatched scope since
        // §1.153) — are already on IO, so the hop only re-entered the dispatcher
        // they were on. On the loop's path it ran once per FramebufferUpdate.
        val output = outputStream ?: return
        val msg = ByteArray(10)
        msg[0] = MSG_FRAMEBUFFER_UPDATE_REQUEST.toByte()
        msg[1] = if (incremental) 1 else 0
        writeU16(msg, 2, 0) // x
        writeU16(msg, 4, 0) // y
        writeU16(msg, 6, _frameWidth.value)
        writeU16(msg, 8, _frameHeight.value)
        output.write(msg)
        output.flush()
    }

    /**
     * Starts the RFB receive loop.
     *
     * §1.153 — this runs in [scope], which is [Dispatchers.IO] by default and used
     * to be the composition's Main-dispatched scope. The loop body is therefore no
     * longer a dispatch: it reads message *N+1* as soon as *N* is decoded, instead
     * of waiting for a Main-queue turn behind the recomposition and traversal that
     * message *N* just caused. See the class kdoc.
     */
    private fun startReceiving() {
        receiveJob = scope.launch {
            while (isActive && _isConnected.value) {
                try {
                    readServerMessage()
                } catch (e: Exception) {
                    Logger.e(TAG, "Error receiving frame", e)
                    // §1.114: surface it — the receive loop's failure was
                    // previously invisible to the UI.
                    _error.value = e.message ?: e::class.simpleName
                    _isConnected.value = false
                    break
                }
            }
        }
    }

    private suspend fun readServerMessage() {
        // §1.153: no withContext — the caller is the receive loop, which is already
        // on IO. This hop ran once per RFB message and, with a Main-dispatched
        // scope, was one of the two dispatches per message the loop paid.
        val input = inputStream ?: return
        val messageType = input.read()
        if (messageType == -1) throw IllegalStateException("Connection closed by server")

        when (messageType) {
            SERVER_MSG_FRAMEBUFFER_UPDATE -> {
                input.read() // padding
                val header = ByteArray(2)
                readFully(input, header)
                val numRectangles = u16(header, 0)

                var changed = false
                for (i in 0 until numRectangles) {
                    if (readRectangle(input)) changed = true
                }
                // §1.153: recorded before the publish so the interval measures
                // update-to-update on the wire and not the consumer's pace.
                stats.recordServerUpdate(numRectangles)
                // Phase 2C instrumentation: T4 = first framebuffer update received. One-shot, no behavior change.
                if (!firstUpdateLogged) {
                    firstUpdateLogged = true
                    Logger.i(TAG, "DesktopTiming T4 firstFramebufferUpdate wallMs=${System.currentTimeMillis()}")
                }
                if (changed) publishFrame()

                // Keep the stream of updates going.
                requestFramebufferUpdate(incremental = true)
            }
            SERVER_MSG_SET_COLOUR_MAP_ENTRIES -> {
                // §1.114: consume the payload. We request true-colour so
                // this should never arrive, but skipping the bytes is what
                // keeps the stream aligned if it does.
                // padding(1) first-colour(2) number-of-colours(2), then
                // 6 bytes (R,G,B as U16) per colour.
                val head = ByteArray(5)
                readFully(input, head)
                val numColours = u16(head, 3)
                skipFully(input, numColours.toLong() * 6)
                Logger.d(TAG, "SetColourMapEntries ignored ($numColours colours)")
            }
            SERVER_MSG_BELL -> {
                // No payload. Intentionally not surfaced in the UI.
                Logger.d(TAG, "Bell received")
            }
            SERVER_MSG_SERVER_CUT_TEXT -> {
                // §1.114: padding(3) length(4) text(length). Previously
                // mis-numbered as Bell and its payload never consumed,
                // which desynced every following read.
                //
                // Desktop Clipboard work (2026-08): the payload is no longer
                // discarded. A ServerCutText is the remote desktop's own copy
                // (a desktop app's Ctrl+C, an X selection change) — reading it
                // and publishing it through [remoteClipboard] lets the viewer
                // put it on the Android clipboard, so a copy inside the desktop
                // is pasteable outside it. The rest of the pairing (the host
                // clipboard reaching the desktop) stays the §1.112 file-spool
                // bridge, which this does not touch.
                val head = ByteArray(7)
                readFully(input, head)
                val length = u32(head, 3)
                // The cap mirrors ClipboardBridgeProvisioner's own transfer
                // bound: a clipboard is not a bulk channel, and an unbounded
                // allocation from a wire-supplied length is the resource-
                // exhaustion shape the rest of this client refuses.
                if (length > MAX_CUT_TEXT_BYTES) {
                    skipFully(input, length.toLong())
                    Logger.w(TAG, "ServerCutText too large, skipped ($length bytes)")
                } else if (length > 0) {
                    val textBytes = ByteArray(length)
                    readFully(input, textBytes)
                    val text = String(textBytes, Charsets.ISO_8859_1).trimEnd { it == '\u0000' }
                    _remoteClipboard.value = text
                    Logger.d(TAG, "ServerCutText received ($length bytes)")
                }
            }
            else -> {
                // §1.114: fail loud. An unknown type has an unknown payload
                // length, so the framing is already lost — continuing would
                // decode garbage as pixels indefinitely.
                throw IllegalStateException("Unsupported RFB server message type $messageType; stream desynced")
            }
        }
    }

    /**
     * Reads one rectangle and composes it into the persistent framebuffer.
     *
     * @return whether the framebuffer changed (so one frame is published per
     *   update message rather than per rectangle — a 40-rectangle update
     *   otherwise triggers 40 recompositions of the same screen).
     */
    private fun readRectangle(input: BufferedInputStream): Boolean {
        val header = ByteArray(12)
        readFully(input, header)

        val x = u16(header, 0)
        val y = u16(header, 2)
        val width = u16(header, 4)
        val height = u16(header, 6)
        val encodingType = u32(header, 8)

        // A rectangle that extends past the framebuffer we allocated means the
        // server resized the desktop; grow to fit rather than clip everything.
        if (x + width > _frameWidth.value || y + height > _frameHeight.value) {
            resizeFramebuffer(
                maxOf(_frameWidth.value, x + width),
                maxOf(_frameHeight.value, y + height)
            )
        }

        return when (encodingType) {
            ENCODING_RAW -> {
                if (width == 0 || height == 0) return false
                val pixelData = ByteArray(width * height * VncPixels.BYTES_PER_PIXEL)
                readFully(input, pixelData)
                // §1.164: decoded straight into the framebuffer. This used to build an
                // IntArray(width * height) and then copy its rows in — 8.3 MB of
                // garbage and a second full traversal per full-screen update, on the
                // receive loop. The destination offsets were already known before the
                // first pixel was converted, so the intermediate buffer bought nothing.
                val written = VncPixels.decodeArgbInto(
                    dest = pixels,
                    destWidth = _frameWidth.value,
                    destHeight = _frameHeight.value,
                    x = x,
                    y = y,
                    srcWidth = width,
                    srcHeight = height,
                    bytes = pixelData
                ) > 0
                // §1.147: recorded only when pixels actually landed. The decode clips,
                // so a fully out-of-bounds rectangle wrote nothing and marking it
                // dirty would enlarge every upload for a region that did not change.
                if (written) dirty.union(x, y, width, height)
                written
            }
            ENCODING_COPY_RECT -> {
                val src = ByteArray(4)
                readFully(input, src)
                VncPixels.copyRect(
                    dest = pixels,
                    destWidth = _frameWidth.value,
                    destHeight = _frameHeight.value,
                    srcX = u16(src, 0),
                    srcY = u16(src, 2),
                    dstX = x,
                    dstY = y,
                    width = width,
                    height = height
                )
                // §1.147: only the DESTINATION is dirty. The source region is
                // unchanged by a CopyRect — it is read, not written — so unioning it
                // as well would roughly double the uploaded area for the commonest
                // encoding on a window drag or scroll, which is the case this whole
                // change exists to make cheaper.
                val changed = width > 0 && height > 0
                if (changed) dirty.union(x, y, width, height)
                changed
            }
            ENCODING_ZRLE -> {
                // §1.151. The wire shape is a U32 compressed length followed by
                // that many bytes of the connection's zlib stream; the inflated
                // bytes are the tile stream [VncZrle] decodes. Note the length is
                // *compressed* — the inflated size is not transmitted, which is why
                // [VncZlibStream] grows its own buffer.
                val lengthBytes = ByteArray(4)
                readFully(input, lengthBytes)
                val compressedLength = u32(lengthBytes, 0)
                // A length is attacker- (or bug-) controlled and is allocated
                // immediately, so it is validated instead of trusted: `u32` returns a
                // signed Int, so a high bit reads negative (NegativeArraySizeException
                // from deep inside the receive loop), and a merely large value is an
                // OutOfMemoryError. Both are the same fact — the stream is not what we
                // think it is — and §1.114 settled that such a fact is reported, not
                // crashed on. The bound is generous: a full-screen 1920×1080 ZRLE
                // rectangle is a few hundred KB compressed.
                if (compressedLength < 0 || compressedLength > MAX_ZRLE_RECTANGLE_BYTES) {
                    throw IllegalStateException(
                        "ZRLE rectangle declares an implausible length $compressedLength; stream desynced"
                    )
                }
                val stream = zlibStream ?: VncZlibStream().also { zlibStream = it }
                val compressed = ByteArray(compressedLength)
                readFully(input, compressed)
                // Inflated unconditionally, before the extent is checked: a
                // zero-extent rectangle's bytes still belong to the shared stream,
                // so consuming them without inflating would desync the dictionary
                // for every rectangle after it.
                val inflatedLength = stream.inflate(compressed)
                if (width == 0 || height == 0) return false
                val written = VncZrle.decode(
                    data = stream.buffer,
                    dataLength = inflatedLength,
                    dest = pixels,
                    destWidth = _frameWidth.value,
                    destHeight = _frameHeight.value,
                    x = x,
                    y = y,
                    width = width,
                    height = height
                ) > 0
                // Same rule as Raw: recorded only when pixels actually landed, so a
                // fully out-of-bounds rectangle does not enlarge every later upload.
                if (written) dirty.union(x, y, width, height)
                written
            }
            ENCODING_DESKTOP_SIZE -> {
                // Pre-release audit fix (2026-09-02, finding A3): the
                // DesktopSize pseudo-encoding (−311, zero payload) is how an
                // RFB server announces a mid-session framebuffer resize
                // (xrandr / guest resolution change). The old path threw
                // "unsupported encoding ... session desynced" for it — the
                // receive loop died and the 3 reconnect attempts all hit a
                // still-resized server. Read the (empty) payload and resize
                // to the rect's announced geometry: the exact resize path the
                // client already supports.
                if (width <= 0 || height <= 0) {
                    throw IllegalStateException(
                        "DesktopSize pseudo-encoding announced an unusable geometry ${width}x$height"
                    )
                }
                resizeFramebuffer(width, height)
                true
            }
            else -> throw IllegalStateException(
                "Server sent unsupported encoding $encodingType after advertising Raw/CopyRect; VNC session desynced"
            )
        }
    }

    private fun publishFrame() {
        generation++
        _frame.value = VncFrame(
            pixels,
            _frameWidth.value,
            _frameHeight.value,
            generation,
            dirty.publish(generation)
        )
    }

    /**
     * §1.147 — called by the consumer once [generation]'s pixels are on screen,
     * releasing the dirty region it just uploaded.
     *
     * This exists because [frame] is a `StateFlow` and therefore conflates: the
     * consumer copies one frame at a time and so sees only the newest frame of a
     * burst, which means the *producer* cannot know which updates were actually
     * uploaded and must be told. Not calling it is safe in the only direction that
     * matters — the region keeps growing and uploads stay full-framebuffer, i.e. the
     * pre-§1.147 cost — so a consumer that ignores it is slow, never wrong.
     *
     * §1.154 — the release is per generation, so acknowledging late no longer throws
     * the whole union away: everything at or below [generation] is retired and
     * anything published after it is kept. See [VncDirtyRegion].
     *
     * §1.153 — the outcome is counted, because it is invisible on screen: a release
     * and a no-op both leave a correct picture and differ only in a cost that
     * accumulates. §1.154 changed what a no-op means — see
     * [VncRenderStats.recordAcknowledge]. The return value is deliberately dropped
     * here rather than propagated: this is the consumer's call site and there is
     * nothing useful it could do with it.
     */
    fun acknowledgeFrame(generation: Long) {
        stats.recordAcknowledge(dirty.acknowledge(generation))
    }

    /**
     * §1.153 — records a completed pixel copy, for [stats].
     *
     * Reported by the consumer because only it knows what was actually copied: the
     * region is the published one union'd with the back buffer's outstanding debt
     * (§1.150), and a measurement of the region *offered* rather than the one copied
     * would understate exactly the case worth catching.
     */
    fun recordUploadedArea(uploadedPixels: Long, framePixels: Long) =
        stats.recordUpload(uploadedPixels, framePixels)

    /** InputStream.read(ByteArray) can return short reads; loop until full. */
    private fun readFully(input: BufferedInputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read == -1) throw IllegalStateException("Connection closed while reading")
            offset += read
        }
    }

    /** Skips exactly [count] bytes; `skip` may return short, so it is looped. */
    private fun skipFully(input: BufferedInputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() == -1) throw IllegalStateException("Connection closed while skipping")
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun u32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 1] = (value and 0xFF).toByte()
    }

    /**
     * Closes the connection and stops every coroutine this client started.
     *
     * §1.153 — this is now the *only* thing that stops them. Before it, the receive
     * loop also died when the composition's scope was cancelled, so a missed
     * `disconnect` leaked a socket but not a coroutine; now it would leak both.
     * `DesktopScreen`'s `DisposableEffect(vncClient)` (§1.140) is what guarantees the
     * call, and it was added because `disconnect` was already the only thing that
     * closed the socket — the ownership was already here.
     *
     * [CoroutineScope.cancelChildren] rather than cancelling the scope's own `Job`:
     * cancelling the `Job` would make this client permanently unusable, and the
     * screen legitimately reconnects the *same* instance (`Running` → not-`Running`
     * → `Running`, e.g. a restart or a resume). Children are the receive loop and
     * any input write still queued behind it, which is exactly what must stop.
     */
    fun disconnect() {
        receiveJob?.cancel()
        receiveJob = null
        firstUpdateLogged = false
        _isConnected.value = false
        closeSocketQuietly()
        // Any pointer/key write still queued belongs to the connection being closed;
        // running it would write a closed stream and log a spurious failure.
        scope.coroutineContext.cancelChildren()
        stats.logSessionSummary()
        // §1.165: freeze the final numbers for the debug panel. Leaving this screen
        // disposes the client (§1.140), which is exactly when a user goes to read the
        // result of the scenario they just ran — and the elapsed window must be
        // captured now rather than at read time, or it would grow after the session
        // ended and flatter every derived rate.
        VncRenderStatsRegistry.retire()
        Logger.d(TAG, "Disconnected from VNC server")
    }

    private fun closeSocketQuietly() {
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        inputStream = null
        outputStream = null
        // §1.151: the ZRLE zlib stream belongs to this connection and holds native
        // memory. Ending it here also guarantees a reconnect starts a *new* stream —
        // reusing the old dictionary against a fresh server would decode garbage.
        try {
            zlibStream?.end()
        } catch (_: Exception) {}
        zlibStream = null
    }

    /**
     * Sends a key event using the X11 keysym derived from the physical
     * Android [keyCode] (§1.79). Passes both press and release through —
     * VNC servers treat a missing release as a stuck key, so key-up must be
     * forwarded, not swallowed. Keys without a keysym (volume/power/media)
     * are dropped.
     */
    /**
     * Sends an X11 keysym press or release.
     *
     * §1.79 established that both press AND release must be forwarded — a VNC
     * server treats a missing release as a key held down. §1.125 made the keysym
     * the parameter instead of an Android keycode: soft-keyboard input has no
     * keycode at all (an IME commits finished characters, whose keysyms come from
     * [XKeysyms.unicodeKeysym] via [VncTextInput]), while a hardware key still
     * resolves through [XKeysyms.androidKeysym] at its own call site — the *base*
     * key, so the X server applies the modifier itself. Both funnel here, so
     * neither path can get the wire format wrong independently.
     */
    fun sendKeysym(keysym: Int, pressed: Boolean) {
        // §1.153: one dispatch, not two. The nested `withContext(Dispatchers.IO)`
        // inside a Main-dispatched launch meant a key press reached the socket only
        // after a Main-queue turn — behind whatever recomposition and traversal the
        // last frame caused. The dispatcher is named on the launch rather than
        // inherited so a substituted [scope] cannot put a blocking write on Main.
        scope.launch(Dispatchers.IO) {
            try {
                val message = ByteArray(8)
                message[0] = MSG_KEY_EVENT.toByte()
                message[1] = if (pressed) 1 else 0
                // message[2..3] padding = 0
                message[4] = ((keysym shr 24) and 0xFF).toByte()
                message[5] = ((keysym shr 16) and 0xFF).toByte()
                message[6] = ((keysym shr 8) and 0xFF).toByte()
                message[7] = (keysym and 0xFF).toByte()
                outputStream?.write(message)
                outputStream?.flush()
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to send key event", e)
            }
        }
    }

    /**
     * §1.125: sends a keysym as a press **and** release.
     *
     * Typed text has no separate release event from the IME's point of view, and a
     * VNC server treats a missing release as a key held down — which wedges the
     * remote session on that character. Pairing them here means a new input path
     * cannot forget to.
     */
    fun sendKeysymPress(keysym: Int) {
        sendKeysym(keysym, pressed = true)
        sendKeysym(keysym, pressed = false)
    }

    /**
     * Sends a MouseWheel scroll as a press→release pair at the pointer's
     * current position (§1.79, corrected in §1.114). [deltaY] is in pixels;
     * positive scrolls down. Each gesture is bounded to a handful of wheel
     * clicks so one fast flick doesn't spam hundreds of frames.
     *
     * §1.114: the button *mask* bits are used ([BUTTON_MASK_WHEEL_UP] /
     * [BUTTON_MASK_WHEEL_DOWN]), not the button numbers 4/5 — writing 4 or 5
     * into the mask told the server that buttons 3+4 (or 1+4) were pressed, so
     * scrolling injected phantom clicks. The event is also sent at [x], [y]
     * rather than at the origin, which previously warped the remote pointer to
     * the top-left corner on every scroll.
     */
    fun sendWheel(x: Int, y: Int, deltaY: Float) {
        if (kotlin.math.abs(deltaY) < 1f) return
        val steps = (kotlin.math.abs(deltaY) / 40f).coerceIn(1f, 8f).toInt()
        val buttonMask = if (deltaY < 0) BUTTON_MASK_WHEEL_UP else BUTTON_MASK_WHEEL_DOWN
        // §1.153: one dispatch, on IO. See [sendKeysym].
        scope.launch(Dispatchers.IO) {
            val output = outputStream ?: return@launch
            try {
                repeat(steps) {
                    writePointerMessage(output, x, y, buttonMask)
                    writePointerMessage(output, x, y, 0)
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to send wheel", e)
            }
        }
    }

    private fun writePointerMessage(output: BufferedOutputStream, x: Int, y: Int, buttonMask: Int) {
        val message = ByteArray(6)
        message[0] = MSG_POINTER_EVENT.toByte()
        message[1] = buttonMask.toByte()
        writeU16(message, 2, x)
        writeU16(message, 4, y)
        output.write(message)
        output.flush()
    }

    fun sendPointerEvent(x: Int, y: Int, buttonMask: Int) {
        // §1.153: one dispatch, on IO. This is the write the audit singled out —
        // a pointer move that waits for a Main-queue turn delays the server's cursor
        // repaint, which delays the FramebufferUpdate the render path is waiting to
        // draw, so the finger and the remote cursor visibly diverge. §1.152's
        // `tcpNoDelay` could not reach it: the delay was before the write.
        scope.launch(Dispatchers.IO) {
            try {
                val output = outputStream ?: return@launch
                writePointerMessage(output, x, y, buttonMask)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to send pointer event", e)
            }
        }
    }
}
