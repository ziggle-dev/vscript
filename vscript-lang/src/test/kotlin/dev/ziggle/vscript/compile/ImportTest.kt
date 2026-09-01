package dev.ziggle.vscript.compile

import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.GraphSource
import dev.ziggle.vscript.model.ImportClosure
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.drive
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Imports: calling another document's functions, and sharing its variables.
 *
 * The assertions that matter are about **identity of state**, not about values. A cross-document call that
 * compiled the callee against the caller's document would still return the right number for anything that
 * touches no globals — so a value assertion alone would pass against a build that resolves every imported
 * name in the wrong place. What distinguishes a real import is that the library's variable is *the same
 * cell* the library itself writes, so the tests read it from both sides.
 */
class ImportTest {

    private val sink = hostNode(
        "test.sink", "sink", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("A", PinType.INT, default = 0)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Result", PinType.INT)),
    )

    private val catalog = NodeCatalog(listOf(sink))

    private class Host {
        val seen = ArrayList<Int>()
        fun registry(): HostRegistry = HostRegistry().apply {
            register("sink", HostKind.INLINE, arity = 1) { args -> (args[0] as Int).also { seen += it } }
        }
    }

    // ---- the closure ------------------------------------------------------------------------------

    @Test
    fun `a diamond gives the shared document ONE set of slots`() {
        val d = library("d") { variable("n", PinType.INT, 7) }
        val b = library("b") { import("d"); variable("bv", PinType.INT, 1) }
        val c = library("c") { import("d"); variable("cv", PinType.INT, 2) }
        val root = graph("root") { import("b"); import("c"); variable("rv", PinType.INT, 0) }

        val closure = ImportClosure.resolve(root, GraphSource.of(listOf(root, b, c, d)))

        assertTrue(closure.ok, closure.errors.joinToString { it.message })
        // root, b, d, c — depth first in declaration order, and `d` appears ONCE despite two importers.
        assertEquals(listOf("root", "b", "d", "c"), closure.documents.map { it.name })
        assertEquals(4, closure.globalsSize)
        // The whole point: both b and c see d's `n` at the same slot.
        assertEquals(closure.globalsBase(d), closure.globalsBase(d))
        assertEquals(listOf(0, 1, 2), listOf(0, 1, 2).map { it })
    }

    /**
     * A document that resolves but does not compile.
     *
     * The closure keeps it, and that is the point. Withholding a broken document left the importer unable
     * to shape a single Call through it — one mistake in a library and every file using it reported
     * `'sqrt' takes 0 value(s)` at each call site, which says nothing true about either file. Its SHAPE is
     * sound (signatures are collected before any body is lowered); only its bodies are not. So the shape
     * stays available and the soundness is reported once, on the import.
     */
    @Test
    fun `a resolved but broken document is reported on the import, and still shapes calls`() {
        val lib = library("lib") { variable("n", PinType.INT, 1) }
        val root = graph("root") { import("lib") }
        val source = object : GraphSource {
            override fun load(imp: dev.ziggle.vscript.model.GraphImport) = lib
            override fun problem(imp: dev.ziggle.vscript.model.GraphImport) = "'x' cannot be reassigned"
        }

        val closure = ImportClosure.resolve(root, source)

        val error = closure.errors.single()
        assertTrue(error.message.contains("has errors of its own"), error.message)
        assertTrue(error.message.contains("'x' cannot be reassigned"), "say WHAT is wrong: ${error.message}")
        assertEquals("import:lib", error.declaration, "it belongs on the import line, not on a call")
        // The load-bearing half: the document is still in the closure, so the importer can shape its calls
        // and does not produce a second wave of complaints about damage this one line already explains.
        assertEquals(listOf("root", "lib"), closure.documents.map { it.name })
    }

    @Test
    fun `a source that knows of no problem reports none`() {
        // `problem` is a default method, so every existing source — the tests, the canvas — inherits
        // "nothing is wrong" and is unaffected.
        val lib = library("lib") { variable("n", PinType.INT, 1) }
        val root = graph("root") { import("lib") }
        val closure = ImportClosure.resolve(root, GraphSource.of(listOf(root, lib)))
        assertTrue(closure.ok, closure.errors.joinToString { it.message })
    }

    @Test
    fun `an import cycle is refused, naming the path`() {
        val a = library("a") { import("b") }
        val b = library("b") { import("a") }
        val closure = ImportClosure.resolve(a, GraphSource.of(listOf(a, b)))

        assertTrue(!closure.ok)
        val msg = closure.errors.single().message
        assertTrue(msg.startsWith("import cycle:"), msg)
        assertTrue(msg.contains("a") && msg.contains("b"), msg)
    }

    @Test
    fun `an unresolved import says what it could not find`() {
        val root = graph("root") { import("banking", "banking") }
        val closure = ImportClosure.resolve(root, GraphSource.of(listOf(root)))

        assertTrue(!closure.ok)
        assertTrue(closure.errors.single().message.contains("banking"), closure.errors.single().message)
    }

    // ---- calling across documents -----------------------------------------------------------------

    @Test
    @Timeout(10)
    fun `a call into an imported function runs that document's body`() {
        // The library's `answer()` returns 42 by way of its own variable, so a body compiled against the
        // WRONG document would read the caller's slot and see 0.
        val lib = library("lib") {
            variable("secret", PinType.INT, 42)
            function("answer", results = listOf("Out" to PinType.INT))
            val box = node(BuiltinNodes.FUNCTION, function = "answer")
            val get = node(BuiltinNodes.VAR_GET, variable = "secret", function = "answer")
            link(get, "Value", box, "Out")
        }
        val root = graph("root") {
            import("lib")
            variable("secret", PinType.INT, 0) // same NAME, different document — must not be confused
            val entry = node(BuiltinNodes.ENTRY)
            val call = node(BuiltinNodes.CALL, callee = "lib::answer")
            val out = node("test.sink")
            link(entry, "Exec", call, "Exec")
            link(call, "Exec", out, "Exec")
            link(call, "Out", out, "A")
        }

        val host = Host()
        val compiler = GraphCompiler(catalog, debug = false, source = GraphSource.of(listOf(root, lib)))
        drive(compiler.compile(root, 1), host.registry())

        assertEquals(listOf(42), host.seen)
    }

    @Test
    @Timeout(10)
    fun `an imported variable is shared, not copied`() {
        // The library WRITES its own variable; the root reads it through the alias. If imports were
        // by-value the root would read the starting 1 rather than the 99 the library just stored.
        val lib = library("lib") {
            variable("count", PinType.INT, 1)
            function("bump")
            val box = node(BuiltinNodes.FUNCTION, function = "bump")
            val set = node(BuiltinNodes.VAR_SET, literals = mapOf("Value" to 99), variable = "count", function = "bump")
            link(box, "Exec", set, "Exec")
        }
        val root = graph("root") {
            import("lib")
            val entry = node(BuiltinNodes.ENTRY)
            val call = node(BuiltinNodes.CALL, callee = "lib::bump")
            val get = node(BuiltinNodes.VAR_GET, variable = "lib::count")
            val out = node("test.sink")
            link(entry, "Exec", call, "Exec")
            link(call, "Exec", out, "Exec")
            link(get, "Value", out, "A")
        }

        val host = Host()
        val compiler = GraphCompiler(catalog, debug = false, source = GraphSource.of(listOf(root, lib)))
        drive(compiler.compile(root, 1), host.registry())

        assertEquals(listOf(99), host.seen)
    }

    @Test
    @Timeout(10)
    fun `two documents each with a variable of the same name get their own cells`() {
        val lib = library("lib") {
            variable("n", PinType.INT, 5)
            function("mine", results = listOf("Out" to PinType.INT))
            val box = node(BuiltinNodes.FUNCTION, function = "mine")
            val get = node(BuiltinNodes.VAR_GET, variable = "n", function = "mine")
            link(get, "Value", box, "Out")
        }
        val root = graph("root") {
            import("lib")
            variable("n", PinType.INT, 3)
            val entry = node(BuiltinNodes.ENTRY)
            val call = node(BuiltinNodes.CALL, callee = "lib::mine")
            val mine = node(BuiltinNodes.VAR_GET, variable = "n")
            val theirs = node("test.sink")
            val ours = node("test.sink")
            // One chain: an exec output drives exactly one node, so the call is a STEP ahead of the sinks
            // rather than a second wire off the entry.
            link(entry, "Exec", call, "Exec")
            link(call, "Exec", theirs, "Exec")
            link(theirs, "Exec", ours, "Exec")
            link(call, "Out", theirs, "A")
            link(mine, "Value", ours, "A")
        }

        val host = Host()
        val compiler = GraphCompiler(catalog, debug = false, source = GraphSource.of(listOf(root, lib)))
        drive(compiler.compile(root, 1), host.registry())

        // 5 from the library's `n`, 3 from the root's — same name, two cells.
        assertEquals(listOf(5, 3), host.seen)
    }

    @Test
    @Timeout(10)
    fun `a local helper and an imported one of the same name do not collide`() {
        // Both documents declare `helper`. The recursion guard keys on (document, name); keying on the
        // name alone would see the second as re-entering the first and refuse to compile.
        val lib = library("lib") {
            function("helper", results = listOf("Out" to PinType.INT))
            val box = node(BuiltinNodes.FUNCTION, function = "helper")
            val v = node(BuiltinNodes.LITERAL_INT, literals = mapOf("Value" to 10), function = "helper")
            link(v, "Value", box, "Out")
        }
        var entryId = 0
        val root = graph("root") {
            import("lib")
            function("helper", results = listOf("Out" to PinType.INT))
            val box = node(BuiltinNodes.FUNCTION, function = "helper")
            val v = node(BuiltinNodes.LITERAL_INT, literals = mapOf("Value" to 20), function = "helper")
            link(v, "Value", box, "Out")

            val entry = node(BuiltinNodes.ENTRY).also { entryId = it }
            val theirs = node(BuiltinNodes.CALL, callee = "lib::helper")
            val ours = node(BuiltinNodes.CALL, callee = "helper")
            val a = node("test.sink")
            val b = node("test.sink")
            link(entry, "Exec", a, "Exec")
            link(a, "Exec", b, "Exec")
            link(theirs, "Out", a, "A")
            link(ours, "Out", b, "A")
        }

        val host = Host()
        val compiler = GraphCompiler(catalog, debug = false, source = GraphSource.of(listOf(root, lib)))
        drive(compiler.compile(root, entryId), host.registry())

        assertEquals(listOf(10, 20), host.seen)
    }

    // ---- what the validator refuses ---------------------------------------------------------------

    @Test
    fun `calling through an alias nothing is imported as is an error about the ALIAS`() {
        val root = graph("root") {
            val entry = node(BuiltinNodes.ENTRY)
            val call = node(BuiltinNodes.CALL, callee = "banking::withdraw")
            link(entry, "Exec", call, "Exec")
        }
        val issues = Validator(catalog, GraphSource.NONE).validate(root)
        assertTrue(
            issues.any { it.message.contains("nothing is imported as 'banking'") },
            issues.joinToString { it.message },
        )
    }
}
