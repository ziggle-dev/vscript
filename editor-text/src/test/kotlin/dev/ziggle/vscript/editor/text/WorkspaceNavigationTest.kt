package dev.ziggle.vscript.editor.text

import dev.ziggle.vscript.model.NodeCatalog
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Go to declaration and find usages **across documents**.
 *
 * The test that matters most is the one about two documents each declaring a `helper`. Matching usages by
 * NAME passes every other test in this file and reports every unrelated `helper` in the workspace — a list
 * that looks right, is wrong, and is wrong in the direction nobody checks.
 */
class WorkspaceNavigationTest {

    private lateinit var root: File

    @BeforeEach
    fun setUp() {
        root = File.createTempFile("vs-wsnav", "").let { it.delete(); it.mkdirs(); it }
        write("core/util.vs", """
            graph "core/util"

            // Add one.
            export fn bump(n: INT) -> INT = n + 1

            export fn helper(n: INT) -> INT = n
        """.trimIndent())
        write("uses/one.vs", """
            graph "uses/one"

            import * as util from "core/util"

            on start {
                log("" + util::bump(n: 1))
            }
        """.trimIndent())
        write("uses/two.vs", """
            graph "uses/two"

            import * as util from "core/util"

            on start {
                log("" + util::bump(n: 2))
                log("" + util::bump(n: 3))
            }
        """.trimIndent())
        // Declares its OWN helper. Not a usage of core/util's.
        write("uses/three.vs", """
            graph "uses/three"

            fn helper(n: INT) -> INT = n * 2

            on start {
                log("" + helper(n: 1))
            }
        """.trimIndent())
    }

    @AfterEach
    fun tearDown() = root.deleteRecursively().let { }

    private fun write(path: String, body: String) {
        val f = File(root, path)
        f.parentFile.mkdirs()
        f.writeText(body)
    }

    private fun nav() = WorkspaceNavigation(Workspace(root), NodeCatalog())

    // ---- declaration --------------------------------------------------------------------------------

    @Test
    fun `it finds a declaration in another document`() {
        val at = assertNotNull(nav().declaration("core/util", "bump"), "no declaration found")
        assertEquals("core/util", at.ref)
        assertTrue("fn bump" in at.text, "landed on '${at.text}'")
    }

    @Test
    fun `a name nothing declares has no declaration`() {
        assertNull(nav().declaration("core/util", "nothingIsCalledThis"))
        assertNull(nav().declaration("no/such/document", "bump"))
    }

    // ---- usages -------------------------------------------------------------------------------------

    @Test
    fun `usages span every document that uses it`() {
        val found = nav().usages("core/util", "bump")
        val refs = found.map { it.ref }.toSet()
        assertTrue("uses/one" in refs, "found: $refs")
        assertTrue("uses/two" in refs, "found: $refs")
        // Two calls in `two`, on separate lines.
        assertEquals(2, found.count { it.ref == "uses/two" }, "found: ${found.map { it.ref to it.line }}")
    }

    @Test
    fun `the declaration is among the usages`() {
        val found = nav().usages("core/util", "bump")
        assertTrue(found.any { it.ref == "core/util" }, "the declaration should be listed: ${found.map { it.ref }}")
    }

    /**
     * **Two documents each declaring a `helper`, and only one of them is being asked about.**
     *
     * This is what separates matching by declaration from matching by name. `uses/three` has its own
     * `helper` and never imports `core/util`, so it is not a usage — but its text says `helper` twice, and
     * a name-based search reports both.
     */
    @Test
    fun `a same-named symbol in another document is not a usage`() {
        val found = nav().usages("core/util", "helper")
        assertTrue(
            found.none { it.ref == "uses/three" },
            "an unrelated `helper` was counted: ${found.map { it.ref to it.line }}",
        )
    }

    @Test
    fun `and its own helper finds itself`() {
        // The mirror: asking about `uses/three`'s helper finds it there and NOT in core/util.
        val found = nav().usages("uses/three", "helper")
        assertTrue(found.any { it.ref == "uses/three" }, "found: ${found.map { it.ref }}")
        assertTrue(found.none { it.ref == "core/util" }, "found: ${found.map { it.ref }}")
    }

    @Test
    fun `every location carries the line it is on`() {
        for (l in nav().usages("core/util", "bump")) {
            val src = l.file.readText()
            assertEquals(
                LineIndex.of(src).textOf(src, l.line).trim(), l.text,
                "${l.ref}:${l.line} reported text that is not on that line",
            )
        }
    }

    @Test
    fun `the limit bounds the work`() {
        assertTrue(nav().usages("core/util", "bump", limit = 1).size <= 1)
    }
}
