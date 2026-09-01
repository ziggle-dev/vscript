package dev.ziggle.vscript.runtime

/**
 * Where the editor's own diagnostics go.
 *
 * The editor used to call `dev.ziggle.util.Log` directly, which is the client's logger and therefore the
 * client's. Six call sites is not much coupling to carry, but it is coupling of exactly the kind that
 * stops the editor being opened anywhere else: a logger is the first thing every host already has and the
 * last thing it wants dictated to it.
 *
 * **A sink, not a facade.** [Sink] is the whole contract — one method, so a host implements it with a
 * lambda — and the level verbs below are conveniences over it that exist only so migrating the call sites
 * was a change of receiver rather than a rewrite of every line.
 *
 * **Discards by default.** An embedder that has not said where output goes has said it does not want any;
 * that is a working configuration, not a misconfiguration, and it must not be a crash. The client
 * installs its sink in `ScriptsHost` before anything else runs.
 */
object EditorLog {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    fun interface Sink {
        fun log(level: Level, tag: String, message: String, error: Throwable?)
    }

    /**
     * Volatile because the sink is installed on whichever thread builds the host and read from the client
     * thread, the render thread and the detached editor window's own thread. It is written once in
     * practice, but "once" that is not published is a value another thread may never see.
     */
    @Volatile
    var sink: Sink = Sink { _, _, _, _ -> }

    fun d(tag: String, message: String) = sink.log(Level.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = sink.log(Level.INFO, tag, message, null)
    fun w(tag: String, message: String) = sink.log(Level.WARN, tag, message, null)
    fun e(tag: String, message: String) = sink.log(Level.ERROR, tag, message, null)
    fun e(tag: String, message: String, t: Throwable) = sink.log(Level.ERROR, tag, message, t)
}
