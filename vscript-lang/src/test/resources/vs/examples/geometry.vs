graph "geometry"

// A library document. Nothing in here is a game node, which is the point: imports, records, visibility
// and functions are LANGUAGE features, so they can be lowered, validated and actually run without a
// client anywhere in sight.

/**
 * A point in the world.
 *
 * Declared here and named across a `::` from the documents that import this one, which is the case
 * qualified type references exist for.
 */
export type Point {
    x: INT,
    y: INT,
}

/** The furthest either axis is apart — the distance that matters on a tile grid. */
export fn chebyshev(a: Point, b: Point) -> INT = max(abs(a.x - b.x), abs(a.y - b.y))

/** Steps walked going along one axis and then the other. */
export fn manhattan(a: Point, b: Point) -> INT = abs(a.x - b.x) + abs(a.y - b.y)

export fn abs(n: INT) -> INT = n < 0 ? 0 - n : n

export fn max(a: INT, b: INT) -> INT = a > b ? a : b

/**
 * Not exported.
 *
 * A `private` declaration is the reason a document can have an inside, and `ExamplesTest` asserts that
 * naming this one across a `::` fails rather than quietly working.
 */
fn secret() -> INT = 99
