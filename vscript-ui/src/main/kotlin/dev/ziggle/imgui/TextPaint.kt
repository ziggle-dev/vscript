package dev.ziggle.imgui

import imgui.ImDrawList
import imgui.ImFont
import imgui.ImGui

/**
 * Measuring and drawing a run of text, at a size the caller chooses.
 *
 * ### Why this is in the kit and not in the canvas
 *
 * It was `CanvasRenderer.textWidth` / `.shadowedText`, plus an `internal object TextMeasure` tucked into
 * `CanvasCamera.kt` — and between them they were the last thing keeping [TextEdit] inside
 * `:editor-graph`, which in turn was the whole of `:editor-text`'s dependency on the other authoring
 * surface.
 *
 * Nothing here is about a canvas: it picks the nearest baked font size, draws the string twice for a
 * shadow, and asks ImGui how wide a string is. `CanvasRenderer` still exposes both under their old names,
 * delegating, so the sixteen call sites that had every right to keep working did.
 */
object TextPaint {

    /** Width of [s] at the UI font's natural size. */
    fun width(s: String): Float = ImGui.calcTextSize(s).x

    fun lineHeight(): Float = ImGui.getTextLineHeight()

    /** Width of [s] as it would be DRAWN at [size] — the natural width, scaled by the quantised ratio. */
    fun width(s: String, size: Float): Float =
        width(s) * (size.toInt().coerceAtLeast(1) / lineHeight())

    /**
     * [s] at [size], with a drop shadow.
     *
     * **The nearest baked size, not a scaled one.** Stretching a single 15px rasterisation across a
     * 0.25x–2.5x zoom is what makes a hand-drawn surface look soft, and it is the most visible difference
     * from a library that bakes a ladder. imgui-java's `addText` takes an INT size, so the drawn size is
     * quantised regardless — which is what makes the ladder the right shape rather than a nicety.
     *
     * Always the explicit-font overload: the position-only `addText` takes an `ImVec2` in imgui-java and
     * cannot be given a size at all.
     */
    fun shadowed(dl: ImDrawList, x: Float, y: Float, size: Float, col: Int, s: String) {
        val px = size.toInt().coerceAtLeast(1)
        val font = Fonts.canvas.nearest(px.toFloat()) ?: Fonts.body ?: ImGui.getFont()
        val off = maxOf(1f, size * 0.07f)
        dl.addText(font, px, x + off, y + off, SHADOW, s)
        dl.addText(font, px, x, y, col, s)
    }

    /** As above, with the face decided by the caller. */
    fun shadowed(dl: ImDrawList, x: Float, y: Float, size: Float, col: Int, s: String, face: ImFont?) {
        if (face == null) {
            shadowed(dl, x, y, size, col, s)
            return
        }
        val px = size.toInt().coerceAtLeast(1)
        val off = maxOf(1f, size * 0.07f)
        dl.addText(face, px, x + off, y + off, SHADOW, s)
        dl.addText(face, px, x, y, col, s)
    }

    private val SHADOW = Theme.col(0x08, 0x0A, 0x10, 0xB0)
}
