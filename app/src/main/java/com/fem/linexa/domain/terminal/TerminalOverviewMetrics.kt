package com.fem.linexa.domain.terminal

/**
 * Geometry contract for the Recent Sessions grid.
 *
 * The overview is a stable two-column (phone) grid, not a carousel. Every
 * card has one aspect ratio and is sized from the available WIDTH only, so the
 * IME opening/closing can never change the cards: the viewport may get shorter,
 * the cards do not.
 *
 * [CARD_ASPECT_RATIO] is width / height. 0.72 is a portrait recent-app card:
 * tall enough to read a terminal preview, wide enough for two columns on a
 * normal phone, and inside the 0.68–0.75 product band.
 */
object TerminalOverviewMetrics {

    const val CARD_ASPECT_RATIO: Float = 0.72f

    const val SCREEN_PADDING_DP: Int = 20
    const val COLUMN_GAP_DP: Int = 14

    /**
     * Horizontal mode keeps one card readable on tablets instead of stretching
     * it to the full window width. The width cap is still width-only: it never
     * consults height or IME insets.
     */
    const val HORIZONTAL_MAX_CARD_WIDTH_DP: Int = 520

    /**
     * Fixed column counts by available content width. Portrait phones keep
     * two readable columns; very narrow devices fall back to one instead of
     * forcing a cramped grid; tablets gain columns rather than one huge card.
     */
    fun columnsForWidth(widthDp: Float): Int = when {
        widthDp < 340f -> 1
        widthDp < 600f -> 2
        widthDp < 720f -> 3
        widthDp < 1000f -> 4
        else -> 5
    }

    /** The exact width one cell receives from a fixed-column grid. */
    fun cardWidthDp(
        availableWidthDp: Float,
        columns: Int,
        horizontalPaddingDp: Int = SCREEN_PADDING_DP,
        gapDp: Int = COLUMN_GAP_DP
    ): Float {
        val safeColumns = columns.coerceAtLeast(1)
        val usable = availableWidthDp -
            horizontalPaddingDp * 2f -
            gapDp * (safeColumns - 1)
        return (usable / safeColumns).coerceAtLeast(0f)
    }

    /**
     * The card width in horizontal mode: the viewport width minus both screen
     * gutters, capped for tablets. Height is then the shared aspect ratio, so
     * the IME cannot influence either dimension.
     */
    fun horizontalCardWidthDp(
        availableWidthDp: Float,
        horizontalPaddingDp: Int = SCREEN_PADDING_DP,
        maxWidthDp: Int = HORIZONTAL_MAX_CARD_WIDTH_DP
    ): Float = (availableWidthDp - horizontalPaddingDp * 2f)
        .coerceIn(0f, maxWidthDp.toFloat())
}
