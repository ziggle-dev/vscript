package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.Severity
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphSource
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.text.TextFrontEnd
import dev.ziggle.vscript.text.TextSource
import dev.ziggle.vscript.text.natives
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every `vs` example in `docs/LANGUAGE.md`, compiled.
 *
 * **Documentation that is not built is documentation that is wrong**, and a language reference is the
 * worst place for it: the reader has no way to tell a typo from a feature they have not understood, and
 * the examples are exactly what gets copied. So the reference is a test fixture — each fenced block is
 * lowered and validated against the real catalogue, and a mistake in prose fails the build.
 *
 * Blocks are complete documents by default. `graph "x"` and all. That is a little verbose for a
 * two-line illustration and it is the price of every example being runnable rather than suggestive:
 * a fragment cannot be checked, and a fragment is what goes stale.
 *
 * Examples that IMPORT another example resolve against the other blocks in the file, which is how the
 * import section can show both halves and have both checked.
 */
class DocsTest {

    // The reference's examples use `Tile` — a record the HOST declares, not a language type — because a
    // concrete example beats an abstract one. The fixture stands in for the pack that declares it, so the
    // examples compile here exactly as they do against a real one. See TileFixture.
    private val catalog = NodeCatalog(
        dev.ziggle.vscript.domain.TileFixture.DESCRIPTORS + dev.ziggle.vscript.domain.ColorFixture.DESCRIPTORS,
    )

    private fun doc(): String {
        // Wherever the test happens to be run from. `../docs` is the one that fires today: the reference
        // is the REPO's, not this module's, so it stayed at the root when the language moved into
        // `:vscript-lang` — and a test's working directory is its own project.
        for (path in listOf("../docs/LANGUAGE.md", "docs/LANGUAGE.md", "vscript/docs/LANGUAGE.md")) {
            val f = File(path)
            if (f.isFile) return f.readText()
        }
        error("cannot find docs/LANGUAGE.md")
    }

    /** One example: where it starts, its source, and which front end is meant to compile it. */
    private class Example(val line: Int, val body: String, val textOnly: Boolean)

    /**
     * Every ```vs fenced block, with the line it starts on so a failure can be found.
     *
     * A block preceded by `<!-- text -->` is compiled by the TEXT front end instead of by lowering.
     * **An explicit marker rather than a guess**, because the two surfaces have genuinely diverged: a
     * record that holds one of its own kind, a type reached through a document this one never imported,
     * and `test`/`assert` all compile as text and have no nodes at all — while the text front end in turn
     * still lacks things the canvas has (`?.`, generic `type`, map iteration). Sniffing for constructs
     * would mean re-deriving that list here, and getting it wrong reports a correct example as broken.
     */
    private fun blocks(): List<Example> {
        val out = ArrayList<Example>()
        val lines = doc().lines()
        var i = 0
        while (i < lines.size) {
            if (lines[i].trim() == "```vs") {
                val marked = (i > 0 && lines[i - 1].trim() == "<!-- text -->")
                val start = i + 2
                val body = StringBuilder()
                i++
                while (i < lines.size && lines[i].trim() != "```") {
                    body.appendLine(lines[i])
                    i++
                }
                out += Example(start, body.toString(), marked)
            }
            i++
        }
        return out
    }

    /**
     * A block that declares a `test` is compiled through the TEXT front end, not lowered.
     *
     * `test` and `assert` have no nodes — the canvas cannot run one, and `Lower` refuses them by design —
     * so lowering an example of them would report the reference as wrong when it is right. Routing them
     * instead of skipping them is what keeps the guarantee this test exists for: every example in the
     * reference is compiled, so a wrong one fails the build rather than misleading a reader.
     */
    private fun declaresTest(text: String): Boolean = Regex("(?m)^\\s*test\\s+\"").containsMatchIn(text)


    private fun lower(text: String, known: List<Graph>): Lower.Result {
        val parsed = Parser(Lexer(text).lex()).parse()
        assertTrue(parsed.ok, "parse errors: ${parsed.errors.map { "${it.span} ${it.message}" }}")
        return Lower(catalog, source = GraphSource.of(known)).lower(parsed.program)
    }

    @Test
    fun `every example in the language reference compiles`() {
        val found = blocks()
        assertTrue(found.size >= 20, "expected the reference to be full of examples, found ${found.size}")

        val text = found.filter { it.textOnly || declaresTest(it.body) }
        val graph = found - text.toSet()

        val problems = ArrayList<String>()

        // ---- the text front end, for the examples that are about it ------------------------------------
        //
        // Every marked block is offered to every other as an importable document, keyed by its `graph`
        // line, so a multi-document example works the way it reads — which is the whole point of the one
        // that shows a type crossing three documents.
        val sources = HashMap<String, String>()
        for (e in text) nameOf(e.body)?.let { sources[it] = e.body }
        val imports = TextSource.of(sources)
        for (e in text) {
            val ref = nameOf(e.body) ?: "<example>"
            val read = TextFrontEnd(catalog.natives(), imports = imports, rootRef = ref).compileTests(e.body)
            for (err in read.errors) {
                problems += "LANGUAGE.md:${e.line} — text: ${err.message} (example line ${err.span.line})"
            }
        }

        // ---- and lowering, for everything else ---------------------------------------------------------
        //
        // Two passes: the first lowers each block so a later one can import it, the second checks
        // everything against the full set. Order in the document then does not matter.
        val known = ArrayList<Graph>()
        for (e in graph) runCatching { lower(e.body, known) }.getOrNull()?.let { known += it.graph }

        for (e in graph) {
            val result = runCatching { lower(e.body, known) }.getOrElse { ex ->
                problems += "LANGUAGE.md:${e.line} — ${ex.message?.take(200)}"
                continue
            }
            for (err in result.errors) {
                problems += "LANGUAGE.md:${e.line} — lowering: ${err.message} (example line ${err.span.line})"
            }
            if (result.errors.isEmpty()) {
                for (issue in Validator(catalog, GraphSource.of(known)).validate(result.graph)) {
                    if (issue.severity == Severity.ERROR) {
                        problems += "LANGUAGE.md:${e.line} — validation: ${issue.message}"
                    }
                }
            }
        }

        assertTrue(problems.isEmpty(), "the reference is wrong:\n" + problems.joinToString("\n"))
    }

    /** The document name an example gives itself, so a later block can import it. */
    private fun nameOf(body: String): String? =
        Regex("""^\s*graph\s+"([^"]+)"""", RegexOption.MULTILINE).find(body)?.groupValues?.get(1)

    /** Everything wrong with [src] — lowering AND validation, since a limit may be enforced by either. */
    private fun errorsIn(src: String): List<String> {
        val r = lower(src, emptyList())
        if (r.errors.isNotEmpty()) return r.errors.map { it.message }
        return Validator(catalog, GraphSource.of(emptyList())).validate(r.graph)
            .filter { it.severity == Severity.ERROR }.map { it.message }
    }

    /**
     * The boundaries the reference states in prose, pinned.
     *
     * The compiled examples above prove everything the reference says you CAN do. These prove the other
     * half - the handful of places it says "this is refused, and here is why". If one of them starts
     * compiling, this fails, which is the prompt to go and rewrite the paragraph rather than leave it
     * telling people not to do something they now can.
     *
     * Anything that is a *defect* rather than a decision belongs in `docs/GAPS.md` instead; these are the
     * deliberate ones.
     */
    @Test
    fun `the boundaries the reference states are still real`() {
        // 7.2 - `!= null` narrows a bound NAME. A graph variable's cell can be written from anywhere,
        // including by something the branch itself calls, so it is deliberately not narrowed.
        refuses(
            "a graph variable narrowed by a null comparison",
            """
            graph "probe"
            export var Home: TILE? = null
            export fn takes(t: TILE) -> INT = t.x
            on start {
                if Home != null {
                    log(message: "" + takes(t: Home))
                }
            }
            """,
        )

        // 4.5 - one document, one meaning per name. A call names its function by name.
        refuses(
            "two functions of one name in one document",
            """
            graph "probe"
            export fn LIST<INT>.describe(self) -> STRING = "ints"
            export fn LIST<STRING>.describe(self) -> STRING = "strings"
            on start { log(message: [1].describe()) }
            """,
        )

        // 4.7 - no variance. A LIST<INT> does not flow into a LIST<STRING> in either direction.
        refuses(
            "a list of one element type wired into another",
            """
            graph "probe"
            export fn takes(xs: LIST<STRING>) -> INT = _listCount(list: xs)
            on start {
                // Bound to a local first, which is the form that carries an element type. A literal
                // wired straight into the parameter is NOT checked — see docs/GAPS.md.
                val ns = [1, 2]
                log(message: "" + takes(xs: ns))
            }
            """,
        )

        // 4.4 - an enum member is its name at run time, so `is` could not tell one from any other text.
        refuses(
            "`is` on an enum",
            """
            graph "probe"
            export enum Phase { Chop, Bank }
            export var State: Phase = Phase.Chop
            on start { if State is Phase { log(message: "yes") } }
            """,
        )

        // 3.1 - a record is a value, so a parameter is a `let` and writing through it would update this
        // call's own copy while the caller saw nothing.
        refuses(
            "assigning a field through a parameter",
            """
            graph "probe"
            export type Course { laps: INT }
            export fn bump(c: Course) { c.laps = c.laps + 1 }
            on start { bump(c: Course { laps: 0 }) }
            """,
        )
    }

    /** [src] must not compile, or the reference is claiming a boundary the language no longer has. */
    private fun refuses(what: String, src: String) {
        assertTrue(
            errorsIn(src.trimIndent().trim()).isNotEmpty(),
            "the reference says the language refuses $what - it no longer does",
        )
    }
}
