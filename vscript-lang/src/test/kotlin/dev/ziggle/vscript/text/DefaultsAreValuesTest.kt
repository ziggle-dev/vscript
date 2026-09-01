package dev.ziggle.vscript.text

import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeDescriptor
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.StructValue
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * An OMITTED argument reaches a host as a value of its declared type, not as the text an editor would have
 * typed into the pin.
 *
 * The bug this pins down: [PinSpec.default] holds an editor string — `"false"` for a BOOL, `"#FF00FFFF"`
 * for a COLOR — so a host got one shape when the caller passed the argument and a different shape when it
 * did not. It survived because the hosts grew branches to absorb it, and those branches are why `Tile` and
 * `Color` becoming records did not fail loudly at the pins that were left behind.
 *
 * Written against [NodeCatalog.natives], the real projection, rather than against a hand-built
 * [NativeTable] — the conversion is only worth anything if it happens on the path the catalogue takes.
 */
class DefaultsAreValuesTest {

    private fun catalogue(vararg pins: PinSpec): NodeCatalog = NodeCatalog(
        listOf(
            NodeDescriptor(
                type = "probe.take",
                title = "Take",
                category = "Probe",
                kind = NodeKind.PURE,
                inputs = pins.toList(),
                outputs = listOf(PinSpec("Out", PinType.BOOL)),
                host = "probe.take",
                hostKind = HostKind.INLINE,
            )
        )
    )

    /** Call `_listTake()` with everything omitted and hand back what the host actually received. */
    private fun omitted(vararg pins: PinSpec): List<Any?> {
        val got = ArrayList<Any?>()
        val hosts = BuiltinHosts.registry()
            .register("probe.take", HostKind.INLINE, arity = pins.size, results = 1) { a ->
                got.addAll(a.toList()); true
            }
        val read = TextFrontEnd(catalogue(*pins).natives()).read(
            """
            graph "p"
            on start { probe.take() }
            """.trimIndent()
        )
        val chunk = read.chunk ?: fail("did not compile: " + read.errors.joinToString { "${it.span} ${it.message}" })
        drive(chunk, hosts)
        return got
    }

    @Test
    fun `a bool default is a boolean, not the text "false"`() {
        assertEquals(listOf<Any?>(false, true), omitted(
            PinSpec("Off", PinType.BOOL, default = "false"),
            PinSpec("On", PinType.BOOL, default = "true"),
        ))
    }

    /**
     * `7` and not `7L`: the VM narrows a Long back to an Int whenever it fits (`Values.arith`), so an Int is
     * the canonical form of a small whole number and a Long here would be a value nothing else produces.
     */
    @Test
    fun `numeric defaults are numbers`() {
        assertEquals(listOf<Any?>(7, 1.5), omitted(
            PinSpec("N", PinType.INT, default = "7"),
            PinSpec("F", PinType.FLOAT, default = "1.5"),
        ))
    }

    /**
     * A type the HOST declares says how its own stored form reads.
     *
     * **This is where the tile and colour cases that used to be here went**, and it is the same test with
     * the ownership removed. The one that mattered was a Color pin whose default is `#AARRGGBB` text while
     * a colour IS a record: a body reading `c.r` off an omitted argument was reading a field off a String.
     * Neither is a builtin now, so neither has a branch to be listed in — a host record says how its
     * stored form reads, and if that hook is not consulted the identical failure comes back one level
     * further out.
     */
    @Test
    fun `a host record's default arrives as the record`() {
        val record = dev.ziggle.vscript.model.HostRecord(
            "Coordinate",
            listOf(
                dev.ziggle.vscript.model.HostField("x", TypeRef(PinType.INT)),
                dev.ziggle.vscript.model.HostField("y", TypeRef(PinType.INT)),
            ),
            isData = true,
            read = { v ->
                val p = v.toString().split(',').map { it.trim().toIntOrNull() ?: 0 }
                StructValue("Coordinate", listOf("x", "y"), arrayOf<Any?>(p.getOrElse(0) { 0 }, p.getOrElse(1) { 0 }))
            },
        )
        dev.ziggle.vscript.model.HostRecords.register(record)
        try {
            val t = omitted(PinSpec("At", record.type, default = "3200,3210")).single()
            val rec = t as? StructValue ?: fail("expected a record, got ${t?.javaClass?.simpleName}: $t")
            assertEquals("Coordinate", rec.type)
            assertEquals(listOf<Any?>(3200, 3210), listOf(rec.get("x"), rec.get("y")))
        } finally {
            dev.ziggle.vscript.model.HostRecords.reset()
        }
    }

    /**
     * A STRING pin means its text and an ENUM member IS its name at run time, so both pass through
     * untouched — "converting" either would be the bug this test is guarding against in reverse.
     */
    @Test
    fun `string and enum defaults are left exactly as written`() {
        assertEquals(listOf<Any?>("false", "Attack"), omitted(
            PinSpec("Text", PinType.STRING, default = "false"),
            PinSpec("Choice", PinType.ENUM, default = "Attack", options = listOf("Attack", "Strength")),
        ))
    }

    /** Nothing to convert: an author who already wrote a value keeps it, and a pin with no default is null. */
    @Test
    fun `a value default and a missing default are untouched`() {
        assertEquals(listOf<Any?>(3L, null), omitted(
            PinSpec("N", PinType.INT, default = 3L),
            PinSpec("M", PinType.INT),
        ))
    }

    /** A default that cannot be parsed as its type stays as it was — a default is not a place to fail. */
    @Test
    fun `an unparseable default is passed through rather than dropped`() {
        assertTrue(omitted(PinSpec("N", PinType.INT, default = "not a number")).single() == "not a number")
    }
}
