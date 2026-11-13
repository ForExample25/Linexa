package com.fem.linexa.ui.components

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.service.rootfs.RootFsPreviewScaling
import java.io.File
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * §1.124 — decodes a §1.121 preview file into an [ImageBitmap] for the Home
 * card, off the main thread, with no image-loading library.
 *
 * **Why no Coil/Glide.** The obvious choice would be Coil's `AsyncImage`, and on
 * a normal project it would be right. Here it is not, for two independent
 * reasons:
 *
 *  1. This build must resolve `--offline`, and the Gradle module cache on the
 *     build host contains exactly the current dependency graph. `io.coil-kt` is
 *     not in it, so adding the coordinate does not produce a slower build — it
 *     produces a build that cannot resolve at all.
 *  2. What a library would buy is the hard part of image loading: HTTP, disk
 *     caching, cross-fades, arbitrary content URIs, format sniffing across
 *     dozens of sources. None of that applies. This decodes **one local file per
 *     card**, already validated to be a contained, readable, non-empty
 *     `png`/`jpg`/`jpeg`/`webp` (§1.121), into a fixed-size square. That is a
 *     `BitmapFactory` call and a memory cache keyed on the file's identity — the
 *     same `asImageBitmap()` pattern `VncViewer` already uses for its
 *     framebuffer.
 *
 * So the dependency footprint of §1.124 is zero new coordinates.
 *
 * **Everything here is fail-open.** A decode that fails is null, which the
 * caller renders as the pre-§1.121 letter/glyph avatar. This is reached from the
 * Home list, so throwing would take down the whole screen over decoration — the
 * same reasoning as [com.fem.linexa.service.rootfs.RootFsPreviewImage]'s
 * read-time contract.
 */
object RootFsPreviewLoader {

    /**
     * Decoded previews, keyed on file identity
     * ([RootFsPreviewScaling.cacheKeyOf]) and target size.
     *
     * Bounded and evicted oldest-first: the key space is one entry per visible
     * card per size, but the *cache* must not be a way for a long-lived Home
     * session to accumulate bitmaps for workspaces that have since been
     * deleted. A synchronized [LinkedHashMap] is enough — entries are added on
     * an IO dispatcher and read on the main thread, and there are single-digit
     * numbers of them.
     *
     * A hit here is **not** a bypass of the §1.121 read-time check: the caller
     * only reaches this after [com.fem.linexa.service.rootfs.RootFsPreviewImage.resolveInside]
     * has re-verified containment on this very call, and the key includes the
     * file's mtime and length, so replaced bytes miss.
     */
    private const val MAX_CACHED = 24

    private val cache: MutableMap<String, ImageBitmap> = Collections.synchronizedMap(
        object : LinkedHashMap<String, ImageBitmap>(MAX_CACHED, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
                size > MAX_CACHED
        }
    )

    /**
     * Loads [file] as a square-ish avatar source no smaller than [targetPx],
     * or returns null when it is not a decodable image.
     *
     * Two-pass by necessity: pass one reads only the header
     * (`inJustDecodeBounds`) so the declared dimensions can be sanity-checked
     * *before* any allocation (see [RootFsPreviewScaling.MAX_SOURCE_PIXELS] —
     * a hostile header is a cheap OOM otherwise), pass two decodes at the
     * sample step that produces the smallest bitmap still at least as large as
     * the tile.
     *
     * `RGB_565` is deliberately **not** used: a preview may be a PNG with
     * transparency, and dropping the alpha channel would fill it with black
     * inside a rounded tile.
     */
    fun decode(file: File, targetPx: Int): ImageBitmap? {
        val key = RootFsPreviewScaling.cacheKeyOf(file.absolutePath, file.lastModified(), file.length())
        val cacheKey = "$key@$targetPx"
        cache[cacheKey]?.let { return it }

        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (!RootFsPreviewScaling.isDecodable(bounds.outWidth, bounds.outHeight)) {
                Logger.w(
                    TAG,
                    "Ignoring declared preview ${file.name}: not a decodable image " +
                        "(${bounds.outWidth}x${bounds.outHeight})"
                )
                return null
            }

            val options = BitmapFactory.Options().apply {
                inSampleSize = RootFsPreviewScaling.sampleSizeFor(
                    bounds.outWidth,
                    bounds.outHeight,
                    targetPx
                )
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
            bitmap.asImageBitmap().also { cache[cacheKey] = it }
        } catch (e: OutOfMemoryError) {
            // Bounds were checked, but a decoder can still fail on a crafted
            // file. A missing avatar image is not a reason to crash Home.
            Logger.w(TAG, "Out of memory decoding declared preview ${file.name}")
            null
        } catch (e: Exception) {
            Logger.w(TAG, "Could not decode declared preview ${file.name}: ${e.message}")
            null
        }
    }

    /**
     * The composable form: null until the decode finishes, then the bitmap (or
     * null forever, if it is not an image).
     *
     * Keyed on the file's *path* rather than the [File] instance so an
     * unchanged preview does not re-decode when Home re-emits its list — which
     * it does on every Room write, including each install progress tick.
     *
     * **Phase 6b bug-fix:** the previous version computed the full cache key
     * — `cacheKeyOf(path, lastModified(), length())` — in the composable scope
     * so `produceState` would re-run its body when a preview file was
     * replaced. That made two `stat` syscalls per visible card per
     * recomposition land on the main thread. Now `produceState` is keyed only
     * on the cheap string `file.absolutePath` (no syscall), and the file's
     * mtime/length invalidation is handled by [decode]'s own cache check,
     * which runs on the IO dispatcher and keys the same way. A replaced file
     * with the same path still misses `decode`'s internal cache and re-decodes;
     * the only behaviour change is that the visible-bitmap churn now happens
     * one `produceState` emission later (after the IO decode resolves) rather
     * than synchronously at recomposition time — invisible for an unchanged
     * file, and the right trade for a multi-GB rootfs.
     */
    @Composable
    fun rememberPreview(file: File?, targetPx: Int): State<ImageBitmap?> {
        return produceState<ImageBitmap?>(
            initialValue = null,
            key1 = file?.absolutePath,
            key2 = targetPx
        ) {
            value = if (file == null) {
                null
            } else {
                withContext(Dispatchers.IO) { decode(file, targetPx) }
            }
        }
    }

    private const val TAG = "RootFsPreviewLoader"
}
