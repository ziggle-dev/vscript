graph "shapes"

// A library whose declarations need something WORKED OUT, which is what separates a record from data.
import * as geo from "geometry"

/**
 * How far a step may stray before the walker gives up.
 *
 * Not exported: it exists so the defaults below have something to call, and a caller reaching it would be
 * depending on a number this document means to change.
 */
fn defaultSlack() -> INT = geo::max(a: 2, b: 3)

/**
 * One leg of a route.
 *
 * **`hops` and `slack` are both defaults, and they are not the same kind of thing.** `hops` is written
 * out, so it folds and rides on the field. `slack` has to RUN, so it becomes a function this document
 * keeps and a literal that leaves the field out calls it — at every construction, which is the whole
 * point of a default rather than a shared value.
 *
 * The corners are plain numbers rather than `geo::Point` on purpose: a field typed through a THIRD
 * document does not yet survive being re-exported, because the requalification is one hop deep. See the
 * note in `toolkit`.
 */
export type Leg {
    fromX: INT,
    fromY: INT,
    toX: INT,
    toY: INT,
    hops: INT = 1,
    slack: INT = defaultSlack(),
}

/**
 * The one route this document keeps.
 *
 * A `single` is a record AND the one instance of it, so its fields are an INITIALISER rather than stored
 * data: `budget` is worked out once at start-up. That is what lets a `single` hold a call, another
 * variable, or a function reference instead of only literals.
 */
export single Route {
    budget: INT = defaultSlack() * 10,
    legs: INT = 0,
}

/** The length of a leg, corner to corner, plus whatever slack it was built with. */
export fn legLength(leg: Leg) -> INT =
    geo::abs(n: leg.toX - leg.fromX) + geo::abs(n: leg.toY - leg.fromY) + leg.slack

/**
 * What this document IS, so an importer can name it in one word.
 *
 * `import measure from "shapes"` binds whatever the importer likes to this one declaration — the name on
 * this side is the library's business and the name on that side is the caller's.
 */
export default fn slack() -> INT = defaultSlack()
