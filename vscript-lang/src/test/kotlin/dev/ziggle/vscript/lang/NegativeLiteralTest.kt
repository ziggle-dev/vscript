package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.compile.errors
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.GraphSource
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `-1` where a value belongs.
 *
 * There is no unary minus — `-x` would lower to `sub(0, x)` and become indistinguishable from an authored
 * `0 - x` on the way back out, so the plan rejected it (§6.7) on the grounds that negative LITERALS are
 * lexed as literals and that "covers the real cases".
 *
 * It did not cover `return -1`. The lexer decides a `-` is a sign by looking at the character before it,
 * and a keyword is spelled like a name and ends in a letter — so `return -1` looked exactly like
 * `count -1` and lexed as a subtraction, leaving `return` with an operator where its value should be.
 * The file did not parse, for a line nobody would think to question.
 */
class NegativeLiteralTest {

    private val catalog = NodeCatalog()

    private fun run(src: String): Int {
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse errors: ${parsed.errors.map { it.message }}")
        val low = Lower(catalog, source = GraphSource.NONE).lower(parsed.program)
        assertEquals(emptyList(), low.errors.map { it.message }, "lowering")
        assertEquals(emptyList(), Validator(catalog).validate(low.graph).errors().map { it.message })
        val entry = low.graph.entries(catalog).single { it.type == BuiltinNodes.ENTRY }
        val chunk = GraphCompiler(catalog, debug = false).compile(low.graph, entry.id)
        val result = drive(chunk, BuiltinHosts.registry())
        assertEquals(null, result.fiber.error?.message)
        val slot = chunk.slots.variables["Out"] ?: error("no Out")
        return (result.interpreter.globals.getOrNull(slot) as Number).toInt()
    }

    @Test
    fun `a negative literal may be returned`() {
        assertEquals(
            -1,
            run(
                """
                graph "probe"
                export fn nope() -> INT { return -1 }
                export var Out: INT = 0
                on start { Out = nope() }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `and may follow the other words a value can follow`() {
        assertEquals(
            -7,
            run(
                """
                graph "probe"
                export var Out: INT = 0
                on start {
                    var n = 0
                    while n > -3 {
                        n = n - 1
                    }
                    if n < -2 {
                        Out = -7
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `an ordinary name before a minus is still subtraction`() {
        // The rule is deliberately four keywords wide. `count -1` has to keep meaning `count - 1`, or
        // every expression that happens to end in a name would change meaning.
        assertEquals(
            9,
            run(
                """
                graph "probe"
                export var Out: INT = 0
                export var Ten: INT = 10
                on start { Out = Ten -1 }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a negative literal round trips as one`() {
        val src = """
            graph "probe"

            export var Out: INT = 0

            on start {
                Out = -5
            }
        """.trimIndent() + "\n"
        val low = Lower(catalog, source = GraphSource.NONE)
            .lower(Parser(Lexer(src).lex()).parse().program)
        assertEquals(src, Print(catalog, source = GraphSource.NONE).print(low.graph))
    }
}
