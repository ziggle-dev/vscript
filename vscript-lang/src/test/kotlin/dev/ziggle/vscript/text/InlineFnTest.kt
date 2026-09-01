package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.Disassembler
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `inline fn` — splice the body at the call site instead of emitting a `CALL`.
 *
 * **A declaration the compiler can FAIL on, which is the point.** The two mechanisms that used to make
 * the wrapper idiom affordable — `Inliner` and `AppendPass` — were inferences that declined in silence
 * and ran only on the graph front end, so a script that lost them just got slower with nothing to point
 * at. Saying it outright means an author who writes something unsplicable gets an error naming the
 * reason.
 *
 * The VM is instruction-bound at a 3ms budget per scheduler pass, so a `CALL`/`RET` pair around a
 * one-instruction body is most of that body's cost. That is what this removes, and it is what makes
 * `op fn` affordable later: an operator that is not inlined trades a compiler special case for a call
 * frame on the hottest operation in the language.
 */
class InlineFnTest {

    private val INT = TypeRef(PinType.INT)
    private val STRING = TypeRef(PinType.STRING)

    private val natives = NativeTable(
        listOf(
            NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList()),
            NativeFn("bump", listOf(NativeParam("n", INT)), results = listOf(NativeParam("Result", INT))),
            NativeFn(
                "_listContains",
                listOf(NativeParam("list", TypeRef.list(INT)), NativeParam("value", INT)),
                results = listOf(NativeParam("Result", TypeRef(PinType.BOOL))),
            ),
        ),
    )

    private fun compile(src: String) = TextFrontEnd(natives).read(src)

    private fun asm(src: String): String {
        val r = compile(src)
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        return Disassembler.disassemble(chunk)
    }

    private fun run(src: String): List<String> {
        val said = ArrayList<String>()
        var bumps = 0
        val hosts = BuiltinHosts.registry()
            .register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
            .register("bump", HostKind.INLINE, arity = 1, results = 1) { a ->
                bumps++
                (a[0] as Number).toInt()
            }
            .register("_listContains", HostKind.INLINE, arity = 2, results = 1) { a ->
                (a[0] as List<*>).contains(a[1])
            }
        val r = compile(src)
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        drive(chunk, hosts)
        said += "bumps=$bumps"
        return said
    }

    // ---- it actually splices ------------------------------------------------------------------------

    @Test
    fun `an inline expression body emits no call`() {
        val src = """
            inline fn double(n: Int) -> Int = n + n

            on start {
                log(message: "" + double(n: 21))
            }
        """.trimIndent()
        assertEquals(listOf("42", "bumps=0"), run(src))
        assertTrue("CALLG" !in asm(src), "a CALLG survived — the body was not spliced:\n${asm(src)}")
    }

    /** The same function WITHOUT the keyword still gets a frame — the pair is what makes the test mean something. */
    @Test
    fun `the same function without inline still emits a call`() {
        val src = """
            fn double(n: Int) -> Int = n + n

            on start {
                log(message: "" + double(n: 21))
            }
        """.trimIndent()
        assertEquals(listOf("42", "bumps=0"), run(src))
        assertTrue("CALLG" in asm(src), "expected an ordinary call:\n${asm(src)}")
    }

    @Test
    fun `an inline statement body splices too`() {
        val src = """
            inline fn say(what: String) { log(message: what) }

            on start {
                say(what: "hello")
            }
        """.trimIndent()
        assertEquals(listOf("hello", "bumps=0"), run(src))
        assertTrue("CALLG" !in asm(src), "a CALLG survived:\n${asm(src)}")
    }

    /** An extension is an ordinary function whose first parameter was written on the left of a dot. */
    @Test
    fun `an inline extension splices`() {
        val src = """
            inline fn Int.twice(self) -> Int = self * 2

            on start {
                log(message: "" + 21.twice())
            }
        """.trimIndent()
        assertEquals(listOf("42", "bumps=0"), run(src))
        assertTrue("CALLG" !in asm(src), "a CALLG survived:\n${asm(src)}")
    }

    // ---- the rule that makes splicing safe ----------------------------------------------------------

    /**
     * **An argument used twice in the body is still evaluated once.**
     *
     * This is the trap a naive body-substitution falls into, and the reason the call site fills a register
     * window before the splice. `bump` counts its own calls, so a body naming `n` twice would report two.
     */
    @Test
    fun `an argument mentioned twice is evaluated once`() {
        val src = """
            inline fn double(n: Int) -> Int = n + n

            on start {
                log(message: "" + double(n: bump(n: 5)))
            }
        """.trimIndent()
        assertEquals(listOf("10", "bumps=1"), run(src))
    }

    // ---- and the failures, which are loud -----------------------------------------------------------

    // ---- `return` works, because an author should not get a different language ---------------------

    /**
     * **A `return` in an `inline fn`'s own body ends THAT function, exactly as it would without the
     * keyword.** It cannot stay an `Op.RET` — that would return from the caller, a different function
     * with a different arity — so it becomes what it means: put the result where the call site wants it,
     * then jump past the rest of the splice.
     *
     * This is a LOCAL return. Kotlin's non-local return is a different feature, about a lambda PASSED to
     * an inline function, and needs those lambdas inlined too; ours are closures called through `CALLV`,
     * so nothing here can reach past the splice.
     */
    @Test
    fun `an inline fn may use return`() {
        val src = """
            inline fn pick(n: Int) -> Int {
                return n + 1
            }

            on start {
                log(message: "" + pick(n: 41))
            }
        """.trimIndent()
        assertEquals(listOf("42", "bumps=0"), run(src))
        assertTrue("CALLG" !in asm(src), "a CALLG survived — the body was not spliced:\n${asm(src)}")
    }

    /** An EARLY return has to skip the rest of the spliced body, not fall through it. */
    @Test
    fun `an early return skips the rest of the spliced body`() {
        val src = """
            inline fn clamp(n: Int) -> Int {
                if n > 10 {
                    return 10
                }
                return n
            }

            on start {
                log(message: "" + clamp(n: 99))
                log(message: "" + clamp(n: 3))
            }
        """.trimIndent()
        assertEquals(listOf("10", "3", "bumps=0"), run(src))
        assertTrue("CALLG" !in asm(src), "a CALLG survived:\n${asm(src)}")
    }

    /**
     * Two calls in one caller each need their own end-of-splice target.
     *
     * The jumps are collected per splice, so a second call patching the first call's jumps would send an
     * early return into the middle of unrelated code — a fault a long way from its cause.
     */
    @Test
    fun `two calls to the same inline fn each get their own exit`() {
        val src = """
            inline fn firstNonZero(a: Int, b: Int) -> Int {
                if a != 0 {
                    return a
                }
                return b
            }

            on start {
                log(message: "" + firstNonZero(a: 0, b: 7))
                log(message: "" + firstNonZero(a: 5, b: 9))
            }
        """.trimIndent()
        assertEquals(listOf("7", "5", "bumps=0"), run(src))
    }

    /** A body with `return` and no value — nothing to land, just the jump out. */
    @Test
    fun `a bare return in a spliced statement body ends the splice`() {
        val src = """
            inline fn maybeSay(n: Int) {
                if n == 0 {
                    return
                }
                log(message: "n=" + n)
            }

            on start {
                maybeSay(n: 0)
                maybeSay(n: 4)
            }
        """.trimIndent()
        assertEquals(listOf("n=4", "bumps=0"), run(src))
    }

    // ---- and the failures, which are loud -----------------------------------------------------------

    @Test
    fun `a recursive inline fn is refused rather than expanded for ever`() {
        val errors = compile(
            """
            inline fn loop(n: Int) -> Int = loop(n: n)

            on start {
                log(message: "" + loop(n: 1))
            }
            """.trimIndent(),
        ).errors.map { it.message }
        assertTrue(
            errors.any { "recursive" in it || "inlined into itself" in it },
            "expected a refusal naming the recursion, got $errors",
        )
    }

    /**
     * **Across an import, which is the case the whole feature is for.**
     *
     * Nothing inlines a helper it declared beside itself; the point is `core/list`'s wrappers being free
     * in the ninety documents that import them. The splice runs on the compiler that OWNS the
     * declaration — its parameters and locals resolve against its own resolution — while emitting into
     * the caller's builder, so this is the case most likely to be wired wrong and least likely to be
     * noticed: an import that quietly fell back to a `CALL` would still be correct, just slow.
     */
    @Test
    fun `an inline fn splices through an import`() {
        val lib = """
            export inline fn Int.twice(self) -> Int = self * 2
        """.trimIndent()
        val main = """
            import "lib"

            on start {
                log(message: "" + 21.twice())
            }
        """.trimIndent()
        val front = TextFrontEnd(natives, imports = TextSource.of(mapOf("lib" to lib)))
        val read = front.read(main)
        val chunk = read.chunk ?: fail("did not compile: " + front.describe(read))
        val text = Disassembler.disassemble(chunk)
        assertTrue("CALLG" !in text, "an imported inline fn fell back to a call:\n$text")

        val said = ArrayList<String>()
        val hosts = BuiltinHosts.registry()
            .register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        drive(chunk, hosts)
        assertEquals(listOf("42"), said)
    }

    /**
     * The exact shape `core/list` uses: exported, inline, generic in its receiver.
     *
     * Pinned separately because it is the combination that ships. `export` has to see past the modifier
     * to know a declaration follows, and the receiver's type variable has to survive the splice — a
     * generic wrapper that quietly stopped binding `T` would still compile and would type the result as
     * a wildcard, which is the kind of thing nothing notices until much later.
     */
    @Test
    fun `an exported generic inline extension splices, as core-list writes them`() {
        val lib = """
            export inline fn List<T>.countOf(self) -> Int = _listCount(list: self)
            export inline fn List<T>.has(self, any: T) -> Bool = _listContains(list: self, value: any)
        """.trimIndent()
        val main = """
            import "lib"

            on start {
                val xs = [1, 2, 3]
                log(message: "" + xs.countOf() + " " + xs.has(any: 2))
            }
        """.trimIndent()
        val front = TextFrontEnd(natives, imports = TextSource.of(mapOf("lib" to lib)))
        val read = front.read(main)
        val chunk = read.chunk ?: fail("did not compile: " + front.describe(read))
        assertTrue(
            "CALLG" !in Disassembler.disassemble(chunk),
            "a generic exported inline extension fell back to a call",
        )

        val said = ArrayList<String>()
        val hosts = BuiltinHosts.registry()
            .register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
            .register("_listContains", HostKind.INLINE, arity = 2, results = 1) { a ->
                (a[0] as List<*>).contains(a[1])
            }
        drive(chunk, hosts)
        assertEquals(listOf("3 true"), said)
    }

    /**
     * What the keyword actually buys, in instructions retired rather than wall clock.
     *
     * A count is deterministic where a clock is a coin flip, and it measures the thing `inline` changes:
     * the `CALL`/`RET` pair and the argument window around a body, per call. The VM is instruction-bound
     * at a 3ms budget per scheduler pass, so instructions ARE the currency.
     */
    @Test
    fun `inlining a wrapper removes the per-call frame`() {
        fun retired(modifier: String): Long {
            val src = """
                $modifier fn Int.twice(self) -> Int = self * 2

                on start {
                    var total = 0
                    for n in [1, 2, 3, 4, 5, 6, 7, 8, 9, 10] {
                        total = total + n.twice()
                    }
                    log(message: "" + total)
                }
            """.trimIndent()
            val said = ArrayList<String>()
            val hosts = BuiltinHosts.registry()
                .register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
            val r = compile(src)
            val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { it.message })
            val run = drive(chunk, hosts)
            assertEquals(listOf("110"), said, "the loop did not compute the same answer")
            return run.fiber.instructionsRetired
        }

        val plain = retired("")
        val spliced = retired("inline")
        println("\n  ten calls to a one-instruction wrapper: plain=$plain  inline=$spliced\n")
        assertTrue(
            spliced < plain,
            "inlining retired $spliced instructions against $plain for the plain call — no saving",
        )
    }

    @Test
    fun `inline must come before fn`() {
        val errors = compile(
            """
            inline var x: Int = 1

            on start {
                log(message: "" + x)
            }
            """.trimIndent(),
        ).errors.map { it.message }
        assertTrue(errors.any { "'inline' goes before 'fn'" in it }, "got $errors")
    }
}
