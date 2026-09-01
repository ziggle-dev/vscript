package dev.ziggle.vscript.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Inline formatting, parsed.
 *
 * The rule this suite really enforces is that **nothing is ever swallowed**: a malformed tag, an unknown
 * one, a close with no open — all come back as text. A formatter that eats what it cannot parse turns a
 * typo into a missing message, and the author then goes looking for output that never appears instead of
 * looking at output that reads wrong.
 */
class RichTextTest {

    @Test
    fun `plain text is one span`() {
        val spans = RichText.parse("just words")
        assertEquals(1, spans.size)
        assertEquals("just words", spans[0].text)
        assertTrue(spans[0].style.isPlain)
    }

    @Test
    fun `a colour applies until it is closed`() {
        val spans = RichText.parse("a <col=ff0000>b</col> c")
        assertEquals(listOf("a ", "b", " c"), spans.map { it.text })
        assertEquals(null, spans[0].style.color)
        assertEquals(0xFF0000, spans[1].style.color)
        assertEquals(null, spans[2].style.color, "the close must restore what was there before")
    }

    @Test
    fun `every style has a tag`() {
        assertTrue(RichText.parse("<b>x</b>")[0].style.bold)
        assertTrue(RichText.parse("<i>x</i>")[0].style.italic)
        assertTrue(RichText.parse("<u>x</u>")[0].style.underline)
        assertTrue(RichText.parse("<str>x</str>")[0].style.strike)
        assertEquals(0x203040, RichText.parse("<bg=203040>x</bg>")[0].style.background)
    }

    @Test
    fun `styles nest and the inner close restores the outer`() {
        val spans = RichText.parse("<col=00ff00>a<b>b</b>c</col>")
        assertEquals(listOf("a", "b", "c"), spans.map { it.text })
        assertTrue(spans.all { it.style.color == 0x00FF00 }, "the colour spans all three")
        assertFalse(spans[0].style.bold)
        assertTrue(spans[1].style.bold)
        assertFalse(spans[2].style.bold, "closing bold must not close the colour too")
    }

    /** `</>` avoids having to remember which tag is innermost when several are open. */
    @Test
    fun `a bare close pops the innermost style`() {
        val spans = RichText.parse("<b><col=ff0000>x</>y</>z")
        assertEquals(listOf("x", "y", "z"), spans.map { it.text })
        assertEquals(0xFF0000, spans[0].style.color)
        assertEquals(null, spans[1].style.color)
        assertTrue(spans[1].style.bold, "only the colour was popped")
        assertFalse(spans[2].style.bold)
    }

    @Test
    fun `an unknown tag is text`() {
        assertEquals("<blink>x</blink>", RichText.plain("<blink>x</blink>"))
    }

    @Test
    fun `a malformed colour is text`() {
        assertEquals("<col=nothex>x", RichText.plain("<col=nothex>x"))
        assertEquals("<col=fff>x", RichText.plain("<col=fff>x"), "three digits is not six")
    }

    @Test
    fun `an unclosed tag still renders its text`() {
        val spans = RichText.parse("before <col=ff0000>after")
        assertEquals(listOf("before ", "after"), spans.map { it.text })
        assertEquals(0xFF0000, spans[1].style.color)
    }

    @Test
    fun `a close with nothing open is text`() {
        assertEquals("x</col>y", RichText.plain("x</col>y"))
    }

    /** A stray `<` must not scan forward and eat the rest of a long line looking for its `>`. */
    @Test
    fun `an unterminated angle bracket is text`() {
        assertEquals("2 < 3 and 4 > 1", RichText.plain("2 < 3 and 4 > 1"))
    }

    @Test
    fun `a doubled bracket is a literal one`() {
        assertEquals("<col=ff0000>", RichText.plain("<<col=ff0000>"))
    }

    @Test
    fun `plain strips every tag`() {
        assertEquals(
            "picked up 3 bananas",
            RichText.plain("picked up <col=ff0000><b>3</b></col> <u>bananas</u>"),
        )
    }

    @Test
    fun `isFormatted tells a marked-up string from a plain one`() {
        assertFalse(RichText.isFormatted("nothing here"))
        assertTrue(RichText.isFormatted("<b>something</b>"))
    }

    @Test
    fun `tag names are case insensitive and hex may lead with a hash`() {
        assertEquals(0xFF0000, RichText.parse("<COL=#FF0000>x")[0].style.color)
    }
}
