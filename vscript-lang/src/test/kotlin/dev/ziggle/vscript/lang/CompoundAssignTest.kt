package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.compile.errors
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `n += 1` — sugar with somewhere to record itself.
 *
 * **It was refused once, and the refusal was right at the time.** `BuiltinNodes.WROTE_FIELD` names `x += 1`
 * as the example of the collision that kills sugar: two spellings lowering to one graph, so the printer has
 * to pick one and the other stops round-tripping. What changed is not the argument but the precedent —
 * `var` and `s.f = v` are also one graph with two spellings, and both are had by leaving a MARK on the node
 * for the printer to read. This is the third of those, and the tests that matter are the ones about the
 * mark: that the graph is untouched by it, and that the printer refuses to trust it once the graph moves.
 */
class CompoundAssignTest {

    private val sayNode = hostNode(
        "test.say", "say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val catalog = NodeCatalog(listOf(sayNode))
    private val text = VsText(catalog)

    private fun graphOf(src: String): dev.ziggle.vscript.model.Graph {
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse: ${parsed.errors.map { it.message }}")
        val low = Lower(catalog).lower(parsed.program)
        assertTrue(low.ok, "lower: ${low.errors}")
        assertEquals(emptyList(), Validator(catalog).validate(low.graph).errors(), "did not validate")
        return low.graph
    }

    private fun said(src: String): List<Any?> {
        val g = graphOf(src)
        val out = ArrayList<Any?>()
        val hosts = HostRegistry()
        hosts.register("say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        drive(
            GraphCompiler(catalog, debug = false).compile(g, g.entries(catalog).single().id),
            hosts, maxTicks = 800,
        )
        return out.map { if (it is Number && it !is Double) it.toLong() else it }
    }

    /** Print [src] back out. The round trip is the whole contract, so most tests here are this. */
    private fun printed(src: String): String = text.write(graphOf(src)).trim()

    // ---- it runs ------------------------------------------------------------------------------------

    @Test
    fun `all five compound operators mean what they say`() {
        assertEquals(
            listOf(7L, 5L, 15L, 5L, 2L),
            said(
                """
                on start {
                    var n = 5
                    n += 2   say(n)
                    n -= 2   say(n)
                    n *= 3   say(n)
                    n /= 3   say(n)
                    n %= 3   say(n)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `it works on a graph variable as well as a local`() {
        assertEquals(listOf(3L), said("var N: Int = 1\non start { N += 2 say(N) }"))
    }

    @Test
    fun `the counted loop it was for`() {
        assertEquals(
            listOf(0L, 1L, 2L),
            said("on start { var i = 0\nwhile i < 3 { say(i) i += 1 } }"),
        )
    }

    @Test
    fun `the right-hand side is a whole expression, not just a literal`() {
        // `n += a * 2` must bind as `n = n + (a * 2)`, never `(n + a) * 2`. The desugaring puts the parsed
        // expression on the right of ONE operator, so precedence inside it cannot leak out.
        assertEquals(listOf(11L), said("on start { var n = 5 let a = 3 n += a * 2 say(n) }"))
    }

    // ---- the graph is unchanged ---------------------------------------------------------------------

    @Test
    fun `it lowers to exactly the graph the long form lowers to`() {
        // The claim the whole design rests on: nothing below the parser learns this form exists. If the two
        // graphs differ in anything but the mark, then the compiler, the validator and the canvas all have
        // a second case to handle and the sugar was not free after all.
        val sugar = graphOf("on start { var n = 0 n += 1 }")
        val long = graphOf("on start { var n = 0 n = n + 1 }")

        assertEquals(long.nodes.map { it.type }, sugar.nodes.map { it.type }, "different nodes")
        assertEquals(
            long.links.map { "${it.fromNode}:${it.fromPin} -> ${it.toNode}:${it.toPin}" },
            sugar.links.map { "${it.fromNode}:${it.fromPin} -> ${it.toNode}:${it.toPin}" },
            "different wiring",
        )

        // …and the ONE difference is the mark.
        val marks = sugar.nodes.mapNotNull { it.literals[BuiltinNodes.ASSIGN_OP] }
        assertEquals(listOf<Any?>(BuiltinNodes.ADD), marks)
        assertEquals(emptyList(), long.nodes.mapNotNull { it.literals[BuiltinNodes.ASSIGN_OP] })
    }

    // ---- the round trip -----------------------------------------------------------------------------

    @Test
    fun `a compound assignment prints back as itself`() {
        for (op in listOf("+", "-", "*", "/", "%")) {
            val src = "on start {\n    var n = 5\n    n $op= 2\n}"
            assertEquals(src, printed(src), "$op= did not survive the round trip")
        }
    }

    @Test
    fun `the long form still prints as the long form`() {
        // The other half of "one spelling per node". An author who wrote it out is not rewritten, which is
        // exactly what a marker buys over a printer that always prefers the shorter form.
        val src = "on start {\n    var n = 5\n    n = n + 2\n}"
        assertEquals(src, printed(src))
    }

    @Test
    fun `a field can be compounded too`() {
        // `INT` rather than `Int`: the printer canonicalises a declared type's spelling, which is a rule
        // that predates this and has nothing to do with the assignment.
        val src = """
            type Run { laps: INT }

            on start {
                var r = Run { laps: 0 }
                r.laps += 1
            }
        """.trimIndent()
        assertEquals(src, printed(src))
        assertEquals(
            listOf(1L),
            said("type Run { laps: Int }\non start { var r = Run { laps: 0 } r.laps += 1 say(r.laps) }"),
        )
    }

    @Test
    fun `an unmarked graph prints the long form, because nobody wrote a compound there`() {
        // A canvas author wiring an Add into a Set made a graph, not a spelling. Printing `+=` for it would
        // put words in their mouth on every reopen — the same rule that makes a hand-wired Set Field print
        // as `with` rather than as an assignment.
        val g = graphOf("on start { var n = 0 n += 1 }")
        for (n in g.nodes) n.literals.remove(BuiltinNodes.ASSIGN_OP)
        assertTrue("n = n + 1" in text.write(g), "expected the long form, got:\n${text.write(g)}")
    }

    @Test
    fun `a mark whose operator no longer matches the node is ignored`() {
        // Half of "the shape is re-derived, not assumed". A canvas edit can replace the Add feeding a Set
        // with a Subtract; printing `n += …` off a stale mark would then say the opposite of what runs.
        val g = graphOf("on start { var n = 0 n += 1 }")
        g.nodes.single { it.literals.containsKey(BuiltinNodes.ASSIGN_OP) }
            .literals[BuiltinNodes.ASSIGN_OP] = BuiltinNodes.SUB

        val out = text.write(g)
        assertTrue("-=" !in out && "+=" !in out, "a mismatched mark was trusted:\n$out")
        assertTrue("n = n + 1" in out, "expected the honest long form, got:\n$out")
    }

    @Test
    fun `a mark on a value that does not read the target is ignored`() {
        // The other half. `n = m + 1` is not `n += 1` and never was — so a mark claiming otherwise, however
        // it got there, must lose to what the wires actually say.
        val g = graphOf("on start { var m = 9 var n = 0 n = m + 1 }")
        val set = g.nodes.last { it.type == BuiltinNodes.LOCAL_SET || it.type == BuiltinNodes.VAR_SET }
        set.literals[BuiltinNodes.ASSIGN_OP] = BuiltinNodes.ADD

        val out = text.write(g)
        assertTrue("+=" !in out, "a mark on the wrong shape was trusted:\n$out")
        assertTrue("n = m + 1" in out, "expected the honest long form, got:\n$out")
    }

    // ---- the lexer ----------------------------------------------------------------------------------

    @Test
    fun `a negative literal and a minus-equals do not compete`() {
        // `-=` and `-1` are decided at different points in the lexer — the literal branch needs a DIGIT
        // after the sign and runs first — so this is the pair that would show it if they ever met.
        assertEquals(listOf(-6L), said("on start { var n = -5 n -= 1 say(n) }"))
        assertEquals(listOf(-4L), said("on start { var n = -5 n -= -1 say(n) }"))
    }

    @Test
    fun `slash-equals is not a comment`() {
        assertEquals(listOf(2L), said("on start { var n = 4 n /= 2 say(n) }"))
    }
}
