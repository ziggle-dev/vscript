package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Severity
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `text.*` builtins.
 *
 * **The language had no string library at all.** It could build text (`+`, `value.format`) and read a
 * number out of a WHOLE string (`value.parseInt`), and nothing in between — no length, no index, no slice,
 * no split, no trim. A script holding `"Golovanova seed (level 34)"` could ask whether it contained
 * "level" and then had nowhere to go: `parseInt` refuses anything with characters left over, which is the
 * right rule and left the number unreachable.
 *
 * These live in the language rather than the SDK because they touch no avatar and know nothing about a
 * game, so a graph using them runs anywhere the VM does — the same reason `list.*` and `value.*` are here.
 */
class TextNodesTest {

    private val sayNode = hostNode(
        "test.say", "test.say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val catalog = NodeCatalog(listOf(sayNode))

    private fun graphOf(src: String): Graph {
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse: ${parsed.errors.map { "${it.span} ${it.message}" }}")
        val low = Lower(catalog).lower(parsed.program)
        assertTrue(low.ok, "lower: ${low.errors.map { it.message }}")
        assertEquals(
            emptyList(),
            Validator(catalog).validate(low.graph).filter { it.severity == Severity.ERROR }.map { it.message },
            "did not validate",
        )
        return low.graph
    }

    /** Run a body of statements and collect everything it said. */
    private fun said(body: String): List<Any?> {
        val src = "graph \"t\"\n\non start {\n$body\n}\n"
        val g = graphOf(src)
        val said = ArrayList<Any?>()
        val hosts = BuiltinHosts.registry()
        hosts.register("test.say", HostKind.INLINE, arity = 1) { a -> said += a[0]; null }
        val r = drive(GraphCompiler(catalog, debug = false).compile(g, g.entries(catalog).single().id), hosts, maxTicks = 20000)
        assertNull(r.fiber.error, "vm error: ${r.fiber.error}")
        return said.map { if (it is Number && it !is Double) it.toLong() else it }
    }

    private fun one(expr: String): Any? = said("    say(message: $expr)").single()

    // ---- the case this was built for -----------------------------------------------------------------

    /**
     * `"Golovanova seed (level 34)"` -> 34, the way a seed table's dialogue option arrives.
     *
     * Both spellings, because both are wanted: `between` when the surrounding words are known and stable,
     * `numberIn` when the only dependable thing is that there is a number in there somewhere.
     */
    @Test
    fun `the level comes out of a seed option`() {
        val option = "\"Golovanova seed (level 34)\""
        assertEquals(34L, one("parseInt(text: between(text: $option, after: \"level \", before: \")\") ?: \"\") ?: 0"))
        assertEquals(34L, one("numberIn(text: $option) ?: 0"))
    }

    /** And the whole-string parse still refuses it, which is why `numberIn` had to be its own node. */
    @Test
    fun `parseInt still refuses a string that is not only a number`() {
        assertEquals(-1L, one("parseInt(text: \"level 34)\") ?: -1"))
    }

    // ---- between -------------------------------------------------------------------------------------

    @Test
    fun `between finds the text separating two markers`() {
        assertEquals("34", one("between(text: \"(level 34)\", after: \"level \", before: \")\") ?: \"?\""))
    }

    /** An empty marker anchors at that END, so this doubles as a prefix / suffix cutter. */
    @Test
    fun `an empty marker means the start or the end`() {
        assertEquals("ab", one("between(text: \"ab|cd\", after: \"\", before: \"|\") ?: \"?\""))
        assertEquals("cd", one("between(text: \"ab|cd\", after: \"|\", before: \"\") ?: \"?\""))
    }

    /**
     * A missing marker is `nothing`, never `""`.
     *
     * The distinction that makes this safe to chain: "the marker was not there" and "the gap between two
     * markers that WERE there is empty" are different facts, and a caller that treats the first as an
     * empty string goes on to parse it and gets a confident zero.
     */
    @Test
    fun `a missing marker is nothing, and an empty gap is not`() {
        assertEquals("none", one("between(text: \"(level 34)\", after: \"tier \", before: \")\") ?: \"none\""))
        assertEquals("", one("between(text: \"(level )\", after: \"level \", before: \")\") ?: \"none\""))
    }

    /** [Before] is the first one AFTER [After], not the first in the string. */
    @Test
    fun `before is measured from after`() {
        assertEquals("b", one("between(text: \")a)b)\", after: \"a)\", before: \")\") ?: \"?\""))
    }

    // ---- numberIn ------------------------------------------------------------------------------------

    @Test
    fun `numberIn reads the first number anywhere`() {
        assertEquals(12L, one("numberIn(text: \"12 of 20\") ?: -1"))
        assertEquals(20L, one("numberIn(text: \"12 of 20\", skip: 1) ?: -1"))
        assertEquals(-1L, one("numberIn(text: \"no digits here\") ?: -1"))
    }

    /** Thousands commas are part of the number; a minus sign is too. */
    @Test
    fun `numberIn handles commas and a minus`() {
        assertEquals(1234L, one("numberIn(text: \"1,234 coins\") ?: 0"))
        assertEquals(-5L, one("numberIn(text: \"at -5 degrees\") ?: 0"))
    }

    // ---- index and slice -----------------------------------------------------------------------------

    @Test
    fun `indexOf answers a position or minus one`() {
        assertEquals(2L, one("positionOf(text: \"abcabc\", part: \"c\")"))
        assertEquals(5L, one("positionOf(text: \"abcabc\", part: \"c\", from: 3)"))
        assertEquals(-1L, one("positionOf(text: \"abc\", part: \"z\")"))
        assertEquals(5L, one("lastPositionOf(text: \"abcabc\", part: \"c\")"))
    }

    @Test
    fun `slice takes a range, with minus one meaning the end`() {
        assertEquals("bcd", one("slice(text: \"abcde\", from: 1, to: 4)"))
        assertEquals("bcde", one("slice(text: \"abcde\", from: 1)"))
    }

    /**
     * Out-of-range bounds CLAMP rather than throw.
     *
     * These indexes usually come from Index Of, whose "not found" answer is -1, so out-of-range arithmetic
     * is the ordinary case rather than a rare mistake — and a VM error in the middle of a run is a far
     * worse answer than a short string.
     */
    @Test
    fun `slice clamps instead of throwing`() {
        assertEquals("abc", one("slice(text: \"abc\", from: -5, to: 99)"))
        assertEquals("", one("slice(text: \"abc\", from: 2, to: 1)"))
        assertEquals("", one("slice(text: \"\", from: 0, to: 5)"))
    }

    // ---- the rest ------------------------------------------------------------------------------------

    @Test
    fun `length trim replace and case`() {
        assertEquals(5L, one("length(text: \"abcde\")"))
        assertEquals("ab", one("trim(text: \"  ab  \")"))
        assertEquals("a-b-c", one("replace(text: \"a b c\", find: \" \", with: \"-\")"))
        assertEquals("AB", one("upper(text: \"aB\")"))
        assertEquals("ab", one("lower(text: \"aB\")"))
    }

    /** [replace] is plain text on both sides — a `.` is a full stop, not a pattern. */
    @Test
    fun `replace is literal, not a pattern`() {
        assertEquals("aXc", one("replace(text: \"a.c\", find: \".\", with: \"X\")"))
    }

    @Test
    fun `startsWith and endsWith are case-sensitive`() {
        assertEquals(true, one("startsWith(text: \"Bologano seed\", part: \"Bolo\")"))
        assertEquals(false, one("startsWith(text: \"Bologano seed\", part: \"bolo\")"))
        assertEquals(true, one("endsWith(text: \"Bologano seed\", part: \"seed\")"))
    }

    @Test
    fun `split and join are inverses`() {
        assertEquals(3L, one("_listCount(list: splitOn(text: \"a,b,c\", on: \",\"))"))
        assertEquals("a,b,c", one("joinWith(parts: splitOn(text: \"a,b,c\", on: \",\"), with: \",\")"))
    }

    /** Empties are kept and pieces are not trimmed — both are the caller's to undo, not ours to guess. */
    @Test
    fun `split keeps empties and does not trim`() {
        assertEquals(3L, one("_listCount(list: splitOn(text: \"a,,b\", on: \",\"))"))
        assertEquals(" b", one("splitOn(text: \"a, b\", on: \",\")[1]"))
    }

    /** An empty separator gives the whole string back rather than every character. */
    @Test
    fun `splitting on nothing gives one part`() {
        assertEquals(1L, one("_listCount(list: splitOn(text: \"abc\", on: \"\"))"))
    }
    // ---- names -----------------------------------------------------------------------------------------

    /**
     * Every `text.*` node is callable by its SHORT name, and nothing that already was has lost one.
     *
     * **A name collision is silent and two-sided.** `Names` gives a node its bare spelling only when the
     * last segment is unique across the whole catalogue; when two nodes want the same one, BOTH fall back
     * to the full type. So adding `text.indexOf` beside the existing `list.indexOf` would not merely have
     * left the new node needing to be written `text.indexOf(…)` — it would have taken `indexOf` away from
     * `list.indexOf`, breaking every script already using it, with nothing to say so but a
     * "nothing here is called indexOf" at the call site. Hence `positionOf` and `splitOn`.
     */
    @Test
    fun `the text nodes have short names and steal none`() {
        val plain = NodeCatalog(emptyList())
        val names = Names(plain)
        val text = plain.all.filter { it.type.startsWith("text.") }
        assertTrue(text.size >= 13, "expected the whole text family, found ${text.size}")
        for (d in text) {
            assertEquals(
                d.type.substringAfterLast('.'),
                names.textName(d.type),
                "${d.type} did not get its short name — something else has claimed it",
            )
        }
        // `list.indexOf` is `_listIndexOf` now. The container verbs took the `_` prefix so that the bare
        // English words go back to script authors — a builtin claiming `indexOf` took it from everyone.
        assertEquals("_listIndexOf", names.textName("list.indexOf"))
        // `struct.split` is record DESTRUCTURING — syntax (`val (a, b) = …`), never a call — so it is
        // not in the callable set and `split` was never its to lose. Pinned so the next person to reach
        // for `text.split` learns that from a test rather than from the name quietly not resolving.
        assertNull(names.textNameOrNull("struct.split"), "struct.split is syntax, not a callable name")
    }

}
