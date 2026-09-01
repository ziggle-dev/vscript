package dev.ziggle.vscript.lang

import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.NodeCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Braces are optional on every body, and the spelling you wrote is the one you get back.
 *
 * `if x foo()` and `if x { foo() }` are the same statements and lower to the same graph, so the printer has
 * to be told which was written — the §6.7 collision, answered the way `@mutable` answers `let` vs `var` and
 * `@wroteField` answers `s.f = v`. One marker, `@bare`, naming which bodies were braceless; nothing below the
 * printer reads it.
 *
 * **Parens around a condition need no marker and never did.** `(x)` is a grouped expression, so `if (x) foo()`
 * always parsed. They carry no meaning, so the printer gives back whatever precedence requires rather than
 * what was typed — the same normalisation `a + (b)` already gets. That is a deliberate difference from
 * braces: braces are a spelling of the same thing, parens are noise around it.
 */
class BareBodyTest {

    private val catalog = NodeCatalog()

    private fun keeps(text: String) {
        val vs = VsText(catalog)
        val read = vs.read(text)
        assertTrue(read.ok, "should compile: ${read.errors.map { "${it.span} ${it.message}" }}")
        val graph = assertNotNull(read.graph)
        assertEquals(text, vs.write(graph, read.comments).trim(), "the spelling changed on the way back out")
    }

    // ---- when ------------------------------------------------------------------------------------------

    /** The shape asked for: compact arms, and a block where a block is wanted. */
    @Test
    fun `a when mixes bare arms and a braced else`() {
        keeps(
            """
            graph "probe"

            export enum Phase { Chop, Bank }

            export var State: Phase = Phase.Chop

            on start {
                when State {
                    Phase.Chop -> log(message: "chopping")
                    Phase.Bank -> log(message: "banking")
                    else -> {
                        log(message: "something else")
                        log(message: "twice")
                    }
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a bare arm in the condition form round-trips`() {
        keeps(
            """
            graph "probe"

            export var Count: INT = 0

            on start {
                when {
                    Count > 10 -> log(message: "many")
                    else -> log(message: "few")
                }
            }
            """.trimIndent(),
        )
    }

    // ---- if --------------------------------------------------------------------------------------------

    @Test
    fun `a bare if and else round-trip`() {
        keeps(
            """
            graph "probe"

            export var Ready: BOOL = true

            on start {
                if Ready log(message: "yes")
                else log(message: "no")
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a bare then with a braced else round-trips`() {
        keeps(
            """
            graph "probe"

            export var Ready: BOOL = true

            on start {
                if Ready log(message: "yes")
                else {
                    log(message: "no")
                    log(message: "really no")
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a braced then with a bare else round-trips`() {
        keeps(
            """
            graph "probe"

            export var Ready: BOOL = true

            on start {
                if Ready {
                    log(message: "yes")
                    log(message: "really yes")
                } else log(message: "no")
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a bare if with no else round-trips`() {
        keeps(
            """
            graph "probe"

            export var Ready: BOOL = true

            on start {
                if Ready log(message: "yes")
                log(message: "after")
            }
            """.trimIndent(),
        )
    }

    /** An `else if` chain, still one statement in the text and a Branch chain in the graph. */
    @Test
    fun `a bare else if chain round-trips`() {
        keeps(
            """
            graph "probe"

            export var Count: INT = 0

            on start {
                if Count > 10 log(message: "many")
                else if Count > 0 log(message: "some")
                else log(message: "none")
            }
            """.trimIndent(),
        )
    }

    // ---- loops -----------------------------------------------------------------------------------------

    @Test
    fun `a bare while body round-trips`() {
        keeps(
            """
            graph "probe"

            export var Count: INT = 0

            on start {
                while Count < 10 Count = Count + 1
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a bare for body round-trips`() {
        keeps(
            """
            graph "probe"

            export var Total: INT = 0
            export var Values: LIST<INT> = [1, 2, 3]

            on start {
                for v in Values Total = Total + v
            }
            """.trimIndent(),
        )
    }

    // ---- parens ----------------------------------------------------------------------------------------

    /**
     * Parens are accepted and dropped, because they mean nothing.
     *
     * Deliberately NOT marked the way braces are. A brace is a spelling of the same statements; a paren is
     * grouping, and the printer already decides grouping from precedence — keeping the typed ones would mean
     * `((x))` had to come back too.
     */
    @Test
    fun `parens around a condition are accepted`() {
        val vs = VsText(catalog)
        val read = vs.read(
            """
            graph "probe"

            export var Ready: BOOL = true

            on start {
                if (Ready) log(message: "yes")
                else log(message: "no")
            }
            """.trimIndent(),
        )
        assertTrue(read.ok, "should compile: ${read.errors.map { it.message }}")
        val out = vs.write(assertNotNull(read.graph)).trim()
        assertTrue("if Ready log" in out, "parens should be dropped as grouping:\n$out")

        // And it settles there — the printed form is a fixpoint.
        val again = vs.read(out)
        assertEquals(out, vs.write(assertNotNull(again.graph)).trim(), "not a fixpoint")
    }

    @Test
    fun `parens keep working around a grouped condition`() {
        keeps(
            """
            graph "probe"

            export var Count: INT = 0

            on start {
                if (Count > 1 || Count < 0) && Count != 5 log(message: "odd")
            }
            """.trimIndent(),
        )
    }

    // ---- the safety rule -------------------------------------------------------------------------------

    /**
     * A body that is no longer ONE statement gets braces back, marker or not.
     *
     * The marker says what was written; the graph may have changed since, because the canvas can add a step
     * to a body the text spelled bare. Printing two statements where one can go produces text that reparses
     * as something else — the second would land outside the `if` — so the marker is honoured only while it
     * is still safe. Built by hand, since text cannot express the disagreement.
     */
    @Test
    fun `a bare marker is ignored once the body holds two statements`() {
        val vs = VsText(catalog)
        val read = vs.read(
            """
            graph "probe"

            export var Ready: BOOL = true

            on start {
                if Ready log(message: "one")
            }
            """.trimIndent(),
        )
        val graph = assertNotNull(read.graph, "${read.errors.map { it.message }}")
        val branch = graph.nodes.first { it.type == BuiltinNodes.BRANCH }
        assertTrue(BuiltinNodes.isBare(branch, "then"), "the marker should have been recorded")

        // Add a second statement to the `then` arm, as a canvas edit would.
        val logNode = graph.nodes.first { graph.linkInto(it.id, "Exec")?.fromPin == "True" }
        val extra = dev.ziggle.vscript.model.Node(
            id = graph.nodes.maxOf { it.id } + 1,
            type = logNode.type,
            literals = LinkedHashMap(logNode.literals),
        )
        val widened = graph.copy(
            nodes = graph.nodes + extra,
            links = graph.links + dev.ziggle.vscript.model.Link(
                id = graph.links.maxOf { it.id } + 1,
                fromNode = logNode.id, fromPin = "Exec", toNode = extra.id, toPin = "Exec",
            ),
        )
        val out = vs.write(widened).trim()
        assertTrue("if Ready {" in out, "two statements need braces:\n$out")

        // And what it printed reparses to the same two statements, which is the point of the guard.
        val back = vs.read(out)
        assertTrue(back.ok, "the braced form should compile: ${back.errors.map { it.message }}")
    }

    /**
     * A body that carries its own body always gets braces — the compact form would buy nothing.
     *
     * A nested `if` would have been the obvious case and is the wrong one: `if a { if b { … } }` collapses to
     * `if a && b { … }`, which is the short-circuit recognizer doing its job and says nothing about braces.
     * A loop cannot collapse into anything, so it is the honest test.
     */
    @Test
    fun `a body holding a loop is printed with braces`() {
        val vs = VsText(catalog)
        val text = """
            graph "probe"

            export var Ready: BOOL = true

            export var Count: INT = 0

            on start {
                if Ready while Count < 3 Count = Count + 1
            }
        """.trimIndent()
        val read = vs.read(text)
        assertTrue(read.ok, "should compile: ${read.errors.map { it.message }}")
        val out = vs.write(assertNotNull(read.graph)).trim()
        assertTrue("if Ready {" in out, "a nested if should be braced:\n$out")

        val again = vs.read(out)
        assertEquals(out, vs.write(assertNotNull(again.graph)).trim(), "not a fixpoint")
    }

    /** A canvas graph carries no marker, so it prints braced — nobody typed the compact form there. */
    @Test
    fun `a graph with no marker prints braced`() {
        val g = dev.ziggle.vscript.compile.graph {
            variable("Ready", dev.ziggle.vscript.model.PinType.BOOL, true)
            val start = node(BuiltinNodes.ENTRY)
            val get = node(BuiltinNodes.VAR_GET, variable = "Ready")
            val br = node(BuiltinNodes.BRANCH)
            val ret = node(BuiltinNodes.RETURN)
            link(start, "Exec", br, "Exec")
            link(get, "Value", br, "Condition")
            link(br, "True", ret, "Exec")
        }
        val out = VsText(catalog).write(g).trim()
        assertTrue("if Ready {" in out, "an unmarked branch should be braced:\n$out")
    }
}
