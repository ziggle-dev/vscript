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
 * Lists, and the type variables that make them ordinary.
 *
 * `Generics.kt` binds ONE variable, from ONE site — an extension's receiver — because that is all a wire
 * needs: the canvas knows what is plugged into `self` before it needs to know anything else. A call site
 * has no privileged argument, so the rule generalises to "every parameter is a binding site", and a `LIST`
 * stops being a special case in the type rules and becomes an instance of one.
 */
class GenericsTest {

    private val STRING = TypeRef(PinType.STRING)
    private val INT = TypeRef(PinType.INT)
    private val T = TypeRef.named("T").asVariable()

    private val natives = NativeTable(
        listOf(
            NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList()),
            // A GENERIC native: what its element type is depends entirely on what it is handed.
            NativeFn("firstOf", listOf(NativeParam("list", TypeRef.list(T))), results = outs(T.orNull())),
            NativeFn(
                "pick",
                listOf(NativeParam("a", T), NativeParam("b", T)),
                results = outs(T),
            ),
        ),
    )

    private fun read(src: String) = TextFrontEnd(natives).read(src)

    private fun run(src: String): List<String> {
        val said = ArrayList<String>()
        val hosts = HostRegistry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        hosts.register("firstOf", HostKind.INLINE, arity = 1, results = 1) { a ->
            (a[0] as? List<*>)?.firstOrNull()
        }
        hosts.register("pick", HostKind.INLINE, arity = 2, results = 1) { a -> a[0] ?: a[1] }
        val r = read(src)
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        drive(chunk, hosts)
        return said
    }

    private fun errors(src: String) = read(src).errors.map { it.message }

    private fun script(body: String) = """
        graph "probe"

        on start {
        $body
        }
    """.trimIndent()

    // ---- lists ---------------------------------------------------------------------------------------

    @Test
    fun `a list literal is built and indexed`() {
        assertEquals(listOf("20"), run(script("""    val xs = [10, 20, 30]${'\n'}    log(message: "" + xs[1])""")))
    }

    @Test
    fun `a for loop walks a list`() {
        assertEquals(
            listOf("a", "b", "c"),
            run(script("""    for s in ["a", "b", "c"] {${'\n'}        log(message: s)${'\n'}    }""")),
        )
    }

    @Test
    fun `a for loop can carry its index`() {
        assertEquals(
            listOf("0=a", "1=b"),
            run(script("""    for (s, i) in ["a", "b"] {${'\n'}        log(message: "" + i + "=" + s)${'\n'}    }""")),
        )
    }

    @Test
    fun `a list of mixed types is refused`() {
        assertTrue(errors(script("""    val xs = [1, "two"]""")).any { it.contains("common type") })
    }

    @Test
    fun `indexing something that is not a list is refused`() {
        assertTrue(errors(script("""    val n = 3${'\n'}    log(message: "" + n[0])""")).any { it.contains("cannot be indexed") })
    }

    @Test
    fun `a list is indexed by a whole number`() {
        assertTrue(
            errors(script("""    val xs = [1, 2]${'\n'}    log(message: "" + xs["one"])""")).any { it.contains("whole number") },
        )
    }

    // ---- inference -----------------------------------------------------------------------------------

    /** `T` is learned from the argument, so the result is an INT and not a shrug. */
    @Test
    fun `a type variable is bound from the argument`() {
        val r = read(script("""    val n = firstOf(list: [1, 2, 3])"""))
        assertTrue(r.resolution?.ok == true, "did not resolve: ${r.errors.map { it.message }}")
        val n = r.resolution!!.localOf.values.first { it.name == "n" }
        assertEquals(INT.name, n.type.required().name)
        assertTrue(n.type.optional, "firstOf gives back T?, so the local should be optional")
    }

    @Test
    fun `a bound variable is carried through to the result at run time`() {
        assertEquals(listOf("10"), run(script("""    log(message: "" + firstOf(list: [10, 20]))""")))
    }

    /** Two parameters share one variable, and the second has to agree with the first. */
    @Test
    fun `one variable bound twice must agree`() {
        assertTrue(
            errors(script("""    val x = pick(a: 1, b: "two")""")).isNotEmpty(),
            "picking between an INT and a STRING should not typecheck",
        )
    }

    /** Agreeing does not mean identical: an INT and a FLOAT settle on FLOAT, as they do everywhere else. */
    @Test
    fun `an int and a float settle on float`() {
        val r = read(script("""    val x = pick(a: 1, b: 2.5)"""))
        assertTrue(r.resolution?.ok == true, "did not resolve: ${r.errors.map { it.message }}")
        assertEquals(PinType.FLOAT, r.resolution!!.localOf.values.first { it.name == "x" }.type.builtin)
    }

    // ---- a generic function a document declares ------------------------------------------------------

    /**
     * `fn first<T>(…)` — the binding site the language did not have.
     *
     * A receiver could always bind one, because a canvas knows what is plugged into `self`. A plain
     * function had nowhere to introduce a variable at all, so a generic one could not be written.
     */
    @Test
    fun `a declared function may be generic`() {
        assertEquals(
            listOf("10", "a"),
            run(
                """
                graph "probe"

                fn head<T>(xs: LIST<T>) -> T? {
                    return firstOf(list: xs)
                }

                on start {
                    log(message: "" + head(xs: [10, 20]))
                    log(message: "" + head(xs: ["a", "b"]))
                }
                """.trimIndent(),
            ),
        )
    }

    /** The variable is bound per CALL, so two calls with different elements get different results. */
    @Test
    fun `a generic function's result follows its argument`() {
        val r = read(
            """
            graph "probe"

            fn head<T>(xs: LIST<T>) -> T? {
                return firstOf(list: xs)
            }

            on start {
                val n = head(xs: [1, 2])
                val s = head(xs: ["a"])
            }
            """.trimIndent(),
        )
        assertTrue(r.resolution?.ok == true, "did not resolve: ${r.errors.map { it.message }}")
        val locals = r.resolution!!.localOf.values.associateBy { it.name }
        assertEquals(PinType.INT, locals.getValue("n").type.required().builtin)
        assertEquals(PinType.STRING, locals.getValue("s").type.required().builtin)
    }

    @Test
    fun `a type parameter cannot shadow a real type`() {
        val e = errors(
            """
            graph "probe"

            fn odd<INT>(x: INT) -> INT {
                return x
            }

            on start { }
            """.trimIndent(),
        )
        assertTrue(e.any { it.contains("already a type") }, "got: $e")
    }

    @Test
    fun `an empty list takes the type it is handed to`() {
        val r = read(script("""    val xs: LIST<INT> = []"""))
        assertTrue(r.resolution?.ok == true, "did not resolve: ${r.errors.map { it.message }}")
    }
}
