package com.fem.linexa.service.rootfs

import org.yaml.snakeyaml.Yaml

/**
 * §1.119 — reads what Linexa can legitimately learn from a legacy Tiny
 * Container `.tiny.yaml`, and nothing more.
 *
 * Linexa's native metadata ([RootFsMetadata]) and Tiny Container's container
 * descriptor answer different questions. `.tiny.yaml` is a *launcher* file:
 * its substance is `boot_command`, `export_command`, `quick_commands` and
 * `options` — shell strings and PRoot argv fragments meant to be executed by
 * Tiny Container. It does not state the two things §1.118 exists to state
 * (`distro.id`/`distro.name`, `architecture`), and it does state a great deal
 * that Linexa must never run.
 *
 * So this adapter is deliberately narrow. It extracts the keys a legacy file
 * can state *honestly* — the presentation keys `name`/`description`/`preview`
 * (§1.119/§1.121) — and stops:
 *
 *  - **No launcher commands, ever.** `boot_command`, `export_command`,
 *    `options[].args`, `pre_start_host_command`, … are ignored outright.
 *    Linexa's launch path is built from typed argv fragments it owns
 *    (§1.14/§1.73/§1.118); importing a foreign shell string there would hand an
 *    archive author arbitrary execution on the **host**, which is precisely the
 *    property §1.118's "metadata is data, never code" rule protects. This is
 *    not a TODO — it is the design.
 *
 *    §1.122 **did not** weaken that, and the Quick Commands re-architecture
 *    removed it outright: the legacy `quick_commands` flattener
 *    ([TinyQuickCommands], deleted) was replaced by the project's own
 *    distribution-aware catalog
 *    ([com.fem.linexa.service.commands.QuickCommandsCatalogLoader]). A
 *    `.tiny.yaml`'s `quick_commands` — and every other key below the
 *    presentation trio — now has **no effect** on anything Linexa renders or
 *    runs; the file is presentation-only forever.
 *  - **No distro/architecture guess.** `code: "trixie"` and
 *    `name: "Debian Trixie"` are free-form label text, not an os-release `ID`.
 *    Inferring `debian`/`arm64` from prose is exactly the guessing §1.118
 *    replaced, and it would be *persisted* as the workspace's identity and
 *    shown to the user as fact. [RootFsProbe][com.fem.linexa.service.installer.RootFsProbe]
 *    already answers this correctly from `/etc/os-release` and the archive's
 *    own entry names, so a legacy image keeps using it — unchanged from
 *    today's behaviour.
 *  - **No desktop declaration.** A `feature:` entry of type `avnc`/`x11`/
 *    `webview` says the *app* may show a graphical frontend, not which desktop
 *    (if any) the image ships, and §1.118 requires `desktop.type` whenever
 *    `desktop.enabled` is true precisely to forbid that half-declaration.
 *    Absent stays absent, so the desktop layer probes as it does today.
 *  - **No package manager declaration.** `.tiny.yaml` has no such field.
 *
 * **Fail-open, unlike the native schema.** A malformed or surprising
 * `.tiny.yaml` returns [Result.Absent] rather than failing the import. The
 * fail-hard stance of §1.26/§1.118 is earned by a file whose schema Linexa
 * defines and whose author can be told to fix it; `.tiny.yaml` is a foreign
 * format that Linexa ignored entirely before §1.119, and Tiny Container
 * images import successfully today. Turning an unparseable legacy file into a
 * newly-failing import would break working archives to gain nothing — the
 * only thing at stake here is a nicer pre-filled workspace name.
 */
object TinyMetadataAdapter {

    /** Same 64 KiB ceiling the native schema uses; `.tiny.yaml` files are ~30 KiB. */
    const val MAX_BYTES: Int = 64 * 1024

    private const val KEY_NAME = "name"
    private const val KEY_DESCRIPTION = "description"
    private const val KEY_PREVIEW = "preview"

    /** Upper bound on adopted text, so a hostile file cannot bloat the UI. */
    private const val MAX_NAME_CHARS = 120
    private const val MAX_DESCRIPTION_CHARS = 2000

    sealed interface Result {
        /** Nothing usable — unparseable, empty, or no readable keys. */
        data object Absent : Result

        /**
         * What Linexa adopted: presentation text only. Never identity, never a
         * command, never a shortcut — a legacy file's `quick_commands` has no
         * reader anywhere in this app anymore.
         */
        data class Presentation(val value: DeclaredPresentation) : Result
    }

    /**
     * Reads [bytes] as a Tiny Container descriptor.
     *
     * Never throws: every failure mode collapses into [Result.Absent] (see the
     * fail-open note in the class kdoc).
     */
    fun read(bytes: ByteArray): Result {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return Result.Absent

        val root = try {
            Yaml().load<Any?>(bytes.inputStream().buffered())
        } catch (e: Exception) {
            return Result.Absent
        }
        if (root !is Map<*, *>) return Result.Absent

        val name = scalarText(root[KEY_NAME], MAX_NAME_CHARS)
        val description = scalarText(root[KEY_DESCRIPTION], MAX_DESCRIPTION_CHARS)
        // §1.121: `.tiny.yaml` writes a container-absolute `/opt/tiny/preview.png`,
        // which is the guest's view of the rootfs root — normalize() strips the
        // leading slash and rejects anything that could escape. A rejected path
        // is simply no preview (fail-open), never an import failure: the letter
        // avatar is a complete rendering and this file's author never targeted
        // Linexa's rules.
        val preview = (root[KEY_PREVIEW] as? String)?.let { RootFsPreviewImage.normalize(it) }

        if (name == null && description == null && preview == null) {
            return Result.Absent
        }

        return Result.Presentation(
            value = DeclaredPresentation(
                displayName = name,
                description = description,
                previewImagePath = preview
            )
        )
    }

    /**
     * A displayable scalar: text only, trimmed, length-capped, control
     * characters (other than the newlines a `description: |` block legitimately
     * contains) stripped. Lists and mappings are rejected rather than
     * flattened — a `name:` that is a list is a file Linexa does not
     * understand, and inventing a rendering for it would be a guess.
     */
    private fun scalarText(value: Any?, maxChars: Int): String? {
        val text = when (value) {
            null -> return null
            is String -> value
            is Int, is Long, is Double, is Float, is Boolean -> value.toString()
            else -> return null
        }
        val cleaned = text
            .filter { it == '\n' || it == '\t' || !it.isISOControl() }
            .trim()
        if (cleaned.isEmpty()) return null
        return if (cleaned.length > maxChars) cleaned.take(maxChars).trimEnd() else cleaned
    }
}
