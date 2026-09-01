package dev.ziggle.vscript.runtime

import dev.ziggle.vscript.runtime.EditorLog
import dev.ziggle.vscript.vm.Breakpoints
import java.io.File
import java.util.Properties

/**
 * Breakpoints on disk, in a **sidecar** keyed by graph id.
 *
 * Not in the graph document, deliberately. Arming a breakpoint is a statement about this debugging session,
 * not about the program: writing it into the file would dirty a script you only wanted to look at, put a
 * debugging artefact into every diff, and mean sharing a graph shares where somebody else was poking at it.
 *
 * Keyed by the document's id rather than its path, so renaming a script keeps its breakpoints.
 */
class BreakpointStore(
    private val file: File = File(System.getProperty("user.home"), ".ziggle/vscript-breakpoints.properties"),
) {
    private val props = Properties()
    private var loaded = false

    fun load(graphId: String, into: Breakpoints) {
        into.clear()
        if (graphId.isEmpty()) return
        ensureLoaded()
        val raw = props.getProperty(graphId) ?: return
        for (part in raw.split(',')) {
            if (part.isBlank()) continue
            // "nodeId:hitCount:enabled" — tolerant of a shorter form so a hand-edited file still loads.
            val bits = part.split(':')
            val id = bits.getOrNull(0)?.trim()?.toIntOrNull() ?: continue
            val hits = bits.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
            val enabled = bits.getOrNull(2)?.trim()?.equals("0") != true
            into.add(id, enabled, hits)
        }
    }

    fun save(graphId: String, from: Breakpoints) {
        if (graphId.isEmpty()) return
        ensureLoaded()
        if (from.size == 0) props.remove(graphId)
        else props.setProperty(graphId, from.entries().joinToString(",") { (id, e) ->
            "$id:${e.hitCount}:${if (e.enabled) 1 else 0}"
        })
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { props.store(it, "vscript visual script breakpoints") }
        }.onFailure { EditorLog.w(TAG, "could not save breakpoints: ${it.message}") }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            if (file.isFile) file.inputStream().use { props.load(it) }
        }.onFailure { EditorLog.w(TAG, "could not read breakpoints: ${it.message}") }
    }

    private companion object {
        const val TAG = "VScript"
    }
}
