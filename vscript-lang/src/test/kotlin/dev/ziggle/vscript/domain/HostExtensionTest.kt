package dev.ziggle.vscript.domain

import dev.ziggle.vscript.model.HostEnums
import dev.ziggle.vscript.model.HostRecords
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.nodes.NodeLibrary
import dev.ziggle.vscript.text.NativeFn
import dev.ziggle.vscript.text.NativeParam
import dev.ziggle.vscript.text.NativeTable
import dev.ziggle.vscript.text.TextFrontEnd
import dev.ziggle.vscript.text.natives
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.drive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A HOST can declare an extension — `fn INT.benchNumbered() -> Bench`.
 *
 * ### The gap this closes
 *
 * A document has been able to write `fn LIST<T>.first(self)` since generics landed. A host could not, and
 * the gap was doing real damage: `toItem`, `toObject`, `toNpc`, `ItemId`, `ObjectId`, `NpcId` and
 * `isValid` are hard-coded in `Intrinsics` — one game's vocabulary in the language's own intrinsic set,
 * typed against `TypeRef.named("Item")`/`NPC`/`OBJECT` — because a node pack had no way to spell them. The corpus
 * calls them 268 times, so they could not simply be deleted either.
 *
 * With this, a domain declares them and the language stops knowing what an item is.
 *
 * ### The rules, and why each is the one a document extension already follows
 *
 * - **The receiver is argument zero**, so an extension is an ordinary call once found.
 * - **It does not answer to its bare name.** `benchNumbered(3)` must not work, or the same function has two
 *   spellings and only one of them typechecks.
 * - **The host is asked LAST**, so a document can shadow a domain verb rather than collide with it.
 */
class HostExtensionTest {

    private val benchType = Greenhouse.Bench.type
    private val INT = TypeRef(PinType.INT)

    /** `n.benchNumbered()` — the receiver is declared FIRST in [NativeFn.params]. */
    private val benchNumbered = NativeFn(
        "benchNumbered",
        params = listOf(NativeParam("self", INT)),
        results = listOf(NativeParam("Result", benchType)),
        receiver = INT,
    )

    /** A second one, taking an argument as well as a receiver. */
    private val wetterThan = NativeFn(
        "wetterThan",
        params = listOf(NativeParam("self", benchType), NativeParam("than", INT)),
        results = listOf(NativeParam("Result", TypeRef(PinType.BOOL))),
        receiver = benchType,
    )

    private lateinit var library: NodeLibrary
    private lateinit var table: NativeTable

    @BeforeTest
    fun setUp() {
        HostRecords.reset(); HostEnums.reset(); Greenhouse.reset()
        val c = Greenhouse.library
        library = NodeLibrary(c.defs, enums = c.enums, records = c.records)
        table = NativeTable(NodeCatalog(library.descriptors).natives().all + listOf(benchNumbered, wetterThan))
    }

    @AfterTest
    fun tearDown() { HostRecords.reset(); HostEnums.reset() }

    private fun run(body: String): List<String> {
        val hosts = HostRegistry()
        library.install(hosts)
        hosts.register("benchNumbered", HostKind.INLINE, arity = 1, results = 1) { a ->
            Greenhouse.benches[((a[0] as Number).toInt() - 1).coerceIn(0, Greenhouse.benches.size - 1)]
        }
        hosts.register("wetterThan", HostKind.INLINE, arity = 2, results = 1) { a ->
            (a[0] as Greenhouse.BenchHandle).humidity > (a[1] as Number).toInt()
        }
        val r = TextFrontEnd(table).read("graph \"p\"\n\non start {\n$body\n}\n")
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span.line}: ${it.message}" })
        drive(chunk, hosts)
        return Greenhouse.said.toList()
    }

    private fun errorsIn(body: String): List<String> =
        TextFrontEnd(table).read("graph \"p\"\n\non start {\n$body\n}\n").errors.map { it.message }

    @Test
    fun `a host extension is written on a value`() {
        assertEquals(listOf("40"), run("    say(text: \"\" + 1.benchNumbered().humidity)"))
    }

    @Test
    fun `a host extension takes arguments after its receiver`() {
        assertEquals(listOf("true", "false"), run(
            "    say(text: \"\" + 2.benchNumbered().wetterThan(than: 50))\n" +
                "    say(text: \"\" + 1.benchNumbered().wetterThan(than: 50))",
        ))
    }

    @Test
    fun `a host extension does not answer to its bare name`() {
        // The rule a document extension already follows. Binding it as a plain name would make
        // `benchNumbered(self: 1)` and `1.benchNumbered()` two spellings of one function.
        assertTrue(
            errorsIn("    say(text: \"\" + benchNumbered(self: 1).humidity)").any { "benchNumbered" in it },
            "calling it by name should not resolve",
        )
    }

    @Test
    fun `two extensions of the same name on the same receiver are refused`() {
        // The name index does this for plain natives; the receiver index has to do it too, or a second
        // registration would silently shadow the first and which one you got would depend on load order.
        val clash = NativeFn(
            "benchNumbered",
            params = listOf(NativeParam("self", INT)),
            results = listOf(NativeParam("Result", benchType)),
            receiver = INT,
        )
        val e = kotlin.test.assertFailsWith<IllegalArgumentException> { NativeTable(listOf(benchNumbered, clash)) }
        assertTrue("benchNumbered" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test
    fun `an extension on the wrong receiver is refused in the receiver's own words`() {
        val errors = errorsIn("    say(text: \"\" + \"nope\".benchNumbered())")
        assertTrue(errors.any { "benchNumbered" in it }, "expected a complaint naming the verb, got $errors")
    }
}
