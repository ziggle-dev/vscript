package dev.ziggle.vscript.editor

import dev.ziggle.vscript.runtime.DebugSession
import dev.ziggle.vscript.runtime.ScriptRuntime
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import dev.ziggle.vscript.compile.Severity
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.FunctionPin
import dev.ziggle.vscript.model.GraphDoc
import dev.ziggle.vscript.model.GraphVariable
import dev.ziggle.vscript.model.Literals
import dev.ziggle.vscript.model.Node
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.Types
import dev.ziggle.vscript.model.NodeDescriptor
import dev.ziggle.vscript.model.effectivePinType
import dev.ziggle.vscript.model.resolveNode
import dev.ziggle.vscript.vm.StepMode
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import dev.ziggle.vscript.runtime.EditorDoc
import dev.ziggle.vscript.runtime.DocumentSource
import dev.ziggle.vscript.editor.graph.OrthogonalRouter
import dev.ziggle.vscript.editor.graph.Tuning

/**
 * The node editor, driven from outside the client.
 *
 * Everything the editor's UI does — author a graph, run it, break on a node, read the values at the pause —
 * reachable over the debug websocket as text. Not a convenience: a visual editor is the one subsystem whose
 * behaviour cannot be checked by reading it, and until it could be driven headlessly the only way to know
 * whether a node worked was for a person to wire it up and watch. This is the seam that makes a graph
 * something a test, a script or an agent can build and step.
 *
 * The vocabulary is deliberately the same as the UI's, not a lower-level one. `add`, `link`, `set`, `run`,
 * `break`, `step` are the operations the editor offers, and a second vocabulary for the same operations
 * would drift from it the first time either side changed.
 *
 * ### Threading
 *
 * The document and the VM belong to the **client thread**: the editor renders there and the scheduler ticks
 * there, which is exactly why neither needs a lock. A command arrives on a socket thread, so every one of
 * them is queued and run on the next tick while the caller waits. That makes a command as atomic as a
 * frame, and it is why [drain] must be called from [ScriptsPanel.tick] and nowhere else.
 */
class VScriptControl(private val panel: ScriptsPanel) {

    /** Work waiting for the client thread. Drained once per tick, in arrival order. */
    private val queue = ConcurrentLinkedQueue<() -> Unit>()

    /**
     * Run everything queued. Client thread only.
     *
     * Drained by count rather than until empty: a command that queued another would otherwise be able to
     * hold the tick indefinitely, and the tick is the game's.
     */
    internal fun drain() {
        var budget = queue.size
        while (budget-- > 0) {
            val work = queue.poll() ?: return
            runCatching { work() }
        }
    }

    /**
     * Run [block] on the client thread and return what it produced.
     *
     * A timeout rather than an indefinite wait because the caller is a socket: if ticks have stopped (the
     * client is shutting down, the game is frozen) a hung command would hold that connection's reader
     * thread forever, and the reason it hung is worth saying out loud.
     */
    private fun onClientThread(block: () -> JsonObject): JsonObject {
        val done = CountDownLatch(1)
        var out: JsonObject? = null
        queue += {
            out = runCatching(block).getOrElse { fail(it.message ?: it.javaClass.simpleName) }
            done.countDown()
        }
        if (!done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            return fail("the client thread did not run the command within ${TIMEOUT_SECONDS}s — is the client ticking?")
        }
        return out ?: fail("no result")
    }

    /** Parse and run one command line, returning the JSON reply body. */
    fun command(line: String): String {
        val trimmed = line.trim()
        val sp = trimmed.indexOf(' ')
        val verb = (if (sp < 0) trimmed else trimmed.substring(0, sp)).lowercase()
        val rest = if (sp < 0) "" else trimmed.substring(sp + 1).trim()
        if (verb.isEmpty() || verb == "help") return HELP.toString()
        return onClientThread { run(verb, rest) }.toString()
    }

    // ---- the commands ------------------------------------------------------------------------------

    private fun run(verb: String, rest: String): JsonObject {
        val args = if (rest.isEmpty()) emptyList() else rest.split(' ').filter { it.isNotEmpty() }
        return when (verb) {
            // ---- documents
            "list" -> list()
            "new" -> { panel.newDocument(rest.ifBlank { "untitled" }); ok("new" to panel.docName()) }
            "open" -> open(rest)
            "save" -> { panel.saveDocument(); ok("saved" to (doc().file?.name ?: doc().name)) }
            "rename" -> { panel.renameDocument(rest.trim()); ok("name" to doc().name) }
            // The document verbatim, parsed back into the reply rather than embedded as a string, so a
            // caller reads `.graph.nodes[0].type` instead of un-escaping a blob.
            "show" -> JsonObject().apply {
                addProperty("type", TYPE)
                addProperty("ok", true)
                @Suppress("DEPRECATION")
                add("graph", com.google.gson.JsonParser().parse(GraphDoc.toJson(doc().toGraph())))
            }

            // ---- the text surface
            "text" -> text(args)
            "export" -> export(args)
            "import" -> importText(args)
            "spans" -> spans(args)

            // ---- the catalogue
            "nodes" -> nodes(rest)

            // ---- authoring
            "add" -> add(args)
            "rm" -> remove(args)
            "move" -> move(args)
            "size" -> size(args)
            "measure" -> measure()
            "wires" -> wires()
            "arrange" -> {
                panel.arrange()
                ok("arranged" to doc().nodes.size)
            }
            "link" -> link(args)
            "unlink" -> unlink(args)
            "set" -> setLiteral(rest)
            "setvar" -> setVariable(rest)
            "clear" -> clearLiteral(args)
            "bind" -> bind(args)
            "var" -> variable(args)
            "rmvar" -> { doc().removeVariable(rest.trim()); ok("removed" to rest.trim()) }

            // ---- imports. NOT `import`, which already means "read a .vs file into the editor" — a
            // whole-document overwrite, and nothing like declaring a dependency on another document.
            "use" -> useDocument(args)
            "rmuse" -> { doc().removeImport(rest.trim()); ok("removed" to rest.trim()) }
            "uses" -> listImports()

            // ---- user functions
            "fn" -> newFunction(args)
            "fns" -> listFunctions()
            "sig" -> signature(rest)
            "rmfn" -> { doc().removeFunction(rest.trim()); ok("removed" to rest.trim()) }
            "type" -> declaredType(rest)
            "types" -> listTypes()
            "rmtype" -> { doc().removeStruct(rest.trim()); ok("removed" to rest.trim()) }
            "tune" -> tune(rest)
            "note" -> note(rest)
            "body" -> body(args)
            "callee" -> callee(args)
            "fold" -> fold(args)
            "undo" -> { doc().undo(); ok("undone" to true) }
            "redo" -> { doc().redo(); ok("redone" to true) }

            // ---- running
            "check" -> check(args)
            // `run` builds for debugging, like pressing Run in the editor; `run --release` drops the TRACE
            // markers and pools constants by value, so breakpoints and live editing do not apply.
            "run" -> {
                val debug = RELEASE !in args
                // A `.vs` runs through the TEXT front end; `--graph` forces the old lowering path, which is
                // how the two are compared against each other rather than argued about.
                val err = panel.startRun(debug = debug, forceGraph = GRAPH in args)
                if (err != null) {
                    failWithIssues(err)
                } else {
                    ok(
                        "running" to true,
                        "debug" to debug,
                        "frontEnd" to if (panel.textScript != null && GRAPH !in args) "text" else "graph",
                    )
                }
            }
            "call" -> call(args)
            "stop" -> { panel.runtime.stop(); ok("running" to false) }
            "sleep" -> sleep(args)
            "wake" -> {
                val err = panel.startWake()
                if (err != null) failWithIssues(err) else ok("phase" to panel.runtime.phase.name.lowercase())
            }
            "state" -> state()
            "log" -> log(args.firstOrNull()?.toIntOrNull() ?: 50)

            // ---- debugging
            "break" -> { panel.armBreakpoint(site(args, 0), true); ok("breakpoints" to breakpointIds()) }
            "unbreak" -> { panel.armBreakpoint(site(args, 0), false); ok("breakpoints" to breakpointIds()) }
            "breaks" -> ok("breakpoints" to breakpointIds())
            "continue" -> { panel.dbg.resume(); ok("resumed" to true) }
            "pause" -> { panel.dbg.pause(); ok("pausing" to true) }
            "step" -> step(args.firstOrNull()?.lowercase() ?: "over")
            "stack" -> stack(args.firstOrNull()?.toIntOrNull())
            "vars" -> vars(args.firstOrNull()?.toIntOrNull())

            else -> fail("unknown subcommand '$verb' — try 'graph help'")
        }
    }

    // ---- documents ---------------------------------------------------------------------------------

    private fun list(): JsonObject {
        val dir = EditorDoc.graphsDir()
        val all = dir.listFiles { f: File -> f.isFile } ?: emptyArray()
        return ok(
            "dir" to dir.absolutePath,
            "files" to all.filter { it.extension == "json" }.map { it.nameWithoutExtension }.sorted(),
            // Exports live beside the documents, and are listed separately because they are a different
            // thing: a `.vs` is a source you `import`, a `.json` is a document you `open`. Without this
            // there is no way to find out an export exists short of shelling out.
            "text" to all.filter { it.extension == "vs" }.map { it.nameWithoutExtension }.sorted(),
            "open" to panel.docName(),
            "dirty" to (panel.doc?.dirty ?: false),
        )
    }

    private fun open(name: String): JsonObject {
        val clean = name.trim().removeSuffix(".json")
        if (clean.isEmpty()) return fail("usage: graph open <name>")
        val file = File(EditorDoc.graphsDir(), "$clean.json")
        if (!file.isFile) return fail("no script named '$clean' — try 'graph list'")
        panel.openDocument(file)
        return ok("open" to panel.docName())
    }

    // ---- the text surface --------------------------------------------------------------------------

    /**
     * `graph text [--layout]` — the open script as source, in the reply.
     *
     * The one command here that touches no disk, and the one worth reaching for most: reading a graph is
     * otherwise `show`'s document JSON, which says what the nodes *are* and nothing about what the script
     * *does*. Forty lines of text answer "what does this script do" in a way three hundred lines of node
     * records cannot.
     */
    private fun text(args: List<String>): JsonObject {
        val src = printer().print(doc().toGraph())
        return ok("name" to doc().name, "lines" to src.lineSequence().count(), "text" to src)
    }

    /** `graph export [file] [--layout]` — the same thing, written to a `.vs` file. */
    /**
     * `graph export [file] [--force]` — write the open document as `.vs`.
     *
     * **Refuses to overwrite a file that has comments in it**, unless forced. A comment lives in its source
     * document and is not carried into the graph, so a printed export cannot contain one — which means
     * writing over a hand-authored `.vs` destroys every `//` and every `/* */` in it, silently, from a
     * socket, without the person who wrote them being the one who asked. `export` with no name uses the
     * DOCUMENT's name, so landing on such a file is easy to do by accident.
     *
     * The check is on the FILE rather than on the document because that is where the loss happens; a file
     * with no comments is overwritten as before, which is the ordinary re-export case.
     */
    private fun export(args: List<String>): JsonObject {
        val file = textFile(args.firstOrNull { !it.startsWith("--") } ?: doc().name)
        val src = printer().print(doc().toGraph())
        val existed = file.isFile
        if (existed && !args.contains("--force")) {
            val comments = commentLines(file.readText())
            if (comments > 0) {
                return fail(
                    "${file.name} has $comments comment line(s) and exporting would erase them — comments " +
                        "live in the .vs, not in the graph. Use 'graph export ${file.name} --force' if that " +
                        "is what you want."
                )
            }
        }
        file.parentFile?.mkdirs()
        file.writeText(src)
        return ok(
            "file" to file.absolutePath,
            "lines" to src.lineSequence().count(),
            // Said out loud rather than left to be discovered: `export` with no name uses the DOCUMENT's
            // name, so it is easy to land on a file somebody wrote by hand without having asked to.
            "overwrote" to existed,
        )
    }

    /**
     * `graph import <file> [--force]` — parse a `.vs` file and make it the open script.
     *
     * Refuses on any error and reports **line and column**, not node ids: the whole claim of the text surface
     * is that it is usable without the canvas open, and a complaint about "node 23" is not.
     *
     * Unsaved edits are refused rather than replaced. An import is a whole-document overwrite, it arrives
     * over a socket, and the person whose work it discards is not necessarily the one who sent it.
     */
    private fun importText(args: List<String>): JsonObject {
        val name = args.firstOrNull { !it.startsWith("--") }
            ?: return fail("usage: graph import <file> [--force]")
        val file = textFile(name)
        if (!file.isFile) return fail("no text script at ${file.absolutePath}")
        val open = panel.doc
        if (open != null && open.dirty && FORCE !in args) {
            return fail(
                "'${open.name}' has unsaved edits — 'graph save' first, " +
                    "or 'graph import $name --force' to discard them",
            )
        }
        // Before reading it: the document's own imports are resolved during `read`, and they resolve
        // against whatever roots the source has at that moment.
        panel.importRoot = scriptsRootOf(file)
        val source = file.readText()
        // **What was imported is a PROGRAM, and this is what it compiles to.** The gate, because this is
        // what will run; the canvas view below is a bonus.
        val script = panel.compileText(file, source)
        if (!script.compiled.ok) {
            return JsonObject().apply {
                addProperty("type", TYPE)
                addProperty("ok", false)
                addProperty("error", "${file.name} was not imported — ${script.compiled.errors.size} error(s)")
                add("errors", textDiagnostics(script.compiled.errors))
            }
        }
        // **No canvas view. Nothing on the text path lowers to a graph any more.**
        //
        // This used to call `VsText.read` for a drawable version, best-effort, on the argument that a
        // script nobody could look at was worse than one nobody could run. That argument stopped holding
        // once the two front ends genuinely diverged: `Lower` REFUSES constructs the text path compiles
        // and runs — `xs[i] = v` is the one that surfaced it — and a refusal is an exception rather than
        // the null the best-effort branch was watching for, so the import failed outright and the script
        // could not be run at all. Exactly the thing the old comment said was backwards.
        //
        // The canvas is not a target, so the honest thing is to stop pretending: an empty document, said
        // out loud, and the text front end is the only thing that reads a `.vs` file.
        panel.loadGraph(
            dev.ziggle.vscript.model.Graph(
                id = java.util.UUID.randomUUID().toString(),
                name = file.nameWithoutExtension,
            ),
            "imported ${file.name} (text only)",
        )
        val undrawable = true
        // The line ↔ site bridge, kept rather than dropped: an external debugger addresses code by line
        // and the VM only understands ids. The TEXT front end's sites, not the lowered graph's node ids —
        // those describe a document that is no longer what runs, so arming one would never fire. Set
        // AFTER loadGraph, which clears both.
        panel.textSpans = script.sites.spans()
        panel.textScript = script
        return JsonObject().apply {
            addProperty("type", TYPE)
            addProperty("ok", true)
            addProperty("file", file.absolutePath)
            // **The REFERENCE, not the canvas document's name.** This is what the IDE debugger stores as
            // "the document I ran" and compares against every frame's chunk name, and chunk names are the
            // reference now. Since the `graph "…"` header became optional, the lowered graph answers
            // `untitled` for most documents — so reporting that made the comparison always fail.
            addProperty("name", panel.textScript?.ref ?: doc().name)
            addProperty("nodes", doc().nodes.size)
            if (undrawable) {
                // Said out loud rather than left as an empty canvas, which reads as a failed import.
                addProperty(
                    "canvas",
                    "text scripts are not drawn on the canvas — it compiles and runs from the text " +
                        "front end, which is the only thing that reads a .vs file",
                )
            }
            // A file with no `graph "…"` line lowers to "untitled", and saving it would write untitled.json.
            // Named here rather than renamed automatically, so what is on disk still says what the document
            // is called and an export/import cycle cannot quietly grow a name it did not have.
            if (doc().name == "untitled") {
                addProperty("note", "the file does not name itself — 'graph rename <name>' before saving")
            }
            add("warnings", textDiagnostics(script.compiled.warnings))
        }
    }

    /** See [dev.ziggle.vscript.lang.Comments.commentLines] — shared with the IDE's Reformat, which can
     *  destroy comments the same way this can. */
    private fun commentLines(source: String): Int = dev.ziggle.vscript.lang.Comments.commentLines(source)

    /**
     * A `.vs` path as somebody would type it: a bare name lands beside the scripts, the extension is
     * optional, and an absolute path is taken as given.
     */
    /**
     * Write a graph variable in the RUNNING graph.
     *
     * The counterpart of `set` for the other half of what a debugger shows. `set` retunes a literal, which
     * belongs to the document and is saved with it; this writes one slot of the run's globals and touches
     * nothing on disk. Nudging a counter to reproduce a case should not quietly edit the program, and
     * keeping them as two verbs is what makes which one you did obvious afterwards.
     */
    private fun setVariable(rest: String): JsonObject {
        val parts = rest.split(' ', limit = 2)
        if (parts.size < 2) return fail("usage: graph setvar <name> <value>")
        val name = parts[0]
        val decl = doc().variable(name)
            ?: return fail("no variable '$name' — 'graph var' lists them")
        // The document's enums come along, because a variable of a DECLARED enum has a text form — its
        // member's name — and nothing else does. Without them this returned null for `Using ThreeTick`,
        // the write succeeded, and the variable became null while the reply said it had been set.
        val value = literalOf(decl.type, parts[1])
        if (value == null && parts[1].isNotBlank()) {
            return fail("'${parts[1]}' is not a valid ${decl.type} — the variable was left alone")
        }
        if (!panel.runtime.setVariable(name, value)) {
            return fail(
                "'$name' has no slot in the running graph — either nothing is running, or nothing " +
                    "reads it and it was never compiled into the layout",
            )
        }
        return ok("name" to name, "type" to decl.type.toString(), "value" to value)
    }

    /**
     * node id → the source it was lowered from, for the open document or for a named file.
     *
     * **The bridge an external debugger needs.** Everything downstream of the graph is addressed by node
     * id — `Op.TRACE`, `Breakpoints`, `SlotMap`, the inspector — and every editor in the world addresses
     * code by line. `Lower` already computes the mapping; this is the seam that lets something outside
     * the client read it, so "break on line 42" becomes "break on node 7" without the VM learning what a
     * line is.
     *
     * With no argument it answers for the OPEN document, which is the honest source: those are the ids
     * the runtime is actually executing. A file argument re-reads that file instead, for tooling that
     * wants the table before importing — the ids agree because lowering is deterministic, but prefer the
     * no-argument form when a document is already loaded.
     */
    private fun spans(args: List<String>): JsonObject {
        val name = args.firstOrNull { !it.startsWith("--") }
        val spans: Map<Int, dev.ziggle.vscript.lang.Span>
        val from: String
        if (name != null) {
            val file = textFile(name)
            if (!file.isFile) return fail("no text script at ${file.absolutePath}")
            // Before reading it: the document's own imports are resolved during `read`, and they resolve
        // against whatever roots the source has at that moment.
        panel.importRoot = scriptsRootOf(file)
            // The TEXT front end's sites, for the same reason `importText` uses them: the lowered graph
            // describes a document that is not what runs, so a line resolved through it addresses nothing.
            val compiled = panel.compileText(file, file.readText())
            if (!compiled.compiled.ok) {
                return JsonObject().apply {
                    addProperty("type", TYPE)
                    addProperty("ok", false)
                    addProperty("error", "${file.name} has ${compiled.compiled.errors.size} error(s)")
                    add("errors", textDiagnostics(compiled.compiled.errors))
                }
            }
            spans = compiled.sites.spans()
            from = file.absolutePath
        } else {
            val all = panel.textSpans
                ?: return fail(
                    "the open script did not come from text, so it has no lines — " +
                        "'graph import <file>' first, or pass a file: 'graph spans <file>'",
                )
            // **Every document of the run, each row saying which.** A breakpoint belongs to a file and a
            // line, and a run spans its libraries — so a table of the open file alone could not express
            // "stop inside `core/breaks` while `rotation` is running", which is a thing people do and the
            // whole reason a library's frames are steppable at all. The pair is what disambiguates: line
            // 12 of the root and line 12 of a library are two rows, not one, and `document` tells them
            // apart. Filtering to the root instead would have made the ambiguity go away by making the
            // capability go away.
            spans = all
            from = doc().name
        }
        val arr = JsonArray()
        // Titles are a convenience, so they must not be the reason this fails: `graph spans <file>` is
        // legitimately called with nothing open, and `doc()` throws when there is no document.
        val open = panel.doc
        // Resolved once for the whole table — see [filesOf].
        val text = panel.textScript
        val files = text?.let { t -> filesOf(t, spans.keys.mapNotNull { t.sites.documentOf(it) }) }.orEmpty()
        // Sorted by node id so a reader can bisect, and so two calls produce byte-identical output.
        for ((nodeId, span) in spans.toSortedMap()) {
            arr.add(JsonObject().apply {
                addProperty("node", nodeId)
                addProperty("line", span.line)
                addProperty("col", span.col)
                addProperty("start", span.start)
                addProperty("end", span.end)
                // The node's title, so a caller can label a frame without a second round trip. A TEXT
                // site has no node — and the graph the file was also lowered to has an unrelated node
                // under that same number, so labelling from it is worse than not labelling at all. Which
                // FILE it is in is the useful answer instead, and the one `--all` makes necessary.
                if (text == null) {
                    open?.node(nodeId)?.let { n -> descOf(n)?.let { addProperty("title", it.title) } }
                } else {
                    text.sites.documentOf(nodeId)?.let { ref ->
                        addProperty("document", ref)
                        // Where it is ON DISK, so an editor can open the right file rather than guess
                        // from a reference it would have to resolve the same way we just did.
                        files[ref]?.let { addProperty("file", it.absolutePath) }
                    }
                }
            })
        }
        return JsonObject().apply {
            addProperty("type", TYPE)
            addProperty("ok", true)
            addProperty("from", from)
            addProperty("nodes", arr.size())
            add("spans", arr)
        }
    }

    /**
     * The folder a file's sibling documents are named relative to.
     *
     * The nearest ancestor called `scripts`, else the file's own folder. Named-folder first because that
     * is the layout the IDE's New Project makes and the one `~/.ziggle/graphs` mirrors — a library in
     * `scripts/util/` is `util/banks` to everything in the project, and picking the file's own folder
     * would make it just `banks` and disagree with the IDE.
     */
    /**
     * The folder an imported file's `import "core/pace"` is resolved against.
     *
     * The NEAREST ancestor named `src` or `scripts`. Two names because there are two layouts and both are
     * real: the client's own tree is a folder called `scripts` with the documents directly inside it,
     * while the standalone scripts project is an IDE module whose source root is `src`. Nearest wins, so
     * `…/plugins/scripts/src/core/pace.vs` roots at `src` rather than at the `scripts` above it — which is
     * the whole difference between `core/pace` resolving and "nothing answers to core/pace".
     *
     * Falling back to the file's own folder keeps a loose `.vs` importable on its own terms.
     */
    private fun scriptsRootOf(file: File): File {
        var dir: File? = file.absoluteFile.parentFile
        while (dir != null) {
            if (dir.name == "src" || dir.name == "scripts") return dir
            dir = dir.parentFile
        }
        return file.absoluteFile.parentFile ?: EditorDoc.graphsDir()
    }

    private fun textFile(name: String): File {
        val clean = name.trim().let { if (it.endsWith(".vs")) it else "$it.vs" }
        val given = File(clean)
        return if (given.isAbsolute) given else File(EditorDoc.graphsDir(), clean)
    }

    /**
     * Graph → `.vs` source, for `graph text` and `graph export`.
     *
     * The one direction that survives the surfaces being separated: reading a canvas document out as text
     * is a migration and an inspection aid, and costs one class. The other direction — text back into a
     * canvas — is gone, and with it `Lower` and `VsText`.
     */
    private fun printer() = dev.ziggle.vscript.lang.Print(
        panel.catalog,
        dev.ziggle.vscript.lang.Names(panel.catalog),
        panel.documents,
    )

    /**
     * The text front end's diagnostics, in the same shape as the graph side's.
     *
     * Its own function rather than a shared one over an interface, because the two carry their position
     * differently: a `VsDiagnostic` may have no span at all (a node the lowering synthesised), and a
     * `TextDiagnostic` always points at something the author typed.
     */
    private fun textDiagnostics(items: List<dev.ziggle.vscript.text.TextDiagnostic>): JsonArray {
        val arr = JsonArray()
        for (d in items) {
            arr.add(JsonObject().apply {
                addProperty("message", d.message)
                addProperty("line", d.span.line)
                addProperty("col", d.span.col)
            })
        }
        return arr
    }

    private fun diagnostics(items: List<dev.ziggle.vscript.lang.VsDiagnostic>): JsonArray {
        val arr = JsonArray()
        for (d in items) {
            arr.add(JsonObject().apply {
                addProperty("message", d.message)
                // Absent for a node the lowering synthesised, which has no source of its own — better than
                // reporting 0:0 and having a caller trust it.
                if (d.span.line > 0) {
                    addProperty("line", d.span.line)
                    addProperty("col", d.span.col)
                }
            })
        }
        return arr
    }

    // ---- the catalogue -----------------------------------------------------------------------------

    /**
     * What can be built.
     *
     * The single most important command here: everything else assumes you already know a node type, and
     * there is no other way to find one out without reading the source.
     */
    private fun nodes(filter: String): JsonObject {
        val q = filter.trim().lowercase()
        val out = JsonArray()
        for (d in panel.catalog.all) {
            if (q.isNotEmpty() &&
                !d.type.lowercase().contains(q) &&
                !d.title.lowercase().contains(q) &&
                !d.category.lowercase().contains(q)
            ) continue
            out.add(JsonObject().apply {
                addProperty("type", d.type)
                addProperty("title", d.title)
                addProperty("category", d.category)
                addProperty("kind", d.kind.name)
                if (d.summary.isNotEmpty()) addProperty("summary", d.summary)
                add("in", pins(d.inputs))
                add("out", pins(d.outputs))
            })
        }
        return JsonObject().apply {
            addProperty("type", TYPE)
            addProperty("ok", true)
            addProperty("count", out.size())
            add("nodes", out)
        }
    }

    private fun pins(specs: List<dev.ziggle.vscript.model.PinSpec>): JsonArray {
        val arr = JsonArray()
        for (p in specs) {
            arr.add(JsonObject().apply {
                addProperty("name", p.name)
                addProperty("type", p.type.name)
                if (p.options.isNotEmpty()) add("options", JsonArray().also { a -> p.options.forEach(a::add) })
                if (p.default != null) addProperty("default", p.default.toString())
            })
        }
        return arr
    }

    // ---- authoring ---------------------------------------------------------------------------------

    private fun add(args: List<String>): JsonObject {
        val type = args.getOrNull(0) ?: return fail("usage: graph add <type> [x] [y]")
        if (panel.catalog[type] == null) return fail("no node type '$type' — try 'graph nodes $type'")
        val x = args.getOrNull(1)?.toFloatOrNull() ?: 120f
        val y = args.getOrNull(2)?.toFloatOrNull() ?: 120f
        val n = doc().addNode(type, x, y)
        return ok("id" to n.id, "type" to n.type)
    }

    private fun remove(args: List<String>): JsonObject {
        if (args.isEmpty()) return fail("usage: graph rm <nodeId> [nodeId…]")
        val ids = args.mapNotNull { it.toIntOrNull() }
        doc().edit("delete") { ids.forEach { doc().removeNode(it) } }
        return ok("removed" to ids)
    }

    private fun move(args: List<String>): JsonObject {
        val n = node(args, 0)
        val x = args.getOrNull(1)?.toFloatOrNull() ?: return fail("usage: graph move <nodeId> <x> <y>")
        val y = args.getOrNull(2)?.toFloatOrNull() ?: return fail("usage: graph move <nodeId> <x> <y>")
        doc().edit("move") { n.x = x; n.y = y }
        return ok("id" to n.id, "x" to x, "y" to y)
    }

    /** Resize a container. Only containers have a size — everything else is measured from its contents. */
    private fun size(args: List<String>): JsonObject {
        val n = node(args, 0)
        val w = args.getOrNull(1)?.toFloatOrNull() ?: return fail("usage: graph size <nodeId> <w> <h>")
        val h = args.getOrNull(2)?.toFloatOrNull() ?: return fail("usage: graph size <nodeId> <w> <h>")
        if (panel.catalog[n.type]?.kind?.isContainer != true) return fail("node ${n.id} is not a container")
        doc().edit("resize") { n.w = w.coerceAtLeast(120f); n.h = h.coerceAtLeast(80f) }
        return ok("id" to n.id, "w" to n.w, "h" to n.h)
    }

    /**
     * Each node's measured rectangle.
     *
     * The one thing a caller outside the client cannot work out for itself: a node's size comes from its
     * title, its pins and its editors, measured against a live font. Without this, anything laying out a
     * graph over this socket is guessing widths — which is how one ends up overlapping itself.
     */
    /**
     * `graph wires` — the waypoints each wire was last routed along.
     *
     * The counterpart to `measure` for links. Routing has enough interacting rules that working out which
     * one applied to a given wire by reading the code is guesswork, and guessing wrong looks identical to
     * the feature not working at all.
     */
    private fun wires(): JsonObject {
        val routes = panel.routedWires()
        val arr = JsonArray()
        for (l in doc().links.sortedBy { it.id }) {
            val p = routes[l.id] ?: continue
            arr.add(JsonObject().apply {
                addProperty("id", l.id)
                addProperty("from", l.fromNode.toString() + "." + l.fromPin)
                addProperty("to", l.toNode.toString() + "." + l.toPin)
                // What the search did, not just where it ended up — see OrthogonalRouter.outcomes.
                OrthogonalRouter.outcomes[l.id]?.let { o ->
                    addProperty("how", o.how)
                    addProperty("obstacles", o.obstacles)
                    addProperty("grid", "${o.columns}x${o.rows}")
                    addProperty("room", "${o.roomLo.toInt()}..${o.roomHi.toInt()}")
                }
                add("points", JsonArray().also { pts ->
                    for (i in 0 until p.size / 2) {
                        pts.add(JsonObject().apply {
                            addProperty("x", p[i * 2]); addProperty("y", p[i * 2 + 1])
                        })
                    }
                })
            })
        }
        return JsonObject().apply {
            addProperty("type", TYPE)
            addProperty("ok", true)
            if (arr.size() == 0) addProperty("note", "nothing routed yet — open the Scripts panel so it draws once")
            add("wires", arr)
        }
    }

    private fun measure(): JsonObject {
        val rects = panel.measuredRects()
        val inset = panel.headingInsets()
        val arr = JsonArray()
        for (n in doc().nodes) {
            val r = rects[n.id] ?: continue
            arr.add(JsonObject().apply {
                addProperty("id", n.id)
                addProperty("type", n.type)
                addProperty("x", r.x); addProperty("y", r.y)
                addProperty("w", r.w); addProperty("h", r.h)
                // A container's heading band is a separate obstacle to the router — text a wire must not
                // cross — and it is not derivable from the rect, so reproducing a route outside the client
                // needs it reported. Absent on anything that is not a container.
                inset[n.id]?.let { addProperty("inset", it) }
            })
        }
        return JsonObject().apply {
            addProperty("type", TYPE)
            addProperty("ok", true)
            // Sizes exist only once the canvas has drawn. Say so rather than reporting an empty graph.
            if (arr.size() == 0) addProperty("note", "nothing measured yet — open the Scripts panel so it draws once")
            add("nodes", arr)
        }
    }

    private fun link(args: List<String>): JsonObject {
        if (args.size < 4) return fail("usage: graph link <fromNode> <fromPin> <toNode> <toPin>")
        val from = node(args, 0)
        val to = node(args, 2)
        // Looked up loosely, then stored by the spec's OWN name: `InCombat` on the wire, `In Combat` in
        // the document, because the argument list was split on spaces and a pin whose name has one would
        // otherwise be impossible to name at all.
        val fromSpec = descOf(from)?.outputLoosely(args[1])
            ?: return fail("node ${from.id} (${from.type}) has no output pin '${args[1]}'")
        val toSpec = descOf(to)?.inputLoosely(args[3])
            ?: return fail("node ${to.id} (${to.type}) has no input pin '${args[3]}'")
        val fromPin = fromSpec.name
        val toPin = toSpec.name
        // Refused here rather than left for the validator: a command that reports success and then produces
        // a red graph is worse than one that says no, because the caller has already moved on.
        val a = effective(from, fromSpec)
        val b = effective(to, toSpec)
        if (!dev.ziggle.vscript.model.canConnect(a, b)) {
            return fail("cannot connect $a → $b (${from.type}.$fromPin → ${to.type}.$toPin)")
        }
        val l = doc().addLink(from.id, fromPin, to.id, toPin, b)
        return ok("id" to l.id)
    }

    private fun unlink(args: List<String>): JsonObject {
        val id = args.getOrNull(0)?.toIntOrNull() ?: return fail("usage: graph unlink <linkId>")
        doc().removeLink(id)
        return ok("removed" to id)
    }

    /**
     * Set an input pin's literal.
     *
     * The value is the rest of the line rather than a token, because a string literal with a space in it is
     * an ordinary thing to want and splitting it would silently truncate.
     */
    /**
     * Typed text as the form the DOCUMENT stores.
     *
     * **The host's field transform first.** A structured type's stored spelling belongs to whatever
     * renders it — `"x,y,plane"`, `#AARRGGBB` — and asking the language instead is what made a tile typed
     * here land as a run-time record the canvas could not edit. Falls through to the language's own
     * literal rules for everything else, which is nearly everything.
     */
    private fun literalOf(type: dev.ziggle.vscript.model.TypeRef, text: String): Any? =
        dev.ziggle.vscript.editor.host.textFor(type)?.parse(text) ?: Literals.of(type, text, doc().enums)

    private fun setLiteral(rest: String): JsonObject {
        val parts = rest.split(' ', limit = 3)
        if (parts.size < 3) return fail("usage: graph set <nodeId> <pin> <value>")
        val n = node(parts, 0)
        val desc = descOf(n) ?: return fail("node ${n.id} has an unknown type '${n.type}'")
        val spec = desc.inputLoosely(parts[1]) ?: desc.outputLoosely(parts[1])
            ?: return fail("node ${n.id} (${n.type}) has no pin '${parts[1]}'")
        val pin = spec.name
        val type = effective(n, spec)
        val value = literalOf(type, parts[2])
        doc().setLiteral(n.id, pin, value)
        return ok("id" to n.id, "pin" to pin, "type" to type.name, "value" to value)
    }

    private fun clearLiteral(args: List<String>): JsonObject {
        val n = node(args, 0)
        val typed = args.getOrNull(1) ?: return fail("usage: graph clear <nodeId> <pin>")
        val desc = descOf(n)
        val pin = (desc?.inputLoosely(typed) ?: desc?.outputLoosely(typed))?.name ?: typed
        doc().edit("clear value") { n.literals.remove(pin) }
        return ok("id" to n.id, "pin" to pin)
    }

    private fun bind(args: List<String>): JsonObject {
        val n = node(args, 0)
        val name = args.getOrNull(1) ?: return fail("usage: graph bind <nodeId> <variable>")
        if (doc().variable(name) == null) return fail("no variable named '$name'")
        doc().setNodeVariable(n.id, name)
        return ok("id" to n.id, "variable" to name)
    }

    /** Declare or re-declare a variable: `graph var <name> <type> [default]`. */
    private fun variable(args: List<String>): JsonObject {
        val name = args.getOrNull(0) ?: return listVariables()
        val typeName = args.getOrNull(1) ?: return fail("usage: graph var <name> <type> [default]")
        // Resolved through the REGISTRY rather than the enum, so a name a document declared works here too
        // — and an unknown one is refused rather than quietly becoming a wildcard that connects to anything.
        val type = resolveType(typeName)
            ?: return fail("no type '$typeName' — one of ${Types.forVariables.joinToString(", ") { it.name }}")
        val default = args.drop(2).joinToString(" ").takeIf { it.isNotEmpty() }
            ?.let { literalOf(type, it) }
        if (doc().variable(name) == null) {
            doc().addVariable(GraphVariable(name, type, default))
        } else {
            doc().updateVariable(name, type, default)
        }
        return ok("name" to name, "type" to type.toString(), "default" to default)
    }

    /**
     * `graph use <alias> [ref]` — declare an import, or list them when given nothing.
     *
     * [ref] defaults to the alias, which is the common case: a document called `banking` imported as
     * `banking`. The reference resolves through the same [DocumentSource] the compiler uses, so it is
     * checked HERE rather than at run time — an import that names nothing is worth knowing about while
     * you are still typing it, not when the script will not start.
     */
    private fun useDocument(args: List<String>): JsonObject {
        val alias = args.getOrNull(0) ?: return listImports()
        if (alias.contains("::")) return fail("an alias is a plain name — '$alias' has a qualifier in it")
        val ref = args.drop(1).joinToString(" ").ifEmpty { alias }
        val found = panel.documents.load(dev.ziggle.vscript.model.GraphImport(alias, ref))
            ?: return fail("no document called '$ref' in ${EditorDoc.graphsDir()}")
        if (found.id == doc().id) return fail("a document cannot import itself")
        doc().addImport(alias, ref)
        return ok(
            "alias" to alias,
            "ref" to ref,
            "resolved" to found.name,
            "functions" to found.functions.size,
            "variables" to found.variables.size,
        )
    }

    private fun listImports(): JsonObject {
        val arr = JsonArray()
        for (i in doc().imports) {
            val target = panel.documents.load(i)
            arr.add(JsonObject().apply {
                addProperty("alias", i.alias)
                addProperty("ref", i.ref)
                addProperty("resolved", target != null)
                // What the alias actually offers, so `graph uses` answers "what can I call?" and not only
                // "what did I write?".
                if (target != null) {
                    add("functions", JsonArray().also { a -> target.functions.forEach { f -> a.add(f.name) } })
                    add("variables", JsonArray().also { a -> target.variables.forEach { v -> a.add(v.name) } })
                    add("types", JsonArray().also { a -> target.types.forEach { t -> a.add(t.name) } })
                }
            })
        }
        return JsonObject().apply {
            addProperty("type", TYPE)
            addProperty("ok", true)
            addProperty("count", arr.size())
            add("imports", arr)
        }
    }

    private fun listVariables(): JsonObject {
        val arr = JsonArray()
        for (v in doc().variables) {
            arr.add(JsonObject().apply {
                addProperty("name", v.name)
                addProperty("type", v.type.name)
                addProperty("default", v.default?.toString())
            })
        }
        return JsonObject().apply {
            addProperty("type", TYPE)
            addProperty("ok", true)
            add("variables", arr)
        }
    }

    // ---- user functions ----------------------------------------------------------------------------

    private fun newFunction(args: List<String>): JsonObject {
        val name = args.getOrNull(0)?.trim().orEmpty().ifEmpty { doc().freeFunctionName() }
        if (doc().function(name) != null) return fail("there is already a function called '$name'")
        val x = args.getOrNull(1)?.toFloatOrNull() ?: 120f
        val y = args.getOrNull(2)?.toFloatOrNull() ?: 520f
        doc().addFunction(name, x, y)
        // The BOX, not merely the last node named for the function — that was the Outputs node, so `fold`
        // on the id this reply gave you folded nothing.
        val box = doc().nodes.last { it.function == name && it.type == BuiltinNodes.FUNCTION }
        return ok("name" to name, "nodes" to doc().nodes.filter { it.function == name }.map { it.id }, "box" to box.id)
    }

    private fun listFunctions(): JsonObject {
        val arr = JsonArray()
        for (f in doc().functions) {
            arr.add(JsonObject().apply {
                addProperty("name", f.name)
                add("params", pinList(f.params))
                add("results", pinList(f.results))
                addProperty("body", doc().nodes.count { it.function == f.name })
                addProperty("calls", doc().nodes.count { it.callee == f.name })
            })
        }
        return JsonObject().apply {
            addProperty("type", TYPE)
            addProperty("ok", true)
            add("functions", arr)
        }
    }

    private fun pinList(pins: List<FunctionPin>): JsonArray = JsonArray().apply {
        pins.forEach { p -> add(JsonObject().apply { addProperty("name", p.name); addProperty("type", p.type.name) }) }
    }

    /**
     * `type <name> [field:TYPE,…]` — declare a record, or set its fields.
     *
     * With no fields it declares an empty one, which is what the outline's + button does; with them it
     * replaces the lot, the same all-or-nothing [signature] takes. Two commands would have meant an
     * add-a-field verb and a remove-a-field verb and a rename verb, and the whole list is one short line.
     */
    private fun declaredType(rest: String): JsonObject {
        val name = rest.trim().substringBefore(' ').trim()
        if (name.isEmpty()) return listTypes()
        if (Types.of(name) != null) {
            return fail("'$name' is a built-in type — pick a name the client does not already use")
        }
        val t = doc().addStruct(name)
        val spec = rest.trim().substringAfter(' ', "").trim()
        if (spec.isEmpty()) return ok("name" to t.name, "fields" to t.fields.map { "${it.name}:${it.type}" })
        val fields = ArrayList<FunctionPin>()
        for (piece in spec.split(',', ' ').filter { it.isNotBlank() }) {
            val field = piece.substringBefore(':').trim()
            if (field.isEmpty()) return fail("a field needs a name — write it as name:TYPE")
            val typeName = piece.substringAfter(':', "WILDCARD").trim()
            val type = resolveType(typeName) ?: return fail("no type '$typeName'")
            fields += FunctionPin(field, type)
        }
        doc().updateStruct(t.name, fields)
        return ok("name" to t.name, "fields" to fields.map { "${it.name}:${it.type}" })
    }

    private fun listTypes(): JsonObject {
        val arr = JsonArray()
        doc().types.forEach { t ->
            arr.add(JsonObject().apply {
                addProperty("name", t.name)
                add("fields", JsonArray().apply { t.fields.forEach { add("${it.name}:${it.type}") } })
                addProperty(
                    "uses",
                    doc().nodes.count { it.literals[BuiltinNodes.STRUCT_OF] == t.name } +
                        doc().variables.count { it.type.name == t.name },
                )
            })
        }
        return ok("types" to arr)
    }

    /**
     * `tune` lists the routing and layout numbers; `tune <name> <value>` changes one.
     *
     * Live, and not persisted. Every one of these was chosen by looking at a real graph and deciding it
     * read better, which is the only way any of them can be chosen — so the loop that matters is
     * look, change, look again, and recompiling in the middle of it makes that slow and second-hand.
     */
    private fun tune(rest: String): JsonObject {
        val words = rest.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) {
            val arr = JsonArray()
            dev.ziggle.vscript.editor.graph.Tuning.all.forEach { k ->
                arr.add(JsonObject().apply {
                    addProperty("name", k.name)
                    addProperty("value", k.get())
                    addProperty("about", k.about)
                })
            }
            return ok("knobs" to arr)
        }
        val name = words[0]
        val knob = dev.ziggle.vscript.editor.graph.Tuning.of(name)
            ?: return fail("no knob '$name' — try 'graph tune' for the list")
        if (words.size == 1) return ok("name" to knob.name, "value" to knob.get(), "about" to knob.about)
        val v = words[1].toDoubleOrNull() ?: return fail("'${words[1]}' is not a number")
        val was = knob.get()
        dev.ziggle.vscript.editor.graph.Tuning.set(knob.name, v)
        return ok("name" to knob.name, "was" to was, "now" to knob.get(), "about" to knob.about)
    }

    /** `sig <name> in a:INT,b:ITEM out ok:BOOL` — either side may be omitted to clear it. */
    private fun signature(rest: String): JsonObject {
        val words = rest.split(' ').filter { it.isNotEmpty() }
        val name = words.firstOrNull() ?: return fail("usage: graph sig <name> in <pin:TYPE,…> out <pin:TYPE,…>")
        val fn = doc().function(name) ?: return fail("no function named '$name'")
        var side: String? = null
        val ins = ArrayList<FunctionPin>()
        val outs = ArrayList<FunctionPin>()
        for (w in words.drop(1)) {
            when (w.lowercase()) {
                "in", "->" -> { side = "in"; continue }
                "out" -> { side = "out"; continue }
            }
            val target = when (side) {
                "in" -> ins
                "out" -> outs
                else -> return fail("say 'in' or 'out' before the pins")
            }
            for (piece in w.split(',').filter { it.isNotBlank() }) {
                val pin = piece.substringBefore(':').trim()
                val typeName = piece.substringAfter(':', "WILDCARD").trim()
                val type = resolveType(typeName) ?: return fail("no type '$typeName'")
                if (pin.isEmpty()) return fail("a pin needs a name — write it as name:TYPE")
                target += FunctionPin(pin, type)
            }
        }
        doc().updateFunction(fn.name, ins, outs)
        return ok("name" to fn.name, "params" to ins.map { "${it.name}:${it.type}" }, "results" to outs.map { "${it.name}:${it.type}" })
    }

    /**
     * `graph note <nodeId> <heading> [| note]` — label a comment box, and optionally say why it is there.
     *
     * The one thing a graph built over this socket could not do. Every other part of organising a script is
     * here — add, move, size, arrange, group into a function — but the boxes came out blank, so a document
     * assembled remotely was laid out correctly and explained nothing. Naming a region is most of what makes
     * a large graph readable, and doing it by hand-editing the saved JSON (which is what this replaced) also
     * meant closing the document first.
     *
     * A `|` splits heading from note, because a heading is a few words and a note is a sentence, and asking
     * for two quoted arguments over a socket that has already been through a shell is asking for trouble.
     *
     * On a FUNCTION box the heading is the function's NAME, so this renames it and every node that refers to
     * it — see [EditorDoc.renameContainer]. A function has no note, so that half is ignored there.
     */
    private fun note(rest: String): JsonObject {
        val parts = rest.trim().split(' ', limit = 2)
        val n = node(parts, 0)
        val d = descOf(n) ?: return fail("node ${n.id} has an unknown type '${n.type}'")
        if (!d.kind.isContainer) {
            return fail("node ${n.id} is a ${d.title}, not a box — only a comment or a function box has a heading")
        }
        val text = parts.getOrNull(1).orEmpty()
        val heading = text.substringBefore('|').trim()
        val bodyText = if ('|' in text) text.substringAfter('|').trim() else null
        if (heading.isNotEmpty()) doc().renameContainer(n.id, heading)
        if (bodyText != null) doc().setContainerBody(n.id, bodyText)
        val after = doc().node(n.id)
        return ok(
            "id" to n.id,
            "heading" to (after?.comment ?: after?.function),
            "note" to after?.body,
        )
    }

    private fun body(args: List<String>): JsonObject {
        val n = node(args, 0)
        val name = args.getOrNull(1) ?: return fail("usage: graph body <nodeId> <function|none>")
        if (name.equals("none", true)) {
            doc().setNodeFunction(n.id, null)
            return ok("id" to n.id, "function" to null)
        }
        if (doc().function(name) == null) return fail("no function named '$name'")
        doc().setNodeFunction(n.id, name)
        return ok("id" to n.id, "function" to name)
    }

    private fun callee(args: List<String>): JsonObject {
        val n = node(args, 0)
        val name = args.getOrNull(1) ?: return fail("usage: graph callee <nodeId> <function>")
        if (doc().function(name) == null) return fail("no function named '$name'")
        doc().setNodeCallee(n.id, name)
        return ok("id" to n.id, "callee" to name)
    }

    private fun fold(args: List<String>): JsonObject {
        val n = node(args, 0)
        val on = when (args.getOrNull(1)?.lowercase()) {
            null -> !n.folded
            "on", "true", "1" -> true
            else -> false
        }
        doc().setFolded(n.id, on)
        return ok("id" to n.id, "folded" to on)
    }

    // ---- running -----------------------------------------------------------------------------------

    /**
     * `check` validates the open script; `check <file>` reads a `.vs` file instead and changes nothing.
     *
     * One verb rather than two, split on whether a file was named. The question is the same one — "would this
     * run?" — and the answer differs only in whether the thing being asked about is already open. A second
     * verb would have to be called something like `checktext`, which is a worse name for the same question.
     */
    /**
     * `graph sleep [--reason <text>] [--within <ms>] [--force]` — ask the script to hand over.
     *
     * Returns as soon as the flag is up. It is a REQUEST: the script leaves its loop where it chose to,
     * then its On Sleep handlers run, and only then is it asleep. Poll `graph state` for `phase`.
     *
     * `--force` skips the asking and takes the account back now. It exists because the forced path is the
     * one nobody exercises by accident, and it is the path that decides whether the state file gets
     * written at all — so it needs to be one flag away rather than a wait of two minutes.
     */
    private fun sleep(args: List<String>): JsonObject {
        val reason = valueAfter(args, "--reason").orEmpty()
        val within = valueAfter(args, "--within")?.toLongOrNull()
        if (FORCE in args) {
            if (!panel.runtime.requestSleep(reason.ifBlank { "forced" }, withinMs = 0L)) {
                return fail("nothing is running to put to sleep")
            }
            return ok("phase" to panel.runtime.phase.name.lowercase(), "forced" to true)
        }
        if (!panel.runtime.requestSleep(reason, within ?: ScriptRuntime.QUIESCE_MS)) {
            return fail("nothing is running to put to sleep")
        }
        return ok(
            "phase" to panel.runtime.phase.name.lowercase(),
            "withinMs" to (within ?: ScriptRuntime.QUIESCE_MS),
        )
    }

    /** The value after [flag], or null — `--reason "between rounds"`. */
    private fun valueAfter(args: List<String>, flag: String): String? =
        args.indexOf(flag).takeIf { it >= 0 && it + 1 < args.size }?.let { args[it + 1] }

    private fun check(args: List<String>): JsonObject {
        args.firstOrNull { !it.startsWith("--") }?.let { return checkText(it) }
        val issues = panel.runtime.validate(doc())
        return JsonObject().apply {
            addProperty("type", TYPE)
            addProperty("ok", issues.none { it.severity == Severity.ERROR })
            addProperty("errors", issues.count { it.severity == Severity.ERROR })
            addProperty("warnings", issues.count { it.severity == Severity.WARNING })
            add("issues", issuesJson())
        }
    }

    /**
     * The dry run: parse, lower and validate a file, install nothing.
     *
     * Deliberately usable with no document open — it needs the catalogue, not a canvas — so a file can be
     * checked before there is anywhere to put it.
     */
    /**
     * Would this file run? Asked of the front end that would run it.
     *
     * Through the TEXT compiler, because that is what a `.vs` compiles with — checking it by lowering it
     * to a graph would answer a question about a document nobody is going to execute, and the two
     * increasingly disagree.
     */
    private fun checkText(name: String): JsonObject {
        val file = textFile(name)
        if (!file.isFile) return fail("no text script at ${file.absolutePath}")
        // Before reading it: the document's own imports resolve against whatever roots the source has at
        // that moment.
        panel.importRoot = scriptsRootOf(file)
        val script = panel.compileText(file, file.readText())
        val compiled = script.compiled
        return JsonObject().apply {
            addProperty("type", TYPE)
            addProperty("ok", compiled.ok)
            addProperty("file", file.absolutePath)
            addProperty("errors", compiled.errors.size)
            addProperty("warnings", compiled.warnings.size)
            if (compiled.ok) {
                addProperty("sites", script.sites.size)
                // What it would actually run, by kind — a script that compiles to nothing is a real
                // outcome and a silent one, and this is where it stops being silent.
                add("handlers", JsonObject().apply {
                    for ((kind, entries) in compiled.entries) {
                        addProperty(kind.name.lowercase(), entries.size)
                    }
                })
            }
            add("issues", textDiagnostics(compiled.diagnostics))
        }
    }

    private fun failWithIssues(message: String): JsonObject = JsonObject().apply {
        addProperty("type", TYPE)
        addProperty("ok", false)
        addProperty("error", message)
        add("issues", issuesJson())
    }

    private fun issuesJson(): JsonArray {
        val arr = JsonArray()
        for (i in panel.runtime.issues) {
            arr.add(JsonObject().apply {
                addProperty("severity", i.severity.name)
                addProperty("message", i.message)
                i.nodeId?.let { addProperty("node", it) }
                i.linkId?.let { addProperty("link", it) }
                i.pin?.let { addProperty("pin", it) }
            })
        }
        return arr
    }

    /**
     * `graph call <fn> [arg…]` — run one function on its own.
     *
     * Arguments are parsed against the function's declared parameter types, so `graph call Steal 1234` puts
     * an Int in an Int pin and a tile string in a Tile pin without anyone saying which is which.
     *
     * Returns as soon as the fiber exists rather than waiting for it: a function that walks somewhere takes
     * minutes, and a debug command that blocks the client thread for minutes is worse than useless. Read
     * the outcome from `graph state` — the fiber reports `DONE` with its `result`, or `FAILED` with where.
     */
    private fun call(args: List<String>): JsonObject {
        val name = args.firstOrNull() ?: return fail("usage: graph call <function> [arg…]")
        val fn = doc().function(name)
            ?: return fail("no function named '$name' — try 'graph fns'")
        val given = args.drop(1)
        if (given.size != fn.params.size) {
            val sig = fn.params.joinToString { "${it.name}: ${it.type}" }.ifEmpty { "no arguments" }
            return fail("'$name' takes ${fn.params.size} argument(s) — $sig")
        }
        val values = fn.params.mapIndexed { i, p -> literalOf(p.type, given[i]) }
        val err = panel.runtime.runFunction(doc(), name, values)
        if (err != null) return fail(err)
        return ok(
            "called" to name,
            "args" to values.map { it?.toString() },
            "note" to "read the outcome from 'graph state'",
        )
    }

    private fun state(): JsonObject {
        val d = panel.doc
        val contexts = JsonArray()
        for (c in panel.dbg.contexts()) {
            contexts.add(JsonObject().apply {
                addProperty("id", c.id)
                addProperty("name", c.name)
                addProperty("entry", c.entryNodeId)
                addProperty("state", c.state.name)
                addProperty("at", c.nodeId)
                if (c.pauseReason != dev.ziggle.vscript.vm.PauseReason.NONE) {
                    addProperty("pausedBy", c.pauseReason.name)
                }
                c.error?.let { addProperty("error", it) }
                // How much longer it means to sleep — see [DebugSession.Context.sleepingForMs]. A parked
                // fiber with no duration beside it is a symptom nobody can act on.
                if (c.sleepingForMs >= 0) addProperty("sleepingForMs", c.sleepingForMs)
                // What a `graph call` produced. Omitted when there is nothing, so an ordinary run's state
                // reads the same as it always did.
                if (c.result.isNotEmpty()) add("result", jsonOf(c.result.map { v -> v?.toString() }))
            })
        }
        return JsonObject().apply {
            addProperty("type", TYPE)
            addProperty("ok", true)
            addProperty("open", d?.name)
            addProperty("dirty", d?.dirty ?: false)
            addProperty("nodes", d?.nodes?.size ?: 0)
            addProperty("running", panel.runtime.isRunning)
            addProperty("paused", panel.dbg.isPaused)
            // Additive, so anything already reading this keeps working. `running` deliberately keeps its
            // old meaning — fibers are executing — which stays true right through a handoff.
            addProperty("phase", panel.runtime.phase.name.lowercase())
            addProperty("asleep", panel.runtime.isAsleep)
            addProperty("sleepRequested", panel.runtime.runPhase.sleepRequested)
            if (panel.runtime.sleepReason.isNotBlank()) {
                addProperty("sleepReason", panel.runtime.sleepReason)
            }
            if (panel.runtime.isAsleep) {
                addProperty("sleptCleanly", panel.runtime.sleptCleanly)
            }
            panel.runtime.lastError?.let { addProperty("lastError", it) }
            add("contexts", contexts)
        }
    }

    /** The console, newest last — the same records the editor's console shows. */
    private fun log(limit: Int): JsonObject {
        val records = panel.runtime.log.records
        val arr = JsonArray()
        for (r in records.takeLast(limit.coerceIn(1, 500))) {
            arr.add(JsonObject().apply {
                addProperty("seq", r.seq)
                addProperty("level", r.level.name)
                addProperty("node", r.nodeId)
                if (r.activation > 0) addProperty("activation", r.activation)
                if (r.repeats > 1) addProperty("repeats", r.repeats)
                addProperty("message", r.message)
            })
        }
        return JsonObject().apply {
            addProperty("type", TYPE)
            addProperty("ok", true)
            addProperty("total", records.size)
            add("records", arr)
        }
    }

    // ---- debugging ---------------------------------------------------------------------------------

    private fun breakpointIds(): List<Int> = panel.runtime.breakpoints.entries().map { it.first }

    private fun step(mode: String): JsonObject {
        when (mode) {
            "over" -> panel.dbg.stepOver()
            "into" -> panel.dbg.stepInto()
            "out" -> panel.dbg.stepOut()
            "data" -> panel.dbg.stepIntoData()
            else -> return fail("usage: graph step [over|into|out|data]")
        }
        return ok("stepped" to mode)
    }

    private fun stack(contextId: Int?): JsonObject {
        val id = contextId ?: panel.dbg.focused()?.id ?: return fail("nothing is running")
        val arr = JsonArray()
        for (f in panel.dbg.stackTrace(id)) {
            arr.add(JsonObject().apply {
                addProperty("index", f.index)
                addProperty("chunk", f.chunkName)
                addProperty("pc", f.pc)
                addProperty("node", f.nodeId)
                // What the frame is, in the reader's own terms — the debugger's model deliberately carries
                // ids rather than presentation, so this is where they become legible. A canvas frame reads
                // as "Walk To" far better than as "node 7"; a text frame reads as a line and a file, and
                // the canvas node that happens to share its number describes something else entirely.
                val text = panel.textScript
                if (text != null) {
                    text.sites.spanOf(f.nodeId)?.let { addProperty("line", it.line) }
                    text.sites.documentOf(f.nodeId)?.let { ref ->
                        addProperty("document", ref)
                        // A frame in a LIBRARY is the case this exists for: stepping into one should open
                        // that file, not point at whatever is on the same line of the file on screen.
                        fileOf(text, ref)?.let { addProperty("file", it.absolutePath) }
                    }
                } else {
                    doc().node(f.nodeId)?.let { n -> descOf(n)?.let { addProperty("title", it.title) } }
                }
                addProperty("activation", f.activation)
            })
        }
        return JsonObject().apply {
            addProperty("type", TYPE)
            addProperty("ok", true)
            addProperty("context", id)
            add("frames", arr)
        }
    }

    private fun vars(contextId: Int?): JsonObject {
        val id = contextId ?: panel.dbg.focused()?.id ?: return fail("nothing is running")
        val scopes = JsonArray()
        for (s in panel.dbg.scopes(id)) {
            val vars = JsonArray()
            for (v in s.variables) {
                vars.add(JsonObject().apply {
                    addProperty("name", v.name)
                    addProperty("value", v.display)
                    addProperty("type", v.typeName)
                    if (v.nodeId >= 0) addProperty("node", v.nodeId)
                })
            }
            scopes.add(JsonObject().apply {
                addProperty("name", s.name)
                add("variables", vars)
            })
        }
        return JsonObject().apply {
            addProperty("type", TYPE)
            addProperty("ok", true)
            addProperty("context", id)
            add("scopes", scopes)
        }
    }

    // ---- shared ------------------------------------------------------------------------------------

    private fun doc(): EditorDoc = panel.doc ?: throw NoDocument()

    /**
     * [node]'s descriptor with its signature resolved.
     *
     * Never `catalog[node.type]` directly: a Call node's pins come from the function it names, so the raw
     * catalog entry has only its exec pair. Reading that instead is exactly how `link` came to report "has
     * no output pin 'X'" for a pin sitting right there on the node.
     */
    private fun descOf(node: Node): NodeDescriptor? =
        panel.catalog[node.type]?.let {
            resolveNode(
                node, it, { name -> doc().function(name) }, { doc().types },
                dev.ziggle.vscript.model.expressionCalls(panel.catalog, doc().nodes, doc().links, doc()::function),
                { doc().enums },
            )
        }

    private fun node(args: List<String>, index: Int): Node {
        val id = args.getOrNull(index)?.toIntOrNull() ?: throw Refused("expected a node id")
        return doc().node(id) ?: throw Refused("no node $id in '${doc().name}'")
    }

    /**
     * An authoring site to arm a breakpoint on — a canvas node, or a place in a `.vs`.
     *
     * The VM has one id space and does not know which front end minted a number; this is the one place
     * that has to. Checked against the open graph for a canvas document, so a typo is still refused, and
     * against the text script's own table otherwise — where `doc().node(id)` would refuse every real site,
     * because those ids index the source rather than the graph it was drawn as.
     */
    /**
     * Which file a document reference names — the root's own file, or a library's, resolved as the
     * compiler resolved it.
     *
     * `<root>` is the script that was imported and is not looked up: it has no import reference, being
     * the thing doing the importing.
     */
    private fun fileOf(text: ScriptsPanel.TextScript, ref: String): java.io.File? =
        if (ref == dev.ziggle.vscript.text.Resolution.ROOT) text.file else panel.documents.fileOf(ref)

    /**
     * Every document reference of a run resolved to a file, ONCE.
     *
     * **Resolving per row is what froze the client.** `DocumentSource.fileOf` re-scans the script folders
     * to answer, which is right for a lookup and catastrophic for nine thousand of them: `graph spans` on
     * `rotation` has a row per authoring site, and asking per row turned one command into thousands of
     * directory walks on the client thread — five seconds, a stalled game, and every later command
     * failing with "the client thread did not run the command within 5s".
     *
     * A run's documents do not change while it is loaded, so one pass over the distinct references is all
     * this ever needed.
     */
    private fun filesOf(text: ScriptsPanel.TextScript, refs: Collection<String>): Map<String, java.io.File> {
        val out = HashMap<String, java.io.File>()
        for (ref in refs.distinct()) fileOf(text, ref)?.let { out[ref] = it }
        return out
    }

    private fun site(args: List<String>, index: Int): Int {
        val id = args.getOrNull(index)?.toIntOrNull() ?: throw Refused("expected an authoring site id")
        val text = panel.textScript ?: return node(args, index).id
        if (text.sites.spanOf(id) == null) throw Refused("no site $id in '${text.name}' — 'graph spans' lists them")
        return id
    }

    /**
     * A type by name, as an author would write it: a built-in's name, or one the document declared.
     *
     * Refuses rather than widening. Reading an unknown name as a wildcard is what the enum-based parse did
     * by accident, and a wildcard connects to anything — so a typo produced a graph that compiled and
     * quietly meant something else.
     */
    private fun resolveType(name: String): dev.ziggle.vscript.model.TypeRef? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        // A parameterised list — `List<Item>` — in the same persisted spelling a document uses. Handled
        // first and by hand: the registry lists "List" as one name and cannot know what it holds, so
        // without this the only way to make a list variable of anything was the sidebar's picker.
        val open = clean.indexOf('<')
        if (open > 0 && clean.endsWith(">")) {
            val outer = resolveType(clean.substring(0, open)) ?: return null
            if (!outer.isList) return outer
            val inner = resolveType(clean.substring(open + 1, clean.length - 1)) ?: return null
            return dev.ziggle.vscript.model.TypeRef.list(inner)
        }
        // The DOCUMENT first, then the host — the same order names have always resolved in here.
        doc().struct(clean)?.let { return dev.ziggle.vscript.model.TypeRef.named(it.name) }
        return Types.of(clean)?.type
            ?: runCatching { PinType.valueOf(clean.uppercase()) }.getOrNull()
                ?.let { dev.ziggle.vscript.model.TypeRef(it) }
    }

    private fun effective(node: Node, spec: dev.ziggle.vscript.model.PinSpec): dev.ziggle.vscript.model.TypeRef =
        effectivePinType(node, spec) { name -> doc().variable(name)?.type }

    /**
     * A successful reply.
     *
     * The envelope is written **after** the payload so it always wins. Written first, a payload field named
     * `type` overwrote it — `graph add movement.walkTo` replied `{"type":"movement.walkTo"}`, which reads as a
     * different kind of message entirely to anything routing on that field.
     */
    private fun ok(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject().apply {
        for ((k, v) in pairs) add(k, jsonOf(v))
        addProperty("type", TYPE)
        addProperty("ok", true)
    }

    private fun jsonOf(v: Any?): com.google.gson.JsonElement = when (v) {
        null -> com.google.gson.JsonNull.INSTANCE
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is List<*> -> JsonArray().also { arr -> v.forEach { arr.add(jsonOf(it)) } }
        // Already JSON. Without this it fell to the branch below and went out as its own toString — a
        // string full of escaped quotes that every caller then had to parse a second time.
        is com.google.gson.JsonElement -> v
        else -> JsonPrimitive(v.toString())
    }

    private fun fail(message: String): JsonObject = JsonObject().apply {
        addProperty("type", TYPE)
        addProperty("ok", false)
        addProperty("error", message)
    }

    /** A command that needs a document when none is open. Distinct so the message can say what to do. */
    private class NoDocument : RuntimeException("no script is open — 'graph new <name>' or 'graph open <name>'")

    /** A command that could not be carried out. The message is the whole answer. */
    private class Refused(message: String) : RuntimeException(message)

    private companion object {
        const val TYPE = "graph"
        const val TIMEOUT_SECONDS = 5L

        /** Import over unsaved edits anyway, and `graph sleep --force` — take the account back now. */
        const val FORCE = "--force"

        /** Build without the debugging apparatus — no breakpoints, no live editing, smaller code. */
        const val RELEASE = "--release"


        /**
         * Run an imported `.vs` through the GRAPH front end instead of the text one.
         *
         * An escape hatch, not a mode: the two compilers are meant to agree, and the way to find out that
         * they do not is to run the same file both ways rather than to reason about it.
         */
        const val GRAPH = "--graph"

        val HELP: JsonObject = JsonObject().apply {
            addProperty("type", TYPE)
            addProperty("ok", true)
            add("commands", JsonArray().also { a ->
                listOf(
                    "list                                  scripts on disk, and which is open",
                    "new <name>                            start a new script",
                    "open <name>                           open one",
                    "save                                  write it back to disk",
                    "rename <name>                         rename the open script and its file",
                    "show                                  the open script as its document JSON",
                    "text                                  the open script as SOURCE, in the reply",
                    "export [file]                         write that source to a .vs file",
                    "import <file> [--force]               parse a .vs file and make it the open script",
                    "spans [file]                          authoring site -> line + file, the debugger's bridge;\n                                      spans every document of the run, imports included",
                    "nodes [filter]                        the catalogue: every node type and its pins",
                    "add <type> [x] [y]                    add a node, returns its id",
                    "rm <nodeId…>                          delete nodes and their links",
                    "move <nodeId> <x> <y>                 reposition a node",
                    "size <nodeId> <w> <h>                 resize a comment or function box",
                    "arrange                               auto-layout, the same as the toolbar button",
                    "measure                               every node's MEASURED rectangle, as last drawn",
                    "wires                                 every wire's routed waypoints, as last drawn",
                    "link <from> <fromPin> <to> <toPin>    connect two pins",
                    "unlink <linkId>                       remove a link",
                    "set <nodeId> <pin> <value>            type a literal into an unconnected pin",
                    "setvar <name> <value>                 write a graph variable in the RUNNING graph",
                    "clear <nodeId> <pin>                  remove a literal",
                    "bind <nodeId> <variable>              point a Get/Set node at a variable",
                    "var [name] [type] [default]           declare a variable, or list them",
                    "rmvar <name>                          remove a variable",
                    "use [alias] [document]                import another document, or list imports",
                    "rmuse <alias>                         drop an import",
                    "uses                                  what each alias offers: functions, variables, types",
                    "undo / redo                           step the edit history",
                    "fn [name] [x] [y]                     new function: a box, an Inputs and an Outputs node",
                    "fns                                   every function, with its signature and use count",
                    "sig <name> in a:INT,b:ITEM out ok:BOOL  set a signature (either side may be omitted)",
                    "type <name> [f:TYPE,…]                declare a record type, or set its fields",
                    "types                                 every declared type, with its fields and use count",
                    "rmtype <name>                         delete one",
                    "tune [name] [value]                   the routing and layout numbers, live",
                    "rmfn <name>                           delete a function and its body",
                    "note <nodeId> <heading> [| note]      label a comment or function box",
                    "body <nodeId> <function|none>         put a node in a body, or take it out",
                    "callee <nodeId> <function>            point a Call node at a function",
                    "fold <nodeId> [on|off]                fold a box down to its title bar",
                    "check [file]                          validate the open script, or a .vs file",
                    "run [--release] [--graph] / stop      start or kill it; a .vs runs through the text compiler,\n                                      --graph forces the old lowering path",
                    "sleep [--reason t] [--within ms] [--force]  ask it to finish up and hand over",
                    "wake                                  start an asleep graph again",
                    "call <function> [arg…]                run ONE function on its own; read the result " +
                        "from 'state'",
                    "state                                 what is open, running, paused, and where",
                    "log [n]                               the console, newest last",
                    "break <nodeId> / unbreak <nodeId>     arm or clear a breakpoint",
                    "breaks                                armed breakpoints",
                    "step [over|into|out|data]             advance the paused context",
                    "continue / pause                      release or halt",
                    "stack [contextId]                     the call stack at the pause",
                    "vars [contextId]                      variables and node outputs at the pause",
                ).forEach(a::add)
            })
        }
    }
}

/**
 * Where the debug server finds the editor.
 *
 * A single volatile field rather than a service-registry publication: there is exactly one node editor per
 * client, it is chrome furniture with the lifetime of the window, and a registry lookup would add scoping
 * and cleanup semantics to something that has neither. Null until the chrome builds the panel, and the
 * command surface says so rather than pretending the editor is simply empty.
 */
object VScriptHost {
    @Volatile
    var control: VScriptControl? = null

    fun command(line: String): String =
        control?.command(line)
            ?: """{"type":"graph","ok":false,"error":"the node editor is not running"}"""
}
