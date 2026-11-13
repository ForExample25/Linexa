package com.fem.linexa.domain.resource

/**
 * The CPU topology a percentage is normalised against.
 *
 * [onlineCores] is the capacity denominator: "percent of total device
 * capacity" means `ticks / elapsed / onlineCores.size`. [configuredCores] is
 * kept separately because `_SC_NPROCESSORS_CONF` can include hot-unplugged
 * cores, and [allowedCores] because an Android cpuset/affinity mask can grant
 * this app fewer cores than the device has online.
 *
 * The three values are deliberately not interchangeable. A UI that says
 * "8 cores" from [configuredCores] while dividing by seven online cores is
 * the kind of quiet mismatch this type prevents.
 */
data class CpuTopology(
    val configuredCores: Int,
    val onlineCores: List<Int>,
    val allowedCores: List<Int>?
) {
    /** Cores used by the device-capacity denominator. Never less than one. */
    val capacityCores: Int
        get() = onlineCores.size.coerceAtLeast(1)

    /** Logical online core numbers, in kernel order. */
    val coreIndices: List<Int>
        get() = onlineCores

    companion object {
        /**
         * A conservative fallback for test doubles and for platforms where the
         * topology could not be read. One core is the only denominator that
         * cannot overstate the device.
         */
        fun fallback(configuredCores: Int = 1): CpuTopology {
            val configured = configuredCores.coerceAtLeast(1)
            return CpuTopology(
                configuredCores = configured,
                onlineCores = (0 until configured).toList(),
                allowedCores = null
            )
        }
    }
}
