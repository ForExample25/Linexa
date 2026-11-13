package com.fem.linexa.service.runtime.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.113: [AudioBridgeProvisioner] — the pure half of the Workspace Audio
 * feature.
 *
 * What these tests are really protecting is a class of bug that does **not**
 * announce itself: an audio format mismatch between the guest config and the
 * Android sink does not fail, it plays noise at the wrong pitch and speed. So the
 * format is pinned from both directions, and the guest/host views of the FIFO path
 * are pinned equal (the §1.112 rule).
 */
class AudioBridgeProvisionerTest {

    // ── the feature id and the paths both sides use ──

    @Test
    fun theFeatureIdMatchesTheShippedToggle() {
        // The toggle in WorkspaceContent is what the user flips; a typo here would
        // make the whole bridge dead code that no toggle reaches.
        assertEquals("audio", AudioBridgeProvisioner.FEATURE_AUDIO)
        assertTrue(
            "the audio feature must exist in the shipped catalog",
            com.fem.linexa.data.WorkspaceContent.builtinFeatureDefaults
                .containsKey(AudioBridgeProvisioner.FEATURE_AUDIO)
        )
    }

    @Test
    fun theGuestAndHostViewsOfTheFifoAreTheSamePath() {
        // The guest opens an absolute path inside the rootfs; the host opens the
        // same file relative to the rootfs root. If these two drift, the pump
        // waits forever on a FIFO nothing writes to — and it looks like silence,
        // not like an error.
        assertEquals(
            "/" + AudioBridgeProvisioner.SINK_RELATIVE_PATH,
            AudioBridgeProvisioner.SINK_GUEST_PATH
        )
        assertTrue(AudioBridgeProvisioner.SINK_GUEST_PATH.startsWith(AudioBridgeProvisioner.BRIDGE_GUEST_DIR))
    }

    @Test
    fun theBridgeLivesWhereTheOtherFileBasedBridgesDo() {
        // §1.111/§1.112 both use /var/spool; keeping audio there is what makes
        // "the guest talks to files in one place" visible rather than asserted.
        assertTrue(AudioBridgeProvisioner.BRIDGE_GUEST_DIR.startsWith("/var/spool/"))
    }

    // ── the format, pinned once for both sides ──

    @Test
    fun theFormatIsPinnedAndConsistentlySpelledForBothAudioStacks() {
        // Same format, three spellings: the ALSA config, the pactl module and
        // AudioTrack's ENCODING_PCM_16BIT. A mismatch does not fail — it plays
        // noise — so the spellings are pinned to the one format they describe.
        assertEquals(48_000, AudioBridgeProvisioner.SAMPLE_RATE_HZ)
        assertEquals(2, AudioBridgeProvisioner.CHANNEL_COUNT)
        assertEquals(2, AudioBridgeProvisioner.BYTES_PER_SAMPLE)
        assertEquals("S16_LE", AudioBridgeProvisioner.ALSA_FORMAT)
        assertEquals("s16le", AudioBridgeProvisioner.PULSE_FORMAT)
        // Both spellings must describe 16-bit little-endian, i.e. BYTES_PER_SAMPLE.
        assertTrue(AudioBridgeProvisioner.ALSA_FORMAT.contains("16"))
        assertTrue(AudioBridgeProvisioner.PULSE_FORMAT.contains("16"))
    }

    @Test
    fun theAlsaConfigAndThePulseModuleAgreeOnRateAndChannels() {
        val alsa = AudioBridgeProvisioner.alsaConfigScript()
        val pulse = AudioBridgeProvisioner.PACTL_LOAD_SINK_COMMAND
        assertTrue(alsa.contains("rate ${AudioBridgeProvisioner.SAMPLE_RATE_HZ}"))
        assertTrue(alsa.contains("channels ${AudioBridgeProvisioner.CHANNEL_COUNT}"))
        assertTrue(pulse.contains("rate=${AudioBridgeProvisioner.SAMPLE_RATE_HZ}"))
        assertTrue(pulse.contains("channels=${AudioBridgeProvisioner.CHANNEL_COUNT}"))
    }

    @Test
    fun theReadBufferIsAboutTwentyMillisecondsOfAudio() {
        // The buffer size is the bridge's added latency. Small enough not to be
        // audible as lip-sync drift, large enough to avoid a syscall per
        // millisecond — this pins the claim in the kdoc to arithmetic.
        val millis = AudioBridgeProvisioner.READ_BUFFER_BYTES * 1000.0 /
            AudioBridgeProvisioner.bytesPerSecond
        assertTrue("latency was $millis ms", millis in 5.0..40.0)
    }

    @Test
    fun theByteRateFollowsFromThePinnedFormat() {
        assertEquals(48_000 * 2 * 2, AudioBridgeProvisioner.bytesPerSecond)
    }

    // ── prepare: a FIFO, idempotently ──

    @Test
    fun prepareCreatesAFifoAndNotARegularFile() {
        val command = AudioBridgeProvisioner.prepareCommand()
        assertTrue(command.contains("mkfifo ${AudioBridgeProvisioner.SINK_GUEST_PATH}"))
        assertTrue("the directory must exist first", command.contains("mkdir -p ${AudioBridgeProvisioner.BRIDGE_GUEST_DIR}"))
    }

    @Test
    fun prepareLeavesAnExistingFifoAlone() {
        // Re-running on every enable/launch must not disturb a FIFO a guest is
        // already writing to — recreating it would break that writer's fd.
        val command = AudioBridgeProvisioner.prepareCommand()
        assertTrue("must test for an existing FIFO", command.contains("[ -p ${AudioBridgeProvisioner.SINK_GUEST_PATH} ]"))
    }

    @Test
    fun prepareReplacesARegularFileAtThePath() {
        // A regular file there would silently "work" while growing without bound —
        // the worst outcome available, because it looks like success.
        val command = AudioBridgeProvisioner.prepareCommand()
        assertTrue(command.contains("rm -f ${AudioBridgeProvisioner.SINK_GUEST_PATH}"))
        // …but only in the branch where it is NOT already a FIFO.
        val fifoBranch = command.substringAfter("[ -p ")
        assertTrue(fifoBranch.indexOf("rm -f") > 0)
    }

    @Test
    fun prepareMakesTheFifoWritableByGuestPrograms() {
        // Guest programs may run as a non-root user; a FIFO only root can write to
        // would make audio fail for exactly those workspaces.
        assertTrue(AudioBridgeProvisioner.prepareCommand().contains("chmod 0666"))
    }

    @Test
    fun prepareFailsRatherThanContinuingWhenMkfifoIsRefused() {
        // The whole command is &&-chained, so a refused mkfifo yields a non-zero
        // rc — which is what lets the supervisor report Failed instead of
        // installing a bridge that leads nowhere. mkfifo on app-private storage
        // is §1.113's explicitly unverified assumption.
        val command = AudioBridgeProvisioner.prepareCommand()
        assertTrue(command.contains("&&"))
        assertFalse("a ';' would swallow the failure", command.contains("; chmod"))
    }

    // ── the ALSA config ──

    @Test
    fun theAlsaConfigRoutesTheDefaultDeviceIntoTheFifo() {
        val config = AudioBridgeProvisioner.alsaConfigScript()
        assertTrue("must take over the default PCM", config.contains("pcm.!default"))
        assertTrue(config.contains("type file"))
        assertTrue(config.contains("file \"${AudioBridgeProvisioner.SINK_GUEST_PATH}\""))
        assertTrue("raw PCM, no header", config.contains("format raw"))
    }

    @Test
    fun theAlsaConfigConvertsSoAProgramWithItsOwnRateStillPlays() {
        // Without `type plug` a program emitting 44.1 kHz mono would be refused by
        // the device rather than converted, and the feature would look broken for
        // everything except 48 kHz stereo sources.
        val config = AudioBridgeProvisioner.alsaConfigScript()
        assertTrue(config.contains("type plug"))
    }

    @Test
    fun theAlsaFilePluginHasATimingSlave() {
        // Without a slave the file plugin has no clock and drains samples as fast
        // as the program can produce them — audio would play at the wrong speed.
        assertTrue(AudioBridgeProvisioner.alsaConfigScript().contains("slave.pcm null"))
    }

    @Test
    fun theAlsaConfigSaysItIsManagedByLinexa() {
        // The marker is what uninstall keys on, and the comment is what tells a
        // user who finds this file why it is there.
        val config = AudioBridgeProvisioner.alsaConfigScript()
        assertTrue(config.contains(AudioBridgeProvisioner.MARKER))
        assertTrue(config.trimStart().startsWith("#"))
    }

    @Test
    fun theMarkerIsPlainAsciiSoGrepIsPortable() {
        // §1.112's rule: a non-ASCII byte in a grep pattern inside a generated
        // shell command is a portability risk across guest shells.
        assertTrue(AudioBridgeProvisioner.MARKER.all { it.code in 32..126 })
    }

    // ── install: never overwrite a config the user wrote ──

    @Test
    fun installNeverOverwritesAnAlsaConfigTheUserWrote() {
        // Same rule as §1.112's alias conditionality: a workspace whose owner
        // configured ALSA has a working setup, and replacing it would BREAK audio
        // rather than bridge it.
        val command = AudioBridgeProvisioner.installAlsaCommand()
        assertTrue(command.contains("[ -e ${AudioBridgeProvisioner.ASOUND_CONF_GUEST_PATH} ]"))
        assertTrue(command.contains("grep -q '${AudioBridgeProvisioner.MARKER}'"))
        assertTrue("must bail out before writing", command.indexOf("exit 0") < command.indexOf("cat > "))
    }

    @Test
    fun installRefreshesItsOwnConfigSoAnAppUpdateReplacesAnOldBridge() {
        // The guard is `-e AND NOT ours`, so a file that IS ours falls through to
        // the write.
        val command = AudioBridgeProvisioner.installAlsaCommand()
        assertTrue(command.contains("! grep -q"))
    }

    @Test
    fun installWritesTheConfigWithAQuotedHeredocSoNothingIsExpanded() {
        val command = AudioBridgeProvisioner.installAlsaCommand()
        assertTrue("quoted heredoc", command.contains("<<'LINEXA_EOF'"))
        assertTrue(command.contains("chmod 0644 ${AudioBridgeProvisioner.ASOUND_CONF_GUEST_PATH}"))
    }

    @Test
    fun theInstallCommandInterpolatesNoUserSuppliedString() {
        // §1.14: every byte comes from constants this object owns.
        val command = AudioBridgeProvisioner.installAlsaCommand()
        assertFalse(
            "a null must never reach a generated guest command",
            command.replace("/dev/null", "").replace("pcm null", "").contains("null")
        )
        assertFalse("no backticks", command.contains("`"))
        assertFalse("no command substitution", Regex("""\$\(""").containsMatchIn(command))
    }

    // ── verification, which is not the exit code ──

    @Test
    fun verificationIsByPrintedTokenNotByExitCode() {
        // installAlsaCommand deliberately exits 0 in the "left the user's config
        // alone" case, so the supervisor cannot use rc to tell the two apart.
        val command = AudioBridgeProvisioner.verifyAlsaCommand()
        assertTrue(command.contains("grep -q '${AudioBridgeProvisioner.MARKER}'"))
        assertTrue(command.contains("echo ${AudioBridgeProvisioner.ALSA_OK}"))
    }

    @Test
    fun aProbeTokenIsMatchedOnItsOwnLineOnly() {
        assertTrue(AudioBridgeProvisioner.probeSucceeded("linexa-alsa-ok\n", AudioBridgeProvisioner.ALSA_OK))
        assertTrue(
            "a token after other output still counts",
            AudioBridgeProvisioner.probeSucceeded("noise\nlinexa-alsa-ok\n", AudioBridgeProvisioner.ALSA_OK)
        )
        assertTrue(
            "surrounding whitespace is not significant",
            AudioBridgeProvisioner.probeSucceeded("  linexa-alsa-ok  ", AudioBridgeProvisioner.ALSA_OK)
        )
        assertFalse(AudioBridgeProvisioner.probeSucceeded("", AudioBridgeProvisioner.ALSA_OK))
        assertFalse(
            "a token mentioned inside other text is not a success",
            AudioBridgeProvisioner.probeSucceeded("cat: linexa-alsa-ok: no such file", AudioBridgeProvisioner.ALSA_OK)
        )
        // The two tokens must not be confusable with each other.
        assertFalse(AudioBridgeProvisioner.probeSucceeded("linexa-alsa-ok", AudioBridgeProvisioner.PULSE_OK))
    }

    // ── PulseAudio: additive, never installed ──

    @Test
    fun thePulseProbeRequiresARunningServerNotJustTheTool() {
        // `command -v pactl` alone is not enough: the tool exists in plenty of
        // rootfs images with no server running, and load-module would then fail on
        // every launch for no reason.
        val probe = AudioBridgeProvisioner.PACTL_PROBE_COMMAND
        assertTrue(probe.contains("command -v pactl"))
        assertTrue(probe.contains("pactl info"))
        assertTrue(probe.contains("echo ${AudioBridgeProvisioner.PULSE_OK}"))
    }

    @Test
    fun nothingHereInstallsAPackage() {
        // Unlike §1.109's sshd, the audio feature auto-installs nothing: the ALSA
        // path already works, so pulling in pulse's dependency tree to duplicate
        // it would be a large, silent, unrequested change to the user's workspace.
        val commands = listOf(
            AudioBridgeProvisioner.prepareCommand(),
            AudioBridgeProvisioner.installAlsaCommand(),
            AudioBridgeProvisioner.verifyAlsaCommand(),
            AudioBridgeProvisioner.PACTL_PROBE_COMMAND,
            AudioBridgeProvisioner.PACTL_LOAD_SINK_COMMAND,
            AudioBridgeProvisioner.PACTL_UNLOAD_SINK_COMMAND,
            AudioBridgeProvisioner.uninstallCommand()
        )
        commands.forEach { command ->
            listOf("apt-get", "apt ", "apk add", "pacman", "dnf install", "zypper").forEach { installer ->
                assertFalse("'$installer' in: $command", command.contains(installer))
            }
        }
    }

    @Test
    fun thePulseSinkIsLoadedIdempotentlyAndMadeDefault() {
        val command = AudioBridgeProvisioner.PACTL_LOAD_SINK_COMMAND
        assertTrue("an existing sink short-circuits", command.contains("pactl list short sinks"))
        assertTrue(command.contains("load-module module-pipe-sink"))
        assertTrue(command.contains("file=${AudioBridgeProvisioner.SINK_GUEST_PATH}"))
        assertTrue(command.contains("sink_name=${AudioBridgeProvisioner.PULSE_SINK_NAME}"))
        assertTrue(
            "a sink nothing routes to is pointless",
            command.contains("set-default-sink ${AudioBridgeProvisioner.PULSE_SINK_NAME}")
        )
    }

    @Test
    fun thePulseWiringNeverEditsTheGuestsOwnPulseConfig() {
        // A config file we write is one we then have to un-write correctly across
        // distro variations; a module we loaded can simply be unloaded.
        listOf(
            AudioBridgeProvisioner.PACTL_LOAD_SINK_COMMAND,
            AudioBridgeProvisioner.PACTL_UNLOAD_SINK_COMMAND
        ).forEach { command ->
            assertFalse(command.contains("default.pa"))
            assertFalse(command.contains("/etc/pulse"))
        }
    }

    @Test
    fun theUnloadIsSafeOnAGuestWithoutPulse() {
        val command = AudioBridgeProvisioner.PACTL_UNLOAD_SINK_COMMAND
        assertTrue(command.contains("command -v pactl >/dev/null 2>&1 || exit 0"))
        assertTrue("must never fail a teardown", command.trimEnd().endsWith("exit 0"))
    }

    // ── uninstall ──

    @Test
    fun uninstallRemovesOnlyItsOwnAlsaConfig() {
        // Deleting a config the user wrote would be worse than never having
        // installed the feature (§1.112's marker-guarded uninstall rule).
        val command = AudioBridgeProvisioner.uninstallCommand()
        assertTrue(command.contains("grep -q '${AudioBridgeProvisioner.MARKER}'"))
        val guardAt = command.indexOf("grep -q")
        val removeAt = command.indexOf("rm -f ${AudioBridgeProvisioner.ASOUND_CONF_GUEST_PATH}")
        assertTrue("the guard must precede the removal", guardAt in 0 until removeAt)
    }

    @Test
    fun uninstallRemovesTheFifoBecauseAnUnreadFifoBlocksItsWriter() {
        // Leaving the FIFO behind would leave a path that blocks any writer
        // forever once the host stops reading — a hang, which is worse than the
        // feature being absent.
        assertTrue(
            AudioBridgeProvisioner.uninstallCommand()
                .contains("rm -f ${AudioBridgeProvisioner.SINK_GUEST_PATH}")
        )
    }

    @Test
    fun uninstallUnloadsThePulseSinkFirst() {
        val command = AudioBridgeProvisioner.uninstallCommand()
        assertTrue(command.startsWith(AudioBridgeProvisioner.PACTL_UNLOAD_SINK_COMMAND))
    }

    @Test
    fun uninstallSucceedsOnAGuestThatNeverHadTheBridge() {
        val command = AudioBridgeProvisioner.uninstallCommand()
        assertTrue(command.trimEnd().endsWith("exit 0"))
        // Semicolons, not &&: one absent artifact must not stop the rest.
        assertTrue(command.contains("; "))
    }
}
