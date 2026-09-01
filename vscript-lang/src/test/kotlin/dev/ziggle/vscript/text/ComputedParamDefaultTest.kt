package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * A parameter default that is not a literal — a function reference, an empty list, a call.
 *
 * **These compiled clean and arrived as null, for as long as the language has had defaults.** Only a
 * `LiteralExpr` could be folded to a value; everything else folded to `null` while `hasDefault` stayed
 * true, so the emitter wrote `CONST reg, null` for every omitted argument and nothing said a word until
 * the value was USED. The tree run is what finally surfaced it — `gatherUntilGone`'s
 * `reclaim: fn() -> Int = noSpill` reached the bag-full path and faulted with `nothing to call: register
 * 19 holds 'null', not a function`, after dropping the logs, every time, for a day. Four defaults in the
 * real corpus were silently null, `inputs: LIST<Input> = []` among them.
 *
 * A computed default now compiles to a nullary chunk in the document that DECLARED it, and an omitted
 * argument calls it. The placement is the point: `noSpill` has to resolve in the scope that can see it,
 * not in whichever document happened to leave the argument out. And it runs PER CALL, which is the only
 * correct reading of `= []` — one folded list would be one caller's mutation showing up in another's.
 *
 * **Driven, not merely compiled**, because compiling clean is exactly what the bug did.
 */
class ComputedParamDefaultTest {

    private val natives = NativeTable(
        listOf(NativeFn("log", listOf(NativeParam("message", TypeRef(PinType.STRING))), results = emptyList())),
    )

    private fun run(main: String, others: Map<String, String> = emptyMap()): List<String> {
        val said = ArrayList<String>()
        val hosts = BuiltinHosts.registry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        val r = TextFrontEnd(natives, imports = TextSource.of(others)).read(main)
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        drive(chunk, hosts)
        return said
    }

    /** The tree run's exact shape: a fn-typed parameter defaulting to a named function, then CALLED. */
    @Test
    fun `a function-reference default is callable`() {
        assertEquals(
            listOf("8"),
            run(
                """
                fn noSpill() -> INT { return 7 }
                fn room() -> INT { return 1 }
                fn gather(makeRoom: fn() -> INT, reclaim: fn() -> INT = noSpill) -> INT {
                    return makeRoom() + reclaim()
                }
                on start { log(message: "" + gather(makeRoom: room)) }
                """.trimIndent(),
            ),
        )
    }

    /** `inputs: LIST<Input> = []` — the scheduler's, and proof a call is not needed to break it. */
    @Test
    fun `an empty-list default arrives as a list`() {
        assertEquals(
            listOf("0", "3"),
            run(
                """
                fn take(xs: LIST<INT> = []) -> INT { return _listCount(list: xs) }
                on start {
                    log(message: "" + take())
                    log(message: "" + take(xs: [1, 2, 3]))
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * The default is compiled where it was DECLARED, not where the argument was left out.
     *
     * The caller imports `gather` and has never named `noSpill`; compiling the expression in the caller's
     * scope could not resolve it, which is the whole reason this is a chunk in the declaring document.
     */
    @Test
    fun `a default resolves in the declaring document, not the caller`() {
        assertEquals(
            listOf("5"),
            run(
                """
                import { gather } from "lib"
                on start { log(message: "" + gather()) }
                """.trimIndent(),
                mapOf(
                    "lib" to """
                        fn noSpill() -> INT { return 5 }
                        export fn gather(reclaim: fn() -> INT = noSpill) -> INT { return reclaim() }
                    """.trimIndent(),
                ),
            ),
        )
    }

    /** Evaluated PER CALL — a folded constant is computed once, a chunk runs every time. */
    @Test
    fun `a computed default is fresh for every call`() {
        assertEquals(
            listOf("1", "2", "99"),
            run(
                """
                var Seen: INT = 0
                fn stamp() -> INT { Seen = Seen + 1 return Seen }
                fn mark(n: INT = stamp()) -> INT { return n }
                on start {
                    log(message: "" + mark())
                    log(message: "" + mark())
                    log(message: "" + mark(n: 99))
                }
                """.trimIndent(),
            ),
        )
    }
}
