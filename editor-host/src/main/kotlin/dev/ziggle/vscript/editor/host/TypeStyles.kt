package dev.ziggle.vscript.editor.host

import dev.ziggle.vscript.model.HostRecords
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef

/**
 * Which inline widget the canvas draws for a value of some type.
 *
 * Deliberately a small closed set. A domain does not get to hand the editor a paint routine — it says
 * what KIND of thing it is, and the canvas draws it in the canvas's own visual language, so a graph does
 * not become a patchwork of one widget per plugin.
 */
enum class Editor {
    /** No inline editor — a handle, a container, a wire-only value. */
    NONE,

    /** A number box. */
    NUMBER,

    /** A single-line text box. */
    TEXT,

    /** A checkbox. */
    TOGGLE,

    /** A searchable catalogue — what a type with a [ValueCatalog] gets, showing a name rather than an id. */
    CATALOGUE,

    /** A colour swatch and its hex. */
    COLOUR,

    /** Several number boxes, one per field — how a small record is typed in. */
    FIELDS,
}

/**
 * A host's opinion about how a type is shown — **every field optional**.
 *
 * Optional individually, and that is not a convenience: it is what stops the host and the derivation
 * calling each other. A host that wanted to override only the colour had to supply an editor too, and the
 * obvious way to supply one was to ask [deriveEditor] — which asked the host, which asked [deriveEditor].
 * Any type in the host's table then overflowed the stack the moment the canvas drew a node carrying it.
 *
 * Nothing caught that but a running client: the seam is installed by `Chrome`, which no test starts.
 *
 * So a host says only what it means to change, [deriveEditor] never consults a host at all, and
 * [styleOf] is the single place the two are combined.
 */
/**
 * How a type's value is read from and written to TEXT — the field's own transform.
 *
 * ### Why the field owns this and not the language
 *
 * A structured type has a form it is STORED as, and it is the field that decides it: a tile is
 * `"x,y,plane"` because that is what three number boxes edit and what you paste off a wiki page; a colour
 * is `#AARRGGBB` because that is what a swatch writes back. Neither is a fact about the language, and the
 * language briefly tried to know them — `Literals.of` reached for the record's run-time reader and
 * documents started storing run-time records, which the fields could not edit and the validator read as
 * an empty pin.
 *
 * ### One transform, so typing and picking cannot disagree
 *
 * [parse] and [format] are the same pair the widget uses. Whatever the colour picker writes, [parse]
 * accepts; whatever [format] produces, the picker shows. Put the spellings anywhere else and there are
 * two answers to "what is a colour" — which is how `0x80FF8040` came to be accepted in one place and
 * silently dropped in another.
 */
interface ValueText {

    /** Typed or pasted text, as the form the DOCUMENT stores. Null when it does not read as one. */
    fun parse(text: String): Any?

    /** A stored value, as the text a field shows. */
    fun format(stored: Any?): String
}

/**
 * What a host says about one of its types: how it looks, and how its value is written down.
 *
 * Every field is optional and answered by [derive] when absent — see [TypeStyles].
 */
class TypeStyle(
    val colour: Int? = null,
    val editor: Editor? = null,
    val width: Float? = null,
    /** The field's text transform. Only a type with a stored form of its own needs one. */
    val text: ValueText? = null,
)

/**
 * A host's chance to say how ITS types look.
 *
 * ### Why it exists
 *
 * The canvas used to hold this as a `when` over `PinType` — a colour for `ITEM`, a width for `NPC`, a
 * picker for `OBJECT`. That is the whole reason those three were in the language's type enum: not to type
 * anything, but so an editor could draw them. The types have gone to the node pack that owns them, and
 * this is what replaces the `when`.
 *
 * ### And why almost nothing needs to implement it
 *
 * [derive] answers for any type without being told anything, from what is already known:
 *
 * - a type the host has a **catalogue** for gets [Editor.CATALOGUE] — the searchable picker, showing a
 *   name instead of an id. `Item`, `Npc` and `Object` land here with no registration at all, because the
 *   host already declares those catalogues for the value picker.
 * - a **host record** of small scalar fields gets [Editor.FIELDS] — three boxes for a tile, and the same
 *   for any three-number thing a domain invents.
 * - a **builtin** gets the obvious widget.
 * - anything else gets a handle and a **hue derived from its name**, so an unregistered domain type is
 *   still a consistent, distinct colour on every node that carries it rather than an undifferentiated
 *   grey. That is the difference between "you must register every type" and "register the ones you care
 *   about".
 *
 * A host implements this only to override — a brand colour, a bespoke widget for the one type that earns
 * one.
 */
fun interface TypeStyles {
    fun styleFor(type: TypeRef): TypeStyle?

    companion object {
        /** A host with no opinions. Everything falls to [derive], which is a working editor. */
        val NONE: TypeStyles = TypeStyles { null }
    }
}

/**
 * What the canvas should do with [type], asking the host first and working it out otherwise.
 *
 * The order is the whole rule:
 *
 *  1. **A catalogue**, if the host has one for this type — the searchable picker, showing a name rather
 *     than an id. This is how `Item`, `Npc` and `Object` get their editor without anyone registering a
 *     style: the host already declares those catalogues for the value picker to use.
 *  2. **A host record of small scalar fields** — one box per field. Three for a tile, and three for any
 *     other three-number thing a domain invents, with no registration.
 *  3. **A builtin**, which the canvas has always known how to draw.
 *  4. **A handle** — no inline editor, and a hue derived from the name so the pin is still a consistent,
 *     distinct colour wherever it appears.
 *
 * Only [colour] is left to the caller: the canvas already derives a stable hue from a type's name, and
 * repeating that here would be a second answer to a question that has one.
 */
fun deriveEditor(type: TypeRef): Editor {
    if (type.isList || type.isMap || type.isFunction || type.isExec) return Editor.NONE
    // A wildcard has no widget of its OWN, and still needs one: a `Literal` node IS its value, so with
    // nothing to type into it the node is decorative. The field takes text and the value's type follows
    // what was written. See `OwnCanvas.parseLiteral`.
    if (type.isWildcard) return Editor.TEXT
    if (EditorHost.values.catalogFor(type) != null) return Editor.CATALOGUE
    HostRecords.of(type)?.let { record ->
        // Small and scalar, or it is a handle. A record of records has no sensible inline form, and a
        // wide one would push every node that carries it off the screen.
        val fields = record.fields
        val scalar = fields.all { it.type.builtin == PinType.INT || it.type.builtin == PinType.FLOAT }
        return if (fields.isNotEmpty() && fields.size <= 4 && scalar) Editor.FIELDS else Editor.NONE
    }
    return when (type.builtin) {
        PinType.INT, PinType.FLOAT -> Editor.NUMBER
        PinType.STRING, PinType.ENUM -> Editor.TEXT
        PinType.BOOL -> Editor.TOGGLE
        else -> Editor.NONE
    }
}

/** How many fields [type] is edited as, for a caller sizing an inline editor. See [Editor.FIELDS]. */
fun fieldCount(type: TypeRef): Int = HostRecords.of(type)?.fields?.size ?: 1

/**
 * The editor for [type] — the host's override if it named one, otherwise the derivation.
 *
 * The ONE place the two are combined. [deriveEditor] deliberately knows nothing about the host, so it
 * cannot be part of a cycle; a host that only wants to recolour a type leaves [TypeStyle.editor] null and
 * still gets the right widget.
 */
fun editorFor(type: TypeRef): Editor =
    EditorHost.styles.styleFor(type)?.editor ?: deriveEditor(type)

/**
 * The text transform for [type], when its host declared one.
 *
 * Null for everything else, which is the common case and means "the language's own literal rules apply".
 * Asked of the REQUIRED type: `Tile?` is stored exactly as `Tile` is, and a pin that also accepts nothing
 * does not change how the something is written down.
 */
fun textFor(type: TypeRef): ValueText? =
    EditorHost.styles.styleFor(type.required())?.text ?: EditorHost.styles.styleFor(type)?.text
