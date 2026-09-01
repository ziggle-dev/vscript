package dev.ziggle.vscript.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The scanner behind the Text node: what counts as a hole, and what it renders to.
 *
 * The pins a node shows and the values it substitutes come from this same walk, so these cases pin both
 * at once — the two disagreeing is the failure that would be hardest to see.
 */
class TemplatesTest {

    @Test
    fun `holes become pins, in reading order, without repeats`() {
        assertEquals(listOf("count", "total"), Templates.placeholders("Full: {count} of {total}"))
        assertEquals(listOf("name"), Templates.placeholders("{name}, hello {name}"))
        assertEquals(emptyList(), Templates.placeholders("no holes here"))
    }

    @Test
    fun `values are substituted by name`() {
        assertEquals(
            "Full: 7 of 27",
            Templates.render("Full: {count} of {total}", mapOf("count" to 7, "total" to 27)),
        )
        // Used twice, supplied once.
        assertEquals("hi hi", Templates.render("{a} {a}", mapOf("a" to "hi")))
    }

    /** A hole with nothing in it says so. An empty string would hide that the value never arrived. */
    @Test
    fun `an unsupplied hole renders as null`() {
        assertEquals("count is null", Templates.render("count is {count}", emptyMap()))
    }

    @Test
    fun `doubled braces are literal`() {
        assertEquals(listOf("real"), Templates.placeholders("{{not a hole}} but {real} is"))
        assertEquals("{not a hole} but 1 is", Templates.render("{{not a hole}} but {real} is", mapOf("real" to 1)))
    }

    /**
     * Half-typed text must not destroy the node.
     *
     * Every keystroke re-derives the pins, so a lone `{` mid-sentence would otherwise drop every pin after
     * it — and the wires with them — while the author was still typing.
     */
    @Test
    fun `an unclosed brace is just text`() {
        assertEquals(emptyList(), Templates.placeholders("Full: {cou"))
        assertEquals("Full: {cou", Templates.render("Full: {cou", emptyMap()))
    }

    @Test
    fun `an empty hole is text, not a nameless pin`() {
        assertEquals(emptyList(), Templates.placeholders("a {} b"))
        assertEquals("a {} b", Templates.render("a {} b", emptyMap()))
    }
}
