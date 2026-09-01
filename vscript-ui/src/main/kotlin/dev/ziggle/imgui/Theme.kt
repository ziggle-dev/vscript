package dev.ziggle.imgui

/**
 * One shared palette + metrics for the whole vscript editor chrome. Colors are pre-packed as ImGui
 * U32 values (the IM_COL32 byte order: R | G<<8 | B<<16 | A<<24) so [DrawKit] can hand them straight
 * to an `ImDrawList`. Keeping every color/size here is what lets the custom components stay visually
 * consistent — change a value once and the toolbar, panels and plugin manager all follow.
 */
object Theme {

    /**
     * Fade a packed colour to a fraction of its own alpha.
     *
     * **Colour arithmetic, so it belongs to the widget kit.** This and [shade] lived on the canvas's
     * renderer, which meant the run views and the shared panel chrome imported the CANVAS to dim a
     * colour — 21 call sites, none of which wanted a canvas. Nothing here knows what is being drawn.
     */
    fun withAlpha(col: Int, factor: Float): Int {
        val a = ((col ushr 24) and 0xFF) * factor.coerceIn(0f, 1f)
        return (col and 0x00FFFFFF) or (a.toInt().coerceIn(0, 255) shl 24)
    }

    /** Darken (factor < 1) or lighten (> 1) a packed ABGR colour, alpha preserved. */
    fun shade(col: Int, factor: Float): Int {
        val a = (col ushr 24) and 0xFF
        val b = (((col ushr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = (((col ushr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val r = ((col and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (b shl 16) or (g shl 8) or r
    }

    /** Pack 0–255 channels into an ImGui draw-list U32 (ABGR in memory). */
    fun col(r: Int, g: Int, b: Int, a: Int = 255): Int =
        (a and 0xFF shl 24) or (b and 0xFF shl 16) or (g and 0xFF shl 8) or (r and 0xFF)

    /** Convert an 0xAARRGGBB int (what plugins pass) to a draw-list U32. */
    fun argb(argb: Int): Int = col((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, (argb ushr 24) and 0xFF)

    // --- surfaces ---
    val BAR_BG = col(0x16, 0x18, 0x20, 0xF2)      // toolbar
    val PANEL_BG = col(0x1A, 0x1C, 0x26, 0xF2)    // docked panels / windows
    val CARD = col(0x22, 0x25, 0x32)              // raised rows / cards
    val CARD_HOVER = col(0x2B, 0x2F, 0x40)

    /**
     * Fill for a [dev.ziggle.imgui.DrawKit.card] BOX — the unlabelled sibling of [SECTION_BG], and
     * translucent for the same reason.
     *
     * Distinct from [CARD], which is the flat fill behind a hovered row or a chip and belongs to the
     * opaque chrome. A card box is the same thing a section is — a container lifted off whatever is behind
     * it — so it composites over [PANEL_BG] to exactly the `#222532` it used to be, and lifts rather than
     * covers on a translucent overlay. Nested inside a section it lifts again, which is what a card inside
     * a box should do.
     */
    val CARD_BG = col(0x3E, 0x45, 0x5D, 0x38)
    val BORDER = col(0x32, 0x37, 0x49)
    /**
     * Fill for a section/group box — a TRANSLUCENT tint, not an opaque plate.
     *
     * A section used to be a solid colour, which is invisible as a choice on the docked config panel
     * (opaque over opaque) and wrong everywhere else: on a floating overlay panel, whose whole body is
     * ~82% and lets the scene through, an opaque box reads as a plate laid on top of the window rather
     * than as part of it. Three of them turn a translucent panel into a mostly solid one.
     *
     * **The colour is solved rather than chosen.** At this alpha it composites over [PANEL_BG] to exactly
     * `#212430` — the opaque value this replaced — so the config panel is unchanged to the pixel, while
     * over the overlay body it lands on `#1C1F2A`: a touch lighter and bluer than the panel around it, and
     * still 14% scene where the panel is 18%. One value, right on both surfaces, because what a section
     * means ("slightly lifted from whatever is behind it") is a relationship and not a colour.
     */
    val SECTION_BG = col(0x3A, 0x40, 0x54, 0x38)

    /**
     * The outline of a section/group box — deliberately brighter than [BORDER].
     *
     * [BORDER] against [SECTION_BG] is a contrast ratio of **1.31:1**, which is not a subtle border, it is
     * an absent one: the box reads purely as its fill, and on a surface where the fill is nearly the same
     * as the background (a translucent overlay panel) it reads as nothing at all. This is the same hue,
     * lifted to about 2:1 — still quiet against the chrome, and actually a line.
     */
    val SECTION_BORDER = col(0x4A, 0x54, 0x70)

    // --- interactive ---
    val BUTTON = col(0x29, 0x2D, 0x3E)
    val BUTTON_HOVER = col(0x35, 0x3B, 0x52)
    val BUTTON_ACTIVE = col(0x40, 0x47, 0x63)
    val ACCENT = col(0x5B, 0x8C, 0xFF)            // brand blue
    val ACCENT_HOVER = col(0x76, 0x9F, 0xFF)
    val TRACK_OFF = col(0x3A, 0x3F, 0x52)         // toggle track, off
    val KNOB = col(0xEE, 0xF1, 0xF8)

    // --- text ---
    val TEXT = col(0xE6, 0xE9, 0xF2)
    val TEXT_DIM = col(0x8A, 0x90, 0xA4)
    val TEXT_ACCENT = col(0x9F, 0xBC, 0xFF)
    val OK = col(0x57, 0xD9, 0x8A)
    val WARN = col(0xF2, 0xB1, 0x4C)
    val BAD = col(0xF2, 0x6D, 0x6D)

    // --- tag badges (category pills next to a plugin name) ---
    val BADGE_BG = col(0x32, 0x3A, 0x55)          // muted accent-tinted chip
    val BADGE_BORDER = col(0x44, 0x4F, 0x70)
    val BADGE_TEXT = col(0xB9, 0xC8, 0xF2)

    // --- metrics ---
    const val BAR_H = 34f
    /** Width of the left icon sidebar rail (replaces the old top toolbar). */
    const val RAIL_W = 48f
    /** Width of the sidebar rail when expanded (clicking the brand) so item labels fit beside the icons. */
    const val RAIL_EXPANDED_W = 194f
    /**
     * The wash under a hovered or resting ghost control — a button with no chrome until you approach it.
     *
     * White at very low alpha rather than a named grey, so it reads the same over any panel background.
     * Lived on the editor's toolbar; every surface that draws a hoverable row wants them, which is what
     * makes them the kit's.
     */
    val GHOST_REST = col(0xFF, 0xFF, 0xFF, 0x08)
    val GHOST_HOVER = col(0xFF, 0xFF, 0xFF, 0x14)

    /** Side of a square nav icon button in the rail. */
    const val RAIL_ICON = 36f
    const val ROUNDING = 5f
    const val PAD_X = 10f
    const val PAD_Y = 6f
    const val ROW_H = 26f
    const val GAP = 6f
}
