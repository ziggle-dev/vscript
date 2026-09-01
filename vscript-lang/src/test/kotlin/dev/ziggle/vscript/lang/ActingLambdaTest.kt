package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.Severity
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.model.GraphSource
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `xs.each { … }` — a lambda that acts, and the three things that had to be true for it to be writable.
 *
 * It compiles (GAPS 27), it says nothing about a result it does not have, and it comes BACK. The last was
 * the one that nearly shipped broken: the printer reproduces a lambda's body by reading through the box's
 * Result pin, and an acting body does not go there — so it round-tripped to `{ }` and lost what was
 * written, which a compile-only test would never have seen.
 */
class ActingLambdaTest {

    private val say = hostNode(
        "test.say", "say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val catalog = NodeCatalog(listOf(say))

    private val src = """
        graph "probe"

        fn LIST<T>.each(self, f: fn(T)) {
            for item in self {
                f(item)
            }
        }

        on start {
            [1, 2, 3].each { say(message: it) }
        }
    """.trimIndent()

    private fun lowered() = Lower(catalog, source = GraphSource.of(emptyList()))
        .lower(Parser(Lexer(src).lex()).parse().program)

    @Test
    fun `an acting lambda lowers and validates clean`() {
        val low = lowered()
        assertEquals(emptyList(), low.errors.map { it.message })
        assertEquals(
            emptyList(),
            Validator(catalog, GraphSource.of(emptyList())).validate(low.graph)
                .filter { it.severity == Severity.ERROR }.map { it.message },
        )
    }

    /**
     * And says nothing about a Result it does not have.
     *
     * A `fn(T)` has no result, and a synthesised function that declared one anyway had a pin nothing fed —
     * so every acting lambda carried "'Result' has nothing feeding it, it will arrive as null". Harmless
     * while an acting body was impossible and the pin was always fed by the body's value; not harmless
     * once one could be written.
     */
    @Test
    fun `an acting lambda warns about nothing`() {
        val low = lowered()
        val issues = Validator(catalog, GraphSource.of(emptyList())).validate(low.graph)
        assertEquals(emptyList(), issues.map { "${it.severity}: ${it.message}" })
    }

    @Test
    fun `an acting lambda comes back as it was written`() {
        assertEquals(src, VsText(catalog).write(lowered().graph).trim())
    }
}
