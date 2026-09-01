package dev.ziggle.vscript.compile

import dev.ziggle.vscript.lang.Lexer
import dev.ziggle.vscript.lang.Lower
import dev.ziggle.vscript.lang.Parser
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphSource
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.vm.Chunk
import dev.ziggle.vscript.vm.DriveResult
import dev.ziggle.vscript.vm.FakeClock
import dev.ziggle.vscript.vm.FiberState
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.Interpreter
import dev.ziggle.vscript.vm.ManualActuator
import dev.ziggle.vscript.vm.Scheduler
import dev.ziggle.vscript.vm.drive
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `var x: T = <expression>` in a document that somebody else runs.
 *
 * A literal default is data on the variable and is seeded across the whole closure before anything starts.
 * A computed one cannot be — a declaration is not a place a node can hang off — so `Lower` turns it into an
 * **initialiser prologue** at the head of that document's `on start`. A run executes the ROOT document's
 * entries only, which meant a library's prologue was never reached: the variable stayed at its type's zero
 * and the first arithmetic on it faulted with "expected a number, got null", pointing three frames into a
 * library that reads perfectly correctly. `docs/IMPORTED_INITIALISERS.md` is the write-up.
 *
 * The fix compiles each document's prologue as a sub-chunk and calls it from the head of the root's start
 * entry. **So the assertions here are about ORDER and about COUNT**, not only about values: a build that
 * ran the prologues in the wrong order, ran a shared one twice, or ran a library's whole `on start` while
 * it was at it would return the right number for the simplest case and be wrong about all three.
 */
class ImportedInitialisersTest {

    /** Records the number it is handed, so a test can assert what ran and in what order. */
    private val mark = hostNode(
        "test.mark", "mark", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("N", PinType.INT, default = 0)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Value", PinType.INT)),
    )

    /** The same, but [HostKind.BLOCKING] — it parks the fiber and waits on the actuator. */
    private val slowMark = hostNode(
        "test.slowMark", "slowMark", NodeKind.IMPURE, hostKind = HostKind.BLOCKING,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("N", PinType.INT, default = 0)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Value", PinType.INT)),
    )

    private val catalog = NodeCatalog(listOf(mark, slowMark))

    private class Host {
        /** Every `mark(n:)` that ran, in the order it ran. */
        val seen = ArrayList<Int>()

        fun registry(): HostRegistry = HostRegistry()
            .register("mark", HostKind.INLINE, arity = 1) { args -> (args[0] as Int).also { seen += it } }
            .register("slowMark", HostKind.BLOCKING, arity = 1) { args -> (args[0] as Int).also { seen += it } }
    }

    // ---- it runs at all ------------------------------------------------------------------------------

    @Test
    fun `an imported document's computed default is initialised before the importer runs`() {
        val lib = lower(
            "lib",
            """
            graph "lib"

            export val Seeded = 7

            export var seed: INT = Seeded * 3

            export fn Next() -> INT = seed + 1
            """.trimIndent(),
        )
        val main = lower(
            "main",
            """
            graph "main"

            import * as lib from "lib"

            export var Got: INT = 0

            on start {
                Got = lib::Next()
            }
            """.trimIndent(),
            listOf(lib),
        )

        val chunk = compile(main, listOf(lib))
        val run = run(chunk, HostRegistry())

        assertEquals(22, chunk.variable("Got", run), "seed is 21 by the time Next() reads it")
    }

    @Test
    fun `the importer runs the library's defaults and NOT its on start`() {
        // The load-bearing distinction. A prologue is the `@init` prefix of the start chain; what follows
        // it is the library's own body, and running that on the importer's behalf would fire a script
        // nobody asked for — while leaving the defaults uninitialised misses the point entirely.
        val lib = lower(
            "lib",
            """
            graph "lib"

            export var Seed: INT = mark(n: 1)
            export var Ran: INT = 0

            on start {
                Ran = 99
                mark(n: 7)
            }
            """.trimIndent(),
        )
        val main = lower(
            "main",
            """
            graph "main"

            import * as lib from "lib"

            export var Got: INT = 0

            on start {
                Got = lib::Seed
            }
            """.trimIndent(),
            listOf(lib),
        )

        val host = Host()
        val chunk = compile(main, listOf(lib))
        val run = run(chunk, host.registry())

        assertEquals(1, chunk.variable("Got", run), "the default ran")
        assertEquals(0, chunk.variable("lib::Ran", run), "the library's `on start` is not the importer's")
        assertEquals(listOf(1), host.seen, "only the prologue's own call should have run")
    }

    // ---- order, and how many times -------------------------------------------------------------------

    @Test
    fun `a chain of imports initialises deepest first`() {
        // `b`'s default READS `c`'s, so the order is not a preference — get it wrong and b sees zero.
        val c = lower("c", """
            graph "c"

            export var Base: INT = mark(n: 1)
        """.trimIndent())
        val b = lower("b", """
            graph "b"

            import * as c from "c"

            export var Doubled: INT = c::Base + mark(n: 2)
        """.trimIndent(), listOf(c))
        val a = lower("a", """
            graph "a"

            import * as b from "b"

            export var Got: INT = 0

            on start {
                Got = b::Doubled + mark(n: 3)
            }
        """.trimIndent(), listOf(b, c))

        val host = Host()
        val chunk = compile(a, listOf(b, c))
        val run = run(chunk, host.registry())

        assertEquals(listOf(1, 2, 3), host.seen, "c, then b, then the root's own first statement")
        assertEquals(6, chunk.variable("Got", run), "(1 + 2) + 3 — b saw c's value, not a zero")
    }

    @Test
    fun `a diamond initialises the shared document exactly once`() {
        // Twice would not merely be a wasted call: it would re-seed a variable an earlier prologue has
        // already read, so `b` and `c` would disagree about what `d` holds.
        val d = lower("d", """
            graph "d"

            export var Ticks: INT = mark(n: 1)
        """.trimIndent())
        val b = lower("b", """
            graph "b"

            import * as d from "d"

            export var Bv: INT = d::Ticks + 10
        """.trimIndent(), listOf(d))
        val c = lower("c", """
            graph "c"

            import * as d from "d"

            export var Cv: INT = d::Ticks + 20
        """.trimIndent(), listOf(d))
        val root = lower("root", """
            graph "root"

            import * as b from "b"
            import * as c from "c"

            export var Got: INT = 0

            on start {
                Got = b::Bv + c::Cv
            }
        """.trimIndent(), listOf(b, c, d))

        val host = Host()
        val chunk = compile(root, listOf(b, c, d))
        val run = run(chunk, host.registry())

        assertEquals(listOf(1), host.seen, "d is one document however many importers reach it")
        assertEquals(32, chunk.variable("Got", run), "(1 + 10) + (1 + 20)")
    }

    @Test
    @Timeout(10)
    fun `a blocking default finishes before the importer's first statement`() {
        // Nothing stops `var x = someBlockingCall()`, and a prologue spawned as a fiber alongside the
        // entry would let the entry race past it. As a CALL it cannot: the frame is on the stack, and the
        // entry does not reach its own first instruction until the prologue has returned.
        val lib = lower("lib", """
            graph "lib"

            export var Seed: INT = slowMark(n: 1)
        """.trimIndent())
        val main = lower("main", """
            graph "main"

            import * as lib from "lib"

            export var Got: INT = 0

            on start {
                Got = lib::Seed + mark(n: 2)
            }
        """.trimIndent(), listOf(lib))

        val host = Host()
        val chunk = compile(main, listOf(lib))

        val clock = FakeClock()
        val actuator = ManualActuator()
        val interpreter = Interpreter(host.registry(), clock, actuator)
        interpreter.resetGlobals(chunk.globals)
        val scheduler = Scheduler(interpreter, clock)
        val fiber = scheduler.spawn("main", chunk)

        scheduler.tick()
        assertEquals(FiberState.AWAITING_ACT, fiber.state, "the prologue's blocking call parks the fiber")
        assertEquals(emptyList(), host.seen, "nothing may have run past the prologue while it waits")

        var passes = 0
        while (!fiber.isFinished && passes++ < 50) {
            actuator.drain()
            scheduler.tick()
        }

        assertTrue(fiber.isFinished, "the run never completed: ${fiber.state} ${fiber.error?.message}")
        assertEquals(listOf(1, 2), host.seen, "the default finished first, then the entry's statement")
        val slot = chunk.slots.variables["Got"] ?: error("no slot for Got")
        assertEquals(3, interpreter.globals.getOrNull(slot), "1 from the blocking default, 2 from the entry")
    }

    // ---- what must NOT change ------------------------------------------------------------------------

    @Test
    fun `a library run directly still initialises itself`() {
        val lib = lower("lib", """
            graph "lib"

            export var Seed: INT = mark(n: 5)
            export var Out: INT = 0

            on start {
                Out = Seed * 2
            }
        """.trimIndent())

        val host = Host()
        val chunk = compile(lib, emptyList())
        val run = run(chunk, host.registry())

        assertEquals(5, chunk.variable("Seed", run), "its own prologue is inline, where Lower put it")
        assertEquals(10, chunk.variable("Out", run))
        assertEquals(listOf(5), host.seen, "and it runs once, not once for being the root and once as a call")
    }

    @Test
    fun `a document whose only entry is the synthesised one runs directly too`() {
        // No `on start` written, so `Lower` made one to hold the prologue. Running the document IS that
        // entry — the same node an importer reaches through a call.
        val lib = lower("lib", """
            graph "lib"

            export var Seed: INT = mark(n: 4) + 1
        """.trimIndent())

        val host = Host()
        val chunk = compile(lib, emptyList())
        val run = run(chunk, host.registry())

        assertEquals(5, chunk.variable("Seed", run))
        assertEquals(listOf(4), host.seen)
    }

    @Test
    fun `a literal default still needs no prologue, and gains no call`() {
        // The path that always worked: `ImportClosure.startingGlobals` seeds it before anything runs, so
        // there is nothing to compile and the root chunk should hold no sub-chunk at all.
        val lib = lower("lib", """
            graph "lib"

            export var N: INT = 7
        """.trimIndent())
        val main = lower("main", """
            graph "main"

            import * as lib from "lib"

            export var Got: INT = 0

            on start {
                Got = lib::N + 1
            }
        """.trimIndent(), listOf(lib))

        val chunk = compile(main, listOf(lib))
        val run = run(chunk, HostRegistry())

        assertEquals(8, chunk.variable("Got", run))
        assertTrue(chunk.program.isEmpty(), "a literal default is data — nothing should have been compiled")
    }

    @Test
    fun `a render entry does not re-run the imported defaults`() {
        // `on render` runs every frame. Seeding a library once per frame would quietly undo whatever it
        // had counted since, so the prologue calls belong on the START entry alone.
        val lib = lower("lib", """
            graph "lib"

            export var Seed: INT = mark(n: 1)
        """.trimIndent())
        val main = lower("main", """
            graph "main"

            import * as lib from "lib"

            export var Got: INT = 0

            on start {
                Got = lib::Seed
            }

            on render {
                Got = lib::Seed + 1
            }
        """.trimIndent(), listOf(lib))

        val render = main.renderEntries().single()
        val chunk = GraphCompiler(catalog, debug = false, source = GraphSource.of(listOf(lib)))
            .compile(main, render.id)

        assertTrue(chunk.program.isEmpty(), "a per-frame entry must carry no prologue call")
    }

    // ---- helpers -------------------------------------------------------------------------------------

    /** Lower one document from text, against the documents it can already see. */
    // ---- a library that also has an `on wake` ---------------------------------------------------------

    /**
     * A library whose initialisers moved to its WAKE entry is still initialised for an importer.
     *
     * **The half of the prologue that `on wake` broke.** `Lower.initsGoOn` puts a document's computed
     * defaults on the first entry that RUNS — the wake, when it has one — because the wake runs before the
     * loop and a default has to hold by then. But `prologueChunkFor` went looking for them on
     * `doc.entries(catalog)`, which is start entries only, so it walked from the wrong node, found no
     * `@init` prefix and built an EMPTY prologue.
     *
     * Nothing about that is loud: the importer runs, the library's variable sits at its type's zero, and
     * the first arithmetic on it faults three frames inside code that reads perfectly correctly. Which is
     * word for word the failure this whole test class was written about, arrived at by a new road.
     *
     * It matters now because registration wants `always on wake` — so every activity document in a
     * registry has both a wake entry and computed defaults, which is exactly this shape.
     */
    @Test
    fun `a library with an on wake still gets its computed defaults`() {
        val lib = lower(
            "lib",
            """
            graph "lib"

            export var Seed: INT = mark(n: 5)

            always on wake {
                mark(n: 6)
            }

            export fn Read() -> INT = Seed
            """.trimIndent(),
        )
        val main = lower(
            "main",
            """
            graph "main"

            import * as lib from "lib"

            export var Got: INT = 0

            on start {
                Got = lib::Read()
            }
            """.trimIndent(),
            listOf(lib),
        )

        val host = Host()
        val chunk = compile(main, listOf(lib))
        val run = run(chunk, host.registry())

        assertEquals(5, chunk.variable("lib::Seed", run), "the library's default never ran")
        assertEquals(5, chunk.variable("Got", run))
        // The prologue, and ONLY the prologue. The wake handler is a separate entry the host spawns; this
        // path must not drag it in on the importer's behalf any more than it drags in an `on start`.
        assertEquals(listOf(5), host.seen, "the prologue ran the wake handler's body too")
    }

    /** With no `on start` at all, the library has nowhere else its initialisers could have gone. */
    @Test
    fun `a library whose only entry is on wake still gets its computed defaults`() {
        val lib = lower(
            "lib",
            """
            graph "lib"

            export var Seed: INT = mark(n: 9)

            always on wake {
                mark(n: 1)
            }
            """.trimIndent(),
        )
        val main = lower(
            "main",
            """
            graph "main"

            import * as lib from "lib"

            export var Got: INT = 0

            on start {
                Got = lib::Seed
            }
            """.trimIndent(),
            listOf(lib),
        )

        val host = Host()
        val chunk = compile(main, listOf(lib))
        val run = run(chunk, host.registry())

        assertEquals(9, chunk.variable("Got", run), "a wake-only library had no prologue found at all")
        assertEquals(listOf(9), host.seen)
    }

    private fun lower(name: String, text: String, known: List<Graph> = emptyList()): Graph {
        val parsed = Parser(Lexer(text).lex()).parse()
        assertTrue(parsed.ok, "$name: parse errors ${parsed.errors.map { "${it.span} ${it.message}" }}")
        val result = Lower(catalog, source = GraphSource.of(known)).lower(parsed.program)
        assertTrue(result.errors.isEmpty(), "$name: ${result.errors.map { "${it.span} ${it.message}" }}")
        return result.graph
    }

    /** Validate and compile [root]'s start entry, failing with the diagnostics rather than a stack trace. */
    private fun compile(root: Graph, libraries: List<Graph>): Chunk {
        val source = GraphSource.of(libraries)
        val issues = Validator(catalog, source).validate(root).filter { it.severity == Severity.ERROR }
        assertEquals(emptyList(), issues.map { it.message }, "${root.name} should validate clean")
        val entry = root.entries(catalog).single { it.type == BuiltinNodes.ENTRY }
        return GraphCompiler(catalog, debug = false, source = source).compile(root, entry.id)
    }

    /**
     * Run [chunk] to completion, insisting that it got there.
     *
     * `drive` returns whatever state the fiber ended in, so a run that faulted inside a prologue would
     * leave every variable at its zero — and an assertion expecting a zero would pass for the wrong
     * reason. The VM's own message is worth far more than "expected 32, got 0".
     */
    private fun run(chunk: Chunk, hosts: HostRegistry): DriveResult {
        val result = drive(chunk, hosts)
        assertTrue(
            result.fiber.isFinished,
            "the run did not complete: ${result.fiber.state} ${result.fiber.error?.message.orEmpty()}",
        )
        return result
    }

    /** What [name] holds at the end of the run — including an imported one, spelled `alias::name`. */
    private fun Chunk.variable(name: String, run: DriveResult): Any? {
        val slot = slots.variables[name] ?: error("no slot for '$name' — the layout changed")
        return run.interpreter.globals.getOrNull(slot)
    }
}
