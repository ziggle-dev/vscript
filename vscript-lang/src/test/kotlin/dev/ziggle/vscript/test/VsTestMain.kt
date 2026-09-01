package dev.ziggle.vscript.test

import dev.ziggle.vscript.model.ModuleNames
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.text.TextFrontEnd
import dev.ziggle.vscript.text.TextSource
import dev.ziggle.vscript.vm.HostRegistry
import kotlin.system.exitProcess

/**
 * `gradlew vsTest` — run the in-language tests in the real scripts tree.
 *
 * **The half of the testing feature that was missing.** The syntax, the compiler path and the runner were
 * all built and unit-tested against sources those tests wrote themselves; nothing ever pointed them at a
 * `.vs` file on disk, so the feature had never been used. This is the pointing.
 *
 * ### What it runs
 *
 * Every document whose reference ends in `_test` — `scheduler/goal_test` tests `scheduler/goal` — which is
 * the convention `ModuleNames` already encodes and the one that grants a test friend access to its own
 * subject's un-exported names. A document with no `test` block in it costs a compile and contributes
 * nothing, which is the right price for not having to list them anywhere.
 *
 * ### No game
 *
 * Hosts are [BuiltinHosts] and nothing else, so **any node that touches the world throws**. That is the
 * rule the scheduler already writes to — `goal.vs` says its planner "reads nothing from the world" for
 * exactly this reason — and it is what makes the result mean something: a test that passed by reading a
 * live account would pass differently tomorrow.
 *
 * The catalogue still has to be present, because the documents under test are declared against it. It
 * comes from whatever JSON a client or a client build last wrote; see [RealCorpus].
 *
 * ### Absence is not failure
 *
 * A fresh clone has no scripts tree and no catalogue, and a task that failed the build for that is one
 * nobody keeps. It says what is missing and exits 0. A test that FAILS exits 1.
 */
object VsTestMain {

    private fun hosts(): HostRegistry = BuiltinHosts.registry()

    @JvmStatic
    fun main(args: Array<String>) {
        val filter = args.firstOrNull { !it.startsWith("-") }

        val root = RealCorpus.scriptsRoot()
        val natives = RealCorpus.natives()
        if (root == null || natives == null) {
            println("=== vs tests: SKIPPED ===")
            println("  scripts tree: ${root?.absolutePath ?: "not found"}")
            println("  catalogue:    ${RealCorpus.catalogFile()?.absolutePath
                ?: "not found — start the client once, or run :vscript-core:vscriptManifest"}")
            return
        }

        val documents = RealCorpus.documents(root)
        val imports = TextSource.of(documents)
        // Only the refs a FILE actually sits at: `documents` also keys a folder barrel by its folder and a
        // document by its `graph` name, and running the same test file three times under three names would
        // treble every count.
        val testRefs = documents.keys
            .filter { ModuleNames.isTestRef(it) }
            .filter { filter == null || it.contains(filter) }
            .sorted()

        if (testRefs.isEmpty()) {
            println("=== vs tests: none found ===")
            println("  looked under ${root.absolutePath} for documents ending in '${ModuleNames.TEST_SUFFIX}'")
            return
        }

        val outcomes = ArrayList<TestOutcome>()
        var broken = 0
        for (ref in testRefs) {
            val compiled = TextFrontEnd(natives, imports = imports, rootRef = ref)
                .compileTests(documents.getValue(ref))
            if (!compiled.ok) {
                broken++
                println("FAIL  $ref - did not compile")
                compiled.errors.take(5).forEach { println("        ${it.span} ${it.message}") }
                continue
            }
            outcomes += VsTestRunner(::hosts).run(compiled).outcomes
        }

        val report = TestReport(outcomes)
        println(report)
        if (broken > 0) println("$broken document${if (broken == 1) "" else "s"} did not compile")
        if (!report.ok || broken > 0) exitProcess(1)
    }
}
