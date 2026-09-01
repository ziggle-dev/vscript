package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.ModuleNames
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A test document may see the internals of the ONE module it tests, and of nothing else.
 *
 * Without this the convention does not work. The scheduler's interesting arithmetic — `chainFor`,
 * `weightOf`, `demandFor` — is un-exported on purpose, so a test living beside it could only reach it by
 * exporting it first, and a codebase that exports everything in order to be testable has no visibility
 * left to enforce. `src/test` in Java and `friend` in C++ both exist for this, and both scope it narrowly.
 */
class TestModuleTest {

    private val natives = NativeTable(
        listOf(NativeFn("log", listOf(NativeParam("message", TypeRef(PinType.STRING))), results = emptyList())),
    )

    private val goal = """
        graph "scheduler/goal"

        export fn published(n: Int) -> Int = n

        fn internal(n: Int) -> Int = n * 7

        type Hidden { value: Int = 3 }
    """.trimIndent()

    private val other = """
        graph "scheduler/pick"

        fn alsoInternal(n: Int) -> Int = n
    """.trimIndent()

    private fun read(ref: String, src: String) =
        TextFrontEnd(
            natives,
            imports = TextSource.of(mapOf("scheduler/goal" to goal, "scheduler/pick" to other)),
            rootRef = ref,
        ).compileTests(src)

    // ---- the naming rule, on its own ------------------------------------------------------------------

    @Test
    fun `a test ref names what it tests`() {
        assertTrue(ModuleNames.isTestRef("scheduler/goal_test"))
        assertTrue(!ModuleNames.isTestRef("scheduler/goal"))
        // Not a test document, just a document whose name is the suffix — it tests nothing.
        assertTrue(!ModuleNames.isTestRef("_test"))
        assertEquals("scheduler/goal", ModuleNames.moduleUnderTest("scheduler/goal_test"))
        assertNull(ModuleNames.moduleUnderTest("scheduler/goal"))
        assertTrue(ModuleNames.maySeeInternals("scheduler/goal_test", "scheduler/goal"))
        assertTrue(!ModuleNames.maySeeInternals("scheduler/goal_test", "scheduler/pick"))
        assertTrue(!ModuleNames.maySeeInternals("scheduler/goal", "scheduler/pick"))
    }

    // ---- and what it buys -----------------------------------------------------------------------------

    @Test
    fun `a test reaches its subject's un-exported function`() {
        val r = read(
            "scheduler/goal_test",
            """
            graph "scheduler/goal_test"

            import { internal } from "scheduler/goal"

            test "seven times two is fourteen" {
                assert internal(n: 2) == 14
            }
            """.trimIndent(),
        )
        assertTrue(r.ok, r.errors.joinToString { "${it.span} ${it.message}" })
    }

    @Test
    fun `a test reaches its subject's un-exported type`() {
        val r = read(
            "scheduler/goal_test",
            """
            graph "scheduler/goal_test"

            import { Hidden } from "scheduler/goal"

            test "a hidden record still has its fields" {
                assert Hidden { }.value == 3
            }
            """.trimIndent(),
        )
        assertTrue(r.ok, r.errors.joinToString { "${it.span} ${it.message}" })
    }

    @Test
    fun `an ordinary document still cannot reach an internal`() {
        val r = read(
            "scheduler/report",
            """
            graph "scheduler/report"

            import { internal } from "scheduler/goal"

            test "not allowed" {
                assert internal(n: 2) == 14
            }
            """.trimIndent(),
        )
        assertTrue(!r.ok, "visibility still means something outside a test")
    }

    /** The rule is one hop and one subject — a test root is not a skeleton key for the whole tree. */
    @Test
    fun `a test cannot reach a THIRD module's internals`() {
        val r = read(
            "scheduler/goal_test",
            """
            graph "scheduler/goal_test"

            import { alsoInternal } from "scheduler/pick"

            test "not allowed either" {
                assert alsoInternal(n: 2) == 2
            }
            """.trimIndent(),
        )
        assertTrue(!r.ok, "a test may see what it tests, not what its subject's neighbours hide")
    }

    /** An export is still an export — the friend rule adds, it does not replace. */
    @Test
    fun `a test still sees the ordinary exports`() {
        val r = read(
            "scheduler/goal_test",
            """
            graph "scheduler/goal_test"

            import { published } from "scheduler/goal"

            test "published still works" {
                assert published(n: 2) == 2
            }
            """.trimIndent(),
        )
        assertTrue(r.ok, r.errors.joinToString { "${it.span} ${it.message}" })
    }

    // ---- several roots, as one lookup -----------------------------------------------------------------

    /**
     * A test tree beside a source tree: two indexes, one lookup, first wins.
     *
     * The suffix is what keeps this honest — `scheduler/goal` comes from the source root and
     * `scheduler/goal_test` from the test root, so neither can shadow the other however the roots are
     * ordered.
     */
    @Test
    fun `two roots resolve as one, in order`() {
        val src = TextSource.of(mapOf("scheduler/goal" to goal))
        val tests = TextSource.of(mapOf("scheduler/goal_test" to "graph \"scheduler/goal_test\"\n"))
        val both = TextSource.chain(src, tests)
        assertTrue(both.load("scheduler/goal")!!.contains("published"))
        assertTrue(both.load("scheduler/goal_test")!!.contains("goal_test"))
        assertNull(both.load("scheduler/nothing"))
    }

    @Test
    fun `the first root wins a name both answer to`() {
        val a = TextSource.of(mapOf("x" to "graph \"a\"\n"))
        val b = TextSource.of(mapOf("x" to "graph \"b\"\n"))
        assertTrue(TextSource.chain(a, b).load("x")!!.contains("\"a\""))
        assertTrue(TextSource.chain(b, a).load("x")!!.contains("\"b\""))
    }
}
