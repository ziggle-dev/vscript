package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.model.sameDeclaredType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A type is the DECLARATION it came from, not the name whoever is reading it happens to have for it.
 *
 * Before `TypeRef.owner` a declared type was a bare spelling, resolved against the reader's own scope. That
 * is wrong in both directions at once, and both were reproduced against the running client with
 * `graph check` before any of this was written:
 *
 *  - **it split one type into none.** `geo` declares `Point`; `mid` imports it and exports
 *    `type Leg { from: Point }`; `probe` imports only `Leg`. Reading `l.from.x` reported
 *    *"Point has no fields"* — because `probe` has no spelling for `geo`, and the fields could only be
 *    found by looking the spelling up again. This is the failure the scheduler documents at
 *    `scheduler/goal.vs:23` as the reason it is "strings and numbers, all the way down".
 *  - **it merged two types into one, silently.** Two modules each declaring a different `Point` were
 *    interchangeable, so `fn _listTake(p: Point) = p.x` compiled against a record with no `x`. The corpus
 *    declares `Config` 17 times and `Session` 16 times, so this was a live hazard rather than a thought
 *    experiment.
 *
 * The pure diamond — one declaration reached by two import paths — always worked here, because a
 * `ModuleSet` memoises by ref and hands back one `RecordType`. It is pinned below so that stays true.
 */
class OwnerIdentityTest {

    private val STRING = TypeRef(PinType.STRING)

    private val natives = NativeTable(
        listOf(NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList())),
    )

    private fun read(main: String, others: Map<String, String>) =
        TextFrontEnd(natives, imports = TextSource.of(others)).read(main)

    private fun errors(main: String, others: Map<String, String>) =
        read(main, others).errors.map { it.message }

    // ---- the loud one: a field typed through the declarer's own import -------------------------------

    private val twoHopLibs = mapOf(
        "geo" to """
            graph "geo"

            export type Point { x: Int = 0, y: Int = 0 }
        """.trimIndent(),
        "mid" to """
            graph "mid"

            import { Point } from "geo"

            export type Leg { from: Point = Point { }, to: Point = Point { } }
        """.trimIndent(),
    )

    @Test
    fun `a record field typed through the declarer's own import reads three documents out`() {
        val r = read(
            """
            graph "probe"

            import { Leg } from "mid"

            fn startX(l: Leg) -> Int = l.from.x

            on start {
                log(message: "" + startX(l: Leg { }))
            }
            """.trimIndent(),
            twoHopLibs,
        )
        assertTrue(
            r.ok,
            "probe never imports 'geo' and does not need to — the type says where it came from: " +
                r.errors.joinToString { "${it.span} ${it.message}" },
        )
    }

    // ---- the silent one: two declarations, one name ---------------------------------------------------

    @Test
    fun `two records of one name from two modules are two types`() {
        val errs = errors(
            """
            graph "probe"

            import { Point } from "geo"
            import * as other from "othergeo"

            fn _listTake(p: Point) -> Int = p.x

            on start {
                log(message: "" + _listTake(p: other::Point { name: "no", size: 3 }))
            }
            """.trimIndent(),
            mapOf(
                "geo" to "graph \"geo\"\n\nexport type Point { x: Int = 0, y: Int = 0 }\n",
                "othergeo" to "graph \"othergeo\"\n\nexport type Point { name: String = \"\", size: Int = 0 }\n",
            ),
        )
        assertTrue(
            errs.isNotEmpty(),
            "one Point has no 'x' at all — passing it for the other used to compile, and then read field 0",
        )
    }

    // ---- the regression guard: a diamond is still ONE type --------------------------------------------

    @Test
    fun `one declaration reached through two import paths is one type`() {
        val r = read(
            """
            graph "probe"

            import { Point } from "geo"
            import { Point as P2 } from "barrel"

            fn _listTake(p: Point) -> Int = p.x

            on start {
                log(message: "" + _listTake(p: P2 { x: 1, y: 2 }))
            }
            """.trimIndent(),
            mapOf(
                "geo" to "graph \"geo\"\n\nexport type Point { x: Int = 0, y: Int = 0 }\n",
                "barrel" to "graph \"barrel\"\n\nexport { Point } from \"geo\"\n",
            ),
        )
        assertTrue(r.ok, "a diamond is one declaration: " + r.errors.joinToString { it.message })
    }

    // ---- the two invariants the representation has to keep --------------------------------------------

    /**
     * The reason [sameDeclaredType] exists instead of folding the owner into `TypeRef.equals`.
     *
     * "An unknown owner matches anything" is a useful rule and an intransitive one, and `equals` has to be
     * transitive — `withArgs` compares `args` with `List.equals`, so an intransitive element relation would
     * propagate through every nested type and into any hash container.
     */
    @Test
    fun `equals stays transitive while sameDeclaredType is free not to be`() {
        val bare = TypeRef.named("Point")
        val fromGeo = TypeRef.named("Point").ownedBy("geo")
        val fromBank = TypeRef.named("Point").ownedBy("bank")

        // equals ignores the owner entirely, so all three are one value and hash together.
        assertEquals(bare, fromGeo)
        assertEquals(fromGeo, fromBank)
        assertEquals(bare.hashCode(), fromGeo.hashCode())
        assertEquals(setOf(bare, fromGeo, fromBank).size, 1)

        // sameDeclaredType is the one that decides identity, and it is deliberately intransitive.
        assertTrue(sameDeclaredType(bare, fromGeo))
        assertTrue(sameDeclaredType(bare, fromBank))
        assertFalse(sameDeclaredType(fromGeo, fromBank))
    }

    /**
     * A document may declare `type Item`, and `TypeRef.named` interns that straight onto [TypeRef.named("Item")].
     * It must keep wiring into every ITEM pin in the catalogue once it carries an owner, which is what the
     * built-in arm of [sameDeclaredType] is for. No corpus document names a type after a `PinType` today,
     * so a regression here would otherwise stay invisible until one did.
     */
    @Test
    fun `a document's own type named after a builtin still matches that builtin`() {
        val declaredItem = TypeRef.named("Item").ownedBy("shopping/ids")
        assertTrue(sameDeclaredType(declaredItem, TypeRef.named("Item")))
        assertTrue(sameDeclaredType(TypeRef.named("Item"), declaredItem))
    }

    /** `asVariable` drops the owner: a type variable stands for whatever binds it and declares nothing. */
    @Test
    fun `a type variable carries no owner`() {
        assertEquals(null, TypeRef.named("T").ownedBy("core/list").asVariable().owner)
    }
}
