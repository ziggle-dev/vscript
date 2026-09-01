package dev.ziggle.vscript.nodes

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.compile.errors
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.lang.Lexer
import dev.ziggle.vscript.lang.Lower
import dev.ziggle.vscript.lang.Parser
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The list and math verbs, driven as SOURCE through the real hosts.
 *
 * Not unit tests of the lambdas in [BuiltinHosts]. Each of these nodes exists because a script was writing
 * it by hand, so what is owed is that the script's replacement works — parsed, lowered, validated, compiled
 * and run, with the value coming back out through `say`. That path is also the one that catches the
 * mistakes a direct call cannot: a pin named differently in the descriptor than in the host's argument
 * order, a list the VM refuses to adopt, a node that never got a text name at all.
 *
 * The corpus cases are called out by name — `byDistance`, `circle`, `abs` — because each is a specific
 * hand-rolled thing in `vscript-client/scripts/core/` that these were added to delete.
 */
class BuiltinVerbsTest {

    private val sayNode = hostNode(
        "test.say", "say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val catalog = NodeCatalog(listOf(sayNode))

    /**
     * Run [src], and hand back both what it said and how it ended.
     *
     * The failure half is not incidental. A VM error does not propagate out of [drive] — it lands on the
     * fiber — so a helper that only collected `say` values would report a node that threw as "expected
     * [1, 2, 3] but was []", which names neither the node nor the reason.
     */
    private fun run(src: String, maxTicks: Int = 2000): Pair<List<Any?>, String?> {
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse: ${parsed.errors.map { it.message }}")
        val low = Lower(catalog).lower(parsed.program)
        assertTrue(low.ok, "lower: ${low.errors}")
        assertEquals(emptyList(), Validator(catalog).validate(low.graph).errors(), "did not validate")

        val out = ArrayList<Any?>()
        // The real registry, plus the one stub. Registering `say` onto BuiltinHosts' own registry rather
        // than a fresh one is what makes this exercise the shipped host table instead of a copy of it.
        val hosts = BuiltinHosts.registry()
        hosts.register("say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        val entry = low.graph.entries(catalog).single()
        val r = drive(GraphCompiler(catalog, debug = false).compile(low.graph, entry.id), hosts, maxTicks = maxTicks)
        // Numbers widened so `3` and `3L` do not compare unequal; Doubles left alone so 3.5 stays 3.5.
        return out.map { if (it is Number && it !is Double) it.toLong() else it } to r.fiber.error?.message
    }

    /** Everything the script said, asserting it ran to the end. */
    private fun said(src: String, maxTicks: Int = 2000): List<Any?> {
        val (out, err) = run(src, maxTicks)
        assertEquals(null, err, "the script failed rather than finishing")
        return out
    }

    /** One expression, said. */
    private fun value(expr: String): Any? = said("on start { say($expr) }").single()

    /** How [expr] failed, or null when it did not — the VM's message, off the fiber. */
    private fun failure(expr: String): String? = run("on start { say($expr) }").second

    // ---- range ---------------------------------------------------------------------------------------

    @Test
    fun `range is the counted loop`() {
        // LANGUAGE.md §5 says "there is no counted `for`, use `while` with a `var`". This is what retires it.
        assertEquals(
            listOf(0L, 1L, 2L),
            said("on start { for i in range(0, 3) { say(i) } }"),
        )
    }

    @Test
    fun `range excludes its end, so it is exactly a list's indices`() {
        assertEquals(
            listOf("a", "b"),
            said(
                """
                on start {
                    val xs = ["a", "b"]
                    for i in range(0, _listCount(list: xs)) { say(xs[i]) }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a range that ends before it starts simply does not loop`() {
        // The reason the previous test is safe on a list that came back empty: `range(0, 0)` is not an
        // error, and neither is a count that went negative.
        assertEquals(listOf("done"), said("on start { for i in range(0, 0) { say(i) } say(\"done\") }"))
        assertEquals(listOf("done"), said("on start { for i in range(5, 2) { say(i) } say(\"done\") }"))
    }

    @Test
    fun `an absurd range is a script error rather than an OutOfMemoryError`() {
        // Every other runaway here is caught by the tick budget because it runs instructions. This one
        // allocates the whole list inside ONE instruction, so nothing would catch it but this.
        val err = failure("_listCount(list: range(0, 999999999))")
        assertTrue(
            "mistake, not a loop" in err.orEmpty(),
            "expected the range cap to fire, got: $err",
        )
    }

    // ---- the list verbs ------------------------------------------------------------------------------

    @Test
    fun `indexOf answers -1 rather than nothing`() {
        assertEquals(1L, value("_listIndexOf(list: [10, 20, 30], value: 20)"))
        assertEquals(-1L, value("_listIndexOf(list: [10, 20, 30], value: 99)"))
    }

    @Test
    fun `indexOf widens like contains does`() {
        // The list holds whatever a node handed back, which is Long-wide; the literal searched for is an
        // Int. Kotlin's own `indexOf` would say -1 here, and the script would look past a value it holds.
        assertEquals(0L, value("_listIndexOf(list: range(7, 9), value: 7)"))
    }

    @Test
    fun `concat is the join that was a loop`() {
        // `core/objects.vs` had `fn List.plus(self, others: Any)` as a for-loop over withItemAdded, with a
        // docstring saying "there is no concat node".
        assertEquals(listOf(1L, 2L, 3L), said("on start { for x in _listConcat(list: [1, 2], other: [3]) { say(x) } }"))
    }

    @Test
    fun `take and drop clamp instead of failing`() {
        assertEquals(listOf(1L, 2L), said("on start { for x in _listTake(list: [1, 2, 3], count: 2) { say(x) } }"))
        assertEquals(listOf(3L), said("on start { for x in _listDrop(list: [1, 2, 3], count: 2) { say(x) } }"))
        // Past the end either way — this is what makes them usable on a list nobody counted.
        assertEquals(0L, value("_listCount(list: _listDrop(list: [1, 2], count: 9))"))
        assertEquals(2L, value("_listCount(list: _listTake(list: [1, 2], count: 9))"))
    }

    @Test
    fun `reversed, sum, smallest and largest`() {
        assertEquals(listOf(3L, 2L, 1L), said("on start { for x in _listReversed(list: [1, 2, 3]) { say(x) } }"))
        assertEquals(6L, value("_listSum(list: [1, 2, 3])"))
        assertEquals(0L, value("_listSum(list: [])"), "nothing sums to 0")
        assertEquals(1L, value("_listSmallest(list: [3, 1, 2])"))
        assertEquals(3L, value("_listLargest(list: [3, 1, 2])"))
    }

    @Test
    fun `smallest and largest answer nothing for an empty list`() {
        // The same answer-for-empty rule `first` takes, and for the same reason: the list being asked
        // about is usually one a scene query produced.
        assertEquals(null, value("_listSmallest(list: [])"))
        assertEquals(null, value("_listLargest(list: [])"))
    }

    @Test
    fun `sum promotes to Float exactly when Add would`() {
        assertEquals(3.5, value("_listSum(list: [1.5, 2.0])"))
    }

    @Test
    fun `sum refuses a list that is not numbers`() {
        val err = failure("_listSum(list: [\"a\", \"b\"])")
        assertTrue(
            "needs a list of numbers" in err.orEmpty(),
            "expected sum to refuse strings rather than concatenating them, got: $err",
        )
    }

    @Test
    fun `without removes the first match only`() {
        assertEquals(
            listOf(2L, 1L),
            said("on start { for x in _listWithout(list: [1, 2, 1], value: 1) { say(x) } }"),
        )
        // A value it does not hold changes nothing.
        assertEquals(3L, value("_listCount(list: _listWithout(list: [1, 2, 1], value: 9))"))
    }

    @Test
    fun `withoutItemAt is how a queue loses its head`() {
        assertEquals(listOf(2L, 3L), said("on start { for x in _listWithoutAt(list: [1, 2, 3], index: 0) { say(x) } }"))
        // Out of range is a no-op, like withItemAt — safe to drive from a search that came up empty.
        assertEquals(3L, value("_listCount(list: _listWithoutAt(list: [1, 2, 3], index: 9))"))
        assertEquals(3L, value("_listCount(list: _listWithoutAt(list: [1, 2, 3], index: -1))"))
    }

    // ---- sortedBy ------------------------------------------------------------------------------------

    @Test
    fun `sortedBy orders by a parallel key list`() {
        // This IS `core/objects.byDistance`: measure each thing once into a parallel list, then sort by
        // what you measured. Its hand-rolled selection sort was n² and copied the whole list per swap.
        assertEquals(
            listOf("c", "a", "b"),
            said(
                """
                on start {
                    for x in _listSortedBy(list: ["a", "b", "c"], keys: [5, 9, 1]) { say(x) }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `sortedBy is stable, so equal keys keep their order`() {
        assertEquals(
            listOf("a", "b", "c"),
            said("on start { for x in _listSortedBy(list: [\"a\", \"b\", \"c\"], keys: [1, 1, 1]) { say(x) } }"),
        )
    }

    @Test
    fun `items past the end of the keys sort last, in their own order`() {
        // A short key list leaves the rest alone rather than scrambling it — which is what makes a keys
        // list built by a loop that broke early merely incomplete instead of destructive.
        assertEquals(
            listOf("b", "a", "c", "d"),
            said(
                """
                on start {
                    for x in _listSortedBy(list: ["a", "b", "c", "d"], keys: [2, 1]) { say(x) }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `byDistance and closest are now two lines each`() {
        // The whole of `core/objects.vs` minus its host call, as a script: measure once, sort, take first.
        // 35 lines of selection sort and a docstring apologising for it.
        assertEquals(
            listOf("near", "mid", "far"),
            said(
                """
                fn order(xs: LIST<String>, ds: LIST<Int>) -> LIST<String> = _listSortedBy(list: xs, keys: ds)
                on start {
                    for x in order(["far", "near", "mid"], [90, 1, 30]) { say(x) }
                }
                """.trimIndent(),
            ),
        )
    }

    // ---- math ----------------------------------------------------------------------------------------

    @Test
    fun `abs keeps the kind it was given`() {
        // `core/math.vs` spelled this `-1 * x`, which widens to Float and then needs a cast back.
        assertEquals(3L, value("abs(-3)"))
        assertEquals(3.5, value("abs(-3.5)"))
        assertEquals(0L, value("abs(0)"))
    }

    @Test
    fun `min and max hand back the operand, so they keep its kind`() {
        assertEquals(2L, value("min(a: 2, b: 5)"))
        assertEquals(5L, value("max(a: 2, b: 5)"))
        assertEquals(2.5, value("min(a: 2.5, b: 5)"))
    }

    @Test
    fun `sqrt and pow replace the hand-rolled loops`() {
        // `core/math.vs` had sqrt as Newton-Raphson in a `while` and pow as a repeated multiply that only
        // took whole positive exponents. Both are now one host call — and pow does fractions.
        assertEquals(3.0, value("sqrt(9.0)"))
        assertEquals(8.0, value("pow(base: 2.0, exponent: 3.0)"))
        assertEquals(2.0, value("pow(base: 4.0, exponent: 0.5)"))
    }

    @Test
    fun `sqrt of a negative is 0 rather than NaN`() {
        // NaN is the honest answer and the useless one: it compares false against everything, so it would
        // travel silently through a distance formula and surface as a walk that never starts.
        assertEquals(0.0, value("sqrt(0.0 - 1.0)"))
    }

    @Test
    fun `sin and cos retire the sixteen-entry table`() {
        // `core/draw.vs` carries a hand-written unit circle with a note saying trig "would be the cleaner
        // answer for the long run". A quarter turn: cos is 0, sin is 1.
        val quarter = said(
            """
            on start {
                val a = pi() / 2.0
                say(round(cos(a) * 1000.0))
                say(round(sin(a) * 1000.0))
            }
            """.trimIndent(),
        )
        assertEquals(listOf(0L, 1000L), quarter)
    }

    @Test
    fun `a circle is now trig rather than a table`() {
        // The 16 points `core/draw.circle` reads out of UnitX/UnitY, computed. Per-mille and rounded, which
        // is the form that file already uses — so this is the table, reproduced.
        val xs = said(
            """
            on start {
                for i in range(0, 16) {
                    say(round(cos(toFloat(i) * 2.0 * pi() / 16.0) * 1000.0))
                }
            }
            """.trimIndent(),
        )
        assertEquals(
            listOf(1000L, 924L, 707L, 383L, 0L, -383L, -707L, -924L, -1000L, -924L, -707L, -383L, 0L, 383L, 707L, 924L),
            xs,
            "these are exactly the entries of core/draw.vs's UnitX",
        )
    }
}
