package dev.ziggle.vscript.editor.graph

import dev.ziggle.imgui.TextEdit
import dev.ziggle.imgui.TextFilter
import imgui.ImGui
import imgui.flag.ImDrawFlags
import imgui.flag.ImGuiButtonFlags
import imgui.flag.ImGuiKey
import imgui.flag.ImGuiMouseButton
import dev.ziggle.imgui.Theme
import dev.ziggle.vscript.compile.Issue
import dev.ziggle.vscript.compile.Severity
import dev.ziggle.vscript.layout.AutoLayout
import dev.ziggle.vscript.layout.BoxPacker
import dev.ziggle.vscript.runtime.EditorDoc
import dev.ziggle.vscript.editor.graph.EditorSettings
import dev.ziggle.vscript.editor.graph.OrthogonalRouter
import dev.ziggle.vscript.editor.graph.WireRouting
import dev.ziggle.vscript.editor.graph.WireTracks
import dev.ziggle.vscript.editor.graph.OutlineDrop
import dev.ziggle.vscript.editor.graph.PinStyle
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeDescriptor
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.model.canConnect
import dev.ziggle.vscript.runtime.History
import dev.ziggle.imgui.EditorKeyboard

/**
 * The node canvas, drawn and driven entirely by us.
 *
 * Replaces imgui-node-editor. The library got the editor working quickly and was the right call to start
 * with, but every visual decision was its own — node shape, wire routing, grid, animation — and that is
 * precisely the surface this editor needs to control.
 *
 * **Structure.** One `invisibleButton` sized to the panel is the sole input sink; [CanvasCamera] owns the
 * transform; [NodeGeometry] measures a node once per frame and both the painter and the hit-tester read
 * that same object, so what is drawn and what responds can never disagree; [CanvasRenderer] does every draw
 * call and touches no state.
 *
 * **Interaction is one explicit mode at a time** ([Drag]). A canvas that lets a marquee begin while a link
 * is in flight, or pans while a node is being dragged, produces bugs that only appear under fast input and
 * are miserable to reproduce — so a gesture claims the canvas until it ends.
 */
class OwnCanvas(private val catalog: NodeCatalog) {

    private val cam = CanvasCamera()

    /** Selected node ids. Kept here, not in the document: selection is view state, not content. */
    private val selection = LinkedHashSet<Int>()

    /**
     * A viewer's canvas: pan, zoom, select, marquee, fold and breakpoints work; nothing that changes the
     * document does — no node or comment drags, no wiring, no Delete, no palette, no inline editors, no
     * editing menu items. The host sets it for a document whose lease belongs to someone else.
     */
    var readOnly: Boolean = false

    private var lastOx = 0f
    private var lastOy = 0f
    private var lastW = 0f
    private var lastH = 0f

    /** Selected link ids. Separate set because links and nodes share no id space. */
    private val selectedLinks = LinkedHashSet<Int>()

    /**
     * Draw order, back to front. Insertion order IS the order; the last entry draws on top.
     *
     * View state, not document state — the same argument as [selection]. Reordering `doc.nodes` would work
     * visually but would dirty the document and push an undo entry every time you clicked something, which
     * is not an edit.
     *
     * Nodes absent from this list draw first, in document order, so an untouched graph looks exactly as it
     * did before anything was raised.
     */
    private val zOrder = LinkedHashSet<Int>()

    private var issuesByNode: Map<Int, List<Issue>> = emptyMap()

    var activeNodes: Set<Int> = emptySet()
    var activeLinks: Set<Int> = emptySet()

    /** Worst log level each node emitted last run — the header badge. Set by the panel each frame. */
    var logLevels: Map<Int, dev.ziggle.vscript.log.LogLevel> = emptyMap()

    /** Messages for the badge tooltip, and what a badge click reports. Supplied by the panel. */
    var logsForNode: (Int) -> List<String> = { emptyList() }

    /** Called when a node's log badge is clicked — the console filters itself to that node. */
    var onBadgeClicked: (Int) -> Unit = {}

    // ---- debugger ----------------------------------------------------------------------------------

    /** Nodes with a breakpoint, and whether each is armed. Supplied by the panel each frame. */
    var breakpoints: Map<Int, Boolean> = emptyMap()

    /** Toggling is the panel's business — this canvas only reports the gesture. */
    var onToggleBreakpoint: (Int) -> Unit = {}

    /** The node execution is stopped on, or -1. */
    var pausedNode: Int = -1

    /** Asked for when a Get/Set node wants a variable that does not exist yet. The panel owns the prompt. */
    var onNewVariable: (Int) -> Unit = {}

    /**
     * A node dragged out of the outline sidebar and released, in SCREEN coordinates.
     *
     * Consumed inside [render], because only there is the canvas origin known — the sidebar cannot convert
     * a drop point into graph space without it, and passing the origin outwards would mean two places
     * holding the same transform.
     */
    var pendingDrop: OutlineDrop? = null

    /**
     * Values observed on output pins while paused, keyed by `nodeId to pin`.
     *
     * Captured on pause only, never continuously: recording every pin on every activation is unbounded and
     * would dominate the runtime, and the answer you want while stopped is what is on the wires *now*.
     */
    var pinValues: Map<Pair<Int, String>, Any?> = emptyMap()

    /**
     * The last value each PURE node produced, by node id.
     *
     * A second map rather than more entries in [pinValues] because they are answers to different questions.
     * That one reads a register that still holds its value at the pause; a pure node has no such register —
     * it is re-expanded into scratch at every use site — so this is a recording made at the instant of
     * evaluation. It is the LAST value, not the value now, and there is no "now" for an expression.
     *
     * Without it every wire leaving a Text node, a literal, a comparison or a query drew no value at all,
     * which is most of the wires in a graph.
     */
    var pureValues: Map<Int, Any?> = emptyMap()

    /** Drives the marching arrowheads on flowing wires. */
    private var flowPhase = 0f

    private var seeded = false
    private var lastFrameNanos = System.nanoTime()

    /** Last viewport size seen by [render], so the toolbar's no-arg actions know how big the view is. */
    private var lastViewW = 800f
    private var lastViewH = 600f

    /** What the user is currently doing. Exactly one at a time — see the class note. */
    private sealed class Drag {
        object None : Drag()
        object Panning : Drag()
        class Nodes(val offsets: Map<Int, Pair<Float, Float>>) : Drag()
        class Linking(val nodeId: Int, val pin: String, val fromInput: Boolean, val ax: Float, val ay: Float) : Drag()
        class Marquee(val startX: Float, val startY: Float) : Drag()

        /** [dx]/[dy] are the corner's offset from the cursor at grab time, so the box does not snap to the
         *  pointer the instant you press. */
        class ResizeComment(val nodeId: Int, val dx: Float, val dy: Float) : Drag()
    }

    private var drag: Drag = Drag.None

    /** What the cursor has been resting on, and since when — the tooltip dwell. See [hoverTooltips]. */
    private var hoverKey: String? = null
    private var hoverSince: Long = 0L

    /** Set when a context menu should open this frame. */
    private var openNodeMenuFor = -1
    private var contextLink = -1

    /** The pin under the cursor this frame, if any — drawn with a halo so a grab target is obvious. */
    private var hoveredPin: Pair<Int, NodeGeometry.Pin>? = null

    /**
     * Widget state (hot/active/focused/drag accumulator).
     *
     * Created ONCE and kept: these only mean anything across frames. The draw list and camera it works
     * through are rebound each frame by `beginFrame`.
     */
    private val widgets = WidgetContext()

    /** The add-node palette. Hand-drawn, screen-space, above everything. */
    private val palette = CanvasPalette(catalog)

    /** Backdrop for an open text field. Not quite opaque, so you keep a sense of what you are annotating. */
    private val EDITOR_BG = Theme.withAlpha(PinStyle.CANVAS_BG, 0.94f)

    fun setIssues(issues: List<Issue>) {
        issuesByNode = issues.mapNotNull { i -> i.nodeId?.let { it to i } }.groupBy({ it.first }, { it.second })
    }

    /**
     * Drop view state that refers to node ids, leaving the camera exactly where it is.
     *
     * What undo needs. Ids may not survive the restore, so selection and draw order have to go — but the
     * viewport is not part of what was undone, and re-framing it means every Ctrl+Z also costs you your
     * place in the graph.
     */
    /** Replaces the selection (nodes only) — what a paste or Ctrl+A needs. */
    fun select(ids: Collection<Int>) {
        selection.clear()
        selectedLinks.clear()
        selection.addAll(ids)
        zOrder.addAll(ids)
    }

    /** Screen → graph coordinates as of the last frame; where to put pasted nodes. */
    fun toGraph(sx: Float, sy: Float): Pair<Float, Float> = cam.toGraphX(sx - lastOx) to cam.toGraphY(sy - lastOy)

    /** Whether a screen point was over the canvas in the last frame. */
    fun contains(sx: Float, sy: Float): Boolean = sx >= lastOx && sy >= lastOy && sx < lastOx + lastW && sy < lastOy + lastH

    fun clearViewState() {
        selection.clear()
        selectedLinks.clear()
        zOrder.clear()
        drag = Drag.None
    }

    /** [clearViewState] plus a re-frame on the next render — for opening a different document. */
    fun reset() {
        clearViewState()
        seeded = false
    }

    fun dispose() = reset()

    /**
     * Frame the selection, or the whole graph when nothing is selected.
     *
     * **Two stages, latched on position rather than a counter.** The first press centres the target at the
     * CURRENT zoom — answering "where is it?" at the scale you were already reading. Only once it is
     * centred does a second press zoom to fit, answering "show me all of it". Travelling and rescaling at
     * once is disorienting, and it is what the library canvas got right that a naive fit does not.
     *
     * The latch is geometric (is it already centred?) rather than a click counter, so it stays correct if
     * you pan away in between, change the selection, or press fit twice in different places.
     */
    fun fitToContent(doc: EditorDoc) {
        val geo = geometry(doc)
        val targets = if (selection.isEmpty()) geo else geo.filter { it.node.id in selection }
        val b = Rect.bounds(targets.map { it.rect }) ?: return
        recordView(doc, History.FRAME)
        if (cam.isCenteredOn(b, lastViewW, lastViewH)) cam.frame(b, lastViewW, lastViewH)
        else cam.centerOn(b, lastViewW, lastViewH)
    }

    /**
     * Push the current viewport onto the undo stack, if the author asked for that.
     *
     * Opt-in: with it on, panning around to look at something and then pressing Ctrl+Z unwinds the
     * navigation rather than the edit you meant. Useful when you navigate deliberately, wrong as a default.
     */
    private fun recordView(doc: EditorDoc, label: String) {
        if (EditorSettings.cameraUndo) doc.recordView(label, cam.view())
    }

    /** The viewport as the history should record it. */
    fun view(): History.ViewState? = if (EditorSettings.cameraUndo) cam.view() else null

    /**
     * Select [nodeId] and bring it into view — what a console row asks for when you click it.
     *
     * Centres rather than fits: you already know what you are looking for, and rescaling the whole graph to
     * frame one node loses the context you were reading it in.
     */
    fun reveal(doc: EditorDoc, nodeId: Int) {
        val g = geometry(doc).firstOrNull { it.node.id == nodeId } ?: return
        recordView(doc, History.FRAME)
        selection.clear()
        selectedLinks.clear()
        selection.add(nodeId)
        raise(listOf(nodeId))
        cam.centerOn(g.rect, lastViewW, lastViewH)
    }

    /**
     * Apply a restored history step.
     *
     * Selection and draw order are dropped only when the GRAPH moved: a camera-only step has taken nothing
     * away, so clearing what you had selected because you undid a pan would be gratuitous.
     */
    fun applyStep(step: EditorDoc.Step) {
        step.view?.let { cam.restore(it) }
        if (step.graphChanged) clearViewState()
    }

    /**
     * Tidy the graph, then re-wrap each comment around whatever its members became.
     *
     * Sizes come from our own measurement rather than a guess — the layout has to respect how tall a node
     * actually renders, or anything with more than a couple of pins overlaps its neighbour.
     */
    /**
     * Lay the graph out, innermost group first.
     *
     * **Depth first, inside out.** A container is only as big as what it holds, and where it goes is a
     * question for whoever holds IT — so the only order that works is to settle the deepest group, size the
     * box around it, and hand that box upward as a single block. Laying the whole document out at once and
     * fitting the boxes afterwards is what produced boxes sitting hundreds of pixels from their own bodies
     * and comments stretched across everything their scattered members had landed on.
     *
     * With a selection, only that is tidied and nothing is restacked — a local tidy must not rearrange the
     * document underneath you.
     */
    fun autoArrange(doc: EditorDoc) {
        // Clear out any knots a previous version left behind, and never make new ones. Wires are routed at
        // DRAW time now, which does the same job without a node in the document to explain — a bead that
        // does nothing is a thing an author has to learn about, and the routing needs no such licence.
        val collapsed = WireTracks.collapse(doc.nodes.toList(), doc.links.toList(), catalog)
        if (collapsed.first.size != doc.nodes.size) {
            doc.replaceReroutes(emptyList(), collapsed.second)
        }

        val geo = drawOrder(geometry(doc))
        val byId = geo.associateBy { it.node.id }
        val sizes = geo.associate { it.node.id to (it.rect.w to it.rect.h) }
        fun sizeOf(id: Int) = sizes[id] ?: (180f to 90f)

        // Real pin offsets, from geometry already computed for drawing. This is what lets the layout
        // straighten a wire pin-to-pin rather than merely aligning two node tops — two nodes with different
        // pin counts have to line up on the WIRE, and where a pin sits is a property of the node's contents.
        val pinOffsets = AutoLayout.PinOffsets { id, pin, input ->
            val g = byId[id] ?: return@PinOffsets null
            val anchors = if (input) g.inputAnchors else g.outputAnchors
            anchors.firstOrNull { it.spec.name == pin }?.let { it.y - g.rect.y }
        }

        val containers = doc.nodes.filter { catalog[it.type]?.kind?.isContainer == true }
        // Membership read BEFORE anything moves: it is partly derived from where nodes sit, so reading it
        // afterwards would find every box empty and orphan its contents.
        val memberIds = containers.associate { it.id to membersOf(doc, byId, it.id).toSet() }

        // The container each node sits in DIRECTLY — the innermost one that holds it.
        val parent = HashMap<Int, Int>()
        for (n in doc.nodes) {
            val holders = containers.filter { it.id != n.id && memberIds.getValue(it.id).contains(n.id) }
            holders.minByOrNull { memberIds.getValue(it.id).size }?.let { parent[n.id] = it.id }
        }

        val selectionOnly = selection.isNotEmpty()
        val graph = doc.toGraph()

        // Which links are the spine. Worked out once: it needs the RESOLVED pins, since a Call's exec pins
        // depend on whether its function is an expression.
        val expression = dev.ziggle.vscript.model.expressionCalls(catalog, doc.nodes, doc.links, doc::function)
        val execLinkIds = graph.links.filter { l ->
            val n = graph.node(l.fromNode) ?: return@filter false
            val d = catalog[n.type]?.let {
                dev.ziggle.vscript.model.resolveNode(n, it, graph::function, { graph.types }, expression)
            } ?: return@filter false
            d.output(l.fromPin)?.type?.isExec == true
        }.map { it.id }.toSet()

        doc.edit("auto arrange") {
            /** Lay out one group and return the block it occupies, having moved everything in it. */
            fun pack(container: Int?, originX: Float, originY: Float): Rect {
                val direct = doc.nodes.filter { parent[it.id] == container }
                val childBoxes = direct.filter { catalog[it.type]?.kind?.isContainer == true }
                val plain = direct.filter { catalog[it.type]?.kind?.isContainer != true }.map { it.id }.toSet()

                // The plain nodes of this group, by the real layered layout.
                var bounds: Rect? = null
                if (plain.isNotEmpty()) {
                    val placed = AutoLayout.arrange(
                        graph, catalog, ::sizeOf, originX, originY,
                        include = { it.id in plain }, pins = pinOffsets,
                    )
                    for ((id, p) in placed) {
                        val n = doc.node(id) ?: continue
                        n.x = p.first
                        n.y = p.second
                    }
                    bounds = Rect.bounds(placed.map { (id, p) ->
                        val sz = sizeOf(id)
                        Rect(p.first, p.second, sz.first, sz.second)
                    })
                }

                // Then the child containers. Each is packed first so its size is known, then placed —
                // and WHERE is a choice, not a queue.
                //
                // Stacking is the default and usually right: it reads top to bottom and keeps the order
                // things were written in. But a narrow container leaves a corridor of empty canvas beside
                // it, and dropping the next one into that corridor can move it hundreds of pixels closer
                // to everything it is wired to. So each container goes wherever the wires come out
                // shortest, with a new row at the bottom always among the options — and winning ties, so
                // nothing shuffles sideways for a gain that is not worth the reading order.

                fun move(box: dev.ziggle.vscript.model.Node, x: Float, y: Float) {
                    val dx = x - box.x
                    val dy = y - box.y
                    if (dx == 0f && dy == 0f) return
                    box.x += dx; box.y += dy
                    for (id in memberIds[box.id].orEmpty()) doc.node(id)?.let { it.x += dx; it.y += dy }
                }

                /**
                 * Total wire length from [box] at ([x],[y]) to everything outside it that is already placed.
                 *
                 * EXEC WIRES COUNT FOR MORE. They are the spine — the order things happen in, and what
                 * anybody reads first — so a long one costs more than a long data wire, and a placement
                 * that shortens the spine wins over one that shortens a value feed by the same amount.
                 */
                fun cost(box: dev.ziggle.vscript.model.Node, x: Float, y: Float): Float {
                    val inside = memberIds[box.id].orEmpty() + box.id
                    val cx = x + box.w / 2f
                    val cy = y + box.h / 2f
                    var total = 0f
                    for (l in graph.links) {
                        val a = l.fromNode in inside
                        val b = l.toNode in inside
                        if (a == b) continue // wholly inside, or nothing to do with this box
                        val other = doc.node(if (a) l.toNode else l.fromNode) ?: continue
                        if (other.id in inside) continue
                        val weight = if (l.id in execLinkIds) EXEC_WEIGHT else 1f
                        total += weight * (kotlin.math.abs(other.x - cx) + kotlin.math.abs(other.y - cy))
                    }
                    return total
                }

                // SIZED FIRST, PLACED SECOND.
                //
                // The row width has to be decided for the whole set, and it cannot be until every box has
                // been packed and is therefore a known size. Deriving it as we went — "as wide as the
                // widest thing so far" — meant a row could only take a second box if the two together
                // fitted inside the wider of them, which is never. Everything went in one column, however
                // much canvas was free either side, and a script came out five screens tall and one wide.
                val startY = (bounds?.bottom ?: originY) + if (bounds != null) SCOPE_GAP else 0f
                var provisional = startY
                for (box in childBoxes) {
                    val inner = pack(box.id, originX + FN_BODY_INSET, provisional + NodeGeometry.HEADER_H + COMMENT_PAD)
                    val pad = if (box.type == BuiltinNodes.FUNCTION) FN_BODY_INSET else COMMENT_PAD
                    box.x = inner.x - pad
                    box.y = inner.y - COMMENT_PAD - NodeGeometry.commentInset(box.body, inner.w + pad * 2)
                    box.w = inner.w + pad * 2
                    box.h = inner.bottom + COMMENT_PAD - box.y
                    provisional = box.y + box.h + SCOPE_GAP
                }

                // A width for the group as a whole, from the area it has to hold. Wider than tall, because
                // a graph is read left to right and screens are that shape — and never narrower than the
                // widest box, which has to fit whatever happens.
                val area = childBoxes.sumOf { it.w.toDouble() * it.h }
                val limit = maxOf(
                    childBoxes.maxOfOrNull { it.w } ?: 0f,
                    bounds?.w ?: 0f,
                    Math.sqrt(area * ROW_ASPECT).toFloat(),
                )
                val packer = BoxPacker(originX, startY, SCOPE_GAP, limit)
                for (box in childBoxes) {
                    val (px, py) = packer.place(box.w, box.h) { x, y -> cost(box, x, y) }
                    move(box, px, py)
                }
                bounds = Rect.bounds(
                    listOfNotNull(bounds) + childBoxes.mapNotNull { b ->
                        doc.node(b.id)?.let { Rect(it.x, it.y, it.w, it.h) }
                    },
                )

                return bounds ?: Rect(originX, originY, 0f, 0f)
            }

            if (selectionOnly) {
                // A local tidy: just the chosen nodes, left where they already are.
                val chosen = selection.filter { catalog[doc.node(it)?.type ?: ""]?.kind?.isContainer != true }.toSet()
                if (chosen.isEmpty()) return@edit
                val before = Rect.bounds(chosen.mapNotNull { byId[it]?.rect }) ?: return@edit
                val placed = AutoLayout.arrange(graph, catalog, ::sizeOf, include = { it.id in chosen })
                val after = Rect.bounds(placed.map { (id, p) ->
                    val sz = sizeOf(id); Rect(p.first, p.second, sz.first, sz.second)
                }) ?: return@edit
                val dx = before.x - after.x
                val dy = before.y - after.y
                for ((id, p) in placed) {
                    val n = doc.node(id) ?: continue
                    n.x = p.first + dx
                    n.y = p.second + dy
                }
                return@edit
            }

            // Whole graph: pack from the top-level group down, anchored where the graph already starts so
            // an arrange does not teleport it.
            val anchor = Rect.bounds(geo.map { it.rect })
            pack(null, anchor?.x ?: 0f, anchor?.y ?: 0f)
        }

        // The camera deliberately does NOT move. Arranging is an edit to the graph; framing it is a
        // separate request with its own button, and doing both on one press means you cannot tidy without
        // also losing your viewport.
    }

    /** Wrap the selection in a comment box, or drop a default one when nothing is selected. */
    fun commentSelection(doc: EditorDoc) {
        val geo = geometry(doc)
        val chosen = geo.filter { it.node.id in selection && !it.desc.kind.isContainer }
        val b = Rect.bounds(chosen.map { it.rect })
        val box = if (b == null) {
            val cx = cam.toGraphX(lastViewW * 0.5f)
            val cy = cam.toGraphY(lastViewH * 0.5f)
            doc.addNode(BuiltinNodes.COMMENT, cx, cy).also {
                it.w = NodeGeometry.COMMENT_W
                it.h = NodeGeometry.COMMENT_H
            }
        } else {
            // A fresh box has no body yet, so its inset is just the heading strip — but it is asked for
            // rather than assumed, so adding a note later re-fits through the same rule.
            val w = b.w + COMMENT_PAD * 2
            val inset = NodeGeometry.commentInset(null, w)
            doc.addNode(BuiltinNodes.COMMENT, b.x - COMMENT_PAD, b.y - COMMENT_PAD - inset).also {
                it.w = w
                it.h = b.h + COMMENT_PAD * 2 + inset
            }
        }
        box.comment = "Comment"
    }

    val selectedIds: List<Int> get() = selection.toList()

    /**
     * True while something on the canvas owns the keyboard.
     *
     * The canvas has its own text fields, which ImGui knows nothing about, so `io.wantTextInput` is false
     * while you are typing into a comment. Panel-level shortcuts have to ask this as well or they fire on
     * characters meant for the note.
     */
    val keyboardCaptured: Boolean
        get() = widgets.keyboardCaptured || palette.isOpen || dev.ziggle.imgui.EditorKeyboard.busy

    // ---- frame ------------------------------------------------------------------------------------

    fun render(doc: EditorDoc) {
        val originVec = ImGui.getCursorScreenPos()
        val avail = ImGui.getContentRegionAvail()
        val w = avail.x.coerceAtLeast(16f)
        val h = avail.y.coerceAtLeast(16f)
        val ox = originVec.x
        val oy = originVec.y
        lastOx = ox
        lastOy = oy
        lastW = w
        lastH = h
        // The console PUSHES the canvas rather than floating over it, so opening it shrinks this viewport.
        // Without compensating, the graph appears to lurch upward as the drawer arrives — the pixels moved,
        // not the content. Holding the world-space centre point makes the panel feel like it slid in beside
        // the graph instead of shoving it.
        if (seeded && h != lastViewH) cam.shiftViewport(0f, (h - lastViewH) * 0.5f)
        if (seeded && w != lastViewW) cam.shiftViewport((w - lastViewW) * 0.5f, 0f)
        lastViewW = w
        lastViewH = h

        // One invisible button captures every button over the whole canvas. Everything below is gated on
        // its hovered/active state, so the canvas never steals input from a popup drawn on top of it.
        ImGui.invisibleButton(
            "##vscript-canvas", w, h,
            ImGuiButtonFlags.MouseButtonLeft or ImGuiButtonFlags.MouseButtonRight or ImGuiButtonFlags.MouseButtonMiddle,
        )
        val hovered = ImGui.isItemHovered()

        val now = System.nanoTime()
        val dt = ((now - lastFrameNanos) / 1e9f).coerceIn(1e-4f, 0.25f)
        lastFrameNanos = now
        cam.update(dt)
        // Ticked here rather than inside routes(), which is not called every frame and must not be the
        // thing that decides how much time has passed.
        if (sinceRouted < Float.MAX_VALUE) sinceRouted += dt * 1000f
        flowPhase = (flowPhase + dt * FLOW_SPEED) % 1f

        // ONE ordered list drives both painting and hit-testing. Deriving them separately is how a canvas
        // ends up letting you click a node that is visibly underneath another.
        val geo = drawOrder(geometry(doc))
        val byId = geo.associateBy { it.node.id }

        if (!seeded) {
            cam.snapTo(Rect.bounds(geo.map { it.rect }), w, h)
            seeded = true
        }

        hoveredPin = if (!hovered || drag !is Drag.None) null else {
            val m = ImGui.getMousePos()
            val gx = cam.toGraphX(m.x - ox)
            val gy = cam.toGraphY(m.y - oy)
            geo.asReversed().firstNotNullOfOrNull { g -> g.pinAt(gx, gy)?.let { g.node.id to it } }
        }

        pendingDrop?.let { d ->
            applyDrop(doc, d, geo, ox, oy, w, h)
            pendingDrop = null
        }

        // ORDER MATTERS. Content is drawn FIRST because the inline widgets claim their input during the
        // same pass that draws them (immediate mode) — the canvas must know a click was taken by a value
        // field before deciding it was a click on the node behind it. Interactive overlays (the dragged
        // wire, the marquee) are then drawn AFTER input so they reflect this frame rather than the last.
        val dl = ImGui.getWindowDrawList()
        val ctx = widgets
        ctx.beginFrame(dl, ScreenCamera(cam, ox, oy), hovered)

        dl.pushClipRect(ox, oy, ox + w, oy + h, true)
        drawContent(doc, geo, byId, ctx, ox, oy, w, h)

        // The palette owns the keyboard while it is up, so the widget pump is skipped entirely rather than
        // just ignored: with nothing focused it CLEARS the typed-character buffer, which ran before the
        // palette could drain it and swallowed every keystroke meant for the search box.
        if (!palette.isOpen) {
            val committed = CanvasWidgets.pumpKeyboard(ctx)
            if (committed != null) commitEditor(doc, committed.first, committed.second)
        }

        // A widget that took the click, is being dragged, or owns the keyboard blocks canvas gestures —
        // otherwise typing into a value field would also delete the node it belongs to. An open palette
        // blocks them for the same reason: it sits over the canvas and owns both mouse and keyboard.
        val widgetBusy = ctx.consumedClick || ctx.active != null || keyboardCaptured
        if (hovered && !widgetBusy) handleInput(doc, geo, byId, ox, oy)
        else if (drag is Drag.Panning && !ImGui.isMouseDown(ImGuiMouseButton.Middle)) drag = Drag.None
        if (!keyboardCaptured) continueDrag(doc, geo, byId, ox, oy)
        else if (drag !is Drag.None) drag = Drag.None
        edgePan(ox, oy, w, h, dt)

        drawOverlays(dl, ox, oy)
        dl.popClipRect()

        // Outside the clip so the panel can overhang the canvas edge, and last so it is above everything.
        palette.render(dl, dt, w, h, ox, oy)?.let { chosen -> createFromPalette(doc, chosen) }
        // After canvas input, so a widget still owns its release frame — see WidgetContext.endFrame.
        ctx.endFrame()
        popups(doc)
    }

    /**
     * Explain what is under the cursor, once it has settled there.
     *
     * A pin says what it CARRIES and a header says what the node DOES — the two questions you cannot
     * answer from the picture alone, since a pin is a coloured dot and a title is at best a reminder.
     *
     * Two rules keep it from being noise. It waits [TOOLTIP_DWELL_MS] before appearing, so sweeping the
     * cursor across a dense graph says nothing; the dwell resets whenever the target changes, so moving
     * between two pins does not inherit the first one's timer. And it never appears while a button is
     * held: during a drag or a link the cursor is over things constantly and none of it is enquiry — you
     * are carrying something, and a panel popping up under the pointer is in the way of exactly the pin
     * you are aiming at.
     */
    private fun hoverTooltips(geo: List<NodeGeometry>, gx: Float, gy: Float) {
        // Any button down means a gesture is in progress, not a question.
        if (ImGui.isMouseDown(ImGuiMouseButton.Left) || ImGui.isMouseDown(ImGuiMouseButton.Right) ||
            ImGui.isMouseDown(ImGuiMouseButton.Middle)
        ) {
            hoverKey = null
            return
        }
        // Topmost first, so an overlapping node's pin wins over the one beneath it — the same order the
        // click hit-tests use, or the tooltip would describe something else's pin.
        var key: String? = null
        var body: String? = null
        for (g in geo.asReversed()) {
            val pin = g.pinAt(gx, gy)
            if (pin != null) {
                key = "p:${g.node.id}:${pin.input}:${pin.index}"
                val type = dev.ziggle.vscript.model.Types.label(pin.type)
                val describe = dev.ziggle.vscript.model.Types.describe(pin.type)
                body = buildString {
                    append(pin.spec.name).append("  —  ").append(type)
                    if (describe.isNotBlank()) append('\n').append(describe)
                }
                break
            }
            if (g.headerRect.contains(gx, gy)) {
                key = "h:${g.node.id}"
                val summary = g.desc.summary
                body = buildString {
                    append(g.desc.title)
                    if (summary.isNotBlank()) append('\n').append(summary)
                }
                break
            }
        }
        if (key == null || body == null) {
            hoverKey = null
            return
        }
        val now = System.nanoTime()
        if (key != hoverKey) {
            hoverKey = key
            hoverSince = now
            return
        }
        if ((now - hoverSince) / 1_000_000L < TOOLTIP_DWELL_MS) return
        ImGui.setTooltip(body.take(TOOLTIP_CHARS))
    }

    /**
     * Scroll the view when a drag reaches the edge of the canvas.
     *
     * Without it the reachable area is whatever happens to be on screen when you press the button: a node
     * cannot be carried somewhere not already visible, and a wire cannot be dropped on a pin just past the
     * edge — you have to let go, pan, and pick it up again, which for a link means losing it entirely.
     *
     * The speed ramps with how far into the margin the cursor is, so nudging the edge creeps and pushing
     * hard against it moves properly; a fixed speed is either too slow to be useful or too fast to aim
     * with. Scaled by [dt] so it travels at the same rate whatever the frame rate.
     *
     * Not for [Drag.Panning] — dragging the view to the edge of itself and having it run away is a
     * feedback loop, not a feature.
     */
    private fun edgePan(ox: Float, oy: Float, w: Float, h: Float, dt: Float) {
        when (drag) {
            is Drag.None, is Drag.Panning -> return
            else -> {}
        }
        val m = ImGui.getMousePos()
        val dx = edgePush(m.x, ox, ox + w)
        val dy = edgePush(m.y, oy, oy + h)
        if (dx == 0f && dy == 0f) return
        // Opposite sign: pushing the cursor RIGHT should bring content from the right into view, which
        // means moving the origin left.
        cam.pan(-dx * dt, -dy * dt)
    }

    /** How hard the cursor is pressing past [lo]/[hi], in pixels per second; 0 while it is clear of both. */
    private fun edgePush(v: Float, lo: Float, hi: Float): Float {
        val into = when {
            v < lo + EDGE_MARGIN -> v - (lo + EDGE_MARGIN)   // negative
            v > hi - EDGE_MARGIN -> v - (hi - EDGE_MARGIN)   // positive
            else -> return 0f
        }
        // Clamped so leaving the window entirely does not launch the view off at whatever speed the
        // cursor's distance happens to imply.
        val ramp = (into / EDGE_MARGIN).coerceIn(-1f, 1f)
        return ramp * EDGE_PAN_SPEED
    }

    /**
     * Place a node dragged in from the sidebar.
     *
     * Dropping onto a compatible pin wires it up on arrival — the same principle as dropping a link on
     * empty canvas and picking from the filtered palette: the editor already knows what you meant, so
     * making you draw the wire afterwards is a step for its benefit rather than yours.
     */
    private fun applyDrop(doc: EditorDoc, d: OutlineDrop, geo: List<NodeGeometry>, ox: Float, oy: Float, w: Float, h: Float) {
        val sx = d.screenX - ox
        val sy = d.screenY - oy
        if (sx < 0f || sy < 0f || sx > w || sy > h) return // released outside the canvas
        val gx = cam.toGraphX(sx)
        val gy = cam.toGraphY(sy)

        val target = geo.asReversed().firstNotNullOfOrNull { g -> g.pinAt(gx, gy)?.let { g to it } }
        val type = when {
            // Ctrl picks the direction, the way it picks Get or Set for a variable: a record is something
            // you either build or take apart, and both are one node away from the row you dragged.
            d.declaredType != null -> if (d.set) BuiltinNodes.STRUCT_SPLIT else BuiltinNodes.STRUCT_MAKE
            d.function != null -> BuiltinNodes.CALL
            d.nodeType != null -> d.nodeType
            // A drop on an INPUT wants a value, so it is a Get; on an output pin there is nothing to read
            // from, so the modifier decides as it does on empty canvas.
            target?.second?.input == true -> BuiltinNodes.VAR_GET
            d.set -> BuiltinNodes.VAR_SET
            else -> BuiltinNodes.VAR_GET
        }
        val bare = catalog[type] ?: return
        // Placed slightly up-left of the cursor so the node lands under the pointer rather than beside it.
        val created = doc.addNode(type, gx - 40f, gy - NodeGeometry.HEADER_H)
        if (d.variable != null) doc.setNodeVariable(created.id, d.variable)
        if (d.function != null) doc.setNodeCallee(created.id, d.function)
        if (d.declaredType != null) doc.setLiteral(created.id, BuiltinNodes.STRUCT_OF, d.declaredType)
        for ((pin, value) in d.literals) doc.setLiteral(created.id, pin, value)
        // RESOLVED, not the catalog's bare descriptor: a Make node's real pins are its record's fields, and
        // the auto-wire below picks one of them. Reading the catalog here would have found no field to
        // offer and quietly dropped the connection the drop was for.
        val desc = dev.ziggle.vscript.model.resolveNode(
            created, bare, { n -> doc.function(n) }, { doc.visibleTypes() },
            dev.ziggle.vscript.model.expressionCalls(catalog, doc.nodes, doc.links, doc::function),
            { doc.enums },
        )
        raise(listOf(created.id))
        selection.clear()
        selection.add(created.id)

        val (tg, pin) = target ?: return
        if (tg.node.id == created.id) return
        if (pin.input) {
            val out = desc.outputs.firstOrNull { canConnect(it.type, pin.spec.type) } ?: return
            doc.addLink(created.id, out.name, tg.node.id, pin.spec.name, pin.spec.type)
        } else {
            val inp = desc.inputs.firstOrNull { canConnect(pin.spec.type, it.type) } ?: return
            doc.addLink(tg.node.id, pin.spec.name, created.id, inp.name, inp.type)
        }
    }

    /** Raise [ids] to the front of the draw order, preserving their relative order. */
    private fun raise(ids: Collection<Int>) {
        for (id in ids) {
            zOrder.remove(id)
            zOrder.add(id)
        }
    }

    /**
     * [geo] sorted back-to-front. Stable, so anything never raised keeps its document order.
     *
     * **A box inside another box is always drawn after it**, whatever has been clicked. Raising is about
     * what you touched last and containment is not — a function box nested in a comment belongs on top of
     * it always, and ordering the two by click meant the comment painted its backdrop over the function
     * whenever the comment had been touched more recently. The function simply vanished until you clicked
     * its header, which put it back on top for as long as you did not click anything else.
     */
    private fun drawOrder(geo: List<NodeGeometry>): List<NodeGeometry> {
        val rank = HashMap<Int, Int>(zOrder.size)
        zOrder.forEachIndexed { i, id -> rank[id] = i + 1 }
        val boxes = geo.filter { it.desc.kind.isContainer }
        val depth = HashMap<Int, Int>(boxes.size)
        for (g in boxes) {
            depth[g.node.id] = boxes.count { it.node.id != g.node.id && holds(it, g) }
        }
        return geo.sortedWith(compareBy({ depth[it.node.id] ?: 0 }, { rank[it.node.id] ?: 0 }))
    }

    /**
     * The nodes to draw, laid out.
     *
     * A folded function's BODY is left out entirely — not drawn, not hit-tested, not wired. That is what
     * folding is for, and doing it here rather than at each use means nothing downstream has to remember:
     * a node that is not in this list cannot be clicked, dragged, or have a wire dropped on it.
     */
    /**
     * [node]'s descriptor with its signature resolved — see [dev.ziggle.vscript.model.resolveNode].
     *
     * Wire dragging reads a node's PINS, so every one of those reads has to come through here or a Call
     * node's signature pins are invisible to the gesture: the wire would refuse to land on a pin drawn
     * right there under the cursor.
     */
    private fun descOf(doc: EditorDoc, node: dev.ziggle.vscript.model.Node) = CanvasTyping.descOf(doc, catalog, node)

    /**
     * Put dropped nodes into whichever function box they landed in — or take them out of one.
     *
     * Membership is STORED (see [dev.ziggle.vscript.model.Node.function]) so a nudge cannot silently evict a
     * node from a function, but that means something has to write it, and dropping a node into the box is
     * the moment a person means it. Without this, a node could sit visibly inside a box and not be part of
     * it: it would keep running, and folding the box would leave it on screen — which is exactly how this
     * showed up.
     *
     * The INNERMOST box wins, so nesting a small function inside a big comment does the obvious thing.
     */
    private fun settleMembership(doc: EditorDoc, moved: Set<Int>) {
        val containers = doc.nodes.filter { catalog[it.type]?.kind?.isContainer == true }
        for (id in moved) {
            val n = doc.node(id) ?: continue
            val cx = n.x + 20f
            val cy = n.y + 12f
            // The innermost container the node's corner is in, so a function box nested in a comment does
            // the obvious thing. A container never adopts itself.
            val inside = containers
                .filter { it.id != id }
                .filter { b -> cx >= b.x && cy >= b.y && cx <= b.x + boxW(b) && cy <= b.y + boxH(b) }
                .sortedBy { boxW(it) * boxH(it) }
            doc.setNodeGroup(id, inside.firstOrNull()?.id)
            // A function box's own function is its identity, never something a drag reassigns.
            if (n.type == BuiltinNodes.FUNCTION) continue
            // Which BODY it is part of is the nearest enclosing function box — walking outward, so a node
            // dropped into a comment that itself sits inside a function is still part of that function.
            doc.setNodeFunction(id, inside.firstOrNull { it.type == BuiltinNodes.FUNCTION }?.function)
        }
    }

    private fun boxW(n: dev.ziggle.vscript.model.Node) = if (n.w > 0f) n.w else NodeGeometry.COMMENT_W
    private fun boxH(n: dev.ziggle.vscript.model.Node) = if (n.h > 0f) n.h else NodeGeometry.COMMENT_H

    /** Whether [pin] on node [nodeId] has a wire on it — asked from the pin's own side. */
    private fun pinConnected(doc: EditorDoc, nodeId: Int, pin: NodeGeometry.Pin): Boolean =
        if (pin.input) doc.links.any { it.toNode == nodeId && it.toPin == pin.spec.name }
        else doc.links.any { it.fromNode == nodeId && it.fromPin == pin.spec.name }

    private fun geometry(doc: EditorDoc): List<NodeGeometry> {
        val folded = doc.nodes
            .filter { it.type == BuiltinNodes.FUNCTION && it.folded }
            .mapNotNull { it.function }
            .toSet()
        return doc.nodes.mapNotNull { n ->
            if (n.type != BuiltinNodes.FUNCTION && n.function in folded) return@mapNotNull null
            NodeGeometry.of(
                n, catalog,
                connected = { pin -> doc.links.any { it.toNode == n.id && it.toPin == pin } },
                // So a Get/Set pin shows and behaves as the variable's declared type rather than as the
                // wildcard the descriptor has to use — see effectivePinType.
                variableType = { name -> doc.variable(name)?.type },
                function = { name -> doc.function(name) },
                // What this document may name — its own records and the host's — so a Make node is drawn with a pin per field.
                types = { doc.visibleTypes() },
                // A Call to a function that only computes is drawn as a pure node — no exec pins.
                pure = dev.ziggle.vscript.model.expressionCalls(catalog, doc.nodes, doc.links, doc::function),
                // And what it declares as choices, so a Choice node offers its real members.
                enums = { doc.enums },
                // And what each wire carries, so a loop's Element is drawn as the list's element type.
                feeding = { id, pin -> CanvasTyping.feedingIn(doc, catalog, id, pin) },
            )
        }
    }

    // ---- input ------------------------------------------------------------------------------------

    private fun handleInput(
        doc: EditorDoc,
        geo: List<NodeGeometry>,
        byId: Map<Int, NodeGeometry>,
        ox: Float,
        oy: Float,
    ) {
        val mouse = ImGui.getMousePos()
        val gx = cam.toGraphX(mouse.x - ox)
        val gy = cam.toGraphY(mouse.y - oy)

        val wheel = ImGui.getIO().mouseWheel
        // Zoom stays live DURING a drag. It used to be refused unless nothing was being dragged, which is
        // exactly backwards: the moment you most need to zoom out is while carrying a node to somewhere
        // off-screen. The drag follows the cursor in GRAPH space, so the thing in hand stays under the
        // pointer as the scale changes and needs no special handling.
        if (wheel != 0f) {
            recordView(doc, History.ZOOM)
            cam.zoomAt(mouse.x - ox, mouse.y - oy, wheel)
        }

        // Arrow keys pan. Only reachable with no field focused (the caller gates on keyboardCaptured), so
        // they still mean "move the caret" while typing. Shift is the coarse step, as it is everywhere.
        val io = ImGui.getIO()
        val step = if (io.keyShift) ARROW_PAN_FAST else ARROW_PAN
        if (ImGui.isKeyPressed(ImGuiKey.LeftArrow, true)) cam.pan(step, 0f)
        if (ImGui.isKeyPressed(ImGuiKey.RightArrow, true)) cam.pan(-step, 0f)
        if (ImGui.isKeyPressed(ImGuiKey.UpArrow, true)) cam.pan(0f, step)
        if (ImGui.isKeyPressed(ImGuiKey.DownArrow, true)) cam.pan(0f, -step)

        // The badge is checked before anything else on the node: it is a small, specific target sitting on
        // a large one, and losing it to the header drag would make it decorative.
        // The breakpoint gutter is outside the node, so it cannot be reached by the node hit-test below.
        val gutter = geo.asReversed().firstOrNull {
            !it.desc.kind.isContainer && CanvasRenderer.breakpointHit(it, gx, gy)
        }
        if (gutter != null && ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
            onToggleBreakpoint(gutter.node.id)
            return
        }
        if (gutter != null && gutter.node.id !in breakpoints) {
            ImGui.setTooltip("Click to set a breakpoint")
        }

        val badge = geo.asReversed().firstOrNull { logLevels.containsKey(it.node.id) && CanvasRenderer.badgeHit(it, gx, gy) }
        if (badge != null) {
            val lines = logsForNode(badge.node.id)
            if (lines.isNotEmpty()) ImGui.setTooltip(lines.joinToString("\n").take(TOOLTIP_CHARS))
            if (ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
                onBadgeClicked(badge.node.id)
                return
            }
        }

        hoverTooltips(geo, gx, gy)

        if (drag !is Drag.None) return

        // Middle-drag pans. Kept off right-drag because the chrome resizes docked panels with that, and two
        // owners for one gesture is how the previous canvas fought the panel for every click.
        //
        // Latched on PRESS, not on isMouseDragging: that waits for ImGui's drag-lock threshold, so the view
        // stayed frozen for the first several pixels of movement and the pan felt like it had to be
        // "pushed" into starting. A pan is unambiguous the moment the middle button goes down — there is no
        // competing gesture on that button to disambiguate from, which is exactly why a threshold is wrong
        // here even though it is right for the panel resize.
        if (ImGui.isMouseClicked(ImGuiMouseButton.Middle)) {
            drag = Drag.Panning
            ImGui.resetMouseDragDelta(ImGuiMouseButton.Middle)
            return
        }

        val hitPin = geo.asReversed().firstNotNullOfOrNull { g -> g.pinAt(gx, gy)?.let { g to it } }

        // A comment is grabbable ONLY by its header strip and its resize grip; its body is transparent to
        // input. Treating the whole box as a handle meant you could not start a marquee inside one, or
        // click empty space within it to deselect — the box swallowed the gesture and moved instead. Since
        // a comment usually wraps the very nodes you want to select, that made it actively obstructive.
        val hitNode = geo.asReversed().firstOrNull { !it.desc.kind.isContainer && it.rect.contains(gx, gy) }
            ?: geo.asReversed().firstOrNull { it.desc.kind.isContainer && commentChrome(it, gx, gy) }

        // The fold toggle, before anything else can claim the click — it sits INSIDE the header strip, so
        // whichever of the two is tested second never gets a look in. Folding wins because the header's
        // other job (dragging the box) has the whole rest of the strip to be grabbed by.
        if (ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
            val toggle = geo.asReversed().firstOrNull { it.foldRect?.contains(gx, gy) == true }
            if (toggle != null) {
                doc.setFolded(toggle.node.id, !toggle.node.folded)
                return
            }
        }

        if (ImGui.isMouseClicked(ImGuiMouseButton.Right)) {
            // Same precedence as a left click — node, then link, then background — so what you right-click
            // is always what you just left-clicked would have been.
            if (hitNode != null) {
                openNodeMenuFor = hitNode.node.id
                ImGui.openPopup(NODE_MENU)
                return
            }
            val link = linkAt(doc, byId, mouse.x - ox, mouse.y - oy, ox, oy)
            if (link != null) {
                contextLink = link
                // Right-clicking something unselected selects it first, so the menu always acts on what
                // is highlighted rather than on an invisible second notion of "the thing you meant".
                if (link !in selectedLinks) {
                    selection.clear()
                    selectedLinks.clear()
                    selectedLinks.add(link)
                }
                ImGui.openPopup(LINK_MENU)
                return
            }
            openNodeMenuFor = -1
            widgets.clearFocus()
            if (!readOnly) palette.open(mouse.x, mouse.y, gx, gy, null)
            return
        }

        // Double-click a comment to edit it: the title strip edits the heading, anywhere else the body.
        // Checked before the single-click handling so the box's transparent body still yields a marquee on
        // a plain click while remaining editable on a double.
        // A node sitting inside the box wins: a comment's rect covers everything it wraps, so without this
        // a double-click on a wrapped node would open the note's body editor instead.
        if (ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left) && hitPin == null &&
            (hitNode == null || hitNode.desc.kind.isContainer)
        ) {
            val c = geo.asReversed().firstOrNull { it.desc.kind.isContainer && it.rect.contains(gx, gy) }
            if (c != null) {
                val onHeading = c.headerRect.contains(gx, gy)
                // A function box's heading IS its name, so that is what opens for editing — seeding it
                // from the comment field would show you the wrong text and rename from the wrong start.
                val heading =
                    if (c.desc.kind == NodeKind.FUNCTION) c.node.function else c.node.comment
                widgets.beginEdit(
                    commentEditId(c.node.id, onHeading),
                    (if (onHeading) heading else c.node.body) ?: "",
                    TextFilter.ANY,
                    // A heading is a short label you are almost always replacing, so it opens selected. A
                    // body is prose you are almost always amending, so it opens with the caret at the end —
                    // selecting it would mean the first keystroke silently wipes the note.
                    selectAll = onHeading,
                )
                return
            }
        }

        if (!ImGui.isMouseClicked(ImGuiMouseButton.Left)) return

        // A pin beats the node under it — the pin is the smaller, more specific target.
        if (hitPin != null) {
            val (g, pin) = hitPin
            if (!readOnly) drag = Drag.Linking(g.node.id, pin.spec.name, pin.input, pin.x, pin.y)
            return
        }

        if (hitNode == null) {
            val link = linkAt(doc, byId, mouse.x - ox, mouse.y - oy, ox, oy)
            if (link != null) {
                if (ImGui.getIO().keyCtrl) {
                    if (!selectedLinks.remove(link)) selectedLinks.add(link)
                } else {
                    selection.clear()
                    selectedLinks.clear()
                    selectedLinks.add(link)
                }
                return
            }
            if (!ImGui.getIO().keyCtrl) { selection.clear(); selectedLinks.clear() }
            drag = Drag.Marquee(mouse.x, mouse.y)
            return
        }

        if (hitNode.desc.kind.isContainer && inGrip(hitNode, gx, gy)) {
            if (!readOnly) drag = Drag.ResizeComment(hitNode.node.id, hitNode.rect.right - gx, hitNode.rect.bottom - gy)
            return
        }

        val id = hitNode.node.id
        raise(listOf(id))
        if (!ImGui.getIO().keyCtrl && id !in selection) selectedLinks.clear()
        if (ImGui.getIO().keyCtrl) {
            if (!selection.remove(id)) selection.add(id)
        } else if (id !in selection) {
            selection.clear()
            selection.add(id)
        }

        // Dragging a node that is already selected moves the WHOLE selection, with each member's offset
        // preserved. Dragging an unselected one replaces the selection first. That is the behaviour every
        // editor converged on, and getting it wrong makes multi-select feel broken.
        val moving = if (id in selection) selection.toList() else listOf(id)
        val commentMembers = moving.filter { byId[it]?.desc?.kind?.isContainer == true }
            .flatMap { membersOf(doc, byId, it) }
        val all = (moving + commentMembers).distinct()
        // The whole moving group comes forward together, so a dragged cluster does not weave through the
        // nodes it passes over.
        raise(all)
        if (readOnly) return
        drag = Drag.Nodes(all.associateWith { nid ->
            val n = doc.node(nid)!!
            (n.x - gx) to (n.y - gy)
        })
    }

    private fun continueDrag(
        doc: EditorDoc,
        geo: List<NodeGeometry>,
        byId: Map<Int, NodeGeometry>,
        ox: Float,
        oy: Float,
    ) {
        val mouse = ImGui.getMousePos()
        val gx = cam.toGraphX(mouse.x - ox)
        val gy = cam.toGraphY(mouse.y - oy)

        when (val d = drag) {
            is Drag.None -> Unit

            is Drag.Panning -> {
                if (ImGui.isMouseDown(ImGuiMouseButton.Middle)) {
                    // lockThreshold 0: the default also suppresses the delta until the drag threshold is
                    // passed, so latching on press alone would still lose the first few pixels.
                    val delta = ImGui.getMouseDragDelta(ImGuiMouseButton.Middle, 0f)
                    // Recorded only on a frame that actually moves, so the first entry of a gesture holds
                    // the view from before the pan rather than one frame into it.
                    if (delta.x != 0f || delta.y != 0f) recordView(doc, History.PAN)
                    cam.pan(delta.x, delta.y)
                    ImGui.resetMouseDragDelta(ImGuiMouseButton.Middle)
                } else drag = Drag.None
            }

            is Drag.Nodes -> {
                if (ImGui.isMouseDown(ImGuiMouseButton.Left)) {
                    doc.recordMove()
                    for ((nid, off) in d.offsets) {
                        val n = doc.node(nid) ?: continue
                        n.x = gx + off.first
                        n.y = gy + off.second
                    }
                } else {
                    // Settle membership when the drag ENDS, not while it is in flight: re-homing a node on
                    // every frame of a drag across a box would flicker it in and out of the function, and
                    // each flicker is an undo step.
                    settleMembership(doc, d.offsets.keys)
                    drag = Drag.None
                }
            }

            is Drag.ResizeComment -> {
                if (ImGui.isMouseDown(ImGuiMouseButton.Left)) {
                    val n = doc.node(d.nodeId)
                    if (n != null) {
                        doc.recordMove()
                        n.w = (gx + d.dx - n.x).coerceAtLeast(120f)
                        n.h = (gy + d.dy - n.y).coerceAtLeast(80f)
                    }
                } else drag = Drag.None
            }

            is Drag.Marquee -> {
                if (!ImGui.isMouseDown(ImGuiMouseButton.Left)) {
                    val r = Rect.ofCorners(
                        cam.toGraphX(d.startX - ox), cam.toGraphY(d.startY - oy), gx, gy,
                    )
                    // Nodes are caught by INTERSECTION — brushing one counts, which is what a marquee is
                    // for. A comment is caught only when the marquee ENCLOSES it: its rect covers
                    // everything inside it, so intersection would grab the container every time you
                    // rubber-banded the nodes it wraps, which is the opposite of what that drag means.
                    for (g in geo) {
                        val caught = if (g.desc.kind.isContainer) {
                            r.contains(g.rect.x, g.rect.y) && r.contains(g.rect.right, g.rect.bottom)
                        } else {
                            g.rect.intersects(r)
                        }
                        if (caught) selection.add(g.node.id)
                    }
                    // Links too — a marquee that grabs nodes but leaves their wires behind is half a
                    // selection, and deleting it leaves danglers the validator then complains about.
                    val sr = Rect.ofCorners(d.startX, d.startY, mouse.x, mouse.y)
                    for (l in doc.links) {
                        val c = curveOf(doc, byId, l, ox, oy) ?: continue
                        if ((0..LINK_SAMPLES).any { i ->
                                val p = CanvasRenderer.curvePoint(c, i.toFloat() / LINK_SAMPLES)
                                sr.contains(p.first, p.second)
                            }
                        ) selectedLinks.add(l.id)
                    }
                    drag = Drag.None
                }
            }

            is Drag.Linking -> {
                if (!ImGui.isMouseDown(ImGuiMouseButton.Left)) {
                    val target = geo.asReversed().firstNotNullOfOrNull { g -> g.pinAt(gx, gy)?.let { g to it } }
                    if (target != null) {
                        connect(doc, d, target.first, target.second)
                    } else {
                        // Dropped on empty canvas: offer a filtered palette, the fastest way to extend a chain.
                        val m = ImGui.getMousePos()
                        widgets.clearFocus()
                        palette.open(m.x, m.y, gx, gy, pinTypeOf(doc, d))
                        pendingFromPin = d
                    }
                    drag = Drag.None
                }
            }
        }

        if (ImGui.isKeyPressed(ImGuiKey.F9)) selection.forEach { onToggleBreakpoint(it) }

        if (!readOnly && ImGui.isKeyPressed(ImGuiKey.Delete) && (selection.isNotEmpty() || selectedLinks.isNotEmpty())) {
            // ONE undo step for the whole selection — see EditorDoc.edit.
            val nodeIds = selection.toList()
            val linkIds = selectedLinks.toList()
            doc.edit("delete") {
                linkIds.forEach { doc.removeLink(it) }
                nodeIds.forEach { doc.removeNode(it) }
            }
            zOrder.removeAll(nodeIds.toSet())
            selection.clear()
            selectedLinks.clear()
        }
    }

    /** The pin a dropped link came from, so the palette can filter and auto-wire. */
    private var pendingFromPin: Drag.Linking? = null

    private fun connect(doc: EditorDoc, from: Drag.Linking, targetGeo: NodeGeometry, target: NodeGeometry.Pin) {
        if (targetGeo.node.id == from.nodeId) return
        if (from.fromInput == target.input) return // two inputs or two outputs
        val fromNode = doc.node(from.nodeId) ?: return
        val fromDesc = descOf(doc, fromNode) ?: return
        val fromSpec = (if (from.fromInput) fromDesc.inputs else fromDesc.outputs)
            .firstOrNull { it.name == from.pin } ?: return
        val fromType = dev.ziggle.vscript.model.effectivePinType(fromNode, fromSpec) { doc.variable(it)?.type }

        val outIsFrom = !from.fromInput
        val outType = if (outIsFrom) fromType else target.type
        val inType = if (outIsFrom) target.type else fromType
        if (!canConnect(outType, inType)) {
            insertToText(doc, from, targetGeo, target, outIsFrom, outType, inType)
            return
        }

        if (outIsFrom) doc.addLink(from.nodeId, from.pin, targetGeo.node.id, target.spec.name, inType)
        else doc.addLink(targetGeo.node.id, target.spec.name, from.nodeId, from.pin, inType)
    }

    /**
     * A wire that will not connect because the target wants TEXT: put a converter in the middle.
     *
     * The convenience of automatic coercion without any of its silence. The conversion becomes a node you
     * can see, retype through, or delete — rather than something the wire did on your behalf, which is what
     * makes a mis-wired Action pin ("5" where a menu entry was wanted) look like it worked.
     *
     * Only into a genuine string SINK, and only from a value that has one: exec wires and a wire dropped
     * backwards are still refused outright, because neither has a sensible conversion.
     */
    private fun insertToText(
        doc: EditorDoc,
        from: Drag.Linking,
        targetGeo: NodeGeometry,
        target: NodeGeometry.Pin,
        outIsFrom: Boolean,
        outType: TypeRef,
        inType: TypeRef,
    ) {
        if (inType.builtin != PinType.STRING || outType.isExec || outType.builtin == PinType.STRING) return

        // Which end is the source of the value, and which the string pin that wants it.
        val srcNode = if (outIsFrom) from.nodeId else targetGeo.node.id
        val srcPin = if (outIsFrom) from.pin else target.spec.name
        val dstNode = if (outIsFrom) targetGeo.node.id else from.nodeId
        val dstPin = if (outIsFrom) target.spec.name else from.pin

        val src = doc.node(srcNode) ?: return
        val dst = doc.node(dstNode) ?: return
        val conv = doc.edit("insert To Text") {
            // Between the two, biased toward the sink: the converter belongs with the thing that needed it.
            val n = doc.addNode(
                BuiltinNodes.TO_TEXT,
                (src.x + dst.x) * 0.5f,
                (src.y + dst.y) * 0.5f + NodeGeometry.HEADER_H,
            )
            doc.addLink(srcNode, srcPin, n.id, "Value", TypeRef.WILDCARD)
            doc.addLink(n.id, "Text", dstNode, dstPin, TypeRef(PinType.STRING))
            n
        }
        raise(listOf(conv.id))
        selection.clear()
        selection.add(conv.id)
    }

    /**
     * Where every wire goes this frame, in GRAPH space — computed once and read by everything.
     *
     * One source for the drawn path and the hit-tested one. They used to be computed separately, and the
     * moment the two disagreed the wires became unclickable while clicks on empty canvas selected them
     * instead. Sharing the map makes that class of bug unrepresentable rather than merely fixed.
     */
    private fun routes(doc: EditorDoc, byId: Map<Int, NodeGeometry>): Map<Int, FloatArray> {
        val mode = EditorSettings.wireRouting
        // Recomputed only when something moved. A* per wire is cheap but not free, and nothing about a
        // route changes between frames unless the geometry does.
        val stamp = routeStamp(doc, byId, mode)

        // A BACKGROUND PASS FINISHING is the one thing that changes the answer while nothing has moved,
        // so it is asked about before the stamp is. Merged rather than assigned: it only ever holds the
        // wires that were stale when it started, and the rest still have the paths they always had.
        takeRoutingResult(stamp)?.let { answer ->
            val merged = HashMap(lastRealRoutes)
            merged.putAll(answer)
            lastRealRoutes = merged
            pending.clear()
            lastRoutes = merged
            return merged
        }
        // Nothing moved. That covers the frames while a background pass is still working, and returning
        // the last answer is exactly right for them: it already holds the straight placeholders, and
        // rebuilding the lattice every frame to discover there is still nothing new would cost about
        // eighty milliseconds a frame — the freeze again, arrived at by a longer road.
        if (stamp == lastRouteStamp) return lastRoutes

        // Straight pin to pin costs nothing to work out, so it is never worth deferring or reusing.
        if (mode == WireRouting.DIRECT) {
            lastRouteStamp = stamp
            val direct = HashMap<Int, FloatArray>(doc.links.size)
            for (l in doc.links) {
                val (a, b) = pinsOf(doc, byId, l) ?: continue
                direct[l.id] = floatArrayOf(a.x, a.y, b.x, b.y)
            }
            lastRoutes = direct
            lastRealRoutes = direct
            return direct
        }

        // WHILE IT IS MOVING, route at a fixed rate rather than every frame.
        //
        // A full pass over a real script used to be around half a second, which per frame is two frames a
        // second. Most of that is gone — see the note in Congestion.lanes — and re-routing only the wires
        // a drag actually disturbs costs about twenty milliseconds. That is affordable, but not sixty
        // times a second, so it runs at [ROUTE_HZ] and the frames in between reuse the last real answer.
        //
        // Those frames RE-ANCHOR it: the path keeps the shape it was last routed into, with its two ends
        // moved onto the pins as they are now. The alternative — drawing a cheap placeholder shape — was
        // tried and is worse than the lag it replaced: the wires stop resembling what they will be, so
        // there is nothing to judge a drag by while making it.
        val moving = drag is Drag.Nodes || drag is Drag.ResizeComment
        if (moving && sinceRouted < 1000f / ROUTE_HZ) return reanchor(doc, byId)
        sinceRouted = 0f
        lastRouteStamp = stamp

        val out = HashMap<Int, FloatArray>(doc.links.size)

        val containers = byId.values.filter { it.desc.kind.isContainer }
        // A container's heading — and the note under it, when it has one — is TEXT, and a wire across text
        // is unreadable. It is not a node, so nothing made it solid: a box is excluded from its own
        // contents' obstacles (a wire has to be able to leave it), and that exclusion took the heading with
        // it. The band is an obstacle in its own right.
        val headings = containers.associate {
            it.node.id to OrthogonalRouter.Obstacle(
                it.rect.x, it.rect.y, it.rect.w, it.contentInset, OrthogonalRouter.CONTAINER_PAD,
            )
        }
        // A container stands wires off further than a node does — see OrthogonalRouter.CONTAINER_PAD.
        val obstacles = byId.values.map {
            OrthogonalRouter.Obstacle(
                it.rect.x, it.rect.y, it.rect.w, it.rect.h,
                if (it.desc.kind.isContainer) OrthogonalRouter.CONTAINER_PAD else 0f,
            )
        } + headings.values
        // ONE WIRE, ONE SEARCH, PIN TO PIN.
        //
        // There used to be a planner in front of this: crossing slots down each container's edge, a pair of
        // points straddling every wall, a standoff that grew with the room beside the box, and a shared
        // trunk column spliced into the middle of the long hauls. A wire was then routed as four to six
        // SEPARATE searches between those waypoints and the pieces glued together.
        //
        // All of it was re-deriving, by hand and badly, something the search already does: route() drops
        // any obstacle that CONTAINS either endpoint, so the container a wire starts or ends inside is
        // already transparent to it and every other container is already solid. The planner's whole job was
        // that one line, restated as five mechanisms — and each mechanism's mistakes showed up as shapes
        // nothing could argue with afterwards. The corridors were derived from merged container spans, so
        // in any graph whose boxes overlap horizontally (the normal case) only the two corridors OUTSIDE
        // everything survived, and every long wire was forced out past the edge of the graph and back. A
        // leg between two waypoints kept the full stub meant for a pin, so a leftward or vertical leg had
        // its start placed to the right of its end and hooked around itself to cope. The trunk was spliced
        // at legs.size / 2, which is the wall crossing rather than the middle whenever a plan had five legs
        // instead of six.
        //
        // None of that can happen to a single search. It sees the whole path, so its turn cost trades a
        // corner here against a corner there; there are no joints to scrub; and the shape it returns is the
        // shortest legal one rather than the cheapest way to honour someone else's waypoints.
        val jobs = doc.links.mapNotNull { l ->
            val fg = byId[l.fromNode] ?: return@mapNotNull null
            val tg = byId[l.toNode] ?: return@mapNotNull null
            val (a, b) = pinsOf(doc, byId, l) ?: return@mapNotNull null
            // A wire wholly inside one box stays inside it. Anything else is free of every box it has an
            // end in and blocked by every box it has not, which route() already arranges.
            val home = containers.filter { holds(it, fg) && holds(it, tg) }
                .minByOrNull { it.rect.w * it.rect.h }
            l.id to OrthogonalRouter.Wire(
                l.id, a.x, a.y, b.x, b.y,
                home?.let { floatArrayOf(it.rect.x, it.rect.y, it.rect.right, it.rect.bottom) },
            )
        }

        val jobsById = jobs.toMap()

        // ONE LATTICE FOR THE WHOLE PASS.
        //
        // The lines a wire may turn on used to be raised per wire, from the obstacles near that wire — so
        // two wires crossing the same region only landed on the same row when an obstacle edge happened to
        // define it for both, and mostly none did. Ten wires travelling together came out as ten different
        // shapes, each a shortest path, and no cost could pull them onto a common line because there was
        // no common line to pull them onto. Raised once, every wire chooses from the same rows.
        val lattice = OrthogonalRouter.lattice(
            obstacles,
            jobs.flatMap { (_, w) -> listOf(w.ax to w.ay, w.bx to w.by) },
        )

        // ONLY WHAT THE CHANGE DISTURBED.
        //
        // Dragging one node moves a handful of wires, and re-searching the other hundred and fifteen to
        // arrive at the paths they already had is the bulk of the cost. A wire is stale when the points it
        // has to pass through have changed — which covers both ends moving and a crossing slot shifting —
        // or when its old path ran through somewhere a node now is, or has just left.
        val changed = movedSince(byId)
        // A TUNING CHANGE MAKES EVERYTHING STALE. The incremental pass asks "did anything move near this
        // wire", and a knob turned while the graph sat still moves nothing — so every wire kept the path it
        // already had, and most of the numbers appeared to do nothing at all.
        val retune = dev.ziggle.vscript.editor.graph.Tuning.generation != lastTuning
        lastTuning = dev.ziggle.vscript.editor.graph.Tuning.generation
        val stale = HashSet<Int>()
        for ((id, w) in jobs) {
            val was = lastEnds[id]
            val old = lastRealRoutes[id]
            val ends = floatArrayOf(w.ax, w.ay, w.bx, w.by)
            if (retune || was == null || old == null || !was.contentEquals(ends) || touches(old, changed)) {
                stale += id
            }
        }
        // Everything else keeps the route it had, and is registered so the stale ones still route around it.
        for ((id, _) in jobs) {
            if (id in stale) continue
            val kept = lastRealRoutes[id] ?: continue
            out[id] = kept
        }
        // A HANDFUL NOW; A WHOLE GRAPH IN THE BACKGROUND.
        //
        // **Opening a script must not stop the client.** Dragging one node makes a handful of wires stale
        // and finishes well inside a frame, which is what this was written for — but IMPORTING one makes
        // every wire stale at once, and a real script is around 1200 of them. Measured, at real scale and
        // real spread: seconds for one pass, three times over with the rip-up, on the client thread, with
        // the game frozen behind it for a minute.
        //
        // The split is by SIZE rather than by cause, because size is the thing that actually hurts and a
        // paste or an auto-layout is as big as an import. Under [SYNC_WIRES] it runs here and now, so a
        // drag keeps its immediate feel and nothing about the common path changed. Over it, the work is
        // snapshotted and handed to a worker: `route` is a pure function of (wire, obstacles, lattice),
        // all three of which are immutable value holders, and its one piece of shared state
        // (`OrthogonalRouter.outcomes`) is already a ConcurrentHashMap. Meanwhile the wires draw straight,
        // the editor stays live, and the console, Run and the debugger all keep working.
        if (stale.size <= SYNC_WIRES) {
            pending.clear()
            for ((id, w) in jobs) {
                if (id !in stale) continue
                out[id] = OrthogonalRouter.route(w, obstacles, lattice)
            }
            // Rip-up and reroute. The first pass is first-come-first-served, so an early wire can take the
            // one clear line past a node and leave a later one crossing the node itself — when in the
            // other order both would have been fine. Re-routing each wire against everything but its own
            // previous path shows it the arrangement the others settled into, which the first pass could
            // not.
            //
            // Only the stale ones are re-routed. The settled wires are already part of the arrangement —
            // they are what the stale ones are being fitted around.
            if (stale.isNotEmpty()) {
                for (pass in 0 until REROUTE_PASSES) {
                    var moved = false
                    for ((id, w) in jobs) {
                        if (id !in stale) continue
                        val fresh = OrthogonalRouter.route(w, obstacles, lattice)
                        if (!fresh.contentEquals(out[id])) {
                            out[id] = fresh
                            moved = true
                        }
                    }
                    if (!moved) break
                }
            }
        } else {
            startRouting(stamp, jobs.filter { it.first in stale }, obstacles, lattice)
            pending.clear()
            pending += stale
        }
        lastRealRoutes = out
        lastEnds = jobs.associate { (id, w) -> id to floatArrayOf(w.ax, w.ay, w.bx, w.by) }
        lastRects = byId.values.associate {
            it.node.id to floatArrayOf(it.rect.x, it.rect.y, it.rect.right, it.rect.bottom)
        }

        // A wire still in the queue has no path yet, and the drawing and hit-testing both read this map —
        // so without something here it would be INVISIBLE rather than provisional, and a freshly opened
        // script would appear to have no wires at all for a second. A straight pin-to-pin line costs
        // nothing, says where the wire goes, and is replaced the moment its turn comes round.
        //
        // Kept out of [lastRealRoutes] deliberately: that map is what the staleness test consults, and a
        // placeholder recorded as a real answer is a wire that never gets routed properly.
        val shown = if (pending.isEmpty()) {
            out
        } else {
            HashMap(out).also { m ->
                for (id in pending) jobsById[id]?.let { w -> m[id] = floatArrayOf(w.ax, w.ay, w.bx, w.by) }
            }
        }
        lastRoutes = shown
        return shown
    }

    /** Wires the background pass has not delivered yet — they draw straight until it does. */
    private val pending = LinkedHashSet<Int>()

    /**
     * Above this many stale wires, routing goes to a worker instead of blocking the frame.
     *
     * Sized so that everything interactive stays synchronous. Dragging a node disturbs a few dozen wires
     * and re-routes them in a millisecond or two; nothing about that path should change, and pushing it
     * off-thread would add a frame of lag to every drag to solve a problem drags do not have.
     */
    private val SYNC_WIRES = 96

    /** The background routing pass, or null when there is none. */
    private var routing: RouteJob? = null

    /**
     * How far along the background pass is, 0..1 — null when nothing is routing.
     *
     * **Never reaches 1 while wires are still straight.** The count covers the first pass only; the rip-up
     * that follows it publishes nothing until it is done, so a bar that hit 100% and then sat there for
     * another second would be reporting a finish that has not happened. Clamped just short instead, and it
     * disappears when the answer actually lands.
     */
    val routingProgress: Float?
        get() = routing?.let { j ->
            if (j.total <= 0) null else minOf(j.done.get() / j.total.toFloat(), 0.99f)
        }

    /**
     * One graph's worth of routing, computed away from the client thread.
     *
     * **Everything it routes is a snapshot.** `Obstacle`, `Wire` and `Lattice` are immutable value
     * holders, so the worker cannot see a half-updated document — the alternative, locking the canvas
     * while it worked, would be the freeze again wearing a different hat.
     *
     * The one thing it reads that CAN change under it is the tuning (`OrthogonalRouter.MARGIN` and
     * friends, which are static vars the Tuning window writes). That is harmless rather than guarded:
     * turning a knob bumps `Tuning.generation`, which is part of the stamp, so a pass that read a
     * half-changed set is superseded and its answer dropped before anything draws it.
     *
     * [stamp] is what it was computed FOR. The geometry can move while it runs (the author drags a node
     * two seconds in), and a result that describes an arrangement that no longer exists has to be thrown
     * away rather than drawn — so the answer is only adopted if the stamp still matches.
     */
    private class RouteJob(
        val stamp: Int,
        val total: Int,
    ) {
        val done = java.util.concurrent.atomic.AtomicInteger()
        val result = java.util.concurrent.atomic.AtomicReference<Map<Int, FloatArray>?>()

        /** Set on the client thread, read on the worker — it stops mid-pass rather than finishing work nobody wants. */
        @Volatile
        var abandoned = false
    }

    /**
     * Start routing [work] in the background, unless the same pass is already running.
     *
     * Re-entrant by design: `routes` is called every frame and will ask for the same stamp on each of
     * them until the answer arrives.
     */
    private fun startRouting(
        stamp: Int,
        work: List<Pair<Int, OrthogonalRouter.Wire>>,
        obstacles: List<OrthogonalRouter.Obstacle>,
        lattice: OrthogonalRouter.Lattice,
    ) {
        val running = routing
        if (running != null && running.stamp == stamp && !running.abandoned) return
        // Superseded: the graph moved while it was working, so its answer is about a graph that is gone.
        running?.abandoned = true

        // ONE object, held by the field and closed over by the worker. Two would mean `abandoned` was set
        // on one and read from the other, and a superseded pass would run to completion anyway.
        val job = RouteJob(stamp, work.size)
        routing = job
        Thread({
            val out = HashMap<Int, FloatArray>(work.size)
            for ((id, w) in work) {
                if (job.abandoned) return@Thread
                out[id] = OrthogonalRouter.route(w, obstacles, lattice)
                job.done.incrementAndGet()
            }
            // The rip-up, for the reason the synchronous path gives — and affordable here, because nobody
            // is waiting on a frame for it. Deliberately outside the progress count: by now every wire is
            // drawn where it belongs and what is left is them settling against each other, so a bar that
            // went back to zero would be reporting a second job the author cannot see the point of.
            for (pass in 0 until REROUTE_PASSES) {
                var moved = false
                for ((id, w) in work) {
                    if (job.abandoned) return@Thread
                    val fresh = OrthogonalRouter.route(w, obstacles, lattice)
                    if (!fresh.contentEquals(out[id])) {
                        out[id] = fresh
                        moved = true
                    }
                }
                if (!moved) break
            }
            // Published LAST and in one go: the client thread reads this reference and nothing else, so
            // until it is set there is no half-built map for a frame to find.
            job.result.set(out)
        }, "vscript-route").apply { isDaemon = true }.start()
    }

    /**
     * The background pass's answer, if it has finished and is still about this graph.
     *
     * Read from the frame, on the client thread, so the map only changes hands at a moment when nothing
     * is drawing from it. A result for a stamp that has moved on is dropped rather than shown: it
     * describes an arrangement of nodes that no longer exists.
     */
    private fun takeRoutingResult(stamp: Int): Map<Int, FloatArray>? {
        val job = routing ?: return null
        if (job.abandoned) { routing = null; return null }
        val answer = job.result.get() ?: return null
        routing = null
        return if (job.stamp == stamp) answer else null
    }

    /**
     * How many times every wire is re-routed against the settled arrangement of the others.
     *
     * Two is enough on real graphs and the pass stops early when nothing moves. It is not free — this is
     * the whole A* pass again — but it only runs when the geometry changed.
     */
    private val REROUTE_PASSES = 2

    /** A wire's two pin anchors, or null when either end is missing. */
    private fun pinsOf(
        doc: EditorDoc,
        byId: Map<Int, NodeGeometry>,
        l: dev.ziggle.vscript.model.Link,
    ): Pair<NodeGeometry.Pin, NodeGeometry.Pin>? {
        val fg = byId[l.fromNode] ?: return null
        val tg = byId[l.toNode] ?: return null
        val fi = fg.desc.outputs.indexOfFirst { it.name == l.fromPin }
        val ti = tg.desc.inputs.indexOfFirst { it.name == l.toPin }
        if (fi < 0 || ti < 0) return null
        val a = fg.anchor(false, fi) ?: return null
        val b = tg.anchor(true, ti) ?: return null
        return a to b
    }

    /** Everything a route depends on, as one number — see [routes]. */
    private fun routeStamp(doc: EditorDoc, byId: Map<Int, NodeGeometry>, mode: WireRouting): Int {
        // The tuning generation is part of the question: a knob turned while nothing moved still changes
        // the answer, and without this the tweak would not show until something did.
        var h = mode.ordinal * 31 + doc.links.size + dev.ziggle.vscript.editor.graph.Tuning.generation * 7919
        for (g in byId.values) {
            h = h * 31 + g.node.id
            h = h * 31 + java.lang.Float.floatToIntBits(g.rect.x)
            h = h * 31 + java.lang.Float.floatToIntBits(g.rect.y)
            h = h * 31 + java.lang.Float.floatToIntBits(g.rect.w)
            h = h * 31 + java.lang.Float.floatToIntBits(g.rect.h)
        }
        for (l in doc.links) h = h * 31 + l.id + l.toNode * 7 + l.fromNode * 13
        return h
    }

    private var lastRouteStamp: Int? = null

    /** What the last real routing saw, so the next one can tell what actually moved. */
    private var lastRects: Map<Int, FloatArray> = emptyMap()

    /** The tuning the last real routing used — see the note in [routes]. */
    private var lastTuning: Int = -1
    private var lastEnds: Map<Int, FloatArray> = emptyMap()

    /**
     * The rectangles a node has occupied since the last routing — where it is now, and where it was.
     *
     * Both, because a wire is disturbed as much by a node arriving on top of it as by one getting out of
     * its way: the first has to move, and the second is free to move back.
     */
    private fun movedSince(byId: Map<Int, NodeGeometry>): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        for (g in byId.values) {
            val now = floatArrayOf(g.rect.x, g.rect.y, g.rect.right, g.rect.bottom)
            val was = lastRects[g.node.id]
            if (was == null) { out += now; continue }
            if (!was.contentEquals(now)) { out += now; out += was }
        }
        // A node that has GONE leaves its rectangle behind, for the same reason.
        for ((id, was) in lastRects) if (id !in byId) out += was
        return out
    }

    /** Does [path] run through any of [rects], with the clearance a route keeps? */
    private fun touches(path: FloatArray, rects: List<FloatArray>): Boolean {
        if (rects.isEmpty()) return false
        val m = OrthogonalRouter.MARGIN
        for (i in 0 until path.size / 2 - 1) {
            val lo = minOf(path[i * 2], path[i * 2 + 2]) - m
            val hi = maxOf(path[i * 2], path[i * 2 + 2]) + m
            val lo2 = minOf(path[i * 2 + 1], path[i * 2 + 3]) - m
            val hi2 = maxOf(path[i * 2 + 1], path[i * 2 + 3]) + m
            for (r in rects) {
                if (hi > r[0] && lo < r[2] && hi2 > r[1] && lo2 < r[3]) return true
            }
        }
        return false
    }

    /** Milliseconds since the last real routing — see [routes]. */
    private var sinceRouted: Float = Float.MAX_VALUE

    /**
     * How often the wires are re-routed while something is being dragged.
     *
     * Fast enough that the shape keeps up with the node, slow enough to leave the frame budget alone. The
     * frames in between are not stale-looking: they re-anchor the last real answer onto the live pins.
     */
    private var ROUTE_HZ = 12f

    /**
     * The last real routes with their ends moved onto the pins as they are now.
     *
     * What a wire looks like between routings while its node is being dragged. The middle is a frame or
     * two out of date and the ends are exact, which is the right way round: the shape is what you are
     * judging, and it is only wrong by however far the node has moved since.
     *
     * The neighbouring point follows on whichever axis kept the first segment straight, so the wire stays
     * made of right angles the whole way through the drag.
     */
    private fun reanchor(doc: EditorDoc, byId: Map<Int, NodeGeometry>): Map<Int, FloatArray> {
        val out = HashMap<Int, FloatArray>(doc.links.size)
        for (l in doc.links) {
            val (a, b) = pinsOf(doc, byId, l) ?: continue
            val was = lastRealRoutes[l.id]
            if (was == null || was.size < 4) {
                out[l.id] = floatArrayOf(a.x, a.y, b.x, b.y)
                continue
            }
            val p = was.copyOf()
            val n = p.size
            if (Math.abs(p[1] - p[3]) < 0.5f) p[3] = a.y else p[2] = a.x
            p[0] = a.x; p[1] = a.y
            if (Math.abs(p[n - 1] - p[n - 3]) < 0.5f) p[n - 3] = b.y else p[n - 4] = b.x
            p[n - 2] = b.x; p[n - 1] = b.y
            out[l.id] = p
        }
        lastRoutes = out
        // The real routes are a frame or two stale now, so the next unthrottled frame must redo them.
        lastRouteStamp = null
        return out
    }

    private fun holds(c: NodeGeometry, g: NodeGeometry): Boolean {
        if (c.node.id == g.node.id) return true
        val cx = g.rect.x + g.rect.w / 2f
        val cy = g.rect.y + g.rect.h / 2f
        return cx >= c.rect.x && cx <= c.rect.right && cy >= c.rect.y && cy <= c.rect.bottom
    }

    /** Does any part of [path] (GRAPH space) fall inside [r]? */
    private fun pathEnters(path: FloatArray, r: Rect): Boolean {
        for (i in 0 until path.size / 2 - 1) {
            val x1 = path[i * 2]; val y1 = path[i * 2 + 1]
            val x2 = path[i * 2 + 2]; val y2 = path[i * 2 + 3]
            if (maxOf(x1, x2) > r.x && minOf(x1, x2) < r.right &&
                maxOf(y1, y2) > r.y && minOf(y1, y2) < r.bottom
            ) return true
        }
        return false
    }

    /** The screen-space path of [l], or null when either end is missing. */
    private fun curveOf(
        doc: EditorDoc,
        byId: Map<Int, NodeGeometry>,
        l: dev.ziggle.vscript.model.Link,
        ox: Float,
        oy: Float,
        routes: Map<Int, FloatArray>? = null,
    ): FloatArray? {
        val wp = (routes ?: routes(doc, byId))[l.id] ?: return null
        return CanvasRenderer.routedPath(ScreenCamera(cam, ox, oy), wp)
    }

    /** The link nearest the cursor within [LINK_PICK_PX], or null. */
    private fun linkAt(
        doc: EditorDoc,
        byId: Map<Int, NodeGeometry>,
        localX: Float,
        localY: Float,
        ox: Float,
        oy: Float,
    ): Int? {
        var best: Int? = null
        var bestD = LINK_PICK_PX
        for (l in doc.links) {
            val c = curveOf(doc, byId, l, ox, oy) ?: continue
            val d = CanvasRenderer.distanceToCurve(localX + ox, localY + oy, c)
            if (d < bestD) { bestD = d; best = l.id }
        }
        return best
    }

    /** The parts of a comment box that respond to input: its title strip and its resize grip. */
    private fun commentChrome(g: NodeGeometry, gx: Float, gy: Float): Boolean =
        g.headerRect.contains(gx, gy) || inGrip(g, gx, gy)

    /**
     * The resize grip: the little triangle at the bottom-right, and ONLY that.
     *
     * Bounded on all four sides. Without the upper bounds this was the whole quadrant below and right of
     * the corner, so a click anywhere down-right of a comment — empty canvas included — started a resize,
     * and the box immediately snapped to wherever the pointer was.
     */
    private fun inGrip(g: NodeGeometry, gx: Float, gy: Float): Boolean =
        gx >= g.rect.right - NodeGeometry.GRIP && gx <= g.rect.right &&
            gy >= g.rect.bottom - NodeGeometry.GRIP && gy <= g.rect.bottom

    /** Non-comment nodes whose centre lies inside comment [commentId] — its members for dragging. */
    /**
     * What moves when a container moves.
     *
     * A COMMENT frames whatever is currently sitting in it, so its members are worked out from the
     * geometry. A FUNCTION carries its body by NAME instead — membership is recorded, and asking the
     * geometry would only find what is being DRAWN. A folded box therefore dragged an empty rectangle and
     * left its body where it was, which you only discovered on unfolding it somewhere else.
     */
    private fun membersOf(
        doc: EditorDoc,
        byId: Map<Int, NodeGeometry>,
        containerId: Int,
        seen: MutableSet<Int> = HashSet(),
    ): List<Int> {
        if (!seen.add(containerId)) return emptyList() // a box cannot contain itself, however it was dragged
        val node = doc.node(containerId) ?: return emptyList()
        if (node.type == BuiltinNodes.FUNCTION) {
            val name = node.function ?: return emptyList()
            return doc.nodes.filter { it.id != containerId && it.function == name }.map { it.id }
        }
        val box = byId[containerId]?.rect ?: return emptyList()
        // A comment frames whatever sits in it — INCLUDING other containers, so a group of functions can be
        // gathered under one heading. Nested members come too: dragging a comment that holds a function box
        // has to bring the function's body, which the box knows about and the comment does not.
        //
        // Membership is what was RECORDED, not what the rectangle currently covers. Asking the rectangle
        // meant dragging one comment across another's contents took them with it, and dragging one over a
        // function's body pulled those nodes out of the function. A node stays with what it was dropped in.
        //
        // The spatial arm is for nodes that predate the field: they are adopted on the first drag (see
        // settleMembership) and stop needing it.
        val direct = byId.values
            .filter { it.node.id != containerId }
            .filter { g ->
                val owner = g.node.group
                if (owner != null) owner == containerId
                else box.contains(g.rect.centerX, g.rect.centerY)
            }
            .map { it.node.id }
        return direct + direct.flatMap { id ->
            if (byId[id]?.desc?.kind?.isContainer == true) membersOf(doc, byId, id, seen) else emptyList()
        }
    }

    // ---- drawing ----------------------------------------------------------------------------------

    private fun drawContent(
        doc: EditorDoc,
        geo: List<NodeGeometry>,
        byId: Map<Int, NodeGeometry>,
        ctx: WidgetContext,
        ox: Float,
        oy: Float,
        w: Float,
        h: Float,
    ) {
        val dl = ctx.dl
        // The camera is expressed relative to the canvas origin, so shift it into window space once here
        // rather than adding ox/oy at every draw site — one place to get wrong instead of fifty.
        val screen = ctx.cam

        CanvasRenderer.grid(dl, screen, ox, oy, w, h)

        val wireRoutes = routes(doc, byId)
        lastRoutes = wireRoutes
        val boxes = geo.filter { it.desc.kind.isContainer }

        /**
         * Does this wire merely PASS OVER a box, with no business inside it?
         *
         * The distinction is what makes "wires under containers" work at all. A comment is a backdrop and
         * should hide a wire crossing behind it from somewhere else — but a wire with an END in that box is
         * not crossing it, it is arriving, and hiding it makes a node's input look unconnected.
         *
         * So the test is EITHER endpoint, not both. Both was the obvious rule and the wrong one: it kept a
         * container's internal wiring visible (right) and made every wire arriving from outside vanish
         * (badly wrong — those are the connections you most need to see).
         */
        fun passesOver(l: dev.ziggle.vscript.model.Link, path: FloatArray): Boolean {
            val from = byId[l.fromNode] ?: return false
            val to = byId[l.toNode] ?: return false
            var enters = false
            for (c in boxes) {
                // Belonging to ANY box wins outright. Wires are drawn in two passes — all beneath the
                // containers or all above them — so a wire that belongs to one box and merely crosses
                // another must go above, or it disappears inside the box it belongs to as well. That was
                // the "wire missing from the container its own node is in" case: it was not missing from
                // that box, it was underneath every box because of an unrelated one it happened to cross.
                if (holds(c, from) || holds(c, to)) return false
                if (pathEnters(path, c.rect)) enters = true
            }
            return enters
        }

        val paths = HashMap<Int, FloatArray>(doc.links.size)
        val beneath = ArrayList<dev.ziggle.vscript.model.Link>()
        val above = ArrayList<dev.ziggle.vscript.model.Link>()
        for (l in doc.links) {
            val wp = wireRoutes[l.id] ?: continue
            val p = CanvasRenderer.routedPath(screen, wp)
            paths[l.id] = p
            if (passesOver(l, wp)) beneath += l else above += l
        }

        fun drawWires(links: List<dev.ziggle.vscript.model.Link>) {
            for (l in links) {
                val fg = byId[l.fromNode] ?: continue
                val fi = fg.desc.outputs.indexOfFirst { it.name == l.fromPin }
                if (fi < 0) continue
                val a = fg.anchor(false, fi) ?: continue
                CanvasRenderer.link(
                    dl, screen, paths[l.id] ?: continue, a.type,
                    highlighted = l.id in selectedLinks,
                    flowPhase = if (l.id in activeLinks) flowPhase else null,
                )
            }
        }

        drawWires(beneath)

        // Containers OVER the wires and under the nodes.
        //
        // A wire crossing behind a comment used to be drawn on top of it, which made the note it was
        // covering hard to read; drawn under a translucent wash instead, it showed through as a coloured
        // smear that read as part of the note. Neither is what a comment is for — it is a backdrop, and a
        // backdrop should hide what is behind it. So the wash is opaque (see [PinStyle.COMMENT_FILL], which
        // is the same colour it always was over the canvas) and the wires go underneath.
        for (g in geo) if (g.desc.kind.isContainer) {
            // The same wired-or-not test the ordinary nodes get. It used to be a flat `false`, which was
            // harmless while containers had no pins and wrong the moment a function box grew some: every
            // boundary pin drew hollow however many wires were on it.
            CanvasRenderer.node(dl, screen, g, visual(g)) { pin -> pinConnected(doc, g.node.id, pin) }
            drawCommentEditor(g, ctx)
        }

        drawWires(above)

        for (g in geo) if (!g.desc.kind.isContainer) {
            CanvasRenderer.node(dl, screen, g, visual(g)) { pin -> pinConnected(doc, g.node.id, pin) }
            drawEditors(doc, g, ctx)
        }

        // AFTER the nodes, not before them. A pill drawn with the wires was painted over by every node it
        // happened to sit behind — and a value you cannot read is worse than no value, because you have no
        // way of telling it apart from a wire that carried nothing.
        if (pinValues.isNotEmpty() || pureValues.isNotEmpty()) {
            for (l in doc.links) {
                // A stable register first, then the recorded pure result — an impure node has the former and
                // never the latter, so the order only decides which wins for a node that somehow had both.
                val v = pinValues[l.fromNode to l.fromPin] ?: pureValues[l.fromNode] ?: continue
                val fg = byId[l.fromNode] ?: continue
                val fi = fg.desc.outputs.indexOfFirst { it.name == l.fromPin }
                if (fi < 0) continue
                val a = fg.anchor(false, fi) ?: continue
                if (a.type.isExec) continue
                // The ROUTED path, the same one the wire was drawn along. Recomputing the direct path here
                // put the pill on the line the wire would have taken if nothing had been in its way — which
                // is nowhere near the wire as soon as the router bends one.
                val wp = wireRoutes[l.id] ?: continue
                val (px, py) = CanvasRenderer.curvePoint(CanvasRenderer.routedPath(screen, wp), 0.5f)
                CanvasRenderer.valuePill(dl, screen, px, py, dev.ziggle.vscript.runtime.Variable.render(v), a.type)
            }
        }
        pendingDoc = doc
        pendingGeo = geo
        pendingOx = ox
        pendingOy = oy
    }

    // Captured during the content pass so the overlay pass can reuse them without re-deriving.
    private var pendingDoc: EditorDoc? = null
    private var pendingGeo: List<NodeGeometry> = emptyList()

    /**
     * Every node's rectangle as of the last frame drawn.
     *
     * A node's size is MEASURED — from its title, its pin names and its inline editors — so it exists only
     * once the canvas has laid it out with a live font. Anything placing nodes from outside the client is
     * otherwise guessing widths, and guessing widths is how a laid-out graph ends up overlapping itself.
     *
     * Empty until the editor has rendered at least once, which is honest: there is nothing to report.
     */
    fun measuredRects(): Map<Int, Rect> = pendingGeo.associate { it.node.id to it.rect }

    /**
     * How deep each container's heading band goes, for containers only.
     *
     * The band is an obstacle in its own right — text a wire must not cross — and unlike everything else the
     * router sees it cannot be worked out from the rect, because its depth comes from the heading and body
     * text measured in a live font. Without it, a route reproduced outside the client is being solved
     * against a different set of obstacles than the one that produced the shape being explained.
     */
    fun headingInsets(): Map<Int, Float> = pendingGeo
        .filter { it.desc.kind.isContainer }
        .associate { it.node.id to it.contentInset }

    /**
     * The waypoints every wire was last routed along, in graph space.
     *
     * Exposed for the debug socket. Wire routing has enough interacting rules — boundaries, fans, hints,
     * separation — that reasoning about which one applied to a given wire is guesswork, and guessing wrong
     * looks exactly like the feature not working. Reading the actual path settles it in one command.
     */
    fun routedWires(): Map<Int, FloatArray> = lastRoutes
    /** Last frame's routes, for [routedWires]. */
    private var lastRoutes: Map<Int, FloatArray> = emptyMap()

    /**
     * The last routes the real search produced, as against what was last DRAWN.
     *
     * They differ while a drag is in flight, and the incremental pass has to read this one: it keeps the
     * path of every wire the change did not disturb, and a sketch is not a path worth keeping. Reading the
     * drawn ones instead would have quietly frozen the cheap two-bend shape onto every untouched wire the
     * first time anything moved, and left it there.
     */
    private var lastRealRoutes: Map<Int, FloatArray> = emptyMap()

    private var pendingOx = 0f
    private var pendingOy = 0f

    /** The dragged wire and the marquee, drawn last so they sit above everything and show THIS frame. */
    private fun drawOverlays(dl: imgui.ImDrawList, ox: Float, oy: Float) {
        val screen = ScreenCamera(cam, ox, oy)
        val doc = pendingDoc
        (drag as? Drag.Linking)?.let { d ->
            val mouse = ImGui.getMousePos()
            CanvasRenderer.pendingLink(
                dl, screen, d.ax, d.ay, mouse.x, mouse.y, d.fromInput,
                doc?.let { validityUnderCursor(it, pendingGeo, ox, oy, d) },
            )
        }
        (drag as? Drag.Marquee)?.let { d ->
            val mouse = ImGui.getMousePos()
            CanvasRenderer.marquee(dl, d.startX, d.startY, mouse.x, mouse.y)
        }
    }

    /**
     * Inline value editors for a node's unconnected data inputs.
     *
     * Only unconnected ones: a pin fed by a wire takes its value from the wire, so showing an editable
     * field there would invite an edit that silently does nothing. Below a zoom threshold they collapse to
     * nothing rather than rendering unreadably — the node still says what its pins are, which is the part
     * that matters when you are looking at the whole graph.
     */
    private fun drawEditors(doc: EditorDoc, g: NodeGeometry, ctx: WidgetContext) {
        if (cam.zoom < CanvasCamera.WIDGET_MIN_ZOOM) return
        val fontSize = cam.px(TextMeasure.lineHeight()) * 0.92f
        // Outputs too: a Literal node IS its value, so the one place you can type it is its output pin.
        for (p in g.inputAnchors + g.outputAnchors) {
            val e = p.editor ?: continue
            val x = ctx.cam.toScreenX(e.x)
            val y = ctx.cam.toScreenY(e.y)
            val w = ctx.cam.px(e.w)
            val h = ctx.cam.px(e.h)
            val id = editorId(g.node.id, p.input, p.spec.name)
            val cur = if (g.node.literals.containsKey(p.spec.name)) g.node.literals[p.spec.name] else p.spec.default

            // One editor per TYPE, in one place — see ValueEditors. The node and the outline sidebar draw
            // the same widget for the same pin, which they had already stopped doing.
            // Rebuilt around the EFFECTIVE type, so setting a boolean variable gives you a toggle rather
            // than the free-text field a wildcard would.
            val spec = if (p.type == p.spec.type) p.spec
            else dev.ziggle.vscript.model.PinSpec(p.spec.name, p.type, default = p.spec.default)
            val v = if (p.type.isWildcard) {
                // A wildcard has no widget of its own; a Literal types into a text field and the value's
                // type follows what was written — see dev.ziggle.vscript.model.Literals.
                CanvasWidgets.text(ctx, id, x, y, w, h, display(cur), fontSize, "value")
                null
            } else {
                ValueEditors.draw(ctx, id, x, y, w, h, spec, cur, fontSize)
            }
            if (v != null && !readOnly) doc.setLiteral(g.node.id, p.spec.name, v)
        }
    }

    /** How a literal of unknown type reads in its field. */
    private fun display(v: Any?): String = when (v) {
        null -> ""
        is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    private fun editorId(nodeId: Int, input: Boolean, pin: String): String =
        "v|$nodeId|" + (if (input) "i" else "o") + "|$pin"

    private fun commentEditId(nodeId: Int, heading: Boolean): String =
        "c|$nodeId|" + (if (heading) "h" else "b")

    /**
     * While a comment's heading or body is being edited, draw the field over the text it replaces.
     *
     * The heading is one line in the title strip; the body is a wrapped, scrolling, multi-line field laid
     * out over [NodeGeometry.bodyRect] — the same rect the painter wraps the static text into, so nothing
     * moves when the edit begins or ends.
     *
     * The field registers itself as hot (and as active while drag-selecting) so clicking *inside* it places
     * the caret instead of committing: `pumpKeyboard` treats any click that is not on the focused field as
     * "tabbed away", which is right for a click on the canvas and exactly wrong for a click on the text.
     */
    private fun drawCommentEditor(g: NodeGeometry, ctx: WidgetContext) {
        val focused = ctx.focused ?: return
        val headingId = commentEditId(g.node.id, true)
        val bodyId = commentEditId(g.node.id, false)
        if (focused != headingId && focused != bodyId) return

        val fontSize = cam.px(TextMeasure.lineHeight())
        val pad = ctx.cam.px(5f)
        val m = ImGui.getMousePos()
        val shift = ImGui.getIO().keyShift

        if (focused == headingId) {
            val inset = ctx.cam.px(NodeGeometry.PAD_X) * 0.5f
            val x = ctx.cam.toScreenX(g.rect.x) + inset
            val w = ctx.cam.px(g.rect.w) - inset * 2
            val y = ctx.cam.toScreenY(g.rect.y) + ctx.cam.px(3f)
            val h = ctx.cam.px(NodeGeometry.HEADER_H) - ctx.cam.px(6f)
            val over = ctx.hit(focused, x, y, w, h)
            editorFrame(ctx, x, y, w, h)
            // The same click-to-place and drag-to-select the pin fields use — it placed the caret already,
            // but a drag through it selected nothing, which is not a difference a heading has earned.
            CanvasWidgets.singleLineMouse(ctx, focused, over, x, pad, fontSize)
            TextEdit.draw(ctx.dl, ctx.edit, x, y, w, h, pad, fontSize)
            return
        }

        // The frame is inflated by the padding so the text inside it lands exactly where the painter puts
        // the static body — the editor is a frame drawn AROUND the text, not a box the text is moved into.
        //
        // Its HEIGHT follows the text: one line to start, growing as the note wraps or a Shift+Enter adds a
        // row. Filling the comment's whole interior the moment you double-click it made a one-word note
        // look like a wall of empty field, and hid every node the box was wrapping.
        val x = ctx.cam.toScreenX(g.bodyTextX) - pad
        val y = ctx.cam.toScreenY(g.bodyTextY) - pad
        val w = ctx.cam.px(g.bodyTextW) + pad * 2

        // The canvas measures with its own baked ladder at the zoomed size. This used to be `layout`'s
        // DEFAULT argument, and it was the one thing that made a general text widget a canvas one.
        val layout = TextEdit.layout(ctx.edit.text, ctx.cam.px(g.bodyTextW), fontSize) {
            CanvasRenderer.textWidth(it, fontSize)
        }
        // Published for pumpKeyboard, which runs later this frame and needs somewhere for Up/Down to go.
        ctx.editLayout = layout

        val oneRow = layout.lineHeight + pad * 2
        // Never past the bottom of the box; beyond that the field scrolls instead of growing.
        val room = (ctx.cam.toScreenY(g.rect.bottom) - ctx.cam.px(NodeGeometry.PAD_Y) - y).coerceAtLeast(oneRow)
        val h = (layout.lines.size * layout.lineHeight + pad * 2).coerceIn(oneRow, room)

        val over = ctx.hit(focused, x, y, w, h)
        editorFrame(ctx, x, y, w, h)

        if (over && ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
            ctx.active = focused
            ctx.consumedClick = true
            TextEdit.caretFromMouse(ctx.edit, layout, m.x, m.y, x, y, pad, shift)
        } else if (ctx.active == focused && ImGui.isMouseDown(ImGuiMouseButton.Left)) {
            TextEdit.caretFromMouse(ctx.edit, layout, m.x, m.y, x, y, pad, extend = true)
        }
        TextEdit.drawMultiline(ctx.dl, ctx.edit, layout, x, y, w, h, pad)
    }

    /** The chrome around an open text field: a near-opaque plate so what is underneath cannot compete with
     *  what is being typed, and an accent border to say which field owns the keyboard. */
    private fun editorFrame(ctx: WidgetContext, x: Float, y: Float, w: Float, h: Float) {
        val r = ctx.cam.px(3f)
        ctx.dl.addRectFilled(x, y, x + w, y + h, EDITOR_BG, r, ImDrawFlags.RoundCornersAll)
        ctx.dl.addRect(
            x, y, x + w, y + h, Theme.ACCENT, r, ImDrawFlags.RoundCornersAll, maxOf(1f, ctx.cam.px(1f)),
        )
    }

    /** Apply a committed text edit back to the document, converted to the pin's type. */
    private fun commitEditor(doc: EditorDoc, id: String, text: String) {
        if (readOnly) return
        val parts = id.split('|')
        if (parts.size < 3) return
        if (parts[0] == "c") {
            val nodeId = parts[1].toIntOrNull() ?: return
            val n = doc.node(nodeId) ?: return
            if (parts[2] == "h") {
                doc.renameContainer(n.id, text)
                return
            }
            // A longer note needs more room, and the nodes inside have to move out of its way — otherwise
            // adding a second line silently parks the text on top of whatever the box was wrapping. The
            // box keeps its TOP edge and grows downward, because the top is what you positioned it by.
            val geo = pendingGeo
            val byId = geo.associateBy { it.node.id }
            val width = byId[nodeId]?.rect?.w ?: n.w
            val members = membersOf(doc, byId, nodeId)
            val insetBefore = NodeGeometry.commentInset(n.body, width)
            doc.edit("edit comment") {
                n.body = text.ifBlank { null }
                val delta = NodeGeometry.commentInset(n.body, width) - insetBefore
                if (delta != 0f) {
                    n.h = (n.h + delta).coerceAtLeast(NodeGeometry.HEADER_H * 2f)
                    for (id in members) doc.node(id)?.let { it.y += delta }
                }
            }
            return
        }
        if (parts[0] != "v" || parts.size != 4) return
        val nodeId = parts[1].toIntOrNull() ?: return
        val input = parts[2] == "i"
        val raw = parts[3]
        val pin = ValueEditors.baseName(raw)
        val node = doc.node(nodeId) ?: return
        val d = descOf(doc, node) ?: return
        val declared = (if (input) d.input(pin) else d.output(pin)) ?: return
        val type = dev.ziggle.vscript.model.effectivePinType(node, declared) { doc.variable(it)?.type }
        val spec = if (type == declared.type) declared
        else dev.ziggle.vscript.model.PinSpec(declared.name, type, default = declared.default)
        if (ValueEditors.isComponent(raw)) {
            val current = if (node.literals.containsKey(pin)) node.literals[pin] else spec.default
            doc.setLiteral(nodeId, pin, ValueEditors.mergeComponent(raw, text, current))
            return
        }
        // One decision about what typed text means, shared with the outline — see Literals. A type that
        // cannot read the text answers null, which means "leave what was there" rather than "store
        // nothing": half-typing a number should not wipe the value.
        if (spec.type.builtin == PinType.BOOL || spec.type.builtin == PinType.STRING) {
            doc.setLiteral(nodeId, pin, dev.ziggle.vscript.editor.host.textFor(spec.type)?.parse(text)
                ?: dev.ziggle.vscript.model.Literals.of(spec.type, text))
            return
        }
        val value = dev.ziggle.vscript.editor.host.textFor(spec.type)?.parse(text)
            ?: dev.ziggle.vscript.model.Literals.of(spec.type, text) ?: return
        doc.setLiteral(nodeId, pin, value)
    }

    /**
     * Whether the pin under the cursor would accept the in-flight link.
     *
     * Shown live on the dragged wire. The reference implementation had the check but never ran it during
     * the drag, so the wire looked identical over a legal target, an illegal one and empty space — the
     * largest single UX gap it had.
     */
    private fun validityUnderCursor(
        doc: EditorDoc,
        geo: List<NodeGeometry>,
        ox: Float,
        oy: Float,
        d: Drag.Linking,
    ): Boolean? {
        val mouse = ImGui.getMousePos()
        val gx = cam.toGraphX(mouse.x - ox)
        val gy = cam.toGraphY(mouse.y - oy)
        val hit = geo.asReversed().firstNotNullOfOrNull { g -> g.pinAt(gx, gy)?.let { g to it } } ?: return null
        if (hit.first.node.id == d.nodeId || hit.second.input == d.fromInput) return false
        val fromNode = doc.node(d.nodeId) ?: return false
        val fromDesc = descOf(doc, fromNode) ?: return false
        val fromSpec = (if (d.fromInput) fromDesc.inputs else fromDesc.outputs)
            .firstOrNull { it.name == d.pin } ?: return false
        val fromType = dev.ziggle.vscript.model.effectivePinType(fromNode, fromSpec) { doc.variable(it)?.type }
        val outType = if (!d.fromInput) fromType else hit.second.type
        val inType = if (!d.fromInput) hit.second.type else fromType
        return canConnect(outType, inType)
    }

    private fun visual(g: NodeGeometry) = CanvasRenderer.NodeVisual(
        selected = g.node.id in selection,
        hovered = false,
        error = issuesByNode[g.node.id]?.any { it.severity == Severity.ERROR } == true,
        active = g.node.id in activeNodes,
        hoveredPin = hoveredPin?.takeIf { it.first == g.node.id }?.second,
        logLevel = logLevels[g.node.id],
        breakpoint = g.node.id in breakpoints,
        breakpointEnabled = breakpoints[g.node.id] != false,
        paused = g.node.id == pausedNode,
        editingHeading = widgets.focused == commentEditId(g.node.id, true),
        editingBody = widgets.focused == commentEditId(g.node.id, false),
    )

    // ---- popups -----------------------------------------------------------------------------------

    /** The type of the pin a link drag started from, for filtering the palette. */
    private fun pinTypeOf(doc: EditorDoc, d: Drag.Linking): TypeRef? {
        val desc = catalog[doc.node(d.nodeId)?.type ?: return null] ?: return null
        return (if (d.fromInput) desc.inputs else desc.outputs).firstOrNull { it.name == d.pin }?.type
    }

    /** Create the picked node, wrapping the selection for a comment and auto-wiring a dropped link. */
    private fun createFromPalette(doc: EditorDoc, chosen: NodeDescriptor) {
        if (readOnly) return
        if (chosen.type == BuiltinNodes.COMMENT) {
            commentSelection(doc)
            pendingFromPin = null
            return
        }
        val created = doc.addNode(chosen.type, palette.createAtX, palette.createAtY)
        raise(listOf(created.id))
        // Auto-wire back to the pin the drag came from — the whole point of dropping a wire on empty space.
        pendingFromPin?.let { src ->
            val srcDesc = doc.node(src.nodeId)?.let { descOf(doc, it) } ?: return@let
            if (src.fromInput) {
                val target = srcDesc.inputs.firstOrNull { it.name == src.pin } ?: return@let
                val match = chosen.outputs.firstOrNull { canConnect(it.type, target.type) } ?: return@let
                doc.addLink(created.id, match.name, src.nodeId, target.name, target.type)
            } else {
                val source = srcDesc.outputs.firstOrNull { it.name == src.pin } ?: return@let
                val match = chosen.inputs.firstOrNull { canConnect(source.type, it.type) } ?: return@let
                doc.addLink(src.nodeId, source.name, created.id, match.name, match.type)
            }
        }
        pendingFromPin = null
    }

    private fun popups(doc: EditorDoc) {
        if (ImGui.beginPopup(LINK_MENU)) {
            if (!readOnly && ImGui.menuItem("Delete link")) {
                // Deletes the whole selection when the link is part of it, matching the node menu and the
                // Delete key — and as ONE undo step.
                val ids = if (contextLink in selectedLinks) selectedLinks.toList() else listOf(contextLink)
                doc.edit("delete") { ids.filter { it >= 0 }.forEach { doc.removeLink(it) } }
                selectedLinks.removeAll(ids.toSet())
            }
            ImGui.endPopup()
        }

        if (ImGui.beginPopup(NODE_MENU)) {
            // A Get/Set node's whole meaning is which variable it names, so the menu that acts on it is
            // where that choice belongs — the title already shows the answer.
            val target = doc.node(openNodeMenuFor)
            if (!readOnly && target != null && (target.type == BuiltinNodes.VAR_GET || target.type == BuiltinNodes.VAR_SET)) {
                if (doc.variables.isEmpty()) ImGui.textDisabled("no variables yet")
                for (v in doc.variables) {
                    if (ImGui.menuItem("${v.name}  (${v.type.name.lowercase()})", "", target.variable == v.name)) {
                        doc.setNodeVariable(target.id, v.name)
                    }
                }
                if (ImGui.menuItem("New variable…")) onNewVariable(target.id)
                ImGui.separator()
            }
            if (ImGui.menuItem("Toggle breakpoint", "F9", openNodeMenuFor in breakpoints)) {
                if (openNodeMenuFor >= 0) onToggleBreakpoint(openNodeMenuFor)
            }
            ImGui.separator()
            if (!readOnly && ImGui.menuItem("Delete")) {
                // Right-clicking a node that is part of the selection deletes the selection, matching what
                // Delete does — a context menu that acts on one node while three are highlighted surprises.
                val ids = if (openNodeMenuFor in selection) selection.toList() else listOf(openNodeMenuFor)
                doc.edit("delete") { ids.filter { it >= 0 }.forEach { doc.removeNode(it) } }
                selection.removeAll(ids.toSet())
            }
            ImGui.endPopup()
        }
    }

    internal companion object {
        /** How long the cursor must rest on a pin or header before it is explained, in milliseconds. */
        private const val TOOLTIP_DWELL_MS = 400L

        /** How close to the canvas edge a drag must get before the view starts following it, in pixels. */
        private const val EDGE_MARGIN = 48f

        /** Top speed of that follow, in pixels per second at full press. */
        private const val EDGE_PAN_SPEED = 900f

        /** Arrow-key pan per press, in pixels; Shift takes the coarse one. */
        private const val ARROW_PAN = 60f
        private const val ARROW_PAN_FAST = 240f

        const val NODE_MENU = "##vs-node"
        const val LINK_MENU = "##vs-link"

        /** Loops per second for the flow arrows. */
        const val FLOW_SPEED = 0.55f

        /** How near the cursor has to be to a wire to pick it, in pixels. Screen-space, so a wire stays
         *  equally clickable at every zoom. */
        const val LINK_PICK_PX = 8f

        /** Samples along a wire when testing it against a marquee. */
        const val LINK_SAMPLES = 20

        /**
         * Breathing room between a comment box and the nodes it wraps.
         *
         * Sized for WIRES, not for looks: every wire entering or leaving a comment has to get from its edge
         * to whatever it connects to, and the only room to do that in is this. One lane, plus the clearance
         * a wire keeps off a node.
         *
         * Two lanes was tried and is too much — it reads as a box padded for the sake of it. The container's
         * own edge is not an obstacle to the wires inside it, so a single lane genuinely is room for one
         * wire to run and turn; a second wire wanting the same band is what the router's own lane hints are
         * for, and it can find one outside the box as easily as in.
         */
        val COMMENT_PAD: Float get() = OrthogonalRouter.MARGIN + OrthogonalRouter.LANE

        /**
         * Side room inside a function box.
         *
         * Wider than a comment's pad because the box carries PINS on its edges: hugging the body would put
         * the boundary pins on top of the very nodes wired to them, and the wires would double back on
         * themselves to reach round.
         */
        @JvmStatic var FN_BODY_INSET = 90f

        /** Vertical gap between the main flow and the routines stacked under it. */
        @JvmStatic var SCOPE_GAP = 120f

        /**
         * How much wider than tall a group is aimed at.
         *
         * A graph is read left to right and screens are that shape, so filling sideways before downwards
         * is the arrangement that puts more of a script in front of you at once.
         */
        @JvmStatic var ROW_ASPECT = 1.8

        /**
         * How much more a unit of EXEC wire costs than a unit of data wire, when choosing where a box goes.
         *
         * The exec wires are the spine — the order things happen in, and what anybody reads first. Keeping
         * those short is worth more than keeping a value feed short by the same amount.
         */
        @JvmStatic var EXEC_WEIGHT = 3f

        /** Cap on a badge tooltip, so one runaway node cannot render a tooltip taller than the screen. */
        const val TOOLTIP_CHARS = 1200

    }
}

/**
 * A [CanvasCamera] shifted into window coordinates.
 *
 * The camera works relative to the canvas's top-left; the draw list works in window space. Rather than
 * adding the origin at every one of the renderer's dozens of draw sites, it is folded in once here.
 */
class ScreenCamera(private val cam: CanvasCamera, private val ox: Float, private val oy: Float) {
    val zoom: Float get() = cam.zoom
    val originX: Float get() = cam.originX + ox
    val originY: Float get() = cam.originY + oy
    fun toScreenX(gx: Float): Float = cam.toScreenX(gx) + ox
    fun toScreenY(gy: Float): Float = cam.toScreenY(gy) + oy
    fun toGraphX(sx: Float): Float = cam.toGraphX(sx - ox)
    fun toGraphY(sy: Float): Float = cam.toGraphY(sy - oy)
    fun px(len: Float): Float = cam.px(len)
}
