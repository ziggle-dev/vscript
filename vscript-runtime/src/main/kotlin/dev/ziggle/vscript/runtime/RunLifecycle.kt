package dev.ziggle.vscript.runtime

interface RunLifecycle {

    /** Drop everything the script asked to be painted. Cheap when there is nothing to drop. */
    fun clearDrawing() {}

    /** A render pass is about to run. */
    fun beginRenderFrame() {}

    /** The render pass finished; [ok] is false if it threw. */
    fun endRenderFrame(ok: Boolean) {}

    /** The run is over. Drop the paint AND any control state it carried. */
    fun endRun() {}

    companion object {
        /** A domain with no per-run state — and the default, so a host need not supply one. */
        val NONE: RunLifecycle = object : RunLifecycle {}
    }
}
