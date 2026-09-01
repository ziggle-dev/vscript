package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * One document reaching another.
 *
 * The three spellings mean different things and the corpus uses all three: an ALIAS (`* as rand`) is
 * reachable only as `rand::name`, so nothing can collide and there is no ordering rule to get wrong; a
 * BARE star makes every exported name visible as itself, which is what lets an extension be used without
 * being asked for; and a NAMED list brings in what it lists.
 *
 * Underneath, an imported call is an ordinary `CALLG` into the table both documents share — which is
 * invariant 1 of `docs/TEXT_FRONTEND.md` doing its everyday job rather than anything new.
 */
class ImportTest {

    private val STRING = TypeRef(PinType.STRING)
    private val INT = TypeRef(PinType.INT)

    private val natives = NativeTable(
        listOf(NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList())),
    )

    private val library = """
        graph "pace"

        export var IdleMs: INT = 700

        export type Beat { ms: INT }

        export fn twice(n: INT) -> INT {
            return n * 2
        }

        fn secret(n: INT) -> INT {
            return n
        }
    """.trimIndent()

    private fun read(main: String, others: Map<String, String> = mapOf("core/pace" to library)) =
        TextFrontEnd(natives, imports = TextSource.of(others)).read(main)

    private fun run(main: String, others: Map<String, String> = mapOf("core/pace" to library)): List<String> {
        val said = ArrayList<String>()
        val hosts = HostRegistry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        val r = read(main, others)
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        drive(chunk, hosts)
        return said
    }

    private fun errors(main: String, others: Map<String, String> = mapOf("core/pace" to library)) =
        read(main, others).errors.map { it.message }

    // ---- the three shapes ----------------------------------------------------------------------------

    @Test
    fun `an aliased import is reached through its alias`() {
        assertEquals(
            listOf("6"),
            run(
                """
                graph "main"

                import * as pace from "core/pace"

                on start {
                    log(message: "" + pace::twice(n: 3))
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a bare star makes exported names visible as themselves`() {
        assertEquals(
            listOf("6"),
            run(
                """
                graph "main"

                import "core/pace"

                on start {
                    log(message: "" + twice(n: 3))
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a named import brings in what it lists`() {
        assertEquals(
            listOf("8"),
            run(
                """
                graph "main"

                import { twice } from "core/pace"

                on start {
                    log(message: "" + twice(n: 4))
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a named import may rename`() {
        assertEquals(
            listOf("8"),
            run(
                """
                graph "main"

                import { twice as double } from "core/pace"

                on start {
                    log(message: "" + double(n: 4))
                }
                """.trimIndent(),
            ),
        )
    }

    // ---- what crosses --------------------------------------------------------------------------------

    @Test
    fun `an exported variable is read through its alias`() {
        assertEquals(
            listOf("700"),
            run(
                """
                graph "main"

                import * as pace from "core/pace"

                on start {
                    log(message: "" + pace::IdleMs)
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * One array of globals across the closure, so writing an imported variable really writes it.
     *
     * This did not parse at all until now: `AssignStmt` carried a name and no module, so a library that
     * exported a `var` for tuning offered no way to tune it. The read side had always worked, which is
     * what made the gap easy to miss.
     */
    @Test
    fun `an imported variable can be written, and is one variable`() {
        assertEquals(
            listOf("700", "50"),
            run(
                """
                graph "main"

                import * as pace from "core/pace"

                on start {
                    log(message: "" + pace::IdleMs)
                    pace::IdleMs = 50
                    log(message: "" + pace::IdleMs)
                }
                """.trimIndent(),
            ),
        )
    }

    /** And the compound form, which desugars to a qualified read and a qualified write. */
    @Test
    fun `an imported variable takes a compound assignment`() {
        assertEquals(
            listOf("710"),
            run(
                """
                graph "main"

                import * as pace from "core/pace"

                on start {
                    pace::IdleMs += 10
                    log(message: "" + pace::IdleMs)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `an exported type can be named and built`() {
        assertEquals(
            listOf("40"),
            run(
                """
                graph "main"

                import * as pace from "core/pace"

                fn describe(b: pace::Beat) -> INT {
                    return b.ms
                }

                on start {
                    log(message: "" + describe(b: pace::Beat { ms: 40 }))
                }
                """.trimIndent(),
            ),
        )
    }

    /** A function called from two documents is ONE chunk in the table they share. */
    @Test
    fun `an imported function is compiled once`() {
        val r = read(
            """
            graph "main"

            import * as pace from "core/pace"

            on start {
                log(message: "" + pace::twice(n: 1))
                log(message: "" + pace::twice(n: 2))
            }
            """.trimIndent(),
        )
        val chunk = r.chunk ?: fail("did not compile: ${r.errors.map { it.message }}")
        // Named by the REFERENCE it was imported through, not by its `graph` line — see
        // `TextCompiler.docRef`. What is under test is unchanged: two call sites, ONE chunk.
        assertEquals(1, chunk.program.count { it.name == "core/pace::twice" })
        assertEquals(0, chunk.program.count { it.name == "pace::twice" })
    }

    // ---- what does not -------------------------------------------------------------------------------

    @Test
    fun `a private function is not exported`() {
        assertTrue(
            errors(
                """
                graph "main"

                import * as pace from "core/pace"

                on start { log(message: "" + pace::secret(n: 1)) }
                """.trimIndent(),
            ).any { it.contains("secret") },
        )
    }

    @Test
    fun `an import of nothing says so once`() {
        val e = errors(
            """
            graph "main"

            import * as gone from "core/missing"

            on start { }
            """.trimIndent(),
        )
        assertEquals(1, e.size, "expected one honest line, got: $e")
        assertTrue(e.first().contains("core/missing"))
    }

    @Test
    fun `an unknown alias is named`() {
        assertTrue(
            errors(
                """
                graph "main"

                on start { log(message: "" + nope::twice(n: 1)) }
                """.trimIndent(),
            ).any { it.contains("nope") },
        )
    }

    /** A ring of imports has no innermost, so there is no order that could work. */
    @Test
    fun `an import cycle is refused rather than followed`() {
        val e = errors(
            """
            graph "main"

            import * as a from "a"

            on start { }
            """.trimIndent(),
            others = mapOf(
                "a" to """graph "a"${'\n'}${'\n'}import * as b from "b"""",
                "b" to """graph "b"${'\n'}${'\n'}import * as a from "a"""",
            ),
        )
        assertTrue(e.isNotEmpty(), "a cycle was followed")
    }

    /** A library with its own errors is reported ON THE IMPORT LINE, not as a page of unknown names. */
    @Test
    fun `a broken library is one error, at the import`() {
        val e = errors(
            """
            graph "main"

            import * as bad from "bad"

            on start { }
            """.trimIndent(),
            others = mapOf("bad" to """graph "bad"${'\n'}${'\n'}fn broken( {"""),
        )
        assertEquals(1, e.size, "expected one line, got: $e")
    }
}
