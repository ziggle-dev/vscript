package dev.ziggle.vscript.shell

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * The workbench knows no game.
 *
 * ### Why this rule survived the move and its siblings did not
 *
 * Most of the boundary rules this repo used to assert are enforced by Gradle now: a module cannot depend
 * on what it does not declare. This one is not, because the danger is not a module dependency — it is one
 * import, of one class, from a domain pack a consumer happens to have on the classpath. Nothing about the
 * build would object.
 *
 * And it is the rule that took longest to earn. `ScriptsPanel` named this client's node library, its file
 * sandbox, its value converter, its run lifecycle and its catalogue dump. It was the last thing in the
 * editor that knew what game it was editing, and therefore the last thing that could not be lifted out.
 * They arrive as a `ScriptDomain` now, supplied by whoever opens the editor.
 *
 * ### What it protects
 *
 * The module's own gate: **the shell opens a `.vs` and a `.json` with no game attached.** `ScriptDomain`
 * has a `NONE` that is a real editor with an empty catalogue rather than a stub, and that only stays true
 * while nothing here reaches past it.
 */
class DomainFreeTest {

    /** A domain pack, an engine, a game. Any of them here means the seam has been gone around. */
    private val DOMAIN = Regex(
        """\b(dev\.ziggle\.(api|nodes|methods|game|walking|interact|ids|quest|sailing|camera)\b)""",
    )

    @Test
    fun `nothing in the shell names a game or an engine`() {
        val offenders = sources().mapNotNull { (f, body) ->
            DOMAIN.find(body)?.let { f.name to it.value }
        }
        assertTrue(
            offenders.isEmpty(),
            "the workbench must open against a domain it is GIVEN, not one it names: $offenders",
        )
    }

    /** Comments stripped: half the surviving mentions are prose explaining why something used to be here. */
    private fun sources(): List<Pair<File, String>> {
        val root = sourceRoot()
        check(root.isDirectory) { "no sources at $root" }
        return root.walkTopDown().filter { it.extension == "kt" }
            .map { it to it.readText().stripComments() }
            .toList()
    }

    private fun String.stripComments(): String =
        replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("""//[^\n]*"""), "")

    private fun sourceRoot(): File {
        var d: File? = File(System.getProperty("user.dir"))
        while (d != null) {
            File(d, "vscript-shell/src/main/kotlin").takeIf { it.isDirectory }?.let { return it }
            File(d, "src/main/kotlin").takeIf { it.isDirectory }?.let { return it }
            d = d.parentFile
        }
        error("could not find vscript-shell sources from ${System.getProperty("user.dir")}")
    }
}
