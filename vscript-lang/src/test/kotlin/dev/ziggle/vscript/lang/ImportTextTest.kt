package dev.ziggle.vscript.lang

import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphSource
import dev.ziggle.vscript.model.NodeCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `import` on the text surface: lowering it, and printing it back unchanged.
 *
 * The round trip is the assertion that matters. Everything about imports is stored where a name already
 * lived — `Node.callee` becomes `banking::withdraw`, `Node.variable` becomes `banking::trips` — so the
 * printer needed no new recognizer, and a test that only checked the graph would not notice if it had.
 */
class ImportTextTest {

    private val catalog = NodeCatalog(dev.ziggle.vscript.domain.TileFixture.DESCRIPTORS)

    /** A library with one function and one variable, built as a document rather than parsed. */
    private val banking = Graph(
        id = "id-banking",
        name = "banking",
        nodes = listOf(
            dev.ziggle.vscript.model.Node(1, BuiltinNodes.FUNCTION, function = "withdraw"),
        ),
        variables = listOf(
            dev.ziggle.vscript.model.GraphVariable(
                "trips",
                dev.ziggle.vscript.model.TypeRef(dev.ziggle.vscript.model.PinType.INT),
                0,
                isExported = true,
            )
        ),
        functions = listOf(
            dev.ziggle.vscript.model.GraphFunction(
                "withdraw",
                params = listOf(dev.ziggle.vscript.model.FunctionPin("amount", dev.ziggle.vscript.model.PinType.INT)),
                isExported = true,
            )
        ),
    )

    private val source = GraphSource.of(listOf(banking))
    private fun vs() = VsText(catalog, source)

    private fun roundTrip(src: String) {
        val read = vs().read(src)
        assertTrue(read.ok, "unexpected: ${read.errors.map { it.toString() }}")
        assertEquals(src, vs().write(read.graph!!))
    }

    @Test
    fun `an import declaration lowers onto the document`() {
        val read = vs().read("""import * as banking from "banking"${'\n'}${'\n'}on start { }""")
        assertTrue(read.ok, "${read.errors.map { it.toString() }}")
        val imp = read.graph!!.imports.single()
        assertEquals("banking", imp.alias)
        assertEquals("banking", imp.ref)
        // The resolved id is written back, so renaming the library later does not strand this import.
        assertEquals("id-banking", imp.docId)
    }

    @Test
    fun `a qualified call becomes a Call node naming the other document`() {
        val read = vs().read(
            """
            import * as banking from "banking"

            on start {
                banking::withdraw(50)
            }
            """.trimIndent()
        )
        assertTrue(read.ok, "${read.errors.map { it.toString() }}")
        val call = read.graph!!.nodes.single { it.type == BuiltinNodes.CALL }
        assertEquals("banking::withdraw", call.callee)
    }

    @Test
    fun `a qualified bare name becomes a Get on the other document's variable`() {
        val read = vs().read(
            """
            import * as banking from "banking"

            on start {
                log(toText(banking::trips))
            }
            """.trimIndent()
        )
        assertTrue(read.ok, "${read.errors.map { it.toString() }}")
        val get = read.graph!!.nodes.single { it.type == BuiltinNodes.VAR_GET }
        assertEquals("banking::trips", get.variable)
    }

    @Test
    fun `imports round-trip character for character`() {
        // Printer-canonical, which is what the T -> G -> T contract is defined against: the argument is
        // NAMED because the printer resolved the imported signature and found the pin was called `amount`.
        // That naming is itself the evidence the signature crossed the document boundary.
        roundTrip(
            """
            import * as banking from "banking"

            on start {
                banking::withdraw(amount: 50)
            }

            """.trimIndent()
        )
    }

    @Test
    fun `a call through an alias nothing is imported as is refused at the alias`() {
        val read = vs().read("on start { nope::withdraw(1) }")
        assertTrue(!read.ok)
        assertTrue(
            read.errors.any { "nothing is imported as 'nope'" in it.message },
            read.errors.joinToString { it.message },
        )
    }

    // ---- visibility -------------------------------------------------------------------------------

    @Test
    fun `export survives the round trip on all three declarations`() {
        // Asserted as a FIXED POINT rather than against a hand-written expected form: the printer
        // canonicalises other things too (a type prints as `INT`, a body-less function grows braces), and
        // baking those into a fixture here would make this test fail for reasons that are not about
        // visibility. What matters is that `export` is still there, and that printing is stable.
        val src = """
            export type Secret { n: Int }
            export var shown: Int = 0
            export fn helper() { }
            on start { }
        """.trimIndent()

        val once = vs().read(src)
        assertTrue(once.ok, "unexpected: ${once.errors.map { it.toString() }}")
        val printed = vs().write(once.graph!!)

        assertEquals(3, Regex("(?m)^export ").findAll(printed).count(), printed)

        val twice = vs().read(printed)
        assertTrue(twice.ok, "reprint did not parse: ${twice.errors.map { it.toString() }}")
        assertEquals(printed, vs().write(twice.graph!!))
    }

    @Test
    fun `export goes before the thing it modifies`() {
        assertTrue(VsText(catalog).read("on start { }").ok, "`on` is a declaration")

        val r = VsText(catalog).read("""log(message: "x")""")
        assertTrue(!r.ok)

        val statement = VsText(catalog).read("""export log(message: "x")""")
        assertTrue(!statement.ok)
        assertTrue(
            statement.errors.any { "'export' goes before" in it.message },
            statement.errors.joinToString { it.message },
        )
    }

    /**
     * An entry has no name, so `export` on one is refused — and the message says what was meant.
     *
     * Two different questions wearing the same shape: `export` makes a name sayable and `always` makes a
     * handler run for an importer. Somebody migrating will reach for the first, so the error hands them
     * the second rather than a list of keywords.
     */
    @Test
    fun `export on an entry is refused, naming always instead`() {
        val r = VsText(catalog).read("export on tick { }")
        assertTrue(!r.ok)
        assertTrue(
            r.errors.any { "always on tick" in it.message },
            r.errors.joinToString { it.message },
        )
    }

    @Test
    fun `a function that is not exported is not reachable through an import, and says so`() {
        val lib = Graph(
            id = "id-lib",
            name = "lib",
            nodes = listOf(dev.ziggle.vscript.model.Node(1, BuiltinNodes.FUNCTION, function = "secret")),
            functions = listOf(dev.ziggle.vscript.model.GraphFunction("secret", isExported = false)),
        )
        val read = VsText(catalog, GraphSource.of(listOf(lib))).read(
            """
            import * as lib from "lib"

            on start {
                lib::secret()
            }
            """.trimIndent()
        )
        assertTrue(!read.ok)
        // Not "no function named" — it is right there, and that message sends you hunting for a typo in a
        // name that is spelled correctly, in the wrong file.
        assertTrue(
            read.errors.any { "declared in 'lib' but not exported" in it.message },
            read.errors.joinToString { it.message },
        )
    }

    @Test
    fun `the same alias twice is refused where the second one is written`() {
        val read = vs().read(
            """
            import * as banking from "banking"
            import * as banking from "banking"

            on start { }
            """.trimIndent()
        )
        assertTrue(!read.ok)
        assertTrue(
            read.errors.any { "already imported" in it.message },
            read.errors.joinToString { it.message },
        )
    }

    // ---- an imported const ---------------------------------------------------------------------------
    //
    // A const crosses where a `var` cannot, because a variable's value belongs to the RUN and a const's
    // belongs to the document. Until this worked, `vars.vs` declared everything shared as a `var` and said
    // why: "a const is a literal NODE, and an import cannot name one".
    //
    // The round trip is the assertion that matters, exactly as it is for the rest of this file. A const
    // folds to its value, so the printer has to be able to raise the qualified name back out of a plain
    // literal — without that the sugar is inadmissible, and the first save would rewrite `limits::Limit`
    // as `5` and lose the name the author wrote.

    private fun limitsLib(): Graph {
        val read = VsText(catalog).read(
            """
            graph "limits"

            export val Limit = 5

            export val Home = tile(3200, 3200, 0)
            """.trimIndent(),
        )
        return kotlin.test.assertNotNull(read.graph, "library: ${read.errors.map { it.message }}")
    }

    private fun withLimits() = VsText(catalog, GraphSource.of(listOf(limitsLib())))

    @Test
    fun `an imported const resolves to its value`() {
        val read = withLimits().read(
            """
            import * as limits from "limits"

            on start {
                val n = limits::Limit
            }
            """.trimIndent(),
        )
        assertTrue(read.ok, "should compile: ${read.errors.map { it.toString() }}")
        val g = kotlin.test.assertNotNull(read.graph)
        val lit = g.nodes.single { it.literals[Lower.IMPORTED_CONST] != null }
        assertEquals(5, lit.literals["Value"])
        assertEquals("limits::Limit", lit.literals[Lower.IMPORTED_CONST])
        // And it is a reference, not a declaration: nothing here declares a const of that name.
        assertTrue(g.nodes.none { it.literals[Lower.CONST_NAME] != null }, "should declare no const")
    }

    @Test
    fun `an imported const prints back as the name, not the value`() {
        val src = """
            import * as limits from "limits"

            on start {
                val n = limits::Limit
            }
        """.trimIndent()
        val read = withLimits().read(src)
        assertTrue(read.ok, "${read.errors.map { it.toString() }}")
        val printed = withLimits().write(read.graph!!)
        assertTrue("limits::Limit" in printed, "the name has to survive: $printed")
        // The IMPORTED const must not be re-declared in this document. Checked by its name rather than
        // by the keyword, since the body's own `val n = …` is a `val` too.
        assertTrue("val Limit" !in printed, "and must not become a declaration here: $printed")
        // Trailing newline only — the printer ends a document with one and the literal here does not.
        assertEquals(src, printed.trimEnd())
    }

    /** A TILE const keeps its kind, which is why the declaring node's type is reused rather than guessed. */
    @Test
    fun `an imported tile const stays a tile`() {
        val read = withLimits().read(
            """
            import * as limits from "limits"

            on start {
                val h = limits::Home
            }
            """.trimIndent(),
        )
        assertTrue(read.ok, "${read.errors.map { it.toString() }}")
        val lit = read.graph!!.nodes.single { it.literals[Lower.IMPORTED_CONST] != null }
        assertEquals(BuiltinNodes.LITERAL_TILE, lit.type)
    }

    /** And a `var` is still a variable — the const lookup must not take over the case it cannot serve. */
    @Test
    fun `an imported var is still read through a Get`() {
        val read = vs().read(
            """
            import * as banking from "banking"

            on start {
                val t = banking::trips
            }
            """.trimIndent(),
        )
        assertTrue(read.ok, "${read.errors.map { it.toString() }}")
        val g = read.graph!!
        assertTrue(g.nodes.any { it.type == BuiltinNodes.VAR_GET && it.variable == "banking::trips" })
        assertTrue(g.nodes.none { it.literals[Lower.IMPORTED_CONST] != null })
    }
}
