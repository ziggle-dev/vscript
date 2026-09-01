package dev.ziggle.vscript.host

/**
 * The files a script may read and write.
 *
 * **A seam for the same reason [Clock] is one.** This module is the language, and the language has no
 * business knowing where a host keeps a script's data — `~/.ziggle`, a temp directory, a map in a test. One
 * interface is the whole of what the JSON verbs need from outside, so depending on `java.nio` directly
 * would have hard-wired a policy (which directory? may a script escape it?) into the compiler's module.
 *
 * **A path here is relative and opaque.** Implementations decide what it resolves against and are expected
 * to refuse anything that leaves their root — see [dev.ziggle.vscript.host.MemoryFiles] for the shape and the
 * client's `ScriptFiles` for the sandboxed one. That check cannot live here: this interface does not know
 * what a root is.
 *
 * Every method may throw. A throw stops the fiber on the node that failed with its inputs still readable,
 * which is the same bargain [dev.ziggle.vscript.nodes.action] makes and for the same reason — a boolean
 * nobody wires up is worth less than a stop at the point of failure.
 */
interface FileStore {

    /** The file's whole text, or **null** when there is no such file. Not an error: absence is a case. */
    fun read(path: String): String?

    /** Write [text], replacing whatever was there and creating parent directories as needed. */
    fun write(path: String, text: String)

    /** Is there a file at [path]? */
    fun exists(path: String): Boolean

    /** Remove it. True when something was removed, false when there was nothing there. */
    fun delete(path: String): Boolean

    /** The file names directly inside [dir], sorted. Empty when there is no such directory. */
    fun list(dir: String): List<String>

    /**
     * The FOLDER names directly inside [dir], sorted. Empty when there is no such directory.
     *
     * Separate from [list] rather than mixed into it, because a caller almost always wants one or the
     * other and a single list of names with no way to tell them apart is the version of this that forces
     * every consumer to guess. Neither recurses: "what is in here" is one level, and a script that wants
     * the tree walks it, which it can now do.
     */
    fun folders(dir: String): List<String>

    companion object {
        /**
         * The default: no file access at all.
         *
         * Refusing rather than silently answering "no such file" — a script whose host never granted it
         * files should say so once, clearly, instead of behaving like every path it tries is empty.
         */
        val DENIED: FileStore = object : FileStore {
            private fun no(): Nothing =
                throw dev.ziggle.vscript.vm.VmError("this script host has no file access")

            override fun read(path: String) = no()
            override fun write(path: String, text: String) = no()
            override fun exists(path: String) = no()
            override fun delete(path: String) = no()
            override fun list(dir: String) = no()
            override fun folders(dir: String) = no()
        }
    }
}

/**
 * Files held in memory — what a test uses.
 *
 * Published here rather than in test fixtures because the *default* registry needs something to point at
 * that is neither the real disk nor a refusal, and because a host embedding the language for a dry run
 * wants exactly this.
 */
class MemoryFiles(initial: Map<String, String> = emptyMap()) : FileStore {

    private val files = LinkedHashMap<String, String>(initial)

    /** Every path currently held, in write order — for a test that wants to assert on the whole store. */
    val paths: List<String> get() = files.keys.toList()

    override fun read(path: String): String? = files[normalise(path)]

    override fun write(path: String, text: String) {
        files[normalise(path)] = text
    }

    override fun exists(path: String): Boolean = files.containsKey(normalise(path))

    override fun delete(path: String): Boolean = files.remove(normalise(path)) != null

    override fun list(dir: String): List<String> = within(dir).filterNot { it.contains('/') }.sorted()

    // Every path that continues past this level, cut at the next separator and deduplicated — a folder
    // "exists" here exactly when something is stored under it, there being no empty directories in a map.
    override fun folders(dir: String): List<String> =
        within(dir).filter { it.contains('/') }.map { it.substringBefore('/') }.distinct().sorted()

    /** The paths under [dir], each relative to it. */
    private fun within(dir: String): List<String> {
        val prefix = normalise(dir).trimEnd('/').let { if (it.isEmpty()) "" else "$it/" }
        return files.keys
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .filter { it.isNotEmpty() }
    }

    /** Separators one way, so a test may write `a/b` and read `a\b` the way the real store tolerates both. */
    private fun normalise(path: String): String = path.replace('\\', '/').trim().trimStart('/')
}
