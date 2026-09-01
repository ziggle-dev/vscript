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
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A top-level `val` holding a RECORD, read across an import — the shape an activity roster is made of.
 *
 * The existing coverage in `ImportedInitialisersTest` is about INT variables reached from an importer's
 * `on start`. Three things about the roster differ, and each is a place the prologue could be missed:
 * the value is a record literal rather than a number, one of its fields is a FUNCTION reference, and the
 * reader is sometimes the declaring document's own `always on wake` rather than the importer.
 */
class ActivityRowInitTest {

    private val say = hostNode(
        "test.say", "say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val catalog = NodeCatalog(listOf(say))

    private val contract = """
        graph "contract"

        export type Row {
            name: String,
            check: fn(Row) -> Bool,
        }
    """.trimIndent()

    /** The new design: the roster is read from the ROOT's `on start`, via an explicit call. */
    @Test
    fun `a record-typed top-level val survives an import and is read from the root's start`() {
        val c = lower("contract", contract)
        val leaf = lower(
            "leaf",
            """
            graph "leaf"

            import "contract"

            export fn ready(r: Row) -> Bool = true

            export val Mine: Row = Row { name: "leaf", check: ready }
            """.trimIndent(),
            listOf(c),
        )
        val root = lower(
            "root",
            """
            graph "root"

            import "contract"
            import * as leaf from "leaf"

            on start {
                say(message: leaf::Mine.name)
            }
            """.trimIndent(),
            listOf(c, leaf),
        )
        assertEquals(listOf<Any?>("leaf"), runStart(root, GraphSource.of(listOf(c, leaf))))
    }

    /** The OLD design, and the one reported broken: the declaring document's own `always on wake`. */
    @Test
    fun `a record-typed top-level val is populated by the time the document's own always-on-wake reads it`() {
        val c = lower("contract", contract)
        val leaf = lower(
            "leaf",
            """
            graph "leaf"

            import "contract"

            export fn ready(r: Row) -> Bool = true

            export val Mine: Row = Row { name: "leaf", check: ready }

            always on wake {
                say(message: Mine.name)
            }
            """.trimIndent(),
            listOf(c),
        )
        val root = lower(
            "root",
            """
            graph "root"

            import "contract"
            import * as leaf from "leaf"

            on wake {
                say(message: "root")
            }

            on start {
                say(message: "started")
            }
            """.trimIndent(),
            listOf(c, leaf),
        )
        assertEquals(
            listOf<Any?>("leaf", "root"),
            runGroup(root, GraphSource.of(listOf(c, leaf)), EntryGroup.WAKE),
        )
    }

    /**
     * The roster shape itself: an enum column holding an imported, TYPE-ANNOTATED `val`.
     *
     * This is what `plugins/scripts/src/activities/activities.vs` is made of — a table of scheduler data
     * with a column carrying each activity's bundle of hooks. It was refused with "an enum's values have
     * to be written out, not worked out", which was a true sentence about the wrong thing: the value IS
     * written out, and the only reason it did not fold was the `: Row` beside its name.
     */
    @Test
    fun `an enum column may name an imported typed val`() {
        val c = lower("contract", contract)
        val leaf = lower(
            "leaf",
            """
            graph "leaf"

            import "contract"

            export fn ready(r: Row) -> Bool = true

            export val Mine: Row = Row { name: "leaf", check: ready }
            """.trimIndent(),
            listOf(c),
        )
        val root = lower(
            "root",
            """
            graph "root"

            import "contract"
            import * as leaf from "leaf"

            enum Roster(label: STRING, impl: Row) {
                Leaf("first", leaf::Mine),
            }

            on start {
                for r in Roster.values() {
                    say(message: r.label + "/" + r.impl.name)
                }
            }
            """.trimIndent(),
            listOf(c, leaf),
        )
        assertEquals(listOf<Any?>("first/leaf"), runStart(root, GraphSource.of(listOf(c, leaf))))
    }

    // ---- harness -------------------------------------------------------------------------------------

    private fun lower(name: String, text: String, known: List<Graph> = emptyList()): Graph {
        val parsed = Parser(Lexer(text).lex()).parse()
        assertTrue(parsed.ok, "$name: parse errors ${parsed.errors.map { "${it.span} ${it.message}" }}")
        val result = Lower(catalog, source = GraphSource.of(known)).lower(parsed.program)
        assertTrue(result.errors.isEmpty(), "$name: ${result.errors.map { "${it.span} ${it.message}" }}")
        return result.graph
    }

    // The BUILTINS, not a bare registry: reading a data column off an enum is a call to
    // `vscript.enumField`, so a test that only registers its own node faults on the first row it reads.
    private fun hosts(out: MutableList<Any?>): HostRegistry =
        dev.ziggle.vscript.nodes.BuiltinHosts.registry()
            .register("say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }

    private fun runStart(root: Graph, source: GraphSource): List<Any?> {
        val issues = Validator(catalog, source).validate(root).filter { it.severity == Severity.ERROR }
        assertEquals(emptyList(), issues.map { it.message }, "root should validate clean")
        val entry = root.entries(catalog).single { it.type == BuiltinNodes.ENTRY }
        val chunk = GraphCompiler(catalog, debug = false, source = source).compile(root, entry.id)
        val out = ArrayList<Any?>()
        val result = drive(chunk, hosts(out), maxTicks = 400)
        assertTrue(
            result.fiber.isFinished,
            "the run did not complete: ${result.fiber.state} ${result.fiber.error?.message.orEmpty()}",
        )
        return out
    }

    private fun runGroup(root: Graph, source: GraphSource, group: EntryGroup): List<Any?> {
        val compiled = GraphCompiler(catalog, debug = false, source = source).compileEntries(root, group)
        val out = ArrayList<Any?>()
        for (entry in compiled.entries) drive(entry.chunk, hosts(out), maxTicks = 400)
        return out
    }
}
