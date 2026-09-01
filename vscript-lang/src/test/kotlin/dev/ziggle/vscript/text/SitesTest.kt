package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The debug metadata a text-compiled program carries.
 *
 * Nothing here is about what the program computes; it is about whether a person can stop it, read it and
 * point at the line that faulted. `TextCompiler` emitted none of it at first, which does not fail a test
 * or a run — it just makes every breakpoint inert and every variables view empty, which is the shape of
 * bug that survives a long time because everything looks like it works.
 */
class SitesTest {

    private val STRING = TypeRef(PinType.STRING)
    private val INT = TypeRef(PinType.INT)

    private val natives = NativeTable(
        listOf(
            NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList()),
            NativeFn("roll", results = outs(INT)),
        ),
    )

    private fun front(others: Map<String, String> = emptyMap()) =
        TextFrontEnd(natives, imports = TextSource.of(others))

    @Test
    fun `every instruction is attributed to a line the author wrote`() {
        val f = front()
        val r = f.read(
            """
            graph "sites"
            on start {
                val n = roll()
                log(message: "n")
            }
            """.trimIndent(),
        )
        val chunk = r.chunk ?: fail(f.describe(r))

        // -1 is ChunkBuilder's "no site", which is what every instruction carried before this existed.
        val unattributed = chunk.nodeIds.count { it < 0 }
        assertEquals(0, unattributed, "instructions with no authoring site")

        for (id in chunk.nodeIds.distinct()) {
            val span = assertNotNull(f.sites.spanOf(id), "site $id has no span")
            assertTrue(span.line >= 1, "site $id has no line")
        }
    }

    @Test
    fun `a site knows which document it was written in`() {
        val f = front(mapOf("lib" to """
            graph "lib"
            export fn helper() -> INT { return roll() }
        """.trimIndent()))
        val r = f.read(
            """
            graph "main"
            import { helper } from "lib"
            on start {
                log(message: "" + helper())
            }
            """.trimIndent(),
        )
        assertNotNull(r.chunk, f.describe(r))

        val documents = f.sites.spans().keys.mapNotNull { f.sites.documentOf(it) }.toSet()
        assertEquals(setOf("<root>", "lib"), documents)
    }

    @Test
    fun `two documents never share a site id`() {
        val f = front(mapOf("lib" to """
            graph "lib"
            export fn helper() -> INT { return roll() }
        """.trimIndent()))
        f.read(
            """
            graph "main"
            import { helper } from "lib"
            on start { log(message: "" + helper()) }
            """.trimIndent(),
        )
        // Breakpoints are a flat set of ids with no chunk beside them, so a collision here would mean a
        // breakpoint armed in the script also firing in the library.
        val ids = f.sites.spans().keys
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(f.sites.size, ids.size)
    }

    @Test
    fun `break on a line finds the first site on it`() {
        val f = front()
        val r = f.read(
            """
            graph "sites"
            on start {
                val n = roll()
                log(message: "n")
            }
            """.trimIndent(),
        )
        assertNotNull(r.chunk, f.describe(r))

        val site = assertNotNull(f.sites.siteAt(Resolution.ROOT, 3), "nothing to break on at line 3")
        assertEquals(3, f.sites.spanOf(site)?.line)

        // `graph "sites"` is a header, not a statement — a breakpoint there is one the editor should
        // refuse rather than quietly move to the next line that does have code.
        assertNull(f.sites.siteAt(Resolution.ROOT, 1))
    }

    @Test
    fun `a document variable is readable by name at a stopped frame`() {
        val f = front()
        val r = f.read(
            """
            graph "sites"
            var Total: INT = 7
            on start { log(message: "" + Total) }
            """.trimIndent(),
        )
        val chunk = r.chunk ?: fail(f.describe(r))

        // `variables` is indexed into the run's GLOBALS, so the value has to be the slot and not a
        // register — see DebugSession.scopes.
        val slot = assertNotNull(chunk.slots.variables["Total"], "Total is not in the slot map")
        assertEquals(7, chunk.globals.getOrNull(slot))
    }

    @Test
    fun `a local is readable, and shadowing does not lose one`() {
        val f = front()
        val r = f.read(
            """
            graph "sites"
            on start {
                val n = roll()
                if true {
                    val n = roll()
                    log(message: "" + n)
                }
                log(message: "" + n)
            }
            """.trimIndent(),
        )
        val chunk = r.chunk ?: fail(f.describe(r))

        // Two `val n`, two declarations, two entries — a name-keyed map would have kept one.
        val ns = chunk.slots.outputs.keys.filter { it.second == "n" }
        assertEquals(2, ns.size, "shadowed locals collapsed: ${chunk.slots.outputs.keys}")
        assertEquals(2, ns.map { it.first }.toSet().size, "both declarations claim the same site")
        for ((site, _) in ns) assertNotNull(f.sites.spanOf(site), "local at an unknown site")
    }

    @Test
    fun `a local is not readable before its statement has run`() {
        val f = front()
        val r = f.read(
            """
            graph "sites"
            on start {
                log(message: "first")
                val n = roll()
                log(message: "" + n)
            }
            """.trimIndent(),
        )
        val chunk = r.chunk ?: fail(f.describe(r))

        val key = chunk.slots.outputs.keys.first { it.second == "n" }
        val declaredAt = assertNotNull(chunk.slots.liveFrom[key], "n has no live-from")

        // Paused at the head of `val n = …` the register still holds the last expression's scratch — the
        // string the `log` above passed. Reported as `n`, that is a wrong answer rather than a missing one.
        assertTrue(!chunk.slots.isLive(key, declaredAt), "n reads as live before it is assigned")
        assertTrue(chunk.slots.isLive(key, declaredAt + 1), "n never becomes live")
    }

    @Test
    fun `a parameter is live from the first instruction`() {
        val f = front()
        val r = f.read(
            """
            graph "sites"
            fn twice(n: INT) -> INT { return n * 2 }
            on start { log(message: "" + twice(n: 2)) }
            """.trimIndent(),
        )
        assertNotNull(r.chunk, f.describe(r))
        val body = r.chunk!!.program.first { it.name.endsWith("::twice") }
        val key = body.slots.outputs.keys.first { it.second == "n" }
        // The caller put it there before the body's first instruction ran, so there is no pc at which it
        // is meaningless.
        assertTrue(body.slots.isLive(key, 1), "a parameter read as not yet live")
    }

    @Test
    fun `a function body carries its own slots`() {
        val f = front()
        val r = f.read(
            """
            graph "sites"
            fn twice(n: INT) -> INT {
                val doubled = n * 2
                return doubled
            }
            on start { log(message: "" + twice(n: 2)) }
            """.trimIndent(),
        )
        assertNotNull(r.chunk, f.describe(r))

        val linked = r.chunk!!.program
        val body = linked.firstOrNull { it.name.endsWith("::twice") }
            ?: fail("no chunk for twice: " + linked.map { it.name })
        val names = body.slots.outputs.keys.map { it.second }.toSet()
        assertTrue("doubled" in names, "the function's own local is missing: $names")
        assertTrue("n" in names, "the parameter is missing: $names")
    }

    @Test
    fun `every handler a document declares is compiled into one program`() {
        val f = front()
        val r = f.read(
            """
            graph "sites"
            fn shared() -> INT { return roll() }
            on start { log(message: "start" + shared()) }
            on stop { log(message: "stop") }
            on tick { log(message: "tick" + shared()) }
            """.trimIndent(),
        )
        assertNotNull(r.chunk, f.describe(r))
        assertEquals(
            setOf(
                dev.ziggle.vscript.lang.EntryKind.START,
                dev.ziggle.vscript.lang.EntryKind.STOP,
                dev.ziggle.vscript.lang.EntryKind.TICK,
            ),
            r.entries.keys,
        )
        // One table, so a `FunctionValue` minted in one handler is callable from another. An entry is a
        // ROOT of the program rather than a member of it, so what is shared is the linked array itself.
        val linked = r.chunk!!.program
        assertTrue(linked.any { it.name.endsWith("::shared") }, "the shared function is not in the table")
        assertTrue(r.entries.values.all { it.program === linked }, "handlers were linked separately")
    }
}
