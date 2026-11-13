package com.fem.linexa.ui.screens.settings

/**
 * UI/UX Phase 4 (2) — the Help / Information links, as a pure Compose-free catalog.
 *
 * The plan's constraint on this section is unusually strict and worth restating,
 * because it is the whole reason this is a typed catalog rather than four
 * `stringResource`s inlined into rows: **only real, existing project URLs, and none
 * invented.** A plausible-looking `t.me/linexa` costs nothing to type, compiles, looks
 * correct in review, and sends every user who taps it to a channel that either does not
 * exist or belongs to someone else. That failure is invisible to `assembleDebug` and to
 * a screenshot — it is only visible to the person who taps it.
 *
 * So the URLs live here, in one place, in Kotlin rather than in `strings.xml`: a URL is
 * not translatable copy (a localized build must not be able to fork it), and keeping it
 * out of the resource table is what makes it assertable. [SettingsLinksTest] pins each
 * one and rejects the shapes a guess takes — a non-`https` scheme, a host outside the
 * two this project actually uses, a placeholder segment, a trailing slash — so an
 * invented link fails in CI instead of on a device.
 *
 * Every entry here was supplied by the project owner (2026-08-12). There is deliberately
 * **no** Discord/Matrix/mailing-list row: this project has none, and an empty row that
 * "looks complete" is worse than an absent one.
 */
enum class HelpLink(
    /** The absolute URL a tap opens. Always `https`. */
    val url: String,
    /** What this link is *for* — see [Kind]. */
    val kind: Kind
) {

    /**
     * Help: the project README.
     *
     * The `#readme` fragment rather than the bare repository URL, so the tap lands on
     * the documentation rather than on a file listing — GitHub renders the README below
     * the tree, and on a phone that is a scroll the user should not have to discover.
     */
    HELP(
        url = "https://github.com/ForExample25/Linexa#readme",
        kind = Kind.HELP
    ),

    /** The project's source repository. */
    SOURCE(
        url = "https://github.com/ForExample25/Linexa",
        kind = Kind.PROJECT
    ),

    /**
     * Where to report a bug.
     *
     * Separate from [SOURCE] on purpose: "report a problem" is the action a user on a
     * settings screen actually wants, and making them find the Issues tab inside a
     * mobile GitHub page is a real drop-off.
     */
    ISSUES(
        url = "https://github.com/ForExample25/Linexa/issues",
        kind = Kind.PROJECT
    ),

    /** The project's Telegram channel (announcements). */
    TELEGRAM_CHANNEL(
        url = "https://t.me/aniartx",
        kind = Kind.COMMUNITY
    ),

    /**
     * Direct contact with the developer.
     *
     * Listed last and labelled as a personal contact rather than as support, because it
     * is one person's private chat — presenting it as a support channel would set an
     * expectation the project cannot meet.
     */
    DEVELOPER_CONTACT(
        url = "https://t.me/for_example_me",
        kind = Kind.COMMUNITY
    );

    /**
     * What a link is, which is what decides the group it appears in.
     *
     * Not a cosmetic grouping: "how do I use this" and "where do I talk to people" are
     * different questions, and the plan asks for both. Declaration order is section
     * order, so adding a link cannot silently reorder the screen.
     */
    enum class Kind { HELP, PROJECT, COMMUNITY }

    companion object {

        /** The links of one [Kind], in declaration order. */
        fun of(kind: Kind): List<HelpLink> = entries.filter { it.kind == kind }

        /**
         * A short, readable identification of a link's destination, shown under its
         * title so the user can see where a tap goes *before* leaving the app.
         *
         * Drops the scheme and any trailing slash and keeps everything else — these URLs
         * are short by construction (the longest is 43 characters), so unlike
         * [com.fem.linexa.ui.screens.about.AboutContent.shortenSource] there is nothing
         * to elide, and eliding the middle of `github.com/ForExample25/Linexa/issues`
         * would hide exactly the part that identifies it.
         */
        fun displayUrl(url: String): String = url
            .removePrefix("https://")
            .removePrefix("http://")
            .removeSuffix("/")
    }
}
