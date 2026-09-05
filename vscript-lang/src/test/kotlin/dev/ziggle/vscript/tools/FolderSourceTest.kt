package dev.ziggle.vscript.tools

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The import root a build tool compiles against, and the naming rules it has to get right.
 *
 * These are the rules an offline checker most often gets wrong — and getting them wrong does not fail
 * loudly, it reports a healthy tree as broken, which is how a checker earns being ignored.
 */
class FolderSourceTest {

    private lateinit var root: File

    private fun write(path: String, text: String) {
        val f = File(root, path)
        f.parentFile.mkdirs()
        f.writeText(text.trimIndent())
    }

    private fun tree(): FolderSource {
        root = Files.createTempDirectory("vs-folder").toFile()
        write("core/pace.vs", """graph "pace"""")
        write("core/items/mod.vs", """graph "items-front-door"""")
        write("core/items/detail.vs", """graph "detail"""")
        // A file that spells the folder's name exactly, beside a folder that would imply it.
        write("core/loadout.vs", """graph "loadout"""")
        write("core/loadout/mod.vs", """graph "loadout-barrel"""")
        return FolderSource(root)
    }

    @Test
    fun `a document answers to its path`() {
        val src = tree()
        assertNotNull(src.load("core/pace"), "refs: ${src.refs}")
        assertTrue(src.load("core/pace")!!.contains("""graph "pace""""))
    }

    /** Imports resolve by the name a document DECLARES, which is why renaming a file changes nothing. */
    @Test
    fun `a document answers to the name it declares`() {
        val src = tree()
        assertNotNull(src.load("detail"), "the declared name should resolve: ${src.refs}")
    }

    /**
     * **The front door.** `core/items` is a folder, and `core/items/mod.vs` answers for it. Miss this and
     * every import of a package fails to resolve.
     */
    @Test
    fun `a folder answers through its mod front door`() {
        val src = tree()
        val text = src.load("core/items")
        assertNotNull(text, "the barrel did not answer for its folder: ${src.refs}")
        assertTrue(text.contains("items-front-door"), "resolved to the wrong file: $text")
    }

    /**
     * Barrels are applied in a SECOND pass, so a file that spells the name exactly keeps it. In one pass
     * the winner would be whichever the directory walk reached first — alphabetical order, which is an
     * answer nobody chose.
     */
    @Test
    fun `an explicit file beside a folder keeps the reference`() {
        val src = tree()
        val text = assertNotNull(src.load("core/loadout"))
        assertTrue(
            text.contains(""""loadout""") && !text.contains("loadout-barrel"),
            "the folder took the reference from the file that spells it: $text",
        )
    }

    @Test
    fun `an unknown reference answers nothing`() {
        assertNull(tree().load("core/nope"))
    }

    /**
     * A stub is for a reference nothing answers to — a corpus can carry a dead import, and every document
     * importing it then fails for a reason that has nothing to do with the document.
     */
    @Test
    fun `a stub overlays a reference the tree cannot resolve`() {
        root = Files.createTempDirectory("vs-folder").toFile()
        write("a.vs", """graph "a"""")
        val src = FolderSource(root, mapOf(FolderSource.stub("scheduler")))

        assertEquals("", src.load("scheduler"), "the dead import should resolve to an empty document")
        assertNotNull(src.load("a"))
    }

    @Test
    fun `the index is stable across two reads`() {
        val src = tree()
        assertEquals(src.refs, src.refs)
        assertEquals(src.load("core/items"), src.load("core/items"))
    }
}
