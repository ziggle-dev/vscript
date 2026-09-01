package dev.ziggle.vscript.editor.graph

import dev.ziggle.imgui.TextEdit
import dev.ziggle.imgui.TextEditState
import dev.ziggle.imgui.TextFilter
import imgui.ImDrawList
import imgui.ImGui
import imgui.flag.ImDrawFlags
import imgui.flag.ImGuiKey
import imgui.flag.ImGuiMouseButton
import dev.ziggle.imgui.Theme
import dev.ziggle.imgui.EditorKeyboard
import dev.ziggle.vscript.editor.graph.PinStyle
import dev.ziggle.vscript.editor.host.EditorHost
import dev.ziggle.vscript.editor.host.IconRef
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.model.Types

/**
 * One thing you can pick.
 *
 * [sprite] is what to draw beside it, and [note] the small print on the right — for a scene thing that is
 * where in the world it is, which is far more use than its id when you are choosing between four doors
 * with the same name.
 */
class PickerRow(
    val value: Any,
    val label: String,
    val note: String = "",
    val icon: IconRef? = null,
    /**
     * A colour chip drawn where a sprite would go, or null.
     *
     * For a type, this is the colour its pins and wires are drawn in — so choosing one is choosing the
     * colour you are about to see all over the graph, and the picker can say so instead of leaving you to
     * find out. Ignored when [sprite] is set; nothing needs both.
     */
    val swatch: Int? = null,
)

/**
 * The search popup behind every id-shaped pin: items, NPCs, scene objects, skills.
 *
 * **Screen space, and a singleton.** A picker is chrome — it should be equally readable however far the
 * canvas is zoomed, and only one can be open at a time by definition, so the alternative (one per widget)
 * would be a lot of duplicated state to keep in step for no gain. Both the canvas and the outline sidebar
 * open the same one.
 *
 * Drawn with our own primitives rather than an ImGui combo, for the same reason the node fields are: an
 * item list that looks like Dear ImGui sitting on a canvas that does not is the seam this editor exists to
 * avoid. The behaviour is deliberately the palette's — type to filter, arrows to move, Enter to take —
 * because it is the same gesture and should not need learning twice.
 */
object ValuePicker {

    private var openFor: String? = null
    private var rows: List<PickerRow> = emptyList()
    private var source: ((String) -> List<PickerRow>)? = null
    private var anchorX = 0f
    private var anchorY = 0f
    private var selected = 0
    private var scroll = 0f
    private var lastMouseX = Float.NaN
    private var lastMouseY = Float.NaN

    private val query = TextEditState()

    /**
     * The frame it opened on.
     *
     * The click that OPENS the picker is still "clicked" when the popup renders later in the same frame,
     * and the cursor is on the field — which is outside the popup — so the dismiss-on-click-outside rule
     * fired instantly and it appeared for exactly one frame. A popup cannot be closed by the press that
     * summoned it.
     */
    private var openedFrame = -1

    val isOpen: Boolean get() = openFor != null

    /**
     * The widget id the picker is open for, or null.
     *
     * Public so each surface can tell whether the open picker is ITS one: exactly one thing may call
     * [render] per frame, and keying on the id means that is decided by who opened it rather than by an
     * ordering convention somebody has to remember.
     */
    val openId: String? get() = openFor

    fun isOpenFor(id: String): Boolean = openFor == id

    /** Open the picker for widget [id], anchored under its field. */
    fun open(id: String, x: Float, y: Float, search: (String) -> List<PickerRow>) {
        openFor = id
        anchorX = x
        anchorY = y
        source = search
        query.set("")
        rows = search("")
        selected = 0
        scroll = 0f
        lastMouseX = Float.NaN
        openedFrame = ImGui.getFrameCount()
        EditorKeyboard.claim(this)
        EditorHost.typed.clear()
    }

    fun close() {
        openFor = null
        source = null
        EditorKeyboard.release(this)
    }

    /**
     * Draw and drive it. Call once per frame, from the OUTERMOST surface, with the FOREGROUND draw list.
     *
     * Both of those matter. Drawn into a child window's list it is clipped to that child — the picker for a
     * variable's default was being trapped inside a 240px sidebar column, which is not a popup so much as a
     * rumour of one. The foreground list is clipped by nothing.
     *
     * @return the chosen value on the frame it is picked, else null.
     */
    fun render(dl: ImDrawList, clipX: Float, clipY: Float, clipW: Float, clipH: Float): Any? {
        val id = openFor ?: return null
        val search = source ?: return null
        if (!EditorKeyboard.holds(this)) {
            // Something else took the keyboard — the picker has no business staying up without it.
            close()
            return null
        }

        val mouse = ImGui.getMousePos()
        val moved = lastMouseX.isNaN() ||
            Math.abs(mouse.x - lastMouseX) > 0.5f || Math.abs(mouse.y - lastMouseY) > 0.5f
        lastMouseX = mouse.x
        lastMouseY = mouse.y

        if (TextEdit.cancelled()) {
            close()
            return null
        }
        val before = query.text
        TextEdit.handleKeys(query, TextFilter.ANY, EditorHost.typed.drain())
        if (query.text != before) {
            rows = search(query.text)
            selected = 0
            scroll = 0f
        }

        var keyboardMoved = query.text != before
        if (rows.isNotEmpty()) {
            if (ImGui.isKeyPressed(ImGuiKey.DownArrow, true)) {
                selected = (selected + 1) % rows.size
                keyboardMoved = true
            }
            if (ImGui.isKeyPressed(ImGuiKey.UpArrow, true)) {
                selected = (selected - 1 + rows.size) % rows.size
                keyboardMoved = true
            }
        }
        selected = selected.coerceIn(0, maxOf(0, rows.size - 1))

        // Fit to the space available, then place. Every bound here is clamped through maxOf/minOf rather
        // than a bare coerceIn: a popup taller or wider than the region it must sit in produces an INVERTED
        // range, and `coerceIn` throws on that — which took the whole panel's draw down with it.
        val chrome = SEARCH_H + PAD * 2
        val width = WIDTH.coerceAtMost(clipW).coerceAtLeast(MIN_W)
        val wantList = (rows.size * ROW_H).coerceAtMost(MAX_LIST_H)
        val maxH = (clipH - MARGIN).coerceAtLeast(chrome + ROW_H)
        val h = (chrome + wantList).coerceAtMost(maxH)
        val listH = (h - chrome).coerceAtLeast(0f)

        val x = anchorX.coerceIn(clipX, maxOf(clipX, clipX + clipW - width))
        // Flipped ABOVE the field when there is no room below it, the way any dropdown near the bottom of
        // a window does — sliding it up to fit instead would cover the thing it belongs to.
        val below = anchorY
        val y = if (below + h <= clipY + clipH) below
        else (anchorY - h - FLIP_GAP).coerceIn(clipY, maxOf(clipY, clipY + clipH - h))

        panel(dl, x, y, width, h)

        val sx = x + PAD
        val sy = y + PAD
        val sw = width - PAD * 2
        dl.addRectFilled(sx, sy, sx + sw, sy + SEARCH_H, FIELD_BG, 5f, ImDrawFlags.RoundCornersAll)
        dl.addLine(sx + 2f, sy + SEARCH_H - 0.5f, sx + sw - 2f, sy + SEARCH_H - 0.5f, Theme.ACCENT, 1.5f)
        if (query.length == 0) {
            CanvasRenderer.shadowedText(dl, sx + 8f, sy + (SEARCH_H - FONT) * 0.5f, FONT, PLACEHOLDER, "Search…")
        }
        TextEdit.draw(dl, query, sx, sy, sw, SEARCH_H, 8f, FONT)

        var chosen: Any? = null
        val lx = x + PAD
        val ly = sy + SEARCH_H + PAD

        val wheel = ImGui.getIO().mouseWheel
        if (wheel != 0f && inside(x, y, width, h)) scroll -= wheel * WHEEL_STEP
        if (keyboardMoved) {
            val viewTop = selected * ROW_H
            if (viewTop < scroll) scroll = viewTop
            if (viewTop + ROW_H > scroll + listH) scroll = viewTop + ROW_H - listH
        }
        scroll = scroll.coerceIn(0f, (rows.size * ROW_H - listH).coerceAtLeast(0f))

        if (rows.isEmpty()) {
            CanvasRenderer.shadowedText(dl, lx + 6f, ly + 5f, FONT, PLACEHOLDER, "no matches")
        } else {
            dl.pushClipRect(lx, ly, lx + sw, ly + listH, true)
            for ((i, row) in rows.withIndex()) {
                val ry = ly + i * ROW_H - scroll
                if (ry + ROW_H < ly || ry > ly + listH) continue
                val hovered = mouse.x >= lx && mouse.x <= lx + sw && mouse.y >= ry && mouse.y <= ry + ROW_H
                // Only a MOVING cursor takes the selection, or hover fights the arrow keys.
                if (hovered && moved) selected = i
                if (i == selected) {
                    dl.addRectFilled(lx, ry, lx + sw, ry + ROW_H, ROW_SELECTED, 4f, ImDrawFlags.RoundCornersAll)
                    dl.addRectFilled(lx, ry + 3f, lx + 2f, ry + ROW_H - 3f, Theme.ACCENT, 1f)
                }
                var textX = lx + 10f
                if (row.swatch != null && row.icon == null) {
                    // A filled disc the size of a pin, in the pin's own colour — the same shape and colour
                    // this choice will produce on the canvas, so the picker previews the result rather than
                    // just naming it.
                    val r = PinStyle.PIN_RADIUS
                    val cx = lx + 4f + (ROW_H - 4f) * 0.5f
                    dl.addCircleFilled(cx, ry + ROW_H * 0.5f, r, row.swatch, 16)
                    dl.addCircle(cx, ry + ROW_H * 0.5f, r, Theme.col(0x0E, 0x10, 0x16), 16, 1.5f)
                    textX = lx + ICON_COL
                }
                if (row.icon != null) {
                    val region = EditorHost.icons.region(row.icon)
                    if (region != null) {
                        // Fitted INSIDE the row rather than scaled to fill it: a wiki render is a tall
                        // portrait and stretching one to a square makes every NPC look melted.
                        val box = ROW_H - 4f
                        dl.addImage(region.texture, lx + 4f, ry + 2f, lx + 4f + box, ry + 2f + box, region.u0, region.v0, region.u1, region.v1)
                    }
                    textX = lx + ICON_COL
                }
                CanvasRenderer.shadowedText(dl, textX, ry + (ROW_H - FONT) * 0.5f, FONT, Theme.TEXT, row.label)
                if (row.note.isNotEmpty()) {
                    // The note gets what the label leaves: it is small print, so a long one is cut with an
                    // ellipsis (or dropped when there is no room at all) rather than drawn over the label.
                    val noteSize = FONT * 0.86f
                    val room = (lx + sw - 10f) - (textX + CanvasRenderer.textWidth(row.label, FONT) + 14f)
                    val note = fitNote(row.note, noteSize, room)
                    if (note.isNotEmpty()) {
                        val nw = CanvasRenderer.textWidth(note, noteSize)
                        CanvasRenderer.shadowedText(dl, lx + sw - nw - 10f, ry + (ROW_H - noteSize) * 0.5f, noteSize, NOTE, note)
                    }
                }
                if (hovered && ImGui.isMouseClicked(ImGuiMouseButton.Left)) chosen = row.value
            }
            dl.popClipRect()
        }

        if (ImGui.isKeyPressed(ImGuiKey.Enter) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter)) {
            chosen = rows.getOrNull(selected)?.value
        }
        if (ImGui.getFrameCount() != openedFrame &&
            ImGui.isMouseClicked(ImGuiMouseButton.Left) && !inside(x, y, width, h)
        ) {
            close()
            return null
        }
        if (chosen != null) close()
        return chosen
    }

    private fun inside(x: Float, y: Float, w: Float, h: Float): Boolean {
        val m = ImGui.getMousePos()
        return m.x >= x && m.x <= x + w && m.y >= y && m.y <= y + h
    }

    private fun panel(dl: ImDrawList, x: Float, y: Float, w: Float, h: Float) {
        for (i in 1..3) {
            val s = i * 2f
            dl.addRect(x - s, y - s, x + w + s, y + h + s, SHADOW, 8f, ImDrawFlags.RoundCornersAll, s)
        }
        dl.addRectFilled(x, y, x + w, y + h, PANEL_BG, 8f, ImDrawFlags.RoundCornersAll)
        dl.addRect(x, y, x + w, y + h, BORDER, 8f, ImDrawFlags.RoundCornersAll, 1f)
    }

    // ---- the corpora --------------------------------------------------------------------------------

    /**
     * Rows for [type], from the indexes the config pickers already use.
     *
     * A blank query browses rather than showing nothing — for skills that is the whole list, and for the
     * big catalogues it is the first page, so the popup is never an empty box waiting to be told what to do.
     */
    /**
     * Rows for [type], from whatever catalogue the host has for it.
     *
     * Takes the `TypeRef` and asks directly. It used to funnel through `type.builtin`, which was fine
     * while the id types WERE builtins and is exactly wrong now they are the node pack's: a named type
     * has no builtin, so every domain type fell out of the picker before the host was ever asked.
     */
    /** [note] cut to [room] pixels at [size] with an ellipsis; empty when even a few characters would not fit. */
    internal fun fitNote(note: String, size: Float, room: Float): String {
        if (room < size * 2f) return ""
        if (CanvasRenderer.textWidth(note, size) <= room) return note
        var keep = note.length
        while (keep > 0) {
            keep--
            val cut = note.substring(0, keep).trimEnd() + "…"
            if (CanvasRenderer.textWidth(cut, size) <= room) return if (keep < 3) "" else cut
        }
        return ""
    }

    fun rowsFor(type: TypeRef, query: String): List<PickerRow> {
        val catalog = EditorHost.values.catalogFor(type) ?: return emptyList()
        val hits = if (query.isBlank()) catalog.browse(BROWSE) else catalog.search(query, LIMIT)
        return hits.map { PickerRow(it.value, it.label, it.note, it.icon) }
    }

    /**
     * The TYPE picker: choose what a list holds, what a variable is, what a function's pin carries.
     *
     * One list serving all four, because they are one question. They had each grown their own answer — the
     * outline offered a menu of `npc`/`object` in enum-name case, the canvas cycled a chip through nine
     * options one click at a time — and neither showed you what a type was for or what it would look like.
     *
     * [value] decides what a chosen row hands back, because the callers want different things from the same
     * choice: a variable's type IS a [PinType], while a list's `Of` is stored as the type's NAME so the
     * document stays readable. Defaulting to the type itself keeps the common case quiet.
     */
    fun typeRows(
        types: List<TypeRef>,
        query: String,
        value: (TypeRef) -> Any = { it },
    ): List<PickerRow> {
        val q = query.trim()
        return types
            .filter {
                q.isEmpty() ||
                    Types.label(it).contains(q, true) ||
                    Types.describe(it).contains(q, true)
            }
            .map {
                PickerRow(
                    value = value(it),
                    label = Types.label(it),
                    note = Types.describe(it),
                    swatch = PinStyle.color(it),
                )
            }
    }

    /** What a stored value should read as. Falls back to the bare id when the catalogue has no name. */
    fun labelFor(type: TypeRef, value: Any?): String {
        if (value == null) return ""
        return EditorHost.values.catalogFor(type)?.labelOf(value) ?: value.toString()
    }

    /** The same, for a caller that still holds a builtin. */
    fun labelFor(type: PinType, value: Any?): String = labelFor(TypeRef(type), value)

    /** The same, for a caller that still holds a builtin. */
    fun rowsFor(type: PinType, query: String): List<PickerRow> = rowsFor(TypeRef(type), query)

    /**
     * A larger picture of what is currently selected, drawn beside a field while it is hovered.
     *
     * To the LEFT of the field, because the fields sit on the right-hand edge of a node and anything drawn
     * to their right would leave the canvas. Only on hover: a graph with thirty item pins is a wall of
     * thumbnails, and the picture is a confirmation rather than something you read continuously.
     */
    fun preview(dl: ImDrawList, icon: IconRef, fieldX: Float, fieldY: Float) {
        val region = EditorHost.icons.region(icon) ?: return
        val x1 = fieldX - PREVIEW_GAP
        val x0 = x1 - PREVIEW
        val y0 = fieldY - PREVIEW * 0.5f
        val y1 = y0 + PREVIEW
        dl.addRectFilled(x0 - 4f, y0 - 4f, x1 + 4f, y1 + 4f, PANEL_BG, 6f, ImDrawFlags.RoundCornersAll)
        dl.addRect(x0 - 4f, y0 - 4f, x1 + 4f, y1 + 4f, BORDER, 6f, ImDrawFlags.RoundCornersAll, 1f)
        dl.addImage(region.texture, x0, y0, x1, y1, region.u0, region.v0, region.u1, region.v1)
    }

    private const val PREVIEW = 96f
    private const val PREVIEW_GAP = 12f

    private const val WIDTH = 280f
    private const val MIN_W = 140f

    /** Space kept clear of the viewport edge, and the gap left when the popup flips above its field. */
    private const val MARGIN = 12f
    private const val FLIP_GAP = 22f
    private const val ROW_H = 26f
    private const val ICON_COL = 30f
    private const val SEARCH_H = 26f
    private const val PAD = 8f
    private const val MAX_LIST_H = 260f
    private const val FONT = 14f
    private const val WHEEL_STEP = 48f

    /** How many rows a blank query browses, and how many a search returns. Both bounded: the item corpus
     *  is ~16k rows and laying all of them out would make the popup the slowest thing on screen. */
    private const val BROWSE = 60
    private const val LIMIT = 60

    private val PANEL_BG = Theme.col(0x1A, 0x1D, 0x27, 0xFA)
    private val BORDER = Theme.col(0x3E, 0x46, 0x5C)
    private val FIELD_BG = Theme.col(0x11, 0x14, 0x1C)
    private val SHADOW = Theme.col(0x00, 0x00, 0x00, 0x1A)
    private val ROW_SELECTED = Theme.col(0xFF, 0xFF, 0xFF, 0x14)
    private val PLACEHOLDER = Theme.col(0x66, 0x6D, 0x7E)
    private val NOTE = Theme.col(0x6E, 0x76, 0x88)
}
