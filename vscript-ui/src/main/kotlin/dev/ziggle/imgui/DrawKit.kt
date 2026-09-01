package dev.ziggle.imgui

import imgui.ImDrawList
import imgui.ImFont
import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiInputTextFlags
import imgui.flag.ImGuiMouseButton
import imgui.flag.ImGuiStyleVar
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImString

/**
 * Reusable, custom-drawn UI components — the vscript widget toolkit. Each component draws its own
 * visuals straight into the active window's `ImDrawList` (rounded fills, a switch knob, …) and uses a
 * zero-render [ImGui.invisibleButton] purely for hit-testing + layout. That keeps the look fully ours
 * (not Dear ImGui's default theme) while still composing with imgui's cursor layout and input routing.
 *
 * Every interactive component needs a stable, unique [id] (imgui keys items by id). Pure-visual ones
 * reserve their space with [ImGui.dummy] so the cursor advances like any other item.
 */
object DrawKit {

    /**
     * The drawable part of a Dear ImGui label — everything before the first `##`.
     *
     * `##` is how every ImGui caller makes two same-named widgets distinct, and the widgets that
     * take a label normally strip it themselves. The components here draw their text with
     * `addText`, which strips nothing, so the convention has to be honoured explicitly or it leaks
     * into the UI. A label that is *only* an id (`"##foo"`) draws nothing, exactly as in ImGui.
     */
    internal fun visibleLabel(label: String): String {
        val cut = label.indexOf("##")
        return if (cut < 0) label else label.substring(0, cut)
    }

    /**
     * A standard button. Auto-sizes to its label when [width] <= 0. Returns true on click.
     *
     * [label] honours Dear ImGui's `##` convention: everything from the first `##` is an id
     * disambiguator and is NOT drawn. These components paint their own text straight into the draw
     * list rather than passing it to an ImGui widget, so nothing stripped it and callers using the
     * universal convention got `Go 3164, 3486##Gunslik28691` rendered literally on the button.
     */
    fun button(id: String, label: String, width: Float = 0f, height: Float = Theme.ROW_H): Boolean {
        @Suppress("NAME_SHADOWING") val label = visibleLabel(label)
        val ts = ImGui.calcTextSize(label)
        val w = if (width > 0f) width else ts.x + Theme.PAD_X * 2
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val clicked = ImGui.invisibleButton(id, w, height)
        val bg = when {
            ImGui.isItemActive() -> Theme.BUTTON_ACTIVE
            ImGui.isItemHovered() -> Theme.BUTTON_HOVER
            else -> Theme.BUTTON
        }
        val dl = ImGui.getWindowDrawList()
        dl.addRectFilled(x, y, x + w, y + height, bg, Theme.ROUNDING)
        dl.addRect(x, y, x + w, y + height, if (ImGui.isItemHovered()) Theme.ACCENT else Theme.BORDER, Theme.ROUNDING, 0, 1f)
        dl.addText(x + (w - ts.x) / 2f, y + (height - ts.y) / 2f, Theme.TEXT, label)
        return clicked
    }

    /**
     * A **primary** button — accent-filled with dark text, for the one action a row is really for.
     *
     * The plain [button] is deliberately low-contrast so a panel full of them stays calm; that is wrong
     * for a row whose entire purpose is a single "do it" control, which reads as disabled chrome when it
     * looks the same as everything around it.
     */
    fun primaryButton(id: String, label: String, width: Float = 0f, height: Float = Theme.ROW_H): Boolean {
        @Suppress("NAME_SHADOWING") val label = visibleLabel(label)
        val ts = ImGui.calcTextSize(label)
        val w = if (width > 0f) width else ts.x + Theme.PAD_X * 2
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val clicked = ImGui.invisibleButton(id, w, height)
        val bg = if (ImGui.isItemHovered()) Theme.ACCENT_HOVER else Theme.ACCENT
        val dl = ImGui.getWindowDrawList()
        dl.addRectFilled(x, y, x + w, y + height, bg, Theme.ROUNDING)
        // Dark text on the accent fill — the same treatment an active [tab] gets.
        dl.addText(x + (w - ts.x) / 2f, y + (height - ts.y) / 2f, Theme.col(0x10, 0x12, 0x18), label)
        return clicked
    }

    /**
     * A **card**: a raised, bordered container that hugs whatever [body] draws.
     *
     * The unlabelled sibling of [section]. A list of multi-line entries needs each entry to read as one
     * object — without a container, a row's name, its badges and its button all sit at the same left
     * edge as the next row's, and where one entry ends is left to the reader to infer from spacing.
     *
     * Height cannot be known before the body runs (immediate mode), so the body is drawn on a foreground
     * draw-list channel and the card is painted behind it afterwards — the same technique [groupBox]
     * uses for an auto-height section.
     *
     * **Do not nest one inside an auto-height [section].** That technique is a draw-list channel split,
     * and a split cannot contain another on the same list — the section has already taken it. [field] gets
     * away with nesting because it paints only a border, so it can draw AFTER its content; a card has a
     * fill, which drawn afterwards would cover the very thing it is meant to sit behind. Inside a section,
     * indent the content instead. A fixed- or max-height section is a `beginChild` and therefore a
     * different draw list, so a card is safe in one of those.
     */
    fun card(id: String, body: () -> Unit) {
        val dl = ImGui.getWindowDrawList()
        val x0 = ImGui.getCursorScreenPosX()
        val y0 = ImGui.getCursorScreenPosY()
        // Both bounds matter: inside a scrolling child the avail width is the real limit (it accounts
        // for the scrollbar), while at a section's top level the avail width overruns the box's
        // inner-right edge. The tighter of the two is correct in both places.
        val boxW = minOf(fillWidth(), ImGui.getContentRegionAvailX()).coerceAtLeast(40f)

        dl.channelsSplit(2)
        dl.channelsSetCurrent(1)
        ImGui.dummy(0f, CARD_PAD_Y)
        ImGui.indent(CARD_PAD_X)
        val prevRight = sectionRight
        sectionRight = x0 + boxW - CARD_PAD_X   // full-width children stop inside the card
        val prevG = inGroupBox; inGroupBox = true
        runCatching { body() }
        inGroupBox = prevG
        sectionRight = prevRight
        ImGui.unindent(CARD_PAD_X)
        ImGui.dummy(0f, CARD_PAD_Y)
        val bottom = ImGui.getCursorScreenPosY()

        dl.channelsSetCurrent(0)
        // Same fill/outline pair a labelled [section] uses, so a card reads as its unlabelled sibling
        // rather than as a different kind of box that happens to sit next to one.
        dl.addRectFilled(x0, y0, x0 + boxW, bottom, Theme.CARD_BG, Theme.ROUNDING)
        dl.addRect(x0, y0, x0 + boxW, bottom, Theme.SECTION_BORDER, Theme.ROUNDING, 0, 1f)
        dl.channelsMerge()

        ImGui.setCursorScreenPos(x0, bottom)
        ImGui.dummy(boxW, CARD_GAP)
    }

    /** A toolbar tab: fills with the accent color while [on]. Returns true on click. */
    fun tab(id: String, label: String, on: Boolean, width: Float = 0f, height: Float = Theme.ROW_H): Boolean {
        @Suppress("NAME_SHADOWING") val label = visibleLabel(label)
        val ts = ImGui.calcTextSize(label)
        val w = if (width > 0f) width else ts.x + Theme.PAD_X * 2
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val clicked = ImGui.invisibleButton(id, w, height)
        val hovered = ImGui.isItemHovered()
        val dl = ImGui.getWindowDrawList()
        dl.addRectFilled(x, y, x + w, y + height, if (on) Theme.ACCENT else if (hovered) Theme.BUTTON_HOVER else Theme.BUTTON, Theme.ROUNDING)
        dl.addText(x + (w - ts.x) / 2f, y + (height - ts.y) / 2f, if (on) Theme.col(0x10, 0x12, 0x18) else Theme.TEXT, label)
        return clicked
    }

    /** An iOS-style switch with a trailing label. Returns the NEW state (pass the current one in). */
    fun toggle(id: String, label: String, state: Boolean): Boolean {
        @Suppress("NAME_SHADOWING") val label = visibleLabel(label)
        val sw = 36f
        val sh = 18f
        val ts = ImGui.calcTextSize(label)
        val h = maxOf(sh, ts.y)
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val clicked = ImGui.invisibleButton(id, sw + 8f + ts.x, h)
        val newState = if (clicked) !state else state
        val dl = ImGui.getWindowDrawList()
        val cy = y + h / 2f
        dl.addRectFilled(x, cy - sh / 2f, x + sw, cy + sh / 2f, if (newState) Theme.ACCENT else Theme.TRACK_OFF, sh / 2f)
        dl.addCircleFilled(if (newState) x + sw - sh / 2f else x + sh / 2f, cy, sh / 2f - 2.5f, Theme.KNOB)
        dl.addText(x + sw + 8f, cy - ts.y / 2f, Theme.TEXT, label)
        return newState
    }

    /**
     * A custom horizontal slider: rounded track, accent fill, a draggable knob, and the value at the far
     * right. Returns the new value (pass the current one in). Full-width when [width] <= 0.
     */
    // Sliders that have been switched (right-click) into precise numeric-entry mode: id -> edit buffer.
    // Presence in the map = that slider is currently a text input; [sliderEditFocus] flags the frame it
    // opened so we can grab keyboard focus once.
    private val sliderEdits = HashMap<String, ImString>()
    private var sliderEditFocus: String? = null

    /** Snap [x] to the nearest multiple of [step] offset from [min], clamped to `[min, max]`. */
    private fun snapInt(x: Int, min: Int, max: Int, step: Int): Int {
        val st = step.coerceAtLeast(1)
        return (min + Math.round((x - min).toFloat() / st) * st).coerceIn(min, max)
    }

    /**
     * An integer slider. Drag to set; **right-click to type an exact value** — it turns into a
     * numbers-only text box that commits on Enter (or when it loses focus) and reverts to a slider. The
     * value text on the right doubles as the current setting. [step] quantises the value (drag and typed
     * entry both land on multiples of it, offset from [min]); the default step of 1 is continuous over ints.
     */
    fun slider(id: String, value: Int, min: Int, max: Int, step: Int = 1, width: Float = 0f): Int {
        val h = Theme.ROW_H
        val w = if (width > 0f) width else ImGui.calcItemWidth() // respects pushItemWidth (e.g. inside a field box)
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()

        // --- precise numeric entry (right-click) ---
        val edit = sliderEdits[id]
        if (edit != null) {
            ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, Theme.ROUNDING)
            ImGui.pushStyleColor(ImGuiCol.FrameBg, Theme.BUTTON)
            if (sliderEditFocus == id) { ImGui.setKeyboardFocusHere(); sliderEditFocus = null }
            ImGui.setNextItemWidth(w)
            val flags = ImGuiInputTextFlags.CharsDecimal or ImGuiInputTextFlags.EnterReturnsTrue or
                ImGuiInputTextFlags.AutoSelectAll
            val committed = ImGui.inputText(id, edit, flags)
            val done = committed || ImGui.isItemDeactivated() // Enter, click-away, or Esc
            ImGui.popStyleColor()
            ImGui.popStyleVar()
            if (done) {
                sliderEdits.remove(id)
                return snapInt(edit.get().trim().toIntOrNull() ?: value, min, max, step)
            }
            return snapInt(value, min, max, step)
        }

        ImGui.invisibleButton(id, w, h)
        val active = ImGui.isItemActive()
        val hovered = ImGui.isItemHovered()
        // Right-click anywhere on the track → switch to typing an exact value next frame.
        if (hovered && ImGui.isMouseClicked(ImGuiMouseButton.Right)) {
            sliderEdits[id] = ImString(snapInt(value, min, max, step).toString(), 16)
            sliderEditFocus = id
        }

        val valueW = 44f
        val tx0 = x + 4f
        val tx1 = x + w - valueW
        var v = snapInt(value, min, max, step)
        if (active && max > min && tx1 > tx0) {
            val t = ((ImGui.getMousePosX() - tx0) / (tx1 - tx0)).coerceIn(0f, 1f)
            v = snapInt(min + Math.round(t * (max - min)), min, max, step)
        }
        val dl = ImGui.getWindowDrawList()
        val cy = y + h / 2f
        val trackH = 5f
        dl.addRectFilled(tx0, cy - trackH / 2f, tx1, cy + trackH / 2f, Theme.TRACK_OFF, trackH / 2f)
        val frac = if (max > min) (v - min).toFloat() / (max - min) else 0f
        val hx = tx0 + frac * (tx1 - tx0)
        dl.addRectFilled(tx0, cy - trackH / 2f, hx, cy + trackH / 2f, Theme.ACCENT, trackH / 2f)
        dl.addCircleFilled(hx, cy, if (active || hovered) 8f else 6.5f, Theme.KNOB)
        val vs = v.toString()
        dl.addText(x + w - ImGui.calcTextSize(vs).x - 6f, cy - ImGui.getTextLineHeight() / 2f, Theme.TEXT, vs)
        return v
    }

    /** Snap [x] to the nearest multiple of [step] offset from [min], clamped to `[min, max]`; a non-positive
     *  [step] means no snapping (just clamp). */
    private fun snapFloat(x: Double, min: Double, max: Double, step: Double): Double {
        val snapped = if (step > 0.0) min + Math.round((x - min) / step) * step else x
        return snapped.coerceIn(min, max)
    }

    /**
     * A float sibling of [slider]: same rounded track + accent fill + draggable knob + right-click precise
     * entry, but over a decimal range, showing [decimals]-place values. Returns the new value. [step] (offset
     * from [min]) quantises the slider; `0.0` (the default) leaves it continuous.
     */
    fun sliderFloat(id: String, value: Double, min: Double, max: Double, decimals: Int = 2, step: Double = 0.0, width: Float = 0f): Double {
        val h = Theme.ROW_H
        val w = if (width > 0f) width else ImGui.calcItemWidth()
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val fmt = "%.${decimals}f"

        // --- precise numeric entry (right-click) ---
        val edit = sliderEdits[id]
        if (edit != null) {
            ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, Theme.ROUNDING)
            ImGui.pushStyleColor(ImGuiCol.FrameBg, Theme.BUTTON)
            if (sliderEditFocus == id) { ImGui.setKeyboardFocusHere(); sliderEditFocus = null }
            ImGui.setNextItemWidth(w)
            val flags = ImGuiInputTextFlags.CharsScientific or ImGuiInputTextFlags.EnterReturnsTrue or
                ImGuiInputTextFlags.AutoSelectAll
            val committed = ImGui.inputText(id, edit, flags)
            val done = committed || ImGui.isItemDeactivated()
            ImGui.popStyleColor()
            ImGui.popStyleVar()
            if (done) {
                sliderEdits.remove(id)
                val parsed = edit.get().trim().toDoubleOrNull() ?: value
                return if (max > min) snapFloat(parsed, min, max, step) else parsed
            }
            return if (max > min) snapFloat(value, min, max, step) else value
        }

        ImGui.invisibleButton(id, w, h)
        val active = ImGui.isItemActive()
        val hovered = ImGui.isItemHovered()
        if (hovered && ImGui.isMouseClicked(ImGuiMouseButton.Right)) {
            sliderEdits[id] = ImString(String.format(fmt, value), 24)
            sliderEditFocus = id
        }

        val valueW = 44f
        val tx0 = x + 4f
        val tx1 = x + w - valueW
        var v = if (max > min) snapFloat(value, min, max, step) else value
        if (active && max > min && tx1 > tx0) {
            val t = ((ImGui.getMousePosX() - tx0) / (tx1 - tx0)).coerceIn(0f, 1f)
            v = snapFloat(min + t * (max - min), min, max, step)
        }
        val dl = ImGui.getWindowDrawList()
        val cy = y + h / 2f
        val trackH = 5f
        dl.addRectFilled(tx0, cy - trackH / 2f, tx1, cy + trackH / 2f, Theme.TRACK_OFF, trackH / 2f)
        val frac = if (max > min) ((v - min) / (max - min)).toFloat() else 0f
        val hx = tx0 + frac * (tx1 - tx0)
        dl.addRectFilled(tx0, cy - trackH / 2f, hx, cy + trackH / 2f, Theme.ACCENT, trackH / 2f)
        dl.addCircleFilled(hx, cy, if (active || hovered) 8f else 6.5f, Theme.KNOB)
        val vs = String.format(fmt, v)
        dl.addText(x + w - ImGui.calcTextSize(vs).x - 6f, cy - ImGui.getTextLineHeight() / 2f, Theme.TEXT, vs)
        return v
    }

    // A dual-knob range slider locks onto ONE knob for the duration of a drag: id -> 0 (lo) / 1 (hi).
    private val rangeDrag = HashMap<String, Int>()

    /** Pick which knob a fresh drag grabs: the nearer one, breaking a tie (coincident knobs) by drag direction. */
    private fun grabKnob(mx: Float, loPos: Float, hiPos: Float): Int {
        val dLo = Math.abs(mx - loPos)
        val dHi = Math.abs(mx - hiPos)
        return when {
            dLo < dHi -> 0
            dHi < dLo -> 1
            else -> if (mx >= hiPos) 1 else 0
        }
    }

    /**
     * A dual-knob **range** slider: two knobs on one track select a low/high pair within `[min, max]`. Drag the
     * knob nearer the cursor; **right-click to type an exact `lo, hi`**. [step] snaps both knobs (offset from
     * [min]). Returns the chosen `lo..hi`, always ordered.
     */
    fun rangeSlider(id: String, lo: Int, hi: Int, min: Int, max: Int, step: Int = 1, width: Float = 0f): IntRange {
        val h = Theme.ROW_H
        val w = if (width > 0f) width else ImGui.calcItemWidth()
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        var vlo = snapInt(minOf(lo, hi), min, max, step)
        var vhi = snapInt(maxOf(lo, hi), min, max, step)

        // --- precise numeric entry (right-click): type "lo, hi" ---
        val edit = sliderEdits[id]
        if (edit != null) {
            ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, Theme.ROUNDING)
            ImGui.pushStyleColor(ImGuiCol.FrameBg, Theme.BUTTON)
            if (sliderEditFocus == id) { ImGui.setKeyboardFocusHere(); sliderEditFocus = null }
            ImGui.setNextItemWidth(w)
            val flags = ImGuiInputTextFlags.CharsDecimal or ImGuiInputTextFlags.EnterReturnsTrue or ImGuiInputTextFlags.AutoSelectAll
            val committed = ImGui.inputText(id, edit, flags)
            val done = committed || ImGui.isItemDeactivated()
            ImGui.popStyleColor()
            ImGui.popStyleVar()
            if (done) {
                sliderEdits.remove(id)
                val nums = edit.get().split(',', ' ').mapNotNull { it.trim().toIntOrNull() }
                if (nums.size >= 2) {
                    vlo = snapInt(minOf(nums[0], nums[1]), min, max, step)
                    vhi = snapInt(maxOf(nums[0], nums[1]), min, max, step)
                }
            }
            return vlo..vhi
        }

        ImGui.invisibleButton(id, w, h)
        val active = ImGui.isItemActive()
        val hovered = ImGui.isItemHovered()
        if (hovered && ImGui.isMouseClicked(ImGuiMouseButton.Right)) {
            sliderEdits[id] = ImString("$vlo, $vhi", 24)
            sliderEditFocus = id
        }

        val valueW = 88f
        val tx0 = x + 4f
        val tx1 = x + w - valueW
        val span = max - min
        fun posOf(v: Int): Float = if (span > 0 && tx1 > tx0) tx0 + (v - min).toFloat() / span * (tx1 - tx0) else tx0
        if (active && span > 0 && tx1 > tx0) {
            val mx = ImGui.getMousePosX()
            val which = rangeDrag.getOrPut(id) { grabKnob(mx, posOf(vlo), posOf(vhi)) }
            val nv = snapInt(min + Math.round(((mx - tx0) / (tx1 - tx0)).coerceIn(0f, 1f) * span), min, max, step)
            if (which == 0) vlo = nv.coerceAtMost(vhi) else vhi = nv.coerceAtLeast(vlo)
        } else if (!active) rangeDrag.remove(id)

        val dl = ImGui.getWindowDrawList()
        val cy = y + h / 2f
        val trackH = 5f
        val hxLo = posOf(vlo)
        val hxHi = posOf(vhi)
        dl.addRectFilled(tx0, cy - trackH / 2f, tx1, cy + trackH / 2f, Theme.TRACK_OFF, trackH / 2f)
        dl.addRectFilled(hxLo, cy - trackH / 2f, hxHi, cy + trackH / 2f, Theme.ACCENT, trackH / 2f)
        dl.addCircleFilled(hxLo, cy, if (active || hovered) 8f else 6.5f, Theme.KNOB)
        dl.addCircleFilled(hxHi, cy, if (active || hovered) 8f else 6.5f, Theme.KNOB)
        val vs = "$vlo – $vhi"
        dl.addText(x + w - ImGui.calcTextSize(vs).x - 6f, cy - ImGui.getTextLineHeight() / 2f, Theme.TEXT, vs)
        return vlo..vhi
    }

    /**
     * The decimal sibling of [rangeSlider]: two knobs picking a low/high [Double] pair, [decimals]-place
     * display, [step] snapping (`0.0` = continuous). Returns the chosen ordered range.
     */
    fun rangeSliderFloat(id: String, lo: Double, hi: Double, min: Double, max: Double, decimals: Int = 2, step: Double = 0.0, width: Float = 0f): ClosedFloatingPointRange<Double> {
        val h = Theme.ROW_H
        val w = if (width > 0f) width else ImGui.calcItemWidth()
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val fmt = "%.${decimals}f"
        var vlo = snapFloat(minOf(lo, hi), min, max, step)
        var vhi = snapFloat(maxOf(lo, hi), min, max, step)

        // --- precise numeric entry (right-click): type "lo, hi" ---
        val edit = sliderEdits[id]
        if (edit != null) {
            ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, Theme.ROUNDING)
            ImGui.pushStyleColor(ImGuiCol.FrameBg, Theme.BUTTON)
            if (sliderEditFocus == id) { ImGui.setKeyboardFocusHere(); sliderEditFocus = null }
            ImGui.setNextItemWidth(w)
            val flags = ImGuiInputTextFlags.CharsScientific or ImGuiInputTextFlags.EnterReturnsTrue or ImGuiInputTextFlags.AutoSelectAll
            val committed = ImGui.inputText(id, edit, flags)
            val done = committed || ImGui.isItemDeactivated()
            ImGui.popStyleColor()
            ImGui.popStyleVar()
            if (done) {
                sliderEdits.remove(id)
                val nums = edit.get().split(',').mapNotNull { it.trim().toDoubleOrNull() }
                if (nums.size >= 2) {
                    vlo = snapFloat(minOf(nums[0], nums[1]), min, max, step)
                    vhi = snapFloat(maxOf(nums[0], nums[1]), min, max, step)
                }
            }
            return vlo..vhi
        }

        ImGui.invisibleButton(id, w, h)
        val active = ImGui.isItemActive()
        val hovered = ImGui.isItemHovered()
        if (hovered && ImGui.isMouseClicked(ImGuiMouseButton.Right)) {
            sliderEdits[id] = ImString("${String.format(fmt, vlo)}, ${String.format(fmt, vhi)}", 32)
            sliderEditFocus = id
        }

        val valueW = 104f
        val tx0 = x + 4f
        val tx1 = x + w - valueW
        val span = max - min
        fun posOf(v: Double): Float = if (span > 0.0 && tx1 > tx0) tx0 + ((v - min) / span).toFloat() * (tx1 - tx0) else tx0
        if (active && span > 0.0 && tx1 > tx0) {
            val mx = ImGui.getMousePosX()
            val which = rangeDrag.getOrPut(id) { grabKnob(mx, posOf(vlo), posOf(vhi)) }
            val nv = snapFloat(min + ((mx - tx0) / (tx1 - tx0)).coerceIn(0f, 1f) * span, min, max, step)
            if (which == 0) vlo = nv.coerceAtMost(vhi) else vhi = nv.coerceAtLeast(vlo)
        } else if (!active) rangeDrag.remove(id)

        val dl = ImGui.getWindowDrawList()
        val cy = y + h / 2f
        val trackH = 5f
        val hxLo = posOf(vlo)
        val hxHi = posOf(vhi)
        dl.addRectFilled(tx0, cy - trackH / 2f, tx1, cy + trackH / 2f, Theme.TRACK_OFF, trackH / 2f)
        dl.addRectFilled(hxLo, cy - trackH / 2f, hxHi, cy + trackH / 2f, Theme.ACCENT, trackH / 2f)
        dl.addCircleFilled(hxLo, cy, if (active || hovered) 8f else 6.5f, Theme.KNOB)
        dl.addCircleFilled(hxHi, cy, if (active || hovered) 8f else 6.5f, Theme.KNOB)
        val vs = "${String.format(fmt, vlo)} – ${String.format(fmt, vhi)}"
        dl.addText(x + w - ImGui.calcTextSize(vs).x - 6f, cy - ImGui.getTextLineHeight() / 2f, Theme.TEXT, vs)
        return vlo..vhi
    }

    /** A segmented control: a row of pill buttons, the selected one filled accent. Returns the new index. */
    fun segmented(id: String, options: List<String>, selected: Int): Int {
        var result = selected
        val h = Theme.ROW_H
        val dl = ImGui.getWindowDrawList()
        for ((i, opt) in options.withIndex()) {
            if (i > 0) ImGui.sameLine(0f, 4f)
            val ts = ImGui.calcTextSize(opt)
            val w = ts.x + Theme.PAD_X * 2
            val x = ImGui.getCursorScreenPosX()
            val y = ImGui.getCursorScreenPosY()
            if (ImGui.invisibleButton("$id$i", w, h)) result = i
            val on = i == selected
            val bg = if (on) Theme.ACCENT else if (ImGui.isItemHovered()) Theme.BUTTON_HOVER else Theme.BUTTON
            dl.addRectFilled(x, y, x + w, y + h, bg, Theme.ROUNDING)
            dl.addText(x + (w - ts.x) / 2f, y + (h - ts.y) / 2f, if (on) Theme.col(0x10, 0x12, 0x18) else Theme.TEXT, opt)
        }
        return result
    }

    private val dropdownFilters = HashMap<String, ImString>()

    /**
     * A custom dropdown: a closed control showing the selected option, that opens a popup with a live
     * search/filter box (auto-focused) over the matching options. Returns the new selected index.
     */
    fun dropdown(id: String, options: List<String>, selected: Int, width: Float = 0f): Int {
        var result = selected
        val w = if (width > 0f) width else ImGui.calcItemWidth()
        val h = ImGui.getFrameHeight() // match a text input's height
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val popupId = "##pop_$id"

        // Closed control: shows the selected value + a chevron (the "not focused" state).
        val clicked = ImGui.invisibleButton(id, w, h)
        val hovered = ImGui.isItemHovered()
        val dl = ImGui.getWindowDrawList()
        dl.addRectFilled(x, y, x + w, y + h, if (hovered) Theme.BUTTON_HOVER else Theme.BUTTON, Theme.ROUNDING)
        dl.addRect(x, y, x + w, y + h, Theme.BORDER, Theme.ROUNDING, 0, 1f)
        dl.addText(x + Theme.PAD_X, y + (h - ImGui.getTextLineHeight()) / 2f, Theme.TEXT, options.getOrNull(selected) ?: "")
        val cx = x + w - 14f
        val cy = y + h / 2f
        dl.addTriangleFilled(cx - 4f, cy - 2f, cx + 4f, cy - 2f, cx, cy + 3f, Theme.TEXT_DIM) // ▼ chevron

        if (clicked) {
            dropdownFilters.getOrPut(id) { ImString("", 128) }.set("")
            ImGui.openPopup(popupId)
        }
        ImGui.setNextWindowPos(x, y + h + 2f)
        ImGui.setNextWindowSize(w, 0f)
        if (ImGui.beginPopup(popupId)) {
            val filter = dropdownFilters.getOrPut(id) { ImString("", 128) }
            if (ImGui.isWindowAppearing()) ImGui.setKeyboardFocusHere() // auto-focus the search box on open
            ImGui.setNextItemWidth(w - 16f)
            ImGui.inputTextWithHint("##filter_$id", "Search…", filter)
            ImGui.separator()
            val q = filter.get().trim().lowercase()
            if (ImGui.beginChild("##list_$id", w - 16f, 160f, false)) {
                options.forEachIndexed { i, opt ->
                    if (q.isEmpty() || opt.lowercase().contains(q)) {
                        if (ImGui.selectable("$opt##$i", i == selected)) {
                            result = i
                            ImGui.closeCurrentPopup()
                        }
                    }
                }
            }
            ImGui.endChild()
            ImGui.endPopup()
        }
        return result
    }

    /** A −/value/+ stepper for a plain integer (no fixed range). Returns the new value. */
    fun stepper(id: String, value: Int, step: Int = 1, min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE): Int {
        var v = value
        if (button("$id-", "−", Theme.ROW_H)) v = (v.toLong() - step).coerceIn(min.toLong(), max.toLong()).toInt()
        ImGui.sameLine(0f, 4f)
        val boxW = 64f
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        ImGui.dummy(boxW, Theme.ROW_H)
        val dl = ImGui.getWindowDrawList()
        dl.addRectFilled(x, y, x + boxW, y + Theme.ROW_H, Theme.BUTTON, Theme.ROUNDING)
        val vs = v.toString()
        dl.addText(x + (boxW - ImGui.calcTextSize(vs).x) / 2f, y + (Theme.ROW_H - ImGui.getTextLineHeight()) / 2f, Theme.TEXT, vs)
        ImGui.sameLine(0f, 4f)
        if (button("$id+", "+", Theme.ROW_H)) v = (v.toLong() + step).coerceIn(min.toLong(), max.toLong()).toInt()
        return v
    }

    /**
     * Draw a small rounded badge (a category tag) straight into [dl], its left edge at [x] and vertically
     * centred on [cy]. Returns the badge's width so the caller can lay several out in a row. Uses the
     * current font; pure-visual, so it neither hit-tests nor advances the imgui cursor.
     */
    /** The width [badge] will draw for [text] — for laying badges out right-to-left before drawing them. */
    fun badgeWidth(text: String): Float = ImGui.calcTextSize(text).x + BADGE_PAD_X * 2f

    fun badge(dl: ImDrawList, x: Float, cy: Float, text: String): Float {
        val ts = ImGui.calcTextSize(text)
        val padX = BADGE_PAD_X
        val padY = 2f
        val w = ts.x + padX * 2f
        val h = ts.y + padY * 2f
        val y0 = cy - h / 2f
        // Modest rounding (not a full pill) so short labels aren't crowded into the rounded caps.
        val r = (h / 3f).coerceAtMost(Theme.ROUNDING)
        dl.addRectFilled(x, y0, x + w, y0 + h, Theme.BADGE_BG, r)
        dl.addRect(x, y0, x + w, y0 + h, Theme.BADGE_BORDER, r, 0, 1f)
        // Nudge the text up ~1px: the font's top side-bearing otherwise reads as extra padding above it.
        dl.addText(x + padX, y0 + padY - 1f, Theme.BADGE_TEXT, text)
        return w
    }

    /** Accent header in the larger heading font (plugin name, "Description"/"Settings", …). */
    fun heading(text: String) {
        Fonts.heading?.let { ImGui.pushFont(it) }
        ImGui.pushStyleColor(ImGuiCol.Text, Theme.TEXT_ACCENT)
        // textUnformatted, not text: ImGui takes the string as a printf format. See Gfx2D.text.
        ImGui.textUnformatted(text)
        ImGui.popStyleColor()
        Fonts.heading?.let { ImGui.popFont() }
    }

    /**
     * A [heading] with a row of [tags] rendered as badges to its right, vertically centred on the heading
     * text. The badges use the normal body font (small), so they read as labels beside the larger title.
     */
    fun headingWithBadges(text: String, tags: List<String>) {
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        Fonts.heading?.let { ImGui.pushFont(it) }
        val ts = ImGui.calcTextSize(text)              // measure in the heading font
        Fonts.heading?.let { ImGui.popFont() }
        heading(text)                                  // draws the title, advances the cursor to the next line
        if (tags.isEmpty()) return
        val dl = ImGui.getWindowDrawList()
        val cy = y + ts.y / 2f
        var bx = x + ts.x + 12f
        for (tag in tags) bx += badge(dl, bx, cy, tag) + 5f
    }

    /** A "label   value" row: dim label, bright (or colored) value. */
    fun labelValue(label: String, value: String, valueColor: Int = Theme.TEXT) {
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val dl = ImGui.getWindowDrawList()
        dl.addText(x, y, Theme.TEXT_DIM, label)
        val lw = ImGui.calcTextSize(label).x
        dl.addText(x + lw + 10f, y, valueColor, value)
        ImGui.dummy(lw + 10f + ImGui.calcTextSize(value).x, ImGui.getTextLineHeight())
    }

    /**
     * A **meter**: a caption line with a right-aligned readout, over a full-width filled bar.
     *
     * The readout for a quantity with a known ceiling — minutes of a stint, a day against its target, a
     * 0..100 gauge — where the number alone makes you do the division and the bar alone loses the number.
     * Pure-visual, and it reserves its own height, so it composes inside a [section] like any other row.
     *
     * **[fraction] and [right] are independent on purpose.** The bar is a proportion and the text is
     * whatever says it best — `"38 of 45 min"`, `"64 / 100"`, `"2h 10m of ~6h"` — and forcing the second
     * to be derived from the first would mean every caller formatting a percentage nobody wanted to read.
     *
     * A [fraction] above 1 fills the bar and tints it [over], which is the honest picture of a budget that
     * has been exceeded: the bar cannot grow, so the colour is the only thing left to say it with.
     */
    fun meter(
        label: String,
        fraction: Float,
        right: String,
        color: Int = Theme.ACCENT,
        over: Int = Theme.WARN,
        height: Float = 5f,
    ) {
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val w = fillWidth()
        val dl = ImGui.getWindowDrawList()
        val lh = ImGui.getTextLineHeight()

        dl.addText(x, y, Theme.TEXT_DIM, visibleLabel(label))
        if (right.isNotEmpty()) {
            val rw = ImGui.calcTextSize(right).x
            dl.addText(x + w - rw, y, Theme.TEXT, right)
        }

        val top = y + lh + 3f
        val r = height / 2f
        dl.addRectFilled(x, top, x + w, top + height, Theme.TRACK_OFF, r)
        // A hair of width even at almost-nothing, so "barely started" and "not started" look different.
        if (fraction > 0f) {
            val filled = (fraction.coerceIn(0f, 1f) * w).coerceAtLeast(height)
            dl.addRectFilled(x, top, x + filled, top + height, if (fraction > 1f) over else color, r)
        }
        ImGui.dummy(w, lh + 3f + height + 4f)
    }

    /** Full-width hairline separator. */
    fun separator() {
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val w = ImGui.getContentRegionAvailX()
        ImGui.getWindowDrawList().addLine(x, y + 3f, x + w, y + 3f, Theme.BORDER)
        ImGui.dummy(w, 7f)
    }

    /**
     * Draw [body] inside a rounded group box with [label] notched into the top-left of the border (a gap
     * in the line, with a small left margin) over a subtle fill. With [fixedHeight] > 0 the content area
     * is that tall and scrollable; otherwise the box grows to fit its content. Composes with imgui's
     * cursor layout, so call it like any other widget and put normal widgets inside [body].
     */
    fun section(label: String, fixedHeight: Float = 0f, maxHeight: Float = 0f, body: () -> Unit) {
        groupBox(label, fixedHeight, Theme.SECTION_BG, Fonts.subHeading, Theme.TEXT_ACCENT, null, maxHeight, body)
    }

    /** A [section] with a clickable icon button notched into the top-right border (glyph string, body font).
     *  @return true on the frame the icon is clicked — e.g. a pop-out control. */
    fun section(label: String, headerIcon: String, fixedHeight: Float = 0f, maxHeight: Float = 0f, body: () -> Unit): Boolean =
        groupBox(label, fixedHeight, Theme.SECTION_BG, Fonts.subHeading, Theme.TEXT_ACCENT, headerIcon, maxHeight, body)

    /**
     * A single labelled field: the same rounded box + notched [label] as a [section] but lighter (no
     * fill, dim label) and sized for one control. Put one control widget inside [body].
     */
    fun field(label: String, contentHeight: Float = 0f, width: Float = 0f, body: () -> Unit) {
        val dl = ImGui.getWindowDrawList()
        val x0 = ImGui.getCursorScreenPosX()
        val y0 = ImGui.getCursorScreenPosY()
        // Right edge: an explicit [width], else the enclosing section's inner-right (leaving a margin
        // inside it), else the container minus the standard right margin.
        val right = when {
            width > 0f -> x0 + width
            sectionRight > 0f -> sectionRight
            else -> x0 + ImGui.getContentRegionAvailX() - SEC_RMARGIN
        }
        val boxW = (right - x0).coerceAtLeast(80f)
        val font = Fonts.body
        val lh = font?.fontSize ?: ImGui.getTextLineHeight()
        val top = y0 + lh / 2f
        val contentY = y0 + lh + SEC_PAD_TOP
        val itemW = boxW - SEC_PAD_L * 2f
        // Field has no fill, so no channel split is needed (works nested inside a section, which is itself
        // channel-split). contentHeight <= 0 AUTO-MEASURES: draw the control, read where it ended, then draw
        // the box around it — so the padding is consistent no matter the control's natural height. A positive
        // contentHeight keeps the fixed-height path (box first) for callers that pre-compute layout.
        ImGui.setCursorScreenPos(x0 + SEC_PAD_L, contentY)
        ImGui.pushItemWidth(itemW)
        val bottom: Float
        if (contentHeight > 0f) {
            bottom = contentY + contentHeight + SEC_PAD_BOT
            boxAndLabel(dl, label, x0, top, x0 + boxW, bottom, 0, font, Theme.TEXT_DIM)
            runCatching { body() }
            ImGui.popItemWidth()
        } else {
            // Standalone (NOT inside a section's channel split), split so the border draws cleanly around the
            // measured content — the same technique a section uses for its own box. Inside a section we're
            // already split (splits can't nest), so there we draw the border after the content directly.
            val split = !inGroupBox
            if (split) { dl.channelsSplit(2); dl.channelsSetCurrent(1) }
            runCatching { body() }
            ImGui.popItemWidth()
            bottom = ImGui.getCursorScreenPosY() + SEC_PAD_BOT
            if (split) dl.channelsSetCurrent(0)
            boxAndLabel(dl, label, x0, top, x0 + boxW, bottom, 0, font, Theme.TEXT_DIM)
            if (split) dl.channelsMerge()
        }
        ImGui.setCursorScreenPos(x0, bottom)
        ImGui.dummy(boxW, SEC_GAP)
    }

    /** The total vertical space a [field] with [contentHeight] occupies (notched label + paddings + gap). */
    fun fieldHeight(contentHeight: Float = Theme.ROW_H): Float {
        val lh = Fonts.body?.fontSize ?: ImGui.getTextLineHeight()
        return lh + SEC_PAD_TOP + contentHeight + SEC_PAD_BOT + SEC_GAP
    }

    /** Y offset from a [field]'s top (the cursor at the call) to where its inner control begins (clears
     *  the notched label). Lets a caller align a sibling control with the field's content box. */
    fun fieldContentTop(): Float = (Fonts.body?.fontSize ?: ImGui.getTextLineHeight()) + SEC_PAD_TOP

    /** Width from the current cursor to the enclosing [section]'s inner-right edge (or the container minus the
     *  standard right margin) — for a full-width control that must NOT overflow a section box, matching how
     *  [field] sizes itself. */
    fun fillWidth(): Float {
        val x0 = ImGui.getCursorScreenPosX()
        val right = if (sectionRight > 0f) sectionRight else x0 + ImGui.getContentRegionAvailX() - SEC_RMARGIN
        return (right - x0).coerceAtLeast(0f)
    }

    /** A square, self-contained icon button drawn at the cursor (icon centred). Returns true on click. */
    fun iconButton(id: String, glyph: String, size: Float): Boolean {
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val clicked = ImGui.invisibleButton(id, size, size)
        val bg = when {
            ImGui.isItemActive() -> Theme.BUTTON_ACTIVE
            ImGui.isItemHovered() -> Theme.BUTTON_HOVER
            else -> Theme.BUTTON
        }
        val dl = ImGui.getWindowDrawList()
        dl.addRectFilled(x, y, x + size, y + size, bg, Theme.ROUNDING)
        val ts = ImGui.calcTextSize(glyph)
        dl.addText(x + (size - ts.x) / 2f, y + (size - ts.y) / 2f, Theme.TEXT, glyph)
        return clicked
    }

    /**
     * A themed, collapsible **tree row** drawn entirely into the draw list (no Dear ImGui tree widget):
     * a share/heat bar behind the row (its width = [fraction] of the parent total), an expand chevron
     * (▶ / ▼) or a leaf bullet, the [label], and right-aligned [right] stats. The whole row width is the
     * click target. Returns true when clicked (the caller toggles expansion). [depth] indents the row.
     */
    fun treeRow(
        id: String,
        depth: Int,
        hasChildren: Boolean,
        expanded: Boolean,
        label: String,
        right: String,
        fraction: Float,
        labelColor: Int = Theme.TEXT,
        rightColor: Int = Theme.TEXT_DIM,
        height: Float = 22f,
        rightBadges: List<String> = emptyList(),
    ): Boolean {
        @Suppress("NAME_SHADOWING") val label = visibleLabel(label)
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        // fillWidth, NOT getContentRegionAvailX: inside a section the container extends past the box's
        // inner-right edge, so the raw avail width runs the row (and its right-aligned text) over the
        // section border. Matches how [field] and a full-width [button] size themselves.
        val w = fillWidth()
        val clicked = ImGui.invisibleButton(id, w, height)
        val hovered = ImGui.isItemHovered()
        val dl = ImGui.getWindowDrawList()

        // share/heat bar (how much of the parent total this node is) — stable even as absolute totals climb
        val frac = fraction.coerceIn(0f, 1f)
        if (frac > 0.003f) dl.addRectFilled(x, y, x + frac * w, y + height, TREE_HEAT, Theme.ROUNDING)
        if (hovered) dl.addRect(x, y, x + w, y + height, Theme.ACCENT, Theme.ROUNDING, 0, 1f)

        val cy = y + height / 2f
        val gx = x + 6f + depth * 15f
        val lh = ImGui.getTextLineHeight()
        val textX: Float
        if (hasChildren) {
            if (expanded) dl.addTriangleFilled(gx - 1f, cy - 3f, gx + 7f, cy - 3f, gx + 3f, cy + 4f, Theme.TEXT) // ▼
            else dl.addTriangleFilled(gx, cy - 5f, gx, cy + 5f, gx + 6f, cy, Theme.TEXT_DIM)                     // ▶
            textX = gx + 14f
        } else {
            dl.addCircleFilled(gx + 3f, cy, 1.7f, Theme.TEXT_DIM)
            textX = gx + 12f
        }
        dl.addText(textX, cy - lh / 2f, labelColor, label)
        // Badges lay out right-to-left from the row's right edge, so the set stays flush no matter how
        // many there are or how wide each renders.
        if (rightBadges.isNotEmpty()) {
            var bx = x + w - BADGE_GAP
            for (b in rightBadges.asReversed()) {
                val bw = badgeWidth(b)
                bx -= bw
                badge(dl, bx, cy, b)
                bx -= BADGE_GAP
            }
        } else if (right.isNotEmpty()) {
            val rw = ImGui.calcTextSize(right).x
            dl.addText(x + w - rw - BADGE_GAP, cy - lh / 2f, rightColor, right)
        }
        return clicked
    }

    /** Faint accent used for the tree share bar. */
    private val TREE_HEAT = Theme.col(0x5B, 0x8C, 0xFF, 0x30)

    private fun groupBox(label: String, fixedHeight: Float, fill: Int, labelFont: ImFont?, labelColor: Int, headerIcon: String?, maxHeight: Float = 0f, body: () -> Unit): Boolean {
        val dl = ImGui.getWindowDrawList()
        val x0 = ImGui.getCursorScreenPosX()
        val y0 = ImGui.getCursorScreenPosY()
        val boxW = ImGui.getContentRegionAvailX() - SEC_RMARGIN
        val lh = labelFont?.fontSize ?: ImGui.getTextLineHeight()
        val top = y0 + lh / 2f                  // top border at the label's centre (label straddles it)
        val contentY = y0 + lh + SEC_PAD_TOP    // content clears the full label height + padding
        val itemW = (boxW - SEC_PAD_L * 2f - 8f).coerceAtLeast(40f)
        var iconClicked = false

        // Max-height mode: grow with the content like the unbounded path, but once the content
        // (measured LAST frame — immediate mode can't know it up front) reaches maxHeight, pin the
        // child there and let it scroll. First frame renders at maxHeight, then settles.
        var boundedH = fixedHeight
        var measureKey: String? = null
        if (fixedHeight <= 0f && maxHeight > 0f) {
            measureKey = label
            boundedH = ((sectionContentH[label] ?: maxHeight) + 1f).coerceAtMost(maxHeight)
        }

        if (boundedH > 0f) {
            val bottom = contentY + boundedH + SEC_PAD_BOT
            iconClicked = boxAndLabel(dl, label, x0, top, x0 + boxW, bottom, fill, labelFont, labelColor, headerIcon)
            ImGui.setCursorScreenPos(x0 + SEC_PAD_L, contentY)
            // **A FIXED-height box never scrolls.** ImGui counts the `ItemSpacing.y` it advances past the
            // LAST item as content, so a box sized to exactly fit its rows is permanently a few pixels
            // "over" and grows a scrollbar around empty space. The scrollbar then narrows the content
            // region, which moves anything centred in it — and since the narrowing is itself content, the
            // layout has no stable answer and visibly jitters frame to frame.
            //
            // Clipping that phantom gap costs nothing, because there is nothing in it. Note this cannot be
            // fixed by walking the cursor back before `endChild`: `SetCursorPos` raises `CursorMaxPos` with
            // an `ImMax`, so a backwards move never lowers the content size it is measured from.
            //
            // The [maxHeight] path is the opposite case and keeps its scrollbar — being able to scroll past
            // the cap is the entire point of a cap.
            val childFlags = if (fixedHeight > 0f) NO_SCROLL else 0
            ImGui.beginChild("##box_$label", boxW - SEC_PAD_L * 2f, boundedH, false, childFlags)
            ImGui.pushItemWidth(itemW)
            val prevG = inGroupBox; inGroupBox = true
            runCatching { body() }
            inGroupBox = prevG
            if (measureKey != null) sectionContentH[measureKey] = ImGui.getCursorPosY() // content height (scroll-independent)
            ImGui.popItemWidth()
            ImGui.endChild()
            ImGui.setCursorScreenPos(x0, bottom)
            ImGui.dummy(boxW, SEC_GAP)
        } else {
            // Unknown height: render content on a foreground channel, then draw the box behind it.
            dl.channelsSplit(2)
            dl.channelsSetCurrent(1)
            ImGui.dummy(0f, lh + SEC_PAD_TOP) // reserve the full label height + top padding
            ImGui.indent(SEC_PAD_L)
            val prevRight = sectionRight
            sectionRight = x0 + boxW - SEC_PAD_L // child fields stop here (right margin inside the section)
            val prevG = inGroupBox; inGroupBox = true
            runCatching { body() }
            inGroupBox = prevG
            sectionRight = prevRight
            ImGui.unindent(SEC_PAD_L)
            ImGui.dummy(0f, SEC_PAD_BOT)
            val bottom = ImGui.getCursorScreenPosY()
            dl.channelsSetCurrent(0)
            iconClicked = boxAndLabel(dl, label, x0, top, x0 + boxW, bottom, fill, labelFont, labelColor, headerIcon)
            dl.channelsMerge()
            ImGui.setCursorScreenPos(x0, bottom)
            ImGui.dummy(boxW, SEC_GAP)
        }
        return iconClicked
    }

    /** Draw the box border + notched [label] (top-left). If [headerIcon] is a glyph, also draw a clickable icon
     *  notched into the top-RIGHT border, mirroring the label. @return true on the frame that icon is clicked. */
    private fun boxAndLabel(
        dl: ImDrawList, label: String, x0: Float, y0: Float, x1: Float, y1: Float,
        fill: Int, labelFont: ImFont?, labelColor: Int, headerIcon: String? = null,
    ): Boolean {
        if (fill != 0) dl.addRectFilled(x0, y0, x1, y1, fill, Theme.ROUNDING)
        // Push the label font so calcTextSize + the draw-list addText both use it. A blank label draws no
        // notch (so an unlabelled frame has a clean, unbroken top border).
        labelFont?.let { ImGui.pushFont(it) }
        val ts = if (label.isEmpty()) null else ImGui.calcTextSize(label)
        val lx = x0 + SEC_LABEL_MARGIN
        if (ts == null) {
            dl.addRect(x0, y0, x1, y1, Theme.SECTION_BORDER, Theme.ROUNDING, 0, 1.2f)
        } else {
            borderNotched(dl, x0, y0, x1, y1, lx - SEC_LABEL_PAD, lx + ts.x + SEC_LABEL_PAD)
            dl.addText(lx, y0 - ts.y / 2f, labelColor, label)
        }
        labelFont?.let { ImGui.popFont() }
        // Header icon button notched into the top-right border (body font carries the FontAwesome glyphs).
        var clicked = false
        if (headerIcon != null && Fonts.iconsLoaded) {
            val isz = ImGui.calcTextSize(headerIcon)
            val ix = x1 - SEC_LABEL_MARGIN - isz.x
            dl.addRectFilled(ix - SEC_LABEL_PAD, y0 - 1.5f, ix + isz.x + SEC_LABEL_PAD, y0 + 1.5f, Theme.PANEL_BG)
            val mx = ImGui.getMousePosX(); val my = ImGui.getMousePosY()
            val hov = mx >= ix - 3f && mx <= ix + isz.x + 3f && my >= y0 - isz.y / 2f - 4f && my <= y0 + isz.y / 2f + 4f
            dl.addText(ix, y0 - isz.y / 2f, if (hov) Theme.ACCENT else Theme.TEXT_DIM, headerIcon)
            clicked = hov && ImGui.isMouseClicked(0)
        }
        return clicked
    }

    /**
     * The box outline, with a GAP in the top edge from [gapX0] to [gapX1] for the label to sit in.
     *
     * **A real gap, not a patch painted over the line.** This used to draw the whole rounded rect and then
     * erase the span behind the label with a `Theme.PANEL_BG` rectangle — which silently assumed the box
     * was sitting on a docked panel. On any other surface the "erase" is simply a differently-coloured
     * mark: on a floating overlay panel, whose background is both darker and TRANSLUCENT, it painted an
     * opaque light tick beside every label and left the (invisible) border untouched. That is
     * unfixable by picking a better colour — there is no opaque colour that matches a translucent
     * surface over a moving game scene — so the line is not drawn there in the first place.
     *
     * Traced as one open path so the corners keep their radius: out of the gap along the top, round the
     * three far corners, up the left edge and back to the other side of the gap.
     */
    private fun borderNotched(
        dl: ImDrawList, x0: Float, y0: Float, x1: Float, y1: Float, gapX0: Float, gapX1: Float,
    ) {
        val r = Theme.ROUNDING
        val col = Theme.SECTION_BORDER
        // A label wider than the box leaves no top edge to draw; the three remaining sides still frame it.
        val g0 = gapX0.coerceIn(x0 + r, x1 - r)
        val g1 = gapX1.coerceIn(g0, x1 - r)
        val half = Math.PI.toFloat() / 2f
        dl.pathClear()
        dl.pathLineTo(g1, y0)
        dl.pathArcTo(x1 - r, y0 + r, r, -half, 0f, ARC_SEGMENTS)          // top-right
        dl.pathArcTo(x1 - r, y1 - r, r, 0f, half, ARC_SEGMENTS)           // bottom-right
        dl.pathArcTo(x0 + r, y1 - r, r, half, 2f * half, ARC_SEGMENTS)    // bottom-left
        dl.pathArcTo(x0 + r, y0 + r, r, 2f * half, 3f * half, ARC_SEGMENTS) // top-left
        dl.pathLineTo(g0, y0)
        dl.pathStroke(col, 0, 1.2f) // open path: the gap is the whole point, so never close it
    }

    /** Segments per rounded corner. Four is what imgui's own fast-arc path uses at this radius. */
    private const val ARC_SEGMENTS = 4

    /** A fixed-height box's child flags — see the note at its `beginChild`. */
    private val NO_SCROLL = ImGuiWindowFlags.NoScrollbar or ImGuiWindowFlags.NoScrollWithMouse

    private const val SEC_PAD_L = 10f      // left content inset
    private const val SEC_PAD_TOP = 3f     // gap below the (notched) label before the content
    private const val SEC_PAD_BOT = 4f     // gap below the content before the box bottom
    private const val CARD_PAD_X = 8f      // horizontal padding inside a [card]
    private const val CARD_PAD_Y = 6f      // vertical padding inside a [card]
    private const val CARD_GAP = 5f        // gap below a [card], separating it from the next
    private const val BADGE_PAD_X = 8f     // horizontal padding inside a badge (shared by badge/badgeWidth)
    private const val BADGE_GAP = 6f       // gap between adjacent badges, and from the row's right edge
    private const val SEC_RMARGIN = 6f     // right margin of the box from its container
    private const val SEC_GAP = 2f         // vertical gap after a box, before the next one
    private const val SEC_LABEL_MARGIN = 12f
    private const val SEC_LABEL_PAD = 5f

    /** Inner-right x of the current section box, so nested [field]s leave a right margin (0 = none). */
    private var sectionRight = 0f

    /** True while inside a [section]/groupBox body — the draw list is already channel-split there, so a nested
     *  [field] must NOT split again (splits don't nest); standalone it splits itself. */
    private var inGroupBox = false

    /** Last-frame content height of each max-height section (by label), so the child can grow to
     *  its content up to the cap — immediate mode can't know the height before drawing. */
    private val sectionContentH = HashMap<String, Float>()
}
