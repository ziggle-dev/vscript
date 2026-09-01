package dev.ziggle.vscript.lang

import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.FunctionPin
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphFunction
import dev.ziggle.vscript.model.GraphSource
import dev.ziggle.vscript.model.GraphVariable
import dev.ziggle.vscript.model.Node
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.StructType
import dev.ziggle.vscript.model.TypeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `import {a, b as c} from "x"` — names taken out of a document and used unqualified.
 *
 * **The design in one sentence: the graph never learns that unqualified imports exist.** An import without
 * an alias is given a synthesised one — `@1`, which nobody can type — so a Call node still says
 * `@1::withdraw`, and the compiler, the validator, the import closure and the document format are exactly
 * what they were. `Lower` puts the alias on and `Print` takes it off. That is the whole feature, and it is
 * why the round-trip tests below are most of the file: the printer's recognizer is the only thing standing
 * between this and a file that comes back naming an alias its author never wrote.
 *
 * **Collisions are errors, at the import line, whether or not the name is used.** That is the same bargain
 * the namespace form always made — this language is built so a name cannot quietly mean two things — and
 * the compensation for the strictness is that the fix is on the line being pointed at. Three cases, and
 * what separates them is what the author can SEE:
 *
 * - another import offers it too — an error, neither has a better claim;
 * - a NODE offers it too — an error, because the catalogue is a vocabulary nobody wrote in this file, so a
 *   silent capture there could not be found by reading;
 * - this document declares it — a WARNING, and the local wins, because that is what an innermost scope
 *   means everywhere and it is written right there to be read.
 */
class ImportUnqualifiedTest {

    private val catalog = NodeCatalog()

    private fun lib(
        name: String,
        fns: List<String> = emptyList(),
        vars: List<String> = emptyList(),
        types: List<String> = emptyList(),
        hidden: List<String> = emptyList(),
    ) = Graph(
        id = "id-$name",
        name = name,
        nodes = (fns + hidden).mapIndexed { i, f -> Node(i + 1, BuiltinNodes.FUNCTION, function = f) },
        variables = vars.map { GraphVariable(it, TypeRef(PinType.INT), 0, isExported = true) },
        types = types.map { StructType(it, listOf(FunctionPin("x", PinType.INT)), isExported = true) },
        functions = fns.map {
            GraphFunction(it, params = listOf(FunctionPin("n", PinType.INT)), isExported = true)
        } + hidden.map { GraphFunction(it, params = emptyList(), isExported = false) },
    )

    private val math = lib("math", fns = listOf("half", "twice"), vars = listOf("Seed"))
    private val more = lib("more", fns = listOf("half", "thrice"))
    private val shapes = lib("shapes", types = listOf("Spot"))
    /** `count` is a BUILTIN — the default catalogue has it, where a game node like `walkTo` would not. */
    private val nodey = lib("nodey", fns = listOf("text"))
    private val hidden = lib("hidden", fns = listOf("shown"), hidden = listOf("secret"))

    private val source = GraphSource.of(listOf(math, more, shapes, nodey, hidden))
    private fun vs() = VsText(catalog, source)

    private fun read(src: String) = vs().read(src)

    private fun problems(src: String): List<String> = read(src).errors.map { it.message }

    private fun warnings(src: String): List<String> = read(src).warnings.map { it.message }

    private fun roundTrip(src: String) {
        val r = read(src)
        assertTrue(r.ok, "unexpected: ${r.errors.map { it.toString() }}")
        assertEquals(src, vs().write(r.graph!!))
    }

    // ---- taking names in ------------------------------------------------------------------------------

    @Test
    fun `a named import makes a function callable unqualified`() {
        val r = read(
            """
            graph "probe"

            import { half } from "math"

            on start {
                half(n: 4)
            }
            """.trimIndent() + "\n",
        )
        assertTrue(r.ok, "${r.errors.map { it.toString() }}")
        val call = r.graph!!.nodes.single { it.type == BuiltinNodes.CALL }
        assertEquals("@1::half", call.callee, "the graph stores the qualified name, always")
    }

    @Test
    fun `a named import takes exactly what it names`() {
        assertEquals(emptyList(), problems(
            """
            graph "probe"
            import { half } from "math"
            on start { half(n: 4) }
            """.trimIndent(),
        ))
        // `twice` was not taken, so it is not here.
        assertTrue(problems(
            """
            graph "probe"
            import { half } from "math"
            on start { twice(n: 4) }
            """.trimIndent(),
        ).any { "twice" in it })
    }

    @Test
    fun `a named import may rename what it takes`() {
        val r = read(
            """
            graph "probe"
            import { half as halve } from "math"
            on start { halve(n: 4) }
            """.trimIndent(),
        )
        assertTrue(r.ok, "${r.errors.map { it.toString() }}")
        assertEquals("@1::half", r.graph!!.nodes.single { it.type == BuiltinNodes.CALL }.callee)
    }

    /** Renamed means renamed: the original spelling is no longer one of the names this file has. */
    @Test
    fun `a renamed import does not also arrive under its own name`() {
        assertTrue(problems(
            """
            graph "probe"
            import { half as halve } from "math"
            on start { half(n: 4) }
            """.trimIndent(),
        ).any { "half" in it })
    }

    @Test
    fun `a renamed and a plain name may be taken in one list`() {
        assertEquals(emptyList(), problems(
            """
            graph "probe"
            import { half as halve, twice } from "math"
            on start {
                halve(n: 4)
                twice(n: 2)
            }
            """.trimIndent(),
        ))
    }

    @Test
    fun `a named import brings variables and types too`() {
        assertEquals(emptyList(), problems(
            """
            graph "probe"
            import { Seed } from "math"
            import { Spot } from "shapes"
            export var Out: INT = 0
            on start {
                Out = Seed
                log(message: "" + Spot { x: 1 }.x)
            }
            """.trimIndent(),
        ))
    }

    /** Unexported now, rather than `private` — the polarity turned over, the rule did not. */
    @Test
    fun `names the document does not export are not offered`() {
        assertTrue(problems(
            """
            graph "probe"
            import { secret } from "hidden"
            on start { secret() }
            """.trimIndent(),
        ).any { "secret" in it })
    }

    @Test
    fun `naming something the document does not offer is reported at the import`() {
        assertTrue(problems(
            """
            graph "probe"
            import { nosuch } from "math"
            on start { log(message: "x") }
            """.trimIndent(),
        ).any { "nosuch" in it && "math" in it })
    }

    // ---- collisions -----------------------------------------------------------------------------------

    /** Reported whether or not anything uses the name — see the note on this class. */
    @Test
    fun `two named imports offering one name collide, unused`() {
        val p = problems(
            """
            graph "probe"
            import { half, twice } from "math"
            import { half, thrice } from "more"
            on start { twice(n: 1) }
            """.trimIndent(),
        )
        assertTrue(p.any { "'half'" in it && "already imported" in it }, "got: $p")
        assertTrue(p.any { "as <name>" in it }, "the message should name the cure: $p")
    }

    @Test
    fun `renaming one of them settles it`() {
        assertEquals(emptyList(), problems(
            """
            graph "probe"
            import { half } from "math"
            import { half as otherHalf, thrice } from "more"
            on start {
                half(n: 1)
                otherHalf(n: 2)
                thrice(n: 3)
            }
            """.trimIndent(),
        ))
    }

    /** A node name is a name. Capturing it would change what every existing call means. */
    @Test
    fun `a name the catalogue already has collides`() {
        val p = problems(
            """
            graph "probe"
            import { text } from "nodey"
            on start { log(message: "x") }
            """.trimIndent(),
        )
        // `text` rather than `count`: the container verbs are `_`-prefixed now, so they no longer claim
        // a bare word for an import to collide with — which is the whole point of the prefix. `text` is
        // still the formatter's name, so it still collides, and the RULE is what this pins.
        assertTrue(p.any { "'text'" in it && "node" in it }, "got: $p")
    }

    @Test
    fun `and renaming it makes the import legal again`() {
        assertEquals(emptyList(), problems(
            """
            graph "probe"
            import { text as formatted } from "nodey"
            on start { formatted(n: 1) }
            """.trimIndent(),
        ))
    }

    /** The local wins — but not silently, or the import would look like it did nothing. */
    @Test
    fun `a name this document declares warns, and the local wins`() {
        val src = """
            graph "probe"
            import { half } from "math"
            export fn half(n: INT) -> INT = n
            export var Out: INT = 0
            on start { Out = half(n: 4) }
        """.trimIndent()
        assertEquals(emptyList(), problems(src), "it is a warning, not an error")
        assertTrue(warnings(src).any { "'half'" in it && "wins" in it }, "got: ${warnings(src)}")
        // ...and the call really is the local one: no alias on the callee.
        val call = read(src).graph!!.nodes.single { it.type == BuiltinNodes.CALL }
        assertEquals("half", call.callee)
    }

    // ---- the round trip -------------------------------------------------------------------------------

    @Test
    fun `a named import round-trips`() {
        roundTrip(
            """
            graph "probe"

            import { half, twice } from "math"

            on start {
                half(n: 4)
            }
            """.trimIndent() + "\n",
        )
    }

    @Test
    fun `a renamed import round-trips, and the call keeps the local name`() {
        roundTrip(
            """
            graph "probe"

            import { half as halve } from "math"

            on start {
                halve(n: 4)
            }
            """.trimIndent() + "\n",
        )
    }

    /** The aliased form is untouched by any of this and still prints as itself. */
    @Test
    fun `an aliased import still round-trips`() {
        roundTrip(
            """
            graph "probe"

            import * as m from "math"

            on start {
                m::half(n: 4)
            }
            """.trimIndent() + "\n",
        )
    }

    @Test
    fun `the two forms sit side by side`() {
        roundTrip(
            """
            graph "probe"

            import * as m from "more"
            import { half } from "math"

            on start {
                half(n: 1)
                m::thrice(n: 2)
            }
            """.trimIndent() + "\n",
        )
    }
}
