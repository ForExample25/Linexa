package com.fem.linexa.ui.screens.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fem.linexa.R
import com.fem.linexa.ui.theme.haven.HavenDim
import com.fem.linexa.ui.theme.haven.HavenColors
import com.fem.linexa.ui.theme.haven.HavenTheme
import com.fem.linexa.ui.theme.haven.HavenType
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The App grid — the Commands section's launcher presentation (App-grid
 * redesign, adapting the reference launcher's three-column icon grid onto the
 * HAVEN design language).
 *
 * What the reference does well — a scannable grid of large icons on organic
 * geometric backdrops, names underneath, one small type line, search on top,
 * category chips between search and grid, and a long-press menu for
 * management — is kept as STRUCTURE. What is deliberately not copied is its
 * palette: its maroon/indigo blobs belong to that app's identity. Here the
 * blob containers are drawn from Linexa's own Haven tokens (the theme's tint
 * / blue / amber / red washes plus the neutral surface), so the grid reads as
 * Linexa's, not the reference's clone.
 *
 * Everything data-shaped stays in [CommandGridModel] (pure, JVM-testable);
 * this file is presentation only.
 */

/**
 * Pure helpers behind the App grid's presentation decisions.
 *
 * The grid inherits the ONE [CommandNode] tree the Commands segment already
 * renders; these functions are the only new logic between that tree and the
 * tiles, and they are pure so the ordering and search rules are asserted in
 * unit tests rather than judged from a screenshot.
 */
object CommandGridModel {

    /**
     * Every [CommandNode.Leaf] in [commands], depth-first in tree order — the
     * corpus a search query matches against. Categories themselves never
     * match (they are navigation, not commands); their leaves do.
     */
    fun flattenLeaves(commands: List<CommandNode>): List<CommandNode.Leaf> {
        val out = ArrayList<CommandNode.Leaf>()
        fun walk(nodes: List<CommandNode>) {
            for (node in nodes) {
                when (node) {
                    is CommandNode.Category -> walk(node.children)
                    is CommandNode.Leaf -> out.add(node)
                }
            }
        }
        walk(commands)
        return out
    }

    /**
     * Whether [leaf] matches [query]: a case-insensitive substring of the
     * title, the description (which for a commandless-declaration card IS the
     * command) or the command itself. Empty/blank queries match everything.
     *
     * Matching the command text matters: the catalog's honest answer to "what
     * does this do" is often the command, so `apt` should find the install
     * actions without the user guessing titles.
     */
    fun matches(leaf: CommandNode.Leaf, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        val s = leaf.shortcut
        return s.title.contains(q, ignoreCase = true) ||
            s.description.contains(q, ignoreCase = true) ||
            (s.command?.contains(q, ignoreCase = true) == true)
    }

    /** Rows of [nodes] for a [columns]-wide grid, preserving order. */
    fun <T> chunkRows(nodes: List<T>, columns: Int): List<List<T>> {
        require(columns > 0) { "columns must be positive" }
        return nodes.chunked(columns)
    }

    /**
     * The stable blob identity of a grid tile: shape and palette indices
     * derived from [key] — deterministic across launches and recompositions,
     * so an app keeps "its" look the way a launcher icon does. The modulo
     * spreads 25 (5 shapes × 5 palettes) combinations without inventing
     * colors outside the theme.
     */
    fun blobVariant(key: String): Int = abs(key.hashCode()) % BLOB_SHAPE_COUNT
    fun blobPalette(key: String): Int = abs((key.hashCode() shr 5)) % BLOB_PALETTE_COUNT

    /** Mirrors the shape table in [AppBlobIcon]; kept in sync by contract. */
    const val BLOB_SHAPE_COUNT = 5

    /** Mirrors the palette table in [AppBlobIcon]; kept in sync by contract. */
    const val BLOB_PALETTE_COUNT = 5
}

/**
 * One Haven-palette blob container with a centered glyph — the grid's icon.
 *
 * Five shapes (organic pebble, squircle, rounded hexagon, six-scallop flower,
 * tilted pebble) drawn as vector paths on a Canvas; five container/tint pairs
 * taken verbatim from [HavenColors]. [shape] and [palette] come from
 * [CommandGridModel.blobVariant]/[blobPalette] so the combination is stable
 * per command.
 *
 * @param blobSize the container's diameter-equivalent; the glyph draws at
 *   [glyphSize].
 */
@Composable
fun AppBlobIcon(
    iconRes: Int,
    shape: Int,
    palette: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    blobSize: Int = 64,
    glyphSize: Int = 30
) {
    val h = HavenTheme.colors
    val (container, tint) = blobPaletteOf(h, palette)
    Box(
        modifier = modifier.size(blobSize.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawBlobShape(shape, size.minDimension, container)
        }
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(glyphSize.dp)
        )
    }
}

/** The palette table — container wash + glyph tint, both from the theme. */
private fun blobPaletteOf(h: HavenColors, palette: Int): Pair<Color, Color> = when (
    palette.mod(CommandGridModel.BLOB_PALETTE_COUNT)
) {
    0 -> h.tint to h.primary
    1 -> h.blueTint to h.blue
    2 -> h.amberTint to h.amber
    3 -> h.redTint to h.red
    else -> h.surface2 to h.text2
}

/** The five blob shapes, drawn in normalized (0..size) coordinates. */
private fun DrawScope.drawBlobShape(shape: Int, side: Float, color: Color) {
    when (shape.mod(CommandGridModel.BLOB_SHAPE_COUNT)) {
        // 0 — organic pebble: a circle pulled slightly out of round, the
        // reference launcher's most common container.
        0 -> drawPath(pebblePath(side), color)
        // 1 — squircle: the calm geometry for the tiles that should recede.
        1 -> drawPath(
            Path().apply {
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        rect = Rect(0.03f * side, 0.03f * side, 0.97f * side, 0.97f * side),
                        cornerRadius = CornerRadius(0.42f * side, 0.42f * side)
                    )
                )
            },
            color
        )
        // 2 — rounded hexagon (pointy-top).
        2 -> drawPath(
            roundedPolygonPath(side, sides = 6, cornerPull = 0.24f, rotationDegrees = -90f),
            color
        )
        // 3 — six-scallop flower: the most playful container, for the tiles
        // that carry a brand-like glyph.
        3 -> drawPath(scallopPath(side, scallops = 6, baseRadius = 0.335f, bulge = 1.62f), color)
        // 4 — tilted pebble: the pebble rotated, so the same shape never
        // reads as a mistake when it neighbors the straight one. Rotated at
        // draw time around the canvas center — the path itself stays the
        // untilted normalized pebble.
        else -> rotate(degrees = 24f, pivot = Offset(side / 2f, side / 2f)) {
            drawPath(pebblePath(side), color)
        }
    }
}

/**
 * The organic pebble — four cubic segments around the center. The normalized
 * (0..1) control points are scaled by [side]; no tilt here, the tilted
 * variant is [DrawScope.drawBlobShape]'s rotated draw of this same path.
 */
private fun pebblePath(side: Float): Path {
    fun px(f: Float) = f * side
    return Path().apply {
        moveTo(px(0.50f), px(0.045f))
        cubicTo(px(0.76f), px(0.025f), px(0.965f), px(0.23f), px(0.955f), px(0.50f))
        cubicTo(px(0.945f), px(0.77f), px(0.745f), px(0.965f), px(0.475f), px(0.955f))
        cubicTo(px(0.21f), px(0.945f), px(0.035f), px(0.74f), px(0.05f), px(0.47f))
        cubicTo(px(0.065f), px(0.215f), px(0.27f), px(0.062f), px(0.50f), px(0.045f))
        close()
    }
}

/**
 * A regular [sides]-gon with rounded corners: vertices on the circle, each
 * corner pulled [cornerPull] of the way toward the arc between its
 * neighboring edge midpoints via quadratic curves. [rotationDegrees] orients
 * the first vertex (−90° puts it at the top).
 */
private fun roundedPolygonPath(
    side: Float,
    sides: Int,
    cornerPull: Float,
    rotationDegrees: Float
): Path {
    val center = Offset(side / 2f, side / 2f)
    val radius = side * 0.47f
    fun vertex(i: Int): Offset {
        val angle = Math.toRadians((rotationDegrees + i * 360.0 / sides))
        return Offset(
            center.x + radius * cos(angle).toFloat(),
            center.y + radius * sin(angle).toFloat()
        )
    }
    val vertices = (0 until sides).map { vertex(it) }
    val midpoints = (0 until sides).map { i ->
        val a = vertices[i]
        val b = vertices[(i + 1) % sides]
        Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
    }
    val path = Path()
    val first = midpoints[0]
    path.moveTo(first.x, first.y)
    for (i in 0 until sides) {
        val corner = vertices[(i + 1) % sides]
        val nextMid = midpoints[(i + 1) % sides]
        // Pull the control point back toward the centroid to round the corner.
        val control = Offset(
            corner.x + (center.x - corner.x) * cornerPull,
            corner.y + (center.y - corner.y) * cornerPull
        )
        path.quadraticBezierTo(control.x, control.y, nextMid.x, nextMid.y)
    }
    path.close()
    return path
}

/**
 * [scallops] outward bulges around [baseRadius]: each edge between adjacent
 * points bows out through a control point pushed to [bulge]× the radius at
 * the edge's mid-angle — the flower/flower-petal silhouette of the reference
 * launcher, here drawn with the theme's restraint (no per-petal fill).
 */
private fun scallopPath(side: Float, scallops: Int, baseRadius: Float, bulge: Float): Path {
    val center = Offset(side / 2f, side / 2f)
    val r = side * baseRadius
    fun point(i: Int): Offset {
        val angle = Math.toRadians((-90.0 + i * 360.0 / scallops))
        return Offset(
            center.x + r * cos(angle).toFloat(),
            center.y + r * sin(angle).toFloat()
        )
    }
    val path = Path()
    val first = point(0)
    path.moveTo(first.x, first.y)
    for (i in 0 until scallops) {
        val start = point(i)
        val end = point(i + 1)
        val midAngle = Math.toRadians((-90.0 + (i + 0.5) * 360.0 / scallops))
        val control = Offset(
            center.x + r * bulge * cos(midAngle).toFloat(),
            center.y + r * bulge * sin(midAngle).toFloat()
        )
        path.quadraticBezierTo(control.x, control.y, end.x, end.y)
    }
    path.close()
    return path
}

/**
 * One grid cell — an executable command as an app tile.
 *
 * Layout mirrors the reference launcher: blob icon, name (one line), one
 * quiet type line. The reference's long-press menu is real here: [onLongPress]
 * opens the management menu ([CommandTileMenu]); tap runs the command through
 * the existing launch path. While the command's session is opening, the glyph
 * is replaced by the Haven spinner — the tile's own honest "launching" state.
 * A pinned tile carries the pin badge; a favorite carries the star.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CommandAppTile(
    name: String,
    subtitle: String?,
    iconRes: Int,
    shape: Int,
    palette: Int,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    opening: Boolean = false,
    pinned: Boolean = false,
    favorite: Boolean = false
) {
    val h = HavenTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(HavenDim.rTile))
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            AppBlobIcon(
                iconRes = iconRes,
                shape = shape,
                palette = palette,
                contentDescription = null
            )
            if (pinned || favorite) {
                Icon(
                    painter = painterResource(
                        if (pinned) R.drawable.ic_haven_pin else R.drawable.ic_haven_star
                    ),
                    contentDescription = if (pinned) {
                        stringResource(R.string.grid_badge_pinned)
                    } else {
                        stringResource(R.string.grid_badge_favorite)
                    },
                    // The theme's accent reads as "marked" without shouting.
                    tint = h.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(13.dp)
                )
            }
        }
        Text(
            text = name,
            style = HavenType.rowTitle.copy(fontSize = 13.sp),
            color = h.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 9.dp)
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = HavenType.small,
                color = h.muted,
                maxLines = 2,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
    // Opening feedback lives next to the icon, not over the name: the label
    // must never reflow when a run state changes.
    if (opening) {
        Spacer(modifier = Modifier.height(4.dp))
        com.fem.linexa.ui.components.haven.HSpinner(small = true)
    }
}

/**
 * One grid cell — a navigable category. The same blob language with the
 * folder glyph and the derived command count as its type line, so levels and
 * commands scan as one grid rather than two dialects.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CategoryAppTile(
    name: String,
    leafCount: Int,
    shape: Int,
    palette: Int,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val h = HavenTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(HavenDim.rTile))
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AppBlobIcon(
            iconRes = R.drawable.ic_haven_folder,
            shape = shape,
            palette = palette,
            contentDescription = null
        )
        Text(
            text = name,
            style = HavenType.rowTitle.copy(fontSize = 13.sp),
            color = h.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 9.dp)
        )
        Text(
            text = if (leafCount == 1) {
                stringResource(R.string.grid_one_command)
            } else {
                stringResource(R.string.grid_command_count, leafCount)
            },
            style = HavenType.small,
            color = h.muted,
            maxLines = 2,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * A pinned-section header row: the eyebrow-style section label plus the pin
 * glyph, used above the pinned tiles at the grid's root level. Favorites do
 * NOT get their own section (they sort ahead inside their level and carry
 * the star badge) — two parallel sections would make the grid read as a
 * management console instead of a launcher.
 */
@Composable
fun PinnedSectionLabel(modifier: Modifier = Modifier) {
    val h = HavenTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_haven_pin),
            contentDescription = null,
            tint = h.primary,
            modifier = Modifier.size(13.dp)
        )
        Text(
            text = stringResource(R.string.grid_pinned_section),
            style = HavenType.section,
            color = h.muted
        )
    }
}
