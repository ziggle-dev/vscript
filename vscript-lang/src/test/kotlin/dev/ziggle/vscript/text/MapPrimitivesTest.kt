package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.Disassembler
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The `_map*` primitives — one instruction each, and the reason they exist.
 *
 * **`withEntry` is a host call that COPIES the map**, so the accumulator loop the language documents as
 * costing nothing is O(n²). Measured on a live client: 3000 inserts took 60 seconds, 20ms apiece. The
 * graph front end never paid that because `AppendPass` rewrote the shape to `Op.SETKEY`; the text front
 * end runs no such pass, so every script anyone actually writes paid it in full.
 *
 * These are the fix, and being intrinsics is the point: an instruction that is always emitted, rather
 * than a copy a pass has to *prove* is unobservable and silently declines to remove. See
 * `docs/VSCRIPT_CONTAINERS_PLAN.md` §1.
 */
class MapPrimitivesTest {

    private val natives = NativeTable(
        listOf(NativeFn("log", listOf(NativeParam("message", TypeRef(PinType.STRING))), results = emptyList())),
    )

    private fun run(src: String): List<String> {
        val said = ArrayList<String>()
        val hosts = BuiltinHosts.registry()
            .register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        val r = TextFrontEnd(natives).read(src)
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        drive(chunk, hosts)
        return said
    }

    @Test
    fun `put, read back, count and drop`() {
        assertEquals(
            listOf("0", "2", "b", "true", "false", "1"),
            run(
                """
                on start {
                    var m: Map<String, String> = _newMap()
                    log(message: "" + _mapCount(m))
                    _mapPut(m, "a", "b")
                    _mapPut(m, "c", "d")
                    log(message: "" + _mapCount(m))
                    log(message: "" + (_mapAt(m, "a") ?: "none"))
                    log(message: "" + _mapHas(m, "c"))
                    _mapDrop(m, "c")
                    log(message: "" + _mapHas(m, "c"))
                    log(message: "" + _mapCount(m))
                }
                """.trimIndent(),
            ),
        )
    }

    /** A key that was never set is the ordinary case, so [Intrinsic.MAP_AT] is optional rather than a fault. */
    @Test
    fun `a missing key reads as nothing rather than failing`() {
        assertEquals(
            listOf("fallback"),
            run(
                """
                on start {
                    var m: Map<String, String> = _newMap()
                    log(message: _mapAt(m, "absent") ?: "fallback")
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * The regression that started all of this, asserted on the BYTECODE rather than the clock.
     *
     * A wall-clock threshold is a coin flip on a busy machine, and an instruction count cannot see this
     * bug at all — a copy hidden inside one `SETKEY` would retire the same number of instructions as an
     * in-place write. What actually distinguishes them is which instruction is emitted, so that is what
     * this reads: `_mapPut` must lower to `SETKEY`, and there must be no `CALL` in the loop, because a
     * `CALL` means it went back to the host that copies.
     */
    @Test
    fun `putting emits SETKEY and calls no host`() {
        val r = TextFrontEnd(natives).read(
            """
            on start {
                var m: Map<Int, Int> = _newMap()
                var i = 0
                while i < 10 {
                    _mapPut(m, i, i)
                    i = i + 1
                }
            }
            """.trimIndent(),
        )
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { it.message })
        val asm = Disassembler.disassemble(chunk)
        assertTrue("SETKEY" in asm, "no SETKEY emitted — _mapPut did not lower to an instruction:\n$asm")
        assertTrue("NEWMAP" in asm, "no NEWMAP emitted — _newMap is still a host call:\n$asm")
        assertTrue(
            "CALL" !in asm,
            "a CALL survived: the map verb went back to the host, which is the copy this replaces:\n$asm",
        )
    }

    /**
     * And the same loop written the OLD way still copies — which is what makes the pair meaningful.
     *
     * If `withEntry` ever stops emitting a `CALL` this assertion fails, and that is the right moment to
     * revisit it: it would mean the copying spelling had quietly become the fast one, and the plan's
     * migration story would need rewriting rather than silently succeeding.
     */
    @Test
    fun `withEntry still goes to the host, which is why the primitive exists`() {
        val r = TextFrontEnd(
            NativeTable(
                listOf(
                    NativeFn("log", listOf(NativeParam("message", TypeRef(PinType.STRING))), results = emptyList()),
                    NativeFn(
                        "_mapWith",
                        listOf(
                            NativeParam("map", TypeRef.map(TypeRef(PinType.INT), TypeRef(PinType.INT))),
                            NativeParam("key", TypeRef(PinType.INT)),
                            NativeParam("value", TypeRef(PinType.INT)),
                        ),
                        results = listOf(NativeParam("Result", TypeRef.map(TypeRef(PinType.INT), TypeRef(PinType.INT)))),
                    ),
                ),
            ),
        ).read(
            """
            on start {
                var m: Map<Int, Int> = _newMap()
                m = _mapWith(map: m, key: 1, value: 1)
            }
            """.trimIndent(),
        )
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { it.message })
        val asm = Disassembler.disassemble(chunk)
        assertTrue("CALL" in asm, "expected withEntry to still be a host call:\n$asm")
    }
}
