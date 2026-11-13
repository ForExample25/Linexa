package com.fem.linexa.service.runtime.features

/**
 * UI/UX Phase 6 — the per-workspace *configuration* of the features that
 * genuinely have any, as a pure value plus its persistence encoding.
 *
 * ## Why this type is so small
 *
 * Phase 6's binding constraint is "do not add configuration options that are not
 * actually supported by the backend". Auditing the five features' guest-side
 * halves against that rule leaves exactly two parameters, and the other three
 * features are deliberately *not* represented here at all:
 *
 * - **SSH → [sshPort].** [SshdProvisioner.daemonCommand] already takes a `port`
 *   parameter and every caller passed the [SshdProvisioner.SSH_PORT] default, so
 *   the backend supported a variable port before any UI existed.
 * - **Clipboard → [clipboardAliases].** [WorkspaceClipboardSupervisor] installs
 *   every entry of [ClipboardBridgeProvisioner.ALIASES] one at a time, each with
 *   its own guarded exec, and already reports the resulting set as genuinely
 *   variable (`ClipboardFeatureState.Active.aliases`). Choosing a subset changes
 *   which of those execs run and nothing else.
 * - **Audio — nothing.** The obvious candidates (sample rate, channel count) are
 *   read from `AudioBridgeProvisioner` by *both* sides: the guest's ALSA config
 *   (`alsaConfigScript`) and the host's `AudioTrack`
 *   (`AndroidAudioSink.buildTrack`). They are not two settings that happen to
 *   match, they are one wire format that must agree — a UI that let them diverge
 *   would play noise. Making it configurable means parameterising the sink, which
 *   is a change to shipped §1.113 playback behaviour, not a UI gap.
 * - **Shared Storage — nothing.** The guest mount point is the literal
 *   `/mnt/shared` inside `PRootLauncher.sharedStorageBind` and the host side is
 *   `{externalFilesDir}/shared`, which the app owns. Neither end is a parameter,
 *   and §1.73's `launch.bindMounts` already covers "bind something else".
 * - **Notifications — nothing.** Every constant in [NotifySendProvisioner] is a
 *   safety bound (record/summary/body sizes) or a path; urgency arrives in the
 *   guest's own `notify-send` argv. There is no knob, only limits.
 *
 * So the asymmetry in the UI — two features with an expandable panel, three
 * without — is a *report* of what the backends parameterise, not an unfinished
 * job. Adding a row here without a backend parameter behind it is the failure
 * this KDoc exists to prevent.
 *
 * ## Desktop Modes (2026-08) — the desktop run mode joins the panel
 *
 * The `desktop` feature's settings panel is the third configurable one, and its
 * fields parameterise a backend that genuinely reads them all:
 * [com.fem.linexa.ui.screens.desktop.DesktopViewModel] consumes the run mode,
 * the VNC resolution preset / ZRLE opt-in / optional VNC password, and the
 * WebView mode's port and guest command at *start* time. Like [sshPort], these
 * take effect the next time the desktop starts — a running Xvnc or web server
 * is not restarted underneath the user.
 *
 * The VNC password is stored in the same plaintext Room attributes as every
 * other setting. That is an acceptable trade for what it protects — the VNC
 * endpoint is loopback-only, and the password's threat model is *other apps on
 * the device*, not disk-level attackers — but it is stated here rather than
 * left implicit, and [com.fem.linexa.ui.screens.desktop.DesktopVncAuthProvisioner]
 * generates it via [java.security.SecureRandom] so the default story is "a
 * password the user never chose and never has to type".
 *
 * ## Where it lives
 *
 * Persisted in `WorkspaceMetadata.attributes` (a `Map<String, String>` with a
 * live Room converter) under the [KEY_SSH_PORT] / [KEY_CLIPBOARD_ALIASES] keys,
 * **not** in `featureToggles` and **not** in a new column:
 *
 * - `featureToggles` is `Map<String, Boolean>` — a port has nowhere to live in it.
 * - A new column would change the Room schema, and the database is declared
 *   `version = 1` with a plain `build()` and no migration (`AppModule`), so a
 *   schema change throws an identity-hash mismatch on every existing install.
 *   `attributes` is already the map §1.117/`WorkspaceCapabilities` uses for
 *   exactly this "structured extra fact about a workspace" purpose.
 *
 * ## Reading is fail-open, writing is validated
 *
 * [from] never fails: an unparseable or out-of-range persisted value resolves to
 * the default, the same stance §1.27 takes for config.yaml application. A stored
 * value can only become invalid through a downgrade or a hand-edited database,
 * and refusing to launch a workspace over it would be a far worse outcome than
 * quietly using 8022. Validation lives on the *write* path
 * ([validatePort], [normalizeAliases]) where a person is present to be told.
 */
data class FeatureSettings(
    /**
     * The port the guest's sshd listens on. Defaults to
     * [SshdProvisioner.SSH_PORT], i.e. the pre-Phase-6 behaviour.
     */
    val sshPort: Int = SshdProvisioner.SSH_PORT,
    /**
     * The clipboard command aliases to provide in the guest, as names drawn from
     * [ClipboardBridgeProvisioner.ALIASES]. Defaults to all of them (the
     * pre-Phase-6 behaviour).
     *
     * An **empty** set is a legitimate, distinct choice: install the canonical
     * `linexa-clip-copy`/`-paste` shims and shadow nothing. That is why the
     * persisted encoding distinguishes an absent key (→ all) from a present but
     * blank one (→ none), the same tri-state as §1.120's `shortcuts:`.
     */
    val clipboardAliases: Set<String> = ALL_ALIAS_NAMES,
    /**
     * How the Desktop screen runs this workspace (Desktop Modes work).
     * Defaults to [DesktopRunMode.VNC], the pre-Desktop-Modes behaviour.
     */
    val desktopMode: DesktopRunMode = DesktopRunMode.DEFAULT,
    /**
     * The Xvnc framebuffer geometry preset for the VNC run mode. [DesktopResolution.AUTO]
     * keeps the display-derived §1.125/§1.138 geometry.
     */
    val desktopResolution: DesktopResolution = DesktopResolution.AUTO,
    /**
     * Whether the VNC client may offer the ZRLE encoding (§1.151/§1.152).
     *
     * Default **false**, unchanged from the §1.152 decision: on the loopback
     * transport the deflate/inflate both run on the one phone CPU, so offering
     * ZRLE is a measured pessimisation *for local use*. The opt-in exists for
     * the non-loopback transports — `adb reverse` port forwarding to a PC, or a
     * future remote viewer — where the bandwidth it saves is real.
     */
    val desktopVncPreferZrle: Boolean = false,
    /**
     * The VNC authentication password for the VNC run mode, or **empty** for
     * `SecurityTypes None` — the pre-Desktop-Modes behaviour, kept as the
     * default so an upgrade cannot lock anyone out of their own desktop.
     *
     * When set, the ViewModel provisions the password with the guest's own
     * `vncpasswd` into the guest FS and starts Xvnc with `-rfbauth`; the
     * embedded client then performs the RFB VNC-Auth challenge/response
     * ([com.fem.linexa.ui.screens.desktop.VncAuth]). The password never has to
     * be typed anywhere — the client and the server are both Linexa's — so the
     * panel's Generate button is the intended flow, and the empty default is a
     * choice the user made, not an accident.
     */
    val desktopVncPassword: String = "",
    /**
     * The TCP port the WebView run mode's guest web server listens on
     * (validated like [sshPort] on the write path). The app connects to it on
     * the device loopback — the guest shares the host network stack, so the
     * server the guest daemon starts is reachable at `127.0.0.1:<port>`.
     */
    val desktopWebPort: Int = DEFAULT_WEB_PORT,
    /**
     * The guest command the WebView run mode starts as its supervised daemon,
     * or **empty** = not configured. Free text by design — the whole point of
     * the mode is that the guest's web stack belongs to the user — and subject
     * to the same trust boundary as every custom shortcut: it runs inside the
     * user's own workspace with the user's own authority, so the panel that
     * edits it is the only validation the value gets.
     */
    val desktopWebCommand: String = "",
    /**
     * Desktop Backends: which GUI delivery backend the desktop uses — X11
     * (preferred, real X11 stack + X-socket readiness) or VNC (compatibility
     * fallback). Defaults to [GuiBackend.DEFAULT] per §11 of the
     * transformation spec; a failed X11 start offers the VNC path explicitly
     * instead of silently switching ([DesktopUiState.X11Failed]).
     */
    val desktopBackend: GuiBackend = GuiBackend.DEFAULT,
    /**
     * Desktop Provisioning: the desktop environment preset to offer when the
     * session probe finds no X client. [DesktopPreset.NONE] keeps the
     * pre-provisioning behaviour ([DesktopUiState.NoSession] with a hint);
     * a chosen preset is installed through the guest's own package manager
     * (idempotently, probe-first) exactly like the Xvnc auto-install.
     */
    val desktopPreset: DesktopPreset = DesktopPreset.NONE,
    /**
     * Desktop Touch work (2026-08): how finger touches map onto the remote
     * pointer in the VNC run mode's embedded viewer — touchpad-relative
     * (historical, default) or direct/absolute. Read live by the viewer per
     * touch event, so unlike the other desktop settings it takes effect
     * without restarting the desktop.
     */
    val desktopTouchMode: DesktopTouchMode = DesktopTouchMode.DEFAULT,
    /**
     * Session Persistence: whether new terminals for this workspace run
     * wrapped in the per-workspace tmux backend (create-or-reattach), so a
     * closed terminal can be re-entered with its state intact while the
     * workspace runtime stays alive. Default **false** — the §1.76 direct
     * PTY → proot → shell shape is the proven core path; persistence is an
     * explicit opt-in with documented Android limits (an app-process death
     * still ends the guest, and Android may kill background runtimes the
     * OS does not permit to persist).
     */
    val terminalPersistence: Boolean = false,
    /**
     * P11: the stored VNC password exists but could not be decrypted (the Keystore key
     * is gone or the value is damaged), so [desktopVncPassword] is empty *because it is
     * unreadable*, not because the user chose no authentication.
     *
     * The distinction matters: an empty password means "start Xvnc with no VNC auth", so
     * without this flag a lost key would silently turn the desktop into an
     * unauthenticated one. The desktop start paths fail closed on it instead
     * (`DesktopVncAuthProvisioner.authRequirement`). Only meaningful while
     * [desktopVncPassword] is empty; editing the password field clears it.
     */
    val desktopVncPasswordUnreadable: Boolean = false
) {

    /** The password is a secret and this is a `data class`: keep it out of logs. */
    override fun toString(): String =
        "FeatureSettings(sshPort=$sshPort, desktopMode=$desktopMode, " +
            "desktopVncPassword=${if (desktopVncPassword.isEmpty()) "<empty>" else "<redacted>"}, " +
            "desktopVncPasswordUnreadable=$desktopVncPasswordUnreadable, " +
            "desktopBackend=$desktopBackend, terminalPersistence=$terminalPersistence)"

    /**
     * The [ClipboardBridgeProvisioner.ALIASES] entries this workspace selected,
     * in the catalog's own order.
     *
     * The supervisor iterates *this* rather than the full catalog. Returning the
     * typed entries (not the names) keeps the alias's `kind` — and therefore
     * which shim it dispatches to — owned by the catalog, so a selection can
     * never invent an alias or change what an existing one does.
     */
    val selectedAliases: List<ClipboardAlias>
        get() = ClipboardBridgeProvisioner.ALIASES.filter { it.name in clipboardAliases }

    /**
     * This value encoded back into attribute entries, for merging into a
     * workspace's `attributes` map.
     *
     * Always writes both keys, including when they equal the defaults: a
     * *chosen* default and an absent value are the same thing to [from], but
     * writing them makes the persisted record self-describing, and an empty
     * alias selection has to be written explicitly anyway (see the tri-state
     * above) — so writing one key conditionally and the other always would be
     * two rules where one will do.
     */
    fun toAttributes(): Map<String, String> = mapOf(
        KEY_SSH_PORT to sshPort.toString(),
        KEY_CLIPBOARD_ALIASES to clipboardAliases
            .let { selected -> ClipboardBridgeProvisioner.ALIASES.map { it.name }.filter { it in selected } }
            .joinToString(ALIAS_SEPARATOR),
        KEY_DESKTOP_MODE to desktopMode.id,
        KEY_DESKTOP_RESOLUTION to desktopResolution.id,
        KEY_DESKTOP_VNC_ZRLE to desktopVncPreferZrle.toString(),
        KEY_DESKTOP_VNC_PASSWORD to desktopVncPassword,
        // Always written (true or false): the attributes merge cannot remove a key, so a
        // stale "true" would otherwise outlive the password the user just set.
        KEY_DESKTOP_VNC_PASSWORD_UNREADABLE to
            (desktopVncPasswordUnreadable && desktopVncPassword.isEmpty()).toString(),
        KEY_DESKTOP_WEB_PORT to desktopWebPort.toString(),
        KEY_DESKTOP_WEB_COMMAND to desktopWebCommand,
        KEY_DESKTOP_BACKEND to desktopBackend.id,
        KEY_DESKTOP_PRESET to desktopPreset.id,
        KEY_DESKTOP_TOUCH_MODE to desktopTouchMode.id,
        KEY_TERMINAL_PERSISTENCE to terminalPersistence.toString()
    )

    companion object {

        /** Attribute key holding the guest sshd's listen port, as a decimal string. */
        const val KEY_SSH_PORT = "feature.ssh.port"

        /**
         * Attribute key holding the selected clipboard aliases as a
         * [ALIAS_SEPARATOR]-joined list of catalog names. Absent → all aliases;
         * present and blank → none.
         */
        const val KEY_CLIPBOARD_ALIASES = "feature.clipboard.aliases"

        /** Attribute key holding the [DesktopRunMode] id for the Desktop feature. */
        const val KEY_DESKTOP_MODE = "feature.desktop.mode"

        /** Attribute key holding the [DesktopResolution] id for the VNC run mode. */
        const val KEY_DESKTOP_RESOLUTION = "feature.desktop.resolution"

        /** Attribute key holding the VNC run mode's ZRLE opt-in, as a boolean string. */
        const val KEY_DESKTOP_VNC_ZRLE = "feature.desktop.vnc.zrle"

        /**
         * Attribute key holding the VNC run mode's password. Empty string and
         * absent both mean "no VNC authentication" — unlike the clipboard
         * aliases' tri-state, there is no third state to distinguish.
         */
        const val KEY_DESKTOP_VNC_PASSWORD = "feature.desktop.vnc.password"

        /**
         * Attribute key marking that the stored [KEY_DESKTOP_VNC_PASSWORD] could not be
         * decrypted. Written by the repository's secret handling and by [toAttributes];
         * see [desktopVncPasswordUnreadable].
         */
        const val KEY_DESKTOP_VNC_PASSWORD_UNREADABLE = "feature.desktop.vnc.password.unreadable"

        /** Attribute key holding the WebView run mode's guest web server port. */
        const val KEY_DESKTOP_WEB_PORT = "feature.desktop.web.port"

        /** Attribute key holding the WebView run mode's guest start command. */
        const val KEY_DESKTOP_WEB_COMMAND = "feature.desktop.web.command"

        /** Attribute key holding the [GuiBackend] id for the Desktop feature. */
        const val KEY_DESKTOP_BACKEND = "feature.desktop.backend"

        /** Attribute key holding the [DesktopPreset] id for the Desktop feature. */
        const val KEY_DESKTOP_PRESET = "feature.desktop.preset"

        /** Attribute key holding the [DesktopTouchMode] id for the VNC viewer. */
        const val KEY_DESKTOP_TOUCH_MODE = "feature.desktop.touchMode"

        /** Attribute key holding the tmux session-persistence opt-in. */
        const val KEY_TERMINAL_PERSISTENCE = "feature.terminal.persistence"

        /** The WebView run mode's default guest web server port. */
        const val DEFAULT_WEB_PORT = 8080

        /** Separator for [KEY_CLIPBOARD_ALIASES]. No alias name contains it. */
        const val ALIAS_SEPARATOR = ","

        /**
         * Lowest selectable SSH port.
         *
         * Ports below 1024 are privileged **on the host**, and that is the end
         * that matters: the guest has emulated root (`proot -0`) but the real
         * process performing the `bind` does not, so a privileged port fails at
         * runtime with a permission error the user cannot act on. Offering it
         * would be offering a setting that cannot work — see [SshdProvisioner]'s
         * own note on why the default is 8022 rather than 22.
         */
        const val MIN_PORT = 1024

        /** Highest selectable SSH port (the TCP maximum). */
        const val MAX_PORT = 65535

        /** Every alias name the clipboard backend knows how to install. */
        val ALL_ALIAS_NAMES: Set<String> =
            ClipboardBridgeProvisioner.ALIASES.map { it.name }.toSet()

        /**
         * Resolve a workspace's feature settings from its persisted attributes.
         *
         * Total and fail-open by construction — see the class KDoc. Every
         * rejection path lands on the same defaults the app used before Phase 6,
         * so a corrupt value degrades to "the feature works as it always did"
         * rather than to a broken workspace.
         */
        fun from(attributes: Map<String, String>): FeatureSettings = FeatureSettings(
            sshPort = attributes[KEY_SSH_PORT]
                ?.trim()
                ?.toIntOrNull()
                ?.takeIf { it in MIN_PORT..MAX_PORT }
                ?: SshdProvisioner.SSH_PORT,
            clipboardAliases = attributes[KEY_CLIPBOARD_ALIASES]
                // Absent means "never chosen" → every alias, the pre-Phase-6
                // behaviour. A present-but-blank value means "chose none" and
                // must NOT fall through to the default, which is the whole
                // reason this reads the raw entry instead of a parsed list.
                ?.let { raw -> normalizeAliases(raw.split(ALIAS_SEPARATOR)) }
                ?: ALL_ALIAS_NAMES,
            desktopMode = DesktopRunMode.fromId(attributes[KEY_DESKTOP_MODE]),
            desktopResolution = DesktopResolution.fromId(attributes[KEY_DESKTOP_RESOLUTION]),
            desktopVncPreferZrle = attributes[KEY_DESKTOP_VNC_ZRLE]?.trim() == "true",
            desktopVncPassword = attributes[KEY_DESKTOP_VNC_PASSWORD] ?: "",
            desktopVncPasswordUnreadable =
                attributes[KEY_DESKTOP_VNC_PASSWORD_UNREADABLE]?.trim() == "true" &&
                    (attributes[KEY_DESKTOP_VNC_PASSWORD] ?: "").isEmpty(),
            desktopWebPort = attributes[KEY_DESKTOP_WEB_PORT]
                ?.trim()
                ?.toIntOrNull()
                ?.takeIf { it in MIN_PORT..MAX_PORT }
                ?: DEFAULT_WEB_PORT,
            desktopWebCommand = attributes[KEY_DESKTOP_WEB_COMMAND] ?: "",
            desktopBackend = GuiBackend.fromId(attributes[KEY_DESKTOP_BACKEND]),
            desktopPreset = DesktopPreset.fromId(attributes[KEY_DESKTOP_PRESET]),
            desktopTouchMode = DesktopTouchMode.fromId(attributes[KEY_DESKTOP_TOUCH_MODE]),
            terminalPersistence = attributes[KEY_TERMINAL_PERSISTENCE]?.trim() == "true"
        )

        /**
         * Why [port] cannot be used, or null when it can.
         *
         * Returns a [PortRejection] rather than a message so the reason is a
         * value the UI renders from string resources — this layer holds no
         * user-facing text.
         */
        fun validatePort(port: Int?): PortRejection? = when {
            port == null -> PortRejection.NOT_A_NUMBER
            port < MIN_PORT -> PortRejection.PRIVILEGED
            port > MAX_PORT -> PortRejection.OUT_OF_RANGE
            else -> null
        }

        /**
         * Keep only names the clipboard catalog actually knows.
         *
         * Unknown names are dropped rather than carried through — the opposite
         * of [com.fem.linexa.data.resolveFeatureToggles]'s lossless treatment of
         * unknown toggle keys, and deliberately so. A toggle key is inert until
         * something reads it, whereas an alias name is a **file the app writes
         * into `/usr/local/bin`**: honouring an unrecognised one would mean
         * creating a guest command from a string that no catalog entry defines a
         * behaviour for. Resolution against a fixed table, never free text — the
         * same rule §1.120 applies to shortcut icons.
         */
        fun normalizeAliases(names: Iterable<String>): Set<String> =
            names.map { it.trim() }.filter { it in ALL_ALIAS_NAMES }.toSet()
    }

    /** Why a proposed SSH port was refused. Rendered by the UI, never here. */
    enum class PortRejection { NOT_A_NUMBER, PRIVILEGED, OUT_OF_RANGE }
}
