package com.fem.linexa.service.rootfs.profile

import com.fem.linexa.service.installer.RootFsProbe
import com.fem.linexa.service.rootfs.ArchiveEntryPath

/**
 * §1.146 — accumulates [ArchiveSignals] during the single tar walk the scan
 * pass already performs.
 *
 * ## Why a collector object
 *
 * The pre-§1.146 scan loop held six mutable locals (`osRelease`,
 * `alpineRelease`, `archRelease`, `debianVersion`, `fedoraRelease`,
 * `archHints`) and a `when (lowerName)` over hardcoded literals. Every new
 * distribution meant another local, another branch, and another argument
 * threaded through two data classes. Worse, the loop was the only place that
 * knew which paths mattered, so nothing could test "does Alpine's layout get
 * detected" without running the whole installer.
 *
 * This class owns that state, derives *which* paths matter from the loaded
 * profiles, and is a plain JVM object — so detection for a real archive can be
 * tested by feeding it that archive's entry names and bytes, with no Android,
 * no `ContentResolver` and no staging directory.
 *
 * ## Root-prefix awareness (§1.147)
 *
 * An archive may wrap the rootfs in one top-level directory (`rootfs/etc/...`).
 * The collector cannot know whether that directory is a wrapper until the walk
 * finishes, so it records both interpretations:
 *
 *  - an **exact** hit (`etc/os-release`) always counts;
 *  - a **nested** hit (`rootfs/etc/os-release`, exactly one extra leading
 *    segment) is retained separately and only folded in when the caller passes
 *    the resolved strip count to [build].
 *
 * That keeps the decision after the walk — where the whole archive's shape is
 * known — instead of guessing from the first member. A mixed archive whose
 * root-level `etc/` and wrapper `rootfs/` both exist therefore resolves to the
 * exact paths, never to a nested duplicate.
 *
 * ## What it deliberately does not do
 *
 * It performs no safety checks. The scan pass runs
 * [com.fem.linexa.core.security.ArchiveValidator.checkEntry] on the **raw**
 * entry name *before* consulting this collector, and that ordering is
 * load-bearing: normalization must never happen upstream of validation, or
 * `./../etc/passwd` would reach the validator already laundered. See
 * [ArchiveEntryPath]'s kdoc.
 */
class ArchiveSignalCollector(profiles: List<RootFsProfile>) {

    /** Text markers to capture, normalized. */
    private val watchedMarkers: Set<String> = RootFsProfileResolver.watchedMarkerPaths(profiles)

    /** ELF binaries to sniff, normalized. */
    private val watchedElf: Set<String> = RootFsProfileResolver.watchedElfPaths(profiles)

    /** Entry-name fragment → architecture label, merged across profiles. */
    private val archHints: Map<String, String> = RootFsProfileResolver.watchedArchHints(profiles)

    // Exact-root observations. Canonical watched path → value.
    private val markerTexts = LinkedHashMap<String, String>()
    private val presentPaths = LinkedHashSet<String>()
    private val elfArchitectures = LinkedHashMap<String, RootFsProbe.RootFsArch>()

    // Nested-by-one observations (a single wrapper directory). Same key space
    // as above; merged into the exact maps by build(stripComponents = 1).
    private val nestedMarkerTexts = LinkedHashMap<String, String>()
    private val nestedPresentPaths = LinkedHashSet<String>()
    private val nestedElfArchitectures = LinkedHashMap<String, RootFsProbe.RootFsArch>()

    private val hintLabels = LinkedHashSet<String>()

    /**
     * Small OCI JSON documents, normalized path → bytes.
     *
     * Bounded three ways, because this is the only unbounded-looking buffer in
     * the scan: each entry must be under [OciImageLayout.MAX_JSON_BYTES], the
     * total is capped by [maxOciBlobBytes], and only paths under
     * [OciImageLayout.BLOB_PREFIX] plus `index.json` are eligible. A crafted
     * archive with thousands of tiny blobs therefore costs bounded memory and
     * then stops being collected.
     */
    private val ociBlobs = LinkedHashMap<String, ByteArray>()
    private val nestedOciBlobs = LinkedHashMap<String, ByteArray>()
    private var ociBlobBytes = 0L
    private var nestedOciBlobBytes = 0L
    private val maxOciBlobBytes = 8L * 1024 * 1024

    /**
     * What the caller should do with the current entry's data.
     *
     * Returned instead of having this class read the stream itself, because only
     * the caller knows how to read a tar entry without desynchronizing the
     * archive — and because "who drains the entry" is exactly the kind of
     * invariant that breaks when two objects share a stream.
     */
    enum class Action {
        /** Read as UTF-8 text and pass to [recordMarkerText]. */
        CAPTURE_TEXT,

        /** Read the first [ElfArchitecture.HEADER_BYTES] and pass to [recordElfHeader]. */
        CAPTURE_ELF,

        /** Read fully and pass to [recordBlob] — a small OCI JSON document. */
        CAPTURE_BLOB,

        /** Nothing to collect; drain it. */
        SKIP
    }

    /**
     * Records name-derived signals for [rawEntryName] and says whether its data
     * is wanted.
     *
     * @param isSymbolicLink a symlink entry carries no data, so it can supply
     *   *presence* but never content. This is why Fedora's profile lists
     *   `usr/lib/os-release` alongside `etc/os-release`: in the official image
     *   the `etc/` spelling is a symlink, and listing only that would capture
     *   nothing at all.
     * @param isDirectory directories carry no data.
     * @param size the entry's declared size, used only to decide whether an OCI
     *   blob is small enough to be a JSON document. Negative means unknown.
     */
    fun inspectEntry(
        rawEntryName: String,
        isSymbolicLink: Boolean,
        isDirectory: Boolean,
        size: Long = -1L
    ): Action {
        val normalized = ArchiveEntryPath.normalize(rawEntryName)
        if (normalized.isEmpty()) return Action.SKIP

        RootFsProfileResolver.matchArchHint(normalized, archHints)?.let { hintLabels += it }

        val exact = matches(normalized)
        val nested = if (exact.isNotEmpty()) emptySet() else matches(nestedPath(normalized))

        if (exact.isNotEmpty()) presentPaths += normalized
        if (nested.isNotEmpty()) {
            nestedPresentPaths += nestedPath(normalized)
        }

        if (isDirectory || isSymbolicLink) return Action.SKIP

        return when {
            normalized in watchedMarkers && normalized !in markerTexts -> Action.CAPTURE_TEXT
            normalized in watchedElf && normalized !in elfArchitectures -> Action.CAPTURE_ELF
            normalized == OciImageLayout.INDEX_JSON && withinJsonBudget(size) -> Action.CAPTURE_BLOB
            normalized.startsWith(OciImageLayout.BLOB_PREFIX) &&
                normalized !in ociBlobs && withinJsonBudget(size) -> Action.CAPTURE_BLOB

            // Nested-by-one variants: rootfs/os-release-style wrappers.
            nested.isNotEmpty() && nestedPath(normalized) in watchedMarkers &&
                nestedPath(normalized) !in nestedMarkerTexts -> Action.CAPTURE_TEXT

            nested.isNotEmpty() && nestedPath(normalized) in watchedElf &&
                nestedPath(normalized) !in nestedElfArchitectures -> Action.CAPTURE_ELF

            nestedPath(normalized) == OciImageLayout.INDEX_JSON &&
                withinNestedJsonBudget(size) -> Action.CAPTURE_BLOB

            nestedPath(normalized).startsWith(OciImageLayout.BLOB_PREFIX) &&
                nestedPath(normalized) !in nestedOciBlobs &&
                withinNestedJsonBudget(size) -> Action.CAPTURE_BLOB

            else -> Action.SKIP
        }
    }

    /** Whether [path] is an OCI document or a nominated marker/ELF path. */
    private fun matches(path: String): Set<String> {
        val out = LinkedHashSet<String>(3)
        if (path in watchedMarkers) out += path
        if (path in watchedElf) out += path
        if (path == OciImageLayout.LAYOUT_MARKER || path == OciImageLayout.INDEX_JSON) out += path
        if (path.startsWith(OciImageLayout.BLOB_PREFIX)) out += path
        return out
    }

    /** Strips exactly one leading component, or returns the input unchanged. */
    private fun nestedPath(normalized: String): String =
        normalized.substringAfter('/', normalized)

    private fun withinJsonBudget(size: Long): Boolean =
        size in 0..OciImageLayout.MAX_JSON_BYTES.toLong() &&
            ociBlobBytes + size <= maxOciBlobBytes

    private fun withinNestedJsonBudget(size: Long): Boolean =
        size in 0..OciImageLayout.MAX_JSON_BYTES.toLong() &&
            nestedOciBlobBytes + size <= maxOciBlobBytes

    /** Stores a small OCI JSON document. Over-budget bytes are dropped. */
    fun recordBlob(rawEntryName: String, bytes: ByteArray) {
        val normalized = ArchiveEntryPath.normalize(rawEntryName)
        if (normalized.isEmpty() || bytes.size > OciImageLayout.MAX_JSON_BYTES) return
        if (normalized == OciImageLayout.INDEX_JSON || normalized.startsWith(OciImageLayout.BLOB_PREFIX)) {
            if (ociBlobBytes + bytes.size <= maxOciBlobBytes) {
                if (ociBlobs.put(normalized, bytes) == null) ociBlobBytes += bytes.size
            }
            presentPaths += normalized
            return
        }
        val nested = nestedPath(normalized)
        if (nested == OciImageLayout.INDEX_JSON || nested.startsWith(OciImageLayout.BLOB_PREFIX)) {
            if (nestedOciBlobBytes + bytes.size <= maxOciBlobBytes) {
                if (nestedOciBlobs.put(nested, bytes) == null) nestedOciBlobBytes += bytes.size
            }
            nestedPresentPaths += nested
        }
    }

    /** Stores the text of a marker file. Blank text is discarded as no signal. */
    fun recordMarkerText(rawEntryName: String, text: String?) {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return
        val normalized = ArchiveEntryPath.normalize(rawEntryName)
        if (normalized in watchedMarkers) {
            markerTexts[normalized] = trimmed
            return
        }
        val nested = nestedPath(normalized)
        if (nested in watchedMarkers) nestedMarkerTexts[nested] = trimmed
    }

    /**
     * Stores the architecture read from an ELF header, if the bytes are one.
     *
     * A non-ELF file (very often `/bin/sh` as a shell script) contributes
     * nothing rather than a wrong answer — see [ElfArchitecture.fromHeader].
     */
    fun recordElfHeader(rawEntryName: String, header: ByteArray, length: Int = header.size) {
        val arch = ElfArchitecture.fromHeader(header, length) ?: return
        val normalized = ArchiveEntryPath.normalize(rawEntryName)
        if (normalized in watchedElf) {
            elfArchitectures[normalized] = arch
            return
        }
        val nested = nestedPath(normalized)
        if (nested in watchedElf) nestedElfArchitectures[nested] = arch
    }

    /** Records that a path exists without capturing anything (OCI blobs, markers). */
    fun recordPresence(rawEntryName: String) {
        val normalized = ArchiveEntryPath.normalize(rawEntryName)
        if (normalized.isEmpty()) return
        if (matches(normalized).any()) presentPaths += normalized
        val nested = nestedPath(normalized)
        if (matches(nested).any()) nestedPresentPaths += nested
    }

    /**
     * True when the archive carries any profile-nominated identity signal —
     * a marker file or its contents — under the same root interpretation
     * [build] uses.
     *
     * The analyzer uses this to decide whether an archive is already the
     * rootfs or is merely a container around one. Only nominated paths count:
     * an arbitrary `README` must not suppress a nested-payload search, and an
     * `etc/os-release` buried in a documentation archive must not be mistaken
     * for the image's own identity (it is only recorded at the exact root or
     * exactly one wrapper below it — see [inspectEntry]).
     */
    fun hasIdentitySignal(stripComponents: Int = 0): Boolean {
        val markers = if (stripComponents > 0) markerTexts + nestedMarkerTexts else markerTexts
        if (markers.keys.any { it in watchedMarkers }) return true
        val present = if (stripComponents > 0) presentPaths + nestedPresentPaths else presentPaths
        return present.any { it in watchedMarkers }
    }

    /** True when this archive looks like an OCI image layout rather than a rootfs. */
    fun looksLikeOciLayout(): Boolean = OciImageLayout.looksLikeLayout(presentPaths)

    /**
     * Resolves which blob is the rootfs layer, using only what this scan already
     * captured.
     *
     * @param stripComponents 1 when the layout sits under a single wrapper
     *   directory (`image/oci-layout`), 0 otherwise. The caller resolves this
     *   from [com.fem.linexa.service.rootfs.ArchiveRootDiscovery].
     */
    fun resolveOciLayer(stripComponents: Int = 0): OciImageLayout.Result {
        val paths = if (stripComponents > 0) nestedPresentPaths else presentPaths
        if (!OciImageLayout.looksLikeLayout(paths)) return OciImageLayout.Result.NotOciLayout
        val blobs = if (stripComponents > 0) nestedOciBlobs else ociBlobs
        val index = blobs[OciImageLayout.INDEX_JSON]
            ?: return OciImageLayout.Result.Invalid(
                "index.json was not captured (larger than ${OciImageLayout.MAX_JSON_BYTES} bytes?)"
            )
        return OciImageLayout.resolve(index, blobs)
    }

    /**
     * @param stripComponents 1 when the archive is wrapped in a single top-level
     *   directory, so the collector's nested-by-one observations describe the
     *   real rootfs. Exact observations always win.
     */
    fun build(embedded: RootFsProfile? = null, stripComponents: Int = 0): ArchiveSignals {
        val markers = LinkedHashMap(markerTexts)
        val present = LinkedHashSet(presentPaths)
        val elf = LinkedHashMap(elfArchitectures)
        if (stripComponents > 0) {
            nestedMarkerTexts.forEach { (k, v) -> markers.putIfAbsent(k, v) }
            present += nestedPresentPaths
            nestedElfArchitectures.forEach { (k, v) -> elf.putIfAbsent(k, v) }
        }
        return ArchiveSignals(
            markerTexts = markers,
            presentPaths = present,
            archHintLabels = LinkedHashSet(hintLabels),
            elfArchitectures = elf,
            embedded = embedded
        )
    }
}
