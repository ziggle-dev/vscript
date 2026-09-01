package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * One result of a MULTI-result call, picked by name, inside a lambda.
 *
 * `itemInfo(it).name == "Saltpetre"` inside a `filter` is the shape the corpus wants and it stacks three
 * things that are each fine alone: a call with six results, a member pick that means "the second of
 * them", and a lambda body compiled into a chunk of its own whose frame sits at the top of the
 * comprehension's registers. The pick allocates a window and asks for `index + 1` results; the
 * comprehension allocates its call window LAST because a callee's frame runs upward from it. Both are
 * true, and whether they are true AT THE SAME TIME is what this asks.
 */
class ResultPickInLambdaTest {

    private val STRING = TypeRef(PinType.STRING)
    private val INT = TypeRef(PinType.INT)

    private val natives = NativeTable(
        listOf(
            NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList()),
            NativeFn("things", results = outs(TypeRef.list(INT))),
            // Six results, like `itemInfo` — the pick is the SECOND, so an off-by-one shows up as a
            // number where a name was wanted rather than as a crash.
            NativeFn(
                "info",
                listOf(NativeParam("of", INT)),
                results = listOf(
                    NativeParam("id", INT),
                    NativeParam("name", STRING),
                    NativeParam("quantity", INT),
                    NativeParam("slot", INT),
                    NativeParam("container", STRING),
                    NativeParam("exists", TypeRef(PinType.BOOL)),
                ),
            ),
        ),
    )

    /**
     * `core/list`, as far as this needs it.
     *
     * The corpus does not call the intrinsic: it calls a GENERIC EXTENSION that wraps it, and that is the
     * path worth testing. `LIST<T>.filter` binds `T` from the receiver and passes a lambda straight
     * through to `filtered`, so a result pick inside the lambda is three inferences deep.
     */
    private val list = """
        graph "list"
        export fn LIST<T>.count(self) -> INT = _listCount(self)
        export fn LIST<T>.filter(self, f: fn(T) -> BOOL) -> LIST<T> = filtered(list: self, keeping: f)
        export fn LIST<T>.isEmpty(self) -> BOOL = self.count() == 0
        export fn LIST<T>.isNotEmpty(self) -> BOOL = !self.isEmpty()
    """.trimIndent()

    /** Three "items": 1 and 3 are named "keep", 2 is not. */
    private fun run(src: String): List<String> {
        val said = ArrayList<String>()
        val hosts = HostRegistry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        hosts.register("things", HostKind.INLINE, arity = 0, results = 1) { arrayListOf(1, 2, 3) }
        hosts.register("info", HostKind.INLINE, arity = 1, results = 6) { a ->
            val id = (a[0] as Number).toInt()
            arrayOf<Any?>(id, if (id == 2) "skip" else "keep", id * 10, id, "inventory", true)
        }
        val r = TextFrontEnd(natives, imports = TextSource.of(mapOf("core/list" to list))).read(src)
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        drive(chunk, hosts)
        return said
    }

    @Test
    fun `a result pick inside a filter lambda reads the right result`() {
        assertEquals(
            listOf("2"),
            run(
                """
                graph "probe"
                import "core/list"
                on start {
                    val kept = things().filter{ info(of: it).name == "keep" }
                    log(message: "" + kept.count())
                }
                """.trimIndent(),
            ),
        )
    }

    /** The same thing the corpus actually writes: through a `fn`, as an expression body. */
    @Test
    fun `the same pick through a function with an expression body`() {
        assertEquals(
            listOf("true"),
            run(
                """
                graph "probe"
                import "core/list"
                fn hasKeep() -> BOOL = things().filter{ info(of: it).name == "keep" }.count() > 0
                on start { log(message: "" + hasKeep()) }
                """.trimIndent(),
            ),
        )
    }

    /** A name nothing matches gives an EMPTY list, not a wrong one. */
    @Test
    fun `a filter that matches nothing keeps nothing`() {
        assertEquals(
            listOf("0"),
            run(
                """
                graph "probe"
                import "core/list"
                on start {
                    val kept = things().filter{ info(of: it).name == "nothing is called this" }
                    log(message: "" + kept.count())
                }
                """.trimIndent(),
            ),
        )
    }

    /** And the destructuring spelling, which is what the corpus used before lambdas took statements. */
    @Test
    fun `the destructuring spelling agrees with the pick`() {
        assertEquals(
            listOf("2"),
            run(
                """
                graph "probe"
                import "core/list"
                on start {
                    val kept = things().filter{ val (id, name) = info(of: it)
                        name == "keep" }
                    log(message: "" + kept.count())
                }
                """.trimIndent(),
            ),
        )
    }
}
