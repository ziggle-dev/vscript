package dev.ziggle.vscript.editor.graph

import dev.ziggle.vscript.editor.graph.EditorSettings
import dev.ziggle.imgui.PanelBits
import dev.ziggle.vscript.editor.graph.PanelField
import dev.ziggle.vscript.runtime.Scope
import dev.ziggle.vscript.runtime.Variable
import imgui.ImDrawList
import imgui.ImGui
import imgui.flag.ImDrawFlags
import imgui.flag.ImGuiMouseButton
import imgui.flag.ImGuiMouseCursor
import imgui.flag.ImGuiWindowFlags
import dev.ziggle.imgui.Fonts
import dev.ziggle.imgui.Theme
import dev.ziggle.vscript.editor.graph.CanvasCamera
import dev.ziggle.vscript.editor.graph.CanvasRenderer
import dev.ziggle.vscript.editor.graph.CanvasWidgets
import dev.ziggle.vscript.editor.graph.NodeGeometry
import dev.ziggle.vscript.editor.graph.ScreenCamera
import dev.ziggle.vscript.editor.graph.ValueEditors
import dev.ziggle.vscript.editor.graph.ValuePicker
import dev.ziggle.vscript.editor.graph.WidgetContext
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.FunctionPin
import dev.ziggle.vscript.model.GraphFunction
import dev.ziggle.vscript.model.GraphVariable
import dev.ziggle.vscript.model.Literals
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.model.Types
import dev.ziggle.vscript.runtime.EditorDoc

/**
 * A node dragged out of the outline and released, in SCREEN coordinates.
 *
 * A top-level type rather than nested in the panel, so the canvas can accept one without importing the UI
 * that produces it — the canvas should know how to place a dropped variable, not where drops come from.
 */
class OutlineDrop(
    val variable: String?,
    val nodeType: String?,
    /** A user function dragged out of the outline: dropping it places a Call to it. */
    val function: String? = null,
    /** A declared type dragged out: dropping it places a Make, or a Split with Ctrl held. */
    val declaredType: String? = null,
    /** Ctrl was held: place a Set rather than a Get. */
    val set: Boolean,
    val screenX: Float,
    val screenY: Float,
    /** Literals to set on the created node — a host's link name on a `Link` pin, say. */
    val literals: Map<String, Any?> = emptyMap(),
)

/**
 * The outline: what this graph is *made of* — its events, its functions, its variables.
 *
 * Structure, not runtime. The drawer at the bottom answers "what is my script doing"; this answers "what
 * does my script have", and the two want different halves of the screen for the same reason a file tree
 * and a terminal do.
 *
 * The rules it is built on, each of which is a decision rather than a style:
 *
 *  - **Sections are labels, not bars.** A muted word, a count, and a `+` that appears on hover. Filled
 *    header bars turn a list of three things into five bands of chrome.
 *  - **Empty sections do not render.** A fresh graph shows almost nothing instead of a column of headings
 *    announcing what you do not have. The one exception is Functions, which renders dimmed because it is a
 *    placeholder for work that is planned — a promise is worth one line; an absence is not.
 *  - **Type is the swatch and only the swatch**, in exactly the colour the pin uses on the canvas. Putting
 *    the word beside it splits the reinforcement across two encodings and teaches neither. The word appears
 *    in the tooltip and in the picker, where it is the thing being chosen.
 *  - **The reference count is the feature.** It answers the question you opened the panel to ask. Clicking
 *    it walks the viewport through each use. Zero references gets a hollow swatch and the word *unused*, so
 *    dead declarations report themselves instead of accumulating — which in a visual language they will.
 *  - **Inline expansion, not a details pane.** A second panel is two pieces of chrome for one selection and
 *    less canvas. If a variable ever grows enough fields to need the room, that is the moment to revisit.
 */
class OutlinePanel(private val catalog: NodeCatalog) {

    /**
     * How the Imports section asks whether an alias still names anything.
     *
     * Set by the panel that owns the document store, rather than constructed here: the outline has no
     * business knowing where scripts live, and the resolver must be the SAME one the compiler uses or the
     * dot could say resolved while a run says otherwise.
     */
    var resolver: dev.ziggle.vscript.model.GraphSource? = null

    var open: Boolean = EditorSettings.outlineOpen
        private set

    private var width: Float = EditorSettings.outlineWidth
    private var resizing = false

    /** Our own field, not ImGui's — see [PanelField] for why the two must not be mixed. */
    private val filter = PanelField("outline-filter")

    /**
     * Widget state for the default-value editors, with an IDENTITY camera.
     *
     * The canvas widgets take screen geometry and consult the camera only for line weights and rounding, so
     * a camera at 1:1 makes them work unchanged in a panel. That is what lets a boolean default be the same
     * toggle here as on a node, rather than a text box that happens to accept "true" — which is what it was.
     */
    private val widgets = WidgetContext()
    private val flatCamera = ScreenCamera(CanvasCamera(), 0f, 0f)
    private var selected: String? = null

    /** Where each variable's "cycle uses" walk has got to. */
    private val cycle = HashMap<String, Int>()

    /** Set on release outside the panel; the caller hands it to the canvas and clears it. */
    var drop: OutlineDrop? = null

    /**
     * What is being dragged out of the outline, as ONE value.
     *
     * It was three parallel fields — variable, node type, function — and clearing them was three
     * assignments in a place that only had two. A function drag therefore stayed armed forever: every
     * later mouse release anywhere dropped another Call node, so the editor appeared to duplicate a node
     * whenever you dragged the mouse. One field cannot be half-cleared.
     */
    private class Drag(
        val variable: String?,
        val nodeType: String?,
        val function: String?,
        val declaredType: String? = null,
    )

    private var pendingDrag: Drag? = null

    /** Reveal a node on the canvas — the count click and the cycle action. */
    var onReveal: (Int) -> Unit = {}

    /** Add a variable to the debugger's watch list. */
    var onWatch: (String) -> Unit = {}

    private var dragArmed = false

    fun toggle() {
        open = !open
        EditorSettings.outlineOpen = open
    }

    fun width(): Float = if (open) width else 0f

    fun render(doc: EditorDoc?, x: Float, y: Float, h: Float) {
        if (!open) return
        val w = width
        ImGui.setCursorScreenPos(x, y)
        ImGui.beginChild("##vs-outline", w, h, false, ImGuiWindowFlags.NoScrollbar or ImGuiWindowFlags.NoScrollWithMouse)
        try {
            val dl = ImGui.getWindowDrawList()
            dl.addRectFilled(x, y, x + w, y + h, PanelBits.BG)
            dl.addLine(x + w - 0.5f, y, x + w - 0.5f, y + h, PanelBits.EDGE, 1f)
            header(dl, doc, x, y, w)
            dl.addLine(x, y + HEAD_H, x + w, y + HEAD_H, PanelBits.ROW_LINE, 1f)
            body(dl, doc, x, y + HEAD_H, w, h - HEAD_H)
            resizeGrip(x + w, y, h)
        } finally {
            ImGui.endChild()
        }
        dragOverlay()
    }

    // ---- chrome ------------------------------------------------------------------------------------

    private fun resizeGrip(x: Float, y: Float, h: Float) {
        ImGui.setCursorScreenPos(x - GRIP, y)
        ImGui.invisibleButton("##vs-outline-grip", GRIP, h)
        val hovered = ImGui.isItemHovered()
        if (hovered || resizing) ImGui.setMouseCursor(ImGuiMouseCursor.ResizeEW)
        if (ImGui.isItemActive()) {
            resizing = true
            val d = ImGui.getMouseDragDelta(ImGuiMouseButton.Left, 0f)
            if (d.x != 0f) {
                width = (width + d.x).coerceIn(MIN_W, MAX_W)
                ImGui.resetMouseDragDelta(ImGuiMouseButton.Left)
            }
        } else if (resizing) {
            resizing = false
            EditorSettings.outlineWidth = width
        }
        if (hovered || resizing) ImGui.getWindowDrawList().addLine(x - 1f, y, x - 1f, y + h, Theme.ACCENT, 2f)
    }

    private fun header(dl: ImDrawList, doc: EditorDoc?, x: Float, y: Float, w: Float) {
        val fy = y + (HEAD_H - FIELD_H) * 0.5f
        val fw = w - PAD * 2 - PLUS_W - 6f
        // The magnifier sits inside the field's own frame, so the field is drawn without one and the plate
        // is painted here around both.
        dl.addRectFilled(x + PAD, fy, x + PAD + fw, fy + FIELD_H, FIELD_BG, 5f, ImDrawFlags.RoundCornersAll)
        val glyph = PanelBits.icon(Fonts.SEARCH)
        val gx = x + PAD + 8f
        dl.addText(gx, fy + (FIELD_H - ImGui.getTextLineHeight()) * 0.5f, PanelBits.STAMP, glyph)
        val textX = gx + ImGui.calcTextSize(glyph).x + 4f
        filter.render(dl, textX, fy, x + PAD + fw - textX, FIELD_H, "Filter", live = true, frame = false)
        if (filter.focused) {
            dl.addRect(x + PAD, fy, x + PAD + fw, fy + FIELD_H, Theme.ACCENT, 5f, ImDrawFlags.RoundCornersAll, 1f)
        }

        val px = x + w - PAD - PLUS_W
        ImGui.setCursorScreenPos(px, fy)
        val plusHot = ImGui.isMouseHoveringRect(px, fy, px + PLUS_W, fy + FIELD_H)
        if (ImGui.invisibleButton("##vs-outline-add", PLUS_W, FIELD_H) && doc != null) ImGui.openPopup(ADD_MENU)
        dl.addRectFilled(px, fy, px + PLUS_W, fy + FIELD_H, if (plusHot) Theme.GHOST_HOVER else Theme.GHOST_REST, 5f, ImDrawFlags.RoundCornersAll)
        // The FontAwesome glyph, not the ASCII character: a typed '+' is a maths symbol drawn at text
        // weight and it reads as a typo next to a row of real icons.
        val plus = PanelBits.icon(Fonts.PLUS)
        val pw = ImGui.calcTextSize(plus)
        dl.addText(px + (PLUS_W - pw.x) * 0.5f, fy + (FIELD_H - pw.y) * 0.5f, if (plusHot) Theme.TEXT else Theme.TEXT_DIM, plus)
        addMenu(doc)
    }

    /** Everything you can create, in one place — the discoverable path. The per-section `+` is the fast one. */
    private fun addMenu(doc: EditorDoc?) {
        if (!ImGui.beginPopup(ADD_MENU)) return
        if (doc != null && ImGui.menuItem("Variable")) newVariable(doc)
        ImGui.menuItem("Event", "", false, false)
        ImGui.textDisabled("  drag one out of the list")
        if (doc != null && ImGui.menuItem("Function")) newFunction(doc)
        if (doc != null && ImGui.menuItem("Type")) newType(doc)
        if (doc != null && ImGui.menuItem("Enum")) newEnum(doc)
        ImGui.endPopup()
    }

    // ---- body --------------------------------------------------------------------------------------

    private fun body(dl: ImDrawList, doc: EditorDoc?, x: Float, y: Float, w: Float, h: Float) {
        ImGui.setCursorScreenPos(x, y)
        ImGui.beginChild("##vs-outline-body", w, h, false)
        try {
            val d2 = ImGui.getWindowDrawList()
            val top = ImGui.getCursorScreenPosY()
            val rowW = ImGui.getContentRegionAvailX()
            // A popup over the list owns the mouse; rows underneath must not also react to it.
            val canHover = ImGui.isWindowHovered() && !ValuePicker.isOpen
            widgets.beginFrame(d2, flatCamera, canHover)
            var row = 0f
            if (doc == null) {
                PanelBits.label(d2, x + PAD, top, "No script open.", PanelBits.STAMP, ROW_H)
            } else {
                val q = filter.text.trim()
                // Above everything, because a type, a signature or a call below may be named through one.
                row = imports(d2, doc, q, x, top, row, rowW, canHover)
                row = events(d2, doc, q, x, top, row, rowW, canHover)
                row = types(d2, doc, q, x, top, row, rowW, canHover)
                row = enumsSection(d2, doc, q, x, top, row, rowW, canHover)
                row = functions(d2, doc, q, x, top, row, rowW, canHover)
                row = variables(d2, doc, q, x, top, row, rowW, canHover)
                row = typeahead(d2, doc, q, x, top, row, rowW, canHover)
            }

            ImGui.setCursorScreenPos(x, top)
            ImGui.dummy(1f, row + 8f)

            // Text edits in a default commit through the same pump the node fields use.
            CanvasWidgets.pumpKeyboard(widgets)?.let { (fid, text) ->
                // fn|<function>            the function's own name
                // fp|<function>|in|<index>  a parameter's name;  |out| a result's
                if (fid.startsWith("fn|")) {
                    val from = fid.removePrefix("fn|")
                    if (doc?.renameFunction(from, text) == true && selected == from) selected = text.trim()
                    return@let
                }
                if (fid.startsWith("fp|")) {
                    val parts = fid.split('|')
                    val fn = doc?.function(parts.getOrNull(1) ?: "") ?: return@let
                    val idx = parts.getOrNull(3)?.toIntOrNull() ?: return@let
                    val clean = text.trim()
                    if (clean.isEmpty()) return@let
                    val ins = fn.params.toMutableList()
                    val outs = fn.results.toMutableList()
                    val side = if (parts.getOrNull(2) == "in") ins else outs
                    if (idx !in side.indices) return@let
                    side[idx] = FunctionPin(clean, side[idx].type)
                    doc.updateFunction(fn.name, ins, outs)
                    return@let
                }
                // tn|<type>              the type's own name
                // tf|<type>|<index>       a field's name
                if (fid.startsWith("tn|")) {
                    val from = fid.removePrefix("tn|")
                    if (doc?.renameStruct(from, text) == true && selected == from) selected = text.trim()
                    return@let
                }
                if (fid.startsWith("tf|")) {
                    val parts = fid.split('|')
                    val t = doc?.struct(parts.getOrNull(1) ?: "") ?: return@let
                    val idx = parts.getOrNull(2)?.toIntOrNull() ?: return@let
                    val clean = text.trim()
                    if (clean.isEmpty() || idx !in t.fields.indices) return@let
                    val fields = t.fields.toMutableList()
                    fields[idx] = FunctionPin(clean, fields[idx].type)
                    doc.updateStruct(t.name, fields)
                    return@let
                }
                // en|<enum>              the enum's own name
                // em|<enum>|<index>       one member
                if (fid.startsWith("en|")) {
                    val from = fid.removePrefix("en|")
                    if (doc?.renameEnum(from, text) == true && selected == from) selected = text.trim()
                    return@let
                }
                if (fid.startsWith("em|")) {
                    val parts = fid.split('|')
                    val e = doc?.enumType(parts.getOrNull(1) ?: "") ?: return@let
                    val idx = parts.getOrNull(2)?.toIntOrNull() ?: return@let
                    val clean = text.trim()
                    if (clean.isEmpty() || idx !in e.members.indices) return@let
                    val members = e.members.toMutableList()
                    members[idx] = clean
                    doc.updateEnum(e.name, members)
                    return@let
                }
                if (fid.startsWith("vn|")) {
                    val from = fid.removePrefix("vn|")
                    // A rename rewrites every node that names the variable, so the selection follows it —
                    // otherwise the row you were working on silently deselects itself.
                    if (doc?.renameVariable(from, text) == true && selected == from) selected = text.trim()
                    return@let
                }
                // vc|<name>  a list variable's COUNT. Resizes, keeping the slots that survive — retyping 5
                // as 4 and back must not cost you the first four values.
                if (fid.startsWith("vc|")) {
                    val v = doc?.variable(fid.removePrefix("vc|")) ?: return@let
                    val n = text.trim().toIntOrNull()?.coerceIn(0, BuiltinNodes.LIST_MAX) ?: return@let
                    val slots = (v.default as? List<*>).orEmpty()
                    if (n != slots.size) doc.updateVariable(v.name, v.type, List(n) { slots.getOrNull(it) })
                    return@let
                }
                // vs|<name>|<index>  one slot, for element types that are TYPED rather than picked; the
                // picked ones (item/npc/object) come back through ScriptsPanel's value picker instead.
                if (fid.startsWith("vs|")) {
                    val parts = fid.split('|')
                    val v = doc?.variable(parts.getOrNull(1) ?: "") ?: return@let
                    val i = parts.getOrNull(2)?.toIntOrNull() ?: return@let
                    val elem = v.type.of ?: return@let
                    val slots = (v.default as? List<*>).orEmpty().toMutableList()
                    if (i !in slots.indices) return@let
                    slots[i] = coerce(text, elem, doc)
                    doc.updateVariable(v.name, v.type, slots.toList())
                    return@let
                }
                // Everything below is the variable DEFAULT (`vd|`). Guarded, because an unrecognised id used
                // to fall through to here and be looked up as a variable name — which silently found nothing
                // and dropped the edit, so the field simply reverted with no clue why.
                if (!fid.startsWith("vd|")) return@let
                val raw = fid.removePrefix("vd|")
                val name = ValueEditors.baseName(raw)
                doc?.variable(name)?.let { v ->
                    // A tile's three boxes share one value, so a typed component is merged rather than
                    // stored — see ValueEditors.isComponent.
                    val next = if (ValueEditors.isComponent(raw)) ValueEditors.mergeComponent(raw, text, v.default)
                    else coerce(text, v.type, doc)
                    doc.updateVariable(v.name, v.type, next)
                }
            }
            widgets.endFrame()

            // BEGUN IN THE SAME WINDOW IT IS OPENED IN. A popup id is computed against the current ID
            // stack, so `openPopup` inside this child and `beginPopup` outside it name two different
            // popups and the menu simply never appears — no error, no warning, nothing. This is the second
            // time that has bitten: the file menu had it when it was opened from inside another popup.
            rowMenu(doc)
        } finally {
            ImGui.endChild()
        }
    }

    /**
     * Creation from the filter box.
     *
     * A search that can create is how a panel stops being somewhere you visit and starts being somewhere
     * you type: name the thing you were looking for and it exists.
     */
    private fun typeahead(dl: ImDrawList, doc: EditorDoc, q: String, x: Float, top: Float, start: Float, w: Float, canHover: Boolean): Float {
        if (q.isEmpty() || doc.variables.any { it.name.equals(q, true) }) return start
        val ry = top + start
        val hot = canHover && ImGui.isMouseHoveringRect(x, ry, x + w, ry + ROW_H)
        if (hot) dl.addRectFilled(x, ry, x + w, ry + ROW_H, Theme.GHOST_REST)
        PanelBits.label(dl, x + INDENT, ry, "Create \"$q\"", if (hot) Theme.TEXT else Theme.ACCENT, ROW_H)
        if (hot && ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
            doc.addVariable(GraphVariable(q, TypeRef(PinType.INT), 0, isExported = true))
            selected = q
            filter.set("")
        }
        return start + ROW_H
    }

    /**
     * Section heading: a word, a count, and a `+` that appears on hover.
     *
     * @return the y advance.
     */
    private fun section(
        dl: ImDrawList,
        x: Float,
        y: Float,
        w: Float,
        title: String,
        count: String,
        dim: Boolean = false,
        onAdd: (() -> Unit)? = null,
    ): Float {
        val hot = ImGui.isMouseHoveringRect(x, y, x + w, y + SECTION_H)
        var cx = x + PAD
        cx += PanelBits.label(dl, cx, y, title, if (dim) PanelBits.STAMP else SECTION_TEXT, SECTION_H) + 7f
        PanelBits.label(dl, cx, y, count, PanelBits.STAMP, SECTION_H)
        if (hot && onAdd != null) {
            val px = x + w - PAD - 12f
            ImGui.setCursorScreenPos(px - 4f, y)
            if (ImGui.invisibleButton("##add-$title", 20f, SECTION_H)) onAdd()
            PanelBits.label(dl, px, y, "+", if (ImGui.isItemHovered()) Theme.TEXT else PanelBits.STAMP, SECTION_H)
        }
        return SECTION_H
    }

    /**
     * Events are the graph's entry points, one row per KIND rather than per placement.
     *
     * A declared-but-unplaced event reports itself the same way an unused variable does — the catalog knows
     * what can start a graph, and a list that only showed what you had already placed could never tell you
     * what you were missing.
     */
    private fun events(dl: ImDrawList, doc: EditorDoc, q: String, x: Float, top: Float, start: Float, w: Float, canHover: Boolean): Float {
        var row = start
        val kinds = catalog.all.filter { it.kind == NodeKind.ENTRY }
            .filter { q.isEmpty() || it.title.contains(q, true) }
        if (kinds.isEmpty()) return row
        row += section(dl, x, top + row, w, "Events", kinds.size.toString())
        for (d in kinds) {
            val uses = doc.nodes.count { it.type == d.type }
            val ry = top + row
            val hot = canHover && ImGui.isMouseHoveringRect(x, ry, x + w, ry + ROW_H)
            if (hot) dl.addRectFilled(x, ry, x + w, ry + ROW_H, Theme.GHOST_REST)
            val fade = uses == 0
            // A filled triangle, the same shape an exec pin uses — an event IS an exec source.
            val cy = ry + ROW_H * 0.5f
            val col = if (fade) Theme.withAlpha(EVENT_COL, 0.45f) else EVENT_COL
            dl.addTriangleFilled(x + INDENT, cy - 4f, x + INDENT, cy + 4f, x + INDENT + 6f, cy, col)
            PanelBits.label(dl, x + INDENT + 14f, ry, d.title, if (fade) PanelBits.STAMP else Theme.TEXT_DIM, ROW_H)
            val note = if (uses == 0) "not placed" else uses.toString()
            PanelBits.label(dl, x + w - ImGui.calcTextSize(note).x - PAD, ry, note, PanelBits.STAMP, ROW_H)
            if (hot) {
                armDrag(null, d.type)
                if (ImGui.isMouseClicked(ImGuiMouseButton.Left) && uses > 0) cycleUses(doc, d.type, null)
                ImGui.setTooltip(if (uses == 0) "Drag onto the canvas to place it" else "Click to cycle its ${uses} placements")
            }
            row += ROW_H
        }
        return row
    }

    /**
     * The graph's user functions: name, shape, and how many places call it.
     *
     * A row is a handle on the function rather than a view of it — click to go to its box, drag one onto the
     * canvas to place a Call. The signature is edited on the box's own pins, which is where you are already
     * looking when you care about it; duplicating that editing here would be two places to change one thing.
     */
    private fun functions(
        dl: ImDrawList,
        doc: EditorDoc?,
        q: String,
        x: Float,
        top: Float,
        start: Float,
        w: Float,
        canHover: Boolean,
    ): Float {
        var row = start
        if (doc == null) return row
        val shown = doc.functions.filter { q.isEmpty() || it.name.contains(q, true) }
        if (shown.isEmpty() && q.isNotEmpty()) return row
        if (doc.functions.isEmpty()) return row
        row += section(dl, x, top + row, w, "Functions", doc.functions.size.toString()) { newFunction(doc) }

        for (f in shown) {
            val calls = doc.nodes.count { it.callee == f.name }
            val ry = top + row
            val isSelected = selected == f.name
            val hot = canHover && ImGui.isMouseHoveringRect(x, ry, x + w, ry + ROW_H)
            if (isSelected) {
                dl.addRectFilled(x, ry, x + w, ry + ROW_H, SEL_BG)
                dl.addRectFilled(x, ry, x + 2f, ry + ROW_H, Theme.ACCENT)
            } else if (hot) {
                dl.addRectFilled(x, ry, x + w, ry + ROW_H, Theme.GHOST_REST)
            }

            PanelBits.label(dl, x + INDENT, ry, f.name, if (isSelected) Theme.TEXT else Theme.TEXT_DIM, ROW_H)

            // The shape, right-aligned and dim — the same string the folded box shows, so the two places a
            // signature appears say it identically.
            val sig = NodeGeometry.signature(f)
            val note = if (calls == 0) sig else "$sig  ×$calls"
            val nw = ImGui.calcTextSize(note).x
            if (nw < w - INDENT - ImGui.calcTextSize(f.name).x - PAD * 3) {
                PanelBits.label(dl, x + w - nw - PAD, ry, note, PanelBits.STAMP, ROW_H)
            }

            // Select only. Framing the definition here fought the DRAG: press-and-move on a row is how you
            // place a Call, and the camera flying to the box on the press meant every attempt started by
            // yanking the canvas somewhere else. Going to the definition is a button now — see below.
            if (hot && ImGui.isMouseClicked(ImGuiMouseButton.Left) && !ImGui.isAnyItemHovered()) {
                selected = if (isSelected) null else f.name
            }
            if (hot && ImGui.isMouseClicked(ImGuiMouseButton.Right)) {
                rowMenuFor = f.name
                ImGui.openPopup(ROW_MENU)
            }
            if (hot) {
                ImGui.setTooltip("Drag onto the canvas to place a Call")
                armDrag(null, null, f.name)
            }
            row += ROW_H

            if (isSelected) row += functionExpanded(dl, doc, f, x, top + row, w)
        }
        // Applied after the list, never mid-iteration — a row that deletes itself while its own expansion
        // is still being laid out only fails when it is the last one.
        pendingFunctionDelete?.let { name ->
            doc.removeFunction(name)
            if (selected == name) selected = null
            pendingFunctionDelete = null
        }
        return row
    }

    /**
     * The selected function's signature, editable in place.
     *
     * This is the only place a signature can be edited, and it is here rather than on the box because the
     * box's pins ARE the signature — a pin you could rename by clicking is a pin you could not drag a wire
     * from, and wiring is what you do to a pin far more often.
     */
    private fun functionExpanded(dl: ImDrawList, doc: EditorDoc, f: GraphFunction, x: Float, y: Float, w: Float): Float {
        val rows = 2 + f.params.size + f.results.size + 2 // name, +in header, pins, +out header, actions
        val h = ROW_H * rows + 6f
        dl.addRectFilled(x, y, x + w, y + h, SEL_BG)
        dl.addRectFilled(x, y, x + 2f, y + h, Theme.ACCENT)
        val lx = x + INDENT + 16f
        var row = 0f

        PanelBits.label(dl, lx, y + row, "Name", PanelBits.STAMP, ROW_H)
        CanvasWidgets.text(
            widgets, "fn|${f.name}", lx + FIELD_LABEL_W, y + row + 2f, NAME_FIELD_W, ROW_H - 4f,
            f.name, ImGui.getTextLineHeight(),
        )
        row += ROW_H

        row += pinSide(dl, doc, f, "in", f.params, lx, x, y + row, w)
        row += pinSide(dl, doc, f, "out", f.results, lx, x, y + row, w)

        val gotoLabel = "${PanelBits.icon(Fonts.TARGET)} Goto"
        val deleteLabel = "${PanelBits.icon(Fonts.TRASH)} Delete"
        var ax = lx
        ax += action(dl, "fgo:${f.name}", ax, y + row, gotoLabel, true) {
            doc.nodes.firstOrNull { it.type == BuiltinNodes.FUNCTION && it.function == f.name }
                ?.let { onReveal?.invoke(it.id) }
        } + ACTION_GAP
        action(dl, "fdel:${f.name}", ax, y + row, deleteLabel, true, PanelBits.ERROR) {
            pendingFunctionDelete = f.name
        }
        row += ROW_H
        return h
    }

    /** One side of a signature: a header with an add button, then a row per pin. */
    private fun pinSide(
        dl: ImDrawList,
        doc: EditorDoc,
        f: GraphFunction,
        side: String,
        pins: List<FunctionPin>,
        lx: Float,
        x: Float,
        y: Float,
        w: Float,
    ): Float {
        var row = 0f
        val title = if (side == "in") "Inputs" else "Outputs"
        PanelBits.label(dl, lx, y + row, title, PanelBits.STAMP, ROW_H)
        // The add button sits on the header, where the count would be — the same place the section headers
        // put theirs, so there is one shape for "make me another one of these".
        val ax = lx + FIELD_LABEL_W
        ImGui.setCursorScreenPos(ax, y + row + 2f)
        if (ImGui.invisibleButton("##add:${f.name}:$side", 22f, ROW_H - 4f)) {
            val ins = f.params.toMutableList()
            val outs = f.results.toMutableList()
            val target = if (side == "in") ins else outs
            target += FunctionPin(freePinName(f, side), PinType.INT)
            doc.updateFunction(f.name, ins, outs)
        }
        val addHot = ImGui.isItemHovered()
        if (addHot) ImGui.setTooltip("Add ${if (side == "in") "an input" else "an output"}")
        PanelBits.label(dl, ax + 6f, y + row, PanelBits.icon(Fonts.PLUS), if (addHot) Theme.TEXT else PanelBits.STAMP, ROW_H)
        row += ROW_H

        for ((i, p) in pins.withIndex()) {
            val ry = y + row
            val key = "fp|${f.name}|$side|$i"
            // The swatch opens the same type picker a variable's does.
            val col = PinStyle.color(p.type)
            val sx = lx + 10f
            ImGui.setCursorScreenPos(sx - 6f, ry)
            if (ImGui.invisibleButton("##pt:$key", 16f, ROW_H)) {
                openTypePicker("ft|${f.name}|$side|$i", sx, ry + ROW_H)
            }
            val swHot = ImGui.isItemHovered()
            if (swHot) ImGui.setTooltip("${Types.label(p.type)} — click to change")
            if (swHot) dl.addCircle(sx, ry + ROW_H * 0.5f, 6.5f, Theme.withAlpha(col, 0.7f), 14, 1.5f)
            dl.addCircleFilled(sx, ry + ROW_H * 0.5f, 4f, col, 12)

            CanvasWidgets.text(
                widgets, key, lx + FIELD_LABEL_W, ry + 2f, NAME_FIELD_W, ROW_H - 4f,
                p.name, ImGui.getTextLineHeight(),
            )
            // A list says what it holds beside its name — the same "Of" a list variable has — so a signature
            // can promise LIST<BlockPos> rather than a list of anything.
            if (p.type.isList) elementChip(dl, doc, "fe|${f.name}|$side|$i", "##pe:$key", lx + FIELD_LABEL_W + NAME_FIELD_W + 8f, ry, p.type.of)

            // Remove, at the right-hand end. Dropping a pin takes its wires with it (see updateFunction),
            // which is why there is no confirmation: it is one Ctrl+Z and the wires come back.
            val rx = x + w - PAD - 16f
            ImGui.setCursorScreenPos(rx - 4f, ry)
            if (ImGui.invisibleButton("##pd:$key", 20f, ROW_H)) {
                val ins = f.params.toMutableList()
                val outs = f.results.toMutableList()
                val target = if (side == "in") ins else outs
                if (i in target.indices) {
                    target.removeAt(i)
                    doc.updateFunction(f.name, ins, outs)
                }
            }
            val delHot = ImGui.isItemHovered()
            if (delHot) ImGui.setTooltip("Remove this pin and its wires")
            PanelBits.label(dl, rx, ry, PanelBits.icon(Fonts.TRASH), if (delHot) PanelBits.ERROR else PanelBits.STAMP, ROW_H)
            row += ROW_H
        }
        return row
    }

    /** A pin name nothing else on this signature is using. */
    private fun freePinName(f: GraphFunction, side: String): String {
        val taken = (f.params + f.results).map { it.name }.toSet()
        val base = if (side == "in") "In" else "Out"
        var i = 1
        while ("$base$i" in taken) i++
        return "$base$i"
    }

    private fun newFunction(doc: EditorDoc) {
        val name = doc.freeFunctionName()
        // Placed below whatever is already there, so a new box never lands on top of the graph.
        val below = doc.nodes.maxOfOrNull { it.y + maxOf(it.h, 120f) } ?: 0f
        doc.addFunction(name, 80f, below + 80f)
        selected = name
        doc.nodes.firstOrNull { it.type == BuiltinNodes.FUNCTION && it.function == name }
            ?.let { onReveal?.invoke(it.id) }
    }

    /**
     * Types this graph declares.
     *
     * Shaped exactly like Functions, because it is the same act: name a thing, give it named typed parts,
     * drag it out to use it. A function's parameter editor and a record's field editor are the same editor
     * — which is why [dev.ziggle.vscript.model.StructType] reuses [FunctionPin] rather than cloning it.
     *
     * Listed ABOVE functions: a signature can be typed as a record, so the thing that can be referred to
     * comes before the things that refer to it.
     */
    private fun types(
        dl: ImDrawList,
        doc: EditorDoc?,
        q: String,
        x: Float,
        top: Float,
        start: Float,
        w: Float,
        canHover: Boolean,
    ): Float {
        var row = start
        if (doc == null) return row
        val shown = doc.types.filter { q.isEmpty() || it.name.contains(q, true) }
        if (shown.isEmpty() && q.isNotEmpty()) return row
        if (doc.types.isEmpty()) return row
        row += section(dl, x, top + row, w, "Types", doc.types.size.toString()) { newType(doc) }

        for (t in shown) {
            val uses = doc.nodes.count { it.literals[BuiltinNodes.STRUCT_OF] == t.name } +
                doc.variables.count { it.type.name == t.name }
            val ry = top + row
            val isSelected = selected == t.name
            val hot = canHover && ImGui.isMouseHoveringRect(x, ry, x + w, ry + ROW_H)
            if (isSelected) {
                dl.addRectFilled(x, ry, x + w, ry + ROW_H, SEL_BG)
                dl.addRectFilled(x, ry, x + 2f, ry + ROW_H, Theme.ACCENT)
            } else if (hot) {
                dl.addRectFilled(x, ry, x + w, ry + ROW_H, Theme.GHOST_REST)
            }

            // A swatch in the type's own derived colour, so the row and the pins it will paint agree —
            // the same promise the built-in types make.
            val col = PinStyle.color(TypeRef.named(t.name))
            dl.addCircleFilled(x + INDENT - 8f, ry + ROW_H * 0.5f, 4f, col, 12)
            PanelBits.label(dl, x + INDENT + 4f, ry, t.name, if (isSelected) Theme.TEXT else Theme.TEXT_DIM, ROW_H)

            val note = t.fields.joinToString(", ") { it.name }.ifEmpty { "no fields" }
                .let { if (uses == 0) it else "$it  ×$uses" }
            val nw = ImGui.calcTextSize(note).x
            if (nw < w - INDENT - ImGui.calcTextSize(t.name).x - PAD * 3) {
                PanelBits.label(dl, x + w - nw - PAD, ry, note, PanelBits.STAMP, ROW_H)
            }

            if (hot && ImGui.isMouseClicked(ImGuiMouseButton.Left) && !ImGui.isAnyItemHovered()) {
                selected = if (isSelected) null else t.name
            }
            if (hot && ImGui.isMouseClicked(ImGuiMouseButton.Right)) {
                rowMenuFor = t.name
                ImGui.openPopup(ROW_MENU)
            }
            if (hot) {
                ImGui.setTooltip("Drag onto the canvas to place a Make — hold Ctrl for a Split")
                armDrag(null, null, declaredType = t.name)
            }
            row += ROW_H

            if (isSelected) row += typeExpanded(dl, doc, t, x, top + row, w)
        }
        // After the list, never mid-iteration — see the note on the function delete.
        pendingTypeDelete?.let { name ->
            doc.removeStruct(name)
            if (selected == name) selected = null
            pendingTypeDelete = null
        }
        return row
    }

    /** The selected type's fields, editable in place — the counterpart of [functionExpanded]. */
    private fun typeExpanded(
        dl: ImDrawList,
        doc: EditorDoc,
        t: dev.ziggle.vscript.model.StructType,
        x: Float,
        y: Float,
        w: Float,
    ): Float {
        val h = ROW_H * (3 + t.fields.size) + 6f
        dl.addRectFilled(x, y, x + w, y + h, SEL_BG)
        dl.addRectFilled(x, y, x + 2f, y + h, Theme.ACCENT)
        val lx = x + INDENT + 16f
        var row = 0f

        PanelBits.label(dl, lx, y + row, "Name", PanelBits.STAMP, ROW_H)
        CanvasWidgets.text(
            widgets, "tn|${t.name}", lx + FIELD_LABEL_W, y + row + 2f, NAME_FIELD_W, ROW_H - 4f,
            t.name, ImGui.getTextLineHeight(),
        )
        row += ROW_H

        PanelBits.label(dl, lx, y + row, "Fields", PanelBits.STAMP, ROW_H)
        val ax = lx + FIELD_LABEL_W
        ImGui.setCursorScreenPos(ax, y + row + 2f)
        if (ImGui.invisibleButton("##addf:${t.name}", 22f, ROW_H - 4f)) {
            doc.updateStruct(t.name, t.fields + FunctionPin(freeFieldName(t), PinType.INT))
        }
        val addHot = ImGui.isItemHovered()
        if (addHot) ImGui.setTooltip("Add a field")
        PanelBits.label(dl, ax + 6f, y + row, PanelBits.icon(Fonts.PLUS), if (addHot) Theme.TEXT else PanelBits.STAMP, ROW_H)
        row += ROW_H

        for ((i, f) in t.fields.withIndex()) {
            val ry = y + row
            val key = "tf|${t.name}|$i"
            val col = PinStyle.color(f.type)
            val sx = lx + 10f
            ImGui.setCursorScreenPos(sx - 6f, ry)
            if (ImGui.invisibleButton("##ft:$key", 16f, ROW_H)) {
                openTypePicker("st|${t.name}|$i", sx, ry + ROW_H)
            }
            val swHot = ImGui.isItemHovered()
            if (swHot) ImGui.setTooltip("${Types.label(f.type)} — click to change")
            if (swHot) dl.addCircle(sx, ry + ROW_H * 0.5f, 6.5f, Theme.withAlpha(col, 0.7f), 14, 1.5f)
            dl.addCircleFilled(sx, ry + ROW_H * 0.5f, 4f, col, 12)

            CanvasWidgets.text(
                widgets, key, lx + FIELD_LABEL_W, ry + 2f, NAME_FIELD_W, ROW_H - 4f,
                f.name, ImGui.getTextLineHeight(),
            )
            if (f.type.isList) elementChip(dl, doc, "se|${t.name}|$i", "##fe:$key", lx + FIELD_LABEL_W + NAME_FIELD_W + 8f, ry, f.type.of)

            val rx = x + w - PAD - 16f
            ImGui.setCursorScreenPos(rx - 4f, ry)
            if (ImGui.invisibleButton("##fd:$key", 20f, ROW_H)) {
                doc.updateStruct(t.name, t.fields.filterIndexed { j, _ -> j != i })
            }
            val delHot = ImGui.isItemHovered()
            if (delHot) ImGui.setTooltip("Remove this field and its wires")
            PanelBits.label(dl, rx, ry, PanelBits.icon(Fonts.TRASH), if (delHot) PanelBits.ERROR else PanelBits.STAMP, ROW_H)
            row += ROW_H
        }

        action(dl, "tdel:${t.name}", lx, y + row, "${PanelBits.icon(Fonts.TRASH)} Delete", true, PanelBits.ERROR) {
            pendingTypeDelete = t.name
        }
        row += ROW_H
        return h
    }

    /** A field name this record is not already using. */
    /**
     * Declared enums: a name and its members. A pin or variable of the enum's type gets the members as its
     * choices; literals are stored by member name. Mirrors [types] — the same rows, the same routing.
     */
    private fun enumsSection(
        dl: ImDrawList,
        doc: EditorDoc?,
        q: String,
        x: Float,
        top: Float,
        start: Float,
        w: Float,
        canHover: Boolean,
    ): Float {
        var row = start
        if (doc == null) return row
        val shown = doc.enums.filter { q.isEmpty() || it.name.contains(q, true) }
        if (shown.isEmpty() && q.isNotEmpty()) return row
        if (doc.enums.isEmpty()) return row
        row += section(dl, x, top + row, w, "Enums", doc.enums.size.toString()) { newEnum(doc) }

        for (e in shown) {
            val uses = doc.variables.count { it.type.name == e.name }
            val ry = top + row
            val isSelected = selected == e.name
            val hot = canHover && ImGui.isMouseHoveringRect(x, ry, x + w, ry + ROW_H)
            if (isSelected) {
                dl.addRectFilled(x, ry, x + w, ry + ROW_H, SEL_BG)
                dl.addRectFilled(x, ry, x + 2f, ry + ROW_H, Theme.ACCENT)
            } else if (hot) {
                dl.addRectFilled(x, ry, x + w, ry + ROW_H, Theme.GHOST_REST)
            }
            val col = PinStyle.color(TypeRef.named(e.name))
            dl.addCircleFilled(x + INDENT - 8f, ry + ROW_H * 0.5f, 4f, col, 12)
            PanelBits.label(dl, x + INDENT + 4f, ry, e.name, if (isSelected) Theme.TEXT else Theme.TEXT_DIM, ROW_H)

            val note = e.members.joinToString(", ").ifEmpty { "no members" }
                .let { if (uses == 0) it else "$it  ×$uses" }
            val nw = ImGui.calcTextSize(note).x
            if (nw < w - INDENT - ImGui.calcTextSize(e.name).x - PAD * 3) {
                PanelBits.label(dl, x + w - nw - PAD, ry, note, PanelBits.STAMP, ROW_H)
            }

            if (hot && ImGui.isMouseClicked(ImGuiMouseButton.Left) && !ImGui.isAnyItemHovered()) {
                selected = if (isSelected) null else e.name
            }
            if (hot && ImGui.isMouseClicked(ImGuiMouseButton.Right)) {
                rowMenuFor = e.name
                ImGui.openPopup(ROW_MENU)
            }
            row += ROW_H

            if (isSelected) row += enumExpanded(dl, doc, e, x, top + row, w)
        }
        pendingEnumDelete?.let { name ->
            doc.removeEnum(name)
            if (selected == name) selected = null
            pendingEnumDelete = null
        }
        return row
    }

    private fun enumExpanded(
        dl: ImDrawList,
        doc: EditorDoc,
        e: dev.ziggle.vscript.model.EnumType,
        x: Float,
        y: Float,
        w: Float,
    ): Float {
        val h = ROW_H * (3 + e.members.size) + 6f
        dl.addRectFilled(x, y, x + w, y + h, SEL_BG)
        dl.addRectFilled(x, y, x + 2f, y + h, Theme.ACCENT)
        val lx = x + INDENT + 16f
        var row = 0f

        PanelBits.label(dl, lx, y + row, "Name", PanelBits.STAMP, ROW_H)
        CanvasWidgets.text(
            widgets, "en|${e.name}", lx + FIELD_LABEL_W, y + row + 2f, NAME_FIELD_W, ROW_H - 4f,
            e.name, ImGui.getTextLineHeight(),
        )
        row += ROW_H

        PanelBits.label(dl, lx, y + row, "Members", PanelBits.STAMP, ROW_H)
        val ax = lx + FIELD_LABEL_W
        ImGui.setCursorScreenPos(ax, y + row + 2f)
        if (ImGui.invisibleButton("##adde:${e.name}", 22f, ROW_H - 4f)) {
            doc.updateEnum(e.name, e.members + freeMemberName(e))
        }
        val addHot = ImGui.isItemHovered()
        if (addHot) ImGui.setTooltip("Add a member")
        PanelBits.label(dl, ax + 6f, y + row, PanelBits.icon(Fonts.PLUS), if (addHot) Theme.TEXT else PanelBits.STAMP, ROW_H)
        row += ROW_H

        for ((i, m) in e.members.withIndex()) {
            val ry = y + row
            val key = "em|${e.name}|$i"
            PanelBits.label(dl, lx + 10f, ry, "${i + 1}.", PanelBits.STAMP, ROW_H)
            CanvasWidgets.text(
                widgets, key, lx + FIELD_LABEL_W, ry + 2f, NAME_FIELD_W, ROW_H - 4f,
                m, ImGui.getTextLineHeight(),
            )
            val rx = x + w - PAD - 16f
            ImGui.setCursorScreenPos(rx - 4f, ry)
            if (ImGui.invisibleButton("##ed:$key", 20f, ROW_H)) {
                doc.updateEnum(e.name, e.members.filterIndexed { j, _ -> j != i })
            }
            val delHot = ImGui.isItemHovered()
            if (delHot) ImGui.setTooltip("Remove this member")
            PanelBits.label(dl, rx, ry, PanelBits.icon(Fonts.TRASH), if (delHot) PanelBits.ERROR else PanelBits.STAMP, ROW_H)
            row += ROW_H
        }

        action(dl, "edel:${e.name}", lx, y + row, "${PanelBits.icon(Fonts.TRASH)} Delete", true, PanelBits.ERROR) {
            pendingEnumDelete = e.name
        }
        row += ROW_H
        return h
    }

    private fun freeMemberName(e: dev.ziggle.vscript.model.EnumType): String {
        val taken = e.members.map { it.lowercase() }.toSet()
        var i = e.members.size + 1
        while ("member$i" in taken) i++
        return "Member$i"
    }

    private fun newEnum(doc: EditorDoc) {
        val name = doc.freeEnumName()
        doc.addEnum(name)
        selected = name
    }

    private fun freeFieldName(t: dev.ziggle.vscript.model.StructType): String {
        val taken = t.fields.map { it.name.lowercase() }.toSet()
        var i = 1
        while ("field$i" in taken) i++
        return "field$i"
    }

    private fun newType(doc: EditorDoc) {
        val name = doc.freeTypeName()
        doc.addStruct(name)
        // Straight into a field, because a record with none is not yet anything.
        doc.updateStruct(name, listOf(FunctionPin("field1", PinType.INT)))
        selected = name
    }

    /**
     * The documents this one imports, and whether each one currently resolves.
     *
     * Read-only, and deliberately: an import is declared with `graph use` or by typing the line into a
     * `.vs`, and the thing a panel is uniquely good at is telling you the state of one — an alias whose
     * document has been renamed or deleted is invisible everywhere else until a run fails. So the section
     * exists to show the RESOLUTION, which is why the unresolved case is what it draws loudest.
     *
     * Hidden entirely when a document imports nothing, matching every other section here: a heading with a
     * zero beside it is a way of announcing what you do not have.
     */
    private fun imports(dl: ImDrawList, doc: EditorDoc, q: String, x: Float, top: Float, start: Float, w: Float, canHover: Boolean): Float {
        var row = start
        if (doc.imports.isEmpty()) return row
        val shown = doc.imports.filter { q.isEmpty() || it.alias.contains(q, true) || it.ref.contains(q, true) }
        if (shown.isEmpty()) return row
        row += section(dl, x, top + row, w, "Imports", doc.imports.size.toString())

        for (imp in shown) {
            val ry = top + row
            val target = resolver?.load(imp)
            val hot = canHover && ImGui.isMouseHoveringRect(x, ry, x + w, ry + ROW_H)
            if (hot) dl.addRectFilled(x, ry, x + w, ry + ROW_H, Theme.GHOST_REST)

            // A filled dot resolves, a ring does not — the same vocabulary an unused variable uses, so the
            // panel has one way of saying "this names nothing".
            val cx = x + INDENT + 4f
            val cy = ry + ROW_H * 0.5f
            if (target == null) dl.addCircle(cx, cy, 4f, PanelBits.STAMP, 12, 1.5f)
            else dl.addCircleFilled(cx, cy, 4f, Theme.ACCENT, 12)

            PanelBits.label(
                dl, x + INDENT + 16f, ry, imp.alias,
                if (target == null) PanelBits.STAMP else Theme.TEXT, ROW_H,
            )

            // What it offers, or that it offers nothing because it was not found. Counting only the public
            // ones, since those are what the alias can actually reach.
            val note = if (target == null) "not found" else {
                // What the document OFFERS, which is now what it exported rather than what it failed to
                // hide — see GraphImport. A library with no `export` anywhere reads as "0 fn, 0 var",
                // which is the honest summary of what an importer can reach.
                val fns = target.functions.count { it.isExported }
                val vars = target.variables.count { it.isExported }
                "$fns fn, $vars var"
            }
            val nx = x + w - ImGui.calcTextSize(note).x - PAD
            PanelBits.label(dl, nx, ry, note, PanelBits.STAMP, ROW_H)
            if (hot) {
                ImGui.setTooltip(
                    if (target == null) "\"${imp.ref}\" — no document answers to it"
                    else "${imp.alias} = \"${imp.ref}\" — call it as ${imp.alias}::name"
                )
            }
            row += ROW_H
        }
        return row
    }

    private fun variables(dl: ImDrawList, doc: EditorDoc, q: String, x: Float, top: Float, start: Float, w: Float, canHover: Boolean): Float {
        var row = start
        val shown = doc.variables.filter { q.isEmpty() || it.name.contains(q, true) }
        if (shown.isEmpty() && q.isNotEmpty()) return row
        if (doc.variables.isEmpty()) return row
        row += section(dl, x, top + row, w, "Variables", doc.variables.size.toString()) { newVariable(doc) }

        for (v in shown) {
            val uses = doc.nodes.count { it.variable == v.name }
            val ry = top + row
            val isSelected = selected == v.name
            val hot = canHover && ImGui.isMouseHoveringRect(x, ry, x + w, ry + ROW_H)
            if (isSelected) {
                dl.addRectFilled(x, ry, x + w, ry + ROW_H, SEL_BG)
                dl.addRectFilled(x, ry, x + 2f, ry + ROW_H, Theme.ACCENT)
            } else if (hot) {
                dl.addRectFilled(x, ry, x + w, ry + ROW_H, Theme.GHOST_REST)
            }

            // The swatch IS the type. Click it to change; the word lives in the tooltip and the picker.
            val col = PinStyle.color(v.type)
            val sx = x + INDENT + 4f
            val cy = ry + ROW_H * 0.5f
            ImGui.setCursorScreenPos(sx - 6f, ry)
            if (ImGui.invisibleButton("##sw:${v.name}", 16f, ROW_H)) {
                openTypePicker("vt|${v.name}", sx, ry + ROW_H)
            }
            val swatchHot = ImGui.isItemHovered()
            if (swatchHot) ImGui.setTooltip("${Types.label(v.type)} — click to change")
            // A ring on hover: without some response the swatch reads as a bullet, and nobody clicks a
            // bullet. The dot itself does not change size, so nothing shifts under the cursor.
            if (swatchHot) dl.addCircle(sx, cy, 6.5f, Theme.withAlpha(col, 0.7f), 14, 1.5f)
            if (uses == 0) dl.addCircle(sx, cy, 4f, PanelBits.STAMP, 12, 1.5f)
            else dl.addCircleFilled(sx, cy, 4f, col, 12)

            val nameCol = when {
                uses == 0 -> PanelBits.STAMP
                isSelected -> Theme.TEXT
                else -> Theme.TEXT_DIM
            }
            PanelBits.label(dl, x + INDENT + 16f, ry, v.name, nameCol, ROW_H)

            val note = if (uses == 0) "unused" else uses.toString()
            val nx = x + w - ImGui.calcTextSize(note).x - PAD
            ImGui.setCursorScreenPos(nx - 4f, ry)
            if (ImGui.invisibleButton("##uses:${v.name}", ImGui.calcTextSize(note).x + 8f, ROW_H) && uses > 0) {
                cycleUses(doc, null, v.name)
            }
            val usesHot = ImGui.isItemHovered()
            if (usesHot && uses > 0) ImGui.setTooltip("Click to walk through its $uses uses")
            PanelBits.label(dl, nx, ry, note, if (usesHot && uses > 0) Theme.TEXT else PanelBits.STAMP, ROW_H)

            // Selecting is a click on the row itself, so it does not fight the swatch or the count.
            if (hot && ImGui.isMouseClicked(ImGuiMouseButton.Left) && !ImGui.isAnyItemHovered()) {
                selected = if (isSelected) null else v.name
            }
            // Right-click is where people reach for delete, whether or not the expansion also offers it.
            if (hot && ImGui.isMouseClicked(ImGuiMouseButton.Right)) {
                rowMenuFor = v.name
                ImGui.openPopup(ROW_MENU)
            }
            if (hot) armDrag(v.name, null)
            row += ROW_H

            if (isSelected) row += expanded(dl, doc, v, uses, x, top + row, w)
        }
        // Applied once the list has been drawn, never mid-iteration: a row that removes itself while its
        // own expansion is still being laid out is the sort of thing that only fails when the last variable
        // is the one deleted.
        pendingFunctionDelete?.let { name ->
            doc.removeFunction(name)
            if (selected == name) selected = null
            pendingFunctionDelete = null
        }
        pendingDelete?.let { name ->
            doc.removeVariable(name)
            if (selected == name) selected = null
            pendingDelete = null
        }
        return row
    }

    private var pendingDelete: String? = null
    private var pendingFunctionDelete: String? = null
    private var pendingTypeDelete: String? = null
    private var pendingEnumDelete: String? = null
    private var rowMenuFor: String? = null

    /** Rename and delete, on the row itself. */
    private fun rowMenu(doc: EditorDoc?) {
        if (!ImGui.beginPopup(ROW_MENU)) return
        val name = rowMenuFor
        val v = if (name == null || doc == null) null else doc.variable(name)
        val fn = if (name == null || doc == null) null else doc.function(name)
        val st = if (name == null || doc == null) null else doc.struct(name)
        val en = if (name == null || doc == null) null else doc.enumType(name)
        when {
            v != null -> {
                if (ImGui.menuItem("Select and rename")) selected = v.name
                if (ImGui.menuItem("Delete")) pendingDelete = v.name
            }
            // A function's body goes with it, so the item says so — "Delete" alone would understate what
            // is about to happen to the nodes inside the box.
            fn != null -> {
                if (ImGui.menuItem("Delete function and its body")) pendingFunctionDelete = fn.name
            }
            // The nodes that named it are LEFT — see removeStruct. Saying so beats a silent cascade.
            st != null -> {
                if (ImGui.menuItem("Select and rename")) selected = st.name
                if (ImGui.menuItem("Delete")) pendingTypeDelete = st.name
            }
            en != null -> {
                if (ImGui.menuItem("Select and rename")) selected = en.name
                if (ImGui.menuItem("Delete")) pendingEnumDelete = en.name
            }
            else -> ImGui.textDisabled("gone")
        }
        ImGui.endPopup()
    }

    /** The selected variable's details, in place. */
    private fun expanded(dl: ImDrawList, doc: EditorDoc, v: GraphVariable, uses: Int, x: Float, y: Float, w: Float): Float {
        // Drawn to the height the rows actually take, which the action wrap can push out by one — a fixed
        // height would either clip the wrapped row or leave a gap under the common case.
        //
        // A list spends its one Default row on Of + Count + a row per slot, so the plate grows with the
        // list. Counted here rather than measured after drawing because the background is drawn FIRST.
        val listRows = if (!v.type.isList) 0 else {
            val slots = (v.default as? List<*>).orEmpty().size
            2 + (if (v.type.of == null) 1 else slots) - 1 // Of + Count + slots, replacing the Default row
        }
        val h = ROW_H * ((if (actionsWrap(w)) 6 else 5) + listRows) + 6f
        dl.addRectFilled(x, y, x + w, y + h, SEL_BG)
        dl.addRectFilled(x, y, x + 2f, y + 2f + h - 2f, Theme.ACCENT)
        val lx = x + INDENT + 16f
        var row = 0f

        // Renaming lives here rather than on the row: a name you can edit by clicking is a name you cannot
        // click to select, and selecting is what you do to a row far more often than renaming it.
        PanelBits.label(dl, lx, y + row, "Name", PanelBits.STAMP, ROW_H)
        CanvasWidgets.text(
            widgets, "vn|${v.name}", lx + FIELD_LABEL_W, y + row + 2f, NAME_FIELD_W, ROW_H - 4f,
            v.name, ImGui.getTextLineHeight(),
        )
        row += ROW_H

        // The row shows the type as a swatch and nothing else, which is right there — but a swatch is a
        // poor place to go LOOKING for a setting. The expansion is where you look, so here it is named,
        // beside Default and Scope, opening the same picker.
        PanelBits.label(dl, lx, y + row, "Type", PanelBits.STAMP, ROW_H)
        val tx = lx + FIELD_LABEL_W
        val tName = Types.label(v.type)
        val tW = ImGui.calcTextSize(tName).x + 26f
        ImGui.setCursorScreenPos(tx, y + row + 2f)
        if (ImGui.invisibleButton("##ty:${v.name}", tW, ROW_H - 4f)) {
            openTypePicker("vt|${v.name}", tx, y + row + ROW_H)
        }
        val tHot = ImGui.isItemHovered()
        val tCol = PinStyle.color(v.type)
        dl.addRectFilled(tx, y + row + 3f, tx + tW, y + row + ROW_H - 3f, Theme.withAlpha(tCol, if (tHot) 0.30f else 0.16f), 4f, ImDrawFlags.RoundCornersAll)
        dl.addCircleFilled(tx + 9f, y + row + ROW_H * 0.5f, 3.5f, tCol, 12)
        PanelBits.label(dl, tx + 17f, y + row, tName, Theme.shade(tCol, 1.35f), ROW_H)
        row += ROW_H

        if (v.type.isList) {
            row += listDefault(dl, doc, v, lx, y + row, w - (lx - x) - PAD)
        } else {
            PanelBits.label(dl, lx, y + row, "Default", PanelBits.STAMP, ROW_H)
            defaultField(dl, doc, v, lx + FIELD_LABEL_W, y + row, w - (lx - x) - FIELD_LABEL_W - PAD)
            row += ROW_H
        }

        PanelBits.label(dl, lx, y + row, "Scope", PanelBits.STAMP, ROW_H)
        val scopeX = lx + FIELD_LABEL_W
        ImGui.setCursorScreenPos(scopeX, y + row)
        ImGui.invisibleButton("##scope:${v.name}", 70f, ROW_H)
        // A field rather than a section: promoting a local to a graph variable should be a change of value,
        // not a move between two lists. There is only one scope until functions exist, so it says so.
        if (ImGui.isItemHovered()) ImGui.setTooltip("Locals arrive with functions; everything is graph-wide for now")
        PanelBits.label(dl, scopeX, y + row, "Graph", Theme.TEXT_DIM, ROW_H)
        row += ROW_H

        // The actions, wrapped onto a second line when they do not fit.
        //
        // The sidebar is resizable, so no fixed width is the right one — and an action that runs off the
        // edge is not merely ugly, it is unreachable. Wrapping costs a row only when it is actually needed.
        val cycleLabel = "${PanelBits.icon(Fonts.TARGET)} Cycle $uses uses"
        val watchLabel = "${PanelBits.icon(Fonts.EYE)} Watch"
        val deleteLabel = "${PanelBits.icon(Fonts.TRASH)} Delete"
        val right = x + w - PAD
        var ax = lx
        ax += action(dl, "cyc:${v.name}", ax, y + row, cycleLabel, uses > 0) { cycleUses(doc, null, v.name) } + ACTION_GAP
        if (ax + ImGui.calcTextSize(watchLabel).x > right) {
            row += ROW_H
            ax = lx
        }
        ax += action(dl, "wat:${v.name}", ax, y + row, watchLabel, true) { onWatch(v.name) } + ACTION_GAP
        if (ax + ImGui.calcTextSize(deleteLabel).x > right) {
            row += ROW_H
            ax = lx
        }
        // No confirmation. Removing a variable unpoints the nodes that named it rather than deleting them,
        // and the whole thing is one Ctrl+Z — a modal asking "are you sure" would be protecting you from
        // something already reversible.
        action(dl, "del:${v.name}", ax, y + row, deleteLabel, true, PanelBits.ERROR) { pendingDelete = v.name }
        // Spelled out on hover, since deleting one that is in use is the case worth a second's thought.
        if (uses > 0 && ImGui.isItemHovered()) {
            ImGui.setTooltip("$uses node(s) will lose their variable. Ctrl+Z undoes it.")
        }
        return row + ROW_H + 6f
    }

    /**
     * Whether the action row needs a second line at this width.
     *
     * Measured against the widest form ("Cycle 99 uses"), so the plate does not change height as the use
     * count ticks over from 9 to 10 — a row that grows while you watch it reads as a glitch.
     */
    private fun actionsWrap(w: Float): Boolean {
        val all = ImGui.calcTextSize("${PanelBits.icon(Fonts.TARGET)} Cycle 99 uses").x +
            ImGui.calcTextSize("${PanelBits.icon(Fonts.EYE)} Watch").x +
            ImGui.calcTextSize("${PanelBits.icon(Fonts.TRASH)} Delete").x + ACTION_GAP * 2
        return INDENT + 16f + all + PAD > w
    }

    private inline fun action(
        dl: ImDrawList,
        id: String,
        x: Float,
        y: Float,
        label: String,
        enabled: Boolean,
        tint: Int = PanelBits.MUTED,
        crossinline onClick: () -> Unit,
    ): Float {
        val w = ImGui.calcTextSize(label).x
        ImGui.setCursorScreenPos(x, y)
        if (ImGui.invisibleButton("##$id", w + 6f, ROW_H) && enabled) onClick()
        val hot = enabled && ImGui.isItemHovered()
        PanelBits.label(dl, x, y, label, if (!enabled) PanelBits.STAMP else if (hot) Theme.TEXT else tint, ROW_H)
        return w
    }

    /**
     * The default value.
     *
     * One field object reused across rows, re-seeded whenever the selection moves. Only one variable is
     * expanded at a time, so one field is all there is to draw — and keeping a field per variable would
     * mean a map that has to be kept in step with renames and deletions for no benefit.
     */
    /**
     * The default value, drawn by the SAME editor a pin of that type gets on a node.
     *
     * A LIST has none: a list is built by the graph, and a field pretending otherwise would misrepresent
     * what the variable accepts — so it says what it is instead of showing an empty box.
     */
    /**
     * A list variable's default, written out the way the List node writes one: pick what it holds, say how
     * many, then fill the slots with that type's own picker.
     *
     * The alternative was the string this used to print — "built by the graph" — which is true of a list
     * only because there was nowhere to author one. A list of items IS a value, and the node form already
     * proved the shape works; a variable is the same value with a name on it, so it gets the same editor
     * rather than a second, lesser one.
     *
     * @return the height drawn.
     */
    private fun listDefault(dl: ImDrawList, doc: EditorDoc, v: GraphVariable, x: Float, y: Float, w: Float): Float {
        val elem = v.type.of
        val slots = (v.default as? List<*>).orEmpty()
        var row = 0f

        // What it holds. Unset reads "Any", which is honest — an unconstrained list connects to anything
        // and cannot offer a slot editor, because nothing knows what a slot would be.
        PanelBits.label(dl, x, y + row, "Of", PanelBits.STAMP, ROW_H)
        val ox = x + FIELD_LABEL_W
        val oName = elem?.let { Types.label(it) } ?: "Any"
        val oW = ImGui.calcTextSize(oName).x + 26f
        ImGui.setCursorScreenPos(ox, y + row + 2f)
        if (ImGui.invisibleButton("##of:${v.name}", oW, ROW_H - 4f)) {
            ValuePicker.open("ve|${v.name}", ox, y + row + ROW_H) { q ->
                ValuePicker.typeRows(BuiltinNodes.elementTypes.map { it.type }, q)
            }
        }
        val oHot = ImGui.isItemHovered()
        val oCol = PinStyle.color(elem ?: TypeRef.WILDCARD)
        dl.addRectFilled(ox, y + row + 3f, ox + oW, y + row + ROW_H - 3f, Theme.withAlpha(oCol, if (oHot) 0.30f else 0.16f), 4f, ImDrawFlags.RoundCornersAll)
        dl.addCircleFilled(ox + 9f, y + row + ROW_H * 0.5f, 3.5f, oCol, 12)
        PanelBits.label(dl, ox + 17f, y + row, oName, Theme.shade(oCol, 1.35f), ROW_H)
        row += ROW_H

        // How many. Editing the count RESIZES rather than rebuilds: shrinking then growing again would
        // otherwise hand back empty slots where the values you had were still perfectly good.
        PanelBits.label(dl, x, y + row, "Count", PanelBits.STAMP, ROW_H)
        val countSpec = PinSpec("Count", TypeRef(PinType.INT), default = slots.size)
        val newCount = ValueEditors.draw(
            widgets, "vc|${v.name}", x + FIELD_LABEL_W, y + row + 2f, 60f, ROW_H - 4f,
            countSpec, slots.size, ImGui.getTextLineHeight(),
        )
        (newCount as? Number)?.toInt()?.coerceIn(0, BuiltinNodes.LIST_MAX)?.let { n ->
            if (n != slots.size) {
                doc.updateVariable(v.name, v.type, List(n) { slots.getOrNull(it) })
            }
        }
        row += ROW_H

        if (elem == null) {
            PanelBits.label(dl, x, y + row, "pick what it holds to fill it", ValueEditors.UNEDITABLE, ROW_H)
            return row + ROW_H
        }
        for (i in slots.indices) {
            PanelBits.label(dl, x, y + row, "${i + 1}", PanelBits.STAMP, ROW_H)
            val spec = PinSpec("${i + 1}", elem, default = slots[i])
            val fieldW = minOf(w - FIELD_LABEL_W, NodeGeometry.editorWidth(spec))
            val changed = ValueEditors.draw(
                widgets, "vs|${v.name}|$i", x + FIELD_LABEL_W, y + row + 2f, fieldW, ROW_H - 4f,
                spec, slots[i], ImGui.getTextLineHeight(),
            )
            if (changed != null) {
                doc.updateVariable(v.name, v.type, slots.toMutableList().also { it[i] = changed }.toList())
            }
            row += ROW_H
        }
        return row
    }

    private fun defaultField(dl: ImDrawList, doc: EditorDoc, v: GraphVariable, x: Float, y: Float, w: Float) {
        val spec = PinSpec("Default", v.type, default = v.default)
        if (!ValueEditors.editable(spec)) {
            val note = if (v.type.isList) "built by the graph" else "—"
            PanelBits.label(dl, x, y, note, ValueEditors.UNEDITABLE, ROW_H)
            return
        }
        val fieldW = minOf(w, NodeGeometry.editorWidth(spec))
        val changed = ValueEditors.draw(
            widgets, "vd|${v.name}", x, y + 2f, fieldW, ROW_H - 4f, spec, v.default, ImGui.getTextLineHeight(),
        )
        if (changed != null) doc.updateVariable(v.name, v.type, changed)
    }

    /**
     * Open the shared type picker for [id], anchored under whatever was clicked.
     *
     * The same popup the canvas opens for a list's element type — searchable, each type described and shown
     * in the colour its pins will be. It replaced a plain ImGui menu of lowercased enum names, which listed
     * the types without saying what any of them was for.
     *
     * The choice comes back through [ScriptsPanel], which routes it by this id. That is deliberate: exactly
     * one surface may render the picker per frame, and the sidebar is not it.
     */
    /**
     * The "Of" of a list-typed pin or field: a chip naming the element type (`Any` when unset), opening the
     * element picker under [pickerId] — routed by the host like every other choice. What is offered is what
     * a list may hold — the registry's declarable types and this document's own records and enums.
     */
    private fun elementChip(dl: ImDrawList, doc: EditorDoc, pickerId: String, widgetId: String, x: Float, y: Float, elem: TypeRef?) {
        val label = "Of " + (elem?.let { Types.label(it) } ?: "Any")
        val w = ImGui.calcTextSize(label).x + 26f
        ImGui.setCursorScreenPos(x, y + 2f)
        if (ImGui.invisibleButton(widgetId, w, ROW_H - 4f)) {
            val offered = BuiltinNodes.elementTypes.map { it.type } + doc.types.map { TypeRef.named(it.name) } + doc.enums.map { TypeRef.named(it.name) }
            ValuePicker.open(pickerId, x, y + ROW_H) { q -> ValuePicker.typeRows(offered, q) }
        }
        val hot = ImGui.isItemHovered()
        if (hot) ImGui.setTooltip("What this list holds — click to choose")
        val col = PinStyle.color(elem ?: TypeRef.WILDCARD)
        dl.addRectFilled(x, y + 3f, x + w, y + ROW_H - 3f, Theme.withAlpha(col, if (hot) 0.30f else 0.16f), 4f, ImDrawFlags.RoundCornersAll)
        dl.addCircleFilled(x + 9f, y + ROW_H * 0.5f, 3.5f, col, 12)
        PanelBits.label(dl, x + 17f, y, label, Theme.shade(col, 1.35f), ROW_H)
    }

    private fun openTypePicker(id: String, x: Float, y: Float) {
        // What THIS document can name: the host's types plus its own declarations. Asked as the popup
        // opens rather than captured when the panel was built, so a record declared a moment ago is there.
        val doc = currentDoc
        val offered = VAR_TYPES + (doc?.types.orEmpty().map { TypeRef.named(it.name) }) + (doc?.enums.orEmpty().map { TypeRef.named(it.name) })
        ValuePicker.open(id, x, y) { q -> ValuePicker.typeRows(offered, q) }
    }

    /** The document being rendered this frame, for the popups that outlive the call that opened them. */
    private var currentDoc: EditorDoc? = null

    // ---- dragging ----------------------------------------------------------------------------------

    /**
     * Drag out of the panel to place a node — the primary interaction, not a row menu.
     *
     * Armed on press over a row and only becomes a drag once the pointer has actually moved, so a click to
     * select is never mistaken for the start of one. Held Ctrl makes it a Set rather than a Get, and
     * dropping onto a compatible pin wires it up — the same "do not make me do the wiring" principle as
     * dropping a link on empty canvas.
     */
    private fun armDrag(
        variable: String?,
        nodeType: String?,
        function: String? = null,
        declaredType: String? = null,
    ) {
        if (!ImGui.isMouseClicked(ImGuiMouseButton.Left)) return
        pendingDrag = Drag(variable, nodeType, function, declaredType)
        dragArmed = false
        ImGui.resetMouseDragDelta(ImGuiMouseButton.Left)
    }

    private fun dragOverlay() {
        val drag = pendingDrag ?: return
        if (!ImGui.isMouseDown(ImGuiMouseButton.Left)) {
            val m = ImGui.getMousePos()
            if (dragArmed) {
                drop = OutlineDrop(
                    drag.variable, drag.nodeType, drag.function, drag.declaredType,
                    ImGui.getIO().keyCtrl, m.x, m.y,
                )
            }
            pendingDrag = null
            dragArmed = false
            return
        }
        val d = ImGui.getMouseDragDelta(ImGuiMouseButton.Left, 0f)
        if (!dragArmed && Math.abs(d.x) + Math.abs(d.y) < DRAG_SLOP) return
        dragArmed = true

        val m = ImGui.getMousePos()
        val label = drag.variable ?: drag.function ?: drag.declaredType
            ?: catalog[drag.nodeType ?: ""]?.title ?: "node"
        val text = when {
            drag.declaredType != null && ImGui.getIO().keyCtrl -> "Split $label"
            drag.declaredType != null -> "Make $label"
            drag.function != null -> "Call $label"
            drag.variable != null && ImGui.getIO().keyCtrl -> "Set $label"
            drag.variable != null -> "Get $label"
            else -> label
        }
        // The FOREGROUND list, so the chip is visible over the canvas child it is being dragged onto.
        val dl = ImGui.getForegroundDrawList()
        val tw = ImGui.calcTextSize(text).x
        val x = m.x + 12f
        val y = m.y + 6f
        dl.addRectFilled(x, y, x + tw + 14f, y + 20f, CHIP_BG, 4f, ImDrawFlags.RoundCornersAll)
        dl.addRect(x, y, x + tw + 14f, y + 20f, Theme.ACCENT, 4f, ImDrawFlags.RoundCornersAll, 1f)
        dl.addText(x + 7f, y + (20f - ImGui.getTextLineHeight()) * 0.5f, Theme.TEXT, text)
    }

    // ---- actions -----------------------------------------------------------------------------------

    private fun newVariable(doc: EditorDoc) {
        val name = doc.freeVariableName()
        doc.addVariable(GraphVariable(name, TypeRef(PinType.INT), 0, isExported = true))
        selected = name
    }

    /** Walk the viewport through each use, the way "find next" does. */
    private fun cycleUses(doc: EditorDoc, nodeType: String?, variable: String?) {
        val key = variable ?: nodeType ?: return
        val hits = doc.nodes.filter {
            if (variable != null) it.variable == variable else it.type == nodeType
        }.map { it.id }.sorted()
        if (hits.isEmpty()) return
        val i = (cycle[key] ?: -1) + 1
        cycle[key] = i % hits.size
        onReveal(hits[i % hits.size])
    }

    /**
     * What typed text means for a variable's default — the same decision the canvas makes. See Literals.
     *
     * The document comes along for its ENUMS: a variable of a declared enum has a text form (its member's
     * name) and every other declared type has none, so without them typing a perfectly good member into
     * the field cleared the default instead of setting it.
     */
    private fun coerce(text: String, type: TypeRef, doc: EditorDoc?): Any? =
        dev.ziggle.vscript.editor.host.textFor(type)?.parse(text) ?: Literals.of(type, text, doc?.enums.orEmpty())

    companion object {
        const val MIN_W = 200f
        const val MAX_W = 460f

        /** Wide enough for the expanded row's three actions to sit on one line at the default font. */
        const val DEFAULT_W = 272f

        private const val HEAD_H = 38f
        private const val FIELD_H = 24f
        private const val PLUS_W = 26f
        private const val SECTION_H = 26f
        private const val ROW_H = 21f
        private const val PAD = 10f
        private const val INDENT = 22f
        private const val FIELD_LABEL_W = 56f
        private const val ACTION_GAP = 12f
        private const val GRIP = 5f
        private const val DRAG_SLOP = 4f

        private const val ADD_MENU = "##vs-outline-add-menu"
        private const val ROW_MENU = "##vs-outline-row"
        private const val NAME_FIELD_W = 120f

        private val SECTION_TEXT = Theme.col(0x8F, 0x9A, 0xAD)
        private val FIELD_BG = Theme.col(0xFF, 0xFF, 0xFF, 0x0D)
        private val SEL_BG = Theme.col(0x4A, 0x7F, 0xD4, 0x24)
        private val EVENT_COL = Theme.col(0xC1, 0x70, 0x6A)
        private val CHIP_BG = Theme.col(0x1B, 0x1F, 0x27, 0xF5)

        /**
         * Types a graph variable or a function pin may take — asked of the registry, never listed.
         *
         * It used to be a literal list here, which meant this file had an opinion about the type system and
         * would have gone stale the moment a document could declare its own. See [Types.forVariables].
         */
        private val VAR_TYPES: List<TypeRef> get() = Types.forVariables.map { it.type }
    }
}
