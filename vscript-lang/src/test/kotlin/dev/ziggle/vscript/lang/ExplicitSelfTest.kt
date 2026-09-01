package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.compile.errors
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `self`, written out — and what its ABSENCE now means.
 *
 * An extension's receiver used to be invisible: `Lower` prepended a `self` parameter and `Print` dropped it
 * again, so `fn List.first()` had a parameter nobody could see. Writing it makes the signature describe the
 * function, and it makes one more thing expressible for free — **a function on the TYPE**, which is simply
 * one that declares no `self`:
 *
 *     fn Vec2.new(x: INT, y: INT) -> Vec2 = Vec2 { x, y }      // Vec2.new(5, 3)
 *     fn Vec2.lengthSq(self) -> INT = self.x * self.x + …      // v.lengthSq()
 *
 * **Derived, not stored**, which is why this is the design that shipped and `impl` blocks are not: an
 * `impl` needed a bit on `GraphFunction` purely so the printer could reproduce a spelling, and the answer
 * here is already in `params`.
 */
class ExplicitSelfTest {

    private val sayNode = hostNode(
        "test.say", "say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val catalog = NodeCatalog(listOf(sayNode))
    private val text = VsText(catalog)

    private fun lower(src: String) = Lower(catalog).lower(Parser(Lexer(src).lex()).parse().program)

    private fun graphOf(src: String): dev.ziggle.vscript.model.Graph {
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse: ${parsed.errors.map { it.message }}")
        val low = Lower(catalog).lower(parsed.program)
        assertTrue(low.ok, "lower: ${low.errors}")
        assertEquals(emptyList(), Validator(catalog).validate(low.graph).errors(), "did not validate")
        return low.graph
    }

    private fun said(src: String): List<Any?> {
        val g = graphOf(src)
        val out = ArrayList<Any?>()
        val hosts = HostRegistry()
        hosts.register("say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        drive(
            GraphCompiler(catalog, debug = false).compile(g, g.entries(catalog).single().id),
            hosts, maxTicks = 800,
        )
        return out.map { if (it is Number && it !is Double) it.toLong() else it }
    }

    private fun printed(src: String): String = text.write(graphOf(src)).trim()

    private val vec2 = """
        type Vec2 { x: INT, y: INT }

        fn Vec2.new(x: INT, y: INT) -> Vec2 = Vec2 { x: x, y: y }

        fn Vec2.lengthSq(self) -> INT = self.x * self.x + self.y * self.y
    """.trimIndent()

    // ---- type-level -----------------------------------------------------------------------------------

    @Test
    fun `a function with no self is called on the type`() {
        assertEquals(listOf(34L), said("$vec2\non start { say(Vec2.new(5, 3).lengthSq()) }"))
    }

    @Test
    fun `and one with self is called on a value`() {
        assertEquals(
            listOf(25L),
            said("$vec2\non start { val v = Vec2 { x: 5, y: 0 } say(v.lengthSq()) }"),
        )
    }

    @Test
    fun `the distinction is derived from the parameter list, not stored`() {
        val g = graphOf(vec2 + "\non start { say(1) }")
        val new = g.functions.single { it.name == "new" }
        val len = g.functions.single { it.name == "lengthSq" }

        assertTrue(new.isExtension, "both extend Vec2")
        assertTrue(len.isExtension)
        assertEquals(null, new.self, "'new' declares no self, so it has no receiver")
        assertEquals("self", len.self?.name)
        assertEquals(true, new.isTypeLevel)
        assertEquals(false, len.isTypeLevel)
        // The receiver pin is a real parameter now, not something prepended out of view.
        assertEquals(listOf("x", "y"), new.params.map { it.name })
        assertEquals(listOf("self"), len.params.map { it.name })
    }

    @Test
    fun `both forms round-trip`() {
        // Canonical form: arguments are labelled and the struct shorthand is what prints, so the fixture
        // is written the way the printer writes rather than the way one might type it.
        val src = "type Vec2 { x: INT, y: INT }\n\n" +
            "fn Vec2.new(x: INT, y: INT) -> Vec2 = Vec2 { x, y }\n\n" +
            "fn Vec2.lengthSq(self) -> INT = self.x * self.x + self.y * self.y\n\n" +
            "on start {\n    say(message: Vec2.new(x: 5, y: 3).lengthSq())\n}"
        assertEquals(src, printed(src))
    }

    @Test
    fun `a value of the same name wins over the type`() {
        // Backward-compatible by construction: a type-level call resolved to nothing before this existed,
        // so there is no meaning to preserve — while the variable reading already worked.
        val src = """
            type Site { radius: INT }

            fn Site.grow(self) -> INT = self.radius + 1

            var Site: Site = Site { radius: 4 }

            on start { say(Site.grow()) }
        """.trimIndent()
        assertEquals(listOf(5L), said(src))
    }

    // ---- the migration guard --------------------------------------------------------------------------

    @Test
    fun `an extension that uses self without declaring it is refused by name`() {
        // The hazard the whole migration turns on: four of the eight extensions in the corpus took no
        // parameters, so under the new rule they would silently become TYPE-level — which compiles and
        // means something else. The detector cannot false-positive: a genuine type-level function has no
        // `self` to mention.
        val low = lower(
            """
            type Vec2 { x: INT, y: INT }
            fn Vec2.lengthSq() -> INT = self.x * self.x
            """.trimIndent(),
        )
        val msg = low.errors.map { it.message }.joinToString("\n")
        assertTrue("'lengthSq' uses 'self' but does not declare it" in msg, "got: $msg")
        assertTrue("fn Vec2.lengthSq(self)" in msg, "the message should show the fix, got: $msg")
    }

    @Test
    fun `self in a plain function says the other thing`() {
        val low = lower("fn area() -> INT = self.x")
        val msg = low.errors.map { it.message }.joinToString("\n")
        assertTrue("extends nothing" in msg, "got: $msg")
    }

    @Test
    fun `self may not be given a type`() {
        // One spelling only. The receiver type is already in the head, where it cannot disagree.
        val r = runCatching { Parser(Lexer("fn Vec2.f(self: Vec2) -> INT = 1").lex()).parse() }
        val msg = r.getOrNull()?.errors?.joinToString { it.message } ?: r.exceptionOrNull()?.message.orEmpty()
        assertTrue("'self' takes no type" in msg, "got: $msg")
    }

    @Test
    fun `self may not come second`() {
        val r = runCatching { Parser(Lexer("fn Vec2.f(a: INT, self) -> INT = 1").lex()).parse() }
        val msg = r.getOrNull()?.errors?.joinToString { it.message } ?: r.exceptionOrNull()?.message.orEmpty()
        assertTrue("has to come first" in msg, "got: $msg")
    }

    // ---- struct-literal field shorthand ---------------------------------------------------------------

    @Test
    fun `a struct literal takes field shorthand`() {
        // `Vec2 { x, y }` is `x: x, y: y` — the mirror of `let { x, y } = v`, which the language already had.
        assertEquals(
            listOf(5L, 3L),
            said(
                """
                type Vec2 { x: INT, y: INT }
                on start {
                    val x = 5
                    val y = 3
                    val v = Vec2 { x, y }
                    say(v.x)
                    say(v.y)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `the shorthand is what prints, as it is on the destructuring side`() {
        // Canonical, with no marker: `bindingText` already answers the mirror question this way, and the
        // reason is the same — the rule is a property of the graph, so the same wires always print alike.
        val src = """
            type Vec2 { x: INT, y: INT }

            on start {
                val x = 5
                val y = 3
                val v = Vec2 { x, y }
                say(message: v.x)
            }
        """.trimIndent()
        assertEquals(src, printed(src))
        // And the long form the author could have typed collapses to it, rather than being two spellings.
        assertEquals(src, printed(src.replace("Vec2 { x, y }", "Vec2 { x: x, y: y }")))
    }

    @Test
    fun `a field whose value is not its own name still spells itself out`() {
        val src = """
            type Vec2 { x: INT, y: INT }

            on start {
                val x = 5
                val v = Vec2 { x, y: 3 }
                say(message: v.y)
            }
        """.trimIndent()
        assertEquals(src, printed(src))
    }

    @Test
    fun `the constructor idiom the whole thing was for`() {
        // `Vec2.new(5, 3)` with `Vec2 { x, y }` inside it — the example that motivated explicit `self`,
        // working end to end.
        assertEquals(
            listOf(5L),
            said(
                """
                type Vec2 { x: INT, y: INT }
                fn Vec2.new(x: INT, y: INT) -> Vec2 = Vec2 { x, y }
                on start { say(Vec2.new(5, 3).x) }
                """.trimIndent(),
            ),
        )
    }
}
