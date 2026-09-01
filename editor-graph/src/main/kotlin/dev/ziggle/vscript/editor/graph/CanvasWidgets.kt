package dev.ziggle.vscript.editor.graph

import dev.ziggle.imgui.TextEdit
import dev.ziggle.imgui.TextEditState
import dev.ziggle.imgui.TextFilter
import dev.ziggle.imgui.TextLayout
import imgui.ImDrawList
import imgui.ImGui
import imgui.flag.ImDrawFlags
import imgui.flag.ImGuiMouseButton
import imgui.flag.ImGuiKey
import dev.ziggle.imgui.Theme
import dev.ziggle.imgui.EditorKeyboard

/**
 * Hand-drawn, immediate-mode widgets for the node canvas.
 *
 * **Why not ImGui's own.** ImGui widgets do not scale: they are laid out in screen pixels against the
 * current font, so inside a canvas that zooms they either stay a fixed size while the node grows around
 * them, or have to be repositioned every frame with `setCursorScreenPos` and still render at the wrong
 * size. They also bring their own visual language, which is exactly what owning the canvas was meant to
 * escape. These take their geometry in graph space, scale with the camera, and look like the rest of it.
 *
 * **The interaction model.** One [WidgetContext] per frame owns the shared state a set of immediate-mode
 * widgets needs — what is hot (under the cursor), what is active (being pressed or dragged), and which
 * field owns the keyboard. Widgets are pure functions of that state plus their own value; they return the
 * new value and never mutate the document, so the caller decides what an edit means.
 *
 * **Ids are caller-supplied strings** rather than derived from call order, so inserting a widget cannot
 * silently transfer another one's active state — the bug that makes immediate-mode UIs mysteriously lose a
 * drag halfway through.
 */
class WidgetContext {

    /**
     * The draw list and camera for the CURRENT frame, rebound by [beginFrame].
     *
     * They change every frame, but the context itself must NOT — hot/active/focused only mean anything
     * across frames. Constructing a fresh context per frame (which is the obvious thing to do when the
     * draw list is a per-frame object) silently wipes them, and the symptom is that click-only widgets
     * work while anything needing a drag or keyboard focus does nothing at all.
     */
    lateinit var dl: ImDrawList
        private set
    lateinit var cam: ScreenCamera
        private set

    /** The widget under the cursor this frame. */
    var hot: String? = null
        private set

    /** The widget being pressed or dragged. Survives the cursor leaving it, which is what makes a drag
     *  keep tracking when you overshoot. */
    var active: String? = null
        internal set

    /** The text field with keyboard focus, if any. */
    var focused: String? = null
        internal set

    /** Editing state for [focused]; committed on Enter or focus loss, discarded on Escape. */
    val edit = TextEditState()

    /** What the focused field will accept. */
    internal var filter: TextFilter = TextFilter.ANY

    /**
     * The focused field's wrapped layout, when it is a multi-line one.
     *
     * Published by whoever DRAWS the field, because only the draw site knows its width and font size, and
     * consumed by [CanvasWidgets.pumpKeyboard] to give Up/Down/Home/End something two-dimensional to walk.
     * Cleared every frame, so a single-line field simply never sets it and gets one-line behaviour by
     * omission rather than by a flag someone has to remember to clear.
     */
    internal var editLayout: TextLayout? = null

    /** True when a widget consumed this frame's click, so the canvas must not also act on it. */
    var consumedClick: Boolean = false
        internal set

    /** True when a text field owns the keyboard, so canvas shortcuts (Delete) must stand down. */
    val keyboardCaptured: Boolean get() = focused != null

    private var mouseX = 0f
    private var mouseY = 0f
    private var canvasHovered = false

    /** How far the active widget has been dragged, in pixels. Distinguishes a click from a drag. */
    internal var activeDragPx = 0f

    /**
     * A composite widget took this frame's Ctrl+C / Ctrl+V.
     *
     * Copying a TILE means copying all three of its fields, not the digits in the one with the caret — so
     * the widget handles the shortcut and tells the text editor to leave it alone. Without this both act:
     * the tile writes "3200,3200,0" to the clipboard and the field immediately overwrites it with "3200".
     */
    internal var suppressClipboard = false

    /** One field Tab can land on: its id, what it currently shows, and what it will accept. */
    internal class TabStop(val id: String, val text: String, val filter: TextFilter)

    /**
     * The editable fields drawn this frame, in draw order — which is reading order, and therefore the
     * order Tab should walk them in.
     *
     * Collected rather than declared, because nothing knows the whole set in advance: a node's fields are
     * its pins, a list's are however many slots it has right now, and both change as you edit. Whoever
     * draws a field is the only thing that knows it exists, so registration happens there.
     *
     * [tabOrderPrev] is the completed list from the LAST frame. Tab is handled before this frame draws
     * anything, so the current list is still empty at that point; last frame's is the one that is whole.
     */
    private val tabOrder = ArrayList<TabStop>()
    private var tabOrderPrev: List<TabStop> = emptyList()

    /** Register a field as a Tab stop. Called by the field widgets as they draw. */
    internal fun tabStop(id: String, text: String, filter: TextFilter) {
        if (tabOrder.none { it.id == id }) tabOrder.add(TabStop(id, text, filter))
    }

    /** The stop Tab (or Shift+Tab) should move to from [from], or null when [from] is not in the ring. */
    internal fun tabTarget(from: String, back: Boolean): TabStop? {
        val i = tabOrderPrev.indexOfFirst { it.id == from }
        if (i < 0 || tabOrderPrev.size < 2) return null
        val step = if (back) -1 else 1
        return tabOrderPrev[(i + step + tabOrderPrev.size) % tabOrderPrev.size]
    }

    fun beginFrame(dl: ImDrawList, cam: ScreenCamera, canvasHovered: Boolean) {
        tabOrderPrev = if (tabOrder.isEmpty()) tabOrderPrev else tabOrder.toList()
        tabOrder.clear()
        this.dl = dl
        this.cam = cam
        val m = ImGui.getMousePos()
        mouseX = m.x
        mouseY = m.y
        this.canvasHovered = canvasHovered
        hot = null
        consumedClick = false
        editLayout = null
        suppressClipboard = false
    }

    /**
     * Release the active widget once the button is up.
     *
     * Deliberately at the END of the frame, not the start: clearing it up front meant the widget never saw
     * the frame its button was released on, so a click-to-type never fired — the release was consumed
     * before anything could react to it.
     */
    fun endFrame() {
        if (!ImGui.isMouseDown(ImGuiMouseButton.Left)) {
            active = null
            activeDragPx = 0f
        }
    }

    /** Drop keyboard focus — used when something else (the palette) takes over the keyboard. */
    fun clearFocus() {
        focused = null
        dev.ziggle.imgui.EditorKeyboard.release(this)
    }

    /** Focus [id] for editing, seeded with [value]. Clears stale keystrokes so none land in the new field. */
    internal fun beginEdit(id: String, value: String, filter: TextFilter, selectAll: Boolean) {
        focused = id
        this.filter = filter
        edit.set(value, selectAll)
        // Claimed globally: there is more than one of these contexts now (the canvas and the outline), and
        // exactly one buffer of typed characters between them. See EditorKeyboard.
        dev.ziggle.imgui.EditorKeyboard.claim(this)
        dev.ziggle.vscript.editor.host.EditorHost.typed.clear()
    }

    internal fun hit(id: String, x: Float, y: Float, w: Float, h: Float): Boolean {
        val over = canvasHovered && mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h
        if (over && active == null) hot = id
        return over
    }

    internal fun mx() = mouseX
    internal fun my() = mouseY
}

/**
 * The widgets themselves.
 *
 * Each takes a screen-space rect (the caller has already applied the camera) and returns whether the value
 * changed. Sizes come in already scaled, so a widget never consults the camera for layout — only for line
 * weights, which must not vanish when zoomed out.
 */
object CanvasWidgets {

    // ---- palette ----------------------------------------------------------------------------------

    private val FIELD_BG = Theme.col(0x14, 0x17, 0x1F, 0xFF)
    private val FIELD_BG_HOT = Theme.col(0x1B, 0x1F, 0x2A, 0xFF)
    private val FIELD_BORDER = Theme.col(0x38, 0x3F, 0x52)
    private val FIELD_BORDER_HOT = Theme.col(0x55, 0x60, 0x7C)
    private val FIELD_BORDER_FOCUS = Theme.ACCENT
    private val KNOB = Theme.col(0xE8, 0xEC, 0xF5)
    private val TRACK_ON = Theme.ACCENT
    private val TRACK_OFF = Theme.col(0x33, 0x39, 0x4A)

    private const val ROUND = 3f

    /** How close an expanded field may come to the window edge before it stops growing and slides. */
    private const val OVERFLOW_MARGIN = 12f

    /** A soft drop shadow, so an expanded field reads as floating over the canvas rather than part of it. */
    private val OVERFLOW_SHADOW = Theme.col(0x00, 0x00, 0x00, 0x66)

    /**
     * The frame every value widget sits in.
     *
     * Drawn as one call rather than per-widget so a checkbox, a number and a text field are visibly the
     * same kind of control — consistency here is most of what makes a dense node look designed rather than
     * assembled.
     */
    private fun field(
        ctx: WidgetContext,
        id: String,
        x: Float, y: Float, w: Float, h: Float,
        focused: Boolean,
        /** Where to draw. An overflowing field paints into the foreground list — see [editRect]. */
        dl: ImDrawList = ctx.dl,
    ) {
        val hot = ctx.hot == id || ctx.active == id
        val r = ctx.cam.px(ROUND)
        dl.addRectFilled(x, y, x + w, y + h, if (hot) FIELD_BG_HOT else FIELD_BG, r, ImDrawFlags.RoundCornersAll)
        val border = when {
            focused -> FIELD_BORDER_FOCUS
            hot -> FIELD_BORDER_HOT
            else -> FIELD_BORDER
        }
        dl.addRect(x, y, x + w, y + h, border, r, ImDrawFlags.RoundCornersAll, maxOf(1f, ctx.cam.px(1f)))
    }

    /** Where a field is actually drawn this frame — see [editRect]. */
    private class FieldRect(val x: Float, val y: Float, val w: Float, val h: Float, val floating: Boolean)

    /**
     * The rectangle a FOCUSED field occupies, which is not always the one it was given.
     *
     * A value longer than its box used to be edited entirely through the caret: the text scrolled, so you
     * could only ever see the handful of characters around wherever the caret happened to be, and finding
     * anything meant arrowing across it and remembering what went past. A node's fields are narrow by
     * design — the node is sized to its pins, not to its longest value — so this is the normal case for
     * anything like a template or an action name, not an edge case.
     *
     * So while it is focused, a field that has outgrown itself GROWS to fit its contents and floats above
     * the canvas. It stays anchored at its own left edge, so the text does not jump when the panel opens,
     * and slides left only as far as it must to stay on screen.
     */
    private fun editRect(
        ctx: WidgetContext,
        x: Float, y: Float, w: Float, h: Float,
        pad: Float,
        fontSize: Float,
    ): FieldRect {
        // Room for the caret past the last glyph, or it sits on the border and the field looks one short.
        val needed = CanvasRenderer.textWidth(ctx.edit.text, fontSize) + pad * 2 + maxOf(2f, fontSize * 0.25f)
        if (needed <= w) return FieldRect(x, y, w, h, floating = false)

        val (ex, ew) = overflowGeometry(needed, x, w, ImGui.getIO().displaySizeX)
        return FieldRect(ex, y, ew, h, floating = true)
    }

    /**
     * Where an expanded field goes: its left edge and its width, given how much room the content wants.
     *
     * Split out from [editRect] with the screen width passed in so the placement rules are testable without
     * a live ImGui — the arithmetic is where this can go wrong (a field that grows off the right of the
     * window, or one clamped to a negative width), and none of that is visible in a screenshot until it is.
     *
     * @return `x to width`.
     */
    internal fun overflowGeometry(needed: Float, x: Float, w: Float, screenW: Float): Pair<Float, Float> {
        val maxW = (screenW - OVERFLOW_MARGIN * 2).coerceAtLeast(w)
        val ew = needed.coerceIn(w, maxW)
        // Anchored left, pushed left only as far as it must be. Centring it instead would move the text out
        // from under the cursor at the exact moment you clicked into it.
        val ex = x.coerceAtMost(screenW - OVERFLOW_MARGIN - ew).coerceAtLeast(OVERFLOW_MARGIN)
        return ex to ew
    }

    /**
     * Click to place the caret, drag to select — in a one-line field.
     *
     * The comment heading and body have had this since they were written; the pin fields never did, so a
     * click anywhere in one dropped the caret at the end and selecting meant Shift and the arrow keys. The
     * geometry passed in must be the field's ACTUAL rect this frame, which for a focused overflowing field
     * is the expanded one — hit-testing the original box would make the visible overflow unclickable.
     */
    internal fun singleLineMouse(
        ctx: WidgetContext,
        id: String,
        over: Boolean,
        x: Float, pad: Float,
        fontSize: Float,
    ) {
        fun place(extend: Boolean) {
            val localX = ImGui.getMousePos().x - TextEdit.singleLineTextX(ctx.edit, x, pad)
            ctx.edit.moveTo(TextEdit.caretAtX(ctx.edit.text, localX, fontSize), extend)
        }
        if (over && ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
            ctx.active = id
            ctx.consumedClick = true
            place(ImGui.getIO().keyShift)
        } else if (ctx.active == id && ImGui.isMouseDown(ImGuiMouseButton.Left)) {
            // Extending even when the cursor has left the field, which is what makes a drag that overshoots
            // keep selecting rather than stopping at the edge.
            place(extend = true)
        }
    }

    /** Centre [text] vertically in a field and clip it to the width. */
    private fun fieldText(ctx: WidgetContext, x: Float, y: Float, w: Float, h: Float, size: Float, col: Int, text: String) {
        if (size < 5f) return
        val pad = ctx.cam.px(5f)
        ctx.dl.pushClipRect(x + pad, y, x + w - pad, y + h, true)
        CanvasRenderer.shadowedText(ctx.dl, x + pad, y + (h - size) * 0.5f, size, col, text)
        ctx.dl.popClipRect()
    }

    // ---- checkbox ---------------------------------------------------------------------------------

    /**
     * A toggle drawn as a track and knob rather than a tick box.
     *
     * At the sizes a node pin row allows, a tick is a few pixels of glyph and reads as noise; a track reads
     * as on/off from its silhouette alone, which survives being zoomed out.
     */
    fun toggle(ctx: WidgetContext, id: String, x: Float, y: Float, w: Float, h: Float, value: Boolean): Boolean {
        val over = ctx.hit(id, x, y, w, h)
        var v = value
        if (over && ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
            v = !v
            ctx.consumedClick = true
        }
        val trackH = h * 0.72f
        val trackY = y + (h - trackH) * 0.5f
        val trackW = minOf(w, h * 1.9f)
        ctx.dl.addRectFilled(x, trackY, x + trackW, trackY + trackH, if (v) TRACK_ON else TRACK_OFF, trackH * 0.5f)
        val kr = trackH * 0.5f - ctx.cam.px(1.5f)
        val kx = if (v) x + trackW - kr - ctx.cam.px(2f) else x + kr + ctx.cam.px(2f)
        ctx.dl.addCircleFilled(kx, trackY + trackH * 0.5f, kr, KNOB)
        return v
    }

    // ---- read-only --------------------------------------------------------------------------------

    /**
     * A field that shows a value but is not typed into — what an id-shaped pin gets.
     *
     * It looks like the other fields on purpose: it is the same kind of thing (a place a value lives), and
     * making it look inert would suggest the value cannot be changed when clicking opens a catalogue.
     */
    fun readonly(
        ctx: WidgetContext,
        id: String,
        x: Float, y: Float, w: Float, h: Float,
        text: String,
        fontSize: Float,
        placeholder: String = "",
    ) {
        field(ctx, id, x, y, w, h, focused = false)
        val col = if (text.isEmpty()) Theme.TEXT_DIM else Theme.TEXT
        fieldText(ctx, x, y, w, h, fontSize, col, text.ifEmpty { placeholder })
    }

    // ---- choice -----------------------------------------------------------------------------------

    /**
     * A one-of-N chip that cycles on click.
     *
     * Not a dropdown. A popup list inside a zooming canvas has to be drawn in screen space, positioned
     * against the node it belongs to and dismissed on the next click — a lot of machinery for a control
     * whose whole job here is picking one of three severities. Cycling shows the current value at all
     * times, needs no second surface, and reads correctly at any zoom; right-click steps backwards so a
     * long option list is still navigable in both directions.
     */
    fun choice(
        ctx: WidgetContext,
        id: String,
        x: Float, y: Float, w: Float, h: Float,
        value: String,
        options: List<String>,
        fontSize: Float,
    ): String {
        if (options.isEmpty()) return value
        val over = ctx.hit(id, x, y, w, h)
        var v = value.takeIf { it in options } ?: options.first()
        if (over && ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
            v = options[(options.indexOf(v) + 1) % options.size]
            ctx.consumedClick = true
        } else if (over && ImGui.isMouseClicked(ImGuiMouseButton.Right)) {
            v = options[(options.indexOf(v) - 1 + options.size) % options.size]
            ctx.consumedClick = true
        }
        field(ctx, id, x, y, w, h, focused = false)
        val tw = CanvasRenderer.textWidth(v, fontSize)
        CanvasRenderer.shadowedText(ctx.dl, x + (w - tw) * 0.5f, y + (h - fontSize) * 0.5f, fontSize, Theme.TEXT, v)
        if (over) ImGui.setTooltip("click to cycle · right-click to go back")
        return v
    }

    // ---- tile -------------------------------------------------------------------------------------

    /**
     * A world tile: three number fields that behave as one value.
     *
     * Three fields rather than one comma-separated box, because x, y and plane are three different things
     * and nudging the y of a tile should not mean re-typing all of it. What they give up is the ability to
     * paste one — so they take it back explicitly: **Ctrl+C** copies the whole tile as `x,y,plane` from
     * whichever field has focus, **Ctrl+V** fills all three from anything comma separated (plane defaults
     * to 0 when omitted, which is how tiles are usually written), and **Tab** walks between them.
     *
     * @return the new `"x,y,plane"` when something changed, else null.
     */
    fun fields(
        ctx: WidgetContext,
        id: String,
        x: Float, y: Float, w: Float, h: Float,
        value: Any?,
        fontSize: Float,
        /** One per box, in order — the record's own field names, shown as tooltips. */
        labels: List<String> = TILE_LABELS,
        /** Whether pasted text reads as a value of this type; null rejects the paste. */
        accepts: (String) -> String? = { it.ifBlank { null } },
    ): String? {
        val parts = components(value, labels.size)
        val ids = Array(labels.size) { "$id#$it" }
        val focusIdx = ids.indexOfFirst { it == ctx.focused }
        var changed = false

        if (focusIdx >= 0) {
            val io = ImGui.getIO()
            if (io.keyCtrl && ImGui.isKeyPressed(ImGuiKey.C)) {
                runCatching { ImGui.setClipboardText(joined(parts)) }
                ctx.suppressClipboard = true
            }
            if (io.keyCtrl && ImGui.isKeyPressed(ImGuiKey.V)) {
                val pasted = runCatching { ImGui.getClipboardText() }.getOrNull()
                accepts(pasted ?: "")?.let { text ->
                    val fresh = components(text, labels.size)
                    fresh.copyInto(parts)
                    changed = true
                    // The focused field is re-seeded so what you see is what was pasted, not the digits
                    // that were there before it.
                    ctx.beginEdit(ids[focusIdx], parts[focusIdx].toString(), TextFilter.INTEGER, selectAll = true)
                }
                ctx.suppressClipboard = true
            }
            if (ImGui.isKeyPressed(ImGuiKey.Tab)) {
                // COMMIT before moving. beginEdit re-seeds the shared edit buffer, so tabbing away without
                // taking the text first threw away everything typed into the field you were leaving — it
                // looked like the value reverted to zero, because the old value is what got redrawn.
                ctx.edit.text.trim().toIntOrNull()?.let { typed ->
                    val v = if (focusIdx == PLANE) typed.coerceIn(0, 3) else typed
                    if (v != parts[focusIdx]) {
                        parts[focusIdx] = v
                        changed = true
                    }
                }
                val step = if (ImGui.getIO().keyShift) -1 else 1
                val next = (focusIdx + step + ids.size) % ids.size
                ctx.beginEdit(ids[next], parts[next].toString(), TextFilter.INTEGER, selectAll = true)
            }
        }

        val gap = maxOf(1f, ctx.cam.px(3f))
        val fw = (w - gap * 2f) / 3f
        for (i in 0..2) {
            val before = parts[i].toDouble()
            val fx = x + i * (fw + gap)
            // The plane is 0..3 and nothing else — the game has four, and a tile on plane 9 is a typo the
            // field can simply refuse rather than a value to discover is wrong later.
            val lo = if (i == PLANE) 0.0 else -Double.MAX_VALUE
            val hi = if (i == PLANE) 3.0 else Double.MAX_VALUE
            val v = number(ctx, ids[i], fx, y, fw, h, before, fontSize, 1.0, 0, lo, hi)
            if (v != before) {
                parts[i] = v.toInt()
                changed = true
            }
            // Three bare number boxes do not say which is which, and the order is not guessable from the
            // outside. A tooltip is the cheapest way to answer it without spending width on labels.
            if (ctx.hot == ids[i]) ImGui.setTooltip(labels[i])
        }
        return if (changed) joined(parts) else null
    }

    // ---- number -----------------------------------------------------------------------------------

    /**
     * A drag-to-change number.
     *
     * Dragging is the primary gesture and a click opens text entry, which is the right way round for a
     * value you usually nudge and occasionally type. The drag accumulates in SCREEN pixels and is divided
     * by zoom, so a given hand movement changes the value by the same amount however far you are zoomed
     * in — otherwise the control's sensitivity would silently depend on the view.
     */
    fun number(
        ctx: WidgetContext,
        id: String,
        x: Float, y: Float, w: Float, h: Float,
        value: Double,
        fontSize: Float,
        step: Double = 1.0,
        decimals: Int = 0,
        min: Double = -Double.MAX_VALUE,
        max: Double = Double.MAX_VALUE,
    ): Double {
        val filter = if (decimals > 0) TextFilter.DECIMAL else TextFilter.INTEGER
        ctx.tabStop(id, format(value.coerceIn(min, max), decimals), filter)
        val over = ctx.hit(id, x, y, w, h)
        var v = value.coerceIn(min, max)

        if (ctx.focused == id) {
            field(ctx, id, x, y, w, h, focused = true)
            TextEdit.draw(ctx.dl, ctx.edit, x, y, w, h, ctx.cam.px(5f), fontSize)
            return v
        }

        if (over && ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
            ctx.active = id
            ctx.consumedClick = true
            ctx.activeDragPx = 0f
            dragAccum = 0f
            ImGui.resetMouseDragDelta(ImGuiMouseButton.Left)
        }
        if (ctx.active == id) {
            if (ImGui.isMouseDown(ImGuiMouseButton.Left)) {
                // lockThreshold 0. The default ALSO suppresses the delta until ImGui's drag threshold is
                // passed — and because this resets the delta every frame, the origin kept moving and the
                // threshold was never reached, so the value never changed at all.
                val d = ImGui.getMouseDragDelta(ImGuiMouseButton.Left, 0f)
                if (d.x != 0f || d.y != 0f) {
                    ImGui.resetMouseDragDelta(ImGuiMouseButton.Left)
                    ctx.activeDragPx += Math.abs(d.x) + Math.abs(d.y)
                    dragAccum += d.x / ctx.cam.zoom
                    val whole = (dragAccum / DRAG_PX_PER_STEP).toInt()
                    if (whole != 0) {
                        // Clamped as it moves rather than at the end, so dragging a bounded field parks at
                        // the limit instead of running up an invisible balance you then have to drag back.
                        v = (v + whole * step).coerceIn(min, max)
                        dragAccum -= whole * DRAG_PX_PER_STEP
                    }
                }
            } else if (ctx.activeDragPx < CLICK_SLOP) {
                // Released without really moving: treat it as a click and open text entry. Measured on
                // total travel rather than the final delta, which a per-frame reset would always report
                // as zero however far you had dragged.
                //
                // The whole value is selected on entry: a number you click into is almost always one you
                // mean to replace, not append to.
                ctx.beginEdit(id, format(v, decimals), if (decimals > 0) TextFilter.DECIMAL else TextFilter.INTEGER, selectAll = true)
                ctx.active = null
            }
        }

        field(ctx, id, x, y, w, h, focused = false)
        fieldText(ctx, x, y, w, h, fontSize, Theme.TEXT, format(v, decimals))
        return v
    }

    /** Index of the plane component in a tile: x, y, then plane. */
    private const val PLANE = 2
    /**
     * The stored text split into [n] whole numbers, padded with zeroes.
     *
     * **Comma separated, and that is the contract of [Editor.FIELDS] rather than a fact about tiles.**
     * This used to call the node pack's tile helper, which put one game's coordinate parser inside the
     * canvas — a domain dependency in a surface that is supposed to open against any domain. A small
     * record of numbers is written as its numbers in order; nothing here needs to know what they mean.
     *
     * Never fails: a half-typed value is still editable, which is the whole reason the boxes exist.
     */
    private fun components(value: Any?, n: Int): IntArray {
        val out = IntArray(n)
        val text = value?.toString() ?: return out
        val split = text.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        for (i in 0 until minOf(split.size, n)) out[i] = split[i].toIntOrNull() ?: 0
        return out
    }

    private fun joined(parts: IntArray): String = parts.joinToString(",")

    /** The default labels, for the type this widget was originally written for. */
    private val TILE_LABELS = listOf("x", "y", "z")

    private var dragAccum = 0f
    private const val DRAG_PX_PER_STEP = 6f

    /** Total travel below which a press-and-release counts as a click rather than a drag. */
    private const val CLICK_SLOP = 3f

    private fun format(v: Double, decimals: Int): String =
        if (decimals <= 0) v.toLong().toString() else String.format("%.${decimals}f", v)

    // ---- text -------------------------------------------------------------------------------------

    /** A click-to-edit text field. Returns the current value; the caller commits on [commitFocused]. */
    fun text(
        ctx: WidgetContext,
        id: String,
        x: Float, y: Float, w: Float, h: Float,
        value: String,
        fontSize: Float,
        placeholder: String = "",
    ): String {
        ctx.tabStop(id, value, TextFilter.ANY)
        val pad = ctx.cam.px(5f)
        val wasFocused = ctx.focused == id
        // Focused, the field may be wider than it was asked to be — so every hit test below uses the rect
        // it is actually drawn at this frame, not the one the node reserved for it.
        val r = if (wasFocused) editRect(ctx, x, y, w, h, pad, fontSize) else FieldRect(x, y, w, h, false)
        val over = ctx.hit(id, r.x, r.y, r.w, r.h)

        // Double-click selects the lot; a single click focuses and puts the caret WHERE YOU CLICKED.
        // Selecting on a double click is what makes "click and retype" fast without stealing the ability
        // to append, and placing on a single one is what everything else that looks like a text box does.
        if (over && ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) {
            ctx.beginEdit(id, value, TextFilter.ANY, selectAll = true)
            ctx.active = id
            ctx.consumedClick = true
        } else if (over && ImGui.isMouseClicked(ImGuiMouseButton.Left) && !wasFocused) {
            ctx.beginEdit(id, value, TextFilter.ANY, selectAll = false)
            singleLineMouse(ctx, id, over, r.x, pad, fontSize)
        } else if (wasFocused) {
            singleLineMouse(ctx, id, over, r.x, pad, fontSize)
        }

        val focused = ctx.focused == id
        if (focused) {
            // Re-measured AFTER the click, because focusing loads the value and this frame's typing may have
            // changed its width — a rect from before either would be one frame stale, which shows up as the
            // panel lagging a character behind what you type.
            val fr = editRect(ctx, x, y, w, h, pad, fontSize)
            // Above the canvas, so the nodes drawn after this one cannot paint over the panel. Nothing else
            // can be focused at the same time, so there is no ordering to arbitrate between fields.
            val dl = if (fr.floating) ImGui.getForegroundDrawList() else ctx.dl
            if (fr.floating) {
                dl.addRectFilled(
                    fr.x + 2f, fr.y + 3f, fr.x + fr.w + 2f, fr.y + fr.h + 3f,
                    OVERFLOW_SHADOW, ctx.cam.px(ROUND), ImDrawFlags.RoundCornersAll,
                )
            }
            field(ctx, id, fr.x, fr.y, fr.w, fr.h, true, dl)
            TextEdit.draw(dl, ctx.edit, fr.x, fr.y, fr.w, fr.h, pad, fontSize)
            return ctx.edit.text
        }
        // Unfocused: the plate and border, in its own rect. Drawing this ONLY while focused turned every
        // resting field into a bare label — no box, and no hover response to say it could be typed into.
        field(ctx, id, x, y, w, h, focused = false)
        val col = if (value.isEmpty()) Theme.TEXT_DIM else Theme.TEXT
        fieldText(ctx, x, y, w, h, fontSize, col, value.ifEmpty { placeholder })
        return value
    }

    /**
     * Feed typed characters and editing keys into the focused field.
     *
     * Call once per frame before drawing. Returns the committed value when the edit ends (Enter or a click
     * away), or null while it is still in progress — so a caller writes to the document exactly once,
     * rather than on every keystroke, which would fill the undo stack with one entry per character.
     */
    fun pumpKeyboard(ctx: WidgetContext): Pair<String, String>? {
        val id = ctx.focused ?: run {
            // Nothing focused HERE: drop anything typed so it cannot arrive in the next field that opens —
            // but only if nothing else in the editor has claimed the keyboard, or this would swallow every
            // keystroke meant for a panel field. See EditorKeyboard.
            if (!dev.ziggle.imgui.EditorKeyboard.busy) dev.ziggle.vscript.editor.host.EditorHost.typed.clear()
            return null
        }
        // Somebody else took the keyboard while this field still thought it had it. Yield rather than
        // fight: two consumers draining one buffer is how keystrokes go missing.
        if (!dev.ziggle.imgui.EditorKeyboard.holds(ctx)) {
            ctx.focused = null
            return null
        }

        val typed = dev.ziggle.vscript.editor.host.EditorHost.typed.drain()
        if (TextEdit.cancelled()) {
            ctx.clearFocus()
            dev.ziggle.vscript.editor.host.EditorHost.typed.clear()
            return null // discarded
        }
        // Tab commits and moves on, exactly as Enter commits and stops. The COMMIT MUST COME FIRST:
        // beginEdit re-seeds the one shared edit buffer, so opening the next field before taking this
        // field's text throws away everything typed into it — and what you see afterwards is the old
        // value redrawn, which reads as "the field reverted" rather than "the edit was dropped".
        //
        // Composite widgets that run their own Tab cycle (a tile's x/y/plane) never register as stops, so
        // tabTarget returns null for them and their own handling stands.
        if (ImGui.isKeyPressed(ImGuiKey.Tab)) {
            val next = ctx.tabTarget(id, ImGui.getIO().keyShift)
            if (next != null) {
                val out = id to ctx.edit.text
                ctx.beginEdit(next.id, next.text, next.filter, selectAll = true)
                return out
            }
        }
        val enter = TextEdit.handleKeys(ctx.edit, ctx.filter, typed, ctx.editLayout, !ctx.suppressClipboard)
        // Clicking anywhere that is not this field commits — the same as tabbing away in any editor, and
        // it means a value is never lost by simply looking elsewhere. `active` is checked as well as `hot`
        // because a field being drag-selected owns the mouse and stops reporting itself as hot.
        val clickedAway = ImGui.isMouseClicked(ImGuiMouseButton.Left) && ctx.hot != id && ctx.active != id
        if (enter || clickedAway) {
            val out = id to ctx.edit.text
            ctx.clearFocus()
            return out
        }
        return null
    }

    // ---- button -----------------------------------------------------------------------------------

    /** A flat button. Returns true on the frame it is released over itself. */
    fun button(
        ctx: WidgetContext,
        id: String,
        x: Float, y: Float, w: Float, h: Float,
        label: String,
        fontSize: Float,
    ): Boolean {
        val over = ctx.hit(id, x, y, w, h)
        if (over && ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
            ctx.active = id
            ctx.consumedClick = true
        }
        val pressed = ctx.active == id
        val clicked = pressed && over && ImGui.isMouseReleased(ImGuiMouseButton.Left)
        val r = ctx.cam.px(ROUND)
        val bg = when {
            pressed -> Theme.BUTTON_ACTIVE
            over -> Theme.BUTTON_HOVER
            else -> Theme.BUTTON
        }
        ctx.dl.addRectFilled(x, y, x + w, y + h, bg, r, ImDrawFlags.RoundCornersAll)
        val tw = CanvasRenderer.textWidth(label, fontSize)
        CanvasRenderer.shadowedText(ctx.dl, x + (w - tw) * 0.5f, y + (h - fontSize) * 0.5f, fontSize, Theme.TEXT, label)
        return clicked
    }
}
