graph "modules"

// Every way the module system can be written, in one document that runs.

/** One import for a whole folder — see `toolkit`, which is where the three became one. */
import * as kit from "toolkit"

/**
 * The other document's `export default`, under a name this file chose.
 *
 * Reaching `shapes` a second way, which is worth knowing about: a TYPE reached through two different
 * import paths is two different names, so `firstLeg()` below goes through the barrel and so does the
 * function it is handed to. See the note in `toolkit`.
 */
import measure from "shapes"

var Reach: INT = 0
var Budget: INT = 0
var Span: INT = 0
var Slack: INT = 0

/**
 * A record built through the barrel, leaving both defaults out.
 *
 * `hops` folds and costs nothing; `slack` has to run, which makes this literal a STEP — and therefore
 * makes this function one. Derived from what the body needs rather than declared, so it cannot disagree
 * with it.
 */
fn firstLeg() -> kit::Leg {
    return kit::Leg { fromX: 0, fromY: 0, toX: 3, toY: 4 }
}

on start {
    Reach = kit::legLength(leg: firstLeg())
    // A `single` re-exported through the barrel, read as the instance it is.
    Budget = kit::Route.budget
    // A record and a function that `toolkit` never declared — both forwarded from `geometry`.
    Span = kit::manhattan(a: kit::Point { x: 0, y: 0 }, b: kit::Point { x: 3, y: 4 })
    // The default import, on something that crosses no type boundary.
    Slack = measure()
}

// The surface said in one place, at the bottom, which lets the file read as code with its API last.
export { firstLeg }
