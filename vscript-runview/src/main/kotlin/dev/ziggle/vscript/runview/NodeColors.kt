package dev.ziggle.vscript.runview

import dev.ziggle.imgui.Theme
import dev.ziggle.vscript.model.NodeKind

/**
 * What colour a node is, by the family it belongs to.
 *
 * ### Why this is in the run views and not on the canvas
 *
 * It was `NodeColors.headerColor`, and `PinStyle` is otherwise about PINS — wire colours, pin shapes, the
 * canvas backdrop. This one function is different in kind: it answers a question about the CATALOGUE, not
 * about a canvas, and three different things ask it. The canvas paints a node header with it, the palette
 * paints a swatch beside a list entry, and the console tints a log row by the node that wrote it.
 *
 * That last one is the reason it moved. The run views are shared by both authoring surfaces — they show a
 * running program, and a program runs the same whichever surface wrote it — so a run view reaching into
 * the canvas to colour a row is an arrow pointing the wrong way. Here, both surfaces may use it and the
 * console needs nothing above itself.
 *
 * Keyed on category rather than node type so a whole family shares one colour and every consumer agrees
 * without a per-node entry.
 */
object NodeColors {

    fun headerColor(category: String, kind: NodeKind): Int = when {
        kind == NodeKind.ENTRY -> Theme.col(0x8E, 0x44, 0x4C)
        category == "Flow" -> Theme.col(0x3E, 0x44, 0x58)
        category == "Math" || category == "Compare" || category == "Logic" -> Theme.col(0x35, 0x5A, 0x48)
        category == "Variables" -> Theme.col(0x4A, 0x3E, 0x60)
        category == "Values" -> Theme.col(0x3A, 0x4E, 0x60)
        else -> Theme.col(0x2E, 0x3D, 0x5C)
    }
}
