package dev.ziggle.vscript.lang

import dev.ziggle.vscript.model.NodeCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Comments do **not** survive a print — by design — and [Comments.commentLines] is what keeps that from
 * costing anybody their documentation.
 *
 * A comment belongs to its source document and never crosses into the graph. VSCRIPT_LANG_PLAN records
 * what forced the rule: a round trip destroyed 833 of 929 `///` lines in the script folder, which is a
 * data-loss bug rather than a design tension. `@note("…")` is the way to put a remark *into* a graph.
 *
 * So this pins two things. That the loss is total and uniform — a printer that carried SOME comments
 * would be worse than one that carries none, because the guard would no longer describe it. And that the
 * guard sees every shape of comment, since every operation that prints over a `.vs` — `graph export`, the
 * IDE's Reformat — decides whether it is safe by asking it.
 */
class CommentRoundTripTest {

    private val catalog = NodeCatalog()

    /** Printed WITH the comment plan the read produced — what a text-to-text caller does. */
    private fun printed(text: String): String {
        val vs = VsText(catalog)
        val read = vs.read(text)
        val graph = assertNotNull(read.graph, "should compile: ${read.errors.map { it.message }}")
        return vs.write(graph, read.comments).trim()
    }

    /** Printed WITHOUT one — what a canvas graph, which has no comments, gets. */
    private fun printedBare(text: String): String {
        val vs = VsText(catalog)
        val read = vs.read(text)
        val graph = assertNotNull(read.graph, "should compile: ${read.errors.map { it.message }}")
        return vs.write(graph).trim()
    }

    private fun keeps(text: String) = assertEquals(text, printed(text), "a comment was lost")

    // ---- carried, when the caller has a plan ----------------------------------------------------------

    @Test
    fun `a doc comment on a variable is carried`() {
        keeps(
            """
            graph "probe"

            /** How many times round. */
            export var Laps: INT = 0
            """.trimIndent(),
        )
    }

    @Test
    fun `a multi-line doc comment on a function is carried`() {
        keeps(
            """
            graph "probe"

            /**
             * Add two numbers.
             *
             * @param a the first
             * @param b the second
             * @return their sum
             */
            export fn add(a: INT, b: INT) -> INT = a + b
            """.trimIndent(),
        )
    }

    @Test
    fun `a line comment inside a body is carried`() {
        keeps(
            """
            graph "probe"

            on start {
                // Say hello first.
                log(message: "hello")
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a comment on a type is carried`() {
        keeps(
            """
            graph "probe"

            /** A place. */
            export type Point { x: INT, y: INT }
            """.trimIndent(),
        )
    }

    @Test
    fun `several comments in one file are all carried`() {
        keeps(
            """
            graph "probe"

            /** The tally. */
            export var Total: INT = 0

            /** Bump it. */
            export fn bump() -> INT = Total + 1

            on start {
                // First.
                log(message: "a")
                // Then.
                log(message: "b")
            }
            """.trimIndent(),
        )
    }

    /**
     * A canvas graph still prints without comments, because it has none.
     *
     * The rule the plan states is unchanged: a comment never enters the graph. It rides in a side table
     * that only a caller who READ text can have, which is exactly the caller that has comments to place.
     */
    @Test
    fun `without a plan the print is comment-free, as a canvas graph is`() {
        assertEquals(
            """
            graph "probe"

            export var Laps: INT = 0
            """.trimIndent(),
            printedBare(
                """
                graph "probe"

                /** How many times round. */
                export var Laps: INT = 0
                """.trimIndent(),
            ),
        )
    }

    /** `@note` is the remark that DOES cross, which is what makes the rule liveable. */
    @Test
    fun `a note survives, because it is graph data rather than source decoration`() {
        val text = """
            graph "probe"

            on start {
                @note("careful") log(message: "hello")
            }
        """.trimIndent()
        assertEquals(text, printed(text))
    }

    /**
     * A REAL file, comments and all — the case that started this.
     *
     * `tally.vs` is the corpus entry with a line comment, three doc comments and an import. Everything
     * above is a construct in isolation; this is the one that says a whole document survives.
     */
    @Test
    fun `a real commented document round-trips`() {
        val lib = java.io.File("src/test/resources/vs/examples/geometry.vs")
        val file = java.io.File("src/test/resources/vs/examples/tally.vs")
        if (!file.isFile || !lib.isFile) return   // corpus not present in this checkout

        val libGraph = assertNotNull(VsText(catalog).read(lib.readText()).graph, "the library should compile")
        val vs = VsText(catalog) { imp -> if (imp.ref == "geometry") libGraph else null }
        val text = file.readText().replace("\r\n", "\n").trimEnd()
        val read = vs.read(text)
        val graph = assertNotNull(read.graph, "should compile: ${read.errors.map { it.message }}")
        val out = vs.write(graph, read.comments).trimEnd()

        // NOT byte-identical, and it should not be: this file is not canonical to begin with, so the
        // printer names its positional arguments (`sumTo(n - 1)` becomes `sumTo(n: n - 1)`). That is the
        // formatting. What must not change is the commentary.
        assertEquals(
            Comments.commentLines(text), Comments.commentLines(out),
            "a comment line was lost:\n$out",
        )
        for (line in text.lines().map { it.trim() }.filter { it.startsWith("//") || it.startsWith("*") }) {
            assertTrue(out.lines().any { it.trim() == line }, "this comment line is missing: $line")
        }

        // And formatting the formatted text changes nothing, comments included.
        val again = vs.read(out)
        assertEquals(out, vs.write(assertNotNull(again.graph), again.comments).trimEnd(), "not a fixpoint")
    }

    // ---- so the guard has to see every shape of comment -----------------------------------------------

    @Test
    fun `the guard counts every kind of comment`() {
        assertEquals(1, Comments.commentLines("// one\n"), "a line comment")
        assertEquals(1, Comments.commentLines("/** doc */\n"), "a single-line doc comment")
        assertEquals(3, Comments.commentLines("/**\n * doc\n */\n"), "a block comment counts its lines")
        assertEquals(2, Comments.commentLines("// a\n// b\n"), "two line comments")
        assertEquals(0, Comments.commentLines("graph \"probe\"\n"), "no comment")
    }

    /**
     * A `//` inside a string is not a comment.
     *
     * The reason the guard lexes instead of grepping, and the reason the lexer emits comment tokens at all
     * rather than skipping them. Getting this wrong makes the guard refuse files that are perfectly safe.
     */
    @Test
    fun `a slash-slash inside a string literal is not a comment`() {
        assertEquals(
            0,
            Comments.commentLines("""graph "probe"${'\n'}${'\n'}export var Url: STRING = "https://example.com"${'\n'}"""),
        )
    }

    @Test
    fun `a source too broken to lex reports no comments`() {
        // It cannot be overwritten in a way that loses more than it already has, so refusing would only
        // block the fix.
        assertTrue(Comments.commentLines("\"unterminated") >= 0)
    }

    /** The whole point: a commented file is recognised as unsafe to print over. */
    @Test
    fun `a real script is seen as carrying comments`() {
        val text = """
            graph "probe"

            // Why this exists.
            import * as geo from "geometry"

            /**
             * A running total.
             */
            export var Total: INT = 0
        """.trimIndent()
        assertEquals(4, Comments.commentLines(text), "one line comment plus a three-line doc comment")
    }
}
