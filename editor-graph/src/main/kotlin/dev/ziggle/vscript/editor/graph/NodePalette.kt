package dev.ziggle.vscript.editor.graph

import imgui.ImGui
import imgui.flag.ImGuiInputTextFlags
import imgui.type.ImString
import dev.ziggle.imgui.FuzzySearch
import dev.ziggle.imgui.Theme
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeDescriptor
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.model.canConnect

/**
 * The add-node search popup.
 *
 * Search-first rather than a nested category menu: with a catalog that will run to hundreds of entries,
 * typing three letters beats walking a tree, and it is the interaction every comparable editor converged
 * on. Categories still show as a dim suffix so the list stays legible while browsing.
 *
 * When opened by dropping a wire, [render] is given the dragged pin's type and offers only nodes that can
 * actually accept it — the difference between "here is everything" and "here is what fits" is most of what
 * makes drag-to-create feel intelligent.
 */
class NodePalette(private val catalog: NodeCatalog) {

    private val query = ImString("", 64)
    private var selection = 0
    private var focusNextFrame = true

    /** Call when the popup opens so it starts empty and focused. */
    fun reset() {
        query.set("")
        selection = 0
        focusNextFrame = true
    }

    /**
     * Draw the palette; returns the chosen descriptor on the frame something is picked.
     *
     * [fromPin] filters to nodes with a compatible pin — null means no filter.
     */
    fun render(fromPin: TypeRef?): NodeDescriptor? {
        ImGui.textDisabled(if (fromPin == null) "Add node" else "Add node  ($fromPin)")
        ImGui.separator()

        if (focusNextFrame) {
            ImGui.setKeyboardFocusHere()
            focusNextFrame = false
        }
        ImGui.setNextItemWidth(260f)
        val submitted = ImGui.inputText("##palette-search", query, ImGuiInputTextFlags.EnterReturnsTrue)

        val results = search(query.get(), fromPin)
        if (results.isEmpty()) {
            ImGui.textDisabled("no matches")
            return null
        }
        selection = selection.coerceIn(0, results.size - 1)

        // Arrow keys move the highlight without leaving the search field.
        if (ImGui.isKeyPressed(imgui.flag.ImGuiKey.DownArrow)) selection = (selection + 1) % results.size
        if (ImGui.isKeyPressed(imgui.flag.ImGuiKey.UpArrow)) selection = (selection - 1 + results.size) % results.size

        var chosen: NodeDescriptor? = null
        ImGui.beginChild("##palette-list", 280f, 260f, false)
        results.forEachIndexed { i, d ->
            if (ImGui.selectable("${d.title}##$i", i == selection)) chosen = d
            ImGui.sameLine()
            ImGui.textDisabled(d.category)
            if (ImGui.isItemHovered() && d.summary.isNotEmpty()) ImGui.setTooltip(d.summary)
        }
        ImGui.endChild()

        if (submitted) chosen = results[selection]
        return chosen
    }

    private fun search(q: String, fromPin: TypeRef?): List<NodeDescriptor> {
        val candidates = catalog.all.filter { d -> fromPin == null || accepts(d, fromPin) }
        if (q.isBlank()) return candidates.sortedWith(compareBy({ it.category }, { it.title }))
        return candidates
            .mapNotNull { d -> FuzzySearch.score(d.title, q)?.let { s -> d to s } }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /** True when [d] has any pin the dragged [type] could connect to, in either direction. */
    private fun accepts(d: NodeDescriptor, type: TypeRef): Boolean =
        d.inputs.any { canConnect(type, it.type) } || d.outputs.any { canConnect(it.type, type) }
}
