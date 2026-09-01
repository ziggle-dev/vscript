graph "tour"

// The document under test. Everything it computes lands in a graph variable, because those are what a
// run leaves behind and therefore what `ExamplesTest` can assert on without a host node to observe with.

import * as geo from "geometry"
import * as tally from "tally"

/**
 * A leg of a journey.
 *
 * The field types are named THROUGH an import — `geo::Point` — which is the case that made `TypeExpr`
 * carry a module in the first place. Two documents may each declare a `Point`, and a field that dropped
 * its qualifier would resolve to whichever the reader asked for first.
 */
export type Leg {
    from: geo::Point,
    to: geo::Point,
    label: String,
}

/** Named the same as `tally`'s on purpose — see the note there. */
export var Total: INT = 0

export var Chebyshev: INT = 0
export var Manhattan: INT = 0
export var Recursed: INT = 0
export var Shared: INT = 0
export var Longest: String = ""
export var Steps: INT = 0
export var Picked: INT = 0
export var Moved: INT = 0

/**
 * A local function, so the file has one of its own alongside the imported ones.
 *
 * An EXPRESSION body that calls an imported expression-bodied function — the shape that needs purity to
 * cross a document boundary. `isPureFunction` derives purity by walking a body, and this body's Call is
 * to a body in another document; getting that wrong made the whole function look like a step and had the
 * validator report that nothing runs a value it plainly reads.
 */
export fn lengthOf(leg: Leg) -> INT = geo::chebyshev(leg.from, leg.to)

/** Two results, and the short `-> (a: T, b: T)` spelling for them. */
export fn bounds(a: INT, b: INT) -> (low: INT, high: INT) {
    return a < b ? a : b, a < b ? b : a
}

on start {
    // ---- records, across a `::` and locally ----
    val origin = geo::Point { x: 0, y: 0 }
    val far = geo::Point { x: 3, y: 7 }
    val leg = Leg { from: origin, to: far, label: "first" }

    // `with` replaces a field rather than writing through it — a record is never mutated in place.
    val moved = far with { x: 10 }
    Moved = moved.x

    // ---- calls through one import, and through an import's own import ----
    Chebyshev = lengthOf(leg)
    Manhattan = tally::spanOf(origin, far)

    // ---- recursion ----
    Recursed = tally::sumTo(5)

    // ---- a variable that belongs to ANOTHER document ----
    Shared = tally::add(4)

    // ---- lists, indexing, and a for loop with both bindings ----
    val sizes = [2, 5, 1]
    for (size, i) in sizes {
        Steps = Steps + size * (i + 1)
    }
    Picked = sizes[1]

    // ---- control flow: if / else if / else, while, break, continue ----
    if Chebyshev > 100 {
        Longest = "impossible"
    } else if Chebyshev > 5 {
        Longest = leg.label
    } else {
        Longest = "short"
    }

    // A tuple binding takes both results of a two-result function.
    val (low, high) = bounds(9, 2)
    Total = high * 100 + low

    // `continue` skips the rest of the body, `break` leaves the loop. Both are observable here: the
    // iteration that continues is the one that does NOT add to Steps.
    while Total > 250 {
        Total = Total - 100
        if Total == 502 {
            continue
        }
        Steps = Steps + 1000
        if Total < 300 {
            break
        }
    }

    // ---- a sequence, which is the one statement that takes several blocks ----
    sequence {
        Steps = Steps + 1
    } {
        Steps = Steps + 2
    }
}

on stop {
    // Entry points other than `start` still have to lower; nothing here reads the result.
    Steps = Steps
}
