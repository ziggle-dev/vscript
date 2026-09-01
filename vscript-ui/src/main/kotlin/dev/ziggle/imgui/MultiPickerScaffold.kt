package dev.ziggle.imgui

import imgui.ImDrawList
import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.type.ImString

object MultiPickerScaffold {

    private val filters = HashMap<String, ImString>()

    /**
     * @param selected current entry ids (order preserved; newly added go on the end).
     * @param boxHeight fixed height of the chip area (scrolls if the chips overflow).
     * @param label chip text for an id. @param drawIcon paints an id's icon into `(dl, id, x, y, size)`.
     * @param results ranked ids for the live query. @param drawRow paints a popup row (last arg = already
     *   selected). @return the new id list (unchanged unless the user added/removed this frame).
     */
    fun render(
        id: String,
        selected: List<Int>,
        width: Float,
        boxHeight: Float,
        chipHeight: Float,
        addLabel: String,
        rowHeight: Float,
        listHeight: Float,
        label: (Int) -> String,
        drawIcon: (ImDrawList, Int, Float, Float, Float) -> Unit,
        results: (String) -> List<Int>,
        drawRow: (ImDrawList, Int, Float, Float, Float, Float, Boolean, Boolean) -> Unit,
    ): List<Int> {
        val w = if (width > 0f) width else ImGui.calcItemWidth()
        val x0 = ImGui.getCursorScreenPosX()
        val y0 = ImGui.getCursorScreenPosY()
        val popupId = "##mpickpop_$id"
        val result = selected.toMutableList()

        var openAdd = false
        ImGui.getWindowDrawList().addRect(x0, y0, x0 + w, y0 + boxHeight, Theme.BORDER, Theme.ROUNDING, 0, 1f)
        ImGui.setCursorScreenPos(x0, y0)
        if (ImGui.beginChild("##mpick_$id", w, boxHeight, false)) {
            val dl = ImGui.getWindowDrawList()
            val pad = 5f; val gap = 4f
            val left = ImGui.getCursorScreenPosX() + pad
            val right = left + ImGui.getContentRegionAvailX() - pad
            val iconSize = chipHeight - 8f
            var cx = left
            var cy = ImGui.getCursorScreenPosY() + pad

            fun wrapFor(needW: Float) { if (cx + needW > right && cx > left) { cx = left; cy += chipHeight + gap } }
            val ty = { ImGui.getTextLineHeight().let { (chipHeight - it) / 2f } }

            for (eid in selected) {
                val name = label(eid)
                val chipW = pad + iconSize + 4f + ImGui.calcTextSize(name).x + 6f + 10f // icon + name + ×
                wrapFor(chipW)
                ImGui.setCursorScreenPos(cx, cy)
                val clicked = ImGui.invisibleButton("##chip_${id}_$eid", chipW, chipHeight)
                val hov = ImGui.isItemHovered()
                dl.addRectFilled(cx, cy, cx + chipW, cy + chipHeight, if (hov) Theme.BAD else Theme.CARD, Theme.ROUNDING)
                drawIcon(dl, eid, cx + pad, cy + (chipHeight - iconSize) / 2f, iconSize)
                val txtCol = if (hov) ON_DANGER else Theme.TEXT
                dl.addText(cx + pad + iconSize + 4f, cy + ty(), txtCol, name)
                dl.addText(cx + chipW - 12f, cy + ty(), txtCol, "×")
                if (clicked) result.remove(eid)
                cx += chipW + gap
            }

            val addW = ImGui.calcTextSize(addLabel).x + 16f
            wrapFor(addW)
            ImGui.setCursorScreenPos(cx, cy)
            if (ImGui.invisibleButton("##mpadd_$id", addW, chipHeight)) {
                filters.getOrPut(id) { ImString("", 128) }.set("")
                openAdd = true // opened below, in the parent scope (see note at endChild)
            }
            val addHov = ImGui.isItemHovered()
            dl.addRectFilled(cx, cy, cx + addW, cy + chipHeight, if (addHov) Theme.BUTTON_HOVER else Theme.BUTTON, Theme.ROUNDING)
            dl.addText(cx + 8f, cy + ty(), Theme.TEXT_ACCENT, addLabel)
            // Mark the content bottom so the child scrolls when the chips overflow the fixed box.
            ImGui.setCursorScreenPos(left, cy + chipHeight + pad); ImGui.dummy(1f, 1f)
        }
        ImGui.endChild()
        // openPopup must run in the SAME window/ID scope as beginPopup below — calling it inside the child
        // above keys the popup to the child window, so ImGui never opens it. Defer it to here.
        if (openAdd) ImGui.openPopup(popupId)
        searchPopup(id, popupId, x0, y0 + boxHeight + 2f, w, listHeight, rowHeight, result, results, drawRow)
        return result
    }

    /**
     * The quantified counterpart to [render]: each chosen entry gets an **editable quantity**, laid out as a
     * vertical list (`icon · name · [qty] · ×`) rather than wrapped chips, because a per-entry number field
     * needs a stable target that wrapped chips don't give it.
     *
     * [quantities] is mutated in place — new entries seeded with [defaultQty], removed entries dropped, edits
     * written back — so the caller reads quantities straight from the same map it passed in. The returned
     * list is the membership (as [render]).
     */
    fun renderQuantified(
        id: String,
        selected: List<Int>,
        quantities: MutableMap<Int, Int>,
        defaultQty: Int,
        width: Float,
        lineHeight: Float,
        addLabel: String,
        rowHeight: Float,
        listHeight: Float,
        label: (Int) -> String,
        drawIcon: (ImDrawList, Int, Float, Float, Float) -> Unit,
        results: (String) -> List<Int>,
        drawRow: (ImDrawList, Int, Float, Float, Float, Float, Boolean, Boolean) -> Unit,
    ): List<Int> {
        val w = if (width > 0f) width else ImGui.calcItemWidth()
        val result = selected.toMutableList()
        val pad = 5f
        val iconSize = lineHeight - 8f
        val qtyW = 54f
        val rmW = 22f
        val dl = ImGui.getWindowDrawList()

        for (eid in selected) {
            val x = ImGui.getCursorScreenPosX()
            val y = ImGui.getCursorScreenPosY()
            dl.addRectFilled(x, y, x + w, y + lineHeight, Theme.CARD, Theme.ROUNDING)
            drawIcon(dl, eid, x + pad, y + (lineHeight - iconSize) / 2f, iconSize)
            val ty = y + (lineHeight - ImGui.getTextLineHeight()) / 2f
            dl.addText(x + pad + iconSize + 6f, ty, Theme.TEXT, label(eid))

            // Quantity: a real input, right-aligned before the remove button. Keyed per (picker, entry) so
            // the buffer survives across frames and edits don't bleed between rows.
            ImGui.setCursorScreenPos(x + w - qtyW - rmW - pad, y + (lineHeight - 22f) / 2f)
            val buf = qtyBufs.getOrPut("$id:$eid") { ImString((quantities[eid] ?: defaultQty).toString(), 8) }
            ImGui.setNextItemWidth(qtyW)
            ImGui.pushStyleColor(ImGuiCol.FrameBg, Theme.BUTTON)
            if (ImGui.inputText("##qty_${id}_$eid", buf, INT_INPUT_FLAGS)) {
                quantities[eid] = buf.get().trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
            }
            ImGui.popStyleColor()

            // Remove: a small × target at the far right, distinct from the qty field so editing never
            // deletes the row.
            ImGui.setCursorScreenPos(x + w - rmW, y)
            if (ImGui.invisibleButton("##qrm_${id}_$eid", rmW, lineHeight)) {
                result.remove(eid); quantities.remove(eid); qtyBufs.remove("$id:$eid")
            }
            val rmHov = ImGui.isItemHovered()
            dl.addText(x + w - rmW + 6f, ty, if (rmHov) Theme.BAD else Theme.TEXT_DIM, "×")

            ImGui.setCursorScreenPos(x, y + lineHeight + 3f)
        }

        // Add button + shared search popup.
        val popupId = "##qpickpop_$id"
        val addW = ImGui.calcTextSize(addLabel).x + 20f
        val ax = ImGui.getCursorScreenPosX(); val ay = ImGui.getCursorScreenPosY()
        if (ImGui.invisibleButton("##qadd_$id", addW, lineHeight)) {
            filters.getOrPut(id) { ImString("", 128) }.set(""); ImGui.openPopup(popupId)
        }
        val addHov = ImGui.isItemHovered()
        dl.addRectFilled(ax, ay, ax + addW, ay + lineHeight, if (addHov) Theme.BUTTON_HOVER else Theme.BUTTON, Theme.ROUNDING)
        dl.addText(ax + 10f, ay + (lineHeight - ImGui.getTextLineHeight()) / 2f, Theme.TEXT_ACCENT, addLabel)
        ImGui.setCursorScreenPos(ax, ay + lineHeight)

        val before = result.toSet()
        searchPopup(id, popupId, ax, ay + lineHeight + 2f, w, listHeight, rowHeight, result, results, drawRow)
        // Reconcile the quantity map with whatever the popup toggled: seed new ids, and (defensively) drop
        // any the popup removed. Additions are the common case — a fresh pick starts at the default amount.
        for (eid in result) if (eid !in before) quantities.putIfAbsent(eid, defaultQty)
        quantities.keys.retainAll(result.toSet())
        return result
    }

    /**
     * The searchable add-popup shared by [render] and [renderQuantified]: a filter box over a scrolling list
     * of results, each row toggling membership in [result] and leaving the popup open so several can be added
     * in one go. Anchored at ([anchorX], [anchorY]); [openPopup] must already have run in this ID scope.
     */
    private fun searchPopup(
        id: String,
        popupId: String,
        anchorX: Float,
        anchorY: Float,
        w: Float,
        listHeight: Float,
        rowHeight: Float,
        result: MutableList<Int>,
        results: (String) -> List<Int>,
        drawRow: (ImDrawList, Int, Float, Float, Float, Float, Boolean, Boolean) -> Unit,
    ) {
        val popW = maxOf(w, 280f)
        ImGui.setNextWindowPos(anchorX, anchorY)
        ImGui.setNextWindowSize(popW, 0f)
        if (ImGui.beginPopup(popupId)) {
            val filter = filters.getOrPut(id) { ImString("", 128) }
            if (ImGui.isWindowAppearing()) ImGui.setKeyboardFocusHere()
            ImGui.setNextItemWidth(popW - 20f)
            ImGui.inputTextWithHint("##mpfilter_$id", "Search…", filter)
            ImGui.separator()
            val ids = results(filter.get())
            if (ImGui.beginChild("##mplist_$id", popW - 20f, listHeight, false)) {
                if (ids.isEmpty()) {
                    ImGui.pushStyleColor(ImGuiCol.Text, Theme.TEXT_DIM)
                    ImGui.textWrapped(if (filter.get().isBlank()) "Type to search…" else "No matches.")
                    ImGui.popStyleColor()
                }
                val ldl = ImGui.getWindowDrawList()
                val rowW = ImGui.getContentRegionAvailX()
                for (eid in ids) {
                    val rx = ImGui.getCursorScreenPosX(); val ry = ImGui.getCursorScreenPosY()
                    val rowClicked = ImGui.invisibleButton("##mprow_${id}_$eid", rowW, rowHeight)
                    val rowHov = ImGui.isItemHovered()
                    val isSel = eid in result
                    val bg = if (isSel) Theme.ACCENT else if (rowHov) Theme.CARD_HOVER else Theme.CARD
                    ldl.addRectFilled(rx, ry, rx + rowW, ry + rowHeight, bg, Theme.ROUNDING)
                    drawRow(ldl, eid, rx, ry, rowW, rowHeight, rowHov, isSel)
                    ImGui.dummy(0f, 3f)
                    if (rowClicked) { if (isSel) result.remove(eid) else result.add(eid) } // toggle; popup stays open
                }
            }
            ImGui.endChild()
            ImGui.endPopup()
        }
    }

    private val qtyBufs = HashMap<String, ImString>()
    private val INT_INPUT_FLAGS = imgui.flag.ImGuiInputTextFlags.CharsDecimal

    private val ON_DANGER = Theme.col(0x10, 0x12, 0x18) // dark text over a chip's red remove-hover fill
}
