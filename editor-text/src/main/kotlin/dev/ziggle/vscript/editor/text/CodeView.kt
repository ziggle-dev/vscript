package dev.ziggle.vscript.editor.text

import dev.ziggle.imgui.TextEdit
import dev.ziggle.imgui.TextEditState
import dev.ziggle.imgui.TextFilter
import dev.ziggle.imgui.TextLayout
import dev.ziggle.imgui.EditorKeyboard
import imgui.ImGui
import imgui.flag.ImGuiKey
import imgui.flag.ImGuiMouseButton
import dev.ziggle.vscript.editor.host.EditorHost
import dev.ziggle.imgui.TextPaint
import dev.ziggle.imgui.Theme
import dev.ziggle.vscript.model.NodeCatalog

/**
 * The code tab: the open script as `.vs` source, editable.
 *
 * **Almost none of this is an editor.** Caret, selection, word motion and the clipboard are
 * [TextEditState] and [TextEdit], written for the canvas's own text fields and reused whole; colours
 * are [CodeHighlighter], which is a mapping over the compiler's lexer; parsing and diagnostics are
 * [CodeBuffer]. What is left here — and all that is here — is a gutter, a viewport and a paint loop.
 *
 * **No word wrap.** Code is laid out by its author and a wrapped line makes the gutter lie about which
 * line you are on. The layout is asked for an effectively infinite width so one source line is one row.
 */
class CodeView(
    private val catalog: NodeCatalog,
    imports: dev.ziggle.vscript.text.TextSource = dev.ziggle.vscript.text.TextSource.NONE,
) {

    val buffer = CodeBuffer(catalog, imports)
    private val edit = TextEditState()

    /** Recomputed only when the text changes — lexing every frame would be wasteful and pointless. */
    private var runs: List<CodeHighlighter.Run> = emptyList()
    private var runsFor: String = "\u0000"


    private var focused = false

    /** Undo/redo for the buffer. The document's own history is a different thing and stays untouched. */
    private val history = TextHistory()

    /** True while the left button is down after pressing inside the text — a selection being dragged. */
    private var dragging = false


    /**
     * Where the caret is, as an offset into the buffer.
     *
     * Read-only and exposed deliberately: it is what an interaction test asserts against after clicking
     * something, and there is no other way to ask "did that click land where it should have" without
     * reaching into the widget's private state.
     */
    val caretOffset: Int get() = edit.caret

    /** Whether anything is selected — an interaction test's way of asking "did that click also drag". */
    val hasSelection: Boolean get() = edit.hasSelection

    /** Show [source]. The text surface opens a `.vs`, not a projection of a canvas document. */
    fun open(source: String) {
        buffer.load(source)
        edit.set(buffer.text)
        history.reset(edit.text, edit.caret)
    }

    /** True when the buffer differs from the document, so the panel can warn before switching away. */
    val dirty: Boolean get() = buffer.dirty

    fun draw(x: Float, y: Float, w: Float, h: Float) {
        val dl = ImGui.getWindowDrawList()
        val fontSize = ImGui.getFontSize().toFloat()
        val lh = fontSize * TextEdit.LINE_SPACING
        val barH = lh + 12f
        val codeY = y + barH
        val codeH = (h - barH).coerceAtLeast(lh)

        bar(dl, x, y, w, barH)

        // The sidebar takes its width off the left before anything else measures. Its rows are the code's
        // own line height so the two columns read as one surface rather than two panes.
        val sideW = if (sidebar.visible) (w * 0.22f).coerceIn(150f, 260f) else 0f
        if (sideW > 0f) {
            sidebar.outline = outline()
            sidebar.draw(x, codeY, sideW, codeH, lh)
        }
        val cx = x + sideW
        val cw = w - sideW

        // Gutter width from the widest line number actually present, so a 12-line script does not reserve
        // room for a 4-digit one. The count comes from the buffer's index, rebuilt on EDIT -- this used to
        // be a full scan of the document once per frame to answer a question that only changes on a
        // keystroke.
        val lineCount = buffer.lines.lineCount
        val gutterW = ImGui.calcTextSize("$lineCount").x + 18f

        dl.addRectFilled(cx, codeY, cx + cw, codeY + codeH, Theme.col(0x14, 0x16, 0x1D))
        dl.addRectFilled(cx, codeY, cx + gutterW, codeY + codeH, Theme.col(0x10, 0x12, 0x18))

        val layout = layoutOf(fontSize)

        gutterWidth = gutterW
        gutter(cx, codeY, gutterW, codeH, lh)
        input(cx + gutterW, codeY, cw - gutterW, codeH, layout)
        paint(dl, cx, codeY, cw, codeH, gutterW, layout, fontSize, lh)
        navigate(cx + gutterW, codeY, cw - gutterW, codeH, layout, lh)
        usagesPopup(cx + gutterW, codeY, cw - gutterW, lh)
    }

    /**
     * The open document's shape, for the structure view.
     *
     * From `buffer.meaning`, which `analyse` keeps even while the file does not compile — so the structure
     * view lists what parsed rather than emptying itself on every keystroke.
     */
    private fun outline(): Outline {
        val program = buffer.meaning?.document ?: return Outline(emptyList())
        return Outline.of(program, buffer.lines)
    }

    /**
     * The line layout, measured with **the same function that paints it**.
     *
     * `TextEdit.layout` defaults to the canvas's own text measurement, which is a different font at a
     * different size from the one this view draws with — and a layout that measures differently from the
     * painter puts the caret in the wrong place. The symptom is precise and misleading: clicks near the end
     * of a line land one character early, so the last character of a line cannot be reached or deleted.
     *
     * `1e6f` for the width rather than `Float.MAX_VALUE`, which overflows the wrap arithmetic. A width no
     * line can reach means "never wrap", so one source line is one row and the gutter can be trusted.
     */
    private fun layoutOf(fontSize: Float) =
        TextEdit.layout(edit.text, maxWidth = 1e6f, fontSize = fontSize) { ImGui.calcTextSize(it).x }

    // ---- the bar -------------------------------------------------------------------------------------

    private fun bar(dl: imgui.ImDrawList, x: Float, y: Float, w: Float, h: Float) {
        dl.addRectFilled(x, y, x + w, y + h, Theme.col(0x18, 0x1B, 0x24))
        val errs = buffer.errors.size
        val warns = buffer.warnings.size
        val status = when {
            errs > 0 -> "$errs error(s)" to Theme.col(0xE0, 0x5A, 0x5A)
            warns > 0 -> "$warns warning(s)" to Theme.WARN
            buffer.dirty -> "edited" to Theme.TEXT_DIM
            else -> "no problems" to Theme.TEXT_DIM
        }
        dl.addText(x + 10f, y + 6f, status.second, status.first)
    }

    /**
     * Breakpoints in the gutter, by LINE.
     *
     * This used to say "not yet". A click here once armed a breakpoint on the NODE the line was written
     * on, which needed the printer's node-to-line mapping and therefore needed the text to be a projection
     * of a canvas document. It is not: a `.vs` is the document.
     *
     * The anchor is the text front end's own `Sites`, as that note said it should be — but a **line** is
     * what is stored, not a site id. `Sites.idOf` hands out `next++` over AST identity, so the ids in the
     * buffer's own compile are not the ids in the compilation that eventually runs, and one stored from
     * here would be a breakpoint that never fires. [LineBreakpoints] resolves lines against whichever
     * compilation is about to run, which is the only moment the mapping is true.
     *
     * A line with no site cannot be armed at all — see [CodeBuffer.armableLines]. That is the same
     * principle the old note was defending: offering a breakpoint that lands somewhere else is worse than
     * offering none.
     */
    val breakpoints = LineBreakpoints()

    /**
     * The column beside the code: the workspace's documents, or the shape of this one.
     *
     * Its width is fixed rather than draggable for now — a splitter is a real interaction (a hit zone, a
     * cursor, a remembered value) and this reads badly enough at the wrong width to be worth doing
     * properly rather than approximately.
     */
    val sidebar = CodeSidebar()

    /** Search everywhere, on a double-tap of Shift. Drawn over everything, so it is not part of [draw]. */
    val search = SymbolSearch()

    /**
     * Where the documents are. Set by the host; until then the tree is empty and search finds nothing.
     *
     * Not derived from the open file's own folder: a document that imports `core/util` resolves it against
     * the workspace root, and a tree rooted somewhere else would show a different set of files from the
     * one the compiler is reading.
     */
    var workspace: Workspace?
        get() = sidebar.workspace
        set(value) {
            sidebar.workspace = value
            search.workspace = value
            across = value?.let { WorkspaceNavigation(it, catalog) }
        }

    /** Go to declaration and find usages ACROSS documents. Null until a host says where the workspace is. */
    private var across: WorkspaceNavigation? = null

    /** Somebody picked another document — from the tree, or from the search box. */
    var onOpenFile: ((java.io.File) -> Unit)?
        get() = sidebar.onOpenFile
        set(value) {
            sidebar.onOpenFile = value
            search.onChoose = { file, line ->
                value?.invoke(file)
                if (line > 0) goToLine(line)
            }
        }

    /** The document being edited, so the tree can mark it. */
    var currentFile: java.io.File?
        get() = sidebar.current
        set(value) { sidebar.current = value }

    init {
        sidebar.onGoToLine = { goToLine(it) }
    }

    /**
     * Put the caret at the start of [line] and bring it into view.
     *
     * The caret moves rather than only the scroll: arriving at a line and then typing should type THERE,
     * and a view that scrolled without moving the caret would send the next keystroke back where it came
     * from.
     */
    fun goToLine(line: Int) {
        val at = buffer.lines.startOf(line.coerceIn(1, buffer.lines.lineCount))
        edit.moveTo(at, extend = false)
        history.breakRun()
        focused = true
        // **Centred, not merely on screen.** The caret-follow scrolls the minimum that brings the caret
        // into view, which for a jump means landing hard against the top or bottom edge with no context
        // on the side you came from. Arriving somewhere you have never read wants the lines either side
        // of it, so the destination is put in the middle and the follow then has nothing left to do.
        centreOn = line
    }

    /** A line the next paint should centre, once it knows the viewport height. Consumed there. */
    private var centreOn = -1

    /**
     * Usages of whatever was last Ctrl+clicked on its own declaration, as a list to pick from.
     *
     * Empty when the popup is closed. Kept here rather than recomputed per frame because the list is a
     * decision the author made — clicking elsewhere should dismiss it, not silently repopulate it.
     */
    private var usages: List<Int> = emptyList()
    private var usagesElsewhere: List<WorkspaceNavigation.Location> = emptyList()
    private var usagesFor = ""
    private var usagesSelected = 0

    /**
     * The caret offset the view has already scrolled to.
     *
     * The view follows the caret when it MOVES, not while it merely sits somewhere — see the note in
     * `paint`. Without this the wheel is undone on the frame after every scroll.
     */
    private var followedCaret = -1

    // ---- navigation and hover -----------------------------------------------------------------------

    /**
     * Ctrl+click to navigate, hover to explain.
     *
     * **Ctrl, not a plain click.** A click in a text area places the caret; that is the one thing it must
     * keep doing, and an editor where clicking a name jumped somewhere would be unusable for editing. Ctrl
     * is what every editor uses for exactly this reason.
     *
     * Clicking a USE goes to the declaration. Clicking the DECLARATION lists the usages, because "go to
     * declaration" from a declaration has nowhere to go and the question being asked there is the other
     * one.
     */
    private fun navigate(x: Float, y: Float, w: Float, h: Float, layout: TextLayout, lh: Float) {
        val nav = buffer.navigation
        val inside = ImGui.isMouseHoveringRect(x, y, x + w, y + h)
        if (!inside) return

        val row = ((ImGui.getMousePosY() - y + edit.scrollY) / lh).toInt()
        if (row < 0 || row >= layout.lines.size) return
        val offset = layout.caretAt(row, ImGui.getMousePosX() - (x + PAD))
        val ctrl = ImGui.getIO().keyCtrl

        // An error under the pointer outranks documentation: if something is wrong here, that is what you
        // are hovering to find out.
        val line = row + 1
        val problems = buffer.on(line)
        val target = nav?.at(offset)

        if (ctrl && target != null) {
            // The affordance: underline what a click would act on, so Ctrl reveals what is navigable
            // rather than making the author guess and click to find out.
            underline(target.at, layout, x, y, lh)
            if (ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
                follow(target, offset)
                return
            }
        }

        when {
            problems.isNotEmpty() -> tooltip(problems.joinToString("\n") { it.message })
            target != null -> tooltip(hoverText(target))
        }
    }

    /**
     * Act on a Ctrl+click: go to the declaration, or — standing on it already — list the usages.
     *
     * **A declaration in another document is a LINE IN THAT DOCUMENT.** Jumping to it without opening the
     * file first lands on that line of the file you were already looking at, which is worse than not
     * jumping because it looks like it worked. `declarationRef` is what makes the difference visible, and
     * the open is asked of the host — this view does not decide what "open a document" means.
     */
    private fun follow(target: Navigation.Target, offset: Int) {
        usages = emptyList()
        val decl = target.declaration

        if (target.isElsewhere) {
            val ref = target.declarationRef ?: return
            val where = across?.declaration(ref, target.name) ?: return
            onOpenFile?.invoke(where.file)
            goToLine(where.line)
            return
        }

        if (decl != null && !contains(decl, offset)) {
            goToLine(decl.line)
            return
        }

        // Standing on the declaration: the question being asked is the other one. Workspace-wide when a
        // workspace is known, this document alone when it is not.
        val ref = thisDocumentRef()
        val wide = if (ref != null) across?.usages(ref, target.name).orEmpty() else emptyList()
        val found = if (wide.isNotEmpty()) wide else target.usages.map {
            WorkspaceNavigation.Location(
                sidebar.current ?: java.io.File(""), ref.orEmpty(), it.line,
                buffer.lines.textOf(buffer.text, it.line).trim(),
            )
        }

        // The declaration is in the list, and standing on it is not "a usage" for this purpose: what is
        // being asked is where else this is used.
        val elsewhere = found.filterNot { it.file == sidebar.current && it.line == (decl?.line ?: -1) }

        // **One usage goes straight there.** A list of one is a dialog asking you to confirm the only
        // answer, which is what IntelliJ avoids by jumping — and the popup earns its place only when
        // there is a choice to make.
        if (elsewhere.size == 1) {
            val only = elsewhere.first()
            if (only.file != sidebar.current && only.file.path.isNotEmpty()) onOpenFile?.invoke(only.file)
            goToLine(only.line)
            return
        }

        usagesElsewhere = elsewhere
        usages = elsewhere.map { it.line }
        usagesFor = target.name
        usagesSelected = 0
    }

    /** What this document is called, so a workspace search knows which declaration is being asked about. */
    private fun thisDocumentRef(): String? {
        val file = sidebar.current ?: return null
        return workspace?.relative(file)?.removeSuffix(".vs")
    }

    private fun contains(span: dev.ziggle.vscript.lang.Span, offset: Int): Boolean =
        offset >= span.start && offset < span.end

    /** Signature first, then the prose — the shape of the thing before the story about it. */
    private fun hoverText(t: Navigation.Target): String {
        val head = t.signature.ifBlank { t.name }
        val body = t.documentation
        return if (body.isBlank()) head else "$head\n\n$body"
    }

    private fun underline(span: dev.ziggle.vscript.lang.Span, layout: TextLayout, x: Float, y: Float, lh: Float) {
        val row = layout.lineIndexOf(span.start)
        if (row < 0 || row >= layout.lines.size) return
        val ry = y + (row + 1) * lh - edit.scrollY - 2f
        // `caretX` measures with the layout's OWN measure function -- which is the one this view paints
        // with, see `layoutOf`. Measuring with anything else drifts along the line and puts the underline
        // under the wrong characters by a margin that grows with the column.
        val x0 = x + PAD + layout.caretX(span.start)
        val x1 = x + PAD + layout.caretX(span.end)
        ImGui.getWindowDrawList().addLine(x0, ry, x1, ry, Theme.ACCENT)
    }

    /**
     * The usages list, after Ctrl+clicking a declaration.
     *
     * A plain list of lines rather than a preview of each: the question "where is this used" is answered
     * by a place, and showing the surrounding code would make a list of five usages taller than the
     * viewport. Click to go; click anywhere else to dismiss.
     */
    /**
     * The usages list: where else this is used, with the line itself as its own preview.
     *
     * **The line IS the preview.** A pane showing the surrounding code would make a list of five usages
     * taller than the viewport, and the thing that tells you whether a usage is the one you want is
     * almost always the line it is on. Rows in another document are prefixed with it; rows in the open one
     * are not, because repeating the current file on every row is noise and its absence is what makes the
     * other names stand out.
     *
     * Arrow keys and Enter work, because a list you can only click is a list you have to leave the
     * keyboard for.
     */
    private fun usagesPopup(x: Float, y: Float, w: Float, lh: Float) {
        if (usages.isEmpty()) return
        val dl = ImGui.getForegroundDrawList()
        val fs = ImGui.getFontSize().toFloat()
        val pw = (w * 0.7f).coerceIn(300f, 640f)
        val ph = lh + usages.size * lh + 8f
        val px = x + 24f
        val py = y + 24f

        dl.addRectFilled(px, py, px + pw, py + ph, Theme.col(0x1A, 0x1D, 0x26))
        dl.addRect(px, py, px + pw, py + ph, Theme.col(0x3A, 0x42, 0x54))
        dl.addText(
            px + 10f, py + (lh - fs) / 2f, Theme.TEXT_DIM,
            "${usages.size} usages of $usagesFor",
        )
        dl.addLine(px, py + lh, px + pw, py + lh, Theme.col(0x2A, 0x30, 0x3C))

        if (ImGui.isKeyPressed(ImGuiKey.DownArrow)) usagesSelected++
        if (ImGui.isKeyPressed(ImGuiKey.UpArrow)) usagesSelected--
        usagesSelected = usagesSelected.coerceIn(0, usages.size - 1)
        if (ImGui.isKeyPressed(ImGuiKey.Escape)) { usages = emptyList(); return }

        var chosen = -1
        if (ImGui.isKeyPressed(ImGuiKey.Enter) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter)) {
            chosen = usagesSelected
        }

        var dismissed = ImGui.isMouseClicked(ImGuiMouseButton.Left)
        for (i in usages.indices) {
            val ry = py + lh + 4f + i * lh
            val over = ImGui.isMouseHoveringRect(px, ry, px + pw, ry + lh)
            if (over) usagesSelected = i
            if (i == usagesSelected) dl.addRectFilled(px + 1f, ry, px + pw - 1f, ry + lh, Theme.col(0x28, 0x2F, 0x3C))

            val at = usagesElsewhere.getOrNull(i)
            val other = at != null && at.file != sidebar.current && at.file.path.isNotEmpty()
            val where = if (other) "${at!!.ref}:${usages[i]}" else "${usages[i]}"
            dl.addText(px + 10f, ry + (lh - fs) / 2f, Theme.TEXT_DIM, where)
            val body = at?.text ?: buffer.lines.textOf(buffer.text, usages[i]).trim()
            dl.addText(px + 10f + TextPaint.width("00000000"), ry + (lh - fs) / 2f, Theme.TEXT, body.take(60))

            if (over && ImGui.isMouseClicked(ImGuiMouseButton.Left)) chosen = i
        }

        if (chosen >= 0) {
            val at = usagesElsewhere.getOrNull(chosen)
            if (at != null && at.file != sidebar.current && at.file.path.isNotEmpty()) onOpenFile?.invoke(at.file)
            goToLine(usages[chosen])
            usages = emptyList()
            return
        }
        if (dismissed) usages = emptyList()
    }

    /** ImGui's own tooltip, so it clamps to the display and follows the pointer like every other one. */
    private fun tooltip(text: String) {
        if (text.isBlank()) return
        ImGui.beginTooltip()
        ImGui.pushTextWrapPos(ImGui.getFontSize() * 32f)
        ImGui.textUnformatted(text)
        ImGui.popTextWrapPos()
        ImGui.endTooltip()
    }

    /** Gutter x-extent from the last paint, so a click can tell the gutter from the text. */
    private var gutterWidth = 0f

    /**
     * A click in the gutter toggles a breakpoint on that line.
     *
     * Separate from [input] because the gutter is not the text: a click here must not move the caret or
     * take the keyboard, and a drag here is not a selection.
     */
    private fun gutter(x: Float, y: Float, gw: Float, h: Float, lh: Float) {
        if (gw <= 0f) return
        if (!ImGui.isMouseClicked(ImGuiMouseButton.Left)) return
        if (!ImGui.isMouseHoveringRect(x, y, x + gw, y + h)) return

        val row = ((ImGui.getMousePosY() - y + edit.scrollY) / lh).toInt()
        val line = row + 1
        if (line < 1 || line > buffer.lines.lineCount) return
        // Refused rather than accepted-and-dropped: a blank line, a comment or a closing brace compiles to
        // nothing that can be stopped at, and a dot that never fires is the failure this design is for.
        if (line !in buffer.armableLines) return
        breakpoints.toggle(line)
    }

    // ---- input ---------------------------------------------------------------------------------------

    private fun input(x: Float, y: Float, w: Float, h: Float, layout: TextLayout) {
        val inside = ImGui.isMouseHoveringRect(x, y, x + w, y + h)

        // Placed here rather than through `TextEdit.caretFromMouse`, which insets X and Y by the same
        // pad. This view pads X only — rows are drawn flush from the top of the viewport — so borrowing it
        // shifted every click by a pad vertically and could land a row early.
        fun place(extend: Boolean) {
            val row = ((ImGui.getMousePosY() - y + edit.scrollY) / layout.lineHeight)
                .toInt().coerceIn(0, layout.lines.size - 1)
            edit.moveTo(layout.caretAt(row, ImGui.getMousePosX() - (x + PAD)), extend)
        }

        if (inside && ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
            focused = true
            EditorKeyboard.claim(this)
            // **Ctrl belongs to navigation: a Ctrl+click neither places the caret nor starts a drag.**
            //
            // It used to do both, and the result was worse than doing nothing. The caret was placed here,
            // `navigate` then jumped to the declaration — and on the NEXT frame, with the button still
            // down, `dragging` extended a selection from the declaration back to the pointer. What that
            // looks like is the file selected from somewhere above down to where you clicked, with the
            // jump invisible underneath it. Reported as "Ctrl+click does nothing, it just selects".
            //
            // Focus is still claimed, because after navigating you want to type where you landed.
            if (!ImGui.getIO().keyCtrl) {
                dragging = true
                place(extend = ImGui.getIO().keyShift)
                // The caret went somewhere typing did not put it, so the next character starts a new undo
                // step.
                history.breakRun()
            }
        } else if (dragging && ImGui.isMouseDown(ImGuiMouseButton.Left)) {
            // Extended even once the cursor has left the box, which is what makes a drag that overshoots
            // keep selecting instead of stopping at the edge. The same rule the canvas fields follow.
            place(extend = true)
        } else if (ImGui.isMouseClicked(ImGuiMouseButton.Left) && !inside) {
            if (focused) EditorKeyboard.release(this)
            focused = false
        }
        if (!ImGui.isMouseDown(ImGuiMouseButton.Left)) dragging = false
        if (!focused) return
        // **Somebody else may hold the keyboard, and this view is drawn FIRST.** The search box claims it
        // on opening, but the workbench draws the body before the overlay — so without this the code view
        // drained every typed character on the way past and the search field received an empty string
        // every frame. Reported as the box not focusing: it was focused, and being robbed.
        if (EditorKeyboard.busy && !EditorKeyboard.holds(this)) return

        // Undo BEFORE anything else reads the keyboard: `Ctrl+Z` must not also arrive as a typed character.
        if (undoRedo()) return

        val before = edit.text
        val caretBefore = edit.caret
        // Tab indents rather than moving focus: this is a text area, and there is nowhere else to go.
        if (ImGui.isKeyPressed(ImGuiKey.Tab)) edit.insert("    ", TextFilter.ANY)
        TextEdit.handleKeys(edit, TextFilter.ANY, EditorHost.typed.drain(), layout)

        // A caret that moved without the text changing is an arrow key or a click — it ends the run so the
        // next character is its own undo step, but is not itself one.
        if (edit.text == before && edit.caret != caretBefore) history.breakRun()
        history.record(edit.text, edit.caret, edit.anchor)
        commit(before)
    }

    /**
     * Put [edit]'s text into the buffer, and move the breakpoints with it.
     *
     * One function rather than two assignments, because there are two paths that change the text —
     * typing, and undo/redo — and a breakpoint that follows an edit but not an undo would drift out of
     * place the first time somebody pressed Ctrl+Z. The old index has to be read BEFORE the assignment:
     * `buffer.text` rebuilds it, and `follow` needs both sides.
     */
    private fun commit(before: String) {
        if (edit.text == before) return
        // One assignment, so `CodeBuffer` re-parses at most once a frame no matter how much was typed.
        val wasIndexed = buffer.lines
        buffer.text = edit.text
        breakpoints.follow(TextChange.between(before, edit.text), wasIndexed, buffer.lines)
    }

    /**
     * `Ctrl+Z` / `Ctrl+Shift+Z` / `Ctrl+Y`, on the BUFFER.
     *
     * Nothing to do with the document's own history: while the code view holds the keyboard the panel's
     * shortcuts are suppressed (`EditorKeyboard.busy`), so Ctrl+Z here cannot reach through and undo a graph
     * edit instead — which would be a spectacular way to lose work.
     */
    private fun undoRedo(): Boolean {
        val io = ImGui.getIO()
        if (!io.keyCtrl) return false
        val snapshot = when {
            ImGui.isKeyPressed(ImGuiKey.Z) && io.keyShift -> history.redo()
            ImGui.isKeyPressed(ImGuiKey.Z) -> history.undo()
            ImGui.isKeyPressed(ImGuiKey.Y) -> history.redo()
            else -> return false
        } ?: return true
        val before = edit.text
        edit.set(snapshot.text)
        edit.moveTo(snapshot.caret, extend = false)
        commit(before)
        // Drain, or the keystroke that triggered this also arrives as text on the same frame.
        EditorHost.typed.drain()
        return true
    }

    // ---- paint ---------------------------------------------------------------------------------------

    private fun paint(
        dl: imgui.ImDrawList,
        x: Float, y: Float, w: Float, h: Float,
        gutterW: Float,
        layout: TextLayout,
        fontSize: Float,
        lh: Float,
    ) {
        if (runsFor != edit.text) {
            runs = CodeHighlighter.runs(edit.text)
            runsFor = edit.text
        }

        // Keep the caret on screen — **only when the caret MOVED.**
        //
        // This used to run every frame, unconditionally, with the wheel applied afterwards. The wheel
        // moved the view for exactly one frame and the next frame dragged it back to wherever the caret
        // was: scroll away from the caret and the view rubber-bands to it. Which direction it snapped
        // depended only on which side of the viewport the caret was on, which is why it read as the
        // scrolling being broken rather than as the caret being followed.
        //
        // Following on MOVEMENT is the rule that was meant: a caret that goes off screen — typed, clicked,
        // jumped to from the structure view — brings the view with it, and a view scrolled by hand stays
        // where it was put until the caret next moves.
        val caretRow = layout.lineIndexOf(edit.caret)
        if (centreOn > 0) {
            // The viewport height is only known here, which is why the request is deferred rather than
            // acted on in `goToLine`.
            edit.scrollY = ((centreOn - 1) * lh - h / 2f + lh / 2f).coerceAtLeast(0f)
            centreOn = -1
            followedCaret = edit.caret
        }
        if (edit.caret != followedCaret) {
            if (caretRow * lh < edit.scrollY) edit.scrollY = caretRow * lh
            if ((caretRow + 1) * lh > edit.scrollY + h) edit.scrollY = (caretRow + 1) * lh - h
            followedCaret = edit.caret
        }
        // Hovering is enough; focus is not. A view you are pointing at is one you can scroll, and needing
        // to click into the code first to read further down it is a rule nothing else in the editor has.
        if (ImGui.isMouseHoveringRect(x, y, x + w, y + h)) {
            edit.scrollY -= ImGui.getIO().mouseWheel * lh * 3f
        }
        // Clamped LAST, once, so neither the follow nor the wheel can leave it out of range.
        edit.scrollY = edit.scrollY.coerceIn(0f, (layout.height - h).coerceAtLeast(0f))

        dl.pushClipRect(x, y, x + w, y + h, true)
        val first = (edit.scrollY / lh).toInt().coerceAtLeast(0)
        val last = ((edit.scrollY + h) / lh).toInt().coerceAtMost(layout.lines.size - 1)
        val textX = x + gutterW + PAD

        for (i in first..last) {
            val line = layout.lines[i]
            val rowY = y + i * lh - edit.scrollY

            if (i == caretRow && focused) {
                dl.addRectFilled(x + gutterW, rowY, x + w, rowY + lh, Theme.col(0x1C, 0x20, 0x2A))
            }

            // A diagnostic on this line: a red bar in the gutter and a tint across the row. The line number
            // is what `VsText` reports, so this needs no mapping of its own.
            val marks = buffer.on(i + 1)
            if (marks.isNotEmpty()) {
                val bad = buffer.errors.any { it.span.line == i + 1 }
                val col = if (bad) Theme.col(0xE0, 0x5A, 0x5A, 0x30) else Theme.col(0xF2, 0xB1, 0x4C, 0x28)
                dl.addRectFilled(x + gutterW, rowY, x + w, rowY + lh, col)
                dl.addRectFilled(x + 2f, rowY + 3f, x + 5f, rowY + lh - 3f, if (bad) Theme.col(0xE0, 0x5A, 0x5A) else Theme.WARN)
            }

            // The breakpoint dot, before the number so the number stays readable on top of it.
            if ((i + 1) in breakpoints) {
                val cy = rowY + lh / 2f
                dl.addCircleFilled(x + 7f, cy, 4.5f, Theme.col(0xE0, 0x5A, 0x5A))
            }

            val num = "${i + 1}"
            dl.addText(
                x + gutterW - 8f - ImGui.calcTextSize(num).x, rowY + (lh - fontSize) / 2f,
                if (i == caretRow) Theme.TEXT_DIM else Theme.col(0x4A, 0x50, 0x60), num,
            )

            selection(dl, layout, i, line, textX, rowY, lh)
            row(dl, line, textX, rowY + (lh - fontSize) / 2f)
        }

        if (focused) {
            val cx = textX + layout.caretX(edit.caret)
            val cy = y + caretRow * lh - edit.scrollY
            if (((ImGui.getTime() * 2).toInt() % 2) == 0) {
                dl.addRectFilled(cx, cy + 2f, cx + 1.5f, cy + lh - 2f, Theme.TEXT)
            }
        }
        dl.popClipRect()
    }

    /** One source line, painted as coloured stretches. */
    private fun row(dl: imgui.ImDrawList, line: TextLayout.Line, x: Float, y: Float) {
        var at = line.start
        var penX = x
        fun put(from: Int, to: Int, col: Int) {
            if (to <= from) return
            val s = edit.text.substring(from, to)
            dl.addText(penX, y, col, s)
            penX += ImGui.calcTextSize(s).x
        }
        for (r in runs) {
            if (r.end <= line.start) continue
            if (r.start >= line.textEnd) break
            val from = maxOf(r.start, at)
            val to = minOf(r.end, line.textEnd)
            if (from > at) put(at, from, Theme.TEXT)
            put(from, to, colourOf(r.role))
            at = maxOf(at, to)
        }
        put(at, line.textEnd, Theme.TEXT)
    }

    private fun selection(
        dl: imgui.ImDrawList,
        layout: TextLayout,
        i: Int,
        line: TextLayout.Line,
        x: Float, rowY: Float, lh: Float,
    ) {
        if (!edit.hasSelection) return
        val from = maxOf(edit.selStart, line.start)
        val to = minOf(edit.selEnd, line.textEnd)
        if (to <= from) return
        val x0 = x + layout.caretX(from)
        val x1 = x + layout.caretX(to)
        dl.addRectFilled(x0, rowY, x1, rowY + lh, Theme.col(0x3A, 0x62, 0xB8, 0x80))
    }

    private fun colourOf(role: CodeHighlighter.Role): Int = when (role) {
        CodeHighlighter.Role.KEYWORD -> Theme.col(0xC8, 0x92, 0xFF)
        CodeHighlighter.Role.NUMBER -> Theme.col(0xB5, 0xCE, 0xA8)
        CodeHighlighter.Role.STRING -> Theme.col(0xCE, 0x91, 0x78)
        CodeHighlighter.Role.COMMENT -> Theme.col(0x6A, 0x99, 0x55)
        CodeHighlighter.Role.ANNOTATION -> Theme.col(0x9F, 0xBC, 0xFF)
        CodeHighlighter.Role.TYPE -> Theme.col(0x4E, 0xC9, 0xB0)
        CodeHighlighter.Role.OPERATOR -> Theme.col(0xA8, 0xB0, 0xC4)
        CodeHighlighter.Role.PLAIN -> Theme.TEXT
    }

    private companion object {
        const val PAD = 8f
    }
}
