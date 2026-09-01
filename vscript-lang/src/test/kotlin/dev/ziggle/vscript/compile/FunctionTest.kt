package dev.ziggle.vscript.compile

import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.vm.FiberState
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * User functions: a named body, its own signature, called from anywhere.
 *
 * These run on the real VM rather than inspecting bytecode, because the thing worth proving is that a call
 * genuinely pushes a frame, receives its arguments and hands a result back — not that a particular opcode
 * appeared.
 */
class FunctionTest {

    private val catalog = NodeCatalog()

    /**
     * `Double(x) = x + x`, called with 21.
     *
     * The main graph returns what the call produced, so the fiber's own result is the whole assertion.
     */
    @Test
    fun `a function receives its argument and returns a result`() {
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
            link(box, "Exec", box, "Exec")   // body: straight from the boundary back to it
            link(box, "X", add, "A")
            link(box, "X", add, "B")
            link(add, "Result", box, "Y")
        }

        assertTrue(Validator(catalog).validate(g).errors().isEmpty(), "${Validator(catalog).validate(g)}")
        val chunk = GraphCompiler(catalog).compile(g, g.entries(catalog).first().id)
        val result = drive(chunk)
        assertEquals(FiberState.DONE, result.fiber.state, "${result.fiber.error}")
        assertEquals(listOf<Any?>(42), result.fiber.result)
    }

    /**
     * A function run on its own, with no Call site and no entry node — what `graph call` does.
     *
     * The point is that it needs neither. A function that can only be exercised through the script around it
     * can only be debugged through the script around it, which is how "Hello, null" cost a live run instead
     * of ten seconds.
     */
    @Test
    fun `a function can be compiled and run without a caller`() {
        val g = graph {
            function("Double", params = listOf("X" to PinType.INT), results = listOf("Y" to PinType.INT))
            // No ENTRY node anywhere: nothing in this document starts a fiber.
            val box = node(BuiltinNodes.FUNCTION, function = "Double")
            val add = node(BuiltinNodes.ADD, function = "Double")
            link(box, "Exec", box, "Exec")
            link(box, "X", add, "A")
            link(box, "X", add, "B")
            link(add, "Result", box, "Y")
        }

        val chunk = GraphCompiler(catalog).compileFunction(g, "Double")
        assertEquals(1, chunk.paramCount, "the parameter has to arrive as an argument")

        val result = drive(chunk, args = listOf(21))
        assertEquals(FiberState.DONE, result.fiber.state, "${result.fiber.error}")
        assertEquals(listOf<Any?>(42), result.fiber.result)
    }

    @Test
    fun `calling a function that does not exist says so`() {
        val g = graph { node(BuiltinNodes.ENTRY) }
        val e = kotlin.test.assertFailsWith<IllegalStateException> {
            GraphCompiler(catalog).compileFunction(g, "Nope")
        }
        assertTrue(e.message!!.contains("Nope"), "${e.message}")
    }

    /** Called twice with different arguments — the body is shared, the frames are not. */
    @Test
    fun `one body serves many call sites`() {
        val g = graph {
            function("Double", params = listOf("X" to PinType.INT), results = listOf("Y" to PinType.INT))

            val start = node(BuiltinNodes.ENTRY)
            val first = node(BuiltinNodes.CALL, literals = mapOf("X" to 3), callee = "Double")
            val second = node(BuiltinNodes.CALL, literals = mapOf("X" to 10), callee = "Double")
            val sum = node(BuiltinNodes.ADD)
            val ret = node(BuiltinNodes.RETURN)
            link(start, "Exec", first, "Exec")
            link(first, "Exec", second, "Exec")
            link(second, "Exec", ret, "Exec")
            link(first, "Y", sum, "A")
            link(second, "Y", sum, "B")
            link(sum, "Result", ret, "Value")

            val box = node(BuiltinNodes.FUNCTION, function = "Double")
            val add = node(BuiltinNodes.ADD, function = "Double")
            link(box, "Exec", box, "Exec")   // body: straight from the boundary back to it
            link(box, "X", add, "A")
            link(box, "X", add, "B")
            link(add, "Result", box, "Y")
        }

        val chunk = GraphCompiler(catalog).compile(g, g.entries(catalog).first().id)
        val result = drive(chunk)
        assertEquals(listOf<Any?>(26), result.fiber.result) // 3+3 then 10+10
    }

    /** A body that just ends returns to its caller. Halting there would stop the whole fiber. */
    @Test
    fun `falling off the end of a body returns rather than halting`() {
        val g = graph {
            function("Nothing")
            val start = node(BuiltinNodes.ENTRY)
            val call = node(BuiltinNodes.CALL, callee = "Nothing")
            val ret = node(BuiltinNodes.RETURN, literals = mapOf("Value" to 7))
            link(start, "Exec", call, "Exec")
            link(call, "Exec", ret, "Exec")

            node(BuiltinNodes.FUNCTION, function = "Nothing")
        }

        val chunk = GraphCompiler(catalog).compile(g, g.entries(catalog).first().id)
        val result = drive(chunk)
        assertEquals(FiberState.DONE, result.fiber.state, "${result.fiber.error}")
        // Reached the Return AFTER the call — so the call came back rather than ending the fiber.
        assertEquals(listOf<Any?>(7), result.fiber.result)
    }

    /** A function box is not a fiber entry, or calling it would also start it on its own. */
    @Test
    fun `a function box does not start a fiber`() {
        val g = graph {
            function("Helper")
            node(BuiltinNodes.ENTRY)
            node(BuiltinNodes.FUNCTION, function = "Helper")
        }
        assertEquals(1, g.entries(catalog).size)
    }

    // ---- what the validator refuses ---------------------------------------------------------------

    private fun errorsOf(g: dev.ziggle.vscript.model.Graph) =
        Validator(catalog).validate(g).errors().map { it.message }

    @Test
    fun `a call naming no function is refused`() {
        val g = graph {
            val start = node(BuiltinNodes.ENTRY)
            val call = node(BuiltinNodes.CALL)
            link(start, "Exec", call, "Exec")
        }
        assertTrue(errorsOf(g).any { it.contains("names no function") }, "${errorsOf(g)}")
    }

    @Test
    fun `a function with no Inputs node is refused`() {
        val g = graph { function("Empty") }
        assertTrue(errorsOf(g).any { it.contains("no box") }, "${errorsOf(g)}")
    }

    /**
     * Direct recursion is ALLOWED — see `RecursionTest`.
     *
     * It used to be refused, because a callee's body is compiled into the caller's chunk and a self-call
     * would have expanded forever. The compiler now reserves the function's own sub-chunk slot before
     * emitting its body, so the call has an index to point at and the body is compiled once; the VM's
     * frame-depth cap handles one that never bottoms out.
     */
    @Test
    fun `a function that calls itself is allowed`() {
        val g = graph {
            function("Loop")
            node(BuiltinNodes.FUNCTION, function = "Loop")
            node(BuiltinNodes.CALL, function = "Loop", callee = "Loop")
        }
        assertTrue(errorsOf(g).none { it.contains("calls itself") }, "${errorsOf(g)}")
    }

    /**
     * Mutual recursion is ACCEPTED, since the linker.
     *
     * It used to be refused because a callee's chunk was nested inside its caller's: A's would have had to
     * contain B's, which contains A's, and neither object exists while the other is being built. Functions
     * are now compiled once into a flat program and named by index, and an index is reserved *before* the
     * body is compiled — so B's call back to A finds A's slot without A having to exist yet.
     */
    @Test
    fun `mutual recursion validates`() {
        val g = graph {
            function("A"); function("B")
            node(BuiltinNodes.FUNCTION, function = "A")
            node(BuiltinNodes.FUNCTION, function = "B")
            node(BuiltinNodes.CALL, function = "A", callee = "B")
            node(BuiltinNodes.CALL, function = "B", callee = "A")
        }
        assertTrue(errorsOf(g).none { it.contains("calls itself") }, "${errorsOf(g)}")
    }

    /**
     * A body CAN use a graph variable, now that variables are the run's globals rather than frame-local.
     *
     * They had to become globals for the stop handler — its own fiber, reporting on what the run wrote —
     * and lifting this restriction came free with it.
     */
    @Test
    fun `a function body reads and writes a graph variable`() {
        val g = graph {
            function("Bump")
            variable("counter", PinType.INT, 5)
            val start = node(BuiltinNodes.ENTRY)
            val call = node(BuiltinNodes.CALL, callee = "Bump")
            val get = node(BuiltinNodes.VAR_GET, variable = "counter")
            val ret = node(BuiltinNodes.RETURN)
            link(start, "Exec", call, "Exec")
            link(call, "Exec", ret, "Exec")
            link(get, "Value", ret, "Value")

            // The body sets counter = counter + 1.
            val box = node(BuiltinNodes.FUNCTION, function = "Bump")
            val g2 = node(BuiltinNodes.VAR_GET, function = "Bump", variable = "counter")
            val one = node(BuiltinNodes.LITERAL_INT, literals = mapOf("Value" to 1), function = "Bump")
            val add = node(BuiltinNodes.ADD, function = "Bump")
            val set = node(BuiltinNodes.VAR_SET, function = "Bump", variable = "counter")
            link(box, "Exec", set, "Exec")
            link(g2, "Value", add, "A")
            link(one, "Value", add, "B")
            link(add, "Result", set, "Value")
            link(set, "Exec", box, "Exec")
        }
        assertTrue(Validator(catalog).validate(g).errors().isEmpty(), "${Validator(catalog).validate(g)}")
        val chunk = GraphCompiler(catalog).compile(g, g.entries(catalog).first().id)
        // 5 + 1, written inside the call and read back outside it.
        assertEquals(listOf<Any?>(6), drive(chunk).fiber.result)
    }

    /**
     * The exact shape that bit a live run: a second call whose parameter had nothing feeding it.
     *
     * The graph compiled and ran, the FIRST call worked, and the second failed deep inside the body with
     * "expected a number, got null" — a long way from the call site that forgot to supply it.
     */
    @Test
    fun `an unfed call parameter is warned about, not left to fail at runtime`() {
        val g = graph {
            function("Double", params = listOf("X" to PinType.INT), results = listOf("Y" to PinType.INT))
            val start = node(BuiltinNodes.ENTRY)
            val call = node(BuiltinNodes.CALL, callee = "Double") // no literal, no wire into X
            link(start, "Exec", call, "Exec")
            node(BuiltinNodes.FUNCTION, function = "Double")
        }
        val issues = Validator(catalog).validate(g)
        assertTrue(issues.none { it.severity == Severity.ERROR }, "should still run: $issues")
        assertTrue(
            issues.any { it.severity == Severity.WARNING && it.message.contains("nothing feeding it") },
            "$issues",
        )
    }

    /** A parameter that IS fed says nothing — a warning you always see is a warning you stop reading. */
    @Test
    fun `a fed parameter is silent`() {
        val g = graph {
            function("Double", params = listOf("X" to PinType.INT))
            val start = node(BuiltinNodes.ENTRY)
            val call = node(BuiltinNodes.CALL, literals = mapOf("X" to 1), callee = "Double")
            link(start, "Exec", call, "Exec")
            node(BuiltinNodes.FUNCTION, function = "Double")
        }
        assertTrue(Validator(catalog).validate(g).none { it.message.contains("nothing feeding it") })
    }

    /** The signature is what a Call node's pins ARE — so a mistyped wire into one is caught. */
    @Test
    fun `a call site is type-checked against the signature`() {
        val g = graph {
            function("Takes", params = listOf("N" to PinType.INT))
            val start = node(BuiltinNodes.ENTRY)
            val text = node(BuiltinNodes.LITERAL_STRING, literals = mapOf("Value" to "nope"))
            val call = node(BuiltinNodes.CALL, callee = "Takes")
            link(start, "Exec", call, "Exec")
            link(text, "Value", call, "N")
            node(BuiltinNodes.FUNCTION, function = "Takes")
        }
        assertTrue(errorsOf(g).any { it.contains("cannot wire STRING into INT") }, "${errorsOf(g)}")
    }

    /**
     * To Text, end to end on the VM.
     *
     * A pure host node, so it is re-expanded at the use site like any other expression — the interesting
     * part is only that a number arrives at a STRING pin as text rather than being refused.
     */
    @Test
    fun `To Text renders a value for a string pin`() {
        val g = graph {
            val start = node(BuiltinNodes.ENTRY)
            val n = node(BuiltinNodes.LITERAL_INT, literals = mapOf("Value" to 42))
            val conv = node(BuiltinNodes.TO_TEXT)
            val ret = node(BuiltinNodes.RETURN)
            link(start, "Exec", ret, "Exec")
            link(n, "Value", conv, "Value")
            link(conv, "Text", ret, "Value")
        }
        assertTrue(Validator(catalog).validate(g).errors().isEmpty(), "${Validator(catalog).validate(g)}")

        val hosts = dev.ziggle.vscript.vm.HostRegistry().register(
            "vscript.toText", dev.ziggle.vscript.vm.HostKind.INLINE, arity = 1, results = 1,
        ) { args -> args.getOrNull(0)?.toString() ?: "null" }

        val chunk = GraphCompiler(catalog).compile(g, g.entries(catalog).first().id)
        assertEquals(listOf<Any?>("42"), drive(chunk, hosts).fiber.result)
    }

    /** A string pin accepts text directly — the converter is for everything else. */
    @Test
    fun `a wildcard input takes anything`() {
        val g = graph {
            val start = node(BuiltinNodes.ENTRY)
            val conv = node(BuiltinNodes.TO_TEXT)
            val ret = node(BuiltinNodes.RETURN)
            link(start, "Exec", ret, "Exec")
            link(conv, "Text", ret, "Value")
        }
        assertTrue(Validator(catalog).validate(g).errors().isEmpty(), "${Validator(catalog).validate(g)}")
    }

    /**
     * A body made only of pure nodes has no exec chain — there is no statement to sequence — so it never
     * reaches the boundary. Its results must come back anyway.
     *
     * Found live: `Add number to name(Name, Number) -> Output` wired entirely with data returned null, and
     * the graph downstream logged "Hello, null". Nothing was wrong with it; there was no exec wire to draw.
     */
    @Test
    fun `a body with no exec wiring still returns its results`() {
        val g = graph {
            function("Join", params = listOf("A" to PinType.INT), results = listOf("Out" to PinType.INT))
            val start = node(BuiltinNodes.ENTRY)
            val call = node(BuiltinNodes.CALL, literals = mapOf("A" to 20), callee = "Join")
            val ret = node(BuiltinNodes.RETURN)
            link(start, "Exec", call, "Exec")
            link(call, "Exec", ret, "Exec")
            link(call, "Out", ret, "Value")

            // Data only: the parameter doubled into the result. No exec wire anywhere in the body.
            val box = node(BuiltinNodes.FUNCTION, function = "Join")
            val add = node(BuiltinNodes.ADD, function = "Join")
            link(box, "A", add, "A")
            link(box, "A", add, "B")
            link(add, "Result", box, "Out")
        }

        val chunk = GraphCompiler(catalog).compile(g, g.entries(catalog).first().id)
        assertEquals(listOf<Any?>(40), drive(chunk).fiber.result)
    }

    /** The Text node, end to end: template in, pins from its holes, one string out. */
    @Test
    fun `a template renders its holes on the VM`() {
        val g = graph {
            val start = node(BuiltinNodes.ENTRY)
            val n = node(BuiltinNodes.LITERAL_INT, literals = mapOf("Value" to 7))
            val fmt = node(BuiltinNodes.FORMAT, literals = mapOf("Template" to "Baskets full: {count} of 27"))
            val ret = node(BuiltinNodes.RETURN)
            link(start, "Exec", ret, "Exec")
            link(n, "Value", fmt, "count")   // the pin exists because the template names it
            link(fmt, "Text", ret, "Value")
        }
        assertTrue(Validator(catalog).validate(g).errors().isEmpty(), "${Validator(catalog).validate(g)}")

        val hosts = dev.ziggle.vscript.vm.HostRegistry().register(
            "vscript.format", dev.ziggle.vscript.vm.HostKind.INLINE, arity = -1, results = 1,
        ) { args ->
            val tpl = args.getOrNull(0)?.toString().orEmpty()
            val names = dev.ziggle.vscript.model.Templates.placeholders(tpl)
            dev.ziggle.vscript.model.Templates.render(tpl, names.withIndex().associate { (i, nm) -> nm to args.getOrNull(i + 1) })
        }
        val chunk = GraphCompiler(catalog).compile(g, g.entries(catalog).first().id)
        assertEquals(listOf<Any?>("Baskets full: 7 of 27"), drive(chunk, hosts).fiber.result)
    }
}
