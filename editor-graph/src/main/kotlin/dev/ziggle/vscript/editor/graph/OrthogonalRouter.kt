package dev.ziggle.vscript.editor.graph

import java.util.PriorityQueue

/**
 * Shortest right-angled path from pin to pin, around everything in the way — A* over a visibility grid.
 *
 * **Why this replaces the channel router.** That one picked a single vertical gap and then grew a special
 * case for every situation the single gap did not cover: an exit edge, an exit row, a separate branch for
 * crossing backwards, a lane fallback, a turn hint, and a rule about which of those won. Each was correct
 * and each covered one shape of problem, so a bug in any wire meant finding which of six branches that wire
 * took — and a fix landing in the wrong branch looked exactly like no fix at all. Six rounds of that is the
 * argument for making SHORTEST the objective rather than something a rule approximates.
 *
 * **The grid is a Hanan grid**, the standard construction for orthogonal routing: take every obstacle edge,
 * offset it by a clearance, and the shortest right-angled path avoiding those obstacles is guaranteed to
 * run along those lines. So instead of searching continuous space, it searches the intersections of a few
 * dozen candidate columns and rows — small enough to solve exactly, and exact means no special cases.
 *
 * **Turns cost.** Length alone gives paths that stairstep through a gap in six little steps, all equally
 * short and all unreadable. Charging for a direction change makes the search prefer the two-bend route that
 * a person would draw, and is most of what makes the output look deliberate.
 */
object OrthogonalRouter {

    /**
     * Clearance kept around an obstacle, before whatever extra that obstacle asks for in [Obstacle.pad].
     *
     * Small on purpose. This is a floor on how close a wire may pass, but it is ALSO the offset of the
     * candidate lines the lattice raises beside each box — so a wide margin does not merely hold wires off,
     * it pushes every turning line out of the corridors BETWEEN nodes and into the open, and that is where
     * the long way round came from. A wire that passes close to a node reads fine. A wire that goes round
     * the block to avoid passing close to it does not.
     *
     * A container wants the opposite and gets it per-obstacle: see [CONTAINER_PAD].
     */
    @JvmStatic var MARGIN = 2f

    /**
     * How far a wire runs straight out of a pin before it may turn.
     *
     * Not just so a corner never lands on the pin — the run is DIRECTIONAL. An output pin is on the right
     * of its node and an input pin on the left, so a wire always leaves rightward and always arrives
     * rightward, whichever way round the two nodes happen to sit. Without that, a wire to a node behind it
     * left the pin heading straight back the way it came and read as attached to the wrong side.
     */
    @JvmStatic var STUB = 14f

    /**
     * How far outside the two pins the search may swing.
     *
     * A wire to a node BEHIND its own has to get around: out to the right, past the target, and back in
     * from the left. In open space there are no obstacles to raise a row or column to do that on, so the
     * grid would be the two pin rows and nothing else, and no legal loop existed at all. These are the
     * lines that make one possible.
     */
    @JvmStatic var SWING = 11f

    /**
     * What a direction change costs, in units of length.
     *
     * High enough to beat any detour it could save, which is the point: given two paths of similar length
     * the one with fewer bends is the one that reads.
     *
     * It is very high — hundreds of pixels a corner — and it is the number a person arrived at looking at
     * real graphs, not one a sweep found. The reason it wants to be this high is that what actually needs
     * minimising is not ink, it is the number of times an eye following a wire has to change direction. At
     * this weight a wire will run a long way out of its way to arrive with one bend instead of three, and
     * that is the right trade every time.
     */
    @JvmStatic var TURN_COST = 400f

    /**
     * Ceiling on candidate lines per axis.
     *
     * A wire spanning a large graph could otherwise raise a grid of every obstacle edge in it — hundreds
     * squared — and this runs for every wire, every frame, on the client thread. Lines are kept nearest the
     * two pins, which is where a shortest path spends its time anyway.
     */
    @JvmStatic var MAX_LINES = 96

    /**
     * How many obstacles contribute candidate lines.
     *
     * Distinct from the collision test, which always considers EVERY obstacle. Dropping a line only costs
     * the search a place it might have turned; dropping a wall would let it route through one.
     */
    @JvmStatic var MAX_OBSTACLES = 40

    /**
     * Closest two candidate lines may be.
     *
     * Two rungs a few units apart are two places to turn a few units apart, and a cheap corner used both:
     * that is where the little steps down and back up on an otherwise straight wire came from. The fix used
     * to be a coarse grid — thin the rungs until the wobble had nowhere to happen. With a corner priced at
     * [TURN_COST] the wobble is not worth taking any more, so the grid can be fine again, and fine is
     * strictly better: more places for a wire to find the ONE turn that lines it up with its pin.
     *
     * Dropping a rung only removes somewhere a wire could have turned. Every obstacle is still tested
     * exactly, so no grid, however coarse, can let a wire through something.
     */
    @JvmStatic var MIN_LINE_GAP = 6f

    /**
     * How far apart two wires must be before they read as two wires.
     *
     * Nothing charges for sharing a lane any more (see the note on the congestion term in [route]), so this
     * survives as one job: the offset of the OUTER pair of candidate lines the lattice raises beside every
     * box. It is much wider than [MARGIN] because the two answer different questions — [MARGIN] is the
     * passing-close line, this is the standing-well-clear line, and a wire crossing the whole graph wants
     * the second. The lattice offers both and the turn cost picks.
     */
    @JvmStatic var LANE = 47f

    /**
     * Extra clearance a CONTAINER keeps, on top of [MARGIN].
     *
     * A comment or a function box is not a node and a wire passing it should not read as belonging to it.
     * Nodes want a small margin — they sit in rows with narrow corridors between them, and a fat margin
     * closes those corridors off. Containers want the opposite: they are big, there is usually open space
     * around them, and a wire hugging one looks attached to it. Since the clearance is per-obstacle, both
     * can have what they want.
     */
    @JvmStatic var CONTAINER_PAD = 20f

    /**
     * The candidate turning lines, raised ONCE for the whole graph and shared by every wire.
     *
     * This is what lets two wires going the same way look alike. Each wire used to raise its own lines,
     * from its own nearby obstacles, so two wires travelling together only landed on the same row when an
     * obstacle edge happened to define it for both — and mostly none did. Ten wires going one way came out
     * as ten different shapes, every one a shortest path, and no cost could pull them onto a common line
     * because there was no common line to pull them onto.
     *
     * Every wire still searches its own WINDOW of this, so the cost stays proportional to the region a
     * wire crosses rather than the size of the graph. What matters is that the lines in that window came
     * from one shared set.
     */
    class Lattice(val xs: FloatArray, val ys: FloatArray)

    /**
     * Raise the lattice: every obstacle's cleared edges, a lane outside each, and every pin's own row and
     * column — those exactly, since the search looks its start and end up by position and a nudged row is
     * a wire that cannot be routed at all.
     */
    fun lattice(obstacles: List<Obstacle>, pins: List<Pair<Float, Float>>): Lattice {
        val xs = ArrayList<Float>(obstacles.size * 4 + pins.size * 3)
        val ys = ArrayList<Float>(obstacles.size * 2 + pins.size * 3)
        for (o in obstacles) {
            xs += o.x - o.clear; xs += o.right + o.clear
            xs += o.x - o.clear - LANE; xs += o.right + o.clear + LANE
            ys += o.y - o.clear; ys += o.bottom + o.clear
        }
        for ((px, py) in pins) {
            xs += px; xs += px + STUB; xs += px - STUB
            ys += py; ys += py + LANE; ys += py - LANE
        }
        val mustX = pins.flatMap { listOf(it.first, it.first + STUB, it.first - STUB) }
        return Lattice(thin(xs, mustX), thin(ys, pins.map { it.second }))
    }

    /**
     * Sort, and drop anything closer than [MIN_LINE_GAP] to a line already kept — except a [must], which
     * is kept exactly.
     *
     * Two lines a few units apart are two places to turn a few units apart, and a search will use both.
     */
    private fun thin(raw: List<Float>, must: List<Float>): FloatArray {
        val keep = ArrayList<Float>(raw.size)
        for (m in must.sorted()) if (keep.none { Math.abs(it - m) < 0.5f }) keep += m
        for (v in raw.sorted()) if (keep.none { Math.abs(it - v) < MIN_LINE_GAP - 0.5f }) keep += v
        keep.sort()
        return keep.toFloatArray()
    }

    /**
     * A solid box, and how far wires stand off it.
     *
     * [pad] is extra clearance for this box alone, over the global [MARGIN] — a container asks for
     * [CONTAINER_PAD], a node asks for nothing. It widens the space wires are kept out of and pushes the
     * lines they may turn on outwards to match, but it deliberately does NOT widen [contains], which is the
     * test for "this wire starts or ends inside me, so I am transparent to it". That test has to stay on the
     * true rectangle: inflating it would swallow pins sitting just outside a box's edge and quietly make the
     * box see-through for their wires, which is the one failure that looks like a routing bug rather than a
     * tuning one.
     */
    class Obstacle(
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val pad: Float = 0f,
    ) {
        val right: Float get() = x + w
        val bottom: Float get() = y + h

        /** Clearance wires keep from THIS box. */
        val clear: Float get() = MARGIN + pad

        fun contains(px: Float, py: Float): Boolean =
            px >= x - 1f && px <= right + 1f && py >= y - 1f && py <= bottom + 1f
    }

    /**
     * One wire to route.
     *
     * [bounds] confines it — the box both ends live in, `x, y, right, bottom`. A wire between two nodes
     * inside a comment leaving that comment and coming back reads as belonging to the graph outside, which
     * is the one thing it is not.
     */
    class Wire(
        val id: Int,
        val ax: Float,
        val ay: Float,
        val bx: Float,
        val by: Float,
        val bounds: FloatArray? = null,
    )

    /**
     * Route one wire, returning waypoints `x0, y0, x1, y1, …` from source pin to target pin.
     *
     * Falls back to the plain two-bend shape when the search finds nothing, which happens only if the pins
     * are walled in. A wire drawn across an obstacle is better than a wire not drawn.
     */
    /**
     * **There is no congestion term any more, and its removal was measured rather than argued.** A cost for
     * running alongside an already-routed wire was added to stop two wires reading as one. On a real
     * script, turning it from nothing up to its old setting added sixty-six corners and twenty-five
     * direction reversals, scattered the wires from twenty-six distinct rows onto forty-six — and left
     * FEWER rows shared than before. It made every measure worse, including the one it existed for.
     *
     * It was fighting the lattice. Wires landing on the same shared row is what a bundle IS; a repulsion
     * cost spends corners breaking exactly that up. If two wires on one line becomes a problem again, the
     * answer is to give them adjacent tracks on that line, deciding their offsets together — not to push
     * them off it one at a time.
     */
    fun route(
        wire: Wire,
        obstacles: List<Obstacle>,
        /** The shared lines. Null raises a private set, which is only right for a wire routed alone. */
        lattice: Lattice? = null,
    ): FloatArray {
        // A wire is never blocked by what it is attached to: a pin sits on its own node's edge, and a
        // container holding one end is a box the wire has to be able to leave.
        val blocking = obstacles.filter {
            !it.contains(wire.ax, wire.ay) && !it.contains(wire.bx, wire.by) && inBounds(it, wire.bounds)
        }
        // Obstacles NEAREST THE CORRIDOR first, so the ones actually in the way keep their lines when the
        // cap bites. Ranking by distance from a midpoint instead let a far-off box contribute a line while
        // a box squarely across the route lost its — and a missing edge is not a harmless approximation
        // here: it is a wall the grid has no vertex to stop at, so the search walks straight through where
        // a wire cannot go.
        val corridor = floatArrayOf(
            minOf(wire.ax, wire.bx), minOf(wire.ay, wire.by),
            maxOf(wire.ax, wire.bx), maxOf(wire.ay, wire.by),
        )
        val near = blocking.sortedBy { gap(it, corridor) }.take(MAX_OBSTACLES)

        // Leave and arrive horizontally, so a corner never lands on the pin. The search runs between the
        // stub ends and the stubs are put back afterwards.
        //
        // On a wire shorter than two stubs the two ends would cross over — the leaving stub ending to the
        // RIGHT of where the arriving one starts — and the search then had to double back to join them,
        // spending three corners on what should be a straight line. So a short forward wire splits the
        // distance instead. A backward wire keeps the full stub: it has to get around either way, and the
        // straight run out of the pin is the whole point of this.
        val span = wire.bx - wire.ax
        val stub = if (span >= 2f * STUB || span <= 0f) STUB else maxOf(4f, span / 2f)
        val sx = wire.ax + stub
        val sy = wire.ay
        val tx = wire.bx - stub
        val ty = wire.by

        // Columns are kept to the wire's OWN span. A right-angled wire gets past an obstacle by going over
        // or under it, never by going round the outside of the graph — but nothing said so, and with wires
        // charged for sharing a lane one found a free column seventeen hundred units past its own target
        // and took it, crossing the whole graph twice to avoid a few hundred units of company. Rows stay
        // free: getting above or below something genuinely does need room outside the pins.
        //
        // A wire running FORWARD gets no slack at all. Any column behind its start or past its end is a
        // step backwards, and a step backwards is what a hook out of the pin is made of — out, up, back
        // across its own start, and down. Only a backward wire needs room outside its ends, because
        // getting around is the one thing it cannot do between them.
        // Forward: from the leaving stub's end to the far PIN. Not to the arriving stub's start — the
        // target pin's own column is the one a wire turns down to reach it when something sits between
        // them, and cutting it off left short wires with nowhere to go but through the obstacle.
        //
        // A backward wire's room is never less than its own stub. The loop it has to make IS the stub —
        // out past the pin, over, and back — so a swing narrower than one leaves the search no legal path
        // at all, and it falls back to the straight line through everything. Below the stub the setting
        // does not mean a tighter loop; it means no loop.
        val swing = maxOf(SWING, stub)
        val room = if (tx >= sx) floatArrayOf(sx, wire.bx) else floatArrayOf(tx - swing, sx + swing)

        // Widened past anything that BLOCKS THE WHOLE WIDTH the wire has.
        //
        // The bound above stops a wire wandering off to an unrelated part of the graph, which it did. But
        // taken alone it also forbids the one detour that is not wandering: getting around a container
        // wider than the gap between the pins. A wire from a comment below the graph up into one at the
        // top had to pass a box spanning two thousand units, could not reach either end of it, and the
        // search simply failed — so the wire fell back to the plain two-bend shape, which consults no
        // obstacles at all and went straight through three containers.
        //
        // Only obstacles that cover the corridor END TO END count. One that merely overlaps it can be
        // passed within the span already allowed, and letting those widen the room is how a wire ends up
        // seventeen hundred units past its own target again.
        for (o in near) {
            if (o.bottom < corridor[1] - o.clear || o.y > corridor[3] + o.clear) continue
            if (o.x > corridor[0] || o.right < corridor[2]) continue
            room[0] = minOf(room[0], o.x - o.clear - STUB)
            room[1] = maxOf(room[1], o.right + o.clear + STUB)
        }
        // The lines to turn on. From the SHARED lattice when there is one, clipped to the window this
        // wire is allowed to use — so two wires crossing the same region choose from the same rows, which
        // is what makes them come out parallel. Without one, a private set, which is only ever right for a
        // wire being routed on its own.
        val xLimit = wire.bounds?.let { maxOf(it[0], room[0]) to minOf(it[2], room[1]) }
            ?: (room[0] to room[1])
        val yLo = wire.bounds?.get(1) ?: Float.NEGATIVE_INFINITY
        val yHi = wire.bounds?.get(3) ?: Float.POSITIVE_INFINITY
        val xs: FloatArray
        val ys: FloatArray
        if (lattice != null) {
            xs = window(lattice.xs, xLimit.first, xLimit.second, floatArrayOf(sx, tx))
            ys = window(lattice.ys, yLo, yHi, floatArrayOf(sy, ty))
        } else {
            xs = lines(
                seeds = listOf(sx, tx),
                hints = near.flatMap { listOf(it.x - it.clear, it.right + it.clear) } +
                    listOf(
                        (sx + tx) * 0.5f, wire.ax, wire.bx, room[0], room[1],
                        sx - LANE, sx + LANE, tx - LANE, tx + LANE,
                    ),
                bounds = xLimit,
            )
            ys = lines(
                seeds = listOf(sy, ty),
                hints = near.flatMap { listOf(it.y - it.clear, it.bottom + it.clear) } +
                    listOf(
                        (sy + ty) * 0.5f, minOf(sy, ty) - SWING, maxOf(sy, ty) + SWING,
                        sy - LANE, sy + LANE, ty - LANE, ty + LANE,
                    ),
                bounds = wire.bounds?.let { it[1] to it[3] },
            )
        }

        // Strict first: leave the pin heading right and arrive at the far one heading anything but left.
        // Relaxed only if that has no solution at all, which means the pins are boxed in — a wire attached
        // to the wrong side of its pin still beats no wire.
        //
        // The fallback is a proper two-bend route, not a straight line between the stubs. Written as
        // `(sx,ay) -> (tx,by)` it was a DIAGONAL — and since the search was failing often, diagonal wires
        // were showing up all over a graph whose whole point is right angles. A fallback has to be a
        // legal shape, or a bug in the search reads as a bug in the renderer.
        val strict = search(sx, sy, tx, ty, xs, ys, blocking, strict = true)
        val path = strict ?: search(sx, sy, tx, ty, xs, ys, blocking, strict = false)
        outcomes[wire.id] = Outcome(
            how = if (strict != null) "strict" else if (path != null) "relaxed" else "fallback",
            obstacles = blocking.size, columns = xs.size, rows = ys.size,
            roomLo = room[0], roomHi = room[1],
        )
        if (path == null) {
            return floatArrayOf(
                wire.ax, wire.ay,
                sx, wire.ay,
                sx, wire.by,
                wire.bx, wire.by,
            )
        }

        val out = ArrayList<Float>(path.size + 4)
        out += wire.ax; out += wire.ay
        path.forEach { out += it }
        out += wire.bx; out += wire.by
        return tidy(out.toFloatArray())
    }

    /**
     * What the last search for each wire actually did — DIAGNOSTICS, read by `graph wires`.
     *
     * A route that reads wrong has two completely different explanations and the shape alone does not say
     * which. Either the search ran and this was genuinely the cheapest path it could find, in which case the
     * costs are wrong; or the search FAILED and what is on screen is the fallback, which consults no
     * obstacles at all and will happily cross anything. Those need opposite fixes, and telling them apart by
     * reading the polyline is guesswork — the fallback's four-point shape is also a perfectly ordinary route.
     *
     * So the router says. Keyed by wire id, overwritten each pass, never read by the renderer.
     */
    @JvmStatic
    val outcomes: MutableMap<Int, Outcome> = java.util.concurrent.ConcurrentHashMap()

    class Outcome(
        /** `strict`, `relaxed` (the pins are boxed in), or `fallback` (no path at all — obstacles ignored). */
        val how: String,
        /** Obstacles this wire was actually solved against, after the drops and the cap. */
        val obstacles: Int,
        val columns: Int,
        val rows: Int,
        /** The x window the search was confined to. A window with no legal column in it is one failure mode. */
        val roomLo: Float,
        val roomHi: Float,
    )

    /** How far [o] is from the corridor `x1, y1, x2, y2` — zero when it overlaps it. */
    private fun gap(o: Obstacle, c: FloatArray): Float {
        val dx = maxOf(0f, maxOf(c[0] - o.right, o.x - c[2]))
        val dy = maxOf(0f, maxOf(c[1] - o.bottom, o.y - c[3]))
        return dx + dy
    }

    private fun inBounds(o: Obstacle, b: FloatArray?): Boolean =
        b == null || (o.right > b[0] && o.x < b[2] && o.bottom > b[1] && o.y < b[3])

    /**
     * Candidate lines on one axis: the [seeds], always and exactly, plus whichever [hints] add a real choice.
     *
     * The two are not the same kind of thing, and treating them as one caused both of this router's worst
     * bugs. A seed is a line the search REQUIRES — the wire's own start and end sit on them, and it looks
     * them up by exact position. A hint is somewhere the wire MAY turn: an obstacle's cleared edge, the
     * midpoint, a line to swing out to. Dropping a hint costs a possibility; dropping or moving a seed
     * takes the search's own endpoint off the grid and fails the whole route.
     */
    /**
     * The stretch of the shared lattice this wire may turn on, plus its own two ends.
     *
     * Global values, not global indices, because the search wants a small dense array — but they came from
     * one lattice, so two wires whose windows overlap are offered exactly the same lines in the overlap.
     * That is the whole mechanism by which a bundle becomes parallel.
     */
    /**
     * The shared lattice's lines, clipped to one wire's window and **capped at [MAX_LINES]**.
     *
     * The cap is the whole point, and its absence was a real bug rather than an oversight in degree.
     * [lines] — the private-lattice path — has always cut its candidates to [MAX_LINES] "sorted by
     * nearness and CUT to the cap before the merge, not after", with a note that letting a thousand in
     * "makes it quadratic in a number that has no business being large". Everything that comment says was
     * true here too, and this is the path the canvas actually takes: the shared lattice is raised from
     * EVERY node in the graph, so on a real script it holds a couple of thousand lines where a private one
     * holds ninety-six. Uncapped, a wire whose window was wide kept most of them, the `out.none { … }`
     * merge went quadratic in that, and then the A* searched an xs-by-ys grid of the same size. A ~1000
     * node script spent 25-30 SECONDS on one routing pass, on the client thread, three times over.
     *
     * **Nearest the wire's own span wins**, which is the same rule [lines] uses and keeps the property the
     * shared lattice exists for: the lines still come from the one global set, so two wires crossing the
     * same region still choose from the same rows and still come out parallel. They simply stop
     * considering rows on the far side of the graph, which no wire was ever going to turn on.
     */
    private fun window(all: FloatArray, lo: Float, hi: Float, must: FloatArray): FloatArray {
        val out = ArrayList<Float>(MAX_LINES + must.size)
        for (m in must) if (out.none { Math.abs(it - m) < 0.5f }) out += m

        // `all` is sorted, so the window is a slice and the nearest lines are found by walking outwards
        // from the middle — no per-wire copy to sort, and nothing outside the window is ever visited.
        var from = 0
        while (from < all.size && all[from] < lo - 0.5f) from++
        var until = all.size
        while (until > from && all[until - 1] > hi + 0.5f) until--

        val mid = if (must.isEmpty()) 0f else must.sum() / must.size
        var up = from
        while (up < until && all[up] < mid) up++
        var down = up - 1
        while (out.size < MAX_LINES && (down >= from || up < until)) {
            val takeDown = when {
                down < from -> false
                up >= until -> true
                else -> (mid - all[down]) <= (all[up] - mid)
            }
            val v = if (takeDown) all[down--] else all[up++]
            if (out.none { Math.abs(it - v) < MIN_LINE_GAP - 0.5f }) out += v
        }
        out.sort()
        return out.toFloatArray()
    }

    private fun lines(seeds: List<Float>, hints: List<Float>, bounds: Pair<Float, Float>?): FloatArray {
        val kept = ArrayList<Float>(MAX_LINES)
        // Seeds are placed first and exactly, deduplicated only against each other. The search looks its
        // start and end up by exact position, so a seed that has been nudged or dropped isn't on the grid
        // at all: the lookup returns -1 and the whole wire falls back to the plain shape. That is what made
        // short wires ignore everything in their way — the two stubs landed within the merge distance of
        // each other and one of them was quietly merged away.
        for (s in seeds) if (kept.none { Math.abs(it - s) < 0.5f }) kept += s

        // Hints are places the wire MAY turn, and one is only worth having if it is far enough from every
        // line already kept to be a distinct choice. Two candidate lines a couple of units apart are two
        // places to turn a couple of units apart, and the search will happily use both — that is where the
        // little two-unit steps down and back up on an otherwise straight wire came from. They were not a
        // drawing fault and not a bug in the search: they were genuinely on the grid.
        // Sorted by nearness and CUT to the cap before the merge, not after. The merge asks each hint
        // against every line already kept, so letting a thousand of them in makes it quadratic in a number
        // that has no business being large — and every one past the cap was going to be dropped anyway.
        val mid = if (seeds.isEmpty()) 0f else seeds.average().toFloat()
        val room = MAX_LINES - kept.size
        if (room > 0) {
            val near = hints
                .filter { bounds == null || (it >= bounds.first && it <= bounds.second) }
                .sortedBy { Math.abs(it - mid) }
            for (h in near) {
                if (kept.size - seeds.size >= room) break
                if (kept.none { Math.abs(it - h) < MIN_LINE_GAP - 0.5f }) kept += h
            }
        }
        kept.sort()
        return kept.toFloatArray()
    }

    /**
     * A* over the grid, charging length plus [TURN_COST] per direction change.
     *
     * A state is a cell AND the direction it was entered from, because the cost of leaving depends on it —
     * searching cells alone would let a path arrive cheaply and then be charged nothing for turning, which
     * is how you get stairsteps.
     */
    private fun search(
        sx: Float, sy: Float, tx: Float, ty: Float,
        xs: FloatArray, ys: FloatArray,
        obstacles: List<Obstacle>,
        strict: Boolean,
    ): List<Float>? {
        val xi = xs.indexOfFirst { Math.abs(it - sx) < 0.5f }
        val yi = ys.indexOfFirst { Math.abs(it - sy) < 0.5f }
        val txi = xs.indexOfFirst { Math.abs(it - tx) < 0.5f }
        val tyi = ys.indexOfFirst { Math.abs(it - ty) < 0.5f }
        if (xi < 0 || yi < 0 || txi < 0 || tyi < 0) return null

        val w = xs.size
        val h = ys.size
        // 4 directions per cell + one "just started" state.
        fun key(x: Int, y: Int, dir: Int) = ((y * w + x) * 5) + dir
        val best = HashMap<Int, Float>()
        val cameFrom = HashMap<Int, Int>()
        val open = PriorityQueue<Pair<Float, Int>>(compareBy { it.first })

        fun heuristic(x: Int, y: Int) = Math.abs(xs[x] - tx) + Math.abs(ys[y] - ty)

        // The search STARTS heading right, because that is how the wire arrived at the stub end — out of an
        // output pin, which is on the right of its node. Combined with the no-reversing rule below, that is
        // what stops a wire turning round and heading back across its own node the moment it leaves.
        val start = key(xi, yi, if (strict) RIGHT else FREE)
        best[start] = 0f
        open += heuristic(xi, yi) to start
        val dx = intArrayOf(1, -1, 0, 0)
        val dy = intArrayOf(0, 0, 1, -1)

        var guard = 0
        while (open.isNotEmpty()) {
            if (guard++ > MAX_EXPANSIONS) return null
            val (_, cur) = open.poll()
            val dir = cur % 5
            val cell = cur / 5
            val cx = cell % w
            val cy = cell / w
            // Arriving heading LEFT is refused: the stub from here to the pin runs rightward, so the wire
            // would double back at the last corner and meet the input pin from the wrong side. Coming in
            // from above or below is fine — that is the ordinary shape.
            if (cx == txi && cy == tyi && (!strict || dir != LEFT)) return rebuild(cameFrom, cur, xs, ys, w)

            val g = best[cur] ?: continue
            for (d in 0 until 4) {
                // Never turn straight round. Always a waste on a shortest path, so pruning it costs
                // nothing, and it is what makes the start direction bind.
                if (dir != FREE && d == (dir xor 1)) continue
                val nx = cx + dx[d]
                val ny = cy + dy[d]
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                if (!clear(xs[cx], ys[cy], xs[nx], ys[ny], obstacles)) continue
                val step = Math.abs(xs[nx] - xs[cx]) + Math.abs(ys[ny] - ys[cy])
                val turn = if (dir == FREE || dir == d) 0f else TURN_COST
                val next = key(nx, ny, d)
                val cost = g + step + turn
                if (cost < (best[next] ?: Float.MAX_VALUE)) {
                    best[next] = cost
                    cameFrom[next] = cur
                    open += (cost + heuristic(nx, ny)) to next
                }
            }
        }
        return null
    }

    private const val MAX_EXPANSIONS = 60_000

    /** Travel directions, indexing the step tables; [FREE] is "not yet moving", used only by the relaxed pass. */
    private const val RIGHT = 0
    private const val LEFT = 1
    private const val FREE = 4

    private fun rebuild(cameFrom: Map<Int, Int>, end: Int, xs: FloatArray, ys: FloatArray, w: Int): List<Float> {
        val pts = ArrayList<Float>()
        var cur: Int? = end
        while (cur != null) {
            val cell = cur / 5
            pts.add(0, ys[cell / w])
            pts.add(0, xs[cell % w])
            cur = cameFrom[cur]
        }
        return pts
    }

    /** Is the axis-aligned segment clear of every obstacle? */
    private fun clear(x1: Float, y1: Float, x2: Float, y2: Float, obstacles: List<Obstacle>): Boolean {
        val loX = minOf(x1, x2); val hiX = maxOf(x1, x2)
        val loY = minOf(y1, y2); val hiY = maxOf(y1, y2)
        for (o in obstacles) {
            if (hiX > o.x - o.clear && loX < o.right + o.clear &&
                hiY > o.y - o.clear && loY < o.bottom + o.clear
            ) return false
        }
        return true
    }

    /**
     * Clean up a path built from several legs: no repeats, no doubling back, no collinear middles.
     *
     * Each leg is routed on its own, so where two meet the first can arrive heading one way and the second
     * leave heading back the other — a spike, which reads as the wire looping on itself. Nothing inside a
     * single search can produce that, which is exactly why it has to be dealt with where the legs are
     * joined rather than inside the search.
     *
     * Public because the caller assembles the legs; it knows where the joints are and this does not need
     * to be told.
     */
    fun tidy(p: FloatArray): FloatArray {
        var cur = dropRepeats(p)
        // Alternate between the passes until none changes: flattening a jog can expose a doubling-back,
        // merging a doubling-back can expose a collinear middle, and dropping a collinear middle can
        // expose another jog.
        repeat(4) {
            // SIMPLIFIED FIRST. Levelling a step moves the far end of the run it levels, which is only
            // safe when the segment beyond that end runs the other way — and after several legs are joined
            // a path can have two collinear segments in a row, where it does not. Moving the point between
            // them then leaves a DIAGONAL, in a router whose entire premise is right angles. Merging
            // collinear runs first makes the alternation the levelling assumes actually true.
            val next = simplify(dropBacktracks(flattenJogs(simplify(cur))))
            if (next.contentEquals(cur)) return cur
            cur = next
        }
        return cur
    }

    /**
     * Straighten a step of a few units across an otherwise straight run.
     *
     * The wire goes along, steps two units down, goes along again — three corners spent on a deviation too
     * small to mean anything, and it reads as the wire being broken rather than routed. It comes from the
     * JOINTS: each leg is searched on its own grid, and two grids agree on the shared endpoint but not on
     * the rung either side of it, so the legs approach at slightly different heights and the joint becomes
     * a step. Nothing inside one search can produce it — [MIN_LINE_GAP] already keeps one grid's rungs
     * apart — so, like the doubling-back, it has to be dealt with after the legs are joined.
     *
     * Safe without consulting the obstacles: the step being removed is smaller than [MIN_LINE_GAP], itself
     * well under [MARGIN], so the straightened run stays inside the clearance the original path kept. It
     * can graze a little further into that padding, never into a node.
     */
    private fun flattenJogs(p: FloatArray): FloatArray {
        val count = p.size / 2
        if (count < 4) return p
        val out = p.copyOf()
        for (i in 0..count - 4) {
            val ax = out[i * 2]; val ay = out[i * 2 + 1]
            val bx = out[(i + 1) * 2]; val by = out[(i + 1) * 2 + 1]
            val cx = out[(i + 2) * 2]; val cy = out[(i + 2) * 2 + 1]
            val dx = out[(i + 3) * 2]; val dy = out[(i + 3) * 2 + 1]

            // Levelling the step means moving one of the two runs onto the other's line, which moves that
            // run's far end too — fine for an interior corner, since the segment beyond it runs the other
            // way and only changes length. Never for a terminal point: that is a pin, and a wire that has
            // been straightened off its own pin is worse than the jog. So a run whose far end is the start
            // or the end of the wire is the one that stays put, and if both are, the jog stays.
            val startFixed = i == 0
            val endFixed = i + 3 == count - 1
            if (startFixed && endFixed) continue

            val flat = Math.abs(ay - by) < 0.5f && Math.abs(bx - cx) < 0.5f && Math.abs(cy - dy) < 0.5f
            val upright = Math.abs(ax - bx) < 0.5f && Math.abs(by - cy) < 0.5f && Math.abs(cx - dx) < 0.5f
            if (flat) {
                val step = Math.abs(cy - by)
                if (step >= jog() || step <= 0.01f) continue
                // Otherwise the shorter run moves: less of the wire shifts, and the long straight stays put.
                val keepFirst = startFixed || (!endFixed && Math.abs(bx - ax) >= Math.abs(dx - cx))
                val y = if (keepFirst) by else cy
                out[(i + 1) * 2 + 1] = y; out[(i + 2) * 2 + 1] = y
                if (keepFirst) out[(i + 3) * 2 + 1] = y else out[i * 2 + 1] = y
            } else if (upright) {
                val step = Math.abs(cx - bx)
                if (step >= jog() || step <= 0.01f) continue
                val keepFirst = startFixed || (!endFixed && Math.abs(by - ay) >= Math.abs(dy - cy))
                val x = if (keepFirst) bx else cx
                out[(i + 1) * 2] = x; out[(i + 2) * 2] = x
                if (keepFirst) out[(i + 3) * 2] = x else out[i * 2] = x
            }
        }
        return out
    }

    /**
     * Largest step worth straightening away.
     *
     * It has to stay BELOW [MIN_LINE_GAP], and that is a correctness condition rather than a preference: a
     * deviation the search chose on purpose is at least one grid rung wide, so anything narrower than a rung
     * can only be joint noise. Set it above a rung and the flattener starts erasing real detours — the loop
     * a backward wire makes to get around is exactly one rung tall, so it came back out as a straight line
     * through the pins it was supposed to hook around, which reads as the search failing when it did not.
     *
     * [jog] enforces it, so the two can be tuned live in either order without the pair inverting.
     */
    @JvmStatic var JOG = 5f

    /** [JOG], never at or above a grid rung. */
    private fun jog(): Float = minOf(JOG, MIN_LINE_GAP - 1f)

    private fun dropRepeats(p: FloatArray): FloatArray {
        val out = ArrayList<Float>(p.size)
        for (i in 0 until p.size / 2) {
            val x = p[i * 2]; val y = p[i * 2 + 1]
            val n = out.size
            if (n >= 2 && Math.abs(out[n - 2] - x) < 0.5f && Math.abs(out[n - 1] - y) < 0.5f) continue
            out += x; out += y
        }
        return out.toFloatArray()
    }

    /**
     * Remove a step out and straight back on the same axis.
     *
     * The middle point of such a triple is a corner that goes nowhere: the wire leaves it in the direction
     * it arrived from. Dropping the excursion is always at least as short and always has fewer bends.
     */
    private fun dropBacktracks(p: FloatArray): FloatArray {
        if (p.size < 8) return p
        val out = ArrayList<Float>(p.size)
        out += p[0]; out += p[1]
        var i = 1
        while (i < p.size / 2 - 1) {
            val ax = out[out.size - 2]; val ay = out[out.size - 1]
            val bx = p[i * 2]; val by = p[i * 2 + 1]
            val cx = p[(i + 1) * 2]; val cy = p[(i + 1) * 2 + 1]
            val backX = Math.abs(ay - by) < 0.5f && Math.abs(by - cy) < 0.5f &&
                (bx - ax) * (cx - bx) < 0f
            val backY = Math.abs(ax - bx) < 0.5f && Math.abs(bx - cx) < 0.5f &&
                (by - ay) * (cy - by) < 0f
            if (backX || backY) { i++; continue } // skip B: the wire turns round at it
            out += bx; out += by
            i++
        }
        out += p[p.size - 2]; out += p[p.size - 1]
        return out.toFloatArray()
    }

    /** Drop the collinear middles the grid leaves behind, so a straight run is one segment. */
    private fun simplify(p: FloatArray): FloatArray {
        if (p.size <= 4) return p
        val out = ArrayList<Float>(p.size)
        out += p[0]; out += p[1]
        for (i in 1 until p.size / 2 - 1) {
            val ax = p[(i - 1) * 2]; val ay = p[(i - 1) * 2 + 1]
            val bx = p[i * 2]; val by = p[i * 2 + 1]
            val cx = p[(i + 1) * 2]; val cy = p[(i + 1) * 2 + 1]
            val straight = (Math.abs(ax - bx) < 0.5f && Math.abs(bx - cx) < 0.5f) ||
                (Math.abs(ay - by) < 0.5f && Math.abs(by - cy) < 0.5f)
            val same = Math.abs(ax - bx) < 0.5f && Math.abs(ay - by) < 0.5f
            if (!straight && !same) { out += bx; out += by }
        }
        out += p[p.size - 2]; out += p[p.size - 1]
        return out.toFloatArray()
    }
}
