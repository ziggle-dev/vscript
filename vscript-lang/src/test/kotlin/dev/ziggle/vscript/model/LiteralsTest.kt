package dev.ziggle.vscript.model

import dev.ziggle.vscript.vm.StructValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What typed text means.
 *
 * The rule worth pinning down is where whitespace survives. Trimming is right for a number, a choice and a
 * name; it is wrong for text, where a value is whatever the author typed — and the two paths that made this
 * decision separately had already come to disagree about it.
 */
class LiteralsTest {

    @Test
    fun `a string keeps its spaces`() {
        // Otherwise a message that reads " x " is impossible to write, with no hint as to why.
        assertEquals("  hello  ", Literals.of(PinType.STRING, "  hello  "))
        assertEquals(" ", Literals.of(PinType.STRING, " "))
        assertEquals("hi ", Literals.of(PinType.STRING, "hi "))
    }

    @Test
    fun `an empty string is the one case that means no value`() {
        assertNull(Literals.of(PinType.STRING, ""))
    }

    @Test
    fun `numbers and choices are trimmed, because the spaces are noise`() {
        assertEquals(42, Literals.of(PinType.INT, "  42 "))
        assertEquals(1.5, Literals.of(PinType.FLOAT, " 1.5"))
        assertEquals(true, Literals.of(PinType.BOOL, " true "))
        assertEquals("warn", Literals.of(PinType.ENUM, " warn "))
    }

    @Test
    fun `text that is not the declared type reads as no value`() {
        // Which callers treat as "leave what was there": half-typing a number should not wipe the value.
        assertNull(Literals.of(PinType.INT, "abc"))
        assertNull(Literals.of(PinType.FLOAT, ""))
    }

    @Test
    fun `an untyped literal takes the type of what was written`() {
        assertEquals(3, Literals.infer("3"))
        assertEquals(1.5, Literals.infer("1.5"))
        assertEquals(true, Literals.infer("true"))
        assertNull(Literals.infer(""))
        assertEquals("hello", Literals.infer("hello"))
    }

    @Test
    fun `an untyped literal that turns out to be text keeps its spaces too`() {
        // The trim belongs to the INTERPRETATION — deciding whether "  3  " is a number — and must not
        // reach the answer when the answer is a string.
        assertEquals("  hello  ", Literals.infer("  hello  "))
        assertEquals(3, Literals.infer("  3  "))
        assertEquals("   ", Literals.infer("   "))
    }

    /**
     * A structured host type gets **no answer here**, and that is the fix rather than a gap.
     *
     * This function answers "what should typed text become IN THE DOCUMENT". `HostRecord.read` answers
     * "what should a stored value become AT RUN TIME". They are different questions, and they briefly
     * shared a hook: a tile typed into a pin came back as the run-time record, the document stored that,
     * and the canvas — which edits `"x,y,plane"` — showed an empty pin while the validator reported null.
     * It took driving a live client to see, because the canvas's own fields produce stored forms directly
     * and only the generic text path came through here.
     *
     * The stored spelling of a structured type belongs to whatever renders its field, next to the widget
     * that writes it back. A language that ships no types cannot know their spellings either.
     */
    @Test
    fun `a structured host type is not read here`() {
        val record = HostRecord(
            "Coordinate",
            listOf(HostField("x", TypeRef(PinType.INT)), HostField("y", TypeRef(PinType.INT))),
            isData = true,
            read = { StructValue("Coordinate", listOf("x", "y"), arrayOf<Any?>(1, 2)) },
        )
        HostRecords.register(record)
        try {
            assertNull(
                Literals.of(record.type, "3200,3200", emptyList()),
                "a structured type has no literal form the LANGUAGE knows -- its field owns that",
            )
        } finally {
            HostRecords.reset()
        }
    }

    /**
     * A NOMINAL host type over a primitive still reads, and must.
     *
     * `Item` is an INT that refuses to be confused with an npc id, and someone typing 995 into a field has
     * no opinion about the refusing — so it reads exactly as it did while `ITEM` was a builtin. This is the
     * case that hid the bug above: `over` lands on a primitive, which IS the stored form, so ids kept
     * working while tiles and colours did not.
     */
    @Test
    fun `a nominal host type over a primitive reads as that primitive`() {
        val item = HostRecord("Item", emptyList(), over = TypeRef(PinType.INT))
        HostRecords.register(item)
        try {
            assertEquals(995, Literals.of(item.type, "995", emptyList()))
        } finally {
            HostRecords.reset()
        }
    }

    // ---- a declared enum -----------------------------------------------------------------------------
    //
    // The one declared type with a text form, because a member IS its name at run time. Without the
    // declarations there is nothing to check a name against, so the no-enums call still says null — and
    // that silence is what made `setvar Using ThreeTick` write null and report success.

    private val method = EnumType("Method", listOf("Afk", "ThreeTick"))

    @Test
    fun `a declared enum reads its member by name`() {
        assertEquals("ThreeTick", Literals.of(TypeRef.named("Method"), "ThreeTick", listOf(method)))
    }

    @Test
    fun `the declaration decides the spelling`() {
        // The same rule Lower follows when it stores a member: what the enum says, not what was typed.
        assertEquals("ThreeTick", Literals.of(TypeRef.named("Method"), " threetick ", listOf(method)))
    }

    @Test
    fun `a name the enum does not declare stays null`() {
        // "Leave the value alone" rather than "store this" — a typo must not become a value.
        assertNull(Literals.of(TypeRef.named("Method"), "Sideways", listOf(method)))
    }

    @Test
    fun `a declared enum with no declarations to hand is still null`() {
        assertNull(Literals.of(TypeRef.named("Method"), "ThreeTick"))
    }

    @Test
    fun `a list of a declared enum reads element by element`() {
        assertEquals(
            listOf("Afk", "ThreeTick"),
            Literals.of(TypeRef.list(TypeRef.named("Method")), "Afk, ThreeTick", listOf(method)),
        )
    }
}
