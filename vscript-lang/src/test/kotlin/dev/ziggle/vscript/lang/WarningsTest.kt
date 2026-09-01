package dev.ziggle.vscript.lang

import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.NodeCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two things the lowering used to do silently.
 *
 * Both are cases where the graph is not what the text says, and neither was an error: the compiler knew
 * exactly what it meant, and only a reader was misled. That is the argument for a warning rather than a
 * refusal — and for warning at all, since the alternative was a file that came back from a round trip
 * shorter than it went in.
 *
 * `Lower` already had a `warnings` channel and `VsText` already surfaced it, so neither of these cost any
 * plumbing.
 */
class WarningsTest {

    private val catalog = NodeCatalog()

    private fun lowered(src: String) = Lower(catalog).lower(Parser(Lexer(src).lex()).parse().program)

    private fun warnings(src: String): List<String> = lowered(src).warnings.map { it.message }

    private fun entry(body: String) = """
        graph "probe"
        $body
    """.trimIndent()

    // ---- unreachable statements — GAPS #3 ------------------------------------------------------------

    /**
     * The repro. `return 1` then `return 2`: the second was dropped from the graph with no word said, so a
     * text → graph → text round trip DELETED a line the author wrote.
     */
    @Test
    fun `a statement after a return is reported`() {
        val w = warnings(
            entry(
                """
                fn f() -> INT {
                    return 1
                    return 2
                }
                on start { log(message: "" + f()) }
                """.trimIndent(),
            ),
        )
        assertTrue(w.any { "nothing can reach this" in it && "return" in it }, "got: $w")
    }

    @Test
    fun `a statement after a break is reported`() {
        val w = warnings(
            entry(
                """
                on start {
                    while true {
                        break
                        log(message: "never")
                    }
                }
                """.trimIndent(),
            ),
        )
        assertTrue(w.any { "nothing can reach this" in it && "break" in it }, "got: $w")
    }

    @Test
    fun `a statement after a continue is reported`() {
        val w = warnings(
            entry(
                """
                on start {
                    for i in range(from: 0, to: 3) {
                        continue
                        log(message: "never")
                    }
                }
                """.trimIndent(),
            ),
        )
        assertTrue(w.any { "nothing can reach this" in it && "continue" in it }, "got: $w")
    }

    /** Every path out of the `if` returns, so what follows genuinely cannot be reached. */
    @Test
    fun `a statement after an if whose every branch returns is reported`() {
        val w = warnings(
            entry(
                """
                fn f(n: INT) -> INT {
                    if n > 0 {
                        return 1
                    } else {
                        return 2
                    }
                    return 3
                }
                on start { log(message: "" + f(n: 1)) }
                """.trimIndent(),
            ),
        )
        assertTrue(w.any { "nothing can reach this" in it }, "got: $w")
    }

    /**
     * **The false positive that would make the whole warning useless.** An early return under an `if` with
     * no `else`, followed by the rest of the body, is one of the most ordinary shapes there is — the False
     * arm is still open, so the tail is reached and nothing should be said.
     */
    @Test
    fun `an early return with no else does not report the rest of the body`() {
        val w = warnings(
            entry(
                """
                fn f(n: INT) -> INT {
                    if n < 0 {
                        return 0
                    }
                    log(message: "carrying on")
                    return n
                }
                on start { log(message: "" + f(n: 1)) }
                """.trimIndent(),
            ),
        )
        assertEquals(emptyList(), w, "an early return must not condemn the rest of the body")
    }

    @Test
    fun `a break inside an if does not condemn the rest of the loop body`() {
        val w = warnings(
            entry(
                """
                on start {
                    while true {
                        if Done { break }
                        log(message: "still going")
                        wait(ms: 100)
                    }
                }
                var Done: BOOL = false
                """.trimIndent(),
            ),
        )
        assertEquals(emptyList(), w)
    }

    /** A `return` as the last statement has nothing after it, so there is nothing to say. */
    @Test
    fun `a return at the end of a body is quiet`() {
        assertEquals(
            emptyList(),
            warnings(
                entry(
                    """
                    fn f() -> INT {
                        log(message: "hi")
                        return 1
                    }
                    on start { log(message: "" + f()) }
                    """.trimIndent(),
                ),
            ),
        )
    }

    /** And the graph really does stop there — the warning is about something already true. */
    @Test
    fun `the unreachable statement is not in the graph`() {
        val g = lowered(
            entry(
                """
                fn f() -> INT {
                    return 1
                    log(message: "gone")
                }
                on start { log(message: "" + f()) }
                """.trimIndent(),
            ),
        ).graph
        assertTrue(
            g.nodes.none { it.type == BuiltinNodes.LOG && it.literals["Message"] == "gone" },
            "the dropped statement should not be in the graph",
        )
    }

    // ---- a local hiding a graph variable — GAPS #13 --------------------------------------------------

    @Test
    fun `a local that hides a graph variable is reported`() {
        val w = warnings(
            entry(
                """
                var N: INT = 0
                on start {
                    val N = 5
                    log(message: "" + N)
                }
                """.trimIndent(),
            ),
        )
        assertTrue(w.any { "'N'" in it && "graph variable" in it }, "got: $w")
    }

    @Test
    fun `a mutable local hides one just as much`() {
        val w = warnings(
            entry(
                """
                var N: INT = 0
                on start {
                    var N = 5
                    N = N + 1
                }
                """.trimIndent(),
            ),
        )
        assertTrue(w.any { "'N'" in it }, "got: $w")
    }

    @Test
    fun `a loop variable that hides one is reported`() {
        val w = warnings(
            entry(
                """
                var I: INT = 0
                on start {
                    for I in range(from: 0, to: 3) {
                        log(message: "" + I)
                    }
                }
                """.trimIndent(),
            ),
        )
        assertTrue(w.any { "'I'" in it }, "got: $w")
    }

    @Test
    fun `an if val that hides one is reported`() {
        val w = warnings(
            entry(
                """
                var Spot: TILE? = null
                fn find() -> TILE? = null
                on start {
                    if val Spot = find() {
                        log(message: "" + Spot.x)
                    }
                }
                """.trimIndent(),
            ),
        )
        assertTrue(w.any { "'Spot'" in it }, "got: $w")
    }

    @Test
    fun `a name that hides nothing is quiet`() {
        assertEquals(
            emptyList(),
            warnings(
                entry(
                    """
                    var Total: INT = 0
                    on start {
                        val n = 5
                        Total = Total + n
                    }
                    """.trimIndent(),
                ),
            ),
        )
    }

    /**
     * **Narrowing must not report itself.** `x != null` rebinds the name to a narrowed pin, and `?.` does
     * the same for its receiver — both go through plain `bind`, deliberately, because they are the
     * language's own machinery rather than something the author declared.
     */
    @Test
    fun `narrowing a local does not report a shadow`() {
        assertEquals(
            emptyList(),
            warnings(
                entry(
                    """
                    fn find() -> TILE? = null
                    on start {
                        val spot = find()
                        if spot != null {
                            log(message: "" + spot.x)
                        }
                    }
                    """.trimIndent(),
                ),
            ),
        )
    }

    /** A parameter is not a declaration the author chose to place beside a variable, so it stays quiet. */
    @Test
    fun `a parameter sharing a variable's name is quiet`() {
        assertEquals(
            emptyList(),
            warnings(
                entry(
                    """
                    var N: INT = 0
                    fn twice(N: INT) -> INT = N * 2
                    on start { log(message: "" + twice(N: 3)) }
                    """.trimIndent(),
                ),
            ),
        )
    }
}
