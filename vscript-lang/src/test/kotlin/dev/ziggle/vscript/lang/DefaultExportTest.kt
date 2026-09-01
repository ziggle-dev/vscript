package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphDoc
import dev.ziggle.vscript.model.GraphSource
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `export default` — the one declaration a document is FOR.
 *
 * **A default import is a renamed named import**, and that is the whole implementation. `import run from
 * "x"` asks `x` what its default is called and then binds the local name to it exactly as
 * `import { <that> as run }` would, so nothing below the import table learns that defaults exist: the Call
 * node still says `@1::run`, and the compiler, the validator and the closure are untouched.
 *
 * What it buys is the thing the hunter dispatch table wanted — a document you can name in one word,
 * without having to know that the function inside it happens to be called `run`.
 */
class DefaultExportTest {

    private val sayNode = hostNode(
        "test.say", "test.say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val catalog = NodeCatalog(listOf(sayNode))

    private fun libOf(vararg docs: Pair<String, String>): GraphSource {
        val built = docs.map { (name, src) ->
            val read = VsText(catalog).read(src)
            assertNotNull(read.graph, "$name should compile: ${read.errors.map { "${it.span.line}: ${it.message}" }}")
        }
        return GraphSource.of(built)
    }

    private fun read(src: String, source: GraphSource) = VsText(catalog, source).read(src)

    private fun said(src: String, source: GraphSource): List<Any?> {
        val r = read(src, source)
        val g = assertNotNull(r.graph, "should compile: ${r.errors.map { "${it.span.line}: ${it.message}" }}")
        val said = ArrayList<Any?>()
        val hosts = BuiltinHosts.registry()
        hosts.register("test.say", HostKind.INLINE, arity = 1) { a -> said += a[0]; null }
        val chunk = GraphCompiler(catalog, debug = false, source = source)
            .compile(g, g.entries(catalog).single().id)
        val out = drive(chunk, hosts, maxTicks = 20000)
        assertNull(out.fiber.error, "vm error: ${out.fiber.error}")
        return said.map { if (it is Number && it !is Double) it.toLong() else it }
    }

    private val METHOD = """
        graph "method"

        export default fn run(n: INT) -> INT = n * 2
    """.trimIndent()

    // ---- it works ---------------------------------------------------------------------------------

    @Test
    fun `a default import binds the other document's default`() {
        assertEquals(
            listOf(14L),
            said(
                """
                graph "probe"

                import twice from "method"

                on start {
                    say(message: twice(n: 7))
                }
                """.trimIndent(),
                libOf("method" to METHOD),
            ),
        )
    }

    /** The name is the IMPORTER's choice — that is the point of a default over a named import. */
    @Test
    fun `the local name is whatever the importer calls it`() {
        assertEquals(
            listOf(6L),
            said(
                """
                graph "probe"

                import doubled from "method"

                on start {
                    say(message: doubled(n: 3))
                }
                """.trimIndent(),
                libOf("method" to METHOD),
            ),
        )
    }

    @Test
    fun `a default may sit beside a named list`() {
        val lib = libOf(
            "method" to """
                graph "method"

                export default fn run(n: INT) -> INT = n * 2

                export fn half(n: INT) -> INT = n / 2
            """.trimIndent(),
        )
        assertEquals(
            listOf(10L, 2L),
            said(
                """
                graph "probe"

                import twice, { half } from "method"

                on start {
                    say(message: twice(n: 5))
                    say(message: half(n: 4))
                }
                """.trimIndent(),
                lib,
            ),
        )
    }

    @Test
    fun `a default may sit beside a namespace`() {
        val lib = libOf(
            "method" to """
                graph "method"

                export default fn run(n: INT) -> INT = n * 2

                export fn half(n: INT) -> INT = n / 2
            """.trimIndent(),
        )
        assertEquals(
            listOf(10L, 2L),
            said(
                """
                graph "probe"

                import twice, * as m from "method"

                on start {
                    say(message: twice(n: 5))
                    say(message: m::half(n: 4))
                }
                """.trimIndent(),
                lib,
            ),
        )
    }

    /** Any declaration, not only a function — the default is "the thing this document is". */
    @Test
    fun `a record may be the default`() {
        val lib = libOf(
            "shape" to """
                graph "shape"

                export default type Point { x: INT, y: INT }
            """.trimIndent(),
        )
        assertEquals(
            listOf(3L),
            said(
                """
                graph "probe"

                import Spot from "shape"

                on start {
                    say(message: Spot { x: 3, y: 4 }.x)
                }
                """.trimIndent(),
                lib,
            ),
        )
    }

    // ---- what it refuses --------------------------------------------------------------------------

    @Test
    fun `importing a default from a document that has none says so`() {
        val lib = libOf("plain" to "graph \"plain\"\n\nexport fn half(n: INT) -> INT = n / 2\n")
        val r = read("graph \"probe\"\n\nimport x from \"plain\"\n", lib)
        assertTrue(!r.ok)
        assertTrue(
            r.errors.any { "declares no 'export default'" in it.message },
            r.errors.joinToString { it.message },
        )
    }

    @Test
    fun `two defaults in one document are refused at the second`() {
        val r = VsText(catalog).read(
            """
            graph "probe"

            export default fn a() -> INT = 1

            export default fn b() -> INT = 2
            """.trimIndent(),
        )
        assertTrue(!r.ok)
        assertTrue(
            r.errors.any { "already this document's default" in it.message },
            r.errors.joinToString { it.message },
        )
    }

    /** `default` says which one; `export` says it may leave at all. Writing only the first is a mistake. */
    @Test
    fun `a default that is somehow not exported is refused`() {
        // `export default` always exports, so this is reached only through a hand-built graph — which the
        // canvas and the document format can both produce.
        val g = Graph(
            id = "id-x", name = "x",
            functions = listOf(dev.ziggle.vscript.model.GraphFunction("run", isExported = false)),
            defaultExport = "run",
        )
        val r = read("graph \"probe\"\n\nimport run from \"x\"\n\non start { }\n", GraphSource.of(listOf(g)))
        assertTrue(!r.ok, "an unexported default must not be reachable")
    }

    // ---- it survives the round trips ----------------------------------------------------------------

    @Test
    fun `export default round-trips through the printer`() {
        val vs = VsText(catalog)
        val text = """
            graph "method"

            export default fn run(n: INT) -> INT = n * 2
        """.trimIndent() + "\n"
        val read = vs.read(text)
        assertTrue(read.ok, "${read.errors.map { it.message }}")
        assertEquals(text, vs.write(assertNotNull(read.graph)))
    }

    @Test
    fun `a default import round-trips through the printer`() {
        val vs = VsText(catalog, libOf("method" to METHOD))
        val text = """
            graph "probe"

            import twice from "method"

            on start {
                log(message: "" + twice(n: 1))
            }
        """.trimIndent() + "\n"
        val read = vs.read(text)
        assertTrue(read.ok, "${read.errors.map { "${it.span.line}: ${it.message}" }}")
        assertEquals(text, vs.write(assertNotNull(read.graph)))
    }

    @Test
    fun `a default beside a named list round-trips`() {
        val lib = libOf(
            "method" to """
                graph "method"

                export default fn run(n: INT) -> INT = n * 2

                export fn half(n: INT) -> INT = n / 2
            """.trimIndent(),
        )
        val vs = VsText(catalog, lib)
        val text = """
            graph "probe"

            import twice, { half } from "method"

            on start {
                log(message: "" + twice(n: half(n: 4)))
            }
        """.trimIndent() + "\n"
        val read = vs.read(text)
        assertTrue(read.ok, "${read.errors.map { "${it.span.line}: ${it.message}" }}")
        assertEquals(text, vs.write(assertNotNull(read.graph)))
    }

    // ---- the bundle -------------------------------------------------------------------------------

    private val BUNDLE = """
        graph "method"

        export fn run(n: INT) -> INT = n * 2

        export fn half(n: INT) -> INT = n / 2

        export default { run, half }
    """.trimIndent()

    /**
     * The bundle is a VALUE, so it is read with a dot and called positionally.
     *
     * Positional is the trade, and it is worth naming: a function reached through a field carries a
     * signature, not argument labels, so `m.run(3)` is the spelling and `m.run(n: 3)` is not. A named
     * import keeps the labels; this buys the module being a thing you can hold.
     */
    @Test
    fun `a bundled default is read as a record`() {
        assertEquals(
            listOf(10L, 2L),
            said(
                """
                graph "probe"

                import m from "method"

                on start {
                    say(message: m.run(5))
                    say(message: m.half(4))
                }
                """.trimIndent(),
                libOf("method" to BUNDLE),
            ),
        )
    }

    @Test
    fun `a bundled default round-trips as the one line it was written as`() {
        val vs = VsText(catalog)
        val text = BUNDLE + "\n"
        val read = vs.read(text)
        assertTrue(read.ok, "${read.errors.map { "${it.span.line}: ${it.message}" }}")
        val printed = vs.write(assertNotNull(read.graph))
        assertTrue("export default { run, half }" in printed, printed)
        assertTrue("@default" !in printed, "the synthesised names must not surface:\n$printed")
        assertEquals(printed, vs.write(assertNotNull(vs.read(printed).graph, "reprint: $printed")))
    }

    @Test
    fun `a bundle naming something undeclared is refused`() {
        val r = VsText(catalog).read(
            """
            graph "probe"

            export fn run(n: INT) -> INT = n

            export default { run, nosuch }
            """.trimIndent(),
        )
        assertTrue(!r.ok)
        assertTrue(
            r.errors.any { "nosuch" in it.message },
            r.errors.joinToString { it.message },
        )
    }

    @Test
    fun `export default survives the document format`() {
        val read = VsText(catalog).read(METHOD)
        val graph = assertNotNull(read.graph, "${read.errors.map { it.message }}")
        assertEquals("run", GraphDoc.fromJson(GraphDoc.toJson(graph)).defaultExport)
    }
}
