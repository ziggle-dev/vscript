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
 * Writing a `single`'s field through `self`, from an extension on it.
 *
 * **The question this settles is whether `fn S.bump(self) { self.n = 1 }` reaches the single.** A record is
 * a VALUE and a parameter is a `val`, so assigning through one updates that call's copy — which would make
 * every `self.field = …` in an extension on a single a write that silently goes nowhere. A `single` is not
 * an ordinary record though: it is one global variable, and the whole point of the form is that
 * `S.field = v` works. Which of those two rules wins here is not written down anywhere.
 */
class SingleSelfWriteTest {

    private val natives = NativeTable(
        listOf(NativeFn("log", listOf(NativeParam("message", TypeRef(PinType.STRING))), results = emptyList())),
    )

    private fun run(main: String): List<String> {
        val said = ArrayList<String>()
        val hosts = BuiltinHosts.registry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        val r = TextFrontEnd(natives).read(main)
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        drive(chunk, hosts)
        return said
    }

    @Test
    fun `a single's field written through self is seen by the next reader`() {
        assertEquals(
            listOf("1"),
            run(
                """
                single S { n: INT = 0 }

                fn S.bump(self) {
                    self.n = self.n + 1
                }

                on start {
                    S.bump()
                    log(message: "" + S.n)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a flag cleared through self stays cleared`() {
        // The wintertodt shape exactly: a latch set by one extension and cleared by another.
        assertEquals(
            listOf("true", "false"),
            run(
                """
                single S { restart: BOOL = false }

                fn S.raise(self) {
                    self.restart = true
                }

                fn S.acted(self) {
                    self.restart = false
                }

                on start {
                    S.raise()
                    log(message: "" + S.restart)
                    S.acted()
                    log(message: "" + S.restart)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a write through self survives being made inside a nested call`() {
        assertEquals(
            listOf("7"),
            run(
                """
                single S { n: INT = 0 }

                fn S.inner(self, v: INT) {
                    self.n = v
                }

                fn S.outer(self, v: INT) {
                    self.inner(v: v)
                }

                on start {
                    S.outer(v: 7)
                    log(message: "" + S.n)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a record instance's field written through self is seen by its holder`() {
        // The same question for an ordinary type rather than a single: `p.bump()` has to leave `p` bumped.
        assertEquals(
            listOf("1", "5"),
            run(
                """
                type Point { x: INT = 0, y: INT = 0 }

                fn Point.bump(self) {
                    self.x = self.x + 1
                }

                fn Point.setY(self, v: INT) {
                    self.y = v
                }

                on start {
                    var p = Point {}
                    p.bump()
                    log(message: "" + p.x)
                    p.setY(v: 5)
                    log(message: "" + p.y)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a single's field written through self is seen from another document`() {
        // The write-back has to survive the import boundary too, since that is where the corpus lives.
        assertEquals(
            listOf("3"),
            runWith(
                """
                import "lib"
                on start {
                    S.setN(v: 3)
                    log(message: "" + S.n)
                }
                """.trimIndent(),
                mapOf("lib" to """
                    export single S { n: INT = 0 }
                    export fn S.setN(self, v: INT) {
                        self.n = v
                    }
                """.trimIndent()),
            ),
        )
    }

    @Test
    fun `an extension may write self AND return a value`() {
        // **The shape that corrupted a live run.** `fn Place.anchorHome(self) -> Bool` both moved the
        // anchor and said whether it managed to — and the call site stored the Bool where the single was,
        // so the next field read anywhere in the script failed with `GETFIELD on Bool`.
        assertEquals(
            listOf("true", "5"),
            run(
                """
                single S { n: INT = 0 }

                fn S.setAndSay(self, v: INT) -> Bool {
                    self.n = v
                    return v > 0
                }

                on start {
                    val ok = S.setAndSay(v: 5)
                    log(message: "" + ok)
                    log(message: "" + S.n)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a mutate-and-return extension works on a record instance too`() {
        assertEquals(
            listOf("false", "2"),
            run(
                """
                type Counter { n: INT = 0 }

                fn Counter.tick(self) -> Bool {
                    self.n = self.n + 1
                    return self.n >= 2
                }

                on start {
                    var c = Counter {}
                    log(message: "" + c.tick())
                    c.tick()
                    log(message: "" + c.n)
                }
                """.trimIndent(),
            ),
        )
    }

    private fun runWith(main: String, others: Map<String, String>): List<String> {
        val said = ArrayList<String>()
        val hosts = BuiltinHosts.registry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        val r = TextFrontEnd(natives, imports = TextSource.of(others)).read(main)
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        drive(chunk, hosts)
        return said
    }
}