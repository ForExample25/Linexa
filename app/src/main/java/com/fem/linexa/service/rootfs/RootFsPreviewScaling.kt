package com.fem.linexa.service.rootfs

/**
 * §1.124 — the pure half of decoding a declared preview image: how much to
 * downsample it, and when to refuse to decode it at all.
 *
 * Deliberately free of Android types so it can be unit-tested on the JVM. The
 * decode itself needs `BitmapFactory`, which is an Android stub outside an
 * instrumented environment, so the arithmetic and the safety limits live here
 * and [com.fem.linexa.ui.components.RootFsPreviewLoader] does nothing but call
 * the framework with the numbers this object produced.
 *
 * Why a size limit exists at all: a preview is the only declaration that names
 * a **file inside a guest-writable rootfs** (§1.121), and the guest can replace
 * it at any point after install. [RootFsPreviewImage] guarantees the file is
 * *contained* and has an image extension, but containment says nothing about
 * its contents — a 40-byte PNG header can legitimately declare 60000×60000,
 * which `BitmapFactory` would try to allocate as ~14 GB and take the process
 * down with an OOM. That is a denial of service reachable from an installed
 * image's own rootfs, so the bounds are checked *before* the pixels are ever
 * read, and an over-limit image is simply "no preview" like every other §1.121
 * rejection.
 */
object RootFsPreviewScaling {

    /**
     * Largest source image this will decode, in pixels.
     *
     * 50 MP is far beyond any sane 52dp avatar source (a generous one is
     * 512×512 = 0.26 MP) and still comfortably under what a bounds-only check
     * can turn into an allocation: at ARGB_8888 the *full-size* bitmap would be
     * 200 MB, and this never decodes full size — the sample step below reduces
     * it to roughly the target before a single pixel is allocated. The limit
     * exists to reject the absurd, not to be a tight budget.
     */
    const val MAX_SOURCE_PIXELS: Long = 50_000_000L

    /**
     * True when an image whose header declares [width]×[height] is worth
     * handing to a decoder.
     *
     * False for non-positive dimensions — which is also how `BitmapFactory`
     * reports "these bytes are not an image I can read", so a `.png` that is
     * really a text file or a truncated download lands here rather than
     * throwing later.
     */
    fun isDecodable(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        return width.toLong() * height.toLong() <= MAX_SOURCE_PIXELS
    }

    /**
     * The `BitmapFactory.Options.inSampleSize` to use when drawing a
     * [sourceWidth]×[sourceHeight] image into a [targetPx]-square avatar.
     *
     * Returns the largest power of two that still leaves **both** dimensions at
     * or above [targetPx], so the decoded bitmap is never smaller than the tile
     * it fills (which would look soft after the crop) but is also never the
     * multi-megapixel original held in memory for a 52dp square. Powers of two
     * specifically because `BitmapFactory` rounds any other value down to one
     * anyway — computing a non-power-of-two here would silently disagree with
     * what the framework actually did.
     *
     * Always at least 1: a source already smaller than the target is decoded
     * as-is and upscaled at draw time, and 0 is not a legal sample size.
     */
    fun sampleSizeFor(sourceWidth: Int, sourceHeight: Int, targetPx: Int): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetPx <= 0) return 1
        var sample = 1
        while (
            sourceWidth / (sample * 2) >= targetPx &&
            sourceHeight / (sample * 2) >= targetPx
        ) {
            sample *= 2
        }
        return sample
    }

    /**
     * The identity a decoded preview may be cached against: path plus the two
     * cheap `stat` fields that change when the file is rewritten.
     *
     * The *resolution* of a preview is deliberately never cached (§1.121(b) —
     * the guest can plant a symlink after install), but once resolution has
     * succeeded on this call, re-decoding the same unchanged bytes on every
     * recomposition would be pure waste. Keying on content identity rather than
     * on the workspace id means a guest that legitimately replaces its own
     * preview is picked up, and it keeps two workspaces' previews distinct even
     * if they somehow share a path.
     */
    fun cacheKeyOf(absolutePath: String, lastModified: Long, length: Long): String =
        "$absolutePath|$lastModified|$length"
}
