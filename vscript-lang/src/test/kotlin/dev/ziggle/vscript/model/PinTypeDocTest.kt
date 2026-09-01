package dev.ziggle.vscript.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every pin type says what it is.
 *
 * **The type is what documents most pins, so a gap here is a gap everywhere.** Per-pin `doc` coverage is
 * thin — a few dozen of five hundred and fifty — because most pins are named after what they hold and
 * writing that out each time would bury the ones that say something. The consequence is that a pin with
 * no words of its own falls back to its TYPE, and a type with no words leaves the author with nothing.
 *
 * A new [PinType] added without documentation fails this, which is the point: the enum is small enough
 * that the cost of the rule is one paragraph, and the reward is that "what goes in this slot" always has
 * an answer.
 */
class PinTypeDocTest {

    @Test
    fun `every pin type is documented`() {
        val silent = PinType.entries.filter { it.doc.isBlank() }
        assertEquals(emptyList(), silent, "pin types with no documentation")
    }

    @Test
    fun `the documentation is prose, not a label`() {
        // A one-word doc would pass the emptiness check and help nobody. Every one of these is a slot an
        // author has to fill correctly, so the bar is a sentence.
        for (type in PinType.entries) {
            assertTrue(
                type.doc.length >= 40,
                "${type.name}'s documentation is too short to be useful: '${type.doc}'",
            )
        }
    }

    @Test
    fun `a builtin type ref reports its type's documentation`() {
        assertEquals(PinType.STRING.doc, TypeRef(PinType.STRING).doc)
        assertEquals(TypeRef.named("Item").doc, TypeRef.named("Item").doc)
    }

    /**
     * A DECLARED type answers empty here, and that is deliberate rather than missing.
     *
     * Its prose is the comment above `type Point { … }` in whichever document declared it, and resolving a
     * name to a document needs a document — the same name may be declared in more than one. The editor
     * does that lookup; this class knows only the name.
     */
    @Test
    fun `a declared type has no documentation of its own here`() {
        val point = TypeRef.named("Point")
        assertTrue(point.declared, "a name nobody built in should read as declared")
        assertEquals("", point.doc)
    }
}
