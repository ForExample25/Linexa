package com.fem.linexa.ui.components

import com.fem.linexa.data.Workspace

/**
 * UI Phase 2 — everything the redesigned Home Workspace card *says*, computed
 * as a pure function of the [Workspace] it renders.
 *
 * Why this exists as a type rather than as string building inside the
 * composable. The card now shows six independent pieces of text (the user's
 * own workspace name, the distribution's name, its version, its short code, the
 * image's prose description, the total size) where it previously showed two, and
 * most of them are *optional* in ways that only occur on real data: a workspace
 * installed before §1.118 has no declared description and no codename, an
 * imported archive has no catalog code, and `DistroInfo.version` is the literal
 * string `"unknown"` for an undetected import (see
 * `InstallCoordinator.buildSummary`). Composing that inside `@Composable`
 * functions would make every one of those cases unverifiable — this repo has no
 * Compose UI test harness and no Robolectric (§1.124(a)) — so the rules would
 * only be checked by looking at a screenshot of whichever workspace the
 * developer happened to have installed.
 *
 * The layout still belongs to the composable. This type answers "what text,
 * and is it present at all", not "where does it go".
 */
data class WorkspaceCardContent(
    /**
     * The card's main title: the **user's** workspace name, always.
     *
     * Never the image's declared `display_name`. That value is only the
     * pre-filled, editable name on the install screen (§1.119), so once a
     * workspace exists the name the user accepted or typed is the only correct
     * title — showing the archive author's suggestion instead would make the
     * Rename action look like it did nothing.
     */
    val title: String,
    /**
     * The distribution's human-readable label — name, version and codename as
     * one line (`Debian 13 (trixie)`), or null when nothing is known.
     *
     * Retained alongside [distroName]/[distroVersionLabel] because it is the
     * form the *description* rule compares against (an image whose
     * `description:` is literally `Debian 13 (trixie)` says nothing the card
     * isn't already showing) and the form the share intent and diagnostics
     * header read. The card itself renders the split fields.
     */
    val distroLabel: String?,
    /**
     * The distribution's name on its own (`Debian`), or null when the workspace
     * declared none.
     *
     * Split out of [distroLabel] for the redesigned card, which places the name
     * directly under the workspace title and the version *beside* it. Those are
     * two independently-styled elements, so they cannot come from one
     * pre-joined string.
     */
    val distroName: String?,
    /**
     * The version as shown beside [distroName] — `13`, `13 (trixie)`, or
     * `trixie` for a rolling release that declared only a codename. Null when
     * neither is known or the version is uninformative.
     *
     * Codename travels with the version rather than with the name because it
     * qualifies the release, and because a card that showed `Debian (trixie)`
     * on line two while the version sat elsewhere would read as two different
     * facts about the same release.
     */
    val distroVersionLabel: String?,
    /**
     * The distribution's short lower-case code (`debian`, `archlinux`), or null.
     *
     * Kept separate from [distroLabel] rather than appended to it: it is a
     * machine identifier, is rendered as its own small monospace chip, and for
     * the common case would otherwise read as a stutter (`Debian · debian`).
     */
    val distroCode: String?,
    /**
     * The image's own prose description, or null when it declared none.
     *
     * Null is the normal case, not a defect: only an archive carrying
     * `linexa.metadata.description` (§1.118) or a legacy `.tiny.yaml`
     * `description:` (§1.119) has one, and the card must not fabricate prose for
     * every other workspace by restating facts it already displays.
     */
    val description: String?,
    /**
     * Total on-disk size of the whole workspace, pre-formatted, or null when it
     * could not be determined.
     *
     * Null is a real state, not a defect: `WorkspaceFileManager.getWorkspaceSize`
     * returns `0` for a workspace whose directory does not exist yet (a row
     * created before its rootfs, a workspace mid-delete), and an installed
     * workspace is never actually zero bytes. Rendering `0 B` there would state
     * a measurement that was never taken, so the card drops the whole size line
     * instead.
     */
    val sizeLabel: String?,
    /** 0f..1f share of the notional storage budget — cosmetic, as before. */
    val storageFraction: Float
) {
    /**
     * The distribution's name as the header renders it: the declared name, or
     * the short code when that is all this workspace has.
     *
     * The code is a fallback rather than its own line. It used to be rendered
     * separately, in monospace, under the name — which for the common case read
     * as a stutter (`Debian` / `debian`) and added a third text row to every
     * card for information the name already carried. As a fallback it earns its
     * place: an image that declared `distro.id: alpine` and no `distro.name`
     * still identifies itself, and nothing is invented, since the code is
     * declared metadata rather than a guess.
     */
    val distroHeadline: String? get() = distroName ?: distroCode

    companion object {

        /**
         * Version strings that carry no information and must not be rendered.
         *
         * `"unknown"` is not hypothetical: it is the literal value
         * `InstallCoordinator` persists when an archive's version could not be
         * detected and the selection had none, so a card that printed it would
         * read `Debian unknown` on a perfectly healthy imported workspace.
         */
        private val UNINFORMATIVE_VERSIONS = setOf("unknown", "n/a", "none", "-")

        fun from(workspace: Workspace): WorkspaceCardContent = WorkspaceCardContent(
            title = workspace.name,
            distroLabel = distroLabelOf(
                name = workspace.distroName,
                version = workspace.distroVersion,
                codename = workspace.distroCodename
            ),
            distroName = workspace.distroName?.trim()?.takeIf { it.isNotEmpty() },
            distroVersionLabel = distroVersionLabelOf(
                version = workspace.distroVersion,
                codename = workspace.distroCodename
            ),
            distroCode = workspace.distroCode?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
            description = descriptionOf(workspace),
            sizeLabel = workspace.storageUsedLabel?.trim()?.takeIf { it.isNotEmpty() },
            storageFraction = workspace.storageUsedFraction
        )

        /**
         * The version half of the identity line: `13`, `13 (trixie)`, or just
         * `trixie` when the release has a codename but no version number
         * (§1.118 — `distro.version` is legitimately null for rolling
         * releases). Null when neither is present or the version is one of
         * [UNINFORMATIVE_VERSIONS].
         *
         * Shares its cleaning rules with [distroLabelOf] so the split fields and
         * the joined label can never disagree about whether a version exists.
         */
        fun distroVersionLabelOf(version: String?, codename: String?): String? {
            val cleanVersion = cleanVersion(version)
            val cleanCodename = cleanCodename(codename, cleanVersion)
            return when {
                cleanVersion != null && cleanCodename != null -> "$cleanVersion ($cleanCodename)"
                cleanVersion != null -> cleanVersion
                else -> cleanCodename
            }
        }

        /**
         * `Debian 13 (trixie)`, dropping whichever parts are absent or
         * uninformative.
         *
         * A codename with no version still renders — `Arch Linux ARM (rolling)`
         * is meaningful, and a rolling release legitimately has no version
         * number at all (§1.118: `distro.version` is null for exactly that
         * reason). A codename identical to the version is dropped, since some
         * images declare `version: trixie`.
         */
        fun distroLabelOf(name: String?, version: String?, codename: String?): String? {
            val cleanName = name?.trim()?.takeIf { it.isNotEmpty() }
            val cleanVersion = cleanVersion(version)
            val cleanCodename = cleanCodename(codename, cleanVersion)

            val head = listOfNotNull(cleanName, cleanVersion).joinToString(" ")
            return when {
                head.isEmpty() && cleanCodename == null -> null
                head.isEmpty() -> cleanCodename
                cleanCodename == null -> head
                else -> "$head ($cleanCodename)"
            }
        }

        /** A version worth printing, or null. See [UNINFORMATIVE_VERSIONS]. */
        private fun cleanVersion(version: String?): String? = version?.trim()
            ?.takeIf { it.isNotEmpty() && it.lowercase() !in UNINFORMATIVE_VERSIONS }

        /**
         * A codename worth printing, or null. Dropped when it merely repeats
         * [cleanVersion] — some images declare `version: trixie`, and
         * `trixie (trixie)` is not information.
         */
        private fun cleanCodename(codename: String?, cleanVersion: String?): String? =
            codename?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals(cleanVersion, ignoreCase = true) }

        /**
         * The declared description, or null.
         *
         * Suppressed when it merely repeats text the card already shows on
         * another line — its own title, or the distro label. Real images do this:
         * a `.tiny.yaml` whose `description:` is just the distro's name would
         * otherwise print the same words twice, two lines apart.
         */
        private fun descriptionOf(workspace: Workspace): String? {
            val text = workspace.imageDescription?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val label = distroLabelOf(
                name = workspace.distroName,
                version = workspace.distroVersion,
                codename = workspace.distroCodename
            )
            if (label != null && text.equals(label, ignoreCase = true)) return null
            if (text.equals(workspace.name.trim(), ignoreCase = true)) return null
            return text
        }
    }
}
