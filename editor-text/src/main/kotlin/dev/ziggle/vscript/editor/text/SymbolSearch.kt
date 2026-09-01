package dev.ziggle.vscript.editor.text

import imgui.ImGui
import imgui.flag.ImGuiKey
import imgui.flag.ImGuiMouseButton
import dev.ziggle.imgui.EditorKeyboard
import dev.ziggle.imgui.Fonts
import dev.ziggle.imgui.TextPaint
import dev.ziggle.imgui.Theme
import java.io.File

/**
 * Search everywhere — the box that opens on a double-tap of Shift.
 *
 * ### Why double-tap Shift and not a chord
 *
 * Every unmodified key is a character in a text editor, and every sensible chord is already an editing
 * command. Shift on its own types nothing, which is what makes the double-tap available at all — it is
 * the same reasoning that put it there in IntelliJ. The window is deliberately short: two Shifts half a
 * second apart is a gesture, two Shifts two seconds apart is somebody capitalising twice.
 *
 * ### It takes the keyboard, and gives it back
 *
 * While it is open, typing goes to the query and not to the document. `EditorKeyboard.claim` is how that
 * is negotiated with the code view, and forgetting to release it on close would leave the editor
 * apparently frozen — every keystroke going to a box that is no longer drawn.
 */
class SymbolSearch {

    var workspace: Workspace? = null

    /** Chosen a result: open this file, and go to this line if it is not zero. */
    var onChoose: ((File, Int) -> Unit)? = null

    var isOpen: Boolean = false
        private set

    private var query = StringBuilder()
    private var selected = 0
    private var hits: List<Workspace.Hit> = emptyList()
    private var lastShiftMs = 0L

    /** How long a gap still counts as a double-tap. */
    private val DOUBLE_TAP_MS = 500L

    fun open() {
        isOpen = true
        query.setLength(0)
        selected = 0
        hits = emptyList()
        EditorKeyboard.claim(this)
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        EditorKeyboard.release(this)
    }

    /**
     * Watch for the gesture. Call once a frame, whether or not the box is open.
     *
     * Separate from [draw] so a host can offer the shortcut on a frame where the code view is not the
     * thing being drawn — the box is not part of the code view, it is over everything.
     */
    fun pollShortcut(nowMs: Long) {
        if (isOpen) return
        // The RELEASE, not the press: holding Shift to type a capital letter would otherwise arm it.
        if (!ImGui.isKeyReleased(ImGuiKey.LeftShift) && !ImGui.isKeyReleased(ImGuiKey.RightShift)) return
        if (nowMs - lastShiftMs in 1..DOUBLE_TAP_MS) {
            open()
            lastShiftMs = 0L
        } else {
            lastShiftMs = nowMs
        }
    }

    fun draw(screenW: Float, screenH: Float, typed: String) {
        if (!isOpen) return
        val dl = ImGui.getForegroundDrawList()
        val fs = ImGui.getFontSize().toFloat()
        val lh = fs * 1.6f

        keys(typed)
        hits = workspace?.search(query.toString(), limit = 12).orEmpty()
        if (selected >= hits.size) selected = (hits.size - 1).coerceAtLeast(0)

        val w = (screenW * 0.5f).coerceIn(360f, 720f)
        val rowsH = hits.size * lh
        val h = lh + 12f + rowsH
        val x = (screenW - w) / 2f
        val y = screenH * 0.18f

        // A scrim, so the box reads as modal -- which it is: the keyboard is going here.
        dl.addRectFilled(0f, 0f, screenW, screenH, Theme.col(0x00, 0x00, 0x00, 0x66))
        dl.addRectFilled(x, y, x + w, y + h, Theme.col(0x1A, 0x1D, 0x26))
        dl.addRect(x, y, x + w, y + h, Theme.col(0x3A, 0x42, 0x54))

        // The query line.
        dl.addText(x + 10f, y + (lh - fs) / 2f, Theme.TEXT_DIM, Fonts.icon(Fonts.SEARCH))
        val qx = x + 32f
        val shown = query.toString().ifEmpty { "Search files and symbols" }
        dl.addText(qx, y + (lh - fs) / 2f, if (query.isEmpty()) Theme.TEXT_DIM else Theme.TEXT, shown)
        if (query.isNotEmpty()) {
            val cx = qx + TextPaint.width(query.toString())
            dl.addLine(cx + 1f, y + 5f, cx + 1f, y + lh - 5f, Theme.TEXT)
        }
        dl.addLine(x, y + lh, x + w, y + lh, Theme.col(0x2A, 0x30, 0x3C))

        rows(x, y + lh + 6f, w, lh, fs)
    }

    private fun rows(x: Float, top: Float, w: Float, lh: Float, fs: Float) {
        val dl = ImGui.getForegroundDrawList()
        for ((i, hit) in hits.withIndex()) {
            val ry = top + i * lh
            val on = i == selected
            if (on) dl.addRectFilled(x + 1f, ry, x + w - 1f, ry + lh, Theme.col(0x28, 0x2F, 0x3C))
            if (ImGui.isMouseHoveringRect(x, ry, x + w, ry + lh)) {
                selected = i
                if (ImGui.isMouseClicked(ImGuiMouseButton.Left)) choose()
            }

            val e = hit.entry
            dl.addText(
                x + 10f, ry + (lh - fs) / 2f,
                if (e.isFile) Theme.TEXT_DIM else Theme.col(0x6C, 0x9E, 0xD8),
                Fonts.icon(if (e.isFile) Fonts.CODE else Fonts.BOLT),
            )

            // The name, with the matched characters picked out -- so a fuzzy hit shows WHY it matched
            // rather than looking like an arbitrary result.
            var cx = x + 32f
            for ((ci, ch) in e.name.withIndex()) {
                val lit = ci in hit.matched
                val s = ch.toString()
                dl.addText(cx, ry + (lh - fs) / 2f, if (lit) Theme.ACCENT else Theme.TEXT, s)
                cx += TextPaint.width(s)
            }

            val detail = if (e.isFile) e.detail else "${e.detail}  ${workspace?.relative(e.file) ?: ""}:${e.line}"
            if (detail.isNotBlank()) {
                val dw = TextPaint.width(detail)
                if (cx + 16f + dw < x + w - 8f) {
                    dl.addText(x + w - 8f - dw, ry + (lh - fs) / 2f, Theme.TEXT_DIM, detail)
                }
            }
        }
        if (hits.isEmpty() && query.isNotEmpty()) {
            dl.addText(x + 32f, top + (lh - fs) / 2f, Theme.TEXT_DIM, "no matches")
        }
    }

    private fun keys(typed: String) {
        if (ImGui.isKeyPressed(ImGuiKey.Escape)) { close(); return }
        if (ImGui.isKeyPressed(ImGuiKey.Enter) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter)) { choose(); return }
        if (ImGui.isKeyPressed(ImGuiKey.DownArrow)) selected++
        if (ImGui.isKeyPressed(ImGuiKey.UpArrow)) selected--
        if (ImGui.isKeyPressed(ImGuiKey.Backspace) && query.isNotEmpty()) query.setLength(query.length - 1)
        for (c in typed) if (!c.isISOControl()) query.append(c)
        selected = selected.coerceAtLeast(0)
    }

    private fun choose() {
        val hit = hits.getOrNull(selected) ?: return
        val e = hit.entry
        close()
        onChoose?.invoke(e.file, e.line)
    }
}
