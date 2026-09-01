package dev.ziggle.vscript.json

import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.StructType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.vm.StructValue
import dev.ziggle.vscript.vm.VmError

/**
 * What a decoded JSON value has to look like — the record declaration, in a form the VM can walk.
 *
 * **Built by the compiler, carried in the constant pool.** A [StructValue] holds field *names* and nothing
 * about their types, which is deliberate — see its class note — so a decoder handed only a target record
 * could not tell a `LIST<Item>` from a `LIST<STRING>` and would have to guess from the data. Guessing is the
 * one answer that must not be given: a file where every `qty` happened to be absent would decode to a record
 * with different types than the same file with one filled in.
 *
 * So the schema is resolved where the declaration is visible — at compile time, from `type` — and baked as a
 * constant. This is the same trick `vscript.enumField` uses (the member names and one column, both baked in
 * declaration order) and for the same reason: the shape is known when the graph is compiled, so pay for it
 * then.
 *
 * **Records are held in a side table and referred to by name** ([Shape.Ref]), so a record that mentions
 * itself — `type Node { kids: LIST<Node> }` — is a finite constant rather than an infinite one.
 */
class JsonSchema(val root: Shape, val records: Map<String, Shape.Record>) {

    /**
     * [value], read as [root]. Throws [VmError] naming the path when the data does not match.
     *
     * **Nothing in gives nothing out**, at the ROOT only. `readJson` on a file that is not there hands
     * over no document, and the type of that cast says so (`Layout?` — see the CAST branch of
     * `resolveNode`), so the null has to travel rather than being reported as "expected an object". A null
     * *inside* a document is a different question and still answers to the field's own declaration: it is
     * accepted where the field is optional and refused where it is not.
     */
    fun decode(value: Any?): Any? = if (value == null) null else read(root, value, "")

    override fun toString(): String = "JsonSchema($root)"

    override fun equals(other: Any?): Boolean =
        other is JsonSchema && other.root == root && other.records == records

    override fun hashCode(): Int = root.hashCode() * 31 + records.hashCode()

    // ---- the shapes ---------------------------------------------------------------------------------

    sealed class Shape {

        /** A `Json` field: the parsed subtree, handed over untouched. The escape hatch from the schema. */
        object Raw : Shape() {
            override fun toString() = "Json"
        }

        /** An `Any` field: same as [Raw] at run time, but says something different in the declaration. */
        object Anything : Shape() {
            override fun toString() = "Any"
        }

        /** `INT`, `FLOAT`, `STRING`, `BOOL`, and the game ids that share their run-time form. */
        class Scalar(val kind: Kind) : Shape() {
            override fun toString() = kind.name
            override fun equals(other: Any?) = other is Scalar && other.kind == kind
            override fun hashCode() = kind.hashCode()
        }

        class ListOf(val element: Shape) : Shape() {
            override fun toString() = "LIST<$element>"
            override fun equals(other: Any?) = other is ListOf && other.element == element
            override fun hashCode() = element.hashCode() * 31 + 1
        }

        /** A JSON object with keys the declaration does not name — `MAP<STRING, V>`. */
        class MapOf(val value: Shape) : Shape() {
            override fun toString() = "MAP<STRING, $value>"
            override fun equals(other: Any?) = other is MapOf && other.value == value
            override fun hashCode() = value.hashCode() * 31 + 2
        }

        /** A closed set of names. Decoded from a string, checked against the members. */
        class Choice(val type: String, val members: List<String>) : Shape() {
            override fun toString() = type
            override fun equals(other: Any?) = other is Choice && other.type == type && other.members == members
            override fun hashCode() = type.hashCode() * 31 + members.hashCode()
        }

        /** Named in place of a [Record], so a self-referential type is a finite constant. */
        class Ref(val type: String) : Shape() {
            override fun toString() = type
            override fun equals(other: Any?) = other is Ref && other.type == type
            override fun hashCode() = type.hashCode() * 31 + 3
        }

        class Record(val type: String, val fields: List<Field>) : Shape() {
            override fun toString() = fields.joinToString(", ", "$type{", "}") { "${it.name}: ${it.shape}" }
            override fun equals(other: Any?) = other is Record && other.type == type && other.fields == fields
            override fun hashCode() = type.hashCode() * 31 + fields.hashCode()
        }

        /** A value that may be absent or `null`. Wraps whatever it is optional OF. */
        class Optional(val of: Shape) : Shape() {
            override fun toString() = "$of?"
            override fun equals(other: Any?) = other is Optional && other.of == of
            override fun hashCode() = of.hashCode() * 31 + 4
        }
    }

    enum class Kind { INT, FLOAT, STRING, BOOL }

    /**
     * One field of a record.
     *
     * [key] is what to look for in the JSON and [name] is the field it fills — the same pair `as`'s rename
     * block has always written, which is why a JSON key that is not a legal vs name needs no new syntax:
     * `as Doc { itemCount: "item_count" }`.
     */
    class Field(val name: String, val key: String, val shape: Shape) {
        override fun equals(other: Any?): Boolean =
            other is Field && other.name == name && other.key == key && other.shape == shape

        override fun hashCode(): Int = (name.hashCode() * 31 + key.hashCode()) * 31 + shape.hashCode()

        override fun toString(): String = if (name == key) "$name: $shape" else "$name <- \"$key\": $shape"
    }

    // ---- decoding -----------------------------------------------------------------------------------

    private fun read(shape: Shape, value: Any?, path: String): Any? = when (shape) {
        is Shape.Raw, is Shape.Anything -> value
        is Shape.Optional -> if (value == null) null else read(shape.of, value, path)
        is Shape.Ref -> read(
            records[shape.type] ?: throw VmError("${at(path)}no schema for '${shape.type}'"),
            value, path,
        )
        is Shape.Scalar -> scalar(shape.kind, value, path)
        is Shape.Choice -> {
            val s = value as? String ?: wrong(path, "one of ${shape.members.joinToString("/")}", value)
            shape.members.firstOrNull { it.equals(s, true) }
                ?: throw VmError("${at(path)}'$s' is not a ${shape.type} — the members are ${shape.members.joinToString(", ")}")
        }
        // **`null` reads as empty, for a container and only for a container.**
        //
        // Not the zero-filling the record branch below refuses, and the difference is that a container has
        // an unambiguous empty: `null`, `[]` and `{}` all say "nothing in it", which is not true of a
        // number or a name. Every JSON writer in the world emits one of the three, and a reader that
        // accepted two of them would be picking a favourite.
        //
        // It is also what heals a file this language itself wrote. A `MAP` variable's starting value was
        // null rather than `{}` (see `GraphCompiler.zeroOf`), so any state saved before its initialiser ran
        // holds `"budgets": null` — and refusing that on the way back in stopped the script every wake,
        // for ever, over a map that was empty and was always going to be empty.
        is Shape.ListOf -> {
            val list = if (value == null) emptyList<Any?>() else value as? List<*> ?: wrong(path, "a list", value)
            ArrayList<Any?>(list.size).also { out ->
                list.forEachIndexed { i, v -> out.add(read(shape.element, v, "$path[$i]")) }
            }
        }
        is Shape.MapOf -> {
            val map = if (value == null) emptyMap<Any?, Any?>() else value as? Map<*, *> ?: wrong(path, "an object", value)
            LinkedHashMap<Any?, Any?>(map.size).also { out ->
                for ((k, v) in map) {
                    val key = k?.toString().orEmpty()
                    out[key] = read(shape.value, v, if (path.isEmpty()) key else "$path.$key")
                }
            }
        }
        is Shape.Record -> {
            val map = value as? Map<*, *> ?: wrong(path, "an object to read as ${shape.type}", value)
            // Extra keys are DROPPED, exactly as a record-to-record `as` drops the fields the target does
            // not name. The source may be wider and may never be narrower — one rule, both directions.
            val values = arrayOfNulls<Any?>(shape.fields.size)
            shape.fields.forEachIndexed { i, f ->
                val here = if (path.isEmpty()) f.key else "$path.${f.key}"
                val present = map.entries.firstOrNull { it.key?.toString() == f.key }
                values[i] = when {
                    present != null -> read(f.shape, present.value, here)
                    f.shape is Shape.Optional -> null
                    // Nothing is zero-filled — the rule `as` already states. A field you forgot would look
                    // exactly like one the file legitimately omitted, so the absence is named instead.
                    else -> throw VmError(
                        "${at(here)}${shape.type}.${f.name} needs \"${f.key}\", and this object has no such key" +
                            " — declare the field as ${f.shape}? if it may be missing"
                    )
                }
            }
            StructValue(shape.type, shape.fields.map { it.name }, values)
        }
    }

    private fun scalar(kind: Kind, value: Any?, path: String): Any? = when (kind) {
        Kind.STRING -> value as? String ?: wrong(path, "a string", value)
        Kind.BOOL -> value as? Boolean ?: wrong(path, "true or false", value)
        Kind.FLOAT -> (value as? Number)?.toDouble() ?: wrong(path, "a number", value)
        Kind.INT -> {
            val n = value as? Number ?: wrong(path, "a whole number", value)
            when (n) {
                is Int, is Long -> n
                // A whole-valued Double is the ordinary case — plenty of writers emit `3.0` for an int —
                // and truncating one with a fraction would lose data silently, which is the failure this
                // language spends its error messages avoiding.
                else -> n.toDouble().let {
                    if (it == Math.floor(it) && !it.isInfinite()) {
                        val l = it.toLong()
                        if (l in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) l.toInt() else l
                    } else {
                        throw VmError("${at(path)}expected a whole number, found $it")
                    }
                }
            }
        }
    }

    private fun wrong(path: String, expected: String, got: Any?): Nothing =
        throw VmError("${at(path)}expected $expected, found ${describe(got)}")

    private fun at(path: String): String = if (path.isEmpty()) "" else "$path: "

    private fun describe(v: Any?): String = when (v) {
        null -> "nothing"
        is String -> "the text \"$v\""
        is Boolean -> "$v"
        is Number -> "the number $v"
        is List<*> -> "a list of ${v.size}"
        is Map<*, *> -> "an object with ${v.size} keys"
        else -> dev.ziggle.vscript.vm.Values.typeName(v)
    }

    // ---- building -----------------------------------------------------------------------------------

    companion object {

        /**
         * The schema for reading [target], or a message saying why that type cannot come from JSON.
         *
         * Resolution is by NAME through [types] and [enums], which is how every other type lookup in this
         * language works, and it is what makes a record declared in an imported document decode as readily
         * as a local one — the caller passes a lookup that sees what the document sees.
         *
         * The failure is a STRING rather than a throw because both callers want to place it: the validator
         * turns it into an `Issue` on the pin, and the compiler asserts it never happens.
         */
        fun of(
            target: TypeRef,
            types: (String) -> StructType?,
            enums: (String) -> List<String>?,
        ): Result {
            val records = LinkedHashMap<String, Shape.Record>()
            val building = HashSet<String>()
            val root = try {
                shapeOf(target, types, enums, records, building)
            } catch (e: Unreadable) {
                return Result(null, e.message.orEmpty())
            }
            return Result(JsonSchema(root, records), null)
        }

        /** What [of] answers with: a schema, or the reason there is none. Exactly one is null. */
        class Result(val schema: JsonSchema?, val problem: String?)

        private class Unreadable(message: String) : RuntimeException(message)

        private fun shapeOf(
            t: TypeRef,
            types: (String) -> StructType?,
            enums: (String) -> List<String>?,
            records: MutableMap<String, Shape.Record>,
            building: MutableSet<String>,
        ): Shape {
            if (t.optional) {
                return Shape.Optional(shapeOf(t.required(), types, enums, records, building))
            }
            // The containers first: nothing a document declares can be called LIST or MAP, because
            // `TypeRef.named` interns both against their `PinType`.
            when (t.builtin) {
                PinType.LIST -> {
                    val of = t.of
                        ?: throw Unreadable("a plain LIST has no element type to read — say LIST<what>")
                    return Shape.ListOf(shapeOf(of, types, enums, records, building))
                }
                PinType.MAP -> {
                    val key = t.args.getOrNull(0)
                    val value = t.args.getOrNull(1)
                        ?: throw Unreadable("a plain MAP has no value type to read — say MAP<STRING, what>")
                    // JSON keys are strings and nothing else is, so a MAP<TILE, …> could be written out but
                    // never read back. Refusing beats decoding into keys that are not the type declared.
                    if (key != null && key.builtin != PinType.STRING && key.builtin != PinType.WILDCARD) {
                        throw Unreadable("JSON keys are text, so a MAP read from JSON must be MAP<STRING, …>")
                    }
                    return Shape.MapOf(shapeOf(value, types, enums, records, building))
                }
                else -> Unit
            }
            val name = t.name
            // **A DECLARED type wins over the built-in kind of the same name, and this order is the whole
            // of what makes `type Item { … }` decode.** `TypeRef.named` interns case-insensitively against
            // the `PinType` constants, so a document's own `Item`, `Object`, `NPC` or `Skill` arrives here
            // carrying `builtin = PinType.ITEM` and friends — reading the enum first turned every field of
            // a record called `Item` into "expected a whole number, found an object". It is also the rule
            // stated everywhere else this question comes up: `Types.register` replaces rather than refuses,
            // and `ImportScope.visibleTypes` puts the HOST's records AFTER the document's own.
            types(name)?.let { declared ->
                // Already built, or being built — the second is a record that mentions itself, and naming
                // it is exactly how that terminates.
                if (name in building || records.containsKey(declared.name)) return Shape.Ref(declared.name)
                building += name
                val fields = declared.fields.map { f ->
                    Field(f.name, f.name, shapeOf(f.type, types, enums, records, building))
                }
                building -= name
                records[declared.name] = Shape.Record(declared.name, fields)
                return Shape.Ref(declared.name)
            }
            enums(name)?.let { return Shape.Choice(name, it) }
            if (name.equals("Json", true)) return Shape.Raw
            // A HOST type that is a nominal name over something simpler reads as that simpler thing.
            // `Item` is an INT that refuses to be confused with an npc id, and JSON has no opinion about
            // the refusing — so the conversion is exact, which is the same reasoning that applied while
            // it was a builtin. Asked before the builtins, because a host record has no builtin at all.
            dev.ziggle.vscript.model.HostRecords.of(t)?.over?.let { return shapeOf(it, types, enums, records, building) }
            when (t.builtin) {
                PinType.INT -> return Shape.Scalar(Kind.INT)
                PinType.FLOAT -> return Shape.Scalar(Kind.FLOAT)
                PinType.STRING -> return Shape.Scalar(Kind.STRING)
                PinType.BOOL -> return Shape.Scalar(Kind.BOOL)
                PinType.WILDCARD -> return Shape.Anything
                else -> Unit
            }
            throw Unreadable(
                "a $name cannot be read from JSON — it has no written form. Records, lists, maps, " +
                    "numbers, text, true/false, a choice and Json itself can"
            )
        }
    }

    /**
     * A copy with the root record's keys renamed — `as Doc { itemCount: "item_count" }`.
     *
     * Only the ROOT, deliberately. A rename is written beside the type it applies to, and a clause that
     * reached into nested records would be renaming fields of a type the reader cannot see from here. A
     * nested record's odd key is renamed where that record is read, or by giving the field the JSON's own
     * spelling.
     */
    fun renamed(renames: Map<String, String>): JsonSchema {
        if (renames.isEmpty()) return this
        fun apply(shape: Shape): Shape = when (shape) {
            is Shape.Record -> Shape.Record(shape.type, shape.fields.map { f ->
                renames.entries.firstOrNull { it.key.equals(f.name, true) }
                    ?.let { Field(f.name, it.value, f.shape) } ?: f
            })
            is Shape.Optional -> Shape.Optional(apply(shape.of))
            else -> shape
        }
        // The root is normally a Ref into the table, so the rename lands on the table entry it names.
        val rootRecord = (root as? Shape.Ref)?.let { records[it.type] }
        if (rootRecord != null) {
            return JsonSchema(root, records + (rootRecord.type to apply(rootRecord) as Shape.Record))
        }
        return JsonSchema(apply(root), records)
    }
}
