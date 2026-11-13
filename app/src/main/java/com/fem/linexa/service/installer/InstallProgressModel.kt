package com.fem.linexa.service.installer

/**
 * Issue #5 (2026-09-03) — the staged, weighted, MONOTONIC progress model for
 * every install pipeline (in-app coordinator flow, background download
 * service, file import).
 *
 * ## The bug this replaces
 *
 * Progress used to be computed independently per stage with no shared scale:
 * the coordinator restarted the bar at 0 for each of download → analyze →
 * extract (100% → 0% → 100% → 15%), and DownloadService's `installFraction`
 * returned the fixed 50% stage boundary for every extraction event that
 * carried no `totalBytes` — so the bar oscillated 60% → 50% → 70% → 30% → 60%
 * exactly as reported. Extraction retries (the §1.50 seccomp fallback) and
 * downloader range-restarts snapped the bar to ~0 from inside a stage. Every
 * one of those was a real regression of the only promise a progress bar has:
 * once you show 60%, the operation is at least 60% done.
 *
 * ## The model
 *
 *  - The pipeline is an ordered list of stages, each with a WEIGHT summing
 *    to 1. The overall fraction is the weighted sum of the per-stage
 *    fractions — one scale across the whole operation, so crossing a stage
 *    boundary never moves the bar backwards.
 *  - Each stage reports its OWN 0..1 fraction; `null` means "this stage
 *    cannot measure itself right now" and leaves the stage's committed
 *    value untouched (entry-name events during extraction, analyze events
 *    without a content length, …) — never a fallback number that lies.
 *  - **Monotonic by construction:** the overall value is clamped to the
 *    maximum ever computed. A genuine in-stage restart (extraction attempt
 *    2 after a seccomp failure, a downloader restart-from-zero) simply
 *    freezes the bar until the work passes the previously-committed point —
 *    which is true, not cosmetic: those bytes WERE written once already.
 *    This is a semantic clamp, not a blind one: the stage label still shows
 *    where in the pipeline the operation is, so the bar cannot become
 *    meaningless.
 *  - **Deterministic indeterminate state:** until at least one stage reports
 *    a real fraction, [update] returns `null` — the UI's existing
 *    indeterminate rendering — instead of a fabricated 0%.
 */
class StagedProgressTracker(stages: List<Stage>) {

    /** One pipeline stage and its share of the whole operation. */
    data class Stage(val name: String, val weight: Float)

    private val orderedNames: List<String> = stages.map { it.name }
    private val weights: Map<String, Float> = stages.associate { it.name to it.weight }

    init {
        require(stages.isNotEmpty()) { "StagedProgressTracker needs at least one stage" }
        require(orderedNames.toSet().size == orderedNames.size) { "duplicate stage names: $orderedNames" }
        val total = stages.sumOf { it.weight.toDouble() }
        require(total in 0.99..1.01) { "stage weights must sum to 1 (got $total): $stages" }
    }

    /** Last real fraction reported per stage (absent = never measured). */
    private val stageFractions = HashMap<String, Float>()

    /** The monotonic overall value — only ever grows. */
    private var overall = 0f

    /** Flips true the first time any stage reports a real fraction. */
    private var measurable = false

    /**
     * Reports [fraction] for [stage] and returns the new overall fraction —
     * or `null` while the operation is still indeterminate (no stage has
     * measured anything yet). A `null` [fraction] never changes any committed
     * value; it only returns the current overall.
     */
    fun update(stage: String, fraction: Float?): Float? {
        require(stage in weights) { "unknown stage '$stage' (known: $orderedNames)" }
        if (fraction != null) {
            stageFractions[stage] = fraction.coerceIn(0f, 1f)
            measurable = true
            recompute()
        }
        return if (measurable) overall else null
    }

    /**
     * Marks a stage finished — for stages the pipeline signals as entered but
     * cannot measure (fixups, finalization): entering them means the prior
     * measured work is done and they themselves are bounded and fast.
     */
    fun complete(stage: String): Float? {
        require(stage in weights) { "unknown stage '$stage' (known: $orderedNames)" }
        stageFractions[stage] = 1f
        measurable = true
        recompute()
        return overall
    }

    /**
     * The current monotonic overall value, or `null` while the operation is
     * still indeterminate (no stage has reported a measurable fraction yet).
     */
    val overallNullable: Float?
        get() = if (measurable) overall else null

    private fun recompute() {
        var total = 0f
        for (name in orderedNames) {
            val fraction = stageFractions[name] ?: continue
            total += weights.getValue(name) * fraction
        }
        // The monotonic clamp: a stage restarting from zero (retry, range
        // restart) recomputes a lower total; the bar holds its high-water
        // mark instead of moving backwards.
        if (total > overall) overall = total
    }
}
