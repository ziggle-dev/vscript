package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.compile.errors
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What an arithmetic node's result is TYPED as, across a chain.
 *
 * **The bug these exist for.** `"x" + 3 + 0.5` was refused with *"cannot wire FLOAT into STRING"*. `+`
 * associates left, so the inner `Add` is `"x" + 3` — and a string literal is deliberately untyped, because
 * a Kotlin String is also how this language stores a TILE, a SKILL and an ENUM (see `literalTypeOf`). So the
 * inner result came out WILDCARD, the outer `Add` saw WILDCARD beside FLOAT, took the "a Double wins" branch,
 * and typed the whole expression FLOAT.
 *
 * Every INT case worked, which is exactly why no script ever tripped it — and why it read, to anyone who
 * did, as "this language cannot put a float in a message".
 *
 * The cure is that an operand which does not say leaves the answer unsaid, which is what `promote`'s own
 * note already promised. Concatenation itself was never in doubt: the VM's `Values.arith` has always
 * treated a String on either side as concatenation, so these run and always would have.
 */
class ArithTypeTest {

    private val sayNode = hostNode(
        "test.say", "say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.STRING)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val catalog = NodeCatalog(listOf(sayNode))

    /** The message a run produced, or the diagnostics that stopped it compiling. */
    private fun said(body: String): List<Any?> {
        val src = """
            graph "probe"
            on start {
                $body
            }
        """.trimIndent()
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse: ${parsed.errors.map { it.message }}")
        val low = Lower(catalog).lower(parsed.program)
        assertTrue(low.ok, "lower: ${low.errors.map { it.message }}")
        assertEquals(
            emptyList(),
            Validator(catalog).validate(low.graph).errors().map { it.message },
            "did not validate",
        )
        val out = ArrayList<Any?>()
        val hosts = BuiltinHosts.registry()
        hosts.register("say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        drive(
            GraphCompiler(catalog, debug = false)
                .compile(low.graph, low.graph.entries(catalog).single().id),
            hosts, maxTicks = 200,
        )
        return out
    }

    // ---- the defect ----------------------------------------------------------------------------------

    /** GAPS #2. Refused to compile before `promote` stopped guessing from a wildcard. */
    @Test
    fun `a float may sit past the second term of a concatenation`() {
        assertEquals(listOf("x30.5"), said("""say("x" + 3 + 0.5)"""))
    }

    @Test
    fun `and from an empty string, which is how a number is made text by hand`() {
        assertEquals(listOf("30.5"), said("""say("" + 3 + 0.5)"""))
    }

    @Test
    fun `a float late in a long chain`() {
        assertEquals(listOf("a-1-2-0.5"), said("""say("a" + "-" + 1 + "-" + 2 + "-" + 0.5)"""))
    }

    /** A local, not a literal — so the FLOAT is established rather than guessed. */
    @Test
    fun `a float held in a local concatenates too`() {
        assertEquals(
            listOf("rate 0.5"),
            said(
                """
                val rate = 0.5
                say("rate " + rate)
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a float local past the second term`() {
        assertEquals(
            listOf("n=3 rate=0.5"),
            said(
                """
                val rate = 0.5
                say("n=" + 3 + " rate=" + rate)
                """.trimIndent(),
            ),
        )
    }

    // ---- what already worked, kept working -----------------------------------------------------------

    @Test
    fun `a single float term`() {
        assertEquals(listOf("0.5"), said("""say("" + 0.5)"""))
    }

    @Test
    fun `bools and ints in a chain`() {
        assertEquals(listOf("atrue3"), said("""say("a" + true + 3)"""))
    }

    @Test
    fun `an int chain is still an int chain`() {
        assertEquals(listOf("6"), said("""say("" + (1 + 2 + 3))"""))
    }

    /**
     * The rule the new guard must not weaken: two INTs still promote to INT.
     *
     * This is the case `promote` was written for. `let a = 7` and `let b = 2` are both INT, so `a / b` is
     * integer division — 3, not 3.5 — and storing it in a FLOAT is a widening the compiler has to *emit*.
     * Both halves show up here: `3` proves the division stayed integral, and `.0` proves the conversion
     * happened rather than an Int being dropped into a slot that says FLOAT.
     *
     * Had the wildcard guard been written one line too high it would swallow this: an unknown result
     * connects to a FLOAT pin without converting, and the variable would quietly hold an Int and print `3`.
     */
    @Test
    fun `two ints still promote to int`() {
        val src = """
            graph "probe"
            on start {
                val a = 7
                val b = 2
                var s: STRING = a / b
            }
        """.trimIndent()
        val low = Lower(catalog).lower(Parser(Lexer(src).lex()).parse().program)
        val problems = low.errors.map { it.message } +
            Validator(catalog).validate(low.graph).errors().map { it.message }
        assertTrue(
            problems.any { "INT" in it && "STRING" in it },
            "INT / INT must still type as INT and be refused into a STRING, got: $problems",
        )
    }

    /**
     * Integer division stays integral, whatever it is stored in.
     *
     * Separate from the refusal above because the two can fail independently: the type could be right and
     * the arithmetic wrong. `7 / 2` is 3.
     */
    @Test
    fun `int division truncates`() {
        assertEquals(
            listOf("3"),
            said(
                """
                val a = 7
                val b = 2
                say("" + (a / b))
                """.trimIndent(),
            ),
        )
    }
}
