package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.compile.errors
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.model.GraphSource
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * An imported BLOCK-BODIED function called from inside a condition.
 *
 * A block body makes a function a STEP — it takes a place in the exec chain rather than being a value the
 * reader evaluates — and the fear is that a branch reads its result pin before the call has run, handing
 * the VM a Unit where a Bool should be. `whenStmt` had exactly that bug and was fixed by lowering the
 * subject BEFORE placing the node; `ifStmt` appears to do the same for its condition.
 *
 * Appears to. This runs it, because "the ordering looks right" is how the `when` bug survived review too.
 */
class StepInConditionTest {

    private val sayNode = hostNode(
        "test.say", "say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val catalog = NodeCatalog(listOf(sayNode))

    /** A library whose `abs` has a BLOCK body, which is what makes it a step. */
    private fun library(): dev.ziggle.vscript.model.Graph {
        val read = VsText(catalog).read(
            """
            graph "m"

            export fn abs(x: Int) -> Int {
                if x < 0 {
                    return -1 * x
                }
                return x
            }
            """.trimIndent(),
        )
        return assertNotNull(read.graph, "library should compile: ${read.errors.map { it.message }}")
    }

    private fun said(src: String): List<Any?> {
        val source = GraphSource { if (it.ref == "m") library() else null }
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse: ${parsed.errors.map { it.message }}")
        val low = Lower(catalog, source = source).lower(parsed.program)
        assertTrue(low.ok, "lower: ${low.errors}")
        assertEquals(emptyList(), Validator(catalog, source).validate(low.graph).errors(), "did not validate")

        val out = ArrayList<Any?>()
        val hosts = HostRegistry()
        hosts.register("say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        val entry = low.graph.entries(catalog).single()
        // The COMPILER needs the source too, not just Lower and the Validator: a Call across an import
        // is shaped from the callee's signature, and without it every pin on it goes missing.
        val compiler = GraphCompiler(catalog, debug = false, source = source)
        drive(compiler.compile(low.graph, entry.id), hosts, maxTicks = 800)
        return out
    }

    @Test
    fun `a step call on BOTH sides of a comparison in an if`() {
        assertEquals(
            listOf(1L, 2L),
            said(
                """
                graph "probe"

                import * as m from "m"

                export fn pick(a: Int, b: Int) -> Int {
                    if m::abs(a) >= m::abs(b) {
                        return 1
                    }
                    return 2
                }

                on start {
                    say(pick(a: -5, b: 3))
                    say(pick(a: 2, b: -9))
                }
                """.trimIndent(),
            ).map { if (it is Number) it.toLong() else it },
        )
    }

    /** The same call inside a larger arithmetic expression, which is the other place it is natural. */
    @Test
    fun `a step call inside an arithmetic expression`() {
        assertEquals(
            listOf(8L),
            said(
                """
                graph "probe"

                import * as m from "m"

                export fn total(a: Int, b: Int) -> Int {
                    return m::abs(a) + m::abs(b)
                }

                on start {
                    say(total(a: -5, b: 3))
                }
                """.trimIndent(),
            ).map { if (it is Number) it.toLong() else it },
        )
    }
}
