package dev.ziggle.vscript.shell

import dev.ziggle.vscript.editor.ScriptDomain
import dev.ziggle.vscript.editor.ScriptsPanel
import dev.ziggle.vscript.host.FileStore
import dev.ziggle.vscript.nodes.OutputConverter
import dev.ziggle.vscript.runtime.RunLifecycle
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.GraphDoc
import dev.ziggle.vscript.model.NodeDescriptor
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.Types
import dev.ziggle.vscript.nodes.NodeDef
import dev.ziggle.vscript.nodes.NodeLibrary
import dev.ziggle.vscript.runtime.EditorDoc
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The gate: the shell opens a `.vs` and a `.json`, with no game attached.**
 *
 * ### Why this is the test the whole phase was for
 *
 * Everything else that says the editor is domain-free says it about the SOURCE. The module graph says no
 * module here declares a dependency on a game; [DomainFreeTest] says no file here names one. Both are
 * strong and both are static, and a thing can typecheck against no domain and still reach for one the
 * moment it is asked to do work — a lazy field, a static initialiser, a default that resolves to the
 * client's home directory. The only way to find that out is to open a document and see.
 *
 * So this constructs the real [ScriptsPanel] against [ScriptDomain.NONE] and puts a document through it,
 * once per authoring surface. Nothing is mocked. There is no game, no client, no window, and no ImGui
 * frame — which is also what makes it a CI test rather than something a person has to watch.
 *
 * ### What "no game attached" is allowed to mean
 *
 * An empty catalogue, and nothing else. A document that uses only the language's own nodes must open,
 * validate and compile exactly as it does in the client; one that calls a game verb must fail as an
 * UNKNOWN NODE, which is a document being wrong about this domain rather than the editor being broken.
 * The line between those two is the whole value of `NONE` being a real editor instead of a stub, so it is
 * asserted in both directions below.
 */
class ShellGateTest {

    private lateinit var workspace: File

    /**
     * A workspace of its own, and this is not tidiness.
     *
     * [EditorDoc.graphsDir] defaulted to `~/.ziggle/graphs` — the developer's real documents, on the machine
     * running the test — and the panel writes there on save. A test that opens a workbench pointed at a
     * folder it did not create is one bad assertion away from deleting somebody's scripts.
     */
    @BeforeEach
    fun setUp() {
        workspace = File.createTempFile("vs-shell-gate", "").let { it.delete(); it.mkdirs(); it }
        EditorDoc.workspace = workspace
    }

    @AfterEach
    fun tearDown() {
        EditorDoc.workspace = null
        workspace.deleteRecursively()
    }

    // ---- the two surfaces ---------------------------------------------------------------------------

    @Test
    fun `it opens a json graph with no game attached`() {
        val panel = ScriptsPanel(ScriptDomain.NONE)

        // Written by the same codec the client saves with, so this is a round trip and not a fixture that
        // could drift away from what the editor actually produces.
        val doc = EditorDoc.blank("gate")
        doc.addNode(BuiltinNodes.LOG, 240f, 120f)
        val file = File(workspace, "gate.json")
        GraphDoc.write(doc.toGraph(), file)

        panel.openDocument(file)

        val opened = assertNotNull(panel.doc, "the panel opened no document")
        assertEquals("gate", opened.name)
        assertTrue(opened.nodes.size >= 2, "the graph came back with ${opened.nodes.size} nodes")
    }

    @Test
    fun `it opens a vs source with no game attached`() {
        val panel = ScriptsPanel(ScriptDomain.NONE)
        val file = File(workspace, "gate.vs")
        val source = """
            graph "gate"

            on start {
                log("hello from a shell with no game")
            }
        """.trimIndent()
        file.writeText(source)

        // debug = false: the gate is that it COMPILES, and arming a debug session is a run-time concern
        // with a live runtime behind it.
        val script = panel.compileText(file, source, debug = false)

        // **Assert on the DIAGNOSTICS, not on the absence of an exception.** The text front end reports
        // every failure as a `TextDiagnostic` and throws for none of them, so "it did not throw" is a
        // sentence about nothing: it passes for a file of pure gibberish. This test said exactly that
        // until the pair below caught it out.
        assertTrue(
            script.compiled.ok,
            "a .vs using only the language did not compile: ${script.compiled.errors.joinToString { it.message }}",
        )
        assertTrue(script.compiled.entries.isNotEmpty(), "it compiled with no errors and no entry to run")
    }

    // ---- what an empty catalogue is, and is not ------------------------------------------------------

    @Test
    fun `the catalogue is empty, and the language is still all there`() {
        val panel = ScriptsPanel(ScriptDomain.NONE)

        // No domain contributes verbs...
        val names = panel.catalog.all.map { it.type }
        assertTrue(names.none { it.startsWith("game.") }, "a game verb reached an editor with no game: $names")

        // ...and every builtin is present regardless, because those belong to the LANGUAGE. This is the
        // distinction `NONE` exists to make: an editor with nothing to call is not an editor that cannot
        // do anything.
        assertNotNull(panel.catalog[BuiltinNodes.ENTRY], "the language's own Start node is missing")
        assertNotNull(panel.catalog[BuiltinNodes.LOG], "the language's own Log node is missing")
    }

    /**
     * A domain verb, against the two domains, and the ONLY difference is the domain.
     *
     * Read as a pair. Alone, the refusal below proves only that something went wrong, and the likeliest
     * something is a typo in the fixture; alone, the acceptance proves only that a node can be registered.
     * The same six lines of script through both says the thing worth saying — that a document's fate is
     * decided by the domain it was opened against, and by nothing the editor is holding on to.
     */
    @Test
    fun `a verb the domain does not have is refused, as a fact about the document`() {
        val panel = ScriptsPanel(ScriptDomain.NONE)
        val file = File(workspace, "needs-a-domain.vs")
        file.writeText(WAVES)

        val script = panel.compileText(file, WAVES, debug = false)

        assertTrue(!script.compiled.ok, "a verb no domain declares compiled anyway")
        // The failure has to be a sentence about the DOCUMENT — the editor got far enough to read the file
        // and form an opinion about it. A crash instead (a NoClassDefFoundError, an NPE out of a lazy
        // field) is exactly the reach past the seam that the static checks cannot see, and it would show
        // up here as this assertion never being reached.
        assertTrue(
            script.compiled.errors.any { it.message.isNotBlank() },
            "it refused the document without saying why, which nobody editing it can act on",
        )
    }

    @Test
    fun `the same document compiles once a domain declares the verb`() {
        val panel = ScriptsPanel(TinyDomain)
        val file = File(workspace, "has-a-domain.vs")
        file.writeText(WAVES)

        val script = panel.compileText(file, WAVES, debug = false)

        assertTrue(
            script.compiled.ok,
            "a verb the domain declares did not compile: ${script.compiled.errors.joinToString { it.message }}",
        )
        assertNotNull(panel.catalog["pretend.wave"], "the domain's node never reached the catalogue")
    }

    private val WAVES = """
        graph "waves"

        on start {
            wave()
        }
    """.trimIndent()

    /**
     * One node, no game, no client: the smallest thing that is a domain at all.
     *
     * **`host` is what makes it callable from text, and omitting it fails silently.**
     * `NodeCatalog.natives()` skips every descriptor without one — deliberately, because `flow.delay` and
     * `logic.and` carry no host and are lowered by the compiler instead, so a signature with nothing
     * behind it would typecheck and then fail to emit. The consequence for a domain author is that a node
     * declared without a host is invisible to `.vs` while appearing perfectly normal on the canvas, and
     * the error it produces names the CALL — `no function called 'wave'` — rather than the declaration.
     * Which is how this test failed first time round.
     */
    private object TinyDomain : ScriptDomain {
        override fun library(): NodeLibrary = NodeLibrary(
            listOf(
                NodeDef(
                    NodeDescriptor(
                        type = "pretend.wave",
                        title = "Wave",
                        category = "Pretend",
                        kind = NodeKind.IMPURE,
                        host = "pretend.wave",
                    ),
                ) { _ -> null },
            ),
        )
    }

    // ---- the domain seam is not consulted when there is no domain ------------------------------------

    /**
     * The type PICKER offers nothing a domain has not declared.
     *
     * `model/Types.kt` used to register nine game types — `Item`, `Npc`, `Object`, `Tile`, `Color`,
     * `Skill`, `EntityRef`, `ItemRef`, `WidgetRef` — in the language itself, describing "what kind of
     * item — a shark, a rune scimitar". None was a `PinType`, so it never showed up as a type-system
     * leak; it showed up HERE, as an editor with no game attached offering nine types nothing on its
     * classpath could produce, and a variable you could declare and never fill.
     *
     * They belong to `dev.ziggle.nodes.GameRecords` now. This is the assertion that keeps them there: the
     * language may register only its own.
     */
    @Test
    fun `the type picker offers only the language's own types`() {
        ScriptsPanel(ScriptDomain.NONE)

        val offered = Types.all.map { it.name }.toSet()
        val game = setOf("Item", "Npc", "Object", "Tile", "Color", "Skill", "EntityRef", "ItemRef", "WidgetRef")
        assertTrue(
            (offered intersect game).isEmpty(),
            "an editor with no domain offers game types it cannot produce: ${offered intersect game}",
        )
        // ...and the language's own are all still there, because the point was never to shorten the list.
        listOf("Int", "Float", "String", "Bool", "List", "Map").forEach {
            assertTrue(it in offered, "the language lost its own '$it'")
        }
    }

    @Test
    fun `no domain refuses, rather than leaving a null for somebody to trip over`() {
        // Every one of these could as easily have been a null nobody checked. They are defaults on the
        // interface instead, so a domain that answers only `library()` -- which is most of them -- gets a
        // coherent editor rather than four holes.
        val none = ScriptDomain.NONE
        assertNull(none.actuator, "an editor with no game handed out an actuator")
        assertEquals(FileStore.DENIED, none.files, "a script with no domain was given file access")
        assertEquals(OutputConverter.NONE, none.valueOut)
        assertEquals(RunLifecycle.NONE, none.lifecycle)
        assertTrue(none.library().descriptors.isEmpty(), "NONE contributed verbs")
    }
}
