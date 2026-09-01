package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.HostEnum
import dev.ziggle.vscript.model.HostEnums
import dev.ziggle.vscript.model.HostField
import dev.ziggle.vscript.model.HostRecord
import dev.ziggle.vscript.model.HostRecords
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.nodes.NodeLibrary
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.drive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A type the HOST provides, with readable fields.
 *
 * The thing this replaces is a node with a column of outputs. `scene.entityInfo` hands back seven of them —
 * `Id, Kind, Name, Tile, Distance, Exists, Clickable` — and fifty-eight sites in the corpus take them
 * apart positionally:
 *
 * ```
 * val (oid, okind, oname, owhere) = entityInfo(o)
 * ```
 *
 * That is a record written as an adjacent mapping, and `okind` is a `String` where an enum existed in
 * Kotlin the whole time. With the type carrying its own fields it is `e.name`, `e.tile`, `e.kind`.
 *
 * **A field read is a host CALL**, not a `GETFIELD`: the value in the register is the host's own object,
 * never converted, so there is no copy and no second representation — and a field is free to be a live
 * read rather than a snapshot, which is what `distance` and `clickable` have to be.
 */
class HostRecordTest {

    /** Stands in for `EntityRef` — the point being that the VM never converts it into anything. */
    private class FakeCritter(val id: Int, val name: String, val kind: String, var distanceReads: Int = 0)

    private val INT = TypeRef(PinType.INT)
    private val STRING = TypeRef(PinType.STRING)

    private val entityKind = HostEnum("EntityKind", listOf("Npc", "Object", "GroundItem"))

    private val critter = HostRecord(
        "Critter",
        listOf(
            HostField("id", INT) { (it as FakeCritter).id },
            HostField("name", STRING) { (it as FakeCritter).name },
            HostField("kind", entityKind.type) { (it as FakeCritter).kind },
            // Deliberately NOT stored: proves a field can answer from the world rather than a snapshot.
            HostField("distance", INT) { (it as FakeCritter).let { e -> e.distanceReads++; e.distanceReads * 10 } },
        ),
    )

    private val subject = FakeCritter(4151, "Abyssal demon", "Npc")

    private val natives = NativeTable(
        listOf(
            NativeFn("nearest", results = listOf(NativeParam("Result", critter.type))),
            NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList()),
        ),
    )

    private lateinit var library: NodeLibrary

    @BeforeTest
    fun registerLibrary() {
        HostRecords.reset()
        HostEnums.reset()
        // Through a real NodeLibrary, so the registration and install paths are the ones a client uses.
        library = NodeLibrary(emptyList(), enums = listOf(entityKind), records = listOf(critter))
    }

    @AfterTest
    fun clear() {
        HostRecords.reset()
        HostEnums.reset()
    }

    private fun run(src: String): List<String> {
        val said = ArrayList<String>()
        val hosts = HostRegistry()
        library.install(hosts)
        hosts.register("nearest", HostKind.INLINE, arity = 0, results = 1) { _ -> subject }
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        val r = TextFrontEnd(natives).read(src)
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        drive(chunk, hosts)
        return said
    }

    @Test
    fun `a host record's fields are readable`() {
        assertEquals(
            listOf("Abyssal demon", "4151"),
            run(
                """
                graph "p"

                on start {
                    val e = nearest()
                    log(message: e.name)
                    log(message: "" + e.id)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a host record may be a parameter and a return type`() {
        assertEquals(
            listOf("Abyssal demon"),
            run(
                """
                graph "p"

                fn nameOf(e: Critter) -> String = e.name

                on start {
                    log(message: nameOf(e: nearest()))
                }
                """.trimIndent(),
            ),
        )
    }

    /** The field that made `okind` a String: a closed set is now a closed set. */
    @Test
    fun `a host record field may be a host enum`() {
        assertEquals(
            listOf("true"),
            run(
                """
                graph "p"

                on start {
                    log(message: "" + (nearest().kind == EntityKind.Npc))
                }
                """.trimIndent(),
            ),
        )
    }

    /** A field is a call, so it answers from the world each time rather than from a snapshot. */
    @Test
    fun `a field read is live`() {
        val said = run(
            """
            graph "p"

            on start {
                val e = nearest()
                log(message: "" + e.distance)
                log(message: "" + e.distance)
            }
            """.trimIndent(),
        )
        assertEquals(listOf("10", "20"), said)
        assertTrue(subject.distanceReads == 2, "each read should have reached the host")
    }

    @Test
    fun `a field nobody declared is refused, and the message says what there is`() {
        val r = TextFrontEnd(natives).read(
            """
            graph "p"

            on start { log(message: nearest().nosuch) }
            """.trimIndent(),
        )
        assertTrue(!r.ok)
        val m = r.errors.joinToString { it.message }
        assertTrue("nosuch" in m && "name" in m, m)
    }

    /**
     * A document declaring the name wins, exactly as it does over a host enum and a built-in.
     *
     * The discriminator is the OWNER: a declared type carries the module that declared it and a host type
     * never does. Matching on the name alone compiled the document's own record into a call to the host's
     * accessor — which is the shape `CLAUDE.md` warns about for `type Item` over `TypeRef.named("Item")`, met again
     * one level up.
     *
     * Named `Critter` here rather than `Entity` on purpose: `PinType.ENTITY` still exists, so
     * `TypeRef.named("Entity")` interns onto the built-in before a document can own it. That is exactly
     * what removing the constant fixes, and testing the mechanism through a name with no `PinType` keeps
     * this test about the mechanism.
     */
    @Test
    fun `a document's own type shadows a host record of the same name`() {
        assertEquals(
            listOf("mine"),
            run(
                """
                graph "p"

                type Critter { label: String = "mine" }

                on start {
                    log(message: Critter { }.label)
                }
                """.trimIndent(),
            ),
        )
    }
}
