package dev.ziggle.vscript.model

/**
 * Which references a document answers to — the ONE statement of the rule, for every resolver.
 *
 * There were two, and they were meant to be identical. `DocumentSource.refresh` in the client decides what
 * an `import` finds at run time; `VsImports.namesOf` in the IntelliJ plugin decides what Ctrl+click finds
 * and what the editor's own compile sees. Both were kept in step by a comment asking the next person to
 * keep them in step, and both have been wrong about it — first the plugin resolved by PATH alone, so a
 * `core/entity.vs` declaring `graph "entities"` navigated in the editor and was missing at run time; then
 * the correction dropped paths entirely and `activities/farmrun/kit.vs` declaring `graph "farmkit"` ran
 * fine and could not be clicked through to. The same bug, twice, wearing each shoe once.
 *
 * So the rule lives here, as strings. No `File`, no `VirtualFile`, no scripts root — a caller has already
 * decided where the root is and what the document calls itself, and those two answers are the whole input.
 * That is what lets the language repo own it: naming a module is a module-system question, and this
 * module system is the one thing this repo is.
 *
 * ### The rule
 *
 * A document is named by **the folder it sits in, plus what its `graph` line calls it** — the folders are
 * the package and the `graph` line is the name, exactly as a class relates to its directory. The path
 * forms follow as fallbacks, for a document that never named itself.
 *
 * ### ...and the one exception: `mod.vs`
 *
 * **A file called `mod.vs` is its folder's front door, and answers to the FOLDER.** `core/loadout/mod.vs`
 * is imported as `core/loadout`. That is `index.ts`, and `mod.rs`, for their reason: a barrel's whole job
 * is to be the one name a caller has to know, and making that name `core/loadout/mod` would mean the
 * caller knows both the folder and the convention.
 *
 * **The bare name `mod` is never registered**, from the `graph` line or from the file name. It is the one
 * name every barrel in the tree would claim, and the registries are first-wins, so `import "mod"` would
 * silently resolve to whichever folder happened to be scanned first. `mod` is a marker, not a name.
 */
object ModuleNames {

    /** The file name that makes a document its folder's front door — without the extension. */
    const val BARREL = "mod"

    /**
     * What marks a document as the TESTS for another one — `scheduler/goal_test` tests `scheduler/goal`.
     *
     * **A suffix rather than a root, because a ref has to stay unique.** The obvious arrangement — a
     * `test/` tree mirroring `src/` — puts `test/scheduler/goal.vs` and `src/scheduler/goal.vs` in the
     * same package under the same name, and the index is first-wins, so one of them would silently shadow
     * the other and which one depended on the order the tree was walked. Java does not have this problem
     * because `FooTest` is a different class name; Go says the same thing with `_test.go`. This is that,
     * and it costs nothing: the two files can still live in different roots, which is what multi-root
     * lookup is for — they simply cannot claim one name.
     *
     * Kept here with the rest of the naming rule so the client's index, the IDE's and the test runner's
     * cannot disagree about which documents are tests.
     */
    const val TEST_SUFFIX = "_test"

    /** Whether [ref] is a test document — `scheduler/goal_test` is, `scheduler/goal` is not. */
    @JvmStatic
    fun isTestRef(ref: String): Boolean = ref.endsWith(TEST_SUFFIX) && ref.length > TEST_SUFFIX.length

    /** What [ref] tests, or null when it is not a test document. */
    @JvmStatic
    fun moduleUnderTest(ref: String): String? =
        if (isTestRef(ref)) ref.dropLast(TEST_SUFFIX.length) else null

    /**
     * Whether [importer] is allowed to see [imported]'s un-exported names.
     *
     * **Only its own subject, and only one hop.** `scheduler/goal_test` may reach into `scheduler/goal`
     * and into nothing else — not the whole tree, and not another module's internals through a shared
     * test root. That is what `src/test` means in Java and what a friend declaration means in C++: the
     * ability to test a thing without first making its internals public, which is the alternative and is
     * how a codebase ends up exporting everything.
     */
    @JvmStatic
    fun maySeeInternals(importer: String, imported: String): Boolean =
        moduleUnderTest(importer) == imported

    /** Whether [path] is a folder's barrel: `core/loadout/mod` is, `core/loadout` is not. */
    @JvmStatic
    fun isBarrel(path: String): Boolean = fileName(path) == BARREL

    /** The folder [path] sits in below the root — `core/loadout` for `core/loadout/mod`, `""` at the top. */
    @JvmStatic
    fun folderOf(path: String): String = path.substringBeforeLast('/', "")

    /** The last segment of [path], which is the file's own name with its extension already dropped. */
    @JvmStatic
    fun fileName(path: String): String = path.substringAfterLast('/')

    /**
     * The folder [path] is the front door of, or null when it is not a barrel (or is one at the top level,
     * where there is no folder to name it after).
     *
     * Separate from [namesOf] because the caller needs it separately: this name must be registered in a
     * LATER pass than the ordinary ones, so that an explicit `core/loadout.vs` beside the folder keeps the
     * reference and `core/loadout/mod.vs` does not take it out from under a file that spells it exactly.
     * Registering both in one pass would hand the name to whichever the directory walk reached first,
     * which is alphabetical order and therefore an answer nobody chose.
     */
    @JvmStatic
    fun barrelName(path: String): String? =
        if (isBarrel(path)) folderOf(path).takeIf { it.isNotEmpty() } else null

    /**
     * Every reference [path] answers to, most specific first, EXCLUDING the barrel name.
     *
     * [path] is the document's location below the scripts root, forward slashes, extension already
     * dropped — `core/loadout/wants`. [declared] is what its own `graph` line calls it, or null for a
     * document that has none (or one too broken to parse, which is the same thing to a caller).
     *
     * The order is the whole content of the rule: the declared names first, so a `util/whatever.vs`
     * declaring `graph "banks"` IS `util/banks` and renaming the file changes nothing; the path forms
     * after, so a document that never named itself is still importable by where it sits.
     */
    @JvmStatic
    fun namesOf(path: String, declared: String?): List<String> {
        val folder = folderOf(path)
        val out = LinkedHashSet<String>()
        if (declared != null && declared.isNotBlank()) {
            out += if (folder.isEmpty()) declared else "$folder/$declared"
            out += declared
        }
        out += path
        out += fileName(path)
        // See the class note: `mod` is a marker, not a name. It is dropped here rather than at each of the
        // two places it can arrive from, because both of them are ways of saying the same wrong thing.
        out -= BARREL
        return out.toList()
    }
}
