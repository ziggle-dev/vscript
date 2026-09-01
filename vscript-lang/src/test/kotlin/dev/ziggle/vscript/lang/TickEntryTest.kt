package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Severity
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.compile.errors
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `on tick` — the fourth entry kind.
 *
 * Three things have to hold, and each has already been got wrong once by one of the other three kinds:
 *
 * 1. It **round-trips**, including its position among the other entries. The printer used to concatenate
 *    entries by kind, so a file that opened with `on render` came back with it last — invisible while
 *    there were three kinds and someone happened to write them in that order, and a broken round trip the
 *    moment there are four.
 * 2. It is **kept out of [dev.ziggle.vscript.model.Graph.entries]**, the fiber set. A driven entry left in
 *    there runs twice over: once as the bounded pass the host pumps, and once as an ordinary fiber beside
 *    it committing the writes the pass just rolled back.
 * 3. It **cannot wait**, and that is refused before the script runs rather than discovered as a stall.
 */
class TickEntryTest {

    private val sayNode = hostNode(
        "test.say", "say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )

    /** Something BLOCKING, which is the whole point of the refusal test. */
    private val walkNode = hostNode(
        "test.walk", "walk", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
        hostKind = HostKind.BLOCKING,
    )
    private val catalog = NodeCatalog(listOf(sayNode, walkNode))

    private fun lower(src: String): dev.ziggle.vscript.model.Graph {
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse: ${parsed.errors.map { it.message }}")
        val low = Lower(catalog).lower(parsed.program)
        assertTrue(low.ok, "lower: ${low.errors}")
        return low.graph
    }

    private fun render(src: String): String {
        val g = lower(src)
        assertEquals(emptyList(), Validator(catalog).validate(g).errors(), "did not validate")
        return Print(catalog).print(g)
    }

    // ---- 1. round trip -------------------------------------------------------------------------------

    @Test
    fun `on tick round-trips`() {
        val src = """
            graph "probe"

            export var Ticks: INT = 0

            on tick {
                Ticks = Ticks + 1
            }
        """.trimIndent() + "\n"
        assertEquals(src, render(src))
        assertEquals(render(src), render(render(src)), "printing is not a fixed point")
    }

    /**
     * All SIX kinds, written in an order no grouping would produce.
     *
     * Deliberately scrambled against every natural grouping — declaration order, run order, the order the
     * `EntryKind` enum lists them in — so a printer that sorted by kind would have to get it wrong. Four
     * kinds was already enough to catch the regrouping bug once; six is where a hand-kept list of entry
     * types in the printer starts to go stale without anything noticing, which is why `Print.ENTRY_WORDS`
     * is now one map that `STATEMENT_FORMS` and `BODY_FORMS` are derived from.
     */
    @Test
    fun `entries keep the order they were written in`() {
        val out = render(
            """
            graph "probe"

            on render {
                say("draw")
            }

            on sleep {
                say("bye")
            }

            on tick {
                say("tick")
            }

            on start {
                say("go")
            }

            on wake {
                say("ready")
            }

            on stop {
                say("done")
            }
            """.trimIndent(),
        )
        // The words in the order they appear. Compared as a list rather than against the whole file
        // because the printer canonicalises the bodies (`say(message: …)`), and this is a claim about
        // order, not about spelling.
        assertEquals(
            listOf("render", "sleep", "tick", "start", "wake", "stop"),
            Regex("""^on (\w+) \{$""", RegexOption.MULTILINE).findAll(out).map { it.groupValues[1] }.toList(),
            "entries were regrouped by kind instead of kept in source order:\n$out",
        )
        assertEquals(out, render(out), "printing is not a fixed point")
    }

    /**
     * `on tick` round-trips — the §6.7 contract.
     *
     * Visibility on an entry has nowhere to live in the graph but a marker on the node, so this IS the
     * thing that makes the syntax admissible: sugar is allowed only if the printer can raise it back out.
     * Lose it and a private entry silently becomes public, which runs somebody's debug overlay inside
     * every document that imports them.
     */
    @Test
    fun `on round-trips`() {
        val src = """
            graph "probe"

            on tick {
                say(message: "mine")
            }

            on render {
                say(message: "everyone's")
            }
        """.trimIndent() + "\n"
        assertEquals(src, render(src))
        assertEquals(render(src), render(render(src)), "printing is not a fixed point")
    }

    /** `private` in front of anything that is not a declaration is still refused, with the four words. */
    @Test
    fun `private before a statement is refused`() {
        val parsed = Parser(
            Lexer(
                """
                graph "probe"

                private say("no")
                """.trimIndent(),
            ).lex(),
        ).parse()
        assertTrue(!parsed.ok, "`private say(…)` is not a declaration")
        assertTrue(
            parsed.errors.any { it.message.contains("'on'") },
            "the message should list `on` now that entries can be private: ${parsed.errors.map { it.message }}",
        )
    }

    // ---- 2. not a fiber ------------------------------------------------------------------------------

    @Test
    fun `a tick entry is not in the fiber set`() {
        val g = lower(
            """
            graph "probe"

            on start {
                say("go")
            }

            on tick {
                say("tick")
            }
            """.trimIndent(),
        )
        assertEquals(1, g.entries(catalog).size, "a tick entry must not be spawned as a fiber")
        assertEquals(BuiltinNodes.ENTRY, g.entries(catalog).single().type)
        assertEquals(1, g.tickEntries().size)
        assertEquals(2, g.allEntries(catalog).size)
    }

    /** And it still compiles and runs when the host asks for it by id, which is how the pass will drive it. */
    @Test
    fun `a tick entry compiles and runs on its own`() {
        val g = lower(
            """
            graph "probe"

            on tick {
                say("tick")
            }
            """.trimIndent(),
        )
        val out = ArrayList<Any?>()
        val hosts = HostRegistry()
        hosts.register("say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        drive(GraphCompiler(catalog, debug = false).compile(g, g.tickEntries().single().id), hosts, maxTicks = 200)
        assertEquals(listOf<Any?>("tick"), out)
    }

    /** A document whose ONLY entry is `on tick` runs perfectly well, so it must not be warned about. */
    @Test
    fun `a tick-only graph is not warned as having nothing to run`() {
        val g = lower(
            """
            graph "probe"

            on tick {
                say("tick")
            }
            """.trimIndent(),
        )
        val issues = Validator(catalog).validate(g)
        assertEquals(emptyList(), issues.errors())
        assertTrue(
            issues.none { it.severity == Severity.WARNING && it.message.contains("nothing will run") },
            "a tick-only graph does run: $issues",
        )
    }

    // ---- 3. cannot wait ------------------------------------------------------------------------------

    @Test
    fun `blocking inside on tick is refused`() {
        val g = lower(
            """
            graph "probe"

            on tick {
                walk()
            }
            """.trimIndent(),
        )
        val errors = Validator(catalog).validate(g).errors()
        assertEquals(1, errors.size, "expected exactly one refusal: $errors")
        assertTrue(errors.single().message.contains("On Tick"), errors.single().message)
    }

    /** Through a call, because putting the walk in a function is the first thing anyone would try next. */
    @Test
    fun `blocking reached through a function call is refused too`() {
        val g = lower(
            """
            graph "probe"

            export fn go() {
                walk()
            }

            on tick {
                go()
            }
            """.trimIndent(),
        )
        assertTrue(
            Validator(catalog).validate(g).errors().any { it.message.contains("On Tick") },
            "a rule that stops at the call boundary is one you get past by adding a function",
        )
    }

    @Test
    fun `delay inside on tick is refused`() {
        val g = lower(
            """
            graph "probe"

            on tick {
                delay(ms: 100)
            }
            """.trimIndent(),
        )
        assertTrue(
            Validator(catalog).validate(g).errors().any { it.message.contains("Delay inside On Tick") },
            "a tick has nothing to wait for",
        )
    }

    /** The same walk in the LOOP is fine — the refusal is about where, not about what. */
    @Test
    fun `blocking in on start is still allowed`() {
        val g = lower(
            """
            graph "probe"

            on start {
                walk()
            }
            """.trimIndent(),
        )
        assertEquals(emptyList(), Validator(catalog).validate(g).errors())
    }
}
