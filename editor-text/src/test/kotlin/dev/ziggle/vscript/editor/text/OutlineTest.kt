package dev.ziggle.vscript.editor.text

import dev.ziggle.vscript.lang.Lexer
import dev.ziggle.vscript.lang.Parser
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The document outline — one model behind the structure view and symbol search.
 *
 * The tests that matter are the last two: that an outline is still produced for a file that does **not**
 * parse, and that every line it reports is the line the declaration is actually on. A structure view whose
 * lines are off takes you to the wrong place, which is worse than not having one.
 */
class OutlineTest {

    private val SRC = """
        graph "probe"

        import * as util from "core/util"

        type Point { x: INT, y: INT }

        enum Phase { Chop, Bank }

        var Count: INT = 0

        fn helper(n: INT) -> INT = n + 1

        on start {
            Count = helper(n: 1)
        }

        on sleep {
            Count = 0
        }
    """.trimIndent()

    private fun outlineOf(src: String): Outline {
        val parsed = Parser(Lexer(src).lex(), src).parse()
        return Outline.of(parsed.program, LineIndex.of(src))
    }

    @Test
    fun `it lists every kind of declaration`() {
        val o = outlineOf(SRC)
        val byName = o.symbols.associateBy { it.name }

        assertEquals(Outline.Kind.RECORD, byName.getValue("Point").kind)
        assertEquals(Outline.Kind.ENUM, byName.getValue("Phase").kind)
        assertEquals(Outline.Kind.VARIABLE, byName.getValue("Count").kind)
        assertEquals(Outline.Kind.FUNCTION, byName.getValue("helper").kind)
        assertEquals(Outline.Kind.IMPORT, byName.getValue("util").kind)
    }

    /** Entries are named the way an author refers to them — nobody looks for "the entry declaration". */
    @Test
    fun `entries are named by their word`() {
        val names = outlineOf(SRC).symbols.filter { it.kind == Outline.Kind.ENTRY }.map { it.name }
        assertTrue("on start" in names, "expected 'on start' among $names")
        assertTrue("on sleep" in names, "expected 'on sleep' among $names")
    }

    @Test
    fun `a record's fields and an enum's members are its children`() {
        val o = outlineOf(SRC)
        val point = o.symbols.first { it.name == "Point" }
        assertEquals(listOf("x", "y"), point.children.map { it.name })
        val phase = o.symbols.first { it.name == "Phase" }
        assertEquals(listOf("Chop", "Bank"), phase.children.map { it.name })
        // ...and flatten reaches them, which is what a search looks through.
        assertTrue("x" in o.flatten().map { it.name })
    }

    @Test
    fun `it keeps declaration order, not alphabetical`() {
        // A structure view that sorts stops being a map of the file.
        val names = outlineOf(SRC).symbols.map { it.name }
        assertEquals(
            listOf("util", "Point", "Phase", "Count", "helper"),
            names.filter { it in setOf("util", "Point", "Phase", "Count", "helper") },
        )
    }

    // ---- the two that matter ------------------------------------------------------------------------

    /**
     * Every reported line is the line the declaration is on.
     *
     * Asserted against the source rather than against constants: a hand-written expectation drifts the
     * moment the fixture is edited, and the failure it produces looks like an outline bug.
     */
    @Test
    fun `every symbol's line holds its declaration`() {
        val o = outlineOf(SRC)
        val ix = LineIndex.of(SRC)
        for (s in o.symbols) {
            val text = ix.textOf(SRC, s.line)
            val needle = when (s.kind) {
                Outline.Kind.ENTRY -> s.name          // "on start"
                Outline.Kind.IMPORT -> "import"
                else -> s.name
            }
            assertTrue(
                needle in text,
                "'${s.name}' (${s.kind}) reported line ${s.line}, which reads '$text'",
            )
        }
    }

    /**
     * **An outline for a file that does not parse.**
     *
     * The reason this is built from the AST rather than from a `Resolution`: the parser resynchronises at
     * declaration boundaries, so a half-typed declaration costs itself and not the file. A structure view
     * that emptied on every keystroke would be unusable.
     */
    @Test
    fun `a broken declaration does not cost the ones around it`() {
        val broken = """
            graph "probe"

            var Count: INT = 0

            var Bad INT = 0

            fn helper(n: INT) -> INT = n + 1

            on start {
                Count = 1
            }
        """.trimIndent()

        val parsed = Parser(Lexer(broken).lex(), broken).parse()
        assertTrue(!parsed.ok, "the fixture was supposed to be broken")

        val names = Outline.of(parsed.program, LineIndex.of(broken)).symbols.map { it.name }
        assertTrue("Count" in names, "the declaration before the break was lost: $names")
        assertTrue("helper" in names, "the declaration after the break was lost: $names")
        assertTrue("on start" in names, "the entry after the break was lost: $names")
    }

    /**
     * **What recovery costs depends on DELIMITERS, not on how wrong the code is.**
     *
     * Measured across eight broken shapes, and the result is not the one you would guess — a file of pure
     * garbage recovers completely, while a single unclosed bracket hides everything after it:
     *
     * | break | what the outline still sees |
     * |---|---|
     * | `var Bad INT = 0`, `type Broken { x: }`, `@@@` | everything — full recovery |
     * | `fn broken() { let }` | everything, including `broken` itself |
     * | `fn broken(` | the next declaration is eaten as its parameter list |
     * | `fn broken( {` | nothing after the break at all |
     *
     * `syncToDeclaration` counts braces rather than searching for the next `fn`, because landing inside a
     * broken block would report a second error for something that is not wrong. An unbalanced opener has
     * no partner to count, so the scan runs to end of file.
     *
     * That is the common case while typing a new function, so it is worth knowing rather than
     * discovering: the structure view thins out until the bracket is closed. Improving it means changing
     * the parser's recovery — a language change with a wide blast radius — so it is recorded here rather
     * than done casually as part of a view.
     */
    @Test
    fun `an unclosed bracket hides what comes after it`() {
        val broken = """
            graph "probe"

            var Count: INT = 0

            fn broken( {

            on start {
                Count = 1
            }
        """.trimIndent()

        val parsed = Parser(Lexer(broken).lex(), broken).parse()
        val names = Outline.of(parsed.program, LineIndex.of(broken)).symbols.map { it.name }

        assertTrue("Count" in names, "the declaration before the break should still be listed: $names")
        assertTrue(
            names.none { it == "on start" },
            "if this now passes, the parser's recovery improved -- delete this test and its note: $names",
        )
    }
}
