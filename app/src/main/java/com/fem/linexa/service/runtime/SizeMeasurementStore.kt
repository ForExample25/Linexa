package com.fem.linexa.service.runtime

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

/**
 * F-010 / F-037 / R-18: a small, atomic, on-disk cache of the last measured
 * workspace sizes.
 *
 * [SizeMeasurementCache] is deliberately in-memory: it removes the duplicate
 * walk *within* a process, but the first Home paint after every cold start
 * still paid a full-tree traversal per workspace (74,451 files / ~2.5 s in the
 * historical debug-build audit). The number itself changes only when files
 * change, so it is worth persisting.
 *
 * The store is intentionally not a Room table: the database is at schema v1
 * with no migration, and a size cache does not justify a schema bump. It is a
 * single `Properties` file (one record per workspace) written atomically
 * through a temp file, which is enough for a derived value that can always be
 * re-measured.
 *
 * ## Freshness
 *
 * A persisted value is used as a **stale-while-revalidate** answer: the caller
 * returns it immediately and schedules a fresh walk in the background, which
 * overwrites the record. Every mutation path that already invalidates
 * [SizeMeasurementCache] also invalidates this store, so an install/delete/
 * import cannot leave a permanently wrong size behind.
 */
internal class SizeMeasurementStore(
    private val file: File
) {
    private val lock = Any()

    fun read(workspaceId: String): SizeMeasurementCache.Measurements? {
        synchronized(lock) {
            val props = load() ?: return null
            val workspace = props.getProperty("$workspaceId.workspace")?.toLongOrNull() ?: return null
            val rootfs = props.getProperty("$workspaceId.rootfs")?.toLongOrNull() ?: return null
            if (workspace < 0L || rootfs < 0L || rootfs > workspace) return null
            return SizeMeasurementCache.Measurements(workspace, rootfs)
        }
    }

    /**
     * DRA-005 closure: age of the persisted measurement, or null when there is
     * no usable record. Used to bound background revalidation to a sane TTL
     * instead of re-walking a multi-GB tree on every stop/re-entry.
     */
    fun ageMillis(workspaceId: String, nowMs: Long = System.currentTimeMillis()): Long? {
        synchronized(lock) {
            val props = load() ?: return null
            val measuredAt = props.getProperty("$workspaceId.measuredAt")?.toLongOrNull() ?: return null
            if (measuredAt <= 0L) return null
            return (nowMs - measuredAt).coerceAtLeast(0L)
        }
    }

    fun write(workspaceId: String, measurements: SizeMeasurementCache.Measurements) {
        synchronized(lock) {
            val props = load() ?: Properties()
            props.setProperty("$workspaceId.workspace", measurements.workspaceBytes.toString())
            props.setProperty("$workspaceId.rootfs", measurements.rootfsBytes.toString())
            props.setProperty("$workspaceId.measuredAt", System.currentTimeMillis().toString())
            persist(props)
        }
    }

    fun invalidate(workspaceId: String) {
        synchronized(lock) {
            val props = load() ?: return
            val prefix = "$workspaceId."
            val keys = props.stringPropertyNames().filter { it.startsWith(prefix) }
            if (keys.isEmpty()) return
            keys.forEach { props.remove(it) }
            persist(props)
        }
    }

    fun invalidateAll() {
        synchronized(lock) {
            runCatching { file.delete() }
        }
    }

    private fun load(): Properties? = try {
        if (!file.isFile) null else Properties().apply {
            FileInputStream(file).use { load(it) }
        }
    } catch (_: Exception) {
        null
    }

    private fun persist(props: Properties) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(tmp).use { props.store(it, null) }
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) tmp.delete()
            }
        } catch (_: Exception) {
            // A cache that cannot persist is still correct: the next call
            // simply measures again.
        }
    }
}
