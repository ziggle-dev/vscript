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
 * `sequence { … } { … }` — the arms in order, then on.
 *
 * **The bug these exist for.** A `sequence` with a statement after it used to compile to an infinite loop.
 * The lowering collected the arms' open ends and handed those back as the statement's continuation — the
 * shape a `when` uses, which is right for a `when` (one arm runs, so every arm's end is a way to reach what
 * follows) and wrong here (all the arms run, so what follows runs once). The next statement was therefore
 * duplicated into every arm, and because the compiler lays the arms out consecutively in the instruction
 * stream, the second copy resolved to a backward jump. Driving it gave:
 *
 * ```
 * ran: FAILED — runaway fiber 'test': ran for 100 consecutive ticks without waiting
 * A = 1   After = 3906366   B = 3906365
 * ```
 *
 * The cure is a completion pin, exactly as a loop has: the node owns the continuation, not the arms. So the
 * test that matters is `the statement after a sequence runs once` — everything else here guards the shapes
 * that already worked.
 *
 * No script in `vscript-client/scripts/` uses the statement, which is the only reason this was latent.
 */
class SequenceTest {

    private val sayNode = hostNode(
        "test.say", "say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val catalog = NodeCatalog(listOf(sayNode))

    private fun graphOf(src: String): dev.ziggle.vscript.model.Graph {
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse: ${parsed.errors.map { it.message }}")
        val low = Lower(catalog).lower(parsed.program)
        assertTrue(low.ok, "lower: ${low.errors}")
        assertEquals(emptyList(), Validator(catalog).validate(low.graph).errors(), "did not validate")
        return low.graph
    }

    /**
     * What the script said, in order.
     *
     * `maxTicks` is deliberately small: the failure this file is about is a script that never stops, and a
     * generous budget would turn a hang into a slow test rather than a red one.
     */
    private fun said(src: String): List<Any?> {
        val g = graphOf(src)
        val out = ArrayList<Any?>()
        val hosts = BuiltinHosts.registry()
        hosts.register("say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        drive(
            GraphCompiler(catalog, debug = false).compile(g, g.entries(catalog).single().id),
            hosts, maxTicks = 200,
        )
        return out
    }

    // ---- what the arms do ----------------------------------------------------------------------------

    @Test
    fun `each arm runs once, in order`() {
        assertEquals(
            listOf("a", "b"),
            said(
                """
                graph "probe"
                on start {
                    sequence {
                        say("a")
                    } {
                        say("b")
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `four arms is the ceiling and all four run`() {
        assertEquals(
            listOf("a", "b", "c", "d"),
            said(
                """
                graph "probe"
                on start {
                    sequence {
                        say("a")
                    } {
                        say("b")
                    } {
                        say("c")
                    } {
                        say("d")
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    // ---- the continuation — the defect ---------------------------------------------------------------

    /** GAPS #1. Ran forever before the completion pin existed. */
    @Test
    fun `the statement after a sequence runs once, after every arm`() {
        assertEquals(
            listOf("a", "b", "after"),
            said(
                """
                graph "probe"
                on start {
                    sequence {
                        say("a")
                    } {
                        say("b")
                    }
                    say("after")
                }
                """.trimIndent(),
            ),
        )
    }

    /** Several statements after it, so the whole tail is reached rather than just the first. */
    @Test
    fun `everything after a sequence runs, once`() {
        assertEquals(
            listOf("a", "b", "one", "two", "three"),
            said(
                """
                graph "probe"
                on start {
                    sequence {
                        say("a")
                    } {
                        say("b")
                    }
                    say("one")
                    say("two")
                    say("three")
                }
                """.trimIndent(),
            ),
        )
    }

    /** The shape that was always correct: nothing follows, so nothing needs a continuation. */
    @Test
    fun `a sequence as the last statement still runs every arm`() {
        assertEquals(
            listOf("a", "b", "c"),
            said(
                """
                graph "probe"
                on start {
                    say("start")
                    sequence {
                        say("a")
                    } {
                        say("b")
                    } {
                        say("c")
                    }
                }
                """.trimIndent(),
            ).drop(1),
        )
    }

    @Test
    fun `a sequence inside a sequence continues into the outer one`() {
        assertEquals(
            listOf("a", "b", "inner-after", "c", "outer-after"),
            said(
                """
                graph "probe"
                on start {
                    sequence {
                        sequence {
                            say("a")
                        } {
                            say("b")
                        }
                        say("inner-after")
                    } {
                        say("c")
                    }
                    say("outer-after")
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a sequence in a loop body runs its arms once per pass`() {
        assertEquals(
            listOf("a", "b", "tail", "a", "b", "tail"),
            said(
                """
                graph "probe"
                on start {
                    for i in range(from: 0, to: 2) {
                        sequence {
                            say("a")
                        } {
                            say("b")
                        }
                        say("tail")
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * An arm that returns takes control with it, so the later arms AND the continuation are unreachable.
     *
     * The same rule Blueprints has, and the reason `sequence` in the compiler answers "did this terminate".
     */
    @Test
    fun `an arm that returns takes control with it`() {
        assertEquals(
            listOf("a"),
            said(
                """
                graph "probe"
                on start {
                    sequence {
                        say("a")
                        return
                    } {
                        say("b")
                    }
                    say("after")
                }
                """.trimIndent(),
            ),
        )
    }

    // ---- round trip ----------------------------------------------------------------------------------

    /**
     * The continuation has to come back OUT of the graph, or the printer silently deletes it.
     *
     * It used to: the arms had swallowed the statement, and the `sequence` branch of the printer answered
     * "nothing follows me".
     */
    @Test
    fun `the statement after a sequence survives a round trip`() {
        // Labelled arguments: the printer always writes them, so this is the canonical form and a
        // round trip from it is character-identical. See docs/CANONICAL_FORM.md.
        val src = """
            graph "probe"

            on start {
                sequence {
                    say(message: "a")
                } {
                    say(message: "b")
                }
                say(message: "after")
            }
        """.trimIndent()
        val printed = Print(catalog).print(graphOf(src))
        assertEquals(src.trim(), printed.trim(), "should print back as it was written")
    }

    @Test
    fun `a sequence with nothing after it round-trips`() {
        val src = """
            graph "probe"

            on start {
                sequence {
                    say(message: "a")
                } {
                    say(message: "b")
                }
            }
        """.trimIndent()
        assertEquals(src.trim(), Print(catalog).print(graphOf(src)).trim())
    }
}
