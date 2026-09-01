package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphSource
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.FiberState
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A registry one document owns, written by a second and read by a third.
 *
 * **The shape the hunter stack is built out of**, and the one that cannot be checked by reading: `rumor`
 * declares the table, each target document fills it in from an `always on start`, and `entry` looks a
 * runner up. Three documents, one cell, and every link between them decided at compile time — a slot
 * layout that disagreed by one would write and read different cells while every line looked correct.
 *
 * Registration is logged and the lookup still failed, which is exactly what a slot mismatch looks like
 * from the outside: both halves ran, neither was wrong, and they were not talking about the same map.
 */
class SharedRegistryTest {

    private val sayNode = hostNode(
        "test.say", "test.say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val catalog = NodeCatalog(listOf(sayNode))

    private fun libOf(vararg docs: Pair<String, String>): Pair<GraphSource, List<Graph>> {
        val built = ArrayList<Graph>()
        val source = GraphSource { imp -> built.firstOrNull { it.name == imp.ref } }
        for ((name, src) in docs) {
            val read = VsText(catalog, source).read(src)
            built += assertNotNull(
                read.graph,
                "$name should compile: ${read.errors.map { "${it.span.line}: ${it.message}" }}",
            )
        }
        return source to built
    }

    private class Run(val said: List<Any?>, val state: FiberState, val error: String?)

    /**
     * Run an entry group the way `ScriptRuntime` does: ONE interpreter, one globals array seeded from the
     * whole closure, and a fiber per entry in the order [EntryGroup.START] asks for.
     *
     * Driving the root's chunk alone — which is what a single-graph test does — never runs an imported
     * document's `always on start` at all, so a registry filled by one would look empty for a reason that
     * has nothing to do with the bug being chased.
     */
    private fun run(entry: String, vararg docs: Pair<String, String>): Run {
        val (source, _) = libOf(*docs)
        val r = VsText(catalog, source).read(entry)
        val g = assertNotNull(r.graph, "entry should compile: ${r.errors.map { "${it.span.line}: ${it.message}" }}")

        val said = ArrayList<Any?>()
        val hosts = BuiltinHosts.registry()
        hosts.register("test.say", HostKind.INLINE, arity = 1) { a -> said += a[0]; null }

        val table = dev.ziggle.vscript.vm.ProgramBuilder()
        val compiled = GraphCompiler(catalog, debug = false, source = source)
            .compileEntries(g, dev.ziggle.vscript.compile.EntryGroup.START, table)
        val closure = dev.ziggle.vscript.model.ImportClosure.resolve(g, source)

        val clock = dev.ziggle.vscript.vm.FakeClock()
        val interpreter = dev.ziggle.vscript.vm.Interpreter(hosts, clock)
        interpreter.resetGlobals(dev.ziggle.vscript.compile.startingGlobals(closure))
        val scheduler = dev.ziggle.vscript.vm.Scheduler(interpreter, clock)

        // Spawned exactly as `ScriptRuntime` does it: innermost-first, with the ROOT's fibers waiting for
        // the imported ones. Then ALL of them are pumped together, which is the part that matters — the
        // scheduler rotates its starting fiber every tick, so running them one at a time would hide the
        // ordering bug this exists to catch.
        val imported = ArrayList<dev.ziggle.vscript.vm.Fiber>()
        val spawned = compiled.entries.map { e ->
            scheduler.spawn(e.document.name, e.chunk).also {
                if (e.document !== g) imported += it else it.waitsFor += imported
            }
        }
        var ticks = 0
        while (spawned.any { !it.isFinished } && ticks < 20000) {
            ticks++
            scheduler.tick()
            if (spawned.any { it.state == FiberState.PAUSED || it.state == FiberState.FAILED }) break
            if (spawned.any { !it.isFinished }) scheduler.nextWakeMs()?.let { clock.now += it }
        }
        val last = spawned.firstOrNull { it.state == FiberState.FAILED } ?: spawned.lastOrNull()
        return Run(
            said.map { if (it is Number && it !is Double) it.toLong() else it },
            last?.state ?: FiberState.DONE,
            last?.error?.rawMessage,
        )
    }

    private val RUMOR = """
        graph "rumor"

        export enum Target(count: INT) { SnowyKnight(150), Glacialis(150) }

        export type Rumor { target: Target }

        var RunTargets: MAP<Target, fn(Rumor)> = _newMap()

        export fn register(target: Target, run: fn(Rumor)) {
            RunTargets = _mapWith(map: RunTargets, key: target, value: run)
        }

        export fn runner(target: Target) -> fn(Rumor) =
            _mapAt(map: RunTargets, key: target) ?: error(message: "no backing runnable")

        export fn registered() -> INT = _mapCount(map: RunTargets)
    """.trimIndent()

    private val BUTTERFLY = """
        graph "butterfly"

        import { register, Target, Rumor } from "rumor"

        export fn run(r: Rumor) {
            say(message: "ran")
        }

        always on start {
            register(target: Target.SnowyKnight, run: run)
        }
    """.trimIndent()

    /**
     * The whole arrangement, end to end.
     *
     * If the writer and the reader disagree about which cell `RunTargets` is, this fails with the very
     * message the live run produced — while the registration itself succeeded.
     */
    @Test
    fun `a table filled by an imported document is readable by the importer`() {
        val out = run(
            """
            graph "entry"

            import { Target, Rumor, runner } from "rumor"
            import * as butterfly from "butterfly"

            on start {
                val f = runner(target: Target.SnowyKnight)
                f(Rumor { target: Target.SnowyKnight })
            }
            """.trimIndent(),
            "rumor" to RUMOR,
            "butterfly" to BUTTERFLY,
        )
        assertEquals(FiberState.DONE, out.state, "the runner should have been found: ${out.error}")
        assertEquals(listOf("ran"), out.said)
    }

    /** The same question with the map's SIZE, which says whether the write landed where the read looks. */
    @Test
    fun `the importer sees the entry the imported document put in`() {
        val out = run(
            """
            graph "entry"

            import { registered } from "rumor"
            import * as butterfly from "butterfly"

            on start {
                say(message: registered())
            }
            """.trimIndent(),
            "rumor" to RUMOR,
            "butterfly" to BUTTERFLY,
        )
        assertEquals(FiberState.DONE, out.state, "${out.error}")
        assertEquals(listOf(1L), out.said, "one registration was made, and the reader must see it")
    }

    /** Two documents registering, so the table has to accumulate rather than be rebuilt. */
    @Test
    fun `two documents both register into one table`() {
        val out = run(
            """
            graph "entry"

            import { registered } from "rumor"
            import * as butterfly from "butterfly"
            import * as falconry from "falconry"

            on start {
                say(message: registered())
            }
            """.trimIndent(),
            "rumor" to RUMOR,
            "butterfly" to BUTTERFLY,
            "falconry" to """
                graph "falconry"

                import { register, Target, Rumor } from "rumor"

                export fn run(r: Rumor) {
                    say(message: "falconry")
                }

                always on start {
                    register(target: Target.Glacialis, run: run)
                }
            """.trimIndent(),
        )
        assertEquals(FiberState.DONE, out.state, "${out.error}")
        assertEquals(listOf(2L), out.said, "both registrations have to land in the same map")
    }

    /**
     * The key is made in the DECLARING document, the entry was made in an importing one.
     *
     * The live failure, and the one thing the cases above do not cover: `butterfly` writes with a member of
     * the enum it IMPORTED, and `rumor.target()` reads with a member of the enum it DECLARES. Both are
     * spelled `Target.SnowyKnight` and both print as `SnowyKnight` — so a map holding one and queried with
     * the other looks, in a debugger, exactly like a map that should have matched.
     */
    @Test
    fun `a key made inside the declaring document matches one made by an importer`() {
        val out = run(
            """
            graph "entry"

            import { pick, makeRumor } from "rumor"
            import * as butterfly from "butterfly"

            on start {
                val f = pick()
                f(Rumor { target: Target.SnowyKnight })
            }
            """.trimIndent().replace("Rumor { target: Target.SnowyKnight }", "makeRumor()"),
            "rumor" to (
                RUMOR + "\n\n" +
                    "export fn makeRumor() -> Rumor = Rumor { target: Target.SnowyKnight }\n\n" +
                    "export fn pick() -> fn(Rumor) =\n" +
                    "    _mapAt(map: RunTargets, key: Target.SnowyKnight) ?: error(message: \"no backing runnable\")"
                ),
            "butterfly" to BUTTERFLY,
        )
        assertEquals(FiberState.DONE, out.state, "one enum, one key, whoever wrote it: ${out.error}")
        assertEquals(listOf("ran"), out.said)
    }
}
