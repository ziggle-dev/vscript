package dev.ziggle.vscript.editor

import dev.ziggle.vscript.host.FileStore
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.nodes.NodeLibrary
import dev.ziggle.vscript.nodes.OutputConverter
import dev.ziggle.vscript.runtime.RunLifecycle
import dev.ziggle.vscript.vm.ActuatorSink

/**
 * The DOMAIN an editor is hosting: its verbs, and the few things only it can answer.
 *
 * ### Why this exists
 *
 * The workbench used to reach for a specific game. `ScriptsPanel` named `GameNodes`, `ScriptFiles`,
 * `GameValueOut`, `GameRunLifecycle` and `CatalogDump` directly — five imports from this client's own
 * bridge, in the one class that opens documents and hosts both authoring surfaces. Everything below it had
 * been made domain-free by then; the workbench was the last thing that knew what game it was editing, and
 * so the last thing that could not move out of this repo.
 *
 * ### The five questions, and why they are one interface
 *
 * They arrive together and they must AGREE. A library, the file access its verbs get, how its own values
 * become records, what a run accumulates, and where to publish the catalogue — supply four of the five and
 * the editor works right up until the one you forgot. That has already happened once inside `library`
 * alone: three contributions were copied across by hand, `records` was silently left behind, and a script
 * validated and compiled and then died at load with `unknown host function 'ItemRef.name'`. One object is
 * the fix for the same class of mistake one level up.
 *
 * ### [NONE] is a real editor, not a stub
 *
 * With no domain the editor still opens, still parses, still validates, still round-trips a document —
 * there is simply nothing to call. That is exactly what a headless test, a CI compile check and the
 * standalone shell want, and it is the property that makes `:vscript-shell`'s gate meaningful: *the shell
 * opens a `.vs` and a `.json` with no game attached*.
 */
interface ScriptDomain {

    /**
     * The node library: descriptors for the editor, host functions for the VM.
     *
     * **Hand back the library the domain built, not one reassembled from its parts.** A library carries
     * defs, enums AND records, and a host record binds its accessors from the library's own `records`
     * list — so a rebuilt one typechecks and then fails at load. See the note above.
     */
    fun library(): NodeLibrary

    /** Where a script's file verbs are rooted. The language refuses them all without one. */
    val files: FileStore get() = FileStore.DENIED

    /** How the domain's own values become the records a script reads fields off. */
    val valueOut: OutputConverter get() = OutputConverter.NONE

    /** What this domain accumulates per run and per frame, and must drop when a run ends. */
    val lifecycle: RunLifecycle get() = RunLifecycle.NONE

    /** Where blocking verbs run, or null when nothing can act — a headless editor, or a test. */
    val actuator: ActuatorSink? get() = null

    /**
     * The catalogue has been built.
     *
     * For a host that publishes it to something else — this client writes it out so an IDE plugin that may
     * never see the client running still learns new nodes. Best-effort by contract: a catalogue that could
     * not be published is not worth failing a launch over.
     */
    fun catalogueReady(catalog: NodeCatalog) {}

    /**
     * The runtime that will run this domain's scripts exists, and here is how to call into it.
     *
     * For a domain whose nodes take a **handler** — `fn(T)` — and have to call it back when something
     * happens. The node keeps the function value; this is the thing that turns one into a running fiber.
     * Called once, before any script has started.
     *
     * Handed as a [Caller] rather than as the runtime itself on purpose. A domain has no business
     * driving a run — starting, stopping and stepping belong to whatever owns the panel — and the one
     * capability it genuinely needs is this one. A narrow interface is also what keeps this seam
     * game-agnostic: nothing here knows what an event is, only that somebody wants a function called.
     */
    fun runtimeReady(caller: Caller) {}

    /** How a domain gets a function value the script gave it called. See [runtimeReady]. */
    fun interface Caller {
        /**
         * Schedule [fn] with [args]. False when it could not be — nothing running, wrong arity, or the
         * runtime's queue is full.
         *
         * It SCHEDULES: the body becomes a fiber on the next pass, because handler bodies may act and
         * may wait. Safe to call from any thread.
         */
        fun call(fn: dev.ziggle.vscript.vm.FunctionValue, args: List<Any?>): Boolean
    }

    companion object {
        /**
         * No domain at all: the editor with an empty catalogue.
         *
         * Not an error case. It is what an embedder that has registered nothing yet should see, and what
         * the shell uses when it is opened without a game.
         */
        val NONE: ScriptDomain = object : ScriptDomain {
            override fun library(): NodeLibrary = NodeLibrary(emptyList())
        }
    }
}
