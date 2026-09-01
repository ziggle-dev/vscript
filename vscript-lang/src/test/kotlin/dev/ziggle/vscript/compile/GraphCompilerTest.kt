package dev.ziggle.vscript.compile

import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.vm.FiberState
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.ManualActuator
import dev.ziggle.vscript.vm.Op
import dev.ziggle.vscript.vm.TraceKind
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Graph → bytecode → execution.
 *
 * These run the compiler's output on the real VM rather than asserting on instruction listings, because
 * what matters is that a graph *does the right thing*. The few tests that do inspect opcodes are the ones
 * checking a property the result cannot show — that a blocking node became `ACT`, or that a shared tail
 * was emitted once rather than duplicated.
 */
class GraphCompilerTest {

    private val doubleNode = hostNode(
        "test.double", "double", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("A", PinType.INT, default = 0)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Result", PinType.INT)),
    )
    private val walkNode = hostNode(
        "test.walk", "walk", NodeKind.IMPURE, HostKind.BLOCKING,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("To", PinType.STRING, default = "")),
        outputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Arrived", PinType.BOOL)),
    )
    private val listNode = hostNode(
        "test.list", "makeList", NodeKind.PURE,
        outputs = listOf(PinSpec("Value", PinType.LIST)),
    )
    private val counterNode = hostNode(
        "test.counter", "counter", NodeKind.PURE,
        outputs = listOf(PinSpec("Value", PinType.INT)),
    )

    private val catalog = NodeCatalog(listOf(doubleNode, walkNode, listNode, counterNode))

    private fun hosts(vararg extra: Pair<String, () -> Any?>): HostRegistry {
        val r = HostRegistry()
        r.register("double", HostKind.INLINE, arity = 1) { args -> (args[0] as Int) * 2 }
        r.register("walk", HostKind.BLOCKING, arity = 1) { true }
        r.register("makeList", HostKind.INLINE) { mutableListOf<Any?>(1, 2, 3) }
        extra.forEach { (name, fn) -> r.register(name, HostKind.INLINE) { fn() } }
        return r
    }

    @Test
    fun `entry to host call to return`() {
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val dbl = node("test.double", literals = mapOf("A" to 21))
            val ret = node(BuiltinNodes.RETURN)
            link(entry, "Exec", dbl, "Exec")
            link(dbl, "Exec", ret, "Exec")
            link(dbl, "Result", ret, "Value")
        }
        val chunk = GraphCompiler(catalog).compile(g, 1)
        assertEquals(42, drive(chunk, hosts()).value())
    }

    @Test
    fun `every literal node lowers to the same constant`() {
        // Typed and untyped literals differ only in what the editor will let you connect them to. By the
        // time the compiler sees one, a literal is a constant — so all of them take the same path, and a
        // new typed literal cannot quietly fall through to the host-call branch and fail at load.
        fun value(type: String, v: Any?): Any? {
            val g = graph {
                val entry = node(BuiltinNodes.ENTRY)
                val lit = node(type, literals = mapOf("Value" to v))
                val ret = node(BuiltinNodes.RETURN)
                link(entry, "Exec", ret, "Exec")
                link(lit, "Value", ret, "Value")
            }
            return drive(GraphCompiler(catalog).compile(g, 1), hosts()).value()
        }

        assertEquals(7, value(BuiltinNodes.LITERAL_INT, 7))
        assertEquals(1.5, value(BuiltinNodes.LITERAL_FLOAT, 1.5))
        assertEquals("hi", value(BuiltinNodes.LITERAL_STRING, "hi"))
        assertEquals(true, value(BuiltinNodes.LITERAL_BOOL, true))
        assertEquals(3, value(BuiltinNodes.LITERAL, 3))
    }

    @Test
    fun `a literal with nothing typed falls back to its pin default`() {
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val lit = node(BuiltinNodes.LITERAL_STRING)
            val ret = node(BuiltinNodes.RETURN)
            link(entry, "Exec", ret, "Exec")
            link(lit, "Value", ret, "Value")
        }
        assertEquals("", drive(GraphCompiler(catalog).compile(g, 1), hosts()).value())
    }

    @Test
    fun `branch takes only the matching path`() {
        fun run(condition: Boolean): Any? {
            val g = graph {
                val entry = node(BuiltinNodes.ENTRY)
                val br = node(BuiltinNodes.BRANCH, literals = mapOf("Condition" to condition))
                val yes = node(BuiltinNodes.RETURN, literals = mapOf("Value" to "yes"))
                val no = node(BuiltinNodes.RETURN, literals = mapOf("Value" to "no"))
                link(entry, "Exec", br, "Exec")
                link(br, "True", yes, "Exec")
                link(br, "False", no, "Exec")
            }
            return drive(GraphCompiler(catalog).compile(g, 1), hosts()).value()
        }
        assertEquals("yes", run(true))
        assertEquals("no", run(false))
    }

    @Test
    fun `a while loop over a graph variable terminates with the right count`() {
        val g = graph {
            variable("i", PinType.INT, 0)
            val entry = node(BuiltinNodes.ENTRY)
            val loop = node(BuiltinNodes.WHILE)
            val lt = node(BuiltinNodes.LT, literals = mapOf("B" to 3))
            val getI = node(BuiltinNodes.VAR_GET, variable = "i")
            val setI = node(BuiltinNodes.VAR_SET, variable = "i")
            val add = node(BuiltinNodes.ADD, literals = mapOf("B" to 1))
            val getI2 = node(BuiltinNodes.VAR_GET, variable = "i")
            val ret = node(BuiltinNodes.RETURN)
            val getI3 = node(BuiltinNodes.VAR_GET, variable = "i")

            link(entry, "Exec", loop, "Exec")
            link(getI, "Value", lt, "A")
            link(lt, "Result", loop, "Condition")
            link(loop, "Body", setI, "Exec")
            link(getI2, "Value", add, "A")
            link(add, "Result", setI, "Value")
            link(loop, "Completed", ret, "Exec")
            link(getI3, "Value", ret, "Value")
        }
        assertEquals(3, drive(GraphCompiler(catalog).compile(g, 1), hosts()).value())
    }

    @Test
    fun `for each visits every element`() {
        val g = graph {
            variable("sum", PinType.INT, 0)
            val entry = node(BuiltinNodes.ENTRY)
            val each = node(BuiltinNodes.FOR_EACH)
            val list = node("test.list")
            val setSum = node(BuiltinNodes.VAR_SET, variable = "sum")
            val add = node(BuiltinNodes.ADD)
            val getSum = node(BuiltinNodes.VAR_GET, variable = "sum")
            val ret = node(BuiltinNodes.RETURN)
            val getSum2 = node(BuiltinNodes.VAR_GET, variable = "sum")

            link(entry, "Exec", each, "Exec")
            link(list, "Value", each, "List")
            link(each, "Body", setSum, "Exec")
            link(getSum, "Value", add, "A")
            link(each, "Element", add, "B")
            link(add, "Result", setSum, "Value")
            link(each, "Completed", ret, "Exec")
            link(getSum2, "Value", ret, "Value")
        }
        assertEquals(6, drive(GraphCompiler(catalog).compile(g, 1), hosts()).value())
    }

    /** The slots are ordinary input pins, so each one can be typed into OR wired — this is the typed half. */
    @Test
    fun `a list literal builds its slots in order`() {
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val list = node(
                BuiltinNodes.LITERAL_LIST,
                literals = mapOf(
                    BuiltinNodes.LIST_OF to "Item",
                    BuiltinNodes.LIST_COUNT to 3,
                    "1" to 1951, "2" to 1963, "3" to 2108,
                ),
            )
            val ret = node(BuiltinNodes.RETURN)
            link(entry, "Exec", ret, "Exec")
            link(list, "Value", ret, "Value")
        }
        assertEquals(listOf(1951, 1963, 2108), drive(GraphCompiler(catalog).compile(g, 1), hosts()).value())
    }

    /** ...and this is the wired half, which is the whole reason the slots are pins rather than a grid. */
    @Test
    fun `a list slot can be fed by a wire`() {
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val lit = node(BuiltinNodes.LITERAL_INT, literals = mapOf("Value" to 99))
            val list = node(
                BuiltinNodes.LITERAL_LIST,
                literals = mapOf(BuiltinNodes.LIST_COUNT to 2, "1" to 7),
            )
            val ret = node(BuiltinNodes.RETURN)
            link(entry, "Exec", ret, "Exec")
            link(lit, "Value", list, "2")
            link(list, "Value", ret, "Value")
        }
        assertEquals(listOf(7, 99), drive(GraphCompiler(catalog).compile(g, 1), hosts()).value())
    }

    /**
     * A pure node is re-expanded at every use site, so a list that was one shared object would accumulate
     * across evaluations — and being a `CONST` in the pool is exactly how that would happen.
     */
    @Test
    fun `each evaluation of a list literal builds a fresh list`() {
        val g = graph {
            variable("total", PinType.INT, 0)
            val entry = node(BuiltinNodes.ENTRY)
            val each = node(BuiltinNodes.FOR_EACH)
            // The SAME list node read on every iteration of an outer loop would grow if it were shared.
            val list = node(BuiltinNodes.LITERAL_LIST, literals = mapOf(BuiltinNodes.LIST_COUNT to 2, "1" to 1, "2" to 2))
            val setTotal = node(BuiltinNodes.VAR_SET, variable = "total")
            val add = node(BuiltinNodes.ADD)
            val getTotal = node(BuiltinNodes.VAR_GET, variable = "total")
            val ret = node(BuiltinNodes.RETURN)
            val getTotal2 = node(BuiltinNodes.VAR_GET, variable = "total")

            link(entry, "Exec", each, "Exec")
            link(list, "Value", each, "List")
            link(each, "Body", setTotal, "Exec")
            link(getTotal, "Value", add, "A")
            link(each, "Element", add, "B")
            link(add, "Result", setTotal, "Value")
            link(each, "Completed", ret, "Exec")
            link(getTotal2, "Value", ret, "Value")
        }
        val chunk = GraphCompiler(catalog).compile(g, 1)
        assertEquals(3, drive(chunk, hosts()).value())
        // The list is built, not fetched: a CONST of a prebuilt list would show up as neither of these.
        assertEquals(1, chunk.countOp(Op.NEWLIST))
        assertEquals(2, chunk.countOp(Op.APPEND))
    }

    /**
     * A pure node's result is observable, which is the only way a wire leaving one can be labelled.
     *
     * It has no stable register — it is re-expanded into scratch at every use site and released straight
     * after — so the inspector finds nothing for it at a pause. That is why the value pills appeared only
     * on impure outputs: in a real graph that is a handful of wires and every literal, comparison, template
     * and query drew nothing.
     */
    @Test
    fun `a pure node reports the value it produced`() {
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val a = node(BuiltinNodes.LITERAL_INT, literals = mapOf("Value" to 20))
            val b = node(BuiltinNodes.LITERAL_INT, literals = mapOf("Value" to 22))
            val sum = node(BuiltinNodes.ADD)
            val ret = node(BuiltinNodes.RETURN)
            link(entry, "Exec", ret, "Exec")
            link(a, "Value", sum, "A")
            link(b, "Value", sum, "B")
            link(sum, "Result", ret, "Value")
        }
        val seen = HashMap<Int, Any?>()
        val chunk = GraphCompiler(catalog).compile(g, 1)
        val clock = dev.ziggle.vscript.vm.FakeClock()
        val interpreter = dev.ziggle.vscript.vm.Interpreter(
            hosts(), clock,
            tracer = { id, kind, value -> if (kind == TraceKind.PURE_EXIT) seen[id] = value },
        )
        interpreter.resetGlobals(chunk.globals)
        val scheduler = dev.ziggle.vscript.vm.Scheduler(interpreter, clock)
        val fiber = scheduler.spawn("t", chunk)
        var n = 0
        while (!fiber.isFinished && n++ < 100) scheduler.tick()

        assertEquals(20, seen[2], "the first literal")
        assertEquals(22, seen[3], "the second")
        assertEquals(42, seen[4], "and the sum that consumed them")
    }

    /** The exit marker must not become a stepping stop, or stepping would halt mid-expression. */
    @Test
    fun `a pure exit is not a node boundary`() {
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val lit = node(BuiltinNodes.LITERAL_INT, literals = mapOf("Value" to 1))
            val ret = node(BuiltinNodes.RETURN)
            link(entry, "Exec", ret, "Exec")
            link(lit, "Value", ret, "Value")
        }
        val chunk = GraphCompiler(catalog).compile(g, 1)
        // Both markers are emitted for the literal, but only the ENTER one carries its node as current.
        assertEquals(1, chunk.countOp(Op.TRACE, a = 2, b = TraceKind.PURE_ENTER))
        assertEquals(1, chunk.countOp(Op.TRACE, a = 2, b = TraceKind.PURE_EXIT))
        val r = drive(chunk, hosts())
        assertEquals(1, r.value())
    }

    @Test
    fun `select yields one arm or the other`() {
        fun run(cond: Boolean): Any? {
            val g = graph {
                val entry = node(BuiltinNodes.ENTRY)
                val sel = node(
                    BuiltinNodes.SELECT,
                    literals = mapOf("Condition" to cond, "If True" to "yes", "If False" to "no"),
                )
                val ret = node(BuiltinNodes.RETURN)
                link(entry, "Exec", ret, "Exec")
                link(sel, "Value", ret, "Value")
            }
            return drive(GraphCompiler(catalog).compile(g, 1), hosts()).value()
        }
        assertEquals("yes", run(true))
        assertEquals("no", run(false))
    }

    /**
     * The arm not taken is never evaluated — the difference from AND/OR, which must evaluate both.
     *
     * Counted through a host function, because "it was not evaluated" is not visible in the result. A Select
     * that ran both arms would give the same answer and quietly do twice the work, including the whole tree
     * of pure nodes feeding the side that was thrown away.
     */
    @Test
    fun `select does not evaluate the arm it did not take`() {
        var trueArm = 0
        var falseArm = 0
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val a = node("test.counter")
            val b = node("test.counter2")
            val sel = node(BuiltinNodes.SELECT, literals = mapOf("Condition" to true))
            val ret = node(BuiltinNodes.RETURN)
            link(entry, "Exec", ret, "Exec")
            link(a, "Value", sel, "If True")
            link(b, "Value", sel, "If False")
            link(sel, "Value", ret, "Value")
        }
        val hosts = HostRegistry()
        hosts.register("counter", HostKind.INLINE, arity = 0, results = 1) { trueArm++; 1 }
        hosts.register("counter2", HostKind.INLINE, arity = 0, results = 1) { falseArm++; 2 }
        val cat = NodeCatalog(
            listOf(
                hostNode("test.counter", "counter", NodeKind.PURE, outputs = listOf(PinSpec("Value", PinType.INT))),
                hostNode("test.counter2", "counter2", NodeKind.PURE, outputs = listOf(PinSpec("Value", PinType.INT))),
            )
        )
        assertEquals(1, drive(GraphCompiler(cat).compile(g, 1), hosts).value())
        assertEquals(1, trueArm, "the taken arm runs once")
        assertEquals(0, falseArm, "the other arm must not run at all")
    }

    /** The operators over a hand-written list, which is the pairing an author will actually build. */
    @Test
    fun `list operators read a literal list`() {
        fun op(type: String, extra: Map<String, Any?> = emptyMap()): Any? {
            val g = graph {
                val entry = node(BuiltinNodes.ENTRY)
                val list = node(
                    BuiltinNodes.LITERAL_LIST,
                    literals = mapOf(BuiltinNodes.LIST_COUNT to 3, "1" to 10, "2" to 20, "3" to 30),
                )
                val o = node(type, literals = extra)
                val ret = node(BuiltinNodes.RETURN)
                link(entry, "Exec", ret, "Exec")
                link(list, "Value", o, "List")
                link(o, catalog[type]!!.dataOutputs.first().name, ret, "Value")
            }
            return drive(GraphCompiler(catalog).compile(g, 1), listHosts()).value()
        }

        assertEquals(3, op(BuiltinNodes.LIST_COUNT_OF))
        assertEquals(10, op(BuiltinNodes.LIST_FIRST))
        assertEquals(false, op(BuiltinNodes.LIST_IS_EMPTY))
        assertEquals(20, op(BuiltinNodes.LIST_AT, mapOf("Index" to 1)))
        assertEquals(true, op(BuiltinNodes.LIST_CONTAINS, mapOf("Value" to 30)))
        assertEquals(false, op(BuiltinNodes.LIST_CONTAINS, mapOf("Value" to 99)))
    }

    /**
     * An index names a position the author claims is there, so being wrong is worth stopping on. "Whatever
     * is there, if anything" is a different question and First is the node that answers it.
     */
    @Test
    fun `an index past the end stops the fiber, but First just reports nothing`() {
        fun run(type: String, index: Int?): DriveOutcome {
            val g = graph {
                val entry = node(BuiltinNodes.ENTRY)
                val list = node(BuiltinNodes.LITERAL_LIST)
                val o = node(type, literals = index?.let { mapOf("Index" to it) } ?: emptyMap())
                val ret = node(BuiltinNodes.RETURN)
                link(entry, "Exec", ret, "Exec")
                link(list, "Value", o, "List")
                link(o, catalog[type]!!.dataOutputs.first().name, ret, "Value")
            }
            val r = drive(GraphCompiler(catalog).compile(g, 1), listHosts())
            return DriveOutcome(r.fiber.state, r.fiber.error?.message, r.fiber.result.firstOrNull())
        }

        val at = run(BuiltinNodes.LIST_AT, 0)
        assertEquals(FiberState.FAILED, at.state)
        assertContains(at.error!!, "out of bounds")

        val first = run(BuiltinNodes.LIST_FIRST, null)
        assertEquals(FiberState.DONE, first.state)
        assertEquals(null, first.value)
    }

    private class DriveOutcome(val state: FiberState, val error: String?, val value: Any?)

    /** The language's own host functions — the same ones the client installs. See [BuiltinHosts]. */
    private fun listHosts(): HostRegistry =
        dev.ziggle.vscript.nodes.BuiltinHosts.registry().also { r ->
            r.register("double", HostKind.INLINE, arity = 1) { args -> (args[0] as Int) * 2 }
        }

    @Test
    fun `an empty list literal is still a list`() {
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val list = node(BuiltinNodes.LITERAL_LIST)
            val ret = node(BuiltinNodes.RETURN)
            link(entry, "Exec", ret, "Exec")
            link(list, "Value", ret, "Value")
        }
        assertEquals(emptyList<Any?>(), drive(GraphCompiler(catalog).compile(g, 1), hosts()).value())
    }

    @Test
    fun `a blocking node compiles to ACT and suspends on the actuator`() {
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val walk = node("test.walk", literals = mapOf("To" to "bank"))
            val ret = node(BuiltinNodes.RETURN)
            link(entry, "Exec", walk, "Exec")
            link(walk, "Exec", ret, "Exec")
            link(walk, "Arrived", ret, "Value")
        }
        val chunk = GraphCompiler(catalog).compile(g, 1)

        assertTrue(Op.ACT in chunk.opcodes(), "a BLOCKING host node must not compile to CALL")
        assertTrue(Op.CALL !in chunk.opcodes())

        // And it really does go through the drain rather than running on the client thread.
        val actuator = ManualActuator()
        val r = drive(chunk, hosts(), actuator = actuator, maxTicks = 5)
        assertEquals(FiberState.AWAITING_ACT, r.fiber.state)
        actuator.drain()
        val r2 = drive(chunk, hosts())
        assertEquals(true, r2.value())
    }

    @Test
    fun `a delay compiles to SLEEP and parks rather than blocking`() {
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val delay = node(BuiltinNodes.DELAY, literals = mapOf("Ms" to 750))
            val ret = node(BuiltinNodes.RETURN, literals = mapOf("Value" to "done"))
            link(entry, "Exec", delay, "Exec")
            link(delay, "Exec", ret, "Exec")
        }
        val chunk = GraphCompiler(catalog).compile(g, 1)
        assertTrue(Op.SLEEP in chunk.opcodes())

        val r = drive(chunk, hosts())
        assertEquals("done", r.value())
        assertTrue(r.ticks > 1, "a Delay must park across ticks, not spin")
    }

    @Test
    fun `converging branches compile the shared tail exactly once`() {
        // The memoisation rule earns its keep here: without it each branch would inline the tail, and a
        // chain of N branches would emit 2^N copies of the code after them.
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val br = node(BuiltinNodes.BRANCH, literals = mapOf("Condition" to true))
            val tail = node("test.double", literals = mapOf("A" to 5))
            val ret = node(BuiltinNodes.RETURN)
            link(entry, "Exec", br, "Exec")
            link(br, "True", tail, "Exec")
            link(br, "False", tail, "Exec")
            link(tail, "Exec", ret, "Exec")
            link(tail, "Result", ret, "Value")
        }
        val chunk = GraphCompiler(catalog).compile(g, 1)

        assertEquals(
            1,
            chunk.countOp(Op.TRACE, a = 3, b = TraceKind.NODE_ENTER),
            "the shared tail node should be emitted once",
        )
        assertEquals(10, drive(chunk, hosts()).value())
    }

    @Test
    fun `a pure node is re-evaluated at every use site`() {
        // Blueprints semantics: a pure node has no cached output, so wiring it into two inputs evaluates
        // it twice. Authors rely on this for things like "current hp" that must be fresh at each read.
        var calls = 0
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val add = node(BuiltinNodes.ADD)
            val counter = node("test.counter")
            val ret = node(BuiltinNodes.RETURN)
            link(entry, "Exec", ret, "Exec")
            link(counter, "Value", add, "A")
            link(counter, "Value", add, "B")
            link(add, "Result", ret, "Value")
        }
        val chunk = GraphCompiler(catalog).compile(g, 1)
        val result = drive(chunk, hosts("counter" to { ++calls })).value()

        assertEquals(2, calls, "the pure node should have been evaluated once per use")
        assertEquals(3, result, "1 + 2 — each evaluation sees a fresh value")
    }

    @Test
    fun `an impure node's output is cached, not recomputed`() {
        var calls = 0
        val hosts = HostRegistry()
            .register("double", HostKind.INLINE, arity = 1) { args -> calls++; (args[0] as Int) * 2 }
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val dbl = node("test.double", literals = mapOf("A" to 4))
            val add = node(BuiltinNodes.ADD)
            val ret = node(BuiltinNodes.RETURN)
            link(entry, "Exec", dbl, "Exec")
            link(dbl, "Exec", ret, "Exec")
            link(dbl, "Result", add, "A")
            link(dbl, "Result", add, "B")
            link(add, "Result", ret, "Value")
        }
        val result = drive(GraphCompiler(catalog).compile(g, 1), hosts).value()

        assertEquals(1, calls, "an impure node runs once in exec order however many wires read it")
        assertEquals(16, result)
    }

    @Test
    fun `a sequence runs each output in order`() {
        val g = graph {
            variable("log", PinType.STRING, "")
            val entry = node(BuiltinNodes.ENTRY)
            val seq = node(BuiltinNodes.SEQUENCE)
            val a = node(BuiltinNodes.VAR_SET, variable = "log")
            val addA = node(BuiltinNodes.ADD, literals = mapOf("B" to "a"))
            val getA = node(BuiltinNodes.VAR_GET, variable = "log")
            val bNode = node(BuiltinNodes.VAR_SET, variable = "log")
            val addB = node(BuiltinNodes.ADD, literals = mapOf("B" to "b"))
            val getB = node(BuiltinNodes.VAR_GET, variable = "log")
            val ret = node(BuiltinNodes.RETURN)
            val getC = node(BuiltinNodes.VAR_GET, variable = "log")

            link(entry, "Exec", seq, "Exec")
            link(seq, "Then0", a, "Exec")
            link(getA, "Value", addA, "A")
            link(addA, "Result", a, "Value")
            link(seq, "Then1", bNode, "Exec")
            link(getB, "Value", addB, "A")
            link(addB, "Result", bNode, "Value")
            link(seq, "Then2", ret, "Exec")
            link(getC, "Value", ret, "Value")
        }
        assertEquals("ab", drive(GraphCompiler(catalog).compile(g, 1), hosts()).value())
    }

    @Test
    fun `an entry chain that just ends halts cleanly`() {
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val dbl = node("test.double", literals = mapOf("A" to 1))
            link(entry, "Exec", dbl, "Exec")
        }
        val chunk = GraphCompiler(catalog).compile(g, 1)
        assertTrue(Op.HALT in chunk.opcodes(), "a chain with no Return must still terminate")
        val r = drive(chunk, hosts())
        assertEquals(FiberState.DONE, r.fiber.state)
    }

    @Test
    fun `compiling a graph with errors reports them instead of emitting bad bytecode`() {
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val ret = node(BuiltinNodes.RETURN)
            link(entry, "Exec", ret, "Exec")
            link(entry, "Exec", ret, "Exec") // an exec output driving two nodes
        }
        val e = runCatching { GraphCompiler(catalog).compile(g, 1) }.exceptionOrNull()
        assertTrue(e is GraphCompileException)
        assertContains(e.message!!, "drives 2 nodes")
    }

    @Test
    fun `debug off emits no TRACE instructions`() {
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val dbl = node("test.double", literals = mapOf("A" to 3))
            val ret = node(BuiltinNodes.RETURN)
            link(entry, "Exec", dbl, "Exec")
            link(dbl, "Exec", ret, "Exec")
            link(dbl, "Result", ret, "Value")
        }
        val release = GraphCompiler(catalog, debug = false).compile(g, 1)
        val debug = GraphCompiler(catalog, debug = true).compile(g, 1)

        assertEquals(0, release.countOp(Op.TRACE))
        assertTrue(debug.countOp(Op.TRACE) > 0)
        assertEquals(6, drive(release, hosts()).value(), "behaviour must be identical either way")
        assertEquals(6, drive(debug, hosts()).value())
    }

    @Test
    fun `every node carries its id into the bytecode for error reporting`() {
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val dbl = node("test.double", literals = mapOf("A" to 2))
            link(entry, "Exec", dbl, "Exec")
        }
        val chunk = GraphCompiler(catalog).compile(g, 1)
        // node 2 is the double node; its emitted instructions must be attributed to it.
        assertTrue((0 until chunk.size).any { chunk.nodeIdAt(it) == 2 })
        assertEquals(1, chunk.countOp(Op.TRACE, a = 2, b = TraceKind.NODE_ENTER))
        assertEquals(TraceKind.NODE_ENTER, chunk.b((0 until chunk.size).first { chunk.op(it) == Op.TRACE && chunk.a(it) == 2 }))
    }

    @Test
    fun `compileAll produces one chunk per entry node`() {
        val g = graph {
            val e1 = node(BuiltinNodes.ENTRY)
            val e2 = node(BuiltinNodes.ENTRY)
            val r1 = node(BuiltinNodes.RETURN, literals = mapOf("Value" to "one"))
            val r2 = node(BuiltinNodes.RETURN, literals = mapOf("Value" to "two"))
            link(e1, "Exec", r1, "Exec")
            link(e2, "Exec", r2, "Exec")
        }
        val chunks = GraphCompiler(catalog).compileAll(g)
        assertEquals(setOf(1, 2), chunks.keys)
        assertEquals("one", drive(chunks.getValue(1), hosts()).value())
        assertEquals("two", drive(chunks.getValue(2), hosts()).value())
    }

    /**
     * And / Or, which a loop condition made of two tests needs and the catalogue had no way to express.
     *
     * Both sides always evaluate — they lower a PURE node, whose inputs are expressions with nothing to
     * skip. Short-circuiting belongs to Branch, which has somewhere for the skipped work not to happen.
     */
    @Test
    fun `and and or combine conditions`() {
        fun run(type: String, a: Boolean, b: Boolean): Any? {
            val g = graph {
                val start = node(BuiltinNodes.ENTRY)
                val ret = node(BuiltinNodes.RETURN)
                val la = node(BuiltinNodes.LITERAL_BOOL, literals = mapOf("Value" to a))
                val lb = node(BuiltinNodes.LITERAL_BOOL, literals = mapOf("Value" to b))
                val op = node(type)
                link(start, "Exec", ret, "Exec")
                link(la, "Value", op, "A")
                link(lb, "Value", op, "B")
                link(op, "Result", ret, "Value")
            }
            val catalog = NodeCatalog()
            return drive(GraphCompiler(catalog).compile(g, g.entries(catalog).first().id)).fiber.result.first()
        }
        assertEquals(true, run(BuiltinNodes.AND, true, true))
        assertEquals(false, run(BuiltinNodes.AND, true, false))
        assertEquals(true, run(BuiltinNodes.OR, false, true))
        assertEquals(false, run(BuiltinNodes.OR, false, false))
    }
}
