package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Severity
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.model.Graph
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
 * `xs.add(3)` as a statement — the mutating receiver of `docs/VSCRIPT_ERGONOMICS_PLAN.md` §2f.
 *
 * **Nothing is mutated, and it could not be.** `Op.CALL` copies the argument window into the callee's
 * frame and a list value is an `ArrayList` reference, so a callee genuinely could write through to the
 * caller's list — which is precisely the aliasing `AppendPass` exists to refuse. As a language feature it
 * would mean `var b = a; b.add(3)` grows `a`, and "a list is a value" would stop being true; `MAP`'s
 * semantics and the whole in-place-append proof rest on that sentence.
 *
 * So `x.f(…)` in statement position MEANS `x = x.f(…)`: a write-back at the call site, the same graph
 * either way, and a printer marker to say which was typed.
 *
 * The property is **derived from the body** — a function is mutating because it assigns `self`. It is not
 * stored as a flag anywhere: a mutating extension hands its receiver back through an implicit result named
 * `self`, so `isMutating` reads it off the signature and the fact crosses an import for free.
 */
class MutatingReceiverTest {

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
        val r = drive(compile(src), hosts, maxTicks = 8000)
        assertNull(r.fiber.error, "vm error: ${r.fiber.error}")
        assertTrue(r.fiber.isFinished, "did not finish")
        return said.map { if (it is Number && it !is Double) it.toLong() else it }
    }

    private val ADD = """
        fn LIST<T>.add(self, value: T) {
            self = _listWithItemAdded(list: self, value: value)
        }
    """.trimIndent()

    // ---- the feature -----------------------------------------------------------------------------------

    /** The whole thing: declared by assigning `self`, called as a statement, and the local grew. */
    @Test
    fun `a mutating extension writes back to a local`() {
        assertEquals(
            listOf(0L, 2L, 7L),
            said(
                """
                graph "t"

                $ADD

                on start {
                    var xs: LIST<INT> = []
                    say(message: _listCount(list: xs))
                    xs.add(value: 5)
                    xs.add(value: 7)
                    say(message: _listCount(list: xs))
                    say(message: xs[1])
                }
                """.trimIndent(),
            ),
        )
    }

    /** A graph variable is a place too. */
    @Test
    fun `a graph variable is a valid receiver`() {
        assertEquals(
            listOf(1L),
            said(
                """
                graph "t"

                export var Seen: LIST<INT> = []

                $ADD

                on start {
                    Seen.add(value: 3)
                    say(message: _listCount(list: Seen))
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * **The property that must not break.** A list is still a value.
     *
     * If the receiver were mutated rather than written back, `b.add(…)` would grow `a` too and this would
     * say 1. Everything from `MAP`'s semantics to the append pass rests on it saying 0.
     */
    @Test
    fun `a list is still a value — the write-back does not alias`() {
        assertEquals(
            listOf(0L, 1L),
            said(
                """
                graph "t"

                $ADD

                on start {
                    var a: LIST<INT> = []
                    var b = a
                    b.add(value: 9)
                    say(message: _listCount(list: a))
                    say(message: _listCount(list: b))
                }
                """.trimIndent(),
            ),
        )
    }

    /** The long spelling still works and means the same thing — the implicit result is a real result. */
    @Test
    fun `the explicit spelling still works`() {
        assertEquals(
            listOf(1L),
            said(
                """
                graph "t"

                $ADD

                on start {
                    var xs: LIST<INT> = []
                    xs = xs.add(value: 4)
                    say(message: _listCount(list: xs))
                }
                """.trimIndent(),
            ),
        )
    }

    /** It composes with generics: `T` is bound by the receiver, so a wrong element type is refused. */
    @Test
    fun `the receiver's type variable still binds`() {
        val e = errors(
            """
            graph "t"

            $ADD

            on start {
                var xs: LIST<INT> = []
                xs.add(value: "no")
            }
            """.trimIndent(),
        )
        assertTrue(e.isNotEmpty(), "a STRING should not go into a LIST<INT>")
    }

    // ---- what is refused -------------------------------------------------------------------------------

    /** A `let` has nowhere to write back, and the error has to say so rather than discard the call. */
    @Test
    fun `a let receiver is refused by name`() {
        val e = errors(
            """
            graph "t"

            $ADD

            on start {
                val xs: LIST<INT> = []
                xs.add(value: 1)
            }
            """.trimIndent(),
        )
        assertTrue(e.any { "nowhere to write back" in it }, "expected the write-back refusal, got $e")
    }

    /**
     * Mutate AND return is refused.
     *
     * Not a limit of the signature but of the CALL SITE: `xs.add(3)` has one place to write back to, so a
     * function that both writes its receiver and hands something else back is a shape the statement form
     * could not express even if the declaration could.
     */
    @Test
    fun `a mutating extension cannot also return a value`() {
        val e = errors(
            """
            graph "t"

            export fn LIST<T>.grow(self, value: T) -> INT {
                self = _listWithItemAdded(list: self, value: value)
                return 1
            }

            on start {
                var xs: LIST<INT> = []
                xs.grow(value: 1)
            }
            """.trimIndent(),
        )
        assertTrue(e.any { "cannot also return" in it }, "expected the mutate-and-return refusal, got $e")
    }

    // ---- the printer -----------------------------------------------------------------------------------

    /**
     * Both spellings make the SAME graph, so only a marker can tell them apart — and the printer has to
     * give each one back as it was typed. That is the rule sugar in this language lives or dies by.
     */
    @Test
    fun `each spelling prints back as it was typed`() {
        val src = """
            graph "t"

            export fn LIST<T>.add(self, value: T) {
                self = _listWithItemAdded(list: self, value: value)
            }

            on start {
                var xs: LIST<INT> = []
                xs.add(value: 1)
                xs = xs.add(value: 2)
                say(message: _listCount(list: xs))
            }
        """.trimIndent()
        assertEquals(src, text.write(graphOf(src)).trim(), "round trip")
    }
}
