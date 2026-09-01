package dev.ziggle.vscript.editor.graph

import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.Link
import dev.ziggle.vscript.model.Node
import dev.ziggle.vscript.model.NodeCatalog

/**
 * Knots on long wires, so a value crossing the graph runs along a clear lane instead of behind the nodes
 * it passes.
 *
 * **The formatter owns every knot.** Arrange [collapse]s them all and then decides afresh where they are
 * needed. That is the only arrangement that stays consistent: a hand-placed knot and a generated one are
 * the same node, so re-arranging would either accumulate them forever or silently throw yours away, and
 * there is no third option that does not involve telling them apart — which means a flag, which means a
 * document that says where a node came from, which is not a thing worth persisting.
 *
 * Because a reroute compiles to nothing (the compiler reads straight through it), all of this is a change
 * to how the graph LOOKS and never to what it does. That is what makes it safe for a formatting button to
 * be editing the node list at all.
 */
object WireTracks {

    /** A wire must cross at least this much canvas before it is worth knotting. */
    const val MIN_SPAN = 420f

    /** How far above a node's top a lane sits, so the wire clears it rather than grazing it. */
    const val LANE_INSET = 22f

    /**
     * Remove every reroute, reconnecting each wire straight through.
     *
     * The inverse of [insert], and run before it so an arrange is idempotent rather than cumulative.
     *
     * @return the links to keep, with knots spliced out.
     */
    fun collapse(nodes: List<Node>, links: List<Link>, catalog: NodeCatalog): Pair<List<Node>, List<Link>> {
        val knots = nodes.filter { it.type == BuiltinNodes.REROUTE }.map { it.id }.toSet()
        if (knots.isEmpty()) return nodes to links

        // Follow a chain of knots back to whatever real node feeds it.
        fun sourceOf(link: Link): Link? {
            var cur = link
            var hops = 0
            while (cur.fromNode in knots) {
                if (hops++ > knots.size) return null // a ring of knots: nothing real behind it
                cur = links.firstOrNull { it.toNode == cur.fromNode && it.toPin == "In" } ?: return null
            }
            return cur
        }

        val kept = ArrayList<Link>()
        for (l in links) {
            if (l.toNode in knots) continue // the wire INTO a knot is replaced by the one out of it
            if (l.fromNode !in knots) { kept += l; continue }
            val src = sourceOf(l) ?: continue // dangling knot: the wire had no real source, so drop it
            kept += Link(l.id, src.fromNode, src.fromPin, l.toNode, l.toPin)
        }
        return nodes.filter { it.id !in knots } to kept
    }

    /** Where a knot goes, and which link it splits. */
    class Knot(val link: Link, val x: Float, val y: Float)

    /**
     * Decide where knots are needed, given final positions.
     *
     * A wire earns one when it is both LONG and OBSTRUCTED — length alone is not enough, because a long
     * wire across empty canvas is perfectly readable and a knot on it is just a bead to wonder about. The
     * lane is chosen above the tallest thing in the way, which is the one place the wire is certainly clear
     * of everything it was passing behind.
     *
     * Only data wires. The exec spine is laid out adjacently, so its wires are short by construction, and a
     * knot on one would have to be a real step in the chain rather than a bead.
     */
    fun plan(
        links: List<Link>,
        catalog: NodeCatalog,
        nodeOf: (Int) -> Node?,
        rectOf: (Int) -> FloatArray?,
        pinAt: (nodeId: Int, pin: String, input: Boolean) -> Pair<Float, Float>?,
    ): List<Knot> {
        val out = ArrayList<Knot>()
        for (l in links) {
            val from = nodeOf(l.fromNode) ?: continue
            if (catalog[from.type]?.output(l.fromPin)?.type?.isExec != false) continue
            val a = pinAt(l.fromNode, l.fromPin, false) ?: continue
            val b = pinAt(l.toNode, l.toPin, true) ?: continue
            if (Math.abs(b.first - a.first) < MIN_SPAN) continue

            val x1 = minOf(a.first, b.first)
            val x2 = maxOf(a.first, b.first)
            val yLo = minOf(a.second, b.second)
            val yHi = maxOf(a.second, b.second)

            // Anything the wire would pass through on its way across.
            var top: Float? = null
            for (id in obstacleIds(links, l)) {
                if (id == l.fromNode || id == l.toNode) continue
                val r = rectOf(id) ?: continue
                val overlapsX = r[0] < x2 && x1 < r[0] + r[2]
                val overlapsY = r[1] < yHi + 1f && yLo - 1f < r[1] + r[3]
                if (overlapsX && overlapsY) top = minOf(top ?: r[1], r[1])
            }
            val lane = top ?: continue // nothing in the way: leave the wire alone
            out += Knot(l, (x1 + x2) * 0.5f, lane - LANE_INSET)
        }
        return out
    }

    /** Every node id that could be in a wire's way — everything but the knots themselves. */
    private fun obstacleIds(links: List<Link>, exclude: Link): Set<Int> =
        (links.map { it.fromNode } + links.map { it.toNode }).toSet()

    /**
     * Split each planned link with a knot.
     *
     * @return the new nodes to add and the replacement link list.
     */
    fun insert(knots: List<Knot>, links: List<Link>, nextNodeId: () -> Int, nextLinkId: () -> Int):
        Pair<List<Node>, List<Link>> {
        if (knots.isEmpty()) return emptyList<Node>() to links
        val added = ArrayList<Node>()
        val result = ArrayList(links)
        for (k in knots) {
            val id = nextNodeId()
            added += Node(id, BuiltinNodes.REROUTE, x = k.x, y = k.y, function = null)
            result.removeAll { it.id == k.link.id }
            result += Link(k.link.id, k.link.fromNode, k.link.fromPin, id, "In")
            result += Link(nextLinkId(), id, "Out", k.link.toNode, k.link.toPin)
        }
        return added to result
    }
}
