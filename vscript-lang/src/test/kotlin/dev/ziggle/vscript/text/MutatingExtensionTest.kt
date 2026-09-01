package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `self = …` in an extension is REFUSED, and this pins the refusal.
 *
 * It used to mean "store the result back over what the call was written on" — a whole compiler mechanism,
 * so that `xs.add(v)` could look like a mutation while a list was still a value. It existed for exactly
 * one declaration in the whole corpus, `core/list`'s `add`.
 *
 * Containers are references now, so a mutation is simply a mutation: `add` calls `_listAdd(self, value)`
 * and the caller sees it because there is only one list. The mechanism has nothing left to do.
 *
 * **Refused rather than deleted**, because deleting it would change what `self = …` MEANS — from "the
 * caller sees this" to "the caller does not" — and a wrong answer is worse than an error. This language
 * has been bitten twice by mechanisms that changed behaviour without saying so.
 *
 * `self.field = v` is a different thing and still works: on a `single` it mutates the one cell, which it
 * always did. See `SingleSelfWriteTest`.
 */
class MutatingExtensionTest {

    private val STRING = TypeRef(PinType.STRING)

    private val natives = NativeTable(
        listOf(NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList())),
    )

    private fun errors(src: String) = TextFrontEnd(natives).read(src).errors.map { it.message }

    @Test
    fun `an extension that assigns self is refused, naming what to write instead`() {
        val msgs = errors(
            """
            fn String.shout(self) {
                self = self + "!"
            }

            on start {
                var s = "hi"
                s.shout()
                log(message: s)
            }
            """.trimIndent(),
        )
        assertTrue(
            msgs.any { "no longer writes back" in it },
            "expected the refusal to name itself, got $msgs",
        )
    }

    /** The message has to say what to do instead, or it is just a wall. */
    @Test
    fun `the refusal names both ways out`() {
        val m = errors(
            """
            fn String.shout(self) {
                self = self + "!"
            }

            on start {
                log(message: "x")
            }
            """.trimIndent(),
        ).first { "no longer writes back" in it }
        assertTrue("mutate it directly" in m, "should offer mutation, got: $m")
        assertTrue("return" in m, "should offer returning a value, got: $m")
    }

    /**
     * The shape that REPLACED it: mutate the receiver and let the caller see it, because there is one
     * container rather than a copy per call.
     */
    @Test
    fun `mutating the receiver directly is what add does now`() {
        val said = ArrayList<String>()
        val hosts = BuiltinHosts.registry()
            .register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        val r = TextFrontEnd(natives).read(
            """
            inline fn List<T>.add(self, value: T) { _listAdd(self, value) }

            on start {
                var xs: List<Int> = _newList()
                xs.add(value: 1)
                xs.add(value: 2)
                log(message: "" + _listCount(xs))
            }
            """.trimIndent(),
        )
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        drive(chunk, hosts)
        assertEquals(listOf("2"), said, "the caller did not see the mutation")
    }
}
