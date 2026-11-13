package com.fem.linexa.ui.screens.filemanager

import com.fem.linexa.domain.filemanager.FileInfo
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * FM-1 — how the browse list is ordered.
 *
 * [NATIVE] is the default and preserves exactly what the source handed us:
 * the guest's `LC_ALL=C ls -lA` order in Linux mode, and the Android lister's
 * directories-first / case-insensitive order in Android mode. Every other
 * criterion is an explicit user choice and keeps directories first.
 */
enum class FileSortCriterion(val key: String) {
    NATIVE("native"),
    NAME("name"),
    SIZE("size"),
    MODIFIED("modified"),
    TYPE("type");

    /** Name → Size → Modified → Type → Native, the chip's cycle. */
    fun next(): FileSortCriterion = when (this) {
        NATIVE -> NAME
        NAME -> SIZE
        SIZE -> MODIFIED
        MODIFIED -> TYPE
        TYPE -> NATIVE
    }
}

enum class FileSortDirection { ASCENDING, DESCENDING }

/**
 * A complete, persistable sort choice. Held by [FileManagerViewModel] (so it
 * survives rotation) and rendered by the toolbar's two chips.
 */
data class FileListSort(
    val criterion: FileSortCriterion = FileSortCriterion.NATIVE,
    val direction: FileSortDirection = FileSortDirection.ASCENDING
) {
    fun nextCriterion(): FileListSort = copy(criterion = criterion.next())

    fun toggledDirection(): FileListSort = copy(
        direction = if (direction == FileSortDirection.ASCENDING) {
            FileSortDirection.DESCENDING
        } else {
            FileSortDirection.ASCENDING
        }
    )
}

/**
 * FM-1 — pure search + ordering for one directory listing.
 *
 * ## Why this is a separate, JVM-tested object
 *
 * Linux and Android listings have different established contracts. Linux is
 * the guest's `LC_ALL=C` bytewise order (uppercase before lowercase, dotfiles
 * first); Android is the existing directories-first, case-insensitive order.
 * A single "natural sort" would silently rewrite one of them, so:
 *
 *  - [FileSortCriterion.NATIVE] never reorders anything;
 *  - explicit Name/Size/Modified/Type sorts are opt-in, always directories
 *    first, and tie-break by the platform's name order and then by path;
 *  - unknown size / mtime / extension always sinks to the bottom, in both
 *    directions, because "unknown" is not "small", "old" or "untyped";
 *  - direction flips only the primary key, never the dirs-first rule or the
 *    deterministic tiebreakers.
 *
 * Search is case-insensitive and ranked exact > prefix > word-prefix >
 * contains. A query only filters the current directory's [files]; it never
 * asks the domain layer for a different scope.
 */
internal object FileListPresentation {

    /** 120 ms is long enough to coalesce typing, short enough to feel live. */
    const val SEARCH_DEBOUNCE_MS = 120L

    private val ISO_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
    private val MONTH_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val TIME_OF_DAY = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val ISO_SHAPE = Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}$")
    private val WHITESPACE = Regex("\\s+")
    private val WORD_BOUNDARY = Regex("[^\\p{L}\\p{N}]+")

    /**
     * How well [name] matches [query], or null when it does not.
     *
     * Lower is better: 0 exact, 1 prefix, 2 starts a later word, 3 contains.
     * The empty query matches everything at the same rank.
     */
    fun searchRank(name: String, query: String): Int? {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return 0
        val n = name.lowercase(Locale.ROOT)
        if (n == q) return 0
        if (n.startsWith(q)) return 1
        if (n.split(WORD_BOUNDARY).any { it.startsWith(q) }) return 2
        if (n.contains(q)) return 3
        return null
    }

    /**
     * The visible browse list.
     *
     * @param linuxOrdering true for [FileManagerViewModel.BrowserMode.LINUX];
     *   selects bytewise (`LC_ALL=C`) name comparison instead of Android's
     *   case-insensitive order.
     * @param now reference point for `ls`'s year-less recent timestamps; tests
     *   pass a fixed value. Parsing happens once per entry, not per comparison.
     */
    fun filterAndSort(
        files: List<FileInfo>,
        query: String,
        sort: FileListSort,
        linuxOrdering: Boolean,
        now: LocalDateTime = LocalDateTime.now()
    ): List<FileInfo> {
        val q = query.trim()
        val filtered = if (q.isEmpty()) {
            files
        } else {
            files.filter { searchRank(it.name, q) != null }
        }
        if (filtered.size < 2) return filtered

        val ranks: Map<String, Int> = if (q.isEmpty()) {
            emptyMap()
        } else {
            filtered.associate { it.path to searchRank(it.name, q)!! }
        }
        val rankOrder = if (q.isEmpty()) {
            null
        } else {
            Comparator<FileInfo> { a, b ->
                ranks.getValue(a.path).compareTo(ranks.getValue(b.path))
            }
        }

        val order = if (sort.criterion == FileSortCriterion.NATIVE) {
            null
        } else {
            comparatorFor(sort, linuxOrdering, filtered, now)
        }

        val combined = when {
            rankOrder == null -> order
            order == null -> rankOrder
            else -> rankOrder.thenComparing(order)
        }
        return if (combined == null) filtered else filtered.sortedWith(combined)
    }

    private fun comparatorFor(
        sort: FileListSort,
        linuxOrdering: Boolean,
        files: List<FileInfo>,
        now: LocalDateTime
    ): Comparator<FileInfo> {
        // Pre-computed once per listing: both byte arrays and parsed
        // timestamps would otherwise be rebuilt on every comparison.
        val byteNames: Map<String, ByteArray> = if (linuxOrdering) {
            files.associate { it.path to it.name.toByteArray(Charsets.UTF_8) }
        } else {
            emptyMap()
        }
        val modified: Map<String, LocalDateTime?> = if (sort.criterion == FileSortCriterion.MODIFIED) {
            files.associate { it.path to parseModified(it.lastModified, now) }
        } else {
            emptyMap()
        }

        val directoriesFirst = Comparator<FileInfo> { a, b ->
            when {
                a.isDirectory == b.isDirectory -> 0
                a.isDirectory -> -1
                else -> 1
            }
        }
        val primary = when (sort.criterion) {
            FileSortCriterion.NATIVE -> Comparator<FileInfo> { _, _ -> 0 }
            FileSortCriterion.NAME -> Comparator<FileInfo> { a, b ->
                val comparison = if (linuxOrdering) {
                    compareBytes(
                        byteNames[a.path] ?: a.name.toByteArray(Charsets.UTF_8),
                        byteNames[b.path] ?: b.name.toByteArray(Charsets.UTF_8)
                    )
                } else {
                    // Direction flips the case-insensitive key only; the
                    // case-sensitive fallback below stays ascending so the
                    // order remains deterministic in both directions.
                    a.name.lowercase(Locale.ROOT)
                        .compareTo(b.name.lowercase(Locale.ROOT))
                }
                directionOf(comparison, sort.direction)
            }
            FileSortCriterion.SIZE -> Comparator<FileInfo> { a, b ->
                when {
                    a.hasByteSize && !b.hasByteSize -> -1
                    !a.hasByteSize && b.hasByteSize -> 1
                    !a.hasByteSize && !b.hasByteSize -> 0
                    else -> directionOf(a.size.compareTo(b.size), sort.direction)
                }
            }
            FileSortCriterion.MODIFIED -> Comparator<FileInfo> { a, b ->
                val left = modified[a.path]
                val right = modified[b.path]
                when {
                    left != null && right == null -> -1
                    left == null && right != null -> 1
                    left == null && right == null -> 0
                    else -> directionOf(left!!.compareTo(right!!), sort.direction)
                }
            }
            FileSortCriterion.TYPE -> Comparator<FileInfo> { a, b ->
                val left = typeKey(a.name)
                val right = typeKey(b.name)
                when {
                    left != null && right == null -> -1
                    left == null && right != null -> 1
                    left == null && right == null -> 0
                    else -> directionOf(left!!.compareTo(right!!), sort.direction)
                }
            }
        }
        return directoriesFirst
            .then(primary)
            .then(nameComparator(linuxOrdering, byteNames))
            .thenBy { it.path }
    }

    private fun directionOf(comparison: Int, direction: FileSortDirection): Int =
        if (direction == FileSortDirection.ASCENDING) comparison else -comparison

    /**
     * Linux: byte-for-byte `LC_ALL=C` comparison of the UTF-8 name. Android:
     * the established case-insensitive primary, with a case-sensitive fallback
     * so equal-folded names still have a deterministic order.
     */
    fun nameComparator(
        linuxOrdering: Boolean,
        byteNames: Map<String, ByteArray> = emptyMap()
    ): Comparator<FileInfo> = if (linuxOrdering) {
        Comparator { a, b ->
            compareBytes(
                byteNames[a.path] ?: a.name.toByteArray(Charsets.UTF_8),
                byteNames[b.path] ?: b.name.toByteArray(Charsets.UTF_8)
            )
        }
    } else {
        Comparator { a, b ->
            val folded = a.name.lowercase(Locale.ROOT).compareTo(b.name.lowercase(Locale.ROOT))
            if (folded != 0) folded else a.name.compareTo(b.name)
        }
    }

    /** Unsigned byte comparison — what `strcmp` under `LC_ALL=C` does. */
    fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val common = minOf(a.size, b.size)
        for (i in 0 until common) {
            val left = a[i].toInt() and 0xFF
            val right = b[i].toInt() and 0xFF
            if (left != right) return left - right
        }
        return a.size - b.size
    }

    /**
     * `FileInfo.lastModified` comes from two writers:
     *  - Android browsing: `yyyy-MM-dd HH:mm`;
     *  - the guest's `ls -lA` (LC_ALL=C): `MMM d HH:mm` for recent files and
     *    `MMM d yyyy` for older ones.
     *
     * Returns null for anything unrecognised; the callers sort that entry
     * last rather than pretending it is the epoch.
     */
    fun parseModified(raw: String, now: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        if (ISO_SHAPE.matches(value)) {
            return runCatching { LocalDateTime.parse(value, ISO_DATE_TIME) }.getOrNull()
        }
        val parts = value.split(WHITESPACE)
        if (parts.size != 3) return null
        val monthDay = runCatching { MonthDay.parse("${parts[0]} ${parts[1]}", MONTH_DAY) }
            .getOrNull() ?: return null
        if (parts[2].contains(':')) {
            val time = runCatching { LocalTime.parse(parts[2], TIME_OF_DAY) }.getOrNull()
                ?: return null
            val date = runCatching {
                LocalDateTime.of(now.year, monthDay.month, monthDay.dayOfMonth, time.hour, time.minute)
            }.getOrNull() ?: return null
            // `ls` prints a time for roughly the last six months. Around New
            // Year a December entry still belongs to the previous year.
            return if (date.isAfter(now.plusDays(1))) date.minusYears(1) else date
        }
        val year = parts[2].toIntOrNull() ?: return null
        return runCatching {
            LocalDateTime.of(year, monthDay.month, monthDay.dayOfMonth, 0, 0)
        }.getOrNull()
    }

    /**
     * The type group used by the Type sort: the lowercased extension, or null
     * when the name has no extension (dotfiles such as `.bashrc` are not
     * "bashrc" files). Extensionless entries sort last among files.
     */
    fun typeKey(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return null
        return name.substring(dot + 1).lowercase(Locale.ROOT)
    }
}
