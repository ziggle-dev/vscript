package dev.ziggle.vscript.tools

import dev.ziggle.vscript.text.TextFrontEnd
import dev.ziggle.vscript.text.VsPackFile
import dev.ziggle.vscript.text.natives
import java.io.File

/**
 * Compile documents, and optionally write `.vspack` files — the worker a build tool shells out to.
 *
 * **A separate process, on purpose.** Compiling needs the HOST's catalogue on the classpath — the game's
 * verbs, the domain's records — and a Gradle plugin cannot load those into the daemon without dragging a
 * consumer's dependency graph into Gradle's own classloader, where it will eventually collide with
 * something Gradle already has. Handing the whole job to a JVM whose classpath the build controls costs a
 * process launch and removes the entire class of problem.
 *
 * ```
 *   --root <dir>            an import root; repeatable, first wins on a collision
 *   --provider <fqcn>       a CatalogProvider implementation, loaded from this classpath
 *   --out <dir>             where to write .vspack files (omit to check only)
 *   --pack <id>:<ver>:<ref> a pack to build; repeatable
 *   --stub <ref>            resolve <ref> to an empty document; repeatable
 *   --keep-debug            do not strip editor/debugger metadata
 * ```
 *
 * Exit codes: 0 all good, 1 a document failed to compile, 2 the arguments or the provider were wrong.
 * Diagnostics go to stderr one per line, prefixed with the document, so a build log stays greppable.
 */
object PackMain {

    private class Pack(val id: String, val version: String, val entry: String)

    @JvmStatic
    fun main(args: Array<String>) {
        val roots = ArrayList<File>()
        val packs = ArrayList<Pack>()
        val stubs = ArrayList<String>()
        var provider: String? = null
        var out: File? = null
        var strip = true

        var i = 0
        while (i < args.size) {
            when (val a = args[i]) {
                "--root" -> roots += File(args[++i])
                "--provider" -> provider = args[++i]
                "--out" -> out = File(args[++i])
                "--stub" -> stubs += args[++i]
                "--keep-debug" -> strip = false
                "--pack" -> {
                    // `id:version:ref` — ref last because it is the only part that may contain a slash,
                    // and splitting with a limit keeps a ref like `activities/errand/mod` intact.
                    val parts = args[++i].split(":", limit = 3)
                    if (parts.size != 3) fail("--pack wants id:version:ref, got '${args[i]}'")
                    packs += Pack(parts[0], parts[1], parts[2])
                }
                else -> fail("unknown argument '$a'")
            }
            i++
        }

        if (roots.isEmpty()) fail("at least one --root is required")
        val catalog = loadCatalog(provider ?: fail("--provider is required"))

        val source = FolderSource(roots, stubs.associate { FolderSource.stub(it) })
        // Nothing declared means "check the whole tree" — the useful default for a `check` task, and the
        // only way a corpus with no packs yet gets any gate at all.
        val targets = packs.ifEmpty {
            source.refs.sorted().map { Pack(it, "0.0.0", it) }
        }

        var failed = 0
        out?.mkdirs()
        for (p in targets) {
            val text = source.load(p.entry)
            if (text == null) {
                System.err.println("${p.entry}: no document answers to this reference")
                failed++
                continue
            }
            val front = TextFrontEnd(catalog.natives(), imports = source, rootRef = p.entry)
            val compiled = front.compile(text)
            if (!compiled.ok) {
                compiled.errors.forEach { System.err.println("${p.entry}:${it.span.line}: ${it.message}") }
                failed++
                continue
            }
            if (out == null) {
                println("ok  ${p.entry}")
                continue
            }
            val bytes = VsPackFile.write(compiled, p.id, p.version, p.entry, strip)
            val file = File(out, "${p.id.replace('/', '-')}-${p.version}.vspack")
            file.writeBytes(bytes)
            val needs = VsPackFile.readInfo(bytes).requiredHosts.size
            println("packed ${p.id} ${p.version} -> ${file.name} (${bytes.size} bytes, $needs host verbs)")
        }

        if (failed > 0) {
            System.err.println("$failed of ${targets.size} document(s) failed to compile")
            kotlin.system.exitProcess(1)
        }
    }

    /**
     * The host's catalogue, by class name off this process's own classpath.
     *
     * Fails with what was tried rather than a bare ClassNotFoundException: the usual cause is a build that
     * named a provider but never put the library holding it on the tool's classpath, and the two are worth
     * telling apart.
     */
    private fun loadCatalog(fqcn: String) = try {
        val cls = Class.forName(fqcn)
        val instance = cls.getDeclaredConstructor().newInstance()
        (instance as? CatalogProvider ?: fail("$fqcn does not implement ${CatalogProvider::class.java.name}"))
            .catalog()
    } catch (e: ClassNotFoundException) {
        fail("catalog provider '$fqcn' is not on the tool classpath — is the host library a dependency?")
    } catch (e: NoSuchMethodException) {
        fail("catalog provider '$fqcn' needs a public no-argument constructor")
    }

    private fun fail(message: String): Nothing {
        System.err.println("vscript: $message")
        kotlin.system.exitProcess(2)
    }
}
