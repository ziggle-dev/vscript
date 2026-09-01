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
 * A function written where it is used.
 *
 * The machinery under it is the VM's, unchanged: a lambda is an ordinary chunk whose signature is its own
 * parameters followed by whatever it closed over, and the value carrying it is a program index plus copied
 * captures. What the front end owes is deciding the parameter types from the DESTINATION — a lambda writes
 * names and never types — and working out which enclosing locals it reads.
 */
class LambdaTest {

    private val STRING = TypeRef(PinType.STRING)
    private val INT = TypeRef(PinType.INT)

    private val natives = NativeTable(
        listOf(NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList())),
    )

    private fun read(src: String) = TextFrontEnd(natives).read(src)

    private fun run(src: String): List<String> {
        val said = ArrayList<String>()
        val hosts = HostRegistry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        val r = read(src)
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        drive(chunk, hosts)
        return said
    }

    private fun errors(src: String) = read(src).errors.map { it.message }

    /** A body with no `apply` in scope — for the verbs that supply the destination themselves. */
    private fun script(body: String) = """
        graph "probe"

        on start {
        $body
        }
    """.trimIndent()

    /** A lambda needs a destination to say what its parameters are, so every fixture gives it one. */
    private fun withApply(body: String) = """
        graph "probe"

        fn apply(f: fn(INT) -> INT, to: INT) -> INT {
            return f(to)
        }

        on start {
        $body
        }
    """.trimIndent()

    @Test
    fun `a lambda is called through the parameter it was passed as`() {
        assertEquals(listOf("6"), run(withApply("""    log(message: "" + apply(f: { it * 2 }, to: 3))""")))
    }

    @Test
    fun `a lambda may name its own parameter`() {
        assertEquals(listOf("7"), run(withApply("""    log(message: "" + apply(f: { n -> n + 4 }, to: 3))""")))
    }

    /**
     * A closure carries the values it read, not the registers they were in.
     *
     * Copied when the value is built, which is why reassigning the local afterwards cannot change what the
     * closure sees — the property that makes a closure a value rather than a window onto a frame.
     */
    @Test
    fun `a lambda closes over an enclosing local`() {
        assertEquals(
            listOf("13"),
            run(withApply("""    val base = 10${'\n'}    log(message: "" + apply(f: { it + base }, to: 3))""")),
        )
    }

    @Test
    fun `a capture is copied, not referenced`() {
        assertEquals(
            listOf("13"),
            run(
                withApply(
                    """    var base = 10
    val f: fn(INT) -> INT = { it + base }
    base = 999
    log(message: "" + apply(f: f, to: 3))""",
                ),
            ),
        )
    }

    /** A document variable is a global, reachable from any frame — so it is read, never captured. */
    @Test
    fun `a document variable is seen without being captured`() {
        assertEquals(
            listOf("13"),
            run(
                """
                graph "probe"

                var Base: INT = 10

                fn apply(f: fn(INT) -> INT, to: INT) -> INT {
                    return f(to)
                }

                on start {
                    log(message: "" + apply(f: { it + Base }, to: 3))
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a lambda with no destination is refused rather than guessed`() {
        assertTrue(
            errors("""graph "probe"${'\n'}${'\n'}on start { val f = { it * 2 } }""")
                .any { it.contains("nothing here to say") },
        )
    }

    @Test
    fun `a lambda of the wrong arity is refused`() {
        assertTrue(errors(withApply("""    log(message: "" + apply(f: { a, b -> a + b }, to: 3))""")).isNotEmpty())
    }

    @Test
    fun `a lambda giving back the wrong type is refused`() {
        assertTrue(
            errors(withApply("""    log(message: "" + apply(f: { "no" }, to: 3))""")).isNotEmpty(),
        )
    }

    // ---- the three verbs that take a function ---------------------------------------------------------

    @Test
    fun `mapped puts every item through the function`() {
        assertEquals(
            listOf("2, 4, 6"),
            run(script("""    val doubled = mapped(list: [1, 2, 3], using: { it * 2 })
    log(message: "" + doubled[0] + ", " + doubled[1] + ", " + doubled[2])""")),
        )
    }

    /** `mapped` may change the TYPE, which is the whole reason its signature needs two variables. */
    @Test
    fun `mapped may change the element type`() {
        val r = read(script("""    val names = mapped(list: [1, 2], using: { "n" + it })"""))
        assertTrue(r.resolution?.ok == true, "did not resolve: ${r.errors.map { it.message }}")
        val names = r.resolution!!.localOf.values.first { it.name == "names" }
        assertEquals(PinType.STRING, names.type.of?.builtin)
    }

    @Test
    fun `filtered keeps the items the function says yes to`() {
        assertEquals(
            listOf("3, 4"),
            run(script("""    val big = filtered(list: [1, 2, 3, 4], keeping: { it > 2 })
    log(message: "" + big[0] + ", " + big[1])""")),
        )
    }

    /** "First" means the rest are never asked — the loop leaves the moment it matches. */
    @Test
    fun `firstWhere stops at the match`() {
        assertEquals(listOf("3"), run(script("""    log(message: "" + firstWhere(list: [1, 2, 3, 4], matching: { it > 2 }))""")))
    }

    @Test
    fun `firstWhere gives back nothing when none match`() {
        assertEquals(listOf("null"), run(script("""    log(message: "" + firstWhere(list: [1, 2], matching: { it > 5 }))""")))
    }

    /** Its answer is optional, because it may find none — so the caller has to deal with that. */
    @Test
    fun `firstWhere gives back an optional`() {
        val r = read(script("""    val found = firstWhere(list: [1, 2], matching: { it > 1 })"""))
        assertTrue(r.resolution?.ok == true, "did not resolve: ${r.errors.map { it.message }}")
        assertTrue(r.resolution!!.localOf.values.first { it.name == "found" }.type.optional)
    }

    @Test
    fun `a higher-order verb may close over a local`() {
        assertEquals(
            listOf("11, 12"),
            run(script("""    val bump = 10
    val up = mapped(list: [1, 2], using: { it + bump })
    log(message: "" + up[0] + ", " + up[1])""")),
        )
    }

    /** `it` is typed from the destination, so what it can do is checked like anything else. */
    @Test
    fun `the implicit parameter is typed from where the lambda is going`() {
        val r = read(withApply("""    log(message: "" + apply(f: { it * 2 }, to: 3))"""))
        assertTrue(r.resolution?.ok == true, "did not resolve: ${r.errors.map { it.message }}")
        val it = r.resolution!!.lambdaParamsOf.values.first().first()
        assertEquals(INT.name, it.type.name)
        assertEquals("it", it.name)
    }

    // ---- a lambda is a BLOCK ---------------------------------------------------------------------

    /**
     * Statements inside a lambda, with the last expression as the result.
     *
     * It used to be exactly one expression, and the reason was the graph's: a function value is reached
     * from a Function node with no exec chain a statement could sequence on. The text front end compiles
     * a lambda into a chunk with a real body, so that bought nothing — and cost the obvious thing, which
     * is that anything needing to name an intermediate had to be lifted out into a named function.
     */
    @Test
    fun `a lambda body may hold statements, and hands back its last expression`() {
        assertEquals(
            listOf("11"),
            run(withApply("""    log(message: "" + apply(f: { val doubled = it * 2${'\n'}        doubled + 5 }, to: 3))""")),
        )
    }

    @Test
    fun `a name declared in a lambda is visible to the rest of its body`() {
        assertEquals(
            listOf("46"),
            run(withApply(
                """    log(message: "" + apply(f: { val a = it * 2${'\n'}        val b = a * 3${'\n'}        a + b + 6 }, to: 5))""",
            )),
        )
    }

    /** And invisible outside it — the block is a scope, which is the whole claim. */
    @Test
    fun `a name declared in a lambda does not leak out of it`() {
        assertTrue(
            errors(withApply(
                """    log(message: "" + apply(f: { val inside = it${'\n'}        inside }, to: 1))${'\n'}    log(message: "" + inside)""",
            )).any { "inside" in it },
            "a lambda's local escaped into the enclosing scope",
        )
    }

    /** A statement body still closes over what it reads. */
    @Test
    fun `a statement body closes over an enclosing local`() {
        assertEquals(
            listOf("23"),
            run(withApply(
                """    val base = 10${'\n'}    log(message: "" + apply(f: { val doubled = it * 2${'\n'}        doubled + base + 7 }, to: 3))""",
            )),
        )
    }

    /**
     * Statements with no trailing expression: it runs, and hands nothing back.
     *
     * Legitimate where the destination wants no result, and refused where it wants one — the destination
     * decides, exactly as it does for `{ }`.
     */
    @Test
    fun `a body with no trailing expression is refused where a result is wanted`() {
        assertTrue(
            errors(withApply("""    log(message: "" + apply(f: { val n = it${'\n'}        log(message: "" + n) }, to: 1))"""))
                .isNotEmpty(),
            "a lambda that hands nothing back was accepted where an INT was wanted",
        )
    }

    @Test
    fun `the empty body still parses and still means do nothing`() {
        assertEquals(emptyList(), errors(script("""    log(message: "hi")""")))
    }

    // ---- early return ----------------------------------------------------------------------------

    /**
     * `return` inside a lambda returns from the LAMBDA, not from the function around it.
     *
     * The semantics were free — a lambda compiles to a chunk of its own, so `Op.RET` in it can only
     * return from it — and what was missing was checking the value against the right thing. Resolved
     * against "no results", `return n` in a `fn(INT) -> INT` was refused as "this returns nothing", which
     * is the one construct a block body most obviously wants.
     */
    @Test
    fun `a lambda may return early`() {
        assertEquals(
            listOf("1", "20"),
            run(withApply(
                """    log(message: "" + apply(f: { if it < 0 { return 1 }${'\n'}        it * 2 }, to: -5))""" +
                    """${'\n'}    log(message: "" + apply(f: { if it < 0 { return 1 }${'\n'}        it * 2 }, to: 10))""",
            )),
        )
    }

    /**
     * A lambda that hands everything back through `return` and has no trailing expression at all.
     *
     * It was typed as handing back NOTHING — its result came from the trailing expression and there is
     * none — and then refused by the very destination that had named its result type.
     */
    @Test
    fun `a lambda of nothing but returns still has a result type`() {
        assertEquals(
            listOf("1", "0"),
            run(withApply(
                """    log(message: "" + apply(f: { if it < 0 { return 1 }${'\n'}        return 0 }, to: -5))""" +
                    """${'\n'}    log(message: "" + apply(f: { if it < 0 { return 1 }${'\n'}        return 0 }, to: 7))""",
            )),
        )
    }

    /** Returning the wrong type is still refused — the check moved, it did not go away. */
    @Test
    fun `an early return of the wrong type is refused`() {
        assertTrue(
            errors(withApply("""    log(message: "" + apply(f: { return "no" }, to: 1))""")).isNotEmpty(),
            "a lambda returned a STRING where an INT was wanted",
        )
    }

    /** A `return` in a lambda whose destination wants nothing hands nothing back, as it always did. */
    @Test
    fun `a bare return in a lambda is allowed`() {
        assertEquals(
            emptyList(),
            errors(
                """
                graph "probe"

                fn each(f: fn(INT), over: LIST<INT>) {
                    for n in over {
                        f(n)
                    }
                }

                on start {
                    each(f: { if it < 0 { return }
                        log(message: "" + it) }, over: [1])
                }
                """.trimIndent(),
            ),
        )
    }
}
