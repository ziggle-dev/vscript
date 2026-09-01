package dev.ziggle.vscript.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Type references.
 *
 * The point of the class is that a type is a NAME, so the tests are about names: that a built-in's is its
 * old enum constant name (which is what makes the format change cost nothing), that an unrecognised one
 * survives as itself rather than widening to a wildcard, and that a list carries what it holds.
 */
class TypeRefTest {

    @Test
    fun `a built-in is interned, so the common case allocates nothing`() {
        assertSame(TypeRef(PinType.INT), TypeRef(PinType.INT))
        assertSame(TypeRef(PinType.INT), TypeRef.named("INT"))
    }

    /**
     * A built-in's name IS its enum constant name.
     *
     * This is the whole reason the format bump is free: `"INT"` written by an older client reads back as
     * the same type, so every existing document round-trips byte for byte.
     */
    @Test
    fun `every built-in keeps its old persisted name`() {
        for (p in PinType.values()) {
            assertEquals(p.name, TypeRef(p).name, "persisted name for $p")
            assertEquals(TypeRef(p), TypeRef.parse(p.name), "round trip for $p")
        }
    }

    /**
     * An unknown name comes back as itself, NOT as a wildcard.
     *
     * Reading through the enum used to widen anything unrecognised to `WILDCARD`, which connects to
     * anything — so a document naming a type it had since deleted kept compiling and quietly stopped
     * meaning what it said. Keeping the name is what lets the validator say which one is missing.
     */
    @Test
    fun `an unknown name is kept rather than widened`() {
        val t = TypeRef.parse("Coordinate")
        assertEquals("Coordinate", t.name)
        assertTrue(t.declared, "a name nothing registered should read as declared")
        assertFalse(t.isWildcard, "widening to a wildcard is the one answer that must not be given")
    }

    @Test
    fun `a list carries what it holds, through the round trip`() {
        val items = TypeRef.list(TypeRef.named("Item"))
        assertEquals("LIST<Item>", items.toString())
        assertEquals(items, TypeRef.parse("LIST<Item>"))
        assertEquals(TypeRef.named("Item"), items.of)
        assertNotEquals(items, TypeRef.list(TypeRef.named("Coordinate")))
        assertNotEquals(items, TypeRef(PinType.LIST))
    }

    @Test
    fun `a list of a declared type round-trips too`() {
        val t = TypeRef.list(TypeRef.named("Coordinate"))
        assertEquals("LIST<Coordinate>", t.toString())
        assertEquals(t, TypeRef.parse("LIST<Coordinate>"))
    }

    @Test
    fun `an empty or missing name is a wildcard`() {
        assertEquals(TypeRef.WILDCARD, TypeRef.parse(null))
        assertEquals(TypeRef.WILDCARD, TypeRef.parse(""))
        assertEquals(TypeRef.WILDCARD, TypeRef.parse("   "))
    }

    @Test
    fun `names are matched case-insensitively but kept as written`() {
        assertEquals(TypeRef(PinType.INT), TypeRef.named("int"))
        assertEquals("Coordinate", TypeRef.named("Coordinate").name)
    }

    // ---- connection rules -------------------------------------------------------------------------

    @Test
    fun `exec only connects to exec`() {
        assertTrue(canConnect(TypeRef.EXEC, TypeRef.EXEC))
        assertFalse(canConnect(TypeRef.EXEC, TypeRef(PinType.INT)))
        assertFalse(canConnect(TypeRef(PinType.INT), TypeRef.EXEC))
        assertFalse(canConnect(TypeRef.EXEC, TypeRef.WILDCARD), "a wildcard is still not control flow")
    }

    /**
     * INT widens, FLOAT does not narrow — and the widening carries an OBLIGATION.
     *
     * The permission alone is what this used to be, and it made the declared type a claim nothing
     * enforced: a FLOAT field fed by an INT held an `Int` for the whole run. `widens` is the other half,
     * and it lives beside `canConnect` so the two are one edit apart rather than in different files.
     */
    @Test
    fun `int widens to float but not the other way`() {
        assertTrue(canConnect(TypeRef(PinType.INT), TypeRef(PinType.FLOAT)))
        assertFalse(canConnect(TypeRef(PinType.FLOAT), TypeRef(PinType.INT)))
    }

    @Test
    fun `widening says where a conversion has to be emitted`() {
        assertTrue(widens(TypeRef(PinType.INT), TypeRef(PinType.FLOAT)), "this is the one that converts")
        assertFalse(widens(TypeRef(PinType.INT), TypeRef(PinType.INT)), "same type, nothing to do")
        assertFalse(widens(TypeRef(PinType.FLOAT), TypeRef(PinType.FLOAT)))
        // A wildcard's value has no established type, so there is nothing safe to convert it TO — see
        // GraphCompiler.widen for why that is a limit rather than an oversight.
        assertFalse(widens(TypeRef.WILDCARD, TypeRef(PinType.FLOAT)))
        assertFalse(widens(TypeRef.list(TypeRef(PinType.INT)), TypeRef.list(TypeRef(PinType.FLOAT))))
    }

    @Test
    fun `narrowing names the nodes that would fix it`() {
        assertEquals(
            "round, floor, ceil or toInt",
            conversionFor(TypeRef(PinType.FLOAT), TypeRef(PinType.INT)),
        )
        // Widening needs no advice — it is allowed, and converts itself.
        assertNull(conversionFor(TypeRef(PinType.INT), TypeRef(PinType.FLOAT)))
        // Not every refusal has a conversion, and inventing one would send the reader somewhere useless.
        assertNull(conversionFor(TypeRef(PinType.STRING), TypeRef(PinType.INT)))
    }

    /**
     * A list of items is not a list of tiles.
     *
     * The old rule compared only the outer kind, so it was — and the mis-wiring survived into a run, where
     * the host casting an element is where it finally went wrong, a long way from the wire that caused it.
     */
    @Test
    fun `lists compare what they hold`() {
        val items = TypeRef.list(TypeRef.named("Item"))
        val spots = TypeRef.list(TypeRef.named("Coordinate"))
        assertTrue(canConnect(items, items))
        assertFalse(canConnect(items, spots), "a list of items wired into a list of coordinates")
    }

    /** An unconstrained list still goes either way — a `ForEach` over an unlabelled wire needs that. */
    @Test
    fun `an unconstrained list accepts and is accepted`() {
        val bare = TypeRef(PinType.LIST)
        val items = TypeRef.list(TypeRef.named("Item"))
        assertTrue(canConnect(items, bare))
        assertTrue(canConnect(bare, items))
    }

    @Test
    fun `two declared types connect only to themselves`() {
        val coord = TypeRef.named("Coordinate")
        val task = TypeRef.named("BankTask")
        assertTrue(canConnect(coord, TypeRef.named("Coordinate")))
        assertFalse(canConnect(coord, task))
        assertFalse(canConnect(coord, TypeRef(PinType.INT)), "a declared type is not an int in disguise")
        assertTrue(canConnect(coord, TypeRef.WILDCARD), "a wildcard still takes anything")
    }

    /** A pin declared the built-in way still produces the same type — 200 declarations depend on it. */
    @Test
    fun `the built-in spelling of a pin is unchanged`() {
        assertEquals(TypeRef(PinType.INT), PinSpec("A", PinType.INT).type)
        val list = PinSpec("Items", TypeRef.list(TypeRef.named("Item")))
        assertEquals(TypeRef.list(TypeRef.named("Item")), list.type)
        assertEquals(TypeRef.named("Item"), list.elementType)
    }

    // ---- the names the PICKER shows must be the names you can WRITE ------------------------------------

    /**
     * `ItemRef` is what [Types] calls `ITEM_REF`, and it has to resolve to it.
     *
     * Matching only the enum constant made the friendly spelling a DECLARED type, so
     * `fn Inventory.contents(self) -> List<ItemRef>` reported "'contents.Result' is typed 'ItemRef', which this
     * graph does not declare" and then refused to wire `LIST<ITEM_REF>` into `LIST<ItemRef>` — two names for
     * one type, disagreeing with each other. Underscores are ignored, so every spelling of it works.
     */
    @Test
    fun `the friendly spelling of a built-in resolves to it`() {
        for (written in listOf("Wildcard", "WILDCARD", "wildcard", "WILDCARD ")) {
            assertEquals(
                PinType.WILDCARD, TypeRef.named(written).builtin,
                "'$written' should be the built-in, not a declared type",
            )
        }
    }

    /**
     * `ItemRef` used to be a [PinType] and is now a host record — a NAME, like `Json` and like every type
     * a node library contributes. The rule it used to demonstrate (underscores and case ignored when
     * matching a built-in) is unchanged; what changed is that this particular type is not one.
     */
    @Test
    fun `a host record is a named type, not a built-in`() {
        for (written in listOf("ItemRef", "EntityRef", "NpcRef", "ObjectRef", "GroundItemRef", "WidgetRef")) {
            val t = TypeRef.named(written)
            assertEquals(null, t.builtin, "'$written' should be a named host type")
            assertEquals(written, t.name)
        }
    }

    /** `Any` and `Choice` are the registry's names for kinds whose constants are spelled nothing like them. */
    @Test
    fun `the two names that are not the constants at all resolve`() {
        assertEquals(PinType.WILDCARD, TypeRef.named("Any").builtin)
        assertEquals(PinType.ENUM, TypeRef.named("Choice").builtin)
    }

    /** Every name the registry offers is writable — the general form of the bug, so it cannot come back. */
    @Test
    fun `every type the registry offers can be written`() {
        for (info in Types.all) {
            assertEquals(
                info.type, TypeRef.named(info.name),
                "'${info.name}' is offered by the registry but does not resolve to its type",
            )
        }
    }

    /** A name nobody declares is still declared-by-a-document, which is what the whole design rests on. */
    @Test
    fun `an unknown name is still a declared type`() {
        assertEquals(null, TypeRef.named("Coordinate").builtin)
    }

    // ---- phase D: several arguments, and optionals ---------------------------------------------------

    /**
     * **`parse` used to drop the arguments of anything that was not a list.**
     *
     * One line — `return if (outer.isList) list(inner) else outer` — and it could not bite while `LIST` was
     * the only parameterized type there was, which is exactly why it had to go before there was a second.
     * A `MAP<Coordinate, INT>` that parses to a bare `MAP` is not a parse failure; it is a type quietly
     * forgetting what it was, and every check downstream then agrees with it.
     */
    @Test
    fun `a parameterized type keeps its arguments`() {
        val t = TypeRef.parse("MAP<Coordinate, INT>")
        assertEquals(2, t.args.size, "arguments were dropped")
        assertEquals(TypeRef.named("Coordinate"), t.args[0])
        assertEquals(TypeRef(PinType.INT), t.args[1])
        assertEquals("MAP<Coordinate, INT>", t.toString())
    }

    /** The commas that split are the ones at depth 0 — otherwise the nesting is read as one long name. */
    @Test
    fun `nested arguments split at the top level only`() {
        val t = TypeRef.parse("MAP<Coordinate, LIST<INT>>")
        assertEquals(2, t.args.size)
        assertEquals(TypeRef.list(TypeRef(PinType.INT)), t.args[1])
        assertEquals("MAP<Coordinate, LIST<INT>>", t.toString())
    }

    /** Which is the bug the split fixes: without it the tail is a DECLARED type whose name has a comma. */
    @Test
    fun `a second argument is not read as part of the first name`() {
        assertNotEquals(TypeRef.named("Coordinate, INT"), TypeRef.parse("MAP<Coordinate, INT>").args.firstOrNull())
    }

    @Test
    fun `an optional round-trips through its persisted form`() {
        val t = TypeRef.parse("Coordinate?")
        assertTrue(t.optional)
        assertEquals(TypeRef.named("Coordinate"), t.required())
        assertEquals("Coordinate?", t.toString())
        assertEquals(t, TypeRef.parse(t.toString()))
    }

    /** The `?` is outermost: an optional LIST of INTs, never a list of optional INTs. */
    @Test
    fun `the question mark binds outside the arguments`() {
        val t = TypeRef.parse("LIST<INT>?")
        assertTrue(t.optional)
        assertFalse(t.of!!.optional)
        assertEquals("LIST<INT>?", t.toString())
    }

    @Test
    fun `optional is part of identity`() {
        assertNotEquals(TypeRef(PinType.BOOL), TypeRef(PinType.BOOL).orNull())
        assertEquals(TypeRef(PinType.BOOL), TypeRef(PinType.BOOL).orNull().required())
        // `assertSame`, so this must be an INTERNED type: only the builtins are, which is why a declared
        // name is used everywhere else in this file and a builtin here.
        assertSame(TypeRef(PinType.BOOL), TypeRef(PinType.BOOL).required())
    }

    /**
     * `T` flows into `T?`; `T?` does not flow into `T`. The whole optional rule, and the reason the
     * `GETFIELD on null` crash `salamander` documents becomes a wire the editor refuses to draw.
     */
    @Test
    fun `a plain value fits an optional slot and not the other way round`() {
        val spot = TypeRef.named("Coordinate")
        assertTrue(canConnect(spot, spot.orNull()))
        assertFalse(canConnect(spot.orNull(), spot))
        assertTrue(canConnect(spot.orNull(), spot.orNull()))
    }

    /**
     * A wildcard still accepts one, and it has to: a wildcard says "whatever is connected decides", not
     * "this is definitely there", and most host pins are wildcards. Refusing here would mean an optional
     * could not be logged.
     */
    @Test
    fun `a wildcard accepts an optional`() {
        assertTrue(canConnect(TypeRef.named("Coordinate").orNull(), TypeRef.WILDCARD))
    }

    /** Nothing above changes what an existing document says, which is what makes it need no migration. */
    @Test
    fun `the persisted form of everything that already existed is unchanged`() {
        for (s in listOf("INT", "LIST", "LIST<INT>", "LIST<ITEM_REF>", "Coordinate", "LIST<Coordinate>")) {
            assertEquals(s, TypeRef.parse(s).toString(), "persisted form of '$s' moved")
        }
    }
}
