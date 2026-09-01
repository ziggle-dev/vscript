package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `import { filter } from "core/list"` — asking for ONE extension by name.
 *
 * The one import form that could not bring an extension in. `import "core/list"` worked and
 * `import * as x from "core/list"` worked; the named list resolved, reported nothing, and then the call
 * failed with `LIST<STRING> has no 'filter'` — which reads as a missing verb rather than a missing form.
 *
 * It matters more than the other two because it is what the EDITOR writes: completion offers an extension
 * from a document you have not imported and adds `import { … }` when you accept it, so the fix it applied
 * produced code that did not compile.
 */
class NamedExtensionImportTest {

    private val natives = NativeTable(
        listOf(NativeFn("log", listOf(NativeParam("message", TypeRef(PinType.STRING))), results = emptyList())),
    )

    private val library = mapOf(
        "core/list" to """
            export fn LIST<T>.firstOr(self, fallback: T) -> T {
                for item in self {
                    return item
                }
                return fallback
            }
        """.trimIndent(),
    )

    private fun run(main: String): List<String> {
        val said = ArrayList<String>()
        val hosts = BuiltinHosts.registry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        val r = TextFrontEnd(natives, imports = TextSource.of(library)).read(main)
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        drive(chunk, hosts)
        return said
    }

    @Test
    fun `a named import brings the extension it names`() {
        assertEquals(
            listOf("a"),
            run(
                """
                import { firstOr } from "core/list"

                on start {
                    val xs: LIST<STRING> = ["a", "b"]
                    log(message: xs.firstOr(fallback: "none"))
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * ...and it may be RENAMED on the way in.
     *
     * An extension has nowhere to put an alias at the call site — it is written after a dot — so the
     * import list is the only place a document can rename one.
     */
    @Test
    fun `a named import may alias the extension`() {
        assertEquals(
            listOf("a"),
            run(
                """
                import { firstOr as headOr } from "core/list"

                on start {
                    val xs: LIST<STRING> = ["a", "b"]
                    log(message: xs.headOr(fallback: "none"))
                }
                """.trimIndent(),
            ),
        )
    }

    /** An alias is the name it was given — the original spelling is not also in scope. */
    @Test
    fun `an aliased extension is not reachable by its old name`() {
        val bad = TextFrontEnd(natives, imports = TextSource.of(library)).resolve(
            """
            import { firstOr as headOr } from "core/list"

            on start {
                val xs: LIST<STRING> = ["a", "b"]
                log(message: xs.firstOr(fallback: "none"))
            }
            """.trimIndent(),
        ).errors.map { it.message }
        assertTrue(bad.isNotEmpty(), "a renamed extension should not answer to its old name")
    }

    /** The other two forms keep working — this adds a source, it does not replace one. */
    @Test
    fun `the unqualified and aliased forms still work`() {
        assertEquals(
            listOf("a"),
            run(
                """
                import "core/list"

                on start {
                    val xs: LIST<STRING> = ["a", "b"]
                    log(message: xs.firstOr(fallback: "none"))
                }
                """.trimIndent(),
            ),
        )
    }
}
