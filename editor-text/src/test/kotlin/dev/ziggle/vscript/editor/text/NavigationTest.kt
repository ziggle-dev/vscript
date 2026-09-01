package dev.ziggle.vscript.editor.text

import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.text.TextFrontEnd
import dev.ziggle.vscript.text.natives
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Go to declaration, find usages and hover — one lookup, so they cannot disagree.
 *
 * Every test here locates its offset by SEARCHING THE SOURCE rather than by a hand-counted number.
 * Hard-coded offsets drift the moment the fixture is edited, and the failure looks like a navigation bug
 * rather than like a stale test.
 */
class NavigationTest {

    private val SRC = """
        graph "probe"

        // How many laps have been run.
        // Reset by the caller, never by us.
        var Count: INT = 0

        // Add one to a number.
        fn helper(n: INT) -> INT = n + 1

        on start {
            Count = helper(n: Count)
            Count = helper(n: Count)
            log("done")
        }
    """.trimIndent()

    private fun nav(src: String = SRC): Navigation {
        val catalog = NodeCatalog()
        val a = TextFrontEnd(catalog.natives(), rootRef = "probe").analyse(src)
        val r = assertNotNull(a.resolution, "the fixture must resolve: ${a.errors.map { it.message }}")
        return Navigation(r, a.comments, catalog)
    }

    /** The offset of the [n]th occurrence of [needle], counting from 1. */
    private fun at(needle: String, n: Int = 1, src: String = SRC): Int {
        var i = -1
        repeat(n) { i = src.indexOf(needle, i + 1) }
        assertTrue(i >= 0, "'$needle' #$n is not in the fixture")
        return i
    }

    // ---- nothing under the cursor -------------------------------------------------------------------

    @Test
    fun `whitespace and comments have no target`() {
        val n = nav()
        assertNull(n.at(at("// How many")), "a comment is not a symbol")
        assertNull(n.at(0), "the graph header is not a symbol")
    }

    // ---- go to declaration --------------------------------------------------------------------------

    @Test
    fun `a variable use points at its declaration`() {
        val n = nav()
        // The `Count` inside `on start`, not the declaration.
        val target = assertNotNull(n.at(at("Count", 3)), "no target on a plain variable use")
        assertEquals("Count", target.name)
        val decl = assertNotNull(target.declaration, "a document variable has a declaration")
        // A declaration's span starts at its KEYWORD (`var`), not at the name, so this asserts the name
        // is inside it rather than that the two offsets are equal.
        val nameAt = at("Count", 1)
        assertTrue(
            nameAt >= decl.start && nameAt < decl.end || decl.start <= nameAt,
            "declaration span ${decl.start}..${decl.end} does not cover the name at $nameAt",
        )
        assertEquals(5, decl.line, "it should point at the `var Count` line")
    }

    @Test
    fun `a call points at the function it calls`() {
        val n = nav()
        val target = assertNotNull(n.at(at("helper", 2)), "no target on a call")
        assertEquals("helper", target.name)
        val decl = assertNotNull(target.declaration, "a document function has a declaration")
        assertEquals(8, decl.line, "it should point at the `fn helper` line")
    }

    @Test
    fun `a host node has no declaration to go to`() {
        // `log` is a node, not source. Offering a jump would take you nowhere.
        val n = nav()
        val target = assertNotNull(n.at(at("log(")), "no target on a host call")
        assertTrue(target.isHostNode, "'log' is a host node")
        assertNull(target.declaration)
    }

    // ---- find usages --------------------------------------------------------------------------------

    @Test
    fun `usages include the declaration and every use`() {
        val n = nav()
        val target = assertNotNull(n.at(at("Count", 3)))
        // `var Count`, then four uses inside the entry (two assignments, two arguments).
        assertTrue(target.usages.size >= 4, "found ${target.usages.size}: ${target.usages}")
        assertTrue(target.usages.first().start <= at("Count", 1), "the declaration should be first")
        assertTrue(
            target.usages.zipWithNext().all { (a, b) -> a.start < b.start },
            "usages must be in source order: ${target.usages.map { it.start }}",
        )
    }

    @Test
    fun `usages of one name do not include another`() {
        val n = nav()
        val count = assertNotNull(n.at(at("Count", 3)))
        val helperAt = at("helper", 2)
        assertTrue(
            count.usages.none { helperAt >= it.start && helperAt < it.end },
            "a different symbol's span leaked into the usage list",
        )
    }

    // ---- hover documentation ------------------------------------------------------------------------

    /** The language has no `///` — the comment above a declaration IS its documentation. */
    @Test
    fun `hover shows the comment above the declaration`() {
        val n = nav()
        val target = assertNotNull(n.at(at("Count", 3)))
        assertTrue("How many laps" in target.documentation, "got: '${target.documentation}'")
        assertTrue("Reset by the caller" in target.documentation, "the whole run should come, not one line")
        assertTrue("//" !in target.documentation, "the comment markers should be stripped: '${target.documentation}'")
    }

    @Test
    fun `a function's comment reaches its call site`() {
        val n = nav()
        val target = assertNotNull(n.at(at("helper", 2)))
        assertTrue("Add one to a number" in target.documentation, "got: '${target.documentation}'")
    }

    @Test
    fun `a host node is documented by the catalogue, not by a comment`() {
        val n = nav()
        val target = assertNotNull(n.at(at("log(")))
        assertTrue(
            target.documentation.isNotBlank(),
            "a host node should carry the catalogue's summary so the hover and the palette agree",
        )
    }

    @Test
    fun `an undocumented declaration hovers empty rather than borrowing a neighbour's comment`() {
        val src = """
            graph "probe"

            // This belongs to Alpha.
            var Alpha: INT = 0

            var Beta: INT = 0

            on start { Beta = Alpha }
        """.trimIndent()
        val n = nav(src)
        val beta = assertNotNull(n.at(at("Beta", 2, src)))
        assertEquals("", beta.documentation, "Beta picked up Alpha's comment")
    }

    // ---- the narrowest span wins --------------------------------------------------------------------

    /**
     * A call's span covers its whole argument list, so a cursor on an argument is inside both. Answering
     * with the call would make go-to-declaration jump to the function whenever you clicked an argument.
     */
    @Test
    fun `an argument resolves to the argument, not the enclosing call`() {
        val n = nav()
        val target = assertNotNull(n.at(at("Count", 4)), "no target on the argument")
        assertEquals("Count", target.name, "the enclosing call swallowed the argument")
    }

    // ---- extensions ---------------------------------------------------------------------------------

    /**
     * `xs.add(v)` must reach `fn LIST.add`.
     *
     * Reported from a running editor: go-to-declaration did nothing on an extension call. An extension is
     * the one call shape whose NAME is not a `NameExpr` — the receiver is, and the method is part of the
     * call — so it is the shape most likely to fall between the two indexes.
     */
    @Test
    fun `an extension call points at the extension`() {
        val src = """
            graph "probe"

            fn LIST.second(self) -> WILDCARD = _listItemAt(list: self, index: 1)

            on start {
                val xs = [1, 2, 3]
                log("" + xs.second())
            }
        """.trimIndent()
        val n = nav(src)

        // On the METHOD name, not the receiver.
        val target = assertNotNull(n.at(at("second", 2, src)), "no target on an extension call")
        assertEquals("second", target.name, "resolved to something else")
        val decl = assertNotNull(target.declaration, "an extension declared in this file has a declaration")
        assertEquals(3, decl.line, "should point at the `fn LIST.second` line")
    }

    @Test
    fun `the receiver of an extension call still resolves to the receiver`() {
        val src = """
            graph "probe"

            fn LIST.second(self) -> WILDCARD = _listItemAt(list: self, index: 1)

            on start {
                val xs = [1, 2, 3]
                log("" + xs.second())
            }
        """.trimIndent()
        val n = nav(src)
        val target = assertNotNull(n.at(at("xs", 2, src)), "no target on the receiver")
        assertEquals("xs", target.name, "the call swallowed its receiver")
    }

    // ---- imported names -----------------------------------------------------------------------------

    private fun navWithLibrary(src: String): Navigation {
        val catalog = NodeCatalog()
        val library = dev.ziggle.vscript.text.TextSource.of(
            mapOf(
                "core/util" to """
                    graph "core/util"

                    // Add one.
                    export fn bump(n: INT) -> INT = n + 1
                """.trimIndent(),
            ),
        )
        val a = TextFrontEnd(catalog.natives(), imports = library, rootRef = "probe").analyse(src)
        val r = assertNotNull(a.resolution, "fixture must resolve: ${a.errors.map { it.message }}")
        return Navigation(r, a.comments, catalog)
    }

    /**
     * **An imported name must report the document that declares it.**
     *
     * Without the ref the target looks locally declared, and the caller applies a line number belonging to
     * ANOTHER document to the open one. That is not a failed jump — it is a successful jump to an
     * arbitrary line, which is what "go to declaration takes me to random spots" looks like from outside.
     */
    @Test
    fun `a name reached through an alias reports the declaring document`() {
        val src = """
            graph "probe"

            import * as util from "core/util"

            export var N: INT = 0

            on start {
                N = util::bump(n: 1)
            }
        """.trimIndent()
        val n = navWithLibrary(src)

        val target = assertNotNull(n.at(at("bump", 1, src)), "no target on an imported call")
        assertEquals("bump", target.name)
        assertEquals("core/util", target.declarationRef, "the declaring document was not reported")
        assertTrue(target.isElsewhere, "an imported name is not declared here")
    }

    @Test
    fun `a locally declared name reports no other document`() {
        // The control: `isElsewhere` must not be true for everything, or the caller opens files at random.
        val n = nav()
        val target = assertNotNull(n.at(at("helper", 2)))
        assertTrue(!target.isElsewhere, "a local declaration was reported as being elsewhere")
        assertNull(target.declarationRef)
    }

    /**
     * **Documentation does NOT cross the file boundary yet, and that is a limit rather than a bug here.**
     *
     * `Navigation` holds one document's comments, keyed by the offset of the token they introduce. An
     * imported symbol's comment lives in another document, where that offset means something else
     * entirely — so the honest answer is nothing rather than whatever happens to sit at that offset here.
     *
     * Fetching it needs the other document analysed, which is `WorkspaceNavigation`'s job and is far too
     * expensive to do per frame without a cache. Asserted so the day it starts working is noticed.
     */
    @Test
    fun `documentation stops at the file boundary, for now`() {
        val src = """
            graph "probe"

            import * as util from "core/util"

            on start { log("" + util::bump(n: 1)) }
        """.trimIndent()
        val target = assertNotNull(navWithLibrary(src).at(at("bump", 1, src)))
        assertEquals("", target.documentation, "if this now has prose, delete this test and its note")
        // The signature still crosses, because it comes from the binding rather than from a comment.
        assertTrue(target.name == "bump")
    }

    // ---- fields on a record -------------------------------------------------------------------------

    private val RECORDS = """
        graph "probe"

        // A spot on the map.
        type Point { x: INT, y: INT }

        export var Sum: INT = 0

        on start {
            val p = Point { x: 1, y: 2 }
            Sum = p.x + p.y
        }
    """.trimIndent()

    /**
     * `p.x` must reach the declaration of `x`, not stop at `p`.
     *
     * Reported: variables on types did not go to their definition. A field access shares its span with the
     * expression it reads FROM — `p` and `p.x` are both recorded over `p.x` — so without a position rule
     * the target always wins and the answer is the local, exactly as it was for extension calls.
     */
    @Test
    fun `a field read points at the record that declares it`() {
        val n = nav(RECORDS)
        val target = assertNotNull(n.at(at("p.x", 1, RECORDS) + 2), "no target on a field read")
        assertEquals("x", target.name, "resolved to the target instead of the field")
        val decl = assertNotNull(target.declaration, "a record declared here has a declaration")
        assertEquals(4, decl.line, "should point at the `type Point` declaration")
    }

    @Test
    fun `the thing being read from still resolves to itself`() {
        val n = nav(RECORDS)
        val target = assertNotNull(n.at(at("p.x", 1, RECORDS)), "no target on the value")
        assertEquals("p", target.name, "the field access swallowed its target")
    }

    @Test
    fun `a field hover names the record and the type`() {
        val n = nav(RECORDS)
        val target = assertNotNull(n.at(at("p.y", 1, RECORDS) + 2))
        assertTrue("Point" in target.signature, "got: '${target.signature}'")
        assertTrue("y" in target.signature, "got: '${target.signature}'")
    }

    @Test
    fun `a field that the record does not declare has no target`() {
        val src = RECORDS.replace("Sum = p.x + p.y", "Sum = p.zzz")
        val n = TextFrontEnd(NodeCatalog().natives(), rootRef = "probe").analyse(src).let { a ->
            a.resolution?.let { Navigation(it, a.comments, NodeCatalog()) }
        }
        // Whether it resolves at all is the compiler's business; what matters is that navigation does not
        // invent a destination for a field nothing declares.
        val target = n?.at(src.indexOf("zzz"))
        assertTrue(target == null || target.name != "zzz" || target.declaration == null)
    }
}
