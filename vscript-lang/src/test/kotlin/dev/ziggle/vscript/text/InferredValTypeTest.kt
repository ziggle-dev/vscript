package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * A `val` with no declared type takes the type of what it was assigned.
 *
 * It only ever did when the initialiser was a LITERAL. `val hook = Hooks { … }` is a struct literal, not a
 * literal — so `hook` was WILDCARD, `hook.prepare(…)` resolved to no callee at all, and the compiler
 * reported "the text front end does not compile a call to 'hook.prepare' yet". That reads like a missing
 * feature, and the feature was there: `FieldCallee` and `Op.CALLV` both existed and were never reached.
 * What was missing was the type.
 */
class InferredValTypeTest {

    private val natives = NativeTable(
        listOf(NativeFn("log", listOf(NativeParam("message", TypeRef(PinType.STRING))), results = emptyList())),
    )

    private fun run(main: String): List<String> {
        val said = ArrayList<String>()
        val hosts = BuiltinHosts.registry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        val r = TextFrontEnd(natives).read(main)
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        drive(chunk, hosts)
        return said
    }

    /** Reading a field off an unannotated `val`. */
    @Test
    fun `a val takes the type of the record it was assigned`() {
        assertEquals(
            listOf("7"),
            run(
                """
                type Box { size: INT }

                val bag = Box { size: 7 }

                on start { log(message: "" + bag.size) }
                """.trimIndent(),
            ),
        )
    }

    /**
     * ...and CALLING one, which is the case that reported a missing compiler feature.
     *
     * A record whose field holds a function is called through the value — `hook.prepare()` — and that is
     * `FieldCallee`, which the compiler has always known how to emit.
     */
    @Test
    fun `a function held in a field is callable through an unannotated val`() {
        assertEquals(
            listOf("11"),
            run(
                """
                type Hooks { step: fn() -> INT }

                val hook = Hooks { step: { 11 } }

                on start { log(message: "" + hook.step()) }
                """.trimIndent(),
            ),
        )
    }

    /** An explicit annotation still wins — nothing that had a type can lose or change one. */
    @Test
    fun `a declared type is not overwritten`() {
        val errors = TextFrontEnd(natives).resolve(
            """
            type Box { size: INT }

            val bag: INT = Box { size: 7 }
            """.trimIndent(),
        ).errors.map { it.message }
        assertEquals(1, errors.size, "expected the mismatch to still be reported, got $errors")
    }
}
