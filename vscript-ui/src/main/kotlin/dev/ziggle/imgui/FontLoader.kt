package dev.ziggle.imgui

import imgui.ImFont
import imgui.ImFontAtlas
import imgui.ImFontConfig
import imgui.ImGuiIO

/**
 * The bold and italic faces, at the body size, for inline formatting.
 *
 * Real faces rather than a drawn-twice fake: Lato ships all four and they are already in resources, so
 * there is no reason to approximate. It matters most for italic, which cannot be faked at all through
 * `ImDrawList.addText` — that draws a whole run in one call, with nowhere to shear a glyph.
 *
 * Any of these may be null if the file is missing; [of] falls back to the regular face, so text renders
 * unstyled rather than not at all.
 */
class Emphasis(
    val bold: CanvasFonts = CanvasFonts(emptyList()),
    val italic: CanvasFonts = CanvasFonts(emptyList()),
    val boldItalic: CanvasFonts = CanvasFonts(emptyList()),
) {
    /**
     * The face for a style at [px], or null to use the regular one.
     *
     * A [CanvasFonts] ladder per face rather than a single size, and the SAME ladder the regular face uses,
     * so `<b>` works wherever text does — a log line at 15px, a heading at 21, a comment on a canvas zoomed
     * to 40. Emphasis that only rendered in panels would be a trap: the tag would simply do nothing on the
     * canvas, with no way to tell that from a typo.
     */
    fun of(bold: Boolean, italic: Boolean, px: Float): ImFont? = when {
        bold && italic -> boldItalic.nearest(px) ?: this.bold.nearest(px) ?: this.italic.nearest(px)
        bold -> this.bold.nearest(px)
        italic -> this.italic.nearest(px)
        else -> null
    }
}

/** The three sizes of the editor font, as loaded into ONE ImGui context's atlas. */
class FontSet(
    val body: ImFont?,
    val subHeading: ImFont?,
    val heading: ImFont?,
    val iconsLoaded: Boolean,
    /** Baked size ladder for the zoomable node canvas — see [CanvasFonts]. */
    val canvas: CanvasFonts = CanvasFonts(emptyList()),
    /** Bold / italic at the body size — see [Emphasis]. */
    val emphasis: Emphasis = Emphasis(),
)

/**
 * The node canvas's font, **baked at several sizes** rather than one.
 *
 * `ImDrawList.addText(font, sizePx, …)` will happily draw a font at any size, but it does so by scaling
 * one rasterisation — so a 15px atlas stretched to 34px is visibly soft, and shrunk to 6px is mush. That is
 * exactly what zooming a node graph does to every label on screen.
 *
 * So the canvas font is baked at a ladder of sizes and [nearest] picks the closest one to what is being
 * asked for; the residual scale is then a few percent rather than several hundred, which reads as crisp at
 * every zoom level.
 *
 * The ladder is geometric rather than every-integer (which is what a from-scratch implementation is tempted
 * to do): each rung is ~15% above the last, so the worst-case residual scale is ~7% while the atlas carries
 * ten sizes instead of thirty. The canvas range is also deliberately narrow — node titles and pin names are
 * ASCII, so there is no reason to rasterise the punctuation and arrow blocks ten more times.
 */
class CanvasFonts(private val ladder: List<Pair<Float, ImFont>>) {

    val isEmpty: Boolean get() = ladder.isEmpty()

    /** The baked font closest to [px], or null when the ladder failed to load. */
    fun nearest(px: Float): ImFont? {
        if (ladder.isEmpty()) return null
        var best = ladder[0]
        var bestDelta = Math.abs(ladder[0].first - px)
        for (i in 1 until ladder.size) {
            val d = Math.abs(ladder[i].first - px)
            if (d < bestDelta) { best = ladder[i]; bestDelta = d }
        }
        return best.second
    }
}

/**
 * Loads the editor's fonts into an ImGui context's font atlas.
 *
 * **Per context, not shared.** An `ImFont` belongs to the atlas it was added to, and the GL backend stamps
 * its font-texture id onto that atlas exactly once — `ImGuiImplGl3.newFrame` only builds the texture when
 * `fontTexture == 0`, and `ImFontAtlas` exposes `setTexID` with no getter to restore a previous value. So
 * two contexts sharing one atlas cannot both work: whichever backend initialises last overwrites the
 * texture id, and the other then draws every glyph with a texture that does not exist in its GL context —
 * which is why sharing the atlas silently blanked the game's ImGui the moment the detached window opened.
 *
 * Each context therefore builds its own atlas from the same font files, and whoever is drawing swaps
 * [Fonts] to its own [FontSet] first (see `Fonts.use`).
 */
object FontLoader {

    /**
     * The codepoints rasterised from the text font.
     *
     * **ImGui's default range is only 0x0020–0x00FF**, and anything outside it renders as `?` — which is
     * why em dashes, ellipses, bullets and arrows scattered through the UI showed up as question marks
     * despite Lato containing every one of them. The glyphs were never missing; they were never asked for.
     *
     * Ranges are pairs, zero-terminated. Requesting codepoints a font lacks is harmless (they are simply
     * skipped), so these are drawn a little wider than current usage to stop the same bug recurring the
     * next time someone types a dash.
     */
    private val TEXT_RANGES: ShortArray = shortArrayOf(
        0x0020, 0x00FF, // Basic Latin + Latin-1 Supplement (· × © ° etc.)
        0x0100, 0x017F, // Latin Extended-A — accented names from the wiki data
        0x2000, 0x206F, // General Punctuation — – — ‘ ’ “ ” … • ‰
        0x20A0, 0x20BF, // Currency symbols
        0x2190, 0x21FF, // Arrows — → ↗
        0x2200, 0x22FF, // Mathematical Operators — − ≥ ≤ ≠
        0x25A0, 0x25FF, // Geometric Shapes — ● ■ (Lato covers only part of this block)
        0,
    )

    /**
     * Sizes baked for the zoomable canvas.
     *
     * Geometric, ~15% apart, spanning a 15px base across the canvas's 0.25x-2.5x zoom range. Ten rungs
     * keeps the worst-case residual scale under ~8% for an atlas cost of ten ASCII faces.
     */
    private val CANVAS_LADDER = floatArrayOf(6f, 8f, 10f, 12f, 14f, 16f, 19f, 23f, 28f, 34f, 40f)

    /**
     * Latin-1, plus the few symbols the canvas actually draws.
     *
     * The wide text ranges are not worth rasterising once per rung — eleven of them — but "ASCII only" was
     * too blunt: a function box shows its signature as `(int) → (int)`, and with the arrow missing from the
     * ladder it drew as nothing at all, while the very same string rendered correctly in the outline (which
     * uses the full-range body face). Comments are canvas text too, so anything typing prose produces —
     * an em-dash, a curly quote, an ellipsis — had the same hole waiting.
     *
     * So: Latin-1 plus three small, named blocks rather than the general punctuation and mathematical
     * ranges the UI faces carry. About 35 extra glyphs a rung against several hundred, which is what keeps
     * the ladder affordable at eleven rungs.
     */
    private val CANVAS_RANGES: ShortArray = shortArrayOf(
        0x0020, 0x00FF, // Latin-1
        0x2010, 0x2027, // dashes, curly quotes, ellipsis, bullet — what typing prose produces
        0x2190, 0x2193, // ← ↑ → ↓ — the function-signature arrow and its siblings
        0x2260, 0x2265, // ≠ ≤ ≥
        0,
    )

    fun load(gio: ImGuiIO): FontSet = runCatching {
        val atlas = gio.fonts
        val lato = javaClass.getResourceAsStream("/fonts/Lato-Regular.ttf")?.use { it.readBytes() }
        val set = if (lato != null) {
            // Lato at three sizes (body becomes the default font, being added first). FontAwesome is
            // merged into the body font so icon glyphs (e.g. the plugin-reload button) render inline.
            val body = atlas.addFontFromMemoryTTF(lato, 15f, TEXT_RANGES)
            val icons = mergeIcons(atlas, 15f)
            // The ladder is added AFTER the UI faces so the first font added stays the default.
            val ladder = CANVAS_LADDER.map { px -> px to atlas.addFontFromMemoryTTF(lato, px, CANVAS_RANGES) }
            FontSet(
                body,
                atlas.addFontFromMemoryTTF(lato, 17f, TEXT_RANGES),
                atlas.addFontFromMemoryTTF(lato, 21f, TEXT_RANGES),
                icons,
                CanvasFonts(ladder),
                // Every size the regular face is available at, with the WIDE ranges — so `<b>` and `<i>`
                // work anywhere text does, and an em-dash or an arrow inside one does not vanish.
                emphasis = Emphasis(
                    bold = ladderOf("/fonts/Lato-Bold.ttf", atlas),
                    italic = ladderOf("/fonts/Lato-Italic.ttf", atlas),
                    boldItalic = ladderOf("/fonts/Lato-BoldItalic.ttf", atlas),
                ),
            )
        } else {
            FontSet(atlas.addFontDefault(), sizedDefault(atlas, 16f), sizedDefault(atlas, 20f), false)
        }
        atlas.build()
        set
    }.getOrElse { FontSet(null, null, null, false) }

    /**
     * Every size a face is needed at: the three UI sizes plus the canvas ladder, deduplicated.
     *
     * One list serving both means [CanvasFonts.nearest] answers for a panel and for a zoomed canvas alike,
     * so nothing has to know which surface it is drawing on to find the right bold.
     */
    private val EMPHASIS_LADDER: FloatArray =
        (floatArrayOf(15f, 17f, 21f) + CANVAS_LADDER).distinct().sorted().toFloatArray()

    /** An emphasis face at every size in [EMPHASIS_LADDER], or empty when the file is not bundled. */
    private fun ladderOf(path: String, atlas: ImFontAtlas): CanvasFonts {
        val bytes = javaClass.getResourceAsStream(path)?.use { it.readBytes() } ?: return CanvasFonts(emptyList())
        // The WIDE ranges at every rung: an italic run is prose, and prose is exactly where the curly
        // quotes, dashes and arrows live. The canvas face gets away with a narrow set because it draws
        // pin names; this does not.
        val rungs = EMPHASIS_LADDER.toList().mapNotNull { px ->
            runCatching { px to atlas.addFontFromMemoryTTF(bytes, px, TEXT_RANGES) }.getOrNull()
        }
        return CanvasFonts(rungs)
    }

    /** Merge the FontAwesome 6 solid glyphs (Unicode PUA) into the previously added font (here: body). */
    private fun mergeIcons(atlas: ImFontAtlas, size: Float): Boolean {
        val bytes = javaClass.getResourceAsStream("/fonts/fa-solid-900.ttf")?.use { it.readBytes() } ?: return false
        val cfg = ImFontConfig()
        cfg.setMergeMode(true)
        cfg.setPixelSnapH(true)
        cfg.setGlyphMinAdvanceX(size) // monospace the icons
        atlas.addFontFromMemoryTTF(bytes, size, cfg, shortArrayOf(0xF000.toShort(), 0xF8FF.toShort(), 0))
        cfg.destroy()
        return true
    }

    private fun sizedDefault(atlas: ImFontAtlas, size: Float): ImFont {
        val cfg = ImFontConfig()
        cfg.sizePixels = size
        val font = atlas.addFontDefault(cfg)
        cfg.destroy()
        return font
    }
}
