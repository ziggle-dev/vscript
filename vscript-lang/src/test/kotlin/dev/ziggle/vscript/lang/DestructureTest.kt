package dev.ziggle.vscript.lang

import dev.ziggle.vscript.model.NodeCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Taking several outputs off ONE read — `val (…) = f()`, by position or by pin name.
 *
 * **Why by name matters.** Positional binding follows the pin ORDER, so the names in it are the author's own
 * and say nothing about which pin was meant. A node that gains an output, or has two reordered, changes what
 * an existing script binds and nothing reports it. `val (n: Name, e: Entity)` cannot go wrong that way: a pin
 * that is not there is an error naming the ones that are.
 *
 * **One read, not several.** The reason to destructure rather than write `f().Name` and `f().Entity` is that
 * each of those is its own node — a fresh read — so the two could disagree. That is the argument
 * `scene.lastClicked`'s own doc makes for having one node with eight outputs instead of five nodes.
 */
class DestructureTest {

    private val catalog = NodeCatalog()

    /** A stand-in for any multi-output node: a Call's pins come from its signature, like a query's. */
    private val header = """
        graph "probe"

        export fn clicked() -> (Id: INT, Kind: STRING, Name: STRING, Age: INT) {
            return 1, "npc", "Cow", 3
        }
    """.trimIndent()

    private fun read(body: String) = VsText(catalog).read("$header\n\non start {\n$body\n}")

    private fun keeps(body: String) {
        val vs = VsText(catalog)
        val text = "$header\n\non start {\n$body\n}"
        val r = vs.read(text)
        assertTrue(r.ok, "should compile: ${r.errors.map { "${it.span} ${it.message}" }}")
        assertEquals(text, vs.write(assertNotNull(r.graph), r.comments).trim(), "the spelling changed")
    }

    // ---- by position -----------------------------------------------------------------------------------

    @Test
    fun `a positional binding takes the outputs in order`() {
        keeps(
            """
            |    val (id, kind, name, age) = clicked()
            |    log(message: name + id + kind + age)
            """.trimMargin(),
        )
    }

    /**
     * Fewer names than outputs is LEGAL and stays legal — but it is now said out loud.
     *
     * Kept legal because a node gaining an output must not break every script that destructured it. Said out
     * loud because nothing else can catch a wrong guess about the order.
     */
    @Test
    fun `taking a prefix warns about what was skipped`() {
        // Names that are NOT outputs, so this stays positional — a list of output names now binds by them
        // and never reaches the prefix rule at all.
        val r = read("    val (a, b) = clicked()\n    log(message: \"\" + a + b)")
        assertTrue(r.ok, "a prefix is legal: ${r.errors.map { it.message }}")
        val warning = r.warnings.joinToString("; ") { it.message }
        assertTrue("name" in warning && "age" in warning, "should name what was skipped: $warning")
        assertTrue("by name" in warning, "should point at the fix: $warning")
    }

    @Test
    fun `more names than outputs is refused`() {
        val r = read("    val (a, b, c, d, e) = clicked()\n    log(message: \"\" + a)")
        assertTrue(
            r.errors.any { "4 value(s)" in it.message && "5 names" in it.message },
            "${r.errors.map { it.message }}",
        )
    }

    // ---- by name ---------------------------------------------------------------------------------------

    /** The thing that was missing: pick the outputs you want, and say which they are. */
    @Test
    fun `a named binding picks pins and renames them`() {
        keeps(
            """
            |    val (clickedName: name, since: age) = clicked()
            |    log(message: clickedName + since)
            """.trimMargin(),
        )
    }

    /**
     * Renaming is what makes shadowing a non-issue.
     *
     * A pin called `Name` and a graph variable called `Name` can coexist, because the local is chosen by the
     * author rather than taken from the pin.
     */
    @Test
    fun `a named binding can avoid shadowing a graph variable`() {
        val vs = VsText(catalog)
        val text = """
            graph "probe"

            export var Name: STRING = "outer"

            export fn clicked() -> (Id: INT, Kind: STRING, Name: STRING, Age: INT) {
                return 1, "npc", "Cow", 3
            }

            on start {
                val (inner: name) = clicked()
                log(message: Name + inner)
            }
        """.trimIndent()
        val r = vs.read(text)
        assertTrue(r.ok, "should compile: ${r.errors.map { "${it.span} ${it.message}" }}")
        assertEquals(text, vs.write(assertNotNull(r.graph), r.comments).trim())
    }

    @Test
    fun `an unknown pin names the ones that exist`() {
        val r = read("    val (e: Entity) = clicked()\n    log(message: \"\" + e)")
        val message = r.errors.joinToString("; ") { it.message }
        assertTrue("Entity" in message, "should name what was written: $message")
        assertTrue("id" in message && "name" in message, "should list the real outputs: $message")
    }

    @Test
    fun `a pin may be named the way the printer spells it`() {
        // `Id` is written `id` in text, and both should resolve — the same forgiveness argument labels get.
        val r = read("    val (n: id) = clicked()\n    log(message: \"\" + n)")
        assertTrue(r.ok, "lower-case pin text should resolve: ${r.errors.map { it.message }}")
    }

    /**
     * A bare name in a by-name list is shorthand for `x: x` — Rust's field shorthand.
     *
     * The mixture used to be refused, on the grounds that a half-positional list would let a later insertion
     * move what a bare entry binds. That is true of a half-positional list and this is not one: ONE rename
     * makes the whole list by-name, so nothing in it is positional and nothing moves.
     */
    @Test
    fun `a bare name beside a rename is shorthand for itself`() {
        keeps(
            """
            |    val (id, clickedName: name, age) = clicked()
            |    log(message: clickedName + id + age)
            """.trimMargin(),
        )
    }

    /** Order is irrelevant once a list is by name, which is most of the point of writing one. */
    @Test
    fun `shorthand entries may be written in any order`() {
        val r = read("    val (age, n: name, id) = clicked()\n    log(message: n + id + age)")
        assertTrue(r.ok, "should compile: ${r.errors.map { it.message }}")
    }

    /** A shorthand naming no output says so, and says the shorthand is what it is. */
    @Test
    fun `a shorthand naming no output is refused`() {
        val message = read("    val (nope, n: name) = clicked()\n    log(message: n)")
            .errors.joinToString("; ") { it.message }
        assertTrue("nope" in message, message)
        assertTrue("shorthand" in message, "should explain the bare form: $message")
    }

    /**
     * An all-bare list whose names are ALL outputs binds by those outputs, not by position.
     *
     * The reading that makes `val (exists, distance) = entityInfo(…)` do what it looks like. It is a real
     * change of meaning for a list written out of order — `val (name, id)` used to take the first two pins —
     * and that is the case it exists to fix.
     */
    @Test
    fun `an all-bare list of output names binds by name`() {
        keeps(
            """
            |    val (name, id) = clicked()
            |    log(message: name + id)
            """.trimMargin(),
        )
        // `name` took the pin called Name, not the first output.
        val graph = assertNotNull(read("    val (name, id) = clicked()\n    log(message: name + id)").graph)
        val get = graph.nodes.first { it.type == dev.ziggle.vscript.model.BuiltinNodes.CALL }
        val nameLink = graph.links.first { it.fromNode == get.id && it.fromPin == "Name" }
        assertTrue(nameLink.toPin.isNotEmpty(), "the Name pin should be read")
    }

    /**
     * A list whose names are NOT all outputs is positional, exactly as before.
     *
     * What keeps the change from reaching every existing destructure: the names have to be pins for
     * anything to move, and `val (a, b) = …` names nothing.
     */
    @Test
    fun `a list of ordinary names is still positional`() {
        keeps(
            """
            |    val (a, b) = clicked()
            |    log(message: b + a)
            """.trimMargin(),
        )
    }

    /**
     * The printer cannot take the positional shortcut when the by-name reading would disagree.
     *
     * `val (kind, id)` over pins Id, Kind IS a leading prefix, so the old rule printed it bare — and a bare
     * list of output names now reads BY NAME, which binds the other way round. The shortcut has to check.
     */
    @Test
    fun `a prefix whose names are swapped prints by name`() {
        val vs = VsText(catalog)
        val text = "$header\n\non start {\n    val (kind: id, id: kind) = clicked()\n    log(message: kind + id)\n}"
        val r = vs.read(text)
        assertTrue(r.ok, "${r.errors.map { it.message }}")
        val out = vs.write(assertNotNull(r.graph)).trim()
        assertTrue("kind: id" in out && "id: kind" in out, "must not print bare:\n$out")
        val again = vs.read(out)
        assertEquals(out, vs.write(assertNotNull(again.graph)).trim(), "not a fixpoint")
    }

    // ---- the printer's rule ----------------------------------------------------------------------------

    /**
     * A non-prefix selection MUST print by name, or it reads back as different wires.
     *
     * The old printer avoided this by naming EVERY output, so a positional binding always covered the whole
     * node and a subset never arose — a correct guard, and the reason `val (a, b) = clicked()` came back as
     * `val (id, kind, name, age) = clicked()`. Recording what was bound replaces the guard with the fact, so
     * the subset is now both expressible and printable.
     */
    @Test
    fun `a non-prefix selection round-trips because it prints by name`() {
        val vs = VsText(catalog)
        val text = "$header\n\non start {\n    val (k: kind, a: age) = clicked()\n    log(message: k + a)\n}"
        val r = vs.read(text)
        assertTrue(r.ok, "should compile: ${r.errors.map { it.message }}")
        val out = vs.write(assertNotNull(r.graph)).trim()
        assertTrue("k: kind" in out && "a: age" in out, "a non-prefix selection must name its pins:\n$out")

        // The wires are what must survive, so read it back and check the same pins are bound.
        val again = vs.read(out)
        assertTrue(again.ok, "${again.errors.map { it.message }}")
        assertEquals(out, vs.write(assertNotNull(again.graph)).trim(), "not a fixpoint")
    }

    /**
     * A named binding that IS the leading prefix prints positionally.
     *
     * Deliberate: the two select the same pins, so they are the same program, and positional is what most
     * bindings are. Canonicalising needs no marker precisely because the rule reads off the wires — the same
     * graph always prints the same way.
     */
    @Test
    fun `a named prefix is canonicalised to positional`() {
        val vs = VsText(catalog)
        val r = vs.read("$header\n\non start {\n    val (a: id, b: kind) = clicked()\n    log(message: b + a)\n}")
        assertTrue(r.ok, "${r.errors.map { it.message }}")
        val out = vs.write(assertNotNull(r.graph)).trim()
        assertTrue("val (a, b) = clicked()" in out, "a prefix should print positionally:\n$out")

        val again = vs.read(out)
        assertEquals(out, vs.write(assertNotNull(again.graph)).trim(), "not a fixpoint")
    }
}
