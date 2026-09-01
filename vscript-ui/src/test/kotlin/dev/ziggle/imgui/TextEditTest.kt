package dev.ziggle.imgui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wrapping, caret arithmetic and the editing primitives.
 *
 * These are the parts of the text editor that can be wrong *silently*: a wrap that puts a line break one
 * word early still looks like prose, and a caret index that is off by the width of a swallowed space still
 * blinks somewhere plausible. Measuring is injected, so the whole thing runs without a live ImGui font —
 * which is what makes it testable at all.
 */
class TextLayoutTest {

    /** Ten pixels a character: exact, so an expectation is a character count rather than a guess. */
    private val measure: (String) -> Float = { it.length * 10f }

    private fun layout(text: String, width: Float) =
        TextEdit.layout(text, width, fontSize = 10f, measure = measure)

    @Test
    fun `wraps at word boundaries and keeps absolute offsets`() {
        val l = layout("abc def", 30f)
        assertEquals(listOf("abc", "def"), l.lines.map { it.text })
        assertEquals(0, l.lines[0].start)
        // The space that caused the break belongs to the line before it, so every offset in the string
        // stays reachable — otherwise a caret dragged into it would have nowhere to go.
        assertEquals(4, l.lines[0].end)
        assertEquals(4, l.lines[1].start)
        assertEquals(7, l.lines[1].end)
    }

    @Test
    fun `hard newline starts a line and a trailing newline leaves an empty one`() {
        val l = layout("ab\ncd\n", 200f)
        assertEquals(listOf("ab", "cd", ""), l.lines.map { it.text })
        assertEquals(3, l.lines[1].start)
        assertEquals(6, l.lines[2].start)
    }

    @Test
    fun `empty text still has one line`() {
        assertEquals(1, layout("", 100f).lines.size)
    }

    @Test
    fun `a word wider than the box gets its own line rather than looping forever`() {
        val l = layout("abcdefgh ijkl", 30f)
        assertEquals(listOf("abcdefgh", "ijkl"), l.lines.map { it.text })
    }

    @Test
    fun `caret maps to the line it sits on`() {
        val l = layout("abc def", 30f)
        assertEquals(0, l.lineIndexOf(0))
        assertEquals(0, l.lineIndexOf(3))
        // On a boundary the LATER line wins, so after a soft wrap the caret shows where you will type.
        assertEquals(1, l.lineIndexOf(4))
        assertEquals(1, l.lineIndexOf(7))
    }

    @Test
    fun `caret x and caret at are inverses on a line`() {
        val l = layout("abc def", 30f)
        assertEquals(30f, l.caretX(3))
        assertEquals(0f, l.caretX(4))
        assertEquals(5, l.caretAt(1, 10f))
        // Past the end of a line clamps to its end rather than running into the next.
        assertEquals(7, l.caretAt(1, 999f))
    }

    @Test
    fun `line bounds are the visual line, not the paragraph`() {
        val l = layout("abc def", 30f)
        assertEquals(4, l.lineStartOf(6))
        assertEquals(7, l.lineEndOf(6))
        assertEquals(0, l.lineStartOf(2))
        assertEquals(3, l.lineEndOf(2))
    }

    @Test
    fun `layout height counts visual lines`() {
        val l = layout("abc def", 30f)
        assertEquals(2 * l.lineHeight, l.height)
        assertTrue(l.lineHeight > l.fontSize)
    }
}

/** The editing primitives, which are line-agnostic and so testable on their own. */
class TextEditStateTest {

    private fun state(s: String) = TextEditState().apply { set(s) }

    @Test
    fun `insert replaces the selection`() {
        val st = state("hello")
        st.moveTo(0, false)
        st.moveTo(4, extend = true)
        st.insert("x", TextFilter.ANY)
        assertEquals("xo", st.text)
        assertEquals(1, st.caret)
    }

    @Test
    fun `a filter rejects the whole candidate rather than the keystroke`() {
        val st = state("12")
        st.insert("a", TextFilter.INTEGER)
        assertEquals("12", st.text)
        st.insert("3", TextFilter.INTEGER)
        assertEquals("123", st.text)
    }

    @Test
    fun `newlines are ordinary text`() {
        val st = state("ab")
        st.insert("\n", TextFilter.ANY)
        st.insert("cd", TextFilter.ANY)
        assertEquals("ab\ncd", st.text)
        assertEquals(5, st.caret)
    }

    @Test
    fun `an unmodified arrow collapses a selection to its edge`() {
        val st = state("hello")
        st.selectAll()
        st.moveBy(-1, extend = false)
        assertEquals(0, st.caret)
        st.selectAll()
        st.moveBy(1, extend = false)
        assertEquals(5, st.caret)
    }

    @Test
    fun `word motion skips separators then the word`() {
        val st = state("foo  bar")
        st.moveTo(8, false)
        st.moveWord(-1, false)
        assertEquals(5, st.caret)
        st.moveWord(-1, false)
        assertEquals(0, st.caret)
    }

    @Test
    fun `backspace deletes the selection when there is one`() {
        val st = state("hello")
        st.moveTo(1, false)
        st.moveTo(4, extend = true)
        st.backspace(word = false)
        assertEquals("ho", st.text)
    }

    @Test
    fun `moving horizontally forgets the vertical goal column`() {
        val st = state("abc")
        st.goalX = 42f
        st.moveBy(-1, false)
        assertEquals(null, st.goalX)
    }
}
