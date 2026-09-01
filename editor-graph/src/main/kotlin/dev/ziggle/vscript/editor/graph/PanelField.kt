package dev.ziggle.vscript.editor.graph

import dev.ziggle.imgui.TextEdit
import dev.ziggle.imgui.TextEditState
import dev.ziggle.imgui.TextFilter
import imgui.ImDrawList
import imgui.ImGui
import imgui.flag.ImDrawFlags
import imgui.flag.ImGuiMouseButton
import dev.ziggle.vscript.editor.host.EditorHost
import dev.ziggle.imgui.Theme
import dev.ziggle.imgui.PanelBits
import dev.ziggle.imgui.EditorKeyboard


/**
 * A text field in screen space, drawn by the same editor the nodes use.
 *
 * Not `ImGui.inputText`. The canvas has a hand-written editor with a real caret, selection, word motion and
 * clipboard — and a look that belongs to this editor rather than to Dear ImGui. Mixing the two puts two
 * visual languages a few pixels apart and gives the panel fields a different set of keys from the node
 * fields, which is the kind of inconsistency people feel without being able to name.
 *
 * The canvas widgets take their geometry in graph space and scale with the camera; the panels have no
 * camera, so this is the same machinery at 1:1.
 */
class PanelField(
    private val id: String,
    private val filter: TextFilter = TextFilter.ANY,
) {
    private val state = TextEditState()

    var text: String = ""
        private set

    val focused: Boolean get() = EditorKeyboard.holds(this)

    /** Set the value from outside — the document changed, or the field is being reused for another row. */
    fun set(value: String) {
        if (focused) return // never yank text out from under someone typing
        text = value
    }

    /**
     * Draw and drive the field.
     *
     * @param live report every keystroke (a filter box) rather than only the committed value (a default).
     * @return the value on the frame it changed, or null.
     */
    fun render(
        dl: ImDrawList,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        placeholder: String = "",
        live: Boolean = false,
        frame: Boolean = true,
    ): String? {
        val fontSize = ImGui.getTextLineHeight()
        ImGui.setCursorScreenPos(x, y)
        val clicked = ImGui.invisibleButton("##pf-$id", w, h)
        val hovered = ImGui.isItemHovered()
        val doubled = hovered && ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)

        if ((clicked || doubled) && !focused) {
            EditorKeyboard.claim(this)
            // Selected on a double click, caret at the end on a single — the same rule as the pin editors.
            state.set(text, selectAll = doubled)
            EditorHost.typed.clear()
        }

        if (frame) {
            val r = 5f
            dl.addRectFilled(x, y, x + w, y + h, if (hovered || focused) FIELD_HOT else FIELD_BG, r, ImDrawFlags.RoundCornersAll)
            if (focused) dl.addRect(x, y, x + w, y + h, Theme.ACCENT, r, ImDrawFlags.RoundCornersAll, 1f)
        }

        var out: String? = null
        if (focused) {
            val typed = EditorHost.typed.drain()
            if (TextEdit.cancelled()) {
                EditorKeyboard.release(this)
            } else {
                val enter = TextEdit.handleKeys(state, filter, typed)
                // Clicking anywhere else commits, the same as tabbing away in any editor — a value is never
                // lost by simply looking elsewhere.
                val away = ImGui.isMouseClicked(ImGuiMouseButton.Left) && !hovered
                if (live && state.text != text) {
                    text = state.text
                    out = text
                }
                if (enter || away) {
                    text = state.text
                    out = text
                    EditorKeyboard.release(this)
                }
            }
            TextEdit.draw(dl, state, x, y, w, h, PAD, fontSize)
        } else {
            val shown = text.ifEmpty { placeholder }
            val col = if (text.isEmpty()) PanelBits.STAMP else Theme.TEXT
            dl.pushClipRect(x + PAD, y, x + w - PAD, y + h, true)
            dl.addText(x + PAD, y + (h - fontSize) * 0.5f, col, shown)
            dl.popClipRect()
        }
        return out
    }

    private companion object {
        const val PAD = 7f
        val FIELD_BG = Theme.col(0xFF, 0xFF, 0xFF, 0x0D)
        val FIELD_HOT = Theme.col(0xFF, 0xFF, 0xFF, 0x16)
    }
}
