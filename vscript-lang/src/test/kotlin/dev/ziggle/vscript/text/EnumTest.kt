package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Enums, with and without the columns a member carries.
 *
 * A member IS its name at run time — the graph's representation, kept because a string survives being
 * read, diffed and hand-edited where an ordinal does not, and because it means an enum needs no opcode.
 * The columns are baked in as constants and read across by `vscript.enumField`, which is the same host
 * the graph compiler calls: one implementation of one idea rather than two that could disagree.
 */
class EnumTest {

    private val STRING = TypeRef(PinType.STRING)

    private val natives = NativeTable(
        listOf(NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList())),
    )

    private fun read(main: String, others: Map<String, String> = emptyMap()) =
        TextFrontEnd(natives, imports = TextSource.of(others)).read(main)

    private fun run(main: String, others: Map<String, String> = emptyMap()): List<String> {
        val said = ArrayList<String>()
        val hosts = BuiltinHosts.registry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        val r = read(main, others)
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        drive(chunk, hosts)
        return said
    }

    private fun errors(main: String) = read(main).errors.map { it.message }

    @Test
    fun `a member is named and read back`() {
        assertEquals(
            listOf("Idle"),
            run(
                """
                graph "probe"

                enum State { Idle, Working }

                on start {
                    val s = State.Idle
                    log(message: "" + s)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `values gives every member in declaration order`() {
        assertEquals(
            listOf("Idle", "Working"),
            run(
                """
                graph "probe"

                enum State { Idle, Working }

                on start {
                    for s in State.values() {
                        log(message: "" + s)
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    /** The whole point of a data enum: a table, read by member. */
    @Test
    fun `a column is read across the member list`() {
        assertEquals(
            listOf("herbs 46", "trees 44"),
            run(
                """
                graph "probe"

                enum Job(name: STRING, priority: INT) {
                    Herbs("herbs", 46),
                    Trees("trees", 44),
                }

                on start {
                    for j in Job.values() {
                        log(message: j.name + " " + j.priority)
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `an unknown member names the ones there are`() {
        assertTrue(
            errors(
                """
                graph "probe"

                enum State { Idle, Working }

                on start { log(message: "" + State.Idel) }
                """.trimIndent(),
            ).any { it.contains("Idel") && it.contains("Working") },
        )
    }

    @Test
    fun `an unknown column is refused`() {
        assertTrue(
            errors(
                """
                graph "probe"

                enum Job(name: STRING) { Herbs("herbs") }

                on start { log(message: Job.Herbs.nme) }
                """.trimIndent(),
            ).any { it.contains("nme") },
        )
    }

    @Test
    fun `a row of the wrong type is refused where it is written`() {
        assertTrue(
            errors(
                """
                graph "probe"

                enum Job(priority: INT) { Herbs("high") }

                on start { }
                """.trimIndent(),
            ).any { it.contains("INT") },
        )
    }

    /**
     * A column may hold a FUNCTION, which is the shape the activity roster is built on.
     *
     * The table is still a constant — a function becomes a value carrying its chunk index, folded where
     * that index is known, exactly as `GraphCompiler.materialise` folds the graph's tables.
     */
    @Test
    fun `a column may hold a function`() {
        assertEquals(
            listOf("ran herbs", "ran trees"),
            run(
                """
                graph "probe"

                fn herbs() { log(message: "ran herbs") }
                fn trees() { log(message: "ran trees") }

                enum Job(run: fn()) {
                    Herbs(herbs),
                    Trees(trees),
                }

                on start {
                    for j in Job.values() {
                        j.run()
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    /** And a RECORD of functions — the `impl: Hooks` column, which is why this matters. */
    @Test
    fun `a column may hold a record of functions`() {
        assertEquals(
            listOf("stepped"),
            run(
                """
                graph "probe"

                type Hooks { step: fn() }

                fn stepHerbs() { log(message: "stepped") }

                val HerbImpl: Hooks = Hooks { step: stepHerbs }

                enum Job(impl: Hooks) { Herbs(HerbImpl) }

                on start {
                    Job.Herbs.impl.step()
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `an enum crosses an import`() {
        assertEquals(
            listOf("herbs"),
            run(
                """
                graph "main"

                import * as jobs from "jobs"

                on start {
                    log(message: jobs::Job.Herbs.name)
                }
                """.trimIndent(),
                others = mapOf(
                    "jobs" to """
                        graph "jobs"

                        export enum Job(name: STRING) { Herbs("herbs") }
                    """.trimIndent(),
                ),
            ),
        )
    }
}
