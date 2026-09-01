package dev.ziggle.vscript.editor.text

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * The code view names nothing from the canvas. **This was `RatchetTest`, and it has reached zero.**
 *
 * ### What the ratchet was counting down
 *
 * The split plan is explicit that the two authoring surfaces must not depend on each other: they share
 * the registered node surface and the bytecode, and nothing else. This one did — `:editor-text` declared
 * `implementation project(':editor-graph')` — because the code view was built on the canvas's in-node
 * text field: caret, selection, word motion, layout, all written for typing a literal into a pin.
 *
 * `RatchetTest` bounded that borrowing to four names and forbade a fifth, so the list could only shrink,
 * and phase 6 was going to empty it by building the code view a text core of its own.
 *
 * ### What actually emptied it
 *
 * Measuring the four, not replacing them. `CanvasTextEdit.kt` imported ImGui and `Theme` and nothing
 * else; the ONLY thing that made it the canvas's was a **default argument** — `measure` fell back to
 * `CanvasRenderer.textWidth` — and the code view was already passing its own, because the canvas draws a
 * different font at a different size. A widget with no opinion about what is being edited belongs in the
 * widget kit, below both surfaces. It is `dev.ziggle.imgui.TextEdit` now, and both surfaces are consumers
 * of it rather than one being a consumer of the other.
 *
 * The same answer `PanelBits`, `EditorKeyboard` and `PanelField` got, and the same shape of answer phase
 * 5a kept getting: **almost nothing that looked like coupling was real coupling.**
 *
 * ### What this does NOT claim
 *
 * A real text core — a line index, a caret set, an edit journal, folding, markers, breakpoints addressed
 * by line — is still unbuilt. That is a FEATURE the code view wants, not a dependency it has, and the two
 * were tangled together in the plan because the feature was the route to the independence. The
 * independence arrived first and on its own; the feature is still worth building.
 */
class IndependentSurfacesTest {

    private val GRAPH_REF = Regex("""io\.ziggle\.vscript\.editor\.graph\.(\w+)""")

    @Test
    fun `the code view names nothing from the canvas`() {
        val borrowed = sources()
            .flatMap { GRAPH_REF.findAll(it).map { m -> m.groupValues[1] } }
            .toSortedSet()
        assertTrue(
            borrowed.isEmpty(),
            "the code view reaches into the canvas again: $borrowed. The two surfaces share the node " +
                "surface and the bytecode and nothing else; anything genuinely common goes DOWN into " +
                "`:vscript-ui`, the way the text field did.",
        )
    }

    /**
     * And the module does not declare the dependency either.
     *
     * Belt and braces, deliberately: the import rule above passes vacuously the moment somebody reaches
     * the canvas through a re-export or a `typealias`, and the build file is where the claim is cheapest
     * to check and hardest to fudge.
     */
    @Test
    fun `and the build file does not declare the canvas`() {
        val gradle = File(moduleRoot(), "build.gradle")
        check(gradle.isFile) { "no build.gradle at $gradle" }
        assertTrue(
            "editor-graph" !in gradle.readText().substringAfter("dependencies {"),
            "`:editor-text` declares `:editor-graph` again — see this test's note for why it must not.",
        )
    }

    /** Every source in this module, comments stripped so a doc reference is not a use. */
    private fun sources(): List<String> {
        val root = File(moduleRoot(), "src/main/kotlin")
        check(root.isDirectory) { "no sources at $root" }
        return root.walkTopDown().filter { it.extension == "kt" }
            .map { it.readText().stripComments() }
            .toList()
    }

    private fun String.stripComments(): String =
        replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("""//[^\n]*"""), "")

    private fun moduleRoot(): File {
        var d: File? = File(System.getProperty("user.dir"))
        while (d != null) {
            if (d.name == "editor-text" && File(d, "build.gradle").isFile) return d
            File(d, "editor-text/build.gradle").takeIf { it.isFile }?.let { return File(d, "editor-text") }
            d = d.parentFile
        }
        error("could not find the editor-text module from ${System.getProperty("user.dir")}")
    }
}
