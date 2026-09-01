package dev.ziggle.vscript.editor.graph

import imgui.ImGui
import imgui.flag.ImGuiMouseButton
import dev.ziggle.imgui.Theme
import dev.ziggle.vscript.editor.graph.PinStyle
import dev.ziggle.vscript.editor.host.EditorHost
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.Literals
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeInfo
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.model.Types

/**
 * One editor per pin type, in one place.
 *
 * Both surfaces that let you type a value — the inline fields on a node and the default on a variable in
 * the outline — go through here. They had been drifting apart already: the node knew how to draw a toggle
 * and the sidebar did not, so the same boolean was a switch in one place and a text box in the other. A
 * type's editor is a property of the type, not of where it happens to be drawn.
 *
 * Geometry arrives in **screen space** and the [WidgetContext] carries the camera, so the same call serves
 * a node at 40% zoom and a panel row at 1:1 — the panel simply hands in an identity camera.
 *
 * Types with no editor return null and draw nothing. `Entity` is one on purpose: it is a live scene handle
 * that only exists while a script runs, so there is no value an author could write.
 *
 * `List` is also absent, but for a different reason, and it is worth being precise about: lists ARE written
 * by hand — that is what the List node is for. What it does is turn one into several ordinary pins, one per
 * slot, each carrying the element's own type. So every editor a list needs is already in here, reached the
 * normal way, and a bespoke list widget would have had to reimplement all of them (including the pickers) to
 * end up somewhere worse: a grid of values you could type into but never wire.
 */
object ValueEditors {

    /** What an unset colour pin shows — opaque cyan, the same default the highlight API uses. */
    private const val DEFAULT_COLOR: Int = 0xFF00FFFF.toInt()

    /** The transparency checkerboard behind a swatch. */
    private const val CHECKER_DARK: Int = 0xFF3A3A40.toInt()
    private const val CHECKER_LIGHT: Int = 0xFF55555C.toInt()


    /** Does [spec] get an inline editor at all? Mirrors [NodeGeometry.editorWidth] being non-zero. */
    fun editable(spec: PinSpec): Boolean = NodeGeometry.editorWidth(spec) > 0f

    /**
     * Draw the editor for [spec] and return the new value, or null when nothing changed.
     *
     * [openPickerAt] is where a search popup should be anchored — normally just under the field.
     */
    fun draw(
        ctx: WidgetContext,
        id: String,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        spec: PinSpec,
        current: Any?,
        fontSize: Float,
    ): Any? {
        // **A type the HOST can search gets the searchable picker**, and it is asked first because a
        // catalogue is the host's own answer about its own type — no builtin can say it.
        //
        // This is where `PinType.ITEM, NPC, OBJECT -> picker(…)` used to be, and removing it without a
        // replacement is a bug the tests could not see: `NodeGeometry` went on RESERVING the width from
        // the same derivation, so an item literal drew a node sized for a field and then drew no field.
        // It took looking at the canvas.
        if (dev.ziggle.vscript.editor.host.editorFor(spec.type) == dev.ziggle.vscript.editor.host.Editor.CATALOGUE) {
            return picker(ctx, id, x, y, w, h, spec.type, current, fontSize)
        }
        // **A small record of numbers gets the boxes**, asked here for exactly the reason above: `Tile` is
        // a type the node pack declares now, not a builtin, so the `PinType.TILE -> …` arm this replaces
        // could not fire and `NodeGeometry` would have reserved three boxes' width to draw nothing.
        if (dev.ziggle.vscript.editor.host.editorFor(spec.type) == dev.ziggle.vscript.editor.host.Editor.FIELDS) {
            // The record's OWN field names label the boxes, and the host's own text transform decides
            // what a paste is — so this draws a tile, or any other small record of numbers a domain
            // registers, without the canvas knowing which.
            val record = dev.ziggle.vscript.model.HostRecords.of(spec.type.required())
            return CanvasWidgets.fields(
                ctx, id, x, y, w, h, current, fontSize,
                labels = record?.fields?.map { it.name } ?: listOf("x", "y", "plane (0-3)"),
                accepts = { text ->
                    dev.ziggle.vscript.editor.host.textFor(spec.type)?.parse(text)?.toString()
                        ?: text.ifBlank { null }
                },
            )
        }
        // **The swatch**, asked here for the reason above once more: `Color` is the drawing pack's record
        // now, so `PinType.COLOR -> colorField(…)` could not fire. It is also the one editor a derivation
        // gets WRONG rather than merely misses — four scalar fields derive to four number boxes, and a
        // colour is the value whose appearance tells you everything. The host names it; see ScriptsHost.
        if (dev.ziggle.vscript.editor.host.editorFor(spec.type) == dev.ziggle.vscript.editor.host.Editor.COLOUR) {
            return colorField(ctx, id, x, y, w, h, current ?: spec.default, fontSize)
        }
        // Otherwise dispatched on the BUILT-IN kind, so a type a document declared falls through to null —
        // no inline editor, wire-only, exactly the branch `List` and `Entity` already take.
        // Bound to a local first. `builtin` is a public property of ANOTHER module now, and Kotlin will
        // not smart-cast across that boundary — the module could in principle change it between the check
        // and the use — so the null-check and the use have to be of the same local.
        val builtin = spec.type.builtin
        return when (builtin) {
            PinType.BOOL -> {
                val before = (current as? Boolean) ?: false
                val v = CanvasWidgets.toggle(ctx, id, x, y, w, h, before)
                if (v != before) v else null
            }

            PinType.FLOAT -> {
                val before = (current as? Number)?.toDouble() ?: 0.0
                val v = CanvasWidgets.number(ctx, id, x, y, w, h, before, fontSize, 0.1, 2)
                if (v != before) v else null
            }

            PinType.INT -> {
                val before = (current as? Number)?.toDouble() ?: 0.0
                val v = CanvasWidgets.number(ctx, id, x, y, w, h, before, fontSize, 1.0, 0)
                if (v != before) v.toInt() else null
            }

            PinType.STRING -> {
                CanvasWidgets.text(ctx, id, x, y, w, h, (current as? String) ?: "", fontSize, "…")
                null // committed through pumpKeyboard, like every other text field
            }

            // A choice among TYPES gets the type picker — searchable, described, and showing the colour each
            // one will paint its pins. A chip you click to cycle is fine for three severities and wrong for
            // nine types: it never shows what is on offer, so finding "Object" means clicking past six
            // things you did not want.
            PinType.ENUM -> when {
                spec.typeChoice -> typePicker(ctx, id, x, y, w, h, current, fontSize, spec.options)
                else -> {
                    val before = (current as? String) ?: spec.options.firstOrNull() ?: ""
                    val v = CanvasWidgets.choice(ctx, id, x, y, w, h, before, spec.options, fontSize)
                    if (v != before) v else null
                }
            }

            else -> null
        }
    }

    /**
     * The colour editor: a live swatch plus the hex, editable in place.
     *
     * The swatch sits on a CHECKERBOARD so alpha reads as alpha rather than as a slightly different
     * colour — a 20%-opacity red and a dark pink are the same picture on a flat background, and telling
     * them apart is most of what you are doing when setting a highlight's transparency.
     *
     * The hex is a normal text field, so it commits down the same path every other typed pin uses
     * (pumpKeyboard → the pin's own [dev.ziggle.vscript.model.Literals] coercion) and needs no routing of
     * its own. It is also the only spelling that can be copied, pasted and diffed.
     */
    private fun colorField(
        ctx: WidgetContext,
        id: String,
        x: Float, y: Float, w: Float, h: Float,
        current: Any?,
        fontSize: Float,
    ): Any? {
        // A default arrives as the hex TEXT it was declared with, so parse that before falling back —
        // otherwise every pin with no stored literal shows one hard-coded colour regardless of its own
        // default, and the swatch quietly disagrees with what the node actually draws.
        val argb = (current as? Number)?.toInt()
            ?: (current as? String)?.let { Literals.parseColor(it) }
            ?: DEFAULT_COLOR
        val sw = minOf(h, maxOf(10f, w * 0.35f))
        // Checker under the swatch, so translucency is visible.
        val cell = maxOf(2f, sw / 4f)
        var iy = 0
        while (iy * cell < sw) {
            var ix = 0
            while (ix * cell < sw) {
                val x0 = x + ix * cell
                val y0 = y + iy * cell
                ctx.dl.addRectFilled(
                    x0, y0, minOf(x0 + cell, x + sw), minOf(y0 + cell, y + sw),
                    if ((ix + iy) % 2 == 0) CHECKER_DARK else CHECKER_LIGHT,
                )
                ix++
            }
            iy++
        }
        // Theme.argb, NOT the raw value: a draw list wants ABGR, and a colour literal is ARGB. Handing it
        // over unconverted swaps red and blue, so the swatch shows a DIFFERENT colour than the hex beside
        // it and than the thing actually drawn in the world — the one bug a colour picker must not have.
        ctx.dl.addRectFilled(x, y, x + sw, y + sw, Theme.argb(argb))
        val overSwatch = ctx.hit("$id#swatch", x, y, sw, sw)
        ctx.dl.addRect(x, y, x + sw, y + sw, if (overSwatch) Theme.TEXT else Theme.BORDER)
        if (overSwatch && ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
            ctx.consumedClick = true
            ColorPicker.open(id, x, y + sw + 4f, argb)
        }

        val gap = 4f
        val fieldX = x + sw + gap
        val fieldW = (w - sw - gap).coerceAtLeast(0f)
        if (fieldW <= 1f) return null
        CanvasWidgets.text(
            ctx, id, fieldX, y, fieldW, h,
            dev.ziggle.vscript.model.Literals.colorText(argb), fontSize, "#AARRGGBB",
        )
        return null // committed through pumpKeyboard, like every other text field
    }

    /**
     * A read-only field showing what is selected, which opens the catalogue when clicked.
     *
     * Not a text box you type an id into. Ids are how the game stores these and no reason for a person to
     * know them; typing one is a thing you do only because the editor made you.
     */
    private fun picker(
        ctx: WidgetContext,
        id: String,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        // A TypeRef, not a PinType: the catalogue belongs to the host and is keyed by the type it can
        // search, which for a domain's own type has no builtin at all.
        type: TypeRef,
        current: Any?,
        fontSize: Float,
    ): Any? {
        val over = ctx.hit(id, x, y, w, h)
        val open = ValuePicker.isOpenFor(id)
        if (over && ImGui.isMouseClicked(ImGuiMouseButton.Left) && !open) {
            ValuePicker.open(id, x, y + h + 2f) { q -> ValuePicker.rowsFor(type, q) }
            ctx.consumedClick = true
        }
        CanvasWidgets.readonly(ctx, id, x, y, w, h, ValuePicker.labelFor(type, current), fontSize, placeholder())
        if (over) {
            ImGui.setTooltip(tooltip(type, current))
            // A picture of what is selected, beside the field, only while you are looking at it.
            iconFor(type, current)?.let { icon ->
                ValuePicker.preview(ImGui.getForegroundDrawList(), icon, x, y + h * 0.5f)
            }
        }
        return null // the popup reports the choice; see ValuePicker.render
    }

    /**
     * A field showing the chosen TYPE, which opens the type picker.
     *
     * Shaped like the id pickers rather than like the cycling chip it replaces, because it is the same
     * gesture — click, search, choose — and that should not need learning twice. It shows the type's colour
     * beside the name, so the pin below it and the field above agree at a glance.
     */
    private fun typePicker(
        ctx: WidgetContext,
        id: String,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        current: Any?,
        fontSize: Float,
        /** The names this pin may take, when the DOCUMENT decides them. Empty means "ask the registry". */
        options: List<String>,
    ): Any? {
        val over = ctx.hit(id, x, y, w, h)
        val open = ValuePicker.isOpenFor(id)
        if (over && ImGui.isMouseClicked(ImGuiMouseButton.Left) && !open) {
            // Asked HERE, as the popup opens, so anything declared since the node was drawn is on offer.
            // A pin naming one of the DOCUMENT's types carries its choices with it, filled in by
            // resolveNode when the node was shaped; empty means the pin names a built-in — a list's element
            // type — so the registry answers instead. Chosen rows hand back the NAME, which is what the
            // literal stores either way.
            ValuePicker.open(id, x, y + h + 2f) { q ->
                if (options.isEmpty()) ValuePicker.typeRows(Types.declarable.map { it.type }, q) { t -> Types.label(t) }
                else ValuePicker.typeRows(options.map { TypeRef.named(it) }, q) { t -> t.name }
            }
            ctx.consumedClick = true
        }
        val chosen = current?.toString()?.trim().orEmpty()
        val info = Types.of(chosen)
            ?: chosen.takeIf { it.isNotEmpty() && options.any { o -> o.equals(it, true) } }
                ?.let { TypeInfo(it, TypeRef.named(it), "declared by this graph", authorable = false) }
        CanvasWidgets.readonly(ctx, id, x, y, w, h, info?.name ?: "", fontSize, "type…")
        if (info != null) {
            // The swatch sits at the right edge, clear of the text — the pin it describes is further right
            // still, so the two read as the same colour travelling outward.
            val cy = y + h * 0.5f
            ctx.dl.addCircleFilled(x + w - 9f, cy, 4f, PinStyle.color(info.type), 12)
        }
        if (over) ImGui.setTooltip((info?.name ?: "Choose a type") + "\n" + (info?.describe ?: ""))
        return null // the popup reports the choice; see ValuePicker.render
    }

    private fun placeholder(): String = "pick…"

    /**
     * The picture for a stored value, or null when there is none.
     *
     * Asked of the host rather than mapped from the pin type here. Which types have pictures is a
     * property of the domain, not of the language: a `PinType.ITEM` means nothing to a host that has no
     * items, and this used to be three lines asserting otherwise.
     */
    fun iconFor(type: TypeRef, current: Any?): dev.ziggle.vscript.editor.host.IconRef? =
        EditorHost.values.catalogFor(type)?.icon(current)

    /** The same, for a caller that still holds a builtin. */
    fun iconFor(type: PinType, current: Any?): dev.ziggle.vscript.editor.host.IconRef? =
        iconFor(TypeRef(type), current)

    private fun tooltip(type: TypeRef, current: Any?): String {
        val name = ValuePicker.labelFor(type, current)
        val idPart = if (current == null) "" else "  (${current})"
        return if (name.isEmpty()) "Click to choose" else "$name$idPart\nClick to change"
    }

    /**
     * Whether [pin] names one FIELD of a composite editor rather than the value itself.
     *
     * A tile's three boxes are three widgets with one value between them, so their ids carry a suffix and
     * a text commit coming back has to be merged rather than stored — otherwise typing in the y box would
     * replace the whole tile with that one number.
     */
    fun isComponent(pin: String): Boolean = pin.contains('#')

    fun baseName(pin: String): String = pin.substringBefore('#')

    /** Merge a typed component back into the whole value. */
    fun mergeComponent(pin: String, text: String, current: Any?): Any? {
        val index = pin.substringAfter('#').toIntOrNull() ?: return current
        // Comma separated, generically — see `CanvasWidgets.components`. Not a tile's parser.
        val parts = current?.toString()?.split(',')?.map { it.trim() }?.toMutableList() ?: mutableListOf()
        while (parts.size <= index) parts += "0"
        parts[index] = (text.trim().toIntOrNull() ?: 0).toString()
        return parts.joinToString(",")
    }

    /** A value's short display, used where there is no editor to draw. */
    fun display(type: PinType, value: Any?): String = when {
        value == null -> ""
        type == PinType.LIST -> if (value is Collection<*>) "List(${value.size})" else "List"
        else -> ValuePicker.labelFor(type, value)
    }

    /** Colour for a value that cannot be edited, so the panel can say so without inventing chrome. */
    val UNEDITABLE: Int = Theme.col(0x6E, 0x76, 0x88)
}
