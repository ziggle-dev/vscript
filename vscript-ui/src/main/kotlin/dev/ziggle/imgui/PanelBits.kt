package dev.ziggle.imgui

import imgui.ImDrawList
import imgui.ImGui
import imgui.flag.ImDrawFlags

/**
 * The handful of shapes the bottom panel is built from.
 *
 * Shared by the tab strip, the console's filter chips and the debugger's lists so a chip means the same
 * thing wherever it appears — the alternative is three near-identical pill routines that drift apart one
 * padding value at a time.
 */
object PanelBits {

    const val HEADER_H = 30f
    const val ROW_H = 20f
    const val PAD = 10f
    const val ICON = 22f
    const val PILL_PAD = 7f

    val MUTED = Theme.col(0x7D, 0x86, 0x95)
    val STAMP = Theme.col(0x5F, 0x67, 0x75)
    val BG = Theme.col(0x1B, 0x1F, 0x27)
    val EDGE = Theme.col(0xFF, 0xFF, 0xFF, 0x12)
    val ROW_LINE = Theme.col(0xFF, 0xFF, 0xFF, 0x0C)
    val WARN = Theme.col(0xC9, 0x9A, 0x3A)
    val WARN_TEXT = Theme.col(0xD9, 0xB8, 0x77)
    val WARN_ROW = Theme.col(0xC9, 0x9A, 0x3A, 0x12)
    val ERROR = Theme.col(0xD1, 0x6A, 0x63)
    val ERROR_TEXT = Theme.col(0xE0, 0x8B, 0x84)
    val ERROR_ROW = Theme.col(0xD1, 0x6A, 0x63, 0x16)
    val PAUSED = Theme.col(0xE8, 0xB4, 0x4A)
    val ROW_SELECTED = Theme.col(0xFF, 0xFF, 0xFF, 0x12)

    /** Vertically centred text on a header row. Returns its width. */
    fun label(dl: ImDrawList, x: Float, y: Float, s: String, col: Int, rowH: Float = HEADER_H): Float {
        val ts = ImGui.calcTextSize(s)
        dl.addText(x, y + (rowH - ts.y) * 0.5f, col, s)
        return ts.x
    }

    /** A chip. Selected ones carry a plate; the rest are bare, like the toolbar's ghosts. */
    inline fun pill(
        dl: ImDrawList,
        id: String,
        x: Float,
        y: Float,
        text: String,
        on: Boolean,
        col: Int,
        onClick: () -> Unit,
    ): Float {
        val ts = ImGui.calcTextSize(text)
        val w = ts.x + PILL_PAD * 2
        val h = 18f
        val py = y + (HEADER_H - h) * 0.5f
        ImGui.setCursorScreenPos(x, py)
        if (ImGui.invisibleButton(id, w, h)) onClick()
        val hovered = ImGui.isItemHovered()
        if (on || hovered) {
            dl.addRectFilled(
                x, py, x + w, py + h,
                if (on) Theme.GHOST_HOVER else Theme.GHOST_REST, 4f, ImDrawFlags.RoundCornersAll,
            )
        }
        dl.addText(x + PILL_PAD, y + (HEADER_H - ts.y) * 0.5f, if (on) col else Theme.withAlpha(col, 0.75f), text)
        return w + 3f
    }

    fun iconButton(dl: ImDrawList, id: String, x: Float, y: Float, glyph: String, tip: String): Boolean {
        val py = y + (HEADER_H - ICON) * 0.5f
        ImGui.setCursorScreenPos(x, py)
        val clicked = ImGui.invisibleButton(id, ICON, ICON)
        val hovered = ImGui.isItemHovered()
        if (hovered) dl.addRectFilled(x, py, x + ICON, py + ICON, Theme.GHOST_HOVER, 4f, ImDrawFlags.RoundCornersAll)
        val ts = ImGui.calcTextSize(glyph)
        dl.addText(x + (ICON - ts.x) * 0.5f, py + (ICON - ts.y) * 0.5f, if (hovered) Theme.TEXT else MUTED, glyph)
        if (hovered && tip.isNotEmpty()) ImGui.setTooltip(tip)
        return clicked
    }

    fun actionWidth(glyph: String, label: String): Float =
        ACTION_PAD * 2 + ImGui.calcTextSize(glyph).x + 5f + ImGui.calcTextSize(label).x

    /**
     * A small tinted button for a header row — the drawer's equivalent of the toolbar's primary.
     *
     * Tinted rather than filled for the same reason the toolbar's is: every other control on this row is a
     * translucent wash, and a solid block would read as a different kind of thing that wandered in.
     */
    fun action(dl: ImDrawList, id: String, x: Float, y: Float, glyph: String, label: String, tint: Int): Boolean {
        val w = actionWidth(glyph, label)
        val h = 20f
        val py = y + (HEADER_H - h) * 0.5f
        ImGui.setCursorScreenPos(x, py)
        val clicked = ImGui.invisibleButton(id, w, h)
        val hovered = ImGui.isItemHovered()
        val wash = if (ImGui.isItemActive()) 0.40f else if (hovered) 0.30f else 0.20f
        dl.addRectFilled(x, py, x + w, py + h, Theme.withAlpha(tint, wash), 4f, ImDrawFlags.RoundCornersAll)
        dl.addRect(x, py, x + w, py + h, Theme.withAlpha(tint, if (hovered) 0.85f else 0.6f), 4f, ImDrawFlags.RoundCornersAll, 1f)
        val fg = Theme.shade(tint, 1.3f)
        val gs = ImGui.calcTextSize(glyph)
        dl.addText(x + ACTION_PAD, y + (HEADER_H - gs.y) * 0.5f, fg, glyph)
        dl.addText(x + ACTION_PAD + gs.x + 5f, y + (HEADER_H - gs.y) * 0.5f, fg, label)
        return clicked
    }

    private const val ACTION_PAD = 8f

    fun icon(cp: Int): String = Fonts.icon(cp)
}
