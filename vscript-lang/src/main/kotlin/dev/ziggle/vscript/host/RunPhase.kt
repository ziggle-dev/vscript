package dev.ziggle.vscript.host

/**
 * Whether the host has asked the running script to hand over.
 *
 * **A seam for the same reason [Clock] and [FileStore] are.** The language owns what `on sleep` MEANS —
 * when its handlers compile, what order they run in, that a loop may poll for the request — and owns
 * none of the policy about when a handoff is a good idea. That belongs to whatever is driving the run:
 * a button, an orchestrator, a debug command. One boolean is the whole of what has to cross.
 *
 * **A class rather than an interface, on purpose.** [Clock] has two implementations and [FileStore] has
 * three, because each abstracts something that genuinely varies. This does not vary — a request is a
 * flag — and an interface here would be an invitation to write a second one that answers differently
 * from the first about the same run.
 *
 * **Nothing here interrupts anything.** Raising the flag asks; the script's own loop is what decides
 * where to stop and when to leave. A script that never reads it never yields, which is the host's
 * problem to escalate (a timeout) and not this class's to solve.
 */
class RunPhase {

    /**
     * Has a sleep been asked for?
     *
     * `@Volatile` because the ask can arrive from a socket thread while the VM reads it on the client
     * thread. A stale read costs one pass — the loop asks again next time round — but a torn one would
     * be a genuine puzzle, and the flag is read once per pass rather than once per instruction, so the
     * barrier costs nothing worth measuring.
     */
    @Volatile
    var sleepRequested: Boolean = false
        private set

    /** Ask the script to finish what it is doing and hand over. Idempotent. */
    fun requestSleep() {
        sleepRequested = true
    }

    /**
     * Forget the request.
     *
     * Called when a run STARTS, not when the sleep handlers begin. Clearing it early would make a
     * library's `always on sleep` unable to tell why it was running — the flag is the only thing that
     * distinguishes "we are handing over" from "the script simply ended" — and leaving it set across a
     * wake would have the newly-started loop quiesce on its very first pass.
     */
    fun clear() {
        sleepRequested = false
    }
}
