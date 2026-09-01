package dev.ziggle.vscript.text

import dev.ziggle.vscript.lang.AsExpr
import dev.ziggle.vscript.lang.AssertStmt
import dev.ziggle.vscript.lang.IsExpr
import dev.ziggle.vscript.lang.SafeAccessExpr
import dev.ziggle.vscript.lang.SafeItExpr
import dev.ziggle.vscript.lang.AssignStmt
import dev.ziggle.vscript.lang.BinaryExpr
import dev.ziggle.vscript.lang.BinaryOp
import dev.ziggle.vscript.lang.Block
import dev.ziggle.vscript.lang.BreakStmt
import dev.ziggle.vscript.lang.CallExpr
import dev.ziggle.vscript.lang.ContinueStmt
import dev.ziggle.vscript.lang.EntryDecl
import dev.ziggle.vscript.lang.EntryKind
import dev.ziggle.vscript.lang.Expr
import dev.ziggle.vscript.lang.ExprBlockStmt
import dev.ziggle.vscript.lang.ElvisExpr
import dev.ziggle.vscript.lang.ExprStmt
import dev.ziggle.vscript.lang.FieldAssignStmt
import dev.ziggle.vscript.lang.IfLetStmt
import dev.ziggle.vscript.lang.ForStmt
import dev.ziggle.vscript.lang.IndexExpr
import dev.ziggle.vscript.lang.LambdaExpr
import dev.ziggle.vscript.lang.ListLitExpr
import dev.ziggle.vscript.lang.IfStmt
import dev.ziggle.vscript.lang.LetStmt
import dev.ziggle.vscript.lang.LiteralExpr
import dev.ziggle.vscript.lang.MemberExpr
import dev.ziggle.vscript.lang.NameExpr
import dev.ziggle.vscript.lang.NotExpr
import dev.ziggle.vscript.lang.ReturnStmt
import dev.ziggle.vscript.lang.Span
import dev.ziggle.vscript.lang.Stmt
import dev.ziggle.vscript.lang.StructLitExpr
import dev.ziggle.vscript.lang.TernaryExpr
import dev.ziggle.vscript.lang.TryStmt
import dev.ziggle.vscript.lang.WhenStmt
import dev.ziggle.vscript.lang.WhileStmt
import dev.ziggle.vscript.json.JsonSchema
import dev.ziggle.vscript.model.FunctionPin
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.StructType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.vm.Chunk
import dev.ziggle.vscript.vm.ChunkBuilder
import dev.ziggle.vscript.vm.FunctionValue
import dev.ziggle.vscript.vm.StructValue
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.Op
import dev.ziggle.vscript.vm.TraceKind
import dev.ziggle.vscript.vm.ProgramBuilder
import dev.ziggle.vscript.vm.StructShape

/**
 * The host that reads one column of an enum — `GraphCompiler`'s, by the same name.
 *
 * Named here rather than shared through a constant because the two front ends agree on a HOST NAME, which
 * is the loosest coupling that still guarantees they mean the same thing: the registry binds it once.
 */
private const val ENUM_FIELD_HOST = "vscript.enumField"

/** The decoder behind `x as Record` — `GraphCompiler`'s, by the same name and with the same schema. */
/** What a failing `assert` calls. Two, so "no sides" needs no sentinel value. */
const val ASSERT_HOST = "test.assert"
const val ASSERT_COMPARE_HOST = "test.assertCompare"

/** `x is Point` — shared with the graph front end so the two cannot disagree about what a type IS. */
private const val IS_TYPE_HOST = "vscript.isType"

/** `Skill.of("Farming")` — a name read back as a member, or null. */
private const val ENUM_OF_HOST = "vscript.enumOf"

private const val JSON_DECODE_HOST = "vscript.jsonDecode"


/** A refusal that names a place in the SOURCE — the text surface's whole reason for existing. */
class TextCompileError(val span: Span, message: String) : Exception(message)

/**
 * One handler, compiled, and the little about its origin a runtime needs.
 *
 * The text counterpart of `CompiledEntry` — and deliberately not the same class, because that one carries
 * a `Graph` and a `Node` and this side has neither.
 *
 *  - [site] is the authoring id a fault, a breakpoint and a stack frame are anchored to (see [Sites]);
 *  - [document] is the reference it was reached by, so a complaint lands in the right file;
 *  - [name] is what the document calls itself, which is what a fiber is labelled with;
 *  - [isRoot] separates the document being run from one it imported — the root's fibers wait on the
 *    imported ones, because a library that fills a registry on start has to fill it first.
 */
class TextEntry(
    val chunk: Chunk,
    val site: Int,
    val document: String,
    val name: String,
    val isRoot: Boolean,
    /**
     * What a `test "…"` calls itself — null for every other kind.
     *
     * Beside [name] rather than replacing it: [name] is the DOCUMENT's label and a report wants both, so
     * that "goal — a chain with nothing held asks for the whole lot" reads as one sentence.
     */
    val label: String? = null,
)

/**
 * A resolved document, as bytecode.
 *
 * **It asks the [Resolution] rather than working anything out.** The skeleton decided for itself what a
 * bare name meant and matched arguments to parameters by hand; both are questions the resolver has already
 * answered, and answering them twice is answering them two ways eventually — the second way being the one
 * nobody tested. So a name is looked up by the BINDING it resolved to, not by its spelling (which is what
 * makes shadowing work), and a call's arguments arrive already in parameter order.
 *
 * What it still owns, because none of it is a question about meaning:
 *
 *  - which register holds what, and when a window may be reused;
 *  - the jump patching behind `if`, `while`, `break`, `continue` and short-circuit operators;
 *  - the `Op.TOF` on an INT flowing into a FLOAT — the resolver decides that it widens, this makes the
 *    value actually widen;
 *  - `CALL` versus `ACT`, taken from the native's own classification so an author never chooses.
 *
 * Three invariants from `docs/TEXT_FRONTEND.md` are load-bearing here: the [program] is injected rather
 * than made privately, the VM's value model is used as it is, and chunk names are namespaced by document.
 */
class TextCompiler(
    private val resolution: Resolution,
    /** The table every chunk of this compilation shares — see `docs/TEXT_FRONTEND.md`, invariant 1. */
    val program: ProgramBuilder = ProgramBuilder(),
    /**
     * One compiler per document, shared across the whole compilation.
     *
     * **An imported body is compiled against ITS OWN resolution.** A function resolves its names, its
     * types and its own variables against the document that declared it, never the one that called it —
     * get that wrong and it does not fail to compile, it silently resolves the callee's `helper` to the
     * caller's. Cached rather than made per call so a module's lambdas are numbered once.
     */
    private val delegates: MutableMap<Resolution, TextCompiler> = HashMap(),
    /**
     * Where every authoring site in this compilation is numbered — see [Sites].
     *
     * Shared with the delegates for the same reason [program] is: a breakpoint is an int with no chunk
     * beside it, so two documents numbering their own sites from one would have a breakpoint in the
     * script also arm whatever happened to take that number in a library.
     */
    val sites: Sites = Sites(),
    /**
     * Emit the apparatus a debugger needs — [Op.TRACE] markers at every statement boundary.
     *
     * **Breakpoints and stepping happen at a TRACE and nowhere else** (`Interpreter`, `Op.TRACE`), so
     * without these a text program runs perfectly and cannot be stopped, stepped, or asked which line it
     * is on. The graph compiler takes the same flag for the same reason.
     *
     * On by default, unlike the graph side's, because the cost is one instruction per statement against a
     * VM whose real expense is game verbs on the actuator drain — and a script you cannot debug is the
     * worse default.
     */
    private val debug: Boolean = true,
    /**
     * Host name → the `fake` standing in for it, for the whole of THIS compilation.
     *
     * **Shared with the delegates, and that is the entire point.** A fake is declared in a test document
     * and the call it replaces is almost never there: `pick_test` fakes `panel.amount`, which is called by
     * `tuning::knob`, two documents away and compiled as its own chunk. A table that only applied to the
     * document declaring it would substitute nothing anywhere it was needed.
     *
     * Empty for every ordinary compile. `TextFrontEnd.compile` passes nothing, so a fake can never change
     * what a script does when it is run for real — only `compileTests` fills this.
     */
    private val fakes: Map<String, FakeBinding> = emptyMap(),
) {

    /** A `fake` and the document that declared it — enough to reach its chunk from any delegate. */
    class FakeBinding(val owner: Resolution, val decl: dev.ziggle.vscript.lang.FnDecl)

    init {
        delegates.putIfAbsent(resolution, this)
    }

    private fun compilerFor(owner: Resolution): TextCompiler =
        delegates.getOrPut(owner) { TextCompiler(owner, program, delegates, sites, debug, fakes) }

    /**
     * The chunk standing in for [host], or null when nothing fakes it.
     *
     * Asked at the two places a native call is emitted. Reached through [compilerFor] rather than through
     * `owningResolution`, because the fake's document is the one importing everything else — a library's
     * compiler cannot walk to it, and does not have to: the delegate map is shared and keyed by resolution.
     */
    internal fun fakeChunkFor(host: String?): Int? {
        val f = fakes[host ?: return null] ?: return null
        return compilerFor(f.owner).chunkFor(f.decl)
    }

    /** The site id for [node], minted on first sight and attributed to this document. */
    private fun siteOf(node: Any, span: Span): Int = sites.idOf(node, span, resolution.ref)

    /**
     * Which document this is — its REFERENCE, and never its `graph` line.
     *
     * Two things key on this and both need it to be unique: the chunk table (`document to name`, so a
     * function compiles once) and the chunk's own name (which is what a debugger reads as "which document
     * is this frame in"). A reference is unique by construction — a `ModuleSet` is keyed by it — and a
     * `graph` line is a string two authors may both choose.
     *
     * It used to be `document.name ?: "script"`, which was wrong in two directions at once. Every document
     * WITHOUT a header was the same document, so a second library's `helper` silently resolved to the
     * first's chunk — the call typechecked, ran, and returned the other library's answer. And two
     * documents that both said `graph "util"` collided in exactly the same way, which needed no new
     * language feature to reach and had presumably been reachable all along.
     *
     * The `graph` line survives as a LABEL — see [TextEntry.name], where a name an author chose is worth
     * more than a path and nothing keys on it.
     */
    private val docRef: String get() = resolution.ref

    /**
     * Where every document variable in the run lives, whichever document declared it.
     *
     * The slots are allocated once across the closure (see `GlobalSlots`), but the map from binding to
     * slot is per-document — so reading an imported variable means asking the document that declared it.
     */
    private val slots: Map<DocumentBinding, Int> by lazy {
        val all = LinkedHashMap<DocumentBinding, Int>()
        val seen = HashSet<Resolution>()
        // Re-exports included, and transitively — a variable declared in a barrel is read through the
        // front door that forwards it, and the door does not import it.
        fun add(r: Resolution) {
            if (!seen.add(r)) return
            all.putAll(r.globalSlots)
            val imports = r.imported
            val reachable = imports.aliased.values + imports.unqualified +
                imports.named.values.map { it.first } + r.reExports
            for (m in reachable) add(m.resolution)
        }
        add(resolution)
        all
    }

    /**
     * Fold a constant expression belonging to THIS document — see `Body.fold`.
     *
     * The way in from another document's compiler. The builder is a throwaway: folding emits nothing, and
     * the only thing it can add to the program is a function's chunk, which is exactly right — a function
     * value in a folded table has to name a real chunk in the run's one table.
     */
    internal fun foldGlobal(e: Expr?, want: TypeRef?, depth: Int): Any? =
        Body(ChunkBuilder("@fold", program = program)).foldFor(e, want, depth)

    /**
     * Every module of this run, importers last — the root is not among them.
     *
     * Depth-first over the import graph, each document once, re-exports included: a barrel that forwards a
     * library is how that library is reached, and a document reached only that way still has handlers.
     */
    /**
     * Every module of this run by its reference, memoised — how [recordOwnedBy] finds a declaration.
     *
     * A `lazy` rather than a call, because a field read is not a rare event and [modules] walks the whole
     * import graph each time it is asked.
     */
    private val closure: Map<String, Module> by lazy { modules().associateBy { it.ref } }

    private fun modules(): List<Module> {
        val out = LinkedHashMap<String, Module>()
        fun walk(r: Resolution) {
            val imports = r.imported
            val reachable = imports.unqualified + imports.aliased.values +
                imports.named.values.map { it.first } + r.reExports
            for (m in reachable) if (out.put(m.ref, m) == null) walk(m.resolution)
        }
        walk(resolution)
        return out.values.toList()
    }

    /**
     * Every `on <kind>` this run has, across the import closure, in the order the runtime spawns them.
     *
     * **An imported handler runs only if it said `always`.** That is not visibility — an entry has no name
     * to export — it is the separate question of whether it RUNS for an importer, and the corpus leans on
     * it: `core/breaks`, `core/day` and `core/stats` are libraries whose whole job is an `always on wake`
     * / `always on sleep` pair. Compiling only the root's entries would have left those silently doing
     * nothing, which is the failure mode `SkippedDocument` exists to shout about on the other side.
     *
     * Ordering is [EntryKind.innermostFirst]'s, so a library initialises before its importer and puts
     * itself away after — the same answer the graph front end gives, read from the same place.
     */
    fun compileEntries(kind: EntryKind): List<TextEntry> {
        val docs = ArrayList<Pair<Resolution, Boolean>>()
        docs += resolution to true
        for (m in modules()) docs += m.resolution to false
        // `modules()` is importers-last, so the list above is outermost-first as it stands.
        val ordered = if (kind.innermostFirst) docs.asReversed() else docs

        val out = ArrayList<TextEntry>()
        for ((owner, isRoot) in ordered) {
            val declared = owner.document.decls.filterIsInstance<EntryDecl>()
                .filter { it.kind == kind && (isRoot || it.isAlways) }
            for (entry in declared) {
                val compiler = compilerFor(owner)
                out += TextEntry(
                    // The CLOSURE's starting values, not the owner's. A document's own snapshot is a
                    // prefix — every module numbers into one array but finishes resolving before the ones
                    // that import it — so an imported handler seeded from its own would size the run's
                    // globals to whatever it could see and lose every slot declared after it.
                    chunk = compiler.compileEntry(kind, entry, resolution.globalDefaults),
                    site = sites.idOf(entry, entry.span, owner.ref),
                    document = owner.ref,
                    // A LABEL, not a key — the author's own word for this document when they wrote one,
                    // and otherwise where it lives. Nothing resolves by it; see [docRef] for what does.
                    name = owner.document.name ?: owner.ref,
                    isRoot = isRoot,
                    label = entry.label,
                )
            }
        }
        return out
    }

    fun compileEntry(kind: EntryKind = EntryKind.START): Chunk {
        val document = resolution.document
        val entry = document.decls.filterIsInstance<EntryDecl>().firstOrNull { it.kind == kind }
            ?: throw TextCompileError(
                document.span,
                "this document has no 'on ${kind.name.lowercase()}' to run",
            )
        return compileEntry(kind, entry)
    }

    /** One named handler, compiled — see [compileEntries] for which ones a run has. */
    fun compileEntry(
        kind: EntryKind,
        entry: EntryDecl,
        /** The whole run's starting values; this document's own when it is the only one. */
        globals: List<Any?> = resolution.globalDefaults,
    ): Chunk {
        val document = resolution.document
        // **A test's name goes in its chunk's name.** Every other kind is one per document, so the kind
        // alone identifies it; tests are many, and two chunks called `goal#test` are indistinguishable in a
        // stack frame, a breakpoint and a failure report — which is the whole audience for a chunk name.
        val name = buildString {
            append(docRef).append('#').append(kind.name.lowercase())
            entry.label?.let { append(':').append(it) }
        }
        val b = ChunkBuilder(name, program = program)
        // **Only the ENTRY carries them.** A run seeds its globals from the chunk it spawns, and a
        // document's own snapshot is a PREFIX of the closure's once imports are in — every module numbers
        // into one array but finishes resolving before the ones that import it. Putting a prefix on a
        // sub-chunk would be a list that is never read and wrong if it ever were.
        b.globals = globals
        // **The declaration is the chunk's default site.** A handler's prologue calls and its closing
        // `HALT` are the compiler's instructions, not the author's, and an unattributed instruction is
        // one a stack frame cannot name — so they land on `on start` itself, which is the honest answer
        // to "where in the file is this".
        b.currentNodeId = siteOf(entry, entry.span)
        Body(b, isEntry = true).run {
            // **Seeding first, innermost first.** A document's computed variables are set by a prologue,
            // and a library's have to be in place before the document that imported it can read them.
            // `Chunk.prologueOf` is what stops a second entry re-seeding what the first already wrote.
            for (index in prologues()) b.emit(Op.CALLG, index, b.regs(1), Op.packCounts(0, 0))
            block(entry.body)
            b.emit(Op.HALT)
            publishSlots()
        }
        val chunk = b.build()
        program.link(chunk)
        return chunk
    }

    // ---------------------------------------------------------------------------------------------------

    /**
     * A function's body, compiled once and shared by every call to it.
     *
     * Keyed in the program the whole compilation shares, and **registered before the body runs** — that is
     * `ProgramBuilder.indexOf`'s contract, and it is what lets a function call itself: the recursive call
     * resolves to the slot already reserved rather than compiling a second copy or recursing forever.
     *
     * Named `<document>::<function>`, which is `GraphCompiler`'s convention for the same thing. The
     * debugger reads a chunk name as "which document is this frame in", and that answer should not depend
     * on which front end produced the frame.
     */
    /**
     * How deep the compiler is inside an `inline` splice — the recursion guard.
     *
     * A recursive `inline` cannot terminate: each splice contains another call to splice. Direct
     * recursion is easy to spot, but the real case is a cycle through two or three functions, so this
     * counts depth rather than inspecting names. Anything past [MAX_INLINE_DEPTH] is refused, naming the
     * function, rather than expanding until the process dies.
     */
    private var inlineDepth = 0

    /**
     * Splice [decl]'s body into [into], with its parameters already sitting in [base]..
     *
     * Called on the compiler that OWNS the declaration — its parameter bindings and locals resolve
     * against its own resolution — while emitting into the CALLER's builder, which is what makes this a
     * splice rather than a call. `Body` takes the builder as a parameter precisely so this is possible.
     *
     * **The arguments are already in registers.** That is the caller's doing and it is load-bearing: a
     * naive substitution would re-evaluate an argument once per mention, so `inline fn twice(n: Int) =
     * n + n` called as `twice(n: f())` would call `f()` twice. Binding to registers first means the body
     * reads a value, not an expression.
     */
    internal fun spliceInto(
        into: ChunkBuilder,
        decl: dev.ziggle.vscript.lang.FnDecl,
        base: Int,
        dst: Int,
        wantResult: Boolean,
    ) {
        if (inlineDepth >= MAX_INLINE_DEPTH) {
            throw TextCompileError(
                decl.span,
                "'${decl.name}' is inlined into itself — an 'inline fn' cannot be recursive, because " +
                    "splicing it would never finish",
            )
        }
        inlineDepth++
        try {
            val params = resolution.paramsOf[decl].orEmpty()
            val stmts = decl.body.stmts
            val onlyReturn = stmts.singleOrNull() as? dev.ziggle.vscript.lang.ReturnStmt
            val soleValue = onlyReturn?.values?.singleOrNull()
            if (soleValue != null) {
                // An expression body — one `return <expr>` — is the common case and needs no jump at all:
                // evaluate straight into the destination. Worth keeping separate from the general path
                // because it is what every wrapper and every `op fn` accessor looks like, and it emits
                // exactly the instructions the expression does and nothing else.
                val body = Body(into, returns = decl.results.size)
                params.forEachIndexed { i, p -> body.bindParameter(p, base + i) }
                body.value(soleValue, dst, wantResult = wantResult)
                body.publishSlots()
                return
            }
            // The general case: `return` becomes "put the result where the caller wants it, then jump
            // past the rest of the splice". See [InlineReturn] for why it cannot stay a `RET`.
            val splice = InlineReturn(dst, wantResult)
            val body = Body(into, returns = decl.results.size, inlineReturn = splice)
            params.forEachIndexed { i, p -> body.bindParameter(p, base + i) }
            body.block(decl.body)
            for (j in splice.jumps) into.patch(j)
            body.publishSlots()
        } finally {
            inlineDepth--
        }
    }

    internal fun chunkFor(decl: dev.ziggle.vscript.lang.FnDecl): Int {
        val doc = docRef
        return program.indexOf(doc to decl.name) {
            val params = resolution.paramsOf[decl].orEmpty()
            val b = ChunkBuilder("$doc::${decl.name}", paramCount = params.size, program = program)
            // A mutating extension hands `self` back — it is the first parameter, so register 0 — and
            // that is what the call site stores where the receiver was. The body never says `return self`
            // and should not have to: writing `self` IS the statement.
            val writesReceiver = bindingFor(decl)?.writesReceiver == true
            b.currentNodeId = siteOf(decl, decl.span)
            val body = Body(b, returns = decl.results.size, returnsSelf = writesReceiver)
            // A parameter arrives in a register the frame already holds — the first [paramCount] of them,
            // in declaration order — so it is bound rather than allocated.
            params.forEachIndexed { i, p -> body.bindParameter(p, i) }
            body.block(decl.body)
            // Falling off the end returns to the caller with nothing — unless the receiver is what this
            // hands back, in which case it is register 0 and always has been.
            if (writesReceiver) b.emit(Op.RET, 0, 1) else b.emit(Op.RET, 0, 0)
            body.publishSlots()
            b.build()
        }
    }

    /**
     * Every prologue this run needs, imports before importers.
     *
     * Depth first over the import graph, and each document only once — a diamond (two documents importing
     * one library) must seed that library once, not twice, or the second pass would overwrite whatever the
     * first had already computed.
     */
    private fun prologues(): List<Int> {
        val out = ArrayList<Int>()
        val seen = HashSet<Resolution>()
        fun visit(r: Resolution) {
            if (!seen.add(r)) return
            val imports = r.imported
            for (m in imports.unqualified) visit(m.resolution)
            for (m in imports.aliased.values) visit(m.resolution)
            for ((m, _) in imports.named.values) visit(m.resolution)
            // A re-exported barrel seeds its own variables too, and nothing else would run its prologue.
            for (m in r.reExports) visit(m.resolution)
            compilerFor(r).prologueChunk()?.let { out += it }
        }
        visit(resolution)
        return out
    }

    /**
     * The chunk that computes this document's variables, or null when every one of them is a literal.
     *
     * Marked with [Chunk.prologueOf] so the VM runs it once per RUN however many entries call it — which
     * is the guard that makes calling it from the head of every entry safe.
     */
    internal fun prologueChunk(): Int? {
        val inits = resolution.globalInits
        if (inits.isEmpty()) return null
        val doc = docRef
        return program.indexOf(doc to "@prologue") {
            val b = ChunkBuilder("$doc::@prologue", program = program)
            b.prologueOf = doc
            // Nothing in the file owns the seeding as a whole, so it belongs to the document — each
            // individual `var` still gets its own site from the initialiser it compiles.
            b.currentNodeId = siteOf(resolution.document, resolution.document.span)
            val body = Body(b)
            for (init in inits) body.seed(init)
            b.emit(Op.RET, 0, 0)
            body.publishSlots()
            b.build()
        }
    }

    /** The binding for a declaration, so the compiler can ask what the resolver decided about it. */
    private fun bindingFor(decl: dev.ziggle.vscript.lang.FnDecl): FunctionBinding? {
        resolution.extensions.values.flatten().firstOrNull { it.decl === decl }?.let { return it }
        for (m in resolution.imported.let { it.unqualified + it.aliased.values + it.named.values.map { p -> p.first } }) {
            m.resolution.extensions.values.flatten().firstOrNull { it.decl === decl }?.let { return it }
        }
        return null
    }

    /** How many lambdas have been given a name, so each gets one nobody can type. */
    private var lambdaCount = 0
    private var defaultCount = 0

    /**
     * A lambda's body, as a chunk of its own.
     *
     * Its signature is **its own parameters followed by what it captured**, which is where the VM puts
     * them: a `CALLV` passes the arguments the call site knows about, and the values the closure carries
     * land straight after them. So the two lists are concatenated here in exactly that order, and the
     * capture list's order is the resolver's.
     */
    /**
     * A chunk that evaluates one COMPUTED parameter default, compiled by the document that declared it.
     *
     * **A default that is not a literal cannot be a constant, and flattening it to one made it null.** The
     * record-field path learned this first (`= emptyMap()` became null); parameters kept the old shape
     * until a tree run died on `reclaim: fn() -> Int = noSpill` after every full inventory.
     *
     * A nullary chunk rather than an expression spliced into the caller, for two reasons. It is compiled
     * HERE, in the declaring document, so `noSpill` resolves against the scope that can see it rather than
     * against whichever document happened to omit the argument. And it RUNS per call, which is the only
     * correct reading of `= []` — folded to a constant, every caller would share one list and mutating it
     * would be somebody else's bug.
     */
    internal fun chunkForDefault(e: Expr, want: TypeRef): Int = program.indexOf(e) {
        val b = ChunkBuilder("$docRef::@default${++defaultCount}", paramCount = 0, program = program)
        b.currentNodeId = siteOf(e, e.span)
        val body = Body(b)
        val r = b.reg()
        body.value(e, r, want)
        b.emit(Op.RET, r, 1)
        body.publishSlots()
        b.build()
    }

    private fun chunkForLambda(e: LambdaExpr): Int = program.indexOf(e) {
        val doc = docRef
        val params = resolution.lambdaParamsOf[e].orEmpty()
        val captures = resolution.capturesOf[e].orEmpty()
        // A name beginning with `@`, which no author can type — the same convention the graph side uses
        // for a synthesised function, and for the same reason.
        val b = ChunkBuilder("$doc::@lambda${++lambdaCount}", paramCount = params.size + captures.size, program = program)
        b.currentNodeId = siteOf(e, e.span)
        val body = Body(b)
        params.forEachIndexed { i, p -> body.bindParameter(p, i) }
        captures.forEachIndexed { i, c -> body.bindParameter(c, params.size + i) }
        // Its own block, before the expression it hands back — a lambda is a scope, not an expression
        // with a value pinned to it. This costs nothing here because a lambda already compiles to a chunk
        // with a real body; the one-expression rule it replaces was the graph's.
        for (st in e.stmts) body.stmt(st)
        if (e.body == null) {
            // `{ }`, or a body whose last statement is not an expression: it ran, and it hands back
            // nothing. The only spelling a `fn(T)` has for a do-nothing body.
            b.emit(Op.RET, 0, 0)
        } else {
            val r = b.reg()
            body.value(e.body, r)
            b.emit(Op.RET, r, 1)
        }
        body.publishSlots()
        b.build()
    }

    /**
     * Where a `return` goes when the body it is in has been SPLICED into a caller.
     *
     * A spliced `return` cannot emit `Op.RET` — that would return from the caller, which is a different
     * function and usually a different arity. So it becomes what it means: put the results where the call
     * site expects them, then jump past the rest of the splice.
     *
     * **This is a local return, not Kotlin's non-local one.** `return` in an `inline fn`'s own body ends
     * that function, exactly as it would without the keyword — an author should not get a different
     * language for having written `inline`. Kotlin's non-local return is a separate feature about lambdas
     * PASSED to an inline function, and needs those lambdas inlined too; ours are closures called through
     * `CALLV`, so nothing here can reach past the splice.
     */
    private class InlineReturn(val dst: Int, val wantResult: Boolean) {
        /** Every `return` in the spliced body, waiting for the end of the splice to be known. */
        val jumps = ArrayList<Int>()
    }

    private inner class Body(
        val b: ChunkBuilder,
        val returns: Int = 0,
        val isEntry: Boolean = false,
        /** This body hands its receiver back — see the note in [chunkFor]. */
        val returnsSelf: Boolean = false,
        /** Set only while compiling a spliced body — see [InlineReturn]. */
        val inlineReturn: InlineReturn? = null,
    ) {

        /**
         * Which register holds each local — keyed by the BINDING, not by the name.
         *
         * That is the whole of what makes shadowing work: two `val n` in sibling blocks are two bindings,
         * and a map keyed by "n" would have the second quietly overwrite the first's register while the
         * first was still live.
         */
        private val registers = HashMap<Binding, Int>()

        /**
         * `(declaring site, name)` to register — the locals a stopped frame can show.
         *
         * **This goes in `SlotMap.outputs`, not `SlotMap.variables`.** The two are read completely
         * differently: `variables` is indexed into the run's GLOBALS (`DebugSession.scopes`), so putting a
         * register number in it would show whatever global happened to sit at that index. `outputs` is
         * read off the frame's stack and carries a site id beside the name, which is exactly a local and
         * where it was declared.
         *
         * Keyed by site as well as name so shadowing survives: two `val n` in sibling blocks are two
         * declarations at two places in the file, and a name-only key would let the second erase the
         * first while it is still live.
         */
        private val named = LinkedHashMap<Pair<Int, String>, Int>()

        /** The pc from which each of [named] holds its value — see `SlotMap.liveFrom`. */
        private val liveFrom = LinkedHashMap<Pair<Int, String>, Int>()

        /** Give [binding] a register, and remember where it was declared so a debugger can show it. */
        private fun bind(binding: Binding, register: Int) {
            registers[binding] = register
            val key = b.currentNodeId to binding.name
            named[key] = register
            // Where it starts meaning something. A pause lands at the head of a statement, so a local
            // declared BY that statement is not live yet and its register still holds the last expression's
            // scratch — reported as the local's value, which is worse than reporting nothing.
            liveFrom[key] = b.here()
        }

        /**
         * Hand the debugger this chunk's slots. Called once, after the body is emitted.
         *
         * The document variables are the whole closure's, not this document's: a stopped frame in a
         * library should still show the script's variables, and they are one array at run time.
         */
        fun publishSlots() {
            b.slots = dev.ziggle.vscript.vm.SlotMap(
                outputs = HashMap(named),
                variables = slots.entries.associate { (binding, slot) -> binding.name to slot },
                liveFrom = HashMap(liveFrom),
            )
        }

        /** Compute one document variable's starting value and write it to its slot. */
        fun seed(init: GlobalInit) {
            val outer = b.currentNodeId
            // Its own site, so `break` on the line a `var` is declared on stops while it is being
            // computed — the prologue is where that happens, and it is the only chance.
            b.currentNodeId = siteOf(init.value, init.value.span)
            val mark = b.mark()
            val r = b.reg()
            value(init.value, r, init.binding.type)
            b.emit(Op.SETG, slotOf(init.binding, init.value.span), r)
            b.release(mark)
            b.currentNodeId = outer
        }

        /** Bind a parameter to the register the frame already holds it in. */
        fun bindParameter(binding: Binding, index: Int) {
            bind(binding, index)
        }

        private val loops = ArrayDeque<Loop>()

        /**
         * Whether a `while` whose body spans [from]..now has to yield to stay legal.
         *
         * **A yield costs a whole scheduler pass — about 20ms — so one per iteration is ruinous.** It is
         * there for one shape: `while true` with no wait inside, which would otherwise spend the entire
         * tick budget every tick and be killed as a runaway. A loop whose body already calls a game verb
         * or a `delay` parks on its own, so it needs nothing; a loop that cannot park does.
         *
         * `for` never yields at all. It walks a finite collection and terminates, and the scheduler's own
         * preemption is what spreads a long one across ticks — which is exactly what the graph front end
         * relies on, having never emitted a yield in its life. Yielding per iteration turned a 246-item
         * scan into five seconds of wall clock, and the nested scan inside it into five minutes.
         */
        private inner class Loop(val top: Int) {
            val breaks = ArrayList<Int>()
        }

        fun block(block: Block) {
            for (s in block.stmts) stmt(s)
        }

        /**
         * One statement, stamped with the site a breakpoint and a stack frame name it by.
         *
         * **The statement, not the expression.** Stepping means "the next thing I wrote", and a reader
         * pointing at line 42 means the whole line — stamping every sub-expression would make one source
         * line several stops and a step over `f(g(x))` land inside `g`. Nested calls still get their own
         * site (see [emit]) so a fault inside one can say which; they are just not where a step rests.
         */
        fun stmt(s: Stmt) {
            val outer = b.currentNodeId
            val site = siteOf(s, s.span)
            b.currentNodeId = site
            // The statement boundary, as the VM understands one. A breakpoint trips here and a step rests
            // here; `fiber.currentNode` is set from it, which is what lets a paused frame say which line.
            if (debug) b.emit(Op.TRACE, site, TraceKind.NODE_ENTER)
            try {
                statement(s)
            } finally {
                b.currentNodeId = outer
            }
        }

        private fun statement(s: Stmt) {
            when (s) {
                is LetStmt -> {
                    // `val (a, b) = f()` — the results land in a window and each name takes one of them.
                    val slots = resolution.destructuredSlots[s]
                    if (slots != null) {
                        val call = s.value as? CallExpr ?: refuse(s.span, "binding several values from this")
                        val mark = b.mark()
                        val window = b.regs(maxOf(slots.size, 1))
                        // Every result is asked for, even the ones nothing binds: a name may have taken
                        // the SECOND slot, and the window is positional however the names were matched.
                        callInto(call, window, slots.size)
                        // Copied OUT of the window into registers of their own, because the window is
                        // scratch the next call will reuse and these names outlive it.
                        val kept = ArrayList<Int>()
                        slots.forEachIndexed { i, binding ->
                            if (binding == null) return@forEachIndexed
                            val r = b.reg()
                            bind(binding, r)
                            b.emit(Op.MOVE, r, window + i)
                            kept += r
                        }
                        b.release(mark + slots.size + kept.size)
                        return
                    }
                    val binding = resolution.localOf[s] ?: refuse(s.span, "a binding the resolver did not record")
                    val r = b.reg()
                    bind(binding, r)
                    value(s.value, r, binding.type)
                }

                is AssignStmt -> {
                    // `s.op` is deliberately not read: a compound assignment arrives ALREADY desugared —
                    // `n += 1` is `n = n + 1` by the time the parser is done — and `op` is carried only so
                    // the printer can put the spelling back. Applying it again compiles `n += 1` to
                    // `n = n + (n + 1)`, which is a wrong answer rather than a crash.
                    when (val target = resolution.targetOf[s] ?: refuse(s.span, "an assignment the resolver did not record")) {
                        is DocumentBinding -> {
                            val mark = b.mark()
                            val r = b.reg()
                            value(s.value, r, target.type)
                            b.emit(Op.SETG, slotOf(target, s.span), r)
                            b.release(mark)
                        }
                        else -> value(s.value, registerOf(target, s.span), target.type)
                    }
                }

                is dev.ziggle.vscript.lang.IndexAssignStmt -> {
                    // A non-list is a CALL to an `op fn set`, rewritten by the resolver — see
                    // [Resolution.indexSetOf]. A list writes its slot with one instruction.
                    val asCall = resolution.indexSetOf[s]
                    if (asCall != null) {
                        val mark = b.mark()
                        call(asCall, b.reg(), wantResult = false)
                        b.release(mark)
                    } else {
                        val mark = b.mark()
                        val list = b.reg()
                        val index = b.reg()
                        val v = b.reg()
                        value(s.target, list)
                        value(s.index, index)
                        value(s.value, v)
                        b.emit(Op.SETINDEX, list, index, v)
                        b.release(mark)
                    }
                }

                is FieldAssignStmt -> {
                    // A record is a VALUE, so this is `target = target with { field: v }`: the record is
                    // copied with one field replaced and the NAME is pointed at the copy. Nothing is
                    // written through a reference, because there are none.
                    val record = recordOfType(resolution.type(s.target), s.span)
                    val index = record.fields.indexOfFirst { it.name == s.field }
                    if (index < 0) refuse(s.span, "field '${s.field}', which the resolver accepted")
                    val mark = b.mark()
                    val copy = b.reg()
                    value(s.target, copy)
                    val v = b.reg()
                    value(s.value, v, record.fields[index].type)
                    b.emit(Op.SETFIELD, copy, index, v)
                    storeBack(s.target, copy, s.span)
                    b.release(mark)
                }

                is IfLetStmt -> {
                    val bound = resolution.boundOf[s]?.firstOrNull()
                        ?: refuse(s.span, "an 'if val' the resolver did not record")
                    val r = b.reg()
                    bind(bound, r)
                    value(s.value, r)
                    // **A presence test is `!= null`, not truthiness.** `Values.truth` accepts a Boolean
                    // or null and THROWS on anything else — deliberately, since a conditional holding a
                    // number is a compiler bug and it says so. So an option holding 7 has to be compared,
                    // not jumped on.
                    val toElse = b.emit(Op.JMPF, present(r), 0, 0)
                    block(s.then)
                    if (s.elseBranch == null) {
                        b.patch(toElse)
                    } else {
                        val toEnd = b.emit(Op.JMP, 0, 0, 0)
                        b.patch(toElse)
                        stmt(s.elseBranch)
                        b.patch(toEnd)
                    }
                }

                is WhenStmt -> {
                    // An if/else chain, which is what it is: the subject is evaluated ONCE and compared
                    // against each arm in turn, or — with no subject — each arm is its own condition.
                    val mark = b.mark()
                    val subject = s.subject?.let { val r = b.reg(); value(it, r); r }
                    val ends = ArrayList<Int>()
                    for (arm in s.arms) {
                        val cond = b.reg()
                        if (subject == null) {
                            value(arm.value, cond)
                        } else {
                            val v = b.reg()
                            value(arm.value, v)
                            b.emit(Op.EQ, cond, subject, v)
                        }
                        val next = b.emit(Op.JMPF, cond, 0, 0)
                        block(arm.body)
                        ends += b.emit(Op.JMP, 0, 0, 0)
                        b.patch(next)
                    }
                    s.elseArm?.let { block(it) }
                    for (j in ends) b.patch(j)
                    b.release(mark)
                }

                is TryStmt -> {
                    // A RANGE over the body's instructions, and the handler is what follows it. The range
                    // covers the body ONLY: an error raised inside the catch must not be caught by the
                    // same handler, or a failing handler would spin forever.
                    val bound = resolution.boundOf[s]?.firstOrNull()
                        ?: refuse(s.span, "a 'try' the resolver did not record")
                    val message = b.reg()
                    bind(bound, message)
                    val start = b.here()
                    block(s.body)
                    val skip = b.emit(Op.JMP, 0, 0, 0)
                    val end = b.here()
                    b.handler(start, end, catchPc = end, messageReg = message)
                    block(s.catch)
                    b.patch(skip)
                }

                /**
                 * `assert a == b` — check, and on failure say what each side held.
                 *
                 * **The sides are evaluated ONCE, before the comparison**, and the comparison is emitted
                 * here from their registers rather than by compiling the condition. Compiling the condition
                 * and then re-evaluating its operands to report them would run each of them twice, which
                 * for anything impure — a game read, a counter, a call — makes the report describe a
                 * different world from the one the check saw.
                 */
                is AssertStmt -> {
                    val mark = b.mark()
                    val sided = s.left != null && s.right != null
                    // [msg, left, right] — the call window, contiguous because a CALL reads it that way.
                    val w = b.regs(3)
                    val cond = b.reg()
                    if (sided) {
                        value(s.left, w + 1)
                        value(s.right, w + 2)
                        b.emit(arithOp((s.condition as BinaryExpr).op, s.span), cond, w + 1, w + 2)
                    } else {
                        value(s.condition, cond)
                    }
                    val ok = b.emit(Op.JMPT, cond, 0, 0)
                    // Everything below runs only when the check failed, so a custom message costs nothing
                    // on the passing path — which is every run of a suite that is green.
                    if (s.message != null) value(s.message, w) else b.emit(Op.CONST, w, b.constant(s.text))
                    b.emit(
                        Op.CALL,
                        b.host(if (sided) ASSERT_COMPARE_HOST else ASSERT_HOST),
                        w,
                        Op.packCounts(if (sided) 3 else 1, 0),
                    )
                    b.patch(ok)
                    b.release(mark)
                }

                is IfStmt -> {
                    val mark = b.mark()
                    val cond = b.reg()
                    value(s.condition, cond)
                    val toElse = b.emit(Op.JMPF, cond, 0, 0)
                    b.release(mark)
                    block(s.then)
                    if (s.elseBranch == null) {
                        b.patch(toElse)
                    } else {
                        val toEnd = b.emit(Op.JMP, 0, 0, 0)
                        b.patch(toElse)
                        stmt(s.elseBranch)
                        b.patch(toEnd)
                    }
                }

                is WhileStmt -> {
                    val top = b.here()
                    val loop = Loop(top)
                    val mark = b.mark()
                    val cond = b.reg()
                    value(s.condition, cond)
                    val out = b.emit(Op.JMPF, cond, 0, 0)
                    b.release(mark)
                    loops.addLast(loop)
                    block(s.body)
                    loops.removeLast()
                    // **No yield. A `while` costs what its body costs.**
                    //
                    // This used to emit `Op.YIELD` per iteration whenever the body could not park by
                    // itself, so a loop that merely COMPUTES advanced one iteration per scheduler pass —
                    // about 20ms each. Measured on a live bank organiser: a 640-iteration plan loop took
                    // 12,801ms, and the same loop written as a `for` took 1ms. Nothing in the source, the
                    // docs or the output named the cause; the only symptom was a slow script.
                    //
                    // The yield was never what kept the client safe, and that is the whole argument for
                    // dropping it. The interpreter already preempts on the 3ms instruction budget and
                    // leaves the fiber RUNNABLE, so a frame cannot be held however long a loop runs; and
                    // `Scheduler.runawayPreemptionLimit` already fails a fiber that spins without ever
                    // parking, naming it. Both mechanisms are untouched by this.
                    //
                    // What the yield actually bought was letting `while !ready() { }` with no delay poll
                    // once a tick instead of spinning. That pattern is already an authoring error —
                    // LANGUAGE.md §14 says every loop waits — so the trade was: quietly rescue a mistake,
                    // and quietly make every correct computational loop a thousand times slower. Now the
                    // mistake trips the runaway guard and says so, which is the failure everyone wanted.
                    b.emit(Op.JMP, top, 0, 0)
                    b.patch(out)
                    for (j in loop.breaks) b.patch(j)
                }

                is ForStmt -> {
                    val bindings = resolution.loopBindingsOf[s] ?: refuse(s.span, "a loop the resolver did not record")
                    val mark = b.mark()
                    val cursor = b.reg()
                    val list = b.reg()
                    value(s.list, list)
                    b.emit(Op.ITER, cursor, list)
                    // The loop variables live BELOW the watermark that gets released, because they have to
                    // survive every pass of the body rather than the expression that filled them.
                    val element = b.reg()
                    bind(bindings[0], element)
                    val index = if (bindings.size > 1) b.reg().also { bind(bindings[1], it) } else -1
                    if (index >= 0) b.emit(Op.CONST, index, b.constant(0))

                    val top = b.here()
                    val loop = Loop(top)
                    val exhausted = b.emit(Op.ITERNEXT, element, cursor, 0)
                    loops.addLast(loop)
                    block(s.body)
                    loops.removeLast()
                    if (index >= 0) {
                        val one = b.reg()
                        b.emit(Op.CONST, one, b.constant(1))
                        b.emit(Op.ADD, index, index, one)
                    }
                    // NO yield: a `for` walks a finite collection and terminates. Neither does a
                    // `while` any more — see the note there for why the budget preempt is the real guard.
                    b.emit(Op.JMP, top, 0, 0)
                    b.patch(exhausted)
                    for (j in loop.breaks) b.patch(j)
                    b.release(mark)
                }

                is BreakStmt -> {
                    val loop = loops.lastOrNull() ?: refuse(s.span, "'break' outside a loop")
                    loop.breaks += b.emit(Op.JMP, 0, 0, 0)
                }

                is ContinueStmt -> {
                    val loop = loops.lastOrNull() ?: refuse(s.span, "'continue' outside a loop")
                    b.emit(Op.JMP, loop.top, 0, 0)
                }

                is ExprStmt -> {
                    val mark = b.mark()
                    val r = b.reg()
                    value(s.expr, r, wantResult = false)
                    b.release(mark)
                }

                is ExprBlockStmt -> block(s.block)

                is ReturnStmt -> {
                    val splice = inlineReturn
                    if (splice != null) {
                        // Spliced: land the result where the call site wants it, then jump past whatever
                        // is left of the body. See [InlineReturn].
                        val e = s.values.singleOrNull()
                        if (e != null && splice.wantResult) {
                            value(e, splice.dst)
                        } else if (e != null) {
                            // The value is not wanted, but the expression may still DO something.
                            val mark = b.mark()
                            value(e, b.reg(), wantResult = false)
                            b.release(mark)
                        }
                        splice.jumps += b.emit(Op.JMP, 0, 0, 0)
                    } else if (s.values.isEmpty()) {
                        // In an ENTRY there is no caller, so returning early is ending the fiber; in a
                        // body it is an ordinary return with nothing — or with the receiver, when that is
                        // what this function hands back.
                        when {
                            returns == 0 && isEntry -> b.emit(Op.HALT)
                            returnsSelf -> b.emit(Op.RET, 0, 1)
                            else -> b.emit(Op.RET, 0, 0)
                        }
                    } else {
                        // Results go in a contiguous window, which is what RET hands back.
                        //
                        // **The receiver rides along at the END** when this body writes it. A `return true`
                        // in a mutating extension has to hand back BOTH — the value it was asked for and
                        // the receiver it changed — and returning only the value left the call site storing
                        // that value where the receiver was. A `single` overwritten with a Bool then failed
                        // at the next field read, in a different function, with `GETFIELD on Bool`.
                        val mark = b.mark()
                        val extra = if (returnsSelf) 1 else 0
                        val window = b.regs(s.values.size + extra)
                        for ((i, v) in s.values.withIndex()) value(v, window + i)
                        if (returnsSelf) b.emit(Op.MOVE, window + s.values.size, 0)
                        b.emit(Op.RET, window, s.values.size + extra)
                        b.release(mark)
                    }
                }

                else -> refuse(s.span, s::class.simpleName ?: "this statement")
            }
        }

        // ---- expressions ------------------------------------------------------------------------------

        /**
         * Emit [e] into [dst], converting if [want] says it widens.
         *
         * **The widening is the point.** The resolver decided that an INT may flow into a FLOAT; without
         * `Op.TOF` on exactly this boundary the value stays an `Int` in a slot declared FLOAT, which is
         * GAPS 16 — a type that was a claim nothing enforced.
         */
        fun value(e: Expr, dst: Int, want: TypeRef? = null, wantResult: Boolean = true) {
            emit(e, dst, wantResult, want)
            if (want != null && want.builtin == PinType.FLOAT && resolution.type(e).builtin == PinType.INT) {
                b.emit(Op.TOF, dst, dst)
            }
        }

        /**
         * [want] is threaded only as far as the one expression that needs it — a record literal, which may
         * have been written in a document whose spelling for its own type means nothing here. Everything
         * else ignores it; the widening decision above stays in [value].
         */
        private fun emit(e: Expr, dst: Int, wantResult: Boolean, want: TypeRef? = null) {
            when (e) {
                is LiteralExpr -> b.emit(Op.CONST, dst, b.constant(e.value))

                is NameExpr -> when (val binding = resolution.bindingOf[e]) {
                    is LocalBinding -> b.emit(Op.MOVE, dst, registerOf(binding, e.span))
                    is DocumentBinding -> b.emit(Op.GETG, dst, slotOf(binding, e.span))
                    // A bare name the resolver read as a nullary call — `inventoryFull`, which reads as a
                    // fact rather than as a function.
                    is NativeBinding -> native(binding.fn, emptyList(), dst, wantResult, e.span)

                    // A function used as a VALUE — `Hooks { step: stepHerbs }`. It costs a constant: a
                    // reference to a named function has nothing to close over, so it is a program index
                    // and a name, which is what `FunctionValue` is.
                    is FunctionBinding -> b.emit(
                        Op.CONST,
                        dst,
                        b.constant(FunctionValue(chunkForBinding(binding), binding.name)),
                    )
                    else -> refuse(e.span, "'${e.name}', which the resolver did not bind")
                }

                is NotExpr -> {
                    value(e.operand, dst)
                    b.emit(Op.NOT, dst, dst)
                }

                is BinaryExpr -> binary(e, dst)

                is TernaryExpr -> {
                    val mark = b.mark()
                    val cond = b.reg()
                    value(e.condition, cond)
                    val toElse = b.emit(Op.JMPF, cond, 0, 0)
                    b.release(mark)
                    value(e.ifTrue, dst, resolution.type(e))
                    val toEnd = b.emit(Op.JMP, 0, 0, 0)
                    b.patch(toElse)
                    value(e.ifFalse, dst, resolution.type(e))
                    b.patch(toEnd)
                }

                is MemberExpr -> {
                    // One of a call's other results, named — ask for them all and keep the one wanted.
                    resolution.resultPick[e]?.let { index ->
                        val call = e.target as CallExpr
                        val mark = b.mark()
                        val window = b.regs(index + 1)
                        callInto(call, window, index + 1)
                        b.emit(Op.MOVE, dst, window + index)
                        b.release(mark)
                        return
                    }
                    // `Roster.Herbs` — a member IS its name, so it costs a constant and no lookup.
                    (e.target as? NameExpr)?.let { head ->
                        val enum = head.module
                            ?.let { resolution.imported.aliased[it]?.resolution?.exportedEnums?.get(head.name) }
                            ?: (if (head.module == null) enumNamed(head.name) else null)
                        if (enum != null) {
                            b.emit(Op.CONST, dst, b.constant(e.member))
                            return
                        }
                    }
                    // A COLUMN of a member: the member list and the column are baked in as constants and
                    // read across at run time, which is what `vscript.enumField` does for the graph. One
                    // implementation of one idea, rather than two that could disagree about a value.
                    enumFor(resolution.type(e.target))?.let { enum ->
                        // `.name` is the member itself — a member is carried as its name, so this is a
                        // move and not a lookup. Guarded by the enum having no column of that name, which
                        // is the same order the resolver decided in.
                        if (enum.columnType(e.member) == null && e.member == "name") {
                            value(e.target, dst)
                            return
                        }
                        // **The column is READ, not baked in.** It lives in a hidden document variable the
                        // prologue fills — see `EnumType.columnVars` — so a cell may be any expression an
                        // initialiser may be. Folded, only constants were expressible and everything else
                        // quietly became null.
                        //
                        // The MEMBER list stays a constant: it is the names as written, and there is
                        // nothing in it to evaluate.
                        val column = enum.columnVars[e.member]
                            ?: refuse(e.span, "column '${e.member}', which the resolver accepted")
                        val mark = b.mark()
                        val w = b.regs(3)
                        b.emit(Op.CONST, w, b.constant(ArrayList<Any?>(enum.members)))
                        b.emit(Op.GETG, w + 1, slotOf(column, e.span))
                        value(e.target, w + 2)
                        b.emit(Op.CALL, b.host(ENUM_FIELD_HOST), w, Op.packCounts(3, 1))
                        b.emit(Op.MOVE, dst, w)
                        b.release(mark)
                        return
                    }
                    // **A HOST record's field is a CALL, not a `GETFIELD`.** The value in the register is
                    // whatever the host handed over — an `EntityRef`, an `ItemRef` — not a `StructValue`,
                    // and converting one at the boundary would mean a copy per read and a second
                    // representation of the same thing to keep in step. It also lets a field be a live
                    // read: `distance` and `clickable` are questions about the world now, and a snapshot
                    // would be the wrong answer in a nicer spelling.
                    // **Only when nothing DECLARED it.** A host record is matched by name, and a
                    // document may declare `type Entity` of its own — which shadows the host's, as it does
                    // over a host enum and over a built-in. A declared type carries an owner and a host one
                    // never does, so that is the test; matching on the name alone compiled the document's
                    // own record into a call to the host's accessor.
                    resolution.type(e.target).takeIf { it.owner == null }
                        ?.let { dev.ziggle.vscript.model.HostRecords.of(it) }
                        // **A DATA record falls through to the GETFIELD below.** Its values ARE structs of
                        // its declared fields, so there is nothing to ask the host and an accessor call
                        // would be a lambda invocation to read something already in hand. That is not just
                        // a saving: `HostField.get` defaults to reading the struct, so taking this branch
                        // would work and quietly cost a call at every `t.x` in the corpus.
                        ?.takeIf { !it.isData }
                        ?.let { host ->
                        if (host.field(e.member) != null) {
                            val m = b.mark()
                            val w = b.regs(1)
                            value(e.target, w)
                            b.emit(Op.CALL, b.host(host.hostFor(e.member)), w, Op.packCounts(1, 1))
                            b.emit(Op.MOVE, dst, w)
                            b.release(m)
                            return
                        }
                    }
                    val mark = b.mark()
                    val target = b.reg()
                    value(e.target, target)
                    val record = recordOf(e)
                    val index = record.fields.indexOfFirst { it.name == e.member }
                    if (index < 0) refuse(e.span, "field '${e.member}', which the resolver accepted")
                    b.emit(Op.GETFIELD, dst, target, index)
                    b.release(mark)
                }

                is ListLitExpr -> {
                    // A FRESH list each time, which is why this is built rather than pooled as a
                    // constant: two evaluations of one literal must not hand back the same object, or
                    // appending to what you got would change what the next reader gets.
                    b.emit(Op.NEWLIST, dst)
                    val mark = b.mark()
                    val item = b.reg()
                    val element = resolution.type(e).of
                    for (i in e.items) {
                        value(i, item, element)
                        b.emit(Op.APPEND, dst, item)
                    }
                    b.release(mark)
                }

                is IndexExpr -> {
                    // **A non-list index is a CALL to an `op fn get`**, rewritten by the resolver so
                    // there is nothing to special-case here. See [Resolution.indexCallOf]: the operator
                    // form goes down the ordinary extension path, which finds the declaration and splices
                    // it, because `op` implies `inline`.
                    val asCall = resolution.indexCallOf[e]
                    if (asCall != null) {
                        call(asCall, dst, wantResult)
                    } else {
                        val mark = b.mark()
                        val list = b.reg()
                        val index = b.reg()
                        value(e.target, list)
                        value(e.index, index)
                        b.emit(Op.INDEX, dst, list, index)
                        b.release(mark)
                    }
                }

                is LambdaExpr -> {
                    val index = chunkForLambda(e)
                    val captures = resolution.capturesOf[e].orEmpty()
                    val mark = b.mark()
                    // The captured values are COPIED into a contiguous window now rather than read later,
                    // so reassigning the local afterwards cannot change what the closure sees. That is
                    // what makes a closure a value.
                    val window = b.regs(maxOf(captures.size, 1))
                    captures.forEachIndexed { i, c -> b.emit(Op.MOVE, window + i, registerOf(c, e.span)) }
                    val template = b.constant(FunctionValue(index, "@lambda"))
                    b.emit(Op.CLOSURE, dst, template, Op.packCounts(window, captures.size))
                    b.release(mark)
                }

                is AsExpr -> {
                    // The same host the graph calls, given the same schema — one decoder, so the two
                    // surfaces cannot disagree about what a saved file means.
                    val target = resolution.type(e).required()
                    val built = JsonSchema.of(
                        target,
                        types = { name ->
                            recordNamed(name)?.let { r ->
                                StructType(r.name, r.fields.map { f -> FunctionPin(f.name, f.type) })
                            }
                        },
                        enums = { name -> enumNamed(name)?.members },
                    )
                    val schema = built.schema
                        ?: refuse(e.span, "'as ${e.typeName}': ${built.problem}")
                    val renames = e.renames.associate { it.name to (it.value as? LiteralExpr)?.value?.toString().orEmpty() }
                    val mark = b.mark()
                    val window = b.regs(2)
                    value(e.value, window)
                    b.emit(Op.CONST, window + 1, b.constant(if (renames.isEmpty()) schema else schema.renamed(renames)))
                    b.emit(Op.CALL, b.host(JSON_DECODE_HOST), window, Op.packCounts(2, 1))
                    b.emit(Op.MOVE, dst, window)
                    b.release(mark)
                }

                /** `x is Point` — the same host the graph front end uses, so one idea has one answer. */
                is IsExpr -> {
                    val mark = b.mark()
                    val w = b.regs(3)
                    value(e.value, w)
                    // The SIMPLE name: `StructValue.type` is the display name and a host record answers
                    // with its class's, so an alias-qualified spelling would match neither.
                    b.emit(Op.CONST, w + 1, b.constant(e.typeName.substringAfterLast("::")))
                    b.emit(Op.CONST, w + 2, b.constant(e.negated))
                    b.emit(Op.CALL, b.host(IS_TYPE_HOST), w, Op.packCounts(3, 1))
                    b.emit(Op.MOVE, dst, w)
                    b.release(mark)
                }

                /**
                 * `a?.b.c` — evaluate the receiver once, and skip the whole access when it is absent.
                 *
                 * `dst` is seeded with null FIRST, so the absent path needs no second jump and no phi: the
                 * access simply overwrites it when it runs. The receiver's register is held for the access
                 * to read through [SafeItExpr], which is why it is allocated below the mark and released
                 * only after — releasing it before the access would let the access's own scratch land on
                 * top of the value it is reading.
                 */
                is SafeAccessExpr -> {
                    val mark = b.mark()
                    val recv = b.reg()
                    value(e.receiver, recv)
                    b.emit(Op.CONST, dst, b.constant(null))
                    val absent = b.emit(Op.JMPF, present(recv), 0, 0)
                    safeReceivers.addLast(recv)
                    try {
                        value(e.access, dst)
                    } finally {
                        safeReceivers.removeLast()
                    }
                    b.patch(absent)
                    b.release(mark)
                }

                is SafeItExpr -> b.emit(
                    Op.MOVE, dst,
                    safeReceivers.lastOrNull() ?: refuse(e.span, "a '?.' receiver outside a '?.'"),
                )

                is ElvisExpr -> {
                    value(e.value, dst)
                    // Present means keep it. Compared against null rather than jumped on, for the reason
                    // in [present]: the value may be any type, and truthiness is only defined for two.
                    val keep = b.emit(Op.JMPT, present(dst), 0, 0)
                    value(e.fallback, dst, resolution.type(e))
                    b.patch(keep)
                }

                is StructLitExpr -> structLit(e, dst, want)

                // Its own site, so a fault raised inside a nested call says which of them faulted —
                // the statement's stamp would name the line and leave `f(g(x))` ambiguous.
                is CallExpr -> {
                    val outer = b.currentNodeId
                    b.currentNodeId = siteOf(e, e.span)
                    try {
                        call(e, dst, wantResult)
                    } finally {
                        b.currentNodeId = outer
                    }
                }

                else -> refuse(e.span, e::class.simpleName ?: "this expression")
            }
        }

        private fun binary(e: BinaryExpr, dst: Int) {
            // `&&` and `||` must not evaluate their right side unless the left made it necessary, which is
            // the whole difference between them and the `and`/`or` calls — so they are jumps, not opcodes.
            if (e.op == BinaryOp.AND_THEN || e.op == BinaryOp.OR_ELSE) {
                value(e.left, dst)
                val skip = b.emit(if (e.op == BinaryOp.AND_THEN) Op.JMPF else Op.JMPT, dst, 0, 0)
                value(e.right, dst)
                b.patch(skip)
                return
            }
            val mark = b.mark()
            val lhs = b.reg()
            val rhs = b.reg()
            // Both sides widen to the result's type, so `1 + 2.5` adds two floats rather than asking the
            // VM to work out what an Int plus a Double is at run time.
            val result = resolution.type(e)
            val widen = if (result.builtin == PinType.FLOAT) result else null
            value(e.left, lhs, widen)
            value(e.right, rhs, widen)
            b.emit(arithOp(e.op, e.span), dst, lhs, rhs)
            b.release(mark)
        }

        /**
         * `Point { x: 1 }` — build a record.
         *
         * [want] is the last resort and it is not decoration: a literal written in ANOTHER document arrives
         * here when that document's field default is evaluated at the point the record is built, and its
         * bare spelling means nothing in the scope doing the building. `mid` declaring
         * `type Leg { from: Point = Point { } }` is compiled by whoever writes `Leg { }`, and a document
         * that imported only `Leg` has never named `Point`. The wanted type is the field's own, which
         * carries the owner, so it says which declaration that is.
         */
        private fun structLit(e: StructLitExpr, dst: Int, want: TypeRef? = null) {
            val simple = e.type.substringAfterLast("::")
            val record = recordNamed(e.type)
                ?: want?.takeIf { it.simpleName == simple }?.let { recordOwnedBy(it) }
                ?: refuse(e.span, "'${e.type}', which the resolver accepted")
            val mark = b.mark()
            val window = b.regs(maxOf(record.fields.size, 1))
            for ((i, field) in record.fields.withIndex()) {
                val supplied = e.fields.firstOrNull { it.name == field.name }
                when {
                    supplied != null -> value(supplied.value, window + i, field.type)
                    // A computed default is EVALUATED here, at the point the record is built — a call
                    // cannot be a constant, and flattening it to one is how `= emptyMap()` became null.
                    field.defaultExpr != null -> value(field.defaultExpr!!, window + i, field.type)
                    else -> b.emit(Op.CONST, window + i, b.constant(field.default))
                }
            }
            b.emit(Op.NEWSTRUCT, dst, b.constant(shapeOf(record)), window)
            b.release(mark)
        }

        private fun call(e: CallExpr, dst: Int, wantResult: Boolean) {
            // `Roster.values()` — every member, in declaration order, as a constant list.
            if (e.module == null && e.target.size == 2 && e.target.last() == "values") {
                enumNamed(e.target.first())?.let { enum ->
                    b.emit(Op.CONST, dst, b.constant(ArrayList<Any?>(enum.members)))
                    return
                }
            }
            // `Skill.of("farming")` — matched against the member list, which is a constant, and answering
            // the DECLARED spelling so what comes back is indistinguishable from `Skill.Farming`.
            if (e.module == null && e.target.size == 2 && e.target.last() == "of") {
                enumNamed(e.target.first())?.let { enum ->
                    val mark = b.mark()
                    val w = b.regs(2)
                    b.emit(Op.CONST, w, b.constant(ArrayList<Any?>(enum.members)))
                    e.args.firstOrNull()?.let { value(it.value, w + 1) }
                        ?: b.emit(Op.CONST, w + 1, b.constant(null))
                    b.emit(Op.CALL, b.host(ENUM_OF_HOST), w, Op.packCounts(2, 1))
                    b.emit(Op.MOVE, dst, w)
                    b.release(mark)
                    return
                }
            }
            val args = resolution.argumentsOf[e].orEmpty()
            when (val callee = resolution.calleeOf[e]) {
                is NativeCallee -> native(
                    callee.fn, args, dst, wantResult, e.span,
                    extra = resolution.templateArgs[e].orEmpty(),
                )
                // Through the owner as well: a TYPE-LEVEL function reached by a re-export — `Want.carry(…)`
                // via `core/loadout` — is declared beyond the caller's own document, and compiling its
                // body here would resolve its parameters against a resolution that never saw them.
                is FunctionCallee -> graphFn(
                    callee.signature,
                    compilerFor(owningResolution(callee.binding.decl)).chunkFor(callee.binding.decl),
                    args, dst, wantResult, e.span, callee.binding.decl,
                )
                // Compiled by the module that DECLARED it, into the table they share.
                // An extension is an ordinary call whose first argument happens to have been written on
                // the left of a dot — so once the callee is known there is nothing special left to do.
                is ExtensionCallee -> {
                    // The owner is found HERE rather than taken from the callee: an extension forwarded by
                    // a re-export is declared beyond the caller's direct imports, which is where the
                    // resolver's own search stopped.
                    val index = compilerFor(owningResolution(callee.binding.decl)).chunkFor(callee.binding.decl)
                    // **A mutating extension rebinds what it was called on.** `xs.add(3)` means
                    // `xs = xs.add(3)`, so the new receiver comes back as the result and is stored where
                    // the old one was. Nothing else in the language writes through a value.
                    if (callee.binding.writesReceiver && callee.receiver != null) {
                        // Marshalled here rather than through `graphFn`, which delivers only the FIRST
                        // result: a mutating extension hands back its own result and then the receiver, and
                        // both are wanted.
                        val mark = b.mark()
                        val sig = callee.signature
                        val n = sig.results.size
                        val window = b.regs(maxOf(sig.params.size, n, 1))
                        for ((i, param) in sig.params.withIndex()) {
                            val arg = args.getOrNull(i)
                            when {
                                arg != null -> value(arg, window + i, param.type)
                                param.hasDefault -> defaultInto(param, callee.binding.decl, window + i, e.span)
                                else -> refuse(e.span, "'${sig.name}' without '${param.name}'")
                            }
                        }
                        b.emit(Op.CALLG, index, window, Op.packCounts(sig.params.size, n))
                        storeBack(callee.receiver, window + n - 1, e.span)
                        if (n > 1 && dst != window) b.emit(Op.MOVE, dst, window)
                        b.release(mark)
                    } else {
                        graphFn(callee.signature, index, args, dst, wantResult, e.span,
                            callee.binding.decl)
                    }
                }
                is ImportedCallee -> graphFn(
                    callee.signature,
                    compilerFor(owningResolution(callee.binding.decl)).chunkFor(callee.binding.decl),
                    args, dst, wantResult, e.span, callee.binding.decl,
                )
                is ValueCallee -> valueCall(callee, args, dst, wantResult, e.span)
                // The function lives in a FIELD, so the target is an expression rather than a binding —
                // everything after reading it into a register is an ordinary dynamic call.
                is FieldCallee -> dynamicCall(callee.signature, args, dst, wantResult, e.span) { reg ->
                    value(callee.target, reg)
                }
                is IntrinsicCallee -> higherOrder(callee.intrinsic, args, dst, e.span)
                else -> refuse(e.span, "a call to '${e.name}'")
            }
        }

        /**
         * A call to a function this document declares — the same window convention as a host call, with
         * the callee named by its index in the shared program rather than by a host name.
         */
        private fun graphFn(
            sig: Signature,
            index: Int,
            args: List<Expr?>,
            dst: Int,
            wantResult: Boolean,
            span: Span,
            /** The declaration, when the caller knows it — only an `inline fn` needs it. */
            decl: dev.ziggle.vscript.lang.FnDecl? = null,
        ) {
            val mark = b.mark()
            val results = if (wantResult) sig.results.size else 0
            val window = b.regs(maxOf(sig.params.size, results, 1))
            // **The arguments land in registers before anything else, and that is what makes inlining
            // safe.** Substituting an argument EXPRESSION into the body would evaluate it once per
            // mention, so `inline fn twice(n: Int) = n + n` called as `twice(n: f())` would call `f()`
            // twice. Filling the window first means the spliced body reads a value.
            for ((i, param) in sig.params.withIndex()) {
                val arg = args.getOrNull(i)
                when {
                    arg != null -> value(arg, window + i, param.type)
                    param.hasDefault -> defaultInto(param, decl, window + i, span)
                    else -> refuse(span, "'${sig.name}' without '${param.name}', which the resolver accepted")
                }
            }
            if (decl != null && decl.isInline) {
                val owner = compilerFor(owningResolution(decl))
                owner.spliceInto(into = b, decl = decl, base = window, dst = dst, wantResult = wantResult)
                b.release(mark)
                return
            }
            b.emit(Op.CALLG, index, window, Op.packCounts(sig.params.size, results))
            if (results > 0 && dst != window) b.emit(Op.MOVE, dst, window)
            b.release(mark)
        }

        /**
         * One of the three list verbs that take a function, emitted as a loop with a `CALLV` in it.
         *
         * A host function is a Kotlin lambda over an array of arguments — no fiber, no frame, no way to
         * enter a chunk — so a verb that has to invoke a function per element cannot be one. The graph
         * compiler answers that the same way, and this is deliberately the same shape: two spellings of
         * one semantics is how the surfaces would start to differ about what `filtered` means.
         */
        private fun higherOrder(intrinsic: Intrinsic, args: List<Expr?>, dst: Int, span: Span) {
            when (intrinsic) {
                Intrinsic.DELAY -> {
                    val mark = b.mark()
                    val ms = b.reg()
                    args.getOrNull(0)?.let { value(it, ms) }
                        ?: b.emit(Op.CONST, ms, b.constant(intrinsic.signature.params[0].default))
                    b.emit(Op.SLEEP, ms)
                    b.release(mark)
                    return
                }

                // ---- the map primitives, one instruction each ------------------------------------
                //
                // The reason they are here rather than in the host: `withEntry` is a native that COPIES
                // the map, so an accumulator loop is O(n²) — measured at 20ms per insert on 3000 entries,
                // which is 60 seconds for the loop. `AppendPass` rewrote that shape to `Op.SETKEY` on the
                // graph path and this front end never ran it. Emitting the op directly is the fix, and
                // being an intrinsic is what makes it unconditional rather than something a pass has to
                // prove — see `docs/VSCRIPT_CONTAINERS_PLAN.md` §1.

                Intrinsic.NEW_LIST -> {
                    b.emit(Op.NEWLIST, dst)
                    return
                }

                Intrinsic.LIST_ADD -> {
                    val mark = b.mark()
                    val list = b.reg()
                    val item = b.reg()
                    value(args.getOrNull(0) ?: refuse(span, "'_listAdd' without a list"), list)
                    value(args.getOrNull(1) ?: refuse(span, "'_listAdd' without a value"), item)
                    b.emit(Op.APPEND, list, item)
                    b.release(mark)
                    return
                }

                Intrinsic.LIST_AT -> {
                    val mark = b.mark()
                    val list = b.reg()
                    val idx = b.reg()
                    value(args.getOrNull(0) ?: refuse(span, "'_listAt' without a list"), list)
                    value(args.getOrNull(1) ?: refuse(span, "'_listAt' without an index"), idx)
                    b.emit(Op.INDEX, dst, list, idx)
                    b.release(mark)
                    return
                }

                Intrinsic.LIST_SET -> {
                    val mark = b.mark()
                    val list = b.reg()
                    val idx = b.reg()
                    val v = b.reg()
                    value(args.getOrNull(0) ?: refuse(span, "'_listSet' without a list"), list)
                    value(args.getOrNull(1) ?: refuse(span, "'_listSet' without an index"), idx)
                    value(args.getOrNull(2) ?: refuse(span, "'_listSet' without a value"), v)
                    b.emit(Op.SETINDEX, list, idx, v)
                    b.release(mark)
                    return
                }

                Intrinsic.LIST_COUNT -> {
                    val mark = b.mark()
                    val list = b.reg()
                    args.getOrNull(0)?.let { value(it, list) } ?: b.emit(Op.NEWLIST, list)
                    b.emit(Op.LEN, dst, list)
                    b.release(mark)
                    return
                }

                Intrinsic.NEW_MAP -> {
                    b.emit(Op.NEWMAP, dst)
                    return
                }

                Intrinsic.MAP_PUT, Intrinsic.MAP_DROP -> {
                    val mark = b.mark()
                    val map = b.reg()
                    val key = b.reg()
                    value(args.getOrNull(0) ?: refuse(span, "'${intrinsic.fnName}' without a map"), map)
                    value(args.getOrNull(1) ?: refuse(span, "'${intrinsic.fnName}' without a key"), key)
                    if (intrinsic == Intrinsic.MAP_DROP) {
                        b.emit(Op.DELKEY, map, key)
                    } else {
                        val v = b.reg()
                        value(args.getOrNull(2) ?: refuse(span, "'_mapPut' without a value"), v)
                        b.emit(Op.SETKEY, map, key, v)
                    }
                    b.release(mark)
                    return
                }

                Intrinsic.MAP_AT, Intrinsic.MAP_HAS -> {
                    val mark = b.mark()
                    val map = b.reg()
                    val key = b.reg()
                    value(args.getOrNull(0) ?: refuse(span, "'${intrinsic.fnName}' without a map"), map)
                    value(args.getOrNull(1) ?: refuse(span, "'${intrinsic.fnName}' without a key"), key)
                    b.emit(if (intrinsic == Intrinsic.MAP_AT) Op.GETKEY else Op.HASKEY, dst, map, key)
                    b.release(mark)
                    return
                }

                Intrinsic.MAP_COUNT -> {
                    val mark = b.mark()
                    val map = b.reg()
                    args.getOrNull(0)?.let { value(it, map) } ?: b.emit(Op.NEWMAP, map)
                    b.emit(Op.MAPLEN, dst, map)
                    b.release(mark)
                    return
                }

                Intrinsic.AND, Intrinsic.OR -> {
                    // BOTH sides, always — `&&` is the one that short-circuits, and keeping them apart is
                    // why the language has two spellings rather than one pretending to be the other.
                    val mark = b.mark()
                    val a = b.reg()
                    val c = b.reg()
                    args.getOrNull(0)?.let { value(it, a) } ?: b.emit(Op.CONST, a, b.constant(false))
                    args.getOrNull(1)?.let { value(it, c) } ?: b.emit(Op.CONST, c, b.constant(false))
                    b.emit(if (intrinsic == Intrinsic.AND) Op.AND else Op.OR, dst, a, c)
                    b.release(mark)
                    return
                }

                else -> Unit
            }
            val list = args.getOrNull(0) ?: refuse(span, "'${intrinsic.fnName}' without a list")
            val fn = args.getOrNull(1) ?: refuse(span, "'${intrinsic.fnName}' without '${intrinsic.fnParam}'")

            val mark = b.mark()
            val src = b.reg()
            value(list, src)
            val fv = b.reg()
            value(fn, fv)
            val n = b.reg()
            b.emit(Op.LEN, n, src)
            val i = b.reg()
            b.emit(Op.CONST, i, b.constant(0))
            val one = b.reg()
            b.emit(Op.CONST, one, b.constant(1))
            val cond = b.reg()
            val item = b.reg()
            // **The call window LAST, and this is load-bearing**: a frame starts at its argument base and
            // runs UPWARD over as many registers as the callee needs, so anything this loop still wants
            // after the call has to sit below it. `dst` does too, and does — the caller allocated it
            // before the mark.
            val win = b.regs(1)

            val finding = intrinsic == Intrinsic.FIRST_WHERE
            if (finding) b.emit(Op.CONST, dst, b.constant(null)) else b.emit(Op.NEWLIST, dst)
            val top = b.here()
            b.emit(Op.LT, cond, i, n)
            val out = b.emit(Op.JMPF, cond, 0)
            b.emit(Op.INDEX, item, src, i)
            b.emit(Op.MOVE, win, item)
            b.emit(Op.CALLV, fv, win, Op.packCounts(1, 1))
            var found = -1
            when (intrinsic) {
                Intrinsic.MAPPED -> b.emit(Op.APPEND, dst, win)
                Intrinsic.FILTERED -> {
                    val skip = b.emit(Op.JMPF, win, 0)
                    b.emit(Op.APPEND, dst, item)
                    b.patch(skip)
                }
                Intrinsic.FIRST_WHERE -> {
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

        /**
         * A call through a function VALUE — `CALLV`, which differs from `CALLG` in one line: the callee is
         * read out of a register instead of named by the instruction. The captures the value carries are
         * added by the VM on entry, so nothing here passes them.
         */
        private fun valueCall(callee: ValueCallee, args: List<Expr?>, dst: Int, wantResult: Boolean, span: Span) =
            dynamicCall(callee.signature, args, dst, wantResult, span) { reg ->
                when (val binding = callee.binding) {
                    is DocumentBinding -> b.emit(Op.GETG, reg, slotOf(binding, span))
                    else -> b.emit(Op.MOVE, reg, registerOf(binding, span))
                }
            }

        /** `CALLV` over whatever [into] leaves in the target register — a value call, however it was found. */
        private fun dynamicCall(
            sig: Signature,
            args: List<Expr?>,
            dst: Int,
            wantResult: Boolean,
            span: Span,
            into: (Int) -> Unit,
        ) {
            val mark = b.mark()
            val target = b.reg()
            into(target)
            val results = if (wantResult) sig.results.size else 0
            val window = b.regs(maxOf(sig.params.size, results, 1))
            for ((i, param) in sig.params.withIndex()) {
                val arg = args.getOrNull(i) ?: refuse(span, "'${sig.name}' without '${param.name}'")
                value(arg, window + i, param.type)
            }
            b.emit(Op.CALLV, target, window, Op.packCounts(sig.params.size, results))
            if (results > 0 && dst != window) b.emit(Op.MOVE, dst, window)
            b.release(mark)
        }

        /**
         * Arguments in a contiguous window, results written back over it — the VM's calling convention,
         * and not a choice made here.
         *
         * The arguments arrive already in PARAMETER order from the resolver, with null where the call left
         * one out. Matching labels is a question about meaning and belongs where every other one does.
         */
        private fun native(
            fn: NativeFn,
            args: List<Expr?>,
            dst: Int,
            wantResult: Boolean,
            span: Span,
            extra: List<Expr> = emptyList(),
        ) {
            // **A reinterpretation emits a MOVE, not a call.** See [HostKind.CAST]: an id is the int it
            // was, and `n.toItem()` exists to be written rather than executed. This is what lets a DOMAIN
            // own conversions like that without paying a host call at each of the 199 sites that use one.
            if (fn.kind == HostKind.CAST) {
                args.getOrNull(0)?.let { value(it, dst) } ?: refuse(span, "'${fn.name}' without a value")
                return
            }
            // **A construction emits a NEWSTRUCT, not a call.** See [HostKind.CONSTRUCT]: `tile(x, y, p)`
            // builds the same record `Tile { … }` does, and used to be a language intrinsic purely because
            // nothing else could emit one. Arguments land on FIELDS positionally, and a missing one takes
            // that field's default rather than being an arity error.
            if (fn.kind == HostKind.CONSTRUCT) {
                constructInto(fn, args, dst, span)
                return
            }
            val mark = b.mark()
            val results = if (wantResult) fn.results.size else 0
            val window = b.regs(maxOf(fn.params.size + extra.size, results, 1))
            for ((i, param) in fn.params.withIndex()) {
                val arg = args.getOrNull(i)
                when {
                    arg != null -> value(arg, window + i, param.type)
                    // A HOST parameter's default comes from the catalogue and is a constant by
                    // construction — there is no expression to compile and no document to compile it in.
                    param.hasDefault -> b.emit(Op.CONST, window + i, b.constant(param.default))
                    else -> refuse(span, "'${fn.name}' without '${param.name}', which the resolver accepted")
                }
            }
            // A template's substituted values follow its declared parameters, in written order.
            extra.forEachIndexed { i, e -> value(e, window + fn.params.size + i) }
            // **A faked host is an ordinary call to the fake's chunk** — no host binding, no re-entry into
            // the VM from a Kotlin lambda, and no ACT: a fake never blocks, because there is nothing for it
            // to block on. Substituting here rather than in the runner is what lets the fake reach a call
            // site in another document, which is where they nearly all are.
            val faked = fakeChunkFor(fn.host)
            if (faked != null) {
                b.emit(Op.CALLG, faked, window, Op.packCounts(fn.params.size + extra.size, results))
                if (results > 0 && dst != window) b.emit(Op.MOVE, dst, window)
                b.release(mark)
                return
            }
            val op = if (fn.kind == HostKind.BLOCKING) Op.ACT else Op.CALL
            b.emit(op, b.host(fn.host), window, Op.packCounts(fn.params.size + extra.size, results))
            if (results > 0 && dst != window) b.emit(Op.MOVE, dst, window)
            b.release(mark)
        }

        // ---- lookups ----------------------------------------------------------------------------------

        private fun registerOf(binding: Binding, span: Span): Int =
            registers[binding] ?: refuse(span, "'${binding.name}', which has no register — read before it is declared?")

        private fun slotOf(binding: DocumentBinding, span: Span): Int =
            slots[binding] ?: refuse(span, "'${binding.name}', which has no global slot")

        /**
         * Put an omitted argument's default into [dst].
         *
         * A plain value is a constant; a COMPUTED one is a call into the chunk the declaring document
         * compiled for it ([chunkForDefault]) — which is why [decl] is threaded this far down. Without a
         * declaration there is nowhere to compile it, and that is refused rather than quietly folded to
         * null, because folding to null is the bug this whole path exists to end.
         */
        private fun defaultInto(param: Param, decl: dev.ziggle.vscript.lang.FnDecl?, dst: Int, span: Span) {
            val expr = param.defaultExpr
            if (expr == null) {
                b.emit(Op.CONST, dst, b.constant(param.default))
                return
            }
            if (decl == null) refuse(span, "a computed default for '${param.name}' on a call with no declaration to compile it in")
            // **Its own window, not [dst].** A call's window is the callee's frame base, and [dst] is a
            // register inside the window the OUTER call is still filling — handing it over as a frame base
            // put the callee on top of arguments already written, and the fiber returned into the wreckage
            // and quietly finished. Every other nested call allocates ([callInto] does), so this does too.
            val mark = b.mark()
            val w = b.regs(1)
            b.emit(Op.CALLG, compilerFor(owningResolution(decl)).chunkForDefault(expr, param.type), w, Op.packCounts(0, 1))
            b.emit(Op.MOVE, dst, w)
            b.release(mark)
        }

        /** Emit [call] with its results landing in [window] — shared by a binding and an ordinary call. */
        private fun callInto(call: CallExpr, window: Int, results: Int) {
            val args = resolution.argumentsOf[call].orEmpty()
            when (val callee = resolution.calleeOf[call]) {
                is NativeCallee -> {
                    // A reinterpretation lands its one argument straight in the window — see above.
                    if (callee.fn.kind == HostKind.CAST) {
                        args.getOrNull(0)?.let { value(it, window) }
                            ?: refuse(call.span, "'${callee.fn.name}' without a value")
                        return
                    }
                    // A construction lands its record straight in the window — see above.
                    if (callee.fn.kind == HostKind.CONSTRUCT) {
                        constructInto(callee.fn, args, window, call.span)
                        return
                    }
                    for ((i, param) in callee.fn.params.withIndex()) {
                        val arg = args.getOrNull(i)
                        when {
                            arg != null -> value(arg, window + i, param.type)
                            // Host defaults are catalogue constants — see the note in the template path.
                            param.hasDefault -> b.emit(Op.CONST, window + i, b.constant(param.default))
                            else -> refuse(call.span, "'${callee.fn.name}' without '${param.name}'")
                        }
                    }
                    val faked = fakeChunkFor(callee.fn.host)
                    if (faked != null) {
                        b.emit(Op.CALLG, faked, window, Op.packCounts(callee.fn.params.size, results))
                    } else {
                        val op = if (callee.fn.kind == HostKind.BLOCKING) Op.ACT else Op.CALL
                                    b.emit(op, b.host(callee.fn.host), window, Op.packCounts(callee.fn.params.size, results))
                    }
                }

                is ImportedCallee -> {
                    val sig = callee.signature
                    for ((i, param) in sig.params.withIndex()) {
                        val arg = args.getOrNull(i)
                        when {
                            arg != null -> value(arg, window + i, param.type)
                            param.hasDefault -> defaultInto(param, callee.binding.decl, window + i, call.span)
                            else -> refuse(call.span, "'${sig.name}' without '${param.name}'")
                        }
                    }
                    val index = compilerFor(owningResolution(callee.binding.decl)).chunkFor(callee.binding.decl)
                    b.emit(Op.CALLG, index, window, Op.packCounts(sig.params.size, results))
                }

                is FunctionCallee -> {
                    val sig = callee.signature
                    for ((i, param) in sig.params.withIndex()) {
                        val arg = args.getOrNull(i)
                        when {
                            arg != null -> value(arg, window + i, param.type)
                            param.hasDefault -> defaultInto(param, callee.binding.decl, window + i, call.span)
                            else -> refuse(call.span, "'${sig.name}' without '${param.name}'")
                        }
                    }
                    b.emit(
                        Op.CALLG,
                        compilerFor(owningResolution(callee.binding.decl)).chunkFor(callee.binding.decl),
                        window,
                        Op.packCounts(sig.params.size, results),
                    )
                }

                else -> refuse(call.span, "binding several values from this call")
            }
        }

        /** Point whatever the field assignment was written on at the copy — recursively for `a.b.c`. */
        private fun storeBack(target: Expr, from: Int, span: Span) {
            when (target) {
                is NameExpr -> when (val binding = resolution.bindingOf[target]) {
                    is DocumentBinding -> b.emit(Op.SETG, slotOf(binding, span), from)
                    is LocalBinding -> b.emit(Op.MOVE, registerOf(binding, span), from)
                    else -> refuse(span, "assigning to '${target.name}'")
                }

                // `a.b.c = v` rebuilds outward: the inner record is replaced in its own parent, and so on
                // up to the name at the root.
                is MemberExpr -> {
                    val outer = recordOfType(resolution.type(target.target), span)
                    val index = outer.fields.indexOfFirst { it.name == target.member }
                    if (index < 0) refuse(span, "field '${target.member}'")
                    val mark = b.mark()
                    val parent = b.reg()
                    value(target.target, parent)
                    b.emit(Op.SETFIELD, parent, index, from)
                    storeBack(target.target, parent, span)
                    b.release(mark)
                }

                else -> refuse(span, "assigning to this")
            }
        }

        /**
         * A register holding "[value] is there" — `value != null`, in a scratch register.
         *
         * Allocated above the watermark and released at once: the answer is consumed by the jump that
         * follows it and never wanted again.
         */
        /** The register each open `?.` left its receiver in — a stack, because they nest. */
        private val safeReceivers = ArrayDeque<Int>()

        private fun present(value: Int): Int {
            // The ANSWER is allocated below the watermark and kept; only the null it is compared against
            // is scratch. Returning a register that had just been released would work exactly as long as
            // nothing allocated between here and the jump — which is true today and is not a property
            // worth depending on. One register per SITE, not per iteration: this is compile time.
            val answer = b.reg()
            val mark = b.mark()
            val nil = b.reg()
            b.emit(Op.CONST, nil, b.constant(null))
            b.emit(Op.NE, answer, value, nil)
            b.release(mark)
            return answer
        }

        private fun recordOfType(t: TypeRef, span: Span): RecordType =
            recordOwnedBy(t) ?: recordNamed(t.name) ?: Prelude.record(t) ?: Prelude.hostRecord(t.name)
                ?: refuse(span, "a field of ${t.name}, which the resolver accepted")

        /**
         * The declaration a type names, asked of the module that DECLARED it.
         *
         * First, and by owner, because the name path underneath can only look a spelling up in the scope of
         * whoever is reading — and the reader may have no spelling at all. `geo` declares `Point`, `mid`
         * exports `type Leg { from: Point }`, and a document that imports only `Leg` never named `geo`:
         * `l.from.x` refused to compile, having resolved perfectly well, which is the "the resolver
         * accepted it yet" hole rather than an author's mistake.
         *
         * Exports first, then everything the module declares — a type can legitimately be reached through a
         * field of a record that WAS exported without being exported itself, and refusing to read a value
         * the module already handed out would be a restriction with nothing behind it.
         */
        /**
         * The enum a TYPE stands for — by owner where it has one, by spelling otherwise.
         *
         * The emit-side twin of `Resolver.enumFor`, and it has to agree with it exactly: the resolver
         * having typed `a.kind.label` and this not knowing what `Kind` is would compile the member access
         * as something else entirely rather than reporting anything.
         */
        private fun enumFor(t: TypeRef): EnumType? = enumOwnedBy(t) ?: enumNamed(t.name)

        private fun enumOwnedBy(t: TypeRef): EnumType? {
            val owner = t.owner ?: return null
            val simple = t.simpleName
            if (owner == resolution.ref) return resolution.enums[simple]
            val m = closure[owner] ?: return null
            return m.resolution.exportedEnums[simple] ?: m.resolution.enums[simple]
        }

        private fun recordOwnedBy(t: TypeRef): RecordType? {
            val owner = t.owner ?: return null
            val simple = t.simpleName
            if (owner == resolution.ref) return resolution.records[simple]
            val m = closure[owner] ?: return null
            return m.resolution.exportedRecords[simple] ?: m.resolution.records[simple]
        }

        /**
         * An expression as a compile-time VALUE — what an enum's table is made of.
         *
         * The table is a constant, so every cell has to be one; what makes that possible for a row like
         * `Herbs("herbs", 46, herbs::Impl)` is that the interesting cases are constant in the same way a
         * literal is. A function becomes a `FunctionValue` here because HERE is where its chunk index is
         * known — the same reason `GraphCompiler.materialise` folds the graph's tables in the compiler and
         * not in lowering.
         *
         * Anything that genuinely has to run gives null, and the caller refuses it by name.
         */
        /** [fold], reachable from another document's compiler — see [TextCompiler.foldGlobal]. */
        fun foldFor(e: Expr?, want: TypeRef?, depth: Int): Any? = fold(e, want, depth)

        private fun fold(e: Expr?, want: TypeRef?, depth: Int = 0): Any? {
            if (e == null || depth > 16) return null
            return when (e) {
                is LiteralExpr -> e.value

                // `herbs::Impl` / `ready` — a name that stands for something constant. A FUNCTION becomes
                // a value carrying its chunk; a `val` folds to whatever it was declared as.
                is NameExpr -> when (val binding = resolution.bindingOf[e]) {
                    is FunctionBinding -> FunctionValue(chunkForBinding(binding), binding.name)
                    // **Folded by the document that WROTE it.** An initialiser reached through an import
                    // is another document's tree, and every lookup this fold makes is identity-keyed on
                    // the resolution that produced it — so folding `seaweed::Impl` here looked up
                    // `contractPrepare` in the IMPORTER's `bindingOf`, found nothing, and quietly folded
                    // the field to null. The record came out shaped correctly with its function fields
                    // empty, and the failure surfaced pages away as "register 7 holds 'null', not a
                    // function" the first time the orchestrator called one.
                    is DocumentBinding -> ownerOfInit(binding)?.let { (owner, init) ->
                        compilerFor(owner).foldGlobal(init, binding.type, depth + 1)
                    }
                    else -> null
                }

                is MemberExpr -> {
                    // `Roster.Herbs` inside a table — a member is its name, which is already a constant.
                    val head = e.target as? NameExpr
                    if (head != null && enumOfName(head) != null) e.member else null
                }

                is StructLitExpr -> {
                    val record = recordNamed(e.type) ?: return null
                    val values = record.fields.map { field ->
                        val supplied = e.fields.firstOrNull { it.name == field.name }
                        if (supplied != null) fold(supplied.value, field.type, depth + 1)
                        else field.default ?: fold(field.defaultExpr, field.type, depth + 1)
                    }
                    StructValue(record.name, record.fields.map { it.name }, values.toTypedArray())
                }

                is ListLitExpr -> ArrayList(e.items.map { fold(it, want?.of, depth + 1) })

                // **A built-in CONSTRUCTOR is a constant, and folding could not see one.**
                // `tile(1620, 3988, 0)` in an enum column folded to null — every corner of the Wintertodt
                // arena came out with no roots pile, and the first read of one faulted three calls away as
                // "GETFIELD on null". It was invisible until `tile` became a real constructor: written as
                // a string it was a literal, and a literal always folded.
                //
                // Only the ones that genuinely ARE constants: they build a record out of their arguments,
                // or they rename an int. Anything that reads the world is not foldable and stays null.
                is CallExpr -> when (val callee = resolution.calleeOf[e]) {
                    is IntrinsicCallee -> foldIntrinsic(callee.intrinsic, e, depth)
                    // **A host CONSTRUCT folds for exactly the reason the note above gives.** That note
                    // describes `tile(…)` failing to fold while it was an intrinsic, and the constructor
                    // is the node pack's now — so the bug would have come straight back if this branch
                    // only knew about intrinsics.
                    is NativeCallee -> if (callee.fn.kind != HostKind.CONSTRUCT) null else {
                        val want = callee.fn.results.firstOrNull()?.type
                        val record = want?.let { Prelude.dataRecord(it.name) }
                        val args = resolution.argumentsOf[e] ?: e.args.map { a -> a.value }
                        // Field defaults resolved EXACTLY as the emitter resolves them — the node's
                        // parameter default first. Two spellings of one construction that disagree is
                        // worse than not folding, and here they would disagree about a missing plane.
                        record?.let { r ->
                            StructValue(
                                r.name,
                                r.fields.map { it.name },
                                r.fields.mapIndexed { i, field ->
                                    args.getOrNull(i)?.let { fold(it, field.type, depth + 1) }
                                        ?: defaultFor(callee.fn, i, field)
                                }.toTypedArray(),
                            )
                        }
                    }
                    else -> null
                }

                else -> null
            }
        }

        /**
         * A compile-time constructor, folded — see the [CallExpr] branch of [fold].
         *
         * Deliberately mirrors what the emitter does for each, field defaults included, because two
         * spellings of one construction that disagree is worse than not folding at all.
         */
        private fun foldIntrinsic(intrinsic: Intrinsic, e: CallExpr, depth: Int): Any? {
            val args = resolution.argumentsOf[e] ?: e.args.map { it.value }
            return when (intrinsic) {
                // **Nothing left to fold here, and the emptiness is the record.** This held the tile and
                // colour constructors and the `toColor` conversion — the last of the language's own
                // value-building intrinsics. All three are the drawing pack's nodes now: the constructors
                // fold through the `NativeCallee` branch above, which is the same folding reached by a
                // general rule instead of by a list of types the language happened to ship.
                else -> null
            }
        }

        /** Whether [e] is literally `null` — the one expression that folds to null and means to. */
        private fun isNullLiteral(e: Expr?): Boolean =
            e is LiteralExpr && e.kind == dev.ziggle.vscript.lang.LiteralKind.NULL

        /** [record] built from [args], each missing one taking its field default — as the emitter does. */
        private fun foldRecord(record: RecordType, args: List<Expr?>, fallback: Any?, depth: Int): Any? {
            val values = record.fields.mapIndexed { i, field ->
                val arg = args.getOrNull(i)
                if (arg != null) fold(arg, field.type, depth + 1) else field.default ?: fallback
            }
            return StructValue(record.name, record.fields.map { it.name }, values.toTypedArray())
        }

        /** Where a document variable's value was written, wherever it was declared. */
        private fun initialiserOf(binding: DocumentBinding): Expr? = ownerOfInit(binding)?.second

        /**
         * The document that declared [binding], and the expression it starts from.
         *
         * The RESOLUTION comes back with the expression because the two are inseparable: every side table
         * a fold reads is keyed by AST identity, so an expression is only meaningful against the
         * resolution that produced it. Handing back the expression alone is what let a cross-document
         * fold be attempted against the importer's tables.
         */
        private fun ownerOfInit(binding: DocumentBinding): Pair<Resolution, Expr>? {
            resolution.globalInits.firstOrNull { it.binding === binding }
                ?.let { return resolution to it.value }
            for (m in allModules()) {
                m.resolution.globalInits.firstOrNull { it.binding === binding }
                    ?.let { return m.resolution to it.value }
            }
            return null
        }

        /**
         * Every module reachable from here, however deep.
         *
         * **Transitively, and that is the point.** A front door re-exports a barrel, so a function reached
         * through the door is declared two modules away — and looking only at the DIRECT imports meant its
         * owner was never found, its body was compiled against the caller's resolution, and the call it
         * contained had no callee recorded there. `self.satisfied(…)` inside `core/loadout/wants` reached
         * from anything that imports `core/loadout` is exactly that.
         */
        private fun allModules(): List<Module> {
            val out = LinkedHashMap<String, Module>()
            fun walk(r: Resolution) {
                val imports = r.imported
                val reachable = imports.unqualified + imports.aliased.values +
                    imports.named.values.map { it.first } + r.reExports
                for (m in reachable) {
                    if (out.put(m.ref, m) == null) walk(m.resolution)
                }
            }
            walk(resolution)
            return out.values.toList()
        }

        /** The enum a name stands for, qualified or not — used while folding a table. */
        private fun enumOfName(head: NameExpr): EnumType? = head.module
            ?.let { resolution.imported.aliased[it]?.resolution?.exportedEnums?.get(head.name) }
            ?: (if (head.module == null) enumNamed(head.name) else null)

        /**
         * The chunk of a function that may belong to ANOTHER document.
         *
         * A reference travels — `activities.vs` names `herbs::Impl`, whose fields name functions declared
         * in `herbs.vs` — and a body is always compiled against the resolution that declared it.
         */
        private fun chunkForBinding(binding: FunctionBinding): Int =
            compilerFor(owningResolution(binding.decl)).chunkFor(binding.decl)

        /** The resolution that DECLARED [decl] — this one, or any module reachable from it. */
        private fun owningResolution(decl: dev.ziggle.vscript.lang.FnDecl): Resolution {
            if (resolution.document.decls.contains(decl)) return resolution
            return allModules().firstOrNull { it.resolution.document.decls.contains(decl) }?.resolution
                ?: resolution
        }

        /** An enum by name, from this document or anything it imports. */
        private fun enumNamed(name: String): EnumType? {
            resolution.enums[name]?.let { return it }
            val imports = resolution.imported
            // The LOCAL name first, translated to whatever it was exported as — `Resolver.importedEnum`'s
            // opening line, which this was missing. See [recordNamed].
            imports.named[name]?.let { (m, exportedAs) ->
                m.resolution.exportedEnums[exportedAs]?.let { return it }
            }
            for (m in imports.unqualified) m.resolution.exportedEnums[name]?.let { return it }
            for (m in imports.aliased.values) m.resolution.exportedEnums[name]?.let { return it }
            for ((m, _) in imports.named.values) m.resolution.exportedEnums[name]?.let { return it }
            // The HOST's, last — the emit-side twin of `Resolver.enumNamed`, and it has to agree with it
            // exactly. The resolver having accepted `Tab.Inventory` and this not knowing what `Tab` is
            // would compile the member access as something else entirely rather than reporting anything.
            return Prelude.hostEnum(name)
        }

        /**
         * What THIS document may see of [m]'s records — its exports, plus its internals when this is the
         * test for it. The resolver decides the same thing the same way (`Resolver.friendly`); the two
         * being separate is why a type the resolver allowed used to arrive here as "the resolver accepted
         * it yet", which reads as a compiler hole and was one.
         */
        private fun recordsOf(m: Module): Map<String, RecordType> =
            if (dev.ziggle.vscript.model.ModuleNames.maySeeInternals(resolution.ref, m.ref)) {
                m.resolution.records + m.resolution.exportedRecords
            } else {
                m.resolution.exportedRecords
            }

        /** A record by name, from this document or anything it imports — see `Resolver.recordFor`. */
        private fun recordNamed(name: String): RecordType? {
            resolution.records[name]?.let { return it }
            Prelude.type(name)?.let { p -> Prelude.record(p)?.let { return it } }
            // A data host record, matching what `Resolver.structLit` accepts. Asked here rather than at
            // the end because the loops below search IMPORTS, and a host type is not one.
            Prelude.dataRecord(name)?.let { return it }
            val imports = resolution.imported
            // **The LOCAL name first, translated to whatever it was exported AS.**
            //
            // `Resolver.importedRecord` opens with exactly this and the compiler did not, so the two
            // disagreed for any import that RENAMES — which every default import does by construction, and
            // an anonymous default most of all: `import Point from "x"` where the declaration is
            // `export default type { … }` binds `Point` to a record whose own name is `@default`, and the
            // loops below look for a record literally called `Point`. The resolver accepted the literal
            // and the compiler refused it, which is reported as a HOLE ("the resolver accepted it yet")
            // rather than as an author's mistake — correctly, because it was one.
            imports.named[name]?.let { (m, exportedAs) ->
                recordsOf(m)[exportedAs]?.let { return it }
            }
            for (m in imports.aliased.values) recordsOf(m)[name]?.let { return it }
            for (m in imports.unqualified) recordsOf(m)[name]?.let { return it }
            for ((m, _) in imports.named.values) recordsOf(m)[name]?.let { return it }
            return null
        }

        private fun recordOf(e: MemberExpr): RecordType = recordOfType(resolution.type(e.target), e.span)

        private fun arithOp(op: BinaryOp, span: Span): Int = when (op) {
            BinaryOp.ADD -> Op.ADD
            BinaryOp.SUB -> Op.SUB
            BinaryOp.MUL -> Op.MUL
            BinaryOp.DIV -> Op.DIV
            BinaryOp.MOD -> Op.MOD
            BinaryOp.EQ -> Op.EQ
            BinaryOp.NE -> Op.NE
            BinaryOp.LT -> Op.LT
            BinaryOp.LE -> Op.LE
            BinaryOp.GT -> Op.GT
            BinaryOp.GE -> Op.GE
            // Unreachable: both are emitted as jumps by [binary].
            BinaryOp.AND_THEN, BinaryOp.OR_ELSE -> refuse(span, "'$op' as a single instruction")
        }

        private fun shapeOf(record: RecordType): StructShape =
            StructShape(record.name, record.fields.map { it.name })

        /**
         * Emit [fn] as the record it constructs, landing in [dst].
         *
         * Deliberately the same shape as `structLit`, because the two spell one construction: a field with
         * no argument takes its own default, so `tile(3200, 3200)` is ground level rather than an arity
         * error, and the emitted `NEWSTRUCT` is indistinguishable from the one `Tile { … }` produces.
         *
         * The record is found from the declared RESULT type rather than from the function's name, which is
         * what keeps the spelling — `tile` — the domain's business and not this file's.
         */
        private fun constructInto(fn: NativeFn, args: List<Expr?>, dst: Int, span: Span) {
            val want = fn.results.firstOrNull()?.type
                ?: refuse(span, "'${fn.name}', declared to construct nothing")
            val record = Prelude.dataRecord(want.name)
                ?: refuse(span, "'${fn.name}', which constructs ${want.name} — not a data record")
            val mark = b.mark()
            val window = b.regs(maxOf(record.fields.size, 1))
            for ((i, field) in record.fields.withIndex()) {
                val arg = args.getOrNull(i)
                if (arg != null) value(arg, window + i, field.type)
                else b.emit(Op.CONST, window + i, b.constant(defaultFor(fn, i, field)))
            }
            b.emit(Op.NEWSTRUCT, dst, b.constant(shapeOf(record)), window)
            b.release(mark)
        }

        /**
         * What field [i] holds when the call left it out.
         *
         * **The NODE's parameter default first, then the field's.** A host record adapted for the text
         * model carries no defaults — its fields are a shape, not a declaration with initialisers — so
         * asking it alone answered null, and `tile(10, 20)` built a tile whose plane was null instead of
         * the ground. The node is where the default was actually written (`param("Plane", int, default =
         * 0)`), and it is the thing an author read when they left the argument off.
         */
        private fun defaultFor(fn: NativeFn, i: Int, field: Param): Any? {
            val param = fn.params.getOrNull(i)
            if (param != null && param.hasDefault) return param.default
            return field.default
        }

        private fun refuse(span: Span, what: String): Nothing =
            throw TextCompileError(span, "the text front end does not compile $what yet")
    }

    private companion object {
        /**
         * How many `inline` splices may nest before the compiler calls it recursion.
         *
         * Genuine nesting is shallow — an `op fn` over an `inline` wrapper over an intrinsic is three —
         * and anything deeper is a cycle the author did not intend. A depth counter catches a cycle
         * through several functions, which comparing names could not.
         */
        const val MAX_INLINE_DEPTH = 16
    }
}
