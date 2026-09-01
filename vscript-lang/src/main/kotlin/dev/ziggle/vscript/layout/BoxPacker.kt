package dev.ziggle.vscript.layout

/**
 * Fits a group's child containers into a rectangle, by skyline.
 *
 * **Rows were not enough.** Boxes used to be packed into rows aligned at the top, which is simple and
 * wastes everything under a short box that happens to share a row with a tall one — on a real script that
 * left a hole thirteen hundred units deep beside the first comment, and the boxes filled less than half
 * the area they spanned. A skyline has no rows: it remembers how high the packing has reached at every x,
 * so the next box drops into whatever hollow suits it, including one under something already placed.
 *
 * Where a box goes is still decided by [place]'s cost — normally the total wire length from there to
 * everything already placed — so this decides only WHERE IT CAN go. Among positions the cost cannot
 * separate, the lowest wins, then the leftmost: equally good is broken toward compact.
 */
class BoxPacker(
    private val originX: Float,
    private val originY: Float,
    /** Space left between boxes, and between a box and the ones under it. */
    private val gap: Float,
    /** How wide the group may run. A box that will not fit inside it is placed at the far left anyway. */
    private val limit: Float,
) {
    /** One run of the skyline: from [x] for [w], the packing has reached [top]. */
    private class Seg(var x: Float, var w: Float, var top: Float)

    private val line = mutableListOf(Seg(originX, maxOf(limit, 1f), originY))

    /** How high the packing has reached across `[x, x + w)`, or null when that does not fit the limit. */
    private fun topOver(x: Float, w: Float): Float? {
        if (x < originX - 0.5f || x + w > originX + limit + 0.5f) return null
        var t = originY
        for (s in line) {
            if (s.x + s.w <= x + 0.01f) continue
            if (s.x >= x + w - 0.01f) break
            if (s.top > t) t = s.top
        }
        return t
    }

    /** Mark `[x, x + w)` as reaching [top], splitting whatever runs it lands across. */
    private fun raise(x: Float, w: Float, top: Float) {
        val next = ArrayList<Seg>(line.size + 2)
        for (s in line) {
            val sEnd = s.x + s.w
            if (sEnd <= x + 0.01f || s.x >= x + w - 0.01f) { next += s; continue }
            if (s.x < x - 0.01f) next += Seg(s.x, x - s.x, s.top)
            if (sEnd > x + w + 0.01f) next += Seg(x + w, sEnd - (x + w), s.top)
        }
        next += Seg(x, w, top)
        next.sortBy { it.x }
        // Merge neighbours at the same height, so the line stays a handful of runs however many boxes go in.
        line.clear()
        for (s in next) {
            val last = line.lastOrNull()
            if (last != null && Math.abs(last.top - s.top) < 0.5f && Math.abs(last.x + last.w - s.x) < 0.5f) {
                last.w += s.w
            } else {
                line += s
            }
        }
    }

    /**
     * Place a box of [w] by [h] and return its top-left.
     *
     * [cost] scores a candidate position. Every place the box could sit flush against what is already
     * there is offered — the left edge of each run, and the right edge of each run with the box tucked
     * against it — because a hollow is only usable if something is offered at both of its sides.
     */
    fun place(w: Float, h: Float, cost: (Float, Float) -> Float): Pair<Float, Float> {
        val xs = LinkedHashSet<Float>()
        for (s in line) {
            xs += s.x
            xs += s.x + s.w - w
        }
        var best: Triple<Float, Float, Float>? = null
        for (x in xs) {
            val y = topOver(x, w) ?: continue
            val c = cost(x, y)
            val cur = best
            if (cur == null || c < cur.third - 0.01f ||
                (Math.abs(c - cur.third) < 0.01f && (y < cur.second - 0.01f ||
                    (Math.abs(y - cur.second) < 0.01f && x < cur.first)))
            ) {
                best = Triple(x, y, c)
            }
        }
        // Nothing fits the width — a box wider than the whole group. It goes at the left, below everything.
        val at = best ?: Triple(originX, line.maxOf { it.top }, 0f)
        raise(at.first, w + gap, at.second + h + gap)
        return at.first to at.second
    }

    /** How far right the packing has reached. */
    fun width(): Float = line.filter { it.top > originY + 0.5f }.maxOfOrNull { it.x + it.w - originX } ?: 0f
}
