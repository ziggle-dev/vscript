package dev.ziggle.vscript.editor.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where a focused field goes when its value has outgrown it.
 *
 * A node's fields are narrow by design — a node is sized to its pins, not to its longest value — so a
 * template or an action name overflowing is the normal case, and editing one through a scrolling window a
 * few characters wide is how it was before this.
 *
 * The arithmetic is the part that can be wrong without looking wrong: a panel that grows off the right of
 * the window, or one clamped to a negative width, both draw *something*.
 */
class OverflowFieldTest {

    private val screen = 1600f

    @Test
    fun `a value that fits is left exactly where it was`() {
        val (x, w) = CanvasWidgets.overflowGeometry(needed = 40f, x = 300f, w = 80f, screenW = screen)
        assertEquals(300f, x)
        assertEquals(80f, w, "a field wide enough for its value must not move or resize")
    }

    @Test
    fun `a value that overflows widens in place`() {
        val (x, w) = CanvasWidgets.overflowGeometry(needed = 420f, x = 300f, w = 80f, screenW = screen)
        assertEquals(300f, x, "it stays anchored at its own left edge")
        assertEquals(420f, w)
    }

    /** Sliding left rather than growing off-screen: the end of the value is the part you are typing. */
    @Test
    fun `a field near the right edge slides left to fit`() {
        val (x, w) = CanvasWidgets.overflowGeometry(needed = 400f, x = 1500f, w = 80f, screenW = screen)
        assertEquals(400f, w)
        assertTrue(x + w <= screen, "ran off the right edge: x=$x w=$w")
        assertTrue(x < 1500f, "should have slid left, stayed at $x")
    }

    /** A value longer than the whole window stops growing and scrolls, rather than reaching past both edges. */
    @Test
    fun `an enormous value is capped to the window`() {
        val (x, w) = CanvasWidgets.overflowGeometry(needed = 9_000f, x = 300f, w = 80f, screenW = screen)
        assertTrue(w <= screen, "wider than the window: $w")
        assertTrue(x >= 0f, "started off the left edge: $x")
        assertTrue(x + w <= screen, "ran off the right edge: x=$x w=$w")
    }

    /**
     * A window narrower than the field itself must still produce a drawable rect.
     *
     * `coerceIn` throws on an inverted range, and this is exactly the shape that inverts one — it has taken
     * the whole canvas down elsewhere in this editor, so it is worth pinning here.
     */
    @Test
    fun `a window narrower than the field does not invert the range`() {
        val (x, w) = CanvasWidgets.overflowGeometry(needed = 500f, x = 10f, w = 300f, screenW = 100f)
        assertTrue(w > 0f, "width collapsed to $w")
        assertTrue(x.isFinite() && w.isFinite())
    }

    @Test
    fun `the left edge is never negative`() {
        val (x, _) = CanvasWidgets.overflowGeometry(needed = 600f, x = 0f, w = 40f, screenW = screen)
        assertTrue(x >= 0f, "started off the left edge: $x")
    }
}
