package dev.ziggle.vscript.editor

import dev.ziggle.vscript.runtime.DocumentSource
import dev.ziggle.vscript.runtime.EditorDoc
import dev.ziggle.vscript.editor.graph.OutlinePanel
import dev.ziggle.vscript.editor.graph.Tuning
import dev.ziggle.vscript.editor.graph.TuningWindow
import dev.ziggle.vscript.editor.VScriptControl
import dev.ziggle.vscript.editor.VScriptHost
import dev.ziggle.vscript.editor.graph.WireRouting
import dev.ziggle.vscript.editor.text.CodeBuffer
import dev.ziggle.vscript.editor.text.CodeView
import dev.ziggle.vscript.runtime.BreakpointStore
import dev.ziggle.vscript.runtime.ScriptRuntime
import dev.ziggle.vscript.runview.DebugPanel
import dev.ziggle.vscript.shell.DetachedEditorWindow
import imgui.ImGui
import imgui.flag.ImGuiHoveredFlags
import imgui.flag.ImGuiInputTextFlags
import imgui.flag.ImGuiMouseButton
import imgui.type.ImString
import dev.ziggle.imgui.DrawKit
import dev.ziggle.imgui.Fonts
import dev.ziggle.imgui.Theme
import dev.ziggle.vscript.runtime.EditorLog
import dev.ziggle.vscript.compile.Severity
import dev.ziggle.vscript.runtime.DebugSession
import dev.ziggle.vscript.editor.graph.CanvasRenderer
import dev.ziggle.vscript.editor.graph.OwnCanvas
import dev.ziggle.vscript.editor.graph.ValuePicker
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.text.natives
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.vm.FiberState
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import java.io.File
import dev.ziggle.imgui.EditorKeyboard
import dev.ziggle.vscript.editor.graph.EditorSettings

/**
 * **On the missing `internal`s.** This class had twenty-one of them, and they were right for as long as
 * "module" meant "the client": the debug server's control vocabulary and the client's own panels are
 * module-mates of the editor, so `internal` said "the things that drive this panel" and meant it.
 *
 * `:vscript-shell` is its own module now, and every consumer is outside it — the client, the debug server,
 * a standalone shell, anything that embeds the editor. `internal` there means "nobody", which is not what
 * any of these are. So they are public: opening a document, loading a graph, starting a run, arming a
 * breakpoint and reading the current one ARE the workbench's control surface, and the module boundary is
 * what made that explicit rather than incidental.
 *
 * Deliberately the same handles the UI uses. A second, parallel surface for "run the graph" would be a
 * second thing to keep correct.
 */
/**
 * The Scripts rail panel: a toolbar, the node canvas and a problems strip.
 *
 * Lives in the chrome rather than on a plugin because a node IDE is editor furniture — it should be there
 * whether or not any particular plugin is enabled, the same as the Plugins and Logs panels.
 *
 * There is no document list pane. A permanent 190px column spent on a handful of filenames is a lot of
 * canvas to give up for something you touch when you switch scripts and never otherwise; the breadcrumb's
 * dropdown does the same job in the space it already occupied, and naming/renaming live in the file menu.
 */
class ScriptsPanel(
    /**
     * The domain being edited — its verbs, its file access, its values, its lifecycle.
     *
     * **Supplied, not reached for.** This class named `GameNodes`, `ScriptFiles`, `GameValueOut`,
     * `GameRunLifecycle` and `CatalogDump` directly, which made the workbench the last thing in the editor
     * that knew what game it was editing — and therefore the last thing that could not leave this repo.
     *
     * [ScriptDomain.NONE] is a real editor and not a stub: it opens, parses, validates and round-trips a
     * document with an empty catalogue. That is what a headless test wants, and it is what makes the
     * standalone shell possible.
     */
    private val domain: ScriptDomain = ScriptDomain.NONE,
) {
    /** The hand-authored game verbs: descriptors for the editor, host functions for the VM. */
    //
    // **`GameNodes.library` DIRECTLY, not a second library built from its `defs`.** It was rebuilt here
    // with `defs` and `enums` copied across and `records` silently left behind, and the failure that
    // caused is worth stating because nothing about it points here: a host record registers itself into
    // the global `HostRecords` when ANY library is constructed, so `itm.name` typechecked and compiled
    // perfectly — while `install()` iterates the constructor's OWN `records` list to bind the accessors,
    // and that list was empty. The result is a script that validates and then dies at load with
    // `unknown host function 'ItemRef.name'`, pointing at the document that reads the field.
    //
    // Three arguments that must agree is two too many to copy by hand. `GameNodes.library` already
    // assembles all of them, so taking the whole thing means a fourth kind of contribution arrives here
    // without anybody remembering to forward it.
    private val library = domain.library()

    // **Public, not internal, and the module split is what settled it.** `internal` was enough while the
    // whole editor and its host were one module: [VScriptControl] drives this panel from the debug server
    // and needed the same handles the UI uses, and an embedder happened to be a module-mate. It is not any
    // more — a host that shows this panel legitimately wants to read a variable out of the run, and
    // `internal` now means "nobody outside this module", which excludes every embedder there will ever be.
    //
    // Deliberately the SAME handles the UI uses. A second, parallel surface for "run the graph" would be a
    // second thing to keep correct.
    val catalog = NodeCatalog(library.descriptors)

    /**
     * The flag `sleepRequested()` answers from.
     *
     * Built HERE, before both the registry and the runtime, because they each need the same one: the
     * registry to answer the question and the runtime to raise the flag. Two instances would compile and
     * run and simply never hand over — the script would poll something nothing ever set.
     */
    private val runPhase = dev.ziggle.vscript.host.RunPhase()

    private val hosts: HostRegistry = library.install(
        // The file verbs, rooted at this client's own script data folder. Without a store the language
        // refuses every one of them — see FileStore.DENIED — so this is where a script GETS file access,
        // and the sandbox is not something a script can opt out of.
        dev.ziggle.vscript.nodes.BuiltinHosts.registry(domain.files, runPhase),
        // The domain's own values come back as the RECORDS a script reads fields off.
        domain.valueOut,
    )

    /**
     * Where a script imported from OUTSIDE the client's own graphs folder resolves its libraries.
     *
     * The IDE runs a file straight off disk, and that document's libraries sit beside it. Without this
     * the only search path was `~/.ziggle/graphs`, so a project that imported anything would not run —
     * reported as an unresolved import rather than as the missing search path it was. Set by
     * `graph import`, and left alone by everything else.
     */
    var importRoot: java.io.File? = null

    /**
     * Where `import` declarations resolve.
     *
     * Internal so [VScriptControl] hands the SAME source to `VsText`: reading a `.vs` and running the
     * graph must agree about what `banking` is, and two sources would be two answers.
     */
    val documents = DocumentSource(catalog) {
        listOfNotNull(EditorDoc.graphsDir(), importRoot)
    }

    init {
        // Publish the catalogue for editors that are not this one. Written on startup rather than on demand
        // because the reader is another PROCESS — an IDE plugin, which may never have the client running
        // when someone opens a .vs — and because it is how a client update teaches that plugin new nodes
        // without a plugin release. Best-effort: a catalogue that could not be written is not worth failing
        // a launch over, and the plugin falls back to the copy it shipped with.
        domain.catalogueReady(catalog)
    }

    val runtime = ScriptRuntime(
        catalog, hosts, actuator = domain.actuator, source = documents,
        // The SAME folders the graph side resolves against, read as source instead of as documents — see
        // [DocumentSource.asTextSource]. Two sources would be two answers to what `core/pace` means.
        textSource = documents.asTextSource(),
        runPhase = runPhase,
        // What this DOMAIN accumulates per run and per frame, and therefore what has to be dropped when a
        // run ends — see [dev.ziggle.vscript.nodes.GameRunLifecycle]. The runtime knows the moments; only
        // the domain knows what is sitting in them.
        lifecycle = domain.lifecycle,
    ).also {
        // Break on error is on and has no switch. It is the single highest-value thing a debugger does and
        // it costs nothing once pausing works: when a node faults you land ON it with the state still live,
        // instead of reading a line about it afterwards.
        it.breakOnError = true
        // The trace switch is the EDITOR's setting, so the editor is what answers for it. The runtime used
        // to read `EditorSettings` itself, which put it downstream of a preferences object that imports
        // the canvas and the run views — see [ScriptRuntime.traceRequested].
        it.traceRequested = { EditorSettings.traceExecution }
    }

    init {
        // The console's height is an EDITOR setting, so the editor is what remembers it. The run views do
        // not read a preferences file — they belong to neither surface and to no particular host; see
        // [dev.ziggle.vscript.runview.DebugPanel.Remembered].
        dev.ziggle.vscript.runview.DebugPanel.remembered =
            object : dev.ziggle.vscript.runview.DebugPanel.Remembered {
                override fun get(): Float = EditorSettings.consoleHeight
                override fun set(v: Float) { EditorSettings.consoleHeight = v }
            }
    }

    /**
     * The canvas, drawn entirely by us.
     *
     * It replaced an imgui-node-editor-backed one, which is gone: every visual decision in that library was
     * its own — node shape, wire routing, grid, animation, popups — and that is precisely the surface this
     * editor needs to control. Keeping both behind a toggle was worth it only while the replacement was
     * catching up; past parity it just meant every canvas feature had to be written twice.
     */
    private val canvas = OwnCanvas(catalog)

    /** The top bar. Holds the row's geometry between [Toolbar.begin] and [Toolbar.end]. */
    private val bar = Toolbar()

    /** The bottom panel: debugger tabs plus the console, sharing one drawer. */
    /** The routing numbers, live — see [Tuning]. */
    private val tuning = TuningWindow()

    private val panel = DebugPanel(catalog)

    /** The left sidebar: what the graph is MADE OF, as against what it is doing. */
    // The outline's Imports section asks the SAME source the compiler does, so a resolved dot there and
    // a successful run cannot disagree.
    private val outline = OutlinePanel(catalog).also { it.resolver = documents }

    /** The debugger's command surface — see [DebugSession] for why it is a protocol and not method calls. */
    val dbg = DebugSession(runtime)

    /**
     * The same editor, driven over the debug websocket.
     *
     * Built here rather than by the chrome so it always exists with the panel, and registered globally so
     * the debug server can find it — see [VScriptHost].
     */
    val control = VScriptControl(this).also { VScriptHost.control = it }

    /**
     * The `.vs` file currently loaded, and its source — null when the open document came from a canvas.
     *
     * **This is what decides which front end runs.** A `.vs` is a program in a text language and compiles
     * through `TextFrontEnd`; a `.json` is a canvas document and compiles through `GraphCompiler`. The
     * canvas still SHOWS an imported `.vs`, because lowering it for display costs nothing and reading the
     * shape is useful — but what runs is the source, not a projection of it.
     */
    var textScript: TextScript? = null

    /**
     * A `.vs` loaded from disk: where it came from, what it said, and what that compiled to.
     *
     * **Compiled once, at import.** Sites are keyed by AST identity, so compiling the same text twice
     * mints two sets of ids — and arming a breakpoint against one set while running the other is a
     * breakpoint that silently never fires. The editor arms against [sites], `graph spans` reports them,
     * and the run spawns [compiled]'s chunks: one table, one numbering.
     */
    class TextScript(
        val file: java.io.File,
        val source: String,
        val name: String,
        /**
         * What this document is CALLED — the reference an `import` names it by.
         *
         * The same string `TextFrontEnd(rootRef = …)` was given, and therefore the one every chunk of this
         * run is named after (`activities/activities#start`). A debugger on the other end of the socket
         * asks "is this frame in the document I ran" by comparing those two, so reporting anything else —
         * the file's bare name, or the lowered graph's `untitled` — makes the answer always no, and the
         * frame loses both its fallback line and its label.
         */
        val ref: String,
        val compiled: dev.ziggle.vscript.text.TextFrontEnd.Compilation,
        val sites: dev.ziggle.vscript.text.Sites,
    ) {
        /** What the console and the breakpoint sidecar key it by — stable across edits, unlike a hash. */
        val id: String get() = file.absolutePath
    }

    /**
     * Compile a `.vs` source through the text front end, against this panel's catalogue and folders.
     *
     * One place, so importing, checking and running cannot disagree about what a file compiles to.
     */
    fun compileText(file: java.io.File, source: String, debug: Boolean = true): TextScript {
        // Worked out ONCE and used twice — as the front end's `rootRef` and as what the run reports itself
        // to be. Two computations of it would be two answers to "which document is this".
        val ref = documents.refOf(file) ?: file.nameWithoutExtension
        val front = dev.ziggle.vscript.text.TextFrontEnd(
            natives = catalog.natives(),
            program = dev.ziggle.vscript.vm.ProgramBuilder(),
            imports = documents.asTextSource(),
            debug = debug,
            // **What this run's ROOT is called.** Every imported module is named by the reference it was
            // reached through; the root arrives as bare text and has none, so without this every root is
            // `<root>` — which is what its chunks are keyed and named by. The file name is the honest
            // fallback for a script that sits outside every search root.
            rootRef = ref,
        )
        return TextScript(file, source, file.nameWithoutExtension, ref, front.compile(source), front.sites)
    }

    /**
     * Start the open script, through whichever front end owns it.
     *
     * One place rather than two, because the Run button and `graph run` must not be able to disagree about
     * which compiler a file goes through.
     */
    fun startRun(debug: Boolean = true, forceGraph: Boolean = false): String? {
        val text = textScript
        val d = doc ?: return "nothing is open"
        if (text == null || forceGraph) return runtime.run(d, debug = debug)
        // `--release` recompiles: the debug apparatus is a compile-time decision, and the copy taken at
        // import has it. Everything else runs what was imported, ids and all.
        val program = if (debug) text else compileText(text.file, text.source, debug = false)
        // **Resolve the gutter's breakpoints against THIS compilation, immediately before it runs.**
        //
        // The code view stores lines, not site ids, because `Sites.idOf` hands out `next++` over AST
        // identity -- so the ids differ between the buffer's own compile, the one taken at import, and the
        // recompile a release run does on the line above. A site id stored anywhere would be a breakpoint
        // that silently never fires, and that failure looks like the debugger being broken rather than
        // like the id being stale.
        armLineBreakpoints(program.sites)
        return runtime.runText(program.compiled, program.sites, id = text.id, name = text.name)
    }

    /**
     * Hand the gutter's line breakpoints to the VM, as the site ids of the run about to start.
     *
     * Stated wholesale rather than toggled: what the gutter shows IS the set that should be armed, and a
     * previous run's ids mean nothing to this one.
     */
    private fun armLineBreakpoints(sites: dev.ziggle.vscript.text.Sites) {
        val wanted = code.breakpoints.resolve(sites.spans())
        runtime.breakpoints.clear()
        wanted.values.forEach { runtime.breakpoints.add(it) }
    }

    /** [startRun]'s counterpart for a script that is asleep — see `ScriptRuntime.wake`. */
    fun startWake(debug: Boolean = true): String? {
        val text = textScript
        val d = doc ?: return "nothing is open"
        if (runtime.phase != ScriptRuntime.Phase.ASLEEP) return "'${d.name}' is not asleep"
        if (text == null) return runtime.wake(d, debug = debug)
        return runtime.runText(text.compiled, text.sites, id = text.id, name = text.name, resume = true)
    }

    /** Breakpoints armed per graph, kept out of the document so arming one cannot dirty a script. */
    private val breakpointStore = BreakpointStore()

    /**
     * The open document. Assigning one wires its literal edits through to the runtime, so editing a value
     * retunes a script that is already running — see [ScriptRuntime.setLiteral].
     */
    var doc: EditorDoc? = null
        set(value) {
            field?.onLiteralCommitted = null
            field = value
            value?.onLiteralCommitted = { nodeId, pin, v -> runtime.setLiteral(nodeId, pin, v) }
        }

    /**
     * node id → where in the `.vs` source it came from, for the document that was last imported from text.
     *
     * Kept because it is the only bridge between a LINE and a NODE, and every consumer of the debugger
     * that is not the canvas addresses code by line: an external editor sets "break on line 42", and the
     * only thing the VM understands is "break on node 7". `Lower` computes this table on the way in and
     * had been dropping it on the floor.
     *
     * Null whenever the open document did not come from text — a graph built on the canvas has no source
     * to point at, and answering with a stale table would put breakpoints on lines of a different file.
     */
    var textSpans: Map<Int, dev.ziggle.vscript.lang.Span>?
        get() = runtime.textSpans
        // One copy, held by the runtime, because that is what has to hand it to every Validator and
        // GraphCompiler it builds — a second field here would be the same table twice and could go stale.
        set(value) { runtime.textSpans = value }

    /**
     * The same script as `.vs` source, when the Code tab is showing.
     *
     * A tab rather than a split: one thing at a time, sharing the toolbar and the problems strip. Its
     * buffer is loaded when the tab is ENTERED and applied only when asked — re-printing under an author
     * mid-edit would throw away what they had typed. See [CodeBuffer].
     */
    // **The SAME source the run resolves against.** Left at the default `TextSource.NONE`, the buffer
    // resolved no imports at all: every name reached through an alias came back unknown, so a document
    // that imports anything was a wall of red while the very same file ran perfectly — because
    // `compileText` was using `documents` and the buffer was not. Two answers to "what does `banking`
    // mean" is one too many, and the visible half was the wrong one.
    private val code = CodeView(catalog, documents.asTextSource()).also { view ->
        // **Rooted where imports resolve, not at the open file's folder.** A document that imports
        // `core/util` resolves it against the workspace root, so a tree rooted anywhere else would show a
        // different set of files from the one the compiler is reading.
        view.workspace = dev.ziggle.vscript.editor.text.Workspace(EditorDoc.graphsDir())
        view.onOpenFile = { file -> openTextFile(file) }
    }

    /**
     * Open a `.vs` from the file tree or the search box.
     *
     * Compiled on the way in rather than only loaded, because everything the editor offers about a
     * document — its diagnostics, its structure, which lines can hold a breakpoint — comes from the
     * compilation, and a file opened without one would come up looking like an empty document that
     * happens to have text in it.
     */
    fun openTextFile(file: java.io.File) {
        val source = runCatching { file.readText() }.getOrNull() ?: return
        textScript = runCatching { compileText(file, source) }.getOrNull()
        textSpans = textScript?.sites?.spans()
        code.open(source)
        code.currentFile = file
        showCode = true
        setStatus("opened ${file.name}", false)
    }

    /** Which of the two views the panel is showing. */
    var showCode: Boolean = false
        private set

    /** Every node's measured rectangle from the last frame — see [OwnCanvas.measuredRects]. */
    fun measuredRects() = canvas.measuredRects()

    /** Each container's heading depth — see [dev.ziggle.vscript.editor.graph.OwnCanvas.headingInsets]. */
    fun headingInsets() = canvas.headingInsets()

    /** Every wire's last routed path — see [dev.ziggle.vscript.editor.graph.OwnCanvas.routedWires]. */
    fun routedWires() = canvas.routedWires()

    /** Lay the open graph out, exactly as the toolbar's button does. */
    fun arrange() {
        doc?.let { canvas.autoArrange(it) }
    }

    /** The open script's name, or null with nothing open — the one thing every remote reply reports. */
    fun docName(): String? = doc?.name

    private var status: String = ""
    private var statusBad = false

    /** Documents on disk, refreshed on demand rather than every frame. */
    private var files: List<File> = emptyList()
    private var filesLoaded = false

    // ---- name prompt -------------------------------------------------------------------------------

    private enum class NamePrompt { NEW, RENAME }

    private val nameBuf = ImString(64)

    /** Requested from inside a menu, opened after it — see [toolbar]. */
    private var promptRequest: NamePrompt? = null
    private var prompt: NamePrompt? = null
    private var promptFocus = false

    /**
     * The detached window. Built once and reused — it is hidden rather than destroyed (see
     * [DetachedEditorWindow.close]), so this stays non-null once the user has ever popped out.
     */
    private var window: DetachedEditorWindow? = null

    /**
     * True while the editor lives in the detached window and the docked panel shows a placeholder.
     *
     * Cleared by the window's `onClosed` callback rather than by whoever asked it to close, because that
     * work is deferred to the AWT thread. Volatile: written on the AWT thread, read on the client thread.
     */
    @Volatile private var poppedOut = false

    /** Set by the host once ImGui is up; until then there is nothing for a second context to build on. */
    var canDetach: Boolean = false

    /**
     * Run the graph's On Render entries for this frame. Called FROM the render pass, unlike [tick].
     *
     * Early in the frame, so what it draws is available to every painter — the world overlay, the item
     * overlay and the panels all run at different points, and the draw store double-buffers precisely so
     * the order between them cannot matter.
     */
    fun renderFrame() = runtime.renderFrame()

    /** Run the graph's On Tick entries for this GAME tick — see [ScriptRuntime.gameTick]. */
    fun gameTick() = runtime.gameTick()

    /** Pump the VM. Called from the client thread by the host — NOT from the render pass. */
    /**
     * Run on the client thread each tick, after any remote command and before the fibers advance.
     *
     * **The gap this exists to close is one tick wide and would be maddening.** Starting a run clears the
     * control store (`dev.ziggle.nodes.ScriptUi`) so a tick box cannot resume where the last run left it —
     * and a run started by [control] is started right here, with its wake handlers pumped by the very next
     * line. Anything that publishes INTO that store on the script's behalf, like the scheduler's tuning,
     * therefore has to re-publish between those two statements, or the first thing a fresh rotation reads
     * is an empty store and it rolls its whole day on the compiled-in defaults.
     */
    var beforePump: (() -> Unit)? = null

    fun tick() {
        // Remote commands first, so one that starts a run has its fibers advanced by this same tick rather
        // than waiting for the next one. It is also the only place the document is safe to mutate.
        control.drain()
        beforePump?.let { hook -> runCatching { hook() } }
        runtime.tick()
        dbg.enforcePauseAll()
    }

    /**
     * True while the pointer is over the editor — read by the host to park the virtual mouse.
     *
     * Editing a graph is precise pointer work, and a virtual cursor chasing your hand across the game
     * underneath is both noise to look at and input the game reacts to. See
     * `dev.ziggle.input.VirtualMouse.pointerClaimed`.
     *
     * Latched while a drag is in flight, so pulling a wire or a selection box past the panel's edge does not
     * hand the mouse back mid-gesture — which would wake the game up in the middle of the drag.
     */
    val pointerOver: Boolean
        // Answered against the CURRENT frame, so closing the panel while the cursor is inside it cannot
        // leave the mouse parked forever. A panel that is not drawn does not report, rather than reporting
        // whatever was true when it last did — the failure mode of every latch nobody remembers to clear.
        get() = pointerFrame == ImGui.getFrameCount() && pointerHeld

    private var pointerHeld = false
    private var pointerFrame = -1
    private var dragLatch = false

    /** Note whether the editor has the pointer. Called from [render], where the window is current. */
    private fun trackPointer() {
        val over = ImGui.isWindowHovered(
            ImGuiHoveredFlags.RootAndChildWindows or ImGuiHoveredFlags.AllowWhenBlockedByActiveItem,
        )
        val down = ImGui.isMouseDown(ImGuiMouseButton.Left)
        if (over && down) dragLatch = true
        if (!down) dragLatch = false
        pointerHeld = over || dragLatch
        pointerFrame = ImGui.getFrameCount()
    }

    fun render() {
        trackPointer()
        // While popped out the docked panel is just a placeholder. The canvas holds one camera and one
        // selection, so drawing it in both windows would have two sets of mouse input driving one gesture.
        if (poppedOut) {
            val w = window
            if (w != null && w.isOpen) {
                ImGui.textDisabled("Editing in the detached window.")
                if (DrawKit.button("##vs-reattach", "Dock it back", 110f)) w.close()
            } else {
                ImGui.textDisabled("Docking back…")
            }
            return
        }
        renderBody()
        // Over everything, including the detached window's own body: it takes the keyboard while it is
        // open, so it must not be something the canvas can draw on top of.
        searchOverlay()
        // AFTER the body: it is a floating window over the canvas, and drawing it first would put the
        // canvas child on top of it.
        tuning.render()
    }

    /**
     * Search everywhere: the gesture, and the box.
     *
     * Polled every frame whether or not the code view is showing — the box is not part of the code view,
     * and being able to reach a document by name from the canvas is most of the point of having it.
     */
    private fun searchOverlay() {
        code.search.pollShortcut(System.currentTimeMillis())
        if (!code.search.isOpen) return
        // NOT `val io = ImGui.getIO()`: a local called `io` shadows the package root, so every
        // `dev.ziggle...` in scope stops resolving. Third time this has bitten in this codebase.
        val display = ImGui.getIO()
        code.search.draw(
            display.displaySizeX, display.displaySizeY,
            dev.ziggle.vscript.editor.host.EditorHost.typed.drain(),
        )
    }

    /** The editor body, drawn either docked or in the detached window. */
    private fun renderBody() {
        if (!filesLoaded) refreshFiles()
        // No separator: the bar draws its own hairline, and a second rule under it reads as a seam.
        toolbar()

        val d = doc

        // **The code tab does not need a canvas document, and requiring one was a leftover.**
        //
        // This used to fall through to the "No script open" empty state whenever `doc` was null, before it
        // ever looked at `showCode` — which made sense when a `.vs` was a PROJECTION of a graph and so
        // always had one behind it. It is not: a `.vs` is the document. Opening one from the file tree or
        // the search box leaves `doc` null by design, and the editor showed an empty canvas and an
        // invitation to create a script that was already open.
        //
        // `DebugPanel.render` has always taken a nullable doc, so the drawer needs nothing here either.
        if (showCode) {
            val cx = ImGui.getCursorScreenPosX()
            val cy = ImGui.getCursorScreenPosY()
            val cw = ImGui.getContentRegionAvailX()
            val ch = ImGui.getContentRegionAvailY() - panel.height()
            code.draw(cx, cy, cw, ch)
            panel.render(runtime.log, dbg, d, cx, cy + ch, cw)
            return
        }

        // The CANVAS needs one, and says so.
        if (d == null) {
            emptyState()
            return
        }

        panel.describeNodes(d)
        // The console takes its height OUT of the canvas rather than floating over it. Layering looked
        // free — the strip underneath is hidden either way — but the canvas's viewport is what Fit and
        // centring measure against, so an overlay quietly framed graphs behind the drawer.
        val hostX = ImGui.getCursorScreenPosX()
        val hostY = ImGui.getCursorScreenPosY()
        val hostW = ImGui.getContentRegionAvailX()
        val hostH = ImGui.getContentRegionAvailY()
        val drawerH = panel.height()

        // The sidebar is drawn BEFORE the canvas so a drop released this frame reaches the canvas in the
        // same frame — the canvas is the only thing that knows how to turn a screen point into a graph one.
        val sideW = outline.width()
        outline.onReveal = { id -> canvas.reveal(d, id) }
        outline.onWatch = { name -> panel.watchVariable(name) }
        outline.render(d, hostX, hostY, hostH - drawerH)
        outline.drop?.let {
            canvas.pendingDrop = it
            outline.drop = null
        }

        ImGui.setCursorScreenPos(hostX + sideW, hostY)
        ImGui.beginChild("##canvas-host", hostW - sideW, hostH - drawerH, false)
        try {
            canvas.setIssues(runtime.issues)
            canvas.activeNodes = runtime.activeNodes()
            canvas.activeLinks = runtime.activeLinks()
            canvas.logLevels = runtime.log.worstByNode()
            canvas.logsForNode = { id -> runtime.log.forNode(id).map { badgeLine(it) } }
            canvas.onBadgeClicked = { id -> panel.openConsoleFor(id) }
            canvas.breakpoints = runtime.breakpoints.entries().associate { it.first to it.second.enabled }
            canvas.onToggleBreakpoint = { id -> toggleBreakpoint(d, id) }
            canvas.onNewVariable = { nodeId ->
                val name = d.freeVariableName()
                d.addVariable(dev.ziggle.vscript.model.GraphVariable(name, PinType.INT, 0))
                d.setNodeVariable(nodeId, name)
                panel.show(DebugPanel.Tab.VARIABLES)
            }
            canvas.pausedNode = dbg.focused()?.takeIf { it.isPaused }?.nodeId ?: -1
            canvas.pinValues = if (dbg.isPaused) dbg.visibleValues(dbg.focused()!!.id) else emptyMap()
            // Pure results come from the runtime's own recording rather than from a frame, so they are
            // available for the same reason and at the same moments — but they are the LAST value seen,
            // not one read out of a live register. Gated on the pause for consistency with the pills above.
            canvas.pureValues = if (dbg.isPaused) runtime.pureValues else emptyMap()
            canvas.render(d)
        } finally {
            // MUST run even on a throw: an unclosed child escapes into Chrome and surfaces there as
            // "Must call EndChild() and not End()!", pointing at the chrome instead of at this panel.
            ImGui.endChild()
        }
        shortcuts(d)
        val reveal = panel.render(runtime.log, dbg, d, hostX, hostY + hostH - drawerH, hostW)
        if (reveal >= 0) canvas.reveal(d, reveal)

        // The catalogue popup is drawn HERE, once, into the foreground list — the outermost place that
        // knows the whole editor's bounds. Drawn inside the canvas or the sidebar it was clipped to that
        // child, and each child would have needed its own copy of the routing besides.
        valuePicker(d)
        colorPicker(d)
    }

    /**
     * The open value picker, and where its choice belongs.
     *
     * Routed by the id it was opened with: `v|node|dir|pin` is a node's literal, `vd|name` a variable's
     * default. That keeps ownership with whoever opened it rather than with a draw order.
     */
    /**
     * Drive the colour picker, committing through the SAME id routes the value picker uses.
     *
     * Deliberately reusing [commitPicked] rather than writing the value here: a colour can land on a node
     * pin, a variable's default or one slot of a list, and those are three different writes. Teaching a
     * second picker all three is how they drift apart.
     */
    private fun colorPicker(d: EditorDoc) {
        val id = dev.ziggle.vscript.editor.graph.ColorPicker.openId ?: return
        val argb = dev.ziggle.vscript.editor.graph.ColorPicker.render() ?: return
        commitPicked(d, id, argb)
    }

    private fun valuePicker(d: EditorDoc) {
        val id = ValuePicker.openId ?: return
        val chosen = ValuePicker.render(
            ImGui.getForegroundDrawList(),
            ImGui.getWindowPosX(), ImGui.getWindowPosY(),
            ImGui.getWindowWidth(), ImGui.getWindowHeight(),
        ) ?: return
        commitPicked(d, id, chosen)
    }

    /** Write a picked value to whatever [id] names — a node pin, a variable default, a list slot. */
    private fun commitPicked(d: EditorDoc, id: String, chosen: Any) {
        when {
            id.startsWith("vd|") -> {
                val name = id.removePrefix("vd|")
                d.variable(name)?.let { v -> d.updateVariable(v.name, v.type, chosen) }
            }
            // A variable's TYPE. The default is re-coerced rather than kept: a `4151` left over from an Item
            // would read as a tile of 4151,0,0 once the type changed under it.
            id.startsWith("vt|") -> {
                val type = chosen as? dev.ziggle.vscript.model.TypeRef ?: return
                val name = id.removePrefix("vt|")
                d.variable(name)?.let { v ->
                    d.updateVariable(
                        v.name, type,
                        dev.ziggle.vscript.model.Literals.of(type, v.default?.toString() ?: "", d.enums),
                    )
                }
            }
            // A list variable's ELEMENT type: `ve|<name>`. The slots are re-coerced through the new type
            // rather than kept, for the same reason [vt|] re-coerces a scalar default — a 4151 that meant
            // an Item is not a tile, and carrying it across would silently mean something else.
            id.startsWith("ve|") -> {
                val elem = chosen as? dev.ziggle.vscript.model.TypeRef ?: return
                val name = id.removePrefix("ve|")
                d.variable(name)?.let { v ->
                    val slots = (v.default as? List<*>).orEmpty()
                        .map { dev.ziggle.vscript.model.Literals.of(elem, it?.toString() ?: "", d.enums) }
                    d.updateVariable(v.name, dev.ziggle.vscript.model.TypeRef.list(elem), slots)
                }
            }
            // One slot of a list variable's default: `vs|<name>|<index>`.
            id.startsWith("vs|") -> {
                val parts = id.split('|')
                val name = parts.getOrNull(1) ?: return
                val index = parts.getOrNull(2)?.toIntOrNull() ?: return
                d.variable(name)?.let { v ->
                    val slots = (v.default as? List<*>).orEmpty().toMutableList()
                    if (index !in slots.indices) return
                    slots[index] = chosen
                    d.updateVariable(v.name, v.type, slots.toList())
                }
            }
            // One of a declared type's field types: `st|<type>|<index>`.
            id.startsWith("st|") -> {
                val type = chosen as? dev.ziggle.vscript.model.TypeRef ?: return
                val parts = id.split('|')
                val t = d.struct(parts.getOrNull(1) ?: return) ?: return
                val index = parts.getOrNull(2)?.toIntOrNull() ?: return
                if (index !in t.fields.indices) return
                val fields = t.fields.toMutableList()
                fields[index] = dev.ziggle.vscript.model.FunctionPin(fields[index].name, type)
                d.updateStruct(t.name, fields)
            }
            // One of a function's parameter or result types: `ft|<fn>|<in|out>|<index>`.
            id.startsWith("ft|") -> {
                val type = chosen as? dev.ziggle.vscript.model.TypeRef ?: return
                val parts = id.split('|')
                val fn = d.function(parts.getOrNull(1) ?: return) ?: return
                val index = parts.getOrNull(3)?.toIntOrNull() ?: return
                val ins = fn.params.toMutableList()
                val outs = fn.results.toMutableList()
                val side = if (parts.getOrNull(2) == "in") ins else outs
                if (index !in side.indices) return
                side[index] = dev.ziggle.vscript.model.FunctionPin(side[index].name, type)
                d.updateFunction(fn.name, ins, outs)
            }
            id.startsWith("v|") -> {
                val parts = id.split('|')
                val nodeId = parts.getOrNull(1)?.toIntOrNull() ?: return
                val pin = parts.getOrNull(3) ?: return
                d.setLiteral(nodeId, pin, chosen)
            }
        }
    }

    /** One line of a node's badge tooltip. */
    private fun badgeLine(r: dev.ziggle.vscript.log.LogRecord): String {
        val stamp = "%+.3fs".format((r.atNanos - runtime.log.runStartNanos) / 1e9)
        val n = if (r.repeats > 1) "  ×${r.repeats}" else ""
        // Stripped: a tooltip is a string, not a drawing surface, and would otherwise read out the tags.
        return "$stamp  ${dev.ziggle.vscript.model.RichText.plain(r.message)}$n"
    }

    /** What the panel shows with nothing open — a landing screen, not an empty canvas. */
    private fun emptyState() {
        ImGui.dummy(1f, 18f)
        ImGui.textDisabled("No script open.")
        ImGui.dummy(1f, 6f)
        if (DrawKit.button("##vs-empty-new", "New script…", 120f)) promptRequest = NamePrompt.NEW
        if (files.isEmpty()) {
            ImGui.dummy(1f, 10f)
            ImGui.textWrapped("Create one, then right-click the canvas to add nodes.")
            return
        }
        ImGui.dummy(1f, 14f)
        ImGui.textDisabled("or open one")
        for (f in files) if (ImGui.selectable(f.nameWithoutExtension)) openDocument(f)
    }

    // ---- chrome ------------------------------------------------------------------------------------

    /**
     * The top bar.
     *
     * Read left to right it is: identity (what am I editing), history, view, then — pinned to the right —
     * state and the actions that change it. Each group is separated by space and a hairline rather than by
     * each control carrying its own border; see [Toolbar] for why that is the whole trick.
     *
     * Save and New are not buttons here. The dirty dot already reports save state and Ctrl+S is the real
     * interaction, so a permanent Save button is chrome that is almost never the thing you want; both live
     * in the folder menu with the rest of the file operations.
     */
    private fun toolbar() {
        val d = doc
        bar.begin()

        // --- identity -------------------------------------------------------------------------------
        if (bar.icon("##vs-files", ic(Fonts.FOLDER), "File actions")) ImGui.openPopup(FILE_MENU)
        bar.divider()

        bar.text("scripts", Toolbar.TEXT_BRIGHT)
        bar.gap()
        bar.text("/", Toolbar.CRUMB)
        bar.gap()
        val docLabel = (d?.name ?: "no script") + "  " + ic(Fonts.CHEVRON_DOWN)
        if (bar.textButton("##vs-doc", docLabel, Toolbar.ICON, "Switch script")) ImGui.openPopup(DOC_MENU)
        if (d != null && d.dirty) {
            // The unsaved marker sits with the document name it describes, not with the folder — the
            // mock-up put it beside the breadcrumb root, where it would be reporting on nothing.
            bar.gap()
            bar.dot(Toolbar.DIRTY, 2.5f)
        }

        // --- canvas / code ---------------------------------------------------------------------------
        bar.divider()
        if (bar.icon("##vs-canvas", ic(Fonts.SITEMAP), "Node canvas", enabled = d != null, resting = !showCode)) {
            showCode = false
        }
        // REQUIRED, not spacing. `icon` draws an `invisibleButton`, which advances ImGui's layout cursor to
        // the next LINE; only `gap` (and `divider`, at a cluster boundary) calls `sameLine`. Without it this
        // icon and everything after it — undo, redo, fit, arrange, the run button — wrapped off the bar and
        // drew underneath the canvas, which looks exactly like the toolbar having been deleted.
        bar.gap()
        // **Enabled by having something to SHOW, which is a text script — not by having a canvas
        // document.** Both buttons were gated on `d != null` from when a `.vs` was a projection of a
        // graph and so always had one; a file opened from the tree or the search box has no canvas
        // document at all, and the tab it lives in was greyed out.
        if (bar.icon("##vs-code", ic(Fonts.CODE), "Code", enabled = textScript != null || d != null, resting = showCode)) {
            // Loaded on ENTRY only: once you are in the buffer it is yours, and re-reading the file
            // under you every frame would throw away whatever had been typed.
            //
            // The SOURCE, not a printing of the canvas document. The code view edits the `.vs` that was
            // opened; a canvas document has no text form to edit and the Code tab simply has nothing to
            // show for one.
            if (!showCode) textScript?.let { code.open(it.source) }
            showCode = true
        }

        // --- history --------------------------------------------------------------------------------
        bar.divider()
        if (bar.icon("##vs-undo", ic(Fonts.UNDO), "Undo  Ctrl+Z", enabled = d?.history?.canUndo == true)) undo(d!!)
        bar.gap()
        if (bar.icon("##vs-redo", ic(Fonts.REDO), "Redo  Ctrl+Shift+Z", enabled = d?.history?.canRedo == true)) redo(d!!)

        // --- view -----------------------------------------------------------------------------------
        bar.divider()
        val hasDoc = d != null
        if (bar.icon("##vs-fit", ic(Fonts.EXPAND), "Fit to selection", enabled = hasDoc)) canvas.fitToContent(d!!)
        bar.gap()
        if (bar.icon("##vs-arrange", ic(Fonts.GRID), "Auto-arrange", enabled = hasDoc)) {
            canvas.autoArrange(d!!)
            setStatus("arranged", false)
        }
        bar.gap()
        if (bar.icon("##vs-comment", ic(Fonts.COMMENT), "Comment the selection", enabled = hasDoc)) {
            canvas.commentSelection(d!!)
        }

        // --- graph data -----------------------------------------------------------------------------
        bar.divider()
        val varCount = d?.variables?.size ?: 0
        if (bar.icon("##vs-outline", ic(Fonts.SIDEBAR), "Outline" + (if (varCount > 0) "  ($varCount variables)" else ""), enabled = hasDoc, resting = outline.open)) {
            outline.toggle()
        }

        // --- state and execution, pinned right ------------------------------------------------------
        //
        // The transport is NOT here. Continue and the step commands live on the debug drawer's own header,
        // beside the call stack and the values they advance — a transport at the top of the window and the
        // state at the bottom means looking in one place and reaching in another on every step.
        val st = statusPill()
        // An asleep script is NOT offered "Run". Pressing it would start a cold run, which discards the
        // state the sleep just wrote — the one press that silently throws away the thing the feature
        // exists for. Stop stays available, because abandoning an asleep script is a real thing to want.
        val runGlyph = if (runtime.isRunning || runtime.isAsleep) ic(Fonts.STOP) else ic(Fonts.PLAY)
        val runLabel = if (runtime.isRunning || runtime.isAsleep) "Stop" else "Run"
        // Both states measured, widest wins, and the button is pinned to it. "Stop" is a letter longer than
        // "Run", so sizing to the current label made the whole right-hand cluster jump the moment a script
        // started — the one time you are least interested in the toolbar moving.
        val runW = maxOf(bar.primaryWidth(ic(Fonts.PLAY), "Run"), bar.primaryWidth(ic(Fonts.STOP), "Stop"))
        // Sleep/Wake is the same button in two directions, so it is measured once at its wider label.
        val sleepW = maxOf(bar.buttonWidth(ic(Fonts.MOON), "Sleep"), bar.buttonWidth(ic(Fonts.MOON), "Wake"))
        var right = bar.pillWidth(st.label, st.count) + Toolbar.DIV_MARGIN +
            bar.buttonWidth(ic(Fonts.CHECK_SQUARE), "Check") + Toolbar.PAIR_GAP + sleepW +
            Toolbar.PAIR_GAP + runW
        // Settings and the window control are one cluster: both are about the editor rather than the graph.

        right += bar.dividerWidth + bar.iconWidth
        if (canDetach) right += Toolbar.CLUSTER_GAP + bar.iconWidth
        bar.spring(right)

        if (bar.statusPill(st.color, st.label, st.tip, st.count)) panel.toggle()
        ImGui.sameLine(0f, Toolbar.DIV_MARGIN)

        // Resting plate, not a bare ghost: Check is the secondary half of the run pair, and next to a filled
        // Run an unbacked icon-and-label reads as a caption rather than a button.
        if (bar.button("##vs-check", ic(Fonts.CHECK_SQUARE), "Check", "Validate the graph", hasDoc, resting = true)) {
            val issues = runtime.validate(d!!, report = true)
            val errs = issues.count { it.severity == Severity.ERROR }
            setStatus(if (errs == 0) "no problems" else "$errs error(s)", errs > 0)
        }
        bar.gap(Toolbar.PAIR_GAP)
        // The cooperative half of the run pair: ask rather than kill. Enabled only where it means
        // something — there is nothing to put to sleep before a run, and nothing to wake mid-handoff.
        val canSleep = runtime.isRunning && !runtime.isAsleep &&
            runtime.phase != ScriptRuntime.Phase.QUIESCING &&
            runtime.phase != ScriptRuntime.Phase.SLEEPING
        val sleepLabel = if (runtime.isAsleep) "Wake" else "Sleep"
        val sleepTip = if (runtime.isAsleep) {
            "Start it again — On Wake first, then the loop"
        } else {
            "Ask it to finish what it is doing, then hand over"
        }
        if (bar.button(
                "##vs-sleep", ic(Fonts.MOON), sleepLabel, sleepTip,
                enabled = hasDoc && (canSleep || runtime.isAsleep), resting = true,
            )
        ) {
            if (runtime.isAsleep) {
                val err = startWake()
                setStatus(err ?: "waking", err != null)
            } else {
                runtime.requestSleep("asked from the editor")
                setStatus("finishing up", false)
            }
        }
        // Wider than a within-cluster gap: Check and Run do very different things, and at 2px apart the
        // pair read as one segmented control you could hit the wrong half of.
        bar.gap(Toolbar.PAIR_GAP)
        // Dimmed rather than hidden with no document open: the primary action keeps its place in the bar.
        val runTint = when {
            !hasDoc -> Theme.shade(Theme.ACCENT, 0.5f)
            runtime.isRunning || runtime.isAsleep -> Toolbar.STOP
            else -> Theme.ACCENT
        }
        if (bar.primary("##vs-run", runGlyph, runLabel, runTint, minWidth = runW) && hasDoc) {
            if (runtime.isRunning || runtime.isAsleep) {
                runtime.stop()
                setStatus("stopped", false)
            } else {
                val err = startRun()
                setStatus(err ?: "running", err != null)
            }
        }

        bar.divider()
        if (bar.icon(
                "##vs-tuning", ic(Fonts.SLIDERS), "Wire tuning — the routing numbers, live",
                resting = tuning.open,
            )
        ) {
            tuning.toggle()
        }
        bar.gap()
        if (bar.icon("##vs-settings", ic(Fonts.GEAR), "Editor settings")) ImGui.openPopup(SETTINGS_MENU)

        // One control, two directions. While the editor is detached this button is drawn by the DETACHED
        // window's own toolbar, so putting the return action anywhere else would mean the window you are
        // looking at has no way to send itself back.
        if (canDetach) {
            bar.gap()
            val out = poppedOut
            val glyph = if (out) ic(Fonts.POP_IN) else ic(Fonts.POP_OUT)
            val tip = if (out) "Dock back into the client" else "Open in its own window"
            if (bar.icon("##vs-popout", glyph, tip)) {
                if (out) window?.close() else popOut()
            }
        }

        bar.end()

        // Menus after the row, not inline with the control that opens them: a popup is a window of its own,
        // and opening one in the middle of a `sameLine` chain is the sort of thing that only misbehaves once
        // the row is long enough to matter.
        fileMenu(d)
        docMenu()
        settingsMenu()
        // Opened here rather than from inside the menu that asked for it: OpenPopup called while a popup is
        // on the stack opens a NESTED popup, which the root-level BeginPopup below would then never match.
        promptRequest?.let { openPrompt(it) }
        namePopup()
    }

    private fun toggleBreakpoint(d: EditorDoc, nodeId: Int) {
        runtime.breakpoints.toggle(nodeId)
        breakpointStore.save(breakpointKey() ?: d.id, runtime.breakpoints)
    }

    /**
     * Arm or clear a breakpoint by node, and persist it.
     *
     * Stated rather than toggled, because a remote caller cannot see the gutter: `break 7` twice should
     * leave node 7 broken on, not back where it started.
     */
    fun armBreakpoint(nodeId: Int, on: Boolean) {
        val key = breakpointKey() ?: return
        if (on) runtime.breakpoints.add(nodeId) else runtime.breakpoints.remove(nodeId)
        breakpointStore.save(key, runtime.breakpoints)
    }

    /**
     * What breakpoints are filed under.
     *
     * A `.vs` is keyed by its PATH rather than by the document id of whatever it lowered to: that id is
     * minted fresh on each import, so breakpoints armed on a file would be lost the next time it was
     * opened — which is the one thing the sidecar exists to prevent.
     */
    private fun breakpointKey(): String? = textScript?.id ?: doc?.id

    private fun ic(codepoint: Int): String = Fonts.icon(codepoint)

    /** What the status pill should say, and in what colour. */
    private class Status(val color: Int, val label: String, val tip: String, val count: String = "")

    /**
     * The pill doubles as the console button.
     *
     * It picks up a count only when there is something to see, so a run that produced nothing looks exactly
     * as it did before the console existed — which is the whole bargain of tier one: no new chrome unless
     * the chrome has earned its place.
     */
    private fun statusPill(): Status {
        val c = runtime.log.counts()
        val count = when {
            c.error > 0 -> "${c.error} error" + (if (c.error > 1) "s" else "")
            c.total > 0 -> c.total.toString()
            else -> ""
        }
        val err = runtime.lastError
        val base = when {
            // FIRST, and above even the run states. Laying out a freshly imported script is the one thing
            // that makes the canvas look broken — the wires are straight and crossing everything — and a
            // reader with no idea it is still working assumes that IS the graph. It routes in the
            // background, so everything else on this bar stays usable while it does.
            canvas.routingProgress != null -> Status(
                Theme.WARN,
                "wiring ${((canvas.routingProgress ?: 0f) * 100).toInt()}%",
                "Laying the wires out. The script can be run, stopped and debugged meanwhile.",
            )
            runtime.fibers.any { it.state == FiberState.PAUSED } -> Status(Theme.WARN, "paused", "")
            // Before the `isRunning` case, which stays true right through a handoff. Saying "running"
            // after somebody pressed Sleep would look like the press had done nothing at all.
            runtime.isAsleep -> Status(
                Toolbar.IDLE_DOT,
                "asleep" + agoSuffix(runtime.sleptAtMs),
                if (runtime.sleptCleanly) "Wake to start it again" else "It was forced — its state may be stale",
            )
            runtime.phase == ScriptRuntime.Phase.WAKING ->
                Status(Theme.ACCENT, "preparing…", "On Wake is running; the loop has not started yet")
            runtime.phase == ScriptRuntime.Phase.QUIESCING -> Status(
                Theme.WARN,
                "finishing…",
                "It has been asked to hand over and is finishing what it was doing" +
                    (if (runtime.sleepReason.isBlank()) "" else "\n${runtime.sleepReason}"),
            )
            runtime.phase == ScriptRuntime.Phase.SLEEPING || runtime.phase == ScriptRuntime.Phase.DRAINING ->
                Status(Theme.WARN, "handing over…", "On Sleep is running")
            runtime.isRunning -> Status(Theme.ACCENT, "running", "")
            err != null -> Status(Theme.BAD, trim(err.lineSequence().first()), err)
            statusBad -> Status(Theme.BAD, trim(status.ifEmpty { "error" }), status)
            status.isNotEmpty() -> Status(Toolbar.IDLE_DOT, trim(status), status)
            else -> Status(Toolbar.IDLE_DOT, "stopped", "")
        }
        // Errors tint the dot even when the run ended by itself — a script that finished having failed is
        // not the same as one that finished.
        val color = if (c.error > 0 && base.color == Toolbar.IDLE_DOT) Theme.BAD else base.color
        val tip = (if (base.tip.isEmpty()) "" else base.tip + "\n") + "Console  (`)"
        return Status(color, base.label, tip, count)
    }

    /** Keep the pill a predictable width; the full text stays available as a tooltip. */
    private fun trim(s: String): String = if (s.length <= STATUS_CHARS) s else s.take(STATUS_CHARS - 1) + "…"

    /**
     * ` 12m` for a moment [sinceMs] ago, or nothing while it is still seconds.
     *
     * Coarse on purpose: how long a script has been asleep is a glanceable fact, and a number that ticks
     * every second in the corner of the toolbar is movement asking to be watched.
     */
    private fun agoSuffix(sinceMs: Long): String {
        if (sinceMs <= 0L) return ""
        val mins = (System.currentTimeMillis() - sinceMs) / 60_000L
        return when {
            mins < 1L -> ""
            mins < 60L -> " ${mins}m"
            else -> " ${mins / 60L}h"
        }
    }

    /** File operations, off the folder icon — where a Save button used to sit permanently. */
    private fun fileMenu(d: EditorDoc?) {
        if (!ImGui.beginPopup(FILE_MENU)) return
        if (ImGui.menuItem("New script…")) promptRequest = NamePrompt.NEW
        if (ImGui.menuItem("Rename…", "", false, d != null)) promptRequest = NamePrompt.RENAME
        if (ImGui.menuItem("Save", "Ctrl+S", false, d != null)) saveDocument()
        ImGui.separator()
        if (ImGui.menuItem("Refresh list")) refreshFiles()
        ImGui.endPopup()
    }

    /** The document selector hanging off the breadcrumb — what the list pane used to be. */
    private fun docMenu() {
        if (!ImGui.beginPopup(DOC_MENU)) return
        if (files.isEmpty()) ImGui.textDisabled("no scripts yet")
        for (f in files) {
            val open = doc?.file?.absolutePath == f.absolutePath
            if (ImGui.menuItem(f.nameWithoutExtension, "", open)) openDocument(f)
        }
        ImGui.separator()
        if (ImGui.menuItem("New script…")) promptRequest = NamePrompt.NEW
        ImGui.endPopup()
    }

    /**
     * Editor preferences.
     *
     * Separate from the file menu on purpose: that one acts on the document in front of you, this one
     * changes how the editor behaves everywhere and for every script. Each entry carries a line saying what
     * it does, because a checkbox called "Undo camera moves" does not tell you what you are trading away.
     */
    /** What each routing mode is called in the menu — the enum names are for the file, not for reading. */
    private fun routingLabel(mode: WireRouting): String = when (mode) {
        WireRouting.DIRECT -> "Straight to the pin"
        WireRouting.AVOID_NODES -> "Route around nodes"
        WireRouting.AVOID_ALL -> "Route around nodes and wires"
    }

    private fun settingsMenu() {
        if (!ImGui.beginPopup(SETTINGS_MENU)) return
        ImGui.textDisabled("Editor settings")
        ImGui.separator()
        if (ImGui.menuItem("Undo camera moves", "", EditorSettings.cameraUndo)) {
            EditorSettings.cameraUndo = !EditorSettings.cameraUndo
        }
        ImGui.textDisabled("  Pans and zooms join Ctrl+Z, grouped by burst.")
        ImGui.textDisabled("  Off: undo only ever touches the graph.")
        ImGui.separator()
        if (ImGui.menuItem("Trace node execution", "", EditorSettings.traceExecution)) {
            EditorSettings.traceExecution = !EditorSettings.traceExecution
        }
        ImGui.textDisabled("  Log every node the run enters, not just what")
        ImGui.textDisabled("  nodes emit. Thousands of rows a second on a")
        ImGui.textDisabled("  tick routine — takes effect on the next run.")
        ImGui.separator()
        if (ImGui.menuItem("Right-angled wires", "", EditorSettings.orthogonalWires)) {
            EditorSettings.orthogonalWires = !EditorSettings.orthogonalWires
        }
        ImGui.textDisabled("  Wires turn at right angles instead of curving.")
        ImGui.textDisabled("  Parallel runs share a lane and read as a bus;")
        ImGui.textDisabled("  curves say \"this goes there\" more directly.")
        ImGui.separator()
        ImGui.textDisabled("Wire routing")
        for (mode in WireRouting.values()) {
            if (ImGui.menuItem(routingLabel(mode), "", EditorSettings.wireRouting == mode)) {
                EditorSettings.wireRouting = mode
            }
        }
        ImGui.textDisabled("  Straight goes pin to pin whatever is in the way.")
        ImGui.textDisabled("  Around nodes keeps a wire visible for its whole")
        ImGui.textDisabled("  length. Around everything also pulls apart wires")
        ImGui.textDisabled("  sharing a lane, which otherwise look like one.")
        ImGui.endPopup()
    }

    private fun openPrompt(which: NamePrompt) {
        promptRequest = null
        prompt = which
        nameBuf.set(if (which == NamePrompt.RENAME) doc?.name ?: "" else "script")
        promptFocus = true
        ImGui.openPopup(NAME_POPUP)
    }

    /** Naming, for both create and rename — one field, because they are the same question. */
    private fun namePopup() {
        if (!ImGui.beginPopup(NAME_POPUP)) return
        val renaming = prompt == NamePrompt.RENAME
        ImGui.textDisabled(if (renaming) "Rename script" else "New script")
        // Focus the field on the frame it opens, so you can simply type — a naming prompt that needs a
        // click first is a prompt you resent.
        if (promptFocus) {
            ImGui.setKeyboardFocusHere()
            promptFocus = false
        }
        ImGui.setNextItemWidth(190f)
        val entered = ImGui.inputText("##vs-name-field", nameBuf, ImGuiInputTextFlags.EnterReturnsTrue)
        ImGui.sameLine()
        val confirmed = DrawKit.button("##vs-name-ok", if (renaming) "Rename" else "Create", 72f)
        if (entered || confirmed) {
            val name = sanitize(nameBuf.get())
            if (name.isNotEmpty()) {
                if (renaming) renameDocument(name) else newDocument(name)
                ImGui.closeCurrentPopup()
            }
        }
        ImGui.endPopup()
    }

    /** A document name has to survive being a filename, so the characters that cannot are dropped. */
    private fun sanitize(raw: String): String =
        raw.trim().replace(Regex("""[\\/:*?"<>|]"""), "").take(60)

    /** Open the editor in its own OS window. The docked panel becomes a placeholder until it is closed. */
    private fun popOut() {
        if (poppedOut) return
        val w = window ?: DetachedEditorWindow(
            title = "vscript — Scripts",
            drawBody = { renderBody() },
            onClosed = { poppedOut = false },
        ).also { window = it }
        poppedOut = true
        if (!w.open()) {
            poppedOut = false
            setStatus("could not open a detached window", true)
        }
    }

    // ---- documents ---------------------------------------------------------------------------------

    fun refreshFiles() {
        val dir = EditorDoc.graphsDir()
        files = (dir.listFiles { f: File -> f.isFile && f.extension == "json" } ?: emptyArray())
            .sortedBy { it.name }
        filesLoaded = true
    }

    fun newDocument(name: String = "untitled") {
        runtime.stop()
        doc = EditorDoc.blank(name.ifBlank { "untitled" })
        breakpointStore.load(doc!!.id, runtime.breakpoints)
        canvas.reset()
        setStatus("new script", false)
    }

    fun openDocument(file: File) {
        runtime.stop()
        try {
            doc = EditorDoc.open(file)
            breakpointStore.load(doc!!.id, runtime.breakpoints)
            // The camera re-seeds itself on the first frame of a fresh canvas, so a reset frames the graph.
            canvas.reset()
            runtime.validate(doc!!)
            setStatus("opened ${file.name}", false)
        } catch (e: Throwable) {
            EditorLog.e(TAG, "failed to open ${file.name}", e)
            setStatus("could not open ${file.name}: ${e.message}", true)
        }
    }

    /**
     * Make [graph] the open document.
     *
     * [openDocument]'s other half: that one is "read this file", this one is "here is a graph, already built"
     * — which is what an import is. Deliberately **not** given a file, so the first save writes to the
     * document's own name under `graphsDir` rather than back over whatever the graph was made from. A `.vs`
     * file is a source, not the document; overwriting it with JSON on a reflexive Ctrl+S would be a poor
     * reward for exporting one.
     *
     * [keepId] carries the previous document's identity across a replacement, because breakpoints are stored
     * in a sidecar keyed by it. Re-importing the file you just exported should not silently disarm every
     * breakpoint you had set — with the id kept and `@id` in the file, they land back on the same nodes.
     */
    fun loadGraph(graph: dev.ziggle.vscript.model.Graph, status: String, keepId: Boolean = true) {
        runtime.stop()
        // Whatever the new document is, the old document's line table does not describe it. Importing
        // from text puts a fresh one back immediately; every other caller leaves it null, which is the
        // honest answer for a graph that has no source.
        textSpans = null
        // Same rule, one step further: the source that was loaded is not this document's source. Left set,
        // opening a canvas graph would run whichever `.vs` happened to be imported before it.
        textScript = null
        val previous = doc?.id
        doc = EditorDoc(graph).also { d ->
            if (keepId && previous != null) d.id = previous
            // Never been saved as a document, whatever it was read from — so the title carries its dot and
            // closing without saving prompts, exactly as a hand-built graph does.
            d.markDirty()
        }
        breakpointStore.load(doc!!.id, runtime.breakpoints)
        canvas.reset()
        runtime.validate(doc!!)
        setStatus(status, false)
    }

    /**
     * Rename the open document, and its file with it.
     *
     * Written under the new name and the old file removed only once that succeeded, so a failed write
     * cannot leave the script with no copy on disk at all.
     */
    fun renameDocument(name: String) {
        val d = doc ?: return
        val old = d.file
        d.name = name
        d.markDirty()
        val target = File(EditorDoc.graphsDir(), "$name.json")
        if (old == null) {
            setStatus("named $name", false)
            return
        }
        try {
            d.save(target)
            if (old.absolutePath != target.absolutePath) old.delete()
            refreshFiles()
            setStatus("renamed to $name", false)
        } catch (e: Throwable) {
            EditorLog.e(TAG, "rename failed", e)
            setStatus("rename failed: ${e.message}", true)
        }
    }

    fun saveDocument() {
        val d = doc ?: return
        try {
            val target = d.file ?: File(EditorDoc.graphsDir(), "${d.name}.json")
            d.save(target)
            // Breakpoint edits made in the panel (enable, hit count) are flushed here rather than on every
            // keystroke — the sidecar is cheap to write but not free, and this is the natural moment.
            breakpointStore.save(breakpointKey() ?: d.id, runtime.breakpoints)
            refreshFiles()
            setStatus("saved ${target.name}", false)
        } catch (e: Throwable) {
            EditorLog.e(TAG, "save failed", e)
            setStatus("save failed: ${e.message}", true)
        }
    }

    /**
     * Keyboard shortcuts.
     *
     * Gated on no text field having focus, or Ctrl+Z while renaming a comment would undo the graph instead
     * of the text.
     */
    private fun shortcuts(d: EditorDoc) {
        // The canvas has its own text fields, which ImGui knows nothing about — without asking it too, a
        // backtick typed into a comment would toggle the console instead of appearing in the note.
        if (ImGui.getIO().wantTextInput || canvas.keyboardCaptured || EditorKeyboard.busy) return
        // The universal console key, and it costs nothing to bind.
        if (ImGui.isKeyPressed(imgui.flag.ImGuiKey.GraveAccent)) panel.toggle()
        // The transport keys everybody already knows. Only meaningful while stopped, and harmless otherwise.
        if (dbg.isPaused) {
            if (ImGui.isKeyPressed(imgui.flag.ImGuiKey.F5)) dbg.resume()
            if (ImGui.isKeyPressed(imgui.flag.ImGuiKey.F10)) dbg.stepOver()
            if (ImGui.isKeyPressed(imgui.flag.ImGuiKey.F11)) {
                if (ImGui.getIO().keyShift) dbg.stepOut() else dbg.stepInto()
            }
            if (ImGui.isKeyPressed(imgui.flag.ImGuiKey.F12)) dbg.stepIntoData()
        }
        val ctrl = ImGui.getIO().keyCtrl
        if (!ctrl) return
        if (ImGui.isKeyPressed(imgui.flag.ImGuiKey.Z)) if (ImGui.getIO().keyShift) redo(d) else undo(d)
        if (ImGui.isKeyPressed(imgui.flag.ImGuiKey.Y)) redo(d)
        if (ImGui.isKeyPressed(imgui.flag.ImGuiKey.S)) saveDocument()
    }

    /**
     * One step back.
     *
     * A step may restore the document, the viewport, or both — the canvas decides what to do with it. Only
     * a step that actually changed the graph re-runs validation; undoing a pan has nothing to re-check.
     */
    private fun undo(d: EditorDoc) = applyStep(d, d.undo(canvas.view()), "undo")

    private fun redo(d: EditorDoc) = applyStep(d, d.redo(canvas.view()), "redo")

    private fun applyStep(d: EditorDoc, step: EditorDoc.Step?, label: String) {
        if (step == null) return
        canvas.applyStep(step)
        if (step.graphChanged) runtime.validate(d)
        setStatus(label, false)
    }

    private fun setStatus(text: String, bad: Boolean) {
        status = text
        statusBad = bad
    }

    companion object {
        private const val TAG = "VScript"

        private const val FILE_MENU = "##vs-file-menu"
        private const val DOC_MENU = "##vs-doc-menu"
        private const val SETTINGS_MENU = "##vs-settings-menu"

        private const val NAME_POPUP = "##vs-name-popup"

        /** Longest status the pill shows inline; the rest is a tooltip, so the bar never reflows. */
        private const val STATUS_CHARS = 26

    }
}
