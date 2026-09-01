package dev.ziggle.vscript.editor.graph

import imgui.ImGui
import imgui.flag.ImGuiColorEditFlags
import imgui.flag.ImGuiCond
import imgui.flag.ImGuiHoveredFlags
import imgui.flag.ImGuiMouseButton
import imgui.flag.ImGuiWindowFlags

/**
 * The colour picker popup — a wheel, not a text box.
 *
 * Colour is the one value where typing the literal is the worst way to set it: nobody knows what
 * `#FF3CC4E0` looks like, and finding a colour by editing hex digits is a search with no feedback until
 * you stop. So the swatch opens a real picker and the hex stays beside it for the cases text IS better —
 * copying one, pasting one, matching one exactly.
 *
 * Routed the same way [ValuePicker] is: exactly one surface may render it per frame, so this only records
 * WHICH field asked and hands the answer back to the panel, which knows how to commit to a node pin, a
 * variable default, or a list slot. Keeping that knowledge in one place is why a picker cannot just write
 * the value itself.
 */
object ColorPicker {

    /** The field that opened it, or null when closed. */
    var openId: String? = null
        private set

    private val rgba = FloatArray(4)
    private var px = 0f
    private var py = 0f

    /** The frame [open] was called on. See the close rule in [render]. */
    private var openedFrame = -1

    /** Open for [id], anchored at [x],[y], starting from [argb]. */
    fun open(id: String, x: Float, y: Float, argb: Int) {
        openId = id
        px = x
        py = y
        openedFrame = ImGui.getFrameCount()
        rgba[0] = ((argb shr 16) and 0xFF) / 255f
        rgba[1] = ((argb shr 8) and 0xFF) / 255f
        rgba[2] = (argb and 0xFF) / 255f
        rgba[3] = ((argb ushr 24) and 0xFF) / 255f
    }

    fun close() {
        openId = null
    }

    /**
     * Draw it, returning the chosen ARGB on the frames it changes.
     *
     * Reports LIVE rather than only on close, so the swatch and anything reading the value update as the
     * wheel is dragged — picking a highlight colour while watching the highlight is the entire point, and
     * a picker that only commits on OK makes you guess and check.
     */
    fun render(): Int? {
        val id = openId ?: return null
        ImGui.setNextWindowPos(px, py, ImGuiCond.Appearing)
        ImGui.setNextWindowBgAlpha(0.98f)
        var out: Int? = null
        val flags = ImGuiWindowFlags.NoTitleBar or ImGuiWindowFlags.AlwaysAutoResize or
            ImGuiWindowFlags.NoSavedSettings or ImGuiWindowFlags.NoDocking
        if (ImGui.begin("##vs-color-$id", flags)) {
            val pick = ImGuiColorEditFlags.AlphaBar or ImGuiColorEditFlags.AlphaPreviewHalf or
                ImGuiColorEditFlags.PickerHueBar
            if (ImGui.colorPicker4("##wheel", rgba, pick)) out = pack()
            if (ImGui.button("Done")) close()
            // Clicking anywhere else commits and closes — the same rule the text fields use, and it means
            // a colour is never lost by simply looking away.
            //
            // But not while it is still opening. The swatch opens the picker DURING the same frame this
            // runs, so the click that opened it is still flagged as clicked here, and a window created this
            // frame has no rectangle from the last one for the hover test to succeed against. Both go the
            // wrong way at once, so the picker read its own opening click as a click somewhere else and
            // shut itself before it could be used.
            // Two frames, where ValuePicker's identical guard needs only one: it hit-tests geometry it
            // computed itself, which is right immediately, while this asks ImGui whether its own window is
            // hovered — and an auto-resizing window has no trustworthy rectangle until it has been laid
            // out once.
            val settled = ImGui.getFrameCount() > openedFrame + 1
            // AllowWhenBlockedByActiveItem is what makes the wheel DRAGGABLE. Pressing on it makes the
            // picker ImGui's active item, and a plain hover test reports false the moment anything is
            // active — so grabbing the wheel looked like a click somewhere else, and the picker committed
            // that first colour and shut. Dragging around to find a colour is the entire point of a wheel.
            val hoverFlags = ImGuiHoveredFlags.RootAndChildWindows or
                ImGuiHoveredFlags.AllowWhenBlockedByActiveItem
            val awayClick = settled && ImGui.isMouseClicked(ImGuiMouseButton.Left) &&
                !ImGui.isWindowHovered(hoverFlags)
            if (awayClick) close()
        }
        ImGui.end()
        return out
    }

    private fun pack(): Int {
        fun ch(v: Float): Int = (v.coerceIn(0f, 1f) * 255f).toInt() and 0xFF
        return (ch(rgba[3]) shl 24) or (ch(rgba[0]) shl 16) or (ch(rgba[1]) shl 8) or ch(rgba[2])
    }
}
