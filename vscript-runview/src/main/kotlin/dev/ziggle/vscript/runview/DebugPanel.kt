package dev.ziggle.vscript.runview

import dev.ziggle.imgui.PanelBits
import dev.ziggle.vscript.runtime.EditorDoc
import imgui.ImDrawList
import imgui.ImGui
import imgui.flag.ImDrawFlags
import imgui.flag.ImGuiMouseButton
import imgui.flag.ImGuiMouseCursor
import imgui.flag.ImGuiWindowFlags
import dev.ziggle.imgui.Fonts
import dev.ziggle.imgui.Theme
import dev.ziggle.vscript.runtime.Context
import dev.ziggle.vscript.runtime.DebugSurface
import dev.ziggle.vscript.runtime.Variable
import dev.ziggle.imgui.PanelBits.HEADER_H
import dev.ziggle.imgui.PanelBits.MUTED
import dev.ziggle.imgui.PanelBits.PAD
import dev.ziggle.imgui.PanelBits.ROW_H
import dev.ziggle.vscript.log.ScriptLog
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.vm.FiberState

/**
 * The bottom panel: call stack, variables, watch, console and breakpoints, sharing one drawer.
 *
 * They share it because they are all answers to "what is my script doing", asked at different distances,
 * and because giving each its own strip would spend the canvas twice over. The console is a tab rather than
 * a special case — the only thing it does differently is own its own filters.
 *
 * **Its own child window.** Drawn as loose items in the panel the drawer advanced the layout cursor past
 * the bottom of the region, which gave the whole panel a scrollbar for space nobody could see; a child is
 * bounded by construction.
 *
 * **Collapses to its own header bar** rather than to nothing, so the tabs stay reachable and the counts
 * stay readable without opening anything.
 */
class DebugPanel(catalog: NodeCatalog) {

    enum class Tab(val label: String) {
        STACK("Call stack"),
        VARIABLES("Variables"),
        WATCH("Watch"),
        CONSOLE("Console"),
        BREAKPOINTS("Breakpoints"),
    }

    val console = ConsoleDrawer(catalog)

    var open: Boolean = false
        private set

    var tab: Tab = Tab.CONSOLE
        private set

    /**
     * Where this panel's height is remembered between sessions — supplied, not reached for.
     *
     * **The run views belong to neither surface and to no particular host**, which means they cannot go
     * looking in an editor's preferences file. This read `EditorSettings.consoleHeight` directly, and that
     * object imports the canvas, the run views and the runtime — so a run view reading it was reaching
     * around a cycle for one float, the same shape as the trace switch on `ScriptRuntime`.
     *
     * Defaults to a plain in-memory cell, which is what an embedder that has not wired persistence should
     * get: a panel you can resize, that forgets when you close it. The client points it at its own
     * settings; see `ScriptsPanel`.
     */
    interface Remembered {
        fun get(): Float
        fun set(v: Float)
    }

    private var height: Float = remembered.get()
    private var dragging = false

    /** Which context's stack and variables are being shown. -1 follows whatever stopped. */
    private var focusedContext: Int = -1
    private var focusedFrame: Int = 0

    /** Watched output pins, in the order they were added. */
    private val watches = LinkedHashSet<Pair<Int, String>>()

    /** The stop we are already looking at, so a NEW one can be told from the same one. */
    private var lastStop = -1L

    fun toggle() {
        open = !open
    }

    fun show(t: Tab) {
        tab = t
        open = true
    }

    fun openConsoleFor(nodeId: Int) {
        console.filterTo(nodeId)
        show(Tab.CONSOLE)
    }

    fun watch(nodeId: Int, pin: String) {
        watches.add(nodeId to pin)
        show(Tab.WATCH)
    }

    /** Watch a graph VARIABLE. Node id -1 marks it as one, since a variable has no node to belong to. */
    fun watchVariable(name: String) = watch(-1, name)

    fun describeNodes(doc: EditorDoc?) = console.describeNodes(doc)

    /** How tall the drawer is right now: its full height when open, its bar alone when collapsed. */
    fun height(): Float = if (open) height else HEADER_H

    /**
     * Draw the panel, anchored at [x],[y]. The caller takes [height] out of the canvas first.
     *
     * @return a node id to reveal on the canvas, or -1.
     */
    fun render(log: ScriptLog, dbg: DebugSurface, doc: EditorDoc?, x: Float, y: Float, width: Float): Int {
        // A new stop re-points the panel at whatever stopped. Which tab it lands on depends on WHY.
        //
        // An UNEXPECTED stop — a breakpoint, a fault — takes you to the call stack, because you did not
        // choose this moment and the first question is where you are. A STEP is a stop you asked for, so it
        // leaves the tab alone: you were watching the console, or a watch expression, and stepping to see
        // what it does next is the entire point. Switching there too meant every step yanked you back to
        // the stack and you had to click your way home again.
        val token = dbg.stopToken()
        if (token != lastStop && dbg.isPaused) {
            open = true
            if (dbg.stoppedReason() != dev.ziggle.vscript.runtime.StoppedReason.STEP) tab = Tab.STACK
            focusedContext = dbg.focused()?.id ?: -1
            focusedFrame = 0
        }
        lastStop = token

        val h = height()
        var reveal = -1
        ImGui.setCursorScreenPos(x, y)
        // NoScrollWithMouse so the wheel reaches whichever list is inside rather than being eaten here.
        ImGui.beginChild(
            "##vs-debug-panel", width, h, false,
            ImGuiWindowFlags.NoScrollbar or ImGuiWindowFlags.NoScrollWithMouse,
        )
        try {
            val dl = ImGui.getWindowDrawList()
            dl.addRectFilled(x, y, x + width, y + h, PanelBits.BG)
            dl.addLine(x, y, x + width, y, PanelBits.EDGE, 1f)
            if (open) resizeGrip(x, y, width)
            header(dl, log, dbg, x, y, width)
            if (open) {
                dl.addLine(x, y + HEADER_H, x + width, y + HEADER_H, PanelBits.ROW_LINE, 1f)
                reveal = body(log, dbg, doc, x, y + HEADER_H, width, h - HEADER_H)
            }
        } finally {
            ImGui.endChild()
        }
        return reveal
    }

    // ---- chrome ------------------------------------------------------------------------------------

    /**
     * The drag handle, kept just INSIDE the top edge.
     *
     * Straddling the boundary would read better, but the strip above belongs to the canvas's child window,
     * and a child sits above its parent for hover — half the handle would silently do nothing.
     */
    private fun resizeGrip(x: Float, y: Float, width: Float) {
        ImGui.setCursorScreenPos(x, y)
        ImGui.invisibleButton("##vs-panel-grip", width, GRIP)
        val hovered = ImGui.isItemHovered()
        if (hovered || dragging) ImGui.setMouseCursor(ImGuiMouseCursor.ResizeNS)
        if (ImGui.isItemActive()) {
            dragging = true
            val d = ImGui.getMouseDragDelta(ImGuiMouseButton.Left, 0f)
            if (d.y != 0f) {
                height = (height - d.y).coerceIn(MIN_H, MAX_H)
                ImGui.resetMouseDragDelta(ImGuiMouseButton.Left)
            }
        } else if (dragging) {
            dragging = false
            // Written once, on release: persisting per frame would hammer the file for a single drag.
            remembered.set(height)
        }
        if (hovered || dragging) ImGui.getWindowDrawList().addLine(x, y, x + width, y, Theme.ACCENT, 2f)
    }

    private fun header(dl: ImDrawList, log: ScriptLog, dbg: DebugSurface, x: Float, y: Float, width: Float) {
        var cx = x + PAD

        // The caret is a toggle on its own; the tabs both select and expand, so clicking one while
        // collapsed does the obvious thing rather than requiring two gestures.
        if (PanelBits.iconButton(
                dl, "##vs-panel-toggle", cx, y,
                PanelBits.icon(if (open) Fonts.CHEVRON_DOWN else Fonts.CHEVRON_UP),
                if (open) "Hide  (`)" else "Show  (`)",
            )
        ) toggle()
        cx += PanelBits.ICON + 8f

        for (t in Tab.values()) {
            val count = when (t) {
                Tab.BREAKPOINTS -> dbg.breakpoints.size.takeIf { it > 0 }
                Tab.WATCH -> watches.size.takeIf { it > 0 }
                Tab.CONSOLE -> log.counts().error.takeIf { it > 0 }
                else -> null
            }
            val text = if (count == null) t.label else "${t.label} $count"
            val col = if (t == Tab.CONSOLE && count != null) PanelBits.ERROR else Theme.TEXT
            cx += PanelBits.pill(dl, "##vs-tab-${t.name}", cx, y, text, tab == t, col) { show(t) }
        }

        // Contextual right-hand side: the transport while execution is held, otherwise whatever the
        // selected tab wants there.
        val reason = dbg.stoppedReason()
        if (reason != null) transport(dl, dbg, reason, x, y, width)
        else if (tab == Tab.CONSOLE) console.header(dl, log, cx + 8f, y, width - (cx + 8f - x))
    }

    /**
     * Continue and the step commands, on the drawer's own header.
     *
     * They live here rather than in the top toolbar because this is where the thing they operate on is: the
     * call stack, the frame you have selected, the values in it. Putting the transport at the top of the
     * window and the state at the bottom means looking in one place and reaching in another for every step.
     *
     * Always visible while stopped, even collapsed — the header bar is drawn either way, so being paused
     * never leaves you without the controls for it.
     */
    private fun transport(dl: ImDrawList, dbg: DebugSurface, reason: dev.ziggle.vscript.runtime.StoppedReason, x: Float, y: Float, width: Float) {
        var rx = x + width - PAD

        rx -= PanelBits.ICON
        if (PanelBits.iconButton(dl, "##vs-dbg-stop", rx, y, PanelBits.icon(Fonts.STOP), "Stop the script")) dbg.stop()
        rx -= PanelBits.ICON + 8f

        // The second granularity — exec pins push, data pins pull, and this is the command that walks the
        // pull instead of running it silently.
        rx -= PanelBits.ICON
        if (PanelBits.iconButton(dl, "##vs-step-data", rx, y, PanelBits.icon(Fonts.STEP_DATA), "Step into the data pull  (F12)")) dbg.stepIntoData()
        rx -= PanelBits.ICON + 2f
        if (PanelBits.iconButton(dl, "##vs-step-out", rx, y, PanelBits.icon(Fonts.STEP_OUT), "Step out  (Shift+F11)")) dbg.stepOut()
        rx -= PanelBits.ICON + 2f
        if (PanelBits.iconButton(dl, "##vs-step-into", rx, y, PanelBits.icon(Fonts.STEP_INTO), "Step into a subgraph  (F11)")) dbg.stepInto()
        rx -= PanelBits.ICON + 2f
        if (PanelBits.iconButton(dl, "##vs-step-over", rx, y, PanelBits.icon(Fonts.STEP_OVER), "Step to the next node  (F10)")) dbg.stepOver()

        rx -= PanelBits.actionWidth(PanelBits.icon(Fonts.PLAY), "Continue") + 8f
        if (PanelBits.action(dl, "##vs-continue", rx, y, PanelBits.icon(Fonts.PLAY), "Continue", Theme.ACCENT)) dbg.resume()

        // The banner sits to the LEFT of the controls, so the sentence reads into the buttons that answer
        // it: "paused at Divide — continue, or step".
        val node = dbg.focused()?.nodeId ?: -1
        val text = "${reason.name.lowercase()} at ${console.titleOf(node)}"
        rx -= ImGui.calcTextSize(text).x + 14f
        dl.addCircleFilled(rx - 10f, y + HEADER_H * 0.5f, 3.5f, PanelBits.PAUSED, 12)
        dl.addText(rx, y + (HEADER_H - ImGui.getTextLineHeight()) * 0.5f, PanelBits.PAUSED, text)
    }

    // ---- bodies ------------------------------------------------------------------------------------

    private fun body(log: ScriptLog, dbg: DebugSurface, doc: EditorDoc?, x: Float, y: Float, width: Float, h: Float): Int = when (tab) {
        Tab.CONSOLE -> console.body(log, x, y, width, h)
        Tab.STACK -> stackTab(dbg, x, y, width, h)
        Tab.VARIABLES -> variablesTab(dbg, doc, x, y, width, h)
        Tab.WATCH -> watchTab(dbg, x, y, width, h)
        Tab.BREAKPOINTS -> breakpointsTab(dbg, x, y, width, h)
    }

    /**
     * Contexts first, then the frames of the selected one.
     *
     * A graph with two Start nodes has two independent execution contexts, so "paused" is not a global
     * state — it is a property of one of them. That is why this reads like a real debugger's threads panel
     * rather than a single stack: the list of contexts is the first question, the stack the second.
     */
    private fun stackTab(dbg: DebugSurface, x: Float, y: Float, width: Float, h: Float): Int {
        var reveal = -1
        list("##vs-stack", x, y, width, h) { dl, top, rowW, canHover ->
            var row = 0
            val contexts = dbg.contexts()
            if (contexts.isEmpty()) {
                empty(dl, x, top, "Not running. Press Run, or set a breakpoint and run.")
                return@list
            }
            val focus = focusedContext.takeIf { id -> contexts.any { it.id == id } } ?: contexts.firstOrNull()?.id ?: -1
            for (c in contexts) {
                val ry = top + row++ * ROW_H
                if (contextRow(dl, c, x, ry, rowW, canHover, c.id == focus)) {
                    focusedContext = c.id
                    focusedFrame = 0
                    reveal = c.nodeId
                }
                if (c.id != focus) continue
                for (f in dbg.stackTrace(c.id)) {
                    val fy = top + row++ * ROW_H
                    val selected = f.index == focusedFrame
                    if (frameRow(dl, f.nodeId, f.activation, f.index, x, fy, rowW, canHover, selected)) {
                        focusedFrame = f.index
                        reveal = f.nodeId
                    }
                }
            }
            ImGui.setCursorScreenPos(x, top)
            ImGui.dummy(1f, row * ROW_H)
        }
        return reveal
    }

    private fun contextRow(dl: ImDrawList, c: Context, x: Float, y: Float, width: Float, canHover: Boolean, selected: Boolean): Boolean {
        val hovered = rowBg(dl, x, y, width, canHover, selected)
        val ty = y + (ROW_H - ImGui.getTextLineHeight()) * 0.5f
        val dot = when {
            c.isPaused -> PanelBits.PAUSED
            c.state == FiberState.FAILED -> PanelBits.ERROR
            c.state == FiberState.DONE -> MUTED
            else -> Theme.OK
        }
        dl.addCircleFilled(x + PAD + 3f, y + ROW_H * 0.5f, 3.5f, dot, 12)
        var cx = x + PAD + 14f
        cx += PanelBits.label(dl, cx, y, console.titleOf(c.entryNodeId), Theme.TEXT, ROW_H) + 8f
        val note = "context ${c.id}" + (c.error?.let { " · $it" } ?: "")
        PanelBits.label(dl, cx, y, note, if (c.error != null) PanelBits.ERROR_TEXT else MUTED, ROW_H)
        return hovered && ImGui.isMouseClicked(ImGuiMouseButton.Left)
    }

    private fun frameRow(dl: ImDrawList, nodeId: Int, activation: Int, depth: Int, x: Float, y: Float, width: Float, canHover: Boolean, selected: Boolean): Boolean {
        val hovered = rowBg(dl, x, y, width, canHover, selected)
        var cx = x + PAD + 20f + depth * 14f
        cx += PanelBits.label(dl, cx, y, console.titleOf(nodeId), console.colorOf(nodeId), ROW_H) + 8f
        PanelBits.label(dl, cx, y, "activation $activation", MUTED, ROW_H)
        return hovered && ImGui.isMouseClicked(ImGuiMouseButton.Left)
    }

    /**
     * Live values, when execution is stopped.
     *
     * Inspection only. Declaring variables belongs in the outline sidebar with the rest of the graph's
     * structure — this drawer is for what the script is doing, not for what it is made of, and putting
     * authoring here meant editing the shape of a graph in a panel you open to debug it.
     *
     * The graph is the better variables panel anyway, since every value in scope is on screen with a wire
     * attached. This exists for the two things the canvas cannot show: graph variables, which have no wire,
     * and values whose node is off screen.
     */
    private fun variablesTab(dbg: DebugSurface, doc: EditorDoc?, x: Float, y: Float, width: Float, h: Float): Int {
        var reveal = -1
        val ctx = focusedContextOr(dbg)
        list("##vs-vars", x, y, width, h) { dl, top, rowW, canHover ->
            if (ctx == null) {
                empty(dl, x, top, "Values are captured when execution stops. Run to a breakpoint to inspect them.")
                return@list
            }
            var row = 0
            for (scope in dbg.scopes(ctx.id, focusedFrame)) {
                val hy = top + row++ * ROW_H
                PanelBits.label(dl, x + PAD, hy, scope.name.uppercase(), PanelBits.STAMP, ROW_H)
                for (v in scope.variables) {
                    val ry = top + row++ * ROW_H
                    if (variableRow(dl, v, x, ry, rowW, canHover) && v.nodeId >= 0) watch(v.nodeId, v.name)
                }
            }
            if (row == 0) empty(dl, x, top, "This frame has no named values.")
            ImGui.setCursorScreenPos(x, top)
            ImGui.dummy(1f, row * ROW_H)
        }
        return reveal
    }

    private fun variableRow(dl: ImDrawList, v: Variable, x: Float, y: Float, width: Float, canHover: Boolean): Boolean {
        val hovered = rowBg(dl, x, y, width, canHover, false)
        var cx = x + PAD + 14f
        if (v.nodeId >= 0) {
            cx += PanelBits.label(dl, cx, y, console.titleOf(v.nodeId), console.colorOf(v.nodeId), ROW_H) + 6f
            cx += PanelBits.label(dl, cx, y, "\u00b7", PanelBits.STAMP, ROW_H) + 6f
        }
        cx += PanelBits.label(dl, cx, y, v.name, Theme.TEXT_DIM, ROW_H) + 10f
        PanelBits.label(dl, cx, y, v.display, Theme.TEXT, ROW_H)
        if (hovered && v.nodeId >= 0) {
            val hint = "+ watch"
            PanelBits.label(dl, x + width - ImGui.calcTextSize(hint).x - PAD, y, hint, MUTED, ROW_H)
            return ImGui.isMouseClicked(ImGuiMouseButton.Left)
        }
        return false
    }

    private fun watchTab(dbg: DebugSurface, x: Float, y: Float, width: Float, h: Float): Int {
        var reveal = -1
        val ctx = focusedContextOr(dbg)
        list("##vs-watch", x, y, width, h) { dl, top, rowW, canHover ->
            if (watches.isEmpty()) {
                empty(dl, x, top, "Nothing watched. Click a value in the Variables tab to add it here.")
                return@list
            }
            var row = 0
            var drop: Pair<Int, String>? = null
            for ((nodeId, pin) in watches) {
                val ry = top + row++ * ROW_H
                val hovered = rowBg(dl, x, ry, rowW, canHover, false)
                var cx = x + PAD + 14f
                if (nodeId >= 0) {
                    cx += PanelBits.label(dl, cx, ry, console.titleOf(nodeId), console.colorOf(nodeId), ROW_H) + 6f
                }
                cx += PanelBits.label(dl, cx, ry, pin, Theme.TEXT_DIM, ROW_H) + 10f
                val v = ctx?.let { c ->
                    if (nodeId >= 0) dbg.valueOf(c.id, nodeId, pin)
                    else dbg.scopes(c.id, focusedFrame).firstOrNull { it.name == "Variables" }
                        ?.variables?.firstOrNull { it.name == pin }
                }
                PanelBits.label(dl, cx, ry, v?.display ?: "—", if (v == null) PanelBits.STAMP else Theme.TEXT, ROW_H)
                if (hovered) {
                    val hint = PanelBits.icon(Fonts.CLOSE)
                    PanelBits.label(dl, x + rowW - ImGui.calcTextSize(hint).x - PAD, ry, hint, MUTED, ROW_H)
                    if (ImGui.isMouseClicked(ImGuiMouseButton.Left)) drop = nodeId to pin
                    if (ImGui.isMouseClicked(ImGuiMouseButton.Right)) reveal = nodeId
                }
            }
            drop?.let { watches.remove(it) }
            ImGui.setCursorScreenPos(x, top)
            ImGui.dummy(1f, row * ROW_H)
        }
        return reveal
    }

    private fun breakpointsTab(dbg: DebugSurface, x: Float, y: Float, width: Float, h: Float): Int {
        var reveal = -1
        list("##vs-bps", x, y, width, h) { dl, top, rowW, canHover ->
            val entries = dbg.breakpoints.entries()
            if (entries.isEmpty()) {
                empty(dl, x, top, "No breakpoints. Click the gutter beside a node, or press F9 with it selected.")
                return@list
            }
            var row = 0
            var drop = -1
            for ((nodeId, e) in entries) {
                val ry = top + row++ * ROW_H
                val hovered = rowBg(dl, x, ry, rowW, canHover, false)
                // The dot is the enable toggle — the same shape as the marker on the canvas, so the two
                // read as the same object rather than as a list that happens to mention it.
                ImGui.setCursorScreenPos(x + PAD, ry)
                if (ImGui.invisibleButton("##vs-bp-$nodeId", 16f, ROW_H)) e.enabled = !e.enabled
                val col = if (e.enabled) PanelBits.ERROR else PanelBits.STAMP
                if (e.enabled) dl.addCircleFilled(x + PAD + 6f, ry + ROW_H * 0.5f, 4.5f, col, 12)
                else dl.addCircle(x + PAD + 6f, ry + ROW_H * 0.5f, 4.5f, col, 12, 1.5f)

                var cx = x + PAD + 24f
                cx += PanelBits.label(dl, cx, ry, console.titleOf(nodeId), console.colorOf(nodeId), ROW_H) + 10f
                val cond = if (e.hitCount > 0) "after ${e.hitCount} hits" else "every hit"
                cx += PanelBits.label(dl, cx, ry, cond, MUTED, ROW_H) + 8f
                PanelBits.label(dl, cx, ry, "· ${e.hits} so far", PanelBits.STAMP, ROW_H)

                if (hovered) {
                    var rx = x + rowW - PAD - 16f
                    if (miniButton(dl, "##vs-bp-del-$nodeId", rx, ry, PanelBits.icon(Fonts.CLOSE))) drop = nodeId
                    rx -= 20f
                    if (miniButton(dl, "##vs-bp-up-$nodeId", rx, ry, "+")) e.hitCount++
                    rx -= 20f
                    if (miniButton(dl, "##vs-bp-dn-$nodeId", rx, ry, "−")) e.hitCount = (e.hitCount - 1).coerceAtLeast(0)
                    rx -= 22f
                    if (miniButton(dl, "##vs-bp-go-$nodeId", rx, ry, PanelBits.icon(Fonts.TARGET))) reveal = nodeId
                }
            }
            if (drop >= 0) dbg.breakpoints.remove(drop)
            ImGui.setCursorScreenPos(x, top)
            ImGui.dummy(1f, row * ROW_H)
        }
        return reveal
    }

    // ---- shared row furniture ----------------------------------------------------------------------

    private inline fun list(
        id: String,
        x: Float,
        y: Float,
        width: Float,
        h: Float,
        body: (dl: ImDrawList, top: Float, rowW: Float, canHover: Boolean) -> Unit,
    ) {
        ImGui.setCursorScreenPos(x, y)
        ImGui.beginChild(id, width, h, false)
        try {
            body(ImGui.getWindowDrawList(), ImGui.getCursorScreenPosY(), ImGui.getContentRegionAvailX(), ImGui.isWindowHovered())
        } finally {
            ImGui.endChild()
        }
    }

    private fun rowBg(dl: ImDrawList, x: Float, y: Float, width: Float, canHover: Boolean, selected: Boolean): Boolean {
        val hovered = canHover && ImGui.isMouseHoveringRect(x, y, x + width, y + ROW_H)
        if (selected) dl.addRectFilled(x, y, x + width, y + ROW_H, PanelBits.ROW_SELECTED)
        else if (hovered) dl.addRectFilled(x, y, x + width, y + ROW_H, Theme.GHOST_REST)
        return hovered
    }

    private fun miniButton(dl: ImDrawList, id: String, x: Float, y: Float, glyph: String): Boolean {
        ImGui.setCursorScreenPos(x, y)
        val clicked = ImGui.invisibleButton(id, 16f, ROW_H)
        val hovered = ImGui.isItemHovered()
        if (hovered) dl.addRectFilled(x, y + 2f, x + 16f, y + ROW_H - 2f, Theme.GHOST_HOVER, 3f, ImDrawFlags.RoundCornersAll)
        val ts = ImGui.calcTextSize(glyph)
        dl.addText(x + (16f - ts.x) * 0.5f, y + (ROW_H - ts.y) * 0.5f, if (hovered) Theme.TEXT else MUTED, glyph)
        return clicked
    }

    /** What a tab says when it has nothing — an instruction, not a blank. */
    private fun empty(dl: ImDrawList, x: Float, y: Float, text: String) {
        dl.addText(x + PAD, y + (ROW_H - ImGui.getTextLineHeight()) * 0.5f, PanelBits.STAMP, text)
    }

    private fun focusedContextOr(dbg: DebugSurface): Context? =
        dbg.contexts().firstOrNull { it.id == focusedContext } ?: dbg.focused()

    companion object {
        const val MIN_H = 90f
        const val MAX_H = 700f
        const val DEFAULT_H = 220f
        private const val GRIP = 8f

        /**
         * Where this panel's height is remembered — see [Remembered].
         *
         * Defaults to an in-memory cell seeded from [DEFAULT_H], which is what an embedder that has not
         * wired persistence should get: a panel you can resize, that forgets when you close it.
         */
        var remembered: Remembered = object : Remembered {
            private var v = DEFAULT_H
            override fun get() = v
            override fun set(value: Float) { v = value }
        }
    }
}
