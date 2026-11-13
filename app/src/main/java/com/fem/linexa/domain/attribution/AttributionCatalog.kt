package com.fem.linexa.domain.attribution

/**
 * §1.130 — what kind of thing a [AttributionComponent] is, and therefore *why* Linexa
 * has an obligation for it.
 *
 * This is not decoration for the UI's benefit. The obligation differs by kind in a way
 * that decides whether a component belongs in the catalog at all:
 *
 *  * [BUNDLED_BINARY] and [BUNDLED_LIBRARY] are **executables and shared objects
 *    shipped verbatim** in `jniLibs/` (see `jniLibs/BINARY-MANIFEST.md`). Linexa did
 *    not compile them; it conveys somebody else's build. For the copyleft ones this is
 *    the strongest form of the obligation — a binary conveyed without its
 *    corresponding source is the classic violation.
 *  * [APP_DEPENDENCY] is code linked into the APK by Gradle.
 *
 * A **test-scope** dependency has no kind, deliberately: attribution follows
 * *distribution*, and JUnit, Mockito, `kotlinx-coroutines-test` and the JVM `zstd-jni`
 * jar are never in the APK. Listing them would claim an obligation Linexa does not
 * have and, worse, would make the list unfalsifiable — a reader could no longer tell
 * which entries correspond to bytes on their device. That exclusion is asserted, not
 * just documented (see `AttributionCatalogTest`).
 */
enum class AttributionKind {
    /** An executable in `jniLibs/`, run as a host process (proot, busybox, tar, …). */
    BUNDLED_BINARY,

    /** A shared object in `jniLibs/` that a bundled binary links against. */
    BUNDLED_LIBRARY,

    /**
     * A Maven/JitPack coordinate compiled or packaged into the APK.
     */
    APP_DEPENDENCY,

    /** Vector artwork shipped as resource drawables, derived from an icon pack. */
    BUNDLED_ICON
}

/**
 * One upstream project Linexa ships code from.
 *
 * **One entry per upstream project, not per file.** PRoot contributes two artifacts
 * (`libproot.so` and the tracee-side `lib__bin__loader__.so`, §1.77) and BusyBox
 * contributes two (`bin/busybox` plus the `libbusybox.so.1.38.0` that holds the actual
 * applets), but each is one project under one license with one source. Splitting them
 * per file would multiply the same notice and make the list read as if Linexa shipped
 * more distinct projects than it does.
 *
 * @param id stable identifier, used as a map/test key and nothing else. Never shown.
 * @param name what the project calls itself.
 * @param version the version actually shipped, or null when the coordinate is not
 *   version-pinned in a way worth showing. Kept as a string because these are not
 *   comparable across ecosystems (`5.1.107.89`, `3.7b`, `48327189ab`).
 * @param licenses every license the shipped part is under. A **list**, because some
 *   components genuinely are: `libandroid-support` carries Apache-2.0 code plus an
 *   MIT `wcwidth`, and collapsing that to one license would misstate the terms of
 *   whichever half lost. Must be non-empty.
 * @param sourceUrl where the corresponding source is. **Required** for a copyleft
 *   component — see the [init] block.
 * @param note anything a reader needs in order to read the entry honestly (a dual
 *   license selection, or the fact that a bundled binary is currently unused).
 */
data class AttributionComponent(
    val id: String,
    val name: String,
    val version: String?,
    val licenses: List<LicenseRef>,
    val sourceUrl: String?,
    val kind: AttributionKind,
    val note: String? = null
) {

    init {
        require(licenses.isNotEmpty()) {
            "$id names no license: an attribution entry with no license states nothing"
        }
        // Fail loudly rather than filtering the entry out. A copyleft component whose
        // source is not named is an unmet distribution obligation, and the failure mode
        // of "quietly render the other entries" is exactly the one that ships a
        // violation — the screen would look complete. Same posture as §1.71's
        // bootstrap-manifest drift check, and the catalog is a compile-time constant
        // exercised by a JVM test, so a mistake fails in CI, not on a device.
        require(!(licenses.any { it.isCopyleft } && sourceUrl.isNullOrBlank())) {
            "$id is copyleft and names no source; a conveyed binary needs its " +
                "corresponding source"
        }
    }

    /** True when this is bytes vendored into the repo rather than a resolved coordinate. */
    val isVendoredBinary: Boolean
        get() = kind == AttributionKind.BUNDLED_BINARY || kind == AttributionKind.BUNDLED_LIBRARY
}

/**
 * §1.130 — every third-party project whose code Linexa distributes, with the license
 * it is under and where its source is.
 *
 * **Why this is a hand-written Kotlin constant and not a generated report.** The usual
 * approach is a Gradle plugin that walks the dependency graph at build time. That
 * cannot work here and would be actively misleading if it did: the majority of the
 * third-party code Linexa ships is not in the dependency graph at all. It is
 * 20 prebuilt binaries and shared objects vendored into `jniLibs/` from the
 * termux-packages lineage (§1.26/§1.77), which no dependency resolver can see. A
 * generated list would confidently show the ten Maven coordinates and silently omit
 * PRoot, BusyBox and GNU tar — i.e. omit precisely the copyleft components whose
 * obligations are the strongest. It would also require a new plugin coordinate, which
 * the offline build cannot resolve (§1.124(b)).
 *
 * The cost of hand-writing it is that it can drift from what is actually shipped. That
 * is paid down where it can be: `AttributionCatalogTest` pins every vendored entry
 * against the same set `jniLibs/BINARY-MANIFEST.md` records and every dependency entry
 * against the coordinates in `app/build.gradle.kts`, so adding a binary or a
 * dependency without an attribution entry fails the build.
 *
 * Every license below was checked against the upstream project rather than copied from
 * a summary, and two of them do not match what a summary says:
 *
 *  * **Zstandard is dual-licensed** (BSD-3-Clause *or* GPL-2.0, "you may select, at
 *    your option, one"). termux's package metadata records `GPL-2.0`; the selection is
 *    ours to make and BSD-3-Clause is the one recorded here, because taking the GPL
 *    option for a component that does not need it would import a copyleft obligation
 *    for nothing.
 *  * **BusyBox is `GPL-2.0-only`, and PRoot is `GPL-2.0-or-later`** — the same license
 *    document, two different grants. BusyBox's own LICENSE states version 2 "is the
 *    only version of this license which this version of BusyBox … may be distributed
 *    under"; PRoot's source headers say "either version 2 … or (at your option) any
 *    later version". This is the distinction [LicenseRef] exists to keep.
 */
object AttributionCatalog {

    /**
     * The vendored native runtime — `jniLibs/`, one entry per upstream project.
     *
     * Versions are the ones in `jniLibs/BINARY-MANIFEST.md`, which is the pinning
     * record §1.25(b) requires and the file CI already checks against the committed
     * bytes (§1.71). Where a project contributes several files, the [AttributionComponent.note]
     * says so.
     */
    private val bundled: List<AttributionComponent> = listOf(
        AttributionComponent(
            id = "proot",
            name = "PRoot",
            version = "5.1.107.89+linexa",
            licenses = listOf(LicenseRef.GPL_2_0_OR_LATER),
            sourceUrl = "https://github.com/termux/proot",
            kind = AttributionKind.BUNDLED_BINARY,
            note = "termux fork, rebuilt with Linexa patches 0001–0004. Ships as " +
                "libproot.so plus its tracee-side loader; both artifacts come from " +
                "the same build. Linexa's entire runtime is this program. " +
                "Copyright (C) STMicroelectronics and the PRoot contributors."
        ),
        AttributionComponent(
            id = "busybox",
            name = "BusyBox",
            version = "1.38.0",
            licenses = listOf(LicenseRef.GPL_2_0_ONLY),
            sourceUrl = "https://git.busybox.net/busybox/",
            kind = AttributionKind.BUNDLED_BINARY,
            note = "Version 2 of the GPL is the only version BusyBox may be " +
                "distributed under. The applets live in libbusybox.so.1.38.0; the " +
                "bin/busybox file is a launcher for it. Copyright (C) 1998-2024 " +
                "Erik Andersen, Rob Landley, Denys Vlasenko and contributors."
        ),
        AttributionComponent(
            id = "gnu-tar",
            name = "GNU tar",
            version = "1.35",
            licenses = listOf(LicenseRef.GPL_3_0_OR_LATER),
            sourceUrl = "https://www.gnu.org/software/tar/",
            kind = AttributionKind.BUNDLED_BINARY,
            note = "Extracts every imported root filesystem, run inside PRoot. " +
                "Copyright (C) 1988-2024 Free Software Foundation, Inc."
        ),
        AttributionComponent(
            id = "zstd",
            name = "Zstandard",
            version = "1.5.7",
            licenses = listOf(LicenseRef.BSD_3_CLAUSE),
            sourceUrl = "https://github.com/facebook/zstd",
            kind = AttributionKind.BUNDLED_BINARY,
            note = "Dual-licensed BSD-3-Clause or GPL-2.0 at the recipient's option; " +
                "Linexa selects BSD-3-Clause. Ships as the zstd tool and libzstd. " +
                "Copyright (c) Meta Platforms, Inc. and affiliates."
        ),
        AttributionComponent(
            id = "tmux",
            name = "tmux",
            version = "3.7b",
            licenses = listOf(LicenseRef.ISC),
            sourceUrl = "https://github.com/tmux/tmux",
            kind = AttributionKind.BUNDLED_BINARY,
            note = "Bundled but NOT on the terminal launch path. It is still in the " +
                "APK, so it is still attributed — being unused is not a reason to " +
                "omit code the user receives. Copyright (c) Various Authors."
        ),
        AttributionComponent(
            id = "libacl",
            name = "acl (libacl)",
            version = "2.4.0",
            licenses = listOf(LicenseRef.LGPL_2_1_OR_LATER),
            sourceUrl = "https://savannah.nongnu.org/projects/acl/",
            kind = AttributionKind.BUNDLED_LIBRARY,
            note = "Linked by GNU tar. The library is LGPL-2.1-or-later; only acl's " +
                "command-line tools, which Linexa does not ship, are GPL."
        ),
        AttributionComponent(
            id = "libandroid-glob",
            name = "libandroid-glob",
            version = "0.6",
            licenses = listOf(LicenseRef.BSD_3_CLAUSE),
            sourceUrl = "https://github.com/termux/libandroid-glob",
            kind = AttributionKind.BUNDLED_LIBRARY,
            note = "Supplies glob(3), which Android's bionic omits."
        ),
        AttributionComponent(
            id = "libandroid-selinux",
            name = "libandroid-selinux",
            version = "14.0.0.11",
            licenses = listOf(LicenseRef.PUBLIC_DOMAIN),
            sourceUrl = "https://github.com/termux/termux-packages/tree/master/packages/libandroid-selinux",
            kind = AttributionKind.BUNDLED_LIBRARY,
            note = "Android's libselinux fork. Public domain, so there is no license " +
                "text to reproduce — filing it under the nearest permissive license " +
                "would misstate its terms."
        ),
        AttributionComponent(
            id = "libandroid-support",
            name = "libandroid-support",
            version = "29",
            licenses = listOf(LicenseRef.APACHE_2_0, LicenseRef.MIT),
            sourceUrl = "https://github.com/termux/termux-packages/tree/master/packages/libandroid-support",
            kind = AttributionKind.BUNDLED_LIBRARY,
            note = "Apache-2.0 support code plus an MIT wcwidth implementation — " +
                "genuinely two licenses, not a choice between them."
        ),
        AttributionComponent(
            id = "libiconv",
            name = "GNU libiconv",
            version = "1.18",
            licenses = listOf(LicenseRef.LGPL_2_1_OR_LATER),
            sourceUrl = "https://www.gnu.org/software/libiconv/",
            kind = AttributionKind.BUNDLED_LIBRARY,
            note = "The shipped part is the library, which is LGPL-2.1-or-later; the " +
                "GPL applies to the iconv program, which Linexa does not ship."
        ),
        AttributionComponent(
            id = "pcre2",
            name = "PCRE2",
            version = "10.47",
            licenses = listOf(LicenseRef.BSD_3_CLAUSE_WITH_PCRE2_EXCEPTION),
            sourceUrl = "https://github.com/PCRE2Project/pcre2",
            kind = AttributionKind.BUNDLED_LIBRARY,
            note = "Distributed under BSD-3-Clause with the PCRE2 binary-linking " +
                "exception. Copyright (c) 1997-2024 University of Cambridge, " +
                "Philip Hazel and contributors."
        ),
        AttributionComponent(
            id = "zlib",
            name = "zlib",
            version = "1.3.2",
            licenses = listOf(LicenseRef.ZLIB),
            sourceUrl = "https://zlib.net/",
            kind = AttributionKind.BUNDLED_LIBRARY
        ),
        AttributionComponent(
            id = "liblzma",
            name = "liblzma (XZ Utils)",
            version = "5.8.3",
            licenses = listOf(LicenseRef.ZERO_BSD),
            sourceUrl = "https://github.com/tukaani-project/xz",
            kind = AttributionKind.BUNDLED_LIBRARY,
            note = "XZ Utils is a mixed-license package; the part Linexa ships is " +
                "liblzma, which is 0BSD. Its GPL'd scripts are not included."
        ),
        AttributionComponent(
            id = "ncurses",
            name = "ncurses",
            version = "6.6",
            licenses = listOf(LicenseRef.MIT),
            sourceUrl = "https://invisible-island.net/ncurses/",
            kind = AttributionKind.BUNDLED_LIBRARY,
            note = "Also the origin of the compiled terminfo entries in " +
                "assets/terminfo (§1.75) — those are ncurses data files, not Linexa's."
        ),
        AttributionComponent(
            id = "libevent",
            name = "libevent",
            version = "2.1.13",
            licenses = listOf(LicenseRef.BSD_3_CLAUSE),
            sourceUrl = "https://libevent.org/",
            kind = AttributionKind.BUNDLED_LIBRARY,
            note = "Linked by tmux."
        ),
        AttributionComponent(
            id = "libtalloc",
            name = "talloc",
            version = "2.4.3",
            licenses = listOf(LicenseRef.LGPL_3_0_OR_LATER),
            sourceUrl = "https://talloc.samba.org/",
            kind = AttributionKind.BUNDLED_LIBRARY,
            note = "Required by the dynamically-linked PRoot build (§1.77). The " +
                "library is LGPL-3.0-or-later even though the wider Samba tree is not."
        ),
        AttributionComponent(
            id = "libandroid-shmem",
            name = "libandroid-shmem",
            version = "0.7",
            licenses = listOf(LicenseRef.BSD_3_CLAUSE),
            sourceUrl = "https://github.com/termux/libandroid-shmem",
            kind = AttributionKind.BUNDLED_LIBRARY,
            note = "Required by the dynamically-linked PRoot build (§1.77)."
        )
    )

    /**
     * Code Gradle links into the APK.
     *
     * Grouped by upstream project rather than by artifact for the same reason as
     * [bundled]: a reader gains nothing from thirty AndroidX rows that all say
     * Apache-2.0 and all point at the same source.
     */
    private val dependencies: List<AttributionComponent> = listOf(
        AttributionComponent(
            id = "termux-terminal-emulator",
            name = "Termux terminal-emulator (vendored)",
            version = "v0.118.3 (5b657c6adf4304e5198951ce815fe0205dcac29c)",
            licenses = listOf(LicenseRef.APACHE_2_0),
            sourceUrl = "https://github.com/termux/termux-app",
            kind = AttributionKind.APP_DEPENDENCY,
            note = "The terminal emulator, PTY backend and JNI (com.termux.terminal.*), " +
                "vendored verbatim from termux/termux-app tag v0.118.3 into the " +
                ":terminal-emulator module — see that module's PROVENANCE.md and NOTICE. " +
                "The upstream repository is GPLv3, but its LICENSE.md names this library " +
                "explicitly as an Apache-2.0 exception (the jackpal Android-Terminal-Emulator " +
                "lineage); its only dependency is androidx.annotation. Copyright (c) " +
                "2012-2024 the Termux project contributors and the Jackpal " +
                "Android-Terminal-Emulator lineage."
        ),
        AttributionComponent(
            id = "termux-terminal-view",
            name = "Termux terminal-view (vendored)",
            version = "v0.118.3 (5b657c6adf4304e5198951ce815fe0205dcac29c)",
            licenses = listOf(LicenseRef.APACHE_2_0),
            sourceUrl = "https://github.com/termux/termux-app",
            kind = AttributionKind.APP_DEPENDENCY,
            note = "The terminal rendering View and its text-selection UI " +
                "(com.termux.view.*), vendored verbatim from the same termux/termux-app " +
                "tag into the :terminal-view module — see that module's PROVENANCE.md and " +
                "NOTICE. Named by the same Apache-2.0 exception in the upstream LICENSE.md " +
                "as its terminal-emulator sibling; depends only on terminal-emulator and " +
                "androidx.annotation. Copyright (c) 2012-2024 the Termux project " +
                "contributors and the Jackpal Android-Terminal-Emulator lineage."
        ),
        AttributionComponent(
            id = "zstd-jni",
            name = "zstd-jni",
            version = "1.5.6-5",
            licenses = listOf(LicenseRef.BSD_2_CLAUSE),
            sourceUrl = "https://github.com/luben/zstd-jni",
            kind = AttributionKind.APP_DEPENDENCY,
            note = "The JNI wrapper is BSD-2-Clause; the Zstandard C code it embeds " +
                "is covered by the Zstandard entry above."
        ),
        AttributionComponent(
            id = "commons-compress",
            name = "Apache Commons Compress",
            version = "1.26.1",
            licenses = listOf(LicenseRef.APACHE_2_0),
            sourceUrl = "https://commons.apache.org/proper/commons-compress/",
            kind = AttributionKind.APP_DEPENDENCY,
            note = "Archive validation before anything is written to disk (§1.14)."
        ),
        AttributionComponent(
            id = "snakeyaml",
            name = "SnakeYAML",
            version = "2.2",
            licenses = listOf(LicenseRef.APACHE_2_0),
            sourceUrl = "https://bitbucket.org/snakeyaml/snakeyaml/",
            kind = AttributionKind.APP_DEPENDENCY,
            note = "Parses config.yaml / linexa.yaml (§1.118)."
        ),
        AttributionComponent(
            id = "xz-java",
            name = "XZ for Java",
            version = "1.9",
            licenses = listOf(LicenseRef.PUBLIC_DOMAIN),
            sourceUrl = "https://tukaani.org/xz/java.html",
            kind = AttributionKind.APP_DEPENDENCY,
            note = "Public domain, so there is no license document to reproduce."
        ),
        AttributionComponent(
            id = "kotlin",
            name = "Kotlin Standard Library",
            version = "2.1.20",
            licenses = listOf(LicenseRef.APACHE_2_0),
            sourceUrl = "https://github.com/JetBrains/kotlin",
            kind = AttributionKind.APP_DEPENDENCY
        ),
        AttributionComponent(
            id = "kotlinx-coroutines",
            name = "kotlinx.coroutines",
            version = "1.8.1",
            licenses = listOf(LicenseRef.APACHE_2_0),
            sourceUrl = "https://github.com/Kotlin/kotlinx.coroutines",
            kind = AttributionKind.APP_DEPENDENCY
        ),
        AttributionComponent(
            id = "kotlinx-serialization",
            name = "kotlinx.serialization",
            version = "1.6.3",
            licenses = listOf(LicenseRef.APACHE_2_0),
            sourceUrl = "https://github.com/Kotlin/kotlinx.serialization",
            kind = AttributionKind.APP_DEPENDENCY
        ),
        AttributionComponent(
            id = "androidx",
            name = "AndroidX",
            version = null,
            licenses = listOf(LicenseRef.APACHE_2_0),
            sourceUrl = "https://github.com/androidx/androidx",
            kind = AttributionKind.APP_DEPENDENCY,
            note = "Core, Activity, Lifecycle, Navigation and Room. Versions are " +
                "pinned per artifact in app/build.gradle.kts."
        ),
        AttributionComponent(
            id = "compose",
            name = "Jetpack Compose and Material 3",
            version = "BOM 2024.06.00",
            licenses = listOf(LicenseRef.APACHE_2_0),
            sourceUrl = "https://github.com/androidx/androidx",
            kind = AttributionKind.APP_DEPENDENCY,
            note = "Every screen in Linexa, plus the Material icon set."
        ),
        AttributionComponent(
            id = "hilt",
            name = "Dagger and Hilt",
            version = "2.56.2",
            licenses = listOf(LicenseRef.APACHE_2_0),
            sourceUrl = "https://github.com/google/dagger",
            kind = AttributionKind.APP_DEPENDENCY
        )
    )

    /**
     * Icon artwork shipped as resource drawables.
     *
     * The `ic_custom_*` set is material-symbols path data extracted from the IconSvg
     * collection and baked into `res/drawable/`, so the glyphs Linexa draws are covered
     * by the icon pack's license, not merely by "the Material icon set" that Compose
     * bundles (§1.130). Every one of the 28 `ic_custom_*` drawables comes from here.
     *
     * The `ic_distro_*` set is the distribution's *own* logo artwork (official
     * brand marks via the IconSvg library's logos/ collection), with Devicon
     * (MIT) and `simple-icons` (CC0) supplying the monochrome silhouettes for
     * distributions that publish no multicolor mark. Distribution names and
     * logos are trademarks of their respective owners; Linexa renders them to
     * identify which distribution a workspace runs.
     */
    private val icons: List<AttributionComponent> = listOf(
        AttributionComponent(
            id = "material-symbols",
            name = "Material Symbols",
            version = null,
            licenses = listOf(LicenseRef.APACHE_2_0),
            sourceUrl = "https://github.com/google/material-design-icons",
            kind = AttributionKind.BUNDLED_ICON,
            note = "The 28 ic_custom_* drawables are derived from this pack's path " +
                "data; Apache-2.0 requires reproducing the license notice on " +
                "redistribution."
        ),
        AttributionComponent(
            id = "devicon",
            name = "Devicon",
            version = null,
            licenses = listOf(LicenseRef.MIT),
            sourceUrl = "https://github.com/devicons/devicon",
            kind = AttributionKind.BUNDLED_ICON,
            note = "Supplies the official-color CentOS, Kali Linux, NixOS and " +
                "openSUSE vectors among the ic_distro_* drawables. MIT — the " +
                "license notice is bundled."
        ),
        AttributionComponent(
            id = "simple-icons",
            name = "Simple Icons",
            version = null,
            licenses = listOf(LicenseRef.CC0_1_0),
            sourceUrl = "https://github.com/simple-icons/simple-icons",
            kind = AttributionKind.BUNDLED_ICON,
            note = "Supplies the single-color silhouettes for distributions " +
                "with no multicolor mark (Alpine, AlmaLinux, elementary, " +
                "EndeavourOS, Gentoo, MX Linux, Parrot Security, Pop!_OS, " +
                "Red Hat, Rocky Linux, Tails, Void Linux) among the ic_distro_* " +
                "drawables, recolored with each distro's official brand color. " +
                "Released under CC0-1.0; trademark rights are not granted."
        ),
        AttributionComponent(
            id = "distro-logos",
            name = "Distribution logos",
            version = null,
            licenses = listOf(LicenseRef.CC0_1_0),
            sourceUrl = "https://github.com/gilbarbara/logos",
            kind = AttributionKind.BUNDLED_ICON,
            note = "The official multicolor marks for Arch Linux, Debian, " +
                "Fedora, Linux Mint, Manjaro and Ubuntu among the ic_distro_* " +
                "drawables, via the IconSvg library's logos/ collection. " +
                "Released under CC0-1.0. Distribution names and logos are " +
                "trademarks of their respective owners, used only to identify " +
                "the distribution a workspace runs; CC0 does not grant " +
                "trademark rights."
        )
    )

    /**
     * Every component, bundled binaries first.
     *
     * Ordered deliberately: the vendored native runtime is what makes Linexa work and
     * carries the obligations that actually bind, so it is not buried under a wall of
     * Apache-2.0 Google libraries. Within each group the order is the one written
     * above (PRoot first, then the tools, then the libraries they link) rather than
     * alphabetical, which would open the list with `acl`.
     */
    val all: List<AttributionComponent> = bundled + dependencies + icons

    /** The components of one [kind], in [all]'s order. */
    fun byKind(kind: AttributionKind): List<AttributionComponent> =
        all.filter { it.kind == kind }

    /**
     * Every license any shipped component is under, in [LicenseRef] declaration order.
     *
     * This — not [LicenseRef.entries] — is what the license list should be built from:
     * an enum constant nothing references would otherwise appear as a license Linexa
     * is bound by. The test suite asserts the two sets agree, so an unused constant is
     * a build failure rather than a silent extra row.
     */
    val licenses: List<LicenseRef> =
        LicenseRef.entries.filter { ref -> all.any { ref in it.licenses } }

    /** Total lookup by [AttributionComponent.id]; null for anything unknown. */
    fun findById(id: String?): AttributionComponent? = all.firstOrNull { it.id == id }
}
