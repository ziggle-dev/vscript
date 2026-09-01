package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A file with one stray character, and what it is allowed to cost.
 *
 * **The lexer throws rather than collecting**, which is right — below a statement there is nothing to
 * resynchronise to. But an exception is only a diagnostic if somebody catches it, and each entry point
 * forgetting separately is how an editor came to show NO errors at all for a file with a `#` in it: the
 * throw escaped to a caller whose job was to keep working, and "keep working" looked exactly like
 * "nothing is wrong".
 */
class LexFailureTest {

    private val STRING = TypeRef(PinType.STRING)

    private val natives = NativeTable(
        listOf(NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList())),
    )

    private val bad = """
        graph "probe"
        on start { log(message: "" + #FF3366CC) }
    """.trimIndent()

    @Test
    fun `a stray character is reported, not thrown`() {
        val f = TextFrontEnd(natives)
        val c = f.compile(bad)
        assertFalse(c.ok)
        assertEquals(1, c.errors.size, "one bad character, one complaint: ${c.errors.map { it.message }}")
        assertTrue(c.errors.first().span.line >= 1, "the complaint has nowhere to point")
    }

    @Test
    fun `read and resolve report it too`() {
        val f = TextFrontEnd(natives)
        assertFalse(f.read(bad).ok)
        assertEquals(1, f.read(bad).errors.size)
        assertEquals(1, f.resolve(bad).errors.size)
    }

    @Test
    fun `a library that does not lex costs one line, on the import`() {
        val f = TextFrontEnd(natives, imports = TextSource.of(mapOf("lib" to bad)))
        val c = f.compile(
            """
            graph "main"
            import * as lib from "lib"
            on start { log(message: "fine") }
            """.trimIndent(),
        )
        assertFalse(c.ok)
        // ONE complaint, on the import line — not a page about every name the broken library was going to
        // provide, and certainly not an exception out of the whole compilation.
        assertEquals(1, c.errors.size, c.errors.joinToString { it.message })
        assertTrue(c.errors.first().message.contains("does not lex"), c.errors.first().message)
        assertEquals(2, c.errors.first().span.line, "the complaint is not on the import line")
    }
}
