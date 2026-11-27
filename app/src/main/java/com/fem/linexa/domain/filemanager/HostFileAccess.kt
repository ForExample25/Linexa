package com.fem.linexa.domain.filemanager

import java.io.IOException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * F-003 — read-only, symlink-safe host access to a workspace rootfs.
 *
 * Every file-manager operation used to fork PRoot plus a guest command
 * (`ls`, `base64`, `test`, `du`), which costs hundreds of milliseconds on
 * device. The rootfs is an app-private directory, so listing, reading,
 * stat-ing and existence checks can be answered directly with NIO.
 *
 * ## Why a custom resolver instead of `Path.normalize()`
 *
 * Guest absolute symlinks (`/usr/lib/foo -> /opt/foo`) must be re-anchored
 * at the rootfs, not at the Android host root — exactly what PRoot does.
 * Relative symlinks and `..` are resolved at the *guest* level, one component
 * at a time, with `NOFOLLOW` checks on every intermediate component. A path
 * can therefore never leave the rootfs: absolute targets are remapped, `..`
 * is clamped at the guest root, and symlink expansion is bounded.
 *
 * Mutations (write/mkdir/touch/delete/rename/copy) deliberately stay on the
 * guest path: they must preserve guest ownership, modes and PRoot's bind
 * semantics.
 *
 * Bind-mounted prefixes (`/dev`, `/proc`, `/sys`, `/run`, `/mnt/shared` and
 * `launch.bindMounts`) are never served from the host: their host directory
 * is not what the guest sees. The caller falls back to exec for those.
 *
 * @param view the workspace rootfs and the guest prefixes to exclude.
 */
internal class HostFileAccess(private val view: RootfsView) {

    private val rootfs: Path? = runCatching { view.rootfs.toPath().toRealPath() }.getOrNull()

    /** True when the rootfs exists and this instance can answer anything. */
    val available: Boolean get() = rootfs != null

    private val excluded: List<String> = view.excludedGuestPrefixes.mapNotNull { prefix ->
        normalizeGuest(prefix)?.takeIf { it != "/" }
    }

    // ── read-only operations (null = "not answerable host-side") ───────────

    fun list(guestPath: String): List<FileInfo>? {
        val dir = resolve(guestPath, followFinal = true) ?: return null
        if (!Files.isDirectory(dir)) return null
        val entries = try {
            Files.newDirectoryStream(dir).use { stream ->
                stream.mapNotNull { entry ->
                    runCatching { describe(guestPath, entry) }.getOrNull()
                }
            }
        } catch (_: IOException) {
            return null
        } catch (_: SecurityException) {
            return null
        }
        return entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    fun read(guestPath: String): String? {
        val file = resolve(guestPath, followFinal = true) ?: return null
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) return null
        return try {
            Files.readAllBytes(file).decodeToString()
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    fun stat(guestPath: String): FileInfo? {
        val path = resolve(guestPath, followFinal = false) ?: return null
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        val normalized = normalizeGuest(guestPath) ?: return null
        return runCatching {
            if (normalized == "/") {
                describe("/", path, name = "/")
            } else {
                describe(parentOf(normalized), path)
            }
        }.getOrNull()
    }

    fun exists(guestPath: String): Boolean? {
        val path = resolve(guestPath, followFinal = false) ?: return null
        // A dangling symlink exists for delete/rename purposes, exactly like
        // the guest command (`test -e path -o -L path`).
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS)
    }

    fun size(guestPath: String): Long? {
        val path = resolve(guestPath, followFinal = true) ?: return null
        // Directories keep using `du -sk` on the guest: its block accounting
        // is not reproducible from the host without a full walk.
        if (!Files.isRegularFile(path)) return null
        return runCatching { Files.size(path) }.getOrNull()
    }

    // ── resolution ─────────────────────────────────────────────────────────

    /**
     * Resolve [guestPath] to a host path, or null when the path cannot be
     * served host-side (not absolute, excluded bind prefix, symlink cycle,
     * unreadable rootfs).
     */
    private fun resolve(guestPath: String, followFinal: Boolean): Path? {
        val root = rootfs ?: return null
        val normalized = normalizeGuest(guestPath) ?: return null
        if (isExcluded(normalized)) return null
        if (normalized == "/") return root

        // Guest-level component stack; absolute symlink targets clear it.
        val resolved = ArrayList<String>()
        val todo = ArrayDeque(normalized.trim('/').split('/'))
        var hops = 0
        while (todo.isNotEmpty()) {
            val name = todo.removeFirst()
            when (name) {
                "", "." -> Unit
                ".." -> if (resolved.isNotEmpty()) resolved.removeAt(resolved.lastIndex)
                else -> {
                    val host = hostOf(root, resolved, name)
                    val isLast = todo.isEmpty()
                    val link = if (!isLast || followFinal) readLink(host) else null
                    if (link == null) {
                        resolved.add(name)
                    } else {
                        if (++hops > MAX_SYMLINK_HOPS) return null
                        if (link.isAbsolute) resolved.clear()
                        val targetParts = link.toString().trim('/').split('/')
                        for (i in targetParts.indices.reversed()) {
                            todo.addFirst(targetParts[i])
                        }
                    }
                }
            }
        }
        val host = hostOf(root, resolved, null).normalize()
        return if (host.startsWith(root)) host else null
    }

    private fun hostOf(root: Path, resolved: List<String>, name: String?): Path {
        var path = root
        resolved.forEach { path = path.resolve(it) }
        if (name != null) path = path.resolve(name)
        return path.normalize()
    }

    private fun readLink(path: Path): Path? = try {
        if (Files.isSymbolicLink(path)) Files.readSymbolicLink(path) else null
    } catch (_: IOException) {
        null
    }

    private fun isExcluded(normalized: String): Boolean = excluded.any { prefix ->
        normalized == prefix || normalized.startsWith("$prefix/")
    }

    // ── FileInfo construction ──────────────────────────────────────────────

    private fun describe(
        directory: String,
        path: Path,
        name: String = path.fileName.toString()
    ): FileInfo {
        val attrs = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS
        )
        val mode = unixMode(path)
        val typeBits = mode?.and(TYPE_MASK) ?: 0
        val isDevice = typeBits == TYPE_CHAR || typeBits == TYPE_BLOCK
        val linkTarget = if (attrs.isSymbolicLink) {
            runCatching { Files.readSymbolicLink(path).toString() }.getOrNull()
        } else {
            null
        }
        return FileInfo(
            name = name,
            path = joinGuest(directory, name),
            isDirectory = attrs.isDirectory,
            size = if (isDevice) 0L else attrs.size(),
            permissions = modeString(path, attrs, typeBits, mode),
            lastModified = DATE_FORMAT.format(Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis())),
            isSymlink = attrs.isSymbolicLink,
            linkTarget = linkTarget,
            hasByteSize = !isDevice
        )
    }

    private fun unixMode(path: Path): Int? = try {
        (Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS) as? Int)
    } catch (_: Exception) {
        null
    }

    private fun modeString(
        path: Path,
        attrs: BasicFileAttributes,
        typeBits: Int,
        mode: Int?
    ): String {
        val type = when {
            attrs.isDirectory || typeBits == TYPE_DIR -> 'd'
            attrs.isSymbolicLink || typeBits == TYPE_LINK -> 'l'
            typeBits == TYPE_FIFO -> 'p'
            typeBits == TYPE_SOCKET -> 's'
            typeBits == TYPE_CHAR -> 'c'
            typeBits == TYPE_BLOCK -> 'b'
            else -> '-'
        }
        val bits = mode?.and(0b111_111_111) ?: posixBits(path)
        val chars = CharArray(9)
        for (i in 0 until 9) {
            chars[i] = if (bits and (1 shl (8 - i)) != 0) PERMISSION_CHARS[i] else '-'
        }
        if (mode != null) {
            if (bits and 0b100_000_000 != 0) chars[2] = if (chars[2] == 'x') 's' else 'S'
            if (bits and 0b000_100_000 != 0) chars[5] = if (chars[5] == 'x') 's' else 'S'
            if (bits and 0b000_000_100 != 0) chars[8] = if (chars[8] == 'x') 't' else 'T'
        }
        return type + String(chars)
    }

    /** Fallback when the platform has no `unix:mode` attribute. */
    private fun posixBits(path: Path): Int = try {
        var bits = 0
        Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).forEach { permission ->
            bits = bits or when (permission) {
                java.nio.file.attribute.PosixFilePermission.OWNER_READ -> 0b100_000_000
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE -> 0b010_000_000
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE -> 0b001_000_000
                java.nio.file.attribute.PosixFilePermission.GROUP_READ -> 0b000_100_000
                java.nio.file.attribute.PosixFilePermission.GROUP_WRITE -> 0b000_010_000
                java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE -> 0b000_001_000
                java.nio.file.attribute.PosixFilePermission.OTHERS_READ -> 0b000_000_100
                java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE -> 0b000_000_010
                java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE -> 0b000_000_001
            }
        }
        bits
    } catch (_: Exception) {
        // A platform without POSIX permissions cannot answer; 0 keeps the
        // metadata row renderable instead of failing the whole listing.
        0
    }

    private fun normalizeGuest(path: String): String? {
        if (!path.startsWith("/")) return null
        val parts = ArrayList<String>()
        path.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(part)
            }
        }
        return "/" + parts.joinToString("/")
    }

    private fun parentOf(path: String): String {
        val normalized = normalizeGuest(path) ?: return "/"
        if (normalized == "/") return "/"
        val idx = normalized.lastIndexOf('/')
        return if (idx <= 0) "/" else normalized.substring(0, idx)
    }

    private fun joinGuest(directory: String, name: String): String =
        if (name == "/") "/"
        else if (directory == "/") "/$name"
        else "${directory.trimEnd('/')}/$name"

    private companion object {
        const val MAX_SYMLINK_HOPS = 40

        const val TYPE_MASK = 0xF000
        const val TYPE_FIFO = 0x1000
        const val TYPE_CHAR = 0x2000
        const val TYPE_DIR = 0x4000
        const val TYPE_BLOCK = 0x6000
        const val TYPE_REGULAR = 0x8000
        const val TYPE_LINK = 0xA000
        const val TYPE_SOCKET = 0xC000

        val PERMISSION_CHARS = "rwxrwxrwx".toCharArray()
        val DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
                .withZone(ZoneId.systemDefault())
    }
}
