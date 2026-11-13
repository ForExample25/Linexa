package com.fem.linexa.service.rootfs

import com.fem.linexa.core.logging.Logger
import com.fem.linexa.service.workspaceconfig.WorkspaceConfigParser
import org.yaml.snakeyaml.Yaml
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §1.118 — the entry point that turns the bytes of a root-level metadata file
 * into a [RootFsDescriptor]. §1.119 — for **both** supported formats.
 *
 * Native pipeline (`.linexa.yaml`/`linexa.yaml`/`config.yaml`): snakeyaml load
 * → [RootFsMetadataParser] (shape) → [RootFsMetadataValidator] (semantics) →
 * [RootFsDescriptor] (declaration + sniffed format + mismatch warnings).
 *
 * Legacy pipeline (`.tiny.yaml`): [TinyMetadataAdapter] → presentation-only
 * descriptor. The two pipelines converge on the same [RootFsDescriptor] type
 * and the same downstream install path — §1.119 adds no second installer,
 * no second config store and no second launch path. What it does *not* do is
 * pretend the formats are equivalent: a legacy file yields
 * [RootFsDescriptor.metadata] `== null`, so every stage that needs a fact
 * (identity, shell, desktop, package manager) keeps probing exactly as it did
 * before, and only the display name/description are adopted. See
 * [TinyMetadataAdapter]'s kdoc for why inferring the rest would be worse than
 * probing.
 *
 * Three properties matter here and are the reason this is a class rather than
 * inlined into the installer:
 *
 * 1. **The file is loaded exactly once for both schemas.** The installer
 *    already hands the same bytes to [WorkspaceConfigParser]; a second
 *    snakeyaml load of untrusted YAML for the metadata half would double the
 *    parse cost and, worse, allow the two halves to disagree about what the
 *    document even is. [inspect] therefore reports the config result too.
 *
 * 2. **Failure semantics differ by stage, and deliberately so.** A *malformed
 *    or invalid* native metadata block fails the import ([Result.Invalid]) —
 *    the §1.26 fail-hard stance, because a declaration that cannot be trusted
 *    is worse than none at all, and the author can fix it. An *absent* block is
 *    [Result.Success] with [RootFsDescriptor.UNDECLARED]: probe-based
 *    detection ([com.fem.linexa.service.installer.RootFsProbe]) remains fully
 *    supported and unchanged for every archive that predates this schema.
 *
 * 3. **§1.119: only the native format is fail-hard.** A broken `.tiny.yaml`
 *    degrades to `UNDECLARED` instead of failing, because Tiny Container
 *    images import successfully today and a foreign file Linexa never
 *    validated before must not start rejecting them.
 */
@Singleton
class RootFsInspector @Inject constructor(
    private val configParser: WorkspaceConfigParser
) {

    sealed interface Result {
        /**
         * The file was understood. [descriptor] is [RootFsDescriptor.UNDECLARED]
         * when it carried no metadata block, and [config] carries the
         * configuration half (empty when it declared no config keys).
         */
        data class Success(
            val descriptor: RootFsDescriptor,
            val config: com.fem.linexa.service.workspaceconfig.WorkspaceConfig
        ) : Result

        /** The file must be rejected; [reason] names the exact YAML path. */
        data class Invalid(val reason: String) : Result
    }

    /**
     * Inspects a root-level config/metadata file in Linexa's **native** format.
     *
     * @param bytes the file's raw bytes, already size-capped by the caller
     *   ([WorkspaceConfigParser.MAX_CONFIG_BYTES] — re-checked here so this is
     *   safe to call directly).
     * @param archiveFormat the compression container sniffed from the archive's
     *   magic bytes ([CompressedArchiveStream]), used only to detect a wrong
     *   `rootfs.compressed_format` declaration.
     * @param supportedAbis the device's ABIs, for the declared-architecture
     *   warning. Empty skips that check.
     * @param source §1.119: which native file these bytes came from, recorded
     *   on the descriptor for diagnostics. Defaults to the canonical
     *   `.linexa.yaml` so existing callers and tests are unaffected.
     */
    fun inspect(
        bytes: ByteArray,
        archiveFormat: RootFsArchiveFormat? = null,
        supportedAbis: List<String> = emptyList(),
        source: RootFsMetadataFile = RootFsMetadataFile.NATIVE_DOT
    ): Result {
        if (bytes.size > WorkspaceConfigParser.MAX_CONFIG_BYTES) {
            return Result.Invalid(
                "config file exceeds ${WorkspaceConfigParser.MAX_CONFIG_BYTES} bytes (${bytes.size})"
            )
        }

        // The configuration half validates the document's overall shape
        // (valid YAML, mapping top level, only `linexa:`) and its own keys.
        // Running it first means the metadata parser never has to re-report
        // those errors, and a config-only file needs no metadata work at all.
        val config = when (val configResult = configParser.parse(bytes)) {
            is WorkspaceConfigParser.Result.Invalid -> return Result.Invalid(configResult.reason)
            is WorkspaceConfigParser.Result.Valid -> configResult.config
        }

        val linexaBlock = try {
            loadLinexaBlock(bytes)
        } catch (e: Exception) {
            // Unreachable in practice: configParser.parse already accepted the
            // document, so it loads. Fail loudly rather than silently treating
            // a declaring archive as undeclared.
            Logger.e(TAG, "YAML re-load disagreed with the config parser", e)
            return Result.Invalid("not valid YAML: ${e.message}")
        }

        if (linexaBlock == null) {
            return Result.Success(RootFsDescriptor.UNDECLARED, config)
        }

        return when (val parsed = RootFsMetadataParser.parse(linexaBlock)) {
            is RootFsMetadataParser.Result.Absent ->
                Result.Success(RootFsDescriptor.UNDECLARED, config)

            is RootFsMetadataParser.Result.Malformed ->
                Result.Invalid("invalid RootFS metadata: ${parsed.reason}")

            is RootFsMetadataParser.Result.Parsed ->
                when (val validated = RootFsMetadataValidator.validate(parsed.draft)) {
                    is RootFsMetadataValidator.Result.Invalid ->
                        Result.Invalid("invalid RootFS metadata: ${validated.reason}")

                    is RootFsMetadataValidator.Result.Valid -> {
                        val descriptor = RootFsDescriptor.of(
                            metadata = validated.metadata,
                            archiveFormat = archiveFormat,
                            supportedAbis = supportedAbis,
                            source = source
                        )
                        descriptor.warnings.forEach { Logger.w(TAG, it) }
                        Result.Success(descriptor, config)
                    }
                }
        }
    }

    /**
     * §1.119 — inspects a legacy Tiny Container `.tiny.yaml`.
     *
     * Always [Result.Success]: see [TinyMetadataAdapter] on why this path is
     * fail-open where the native path is fail-hard. The returned
     * [com.fem.linexa.service.workspaceconfig.WorkspaceConfig] is always empty
     * — `.tiny.yaml` carries no Linexa configuration, and its own
     * `boot_command`/`options` are never translated into one.
     *
     * The bytes are deliberately **not** persisted as `{workspace}/config.yaml`
     * by the installer: that file is Linexa's own schema, re-read fail-open at
     * every launch ([WorkspaceRootFsMetadata], §1.27), and writing a foreign
     * document there would make every later read log a parse warning for a
     * file the app itself planted.
     */
    fun inspectLegacyTiny(
        bytes: ByteArray,
        archiveFormat: RootFsArchiveFormat? = null
    ): Result {
        val descriptor = when (val read = TinyMetadataAdapter.read(bytes)) {
            is TinyMetadataAdapter.Result.Absent -> {
                Logger.i(TAG, "§1.119 .tiny.yaml carried no usable presentation — treating as undeclared")
                RootFsDescriptor.UNDECLARED
            }

            is TinyMetadataAdapter.Result.Presentation -> {
                Logger.i(
                    TAG,
                    "§1.119 adopted legacy .tiny.yaml presentation " +
                        "(name='${read.value.displayName}', preview=${read.value.previewImagePath != null}); " +
                        "distro/arch/shell/desktop/package-manager stay probe-detected and the file's " +
                        "quick_commands have no reader anymore (built-ins come from the project catalog)"
                )
                RootFsDescriptor.legacy(read.value, archiveFormat)
            }
        }
        return Result.Success(descriptor, com.fem.linexa.service.workspaceconfig.WorkspaceConfig())
    }

    /**
     * §1.119 — dispatches to the right pipeline for [source].
     *
     * The single call site the installer needs, so the format decision lives
     * here (next to both pipelines) rather than in the tar walk.
     */
    fun inspectFile(
        source: RootFsMetadataFile,
        bytes: ByteArray,
        archiveFormat: RootFsArchiveFormat? = null,
        supportedAbis: List<String> = emptyList()
    ): Result = if (source.isNative) {
        inspect(bytes, archiveFormat, supportedAbis, source)
    } else {
        inspectLegacyTiny(bytes, archiveFormat)
    }

    /**
     * Returns the `linexa:` mapping, or null when the document is empty or
     * carries no such block. Anything structurally wrong above that point was
     * already rejected by [WorkspaceConfigParser.parse].
     */
    private fun loadLinexaBlock(bytes: ByteArray): Map<*, *>? {
        val root = Yaml().load<Any?>(bytes.inputStream().buffered()) ?: return null
        if (root !is Map<*, *>) return null
        return root[TOP_LEVEL_KEY] as? Map<*, *>
    }

    private companion object {
        const val TAG = "RootFsInspector"
        const val TOP_LEVEL_KEY = "linexa"
    }
}
