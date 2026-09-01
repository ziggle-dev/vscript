package dev.ziggle.vscript.shell

import dev.ziggle.vscript.editor.ScriptDomain
import dev.ziggle.vscript.editor.ScriptsPanel
import dev.ziggle.vscript.runtime.EditorDoc
import java.io.File

object Main {

    @JvmStatic
    fun main(args: Array<String>) {
        val workspace = workspace(args.getOrNull(0))
        EditorDoc.workspace = workspace
        println("vs editor — workspace: $workspace")

        val panel = ScriptsPanel(domain())

        args.getOrNull(1)?.let { open(panel, workspace, it) }

        // A smoke run draws a fixed number of frames and exits, so that "the standalone editor opens" is
        // something a build can check rather than something a person has to sit and watch.
        val frames = System.getProperty(FRAMES_PROPERTY)?.toIntOrNull() ?: 0
        if (frames > 0) println("smoke run: $frames frames, then exit")

        GlfwEditorWindow(TITLE) {
            // In the client these are three clocks on two threads: `tick` rides the ~20ms client tick,
            // `renderFrame` the frame, `gameTick` the ~600ms server beat. Standalone there is one thread
            // and no server, so the first two collapse into the frame and the third does not exist —
            // an `on tick` handler will not fire here, because nothing has happened for it to fire on.
            panel.tick()
            panel.renderFrame()
            panel.render()
        }.run(frames = frames)

        if (frames > 0) println("drew $frames frames with no game attached, and closed cleanly")
    }

    /**
     * Whichever domain is on the classpath, or none.
     *
     * **Looked up, never named.** `:vscript-shell` knows no game — that is the property phase 5 spent
     * itself establishing, and `DomainFreeTest` enforces it by refusing any mention of a domain pack in
     * this module's sources. Naming one here to make the standalone editor useful would trade the whole
     * point of the module for a convenience.
     *
     * `ServiceLoader` is a mechanism rather than a name, so both hold: run the shell with
     * `:shell-nodes` on the classpath and the editor has the game's catalogue; run it without and it is
     * [ScriptDomain.NONE], which is a real editor with an empty one rather than a stub.
     *
     * It matters because an editor with no domain is RED for every real script. Since SDK 1.40.0 the
     * game's types belong to the pack, so without it the editor does not know what `ItemRef` or `Tile`
     * is, let alone `bankOpen` — and six of the corpus's 117 documents read clean.
     */
    private fun domain(): ScriptDomain {
        val found = runCatching {
            java.util.ServiceLoader.load(ScriptDomain::class.java, Main::class.java.classLoader).firstOrNull()
        }.getOrNull()
        println(found?.let { "domain: ${it.javaClass.simpleName}" } ?: "domain: none — the language only")
        return found ?: ScriptDomain.NONE
    }

    /** Open [name] on whichever surface its extension says, relative to the workspace unless absolute. */
    private fun open(panel: ScriptsPanel, workspace: File, name: String) {
        val file = File(name).let { if (it.isAbsolute) it else File(workspace, name) }
        if (!file.isFile) {
            System.err.println("no such document: $file")
            return
        }
        if (file.extension.equals("vs", ignoreCase = true)) {
            // Through the panel's own open, so this takes exactly the path the file tree and the search
            // box take: compile, load the buffer, mark the file, show the code tab. Doing it here by hand
            // was how the shell came up on an empty CANVAS with the script compiled but nothing shown.
            panel.openTextFile(file)
            val errors = panel.textScript?.compiled?.errors.orEmpty()
            println(if (errors.isEmpty()) "compiled ${file.name}" else "${file.name}: ${errors.size} error(s)")
            errors.take(20).forEach { println("  ${it.span}: ${it.message}") }
            if (errors.size > 20) println("  ... and ${errors.size - 20} more")
        } else {
            panel.openDocument(file)
            // **Reported, because `openDocument` cannot fail out loud.** It catches everything and turns it
            // into a status line for the toolbar, which is right for a person clicking Open and useless to
            // a script: a graph that failed to parse would have left this run looking identical to a
            // successful one. The open document is the only honest answer.
            val doc = panel.doc
            if (doc == null) {
                System.err.println("could not open ${file.name}")
            } else {
                println("opened ${file.name}: ${doc.nodes.size} nodes, ${doc.links.size} links")
            }
        }
    }

    /**
     * Where documents live this run.
     *
     * A temp directory by default, deliberately: an editor that silently adopted `~/.ziggle/graphs` would
     * be writing into a running client's documents, and the first save would be a surprise.
     */
    private fun workspace(given: String?): File {
        val dir = when {
            given.isNullOrBlank() -> File(System.getProperty("java.io.tmpdir"), "vs-shell")
            given.startsWith("~") -> File(System.getProperty("user.home"), given.removePrefix("~").trimStart('/', '\\'))
            else -> File(given)
        }
        dir.mkdirs()
        return dir
    }

    private const val TITLE = "vs editor — no game attached"

    /** `-Dziggle.vscript.frames=N` — draw N frames and exit. See [GlfwEditorWindow.run]. */
    private const val FRAMES_PROPERTY = "ziggle.vscript.frames"
}
