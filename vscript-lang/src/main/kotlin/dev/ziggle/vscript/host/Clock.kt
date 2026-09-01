package dev.ziggle.vscript.host

/**
 * The time source the scheduler measures cooperative waits against.
 *
 * **Declared here rather than taken from the SDK**, and that is the whole of why: this module is the
 * language — a lexer, a parser, a compiler and a register VM — and it should not need the game's API on its
 * classpath to compile. One method was the only thing `dev.ziggle.script.Clock` was being used for, so
 * depending on the SDK for it would have meant every consumer of the language dragging in the game.
 *
 * A `fun interface`, so the client adapts its own clock with `Clock { sdkClock.nowMs() }` and a test hands
 * over a counter it advances by hand — no `Thread.sleep`, no live client.
 */
fun interface Clock {
    fun nowMs(): Long
}

/** Wall-clock time — the production [Clock]. */
object SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}
