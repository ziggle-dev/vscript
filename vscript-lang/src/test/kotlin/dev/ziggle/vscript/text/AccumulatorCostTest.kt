package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What the documented accumulator idiom actually costs on the text path.
 *
 * `LANGUAGE.md` §3.4 says, in bold, that *"writing the accumulator the obvious way does not cost what it
 * looks like it costs"*. That is true on the GRAPH path, where `AppendPass` proves the copy unobservable
 * and emits `Op.APPEND` in its place. `TextCompiler` runs no such pass and no `Inliner`, so on the path
 * every script is actually written for, `xs = _listWithItemAdded(list: xs, value: v)` is a host call that
 * copies the whole list — making the loop quadratic.
 *
 * **This is measured rather than assumed, and the distinction matters.** An earlier attempt to measure it
 * was contaminated: the benchmark was a `while` loop, and `while` emitted an `Op.YIELD` per iteration, so
 * it timed the scheduler rather than the copy. Both loops here are `for` loops, which never yielded.
 *
 * The assertion is on the SHAPE, not a millisecond count — a wall clock is a coin flip on a busy machine,
 * and the thing being measured is a factor of n. Five times the items: linear is ~5x the time, a copy per
 * item is ~25x.
 */
class AccumulatorCostTest {

    private val INT = TypeRef(PinType.INT)

    private val natives = NativeTable(
        listOf(
            NativeFn("log", listOf(NativeParam("message", TypeRef(PinType.STRING))), results = emptyList()),
            // `range` is the client catalogue's, not a builtin — the loop needs something to walk.
            NativeFn(
                "range",
                listOf(NativeParam("from", INT), NativeParam("to", INT)),
                results = listOf(NativeParam("Result", TypeRef.list(INT))),
            ),
            NativeFn(
                "_listWithItemAdded",
                listOf(NativeParam("list", TypeRef.list(INT)), NativeParam("value", INT)),
                results = listOf(NativeParam("Result", TypeRef.list(INT))),
            ),
        ),
    )

    private fun run(src: String): Pair<List<String>, Long> {
        val said = ArrayList<String>()
        val hosts = BuiltinHosts.registry()
            .register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
            // Faithful to the real node: every list verb allocates unconditionally, so no host may hand
            // back the list it was passed. See vscript/CLAUDE.md.
            .register("_listWithItemAdded", HostKind.INLINE, arity = 2, results = 1) { a ->
                ArrayList(a[0] as List<*>).also { it.add(a[1]) }
            }
            .register("range", HostKind.INLINE, arity = 2, results = 1) { a ->
                val from = (a[0] as Number).toInt()
                val to = (a[1] as Number).toInt()
                ArrayList<Any?>((from until to).toList())
            }
        val r = TextFrontEnd(natives).read(src)
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        val t0 = System.nanoTime()
        drive(chunk, hosts, maxTicks = 2_000_000)
        return said to (System.nanoTime() - t0) / 1_000_000
    }

    private fun copying(n: Int) = """
        on start {
            var xs: List<Int> = []
            for i in range(from: 0, to: $n) {
                xs = _listWithItemAdded(list: xs, value: i)
            }
            log(message: "" + _listCount(list: xs))
        }
    """.trimIndent()

    private fun inPlace(n: Int) = """
        on start {
            var xs: List<Int> = _newList()
            for i in range(from: 0, to: $n) {
                _listAdd(xs, i)
            }
            log(message: "" + _listCount(xs))
        }
    """.trimIndent()

    private fun timed(src: String, n: Int): Long {
        run(src)                                    // warm the JIT, and prove it compiles
        val (out, ms) = run(src)
        assertEquals(listOf("$n"), out, "the list did not end up with $n items")
        return ms
    }

    @Test
    fun `the copying accumulator is quadratic and the primitive is not`() {
        val sizes = listOf(500, 1_000, 5_000, 20_000)
        val rows = sizes.map { n -> Triple(n, timed(copying(n), n), timed(inPlace(n), n)) }

        val body = rows.joinToString("\n") { (n, copy, fast) ->
            "  |  n=%-6d  copying %6dms   in place %4dms".format(n, copy, fast)
        }
        println(
            """
            |
            |  accumulator cost, 'for' loop, text front end (raw CPU, no tick budget)
            |  ---------------------------------------------------------------------
            |$body
            |
            """.trimMargin(),
        )

        // The SHAPE, not a millisecond count — a wall clock is a coin flip and the defect is a factor
        // of n. Quadratic doubles its per-item cost every time n doubles; linear holds it flat.
        val (nSmall, copySmall, _) = rows.first { it.first == 1_000 }
        val (nLarge, copyLarge, fastLarge) = rows.first { it.first == 20_000 }
        val grew = if (copySmall > 0) copyLarge.toDouble() / copySmall else Double.MAX_VALUE
        val items = nLarge.toDouble() / nSmall
        assertTrue(
            grew > items * 2,
            "copying grew ${"%.1f".format(grew)}x for ${items.toInt()}x the items — that is not the " +
                "quadratic this test exists to document; has the copy been elided?",
        )
        assertTrue(
            fastLarge * 4 < copyLarge,
            "the in-place primitive (${fastLarge}ms) should be far cheaper than copying (${copyLarge}ms)",
        )
    }
}
