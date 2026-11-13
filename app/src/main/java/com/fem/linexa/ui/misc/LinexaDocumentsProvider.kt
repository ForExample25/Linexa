package com.fem.linexa.ui.misc

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.fem.linexa.core.filesystem.deleteRecursivelyAllowingReadOnly
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.runtime.RuntimeState
import com.fem.linexa.domain.workspace.WorkspaceLaunchValidator
import com.fem.linexa.domain.workspace.WorkspaceMetadata
import com.fem.linexa.domain.workspace.WorkspaceReadiness
import com.fem.linexa.domain.workspace.WorkspaceRepository
import com.fem.linexa.service.runtime.SessionManager
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.ui.misc.LinexaDocumentsMapping.Decoded
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** openDocument read mode — SDK 34 exposes no public constant for it. */
private const val MODE_READ_ONLY = "r"
/** Write modes SAF clients actually pass (issue #8 copy/move-into support). */
private val MODES_WRITE = setOf("w", "wt", "wa", "rw", "rwt")

/**
 * SAF [DocumentsProvider] exposing Linexa's on-disk state to the system
 * file manager (§1.74, reworked for issue #8, 2026-09-03).
 *
 * ## What changed for issue #8, and why
 *
 * The provider used to be strictly read-only, which is exactly why the
 * Android Files experience broke around it:
 *
 *  - **The app entry had no icon and an unclear name.** The root was titled
 *    "Containers" (indistinguishable from TinyContainer's own entry in the
 *    Files app — the reported side-by-side screenshot) and advertised no
 *    `Root.COLUMN_ICON`, so file managers that resolve the icon from the
 *    roots cursor drew nothing. The root is now titled **"Linexa"** and
 *    carries [Root.COLUMN_ICON] = the app's launcher icon — the standard
 *    mechanism; managers that instead use the package icon resolve the same
 *    graphic either way.
 *  - **The entry "disappeared" in destination-picking mode.** Clients that
 *    move/copy files re-query roots and documents and then offer a
 *    destination only when the target advertises create support — a
 *    provider whose rows all carried FLAGS = 0 and implemented no
 *    createDocument cannot be chosen, and some managers drop such roots
 *    from the move/copy flow entirely. Directories now advertise
 *    [Document.FLAG_DIR_SUPPORTS_CREATE], files advertise
 *    write/delete/rename, roots advertise [Root.FLAG_LOCAL] +
 *    [Root.FLAG_SUPPORTS_IS_CHILD] (isChildDocument has always been
 *    implemented), and the mutations are implemented for real:
 *    [createDocument], [deleteDocument], [renameDocument] and writable
 *    [openDocument] modes. Copying/moving a file INTO the Linexa project
 *    location now works through the standard SAF path — and the entry
 *    stays visible while doing it, because the provider is no longer
 *    filtered out as non-writable.
 *  - **Resilience:** a workspace listing hiccup (DB cold read on a binder
 *    thread) used to be able to fail a whole query; every DB read is now
 *    fail-open so the roots never blink out of the UI over a transient.
 *
 * ## Security posture (unchanged where it matters)
 *
 * Document IDs are validated by [LinexaDocumentsMapping] (no "..", no
 * absolute, no empty segments) and every resolved [File] is re-checked
 * canonically against its root — the same containment rule the read-only
 * provider enforced, now applied to writes too. create/rename validate the
 * client-supplied display names against the same segment rules BEFORE any
 * filesystem call, so a SAF client cannot escape a root with a crafted
 * name, and a create can never follow a symlink out of the workspace.
 *
 * **Live-workspace rule (2026-11-13, OPT-SEC-001):** every mutation goes
 * through [requireMutable]/[WorkspaceMutationPolicy]. A workspace whose
 * runtime is `Starting`, `Running` or `Stopping` refuses create, delete,
 * rename and writable open calls under its rootfs; reads stay available.
 * The rootfs root itself is never mutable through SAF, active or not —
 * deleting it here would orphan the database row and turn a healthy
 * workspace into `Broken`; the app's Delete action is the coordinated path.
 * The `Public` root remains fully writable because it is the shared mount,
 * not the rootfs. Liveness failures fail closed.
 *
 * Two roots:
 *  - "Linexa": one entry per installed workspace whose DB status is Ready
 *    AND whose rootfs passes [WorkspaceLaunchValidator]'s disk check —
 *    exactly the workspaces RuntimeService would launch. Each workspace
 *    maps to {app-files}/workspaces/{id}/rootfs and nothing else: the
 *    parent directory also holds `.staging-rootfs`, `.import-cache` and
 *    `config.yaml`, which are installer-internal and never exposed.
 *  - "Public": {externalFilesDir}/shared — the exact directory PRoot binds
 *    into every guest at /mnt/shared ([WorkspaceFileManager] layout), so
 *    what you see here is byte-for-byte what the guest's /mnt/shared
 *    contains.
 */
class LinexaDocumentsProvider : DocumentsProvider() {

    private val tag = "LinexaDocumentsProvider"

    private val rootColumns = arrayOf(
        Root.COLUMN_ROOT_ID,
        Root.COLUMN_DOCUMENT_ID,
        Root.COLUMN_TITLE,
        Root.COLUMN_SUMMARY,
        Root.COLUMN_ICON,
        Root.COLUMN_FLAGS,
        Root.COLUMN_AVAILABLE_BYTES
    )

    private val documentColumns = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_FLAGS,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED
    )

    private var appContext: Context? = null

    @Volatile
    private var entryPoint: LinexaDocsEntryPoint? = null

    override fun onCreate(): Boolean {
        appContext = context?.applicationContext
        return true
    }

    // ── Roots ──────────────────────────────────────────────────────────

    override fun queryRoots(projection: Array<out String>?): Cursor {
        return cursor(projection, rootColumns) { c ->
            addRootRow(
                c,
                LinexaDocumentsMapping.ROOT_CONTAINERS,
                ROOT_TITLE_LINEXA,
                "Workspace root filesystems",
                bytesAvailable(workspacesBaseDir())
            )
            addRootRow(
                c,
                LinexaDocumentsMapping.ROOT_PUBLIC,
                "Public",
                "Host folder bound to /mnt/shared inside every workspace",
                bytesAvailable(publicSharedDir())
            )
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val decoded = decodeOrThrow(parentDocumentId)
        return cursor(projection, documentColumns) { c ->
            when (decoded) {
                is Decoded.ContainersRoot -> {
                    val browsable = listBrowsableWorkspaces()
                    val names = LinexaDocumentsMapping.assignDisplayNames(browsable.map { it.id to it.displayName })
                    browsable.forEach { meta ->
                        val rootfs = resolveRootfs(meta.id)
                        addDirRow(c, LinexaDocumentsMapping.workspaceDocumentId(meta.id), names[meta.id] ?: meta.id, rootfs)
                    }
                }
                is Decoded.Workspace -> addChildRows(c, resolveRootfs(decoded.workspaceId), parentDocumentId)
                is Decoded.ContainersFile -> addChildRows(
                    c,
                    resolveFile(parentDocumentId).also { checkIsDirectory(it, parentDocumentId) },
                    parentDocumentId
                )
                is Decoded.PublicRoot -> addChildRows(c, publicSharedDir(), parentDocumentId)
                is Decoded.PublicFile -> addChildRows(
                    c,
                    resolveFile(parentDocumentId).also { checkIsDirectory(it, parentDocumentId) },
                    parentDocumentId
                )
            }
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val decoded = decodeOrThrow(documentId)
        return cursor(projection, documentColumns) { c ->
            when (decoded) {
                is Decoded.ContainersRoot -> addDirRow(c, documentId, ROOT_TITLE_LINEXA, workspacesBaseDir())
                is Decoded.PublicRoot -> addDirRow(c, documentId, "Public", publicSharedDir())
                is Decoded.Workspace -> {
                    val names = LinexaDocumentsMapping.assignDisplayNames(
                        listBrowsableWorkspaces().map { it.id to it.displayName }
                    )
                    addDirRow(c, documentId, names[decoded.workspaceId] ?: decoded.workspaceId, resolveRootfs(decoded.workspaceId))
                }
                is Decoded.ContainersFile -> addFileRow(c, documentId, resolveFile(documentId))
                is Decoded.PublicFile -> addFileRow(c, documentId, resolveFile(documentId))
            }
        }
    }

    override fun getDocumentType(documentId: String): String {
        val decoded = decodeOrThrow(documentId)
        return when (decoded) {
            is Decoded.ContainersRoot, is Decoded.PublicRoot, is Decoded.Workspace -> Document.MIME_TYPE_DIR
            is Decoded.ContainersFile -> {
                val file = resolveFile(documentId)
                if (file.isDirectory) Document.MIME_TYPE_DIR else mimeTypeOf(file)
            }
            is Decoded.PublicFile -> {
                val file = resolveFile(documentId)
                if (file.isDirectory) Document.MIME_TYPE_DIR else mimeTypeOf(file)
            }
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val isWrite = mode in MODES_WRITE
        if (mode != MODE_READ_ONLY && !isWrite) {
            throw FileNotFoundException("Unsupported open mode '$mode' for $documentId")
        }
        if (isWrite) requireMutable(documentId, WorkspaceMutationPolicy.Mutation.WRITE)
        val file = resolveFile(documentId)
        if (file.isDirectory) throw FileNotFoundException("$documentId is a directory")
        // Pre-release audit fix (2026-09-02, security S9): [resolveFile]
        // validates containment on the CANONICAL path; opening the canonical
        // file keeps that TOCTOU window closed for reads and writes alike.
        val pfdMode = when {
            !isWrite -> ParcelFileDescriptor.MODE_READ_ONLY
            mode == "wa" ->
                ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_APPEND
            mode == "rw" ->
                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
            else -> // "w", "wt", "rwt"
                ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE
        }
        return try {
            val opened = ParcelFileDescriptor.open(file.canonicalFile, pfdMode)
            if (isWrite) notifyChanged(documentId)
            opened
        } catch (e: IOException) {
            throw FileNotFoundException("Cannot open $documentId: ${e.message}")
        }
    }

    // ── Issue #8: the write surface (create / delete / rename) ─────────

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        requireMutable(parentDocumentId, WorkspaceMutationPolicy.Mutation.CREATE)
        val parent = resolveFile(parentDocumentId).also { checkIsDirectory(it, parentDocumentId) }
        val name = validateDisplayName(displayName)
        val target = File(parent, name)
        // Canonical containment BEFORE creating: a crafted name could not
        // escape (no separators survive validateDisplayName), but the check
        // also refuses to create through a symlinked parent's escape.
        checkContained(target, rootOf(parentDocumentId), parentDocumentId)
        if (target.exists()) {
            throw FileNotFoundException("$name already exists in $parentDocumentId")
        }
        val created = if (mimeType == Document.MIME_TYPE_DIR) {
            target.mkdir()
        } else {
            try {
                target.createNewFile()
            } catch (e: IOException) {
                throw FileNotFoundException("Cannot create $name: ${e.message}")
            }
        }
        if (!created) throw FileNotFoundException("Cannot create $name in $parentDocumentId")
        val documentId = LinexaDocumentsMapping.childDocumentId(parentDocumentId, name)
        notifyChanged(documentId)
        notifyChanged(parentDocumentId)
        return documentId
    }

    override fun deleteDocument(documentId: String) {
        requireMutable(documentId, WorkspaceMutationPolicy.Mutation.DELETE)
        val file = resolveFile(documentId)
        val parentId = parentDocumentIdOf(documentId)
        val deleted = if (file.isDirectory) {
            file.deleteRecursivelyAllowingReadOnly()
        } else {
            file.delete()
        }
        if (!deleted) {
            // FilesContract: clients handle a delete failure via the thrown
            // exception; a partial recursive delete surfaces honestly.
            throw FileNotFoundException("Could not delete $documentId")
        }
        notifyChanged(documentId)
        parentId?.let { notifyChanged(it) }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        requireMutable(documentId, WorkspaceMutationPolicy.Mutation.RENAME)
        val file = resolveFile(documentId)
        val name = validateDisplayName(displayName)
        val parent = file.parentFile
            ?: throw FileNotFoundException("$documentId has no parent directory")
        val target = File(parent, name)
        checkContained(target, rootOf(documentId), documentId)
        if (target.exists()) {
            throw FileNotFoundException("$name already exists")
        }
        if (!file.renameTo(target)) {
            throw FileNotFoundException("Could not rename $documentId to $name")
        }
        val renamedId = rebaseDocumentId(documentId, file.name, name)
        notifyChanged(documentId)
        notifyChanged(renamedId)
        parentDocumentIdOf(renamedId)?.let { notifyChanged(it) }
        return renamedId
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = LinexaDocumentsMapping.decode(parentDocumentId) ?: return false
        val child = LinexaDocumentsMapping.decode(documentId) ?: return false
        return when (parent) {
            is Decoded.ContainersRoot ->
                child is Decoded.Workspace || child is Decoded.ContainersFile
            is Decoded.Workspace ->
                child is Decoded.ContainersFile && child.workspaceId == parent.workspaceId
            is Decoded.ContainersFile ->
                child is Decoded.ContainersFile && child.workspaceId == parent.workspaceId &&
                    child.relativeSegments.size > parent.relativeSegments.size &&
                    child.relativeSegments.take(parent.relativeSegments.size) == parent.relativeSegments
            is Decoded.PublicRoot ->
                child is Decoded.PublicFile
            is Decoded.PublicFile ->
                child is Decoded.PublicFile &&
                    child.relativeSegments.size > parent.relativeSegments.size &&
                    child.relativeSegments.take(parent.relativeSegments.size) == parent.relativeSegments
        }
    }

    // ── Resolution ─────────────────────────────────────────────────────

    private fun decodeOrThrow(documentId: String): Decoded =
        LinexaDocumentsMapping.decode(documentId)
            ?: throw FileNotFoundException("Unknown document ID: $documentId")

    /**
     * Resolves a file (or directory) document ID to a [File], enforcing
     * containment: the decoded segments are validated by the mapping layer
     * and the result is re-checked canonically so a symlink inside the
     * rootfs can never carry access outside its root.
     */
    private fun resolveFile(documentId: String): File {
        val decoded = decodeOrThrow(documentId)
        val (root, segments) = when (decoded) {
            is Decoded.ContainersFile -> resolveRootfs(decoded.workspaceId) to decoded.relativeSegments
            is Decoded.PublicFile -> publicSharedDir() to decoded.relativeSegments
            else -> throw FileNotFoundException("$documentId is not a file or directory under a root")
        }
        val candidate = segments.fold(root) { acc, segment -> File(acc, segment) }
        checkContained(candidate, root, documentId)
        if (!candidate.exists()) {
            throw FileNotFoundException("$documentId does not exist")
        }
        return candidate
    }

    /** Canonical containment check — the one rule every access goes through. */
    private fun checkContained(candidate: File, root: File, documentId: String) {
        val rootCanonical = canonicalOrThrow(root)
        val candidateCanonical = try {
            candidate.canonicalFile
        } catch (e: IOException) {
            throw FileNotFoundException("Cannot resolve $documentId: ${e.message}")
        }
        if (!candidateCanonical.path.startsWith(rootCanonical.path + File.separator) &&
            candidateCanonical.path != rootCanonical.path
        ) {
            throw FileNotFoundException("$documentId escapes its root")
        }
    }

    /** The containment root for a document id (its workspace rootfs or the shared dir). */
    private fun rootOf(documentId: String): File = when (val decoded = decodeOrThrow(documentId)) {
        is Decoded.ContainersFile -> resolveRootfs(decoded.workspaceId)
        is Decoded.Workspace -> resolveRootfs(decoded.workspaceId)
        is Decoded.PublicFile, is Decoded.PublicRoot -> publicSharedDir()
        is Decoded.ContainersRoot -> workspacesBaseDir()
    }

    /**
     * Rewrites the LAST segment of [documentId] after a successful rename —
     * the id shape stays "{parent}/{newName}" with the mapping's structural
     * colon rules for direct root children.
     */
    private fun rebaseDocumentId(documentId: String, oldName: String, newName: String): String {
        val parentId = documentId.removeSuffix(oldName).trimEnd('/')
        return LinexaDocumentsMapping.childDocumentId(parentId, newName)
    }

    /**
     * Enforces [WorkspaceMutationPolicy] before any destructive or writable
     * filesystem call. One helper so the policy cannot drift between methods.
     */
    private fun requireMutable(documentId: String, mutation: WorkspaceMutationPolicy.Mutation) {
        val decision = WorkspaceMutationPolicy.check(documentId, mutation, ::isWorkspaceActive)
        if (decision is WorkspaceMutationPolicy.Decision.Refused) {
            throw FileNotFoundException(decision.reason)
        }
    }

    /**
     * The authoritative liveness query. [SessionManager] is a singleton and
     * `getState` is a map read, so this is safe on a binder thread.
     *
     * Fail-closed: if the dependency graph or the state read throws, a
     * mutation is refused. A stale `Running` merely makes the user stop the
     * workspace; a stale `Stopped` would delete a live rootfs.
     */
    private fun isWorkspaceActive(workspaceId: String): Boolean = try {
        when (dependencies().sessionManager().getState(workspaceId)) {
            is RuntimeState.Running, is RuntimeState.Starting, is RuntimeState.Stopping -> true
            else -> false
        }
    } catch (e: Exception) {
        Logger.w(tag, "liveness check failed for $workspaceId — refusing mutation: ${e.message}")
        true
    }

    /** Parent document id for observer notification; null for a virtual root. */
    private fun parentDocumentIdOf(documentId: String): String? {
        val slash = documentId.lastIndexOf('/')
        if (slash > 0) return documentId.substring(0, slash)
        return when {
            documentId.startsWith("${LinexaDocumentsMapping.ROOT_CONTAINERS}:") ->
                LinexaDocumentsMapping.ROOT_CONTAINERS
            documentId.startsWith("${LinexaDocumentsMapping.ROOT_PUBLIC}:") ->
                LinexaDocumentsMapping.ROOT_PUBLIC
            else -> null
        }
    }

    /**
     * Best-effort `ContentResolver` notification. A failure here must never
     * fail the filesystem operation that already succeeded.
     */
    private fun notifyChanged(documentId: String) {
        val context = appContext ?: return
        try {
            context.contentResolver.notifyChange(
                DocumentsContract.buildDocumentUri(
                    "${context.packageName}.docs",
                    documentId
                ),
                null
            )
        } catch (e: Exception) {
            Logger.w(tag, "notifyChange($documentId) failed: ${e.message}")
        }
    }

    /**
     * Client-supplied display names must be single, safe path segments —
     * no separators, no dot segments, no control characters. This is the
     * create/rename half of [LinexaDocumentsMapping]'s decode rules.
     */
    private fun validateDisplayName(raw: String): String {
        val name = raw.trim()
        if (name.isEmpty() || name == "." || name == ".." ||
            name.contains('/') || name.contains('\\') || name.any { it.isISOControl() }
        ) {
            throw FileNotFoundException("Invalid name: $raw")
        }
        return name
    }

    private fun resolveRootfs(workspaceId: String): File {
        if (workspaceId.isBlank()) throw FileNotFoundException("Empty workspace id")
        val fileManager = dependencies().workspaceFileManager()
        val rootfs = fileManager.getRootfsPath(workspaceId)
        val baseCanonical = canonicalOrThrow(fileManager.getWorkspaceBasePath(workspaceId))
        val rootfsCanonical = canonicalOrThrow(rootfs)
        if (!rootfsCanonical.path.startsWith(baseCanonical.path + File.separator)) {
            throw FileNotFoundException("Workspace $workspaceId rootfs escapes its workspace directory")
        }
        if (!rootfs.isDirectory) {
            throw FileNotFoundException("Workspace $workspaceId rootfs is not present on disk")
        }
        return rootfs
    }

    private fun canonicalOrThrow(file: File): File = try {
        file.canonicalFile
    } catch (e: IOException) {
        throw FileNotFoundException("Cannot resolve path for ${file.path}: ${e.message}")
    }

    private fun checkIsDirectory(file: File, documentId: String) {
        if (!file.isDirectory) throw FileNotFoundException("$documentId is not a directory")
    }

    /** {externalFilesDir}/shared — same location PRootLauncher binds as /mnt/shared. */
    private fun publicSharedDir(): File =
        File(requireAppContext().getExternalFilesDir(null), "shared")

    /** {filesDir}/workspaces — the parent of every workspace's directory. */
    private fun workspacesBaseDir(): File =
        dependencies().workspaceFileManager().getWorkspaceBasePath("")

    private fun requireAppContext(): Context =
        appContext ?: throw IllegalStateException("LinexaDocumentsProvider.onCreate never ran")

    // ── Workspace listing (DB + disk truth, like RuntimeService) ────────

    /**
     * Fail-open on purpose (issue #8): the Files app caches roots; a
     * transient failure here used to be able to blank the workspace list
     * (and, in destination-picking mode, the whole entry). A hiccup now
     * yields an empty-but-valid listing instead of a broken query.
     */
    private fun listBrowsableWorkspaces(): List<WorkspaceMetadata> {
        return try {
            val deps = dependencies()
            val all = runBlocking { deps.workspaceRepository().observeAll().first() }
            val validator = deps.workspaceLaunchValidator()
            all.filter { meta ->
                LinexaDocumentsMapping.isBrowsable(meta.status, validator.evaluate(meta) is WorkspaceReadiness.Ready)
            }
        } catch (e: Exception) {
            Logger.w(tag, "workspace listing failed — returning empty: ${e.message}")
            emptyList()
        }
    }

    // ── Cursor helpers ─────────────────────────────────────────────────

    private fun cursor(
        projection: Array<out String>?,
        columns: Array<String>,
        fill: (MatrixCursor) -> Unit
    ): Cursor {
        val filtered = projection?.filter { it in columns }?.toTypedArray() ?: columns
        return MatrixCursor(filtered).also(fill)
    }

    private fun addRootRow(c: MatrixCursor, rootId: String, title: String, summary: String, availableBytes: Long) {
        c.newRow()
            .add(Root.COLUMN_ROOT_ID, rootId)
            .add(Root.COLUMN_DOCUMENT_ID, LinexaDocumentsMapping.rootDocumentId(rootId))
            .add(Root.COLUMN_TITLE, title)
            .add(Root.COLUMN_SUMMARY, summary)
            // Issue #8: the app's own launcher icon — the standard root-icon
            // channel (managers load it from the provider's package
            // resources). Managers that instead fall back to the package
            // icon resolve the same graphic.
            // Issue #3 (2026-09-04) BUGFIX: the roots used to advertise ONLY
            // FLAG_SUPPORTS_IS_CHILD, and that is exactly why the "Linexa"
            // root vanished from file-transfer pickers while it browsed fine
            // in the Files app: DocumentsUI filters destination lists for
            // create/move/copy/save actions on Root.FLAG_SUPPORTS_CREATE.
            // (FLAG_LOCAL would also help, but it is an @hide constant — the
            // platform stubs expose no such field, which the compiler just
            // proved — so CREATE is the advertised capability here.)
            .add(Root.COLUMN_ICON, requireAppContext().applicationInfo.icon)
            .add(
                Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_IS_CHILD or
                    Root.FLAG_SUPPORTS_CREATE
            )
            .add(Root.COLUMN_AVAILABLE_BYTES, availableBytes)
    }

    private fun dirFlags(): Int =
        Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE or
            Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_WRITE

    private fun fileFlags(): Int =
        Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or
            Document.FLAG_SUPPORTS_RENAME

    private fun addDirRow(c: MatrixCursor, documentId: String, displayName: String, dir: File) {
        c.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, documentId)
            .add(Document.COLUMN_DISPLAY_NAME, displayName)
            .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            .add(Document.COLUMN_FLAGS, dirFlags())
            .add(Document.COLUMN_SIZE, 0L)
            .add(Document.COLUMN_LAST_MODIFIED, dir.lastModified())
    }

    private fun addFileRow(c: MatrixCursor, documentId: String, file: File) {
        c.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, documentId)
            .add(Document.COLUMN_DISPLAY_NAME, file.name)
            .add(Document.COLUMN_MIME_TYPE, mimeTypeOf(file))
            .add(Document.COLUMN_FLAGS, fileFlags())
            .add(Document.COLUMN_SIZE, if (file.isDirectory) 0L else file.length())
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
    }

    private fun addChildRows(c: MatrixCursor, dir: File, parentDocumentId: String) {
        val children = dir.listFiles() ?: return
        children.sortedWith(compareBy({ !it.isDirectory }, { it.name })).forEach { child ->
            val childId = LinexaDocumentsMapping.childDocumentId(parentDocumentId, child.name)
            if (child.isDirectory) {
                addDirRow(c, childId, child.name, child)
            } else {
                addFileRow(c, childId, child)
            }
        }
    }

    private fun mimeTypeOf(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val extension = file.extension.lowercase()
        if (extension.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    private fun bytesAvailable(dir: File): Long = try {
        StatFs(dir.parentFile?.path ?: dir.path).availableBytes
    } catch (e: Exception) {
        Logger.w(tag, "availableBytes: ${e.message}")
        0L
    }

    companion object {
        /**
         * Issue #8: the project entry's name in the Android Files app — the
         * app's own name, per the requirement ("Linexa or Linexa Project —
         * choose the name that best matches the rest of the application's
         * UI"). Also disambiguates from any other provider's "Containers".
         */
        const val ROOT_TITLE_LINEXA = "Linexa"
    }

    // ── Hilt ───────────────────────────────────────────────────────────

    private fun dependencies(): LinexaDocsEntryPoint {
        entryPoint?.let { return it }
        val context = appContext ?: throw IllegalStateException("LinexaDocumentsProvider.onCreate never ran")
        val resolved = EntryPointAccessors.fromApplication(context, LinexaDocsEntryPoint::class.java)
        entryPoint = resolved
        return resolved
    }
}

/**
 * Lazy Hilt access: ContentProvider.onCreate runs BEFORE Application.onCreate,
 * so the Hilt component does not exist yet when the provider starts. The
 * entry point is therefore resolved on first query (binder thread, always
 * well after startup) rather than in onCreate.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface LinexaDocsEntryPoint {
    fun workspaceRepository(): WorkspaceRepository
    fun workspaceLaunchValidator(): WorkspaceLaunchValidator
    fun workspaceFileManager(): WorkspaceFileManager
    fun sessionManager(): SessionManager
}
