package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * An enum reached THROUGH a type resolves like a record does.
 *
 * `OwnerIdentityTest` pinned this for records: a type carries the module that declared it, so a field read
 * three documents out finds the declaration by owner rather than by whether the reader happened to import
 * it. Enums got the same `owner` stamp and not the same LOOKUP, so half the type system had the fix.
 *
 * The shape that exposed it, found by auditing the scripts rather than by a test: document 3 imports only
 * document 2, which exports a record holding both a generic record and an enum from document 1.
 * `h.boxed.value` resolved; `h.row.kind.label` reported `Kind has no fields` about an enum declared
 * perfectly well one hop further out.
 *
 * It stayed hidden because a document that COMPARES against a member has to name the enum, and naming it
 * requires the import — so the only way to meet it is to read a column off a value that merely travelled.
 */
class EnumOwnerIdentityTest {

    private val base = """
        graph "base"
        export enum Kind(label: String, weight: Int) {
            Minigame("minigame", 3),
            Errand("errand", 7),
        }
        export type Box<T> { value: T }
        export type Row { kind: Kind = Kind.Minigame, n: Int = 0 }
        export { Kind, Box, Row }
    """.trimIndent()

    private val mid = """
        graph "mid"
        import "base"
        export type Holder { row: Row = Row { }, boxed: Box<Int> = Box { value: 0 } }
        export fn make(k: Kind, n: Int) -> Holder = Holder { row: Row { kind: k, n: n }, boxed: Box { value: n } }
        export fn errand() -> Holder = Holder { row: Row { kind: Kind.Errand, n: 9 }, boxed: Box { value: 9 } }
        export { Holder, make, errand }
    """.trimIndent()

    /** Compile [top] against the two library documents, and hand back what it logged. */
    private fun run(top: String): List<String> {
        val said = ArrayList<String>()
        val hosts = BuiltinHosts.registry()
        hosts.register("vscript.log", HostKind.INLINE, arity = 2, results = 0) { a -> said += a[0].toString(); null }
        val read = TextFrontEnd(NodeCatalog().natives(), imports = TextSource.of(mapOf("base" to base, "mid" to mid)))
            .read(top)
        val chunk = read.chunk
            ?: fail("did not compile: " + read.errors.joinToString { "${it.span} ${it.message}" })
        drive(chunk, hosts)
        return said
    }

    /**
     * The same read with NOTHING but the intermediate document imported — the real shape of the bug.
     *
     * The member comes from a function in `mid`, so `top` never names `Kind` at all and the only thing it
     * does with the enum is read a column off a value that travelled to it.
     */
    @Test
    fun `an enum column resolves without importing the declaring document`() {
        val said = run(
            """
            graph "top"
            import "mid"
            fn label(h: Holder) -> String = h.row.kind.label
            fn weight(h: Holder) -> Int = h.row.kind.weight
            fn named(h: Holder) -> String = h.row.kind.name
            on start {
                val h = errand()
                log(message: label(h) + " " + weight(h) + " " + named(h))
            }
            """.trimIndent(),
        )
        assertEquals(listOf("errand 7 Errand"), said)
    }

    /** The record half, which already worked — here so a regression in either is caught by one file. */
    @Test
    fun `a generic record field still resolves without importing the declaring document`() {
        assertEquals(
            listOf("9"),
            run(
                """
                graph "top"
                import "mid"
                fn boxed(h: Holder) -> Int = h.boxed.value
                on start { log(message: "" + boxed(errand())) }
                """.trimIndent(),
            ),
        )
    }

    /** Naming the enum still needs the import — that is ordinary scoping and must not have moved. */
    @Test
    fun `naming a member still requires importing the declaring document`() {
        val read = TextFrontEnd(NodeCatalog().natives(), imports = TextSource.of(mapOf("base" to base, "mid" to mid))).read(
            """
            graph "top"
            import "mid"
            on start { log(message: make(k: Kind.Errand, n: 1).row.kind.label) }
            """.trimIndent()
        )
        assertTrue(
            read.errors.any { it.message.contains("Kind") },
            "expected the unimported NAME to be refused, got: " + read.errors.joinToString { it.message },
        )
    }
}
