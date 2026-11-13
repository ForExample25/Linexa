package com.fem.linexa.service.rootfs

import com.fem.linexa.core.logging.Logger
import com.fem.linexa.service.runtime.WorkspaceFileManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §1.118 — read access to an *installed* workspace's declared RootFS
 * metadata, for the runtime consumers that would otherwise probe the guest.
 * §1.123 — extended to the workspace's full *declaration*, in either format,
 * for the UI consumers that want the image's own name/description/preview and
 * shortcuts.
 *
 * The declaration is not stored separately. Two files are read, in the priority
 * order §1.119 established:
 *
 *  - `{workspace}/config.yaml` — the archive's native root file, persisted
 *    verbatim by the installer (§1.26). Carries the [RootFsMetadata] half.
 *  - `{workspace}/rootfs/.tiny.yaml` — a legacy Tiny Container descriptor,
 *    which is *not* persisted as `config.yaml` on purpose (§1.119: writing a
 *    foreign document into Linexa's own schema path would make every later read
 *    log a warning about a file the app itself planted). It is read where it
 *    genuinely lives — inside the extracted rootfs, since extraction is a plain
 *    `tar -xf` with no `--strip-components`, so a root-level archive entry lands
 *    at the rootfs root.
 *
 * **Fail-open, exactly like [com.fem.linexa.service.workspaceconfig.WorkspaceConfigParser.load]
 * (§1.27).** The file was validated hard at import time, so a file that no
 * longer parses at launch can only mean later tampering or manual editing.
 * Every consumer of this store has a working fallback — probe the guest,
 * probe the rootfs, use a default, or render the letter avatar — so refusing to
 * launch a workspace over an unreadable *optional* declaration would be
 * strictly worse than ignoring it with a log line. `null` therefore means "no
 * usable declaration", never "error".
 *
 * Results are memoized per workspace because the consumers are on hot paths
 * (every launch, every Package Manager open, every desktop start, every Home
 * list emission) and the files only change when an import writes them. The memo
 * is keyed on **both** files' identity (existence + size + mtime) rather than on
 * the workspace id alone, so a reinstall over an existing workspace is picked up
 * automatically. That is deliberate: an id-only memo would be correct only as
 * long as every present *and future* write site remembered to call [invalidate],
 * and a stale-metadata bug would surface as a workspace launching with the
 * previous image's declared shell — silent, and hard to attribute. Two `stat`s
 * per read are cheap next to the YAML parse they avoid.
 *
 * The preview image is deliberately **not** memoized ([previewImage]): its path
 * is resolved against the live filesystem on every call, because the file can be
 * replaced with a symlink out of the rootfs at any time after import. See
 * [RootFsPreviewImage].
 */
@Singleton
class WorkspaceRootFsMetadata @Inject constructor(
    private val fileManager: WorkspaceFileManager,
    private val inspector: RootFsInspector
) {

    private val cache = ConcurrentHashMap<String, Entry>()

    /**
     * The workspace's declared metadata, or null when it declared none (or
     * when the file is absent/unreadable/invalid — see the class kdoc on why
     * those collapse into the same answer).
     *
     * Native format only, by definition: a legacy `.tiny.yaml` yields no
     * [RootFsMetadata] at all (§1.119), so every fact-consuming caller here
     * keeps probing for a legacy image exactly as it did before §1.119.
     */
    fun of(workspaceId: String): RootFsMetadata? = descriptorOf(workspaceId).metadata

    /**
     * §1.123 — the workspace's full declaration, native or legacy.
     *
     * [RootFsDescriptor.UNDECLARED] when it declared nothing usable. Consumers
     * that want display text read [RootFsDescriptor.presentation]; consumers
     * that want shortcuts read [RootFsDescriptor.declaredShortcuts]; consumers
     * that need *facts* read [RootFsDescriptor.metadata] and correctly get null
     * for a legacy image.
     */
    fun descriptorOf(workspaceId: String): RootFsDescriptor {
        val native = configFile(workspaceId)
        val legacy = legacyFile(workspaceId)
        val stamp = Stamp.of(native, legacy)
        val cached = cache[workspaceId]
        if (cached != null && cached.stamp == stamp) return cached.value
        val value = read(native, legacy)
        cache[workspaceId] = Entry(stamp, value)
        return value
    }

    /**
     * §1.123 — the workspace's declared preview image as a readable file inside
     * its rootfs, or null when it declared none or the declaration cannot be
     * trusted right now.
     *
     * Resolved on every call rather than memoized with the declaration: the
     * path is stable but the *file* is inside a guest-writable rootfs, so
     * containment has to be re-verified against the live filesystem (a symlink
     * escape can be planted long after import). [RootFsPreviewImage] carries the
     * rule; the cost is one canonicalization.
     */
    fun previewImage(workspaceId: String): File? {
        val declared = descriptorOf(workspaceId).presentation?.previewImagePath ?: return null
        return RootFsPreviewImage.resolveInside(fileManager.getRootfsPath(workspaceId), declared)
    }

    /**
     * Drops the memoized declaration. Not required for correctness (see the
     * class kdoc — the memo revalidates itself against the files), but callers
     * that have just replaced a file can use it to skip the stale read.
     */
    fun invalidate(workspaceId: String) {
        cache.remove(workspaceId)
    }

    private fun configFile(workspaceId: String): File =
        File(fileManager.getWorkspaceBasePath(workspaceId), CONFIG_FILE_NAME)

    /**
     * The legacy descriptor's real location: the rootfs root, not the workspace
     * root. §1.119 keeps foreign bytes out of `{workspace}/config.yaml`, and
     * extraction preserves root-level archive entries as-is, so this is where a
     * Tiny Container image's own `.tiny.yaml` already is.
     */
    private fun legacyFile(workspaceId: String): File =
        File(fileManager.getRootfsPath(workspaceId), RootFsMetadataFile.LEGACY_TINY.fileName)

    /**
     * Native first, legacy second — the same priority as the import-time scan
     * ([RootFsMetadataFile]), so an image carrying both resolves identically
     * before and after installation. Reading them in the other order would let
     * a workspace show one name at the confirmation screen and another on the
     * Home card.
     */
    private fun read(native: File, legacy: File): RootFsDescriptor {
        readNative(native)?.let { return it }
        readLegacy(legacy)?.let { return it }
        return RootFsDescriptor.UNDECLARED
    }

    /**
     * Returns null when there is nothing usable in the native file, so the
     * caller can fall through to the legacy one. A native file that exists but
     * declares no metadata block still returns null here: that is not an error,
     * it just means this workspace's *config* had no declaration, and a legacy
     * sibling may still describe the image.
     */
    private fun readNative(file: File): RootFsDescriptor? {
        if (!file.isFile) return null
        return try {
            when (val result = inspector.inspect(file.readBytes())) {
                is RootFsInspector.Result.Success ->
                    result.descriptor.takeIf { it.metadata != null }

                is RootFsInspector.Result.Invalid -> {
                    Logger.w(TAG, "Ignoring unreadable RootFS metadata in ${file.path}: ${result.reason}")
                    null
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Could not read RootFS metadata from ${file.path}: ${e.message}")
            null
        }
    }

    private fun readLegacy(file: File): RootFsDescriptor? {
        if (!file.isFile) return null
        if (file.length() > TinyMetadataAdapter.MAX_BYTES) {
            Logger.w(TAG, "Ignoring oversized legacy descriptor ${file.path} (${file.length()} bytes)")
            return null
        }
        return try {
            // inspectLegacyTiny is fail-open by contract and never Invalid, so
            // an unusable file lands on UNDECLARED — which reads as "nothing
            // declared" here, exactly like an absent file.
            when (val result = inspector.inspectLegacyTiny(file.readBytes())) {
                is RootFsInspector.Result.Success -> result.descriptor.takeIf { it.isLegacy }
                is RootFsInspector.Result.Invalid -> null
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Could not read legacy descriptor ${file.path}: ${e.message}")
            null
        }
    }

    /**
     * The observable identity of both declaration files. A memo is reused only
     * while this is unchanged, so a rewritten config.yaml — or a `.tiny.yaml`
     * edited inside the guest — is re-read. `exists=false` is a distinct stamp,
     * which is what lets a workspace that gains a declaration after its first
     * (absent) read pick it up.
     */
    private data class Stamp(
        val nativeExists: Boolean,
        val nativeLength: Long,
        val nativeModified: Long,
        val legacyExists: Boolean,
        val legacyLength: Long,
        val legacyModified: Long
    ) {
        companion object {
            fun of(native: File, legacy: File): Stamp = Stamp(
                nativeExists = native.isFile,
                nativeLength = if (native.isFile) native.length() else 0L,
                nativeModified = if (native.isFile) native.lastModified() else 0L,
                legacyExists = legacy.isFile,
                legacyLength = if (legacy.isFile) legacy.length() else 0L,
                legacyModified = if (legacy.isFile) legacy.lastModified() else 0L
            )
        }
    }

    /** Lets the cache memoize a legitimate "declared nothing" answer. */
    private data class Entry(val stamp: Stamp, val value: RootFsDescriptor)

    private companion object {
        const val TAG = "WorkspaceRootFsMetadata"
        const val CONFIG_FILE_NAME = "config.yaml"
    }
}
