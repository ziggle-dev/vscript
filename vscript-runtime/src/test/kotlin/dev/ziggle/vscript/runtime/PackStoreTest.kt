package dev.ziggle.vscript.runtime

import dev.ziggle.vscript.text.PackCodec
import dev.ziggle.vscript.text.VsPackFile
import dev.ziggle.vscript.text.VsPackInfo
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.text.NativeFn
import dev.ziggle.vscript.text.NativeParam
import dev.ziggle.vscript.text.NativeTable
import dev.ziggle.vscript.text.TextFrontEnd
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What is installed, read from headers — and what happens when one of the files is rubbish. */
class PackStoreTest {

    private val natives = NativeTable(
        listOf(NativeFn("say", listOf(NativeParam("message", TypeRef(PinType.STRING))), results = emptyList())),
    )

    private lateinit var root: File

    private fun packBytes(id: String, version: String): ByteArray {
        val c = TextFrontEnd(natives).compile(
            """
            graph "main"

            on start { say(message: "hi") }
            """.trimIndent(),
        )
        assertTrue(c.ok, "fixture: " + c.diagnostics.joinToString { it.message })
        return VsPackFile.write(c, id, version, "main", builtAtMs = version.hashCode().toLong())
    }

    private fun store(vararg packs: Pair<String, String>): PackStore {
        root = Files.createTempDirectory("vs-packs").toFile()
        packs.forEach { (id, v) -> File(root, "$id-$v${PackStore.EXTENSION}").writeBytes(packBytes(id, v)) }
        return PackStore(root)
    }

    @Test
    fun `lists what is installed from headers alone`() {
        val s = store("alpha" to "1.0.0", "beta" to "2.0.0")
        assertEquals(setOf("alpha", "beta"), s.installed().map { it.id }.toSet())
        assertNotNull(s.find("alpha"))
        assertNull(s.find("nope"))
    }

    @Test
    fun `loading gives back a runnable program`() {
        val s = store("alpha" to "1.0.0")
        val (info, pack) = assertNotNull(s.load("alpha"))
        assertEquals("alpha", info.id)
        assertTrue("say" in pack.requiredHosts(), "the gate must survive the round trip")
        assertTrue(pack.functions.isNotEmpty() || pack.entries.isNotEmpty())
    }

    /**
     * **One bad file must not make the rest invisible.** A folder is a place users drop things; a store
     * that threw on the first unreadable one would report an empty installation and give no clue why.
     */
    @Test
    fun `an unreadable pack is reported, not thrown`() {
        val s = store("alpha" to "1.0.0")
        File(root, "junk${PackStore.EXTENSION}").writeText("this is not a zip")

        assertEquals(listOf("alpha"), s.installed().map { it.id }, "the good pack is still listed")
        assertTrue("junk${PackStore.EXTENSION}" in s.problems(), "the bad one is named: ${s.problems()}")
    }

    /** A manifest that disagrees with its bytecode is refused at load, not run. */
    @Test
    fun `a pack whose manifest understates what it calls is refused on load`() {
        root = Files.createTempDirectory("vs-packs").toFile()
        val real = VsPackFile.read(packBytes("liar", "1.0.0")).first
        val lying = VsPackInfo(real.id, real.version, real.entry, emptySet(), real.stripped, real.builtAtMs)
        val program = PackCodec.write(
            TextFrontEnd(natives).compile("""graph "main"${'\n'}${'\n'}on start { say(message: "hi") }"""),
            true,
        )
        File(root, "liar${PackStore.EXTENSION}").writeBytes(VsPackFile.write(lying, program))

        val s = PackStore(root)
        assertNotNull(s.find("liar"), "its header still reads")
        val e = runCatching { s.load("liar") }.exceptionOrNull()
        assertNotNull(e, "loading must refuse a manifest that disagrees with the program")
    }

    @Test
    fun `an empty or missing folder is an empty installation, not a failure`() {
        assertEquals(emptyList(), PackStore(File("does-not-exist-anywhere")).installed())
    }
}
