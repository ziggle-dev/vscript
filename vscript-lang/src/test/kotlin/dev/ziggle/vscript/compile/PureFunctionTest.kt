package dev.ziggle.vscript.compile

import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.isPureFunction
import dev.ziggle.vscript.model.resolveNode
import dev.ziggle.vscript.vm.FiberState
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Functions that only compute.
 *
 * A function whose body has no steps in it is an expression, and its Call is a pure node: no exec pins,
 * re-evaluated wherever it is read. Derived from the body rather than declared, so there is nothing that
 * can disagree with what the function actually does.
 */
class PureFunctionTest {

    private val catalog = NodeCatalog()

    private fun run(g: dev.ziggle.vscript.model.Graph): List<Any?> {
        val issues = Validator(catalog).validate(g)
        assertTrue(issues.errors().isEmpty(), "$issues")
        val result = drive(GraphCompiler(catalog).compile(g, g.entries(catalog).first().id))
        assertEquals(FiberState.DONE, result.fiber.state, "${result.fiber.error}")
        return result.fiber.result
    }

    private fun pure(g: dev.ziggle.vscript.model.Graph, name: String) =
        isPureFunction(name, catalog, g.nodes, g::function, g.links)

    private fun expr(g: dev.ziggle.vscript.model.Graph) =
        dev.ziggle.vscript.model.expressionCalls(catalog, g.nodes, g.links, g::function)

    /** `Double(x) = x + x`, read straight into a Return with no exec wire anywhere near it. */
    @Test
    fun `a computing function is called from a data pin alone`() {
        val g = graph {
            function("Double", params = listOf("X" to PinType.INT), results = listOf("Y" to PinType.INT))
            val start = node(BuiltinNodes.ENTRY)
            val ret = node(BuiltinNodes.RETURN)
            val call = node(BuiltinNodes.CALL, literals = mapOf("X" to 21), callee = "Double")
            link(start, "Exec", ret, "Exec")
            link(call, "Y", ret, "Value")   // the ONLY wire to the call

            val box = node(BuiltinNodes.FUNCTION, function = "Double")
            val add = node(BuiltinNodes.ADD, function = "Double")
            link(box, "X", add, "A")
            link(box, "X", add, "B")
            link(add, "Result", box, "Y")
        }
        assertTrue(pure(g, "Double"))
        assertEquals(listOf(42), run(g))
    }

    /** The Call has no exec pins at all — not exec pins that are ignored. */
    @Test
    fun `a pure call has no exec pins`() {
        val g = graph {
            function("Double", params = listOf("X" to PinType.INT), results = listOf("Y" to PinType.INT))
            node(BuiltinNodes.CALL, callee = "Double")
            node(BuiltinNodes.FUNCTION, function = "Double")
            node(BuiltinNodes.ADD, function = "Double")
        }
        val call = resolveNode(
            g.nodes[0], catalog[BuiltinNodes.CALL]!!, g::function, { g.types }, expr(g),
        )
        assertEquals(NodeKind.PURE, call.kind)
        assertEquals(listOf("X"), call.inputs.map { it.name })
        assertEquals(listOf("Y"), call.outputs.map { it.name })

        // And the box loses them too, on both sides.
        val box = resolveNode(
            g.nodes[1], catalog[BuiltinNodes.FUNCTION]!!, g::function, { g.types }, expr(g),
        )
        assertEquals(listOf("Y"), box.inputs.map { it.name })
        assertEquals(listOf("X"), box.outputs.map { it.name })
    }

    /**
     * The shape that started this: a helper feeding a While's CONDITION.
     *
     * A condition is pulled as data with no exec chain to put a call on, so an impure Call there reads a
     * register nothing ever wrote — "expected number, got null", at the arithmetic rather than at the call.
     */
    @Test
    fun `a computing function can feed a loop condition`() {
        val g = graph {
            variable("n", PinType.INT, 0)
            function("Enough", results = listOf("Yes" to PinType.BOOL))

            val start = node(BuiltinNodes.ENTRY)
            val loop = node(BuiltinNodes.WHILE)
            val bump = node(BuiltinNodes.VAR_SET, variable = "n")
            val cur = node(BuiltinNodes.VAR_GET, variable = "n")
            val one = node(BuiltinNodes.LITERAL_INT, literals = mapOf("Value" to 1))
            val add = node(BuiltinNodes.ADD)
            val done = node(BuiltinNodes.RETURN)
            val cond = node(BuiltinNodes.CALL, callee = "Enough")
            link(start, "Exec", loop, "Exec")
            link(cond, "Yes", loop, "Condition")
            link(loop, "Body", bump, "Exec")
            link(cur, "Value", add, "A")
            link(one, "Value", add, "B")
            link(add, "Result", bump, "Value")
            link(loop, "Completed", done, "Exec")
            link(cur, "Value", done, "Value")

            // Enough() = n < 3
            val box = node(BuiltinNodes.FUNCTION, function = "Enough")
            val read = node(BuiltinNodes.VAR_GET, variable = "n", function = "Enough")
            val three = node(BuiltinNodes.LITERAL_INT, literals = mapOf("Value" to 3), function = "Enough")
            val lt = node(BuiltinNodes.LT, function = "Enough")
            link(read, "Value", lt, "A")
            link(three, "Value", lt, "B")
            link(lt, "Result", box, "Yes")
        }
        assertTrue(pure(g, "Enough"))
        assertEquals(listOf(3), run(g), "the loop should have run until n reached 3")
    }

    // ---- what is NOT pure -------------------------------------------------------------------------

    /** One step in the body and the whole function is a step. */
    @Test
    fun `a body with anything that runs is not pure`() {
        val g = graph {
            function("Noisy", results = listOf("Out" to PinType.INT))
            node(BuiltinNodes.FUNCTION, function = "Noisy")
            node(BuiltinNodes.LOG, literals = mapOf("Message" to "hi"), function = "Noisy")
        }
        assertFalse(pure(g, "Noisy"))
    }

    /**
     * No results, no purity, however little it does.
     *
     * There would be nothing to pull on — the Call could neither be reached nor read, so it would silently
     * never run, which is the exact bug the exec pins are there to prevent.
     */
    @Test
    fun `a function that returns nothing is never pure`() {
        val g = graph {
            function("Nothing")
            node(BuiltinNodes.FUNCTION, function = "Nothing")
        }
        assertFalse(pure(g, "Nothing"))
    }

    /** Purity travels: a computing function that calls another one is still an expression. */
    @Test
    fun `purity is transitive`() {
        val g = graph {
            function("Inner", results = listOf("V" to PinType.INT))
            function("Outer", results = listOf("V" to PinType.INT))
            val ib = node(BuiltinNodes.FUNCTION, function = "Inner")
            val lit = node(BuiltinNodes.LITERAL_INT, literals = mapOf("Value" to 5), function = "Inner")
            link(lit, "Value", ib, "V")

            val ob = node(BuiltinNodes.FUNCTION, function = "Outer")
            val call = node(BuiltinNodes.CALL, callee = "Inner", function = "Outer")
            link(call, "V", ob, "V")
        }
        assertTrue(pure(g, "Inner"))
        assertTrue(pure(g, "Outer"))
    }

    /** And it stops travelling the moment something in the chain runs. */
    @Test
    fun `a computing function that calls a step is a step`() {
        val g = graph {
            function("Inner", results = listOf("V" to PinType.INT))
            function("Outer", results = listOf("V" to PinType.INT))
            node(BuiltinNodes.FUNCTION, function = "Inner")
            node(BuiltinNodes.LOG, literals = mapOf("Message" to "x"), function = "Inner")

            val ob = node(BuiltinNodes.FUNCTION, function = "Outer")
            val call = node(BuiltinNodes.CALL, callee = "Inner", function = "Outer")
            link(call, "V", ob, "V")
        }
        assertFalse(pure(g, "Inner"))
        assertFalse(pure(g, "Outer"))
    }

    /** An impure function's Call keeps its exec pins, and reading it unreached is still refused. */
    @Test
    fun `an impure call still needs to be run`() {
        val g = graph {
            function("Noisy", results = listOf("Out" to PinType.INT))
            val start = node(BuiltinNodes.ENTRY)
            val ret = node(BuiltinNodes.RETURN)
            val call = node(BuiltinNodes.CALL, callee = "Noisy")
            link(start, "Exec", ret, "Exec")
            link(call, "Out", ret, "Value")

            val box = node(BuiltinNodes.FUNCTION, function = "Noisy")
            val log = node(BuiltinNodes.LOG, literals = mapOf("Message" to "hi"), function = "Noisy")
            link(box, "Exec", log, "Exec")
            link(log, "Exec", box, "Exec")
        }
        assertFalse(pure(g, "Noisy"))
        assertTrue(Validator(catalog).validate(g).any { "nothing runs" in it.message })
    }

    /** A body with nothing in it is unfinished, not an expression. */
    @Test
    fun `an empty body is not an expression`() {
        val g = graph {
            function("Later", results = listOf("V" to PinType.INT))
            node(BuiltinNodes.FUNCTION, function = "Later")
        }
        assertFalse(pure(g, "Later"))
    }

    /**
     * A Call already wired into the exec chain stays a step, however little its function does.
     *
     * Otherwise detecting purity would delete the exec pins under every call site that had them — a graph
     * rewrite dressed up as a detection. It is also the opt-out worth having: an expression is
     * re-evaluated at every read, so an expensive body is sometimes better run once and cached.
     */
    @Test
    fun `a call already in the exec chain keeps its exec pins`() {
        val g = graph {
            function("Double", params = listOf("X" to PinType.INT), results = listOf("Y" to PinType.INT))
            val start = node(BuiltinNodes.ENTRY)
            val call = node(BuiltinNodes.CALL, literals = mapOf("X" to 21), callee = "Double")
            val ret = node(BuiltinNodes.RETURN)
            link(start, "Exec", call, "Exec")
            link(call, "Exec", ret, "Exec")
            link(call, "Y", ret, "Value")

            val box = node(BuiltinNodes.FUNCTION, function = "Double")
            val add = node(BuiltinNodes.ADD, function = "Double")
            link(box, "X", add, "A")
            link(box, "X", add, "B")
            link(add, "Result", box, "Y")
        }
        assertTrue(pure(g, "Double"), "the function itself only computes")
        val call = g.nodes.first { it.type == BuiltinNodes.CALL }
        val d = resolveNode(call, catalog[BuiltinNodes.CALL]!!, g::function, { g.types }, expr(g))
        assertEquals(NodeKind.IMPURE, d.kind, "an exec-wired call must stay a step")
        assertTrue(d.inputs.any { it.type.isExec })
        assertEquals(listOf(42), run(g), "and it still works")
    }
}
