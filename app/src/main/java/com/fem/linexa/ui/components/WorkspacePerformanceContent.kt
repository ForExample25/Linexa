package com.fem.linexa.ui.components

import com.fem.linexa.domain.resource.WorkspaceResourceSample
import com.fem.linexa.domain.resource.WorkspaceStorage
import com.fem.linexa.domain.runtime.RuntimeState

/**
 * Everything the workspace performance panel *says*, as a pure function of the
 * runtime state and the latest sample (UI/UX Phase 5B).
 *
 * The layout belongs to the composable; this type answers "what text, what bar
 * length, and is the value known at all". It exists as a type for the same reason
 * [WorkspaceCardContent] does — this repo has no Compose UI test harness and no
 * Robolectric (§1.124(a)), so a rule expressed as an `if` inside a `@Composable`
 * is only ever verified by looking at a screenshot of whichever workspace the
 * developer happened to have running.
 *
 * And here the rules are almost entirely about *honesty*, which is precisely
 * what a screenshot cannot show: the plan requires that a metric which cannot be
 * calculated at workspace scope reports its limitation rather than a misleading
 * number. Three states have to stay distinguishable — **stopped** (no session, so
 * no reading exists), **unknown** (running, but `/proc` would not answer), and a
 * real **value** — and the failure mode being guarded is that all three collapse
 * into a confident `0%`.
 */
data class WorkspacePerformanceContent(
    /** True when a session is running; false renders the panel in its idle form. */
    val active: Boolean,
    val cpu: Metric,
    val memory: Metric,
    val storage: Metric,
    /**
     * Process count line (`4 processes`), or null when there is no live tree.
     *
     * The one metric with no caveat attached: it is a direct count of the pids in
     * the workspace's tree.
     */
    val processLabel: String?,
    /** Raw process count backing [processLabel], for locale-aware plurals. */
    val processCount: Int? = null,
    /**
     * True when the memory figure is a sum of `VmRSS` rather than `Pss` and is
     * therefore an upper bound. The UI appends a `~` and explains it in the
     * content description — a silently-inflated number is worse than a marked one.
     */
    val memoryApproximate: Boolean,
    /**
     * Cores the CPU figure's normalisation used (`sample.cpuCores`), or null
     * when no sample has answered yet. App-grid redesign: the task-manager
     * card qualifies its CPU bar with this denominator — the same number the
     * workspace pane number's "of device" sub-line describes — rather than
     * letting the reader guess whether the percent is per-core or total.
     */
    val cpuCores: Int? = null
) {
    /**
     * One row of the panel.
     *
     * [fraction] is null when the bar has no honest length; the UI must draw an
     * empty track rather than a zero-width bar that reads as "none".
     */
    data class Metric(
        val value: String,
        val fraction: Float?,
        val known: Boolean
    )

    companion object {

        /** Rendered for any metric whose value is genuinely unknown. */
        const val UNKNOWN = "—"

        /**
         * Build the panel's content.
         *
         * @param state the workspace's live runtime state. Anything other than
         *   `Running` means there is no process to measure, and every process
         *   metric reads idle — the panel never shows the last value from a
         *   previous session, which would look current.
         * @param sample the most recent reading, or null if none has completed
         *   yet.
         * @param storage this workspace's disk usage. Present regardless of run
         *   state, because it is a property of the installed workspace rather
         *   than of a session.
         */
        fun from(
            state: RuntimeState,
            sample: WorkspaceResourceSample?,
            storage: WorkspaceStorage?
        ): WorkspacePerformanceContent {
            val running = state is RuntimeState.Running
            return WorkspacePerformanceContent(
                active = running,
                cpu = cpuMetric(running, sample),
                memory = memoryMetric(running, sample),
                storage = storageMetric(storage),
                processLabel = processLabel(running, sample),
                processCount = sample?.processCount?.takeIf { running && it > 0 },
                memoryApproximate = running &&
                    sample?.memoryKb != null &&
                    !sample.memoryIsProportional,
                cpuCores = sample?.cpuCores?.takeIf { it > 0 }
            )
        }

        /**
         * CPU row.
         *
         * A running workspace with no CPU reading yet is *unknown*, not zero: the
         * value is a delta between two samples, so the first tick after launch
         * genuinely has no answer. Reporting `0%` there put a dip the workspace
         * never had into the chart on every start.
         */
        private fun cpuMetric(running: Boolean, sample: WorkspaceResourceSample?): Metric {
            if (!running) return idle()
            val percent = sample?.cpuPercentOfDevice ?: return unknown()
            return Metric(
                value = com.fem.linexa.core.format.CpuPercentFormatter.format(percent),
                fraction = sample.cpuFraction,
                known = true
            )
        }

        private fun memoryMetric(running: Boolean, sample: WorkspaceResourceSample?): Metric {
            if (!running) return idle()
            val kb = sample?.memoryKb ?: return unknown()
            val label = formatKb(kb)
            return Metric(
                value = if (sample.memoryIsProportional) label else "~$label",
                fraction = sample.memoryFraction,
                known = true
            )
        }

        /**
         * Storage row — the only metric that is meaningful for a stopped
         * workspace, so it never renders as idle.
         *
         * A null [WorkspaceStorage.fraction] (free space unknown) still shows the
         * measured size; only the bar is withheld.
         */
        private fun storageMetric(storage: WorkspaceStorage?): Metric {
            if (storage == null) return unknown()
            return Metric(
                value = storage.usedLabel,
                fraction = storage.fraction,
                known = true
            )
        }

        private fun processLabel(running: Boolean, sample: WorkspaceResourceSample?): String? {
            if (!running) return null
            val count = sample?.processCount ?: return null
            if (count <= 0) return null
            return if (count == 1) "1 process" else "$count processes"
        }

        private fun idle() = Metric(value = UNKNOWN, fraction = null, known = false)

        private fun unknown() = Metric(value = UNKNOWN, fraction = null, known = false)

        /**
         * kB → a short label, reusing the app's binary units.
         *
         * Deliberately not [com.fem.linexa.core.format.FileSizeFormatter]: that
         * takes **bytes**, and `kb * 1024` would overflow nothing here but would
         * imply a byte-level precision the kernel did not report.
         */
        fun formatKb(kb: Long): String {
            val value = if (kb < 0) 0L else kb
            val mb = value / 1024.0
            return when {
                mb < 1.0 -> "${value} kB"
                mb < 1024.0 -> "%.0f MB".format(java.util.Locale.US, mb)
                else -> "%.1f GB".format(java.util.Locale.US, mb / 1024.0)
            }
        }
    }
}
