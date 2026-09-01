package dev.ziggle.vscript.text

import dev.ziggle.vscript.lang.EntryKind
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which handlers a run actually has, once imports are in.
 *
 * `core/breaks`, `core/day` and `core/stats` are libraries whose entire job is an `always on wake` /
 * `always on sleep` pair. Compiling only the root document's entries leaves them doing nothing — and
 * nothing is exactly what that looks like from the outside, which is why it gets its own test.
 */
class RunEntriesTest {

    private val STRING = TypeRef(PinType.STRING)

    private val natives = NativeTable(
        listOf(NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList())),
    )

    private fun compile(main: String, others: Map<String, String>) =
        TextFrontEnd(natives, imports = TextSource.of(others)).compile(main)

    private val library = """
        graph "lib"
        export fn nothing() { }
        always on wake { log(message: "lib wake") }
        always on sleep { log(message: "lib sleep") }
        on tick { log(message: "lib tick") }
    """.trimIndent()

    @Test
    fun `an imported handler runs only when it said always`() {
        val c = compile(
            """
            graph "main"
            import { nothing } from "lib"
            on start { nothing() }
            """.trimIndent(),
            mapOf("lib" to library),
        )
        assertTrue(c.ok, c.errors.joinToString { "${it.span} ${it.message}" })

        assertEquals(listOf("lib"), c.entries[EntryKind.WAKE].orEmpty().map { it.document })
        assertEquals(listOf("lib"), c.entries[EntryKind.SLEEP].orEmpty().map { it.document })
        // `on tick` without `always` is the library's own business when it is run directly.
        assertEquals(emptyList(), c.entries[EntryKind.TICK].orEmpty().map { it.document })
        assertEquals(listOf(Resolution.ROOT), c.entries[EntryKind.START].orEmpty().map { it.document })
    }

    @Test
    fun `a document run directly gets all of its own handlers`() {
        val c = compile(library, emptyMap())
        assertTrue(c.ok, c.errors.joinToString { "${it.span} ${it.message}" })
        assertEquals(setOf(EntryKind.WAKE, EntryKind.SLEEP, EntryKind.TICK), c.entries.keys)
        assertTrue(c.entries.values.flatten().all { it.isRoot })
    }

    @Test
    fun `start runs imports first and sleep runs them last`() {
        val c = compile(
            """
            graph "main"
            import "lib"
            on start { log(message: "main start") }
            on sleep { log(message: "main sleep") }
            """.trimIndent(),
            mapOf("lib" to """
                graph "lib"
                always on start { log(message: "lib start") }
                always on sleep { log(message: "lib sleep") }
            """.trimIndent()),
        )
        assertTrue(c.ok, c.errors.joinToString { "${it.span} ${it.message}" })

        // A library initialises before the thing that imported it can use it...
        assertEquals(listOf("lib", Resolution.ROOT), c.entries[EntryKind.START].orEmpty().map { it.document })
        // ...and puts itself away after — see EntryKind.innermostFirst.
        assertEquals(listOf(Resolution.ROOT, "lib"), c.entries[EntryKind.SLEEP].orEmpty().map { it.document })
    }

    @Test
    fun `a handlers-only script compiles, with nothing to start`() {
        val c = compile(
            """
            graph "watchdog"
            on tick { log(message: "watching") }
            """.trimIndent(),
            emptyMap(),
        )
        assertTrue(c.ok, c.errors.joinToString { "${it.span} ${it.message}" })
        assertEquals(setOf(EntryKind.TICK), c.entries.keys)
    }

    @Test
    fun `every handler of a run shares one program and one set of globals`() {
        val c = compile(
            """
            graph "main"
            import "lib"
            var Mine: INT = 3
            on start { log(message: "" + Mine) }
            """.trimIndent(),
            mapOf("lib" to """
                graph "lib"
                var Theirs: INT = 9
                always on wake { log(message: "" + Theirs) }
            """.trimIndent()),
        )
        assertTrue(c.ok, c.errors.joinToString { "${it.span} ${it.message}" })

        val all = c.entries.values.flatten()
        assertEquals(2, all.size)
        // The imported handler must be sized for the WHOLE closure. Seeded from its own snapshot it would
        // hold a prefix, and the root's variables would read null out of slots nothing had sized.
        for (e in all) assertEquals(c.globals, e.chunk.globals, "${e.document} carries a different closure")
        assertTrue(c.globals.containsAll(listOf(9, 3)), "globals are ${c.globals}")
    }
}
