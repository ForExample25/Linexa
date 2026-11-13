package com.fem.linexa.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * The branded distribution mark: the distro's **official logo, drawn directly
 * on the surface** — no tile, no background plate, no border, no tint (see
 * [DistroLogo]) — when the distro is identified through [DistroIcons], or the
 * letter avatar when Linexa cannot identify one. No generic "Linux" icon for
 * a distro we can't identify (it would be fake branding).
 *
 * The [distroId] → logo resolution lives centrally in [DistroIcons]; adding
 * a distribution's artwork is one table row there, and every screen that
 * renders a distro identity picks it up.
 *
 * @param size the mark's edge length; the logo scales to fit it edge-to-edge
 *   with its aspect ratio preserved, the letter scales with the typography,
 *   so very small sizes should pass a smaller [letterStyle].
 */
@Composable
fun DistroGlyph(
    distroId: String?,
    letter: String,
    color: Color,
    size: Int = 32,
    letterStyle: androidx.compose.ui.text.TextStyle? = null,
    modifier: Modifier = Modifier
) {
    if (DistroIcons.visualFor(distroId) != null) {
        DistroLogo(
            distroId = distroId,
            size = size.dp,
            modifier = modifier
        )
    } else {
        val shape = RoundedCornerShape((size * 0.31f).dp)
        Box(
            modifier = modifier
                .size(size.dp)
                .clip(shape)
                .background(color = color, shape = shape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = letter,
                style = letterStyle ?: MaterialTheme.typography.labelLarge,
                color = Color.White
            )
        }
    }
}

/**
 * Draws the avatar for a workspace or catalog entry: the image's own declared
 * preview, the distro's official logo (direct, no container — [DistroLogo]),
 * or the letter avatar, in that strict order of precedence.
 *
 * Every fallback is reachable at runtime and none is a failure path: a declared
 * preview that is missing, unreadable, outside the rootfs or not decodable
 * yields null (§1.121 is fail-open by contract) and this renders what it
 * rendered before §1.121. [previewFile] defaults to null so the pre-existing
 * call sites — and every workspace with no declared preview — are unchanged.
 *
 * @param previewFile a preview **already resolved** for this workspace by
 *   [com.fem.linexa.service.rootfs.WorkspaceRootFsMetadata.previewImage]. This
 *   composable never resolves a path itself: containment has to be re-verified
 *   against the live filesystem on every read (§1.121(b)), which is not
 *   something to do inside composition, and taking a `File` rather than a
 *   `String` makes it impossible to pass an unvalidated author string here.
 */
@Composable
fun DistroAvatar(
    letter: String,
    color: Color,
    size: Int = 52,
    distroId: String? = null,
    previewFile: File? = null
) {
    val density = LocalDensity.current
    val targetPx = remember(size, density) { with(density) { size.dp.roundToPx() } }
    val preview by RootFsPreviewLoader.rememberPreview(previewFile, targetPx)
    val declaredPreview = preview
    if (declaredPreview != null) {
        // A preview is artwork, not a glyph: it fills its slot cropped, and is
        // never tinted — the image's own colors are the point.
        Image(
            bitmap = declaredPreview,
            contentDescription = null,
            modifier = Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(16.dp)),
            contentScale = ContentScale.Crop
        )
    } else {
        DistroGlyph(
            distroId = distroId,
            letter = letter,
            color = color,
            size = size
        )
    }
}
