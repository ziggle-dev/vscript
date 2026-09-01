package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.Disassembler
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `op fn` — an operator as a library declaration rather than a compiler special case.
 *
 * `xs[i]` is a hardcoded `Op.INDEX` today, typed by a rule that knows about lists and nothing else — so a
 * map is read with `_mapAt(map:, key:)`, a record cannot be indexed at all, and adding either meant
 * editing the compiler. As a declaration it is a name like any other: readable, documentable, reachable
 * through an import, and extendable without a language release.
 *
 * **`op` implies `inline`**, enforced. Without it this would trade a compiler special case for a
 * `CALL`/`RET` pair on the hottest operation in the language, which is the opposite of the point.
 *
 * **A LIST keeps its own instruction.** `Op.INDEX` is one opcode and needs no declaration to exist, so
 * routing the commonest read in the language through a lookup would cost something to arrive back where
 * it started.
 */
class OperatorFnTest {

    private val INT = TypeRef(PinType.INT)
    private val STRING = TypeRef(PinType.STRING)

    private val natives = NativeTable(
        listOf(NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList())),
    )

    private fun compile(src: String) = TextFrontEnd(natives).read(src)

    private fun run(src: String): List<String> {
        val said = ArrayList<String>()
        val hosts = BuiltinHosts.registry()
            .register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        val r = compile(src)
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        drive(chunk, hosts)
        return said
    }

    private fun asm(src: String): String {
        val r = compile(src)
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { it.message })
        return Disassembler.disassemble(chunk)
    }

    // ---- a map gets `[` ------------------------------------------------------------------------------

    @Test
    fun `a map can be indexed once it declares op fn get`() {
        val src = """
            op fn Map<K, V>.get(self, key: K) -> V? = _mapAt(self, key)

            on start {
                var m: Map<String, Int> = _newMap()
                _mapPut(m, "a", 1)
                log(message: "" + (m["a"] ?: 0))
                log(message: "" + (m["missing"] ?: -1))
            }
        """.trimIndent()
        assertEquals(listOf("1", "-1"), run(src))
    }

    /** `op` implies `inline`, so the operator costs an instruction rather than a frame. */
    @Test
    fun `an operator is spliced, not called`() {
        val src = """
            op fn Map<K, V>.get(self, key: K) -> V? = _mapAt(self, key)

            on start {
                var m: Map<String, Int> = _newMap()
                _mapPut(m, "a", 1)
                log(message: "" + (m["a"] ?: 0))
            }
        """.trimIndent()
        assertTrue("CALLG" !in asm(src), "the operator emitted a call rather than splicing:\n${asm(src)}")
        assertTrue("GETKEY" in asm(src), "expected the map read to reach its instruction:\n${asm(src)}")
    }

    /** A record is a type like any other — nothing about `[` is about containers. */
    @Test
    fun `a record can declare an index operator too`() {
        val src = """
            type Pair { a: Int, b: Int }

            op fn Pair.get(self, index: Int) -> Int = index == 0 ? self.a : self.b

            on start {
                val p = Pair { a: 10, b: 20 }
                log(message: "" + p[0] + " " + p[1])
            }
        """.trimIndent()
        assertEquals(listOf("10 20"), run(src))
    }

    // ---- lists are untouched -------------------------------------------------------------------------

    @Test
    fun `a list still uses its own instruction and needs no declaration`() {
        val src = """
            on start {
                val xs = [7, 8, 9]
                log(message: "" + xs[1])
            }
        """.trimIndent()
        assertEquals(listOf("8"), run(src))
        assertTrue("INDEX" in asm(src), "a list index should still be Op.INDEX:\n${asm(src)}")
    }

    // ---- writing through the index form --------------------------------------------------------------

    /**
     * `xs[i] = v` on a list — one instruction, and it could not exist a day ago.
     *
     * The parser refused it with a message that said why: *a list is a value, so no one position can be
     * written into*. A list is a reference now, so the slot is simply written.
     */
    @Test
    fun `a list position can be written`() {
        val src = """
            on start {
                var xs = [1, 2, 3]
                xs[1] = 9
                log(message: "" + xs[0] + " " + xs[1] + " " + xs[2])
            }
        """.trimIndent()
        assertEquals(listOf("1 9 3"), run(src))
        assertTrue("SETINDEX" in asm(src), "expected the write to reach its instruction:\n${asm(src)}")
    }

    @Test
    fun `a map can be written once it declares op fn set`() {
        val src = """
            op fn Map<K, V>.get(self, key: K) -> V? = _mapAt(self, key)
            op fn Map<K, V>.set(self, key: K, value: V) { _mapPut(self, key, value) }

            on start {
                var m: Map<String, Int> = _newMap()
                m["a"] = 1
                m["b"] = 2
                m["a"] = 3
                log(message: "" + (m["a"] ?: 0) + " " + (m["b"] ?: 0) + " " + _mapCount(m))
            }
        """.trimIndent()
        assertEquals(listOf("3 2 2"), run(src))
        assertTrue("CALLG" !in asm(src), "the set operator emitted a call rather than splicing")
    }

    /** The caller sees it, because there is one container rather than a copy per call. */
    @Test
    fun `writing through a reference is seen by the caller`() {
        val src = """
            inline fn List<Int>.bump(self, at: Int) { self[at] = self[at] + 100 }

            on start {
                var xs = [1, 2, 3]
                xs.bump(at: 0)
                log(message: "" + xs[0])
            }
        """.trimIndent()
        assertEquals(listOf("101"), run(src))
    }

    /**
     * The whole shape `core/map` ships, compiled together.
     *
     * The risky part is not the operators — it is the wrappers whose RESULT is generic in the receiver's
     * arguments. `keys()` must come back as `List<K>`, not a wildcard, or the first `for k in m.keys()`
     * downstream types its element as anything and the error surfaces somewhere else entirely.
     */
    @Test
    fun `the core-map shape compiles and keeps its type arguments`() {
        val lib = """
            export op fn Map<K, V>.get(self, key: K) -> V? = _mapAt(self, key)
            export op fn Map<K, V>.set(self, key: K, value: V) { _mapPut(self, key, value) }
            export inline fn Map<K, V>.has(self, key: K) -> Bool = _mapHas(self, key)
            export inline fn Map<K, V>.count(self) -> Int = _mapCount(self)
            export inline fn Map<K, V>.keys(self) -> List<K> = _mapKeys(map: self)
            export inline fn Map<K, V>.getOr(self, key: K, fallback: V) -> V = _mapAt(self, key) ?: fallback
        """.trimIndent()
        val main = """
            import "core/map"

            on start {
                var m: Map<String, Int> = _newMap()
                m["a"] = 1
                m["b"] = 2
                m["a"] = m.getOr(key: "a", fallback: 0) + 10

                var total = 0
                // If `keys()` lost its argument this would not type as a String below.
                for k in m.keys() {
                    total = total + m.getOr(key: k, fallback: 0) + _listCount(list: m.keys())
                }
                log(message: "" + m.count() + " " + m.has(key: "b") + " " + total)
            }
        """.trimIndent()
        val front = TextFrontEnd(
            NativeTable(
                listOf(
                    NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList()),
                    NativeFn(
                        "_mapKeys",
                        listOf(NativeParam("map", TypeRef.map(TypeRef.named("K").asVariable(), TypeRef.named("V").asVariable()))),
                        results = listOf(NativeParam("Result", TypeRef.list(TypeRef.named("K").asVariable()))),
                    ),
                ),
            ),
            imports = TextSource.of(mapOf("core/map" to lib)),
        )
        val read = front.read(main)
        val chunk = read.chunk ?: fail("did not compile: " + front.describe(read))

        val said = ArrayList<String>()
        val hosts = BuiltinHosts.registry()
            .register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
            .register("_mapKeys", HostKind.INLINE, arity = 1, results = 1) { a ->
                ArrayList((a[0] as Map<*, *>).keys)
            }
        drive(chunk, hosts)
        // 2 entries; "b" present; total = (11 + 2) + (2 keys counted twice) = 11+2+2+2 = 17
        assertEquals(listOf("2 true 17"), said)
        assertTrue("CALLG" !in Disassembler.disassemble(chunk), "something in core/map fell back to a call")
    }

    // ---- the refusals, which are loud ----------------------------------------------------------------

    @Test
    fun `indexing a type with no get says how to give it one`() {
        val errors = compile(
            """
            type Pair { a: Int, b: Int }

            on start {
                val p = Pair { a: 1, b: 2 }
                log(message: "" + p[0])
            }
            """.trimIndent(),
        ).errors.map { it.message }
        assertTrue(errors.any { "cannot be indexed" in it && "op fn" in it }, "got $errors")
    }

    @Test
    fun `an operator name outside the closed set is refused`() {
        val errors = compile(
            """
            op fn Int.shazam(self) -> Int = self

            on start {
                log(message: "1")
            }
            """.trimIndent(),
        ).errors.map { it.message }
        assertTrue(errors.any { "is not an operator" in it }, "got $errors")
    }

    @Test
    fun `writing by index into a type with no set says how to give it one`() {
        val errors = compile(
            """
            type Pair { a: Int, b: Int }

            on start {
                var p = Pair { a: 1, b: 2 }
                p[0] = 9
            }
            """.trimIndent(),
        ).errors.map { it.message }
        assertTrue(errors.any { "cannot be written by index" in it && "op fn" in it }, "got $errors")
    }

    @Test
    fun `an operator needs a receiver`() {
        val errors = compile(
            """
            op fn get(index: Int) -> Int = index

            on start {
                log(message: "1")
            }
            """.trimIndent(),
        ).errors.map { it.message }
        assertTrue(errors.any { "needs a receiver" in it }, "got $errors")
    }
}
