package dev.ziggle.vscript.manifest

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.ziggle.vscript.lang.Names
import dev.ziggle.vscript.model.HostEnum
import dev.ziggle.vscript.model.HostEnums
import dev.ziggle.vscript.model.HostField
import dev.ziggle.vscript.model.HostRecord
import dev.ziggle.vscript.model.HostRecords
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeDescriptor
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.model.Types
import dev.ziggle.vscript.vm.HostKind

/**
 * The node catalogue, as a file something outside this process can read.
 *
 * **This exists for editors that are not the canvas.** A `NodeDescriptor` is already a complete signature —
 * type, kind, pins with names, types, defaults and documentation, plus a summary — so an IDE offering
 * completion, hover and go-to-definition needs no new model, only a way to be *told*. That is all this is.
 *
 * Two producers, one schema:
 *
 * - **Build time.** The client can build its library headlessly (`GameNodes.library(null)` returns
 *   descriptors without a game) and write this out, so a plugin ships knowing every node without ever
 *   having run the client.
 * - **Run time.** The client writes the same file into its home on startup, so a plugin can prefer a newer
 *   one — which means a client update teaches the IDE new nodes **without a plugin release**. The node
 *   catalogue moves far faster than the language does, and that gap is what this closes.
 *
 * [text] is the load-bearing field and the easiest to get wrong. It is `Names`' shortest-unambiguous
 * spelling — `nearestObjectNamed` for `game.nearestObjectNamed`, `drawTile` for `draw.tile` because that
 * node claims the name, and the qualified type where two nodes would answer to the same short word. A
 * consumer that recomputed it from the type string would offer completions the parser then rejects, so it
 * is written down rather than derived.
 */
object CatalogManifest {

    /**
     * Bumped when the SHAPE changes, never when the contents do.
     *
     * A new node is an ordinary content change and must not make an older reader refuse the file — that is
     * exactly the case this is meant to survive, since a client ships new nodes constantly and the plugin
     * on the other end is whatever the user happens to have installed.
     */
    const val SCHEMA = 1

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /** What a manifest says, once read. */
    class Manifest(
        val schema: Int,
        /** Free-form provenance — language version, client version, git sha. Never interpreted here. */
        val meta: Map<String, String>,
        val nodes: List<NodeDescriptor>,
        /** Node type → the shortest spelling the parser accepts. */
        val text: Map<String, String>,
        val types: List<TypeEntry>,
        /**
         * The enums the HOST declares — `Tab`, `Skill`.
         *
         * **Absent from a manifest written by an older client, and that has to be survivable**: this is a
         * content addition, not a shape change, so [SCHEMA] does not move and a reader that finds no
         * `enums` key gets an empty list rather than a parse failure. A plugin sitting on an older client
         * loses completion for host enums and nothing else.
         */
        val enums: List<EnumEntry> = emptyList(),
        /**
         * The records the HOST declares — `NpcRef`, `ItemRef`.
         *
         * Absent from an older manifest for [enums]' reason and survivable the same way. A reader that
         * finds no `records` key gets an empty list, and loses the ability to type `n.tile`.
         */
        val records: List<RecordEntry> = emptyList(),
    ) {

        /**
         * The catalogue this describes, with the host types it is written in terms of registered.
         *
         * **The registration is not separable, and it used to be.** There was an `installEnums()` beside
         * this that every caller had to remember, and the count of callers that remembered was zero — so a
         * manifest round-trip produced a catalogue whose pins named `Skill` and `ItemRef` while nothing had
         * ever heard of either. What that looks like downstream is not a missing enum: it is
         * `ItemRef has no fields` on a document that is perfectly correct, and `benchReal` quietly
         * benchmarking 21 of the corpus's 79 documents and reporting the other 58 as "skipped".
         *
         * A catalogue and the types its pins are declared with are one fact, so they arrive together.
         *
         * Descriptors only, still — a catalogue is signatures, and the implementations are the host's.
         * That is precisely why a manifest can round-trip one: there was never anything else in it.
         */
        fun toCatalog(exclude: Set<String> = emptySet()): NodeCatalog {
            install()
            return NodeCatalog(nodes.filterNot { it.type in builtinTypes }, exclude)
        }

        /**
         * Put this manifest's host types into the language's registries.
         *
         * Idempotent: both registries replace by name, so reading a manifest twice is the same as reading
         * it once, and a newer one supersedes an older one field for field.
         */
        fun install() {
            HostEnums.registerAll(enums.map { HostEnum(it.name, it.members, it.describe) })
            HostRecords.registerAll(records.map { it.toRecord() })
        }

        private val builtinTypes: Set<String> by lazy { NodeCatalog().types }
    }

    /** One type an author may name, with the one line the pickers show. */
    class TypeEntry(val name: String, val describe: String, val authorable: Boolean)

    /**
     * One HOST-declared enum: its name, what it is for, and its members in declaration order.
     *
     * [HostEnum.type] is deliberately NOT carried. It exists so `Skill` can attach itself to a pin type
     * the catalogue already uses, which is a fact about how the client wires its own pins — a consumer
     * offering completion needs the name and the members and would have nothing to do with the rest.
     */
    class EnumEntry(val name: String, val describe: String, val members: List<String>)

    /**
     * One HOST-declared record: its name, what it may be used as, and its fields.
     *
     * **The fields are a SHAPE and not an implementation**, which is the whole difference between a
     * manifest's record and a live one. `HostField.get` reads a value off the host's own object — a live
     * scene lookup, in the cases that matter — and there is no such object on the other side of a JSON
     * file. So a field read back from here TYPES and refuses to run, which is the same bargain the rest of
     * a headless load makes: a signature is enough to compile against, and touching the world without a
     * world has to fail loudly rather than answer zero.
     */
    class RecordEntry(
        val name: String,
        val describe: String,
        /** The type this may be used as — `NpcRef` widens to `EntityRef`. Null when it widens to nothing. */
        val widensTo: String?,
        val fields: List<FieldEntry>,
    ) {
        fun toRecord(): HostRecord = HostRecord(
            name = name,
            fields = fields.map { f ->
                HostField(f.name, TypeRef.parse(f.type), f.describe) {
                    throw IllegalStateException(
                        "'$name.${f.name}' came from a catalogue manifest, which carries the SHAPE of a " +
                            "host record and not its implementation — there is no client here to read it " +
                            "off. Bind a real node library, or fake this field in the test."
                    )
                }
            },
            describe = describe,
            widensTo = widensTo?.let { TypeRef.parse(it) },
        )
    }

    /** One field of a [RecordEntry]. */
    class FieldEntry(val name: String, val type: String, val describe: String)

    // ---- write ------------------------------------------------------------------------------------

    fun toJson(catalog: NodeCatalog, meta: Map<String, String> = emptyMap()): String =
        gson.toJson(encode(catalog, meta))

    private fun encode(catalog: NodeCatalog, meta: Map<String, String>): JsonObject {
        val names = Names(catalog)
        return JsonObject().apply {
            addProperty("schema", SCHEMA)
            add("meta", JsonObject().apply { meta.forEach { (k, v) -> addProperty(k, v) } })

            add("types", JsonArray().apply {
                Types.forVariables.forEach { t ->
                    add(JsonObject().apply {
                        addProperty("name", t.name)
                        addProperty("describe", t.describe)
                        addProperty("authorable", t.authorable)
                        addProperty("declarable", t.declarable)
                    })
                }
            })

            // Beside `types` rather than inside it: a type entry answers "may a variable be this", and a
            // host enum's interesting content is its MEMBERS, which a TypeEntry has nowhere to put.
            add("enums", JsonArray().apply {
                HostEnums.all.forEach { e ->
                    add(JsonObject().apply {
                        addProperty("name", e.name)
                        addProperty("describe", e.describe)
                        add("members", JsonArray().apply { e.members.forEach { add(it) } })
                    })
                }
            })

            // Beside `enums`, for its reason one step further: a host record's interesting content is its
            // FIELDS, and neither a `TypeEntry` nor an enum entry has anywhere to put a name-and-type pair.
            // Without this an editor knows `NpcRef` exists and cannot complete `n.` — which is most of what
            // making these types records was for.
            add("records", JsonArray().apply {
                HostRecords.all.forEach { r ->
                    add(JsonObject().apply {
                        addProperty("name", r.name)
                        addProperty("describe", r.describe)
                        // What it may be USED AS — `NpcRef` widens to `EntityRef`, so a completion that
                        // filters by the wanted type has to know the pair. Absent when it widens to nothing.
                        r.widensTo?.let { addProperty("widensTo", it.toString()) }
                        add("fields", JsonArray().apply {
                            r.fields.forEach { f ->
                                add(JsonObject().apply {
                                    addProperty("name", f.name)
                                    addProperty("type", f.type.toString())
                                    addProperty("describe", f.describe)
                                })
                            }
                        })
                    })
                }
            })

            add("nodes", JsonArray().apply {
                catalog.all.forEach { d ->
                    add(JsonObject().apply {
                        addProperty("type", d.type)
                        // Only for the nodes that can actually be WRITTEN. A syntactic node — a Branch, a
                        // comment box — has no spelling, and inventing one would put it in completion.
                        names.textNameOrNull(d.type)?.let { addProperty("text", it) }
                        addProperty("title", d.title)
                        addProperty("category", d.category)
                        addProperty("kind", d.kind.name)
                        addProperty("hostKind", d.hostKind.name)
                        d.host?.let { addProperty("host", it) }
                        // **The type an extension is WRITTEN ON, and it is not recoverable from the pins.**
                        // `item.isValid` and a plain `isValid(value: Item)` have identical inputs; the only
                        // thing that makes the first callable as `id.isValid()` is this field. Dropping it
                        // meant every host extension came back as an ordinary function, so a manifest-loaded
                        // catalogue reported `Item has no 'isValid'` about a script the live client
                        // compiles — and, because `core/items` uses it, that took every test document
                        // downstream of `core/items` with it.
                        d.receiver?.let { addProperty("receiver", it.toString()) }
                        if (d.summary.isNotEmpty()) addProperty("summary", d.summary)
                        add("inputs", pins(d.inputs))
                        add("outputs", pins(d.outputs))
                    })
                }
            })
        }
    }

    private fun pins(specs: List<PinSpec>): JsonArray = JsonArray().apply {
        specs.forEach { p ->
            add(JsonObject().apply {
                addProperty("name", p.name)
                // The persisted spelling, so it reads back through TypeRef.parse — `List<Item>` and all.
                addProperty("type", p.type.toString())
                p.default?.let { addProperty("default", it.toString()) }
                if (p.options.isNotEmpty()) add("options", JsonArray().also { a -> p.options.forEach(a::add) })
                if (p.typeChoice) addProperty("typeChoice", true)
                if (p.editable) addProperty("editable", true)
                if (p.doc.isNotEmpty()) addProperty("doc", p.doc)
            })
        }
    }

    // ---- read -------------------------------------------------------------------------------------

    fun fromJson(text: String): Manifest {
        val root = gson.fromJson(text, JsonObject::class.java)
        val schema = root.get("schema")?.asInt ?: 0
        require(schema <= SCHEMA) {
            "catalogue manifest schema $schema is newer than this reader understands (max $SCHEMA)"
        }

        val meta = LinkedHashMap<String, String>()
        root.getAsJsonObject("meta")?.entrySet()?.forEach { (k, v) ->
            if (!v.isJsonNull) meta[k] = v.asString
        }

        val types = root.getAsJsonArray("types")?.map { e ->
            val o = e.asJsonObject
            TypeEntry(
                name = o.get("name").asString,
                describe = o.get("describe")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                authorable = o.get("authorable")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
            )
        } ?: emptyList()

        val enums = root.getAsJsonArray("enums")?.map { e ->
            val o = e.asJsonObject
            EnumEntry(
                name = o.get("name").asString,
                describe = o.get("describe")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                members = o.getAsJsonArray("members")?.map { it.asString } ?: emptyList(),
            )
        } ?: emptyList()

        val records = root.getAsJsonArray("records")?.map { e ->
            val o = e.asJsonObject
            RecordEntry(
                name = o.get("name").asString,
                describe = o.get("describe")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                widensTo = o.get("widensTo")?.takeIf { !it.isJsonNull }?.asString,
                fields = o.getAsJsonArray("fields")?.map { f ->
                    val fo = f.asJsonObject
                    FieldEntry(
                        name = fo.get("name").asString,
                        type = fo.get("type").asString,
                        describe = fo.get("describe")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                    )
                } ?: emptyList(),
            )
        } ?: emptyList()

        val textNames = LinkedHashMap<String, String>()
        val nodes = root.getAsJsonArray("nodes")?.map { e ->
            val o = e.asJsonObject
            val type = o.get("type").asString
            o.get("text")?.takeIf { !it.isJsonNull }?.let { textNames[type] = it.asString }
            NodeDescriptor(
                type = type,
                title = o.get("title")?.takeIf { !it.isJsonNull }?.asString ?: type,
                category = o.get("category")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                kind = NodeKind.valueOf(o.get("kind").asString),
                inputs = decodePins(o.getAsJsonArray("inputs")),
                outputs = decodePins(o.getAsJsonArray("outputs")),
                host = o.get("host")?.takeIf { !it.isJsonNull }?.asString,
                hostKind = o.get("hostKind")?.takeIf { !it.isJsonNull }
                    ?.let { HostKind.valueOf(it.asString) } ?: HostKind.INLINE,
                receiver = o.get("receiver")?.takeIf { !it.isJsonNull }?.let { TypeRef.parse(it.asString) },
                summary = o.get("summary")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
            )
        } ?: emptyList()

        return Manifest(schema, meta, nodes, textNames, types, enums, records)
    }

    private fun decodePins(arr: JsonArray?): List<PinSpec> = arr?.map { e ->
        val o = e.asJsonObject
        PinSpec(
            name = o.get("name").asString,
            type = TypeRef.parse(o.get("type")?.takeIf { !it.isJsonNull }?.asString),
            // Defaults come back as STRINGS. A manifest is documentation of a signature, not a document
            // that runs — nothing reads a default off it to execute with, so re-deriving the original Int
            // or Boolean would be precision nobody spends. `Literals.of` is where that happens, for a
            // document, against the pin's real type.
            default = o.get("default")?.takeIf { !it.isJsonNull }?.asString,
            options = o.getAsJsonArray("options")?.map { it.asString } ?: emptyList(),
            typeChoice = o.get("typeChoice")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
            editable = o.get("editable")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
            doc = o.get("doc")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
        )
    } ?: emptyList()
}
