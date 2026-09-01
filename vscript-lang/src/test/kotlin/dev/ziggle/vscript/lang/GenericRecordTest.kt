package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Severity
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphDoc
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.Chunk
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `type Pair<A, B>` — phase F's last piece.
 *
 * **Erased**, and that is the decision the rest follows from: there is one `StructShape` for `Pair`
 * whatever it is built at, the arguments are checked where the record is made and read, and they are gone
 * by run time. So `p is Pair` works and `p is Pair<INT, STRING>` is refused — the second is a question the
 * running program cannot answer, and answering it approximately would be worse than refusing.
 *
 * The arguments are never WRITTEN at a construction. `Pair { first: 1, second: "a" }` is a
 * `Pair<INT, STRING>` because the values say so; making the author write it too would be a second place to
 * state a fact, and the first place would go stale.
 */
class GenericRecordTest {

    private val sayNode = hostNode(
        "test.say", "test.say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )

    private val catalog = NodeCatalog(listOf(sayNode))
    private val text = VsText(catalog)

    private fun graphOf(src: String): Graph {
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse: ${parsed.errors.map { "${it.span} ${it.message}" }}")
        val low = Lower(catalog).lower(parsed.program)
        assertTrue(low.ok, "lower: ${low.errors}")
        assertEquals(
            emptyList(),
            Validator(catalog).validate(low.graph).filter { it.severity == Severity.ERROR }.map { it.message },
            "did not validate",
        )
        return low.graph
    }

    private fun errors(src: String): List<String> {
        val parsed = Parser(Lexer(src).lex()).parse()
        if (!parsed.ok) return parsed.errors.map { it.message }
        val low = Lower(catalog).lower(parsed.program)
        if (low.errors.isNotEmpty()) return low.errors.map { it.message }
        return Validator(catalog).validate(low.graph).filter { it.severity == Severity.ERROR }.map { it.message }
    }

    private fun compile(src: String): Chunk {
        val g = graphOf(src)
        return GraphCompiler(catalog, debug = false).compile(g, g.entries(catalog).single().id)
    }

    private fun said(src: String): List<Any?> {
        val said = ArrayList<Any?>()
        val hosts = BuiltinHosts.registry()
        hosts.register("test.say", HostKind.INLINE, arity = 1) { a -> said += a[0]; null }
        val r = drive(compile(src), hosts, maxTicks = 4000)
        assertNull(r.fiber.error, "vm error: ${r.fiber.error}")
        assertTrue(r.fiber.isFinished, "did not finish")
        return said.map { if (it is Number && it !is Double) it.toLong() else it }
    }

    private fun roundTrip(src: String) {
        assertEquals(src.trim(), text.write(graphOf(src)).trim(), "round trip")
    }

    // ---- the declaration -------------------------------------------------------------------------------

    /** It parses, it prints back, and the field values arrive where they were put. */
    @Test
    fun `a generic record is declared, built and read`() {
        assertEquals(
            listOf(1L, "a"),
            said(
                """
                graph "t"

                export type Pair<A, B> { first: A, second: B }

                on start {
                    val p = Pair { first: 1, second: "a" }
                    say(message: p.first)
                    say(message: p.second)
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * Note the two declarations print with no blank line between them: that is the printer's existing
     * shape for a run of records, not something generics changed.
     */
    @Test
    fun `the declaration survives the round trip`() {
        roundTrip(
            """
            graph "t"

            export type Pair<A, B> { first: A, second: B }
            export type Box<T> { held: T, tries: INT }

            on start {
                val p = Pair { first: 1, second: "a" }
                say(message: p.first)
            }
            """.trimIndent(),
        )
    }

    /**
     * And through the PERSISTED form, which is the other boundary marking happens at.
     *
     * A field typed `A` reads back as a declared type otherwise, and the validator would then report a
     * record called `A` that nothing declares — the same failure phase E hit with a function's `T`.
     */
    @Test
    fun `the parameters survive the persisted form`() {
        val g = graphOf(
            """
            graph "t"

            export type Pair<A, B> { first: A, second: B }

            on start {
                val p = Pair { first: 1, second: "a" }
                say(message: p.first)
            }
            """.trimIndent(),
        )
        val reread = GraphDoc.fromJson(GraphDoc.toJson(g))
        assertEquals(listOf("A", "B"), reread.types.single().params)
        assertTrue(reread.types.single().fields.all { it.type.variable }, "field types should read back marked")
        assertEquals(
            emptyList(),
            Validator(catalog).validate(reread).filter { it.severity == Severity.ERROR }.map { it.message },
        )
    }

    // ---- inference -------------------------------------------------------------------------------------

    /**
     * The arguments are inferred from the field VALUES, and they are real downstream.
     *
     * A wildcard would have let this wire and the test would have passed for the wrong reason — so the
     * assertion is that a wrong use is REFUSED, which only a bound `A = INT` can do.
     */
    @Test
    fun `the field types are bound from what was built with`() {
        val e = errors(
            """
            graph "t"

            export type Pair<A, B> { first: A, second: B }

            export fn wantsText(s: STRING) -> INT = _listCount(list: [s])

            on start {
                val p = Pair { first: 1, second: "a" }
                say(message: wantsText(s: p.first))
            }
            """.trimIndent(),
        )
        assertTrue(e.any { "INT" in it && "STRING" in it }, "expected the refusal to name both types, got $e")
    }

    /** Two instantiations of one record, in one document, that do not agree — and both are checked. */
    @Test
    fun `two instantiations do not leak into each other`() {
        assertEquals(
            listOf(1L, "x", "y", 2L),
            said(
                """
                graph "t"

                export type Pair<A, B> { first: A, second: B }

                on start {
                    val a = Pair { first: 1, second: "x" }
                    val b = Pair { first: "y", second: 2 }
                    say(message: a.first)
                    say(message: a.second)
                    say(message: b.first)
                    say(message: b.second)
                }
                """.trimIndent(),
            ),
        )
    }

    /** A generic record inside a container, and a container inside one. */
    @Test
    fun `a parameter may be bound to a container`() {
        assertEquals(
            listOf(2L),
            said(
                """
                graph "t"

                export type Box<T> { held: T }

                on start {
                    val b = Box { held: [1, 2] }
                    say(message: _listCount(list: b.held))
                }
                """.trimIndent(),
            ),
        )
    }

    // ---- erasure ---------------------------------------------------------------------------------------

    /** `is` asks a question the running program can answer. With arguments, it is not one. */
    @Test
    fun `is asks about the record and refuses its arguments`() {
        assertEquals(
            listOf(true),
            said(
                """
                graph "t"

                export type Pair<A, B> { first: A, second: B }

                on start {
                    val p = Pair { first: 1, second: "a" }
                    say(message: p is Pair)
                }
                """.trimIndent(),
            ),
        )
        val e = errors(
            """
            graph "t"

            export type Pair<A, B> { first: A, second: B }

            on start {
                val p = Pair { first: 1, second: "a" }
                say(message: p is Pair<INT, STRING>)
            }
            """.trimIndent(),
        )
        assertTrue(e.any { "ERASED" in it }, "expected the erasure refusal, got $e")
    }

    // ---- what is still an error ------------------------------------------------------------------------

    /**
     * The safety property: a field typed with a name the record does NOT list is still a mistake.
     *
     * A record says what its parameters are, so there is no convention to fall back on and no reason to
     * guess — which is exactly why this rule can be stricter than a function's.
     */
    @Test
    fun `a field typed with an unlisted name is still undeclared`() {
        val e = errors(
            """
            graph "t"

            export type Pair<A> { first: A, second: B }

            on start {
                val p = Pair { first: 1, second: "a" }
                say(message: p.first)
            }
            """.trimIndent(),
        )
        assertTrue(e.any { "B" in it }, "expected 'B' to be reported as undeclared, got $e")
    }
}
