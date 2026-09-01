package dev.ziggle.vscript.editor.graph

import dev.ziggle.imgui.TextEdit
import dev.ziggle.imgui.TextEditState
import dev.ziggle.imgui.TextFilter
import imgui.ImDrawList
import imgui.ImGui
import imgui.flag.ImDrawFlags
import imgui.flag.ImGuiKey
import imgui.flag.ImGuiMouseButton
import dev.ziggle.imgui.FuzzySearch
import dev.ziggle.imgui.Theme
import dev.ziggle.vscript.editor.graph.PinStyle
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeDescriptor
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.model.canConnect
import dev.ziggle.vscript.runview.NodeColors
import dev.ziggle.imgui.EditorKeyboard

/**
 * The add-node palette: a hand-drawn, search-first popup.
 *
 * **Search-first, not a menu tree.** With a catalog heading for hundreds of entries, typing three letters
 * beats walking a hierarchy, and it is where every comparable editor ended up. The category is still shown
 * on every row so browsing stays legible.
 *
 * Three things the reference implementation got wrong, fixed here because each one bites exactly when the
 * palette is most useful:
 *
 *  - **Filtering kept its categories.** There, typing collapsed the tree into a flat list with no group
 *    labels — losing all context precisely when you are least sure what you are looking for.
 *  - **Keyboard navigation follows the FILTERED list.** There, the arrow keys walked the entire unfiltered
 *    library regardless of what was typed, and never scrolled the highlight into view, so it routinely
 *    sat off-screen.
 *  - **Matching is fuzzy and ranked**, not a substring test, so "wlk" finds "Walk To".
 *
 * Drawn in SCREEN space, not graph space: a popup is chrome, and should be equally readable however far
 * the canvas is zoomed.
 */
class CanvasPalette(private val catalog: NodeCatalog) {

    private val search = TextEditState()

    var isOpen = false
        private set

    /** Where the panel is anchored, and where a chosen node should be created (graph space). */
    private var anchorX = 0f
    private var anchorY = 0f
    var createAtX = 0f
        private set
    var createAtY = 0f
        private set

    /** The pin the palette was opened from, so results can be filtered to what would actually connect. */
    var fromPin: TypeRef? = null
        private set

    private var selected = 0
    private var scroll = 0f
    private var scrollTarget = 0f
    private var openProgress = 0f
    private var results: List<NodeDescriptor> = emptyList()

    /** Last cursor position, so hover can tell "the mouse is here" from "the mouse just moved here". */
    private var lastMouseX = Float.NaN
    private var lastMouseY = Float.NaN

    fun open(screenX: Float, screenY: Float, graphX: Float, graphY: Float, from: TypeRef?) {
        isOpen = true
        anchorX = screenX
        anchorY = screenY
        createAtX = graphX
        createAtY = graphY
        fromPin = from
        search.set("")
        selected = 0
        scroll = 0f
        scrollTarget = 0f
        openProgress = 0f
        // Reset so the first frame does not count as movement and immediately hover-select whatever the
        // panel happens to open under the cursor.
        lastMouseX = screenX
        lastMouseY = screenY
        dev.ziggle.vscript.editor.host.EditorHost.typed.clear()
        // CLAIM THE KEYBOARD. The palette drains the same typed-character buffer the pin editors do, and
        // `CanvasWidgets.pumpKeyboard` empties that buffer whenever nothing has claimed it — so with no
        // claim here the search box lost its keystrokes to whichever field pumped first that frame, which
        // is why it took typing only sometimes. See EditorKeyboard: one buffer, three consumers, and the
        // rule is that whoever has focus says so.
        dev.ziggle.imgui.EditorKeyboard.claim(this)
    }

    fun close() {
        isOpen = false
        fromPin = null
        dev.ziggle.imgui.EditorKeyboard.release(this)
    }

    /**
     * Draw and drive the palette.
     *
     * @return the chosen descriptor on the frame it is picked, else null.
     */
    fun render(dl: ImDrawList, dt: Float, clipW: Float, clipH: Float, clipX: Float, clipY: Float): NodeDescriptor? {
        if (!isOpen) return null

        // Grow on open. The reference popped its contents in at the halfway mark, which reads as a glitch;
        // fading them with the same curve makes one motion instead of two events.
        openProgress = (openProgress + dt / OPEN_SECONDS).coerceAtMost(1f)
        val ease = 1f - (1f - openProgress) * (1f - openProgress) * (1f - openProgress)

        // Whether the cursor MOVED this frame, not merely where it is.
        //
        // Hovering a row selects it, which fights the arrow keys: the cursor sits still over a row, so
        // every frame it re-claims the selection and the keyboard appears to snap back. Gating hover on
        // actual movement hands control to whichever device the user last used, which is what every menu
        // that gets this right does.
        val mousePos = ImGui.getMousePos()
        val mouseMoved = lastMouseX.isNaN() ||
            Math.abs(mousePos.x - lastMouseX) > MOUSE_MOVE_EPS ||
            Math.abs(mousePos.y - lastMouseY) > MOUSE_MOVE_EPS
        lastMouseX = mousePos.x
        lastMouseY = mousePos.y

        val typed = dev.ziggle.vscript.editor.host.EditorHost.typed.drain()
        if (TextEdit.cancelled()) {
            close()
            return null
        }

        // Up/Down drive the LIST; Left/Right stay with the caret. That split is what lets you refine a
        // query and move the highlight without ever reaching for the mouse.
        val prevText = search.text
        TextEdit.handleKeys(search, TextFilter.ANY, typed)
        results = search(search.text, fromPin)
        if (search.text != prevText) {
            selected = 0
            scrollTarget = 0f
        }
        // Tracked because scroll-into-view must follow the KEYBOARD only. Applying it every frame would
        // fight the wheel, and hovering a row also moves the selection — so an unconditional follow would
        // yank the list back the moment the mouse passed over it.
        var keyboardMoved = search.text != prevText
        if (results.isNotEmpty()) {
            if (ImGui.isKeyPressed(ImGuiKey.DownArrow, true)) {
                selected = (selected + 1) % results.size
                keyboardMoved = true
            }
            if (ImGui.isKeyPressed(ImGuiKey.UpArrow, true)) {
                selected = (selected - 1 + results.size) % results.size
                keyboardMoved = true
            }
        }
        selected = selected.coerceIn(0, maxOf(0, results.size - 1))

        val rows = results.size
        val listH = (rows * ROW_H).coerceAtMost(MAX_LIST_H)
        val panelH = (SEARCH_H + PAD * 2 + listH) * ease
        val panelW = WIDTH

        // Keep the panel on screen rather than letting it run off the edge it was opened near.
        val x = anchorX.coerceIn(clipX, clipX + clipW - panelW)
        val y = anchorY.coerceIn(clipY, clipY + clipH - panelH)

        val alpha = ease
        panel(dl, x, y, panelW, panelH, alpha)
        if (ease < 0.35f) return null // too small to draw contents into legibly

        // Search box. Borderless: the field is the only thing in the panel that takes typing, so a hard
        // 1px accent outline is stating something already obvious and adds a second rectangle inside a
        // rectangle. A slightly recessed fill and a single accent underline carry focus instead.
        val sx = x + PAD
        val sy = y + PAD
        val sw = panelW - PAD * 2
        dl.addRectFilled(sx, sy, sx + sw, sy + SEARCH_H, fade(FIELD_BG, alpha), 5f, ImDrawFlags.RoundCornersAll)
        dl.addLine(
            sx + 2f, sy + SEARCH_H - 0.5f, sx + sw - 2f, sy + SEARCH_H - 0.5f,
            fade(Theme.ACCENT, alpha * 0.85f), 1.5f,
        )
        if (search.length == 0) {
            CanvasRenderer.shadowedText(
                dl, sx + 8f, sy + (SEARCH_H - FONT) * 0.5f, FONT, fade(PLACEHOLDER, alpha),
                if (fromPin == null) "Search nodes…" else "Search ${fromPin} nodes…",
            )
        }
        TextEdit.draw(dl, search, sx, sy, sw, SEARCH_H, 8f, FONT)

        // The wheel scrolls the list whenever the cursor is over the panel. The canvas does not steal it:
        // an open palette blocks canvas gestures, so the zoom that normally owns the wheel stands down.
        val wheel = ImGui.getIO().mouseWheel
        if (wheel != 0f && !mouseOutside(x, y, panelW, panelH)) scrollTarget -= wheel * WHEEL_STEP

        // Follow the highlight only when the KEYBOARD moved it — see keyboardMoved.
        if (keyboardMoved) {
            val viewTop = selected * ROW_H
            if (viewTop < scrollTarget) scrollTarget = viewTop
            if (viewTop + ROW_H > scrollTarget + listH) scrollTarget = viewTop + ROW_H - listH
        }
        scrollTarget = scrollTarget.coerceIn(0f, (rows * ROW_H - listH).coerceAtLeast(0f))
        scroll += (scrollTarget - scroll) * SCROLL_EASE

        val lx = x + PAD
        val ly = sy + SEARCH_H + PAD
        var chosen: NodeDescriptor? = null

        if (rows == 0) {
            CanvasRenderer.shadowedText(dl, lx + 6f, ly + 6f, FONT, fade(Theme.TEXT_DIM, alpha), "no matches")
        } else {
            dl.pushClipRect(lx, ly, lx + sw, ly + listH, true)
            val mouse = ImGui.getMousePos()
            for ((i, d) in results.withIndex()) {
                val ry = ly + i * ROW_H - scroll
                if (ry + ROW_H < ly || ry > ly + listH) continue
                val hovered = mouse.x >= lx && mouse.x <= lx + sw && mouse.y >= ry && mouse.y <= ry + ROW_H
                // Only a MOVING cursor may take the selection — see mouseMoved.
                if (hovered && mouseMoved) selected = i
                if (i == selected) {
                    // A subtle fill plus an accent edge, not a solid blue block: the row is a *cursor*, and
                    // a saturated bar behind the text fights the text it is meant to be pointing at.
                    dl.addRectFilled(lx, ry, lx + sw, ry + ROW_H, fade(ROW_SELECTED, alpha), 4f, ImDrawFlags.RoundCornersAll)
                    dl.addRectFilled(lx, ry + 3f, lx + 2f, ry + ROW_H - 3f, fade(Theme.ACCENT, alpha), 1f)
                } else if (hovered) {
                    dl.addRectFilled(lx, ry, lx + sw, ry + ROW_H, fade(ROW_HOVER, alpha), 4f, ImDrawFlags.RoundCornersAll)
                }
                // A colour chip carrying the node's own header colour, so a row in the list and the node it
                // creates are visibly the same thing.
                dl.addRectFilled(lx + 7f, ry + 6f, lx + 11f, ry + ROW_H - 6f, fade(NodeColors.headerColor(d.category, d.kind), alpha), 2f)
                CanvasRenderer.shadowedText(dl, lx + 18f, ry + (ROW_H - FONT) * 0.5f, FONT, fade(Theme.TEXT, alpha), d.title)
                // The category is context, not a second name, so it is smaller AND muted — at the same
                // weight as the title it competes with the thing you are actually scanning for.
                val cat = FONT * 0.86f
                val cw = CanvasRenderer.textWidth(d.category, cat)
                CanvasRenderer.shadowedText(
                    dl, lx + sw - cw - 10f, ry + (ROW_H - cat) * 0.5f, cat,
                    fade(CATEGORY, alpha), d.category,
                )
                if (hovered && ImGui.isMouseClicked(ImGuiMouseButton.Left)) chosen = d
            }
            dl.popClipRect()
            scrollbar(dl, lx + sw - 4f, ly, listH, rows * ROW_H, scroll, alpha)
        }

        if (ImGui.isKeyPressed(ImGuiKey.Enter) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter)) {
            chosen = results.getOrNull(selected)
        }
        // A click anywhere outside dismisses, like any popup.
        if (ImGui.isMouseClicked(ImGuiMouseButton.Left) &&
            (mouseOutside(x, y, panelW, panelH))
        ) {
            close()
            return null
        }
        if (chosen != null) close()
        return chosen
    }

    private fun mouseOutside(x: Float, y: Float, w: Float, h: Float): Boolean {
        val m = ImGui.getMousePos()
        return m.x < x || m.x > x + w || m.y < y || m.y > y + h
    }

    /** Panel chrome: a drop shadow, a fill and a border. */
    private fun panel(dl: ImDrawList, x: Float, y: Float, w: Float, h: Float, alpha: Float) {
        for (i in 1..3) {
            val s = i * 2f
            dl.addRect(x - s, y - s, x + w + s, y + h + s, fade(SHADOW, alpha * 0.10f), 8f, ImDrawFlags.RoundCornersAll, s)
        }
        dl.addRectFilled(x, y, x + w, y + h, fade(PANEL_BG, alpha), 8f, ImDrawFlags.RoundCornersAll)
        dl.addRect(x, y, x + w, y + h, fade(BORDER, alpha), 8f, ImDrawFlags.RoundCornersAll, 1f)
    }

    /** A thin scrollbar with no track — visible when it matters, invisible when it does not. */
    private fun scrollbar(dl: ImDrawList, x: Float, y: Float, viewH: Float, contentH: Float, at: Float, alpha: Float) {
        if (contentH <= viewH) return
        val frac = viewH / contentH
        val barH = (viewH * frac).coerceAtLeast(20f)
        val barY = y + (at / (contentH - viewH)) * (viewH - barH)
        dl.addRectFilled(x, barY, x + 3f, barY + barH, fade(Theme.ACCENT, alpha * 0.7f), 1.5f)
    }

    /**
     * Rank the catalog against [q].
     *
     * With no query the list is the whole catalog grouped by category — browsing. With one it is fuzzy-
     * ranked, and the category stays on every row so you never lose the context you were browsing by.
     */
    private fun search(q: String, from: TypeRef?): List<NodeDescriptor> {
        val candidates = catalog.all.filter { from == null || accepts(it, from) }
        if (q.isBlank()) return candidates.sortedWith(compareBy({ it.category }, { it.title }))
        return candidates
            .mapNotNull { d -> FuzzySearch.score(d.title, q)?.let { d to it } }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /** True when [d] has any pin the dragged [type] could connect to, in either direction. */
    private fun accepts(d: NodeDescriptor, type: TypeRef): Boolean =
        d.inputs.any { canConnect(type, it.type) } || d.outputs.any { canConnect(it.type, type) }

    private fun fade(col: Int, a: Float): Int = Theme.withAlpha(col, a)

    private companion object {
        const val WIDTH = 310f

        /** Rows are given room to breathe: at 22px the list read as a wall of text. */
        const val ROW_H = 26f
        const val SEARCH_H = 28f
        const val PAD = 8f
        const val MAX_LIST_H = 300f
        const val FONT = 14f
        const val OPEN_SECONDS = 0.14f

        /** Per-frame approach fraction for the scroll — the smoothing that keeps a jump from being abrupt. */
        const val SCROLL_EASE = 0.35f

        /** Pixels per wheel notch — a little over two rows, so a flick moves a readable chunk. */
        const val WHEEL_STEP = 48f

        /** Movement below this is noise, not intent. */
        const val MOUSE_MOVE_EPS = 0.5f

        val PANEL_BG = Theme.col(0x1A, 0x1D, 0x27, 0xFA)
        val BORDER = Theme.col(0x3E, 0x46, 0x5C)
        val FIELD_BG = Theme.col(0x11, 0x14, 0x1C)
        val SHADOW = Theme.col(0x00, 0x00, 0x00)

        /** Selected and hovered rows: white washes, like the toolbar's ghost buttons. */
        val ROW_SELECTED = Theme.col(0xFF, 0xFF, 0xFF, 0x14)
        val ROW_HOVER = Theme.col(0xFF, 0xFF, 0xFF, 0x0A)
        val CATEGORY = Theme.col(0x6E, 0x76, 0x88)
        val PLACEHOLDER = Theme.col(0x66, 0x6D, 0x7E)
    }
}
