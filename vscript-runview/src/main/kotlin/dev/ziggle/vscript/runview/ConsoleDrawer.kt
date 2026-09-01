package dev.ziggle.vscript.runview

import dev.ziggle.imgui.PanelBits
import dev.ziggle.vscript.runtime.EditorDoc
import imgui.ImDrawList
import imgui.ImGui
import imgui.flag.ImDrawFlags
import imgui.flag.ImGuiMouseButton
import dev.ziggle.imgui.Fonts
import dev.ziggle.imgui.Theme
import dev.ziggle.imgui.PanelBits.ERROR
import dev.ziggle.imgui.PanelBits.ERROR_ROW
import dev.ziggle.imgui.PanelBits.ERROR_TEXT
import dev.ziggle.imgui.PanelBits.MUTED
import dev.ziggle.imgui.PanelBits.PAD
import dev.ziggle.imgui.PanelBits.ROW_H
import dev.ziggle.imgui.PanelBits.STAMP
import dev.ziggle.imgui.PanelBits.WARN
import dev.ziggle.imgui.PanelBits.WARN_ROW
import dev.ziggle.imgui.PanelBits.WARN_TEXT
import dev.ziggle.vscript.log.LogLevel
import dev.ziggle.vscript.log.LogRecord
import dev.ziggle.vscript.log.ScriptLog
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind

/**
 * The console tab: an **annotation layer** over the graph, not a terminal.
 *
 * Every message has a node that emitted it, and the link runs both ways — a row reveals its node on the
 * canvas, and a node's badge filters this down to that node. In a node editor you rarely want the whole
 * stream; you want to know why *that* node misbehaved.
 *
 * It owns its rows and its own filters. The frame it sits in — collapse, resize, tab strip — belongs to
 * [DebugPanel], because the console is one of several things that share that space.
 */
class ConsoleDrawer(private val catalog: NodeCatalog) {

    /** Severity filter. Null shows everything. */
    private var filter: LogLevel? = null

    /** When set, only this node's records are listed — what a badge click leaves behind. */
    var nodeFilter: Int = -1
        private set

    private var followTail = true

    fun filterTo(nodeId: Int) {
        nodeFilter = nodeId
        filter = null
        followTail = false
    }

    /** Console-specific header controls, drawn from [x]. Returns where it ended. */
    fun headerLeft(dl: ImDrawList, x: Float, y: Float): Float {
        var cx = x
        return cx
    }

    /** Level chips and the run summary / actions, filling the header's right-hand side. */
    fun header(dl: ImDrawList, log: ScriptLog, x: Float, y: Float, width: Float) {
        val counts = log.counts()
        var cx = x
        cx += PanelBits.pill(dl, "##vs-f-all", cx, y, "All ${counts.total}", filter == null, Theme.TEXT) { filter = null }
        cx += PanelBits.pill(dl, "##vs-f-info", cx, y, "Info ${counts.info}", filter == LogLevel.INFO, MUTED) { filter = LogLevel.INFO }
        cx += PanelBits.pill(dl, "##vs-f-warn", cx, y, "Warn ${counts.warn}", filter == LogLevel.WARN, WARN) { filter = LogLevel.WARN }
        cx += PanelBits.pill(dl, "##vs-f-err", cx, y, "Error ${counts.error}", filter == LogLevel.ERROR, ERROR) { filter = LogLevel.ERROR }

        // A node filter is a MODE, so it says so and offers a way out — a list that is silently a subset is
        // the sort of thing you spend ten minutes not noticing.
        if (nodeFilter >= 0) {
            cx += 6f
            val name = nodeTitles[nodeFilter] ?: "node $nodeFilter"
            PanelBits.pill(dl, "##vs-f-node", cx, y, "$name  ${PanelBits.icon(Fonts.CLOSE)}", true, Theme.ACCENT) {
                nodeFilter = -1
            }
        }

        var rx = x + width - PAD
        rx -= PanelBits.ICON
        if (PanelBits.iconButton(dl, "##vs-console-clear", rx, y, PanelBits.icon(Fonts.TRASH), "Clear")) log.clear()
        rx -= PanelBits.ICON + 2f
        if (PanelBits.iconButton(dl, "##vs-console-export", rx, y, PanelBits.icon(Fonts.EXPORT), "Copy to clipboard")) {
            runCatching { ImGui.setClipboardText(log.exportText()) }
        }
        val summary = "run ${log.runId} · ${"%.2f".format(log.elapsedNanos() / 1e9)}s"
        rx -= ImGui.calcTextSize(summary).x + 10f
        dl.addText(rx, y + (PanelBits.HEADER_H - ImGui.getTextLineHeight()) * 0.5f, MUTED, summary)
    }

    /**
     * The list.
     *
     * **Virtualised**: only rows inside the viewport are drawn, however many the ring holds. A loop can
     * produce four thousand records in a second, and laying out four thousand rows a frame would make the
     * console the slowest thing in the editor at exactly the moment you need to read it.
     *
     * @return a node id to reveal, or -1.
     */
    fun body(log: ScriptLog, x: Float, y: Float, width: Float, viewH: Float): Int {
        var reveal = -1
        val visible = log.records.filter { r ->
            (filter == null || r.level == filter) && (nodeFilter < 0 || r.nodeId == nodeFilter)
        }

        ImGui.setCursorScreenPos(x, y)
        ImGui.beginChild("##vs-console-rows", width, viewH, false)
        try {
            val dl = ImGui.getWindowDrawList()
            val top = ImGui.getCursorScreenPosY()
            // Measured INSIDE the list, so it excludes a scrollbar when one is present — otherwise the
            // right-aligned hint is drawn under it and clipped.
            val rowW = ImGui.getContentRegionAvailX()
            val scroll = ImGui.getScrollY()
            val header = if (log.dropped > 0) ROW_H else 0f
            ImGui.dummy(1f, header + visible.size * ROW_H)

            if (log.dropped > 0) {
                dl.addText(x + PAD, top + (ROW_H - ImGui.getTextLineHeight()) * 0.5f, MUTED,
                    "${log.dropped} earlier entries dropped")
            }

            val first = ((scroll - header) / ROW_H).toInt().coerceAtLeast(0)
            val last = (((scroll + viewH - header) / ROW_H).toInt() + 1).coerceAtMost(visible.size - 1)
            val hovered = ImGui.isWindowHovered()
            for (i in first..last) {
                val r = visible[i]
                if (row(dl, r, log, x, top + header + i * ROW_H, rowW, hovered)) reveal = r.nodeId
            }

            // Follow the tail unless the reader has scrolled away — a console that yanks you back to the
            // bottom while you are reading something is worse than one that never scrolls at all.
            val atBottom = ImGui.getScrollMaxY() - scroll < ROW_H
            if (ImGui.getScrollMaxY() > 0f) followTail = atBottom || (followTail && scroll >= ImGui.getScrollMaxY() - 1f)
            if (followTail) ImGui.setScrollHereY(1f)
        } finally {
            ImGui.endChild()
        }
        return reveal
    }

    /** @return true when this row asked to reveal its node. */
    private fun row(dl: ImDrawList, r: LogRecord, log: ScriptLog, x: Float, y: Float, width: Float, canHover: Boolean): Boolean {
        val hovered = canHover && ImGui.isMouseHoveringRect(x, y, x + width, y + ROW_H)
        val tint = when (r.level) {
            LogLevel.ERROR -> ERROR_ROW
            LogLevel.WARN -> WARN_ROW
            LogLevel.INFO -> 0
        }
        if (tint != 0) dl.addRectFilled(x, y, x + width, y + ROW_H, tint)
        if (hovered) dl.addRectFilled(x, y, x + width, y + ROW_H, Theme.GHOST_REST)

        val ty = y + (ROW_H - ImGui.getTextLineHeight()) * 0.5f
        var cx = x + PAD

        // Relative to the run, not the wall clock: you care about the shape of execution, not the time.
        dl.addText(cx, ty, STAMP, "%+.3fs".format((r.atNanos - log.runStartNanos) / 1e9))
        cx += TIME_W

        // The source chip carries the node's own category colour, so "where did this come from" is
        // answerable by scanning rather than by reading.
        val src = sourceName(r)
        val chipCol = sourceColor(r)
        val cw = ImGui.calcTextSize(src).x + CHIP_PAD * 2
        dl.addRectFilled(cx, y + 4f, cx + cw, y + ROW_H - 4f, Theme.withAlpha(chipCol, 0.16f), 3f, ImDrawFlags.RoundCornersAll)
        dl.addText(cx + CHIP_PAD, ty, chipCol, src)
        cx += cw + 10f

        val msgCol = when (r.level) {
            LogLevel.ERROR -> ERROR_TEXT
            LogLevel.WARN -> WARN_TEXT
            LogLevel.INFO -> Theme.TEXT_DIM
        }
        // Through the rich-text painter, so a Log node can colour a word or strike one out. Plain messages
        // take the same path and come out identical — one span, no decoration, one addText.
        cx += dev.ziggle.vscript.runview.RichTextDraw.panel(dl, cx, ty, r.message, msgCol) + 8f

        if (r.repeats > 1) {
            val n = "×${r.repeats}"
            val nw = ImGui.calcTextSize(n).x + 10f
            dl.addRectFilled(cx, y + 4f, cx + nw, y + ROW_H - 4f, Theme.withAlpha(msgCol, 0.14f), 8f, ImDrawFlags.RoundCornersAll)
            dl.addText(cx + 5f, ty, msgCol, n)
        }

        if (hovered && r.nodeId >= 0) {
            val hint = "${PanelBits.icon(Fonts.TARGET)} reveal"
            dl.addText(x + width - ImGui.calcTextSize(hint).x - PAD, ty, MUTED, hint)
            if (r.activation > 0) ImGui.setTooltip("${sourceName(r)} · activation ${r.activation}")
            return ImGui.isMouseClicked(ImGuiMouseButton.Left)
        }
        return false
    }

    private fun sourceName(r: LogRecord): String =
        if (r.nodeId < 0) "engine" else nodeTitles[r.nodeId] ?: "node ${r.nodeId}"

    private fun sourceColor(r: LogRecord): Int = when {
        r.level == LogLevel.ERROR -> ERROR
        r.level == LogLevel.WARN -> WARN
        r.nodeId < 0 -> MUTED
        else -> nodeColors[r.nodeId] ?: MUTED
    }

    /**
     * Node titles and header colours, refreshed each frame.
     *
     * Accumulated and never cleared: a record outlives the node that made it, so deleting a node should not
     * turn its rows into "node 12" retroactively.
     */
    private val nodeTitles = HashMap<Int, String>()
    private val nodeColors = HashMap<Int, Int>()

    fun describeNodes(doc: EditorDoc?) {
        if (doc == null) return
        for (n in doc.nodes) {
            val d = catalog[n.type] ?: continue
            if (d.kind == NodeKind.COMMENT) continue
            nodeTitles[n.id] = d.title
            nodeColors[n.id] = Theme.shade(NodeColors.headerColor(d.category, d.kind), 2.1f)
        }
    }

    fun titleOf(nodeId: Int): String = nodeTitles[nodeId] ?: if (nodeId < 0) "engine" else "node $nodeId"

    fun colorOf(nodeId: Int): Int = nodeColors[nodeId] ?: MUTED

    private companion object {
        /** Fixed column so timestamps line up — a ragged left edge makes a log much harder to scan. */
        const val TIME_W = 58f
        const val CHIP_PAD = 6f
    }
}
