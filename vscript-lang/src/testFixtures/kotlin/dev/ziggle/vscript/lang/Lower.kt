package dev.ziggle.vscript.lang

import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.FunctionPin
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphImport
import dev.ziggle.vscript.model.GraphSource
import dev.ziggle.vscript.model.QualName
import dev.ziggle.vscript.model.GraphFunction
import dev.ziggle.vscript.model.GraphVariable
import dev.ziggle.vscript.model.Link
import dev.ziggle.vscript.model.Node
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeDescriptor
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.EnumType
import dev.ziggle.vscript.model.StructType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.canConnect
import dev.ziggle.vscript.model.effectivePinType
import dev.ziggle.vscript.model.receiverSpecificity
import dev.ziggle.vscript.model.resolveNode
import dev.ziggle.vscript.model.Export
import dev.ziggle.vscript.model.exportsOf
import dev.ziggle.vscript.model.qualifyThrough
import dev.ziggle.vscript.model.withFieldVariables
import dev.ziggle.vscript.model.withTypeVariables

/**
 * AST → [Graph].
 *
 * The text surface's whole claim rests here: a `.vs` file becomes a real graph, and the existing `Validator`
 * and `GraphCompiler` take it from there unchanged. There is one lowering, so text and canvas cannot drift.
 *
 * Three things shape the code more than anything else.
 *
 * **A data wire is pulled, an exec wire is pushed.** Lowering an expression yields a [Src] — either a pin to
 * wire from, or a literal to type into the consuming pin. Lowering a statement appends to an exec [Chain].
 * Expressions get the chain too, because an *impure* call is a step as well as a value: `let ok = openBank()`
 * has to put `openBank` on the chain, and it must land there before whatever consumes it.
 *
 * **The open end of a chain is a SET, not a pin.** There is no join node in this language: after an
 * `if`/`else`, both arms are dangling, and they converge by each wiring into the same next node. Modelling
 * the open end as one pin works right up until the first `else` and then quietly drops an arm.
 *
 * **Shape pins are written before their node is resolved.** `text("{n}", n: v)`'s pins come from its own
 * template, a list's from its contents, a record's from its declaration — so the literal has to be set,
 * the descriptor re-resolved, and only then can the remaining arguments be matched to pins that now exist.
 */
/*
 * ### Why this is still here, and why removing it is the wrong trade
 *
 * `Lower` turns vs source into a [Graph]. It **ships nowhere** — it moved to `testFixtures` when text
 * stopped being lowered for the editor, and `VScriptControl` says so in as many words: *"the other
 * direction — text back into a canvas — is gone, and with it `Lower` and `VsText`."* That is true of the
 * PRODUCT. It is not true of this source set, and the difference is worth writing down because the
 * question keeps coming back.
 *
 * What it is now is **the readable way to build a graph in a test**. Measured 2026-08-25:
 *
 * | | |
 * |---|---|
 * | test files using it | 69 |
 * | ...whose bodies assert on graph structure (nodes, links, functions, printing) | **64** |
 * | ...that could move to `TextFrontEnd` mechanically | 5 |
 * | production call sites | **0** |
 *
 * And the 64 are not testing a dead path. They are testing `GraphCompiler` and `Print`, both of which
 * ship: the canvas compiles through one and `graph text` / `graph export` read a document out through the
 * other. `GraphCompiler` has 65 test files and 57 of them are here.
 *
 * The alternative is `GraphBuilder`, which builds a graph node by node. It is the right tool when the
 * shape under test IS the graph, and it is a poor one for "does this language construct compile to the
 * right thing" — a twenty-line snippet becomes sixty lines of node and link construction, and the test
 * stops reading like the thing it is about.
 *
 * So: deleting this deletes 64 test files' worth of coverage from two shipping classes, in exchange for
 * removing a fixture that is already excluded from every artifact. **Keep it, and keep `LowerTest` with
 * it** — 64 files trust this to be correct, and an unguarded fixture that silently lowers the wrong graph
 * would make all of them agree about the wrong answer.
 */
class Lower(
    private val catalog: NodeCatalog,
    private val names: Names = Names(catalog),
    /**
     * Where `import` declarations are looked up while lowering.
     *
     * Needed for one thing only, and it is not optional: a Call node's PINS come from the callee's
     * signature, so an imported call cannot be shaped — and therefore cannot have its arguments wired —
     * without reading the document that declares it. Everything else about imports is document-local and
     * needs no source.
     *
     * With [GraphSource.NONE] an imported call still lowers; it simply gets no pins, which the validator
     * then reports against the import it could not resolve rather than against the call.
     */
    private val source: GraphSource = GraphSource.NONE,
    /**
     * What identifies the document this text IS — its reference, when the caller knows one.
     *
     * **A text file has no id field, and a blank id is not an identity.** `ImportClosure` keys a document
     * by `graph.id.ifBlank { graph.name }`, so a lowered document with no id falls back to its `graph`
     * line — and since that line became optional, every document without one is called `untitled` and they
     * are all the SAME document to the closure. What that looks like from the outside is
     * `import cycle: untitled -> untitled` on two files that import nothing of the sort.
     *
     * The reference is the right thing to put here for the reason the text side uses it too
     * (`TextCompiler.docRef`): it is unique by construction, it is what an import resolves BY, and it needs
     * nothing written inside the file. A caller that has no reference — a snippet, a test — leaves it
     * blank and gets the old fallback, which is still correct when there is only one document.
     */
    private val documentId: String = "",
    /**
     * Which functions this pass may treat as expressions, or null to assume every candidate is one.
     *
     * **The one thing lowering cannot read off the body it is lowering.** A Call node's pins depend on
     * whether the callee is an expression, and a call may be lowered long before — or instead of — the
     * body that decides it, including a call to itself. So the answer has to be assumed and then checked,
     * which is what [lower] does: it runs a pass with everything that COULD be an expression assumed to be
     * one, asks `isPureFunction` of the graph that came out, and runs again with whatever that demoted.
     *
     * Only ever shrinks, so it terminates: a function is demoted when its body turned out to need a step,
     * and re-lowering it with less purity cannot give any of it back. The alternative was to decide purity
     * syntactically before lowering — a second implementation of "what has to run", able to disagree with
     * the one in [expr], which is the failure mode this whole design exists to avoid.
     */
    private val assumePure: Set<String>? = null,
) {

    /** What a lowering produced: the graph, where each node came from, and anything that went wrong. */
    class Result(
        val graph: Graph,
        /** node id → the source that produced it. The debugger's line ↔ node bridge; see [Span]. */
        val spans: Map<Int, Span>,
        val errors: List<VsDiagnostic>,
        /** `var:Total` → where it was written, for issues that name a declaration rather than a node. */
        val declSpans: Map<String, Span> = emptyMap(),
        /** Worth saying, not worth refusing — see [Lower.warn]. */
        val warnings: List<VsDiagnostic> = emptyList(),
    ) {
        val ok: Boolean get() = errors.isEmpty()
    }

    // ---- where a value comes from ---------------------------------------------------------------------

    /**
     * A value, on its way to an input pin.
     *
     * [Inline] exists because a constant typed into a pin and a constant node wired to it are *different
     * graphs* with the same behaviour, and the difference is observable: a literal is keyed `"nodeId/pin"`
     * in the constant pool for live editing, so one node feeding three pins is one knob and three pin
     * literals are three. Inline is the default; `const` is how you ask for the other.
     */
    private sealed class Src
    private class FromPin(val node: Int, val pin: String) : Src()
    /**
     * A value typed straight into a pin.
     *
     * [inferred] is what the SOURCE said it was, for the kinds whose stored form cannot say — a tile
     * and a colour are both held as ordinary text and numbers. Carried here because `wire` is where a
     * literal meets its destination and is the last place both are in view; see
     * [BuiltinNodes.pinInferred].
     */
    private class Inline(val value: Any?, val inferred: TypeRef? = null) : Src()

    /** One dangling exec output, waiting for whatever runs next. */
    private class End(val node: Int, val pin: String)

    /**
     * The exec chain under construction.
     *
     * [open] is every dangling end — see the class note on why it is a list. [place] is the only way a node
     * joins the chain, so ordering is decided in one place.
     */
    private inner class Chain(var open: List<End>) {
        fun place(nodeId: Int, inPin: String = "Exec", outPin: String? = "Exec") {
            for (e in open) link(e.node, e.pin, nodeId, inPin)
            open = if (outPin == null) emptyList() else listOf(End(nodeId, outPin))
        }
    }

    // ---- state ----------------------------------------------------------------------------------------

    private val nodes = ArrayList<Node>()
    private val links = ArrayList<Link>()
    private val spans = HashMap<Int, Span>()

    /**
     * Where each DECLARATION was written — `var:Total`, `fn:double`, `type:Point`, `import:banking`.
     *
     * The node table answers for everything the validator can attribute to a node. Nothing attributes a
     * mistyped variable or an unresolved import to one, so those arrived with no span and a text editor
     * put them on the first character of the file — which reads as no diagnostic at all, with a red mark
     * somewhere innocent. See [Issue.declaration].
     */
    private val declSpans = HashMap<String, Span>()
    private val errors = ArrayList<VsDiagnostic>()
    private val warnings = ArrayList<VsDiagnostic>()

    private val variables = ArrayList<GraphVariable>()
    private val functions = ArrayList<GraphFunction>()
    private val structs = ArrayList<StructType>()
    private val enums = ArrayList<EnumType>()
    private val imports = ArrayList<GraphImport>()

    /** alias → the document it names, for the ones [source] could find. Empty is not an error here. */
    private val importedDocs = LinkedHashMap<String, Graph>()

    private var nextNode = 1
    private var nextLink = 1
    private var reserved = HashSet<Int>()

    /** The function body being lowered, or null at top level — becomes [Node.function]. */
    private var currentFn: String? = null

    /** The box node of the function being lowered, so `return` knows where to wire its results. */
    private var currentBox: Int? = null

    /**
     * The container a new node sits **in** — a comment box, or a function box inside one.
     *
     * Distinct from [currentFn], which is what a node is PART of. A node can have both and they say
     * different things: `function` is the semantic axis the compiler reads, `group` is only where the node
     * was dropped. This is what a `comment { }` block sets, and it is why the block needs no ids.
     */
    private var currentGroup: Int? = null

    /**
     * The functions this pass is treating as expressions — [assumePure] narrowed to what was declared,
     * plus every lambda, which is an expression by construction.
     *
     * Read by [isPure] to shape a Call. An assumption rather than a fact, because `isPureFunction` derives
     * the fact from body NODES and during lowering the body may not exist yet: a call can be lowered before
     * the function it names, and a recursive one before its own.
     */
    private val pureFunctions = HashSet<String>()

    /**
     * Every declared function whose body COULD be an expression — see [couldBeExpression].
     *
     * The domain the fixpoint works over. Anything outside it is a step whatever the graph says, so
     * re-lowering could never change its mind about one.
     */
    private val candidates = HashSet<String>()

    /**
     * Functions whose body assigns `self` — a MUTATING extension, so `xs.add(3)` writes back.
     *
     * **Derived from the body, not declared**, which is the decision this feature turns on. It is the same
     * call phase C made for `self`/type-level (the absence of a `self` parameter is what makes a function
     * type-level) and the same one purity already makes. The cost is real and worth naming: a call site
     * behaves differently depending on a body the reader may not have in front of them, which purity does
     * not do — purity only changes WHEN something is evaluated, this changes what a statement DOES.
     *
     * What makes it survivable is that the property is not stored ANYWHERE as a flag. A mutating function
     * has an implicit RESULT named `self`, so [isMutating] reads it straight off the signature — which
     * crosses an import, round-trips through JSON and shows up on the canvas for free, with nothing to
     * keep in step.
     */
    private val mutatingFunctions = HashSet<String>()

    /** The mutable Hold standing in for `self` in the body being lowered, or null outside one. */
    private var selfHold: Int? = null

    /** How many lambdas have been given a name — see [lambdaRef]. */
    private var lambdaCount = 0

    /**
     * `export default …` — the name of the declaration this document is for, carried onto the graph.
     *
     * Checked after the declarations exist: a default naming something that is not exported would be a
     * document offering an importer a name it also refuses, which is worth catching where it is written.
     */
    private var defaultExport: String? = null

    /** A field whose default has to RUN — see [BuiltinNodes.FIELD_DEFAULT]. */
    private class FieldDefault(val type: String, val field: Field, val isExported: Boolean)

    private val fieldDefaults = ArrayList<FieldDefault>()

    /**
     * The zero-argument function a computed field default becomes.
     *
     * A real [FnDecl], run through the ordinary declaration and body paths, so it inherits every rule a
     * hand-written function has — purity derivation above all. That is what makes `Point { x: 1 }` a STEP
     * exactly when the default it fills in is one, without anything here deciding so.
     */
    private fun defaultFn(fd: FieldDefault): FnDecl = FnDecl(
        BuiltinNodes.fieldDefaultName(fd.type, fd.field.name),
        null,
        emptyList(),
        listOf(Field(Parser.RESULT_PIN, fd.field.type, fd.field.span)),
        Block(
            listOf(ReturnStmt(listOf(fd.field.default!!), fd.field.span)),
            fd.field.span,
            braced = false,
        ),
        fd.field.span,
        Annotations.NONE,
        fd.isExported,
    )

    /**
     * Type and enum names this document declares itself, read off the AST before anything is lowered.
     *
     * Only so [qualifyImportedTypes] can leave them alone: a local declaration wins over an import of the
     * same name, and the warning that says so runs too late to help a type position.
     */
    private val localTypeNames = HashSet<String>()

    /** Which document a star-imported name came from, so a second star offering it is a real collision. */
    private val starOffers = HashMap<String, String>()

    /**
     * Names two `import "…"` lines both offer — bound by neither, and reported where one is USED.
     *
     * At the import there is nothing wrong yet: a document may well offer twenty names you want and one
     * you do not. It becomes a question only when the word is written.
     */
    private val starAmbiguous = LinkedHashMap<String, LinkedHashSet<String>>()

    /** Names written in an `export { … }` list — spelling, for the printer. See [Graph.exportList]. */
    private val exportList = ArrayList<String>()

    /** Is this name already part of the surface because its own declaration said so? */
    private fun alreadyExported(name: String): Boolean =
        functions.any { it.name == name && it.isExported } ||
            variables.any { it.name == name && it.isExported } ||
            structs.any { it.name == name && it.isExported } ||
            enums.any { it.name == name && it.isExported } ||
            nodes.any { it.literals[CONST_NAME] == name && it.literals[CONST_EXPORTED] == true }

    /** Local function names, collected before the enum pass so a row may name one. */
    private val enumPassFunctions = HashSet<String>()

    /**
     * Are we lowering a `fn f() = call(…)` body — the short form on a function with no result?
     *
     * Only so the "nothing is done with it" complaint can say the useful thing there. In a BLOCK that
     * message is right: a pure call on its own line computes and throws the value away. Written `= …` the
     * author plainly meant to hand it back, and the fix is a result type rather than a different statement.
     */
    private var inBareRun = false

    /** What a mutating extension hands back: the type of its own `self` parameter. */
    private fun receiverTypeOf(d: FnDecl): TypeRef =
        d.params.firstOrNull { it.name == GraphFunction.SELF }?.let { typeRef(it.type) }
            ?: TypeRef(PinType.WILDCARD)

    /**
     * Does this body assign `self` anywhere, at any depth?
     *
     * Every block-bearing statement, because a `self = …` inside an `if` inside a `for` is exactly as
     * mutating as one at the top — and a walker that missed a shape would silently produce a function that
     * writes to a local nobody reads.
     */
    private fun assignsSelf(b: Block): Boolean = b.stmts.any { s ->
        when (s) {
            is AssignStmt -> s.name == GraphFunction.SELF
            is IfStmt -> assignsSelf(s.then) || when (val e = s.elseBranch) {
                is IfStmt -> assignsSelf(Block(listOf(e)))
                is ExprBlockStmt -> assignsSelf(e.block)
                else -> false
            }
            is IfLetStmt -> assignsSelf(s.then) || when (val e = s.elseBranch) {
                is IfStmt -> assignsSelf(Block(listOf(e)))
                is ExprBlockStmt -> assignsSelf(e.block)
                else -> false
            }
            is WhileStmt -> assignsSelf(s.body)
            is ForStmt -> assignsSelf(s.body)
            is SequenceStmt -> s.arms.any { assignsSelf(it) }
            is WhenStmt -> s.arms.any { assignsSelf(it.body) } || s.elseArm?.let { assignsSelf(it) } == true
            is ExprBlockStmt -> assignsSelf(s.block)
            else -> false
        }
    }

    /** A pending "does this document declare it too" check — see [reportLocalShadows]. */
    private class ShadowCheck(val local: String, val real: String, val ref: String, val at: Span)

    private val shadowChecks = ArrayList<ShadowCheck>()

    /**
     * The one part of resolving an unqualified import that has to wait for this document's declarations.
     *
     * **The TABLE cannot wait for them, which is why this is split.** An enum's rows are folded during the
     * declaration pass, and a row may name an imported enum's member — `Target.SapphireGlacialis` off an
     * `import { Target }`. With the whole of the resolution deferred, that name resolved to nothing and
     * the row was reported as "an enum's values have to be written out, not worked out", which is a true
     * sentence about the wrong thing.
     *
     * The local wins, as an innermost scope does everywhere — but not silently, or the import would look
     * like it had done nothing.
     */
    private fun reportLocalShadows() {
        // A star gives the name up SILENTLY, because "it never takes a name that is spoken for" is the
        // form's stated rule rather than a surprise — there is nothing to warn about. It has to happen
        // here for the same reason the rest does: this document's own declarations are not read yet when
        // the table is built.
        for (name in starOffers.keys) if (declaresLocally(name)) unqualified.remove(name)
        for (c in shadowChecks) {
            if (!declaresLocally(c.local)) continue
            unqualified.remove(c.local)
            warn(
                c.at,
                "'${c.local}' is declared in this document too, so the one here wins — " +
                    "rename the import to reach \"${c.ref}\"'s: " +
                    "'import {${c.real} as <name>} from \"${c.ref}\"'",
            )
        }
    }

    /** Names in scope: `let` bindings, `const`s, loop variables, parameters. Innermost last. */
    private val scopes = ArrayList<HashMap<String, Src>>()

    /**
     * `var N: T = <expr>` where the expression has to RUN.
     *
     * A literal default is document data and is stored on the [GraphVariable], which is what the canvas
     * has always done. Anything else has nowhere to live there — a declaration is not a place a node can
     * hang off — so it becomes an initialiser at the head of `on start`, in declaration order. The
     * variable holds its type's zero until that runs, which is the honest consequence and the reason
     * these are collected rather than pretended to be data.
     */
    private class PendingInit(val name: String, val value: Expr, val span: Span)

    /**
     * A lambda that was FOLDED into a declaration, and whose body has not been lowered yet.
     *
     * **The two halves happen in different phases and they have to.** An enum's table, a column default
     * and a parameter default are document data — folded while the declarations are being read, before a
     * single function signature is registered. A body lowered there would resolve its calls against a
     * table that is still being built. So the fold takes the NAME, which is all a column stores anyway
     * (a handler column has always held a function's name), and the body is lowered with every other
     * body once the declarations are complete. See [foldLambda].
     */
    private class PendingLambda(
        val name: String,
        val lam: LambdaExpr,
        val params: List<FunctionPin>,
        val hasResult: Boolean,
    )

    private val pendingLambdas = ArrayList<PendingLambda>()

    /**
     * What a destination says a value must be — the type, and enough naming to explain a mismatch.
     *
     * **Only a lambda reads this, and only because a lambda is the one value that cannot say what it is.**
     * Everything else in the language carries its own type; `{ it > 0 }` carries an arity it inferred and
     * parameter types it does not have, so it has to be told. A call's arguments could always tell it —
     * the pin is right there — and nothing else could, which is why a lambda used to be legal in exactly
     * one place and refused in three that knew the answer perfectly well: a record field, a declared
     * variable, and a local. See [lambdaValue].
     */
    private class Expected(val type: TypeRef, val what: String, val owner: String)

    private val pendingInits = ArrayList<PendingInit>()

    /** Set once the prologue has been attached, so a synthetic entry is not also created. */
    private var initsEmitted = false

    /**
     * Which entry kind carries the initialiser prologue — the FIRST one that runs.
     *
     * `on start` until `on wake` exists, and then the wake, because the wake runs first and a variable
     * whose default is computed must hold its value before the earliest line that can read it. Getting
     * this wrong is silent both ways round: leave the prologue on `on start` and it re-seeds, at the head
     * of the loop, on top of whatever the wake just restored from disk — `State = saved` undone one phase
     * later by the very declaration that named it. Emit it in BOTH and the same thing happens, because
     * these Sets carry no run-once guard (the imported-document prologues do; see
     * `Interpreter.ranPrologues`).
     *
     * Decided by a pre-scan in [bodies] rather than as the declarations go past, because they go past in
     * SOURCE order: a document that writes `on start` above `on wake` would already have emitted.
     */
    private var initsGoOn = EntryKind.START

    // ---- entry point ----------------------------------------------------------------------------------

    /**
     * Lower [program], settling which functions are expressions.
     *
     * One pass would be enough if purity were declared. It is derived, and derived from a body that may not
     * have been lowered yet when the call that needs the answer is — so this runs [pass] optimistically,
     * asks the graph that came out which of its assumptions survived, and runs again with the survivors.
     * See [assumePure] for why the alternative (deciding it syntactically up front) is worse.
     *
     * Converges: the assumed set only ever shrinks, so the loop is bounded by the number of candidates.
     * The cap is a backstop against a bug in that argument, not an expected outcome — hitting it returns the
     * last pass, which is self-consistent for everything that did not change on the final step.
     */
    fun lower(program: Program): Result {
        var assumed = assumePure
        var last: Result
        var runs = 0
        while (true) {
            val pass = if (runs == 0 && assumed === assumePure) this else Lower(catalog, names, source, documentId, assumed)
            last = pass.pass(program)
            val settled = pass.settled(last.graph)
            if (settled == pass.candidates.filterTo(HashSet()) { it in pass.pureFunctions }) return last
            assumed = settled
            if (++runs > MAX_PURITY_PASSES) return last
        }
    }

    /**
     * Which of this pass's assumptions the graph agrees with.
     *
     * `isPureFunction` is the authority everywhere else — the validator, the printer and `resolveNode` all
     * ask it — so it is the authority here too rather than a lookalike that could answer differently.
     */
    private fun settled(graph: Graph): Set<String> {
        val across = { callee: String -> purityAcrossImport(callee) }
        return candidates.filterTo(HashSet()) {
            it in pureFunctions &&
                dev.ziggle.vscript.model.isPureFunction(
                    it, catalog, graph.nodes, graph::function, graph.links, emptySet(), across,
                )
        }
    }

    /**
     * Could this function be an expression at all?
     *
     * The shape question only — "is the body one `return` of the right number of values" — with no opinion
     * about what the values do. What they do is [tailReturn]'s answer, and it needs them lowered.
     */
    private fun couldBeExpression(d: FnDecl): Boolean {
        // ONE result. An expression is read where it is used, so a caller reading two of its results reads
        // it twice — `val (a, b) = pair()` became `pair().a` and `pair().b`, two evaluations of a function
        // written to produce one pair. There is also nowhere to put the second in `= …`, which is why the
        // printer only ever emitted the first.
        if (d.results.size != 1) return false
        if (d.params.any { it.name == GraphFunction.SELF } && assignsSelf(d.body)) return false
        val only = d.body.stmts.singleOrNull() as? ReturnStmt ?: return false
        return only.values.size == 1
    }

    private fun pass(program: Program): Result {
        // The document's own scope. Without it `bind` has nowhere to write and a top-level `const` is
        // silently dropped — the binding vanishes and every reader reports "nothing here is called n".
        defaultExport = program.defaultExport
        for (d in program.decls) {
            when (d) {
                is TypeDecl -> localTypeNames += d.name
                is SingleDecl -> localTypeNames += d.name
                is EnumDecl -> localTypeNames += d.name
                else -> {}
            }
        }
        pushScope()
        reserved = collectPinnedIds(program)
        nextNode = ((reserved.maxOrNull() ?: 0) + 1).coerceAtLeast(1)

        // Imports before everything, because a declaration below may be TYPED through one and a body may
        // call through one. Document-local: whether the alias resolves to a real document is the
        // validator's question, since only it is given a source it can trust.
        collectImports(program.decls)
        // The unqualified table BEFORE the declarations, so the enum pass can fold a row that names an
        // imported member. Its local-shadow half runs after them — see [reportLocalShadows].
        resolveUnqualifiedImports(program.decls)
        // Then the other declarations, so a body can call a function declared below it and name a type
        // declared anywhere. Bodies resolve against the whole document, exactly as the canvas does.
        declarations(program.decls)
        // The functions a computed field default became, registered the way a hand-written one is — a
        // signature and a purity candidacy — so nothing below here can tell them apart. Their bodies are
        // lowered with the rest, below.
        val synthesised = fieldDefaults.map { defaultFn(it) }
        for (d in synthesised) {
            declSpans["fn:${d.name}"] = d.span
            functions += GraphFunction(
                d.name,
                emptyList(),
                d.results.map { FunctionPin(it.name, typeRef(it.type)) },
                d.isExported,
            )
            if (couldBeExpression(d)) {
                candidates += d.name
                if (assumePure?.contains(d.name) != false) pureFunctions += d.name
            }
        }
        // `export { a, b }` — the same thing as writing `export` on each, said in one place. Applied
        // after the declarations exist and before anything asks what this document offers.
        for (d in program.decls) {
            if (d !is ExportListDecl) continue
            for (name in d.names) {
                // Recorded for the PRINTER only, and only when the declaration did not already say it —
                // a name written both ways is one fact, and prints once. See Graph.exportList.
                if (!alreadyExported(name)) exportList += name
                var marked = false
                functions.indexOfFirst { it.name == name }.takeIf { it >= 0 }?.let { i ->
                    val f = functions[i]
                    functions[i] = GraphFunction(f.name, f.params, f.results, true, f.receiver)
                    marked = true
                }
                variables.indexOfFirst { it.name == name }.takeIf { it >= 0 }?.let { i ->
                    val v = variables[i]
                    variables[i] = GraphVariable(v.name, v.type, v.default, true, v.isImmutable)
                    marked = true
                }
                structs.indexOfFirst { it.name == name }.takeIf { it >= 0 }?.let { i ->
                    val t = structs[i]
                    structs[i] = StructType(t.name, t.fields, true, t.params, t.isSingle)
                    marked = true
                }
                enums.indexOfFirst { it.name == name }.takeIf { it >= 0 }?.let { i ->
                    val en = enums[i]
                    enums[i] = EnumType(en.name, en.members, true, en.fields, en.values)
                    marked = true
                }
                // A `const` is a literal node; marking it is the same second literal `export const` writes.
                constDecls[name]?.let { _ ->
                    nodes.firstOrNull { it.literals[CONST_NAME] == name }?.literals?.put(CONST_EXPORTED, true)
                    marked = true
                }
                if (!marked) err(d.span, "'$name' is not declared here, so it cannot be exported")
            }
        }
        // `export default { … }` — synthesised once every declaration it may name has been registered.
        program.decls.filterIsInstance<DefaultBundleDecl>().firstOrNull()?.let { bundle(it) }
        // A default has to name something this document actually offers — checked here, where the
        // declarations exist and the `export default` is still a line somebody can be pointed at.
        defaultExport?.let { named ->
            val at = declSpans["fn:$named"] ?: declSpans["var:$named"] ?: declSpans["type:$named"]
                ?: declSpans["enum:$named"] ?: Span.NONE
            val exported = functions.any { it.name == named && it.isExported } ||
                variables.any { it.name == named && it.isExported } ||
                structs.any { it.name == named && it.isExported } ||
                enums.any { it.name == named && it.isExported }
            if (!exported) {
                err(at, "'$named' is this document's default, so it has to be exported too")
                defaultExport = null
            }
        }
        reportLocalShadows()
        bodies(program.decls + synthesised)
        // The bodies of lambdas that were FOLDED into declarations — after every other body, because by
        // here every signature this one might call is registered. An index loop rather than a for-each:
        // nothing appends while this runs today, and a crash if that ever changes beats a silent skip.
        var li = 0
        while (li < pendingLambdas.size) {
            val pl = pendingLambdas[li++]
            if (lambdaBody(pl.name, pl.lam, pl.params, pl.hasResult)) pureFunctions += pl.name
        }

        // A document may initialise variables and never say `on start`. The prologue still has to run,
        // so it gets an entry of its own rather than silently not happening.
        if (pendingInits.isNotEmpty() && !initsEmitted) {
            // Run two ways, and neither is a special case. Directly, this entry spawns a fiber like any
            // other; imported, nothing spawns it and `GraphCompiler.prologueChunkFor` finds the prologue
            // by starting here — the entry is the anchor either way.
            val id = node(BuiltinNodes.ENTRY, pendingInits.first().span)
            pushScope()
            emitInits(Chain(listOf(End(id, "Exec"))))
            popScope()
        }

        val graph = Graph(
            id = documentId,
            name = program.name ?: "untitled",
            nodes = nodes,
            links = links,
            variables = variables,
            functions = functions,
            types = structs,
            enums = enums,
            // The resolved id is written back where the document was found, so a later rename of the
            // target does not strand the import — the ref stays as the author typed it.
            imports = imports.map { imp -> imp.withId(importedDocs[imp.alias]?.id) },
            defaultExport = defaultExport,
            exportList = exportList,
        )
        return Result(placed(graph), spans, errors, declSpans, warnings)
    }

    /**
     * `import` declarations, and the documents they name.
     *
     * A duplicate alias is refused HERE rather than left to the closure, because at this point there is a
     * span to point at — the second `import` line — and by the time the closure sees it there is only a
     * document with two entries and nothing to underline.
     *
     */
    private fun collectImports(decls: List<Decl>) {
        for (d in decls) {
            when (d) {
                // `export { a } from "y"` — an import edge that binds nothing here. It still has to LOAD
                // the document, so the closure, the globals layout and the format all see it as an edge
                // like any other; what makes it a re-export is only where its names go. Always its own
                // anonymous alias: two re-export lines naming the same document are two independent
                // statements about this document's surface, and merging them would merge their item lists.
                is ReExportDecl -> {
                    val alias = GraphImport.anonymousAlias(imports.size + 1)
                    val imp = GraphImport(
                        alias, d.ref, null,
                        reExported = d.items, reExportAll = d.all,
                    )
                    imports += imp
                    declSpans["import:$alias"] = d.span
                    val loaded = source.load(imp)
                    if (loaded != null) {
                        importedDocs[alias] = loaded
                    } else if (source !== GraphSource.NONE) {
                        err(d.span, "nothing answers to \"${d.ref}\" — no document is named that")
                    }
                }
                is ImportDecl -> {
                    if (d.alias != null && imports.any { it.alias == d.alias }) {
                        err(d.span, "'${d.alias}' is already imported")
                        continue
                    }
                    // An unqualified import still gets an alias — it is the key everything downstream uses
                    // to say which document a name came from, and a synthesised one keeps the Call nodes,
                    // the compiler and the document format exactly as they were. Never printed: see
                    // [GraphImport.anonymousAlias].
                    //
                    // **One alias per DOCUMENT, not per import line.** A name's identity is its qualified
                    // form, so two anonymous aliases over the same document make its `Point` two unrelated
                    // types — and `import * from "geo"` beside `import {chebyshev as distance} from "geo"`
                    // is a perfectly ordinary thing to write. Sharing the alias makes them one type again
                    // and costs nothing: both lines still print as themselves, because the printer works
                    // from each import's own spelling rather than from the alias.
                    val alias = d.alias
                        ?: imports.firstOrNull { it.isUnqualified && it.ref == d.ref }?.alias
                        ?: GraphImport.anonymousAlias(imports.size + 1)
                    val imp = GraphImport(alias, d.ref, null, d.named, d.default, star = d.star)
                    imports += imp
                    declSpans["import:$alias"] = d.span
                    val loaded = source.load(imp)
                    if (loaded != null) {
                        importedDocs[alias] = loaded
                    } else if (source !== GraphSource.NONE) {
                        // **Said HERE, not left to the validator.** An import that resolves to nothing is
                        // noticed first at this line and reported everywhere else: every name reached
                        // through the alias fails on its own, so the file fills with complaints about calls
                        // that are written perfectly and one silent line that is not. Worse, lowering
                        // errors stop the pipeline before the validator — which does check imports — ever
                        // runs, so the one accurate message was the one that never appeared.
                        //
                        // Only when a source was supplied. `GraphSource.NONE` is not a failed lookup, it is
                        // "this caller does not do imports" — every test and every canvas graph predating
                        // them passes it, and refusing those would be reporting the caller, not the file.
                        err(d.span, "nothing answers to \"${d.ref}\" — no document is named that")
                    }
                }
                else -> {}
            }
        }
    }

    /**
     * What each unqualified imported name means here — and every collision, reported at the import.
     *
     * **Eagerly, not at the use.** Two wildcards both offering `foo` is an error whether or not anything
     * calls `foo`, which is the same bargain the mandatory alias always made: this language is built so a
     * name cannot quietly mean two things, and the price is that a library adding a name can break an
     * importer that never used it. The compensation is that the fix is at the line you are being shown.
     *
     * Three outcomes, and the difference between them is what the author can SEE:
     *
     * - **Another import offers it too** — an error. Neither is more yours than the other.
     * - **A node offers it too** — an error. The catalogue is a vocabulary nobody wrote down in this file,
     *   so a silent capture is the one that could not be found by reading.
     * - **This document declares it** — a warning, and the local wins. That is what every language does
     *   with its innermost scope, and it is written right here where it can be read.
     */
    private fun resolveUnqualifiedImports(decls: List<Decl>) {
        val spanOf = HashMap<String, Span>()
        for (d in decls) if (d is ImportDecl) spanOf[d.ref] = d.span

        // Explicit names FIRST, every line of them, then the stars — so `import { a } from "x"` wins over
        // `import "y"` whatever order they were written in. A rule that depended on line order would make
        // moving an import change what a name means.
        for (imp in imports.filter { !it.star } + imports.filter { it.star }) {
            if (!imp.isUnqualified) continue
            val doc = importedDocs[imp.alias] ?: continue
            val at = spanOf[imp.ref] ?: Span.NONE

            // A named list offers exactly what it names, under its own spelling; a default offers the
            // one declaration the other document marked. Both land in the same table, so the default is
            // not a special case anywhere below here — it is a renamed named import whose real name had
            // to be looked up rather than written.
            val offered = LinkedHashMap<String, String>()
            // The default, which IS a renamed named import — the only difference is that the name on the
            // other side was looked up rather than written. Placed before the named list so an explicit
            // `{ … }` entry wins for a name they both offer.
            imp.default?.let { local ->
                // Checked against what the other document OFFERS, not merely against what it names: a
                // graph built on the canvas or read from JSON can carry a default pointing at something
                // unexported, and resolving it would be the one hole in an opt-in surface.
                val real = doc.defaultExport?.takeIf { it in exportedNames(doc) }
                if (real == null) {
                    err(
                        at,
                        "\"${imp.ref}\" declares no 'export default' — name what you want with " +
                            "'{ … }', or add 'export default' to the declaration it is for",
                    )
                } else {
                    offered[local] = real
                }
            }
            // `import "core/list"` — everything, under its own name. Nothing is written, so nothing is
            // checked here: which of these actually arrive is decided below, by what is already spoken for.
            if (imp.star) for (name in exportedNames(doc)) offered[name] = name
            for (item in imp.named) {
                if (item.name !in exportedNames(doc)) {
                    err(at, "\"${imp.ref}\" offers nothing called '${item.name}'")
                    continue
                }
                // A renamed one no longer arrives under its own name, even from the same `*`.
                if (item.isAliased) offered.remove(item.name)
                offered[item.local] = item.name
            }

            for ((local, real) in offered) {
                // **An extension is not a bare name.** Naming one in the list is how a document asks for
                // it — see `extensionCall` — but it is only ever reached through a receiver, exactly as a
                // locally declared extension is (`call` refuses to make one callable by its bare name). So
                // it must NOT join the unqualified table: `import { isEmpty } from "core/list"` would
                // otherwise collide with the `isEmpty` NODE and be refused, which would make asking for the
                // extension impossible for every name the catalogue also uses.
                val offeredFn = exportsIn(doc)[real]?.let { it.owner.function(it.name) }
                if (offeredFn?.isExtension == true) continue
                val clash = unqualified[local]
                // **A star never takes a name that is already spoken for.** A node, an explicit import or
                // this document's own declaration keeps its meaning and the name simply does not arrive —
                // so one unlucky word cannot break a line somebody wanted for the other twenty. Two stars
                // offering one name bind NEITHER; that is a real ambiguity and it is reported where the
                // name is used, which is the only place there is anything to be ambiguous about.
                if (imp.star) {
                    val takenByStar = starOffers[local]
                    when {
                        takenByStar != null && takenByStar != imp.ref -> {
                            starAmbiguous.getOrPut(local) { linkedSetOf(takenByStar) } += imp.ref
                            unqualified.remove(local)
                        }
                        clash != null || names.resolveType(local) != null -> {}
                        else -> {
                            starOffers[local] = imp.ref
                            unqualified[local] = Imported(canonicalAlias(imp.alias), real, imp.ref)
                        }
                    }
                    continue
                }
                when {
                    clash != null -> err(
                        at,
                        "'$local' is already imported from \"${clash.ref}\" — rename one of them: " +
                            "'import {$real as <name>} from \"${imp.ref}\"'",
                    )
                    names.resolveType(local) != null -> err(
                        at,
                        "'$local' is already a node, so an unqualified import of it would change what " +
                            "every call to it means — rename it: " +
                            "'import {$real as <name>} from \"${imp.ref}\"'",
                    )
                    else -> {
                        unqualified[local] = Imported(canonicalAlias(imp.alias), real, imp.ref)
                        // The local-shadow check needs this document's own declarations, which have not
                        // been read yet — see [reportLocalShadows] for why the table cannot wait for them.
                        shadowChecks += ShadowCheck(local, real, imp.ref, at)
                    }
                }
            }
        }
    }

    /**
     * `export default { run, setup }` — one record type and one instance of it, both `@default`.
     *
     * **Reusing the machinery rather than adding any.** The instance is an ordinary `pendingInits` entry
     * holding an ordinary struct literal, so it is built by the same prologue a computed `var` default
     * uses and read by the same field access `p.x` uses. What the bundle costs is this function; what it
     * would have cost as its own kind of value is a node type, an opcode and a case in every walker.
     *
     * A field's TYPE comes from the declaration it names, which is why this runs after the signature pass:
     * a function contributes its signature as a function type, anything else contributes its own.
     */
    private fun bundle(d: DefaultBundleDecl) {
        val fields = ArrayList<FunctionPin>()
        for (name in d.names) {
            val fn = functions.firstOrNull { it.name == name }
            val v = variables.firstOrNull { it.name == name }
            val type = when {
                fn != null -> TypeRef.function(
                    fn.params.map { it.type },
                    fn.results.firstOrNull()?.type ?: TypeRef.NOTHING,
                )
                v != null -> v.type
                else -> {
                    err(d.span, "'$name' is not declared here, so it cannot be part of the default")
                    continue
                }
            }
            fields += FunctionPin(name, type)
        }
        if (fields.isEmpty()) return
        structs += StructType(Parser.DEFAULT_BUNDLE, fields, isExported = true)
        variables += GraphVariable(
            Parser.DEFAULT_BUNDLE, TypeRef.named(Parser.DEFAULT_BUNDLE), null, isExported = true,
        )
        declSpans["var:${Parser.DEFAULT_BUNDLE}"] = d.span
        pendingInits += PendingInit(
            Parser.DEFAULT_BUNDLE,
            StructLitExpr(Parser.DEFAULT_BUNDLE, d.names.map { FieldInit(it, NameExpr(it, d.span), d.span) }, d.span),
            d.span,
        )
    }

    private val exportCache = HashMap<String, Map<String, Export>>()

    /**
     * What [doc] offers an importer, re-exports followed — see [exportsOf].
     *
     * Lowering's copy of the question `ImportScope` answers after the graph exists. The two share the one
     * implementation rather than each walking declarations, which is what stops them disagreeing about a
     * barrel: this decides that a name RESOLVES and that one decides what it means.
     */
    private fun exportsIn(doc: Graph): Map<String, Export> =
        exportCache.getOrPut(doc.id.ifBlank { doc.name }) {
            exportsOf(doc, resolve = { _, imp -> source.load(imp) })
        }

    /** Everything a document offers an importer, by the name it offers it under. */
    private fun exportedNames(doc: Graph): Set<String> = exportsIn(doc).keys

    /** Does this document declare [name] itself? Its own declarations win over any import. */
    private fun declaresLocally(name: String): Boolean =
        functions.any { it.name == name } || structs.any { it.name == name } ||
            enums.any { it.name == name } || variables.any { it.name == name } ||
            constDecls.containsKey(name)

    private fun declarations(decls: List<Decl>) {
        // Enums first, in a pass of their own. A variable's DEFAULT may name a member — `var State: Phase =
        // Phase.Chop` — and [literalOf] has to resolve it while that declaration is being lowered, so an
        // enum written BELOW the variable would not exist yet. Whether a declaration is visible should not
        // depend on which line it is on. Nothing else here reads a declaration's VALUE, which is why this is
        // the only kind that needs the pass.
        // Consts and RECORDS before enums, so a member's row may name a const and may be a record literal.
        // Consts are bound later, in declaration order, and records are lowered in the main pass below —
        // but a const is itself a literal expression and a record's shape is its declaration, so the
        // declarations are all `literalOf` needs. Without this an enum could only carry a record declared
        // above it, which is exactly the "depends on which line it is on" rule the pass above rejects.
        for (d in decls) if (d is ConstDecl) constDecls.putIfAbsent(d.name, d.value)
        // A `val` with no written type is a const when its value folds — registered here, before
        // anything is lowered, so an enum row or a parameter default may name one declared further
        // down. One that does not fold simply answers null from `literalOf` and falls through, which
        // is the same as not being in the table at all.
        for (d in decls) if (d is ValDecl && d.type == null) constDecls.putIfAbsent(d.name, d.value)
        for (d in decls) {
            // A `single` declares a record too — the same field list, and then one variable of it below.
            val fields = when (d) {
                is TypeDecl -> d.fields
                is SingleDecl -> d.fields
                else -> continue
            }
            val name = if (d is TypeDecl) d.name else (d as SingleDecl).name
            declSpans["type:$name"] = d.span
            // SHAPES ONLY here — the defaults are folded further down, after the enums exist. A record's
            // field may be typed as an enum and an enum's COLUMN may be typed as a record, so neither pass
            // can simply run first; what breaks the cycle is that a shape needs only names and types.
            structs += withFieldVariables(
                StructType(
                    name,
                    fields.map { FunctionPin(it.name, typeRef(it.type)) },
                    if (d is TypeDecl) d.isExported else (d as SingleDecl).isExported,
                    if (d is TypeDecl) d.params else emptyList(),
                    isSingle = d is SingleDecl,
                )
            )
        }
        // ...and the one instance. Separate from the loop above so every record exists first: a single's
        // field may be typed as a record declared further down, and the value built here reads the types.
        for (d in decls) {
            if (d !is SingleDecl) continue
            val t = structs.first { it.name == d.name }
            declSpans["var:${d.name}"] = d.span
            // The instance IS the field defaults, folded to the record a run would build — the same value
            // an enum column of record type stores, so nothing new had to learn how to hold it. A field
            // with no default contributes null, which is the type's zero, exactly as a record-typed `var`
            // with no default already behaves.
            // No stored default: the compiler builds a record variable's starting value with `zeroOf`,
            // which reads each field's declared default and falls back to that field type's zero. Storing
            // one here would duplicate that rule and be the copy that goes stale.
            variables += GraphVariable(d.name, TypeRef.named(d.name), null, d.isExported)
            // ...and it is BUILT, not reconstructed. A single is an instance, so its fields are an
            // initialiser like any other: `single Run { anchor: TILE = tiles::home() }` runs the call once
            // at start-up. Only when something in it has to run — an all-literal single still needs no
            // prologue at all, and `zeroOf` fills it from the declared defaults exactly as before.
            if (d.fields.any { f -> f.default != null && enumValueOf(f.default) !is Inline }) {
                // An EMPTY literal: every field is filled by the machinery a construction site already
                // uses — a literal default from the pin, a computed one by calling its function. Listing
                // them here would be a second way to fill the same fields, and the two could disagree.
                pendingInits += PendingInit(d.name, StructLitExpr(d.name, emptyList(), d.span), d.span)
            }
        }
        // The local functions, by name, BEFORE the enum pass reads any row — a row may name one, and the
        // signature pass that registers them has not run yet. Only the names are needed here: what a
        // handler column stores is a name.
        decls.filterIsInstance<FnDecl>().mapTo(enumPassFunctions) { it.name }
        for (d in decls) {
            if (d !is EnumDecl) continue
            declSpans["enum:${d.name}"] = d.span
            // A member's row is read here as LITERALS, the same way a graph variable's default is: a
            // declaration is document data, so there is nowhere in it to hang a node. Anything that is not
            // a literal is reported at the value that is wrong rather than at the member, since a row of
            // four with one bad entry should point at the entry.
            val values = d.members.associate { m ->
                m.name to m.values.mapIndexed { i, v ->
                    (enumValueOf(v, columnWant(d, i)) as? Inline)?.value
                        ?: run { err(v.span, "an enum's values have to be written out, not worked out"); null }
                }
            }
            enums += EnumType(
                d.name,
                d.members.map { it.name },
                d.isExported,
                d.fields.map { f ->
                    // A column's default, so a member may leave a trailing value off — the same thing a
                    // parameter default buys a call site, and the same rule about what one may be.
                    val folded = f.default?.let {
                        enumValueOf(it, Expected(typeRef(f.type), f.name, d.name)) as? Inline
                    }
                    if (f.default != null && folded == null) {
                        err(f.span, "'${f.name}' default has to be a value written out")
                    }
                    FunctionPin(f.name, typeRef(f.type), folded?.value)
                },
                // Rows are kept only for an enum that declares fields, so a plain enum carries nothing new
                // and its document is byte-for-byte what it was.
                if (d.fields.isEmpty()) emptyMap() else values,
            )
        }
        // Now the record field DEFAULTS, with every enum and every record shape in place. Third pass rather
        // than folded into the first because the two declarations point at each other: a record's field may
        // be an enum member (`phase: Phase = Phase.Chop`) and an enum's column may be a record. Whichever
        // ran first would refuse the other's defaults, and the refusal read as "that is not a value".
        for (d in decls) {
            val written = when (d) {
                is TypeDecl -> d.fields
                is SingleDecl -> d.fields
                else -> continue
            }
            if (written.none { it.default != null }) continue
            val name = if (d is TypeDecl) d.name else (d as SingleDecl).name
            val at = structs.indexOfFirst { it.name == name }
            if (at < 0) continue
            val shape = structs[at]
            structs[at] = StructType(
                shape.name,
                shape.fields.mapIndexed { i, pin ->
                    val f = written.getOrNull(i) ?: return@mapIndexed pin
                    // Checked on the Inline, not on its VALUE: `tag: STRING? = null` folds to
                    // `Inline(null)`, and testing the value cannot tell that from "did not fold".
                    val folded = f.default?.let { enumValueOf(it) as? Inline }
                        // ...but a FUNCTION-typed field naming a function is not a literal. `enumValueOf`
                        // folds a function name to its NAME, which is the storage an enum's handler column
                        // wants — on a record field it put the string "twice" where a function VALUE
                        // belonged, and the VM said so at run time. Left unfolded it becomes `= twice`
                        // and lowers to the function reference it is.
                        ?.takeIf { !(pin.type.isFunction && it.value is String) }
                    // ...and one that does NOT fold becomes a function, called wherever a literal leaves
                    // the field out. See BuiltinNodes.FIELD_DEFAULT for why a function rather than an
                    // expression stored on the pin.
                    if (f.default != null && folded == null) {
                        fieldDefaults += FieldDefault(name, f, shape.isExported)
                    }
                    FunctionPin(pin.name, pin.type, folded?.value)
                },
                shape.isExported,
                shape.params,
                shape.isSingle,
            )
        }
        for (d in decls) {
            when (d) {
                is TypeDecl -> {}   // taken in the pass above, beside the enums

                is EnumDecl -> {}   // taken in the pass above
                is VarDecl -> {
                    declSpans["var:${d.name}"] = d.span
                    val literal = (literalOf(d.default) as? Inline)?.value
                    // Not a literal, but something was written: it has to run, so it becomes a
                    // prologue assignment and the stored default stays empty.
                    if (literal == null && d.default != null) {
                        pendingInits += PendingInit(d.name, d.default, d.span)
                    }
                    variables += GraphVariable(d.name, typeRef(d.type), literal, d.isExported)
                }
                is FnDecl -> {
                    declSpans["fn:${d.name}"] = d.span
                    // **A generic function is text-only, and saying so is the point.** The two surfaces
                    // diverge here: a canvas binds a type variable through a receiver, because it knows
                    // what is plugged into `self` before it needs anything else, and it has nowhere to put
                    // a variable a CALL SITE binds. Refusing is what stops the parameters being silently
                    // dropped and the function compiling as if it had never been generic — see
                    // `docs/TEXT_FRONTEND.md`.
                    if (d.typeParams.isNotEmpty()) {
                        err(
                            d.span,
                            "'${d.name}' is generic, and a graph has nowhere to bind '" +
                                d.typeParams.joinToString(", ") + "' — a generic function is text-only",
                        )
                    }
                    // Could this one be an expression? A Call has to be SHAPED before the callee's body
                    // exists, so the question is answered from the declaration and settled afterwards
                    // against the graph — see [assumePure].
                    if (couldBeExpression(d)) {
                        candidates += d.name
                        if (assumePure?.contains(d.name) != false) pureFunctions += d.name
                    }
                    // A body that assigns `self` makes this a mutating extension — see [mutatingFunctions].
                    // Only an extension can be one: a function with no `self` parameter has no receiver to
                    // write back to, and the assignment is already reported where it is written.
                    val mutating = assignsSelf(d.body) &&
                        d.params.any { it.name == GraphFunction.SELF }
                    if (mutating) mutatingFunctions += d.name
                    // A mutating extension's one result IS its receiver, so a declared result has nowhere
                    // to go — the implicit `@self` would silently overwrite it and the function would hand
                    // back something other than what its signature says. Refused rather than resolved: a
                    // statement-position call has one place to write back to, so "mutate AND return" is a
                    // shape the call site could not express even if the signature could.
                    if (mutating && d.results.isNotEmpty()) {
                        err(
                            d.span,
                            "'${d.name}' assigns 'self', which makes it a mutating extension — it hands " +
                                "its receiver back, so it cannot also return " +
                                (if (d.results.size == 1) "a value" else "values") +
                                ". Drop the '->', or stop assigning 'self' and return instead",
                        )
                    }
                    // An extension's receiver is its FIRST parameter, called `self` — and it is now WRITTEN
                    // rather than synthesised here, so this reads it off the list like any other parameter.
                    // Everything downstream — the Call node, its pins, the compiler, the VM — still sees an
                    // ordinary function and needs no change at all.
                    //
                    // **Its absence is meaningful.** `fn Vec2.new(x, y)` declares no `self`, so it is a
                    // function on the TYPE and takes no receiver: `Vec2.new(5, 3)`. That is the whole of
                    // type-level extensions, and it costs nothing to store because `params` already says it.
                    // **The receiver's arguments are a binding site**, so `fn List<T>.first(self) -> T?`
                    // introduces `T` and every other `T` in the signature is that one. Marked HERE, once,
                    // where the declaration is — a `T` discovered later by name would be a phantom record
                    // called T, which is what `TypeRef.named` deliberately makes any unknown name. See
                    // `Generics.kt`.
                    functions += withTypeVariables(GraphFunction(
                        d.name,
                        d.params.map { p ->
                            // A default has to be a value written out: a signature is document data and
                            // there is nowhere in it to hang a node that would work one out.
                            //
                            // Through `enumValueOf` rather than `literalOf`, which is what an enum COLUMN's
                            // default already uses — and whose own note says it is "the same thing a
                            // parameter default buys a call site, and the same rule about what one may be".
                            // It was not the same rule: an enum row could name a `const` and a parameter
                            // could not, so `fn scaled(by: INT = Base)` was refused while `Tier(Base)` was
                            // fine. A named constant is exactly what a shared default wants to be.
                            //
                            // It inherits that fold's stated cost: the value is folded, so `by: INT = Base`
                            // prints back as `by: INT = 10`. Carrying the name instead would mean a slot on
                            // `FunctionPin` for a spelling, which is a model change for punctuation.
                            val folded = p.default?.let {
                                enumValueOf(it, Expected(typeRef(p.type), p.name, d.name)) as? Inline
                            }
                            if (p.default != null && folded == null) {
                                err(p.span, "'${p.name}' default has to be a value written out")
                            }
                            FunctionPin(p.name, typeRef(p.type), folded?.value)
                        },
                        // A mutating extension hands its receiver back, and THAT is where the property
                        // lives — there is no flag anywhere. `isMutating` reads this pin, so the fact
                        // crosses an import, survives JSON and appears on the canvas with nothing kept in
                        // step. A function that declares its own results as well is refused by the
                        // validator rather than silently given two.
                        if (mutating) listOf(FunctionPin(GraphFunction.SELF_RESULT, receiverTypeOf(d)))
                        else d.results.map { FunctionPin(it.name, typeRef(it.type)) },
                        d.isExported,
                        receiver = d.receiver?.let { typeRef(it) },
                    ), ::declaresType)
                }
                // With the other declarations, not with the bodies: a const is a value the whole document
                // can read, so it has to exist before anything that reads it — which includes an `on start`
                // written above it.
                is ConstDecl -> topLevelConst(d)
                is ValDecl -> topLevelVal(d)
                else -> {}
            }
        }
    }

    private fun bodies(decls: List<Decl>) {
        // Before any of them are lowered — see [initsGoOn]. The rule is the AST-side statement of
        // `Graph.initialiserEntry`, which asks the same question of the lowered graph: the wake first,
        // because it is the entry that runs first. The two have to agree or an imported document's
        // prologue is looked for somewhere it was never written.
        initsGoOn = if (decls.any { it is EntryDecl && it.kind == EntryKind.WAKE }) {
            EntryKind.WAKE
        } else {
            EntryKind.START
        }
        for (d in decls) {
            when (d) {
                is EntryDecl -> entry(d)
                is FnDecl -> function(d)
                else -> {}
            }
        }
    }

    /**
     * Give [from] the id [to], fixing up everything that already points at it.
     *
     * `@id` on a **statement** reaches [node] before it builds anything, so the id is simply used. An
     * expression's node is built several layers down — and for `x.field` the target's node is built *before*
     * the one being annotated — so there is no reliable "next node" to hand it to. Renaming afterwards is
     * exact and contained: the id is a field and a key, and the only things holding it are the links made so
     * far, the span table and any node already placed inside this one.
     *
     * Cannot collide: every `@id` in the file is reserved before lowering starts, so [allocate] never hands
     * one out. A file that pins the same id twice is caught by the validator as a duplicate node id.
     */
    /** Ids written as `@id(n)`, gathered before anything is allocated so nothing collides with one. */
    private fun collectPinnedIds(program: Program): HashSet<Int> {
        val out = HashSet<Int>()
        fun visit(ann: Annotations) { ann.id?.let { out += it } }
        fun visitBlock(b: Block) {
            for (s in b.stmts) {
                visit(s.ann)
                when (s) {
                    is IfStmt -> { visitBlock(s.then); s.elseBranch?.let { e ->
                        if (e is IfStmt) visitBlock(Block(listOf(e))) else if (e is ExprBlockStmt) visitBlock(e.block) } }
                    is WhileStmt -> visitBlock(s.body)
                    is ForStmt -> visitBlock(s.body)
                    is SequenceStmt -> s.arms.forEach { visitBlock(it) }
                    is WhenStmt -> { s.arms.forEach { visitBlock(it.body) }; s.elseArm?.let { visitBlock(it) } }
                    else -> {}
                }
            }
        }
        fun visitDecls(decls: List<Decl>) {
            for (d in decls) {
                when (d) {
                    is EntryDecl -> { visit(d.ann); visitBlock(d.body) }
                    is FnDecl -> { visit(d.ann); d.body?.let { visitBlock(it) } }
                    is ConstDecl -> visit(d.ann)
                    else -> {}
                }
            }
        }
        visitDecls(program.decls)
        return out
    }

    // ---- node and link construction --------------------------------------------------------------------

    private fun node(type: String, span: Span, ann: Annotations = Annotations.NONE): Int {
        val id = ann.id ?: allocate()
        val n = Node(id, type, function = currentFn)
        // Where the node sits — the comment block it was written inside, and nothing else. Steps only: the
        // values a step reads are written inside it in the text but sit wherever they like on the canvas.
        val kind = catalog[type]?.kind
        n.group = currentGroup.takeIf { kind == null || kind != NodeKind.PURE }
        // Everything the system does not define is carried through under an `@`-prefixed key, which
        // GraphDoc already persists and which can never collide with a pin name.
        for (extra in ann.extras) {
            n.literals["@${extra.name}"] = if (extra.args.size == 1) extra.args[0] else extra.args
        }
        ann.first("note")?.args?.getOrNull(0)?.let { n.literals["@note"] = it }
        nodes += n
        spans[id] = span
        return id
    }

    private fun allocate(): Int {
        while (nextNode in reserved) nextNode++
        return nextNode++
    }

    private fun link(fromNode: Int, fromPin: String, toNode: Int, toPin: String) {
        links += Link(nextLink++, fromNode, fromPin, toNode, toPin)
    }

    private fun nodeOf(id: Int): Node = nodes.first { it.id == id }

    private fun err(span: Span, message: String) { errors += VsDiagnostic(span, message) }

    /**
     * Something worth saying that must not refuse the document.
     *
     * The parser has had this channel since imports; lowering had only [err], so anything it noticed was
     * either fatal or silent. The first thing that needed the middle ground was a positional destructure
     * taking fewer values than a node gives: legal, deliberately kept legal — a node gaining an output must
     * not break every script that destructured it — and worth pointing at, because the names in a positional
     * binding are the author's own and nothing else can catch a wrong guess about the ORDER.
     */
    private fun warn(span: Span, message: String) { warnings += VsDiagnostic(span, message) }

    /** A node's pins WITH the document's own shapes applied — a Call's signature, a record's fields. */
    private fun resolved(id: Int): NodeDescriptor {
        val n = nodeOf(id)
        val base = catalog[n.type] ?: error("unknown node type '${n.type}'")
        // Purity passed in: a Call to an expression-bodied function has no exec pins, and treating it as a
        // step both puts it on the chain — where nothing should run it — and lets `let` skip the Hold that
        // makes it evaluate once.
        return resolveNode(
            n, base,
            ::signatureOf,
            // [visibleStructs], not `structs + importedTypes` — the BUILTIN records belong here too.
            // Leaving them out made a `Make` node naming one an untyped node: `Tile { x: 0, y: 0 }` came
            // back as a wildcard, so anything holding one stopped being a Tile and `prev.x` reported
            // "this has no field 'x'" on a record the language itself defines.
            //
            // A document-declared record worked, which is what kept this hidden — the two lists differ
            // only in the builtins, and the Validator's equivalent (`ImportScope.visibleTypes`) always
            // included them. One question, one list.
            ::visibleStructs,
            ::isPure,
            ::visibleEnums,
            // A generic call's pins come from what is wired into `self` — see the note on the parameter.
            // Safe against recursion because it asks about a DIFFERENT node: the one on the other end of
            // the wire, which cannot be this one without a data cycle.
            { n, p -> feeding(n, p) },
        )
    }

    /**
     * Is this Call an expression rather than a step?
     *
     * Locally that is "the author wrote an expression body", recorded in [pureFunctions]. Across an
     * import it has to be ASKED OF THE OTHER DOCUMENT, because purity is derived from a function's body
     * nodes and this document has never seen them. Answering from the local set alone made every
     * imported pure function look like a step: the Call went on the exec chain, and the validator then
     * reported "'Call.Result' is read here, but nothing runs 'Call'" for a call that was correct — so a
     * pure function was, in practice, not importable.
     */
    private fun isPure(node: Node, fn: GraphFunction): Boolean =
        purityAcrossImport(node.callee ?: fn.name) ?: (fn.name in pureFunctions)

    /**
     * Is this QUALIFIED callee an expression? Null when the name is local, so the caller decides.
     *
     * An imported function's purity is derived from ITS body nodes, which live in the other document — so
     * it is read there rather than guessed here. The shape `isPureFunction` wants for its `across`
     * parameter, and the same answer [isPure] gives a Call, so a call and the fixpoint that judges the
     * function containing it cannot disagree.
     */
    private fun purityAcrossImport(callee: String): Boolean? {
        val q = QualName.parse(callee)
        if (!q.isQualified) return null
        val doc = importedDocs[q.module] ?: return false
        // The document that really declares it, which across a barrel is not the one the alias names —
        // purity is derived from body NODES, and a barrel has none of them.
        val e = exportsIn(doc)[q.name] ?: return false
        return dev.ziggle.vscript.model.isPureFunction(
            e.name, catalog, e.owner.nodes, e.owner::function, e.owner.links,
        )
    }

    private fun typeRef(t: TypeExpr): TypeRef {
        checkTypeModule(t)
        return TypeRef.parse(qualifyImportedTypes(t).toString())
    }

    /**
     * A type named by a NAMED import, spelled the way the graph stores it.
     *
     * `import { Target } from "…"` puts `Target` in this file's namespace, and every VALUE of it is stored
     * `@2::Target` — because that is how a name says which document it came from. A type POSITION was the
     * one place that never asked the table, so the declaration said `Target`, the values said
     * `@2::Target`, and the two refused to wire on a file that was written correctly:
     *
     *     'Method.target' is typed 'Target', which this graph does not declare
     *     cannot wire @2::Target into Target
     *
     * Recursive, because the name may be inside — `LIST<Target>` and `act(HunterRumor)` both carry one.
     */
    private fun qualifyImportedTypes(t: TypeExpr): TypeExpr {
        val args = t.args.map { qualifyImportedTypes(it) }
        val imported = if (t.module == null && t.name !in localTypeNames) unqualified[t.name] else null
        // Through the EXPORT MAP rather than by asking the aliased document, because the document a name
        // arrives through is not always the one that declares it. A barrel — `export { Point } from "geo"`
        // — declares nothing at all, so asking it directly answered "no such type", the name stayed bare,
        // and `p.x` on a parameter typed through the barrel reported "this has no field 'x'" on a file
        // that is written correctly. The map already knows the OWNER; every other lookup that crosses an
        // import goes through it, and this was the one left asking the alias.
        val named = imported?.takeIf { i ->
            val e = importedDocs[i.alias]?.let { exportsIn(it)[i.name] } ?: return@takeIf false
            e.owner.structExactly(e.name) != null || e.owner.enumExactly(e.name) != null
        }
        if (named == null) {
            return if (args == t.args) t else TypeExpr(t.name, args, t.span, t.module, t.optional)
        }
        return TypeExpr(named.name, args, t.span, named.alias, t.optional)
    }

    /**
     * A qualified type names a DOCUMENT, and it has to be one this file imported.
     *
     * Checked here because a declaration's types were the one place an unknown alias said nothing. An
     * EXPRESSION reaching through a dead alias has always been reported — `rumor::target()` says "nothing
     * is imported as 'rumor'" — but `enum Method(target: rumor::Target)` was accepted in silence, and the
     * first complaint arrived somewhere else entirely: every field read off a value of that type, saying
     * "this has no field", which sends somebody to the wrong line in the wrong file.
     *
     * Recursive, because the alias may be inside — `act(rumor::HunterRumor)` and `LIST<geo::Point>` both
     * name a document, and neither is the outermost name.
     */
    private fun checkTypeModule(t: TypeExpr) {
        t.module?.let { m ->
            if (m !in importedDocs) err(t.span, "nothing is imported as '$m'")
        }
        t.args.forEach { checkTypeModule(it) }
    }

    /**
     * The signature a callee name names — this document's functions, or an imported document's.
     *
     * Both spellings go through here so a Call node is shaped the same whichever side of an import it
     * came from; a local-only lookup would leave every imported call with bare exec pins and silently
     * drop its arguments.
     */
    private fun signatureOf(name: String): GraphFunction? {
        val q = QualName.parse(name)
        if (!q.isQualified) return functions.firstOrNull { it.name == q.name }
        val alias = q.module ?: return null
        val doc = importedDocs[alias] ?: return null
        // `private` does not cross, and the signature's own types are renamed into this document's
        // vocabulary — otherwise the caller's `geo::Point` refuses to wire into the callee's `Point`.
        // Through the export map, so a function reached across a barrel resolves to the document that
        // really declares it — and its signature is renamed from THERE, not from the barrel.
        val e = exportsIn(doc)[q.name] ?: return null
        val fn = e.owner.function(e.name) ?: return null
        // **Through the OWNER, not through `doc`** — the line above finds the document that really declares
        // the function, and this is the half that used to ignore it. [qualifyThrough] renames a type only
        // when the document it is handed declares that type, and a BARREL declares nothing: qualifying a
        // forwarded signature through the barrel therefore left every type in it bare, while the record
        // fields, the variables and the extension receivers beside it were all qualified through the owner.
        //
        // Two spellings of one type, and they refuse to wire. It stayed invisible for as long as a barrel
        // forwarded only plain functions — bare types wire into bare types perfectly well — and surfaced
        // the moment one forwarded an EXTENSION, as `'twice' extends '@1::Point', and this is 'Point'` on
        // code that is exactly right.
        return respellFromOwner(e.owner, dev.ziggle.vscript.model.qualifyThrough(alias, e.owner, fn))
    }

    /**
     * A signature spelled in the OWNER's vocabulary, respelled into THIS document's.
     *
     * **[qualifyThrough] renames what the owner DECLARES; this renames what the owner IMPORTED**, and a
     * barrel makes the second case the common one. `core/loadout` forwards `wants` (which declares
     * `Loadout`) and `tools` (which imports `wants` and declares `fn Loadout.topUp`). The receiver of
     * `topUp` is written in TOOLS' word for wants — its own synthesised `@1` — and `qualifyThrough` leaves
     * it alone, because tools does not declare `Loadout`. The importer meanwhile calls the same document
     * something else entirely, and the two refuse to wire:
     *
     * ```
     * 'topUp' extends '@1::Loadout', and this is '@4::Loadout'
     * ```
     *
     * **The numbers are why this hid for so long.** An unqualified import's alias is synthesised per
     * document, so when the owner and the importer happen to have the same number of imports they both say
     * `@1` and the untranslated spelling is accidentally correct. It only breaks once the importer has more
     * imports than the owner — `tithe.vs` reaches `core/loadout` fourth, so it says `@4` while tools says
     * `@1`. Three tests written before this one passed for exactly that reason and proved nothing.
     *
     * The translation follows re-exports on our side, because a barrel is precisely how we reach the
     * document in question: we ask each of our own imports "do you offer this name, and does it come from
     * the document the owner meant". A type reached through a document we do not import at all is left
     * alone — there is no name here it could be spelled as, and the wire refusal says so honestly.
     */
    private fun respellFromOwner(owner: Graph, fn: GraphFunction): GraphFunction = GraphFunction(
        fn.name,
        fn.params.map { FunctionPin(it.name, respellFromOwner(owner, it.type)) },
        fn.results.map { FunctionPin(it.name, respellFromOwner(owner, it.type)) },
        fn.isExported,
        receiver = fn.receiver?.let { respellFromOwner(owner, it) },
    )

    private fun respellFromOwner(owner: Graph, t: TypeRef): TypeRef {
        val args = t.args.map { respellFromOwner(owner, it) }
        val q = QualName.parse(t.name)
        val mod = q.module ?: return t.withArgs(args)
        // Which document does the OWNER mean by that alias? Its own import list is the only authority.
        val ownerImp = owner.imports.firstOrNull { it.alias == mod } ?: return t.withArgs(args)
        val reached = source.load(ownerImp) ?: return t.withArgs(args)
        // **The document the owner NAMES may itself be a barrel**, so follow it to whoever really declares
        // the type. Comparing the two sides' import targets works only while both import the declaring file
        // directly; the moment one side reaches it through a package front door the targets are a barrel
        // and a file and never match. Both sides are resolved to the DECLARER, which is the one thing they
        // can agree on however many barrels sit in between.
        val target = exportsIn(reached)[q.name]?.owner ?: reached
        // And what do WE call the document that offers this name? `exportsIn` follows barrels, which is
        // what makes a forwarded type findable at all.
        val ours = imports.firstOrNull { imp ->
            imp.bindsLocally && importedDocs[imp.alias]?.let { d ->
                val ourDeclarer = exportsIn(d)[q.name]?.owner ?: d
                dev.ziggle.vscript.model.key(ourDeclarer) == dev.ziggle.vscript.model.key(target)
            } == true
        } ?: return t.withArgs(args)
        return t.renamedTo("${canonicalAlias(ours.alias)}${QualName.SEP}${q.name}").withArgs(args)
    }

    /**
     * Records nameable through an alias, spelled as the importer writes them.
     *
     * `alias::Name` and not the bare name, because that is what [ImportScope.visibleTypes] produces and
     * the COMPILER resolves `struct.of` against that list. Lowering a record to its bare name would
     * compile to "names no declared type" one stage later — and two documents may each declare a `Point`,
     * so the qualifier is what tells them apart.
     *
     * `private` records do not cross, for the same reason private functions do not.
     */
    /**
     * **The FIELD TYPES are renamed too, not just the record's own name.** A field declared `a: Altar` in
     * the library is an `ids::Altar` from here, and copying the fields verbatim left it spelled `Altar` —
     * a name that resolves to nothing in this document. What that looked like was `r.a.level` reporting
     * "this has no field 'level'": the read found no enum of that name, gave up on the table, and fell
     * through to the record path to report a missing field. `ImportScope.visibleTypes` has always done
     * this rewriting for the validator; this is the same question asked one stage earlier, and the two
     * disagreeing is what made the failure look like a struct-lowering bug in `rumor.vs`.
     */
    private val importedTypes: List<StructType> by lazy {
        bindingImports().flatMap { (alias, doc) ->
            exportsIn(doc).mapNotNull { (offered, e) ->
                val s = e.owner.structExactly(e.name) ?: return@mapNotNull null
                StructType(
                    "$alias${QualName.SEP}$offered",
                    s.fields.map { f ->
                        FunctionPin(
                            f.name,
                            dev.ziggle.vscript.model.qualifyThrough(alias, e.owner, f.type),
                            // **And the DEFAULT travels too.** Third time this list has been rebuilt from
                            // too little — the enum table above, the field types beside it, and now this.
                            // A literal default rides on the `struct.make` pin and the pins are built from
                            // exactly this list, so dropping it here meant an imported record's defaults
                            // simply did not exist: a field left out of a literal in ANOTHER document read
                            // back null, through a type that says it cannot be. Which is every field a
                            // default is for, since a default on a record nobody else builds is a default
                            // nobody needs.
                            default = f.default,
                        )
                    },
                )
            }
        }
    }

    /** Every record this document may name: its own, plus the imported ones under `alias::Name`. */
    // Builtins LAST, so a document that declares its own `Tile` still means its own — shadowing one is
    // unusual, but silently preferring ours would be the surprising reading.
    private fun visibleStructs(): List<StructType> =
        structs + importedTypes + dev.ziggle.vscript.model.HostRecords.dataStructs()

    /**
     * Enums nameable through an alias, spelled as the importer writes them.
     *
     * The same rule [importedTypes] follows and for the same reason: the compiler resolves `enum.of`'s
     * naming pin against this list, so a bare name would compile to "names no declared enum" one stage
     * later, and two documents may each declare a `Phase`.
     *
     * **The TABLE comes with it.** Renaming used to build the qualified copy from the name and the members
     * alone, which dropped [EnumType.fields] and [EnumType.values] on the floor — so an imported enum kept
     * its members and lost its columns, and `ids::Altar.Air.level` reported "this has no field 'level'"
     * while the identical read inside the declaring document was fine. `enumFieldOf` gives up on an enum
     * whose `fields` are empty, which is what turned a missing table into a message about a missing field.
     * `gotr-ids` keeps every question about its table in the same document as the table because of this,
     * and `rumor` carries a duplicate column per field for the same reason.
     */
    private val importedEnums: List<EnumType> by lazy {
        bindingImports().flatMap { (alias, doc) ->
            exportsIn(doc).mapNotNull { (offered, ex) ->
                val e = ex.owner.enumExactly(ex.name) ?: return@mapNotNull null
                EnumType(
                    "$alias${QualName.SEP}$offered", e.members,
                    fields = e.fields, values = e.values,
                )
            }
        }
    }

    /**
     * The imports that put names in THIS document — everything except a pure `export … from`.
     *
     * A re-export's names go past this file rather than into it, so it contributes no types, no enums and
     * no extensions here. Missing that would make a barrel able to call what it forwards, which is the one
     * thing separating a re-export from an import followed by an export.
     */
    /**
     * **One spelling per imported DOCUMENT**, whichever line a name arrived on.
     *
     * A document may be imported more than once — and now usually is, because asking for an extension is a
     * `{ … }` list while reaching its types is a namespace: `import * as counters from "counters"` beside
     * `import { bump } from "counters"`. Each line carries its own alias, the second one synthesised, so a
     * type crossing on one arrived spelled `@2::Counter` while a value built through the other was
     * `counters::Counter` — one record, two names, and they refused to wire.
     *
     * So every alias for a ref collapses to one: the WRITTEN namespace when there is one, since that is
     * the only spelling an author can use in a qualified call, and otherwise the first line's.
     */
    private fun canonicalAlias(alias: String): String {
        val imp = imports.firstOrNull { it.alias == alias } ?: return alias
        val same = imports.filter { it.ref == imp.ref && it.bindsLocally }
        return same.firstOrNull { it.hasNamespace }?.alias ?: same.firstOrNull()?.alias ?: alias
    }

    /**
     * A namespace for [alias] that the author actually wrote, or null when the document has none.
     *
     * The stored alias of a named import is synthesised — `@2` — and unwritable, so any message offering a
     * qualified call has to find a real one. Another line importing the SAME document may have written it,
     * which is exactly the shape a collision has: `import * as x from "a"` beside `import { double } from "a"`.
     */
    private fun writableAlias(alias: String): String? {
        val imp = imports.firstOrNull { it.alias == alias } ?: return null
        if (imp.hasNamespace) return imp.alias
        return imports.firstOrNull { it.ref == imp.ref && it.hasNamespace }?.alias
    }

    /** The document an alias came from, for a message that cannot use the alias itself. */
    private fun importRefOf(alias: String?): String =
        imports.firstOrNull { it.alias == alias }?.ref ?: alias.orEmpty()

    private fun bindingImports(): List<Pair<String, Graph>> = imports
        .filter { it.bindsLocally }
        .mapNotNull { imp -> importedDocs[imp.alias]?.let { canonicalAlias(imp.alias) to it } }
        .distinctBy { it.first }

    /** Every enum this document may name: its own, plus the imported ones under `alias::Name`. */
    private fun visibleEnums(): List<EnumType> = enums + importedEnums

    /** The enum [name] names, qualified by [module] when it was written `alias::Name`. */
    /**
     * `alias::Name` when it was written that way, `Name` when it is this document's — and the alias an
     * unqualified import put it behind when the table knows it.
     *
     * One place, so a record, an enum and a call all agree about what a bare imported name means.
     */
    private fun qualifiedName(module: String?, name: String): String {
        if (module != null) return "$module${QualName.SEP}$name"
        val imported = unqualified[name] ?: return name
        return "${imported.alias}${QualName.SEP}${imported.name}"
    }

    /**
     * A type as the AUTHOR would write it — the inverse of [qualifiedName], for MESSAGES.
     *
     * An unqualified import stores its names behind a synthesised alias (`@1`, `@2`), which is exactly the
     * spelling nobody can type: `import { HunterRumor } from "…"` then `fn(@1::HunterRumor)` in a
     * complaint sends the reader looking for an alias that is not in their file. The table already knows
     * the local name; this reads it backwards.
     *
     * Recursive, because the name is usually INSIDE something — `fn(@1::HunterRumor)`, `MAP<@1::Target, …>`.
     */
    private fun spell(t: TypeRef): String {
        val local = QualName.parse(t.name).let { q ->
            if (q.module == null) null
            else unqualified.entries.firstOrNull { (_, i) -> i.alias == q.module && i.name == q.name }?.key
        }
        val head = local ?: t.name
        return t.renamedTo(head).withArgs(emptyList()).toString().removeSuffix("?").let { base ->
            // Rebuilt rather than printed through TypeRef, so the arguments can be spelled too.
            val inner = t.args.map { spell(it) }
            when {
                t.isFunction -> {
                    val params = inner.dropLast(1).joinToString(", ")
                    val result = t.returnsOf?.let { " -> " + spell(it) } ?: ""
                    val written = if (t.args.isEmpty()) "fn" else "fn($params)$result"
                    if (!t.optional) written
                    else if (t.returnsOf == null) "$written?" else "($written)?"
                }
                inner.isEmpty() -> base + if (t.optional) "?" else ""
                else -> "$head<${inner.joinToString(", ")}>" + if (t.optional) "?" else ""
            }
        }
    }

    private fun enumNamed(module: String?, name: String): EnumType? {
        val wanted = qualifiedName(module, name)
        return visibleEnums().firstOrNull { it.name.equals(wanted, true) }
    }

    /**
     * The record [name] names, qualified by [module] when it was written `alias::Name`.
     *
     * One lookup for every place a record is named — a literal, a `with`, a field read — because they
     * used to search `structs` alone and so a record reached through an import resolved in a signature
     * and then failed to construct.
     */
    private fun structNamed(module: String?, name: String): StructType? {
        val wanted = qualifiedName(module, name)
        return visibleStructs().firstOrNull { it.name.equals(wanted, true) }
    }

    // ---- declarations ----------------------------------------------------------------------------------

    private fun entry(d: EntryDecl) {
        val type = when (d.kind) {
            EntryKind.START -> BuiltinNodes.ENTRY
            EntryKind.STOP -> BuiltinNodes.ENTRY_STOP
            EntryKind.RENDER -> BuiltinNodes.ENTRY_RENDER
            EntryKind.TICK -> BuiltinNodes.ENTRY_TICK
            EntryKind.WAKE -> BuiltinNodes.ENTRY_WAKE
            EntryKind.SLEEP -> BuiltinNodes.ENTRY_SLEEP
            // **A test is a text construct and has no node.** Lowering one would need an entry descriptor,
            // a spelling in `ENTRY_WORDS` and a canvas palette entry, for a thing the canvas cannot run —
            // `ScriptRuntime` never pumps this kind. Refusing out loud beats inventing a node that draws.
            EntryKind.TEST -> throw VsSyntaxError(
                d.span,
                "a 'test' is a text-front-end construct and has no node — the canvas cannot run one",
            )
        }
        val id = node(type, d.span, d.ann)
        if (d.isAlways) nodeOf(id).literals[BuiltinNodes.ALWAYS_ENTRY] = true
        pushScope()
        val chain = Chain(listOf(End(id, "Exec")))
        // Before the body: a variable initialised by an expression must hold its value by the time the
        // first written statement runs, or the declaration would be a lie about what it holds. On the
        // first entry that RUNS, which is the wake when there is one — see [initsGoOn].
        if (d.kind == initsGoOn) emitInits(chain)
        stmts(d.body.stmts, chain)
        popScope()
    }

    /**
     * The initialiser prologue — one Set per variable whose default has to run, in declaration order.
     *
     * Marked with `@init` so [Print] can write them back as defaults instead of as statements somebody
     * did not type. That is the same `@`-prefixed metadata channel annotations use, and the same rule:
     * the PRINTER reads it and the compiler never does.
     */
    private fun emitInits(chain: Chain) {
        if (initsEmitted) return
        initsEmitted = true
        for (init in pendingInits) {
            val set = node(BuiltinNodes.VAR_SET, init.span)
            nodeOf(set).variable = init.name
            nodeOf(set).literals[INIT_MARK] = true
            // The variable's DECLARED type is the destination — which is what lets `var F: fn(INT) ->
            // BOOL = { it > 0 }` be written at all. Looked up rather than carried on [PendingInit],
            // because the declaration that made the entry is the same one that made the variable.
            val want = variables.firstOrNull { it.name == init.name }
                ?.let { Expected(it.type, it.name, "this declaration") }
            wire(expr(init.value, chain, want), set, "Value", init.span)
            chain.place(set)
        }
    }

    /**
     * `val Name = …` at document level — one word, two shapes, decided by the value.
     *
     * **Folds to a literal when it can**, which is the whole reason a `const` had to be written out: a
     * literal node is what an enum row and a parameter default require, and a `val` that quietly stopped
     * being one where a `const` was would be a worse trade than the extra keyword. So the fold is tried
     * first, through the same widened one an enum row uses — literals, tiles, enum members, records, lists,
     * and other constants by name.
     *
     * **Otherwise it is a graph variable with a one-time initialiser**, which is what makes `val Home: TILE
     * = nearestBank()` expressible at all — and what folds in the "graph var I never reassign" case that
     * had no spelling before. The initialiser is the same `pendingInits` prologue a `var`'s computed default
     * already uses, so nothing new runs; the variable is marked immutable and [assign] refuses it.
     *
     * The type is required only in the second case, and the message says why rather than restating the
     * rule: a graph variable is always typed, and by here we know that is what this became.
     */
    private fun topLevelVal(d: ValDecl) {
        declSpans["var:${d.name}"] = d.span
        val folded = enumValueOf(d.value) as? Inline
        // **Whether it folds is a property of the VALUE, never of whether a type was written beside it.**
        // It used to be both: an annotated `val` became a graph variable even when its value was a
        // constant, on the reasoning that a literal node had nowhere to carry the type. The cost was that
        // adding an annotation — the most ordinary thing an author does for a reader — moved the
        // declaration out of every place a constant is allowed. `val Impl: Hooks = Hooks { … }` could not
        // be named by an enum row and did not cross an import as a value, while the identical line without
        // `: Hooks` did both; and nothing said why, because the two spellings mean the same thing.
        //
        // Now the type is kept on the node — see [CONST_TYPE] — and the fold decides on its own.
        if (folded != null) {
            // Exactly a `const`: one literal node, `@name`, and every reader wired to it. Registered in
            // `constDecls` too, so an enum row or a parameter default can name it.
            constDecls.putIfAbsent(d.name, d.value)
            val id = literalNode(folded.value, d.value, d.ann, d.name)
            // The same second literal a `const` carries — a folded `val` IS one, so its export has to ride
            // the same way or `export val Home = tile(…)` comes back without its `export`.
            if (d.isExported) nodeOf(id).literals[CONST_EXPORTED] = true
            // ...and the third, when one was written. Stored as the text the printer would have produced
            // for a graph variable's type, so the two spellings of a type come back identically.
            d.type?.let { nodeOf(id).literals[CONST_TYPE] = typeRef(it).toString() }
            bind(d.name, FromPin(id, "Value"))
            return
        }
        if (d.type == null) {
            err(
                d.span,
                "'${d.name}' has to be worked out while running, so it is a graph variable underneath — " +
                    "give it a type: 'val ${d.name}: <Type> = …'",
            )
            return
        }
        // Everything that did NOT fold: a graph variable with a one-time initialiser. A type is required
        // here and the error above says why — there is nothing to infer one from.
        pendingInits += PendingInit(d.name, d.value, d.span)
        variables += GraphVariable(d.name, typeRef(d.type), null, d.isExported, isImmutable = true)
    }

    private fun topLevelConst(d: ConstDecl) {
        val src = literalOf(d.value)
        if (src !is Inline) { err(d.span, "'${d.name}' has to be a value written out"); return }
        val id = literalNode(src.value, d.value, d.ann, d.name)
        // A const has no record of its own on the document, so its export is a second literal beside
        // its name — see [exportedNames]. Written only when true, so a document without one is
        // byte-for-byte what it was.
        if (d.isExported) nodeOf(id).literals[CONST_EXPORTED] = true
        bind(d.name, FromPin(id, "Value"))
    }

    /**
     * A function: its box, and its body.
     *
     * The box IS the boundary. Its parameters are OUTPUTS (the body reads them) and its results are INPUTS
     * (the body writes them) — so a body reaches its results either by wiring straight into the box or with
     * a `return`, and which of those happens is decided HERE rather than by how the body was spelled.
     *
     * **One body form.** `= e` arrives as `{ return e }` ([FnDecl]), so there is one path through this
     * function and one rule at the end of it: a body that is a single `return` gets the expression shape
     * when its value turned out to need no exec chain, and the ordinary step shape when it did. Nothing
     * declares purity — the box's exec pins are left unwired or not, and `isPureFunction` reads the answer
     * off the graph exactly as it does for a canvas.
     */
    private fun function(d: FnDecl) {
        val prevFn = currentFn
        val prevBox = currentBox
        currentFn = d.name
        val box = node(BuiltinNodes.FUNCTION, d.span, d.ann)
        nodeOf(box).function = d.name
        currentBox = box
        // `fn f() = call(…)` — the short body form on a function with no result, which means RUN it. The
        // spelling is marked so the printer gives it back; without this it comes out braced, which is the
        // same program written differently and exactly what BARE exists to stop elsewhere.
        val bareRun = !d.body.braced && d.results.isEmpty()
        if (bareRun) BuiltinNodes.bareOf(listOf("body"))?.let { nodeOf(box).literals[BuiltinNodes.BARE] = it }
        val prevBareRun = inBareRun
        inBareRun = bareRun

        pushScope()
        // `self` is an ordinary parameter and is bound by the loop below like any other — it is params[0]
        // when it was WRITTEN, and absent when it was not. That absence is what makes a function
        // type-level, so binding it unconditionally would quietly give `fn Vec2.new(…)` a receiver it never
        // declared and cannot be passed one.
        for (p in d.params) bind(p.name, FromPin(box, p.name))

        // A mutating extension's `self` is a mutable LOCAL seeded from the parameter, not the parameter
        // itself. A parameter is a pin on the box and pins are read-only — so without this the assignment
        // has nowhere to write, which is exactly what it reports today. Rebinding the name here means
        // every read after this point goes through the Hold and sees what was last written, which is the
        // ordinary `var` rule rather than a rule invented for `self`.
        val prevSelfHold = selfHold
        selfHold = null
        if (d.name in mutatingFunctions) {
            val hold = node(BuiltinNodes.HOLD, d.span)
            nodeOf(hold).literals[BuiltinNodes.HOLD_NAME] = GraphFunction.SELF
            nodeOf(hold).literals[BuiltinNodes.MUTABLE] = true
            link(box, GraphFunction.SELF, hold, "Value")
            selfHold = hold
            bind(GraphFunction.SELF, FromPin(hold, "Value"))
        }

        // The body sits INSIDE the box, which is what the canvas records when you drop a node into one.
        // Set after the box itself is made, so the box keeps the group it was declared in.
        val prevGroup = currentGroup
        currentGroup = box

        val chain = Chain(listOf(End(box, "Exec")))
        // First on the chain, before any statement — the seed has to have run before anything reads
        // `self`, and a Hold is a step like any other.
        selfHold?.let { chain.place(it) }
        // A body of one `return` decides its own shape and finishes the job either way — there is nothing
        // after it to fall through to, so no open end is wired back to the box here. When it wired straight
        // into the box, the box's exec pins staying unwired is exactly the signal that this is an
        // expression, and adding one would delete that.
        if (!tailReturn(d, box, chain)) {
            val end = stmts(d.body.stmts, chain)
            // Falling off the end of a body returns whatever the result pins are fed by — the compiler's
            // own rule. Wiring the open end back to the box is how that is said in a graph.
            for (e in end) link(e.node, e.pin, box, "Exec")
            // ...and for a mutating extension what it hands back is the local, whatever it last held.
            selfHold?.let { link(it, "Value", box, GraphFunction.SELF_RESULT) }
        }
        popScope()
        selfHold = prevSelfHold
        currentGroup = prevGroup
        currentFn = prevFn
        currentBox = prevBox
        inBareRun = prevBareRun
    }

    /**
     * A body that is one `return`, wired straight into the box — or not, if it turned out to need a step.
     *
     * **This is where a function becomes an expression**, and the decision is made from the lowered value
     * rather than from anything the author declared. The value is lowered onto the real chain first; if
     * nothing joined it — no impure call, no Hold, nothing that has to happen in an order — then the chain
     * is still the box's own open end, there is nothing to sequence, and the results are fed directly. The
     * box keeps its exec pins unwired and `isPureFunction` calls it an expression.
     *
     * If something DID join the chain, the same lowered values get a `return` node and the ordinary step
     * shape — nothing is lowered twice and nothing is thrown away, because the two shapes differ only in
     * where the values are wired. That is what makes a one-line function legal whatever it calls.
     *
     * Hands back false only when the body is not a single `return` at all, or when the short form would
     * lose something rather than mean the same thing: a different number of values than results, or a
     * mutating extension — whose `self` Hold is on the chain before the first statement, so its box is
     * exec-wired by construction and there is nothing to decide.
     */
    private fun tailReturn(d: FnDecl, box: Int, chain: Chain): Boolean {
        if (selfHold != null || d.results.isEmpty()) return false
        val only = d.body.stmts.singleOrNull() as? ReturnStmt ?: return false
        if (only.values.size != d.results.size) return false
        // A multi-result function keeps its Return node whatever its values do — see [couldBeExpression].
        // Wiring several results into the box would make it an expression that is read once per result.
        if (d.results.size != 1) return false
        // Each declared RESULT is a destination — which is what lets `fn f() -> fn(INT) -> BOOL =
        // { it > 0 }` be written, and with it every computed field default, since one is lowered as a
        // synthesised function whose whole body is this shape.
        val srcs = only.values.mapIndexed { i, v ->
            val r = d.results[i]
            expr(v, chain, Expected(typeRef(r.type), r.name, d.name))
        }
        // Nothing joined the chain, so the box's own end is still the only one open: there is no order for
        // anything to happen in, and the results can be fed directly. An annotation is the one thing that
        // needs a node to live on, so a `@id(7) return` keeps its Return.
        val asExpression = only.ann === Annotations.NONE &&
            chain.open.singleOrNull()?.let { it.node == box && it.pin == "Exec" } == true
        if (asExpression) {
            srcs.forEachIndexed { i, s -> wire(s, box, d.results[i].name, only.values[i].span) }
            return true
        }
        val ret = node(BuiltinNodes.RETURN, only.span, only.ann)
        srcs.forEachIndexed { i, s -> wire(s, ret, d.results[i].name, only.values[i].span) }
        chain.place(ret, outPin = null)
        return true
    }

    // ---- statements -------------------------------------------------------------------------------------

    /**
     * A block's statements, onto [chain].
     *
     * **A statement that ends the chain ends the block.** `return`, `break` and `continue` all leave
     * `chain.open` empty — nothing after them can be reached — and lowering the rest anyway built nodes with
     * no exec wire into them. The graph then simply did not contain what the author wrote, so a text → graph
     * → text round trip DELETED the lines: no error, no warning, and a file that came back shorter than it
     * went in.
     *
     * Stopping is what the graph already meant; the warning is the part that was missing. On the canvas the
     * same shape is a node with nothing wired to it, which you can see — in text there was nothing to see.
     */
    private fun stmts(list: List<Stmt>, chain: Chain): List<End> {
        for ((i, s) in list.withIndex()) {
            stmt(s, chain)
            if (chain.open.isEmpty() && i < list.size - 1) {
                // Named where it can be, because "unreachable" is only useful once you know what made it
                // so — and for a branch whose every arm returns, that is not the line above.
                val why = when (s) {
                    is ReturnStmt -> "the 'return' above"
                    is BreakStmt -> "the 'break' above"
                    is ContinueStmt -> "the 'continue' above"
                    else -> "every path above this ends"
                }
                warn(list[i + 1].span, "nothing can reach this — $why. It will not be part of the script")
                break
            }
        }
        return chain.open
    }

    private fun stmt(s: Stmt, chain: Chain) {
        when (s) {
            // A test is a text construct (see [entry]), so its statements are too — there is no assert node
            // for the canvas to draw and nothing that would run one. Refused where it is written.
            is AssertStmt -> throw VsSyntaxError(
                s.span,
                "'assert' belongs to a 'test', which the canvas cannot run",
            )
            // `xs[i] = v` is sugar for a mutating extension call — see [IndexAssignStmt] — and the canvas
            // has no node for one. Refused where it is written rather than lowered to something that
            // would print back as a different program.
            is IndexAssignStmt -> throw VsSyntaxError(
                s.span,
                "index assignment is a text construct — the canvas has no node for it",
            )
            is LetStmt -> letStmt(s, chain)
            is ConstStmt -> {
                val src = literalOf(s.value)
                if (src !is Inline) err(s.span, "'${s.name}' has to be a value written out")
                else bindDeclared(s.name, FromPin(literalNode(src.value, s.value, s.ann, s.name), "Value"), s.span)
            }
            is AssignStmt -> assign(s, chain)
            is FieldAssignStmt -> fieldAssign(s, chain)
            is IfStmt -> ifStmt(s, chain)
            is IfLetStmt -> ifLetStmt(s, chain)
            is WhileStmt -> whileStmt(s, chain)
            is ForStmt -> forStmt(s, chain)
            is ReturnStmt -> returnStmt(s, chain)
            is TryStmt -> tryStmt(s, chain)
            // Both END the chain: nothing after them in that block can run, so the open end is dropped
            // rather than wired on. Leaving it open would put the next statement after a jump.
            is BreakStmt -> chain.place(node(BuiltinNodes.BREAK, s.span, s.ann), outPin = null)
            is ContinueStmt -> chain.place(node(BuiltinNodes.CONTINUE, s.span, s.ann), outPin = null)
            is SequenceStmt -> sequenceStmt(s, chain)
            is WhenStmt -> whenStmt(s, chain)
            is ExprStmt -> exprStmt(s, chain)
            is ExprBlockStmt -> { pushScope(); stmts(s.block.stmts, chain); popScope() }
        }
    }

    /**
     * `let x = …`
     *
     * A `Hold` unless the value is already a stable register under the same name. A pure node is re-expanded
     * at every use site, so without the Hold `let` would silently mean "run this again per reader" — see
     * VSCRIPT_LANG_PLAN.md §6.2. The exception keeps hand-wired graphs round-tripping structurally: an
     * impure call with one output already has a stable slot, and if the binding is called what the pin is
     * called there is nothing left for a Hold to add.
     */
    private fun letStmt(s: LetStmt, chain: Chain) {
        when (val b = s.binding) {
            is NameBinding -> {
                // A written type is a destination, so `val f: fn(INT) -> BOOL = { it > 0 }` reads as one.
                val want = s.declaredType?.let { Expected(typeRef(it), b.name, "this declaration") }
                val src = expr(s.value, chain, want)
                // A `var` ALWAYS gets a Hold of its own. The shortcut below binds the name straight to a
                // call's output pin — no node, no register — which is exactly right for a name that will
                // never change and leaves nothing for an assignment to write into.
                // A DECLARED type needs a Hold to carry it, the same way a `var` needs one to be written
                // into — the shortcut below binds straight to a call's output pin, which has nowhere to
                // record what the author said the binding was.
                if (!s.mutable && s.declaredType == null &&
                    src is FromPin && sameNamedSingleOutput(src, b.name)
                ) {
                    bindDeclared(b.name, src, s.span)
                    return
                }
                val hold = node(BuiltinNodes.HOLD, s.span, s.ann)
                nodeOf(hold).literals[BuiltinNodes.HOLD_NAME] = b.name
                if (s.mutable) nodeOf(hold).literals[BuiltinNodes.MUTABLE] = true
                if (s.declaredType != null) {
                    nodeOf(hold).literals[BuiltinNodes.HOLD_TYPE] = typeRef(s.declaredType).toString()
                } else {
                    // Nothing written, so record what the SOURCE knows for the values whose stored form
                    // cannot say what they are — see [BuiltinNodes.HOLD_INFERRED]. Not HOLD_TYPE: the
                    // printer writes that one back, and `let t: TILE = …` is a line nobody typed.
                    sourceTypeOf(s.value)
                        ?.let { nodeOf(hold).literals[BuiltinNodes.HOLD_INFERRED] = it.toString() }
                }
                wire(src, hold, "Value", s.span)
                chain.place(hold)
                bindDeclared(b.name, FromPin(hold, "Value"), s.span)
            }
            is TupleBinding -> {
                val src = expr(s.value, chain)
                if (src !is FromPin) { err(s.span, "this produces one value, not several"); return }
                val outs = resolved(src.node).dataOutputs
                // pin → the local it was bound to, recorded on the node so the printer can give the names
                // back — a destructure builds no node of its own, so this is the only place they can live.
                val boundPins = LinkedHashMap<String, String>()
                // By name when the author said so with a `local: Pin`, OR when every bare name IS an
                // output's name. The second is the Rust-ish reading — `let (exists, distance) = …` does what
                // it looks like — and it can only be decided here, because it depends on what is being
                // destructured and the parser resolves nothing.
                //
                // It is a real change in meaning for an all-bare list whose names match out of ORDER, and
                // that is the point: `let (name, entity) = lastClicked()` used to bind Id and Kind. A list
                // whose names are not all outputs — `let (a, b) = …` — is positional exactly as before, so
                // the names have to be pins for anything to move.
                val byName = b.explicitlyNamed || b.entries.all { e ->
                    e.pin == null && outs.any {
                        it.name.equals(e.local, true) || Names.pinText(it.name).equals(e.local, true)
                    }
                }
                if (byName) {
                    for (e in b.entries) {
                        // A bare entry in a by-name list is shorthand for `x: x` — the pin it takes is the
                        // one it is called after.
                        val wanted = e.pin ?: e.local
                        val pin = outs.firstOrNull {
                            it.name.equals(wanted, true) || Names.pinText(it.name).equals(wanted, true)
                        }
                        if (pin == null) {
                            err(
                                e.span,
                                "this has no output called '$wanted' — it gives " +
                                    outs.joinToString(", ") { Names.pinText(it.name) } +
                                    if (e.pin == null) ". A bare name in a list that binds by name is " +
                                        "shorthand for 'x: x', so write 'x: <output>' to take a different one"
                                    else "",
                            )
                            continue
                        }
                        bindDeclared(e.local, FromPin(src.node, pin.name), s.span)
                        boundPins[pin.name] = e.local
                    }
                } else {
                    if (outs.size < b.names.size) {
                        err(s.span, "this gives ${outs.size} value(s) but ${b.names.size} names were written")
                    }
                    // Taking a PREFIX is allowed and stays allowed: a node gaining an output must not break
                    // every script that destructured it. But it is worth saying out loud, because the names
                    // are the author's own and so nothing else can catch a wrong guess about the ORDER —
                    // which is the whole reason the named form exists.
                    if (outs.size > b.names.size) {
                        warn(
                            s.span,
                            "this gives ${outs.size} values and ${b.names.size} were taken, so these are " +
                                "skipped: " + outs.drop(b.names.size).joinToString(", ") { Names.pinText(it.name) } +
                                ". Bind by name — 'let (${Names.pinText(outs[0].name)}: x) = …' — to say " +
                                "which you mean",
                        )
                    }
                    // No "these look like output names" warning here any more: a list whose names are ALL
                    // outputs now BINDS by them, so the case the warning existed for cannot arise. What is
                    // left is genuinely positional — names that are the author's own — and nothing can be
                    // checked about those.
                    b.names.forEachIndexed { i, n ->
                        outs.getOrNull(i)?.let {
                            bindDeclared(n, FromPin(src.node, it.name), s.span); boundPins[it.name] = n
                        }
                    }
                }
                BuiltinNodes.boundOf(boundPins)?.let {
                    nodeOf(src.node).literals[BuiltinNodes.BOUND] = it
                }
            }
            is RecordBinding -> recordBinding(b, s, chain)
        }
    }

    /** `let {tile, name} = s` — one `struct.split`, a field per name. */
    private fun recordBinding(b: RecordBinding, s: LetStmt, chain: Chain) {
        val src = expr(s.value, chain)
        if (src !is FromPin) { err(s.span, "a record has to come from somewhere"); return }
        val typeName = structNameOf(src)
        if (typeName == null) { err(s.span, "this is not a record, so it has no fields to take apart"); return }
        val split = node(BuiltinNodes.STRUCT_SPLIT, s.span, s.ann)
        nodeOf(split).literals[BuiltinNodes.STRUCT_OF] = typeName
        wire(src, split, "Value", s.span)
        val t = structs.first { it.name.equals(typeName, true) }
        for (n in b.names) {
            val field = t.fields.firstOrNull { Names.pinText(it.name) == n || it.name.equals(n, true) }
            if (field == null) err(s.span, "'$typeName' has no field '$n'")
            else bindDeclared(n, FromPin(split, field.name), s.span)
        }
    }

    private fun sameNamedSingleOutput(src: FromPin, name: String): Boolean {
        val d = resolved(src.node)
        if (d.kind != NodeKind.IMPURE) return false
        val outs = d.dataOutputs
        return outs.size == 1 && Names.pinText(outs[0].name) == name && outs[0].name == src.pin
    }

    /**
     * `s.f = v` — desugared to `s = s with { f: v }` and lowered as that.
     *
     * Rewritten at the AST rather than lowered separately, so the two spellings CANNOT produce different
     * graphs: one is literally the other. A separate lowering would be a second implementation of "copy a
     * record with one field changed", and the two would eventually disagree about something — the order
     * the value is evaluated in, or which node carries the span.
     *
     * Nested targets fold outward: `a.b.c = v` rewrites to `a.b = a.b with { c: v }` and recurses, giving
     * `a = a with { b: a.b with { c: v } }`. Each level rebuilds the record above it, because that is what
     * value semantics MEANS — there is no reference to write through.
     *
     * The marker is what makes it round-trip; see [BuiltinNodes.WROTE_FIELD].
     */
    private fun fieldAssign(s: FieldAssignStmt, chain: Chain) {
        val rewritten = rewriteFieldAssign(s.target, s.field, s.value, s.span, s.ann, s.op)
        if (rewritten == null) {
            err(s.span, "only a record held by a name can have a field assigned — assign to a local or a variable")
            return
        }
        fieldAssignDepth++
        try {
            assign(rewritten, chain)
        } finally {
            fieldAssignDepth--
        }
    }

    /** Fold a possibly-nested field target into the plain `name = …` the rest of the lowering knows. */
    private fun rewriteFieldAssign(
        target: Expr,
        field: String,
        value: Expr,
        span: Span,
        ann: Annotations,
        op: BinaryOp? = null,
    ): AssignStmt? {
        val replaced = WithExpr(target, listOf(FieldInit(field, value, span)), span)
        return when (target) {
            is NameExpr -> AssignStmt(target.name, replaced, span, ann, op)
            // The op rides all the way out to the OUTERMOST assignment, which is the one that becomes a Set
            // and so the only one with anywhere to carry a mark — the same place `markFieldSpelling` puts
            // the field spelling, and for the same reason.
            is MemberExpr -> rewriteFieldAssign(target.target, target.member, replaced, span, ann, op)
            else -> null
        }
    }

    /** Non-zero while a field assignment is being lowered, so the set it produces can be marked. */
    private var fieldAssignDepth = 0

    /** Record that this set was written `s.f = v`. Only the OUTERMOST one — see below. */
    private fun markFieldSpelling(set: Int) {
        // Depth 1 rather than any depth: a nested `a.b.c = v` folds to ONE assignment to `a`, so there is
        // one set to mark however deep the target was. The counter guards against an assignment appearing
        // inside the value expression, which would otherwise inherit a spelling nobody wrote.
        if (fieldAssignDepth == 1) nodeOf(set).literals[BuiltinNodes.WROTE_FIELD] = true
    }

    private fun assign(s: AssignStmt, chain: Chain) {
        // **Text-only, and refused rather than dropped.** `pace::IdleMs = 50` names another document's
        // variable; a graph writes its variables by node and has no spelling for one it does not own, so
        // ignoring the alias here would write a LOCAL of that name instead — silently, and nowhere near
        // where the author would look. See `docs/TEXT_FRONTEND.md`.
        if (s.module != null) {
            err(s.span, "'${s.module}::${s.name}' writes another document's variable, which a graph cannot do")
            return
        }
        // A LOCAL first. `var n = 0` inside a body is a register, and a register belongs to one call — so
        // a counter counts that call's iterations and a recursive call gets its own. Checked before the
        // graph variables so a local shadowing one wins, which is the reading every language has.
        val local = lookup(s.name)
        if (local is FromPin && nodeOf(local.node).literals[BuiltinNodes.MUTABLE] == true) {
            val set = node(BuiltinNodes.LOCAL_SET, s.span, s.ann)
            nodeOf(set).variable = s.name
            markFieldSpelling(set)
            markCompound(set, s.op)
            nodeOf(set).literals[BuiltinNodes.LOCAL_TARGET] = local.node
            // Copied from the Hold rather than looked up later: what a local was DECLARED as cannot be
            // reached from the assignment through `feeding`, which follows the wire into the Hold and so
            // reports what initialised it — an INT `0` for a local the author wrote `: FLOAT` on.
            (nodeOf(local.node).literals[BuiltinNodes.HOLD_TYPE] as? String)
                ?.let { nodeOf(set).literals[BuiltinNodes.HOLD_TYPE] = it }
            wire(expr(s.value, chain), set, "Value", s.span)
            chain.place(set)
            return
        }
        if (variables.none { it.name == s.name }) {
            // A name that IS in scope but is not assignable is the commoner mistake by far, and the
            // generic message sent people to declare a graph variable they already had a binding for —
            // which for a loop accumulator inside a function is the wrong cure twice over: a graph
            // variable is shared by every call, so it breaks recursion and re-entrancy silently.
            err(
                s.span,
                if (local != null) {
                    "'${s.name}' is a `val`, which names a value once — write 'var ${s.name} = …' " +
                        "instead if it has to change"
                } else {
                    "no graph variable named '${s.name}' — declare it with 'var ${s.name}: Type'"
                },
            )
            return
        }
        // A document-level `val` that had to be worked out IS a graph variable — it has a slot and the
        // prologue writes it — so the only thing standing between it and an assignment is this.
        //
        // No guard for the initialiser: [emitInits] builds its own Set node directly and never comes
        // through here, so the one write that makes the value is already out of reach of this check.
        if (variables.any { it.name == s.name && it.isImmutable }) {
            err(
                s.span,
                "'${s.name}' is a `val`, which is set once and then stays — write 'var ${s.name}: Type = …' " +
                    "instead if it has to change",
            )
            return
        }
        val set = node(BuiltinNodes.VAR_SET, s.span, s.ann)
        nodeOf(set).variable = s.name
        markFieldSpelling(set)
        markCompound(set, s.op)
        val src = expr(s.value, chain)
        wire(src, set, "Value", s.span)
        chain.place(set)
    }

    /**
     * Record that this set was written `n += 1` — see [BuiltinNodes.ASSIGN_OP].
     *
     * The operator's own text, taken from the same table the printer prints binaries with, so the two
     * cannot drift into disagreeing about what `+` is called.
     */
    private fun markCompound(set: Int, op: BinaryOp?) {
        nodeOf(set).literals[BuiltinNodes.ASSIGN_OP] = BINARY[op ?: return] ?: return
    }

    /**
     * `if`, and the one place `&&` is **control flow rather than a value**.
     *
     * `if a && b { T } else { E }` lowers to two **chained Branches** — `a`'s True reaching `b`, both Falses
     * reaching `E` — not to a Select feeding one Branch. That is what a short-circuit *is*: `b` is evaluated
     * only when `a` holds, which a chain of branches says exactly and a Select says only by convention.
     *
     * It is also what makes the round trip exact. A canvas graph in this shape used to print as nested `if`s
     * with the whole tail written once per path (`kill-collect`: 70 nodes → 141, 205 instructions → 304);
     * `Print` now collapses it back to `a && b`, and this is the other half — without it the text read back
     * as Select-plus-Branch and 125 of 205 instructions differed.
     *
     * The cost, stated plainly: a Select wired *directly* into a Branch's condition on the canvas prints as
     * `if a && b` too, and comes back as chained branches. Behaviour is identical, the graph is not. No real
     * script does that, and the chained form is the more faithful reading of the text, so this is the better
     * way round — but it is a real asymmetry, not a free win. `&&` anywhere else is still a Select, because
     * anywhere else it genuinely is a value.
     */
    /**
     * The name a null test proves something about, and which way round — `x != null` gives `x to true`.
     *
     * Only a bare NAME that is already bound, and both of those matter. A graph variable is excluded
     * because its slot can be written from anywhere, including by something the guarded arm itself calls,
     * so a name proved here would not stay proved. An expression is excluded because there is nothing to
     * rebind: `if f() != null` has no name, which is what `if val` is for.
     */
    private fun nullTestOn(e: Expr): Pair<String, Boolean>? {
        val c = e as? BinaryExpr ?: return null
        val proved = when (c.op) {
            BinaryOp.NE -> true
            BinaryOp.EQ -> false
            else -> return null
        }
        val name = (c.left as? NameExpr)?.takeIf { it.module == null } ?: return null
        if ((c.right as? LiteralExpr)?.kind != LiteralKind.NULL) return null
        if (lookup(name.name) == null) return null
        return name.name to proved
    }

    /**
     * Every name [e] proves is NOT null when it comes out [wanted].
     *
     * The two directions are one recursion because `&&` and `||` are duals and `!` swaps them, and writing
     * either half alone is how `if x == null || …` came to mean nothing on its right-hand side while
     * `if x != null && …` narrowed (GAPS 20). The reasoning is the same both ways round:
     *
     * - `a && b` is true only when BOTH were, so a true `&&` proves everything either side proves true.
     * - `a || b` is false only when BOTH were, so a false `||` proves everything either side proves false.
     * - the mixed cases prove nothing: a true `||` says one of them held and not which.
     *
     * Order is preserved (a list, not a set) because the narrowings are applied left to right and a later
     * test in the same chain is lowered against the earlier ones — `x != null && x.next != null` has to see
     * the first before it lowers the second.
     */
    private fun provenNonNull(e: Expr, wanted: Boolean): List<Pair<String, Span>> = when {
        e is NotExpr -> provenNonNull(e.operand, !wanted)
        e is BinaryExpr && e.op == BinaryOp.AND_THEN && wanted ->
            provenNonNull(e.left, true) + provenNonNull(e.right, true)
        e is BinaryExpr && e.op == BinaryOp.OR_ELSE && !wanted ->
            provenNonNull(e.left, false) + provenNonNull(e.right, false)
        else -> nullTestOn(e)?.takeIf { it.second == wanted }?.let { listOf(it.first to e.span) }.orEmpty()
    }

    /**
     * Rebind [name] to a narrowed view of whatever it is bound to, for the current scope.
     *
     * The caller has already pushed a scope, so this lasts exactly as long as the arm the test proved.
     * Nothing is emitted at run time — see [BuiltinNodes.NARROW] — so the cost of this is one node the
     * compiler walks through and the printer prints as its input.
     */
    private fun narrow(name: String, span: Span) {
        val src = lookup(name) ?: return
        val id = node(BuiltinNodes.NARROW, span)
        wire(src, id, "Value", span)
        bind(name, FromPin(id, BuiltinNodes.NARROW_OUT))
    }

    private fun ifStmt(s: IfStmt, chain: Chain) {
        val conds = ArrayList<Expr>()
        fun flatten(e: Expr) {
            if (e is BinaryExpr && e.op == BinaryOp.AND_THEN) { flatten(e.left); flatten(e.right) } else conds += e
        }
        flatten(s.condition)

        val falseEnds = ArrayList<End>()
        var reached: List<End>? = null   // null on the first, which joins the caller's chain instead
        var last = -1
        var outermost = -1
        // **A null test narrows for everything after it.** Scoped for the whole `&&` chain and the `then`
        // block together, because that is exactly the region a `False` on this Branch does not reach —
        // which is what makes `if x != null && x.count > 3` read the way anyone would expect it to.
        // `== null` proves nothing on this side, so it narrows in the `else` instead, below.
        pushScope()
        val provedInElse = ArrayList<Pair<String, Span>>()
        val provedInThen = ArrayList<Pair<String, Span>>()
        for ((i, c) in conds.withIndex()) {
            val branch = node(BuiltinNodes.BRANCH, s.span, if (i == 0) s.ann else Annotations.NONE)
            if (i == 0) outermost = branch
            // The condition is lowered onto the chain that REACHES this branch, so an impure test in `b`
            // runs only when `a` held — the whole point of a short circuit.
            val into = reached?.let { Chain(it) } ?: chain
            wire(expr(c, into), branch, "Condition", c.span)
            into.place(branch, outPin = null)
            falseEnds += End(branch, "False")
            reached = listOf(End(branch, "True"))
            last = branch
            provenNonNull(c, true).let { proved ->
                provedInThen += proved
                proved.forEach { (name, sp) -> narrow(name, sp) }
            }
            provedInElse += provenNonNull(c, false)
        }

        // On the OUTERMOST branch, which is the one the printer starts from — an `a && b` chain is several
        // Branch nodes and only the first bears the statement's own spelling.
        val bare = ArrayList<String>()
        if (!s.then.braced) bare += "then"
        (s.elseBranch as? ExprBlockStmt)?.let { if (!it.block.braced) bare += "else" }
        if (outermost >= 0) BuiltinNodes.bareOf(bare)?.let { nodeOf(outermost).literals[BuiltinNodes.BARE] = it }

        pushScope()
        val trueEnd = stmts(s.then.stmts, Chain(reached ?: listOf(End(last, "True"))))
        popScope()
        // The chain's own scope, which carried the narrowing across the `&&` chain and the `then` block.
        popScope()

        val falseEnd = when (val e = s.elseBranch) {
            null -> falseEnds
            else -> {
                val c = Chain(falseEnds)
                pushScope()
                // `if x == null { … } else { … }` proves it on THIS side. Only when the whole condition
                // was that one test: with an `&&` chain the else is reached when ANY of them failed, so
                // nothing is proved there.
                if (conds.size == 1) provedInElse.forEach { (n, sp) -> narrow(n, sp) }
                stmt(e, c)
                popScope()
                c.open
            }
        }
        // **A guard clause narrows what comes AFTER it.** An arm that cannot fall through is an arm the
        // code below is never reached from, so whatever the OTHER arm proved is proved down there — which
        // is the reading every language gives `if t == null { return }`, and the one this did not have
        // (GAPS 20). Without it an early return could not be used with an optional at all: every one
        // pushed its body a level deeper or into a second function.
        //
        // The `&&` chain is why the two sides are not symmetric. Falling out of the `then` means every
        // condition held, so all of them are proved; reaching the code below past a `then` that returned
        // means the branch went False, and with several branches that says only that ONE of them did.
        if (trueEnd.isEmpty() && falseEnd.isNotEmpty() && conds.size == 1) {
            provedInElse.forEach { (n, sp) -> narrow(n, sp) }
        } else if (falseEnd.isEmpty() && trueEnd.isNotEmpty()) {
            provedInThen.forEach { (n, sp) -> narrow(n, sp) }
        }
        // Both arms dangle, and they converge by wiring into whatever comes next — there is no join node.
        chain.open = trueEnd + falseEnd
    }

    /**
     * `if val t = nearest() { … } else { … }`
     *
     * Shorter than [ifStmt] because there is no `&&` chain to flatten: an `if val` binds one thing and a
     * second test would have nowhere to put its own binding. The Some arm gets a scope with the name bound
     * to the node's `Value` PIN — not to a Hold — which is what makes the binding cost nothing and what
     * makes its type come out of [dev.ziggle.vscript.model.effectivePinType] rather than out of a literal.
     */
    private fun ifLetStmt(s: IfLetStmt, chain: Chain) {
        val id = node(BuiltinNodes.IF_SOME, s.span, s.ann)
        val bare = ArrayList<String>()
        if (!s.then.braced) bare += "then"
        (s.elseBranch as? ExprBlockStmt)?.let { if (!it.block.braced) bare += "else" }
        BuiltinNodes.bareOf(bare)?.let { nodeOf(id).literals[BuiltinNodes.BARE] = it }
        // The name the binding was written with, so the printer gives back what was typed. Nothing reads it
        // at run time — the value is a pin, and a pin is a register.
        nodeOf(id).literals[BuiltinNodes.HOLD_NAME] = s.name

        wire(expr(s.value, chain), id, BuiltinNodes.IF_SOME_OPTION, s.value.span)
        chain.place(id, outPin = null)

        pushScope()
        bindDeclared(s.name, FromPin(id, BuiltinNodes.IF_SOME_VALUE), s.span)
        val someEnd = stmts(s.then.stmts, Chain(listOf(End(id, BuiltinNodes.IF_SOME_THEN))))
        popScope()

        val noneEnds = listOf(End(id, BuiltinNodes.IF_SOME_ELSE))
        val noneEnd = when (val e = s.elseBranch) {
            null -> noneEnds
            else -> {
                val c = Chain(noneEnds)
                pushScope(); stmt(e, c); popScope()
                c.open
            }
        }
        chain.open = someEnd + noneEnd
    }

    /**
     * `try { … } catch e { … }`.
     *
     * Shaped exactly like [ifLetStmt] and for the same reasons: one node, two exec branches, and the caught
     * message bound to the node's own PIN rather than to a Hold — so the binding costs nothing and its type
     * comes from the pin. The two arms' open ends join, as an `if`'s do, because both continue.
     *
     * Nothing here says anything about WHICH errors are caught, because there is no such question: a
     * handler catches everything raised under it and the message is the whole of what it gets.
     */
    private fun tryStmt(s: TryStmt, chain: Chain) {
        val id = node(BuiltinNodes.TRY, s.span, s.ann)
        // The name that was typed, so the printer gives it back. Nothing reads it at run time.
        nodeOf(id).literals[BuiltinNodes.HOLD_NAME] = s.error
        chain.place(id, outPin = null)

        pushScope()
        val bodyEnd = stmts(s.body.stmts, Chain(listOf(End(id, BuiltinNodes.TRY_BODY))))
        popScope()

        pushScope()
        bindDeclared(s.error, FromPin(id, BuiltinNodes.TRY_ERROR), s.span)
        val catchEnd = stmts(s.catch.stmts, Chain(listOf(End(id, BuiltinNodes.TRY_CATCH))))
        popScope()

        chain.open = bodyEnd + catchEnd
    }

    private fun whileStmt(s: WhileStmt, chain: Chain) {
        val loop = node(BuiltinNodes.WHILE, s.span, s.ann)
        if (!s.body.braced) nodeOf(loop).literals[BuiltinNodes.BARE] = "body"
        // The loop joins the chain FIRST, so the condition's own steps can hang off its Check pin instead
        // of off whatever came before it.
        chain.place(loop, outPin = null)
        // **The condition is lowered into Check, not into the enclosing chain.** A condition holding an
        // impure call — `while overTen(i) == 0`, or anything block-bodied — used to place that call before
        // the loop, where it ran exactly once; every iteration then re-read the register it left behind
        // and the loop either spun forever or never started. The printer said so plainly, rewriting the
        // source as `let result = overTen(i)` above a `while result == 0`, which is a different program
        // from the one that was written. On Check the steps re-run before each test, which is what the
        // text says. A pure condition places nothing here and is unaffected.
        val cond = expr(s.condition, Chain(listOf(End(loop, "Check"))))
        wire(cond, loop, "Condition", s.condition.span)

        pushScope()
        // The body's dangling end is LEFT dangling. The compiler closes the loop itself — falling out of
        // the bottom of the body with nowhere to go emits a jump back to the top — which is also how a loop
        // reads on the canvas: you wire the Body pin, not a wire back. See [forStmt] for why writing one by
        // hand is actively wrong rather than merely redundant.
        stmts(s.body.stmts, Chain(listOf(End(loop, "Body"))))
        popScope()
        chain.open = listOf(End(loop, "Completed"))
    }

    private fun forStmt(s: ForStmt, chain: Chain) {
        // The source decides which loop this is. `for (a, b) in xs` over a LIST binds element and index;
        // over a MAP it binds key and value — one spelling, because "the two things each pass gives you" is
        // the same idea and the container says what they are. Anything that is not a map takes the list
        // loop and reports its own mismatch at the wire, exactly as before.
        val src = expr(s.list, chain)
        if (s.index != null && typeOfSrc(src)?.isMap == true) {
            mapForStmt(s, src, chain)
            return
        }
        val loop = node(BuiltinNodes.FOR_EACH, s.span, s.ann)
        if (!s.body.braced) nodeOf(loop).literals[BuiltinNodes.BARE] = "body"
        val list = src
        wire(list, loop, "List", s.list.span)
        chain.place(loop, outPin = null)

        pushScope()
        bindDeclared(s.element, FromPin(loop, "Element"), s.span)
        s.index?.let { bindDeclared(it, FromPin(loop, "Index"), s.span) }
        // The names the author wrote, kept on the node — the same trick `const` plays with [CONST_NAME], and
        // for the same reason: a name that exists only in the text cannot survive a trip through the graph.
        // Without this the printer had to invent one from the node id, which a round trip reallocates, so
        // `for (x8, i8)` came back as `for (x21, i21)` and printing was not a fixed point. Not a pin: a
        // variable's name is metadata, nothing reads it at runtime, and giving `ForEach` two config pins
        // would put clutter on the canvas to solve a problem the canvas does not have.
        nodeOf(loop).literals[LOOP_ELEMENT] = s.element
        s.index?.let { nodeOf(loop).literals[LOOP_INDEX] = it }
        // No back edge, and here it matters. A While's node label and its loop top are the same address, so
        // an explicit back wire merely duplicated what the compiler emits; a ForEach's label sits BEFORE its
        // `ITER` setup, so the same wire jumped there and rebuilt the iterator on every pass — a loop that
        // ran forever handing out the first element.
        stmts(s.body.stmts, Chain(listOf(End(loop, "Body"))))
        popScope()
        chain.open = listOf(End(loop, "Completed"))
    }

    /** `for (k, v) in m` — the map loop, chosen by [forStmt] when the source really is one. */
    private fun mapForStmt(s: ForStmt, src: Src, chain: Chain) {
        val loop = node(BuiltinNodes.MAP_FOR_EACH, s.span, s.ann)
        if (!s.body.braced) nodeOf(loop).literals[BuiltinNodes.BARE] = "body"
        wire(src, loop, BuiltinNodes.MAP_PIN, s.list.span)
        chain.place(loop, outPin = null)

        pushScope()
        bindDeclared(s.element, FromPin(loop, BuiltinNodes.MAP_KEY_PIN), s.span)
        s.index?.let { bindDeclared(it, FromPin(loop, BuiltinNodes.MAP_VALUE_PIN), s.span) }
        // The written names, for the same reason the list loop keeps its own — see [forStmt].
        nodeOf(loop).literals[LOOP_ELEMENT] = s.element
        s.index?.let { nodeOf(loop).literals[LOOP_INDEX] = it }
        stmts(s.body.stmts, Chain(listOf(End(loop, "Body"))))
        popScope()
        chain.open = listOf(End(loop, "Completed"))
    }

    /** What a lowered expression carries, when it comes off a pin and the pin says. */
    private fun typeOfSrc(src: Src): TypeRef? {
        val from = src as? FromPin ?: return null
        val pin = resolved(from.node).output(from.pin) ?: return null
        return effectivePinType(nodeOf(from.node), pin, ::declaredVarType) { n, p -> feeding(n, p) }
    }

    /**
     * `when` — one node, an arm per case.
     *
     * Shaped like a Sequence's lowering and for the same reason: the arms are independent chains hanging off
     * the node's exec outputs, and where they end is where the statement's continuation picks up. The
     * difference is that only ONE of them runs, so every arm's open end is a place the code after the `when`
     * can be reached from — including the node's own `Else` when no `else` was written, since falling through
     * is what happens then.
     *
     * The case values are wired BEFORE the arms are lowered, so a case reading a variable sees the value the
     * `when` was entered with rather than whatever an earlier arm assigned to it.
     */
    private fun whenStmt(s: WhenStmt, chain: Chain) {
        val id = node(BuiltinNodes.WHEN, s.span, s.ann)
        nodeOf(id).literals[BuiltinNodes.WHEN_COUNT] = s.arms.size
        if (s.elseArm != null) nodeOf(id).literals[BuiltinNodes.WHEN_HAS_ELSE] = true

        // Lowered onto the chain that REACHES the node, and BEFORE it is placed. An impure expression
        // becomes a step, and a step created after the `when` was placed would sit later in the exec chain
        // than the node that reads it — "nothing runs 'Call'", for a subject written perfectly reasonably.
        //
        // So an impure case is evaluated eagerly, before any arm is tested. That is the same bargain `&&`
        // and `||` already make with impure operands, and the only one available: a value a node reads has
        // to be produced by a node that already ran.
        val subject = s.subject?.let { expr(it, chain) to it.span }
        val cases = s.arms.map { expr(it.value, chain) to it.value.span }
        chain.place(id, outPin = null)
        subject?.let { (src, span) -> wire(src, id, BuiltinNodes.WHEN_SUBJECT, span) }
        cases.forEachIndexed { i, (src, span) -> wire(src, id, BuiltinNodes.whenCase(i + 1), span) }

        // Which arms were written without braces — see [BuiltinNodes.BARE].
        val bare = ArrayList<String>()
        s.arms.forEachIndexed { i, arm -> if (!arm.body.braced) bare += (i + 1).toString() }
        if (s.elseArm?.braced == false) bare += "else"
        BuiltinNodes.bareOf(bare)?.let { nodeOf(id).literals[BuiltinNodes.BARE] = it }

        val ends = ArrayList<End>()
        s.arms.forEachIndexed { i, arm ->
            pushScope()
            ends += stmts(arm.body.stmts, Chain(listOf(End(id, BuiltinNodes.whenThen(i + 1)))))
            popScope()
        }
        if (s.elseArm != null) {
            pushScope()
            ends += stmts(s.elseArm.stmts, Chain(listOf(End(id, BuiltinNodes.WHEN_ELSE))))
            popScope()
        } else {
            // No `else`: nothing matching falls straight through to whatever follows, so the Else pin IS an
            // open end. Leaving it out would strand the statements after the `when` on the matched arms only.
            ends += End(id, BuiltinNodes.WHEN_ELSE)
        }
        chain.open = ends
    }

    /**
     * `sequence { … } { … }` — the arms in order, then on.
     *
     * **The continuation belongs to the NODE, not to the arms.** Collecting the arms' open ends and handing
     * those back is the shape a `when` uses, and it is wrong here for the reason the two constructs differ:
     * a `when` takes ONE arm, so every arm's end is a place the following statement is reached from, while a
     * `sequence` takes all of them and the following statement runs once. Wired the `when` way, the next
     * statement was duplicated into every arm — and since the compiler lays the arms out consecutively, the
     * second copy resolved to a backward jump and the script ran forever.
     *
     * So the open end is the node's own [BuiltinNodes.SEQUENCE_DONE], exactly as a loop's is.
     */
    private fun sequenceStmt(s: SequenceStmt, chain: Chain) {
        val seq = node(BuiltinNodes.SEQUENCE, s.span, s.ann)
        chain.place(seq, outPin = null)
        s.arms.forEachIndexed { i, arm ->
            pushScope()
            stmts(arm.stmts, Chain(listOf(End(seq, BuiltinNodes.SEQUENCE_ARMS[i]))))
            popScope()
        }
        chain.open = listOf(End(seq, BuiltinNodes.SEQUENCE_DONE))
    }

    /**
     * `return`.
     *
     * Inside a function this is the box: control arriving back at it *is* the return, and its input pins are
     * the results. At top level it is `flow.return`, which ends the fiber.
     */
    private fun returnStmt(s: ReturnStmt, chain: Chain) {
        // A Return NODE, not a wire to the box — inside a body its pins are the function's results, so each
        // return hands back its own values. Wiring to the box makes every path share one expression, since
        // the box has one input per result; two literal returns simply overwrote each other and the function
        // returned the same thing everywhere. The box still serves the implicit return, for a body that
        // reaches the end without saying so.
        val ret = node(BuiltinNodes.RETURN, s.span, s.ann)
        val results = currentFn?.let { name -> functions.first { it.name == name }.results }
        if (results != null) {
            if (s.values.size > results.size) {
                err(s.span, "'$currentFn' returns ${results.size} value(s), but ${s.values.size} were given")
            }
            s.values.forEachIndexed { i, e ->
                // The declared RESULT is a destination, so `return { it > 0 }` reads as one — and with it
                // every computed field default, which is lowered as a synthesised `return <default>`.
                results.getOrNull(i)?.let {
                    val want = Expected(it.type, it.name, currentFn ?: "this function")
                    wire(expr(e, chain, want), ret, it.name, e.span)
                }
            }
        } else {
            if (s.values.size > 1) err(s.span, "only a function returns several values")
            s.values.firstOrNull()?.let { wire(expr(it, chain), ret, "Value", it.span) }
        }
        // A bare `return` in a mutating extension still hands the receiver back — the author wrote no
        // value because there is none to write, and the result pin is implicit for exactly that reason.
        selfHold?.takeIf { s.values.isEmpty() }?.let {
            wire(FromPin(it, "Value"), ret, GraphFunction.SELF_RESULT, s.span)
        }
        chain.place(ret, outPin = null)
    }

    /**
     * `xs.add(3)` — a mutating extension called in STATEMENT position, which means `xs = xs.add(value: 3)`.
     *
     * **Nothing is mutated**, and it could not be. `Op.CALL` copies the argument window into the callee's
     * frame and a list value is an `ArrayList` reference, so a callee genuinely COULD write through to the
     * caller's list — and that is precisely the aliasing `AppendPass` exists to refuse. Making it a
     * language feature would mean `var b = a; b.add(5)` grows `a`, and "a list is a value" would stop being
     * true, which is what `MAP`'s semantics and the whole in-place-append proof rest on.
     *
     * So the mechanism is a write-back at the CALL SITE, and the graph is identical to the one the long
     * spelling makes — only [BuiltinNodes.WROTE_RECEIVER] says which was typed.
     *
     * Returns false when this is not one, so the ordinary statement path runs unchanged.
     */
    private fun mutatingCall(s: ExprStmt, e: CallExpr, chain: Chain): Boolean {
        // The receiver has to be a bare NAME, because a write-back needs somewhere to write. Anything else
        // — `found()[0].add(5)`, `a.b.add(5)` — is reported below rather than silently discarded.
        val target = when {
            e.receiver is NameExpr -> (e.receiver as NameExpr).name
            e.receiver == null && e.target.size == 2 -> e.target.first()
            else -> null
        } ?: return false
        val method = e.target.last()
        // Only a name that could BE a place. A node type called `x.y` reaches here too, and it is not one.
        val local = lookup(target)
        val isLocal = local is FromPin && nodeOf(local.node).literals[BuiltinNodes.MUTABLE] == true
        val isVar = variables.any { it.name == target }
        val letBound = local != null && !isLocal
        if (!isLocal && !isVar && !letBound) return false
        // Is anything called `method` a mutating extension? Asked across the visible set, since the choice
        // between overloads is made by the receiver and this only needs to know which SHAPE to lower.
        val visible = functions.filter { it.name == method } +
            bindingImports().mapNotNull { (_, doc) ->
                exportsIn(doc)[method]?.let { e -> e.owner.function(e.name) }
            }
        if (visible.none { dev.ziggle.vscript.model.isMutating(it) }) return false
        if (letBound) {
            err(
                s.span,
                "'$target' is a `let`, so '$method' has nowhere to write back — a mutating extension " +
                    "means '$target = $target.$method(…)'. Write 'var $target = …' if it has to change",
            )
            return true
        }
        val src = call(e, chain, s.ann)
        if (isLocal) {
            val hold = (local as FromPin).node
            val set = node(BuiltinNodes.LOCAL_SET, s.span)
            nodeOf(set).variable = target
            nodeOf(set).literals[BuiltinNodes.LOCAL_TARGET] = hold
            (nodeOf(hold).literals[BuiltinNodes.HOLD_TYPE] as? String)
                ?.let { nodeOf(set).literals[BuiltinNodes.HOLD_TYPE] = it }
            nodeOf(set).literals[BuiltinNodes.WROTE_RECEIVER] = true
            wire(src, set, "Value", s.span)
            chain.place(set)
        } else {
            val set = node(BuiltinNodes.VAR_SET, s.span)
            nodeOf(set).variable = target
            nodeOf(set).literals[BuiltinNodes.WROTE_RECEIVER] = true
            wire(src, set, "Value", s.span)
            chain.place(set)
        }
        return true
    }

    /**
     * `error("…")` **on its own line** — the step form, which is a different node from the pure one.
     *
     * The pure [BuiltinNodes.FAIL] is what lets `x ?: error(…)` be an expression, and a pure node in
     * statement position computes a value nobody reads and never runs. So position decides which node is
     * made, and only here, where position is known. A reader never learns there are two.
     *
     * **Only when nothing else claims the name.** A document may declare its own `fn error(…)`, and the
     * ordinary resolution order — a local binding, then this document's functions, then the catalogue —
     * must go on holding. Answers false when it is not this, so the caller falls through unchanged.
     */
    private fun failStmt(s: ExprStmt, e: CallExpr, chain: Chain): Boolean {
        if (e.receiver != null || e.target.size != 1 || e.name != names.textName(BuiltinNodes.FAIL)) return false
        if (functions.any { it.name == e.name } || e.name in unqualified || lookup(e.name) != null) return false
        val id = node(BuiltinNodes.FAIL_STEP, s.span, s.ann)
        wireArgs(id, resolved(id), e, chain)
        // No Exec out — nothing after it runs, exactly as a `return` does not run what follows it.
        chain.place(id, outPin = null)
        return true
    }

    private fun exprStmt(s: ExprStmt, chain: Chain) {
        val callExpr = s.expr as? CallExpr ?: run { err(s.span, "a statement has to be a call"); return }
        if (mutatingCall(s, callExpr, chain)) return
        if (failStmt(s, callExpr, chain)) return
        val before = chain.open
        // Straight to `call` rather than through `expr`, so the statement's annotations reach the node it
        // makes. They cannot be applied afterwards: `@id` decides the id at the moment of creation.
        val src = call(callExpr, chain, s.ann)
        // A pure call as a statement computes something and throws it away — it never joins the chain, so
        // it would simply not run. Detected by the chain not having moved.
        if (chain.open === before && src is FromPin) {
            if (inBareRun) {
                err(
                    s.span,
                    "'$currentFn' hands back what '${callExpr.name}' works out, but says no result type " +
                        "— add '-> Type'",
                )
                return
            }
            err(s.span, "'${callExpr.name}' works something out but nothing is done with it, so it will not run")
        }
    }

    // ---- expressions -------------------------------------------------------------------------------------

    private fun expr(e: Expr, chain: Chain, expect: Expected? = null): Src = when (e) {
        is LiteralExpr -> Inline(e.value)
        is NameExpr -> name(e, chain)
        is CallExpr -> call(e, chain)
        is BinaryExpr -> binary(e, chain)
        is NotExpr -> unary(e, chain)
        is TernaryExpr -> ternary(e, chain)
        is ElvisExpr -> elvis(e, chain)
        is SafeAccessExpr -> safeAccess(e, chain)
        is SafeItExpr -> safeIt ?: run {
            err(e.span, "'?.' has no receiver here (the parser should have caught this)")
            FromPin(0, "Value")
        }
        is MemberExpr -> member(e, chain)
        is IndexExpr -> index(e, chain)
        is StructLitExpr -> structLit(e, chain)
        is WithExpr -> withExpr(e, chain)
        is IsExpr -> isExpr(e, chain)
        is AsExpr -> asExpr(e, chain)
        is ListLitExpr -> listLit(e, chain)
        // A lambda takes its parameter types from its DESTINATION, so it needs one to be anything at all.
        // Wherever the destination is known it is passed down as [Expected]; where it is not, there is
        // genuinely nothing to read them from and that is what [lambdaValue] reports.
        is LambdaExpr -> lambdaValue(e, expect)
    }

    /**
     * A bare name: a binding in scope, then a graph variable, then a call that takes nothing.
     *
     * Takes the chain because the last of those may be IMPURE — `openBank` written without parens is still
     * a step, and building it without placing it would leave a node nothing ever runs.
     */
    private fun name(e: NameExpr, chain: Chain): Src {
        // Qualified: none of the local resolution order applies, which is the point of writing the alias.
        // A bare qualified name is a VARIABLE — an imported nullary FUNCTION must be written with its
        // parens. The rule is a rule rather than a lookup because the answer lives in another document
        // and would otherwise make `banking::x` mean different things depending on what resolved.
        // A bare name an unqualified import brought in resolves exactly as the qualified spelling would —
        // the table says which document and what it is called there, and from here it IS that name.
        // Local bindings and this document's own declarations were tried above and win, which is why
        // reaching this line means the name is genuinely the import's.
        val imported = if (e.module == null) unqualified[e.name] else null
        (e.module ?: imported?.alias)?.let { alias ->
            val realName = imported?.name ?: e.name
            if (imports.none { it.alias == alias }) {
                err(e.span, "nothing is imported as '$alias'")
                return Inline(null)
            }
            // A CONST first, then a variable. The rule this refines used to be flat — "a bare qualified
            // name is a variable" — and it was a rule rather than a lookup so that `banking::x` could not
            // mean different things depending on what happened to resolve. That reasoning was about
            // telling a variable from a nullary FUNCTION, which genuinely cannot be decided from here; a
            // const is neither, and it cannot collide with a variable of the same name in a way that
            // changes what the line does, because both name one value.
            importedConstRef(alias, realName, e)?.let { return it }
            // ...and then a FUNCTION, which is what `register(1, lib::run)` means.
            //
            // **Only when the document has no variable of that name**, so nothing that resolved before
            // changes meaning — the rule above stays exactly as it was and this is a fallback for what
            // used to be an error. The original reasoning holds for a nullary FUNCTION, which genuinely
            // cannot be told from a variable here and still has to be written `lib::run()`; a bare name
            // with no variable behind it had no meaning at all, and "the function of that name" is the
            // only thing it could sensibly be. Without this a dispatch table could be BUILT in an enum
            // column — which lowers by another path — and not passed to anything.
            val doc = importedDocs[alias]
            if (doc != null && doc.variable(realName) == null) {
                val offered = exportsIn(doc)[realName]
                if (offered?.owner?.function(offered.name)?.isExtension == false) {
                    val id = node(BuiltinNodes.FUNCTION_REF, e.span)
                    nodeOf(id).literals[BuiltinNodes.FUNCTION_REF_NAME] = "$alias${QualName.SEP}$realName"
                    return FromPin(id, "Value")
                }
            }
            val get = node(BuiltinNodes.VAR_GET, e.span)
            nodeOf(get).variable = "$alias${QualName.SEP}$realName"
            return FromPin(get, "Value")
        }
        lookup(e.name)?.let { return it }
        if (variables.any { it.name == e.name }) {
            val get = node(BuiltinNodes.VAR_GET, e.span)
            nodeOf(get).variable = e.name
            return FromPin(get, "Value")
        }
        names.resolveType(e.name)?.let { d ->
            if (d.dataInputs.isEmpty()) return call(CallExpr(listOf(e.name), emptyList(), e.span), chain)
        }
        // **The migration guard for explicit `self`.** A body mentioning `self` that its signature does not
        // declare used to be every extension — the receiver was synthesised — and is now a function on the
        // TYPE, which compiles and means something else entirely. The detector cannot false-positive: a
        // genuine type-level function has no `self` to mention.
        if (e.name == GraphFunction.SELF) {
            val fn = currentFn?.let { name -> functions.firstOrNull { it.name == name } }
            err(
                e.span,
                if (fn?.isExtension == true) {
                    "'${fn.name}' uses 'self' but does not declare it. An extension on a VALUE takes " +
                        "'self' as its first parameter: 'fn ${fn.receiver}.${fn.name}(self" +
                        (if (fn.params.isEmpty()) "" else ", …") + ")'"
                } else {
                    "'self' is the receiver of an extension, and this function extends nothing — " +
                        "write 'fn <Type>.${currentFn ?: "name"}(self, …)' if that is what you meant"
                },
            )
            return Inline(null)
        }
        // A bare name that names a FUNCTION is a reference to it — `filtered(list: spots, keeping: near)`.
        //
        // LAST, after every binding kind, so a `let` called `near` still wins. That is the ordinary
        // shadowing rule and it has to be this way round: a function value has no spelling of its own to
        // disambiguate with, so the only stable promise is "a name means the nearest thing that has it".
        if (declSpans.containsKey("fn:${e.name}")) {
            // **A body of steps is no longer a refusal here**, and that is the whole of `act`. It used to
            // be one on the grounds that a function value is called from inside an expression — which was
            // never a fact about the value, only about the three PURE nodes that were the only things able
            // to call one. The kind now rides in the type, so the refusal happens at the WIRE, where the
            // destination is known: `canConnect` stops an action reaching `keeping:`, and Invoke exists to
            // be the destination that accepts one.
            val id = node(BuiltinNodes.FUNCTION_REF, e.span)
            nodeOf(id).literals[BuiltinNodes.FUNCTION_REF_NAME] = e.name
            return FromPin(id, "Value")
        }
        err(e.span, "nothing here is called '${e.name}'")
        return Inline(null)
    }

    private fun call(e: CallExpr, chain: Chain, ann: Annotations = Annotations.NONE): Src {
        // `tile(x, y, p)` is a literal spelling, not a call — the one reserved word.
        if (e.receiver == null && e.name == "tile") return tileLiteral(e)

        // `21.double()` — a receiver the parser could not fold into a dotted name. Only an extension can be
        // written this way, so there is nothing else to fall back to.
        if (e.receiver != null) {
            // **The receiver, once, before either spelling is tried.** Both need its type to know whether
            // they apply, and both would otherwise lower it themselves — which for a receiver that ACTS
            // would run it twice.
            val self = expr(e.receiver, chain)
            // **A FIELD wins over an extension, and that is the same order a bare head already uses** —
            // `invokeSugar` runs ahead of all name resolution there. It is the right way round for a
            // reason that survives the tie-break being arbitrary: an extension's name can be changed at
            // the import (`import { at as elsewhere }`), and a record's field name cannot be aliased at
            // all. So the field is the name with no way out, and the extension is the one that can move.
            receiverFieldInvoke(e, self, chain, ann)?.let { return it }
            extensionCall(e, chain, ann, self)?.let { return it }
            err(e.span, "nothing extends this with '${e.target.last()}'")
            return Inline(null)
        }

        // `Phase.values()` — the one builtin spelled as a call on a TYPE.
        enumValuesCall(e)?.let { return it }

        // `f(rumor)` where `f` HOLDS a function — dispatch, written the way every other call is written.
        //
        // Tried before any name resolution, and safe to be, because it demands the binding's TYPE be a
        // function: a local `f` holding an INT still leaves `f(…)` meaning the function called `f`. So the
        // only names this can take over are ones where something in scope holds a function AND shares a
        // name with a function or a node — where "the nearest binding wins" is the rule already.
        invokeSugar(e, chain, ann)?.let { return it }

        // An unqualified imported name IS a qualified call — the table says which document and what that
        // document calls it, and from here on nothing knows the difference. `Print` takes the alias back
        // off, which is what makes the round trip work without any other stage learning about `import *`.
        //
        // After the local lookups above and before the node lookup below is not a choice about precedence:
        // a name that reached the table collided with neither, because both are reported at the import.
        val imported = if (e.module == null) unqualified[e.name] else null
        (e.module ?: imported?.alias)?.let { alias ->
            val calleeName = imported?.name ?: e.name
            if (imports.none { it.alias == alias }) {
                err(e.span, "nothing is imported as '$alias'")
                return Inline(null)
            }
            val id = node(BuiltinNodes.CALL, e.span, ann)
            nodeOf(id).callee = "$alias${QualName.SEP}$calleeName"
            val d = resolved(id)
            wireArgs(id, d, e, chain)
            if (d.kind == NodeKind.IMPURE || d.kind == NodeKind.ENTRY) chain.place(id)
            val out = d.dataOutputs.firstOrNull()
            return if (out == null) Inline(null) else FromPin(id, out.name)
        }

        val userFn = functions.firstOrNull { it.name == e.name }
        // An extension is not callable by its bare name, so it is not a candidate here — and crucially it
        // does not SHADOW one either. Declaring `fn INT.toText()` must leave the catalogue's `toText` node
        // reachable; the extension has its own spelling and has taken nothing away.
        val callable = userFn?.takeIf { !it.isExtension }
        val desc = if (callable != null) catalog[BuiltinNodes.CALL]!! else names.resolveType(e.name)
        if (desc == null) {
            // A name that exists but was written the wrong way. Both spellings would otherwise produce the
            // same Call node — the collision §6.7 refuses sugar for — so the printer would have to pick one
            // and would silently rewrite the other.
            if (userFn != null && userFn.isExtension) {
                err(
                    e.span,
                    "'${e.name}' extends ${userFn.receiver} — call it on one: " +
                        "'<${userFn.receiver.toString().lowercase()}>.${e.name}(…)'",
                )
                return Inline(null)
            }
            // `Vec2.new(5, 3)`, then `xs.add(v)`. Both tried only AFTER the dotted name failed as a node
            // type, so `draw.text(…)` keeps meaning the node it has always meant — the dot already
            // separates the parts of a node type, and preserving every existing script matters more than
            // which one wins a hypothetical clash.
            typeLevelCall(e, chain, ann)?.let { return it }
            extensionCall(e, chain, ann)?.let { return it }
            starAmbiguous[e.name]?.let { from ->
                err(
                    e.span,
                    "'${e.name}' is offered by " + from.joinToString(" and ") { "\"$it\"" } +
                        " — say which with a named import: 'import { ${e.name} } from \"${from.first()}\"'",
                )
                return Inline(null)
            }
            err(e.span, "nothing here is called '${e.name}'")
            return Inline(null)
        }
        val id = node(if (callable != null) BuiltinNodes.CALL else desc.type, e.span, ann)
        if (callable != null) nodeOf(id).callee = callable.name

        writeShapePins(id, e)
        val d = resolved(id)
        wireArgs(id, d, e, chain)

        if (d.kind == NodeKind.IMPURE || d.kind == NodeKind.ENTRY) chain.place(id)
        val out = d.dataOutputs.firstOrNull()
        return if (out == null) Inline(null) else FromPin(id, out.name)
    }

    /**
     * `xs.add(v)` — a call to an extension, with everything before the last dot as the receiver.
     *
     * Lowered to the ordinary Call node: `self` is wired from the receiver expression and the rest of the
     * arguments are wired exactly as any other call's. There is no extension machinery below this line,
     * which is the point — the receiver was only ever a parameter written somewhere unusual.
     *
     * Returns null when this is not an extension call, so the caller can report its own error.
     */
    /**
     * `Vec2.new(5, 3)` — a call on a TYPE rather than on a value of one.
     *
     * The declaration is what makes it possible: `fn Vec2.new(x: Int, y: Int)` extends `Vec2` and declares
     * no `self`, so there is no receiver pin to fill and the thing before the dot is a type NAME rather
     * than an expression. Everything else is an ordinary call.
     *
     * **A variable of the same name wins.** `Site.foo()` where `Site` is both a declared type and a graph
     * variable is the instance reading, and that is backward-compatible by construction: a type-level call
     * resolved to nothing before this existed, so there is no meaning to preserve, while the variable
     * reading already worked. The validator warns at the declaration rather than here.
     *
     * Tried AFTER a node type, which is the order dotted names already resolve in — `draw.text(…)` keeps
     * meaning the node it has always meant.
     */
    /**
     * `Phase.values()` — every member of an enum, in declaration order.
     *
     * Ahead of [typeLevelCall] because a user function on the TYPE resolves the same way, and this is the
     * builtin: a document that declares `fn Phase.values()` of its own would otherwise be shadowed by it.
     * A value in scope still wins, which is the rule every other name here follows.
     */
    private fun enumValuesCall(e: CallExpr): Src? {
        if (e.receiver != null) return null
        if (e.target.size != 2) return null
        val (typeName, method) = e.target
        if (method != "values") return null
        if (e.module == null && (lookup(typeName) != null || variables.any { it.name == typeName })) return null
        // A user function on the type beats the builtin, so this yields to one that is declared.
        if (functions.any { it.name == method && it.isTypeLevel && it.receiver?.name.equals(typeName, true) }) {
            return null
        }
        val t = enumNamed(e.module, typeName) ?: return null
        if (e.args.isNotEmpty()) {
            err(e.span, "'${t.name}.values()' takes no arguments")
            return Inline(null)
        }
        val id = node(BuiltinNodes.ENUM_VALUES, e.span)
        nodeOf(id).literals[BuiltinNodes.ENUM_TYPE] = t.name
        return FromPin(id, "Values")
    }

    private fun typeLevelCall(e: CallExpr, chain: Chain, ann: Annotations): Src? {
        if (e.module != null || e.receiver != null) return null
        if (e.target.size != 2) return null
        val (typeName, method) = e.target
        // The value wins — see above.
        if (lookup(typeName) != null || variables.any { it.name == typeName }) return null

        fun matches(f: GraphFunction) =
            f.name == method && f.isTypeLevel && f.receiver?.name.equals(typeName, ignoreCase = true)

        val local = functions.firstOrNull { matches(it) }
        val imported = bindingImports().flatMap { (alias, doc) ->
            exportsIn(doc).mapNotNull { (_, e) ->
                e.owner.function(e.name)?.takeIf { matches(it) }?.let { alias to it }
            }
        }
        if (local == null && imported.size > 1) {
            err(
                e.span,
                "'$typeName.$method' is declared by " + imported.joinToString(" and ") { "'${it.first}'" } +
                    " — say which with an alias",
            )
            return Inline(null)
        }
        val callee = local?.name
            ?: imported.firstOrNull()?.let { "${it.first}${QualName.SEP}$method" }
            ?: return null

        val id = node(BuiltinNodes.CALL, e.span, ann)
        nodeOf(id).callee = callee
        val d = resolved(id)
        // No `self` to wire: that absence IS the feature, so every declared parameter is an argument.
        wireArgs(id, d, e, chain)
        if (d.kind == NodeKind.IMPURE || d.kind == NodeKind.ENTRY) chain.place(id)
        val out = d.dataOutputs.firstOrNull()
        return if (out == null) Inline(null) else FromPin(id, out.name)
    }

    /**
     * [lowered] is the receiver, already turned into a value by the caller.
     *
     * **Passed in so it is lowered ONCE across both spellings a `recv.name(…)` can have.** A field call
     * and an extension call are told apart by what the receiver turns out to be, so whichever is tried
     * first has to lower it — and the other lowering it again would run the receiver twice. See [call]'s
     * receiver branch, which does it there and hands the answer to both.
     */
    private fun extensionCall(
        e: CallExpr,
        chain: Chain,
        ann: Annotations,
        lowered: Src? = null,
    ): Src? {
        if (e.module != null) return null
        if (e.receiver == null && e.target.size < 2) return null
        val method = e.target.last()

        // Every candidate BY NAME first; the receiver picks between them below. It used to be
        // `firstOrNull`, which was the whole resolution rule — one name, one function — and phase E is
        // exactly the observation that a name is not enough: `List<Entity>.closest` and `List<Tile>.closest`
        // are two functions and the thing before the dot says which.
        //
        // `self != null` rather than merely `isExtension`: a type-level function extends a type without
        // taking a receiver, so there is no pin here to wire the thing before the dot into. Those are
        // [typeLevelCall]'s, and one reaching here would silently drop its first argument.
        val locals = functions.filter { it.name == method && it.self != null }
        // Qualified on the way in, so the receiver being COMPARED is spelled in this document's
        // vocabulary: a library's `fn LIST<Point>.f` arrives as `LIST<geo::Point>`, which is what a value
        // of it is typed here. Without that the two names differ and every such call would be refused.
        // **Only what the import NAMED.** An extension is the one name that arrives unqualified — `xs.add(v)`
        // has nowhere to put an alias — and for a while that was taken to mean it needed no naming either:
        // every exported extension of every imported document was in scope, so `rumor.run()` worked with no
        // mention of `run` anywhere. That is the one hole left in "nothing crosses a boundary unless it is
        // exported AND asked for", and importing a document for its TYPES should not silently bring verbs.
        //
        // Matched on the LOCAL spelling, so `{ run as go }` is called `.go()`; the callee below is built
        // from the function's own name, which is what the other document still calls it.
        val imported = imports.mapNotNull { imp ->
            if (!imp.bindsLocally) return@mapNotNull null
            // A star offers every verb the document has, under its own name; a named list offers exactly
            // what it wrote, under the LOCAL spelling.
            val remote = if (imp.star) method else imp.named.firstOrNull { it.local == method }?.name
                ?: return@mapNotNull null
            val doc = importedDocs[imp.alias] ?: return@mapNotNull null
            val e = exportsIn(doc)[remote] ?: return@mapNotNull null
            val alias = canonicalAlias(imp.alias)
            e.owner.function(e.name)
                ?.takeIf { it.self != null }
                ?.let { alias to respellFromOwner(e.owner, dev.ziggle.vscript.model.qualifyThrough(alias, e.owner, it)) }
        }
        if (locals.isEmpty() && imported.isEmpty()) return null

        // Either the parser handed over the receiver — `21.double()` — or it is everything before the last
        // dot of a name it folded together: `xs` for `xs.add(v)`, `a.b` for `a.b.add(v)`.
        val receiver: Expr = e.receiver ?: run {
            val head = e.target.dropLast(1)
            var built: Expr = NameExpr(head[0], e.span)
            for (part in head.drop(1)) built = MemberExpr(built, part, e.span)
            built
        }

        // **Lowered BEFORE the callee is chosen, and exactly once.** Choosing needs the receiver's type,
        // and lowering it is not free of side effects — it places nodes on the exec chain — so asking the
        // question twice would run the receiver twice. Every candidate is judged against this one answer.
        //
        // ...or handed in already done, when the caller had to lower it to decide something else first.
        val self = lowered ?: expr(receiver, chain)
        val selfType = typeOf(self)

        val candidates = locals.map { null to it } + imported
        // A receiver whose type nothing can establish accepts every candidate, which is exactly the
        // behaviour before generics existed — permissive, and never a refusal on no evidence.
        val fitting = candidates.filter { (_, f) ->
            selfType == null || f.receiver == null || canConnect(selfType, f.receiver)
        }
        if (fitting.isEmpty()) {
            val kinds = candidates.mapNotNull { it.second.receiver }.map { "'$it'" }.distinct()
            err(
                e.span,
                "'$method' extends " + kinds.joinToString(" and ") + ", and this is '$selfType'",
            )
            return Inline(null)
        }
        // The most specific receiver wins — `LIST<ENTITY>` over `LIST`, `LIST` over `Any`. That is the
        // entire Level 0 resolution rule, and it is what lets `core/list.vs` and `core/objects.vs` both
        // declare `plus` without either having to be renamed.
        val best = fitting.maxOf { receiverSpecificity(it.second.receiver) }
        val winners = fitting.filter { receiverSpecificity(it.second.receiver) == best }
        // This document's own first. Local wins, which is the rule every other name here follows — and it
        // doubles as the way out of an ambiguity: declare your own.
        //
        // **The one place two documents can genuinely collide.** Everything else an import brings is
        // qualified at the point of use, so there is never a question of which one was meant; `xs.add(v)`
        // has nowhere to put an alias. Refused rather than picked — picking would mean an import somewhere
        // above silently changed what this line does. Narrower than it was: two extensions of DIFFERENT
        // types are no longer a collision at all, because the receiver has already told them apart.
        val chosen = winners.firstOrNull { it.first == null } ?: run {
            if (winners.size > 1) {
                // Named by a spelling the author can TYPE. An extension is usually asked for with
                // `import { … }`, which writes no alias, so the stored one is synthesised (`@2`) — and a
                // message that says "say which: '@2::double(…)'" names a way out that does not exist. The
                // written namespace of the same document is the answer when there is one; when there is
                // not, adding one IS the fix, so the message says that instead.
                val ways = winners.map { (alias, _) -> alias?.let { writableAlias(it) } }
                err(
                    e.span,
                    "'$method' is extended by " + winners.mapIndexed { i, w ->
                        "'${ways[i] ?: importRefOf(w.first)}'"
                    }.joinToString(" and ") +
                        " — say which: " + (
                        ways[0]?.let { "'$it${QualName.SEP}$method(self: …)'" }
                            ?: "give one of them a namespace ('import * as <name> from \"" +
                            "${importRefOf(winners[0].first)}\"') and call it '<name>${QualName.SEP}$method(self: …)'"
                        ),
                )
                return Inline(null)
            }
            winners[0]
        }
        // The function's OWN name, not the local spelling: `{ run as go }` calls `.go()` here and names
        // `run` over there, and the callee has to be what the other document answers to.
        val callee = chosen.first?.let { "$it${QualName.SEP}${chosen.second.name}" } ?: chosen.second.name

        val id = node(BuiltinNodes.CALL, e.span, ann)
        nodeOf(id).callee = callee
        // **Wired before the pins are asked for**, which is new in phase E and load-bearing: a generic
        // call's parameter and result types are substituted from what feeds `self`, so a descriptor
        // resolved while that pin is still empty would hand back the unbound `T`s and every argument
        // below would be matched against the wrong type.
        wire(self, id, GraphFunction.SELF, e.span)
        val d = resolved(id)
        wireArgs(id, d, e, chain, skip = setOf(GraphFunction.SELF))
        if (d.kind == NodeKind.IMPURE || d.kind == NodeKind.ENTRY) chain.place(id)
        val out = d.dataOutputs.firstOrNull()
        return if (out == null) Inline(null) else FromPin(id, out.name)
    }

    /**
     * What a lowered expression carries — the receiver's type, for choosing between extensions.
     *
     * A WILDCARD answers null rather than `Any`, and the difference matters at the one call site: null
     * means "nothing here says", which accepts every candidate, and that is the honest reading of a pin
     * whose type nobody wrote down.
     */
    private fun typeOf(src: Src): TypeRef? = when (src) {
        is FromPin -> resolved(src.node).output(src.pin)
            ?.let { pin -> effectivePinType(nodeOf(src.node), pin, ::declaredVarType) { n, p -> feeding(n, p) } }
            ?.takeIf { !it.isWildcard }
        is Inline -> dev.ziggle.vscript.model.literalTypeOf(src.value)
    }

    /**
     * Pins that decide a node's other pins, written before the descriptor is resolved.
     *
     * `text("{n}", …)`'s holes come from its template and a record's fields from its declaration, so the
     * shape pin has to be typed in first — resolving the node before it is set finds a node with no pins to
     * match arguments against.
     */
    /**
     * `f(x)` — calling the function a NAME holds, rather than one the document declares.
     *
     * Returns null when the name holds no function, which is every other call in every existing script:
     * the check is on the binding's TYPE, so nothing that used to resolve to a declaration stops doing so.
     *
     * The node it builds is exactly the one `invoke(f, x)` builds; only [BuiltinNodes.INVOKE_WRITTEN] says
     * which spelling was used, and it is written by the OTHER path so that the shorter form — the one
     * somebody would draw on a canvas — is what an unmarked node prints as.
     */
    /**
     * `r.impl.at(4)` — a function-valued field called on a receiver that is itself an expression.
     *
     * **The field-before-extension lookup already existed and stopped one level short.** `Impl.at(4)` and
     * `h.at(4)` both work, because the head is a bare name and [invokeSugar] handles it; write the same
     * call on a field read and the parser hands `call` a RECEIVER instead of a two-part name, and that
     * branch tried an extension and nothing else — reported as "nothing extends this with 'at'", which
     * names the wrong thing entirely for a field sitting right there. GAPS 29.
     *
     * Tried only AFTER [extensionCall] has declined, so an extension still wins a name a field shares —
     * the same precedence the two already had one level up.
     *
     * The receiver arrives already lowered, because deciding between a field and an extension needs its
     * type and neither may lower it twice — see [call]'s receiver branch.
     */
    private fun receiverFieldInvoke(e: CallExpr, lowered: Src, chain: Chain, ann: Annotations): Src? {
        val field = e.target.lastOrNull() ?: return null
        val recv = lowered as? FromPin ?: return null
        val typeName = structNameOf(recv) ?: return null
        val t = visibleStructs().firstOrNull { it.name.equals(typeName, true) } ?: return null
        val f = t.fields.firstOrNull { Names.pinText(it.name) == field || it.name.equals(field, true) }
            ?: return null
        if (!f.type.isFunction) return null
        val get = node(BuiltinNodes.STRUCT_GET, e.span)
        nodeOf(get).literals[BuiltinNodes.STRUCT_OF] = t.name
        nodeOf(get).literals[BuiltinNodes.STRUCT_FIELD] = f.name
        wire(recv, get, "Value", e.span)
        return invokeOn(FromPin(get, f.name), field, e, chain, ann)
    }

    private fun invokeSugar(e: CallExpr, chain: Chain, ann: Annotations): Src? {
        if (e.receiver != null || e.module != null || e.target.isEmpty() || e.target.size > 2) return null
        val name = e.target.joinToString(".")
        val head = e.target[0]
        // **Everything below decides BEFORE it builds anything.** This runs ahead of every other kind of
        // call, so a shape it does not want has to be handed back untouched — `xs.add(v)` is an extension
        // and reaches here first, and a speculative member access would both make nodes nobody wants and
        // report "no field 'add'" for a call that is perfectly correct.
        val bound = lookup(head)
        val isVar = variables.any { it.name == head }
        // An IMPORTED name reaches here too: `import m from "x"` binds `m` to the other document's
        // default, and a bundled default is a record. Without this the field read is tried as a node type
        // and reported as "nothing here is called 'm.run'" — a call that is perfectly correct.
        val imported = unqualified[head]
        val importedVar = imported?.let { i -> importedDocs[i.alias]?.variable(i.name) }
        if (bound == null && !isVar && importedVar == null) return null

        val src: Src = if (e.target.size == 1) {
            bound ?: node(BuiltinNodes.VAR_GET, e.span).let { g ->
                nodeOf(g).variable = head
                FromPin(g, "Value")
            }
        } else {
            // `m.run(3)` — a handler read off a record or an enum row. The field has to EXIST and be
            // function-typed, both answered by looking at the declaration rather than by building the
            // access and seeing what happens.
            val field = e.target[1]
            // The record the head carries, named in THIS document's vocabulary. For an imported head that
            // is its own document's type requalified through the alias, which is exactly how
            // [importedTypes] spells it — the local case reads it off the pin instead.
            val on = bound as? FromPin
            val named = when {
                importedVar != null -> "${imported!!.alias}::${importedVar.type.name}"
                on != null -> structNameOf(on)
                // A GRAPH variable — which is what a `single` is — has no binding to read the type off,
                // so it comes from the declaration. Without this `Run.step(4)` on a single holding a
                // function value was reported as "nothing here is called 'Run.step'".
                else -> variables.firstOrNull { it.name == head }?.type?.name
            }
            val t = named?.let { n -> visibleStructs().firstOrNull { it.name.equals(n, true) } }
                ?.fields?.firstOrNull { it.name.equals(field, true) }?.type
                ?: on?.let { enumOf(it) }?.fields?.firstOrNull { it.name.equals(field, true) }?.type
                ?: return null
            if (!t.isFunction) return null
            expr(MemberExpr(NameExpr(head, e.span), field, e.span), chain)
        }
        return invokeOn(src, name, e, chain, ann)
    }

    /**
     * Build the Invoke for a function VALUE already in hand.
     *
     * Shared by the two ways one is reached: [invokeSugar], where the value is a name or a field of one,
     * and [receiverFieldInvoke], where it is a field read off an arbitrary receiver. Everything from the
     * arity check down is the same work, and two copies of it would be two copies to keep in step.
     */
    private fun invokeOn(src: Src, name: String, e: CallExpr, chain: Chain, ann: Annotations): Src? {
        val fnType = typeOf(src)
        if (fnType?.isFunction != true) return null
        e.args.firstOrNull { it.name != null }?.let {
            err(
                it.span,
                "'$name' is a function VALUE, and its arguments are positional — a value carries the " +
                    "shape of its parameters, not their names",
            )
        }
        val given = e.args.size + (if (e.trailing != null) 1 else 0)
        // **How many it takes, checked.** Nothing checked it: the argument PINS are derived from the
        // signature (see `effectivePinType`'s INVOKE case), so writing too few simply made fewer pins and
        // writing too many made extra ones typed WILDCARD — either way every wire fitted and the call
        // compiled. `rumor.runnable()` on a `fn(HunterRumor)` was silently a call with nothing in it.
        //
        // Only when the type says how many. An unconstrained `fn` is the "some function, any shape" type —
        // it is what Invoke's own pin is declared as — and it has nothing to be wrong about.
        if (fnType.args.isNotEmpty() && fnType.paramsOf.size != given) {
            val want = fnType.paramsOf
            err(
                e.span,
                "'$name' is a ${spell(fnType)}, so it takes ${want.size} argument(s) and $given " +
                    (if (given == 1) "was" else "were") + " given" +
                    if (want.isEmpty()) "" else " — ${want.joinToString(", ") { spell(it) }}",
            )
        }
        val id = node(BuiltinNodes.INVOKE, e.span, ann)
        nodeOf(id).literals[BuiltinNodes.INVOKE_COUNT] = given
        val d = resolved(id)
        wire(src, id, BuiltinNodes.INVOKE_FN, e.span, check = false)
        val pins = d.dataInputs.filter { it.name != BuiltinNodes.INVOKE_FN }
        e.args.forEachIndexed { i, arg ->
            val pin = pins.getOrNull(i) ?: return@forEachIndexed
            wire(expr(arg.value, chain), id, pin.name, arg.span, check = false)
        }
        e.trailing?.let { lam ->
            val pin = pins.getOrNull(e.args.size) ?: return@let
            wire(lambdaRef(lam, pin, name), id, pin.name, lam.span, check = false)
        }
        chain.place(id)
        val out = d.dataOutputs.firstOrNull()
        return if (out == null) Inline(null) else FromPin(id, out.name)
    }

    private fun writeShapePins(id: Int, e: CallExpr) {
        val n = nodeOf(id)
        // `invoke(handler, rumor)` — everything after the function is an argument, and how many there are
        // is the node's shape. Written before the descriptor is resolved, which is the only order that
        // works: the pins have to exist before `wireArgs` can put anything in them.
        if (n.type == BuiltinNodes.INVOKE) {
            n.literals[BuiltinNodes.INVOKE_COUNT] = maxOf(e.args.size - 1, 0) + (if (e.trailing != null) 1 else 0)
            // Written the long way. `invokeSugar` builds the same node without this, so the marker is what
            // gives each spelling back as it was typed.
            n.literals[BuiltinNodes.INVOKE_WRITTEN] = true
        }
        if (n.type == BuiltinNodes.FORMAT) {
            val tpl = (e.args.firstOrNull()?.value as? LiteralExpr)?.value
            if (tpl == null) err(e.span, "the message has to be written out, so its holes can become pins")
            else n.literals["Template"] = tpl
        }
    }

    /** Match arguments to pins: positional in declaration order, named by pin name. */
    /**
     * `{ it * 2 }` — an anonymous function, plus a reference to it.
     *
     * **A lambda is not a new kind of value.** It becomes a real function under a name no author can type
     * ([BuiltinNodes.isAnonymous]) and an ordinary [BuiltinNodes.FUNCTION_REF] on the wire, so the canvas
     * shows a function box and a reference — the same two things a named function shows — and everything
     * downstream of here already knew what to do with both.
     *
     * **The parameter types come from the PIN**, which is why this takes one: `xs.filter { it > 3 }` wires
     * into `keeping: fn(T) -> BOOL`, so `it` is `T` and the result is `BOOL`. There is no inference to do
     * and no annotation to write — the destination already says what shape of function it wants.
     *
     * **Free names become captures.** A name the body reads that is a LOCAL of the body the lambda was
     * written in cannot be reached from inside the callee's frame, so it becomes a trailing parameter of
     * the synthesised function and an input pin on the reference, wired from that local. `Op.CLOSURE`
     * copies the values in when the function value is built. Graph variables are not captured — they are
     * one cell for the whole run and the body can read them where it stands.
     */
    /**
     * A lambda written where something already says what it must be.
     *
     * **The rule is "a lambda needs a destination", and it used to be enforced as "a lambda needs a
     * call".** Those are not the same, and the difference was three refusals with a declared function type
     * sitting right beside them — `Hooks { ready: { it > 0 } }`, `val F: fn(INT) -> BOOL = { … }`, and the
     * same as a local. The error even explained that parameter types come from the destination, which was
     * true and was the argument for allowing it rather than against.
     *
     * Everything below this line is [lambdaRef]'s, unchanged: it already took its destination as a
     * `PinSpec` rather than as a call, so the only thing missing was somewhere else to build one from.
     */
    private fun lambdaValue(lam: LambdaExpr, expect: Expected?): Src {
        if (lam.stmts.isNotEmpty()) {
            // **Refused by name, not mis-lowered.** A lambda's body is a block in the text language, and a
            // function value on the canvas is a chain reached from a Function node with no statements
            // before it — there is no node to hang a `val` on. Saying so beats lowering the last
            // expression alone, which would silently drop the work the earlier statements did.
            err(
                lam.span,
                "the canvas cannot draw a lambda with statements in it — give it a body of one " +
                    "expression, or lift it out into a named function",
            )
            return Inline(null)
        }
        if (expect == null) {
            err(
                lam.span,
                "a lambda needs something to say what its parameters are — a call's argument, a record " +
                    "field, or a variable with a declared function type. There is nothing here to read " +
                    "them from",
            )
            return Inline(null)
        }
        return lambdaRef(lam, PinSpec(expect.what, expect.type), expect.owner)
    }

    /**
     * The parameter names [lam] declares against its destination, or null when the two disagree.
     *
     * Shared by the two ways a lambda becomes a function: [lambdaRef], which builds a reference on the
     * spot, and [foldLambda], which takes only the name and leaves the body for later. The rules are the
     * same either way, which is why this is one function rather than two copies that drift.
     */
    private fun lambdaShape(lam: LambdaExpr, pin: PinSpec, calleeName: String): List<String>? {
        val want = pin.type.paramsOf
        val declared = when {
            lam.params.isNotEmpty() -> lam.params
            lam.arrow -> {
                err(
                    lam.span,
                    "a lambda that names no parameters is written '{ … }' — the '->' is only for naming " +
                        "them, and with nothing before it there is nothing to name",
                )
                return null
            }
            want.isEmpty() -> emptyList()
            else -> listOf(LambdaExpr.IT)
        }
        if (declared.size != want.size) {
            err(
                lam.span,
                "'${pin.name}' of '$calleeName' wants a function of ${want.size} argument(s), and this " +
                    "lambda takes ${declared.size}" +
                    if (lam.params.isEmpty()) " — write them out with '->' rather than relying on 'it'" else "",
            )
            return null
        }
        declared.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.let {
            err(lam.span, "'${it.key}' is named twice in this lambda's parameters")
            return null
        }
        return declared
    }

    /**
     * A lambda written into a DECLARATION — an enum's row, a column default, a parameter default.
     *
     * **These fold to a constant, and a constant is the one thing a lambda is not.** What is stored is the
     * function's NAME, exactly as it already is when the column names one: `GraphCompiler` turns a
     * function-typed column's string into a `FunctionValue` on the way into the constant pool, and it does
     * not care whether a person wrote that name or this did.
     *
     * **Captures are impossible here and are not looked for.** A declaration has no enclosing locals, so
     * the free-name walk [lambdaRef] does would come back empty every time; anything the body reads is a
     * graph variable or a function, and both are reached by name from inside the synthesised function.
     *
     * The body is deferred — see [PendingLambda].
     */
    private fun foldLambda(lam: LambdaExpr, want: Expected): Src? {
        if (!want.type.isFunction) return null
        val pin = PinSpec(want.what, want.type)
        val declared = lambdaShape(lam, pin, want.owner) ?: return null
        if (lam.body == null && !want.type.hasNoResult) {
            err(
                lam.span,
                "'${want.what}' of '${want.owner}' wants a function that hands back " +
                    "${want.type.returnsOf ?: "a value"}, and '{ }' hands back nothing",
            )
            return null
        }
        val types = want.type.paramsOf
        val params = declared.mapIndexed { i: Int, n: String -> FunctionPin(n, types[i]) }
        val name = "@lambda${++lambdaCount}"
        functions += GraphFunction(name, params, lambdaResults(want.type))
        pendingLambdas += PendingLambda(name, lam, params, hasResult = !want.type.hasNoResult)
        return Inline(name)
    }

    /** What a synthesised lambda hands back: one Result, or none at all for a `fn(T)`. */
    private fun lambdaResults(type: TypeRef): List<FunctionPin> =
        if (type.hasNoResult) {
            emptyList()
        } else {
            listOf(FunctionPin(Parser.RESULT_PIN, type.resultOf ?: TypeRef(PinType.WILDCARD)))
        }

    private fun lambdaRef(lam: LambdaExpr, pin: PinSpec, calleeName: String): Src {
        if (!pin.type.isFunction) {
            err(lam.span, "'${pin.name}' of '$calleeName' is not a function, so a lambda cannot go there")
            return Inline(null)
        }
        // `{ }` evaluates to nothing, so it fits only a destination that wants nothing back. Checked here
        // because this is the one place that knows both halves.
        if (lam.body == null && !pin.type.hasNoResult) {
            err(
                lam.span,
                "'${pin.name}' of '$calleeName' wants a function that hands back " +
                    "${pin.type.returnsOf ?: "a value"}, and '{ }' hands back nothing",
            )
            return Inline(null)
        }
        val want = pin.type.paramsOf
        // **A lambda that names no parameters takes its ARITY from the pin, not a fixed one.** Nothing is
        // written down, so there is nothing to contradict the destination — and the destination is already
        // where the parameter TYPES come from, so reading the count from anywhere else was the odd rule.
        //
        // A pin that takes nothing therefore gets `{ f() }`, exactly as in Kotlin. It used to get the
        // implicit `it` regardless and fail as an arity error, which left `fn() -> BOOL` with no inline
        // spelling at all — every one-line predicate had to be lifted out into a named function beside the
        // call. `{ -> … }` is refused rather than accepted as a synonym: two spellings for one graph is
        // the thing this language does not do, and the printer would have to guess which was typed.
        val declared = lambdaShape(lam, pin, calleeName) ?: return Inline(null)

        // Read in the ENCLOSING scope, before it is swapped out for the body's.
        // A body that is not there captures nothing.
        val free = LinkedHashSet<String>().also { f ->
            lam.body?.let { freeNames(it, declared.toSet(), f) }
        }
        val captured = free.filter { lookup(it) != null }

        val name = "@lambda${++lambdaCount}"
        val params = declared.mapIndexed { i, n -> FunctionPin(n, want[i]) } +
            captured.map { FunctionPin(it, TypeRef(PinType.WILDCARD)) }
        // **No Result pin when the destination hands nothing back.** A `fn(T)` has no result, and one
        // declared anyway is one nothing feeds — reported as "'Result' has nothing feeding it, it will
        // arrive as null" on every acting lambda. It was harmless while an acting body was impossible and
        // the pin was always fed by the body's value; it is not harmless now.
        functions += GraphFunction(name, params, lambdaResults(pin.type))

        val ref = node(BuiltinNodes.FUNCTION_REF, lam.span)
        nodeOf(ref).literals[BuiltinNodes.FUNCTION_REF_NAME] = name
        if (captured.isNotEmpty()) {
            nodeOf(ref).literals[BuiltinNodes.CAPTURES] = captured.joinToString(",")
            for (c in captured) wire(lookup(c)!!, ref, c, lam.span, check = false)
        }
        // Purity is DERIVED from the body — see [lambdaBody]. Recorded after it, because before it there
        // is nothing to derive it from.
        if (lambdaBody(name, lam, params, hasResult = !pin.type.hasNoResult)) pureFunctions += name
        return FromPin(ref, "Value")
    }

    /** The synthesised function's box and body, lowered with ONLY its own parameters in scope. */
    private fun lambdaBody(
        name: String,
        lam: LambdaExpr,
        params: List<FunctionPin>,
        hasResult: Boolean,
    ): Boolean {
        val prevFn = currentFn
        val prevBox = currentBox
        val prevGroup = currentGroup
        val prevSelf = selfHold
        // The whole scope stack, not one frame. A lambda's body must NOT see the locals of the body it was
        // written in: those are captures, bound below as parameters, and leaving them visible would wire a
        // node in one function's body straight to a Hold in another's — which compiles, and reads a
        // register belonging to a different frame.
        val outer = ArrayList(scopes)
        scopes.clear()
        currentFn = name
        selfHold = null
        val box = node(BuiltinNodes.FUNCTION, lam.span)
        nodeOf(box).function = name
        currentBox = box
        currentGroup = box
        pushScope()
        for (p in params) bind(p.name, FromPin(box, p.name))
        // An expression body is pure until its own chain says otherwise, and one that returns a value has
        // no chain at all — so this is the answer for every lambda but an acting one.
        var pure = true
        // **A body that hands nothing back gets an exec chain; one that returns a value does not.**
        //
        // That line is the whole rule, and it is about what the DESTINATION asked for. `fn(T)` is the type
        // of a function that exists to act — `each(self, f: fn(T))` calls it from inside a `for`, on its
        // own chain — so its body must be allowed to act, and `xs.each { register(it) }` is the ordinary
        // way to write one. It could not be written before: every lambda was lowered with
        // `Chain(emptyList())` and declared pure before its body was read, so a step inside one was
        // reported as "nothing runs this" — a true sentence about a rule nobody had chosen.
        //
        // `fn(T) -> U` keeps the old treatment, and that is deliberate rather than caution. Those are the
        // ones handed to `mapped`, `filtered` and `firstWhere`, which are PURE nodes re-expanded at every
        // read of their result — so a body that acts there runs its effects once per read. §3.6 states
        // that hazard for a named function passed the same way; leaving the lambda form an expression is
        // what keeps it from being the easy thing to write by accident.
        //
        // Purity is then DERIVED, exactly as [function] derives it: nothing joins the chain for a body
        // that only computes, the box's exec pins stay unwired, and that IS the expression signal.
        if (hasResult) {
            // Nothing to wire when there is nothing to evaluate: `{ }` leaves the result pin unfed, which
            // is what a function with no result looks like anywhere else.
            lam.body?.let { wire(expr(it, Chain(emptyList())), box, Parser.RESULT_PIN, it.span) }
        } else {
            val chain = Chain(listOf(End(box, "Exec")))
            lam.body?.let { expr(it, chain) }
            pure = chain.open.singleOrNull()?.let { it.node == box && it.pin == "Exec" } == true
            // Falling off the end wires the open end back to the box, the same way a function body's does.
            if (!pure) for (e in chain.open) link(e.node, e.pin, box, "Exec")
        }
        popScope()
        scopes.clear()
        scopes += outer
        currentFn = prevFn
        currentBox = prevBox
        currentGroup = prevGroup
        selfHold = prevSelf
        return pure
    }

    /**
     * Every bare name [e] reads, minus those [bound] here — the candidates for capture.
     *
     * Over-approximating is safe and under-approximating is not: a name that turns out to be a graph
     * variable or a function is dropped by the [lookup] filter at the call site, where a name that was
     * MISSED would be looked up inside the lambda's own scope, found to be nothing, and reported as
     * undeclared. So every expression shape is listed rather than defaulted.
     */
    private fun freeNames(e: Expr, bound: Set<String>, into: MutableSet<String>) {
        fun walk(x: Expr) = freeNames(x, bound, into)
        when (e) {
            is NameExpr -> if (e.module == null && e.name !in bound) into += e.name
            is CallExpr -> {
                e.receiver?.let(::walk)
                // `xs.add(3)` — the RECEIVER, and it is not in [CallExpr.receiver]. A call whose head is a
                // bare name has its dots collected into [CallExpr.target], because that is also how a node
                // TYPE is spelled (`draw.text`), so the two are told apart by resolution and not by the
                // parser. Missing it here is what made a captured local unusable in receiver position and
                // nowhere else: `limit < it` captured `limit` and `it < limit.twice()` reported "nothing
                // here is called limit" — a rule nobody would guess, and documented as GAPS 18.
                //
                // Over-approximating is safe and stated to be: `draw` in `draw.text(…)` reaches [lookup],
                // is nothing, and is dropped.
                if (e.module == null && e.target.size >= 2 && e.target.first() !in bound) {
                    into += e.target.first()
                }
                e.args.forEach { walk(it.value) }
                // A nested lambda's own parameters shadow, and everything else it reads is free out here
                // too — it will be captured by this lambda and passed along.
                e.trailing?.let { t -> t.body?.let { freeNames(it, bound + t.params + LambdaExpr.IT, into) } }
            }
            is LambdaExpr -> e.body?.let { freeNames(it, bound + e.params + LambdaExpr.IT, into) }
            is BinaryExpr -> { walk(e.left); walk(e.right) }
            is NotExpr -> walk(e.operand)
            is TernaryExpr -> { walk(e.condition); walk(e.ifTrue); walk(e.ifFalse) }
            is ElvisExpr -> { walk(e.value); walk(e.fallback) }
            is SafeAccessExpr -> { walk(e.receiver); walk(e.access) }
            is MemberExpr -> walk(e.target)
            is IndexExpr -> { walk(e.target); walk(e.index) }
            is IsExpr -> walk(e.value)
            // The renames name FIELDS of the source record, not anything in scope.
            is AsExpr -> walk(e.value)
            is WithExpr -> { walk(e.target); e.fields.forEach { walk(it.value) } }
            is StructLitExpr -> e.fields.forEach { walk(it.value) }
            is ListLitExpr -> e.items.forEach(::walk)
            is LiteralExpr, is SafeItExpr -> Unit
        }
    }

    private fun wireArgs(
        id: Int,
        d: NodeDescriptor,
        e: CallExpr,
        chain: Chain,
        /** Pins already wired by the caller — an extension's `self`, which is not an argument. */
        skip: Set<String> = emptySet(),
    ) {
        val shape = BuiltinNodes.SHAPE_PINS[nodeOf(id).type].orEmpty()
        val positional = d.dataInputs.filter { it.name !in shape && it.name !in skip }
        var next = 0
        // Typed-in values, judged AFTER every argument is in place.
        //
        // **A pin's type can be decided by a sibling.** `[].pick(a: 3, b: 2.5)` on
        // `fn LIST<T>.pick(self, a: T, b: T)` binds `T` from every argument at once and resolves it to the
        // type that accepts them all — FLOAT. Checking `b` the moment it landed asked while only `a` had
        // been seen, so a correct call was refused with "cannot put a Float into INT", which is the exact
        // failure `docs/VSCRIPT_ERGONOMICS_PLAN.md` §5.4c predicts for binding on first sight.
        //
        // Nothing is skipped: every value still gets the same check, one pass later.
        val typed = ArrayList<Triple<Any?, String, Span>>()
        /** Pins an argument reached, by their RESOLVED names — `keeping:` and `Keeping` are one pin. */
        val fed = HashSet<String>()
        for (arg in e.args) {
            val pin: PinSpec? = if (arg.name != null) {
                names.input(d, arg.name) ?: run { err(arg.span, "'${e.name}' has no input called '${arg.name}'"); null }
            } else {
                // The template was consumed as a shape pin; it is not an ordinary argument.
                if (nodeOf(id).type == BuiltinNodes.FORMAT && next == 0) { next++; continue }
                positional.getOrNull(next++) ?: run { err(arg.span, "'${e.name}' takes ${positional.size} value(s)"); null }
            }
            if (pin == null) continue
            if (pin.name in shape) { err(arg.span, "'${pin.name}' decides this node's pins, so it is written, not wired"); continue }
            fed += pin.name
            // A lambda written AT the argument — `filter(keeping: { it > 3 })`, `useAt(done: { … })`.
            // It cannot go through [expr], which refuses a bare lambda because it has no destination to
            // read the parameter types from; here the destination is exactly what we have. The pin is
            // recorded so the printer writes it back where it was written rather than moving it to the
            // trailing position, which is the only reason the two spellings can coexist.
            val lam = arg.value as? LambdaExpr
            val src = if (lam != null) {
                nodeOf(id).literals[BuiltinNodes.INLINE_ARGS] =
                    (BuiltinNodes.inlineArgsOf(nodeOf(id)) + pin.name).joinToString(",")
                lambdaRef(lam, pin, e.name)
            } else {
                // The real chain, and BEFORE this node is placed: a nested impure argument has to run first.
                expr(arg.value, chain)
            }
            if (src is Inline) typed += Triple(src.value, pin.name, arg.span)
            wire(src, id, pin.name, arg.span, check = false)
        }
        for ((value, pin, span) in typed) checkLiteralFits(value, id, pin, span)

        // The trailing lambda, bound to the LAST function-typed input — Kotlin's rule, and the only one
        // that needs no name at the call site. Wired after the ordinary arguments so a pin that already
        // has one is reported rather than quietly overwritten.
        e.trailing?.let { lam ->
            val pin = d.dataInputs.lastOrNull { it.type.isFunction && it.name !in shape && it.name !in skip }
            if (pin == null) {
                err(
                    lam.span,
                    "'${e.name}' takes no function, so there is nothing for a trailing lambda to be — " +
                        "a lambda goes to the last parameter typed 'fn(…) -> …'",
                )
                return
            }
            if (pin.name in fed) {
                err(lam.span, "'${pin.name}' was given twice — once by name and once as a trailing lambda")
                return
            }
            wire(lambdaRef(lam, pin, e.name), id, pin.name, lam.span, check = false)
        }
    }

    /**
     * `x is Int` — a pure node carrying the question, not a cast.
     *
     * The type is written into the node rather than wired, because it is part of the QUESTION: a wire
     * there would be a question decided at run time by something the reader cannot see.
     */
    private fun isExpr(e: IsExpr, chain: Chain): Src {
        val id = node(BuiltinNodes.IS_TYPE, e.span)
        val named = e.module?.let { "$it${QualName.SEP}${e.typeName}" } ?: e.typeName
        nodeOf(id).literals[BuiltinNodes.IS_OF] = named
        if (e.negated) nodeOf(id).literals[BuiltinNodes.IS_NOT] = true
        wire(expr(e.value, chain), id, "Value", e.span)
        return FromPin(id, "Result")
    }

    /**
     * `p as Vec2i` — a node carrying the target and the renames.
     *
     * A NODE rather than lowering straight to `Vec2i { x: p.x, y: p.y }`, even though that is exactly
     * what it compiles to. The expansion is indistinguishable from a hand-written record literal, so a
     * document would print back as the long form and the cast would vanish on the first round trip —
     * the recognizer collision VSCRIPT_LANG_PLAN.md §6.7 rejects sugar for.
     */
    private fun asExpr(e: AsExpr, chain: Chain): Src {
        val id = node(BuiltinNodes.CAST, e.span)
        // The WHOLE type, arguments included. The literal is the printer's source of truth as well as the
        // compiler's, so rendering the arguments here is what makes `json as MAP<STRING, Tally>` survive a
        // round trip; `TypeRef.parse` is the other half, and the two are exact inverses.
        val base = e.module?.let { "$it${QualName.SEP}${e.typeName}" } ?: e.typeName
        val named = if (e.typeArgs.isEmpty()) base
        else e.typeArgs.joinToString(", ", "$base<", ">") { typeRef(it).toString() }
        nodeOf(id).literals[BuiltinNodes.CAST_OF] = named
        val renames = LinkedHashMap<String, String>()
        // Which of them the author quoted. Metadata, exactly like `@bare` on a `when`: it changes nothing
        // about what runs — the mapping is the same either way — and exists so `Print` can raise back the
        // spelling that was written instead of picking one and breaking a character-identical round trip.
        val quoted = LinkedHashSet<String>()
        for (r in e.renames) {
            when (val v = r.value) {
                is NameExpr -> renames[r.name] = v.name
                is LiteralExpr ->
                    if (v.kind == LiteralKind.STRING) {
                        val key = v.value?.toString().orEmpty()
                        // The stored form joins pairs on ',' and splits each on the first '=', so a key
                        // holding either would come back as a different key or as two. Said here, where
                        // the author can see the string, rather than surfacing as a missing key at run
                        // time in a file that looks right.
                        val problem = BuiltinNodes.castKeyProblem(key)
                        if (problem != null) {
                            err(v.span, problem)
                        } else {
                            renames[r.name] = key
                            quoted += r.name
                        }
                    } else {
                        err(r.span, "a rename names a field of the source, or a JSON key in quotes")
                    }
                else -> err(r.span, "a rename names a field of the source, or a JSON key in quotes")
            }
        }
        if (renames.isNotEmpty()) {
            nodeOf(id).literals[BuiltinNodes.CAST_RENAMES] = BuiltinNodes.castRenamesOf(renames)
        }
        if (quoted.isNotEmpty()) {
            nodeOf(id).literals[BuiltinNodes.CAST_QUOTED] = quoted.joinToString(",")
        }
        wire(expr(e.value, chain), id, "Value", e.span)
        return FromPin(id, "Result")
    }

    private fun binary(e: BinaryExpr, chain: Chain): Src {
        // `&&` and `||` short-circuit, and Select is the node that can: it has a jump to skip the untaken
        // arm with, which AND/OR by definition do not. `and(a, b)` remains available for the other one.
        if (e.op == BinaryOp.AND_THEN || e.op == BinaryOp.OR_ELSE) {
            val sel = node(BuiltinNodes.SELECT, e.span)
            wire(expr(e.left, chain), sel, "Condition", e.left.span)
            // **The right side is only reached when the left went one way, so a null test on the left is
            // proved there.** Sound for the reason the ternary's is: Select compiles to a `JMPF` over the
            // arm it does not take, so the narrowed arm runs only when the test held. Scoped to that arm.
            //
            // `||` is the half that was missing, and it is the one an early-out guard is written with:
            // `t == null || t.x > 3` reads the right side only when `t` is not null, and reported
            // "cannot wire TILE? into TILE" for saying so (GAPS 20).
            val wanted = e.op == BinaryOp.AND_THEN
            pushScope()
            provenNonNull(e.left, wanted).forEach { (n, sp) -> narrow(n, sp) }
            if (wanted) {
                wire(expr(e.right, chain), sel, "If True", e.right.span)
                wire(Inline(false), sel, "If False", e.span)
            } else {
                wire(Inline(true), sel, "If True", e.span)
                wire(expr(e.right, chain), sel, "If False", e.right.span)
            }
            popScope()
            return FromPin(sel, "Value")
        }
        val type = BINARY[e.op] ?: run { err(e.span, "no node for this operator"); return Inline(null) }
        val id = node(type, e.span)
        wire(expr(e.left, chain), id, "A", e.left.span)
        wire(expr(e.right, chain), id, "B", e.right.span)
        return FromPin(id, "Result")
    }

    private fun unary(e: NotExpr, chain: Chain): Src {
        val id = node(BuiltinNodes.NOT, e.span)
        wire(expr(e.operand, chain), id, "A", e.operand.span)
        return FromPin(id, "Result")
    }

    /**
     * `c ? a : b`, and a null test in `c` narrows the arm it proved.
     *
     * `text != null ? needs(text) : fail()` is the shape this is for, and it is sound for the same reason
     * the statement form is: `Select` compiles to a `JMPF` over the arm it does not take, so the proved
     * arm only ever runs when the test held. The narrowing is scoped to that one arm.
     */
    private fun ternary(e: TernaryExpr, chain: Chain): Src {
        val id = node(BuiltinNodes.SELECT, e.span)
        wire(expr(e.condition, chain), id, "Condition", e.condition.span)

        pushScope()
        provenNonNull(e.condition, true).forEach { (n, sp) -> narrow(n, sp) }
        wire(expr(e.ifTrue, chain), id, "If True", e.ifTrue.span)
        popScope()

        pushScope()
        provenNonNull(e.condition, false).forEach { (n, sp) -> narrow(n, sp) }
        wire(expr(e.ifFalse, chain), id, "If False", e.ifFalse.span)
        popScope()

        return FromPin(id, "Value")
    }

    /**
     * `a?.b` — the access is lowered against the guard's own `It` pin, not against `a`.
     *
     * That is the whole trick and it is what buys a single evaluation: `a` is wired to `Value` once, and
     * whatever `b` reads it from reads the `It` output instead. Nothing here has to know which KIND of
     * access it is — a field, a method call — because the placeholder was put in the receiver position by
     * the parser and lowering an expression is the same job either way.
     */
    private fun safeAccess(e: SafeAccessExpr, chain: Chain): Src {
        val id = node(BuiltinNodes.IF_PRESENT, e.span)
        wire(expr(e.receiver, chain), id, BuiltinNodes.IF_PRESENT_VALUE, e.receiver.span)
        val was = safeIt
        safeIt = FromPin(id, BuiltinNodes.IF_PRESENT_IT)
        try {
            wire(expr(e.access, chain), id, BuiltinNodes.IF_PRESENT_THEN, e.access.span)
        } finally {
            safeIt = was
        }
        return FromPin(id, "Result")
    }

    /** What a [SafeItExpr] resolves to — the innermost enclosing `?.`'s receiver pin. */
    private var safeIt: Src? = null

    /** `a ?: b`. */
    private fun elvis(e: ElvisExpr, chain: Chain): Src {
        val id = node(BuiltinNodes.OR_ELSE, e.span)
        wire(expr(e.value, chain), id, BuiltinNodes.OR_ELSE_VALUE, e.value.span)
        wire(expr(e.fallback, chain), id, BuiltinNodes.OR_ELSE_FALLBACK, e.fallback.span)
        return FromPin(id, "Result")
    }

    /** `x.name` — a record's field, or one output pin of a call with several. */
    /**
     * `Phase.Chop` — one member of a declared enum, as an [BuiltinNodes.ENUM_OF] node.
     *
     * Tried BEFORE the target is evaluated, which is not an optimisation: `Phase` is a type, not a value, so
     * lowering it as an expression would report "nothing here is called 'Phase'" and the real spelling would
     * never be reached.
     *
     * **A value in scope wins.** `let Phase = something` followed by `Phase.x` is a field read on that
     * value, not a member of the enum it shadows — the same rule every other name here follows, and the
     * reason this asks [lookup] and [variables] before asking [enumNamed].
     */
    private fun enumMember(e: MemberExpr): Src? {
        val base = e.target as? NameExpr ?: return null
        if (base.module == null && (lookup(base.name) != null || variables.any { it.name == base.name })) {
            return null
        }
        val t = enumNamed(base.module, base.name) ?: return null
        val id = node(BuiltinNodes.ENUM_OF, e.span)
        nodeOf(id).literals[BuiltinNodes.ENUM_TYPE] = t.name
        // Stored as the enum SPELLS it, not as it was written, so `phase.chop` and `Phase.Chop` produce the
        // same document. The declaration is the one authority on capitalisation.
        val member = t.member(e.member)
        if (member == null) {
            err(e.span, "'${t.name}' has no member '${e.member}' — it has ${t.members.joinToString(", ")}")
        }
        nodeOf(id).literals[BuiltinNodes.ENUM_MEMBER] = member ?: e.member
        return FromPin(id, "Value")
    }

    private fun member(e: MemberExpr, chain: Chain): Src {
        enumMember(e)?.let { return it }
        val target = expr(e.target, chain)
        if (target !is FromPin) { err(e.span, "'${e.member}' needs something to read it from"); return Inline(null) }
        val outs = resolved(target.node).dataOutputs
        // An output pin by name — `entityInfo(e).Tile`. A record arrives on a pin called `Value`, so this
        // cannot shadow a field access: the two never match the same word.
        outs.firstOrNull { Names.pinText(it.name) == e.member || it.name == e.member }
            ?.let { return FromPin(target.node, it.name) }
        // A column of an enum's table — `t.anchor`. Asked before the record path because the two cannot
        // both apply: a pin carries an enum or a record, never both. It covers the static spelling too,
        // since `Target.WildKebbit` is a pin of the enum's own type and this reads off it exactly as it
        // reads off a variable.
        enumFieldOf(target, e)?.let { return it }
        val typeName = structNameOf(target)
        if (typeName == null) { err(e.span, "this has no field '${e.member}'"); return Inline(null) }
        val t = visibleStructs().first { it.name.equals(typeName, true) }
        val field = t.fields.firstOrNull { Names.pinText(it.name) == e.member || it.name.equals(e.member, true) }
        if (field == null) { err(e.span, "'$typeName' has no field '${e.member}'"); return Inline(null) }
        val get = node(BuiltinNodes.STRUCT_GET, e.span)
        nodeOf(get).literals[BuiltinNodes.STRUCT_OF] = t.name
        nodeOf(get).literals[BuiltinNodes.STRUCT_FIELD] = field.name
        wire(target, get, "Value", e.span)
        return FromPin(get, field.name)
    }

    private fun index(e: IndexExpr, chain: Chain): Src {
        val id = node(BuiltinNodes.LIST_AT, e.span)
        wire(expr(e.target, chain), id, "List", e.target.span)
        wire(expr(e.index, chain), id, "Index", e.index.span)
        return FromPin(id, "Item")
    }

    private fun structLit(e: StructLitExpr, chain: Chain): Src {
        val t = structNamed(e.module, e.type)
        if (t == null) {
            val written = if (e.module == null) e.type else "${e.module}${QualName.SEP}${e.type}"
            err(
                e.span,
                if (e.module == null) "no type named '$written' in this graph"
                else "no type named '$written' — is it declared, and does it say 'export'?",
            )
            return Inline(null)
        }
        val id = node(BuiltinNodes.STRUCT_MAKE, e.span)
        nodeOf(id).literals[BuiltinNodes.STRUCT_OF] = t.name
        val written = HashSet<String>()
        for (f in e.fields) {
            val field = t.fields.firstOrNull { Names.pinText(it.name) == f.name || it.name.equals(f.name, true) }
            if (field == null) err(f.span, "'${t.name}' has no field '${f.name}'")
            else {
                written += field.name
                wire(expr(f.value, chain, Expected(field.type, field.name, t.name)), id, field.name, f.span)
            }
        }
        // A field LEFT OUT whose default has to run is filled by calling it — here, at the construction
        // site, which is what "evaluated per construction" means. A literal default needs none of this: it
        // rides on the pin and `struct.make` resolves it as it always has.
        for (field in t.fields) {
            if (field.name in written) continue
            val fn = BuiltinNodes.fieldDefaultName(QualName.parse(t.name).name, field.name)
            val callee = QualName.parse(t.name).module?.let { "$it${QualName.SEP}$fn" } ?: fn
            if (signatureOf(callee) == null) continue
            wire(callDefault(callee, chain, e.span), id, field.name, e.span)
        }
        return FromPin(id, "Value")
    }

    /**
     * A Call to a synthesised field default — an ordinary call, built by hand because nothing wrote one.
     *
     * On the exec chain when the callee is a step, off it when it is not, which is the same decision every
     * other call makes and the reason a record literal can be either. See [BuiltinNodes.FIELD_DEFAULT].
     */
    private fun callDefault(callee: String, chain: Chain, at: Span): Src {
        val id = node(BuiltinNodes.CALL, at)
        nodeOf(id).callee = callee
        if (!isPureCallee(callee)) chain.place(id)
        return FromPin(id, Parser.RESULT_PIN)
    }

    /** Is a callee an expression? The same question [isPure] answers, asked by name. */
    private fun isPureCallee(callee: String): Boolean {
        val q = QualName.parse(callee)
        purityAcrossImport(callee)?.let { return it }
        return q.name in pureFunctions || callee in pureFunctions
    }

    private fun withExpr(e: WithExpr, chain: Chain): Src {
        val target = expr(e.target, chain)
        val typeName = (target as? FromPin)?.let { structNameOf(it) }
        if (typeName == null) { err(e.span, "'with' needs a record"); return Inline(null) }
        val t = visibleStructs().first { it.name.equals(typeName, true) }
        if (e.fields.size != 1) {
            err(e.span, "'with' replaces one field at a time — chain another 'with' for the next")
            return Inline(null)
        }
        val f = e.fields[0]
        val field = t.fields.firstOrNull { Names.pinText(it.name) == f.name || it.name.equals(f.name, true) }
        if (field == null) { err(f.span, "'${t.name}' has no field '${f.name}'"); return Inline(null) }
        val id = node(BuiltinNodes.STRUCT_SET, e.span)
        nodeOf(id).literals[BuiltinNodes.STRUCT_OF] = t.name
        nodeOf(id).literals[BuiltinNodes.STRUCT_FIELD] = field.name
        wire(target, id, "Value", e.target.span)
        wire(expr(f.value, chain), id, field.name, f.span)
        return FromPin(id, "Result")
    }

    private fun listLit(e: ListLitExpr, chain: Chain): Src {
        val id = node(BuiltinNodes.LITERAL_LIST, e.span)
        val n = nodeOf(id)
        n.literals[BuiltinNodes.LIST_OF] = elementTypeName(e)
        n.literals[BuiltinNodes.LIST_COUNT] = e.items.size
        val d = resolved(id)
        val slots = d.dataInputs.filter { it.name !in BuiltinNodes.SHAPE_PINS.getValue(BuiltinNodes.LITERAL_LIST) }
        e.items.forEachIndexed { i, item ->
            slots.getOrNull(i)?.let { wire(expr(item, chain), id, it.name, item.span) }
        }
        return FromPin(id, "Value")
    }

    /** What a written-out list holds, guessed from what is in it. Ints when it is empty or mixed. */
    private fun elementTypeName(e: ListLitExpr): String {
        // A list of RECORDS says its own element type, and has to be read before the elements are wired.
        // The pin a list is handed to retypes it — see [retypeList] — but that happens after the items
        // have been attached, so a list of records built here defaulted to Int and then refused every one
        // of its own elements. Nothing else can answer this: the items are the only evidence there is.
        val structs = e.items.filterIsInstance<StructLitExpr>()
        if (structs.size == e.items.size && structs.isNotEmpty()) {
            val named = structs.map { it.module?.let { m -> "$m${QualName.SEP}${it.type}" } ?: it.type }.distinct()
            if (named.size == 1) return named[0]
        }
        val kinds = e.items.filterIsInstance<LiteralExpr>().map { it.kind }.distinct()
        return when {
            // MIXED, or empty: unconstrained rather than Int. Guessing Int for a list that plainly holds
            // strings as well made the list refuse its own elements — so a mixed list could not be
            // written at all, which is exactly the case a run-time type test exists to sort out. An
            // unconstrained list is also what the pin it is handed to will retype, if it is handed to one.
            kinds.size != 1 -> "Wildcard"
            kinds[0] == LiteralKind.STRING -> "String"
            kinds[0] == LiteralKind.FLOAT -> "Float"
            kinds[0] == LiteralKind.BOOL -> "Bool"
            kinds[0] == LiteralKind.COLOR -> "Color"
            else -> "Int"
        }
    }

    /**
     * The type an initialiser has AT THE SOURCE, for the ones whose stored value cannot say.
     *
     * Only the text-shaped kinds are here, and that is the whole point: an INT, a FLOAT and a BOOL are
     * recoverable from the value itself by `literalTypeOf`, while a tile, a colour and a plain string are
     * all stored as text and indistinguishable once written down. `tile(…)` is unmistakable where it is
     * WRITTEN, so the question is answered here, where the expression still exists.
     *
     * Null for anything else — including a call, whose type comes off its result pin through the wire, and
     * an enum member, which arrives on an `enum.of` node that already carries its type.
     */
    /** Does [pin] on [toNode] decline to say what it holds? Then a literal's own answer is worth keeping. */
    private fun wildcardPin(toNode: Int, pin: String): Boolean {
        val spec = resolved(toNode).input(pin) ?: return false
        return effectivePinType(nodeOf(toNode), spec, ::declaredVarType) { n, p -> feeding(n, p) }.isWildcard
    }

    private fun sourceTypeOf(e: Expr): TypeRef? = when {
        e is CallExpr && e.receiver == null && e.module == null && e.name == "tile" -> TypeRef.named("Tile")
        e is LiteralExpr && e.kind == LiteralKind.STRING -> TypeRef(PinType.STRING)
        e is LiteralExpr && e.kind == LiteralKind.COLOR -> TypeRef.named("Color")
        else -> null
    }

    private fun tileLiteral(e: CallExpr): Src {
        val parts = e.args.mapNotNull { (it.value as? LiteralExpr)?.value as? Number }
        if (parts.size < 2) { err(e.span, "a tile is 'tile(x, y)' or 'tile(x, y, plane)'"); return Inline(null) }
        return Inline(
            "${parts[0].toInt()},${parts[1].toInt()},${parts.getOrNull(2)?.toInt() ?: 0}",
            TypeRef.named("Tile"),
        )
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    /**
     * Put [src] into [pin] — a wire when it comes from a node, a typed-in value when it is a constant.
     *
     * [check] is off for an ARGUMENT, and that is not an omission — see [wireArgs]. A pin's type can be
     * decided by a sibling argument that has not been placed yet, so judging a literal the moment it lands
     * asks the question too early.
     */
    private fun wire(src: Src, toNode: Int, toPin: String, span: Span, check: Boolean = true) {
        when (src) {
            is FromPin -> { retypeList(src, toNode, toPin); link(src.node, src.pin, toNode, toPin) }
            is Inline -> {
                if (check) checkLiteralFits(src.value, toNode, toPin, span)
                nodeOf(toNode).literals[toPin] = src.value
                // Only where the pin does not already answer it — see [BuiltinNodes.pinInferred].
                src.inferred?.takeIf { wildcardPin(toNode, toPin) }?.let {
                    nodeOf(toNode).literals[BuiltinNodes.pinInferred(toPin)] = it.toString()
                }
            }
        }
    }

    /**
     * A typed-in value has to be the kind of thing the pin holds.
     *
     * **Wiring the wrong type was already refused; typing it in was not.** `canConnect` guards every link,
     * but a literal never passes through it — it is written straight into `Node.literals` — so
     * `delay(ms: "soon")` and `N = "not a number"` compiled clean and went wrong at run time, where a host
     * casting the value is a long way from the line that caused it. That asymmetry is the bug; this closes
     * it, and the message is deliberately shaped like the wire one because it is the same mistake.
     *
     * Checked against the value's RUNTIME kind rather than the literal's syntax, because by here the lexer
     * has already decoded it. One thing is lost with the syntax: `#FF00FF` and `16711935` are both an Int,
     * so an Int is accepted into a Color pin. That is the honest limit of checking after decoding, and it
     * is a far smaller hole than the one being closed.
     */
    private fun checkLiteralFits(value: Any?, toNode: Int, toPin: String, span: Span) {
        // null is "no value", which every pin may hold — it is how a cleared field is spelled.
        if (value == null) return
        // A list literal takes its element type from the pin, which `retypeList` has yet to do.
        if (value is List<*>) return

        val spec = resolved(toNode).input(toPin) ?: return
        val type = effectivePinType(nodeOf(toNode), spec, ::declaredVarType) { n, p -> feeding(n, p) }
        // A wildcard holds anything, and a list pin is `retypeList`'s business.
        if (type.isWildcard || type.isList || type.isExec) return
        val builtin = type.builtin ?: return
        if (accepts(builtin, value)) return

        val desc = resolved(toNode)
        err(
            span,
            "cannot put ${kindOf(value)} into $type ('${desc.title}.${spec.name}')",
        )
    }

    /**
     * Which decoded values a pin type will hold.
     *
     * The id-like types are Ints because that is what an id is; `SKILL` and `ENUM` are Strings because the
     * picker lists names and the node bodies read names. All of that is [Literals.of]'s existing
     * arrangement, read back the other way — if the two ever disagree, this is the copy that is wrong.
     *
     * `TILE` was here, a String because [tileLiteral] packs it as `"x,y,plane"`. A tile is a type the node
     * pack declares now, so it has no [PinType] to be listed under and reaches the declared-type path
     * instead.
     */
    private fun accepts(type: PinType, value: Any?): Boolean = when (type) {
        PinType.STRING, PinType.ENUM -> value is String
        PinType.BOOL -> value is Boolean
        // A whole number written into a float pin is fine — `4` has no type until it is placed, so in a
        // FLOAT slot it is simply how four is written. Distinct from the INT → FLOAT widening `canConnect`
        // allows, which is about a WIRE and costs an `Op.TOF` at run time: there is no wire here, so the
        // compiler stores `4.0` in the constant pool instead and nothing runs at all.
        PinType.FLOAT -> value is Double || value is Float || value is Int || value is Long
        PinType.INT -> value is Int || value is Long
        // Entity, widget, item-ref and anything added later: handles, never typed in, so there is no
        // literal spelling to check and refusing one would be guessing.
        else -> true
    }

    private fun kindOf(value: Any?): String = when (value) {
        is String -> "a String"
        is Boolean -> "a Bool"
        is Double, is Float -> "a Float"
        is Int, is Long -> "an Int"
        else -> "that"
    }

    /**
     * A list literal takes its element type from the pin it is being handed to.
     *
     * `[…]` says what is in the list and not what kind of list it is, and those are different questions: the
     * ids `[1783, 1781, 1775]` are a list of **items** when a Drop Any is asked to drop them and a list of
     * ints anywhere else. Guessing from the contents — which is all [elementTypeName] can do — gets the
     * common case wrong in both directions: those ids came back `List<Int>` and would not wire into
     * `List<Item>`, and `[text(…), text(…)]` came back `List<Int>` because a call is not a literal and there
     * was nothing to guess from at all.
     *
     * So the destination decides, and the contents are only the fallback for a list that feeds nothing
     * type-bearing. This is what makes `[…]` **faithful sugar**: the type it drops is recoverable from
     * context, so a printed graph reads back as the same graph. Every real script found by
     * `RealGraphsTest` failed on exactly this.
     *
     * Only the slot pins' *types* change — their names are `1`…`n` either way — so retyping after the slots
     * were filled is safe, and doing it here means it applies wherever a list is passed rather than only in
     * the one place [listLit] is called from.
     */
    private fun retypeList(src: FromPin, toNode: Int, toPin: String) {
        val list = nodeOf(src.node)
        if (list.type != BuiltinNodes.LITERAL_LIST) return
        val spec = resolved(toNode).input(toPin) ?: return
        val target = effectivePinType(nodeOf(toNode), spec, ::declaredVarType) { n, p -> feeding(n, p) }
        // An UNCONSTRAINED list pin — `List` with no element type — genuinely does not say, and a wildcard
        // says even less. Leave the guess alone rather than replacing it with a worse one.
        //
        // **A type VARIABLE says least of all**, and it is the one that does damage rather than nothing:
        // the `self` pin of `fn LIST<T>.head(self)` is a `LIST<T>` at the moment the receiver is wired
        // (nothing is bound yet — the wire being made is what will bind it), so without this `[1, 2, 3]`
        // was retyped to a list of `T`, a record nobody declares. The binding then read `T` back off it
        // and `head()` handed out a `T` at every call site. The guess is what should stand: it is INT, and
        // INT is the answer.
        val of = target.takeIf { it.isList }?.of?.takeIf { !it.variable } ?: return
        // **Retyping is not the same as accepting.** Everything above is about a guess that says too
        // little; this is about one that says something ELSE. `[1, 2]` handed to a `LIST<STRING>` was
        // silently relabelled a list of strings, and `canConnect` then compared the relabelled type against
        // itself and agreed — so the one place element types were not enforced was the shortest way to
        // write the call, which is also the way anyone writes it first. Binding the same literal to a local
        // first WAS caught, which is what made the hole look like a rule nobody could quite pin down.
        //
        // The guess cannot simply be checked with `canConnect`, because the whole reason this function
        // exists is that it would say no to the case it is for: `[1783, 1781]` guesses INT, a Drop Any
        // wants `LIST<ITEM>`, and `canConnect(INT, ITEM)` is false. What is true of that pair and false of
        // INT-into-STRING is that both are held the same way at run time. So the test is STORAGE, not type:
        // retype when the destination is a different reading of the same stored value, refuse when it is a
        // different thing entirely and let the wire report it with the message it already has.
        val guess = BuiltinNodes.listElementType(list.literals[BuiltinNodes.LIST_OF]?.toString())
        val from = guess.builtin?.let { dev.ziggle.vscript.model.storageOf(it) }
        val to = of.builtin?.let { dev.ziggle.vscript.model.storageOf(it) }
        // A declared type on either side is a record, and records are compared by name, not by storage:
        // `canConnect` handles those exactly, so leave them to it. A guess of `Wildcard` — a mixed or empty
        // literal, or one built from calls — genuinely says nothing and takes the destination as before.
        if (guess.declared || of.declared) {
            if (guess.declared != of.declared || !canConnect(guess, of)) return
        } else if (from != null && to != null && from != to) {
            return
        }
        list.literals[BuiltinNodes.LIST_OF] = dev.ziggle.vscript.model.Types.label(of)
    }

    /**
     * The declared type a value carries, when it carries one — for `.field`, `with`, and `let {…}`.
     *
     * Asks [effectivePinType] rather than reading the pin directly, which is what makes a record survive a
     * `let`: a Hold's pins are wildcards so one node can serve every type, and that shared rule is where
     * "typed like whatever it holds" is decided. Walking the wire here instead would have been a second
     * copy of it, and the two would eventually disagree.
     */
    /**
     * `t.anchor` — one column of an enum's table, when [target] carries an enum that declares fields.
     *
     * Null when it does not, so the caller falls through to the record reading and reports its own error.
     * An enum WITHOUT fields also returns null, and deliberately: `Phase.Chop.foo` should say "this has no
     * field 'foo'" like anything else, not something about tables.
     */
    private fun enumFieldOf(target: FromPin, e: MemberExpr): Src? {
        val t = enumOf(target) ?: return null
        if (t.fields.isEmpty()) return null
        val field = t.field(e.member)
            ?: t.fields.firstOrNull { Names.pinText(it.name) == e.member }
        if (field == null) {
            err(
                e.span,
                "'${t.name}' has no field '${e.member}' — it has ${t.fields.joinToString(", ") { it.name }}",
            )
            return Inline(null)
        }
        val get = node(BuiltinNodes.ENUM_FIELD, e.span)
        nodeOf(get).literals[BuiltinNodes.ENUM_TYPE] = t.name
        nodeOf(get).literals[BuiltinNodes.STRUCT_FIELD] = field.name
        wire(target, get, "Value", e.span)
        return FromPin(get, field.name)
    }

    private fun structNameOf(src: FromPin): String? {
        val pin = resolved(src.node).output(src.pin) ?: return null
        val t = effectivePinType(nodeOf(src.node), pin, ::declaredVarType) { n, p -> feeding(n, p) }
        return visibleStructs().firstOrNull { it.name.equals(t.name, true) }?.name
    }

    /**
     * The enum a pin carries, or null when it carries something else.
     *
     * Through [effectivePinType] rather than off the declared pin, for exactly the reason [structNameOf]
     * does the same: a Hold's pin says WILDCARD and the type has to be traced back to what fed it, so
     * `let t = Target.WildKebbit` followed by `t.count` would otherwise find no enum and report a missing
     * field on a value that has one.
     */
    private fun enumOf(src: FromPin): EnumType? {
        val pin = resolved(src.node).output(src.pin) ?: return null
        val t = effectivePinType(nodeOf(src.node), pin, ::declaredVarType) { n, p -> feeding(n, p) }
        return visibleEnums().firstOrNull { it.name.equals(t.name, true) }
    }

    /**
     * The declared type of a variable this document can read, local or imported.
     *
     * An imported one is stored `alias::Trips` and its type is written in the OTHER document's vocabulary,
     * so a record type has to be requalified on the way out — `@default` there is `@1::@default` here.
     * Without that, reading a field off an imported record reported "this has no field", because the type
     * traced back to a name this document has never heard of.
     */
    private fun declaredVarType(name: String): TypeRef? {
        variables.firstOrNull { it.name == name }?.let { return it.type }
        val q = QualName.parse(name)
        val alias = q.module ?: return null
        val doc = importedDocs[alias] ?: return null
        val e = exportsIn(doc)[q.name] ?: return null
        val v = e.owner.variable(e.name) ?: return null
        return qualifyThrough(alias, e.owner, v.type)
    }

    /**
     * Does a type by this name exist — this document's, or the language's own?
     *
     * The predicate `typeParametersOf` asks to tell `fn List<Entity>.closest` from `fn List<T>.first`. Only
     * the local vocabulary, and deliberately: an imported type is always written `alias::Name`, which the
     * qualifier rules out on its own, so consulting the imports here would buy nothing and would make the
     * answer depend on resolution order.
     */
    private fun declaresType(name: String): Boolean =
        visibleStructs().any { it.name.equals(name, true) } || visibleEnums().any { it.name.equals(name, true) }

    /** What the wire into [nodeId]'s [pin] carries — the graph half of [effectivePinType]'s question. */
    private fun feeding(nodeId: Int, pin: String, depth: Int = 0): TypeRef? {
        if (depth > 32) return null
        // Nothing wired in: whatever was TYPED in, when its kind decides a type — see `literalTypeOf`.
        val l = links.firstOrNull { it.toNode == nodeId && it.toPin == pin }
            ?: return dev.ziggle.vscript.model.literalTypeOf(nodes.firstOrNull { it.id == nodeId }?.literals?.get(pin))
        val out = resolved(l.fromNode).output(l.fromPin) ?: return null
        return effectivePinType(nodeOf(l.fromNode), out, ::declaredVarType) { n, p -> feeding(n, p, depth + 1) }
    }

    private fun literalOf(e: Expr?): Src? = when (e) {
        null -> null
        is LiteralExpr -> Inline(e.value)
        is CallExpr -> if (e.name == "tile") tileLiteral(e) else null
        // `var State: Phase = Phase.Chop`. A member IS a value written out — the whole point of lowering one
        // to its name — so it belongs on the declaration rather than becoming an initialiser statement that
        // the printer then has to put back. Only a real member: an unknown one falls through to the
        // initialiser path, where lowering the expression reports it against the right span.
        is MemberExpr -> (e.target as? NameExpr)
            ?.let { enumNamed(it.module, it.name) }
            ?.member(e.member)
            ?.let { Inline(it) }
        // A list variable's default — `var Drops: List<Item> = [207, 211, …]`. Not a list NODE: a variable's
        // default is a value the document stores, and there is nowhere to hang a node off a declaration. Only
        // when every element is itself a constant, which is the only kind of default there can be; anything
        // else needs a graph to compute it, and the answer to that is an assignment in `on start`.
        is ListLitExpr -> e.items.map { (it as? LiteralExpr)?.value ?: return null }.let { Inline(it) }
        else -> null
    }

    /**
     * What an enum's row may hold: [literalOf], plus two folds that only a DECLARATION needs.
     *
     * Kept apart from `literalOf` rather than folded into it, and the difference matters. `literalOf` also
     * decides whether a `var`'s default can be STORED or has to become an `@init` assignment — so widening
     * it would make `var Home: TILE = vars::HOME` store the tile and print back as `tile(…)`, losing the
     * name that was typed. A variable has somewhere to run; an enum has not, so only the enum needs these.
     *
     * The cost here, stated because it is real and one-directional: a folded const prints back as its
     * VALUE. `X(NEEDED)` comes back as `X(30)`. A record literal does not have that problem — it prints
     * back as itself — so only the name-shaped cases lose their spelling.
     */
    /**
     * The destination an enum ROW's [i]th value is written into — that column, by position.
     *
     * Null past the end, which is a row with more values than columns: reported where the value is rather
     * than invented a type for.
     */
    private fun columnWant(d: EnumDecl, i: Int): Expected? =
        d.fields.getOrNull(i)?.let { Expected(typeRef(it.type), it.name, d.name) }

    private fun enumValueOf(e: Expr, want: Expected? = null): Src? = literalOf(e) ?: when (e) {
        // `Vec2 { x: 1, y: 2 }` — a record whose every field is itself written out, folded to the value a
        // run would build. Nests, so `Box { origin: Vec2 { … } }` folds too.
        //
        // This is a VALUE, not a node: a `struct.make` would need somewhere to hang, and a declaration has
        // nowhere. It round-trips because the printer knows how to write a record back out — which is what
        // separates this from the cases below that are refused.
        is StructLitExpr -> {
            val t = structNamed(e.module, e.type)
            when {
                t == null -> null
                // Every field, in DECLARATION order — the order `StructValue` is positional in — and
                // filled from the literal by name, so writing them out of order still folds correctly.
                else -> {
                    val values = arrayOfNulls<Any?>(t.fields.size)
                    for ((i, f) in t.fields.withIndex()) {
                        val written = e.fields.firstOrNull {
                            Names.pinText(f.name) == it.name || f.name.equals(it.name, true)
                        }
                        // A field left out takes its DECLARED default, and its type's zero when there is
                        // none — the same order `struct.make` resolves an unsupplied pin in, so a folded
                        // record and a built one agree about what was left out.
                        val v = if (written != null) {
                            // **No destination is handed down here, deliberately.** A field IS one, and
                            // passing it would fold a lambda inside a record literal to a function NAME —
                            // which only an enum COLUMN knows how to turn back into a callable, because
                            // `GraphCompiler` converts a function-typed column and does not walk inside a
                            // folded record. `val H: Hooks = Hooks { ready: { … } }` would then hold the
                            // string "@lambda1" at run time and calling it would fault. Left unfolded, it
                            // takes the ordinary variable path and its lambda lowers as a reference, which
                            // is what it needs to be. The cost is stated in GAPS 25: a record literal
                            // written INSIDE an enum row cannot contain a lambda.
                            (enumValueOf(written.value) as? Inline)?.value ?: return null
                        } else {
                            f.default
                        }
                        values[i] = v
                    }
                    Inline(dev.ziggle.vscript.vm.StructValue(t.name, t.fields.map { it.name }, values))
                }
            }
        }
        // A `const`, by name. Read off the DECLARATION rather than through the binding, because enums are
        // lowered in a pass before any const is bound — and a const is itself a value written out, so
        // there is nothing to run either way.
        //
        // The cost, stated because it is real: the value is folded, so the printer gives back `30` rather
        // than the name that was typed. A const is a name for a literal, and here only the literal
        // survives.
        is NameExpr -> {
            // `vars::NEEDED` — an imported const. A const is a literal NODE carrying `@name`, not a field
            // on the document, so that case reads it off the imported graph rather than out of a table.
            // It is what makes a shared constants document usable from an enum's table, which is the case
            // this whole fold exists for: `hunter/utils/vars.vs` is where the anchors live.
            val m = e.module
            // ...or a FUNCTION, by name — which is what makes a column of HANDLERS possible.
            //
            // Folded to the NAME rather than to a value, because the value is an index into the linked
            // program and no program exists yet. `GraphCompiler` turns it into a `FunctionValue` on the
            // way into the constant pool, which is the same place and the same moment a TILE's string
            // becomes a record. The column's declared type is what says which of the two a string is.
            if (m == null) {
                constDecls[e.name]?.let { literalOf(it) }
                    ?: e.name.takeIf { it in enumPassFunctions }?.let { Inline(it) }
            } else {
                importedConst(m, e.name)?.let { Inline(it) }
                    ?: importedDocs[m]?.let { doc -> exportsIn(doc)[e.name] }
                        ?.let { ex -> ex.owner.function(ex.name) }
                        ?.let { Inline("$m${QualName.SEP}${e.name}") }
            }
        }
        // A lambda, folded to the name of a function synthesised for it — see [foldLambda]. Only where the
        // destination says it is a function; with nothing to read its parameters from there is no shape.
        is LambdaExpr -> want?.let { foldLambda(e, it) }
        else -> null
    }


    /**
     * The value of an imported `const`, or null when the alias or the name names something else.
     *
     * Only a CONST. An imported `var` has a slot that a run writes to, so its value is not a property of
     * the document and cannot be folded into one — that case falls through and is refused with the message
     * about being written out, which is the true answer.
     */
    private fun importedConst(alias: String, name: String): Any? {
        val decl = importedConstDecl(alias, name) ?: return null
        val owner = importedDocs[alias]?.let { exportsIn(it)[name] }?.owner
        val value = decl.literals["Value"]
        return if (owner == null) value else requalifiedValue(value, alias, owner)
    }

    /**
     * A folded RECORD, with the function references inside it rewritten into this document's spelling.
     *
     * **A name is how a function reference survives folding, and a name means different things on either
     * side of an import.** `leaf` stores `Hooks { at: twice }` as the string `twice`, because that is what
     * it calls the function; an importer reaches the same function as `leaf::twice`, and a value copied
     * across unchanged sends the compiler looking for a `twice` this document does not have — reported as
     * "function 'twice' has no Inputs node".
     *
     * The same rewrite `enumValueOf` already does for a function named DIRECTLY in a row; this is it done
     * one level in, for the fields of a record that folded. Records nest, so this does too.
     *
     * Only unqualified names are touched: one the owner had itself imported is already qualified relative
     * to the owner, which this cannot retarget — the same limit [requalified] states for types.
     */
    private fun requalifiedValue(value: Any?, alias: String, owner: Graph, depth: Int = 0): Any? {
        if (depth > 16) return value
        val record = value as? dev.ziggle.vscript.vm.StructValue ?: return value
        // **This document's scope first, the owner's second.** A record's TYPE is often declared in
        // neither the owner nor here but in a third document both import — a contract beside the
        // activities that use it — and the owner cannot resolve a name it merely imported. Whoever can
        // see the fields is who is asked.
        // **The type is spelled in the OWNER's vocabulary, so the qualifier is the owner's and means
        // nothing here.** A record declared in `core/activity` and reached through a bare import is
        // `@1::Hooks` inside a document that imported it first and `@2::Hooks` inside one that imported
        // something else first — so a verbatim lookup succeeds or fails on the accident of import ORDER.
        // It did exactly that: every activity requalified except `tithe`, which imports `core/wait` ahead
        // of `core/activity`, and its hooks alone stayed bare names.
        //
        // The bare name is what crosses: `Hooks` is one type however each document reached it, and this
        // only needs its FIELDS, to know which of them are functions.
        val simple = QualName.parse(record.type).name
        val t = structNamed(null, record.type)
            ?: structNamed(null, simple)
            ?: owner.struct(record.type)
            ?: owner.struct(simple)
            ?: return record
        var out = record
        for (i in 0 until record.size) {
            val ft = t.fields.getOrNull(i)?.type ?: continue
            val was = record[i]
            val now = when {
                ft.isFunction && was is String && !QualName.parse(was).isQualified ->
                    "$alias${QualName.SEP}$was"
                was is dev.ziggle.vscript.vm.StructValue -> requalifiedValue(was, alias, owner, depth + 1)
                else -> was
            }
            if (now !== was) out = out.with(i, now)
        }
        return out
    }

    /**
     * The literal NODE an imported `const` is declared by, or null when the name is not one.
     *
     * The node rather than its value, because its TYPE is information nothing else can recover: a TILE and
     * a plain string are both stored as text, so a reader that saw only `"3200,3200,0"` would have to guess
     * and would guess wrong. Reusing the declaring document's own literal type makes the copy exactly as
     * typed as the original, which is what lets it wire into a TILE pin.
     */
    private fun importedConstDecl(alias: String, name: String): Node? {
        val doc = importedDocs[alias] ?: return null
        // The document that DECLARES it, so a const reached across a barrel finds its literal node.
        val e = exportsIn(doc)[name] ?: return null
        return e.owner.nodes.firstOrNull {
            it.type in BuiltinNodes.LITERALS && it.literals[CONST_NAME]?.toString() == e.name &&
                it.literals[CONST_EXPORTED] == true
        }
    }

    /**
     * `vars::Limit` — an imported `const`, standing in for its value.
     *
     * **A const can cross where a `var` cannot, and the difference is what a value belongs to.** A
     * variable has a slot a run writes to, so its value is a property of the RUN and there is nothing to
     * fold; a const is a property of the document, so a copy of it here means exactly what the original
     * means there. Until this existed, `vars.vs` had to declare everything shared as a `var` and said so:
     * "a const is a literal NODE, and an import cannot name one".
     *
     * Marked with [IMPORTED_CONST] so the printer writes `vars::Limit` back rather than the value — the
     * round trip is what makes this admissible sugar at all — and so it is not mistaken for a declaration
     * of that name in this document.
     */
    /**
     * A declared type, rewritten from the DECLARING document's spelling into this one's.
     *
     * **A type name means different things on either side of an import, and a copied literal carries the
     * wrong one.** `core/activity` calls its record `Activity`, because it declares it; a document that
     * imports it reaches the same record as `@1::Activity`, the anonymous alias a bare `import` gets. So
     * a folded `val Solo: Activity = …` copied across arrives claiming to be `Activity`, nothing here
     * declares that, and wiring it into a parameter is refused with "cannot wire Activity into
     * @1::Activity" — two spellings of one type, which is the shape of GAPS 22.
     *
     * Only names the OWNER declares are touched, and only where they are unqualified: a builtin (`INT`,
     * `LIST<TILE>`) means the same thing everywhere and must be left alone, and a name the owner had
     * itself imported is already qualified relative to the owner — which this cannot yet retarget, so it
     * is left as it was rather than rewritten into something worse.
     */
    private fun requalified(type: String, alias: String, name: String): String {
        val owner = importedDocs[alias]?.let { exportsIn(it)[name] }?.owner ?: return type
        return Regex("[A-Za-z_][A-Za-z0-9_]*").replace(type) { m ->
            val n = m.value
            val declared = owner.structExactly(n) != null || owner.enumExactly(n) != null
            if (declared) "$alias${QualName.SEP}$n" else n
        }
    }

    private fun importedConstRef(alias: String, name: String, e: NameExpr): Src? {
        val decl = importedConstDecl(alias, name) ?: return null
        val id = node(decl.type, e.span)
        // The same rewrite [importedConst] does — see [requalifiedValue]. A reference copied with the
        // owner's spelling names a function this document has no way to reach.
        nodeOf(id).literals["Value"] = importedDocs[alias]?.let { exportsIn(it)[name] }?.owner
            ?.let { requalifiedValue(decl.literals["Value"], alias, it) }
            ?: decl.literals["Value"]
        nodeOf(id).literals[IMPORTED_CONST] = "$alias${QualName.SEP}$name"
        // ...and the type it was DECLARED with, for the same reason the node type is reused above: a
        // record folds to a value that cannot say what shape it is, so a copy without this reports
        // WILDCARD and `leaf::Impl.ready` is refused with "this has no field". See [CONST_TYPE].
        // ...and the type it was DECLARED with, requalified into THIS document's namespace — see
        // [CONST_TYPE] and [requalified].
        decl.literals[CONST_TYPE]?.let {
            nodeOf(id).literals[CONST_TYPE] = requalified(it.toString(), alias, name)
        }
        return FromPin(id, "Value")
    }

    /** Every top-level `const`, by name — see [literalOf]. Filled before anything is lowered. */
    /** One unqualified imported name: which import it came through, and what that document calls it. */
    private class Imported(val alias: String, val name: String, val ref: String)

    /**
     * The unqualified names an import put into this document — see [resolveUnqualifiedImports].
     *
     * Read where a bare name fails to resolve locally, and turned into the QUALIFIED name the graph stores,
     * so nothing downstream learns that unqualified imports exist. The printer takes it back off.
     */
    private val unqualified = LinkedHashMap<String, Imported>()

    private val constDecls = HashMap<String, Expr>()

    /**
     * A constant that is its own node, so several readers share one editable slot. See [ConstDecl].
     *
     * The NAME is kept under `@name`, the same `@`-prefixed metadata channel annotations use. A literal
     * node has no field for one — every literal descriptor is shared, so a `Name` pin would appear on every
     * constant in the language — and without somewhere to put it a `const` could be written but never
     * printed back, which is the round trip failing for a construct that exists only to be named.
     */
    private fun literalNode(value: Any?, from: Expr, ann: Annotations, name: String?): Int {
        val type = when ((from as? LiteralExpr)?.kind) {
            LiteralKind.INT -> BuiltinNodes.LITERAL_INT
            LiteralKind.FLOAT -> BuiltinNodes.LITERAL_FLOAT
            LiteralKind.STRING -> BuiltinNodes.LITERAL_STRING
            LiteralKind.BOOL -> BuiltinNodes.LITERAL_BOOL
            LiteralKind.COLOR -> BuiltinNodes.LITERAL_COLOR
            else -> if (from is CallExpr && from.name == "tile") BuiltinNodes.LITERAL_TILE else BuiltinNodes.LITERAL
        }
        val id = node(type, from.span, ann)
        nodeOf(id).literals["Value"] = value
        if (name != null) nodeOf(id).literals[CONST_NAME] = name
        return id
    }

    private fun pushScope() { scopes += HashMap() }
    private fun popScope() { scopes.removeAt(scopes.size - 1) }
    private fun bind(name: String, src: Src) { scopes.lastOrNull()?.put(name, src) }

    /**
     * [bind] a name the author has just DECLARED, warning when it hides a graph variable.
     *
     * Nothing is ambiguous to the compiler — the local wins, everywhere, and that is a rule with no
     * exceptions. It is ambiguous to a reader, who scrolls past the declaration, sees `N`, and thinks of
     * the variable. Everything else about names here is built so that cannot happen: an import carries a
     * mandatory alias precisely so there is never a question about which `Total` is meant. This was the one
     * place the rule lapsed.
     *
     * **Only for declarations.** Plain [bind] is also how `!= null` narrowing and `?.` rebind a name that
     * is deliberately the same one, and how `self` and a function's parameters are seeded. Warning there
     * would report the language's own machinery as a mistake.
     */
    private fun bindDeclared(name: String, src: Src, span: Span) {
        if (variables.any { it.name == name }) {
            warn(span, "'$name' is also a graph variable — inside here the name means this one")
        }
        bind(name, src)
    }
    private fun lookup(name: String): Src? {
        for (i in scopes.indices.reversed()) scopes[i][name]?.let { return it }
        return null
    }

    /**
     * Give every node a position.
     *
     * Anything with `@at` keeps it. The rest are arranged headlessly — `AutoLayout.arrange` defaults its
     * measured sizes, so a placement without a canvas is possible; it simply lacks the pin-to-pin
     * straightening the editor restores once it can measure. Arranged **per group**, because `arrange`
     * lays out one group at a time and passing the whole document strews a function's body across the flow.
     */
    private fun placed(graph: Graph): Graph {
        if (graph.nodes.all { it.x != 0f || it.y != 0f }) return graph
        val groups = graph.nodes.map { it.function }.distinct()
        var originY = 0f
        for (g in groups) {
            val positions = dev.ziggle.vscript.layout.AutoLayout.arrange(
                graph, catalog, originX = 0f, originY = originY, include = { it.function == g },
            )
            for ((id, xy) in positions) {
                val n = graph.node(id) ?: continue
                if (n.x == 0f && n.y == 0f) { n.x = xy.first; n.y = xy.second }
            }
            originY += 600f
        }
        return graph
    }

    companion object {
        /**
         * How many times [lower] will re-run before giving up on settling purity.
         *
         * Two is the expected worst case for real code — one optimistic pass, one to confirm what it
         * demoted — because `isPureFunction` already follows a Call into its callee and so demotes a whole
         * chain in one go. The cap is generous enough that hitting it means a bug in that reasoning rather
         * than an unusual document.
         */
        private const val MAX_PURITY_PASSES = 8

        /** Where a `const`'s name lives — see [literalNode]. */
        const val CONST_NAME = BuiltinNodes.CONST_NAME

        /**
         * Beside [CONST_NAME] on the same literal node: this `const` said `export`.
         *
         * A const is the one exportable thing with no record on the document to carry a flag — it IS
         * a literal node — so the flag rides in the same generic literals map its name already does.
         * Absent means not exported, which is the new default and so needs nothing written.
         */
        const val CONST_EXPORTED = BuiltinNodes.CONST_EXPORTED

        /**
         * Beside [CONST_NAME] on the same literal node: the type this `val` was WRITTEN with.
         *
         * **A folded `val` used to lose its annotation, so writing one changed what the declaration
         * meant.** `val Home: TILE = tile(…)` became a graph variable while `val Home = tile(…)`
         * became a constant — and only the constant could be named by an enum row or crossed an
         * import as a value. Adding the type for a reader silently took the declaration out of every
         * place a constant is allowed, which is a surprising thing for documentation to do.
         *
         * So the fold no longer asks whether a type was written; it keeps it here instead, for the
         * printer to give back. Absent means none was written, which is what most constants are.
         */
        const val CONST_TYPE = BuiltinNodes.CONST_TYPE

        /**
         * The qualified name of an IMPORTED `const` this literal is standing in for — `vars::Limit`.
         *
         * Separate from [CONST_NAME] because the two mean opposite things to the printer: a literal
         * carrying a const name is a DECLARATION to write out at the top of the document, and one carrying
         * this is a REFERENCE to somebody else's. Sharing the key would print `const vars::Limit = 5` into
         * the importing file, declaring a name it does not own.
         *
         * A const folds to its value where a `var` cannot, which is the whole asymmetry: a variable has a
         * slot a run writes to, so its value is not a property of the document. That is why an imported
         * `var` still resolves to a Get and only a const arrives as a literal.
         */
        const val IMPORTED_CONST = dev.ziggle.vscript.model.GraphMarks.IMPORTED_CONST

        /** A `for` loop's variable names, on the ForEach node — see [forStmt]. */
        const val LOOP_ELEMENT = dev.ziggle.vscript.model.GraphMarks.LOOP_ELEMENT
        const val LOOP_INDEX = dev.ziggle.vscript.model.GraphMarks.LOOP_INDEX

        /**
         * Marks a Set node the printer writes back as a variable's default — see [emitInits].
         *
         * Defined in [BuiltinNodes] rather than here because the COMPILER reads it too, to find where a
         * document's initialiser prologue ends; kept spelled here so the printer's references still read
         * as `Lower.INIT_MARK`, which is where the mark is written.
         */
        const val INIT_MARK = BuiltinNodes.INIT_MARK

        /**
         * Literal keys that are a construct's own syntax rather than metadata to print as an `@`.
         *
         * All `@`-prefixed, because that is the one namespace no pin can collide with, and all consumed by
         * a recognizer that writes them back out as part of the thing they name — so printing them as
         * annotations as well would say everything twice.
         */

        /** Moved to [dev.ziggle.vscript.model.GraphMarks]; kept here so existing callers still resolve. */
        val SYNTACTIC_LITERALS: Set<String> get() = dev.ziggle.vscript.model.GraphMarks.SYNTACTIC_LITERALS

        val BINARY: Map<BinaryOp, String> = mapOf(
            BinaryOp.ADD to BuiltinNodes.ADD,
            BinaryOp.SUB to BuiltinNodes.SUB,
            BinaryOp.MUL to BuiltinNodes.MUL,
            BinaryOp.DIV to BuiltinNodes.DIV,
            BinaryOp.MOD to BuiltinNodes.MOD,
            BinaryOp.EQ to BuiltinNodes.EQ,
            BinaryOp.NE to BuiltinNodes.NE,
            BinaryOp.LT to BuiltinNodes.LT,
            BinaryOp.LE to BuiltinNodes.LE,
            BinaryOp.GT to BuiltinNodes.GT,
            BinaryOp.GE to BuiltinNodes.GE,
        )
    }
}
