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
 * `import "core/list"` — everything the document exports, under its own name.
 *
 * **It exists because the utility documents are entirely EXTENSIONS.** `core/list` declares eleven and no
 * plain functions at all; `core/tile` two; `core/objects` three. Since an extension has to be asked for by
 * name, using a list library meant writing eleven verbs in a list that said nothing anybody wanted to read.
 * This is the one form that says "all of it" — which is still an explicit statement about this document,
 * unlike inheriting verbs merely by importing a file for its types.
 *
 * **It never takes a name that is already spoken for.** A node, an explicit import, or this document's own
 * declaration keeps its meaning and the name does not arrive — so one unlucky word (`contains` is a node)
 * cannot break a line wanted for the other twenty. Two stars offering one name bind NEITHER, and that is
 * reported where the name is used, because that is the first point at which there is anything to decide.
 * `import java.util.*`'s rule, for its reason.
 */
class StarImportTest {

    private val sayNode = hostNode(
        "test.say", "test.say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val catalog = NodeCatalog(listOf(sayNode))

    private fun libOf(vararg docs: Pair<String, String>): GraphSource {
        val built = ArrayList<Graph>()
        val source = GraphSource { imp -> built.firstOrNull { it.name == imp.ref } }
        for ((name, src) in docs) {
            val read = VsText(catalog, source).read(src)
            built += assertNotNull(
                read.graph,
                "$name should compile: ${read.errors.map { "${it.span.line}: ${it.message}" }}",
            )
        }
        return source
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

    private val LIST = """
        graph "list"

        export fn LIST<T>.second(self) -> T = self[1]

        export fn LIST<T>.lastOne(self) -> T = self[_listCount(list: self) - 1]

        export fn twice(n: INT) -> INT = n * 2
    """.trimIndent()

    // ---- it brings everything ------------------------------------------------------------------------

    /** The case it is FOR: a document of nothing but verbs, in one line that names none of them. */
    @Test
    fun `a star import brings every extension`() {
        assertEquals(
            listOf(2L, 3L),
            said(
                """
                graph "probe"

                import "list"

                on start {
                    say(message: [1, 2, 3].second())
                    say(message: [1, 2, 3].lastOne())
                }
                """.trimIndent(),
                libOf("list" to LIST),
            ),
        )
    }

    @Test
    fun `a star import brings plain functions too`() {
        assertEquals(
            listOf(14L),
            said(
                """
                graph "probe"

                import "list"

                on start {
                    say(message: twice(n: 7))
                }
                """.trimIndent(),
                libOf("list" to LIST),
            ),
        )
    }

    // ---- it never takes a name that is spoken for ----------------------------------------------------

    /**
     * A NODE keeps its meaning, and the line still compiles.
     *
     * The whole reason collisions are not reported at the import: `core/list` exports a `count`, and so
     * does the catalogue. Refusing the line would make the form useless for the one document it exists for.
     */
    @Test
    fun `a name the catalogue already uses is left alone`() {
        val lib = libOf(
            "shadow" to """
                graph "shadow"

                export fn _listCount(list: LIST<INT>) -> INT = 99

                export fn ours(n: INT) -> INT = n + 1
            """.trimIndent(),
        )
        assertEquals(
            listOf(3L, 5L),
            said(
                """
                graph "probe"

                import "shadow"

                on start {
                    say(message: _listCount(list: [1, 2, 3]))
                    say(message: ours(n: 4))
                }
                """.trimIndent(),
                lib,
            ),
        )
    }

    /** This document's own declaration wins, silently — the same rule. */
    @Test
    fun `a local declaration keeps its name`() {
        assertEquals(
            listOf(100L),
            said(
                """
                graph "probe"

                import "list"

                fn twice(n: INT) -> INT = 100

                on start {
                    say(message: twice(n: 7))
                }
                """.trimIndent(),
                libOf("list" to LIST),
            ),
        )
    }

    /** ...and an EXPLICIT import wins, whichever line came first. */
    @Test
    fun `a named import beats a star written above it`() {
        val lib = libOf(
            "a" to "graph \"a\"\n\nexport fn pick() -> INT = 1\n",
            "b" to "graph \"b\"\n\nexport fn pick() -> INT = 2\n",
        )
        assertEquals(
            listOf(2L),
            said(
                """
                graph "probe"

                import "a"
                import { pick } from "b"

                on start {
                    say(message: pick())
                }
                """.trimIndent(),
                lib,
            ),
        )
    }

    // ---- two stars are a real collision --------------------------------------------------------------

    /**
     * Reported where the name is USED, naming both, with the way out.
     *
     * Neither binds it: picking one would mean an import somewhere above silently decided what the line
     * does, which is the failure the whole qualified-everything design exists to prevent.
     */
    @Test
    fun `a name two stars both offer is refused at the use`() {
        val lib = libOf(
            "a" to "graph \"a\"\n\nexport fn pick() -> INT = 1\n",
            "b" to "graph \"b\"\n\nexport fn pick() -> INT = 2\n",
        )
        val r = read(
            """
            graph "probe"

            import "a"
            import "b"

            on start {
                say(message: pick())
            }
            """.trimIndent(),
            lib,
        )
        assertTrue(!r.ok)
        val message = r.errors.joinToString("; ") { it.message }
        assertTrue("\"a\"" in message && "\"b\"" in message, "should name both: $message")
        assertTrue("import { pick }" in message, "should say the way out: $message")
    }

    /** And the OTHER names from both documents still arrive — only the contested one is withheld. */
    @Test
    fun `only the contested name is withheld`() {
        val lib = libOf(
            "a" to "graph \"a\"\n\nexport fn pick() -> INT = 1\n\nexport fn onlyA() -> INT = 7\n",
            "b" to "graph \"b\"\n\nexport fn pick() -> INT = 2\n\nexport fn onlyB() -> INT = 9\n",
        )
        assertEquals(
            listOf(7L, 9L),
            said(
                """
                graph "probe"

                import "a"
                import "b"

                on start {
                    say(message: onlyA())
                    say(message: onlyB())
                }
                """.trimIndent(),
                lib,
            ),
        )
    }

    // ---- it survives the round trips -----------------------------------------------------------------

    @Test
    fun `it prints back as it was written`() {
        val vs = VsText(catalog, libOf("list" to LIST))
        val text = """
            graph "probe"

            import "list"

            on start {
                log(message: "" + [1, 2, 3].second())
            }
        """.trimIndent() + "\n"
        val read = vs.read(text)
        assertTrue(read.ok, "${read.errors.map { "${it.span.line}: ${it.message}" }}")
        assertEquals(text, vs.write(assertNotNull(read.graph)))
    }

    @Test
    fun `it survives the document format`() {
        val read = VsText(catalog, libOf("list" to LIST)).read(
            "graph \"probe\"\n\nimport \"list\"\n\non start { }\n",
        )
        val graph = assertNotNull(read.graph, "${read.errors.map { it.message }}")
        val back = GraphDoc.fromJson(GraphDoc.toJson(graph))
        assertTrue(back.imports.single().star, "the star has to survive a save")
        assertEquals("list", back.imports.single().ref)
    }

    /** `import *` without a name is still refused, and now points at the form that replaced it. */
    @Test
    fun `a bare star is refused with the spelling that works`() {
        val r = read("graph \"probe\"\n\nimport * from \"list\"\n\non start { }\n", libOf("list" to LIST))
        assertTrue(!r.ok)
        assertTrue(
            r.errors.any { "import \"banking\"" in it.message },
            r.errors.joinToString { it.message },
        )
    }
}
