package com.fem.linexa.service.runtime.features

/**
 * §1.113 — Pure (JVM-testable) logic for the Workspace **Audio** feature: the
 * guest-side consumer of the `audio` feature toggle
 * ([com.fem.linexa.data.WorkspaceContent] id `"audio"`).
 *
 * Same convention as [SshdProvisioner], [NotifySendProvisioner] and
 * [ClipboardBridgeProvisioner]: deterministic string work only (no Android, no
 * coroutines), so the whole FIFO → config → PCM-format chain is asserted in
 * plain JUnit tests. The suspend orchestration lives in
 * [WorkspaceAudioSupervisor]; `AudioTrack` lives behind [AudioSink] and the
 * blocking FIFO read behind [AudioSource].
 *
 * Design decisions (recorded in `Linexa-Roadmap.md` §1.113):
 *
 * - **Raw PCM through a FIFO inside the rootfs — not PulseAudio over TCP.**
 *   `Linexa-Roadmap.md` §7 line 465 suggested "PulseAudio forwarding over a Unix
 *   socket", but that was a sketch, never a §1 decision, and it costs one of two
 *   things: implementing the PulseAudio native protocol on the Android side, or
 *   vendoring a host-side `pulseaudio` binary (a new ABI-paired artifact, so a
 *   `bootstrap-manifest.json` + `BINARY-MANIFEST.md` update in the same commit
 *   per §1.71, and a second daemon to supervise). A FIFO carrying s16le PCM
 *   needs neither: the guest's *existing* audio stack already knows how to write
 *   raw PCM to a file, and Android's `AudioTrack` already knows how to play it.
 *   It also keeps §1.111/§1.112's property that the guest talks to **files** —
 *   no socket, no port, no bind mount, nothing another app on the device can
 *   connect to (`SecurityAudit`'s recorded "no network namespace isolation"
 *   limitation makes a loopback audio port reachable by every app on the phone,
 *   which is exactly what the file-based bridges were chosen to avoid).
 *
 * - **A FIFO, not a regular file.** A regular file would grow without bound and
 *   give the reader no way to wait for data; a FIFO gives blocking reads and
 *   real backpressure (a guest that produces faster than the host consumes is
 *   made to wait, rather than filling the workspace's storage). The cost is
 *   recorded honestly: `mkfifo` must work inside the rootfs on app-private
 *   storage, which is **unverified on device** — if it fails, [prepareCommand]
 *   exits non-zero and the feature reports `Failed` rather than half-working.
 *
 * - **The format is pinned here, once, for both sides.** [SAMPLE_RATE_HZ] /
 *   [CHANNEL_COUNT] / s16le are interpolated into the guest config *and* read by
 *   [AudioSink]; a mismatch would not fail loudly, it would play noise at the
 *   wrong pitch, so a test pins the two views equal. 48 kHz because that is the
 *   native rate of essentially every Android device — resampling on the guest
 *   side (where a config already exists) is cheaper and more predictable than
 *   resampling in the sink.
 *
 * - **ALSA is the primary mechanism; PulseAudio is additive and never
 *   installed.** [alsaConfigScript] points ALSA's default device at the FIFO,
 *   which covers everything that talks ALSA directly (`aplay`, `ffmpeg`, `mpv
 *   --ao=alsa`, and any pulse-less rootfs) using only the `file` plugin built
 *   into alsa-lib — no package to install. PulseAudio, when a server is
 *   *already running* in the guest, is wired by [PACTL_LOAD_SINK_COMMAND]
 *   instead: `pactl` at runtime rather than surgery on the distro's
 *   `default.pa`, because a config file we edit is a config file we then have to
 *   un-edit correctly, and a module we loaded can simply be unloaded. Unlike
 *   §1.109's sshd, nothing here auto-installs a package: the ALSA path already
 *   works, so pulling in PulseAudio's dependency tree to duplicate it would be
 *   a large, silent, unrequested change to the user's workspace.
 *
 * - **An existing `/etc/asound.conf` is never overwritten.** Same rule as
 *   §1.112's alias conditionality: a workspace whose owner configured ALSA
 *   themselves has a working setup, and replacing it would *break* audio rather
 *   than bridge it. The feature then reports which mechanisms it actually
 *   provided instead of claiming success.
 */
object AudioBridgeProvisioner {

    /** The workspace feature id this provisioner consumes (`WorkspaceContent`). */
    const val FEATURE_AUDIO = "audio"

    /** Root of the guest-visible bridge, inside the rootfs. */
    const val BRIDGE_GUEST_DIR = "/var/spool/linexa-audio"

    /**
     * The FIFO the guest writes PCM into and the host reads.
     *
     * Under `/var/spool` for the same reason §1.111/§1.112 are: it is a
     * directory that genuinely exists in every rootfs Linexa installs, and
     * keeping all three bridges in one place makes the "the guest talks to files
     * in one directory" rule visible rather than asserted.
     */
    const val SINK_GUEST_PATH = "$BRIDGE_GUEST_DIR/sink"

    /**
     * The same FIFO as the *host* resolves it, relative to the rootfs root. Kept
     * adjacent to the guest path so the two views cannot drift (pinned equal by
     * a test, as in §1.112).
     */
    const val SINK_RELATIVE_PATH = "var/spool/linexa-audio/sink"

    /** Guest path of the ALSA config this feature owns. */
    const val ASOUND_CONF_GUEST_PATH = "/etc/asound.conf"

    /**
     * PCM format, pinned for both sides. s16le/48000/stereo: 48 kHz is the
     * native rate of essentially every Android device, and signed 16-bit
     * little-endian is the one format both `AudioTrack` and every guest audio
     * stack handle without conversion.
     */
    const val SAMPLE_RATE_HZ = 48_000
    const val CHANNEL_COUNT = 2
    const val BYTES_PER_SAMPLE = 2

    /** ALSA's spelling of [BYTES_PER_SAMPLE]-wide signed little-endian samples. */
    const val ALSA_FORMAT = "S16_LE"

    /** PulseAudio's spelling of the same format. */
    const val PULSE_FORMAT = "s16le"

    /** The sink name PulseAudio gets, so it can be unloaded by name. */
    const val PULSE_SINK_NAME = "linexa"

    /**
     * Bytes read from the FIFO per pass. ~21 ms of audio at [SAMPLE_RATE_HZ]
     * stereo — small enough that the bridge does not add audible latency,
     * large enough that a read syscall is not performed per millisecond.
     */
    const val READ_BUFFER_BYTES = 4096

    /**
     * The marker every generated guest file carries, so [uninstallCommand] can
     * recognise its own work. Plain ASCII for the same reason as §1.112's: a
     * non-ASCII byte in a `grep` pattern inside a generated shell command is a
     * portability risk across guest shells.
     */
    const val MARKER = "Linexa audio"

    /**
     * Creates the bridge directory and the FIFO, and nothing else.
     *
     * Deliberately **separate from [installAlsaCommand]** so the supervisor can
     * open the FIFO for reading *before* any guest config points at it. That
     * ordering matters: ALSA's `file` plugin opens its target `O_WRONLY`, which
     * on a FIFO blocks until a reader exists, so pointing the guest's default
     * device at an unread FIFO would hang the first program that tried to play
     * something.
     *
     * Idempotent: an existing FIFO is left alone; a *regular file* of that name
     * (a stale artifact, or a guest that wrote to the path before the FIFO
     * existed) is replaced, because a regular file would silently "work" while
     * growing without bound.
     */
    fun prepareCommand(fifo: String = SINK_GUEST_PATH, dir: String = BRIDGE_GUEST_DIR): String =
        "mkdir -p $dir && { [ -p $fifo ] || { rm -f $fifo && mkfifo $fifo; }; } && chmod 0666 $fifo"

    /**
     * The ALSA configuration that routes the guest's default PCM device into the
     * bridge.
     *
     * `type plug` in front does rate/channel/format conversion, so a program
     * that only knows how to emit 44.1 kHz mono still plays; the `file` slave
     * writes the converted stream to the FIFO. `slave.pcm null` is what gives
     * the `file` plugin its timing — without a slave it would have no clock and
     * would run as fast as the program could produce samples.
     */
    fun alsaConfigScript(
        fifo: String = SINK_GUEST_PATH,
        sampleRate: Int = SAMPLE_RATE_HZ,
        channels: Int = CHANNEL_COUNT
    ): String = """
        # $MARKER bridge (§1.113) — routes this workspace's default ALSA device
        # into Linexa, which plays it through Android. Managed by Linexa: it is
        # removed when the Audio feature is switched off.
        pcm.!default {
            type plug
            slave {
                pcm "linexa_bridge"
                rate $sampleRate
                channels $channels
                format $ALSA_FORMAT
            }
        }
        pcm.linexa_bridge {
            type file
            slave.pcm null
            file "$fifo"
            format raw
        }
        ctl.!default {
            type hw
            card 0
        }
    """.trimIndent() + "\n"

    /**
     * Installs [alsaConfigScript] **only if the guest has no ALSA configuration
     * of its own** — or if the one present is ours, in which case it is
     * refreshed (so an app update replaces an older bridge config).
     *
     * Exits 0 either way: the supervisor distinguishes "installed" from "left
     * alone" by re-reading the file, not by an exit code, because "the user
     * configured ALSA themselves" is a legitimate state and not a failure.
     */
    fun installAlsaCommand(path: String = ASOUND_CONF_GUEST_PATH): String = buildString {
        append("if [ -e ")
        append(path)
        append(" ] && ! grep -q '")
        append(MARKER)
        append("' ")
        append(path)
        append(" 2>/dev/null; then exit 0; fi; ")
        append("cat > ")
        append(path)
        append(" <<'LINEXA_EOF'\n")
        append(alsaConfigScript())
        append("LINEXA_EOF\n")
        append("chmod 0644 ")
        append(path)
    }

    /**
     * Verifies that the installed ALSA config is the bridge's. Printed output is
     * what the supervisor keys on, because [installAlsaCommand] deliberately
     * exits 0 in the "left the user's own config alone" case.
     */
    fun verifyAlsaCommand(path: String = ASOUND_CONF_GUEST_PATH): String =
        "grep -q '$MARKER' $path 2>/dev/null && echo $ALSA_OK"

    /** The token [verifyAlsaCommand] prints when the bridge owns the config. */
    const val ALSA_OK = "linexa-alsa-ok"

    /** The token [PACTL_PROBE_COMMAND] prints when a pulse server answered. */
    const val PULSE_OK = "linexa-pulse-ok"

    /**
     * Probes for a *running* PulseAudio server. Nothing is installed on the
     * strength of this — a guest without pulse simply uses the ALSA path.
     */
    const val PACTL_PROBE_COMMAND =
        "command -v pactl >/dev/null 2>&1 && pactl info >/dev/null 2>&1 && echo $PULSE_OK"

    /**
     * Loads a pipe sink into the running PulseAudio server and makes it the
     * default, idempotently (an existing sink of that name short-circuits).
     *
     * `pactl` rather than an edit to `/etc/pulse/default.pa`: a config file we
     * write is a config file we then have to un-write correctly across distro
     * variations, while a module we loaded can be unloaded by name. It also
     * means the guest's own audio configuration is never modified.
     */
    val PACTL_LOAD_SINK_COMMAND: String =
        "if pactl list short sinks 2>/dev/null | grep -q '$PULSE_SINK_NAME'; then exit 0; fi; " +
            "pactl load-module module-pipe-sink " +
            "file=$SINK_GUEST_PATH " +
            "sink_name=$PULSE_SINK_NAME " +
            "rate=$SAMPLE_RATE_HZ " +
            "channels=$CHANNEL_COUNT " +
            "format=$PULSE_FORMAT && " +
            "pactl set-default-sink $PULSE_SINK_NAME"

    /** Unloads the pipe sink again. Idempotent; a guest without pulse exits 0. */
    val PACTL_UNLOAD_SINK_COMMAND: String =
        "command -v pactl >/dev/null 2>&1 || exit 0; " +
            "pactl unload-module module-pipe-sink 2>/dev/null; exit 0"

    /**
     * Removes everything the bridge installed: the pulse module, the ALSA config
     * (**only** when it carries our marker, so a config the user wrote is never
     * deleted — §1.112's rule), and the FIFO itself.
     *
     * The FIFO goes because leaving it would leave a path that blocks any writer
     * forever once the host stops reading — the audio equivalent of a hang, and
     * worse than the feature simply being absent.
     */
    fun uninstallCommand(
        path: String = ASOUND_CONF_GUEST_PATH,
        fifo: String = SINK_GUEST_PATH
    ): String = buildString {
        append(PACTL_UNLOAD_SINK_COMMAND)
        append("; if [ -f ")
        append(path)
        append(" ] && grep -q '")
        append(MARKER)
        append("' ")
        append(path)
        append(" 2>/dev/null; then rm -f ")
        append(path)
        append("; fi; rm -f ")
        append(fifo)
        append(" 2>/dev/null; exit 0")
    }

    /** Whether [output] contains the token a probe prints on success. */
    fun probeSucceeded(output: String, token: String): Boolean =
        output.lineSequence().any { it.trim() == token }

    /**
     * Byte rate of the pinned format — the reference a test uses to keep the
     * guest config and [AudioSink] from drifting apart, and the number that
     * makes [READ_BUFFER_BYTES]'s latency claim checkable.
     */
    val bytesPerSecond: Int get() = SAMPLE_RATE_HZ * CHANNEL_COUNT * BYTES_PER_SAMPLE
}
