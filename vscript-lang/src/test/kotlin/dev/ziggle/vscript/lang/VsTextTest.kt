package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The seam the CLI sits on: text in, graph out, and every complaint pointed back at a **line**.
 *
 * What is under test is not the four stages — they have their own tests — but the funnel between them. Two
 * things can go wrong there and neither shows up anywhere else: a later stage running on the wreckage of an
 * earlier one and burying the real message under consequences of it, and a stage's own vocabulary leaking
 * out. The second is the one that matters, because `Validator` speaks in node ids and the whole claim of the
 * text surface is that it is usable by someone who has never opened the canvas.
 */
class VsTextTest {

    private val sayNode = hostNode(
        "test.say", "say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )

    /** Typed pins, so a wire can be wrong in a way only the validator notices. */
    private val nameNode = hostNode(
        "test.name", "name", NodeKind.PURE,
        outputs = listOf(PinSpec("Value", PinType.STRING)),
    )
    private val doubleNode = hostNode(
        "test.double", "double", NodeKind.PURE,
        inputs = listOf(PinSpec("N", PinType.INT)),
        outputs = listOf(PinSpec("Value", PinType.INT)),
    )

    private val catalog = NodeCatalog(listOf(sayNode, nameNode, doubleNode))
    private val text = VsText(catalog)

    // ---- the happy path ----------------------------------------------------------------------------------

    @Test
    fun `good source becomes a graph`() {
        val read = text.read("""on start { say("hi") }""")
        assertTrue(read.ok, read.errors.toString())
        assertEquals(emptyList(), read.errors)
        assertTrue(assertNotNull(read.graph).nodes.isNotEmpty())
    }

    @Test
    fun `every node knows where it came from`() {
        val read = text.read("on start {\n  say(1)\n  say(2)\n}")
        assertTrue(read.ok, read.errors.toString())
        val graph = assertNotNull(read.graph)
        // Not merely non-empty: a span table missing the nodes anyone would want to break on is the same as
        // no span table, and it fails silently — the lookup just returns null and the line goes unreported.
        for (n in graph.nodes) assertNotNull(read.spans[n.id], "node ${n.id} (${n.type}) has no span")
        assertEquals(listOf(2, 3), graph.nodes.filter { it.type == "test.say" }.map { read.spans.getValue(it.id).line })
    }

    @Test
    fun `writing then reading gives an equivalent graph`() {
        val once = assertNotNull(text.read("on start { say(1) say(2) }").graph)
        val printed = text.write(once)
        val twice = assertNotNull(text.read(printed).graph, "the printer produced something unreadable")
        assertEquals(once.nodes.map { it.type }, twice.nodes.map { it.type })
        assertEquals(once.links.size, twice.links.size)
    }

    // ---- the funnel --------------------------------------------------------------------------------------

    @Test
    fun `a lex error names the line it is on`() {
        val read = text.read("on start {\n  say(\"unterminated)\n}")
        assertFalse(read.ok)
        assertNull(read.graph, "a graph was handed back for source that does not lex")
        assertEquals(2, read.errors.single().span.line)
    }

    @Test
    fun `a parse error stops before lowering`() {
        val read = text.read("on start {\n  say(\n}")
        assertFalse(read.ok)
        assertNull(read.graph)
        assertTrue(read.errors.isNotEmpty())
        // The point of the funnel: lowering half a program would report a second wave of complaints about
        // damage the parse error already explained, and the reader has to guess which one is the cause.
        assertEquals(emptyMap(), read.spans)
    }

    @Test
    fun `a validation error is reported at a line, not a node id`() {
        // A STRING wired into an INT pin — syntactically fine, lowers fine, and refused by the validator,
        // which is the only stage here that has no idea what a line is.
        val read = text.read("on start {\n  say(1)\n  say(double(name()))\n}")
        assertFalse(read.ok, "a graph the validator refuses was handed back as installable")
        assertNull(read.graph)
        val error = read.errors.single()
        assertEquals(3, error.span.line, "the wrong line was blamed: $error")
        assertFalse("node" in error.message, "a node id leaked into a message meant for a reader of source")
    }

    @Test
    fun `an unknown annotation warns and still produces a graph`() {
        // §8b: metadata the language has not been taught must be visible without being fatal, or the
        // language cannot carry anything new.
        val read = text.read("""on start { @author("jw") say(1) }""")
        assertTrue(read.ok, read.errors.toString())
        assertNotNull(read.graph)
        assertTrue(read.warnings.any { "author" in it.message }, "an unknown annotation passed unremarked")
    }
}
