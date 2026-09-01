package dev.ziggle.vscript.model

/**
 * What text typed into a value field means.
 *
 * **Whitespace is significant in a string and noise everywhere else.** Trimming is right for a number
 * ("` 42 `" is 42), for a choice (it has to match an option), and for a name (an identifier with a trailing
 * space is a mistake every time). It is wrong for text: a string value is whatever the author typed, and
 * silently eating the spaces at either end of it makes a message that reads " x " impossible to write and
 * gives no hint why.
 *
 * So the trim happens on the way to the *interpretation* and never to the result when that result is a
 * string. Kept in the model, and shared, because the same decision was being made independently by the
 * canvas and the outline — which is how they came to disagree in the first place.
 */
object Literals {

    /**
     * The value of [text] for a pin with no declared type.
     *
     * The type follows what was written, so one Literal node serves every pin. Order matters: `true` is a
     * boolean before it is a string, `3` is an int before it is a double, and anything left over is text —
     * returned **exactly as typed**.
     */
    fun infer(text: String): Any? {
        val t = text.trim()
        if (t.isEmpty()) return if (text.isEmpty()) null else text
        t.toIntOrNull()?.let { return it }
        t.toLongOrNull()?.let { return it }
        t.toDoubleOrNull()?.let { return it }
        if (t.equals("true", true)) return true
        if (t.equals("false", true)) return false
        if (t.equals("null", true)) return null
        return text
    }

    /**
     * The value of [text] for a pin of a known [type].
     *
     * Returns null when the text cannot be that type, which callers read as "leave it alone" rather than
     * "store nothing" — half-typing a number should not wipe the value that was there.
     */
    /** `#AARRGGBB` / `#RRGGBB` / bare hex / a plain decimal Int. Null when it is none of those. */
    fun parseColor(text: String): Int? {
        val t = text.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
        if (t.isEmpty()) return null
        val hex = t.toLongOrNull(16)
        if (hex != null) {
            return when (t.length) {
                6 -> (0xFF000000L or hex).toInt()   // opaque
                8 -> hex.toInt()
                else -> null
            }
        }
        return text.trim().toIntOrNull()
    }

    /** The persisted/edited spelling of [argb] — always the full eight digits, so alpha is never implied. */
    fun colorText(argb: Int): String = "#%08X".format(argb)

    /**
     * A declared type cannot be typed in — it has no text form, so there is nothing to parse.
     *
     * A LIST can be, though, once it says what it holds: comma-separated, each item read as an element.
     * The element type is the whole reason this lives on the [TypeRef] overload and not the [PinType] one
     * — `LIST` alone cannot parse `526` into anything, because nothing knows whether that is an item id
     * or a plain number.
     *
     * **With one exception, and [enums] is what buys it: a declared ENUM does have a text form.** A member
     * is its own NAME at run time — the invariant the whole enum design rests on — so `"ThreeTick"` is
     * already the value, and all this has to do is agree that the enum declares it.
     *
     * Without that, a declared type fell to `type.builtin`, which is null for one, and the whole call
     * handed back null. Callers read null as "the text is not that type, leave the value alone", so a
     * `setvar Using ThreeTick` looked like it worked and quietly made the variable null instead. It was
     * observed live: the console said `switching to null` and the session ran the other branch of a `when`
     * for a minute while looking exactly like it was working. `fisher.vs` still carries the note.
     *
     * A name the enum does not declare stays null, which is the same "leave it alone" the rest of this
     * object means by it — a typo must not become a value. The answer is the DECLARATION's spelling, not
     * what was typed, for the same reason `Lower` stores a member canonically.
     */
    @JvmOverloads
    fun of(type: TypeRef, text: String, enums: List<EnumType> = emptyList()): Any? {
        if (type.isList) {
            val elem = type.of ?: return null
            return text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { of(elem, it, enums) }
        }
        type.builtin?.let { return of(it, text) }
        // A HOST type that is a nominal name over something simpler parses as that simpler thing.
        // `Item` is an INT that refuses to be confused with an npc id, and a field someone typed 995 into
        // has no opinion about the refusing — so the literal reads exactly as it did while ITEM was a
        // builtin. Without this a canvas item literal silently came back null, which reads as "leave the
        // pin alone" and is the most invisible failure this file has: the pin keeps its default and the
        // wrongness surfaces somewhere else entirely.
        HostRecords.of(type)?.over?.let { return of(it, text, enums) }
        // **A STRUCTURED host type has no answer here, and asking [HostRecord.read] for one was a bug.**
        //
        // This function answers "what should typed text become IN THE DOCUMENT". `read` answers "what
        // should a stored value become AT RUN TIME". They are different questions and briefly shared a
        // hook: a tile typed into a pin came back as the run-time record, the document stored that, and
        // the canvas — which edits `"x,y,plane"` — then showed an empty pin and the validator called it
        // null. `Item` hid it, because `over` lands on a primitive, which IS the stored form.
        //
        // The stored form of a structured type is the business of whatever renders its field: the same
        // transform that turns what you type into what is saved is the one the picker writes back
        // through, so the two cannot drift. That lives with the editor, not here — see the host's
        // `ValueText`. A language that knows no types cannot know their spellings either.
        return enums.firstOrNull { it.name.equals(type.name, true) }?.member(text)
    }

    fun of(type: PinType, text: String): Any? = when (type) {
        // As typed. An empty field is the one case that means "no value"; a field holding only spaces holds
        // spaces, which is a thing someone may well have meant.
        PinType.STRING -> text.ifEmpty { null }
        PinType.ENUM -> text.trim().ifEmpty { null }
        PinType.BOOL -> text.trim().equals("true", ignoreCase = true)
        PinType.FLOAT -> text.trim().toDoubleOrNull()
        PinType.INT -> text.trim().toIntOrNull()
        // A skill is its NAME — the host enum that declares it says so, the picker lists names, and the bodies
        // read names. Parsing it as an Int put "Gathering" through toIntOrNull(), got null, and null
        // means "leave it alone": the pin kept its DEFAULT and the failure was completely silent. What
        // that looked like downstream was a gathering script reading a stat and choosing
        // willows for a level 5 account — so the wrongness surfaced three nodes away from its cause.

        PinType.WILDCARD -> infer(text)
        else -> null
    }
}
