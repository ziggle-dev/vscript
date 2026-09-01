package dev.ziggle.vscript.compile

import dev.ziggle.vscript.lang.Lexer
import dev.ziggle.vscript.lang.Lower
import dev.ziggle.vscript.lang.Parser
import dev.ziggle.vscript.model.Graph
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
import kotlin.test.assertTrue

/**
 * A library's own entries, run when something imports it.
 *
 * Everything else about a document survives an import — its functions, types, enums, extensions and
 * variables. Its entries did not, so a library could only ever be CALLED; it could not watch, or draw, or
 * report on itself. `graahk` knew what its lure looked like and could not draw it, so the `on render` was
 * hoisted into `entry`, which knows nothing about lures.
 *
 * The three things that can go wrong here are each silent, which is why each is asserted:
 *
 *  - **Running twice.** A node id is unique only within a document, so anything keyed by id across a
 *    closure keeps one of two handlers; and a document reached through two aliases is still one document.
 *  - **Writing to the wrong slot.** Globals are one flat array for the run. If an imported entry's writes
 *    landed at the importer's base, the feature would corrupt state rather than fail.
 *  - **Ordering.** A library that sets up after its user has already read it is a race that shows up as
 *    an occasional null.
 */
class ImportedEntriesTest {

    private val sayNode = hostNode(
        "test.say", "say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val walkNode = hostNode(
        "test.walk", "walk", NodeKind.IMPURE,
        hostKind = HostKind.BLOCKING,
        inputs = listOf(PinSpec("Exec", PinType.EXEC)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val catalog = NodeCatalog(listOf(sayNode, walkNode))

    /** A source over named documents, so a test can build a whole closure from text. */
    private fun sourceOf(vararg docs: Pair<String, String>): GraphSource {
        val built = HashMap<String, Graph>()
        // Built lazily and cached, because a library that imports another has to resolve through the
        // same source it is being built by.
        lateinit var source: GraphSource
        source = GraphSource { imp ->
            val text = docs.firstOrNull { it.first == imp.ref }?.second ?: return@GraphSource null
            built.getOrPut(imp.ref) { lower(text, source) }
        }
        return source
    }

    private fun lower(src: String, source: GraphSource = GraphSource.NONE): Graph {
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse: ${parsed.errors.map { it.message }}")
        val low = Lower(catalog, source = source).lower(parsed.program)
        assertTrue(low.ok, "lower: ${low.errors}")
        return low.graph
    }

    /** Run every entry of [group] in order, and collect what was said. */
    private fun run(root: Graph, source: GraphSource, group: EntryGroup): List<Any?> {
        val compiled = GraphCompiler(catalog, debug = false, source = source).compileEntries(root, group)
        val out = ArrayList<Any?>()
        val hosts = HostRegistry()
        hosts.register("say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        for (entry in compiled.entries) drive(entry.chunk, hosts, maxTicks = 400)
        return out
    }

    // ---- an imported entry runs at all -----------------------------------------------------------

    @Test
    fun `an imported on render runs when the importer is run`() {
        val source = sourceOf(
            "lib" to """
                graph "lib"

                always on render {
                    say("lib drew")
                }
            """.trimIndent(),
        )
        val root = lower(
            """
            graph "probe"

            import * as lib from "lib"

            on render {
                say("root drew")
            }
            """.trimIndent(),
            source,
        )
        assertEquals(listOf<Any?>("lib drew", "root drew"), run(root, source, EntryGroup.RENDER))
    }

    // ---- ordering --------------------------------------------------------------------------------

    /** A library initialises before the thing that imported it can use it. */
    @Test
    fun `on start runs imports before importers`() {
        val source = sourceOf(
            "inner" to """
                graph "inner"

                always on start {
                    say("inner")
                }
            """.trimIndent(),
            "outer" to """
                graph "outer"

                import * as inner from "inner"

                always on start {
                    say("outer")
                }
            """.trimIndent(),
        )
        val root = lower(
            """
            graph "probe"

            import * as outer from "outer"

            on start {
                say("root")
            }
            """.trimIndent(),
            source,
        )
        assertEquals(listOf<Any?>("inner", "outer", "root"), run(root, source, EntryGroup.START))
    }

    /**
     * `on wake` orders like `on start` — and it is the one an importer should rely on.
     *
     * Untested until now, which is worth saying because it is load-bearing for the registry pattern: an
     * activity registers itself from `always on wake`, and the orchestrator's `on start` reads the
     * registry. If the wake did not settle inwards-first, an orchestrator would sometimes find a table
     * that was still being filled — the shape of a bug that only appears under a particular import order
     * and looks like "it works if I stop and start it".
     *
     * It is also the STRONGER of the two guarantees. `always on start` relies on a `Fiber.waitsFor` edge,
     * and `isSettled` counts a fiber waiting on the actuator as settled; the host drains the wake phase
     * one handler at a time, to completion, before any start fiber exists.
     */
    @Test
    fun `on wake runs imports before importers`() {
        val source = sourceOf(
            "inner" to """
                graph "inner"

                always on wake {
                    say("inner")
                }
            """.trimIndent(),
            "outer" to """
                graph "outer"

                import * as inner from "inner"

                always on wake {
                    say("outer")
                }
            """.trimIndent(),
        )
        val root = lower(
            """
            graph "probe"

            import * as outer from "outer"

            on wake {
                say("root")
            }
            """.trimIndent(),
            source,
        )
        assertEquals(listOf<Any?>("inner", "outer", "root"), run(root, source, EntryGroup.WAKE))
    }

    /** An unmarked `on wake` is a private preparation, exactly as an unmarked `on start` is a private run. */
    @Test
    fun `an on wake that is not always does not run for an importer`() {
        val source = sourceOf(
            "lib" to """
                graph "lib"

                on wake {
                    say("lib private")
                }

                always on wake {
                    say("lib shared")
                }
            """.trimIndent(),
        )
        val root = lower(
            """
            graph "probe"

            import * as lib from "lib"

            on wake {
                say("root")
            }
            """.trimIndent(),
            source,
        )
        assertEquals(listOf<Any?>("lib shared", "root"), run(root, source, EntryGroup.WAKE))
    }

    /** Winding down goes the other way, matching `on stop`: the importer puts itself away first. */
    @Test
    fun `on sleep runs importers before imports`() {
        val source = sourceOf(
            "lib" to """
                graph "lib"

                always on sleep {
                    say("lib")
                }
            """.trimIndent(),
        )
        val root = lower(
            """
            graph "probe"

            import * as lib from "lib"

            on sleep {
                say("root")
            }
            """.trimIndent(),
            source,
        )
        assertEquals(listOf<Any?>("root", "lib"), run(root, source, EntryGroup.SLEEP))
    }

    /** And tearing down goes the other way: the user finishes before what it depends on. */
    @Test
    fun `on stop runs importers before imports`() {
        val source = sourceOf(
            "lib" to """
                graph "lib"

                always on stop {
                    say("lib")
                }
            """.trimIndent(),
        )
        val root = lower(
            """
            graph "probe"

            import * as lib from "lib"

            on stop {
                say("root")
            }
            """.trimIndent(),
            source,
        )
        assertEquals(listOf<Any?>("root", "lib"), run(root, source, EntryGroup.STOP))
    }

    // ---- run once --------------------------------------------------------------------------------

    /**
     * One document, two aliases, one set of entries.
     *
     * The failure mode is not a crash — it is a handler that quietly runs twice per frame, which reads as
     * "the overlay flickers" or "the counter goes up in twos".
     */
    @Test
    fun `a document imported twice contributes its entries once`() {
        val source = sourceOf(
            "lib" to """
                graph "lib"

                always on render {
                    say("once")
                }
            """.trimIndent(),
        )
        val root = lower(
            """
            graph "probe"

            import * as a from "lib"
            import * as b from "lib"
            """.trimIndent(),
            source,
        )
        assertEquals(listOf<Any?>("once"), run(root, source, EntryGroup.RENDER))
    }

    // ---- globals ---------------------------------------------------------------------------------

    /**
     * An imported entry's writes land in ITS document's slot.
     *
     * Globals are one flat array for the whole run, indexed by a per-document base. If an imported entry
     * were compiled against the importer, it would read and write the importer's slots — and because both
     * documents here declare a `Count`, that would look like it worked while corrupting the other one.
     */
    @Test
    fun `an imported entry writes its own document's variable`() {
        val source = sourceOf(
            "lib" to """
                graph "lib"

                export var Count: INT = 0

                always on start {
                    Count = Count + 5
                    say(Count)
                }
            """.trimIndent(),
        )
        val root = lower(
            """
            graph "probe"

            import * as lib from "lib"

            export var Count: INT = 100

            on start {
                say(Count)
            }
            """.trimIndent(),
            source,
        )
        // The library counts from its OWN zero, and the root's 100 is untouched. Sharing a slot would
        // print 105 and 105.
        assertEquals(
            listOf<Any?>(5L, 100L),
            run(root, source, EntryGroup.START).map { if (it is Number) it.toLong() else it },
        )
    }

    // ---- on ------------------------------------------------------------------------------

    /**
     * `on` is the opt-out, and it means what `private` already means: not through an import.
     *
     * Once a library runs its own handlers it needs a way to say which of them are for whoever imports it
     * — the rest being a debug overlay that is useful while developing the library and noise everywhere
     * else. Opt-in, matching `export`: the plain form is the one for the document's own use.
     */
    @Test
    fun `an entry that is not always does not run for an importer`() {
        val source = sourceOf(
            "lib" to """
                graph "lib"

                on render {
                    say("lib debug")
                }

                always on render {
                    say("lib real")
                }
            """.trimIndent(),
        )
        val root = lower(
            """
            graph "probe"

            import * as lib from "lib"
            """.trimIndent(),
            source,
        )
        assertEquals(listOf<Any?>("lib real"), run(root, source, EntryGroup.RENDER))
    }

    /**
     * And it DOES run when that document is the one being run.
     *
     * `always` says what an IMPORTER inherits and nothing else. A document being run directly runs all of
     * its own handlers, or the plain form would be a way to disable one — which is not what `on` says
     * anywhere else, and would leave a library unable to have a handler it uses itself.
     */
    @Test
    fun `every entry runs when its own document is the one run`() {
        val lib = lower(
            """
            graph "lib"

            on render {
                say("lib debug")
            }

            on render {
                say("lib real")
            }
            """.trimIndent(),
        )
        assertEquals(
            listOf<Any?>("lib debug", "lib real"),
            run(lib, GraphSource.NONE, EntryGroup.RENDER),
        )
    }

    // ---- a broken library ------------------------------------------------------------------------

    /**
     * A library whose handler will not validate loses its handler, not the run.
     *
     * Until now an imported document's entry nodes were never compiled, so they were never checked — and
     * a blocking call inside a library's `on render` is exactly what the driven-entry rule exists to
     * refuse. Making it fatal instead would let a fault in a handler nobody uses stop a script that has
     * worked for months.
     */
    @Test
    fun `a library with a broken handler is skipped, not fatal`() {
        val source = sourceOf(
            "bad" to """
                graph "bad"

                always on render {
                    walk()
                }
            """.trimIndent(),
        )
        val root = lower(
            """
            graph "probe"

            import * as bad from "bad"

            on render {
                say("root drew")
            }
            """.trimIndent(),
            source,
        )
        val compiled = GraphCompiler(catalog, debug = false, source = source)
            .compileEntries(root, EntryGroup.RENDER)
        assertEquals(1, compiled.entries.size, "the root's own handler must survive")
        assertEquals(1, compiled.skipped.size, "the broken library must be reported, not dropped quietly")
        assertEquals("bad", compiled.skipped.single().document.name)
        assertTrue(
            compiled.skipped.single().errors.any { it.message.contains("On Render") },
            "the reason should be the real one: ${compiled.skipped.single().errors}",
        )
    }
}
