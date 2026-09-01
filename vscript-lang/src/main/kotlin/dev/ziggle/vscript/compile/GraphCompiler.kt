package dev.ziggle.vscript.compile

import dev.ziggle.vscript.lang.EntryKind
import dev.ziggle.vscript.lang.Span
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphSource
import dev.ziggle.vscript.model.ImportClosure
import dev.ziggle.vscript.model.ImportScope
import dev.ziggle.vscript.model.Node
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeDescriptor
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.QualName
import dev.ziggle.vscript.model.key
import dev.ziggle.vscript.vm.Chunk
import dev.ziggle.vscript.vm.ChunkBuilder
import dev.ziggle.vscript.vm.FunctionValue
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.Op
import dev.ziggle.vscript.vm.ProgramBuilder
import dev.ziggle.vscript.vm.StructShape
import dev.ziggle.vscript.vm.TraceKind

/** The three list nodes that take a function. One emitter, because they are one loop with three endings. */
private val HIGHER_ORDER = setOf(
    BuiltinNodes.LIST_MAP,
    BuiltinNodes.LIST_FILTER,
    BuiltinNodes.LIST_FIRST_WHERE,
)

/**
 * The four kinds of entry, and the order each visits the import closure in.
 *
 * The order is a property of the KIND, not of the caller, so it is written once here rather than being
 * chosen at four call sites that would eventually disagree.
 */
enum class EntryGroup(
    /**
     * The language's own handler, which is where the ordering rule lives — see [EntryKind].
     *
     * Two front ends compile these, and "does a library's `on stop` run before or after its importer's"
     * has to have one answer however the script was written. Held here rather than restated.
     */
    val kind: EntryKind,
) {
    START(EntryKind.START),
    STOP(EntryKind.STOP),
    RENDER(EntryKind.RENDER),
    TICK(EntryKind.TICK),
    WAKE(EntryKind.WAKE),
    SLEEP(EntryKind.SLEEP);

    /** Imports before importers — see [EntryKind.innermostFirst]. */
    internal val innermostFirst: Boolean get() = kind.innermostFirst

    internal fun select(catalog: NodeCatalog): (Graph) -> List<Node> = when (this) {
        START -> { g -> g.entries(catalog) }
        STOP -> { g -> g.stopEntries() }
        RENDER -> { g -> g.renderEntries() }
        TICK -> { g -> g.tickEntries() }
        WAKE -> { g -> g.wakeEntries() }
        SLEEP -> { g -> g.sleepEntries() }
    }
}

/** One entry, compiled, and the document it belongs to — see [DocEntry] for why the document travels with it. */
class CompiledEntry(val document: Graph, val node: Node, val chunk: Chunk)

/** An imported document left out of a run, and what is wrong with it. */
class SkippedDocument(val document: Graph, val errors: List<Issue>)

/**
 * What [GraphCompiler.compileEntries] found.
 *
 * [skipped] is not an aside: a library dropped silently is a handler that simply stopped happening, which
 * is the hardest kind of absence to notice. The caller is expected to say so out loud.
 */
class EntryCompilation(val entries: List<CompiledEntry>, val skipped: List<SkippedDocument>)

/**
 * Lowers a validated [Graph] to a [Chunk].
 *
 * **Exec wires become control flow, data wires become registers.** Each impure node's outputs get a stable
 * register for the life of the chunk, so a later node reading them is a plain register reference. Pure
 * nodes have no registers of their own — they are re-expanded at every use site, which is Blueprints'
 * semantics and the reason a pure node can be read from two places without being "run twice" in any way the
 * author can observe.
 *
 * **Exec chains are memoised by node id.** The first time a node is reached it is emitted inline and its
 * address recorded; every later arrival emits a `JMP` to that address. That single rule handles both of the
 * awkward cases for free: two branches converging on a shared tail compiles the tail *once* instead of
 * duplicating it exponentially, and a chain wired back on itself becomes a real loop rather than infinite
 * inlining.
 *
 * Compilation assumes [Validator] has already passed — it does not re-check types or wiring.
 */
class GraphCompiler(
    private val catalog: NodeCatalog,
    /**
     * Build for **debugging**: emit [Op.TRACE] markers for the flow animation and breakpoints, and give
     * every authored pin its own constant slot so its value can be rewritten while the script runs.
     *
     * Off is the release build — smaller and faster, and with the constant pool **interned by value**. That
     * second half matters more than it looks: a literal node feeding five pins is one editable knob where
     * five typed-in values are five, so the pool is the one place a graph and its text round trip can differ
     * without the program differing. Interning removes the distinction entirely, which is sound precisely
     * *because* the thing that could observe it — `Chunk.setLiteral` — is a debug affordance.
     *
     * Nothing else may depend on this flag. `DifferentialTest` drives both builds side by side and requires
     * the same host calls in the same order; the moment a mode changes what a script *does*, the mode you
     * test stops being the mode you ship.
     */
    private val debug: Boolean = true,
    /**
     * Where `import` declarations are looked up.
     *
     * [GraphSource.NONE] — the default — is not "imports are broken", it is "this graph imports nothing",
     * which is what every caller predating imports means and what the canvas means until it grows an
     * Imports panel. A graph that DOES declare an import and is compiled through a source that cannot find
     * it fails in the validator with the ref it could not resolve, rather than here.
     */
    private val source: GraphSource = GraphSource.NONE,
    /**
     * node id -> where in the source it came from, when this graph was lowered from text.
     *
     * Passed straight to the [Validator] so a [GraphCompileException] names lines. Empty for a canvas
     * graph, and empty is not a failure -- see [Issue.span].
     */
    private val spans: Map<Int, Span> = emptyMap(),
    /**
     * Compile each `on tick` handler as a LOOP rather than as a one-shot pass.
     *
     * For a host whose tick IS the program's body — one pass per world tick, with sequences and waits
     * allowed inside a pass — the handler is spawned as a long-lived fiber, and this is what its chunk has
     * to look like for that: the chain is followed by `YIELD` and a jump back to its head, so a pass ends
     * by parking until the next scheduler pass and the next begins where the first did. A `return` in the
     * body ends the PASS rather than the fiber. Off is the staged pass the client drives through
     * `ScriptRuntime.gameTick`, unchanged.
     */
    private val tickLoop: Boolean = false,
) {

    /**
     * A validator for THIS document, carrying the source spans so its issues name lines.
     *
     * Only for this one. An imported document's node ids index its own source, so handing them this
     * document's table would put every issue in a library on a line of the file that imported it.
     */
    private fun validator() = Validator(catalog, source, spans)

    /** The documents [graph] spans, and the one globals layout they agree on. */
    private fun closureOf(graph: Graph): ImportClosure =
        if (graph.imports.isEmpty()) ImportClosure.single(graph) else ImportClosure.resolve(graph, source)

    /**
     * Compile the chain starting at [entryNodeId] (an [NodeKind.ENTRY] node).
     *
     * @throws GraphCompileException if the graph has validation errors.
     */
    fun compile(graph: Graph, entryNodeId: Int): Chunk {
        val issues = validator().validate(graph)
        if (issues.errors().isNotEmpty()) throw GraphCompileException(issues)
        val table = ProgramBuilder()
        val chunk = Ctx(closureOf(graph), graph, entryNodeId, table).build()
        // The entry chunk calls into the program without being part of it, so it is linked as a root.
        table.link(chunk)
        return chunk
    }

    /**
     * Compile ONE user function's body as a runnable chunk, callable with arguments.
     *
     * The same sub-chunk [Ctx] builds for a Call site, but reached without one — so a function can be run on
     * its own, with values you choose, instead of only through whatever graph happens to call it. That is
     * the difference between testing a function and testing a script that uses it, and it is the check that
     * would have answered "Hello, null" in seconds rather than a live run.
     */
    fun compileFunction(graph: Graph, name: String): Chunk {
        val issues = validator().validate(graph)
        if (issues.errors().isNotEmpty()) throw GraphCompileException(issues)
        val closure = closureOf(graph)
        val scope = ImportScope(closure, graph)
        // A qualified name runs an IMPORTED function directly — `graph call banking::withdraw`. The body
        // compiles against its own document, which is the same rebasing an ordinary call does.
        val q = QualName.parse(name)
        val doc = scope.documentOf(q) ?: error("no document '${q.module}' — is it imported?")
        doc.function(q.name) ?: error("no function named '$name'")
        val entry = doc.entryOf(q.name) ?: error("function '$name' has no box on the canvas")
        // Compiled INTO the table under its own key, not beside it — so a recursive self-call inside the
        // body resolves to this very chunk rather than compiling a second copy of it.
        val table = ProgramBuilder()
        val idx = table.indexOf(doc.id to q.name) {
            Ctx(closure, doc, entry.id, table, q.name).build()
        }
        return table.link()[idx]
    }

    /** Compile every entry node in the graph, keyed by node id. */
    fun compileAll(graph: Graph): Map<Int, Chunk> {
        val issues = validator().validate(graph)
        if (issues.errors().isNotEmpty()) throw GraphCompileException(issues)
        val closure = closureOf(graph)
        // One table for every entry, so a helper called from two of them is compiled once and they share
        // its constant pool — which is what makes retuning a literal in it reach both.
        val table = ProgramBuilder()
        val byEntry = graph.entries(catalog).associate { it.id to Ctx(closure, graph, it.id, table).build() }
        table.link(*byEntry.values.toTypedArray())
        return byEntry
    }

    /**
     * Every entry of [group], from the ROOT and from everything it imports.
     *
     * The closure-aware counterpart to [compileAll] — see [ImportClosure.entriesAcross] for why an
     * imported document's entries should run at all. Each is compiled against **its own** document, which
     * is what makes its variable reads and writes land in that document's globals slots rather than the
     * importer's: `Ctx` already takes the document explicitly, and `globalsBase` is already per-document,
     * so nothing new is needed here to get that right.
     *
     * **An imported document is validated before its entries are taken, and a broken one is SKIPPED
     * rather than fatal.** Two reasons, and they pull the same way:
     *
     *  - Until now an imported document's entry nodes were never compiled, so they were never checked.
     *    Compiling one unchecked would let a blocking call inside a library's `on render` through — the
     *    exact thing `checkDrivenEntries` exists to refuse, reappearing one import away.
     *  - Making it fatal would mean a library with a fault in a handler nobody uses could stop a script
     *    that has worked for months. One library's problem must cost you that library's handlers.
     *
     * The root is NOT treated that way: its own errors are the caller's to fix, and it is validated by
     * [compileAll] before this is ever reached.
     */
    fun compileEntries(
        graph: Graph,
        group: EntryGroup,
        /**
         * The program to compile into — pass ONE across every group of a run.
         *
         * A run is compiled a group at a time (`on start`, then `on stop`, `on render`, `on tick`), and a
         * helper called from two of them is one function. Given separate tables it is compiled twice, with
         * two constant pools, so retuning a literal inside it reaches one copy and leaves the other stale —
         * see `docs/LINKER_PLAN.md` §2.2. The default keeps a single-group caller working unchanged.
         */
        table: ProgramBuilder = ProgramBuilder(),
    ): EntryCompilation {
        val closure = closureOf(graph)
        val entries = ArrayList<CompiledEntry>()
        val skipped = ArrayList<SkippedDocument>()
        // Validated once per document, not once per entry — a document with three handlers is one
        // question about that document, asked three times otherwise.
        val verdict = HashMap<String, List<Issue>>()
        for (found in closure.entriesAcross(group.innermostFirst, group.select(catalog))) {
            val doc = found.document
            if (doc !== closure.root) {
                val errors = verdict.getOrPut(key(doc)) {
                    // NOT [validator]: this is another document, and its node ids index its own source.
                    Validator(catalog, source).validate(doc).errors()
                }
                if (errors.isNotEmpty()) {
                    if (skipped.none { it.document === doc }) skipped += SkippedDocument(doc, errors)
                    continue
                }
            }
            entries += CompiledEntry(doc, found.node, Ctx(closure, doc, found.node.id, table).build())
        }
        table.link(*entries.map { it.chunk }.toTypedArray())
        return EntryCompilation(entries, skipped)
    }

    /**
     * One chunk under construction: the main graph, or one user function's body.
     *
     * [functionName] null means the top-level chain reached from a fiber's entry node. Otherwise this is a
     * function body, and it differs in three ways that matter: its first registers are the parameters, only
     * the nodes naming it are compiled into it, and falling off the end returns to the caller rather than
     * halting the fiber.
     */
    private inner class Ctx(
        /** Every document this run spans — shared by every Ctx, so they agree on globals slots. */
        val closure: ImportClosure,
        /**
         * The document THIS body belongs to.
         *
         * Was implicitly "the one being compiled" and is now explicit, which is the whole of what imports
         * cost the compiler: an imported function's body resolves its names, its types and its own
         * variables against the document that DECLARED it, never the one that called it. Getting this
         * wrong does not fail to compile — it silently resolves `helper` to the caller's `helper`.
         *
         * The document as **saved**, which is what every name resolves against. What is *compiled* is
         * [graph] — the same document with the inlinable calls already spliced out.
         */
        val document: Graph,
        val entryNodeId: Int,
        /**
         * The program every chunk of this compilation shares — see [ProgramBuilder].
         *
         * Shared rather than per-Ctx, and that is the whole of the linker: a function is looked up by
         * (document, name) in ONE table, so it is compiled once for the program instead of once per chunk
         * that calls it.
         */
        val table: ProgramBuilder,
        val functionName: String? = null,
        /**
         * This chunk is a document's **initialiser prologue** — see [prologueChunkFor].
         *
         * It starts at an entry node like the top-level chain does, but it is neither that nor a function
         * body, and it differs from both in exactly two ways: the walk stops at the end of the `@init`
         * prefix rather than running the document's `on start` on behalf of whoever imported it, and
         * falling off the end returns to the caller instead of halting the fiber.
         */
        val prologue: Boolean = false,
    ) {
        /**
         * What names mean from inside [document] — the resolver every lookup below goes through.
         *
         * Built from the SAVED document, so [subChunkFor] hands the next `Ctx` a document from the closure
         * rather than a rewritten copy of one. A copy would be resolved identically — [key] is its id — but
         * it would then be rewritten a second time, and a compiler that can be handed either is a compiler
         * with two kinds of document in it.
         */
        val scope = ImportScope(closure, document)

        /**
         * The document as COMPILED: [document] with each inlinable call replaced by the callee's body.
         *
         * Everything below reads THIS — nodes, links, the exec chain, [AppendPass] — and nothing outside
         * **No rewrite any more.** `Inliner` used to splice a single-assignment mutating extension here so
         * that `AppendPass` could recognise the accumulator shape underneath it. Both are gone: the text
         * front end never ran either (which is how the idiom they existed to make affordable turned out to
         * be O(n²) on the path everyone actually uses), and containers are references now, so there is no
         * copy left to elide. The canvas keeps working and simply pays the host call.
         */
        val graph: Graph = document

        val fn = functionName?.let { graph.function(it) }

        /** This chunk is a tick handler compiled as a loop — see [GraphCompiler.tickLoop]. */
        val loopEntry: Boolean =
            tickLoop && !prologue && functionName == null &&
                graph.node(entryNodeId)?.type == BuiltinNodes.ENTRY_TICK

        /** Jumps out of a loop pass — a `return` in the body — patched to the pass boundary in [build]. */
        private val passExits = ArrayList<Int>()

        val b = ChunkBuilder(
            when {
                functionName != null -> "${graph.name}::$functionName"
                // Named apart from the document's own start chunk, which begins at the same entry node:
                // a VM error naming "'lib#3' …" for either of two different chunks is a bad half-hour.
                prologue -> "${graph.name} defaults"
                else -> "${graph.name}#$entryNodeId"
            },
            fn?.params?.size ?: 0,
            editableLiterals = debug,
            program = table,
        )

        /** "Is this Call an expression?" — built once, since it walks bodies to answer. */
        val expression = dev.ziggle.vscript.model.expressionCalls(
            catalog, graph.nodes, graph.links, scope::function,
        ) { scope.pureAcrossImport(catalog, it) }

        /** Stable register per impure/entry node output pin — `(nodeId, pin) -> register`. */
        val outReg = HashMap<Pair<Int, String>, Int>()

        /**
         * Index per graph variable — into the run's GLOBALS, not this chunk's registers.
         *
         * The index is the variable's position in the document, so every chunk agrees on it without having
         * to be told: a function body compiled into a sub-chunk reads slot 3 and the main chain writes slot
         * 3, and they mean the same variable.
         */
        val varIndex: Map<String, Int> = buildMap {
            // This document's own variables, at its base — which is 0 unless something imported it.
            val base = closure.globalsBase(graph)
            graph.variables.forEachIndexed { i, v -> put(v.name, base + i) }
            // And every name this document can reach through an alias, at the DEFINING document's base.
            // One cell per (document, variable) is what makes an imported variable shared rather than
            // copied: a library that counts something and a caller that reads the count are looking at the
            // same slot, which is the only reading under which either of them is telling the truth.
            for (imp in graph.imports) {
                // Through the export map, so a variable reached across a BARREL lands on the slot of the
                // document that really declares it — a barrel declares none, so reading its own list
                // offered nothing and the name was simply missing from the map.
                for ((offered, e) in scope.offeredBy(imp)) {
                    val i = e.owner.variables.indexOfFirst { it.name == e.name }
                    if (i < 0) continue
                    put("${imp.alias}${QualName.SEP}$offered", closure.globalsBase(e.owner) + i)
                }
            }
        }

        /** Address of each already-emitted exec node, for the memoisation rule described above. */
        val execLabel = HashMap<Int, Int>()

        /** Guards against re-entering a pure node while expanding it (validator should have caught it). */
        val pureInProgress = HashSet<Int>()

        /**
         * Output pins standing in a scratch register for the extent of one expansion.
         *
         * A pure node owns no register — it is re-expanded at every use site, which is the whole model —
         * and that is right until an expansion needs to hand its own intermediate value to a subtree.
         * `a?.b` is the first such case: the access has to read the receiver the guard already evaluated,
         * and re-expanding it would ask a query twice and possibly get two different answers.
         *
         * Scoped to the expansion that binds it and removed in a `finally`, so a pin is only ever bound
         * where the register it names is genuinely live.
         */
        val pureBound = HashMap<Pair<Int, String>, Int>()

        /**
         * This node's descriptor WITH its signature resolved — a Call node's pins come from the function it
         * names, not from the catalog. Every read of a node's pins in this compiler goes through here.
         */
        /** Types nameable from this body — see [ImportScope.visibleTypes]. Built once; it walks imports. */
        val visibleTypes: List<dev.ziggle.vscript.model.StructType> by lazy { scope.visibleTypes() }

        /** Enums nameable from this body — see [ImportScope.visibleEnums]. */
        val visibleEnums: List<dev.ziggle.vscript.model.EnumType> by lazy { scope.visibleEnums() }

        fun desc(node: Node, depth: Int = 0): NodeDescriptor = dev.ziggle.vscript.model.resolveNode(
            node,
            catalog[node.type] ?: error("unknown node type '${node.type}' (validator should have caught this)"),
            scope::function,
            { visibleTypes },
            expression,
            { visibleEnums },
            // A generic call's pins are substituted from what is wired into `self`. Only asked for a
            // signature that HAS a type variable, so every existing script pays nothing; [depth] bounds
            // the mutual recursion, which a ring of Holds in a hand-edited file would otherwise not.
            { n, p -> if (depth > 32) null else feedingInto(n, p, depth + 1) },
        )

        /** What the wire into [nodeId]'s [pin] carries — see `effectivePinType`. */
        fun feedingInto(nodeId: Int, pin: String, depth: Int): dev.ziggle.vscript.model.TypeRef? {
            if (depth > 32) return null
            val l = graph.linkInto(nodeId, pin)
                ?: return dev.ziggle.vscript.model.literalTypeOf(graph.node(nodeId)?.literals?.get(pin))
            val src = graph.node(l.fromNode) ?: return null
            val out = desc(src, depth + 1).output(l.fromPin) ?: return null
            return dev.ziggle.vscript.model.effectivePinType(src, out, { scope.variable(it)?.type }) { n, p ->
                feedingInto(n, p, depth + 1)
            }
        }

        /**
         * The nodes making up this document's initialiser prologue: the head of its start chain, up to and
         * including the LAST `@init` Set.
         *
         * **Why up to the last mark rather than "while marked".** A default that has to run is an
         * expression, and an expression containing an impure call has that call placed in the chain
         * *before* the Set that stores the result — `var t: INT = countThings()` is two nodes, only one of
         * which carries the mark. Walking while-marked would stop at the first of those and initialise
         * nothing; taking everything up to the last mark takes each Set together with whatever feeds it.
         *
         * The prefix is linear by construction — `emitInits` places Sets and their feeders and nothing
         * else, and no expression can contain a branch — so following one exec output per node is the
         * whole walk. Whatever comes after the last mark is the author's own `on start`, and is *not* this
         * importer's business to run.
         */
        val prologueIds: Set<Int> by lazy {
            val chain = ArrayList<Int>()
            val seen = HashSet<Int>()
            var last = 0
            var at: Node? = graph.node(entryNodeId)
            while (true) {
                val here = at ?: break
                if (!seen.add(here.id)) break // a chain wired back on itself: there is no prefix to find
                if (here.id != entryNodeId) {
                    chain += here.id
                    if (BuiltinNodes.isInitialiser(here)) last = chain.size
                }
                val pin = desc(here).execOutputs.firstOrNull()?.name ?: break
                val link = graph.links.firstOrNull { it.fromNode == here.id && it.fromPin == pin } ?: break
                at = graph.node(link.toNode)
            }
            chain.take(last).toHashSet()
        }

        fun build(): Chunk {
            // Then a stable register for every non-pure node's data outputs. Pure nodes are re-expanded at
            // each use so they own no register; comments are annotation and compile to nothing at all.
            // Only the nodes belonging to THIS chunk. A function body has its own frame and its own
            // registers; giving it slots for the whole document would be both wasteful and wrong.
            for (n in graph.nodes) {
                if (n.function != functionName) continue
                // A prologue is a slice of the top-level chain, so "belongs to this chunk" is narrower
                // still: the library's own `on start` body is not compiled here and needs no registers.
                if (prologue && n.id !in prologueIds) continue
                val d = catalog[n.type] ?: continue
                // The BOX's parameters ARE the first registers by the calling convention — the caller wrote
                // them there — so they map onto those rather than getting fresh ones.
                if (d.kind == NodeKind.FUNCTION) {
                    if (n.function == functionName) {
                        fn?.params?.forEachIndexed { i, p -> outReg[n.id to p.name] = i }
                    }
                    continue
                }
                // The RESOLVED kind, not the catalogue's: a Call to a function whose body only computes
                // is a pure node, and a pure node owns no register — it is expanded wherever it is read.
                val resolved = desc(n)
                if (resolved.kind == NodeKind.PURE || resolved.kind == NodeKind.COMMENT) continue
                for (pin in resolved.dataOutputs) outReg[n.id to pin.name] = b.reg()
            }

            // Publish the register assignments for the inspector. Only these have stable slots — see
            // [SlotMap] for why a pure node has none.
            b.slots = dev.ziggle.vscript.vm.SlotMap(outputs = HashMap(outReg), variables = HashMap(varIndex))
            // Zero-valued rather than null for a record, because there is no field to type a default
            // into: a variable typed as one would otherwise start null and every read before the first
            // Make would fault. Built HERE rather than where the run starts, so a chunk carries its own
            // starting values — `graph call` and the tests drive a chunk directly, and a second copy of
            // this rule living beside the runtime is a second copy that can disagree.
            b.globals = startingGlobals(closure)
            // Whose prologue this is, so the VM can run it once per run however many entries call it.
            if (prologue) b.prologueOf = key(document)

            val entry = graph.node(entryNodeId) ?: error("no node $entryNodeId")
            // Before the first instruction of the run: every imported document's computed defaults, in
            // dependency order. See [callImportedPrologues] for why this is the one place they can go.
            //
            // The WAKE entry too, because it runs before the start does and a library's computed default
            // has to hold by then. Safe to name both: a prologue chunk carries `prologueOf`, and
            // `Interpreter.ranPrologues` drops the second call, so whichever entry gets there first is the
            // one that seeds. That guard is what makes this different from the ROOT's own initialisers,
            // which have none and so must be emitted on exactly one entry — see `Lower.initsGoOn`.
            if (!prologue && functionName == null &&
                (entry.type == BuiltinNodes.ENTRY || entry.type == BuiltinNodes.ENTRY_WAKE)
            ) {
                callImportedPrologues()
                // **And its OWN, when this entry belongs to an imported document.**
                //
                // `prologueOrder` walks `graph.imports` — the imports of whichever document's entry is
                // being compiled — so a library's entry seeds everything it imports and never itself. Its
                // own prologue is only reached from an IMPORTER's entry, and when the importer has no
                // `on wake` that lands on `on start`: after every imported wake handler has already run.
                //
                // So a library woke with its variables unseeded, wrote something, and had it overwritten
                // by its own defaults a moment later. An orchestrator restored its saved stint in
                // `on wake` and the root's `on start` reset the record to empty — the log read "resuming
                // tithe (~27 of 28 min left)" and then "tithe for ~33 min", one millisecond apart.
                //
                // `ranPrologues` makes this free where it is not needed: whichever entry gets there first
                // seeds, and the importer's later call is dropped.
                if (key(document) != key(closure.root)) {
                    prologueChunkFor(document)?.let { idx ->
                        val mark = b.mark()
                        val window = b.regs(1)
                        b.emit(Op.CALLG, idx, window, Op.packCounts(0, 0))
                        b.release(mark)
                    }
                }
            }
            val execPin = desc(entry).execOutputs.firstOrNull()
            // Where a loop pass begins: after the prologue calls, which run once and not once per pass.
            val passTop = b.here()
            val terminated = if (execPin == null) false else compileExecEdge(entry.id, execPin.name)
            if (loopEntry) {
                // The pass boundary. Park until the next scheduler pass, then run the body again; a
                // `return` inside the body jumped here rather than ending the fiber — see [ret]. Stamped
                // with the entry node, which is the thing a debugger should see the fiber resting on.
                val boundary = b.here()
                passExits.forEach { b.patch(it, boundary) }
                b.currentNodeId = entry.id
                b.emit(Op.YIELD)
                b.emit(Op.JMP, passTop)
                return b.build()
            }
            // Running off the end of a FUNCTION returns to whoever called it — WITH ITS RESULTS. Halting
            // would stop the whole fiber, and returning empty-handed is nearly as bad: a body that is pure
            // DATA has no exec chain to reach the boundary with, so it fell off the end and handed back
            // nothing, and the call site saw null. Nothing was wrong with such a graph; it simply had no
            // exec wire to draw, because there was no statement to sequence.
            //
            // So the end of a body is an implicit return of whatever its result pins are fed by, which is
            // what "the function ends here" ought to mean. A body that DOES reach the boundary has already
            // returned above and never gets here.
            if (!terminated) {
                when {
                    // A prologue is CALLED, so the end of it is a return like any other. Halting here
                    // would end the fiber before the entry that called it had run a single statement.
                    prologue -> b.emit(Op.RET, 0, 0)
                    functionName == null -> b.emit(Op.HALT)
                    else -> functionReturn(entry, desc(entry))
                }
            }
            // No self-fill knot any more. A function's index is reserved in the shared table before its
            // body is compiled, so a recursive call already resolved to a slot that this chunk is about to
            // occupy — there is nothing to patch afterwards, and no chunk that contains itself.
            return b.build()
        }

        // ---- exec flow ------------------------------------------------------------------------------

        /**
         * Follow an exec output, emitting the edge marker the flow animation reads.
         *
         * Separate from [compileExec] so the *link* is traced as control crosses it, which is what lets the
         * editor pulse the wire rather than only lighting up nodes.
         */
        fun compileExecEdge(fromNodeId: Int, pin: String): Boolean {
            val link = graph.links.firstOrNull { it.fromNode == fromNodeId && it.fromPin == pin }
            // The end of a prologue: the chain carries on into the library's own `on start`, and that body
            // belongs to a run OF the library, not to whoever imported it. Checked before the marker so
            // the flow animation does not pulse a wire nothing crosses.
            if (prologue && link != null && link.toNode !in prologueIds) { b.emit(Op.RET, 0, 0); return true }
            if (debug && link != null) b.emit(Op.TRACE, link.id, TraceKind.EXEC_EDGE)
            return compileExec(link?.toNode)
        }

        /**
         * Emit the chain starting at [nodeId].
         *
         * @return true if control cannot fall out of the bottom (the chain returned, halted, or jumped
         *   somewhere), false if the next thing emitted will run next. Callers use this to decide whether
         *   they still need a `JMP` to their join point.
         */
        fun compileExec(nodeId: Int?): Boolean {
            if (nodeId == null) return false
            execLabel[nodeId]?.let { b.emit(Op.JMP, it); return true }

            val node = graph.node(nodeId) ?: return false
            val d = desc(node)
            execLabel[nodeId] = b.here()
            b.currentNodeId = nodeId
            if (debug) b.emit(Op.TRACE, nodeId, TraceKind.NODE_ENTER)

            return when (node.type) {
                BuiltinNodes.BRANCH -> branch(node)
                BuiltinNodes.IF_SOME -> ifSome(node)
                BuiltinNodes.TRY -> tryNode(node)
                BuiltinNodes.SEQUENCE -> sequence(node)
                BuiltinNodes.WHEN -> whenNode(node)
                BuiltinNodes.WHILE -> whileLoop(node, nodeId)
                BuiltinNodes.FOR_EACH -> forEach(node, nodeId)
                BuiltinNodes.MAP_FOR_EACH -> forEachEntry(node, nodeId)
                BuiltinNodes.BREAK -> leaveLoop(exit = true)
                BuiltinNodes.CONTINUE -> leaveLoop(exit = false)
                BuiltinNodes.DELAY -> delay(node, d)
                BuiltinNodes.RETURN -> ret(node, d)
                BuiltinNodes.VAR_SET -> varSet(node, d)
                BuiltinNodes.HOLD -> hold(node, d)
                BuiltinNodes.LOCAL_SET -> localSet(node, d)
                // Control arriving back AT the box is the return: its input pins are the results.
                BuiltinNodes.FUNCTION -> functionReturn(node, d)
                BuiltinNodes.CALL -> callFunction(node, d)
                BuiltinNodes.INVOKE -> invoke(node, d)
                else -> hostCall(node, d)
            }
        }

        /**
         * `if val t = … { … } else { … }` — [branch] with the value left in a register for the Some arm.
         *
         * The option is evaluated straight into the node's OWN output register, which is the whole of the
         * binding: `build` gave every impure node's outputs a stable slot, the Some arm reads that slot,
         * and there is no Hold, no copy and nothing to name. What makes it a binding rather than a wire is
         * only that the pin's type has the `?` taken off.
         */
        fun ifSome(node: Node): Boolean {
            val value = outReg.getValue(node.id to BuiltinNodes.IF_SOME_VALUE)
            evalInput(node, BuiltinNodes.IF_SOME_OPTION, value)
            val mark = b.mark()
            val present = b.reg()
            b.emit(Op.CONST, present, b.constant(null))
            b.emit(Op.NE, present, value, present)
            val jf = b.emit(Op.JMPF, present, 0)
            b.release(mark)

            val someTerm = compileExecEdge(node.id, BuiltinNodes.IF_SOME_THEN)
            val skip = if (someTerm) -1 else b.emit(Op.JMP, 0)
            b.patch(jf)
            val noneTerm = compileExecEdge(node.id, BuiltinNodes.IF_SOME_ELSE)
            if (skip >= 0) b.patch(skip)
            return someTerm && noneTerm
        }

        /**
         * `try { … } catch e { … }` — the guarded body, then the handler, with a RANGE recorded over the
         * body's instructions.
         *
         * There is nothing to emit for the guard itself: no instruction arms it and none disarms it. That
         * is the whole reason for a table — `return`, `break` and `continue` all leave the body without
         * passing its bottom, and each of them would otherwise have to remember to disarm.
         *
         * The range covers the BODY only. An error raised inside the catch block must reach the NEXT
         * handler out, not this one, or a handler that fails would catch itself forever.
         */
        fun tryNode(node: Node): Boolean {
            val err = outReg.getValue(node.id to BuiltinNodes.TRY_ERROR)
            val start = b.here()
            val bodyTerm = compileExecEdge(node.id, BuiltinNodes.TRY_BODY)
            // Past the body: skip the handler on the path that did not raise.
            val skip = if (bodyTerm) -1 else b.emit(Op.JMP, 0)
            val end = b.here()
            b.handler(start, end, catchPc = end, messageReg = err)
            val catchTerm = compileExecEdge(node.id, BuiltinNodes.TRY_CATCH)
            if (skip >= 0) b.patch(skip)
            return bodyTerm && catchTerm
        }

        fun branch(node: Node): Boolean {
            val mark = b.mark()
            val cond = b.reg()
            evalInput(node, "Condition", cond)
            val jf = b.emit(Op.JMPF, cond, 0)
            b.release(mark)

            val trueTerm = compileExecEdge(node.id, "True")
            val skip = if (trueTerm) -1 else b.emit(Op.JMP, 0)
            b.patch(jf)
            val falseTerm = compileExecEdge(node.id, "False")
            if (skip >= 0) b.patch(skip)
            return trueTerm && falseTerm
        }

        /**
         * `when` — test each case in order, run the first that matches, skip the rest.
         *
         * A chain of the same shape [branch] emits, which is the point: the two forms and the `if`/`else if`
         * chain all cost the same, so choosing `when` is a choice about what the source says and never about
         * speed.
         *
         * **The subject is evaluated ONCE**, into a register held for the whole test chain, rather than
         * re-read per case. That is not only cheaper — an impure subject re-evaluated per case would call the
         * host once per arm, so `when nextTarget() { … }` would ask for a different target in each test and
         * could match none of them.
         *
         * Returns true only if EVERY path terminates, `else` included. An unwired `Else` is a path that falls
         * through, so a `when` without one never terminates its chain however many of its arms return.
         */
        fun whenNode(node: Node): Boolean {
            val n = BuiltinNodes.whenCount(node)
            val hasSubject = graph.linkInto(node.id, BuiltinNodes.WHEN_SUBJECT) != null ||
                node.literals.containsKey(BuiltinNodes.WHEN_SUBJECT)

            val mark = b.mark()
            // Held across every test, so it is computed once. Released only after the last comparison.
            val subject = if (!hasSubject) -1 else b.reg().also {
                evalInput(node, BuiltinNodes.WHEN_SUBJECT, it)
            }

            // Jumps out of each arm to the end of the whole statement, patched once the last arm is placed.
            val toEnd = ArrayList<Int>()
            var allTerminate = true
            for (i in 1..n) {
                val test = b.mark()
                val cond = b.reg()
                evalInput(node, BuiltinNodes.whenCase(i), cond)
                if (hasSubject) {
                    // The case is a VALUE to equal. Compared into the same register, so the arm chain below
                    // sees a boolean either way and there is one jump shape rather than two.
                    b.emit(Op.EQ, cond, subject, cond)
                }
                val skipArm = b.emit(Op.JMPF, cond, 0)
                b.release(test)

                val armTerminates = compileExecEdge(node.id, BuiltinNodes.whenThen(i))
                if (!armTerminates) toEnd += b.emit(Op.JMP, 0)
                allTerminate = allTerminate && armTerminates
                b.patch(skipArm)
            }
            b.release(mark)

            // Nothing matched. A wired Else runs; an unwired one falls through, which is the same address.
            val elseTerminates = compileExecEdge(node.id, BuiltinNodes.WHEN_ELSE)
            toEnd.forEach { b.patch(it) }
            return allTerminate && elseTerminates
        }

        fun sequence(node: Node): Boolean {
            for (pin in BuiltinNodes.SEQUENCE_ARMS) {
                // A chain that terminates (Return, or a jump into a loop) takes control with it, so the
                // later Then pins are genuinely unreachable — same as Blueprints. And so is Completed:
                // control never arrives at the end of the sequence to leave by it.
                if (compileExecEdge(node.id, pin)) return true
            }
            // The arms fall through into one another in the instruction stream, so the last one runs
            // straight on into this. That is what makes the statement AFTER a `sequence` run once, after
            // every arm, rather than once per arm.
            return compileExecEdge(node.id, BuiltinNodes.SEQUENCE_DONE)
        }

        /**
         * The loops currently being compiled, innermost last.
         *
         * `break` and `continue` are jumps, and only the compiler knows where to. `continue` has its target
         * already — the address the body loops back to — but `break` lands *after* a loop that has not been
         * compiled yet, so each one emits a placeholder and the loop patches them all when it closes.
         *
         * Per-[Ctx], which is per-chunk, so the stack cannot leak across a function boundary: a `break`
         * inside a called function is a `break` in that function's loops or in none.
         */
        private inner class Loop(val continueAt: Int) {
            val exits = ArrayList<Int>()
        }

        private val loops = ArrayList<Loop>()

        /**
         * `break` (exit) or `continue`.
         *
         * Both END the path they are on — nothing after them in that block can run — which is what the
         * `true` says, and it is why the enclosing block does not then emit a fall-through jump.
         *
         * With no enclosing loop this emits nothing at all rather than guessing. The validator refuses that
         * graph, so it is unreachable in practice; reaching it would mean compiling something already
         * reported as broken, and a jump to nowhere is a worse answer than no jump.
         */
        fun leaveLoop(exit: Boolean): Boolean {
            val loop = loops.lastOrNull() ?: return true
            if (exit) loop.exits += b.emit(Op.JMP, 0) else b.emit(Op.JMP, loop.continueAt)
            return true
        }

        fun whileLoop(node: Node, nodeId: Int): Boolean {
            // Loop back to this node's own label so the condition is re-evaluated each iteration.
            val top = execLabel.getValue(nodeId)
            // Whatever the condition has to DO to be answered, before each test. Emitted at the top, so
            // the jump back re-runs it — which is the whole difference between a condition that is asked
            // again and one that was decided once. Pure conditions have no Check chain and emit nothing.
            compileExecEdge(node.id, "Check")
            b.currentNodeId = nodeId
            val mark = b.mark()
            val cond = b.reg()
            evalInput(node, "Condition", cond)
            val jf = b.emit(Op.JMPF, cond, 0)
            b.release(mark)

            loops += Loop(continueAt = top)
            if (!compileExecEdge(node.id, "Body")) b.emit(Op.JMP, top)
            val loop = loops.removeAt(loops.size - 1)
            // `break` lands exactly where a failing condition lands: after the loop, before Completed.
            b.patch(jf)
            loop.exits.forEach { b.patch(it) }
            return compileExecEdge(node.id, "Completed")
        }

        fun forEach(node: Node, nodeId: Int): Boolean {
            // The list and the cursor must outlive the loop body, so they are NOT released — no liveness
            // reuse in this pass; registers are cheap and correctness here is not.
            val list = b.reg()
            evalInput(node, "List", list)
            val iter = b.reg()
            b.emit(Op.ITER, iter, list)

            // Counts iterations alongside the iterator. Starts BELOW zero so the single increment at the
            // top of each pass lands on 0 for the first element — one increment, on the path that actually
            // produced an element, rather than one before the loop and another at the bottom that the
            // exhausted path would have to skip.
            val index = outReg.getValue(nodeId to "Index")
            val step = b.reg()
            b.emit(Op.CONST, index, b.constant(-1))
            b.emit(Op.CONST, step, b.constant(1))

            val top = b.here()
            val element = outReg.getValue(nodeId to "Element")
            val exhausted = b.emit(Op.ITERNEXT, element, iter, 0)
            b.emit(Op.ADD, index, index, step)
            // `continue` targets `top`, which is the ITERNEXT and the index bump — not the ITER setup
            // above it. Jumping there would rebuild the iterator every pass and hand out the first element
            // forever, which is the shape of a hang rather than of a wrong answer.
            loops += Loop(continueAt = top)
            if (!compileExecEdge(node.id, "Body")) b.emit(Op.JMP, top)
            val loop = loops.removeAt(loops.size - 1)
            b.patch(exhausted)
            loop.exits.forEach { b.patch(it) }
            return compileExecEdge(node.id, "Completed")
        }

        /**
         * `for (k, v) in m` — [forEach] over the map's entry pairs, unpacked into two registers.
         *
         * No opcode of its own: the entries arrive as two-item lists from `vscript.mapEntries`, so this is
         * the list iterator plus one INDEX per half. That is one pass over the map to build the pairs and
         * nothing per key after it — as against walking Keys and asking the map for each value, which is a
         * scan per entry.
         */
        fun forEachEntry(node: Node, nodeId: Int): Boolean {
            // The entries, once, before the loop — a CALL window of one in and one out, the same shape
            // `hostCall` builds, done by hand here because this node's own exec chain is the loop.
            val entries = b.reg()
            val window = b.reg()
            evalInput(node, BuiltinNodes.MAP_PIN, window)
            b.emit(Op.CALL, b.host("vscript.mapEntries"), window, Op.packCounts(1, 1))
            b.emit(Op.MOVE, entries, window)
            val iter = b.reg()
            b.emit(Op.ITER, iter, entries)

            val pair = b.reg()
            val zero = b.reg()
            val one = b.reg()
            b.emit(Op.CONST, zero, b.constant(0))
            b.emit(Op.CONST, one, b.constant(1))

            val top = b.here()
            val key = outReg.getValue(nodeId to BuiltinNodes.MAP_KEY_PIN)
            val value = outReg.getValue(nodeId to BuiltinNodes.MAP_VALUE_PIN)
            val exhausted = b.emit(Op.ITERNEXT, pair, iter, 0)
            b.emit(Op.INDEX, key, pair, zero)
            b.emit(Op.INDEX, value, pair, one)

            loops += Loop(continueAt = top)
            if (!compileExecEdge(node.id, "Body")) b.emit(Op.JMP, top)
            val loop = loops.removeAt(loops.size - 1)
            b.patch(exhausted)
            loop.exits.forEach { b.patch(it) }
            return compileExecEdge(node.id, "Completed")
        }

        fun delay(node: Node, d: NodeDescriptor): Boolean {
            val mark = b.mark()
            val ms = b.reg()
            evalInput(node, "Ms", ms)
            b.emit(Op.SLEEP, ms)
            b.release(mark)
            return compileExecEdge(node.id, d.execOutputs.first().name)
        }

        /**
         * Hand values back and stop.
         *
         * Inside a body this node's pins ARE the function's results (see [resolveNode]), so each return
         * carries its own — which is the whole reason it exists rather than everything wiring to the box:
         * the box has one input per result, so every path reaching it returns the same expression. At top
         * level the node keeps its single `Value` pin and this ends the fiber, exactly as before.
         *
         * A contiguous window, like [functionReturn] and every call: `RET` names a base and a count, and
         * the interpreter copies that window into the caller's result slots.
         */
        fun ret(node: Node, d: NodeDescriptor): Boolean {
            if (loopEntry) {
                // A `return` in a loop pass ends the PASS, not the fiber — patched in [build].
                passExits += b.emit(Op.JMP, 0)
                return true
            }
            val results = d.dataInputs
            if (results.isEmpty()) {
                b.emit(Op.RET, 0, 0)
                return true
            }
            val mark = b.mark()
            val window = b.regs(results.size)
            results.forEachIndexed { i, pin -> evalInput(node, pin.name, window + i) }
            b.emit(Op.RET, window, results.size)
            b.release(mark)
            return true
        }

        fun varSet(node: Node, d: NodeDescriptor): Boolean {
            val slot = varIndex.getValue(node.variable!!)
            val mark = b.mark()
            val tmp = b.reg()
            evalInput(node, "Value", tmp)
            b.emit(Op.SETG, slot, tmp)
            b.release(mark)
            return compileExecEdge(node.id, d.execOutputs.first().name)
        }

        /**
         * Evaluate once, into this node's own stable register.
         *
         * The whole node is this one line, and that is the point of building it as an impure node rather
         * than as anything cleverer: [build] has already given every impure node's data outputs a register
         * for the life of the chunk, so evaluating INTO that register is the entire mechanism. Every reader
         * downstream then takes the [Op.MOVE] branch of [evalInput] — a register read — instead of
         * re-expanding the expression the way it would for a pure source.
         *
         * The `Name` pin is never read here. It is configuration, labelling the node for whoever is looking
         * at it, and [BuiltinNodes.CONFIG_PINS] is what stops it being wired.
         */
        fun hold(node: Node, d: NodeDescriptor): Boolean {
            evalInput(node, "Value", outReg.getValue(node.id to "Value"))
            return compileExecEdge(node.id, d.execOutputs.first().name)
        }

        /**
         * Give a `var` local a new value: evaluate into the register its Hold owns.
         *
         * That is the entire mechanism, and it is why mutable locals needed no new storage concept. [build]
         * has already given every impure node's outputs a register for the life of the chunk, a chunk is
         * one call's body, and a frame is one call — so writing the Hold's register is per-call by
         * construction. Two calls have two frames and two registers; a recursive call has its own. A graph
         * variable, which is what people had to reach for before this existed, has exactly one cell for the
         * whole run and silently gives none of that.
         *
         * The target is a node id recorded at lowering time rather than a name looked up here: names nest,
         * and the compiler has no scope stack to tell two `x`s apart.
         */
        fun localSet(node: Node, d: NodeDescriptor): Boolean {
            val target = (node.literals[BuiltinNodes.LOCAL_TARGET] as? Number)?.toInt()
                ?: error("Set Local ${node.id} names no local (validator should have caught this)")
            val reg = outReg[target to "Value"]
                ?: error("Set Local ${node.id} targets node $target, which owns no register")
            // The accumulator used to be rewritten in place here, when `AppendPass` could prove nobody
            // else held the list. Both that pass and the `Inliner` in front of it are gone — see [graph]
            // — so this is the ordinary path: evaluate the value and store it.
            evalInput(node, "Value", reg)
            return compileExecEdge(node.id, d.execOutputs.first().name)
        }

        /** Hand the results back and end the call. */
        fun functionReturn(node: Node, d: NodeDescriptor): Boolean {
            val results = d.dataInputs
            if (results.isEmpty()) {
                b.emit(Op.RET, 0, 0)
                return true
            }
            val mark = b.mark()
            // Consecutive, because RET names a base and a count — the caller copies that window straight
            // into its own result registers.
            val window = b.regs(results.size)
            results.forEachIndexed { i, pin -> evalInput(node, pin.name, window + i) }
            b.emit(Op.RET, window, results.size)
            b.release(mark)
            return true
        }

        /**
         * Call a user function: compile its body into a sub-chunk (once per chunk that calls it) and invoke it.
         *
         * Identical in shape to [hostCall] because the calling convention is the same — arguments laid out
         * consecutively, results copied back into the node's stable registers. The difference is only that
         * this one pushes a frame.
         */
        /**
         * The program index to `CALLG`, compiling the callee if this is its first mention anywhere.
         *
         * **Recursion needs no special case.** [ProgramBuilder.indexOf] registers the index *before* it
         * compiles the body, so a call reached from inside that body — the function itself, or a second
         * function that calls back — finds the reserved slot and returns it. Direct and mutual recursion
         * are the same mechanism, and neither requires a chunk to exist before it has been built.
         *
         * Keyed by **(document id, name)** rather than by spelling. Two documents may each declare a
         * `helper`, and inside an imported body a self-call is written unqualified — so matching the raw
         * string would be right by luck and wrong the moment the same name existed twice.
         */
        fun subChunkFor(name: String): Int {
            // Which document declares it — this one, or the one an alias points at. The body then compiles
            // with THAT document as its Ctx.graph, so its own names, types and variables resolve where they
            // were written rather than where they were called from.
            val q = QualName.parse(name)
            // The document that DECLARES it, which across a barrel is not the one the alias names — a
            // re-export forwards a name and never a body, so `scope.owner` follows the chain to the file
            // the function is actually written in. That is also what makes a barrel free at run time: the
            // chunk is compiled once, against its own document, whoever reached it.
            // The export table first, then the document itself. **A reference the COMPILER made may name
            // a private function**, and one always does: a folded record carries its hooks as qualified
            // names, and those hooks are private to the document that declared the record — the record is
            // what crosses the import, not them. Through the strict lookup that failed at the export table
            // and was reported as "no document 'hb'", which blames the document for a name sitting in it.
            val owner = scope.owner(q)
                ?: scope.ownerIgnoringVisibility(q)
                ?: error(
                    "no document '${q.module}', or it has no '${q.name}' " +
                        "(validator should have caught this)",
                )
            val doc = owner.owner
            // `key(doc)`, NOT `doc.id` — an id is blank on a freshly lowered document, so keying on it
            // alone makes every such document the same document. As a "have we seen this" guard that was
            // harmless; as a CACHE key it silently hands one document's chunk to another's call.
            return table.indexOf(key(doc) to owner.name) {
                val bodyEntry = doc.entryOf(owner.name)
                    ?: error("function '$name' has no Inputs node (validator should have caught this)")
                Ctx(closure, doc, bodyEntry.id, table, owner.name).build()
            }
        }

        /**
         * Call the function a wire is carrying — [BuiltinNodes.INVOKE].
         *
         * Identical to [callFunction] but for where the callee comes from, which is the whole of what
         * `Op.CALLV` is. The function register is allocated BEFORE the window, because a call window has
         * to be the topmost allocation: a frame starts at its argument base and runs upward, so anything
         * allocated after it would be inside the callee's registers.
         */
        fun invoke(node: Node, d: NodeDescriptor): Boolean {
            val args = d.dataInputs.filter { it.name != BuiltinNodes.INVOKE_FN }
            val rets = d.dataOutputs

            val mark = b.mark()
            val fn = b.reg()
            evalInput(node, BuiltinNodes.INVOKE_FN, fn)
            val window = b.regs(maxOf(args.size, rets.size).coerceAtLeast(1))
            args.forEachIndexed { i, pin -> evalInput(node, pin.name, window + i) }
            b.emit(Op.CALLV, fn, window, Op.packCounts(args.size, rets.size))
            rets.forEachIndexed { i, pin -> b.emit(Op.MOVE, outReg.getValue(node.id to pin.name), window + i) }
            b.release(mark)

            val next = d.execOutputs.firstOrNull() ?: return false
            return compileExecEdge(node.id, next.name)
        }

        fun callFunction(node: Node, d: NodeDescriptor): Boolean {
            val name = node.callee ?: error("Call node ${node.id} names no function (validator should have caught this)")
            val target = scope.function(name) ?: error("no function '$name' (validator should have caught this)")
            val idx = subChunkFor(name)
            val args = d.dataInputs
            val rets = d.dataOutputs

            val mark = b.mark()
            val window = b.regs(maxOf(args.size, rets.size).coerceAtLeast(1))
            args.forEachIndexed { i, pin -> evalInput(node, pin.name, window + i) }
            b.emit(Op.CALLG, idx, window, Op.packCounts(target.params.size, rets.size))
            rets.forEachIndexed { i, pin -> b.emit(Op.MOVE, outReg.getValue(node.id to pin.name), window + i) }
            b.release(mark)

            val next = d.execOutputs.firstOrNull() ?: return false
            return compileExecEdge(node.id, next.name)
        }

        // ---- imported initialisers ---------------------------------------------------------------------

        /**
         * Run every imported document's computed variable defaults, before this entry's first statement.
         *
         * **A run executes the ROOT document's entries only.** `var seed: FLOAT = now()` in a library
         * therefore never ran: `Lower` puts it in a prologue at the head of *that document's* `on start`,
         * and nothing ever spawns that. The variable stayed at its type's zero, and the first arithmetic
         * on it faulted from three frames inside a library that reads perfectly correctly.
         *
         * A `CALL` at the head of the entry chunk is what fixes it, and the reason to prefer it over
         * driving prologues from the runtime is that the ordering guarantee then falls out of the
         * mechanism instead of being maintained by hand. These are instructions: they cannot be reordered,
         * they cannot be skipped, and a prologue that BLOCKS blocks the way a blocking call blocks
         * anywhere else — the entry does not proceed until it has finished. A fiber spawned alongside
         * would have offered none of those three.
         *
         * **Only on the start entry**, so `on render` does not re-seed the library every frame. There is
         * always one to hang them off: `Graph.entries` is start entries alone, and a graph with none is
         * refused with "no entry node to run" before anything is compiled.
         *
         * The root's OWN prologue is not here — it is already inline at the head of this very chain, which
         * is where `Lower` put it. It runs after these, which is the right way round: the root's default
         * may read an imported one.
         */
        fun callImportedPrologues() {
            for (doc in prologueOrder()) {
                val idx = prologueChunkFor(doc) ?: continue
                val mark = b.mark()
                // Nothing in and nothing out, but CALLG still names a window — it becomes the callee's
                // register base, so it has to be a register this frame owns rather than slot zero.
                val window = b.regs(1)
                b.emit(Op.CALLG, idx, window, Op.packCounts(0, 0))
                b.release(mark)
            }
        }

        /**
         * The imported documents, each AFTER the ones it imports itself.
         *
         * Post-order, because a library's own default may read one from a library IT imports, and depth
         * alone does not order a diamond: with `a` importing `b` and `c` which both import `d`, the
         * closure's document order is `[a, b, d, c]` and neither it nor its reverse puts `d` before both
         * of its importers.
         *
         * Visiting by document identity is also what makes `d` run **once**. Twice would not be a wasted
         * call — it would re-seed a variable that a previous prologue may already have read.
         */
        fun prologueOrder(): List<Graph> {
            val order = ArrayList<Graph>()
            val seen = HashSet<String>()
            fun visit(doc: Graph) {
                if (!seen.add(key(doc))) return
                for (imp in doc.imports) closure.resolve(doc, imp.alias)?.let { visit(it) }
                order += doc
            }
            for (imp in graph.imports) closure.resolve(graph, imp.alias)?.let { visit(it) }
            return order
        }

        /**
         * [doc]'s prologue as a sub-chunk of this one, or null when it has no computed defaults.
         *
         * Compiled inside **this run's closure**, which is the detail that rules out compiling the
         * document on its own and calling the result: `globalsBase` is assigned by walking from a root, so
         * a standalone compile of `lib` puts its variables at slot 0 and every `SETG` it emits writes over
         * the root's. One closure, one `Ctx` per document — the same arrangement an imported function body
         * already compiles under.
         */
        fun prologueChunkFor(doc: Graph): Int? {
            if (doc.nodes.none { BuiltinNodes.isInitialiser(it) }) return null
            // The entry `Lower` put the `@init` prefix on, asked the same way it decided — see
            // [Graph.initialiserEntry]. Asking for the start entry here instead is what left a library
            // with an `on wake` holding an empty prologue.
            val entry = doc.initialiserEntry(catalog) ?: return null
            // Keyed like a function, under a name no function can have — a prologue is one per document,
            // and it must not collide with a function that happens to be called the same thing. `key(doc)`
            // for the same reason [subChunkFor] uses it: a lowered document's id is blank.
            return table.indexOf(key(doc) to "@prologue") {
                Ctx(closure, doc, entry.id, table, prologue = true).build()
            }
        }

        /**
         * A node backed by a host function.
         *
         * The opcode comes from the catalog's [HostKind], never the author: an [HostKind.BLOCKING] function
         * emitted as `CALL` would run on the client thread and trip the tick watchdog.
         */
        fun hostCall(node: Node, d: NodeDescriptor): Boolean {
            val host = d.host ?: error("node type '${node.type}' has no host function and is not a builtin")
            val args = d.dataInputs
            val rets = d.dataOutputs

            val mark = b.mark()
            // A CONSTRUCTION is a record built from the arguments, no call — see [HostKind.CONSTRUCT] and
            // the text compiler's NEWSTRUCT. Field order is the record's; an argument reaches the field of
            // its own name, and a field with no argument starts empty.
            if (d.hostKind == HostKind.CONSTRUCT) {
                val out = rets.firstOrNull() ?: error("construct '${node.type}' declares no result")
                val record = dev.ziggle.vscript.model.HostRecords.of(out.type) ?: error("construct '${node.type}' makes '${out.type.name}', which is not a host record")
                val fields = record.fields
                val window = b.regs(fields.size.coerceAtLeast(1))
                fields.forEachIndexed { i, f ->
                    val pin = args.firstOrNull { it.name.equals(f.name, ignoreCase = true) } ?: args.getOrNull(i)
                    if (pin != null) evalInput(node, pin.name, window + i) else b.emit(Op.CONST, window + i, b.constant(null))
                }
                val shape = b.constant(StructShape(record.name, fields.map { it.name }))
                b.emit(Op.NEWSTRUCT, outReg.getValue(node.id to out.name), shape, window)
                b.release(mark)
                val next = d.execOutputs.firstOrNull() ?: return false
                return compileExecEdge(node.id, next.name)
            }
            val window = b.regs(maxOf(args.size, rets.size).coerceAtLeast(1))
            args.forEachIndexed { i, pin -> evalInput(node, pin.name, window + i) }
            val opcode = if (d.hostKind == HostKind.BLOCKING) Op.ACT else Op.CALL
            b.emit(opcode, b.host(host), window, Op.packCounts(args.size, rets.size))
            // Copy out of the call window into the node's stable output registers before the window is
            // reused by the next call.
            rets.forEachIndexed { i, pin -> b.emit(Op.MOVE, outReg.getValue(node.id to pin.name), window + i) }
            b.release(mark)

            val next = d.execOutputs.firstOrNull() ?: return false
            return compileExecEdge(node.id, next.name)
        }

        // ---- data flow ------------------------------------------------------------------------------

        /**
         * See through any run of reroute knots to the wire's real source.
         *
         * A reroute compiles to nothing at all — it exists only so the layout can run a long wire along a
         * clear lane — so the value must arrive exactly as if it were not there. Resolving it HERE rather
         * than emitting a copy is what makes that literally true: no register, no MOVE, no trace marker,
         * and a graph means the same thing whether the formatter happened to knot it or not.
         *
         * Guarded against a ring of knots wired to each other, which is not something the editor can
         * produce but is something a hand-edited file can contain.
         */
        fun throughReroutes(start: dev.ziggle.vscript.model.Link): dev.ziggle.vscript.model.Link? {
            var link = start
            var hops = 0
            while (graph.node(link.fromNode)?.type == BuiltinNodes.REROUTE) {
                if (hops++ > graph.nodes.size) return null // a cycle of knots: no source to find
                link = graph.linkInto(link.fromNode, "In") ?: return null
            }
            return link
        }

        /** The type a struct node names. The validator has already refused one that names nothing. */
        fun declaredOf(node: Node, pin: String = BuiltinNodes.STRUCT_OF): dev.ziggle.vscript.model.StructType =
            visibleTypes.firstOrNull {
                it.name.equals(node.literals[pin]?.toString()?.trim(), true)
            } ?: error("'${node.type}' names no declared type (validator should have caught this)")

        /**
         * What a cast is reading FROM, which is whatever its Value pin carries.
         *
         * Split out of [sourceRecordOf] because a `Json` there is not a record and must not be looked up
         * as one — it is the decode case, and the whole point is that there is no source record.
         */
        fun sourceTypeOf(node: Node): dev.ziggle.vscript.model.TypeRef {
            val pin = desc(node).input("Value")
                ?: error("cast ${node.id} has no Value pin")
            // The same recursive walk the validator does — a cast usually reads a `let`, and a Hold's
            // type is whatever fed it.
            fun feeding(id: Int, p: String, depth: Int = 0): dev.ziggle.vscript.model.TypeRef? {
                if (depth > 32) return null
                val l = graph.linkInto(id, p) ?: return null
                val src = graph.node(l.fromNode) ?: return null
                val out = desc(src).output(l.fromPin) ?: return null
                return dev.ziggle.vscript.model.effectivePinType(src, out, { scope.variable(it)?.type }) { i, q ->
                    feeding(i, q, depth + 1)
                }
            }
            return dev.ziggle.vscript.model.effectivePinType(node, pin, { scope.variable(it)?.type }) { n, p ->
                feeding(n, p)
            }
        }

        /** The record a cast is reading FROM. Only asked once the source is known not to be a document. */
        fun sourceRecordOf(node: Node): dev.ziggle.vscript.model.StructType {
            val type = sourceTypeOf(node)
            return visibleTypes.firstOrNull { it.name.equals(type.name, true) }
                ?: error("cast ${node.id} reads a ${type.name}, which is no record (validator should have caught this)")
        }

        /**
         * The schema for `json as [target]`, resolved from the declarations this body can see.
         *
         * Baked as ONE constant rather than as instructions. A record's shape is fixed when the graph is
         * compiled — that is the same argument `StructValue` makes for positional fields — so emitting a
         * tree of field reads would be paying at run time, per decode, for something already known.
         */
        fun jsonSchemaFor(node: Node): dev.ziggle.vscript.json.JsonSchema {
            // From the literal's own text, parsed — so a container target keeps its arguments. Reducing it
            // to a name is what used to make `MAP<STRING, Tally>` unreachable at the root.
            val named = node.literals[BuiltinNodes.CAST_OF]?.toString()?.trim().orEmpty()
            val built = dev.ziggle.vscript.json.JsonSchema.of(
                dev.ziggle.vscript.model.TypeRef.parse(named),
                types = { name -> visibleTypes.firstOrNull { it.name.equals(name, true) } },
                enums = { name -> visibleEnums.firstOrNull { it.name.equals(name, true) }?.members },
            )
            val schema = built.schema
                ?: error("cast ${node.id}: ${built.problem} (validator should have caught this)")
            return schema.renamed(BuiltinNodes.castRenames(node))
        }

        /**
         * A typed-in value as the type the pin it sits on actually carries — see [asDeclared].
         *
         * Through [effectivePinType] rather than off the [NodeDescriptor], because the pin that most needs
         * this is a variable's: `Set`'s Value pin is declared WILDCARD so one node type can serve every
         * variable, and the FLOAT it really carries is only knowable from the variable it names.
         */
        /**
         * A folded constant, made RUNNABLE.
         *
         * **A function reference survives folding as its NAME**, because the index it occupies is a
         * property of the linked program and no program exists while a document is being read. Turning it
         * back into something callable is this compiler's job, and it was being done at exactly one depth:
         * a function-typed enum COLUMN. A function sitting inside a folded RECORD was left as a string, so
         * `val Impl: Hooks = Hooks { at: twice }` — a record whose fields are hooks — handed back
         * `'twice'` and the first call on it faulted with "nothing to call".
         *
         * That shape could not fold at all until a `val` with a written type began to (GAPS 24), which is
         * what turned a latent hole into a live one. Records nest, so this does too.
         */
        fun materialise(value: Any?, type: dev.ziggle.vscript.model.TypeRef?, depth: Int = 0): Any? {
            if (depth > 16) return value
            if (type?.isFunction == true && value is String) {
                return dev.ziggle.vscript.vm.FunctionValue(subChunkFor(value), value)
            }
            val record: dev.ziggle.vscript.vm.StructValue =
                value as? dev.ziggle.vscript.vm.StructValue ?: return asDeclared(value, type)
            // **Looked up by the DECLARED type first, not by the value's own name.** A record carries the
            // simple name its owner declared it with — `Hooks` — and an importing document reaches that
            // type through its alias, so asking the scope for `Hooks` there answers null and every field
            // was left as it was. The pin or column that carries the value knows the spelling that
            // resolves here, which is what [type] is.
            val t = scope.struct(type?.name)
                ?: scope.struct(record.type)
                ?: scope.structIgnoringVisibility(record.type)
                ?: return record
            var out = record
            for (i in 0 until record.size) {
                val ft = t.fields.getOrNull(i)?.type ?: continue
                val was = record[i]
                val now = materialise(was, ft, depth + 1)
                if (now !== was) out = out.with(i, now)
            }
            return out
        }

        fun declared(node: Node, pin: dev.ziggle.vscript.model.PinSpec?, value: Any?): Any? {
            // Guarded on the VALUE being present rather than on its kind: this began as the INT -> FLOAT
            // widening and narrowing it to Int/Long silently excluded the tile and colour cases, whose
            // stored forms are a string and an ARGB int.
            if (pin == null || value == null) return value
            val type = dev.ziggle.vscript.model.effectivePinType(node, pin) { scope.variable(it)?.type }
            return materialise(value, type)
        }

        /** Emit code placing the value of [node]'s input pin [pinName] into register [dst]. */
        fun evalInput(node: Node, pinName: String, dst: Int) {
            val d = desc(node)
            val pin = d.input(pinName)
            val link = graph.linkInto(node.id, pinName)?.let { throughReroutes(it) }
            if (link == null) {
                val literal = if (node.literals.containsKey(pinName)) node.literals[pinName] else pin?.default
                // Keyed by the PIN, not by the value, so the slot stays this pin's own and can be
                // rewritten while the script runs. See Chunk.setLiteral.
                b.emit(Op.CONST, dst, b.literalConstant("${node.id}/$pinName", declared(node, pin, literal)))
                return
            }
            val source = graph.node(link.fromNode) ?: return
            // A pin bound to a scratch register for the extent of one expansion — `?.`'s receiver. A pure
            // node owns no register, so without this the access under a `?.` would re-expand what it is
            // reading and ask the same question twice; with it, the whole guard costs one evaluation.
            pureBound[link.fromNode to link.fromPin]?.let {
                b.emit(Op.MOVE, dst, it)
                widen(source, link.fromPin, node, pin, dst)
                return
            }
            val sd = desc(source)
            if (sd.kind == NodeKind.PURE) {
                evalPure(source, link.fromPin, dst)
            } else {
                // An impure node ran earlier in the exec chain and cached its outputs.
                b.emit(Op.MOVE, dst, outReg.getValue(source.id to link.fromPin))
            }
            widen(source, link.fromPin, node, pin, dst)
        }

        /**
         * The INT → FLOAT widening, as an instruction rather than as a permission.
         *
         * `canConnect` allows the wire; without this that is ALL it allowed, and the value arrived as an
         * `Int` in a slot declared FLOAT — so a float field printed as `4` and divided as an integer. One
         * [Op.TOF] on the boundary is the whole difference between a convenience and a lie.
         *
         * Both ends through [effectivePinType], because the two pins that most need it are a variable's
         * Get and Set: they are declared WILDCARD so one node type can serve every variable, and the INT
         * or FLOAT they really carry is only knowable from the variable named.
         *
         * A WILDCARD source is left alone, and that is the known limit: a math node's `Result` is one, so
         * `var f: FLOAT = a / b` on two INTs still stores an Int. Converting on a wildcard would mean
         * converting values whose type nobody has established — including the String that `"a" + "b"`
         * produces through those same pins.
         */
        fun widen(from: Node, fromPin: String, to: Node, toPin: dev.ziggle.vscript.model.PinSpec?, dst: Int) {
            if (toPin == null) return
            val out = desc(from).output(fromPin) ?: return
            val varType = { name: String -> scope.variable(name)?.type }
            val fromType = dev.ziggle.vscript.model.effectivePinType(from, out, varType)
            val toType = dev.ziggle.vscript.model.effectivePinType(to, toPin, varType)
            if (dev.ziggle.vscript.model.widens(fromType, toType)) b.emit(Op.TOF, dst, dst)
        }

        /** Expand a pure node's expression into [dst], re-evaluating it at this use site. */
        fun evalPure(node: Node, outPin: String, dst: Int) {
            if (!pureInProgress.add(node.id)) {
                error("pure cycle at node ${node.id} (validator should have caught this)")
            }
            try {
                val d = desc(node)
                val prevNode = b.currentNodeId
                b.currentNodeId = node.id
                // A pure node's own boundary, distinct from an exec node's — the marker "step into data"
                // walks and ordinary stepping skips. Debug-only: without it a Literal is one instruction.
                if (debug) b.emit(Op.TRACE, node.id, TraceKind.PURE_ENTER)
                when {
                    // Every literal node, typed or not, is the same instruction — the type is an editor
                    // and validator concern, and by here the value is just a constant.
                    node.type in BuiltinNodes.LITERALS -> {
                        val v = if (node.literals.containsKey("Value")) node.literals["Value"]
                        else d.output("Value")?.default
                        // A Float literal node holding a whole number is the same case as a float PIN
                        // holding one — the node's own type says what it is, so make the value agree.
                        val out = materialise(v, d.output("Value")?.type)
                        b.emit(Op.CONST, dst, b.literalConstant("${node.id}/Value", out))
                    }
                    // A FRESH list per evaluation, never a constant. A constant is one object shared by the
                    // whole chunk, so appending to it anywhere would quietly grow it everywhere — and a
                    // pure node is re-expanded at every use site, which is exactly where that would bite.
                    node.type == BuiltinNodes.LITERAL_LIST -> {
                        val mark = b.mark()
                        b.emit(Op.NEWLIST, dst)
                        val slot = b.reg()
                        // Only the generated element pins — Of and Count describe the node, they are not in it.
                        for (pin in d.dataInputs) {
                            if (pin.name in BuiltinNodes.SHAPE_PINS.getValue(BuiltinNodes.LITERAL_LIST)) continue
                            evalInput(node, pin.name, slot)
                            b.emit(Op.APPEND, dst, slot)
                        }
                        b.release(mark)
                    }
                    // Short-circuiting, unlike AND/OR: the jump IS somewhere for the untaken arm not to
                    // happen. Both arms are pure, so the only difference is that the skipped one costs
                    // nothing — including the whole tree of pure nodes feeding it.
                    // `a ?: b`. Evaluate the value where the result goes, and only reach for the fallback
                    // when it turned out to be nothing — the same jump SELECT uses, so an expensive
                    // fallback costs nothing on the passes that did not need it.
                    //
                    // No new opcode: `Op.EQ` runs `Values.eq`, which already answers about null.
                    node.type == BuiltinNodes.OR_ELSE -> {
                        val mark = b.mark()
                        evalInput(node, BuiltinNodes.OR_ELSE_VALUE, dst)
                        val absent = b.reg()
                        b.emit(Op.CONST, absent, b.constant(null))
                        b.emit(Op.EQ, absent, dst, absent)
                        // Jump when it is NOT absent, which is when the value stands as the result.
                        val present = b.emit(Op.JMPF, absent, 0)
                        evalInput(node, BuiltinNodes.OR_ELSE_FALLBACK, dst)
                        b.patch(present)
                        b.release(mark)
                    }
                    // `a?.b`. The receiver into a scratch register, then either nothing or the access —
                    // which reads that register through [pureBound] rather than re-expanding the receiver.
                    node.type == BuiltinNodes.IF_PRESENT -> {
                        val mark = b.mark()
                        val it = b.reg()
                        evalInput(node, BuiltinNodes.IF_PRESENT_VALUE, it)
                        val absent = b.reg()
                        b.emit(Op.CONST, absent, b.constant(null))
                        b.emit(Op.EQ, absent, it, absent)
                        val present = b.emit(Op.JMPF, absent, 0)
                        b.emit(Op.CONST, dst, b.constant(null))
                        val skip = b.emit(Op.JMP, 0)
                        b.patch(present)
                        val key = node.id to BuiltinNodes.IF_PRESENT_IT
                        pureBound[key] = it
                        try {
                            evalInput(node, BuiltinNodes.IF_PRESENT_THEN, dst)
                        } finally {
                            pureBound.remove(key)
                        }
                        b.patch(skip)
                        b.release(mark)
                    }
                    // A narrowing is a claim about a TYPE, so there is nothing to run: the value goes
                    // straight through. See BuiltinNodes.NARROW — it prints as its input too.
                    node.type == BuiltinNodes.NARROW -> evalInput(node, "Value", dst)
                    node.type == BuiltinNodes.SELECT -> {
                        val mark = b.mark()
                        val cond = b.reg()
                        evalInput(node, "Condition", cond)
                        val jf = b.emit(Op.JMPF, cond, 0)
                        evalInput(node, "If True", dst)
                        val skip = b.emit(Op.JMP, 0)
                        b.patch(jf)
                        evalInput(node, "If False", dst)
                        b.patch(skip)
                        b.release(mark)
                    }
                    // A cast: read the named fields out of the source and build the target from them.
                    // The same instructions a hand-written `Vec2i { x: p.x, y: p.y }` emits, which is
                    // the point — the node exists so the SOURCE can print back as a cast, not because
                    // casting is a different operation at run time.
                    // A cast whose source is a DOCUMENT: one host call with the schema beside it, rather
                    // than the field reads below. There are no fields to read — a document has whatever
                    // the file had — so the target's declaration travels as a constant and the reader
                    // checks the data against it, naming the path where they disagree.
                    node.type == BuiltinNodes.CAST && sourceTypeOf(node).name.equals("Json", true) -> {
                        val mark = b.mark()
                        val window = b.regs(2)
                        evalInput(node, "Value", window)
                        b.emit(Op.CONST, window + 1, b.constant(jsonSchemaFor(node)))
                        b.emit(Op.CALL, b.host(JSON_DECODE_HOST), window, Op.packCounts(2, 1))
                        b.emit(Op.MOVE, dst, window)
                        b.release(mark)
                    }
                    node.type == BuiltinNodes.CAST -> {
                        val target = declaredOf(node, BuiltinNodes.CAST_OF)
                        val from = sourceRecordOf(node)
                        val renames = BuiltinNodes.castRenames(node)
                        val mark = b.mark()
                        val src = b.reg()
                        evalInput(node, "Value", src)
                        val window = b.regs(target.fields.size.coerceAtLeast(1))
                        target.fields.forEachIndexed { i, f ->
                            val wanted = renames[f.name] ?: f.name
                            val index = from.fields.indexOfFirst { it.name.equals(wanted, true) }
                            if (index < 0) {
                                error("'${target.name}.${f.name}' has no source (validator should have caught this)")
                            }
                            b.emit(Op.GETFIELD, window + i, src, index)
                        }
                        val shape = b.constant(StructShape(target.name, target.fields.map { it.name }))
                        b.emit(Op.NEWSTRUCT, dst, shape, window)
                        b.release(mark)
                    }
                    // One member of a choice: its NAME, as a constant. Nothing to evaluate — the whole
                    // design decision is that a member IS its name — so this is a LOADK and no more, which
                    // is why the node can be pure and why it costs nothing to write `Phase.Chop` inline
                    // wherever it is read.
                    node.type == BuiltinNodes.ENUM_OF -> {
                        val named = node.literals[BuiltinNodes.ENUM_TYPE]?.toString()?.trim().orEmpty()
                        val t = visibleEnums.firstOrNull { it.name.equals(named, true) }
                            ?: error("no choice named '$named' (validator should have caught this)")
                        val written = node.literals[BuiltinNodes.ENUM_MEMBER]?.toString()?.trim()
                        // As the DECLARATION spells it. The literal may differ in case — a hand-edited file,
                        // or a member renamed since — and every comparison downstream is string equality, so
                        // handing back what was typed would make `Phase.chop != Phase.Chop`.
                        val member = t.member(written)
                            ?: error("'$named' has no member '$written' (validator should have caught this)")
                        b.emit(Op.CONST, dst, b.constant(member))
                    }
                    // One column of an enum's table, looked up by the member's NAME.
                    //
                    // Two constants and a host call: the member names in declaration order, and that
                    // field's column in the same order. The lookup is "find the name, take the same
                    // position" — which works only because a member IS its name at run time, so the
                    // invariant that keeps documents legible is also what makes this need no new opcode,
                    // no jump chain and no VM change.
                    //
                    // Not folded when the member is statically known. It could be, and the printer would
                    // then give back the value instead of `Target.WildKebbit.anchor`.
                    // Every member, as one constant list. A member is its NAME at run time, so the
                    // member list IS the value — there is nothing to evaluate and nothing to call.
                    node.type == BuiltinNodes.ENUM_VALUES -> {
                        val named = node.literals[BuiltinNodes.ENUM_TYPE]?.toString()?.trim().orEmpty()
                        val t = visibleEnums.firstOrNull { it.name.equals(named, true) }
                            ?: error("no choice named '$named' (validator should have caught this)")
                        b.emit(Op.CONST, dst, b.constant(ArrayList<Any?>(t.members)))
                    }
                    node.type == BuiltinNodes.ENUM_FIELD -> {
                        val named = node.literals[BuiltinNodes.ENUM_TYPE]?.toString()?.trim().orEmpty()
                        val t = visibleEnums.firstOrNull { it.name.equals(named, true) }
                            ?: error("no choice named '$named' (validator should have caught this)")
                        val field = node.literals[BuiltinNodes.STRUCT_FIELD]?.toString()?.trim()
                        // Through `asDeclared`, exactly as a typed-in pin value is. A TILE is a string in
                        // the document and a record at run time, so a column baked verbatim would hand
                        // back `"2452,3223,0"` and `s.at.x` would be a GETFIELD on a String. The document
                        // keeps the form the pickers edit; the conversion happens here, once, on the way
                        // into the constant pool.
                        val type = t.field(field)?.type
                        // A HANDLER column: the document stores the function's NAME, and the index it
                        // occupies is a property of the linked program — so it is resolved here, in the
                        // same breath as a TILE's string becoming a record, and for the same reason.
                        // Through [materialise], which is the same conversion this site used to spell
                        // inline — a function-typed column's name becomes a value — plus the one it was
                        // missing: a RECORD column whose own fields are functions.
                        val column = ArrayList(t.column(field).map { materialise(it, type) })
                        val mark = b.mark()
                        // Consecutive, because a host call names a base and a count — the same window
                        // shape `hostCall` builds.
                        val w = b.regs(3)
                        b.emit(Op.CONST, w, b.constant(ArrayList<Any?>(t.members)))
                        b.emit(Op.CONST, w + 1, b.constant(column))
                        evalInput(node, "Value", w + 2)
                        b.emit(Op.CALL, b.host(ENUM_FIELD_HOST), w, Op.packCounts(3, 1))
                        b.emit(Op.MOVE, dst, w)
                        b.release(mark)
                    }
                    // A record, built fresh, from a contiguous window — the same shape a call uses, and for
                    // the same reason: one instruction, however many fields.
                    node.type == BuiltinNodes.STRUCT_MAKE -> {
                        val t = declaredOf(node)
                        val mark = b.mark()
                        val window = b.regs(t.fields.size.coerceAtLeast(1))
                        t.fields.forEachIndexed { i, f -> evalInput(node, f.name, window + i) }
                        val shape = b.constant(StructShape(t.name, t.fields.map { it.name }))
                        b.emit(Op.NEWSTRUCT, dst, shape, window)
                        b.release(mark)
                    }
                    // One field out of one. Break has a pin per field, and each is compiled where it is
                    // READ — a pure node is an expression, so an unused field costs nothing at all.
                    node.type == BuiltinNodes.STRUCT_SPLIT -> {
                        val t = declaredOf(node)
                        val index = t.fields.indexOfFirst { it.name == outPin }
                        if (index < 0) error("struct.split has no field '$outPin' (validator should have caught this)")
                        val mark = b.mark()
                        val src = b.reg()
                        evalInput(node, "Value", src)
                        b.emit(Op.GETFIELD, dst, src, index)
                        b.release(mark)
                    }
                    // One field out. The same instruction Break uses — the difference between the two is
                    // which pins the node shows, not what it compiles to.
                    node.type == BuiltinNodes.STRUCT_GET -> {
                        val t = declaredOf(node)
                        val want = node.literals[BuiltinNodes.STRUCT_FIELD]?.toString()?.trim()
                        val index = t.fields.indexOfFirst { it.name.equals(want, true) }
                            .takeIf { it >= 0 } ?: 0
                        val mark = b.mark()
                        val src = b.reg()
                        evalInput(node, "Value", src)
                        b.emit(Op.GETFIELD, dst, src, index)
                        b.release(mark)
                    }
                    // A COPY with one field replaced. The record goes into dst first, because SETFIELD's
                    // destination is also its source — see the note there — and the copy happens inside
                    // StructValue.with, so the record on the wire in is untouched.
                    node.type == BuiltinNodes.STRUCT_SET -> {
                        val t = declaredOf(node)
                        val want = node.literals[BuiltinNodes.STRUCT_FIELD]?.toString()?.trim()
                        val index = t.fields.indexOfFirst { it.name.equals(want, true) }
                            .takeIf { it >= 0 } ?: 0
                        val mark = b.mark()
                        evalInput(node, "Value", dst)
                        val value = b.reg()
                        evalInput(node, t.fields.getOrNull(index)?.name ?: "Value", value)
                        b.emit(Op.SETFIELD, dst, index, value)
                        b.release(mark)
                    }
                    // A call to a function that only computes. Same calling convention as the impure
                    // one — arguments in a window, a frame pushed — but the result lands in the scratch
                    // register the reader gave us instead of a stable one, because a pure node is expanded
                    // at every use site and has no stable slot to own.
                    //
                    // It really does call, once per output pin read. That is the pure-node bargain
                    // everywhere else in this compiler too: no exec wire to place, paid for by repetition.
                    node.type == BuiltinNodes.CALL -> {
                        val name = node.callee
                            ?: error("Call node ${node.id} names no function (validator should have caught this)")
                        // Through the scope, not the document: a call in EXPRESSION position crosses an
                        // import boundary exactly as the statement form does. Resolving this one locally
                        // was invisible to every test that wired a call into an exec chain, because that
                        // is the other path entirely.
                        val target = scope.function(name)
                            ?: error("no function '$name' (validator should have caught this)")
                        // The same resolver as the statement form: an expression-bodied function can recurse
                        // too, and having two ways to reach a sub-chunk index is how one of them stops
                        // handling the case the other learned.
                        val idx = subChunkFor(name)
                        val args = d.dataInputs
                        val rets = d.dataOutputs
                        val mark = b.mark()
                        val window = b.regs(maxOf(args.size, rets.size).coerceAtLeast(1))
                        args.forEachIndexed { i, pin -> evalInput(node, pin.name, window + i) }
                        b.emit(Op.CALLG, idx, window, Op.packCounts(target.params.size, rets.size))
                        val slot = rets.indexOfFirst { it.name == outPin }
                        b.emit(Op.MOVE, dst, window + maxOf(slot, 0))
                        b.release(mark)
                    }
                    node.type == BuiltinNodes.LIST_COUNT_OF -> {
                        val mark = b.mark()
                        val list = b.reg()
                        evalInput(node, "List", list)
                        b.emit(Op.LEN, dst, list)
                        b.release(mark)
                    }
                    node.type == BuiltinNodes.LIST_AT -> {
                        val mark = b.mark()
                        val list = b.reg()
                        val index = b.reg()
                        evalInput(node, "List", list)
                        evalInput(node, "Index", index)
                        b.emit(Op.INDEX, dst, list, index)
                        b.release(mark)
                    }
                    // A function, as a value. No call and nothing to evaluate: the linker has already
                    // decided which sub-chunk the name means, so this is a constant — the same instruction
                    // a number is, holding an index instead of a number.
                    node.type == BuiltinNodes.FUNCTION_REF -> {
                        val name = node.literals[BuiltinNodes.FUNCTION_REF_NAME]?.toString()
                            ?: error("function reference ${node.id} names nothing (validator should have caught this)")
                        val template = b.constant(FunctionValue(subChunkFor(name), name))
                        val captures = BuiltinNodes.capturesOf(node)
                        if (captures.isEmpty()) {
                            b.emit(Op.CONST, dst, template)
                        } else {
                            // A lambda that reads a local. The captured values are evaluated into a
                            // consecutive window — `Op.CLOSURE` copies a run of registers — and the value
                            // built from them is a constant with a copy step, nothing more.
                            val mark = b.mark()
                            val win = b.regs(captures.size)
                            captures.forEachIndexed { i, pin -> evalInput(node, pin, win + i) }
                            b.emit(Op.CLOSURE, dst, template, Op.packCounts(win, captures.size))
                            b.release(mark)
                        }
                    }
                    // The higher-order three, compiled as ONE loop with three endings.
                    //
                    // Inline rather than a host, and it could not be otherwise: a host is a Kotlin function
                    // the VM calls, and calling back INTO the VM from one would mean a second interpreter
                    // on the Kotlin stack — which is the thing a fiber exists to avoid, because it could
                    // not then be parked mid-way. Emitted here the call is an ordinary frame, so a script
                    // that sleeps inside a mapped function suspends and resumes like anything else.
                    node.type in HIGHER_ORDER -> {
                        val mark = b.mark()
                        val src = b.reg()
                        evalInput(node, "List", src)
                        val fv = b.reg()
                        evalInput(node, BuiltinNodes.FN_PIN.getValue(node.type), fv)
                        val n = b.reg()
                        b.emit(Op.LEN, n, src)
                        val i = b.reg()
                        b.emit(Op.CONST, i, b.constant(0))
                        val one = b.reg()
                        b.emit(Op.CONST, one, b.constant(1))
                        val cond = b.reg()
                        val item = b.reg()
                        // The call window LAST, and this is load-bearing: a frame starts at its argument
                        // base and runs UPWARD over as many registers as the callee needs, so anything
                        // this loop still wants after the call has to sit below it.
                        val win = b.regs(1)
                        val finding = node.type == BuiltinNodes.LIST_FIRST_WHERE
                        if (finding) b.emit(Op.CONST, dst, b.constant(null)) else b.emit(Op.NEWLIST, dst)
                        val top = b.here()
                        b.emit(Op.LT, cond, i, n)
                        val out = b.emit(Op.JMPF, cond, 0)
                        b.emit(Op.INDEX, item, src, i)
                        b.emit(Op.MOVE, win, item)
                        b.emit(Op.CALLV, fv, win, Op.packCounts(1, 1))
                        var found = -1
                        when (node.type) {
                            BuiltinNodes.LIST_MAP -> b.emit(Op.APPEND, dst, win)
                            BuiltinNodes.LIST_FILTER -> {
                                val skip = b.emit(Op.JMPF, win, 0)
                                b.emit(Op.APPEND, dst, item)
                                b.patch(skip)
                            }
                            else -> {
                                val skip = b.emit(Op.JMPF, win, 0)
                                b.emit(Op.MOVE, dst, item)
                                // Straight out of the loop: "first" means the rest are never asked.
                                found = b.emit(Op.JMP, 0)
                                b.patch(skip)
                            }
                        }
                        b.emit(Op.ADD, i, i, one)
                        b.emit(Op.JMP, top)
                        b.patch(out)
                        if (found >= 0) b.patch(found)
                        b.release(mark)
                    }
                    node.type == BuiltinNodes.VAR_GET -> b.emit(Op.GETG, dst, varIndex.getValue(node.variable!!))
                    node.type == BuiltinNodes.NOT -> {
                        val mark = b.mark()
                        val a = b.reg()
                        evalInput(node, "A", a)
                        b.emit(Op.NOT, dst, a)
                        b.release(mark)
                    }
                    else -> {
                        val opcode = BINARY_OPS[node.type]
                        if (opcode != null) {
                            val mark = b.mark()
                            val a = b.reg()
                            val bb = b.reg()
                            evalInput(node, "A", a)
                            evalInput(node, "B", bb)
                            b.emit(opcode, dst, a, bb)
                            b.release(mark)
                        } else {
                            pureHostCall(node, d, outPin, dst)
                        }
                    }
                }
                // The result is in `dst` and about to be consumed; the register is reused straight after, so
                // this marker is the only chance anything has to observe it. See [TraceKind.PURE_EXIT].
                if (debug) b.emit(Op.TRACE, node.id, TraceKind.PURE_EXIT, dst)
                b.currentNodeId = prevNode
            } finally {
                pureInProgress.remove(node.id)
            }
        }

        /** A pure node backed by a host function — evaluated inline, result moved into [dst]. */
        fun pureHostCall(node: Node, d: NodeDescriptor, outPin: String, dst: Int) {
            val host = d.host ?: error("pure node type '${node.type}' has no host function")
            val args = d.dataInputs
            val rets = d.dataOutputs
            val mark = b.mark()
            // A CONSTRUCTION builds the record here — see [HostKind.CONSTRUCT] and `hostCall`.
            if (d.hostKind == HostKind.CONSTRUCT) {
                val out = rets.firstOrNull() ?: error("construct '${node.type}' declares no result")
                val record = dev.ziggle.vscript.model.HostRecords.of(out.type) ?: error("construct '${node.type}' makes '${out.type.name}', which is not a host record")
                val fields = record.fields
                val window = b.regs(fields.size.coerceAtLeast(1))
                fields.forEachIndexed { i, f ->
                    val pin = args.firstOrNull { it.name.equals(f.name, ignoreCase = true) } ?: args.getOrNull(i)
                    if (pin != null) evalInput(node, pin.name, window + i) else b.emit(Op.CONST, window + i, b.constant(null))
                }
                val shape = b.constant(StructShape(record.name, fields.map { it.name }))
                b.emit(Op.NEWSTRUCT, dst, shape, window)
                b.release(mark)
                return
            }
            val window = b.regs(maxOf(args.size, rets.size).coerceAtLeast(1))
            args.forEachIndexed { i, pin -> evalInput(node, pin.name, window + i) }
            // A pure node is by definition side-effect free, so it never needs the actuator.
            b.emit(Op.CALL, b.host(host), window, Op.packCounts(args.size, rets.size))
            val outIndex = rets.indexOfFirst { it.name == outPin }.coerceAtLeast(0)
            b.emit(Op.MOVE, dst, window + outIndex)
            b.release(mark)
        }
    }

    private companion object {
        /**
         * The host backing [BuiltinNodes.ENUM_FIELD].
         *
         * Named here rather than on the descriptor, because this node is lowered by the compiler like the
         * rest of the enum and struct family — it is not an ordinary `host = …` call whose pins match the
         * host's arguments. Two of its three arguments are constants the compiler builds from the
         * declaration; only the third comes off a pin.
         */
        const val ENUM_FIELD_HOST = "vscript.enumField"

        /**
         * The host backing a cast whose source is a document — `json as Doc`.
         *
         * Named here for the same reason [ENUM_FIELD_HOST] is: the node it belongs to is `value.cast`,
         * whose descriptor carries no host at all, and one of the two arguments is a constant the compiler
         * builds from the target's declaration rather than a value off a pin.
         */
        const val JSON_DECODE_HOST = "vscript.jsonDecode"

        val BINARY_OPS: Map<String, Int> = mapOf(
            BuiltinNodes.ADD to Op.ADD,
            BuiltinNodes.SUB to Op.SUB,
            BuiltinNodes.MUL to Op.MUL,
            BuiltinNodes.DIV to Op.DIV,
            BuiltinNodes.MOD to Op.MOD,
            BuiltinNodes.EQ to Op.EQ,
            BuiltinNodes.NE to Op.NE,
            BuiltinNodes.LT to Op.LT,
            BuiltinNodes.LE to Op.LE,
            BuiltinNodes.GT to Op.GT,
            BuiltinNodes.GE to Op.GE,
            BuiltinNodes.AND to Op.AND,
            BuiltinNodes.OR to Op.OR,
        )
    }
}

/**
 * A typed-in whole number, as the type it was typed INTO.
 *
 * `var x: FLOAT = 4` and `Point { x: 4 }` stay legal after `canConnect` stopped widening INT to FLOAT,
 * because a literal has no type until it is placed. But *legal* is not enough on its own: stored verbatim,
 * the run holds an `Int` in a slot the author declared FLOAT, so `"" + p.x` prints `4` and `p.x / 2` does
 * integer division. The declared type has to be true of the value, and here is the one place that costs
 * nothing — at COMPILE time, not at lowering, so the document still holds what was written and prints back
 * as `4` rather than growing a `.0` nobody typed.
 *
 * Only this direction. A float typed into an INT pin is refused by `Lower.accepts`, because rounding
 * somebody's number for them is a decision, not a conversion.
 */
private fun asDeclared(value: Any?, type: dev.ziggle.vscript.model.TypeRef?): Any? = when {
    type?.builtin == dev.ziggle.vscript.model.PinType.FLOAT && (value is Int || value is Long) ->
        (value as Number).toDouble()
    // A tile and a colour are RECORDS to the language and a string / an ARGB int in the document. The
    // document keeps the form the pickers edit and every existing file already holds; the conversion
    // happens here, once, on the way into the constant pool — so `t.plane` is an ordinary GETFIELD and
    // nothing in the VM had to learn what a tile is.
    //
    // **Asked of the TYPE, not listed here.** Both used to be branches naming a `PinType`; both are host
    // records now, and a domain adding a third stored form needs no edit to this file.
    else -> dev.ziggle.vscript.model.HostRecords.read(type, value)
}

/**
 * What a run's variables start as.
 *
 * Public and shared, because two places need the answer — the compiler, which stamps it onto every chunk so
 * one driven directly (`graph call`, and the tests) has its variables seeded, and the runtime, which seeds
 * the interpreter before a run. Two copies of this rule is two copies that can disagree, and the shape of
 * that bug is a variable that reads null in one path and a record in the other.
 */
fun startingGlobals(graph: Graph): List<Any?> =
    graph.variables.map { asDeclared(it.default, it.type) ?: zeroOf(graph, it.type, 0) }

/**
 * The same, across every document a run spans.
 *
 * Concatenated in the closure's own document order, so the slot a variable lands on here is the slot
 * [ImportScope.variableSlot] computed for it — the two must agree or a run starts with every imported
 * variable's default one document out of place. Each document's zeros are built against ITS OWN
 * declarations, because a record-typed variable's zero comes from the struct that document declared.
 */
fun startingGlobals(closure: ImportClosure): List<Any?> =
    closure.startingGlobals { doc, v -> asDeclared(v.default, v.type) ?: zeroOf(doc, v.type, 0) }

/**
 * A starting value for a variable with no default: a record with each field at its own type's zero.
 *
 * Terminates because the validator refuses a record that contains itself — the depth guard is a backstop
 * for a graph that reached here unvalidated, not the actual defence.
 */
private fun zeroOf(graph: Graph, type: dev.ziggle.vscript.model.TypeRef, depth: Int): Any? {
    if (depth > 16) return null
    val t = graph.struct(type.name) ?: return null
    return dev.ziggle.vscript.vm.StructValue(
        t.name,
        t.fields.map { it.name },
        t.fields.map { f ->
            // A field's DECLARED default first — that is what makes `single State { laps: INT = 3 }`
            // start at 3 rather than at the type's zero, and it costs nothing for a record that has none.
            //
            // Through [asDeclared], for exactly the reason the ENUM_FIELD column above is: a TILE is a
            // STRING in the document and a record at run time, so a default baked verbatim hands back
            // `"1802,3503,0"` and the first `t.x` is a GETFIELD on a String — from a document the
            // compiler accepted. `single Entry { seedTable: Tile = tile(1802, 3503, 0) }` reached a live
            // run that way: the string passed through every node it was given to (a TILE pin parses one
            // happily) and only failed several calls deep, inside `distanceTo`, where a typed `fn`
            // finally read a field off it.
            //
            // This path in particular, because an all-literal `single` gets NO initialiser prologue —
            // `Lower` skips it precisely when every field is inline — so this is the only place its
            // starting value is ever built, and the only place the conversion can happen.
            asDeclared(f.default, f.type) ?: when (f.type.builtin) {
                dev.ziggle.vscript.model.PinType.INT -> 0
                dev.ziggle.vscript.model.PinType.FLOAT -> 0.0
                dev.ziggle.vscript.model.PinType.BOOL -> false
                dev.ziggle.vscript.model.PinType.STRING, dev.ziggle.vscript.model.PinType.ENUM -> ""
                dev.ziggle.vscript.model.PinType.LIST -> emptyList<Any?>()
                // **A map's zero is an empty map, exactly as a list's is an empty list**, and its absence
                // here was silent for as long as nothing looked. A `MAP` field's declared default is almost
                // always `emptyMap()`, which is a CALL — so `asDeclared` cannot fold it and the field fell
                // to `else`, which is null. A single is initialised by a prologue that would have run the
                // call, so a live script never noticed; what noticed was `writeJson`, on a save that
                // happened before the prologue ran. It wrote `"budgets": null`, and the next wake read it
                // back and stopped the script with `budgets: expected an object, found nothing`.
                dev.ziggle.vscript.model.PinType.MAP -> emptyMap<Any?, Any?>()
                null -> zeroOf(graph, f.type, depth + 1)
                else -> null
            }
        }.toTypedArray(),
    )
}
