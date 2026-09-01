package dev.ziggle.vscript.runtime

import dev.ziggle.vscript.vm.Breakpoints

/**
 * What a debug drawer asks of "the thing being debugged". [DebugSession] answers over a local
 * [ScriptRuntime]; a host whose VM runs elsewhere answers from a mirror of its state and forwards the verbs.
 * Values arrive pre-rendered ([Variable.display]) so the mirror never needs the domain's types.
 */
interface DebugSurface {
    val isPaused: Boolean
    val breakpoints: Breakpoints
    fun contexts(): List<Context>
    fun focused(): Context?
    fun stoppedReason(): StoppedReason?
    fun stopToken(): Long
    fun stackTrace(contextId: Int): List<StackFrame>
    fun scopes(contextId: Int, frameIndex: Int = 0): List<Scope>
    fun valueOf(contextId: Int, nodeId: Int, pin: String): Variable?
    fun resume()
    fun stop()
    fun stepOver()
    fun stepInto()
    fun stepOut()
    fun stepIntoData()
}
