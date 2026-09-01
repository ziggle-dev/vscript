package dev.ziggle.vscript.model

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.io.File

/**
 * Reads and writes graph documents as JSON.
 *
 * **Files, not `DataStore`.** Graphs live as JSON files under `~/.ziggle/graphs/` because they are authoring
 * artifacts: they should be diffable, shareable and account-independent, none of which a per-account
 * key-value store gives. It also means hot-reload can reuse the directory-watching the plugin loader
 * already does.
 *
 * **Mapped by hand rather than by reflection.** Gson's field reflection would couple the on-disk schema to
 * Kotlin field names, so a refactor would silently invalidate every saved graph. Writing the mapping out
 * makes the format an explicit, reviewable contract and gives migrations somewhere to live.
 *
 * Node positions are stored here rather than in imgui-node-editor's own opaque state blob, so a document
 * is self-contained and moving a node produces a readable diff.
 */
object GraphDoc {

    /** Bumped whenever the on-disk shape changes; [read] migrates older documents forward. */
    const val FORMAT = 10

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    // ---- write ------------------------------------------------------------------------------------

    fun toJson(graph: Graph): String = gson.toJson(encode(graph))

    fun write(graph: Graph, file: File) {
        file.parentFile?.mkdirs()
        file.writeText(toJson(graph))
    }

    private fun encode(graph: Graph): JsonObject = JsonObject().apply {
        addProperty("format", FORMAT)
        addProperty("id", graph.id)
        addProperty("name", graph.name)
        // One name, so it rides beside the header rather than as a flag on a declaration where a second
        // one could contradict it. Written only when there is one.
        graph.defaultExport?.let { addProperty("default", it) }
        // Spelling — which declarations said `export` at the bottom rather than in place. Written only
        // when there is one, so a document that uses the ordinary form is unchanged. See Graph.exportList.
        if (graph.exportList.isNotEmpty()) {
            add("exportList", JsonArray().apply { graph.exportList.forEach { add(it) } })
        }
        // First, because everything below may be typed or named through one and a document reads top-down.
        // Both the ref and the resolved id are written: the ref is what a person recognises in a diff, the
        // id is what survives the target being renamed. See [GraphImport].
        if (graph.imports.isNotEmpty()) {
            add("imports", JsonArray().apply {
                graph.imports.forEach { i ->
                    add(JsonObject().apply {
                        addProperty("alias", i.alias)
                        addProperty("ref", i.ref)
                        i.docId?.let { addProperty("doc", it) }
                        // The named list and the default, which had NEVER been written — so a `.vs` using
                        // either collapsed into a plain namespace import the first time it went through
                        // the canvas, silently, and the names it had brought in stopped resolving. Both
                        // are written only when present, so a namespace import's JSON is unchanged.
                        i.default?.let { addProperty("default", it) }
                        // `export … from` — what this line passes ON. Written only when there is some,
                        // so an ordinary import's JSON is byte-for-byte what it was.
                        if (i.reExportAll) addProperty("reExportAll", true)
                        // `import "core/list"` — everything under its own name. Written only when set, so
                        // every other import's JSON is unchanged.
                        if (i.star) addProperty("star", true)
                        if (i.reExported.isNotEmpty()) {
                            add("reExported", JsonArray().apply {
                                i.reExported.forEach { item ->
                                    add(JsonObject().apply {
                                        addProperty("name", item.name)
                                        if (item.isAliased) addProperty("as", item.local)
                                    })
                                }
                            })
                        }
                        if (i.named.isNotEmpty()) {
                            add("named", JsonArray().apply {
                                i.named.forEach { item ->
                                    add(JsonObject().apply {
                                        addProperty("name", item.name)
                                        if (item.isAliased) addProperty("as", item.local)
                                    })
                                }
                            })
                        }
                    })
                }
            })
        }
        // Before variables and functions, because they can be typed as one and a document reads top-down.
        if (graph.types.isNotEmpty()) {
            add("types", JsonArray().apply {
                graph.types.forEach { t ->
                    add(JsonObject().apply {
                        addProperty("name", t.name)
                        // Before the fields, because a field may be typed as one of them.
                        if (t.params.isNotEmpty()) {
                            add("params", JsonArray().apply { t.params.forEach { add(it) } })
                        }
                        add("fields", encodePins(t.fields))
                        // Only when true: writing the default on every declaration doubles a document's
                        // size to record nothing.
                        if (t.isExported) addProperty("export", true)
                        // A record with exactly one of it — see StructType.isSingle. Written only when
                        // true, so every document from before it existed reads back as the record it was.
                        if (t.isSingle) addProperty("single", true)
                    })
                }
            })
        }
        // Beside the records and for the same reason: a variable may be typed as one.
        if (graph.enums.isNotEmpty()) {
            add("enums", JsonArray().apply {
                graph.enums.forEach { e ->
                    add(JsonObject().apply {
                        addProperty("name", e.name)
                        add("members", JsonArray().apply { e.members.forEach { add(it) } })
                        if (e.isExported) addProperty("export", true)
                        // Written only for an enum that declares fields, so a plain enum's JSON is
                        // byte-for-byte what it was at format 8 and its diff stays empty.
                        if (e.fields.isNotEmpty()) {
                            add("fields", encodePins(e.fields))
                            // Keyed by member name, matching how the model holds them and how a member is
                            // identified everywhere else. A positional array would re-introduce the
                            // ordinal this language spent effort not having.
                            add("values", JsonObject().apply {
                                e.members.forEach { m ->
                                    add(m, JsonArray().apply {
                                        e.values[m].orEmpty().forEach { add(encodeValue(it)) }
                                    })
                                }
                            })
                        }
                    })
                }
            })
        }
        add("variables", JsonArray().apply {
            graph.variables.forEach { v ->
                add(JsonObject().apply {
                    addProperty("name", v.name)
                    addProperty("type", v.type.toString())
                    add("default", encodeValue(v.default))
                    if (v.isExported) addProperty("export", true)
                    // Written only when true, like `private`, so every document written before `val`
                    // existed reads back as the `var` it was.
                    if (v.isImmutable) addProperty("immutable", true)
                })
            }
        })
        add("functions", JsonArray().apply {
            graph.functions.forEach { f ->
                add(JsonObject().apply {
                    addProperty("name", f.name)
                    add("params", encodePins(f.params))
                    add("results", encodePins(f.results))
                    if (f.isExported) addProperty("export", true)
                    // Only on an extension. The receiver is already `params[0]`, so this records the
                    // SPELLING rather than the shape — see [GraphFunction.receiver].
                    f.receiver?.let { addProperty("receiver", it.toString()) }
                })
            }
        })
        add("nodes", JsonArray().apply {
            graph.nodes.forEach { n ->
                add(JsonObject().apply {
                    addProperty("id", n.id)
                    addProperty("type", n.type)
                    addProperty("x", n.x)
                    addProperty("y", n.y)
                    // Only container nodes carry a size; omit it elsewhere so documents stay readable.
                    if (n.w > 0f || n.h > 0f) {
                        addProperty("w", n.w)
                        addProperty("h", n.h)
                    }
                    n.variable?.let { addProperty("variable", it) }
                    n.function?.let { addProperty("function", it) }
                    n.callee?.let { addProperty("callee", it) }
                    n.group?.let { addProperty("group", it) }
                    // Only when true: a folded flag on every node would double the size of a document to
                    // record the default.
                    if (n.folded) addProperty("folded", true)
                    n.comment?.let { addProperty("comment", it) }
                    n.body?.let { addProperty("body", it) }
                    if (n.literals.isNotEmpty()) {
                        add("literals", JsonObject().apply {
                            n.literals.forEach { (k, v) -> add(k, encodeValue(v)) }
                        })
                    }
                })
            }
        })
        add("links", JsonArray().apply {
            graph.links.forEach { l ->
                add(JsonObject().apply {
                    addProperty("id", l.id)
                    addProperty("fromNode", l.fromNode)
                    addProperty("fromPin", l.fromPin)
                    addProperty("toNode", l.toNode)
                    addProperty("toPin", l.toPin)
                })
            }
        })
    }

    /**
     * Encode a literal.
     *
     * Ints are tagged so they survive the round trip. JSON has one number type and Gson hands a bare `1`
     * back as a Double, which would turn every item id into `4151.0` and every `Int` pin into a Float —
     * quietly wrong in a way that only shows up when a host function casts.
     */
    private fun encodeValue(v: Any?): com.google.gson.JsonElement = when (v) {
        null -> com.google.gson.JsonNull.INSTANCE
        is Boolean -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        is Int -> tagged("i", v)
        is Long -> tagged("l", v)
        is Double, is Float -> JsonPrimitive(v as Number)
        is List<*> -> JsonArray().apply { v.forEach { add(encodeValue(it)) } }
        // Tagged like the ints are, and for the same reason: without it a record would go out as its
        // toString and come back as a string that merely looks like one.
        is dev.ziggle.vscript.vm.StructValue -> JsonObject().apply {
            addProperty("\$t", "s")
            addProperty("type", v.type)
            add("fields", JsonObject().apply { v.names.forEachIndexed { i, n -> add(n, encodeValue(v[i])) } })
        }
        else -> JsonPrimitive(v.toString())
    }

    /**
     * A signature's pins — a parameter list, a result list, or an enum's columns.
     *
     * **The default is written, and it was not before.** A `FunctionPin` has carried one since defaults
     * were added, but this wrote only the name and the type — so `fn f(dx: INT = 1)` survived being
     * printed as text and was silently lost the moment the document went through JSON, turning a callable
     * function into one that reports a missing argument at every existing call site. Only when there IS
     * one, so a document without defaults is byte-for-byte what it was.
     */
    private fun encodePins(pins: List<FunctionPin>): JsonArray = JsonArray().apply {
        pins.forEach { p ->
            add(JsonObject().apply {
                addProperty("name", p.name)
                addProperty("type", p.type.toString())
                if (p.default != null) add("default", encodeValue(p.default))
            })
        }
    }

    private fun tagged(kind: String, n: Number): JsonObject =
        JsonObject().apply { addProperty("\$t", kind); addProperty("v", n) }

    // ---- read -------------------------------------------------------------------------------------

    fun fromJson(text: String): Graph {
        // `Gson.fromJson` rather than `JsonParser.parseString`: the effective gson on the client classpath
        // report shows a newer gson, but the shaded copy wins at compile and run time.
        val root = gson.fromJson(text, JsonObject::class.java)
        val format = root.get("format")?.asInt ?: 0
        require(format <= FORMAT) {
            "graph document format $format is newer than this client understands (max $FORMAT)"
        }
        return decode(migrate(root, format))
    }

    fun read(file: File): Graph = fromJson(file.readText())

    /**
     * Bring an older document up to [FORMAT].
     *
     * Nothing to do yet — but the hook exists from version 1 deliberately. Migrations are cheap to add
     * while there is one format and expensive to retrofit once graphs are in the wild.
     */
    /**
     * Bring an older document up to [FORMAT].
     *
     * **8 → 9: an enum may carry data.** Nothing to rewrite — a format-8 document has no `fields` key on an
     * enum and decodes to none, which is exactly what it meant. The bump is the 6 → 7 precedent applied to
     * the same construct one step on: an older client that dropped the table would keep the members and
     * lose the rows, so every `t.anchor` in the document would resolve to a field the enum no longer
     * declares. The node would then shape itself with no output at all and the graph would compile to one
     * that silently reads nothing — which is worse than refusing to open it.
     *
     * **7 → 8: a function may extend a type.** Nothing to rewrite — a format-7 document has no `receiver`
     * key and decodes to none, which is exactly what it meant. The bump is the 4 → 5 precedent again: an
     * older client that dropped the receiver would read `fn List.add` as an ordinary `add` whose first
     * parameter happens to be called `self`, then print it back that way — turning an extension into a
     * function nobody can call, silently.
     *
     * **6 → 7: a document may declare enums.** Nothing to rewrite — a format-6 document has no `enums` key
     * and decodes to none, which is exactly what it meant. The bump exists for the same reason 4 → 5's did:
     * so an OLDER client REFUSES a format-7 document rather than reading it. Dropping the enums it cannot
     * represent would leave every `enum.of` node naming a type that does not exist, so the picker would show
     * no members and the node would compile to whichever member happened to be first — a script that runs
     * and takes the wrong branch, which is the failure this project keeps designing against.
     *
     * **4 → 5: a document may import others.** Nothing to rewrite — a format-4 document has no `imports`
     * key and decodes to none, which is exactly what it meant. The bump is there so an OLDER client
     * refuses a format-5 document rather than reading it: dropping the imports it cannot represent would
     * leave every qualified name unresolvable and every imported variable's slot missing from the globals
     * layout, which surfaces as a script that compiles and reads the wrong state.
     *
     * **3 → 4: the item-list pins hold a LIST.** `Use Item On Any` and `Drop Any` took their ids as a
     * comma-separated string, which is what they were written with before lists existed. The pins are
     * real lists now, and the host still reads either — but a string left on a list pin draws no editor,
     * so a graph authored before the change would have become one you could run and not change. The
     * conversion is mechanical: split on commas, keep the numbers.
     *
     * **9 → 10: `private` became `export`, and the polarity turned over.** This is the one migration
     * that is not a no-op, because absence used to MEAN something: a declaration with no `"private"` key
     * was public, and under the new rule a declaration with no `"export"` key offers nothing. Read
     * literally, a format-9 document would come back with every symbol hidden and every importer broken —
     * which is precisely the silent misreading the version gate exists to prevent. So the rewrite is
     * explicit: drop `"private": true`, and write `"export": true` on everything that did not have it.
     *
     * Entries need nothing. `@private` on an entry node meant "does not fire for an importer", and the
     * replacement `@always` means "does fire" — absent is the same answer either way round, because a
     * document being run DIRECTLY has always run all of its own entries.
     *
     * **2 → 3: a type is a NAME, not an enum constant.** Nothing to rewrite. A built-in's name IS its old
     * enum constant name, so `"INT"` written by a format-2 client parses back to the same type and a
     * document that never mentions a declared type round-trips byte for byte. The bump exists so a
     * format-3 document — one that DOES name a declared type — is refused by an older client rather than
     * silently read with every such type widened to a wildcard.
     *
     * **1 → 2: a function's ends moved onto its box.** Bodies used to begin at an `function.in` node and
     * return at a `function.out` one; the box now carries both, as pins on its edges. The rewrite is purely
     * mechanical — every wire that touched a proxy node is re-aimed at the box, keeping its pin name, and
     * the proxies are dropped. Pin names are unchanged, so nothing has to be matched up by hand.
     */
    /**
     * Format 9 → 10: every declaration that was public becomes explicitly exported.
     *
     * Applied to the four kinds that carried the flag — types, enums, variables, functions. A `const` is a
     * literal node and had no flag to carry: it was reachable through an import unconditionally, so every
     * one of them becomes exported too, which is what the document meant.
     */
    private fun privateToExport(root: JsonObject) {
        for (key in listOf("types", "enums", "variables", "functions")) {
            for (e in root.getAsJsonArray(key) ?: continue) {
                val o = e.asJsonObject
                val wasPrivate = o.get("private")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                o.remove("private")
                if (!wasPrivate) o.addProperty("export", true)
            }
        }
        for (e in root.getAsJsonArray("nodes") ?: JsonArray()) {
            val literals = e.asJsonObject.getAsJsonObject("literals") ?: continue
            if (literals.has("@name")) literals.addProperty("@exported", true)
        }
    }

    private fun migrate(root: JsonObject, from: Int): JsonObject {
        // BEFORE everything else: it rewrites a key every later step may read.
        if (from < 10) privateToExport(root)
        // BEFORE itemListsToLists, which names its two nodes by their current type.
        if (from < 6) splitGameNamespace(root)
        if (from < 4) itemListsToLists(root)
        if (from >= 2) return root
        val nodes = root.getAsJsonArray("nodes") ?: return root
        val links = root.getAsJsonArray("links") ?: JsonArray()

        fun str(o: JsonObject, k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asString

        // function name -> box id, and the proxy ids that should now point at it.
        val boxOf = HashMap<String, Int>()
        val proxyToBox = HashMap<Int, Int>()
        for (e in nodes) {
            val o = e.asJsonObject
            if (str(o, "type") == "function.box") str(o, "function")?.let { boxOf[it] = o.get("id").asInt }
        }
        for (e in nodes) {
            val o = e.asJsonObject
            val t = str(o, "type")
            if (t != "function.in" && t != "function.out") continue
            val box = str(o, "function")?.let { boxOf[it] } ?: continue
            proxyToBox[o.get("id").asInt] = box
        }
        if (proxyToBox.isEmpty()) return root

        for (e in links) {
            val l = e.asJsonObject
            proxyToBox[l.get("fromNode").asInt]?.let { l.addProperty("fromNode", it) }
            proxyToBox[l.get("toNode").asInt]?.let { l.addProperty("toNode", it) }
        }
        val kept = JsonArray()
        for (e in nodes) {
            if (e.asJsonObject.get("id").asInt !in proxyToBox) kept.add(e)
        }
        root.add("nodes", kept)
        root.add("links", links)
        return root
    }

    /**
     * The game verbs used to share one flat `game.` namespace; each carries its own domain's prefix now.
     *
     * `game.walkTo` is `movement.walkTo`, `game.itemCount` is `inventory.itemCount`, and so on for all 128
     * of them — the prefix matches the file the node is defined in, alongside the `draw.` that always had
     * one. Only the PREFIX changed; every suffix is untouched, which is why text documents needed no
     * migration at all: they write the short name (`walkTo`) wherever it is unambiguous.
     *
     * This is the one place the old names still exist, and it is a one-way upgrade rather than an alias —
     * a document that comes through here is written back with the new names and never needs it again.
     * Resolving `game.*` at LOOKUP time instead would have been less code and would have kept the dead
     * namespace alive in every document indefinitely.
     */
    private fun splitGameNamespace(root: JsonObject) {
        for (e in root.getAsJsonArray("nodes") ?: JsonArray()) {
            val o = e.asJsonObject
            val type = o.get("type")?.takeIf { !it.isJsonNull }?.asString ?: continue
            if (!type.startsWith("game.")) continue
            val suffix = type.removePrefix("game.")
            NAMESPACE_OF[suffix]?.let { o.addProperty("type", "$it.$suffix") }
        }
    }

    /**
     * Which domain each pre-split verb moved into, by suffix.
     *
     * Written out rather than derived from the live catalogue, because a migration has to mean the same
     * thing forever: it describes what documents of a given vintage contain, and re-deriving it from a
     * catalogue that keeps changing would make an old document's meaning depend on today's node list.
     */
    private val NAMESPACE_OF: Map<String, String> = mapOf(
        "bank" to "bankCount bankOpen closeBank depositAll depositItem openBank withdrawItem",
        "camera" to "cameraZoom focusObject lockCamera unlockCamera zoom",
        "dialogue" to "chooseOption continueDialogue inDialogue",
        "hop" to "currentWorld hopNextWorld hopPreviousWorld hopRandomWorld hopToWorld worldAhead worldInfo",
        "interact" to "dropItem equipItem interactEntity interactGroundItem interactNpc interactObject " +
            "lootItem makeAll makeOpen pressSpace takeItem talkTo tryEquipItem useItem useItemOn " +
            "useItemOnAny useItemOnItem",
        "inventory" to "dropAny freeSlots hasItem inventoryFull isEquipped itemCount itemHasAction " +
            "itemWithAction itemsWithAction waitForItem",
        "items" to "itemCounts itemInfo itemRef itemRefs",
        "lists" to "bankItems equippedItems groundItemTiles inventoryItems nearbyGroundItems nearbyNpcs " +
            "nearbyObjects npcTiles objectTiles",
        "magic" to "castSpell",
        "movement" to "clickTile isNavigating stopWalking walkStep walkTo",
        "names" to "groundItemsNamed itemByName itemName nearestGroundItemNamed nearestNpcNamed " +
            "nearestNpcOfId nearestObjectNamed npcByName npcName npcsNamed objectByName objectName " +
            "objectsNamed",
        "player" to "distanceTo healthPercent idleFor inCombat isAnimating isMoving playerTile waitIdle",
        "scene" to "anyObjectAt canReachGroundItem canReachNpc canReachObject canReachTile chainFrom " +
            "entityInfo groundItemAt groundItemNamedAt groundItemNearby groundItemTile hasTile isWalkable " +
            "lastClicked npcAt npcHealthBar npcNamedAt npcNear npcNearby npcTile objectAction objectAt " +
            "objectNamedAt objectNearby objectRefAt objectTile tileOffset tileWithChain tileWithRoom",
        "skills" to "bestTool skillExperience skillLevel skillReal totalExperience xpToNextLevel",
        "ui" to "findWidget findWidgets widgetExists widgetInfo widgetInteract",
        "world" to "nearestClusterOf npcInWorld npcInWorldNamed objectClusterOf objectInWorld objectInWorldNamed",
    ).flatMap { (prefix, names) -> names.split(' ').map { it to prefix } }.toMap()

    /** Comma-string ids on the two pins that now carry a list, turned into one. */
    private fun itemListsToLists(root: JsonObject) {
        val pins = mapOf("interact.useItemOnAny" to "Targets", "inventory.dropAny" to "Items")
        for (e in root.getAsJsonArray("nodes") ?: JsonArray()) {
            val o = e.asJsonObject
            val pin = pins[o.get("type")?.takeIf { !it.isJsonNull }?.asString] ?: continue
            val lits = o.getAsJsonObject("literals") ?: continue
            val v = lits.get(pin)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString } ?: continue
            val ids = v.asString.split(',', ' ').mapNotNull { it.trim().toIntOrNull() }
            if (ids.isEmpty()) lits.remove(pin)
            else lits.add(pin, JsonArray().apply { ids.forEach { add(tagged("i", it)) } })
        }
    }

    private fun decode(root: JsonObject): Graph {
        val variables = root.getAsJsonArray("variables")?.map { e ->
            val o = e.asJsonObject
            GraphVariable(
                name = o.get("name").asString,
                type = TypeRef.parse(o.get("type")?.takeIf { !it.isJsonNull }?.asString),
                default = decodeValue(o.get("default")),
                isExported = o.get("export")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                isImmutable = o.get("immutable")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
            )
        } ?: emptyList()

        val types = root.getAsJsonArray("types")?.map { e ->
            val o = e.asJsonObject
            // Marked HERE, the same way a function's variables are and at the same boundary — a field
            // typed `A` reads back as a declared type otherwise, and the validator would report a record
            // called `A` that nothing declares. A record needs no `declares` predicate: it SAYS what its
            // parameters are, so there is nothing to infer.
            withFieldVariables(
                StructType(
                    name = o.get("name").asString,
                    fields = decodePins(o.getAsJsonArray("fields")),
                    isExported = o.get("export")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                    params = o.getAsJsonArray("params")?.map { it.asString } ?: emptyList(),
                    isSingle = o.get("single")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                )
            )
        } ?: emptyList()

        val enums = root.getAsJsonArray("enums")?.map { e ->
            val o = e.asJsonObject
            val members = o.getAsJsonArray("members")?.map { it.asString } ?: emptyList()
            val rows = o.getAsJsonObject("values")
            EnumType(
                name = o.get("name").asString,
                members = members,
                isExported = o.get("export")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                fields = decodePins(o.getAsJsonArray("fields")),
                // Only for members the file actually carries a row for. A member with none reads back as
                // absent rather than as an empty row, which is what `column` already expects.
                values = members.mapNotNull { m ->
                    rows?.getAsJsonArray(m)?.let { arr -> m to arr.map { decodeValue(it) } }
                }.toMap(),
            )
        } ?: emptyList()

        // A type variable is stored as the bare name the author wrote — see [TypeRef.toString] — so it has
        // to be re-derived here, from the same evidence `Lower` used: this document's own declarations.
        // Both boundaries ask exactly one question and so cannot disagree.
        val declaresType = { name: String ->
            types.any { it.name.equals(name, true) } || enums.any { it.name.equals(name, true) } ||
                HostRecords.dataStructs().any { it.name.equals(name, true) }
        }
        val functions = root.getAsJsonArray("functions")?.map { e ->
            val o = e.asJsonObject
            withTypeVariables(
                GraphFunction(
                    name = o.get("name").asString,
                    params = decodePins(o.getAsJsonArray("params")),
                    results = decodePins(o.getAsJsonArray("results")),
                    isExported = o.get("export")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                    receiver = o.get("receiver")?.takeIf { !it.isJsonNull }?.let { TypeRef.parse(it.asString) },
                ),
                declaresType,
            )
        } ?: emptyList()

        val nodes = root.getAsJsonArray("nodes")?.map { e ->
            val o = e.asJsonObject
            Node(
                id = o.get("id").asInt,
                type = o.get("type").asString,
                x = o.get("x")?.asFloat ?: 0f,
                y = o.get("y")?.asFloat ?: 0f,
                w = o.get("w")?.asFloat ?: 0f,
                h = o.get("h")?.asFloat ?: 0f,
                literals = o.getAsJsonObject("literals")?.entrySet()
                    ?.associateTo(LinkedHashMap()) { (k, v) -> k to decodeValue(v) } ?: LinkedHashMap(),
                variable = o.get("variable")?.takeIf { !it.isJsonNull }?.asString,
                comment = o.get("comment")?.takeIf { !it.isJsonNull }?.asString,
                body = o.get("body")?.takeIf { !it.isJsonNull }?.asString,
                function = o.get("function")?.takeIf { !it.isJsonNull }?.asString,
                callee = o.get("callee")?.takeIf { !it.isJsonNull }?.asString,
                group = o.get("group")?.takeIf { !it.isJsonNull }?.asInt,
                folded = o.get("folded")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
            )
        } ?: emptyList()

        val links = root.getAsJsonArray("links")?.map { e ->
            val o = e.asJsonObject
            Link(
                id = o.get("id").asInt,
                fromNode = o.get("fromNode").asInt,
                fromPin = o.get("fromPin").asString,
                toNode = o.get("toNode").asInt,
                toPin = o.get("toPin").asString,
            )
        } ?: emptyList()

        val imports = root.getAsJsonArray("imports")?.map { e ->
            val o = e.asJsonObject
            GraphImport(
                alias = o.get("alias").asString,
                ref = o.get("ref")?.takeIf { !it.isJsonNull }?.asString ?: o.get("alias").asString,
                docId = o.get("doc")?.takeIf { !it.isJsonNull }?.asString,
                named = o.getAsJsonArray("named")?.map { n ->
                    val item = n.asJsonObject
                    val name = item.get("name").asString
                    ImportItem(name, item.get("as")?.takeIf { !it.isJsonNull }?.asString ?: name)
                } ?: emptyList(),
                default = o.get("default")?.takeIf { !it.isJsonNull }?.asString,
                reExported = o.getAsJsonArray("reExported")?.map { n ->
                    val item = n.asJsonObject
                    val name = item.get("name").asString
                    ImportItem(name, item.get("as")?.takeIf { !it.isJsonNull }?.asString ?: name)
                } ?: emptyList(),
                reExportAll = o.get("reExportAll")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                star = o.get("star")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
            )
        } ?: emptyList()

        return Graph(
            id = root.get("id")?.asString ?: "",
            name = root.get("name")?.asString ?: "untitled",
            nodes = nodes,
            links = links,
            variables = variables,
            functions = functions,
            types = types,
            enums = enums,
            imports = imports,
            defaultExport = root.get("default")?.takeIf { !it.isJsonNull }?.asString,
            exportList = root.getAsJsonArray("exportList")?.map { it.asString } ?: emptyList(),
        )
    }

    private fun decodePins(arr: JsonArray?): List<FunctionPin> = arr?.map { e ->
        val o = e.asJsonObject
        FunctionPin(
            name = o.get("name").asString,
            type = TypeRef.parse(o.get("type")?.takeIf { !it.isJsonNull }?.asString),
            default = decodeValue(o.get("default")),
        )
    } ?: emptyList()

    private fun decodeValue(e: com.google.gson.JsonElement?): Any? = when {
        e == null || e.isJsonNull -> null
        e.isJsonObject -> {
            val o = e.asJsonObject
            when (o.get("\$t")?.asString) {
                "i" -> o.get("v").asInt
                "l" -> o.get("v").asLong
                else -> null
            }
        }
        e.isJsonArray -> e.asJsonArray.map { decodeValue(it) }
        else -> {
            val p = e.asJsonPrimitive
            when {
                p.isBoolean -> p.asBoolean
                p.isString -> p.asString
                else -> p.asDouble
            }
        }
    }
}
