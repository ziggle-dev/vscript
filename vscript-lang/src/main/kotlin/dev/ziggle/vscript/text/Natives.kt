package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.vm.HostKind

/**
 * What the text front end knows about a host function: a **signature**, not a node.
 *
 * The whole of the catalogue's meaning to a language is "these functions exist, they take these
 * parameters, they give back this many values, and this one blocks". Everything else a
 * [dev.ziggle.vscript.model.NodeDescriptor] carries — a title, a category, a pin's position, the options an
 * editor offers, whether to draw a field on an output — is about drawing a node, and the text surface has
 * never had a use for any of it.
 *
 * Separating them is what lets `PinSpec` stop being the language's problem. The projection from the
 * catalogue lands in phase 3 (`docs/TEXT_FRONTEND.md`); until then a caller supplies these directly, which
 * is also what every test wants.
 */
class NativeParam(
    val name: String,
    val type: TypeRef = TypeRef.WILDCARD,
    /**
     * Used when the call omits this argument.
     *
     * [hasDefault] rather than "default is null", because null IS a legitimate default for an optional
     * parameter and the two cases have to stay distinguishable — an omitted required argument is an error
     * and an omitted optional one is not.
     */
    val default: Any? = null,
    val hasDefault: Boolean = false,
)

/**
 * One callable host function.
 *
 * [host] is the name the [dev.ziggle.vscript.vm.HostRegistry] binds at load, and is deliberately not
 * assumed to equal [name]: the language may call it `bank.count` while the registry knows it as
 * `bank.bankCount`, and pinning them together would make renaming one a breaking change to the other.
 *
 * [kind] decides the opcode and nothing else asks: `INLINE` compiles to `CALL` and runs on the client
 * thread, `BLOCKING` compiles to `ACT` and is handed to the actuator drain. An author never chooses and
 * so cannot get it wrong — see `HostKind`.
 */
class NativeFn(
    val name: String,
    val params: List<NativeParam> = emptyList(),
    /**
     * The types this hands back, in order — a LIST because the language has always had several.
     *
     * A count would have been enough for the skeleton, which only needed to know how many registers the
     * call writes. It is not enough for a type checker, and the two facts are the same fact: how many
     * results there are is how long this is.
     */
    val results: List<NativeParam> = listOf(NativeParam(RESULT)),
    val kind: HostKind = HostKind.INLINE,
    val host: String = name,
    /**
     * Its arguments come from its own TEXT, so no signature can list them.
     *
     * `text("laps: {n}", n: laps)` — the placeholders in the template are the parameters, which is why the
     * node declares arity "it depends" and why the VM takes the count from the bytecode. A checker that
     * insisted on the declared list would refuse every use of the one node whose list is written in its
     * first argument.
     */
    val fromTemplate: Boolean = false,
    /**
     * What this is written ON, when it is an extension — `fn INT.toItem() -> Item`.
     *
     * ### Why a host needs this
     *
     * A document has been able to declare `fn LIST<T>.first(self)` since generics landed. A HOST could
     * not, and the gap showed: `toItem`, `toObject`, `isValid` and four more were hard-coded into
     * `Intrinsics` — one game's vocabulary in the language's own intrinsic set — because a node pack had
     * no way to spell them. This is that way.
     *
     * ### The receiver is argument zero
     *
     * Exactly as an [ExtensionCallee] describes for a document's own: "the receiver is the first argument
     * and the rest follow, which is what makes an extension an ordinary function once it has been found".
     * So [params] lists the receiver FIRST and the arguments after it, [signature] needs no special case,
     * and the compiler emits an ordinary host call.
     *
     * ### It is not callable by its bare name
     *
     * `toItem(n)` must not also work. That is the rule a document extension already follows — binding it
     * as a plain name "would let `first(list: xs)` and `xs.first()` be two spellings of one function only
     * one of which typechecks" — and [NativeTable] enforces it by keeping these out of the name index.
     */
    val receiver: TypeRef? = null,
) {
    /** This function as the resolver checks call sites against — see [Signature]. */
    val signature: Signature by lazy {
        Signature(
            name,
            params.map { Param(it.name, it.type, it.default, it.hasDefault) },
            results.map { Param(it.name, it.type) },
        )
    }

    /** True when this is written on a value rather than called by name. */
    val isExtension: Boolean get() = receiver != null
}

/**
 * Every host function a document may call, by the name it is called by.
 *
 * **Exactly the name, and no short alias.** An early version also registered the last dotted segment when
 * it was unambiguous, which seemed harmless and was not: `Names` already computes the shortest spelling
 * that could only mean one node, honouring what a node CLAIMED and qualifying the rest — `tile(x, y, p)`
 * is the TILE literal, so the drawing node is spelled `drawTile` and never `tile`. Aliasing behind its
 * back handed `tile` to the drawing node, and a corpus full of tile literals started reporting that a
 * Color pin was being given an INT.
 */
/** What an unnamed single result is called — the graph's own name for the pin. */
const val RESULT = "Result"

/**
 * Results that carry no name of their own.
 *
 * A destructuring can bind by name, so a result HAS a name; this is for the ones that never had a better
 * one than the graph's `Result`, and for tests where the name is not what is under test.
 */
fun outs(vararg types: TypeRef): List<NativeParam> = types.map { NativeParam(RESULT, it) }

class NativeTable(fns: List<NativeFn> = emptyList()) {

    private val byName = LinkedHashMap<String, NativeFn>()

    /**
     * Host extensions, by the NAME of the type they are written on, then by their own name.
     *
     * A separate index rather than a flag on [byName], because an extension must not answer to its bare
     * name — see [NativeFn.receiver]. Keyed by type name for the same reason the resolver keys a
     * document's extensions that way: it is what a receiver's type and its supertypes can both be looked
     * up by.
     */
    private val byReceiver = LinkedHashMap<String, LinkedHashMap<String, NativeFn>>()

    init {
        for (fn in fns) register(fn)
    }

    fun register(fn: NativeFn): NativeTable {
        val on = fn.receiver
        if (on == null) {
            require(byName.put(fn.name, fn) == null) { "duplicate native '${fn.name}'" }
        } else {
            val onType = byReceiver.getOrPut(on.name) { LinkedHashMap() }
            require(onType.put(fn.name, fn) == null) { "duplicate native '${on.name}.${fn.name}'" }
        }
        return this
    }

    /** The host extension called [name] written on [receiverType], or null. */
    fun extension(receiverType: String, name: String): NativeFn? = byReceiver[receiverType]?.get(name)

    /** Every host extension written on [receiverType] — for completion and for diagnostics. */
    fun extensionsOn(receiverType: String): Collection<NativeFn> =
        byReceiver[receiverType]?.values ?: emptyList()

    operator fun get(name: String): NativeFn? = byName[name]

    /**
     * Every type name the catalogue MENTIONS but does not describe — `Row`, and anything like it.
     *
     * A node may take or hand back a type that is only ever a handle: `rowText(…)` makes a `Row` and
     * `panel(rows: …)` consumes one, and nothing in between needs to know what is inside it. A script
     * naming it — `var rows: LIST<Row> = []` — is naming something perfectly real, and the resolver
     * refused it because the only types it knew were the prelude's and the document's.
     *
     * Gathered from the signatures rather than declared anywhere: the catalogue is the only thing that
     * knows, and a second list would be one more thing to keep in step.
     */
    val types: Set<String> by lazy {
        val out = LinkedHashSet<String>()
        fun walk(t: TypeRef) {
            if (t.builtin == null && t.name.isNotBlank() && !t.variable) out += t.name
            t.args.forEach { walk(it) }
        }
        for (fn in byName.values) {
            fn.params.forEach { walk(it.type) }
            fn.results.forEach { walk(it.type) }
        }
        out
    }

    /** Every host function, extensions included — what a catalogue copy or a manifest walks. */
    val all: List<NativeFn> get() = byName.values + byReceiver.values.flatMap { it.values }
}
