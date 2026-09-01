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
 * The four statement forms the corpus leans on hardest — field assignment (141 uses), destructuring (75),
 * `if val` (54) and `?:` (14).
 *
 * Picked by counting rather than by taste: the front end was run over all 44 scripts in
 * `plugins/scripts/src` and the refusals were grouped, which turns "what next" into data.
 */
class StatementFormsTest {

    private val STRING = TypeRef(PinType.STRING)
    private val INT = TypeRef(PinType.INT)
    private val BOOL = TypeRef(PinType.BOOL)

    private val natives = NativeTable(
        listOf(
            NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList()),
            // Several results, NAMED — which is what lets a destructuring bind by name rather than by
            // the order the pins happen to be declared in.
            NativeFn(
                "entityInfo",
                listOf(NativeParam("of", INT)),
                results = listOf(NativeParam("exists", BOOL), NativeParam("distance", INT)),
            ),
            NativeFn("nearest", results = outs(INT.orNull())),
            NativeFn("nothingNear", results = outs(INT.orNull())),
        ),
    )

    private fun read(src: String) = TextFrontEnd(natives).read(src)

    private fun run(src: String): List<String> {
        val said = ArrayList<String>()
        val hosts = HostRegistry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        hosts.register("entityInfo", HostKind.INLINE, arity = 1, results = 2) { a ->
            arrayOf<Any?>(true, (a[0] as Number).toInt() * 2)
        }
        hosts.register("nearest", HostKind.INLINE, arity = 0, results = 1) { 7 }
        hosts.register("nothingNear", HostKind.INLINE, arity = 0, results = 1) { null }
        val r = read(src)
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        drive(chunk, hosts)
        return said
    }

    private fun errors(src: String) = read(src).errors.map { it.message }

    private fun script(body: String) = """
        graph "probe"

        type Trip { bank: STRING, laps: INT }
        type Day { trip: Trip, done: BOOL }

        on start {
        $body
        }
    """.trimIndent()

    // ---- field assignment ----------------------------------------------------------------------------

    @Test
    fun `a field is replaced and the name rebound`() {
        assertEquals(
            listOf("Varrock 4"),
            run(
                script(
                    """    var t = Trip { bank: "Varrock", laps: 3 }
    t.laps = 4
    log(message: t.bank + " " + t.laps)""",
                ),
            ),
        )
    }

    /** `a.b.c = v` rebuilds outward — the inner record is replaced in its parent, and so on to the root. */
    @Test
    fun `a nested field rebuilds outward`() {
        assertEquals(
            listOf("9"),
            run(
                script(
                    """    var d = Day { trip: Trip { bank: "Varrock", laps: 1 }, done: false }
    d.trip.laps = 9
    log(message: "" + d.trip.laps)""",
                ),
            ),
        )
    }

    /** A record is a VALUE, so a copy taken before the write does not see it. */
    @Test
    fun `assigning a field does not write through a copy`() {
        assertEquals(
            listOf("3 4"),
            run(
                script(
                    """    var a = Trip { bank: "V", laps: 3 }
    var b = a
    b.laps = 4
    log(message: "" + a.laps + " " + b.laps)""",
                ),
            ),
        )
    }

    @Test
    fun `assigning a field of a val is refused`() {
        assertTrue(
            errors(
                script("""    val t = Trip { bank: "V", laps: 1 }${'\n'}    t.laps = 2"""),
            ).any { it.contains("names a value once") && it.contains("var t") },
        )
    }

    @Test
    fun `assigning an unknown field names the ones there are`() {
        assertTrue(
            errors(script("""    var t = Trip { bank: "V", laps: 1 }${'\n'}    t.lps = 2"""))
                .any { it.contains("lps") && it.contains("laps") },
        )
    }

    // ---- destructuring -------------------------------------------------------------------------------

    @Test
    fun `several results are bound at once`() {
        assertEquals(
            listOf("true 10"),
            run(script("""    val (found, dist) = entityInfo(of: 5)${'\n'}    log(message: "" + found + " " + dist)""")),
        )
    }

    @Test
    fun `each bound name takes its own result's type`() {
        val r = read(script("""    val (found, dist) = entityInfo(of: 5)"""))
        assertTrue(r.resolution?.ok == true, "did not resolve: ${r.errors.map { it.message }}")
        val bound = r.resolution!!.boundOf.values.first().associateBy { it.name }
        assertEquals(PinType.BOOL, bound.getValue("found").type.builtin)
        assertEquals(PinType.INT, bound.getValue("dist").type.builtin)
    }

    /**
     * A bare name binds BY NAME when it is one of the results' — so the order the pins were declared in
     * does not matter, which is the whole reason the form exists.
     */
    @Test
    fun `a destructuring may bind by name, out of order`() {
        assertEquals(
            listOf("10 true"),
            run(script("""    val (distance, exists) = entityInfo(of: 5)${'\n'}    log(message: "" + distance + " " + exists)""")),
        )
    }

    /** One name, and it is the SECOND result — a positional reading would take the first. */
    @Test
    fun `a single name takes the result it names`() {
        assertEquals(
            listOf("10"),
            run(script("""    val (distance) = entityInfo(of: 5)${'\n'}    log(message: "" + distance)""")),
        )
    }

    /** A name that is not a result's is positional, so an author may still call them what they like. */
    @Test
    fun `a name that matches nothing is positional`() {
        assertEquals(
            listOf("true"),
            run(script("""    val (here) = entityInfo(of: 5)${'\n'}    log(message: "" + here)""")),
        )
    }

    /** And an explicit pin, mixed with a positional one — the same rule a call's arguments follow. */
    @Test
    fun `name and position may be mixed`() {
        assertEquals(
            listOf("true 10"),
            run(
                script(
                    """    val (found, distance) = entityInfo(of: 5)
    log(message: "" + found + " " + distance)""",
                ),
            ),
        )
    }

    @Test
    fun `binding more names than there are results is refused`() {
        assertTrue(
            errors(script("""    val (a, b, c) = entityInfo(of: 5)""")).any { it.contains("value(s)") },
        )
    }

    /**
     * `entityInfo(of: 5).distance` — one of the OTHER results, by name.
     *
     * A call's type is its first result, which is right nearly always and wrong for exactly this; a
     * destructuring is a heavy way to reach for one value.
     */
    @Test
    fun `a named result can be read straight off a call`() {
        assertEquals(listOf("10"), run(script("""    log(message: "" + entityInfo(of: 5).distance)""")))
    }

    @Test
    fun `the first result is still what a bare call means`() {
        assertEquals(listOf("true"), run(script("""    log(message: "" + entityInfo(of: 5))""")))
    }

    // ---- if val --------------------------------------------------------------------------------------

    /** Inside the branch it is known to be there, so the `?` comes off. */
    @Test
    fun `if val binds the value when it is there`() {
        assertEquals(listOf("7"), run(script("""    if val n = nearest() {${'\n'}        log(message: "" + n)${'\n'}    }""")))
    }

    @Test
    fun `if val runs its else when there is nothing`() {
        assertEquals(
            listOf("none"),
            run(
                script(
                    """    if val n = nothingNear() {
        log(message: "" + n)
    } else {
        log(message: "none")
    }""",
                ),
            ),
        )
    }

    @Test
    fun `the bound name is not optional inside the branch`() {
        val r = read(script("""    if val n = nearest() {${'\n'}        log(message: "" + n)${'\n'}    }"""))
        assertTrue(r.resolution?.ok == true, "did not resolve: ${r.errors.map { it.message }}")
        assertTrue(!r.resolution!!.boundOf.values.first().first().type.optional)
    }

    @Test
    fun `if val on something never absent is refused`() {
        assertTrue(errors(script("""    if val n = 3 { log(message: "" + n) }""")).any { it.contains("always is present") })
    }

    // ---- elvis ---------------------------------------------------------------------------------------

    @Test
    fun `elvis takes the value when it is there`() {
        assertEquals(listOf("7"), run(script("""    log(message: "" + (nearest() ?: 0))""")))
    }

    @Test
    fun `elvis falls back when it is not`() {
        assertEquals(listOf("0"), run(script("""    log(message: "" + (nothingNear() ?: 0))""")))
    }

    /** The result is no longer optional, which is what makes `?:` the way out of an option. */
    @Test
    fun `elvis gives back something present`() {
        val r = read(script("""    val n = nearest() ?: 0"""))
        assertTrue(r.resolution?.ok == true, "did not resolve: ${r.errors.map { it.message }}")
        assertTrue(!r.resolution!!.localOf.values.first { it.name == "n" }.type.optional)
    }

    /** Dead code rather than wrong code — so it warns, and still compiles. */
    @Test
    fun `elvis on something never absent warns`() {
        val r = read(script("""    val n = 3 ?: 0"""))
        assertTrue(r.resolution?.ok == true, "should still compile: ${r.errors.map { it.message }}")
        assertTrue(r.resolution!!.warnings.any { it.message.contains("never absent") })
    }
}
