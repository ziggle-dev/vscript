package dev.ziggle.vscript.compile

import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.model.resolveNode
import dev.ziggle.vscript.vm.StructValue
import dev.ziggle.vscript.vm.FiberState
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Types a document declares — records, made and taken apart.
 *
 * Run on the real VM rather than by inspecting bytecode, for the reason the function tests give: what is
 * worth proving is that a record genuinely carries its fields from one side of a wire to the other, not
 * that a particular opcode appeared.
 */
class StructTest {

    private val catalog = NodeCatalog()

    /** Compile the graph's one entry and run it, asserting it finished cleanly. */
    private fun run(g: dev.ziggle.vscript.model.Graph): List<Any?> {
        val issues = Validator(catalog).validate(g)
        assertTrue(issues.errors().isEmpty(), "$issues")
        val chunk = GraphCompiler(catalog).compile(g, g.entries(catalog).first().id)
        val result = drive(chunk)
        assertEquals(FiberState.DONE, result.fiber.state, "${result.fiber.error}")
        return result.fiber.result
    }

    /** A `Coordinate` built from two numbers, taken apart again, and its `y` returned. */
    @Test
    fun `a record carries its fields through make and break`() {
        val g = graph {
            type("Coordinate", "x" to PinType.INT, "y" to PinType.INT)

            val start = node(BuiltinNodes.ENTRY)
            val make = node(
                BuiltinNodes.STRUCT_MAKE,
                literals = mapOf(BuiltinNodes.STRUCT_OF to "Coordinate", "x" to 3200, "y" to 3428),
            )
            val take = node(BuiltinNodes.STRUCT_SPLIT, literals = mapOf(BuiltinNodes.STRUCT_OF to "Coordinate"))
            val ret = node(BuiltinNodes.RETURN)
            link(start, "Exec", ret, "Exec")
            link(make, "Value", take, "Value")
            link(take, "y", ret, "Value")
        }
        assertEquals(listOf(3428), run(g))
    }

    /** The record itself reaches the far end intact, not just one field out of it. */
    @Test
    fun `a record is one value on a wire`() {
        val g = graph {
            type("Coordinate", "x" to PinType.INT, "y" to PinType.INT)
            val start = node(BuiltinNodes.ENTRY)
            val make = node(
                BuiltinNodes.STRUCT_MAKE,
                literals = mapOf(BuiltinNodes.STRUCT_OF to "Coordinate", "x" to 12, "y" to 34),
            )
            val ret = node(BuiltinNodes.RETURN)
            link(start, "Exec", ret, "Exec")
            link(make, "Value", ret, "Value")
        }
        val v = run(g).single() as StructValue
        assertEquals("Coordinate", v.type)
        assertEquals(12, v.get("x"))
        assertEquals(34, v.get("y"))
        assertEquals("Coordinate(x=12, y=34)", v.toString())
    }

    /** Records nest, and a field of one is reached through the other's Break. */
    @Test
    fun `a record can hold another record`() {
        val g = graph {
            type("Coordinate", "x" to PinType.INT, "y" to PinType.INT)
            type("Trip", "from" to "Coordinate", "hops" to PinType.INT)

            val start = node(BuiltinNodes.ENTRY)
            val here = node(
                BuiltinNodes.STRUCT_MAKE,
                literals = mapOf(BuiltinNodes.STRUCT_OF to "Coordinate", "x" to 7, "y" to 9),
            )
            val trip = node(BuiltinNodes.STRUCT_MAKE, literals = mapOf(BuiltinNodes.STRUCT_OF to "Trip", "hops" to 3))
            val openTrip = node(BuiltinNodes.STRUCT_SPLIT, literals = mapOf(BuiltinNodes.STRUCT_OF to "Trip"))
            val openCoord = node(BuiltinNodes.STRUCT_SPLIT, literals = mapOf(BuiltinNodes.STRUCT_OF to "Coordinate"))
            val ret = node(BuiltinNodes.RETURN)
            link(start, "Exec", ret, "Exec")
            link(here, "Value", trip, "from")
            link(trip, "Value", openTrip, "Value")
            link(openTrip, "from", openCoord, "Value")
            link(openCoord, "y", ret, "Value")
        }
        assertEquals(listOf(9), run(g))
    }

    /**
     * A record is a VALUE: two built the same way are equal, and nothing shares storage.
     *
     * The compiler learned this with list literals — a constant is one object handed out at every
     * evaluation, and a pure node is re-expanded at every use site, so anything writing through a shared
     * value would quietly change it everywhere it had ever been read.
     */
    @Test
    fun `records compare by what they hold`() {
        val a = StructValue("Coordinate", listOf("x", "y"), arrayOf(1, 2))
        val b = StructValue("Coordinate", listOf("x", "y"), arrayOf(1, 2))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, StructValue("Coordinate", listOf("x", "y"), arrayOf(1, 3)))
        assertNotEquals<Any>(a, StructValue("Anchor", listOf("x", "y"), arrayOf(1, 2)))
        // `with` copies rather than writes.
        val c = a.with(0, 99)
        assertEquals(1, a[0], "the original was mutated")
        assertEquals(99, c[0])
    }

    /** Each Make evaluates afresh, so two records from one node are not the same object. */
    @Test
    fun `a make is not a shared constant`() {
        val g = graph {
            type("Box", "n" to PinType.INT)
            val start = node(BuiltinNodes.ENTRY)
            val make = node(BuiltinNodes.STRUCT_MAKE, literals = mapOf(BuiltinNodes.STRUCT_OF to "Box", "n" to 1))
            val ret = node(BuiltinNodes.RETURN)
            link(start, "Exec", ret, "Exec")
            link(make, "Value", ret, "Value")
        }
        val first = run(g).single()
        val second = run(g).single()
        assertEquals(first, second)
        assertTrue(first !== second, "two evaluations handed back the same object")
    }

    // ---- one field at a time ----------------------------------------------------------------------

    /** Get Field reads one. Break would give all six; this says which one was wanted. */
    @Test
    fun `get field reads the named one`() {
        val g = graph {
            type("Coordinate", "x" to PinType.INT, "y" to PinType.INT)
            val start = node(BuiltinNodes.ENTRY)
            val make = node(
                BuiltinNodes.STRUCT_MAKE,
                literals = mapOf(BuiltinNodes.STRUCT_OF to "Coordinate", "x" to 11, "y" to 22),
            )
            val get = node(
                BuiltinNodes.STRUCT_GET,
                literals = mapOf(BuiltinNodes.STRUCT_OF to "Coordinate", BuiltinNodes.STRUCT_FIELD to "y"),
            )
            val ret = node(BuiltinNodes.RETURN)
            link(start, "Exec", ret, "Exec")
            link(make, "Value", get, "Value")
            link(get, "y", ret, "Value")
        }
        assertEquals(listOf(22), run(g))
    }

    /**
     * Set Field hands back a COPY. The record that went in is unchanged.
     *
     * The whole meaning of the node under value semantics — and the property that makes it safe for a pure
     * node to be re-expanded at every use site. The graph returns the ORIGINAL's field, so if anything had
     * been written through, this would come back as the new value.
     */
    @Test
    fun `set field copies rather than writing through`() {
        val g = graph {
            type("Coordinate", "x" to PinType.INT, "y" to PinType.INT)
            val start = node(BuiltinNodes.ENTRY)
            val make = node(
                BuiltinNodes.STRUCT_MAKE,
                literals = mapOf(BuiltinNodes.STRUCT_OF to "Coordinate", "x" to 1, "y" to 2),
            )
            val set = node(
                BuiltinNodes.STRUCT_SET,
                literals = mapOf(
                    BuiltinNodes.STRUCT_OF to "Coordinate",
                    BuiltinNodes.STRUCT_FIELD to "y",
                    "y" to 99,
                ),
            )
            // Read the ORIGINAL back, not the copy.
            val get = node(
                BuiltinNodes.STRUCT_GET,
                literals = mapOf(BuiltinNodes.STRUCT_OF to "Coordinate", BuiltinNodes.STRUCT_FIELD to "y"),
            )
            val ret = node(BuiltinNodes.RETURN)
            link(start, "Exec", ret, "Exec")
            link(make, "Value", set, "Value")
            link(make, "Value", get, "Value")
            link(get, "y", ret, "Value")
        }
        assertEquals(listOf(2), run(g), "the record on the wire in was written through")
    }

    /** And the copy really does carry the change. */
    @Test
    fun `set field changes the one it hands back`() {
        val g = graph {
            type("Coordinate", "x" to PinType.INT, "y" to PinType.INT)
            val start = node(BuiltinNodes.ENTRY)
            val make = node(
                BuiltinNodes.STRUCT_MAKE,
                literals = mapOf(BuiltinNodes.STRUCT_OF to "Coordinate", "x" to 1, "y" to 2),
            )
            val set = node(
                BuiltinNodes.STRUCT_SET,
                literals = mapOf(
                    BuiltinNodes.STRUCT_OF to "Coordinate",
                    BuiltinNodes.STRUCT_FIELD to "y",
                    "y" to 99,
                ),
            )
            val ret = node(BuiltinNodes.RETURN)
            link(start, "Exec", ret, "Exec")
            link(make, "Value", set, "Value")
            link(set, "Result", ret, "Value")
        }
        val v = run(g).single() as StructValue
        assertEquals(99, v.get("y"))
        assertEquals(1, v.get("x"), "the untouched field moved")
    }

    /** Chained, because that is how a record held in a variable is updated. */
    @Test
    fun `set field chains`() {
        val g = graph {
            type("Coordinate", "x" to PinType.INT, "y" to PinType.INT)
            val start = node(BuiltinNodes.ENTRY)
            val make = node(
                BuiltinNodes.STRUCT_MAKE,
                literals = mapOf(BuiltinNodes.STRUCT_OF to "Coordinate", "x" to 0, "y" to 0),
            )
            val setX = node(
                BuiltinNodes.STRUCT_SET,
                literals = mapOf(BuiltinNodes.STRUCT_OF to "Coordinate", BuiltinNodes.STRUCT_FIELD to "x", "x" to 7),
            )
            val setY = node(
                BuiltinNodes.STRUCT_SET,
                literals = mapOf(BuiltinNodes.STRUCT_OF to "Coordinate", BuiltinNodes.STRUCT_FIELD to "y", "y" to 8),
            )
            val ret = node(BuiltinNodes.RETURN)
            link(start, "Exec", ret, "Exec")
            link(make, "Value", setX, "Value")
            link(setX, "Result", setY, "Value")
            link(setY, "Result", ret, "Value")
        }
        val v = run(g).single() as StructValue
        assertEquals(7, v.get("x"))
        assertEquals(8, v.get("y"))
    }

    /** A record kept in a VARIABLE, read, changed and written back — the mutation an author actually wants. */
    @Test
    fun `a record in a variable is updated by reading, setting and storing`() {
        val g = graph {
            type("Coordinate", "x" to PinType.INT, "y" to PinType.INT)
            variable("home", TypeRef.named("Coordinate"))

            val start = node(BuiltinNodes.ENTRY)
            val get = node(BuiltinNodes.VAR_GET, variable = "home")
            val set = node(
                BuiltinNodes.STRUCT_SET,
                literals = mapOf(BuiltinNodes.STRUCT_OF to "Coordinate", BuiltinNodes.STRUCT_FIELD to "x", "x" to 3200),
            )
            val store = node(BuiltinNodes.VAR_SET, variable = "home")
            val readBack = node(BuiltinNodes.VAR_GET, variable = "home")
            val field = node(
                BuiltinNodes.STRUCT_GET,
                literals = mapOf(BuiltinNodes.STRUCT_OF to "Coordinate", BuiltinNodes.STRUCT_FIELD to "x"),
            )
            val ret = node(BuiltinNodes.RETURN)
            link(start, "Exec", store, "Exec")
            link(store, "Exec", ret, "Exec")
            link(get, "Value", set, "Value")
            link(set, "Result", store, "Value")
            link(readBack, "Value", field, "Value")
            link(field, "x", ret, "Value")
        }
        // The variable started as a zero-valued record, so the read before the first Make is not a null.
        assertEquals(listOf(3200), run(g))
    }

    @Test
    fun `naming a field the record does not have is refused`() {
        val g = graph {
            type("Coordinate", "x" to PinType.INT)
            val start = node(BuiltinNodes.ENTRY)
            val make = node(BuiltinNodes.STRUCT_MAKE, literals = mapOf(BuiltinNodes.STRUCT_OF to "Coordinate"))
            val get = node(
                BuiltinNodes.STRUCT_GET,
                literals = mapOf(BuiltinNodes.STRUCT_OF to "Coordinate", BuiltinNodes.STRUCT_FIELD to "z"),
            )
            val ret = node(BuiltinNodes.RETURN)
            link(start, "Exec", ret, "Exec")
            link(make, "Value", get, "Value")
        }
        assertTrue(issues(g).any { it.severity == Severity.ERROR && "no field 'z'" in it.message })
    }

    /** The field pin decides the value pin's type, so wiring it would never be read. */
    @Test
    fun `wiring the field pin is refused`() {
        val g = graph {
            type("Coordinate", "x" to PinType.INT)
            val start = node(BuiltinNodes.ENTRY)
            val text = node(BuiltinNodes.LITERAL_STRING, literals = mapOf("Value" to "x"))
            val get = node(
                BuiltinNodes.STRUCT_GET,
                literals = mapOf(BuiltinNodes.STRUCT_OF to "Coordinate", BuiltinNodes.STRUCT_FIELD to "x"),
            )
            val ret = node(BuiltinNodes.RETURN)
            link(start, "Exec", ret, "Exec")
            link(text, "Value", get, BuiltinNodes.STRUCT_FIELD)
        }
        assertTrue(issues(g).any { it.severity == Severity.ERROR && "decides this node's pins" in it.message })
    }

    /** Set Field's value pin is named for the field and carries its type. */
    @Test
    fun `set field takes its value pin from the chosen field`() {
        val g = graph {
            type("Coordinate", "x" to PinType.INT, "name" to PinType.STRING)
            node(
                BuiltinNodes.STRUCT_SET,
                literals = mapOf(BuiltinNodes.STRUCT_OF to "Coordinate", BuiltinNodes.STRUCT_FIELD to "name"),
            )
        }
        val d = resolveNode(g.nodes[0], catalog[BuiltinNodes.STRUCT_SET]!!, g::function, { g.types })
        assertEquals(listOf("Of", "Value", "Field", "name"), d.inputs.map { it.name })
        assertEquals(TypeRef(PinType.STRING), d.input("name")?.type)
        assertEquals(listOf("Result"), d.outputs.map { it.name })
        assertEquals(TypeRef.named("Coordinate"), d.output("Result")?.type)
    }

    // ---- shape ------------------------------------------------------------------------------------

    /** A Make node's pins ARE the declaration — the same trick a Call node plays with a signature. */
    @Test
    fun `make and break take their pins from the declaration`() {
        val g = graph {
            type("Coordinate", "x" to PinType.INT, "y" to PinType.INT)
            node(BuiltinNodes.STRUCT_MAKE, literals = mapOf(BuiltinNodes.STRUCT_OF to "Coordinate"))
            node(BuiltinNodes.STRUCT_SPLIT, literals = mapOf(BuiltinNodes.STRUCT_OF to "Coordinate"))
        }
        val make = resolveNode(g.nodes[0], catalog[BuiltinNodes.STRUCT_MAKE]!!, g::function, { g.types })
        assertEquals(listOf("Of", "x", "y"), make.inputs.map { it.name })
        assertEquals(listOf("Value"), make.outputs.map { it.name })
        assertEquals(TypeRef.named("Coordinate"), make.output("Value")?.type)

        val take = resolveNode(g.nodes[1], catalog[BuiltinNodes.STRUCT_SPLIT]!!, g::function, { g.types })
        assertEquals(listOf("Of", "Value"), take.inputs.map { it.name })
        assertEquals(listOf("x", "y"), take.outputs.map { it.name })
        assertEquals(TypeRef.named("Coordinate"), take.input("Value")?.type)
    }

    /** Naming nothing yet still draws — an unfinished node is something you can look at. */
    @Test
    fun `a node naming no type keeps its naming pin`() {
        val g = graph {
            type("Coordinate", "x" to PinType.INT)
            node(BuiltinNodes.STRUCT_MAKE)
        }
        val d = resolveNode(g.nodes[0], catalog[BuiltinNodes.STRUCT_MAKE]!!, g::function, { g.types })
        assertEquals(listOf("Of"), d.inputs.map { it.name })
        assertEquals(listOf("Coordinate"), d.input("Of")?.options, "the naming pin offers what the graph declares")
    }

    // ---- validation -------------------------------------------------------------------------------

    private fun issues(g: dev.ziggle.vscript.model.Graph) = Validator(catalog).validate(g)

    @Test
    fun `a node naming a type the graph does not declare is refused`() {
        val g = graph {
            val start = node(BuiltinNodes.ENTRY)
            val make = node(BuiltinNodes.STRUCT_MAKE, literals = mapOf(BuiltinNodes.STRUCT_OF to "Sandwich"))
            val ret = node(BuiltinNodes.RETURN)
            link(start, "Exec", ret, "Exec")
            link(make, "Value", ret, "Value")
        }
        assertTrue(issues(g).any { it.severity == Severity.ERROR && "Sandwich" in it.message })
    }

    /**
     * A variable typed as something nobody declared is an ERROR, not a wildcard.
     *
     * Reading a type through the enum used to default anything unrecognised to `WILDCARD`, which connects
     * to anything — so the graph kept compiling and quietly stopped meaning what it said, and there was
     * nothing left to report it with.
     */
    @Test
    fun `a variable typed as an undeclared type is reported`() {
        val g = graph {
            variable("home", TypeRef.named("Coordinate"))
            node(BuiltinNodes.ENTRY)
        }
        assertTrue(issues(g).any { it.severity == Severity.ERROR && "Coordinate" in it.message })
    }

    @Test
    fun `a record containing itself is refused`() {
        val g = graph {
            type("Loop", "next" to "Loop")
            node(BuiltinNodes.ENTRY)
        }
        assertTrue(issues(g).any { it.severity == Severity.ERROR && "contains itself" in it.message })
    }

    /** Round the ring, not just directly — the check has to walk. */
    @Test
    fun `a ring of records is refused too`() {
        val g = graph {
            type("A", "b" to "B")
            type("B", "a" to "A")
            node(BuiltinNodes.ENTRY)
        }
        assertTrue(issues(g).any { it.severity == Severity.ERROR && "contains itself" in it.message })
    }

    @Test
    fun `two fields with one name are refused`() {
        val g = graph {
            type("Coordinate", "x" to PinType.INT, "x" to PinType.INT)
            node(BuiltinNodes.ENTRY)
        }
        assertTrue(issues(g).any { it.severity == Severity.ERROR && "two fields" in it.message })
    }

    /** The naming pin decides the node's shape, so a wire into it would never be read. */
    @Test
    fun `wiring the naming pin is refused`() {
        val g = graph {
            type("Coordinate", "x" to PinType.INT)
            val start = node(BuiltinNodes.ENTRY)
            val text = node(BuiltinNodes.LITERAL_STRING, literals = mapOf("Value" to "Coordinate"))
            val make = node(BuiltinNodes.STRUCT_MAKE, literals = mapOf(BuiltinNodes.STRUCT_OF to "Coordinate"))
            val ret = node(BuiltinNodes.RETURN)
            link(start, "Exec", ret, "Exec")
            link(text, "Value", make, BuiltinNodes.STRUCT_OF)
            link(make, "Value", ret, "Value")
        }
        assertTrue(issues(g).any { it.severity == Severity.ERROR && "decides this node's pins" in it.message })
    }

    /** A record wires only into a pin of its own type. */
    @Test
    fun `two record types do not cross-connect`() {
        val g = graph {
            type("Coordinate", "x" to PinType.INT)
            type("Anchor", "x" to PinType.INT)
            val start = node(BuiltinNodes.ENTRY)
            val make = node(BuiltinNodes.STRUCT_MAKE, literals = mapOf(BuiltinNodes.STRUCT_OF to "Coordinate"))
            val take = node(BuiltinNodes.STRUCT_SPLIT, literals = mapOf(BuiltinNodes.STRUCT_OF to "Anchor"))
            val ret = node(BuiltinNodes.RETURN)
            link(start, "Exec", ret, "Exec")
            link(make, "Value", take, "Value")
            link(take, "x", ret, "Value")
        }
        assertTrue(issues(g).any { it.severity == Severity.ERROR && "cannot wire" in it.message })
    }

    /** A graph that declares nothing is untouched by any of this. */
    @Test
    fun `a graph with no declared types raises no type issues`() {
        val g = graph {
            val start = node(BuiltinNodes.ENTRY)
            val ret = node(BuiltinNodes.RETURN)
            link(start, "Exec", ret, "Exec")
        }
        assertEquals(emptyList(), issues(g).filter { it.severity == Severity.ERROR })
    }
}
