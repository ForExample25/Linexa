package com.fem.linexa.service.runtime.container

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files

/**
 * Validates a user/rootfs-declared shell path **inside a workspace rootfs**
 * before PRoot is asked to exec it (RootFS finding 018).
 *
 * `linexa.launch.shell` is deliberately the highest-precedence shell choice,
 * and it is user-editable. Without a check, a typo, a removed package, a
 * directory path, or a non-executable file makes every terminal fail at exec
 * (commonly exit 127). This validator is the fail-open gate: a rejected path
 * is logged with a concrete reason, and the caller falls back to
 * `linexa.environment.shell` and then the normal probe.
 *
 * ## Why the symlinks are resolved by hand
 *
 * `File(rootfs, path).canonicalFile` follows an **absolute** guest symlink
 * (`/bin/sh -> /usr/bin/dash`) against the *host* filesystem, not the rootfs.
 * The result is either a false rejection or, worse, a check against an
 * unrelated host file while PRoot would execute the guest one. The resolver
 * here keeps every hop inside [rootfs]: an absolute target restarts at the
 * rootfs root, `..` can never climb above it, and hop/segment counts are
 * bounded so a symlink loop is a rejection rather than a hang.
 *
 * ## What is accepted
 *
 * - a regular, executable file;
 * - a script whose bounded first line is `#!<absolute interpreter>` and whose
 *   interpreter is itself a regular, executable file inside the rootfs;
 * - an ELF binary whose `PT_INTERP` loader (if any) exists and is executable
 *   inside the rootfs — a static ELF needs no loader.
 *
 * A malformed ELF, an escaping path, a missing interpreter, or an unreadable
 * file is rejected with a human-readable reason. The caller never uses a
 * rejected path.
 */
internal object GuestShellValidator {

    /**
     * Returns null when [guestPath] is safe to launch inside [rootfs], or a
     * specific reason (suitable for a diagnostic log) when it is not.
     *
     * Pure inspection: no guest process is started, and no file is modified.
     */
    fun validate(rootfs: File, guestPath: String): String? {
        val resolved = when (val resolution = resolveInsideRootfs(rootfs, guestPath)) {
            is Resolution.Rejected -> return resolution.reason
            is Resolution.Resolved -> resolution.file
        }
        if (!resolved.exists()) return "does not exist inside the rootfs"
        if (!resolved.isFile) return "is not a regular file"
        if (!resolved.canExecute()) return "is not executable"

        val prefix = readPrefix(resolved, ELF_IDENT_SIZE) ?: return "could not be read"
        if (prefix.size >= 2 && prefix[0] == '#'.code.toByte() && prefix[1] == '!'.code.toByte()) {
            val interpreter = readShebangInterpreter(resolved)
                ?: return "has no usable shebang interpreter"
            return validateInterpreter(rootfs, interpreter, "shebang interpreter")
        }
        if (isElfMagic(prefix)) {
            return when (val elf = inspectElf(resolved)) {
                ElfInspection.Malformed -> "is an ELF binary with malformed program headers"
                ElfInspection.Static -> null
                is ElfInspection.Interpreter -> validateInterpreter(rootfs, elf.path, "ELF interpreter")
            }
        }
        // An executable regular file that is neither a script nor an ELF is
        // left to the kernel/PRoot: the finding is about paths that cannot
        // exec, and refusing unknown-but-executable files would be stricter
        // than the launch contract.
        return null
    }

    // ── path resolution ──────────────────────────────────────────────────

    private sealed interface Resolution {
        data class Resolved(val file: File) : Resolution
        data class Rejected(val reason: String) : Resolution
    }

    private fun resolveInsideRootfs(rootfs: File, guestPath: String): Resolution {
        val raw = guestPath.trim()
        if (raw.isEmpty()) return Resolution.Rejected("has an empty path")
        if (raw.length > MAX_PATH_CHARS) return Resolution.Rejected("has an overlong path")
        if (raw.any { it == '\u0000' || it.isISOControl() }) {
            return Resolution.Rejected("contains control characters")
        }
        if (!raw.startsWith("/")) return Resolution.Rejected("is not an absolute guest path")

        val pending = ArrayDeque(raw.split('/').filter { it.isNotEmpty() })
        if (pending.size > MAX_SEGMENTS) return Resolution.Rejected("has too many path segments")

        val resolved = ArrayList<String>()
        var hops = 0
        while (pending.isNotEmpty()) {
            val segment = pending.removeFirst()
            when (segment) {
                "." -> continue
                ".." -> {
                    if (resolved.isEmpty()) return Resolution.Rejected("escapes the rootfs")
                    resolved.removeAt(resolved.lastIndex)
                    continue
                }
            }
            val candidate = childOf(rootfs, resolved, segment)
            val isLink = try {
                Files.isSymbolicLink(candidate.toPath())
            } catch (e: Exception) {
                return Resolution.Rejected("could not be inspected (${e.javaClass.simpleName})")
            }
            if (isLink) {
                if (++hops > MAX_SYMLINK_HOPS) {
                    return Resolution.Rejected("has too many symbolic links (possible loop)")
                }
                val target = try {
                    Files.readSymbolicLink(candidate.toPath()).toString()
                } catch (e: Exception) {
                    return Resolution.Rejected("has an unreadable symbolic link (${e.javaClass.simpleName})")
                }
                if (target.isEmpty()) return Resolution.Rejected("has an empty symbolic-link target")
                if (target.startsWith("/")) resolved.clear()
                val parts = target.split('/').filter { it.isNotEmpty() }
                for (i in parts.indices.reversed()) pending.addFirst(parts[i])
                continue
            }
            if (pending.isNotEmpty() && !candidate.isDirectory) {
                // A component that does not exist is not "not a directory".
                // Keep walking so the final existence check reports the honest
                // "does not exist inside the rootfs" (and so a missing tail
                // such as `/lib/ld-missing.so` names the file, not `/lib`).
                if (!candidate.exists()) {
                    resolved.add(segment)
                    continue
                }
                return Resolution.Rejected("path component '$segment' is not a directory")
            }
            resolved.add(segment)
        }
        if (resolved.isEmpty()) return Resolution.Rejected("names the rootfs root itself")
        return Resolution.Resolved(resolved.fold(rootfs) { parent, segment -> File(parent, segment) })
    }

    private fun childOf(rootfs: File, resolved: List<String>, segment: String): File =
        resolved.fold(rootfs) { parent, part -> File(parent, part) }.let { File(it, segment) }

    // ── shebang / ELF inspection ─────────────────────────────────────────

    private fun validateInterpreter(rootfs: File, guestPath: String, label: String): String? {
        if (!guestPath.startsWith("/")) {
            return "$label '$guestPath' is not an absolute guest path"
        }
        val resolved = when (val resolution = resolveInsideRootfs(rootfs, guestPath)) {
            is Resolution.Rejected -> return "$label '$guestPath' ${resolution.reason}"
            is Resolution.Resolved -> resolution.file
        }
        if (!resolved.exists()) return "$label '$guestPath' does not exist inside the rootfs"
        if (!resolved.isFile) return "$label '$guestPath' is not a regular file"
        if (!resolved.canExecute()) return "$label '$guestPath' is not executable"
        return null
    }

    /** Bounded first line; the kernel itself caps `#!` at 127/255 bytes. */
    private fun readShebangInterpreter(file: File): String? {
        val bytes = readPrefix(file, MAX_SHEBANG_BYTES) ?: return null
        val newline = bytes.indexOf('\n'.code.toByte()).let { if (it < 0) bytes.size else it }
        if (newline < 2) return null
        if (bytes[0] != '#'.code.toByte() || bytes[1] != '!'.code.toByte()) return null
        val line = String(bytes, 2, newline - 2, Charsets.UTF_8)
        val interpreter = line.trimStart(' ', '\t').substringBefore(' ').substringBefore('\t')
        return interpreter.takeIf { it.isNotEmpty() }
    }

    private sealed interface ElfInspection {
        data object Static : ElfInspection
        data object Malformed : ElfInspection
        data class Interpreter(val path: String) : ElfInspection
    }

    /**
     * Reads the ELF program-header table and returns `PT_INTERP`, if present.
     * Both 32/64-bit and little/big-endian headers are supported; every offset
     * is bounds-checked against the real file length before reading.
     */
    private fun inspectElf(file: File): ElfInspection {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                if (length < ELF_IDENT_SIZE) return ElfInspection.Malformed
                val ident = ByteArray(ELF_IDENT_SIZE)
                raf.readFully(ident)
                if (!isElfMagic(ident)) return ElfInspection.Malformed

                val elfClass = ident[4].toInt() and 0xFF
                val data = ident[5].toInt() and 0xFF
                if ((elfClass != 1 && elfClass != 2) || (data != 1 && data != 2)) {
                    return ElfInspection.Malformed
                }
                val littleEndian = data == 1
                val headerSize = if (elfClass == 1) ELF32_HEADER_SIZE else ELF64_HEADER_SIZE
                if (length < headerSize) return ElfInspection.Malformed
                val header = ByteArray(headerSize)
                raf.seek(0)
                raf.readFully(header)

                val phoff = if (elfClass == 1) {
                    readUInt32(header, 28, littleEndian)
                } else {
                    readUInt64(header, 32, littleEndian)
                }
                val phentsize = readUInt16(
                    header,
                    if (elfClass == 1) 42 else 54,
                    littleEndian
                )
                val phnum = readUInt16(
                    header,
                    if (elfClass == 1) 44 else 56,
                    littleEndian
                )
                if (phnum <= 0) return ElfInspection.Static
                val minEntrySize = if (elfClass == 1) ELF32_PHENT_SIZE else ELF64_PHENT_SIZE
                if (phentsize < minEntrySize) return ElfInspection.Malformed
                val tableBytes = phentsize.toLong() * phnum
                if (tableBytes <= 0 || tableBytes > MAX_ELF_TABLE_BYTES) return ElfInspection.Malformed
                if (phoff < 0 || phoff > length - tableBytes) return ElfInspection.Malformed
                raf.seek(phoff)
                val table = ByteArray(tableBytes.toInt())
                raf.readFully(table)

                var interpreter: String? = null
                for (index in 0 until phnum) {
                    val base = index * phentsize
                    if (readUInt32(table, base, littleEndian) != PT_INTERP.toLong()) continue
                    val offset = if (elfClass == 1) {
                        readUInt32(table, base + 4, littleEndian)
                    } else {
                        readUInt64(table, base + 8, littleEndian)
                    }
                    val size = if (elfClass == 1) {
                        readUInt32(table, base + 16, littleEndian)
                    } else {
                        readUInt64(table, base + 32, littleEndian)
                    }
                    if (size <= 0 || size > MAX_INTERPRETER_BYTES) return ElfInspection.Malformed
                    if (offset < 0 || offset > length - size) return ElfInspection.Malformed
                    raf.seek(offset)
                    val bytes = ByteArray(size.toInt())
                    raf.readFully(bytes)
                    val end = bytes.indexOf(0.toByte()).let { if (it < 0) bytes.size else it }
                    val path = String(bytes, 0, end, Charsets.UTF_8).trim()
                    if (path.isEmpty()) return ElfInspection.Malformed
                    interpreter = path
                }
                interpreter?.let { ElfInspection.Interpreter(it) } ?: ElfInspection.Static
            }
        } catch (e: IOException) {
            ElfInspection.Malformed
        } catch (e: SecurityException) {
            ElfInspection.Malformed
        }
    }

    private fun readPrefix(file: File, maxBytes: Int): ByteArray? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val length = minOf(raf.length(), maxBytes.toLong()).toInt()
                if (length <= 0) return null
                val bytes = ByteArray(length)
                raf.readFully(bytes)
                bytes
            }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    private fun isElfMagic(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x7F.toByte() &&
            bytes[1] == 'E'.code.toByte() &&
            bytes[2] == 'L'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()

    private fun readUInt16(bytes: ByteArray, offset: Int, littleEndian: Boolean): Int {
        if (offset < 0 || offset + 2 > bytes.size) return -1
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        return if (littleEndian) b0 or (b1 shl 8) else (b0 shl 8) or b1
    }

    private fun readUInt32(bytes: ByteArray, offset: Int, littleEndian: Boolean): Long {
        if (offset < 0 || offset + 4 > bytes.size) return -1
        var value = 0L
        if (littleEndian) {
            for (i in 3 downTo 0) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        } else {
            for (i in 0 until 4) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return value
    }

    private fun readUInt64(bytes: ByteArray, offset: Int, littleEndian: Boolean): Long {
        if (offset < 0 || offset + 8 > bytes.size) return -1
        var value = 0L
        if (littleEndian) {
            for (i in 7 downTo 0) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        } else {
            for (i in 0 until 8) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return value
    }

    private const val ELF_IDENT_SIZE = 16
    private const val ELF32_HEADER_SIZE = 52
    private const val ELF64_HEADER_SIZE = 64
    private const val ELF32_PHENT_SIZE = 32
    private const val ELF64_PHENT_SIZE = 56
    private const val PT_INTERP = 3
    private const val MAX_PATH_CHARS = 4096
    private const val MAX_SEGMENTS = 256
    private const val MAX_SYMLINK_HOPS = 40
    private const val MAX_SHEBANG_BYTES = 256
    private const val MAX_ELF_TABLE_BYTES = 64L * 1024L
    private const val MAX_INTERPRETER_BYTES = 4096L
}
