package dev.ziggle.vscript.model

import dev.ziggle.vscript.text.NativeTable
import dev.ziggle.vscript.text.TextFrontEnd
import dev.ziggle.vscript.text.TextSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The naming rule, and the `mod.vs` exception to it.
 *
 * Here rather than in the client because the rule is here now — see [ModuleNames] for why it stopped being
 * two copies. A test in the client could only ever check one of the two resolvers.
 */
class ModuleNamesTest {

    @Test
    fun `a document is its folder plus its graph line`() {
        assertEquals(
            listOf("core/random", "random"),
            ModuleNames.namesOf("core/random", "random"),
        )
    }

    /** The trap `LANGUAGE.md` warns about: the last segment is the GRAPH name, not the file name. */
    @Test
    fun `the graph line wins over the file name, and the path still answers`() {
        assertEquals(
            listOf("hunter/falconry", "falconry", "hunter/falcon", "falcon"),
            ModuleNames.namesOf("hunter/falcon", "falconry"),
        )
    }

    @Test
    fun `a document that never named itself is importable by where it sits`() {
        assertEquals(listOf("util/banks", "banks"), ModuleNames.namesOf("util/banks", null))
    }

    @Test
    fun `a barrel answers to its folder`() {
        assertEquals("core/loadout", ModuleNames.barrelName("core/loadout/mod"))
        assertTrue(ModuleNames.isBarrel("core/loadout/mod"))
    }

    /**
     * **The bare `mod` is never a name.** Every barrel in the tree would claim it, and the registries are
     * first-wins — so `import "mod"` would resolve to whichever folder the directory walk happened to
     * reach first, which is alphabetical order and therefore an answer nobody chose.
     */
    @Test
    fun `mod is a marker, not a name`() {
        assertTrue(ModuleNames.BARREL !in ModuleNames.namesOf("core/loadout/mod", "mod"))
        assertTrue(ModuleNames.BARREL !in ModuleNames.namesOf("core/bank/mod", "mod"))
        assertEquals(listOf("core/loadout/mod"), ModuleNames.namesOf("core/loadout/mod", "mod"))
    }

    /** A `mod.vs` at the top of the tree has no folder to be the front door of, so it is not one. */
    @Test
    fun `a barrel at the root names nothing`() {
        assertNull(ModuleNames.barrelName("mod"))
    }

    /**
     * The whole point, end to end: a folder with a `mod.vs` in it is imported BY THE FOLDER, and the
     * names it forwards arrive.
     *
     * Through the real front end, with the references built the way a resolver builds them — otherwise
     * this would only be testing that a map lookup works.
     */
    @Test
    fun `a folder with a mod is imported by the folder`() {
        val files = mapOf(
            "core/list" to """
                graph "list"
                export fn twice(n: INT) -> INT = n * 2
            """.trimIndent(),
            "core/math" to """
                graph "math"
                export fn half(n: INT) -> INT = n / 2
            """.trimIndent(),
            // The barrel. It forwards and declares nothing, which is what a barrel is.
            "core/mod" to """
                graph "mod"
                export * from "core/list"
                export * from "core/math"
            """.trimIndent(),
        )

        // Every reference each file answers to, exactly as `DocumentSource` builds the index: the ordinary
        // names first, then the barrel names, so an explicit sibling would keep the folder reference.
        val byRef = LinkedHashMap<String, String>()
        for ((path, text) in files) {
            val declared = text.substringAfter("graph \"").substringBefore('"')
            for (name in ModuleNames.namesOf(path, declared)) byRef.putIfAbsent(name, text)
        }
        for ((path, text) in files) ModuleNames.barrelName(path)?.let { byRef.putIfAbsent(it, text) }

        assertEquals(files.getValue("core/mod"), byRef["core"], "'core' should be the folder's mod.vs")

        val root = """
            graph "root"
            import * as core from "core"
            var Total: INT = 0
            on start {
                Total = core::twice(n: 4) + core::half(n: 10)
            }
        """.trimIndent()

        val compiled = TextFrontEnd(NativeTable(), imports = TextSource.of(byRef)).compile(root)
        assertTrue(
            compiled.ok,
            "importing a folder should work:\n" + compiled.errors.joinToString("\n") { "  ${it.span} ${it.message}" },
        )
    }
}
