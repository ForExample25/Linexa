package com.fem.linexa.domain.whatsnew

/**
 * Whether the What's New card should appear, as a pure function of the four facts that
 * decide it: what the user has already acknowledged, which build is running, whether
 * that build ships any notes, and whether this install is a genuinely fresh one.
 *
 * A separate type rather than an `if` inside the ViewModel, for the reason this repo
 * keeps reaching for (`HomeChromeState`, `WorkspaceCardContent`, `GlobalSettingsContent`):
 * every case that matters here is a *state transition over time* — first install, then
 * an update, then a relaunch, then a second update — and none of them can be observed
 * in a screenshot or reached from a running app without reinstalling it. As a pure
 * function the whole sequence is a handful of assertions on the JVM, which is the only
 * test harness this repo has (§1.124(a)).
 *
 * The rule is deliberately **not** "show whenever the stored code differs from the
 * current one". It is monotonic — strictly greater — so a downgrade (reachable with
 * `adb install -r -d`, and by a user sideloading an older APK) does not present an
 * older release's changes as news.
 */
object WhatsNewDecision {

    /**
     * The stored value meaning "nothing has ever been recorded".
     *
     * Zero rather than `-1` or a nullable Int because it is below every real
     * `versionCode` (Android requires `versionCode >= 1`), so it can never collide
     * with a genuine acknowledgement, and because `SharedPreferences.getInt` needs a
     * non-null default anyway. Using a sentinel rather than `contains(key)` keeps the
     * whole decision expressible as arithmetic on two Ints, which is what makes it
     * testable without a `SharedPreferences` fake.
     */
    const val NOTHING_ACKNOWLEDGED: Int = 0

    /**
     * What the caller should do. Three outcomes, not two, because "do not show" splits
     * into two genuinely different obligations.
     */
    sealed interface Outcome {

        /** Show [notes]. The card is dismissed by acknowledging [ReleaseNotes.versionCode]. */
        data class Show(val notes: ReleaseNotes) : Outcome

        /**
         * Show nothing, and record [versionCode] as acknowledged **without** the user
         * having seen anything.
         *
         * This is the fresh-installation case, and the silent write is the whole point:
         * a fresh install has no previous version, so there is nothing to report — but
         * if nothing were recorded, the *next* launch would look identical, and the
         * launch after an update would have no baseline to compare against.
         * Establishing the baseline here is what makes the first real update show its
         * card exactly once.
         */
        data class EstablishBaseline(val versionCode: Int) : Outcome

        /**
         * Show nothing and write nothing.
         *
         * Covers three distinct situations that all correctly do nothing: the running
         * version has already been acknowledged (every launch after a dismissal), the
         * build ships no catalog entry, and the stored code is *higher* than the
         * running one (a downgrade).
         *
         * Writing nothing matters in the no-notes case specifically. A build with no
         * entry must leave the stored baseline alone, so that the next version that
         * *does* ship notes is still compared against the last version the user
         * actually acknowledged.
         */
        data object Hide : Outcome
    }

    /**
     * Decide what to do on this launch.
     *
     * @param lastAcknowledgedVersionCode the persisted value, or [NOTHING_ACKNOWLEDGED].
     * @param currentVersionCode `BuildConfig.VERSION_CODE` of the running build.
     * @param notes the catalog entry for [currentVersionCode], or null when there is none.
     * @param isFirstInstall whether the OS reports this package as never having been
     *   updated. See the branch order below for why this cannot be inferred from
     *   [lastAcknowledgedVersionCode] alone.
     *
     * The order of the branches is load-bearing and not interchangeable:
     *
     *  1. **A genuinely fresh install wins outright**, before notes are even consulted.
     *     Checked first because `currentVersionCode > NOTHING_ACKNOWLEDGED` is true for
     *     every real build, so without this branch a first installation would show the
     *     card — precisely what it must not do. It also runs ahead of the notes check
     *     so that installing a build which happens to ship *no* notes still establishes
     *     the baseline; otherwise that user's first update would look like a first
     *     install all over again.
     *  2. **No notes, nothing to say.** Also covers an entry that exists but is empty,
     *     which is a catalog mistake that would otherwise render as a titled card with
     *     a blank body.
     *  3. **An absent baseline on a non-fresh install is an update.** This is the user
     *     who was already running a build from *before* this feature existed: there is
     *     no stored value to compare against, but the OS says the package has been
     *     updated, so the running version's notes are genuinely news. Without this
     *     branch the feature would stay invisible until the *second* update after it
     *     shipped.
     *  4. **Strictly greater, only then show.** Equal means already acknowledged; lower
     *     means a downgrade.
     */
    fun decide(
        lastAcknowledgedVersionCode: Int,
        currentVersionCode: Int,
        notes: ReleaseNotes?,
        isFirstInstall: Boolean
    ): Outcome {
        val nothingStored = lastAcknowledgedVersionCode <= NOTHING_ACKNOWLEDGED

        if (nothingStored && isFirstInstall) return Outcome.EstablishBaseline(currentVersionCode)

        if (notes == null || !notes.hasContent) return Outcome.Hide

        if (nothingStored) return Outcome.Show(notes)

        // Strictly greater. Note this compares the *running build's* code against the
        // stored one and deliberately does not consult the codes of any versions in
        // between: a user going 2 → 5 sees version 5's notes and only version 5's,
        // because the card describes the build they are now running and claiming
        // versions 3 and 4 had been installed would be false.
        return if (currentVersionCode > lastAcknowledgedVersionCode) {
            Outcome.Show(notes)
        } else {
            Outcome.Hide
        }
    }
}
