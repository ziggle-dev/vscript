package dev.ziggle.vscript.editor.graph

import dev.ziggle.imgui.TextEdit
import dev.ziggle.vscript.model.Node
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeDescriptor
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.model.effectivePinType

/**
 * Where a node's box and every one of its pins sit, in graph space.
 *
 * **Size is measured from content, not declared.** The reference implementation used a fixed per-type
 * width and a height of `pinCount × 20`, which is why its titles were truncated and its pin labels
 * overflowed the body. Here the width comes from the widest thing the node actually has to show — its
 * title, or its widest input/output row — so a node is never too small for its own text and never wider
 * than it needs to be.
 *
 * One geometry object is built per node per frame and handed to both the renderer and the hit-tester, so
 * what is drawn and what responds to the mouse can never disagree.
 */
class NodeGeometry(
    val node: Node,
    val desc: NodeDescriptor,
    val rect: Rect,
    /** Input pin anchors (graph space), in descriptor order. */
    val inputAnchors: List<Pin>,
    val outputAnchors: List<Pin>,
    /**
     * A comment's body, wrapped in GRAPH space.
     *
     * Graph space, not screen: this feeds the box's content inset, which the document stores, so it must
     * not change with the zoom the author happened to be at. Empty for everything that is not a comment
     * with body text.
     */
    val bodyLines: List<String> = emptyList(),
    /** A container's second line of header text — a function's signature. Empty for a comment. */
    val subtitle: String = "",
) {
    class Pin(
        val spec: PinSpec,
        val index: Int,
        val input: Boolean,
        val x: Float,
        val y: Float,
        /** Where this pin's inline value editor goes, or null when it has none (connected, exec, output). */
        val editor: Rect? = null,
        /**
         * What this pin actually carries.
         *
         * The same as `spec.type` everywhere except a variable node, whose value pin is declared wildcard
         * so one node type can serve every variable — see [dev.ziggle.vscript.model.effectivePinType]. Colour,
         * connection checks and the inline editor all read THIS, so a `Get counter` on a boolean looks and
         * behaves like a boolean rather than like "anything".
         */
        val type: TypeRef = spec.type,
    ) {
        /** Generous hit radius — see [NodeGeometry.HIT_RADIUS]. */
        fun hit(gx: Float, gy: Float): Boolean {
            val dx = gx - x
            val dy = gy - y
            return dx * dx + dy * dy <= HIT_RADIUS * HIT_RADIUS
        }
    }

    val headerRect: Rect get() = Rect(rect.x, rect.y, rect.w, HEADER_H)

    // Where a comment's body text lives, under its title strip. Read by the painter AND by the body
    // editor, so the note does not shift by a few pixels the moment you double-click it — a jump at the
    // start of an edit reads as the text having changed.
    val bodyTextX: Float get() = rect.x + PAD_X
    /**
     * The fold toggle, in graph space — null on anything that cannot fold.
     *
     * Derived here rather than computed at each site: a toggle drawn in one place and hit-tested in
     * another is the classic way a control ends up looking clickable an inch from where it is.
     */
    val foldRect: Rect?
        get() = if (desc.kind != NodeKind.FUNCTION) null
        else Rect(rect.right - FOLD_W, rect.y, FOLD_W, HEADER_H)

    val bodyTextY: Float get() = rect.y + HEADER_H + COMMENT_BODY_TOP
    val bodyTextW: Float get() = (rect.w - PAD_X * 2).coerceAtLeast(1f)
    val bodyLineH: Float get() = lineHeight()

    /**
     * Y at which the text block ends — where the rule below the body goes.
     *
     * The header bottom when there is no body, so a comment with only a heading keeps exactly the shape it
     * had before bodies existed: one strip, one rule, then contents.
     */
    val textBottom: Float
        get() = if (bodyLines.isEmpty()) rect.y + HEADER_H
        else bodyTextY + bodyLines.size * bodyLineH + COMMENT_BODY_BOTTOM

    /** Distance from the box's top edge to where its contents may begin. */
    val contentInset: Float get() = textBottom - rect.y

    fun anchor(input: Boolean, index: Int): Pin? =
        (if (input) inputAnchors else outputAnchors).getOrNull(index)

    fun pinAt(gx: Float, gy: Float): Pin? =
        inputAnchors.firstOrNull { it.hit(gx, gy) } ?: outputAnchors.firstOrNull { it.hit(gx, gy) }

    companion object {
        const val HEADER_H = 26f

        /** A reroute knot's box — just big enough to grab. */
        const val KNOT = 16f

        const val ROW_H = 22f
        const val PAD_X = 10f
        const val PAD_Y = 6f
        const val PIN_RADIUS = 5f

        /** Gap between the input column and the output column. */
        const val COL_GAP = 20f

        /**
         * Distance from a pin's centre to its label.
         *
         * The renderer draws a label at `PIN_RADIUS + 6` from the pin, on both sides. Measuring with a
         * different number than the painter uses is how a node ends up either padded with dead space or
         * one glyph too narrow, so both read this.
         */
        const val PIN_LABEL_OFFSET = PIN_RADIUS + 6f

        /** Gap between an input's label and its inline value editor. */
        const val EDITOR_GAP = 6f

        /** Height of an inline value editor. Width is per-type — see [editorWidth]. */
        const val EDITOR_H = 16f

        /**
         * Inline editor widths, by what the widget actually needs.
         *
         * A single width for every type was what made a node like `Not` far wider than its content: a
         * boolean draws a ~26px toggle but reserved the same 62px a number field needs, and the surplus
         * went straight into the node's width — where it then collided with the output label, because the
         * editor is right-aligned and the reservation said there was room.
         */
        const val TOGGLE_W = 26f
        const val NUMBER_W = 56f
        const val STRING_W = 84f
        const val TILE_W = 126f

        /** Padding around a choice chip's widest option. */
        const val CHOICE_PAD = 18f

        const val MIN_W = 110f
        const val CORNER = 6f

        /** Trailing room after a node's title, so a header is never filled edge to edge. */
        const val TITLE_TRAIL = 12f

        /** Gap under a comment's title strip, leaving room for the separator rule. */
        const val COMMENT_BODY_TOP = 10f

        /** Gap under a comment's body text, before the rule that closes it off. */
        const val COMMENT_BODY_BOTTOM = 8f

        /**
         * Wrapped body lines for a comment [width] wide, measured in graph units.
         *
         * Exposed as a function as well as a property because [commentInset] has to answer "how tall would
         * this box's text be?" for a box that does not exist yet — creating a comment around a selection
         * needs the inset to know where to put the top edge.
         */
        fun commentBody(body: String?, width: Float): List<String> {
            if (body.isNullOrBlank()) return emptyList()
            val maxW = (width - PAD_X * 2).coerceAtLeast(1f)
            return TextEdit
                .layout(body, maxW, lineHeightBase()) { TextMeasure.width(it) }
                .lines.map { it.text }
        }

        /**
         * Distance from a comment's top edge to where its contents may begin — the strip, plus any body.
         *
         * Callers that place or re-fit a box use this instead of [HEADER_H] so the nodes inside never sit
         * under the note describing them.
         */
        fun commentInset(body: String?, width: Float): Float {
            val lines = commentBody(body, width)
            if (lines.isEmpty()) return HEADER_H
            return HEADER_H + COMMENT_BODY_TOP + lines.size * lineHeight() + COMMENT_BODY_BOTTOM
        }

        /** Baseline text size in graph units, and the row pitch derived from it. */
        private fun lineHeightBase(): Float = TextMeasure.lineHeight()

        internal fun lineHeight(): Float = TextMeasure.lineHeight() * TextEdit.LINE_SPACING

        /**
         * Width an unconnected input of [type] reserves for its inline editor, or 0 when it has none.
         *
         * Types with no editor reserve nothing — a `List` or `Entity` pin can only be filled by a wire, so
         * padding its row for a field that is never drawn just makes the node wider for no reason.
         */
        /** Room for [spec]'s inline widget: a choice sizes to its longest option, everything else is fixed. */
        fun editorWidth(spec: PinSpec): Float {
            // A type choice is sized like a name field, not to its options: the registry can grow, and a
            // node that changed width because a document declared a type would be a strange thing to watch.
            if (spec.typeChoice) return STRING_W
            if (spec.type.builtin == dev.ziggle.vscript.model.PinType.ENUM) {
                val widest = spec.options.maxOfOrNull { TextMeasure.width(it) } ?: 0f
                return widest + CHOICE_PAD
            }
            return editorWidth(spec.type)
        }

        /**
         * How much room [type]'s inline editor needs.
         *
         * Asked of the derivation rather than mapped from a pin type, so a DOMAIN's types get a sensible
         * width without registering one: a type the host has a catalogue for is as wide as a name, a
         * small record is as wide as its fields, and anything else reserves nothing — as `List` and a
         * handle always did.
         */
        fun editorWidth(type: TypeRef): Float {
            dev.ziggle.vscript.editor.host.EditorHost.styles.styleFor(type)?.width?.let { return it }
            return when (dev.ziggle.vscript.editor.host.editorFor(type)) {
                dev.ziggle.vscript.editor.host.Editor.NONE -> 0f
                dev.ziggle.vscript.editor.host.Editor.NUMBER -> NUMBER_W
                dev.ziggle.vscript.editor.host.Editor.TOGGLE -> TOGGLE_W
                // Wide enough for a NAME rather than an id: a catalogue shows what was picked, and
                // "Dragon scimitar" in a 56px box is not a value anyone can read.
                dev.ziggle.vscript.editor.host.Editor.CATALOGUE -> STRING_W
                dev.ziggle.vscript.editor.host.Editor.TEXT -> STRING_W
                // Swatch plus its hex.
                dev.ziggle.vscript.editor.host.Editor.COLOUR -> STRING_W
                // Boxes side by side, so it needs about half again what one name does.
                dev.ziggle.vscript.editor.host.Editor.FIELDS -> TILE_W
            }
        }

        /**
         * The same question, for a caller that still holds a builtin.
         *
         * Delegates rather than keeping its own table. It kept one for a while and the two drifted within
         * the hour: the builtin path said a wildcard gets a text field and the `TypeRef` path said it gets
         * nothing, so a Literal node was sized for a field it drew and drew a field it was not sized for,
         * depending which overload the caller happened to reach.
         */
        fun editorWidth(type: dev.ziggle.vscript.model.PinType): Float = editorWidth(TypeRef(type))

        /**
         * Pin hit radius, in graph units.
         *
         * Deliberately much larger than the drawn [PIN_RADIUS], and **the same value for grabbing and for
         * dropping**. The reference implementation used three different hit areas for one pin — a
         * label-sized rect to start a drag, a 4px circle to land one, and a dead 12px box — so links were
         * comfortable to begin and nearly impossible to finish. One number, generous, used everywhere.
         */
        const val HIT_RADIUS = 11f

        /** Comment resize grip, bottom-right. Shared by the painter and the hit-tester so the drawn
         *  affordance is exactly the area that responds. */
        const val GRIP = 16f

        /** Default size for a comment container that has never been fitted to anything. */
        /** How wide the fold toggle's square is, at the right-hand end of a container's header. */
        const val FOLD_W = 22f

        /**
         * A function's signature, the way the folded box shows it: `(int, item) → (bool)`.
         *
         * Types rather than names, because folded is the view where you want the SHAPE at a glance — the
         * names are right there on the pins the moment you unfold it or look at a call site.
         */
        fun signature(fn: dev.ziggle.vscript.model.GraphFunction?): String {
            if (fn == null) return ""
            fun side(pins: List<dev.ziggle.vscript.model.FunctionPin>) =
                pins.joinToString(", ") { it.type.name.lowercase() }
            return "(${side(fn.params)}) \u2192 (${side(fn.results)})"
        }

        const val COMMENT_W = 320f
        const val COMMENT_H = 200f

        /**
         * Measure [node] and lay out its pins.
         *
         * Rows pair an input with an output by index, which is what keeps the two columns aligned when a
         * node has different numbers of each.
         */
        fun of(
            node: Node,
            catalog: NodeCatalog,
            connected: (pin: String) -> Boolean,
            variableType: (String) -> TypeRef? = { null },
            function: (String) -> dev.ziggle.vscript.model.GraphFunction? = { null },
            types: () -> List<dev.ziggle.vscript.model.StructType> = { emptyList() },
            pure: (Node, dev.ziggle.vscript.model.GraphFunction) -> Boolean = { _, _ -> false },
            /** The document's enums, so a Choice node draws its real members — see `EnumType`. */
            enums: () -> List<dev.ziggle.vscript.model.EnumType> = { emptyList() },
            /**
             * What the wire into a pin carries — so a loop's `Element` is drawn as the list's element type,
             * a map loop's `Key` / `Value` as the map's, and a generic call's pins as what was wired into
             * `self`. Null keeps the wildcard, as before. See [CanvasTyping].
             */
            feeding: (Int, String) -> TypeRef? = { _, _ -> null },
        ): NodeGeometry? {
            // Resolved, so a Call node is drawn with the pins its signature gives it rather than the bare
            // exec pair the catalog declares.
            val d = catalog[node.type]
                ?.let { dev.ziggle.vscript.model.resolveNode(node, it, function, types, pure, enums, feeding) }
                ?: return null

            if (d.kind.isContainer) {
                val w = if (node.w > 0f) node.w else COMMENT_W
                // Folded: the header bar and nothing else. The stored height is left alone so unfolding
                // restores the box the author sized rather than a default one.
                val h = when {
                    node.folded -> HEADER_H
                    node.h > 0f -> node.h
                    else -> COMMENT_H
                }
                val rect = Rect(node.x, node.y, w, h)
                // A function box carries its signature ON ITS EDGES, mirrored: the parameters are OUTPUTS
                // (the body reads them) and sit down the LEFT, the results are INPUTS and sit down the
                // RIGHT. Seen from inside the body that is still left-to-right, which is the only reading
                // that makes sense from in there. A comment has no pins at all.
                val ins: List<Pin>
                val outs: List<Pin>
                if (d.kind == NodeKind.FUNCTION && !node.folded) {
                    outs = d.outputs.mapIndexed { i, spec ->
                        Pin(spec, i, false, rect.x, boundaryY(rect, i), null, spec.type)
                    }
                    ins = d.inputs.mapIndexed { i, spec ->
                        Pin(spec, i, true, rect.right, boundaryY(rect, i), null, spec.type)
                    }
                } else {
                    ins = emptyList()
                    outs = emptyList()
                }
                return NodeGeometry(
                    node, d, rect, ins, outs,
                    bodyLines = if (node.folded) emptyList() else commentBody(node.body, w),
                    subtitle = if (d.kind == NodeKind.FUNCTION) signature(node.function?.let(function)) else "",
                )
            }

            // A knot is a POINT on a wire, not a box on it. Both pins sit at the same place — its centre —
            // so the wire arrives and leaves from one spot and reads as continuous. Drawn as a node with a
            // header and two labelled rows, a reroute would be bigger than the problem it solves.
            if (d.type == dev.ziggle.vscript.model.BuiltinNodes.REROUTE) {
                val rect = Rect(node.x, node.y, KNOT, KNOT)
                val cx = rect.x + KNOT / 2f
                val cy = rect.y + KNOT / 2f
                val carried = d.inputs.firstOrNull()?.type ?: TypeRef.WILDCARD
                return NodeGeometry(
                    node, d, rect,
                    listOf(Pin(d.inputs[0], 0, true, cx, cy, null, carried)),
                    listOf(Pin(d.outputs[0], 0, false, cx, cy, null, carried)),
                )
            }

            val ins = d.inputs
            val outs = d.outputs
            val rows = maxOf(ins.size, outs.size)

            // Per-row column widths, measured once and reused for both the node's width and the editor's
            // placement. Deriving the two separately is exactly what let a right-aligned editor sit on top
            // of an output label the width calculation had already accounted for.
            val leftW = FloatArray(rows)
            val rightW = FloatArray(rows)
            val editorW = FloatArray(rows)
            val outEditorW = FloatArray(rows)
            for (r in 0 until rows) {
                val i = ins.getOrNull(r)
                val o = outs.getOrNull(r)
                if (i != null) {
                    val itype = effectivePinType(node, i, variableType, feeding)
                    editorW[r] = when {
                        itype.isExec || connected(i.name) -> 0f
                        // The spec-aware measure when the type is the declared one (an ENUM sizes to its
                        // longest option); the plain one when it came from a variable, which has no options.
                        itype == i.type -> editorWidth(i)
                        else -> editorWidth(itype)
                    }
                    leftW[r] = PIN_LABEL_OFFSET + TextMeasure.width(i.name) +
                        (if (editorW[r] > 0f) EDITOR_GAP + editorW[r] else 0f)
                }
                if (o != null) {
                    // An editable OUTPUT — a Literal's value. Sized into the right column so the field sits
                    // immediately left of the pin label rather than floating in the middle of the node.
                    outEditorW[r] = if (o.editable && !o.type.isExec) editorWidth(o) else 0f
                    rightW[r] = PIN_LABEL_OFFSET + TextMeasure.width(o.name) +
                        (if (outEditorW[r] > 0f) EDITOR_GAP + outEditorW[r] else 0f)
                }
            }

            var widest = TextMeasure.width(title(node, d)) + PAD_X * 2 + TITLE_TRAIL
            for (r in 0 until rows) {
                // Each column already carries its own edge margin in PIN_LABEL_OFFSET; what a row needs on
                // top is the gap BETWEEN the columns, or a plain pad on the side that has none.
                val row = when {
                    leftW[r] > 0f && rightW[r] > 0f -> leftW[r] + COL_GAP + rightW[r]
                    leftW[r] > 0f -> leftW[r] + PAD_X
                    rightW[r] > 0f -> rightW[r] + PAD_X
                    else -> 0f
                }
                widest = maxOf(widest, row)
            }

            val width = maxOf(widest, MIN_W)
            val height = HEADER_H + PAD_Y * 2 + rows * ROW_H
            val rect = Rect(node.x, node.y, width, height)

            val inputAnchors = ins.mapIndexed { idx, spec ->
                val cy = rowCenterY(rect, idx)
                // An editor is laid out ONCE, here, and both the painter and the hit-tester read it — the
                // same rule as the node rect itself. A widget drawn at one place and clicked at another is
                // the classic failure of hand-rolled canvases.
                val ew = editorW.getOrElse(idx) { 0f }
                val editor = if (ew <= 0f) null else {
                    val labelEnd = rect.x + PIN_LABEL_OFFSET + TextMeasure.width(spec.name)
                    // Right-aligned, but only as far as the output column on THIS row allows.
                    val reserve = if (rightW[idx] > 0f) rightW[idx] + COL_GAP else PAD_X
                    val ex = maxOf(labelEnd + EDITOR_GAP, rect.right - reserve - ew)
                    Rect(ex, cy - EDITOR_H * 0.5f, ew, EDITOR_H)
                }
                Pin(spec, idx, true, rect.x, cy, editor, effectivePinType(node, spec, variableType, feeding))
            }
            val outputAnchors = outs.mapIndexed { idx, spec ->
                val cy = rowCenterY(rect, idx)
                val ew = outEditorW.getOrElse(idx) { 0f }
                val editor = if (ew <= 0f) null else {
                    val labelStart = rect.right - PIN_LABEL_OFFSET - TextMeasure.width(spec.name)
                    Rect(labelStart - EDITOR_GAP - ew, cy - EDITOR_H * 0.5f, ew, EDITOR_H)
                }
                Pin(spec, idx, false, rect.right, cy, editor, effectivePinType(node, spec, variableType, feeding))
            }
            return NodeGeometry(node, d, rect, inputAnchors, outputAnchors)
        }

        /**
         * Where a boundary pin sits down a function box's edge.
         *
         * Spaced from the TOP rather than spread over the height: a box is resized to fit what it holds, so
         * a pin whose position depended on that height would slide about every time you dragged the corner,
         * and the wire attached to it with it.
         */
        private fun boundaryY(rect: Rect, row: Int): Float =
            rect.y + HEADER_H + PAD_Y + row * ROW_H + ROW_H * 0.5f

        /** Pins sit ON the node's edge, not floating outside it — the wire meets the body it belongs to. */
        private fun rowCenterY(rect: Rect, row: Int): Float =
            rect.y + HEADER_H + PAD_Y + row * ROW_H + ROW_H * 0.5f

        fun title(node: Node, d: NodeDescriptor): String = when (node.type) {
            dev.ziggle.vscript.model.BuiltinNodes.VAR_GET -> "Get ${node.variable ?: "?"}"
            dev.ziggle.vscript.model.BuiltinNodes.VAR_SET -> "Set ${node.variable ?: "?"}"
            // Which function, on the node. A row of nodes all titled "Call" tells you nothing about the
            // graph you are reading, and the answer is a field away.
            dev.ziggle.vscript.model.BuiltinNodes.CALL -> "Call ${node.callee ?: "?"}"
            else -> d.title
        }
    }
}
