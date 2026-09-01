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
 * `xs.first()` — a call written on a value.
 *
 * An extension is an ordinary function whose first parameter was written to the left of a dot, and that is
 * the whole of it once the callee has been found. Finding it is the part that needs the resolver: the
 * receiver's TYPE chooses, which is why an extension has no qualified spelling and why a bare-star import
 * exists at all.
 */
class ExtensionTest {

    private val STRING = TypeRef(PinType.STRING)
    private val INT = TypeRef(PinType.INT)

    private val natives = NativeTable(
        listOf(NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList())),
    )

    private val listLib = """
        graph "list"

        export fn LIST<T>.head(self) -> T? = firstWhere(list: self, matching: { true })

        export fn LIST<T>.count(self) -> INT {
            var n = 0
            for x in self {
                n += 1
            }
            return n
        }
    """.trimIndent()

    private fun read(main: String, others: Map<String, String> = mapOf("core/list" to listLib)) =
        TextFrontEnd(natives, imports = TextSource.of(others)).read(main)

    private fun run(main: String, others: Map<String, String> = mapOf("core/list" to listLib)): List<String> {
        val said = ArrayList<String>()
        val hosts = HostRegistry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        val r = read(main, others)
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        drive(chunk, hosts)
        return said
    }

    private fun errors(main: String, others: Map<String, String> = mapOf("core/list" to listLib)) =
        read(main, others).errors.map { it.message }

    @Test
    fun `an extension on a declared type is called on a value`() {
        assertEquals(
            listOf("4"),
            run(
                """
                graph "main"

                type Trip { laps: INT }

                fn Trip.doubled(self) -> INT {
                    return self.laps * 2
                }

                on start {
                    val t = Trip { laps: 2 }
                    log(message: "" + t.doubled())
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `an extension takes arguments after the receiver`() {
        assertEquals(
            listOf("7"),
            run(
                """
                graph "main"

                type Trip { laps: INT }

                fn Trip.plus(self, n: INT) -> INT {
                    return self.laps + n
                }

                on start {
                    log(message: "" + Trip { laps: 3 }.plus(n: 4))
                }
                """.trimIndent(),
            ),
        )
    }

    /** A bare star is what makes an extension usable — it has no qualified spelling at the call site. */
    @Test
    fun `an imported extension arrives through a bare star`() {
        assertEquals(
            listOf("3"),
            run(
                """
                graph "main"

                import "core/list"

                on start {
                    log(message: "" + [1, 2, 3].count())
                }
                """.trimIndent(),
            ),
        )
    }

    /** The receiver binds the type variable, which is the binding site extensions always had. */
    @Test
    fun `a generic extension takes its type from the receiver`() {
        val r = read(
            """
            graph "main"

            import "core/list"

            on start {
                val n = ["a", "b"].head()
            }
            """.trimIndent(),
        )
        assertTrue(r.resolution?.ok == true, "did not resolve: ${r.errors.map { it.message }}")
        val n = r.resolution!!.localOf.values.first { it.name == "n" }
        assertEquals(PinType.STRING, n.type.required().builtin)
    }

    @Test
    fun `an extension is not callable as a plain name`() {
        assertTrue(
            errors(
                """
                graph "main"

                type Trip { laps: INT }

                fn Trip.doubled(self) -> INT {
                    return self.laps * 2
                }

                on start { log(message: "" + doubled()) }
                """.trimIndent(),
            ).any { it.contains("doubled") },
        )
    }

    @Test
    fun `an unknown extension names the receiver's type`() {
        assertTrue(
            errors(
                """
                graph "main"

                on start { log(message: "" + [1, 2].nope()) }
                """.trimIndent(),
            ).any { it.contains("nope") },
        )
    }
}
