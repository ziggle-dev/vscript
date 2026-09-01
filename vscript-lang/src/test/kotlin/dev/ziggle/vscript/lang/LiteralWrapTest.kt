package dev.ziggle.vscript.lang

import dev.ziggle.vscript.model.NodeCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A long list or record literal is printed across lines, not as one enormous one.
 *
 * A canonical form has to be readable or nobody keeps it. Six obstacles comma-joined is a
 * six-hundred-character line that no reviewer can diff and no author would have typed — and it is what
 * `graph export` and every canvas-to-text write produced, not just the editor.
 */
class LiteralWrapTest {

    private val catalog = NodeCatalog(dev.ziggle.vscript.domain.TileFixture.DESCRIPTORS)

    private fun printed(text: String): String {
        val vs = VsText(catalog)
        val read = vs.read(text)
        val graph = assertNotNull(read.graph, "should compile: ${read.errors.map { it.message }}")
        return vs.write(graph, read.comments).trim()
    }

    @Test
    fun `a long list of records is one record per line`() {
        val out = printed(
            """
            graph "probe"

            export type Step { at: Tile, obstacle: STRING }

            export var Steps: LIST<Step> = [
                Step { at: tile(3103, 3279, 0), obstacle: "Rough wall" },
                Step { at: tile(3098, 3277, 3), obstacle: "Tightrope" },
                Step { at: tile(3092, 3276, 3), obstacle: "Narrow wall" },
            ]
            """.trimIndent(),
        )
        assertEquals(
            """
            graph "probe"

            export type Step { at: Tile, obstacle: STRING }

            export var Steps: LIST<Step> = [
                Step { at: tile(3103, 3279, 0), obstacle: "Rough wall" },
                Step { at: tile(3098, 3277, 3), obstacle: "Tightrope" },
                Step { at: tile(3092, 3276, 3), obstacle: "Narrow wall" },
            ]

            on start {
            }
            """.trimIndent(),
            out,
        )
    }

    /** Short ones stay inline — breaking them would be the noisier answer. */
    @Test
    fun `a short literal stays on one line`() {
        val text = """
            graph "probe"

            export type Point { x: INT, y: INT }

            export var P: Point = Point { x: 1, y: 2 }
            export var Xs: LIST<INT> = [1, 2, 3]

            on start {
            }
        """.trimIndent()
        assertEquals(text, printed(text))
    }

    /** And it is a fixpoint: printing the wrapped form back gives the same wrapped form. */
    @Test
    fun `wrapping is stable`() {
        val once = printed(
            """
            graph "probe"

            export type Step { at: Tile, obstacle: STRING }

            export var Steps: LIST<Step> = [
                Step { at: tile(3103, 3279, 0), obstacle: "Rough wall" },
                Step { at: tile(3098, 3277, 3), obstacle: "Tightrope" },
                Step { at: tile(3092, 3276, 3), obstacle: "Narrow wall" },
            ]
            """.trimIndent(),
        )
        assertTrue(once.contains("\n    Step {"), "expected one record per line, got:\n$once")
        assertEquals(once, printed(once), "printing the wrapped form changed it")
    }
}
