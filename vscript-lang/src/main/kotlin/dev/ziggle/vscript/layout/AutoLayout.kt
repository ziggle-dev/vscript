package dev.ziggle.vscript.layout

import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.Link
import dev.ziggle.vscript.model.Node
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind

/**
 * Automatic graph arrangement — the "tidy this up" button.
 *
 * **A rooted tree walk, not a general graph layout.** This used to be a Sugiyama-style layered layout —
 * layer assignment, barycentre ordering, coordinate straightening — which is the textbook answer for
 * arbitrary directed graphs and the wrong one here. A node graph is not an arbitrary graph: it has a
 * distinguished **execution spine** running left to right from an entry node, with value nodes hanging off
 * it, and it is read by following that spine. A layered layout does not know the spine exists. It treats an
 * exec wire and a wire carrying an item id as the same kind of edge, mixes both into one layering, and
 * produces something that is technically well-ordered and does not read as the program.
 *
 * So instead:
 *
 *  1. **Roots.** Entry nodes, or anything with nothing flowing into it. The root's position is where the
 *     group begins; everything else is placed relative to it.
 *  2. **Walk the exec spine for X.** Depth-first along exec output pins. A child sits one node-width plus a
 *     gap to the right of its parent, so the spine reads as steps in order. A node already placed is left
 *     alone — that is how a loop's back edge is handled: ignored for placement, drawn afterwards.
 *  3. **Y by subtree bands.** Each branch reserves the bounding box of its WHOLE subtree, and siblings
 *     stack so those boxes cannot intersect. Reserving the node rather than the subtree is the classic
 *     mistake — it looks fine until a branch grows a second step and slides under its sibling.
 *  4. **Parameter nodes in a second pass.** Pure value nodes are laid to the LEFT of whatever consumes
 *     them, in data-flow order. When that would push one back past the consumer's own predecessor — a deep
 *     pure chain feeding a node deep in the spine — they drop BELOW instead ("helixing"), which keeps the
 *     spine intact at the cost of a bend in one data wire.
 *  5. **Straighten pin to pin.** A node's Y is chosen so its input pin lands at exactly the Y of the output
 *     pin feeding it, giving a horizontal wire. This is pin-relative, not box-relative — two nodes with
 *     different pin counts line up on the *wire*, not on their tops — and it is most of what makes an
 *     arranged graph look deliberate rather than merely non-overlapping.
 *  6. **Resolve what straightening broke.** Pulling a node up to align a pin can push it into a sibling's
 *     band, so a final sweep separates overlaps, giving way to the exec spine and letting value nodes
 *     absorb the displacement.
 *
 * Sizes are MEASURED, never estimated — the canvas hands in real widths and heights, and pin offsets, from
 * geometry it has already computed. (A Slate-based editor has to cache these per node because a widget
 * cannot be measured until it has ticked; drawing our own nodes means we simply know.)
 *
 * Pure geometry, no ImGui — so it is unit-testable and survives replacing the canvas.
 */
object AutoLayout {

    /** Horizontal gap between a node and the next step of the spine. */
    const val GAP_X = 90f

    /** Vertical gap between sibling branches, and between stacked parameter nodes. */
    const val GAP_Y = 40f

    /**
     * How much room a channel between two columns needs per wire crossing it.
     *
     * The layout's own estimate of what the router will want, which is all it can be: the router decides
     * where a wire actually goes, and it does that after the nodes have been placed. These moved here when
     * the channel router they belonged to was deleted — the layout was their only remaining reader.
     */
    const val WIRE_SEPARATION = 20f
    const val WIRE_STUB = 20f

    /** Gap between a parameter node and the node it feeds. Tighter than [GAP_X]: they belong together. */
    const val PARAM_GAP_X = 40f

    /**
     * How close two X positions must be to count as the same column.
     *
     * Nodes placed from the same parent share an X exactly, so this only has to be tight enough not to
     * merge two genuinely different columns.
     */
    const val COLUMN_EPS = 40f

    /** Fallback node size when the canvas has not measured one yet. */
    private const val DEFAULT_W = 180f
    private const val DEFAULT_H = 90f

    /** Where a pin sits, as an offset from its node's top edge. */
    fun interface PinOffsets {
        /** Null when the node has no such pin — the layout then falls back to the node's centre. */
        fun offsetOf(nodeId: Int, pin: String, input: Boolean): Float?
    }

    /**
     * Compute new positions for every node in [graph].
     *
     * [sizeOf] supplies each node's measured `(width, height)`; [pins] where each pin sits within it. Both
     * come from the canvas's real geometry. Comments are excluded — they wrap other nodes, so moving one
     * independently would tear a box away from its contents.
     *
     * @return node id → new `(x, y)`.
     */
    fun arrange(
        graph: Graph,
        catalog: NodeCatalog,
        sizeOf: (Int) -> Pair<Float, Float> = { DEFAULT_W to DEFAULT_H },
        originX: Float = 0f,
        originY: Float = 0f,
        /**
         * Which nodes this pass covers.
         *
         * A layout is always over ONE group — a function's body, a comment's contents, the free nodes of
         * the graph. Passing the whole document and hoping is what strews a group's members across the
         * flow and leaves the box that framed them stretched over the wreckage.
         */
        include: (Node) -> Boolean = { it.function == null },
        pins: PinOffsets = PinOffsets { _, _, _ -> null },
    ): Map<Int, Pair<Float, Float>> {
        val scope = graph.nodes
            .filter { catalog[it.type]?.kind?.isContainer != true }
            .filter(include)
        if (scope.isEmpty()) return emptyMap()
        return Layout(graph, catalog, scope, sizeOf, pins, originX, originY).run()
    }

    /** One arrangement in progress. A class so the passes can share the tables they all read. */
    private class Layout(
        val graph: Graph,
        val catalog: NodeCatalog,
        scope: List<Node>,
        val sizeOf: (Int) -> Pair<Float, Float>,
        val pins: PinOffsets,
        val originX: Float,
        val originY: Float,
    ) {
        val ids: Set<Int> = scope.map { it.id }.toSet()
        val byId: Map<Int, Node> = scope.associateBy { it.id }
        val pos = HashMap<Int, Pair<Float, Float>>()

        fun w(id: Int) = sizeOf(id).first
        fun h(id: Int) = sizeOf(id).second
        fun x(id: Int) = pos.getValue(id).first
        fun y(id: Int) = pos.getValue(id).second

        /** A pin's absolute Y once its node is placed; the node's centre when it has no such pin. */
        fun pinY(id: Int, pin: String, input: Boolean): Float =
            y(id) + (pins.offsetOf(id, pin, input) ?: (h(id) / 2f))

        val execLinks: List<Link> = graph.links.filter { inScope(it) && isExec(it) }
        val dataLinks: List<Link> = graph.links.filter { inScope(it) && !isExec(it) }

        fun inScope(l: Link) = l.fromNode in ids && l.toNode in ids

        fun isExec(l: Link): Boolean =
            byId[l.fromNode]?.let { catalog[it.type] }?.output(l.fromPin)?.type?.isExec == true

        /** Exec successors of [id], in the order its output pins are declared — so True comes before False. */
        fun execChildren(id: Int): List<Link> {
            val d = byId[id]?.let { catalog[it.type] } ?: return emptyList()
            val order = d.outputs.withIndex().associate { (i, p) -> p.name to i }
            return execLinks.filter { it.fromNode == id }.sortedBy { order[it.fromPin] ?: Int.MAX_VALUE }
        }

        /** Data inputs of [id], in declared pin order — the order parameters stack down the left side. */
        fun dataInputs(id: Int): List<Link> {
            val d = byId[id]?.let { catalog[it.type] } ?: return emptyList()
            val order = d.inputs.withIndex().associate { (i, p) -> p.name to i }
            return dataLinks.filter { it.toNode == id }.sortedBy { order[it.toPin] ?: Int.MAX_VALUE }
        }

        fun isPure(id: Int): Boolean = byId[id]?.let { catalog[it.type] }?.kind == NodeKind.PURE

        private val paramWidthCache = HashMap<Int, Float>()

        /**
         * How much room [id]'s parameter tree needs to its LEFT.
         *
         * The exec walk adds this to the next step's X, which is what stops a value node being squeezed
         * between two steps that were spaced only for each other. Without it a literal feeding the first
         * step of a chain has 90px of gap and needs 140, so it helixes — and the whole graph ends up with
         * its values below the spine instead of beside it, which is the layout doing the right thing about
         * the wrong geometry.
         *
         * Memoised, and guarded against a cycle among pure nodes (which the validator rejects, but a layout
         * runs on documents mid-edit that have not been validated).
         */
        fun paramWidth(id: Int, seen: MutableSet<Int> = HashSet()): Float {
            paramWidthCache[id]?.let { return it }
            if (!seen.add(id)) return 0f
            var widest = 0f
            for (link in dataInputs(id)) {
                val p = link.fromNode
                if (!isPure(p)) continue
                widest = maxOf(widest, w(p) + PARAM_GAP_X + paramWidth(p, seen))
            }
            seen.remove(id)
            paramWidthCache[id] = widest
            return widest
        }

        fun run(): Map<Int, Pair<Float, Float>> {
            var top = originY
            for (root in roots()) {
                if (root in pos) continue
                val band = walkExec(root, originX, top)
                top = band.bottom + GAP_Y
            }
            // Anything the walk never reached — an island of pure nodes, an orphan left by an edit. Placed
            // rather than left where it was: an arrange that silently ignores nodes is worse than one that
            // puts them somewhere plain, because you cannot tell it happened.
            for (id in ids) {
                if (id in pos) continue
                pos[id] = originX to top
                top += h(id) + GAP_Y
            }
            parameters()
            separate()
            widenChannels()
            return pos
        }

        /**
         * Push columns apart where too many wires have to squeeze between them.
         *
         * The router can only spread wires within the gap it is given. Where a dozen wires cross one
         * 90px gap there is nowhere to spread them TO, so they end up drawn on top of each other however
         * good the routing is — the separation was an afterthought applied to a layout that had not left
         * room for it.
         *
         * So the layout leaves room. This is deliberately a small nudge and only ever rightward: the
         * columns keep their order and their straightened rows, and a graph that needed no extra space
         * comes out identical to one arranged before this existed.
         */
        fun widenChannels() {
            if (pos.isEmpty()) return
            val columns = pos.keys.groupBy { Math.round(x(it) / COLUMN_EPS) }.toSortedMap()
            val keys = columns.keys.toList()
            if (keys.size < 2) return

            var shift = 0f
            for (i in 0 until keys.size - 1) {
                val left = columns.getValue(keys[i])
                val right = columns.getValue(keys[i + 1])
                // Apply what earlier gaps have already pushed before measuring this one.
                for (id in right) pos[id] = (x(id) + shift) to y(id)

                val leftEdge = left.maxOf { x(it) + w(it) }
                val rightEdge = right.minOf { x(it) }
                val crossing = wiresCrossing(leftEdge, rightEdge)
                if (crossing <= 1) continue
                // Every wire needs its own lane, plus the stubs at each end.
                val needed = crossing * WIRE_SEPARATION + WIRE_STUB * 2f
                val have = rightEdge - leftEdge
                if (have >= needed) continue
                val extra = needed - have
                for (id in right) pos[id] = (x(id) + extra) to y(id)
                shift += extra
            }
            // Everything right of the last measured column has been moved by the loop already; nothing
            // further to do, because the loop applied `shift` to each column as it reached it.
        }

        /** How many DATA wires have to pass through the gap between [leftEdge] and [rightEdge]. */
        fun wiresCrossing(leftEdge: Float, rightEdge: Float): Int =
            dataLinks.count { l ->
                val a = pos[l.fromNode] ?: return@count false
                val b = pos[l.toNode] ?: return@count false
                val from = a.first + w(l.fromNode)
                val to = b.first
                from <= leftEdge + 1f && to >= rightEdge - 1f
            }

        /**
         * Where each walk begins.
         *
         * Entry nodes first — they are the reason the graph runs and belong at the left edge. Then anything
         * with no exec wire into it, which is what a fragment mid-edit looks like. Ties break on id so the
         * result is stable: an arrange that reshuffles on every press is one nobody trusts.
         */
        fun roots(): List<Int> {
            val hasExecIn = execLinks.map { it.toNode }.toSet()
            val entries = ids.filter { byId.getValue(it).let { n -> catalog[n.type]?.kind == NodeKind.ENTRY } }
            val free = ids.filter { it !in hasExecIn && it !in entries && !isPure(it) }
            // Pure nodes last: they are parameters, and starting a walk at one would strand the exec chain
            // that consumes it to the right of a value, which is backwards.
            val purelyFree = ids.filter { it !in hasExecIn && isPure(it) }
            return (entries.sorted() + free.sorted() + purelyFree.sorted())
        }

        class Band(var top: Float, var bottom: Float)

        /**
         * Place [id] at [x] and everything downstream of it, returning the band the whole subtree occupies.
         *
         * A node already placed is left where it is — that is the back-edge rule. A `While` wires its body
         * back to itself and a `Branch` can rejoin, so the exec graph is not a tree; treating the second
         * arrival as "already done" turns it into one, and the wire is simply drawn across the result.
         */
        fun walkExec(id: Int, x: Float, top: Float): Band {
            pos[id] = x to top
            val spineX = x + w(id) + GAP_X
            var cursor = top
            var bottom = top + h(id)
            var first = true
            var firstChildCentre: Float? = null
            var lastChildCentre: Float? = null

            for (link in execChildren(id)) {
                if (link.toNode in pos) continue // back edge, or a join reached by another path
                // The child is pushed right by whatever its own parameters need on their left, so they have
                // somewhere to go that is not underneath it.
                val band = walkExec(link.toNode, spineX + paramWidth(link.toNode), cursor)
                // Straighten the FIRST branch onto its parent's pin, so a plain run of steps — which is
                // most of any graph — comes out on one line. Later branches keep their stacking.
                if (first) {
                    val want = pinY(id, link.fromPin, input = false) -
                        (pins.offsetOf(link.toNode, link.toPin, true) ?: (h(link.toNode) / 2f))
                    shift(link.toNode, want - y(link.toNode), band)
                }
                first = false
                val centre = y(link.toNode) + h(link.toNode) / 2f
                if (firstChildCentre == null) firstChildCentre = centre
                lastChildCentre = centre
                cursor = band.bottom + GAP_Y
                bottom = maxOf(bottom, band.bottom)
            }

            // Centre the parent on the span its children occupy, the way a Branch sits between its two
            // arms. With one child this is exactly the straightening above, so a linear chain is unaffected.
            if (firstChildCentre != null && lastChildCentre != null && firstChildCentre != lastChildCentre) {
                val centre = (firstChildCentre + lastChildCentre) / 2f
                pos[id] = x to (centre - h(id) / 2f)
                bottom = maxOf(bottom, y(id) + h(id))
            }
            return Band(minOf(top, y(id)), bottom)
        }

        /** Move [id] and everything downstream of it by [dy], growing [band] to match. */
        fun shift(id: Int, dy: Float, band: Band) {
            if (dy == 0f) return
            val seen = HashSet<Int>()
            fun go(n: Int) {
                if (!seen.add(n)) return
                pos[n]?.let { pos[n] = it.first to (it.second + dy) }
                for (l in execChildren(n)) if (l.toNode in pos) go(l.toNode)
            }
            go(id)
            band.top += dy
            band.bottom += dy
        }

        /**
         * The second pass: pure value nodes, to the left of whatever consumes them.
         *
         * Ordered by DEPTH from the spine — a value read by an exec node first, then the values feeding
         * that, and so on outward. Doing it that way rather than recursively per consumer is what makes a
         * SHARED node work: one read by five nodes has to clear all five, and a recursive walk places it
         * for whichever consumer happened to reach it first and leaves it overlapping the rest. That was
         * real: a List node feeding Count, First, Item At, Contains and Is Empty sat on top of two of them.
         */
        fun parameters() {
            val depth = HashMap<Int, Int>()
            fun depthOf(id: Int, seen: MutableSet<Int> = HashSet()): Int {
                depth[id]?.let { return it }
                if (!seen.add(id)) return 0
                val d = 1 + (consumersOf(id).filter { isPure(it) }.maxOfOrNull { depthOf(it, seen) } ?: 0)
                seen.remove(id)
                depth[id] = d
                return d
            }
            val pure = ids.filter { isPure(it) }
            for (id in pure.sortedWith(compareBy({ depthOf(it) }, { it }))) place(id)
        }

        /** Every node reading [id]'s output. */
        fun consumersOf(id: Int): List<Int> = dataLinks.filter { it.fromNode == id }.map { it.toNode }

        fun place(id: Int) {
            val consumers = dataLinks.filter { it.fromNode == id && it.toNode in pos }
            if (consumers.isEmpty()) {
                // Nothing reads it. Somewhere plain, out of the spine's way.
                // Below everything placed so far, by the heights they ACTUALLY have. It used to assume
                // sixty — which is about a two-row node — so a column of tall ones was laid out overlapping
                // and then shoved apart by [resolve], and the shoving is what left the gaps: each push
                // clears one neighbour and lands in the next, so the error compounds down the column.
                if (id !in pos) {
                    val below = pos.keys.maxOfOrNull { y(it) + h(it) } ?: (originY - GAP_Y)
                    pos[id] = originX to (below + GAP_Y)
                }
                return
            }
            // LEFT OF THE LEFTMOST consumer, so one value serving several of them clears all of them.
            val leftmost = consumers.minByOrNull { x(it.toNode) }!!
            val wantX = x(leftmost.toNode) - w(id) - PARAM_GAP_X

            // A parameter must not reach back across the node preceding its consumer on the spine, or the
            // data wire crosses the exec wire it should run parallel to. That is what helixing is for.
            val limit = consumers.flatMap { c -> execLinks.filter { it.toNode == c.toNode } }
                .mapNotNull { pos[it.fromNode]?.let { p -> p.first + w(it.fromNode) } }
                .maxOrNull()

            if (limit != null && wantX < limit) {
                val c = leftmost.toNode
                pos[id] = x(c) to (y(c) + h(c) + GAP_Y)
            } else {
                // Pin to pin against the leftmost consumer, so at least that wire comes out horizontal.
                val want = pinY(leftmost.toNode, leftmost.toPin, input = true) -
                    (pins.offsetOf(id, leftmost.fromPin, false) ?: (h(id) / 2f))
                pos[id] = wantX to want
            }
        }

        /**
         * Push apart whatever the straightening pushed together.
         *
         * Straightening and band packing genuinely fight: pulling a node up to align a pin can slide it
         * into a neighbour, and two values feeding the SAME node get aligned to two pins a few pixels
         * apart and land on each other. So this is a real rectangle sweep, not a per-column one — the
         * earlier version bucketed by X and therefore compared none of the overlaps that actually happen,
         * since a value node placed left of its consumer shares a column with nothing.
         *
         * **The spine does not move.** Exec nodes are resolved among themselves first and then treated as
         * fixed; value nodes absorb all the displacement. A layout that nudged the spine to make room for a
         * literal would undo the straightening that is the point of the whole pass.
         */
        fun separate() {
            val spine = pos.keys.filter { !isPure(it) }
            val values = pos.keys.filter { isPure(it) }
            resolve(spine, emptyList())
            resolve(values, spine)
        }

        /** Push each of [movable] down until it clears the ones before it and every one of [fixed]. */
        fun resolve(movable: List<Int>, fixed: List<Int>) {
            val order = movable.sortedWith(compareBy({ x(it) }, { y(it) }, { it }))
            val settled = ArrayList<Int>(fixed)
            for (id in order) {
                var top = y(id)
                // Repeat because clearing one neighbour can slide into the next one down.
                var guard = 0
                while (guard++ < order.size + fixed.size + 1) {
                    val hit = settled.firstOrNull { other ->
                        overlaps(id, top, other)
                    } ?: break
                    top = y(hit) + h(hit) + GAP_Y
                }
                pos[id] = x(id) to top
                settled += id
            }
        }

        /** Would [id] at [top] overlap [other] where it now sits? */
        fun overlaps(id: Int, top: Float, other: Int): Boolean {
            if (id == other) return false
            val ax = x(id)
            val bx = x(other)
            if (ax + w(id) <= bx || bx + w(other) <= ax) return false
            val by = y(other)
            return top < by + h(other) + GAP_Y && by < top + h(id) + GAP_Y
        }
    }
}
