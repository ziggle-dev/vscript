package dev.ziggle.vscript.runview

import imgui.ImDrawList
import imgui.ImGui
import dev.ziggle.imgui.Fonts
import dev.ziggle.imgui.Theme
import dev.ziggle.vscript.model.RichText
import dev.ziggle.vscript.model.TextSpan
import dev.ziggle.vscript.model.TextStyle

/**
 * Paints [RichText] spans — colour, background, bold, underline, strikethrough.
 *
 * Two surfaces draw text in this editor and they do it differently: a docked panel uses ImGui's current
 * font through `addText`, while the zooming canvas picks a baked size off a ladder and adds a shadow. So
 * the span walk is written once and the two differences — how a run is measured, and how a run is put on
 * screen — are handed in. Writing it twice is how the console ends up supporting a tag the canvas does not.
 */
object RichTextDraw {

    /** Draw [text] with the panel font at the current ImGui size. Returns the width drawn. */
    fun panel(dl: ImDrawList, x: Float, y: Float, text: String, defaultCol: Int): Float {
        val size: Float = ImGui.getFontSize().toFloat()
        return draw(
            dl, x, y, RichText.parse(text), defaultCol,
            lineH = ImGui.getTextLineHeight(),
            measure = { s, st -> measureWith(s, st, size) { ImGui.calcTextSize(it).x } },
            put = { px, py, col, s, st ->
                val face = Fonts.emphasis.of(st.bold, st.italic, size)
                if (face == null) dl.addText(px, py, col, s) else dl.addText(face, size.toInt().coerceAtLeast(1), px, py, col, s)
            },
        )
    }


    // **There was a `canvas(…)` here and it had no callers.** It measured and drew through
    // `CanvasRenderer`, and was the only reason this file — which is otherwise ImGui, the widget kit and
    // the language's own `RichText` — depended on the canvas surface at all. Deleting it is what let this
    // move to the run views, which are its one real consumer. If the canvas ever wants rich text it can
    // add the call site and pass its own measure/put, which is the shape `draw` already takes.
    /** What [text] will occupy once its tags are gone, in the panel font. */
    fun panelWidth(text: String): Float {
        val size: Float = ImGui.getFontSize().toFloat()
        return RichText.parse(text)
            .sumOf { measureWith(it.text, it.style, size) { s -> ImGui.calcTextSize(s).x }.toDouble() }
            .toFloat()
    }

    /**
     * Measure a run in the face it will be DRAWN in.
     *
     * Bold is wider than regular, and measuring it as regular is how a styled run ends up overlapping the
     * one after it — the kind of bug that looks like a layout problem and is really a measurement one.
     */
    private fun measureWith(
        s: String,
        style: TextStyle,
        size: Float,
        fallback: (String) -> Float,
    ): Float {
        val face = Fonts.emphasis.of(style.bold, style.italic, size) ?: return fallback(s)
        val w = runCatching { face.calcTextSizeA(size, Float.MAX_VALUE, 0f, s).x }.getOrNull()
        return if (w == null || w <= 0f) fallback(s) else w
    }

    private fun draw(
        dl: ImDrawList,
        x: Float,
        y: Float,
        spans: List<TextSpan>,
        defaultCol: Int,
        lineH: Float,
        measure: (String, TextStyle) -> Float,
        put: (Float, Float, Int, String, TextStyle) -> Unit,
    ): Float {
        var cx = x
        for (span in spans) {
            if (span.text.isEmpty()) continue
            val st = span.style
            val w = measure(span.text, st)

            // Behind the glyphs. Drawn per span rather than once for the line, so `<bg>` marks a phrase.
            st.background?.let { dl.addRectFilled(cx, y, cx + w, y + lineH, opaque(it)) }

            val col = st.color?.let { opaque(it) } ?: defaultCol
            put(cx, y, col, span.text, st)

            val rule = maxOf(1f, lineH * 0.07f)
            if (st.underline) {
                val uy = y + lineH - rule
                dl.addLine(cx, uy, cx + w, uy, col, rule)
            }
            if (st.strike) {
                val sy = y + lineH * 0.55f
                dl.addLine(cx, sy, cx + w, sy, col, rule)
            }
            cx += w
        }
        return cx - x
    }

    /** 0xRRGGBB from the markup -> a fully opaque draw-list colour. */
    private fun opaque(rgb: Int): Int =
        Theme.col((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, 0xFF)
}
