package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.compile.errors
import dev.ziggle.vscript.host.MemoryFiles
import dev.ziggle.vscript.json.Json
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeDescriptor
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reading and writing JSON, and the cast that turns a document into records.
 *
 * The claim under test is the design one: **`as` is the whole surface**. There is no decode node, no type
 * argument and no second rename syntax — the same `value.cast` that reads one record as another reads a
 * document as one, and which it does is decided by what feeds its Value pin. So most of these tests are
 * about the two sides of that fork behaving like one feature: the same "nothing is zero-filled" rule, the
 * same rename clause, the same round trip.
 */
class JsonTest {

    private val sayNode = NodeDescriptor(
        "test.say", "Say", "Test", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
        host = "say",
    )
    private val catalog = NodeCatalog(listOf(sayNode))

    private fun graphOf(src: String): dev.ziggle.vscript.model.Graph {
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse: ${parsed.errors.map { "${it.span} ${it.message}" }}")
        val low = Lower(catalog).lower(parsed.program)
        assertTrue(low.ok, "lower: ${low.errors.map { it.message }}")
        assertEquals(emptyList(), Validator(catalog).validate(low.graph).errors(), "did not validate")
        return low.graph
    }

    /** Run [src], returning everything it `say`s. [files] backs the file verbs. */
    private fun said(src: String, files: MemoryFiles = MemoryFiles()): List<Any?> {
        val g = graphOf(src)
        val out = ArrayList<Any?>()
        val hosts = BuiltinHosts.registry(files)
        hosts.register("say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        // A failed fiber is REPORTED, not swallowed. `drive` leaves the error on the fiber, so a decode
        // that stopped halfway would otherwise show up as "expected [a, b] but was []" — which says
        // nothing about the message these tests exist to check.
        val run = drive(
            GraphCompiler(catalog, debug = false).compile(g, g.entries(catalog).single().id),
            hosts, maxTicks = 2_000,
        )
        run.fiber.error?.let { throw it }
        return out.map { if (it is Number && it !is Double) it.toLong() else it }
    }

    /** The errors validation reports, for the cases that must be refused before the script runs. */
    private fun refused(src: String): List<String> {
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse: ${parsed.errors.map { it.message }}")
        val low = Lower(catalog).lower(parsed.program)
        if (!low.ok) return low.errors.map { it.message }
        return Validator(catalog).validate(low.graph).errors().map { it.message }
    }

    // ---- the codec on its own -------------------------------------------------------------------------

    @Test
    fun `a whole number parses as INT and a decimal one as FLOAT`() {
        // By SPELLING, not by value. `1.0` is a Float even though it is a round number, or a FLOAT field
        // would decode as an INT on whichever run its data happened to come out even.
        assertEquals(1, Json.parse("1"))
        assertEquals(1.0, Json.parse("1.0"))
        assertEquals(1.0, Json.parse("1e0"))
        // Past Int, still an INT — the language's integer is Long-wide.
        assertEquals(3_000_000_000L, Json.parse("3000000000"))
    }

    @Test
    fun `objects keep their key order`() {
        val m = Json.parse("""{"b": 1, "a": 2, "c": 3}""") as Map<*, *>
        assertEquals(listOf("b", "a", "c"), m.keys.toList())
    }

    @Test
    fun `text survives a round trip through both directions`() {
        val awkward = """{"k": "a \"quoted\" line\nand a tab\there"}"""
        assertEquals(Json.parse(awkward), Json.parse(Json.write(Json.parse(awkward))))
    }

    @Test
    fun `a truncated document says where it gave up`() {
        val e = runCatching { Json.parse("""{"a": [1, 2""") }.exceptionOrNull()
        assertTrue(e?.message.orEmpty().contains("ended inside"), "unhelpful: ${e?.message}")
    }

    // ---- decoding into records ------------------------------------------------------------------------

    private val layout = """
        graph "probe"

        type Item { id: INT, name: STRING, qty: INT }
        type Tab { tab: INT, title: STRING, items: LIST<Item> }
        type Doc { version: INT, tabs: LIST<Tab> }
    """.trimIndent()

    private val sample = """
        {"version": 2, "tabs": [
          {"tab": 0, "title": "Main", "items": [{"id": 995, "name": "Coins", "qty": 494533}]},
          {"tab": 1, "title": "Gear", "items": []}
        ]}
    """.trimIndent()

    @Test
    fun `fields read through at every depth`() {
        val out = said(
            """
            $layout

            on start {
                val doc = parseJson(text: ${quoted(sample)}) as Doc
                say(message: doc.version)
                say(message: _listCount(list: doc.tabs))
                say(message: doc.tabs[0].title)
                say(message: doc.tabs[0].items[0].name)
                say(message: doc.tabs[0].items[0].qty)
                say(message: _listCount(list: doc.tabs[1].items))
            }
            """.trimIndent(),
        )
        assertEquals(listOf(2L, 2L, "Main", "Coins", 494533L, 0L), out)
    }

    @Test
    fun `a decoded record is a real record — it compares and it is`() {
        val out = said(
            """
            $layout

            on start {
                val doc = parseJson(text: ${quoted(sample)}) as Doc
                val one = doc.tabs[0].items[0]
                say(message: one is Item)
                say(message: one == Item { id: 995, name: "Coins", qty: 494533 })
            }
            """.trimIndent(),
        )
        assertEquals(listOf(true, true), out)
    }

    @Test
    fun `extra keys are dropped, exactly as a record cast drops extra fields`() {
        val out = said(
            """
            graph "probe"

            type Small { id: INT }

            on start {
                val s = parseJson(text: ${quoted("""{"id": 7, "name": "x", "extra": [1,2,3]}""")}) as Small
                say(message: s.id)
            }
            """.trimIndent(),
        )
        assertEquals(listOf(7L), out)
    }

    @Test
    fun `a missing key is a stop that names the path and the cure`() {
        val e = runCatching {
            said(
                """
                $layout

                on start {
                    val doc = parseJson(text: ${quoted("""{"version": 1, "tabs": [{"tab": 0, "items": []}]}""")}) as Doc
                }
                """.trimIndent(),
            )
        }.exceptionOrNull()
        val m = e?.message.orEmpty()
        // Nothing is zero-filled — the rule the record cast already states — so the absence is named,
        // with the path to it and the declaration that would make it legal.
        assertTrue(m.contains("tabs[0]"), "no path: $m")
        assertTrue(m.contains("title"), "no field: $m")
        assertTrue(m.contains("STRING?"), "no cure: $m")
    }

    @Test
    fun `an optional field takes a missing key and an explicit null`() {
        val src = { doc: String ->
            """
            graph "probe"

            type Row { id: INT, note: STRING? }

            on start {
                val r = parseJson(text: ${quoted(doc)}) as Row
                say(message: r.note ?: "none")
            }
            """.trimIndent()
        }
        assertEquals(listOf("none"), said(src("""{"id": 1}""")))
        assertEquals(listOf("none"), said(src("""{"id": 1, "note": null}""")))
        assertEquals(listOf("here"), said(src("""{"id": 1, "note": "here"}""")))
    }

    @Test
    fun `the wrong kind of value is a stop that names the path`() {
        val e = runCatching {
            said(
                """
                graph "probe"

                type Row { id: INT }

                on start {
                    val r = parseJson(text: ${quoted("""{"id": "nope"}""")}) as Row
                }
                """.trimIndent(),
            )
        }.exceptionOrNull()
        val m = e?.message.orEmpty()
        assertTrue(m.contains("id"), "no path: $m")
        assertTrue(m.contains("nope"), "does not show the value: $m")
    }

    @Test
    fun `a whole-valued decimal fills an INT, and a fractional one is refused`() {
        assertEquals(
            listOf(3L),
            said(
                """
                graph "probe"
                type Row { n: INT }
                on start { say(message: (parseJson(text: ${quoted("""{"n": 3.0}""")}) as Row).n) }
                """.trimIndent(),
            ),
        )
        val e = runCatching {
            said(
                """
                graph "probe"
                type Row { n: INT }
                on start { say(message: (parseJson(text: ${quoted("""{"n": 3.5}""")}) as Row).n) }
                """.trimIndent(),
            )
        }.exceptionOrNull()
        assertTrue(e?.message.orEmpty().contains("whole number"), "unhelpful: ${e?.message}")
    }

    @Test
    fun `a record that mentions itself is refused by the language, before JSON is involved`() {
        // Pinned rather than assumed. The schema holds records in a side table keyed by NAME precisely so
        // a self-reference is a finite constant — but the language refuses such a record outright, so that
        // guard can never fire from a document today. If this rule is ever relaxed, this test goes red and
        // sends whoever relaxed it to check that decoding one actually works.
        val issues = refused(
            """
            graph "probe"

            type Node { name: STRING, kids: LIST<Node> }

            on start {
                val n = parseJson(text: "{}") as Node
            }
            """.trimIndent(),
        )
        assertTrue(issues.any { it.contains("contains itself") }, "expected the language to refuse it: $issues")
    }

    @Test
    fun `a declared type wins over the built-in kind of the same name`() {
        // `TypeRef.named` interns against the `PinType` constants, so a record called `Item` arrives at
        // the schema builder carrying `builtin = TypeRef.named("Item")`. Reading that first made every field of
        // such a record decode as a number. The `layout` fixture above is named `Item` for this reason;
        // this test says so out loud.
        val out = said(
            """
            graph "probe"

            type Item { id: INT, name: STRING }
            type Npc { id: INT }
            type Skill { name: STRING }

            on start {
                val i = parseJson(text: ${quoted("""{"id": 1, "name": "Coins"}""")}) as Item
                val n = parseJson(text: ${quoted("""{"id": 2}""")}) as Npc
                val s = parseJson(text: ${quoted("""{"name": "Agility"}""")}) as Skill
                say(message: i.name)
                say(message: n.id)
                say(message: s.name)
            }
            """.trimIndent(),
        )
        assertEquals(listOf("Coins", 2L, "Agility"), out)
    }

    @Test
    fun `a rename reads a JSON key that is not a legal field name`() {
        val out = said(
            """
            graph "probe"

            type Row { itemCount: INT, label: STRING }

            on start {
                val r = parseJson(text: ${quoted("""{"item count": 4, "label": "x"}""")}) as Row { itemCount: "item count" }
                say(message: r.itemCount)
            }
            """.trimIndent(),
        )
        assertEquals(listOf(4L), out)
    }

    @Test
    fun `a type with no JSON form is refused before the script runs`() {
        // The check IS the schema build, so there is no second list of readable types to drift from what
        // the decoder accepts.
        val issues = refused(
            """
            graph "probe"

            type Row { who: ENTITY }

            on start {
                val r = parseJson(text: "{}") as Row
            }
            """.trimIndent(),
        )
        assertTrue(issues.any { it.contains("cannot be read from JSON") }, "not refused: $issues")
    }

    @Test
    fun `a rename naming no field of the target is refused`() {
        val issues = refused(
            """
            graph "probe"

            type Row { id: INT }

            on start {
                val r = parseJson(text: "{}") as Row { nope: "x" }
            }
            """.trimIndent(),
        )
        assertTrue(issues.any { it.contains("no field 'nope'") }, "not refused: $issues")
    }

    // ---- encoding -------------------------------------------------------------------------------------

    @Test
    fun `a record writes as an object in declaration order`() {
        val out = said(
            """
            graph "probe"

            type Item { id: INT, name: STRING, qty: INT }

            on start {
                say(message: jsonText(value: Item { id: 995, name: "Coins", qty: 3 }, pretty: false))
            }
            """.trimIndent(),
        )
        assertEquals(listOf("""{"id":995,"name":"Coins","qty":3}"""), out)
    }

    @Test
    fun `a value survives writing and reading back`() {
        val out = said(
            """
            $layout

            on start {
                val doc = parseJson(text: ${quoted(sample)}) as Doc
                val again = parseJson(text: jsonText(value: doc)) as Doc
                say(message: again.tabs[0].items[0].name)
                say(message: again == doc)
            }
            """.trimIndent(),
        )
        assertEquals(listOf("Coins", true), out)
    }

    // ---- maps and lists at the root ---------------------------------------------------------------
    //
    // There was no MAP coverage here at all, and `as` refused a target with type arguments outright — so a
    // list or a map could only be persisted by declaring a record to wrap it in. The schema builder had
    // always handled both at any depth; it was simply never handed one at the root.

    @Test
    fun `a map of numbers survives a round trip`() {
        val out = said(
            """
            graph "probe"

            on start {
                var runs: MAP<STRING, INT> = _newMap()
                runs = _mapWith(map: runs, key: "gotr", value: 3)
                runs = _mapWith(map: runs, key: "tithe", value: 1)
                val back = parseJson(text: jsonText(value: runs)) as MAP<STRING, INT>
                say(message: _mapAt(map: back, key: "gotr") ?: -1)
                say(message: _mapAt(map: back, key: "tithe") ?: -1)
                say(message: _mapAt(map: back, key: "never") ?: -1)
            }
            """.trimIndent(),
        )
        assertEquals(listOf(3L, 1L, -1L), out)
    }

    @Test
    fun `a map of records survives a round trip`() {
        // The shape a per-activity stats model wants: tallies keyed by NAME. Keyed by name rather than
        // holding the activity itself on purpose — a record carrying another document's type breaks a
        // third document that imports it — so this is the shape that has to work.
        val out = said(
            """
            graph "probe"

            type Tally { runs: INT, minutes: INT }

            on start {
                var stats: MAP<STRING, Tally> = _newMap()
                stats = _mapWith(map: stats, key: "gotr", value: Tally { runs: 4, minutes: 92 })
                val back = parseJson(text: jsonText(value: stats)) as MAP<STRING, Tally>
                if val t = _mapAt(map: back, key: "gotr") {
                    say(message: t.runs)
                    say(message: t.minutes)
                }
            }
            """.trimIndent(),
        )
        assertEquals(listOf(4L, 92L), out)
    }

    @Test
    fun `a list of records survives a round trip`() {
        val out = said(
            """
            graph "probe"

            type Row { id: INT, name: STRING }

            on start {
                val back = parseJson(text: ${quoted("""[{"id": 1, "name": "a"}, {"id": 2, "name": "b"}]""")}) as LIST<Row>
                say(message: _listCount(list: back))
                say(message: back[1].name)
            }
            """.trimIndent(),
        )
        assertEquals(listOf(2L, "b"), out)
    }

    @Test
    fun `JSON keys are text, so a map read back must be keyed by text`() {
        // The one thing a generic target still cannot be. A MAP<INT, …> writes out perfectly well and can
        // never be read back, because every JSON key is a string — so it is refused at compile time rather
        // than decoding into keys that are not the type declared.
        val problems = refused(
            """
            graph "probe"

            on start {
                val back = parseJson(text: "{}") as MAP<INT, INT>
            }
            """.trimIndent(),
        )
        assertTrue(problems.any { it.contains("MAP<STRING") }, "expected a keys-are-text refusal, got: $problems")
    }

    @Test
    fun `type arguments are still refused between records, where they mean nothing`() {
        // The other fork of `as`. Copying named fields from one record to another has no use for
        // arguments, and silently ignoring them would let a wrong belief compile.
        val problems = refused(
            """
            graph "probe"

            type Row { id: INT, name: STRING }

            on start {
                val r = Row { id: 1, name: "a" }
                val bad = r as LIST<Row>
            }
            """.trimIndent(),
        )
        assertTrue(
            problems.any { it.contains("type arguments") },
            "expected a refusal for arguments on a record-to-record cast, got: $problems",
        )
    }

    // ---- files ----------------------------------------------------------------------------------------

    @Test
    fun `write then read gets the records back`() {
        val files = MemoryFiles()
        val out = said(
            """
            $layout

            on start {
                val doc = parseJson(text: ${quoted(sample)}) as Doc
                writeJson(path: "bank/layout.json", value: doc)
                // `if val`, because reading a file that might not be there gives a `Doc?`. This is the
                // idiom the whole feature is meant to be written in.
                if val back = readJson(path: "bank/layout.json") as Doc {
                    say(message: back.tabs[1].title)
                    say(message: back == doc)
                }
            }
            """.trimIndent(),
            files,
        )
        assertEquals(listOf("Gear", true), out)
        assertEquals(listOf("bank/layout.json"), files.paths)
    }

    @Test
    fun `reading a missing file as a record is nothing, not a stop`() {
        // The type of `readJson(…) as Doc` is `Doc?` — the cast carries its source's optionality — so the
        // first-run case is an `?:` rather than a guard the author has to remember.
        val out = said(
            """
            $layout

            on start {
                val doc = readJson(path: "nope.json") as Doc
                say(message: doc?.version ?: -1)
                if val d = readJson(path: "nope.json") as Doc {
                    say(message: "unreachable")
                }
                say(message: "past it")
            }
            """.trimIndent(),
        )
        assertEquals(listOf(-1L, "past it"), out)
    }

    @Test
    fun `a missing file reads as nothing rather than stopping`() {
        // The ordinary first-run case: no saved state yet. A stop would make every read need a guard.
        val out = said(
            """
            graph "probe"

            on start {
                say(message: readText(path: "nope.txt") ?: "absent")
                say(message: fileExists(path: "nope.txt"))
            }
            """.trimIndent(),
        )
        assertEquals(listOf("absent", false), out)
    }

    @Test
    fun `text and listing and deleting behave`() {
        val files = MemoryFiles()
        val out = said(
            """
            graph "probe"

            on start {
                writeText(path: "d/a.txt", text: "one")
                writeText(path: "d/b.txt", text: "two")
                say(message: _listCount(list: listFiles(folder: "d")))
                say(message: readText(path: "d/a.txt") ?: "")
                say(message: deleteFile(path: "d/a.txt"))
                say(message: deleteFile(path: "d/a.txt"))
                say(message: _listCount(list: listFiles(folder: "d")))
            }
            """.trimIndent(),
            files,
        )
        assertEquals(listOf(2L, "one", true, false, 1L), out)
    }

    @Test
    fun `subfolders nest, and a script can walk down them`() {
        // Organisation, from the script's side: a folder per run, discovered a level at a time.
        val out = said(
            """
            graph "probe"

            type Run { points: INT }

            on start {
                writeJson(path: "runs/2026-08/gotr.json", value: Run { points: 40 })
                writeJson(path: "runs/2026-08/tithe.json", value: Run { points: 9 })
                writeJson(path: "runs/2026-07/gotr.json", value: Run { points: 3 })

                for month in listFolders(folder: "runs") {
                    for name in listFiles(folder: text("runs/{m}", m: month)) {
                        say(message: text("{m}/{n}", m: month, n: name))
                    }
                }
                if val r = readJson(path: "runs/2026-08/gotr.json") as Run {
                    say(message: r.points)
                }
            }
            """.trimIndent(),
        )
        assertEquals(
            listOf("2026-07/gotr.json", "2026-08/gotr.json", "2026-08/tithe.json", 40L),
            out,
        )
    }

    @Test
    fun `a single is saved and loaded like any other record`() {
        // `single Run { … }` is a record type plus one variable of it under the same name — so there is
        // nothing new here at all: the variable holds a StructValue, which writes as an object, and `as
        // Run` names the TYPE, which is what the cast wanted anyway. Worth pinning precisely because the
        // shared name makes it look like it might need something special. It does not.
        val files = MemoryFiles()
        val out = said(
            """
            graph "probe"

            enum Phase { Chop, Bank }

            single Run {
                phase: Phase = Phase.Chop,
                laps: INT = 0,
                where: STRING = "start",
            }

            on start {
                Run.laps = 7
                Run.phase = Phase.Bank

                // Saving the singleton is saving the variable.
                writeJson(path: "state/run.json", value: Run)

                // Loading it is an ordinary cast to the type of the same name, then an assignment. The
                // `if val` is because the file might not be there, not because a single is special.
                Run.laps = 0
                Run.phase = Phase.Chop
                if val saved = readJson(path: "state/run.json") as Run {
                    Run = saved
                }

                say(message: Run.laps)
                say(message: Run.phase == Phase.Bank)
                say(message: Run.where)
            }
            """.trimIndent(),
            files,
        )
        assertEquals(listOf(7L, true, "start"), out)
        // The enum member round-trips as its own name, which is what an enum IS at run time.
        assertTrue(files.read("state/run.json").orEmpty().contains("\"phase\": \"Bank\""), files.read("state/run.json").orEmpty())
    }

    @Test
    fun `a map field's STARTING value is an empty map, not null`() {
        // **The bug this pins cost a live account its rotation, and a script-level test cannot see it.**
        //
        // A `MAP` field's declared default is almost always `emptyMap()`, which is a CALL — so the compiler
        // cannot fold it into the variable's starting value, and `zeroOf` had no MAP case, so the field
        // started as null. A single with a computed default also gets an initialiser prologue that runs the
        // call at start-up, which is why a script that RAN never saw it: by the time `on start` executes,
        // the map is already `{}`. Written as a running probe, this test passes with the bug still in.
        //
        // What has no prologue is a graph that was loaded and never started — and `always on stop` still
        // fires for one, at client shutdown. That save wrote `"budgets": null`, and every wake afterwards
        // stopped the script on `budgets: expected an object, found nothing`. So the starting value is
        // asked for directly, which is the only place the fault is observable.
        val g = graphOf(
            """
            graph "probe"

            single Day {
                target: INT = 3,
                budgets: MAP<STRING, INT> = _newMap(),
                runs: LIST<INT> = [],
            }

            on start {
                say(message: Day.target)
            }
            """.trimIndent(),
        )
        val day = dev.ziggle.vscript.compile.startingGlobals(g).single() as dev.ziggle.vscript.vm.StructValue
        assertEquals(emptyMap<Any?, Any?>(), day.get("budgets"), "a map's zero is an empty map")
        assertEquals(emptyList<Any?>(), day.get("runs"), "and a list's is an empty list, as it always was")
        assertEquals(3L, (day.get("target") as Number).toLong())
    }

    @Test
    fun `a null container reads as an empty one, so a file written before the fix still loads`() {
        // Every state file this language wrote while the hole above was open holds a null where a map
        // should be, and those files are on real accounts. Refusing them means the script stops on every
        // wake for ever over a map that was empty and was always going to be empty.
        //
        // Deliberately NOT the zero-filling a missing key gets, which is still a stop: a container has an
        // unambiguous empty and `null`, `[]` and `{}` all say it, while a missing number does not.
        val files = MemoryFiles()
        files.write("state/day.json", """{"target": 5, "budgets": null, "runs": null}""")
        val out = said(
            """
            graph "probe"

            type Day {
                target: INT = 0,
                budgets: MAP<STRING, INT> = _newMap(),
                runs: LIST<INT> = [],
            }

            on start {
                if val saved = readJson(path: "state/day.json") as Day {
                    say(message: saved.target)
                    say(message: _mapCount(map: saved.budgets))
                    say(message: _listCount(list: saved.runs))
                }
            }
            """.trimIndent(),
            files,
        )
        assertEquals(listOf(5L, 0L, 0L), out)
    }

    @Test
    fun `a single whose saved file names a member the enum lost is refused, not silently kept`() {
        // The value of decoding a choice against its declaration rather than taking any string: a saved
        // file outlives the code that wrote it, and "Move" surviving into a `Phase` that no longer has
        // it would be a member nothing can match and every `when` would fall through.
        val files = MemoryFiles(mapOf("state/run.json" to """{"phase": "Legacy", "laps": 1}"""))
        val e = runCatching {
            said(
                """
                graph "probe"

                enum Phase { Chop, Bank }

                single Run { phase: Phase = Phase.Chop, laps: INT = 0 }

                on start {
                    if val saved = readJson(path: "state/run.json") as Run {
                        Run = saved
                    }
                }
                """.trimIndent(),
                files,
            )
        }.exceptionOrNull()
        val m = e?.message.orEmpty()
        assertTrue(m.contains("Legacy"), "does not name the bad member: $m")
        assertTrue(m.contains("Chop") && m.contains("Bank"), "does not list the members: $m")
    }

    @Test
    fun `a host that granted no files says so instead of pretending they are empty`() {
        val e = runCatching {
            // The default registry, with no store passed — what an embedder gets until it grants one.
            val g = graphOf(
                """
                graph "probe"
                on start { writeText(path: "a.txt", text: "x") }
                """.trimIndent(),
            )
            drive(
                GraphCompiler(catalog, debug = false).compile(g, g.entries(catalog).single().id),
                BuiltinHosts.registry(), maxTicks = 100,
            ).fiber.error?.let { throw it }
        }.exceptionOrNull()
        assertTrue(e?.message.orEmpty().contains("no file access"), "unhelpful: ${e?.message}")
    }

    @Test
    fun `a file verb with no path is refused rather than touching the folder itself`() {
        val e = runCatching {
            said(
                """
                graph "probe"
                on start { writeText(path: "", text: "x") }
                """.trimIndent(),
            )
        }.exceptionOrNull()
        assertTrue(e?.message.orEmpty().contains("needs a path"), "unhelpful: ${e?.message}")
    }

    // ---- the round trip -------------------------------------------------------------------------------

    @Test
    fun `a quoted key comes back quoted and a bare one bare`() {
        // The reason `@quoted` exists. Both spellings are legal and they mean different things — a JSON
        // key and a field of a source record — so deriving the spelling from the text would rewrite one
        // of them. `item_count` is a legal bare name, which is exactly the case that proves it.
        for (clause in listOf("""{ itemCount: "total" }""", "{ itemCount: total }")) {
            val src = """
                graph "probe"

                type Row { itemCount: INT }
                type Wide { total: INT, other: STRING }

                on start {
                    val w = Wide { total: 1, other: "x" }
                    val r = w as Row $clause
                }
            """.trimIndent()
            assertEquals(src.trim(), VsText(catalog).write(graphOf(src)).trim(), "did not round-trip: $clause")
        }
    }

    @Test
    fun `a JSON key holding the separator is refused where it is written`() {
        // `castRenamesOf` joins on ',' and splits on '=', so such a key would come back as a different
        // one — or as two. Reported at the source rather than corrupting the literal.
        val issues = refused(
            """
            graph "probe"

            type Row { id: INT }

            on start {
                val r = parseJson(text: "{}") as Row { id: "a,b" }
            }
            """.trimIndent(),
        )
        assertTrue(issues.any { it.contains("comma") }, "not refused: $issues")
    }

    /** [s] as a vs string literal — the escaping the tests would otherwise all repeat. */
    private fun quoted(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
