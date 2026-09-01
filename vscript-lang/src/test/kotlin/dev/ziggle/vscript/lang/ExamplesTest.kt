package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Severity
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphSource
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The worked examples, taken all the way to a run.
 *
 * **These are not parse fixtures.** `ScriptsParseTest` stops at the parser because its corpus is real game
 * scripts and lowering those needs the client's catalogue. These three documents use no game nodes at all
 * — imports, records, visibility, functions, recursion, lists and control flow are LANGUAGE features — so
 * they lower, validate, compile and execute here, and every assertion below is a value the VM actually
 * produced.
 *
 * That distinction is the point. Imports had no end-to-end coverage: a document could resolve, shape a
 * Call node's pins and still be given the wrong globals slot, and nothing would have said so. Reading the
 * numbers back out of the run is what closes that.
 *
 * The examples double as the language's worked documentation, which is why they are commented for a reader
 * rather than trimmed to what the assertions need.
 */
class ExamplesTest {

    private val catalog = NodeCatalog()

    private fun source(name: String): String =
        javaClass.getResource("/vs/examples/$name.vs")?.readText()
            ?: error("missing example /vs/examples/$name.vs — it is checked in under src/test/resources")

    /** Lower one document against [known], failing loudly with whatever went wrong. */
    private fun lower(name: String, known: List<Graph> = emptyList()): Graph {
        val text = source(name)
        val parsed = Parser(Lexer(text).lex()).parse()
        assertTrue(parsed.ok, "$name: parse errors ${parsed.errors.map { "${it.span} ${it.message}" }}")

        val result = Lower(catalog, source = GraphSource.of(known)).lower(parsed.program)
        assertTrue(
            result.errors.isEmpty(),
            "$name: lowering errors ${result.errors.map { "${it.span} ${it.message}" }}",
        )
        return result.graph
    }

    /** Every document, in dependency order — `geometry` knows nobody, `tour` knows both. */
    private fun documents(): Triple<Graph, Graph, Graph> {
        val geometry = lower("geometry")
        val tally = lower("tally", listOf(geometry))
        val tour = lower("tour", listOf(geometry, tally))
        return Triple(geometry, tally, tour)
    }

    /**
     * The features that landed after the phase list closed, run end to end.
     *
     * Worth running rather than merely parsing: an extension is the existing Call node with `self` wired
     * in, a `when` is a jump chain, and a by-name destructure moves which PIN a local reads. All three are
     * the kind of thing that compiles perfectly while binding the wrong wire, and only a value read back
     * out of the VM says otherwise.
     */
    @Test
    fun `the patrol runs, and the newer constructs produce the values they should`() {
        val patrol = lower("patrol")
        val issues = Validator(catalog).validate(patrol).filter { it.severity == Severity.ERROR }
        assertEquals(emptyList(), issues.map { it.message }, "patrol should validate clean")

        val entry = patrol.entries(catalog).single { it.type == dev.ziggle.vscript.model.BuiltinNodes.ENTRY }
        val chunk = GraphCompiler(catalog, debug = false).compile(patrol, entry.id)
        val result = drive(chunk, HostRegistry())
        // DONE, not `isFinished` — a FAILED fiber is finished too, and asserting the weaker thing let a
        // run that died on a missing host report every variable at its default and pass.
        assertEquals(
            dev.ziggle.vscript.vm.FiberState.DONE, result.fiber.state,
            "the patrol did not run to completion: ${result.fiber.error}",
        )

        fun variable(name: String): Any? {
            val slot = chunk.slots.variables[name] ?: error("no slot for '$name' — the layout changed")
            return result.interpreter.globals.getOrNull(slot)
        }

        // Destructured BY NAME and out of order: `Cost` is the third output, `Label` the second.
        assertEquals("step 3", variable("Label"), "`label` should take the Label pin, not the first one")
        assertEquals(6, variable("Cost"), "`cost: Cost` should take the Cost pin — 3 doubled")

        // Extensions, chained, with an argument beside the receiver.
        assertEquals(12, variable("Clamped"), "9 doubled is 18, clamped to 12")

        // A loop over the extension: 0 + 2 + 4.
        assertEquals(6, variable("Steps"), "the loop should have doubled and summed 0, 1, 2")

        // `when` over an enum, then `when` over conditions, then a braceless `if`.
        assertEquals("fighting (steady) !", variable("Story"))
    }

    // ---- it all holds together ------------------------------------------------------------------------

    @Test
    fun `every example validates clean`() {
        val (geometry, tally, tour) = documents()
        for ((name, graph) in listOf("geometry" to geometry, "tally" to tally, "tour" to tour)) {
            val issues = Validator(catalog, GraphSource.of(listOf(geometry, tally)))
                .validate(graph)
                .filter { it.severity == Severity.ERROR }
            assertEquals(emptyList(), issues.map { it.message }, "$name should validate clean")
        }
    }

    @Test
    fun `the tour runs, and every feature it exercises produces the value it should`() {
        val (geometry, tally, tour) = documents()
        val source = GraphSource.of(listOf(geometry, tally))
        val entry = tour.entries(catalog).single { it.type == dev.ziggle.vscript.model.BuiltinNodes.ENTRY }
        val chunk = GraphCompiler(catalog, debug = false, source = source).compile(tour, entry.id)

        val result = drive(chunk, HostRegistry())
        assertTrue(result.fiber.isFinished, "the tour did not run to completion")

        fun variable(name: String): Any? {
            val slot = chunk.slots.variables[name] ?: error("no slot for '$name' — the layout changed")
            return result.interpreter.globals.getOrNull(slot)
        }

        // A record replaced through `with`, not written through.
        assertEquals(10, variable("Moved"), "`far with { x: 10 }` should give a point at x = 10")

        // A call into an imported document's function, which itself reads two imported record fields.
        assertEquals(7, variable("Chebyshev"), "chebyshev((0,0),(3,7)) is max(3, 7)")

        // Two documents deep: tour -> tally -> geometry.
        assertEquals(10, variable("Manhattan"), "manhattan((0,0),(3,7)) is 3 + 7, reached through tally")

        assertEquals(15, variable("Recursed"), "sumTo(5) is 1+2+3+4+5")

        // Writes a variable belonging to ANOTHER document, and reads back what it now holds. This is the
        // one that would silently give the wrong answer if the globals layout collided.
        assertEquals(4, variable("Shared"), "tally::add(4) should return tally's own Total")

        assertEquals(5, variable("Picked"), "sizes[1] of [2, 5, 1]")
        assertEquals("first", variable("Longest"), "the `else if` arm should have been taken")

        // bounds(9, 2) is (2, 9), so high * 100 + low is 902; the loop then steps down to 202 and breaks.
        assertEquals(202, variable("Total"), "the while loop should break at the first value below 300")

        // 2*1 + 5*2 + 1*3 from the for loop, six thousand from the six iterations that did NOT continue,
        // and three from the sequence's two blocks.
        assertEquals(15 + 6000 + 3, variable("Steps"))
    }

    // ---- what must NOT work ---------------------------------------------------------------------------

    @Test
    fun `a private declaration is not reachable across an import`() {
        val geometry = lower("geometry")
        val text = """
            graph "peeker"
            import * as geo from "geometry"
            export var N: INT = 0
            on start { N = geo::secret() }
        """.trimIndent()

        val parsed = Parser(Lexer(text).lex()).parse()
        assertTrue(parsed.ok, "parse errors ${parsed.errors.map { it.message }}")
        val lowered = Lower(catalog, source = GraphSource.of(listOf(geometry))).lower(parsed.program)
        val issues = Validator(catalog, GraphSource.of(listOf(geometry)))
            .validate(lowered.graph)
            .filter { it.severity == Severity.ERROR }

        assertTrue(
            lowered.errors.isNotEmpty() || issues.isNotEmpty(),
            "`fn secret` must not be callable from another document — that is what private is for",
        )
    }

    @Test
    fun `an import naming a document nobody provides is reported`() {
        val text = """
            graph "lonely"
            import * as missing from "nowhere"
            export var N: INT = 0
            on start { N = missing::whatever() }
        """.trimIndent()

        val parsed = Parser(Lexer(text).lex()).parse()
        assertTrue(parsed.ok)
        val lowered = Lower(catalog, source = GraphSource.NONE).lower(parsed.program)
        val issues = Validator(catalog, GraphSource.NONE)
            .validate(lowered.graph)
            .filter { it.severity == Severity.ERROR }

        assertTrue(
            lowered.errors.isNotEmpty() || issues.isNotEmpty(),
            "an unresolved import has to be reported rather than compiling to nothing",
        )
    }

    // ---- the examples are documentation, and have to stay readable ------------------------------------

    @Test
    fun `the examples keep their commentary`() {
        for (name in listOf("geometry", "tally", "tour")) {
            val comments = Lexer(source(name)).lex().count { it.type == TokenType.COMMENT }
            assertTrue(comments > 3, "$name reads as documentation, so it should be commented")
        }
    }

    @Test
    fun `the examples use doc comments, which the IDE renders on hover`() {
        val docs = listOf("geometry", "tally", "tour").sumOf { name ->
            Lexer(source(name)).lex().count { it.type == TokenType.COMMENT && it.text.startsWith("/**") }
        }
        assertTrue(docs >= 4, "expected `/** … */` on the exported declarations, found $docs")
    }

    /**
     * Purity crosses a document boundary, in both directions that matter.
     *
     * `tour.lengthOf` is expression-bodied and calls the imported `geo::chebyshev`; `tally.spanOf` is
     * expression-bodied and calls `geo::manhattan`. Both were refused until `isPureFunction` learned to
     * ask the DECLARING document rather than walking whatever nodes it had been handed — an imported
     * pure function could be called from a block body and from nowhere else.
     */
    @Test
    fun `an expression-bodied function may call an imported one`() {
        val (geometry, tally, tour) = documents()
        val source = GraphSource.of(listOf(geometry, tally))

        for ((name, graph) in listOf("tally" to tally, "tour" to tour)) {
            val issues = Validator(catalog, source).validate(graph)
                .filter { it.severity == Severity.ERROR }
            assertEquals(emptyList(), issues.map { it.message }, "$name should validate clean")
        }
    }

    @Test
    fun `an imported document's computed default is initialised before the importer runs`() {
        // A `var x = <expression>` in a LIBRARY. Its initialiser prologue is emitted into that document,
        // but the run starts from the importer's `on start` — so until the compiler learned to call
        // imported prologues, the variable was still null when the library's own function read it and the
        // VM reported "expected a number, got null" from inside code that looks correct.
        val lib = lowerText(
            "lib",
            """
            graph "lib"

            export val Seeded = 7

            export var seed: INT = Seeded * 3

            export fn Next() -> INT = seed + 1
            """.trimIndent(),
        )
        val main = lowerText(
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

        val source = GraphSource.of(listOf(lib))
        val entry = main.entries(catalog).single { it.type == dev.ziggle.vscript.model.BuiltinNodes.ENTRY }
        val chunk = GraphCompiler(catalog, debug = false, source = source).compile(main, entry.id)
        val result = drive(chunk, HostRegistry())

        val slot = chunk.slots.variables["Got"] ?: error("no slot for Got")
        assertEquals(
            22,
            result.interpreter.globals.getOrNull(slot),
            "seed should be 21 by the time Next() reads it — see ImportedInitialisersTest",
        )
    }

    /** Lower one document from text, against documents already lowered. */
    private fun lowerText(name: String, text: String, known: List<Graph> = emptyList()): Graph {
        val parsed = Parser(Lexer(text).lex()).parse()
        assertTrue(parsed.ok, "$name: ${parsed.errors.map { it.message }}")
        val result = Lower(catalog, source = GraphSource.of(known)).lower(parsed.program)
        assertTrue(result.errors.isEmpty(), "$name: ${result.errors.map { it.message }}")
        return result.graph
    }

    // ---- the module system, worked -------------------------------------------------------------------

    /** `geometry` knows nobody, `shapes` knows it, `toolkit` forwards both, `modules` uses the front door. */
    private fun modules(): List<Graph> {
        val geometry = lower("geometry")
        val shapes = lower("shapes", listOf(geometry))
        val toolkit = lower("toolkit", listOf(geometry, shapes))
        val modules = lower("modules", listOf(geometry, shapes, toolkit))
        return listOf(geometry, shapes, toolkit, modules)
    }

    @Test
    fun `the module example validates clean`() {
        val docs = modules()
        val source = GraphSource.of(docs)
        for (graph in docs) {
            val issues = Validator(catalog, source).validate(graph)
                .filter { it.severity == Severity.ERROR }
            assertEquals(emptyList(), issues.map { it.message }, "${graph.name} should validate clean")
        }
    }

    /**
     * Run, not merely validated.
     *
     * Every one of these is the kind of thing that compiles while wiring the wrong thing: a name reached
     * across a barrel resolves to a document that declares nothing, a computed field default is a call
     * somebody has to remember to emit, and a `single`'s instance used to be rebuilt from folded literals
     * rather than initialised. Only a value read back out of the VM says which of them actually happened.
     */
    @Test
    fun `the module example runs, and every module feature produces the value it should`() {
        val docs = modules()
        val modules = docs.last()
        val source = GraphSource.of(docs)
        val entry = modules.entries(catalog)
            .single { it.type == dev.ziggle.vscript.model.BuiltinNodes.ENTRY }
        val chunk = GraphCompiler(catalog, debug = false, source = source).compile(modules, entry.id)
        val result = drive(chunk, HostRegistry())
        assertTrue(result.fiber.isFinished, "the module example did not run to completion: ${result.fiber.error}")

        val closure = dev.ziggle.vscript.model.ImportClosure.resolve(modules, source)
        val scope = dev.ziggle.vscript.model.ImportScope(closure, modules)
        fun read(name: String): Any? =
            scope.variableSlot(name)?.let { result.interpreter.globals.getOrNull(it) }

        // manhattan((0,0),(3,4)) = 7, plus the computed `slack` default of max(2,3) = 3.
        assertEquals(10L, (read("Reach") as Number).toLong(), "a record built across a barrel, defaults filled")
        // The `single`'s own initialiser ran: defaultSlack() * 10.
        assertEquals(30L, (read("Budget") as Number).toLong(), "a single is an instance, not folded data")
        // A record and a function the barrel never declared, both forwarded from `geometry`.
        assertEquals(7L, (read("Span") as Number).toLong(), "a star re-export carries a record and a fn")
        assertEquals(3L, (read("Slack") as Number).toLong(), "a default import, named by the caller")
    }
}
