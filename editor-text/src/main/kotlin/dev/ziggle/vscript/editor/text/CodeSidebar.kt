package dev.ziggle.vscript.editor.text

import imgui.ImDrawList
import imgui.ImGui
import imgui.flag.ImGuiMouseButton
import dev.ziggle.imgui.Fonts
import dev.ziggle.imgui.Theme
import dev.ziggle.imgui.TextPaint
import java.io.File

/**
 * The column beside the code: the documents in the workspace, or the shape of the open one.
 *
 * ### One column, two views, a toggle
 *
 * They answer "which file" and "where in this file", and an author wants one at a time — so they share
 * the space rather than halving it. Two permanent panes on a canvas that is already sharing width with a
 * node editor would leave the code itself in a column too narrow to read, which is the thing all of this
 * exists to make readable.
 *
 * ### Drawn, not `ImGui::TreeNode`
 *
 * The same reason the canvas draws its own widgets: this has to sit inside a panel whose geometry is
 * decided by the workbench, with rows the height of the code's own line so the two read as one surface.
 * ImGui's tree widget brings its own padding, its own indent and its own font metrics, and fighting those
 * is more code than drawing a row.
 */
class CodeSidebar {

    enum class View { FILES, STRUCTURE }

    var view: View = View.FILES
    var visible: Boolean = true

    /** Where the workspace is rooted. Null until a host says; the tree is empty until then. */
    var workspace: Workspace? = null

    /** The file currently open, so the tree can mark it. */
    var current: File? = null

    /** Somebody clicked a document. */
    var onOpenFile: ((File) -> Unit)? = null

    /** Somebody clicked a symbol: go to this line of the open document. */
    var onGoToLine: ((Int) -> Unit)? = null

    /** The outline of the open document, supplied by the view that has it. */
    var outline: Outline = Outline(emptyList())

    private val collapsed = HashSet<String>()
    private var scroll = 0f

    /** Rows laid out by the last paint, so a click can be resolved without walking the tree again. */
    private class Row(val depth: Int, val label: String, val icon: Int, val kind: RowKind, val payload: Any?)

    private enum class RowKind { FOLDER, FILE, SYMBOL }

    private val rows = ArrayList<Row>()

    fun draw(x: Float, y: Float, w: Float, h: Float, lh: Float) {
        if (!visible || w <= 8f) return
        val dl = ImGui.getWindowDrawList()
        dl.addRectFilled(x, y, x + w, y + h, Theme.col(0x0E, 0x10, 0x16))
        dl.addLine(x + w, y, x + w, y + h, Theme.col(0x22, 0x26, 0x30))

        val tabH = lh + 8f
        tabs(dl, x, y, w, tabH)

        rows.clear()
        when (view) {
            View.FILES -> workspace?.let { collectFiles(it.tree(), 0) }
            View.STRUCTURE -> collectStructure()
        }

        val listY = y + tabH
        val listH = (h - tabH).coerceAtLeast(lh)
        scrollAndClick(x, listY, w, listH, lh)
        paint(dl, x, listY, w, listH, lh)
    }

    // ---- the toggle -------------------------------------------------------------------------------

    private fun tabs(dl: ImDrawList, x: Float, y: Float, w: Float, h: Float) {
        dl.addRectFilled(x, y, x + w, y + h, Theme.col(0x14, 0x16, 0x1D))
        val half = w / 2f
        for ((i, v) in View.values().withIndex()) {
            val tx = x + i * half
            val on = view == v
            if (on) dl.addRectFilled(tx, y, tx + half, y + h, Theme.col(0x1E, 0x22, 0x2C))
            val label = if (v == View.FILES) "Files" else "Structure"
            val tw = ImGui.calcTextSize(label).x
            dl.addText(
                tx + (half - tw) / 2f, y + (h - ImGui.getFontSize()) / 2f,
                if (on) Theme.TEXT else Theme.TEXT_DIM, label,
            )
            if (ImGui.isMouseClicked(ImGuiMouseButton.Left) &&
                ImGui.isMouseHoveringRect(tx, y, tx + half, y + h)
            ) {
                view = v
            }
        }
        dl.addLine(x, y + h, x + w, y + h, Theme.col(0x22, 0x26, 0x30))
    }

    // ---- what to show -----------------------------------------------------------------------------

    private fun collectFiles(node: Workspace.Node, depth: Int) {
        for (child in node.children) {
            if (child.isDirectory) {
                val key = child.file.path
                val open = key !in collapsed
                rows += Row(
                    depth, child.name,
                    if (open) Fonts.CHEVRON_DOWN else Fonts.CARET_RIGHT,
                    RowKind.FOLDER, key,
                )
                if (open) collectFiles(child, depth + 1)
            } else {
                rows += Row(depth, child.name.removeSuffix(".vs"), Fonts.CODE, RowKind.FILE, child.file)
            }
        }
    }

    private fun collectStructure() {
        for (s in outline.symbols) {
            rows += Row(0, s.name, iconFor(s.kind), RowKind.SYMBOL, s.line)
            for (c in s.children) rows += Row(1, c.name, iconFor(c.kind), RowKind.SYMBOL, c.line)
        }
    }

    /** One glyph per kind, so the shape of a document reads without any of it being spelled out. */
    private fun iconFor(kind: Outline.Kind): Int = when (kind) {
        Outline.Kind.FUNCTION -> Fonts.BOLT
        Outline.Kind.RECORD -> Fonts.GRID
        Outline.Kind.ENUM -> Fonts.VARIABLES
        Outline.Kind.SINGLE -> Fonts.GRID
        Outline.Kind.VARIABLE -> Fonts.SQUARE
        Outline.Kind.CONSTANT -> Fonts.SQUARE
        Outline.Kind.ENTRY -> Fonts.PLAY
        Outline.Kind.IMPORT -> Fonts.FOLDER
    }

    // ---- input and paint --------------------------------------------------------------------------

    private fun scrollAndClick(x: Float, y: Float, w: Float, h: Float, lh: Float) {
        val inside = ImGui.isMouseHoveringRect(x, y, x + w, y + h)
        val maxScroll = (rows.size * lh - h).coerceAtLeast(0f)
        if (inside) scroll = (scroll - ImGui.getIO().mouseWheel * lh * 3f).coerceIn(0f, maxScroll)
        else scroll = scroll.coerceIn(0f, maxScroll)

        if (!inside || !ImGui.isMouseClicked(ImGuiMouseButton.Left)) return
        val i = ((ImGui.getMousePosY() - y + scroll) / lh).toInt()
        val row = rows.getOrNull(i) ?: return
        when (row.kind) {
            // A folder toggles rather than opening anything -- the tree is for finding, and collapsing is
            // how you find in a tree with a hundred leaves.
            RowKind.FOLDER -> (row.payload as? String)?.let { if (!collapsed.remove(it)) collapsed.add(it) }
            RowKind.FILE -> (row.payload as? File)?.let { onOpenFile?.invoke(it) }
            RowKind.SYMBOL -> (row.payload as? Int)?.let { onGoToLine?.invoke(it) }
        }
    }

    private fun paint(dl: ImDrawList, x: Float, y: Float, w: Float, h: Float, lh: Float) {
        dl.pushClipRect(x, y, x + w, y + h, true)
        val first = (scroll / lh).toInt().coerceAtLeast(0)
        val last = ((scroll + h) / lh).toInt().coerceAtMost(rows.size - 1)
        val hovered = if (ImGui.isMouseHoveringRect(x, y, x + w, y + h)) {
            ((ImGui.getMousePosY() - y + scroll) / lh).toInt()
        } else -1

        for (i in first..last) {
            val row = rows[i]
            val ry = y + i * lh - scroll
            if (i == hovered) dl.addRectFilled(x, ry, x + w, ry + lh, Theme.col(0x1C, 0x20, 0x2A))
            val open = row.kind == RowKind.FILE && (row.payload as? File) == current
            if (open) dl.addRectFilled(x, ry, x + 2.5f, ry + lh, Theme.ACCENT)

            val indent = 8f + row.depth * 12f
            val fs = ImGui.getFontSize()
            dl.addText(
                x + indent, ry + (lh - fs) / 2f,
                if (row.kind == RowKind.FOLDER) Theme.TEXT_DIM else Theme.col(0x6C, 0x9E, 0xD8),
                Fonts.icon(row.icon),
            )
            val tx = x + indent + 16f
            dl.addText(
                tx, ry + (lh - fs) / 2f,
                if (open) Theme.TEXT else if (row.kind == RowKind.FOLDER) Theme.TEXT_DIM else Theme.TEXT,
                clip(row.label, w - (tx - x) - 8f),
            )
        }
        dl.popClipRect()
    }

    /** Cut a label that does not fit, with an ellipsis, so a deep tree does not spill into the code. */
    private fun clip(s: String, width: Float): String {
        if (width <= 0f || TextPaint.width(s) <= width) return s
        var cut = s.length
        while (cut > 1 && TextPaint.width(s.substring(0, cut) + "…") > width) cut--
        return s.substring(0, cut) + "…"
    }
}
