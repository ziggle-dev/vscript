package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Severity
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.compile.errors
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.vm.FiberState
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `when` — the first arm that matches, in both of Kotlin's forms.
 *
 * With a subject each case is a value to EQUAL; without one each case is a condition. They are one statement
 * because an author writes a subject when every arm asks about the same thing and omits it when they do not,
 * and they are told apart by whether anything feeds the `Subject` pin — never by a flag, which could disagree
 * with the wiring.
 *
 * **Why it is a node and not a lowering.** The condition form compiles to the same Branch chain an
 * `if`/`else if` does. Lowering to one would make the two indistinguishable, so a document would print back
 * as the chain and the `when` would vanish on the first round trip — the recognizer collision
 * VSCRIPT_LANG_PLAN.md §6.7 rejects sugar for, and the reason `break` and `as` are nodes too. Half of these
 * tests exist to hold that line: they assert the SPELLING survives, not merely that the program runs.
 */
class WhenTest {

    private val catalog = NodeCatalog()

    // ---- round trips -----------------------------------------------------------------------------------

    private fun keeps(text: String) {
        val vs = VsText(catalog)
        val read = vs.read(text)
        assertTrue(read.ok, "should compile: ${read.errors.map { "${it.span} ${it.message}" }}")
        val graph = assertNotNull(read.graph)
        assertEquals(text, vs.write(graph, read.comments).trim(), "the spelling changed on the way back out")
    }

    @Test
    fun `the subject form round-trips`() {
        keeps(
            """
            graph "probe"

            export enum Phase { Chop, Bank }

            export var State: Phase = Phase.Chop

            on start {
                when State {
                    Phase.Chop -> {
                        log(message: "chopping")
                    }
                    Phase.Bank -> {
                        log(message: "banking")
                    }
                }
            }
            """.trimIndent(),
        )
    }

    /** The form the original question asked for: arms that are arbitrary expressions. */
    @Test
    fun `the condition form round-trips`() {
        keeps(
            """
            graph "probe"

            export var Count: INT = 0

            on start {
                when {
                    Count > 10 -> {
                        log(message: "many")
                    }
                    Count > 0 -> {
                        log(message: "some")
                    }
                    else -> {
                        log(message: "none")
                    }
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `an else arm round-trips`() {
        keeps(
            """
            graph "probe"

            export enum Phase { Chop, Bank }

            export var State: Phase = Phase.Chop

            on start {
                when State {
                    Phase.Chop -> {
                        log(message: "chopping")
                    }
                    else -> {
                        log(message: "something else")
                    }
                }
            }
            """.trimIndent(),
        )
    }

    /**
     * Statements after the `when` belong to the enclosing block, not to the last arm.
     *
     * The property `rejoinAll` exists for. Every arm reconverges on the same continuation, and printing it
     * inside the `when` — or once per arm — is the classic way a multi-way recognizer goes wrong.
     */
    @Test
    fun `what follows a when is printed once, outside it`() {
        keeps(
            """
            graph "probe"

            export var Count: INT = 0

            on start {
                when {
                    Count > 0 -> {
                        log(message: "some")
                    }
                    else -> {
                        log(message: "none")
                    }
                }
                log(message: "after")
            }
            """.trimIndent(),
        )
    }

    /** A `when` with no `else` still has one continuation, reached by falling through. */
    @Test
    fun `falling through reaches what follows`() {
        keeps(
            """
            graph "probe"

            export var Count: INT = 0

            on start {
                when {
                    Count > 0 -> {
                        log(message: "some")
                    }
                }
                log(message: "after")
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a nested when round-trips`() {
        keeps(
            """
            graph "probe"

            export var Count: INT = 0

            on start {
                when {
                    Count > 0 -> {
                        when {
                            Count > 10 -> {
                                log(message: "many")
                            }
                            else -> {
                                log(message: "few")
                            }
                        }
                    }
                    else -> {
                        log(message: "none")
                    }
                }
            }
            """.trimIndent(),
        )
    }

    /**
     * An `if`/`else if` chain still prints as one.
     *
     * The other half of the collision: adding a `when` recognizer must not make the printer start writing
     * `when` for graphs that were authored as `if`. They lower to different nodes, so this holds by
     * construction — and this test is what says so if anyone ever makes `when` lower to a Branch chain.
     */
    @Test
    fun `if else if is untouched by the when recognizer`() {
        keeps(
            """
            graph "probe"

            export var Count: INT = 0

            on start {
                if Count > 10 {
                    log(message: "many")
                } else if Count > 0 {
                    log(message: "some")
                } else {
                    log(message: "none")
                }
            }
            """.trimIndent(),
        )
    }

    /**
     * A one-statement arm keeps its compact spelling.
     *
     * `Phase.Chop -> chop()` and the braced form lower to the SAME graph, so the printer has to be told which
     * was written — see `BareBodyTest`, which covers the marker across every construct that has a body. This
     * is here so the `when` half cannot regress on its own.
     */
    @Test
    fun `a compact arm keeps its spelling`() {
        val vs = VsText(catalog)
        val text = """
            graph "probe"

            export var Count: INT = 0

            on start {
                when {
                    Count > 0 -> log(message: "some")
                    else -> log(message: "none")
                }
            }
        """.trimIndent()
        val read = vs.read(text)
        assertTrue(read.ok, "should compile: ${read.errors.map { it.message }}")
        assertEquals(text, vs.write(assertNotNull(read.graph), read.comments).trim())
    }

    // ---- what it runs as -------------------------------------------------------------------------------

    private fun run(text: String): List<Any?> {
        val read = VsText(catalog).read(text)
        val graph = assertNotNull(read.graph, "should compile: ${read.errors.map { it.message }}")
        val issues = Validator(catalog).validate(graph)
        assertTrue(issues.errors().isEmpty(), "$issues")
        val chunk = GraphCompiler(catalog).compile(graph, graph.entries(catalog).first().id)
        val result = drive(chunk)
        assertEquals(FiberState.DONE, result.fiber.state, "${result.fiber.error}")
        return result.fiber.result
    }

    private fun subjectArm(state: String) = """
        graph "probe"

        export enum Phase { Chop, Bank, Walk }

        export var State: Phase = Phase.$state

        on start {
            when State {
                Phase.Chop -> {
                    return "chopping"
                }
                Phase.Bank -> {
                    return "banking"
                }
                else -> {
                    return "walking"
                }
            }
        }
    """.trimIndent()

    @Test
    fun `the subject form takes the matching arm`() {
        assertEquals(listOf("chopping"), run(subjectArm("Chop")))
        assertEquals(listOf("banking"), run(subjectArm("Bank")))
        assertEquals(listOf("walking"), run(subjectArm("Walk")), "no case matched, so else")
    }

    private fun conditionArm(count: Int) = """
        graph "probe"

        export var Count: INT = $count

        on start {
            when {
                Count > 10 -> {
                    return "many"
                }
                Count > 0 -> {
                    return "some"
                }
                else -> {
                    return "none"
                }
            }
        }
    """.trimIndent()

    /** Including the ORDER: 20 satisfies both conditions, and the first one wins. */
    @Test
    fun `the condition form takes the first true arm`() {
        assertEquals(listOf("many"), run(conditionArm(20)))
        assertEquals(listOf("some"), run(conditionArm(5)))
        assertEquals(listOf("none"), run(conditionArm(0)))
    }

    /**
     * A matched arm SKIPS the rest, even when a later arm would also have matched.
     *
     * Every other run test here has arms that `return`, so control leaves anyway and the jump past the
     * remaining arms is never emitted — which means none of them could tell "first match wins" from "every
     * matching arm runs". Found by deliberately breaking that jump and watching the suite stay green; this is
     * the test that goes red instead. Both conditions hold at 20, so the answer names which arm ran.
     */
    @Test
    fun `a matched arm skips the arms after it`() {
        assertEquals(
            listOf("first"),
            run(
                """
                graph "probe"

                export var Count: INT = 20

                export var Hit: String = "none"

                on start {
                    when {
                        Count > 0 -> {
                            Hit = "first"
                        }
                        Count > 10 -> {
                            Hit = "second"
                        }
                    }
                    return Hit
                }
                """.trimIndent(),
            ),
        )
    }

    /** The same property for the subject form, where the comparison rather than a condition selects. */
    @Test
    fun `a matched case skips the cases after it`() {
        assertEquals(
            listOf("first"),
            run(
                """
                graph "probe"

                export var Count: INT = 3

                export var Hit: String = "none"

                on start {
                    when Count {
                        3 -> {
                            Hit = "first"
                        }
                        3 -> {
                            Hit = "second"
                        }
                    }
                    return Hit
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * An arm whose chain DEAD-ENDS still skips the arms below it.
     *
     * The one thing the jump past a matched arm actually protects, and it took deliberately breaking that
     * jump to find that nothing covered it. Every text-written `when` wires each arm's end to whatever
     * follows the statement, and the compiler memoises a shared continuation — so the arms leave by that
     * route and the jump is never reached. A CANVAS graph has no such guarantee: an arm can simply stop.
     *
     * Built by hand for exactly that reason. Both cases are true, and arm 1 ends. Correct: arm 1 runs and
     * control leaves the `when`, so the `else` — the only path that returns — never runs. Without the jump,
     * control falls into case 2's test, which also matches, and the graph answers "second".
     */
    @Test
    fun `a dead-ending arm does not fall into the case below it`() {
        val g = dev.ziggle.vscript.compile.graph {
            variable("Hit", dev.ziggle.vscript.model.PinType.STRING, "none")
            val start = node(dev.ziggle.vscript.model.BuiltinNodes.ENTRY)
            val w = node(
                dev.ziggle.vscript.model.BuiltinNodes.WHEN,
                literals = mapOf(
                    dev.ziggle.vscript.model.BuiltinNodes.WHEN_COUNT to 2,
                    dev.ziggle.vscript.model.BuiltinNodes.whenCase(1) to true,
                    dev.ziggle.vscript.model.BuiltinNodes.whenCase(2) to true,
                ),
            )
            val first = node(
                dev.ziggle.vscript.model.BuiltinNodes.VAR_SET,
                literals = mapOf("Value" to "first"), variable = "Hit",
            )
            val second = node(
                dev.ziggle.vscript.model.BuiltinNodes.VAR_SET,
                literals = mapOf("Value" to "second"), variable = "Hit",
            )
            val ret = node(dev.ziggle.vscript.model.BuiltinNodes.RETURN)
            val read = node(dev.ziggle.vscript.model.BuiltinNodes.VAR_GET, variable = "Hit")
            link(start, "Exec", w, "Exec")
            link(w, dev.ziggle.vscript.model.BuiltinNodes.whenThen(1), first, "Exec")
            link(w, dev.ziggle.vscript.model.BuiltinNodes.whenThen(2), second, "Exec")
            link(w, dev.ziggle.vscript.model.BuiltinNodes.WHEN_ELSE, ret, "Exec")
            link(read, "Value", ret, "Value")
        }
        val issues = Validator(catalog).validate(g)
        assertTrue(issues.errors().isEmpty(), "$issues")
        val chunk = GraphCompiler(catalog).compile(g, g.entries(catalog).first().id)
        val result = drive(chunk)
        assertEquals(FiberState.DONE, result.fiber.state, "${result.fiber.error}")
        assertEquals(
            emptyList(), result.fiber.result,
            "arm 1 ended, so nothing should have returned — it fell into the case below it",
        )
    }

    @Test
    fun `nothing matching and no else runs nothing`() {
        assertEquals(
            listOf("after"),
            run(
                """
                graph "probe"

                export var Count: INT = 0

                on start {
                    when {
                        Count > 10 -> {
                            return "many"
                        }
                    }
                    return "after"
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * The subject is evaluated ONCE, however many arms test it.
     *
     * Not a micro-optimisation: an impure subject re-read per case would ask the host a fresh question in
     * every test, so a `when nextThing() { … }` could match none of its own arms. Asserted by counting, since
     * the value alone cannot tell the two apart.
     */
    @Test
    fun `the subject is evaluated once`() {
        val text = """
            graph "probe"

            export var Calls: INT = 0

            export fn bump() -> INT {
                Calls = Calls + 1
                return Calls
            }

            on start {
                when bump() {
                    99 -> {
                        Calls = 99
                    }
                    98 -> {
                        Calls = 98
                    }
                    97 -> {
                        Calls = 97
                    }
                }
                return Calls
            }
        """.trimIndent()
        // No arm matches, so `Calls` is exactly the number of times the subject ran. Host-free on purpose:
        // the VM binds every host a chunk names when it loads, even for arms that never execute.
        assertEquals(listOf(1), run(text), "the subject was re-evaluated per case")
    }

    // ---- diagnostics -----------------------------------------------------------------------------------

    private fun issuesFor(text: String): List<dev.ziggle.vscript.compile.Issue> {
        val read = VsText(catalog).read(text)
        val graph = assertNotNull(read.graph, "should compile: ${read.errors.map { it.message }}")
        return Validator(catalog).validate(graph)
    }

    /** The payoff of enums plus `when`: adding a member tells you which `when`s no longer cover it. */
    @Test
    fun `a missing enum member is reported`() {
        val issues = issuesFor(
            """
            graph "probe"

            export enum Phase { Chop, Bank, Walk }

            export var State: Phase = Phase.Chop

            on start {
                when State {
                    Phase.Chop -> {
                        log(message: "chopping")
                    }
                }
            }
            """.trimIndent(),
        )
        val message = issues.joinToString("; ") { it.message }
        assertTrue("Bank" in message && "Walk" in message, "should name the uncovered members: $message")
        assertTrue(
            issues.none { it.severity == Severity.ERROR },
            "falling through is legal, so this is a warning: $issues",
        )
    }

    @Test
    fun `covering every member reports nothing`() {
        val issues = issuesFor(
            """
            graph "probe"

            export enum Phase { Chop, Bank }

            export var State: Phase = Phase.Chop

            on start {
                when State {
                    Phase.Chop -> {
                        log(message: "chopping")
                    }
                    Phase.Bank -> {
                        log(message: "banking")
                    }
                }
            }
            """.trimIndent(),
        )
        assertTrue(issues.isEmpty(), "an exhaustive when should be silent: $issues")
    }

    @Test
    fun `an else covers the rest, so nothing is reported`() {
        val issues = issuesFor(
            """
            graph "probe"

            export enum Phase { Chop, Bank, Walk }

            export var State: Phase = Phase.Chop

            on start {
                when State {
                    Phase.Chop -> {
                        log(message: "chopping")
                    }
                    else -> {
                        log(message: "the rest")
                    }
                }
            }
            """.trimIndent(),
        )
        assertTrue(issues.isEmpty(), "an else covers what is left: $issues")
    }

    @Test
    fun `a repeated case is reported as unreachable`() {
        val message = issuesFor(
            """
            graph "probe"

            export enum Phase { Chop, Bank }

            export var State: Phase = Phase.Chop

            on start {
                when State {
                    Phase.Chop -> {
                        log(message: "once")
                    }
                    Phase.Chop -> {
                        log(message: "twice")
                    }
                    Phase.Bank -> {
                        log(message: "banking")
                    }
                }
            }
            """.trimIndent(),
        )
            .joinToString("; ") { it.message }
        assertTrue("already handled" in message, "a duplicate case should be reported: $message")
    }

    /** No exhaustiveness question is asked where there is no finite set of values to be exhaustive over. */
    @Test
    fun `an int subject is not asked to be exhaustive`() {
        val issues = issuesFor(
            """
            graph "probe"

            export var Count: INT = 0

            on start {
                when Count {
                    1 -> {
                        log(message: "one")
                    }
                    2 -> {
                        log(message: "two")
                    }
                }
            }
            """.trimIndent(),
        )
        assertTrue(issues.isEmpty(), "there is no set of all Ints to cover: $issues")
    }

    @Test
    fun `a when with no arms is refused`() {
        val read = VsText(catalog).read("""graph "p"${'\n'}on start { when { } }""")
        assertTrue(read.errors.any { "at least one arm" in it.message }, "${read.errors.map { it.message }}")
    }

    @Test
    fun `an else in the middle is refused`() {
        val read = VsText(catalog).read(
            """
            graph "p"

            export var Count: INT = 0

            on start {
                when {
                    else -> { log(message: "a") }
                    Count > 0 -> { log(message: "b") }
                }
            }
            """.trimIndent(),
        )
        assertTrue(
            read.errors.any { "last arm" in it.message },
            "an else before other arms makes them dead: ${read.errors.map { it.message }}",
        )
    }
}
