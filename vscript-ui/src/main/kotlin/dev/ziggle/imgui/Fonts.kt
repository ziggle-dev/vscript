package dev.ziggle.imgui

import imgui.ImFont

/**
 * The three text sizes of the editor's font, loaded once into the ImGui atlas at startup (see
 * `ImGuiManager.init`). [body] is the normal UI size; [subHeading] and [heading] are the same font at
 * larger sizes for hierarchy (section labels, plugin name / panel headers). Null until loaded, so
 * callers must null-check (and fall back to the current font).
 */
object Fonts {
    @JvmField var body: ImFont? = null
    @JvmField var subHeading: ImFont? = null
    @JvmField var heading: ImFont? = null

    /** True once the FontAwesome solid glyphs (PUA 0xF000–0xF8FF) were merged into [body]. */
    @JvmField var iconsLoaded = false

    /** Baked size ladder for the zoomable node canvas — see [CanvasFonts]. */
    @JvmField var canvas: CanvasFonts = CanvasFonts(emptyList())

    /** Bold / italic faces for inline formatting — see [Emphasis] and `RichText`. */
    @JvmField var emphasis: Emphasis = Emphasis()

    /**
     * A FontAwesome glyph as a string, for inline use in labels (`icon(CARET_LEFT) + " Files"`).
     *
     * Use this rather than a Unicode symbol whenever the text font might not have the character. Lato
     * covers punctuation, arrows and maths, but not the geometric-shape or emoji blocks — a `◀` typed into
     * a label renders as `?`, while the FontAwesome PUA range is always present because it is merged into
     * the body font. Falls back to [fallback] when the icons failed to load.
     */
    fun icon(codepoint: Int, fallback: String = ""): String =
        if (iconsLoaded) String(Character.toChars(codepoint)) else fallback

    // FontAwesome 6 solid codepoints used by the chrome.
    const val CARET_LEFT = 0xF0D9
    const val CARET_RIGHT = 0xF0DA
    const val IMAGE = 0xF03E
    const val SQUARE = 0xF0C8
    const val RETURN = 0xF3E5

    // Node-editor toolbar.
    const val FOLDER = 0xF07B
    const val CHEVRON_DOWN = 0xF078
    const val CHEVRON_UP = 0xF077
    const val UNDO = 0xF0E2
    const val REDO = 0xF01E
    const val EXPAND = 0xF065
    const val GRID = 0xF00A
    const val COMMENT = 0xF075
    const val CHECK_SQUARE = 0xF14A
    const val PLAY = 0xF04B
    const val STOP = 0xF04D

    /** Moon — Sleep / Wake, the cooperative half of the run pair. */
    const val MOON = 0xF186
    const val STEP_FORWARD = 0xF051
    const val POP_OUT = 0xF35D
    const val GEAR = 0xF013

    /** Sliders — the tuning window's toggle. */
    const val SLIDERS = 0xF1DE

    // Canvas / code views.
    /** Project diagram — the node canvas. */
    const val SITEMAP = 0xF542
    /** Angle brackets — the text view. */
    const val CODE = 0xF121

    // Console.
    const val TERMINAL = 0xF120
    const val TRASH = 0xF2ED
    const val EXPORT = 0xF56D
    const val TARGET = 0xF140
    const val WARNING = 0xF071
    const val CLOSE = 0xF00D

    // Debugger transport.
    const val STEP_OVER = 0xF30B
    const val STEP_INTO = 0xF309
    const val STEP_OUT = 0xF30C
    const val STEP_DATA = 0xF542
    const val BUG = 0xF188
    const val VARIABLES = 0xF02B
    const val SEARCH = 0xF002
    const val EYE = 0xF06E
    const val SIDEBAR = 0xF0CA
    const val PLUS = 0xF067

    /** The return trip. `window-restore` rather than a mirrored arrow, because "put this back where it
     *  came from" is a window operation and reads as one. */
    const val POP_IN = 0xF2D2

    // The scheduler dashboard. Each names the QUESTION its row answers rather than the shape it draws —
    // a clock beside a timed activity, a bed beside the night off — so the panel reads at a glance before
    // any of the numbers do.
    /** Clock — a timed activity, and when it next comes round. */
    const val CLOCK = 0xF017
    /** Hourglass — waiting: patience, a stint running down. */
    const val HOURGLASS = 0xF252
    /** Bed — the night off. */
    const val BED = 0xF236
    /** Mug — the short kinds of not-playing: the transition pause and the AFK. */
    const val MUG = 0xF0F4
    /** Bolt — how demanding an activity is. */
    const val BOLT = 0xF0E7
    /** Dice — the draw itself. */
    const val DICE = 0xF522
    /** Ban — an activity switched off. */
    const val BAN = 0xF05E
    /** Chart — history over time. */
    const val CHART = 0xF201

    /** Point this object at [set] — the fonts of whichever ImGui context is about to draw. */
    fun bind(set: FontSet) {
        body = set.body
        subHeading = set.subHeading
        heading = set.heading
        iconsLoaded = set.iconsLoaded
        canvas = set.canvas
        emphasis = set.emphasis
    }

    /**
     * Draw [block] with [set] bound, restoring the previous fonts afterwards.
     *
     * An `ImFont` belongs to the atlas of one ImGui context, so with a second context open (the detached
     * editor window) these globals have to follow whoever is drawing — handing a font from the wrong
     * context to `pushFont` is undefined behaviour in native code. Callers hold the ImGui frame lock, so
     * the swap is never concurrent. See [FontLoader] for why the atlas cannot simply be shared.
     */
    inline fun <T> use(set: FontSet, block: () -> T): T {
        val b = body; val s = subHeading; val h = heading; val i = iconsLoaded; val c = canvas
        val e = emphasis
        bind(set)
        try {
            return block()
        } finally {
            body = b; subHeading = s; heading = h; iconsLoaded = i; canvas = c; emphasis = e
        }
    }
}
