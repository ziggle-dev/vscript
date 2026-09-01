package dev.ziggle.vscript.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fitting a group's containers into a rectangle.
 *
 * The one thing that must never happen is two boxes on top of each other. The one thing that should happen
 * is that the space gets used: rows aligned at the top wasted everything under a short box sharing a row
 * with a tall one, which on a real script was less than half the area filled.
 */
class BoxPackerTest {

    private class Box(val x: Float, val y: Float, val w: Float, val h: Float)

    private fun assertNoOverlap(boxes: List<Box>) {
        for (i in boxes.indices) {
            for (j in i + 1 until boxes.size) {
                val a = boxes[i]
                val b = boxes[j]
                val dx = minOf(a.x + a.w, b.x + b.w) - maxOf(a.x, b.x)
                val dy = minOf(a.y + a.h, b.y + b.h) - maxOf(a.y, b.y)
                assertTrue(
                    dx <= 0.5f || dy <= 0.5f,
                    "boxes $i and $j overlap by ${dx}x$dy — " +
                        "(${a.x}, ${a.y}, ${a.w}, ${a.h}) and (${b.x}, ${b.y}, ${b.w}, ${b.h})",
                )
            }
        }
    }

    /** Boxes sit beside each other when the group has room. */
    @Test
    fun `boxes share the top row when there is room`() {
        val p = BoxPacker(0f, 0f, 100f, 3200f)
        val a = p.place(1500f, 400f) { _, _ -> 0f }
        val b = p.place(1500f, 400f) { _, _ -> 0f }
        assertEquals(0f to 0f, a)
        assertEquals(1600f to 0f, b)
    }

    /**
     * The hollow beside a short box gets used.
     *
     * With rows this was the waste: a 400-tall box beside a 1700-tall one left thirteen hundred units of
     * dead canvas, and the next box went below both of them.
     */
    @Test
    fun `a box drops into the hollow under a short one`() {
        val p = BoxPacker(0f, 0f, 100f, 3200f)
        val short = p.place(1500f, 400f) { _, _ -> 0f }
        val tall = p.place(1500f, 1700f) { _, _ -> 0f }
        val next = p.place(1400f, 900f) { _, _ -> 0f }
        assertEquals(0f to 0f, short)
        assertEquals(1600f to 0f, tall)
        assertEquals(0f to 500f, next, "it should have tucked under the short box, not below everything")
    }

    /** Cost still decides: a position that scores better wins over the compact one. */
    @Test
    fun `cost chooses among the places it could go`() {
        val p = BoxPacker(0f, 0f, 100f, 3200f)
        p.place(1500f, 400f) { _, _ -> 0f }
        // Everything equal, it would tuck under at (0, 500). Make the right-hand side cheaper.
        val next = p.place(1000f, 300f) { x, _ -> if (x > 1000f) -10f else 0f }
        assertTrue(next.first > 1000f, "the cheaper position should have won, got $next")
    }

    /** A box is never placed past the group's width. */
    @Test
    fun `the group width is respected`() {
        val p = BoxPacker(0f, 0f, 100f, 3200f)
        val placed = ArrayList<Box>()
        for (w in listOf(1500f, 1500f, 1500f, 900f)) {
            val (x, y) = p.place(w, 300f) { _, _ -> 0f }
            placed += Box(x, y, w, 300f)
        }
        assertNoOverlap(placed)
        assertTrue(placed.all { it.x + it.w <= 3200.5f }, "ran past the width: ${placed.map { it.x + it.w }}")
    }

    /** A box wider than the whole group still gets placed, below everything, rather than vanishing. */
    @Test
    fun `a box too wide for the group is still placed`() {
        val p = BoxPacker(0f, 0f, 100f, 1000f)
        p.place(900f, 300f) { _, _ -> 0f }
        val huge = p.place(4000f, 200f) { _, _ -> 0f }
        assertEquals(0f, huge.first)
        assertTrue(huge.second >= 300f, "it should be below what is already there, got $huge")
    }

    /** Whatever the costs say, and whatever the sizes, nothing lands on anything else. */
    @Test
    fun `no arrangement produces an overlap`() {
        val p = BoxPacker(0f, 0f, 60f, 2600f)
        val placed = ArrayList<Box>()
        val sizes = listOf(
            900f to 200f, 300f to 1400f, 1200f to 300f, 200f to 150f,
            400f to 900f, 1500f to 250f, 250f to 250f, 700f to 1100f,
            180f to 120f, 1000f to 400f, 2400f to 180f, 130f to 2000f,
        )
        for ((i, sz) in sizes.withIndex()) {
            // A cost that actively wants the top-left, the worst case for a packer that must not overlap.
            val (x, y) = p.place(sz.first, sz.second) { px, py -> px + py * (if (i % 2 == 0) 1f else 3f) }
            placed += Box(x, y, sz.first, sz.second)
        }
        assertNoOverlap(placed)
    }

    /** And the packing is dense: the same set of boxes fills far more of what it spans than rows did. */
    @Test
    fun `the packing is dense`() {
        val p = BoxPacker(0f, 0f, 100f, 3700f)
        val sizes = listOf(
            1516f to 429f, 2087f to 1747f, 526f to 1204f, 1743f to 535f,
            1142f to 235f, 1028f to 313f, 1045f to 320f, 2042f to 335f, 1242f to 579f,
        )
        val placed = sizes.map { (w, h) ->
            val (x, y) = p.place(w, h) { _, _ -> 0f }
            Box(x, y, w, h)
        }
        assertNoOverlap(placed)
        val spanX = placed.maxOf { it.x + it.w } - placed.minOf { it.x }
        val spanY = placed.maxOf { it.y + it.h } - placed.minOf { it.y }
        val fill = placed.sumOf { (it.w * it.h).toDouble() } / (spanX * spanY)
        assertTrue(fill > 0.62, "only ${(fill * 100).toInt()}% of the area used")
    }
}
