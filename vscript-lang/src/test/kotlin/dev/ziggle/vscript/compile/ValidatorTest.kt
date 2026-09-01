package dev.ziggle.vscript.compile

import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.Node
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Static checks.
 *
 * Every case here is one the compiler would otherwise turn into either a crash or — worse — plausible
 * bytecode that misbehaves at runtime. The assertion each test really makes is that the message names
 * something the user can find on the canvas.
 */
class ValidatorTest {

    private val npcNode = hostNode(
        "test.npc", "npc", NodeKind.PURE,
        outputs = listOf(PinSpec("Npc", TypeRef.named("Npc"))),
    )
    private val itemSink = hostNode(
        "test.itemSink", "itemSink", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Item", TypeRef.named("Item"))),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val catalog = NodeCatalog(listOf(npcNode, itemSink))
    private val validator = Validator(catalog)

    @Test
    fun `a clean graph produces no errors`() {
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val ret = node(BuiltinNodes.RETURN, literals = mapOf("Value" to 1))
            link(entry, "Exec", ret, "Exec")
        }
        assertEquals(emptyList(), validator.validate(g).errors())
    }

    /**
     * A shape pin is read at edit time to decide what the OTHER pins are, so a wire into it is never
     * consulted. Silence here is the failure mode this whole project keeps meeting: the wire draws, the
     * graph compiles, and the node quietly keeps whatever was typed instead.
     */
    @Test
    fun `a pin that decides the node's shape cannot be wired`() {
        for ((type, pin) in listOf(
            BuiltinNodes.LITERAL_LIST to BuiltinNodes.LIST_COUNT,
            BuiltinNodes.LITERAL_LIST to BuiltinNodes.LIST_OF,
            BuiltinNodes.FORMAT to "Template",
        )) {
            val g = graph {
                val entry = node(BuiltinNodes.ENTRY)
                val src = node(BuiltinNodes.LITERAL, literals = mapOf("Value" to 2))
                val target = node(type)
                val ret = node(BuiltinNodes.RETURN)
                link(entry, "Exec", ret, "Exec")
                link(src, "Value", target, pin)
            }
            val issue = validator.validate(g).errors().single { it.pin == pin }
            assertContains(issue.message, "typed in rather than wired")
            assertEquals(3, issue.nodeId, "$type.$pin should be reported against the node it is on")
        }
    }

    @Test
    fun `an unknown node type is reported against the node`() {
        val g = graph { node("does.not.exist") }
        val issue = validator.validate(g).errors().single()
        assertContains(issue.message, "unknown node type")
        assertEquals(1, issue.nodeId)
    }

    @Test
    fun `incompatible pin types cannot be wired`() {
        // An npc id and an item id are both ints underneath; refusing the wire is the whole point of
        // having distinct pin types.
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val npc = node("test.npc")
            val sink = node("test.itemSink")
            link(entry, "Exec", sink, "Exec")
            link(npc, "Npc", sink, "Item")
        }
        val issue = validator.validate(g).errors().single()
        assertContains(issue.message, "cannot wire Npc into Item")
    }

    /**
     * INT widens to FLOAT; FLOAT does not narrow to INT, and the message says what to write instead.
     *
     * Narrowing is refused because there are four right answers to it — floor, ceil, round and toInt —
     * and picking one on the author's behalf is how a rounding bug gets written. Widening has one right
     * answer, so it needs no decision and is made for you (`Op.TOF`, emitted by `GraphCompiler.widen`).
     */
    @Test
    fun `int widens to float, and narrowing is refused with the fix in the message`() {
        val floatSink = hostNode(
            "test.floatSink", "f", NodeKind.IMPURE,
            inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("V", PinType.FLOAT)),
        )
        val intSink = hostNode(
            "test.intSink", "i", NodeKind.IMPURE,
            inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("V", PinType.INT)),
        )
        val intSrc = hostNode("test.intSrc", "is", NodeKind.PURE, outputs = listOf(PinSpec("V", PinType.INT)))
        val floatSrc = hostNode("test.floatSrc", "fs", NodeKind.PURE, outputs = listOf(PinSpec("V", PinType.FLOAT)))
        val c = NodeCatalog(listOf(floatSink, intSink, intSrc, floatSrc))

        val widening = graph {
            val src = node("test.intSrc")
            val sink = node("test.floatSink")
            link(src, "V", sink, "V")
        }
        assertEquals(emptyList(), Validator(c).validate(widening).errors(), "INT into FLOAT is allowed")

        val narrowing = graph {
            val src = node("test.floatSrc")
            val sink = node("test.intSink")
            link(src, "V", sink, "V")
        }
        val narrowingIssue = Validator(c).validate(narrowing).errors().single()
        assertContains(narrowingIssue.message, "cannot wire FLOAT into INT")
        assertContains(narrowingIssue.message, "round, floor, ceil or toInt")
    }

    @Test
    fun `an exec output driving two nodes is an error with a fix in the message`() {
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val a = node(BuiltinNodes.RETURN)
            val b = node(BuiltinNodes.RETURN)
            link(entry, "Exec", a, "Exec")
            link(entry, "Exec", b, "Exec")
        }
        val issue = validator.validate(g).errors().single()
        assertContains(issue.message, "use a Sequence node")
    }

    @Test
    fun `two exec wires may converge on one node`() {
        // The mirror of the case above: fan-IN is a legitimate merge point, fan-OUT is ambiguous.
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val br = node(BuiltinNodes.BRANCH)
            val tail = node(BuiltinNodes.RETURN)
            link(entry, "Exec", br, "Exec")
            link(br, "True", tail, "Exec")
            link(br, "False", tail, "Exec")
        }
        assertEquals(emptyList(), validator.validate(g).errors())
    }

    @Test
    fun `a data input fed by two wires is an error`() {
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val a = node("test.npc")
            val b = node("test.npc")
            val ret = node(BuiltinNodes.RETURN)
            link(entry, "Exec", ret, "Exec")
            link(a, "Npc", ret, "Value")
            link(b, "Npc", ret, "Value")
        }
        assertContains(validator.validate(g).errors().single().message, "fed by 2 wires")
    }

    @Test
    fun `a cycle among pure nodes is caught before it overflows the compiler`() {
        val g = graph {
            val a = node(BuiltinNodes.ADD)
            val b = node(BuiltinNodes.ADD)
            link(a, "Result", b, "A")
            link(b, "Result", a, "A")
        }
        assertTrue(validator.validate(g).errors().any { it.message.contains("cycle") })
    }

    @Test
    fun `a variable node with no or unknown variable is reported`() {
        val missing = graph { node(BuiltinNodes.VAR_GET) }
        assertContains(validator.validate(missing).errors().single().message, "no variable selected")

        val unknown = graph { node(BuiltinNodes.VAR_GET, variable = "nope") }
        assertContains(validator.validate(unknown).errors().single().message, "no graph variable named 'nope'")
    }

    @Test
    fun `a link to a missing node is reported against the link`() {
        val g = graph {
            node(BuiltinNodes.ENTRY)
            link(1, "Exec", 99, "Exec")
        }
        val issue = validator.validate(g).errors().single()
        assertContains(issue.message, "node that does not exist")
        assertEquals(1, issue.linkId)
    }

    @Test
    fun `a link to a pin the node does not have is reported`() {
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val ret = node(BuiltinNodes.RETURN)
            link(entry, "NoSuchPin", ret, "Exec")
        }
        assertContains(validator.validate(g).errors().single().message, "has no output pin 'NoSuchPin'")
    }

    @Test
    fun `duplicate node ids are reported`() {
        val g = graph {
            rawNode(Node(7, BuiltinNodes.ENTRY))
            rawNode(Node(7, BuiltinNodes.RETURN))
        }
        assertContains(validator.validate(g).errors().single().message, "duplicate node id 7")
    }

    @Test
    fun `a graph with no entry node warns rather than errors`() {
        val g = graph { node(BuiltinNodes.RETURN) }
        val issues = validator.validate(g)
        assertEquals(emptyList(), issues.errors())
        assertTrue(issues.any { it.severity == Severity.WARNING && it.message.contains("no entry node") })
    }

    @Test
    fun `a variable's declared type is enforced through Get and Set`() {
        // Get/Set are declared with a WILDCARD value pin so ONE node type can serve every variable. Left at
        // that, a variable is untyped in practice: you could declare `counter` a boolean, wire it into
        // arithmetic, and nothing would object — which is exactly what the pin types exist to catch.
        val bad = graph {
            variable("flag", PinType.BOOL, false)
            val entry = node(BuiltinNodes.ENTRY)
            val get = node(BuiltinNodes.VAR_GET, variable = "flag")
            val delay = node(BuiltinNodes.DELAY)
            link(entry, "Exec", delay, "Exec")
            link(get, "Value", delay, "Ms")
        }
        val issues = Validator(catalog).validate(bad)
        assertTrue(
            issues.errors().any { it.message.contains("BOOL") && it.message.contains("INT") },
            "a bool variable wired into an INT pin should be rejected: $issues",
        )
    }

    @Test
    fun `a variable of the right type still connects`() {
        val g = graph {
            variable("wait", PinType.INT, 600)
            val entry = node(BuiltinNodes.ENTRY)
            val get = node(BuiltinNodes.VAR_GET, variable = "wait")
            val delay = node(BuiltinNodes.DELAY)
            link(entry, "Exec", delay, "Exec")
            link(get, "Value", delay, "Ms")
        }
        assertTrue(Validator(catalog).validate(g).errors().isEmpty())
    }

    @Test
    fun `setting a variable from an incompatible source is rejected`() {
        val g = graph {
            variable("flag", PinType.BOOL, false)
            val entry = node(BuiltinNodes.ENTRY)
            val lit = node(BuiltinNodes.LITERAL_STRING, literals = mapOf("Value" to "hi"))
            val set = node(BuiltinNodes.VAR_SET, variable = "flag")
            link(entry, "Exec", set, "Exec")
            link(lit, "Value", set, "Value")
        }
        val issues = Validator(catalog).validate(g)
        assertTrue(
            issues.errors().any { it.message.contains("STRING") && it.message.contains("BOOL") },
            "a string wired into a bool variable should be rejected: $issues",
        )
    }

    /**
     * An impure node whose output is read, but which nothing ever runs.
     *
     * Found in the wild: a `Call GetEarnedXp` with its result wired into a log message and no exec input at
     * all. It compiled, it ran, and it printed nothing where the number should have been — for as long as
     * the script had existed. The compiler allocates a register for every impure node whether or not it is
     * reachable, so the read succeeds and hands back whatever was in it.
     */
    @Test
    fun `an impure node that never runs is not a legal source of a value`() {
        val g = graph {
            function("Earned", results = listOf("Xp" to PinType.INT))
            val start = node(BuiltinNodes.ENTRY)
            val ret = node(BuiltinNodes.RETURN)
            val call = node(BuiltinNodes.CALL, callee = "Earned")
            link(start, "Exec", ret, "Exec")
            link(call, "Xp", ret, "Value")          // read…
            // …but nothing execs into the call.
            val box = node(BuiltinNodes.FUNCTION, function = "Earned")
            link(box, "Exec", box, "Exec")
        }
        val issues = Validator(NodeCatalog()).validate(g)
        assertTrue(
            issues.any { it.severity == Severity.ERROR && "nothing runs" in it.message },
            "an unreachable Call's output was accepted: $issues",
        )
    }

    /** Wired into the chain, the same graph is fine. */
    @Test
    fun `the same node is fine once something runs it`() {
        val g = graph {
            function("Earned", results = listOf("Xp" to PinType.INT))
            val start = node(BuiltinNodes.ENTRY)
            val call = node(BuiltinNodes.CALL, callee = "Earned")
            val ret = node(BuiltinNodes.RETURN)
            link(start, "Exec", call, "Exec")
            link(call, "Exec", ret, "Exec")
            link(call, "Xp", ret, "Value")
            val box = node(BuiltinNodes.FUNCTION, function = "Earned")
            link(box, "Exec", box, "Exec")
        }
        assertTrue(Validator(NodeCatalog()).validate(g).errors().isEmpty())
    }

    /**
     * A PURE node needs no exec wire, and must not be reported.
     *
     * That is the entire distinction the check turns on: a pure node is an expression, re-evaluated
     * wherever it is read, so "does anything run it" is not a question that applies.
     */
    @Test
    fun `a pure node with no exec wire is not reported`() {
        val g = graph {
            val start = node(BuiltinNodes.ENTRY)
            val lit = node(BuiltinNodes.LITERAL_INT, literals = mapOf("Value" to 7))
            val add = node(BuiltinNodes.ADD)
            val ret = node(BuiltinNodes.RETURN)
            link(start, "Exec", ret, "Exec")
            link(lit, "Value", add, "A")
            link(lit, "Value", add, "B")
            link(add, "Result", ret, "Value")
        }
        assertTrue(Validator(NodeCatalog()).validate(g).errors().isEmpty())
    }

    /** A node inside a function body is reached from the box, not from an entry. */
    @Test
    fun `a body node is reachable from its own function box`() {
        val g = graph {
            function("Work", results = listOf("Out" to PinType.INT))
            val start = node(BuiltinNodes.ENTRY)
            val ret = node(BuiltinNodes.RETURN)
            link(start, "Exec", ret, "Exec")
            val box = node(BuiltinNodes.FUNCTION, function = "Work")
            val log = node(BuiltinNodes.LOG, literals = mapOf("Message" to "hi"), function = "Work")
            val now = node(BuiltinNodes.NOW, function = "Work")
            link(box, "Exec", log, "Exec")
            link(log, "Exec", box, "Exec")
            link(now, "Ms", box, "Out")
        }
        assertTrue(Validator(NodeCatalog()).validate(g).errors().isEmpty(), "${Validator(NodeCatalog()).validate(g)}")
    }

    // ---- a message with an empty hole -------------------------------------------------------------

    /**
     * GAPS #4. A `text("…{b}…")` whose `{b}` nothing fills.
     *
     * The mirror case — an argument with no hole — was already caught (`'text' has no input called 'z'`),
     * which is what made the silence here easy to miss. A `value.format` node's pins come from its own
     * template, so like a function's signature they name a thing and never a value to stand in for it;
     * that is precisely the condition `checkSignaturePinsFed` describes, and the node was simply outside
     * its filter. Unfilled, the hole reaches the log as the word `null` in the middle of a sentence.
     */
    private fun warningsOn(template: String, filled: Map<String, Any?>): List<String> {
        val g = graph {
            val start = node(BuiltinNodes.ENTRY)
            val fmt = node(BuiltinNodes.FORMAT, literals = mapOf("Template" to template) + filled)
            val log = node(BuiltinNodes.LOG)
            link(start, "Exec", log, "Exec")
            link(fmt, "Text", log, "Message")
        }
        return Validator(NodeCatalog()).validate(g)
            .filter { it.severity == Severity.WARNING }.map { it.message }
    }

    @Test
    fun `a hole with nothing filling it is reported`() {
        val warnings = warningsOn("{a} and {b}", mapOf("a" to 1))
        assertTrue(
            warnings.any { "{b}" in it },
            "the empty hole should be named in its braces, got: $warnings",
        )
    }

    @Test
    fun `a message with every hole filled is quiet`() {
        assertEquals(emptyList(), warningsOn("{a} and {b}", mapOf("a" to 1, "b" to 2)))
    }

    /** No holes, nothing to fill — and the Template pin must not report itself. */
    @Test
    fun `a message with no holes is quiet`() {
        assertEquals(emptyList(), warningsOn("plain text", emptyMap()))
    }

    @Test
    fun `every empty hole is named, not just the first`() {
        val warnings = warningsOn("{a} {b} {c}", mapOf("b" to 1))
        assertTrue(warnings.any { "{a}" in it }, "got: $warnings")
        assertTrue(warnings.any { "{c}" in it }, "got: $warnings")
    }

    /** A hole fed by a WIRE is filled just as much as one typed into. */
    @Test
    fun `a hole fed by a wire is quiet`() {
        val g = graph {
            val start = node(BuiltinNodes.ENTRY)
            val lit = node(BuiltinNodes.LITERAL_INT, literals = mapOf("Value" to 7))
            val fmt = node(BuiltinNodes.FORMAT, literals = mapOf("Template" to "count {n}"))
            val log = node(BuiltinNodes.LOG)
            link(start, "Exec", log, "Exec")
            link(lit, "Value", fmt, "n")
            link(fmt, "Text", log, "Message")
        }
        assertEquals(
            emptyList(),
            Validator(NodeCatalog()).validate(g)
                .filter { it.severity == Severity.WARNING }.map { it.message },
        )
    }
}
