package dev.ziggle.vscript.model

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Serialization of graph documents — the format that has to survive every future version of the editor. */
class GraphDocTest {

    private fun sample() = Graph(
        id = "a1b2c3",
        name = "Blackjack",
        variables = listOf(
            GraphVariable("target", TypeRef.named("Npc"), 1234),
            GraphVariable("enabled", PinType.BOOL, true),
            GraphVariable("label", PinType.STRING, "bandit"),
        ),
        nodes = listOf(
            Node(1, BuiltinNodes.ENTRY, x = 10.5f, y = -20f),
            Node(2, "npc.interact", literals = linkedMapOf<String, Any?>("Npc" to 1234, "Action" to "Lure"), comment = "lure it in"),
            Node(3, BuiltinNodes.VAR_SET, variable = "enabled"),
        ),
        links = listOf(
            Link(1, 1, "Exec", 2, "Exec"),
            Link(2, 2, "Exec", 3, "Exec"),
        ),
    )

    @Test
    fun `a document round-trips unchanged`() {
        val original = sample()
        val back = GraphDoc.fromJson(GraphDoc.toJson(original))

        assertEquals(original.id, back.id)
        assertEquals(original.name, back.name)
        assertEquals(original.nodes.size, back.nodes.size)
        assertEquals(original.links.size, back.links.size)

        val n2 = back.node(2)!!
        assertEquals("npc.interact", n2.type)
        assertEquals("Lure", n2.literals["Action"])
        assertEquals("lure it in", n2.comment)
        assertEquals("enabled", back.node(3)!!.variable)

        val l = back.links.first()
        assertEquals(1, l.fromNode)
        assertEquals("Exec", l.fromPin)
        assertEquals(2, l.toNode)
    }

    @Test
    fun `integers survive the round trip as integers`() {
        // JSON has one number type and Gson hands a bare `1234` back as a Double. Without the tagging in
        // GraphDoc every item/npc id would come back as 1234.0 and blow up the first host function that
        // casts to Int — at runtime, in the game, not here.
        val back = GraphDoc.fromJson(GraphDoc.toJson(sample()))
        val npcLiteral = back.node(2)!!.literals["Npc"]
        assertTrue(npcLiteral is Int, "expected Int, got ${npcLiteral?.javaClass?.simpleName}")
        assertEquals(1234, npcLiteral)

        val varDefault = back.variable("target")!!.default
        assertTrue(varDefault is Int, "expected Int, got ${varDefault?.javaClass?.simpleName}")
    }

    @Test
    fun `booleans strings doubles and nulls round-trip with their types`() {
        val g = Graph(
            id = "t", name = "t",
            nodes = listOf(
                Node(1, BuiltinNodes.LITERAL, literals = linkedMapOf<String, Any?>(
                    "b" to true, "s" to "hi", "d" to 1.5, "n" to null, "l" to 9_000_000_000L,
                )),
            ),
        )
        val lit = GraphDoc.fromJson(GraphDoc.toJson(g)).node(1)!!.literals
        assertEquals(true, lit["b"])
        assertEquals("hi", lit["s"])
        assertEquals(1.5, lit["d"])
        assertNull(lit["n"])
        assertEquals(9_000_000_000L, lit["l"])
    }

    @Test
    fun `positions round-trip so a layout survives a reload`() {
        val back = GraphDoc.fromJson(GraphDoc.toJson(sample()))
        assertEquals(10.5f, back.node(1)!!.x)
        assertEquals(-20f, back.node(1)!!.y)
    }

    @Test
    fun `the format version is stamped`() {
        assertContains(GraphDoc.toJson(sample()), "\"format\": ${GraphDoc.FORMAT}")
    }

    @Test
    fun `a document from a newer client is refused rather than half-read`() {
        val future = GraphDoc.toJson(sample()).replace(
            "\"format\": ${GraphDoc.FORMAT}",
            "\"format\": ${GraphDoc.FORMAT + 1}",
        )
        val e = assertFailsWith<IllegalArgumentException> { GraphDoc.fromJson(future) }
        assertContains(e.message!!, "newer than this client understands")
    }

    @Test
    fun `an empty graph round-trips`() {
        val back = GraphDoc.fromJson(GraphDoc.toJson(Graph("e", "empty")))
        assertEquals("empty", back.name)
        assertTrue(back.nodes.isEmpty())
        assertTrue(back.links.isEmpty())
    }

    @Test
    fun `writing and reading a file round-trips`(@org.junit.jupiter.api.io.TempDir dir: java.io.File) {
        val file = java.io.File(dir, "graphs/blackjack.json")
        GraphDoc.write(sample(), file)
        assertTrue(file.exists(), "write should create parent directories")
        assertEquals("Blackjack", GraphDoc.read(file).name)
    }

    /**
     * Format 1 put a function's ends in two proxy nodes; format 2 puts them on the box.
     *
     * The wires are what matter — a migration that dropped them would leave a graph that still opened and
     * quietly did nothing, which is the worst way for this to go wrong.
     */
    @Test
    fun `an old document moves its function ends onto the box`() {
        val old = """
            {
              "format": 1,
              "id": "x", "name": "old",
              "functions": [ { "name": "F",
                "params": [ { "name": "X", "type": "INT" } ],
                "results": [ { "name": "Y", "type": "INT" } ] } ],
              "nodes": [
                { "id": 1, "type": "function.box", "x": 0, "y": 0, "function": "F" },
                { "id": 2, "type": "function.in",  "x": 0, "y": 0, "function": "F" },
                { "id": 3, "type": "function.out", "x": 0, "y": 0, "function": "F" },
                { "id": 4, "type": "math.add",     "x": 0, "y": 0, "function": "F" }
              ],
              "links": [
                { "id": 1, "fromNode": 2, "fromPin": "Exec", "toNode": 3, "toPin": "Exec" },
                { "id": 2, "fromNode": 2, "fromPin": "X", "toNode": 4, "toPin": "A" },
                { "id": 3, "fromNode": 4, "fromPin": "Result", "toNode": 3, "toPin": "Y" }
              ]
            }
        """.trimIndent()

        val g = GraphDoc.fromJson(old)
        assertEquals(2, g.nodes.size, "the proxy nodes should be gone: ${g.nodes.map { it.type }}")
        val box = g.nodes.first { it.type == BuiltinNodes.FUNCTION }.id
        // Every wire that touched a proxy now touches the box, keeping its pin.
        assertEquals(3, g.links.size)
        assertTrue(g.links.any { it.fromNode == box && it.fromPin == "Exec" && it.toNode == box })
        assertTrue(g.links.any { it.fromNode == box && it.fromPin == "X" && it.toPin == "A" })
        assertTrue(g.links.any { it.toNode == box && it.toPin == "Y" && it.fromPin == "Result" })
    }

    /** Where a node SITS has to survive a save, or every container forgets its contents on reopen. */
    @Test
    fun `container membership round-trips`() {
        val g = Graph(
            "x", "doc",
            nodes = listOf(
                Node(1, BuiltinNodes.COMMENT),
                Node(2, BuiltinNodes.LOG).also { it.group = 1 },
                Node(3, BuiltinNodes.LOG),
            ),
        )
        val back = GraphDoc.fromJson(GraphDoc.toJson(g))
        assertEquals(1, back.node(2)?.group)
        assertNull(back.node(3)?.group)
    }

    /**
     * A comma-string of ids becomes a real list.
     *
     * `Use Item On Any` and `Drop Any` took their ids as text, because they were written before lists
     * existed. The pins carry a list now and the host still reads either — but a string sitting on a list
     * pin draws no editor, so without this a graph authored before the change would be one you could run
     * and not change.
     */
    @Test
    fun `format 3 item strings become lists`() {
        val old = """
            {"format": 3, "id": "x", "name": "old", "nodes": [
              {"id": 1, "type": "interact.useItemOnAny", "x": 0, "y": 0,
               "literals": {"Item": {"${'$'}t":"i","v":1955}, "Targets": "5378, 5380,5376"}},
              {"id": 2, "type": "inventory.dropAny", "x": 0, "y": 0, "literals": {"Items": "247,464"}}
            ], "links": []}
        """.trimIndent()
        val g = GraphDoc.fromJson(old)
        assertEquals(listOf(5378, 5380, 5376), g.node(1)?.literals?.get("Targets"))
        assertEquals(listOf(247, 464), g.node(2)?.literals?.get("Items"))
        // The ids stay INTS through the round trip — a bare JSON number would come back as 5378.0.
        val back = GraphDoc.fromJson(GraphDoc.toJson(g))
        assertEquals(listOf(5378, 5380, 5376), back.node(1)?.literals?.get("Targets"))
    }

    /** Nothing to convert leaves the pin alone rather than writing an empty list onto it. */
    @Test
    fun `an empty item string migrates to nothing`() {
        val g = GraphDoc.fromJson(
            """{"format": 3, "id": "x", "name": "old", "nodes": [
                 {"id": 1, "type": "inventory.dropAny", "x": 0, "y": 0, "literals": {"Items": ""}}
               ], "links": []}""",
        )
        assertEquals(null, g.node(1)?.literals?.get("Items"))
    }

    /** A list already written as one is untouched. */
    @Test
    fun `a current document is not re-migrated`() {
        val g = GraphDoc.fromJson(
            """{"format": 4, "id": "x", "name": "new", "nodes": [
                 {"id": 1, "type": "inventory.dropAny", "x": 0, "y": 0,
                  "literals": {"Items": [{"${'$'}t":"i","v":247}]}}
               ], "links": []}""",
        )
        assertEquals(listOf(247), g.node(1)?.literals?.get("Items"))
    }
}
