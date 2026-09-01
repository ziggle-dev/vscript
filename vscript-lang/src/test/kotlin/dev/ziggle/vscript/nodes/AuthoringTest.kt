package dev.ziggle.vscript.nodes

import dev.ziggle.vscript.model.HostEnum
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.vm.HostKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The authoring DSL — `NODE_AUTHORING_PLAN.md` phase 1.
 *
 * What these pin down is the set of mistakes the old `PinSpec` DSL made possible: a default that was the
 * editor's text rather than a value, a body reading its arguments by index, and results supplied
 * positionally against a list written somewhere else.
 */
class AuthoringTest {

    private fun NodeDef.call(vararg args: Any?): Any? = fn.invoke(arrayOf(*args))

    // ---- reading and writing through the declaration -----------------------------------------------

    @Test
    fun `a body reads its parameters through their declarations`() {
        val f = func("probe.add") {
            val a = param("A", Vs.int)
            val b = param("B", Vs.int, default = 10L)
            result("Sum", Vs.int)
            query { a() + b() }
        }
        assertEquals(3L, f.call(1, 2))
        // The default is a VALUE, and it is what an omitted argument reads as.
        assertEquals(11L, f.call(1))
    }

    @Test
    fun `results are supplied by name and land in declaration order`() {
        val f = func("probe.split") {
            val text = param("Text", Vs.string)
            val head = result("Head", Vs.string)
            val size = result("Size", Vs.int)
            query {
                // Written out of order on purpose: position is the declaration's business, not the body's.
                size set text().length.toLong()
                head set text().take(1)
            }
        }
        assertEquals(listOf<Any?>("a", 3L), (f.call("abc") as Array<*>).toList())
    }

    /**
     * Inserting a parameter in the MIDDLE re-points nothing.
     *
     * The old DSL's defining failure: bodies said `Args.int(a, 1)`, so a new pin at position 1 silently
     * turned every read below it into the wrong argument. It is why `SceneNodes` carries a comment saying
     * `Clickable` had to go last.
     */
    @Test
    fun `a parameter inserted in the middle does not move any other read`() {
        fun build(withMiddle: Boolean) = func("probe.mid") {
            val a = param("A", Vs.string)
            if (withMiddle) param("Middle", Vs.string, default = "?")
            val z = param("Z", Vs.string)
            result("Out", Vs.string)
            query { a() + z() }
        }
        assertEquals("xy", build(false).call("x", "y"))
        assertEquals("xy", build(true).call("x", "ignored", "y"))
    }

    // ---- defaults are values ------------------------------------------------------------------------

    /**
     * `default = "false"` for a Bool does not compile any more — this is the compile-time half of the
     * change, asserted the only way a test can: by showing the value form works and arrives as a Boolean.
     */
    @Test
    fun `a typed default reaches the pin as a value, not as text`() {
        val f = func("probe.flag") {
            param("On", Vs.bool, default = true)
            result("Out", Vs.bool)
            query { true }
        }
        assertEquals(true, f.descriptor.input("On")?.default)
    }

    /** [VsType.store] renders a default into the form a pin carries, for the types that have one. */
    @Test
    fun `a type may render its default into the pin's storage form`() {
        val hex = VsType.of(TypeRef.named("Color"), { _, v -> v }, { v -> "#%08X".format(v) })
        val f = func("probe.paint") {
            param("Color", hex, default = 0x80FF00FF.toInt())
            result("Out", Vs.bool)
            query { true }
        }
        assertEquals("#80FF00FF", f.descriptor.input("Color")?.default)
    }

    @Test
    fun `no default means no default`() {
        val f = func("probe.bare") {
            param("N", Vs.int)
            result("Out", Vs.int)
            query { 0L }
        }
        assertNull(f.descriptor.input("N")?.default)
    }

    // ---- shapes -------------------------------------------------------------------------------------

    @Test
    fun `a query has no exec pins and one result per declaration`() {
        val f = func("probe.two") {
            param("A", Vs.int)
            result("X", Vs.int)
            result("Y", Vs.int)
            query { }
        }
        assertEquals(NodeKind.PURE, f.descriptor.kind)
        assertEquals(HostKind.INLINE, f.descriptor.hostKind)
        assertEquals(listOf("A"), f.descriptor.inputs.map { it.name })
        assertEquals(listOf("X", "Y"), f.descriptor.outputs.map { it.name })
    }

    @Test
    fun `an action carries exec in and out, and blocks`() {
        val f = func("probe.go") {
            param("At", Vs.string)
            action { }
        }
        assertEquals(NodeKind.IMPURE, f.descriptor.kind)
        assertEquals(HostKind.BLOCKING, f.descriptor.hostKind)
        assertEquals(listOf("Exec", "At"), f.descriptor.inputs.map { it.name })
        assertEquals(listOf("Exec"), f.descriptor.outputs.map { it.name })
    }

    @Test
    fun `a command is sequenced but stays inline`() {
        val f = func("probe.mark") { command { } }
        assertEquals(NodeKind.IMPURE, f.descriptor.kind)
        assertEquals(HostKind.INLINE, f.descriptor.hostKind)
    }

    /**
     * An EXEC pin does not shift a body's arguments.
     *
     * The VM binds a host at `dataInputs.size` and passes only data arguments, so a field's index is its
     * position among the params — which is what lets an action's first parameter still be index 0.
     */
    @Test
    fun `an action's exec pin does not shift its parameter indices`() {
        val f = func("probe.act") {
            val a = param("A", Vs.string)
            result("Out", Vs.string)
            action { a() }
        }
        assertEquals("first", f.call("first"))
    }

    // ---- the single-result short form ---------------------------------------------------------------

    @Test
    fun `a lone result may simply be returned`() {
        val f = func("probe.one") {
            result("Out", Vs.int)
            query { 7L }
        }
        assertEquals(7L, f.call())
    }

    @Test
    fun `an explicit set beats the body's value`() {
        val f = func("probe.both") {
            val out = result("Out", Vs.int)
            query { out set 1L; 2L }
        }
        assertEquals(1L, f.call())
    }

    /** A body ending in a statement returns `Unit`, which is not a result. */
    @Test
    fun `a body that produces nothing leaves its result null`() {
        val f = func("probe.silent") {
            result("Out", Vs.int)
            query { }
        }
        assertNull(f.call())
    }

    @Test
    fun `a function with no results hands back nothing whatever the body says`() {
        val f = func("probe.void") { command { 99L } }
        assertNull(f.call())
    }

    // ---- refusals -----------------------------------------------------------------------------------

    @Test
    fun `two parameters cannot share a name`() {
        val e = assertFailsWith<IllegalArgumentException> {
            func("probe.dup") {
                param("N", Vs.int)
                param("N", Vs.int)
                query { }
            }
        }
        assertTrue(e.message!!.contains("'N'"), e.message)
    }

    @Test
    fun `two results cannot share a name`() {
        assertFailsWith<IllegalArgumentException> {
            func("probe.dup2") {
                result("N", Vs.int)
                result("N", Vs.int)
                query { }
            }
        }
    }

    /**
     * A parameter and a result MAY share one, and several in the catalogue do: `names.nearestNpcOfId`
     * takes an `NPC` (which kind) and answers an `NPC` (which one).
     */
    @Test
    fun `a parameter and a result may share a name`() {
        val f = func("probe.same") {
            val into = param("NPC", Vs.int)
            val out = result("NPC", Vs.int)
            query { out set into() + 1 }
        }
        assertEquals(1, f.descriptor.inputs.size)
        assertEquals(1, f.descriptor.outputs.size)
        assertEquals(2L, f.call(1))
    }

    // ---- libraries ----------------------------------------------------------------------------------

    /**
     * A library names its namespace once.
     *
     * It was written into all 310 declarations by hand, in files that already said which domain they were.
     */
    @Test
    fun `a library prefixes its functions`() {
        val c = library("skills") {
            func("skillLevel") { result("Level", Vs.int); query { 1L } }
            func("skillReal") { result("Level", Vs.int); query { 1L } }
        }
        assertEquals(listOf("skills.skillLevel", "skills.skillReal"), c.defs.map { it.descriptor.type })
        // The host binds under the same name, which is what makes the two impossible to drift.
        assertEquals(listOf("skills.skillLevel", "skills.skillReal"), c.defs.map { it.descriptor.host })
    }

    /** The escape hatch: a name that already says its namespace is taken as written. */
    @Test
    fun `a dotted name is left alone`() {
        val c = library("banktag") {
            func("open") { command { } }
            func("ui.tabOpen") { command { } }
        }
        assertEquals(listOf("banktag.open", "ui.tabOpen"), c.defs.map { it.descriptor.type })
    }

    @Test
    fun `a library supplies the category, and a function may still override it`() {
        val c = library("scene", category = "Scene") {
            func("nearestNpc") { result("N", Vs.int); query { 0L } }
            func("odd") { category("Elsewhere"); result("N", Vs.int); query { 0L } }
        }
        assertEquals(listOf("Scene", "Elsewhere"), c.defs.map { it.descriptor.category })
    }

    /** With no category stated, the prefix is a better guess than the empty string. */
    @Test
    fun `a library with no category names itself`() {
        val c = library("movement") { func("walkTo") { action { } } }
        assertEquals("Movement", c.defs.single().descriptor.category)
    }

    @Test
    fun `a title defaults to the last segment and can be replaced`() {
        val c = library("skills") {
            func("skillLevel") { result("L", Vs.int); query { 0L } }
            func("skillReal") { title("Base Level"); result("L", Vs.int); query { 0L } }
        }
        assertEquals(listOf("skillLevel", "Base Level"), c.defs.map { it.descriptor.title })
    }

    /** Types travel with the functions that are declared in terms of them. */
    @Test
    fun `a library carries the types its signatures need`() {
        val kind = HostEnum("ProbeKind", listOf("A", "B"), "a probe kind")
        val c = library("probe") {
            enum(kind)
            func("kindOf") { result("Kind", Vs.enum(kind)); query { "A" } }
        }
        assertEquals(listOf("ProbeKind"), c.enums.map { it.name })
        assertEquals(kind.type, c.defs.single().descriptor.output("Kind")?.type)
    }

    @Test
    fun `contributions merge`() {
        val a = library("a") { func("x") { command { } } }
        val b = library("b") { func("y") { command { } } }
        assertEquals(listOf("a.x", "b.y"), Contribution.of(listOf(a, b)).defs.map { it.descriptor.type })
    }

    // ---- prose --------------------------------------------------------------------------------------

    /** A multi-paragraph explanation can be written as a raw string instead of a `"…" +` chain. */
    @Test
    fun `doc is de-indented`() {
        val f = func("probe.doc") {
            doc(
                """
                First line.

                Second paragraph.
                """
            )
            command { }
        }
        assertEquals("First line.\n\nSecond paragraph.", f.descriptor.summary)
    }
}
