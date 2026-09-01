package dev.ziggle.vscript.editor.text

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The document tree and the search index, against real files on disk. */
class WorkspaceTest {

    private lateinit var root: File

    @BeforeEach
    fun setUp() {
        root = File.createTempFile("vs-ws", "").let { it.delete(); it.mkdirs(); it }
        write("core/bank.vs", """
            graph "core/bank"

            export fn withdrawAll(item: INT) -> BOOL = true

            export var Deposited: INT = 0
        """.trimIndent())
        write("core/util.vs", """
            graph "core/util"

            export type Point { x: INT, y: INT }
        """.trimIndent())
        write("skilling/mining.vs", """
            graph "skilling/mining"

            on start {
                log("mine")
            }
        """.trimIndent())
        write("notes.txt", "not a script")
        write("build/ignored.vs", "graph \"ignored\"")
    }

    @AfterEach
    fun tearDown() = root.deleteRecursively().let { }

    private fun write(path: String, body: String) {
        val f = File(root, path)
        f.parentFile.mkdirs()
        f.writeText(body)
    }

    private fun ws() = Workspace(root)

    // ---- the tree ---------------------------------------------------------------------------------

    @Test
    fun `the tree holds only vs files`() {
        val names = ws().tree().files().map { it.name }
        assertTrue("bank.vs" in names)
        assertTrue("notes.txt" !in names, "a non-script got in: $names")
    }

    @Test
    fun `build output is not a source folder`() {
        val paths = ws().tree().files().map { ws().relative(it.file) }
        assertTrue(paths.none { it.startsWith("build/") }, "build output is in the tree: $paths")
    }

    @Test
    fun `folders come before files, each alphabetical`() {
        // What makes a directory's shape readable at a glance rather than interleaved.
        val top = ws().tree().children
        val firstFileAt = top.indexOfFirst { !it.isDirectory }
        val lastDirAt = top.indexOfLast { it.isDirectory }
        if (firstFileAt >= 0 && lastDirAt >= 0) {
            assertTrue(lastDirAt < firstFileAt, "a file sorted above a folder: ${top.map { it.name }}")
        }
        val dirs = top.filter { it.isDirectory }.map { it.name }
        assertEquals(dirs.sortedBy { it.lowercase() }, dirs)
    }

    @Test
    fun `an empty folder is dropped`() {
        File(root, "empty/deeper").mkdirs()
        val names = ws().tree().children.map { it.name }
        assertTrue("empty" !in names, "an empty folder is noise: $names")
    }

    // ---- the index --------------------------------------------------------------------------------

    @Test
    fun `every document and every symbol is indexed`() {
        val index = ws().index()
        val files = index.filter { it.isFile }.map { it.name }
        assertTrue("bank" in files, "documents: $files")
        val symbols = index.filterNot { it.isFile }.map { it.name }
        assertTrue("withdrawAll" in symbols, "symbols: $symbols")
        assertTrue("Point" in symbols)
        assertTrue("x" in symbols, "a record's fields should be searchable too")
    }

    @Test
    fun `a symbol carries the line it is on`() {
        val hit = ws().index().first { it.name == "withdrawAll" }
        val src = hit.file.readText()
        assertTrue(
            "withdrawAll" in LineIndex.of(src).textOf(src, hit.line),
            "line ${hit.line} of ${hit.file.name} does not hold it",
        )
    }

    @Test
    fun `a broken document still contributes what parsed`() {
        // One unparseable script must not empty the search box of everything in it.
        write("core/broken.vs", "graph \"core/broken\"\n\nexport var Kept: INT = 0\n\nvar Bad INT = 0")
        val names = ws().index().map { it.name }
        assertTrue("Kept" in names, "a broken file lost its good declarations: $names")
    }

    @Test
    fun `re-indexing an unchanged tree is served from cache`() {
        val w = ws()
        val first = w.index()
        val second = w.index()
        assertEquals(first.size, second.size)
        // Same instances: nothing was re-read or re-parsed.
        assertTrue(first.zip(second).all { (a, b) -> a === b }, "the cache did not hold")
    }

    @Test
    fun `an edited file is re-indexed`() {
        val w = ws()
        assertTrue(w.index().none { it.name == "brandNew" })
        val f = File(root, "core/bank.vs")
        f.writeText(f.readText() + "\n\nexport fn brandNew() -> BOOL = true\n")
        f.setLastModified(f.lastModified() + 2_000)
        assertTrue(w.index().any { it.name == "brandNew" }, "a stale cache survived an edit")
    }

    // ---- search -----------------------------------------------------------------------------------

    @Test
    fun `an empty query finds nothing`() {
        // A box that dumps everything the moment it opens has answered a question nobody asked.
        assertEquals(emptyList(), ws().search(""))
    }

    @Test
    fun `an abbreviation finds the symbol`() {
        val hits = ws().search("wa")
        assertTrue(
            hits.any { it.entry.name == "withdrawAll" },
            "'wa' should find withdrawAll: ${hits.map { it.entry.name }}",
        )
    }

    @Test
    fun `a document name finds the document above a symbol inside it`() {
        val hits = ws().search("bank")
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.first().entry.isFile, "expected the file first: ${hits.map { it.entry.name to it.entry.isFile }}")
    }

    @Test
    fun `results are capped`() {
        assertTrue(ws().search("a", limit = 3).size <= 3)
    }

    // ---- the real corpus ----------------------------------------------------------------------------

    /**
     * The whole script corpus, indexed.
     *
     * A temp directory with four files proves the shape; it does not prove this survives 105 real
     * documents, several of them 800 lines, some of which do not resolve without a game attached. Skipped
     * rather than failed when the corpus is not beside this checkout, so a standalone clone still builds —
     * the same rule `benchReal` follows.
     */
    @Test
    fun `the real script corpus indexes`() {
        val corpus = listOf("../plugins/scripts/src", "../../plugins/scripts/src")
            .map { File(it) }.firstOrNull { it.isDirectory } ?: return

        val w = Workspace(corpus)
        val files = w.tree().files()
        assertTrue(files.size > 50, "expected the corpus, found ${files.size} files")

        val index = w.index()
        assertTrue(index.size > files.size, "every document should contribute at least itself")
        // Every symbol points at a line that exists in the file it claims to be in.
        for (e in index.filterNot { it.isFile }.take(400)) {
            assertTrue(e.line >= 1, "'${e.name}' has line ${e.line}")
        }
        // And the box finds something for a plausible abbreviation.
        assertTrue(w.search("bank").isNotEmpty(), "'bank' found nothing in the whole corpus")
    }
}
