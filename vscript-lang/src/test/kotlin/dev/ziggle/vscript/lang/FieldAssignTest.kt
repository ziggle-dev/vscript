package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Severity
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphSource
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `s.f = v` — a field rebind, and the round trip that makes it safe to add.
 *
 * **Two spellings, one graph.** `course.laps = 1` and `course = course with { laps: 1 }` lower to exactly
 * the same nodes, because the first is desugared into the second. That is the collision VSCRIPT_LANG_PLAN
 * §6.7 gives as the reason `x += 1` was refused: the printer must choose one, and whichever it chooses,
 * the other stops round-tripping.
 *
 * So the answer is the one `let`/`var` already uses — a marker recording which was written. These tests
 * are what makes that claim checkable rather than intended: each spelling must come back as itself, and a
 * graph carrying no marker must come back as `with`, since a hand-wired Set Field was never an assignment.
 */
class FieldAssignTest {

    private val catalog = NodeCatalog()

    private fun graphOf(text: String): Graph {
        val parsed = Parser(Lexer(text).lex()).parse()
        assertTrue(parsed.ok, "parse errors: ${parsed.errors.map { "${it.span} ${it.message}" }}")
        val result = Lower(catalog, source = GraphSource.NONE).lower(parsed.program)
        assertEquals(emptyList(), result.errors.map { it.message }, "lowering errors")
        val issues = Validator(catalog).validate(result.graph).filter { it.severity == Severity.ERROR }
        assertEquals(emptyList(), issues.map { it.message }, "validation errors")
        return result.graph
    }

    private fun roundTrip(text: String) {
        val printed = VsText(catalog).write(graphOf(text))
        assertEquals(text.trim(), printed.trim(), "did not round-trip")
    }

    // ---- both spellings come back as themselves -------------------------------------------------------

    @Test
    fun `a field assignment round-trips as a field assignment`() {
        roundTrip(
            """
            graph "probe"

            export type Course { name: STRING, laps: INT }

            export var Run: Course = Course { name: "Draynor", laps: 0 }

            on start {
                Run.laps = Run.laps + 1
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `the with form round-trips as the with form`() {
        // The point of the marker. Without it one of these two tests must fail.
        roundTrip(
            """
            graph "probe"

            export type Course { name: STRING, laps: INT }

            export var Run: Course = Course { name: "Draynor", laps: 0 }

            on start {
                Run = Run with { laps: Run.laps + 1 }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a mutable local takes a field assignment too`() {
        roundTrip(
            """
            graph "probe"

            export type Course { name: STRING, laps: INT }

            on start {
                var c = Course { name: "Canifis", laps: 0 }
                c.laps = 4
                log(message: "" + c.laps)
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a nested field assignment round-trips`() {
        roundTrip(
            """
            graph "probe"

            export type Inner { n: INT }
            export type Outer { inner: Inner }

            export var O: Outer = Outer { inner: Inner { n: 0 } }

            on start {
                O.inner.n = 7
            }
            """.trimIndent(),
        )
    }

    // ---- and mean the same thing ----------------------------------------------------------------------

    @Test
    fun `both spellings produce the same graph`() {
        val assigned = graphOf(
            """
            graph "probe"
            export type Course { name: STRING, laps: INT }
            export var Run: Course = Course { name: "Draynor", laps: 0 }
            on start {
                Run.laps = 3
            }
            """.trimIndent(),
        )
        val withForm = graphOf(
            """
            graph "probe"
            export type Course { name: STRING, laps: INT }
            export var Run: Course = Course { name: "Draynor", laps: 0 }
            on start {
                Run = Run with { laps: 3 }
            }
            """.trimIndent(),
        )
        // Same node types in the same order, and the same wiring — the marker is the only difference.
        assertEquals(
            withForm.nodes.map { it.type },
            assigned.nodes.map { it.type },
            "a field assignment should BE a struct.set feeding a set",
        )
        assertEquals(withForm.links.size, assigned.links.size, "same wiring")
    }

    @Test
    fun `the value actually changes`() {
        val chunk = compile(
            """
            graph "probe"

            export type Course { name: STRING, laps: INT }

            export var Run: Course = Course { name: "Draynor", laps: 0 }
            export var Seen: INT = -1

            on start {
                Run.laps = Run.laps + 1
                Run.laps = Run.laps + 1
                Seen = Run.laps
            }
            """.trimIndent(),
        )
        val run = drive(chunk, BuiltinHosts.registry())
        assertTrue(run.fiber.isFinished, "the run did not complete: ${run.fiber.error?.message.orEmpty()}")
        val slot = chunk.slots.variables["Seen"] ?: error("no slot")
        assertEquals(2L, (run.interpreter.globals.getOrNull(slot) as Number).toLong(), "two increments")
    }

    /**
     * Assigning through a parameter is REFUSED, which is better than the alternative.
     *
     * A record is a value, so `c.laps = …` inside `fn bump(c: Course)` could only ever update that call's
     * own copy — the caller would see nothing, and a lap counter written that way would silently never
     * count. It does not compile instead, because a parameter is a `let`: named once. The existing rule
     * for immutable bindings turns out to be exactly the right one here, and the error even names the fix.
     */
    @Test
    fun `assigning through a parameter is refused`() {
        val parsed = Parser(
            Lexer(
                """
                graph "probe"

                export type Course { laps: INT }

                export fn bump(c: Course) {
                    c.laps = c.laps + 1
                }

                on start {
                    bump(c: Course { laps: 0 })
                }
                """.trimIndent(),
            ).lex(),
        ).parse()
        assertTrue(parsed.ok, "it should PARSE — this is a meaning error, not a syntax one")
        val errors = Lower(catalog, source = GraphSource.NONE).lower(parsed.program).errors.map { it.message }
        assertTrue(
            errors.any { it.contains("names a value once") },
            "expected a parameter to be refused as a let, got $errors",
        )
    }

    // ---- a canvas graph is not an assignment ----------------------------------------------------------

    @Test
    fun `an unmarked set prints as with`() {
        // A Set Field wired on the canvas carries no spelling, so it must print as the expression form.
        // Stripping the marker is the closest thing to hand-wiring one that a text test can do.
        val graph = graphOf(
            """
            graph "probe"

            export type Course { name: STRING, laps: INT }

            export var Run: Course = Course { name: "Draynor", laps: 0 }

            on start {
                Run.laps = 5
            }
            """.trimIndent(),
        )
        graph.nodes.forEach { it.literals.remove(BuiltinNodes.WROTE_FIELD) }
        assertTrue(
            VsText(catalog).write(graph).contains("Run = Run with { laps: 5 }"),
            "an unmarked set should print as the with form, got:\n${VsText(catalog).write(graph)}",
        )
    }

    private fun compile(text: String) =
        graphOf(text).let { g ->
            GraphCompiler(catalog, debug = false)
                .compile(g, g.entries(catalog).single { it.type == BuiltinNodes.ENTRY }.id)
        }
}
