package dev.ziggle.vscript.layout

import dev.ziggle.vscript.compile.graph
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Auto-arrange.
 *
 * Pure geometry, so it is testable without a canvas — which matters because the failure modes (a node left
 * of its own input, two nodes stacked on the same pixel, a cyclic graph hanging the layout) are exactly the
 * ones that are miserable to diagnose by eye.
 */
class AutoLayoutTest {

    private val step = hostNode(
        "test.step", "step", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("A", PinType.INT)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Result", PinType.INT)),
    )
    private val catalog = NodeCatalog(listOf(step))
    private val size: (Int) -> Pair<Float, Float> = { 100f to 50f }

    /**
     * Pin offsets standing in for real geometry: the exec pin is near the top, the data pin below it.
     *
     * The point of the whole straightening pass is that nodes line up on the WIRE, not on their tops — so a
     * test that leaves every pin at the node's centre cannot tell a correct layout from a lucky one.
     */
    private val stagger = AutoLayout.PinOffsets { _, pin, _ ->
        when (pin) {
            "Exec" -> 10f
            "A", "Result" -> 34f
            else -> null
        }
    }

    /** Every pair of nodes, checked for a genuine rectangle overlap. */
    private fun assertNoOverlaps(pos: Map<Int, Pair<Float, Float>>, w: Float = 100f, h: Float = 50f) {
        val ids = pos.keys.toList()
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            val a = pos.getValue(ids[i])
            val b = pos.getValue(ids[j])
            val hit = a.first < b.first + w && b.first < a.first + w &&
                a.second < b.second + h && b.second < a.second + h
            assertTrue(!hit, "node ${ids[i]} at $a overlaps node ${ids[j]} at $b")
        }
    }

    /**
     * A value read by several nodes has to clear ALL of them, not just the first one to ask.
     *
     * Placing parameters by recursing from each consumer put a shared node wherever the first consumer
     * wanted it and left it sitting on the others — a List feeding Count, First, Item At, Contains and Is
     * Empty overlapped two of them on a real graph.
     */
    @Test
    fun `a value read by several nodes clears all of them`() {
        val g = graph {
            val e = node(BuiltinNodes.ENTRY)
            val s = node("test.step")
            val shared = node(BuiltinNodes.LITERAL_INT)
            val r1 = node(BuiltinNodes.LIST_COUNT_OF)
            val r2 = node(BuiltinNodes.LIST_FIRST)
            val r3 = node(BuiltinNodes.LIST_IS_EMPTY)
            link(e, "Exec", s, "Exec")
            link(shared, "Value", r1, "List")
            link(shared, "Value", r2, "List")
            link(shared, "Value", r3, "List")
            link(r1, "Count", s, "A")
        }
        assertNoOverlaps(AutoLayout.arrange(g, catalog, size, pins = stagger))
    }

    /**
     * Two values feeding the SAME node are straightened to two pins a few pixels apart — and so land on
     * each other unless something separates them afterwards.
     */
    @Test
    fun `two parameters of one node do not land on each other`() {
        val g = graph {
            val e = node(BuiltinNodes.ENTRY)
            val s = node("test.step")
            val fmt = node(BuiltinNodes.FORMAT, literals = mapOf("Template" to "{a} {b}"))
            val p1 = node(BuiltinNodes.LITERAL_INT)
            val p2 = node(BuiltinNodes.LITERAL_INT)
            link(e, "Exec", s, "Exec")
            link(p1, "Value", fmt, "a")
            link(p2, "Value", fmt, "b")
            link(fmt, "Text", s, "A")
        }
        assertNoOverlaps(AutoLayout.arrange(g, catalog, size, pins = stagger))
    }

    /** The whole-graph guarantee, over a shape with branches, values and a shared producer. */
    @Test
    fun `an arranged graph has no overlapping nodes at all`() {
        val g = graph {
            val e = node(BuiltinNodes.ENTRY)
            val br = node(BuiltinNodes.BRANCH)
            val t = node("test.step")
            val f = node("test.step")
            val after = node("test.step")
            val cond = node(BuiltinNodes.GT)
            val lhs = node(BuiltinNodes.LITERAL_INT)
            val shared = node(BuiltinNodes.LITERAL_INT)
            link(e, "Exec", br, "Exec")
            link(br, "True", t, "Exec")
            link(br, "False", f, "Exec")
            link(t, "Exec", after, "Exec")
            link(cond, "Result", br, "Condition")
            link(lhs, "Value", cond, "A")
            link(shared, "Value", cond, "B")
            link(shared, "Value", t, "A")
            link(shared, "Value", f, "A")
        }
        assertNoOverlaps(AutoLayout.arrange(g, catalog, size, pins = stagger))
    }

    /**
     * A gap several wires must cross is widened to fit them.
     *
     * Separating wires is only possible within the gap the layout left, so a dozen of them crossing one
     * 90px column gap have nowhere to spread TO and end up drawn on top of each other however good the
     * routing is. The layout has to leave the room; the router cannot invent it.
     */
    @Test
    fun `a gap many wires must cross is widened to fit them`() {
        fun gapWith(producers: Int): Float {
            val g = graph {
                val e = node(BuiltinNodes.ENTRY)
                val a = node("test.step")
                val sink = node(BuiltinNodes.FORMAT, literals = mapOf("Template" to (0 until producers).joinToString(" ") { "{p$it}" }))
                link(e, "Exec", a, "Exec")
                link(sink, "Text", a, "A")
                // Several values all feeding the one consumer, so their wires share a crossing.
                repeat(producers) {
                    val p = node(BuiltinNodes.LITERAL_INT)
                    link(p, "Value", sink, "p$it")
                }
            }
            val pos = AutoLayout.arrange(g, catalog, size, pins = stagger)
            // The gap immediately left of the consumer, which every producer's wire crosses.
            val sinkX = pos.getValue(3).first
            val producerRight = (4..3 + producers).maxOf { pos.getValue(it).first + 100f }
            return sinkX - producerRight
        }
        assertTrue(
            gapWith(6) > gapWith(2),
            "six wires should be given more room than two: ${gapWith(6)} vs ${gapWith(2)}",
        )
    }

    /** A graph that needs no extra room must come out exactly as it did before widening existed. */
    @Test
    fun `widening leaves an uncrowded graph alone`() {
        val g = graph {
            val e = node(BuiltinNodes.ENTRY)
            val a = node("test.step")
            val b = node("test.step")
            link(e, "Exec", a, "Exec")
            link(a, "Exec", b, "Exec")
        }
        val pos = AutoLayout.arrange(g, catalog, size, pins = stagger)
        // Plain chain, no data wires: the columns should sit exactly one gap apart.
        assertEquals(100f + AutoLayout.GAP_X, pos.getValue(2).first - pos.getValue(1).first, 0.5f)
        assertEquals(100f + AutoLayout.GAP_X, pos.getValue(3).first - pos.getValue(2).first, 0.5f)
    }

    /** A branch's arm reserves the space its WHOLE subtree needs, not just its first node. */
    @Test
    fun `a deep branch does not slide under its sibling`() {
        val g = graph {
            val e = node(BuiltinNodes.ENTRY)
            val br = node(BuiltinNodes.BRANCH)
            val t1 = node("test.step")
            val t2 = node("test.step")
            val t3 = node("test.step")
            val f1 = node("test.step")
            link(e, "Exec", br, "Exec")
            link(br, "True", t1, "Exec")
            link(t1, "Exec", t2, "Exec")
            link(t2, "Exec", t3, "Exec")
            link(br, "False", f1, "Exec")
        }
        val pos = AutoLayout.arrange(g, catalog, size, pins = stagger)

        // The false arm must clear the deepest point of the true arm, not merely the first node of it.
        val trueArmBottom = listOf(3, 4, 5).maxOf { pos.getValue(it).second + 50f }
        assertTrue(
            pos.getValue(6).second >= trueArmBottom - 1f,
            "false arm at ${pos.getValue(6).second} runs into the true arm ending at $trueArmBottom",
        )
    }

    /**
     * A straight run lines up so the exec WIRE is horizontal — the pins share a Y, not the node tops.
     *
     * With staggered pins these two are different answers, and only one of them draws a straight wire.
     */
    @Test
    fun `a chain is straightened pin to pin, not top to top`() {
        val g = graph {
            val e = node(BuiltinNodes.ENTRY)
            val a = node("test.step")
            link(e, "Exec", a, "Exec")
        }
        // The entry's only pin sits at 10; the step's exec input also at 10 — so with equal offsets the
        // tops coincide. Give the step a different offset and the TOPS must diverge to keep the pins level.
        val offset = AutoLayout.PinOffsets { id, _, _ -> if (id == 1) 10f else 30f }
        val pos = AutoLayout.arrange(g, catalog, size, pins = offset)
        val entryPin = pos.getValue(1).second + 10f
        val stepPin = pos.getValue(2).second + 30f
        assertEquals(entryPin, stepPin, 0.5f, "the wire should be horizontal")
        assertTrue(pos.getValue(1).second != pos.getValue(2).second, "their tops should NOT match")
    }

    @Test
    fun `a parameter node sits to the left of what consumes it`() {
        val g = graph {
            val e = node(BuiltinNodes.ENTRY)
            val a = node("test.step")
            val lit = node(BuiltinNodes.LITERAL_INT, literals = mapOf("Value" to 3))
            link(e, "Exec", a, "Exec")
            link(lit, "Value", a, "A")
        }
        val pos = AutoLayout.arrange(g, catalog, size, pins = stagger)
        assertTrue(
            pos.getValue(3).first + 100f <= pos.getValue(2).first + 1f,
            "the literal at ${pos.getValue(3)} should end left of the step at ${pos.getValue(2)}",
        )
    }

    /**
     * A parameter is never pushed back past its consumer's own predecessor on the spine.
     *
     * That is the invariant; there are two ways to hold it. Normally the exec walk has already reserved the
     * parameter's width when spacing the spine, so it simply fits to the left. When it cannot — a join,
     * where the node was placed from one path and another exec predecessor turns out to reach further
     * right — the parameter drops BELOW its consumer instead ("helixing"). Either is fine. Marching left
     * across the predecessor is not: the data wire would then cross the exec wire it should run parallel to.
     */
    @Test
    fun `a parameter never overruns the spine behind it`() {
        val g = graph {
            val e = node(BuiltinNodes.ENTRY)
            val a = node("test.step")
            val b = node("test.step")
            val lit = node(BuiltinNodes.LITERAL_INT, literals = mapOf("Value" to 3))
            link(e, "Exec", a, "Exec")
            link(a, "Exec", b, "Exec")
            link(lit, "Value", b, "A") // feeds the SECOND step, whose left is occupied by the first
        }
        val pos = AutoLayout.arrange(g, catalog, size, pins = stagger)
        val param = pos.getValue(4)
        // Node 2 is the step BEFORE the consumer — the thing the parameter must not reach back across.
        val predecessorRight = pos.getValue(2).first + 100f
        assertTrue(
            param.first >= predecessorRight - 1f,
            "the parameter at ${param.first} was pushed back past the spine ending at $predecessorRight",
        )
    }

    /**
     * The spine reserves room for the values hanging off it.
     *
     * Space the exec chain for the steps alone and a value node feeding one has nowhere to go: the gap
     * between two steps is smaller than a node plus its padding, so every parameter helixes and the graph
     * comes out with its values stacked underneath instead of beside. The layout would be doing the right
     * thing about the wrong geometry.
     */
    @Test
    fun `a step with parameters is spaced further from its predecessor than one without`() {
        fun gapAfterFirstStep(withParam: Boolean): Float {
            val g = graph {
                val e = node(BuiltinNodes.ENTRY)
                val a = node("test.step")
                val b = node("test.step")
                link(e, "Exec", a, "Exec")
                link(a, "Exec", b, "Exec")
                if (withParam) {
                    val lit = node(BuiltinNodes.LITERAL_INT)
                    link(lit, "Value", b, "A")
                }
            }
            val pos = AutoLayout.arrange(g, catalog, size, pins = stagger)
            return pos.getValue(3).first - (pos.getValue(2).first + 100f)
        }
        assertTrue(
            gapAfterFirstStep(true) > gapAfterFirstStep(false),
            "a parameterised step should be pushed right to make room: " +
                "${gapAfterFirstStep(true)} vs ${gapAfterFirstStep(false)}",
        )
    }

    /** Pressing Arrange twice must not move anything the second time. */
    @Test
    fun `the layout is stable`() {
        val g = graph {
            val e = node(BuiltinNodes.ENTRY)
            val br = node(BuiltinNodes.BRANCH)
            val t = node("test.step")
            val f = node("test.step")
            val lit = node(BuiltinNodes.LITERAL_INT)
            link(e, "Exec", br, "Exec")
            link(br, "True", t, "Exec")
            link(br, "False", f, "Exec")
            link(lit, "Value", t, "A")
        }
        val once = AutoLayout.arrange(g, catalog, size, pins = stagger)
        val twice = AutoLayout.arrange(g, catalog, size, pins = stagger)
        assertEquals(once, twice, "arrange should be deterministic")
    }

    /** A loop's back edge must not drag the node it points at, or the body walks away to the right. */
    @Test
    fun `a back edge does not move the node it returns to`() {
        val g = graph {
            val e = node(BuiltinNodes.ENTRY)
            val loop = node(BuiltinNodes.WHILE)
            val body = node("test.step")
            link(e, "Exec", loop, "Exec")
            link(loop, "Body", body, "Exec")
            link(body, "Exec", loop, "Exec") // back to the loop
        }
        val pos = AutoLayout.arrange(g, catalog, size, pins = stagger)
        assertTrue(
            pos.getValue(2).first < pos.getValue(3).first,
            "the loop should stay left of its body: ${pos.getValue(2)} vs ${pos.getValue(3)}",
        )
    }

    @Test
    fun `a linear chain lays out left to right, one column per step`() {
        val g = graph {
            val e = node(BuiltinNodes.ENTRY)
            val a = node("test.step")
            val b = node("test.step")
            link(e, "Exec", a, "Exec")
            link(a, "Exec", b, "Exec")
        }
        val pos = AutoLayout.arrange(g, catalog, size)

        val xs = listOf(1, 2, 3).map { pos.getValue(it).first }
        assertTrue(xs[0] < xs[1] && xs[1] < xs[2], "columns should advance with the chain: $xs")
    }

    @Test
    fun `a straight chain comes out on one horizontal line`() {
        // The straightening pass is what makes an arranged graph look deliberate rather than stair-stepped.
        val g = graph {
            val e = node(BuiltinNodes.ENTRY)
            val a = node("test.step")
            val b = node("test.step")
            link(e, "Exec", a, "Exec")
            link(a, "Exec", b, "Exec")
        }
        val pos = AutoLayout.arrange(g, catalog, size)
        val ys = listOf(1, 2, 3).map { pos.getValue(it).second }
        assertTrue(ys.distinct().size == 1, "a straight chain should share one row, got $ys")
    }

    @Test
    fun `a producer is placed left of its consumer even with no exec wire`() {
        val g = graph {
            val e = node(BuiltinNodes.ENTRY)
            val consumer = node("test.step")
            val producer = node("test.step")
            link(e, "Exec", consumer, "Exec")
            link(producer, "Result", consumer, "A")
        }
        val pos = AutoLayout.arrange(g, catalog, size)
        assertTrue(
            pos.getValue(3).first < pos.getValue(2).first,
            "data producer should sit left of its consumer",
        )
    }

    @Test
    fun `branches do not overlap`() {
        val g = graph {
            val e = node(BuiltinNodes.ENTRY)
            val br = node(BuiltinNodes.BRANCH)
            val t = node("test.step")
            val f = node("test.step")
            link(e, "Exec", br, "Exec")
            link(br, "True", t, "Exec")
            link(br, "False", f, "Exec")
        }
        val pos = AutoLayout.arrange(g, catalog, size)
        val a = pos.getValue(3)
        val b = pos.getValue(4)
        assertTrue(a != b, "the two branch arms must not land on the same point")
        if (a.first == b.first) {
            assertTrue(kotlin.math.abs(a.second - b.second) >= 50f, "same column needs vertical clearance")
        }
    }

    @Test
    fun `a cyclic graph terminates instead of hanging`() {
        // A While body wired back to the loop node is the normal case, not an error — the layout has to
        // cope with cycles rather than assume a DAG.
        val g = graph {
            val e = node(BuiltinNodes.ENTRY)
            val loop = node(BuiltinNodes.WHILE)
            val body = node("test.step")
            link(e, "Exec", loop, "Exec")
            link(loop, "Body", body, "Exec")
            link(body, "Exec", loop, "Exec")
        }
        val pos = AutoLayout.arrange(g, catalog, size)
        assertEquals(3, pos.size)
        assertTrue(pos.values.all { it.first.isFinite() && it.second.isFinite() })
    }

    @Test
    fun `comment containers are not moved`() {
        // They wrap other nodes, so repositioning one independently tears the box off its contents.
        val g = graph {
            val e = node(BuiltinNodes.ENTRY)
            node(BuiltinNodes.COMMENT)
            link(e, "Exec", e, "Exec")
        }
        val pos = AutoLayout.arrange(g, catalog, size)
        assertTrue(2 !in pos, "the comment box should be left where it is")
    }

    @Test
    fun `an empty graph produces no positions`() {
        assertEquals(emptyMap(), AutoLayout.arrange(graph { }, catalog, size))
    }

    @Test
    fun `every node gets exactly one position`() {
        val g = graph {
            val e = node(BuiltinNodes.ENTRY)
            val a = node("test.step")
            val b = node("test.step")
            val c = node("test.step")
            link(e, "Exec", a, "Exec")
            link(a, "Exec", b, "Exec")
            link(b, "Exec", c, "Exec")
        }
        assertEquals(4, AutoLayout.arrange(g, catalog, size).size)
    }

    /**
     * A layout covers one scope.
     *
     * Arranging a function's body together with the top-level graph strews it across the main flow and
     * leaves the box behind — the exact mess the button exists to clear up. One pass, one group.
     */
    @Test
    fun `a function body is arranged apart from the graph it sits in`() {
        val g = dev.ziggle.vscript.compile.graph {
            function("F")
            val start = node(BuiltinNodes.ENTRY)
            val log = node(BuiltinNodes.LOG)
            link(start, "Exec", log, "Exec")

            node(BuiltinNodes.FUNCTION, function = "F")
            node(BuiltinNodes.ADD, function = "F")
        }
        val catalog = NodeCatalog()
        val topLevel = AutoLayout.arrange(g, catalog, include = { it.function == null })
        val body = AutoLayout.arrange(g, catalog, include = { it.function == "F" })

        val bodyIds = g.nodes.filter { it.function == "F" && it.type == BuiltinNodes.ADD }.map { it.id }
        assertTrue(topLevel.keys.none { it in bodyIds }, "the top-level pass placed a body node")
        assertEquals(bodyIds.toSet(), body.keys, "the body pass should place exactly the body")
        // Neither pass places a container: moving one would tear it off what it holds.
        val boxes = g.nodes.filter { it.type == BuiltinNodes.FUNCTION }.map { it.id }
        assertTrue((topLevel.keys + body.keys).none { it in boxes })
    }
}
