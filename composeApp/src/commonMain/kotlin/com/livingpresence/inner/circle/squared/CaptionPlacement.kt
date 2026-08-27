package com.livingpresence.inner.circle.squared

/** Gap (dp) kept between the caption strip and the bottom edge of the player. */
internal const val CAPTION_EDGE_INSET_DP = 8f

/** Gap (dp) kept between the caption strip and the bottom control bar above which it sits. */
internal const val CAPTION_CONTROLS_GAP_DP = 8f

/**
 * How far above the player's bottom edge the caption strip sits where the bottom control
 * bar shares the screen with it — iOS and web, whose controls never hide: the bar's
 * measured height plus a small gap, falling back to the plain edge inset until the bar
 * has been measured. Android hides its captions while the controls are up, so it pins
 * them at [CAPTION_EDGE_INSET_DP] instead.
 *
 * Captions belong at the bottom of the screen; a fixed lift (the previous 120.dp) is what
 * pushed the text into the middle of the picture in landscape, where the whole player is
 * barely twice that tall.
 *
 * @param controlsBarHeightDp measured height of the bottom control bar; ignored when it
 *   hasn't been measured yet (0, negative, or non-finite).
 */
internal fun captionBottomInsetDp(controlsBarHeightDp: Float): Float {
    if (!controlsBarHeightDp.isFinite() || controlsBarHeightDp <= 0f) return CAPTION_EDGE_INSET_DP
    return controlsBarHeightDp + CAPTION_CONTROLS_GAP_DP
}
