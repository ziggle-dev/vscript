package dev.ziggle.vscript.nodes

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.compile.errors
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.lang.Lexer
import dev.ziggle.vscript.lang.Lower
import dev.ziggle.vscript.lang.Parser
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.drive
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The calendar verbs, driven against a clock that does not move.
 *
 * This is the whole reason the clock is injectable. A script that plays like a person decides things by
 * the calendar — a budget that resets at midnight, a bedtime, a task that is only worth doing on a
 * weekend — and against the real clock none of it can be asserted: you would have to wait for midnight,
 * and the test would then pass or fail depending on when it ran. Handing the language a fixed instant
 * turns "what does it do at 23:50 on a Tuesday" into an ordinary test.
 *
 * A fixed ZONE as well as a fixed instant, and both matter. The day rolls over at the PLAYER's midnight,
 * so the same epoch millisecond is two different dates depending on where they are; a test that took the
 * machine's zone would pass in London and fail in Auckland.
 */
class ClockVerbsTest {

    private val sayNode = hostNode(
        "test.say", "say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val catalog = NodeCatalog(listOf(sayNode))

    /** 2026-08-18 is a Tuesday. Local time 23:50, in a zone the test pins so the date cannot drift. */
    private val zone: ZoneId = ZoneId.of("Europe/London")
    private fun at(local: String): Clock =
        Clock.fixed(java.time.LocalDateTime.parse(local).atZone(zone).toInstant(), zone)

    private fun said(src: String, clock: Clock): List<Any?> {
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse: ${parsed.errors.map { it.message }}")
        val low = Lower(catalog).lower(parsed.program)
        assertTrue(low.ok, "lower: ${low.errors}")
        assertEquals(emptyList(), Validator(catalog).validate(low.graph).errors(), "did not validate")
        val out = ArrayList<Any?>()
        val hosts = BuiltinHosts.registry(clock = clock)
        hosts.register("say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        val entry = low.graph.entries(catalog).single()
        val r = drive(GraphCompiler(catalog, debug = false).compile(low.graph, entry.id), hosts, maxTicks = 2000)
        assertEquals(null, r.fiber.error?.message, "the script failed rather than finishing")
        return out.map { if (it is Number && it !is Double) it.toLong() else it }
    }

    @Test
    fun `the calendar verbs read the clock they were given`() {
        val out = said(
            """
            on start {
                say(message: localDate())
                say(message: hourOfDay())
                say(message: minuteOfHour())
                say(message: dayOfWeek())
            }
            """.trimIndent(),
            at("2026-08-18T23:50:00"),
        )
        // YYYYMMDD, so a day comparison is arithmetic and a later date is a larger number.
        assertEquals(listOf(20_260_818L, 23L, 50L, 2L), out)
    }

    @Test
    fun `the date is a number that orders, and rolls at local midnight`() {
        val before = said("on start { say(message: localDate()) }", at("2026-08-18T23:59:59")).single() as Long
        val after = said("on start { say(message: localDate()) }", at("2026-08-19T00:00:01")).single() as Long
        assertTrue(after > before, "$after should sort after $before")
        assertEquals(20_260_819L, after)
        // Across a month end, where a naive YYYYMMDD would still order correctly but a naive day-of-month
        // would not — the reason the whole date is packed rather than just the day.
        assertTrue(
            (said("on start { say(message: localDate()) }", at("2026-09-01T00:30:00")).single() as Long) > 20_260_831L,
        )
    }

    @Test
    fun `msUntil counts forward to the next time the clock reads that`() {
        // Ten minutes to go tonight.
        assertEquals(
            listOf(10L * 60 * 1000),
            said("on start { say(message: msUntilLocal(hour: 23, minute: 0)) }", at("2026-08-18T22:50:00")),
        )
        // Asked once it has gone, the answer is TOMORROW's — never a negative number, which would invert
        // every comparison made against it.
        assertEquals(
            listOf(23L * 60 * 60 * 1000 + 50L * 60 * 1000),
            said("on start { say(message: msUntilLocal(hour: 23, minute: 0)) }", at("2026-08-18T23:10:00")),
        )
    }

    @Test
    fun `an out-of-range time is clamped rather than throwing`() {
        // A bedtime rolled from a jittered range can land at hour 24; that should mean midnight, not a
        // stopped script.
        val out = said("on start { say(message: msUntilLocal(hour: 99, minute: 99)) }", at("2026-08-18T23:50:00"))
        assertTrue((out.single() as Long) > 0, "a clamped time should still be in the future")
    }

    @Test
    fun `nowMs comes from the same clock`() {
        val expected = java.time.LocalDateTime.parse("2026-08-18T23:50:00").atZone(zone).toInstant().toEpochMilli()
        assertEquals(listOf(expected), said("on start { say(message: now()) }", at("2026-08-18T23:50:00")))
    }

    @Test
    fun `production gets the system clock`() {
        // The default is the real clock and not a frozen instant left in by accident — which a suite full
        // of fixed clocks would otherwise never notice.
        val before = Instant.now().toEpochMilli()
        val got = said("on start { say(message: now()) }", Clock.systemDefaultZone()).single() as Long
        assertTrue(got >= before - 1000, "the default clock should be the real one, got $got")
    }
}
