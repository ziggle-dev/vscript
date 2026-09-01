package dev.ziggle.vscript.lang

import dev.ziggle.vscript.model.GraphSource
import dev.ziggle.vscript.model.NodeCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A call into an IMPORTED document survives being printed.
 *
 * Found by the IDE's invariant 2 (`ideFormat(source) == canonical`), which is the first thing to run the
 * printer over the example corpus with imports actually resolved. `ScriptsParseTest` covers that corpus
 * and only parses it, so nothing ever printed these documents back — and the failure is the worst kind:
 * `fn spanOf(…) -> INT = geo::manhattan(a, b)` printed as `fn spanOf(…) -> INT { }`.
 *
 * A dropped body is silent. It compiles, it round-trips a SECOND time consistently, and the function
 * simply does nothing from then on. Formatting a file in the editor would have deleted it.
 */
class ImportedCallRoundTripTest {

    private val catalog = NodeCatalog()

    private fun library(): dev.ziggle.vscript.model.Graph {
        val text = """
            graph "geometry"

            export fn manhattan(a: INT, b: INT) -> INT = a + b
        """.trimIndent()
        val read = VsText(catalog).read(text)
        return assertNotNull(read.graph, "the library should compile: ${read.errors.map { it.message }}")
    }

    private fun source(): GraphSource {
        val lib = library()
        return GraphSource { imp -> if (imp.ref == "geometry") lib else null }
    }

    @Test
    fun `an expression body calling an imported function round-trips`() {
        val text = """
            graph "tally"

            import * as geo from "geometry"

            export fn spanOf(a: INT, b: INT) -> INT = geo::manhattan(a: a, b: b)
        """.trimIndent()

        val vs = VsText(catalog, source())
        val read = vs.read(text)
        assertTrue(read.ok, "should compile: ${read.errors.map { "${it.span} ${it.message}" }}")
        val graph = assertNotNull(read.graph, "should be installable")

        assertEquals(text, vs.write(graph).trim(), "the call was lost on the way back out")
    }

    @Test
    fun `a block body calling an imported function round-trips`() {
        val text = """
            graph "tally"

            import * as geo from "geometry"

            export fn spanOf(a: INT, b: INT) -> INT {
                return geo::manhattan(a: a, b: b)
            }
        """.trimIndent()
        // `manhattan` is itself an expression, so nothing in this body has to RUN and the wrapper is one
        // too — printed in the canonical short form. What this test is really about is that the call
        // survives at all: the body used to come back empty, because an imported callee's purity was
        // looked for among this document's nodes and never found.
        val canonical = """
            graph "tally"

            import * as geo from "geometry"

            export fn spanOf(a: INT, b: INT) -> INT = geo::manhattan(a: a, b: b)
        """.trimIndent()

        val vs = VsText(catalog, source())
        val read = vs.read(text)
        assertTrue(read.ok, "should compile: ${read.errors.map { "${it.span} ${it.message}" }}")
        val graph = assertNotNull(read.graph, "should be installable")

        assertEquals(canonical, vs.write(graph).trim(), "the call was lost on the way back out")
    }

    /** The same call as a statement, so the failure can be told apart from anything about returns. */
    @Test
    fun `an imported call as a statement round-trips`() {
        val text = """
            graph "tally"

            import * as geo from "geometry"

            export var Seen: INT = 0

            on start {
                Seen = geo::manhattan(a: 1, b: 2)
            }
        """.trimIndent()

        val vs = VsText(catalog, source())
        val read = vs.read(text)
        assertTrue(read.ok, "should compile: ${read.errors.map { "${it.span} ${it.message}" }}")
        val graph = assertNotNull(read.graph, "should be installable")

        assertEquals(text, vs.write(graph).trim(), "the call was lost on the way back out")
    }
}
