package dev.ziggle.vscript.runtime

/**
 * What an `on tick` handler is to the host running it.
 *
 * The language has one spelling for "once per tick" and two hosts want two different things from it.
 *
 * - [PASS] — the client's. A tick is for NOTICING: a fresh fiber per handler per tick, one bounded pass
 *   with staged writes that are committed only if it finishes, and no waiting inside — a wait there is a
 *   wait for the whole client thread. Driven by [ScriptRuntime.gameTick].
 * - [LOOP] — a server's. The tick IS the program: each handler is a long-lived fiber whose body runs once
 *   per [ScriptRuntime.tick], sequences and waits allowed — "mine, wait twenty ticks, push to the chest" is
 *   one pass that happens to take twenty-one ticks, and the next pass starts when it is done. Spawned after
 *   the start fibers finish, ended at a pass boundary when asked to sleep. The compiler emits the loop
 *   (`GraphCompiler.tickLoop`) and the validator lifts the cannot-wait rule (`Validator.tickMayWait`).
 */
enum class TickMode { PASS, LOOP }
