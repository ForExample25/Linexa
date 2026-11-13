package com.fem.linexa.domain.attribution

/**
 * §1.130 — a license Linexa has an attribution obligation under, as a closed set.
 *
 * Three properties are deliberate:
 *
 *  * **An SPDX identifier and a license *text* are not 1:1.** `GPL-2.0-only` and
 *    `GPL-2.0-or-later` are different *grants* over the identical document, so both
 *    point at the same [assetName]. That is why the catalog is keyed on this type
 *    rather than on a filename: keying on the file would force BusyBox (which states
 *    GPL version 2 is the *only* version it may be distributed under) and PRoot
 *    (whose headers say "either version 2 … or (at your option) any later version")
 *    to claim the same grant, and that claim would be wrong for one of them.
 *  * **[assetName] is nullable, and null is a real answer.** A component in the
 *    public domain (libselinux's Android fork, XZ for Java 1.9) has no license
 *    document to reproduce; inventing one, or filing it under the nearest permissive
 *    license, would misstate its terms. Those render as a statement instead of a link.
 *  * **[displayName] is what the user reads, [spdxId] is what a machine reads.** They
 *    are separate because "GNU General Public License v2.0" is the name on the
 *    document while `GPL-2.0-only` is the identifier that pins the grant.
 *
 * The texts live under `assets/licenses/` and are read at display time
 * ([com.fem.linexa.ui.screens.about.AboutViewModel]) rather than being compiled into
 * string resources: they are up to ~40 KB each, they are verbatim documents that must
 * not be translated or reflowed, and a `<string>` would have to escape them.
 */
enum class LicenseRef(
    val spdxId: String,
    val displayName: String,
    val assetName: String?
) {
    APACHE_2_0("Apache-2.0", "Apache License 2.0", "Apache-2.0.txt"),
    BSD_2_CLAUSE("BSD-2-Clause", "BSD 2-Clause License", "BSD-2-Clause.txt"),
    BSD_3_CLAUSE("BSD-3-Clause", "BSD 3-Clause License", "BSD-3-Clause.txt"),
    BSD_3_CLAUSE_WITH_PCRE2_EXCEPTION(
        "BSD-3-Clause WITH PCRE2-exception",
        "BSD 3-Clause License with PCRE2 exception",
        "BSD-3-Clause-WITH-PCRE2-exception.txt"
    ),

    // Same document, two grants — see the class doc.
    GPL_2_0_ONLY("GPL-2.0-only", "GNU General Public License v2.0 only", "GPL-2.0-or-later.txt"),
    GPL_2_0_OR_LATER(
        "GPL-2.0-or-later",
        "GNU General Public License v2.0 or later",
        "GPL-2.0-or-later.txt"
    ),
    GPL_3_0_OR_LATER(
        "GPL-3.0-or-later",
        "GNU General Public License v3.0 or later",
        "GPL-3.0-or-later.txt"
    ),

    CC0_1_0("CC0-1.0", "Creative Commons Zero v1.0 Universal", "CC0-1.0.txt"),
    ISC("ISC", "ISC License", "ISC.txt"),
    LGPL_2_1_OR_LATER(
        "LGPL-2.1-or-later",
        "GNU Lesser General Public License v2.1 or later",
        "LGPL-2.1-or-later.txt"
    ),
    LGPL_3_0_OR_LATER(
        "LGPL-3.0-or-later",
        "GNU Lesser General Public License v3.0 or later",
        "LGPL-3.0-or-later.txt"
    ),
    MIT("MIT", "MIT License", "MIT.txt"),
    ZERO_BSD("0BSD", "BSD Zero Clause License", "0BSD.txt"),
    ZLIB("Zlib", "zlib License", "Zlib.txt"),

    /** No document to reproduce. Rendered as a statement, never as a link. */
    PUBLIC_DOMAIN("LicenseRef-public-domain", "Public domain", null);

    /**
     * True for the licenses whose conditions extend past "reproduce this notice" —
     * i.e. the ones that require the corresponding source of the binary being
     * conveyed. [AttributionCatalog] refuses a copyleft component that names no
     * source, which is the one obligation an attribution screen most often misses.
     */
    val isCopyleft: Boolean
        get() = when (this) {
            GPL_2_0_ONLY, GPL_2_0_OR_LATER, GPL_3_0_OR_LATER,
            LGPL_2_1_OR_LATER, LGPL_3_0_OR_LATER -> true

            APACHE_2_0, BSD_2_CLAUSE, BSD_3_CLAUSE, BSD_3_CLAUSE_WITH_PCRE2_EXCEPTION,
            CC0_1_0, ISC, MIT, ZERO_BSD, ZLIB, PUBLIC_DOMAIN -> false
        }

    /** The path the text is bundled at, or null when there is no document. */
    val assetPath: String?
        get() = assetName?.let { "$LICENSE_ASSET_DIR/$it" }

    companion object {
        /** Directory under `app/src/main/assets/` holding the verbatim texts. */
        const val LICENSE_ASSET_DIR = "licenses"

        /**
         * Total lookup by [spdxId]; null for anything unknown.
         *
         * Total rather than throwing because the only caller is a navigation
         * argument — a stale back stack (or a deep link) must not be able to crash
         * the app on a string it does not recognise.
         */
        fun fromSpdxId(id: String?): LicenseRef? =
            entries.firstOrNull { it.spdxId == id }
    }
}
