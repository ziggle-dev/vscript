package dev.ziggle.vscript.lang

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Real scripts still lex and parse after a grammar change.
 *
 * **The corpus is in `src/test/resources/vs/`, not in `scripts/`.** That directory is gitignored apart from
 * two force-added files, so a test reading it would answer differently on every machine — writing a new
 * script could turn the build red, and the same commit could pass here and fail in CI. That is exactly what
 * got `RealGraphsTest` deleted, and pointing a new test at the same kind of directory would have been
 * repeating it with a different name. The fixtures are copies, checked in, and therefore reproducible.
 *
 * **Deliberately only as far as the PARSER.** Lowering needs the real game catalogue, which is a property
 * of the client rather than of the language. Syntax is what these files can answer for on their own, and
 * syntax is what a grammar change breaks.
 */
class ScriptsParseTest {

    private fun corpus(): List<Pair<String, String>> {
        val dir = "/vs/examples/"
        // Enumerated rather than scanned: a classpath directory listing is not portable out of a jar, and a
        // scan that silently found nothing would pass vacuously — which the count assertion below catches
        // either way, but naming them makes the corpus reviewable.
        val names = listOf("tour.vs", "patrol.vs", "modules.vs", "geometry.vs", "shapes.vs", "tally.vs", "toolkit.vs")
        return names.map { n ->
            val text = javaClass.getResource("$dir$n")?.readText()
                ?: error("missing fixture $dir$n — it is checked in under src/test/resources")
            n to text
        }
    }

    @Test
    fun `the corpus parses`() {
        val files = corpus()
        assertTrue(files.isNotEmpty(), "empty corpus — this test would pass vacuously")

        val failures = ArrayList<String>()
        for ((name, text) in files) {
            val r = try {
                Parser(Lexer(text).lex()).parse()
            } catch (e: VsSyntaxError) {
                failures += "$name: ${e.message}"
                continue
            }
            if (!r.ok) failures += "$name: " + r.errors.joinToString("; ") { "${it.span} ${it.message}" }
        }
        assertTrue(failures.isEmpty(), "scripts failed to parse:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `the corpus keeps its comments`() {
        // The point of moving off `comment` boxes and `///`: the commentary is still in the FILE. Before,
        // 89% of it was destroyed by a round trip because it had nowhere in the graph to live.
        for ((name, text) in corpus()) {
            val comments = Lexer(text).lex().count { it.type == TokenType.COMMENT }
            assertTrue(comments > 0, "$name kept no comments")
        }
    }

    @Test
    fun `the corpus is off the removed syntax`() {
        for ((name, text) in corpus()) {
            val bad = text.lines().withIndex().filter { (_, l) ->
                l.trimStart().startsWith("comment \"") || l.trimStart().startsWith("///")
            }
            assertTrue(bad.isEmpty(), "$name still uses removed syntax at lines ${bad.map { it.index + 1 }}")
        }
    }
}
