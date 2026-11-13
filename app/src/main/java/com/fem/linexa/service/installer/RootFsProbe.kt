package com.fem.linexa.service.installer

/**
 * Distro/architecture detection for a rootfs archive — the "Detect the
 * Linux distribution" + "Detect architecture" responsibilities of the
 * Analyze Root Filesystem stage.
 *
 * This is a PURE detector: it takes the raw signals the scan pass collected
 * while walking the tar entries ([RootFsSignals]) and returns a
 * [RootFsIdentity]. It does no I/O, so it is fully JVM-testable.
 *
 * Signals:
 *  - `etc/os-release` (or `usr/lib/os-release`) text — `ID`/`NAME`/
 *    `VERSION_ID` are parsed per the os-release(5) spec (values may be
 *    quoted, `VERSION_ID` may be absent for rolling releases).
 *  - Distro marker files: `etc/alpine-release`, `etc/arch-release`,
 *    `etc/debian_version`, `etc/fedora-release` / `etc/redhat-release`
 *    (these exist when the distro predates or omits os-release, and carry
 *    the version for Alpine/Debian).
 *  - Architecture hints collected from entry names: glibc/musl loader
 *    paths and multilib toolchain directories (`lib/aarch64-linux-gnu/…`,
 *    `lib/ld-musl-aarch64.so.1`, …).
 */
object RootFsProbe {

    /**
     * Signals captured during the archive scan pass — the raw input to
     * [detect]. `null` marker content means the file was not present in the
     * archive; an empty-but-present marker is an empty string.
     */
    data class Signals(
        val osRelease: String? = null,
        val alpineRelease: String? = null,
        val archRelease: String? = null,
        val debianVersion: String? = null,
        val fedoraRelease: String? = null,
        /** Lower-cased entry-name fragments that matched an arch pattern (see [ArchHint]). */
        val archHints: Set<String> = emptySet()
    )

    /** The detection result shown on the confirmation screen + persisted as DistroInfo. */
    data class Identity(
        val distroId: DistroId,
        val distroName: String,
        val version: String,
        val arch: RootFsArch,
        /** False when nothing matched — callers should fall back to the user's selection. */
        val detected: Boolean
    )

    enum class DistroId(val displayName: String) {
        DEBIAN("Debian"),
        UBUNTU("Ubuntu"),
        ALPINE("Alpine Linux"),
        ARCH("Arch Linux ARM"),
        FEDORA("Fedora"),
        OTHER("Linux")
    }

    enum class RootFsArch(val label: String, val abi: String) {
        ARM64_V8A("arm64", "arm64-v8a"),
        ARMEABI_V7A("armv7l", "armeabi-v7a"),
        X86_64("x86_64", "x86_64"),
        X86("i686", "x86"),
        UNKNOWN("unknown", "unknown")
    }

    /**
     * Entry-name fragments the scan pass matches to learn the archive's
     * target CPU. `label` is a stable identifier the scan stores in
     * [Signals.archHints]; [detectArch] maps labels back to [RootFsArch].
     */
    private object ArchHint {
        const val AARCH64 = "aarch64"
        const val ARMV7 = "armv7"
        const val X86_64 = "x86_64"
        const val X86 = "i386"

        /** Patterns applied to a lower-cased entry name; first hit wins per entry. */
        fun match(name: String): String? = when {
            containsAny(name, "aarch64-linux-gnu", "ld-musl-aarch64", "ld-linux-aarch64") -> AARCH64
            containsAny(name, "arm-linux-gnueabihf", "ld-musl-armhf", "ld-linux-armhf", "armv7l") -> ARMV7
            containsAny(name, "x86_64-linux-gnu", "ld-linux-x86-64") -> X86_64
            containsAny(name, "i386-linux-gnu", "i686", "ld-linux.so") -> X86
            else -> null
        }

        private fun containsAny(name: String, vararg needles: String): Boolean =
            needles.any { name.contains(it) }
    }

    /**
     * Public entry point the scan pass calls per entry to collect arch
     * hints (see [Signals.archHints]). Returns the matched hint label or
     * null. First match wins per entry.
     */
    fun matchArchHint(lowerCasedEntryName: String): String? =
        ArchHint.match(lowerCasedEntryName)

    fun detect(signals: Signals): Identity {
        val os = signals.osRelease?.let(::parseOsRelease)

        val distroId = when {
            os?.id == "debian" -> DistroId.DEBIAN
            os?.id == "ubuntu" -> DistroId.UBUNTU
            os?.id == "alpine" -> DistroId.ALPINE
            os?.id == "arch" || os?.id == "archarm" -> DistroId.ARCH
            os?.id == "fedora" -> DistroId.FEDORA
            // Derivative distros: ID_LIKE names the parent in preference order.
            os != null -> os.idLike.firstNotNullOfOrNull(::distroIdFor)
                ?: signals.alpineRelease?.let { DistroId.ALPINE }
                ?: signals.archRelease?.let { DistroId.ARCH }
                ?: signals.debianVersion?.let { DistroId.DEBIAN }
                ?: signals.fedoraRelease?.let { DistroId.FEDORA }
                ?: DistroId.OTHER
            signals.alpineRelease != null -> DistroId.ALPINE
            signals.archRelease != null -> DistroId.ARCH
            signals.debianVersion != null -> DistroId.DEBIAN
            signals.fedoraRelease != null -> DistroId.FEDORA
            else -> DistroId.OTHER
        }

        val version = os?.versionId
            ?.ifBlank { null }
            ?: signals.debianVersion?.trim()
            ?: signals.alpineRelease?.trim()
            ?: signals.fedoraRelease?.trim()
            ?: "unknown"

        val arch = detectArch(signals.archHints)
        val detected = distroId != DistroId.OTHER && arch != RootFsArch.UNKNOWN

        return Identity(
            distroId = distroId,
            // A known distro owns its name; an unknown one is better labelled
            // with its own PRETTY_NAME than the generic "Linux".
            distroName = if (distroId == DistroId.OTHER) {
                os?.prettyName ?: os?.name ?: distroId.displayName
            } else {
                distroId.displayName
            },
            version = version,
            arch = arch,
            detected = detected
        )
    }

    private fun distroIdFor(id: String): DistroId? = when (id.lowercase()) {
        "debian" -> DistroId.DEBIAN
        "ubuntu" -> DistroId.UBUNTU
        "alpine" -> DistroId.ALPINE
        "arch", "archarm", "archlinux" -> DistroId.ARCH
        "fedora", "rhel", "centos", "rocky", "almalinux" -> DistroId.FEDORA
        else -> null
    }

    private fun detectArch(hints: Set<String>): RootFsArch = when {
        ArchHint.AARCH64 in hints -> RootFsArch.ARM64_V8A
        ArchHint.ARMV7 in hints -> RootFsArch.ARMEABI_V7A
        ArchHint.X86_64 in hints -> RootFsArch.X86_64
        ArchHint.X86 in hints -> RootFsArch.X86
        else -> RootFsArch.UNKNOWN
    }

    /** Minimal os-release(5) parser: `KEY="value"` / `KEY=value` lines. */
    private fun parseOsRelease(text: String): OsRelease {
        var id: String? = null
        var versionId: String? = null
        var name: String? = null
        var prettyName: String? = null
        var idLike: List<String> = emptyList()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) continue
            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue
            val key = trimmed.substring(0, eq).trim()
            var value = trimmed.substring(eq + 1).trim()
            if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
                value = value.substring(1, value.length - 1)
            }
            when (key) {
                "ID" -> id = value
                "VERSION_ID" -> versionId = value
                "NAME" -> name = value
                "PRETTY_NAME" -> prettyName = value
                "ID_LIKE" -> idLike = value.split(Regex("\\s+"))
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
            }
        }
        return OsRelease(
            id = id,
            versionId = versionId,
            name = name,
            prettyName = prettyName,
            idLike = idLike
        )
    }

    private data class OsRelease(
        val id: String?,
        val versionId: String?,
        val name: String?,
        val prettyName: String? = null,
        val idLike: List<String> = emptyList()
    )
}
