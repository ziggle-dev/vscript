package dev.ziggle.vscript.editor.graph

import dev.ziggle.imgui.Theme
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef

/**
 * Visual language for the canvas: what colour a pin and its wire are, and what colour a node's header is.
 *
 * Colour is how a graph is read at a glance, so type → colour is a fixed mapping rather than a per-node
 * choice: once an author learns "blue is a tile", every tile pin and every tile wire in every graph is
 * blue. Values are pre-packed ImGui draw-list U32s via [Theme.col], matching the rest of the chrome.
 */
object PinStyle {

    /** Exec pins and wires are near-white, so control flow reads as the spine of the graph. */
    val EXEC = Theme.col(0xE8, 0xEC, 0xF5)

    /**
     * A type's colour.
     *
     * A type a document declared has no entry here and cannot have one, so its colour is DERIVED from its
     * name — the same name gives the same colour in every graph, which is the property that makes colour
     * worth learning in the first place. Hue only: saturation and lightness are fixed at the built-ins'
     * values so a struct sits in the same palette rather than shouting over it, and hues near a built-in's
     * are pushed away so `Coordinate` never arrives looking like a Tile.
     */
    fun color(type: TypeRef): Int =
        // The HOST first: a domain may name a colour for its own types, which is what the three
        // hard-coded id hues below used to be. Then the builtins, then a hue derived from the name --
        // so a type nobody has an opinion about is still consistent and distinct wherever it appears.
        dev.ziggle.vscript.editor.host.EditorHost.styles.styleFor(type)?.colour
            ?: type.builtin?.let { color(it) }
            ?: derived(type.name)

    fun color(type: PinType): Int = when (type) {
        PinType.EXEC -> EXEC
        PinType.BOOL -> Theme.col(0xC0, 0x4A, 0x4A)
        PinType.INT -> Theme.col(0x4C, 0xC2, 0x8E)
        PinType.FLOAT -> Theme.col(0x8C, 0xD9, 0x5B)
        PinType.STRING -> Theme.col(0xD4, 0x5B, 0xC4)
        // A cousin of STRING, since that is what it carries — but distinct, because "one of these names"
        // and "any text" behave differently and a wire's colour should say which you are holding.
        PinType.ENUM -> Theme.col(0xB4, 0x6C, 0xD9)
        PinType.LIST -> Theme.col(0x6C, 0xB2, 0xE0)
        // The other container, so a neighbouring hue — alike at a glance, the way the three reference
        // types are, because "several of these" and "these looked up by that" are the same kind of thing
        // and the wire should say so before it says which.
        PinType.MAP -> Theme.col(0x6C, 0xE0, 0xD2)
        // Not a data hue at all, and that is the point: a function value carries BEHAVIOUR, so it is a
        // pale steel that reads as a cousin of the near-white exec wire rather than as another kind of
        // thing to hold. Lighter than the wildcard grey it sits nearest, which is the pair most at risk
        // of being confused at a glance.
        PinType.FUNCTION -> Theme.col(0xC8, 0xD8, 0xF0)
        PinType.WILDCARD -> Theme.col(0x9A, 0xA2, 0xB8)
    }

    /**
     * The REFERENCE family, by name — they stopped being [PinType]s and kept their hues.
     *
     * Derived colours would have scattered them: the whole point of these five was that they sit in one
     * band, so a reference reads as a reference at a glance and is never mistaken for the named thing it
     * points at. A hash cannot know that. Six names is a small table and it says what it means.
     */
    /**
     * Was a table of one game's reference types -- `entityref`, `npcref`, `itemref` and three more.
     *
     * Gone to the host, which is the thing that knows it has them: `EditorHost.styles`, consulted above.
     * What is left here is the DERIVATION, which needs no table because it works from the name.
     */
    private val BY_NAME: Map<String, Int> = emptyMap()

    /** Hues the built-ins already occupy, in degrees, so a derived colour keeps clear of them. */
    private val TAKEN = intArrayOf(0, 30, 45, 100, 150, 190, 210, 265, 285, 310)

    private val derivedCache = HashMap<String, Int>()

    private fun derived(name: String): Int = BY_NAME[name.lowercase()] ?: derivedCache.getOrPut(name) {
        // A stable hash rather than String.hashCode, which is stable across runs but clusters badly for
        // short similar names — and struct names in one document are short and similar by nature.
        var h = 2166136261L
        for (c in name.lowercase()) {
            h = (h xor c.code.toLong()) * 16777619L
        }
        var hue = ((h ushr 8) % 360L).toInt()
        // Nudge off any built-in's hue. A small step, repeated, rather than one big jump: it stays near
        // where the hash put it, so two names that hashed apart do not both land in the same gap.
        repeat(24) {
            val clash = TAKEN.minByOrNull { minOf(Math.abs(it - hue), 360 - Math.abs(it - hue)) } ?: return@repeat
            if (minOf(Math.abs(clash - hue), 360 - Math.abs(clash - hue)) >= 14) return@repeat
            hue = (hue + 7) % 360
        }
        hsl(hue, 0.55f, 0.62f)
    }

    /** HSL to the packed U32 [Theme.col] uses. Saturation and lightness match the built-in palette. */
    private fun hsl(hue: Int, s: Float, l: Float): Int {
        val c = (1f - Math.abs(2f * l - 1f)) * s
        val hp = hue / 60f
        val x = c * (1f - Math.abs(hp % 2f - 1f))
        val (r1, g1, b1) = when (hp.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = l - c * 0.5f
        fun ch(v: Float) = ((v + m) * 255f).toInt().coerceIn(0, 255)
        return Theme.col(ch(r1), ch(g1), ch(b1))
    }


    /** Node body fill, and the border in normal / selected states. */
    val BODY = Theme.col(0x1E, 0x21, 0x2C, 0xF5)
    val BORDER = Theme.col(0x3A, 0x40, 0x54)
    val BORDER_SELECTED = Theme.ACCENT
    val BORDER_ERROR = Theme.BAD

    /** The canvas backdrop and its grid. Declared first: the container washes are mixed against it. */
    val CANVAS_BG = Theme.col(0x12, 0x14, 0x1B)
    val GRID = Theme.col(0x22, 0x26, 0x31)
    val GRID_MAJOR = Theme.col(0x2A, 0x2F, 0x3D)

    /**
     * Comment containers, in three tones.
     *
     * Each part has its own tone: the heading strip is the lightest, the note's band sits between, and the
     * region holding the nodes is the darkest, so the nodes still read as the foreground.
     *
     * TRANSLUCENT, which it can be again.
     *
     * It was made opaque because a wire crossing behind a comment showed through as a coloured smear that
     * read as part of the note — a 77%-opaque wash does not hide what is under it, it tints it, which is
     * worse than either extreme. What changed is that wires no longer cross a container they have no
     * business in: they route around it. The smear was a symptom of the routing, and hiding it under an
     * opaque wash was treating the symptom.
     *
     * Translucency is worth having back for what it always gave — you can see the grid through a comment,
     * so a box reads as an annotation over the canvas rather than as a hole cut in it.
     */
    val COMMENT_FILL = Theme.col(0x23, 0x28, 0x36, 0xC4)
    val COMMENT_HEADER = Theme.col(0x3B, 0x44, 0x5E, 0xF0)
    val COMMENT_TEXT_BG = Theme.col(0x2D, 0x34, 0x48, 0xEC)
    val COMMENT_BORDER = Theme.col(0x4C, 0x56, 0x72)

    /** Body text: clearly readable, still a step below the heading so the two stay distinguishable. */
    val COMMENT_TEXT = Theme.col(0xC2, 0xC9, 0xDB)

    /** Radius of a data pin's circle, and the half-height of an exec pin's triangle. */
    const val PIN_RADIUS = 5f
    const val PIN_SLOT = 14f
}
