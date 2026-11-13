package com.fem.linexa.domain.hostinfo

/**
 * Android host storage, in bytes.
 *
 * The provider answers ONLY for device-side capacity. Per-workspace rootfs
 * usage stays with [com.fem.linexa.service.runtime.WorkspaceFileManager] —
 * the two questions ("how much space does this phone have" and "how much
 * does workspace 7 weigh") have different sources (StatFs volumes vs a
 * symlink-safe file walk), different change rates, and mixing them is how a
 * "rootfs size" figure ends up silently divided by a 10 GB notional budget.
 *
 * @property primaryVolume the volume Linexa's own data lives on — internal
 *   app-private storage. This is THE volume that constrains RootFS installs
 *   and growth.
 * @property externalAppVolume `getExternalFilesDir`'s volume when the device
 *   has one (usually the same physical storage as primary on modern devices,
 *   but reported separately because StatFs reports it as its own mount).
 * @property sharedStorageVolume the user-visible "Shared Storage" bind source
 *   volume (`{externalFilesDir}/shared`) — same underlying volume as
 *   [externalAppVolume]; captured so diagnostics can show what `/mnt/shared`
 *   actually maps onto inside guests.
 * @property allVolumes every volume StorageManager enumerates that could be
 *   stat'd; never fails — an unreadable volume contributes nothing rather
 *   than poisoning the list.
 */
data class HostStorageInfo(
    val primaryVolume: HostStorageVolume?,
    val externalAppVolume: HostStorageVolume?,
    val sharedStorageVolume: HostStorageVolume?,
    val allVolumes: List<HostStorageVolume>
)

/**
 * One Android storage volume measured at snapshot time.
 *
 * @property id stable identity from StorageManager (UUID-ish), or the path
 *   when no UUID is available to app uid — used only for correlation between
 *   snapshots, never displayed raw in UI text without a friendly label.
 * @property description human label StorageManager provides, when present.
 * @property path mount path the measurement was taken against.
 * @property kind coarse classification for UI grouping.
 * @property state StorageManager volume state string (mounted/unmounted),
 *   null when the API withholds it.
 * @property totalBytes StatFs.totalBytes — capacity of the whole volume.
 * @property freeBytes StatFs.availableBytes — what THIS UID can still claim;
 *   already excludes the FS reserve. This is the field consumers should use.
 */
data class HostStorageVolume(
    val id: String,
    val description: String?,
    val path: String,
    val kind: Kind,
    val state: String?,
    val totalBytes: Long?,
    val freeBytes: Long?
) {
    /** Used bytes derived as `total - free`; null when either side unknown. */
    val usedBytes: Long?
        get() {
            val t = totalBytes ?: return null
            val f = freeBytes ?: return null
            if (f > t) return 0L
            return t - f
        }

    enum class Kind { INTERNAL_PRIMARY, APP_EXTERNAL, SHARED_BIND_SOURCE, OTHER }
}
