graph "tally"

// A library that imports another library, so the closure is two deep rather than one.
import * as geo from "geometry"

/**
 * A running total.
 *
 * Deliberately called `Total` here AND in `tour.vs`. Every document's variables live in one flat globals
 * array at run time, so two documents each believing their first variable is slot 0 is exactly the
 * collision `ImportClosure`'s base offsets exist to prevent — and a shared name is how you find out
 * whether they really do.
 */
export var Total: INT = 0

/**
 * Sum of 1..n, by recursion.
 *
 * Recursive rather than a loop because this file is the import-closure fixture and a recursive call is
 * what exercises it. A loop would be the ordinary way to write this today: `var acc = 0` inside a body is
 * a local you can assign, and it belongs to one call, so it is reentrant where a graph `var` is not.
 *
 * @param n how far to count
 * @return the sum, or 0 for anything below 1
 */
export fn sumTo(n: INT) -> INT = n <= 0 ? 0 : n + sumTo(n - 1)

/** Reaches through this document's own import, so a caller is two documents away from `geo`. */
export fn spanOf(a: geo::Point, b: geo::Point) -> INT = geo::manhattan(a, b)

/** Adds to the shared total and hands back what it now holds. */
export fn add(n: INT) -> INT {
    Total = Total + n
    return Total
}
