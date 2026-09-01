package dev.ziggle.imgui

import imgui.ImDrawList
import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.type.ImString

object PickerScaffold {

    private val filters = HashMap<String, ImString>()

    /**
     * @param selected the currently-selected entry id (`< 0` = none).
     * @param drawClosed paints the closed control's interior into its `(dl, x, y, w, h)` box.
     * @param results ranked entry ids for the live query (empty query ⇒ the caller returns nothing).
     * @param drawRow paints one result row: `(dl, entryId, x, y, w, h, hovered, isSelected)`.
     * @return the new selected id (unchanged unless the user picked a row this frame).
     */
    fun render(
        id: String,
        selected: Int,
        width: Float,
        closedHeight: Float,
        rowHeight: Float,
        listHeight: Float,
        emptyQueryCaption: String? = null,
        drawClosed: (ImDrawList, Float, Float, Float, Float) -> Unit,
        results: (String) -> List<Int>,
        drawRow: (ImDrawList, Int, Float, Float, Float, Float, Boolean, Boolean) -> Unit,
    ): Int {
        val w = if (width > 0f) width else ImGui.calcItemWidth()
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val popupId = "##pickpop_$id"

        // Closed control: a rounded, bordered box with a chevron; the caller fills its interior.
        val clicked = ImGui.invisibleButton(id, w, closedHeight)
        val hovered = ImGui.isItemHovered()
        val dl = ImGui.getWindowDrawList()
        dl.addRectFilled(x, y, x + w, y + closedHeight, if (hovered) Theme.BUTTON_HOVER else Theme.BUTTON, Theme.ROUNDING)
        dl.addRect(x, y, x + w, y + closedHeight, Theme.BORDER, Theme.ROUNDING, 0, 1f)
        drawClosed(dl, x, y, w - 16f, closedHeight)
        val cx = x + w - 14f
        val cyc = y + closedHeight / 2f
        dl.addTriangleFilled(cx - 4f, cyc - 2f, cx + 4f, cyc - 2f, cx, cyc + 3f, Theme.TEXT_DIM)

        if (clicked) {
            filters.getOrPut(id) { ImString("", 128) }.set("")
            ImGui.openPopup(popupId)
        }

        var result = selected
        val popW = maxOf(w, 280f)
        ImGui.setNextWindowPos(x, y + closedHeight + 2f)
        ImGui.setNextWindowSize(popW, 0f)
        if (ImGui.beginPopup(popupId)) {
            val filter = filters.getOrPut(id) { ImString("", 128) }
            if (ImGui.isWindowAppearing()) ImGui.setKeyboardFocusHere()
            ImGui.setNextItemWidth(popW - 20f)
            ImGui.inputTextWithHint("##pickfilter_$id", "Search…", filter)
            ImGui.separator()
            val ids = results(filter.get())
            if (ImGui.beginChild("##picklist_$id", popW - 20f, listHeight, false)) {
                val blank = filter.get().isBlank()
                if (ids.isEmpty()) {
                    ImGui.pushStyleColor(ImGuiCol.Text, Theme.TEXT_DIM)
                    ImGui.textWrapped(if (blank) "Type to search…" else "No matches.")
                    ImGui.popStyleColor()
                } else if (blank && emptyQueryCaption != null) {
                    ImGui.pushStyleColor(ImGuiCol.Text, Theme.TEXT_DIM)
                    ImGui.text(emptyQueryCaption)
                    ImGui.popStyleColor()
                    ImGui.dummy(0f, 2f)
                }
                val listDl = ImGui.getWindowDrawList()
                val rowW = ImGui.getContentRegionAvailX()
                for (entryId in ids) {
                    val rx = ImGui.getCursorScreenPosX()
                    val ry = ImGui.getCursorScreenPosY()
                    val rowClicked = ImGui.invisibleButton("##pickrow_${id}_$entryId", rowW, rowHeight)
                    val rowHovered = ImGui.isItemHovered()
                    val isSel = entryId == selected
                    val bg = when {
                        isSel -> Theme.ACCENT
                        rowHovered -> Theme.CARD_HOVER
                        else -> Theme.CARD
                    }
                    listDl.addRectFilled(rx, ry, rx + rowW, ry + rowHeight, bg, Theme.ROUNDING)
                    drawRow(listDl, entryId, rx, ry, rowW, rowHeight, rowHovered, isSel)
                    ImGui.dummy(0f, 1f) // gap between cards
                    if (rowClicked) {
                        result = entryId
                        ImGui.closeCurrentPopup()
                    }
                }
            }
            ImGui.endChild()
            ImGui.endPopup()
        }
        return result
    }
}
