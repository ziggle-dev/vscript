package dev.ziggle.vscript.runtime

import dev.ziggle.vscript.compile.CompiledEntry
import dev.ziggle.vscript.text.TextEntry
import dev.ziggle.vscript.vm.Chunk

/**
 * One handler the runtime can spawn, and the little it needs to know about where it came from.
 *
 * **The runtime stopped being able to name a `Graph`.** Everything downstream of compiling used to be
 * [CompiledEntry] — `document: Graph`, `node: Node`, `chunk` — and a *text*-compiled handler has neither a
 * graph nor a node. Reading the four fields that were actually used off that class and calling them what
 * they are leaves the graph path unchanged and gives the text path somewhere to stand.
 *
 * Everything here is what the runtime asks of an entry, and nothing more:
 *
 *  - [site] — the authoring id a fault, a breakpoint and a trace row are anchored to. A graph node's id,
 *    or a text `Sites` id; the VM has never known the difference (`Chunk.nodeIds` is opaque).
 *  - [documentId] — which document a complaint belongs to. An imported handler's site means nothing in
 *    the root document, so a console row anchored there would point confidently at innocent code.
 *  - [documentName] — what the fiber is called, which is what the panel and the debugger show.
 *  - [isRoot] — whether this is the document being run rather than one it imported. The root's fibers
 *    wait on the imported ones (`Fiber.waitsFor`), because a registry a library fills on start has to be
 *    filled before the importer reads it.
 */
class RunEntry(
    val chunk: Chunk,
    val site: Int,
    val documentId: String,
    val documentName: String,
    val isRoot: Boolean,
) {
    companion object {
        /** The graph front end's entry, as the runtime sees it. */
        fun of(entry: CompiledEntry, root: Any?): RunEntry = RunEntry(
            chunk = entry.chunk,
            site = entry.node.id,
            documentId = entry.document.id,
            documentName = entry.document.name,
            isRoot = entry.document === root,
        )

        /**
         * The text front end's entry, as the runtime sees it.
         *
         * The document's import REFERENCE stands in for the graph's document id. Both are only ever used
         * to say which file a complaint belongs to, and a text run has no saved document to have an id.
         */
        fun of(entry: TextEntry): RunEntry = RunEntry(
            chunk = entry.chunk,
            site = entry.site,
            documentId = entry.document,
            documentName = entry.name,
            isRoot = entry.isRoot,
        )
    }
}
