package dev.ziggle.vscript.text

import dev.ziggle.vscript.lang.EntryKind
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.drive
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The distributable file: a manifest anyone can read, and bytecode it is not allowed to lie about. */
class VsPackFileTest {

    private val STRING = TypeRef(PinType.STRING)

    private val natives = NativeTable(
        listOf(NativeFn("say", listOf(NativeParam("message", STRING)), results = emptyList())),
    )

    private val library = """
        graph "pace"

        export fn greeting() -> STRING {
            return "hello"
        }
    """.trimIndent()

    private val main = """
        graph "main"

        import * as pace from "core/pace"

        on start {
            say(message: pace::greeting())
        }
    """.trimIndent()

    private fun compilation() =
        TextFrontEnd(natives, imports = TextSource.of(mapOf("core/pace" to library)))
            .compile(main)
            .also { assertTrue(it.ok, "fixture: " + it.diagnostics.joinToString { d -> d.message }) }

    /** A fixed clock: `builtAtMs` defaults to now, so a pack is only reproducible against a pinned one. */
    private val builtAt = 1_700_000_000_000L

    private fun packFile(strip: Boolean = true) = VsPackFile.write(
        compilation(), id = "osrsx/greeter", version = "1.2.0", entry = "main", strip = strip,
        builtAtMs = builtAt, meta = mapOf("gitSha" to "abc1234"),
    )

    // ---- the file ------------------------------------------------------------------------------------

    @Test
    fun `a pack is a zip of a manifest and a program`() {
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(packFile())).use { z ->
            var e = z.nextEntry
            while (e != null) { names += e.name; e = z.nextEntry }
        }
        assertEquals(listOf(VsPackFile.MANIFEST, VsPackFile.PROGRAM), names)
    }

    @Test
    fun `the header reads without decoding the program`() {
        val info = VsPackFile.readInfo(packFile())

        assertEquals("osrsx/greeter", info.id)
        assertEquals("1.2.0", info.version)
        assertEquals("main", info.entry)
        assertTrue(info.stripped)
        assertEquals(mapOf("gitSha" to "abc1234"), info.meta)
        assertTrue("say" in info.requiredHosts, "the gate must be in the header: ${info.requiredHosts}")
    }

    @Test
    fun `the program inside still runs, imports and all`() {
        val (_, pack) = VsPackFile.read(packFile())

        val said = ArrayList<String>()
        val hosts = HostRegistry().apply {
            register("say", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        }
        drive(pack.entries.getValue(EntryKind.START).single().chunk, hosts)
        assertEquals(listOf("hello"), said, "the imported library's function did not survive")
    }

    // ---- what the manifest is not allowed to do ------------------------------------------------------

    /**
     * **The manifest is checked against the program, not trusted.** `requiredHosts` is what a host decides
     * to run something on, and it travels in plain text beside the bytecode — so an understated manifest
     * is a pack that talks a host into running code it cannot serve, failing at whatever instruction
     * reaches the missing verb.
     */
    @Test
    fun `a manifest that understates what the program calls is refused`() {
        val original = VsPackFile.read(packFile()).first
        val lying = VsPackInfo(
            id = original.id, version = original.version, entry = original.entry,
            requiredHosts = emptySet(),          // claims it needs nothing
            stripped = original.stripped, builtAtMs = original.builtAtMs,
        )
        val program = PackCodec.write(compilation(), strip = true)

        val e = assertFailsWith<IllegalArgumentException> {
            VsPackFile.read(VsPackFile.write(lying, program))
        }
        assertTrue(e.message!!.contains("undeclared"), "unhelpful message: ${e.message}")
    }

    @Test
    fun `a file with no manifest is not a pack`() {
        val e = assertFailsWith<IllegalArgumentException> { VsPackFile.readInfo("nope".toByteArray()) }
        assertTrue(e.message!!.contains("not a .vspack"), "unhelpful message: ${e.message}")
    }

    @Test
    fun `a container format this build does not know is refused`() {
        val json = VsPackFile.toJson(VsPackFile.readInfo(packFile()))
            .replace("\"format\": 1", "\"format\": 99")
        val e = assertFailsWith<IllegalArgumentException> { VsPackFile.fromJson(json) }
        assertTrue(e.message!!.contains("container is format 99"), "unhelpful message: ${e.message}")
    }

    /**
     * Two builds of the same source and the same clock produce the same manifest — the sets are written
     * sorted, so a diff of two packs is about what changed rather than about hash ordering.
     *
     * Only the clock stops a pack being byte-reproducible outright; `builtAtMs` is the one field that
     * moves on its own, which is why a caller that wants reproducibility passes it in.
     */
    @Test
    fun `the manifest is deterministic`() {
        val a = VsPackFile.toJson(VsPackFile.readInfo(packFile()))
        val b = VsPackFile.toJson(VsPackFile.readInfo(packFile()))
        assertEquals(a, b)
        assertTrue(a.indexOf("\"requiredHosts\"") > 0)
    }
}
