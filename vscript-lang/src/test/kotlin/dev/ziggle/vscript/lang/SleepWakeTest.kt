package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.EntryGroup
import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Severity
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.compile.errors
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.host.RunPhase
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `on wake` and `on sleep` — the fifth and sixth entry kinds, and the run's own shape.
 *
 * `on wake` gets ready, `on start` does the work, `on sleep` hands over. Four claims carry it, and each
 * of them is silent when it breaks, which is why they are pinned here rather than left to the corpus:
 *
 * 1. They **round-trip**, in source order among all six kinds. [TickEntryTest] owns the ordering case;
 *    this file owns the two new spellings.
 * 2. They are **kept out of the fiber set**, like `on stop` and for its reason: they start fibers at a
 *    moment of their own rather than alongside the work.
 * 3. They **may wait**, which is the entire point and the exact inverse of the rule `on render` and
 *    `on tick` live under. If that rule ever widens to cover these two, banking on the way out stops
 *    compiling and the feature is gone.
 * 4. The **initialiser prologue follows the wake**, because the wake runs first. Leave it on `on start`
 *    and a computed default re-seeds, at the head of the loop, on top of whatever the wake restored —
 *    which is not an error anywhere, just the wrong number from then on.
 */
class SleepWakeTest {

    private val sayNode = hostNode(
        "test.say", "say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )

    /** Something BLOCKING — banking on the way out is the thing these entries exist to allow. */
    private val walkNode = hostNode(
        "test.walk", "walk", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
        hostKind = HostKind.BLOCKING,
    )

    /** A pure INT source, so a computed variable default has something impure-free to be. */
    private val seedNode = hostNode(
        "test.seed", "seed", NodeKind.PURE,
        outputs = listOf(PinSpec("Result", PinType.INT)),
    )

    /** [NodeCatalog] registers the builtins itself, so this adds only what the fixtures need. */
    private val catalog = NodeCatalog(listOf(sayNode, walkNode, seedNode))

    /**
     * Run [chunks] one after another on ONE interpreter, as the host's phases do.
     *
     * `drive` cannot be used for this: it seeds the globals per call, so two of them are two runs and
     * `on start` would not see what `on wake` wrote — which is the very thing under test.
     */
    private fun runPhases(hosts: HostRegistry, vararg chunks: dev.ziggle.vscript.vm.Chunk) {
        val clock = dev.ziggle.vscript.vm.FakeClock()
        val interpreter = dev.ziggle.vscript.vm.Interpreter(hosts, clock)
        interpreter.resetGlobals(chunks.first().globals)
        val scheduler = dev.ziggle.vscript.vm.Scheduler(interpreter, clock)
        for (chunk in chunks) {
            val fiber = scheduler.spawn("phase", chunk)
            var ticks = 0
            while (!fiber.isFinished && ticks++ < 1_000) {
                scheduler.tick()
                if (!fiber.isFinished) scheduler.nextWakeMs()?.let { clock.now += it }
            }
            assertTrue(fiber.isFinished, "a phase did not finish: ${fiber.error?.message}")
        }
    }

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
    fun `on wake and on sleep round-trip`() {
        val src = """
            graph "probe"

            on wake {
                say(message: "ready")
            }

            on start {
                say(message: "working")
            }

            on sleep {
                say(message: "bye")
            }
        """.trimIndent() + "\n"
        assertEquals(src, render(src))
        assertEquals(render(src), render(render(src)), "printing is not a fixed point")
    }

    @Test
    fun `always on wake and always on sleep round-trip`() {
        val src = """
            graph "probe"

            always on wake {
                say(message: "ready")
            }

            always on sleep {
                say(message: "bye")
            }
        """.trimIndent() + "\n"
        assertEquals(src, render(src))
    }

    /**
     * The printer has no `else` any more, and this is what that buys.
     *
     * Before, an entry type missing from the spelling table printed back as `on start`. Nothing failed:
     * the file still parsed, still compiled, and quietly ran its preparation as its main loop. A map with
     * no default turns that into a crash naming the type.
     */
    @Test
    fun `every entry kind has a spelling`() {
        val entries = catalog.all.filter { it.kind == NodeKind.ENTRY }.map { it.type }.toSet()
        assertEquals(
            emptySet(),
            entries - BuiltinNodes.ENTRY_WORDS.keys,
            "an entry kind in the catalogue with no word in BuiltinNodes.ENTRY_WORDS would print as 'on start'",
        )
    }

    /** The word after `on` has to be one of six, and the message has to say which six. */
    @Test
    fun `an unknown event names the ones that exist`() {
        val parsed = Parser(
            Lexer(
                """
                graph "probe"

                on whenever {
                    say("no")
                }
                """.trimIndent(),
            ).lex(),
        ).parse()
        assertTrue(!parsed.ok, "'on whenever' is not an event")
        val message = parsed.errors.joinToString { it.message }
        assertTrue("'wake'" in message && "'sleep'" in message, "the message should list them: $message")
    }

    // ---- 2. not in the fiber set ---------------------------------------------------------------------

    @Test
    fun `wake and sleep entries are not spawned with the work`() {
        val g = lower(
            """
            graph "probe"

            on wake {
                say("ready")
            }

            on start {
                say("working")
            }

            on sleep {
                say("bye")
            }
            """.trimIndent(),
        )
        assertEquals(1, g.entries(catalog).size, "only the start entry is a fiber spawned at Run")
        assertEquals(BuiltinNodes.ENTRY, g.entries(catalog).single().type)
        assertEquals(1, g.wakeEntries().size)
        assertEquals(1, g.sleepEntries().size)
        assertEquals(3, g.allEntries(catalog).size)
    }

    /** The host drives each by id, so each has to compile and run on its own. */
    @Test
    fun `a wake entry compiles and runs on its own`() {
        val g = lower(
            """
            graph "probe"

            on wake {
                say("ready")
            }
            """.trimIndent(),
        )
        val out = ArrayList<Any?>()
        val hosts = HostRegistry()
        hosts.register("say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        drive(GraphCompiler(catalog, debug = false).compile(g, g.wakeEntries().single().id), hosts, maxTicks = 200)
        assertEquals(listOf<Any?>("ready"), out)
    }

    /**
     * A bank organiser: preparation and work, nothing saved, no `on sleep` at all.
     *
     * The common shape, and the one that proves `on wake` is not merely sleep's other half. It must not
     * be refused, and it must not be warned at.
     */
    @Test
    fun `a wake without a sleep is an ordinary script`() {
        val g = lower(
            """
            graph "probe"

            on wake {
                walk()
            }

            on start {
                say("organising")
            }
            """.trimIndent(),
        )
        val issues = Validator(catalog).validate(g)
        assertEquals(emptyList(), issues.errors(), "a wake-only script is ordinary")
        assertTrue(issues.none { it.severity == Severity.WARNING }, "and unremarkable: $issues")
    }

    /** A document whose only entry is `on wake` still has something to run. */
    @Test
    fun `a wake-only graph is not warned as having nothing to run`() {
        val g = lower(
            """
            graph "probe"

            on wake {
                say("ready")
            }
            """.trimIndent(),
        )
        val issues = Validator(catalog).validate(g)
        assertEquals(emptyList(), issues.errors())
        assertTrue(
            issues.none { it.message.contains("nothing will run") },
            "a wake-only graph does run: $issues",
        )
    }

    // ---- 3. they may wait ----------------------------------------------------------------------------

    /**
     * **The most important assertion in the file.**
     *
     * The exact inverse of `TickEntryTest`'s "blocking inside on tick is refused". `on sleep` exists so a
     * script can bank before it hands over, and `on wake` so it can walk to where the work is; both are
     * blocking verbs. If `checkDrivenEntries` ever grows to cover these two — an easy mistake, since they
     * ARE in `DRIVEN_ENTRIES`, which answers a different question — this fails and says why.
     */
    @Test
    fun `blocking inside on sleep and on wake is allowed`() {
        val g = lower(
            """
            graph "probe"

            on wake {
                walk()
            }

            on start {
                say("working")
            }

            on sleep {
                walk()
            }
            """.trimIndent(),
        )
        assertEquals(
            emptyList(),
            Validator(catalog).validate(g).errors(),
            "walking is the point of these two entries",
        )
    }

    @Test
    fun `delay inside on sleep is allowed`() {
        val g = lower(
            """
            graph "probe"

            on sleep {
                delay(ms: 600)
            }
            """.trimIndent(),
        )
        assertEquals(emptyList(), Validator(catalog).validate(g).errors())
    }

    // ---- 4. the initialiser prologue follows the wake ------------------------------------------------

    /**
     * A computed default holds by the wake's first statement, and is NOT applied a second time.
     *
     * The failure this pins is invisible: with the prologue left on `on start`, the wake writes `Seeded`,
     * the loop starts, and the declaration re-runs and puts the default back. Nothing errors — the script
     * simply behaves as though the wake never happened, which reads as a bug in whatever the wake was
     * restoring rather than in where a Set was emitted.
     */
    @Test
    fun `a computed default is applied before the wake, and only once`() {
        val out = ArrayList<Any?>()
        val hosts = HostRegistry()
        hosts.register("say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        hosts.register("seed", HostKind.INLINE, arity = 0, results = 1) { 1 }

        val g = lower(
            """
            graph "probe"

            var Seeded: INT = seed()

            on wake {
                say(Seeded)
                Seeded = 99
                say(Seeded)
            }

            on start {
                say(Seeded)
            }
            """.trimIndent(),
        )
        assertEquals(1, g.wakeEntries().size)
        // ONE ProgramBuilder across both groups, exactly as the host does it.
        val compiler = GraphCompiler(catalog, debug = false)
        val table = dev.ziggle.vscript.vm.ProgramBuilder()
        val wake = compiler.compileEntries(g, EntryGroup.WAKE, table).entries.single()
        val start = compiler.compileEntries(g, EntryGroup.START, table).entries.single()

        // The host runs them in phase order, so the test does too.
        runPhases(hosts, wake.chunk, start.chunk)

        // Ints, not Longs: INT is Long-WIDE but narrows to Int where it fits, and both of these do.
        assertEquals(
            listOf<Any?>(1, 99, 99),
            out,
            "the default must hold before the wake's first line, and must not be re-applied at the head " +
                "of the loop",
        )
    }

    /** With no wake, nothing moves: the prologue stays exactly where it has always been. */
    @Test
    fun `without a wake the prologue is still on the start entry`() {
        val g = lower(
            """
            graph "probe"

            var Seeded: INT = seed()

            on start {
                say(Seeded)
            }
            """.trimIndent(),
        )
        val start = g.entries(catalog).single()
        val inits = g.nodes.filter { it.type == BuiltinNodes.VAR_SET && it.literals.containsKey("@init") }
        assertEquals(1, inits.size, "the initialiser is still emitted")
        assertTrue(
            g.linksFrom(start.id, "Exec").any { it.toNode == inits.single().id },
            "and it is still the first thing the start entry does",
        )
    }

    // ---- 5. sleepRequested ---------------------------------------------------------------------------

    @Test
    fun `sleepRequested reads the run phase`() {
        val g = lower(
            """
            graph "probe"

            on start {
                say(sleepRequested())
            }
            """.trimIndent(),
        )
        val chunk = GraphCompiler(catalog, debug = false).compile(g, g.entries(catalog).single().id)

        val phase = RunPhase()
        val out = ArrayList<Any?>()
        fun run() {
            val hosts = BuiltinHosts.registry(phase = phase)
            hosts.register("say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
            drive(chunk, hosts, maxTicks = 200)
        }

        run()
        assertEquals(listOf<Any?>(false), out, "a fresh run has not been asked to sleep")

        out.clear()
        phase.requestSleep()
        run()
        assertEquals(listOf<Any?>(true), out, "and it answers once the host asks")

        out.clear()
        phase.clear()
        run()
        assertEquals(listOf<Any?>(false), out, "and a new run forgets the request")
    }

    /** It stays true through the handoff, so a library's handler may guard on it without skipping itself. */
    @Test
    fun `sleepRequested is still true inside on sleep`() {
        val g = lower(
            """
            graph "probe"

            on sleep {
                say(sleepRequested())
            }
            """.trimIndent(),
        )
        val chunk = GraphCompiler(catalog, debug = false).compile(g, g.sleepEntries().single().id)
        val phase = RunPhase().also { it.requestSleep() }
        val out = ArrayList<Any?>()
        val hosts = BuiltinHosts.registry(phase = phase)
        hosts.register("say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        drive(chunk, hosts, maxTicks = 200)
        assertEquals(listOf<Any?>(true), out)
    }

    @Test
    fun `a fresh run phase never sleeps`() {
        assertFalse(RunPhase().sleepRequested)
    }

    // ---- 6. the validator's nudge --------------------------------------------------------------------

    @Test
    fun `a sleep handler nobody polls for is warned`() {
        val g = lower(
            """
            graph "probe"

            on start {
                say("working")
            }

            on sleep {
                say("bye")
            }
            """.trimIndent(),
        )
        val issues = Validator(catalog).validate(g)
        assertEquals(emptyList(), issues.errors(), "it is a warning, not a refusal")
        assertTrue(
            issues.any { it.severity == Severity.WARNING && it.message.contains("nothing checks") },
            "a script that cannot notice the request should be told: $issues",
        )
    }

    @Test
    fun `polling anywhere in the document settles it`() {
        val g = lower(
            """
            graph "probe"

            fn done() -> BOOL = sleepRequested()

            on start {
                while !done() {
                    say("working")
                }
            }

            on sleep {
                say("bye")
            }
            """.trimIndent(),
        )
        val issues = Validator(catalog).validate(g)
        assertTrue(
            issues.none { it.message.contains("nothing checks") },
            "the poll is behind a function, which is ordinary: $issues",
        )
    }

    /**
     * A library contributing `always on sleep` and never polling is CORRECT, and must not be nudged.
     *
     * Deciding when to quiesce belongs to whoever owns the loop. A library saving the state it owns has
     * no loop and no business breaking anyone else's, so warning it would be advice it must not take.
     */
    @Test
    fun `a library that only saves its own state is not warned`() {
        val g = lower(
            """
            graph "probe"

            export var Trips: INT = 0

            always on sleep {
                say(Trips)
            }
            """.trimIndent(),
        )
        val issues = Validator(catalog).validate(g)
        assertTrue(
            issues.none { it.message.contains("nothing checks") },
            "a library has no loop to break out of: $issues",
        )
    }
    // ---- an IMPORTED document's wake, and its own defaults --------------------------------------------

    /**
     * A library's `always on wake` must run AFTER its own initialisers, not before them.
     *
     * **This is the single-document test's blind spot, and it cost a working feature.** The root's own
     * initialisers are emitted inline on whichever entry `Lower.initsGoOn` picks, so the case above holds
     * trivially. An IMPORTED document's live in a prologue CHUNK instead, and `prologueOrder()` is built
     * from `graph.imports` — the imports of whichever document's entry is being compiled. A library's own
     * entry therefore seeds everything it imports and never itself; only an importer's entry runs its
     * prologue.
     *
     * When the importer has no `on wake` of its own, that prologue lands on `on start` — i.e. AFTER every
     * imported wake handler has already run. So the library wakes with its variables unseeded, writes
     * something, and has it overwritten by its own defaults a moment later.
     *
     * Live cost: an orchestrator restored its saved stint in `on wake`, the root's `on start` re-seeded the
     * record to `name = ""`, and the first pass found nothing running and rolled a brand new stint. The log
     * read "resuming tithe (~27 of 28 min left)" and then "tithe for ~33 min", one millisecond apart.
     */
    @Test
    fun `an imported wake runs after that document's own defaults`() {
        val lib = lower(
            """
            graph "lib"

            var Seeded: INT = seed()

            export fn seen() -> INT = Seeded

            always on wake {
                Seeded = 99
            }

            export { Seeded, seen }
            """.trimIndent(),
        )
        val source = dev.ziggle.vscript.model.GraphSource { imp -> if (imp.ref == "lib") lib else null }
        val parsed = Parser(Lexer(
            """
            graph "root"

            import "lib"

            on start {
                say(seen())
            }
            """.trimIndent(),
        ).lex()).parse()
        assertTrue(parsed.ok, "parse: ${parsed.errors.map { it.message }}")
        val low = Lower(catalog, source = source).lower(parsed.program)
        assertTrue(low.ok, "lower: ${low.errors}")

        val out = ArrayList<Any?>()
        val hosts = BuiltinHosts.registry()
        hosts.register("say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        hosts.register("seed", HostKind.INLINE, arity = 0, results = 1) { 1 }

        val compiler = GraphCompiler(catalog, debug = false, source = source)
        val table = dev.ziggle.vscript.vm.ProgramBuilder()
        val wake = compiler.compileEntries(low.graph, EntryGroup.WAKE, table).entries.single()
        val start = compiler.compileEntries(low.graph, EntryGroup.START, table).entries.single()
        runPhases(hosts, wake.chunk, start.chunk)

        assertEquals(
            listOf<Any?>(99),
            out,
            "the wake's write must survive into the run — if this is the default (1), the library's " +
                "prologue re-seeded it on the START entry, after its own wake had already written",
        )
    }

}
