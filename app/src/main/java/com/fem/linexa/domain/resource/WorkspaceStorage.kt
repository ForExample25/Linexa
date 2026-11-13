package com.fem.linexa.domain.resource

import com.fem.linexa.core.format.FileSizeFormatter

/**
 * A workspace's storage reading (UI/UX Phase 5B).
 *
 * Phase 5B stopped the panel from reusing `Workspace.storageUsedFraction`, whose
 * own KDoc calls it "purely cosmetic": it is `workspaceBytes / 10 GB`, a
 * hardcoded notional budget. Rendered inside a panel captioned "Storage" that
 * would be a fabricated metric — a 3 GB workspace on a full 32 GB phone and the
 * same workspace on an empty 512 GB phone would show the identical 30% bar.
 *
 * What is shown instead are two facts that are both real and both measured:
 * [usedBytes], the size of this workspace's own directory (the value deleting it
 * reclaims, §1.127), and [deviceFreeBytes], the space actually left on the
 * volume it lives on. The bar's denominator is `used + free`, i.e. the space this
 * workspace could occupy — not total device capacity, which would make every
 * workspace a sliver against the OS and other apps.
 */
data class WorkspaceStorage(
    /** Size of `workspaces/{id}/` — rootfs, config, logs, caches. */
    val usedBytes: Long,
    /** Usable bytes remaining on the workspace volume, or null if unknown. */
    val deviceFreeBytes: Long?
) {
    val usedLabel: String get() = FileSizeFormatter.format(usedBytes)

    val freeLabel: String? get() = deviceFreeBytes?.let { FileSizeFormatter.format(it) }

    /**
     * This workspace's share of the space it could use (`used / (used + free)`),
     * or null when free space is unknown.
     *
     * Null rather than a guess: an unknown denominator means the bar has no
     * honest length, and the panel renders the used figure without a bar.
     */
    val fraction: Float?
        get() {
            val free = deviceFreeBytes ?: return null
            val budget = usedBytes + free
            if (budget <= 0L) return null
            return (usedBytes.toFloat() / budget).coerceIn(0f, 1f)
        }
}
