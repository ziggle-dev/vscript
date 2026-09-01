package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * A document that does not name itself — which, since the `graph` line became optional, is most of them.
 *
 * The header was mandatory when a text file had to round-trip through a canvas document, because a `Graph`
 * has a name and something had to supply it. Nothing round-trips now: a document is named by where it
 * sits, the same way a class is, and writing the name a second time inside the file only creates the
 * chance for the two to disagree — which is a trap the language reference documents rather than a feature.
 *
 * What that exposed is that the compiler used the `graph` line for something load-bearing and had no
 * fallback worth the name. See [two unnamed documents keep their own functions].
 */
class UnnamedDocumentTest {

    private val STRING = TypeRef(PinType.STRING)

    private val natives = NativeTable(
        listOf(NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList())),
    )

    private fun run(main: String, others: Map<String, String> = emptyMap()): List<String> {
        val said = ArrayList<String>()
        val hosts: HostRegistry = BuiltinHosts.registry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        val r = TextFrontEnd(natives, imports = TextSource.of(others)).read(main)
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        drive(chunk, hosts)
        return said
    }

    /** A file with no header at all is a document, and runs. */
    @Test
    fun `a document needs no graph line`() {
        assertEquals(
            listOf("7"),
            run(
                """
                on start {
                    log(message: "" + 7)
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * **Two unnamed documents are two documents.**
     *
     * A function's chunk is keyed `document to name` in the program the whole run shares, and the document
     * half used to be `document.name ?: "script"` — so every file without a `graph` line WAS the same
     * document, and the second one's `helper` resolved to the first one's chunk. Silently: the call
     * typechecked, ran, and returned the other library's answer.
     *
     * The name is the document's REFERENCE now, which is unique by construction — a module set is keyed by
     * it — so this needs no header to be right and cannot be made wrong by two authors choosing the same
     * `graph` line either.
     *
     * BLOCK bodies on purpose. An expression-bodied function is pure and is re-expanded at each use site,
     * so it never goes near the chunk table and would pass this test while the bug was still there.
     */
    @Test
    fun `two unnamed documents keep their own functions`() {
        assertEquals(
            listOf("1", "2"),
            run(
                """
                import * as a from "lib/a"
                import * as b from "lib/b"

                on start {
                    log(message: "" + a::helper())
                    log(message: "" + b::helper())
                }
                """.trimIndent(),
                mapOf(
                    "lib/a" to """
                        export fn helper() -> INT {
                            return 1
                        }
                    """.trimIndent(),
                    "lib/b" to """
                        export fn helper() -> INT {
                            return 2
                        }
                    """.trimIndent(),
                ),
            ),
        )
    }

    /** The same collision, with `graph` lines that agree — two authors are allowed to pick one word. */
    @Test
    fun `two documents naming themselves the same keep their own functions`() {
        assertEquals(
            listOf("1", "2"),
            run(
                """
                import * as a from "lib/a"
                import * as b from "lib/b"

                on start {
                    log(message: "" + a::helper())
                    log(message: "" + b::helper())
                }
                """.trimIndent(),
                mapOf(
                    "lib/a" to """
                        graph "util"
                        export fn helper() -> INT {
                            return 1
                        }
                    """.trimIndent(),
                    "lib/b" to """
                        graph "util"
                        export fn helper() -> INT {
                            return 2
                        }
                    """.trimIndent(),
                ),
            ),
        )
    }
}
