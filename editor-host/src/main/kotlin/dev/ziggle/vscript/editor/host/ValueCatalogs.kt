package dev.ziggle.vscript.editor.host

import dev.ziggle.vscript.model.TypeRef

/**
 * A picture the host can draw, named in the host's own terms.
 *
 * Deliberately empty, and deliberately not an id or an enum. The editor's job is to carry this from the
 * catalogue that minted it to the [IconSource] that resolves it, and to do that without ever learning
 * what an item is. `SpriteStore.Kind.ITEM` used to travel this path instead, which is three words of one
 * game's vocabulary sitting in the middle of a value picker.
 *
 * A host implements it with whatever it actually needs — a kind plus an id, a file path, a texture handle
 * it has already uploaded.
 */
interface IconRef

/**
 * A rectangle of a texture in normalised UV — one cell of an atlas, or the whole picture by default.
 *
 * [texture] is a `Long` and not an `Int`, and that is the whole of what the type has to say.
 *
 * It began as an OpenGL texture name, which is a 32-bit integer, and the host that draws these happens to
 * be Minecraft. From Minecraft 1.21.6 the game no longer hands out GL names: its Blaze3D layer owns
 * texture handles behind an abstraction, they are 64-bit, and on a Vulkan backend there is no GL name to
 * be had at all. A host that could only answer with an `Int` would be stuck on an old renderer for
 * reasons that have nothing to do with this editor.
 *
 * ImGui's draw list has always taken a `Long` here, so widening costs nothing on either side: the
 * conversions that used to sit at the two `addImage` call sites are simply gone.
 */
class IconRegion(val texture: Long, val u0: Float = 0f, val v0: Float = 0f, val u1: Float = 1f, val v1: Float = 1f)

/** Resolves an [IconRef] to a texture handle, or null when there is no picture for it. */
fun interface IconSource {
    fun texture(ref: IconRef): Long?

    /** The icon as a region of a texture; the default is the whole of [texture]. Atlases override this. */
    fun region(ref: IconRef): IconRegion? = texture(ref)?.let { IconRegion(it) }
}

/**
 * A searchable set of values a pin of some type can hold.
 *
 * This is the whole of what the editor knows about a domain's ids. It replaces three concrete indexes and
 * a location database that the value picker reached for directly, and with them the assumption that
 * "things a pin can hold" means items, NPCs and scene objects — which is one game's answer to a question
 * every domain has.
 *
 * [note] is the small print on the right of a row. For a scene thing that is where in the world it is,
 * which is far more use than its id when you are choosing between four doors with the same name — but
 * *what* is worth saying is the host's call, so it arrives already decided rather than being computed
 * here from a database the editor had to open.
 */
interface ValueCatalog {

    /** Ranked matches for [query]. */
    fun search(query: String, limit: Int): List<Entry>

    /**
     * The first [limit] entries, for a blank query.
     *
     * A blank query browses rather than showing nothing — for a short vocabulary that is the whole list,
     * and for a big catalogue it is the first page, so the popup is never an empty box waiting to be told
     * what to do.
     */
    fun browse(limit: Int): List<Entry>

    /** What a stored value should read as, or null when this catalogue does not recognise it. */
    fun labelOf(value: Any?): String?

    /** The picture for a stored value, or null when it has none. */
    fun icon(value: Any?): IconRef?

    class Entry(
        val value: Any,
        val label: String,
        val note: String = "",
        val icon: IconRef? = null,
    )
}

/**
 * Which types this host has a catalogue for.
 *
 * Returning null is not a failure — it is a type whose values are just numbers here, and the editor falls
 * back to a plain field. That fallback is what lets the same editor open against a domain with no
 * catalogues at all, which is the only reason this interface exists rather than three concrete indexes.
 */
fun interface ValueCatalogs {
    /**
     * By `TypeRef`, not by pin type.
     *
     * It took a `PinType` while `ITEM`, `NPC` and `OBJECT` were builtins. They are the node pack's now,
     * so the question is asked about a TYPE — which is also the only form that can name a domain's own.
     */
    fun catalogFor(type: TypeRef): ValueCatalog?

    companion object {
        /** A host with no catalogues. Every pin is a plain field, and everything still works. */
        val NONE: ValueCatalogs = ValueCatalogs { null }
    }
}
