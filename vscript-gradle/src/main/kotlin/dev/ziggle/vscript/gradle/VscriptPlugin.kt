package dev.ziggle.vscript.gradle

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.JavaExec
import javax.inject.Inject

/** One `.vspack` to build. The container's name is the pack's id unless [id] says otherwise. */
abstract class PackSpec @Inject constructor(val name: String) {
    /** Stable identity across versions. Defaults to the block's name. */
    abstract val id: Property<String>
    /** The document to build FROM, as its import reference — `activities/errand/mod`. */
    abstract val entry: Property<String>
    /** The pack's own version. Defaults to the project's. */
    abstract val version: Property<String>
}

/**
 * `vscript { }` — what a consumer configures.
 *
 * Every knob has a default that is right for the commonest case, so a scripts repo can apply the plugin,
 * name one pack, and be done.
 */
abstract class VscriptExtension @Inject constructor(project: Project) {

    /** The language version to depend on. Defaults to the plugin's own — the one it was tested against. */
    abstract val version: Property<String>

    /**
     * Add the repository releases are served from.
     *
     * That repo is PUBLIC, so this needs no credentials — which is the entire reason a consumer can apply
     * this plugin and immediately build. Turn it off if you resolve the language some other way (a
     * composite build, a vendored jar, an internal mirror).
     */
    abstract val addRepository: Property<Boolean>

    /** Add `vscript-runtime` — the scheduler, the phase machine, `runPack`. A host that RUNS scripts. */
    abstract val runtime: Property<Boolean>

    /** Add `vscript-shell` and the editors. Brings [VscriptPlugin.EDITOR_TASK] with it. */
    abstract val editor: Property<Boolean>

    /**
     * The import root — the folder references resolve against.
     *
     * `src` by default, which is the convention the language's own `scriptsRootOf` walks up to find, so a
     * document means the same thing to this build and to a client that opened it off disk.
     */
    abstract val scriptsRoot: DirectoryProperty

    /**
     * A `CatalogProvider` implementation, by fully-qualified name.
     *
     * **Required for check and pack, and deliberately not defaulted.** The language ships builtins only;
     * every real verb belongs to a host, so there is no catalogue this plugin could reasonably guess. Put
     * the library holding it on the `vscriptTool` configuration.
     */
    abstract val catalogProvider: Property<String>

    /** Keep the metadata only an editor and a debugger read. Off by default — a shipped pack is stripped. */
    abstract val keepDebugInfo: Property<Boolean>

    /**
     * References to resolve to an empty document.
     *
     * For a corpus carrying a dead import that no file answers: every document importing it otherwise
     * fails for a reason that has nothing to do with the document. Narrow and knowing — a stub hides a
     * genuinely missing file just as well as a dead one.
     */
    abstract val stubs: org.gradle.api.provider.ListProperty<String>

    /** The packs to build. `packs { errands { entry = "activities/errand/mod" } }`. */
    val packs: NamedDomainObjectContainer<PackSpec> = project.objects.domainObjectContainer(PackSpec::class.java)

    fun packs(action: org.gradle.api.Action<NamedDomainObjectContainer<PackSpec>>) = action.execute(packs)
}

/**
 * The `dev.ziggle.vscript` plugin.
 *
 * Wires the repository and the language dependencies, and adds the tasks a document tree needs:
 *
 *  - `vscriptCheck` — compile every declared pack (or the whole tree when none are declared) and fail on
 *    the first document that does not. The gate a scripts repo has otherwise never had.
 *  - `vscriptPack`  — write a `.vspack` per declared pack into `build/vspacks`.
 *  - `vscriptEditor`— open the standalone editor on the scripts root.
 *
 * Check and pack run in their own JVM against the `vscriptTool` classpath. Compiling needs the HOST's
 * catalogue, and loading a consumer's dependency graph into the Gradle daemon is how a build eventually
 * collides with something Gradle already has on its own classloader.
 */
class VscriptPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val ext = project.extensions.create("vscript", VscriptExtension::class.java, project)
        ext.version.convention(VSCRIPT_VERSION)
        ext.addRepository.convention(true)
        ext.runtime.convention(false)
        ext.editor.convention(false)
        ext.keepDebugInfo.convention(false)
        ext.scriptsRoot.convention(project.layout.projectDirectory.dir("src"))

        // The classpath the worker runs on: the language, plus whatever host library the consumer adds.
        val tool = project.configurations.create(TOOL_CONFIGURATION) {
            it.isCanBeConsumed = false
            it.isCanBeResolved = true
            it.description = "Classpath for vscriptCheck/vscriptPack — the language plus the host's catalogue."
        }
        val editorRuntime = project.configurations.create(EDITOR_CONFIGURATION) {
            it.isCanBeConsumed = false
            it.isCanBeResolved = true
            it.description = "Classpath for vscriptEditor."
        }

        // Repositories and dependencies are decided after the build script has run, so `vscript { }` can
        // set the version and the feature flags in any order relative to `dependencies { }`.
        project.afterEvaluate {
            val v = ext.version.get()
            if (ext.addRepository.get()) {
                // Maven Central FIRST, and not as a courtesy: the language's own transitives live there
                // (kotlin-stdlib), and a documents-only project declares no repositories at all — it
                // applies no JVM language plugin, so nothing has ever added one for it. Serving only our
                // own repo means resolution fails on someone else's artifact, from a URL that is ours,
                // which reads as "the vscript repo is broken".
                if (project.repositories.findByName(CENTRAL) == null) project.repositories.mavenCentral()
                project.repositories.maven { r ->
                    r.name = "vscript"
                    r.setUrl(RELEASES)
                    // No credentials: the repository is public, which is what makes this one line enough.
                }
            }

            val deps = project.dependencies
            // The language itself always — a consumer that applied this plugin wants vs.
            project.configurations.findByName("implementation")?.let { impl ->
                deps.add(impl.name, "dev.ziggle:vscript:$v")
                if (ext.runtime.get()) deps.add(impl.name, "dev.ziggle:vscript-runtime:$v")
                if (ext.editor.get()) deps.add(impl.name, "dev.ziggle:vscript-shell:$v")
            }
            // The worker needs the language whether or not the project has an `implementation` (a pure
            // documents repo applies no JVM language plugin at all, so it has none).
            deps.add(tool.name, "dev.ziggle:vscript:$v")
            // gson is compileOnly in the language, so a consumer supplies it — including this worker,
            // which reads and writes pack manifests.
            deps.add(tool.name, "com.google.code.gson:gson:$GSON")
            if (ext.editor.get()) deps.add(editorRuntime.name, "dev.ziggle:vscript-shell:$v")
        }

        project.tasks.register(CHECK_TASK, JavaExec::class.java) { t ->
            t.group = GROUP
            t.description = "Compile the vs documents and fail on the first that does not."
            t.classpath = tool
            t.mainClass.set(WORKER)
            t.argumentProviders.add { workerArgs(project, ext, out = null) }
        }

        project.tasks.register(PACK_TASK, JavaExec::class.java) { t ->
            t.group = GROUP
            t.description = "Compile the declared packs into build/vspacks/*.vspack."
            t.classpath = tool
            t.mainClass.set(WORKER)
            t.outputs.dir(project.layout.buildDirectory.dir(OUT_DIR))
            t.argumentProviders.add {
                workerArgs(project, ext, out = project.layout.buildDirectory.dir(OUT_DIR).get().asFile.path)
            }
        }

        project.tasks.register(EDITOR_TASK, JavaExec::class.java) { t ->
            t.group = GROUP
            t.description = "Open the standalone vs editor on the scripts root."
            t.classpath = editorRuntime
            t.mainClass.set(EDITOR_MAIN)
            t.argumentProviders.add { listOf(ext.scriptsRoot.get().asFile.absolutePath) }
            t.onlyIf {
                ext.editor.get().also { on ->
                    if (!on) project.logger.lifecycle("[vscript] set `vscript { editor = true }` to use $EDITOR_TASK")
                }
            }
        }

        // A documents repo has no `check` unless something made one; wire into it when it exists, so
        // `./gradlew check` gates the tree like it gates anything else.
        project.tasks.matching { it.name == "check" }.configureEach { it.dependsOn(CHECK_TASK) }
    }

    private fun workerArgs(project: Project, ext: VscriptExtension, out: String?): List<String> {
        val args = ArrayList<String>()
        args += listOf("--root", ext.scriptsRoot.get().asFile.absolutePath)
        args += listOf(
            "--provider",
            ext.catalogProvider.orNull ?: error(
                "vscript { catalogProvider = \"…\" } is required for $CHECK_TASK and $PACK_TASK.\n" +
                    "  It names a dev.ziggle.vscript.tools.CatalogProvider on the '$TOOL_CONFIGURATION' " +
                    "configuration — the language has builtins only, so the host's verbs have to come " +
                    "from somewhere this plugin cannot guess."
            ),
        )
        out?.let { args += listOf("--out", it) }
        if (ext.keepDebugInfo.get()) args += "--keep-debug"
        ext.stubs.getOrElse(emptyList()).forEach { args += listOf("--stub", it) }
        ext.packs.forEach { p ->
            val id = p.id.getOrElse(p.name)
            val version = p.version.getOrElse(project.version.toString())
            val entry = p.entry.orNull ?: error("vscript pack '${p.name}' has no `entry`")
            args += listOf("--pack", "$id:$version:$entry")
        }
        return args
    }

    companion object {
        const val GROUP = "vscript"
        const val TOOL_CONFIGURATION = "vscriptTool"
        const val EDITOR_CONFIGURATION = "vscriptEditorRuntime"
        const val CHECK_TASK = "vscriptCheck"
        const val PACK_TASK = "vscriptPack"
        const val EDITOR_TASK = "vscriptEditor"
        const val OUT_DIR = "vspacks"
        const val WORKER = "dev.ziggle.vscript.tools.PackMain"
        const val EDITOR_MAIN = "dev.ziggle.vscript.shell.Main"
        const val RELEASES = "https://raw.githubusercontent.com/ziggle-dev/vscript/maven/"
        const val GSON = "2.8.5"
        /** The name Gradle gives `mavenCentral()`, which is how we tell it is already there. */
        const val CENTRAL = "MavenRepo"
    }
}
