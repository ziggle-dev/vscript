package dev.ziggle.vscript.text

import dev.ziggle.vscript.lang.EntryKind
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.FakeClock
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.Interpreter
import dev.ziggle.vscript.vm.Scheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Does every document's `single` get seeded before anything can read it?
 *
 * A run seeds each document's computed globals with a PROLOGUE, called at the head of an entry, imports
 * before importers. A run also has more than one entry — a `always on wake` in a library runs before the
 * script's `on start` — and each of those is compiled by the document that declared it, so each carries
 * the prologues of ITS OWN subtree and not the run's.
 *
 * `Chunk.prologueOf` is what stops the second entry re-seeding what the first already wrote. So the order
 * a document is seeded in is decided by whichever ENTRY reached it first, not by the root's view of the
 * import graph — and this is what asks whether that still gets everybody seeded, and seeded before use.
 */
class PrologueOrderTest {

    private val STRING = TypeRef(PinType.STRING)

    private val natives = NativeTable(
        listOf(NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList())),
    )

    /** The deepest module: a `single` with literal fields, and a function that reads it. */
    private val deep = """
        graph "deep"
        type Store { ns: STRING }
        single Deep {
            growMs: INT = 4800,
            store: Store = Store { ns: "deep" },
        }
        export fn where() -> STRING = Deep.store.ns
        export fn howLong() -> INT = Deep.growMs
    """.trimIndent()

    /** The middle module, which imports the deep one and has its own `always on wake`. */
    private val middle = """
        graph "middle"
        import { where, howLong } from "deep"
        single Mid { label: STRING = "mid" }
        export fn describe() -> STRING = Mid.label + ":" + where() + ":" + howLong()
        always on wake { log(message: "middle woke") }
    """.trimIndent()

    private fun run(main: String, others: Map<String, String>): List<String> {
        val said = ArrayList<String>()
        val hosts = BuiltinHosts.registry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }

        val front = TextFrontEnd(natives, imports = TextSource.of(others))
        val c = front.compile(main)
        if (!c.ok) fail("did not compile: " + c.errors.joinToString { "${it.span} ${it.message}" })

        val clock = FakeClock()
        val interpreter = Interpreter(hosts, clock)
        interpreter.resetGlobals(c.globals)
        val scheduler = Scheduler(interpreter, clock)

        // The runtime's own order: every WAKE handler first, then the work — see `ScriptRuntime.begin`.
        for (e in c.entries[EntryKind.WAKE].orEmpty()) {
            val f = scheduler.spawn("wake", e.chunk)
            var n = 0
            while (!f.isFinished && n++ < 500) { scheduler.tick(); scheduler.nextWakeMs()?.let { clock.now += it } }
        }
        for (e in c.entries[EntryKind.START].orEmpty()) {
            val f = scheduler.spawn("start", e.chunk)
            var n = 0
            while (!f.isFinished && n++ < 500) { scheduler.tick(); scheduler.nextWakeMs()?.let { clock.now += it } }
            f.error?.let { said += "FAILED: " + it.rawMessage }
        }
        return said
    }

    @Test
    fun `a transitively imported single is seeded before the script runs`() {
        assertEquals(
            listOf("middle woke", "mid:deep:4800"),
            run(
                """
                graph "main"
                import { describe } from "middle"
                on start { log(message: describe()) }
                """.trimIndent(),
                mapOf("deep" to deep, "middle" to middle),
            ),
        )
    }

    /** The same, with the wake handler itself reaching into the deep module. */
    @Test
    fun `a wake handler that reads a deeper single sees it seeded`() {
        val waking = """
            graph "middle"
            import { where, howLong } from "deep"
            single Mid { label: STRING = "mid" }
            export fn describe() -> STRING = Mid.label + ":" + where() + ":" + howLong()
            always on wake { log(message: "wake sees " + where()) }
        """.trimIndent()
        assertEquals(
            listOf("wake sees deep", "mid:deep:4800"),
            run(
                """
                graph "main"
                import { describe } from "middle"
                on start { log(message: describe()) }
                """.trimIndent(),
                mapOf("deep" to deep, "middle" to waking),
            ),
        )
    }

    /**
     * Five documents that each declare `single Run`, which is what the corpus does.
     *
     * The farm-run activities each own a `Run` — herbs, seaweed, fruit, trees, standby — and nothing about
     * that is unusual: a `single` is private to its document, so the name is the document's business. What
     * it stresses is every table that is keyed by NAME rather than by the declaration: the slot map, the
     * defaults array, the prologue registry. Any one of them collapsing five documents into one leaves
     * four of them reading null out of somebody else's slot.
     */
    @Test
    fun `five documents may each have a single of the same name`() {
        val mods = HashMap<String, String>()
        for (i in 1..5) {
            mods["m$i"] = """
                graph "m$i"
                single Run {
                    growMs: INT = ${i * 1000},
                    store: STRING = "store$i",
                }
                export fn label$i() -> STRING = Run.store + "/" + Run.growMs
            """.trimIndent()
        }
        val imports = (1..5).joinToString("\n") { "import { label$it } from \"m$it\"" }
        val calls = (1..5).joinToString("\n    ") { "log(message: label$it())" }
        assertEquals(
            (1..5).map { "store$it/${it * 1000}" },
            run(
                """
                graph "main"
                $imports
                on start {
                    $calls
                }
                """.trimIndent(),
                mods,
            ),
        )
    }

    /** The same, reached through one intermediary — the corpus's actual shape (a roster in the middle). */
    @Test
    fun `five same-named singles reached through a roster document`() {
        val mods = HashMap<String, String>()
        for (i in 1..5) {
            mods["m$i"] = """
                graph "m$i"
                single Run { store: STRING = "store$i" }
                export fn label$i() -> STRING = Run.store
            """.trimIndent()
        }
        mods["roster"] = """
            graph "roster"
            ${(1..5).joinToString("\n") { "import { label$it } from \"m$it\"" }}
            export fn all() -> STRING = ${(1..5).joinToString(" + \",\" + ") { "label$it()" }}
        """.trimIndent()
        assertEquals(
            listOf("store1,store2,store3,store4,store5"),
            run(
                """
                graph "main"
                import { all } from "roster"
                on start { log(message: all()) }
                """.trimIndent(),
                mods,
            ),
        )
    }

    /**
     * A pass that seeds and is then abandoned must leave nothing seeded AND nothing recorded.
     *
     * **The bug that killed `rotation.vs`.** A render or tick pass is all-or-nothing: it gets a couple of
     * milliseconds, and whatever it wrote is staged and discarded if it runs over, so a frame that took
     * slightly too long cannot leave half a decision behind. A prologue reached inside such a pass had
     * its writes staged like any other — and its "this document is seeded" mark taken immediately and
     * kept. Discard the pass and the two disagree for the rest of the run: the variables are back at
     * their defaults and every later entry skips seeding them, because the record says it is done.
     *
     * What that looks like from the outside is nothing at all until something reads one, pages away, in a
     * third file: `GETFIELD on null, expected a record`.
     */
    @Test
    fun `a discarded pass un-records the prologues it was the first to reach`() {
        val lib = """
            graph "lib"
            single Cfg { store: STRING = "seeded" }
            export fn where() -> STRING = Cfg.store
        """.trimIndent()
        val main = """
            graph "main"
            import { where } from "lib"
            on start { log(message: "start sees " + where()) }
        """.trimIndent()

        val said = ArrayList<String>()
        val hosts = BuiltinHosts.registry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }

        val front = TextFrontEnd(natives, imports = TextSource.of(mapOf("lib" to lib)))
        val c = front.compile(main)
        if (!c.ok) fail("did not compile: " + c.errors.joinToString { "${it.span} ${it.message}" })

        val clock = FakeClock()
        val interpreter = Interpreter(hosts, clock)
        interpreter.resetGlobals(c.globals)
        val scheduler = Scheduler(interpreter, clock)

        // A pass that reaches the prologue and is then thrown away — the render budget being blown.
        val entry = c.entries[EntryKind.START].orEmpty().first().chunk
        interpreter.beginStaged()
        val doomed = scheduler.spawn("doomed", entry)
        scheduler.tick()
        scheduler.kill(doomed)
        interpreter.discardPending()
        said.clear()

        // Now the real run. It must seed, because nothing actually did.
        val f = scheduler.spawn("start", entry)
        var n = 0
        while (!f.isFinished && n++ < 500) { scheduler.tick(); scheduler.nextWakeMs()?.let { clock.now += it } }
        f.error?.let { said += "FAILED: " + it.rawMessage }
        assertEquals(listOf("start sees seeded"), said)
    }

    /** And a pass that COMPLETES still seeds once — the guard has to keep doing its own job. */
    @Test
    fun `a committed pass still records its prologues`() {
        val lib = """
            graph "lib"
            single Cfg { n: INT = 1 }
            export fn bump() -> INT {
                Cfg.n = Cfg.n + 10
                return Cfg.n
            }
        """.trimIndent()
        val main = """
            graph "main"
            import { bump } from "lib"
            on start { log(message: "" + bump()) }
        """.trimIndent()

        val said = ArrayList<String>()
        val hosts = BuiltinHosts.registry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }

        val front = TextFrontEnd(natives, imports = TextSource.of(mapOf("lib" to lib)))
        val c = front.compile(main)
        if (!c.ok) fail("did not compile: " + c.errors.joinToString { "${it.span} ${it.message}" })

        val clock = FakeClock()
        val interpreter = Interpreter(hosts, clock)
        interpreter.resetGlobals(c.globals)
        val scheduler = Scheduler(interpreter, clock)
        val entry = c.entries[EntryKind.START].orEmpty().first().chunk

        interpreter.beginStaged()
        val first = scheduler.spawn("first", entry)
        var n = 0
        while (!first.isFinished && n++ < 500) { scheduler.tick(); scheduler.nextWakeMs()?.let { clock.now += it } }
        interpreter.commitPending()

        val second = scheduler.spawn("second", entry)
        n = 0
        while (!second.isFinished && n++ < 500) { scheduler.tick(); scheduler.nextWakeMs()?.let { clock.now += it } }

        // 11 then 21: the second run must NOT re-seed `Cfg.n` back to 1.
        assertEquals(listOf("11", "21"), said)
    }
}
