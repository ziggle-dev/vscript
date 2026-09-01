package dev.ziggle.vscript.nodes

import dev.ziggle.vscript.model.HostEnum
import dev.ziggle.vscript.model.HostEnums
import dev.ziggle.vscript.model.HostRecord
import dev.ziggle.vscript.model.HostRecords
import dev.ziggle.vscript.model.NodeDescriptor
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.vm.HostFn
import dev.ziggle.vscript.vm.HostFunction
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry

/**
 * A node and its implementation, defined together.
 *
 * They cannot be defined apart. A node's pins say how many arguments its host function takes and how many
 * values it returns, and the VM reads those counts off the *bytecode* — so a descriptor that gains a pin
 * while its function keeps its old signature does not fail to compile, it reads a register that was never
 * written. Pairing them means the counts are derived from one source and can't drift.
 */
class NodeDef(val descriptor: NodeDescriptor, val fn: HostFn)

/**
 * The game-facing half of the catalog: descriptors for the editor, host functions for the VM.
 *
 * Layer 1 of the plan's hybrid catalog — hand-authored, because these are the verbs the whole thing is for
 * and their pin shapes are a design decision rather than something derivable from a Java signature. The
 * generated layer over the SDK surface comes later and conforms to what this establishes.
 */
class NodeLibrary(
    val defs: List<NodeDef>,
    /**
     * The enums this library contributes to the LANGUAGE — `Tab`, and whatever a later library adds.
     *
     * **Registered on construction, not on [install], and that is deliberate.** `install` binds host
     * FUNCTIONS into a VM registry, so it only runs where there is a VM to bind into; a headless build
     * writing the catalogue manifest never calls it. An enum is a fact about the library's SIGNATURES —
     * the same class of thing as a pin type — so it has to be true the moment the library exists, or a
     * manifest generated without a running client would describe pins whose type it never mentions.
     *
     * The registry replaces by name, so building a library twice is idempotent rather than cumulative.
     */
    val enums: List<HostEnum> = emptyList(),
    /**
     * The records this library contributes — `Entity`, `ItemRef`.
     *
     * Registered on construction for [enums]' reason and it applies more strongly here: a record's FIELDS
     * are part of the signature surface (`e.tile` has to typecheck), so a manifest or a headless compile
     * with no VM still has to know them.
     */
    val records: List<HostRecord> = emptyList(),
) {

    init {
        HostEnums.registerAll(enums)
        HostRecords.registerAll(records)
    }

    val descriptors: List<NodeDescriptor> get() = defs.map { it.descriptor }

    /**
     * Bind every definition into [hosts].
     *
     * Arity and result count come from the descriptor's pins — never passed in. That is the point of
     * [NodeDef]: the one place both halves are visible is the only place allowed to state them.
     */
    fun install(hosts: HostRegistry, valueOut: OutputConverter = OutputConverter.NONE): HostRegistry {
        // **A field of a host record is an ordinary host function.** `e.tile` compiles to a call to
        // `Entity.tile`, so the accessors have to be bound here beside the verbs — the compiler resolved
        // the name at compile time and `HostRegistry.bind` will refuse the chunk at load if nothing
        // answers to it, which is the loud failure we want rather than a null much later.
        for (record in records) {
            for (field in record.fields) {
                hosts.register(
                    HostFunction(
                        name = record.hostFor(field.name),
                        kind = HostKind.INLINE,
                        arity = 1,
                        results = 1,
                        // **Through the same converter the node outputs use.** A field typed `Tile`
                        // has to arrive as the language's Tile record, exactly as `Entity Info`'s Tile
                        // OUTPUT does — the host's own `dev.ziggle.api.Tile` means nothing here. Missing
                        // this makes `e.tile` hand back an object the language cannot read, while
                        // `entityInfo(e).Tile` works, and the two disagreeing is the whole failure mode
                        // this module keeps designing against.
                        fn = { args -> valueOut.convert(field.type, field.get(args.getOrNull(0))) },
                    )
                )
            }
        }
        for (def in defs) {
            val name = def.descriptor.host ?: continue
            hosts.register(
                HostFunction(
                    name = name,
                    kind = def.descriptor.hostKind,
                    arity = def.descriptor.dataInputs.size,
                    results = def.descriptor.dataOutputs.size,
                    fn = converting(def, valueOut),
                )
            )
        }
        return hosts
    }

    /**
     * [def]'s function, with its results put into the form the LANGUAGE expects for their declared pins.
     *
     * A tile is a record to a script — `t.plane` is an ordinary field read — and a host hands back whatever
     * its own API uses. Somewhere has to be the seam, and this is the only place that sees both a node's
     * declared output types and its implementation, so it is the only place that can do it once rather
     * than at every node that returns one.
     *
     * Untouched when the converter has nothing to say about any of a node's outputs, which is all but a
     * few dozen of them — so the overwhelming majority of host calls go through exactly the code they did
     * before, with no wrapper in the way.
     */
    private fun converting(def: NodeDef, valueOut: OutputConverter): HostFn {
        val outs = def.descriptor.dataOutputs
        if (outs.none { handlesDeep(it.type, valueOut) }) return def.fn
        val single = outs.size <= 1
        return HostFn { args ->
            val raw = def.fn.invoke(args)
            // A promise is converted when it completes, not here — see [dev.ziggle.vscript.vm.HostAwait].
            if (raw is dev.ziggle.vscript.vm.HostAwait) return@HostFn raw
            if (single) {
                convertDeep(outs.firstOrNull()?.type ?: return@HostFn raw, raw, valueOut)
            } else {
                // A multi-result node hands back an array, one entry per output pin, so each is converted
                // against the pin it belongs to rather than all of them against the first.
                val values = raw as? Array<*> ?: return@HostFn raw
                Array<Any?>(values.size) { i ->
                    outs.getOrNull(i)?.type?.let { convertDeep(it, values[i], valueOut) } ?: values[i]
                }
            }
        }
    }

    /**
     * Does [t] need converting, counting a LIST of something that does?
     *
     * **The list case is the language's business, not the host's.** A converter knows what an
     * `dev.ziggle.api.Tile` is; that a `LIST<TILE>` is a list whose elements are tiles is a fact about the
     * TYPE SYSTEM, and asking every host to reimplement it would be asking each of them to get the same
     * traversal right.
     *
     * Missing it made a node returning `LIST<TILE>` hand back raw host tiles while a node returning one
     * TILE handed back a record — so `t.x` worked on the second and failed on the first with "GETFIELD on
     * Tile, expected a record", a runtime error from a graph the compiler had just accepted.
     */
    private fun handlesDeep(t: dev.ziggle.vscript.model.TypeRef, valueOut: OutputConverter): Boolean =
        valueOut.handles(t) || (t.isList && t.of?.let { valueOut.handles(it) } == true)

    /** [OutputConverter.convert], applied through a list to its elements. */
    private fun convertDeep(
        t: dev.ziggle.vscript.model.TypeRef,
        value: Any?,
        valueOut: OutputConverter,
    ): Any? {
        if (!t.isList) return valueOut.convert(t, value)
        val element = t.of ?: return value
        if (!valueOut.handles(element)) return value
        // Anything that is not a list passes through: a host that answered with something else has a
        // problem this is not the place to report.
        val list = value as? List<*> ?: return value
        return list.map { valueOut.convert(element, it) }
    }
}

/**
 * How a host's own value becomes the one the language uses for that pin type.
 *
 * Supplied by the HOST, because the conversion needs to know the host's types and this module must not:
 * an `dev.ziggle.api.Tile` means nothing here, and teaching the language about it would put the client's API
 * in the language's dependencies to save the client a dozen lines.
 */
interface OutputConverter {
    /** Whether [type] is one this converter has anything to say about — checked once, at install. */
    fun handles(type: dev.ziggle.vscript.model.TypeRef): Boolean

    fun convert(type: dev.ziggle.vscript.model.TypeRef, value: Any?): Any?

    companion object {
        /** Everything through unchanged — what a caller with no host types of its own means. */
        val NONE: OutputConverter = object : OutputConverter {
            override fun handles(type: dev.ziggle.vscript.model.TypeRef) = false
            override fun convert(type: dev.ziggle.vscript.model.TypeRef, value: Any?) = value
        }
    }
}

/** The exec pin every impure node carries in and out. Named once so they are literally the same shape. */
private val EXEC_IN = PinSpec("Exec", PinType.EXEC)
private val EXEC_OUT = PinSpec("Exec", PinType.EXEC)

/**
 * A **query**: a pure node that reads live state on the client thread.
 *
 * Public rather than `internal` since the language became its own module: the node LIBRARIES that use these
 * — `GameNodes`, `DrawNodes` — are the host's, and stayed with the client. This is the DSL for declaring a
 * node and its implementation together; the verbs themselves are none of the language's business.
 *
 * Pure means it has no exec pins and is re-evaluated wherever it is read, which is right for a read and
 * wrong for anything else — two reads of "am I moving" a second apart should give two answers.
 *
 * [HostKind.INLINE] means it runs on the client thread with no marshalling, so what it returns is this
 * frame's truth rather than a snapshot of some earlier one. Only put genuinely cheap reads here.
 */
fun query(
    type: String,
    title: String,
    category: String,
    summary: String,
    inputs: List<PinSpec> = emptyList(),
    output: PinSpec,
    body: (Array<Any?>) -> Any?,
): NodeDef = NodeDef(
    NodeDescriptor(
        type = type,
        title = title,
        category = category,
        kind = NodeKind.PURE,
        inputs = inputs,
        outputs = listOf(output),
        host = type,
        hostKind = HostKind.INLINE,
        summary = summary,
    ),
    body,
)

/**
 * A **query with several outputs** — one read, several pins.
 *
 * Not sugar over [query]: splitting a compound read into one node per field means each field is read
 * independently, so two pins of the same node can describe two different moments. For "what did I last
 * click" that is a bug you would have to be unlucky to see and could never reproduce — the id from one
 * click and the age from the next. One call, one snapshot, pins taken off it.
 *
 * [body] returns an `Array<Any?>` positionally matching [outputs] (the VM unpacks it — see
 * `Interpreter.writeResults`).
 */
fun queryMulti(
    type: String,
    title: String,
    category: String,
    summary: String,
    inputs: List<PinSpec> = emptyList(),
    outputs: List<PinSpec>,
    body: (Array<Any?>) -> Array<Any?>,
): NodeDef = NodeDef(
    NodeDescriptor(
        type = type,
        title = title,
        category = category,
        kind = NodeKind.PURE,
        inputs = inputs,
        outputs = outputs,
        host = type,
        hostKind = HostKind.INLINE,
        summary = summary,
    ),
    body,
)

/**
 * A **command**: an impure node that changes editor/overlay state, not the avatar.
 *
 * Exec-sequenced like an [action] — order matters, and it happens once where it sits — but run INLINE
 * rather than on the actuator drain. The drain exists to serialise things the avatar does, one at a time,
 * because the game can only be doing one of them; writing a marker into a store is not one of those. Put
 * on the drain it would queue behind a walk and paint a highlight seconds after the thing it described
 * stopped being true.
 */
fun command(
    type: String,
    title: String,
    category: String,
    summary: String,
    inputs: List<PinSpec> = emptyList(),
    outputs: List<PinSpec> = emptyList(),
    body: (Array<Any?>) -> Any?,
): NodeDef = NodeDef(
    NodeDescriptor(
        type = type,
        title = title,
        category = category,
        kind = NodeKind.IMPURE,
        inputs = listOf(EXEC_IN) + inputs,
        outputs = listOf(EXEC_OUT) + outputs,
        host = type,
        hostKind = HostKind.INLINE,
        summary = summary,
    ),
    body,
)

/**
 * An **action**: an impure node that does something to the game, on the actuator drain.
 *
 * Exec pins are added here rather than declared per node, so every action is sequenced the same way and no
 * one can accidentally define one that isn't.
 *
 * Actions have no `Success` output by design. The verbs behind them either do the thing or throw, and a
 * throw is worth more than a boolean nobody wires up: the fiber stops **on the node that failed** with its
 * inputs still readable, which is exactly where you want to be standing when you ask why.
 */
fun action(
    type: String,
    title: String,
    category: String,
    summary: String,
    inputs: List<PinSpec> = emptyList(),
    outputs: List<PinSpec> = emptyList(),
    body: (Array<Any?>) -> Any?,
): NodeDef = NodeDef(
    NodeDescriptor(
        type = type,
        title = title,
        category = category,
        kind = NodeKind.IMPURE,
        inputs = listOf(EXEC_IN) + inputs,
        outputs = listOf(EXEC_OUT) + outputs,
        host = type,
        hostKind = HostKind.BLOCKING,
        summary = summary,
    ),
    body,
)
