package dev.ziggle.vscript.text

import dev.ziggle.vscript.compile.Severity
import dev.ziggle.vscript.lang.AsExpr
import dev.ziggle.vscript.lang.AssertStmt
import dev.ziggle.vscript.lang.AssignStmt
import dev.ziggle.vscript.lang.BinaryExpr
import dev.ziggle.vscript.lang.BinaryOp
import dev.ziggle.vscript.lang.Block
import dev.ziggle.vscript.lang.BreakStmt
import dev.ziggle.vscript.lang.CallExpr
import dev.ziggle.vscript.lang.ConstDecl
import dev.ziggle.vscript.lang.ConstStmt
import dev.ziggle.vscript.lang.ContinueStmt
import dev.ziggle.vscript.lang.EntryDecl
import dev.ziggle.vscript.lang.EnumDecl
import dev.ziggle.vscript.lang.Expr
import dev.ziggle.vscript.lang.ExprBlockStmt
import dev.ziggle.vscript.lang.ElvisExpr
import dev.ziggle.vscript.lang.ExprStmt
import dev.ziggle.vscript.lang.FieldAssignStmt
import dev.ziggle.vscript.lang.IfLetStmt
import dev.ziggle.vscript.lang.ExportListDecl
import dev.ziggle.vscript.lang.ImportDecl
import dev.ziggle.vscript.lang.Field
import dev.ziggle.vscript.lang.FnDecl
import dev.ziggle.vscript.lang.ForStmt
import dev.ziggle.vscript.lang.IfStmt
import dev.ziggle.vscript.lang.Arg
import dev.ziggle.vscript.lang.IndexAssignStmt
import dev.ziggle.vscript.lang.IndexExpr
import dev.ziggle.vscript.lang.IsExpr
import dev.ziggle.vscript.lang.SafeAccessExpr
import dev.ziggle.vscript.lang.SafeItExpr
import dev.ziggle.vscript.lang.SequenceStmt
import dev.ziggle.vscript.lang.WithExpr
import dev.ziggle.vscript.lang.ListLitExpr
import dev.ziggle.vscript.lang.LambdaExpr
import dev.ziggle.vscript.lang.LetStmt
import dev.ziggle.vscript.lang.LiteralExpr
import dev.ziggle.vscript.lang.LiteralKind
import dev.ziggle.vscript.lang.MemberExpr
import dev.ziggle.vscript.lang.NameBinding
import dev.ziggle.vscript.lang.NameExpr
import dev.ziggle.vscript.lang.NotExpr
import dev.ziggle.vscript.lang.Program
import dev.ziggle.vscript.lang.ReExportDecl
import dev.ziggle.vscript.lang.ReturnStmt
import dev.ziggle.vscript.lang.Span
import dev.ziggle.vscript.lang.Stmt
import dev.ziggle.vscript.lang.declaredName
import dev.ziggle.vscript.lang.SingleDecl
import dev.ziggle.vscript.lang.StructLitExpr
import dev.ziggle.vscript.lang.ValDecl
import dev.ziggle.vscript.lang.TryStmt
import dev.ziggle.vscript.lang.TupleBinding
import dev.ziggle.vscript.lang.TupleEntry
import dev.ziggle.vscript.lang.TernaryExpr
import dev.ziggle.vscript.lang.TypeDecl
import dev.ziggle.vscript.lang.TypeExpr
import dev.ziggle.vscript.lang.VarDecl
import dev.ziggle.vscript.lang.WhenStmt
import dev.ziggle.vscript.lang.WhileStmt
import dev.ziggle.vscript.model.HostRecords
import dev.ziggle.vscript.model.ModuleNames
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.hasTypeVariables
import dev.ziggle.vscript.model.substitute
import dev.ziggle.vscript.model.TypeRef

/**
 * Names and types, worked out once, for the whole document.
 *
 * **The pass `Lower` never got to be.** Lowering resolves names and types too, but it does it while
 * building nodes, so its conclusions are recorded as graph shape — a pin's type, a literal node's
 * requalified type string — and anything that wants to ASK becomes a search over that shape. That is the
 * shared root of a family of defects: a type reached through two imports becoming two types, a folded
 * record's type resolving in seven documents and not the eighth, a computed FLOAT local quietly holding an
 * Int. Here a type is an object, a name points at the binding it means, and neither can be spelled two
 * ways.
 *
 * **It collects rather than throws.** An editor wants every error in the file, not the first one; and a
 * body whose type could not be decided still has names worth resolving for the rest of the pass. So every
 * complaint lands in [Resolution.diagnostics] and the walk continues with `WILDCARD` — which
 * [assignable] treats as unknown, so one failure does not cascade into a page of false ones.
 *
 * Not here yet, and refused by name rather than ignored: imports, lambdas, lists, maps, enums a document
 * declares, generics, `for`, `try`, and extension functions. Each arrives with its own commit.
 */
class Resolver(
    private val natives: NativeTable,
    /** Where an `import` is resolved. [ModuleSet] resolves leaves before importers — see its note. */
    private val modules: ModuleSet? = null,
    /**
     * The one globals layout every document in the run shares.
     *
     * Passed in rather than owned, because two modules each numbering their variables from zero would
     * have the second silently reading and writing the first's — they are one array at run time.
     */
    private val globals: GlobalSlots = GlobalSlots(),
    /** How this document was reached — see [Resolution.ref]. */
    private val ref: String = Resolution.ROOT,
) {

    private val typeOf = LinkedHashMap<Expr, TypeRef>()
    private val bindingOf = LinkedHashMap<NameExpr, Binding>()
    private val calleeOf = LinkedHashMap<CallExpr, Callee>()
    private val argumentsOf = LinkedHashMap<CallExpr, List<Expr?>>()
    private val localOf = LinkedHashMap<LetStmt, LocalBinding>()
    private val targetOf = LinkedHashMap<AssignStmt, Binding>()
    private val paramsOf = LinkedHashMap<FnDecl, List<LocalBinding>>()

    /** `xs[i]` on a non-list, rewritten as `xs.get(i)` — see [Resolution.indexCallOf]. */
    private val indexCallOf = LinkedHashMap<IndexExpr, CallExpr>()

    /** `xs[i] = v` on a non-list, rewritten as `xs.set(i, v)` — see [Resolution.indexSetOf]. */
    private val indexSetOf = LinkedHashMap<IndexAssignStmt, CallExpr>()
    private val loopBindingsOf = LinkedHashMap<ForStmt, List<LocalBinding>>()
    private val boundOf = LinkedHashMap<Stmt, List<LocalBinding>>()
    /** Which RESULT slot each destructured name took — null where a slot was not bound. */
    private val slotsOf = LinkedHashMap<Stmt, List<LocalBinding?>>()
    private val resultPick = LinkedHashMap<MemberExpr, Int>()
    private val templateArgs = LinkedHashMap<CallExpr, List<Expr>>()
    private val lambdaParamsOf = LinkedHashMap<LambdaExpr, List<LocalBinding>>()
    private val capturesOf = LinkedHashMap<LambdaExpr, MutableList<LocalBinding>>()

    /** The lambdas being resolved, innermost last — see [capture]. */
    private val openLambdas = ArrayDeque<LambdaExpr>()
    private val globalSlots = LinkedHashMap<DocumentBinding, Int>()
    private val globalInits = ArrayList<GlobalInit>()
    /** Initialisers that need every declaration in place before they can be read. */
    private val pendingInits = ArrayList<Triple<DocumentBinding, Expr, TypeRef>>()
    /**
     * Field defaults that have to be COMPUTED — resolved once, after every declaration is in.
     *
     * A default is written on the declaration and evaluated wherever a literal leaves the field out, so
     * resolving it at each of those sites would resolve it many times or — as it did — none at all: the
     * compiler emitted a call the resolver had never seen and refused it as an unknown callee.
     */
    private val pendingFieldDefaults = ArrayList<Pair<Expr, TypeRef>>()

    /**
     * One enum cell, waiting to be checked once every declaration exists.
     *
     * Checked eagerly, inside `declare`, it could only see the declarations ABOVE it — so
     * `enum Roster(target: Int) { A(Ids.Value) }` reported `nothing here is called 'Ids'` whenever `Ids`
     * happened to be written lower down, and moving the declaration cured it. That is the order-dependence
     * this resolver refuses everywhere else, met one more time.
     */
    private class PendingCell(val value: Expr, val want: TypeRef?, val column: String, val member: String)

    private val pendingCells = ArrayList<PendingCell>()
    private val diagnostics = ArrayList<TextDiagnostic>()

    /**
     * The type parameters of the function being read, if any.
     *
     * Held as state rather than threaded through every call because a type annotation can appear at any
     * depth of a signature, and every reader of one would otherwise need to be handed the set. Set and
     * cleared around a declaration and around its body, which are the only two places it is not empty.
     */
    private var typeVars: Set<String> = emptySet()

    private val records = LinkedHashMap<String, RecordType>()
    private val enums = LinkedHashMap<String, EnumType>()
    private val document = LinkedHashMap<String, Binding>()

    /** Names this document exports — filled as declarations are read, plus any `export { … }` list. */
    private val exported = LinkedHashSet<String>()
    /** Names this document forwards from somewhere else — `export * from "x"`. */
    private val reExported = LinkedHashMap<String, Binding>()
    private val reExportedRecords = LinkedHashMap<String, RecordType>()
    private val reExportedExtensions = LinkedHashMap<String, MutableList<FunctionBinding>>()
    private val reExportedEnums = LinkedHashMap<String, EnumType>()
    private val reExportedFrom = LinkedHashMap<String, Module>()
    private val reExportedTypeLevel = LinkedHashMap<String, Map<String, FunctionBinding>>()
    private val extensions = LinkedHashMap<String, MutableList<FunctionBinding>>()
    /** `fn Want.carry(…)` with no `self` — a function on the TYPE, by type name then function name. */
    private val typeLevel = LinkedHashMap<String, MutableMap<String, FunctionBinding>>()
    private var imports = ImportedNames.NONE

    /** Which extensions hand their receiver back — see [mutatingExtensions]. */
    private var mutating: Set<dev.ziggle.vscript.lang.FnDecl> = emptySet()

    /**
     * The name an anonymous `export default` is filed under, which is per-DOCUMENT.
     *
     * [ANONYMOUS_DEFAULT] is one string, and it used to be the whole mechanism: a declaration registered
     * under the same name a default import looks up, so the anonymous case needed nothing of its own.
     * That holds for one such document and breaks at two. A `TypeRef` carries a spelling rather than an
     * origin, so two anonymous defaults are one name to everything downstream, and [importedRecord] can
     * only look the spelling up again — taking whichever import it happens to meet first.
     *
     * It fails where it cannot be read: `Minigame.Wintertodt` reported `@default has no field
     * 'Wintertodt' — it has HerbRun`, listing ANOTHER document's fields in a file where both documents are
     * written correctly. Qualifying by [ref] makes the spelling unique, and the export stays reachable as
     * [DEFAULT_EXPORT] because `withDefault` aliases it there — so a default import is unaffected.
     *
     * Still not a legal identifier, so it still collides with nothing an author can write, and a
     * diagnostic that does surface it now says WHICH document it means.
     */
    private fun localName(name: String): String =
        if (name == DEFAULT_EXPORT) "$DEFAULT_EXPORT:$ref" else name

    fun resolve(program: Program): Resolution {
        // Declarations FIRST, all of them, before any body. A function may call one declared below it and
        // an entry may read a variable declared after it; reading top to bottom would make a script's
        // meaning depend on the order its author happened to type things in.
        // Imports FIRST: a declaration may name an imported type, and the module has to be there before
        // the type annotation is read rather than after it has already been reported as missing.
        imports = importsOf(program)
        // **Before any declaration is read.** Whether an extension mutates changes its SIGNATURE, and a
        // call to it may be resolved before its own declaration is reached.
        mutating = mutatingExtensions(program)
        // **Type NAMES before anything reads a type.** A record may mention itself, or two may mention
        // each other, and a field is read while the record it belongs to is still being built.
        for (d in program.decls) {
            when (d) {
                is TypeDecl -> records[localName(d.name)] =
                    RecordType(localName(d.name), emptyList(), d.span, owner = ref, params = d.params)
                is SingleDecl ->
                    records[localName(d.name)] = RecordType(localName(d.name), emptyList(), d.span, isSingle = true, owner = ref)
                is EnumDecl -> enums[d.name] = EnumType(d.name, d.members.map { it.name }, d.span, owner = ref)
                else -> Unit
            }
        }
        for (d in program.decls) declare(d)
        checkInhabitable()
        // **A name cannot be both declared here and imported BY NAME.**
        //
        // Two explicit claims on one name, which is the case the unqualified import forms already refuse —
        // and refuse for this reason: a name here does not quietly mean two things. A local declaration
        // still wins over a bare `import "x"` or an `export *` without complaint, because that is the
        // bargain that makes a barrel safe to add to; what is refused is the pair where somebody wrote
        // BOTH names down.
        //
        // Left unreported it does not read as a collision at all. A `single Hooks` beside an
        // `import { Hooks }` shadowed the import silently, so a literal meant for the imported record was
        // checked against the local one and reported `'Hooks' has no field 'n'` — a complaint about the
        // wrong declaration, in the wrong file, naming a field the author never mentioned. Worse across a
        // boundary: a binding carries its type as a SPELLING rather than an origin, so an importer
        // resolving that single's type looked `Hooks` up in its OWN scope and silently found the other one.
        for (d in program.decls) {
            val name = d.declaredName ?: continue
            val (module, exportedAs) = imports.named[name] ?: continue
            val spelling = if (exportedAs == DEFAULT_EXPORT) "default as $name" else "$name as <name>"
            error(
                d.span,
                "'$name' is already imported from \"${module.ref}\" — rename one of them: " +
                    "'import {$spelling} from \"${module.ref}\"'",
            )
        }
        // Initialisers AFTER every declaration, because one may call a function declared below it — the
        // same reason declarations are read before bodies.
        // Field defaults first: a variable's initialiser may build a record that leaves one out.
        for (c in pendingCells) {
            val got = expr(c.value, Scope(null), c.want)
            if (c.want != null && !assignable(got, c.want)) {
                error(c.value.span, "'${c.column}' is ${show(c.want)} and '${c.member}' gives ${show(got)}")
            }
        }
        for ((value, want) in pendingFieldDefaults) {
            val got = expr(value, Scope(null), want)
            if (!Unknown.isUnknown(want) && !assignable(got, want)) {
                error(value.span, "this default is ${show(got)} where ${show(want)} was declared")
            }
        }
        for ((binding, value, declared) in pendingInits) {
            val got = expr(value, Scope(null), declared)
            if (!Unknown.isUnknown(declared) && !assignable(got, declared)) {
                error(value.span, "'${binding.name}' is ${show(declared)} but starts as ${show(got)}")
            }
            // **An unannotated declaration takes the type of what it was assigned.** See
            // `DocumentBinding.type`: this pass already worked the answer out in order to check it, and
            // throwing it away left `val hook = Hooks { … }` untyped — so every `hook.field` after it
            // resolved to nothing. Only ever fills an unknown.
            if (Unknown.isUnknown(binding.type) && !Unknown.isUnknown(got)) binding.type = got
            globalInits += GlobalInit(binding, value)
        }
        orderInits()
        for (d in program.decls) {
            when (d) {
                is FnDecl -> body(d)
                is EntryDecl -> {
                    val scope = Scope(null)
                    block(d.body, scope, Loop.NONE, results = emptyList())
                }
                else -> Unit
            }
        }
        // **Forwarded names count as this document's exports.** A front door that re-exports a barrel is
        // the shape the corpus uses to keep a collision surfacing in the file that owns the vocabulary
        // rather than in whichever file happened to import both — so an importer has to see them here.
        val exportedBindings = reExported + document.filterKeys { it in exported }
        val exportedTypes = reExportedRecords + records.filterKeys { it in exported }
        // **`export default` is a SECOND NAME for a declaration, not a second declaration.** The import
        // side has always registered a default import as the name [DEFAULT_EXPORT] (`importsOf`), and
        // nothing ever wrote that key — so `import Roster from "x"` resolved its document, reported no
        // error on the import line, and then failed at every USE with `nothing here is called 'Roster'`,
        // which reads like a typo in the file that is right. It is aliased under all three surfaces
        // because `export default` may name a fn, a var, a `single` (which is a value AND a record) or
        // an enum, and a default import of one that only half-registered would fail the same silent way.
        val default = program.defaultExport?.let { localName(it) }
        return Resolution(
            program, typeOf, bindingOf, calleeOf, argumentsOf, records,
            withDefault(exportedBindings, default), withDefault(exportedTypes, default),
            enums + reExportedEnums,
            withDefault(enums.filterKeys { it in exported } + reExportedEnums, default),
            merged(extensions, reExportedExtensions),
            typeLevel + reExportedTypeLevel.filterKeys { it !in typeLevel },
            // **A re-export IS an export.** This was computed from the document's own extensions alone,
            // so a front door that forwarded a barrel offered its extensions to itself and to nobody
            // else — `Want.maybe()` worked inside `core/loadout` and not in anything that imported it.
            merged(
                extensions.mapValues { (_, fns) -> fns.filter { it.name in exported } },
                reExportedExtensions,
            ).filterValues { it.isNotEmpty() },
            imports,
            reExportedFrom.values.toList(),
            localOf, paramsOf, indexCallOf = indexCallOf, indexSetOf = indexSetOf, loopBindingsOf = loopBindingsOf, boundOf, slotsOf, resultPick, templateArgs, lambdaParamsOf, capturesOf, targetOf, globalSlots, globals.snapshot(), globalInits, diagnostics,
            allBindings = document, ref = ref,
        )
    }

    /**
     * [map] with whatever [name] points at ALSO reachable as [DEFAULT_EXPORT] — the name a default import
     * looks up.
     *
     * Null-safe on both counts: a document with no `export default`, and one whose default names something
     * that is not in this particular map (a `fn` is not in the records map), are both simply unchanged.
     */
    private fun <T> withDefault(map: Map<String, T>, name: String?): Map<String, T> {
        val target = name?.let { map[it] } ?: return map
        return map + (DEFAULT_EXPORT to target)
    }

    // ---- declarations ---------------------------------------------------------------------------------

    private fun declare(d: dev.ziggle.vscript.lang.Decl) {
        when (d) {
            is TypeDecl -> {
                if (d.isExported) exported += localName(d.name)
                // **The parameters are variables while the fields are read.** `type Pair<A, B>` means `A`
                // and `B` are placeholders, not records nobody declared — and `TypeRef.named` turns any
                // unknown name into a declared type on purpose, so without this they would resolve to
                // phantom records and the validator would report the declaration as broken.
                //
                // Scoped to this declaration, and restored after: two records may each be generic in `T`,
                // and a third that is generic in nothing must still see `T` as a mistake.
                val outer = typeVars
                typeVars = typeVars + d.params
                // The name was registered before this loop so the fields could mention it; filling them
                // in is all that is left.
                records.getValue(localName(d.name)).fields = d.fields.map { field(it) }
                typeVars = outer
            }

            // Handled by [importsOf], before any declaration is read.
            is ImportDecl -> Unit

            is ExportListDecl -> exported += d.names

            is ReExportDecl -> reExport(d)

            // `single Registry { … }` — a type AND its one value, which is why `Registry.byName` reads a
            // field and `fn Registry.register(self, …)` extends it like any other type.
            is SingleDecl -> {
                val name = localName(d.name)
                if (d.isExported) exported += name
                val record = records.getValue(name)
                record.fields = d.fields.map { field(it) }
                val binding = DocumentBinding(name, record.ref, d.span, mutable = true)
                bind(name, binding, d.span)
                // Seeded like any computed variable: the value is the record with every field at its
                // default, and a default may itself be a call.
                globalSlots[binding] = globals.allocate(null)
                pendingInits += Triple(binding, StructLitExpr(name, emptyList(), d.span), record.ref)
            }

            is EnumDecl -> {
                if (d.isExported) exported += d.name
                val columns = d.fields.map { field(it) }
                val rows = LinkedHashMap<String, List<Expr>>()
                for (m in d.members) {
                    if (m.values.size > columns.size) {
                        error(m.span, "'${m.name}' has ${m.values.size} value(s) and '${d.name}' declares ${columns.size}")
                    }
                    // Checked against the column it fills, so a mistyped row is caught where it is
                    // written rather than wherever it is read. The VALUE is folded by the compiler, which
                    // is the only place a function's chunk is known — see [EnumType.rows].
                    for ((i, v) in m.values.withIndex()) {
                        // DEFERRED, not checked here: a cell may name something declared further down, and
                        // `declare` has only reached this far. See [PendingCell].
                        pendingCells += PendingCell(
                            v, columns.getOrNull(i)?.type, columns.getOrNull(i)?.name ?: "", m.name,
                        )
                    }
                    rows[m.name] = m.values
                }
                val type = EnumType(d.name, d.members.map { it.name }, d.span, columns, rows, owner = ref)
                // **Each column becomes a hidden variable, seeded by the prologue.** A column used to be
                // folded to a constant, so only a literal, a record, a list or a function reference could
                // be written in one and anything else became null — `tile(…)` among them, silently, until
                // something read it several calls away. Evaluated at run start instead, a column may be
                // any expression an initialiser may be, which is what a Kotlin enum entry already allows.
                val vars = LinkedHashMap<String, DocumentBinding>()
                for ((i, column) in columns.withIndex()) {
                    val cells = d.members.map { m ->
                        m.values.getOrNull(i)
                            ?: column.defaultExpr
                            ?: LiteralExpr(column.default, literalKindOf(column.default), m.span)
                    }
                    val binding = DocumentBinding(
                        "${d.name}.${column.name}", TypeRef.list(column.type), d.span,
                    )
                    globalSlots[binding] = globals.allocate(null)
                    // In DECLARATION order among the other initialisers, so a column may read a variable
                    // written above it and a variable may read a column written above it — the same rule
                    // either way, and the only one that can be stated.
                    pendingInits += Triple(binding, ListLitExpr(cells, d.span), TypeRef.list(column.type))
                    vars[column.name] = binding
                }
                type.columnVars = vars
                enums[d.name] = type
            }

            is ValDecl -> {
                if (d.isExported) exported += d.name
                val declared = d.type?.let { typeOf(it) }
                val t = declared ?: (d.value as? LiteralExpr)?.let { expr(it, Scope(null)) } ?: TypeRef.WILDCARD
                val binding = DocumentBinding(d.name, t, d.span)
                bind(d.name, binding, d.span)
                global(binding, d.value, d.span)
            }

            is VarDecl -> {
                if (d.isExported) exported += d.name
                val binding = DocumentBinding(d.name, typeOf(d.type), d.span, mutable = true)
                bind(d.name, binding, d.span)
                global(binding, d.default, d.span)
            }

            is ConstDecl -> {
                if (d.isExported) exported += d.name
                // A const's type comes from its value, and the value may name something declared further
                // down — so a literal is typed now and anything else is typed with the other initialisers,
                // once every declaration is in.
                val t = (d.value as? LiteralExpr)?.let { expr(it, Scope(null)) } ?: TypeRef.WILDCARD
                val binding = DocumentBinding(d.name, t, d.span)
                bind(d.name, binding, d.span)
                global(binding, d.value, d.span)
            }

            is FnDecl -> {
                // **A `fake` belongs to a test document and nowhere else.** It exists to say "there is no
                // dashboard" while a test runs; one sitting in a library would silently replace a host for
                // every document that imported it, which is the same class of surprise as a monkey-patch
                // and has no way of announcing itself at the call site.
                if (d.fakes != null && !ModuleNames.isTestRef(ref)) {
                    error(
                        d.span,
                        "'fake ${d.fakes}' can only be written in a test document — this is '$ref', and a " +
                            "test document's name ends in '${ModuleNames.TEST_SUFFIX}'",
                    )
                }
                // **An operator is SYNTAX, so the names are a closed set.** Anything a declaration could
                // invent is syntax nobody can read: `xs[i]` has to mean the same thing in every document
                // or the bracket stops being a bracket. `get` and `set` are what the index form needs;
                // the arithmetic ones come when there is a call for them, and adding one is adding a row
                // here rather than a rule in the compiler.
                if (d.isOperator) {
                    if (d.receiver == null) {
                        error(
                            d.span,
                            "'op fn' declares an operator on a type, so it needs a receiver — " +
                                "'op fn List<T>.${d.name}(self, …)'",
                        )
                    }
                    if (d.name !in OPERATOR_NAMES) {
                        error(
                            d.span,
                            "'${d.name}' is not an operator — an operator is syntax, so the names are a " +
                                "closed set: ${OPERATOR_NAMES.joinToString(", ")}",
                        )
                    }
                }
                // The names this function INTRODUCES become variables everywhere in its own signature, so
                // a call site can bind them from the arguments. Marked here, once, rather than at each
                // place a type is read — the alternative is every reader knowing the rule.
                for (p in d.typeParams) {
                    if (Prelude.type(p) != null || records.containsKey(p)) {
                        error(d.span, "'$p' is already a type, so it cannot also be a type parameter")
                    }
                }
                typeVars = typeVarsOf(d)
                // **The receiver is the first parameter**, called `self` — which is what makes an
                // extension an ordinary function everywhere below this line. The parser already puts it
                // in `params` when the body writes `self`; a body that does not is a function on the TYPE
                // rather than on a value of it, and has no receiver argument to take.
                val sig = Signature(
                    d.name,
                    d.params.map { p ->
                        // **A COMPUTED default is kept as an expression, exactly as a record field's is.**
                        // Only a `LiteralExpr` can be folded to a value; anything else — a name, a call, an
                        // empty list — folded to `null` while `hasDefault` stayed true, and the emitter then
                        // wrote `CONST reg, null` for every omitted argument. Nothing complained until the
                        // value was USED, and then only as `nothing to call: register 19 holds 'null', not
                        // a function`, a hundred nodes from the declaration that caused it. Four defaults in
                        // the real corpus were silently null this way, including `inputs: LIST<Input> = []`.
                        //
                        // Resolved here in the DECLARING document's top-level scope (the same
                        // [pendingFieldDefaults] queue record fields use), because that is the only scope
                        // its bare spelling means anything in — `= noSpill` is compiled wherever the call
                        // was written, and that document may never have named `noSpill`.
                        val type = typeOf(p.type)
                        val computed = p.default?.takeIf { it !is LiteralExpr }
                        computed?.let { pendingFieldDefaults += it to type }
                        Param(
                            p.name, type, (p.default as? LiteralExpr)?.value,
                            hasDefault = p.default != null, defaultExpr = computed,
                        )
                    },
                    d.results.map { Param(it.name, typeOf(it.type)) },
                )
                typeVars = emptySet()
                if (d.isExported) exported += d.name
                // Whether the body writes `self` is a SYNTACTIC question, answered here rather than after
                // the body is checked: a call to this function may be resolved before its own body is, and
                // the answer changes what the call means.
                val writes = d.receiver != null && d in mutating
                val binding = FunctionBinding(
                    d.name,
                    d.span,
                    // A mutating extension hands its receiver back, which is what lets the call site
                    // rebind — appended AFTER any result of its own, so `resultType` still answers with the
                    // type that was written down. An extension may both change its receiver and report
                    // something (`fn Farm.find(self) -> BOOL` locates the grid and says whether it found
                    // one), and putting the receiver first made every such call read as its own receiver.
                    if (writes) Signature(sig.name, sig.params, sig.results + sig.params.first()) else sig,
                    d,
                    writes,
                )
                if (d.receiver != null && d.params.none { it.name == SELF }) {
                    // **A receiver with no `self` is a function on the TYPE**, not on a value of it —
                    // `Want.carry(…)`, called by its qualified name. The distinction is derived from the
                    // parameter list rather than stored, which is the answer that killed `impl` blocks.
                    typeLevel.getOrPut(receiverKey(d.receiver!!)) { LinkedHashMap() }[d.name] = binding
                } else if (d.receiver != null) {
                    // An extension is NOT bound as a plain name: `first` on its own means nothing, and
                    // binding it would let `first(list: xs)` and `xs.first()` be two spellings of one
                    // function only one of which typechecks.
                    extensions.getOrPut(receiverKey(d.receiver!!)) { ArrayList() } += binding
                } else {
                    bind(d.name, binding, d.span)
                }
            }

            else -> Unit
        }
    }

    /**
     * Give a document variable its slot and its starting value.
     *
     * **Only a literal starts one, for now.** A variable whose initialiser is an expression has to be
     * computed by something that runs, and the graph pipeline answers that with a per-document prologue
     * chunk it splices ahead of the entry. That is the right answer here too and it is not this commit;
     * until then an expression initialiser says so rather than quietly starting as null — which is the
     * failure the author would otherwise be debugging.
     */
    /**
     * The [LiteralKind] a plain value would have been written as — for a column cell a member left out.
     *
     * A missing cell has no expression of its own, so one is synthesised from the column's default. NULL
     * for anything unrecognised, which is what "no default" already meant.
     */
    private fun literalKindOf(v: Any?): LiteralKind = when (v) {
        is String -> LiteralKind.STRING
        is Boolean -> LiteralKind.BOOL
        is Int, is Long -> LiteralKind.INT
        is Float, is Double -> LiteralKind.FLOAT
        else -> LiteralKind.NULL
    }

    private fun global(binding: DocumentBinding, initialiser: Expr?, span: Span) {
        // A LITERAL rides in the starting values and costs nothing. Anything else has to be evaluated by
        // something that runs — a prologue — so the slot starts null and is seeded before the entry does
        // anything. Deferred rather than resolved here: a declaration may name one made further down.
        val value = (initialiser as? LiteralExpr)?.value
        if (initialiser != null && initialiser !is LiteralExpr) {
            pendingInits += Triple(binding, initialiser, binding.type)
        } else if (initialiser is LiteralExpr) {
            // **Checked, even though it needs no prologue.** Riding in the starting values is about how it
            // gets there, not about whether it fits: `var Seed: INT = "forty two"` was seeded happily and
            // the declared type was a claim nothing enforced — the same shape as GAPS 16.
            val got = expr(initialiser, Scope(null), binding.type)
            if (!Unknown.isUnknown(binding.type) && !assignable(got, binding.type)) {
                error(initialiser.span, "'${binding.name}' is ${show(binding.type)} but starts as ${show(got)}")
            }
            literal(initialiser, binding.type)
        }
        globalSlots[binding] = globals.allocate(value)
    }

    /**
     * Every module this document imports, under the shape it was imported in.
     *
     * A module that cannot be read is reported ONCE, on its import line, and then leaves nothing behind —
     * so the names it would have brought in come back as ordinary unknown names rather than as a second
     * wave of complaints about a failure already explained.
     */
    private fun importsOf(program: Program): ImportedNames {
        val decls = program.decls.filterIsInstance<ImportDecl>()
        if (decls.isEmpty()) return ImportedNames.NONE
        val set = modules ?: run {
            for (d in decls) unsupported(d.span, "imports, with nowhere to resolve them")
            return ImportedNames.NONE
        }
        val aliased = LinkedHashMap<String, Module>()
        val unqualified = ArrayList<Module>()
        val named = LinkedHashMap<String, Pair<Module, String>>()
        for (d in decls) {
            val module = set.module(d.ref)
            if (module == null) {
                error(d.span, set.problem(d.ref) ?: "nothing answers to '${d.ref}'")
                continue
            }
            // Readable but not runnable — see the note in `ModuleSet`. Reported here, on the line that
            // named it, and then used anyway so this document's own names still resolve.
            set.problem(d.ref)?.let { error(d.span, it) }
            when {
                d.alias != null -> aliased[d.alias!!] = module
                // A BARE star: every exported name visible as itself. That is what makes an extension
                // usable without asking for it by name, which is the reason the spelling exists.
                d.star || (d.named.isEmpty() && d.default == null) -> unqualified += module
            }
            for (item in d.named) named[item.local] = module to item.name
            d.default?.let { named[it] = module to DEFAULT_EXPORT }
        }
        return ImportedNames(aliased, unqualified, named)
    }

    /**
     * `export * from "x"` / `export { a as b } from "x"` — names this document passes on.
     *
     * The target is resolved like any import, and what it exports becomes what THIS document exports,
     * under whatever name the re-export gives it. Extensions come with it, because an extension has no
     * qualified spelling: a front door that forwarded only the plain names would leave `xs.first()`
     * working through the barrel and not through the door.
     */
    private fun reExport(d: ReExportDecl) {
        val set = modules ?: run {
            unsupported(d.span, "a re-export, with nowhere to resolve it")
            return
        }
        val module = set.module(d.ref)
        if (module == null) {
            error(d.span, set.problem(d.ref) ?: "nothing answers to '${d.ref}'")
            return
        }
        set.problem(d.ref)?.let { error(d.span, it) }
        reExportedFrom[module.ref] = module
        val from = module.resolution
        if (d.all) {
            reExported += from.exports
            reExportedRecords += from.exportedRecords
            reExportedEnums += from.exportedEnums
            reExportedTypeLevel += from.typeLevel
            for ((type, fns) in from.exportedExtensions) {
                reExportedExtensions.getOrPut(type) { ArrayList() } += fns
            }
            return
        }
        for (item in d.items) {
            val binding = from.exports[item.name]
            val record = from.exportedRecords[item.name]
            if (binding == null && record == null) {
                error(d.span, "'${module.ref}' does not export '${item.name}'")
                continue
            }
            binding?.let { reExported[item.local] = it }
            record?.let { reExportedRecords[item.local] = it }
        }
    }

    /** An exported name, from wherever it was imported — or null when nothing offers it. */
    private fun imported(name: String): Binding? {
        imports.named[name]?.let { (module, exportedAs) -> return bindingsOf(module)[exportedAs] }
        for (module in imports.unqualified) bindingsOf(module)[name]?.let { return it }
        return null
    }

    /**
     * An exported record, from wherever it was imported — **aliased imports included**.
     *
     * That last part looks like it breaks the alias rule and does not. An alias governs what NAMES are
     * visible; this is asked once a type has already been reached legitimately, and a `TypeRef` is only a
     * name, so its fields can only be found by looking the name up again. Refusing to look in aliased
     * modules meant `pace::Beat` typechecked as a parameter and then had no fields.
     *
     * The cost is that two modules exporting a record of the same name are indistinguishable here — which
     * is GAPS 22 ("one type reached through two imports is two types") seen from the other side, and the
     * real fix is a type that carries its origin rather than its spelling.
     */
    /** An enum this document declares or can see — the same search records get, for the same reason. */
    /**
     * The enum called [name] — this document's, then its imports', then the HOST's.
     *
     * **One function, and every question about an enum comes through it**: `Phase.Chop` reads it to check
     * the member, `it.priority` to find the column, `Phase.values()` to build the list. That is why a host
     * enum needed no new resolution path — being answered here makes `Tab.Inventory` mean exactly what
     * `Phase.Chop` means, with the same errors and the same types.
     *
     * The host is LAST on purpose, so a document declaring its own `Tab` shadows the client's rather than
     * colliding with it. See [Prelude.hostEnum].
     */
    private fun enumNamed(name: String): EnumType? =
        enums[name] ?: importedEnum(name) ?: Prelude.hostEnum(name)

    /**
     * The enum a TYPE stands for — by owner where it has one, by spelling otherwise.
     *
     * **The twin of [recordFor], and it was missing.** A record reached through an imported field resolves
     * by owner, so `h.boxed.value` works in a document that never imported the module declaring `Box`. An
     * enum reached the same way had only the name path, so `h.row.kind.label` answered "Kind has no
     * fields" about an enum declared perfectly well one document further out — the same failure owner
     * identity was built to end, surviving in the half of the type system that did not get it.
     *
     * The asymmetry was easy to miss because a document that COMPARES against a member has to name the
     * enum, and naming it requires the import. It bites where a column is read off a value that merely
     * travelled: `a.kind.label`, `row.stage.name`.
     */
    private fun enumFor(t: TypeRef): EnumType? {
        val owner = t.owner
        if (owner != null) {
            val simple = t.simpleName
            if (owner == ref) enums[simple]?.let { return it }
            modules?.byRef(owner)?.resolution?.let { r ->
                // Its exports first, then everything it declares — [recordFor]'s rule, for its reason: a
                // type may legitimately be reached through a field of an exported record without being
                // exported itself, and refusing that would put `export` in the way of reading a value the
                // module already handed out.
                (r.exportedEnums[simple] ?: r.enums[simple])?.let { return it }
            }
        }
        return enumNamed(t.name)
    }

    private fun importedEnum(name: String): EnumType? {
        imports.named[name]?.let { (module, exportedAs) -> return enumsOf(module)[exportedAs] }
        for (module in imports.unqualified) enumsOf(module)[name]?.let { return it }
        for (module in imports.aliased.values) enumsOf(module)[name]?.let { return it }
        for ((module, _) in imports.named.values) enumsOf(module)[name]?.let { return it }
        return null
    }

    /**
     * Whether THIS document may see [m]'s un-exported names — see `ModuleNames.maySeeInternals`.
     *
     * A test may reach into the one module it tests. That is not a hole in visibility, it is what makes
     * visibility affordable: without it every internal a test needs has to be exported, and a codebase
     * that exports everything to be testable has no visibility left to enforce.
     */
    private fun friendly(m: Module): Boolean = ModuleNames.maySeeInternals(ref, m.ref)

    // The four views an importer takes of a module. Everything below looks through these rather than at
    // the export maps directly, so the friend rule is stated once instead of at each of eighteen sites —
    // and the one that got forgotten would be an internal reachable from anywhere.
    private fun bindingsOf(m: Module): Map<String, Binding> =
        if (friendly(m)) m.resolution.allBindings + m.resolution.exports else m.resolution.exports

    private fun recordsOf(m: Module): Map<String, RecordType> =
        if (friendly(m)) m.resolution.records + m.resolution.exportedRecords else m.resolution.exportedRecords

    private fun enumsOf(m: Module): Map<String, EnumType> =
        if (friendly(m)) m.resolution.enums + m.resolution.exportedEnums else m.resolution.exportedEnums

    private fun extensionsOf(m: Module): Map<String, List<FunctionBinding>> =
        if (friendly(m)) m.resolution.extensions + m.resolution.exportedExtensions
        else m.resolution.exportedExtensions

    private fun importedRecord(name: String): RecordType? {
        imports.named[name]?.let { (module, exportedAs) -> return recordsOf(module)[exportedAs] }
        for (module in imports.unqualified) recordsOf(module)[name]?.let { return it }
        for (module in imports.aliased.values) recordsOf(module)[name]?.let { return it }
        // **The modules a NAMED import came from, last.** Same rule as the aliased line above and the same
        // justification: a name is only reachable here once a TYPE has already been reached legitimately,
        // and a `TypeRef` carries a spelling rather than an origin, so its fields can only be found by
        // looking the spelling up again.
        //
        // Without it a value is importable while its type is not — `import Roster from "x"` where the
        // default is a `single` bound the name, typed it, and then answered `Roster has no fields`, which
        // reads as a broken declaration in the file that declares it perfectly well. A default import is
        // the common way to meet this because it brings exactly ONE name across by construction.
        for ((module, _) in imports.named.values) recordsOf(module)[name]?.let { return it }
        return null
    }

    /** What an alias points at, complaining once when it points at nothing. */
    private fun moduleFor(alias: String, span: Span): Module? {
        val module = imports.aliased[alias]
        if (module == null) error(span, "no import is called '$alias'")
        return module
    }

    /**
     * The name an extension is filed under — `LIST` for `List<T>`, `Registry` for `Registry`.
     *
     * The type's own name with its arguments dropped, because the arguments are a BINDING SITE here: the
     * whole point of `fn List<T>.first(self)` is that `T` is decided by the receiver at the call site, so
     * filing it under `LIST<T>` would make it findable only by a receiver spelled exactly that way.
     */
    /**
     * Every name this function treats as a type variable.
     *
     * Three sources, and the third is a CONVENTION rather than a declaration. A receiver's arguments are a
     * binding site outright — `List<T>` is evidence on its own. Anywhere else a name must also LOOK like
     * one (`[A-Z][0-9]*`), because `p: Tille` is overwhelmingly a typo for a record and treating it as a
     * variable would take the diagnostic away. `Generics.kt` reached the same rule for the graph, and
     * `fn LIST<T>.map(self, f: fn(T) -> U) -> LIST<U>` is why: `U` is introduced nowhere else.
     */
    private fun typeVarsOf(d: FnDecl): Set<String> {
        val declared = d.typeParams.toSet() + receiverVariables(d.receiver)
        val conventional = LinkedHashSet<String>()
        fun scan(t: TypeExpr?) {
            if (t == null) return
            if (t.args.isEmpty() && t.module == null && CONVENTIONAL.matches(t.name) && !known(t.name)) {
                conventional += t.name
            }
            t.args.forEach { scan(it) }
        }
        d.params.forEach { scan(it.type) }
        d.results.forEach { scan(it.type) }
        return declared + conventional
    }

    /** Whether [name] is a type this document can already see — the guard that keeps a typo a typo. */
    private fun known(name: String): Boolean =
        Prelude.type(name) != null || records.containsKey(name) ||
            enums.containsKey(name) || importedRecord(name) != null || importedEnum(name) != null ||
            hostType(name)

    /**
     * A type the HOST registered — a record or an enum.
     *
     * **Counted as known, and forgetting to was a real bug with a very indirect symptom.** Both places
     * that ask "is this name a type or a type VARIABLE" listed the document's own, its imports' and the
     * prelude's, and stopped there. That was complete while every host type was also a builtin, so
     * `Prelude.type("Tile")` answered for it. The moment a type moved to the node pack, `Tile` named
     * nothing either of them knew — so `fn List<Tile>.nearestTo(self, …)` was read as GENERIC over a
     * variable that happened to be spelled `Tile`.
     *
     * Nothing complained. `self` typed as `LIST<variable>`, the loop variable came out as the variable,
     * and `extensionCall` leaves an unknown receiver alone by design rather than guessing at it — so
     * `t.sqDistanceTo(…)` resolved to no callee at all, silently, and surfaced pages later as the
     * compiler's "does not compile a call to 't.sqDistanceTo' yet", which reads as a missing feature.
     */
    private fun hostType(name: String): Boolean =
        dev.ziggle.vscript.model.HostRecords.of(name) != null ||
            dev.ziggle.vscript.model.HostEnums.of(name) != null

    /**
     * The type parameters a receiver introduces — its arguments that name nothing this document knows.
     *
     * A binding site cannot be typo-checked: `fn List<Etity>.f(self)` is generic over `Etity` for exactly
     * the reason `fn f(etity: INT)` has a parameter called `etity`. `Generics.kt` says the same and
     * catches the realistic mistake elsewhere — a parameter introduced and never used.
     */
    private fun receiverVariables(receiver: TypeExpr?): Set<String> {
        if (receiver == null) return emptySet()
        return receiver.args
            .filter { it.args.isEmpty() && it.module == null }
            .map { it.name }
            // A HOST type is a type, not a variable — see [hostType], which is where the whole story is.
            .filter {
                Prelude.type(it) == null && !records.containsKey(it) && importedRecord(it) == null &&
                    !hostType(it)
            }
            .toSet()
    }

    private fun receiverKey(t: TypeExpr): String = typeOf(TypeExpr(t.name, span = t.span)).name

    /** Every extension called [name] that could apply to [receiver] — this document's and its imports'. */
    private fun extensionsFor(receiver: TypeRef, name: String): List<FunctionBinding> {
        // **A widened receiver inherits its supertype's extensions**, which is what makes the supertype
        // worth having. `core/world/interaction` declares `fn EntityRef.click(self, …)` once, and an
        // `ObjectRef` is an entity — without this it would report `ObjectRef has no 'click'` and the only
        // fix would be three copies of every scene extension, which is exactly the duplication the
        // widening rule exists to avoid. Nearest first, so a verb declared on the specific type wins over
        // the general one, the way an override should.
        val keys = HostRecords.of(receiver)?.widening()?.map { it.name } ?: listOf(receiver.name)
        for (k in keys) {
            val hit = extensionsForNamed(k, name)
            if (hit.isNotEmpty()) return hit
        }
        return emptyList()
    }

    /**
     * A host-declared extension called [name] that applies to [receiver], or null.
     *
     * Widens exactly as [extensionsFor] does and for the same reason: a pack declaring
     * `fn EntityRef.click(self)` should reach an `ObjectRef`, or every scene verb needs three copies.
     * Nearest first, so a verb declared on the specific type wins over the general one.
     */
    private fun hostExtension(receiver: TypeRef, name: String): NativeFn? {
        val keys = HostRecords.of(receiver)?.widening()?.map { it.name } ?: listOf(receiver.name)
        for (k in keys) natives.extension(k, name)?.let { return it }
        return null
    }

    private fun extensionsForNamed(key: String, name: String): List<FunctionBinding> {
        // **Deduplicated by IDENTITY.** One extension reached twice — directly and through a front door
        // that re-exports it — is one extension, and complaining that it is offered "by more than one
        // import" would punish the very layering the re-export exists to provide.
        val found = LinkedHashSet<FunctionBinding>()
        extensions[key]?.let { found += it.filter { fn -> fn.name == name } }
        for (module in imports.unqualified) {
            extensionsOf(module)[key]?.let { found += it.filter { fn -> fn.name == name } }
        }
        for (module in imports.aliased.values) {
            extensionsOf(module)[key]?.let { found += it.filter { fn -> fn.name == name } }
        }
        // **A NAMED import, which is the one form that did not work.** `import { filter } from "core/list"`
        // resolved, reported nothing, and then `xs.filter { … }` failed with "LIST<STRING> has no
        // 'filter'" — while `import "core/list"` and `import * as x from "core/list"` both worked. So the
        // one spelling that says exactly which verb a document wants was the one that could not ask for it.
        //
        // Matched on the LOCAL name and searched by the EXPORTED one, which is what makes
        // `import { filter as doTheFiltering }` work: an extension has nowhere to put an alias at the call
        // site — it is written after a dot — so the import list is the only place a document can rename
        // one, and the rename has to be honoured here or it means nothing.
        for ((local, target) in imports.named) {
            if (local != name) continue
            val (module, exportedAs) = target
            extensionsOf(module)[key]
                ?.let { found += it.filter { fn -> fn.name == exportedAs } }
        }
        return found.toList()
    }

    /**
     * One declared field, keeping its default as a VALUE when it is one and as an EXPRESSION otherwise.
     *
     * Recording only the fact that there IS a default is how a field written `= 1` was built as null: the
     * compiler asked for something to put in the slot and there was nothing to give it. A computed default
     * needs the expression for the same reason a computed variable needs a prologue — something has to run.
     */
    private fun field(f: dev.ziggle.vscript.lang.Field): Param {
        val type = typeOf(f.type)
        val computed = f.default?.takeIf { it !is LiteralExpr }
        computed?.let { pendingFieldDefaults += it to type }
        return Param(
            f.name,
            type,
            (f.default as? LiteralExpr)?.value,
            hasDefault = f.default != null,
            defaultExpr = computed,
        )
    }

    /** Two extension tables, joined by receiver type — a type may be extended from both sides. */
    private fun merged(
        own: Map<String, List<FunctionBinding>>,
        forwarded: Map<String, List<FunctionBinding>>,
    ): Map<String, List<FunctionBinding>> {
        val out = LinkedHashMap<String, List<FunctionBinding>>()
        for ((type, fns) in own) out[type] = fns
        for ((type, fns) in forwarded) out[type] = (out[type].orEmpty() + fns).distinct()
        return out
    }

    /** What a body does with `self`: whether it writes it, and which methods it calls ON it. */
    private class SelfUse(
        var writes: Boolean = false,
        val calls: MutableSet<String> = HashSet(),
        /**
         * `self = <whole new value>`, as opposed to `self.field = v`.
         *
         * Only the first is the write-back crutch — the mechanism that stored a returned receiver back
         * over the call site so `xs.add(v)` could look like a mutation while a list was a value. A field
         * write is an ordinary one, and on a `single` it has always changed the one cell directly.
         */
        var replacesSelf: Boolean = false,
    )

    /**
     * Does this body write `self`? The marker that makes an extension a mutating one.
     *
     * **A FIELD write counts, and missing that was a bug with no symptom.** `self.n = 1` is a
     * [FieldAssignStmt], not an [AssignStmt] — it desugars to `self = self with { n: 1 }`, an assignment to
     * a parameter, which is local by the rule [FieldAssignStmt] itself documents. Seen only as an
     * `AssignStmt`, the extension was not marked mutating, so no receiver came back and no call site
     * rebound: `fn S.bump(self) { self.n = self.n + 1 }` compiled, ran, and did nothing at all.
     *
     * It bit hardest on a `single`, where it looks least likely to be wrong — a single IS one global
     * variable, `S.n = 1` works from anywhere, and the same write through `self` inside its own extension
     * silently went nowhere. A latch cleared that way stayed set for the life of the run.
     *
     * **And the walk has to be complete.** It used to visit five statement forms, so the same write inside a
     * `try`, a `when` or a `sequence` was missed for the same reason and with the same silence.
     */
    private fun selfUse(block: Block?): SelfUse {
        val out = SelfUse()
        if (block == null) return out

        // `self.a.b = v` rebuilds outward and ends in an assignment to `self`, so any depth of field counts.
        fun rootIsSelf(e: Expr): Boolean = when (e) {
            is NameExpr -> e.name == SELF && e.module == null
            is MemberExpr -> rootIsSelf(e.target)
            is IndexExpr -> rootIsSelf(e.target)
            else -> false
        }

        fun inExpr(e: Expr?) {
            when (e) {
                null -> Unit
                is CallExpr -> {
                    if (e.module == null) {
                        val recv = e.receiver
                        // Both spellings a method call arrives in: an explicit receiver expression, and the
                        // dotted form where `self` is simply the first part of the name.
                        if (recv != null && rootIsSelf(recv)) out.calls += e.target.last()
                        else if (e.target.size >= 2 && e.target.first() == SELF) out.calls += e.target[1]
                    }
                    inExpr(e.receiver)
                    for (a in e.args) inExpr(a.value)
                }
                is BinaryExpr -> { inExpr(e.left); inExpr(e.right) }
                is IsExpr -> inExpr(e.value)
                is AsExpr -> { inExpr(e.value); for (f in e.renames) inExpr(f.value) }
                is NotExpr -> inExpr(e.operand)
                is TernaryExpr -> { inExpr(e.condition); inExpr(e.ifTrue); inExpr(e.ifFalse) }
                is ElvisExpr -> { inExpr(e.value); inExpr(e.fallback) }
                is SafeAccessExpr -> { inExpr(e.receiver); inExpr(e.access) }
                is MemberExpr -> inExpr(e.target)
                is IndexExpr -> { inExpr(e.target); inExpr(e.index) }
                is StructLitExpr -> for (f in e.fields) inExpr(f.value)
                is WithExpr -> { inExpr(e.target); for (f in e.fields.toList()) inExpr(f.value) }
                is ListLitExpr -> for (x in e.items) inExpr(x)
                is LambdaExpr -> inExpr(e.body)
                else -> Unit
            }
        }

        fun walk(b: Block?) {
            fun inStmt(st: Stmt) {
                when (st) {
                    is AssignStmt -> {
                        if (st.name == SELF && st.module == null) {
                            out.writes = true
                            // `self = <whole new value>` specifically — the write-back crutch, as opposed
                            // to `self.field = v`, which is an ordinary field write and, on a `single`,
                            // has always mutated the one cell directly.
                            out.replacesSelf = true
                        }
                        inExpr(st.value)
                    }
                    is FieldAssignStmt -> {
                        if (rootIsSelf(st.target)) out.writes = true
                        inExpr(st.target)
                        inExpr(st.value)
                    }
                    is LetStmt -> inExpr(st.value)
                    is ConstStmt -> inExpr(st.value)
                    is ExprStmt -> inExpr(st.expr)
                    is ReturnStmt -> for (v in st.values) inExpr(v)
                    is IfStmt -> { inExpr(st.condition); walk(st.then); st.elseBranch?.let { inStmt(it) } }
                    is IfLetStmt -> { inExpr(st.value); walk(st.then); st.elseBranch?.let { inStmt(it) } }
                    is WhileStmt -> { inExpr(st.condition); walk(st.body) }
                    is ForStmt -> { inExpr(st.list); walk(st.body) }
                    is ExprBlockStmt -> walk(st.block)
                    is TryStmt -> { walk(st.body); walk(st.catch) }
                    is WhenStmt -> {
                        inExpr(st.subject)
                        for (arm in st.arms) { inExpr(arm.value); walk(arm.body) }
                        st.elseArm?.let { walk(it) }
                    }
                    is SequenceStmt -> for (arm in st.arms) walk(arm)
                    else -> Unit
                }
            }
            b?.stmts?.forEach { inStmt(it) }
        }
        walk(block)
        return out
    }

    /**
     * Every extension in this document that mutates its receiver — directly, or by calling one that does.
     *
     * **Mutation travels.** `fn Farm.settleInto(self) { self.find() }` writes nothing itself and is a
     * mutating extension all the same, because `find` is: the receiver `find` hands back is stored into
     * `settleInto`'s own `self`, and unless `settleInto` also hands its `self` back, that is where the
     * change stops. Observed exactly so — the tithe run located its farm, wrote the origin, and forgot it
     * on the way out of the call.
     *
     * A fixpoint rather than one pass, because the chain can be any length and is not written in order.
     *
     * Answered SYNTACTICALLY and before any body is checked, for the reason the caller gives: a call may be
     * resolved before the declaration it names, and whether that declaration mutates changes what the call
     * compiles to. Matching is by receiver spelling and method name, which is all that is known this early.
     */
    private fun mutatingExtensions(program: Program): Set<FnDecl> {
        val exts = program.decls.filterIsInstance<FnDecl>().filter { it.receiver != null }
        if (exts.isEmpty()) return emptySet()
        val uses = exts.associateWith { selfUse(it.body) }
        // **`self = …` is refused rather than quietly meaning something else.**
        //
        // It used to mean "store the result back over the receiver at the call site" — a whole compiler
        // mechanism, invented so that `xs.add(v)` could look like a mutation while a list was still a
        // value. It existed for exactly one declaration in the corpus, `List<T>.add`.
        //
        // Containers are references now, so a mutation is just a mutation and the mechanism has nothing
        // left to do. Deleting it silently would change what `self = …` MEANS — from "the caller sees
        // this" to "the caller does not" — which is a wrong answer rather than an error, and the kind
        // this language has been bitten by twice. So it is named where it is written.
        for (d in exts) {
            if (uses.getValue(d).replacesSelf) {
                error(
                    d.span,
                    "'self = …' no longer writes back to the call site — a receiver is a reference, so " +
                        "mutate it directly ('_listAdd(self, …)') or hand a new value back with 'return'",
                )
            }
        }
        val out = exts.filterTo(HashSet()) { uses.getValue(it).writes }
        // What each receiver type already knows how to mutate, by method name.
        val mutators = HashMap<String, MutableSet<String>>()
        for (d in out) mutators.getOrPut(d.receiver!!.name) { HashSet() } += d.name
        var changed = true
        while (changed) {
            changed = false
            for (d in exts) {
                if (d in out) continue
                val known = mutators[d.receiver!!.name] ?: continue
                if (uses.getValue(d).calls.any { it in known }) {
                    out += d
                    mutators.getValue(d.receiver!!.name) += d.name
                    changed = true
                }
            }
        }
        return out
    }

    private fun bind(name: String, binding: Binding, span: Span) {
        if (document.put(name, binding) != null) error(span, "'$name' is declared twice")
    }

    /** A written type annotation, as a type. Unknown names are reported and become WILDCARD. */
    private fun typeOf(t: TypeExpr?): TypeRef {
        if (t == null) return TypeRef.WILDCARD
        t.module?.let { alias ->
            val module = imports.aliased[alias]
            if (module == null) {
                error(t.span, "no import is called '$alias'")
                return TypeRef.WILDCARD
            }
            val record = recordsOf(module)[t.name]
            if (record == null) {
                error(t.span, "'${module.ref}' does not export a type called '${t.name}'")
                return TypeRef.WILDCARD
            }
            return if (t.optional) record.ref.orNull() else record.ref
        }
        // A name the enclosing function INTRODUCED is a variable, not a missing type. Checked before the
        // catalogue of real types, so `fn odd<INT>(…)` cannot quietly mean the built-in — and the
        // declaration refuses that spelling anyway.
        if (t.name in typeVars) {
            val v = TypeRef.named(t.name).asVariable()
            return if (t.optional) v.orNull() else v
        }
        val base = Prelude.type(t.name)
            ?: records[t.name]?.ref
            ?: enums[t.name]?.ref
            ?: importedRecord(t.name)?.ref
            ?: importedEnum(t.name)?.ref
            // A HOST enum — `val t: Tab = Tab.Inventory`. Below the document's own and its imports', for
            // the reason `enumNamed` gives: a document that declares the name wins.
            ?: Prelude.hostEnum(t.name)?.ref
            // A record the HOST provides — `Entity`, `ItemRef`. Below the document's own and its imports'
            // for the reason `enumNamed` gives: a document declaring the name shadows rather than collides.
            ?: dev.ziggle.vscript.model.HostRecords.of(t.name)?.type
            // A type the CATALOGUE names — an opaque handle like `Row`, which a script may hold and pass
            // on without ever looking inside.
            ?: natives.types.firstOrNull { it == t.name }?.let { TypeRef.named(it) }
            ?: when (t.name.uppercase()) {
                // `fn(INT) -> BOOL` — the parser puts the RESULT last in the argument list, so the
                // parameters are everything before it. `NOTHING` there is a function that hands nothing
                // back, which is an ordinary shape rather than a missing one.
                "FN", "ACT" -> TypeRef.function(
                    t.args.dropLast(1).map { typeOf(it) },
                    t.args.lastOrNull()?.let { typeOf(it) } ?: TypeRef.NOTHING,
                )
                "NOTHING" -> TypeRef.NOTHING
                "LIST" -> TypeRef.list(t.of?.let { typeOf(it) })
                "MAP" -> TypeRef.map(t.args.getOrNull(0)?.let { typeOf(it) }, t.args.getOrNull(1)?.let { typeOf(it) })
                else -> {
                    error(t.span, "no type called '${t.name}'")
                    TypeRef.WILDCARD
                }
            }
        // **A generic record's written arguments ride on the ref.** `Pair<INT, STRING>` and
        // `Pair<STRING, INT>` are the same declaration and not the same type, and the only place that
        // distinction can live is here — `sameDeclaredType` already compares arguments, and `substitute`
        // reads them back when a field is asked for.
        val declared = recordFor(base)
        val applied = when {
            declared == null || declared.params.isEmpty() || t.args.isEmpty() -> base
            t.args.size != declared.params.size -> {
                error(
                    t.span,
                    "'" + declared.name + "' takes " + declared.params.size + " type argument" +
                        (if (declared.params.size == 1) "" else "s") + ", and this gives " + t.args.size,
                )
                base
            }
            else -> base.withArgs(t.args.map { typeOf(it) })
        }
        return if (t.optional) applied.orNull() else applied
    }

    /**
     * A generic record's fields, with its arguments put in — `Pair<INT, STRING>` has `first: INT`.
     *
     * The declaration holds variables and every reader wants the applied form, so this is the one place
     * that turns one into the other. Non-generic records and un-applied ones fall straight through, which
     * is every record in the corpus today.
     */
    private fun fieldsOf(t: TypeRef, record: RecordType): List<Param> {
        if (record.params.isEmpty() || t.args.size != record.params.size) return record.fields
        val bindings = record.params.zip(t.args).toMap()
        return record.fields.map { Param(it.name, substitute(it.type, bindings), it.default, it.hasDefault, it.defaultExpr) }
    }

    /** A function body, checked against its own parameters and results. */
    private fun body(d: FnDecl) {
        val scope = Scope(null)
        typeVars = typeVarsOf(d)
        val params = d.params.map { p ->
            // `self` may be written: that IS how a mutating extension is spelled, and the write is what
            // the call site reads back.
            LocalBinding(p.name, typeOf(p.type), p.span, mutable = p.name == SELF, isParameter = true)
                .also { scope.declare(it, this) }
        }
        paramsOf[d] = params
        block(d.body, scope, Loop.NONE, d.results.map { typeOf(it.type) })
        typeVars = emptySet()
    }

    // ---- scopes ---------------------------------------------------------------------------------------

    /**
     * One block's names, and the block around it.
     *
     * Nested rather than flat, which the skeleton compiler was not: a name declared inside an `if` must
     * stop existing at its closing brace, or a second `val` of the same name in a sibling block is a
     * redeclaration of something the author cannot see.
     */
    private class Scope(val parent: Scope?, val owner: LambdaExpr? = parent?.owner) {
        private val names = LinkedHashMap<String, Binding>()

        /**
         * What a name is known to be HERE, narrower than it was declared.
         *
         * `Generics.kt`'s note says the graph could not do this: "a type here belongs to a pin rather than
         * to a point in the exec chain, so there is nowhere to record that a value is something narrower
         * inside one branch". A tree has such a point — this scope — which is the whole reason the
         * detachment was worth doing.
         */
        private val narrowed = LinkedHashMap<Binding, TypeRef>()

        fun narrow(binding: Binding, type: TypeRef) {
            narrowed[binding] = type
        }

        fun narrowedType(binding: Binding): TypeRef? = narrowed[binding] ?: parent?.narrowedType(binding)

        fun declare(b: Binding, r: Resolver) {
            // Shadowing an OUTER name is allowed and ordinary; redeclaring in the SAME block is a typo.
            if (names.put(b.name, b) != null) r.error(b.span, "'${b.name}' is already declared in this block")
        }

        fun find(name: String): Binding? = names[name] ?: parent?.find(name)

        /** Which scope actually holds [name] — how a read tells an enclosing local from an inner one. */
        fun holderOf(name: String): Scope? =
            if (names.containsKey(name)) this else parent?.holderOf(name)
    }

    /** What `break` and `continue` are allowed to mean here. */
    private class Loop(val inside: Boolean) {
        companion object {
            val NONE = Loop(false)
            val INSIDE = Loop(true)
        }
    }

    // ---- statements -----------------------------------------------------------------------------------

    private fun block(b: Block, outer: Scope, loop: Loop, results: List<TypeRef>) {
        val scope = Scope(outer)
        for (s in b.stmts) {
            stmt(s, scope, loop, results)
            // **An early return narrows what follows it.** `if have == null { return … }` leaves `have`
            // present for the rest of the block, which is the shape the corpus reaches for and the one a
            // pin could never express.
            afterExit(s, scope)
        }
    }

    /** If [s] is an `if <x> == null { … return }`, note that `x` is present from here on. */
    private fun afterExit(s: Stmt, scope: Scope) {
        if (s !is IfStmt || s.elseBranch != null) return
        if (!alwaysExits(s.then)) return
        val (binding, isNullTest) = nullTest(s.condition, scope) ?: return
        if (isNullTest) scope.narrow(binding, (scope.narrowedType(binding) ?: binding.type).required())
    }

    /** Does this block always leave? The conservative reading: its LAST statement does. */
    /**
     * Whether [s] can hand a value back — a `return` with something after it, however deeply nested.
     *
     * Only asked of a lambda with no trailing expression, to tell "hands nothing back" from "hands
     * everything back through `return`". A `return` inside a NESTED lambda belongs to that lambda and is
     * not this one's, which is why the walk does not descend into expressions.
     */
    private fun returnsAValue(s: Stmt): Boolean = when (s) {
        is ReturnStmt -> s.values.isNotEmpty()
        is IfStmt -> s.then.stmts.any { returnsAValue(it) } || s.elseBranch?.let { returnsAValue(it) } == true
        is IfLetStmt -> s.then.stmts.any { returnsAValue(it) } || s.elseBranch?.let { returnsAValue(it) } == true
        is ExprBlockStmt -> s.block.stmts.any { returnsAValue(it) }
        is WhileStmt -> s.body.stmts.any { returnsAValue(it) }
        is ForStmt -> s.body.stmts.any { returnsAValue(it) }
        is dev.ziggle.vscript.lang.SequenceStmt -> s.arms.any { arm -> arm.stmts.any { returnsAValue(it) } }
        is TryStmt -> s.body.stmts.any { returnsAValue(it) } || s.catch.stmts.any { returnsAValue(it) }
        is WhenStmt -> s.arms.any { arm -> arm.body.stmts.any { returnsAValue(it) } } ||
            s.elseArm?.stmts?.any { returnsAValue(it) } == true
        else -> false
    }

    private fun alwaysExits(b: Block): Boolean = when (val last = b.stmts.lastOrNull()) {
        is ReturnStmt, is BreakStmt, is ContinueStmt -> true
        is IfStmt -> last.elseBranch != null && alwaysExits(last.then) &&
            (last.elseBranch as? ExprBlockStmt)?.block?.let { alwaysExits(it) } == true
        else -> false
    }

    /**
     * `x == null` / `x != null` over a simple name — the binding it tests, and which way round.
     *
     * Deliberately narrow. A test that is not literally a name against `null` is not evidence this pass
     * knows how to use, and guessing at one would narrow something that might be absent — which is worse
     * than not narrowing at all.
     */
    private fun nullTest(e: Expr, scope: Scope): Pair<Binding, Boolean>? {
        if (e !is BinaryExpr) return null
        if (e.op != BinaryOp.EQ && e.op != BinaryOp.NE) return null
        val name = (e.left as? NameExpr)?.takeIf { isNull(e.right) }
            ?: (e.right as? NameExpr)?.takeIf { isNull(e.left) }
            ?: return null
        val binding = scope.find(name.name) ?: document[name.name] ?: return null
        return binding to (e.op == BinaryOp.EQ)
    }

    private fun isNull(e: Expr): Boolean =
        e is LiteralExpr && e.kind == LiteralKind.NULL

    private fun stmt(s: Stmt, scope: Scope, loop: Loop, results: List<TypeRef>) {
        when (s) {
            is AssertStmt -> {
                val t = expr(s.condition, scope)
                // A condition that is not a BOOL is the mistake worth catching here: `assert count(xs)`
                // reads as a check and is a number, which would be truthy-tested into always passing.
                if (!Unknown.isUnknown(t) && t.builtin != PinType.BOOL) {
                    error(s.condition.span, "'assert' needs a true-or-false condition, and this is $t")
                }
                s.message?.let { expr(it, scope) }
                // The sides are the SAME expression objects as the condition's operands, so they are
                // already resolved by the walk above; touching them again would double-record them.
            }
            is LetStmt -> {
                val binding = s.binding
                if (binding is TupleBinding) {
                    destructure(s, binding, scope)
                    return
                }
                if (binding !is NameBinding) {
                    unsupported(s.span, "destructuring by field name")
                    return
                }
                val declared = s.declaredType?.let { typeOf(it) }
                val actual = expr(s.value, scope, declared)
                // **The declared type wins.** `let n: FLOAT = 0` is a float that starts at zero, not an
                // int — which is GAPS 16, and it is a gap precisely because the graph had nowhere to
                // record the annotation once the literal had been folded.
                val type = declared ?: actual
                if (declared != null && !assignable(actual, declared)) {
                    error(s.value.span, "'${binding.name}' is declared ${show(declared)} but starts as ${show(actual)}")
                }
                val local = LocalBinding(binding.name, type, s.span, mutable = s.mutable)
                scope.declare(local, this)
                localOf[s] = local
            }

            is AssignStmt -> {
                val target = s.module?.let { alias ->
                    val module = moduleFor(alias, s.span)
                    val found = module?.resolution?.exports?.get(s.name)
                    if (module != null && found == null) {
                        error(s.span, "'${module.ref}' does not export '${s.name}'")
                    }
                    found
                } ?: scope.find(s.name) ?: document[s.name] ?: imported(s.name)
                if (target == null) {
                    error(s.span, "nothing here is called '${s.name}'")
                    expr(s.value, scope)
                    return
                }
                targetOf[s] = target
                if (!target.mutable) {
                    error(
                        s.span,
                        "'${s.name}' is a `val`, which names a value once — write 'var ${s.name} = …' " +
                            "instead if it has to change",
                    )
                }
                val actual = expr(s.value, scope, target.type)
                if (!assignable(actual, target.type)) {
                    error(s.value.span, "'${s.name}' is ${show(target.type)} but is being given ${show(actual)}")
                }
            }

            is IndexAssignStmt -> {
                val target = expr(s.target, scope)
                when {
                    Unknown.isUnknown(target) -> {
                        expr(s.index, scope)
                        expr(s.value, scope)
                    }
                    // A list writes its own position with one instruction — the built-in entry in the
                    // same lookup everything else goes through. It could not exist while a list was a
                    // value; now it is a reference, so the slot is simply written.
                    target.isList -> {
                        val index = expr(s.index, scope, TypeRef(PinType.INT))
                        if (!Unknown.isUnknown(index) && index.builtin != PinType.INT) {
                            error(s.index.span, "a list is indexed by a whole number, and this is ${show(index)}")
                        }
                        expr(s.value, scope, target.of)
                    }
                    // Anything else asks the TYPE what `[…] =` means — an `op fn <T>.set`. Desugared to
                    // an ordinary call for the same reason the read is: the extension path already knows
                    // how to find it, check it and splice it.
                    else -> {
                        val call = CallExpr(
                            listOf("set"),
                            listOf(Arg(null, s.index, s.index.span), Arg(null, s.value, s.value.span)),
                            s.span,
                        )
                        indexSetOf[s] = call
                        if (extensionsFor(target, "set").isEmpty()) {
                            error(
                                s.span,
                                "${show(target)} cannot be written by index — a type gets '[…] =' by " +
                                    "declaring 'op fn ${show(target)}.set(self, …)'",
                            )
                            expr(s.index, scope)
                            expr(s.value, scope)
                        } else {
                            extensionCall(call, s.target, scope, "set")
                        }
                    }
                }
            }

            is FieldAssignStmt -> {
                // `course.laps = v` is `course = course with { laps: v }` — a record is a VALUE, so this
                // rebinds the name rather than writing through anything. `op` is already desugared into
                // `value`, exactly as an ordinary assignment's is.
                val record = expr(s.target, scope)
                // **[recordFor], the same lookup a field READ uses.** This was
                // `records[record.name] ?: Prelude.record(record)` — this document's own table and the
                // prelude, and nothing else — so assigning to a field of an IMPORTED record reported
                // "Order has no fields to assign" about a record declared perfectly well next door, while
                // reading the very same field worked. It went unnoticed because every record anyone had
                // assigned into so far happened to be declared in the file doing the assigning.
                val declaration = recordFor(record)
                if (declaration == null) {
                    if (!Unknown.isUnknown(record)) error(s.span, "${show(record)} has no fields to assign")
                    expr(s.value, scope)
                    return
                }
                // Through [fieldsOf] so a generic record's field carries the type its ARGUMENT gives it —
                // `Box<Int>.value` is an Int here, exactly as it is when read.
                val fields = fieldsOf(record, declaration)
                val field = fields.firstOrNull { it.name == s.field }
                if (field == null) {
                    error(s.span, "'${declaration.name}' has no field '${s.field}' — it has ${fields.joinToString { it.name }}")
                    expr(s.value, scope)
                    return
                }
                val got = expr(s.value, scope, field.type)
                if (!assignable(got, field.type)) {
                    error(s.value.span, "'${s.field}' is ${show(field.type)} but is being given ${show(got)}")
                }
                // The name being rebound has to be one that CAN be: `course.laps = …` writes `course`.
                val root = rootName(s.target)
                val target = root?.let { scope.find(it) ?: document[it] }
                if (target != null && !target.mutable) {
                    error(
                        s.span,
                        "'$root' is a `val`, which names a value once — write 'var $root = …' " +
                            "instead if it has to change",
                    )
                }
            }

            is IfLetStmt -> {
                // `if val t = nearest() { … }` — the binding's type is the option's with the `?` removed,
                // which is the whole point: inside the branch it is known to be there.
                val option = expr(s.value, scope)
                if (!Unknown.isUnknown(option) && !option.optional) {
                    error(s.value.span, "'if val' tests something that may be absent, and ${show(option)} always is present")
                }
                val inner = Scope(scope)
                val bound = LocalBinding(s.name, option.required(), s.span)
                inner.declare(bound, this)
                boundOf[s] = listOf(bound)
                val body = Scope(inner)
                for (st in s.then.stmts) stmt(st, body, loop, results)
                s.elseBranch?.let { stmt(it, scope, loop, results) }
            }

            is WhenStmt -> {
                // Two forms, and the difference is only what an arm's value is compared against: a SUBJECT
                // makes each arm a value to match, and no subject makes each arm a condition of its own.
                val subject = s.subject?.let { expr(it, scope) }
                for (arm in s.arms) {
                    if (subject == null) {
                        condition(arm.value, scope)
                    } else {
                        val got = expr(arm.value, scope, subject)
                        if (!comparable(got, subject)) {
                            error(arm.value.span, "${show(subject)} cannot be matched against ${show(got)}")
                        }
                    }
                    block(arm.body, scope, loop, results)
                }
                s.elseArm?.let { block(it, scope, loop, results) }
            }

            is TryStmt -> {
                block(s.body, scope, loop, results)
                // The message is a STRING and it is bound only inside the catch — there is nothing for it
                // to mean anywhere else, and a name that outlived its handler would read as though the
                // last failure were still available.
                val caught = Scope(scope)
                val bound = LocalBinding(s.error, TypeRef(PinType.STRING), s.span)
                caught.declare(bound, this)
                boundOf[s] = listOf(bound)
                for (st in s.catch.stmts) stmt(st, Scope(caught), loop, results)
            }

            is IfStmt -> {
                condition(s.condition, scope)
                // `if x != null { … }` — inside the branch it is there. The mirrored case, `== null`,
                // narrows the ELSE arm for the same reason.
                val test = nullTest(s.condition, scope)
                val then = Scope(scope)
                test?.let { (binding, isNullTest) ->
                    if (!isNullTest) then.narrow(binding, (scope.narrowedType(binding) ?: binding.type).required())
                }
                for (st in s.then.stmts) stmt(st, then, loop, results)
                s.elseBranch?.let { branch ->
                    val other = Scope(scope)
                    test?.let { (binding, isNullTest) ->
                        if (isNullTest) other.narrow(binding, (scope.narrowedType(binding) ?: binding.type).required())
                    }
                    stmt(branch, other, loop, results)
                }
            }

            is WhileStmt -> {
                condition(s.condition, scope)
                block(s.body, scope, Loop.INSIDE, results)
            }

            is ForStmt -> {
                val over = expr(s.list, scope)
                if (!Unknown.isUnknown(over) && !over.isList) {
                    error(s.list.span, "'for' walks a list, and this is ${show(over)}")
                }
                val element = over.of ?: TypeRef.WILDCARD
                val inner = Scope(scope)
                val bindings = ArrayList<LocalBinding>()
                LocalBinding(s.element, element, s.span).also { inner.declare(it, this); bindings += it }
                // `for (x, i) in xs` — the index is bound only when it was written, so a loop that does
                // not want one does not pay for a register holding it.
                s.index?.let { name ->
                    LocalBinding(name, TypeRef(PinType.INT), s.span).also { inner.declare(it, this); bindings += it }
                }
                loopBindingsOf[s] = bindings
                // **ONE scope for the whole body**, hung off the one holding the loop variables. A scope
                // per STATEMENT — which this was — makes a `val` on one line invisible on the next, and
                // the body of a `for` is where most of them are written.
                val body = Scope(inner)
                for (st in s.body.stmts) stmt(st, body, Loop.INSIDE, results)
            }

            is BreakStmt -> if (!loop.inside) error(s.span, "'break' is only meaningful inside a loop")
            is ContinueStmt -> if (!loop.inside) error(s.span, "'continue' is only meaningful inside a loop")

            is ExprStmt -> expr(s.expr, scope)
            is ExprBlockStmt -> block(s.block, scope, loop, results)

            is ReturnStmt -> {
                if (s.values.size != results.size) {
                    error(
                        s.span,
                        if (results.isEmpty()) "this returns nothing, but ${s.values.size} value(s) are given"
                        else "this returns ${results.size} value(s), but ${s.values.size} are given",
                    )
                }
                for ((i, v) in s.values.withIndex()) {
                    val want = results.getOrNull(i)
                    val got = expr(v, scope, want)
                    if (want != null && !assignable(got, want)) {
                        error(v.span, "returning ${show(got)} where ${show(want)} was declared")
                    }
                }
            }

            /**
             * `sequence { … } { … }` — a CANVAS construct, and not a gap here.
             *
             * On a graph it earns its place: a node has one exec output per arm, and running several
             * chains one after another needs something to say so. In text, statements already run in the
             * order they are written — the arms ARE the statements — so there is nothing for it to mean
             * and nothing to implement. Refused with that said out loud, rather than through the
             * "does not understand … yet" wording, which promises a feature that is never coming.
             */
            is SequenceStmt -> {
                for (arm in s.arms) block(arm, Scope(scope), loop, results)
                error(
                    s.span,
                    "'sequence' is a canvas construct — in text, statements already run in the order they " +
                        "are written, so the blocks can simply follow one another",
                )
            }

            else -> unsupported(s.span, s::class.simpleName ?: "this statement")
        }
    }

    /**
     * `val (a, b) = entityInfo(entity: t)` — several results, bound at once.
     *
     * By NAME when the call site wrote names, by POSITION otherwise. The named form is what survives a
     * function gaining a result: a positional list silently rebinds, and the printer writes labels
     * everywhere else for exactly this reason.
     */
    private fun destructure(s: LetStmt, binding: TupleBinding, scope: Scope) {
        val callee = (s.value as? CallExpr)?.let { expr(it, scope); calleeOf[it] }
        if (callee == null) {
            error(s.span, "only a call gives back several values to bind")
            return
        }
        val results = callee.signature.results
        if (binding.entries.size > results.size) {
            error(s.span, "'${callee.signature.name}' gives back ${results.size} value(s), and ${binding.entries.size} are being bound")
        }
        // Name, position, or a mix — the same matcher a call's arguments go through.
        val slots = matchSlots<TupleEntry>(
            results,
            binding.entries.map { it.pin to it },
        ) { entry, why -> error(entry.span, "'${entry.local}': $why") }
        // In SLOT order, so the compiler can copy result i into binding i without matching again.
        val bound = ArrayList<LocalBinding?>()
        for ((i, slot) in results.withIndex()) {
            val entry = slots.getOrNull(i) as? TupleEntry
            bound += entry?.let {
                LocalBinding(it.local, slot.type, it.span).also { b -> scope.declare(b, this) }
            }
        }
        boundOf[s] = bound.filterNotNull()
        slotsOf[s] = bound
    }

    private fun warn(span: Span, message: String) {
        diagnostics += TextDiagnostic(span, message, Severity.WARNING)
    }

    private fun condition(e: Expr, scope: Scope) {
        val t = expr(e, scope, TypeRef(PinType.BOOL))
        if (!Unknown.isUnknown(t) && t.builtin != PinType.BOOL) {
            error(e.span, "a condition has to be true or false, and this is ${show(t)}")
        }
    }

    // ---- expressions ----------------------------------------------------------------------------------

    /**
     * The type of [e], recorded against it.
     *
     * [expected] is a HINT and never a check — the caller does the checking, because only the caller knows
     * what to say when it fails. It is passed down for the one case that genuinely needs it: an empty list
     * or a bare `null` has no type of its own and takes the one it is being handed to.
     */
    private fun expr(e: Expr, scope: Scope, expected: TypeRef? = null): TypeRef {
        val t = when (e) {
            is LiteralExpr -> when (e.kind) {
                LiteralKind.INT -> TypeRef(PinType.INT)
                LiteralKind.FLOAT -> TypeRef(PinType.FLOAT)
                LiteralKind.BOOL -> TypeRef(PinType.BOOL)
                LiteralKind.STRING -> TypeRef(PinType.STRING)
                // The lexer's `#AARRGGBB` form. `Color` is a type the HOST registers now, so this names
                // it rather than being a builtin — and where no host registers one the literal has a type
                // nothing declares, which is the honest answer: a language with no colour cannot have a
                // colour literal mean anything.
                LiteralKind.COLOR -> TypeRef.named("Color")
                LiteralKind.NULL -> expected?.orNull() ?: NULL
            }

            is NameExpr -> name(e, scope)

            is NotExpr -> {
                condition(e.operand, scope)
                TypeRef(PinType.BOOL)
            }

            is BinaryExpr -> binary(e, scope)

            is TernaryExpr -> {
                condition(e.condition, scope)
                val a = expr(e.ifTrue, scope, expected)
                val b = expr(e.ifFalse, scope, expected)
                // The wider of the two, and a complaint when neither is: `c ? 1 : "x"` has no type, and
                // picking the first arm's would make the second one's a lie.
                when {
                    assignable(b, a) -> a
                    assignable(a, b) -> b
                    else -> {
                        error(e.span, "the two arms are ${show(a)} and ${show(b)}, which have no common type")
                        TypeRef.WILDCARD
                    }
                }
            }

            is ListLitExpr -> {
                val items = e.items.map { expr(it, scope, expected?.of) }
                val element = elementOf(items)
                if (element == null && items.isNotEmpty()) {
                    error(e.span, "the items of this list have no common type: ${items.joinToString { show(it) }}")
                }
                // An empty literal takes the element type of whatever it is being handed to — that is what
                // makes `val xs: LIST<INT> = []` mean something.
                TypeRef.list(element ?: expected?.of)
            }

            is IndexExpr -> {
                val target = expr(e.target, scope)
                when {
                    Unknown.isUnknown(target) -> {
                        expr(e.index, scope)
                        TypeRef.WILDCARD
                    }
                    // A list keeps its own rule and its own instruction. `Op.INDEX` is one opcode and
                    // needs no declaration to exist, so making lists go through an operator would cost
                    // the language's commonest read a lookup to arrive back where it started.
                    target.isList -> {
                        val index = expr(e.index, scope, TypeRef(PinType.INT))
                        if (!Unknown.isUnknown(index) && index.builtin != PinType.INT) {
                            error(e.index.span, "a list is indexed by a whole number, and this is ${show(index)}")
                        }
                        target.of ?: TypeRef.WILDCARD
                    }
                    // **Anything else asks the TYPE what `[` means** — an `op fn <T>.get`. Rewritten into
                    // an ordinary extension call so the compiler needs no case for it: the existing path
                    // finds the declaration, checks the argument against the declared parameter (so a map
                    // keyed by String reports a String, not "a whole number"), and splices it, because
                    // `op` implies `inline`.
                    else -> {
                        val call = CallExpr(listOf("get"), listOf(Arg(null, e.index, e.index.span)), e.span)
                        indexCallOf[e] = call
                        val got = extensionCall(call, e.target, scope, "get")
                        if (extensionsFor(target, "get").isEmpty()) {
                            error(
                                e.span,
                                "${show(target)} cannot be indexed — a type gets '[' by declaring " +
                                    "'op fn ${show(target)}.get(self, …)'",
                            )
                        }
                        got
                    }
                }
            }

            is LambdaExpr -> lambda(e, scope, expected)

            is ElvisExpr -> {
                val value = expr(e.value, scope, expected?.orNull())
                val fallback = expr(e.fallback, scope, expected)
                if (!Unknown.isUnknown(value) && !value.optional) {
                    // Not an error: it is dead code rather than wrong code, and saying so is more use than
                    // refusing something that behaves exactly as written.
                    warn(e.span, "the left of '?:' is ${show(value)}, which is never absent — the fallback cannot run")
                }
                widened(value.required(), fallback) ?: run {
                    error(e.span, "'?:' gives back ${show(value.required())} or ${show(fallback)}, which have no common type")
                    TypeRef.WILDCARD
                }
            }

            is AsExpr -> {
                // `readJson(path: …) as Break` — a DECODE, and it may fail, so the answer is optional.
                // That is not a hedge: the file may be absent, truncated, or written by an older shape,
                // and every use in the corpus already wraps it in `if val`.
                expr(e.value, scope)
                val target = typeOf(TypeExpr(e.typeName, e.typeArgs, e.span, e.module))
                if (Unknown.isUnknown(target)) TypeRef.WILDCARD else target.orNull()
            }

            is MemberExpr -> member(e, scope)

            is StructLitExpr -> structLit(e, scope)

            is CallExpr -> call(e, scope)

            /**
             * `x is Point`, `x !is Point` — a run-time type TEST, answering true or false.
             *
             * Never a narrowing: it hands back a BOOL and nothing else, so the branch it guards does not
             * learn anything about `x`. `if val` is how you narrow, and this is how you ASK.
             */
            is IsExpr -> {
                expr(e.value, scope)
                // Named rather than inferred, so a name nobody declared is caught here instead of
                // silently answering false at run time for the rest of the script's life.
                if (typeNamed(e.typeName) == null) {
                    error(e.span, "no type called '" + e.typeName + "'")
                }
                TypeRef(PinType.BOOL)
            }

            /**
             * `a?.b.c` — read through something that might not be there.
             *
             * The whole chain after the `?.` is the ACCESS, with [SafeItExpr] standing where the receiver
             * goes, so `a?.b.c` is one test and not two. The result is optional whatever the access
             * produced: the answer is absent exactly when the receiver was.
             */
            is SafeAccessExpr -> {
                val receiver = expr(e.receiver, scope)
                safeReceivers.addLast(receiver.required())
                val accessed = try { expr(e.access, scope) } finally { safeReceivers.removeLast() }
                if (Unknown.isUnknown(accessed)) accessed else accessed.orNull()
            }

            // Only ever built by the parser, and only ever inside a `?.` — it IS the receiver, already
            // known not to be null by the time the access runs.
            is SafeItExpr -> safeReceivers.lastOrNull() ?: TypeRef.WILDCARD

            else -> {
                unsupported(e.span, e::class.simpleName ?: "this expression")
                TypeRef.WILDCARD
            }
        }
        typeOf[e] = t
        return t
    }

    /**
     * What the receiver of the `?.` currently being resolved is, with its `?` taken off.
     *
     * A stack because they nest — `a?.b?.c` resolves the inner one while the outer is still open — and the
     * access is an ordinary expression tree that must not be able to see past its own `?.`.
     */
    private val safeReceivers = ArrayDeque<TypeRef>()

    /** Any type this document can name — its own, its imports', the prelude's, the host's. */
    private fun typeNamed(name: String): TypeRef? =
        records[name]?.ref ?: enums[name]?.ref ?: importedRecord(name)?.ref ?: importedEnum(name)?.ref
            ?: Prelude.type(name) ?: Prelude.hostEnum(name)?.ref
            ?: dev.ziggle.vscript.model.HostRecords.of(name)?.type


    /**
     * Put the document's initialisers in DEPENDENCY order, not declaration order.
     *
     * A computed initialiser becomes a prologue step, and the prologue used to run in the order the
     * declarations happened to be typed. So this failed:
     *
     * ```
     * single Config { Target: Int = Ids.Value + 1 }
     * single Ids    { Value: Int = 41 }
     * ```
     *
     * `Config` was seeded first, read `Ids` before anything had built it, and the run died with
     * `GETFIELD on null, expected a record` — pointing at the prologue, naming neither variable. Moving
     * the declaration fixed it, which is exactly the property this language refuses everywhere else:
     * `resolve` reads every declaration before any body precisely so that meaning does not depend on the
     * order somebody typed things in, and initialisation was the one place it still did.
     *
     * It looked like a `single`-only problem and is not. A LITERAL initialiser rides in the starting
     * values and needs no prologue at all, so `val B: Int = 41` is always ready — which is why plain
     * values seemed fine. `val A: Int = B + 1` with `val B: Int = 40 + 1` fails the same way. A `single`
     * simply hits it every time, its value being a synthesised record literal and never a literal.
     *
     * **Stable**, so independent initialisers keep the order they were written in — the order a reader
     * expects side effects in — and only the ones that actually depend on something move.
     */
    private fun orderInits() {
        if (globalInits.size < 2) return
        val at = HashMap<DocumentBinding, Int>()
        globalInits.forEachIndexed { i, g -> at[g.binding] = i }
        val needs = globalInits.map { g ->
            val bindings = LinkedHashSet<DocumentBinding>()
            namesIn(g.value).mapNotNullTo(bindings) { bindingOf[it] as? DocumentBinding }
            // **An enum's columns are document variables too**, hidden ones seeded by the same prologue —
            // so `single Config { Want: Int = Roster.A.target }` depends on `Roster`'s columns exactly as
            // it would on any other variable. Missed, it did not fail loudly: the column was still null
            // when Config read it and `Want` was silently null for the rest of the run.
            enumColumnsIn(g.value, bindings)
            bindings.mapNotNull { at[it] }.filterTo(LinkedHashSet()) { it != at[g.binding] }
        }

        val done = BooleanArray(globalInits.size)
        val open = BooleanArray(globalInits.size)
        val out = ArrayList<GlobalInit>(globalInits.size)
        fun visit(i: Int) {
            if (done[i]) return
            if (open[i]) {
                // A genuine ring: no order can satisfy it, and saying which two variables are involved is
                // the whole difference between this and a null nobody can trace.
                error(
                    globalInits[i].value.span,
                    "'" + globalInits[i].binding.name + "' needs itself to start up, directly or through " +
                        "something it reads — one of them has to start from a value that does not depend " +
                        "on the others",
                )
                return
            }
            open[i] = true
            for (d in needs[i]) visit(d)
            open[i] = false
            done[i] = true
            out += globalInits[i]
        }
        for (i in globalInits.indices) visit(i)
        // Anything a cycle stopped is still emitted, in its written order, so one bad ring does not take
        // the rest of the document's start-up with it.
        for (i in globalInits.indices) if (!done[i]) out += globalInits[i]
        globalInits.clear()
        globalInits += out
    }

    /**
     * Every hidden enum-column variable [e] reads.
     *
     * `Enum.Member.column` is named precisely. Anything else that merely mentions an enum — `values()`, a
     * member passed around — depends on ALL of its columns, which over-approximates and is the safe
     * direction: an extra ordering constraint can only move an initialiser earlier than it had to be.
     */
    private fun enumColumnsIn(e: Expr?, into: MutableSet<DocumentBinding>) {
        val seen = HashSet<String>()
        fun rootName(x: Expr?): String? = when (x) {
            is NameExpr -> x.name
            is MemberExpr -> rootName(x.target)
            is IndexExpr -> rootName(x.target)
            else -> null
        }
        fun walk(x: Expr?) {
            when (x) {
                null -> Unit
                is MemberExpr -> {
                    val root = rootName(x)
                    val enum = root?.let { enums[it] }
                    if (enum != null) {
                        val exact = enum.columnVars[x.member]
                        if (exact != null) into += exact else into += enum.columnVars.values
                    }
                    walk(x.target)
                }
                is NameExpr -> enums[x.name]?.let { into += it.columnVars.values }
                is IndexExpr -> { walk(x.target); walk(x.index) }
                is BinaryExpr -> { walk(x.left); walk(x.right) }
                is NotExpr -> walk(x.operand)
                is TernaryExpr -> { walk(x.condition); walk(x.ifTrue); walk(x.ifFalse) }
                is ElvisExpr -> { walk(x.value); walk(x.fallback) }
                is SafeAccessExpr -> { walk(x.receiver); walk(x.access) }
                is IsExpr -> walk(x.value)
                is AsExpr -> { walk(x.value); x.renames.forEach { walk(it.value) } }
                is WithExpr -> { walk(x.target); x.fields.forEach { walk(it.value) } }
                is ListLitExpr -> x.items.forEach { walk(it) }
                is StructLitExpr -> {
                    x.fields.forEach { walk(it.value) }
                    // And the defaults it did not supply — a `single`'s initialiser supplies NOTHING, so
                    // this is where all of its dependencies actually live. Same omission, same cure, as in
                    // [namesIn]: without it a `single` reading an enum column looked to depend on nothing.
                    val record = records[x.type] ?: importedRecord(x.type)
                    if (record != null && seen.add(record.name)) {
                        for (f in record.fields) {
                            if (x.fields.none { it.name == f.name }) walk(f.defaultExpr)
                        }
                    }
                }
                is CallExpr -> { walk(x.receiver); x.args.forEach { walk(it.value) } }
                else -> Unit
            }
        }
        walk(e)
    }

    /** Every name mentioned anywhere in [e]. Conservative: a shape it misses simply keeps written order. */
    private fun namesIn(e: Expr?): List<NameExpr> {
        val out = ArrayList<NameExpr>()
        val seen = HashSet<String>()
        fun walk(x: Expr?) {
            when (x) {
                null -> Unit
                is NameExpr -> out += x
                is MemberExpr -> walk(x.target)
                is IndexExpr -> { walk(x.target); walk(x.index) }
                is BinaryExpr -> { walk(x.left); walk(x.right) }
                is NotExpr -> walk(x.operand)
                is TernaryExpr -> { walk(x.condition); walk(x.ifTrue); walk(x.ifFalse) }
                is ElvisExpr -> { walk(x.value); walk(x.fallback) }
                is SafeAccessExpr -> { walk(x.receiver); walk(x.access) }
                is IsExpr -> walk(x.value)
                is AsExpr -> { walk(x.value); x.renames.forEach { walk(it.value) } }
                is WithExpr -> { walk(x.target); x.fields.forEach { walk(it.value) } }
                is ListLitExpr -> x.items.forEach { walk(it) }
                is StructLitExpr -> {
                    x.fields.forEach { walk(it.value) }
                    // **And the defaults of the fields it did NOT supply.** A `single`'s initialiser is a
                    // struct literal with no fields at all — the whole record is its defaults — so without
                    // this it looks to depend on nothing and `single Config { Target: Int = Ids.Value }`
                    // orders as if it were a constant. Guarded against a ring of records, which is a
                    // separate error reported at the declaration.
                    val record = records[x.type] ?: importedRecord(x.type)
                    if (record != null && seen.add(record.name)) {
                        for (f in record.fields) {
                            if (x.fields.none { it.name == f.name }) walk(f.defaultExpr)
                        }
                    }
                }
                is CallExpr -> { walk(x.receiver); x.args.forEach { walk(it.value) } }
                is LambdaExpr -> Unit // its body runs later, not while the prologue is seeding.
                else -> Unit
            }
        }
        walk(e)
        return out
    }

    /** What every enum member answers to, when its enum has no column of that name. */
    private val NAME_OF_MEMBER = "name"

    private fun name(e: NameExpr, scope: Scope): TypeRef {
        e.module?.let { alias ->
            val module = moduleFor(alias, e.span) ?: return TypeRef.WILDCARD
            val found = bindingsOf(module)[e.name]
            if (found == null) {
                // Distinguished on purpose: a name that is there but private is a different problem from
                // one that is not there, and the fix is different too.
                val private = module.resolution.localOf.values.none { it.name == e.name }
                error(e.span, "'${module.ref}' does not export '${e.name}'" + if (private) "" else " — it is private")
                return TypeRef.WILDCARD
            }
            bindingOf[e] = found
            return found.type
        }
        val local = scope.find(e.name) ?: document[e.name] ?: imported(e.name)
        if (local != null) {
            bindingOf[e] = local
            if (local is LocalBinding) capture(local, scope.holderOf(e.name)?.owner)
            // What it is HERE, which a null test may have made narrower than what it was declared.
            return scope.narrowedType(local) ?: local.type
        }
        // Not a name in scope, so it is a nullary call — the language's resolution order, and the reason
        // `inventoryFull` reads as a fact rather than as a function.
        val native = natives[e.name]
        if (native != null) {
            if (native.params.any { !it.hasDefault }) {
                error(e.span, "'${e.name}' needs arguments")
                return TypeRef.WILDCARD
            }
            bindingOf[e] = NativeBinding(native)
            return native.signature.resultType
        }
        error(e.span, "nothing here is called '${e.name}'")
        return TypeRef.WILDCARD
    }

    /**
     * Note that [binding] has to travel into every lambda that does not own it.
     *
     * Every OPEN lambda whose scope does not hold the name captures it, not just the innermost: a name
     * read two lambdas deep has to be carried through the outer one as well, because the inner value is
     * built inside the outer's frame and can only copy from registers that frame actually has.
     *
     * A document variable is never captured — it is a global, reachable from any frame by slot, which is
     * exactly why globals exist.
     */
    private fun capture(binding: LocalBinding, ownerOfName: LambdaExpr?) {
        for (lambda in openLambdas) {
            if (lambda === ownerOfName) break
            val list = capturesOf.getOrPut(lambda) { ArrayList() }
            if (list.none { it === binding }) list += binding
        }
    }

    private fun binary(e: BinaryExpr, scope: Scope): TypeRef {
        if (e.op == BinaryOp.AND_THEN || e.op == BinaryOp.OR_ELSE) {
            condition(e.left, scope)
            condition(e.right, scope)
            return TypeRef(PinType.BOOL)
        }
        val a = expr(e.left, scope)
        val b = expr(e.right, scope)
        return when (e.op) {
            BinaryOp.EQ, BinaryOp.NE -> {
                if (!comparable(a, b)) error(e.span, "${show(a)} and ${show(b)} cannot be compared")
                TypeRef(PinType.BOOL)
            }

            BinaryOp.LT, BinaryOp.LE, BinaryOp.GT, BinaryOp.GE -> {
                if (!ordered(a, b)) error(e.span, "${show(a)} and ${show(b)} have no order")
                TypeRef(PinType.BOOL)
            }

            else -> arithResult(symbol(e.op), a, b) ?: run {
                error(e.span, "${show(a)} ${symbol(e.op)} ${show(b)} is not something the language can do")
                TypeRef.WILDCARD
            }
        }
    }

    /**
     * `{ it * 2 }` — a function written where it is used.
     *
     * **Its parameter types come from where it is GOING**, which is the one place they can come from: a
     * lambda writes names and never types, so `{ it * 2 }` is only meaningful once something says what
     * `it` is. Without a destination there is nothing to say, and that is an error rather than a guess —
     * a guessed parameter type is a wrong answer somewhere further along.
     */
    private fun lambda(e: LambdaExpr, scope: Scope, expected: TypeRef?): TypeRef {
        if (expected == null || !expected.isFunction) {
            error(e.span, "there is nothing here to say what this lambda's parameters are")
            return TypeRef.WILDCARD
        }
        val wantParams = expected.paramsOf
        // No names written means the implicit `it` — unless an arrow was, which is the only thing telling
        // `{ -> f() }` (takes nothing) from `{ f() }` (takes one).
        val names = when {
            e.params.isNotEmpty() -> e.params
            e.arrow -> emptyList()
            // **The destination decides the arity too.** A bare `{ … }` is the implicit `it` — but only
            // where something wants one parameter. Where a `fn()` is wanted it takes none, which is what
            // an author writing `{ log(…) }` into a callback plainly means, and insisting on `{ -> … }`
            // there would be ceremony for a distinction nothing is asking about.
            wantParams.isEmpty() -> emptyList()
            else -> listOf(LambdaExpr.IT)
        }
        if (wantParams.isNotEmpty() && names.size != wantParams.size) {
            error(e.span, "this wants ${wantParams.size} parameter(s) and the lambda takes ${names.size}")
        }

        val inner = Scope(scope, owner = e)
        val params = names.mapIndexed { i, n ->
            LocalBinding(n, wantParams.getOrNull(i) ?: TypeRef.WILDCARD, e.span)
                .also { inner.declare(it, this) }
        }
        lambdaParamsOf[e] = params
        capturesOf.getOrPut(e) { ArrayList() }

        openLambdas.addLast(e)
        val want = expected.resultOf
        // **`return` inside a lambda returns from the LAMBDA.** It compiles to an `Op.RET` in the lambda's
        // own chunk, so the semantics were always right; what was missing was checking it against the
        // right thing. Given `emptyList()` here, `return n` in a `fn(INT) -> INT` was refused as "this
        // returns nothing" — the one construct a block body most obviously wants.
        val wants = if (want == null || isNothing(want)) emptyList() else listOf(want)
        // The lambda's own block, in the lambda's own scope — so a `val` here is visible to the statements
        // after it and to the result expression, and invisible outside. `Loop.NONE`: a `break` inside a
        // lambda would be breaking the enclosing loop from inside a different frame, which is not
        // something this can mean.
        for (st in e.stmts) stmt(st, inner, Loop.NONE, wants)
        val body = e.body?.let { expr(it, inner, want) } ?: TypeRef.NOTHING
        openLambdas.removeLast()

        if (e.body != null && want != null && !isNothing(want) && !assignable(body, want)) {
            error(e.body.span, "this lambda gives back ${show(body)} where ${show(want)} was wanted")
        }
        // What it hands back: its last expression, or — when it has none — whatever its `return`s hand
        // back. A lambda that is all early exits (`{ if x { return 1 }  return 2 }`) has no trailing
        // expression and is emphatically not a lambda that returns nothing; typed as one, it was refused
        // by the very destination that named its result type.
        val result = when {
            e.body != null -> body
            e.stmts.any { returnsAValue(it) } -> want ?: TypeRef.WILDCARD
            else -> TypeRef.NOTHING
        }
        return TypeRef.function(params.map { it.type }, result)
    }

    /** A function-typed binding, as something a call site can be checked against. */
    private fun signatureOf(binding: Binding): Signature {
        val t = binding.type
        return Signature(
            binding.name,
            t.paramsOf.mapIndexed { i, p -> Param("arg${i + 1}", p) },
            listOfNotNull(t.returnsOf).map { Param(RESULT, it) },
        )
    }

    /**
     * The record behind a type, wherever it was declared — here, the prelude, or any module in the run.
     *
     * **By OWNER when the type has one**, which is the whole point of carrying one. A `TypeRef` used to be
     * only a name, so the fields could only be found by looking that name up again in the *reader's* scope
     * — and a reader that never imported the declaring module has no such name. That is the two-hop
     * failure exactly: `geo` declares `Point`, `mid` exports `type Leg { from: Point }`, and `probe`
     * imports only `Leg`, so `l.from.x` reported "Point has no fields" about a record declared perfectly
     * well one document further out.
     *
     * The name path stays underneath for everything with no owner — the prelude, host types, and any ref
     * built before a resolver saw it.
     */
    private fun recordFor(t: TypeRef): RecordType? {
        val owner = t.owner
        if (owner != null) {
            val simple = t.simpleName
            if (owner == ref) records[simple]?.let { return it }
            modules?.byRef(owner)?.resolution?.let { r ->
                // Its exports first, then everything it declares: a type may legitimately be reached
                // through a field of an exported record without being exported itself, and refusing that
                // would put the export keyword in the way of reading a value the module already handed out.
                (r.exportedRecords[simple] ?: r.records[simple])?.let { return it }
            }
        }
        return records[t.name] ?: importedRecord(t.name) ?: Prelude.record(t)
            ?: Prelude.hostRecord(t.name)
    }

    private fun member(e: MemberExpr, scope: Scope): TypeRef {
        // `entityInfo(e).distance` — one of the OTHER results, named. A call's type is its first result,
        // which is right nearly always and wrong for exactly this, and a destructuring is a heavy way to
        // reach for one value.
        (e.target as? CallExpr)?.let { call ->
            expr(call, scope)
            val results = calleeOf[call]?.signature?.results.orEmpty()
            if (results.size > 1) {
                val i = results.indexOfFirst { looseEquals(it.name, e.member) }
                if (i >= 0) {
                    resultPick[e] = i
                    return results[i].type
                }
            }
        }
        // `Roster.Herbs` — the target names a TYPE, not a value, so it is asked BEFORE the target is
        // resolved: resolving it first would report `Roster` as an unknown name and never get here.
        (e.target as? NameExpr)?.let { head ->
            // `jobs::Job.Herbs` too: the alias says which document to ask, and an enum is not a binding
            // so it is not in `exports` — asking there is what reported it as unexported.
            val enum = head.module?.let { imports.aliased[it]?.resolution?.exportedEnums?.get(head.name) }
                ?: (if (head.module == null) enumNamed(head.name) else null)
            enum?.let {
                if (!it.members.contains(e.member)) {
                    error(e.span, "'${it.name}' has no member '${e.member}' — it has ${it.members.joinToString()}")
                    return TypeRef.WILDCARD
                }
                return it.ref
            }
        }
        val target = expr(e.target, scope)
        if (Unknown.isUnknown(target)) return TypeRef.WILDCARD
        if (target.optional) {
            error(e.span, "${show(target)} may be absent — check it, or use '?.'")
            return TypeRef.WILDCARD
        }
        // A record the DOCUMENT declared, or one the prelude gives structure to. `t.x` on a tile has never
        // been sayable before, and this is the whole of why it is now.
        // A column of an enum MEMBER — `it.priority`, read across the baked-in column.
        enumFor(target)?.let { enum ->
            val type = enum.columnType(e.member)
            // **`.name` is every enum member's, unless the enum declared a column called that.**
            // A member IS its name at run time, so this reads what is already in the register — but until
            // now there was no way to SAY it, and an enum member could not be logged, joined into a
            // message or written to a file without a column duplicating what the member already was.
            // The column wins where one exists (`Roster` declares `name: String`), so nothing that
            // resolves today moves.
            if (type == null && e.member == NAME_OF_MEMBER) return TypeRef(PinType.STRING)
            if (type == null) {
                error(e.span, "'${enum.name}' has no column '${e.member}'" +
                    if (enum.columns.isEmpty()) " — it declares none" else " — it has ${enum.columns.joinToString { it.name }}")
                return TypeRef.WILDCARD
            }
            return type
        }
        val record = recordFor(target)
        if (record == null) {
            error(e.span, "${show(target)} has no fields")
            return TypeRef.WILDCARD
        }
        val fields = fieldsOf(target, record)
        val field = fields.firstOrNull { it.name == e.member }
        if (field == null) {
            error(e.span, "${show(target)} has no field '${e.member}' — it has ${fields.joinToString { it.name }}")
            return TypeRef.WILDCARD
        }
        return field.type
    }

    /**
     * `Trip { bank: "Varrock", laps: 3 }` — a record, built.
     *
     * Every field is checked and the UNSUPPLIED ones are left alone rather than filled in: what a literal
     * writes is what it wrote, and the declaration stays the one place a default is stated.
     */
    private fun structLit(e: StructLitExpr, scope: Scope): TypeRef {
        val record = e.module?.let { alias ->
            moduleFor(alias, e.span)?.resolution?.exportedRecords?.get(e.type)
        } ?: records[e.type] ?: importedRecord(e.type) ?: Prelude.type(e.type)?.let { Prelude.record(it) }
            // A DATA record the host declared — `Tile { x: …, y: …, plane: … }`. Last, so a document that
            // declares its own `Tile` shadows rather than collides, which is how every host type behaves
            // here. Only data records: an accessor record is a handle to something the host owns and
            // there is nothing for a literal to make.
            ?: Prelude.dataRecord(e.type)
        if (record == null) {
            error(e.span, "no type called '${e.type}'")
            for (f in e.fields) expr(f.value, scope)
            return TypeRef.WILDCARD
        }
        val seen = HashSet<String>()
        // **Typed first, checked second, and that order is what makes a generic record work.** For
        // `Pair<A, B>` the declared field types are variables, so there is nothing to check a value
        // against until every value has been seen and the variables are bound. Non-generic records take
        // exactly the same path: `unify` binds nothing and `substitute` is the identity, so there is one
        // code path rather than two that could disagree.
        val bindings = HashMap<String, TypeRef>()
        val supplied = ArrayList<Triple<dev.ziggle.vscript.lang.FieldInit, Param, TypeRef>>()
        for (f in e.fields) {
            val field = record.field(f.name)
            if (field == null) {
                error(f.span, "'${record.name}' has no field '${f.name}' — it has ${record.fields.joinToString { it.name }}")
                expr(f.value, scope)
                continue
            }
            if (!seen.add(f.name)) error(f.span, "'${f.name}' is given twice")
            val got = expr(f.value, scope, field.type)
            unify(field.type, got, bindings)
            supplied += Triple(f, field, got)
        }
        for ((f, field, got) in supplied) {
            val want = substitute(field.type, bindings)
            if (!assignable(got, want)) {
                error(f.value.span, "'${f.name}' is ${show(want)} but is being given ${show(got)}")
            } else {
                literal(f.value, want)
            }
        }
        // A field with no default and no value is null at run time, which GAPS 17 is about. Reported here
        // rather than silently, because the resolver is the first thing in this language that KNOWS.
        for (field in record.fields) {
            if (field.hasDefault) continue
            if (e.fields.none { it.name == field.name } && !field.type.optional) {
                error(e.span, "'${record.name}' needs '${field.name}', which has no default")
            }
        }
        // A variable no field pinned down stays a variable rather than becoming a wildcard: `Box { }` with
        // nothing supplied is genuinely `Box<T>`, and saying so lets the value's destination decide.
        if (record.params.isEmpty()) return record.ref
        return record.ref.withArgs(record.params.map { bindings[it] ?: TypeRef.named(it).asVariable() })
    }

    private fun call(e: CallExpr, scope: Scope): TypeRef {
        e.receiver?.let { return extensionCall(e, it, scope) }
        // **A dotted head is ambiguous in the grammar and settled here.** `xs.add(v)` and `draw.text(…)`
        // parse identically — the parser collects the dots into `target` because that is also how a node
        // TYPE is spelled, and teaching it to stop at the first dot would need it to know whether `draw`
        // is a namespace or a value, which is the thing it refuses to know. So: if the head names
        // something in scope, the call is written ON it.
        // **A node's own dotted name wins.** `draw.tile(…)` is one name, and the corpus has locals called
        // `draw` — reading the head as a value first made a panel-drawing script call a method on its own
        // callback. Checked before the head, because `draw.tile` is not ambiguous: it either IS a node or
        // it is not, and nothing else is spelled that way.
        if (e.module == null && e.target.size > 1 && natives[e.name] != null) {
            return checkCall(e, NativeCallee(natives.getValue(e.name)), scope)
        }
        if (e.module == null && e.target.size > 1) {
            val head = e.target.first()
            if (scope.find(head) != null || document.containsKey(head) || imported(head) != null) {
                return extensionCall(e, receiverPath(e.target.dropLast(1), e.span), scope, e.target.last())
            }
            // `Roster.values()` — every member, in declaration order.
            if (e.target.size == 2 && e.target.last() == VALUES) {
                enumNamed(head)?.let { return TypeRef.list(it.ref) }
            }
            // `Skill.of("Farming")` — the way BACK from a name, and the counterpart of `.name`.
            //
            // **Optional, because it can fail.** A name arriving from a chat line, a saved file or a
            // config field may be anything, and answering with a member that does not exist would put the
            // problem back where it was. `if val` at the call site is the whole ceremony.
            if (e.target.size == 2 && e.target.last() == OF) {
                enumNamed(head)?.let { enum ->
                    val given = e.args.firstOrNull()
                    if (e.args.size != 1) {
                        error(e.span, "'" + head + ".of' takes one name")
                    } else {
                        val got = expr(given!!.value, scope, TypeRef(PinType.STRING))
                        if (!assignable(got, TypeRef(PinType.STRING))) {
                            error(given.value.span, "'" + head + ".of' takes a name, and this is " + show(got))
                        }
                    }
                    return enum.ref.orNull()
                }
            }
            // `Want.carry(…)` — the head names a TYPE, so this is the function declared ON it.
            typeLevelFn(head, e.target.last())?.let { return checkCall(e, FunctionCallee(it), scope) }
        }

        val name = e.name
        e.module?.let { alias ->
            val module = moduleFor(alias, e.span) ?: return TypeRef.WILDCARD
            val fn = bindingsOf(module)[name] as? FunctionBinding
            if (fn == null) {
                error(e.span, "'${module.ref}' does not export a function called '$name'")
                for (a in e.args) expr(a.value, scope)
                return TypeRef.WILDCARD
            }
            return checkCall(e, ImportedCallee(module, fn), scope)
        }
        // A local or a variable HOLDING a function is called through its value. Checked before the
        // document's own functions for the ordinary reason a local wins: the nearer name is the one an
        // author means.
        val holder = scope.find(name) ?: document[name]
        val callee: Callee = when {
            // A function the DOCUMENT declares is called by name, never through a value — its binding has
            // a function type too, so this has to be asked first or every declared call becomes a dynamic
            // one with invented parameter names.
            // Before everything, including a local of the same name: these three are not values and
            // cannot be shadowed by one, because there is nothing to shadow — they are a shape the
            // compiler emits rather than a function that exists.
            Intrinsic[name] != null -> IntrinsicCallee(Intrinsic[name]!!)
            holder is FunctionBinding -> FunctionCallee(holder)
            holder != null && holder.type.isFunction -> {
                if (holder is LocalBinding) capture(holder, scope.holderOf(name)?.owner)
                bindingOf[NameExpr(name, e.span)] = holder
                ValueCallee(holder, signatureOf(holder))
            }
            document[name] is FunctionBinding -> FunctionCallee(document[name] as FunctionBinding)
            natives[name] != null -> NativeCallee(natives.getValue(name))
            // Brought in by a bare star or a named import — visible as itself, which is the whole point of
            // those two spellings.
            imported(name) is FunctionBinding -> importedCallee(name)
            else -> {
                error(e.span, "no function called '$name'")
                for (a in e.args) expr(a.value, scope)
                return TypeRef.WILDCARD
            }
        }
        return checkCall(e, callee, scope)
    }

    /**
     * `xs.first()` — found by the receiver's type, then checked as an ordinary call with the receiver
     * standing in for `self`.
     *
     * A receiver whose type the resolver could not work out is left alone rather than guessed at: with no
     * type there is no way to choose between two extensions of the same name, and choosing wrong is worse
     * than saying so.
     */
    /** `a.b.c()` — the receiver is everything before the last dot, rebuilt as an expression. */
    private fun receiverPath(parts: List<String>, span: Span): Expr {
        var e: Expr = NameExpr(parts.first(), span)
        for (p in parts.drop(1)) e = MemberExpr(e, p, span)
        return e
    }

    private fun extensionCall(
        e: CallExpr,
        receiver: Expr,
        scope: Scope,
        name: String = e.name,
    ): TypeRef {
        val on = expr(receiver, scope)
        if (Unknown.isUnknown(on)) {
            for (a in e.args) expr(a.value, scope)
            return TypeRef.WILDCARD
        }
        // A built-in conversion — `3.toItem()`. Before the user's extensions, because these are part of
        // the language and nothing should be able to shadow one.
        Intrinsic.on(on, name)?.let { return checkCall(e, IntrinsicCallee(it), scope, receiver) }
        // **A FIELD first.** `j.run()` where `run` is a column holding a function is a call THROUGH the
        // value, not an extension on its type — and a field wins because an extension's name can be
        // aliased at the import and a field's cannot.
        fieldFunction(on, name)?.let { fn ->
            val member = MemberExpr(receiver, name, e.span)
            typeOf[member] = fn
            return checkCall(e, FieldCallee(member, signatureOf(LocalBinding(name, fn, e.span))), scope)
        }
        val candidates = extensionsFor(on, name)
        if (candidates.isEmpty()) {
            // **The HOST's extensions, asked last.** Same precedence host records and host enums get: a
            // document's own declarations and its imports win, so a script can shadow a domain verb
            // without colliding with it. The receiver is argument zero, so this is an ordinary host call
            // once it has been found — see `NativeFn.receiver`.
            hostExtension(on, name)?.let { return checkCall(e, NativeCallee(it), scope, receiver) }
            error(e.span, "${show(on)} has no '$name'")
            for (a in e.args) expr(a.value, scope)
            return TypeRef.WILDCARD
        }
        if (candidates.size > 1) {
            error(e.span, "'$name' is offered for ${show(on)} by more than one import")
        }
        val binding = candidates.first()
        val module = moduleOwning(binding)
        // The receiver IS the first argument. Dropping `self` from the signature and passing the receiver
        // separately would make an extension a second kind of call; keeping it makes it the same one.
        val sig = binding.signature
        return checkCall(e, ExtensionCallee(binding, module, sig, receiver), scope, receiver)
    }

    /**
     * A function declared on a TYPE — this document's, an imported one's, or one forwarded by a re-export.
     *
     * The last of those matters as much as the first: a front door that re-exports a barrel forwards the
     * type-level functions with it, or `Want.carry(…)` works through the barrel and not through the door.
     */
    private fun typeLevelFn(type: String, name: String): FunctionBinding? {
        typeLevel[type]?.get(name)?.let { return it }
        for (m in imports.unqualified) m.resolution.typeLevel[type]?.get(name)?.let { return it }
        for (m in imports.aliased.values) m.resolution.typeLevel[type]?.get(name)?.let { return it }
        for ((m, _) in imports.named.values) m.resolution.typeLevel[type]?.get(name)?.let { return it }
        return null
    }

    /** A field or enum column of [on] called [name] that holds a function, or null. */
    private fun fieldFunction(on: TypeRef, name: String): TypeRef? {
        val type = recordFor(on)?.field(name)?.type ?: enumFor(on)?.columnType(name)
        return type?.takeIf { it.isFunction }
    }

    /** Which imported module declared [binding], or null when this document did. */
    private fun moduleOwning(binding: FunctionBinding): Module? {
        for (m in imports.unqualified) if (m.resolution.extensions.values.any { it.contains(binding) }) return m
        for (m in imports.aliased.values) if (m.resolution.extensions.values.any { it.contains(binding) }) return m
        return null
    }

    /** Which module offers [name] unqualified, as a callee. */
    private fun importedCallee(name: String): Callee {
        imports.named[name]?.let { (module, exportedAs) ->
            (bindingsOf(module)[exportedAs] as? FunctionBinding)?.let { return ImportedCallee(module, it) }
        }
        for (module in imports.unqualified) {
            (bindingsOf(module)[name] as? FunctionBinding)?.let { return ImportedCallee(module, it) }
        }
        error(Span.NONE, "'$name' was offered by an import and then was not")
        return NativeCallee(NativeFn(name))
    }

    /**
     * Match what was written against named slots — **by name, by position, or a mix**.
     *
     * One function for two questions that are the same question: a call's arguments against its
     * parameters, and a destructuring's names against a callee's results. They were matched separately
     * and drifted immediately — arguments allowed a mix and destructuring was positional only, so
     * `val (name) = itemInfo(itm)` took the FIRST result rather than the one called `name`, and then
     * compared an item id against a string.
     *
     * A bare entry binds by name when its own name IS a slot's — which is what makes
     * `val (exists, distance) = entityInfo(…)` independent of the order the pins were declared in — and
     * otherwise takes the next slot nothing has claimed. An explicit `local: Pin` always binds that pin.
     */
    private fun <T> matchSlots(
        slots: List<Param>,
        written: List<Pair<String?, T>>,
        onProblem: (T, String) -> Unit,
    ): Array<Any?> {
        val out = arrayOfNulls<Any?>(slots.size)
        var next = 0
        for ((label, value) in written) {
            val byName = when {
                label != null -> slots.indexOfFirst { looseEquals(it.name, label) }
                else -> slots.indexOfFirst { looseEquals(it.name, nameOf(value)) }
            }
            val i = if (byName >= 0) byName else if (label == null) next else -1
            when {
                i < 0 -> onProblem(value, "there is no '${label}' here")
                i >= slots.size -> onProblem(value, "there are only ${slots.size}")
                out[i] != null -> onProblem(value, "'${slots[i].name}' twice")
                else -> {
                    out[i] = value
                    // Positional counting skips what a name has already claimed, so a mix behaves the way
                    // a reader expects rather than shifting everything after the first named one.
                    if (i == next) {
                        while (next < slots.size && out[next] != null) next++
                    }
                }
            }
        }
        return out
    }

    /** The name a written entry offers for by-name matching — a destructuring's local, or nothing. */
    private fun nameOf(value: Any?): String = (value as? TupleEntry)?.local ?: ""

    /** Everything a call site owes its callee, once which callee it is has been settled. */
    private fun checkCall(e: CallExpr, callee: Callee, scope: Scope, receiver: Expr? = null): TypeRef {
        calleeOf[e] = callee

        val sig = callee.signature
        // The same matcher a destructuring goes through — name, position, or a mix. A call's bare
        // argument is an EXPRESSION and never matches by name: `f(y, x)` passes what was written where it
        // was written, and only a destructuring's bare name is also a name.
        val written = ArrayList<Pair<String?, Expr>>()
        if (receiver != null && sig.params.isNotEmpty()) written += sig.params.first().name to receiver
        for (arg in e.args) written += arg.name to arg.value
        // `xs.each { … }` — a lambda written after the closing paren fills the LAST parameter that takes
        // a function, which is where every signature that wants one puts it. Named rather than positional
        // so it lands there whatever else was supplied.
        e.trailing?.let { lambda ->
            val slot = sig.params.lastOrNull { it.type.isFunction }
            if (slot == null) error(e.span, "'${sig.name}' takes no function, so there is nothing for the block to be")
            else written += slot.name to lambda
        }
        // A template's arguments are its placeholders, so anything past the template is a value it
        // substitutes rather than a parameter it never declared.
        val template = (callee as? NativeCallee)?.fn?.fromTemplate == true
        val matched = matchSlots(sig.params, if (template) written.take(sig.params.size) else written) { expr, why ->
            error(expr.span, "'${sig.name}': $why")
            expr(expr, scope)
        }
        if (template) {
            for ((_, value) in written.drop(sig.params.size)) expr(value, scope)
            templateArgs[e] = written.drop(sig.params.size).map { it.second }
        }
        val slots = Array(sig.params.size) { matched.getOrNull(it) as? Expr }

        // **Every parameter is a binding site.** A call site has no privileged argument, so the variables
        // in a signature are bound from the arguments left to right and each has to agree with what an
        // earlier one settled — which is the generalisation `Generics.kt` stopped short of, because a wire
        // always knew its receiver first.
        val bound = HashMap<String, TypeRef>()
        for ((i, p) in sig.params.withIndex()) {
            val supplied = slots[i]
            if (supplied == null) {
                if (!p.hasDefault) error(e.span, "'${sig.name}' needs '${p.name}'")
                continue
            }
            val want = substitute(p.type, bound)
            // The receiver was typed to FIND the extension, so it is not resolved a second time — doing
            // so would report any error in it twice, once for each visit.
            val got = if (supplied === receiver) typeOf[supplied] ?: TypeRef.WILDCARD else expr(supplied, scope, want)
            if (hasTypeVariables(p.type) && !unify(p.type, got, bound)) {
                error(supplied.span, "'${p.name}' is ${show(p.type)} but is being given ${show(got)}")
            } else if (!hasTypeVariables(p.type) && !assignable(got, want)) {
                error(supplied.span, "'${p.name}' is ${show(want)} but is being given ${show(got)}")
            } else {
                literal(supplied, want)
            }
        }
        argumentsOf[e] = slots.toList()
        return substitute(sig.resultType, bound)
    }

    /**
     * A literal handed to one of the string-backed types, checked against what that type actually allows.
     *
     * **The fixed list of skills has existed all along and nothing consulted it**, so `"Attak"` typechecked
     * as a skill and failed in the game, hours later, as a verb that quietly did nothing. Same for a tile
     * whose string does not parse. This is what a real enum and a real record would give for free; until
     * they are one, it is available here for the price of asking.
     */
    private fun literal(e: Expr, want: TypeRef) {
        val text = (e as? LiteralExpr)?.takeIf { it.kind == LiteralKind.STRING }?.value as? String ?: return
        // A string where a host enum is wanted. The corpus still writes plenty of these, and without
        // this they would fail somewhere less helpful. Generalised from a hard-coded `Skill` check: any
        // registered enum gets the same diagnostic, in its own name and with its own count.
        val wantedEnum = Prelude.enumOf(want)
        if (wantedEnum != null && !wantedEnum.has(text)) {
            error(e.span, "'$text' is not a ${wantedEnum.name} — there are ${wantedEnum.members.size} of them")
            return
        }
        when (want.builtin) {
            else -> Unit
        }
    }

    // ---- complaints -----------------------------------------------------------------------------------

    /**
     * Complain, once.
     *
     * A signature is read twice — when the function is declared, and again when its body is checked
     * against it — so a bad type in one arrives here twice and would be shown twice. Deduplicated on the
     * way in rather than on the way out, so a caller counting errors gets the number a reader would.
     */

    /**
     * A record must be possible to BUILD — refuse the cycles that are not, and allow the ones that are.
     *
     * A record may name itself, and most of the interesting shapes do: `type Node { next: Node? }` and
     * `type Tree { kids: LIST<Tree> }` both work and both run. What cannot exist is a cycle every step of
     * which is a REQUIRED, un-boxed field — `type Loop { me: Loop }` — because building one would need a
     * value of itself first. There is no finite value, so the field is silently left null, and a field
     * declared without `?` holding null is the "compiles and quietly means less" shape rather than an
     * error anybody would see.
     *
     * **The box is what breaks the cycle**, and the language already has three: an optional is null, a list
     * is empty, a map is empty. Each is a real value of the field's own type, so a cycle through any of
     * them terminates. That is Rust's rule with `Box` and Kotlin's with a nullable, arrived at from the
     * same place — and it is why this refuses a cycle rather than refusing self-reference, which is what
     * the graph front end does (`Validator.checkTypes`, "a record cannot hold one of its own kind") and
     * what made the shapes above unwritable there.
     *
     * Local declarations only. An imported record was checked when ITS document was resolved, and checking
     * it again here would report somebody else's declaration against a line in this file.
     */
    private fun checkInhabitable() {
        // An edge is a field that must hold a value of another record RIGHT NOW: not optional, and not a
        // container, whose empty case is the finite value that ends the walk.
        fun edges(r: RecordType): List<Pair<String, RecordType>> = r.fields.mapNotNull { f ->
            val t = f.type
            if (t.optional || t.isList || t.isMap) return@mapNotNull null
            records[t.simpleName]?.let { f.name to it }
        }

        val state = HashMap<String, Int>() // 0 unseen, 1 on the stack, 2 done
        val path = ArrayList<String>()

        fun walk(r: RecordType) {
            if (state[r.name] == 2) return
            if (state[r.name] == 1) {
                val from = path.indexOf(r.name)
                val ring = (path.drop(from) + r.name).joinToString(" -> ")
                error(
                    r.span,
                    "'" + r.name + "' cannot be built: " + ring + " is a cycle of required fields, so a " +
                        "value of it would have to exist before it could be made. Make one of those fields " +
                        "optional ('" + r.name + "?'), or hold them in a LIST or a MAP.",
                )
                return
            }
            state[r.name] = 1
            path += r.name
            for ((_, next) in edges(r)) walk(next)
            path.removeAt(path.size - 1)
            state[r.name] = 2
        }

        for (r in records.values.toList()) walk(r)
    }

    private fun error(span: Span, message: String) {
        val d = TextDiagnostic(span, message, Severity.ERROR)
        if (diagnostics.none { it.span == d.span && it.message == d.message }) diagnostics += d
    }

    /**
     * Not built yet — an ERROR, not a warning.
     *
     * A construct the resolver skips is one the compiler would then compile with no type behind it, which
     * is the failure mode this pass exists to end. Saying so out loud costs an author one honest message;
     * the alternative costs them a wrong answer at run time.
     */
    private fun unsupported(span: Span, what: String) {
        diagnostics += TextDiagnostic(span, "the text front end does not understand $what yet", Severity.ERROR)
    }

    /** The name a field assignment ultimately rebinds — `a` in `a.b.c = v`. */
    private fun rootName(e: Expr): String? = when (e) {
        is NameExpr -> e.name
        is MemberExpr -> rootName(e.target)
        else -> null
    }

    /** Pin names and written labels compared the way the catalogue compares them. */
    private fun looseEquals(a: String, b: String): Boolean = loose(a) == loose(b)

    private fun loose(s: String): String =
        s.filterNot { it.isWhitespace() || it == '_' || it == '-' }.lowercase()

    private fun show(t: TypeRef): String = t.toString()

    private companion object {
        /** The name an `export default` is stored under — what a default import binds to. */
        /** The receiver's own parameter name — its ABSENCE is what makes a function type-level. */
        const val SELF = "self"

        /** What a type variable looks like outside a receiver — `Generics.kt`'s rule, not a second one. */
        val CONVENTIONAL = Regex("[A-Z][0-9]*")

        /** Every enum answers this, and no enum declares it. */
        const val VALUES = "values"

        /** `Skill.of("Farming")` — a name read back as a member. See the note at its use. */
        const val OF = "of"

        /**
         * The name an `export default` is stored under — what a default import binds to.
         *
         * The same string as [dev.ziggle.vscript.lang.ANONYMOUS_DEFAULT], and referenced rather than
         * respelled: a nameless `export default single { … }` registers under that, and a default import
         * is a lookup of this. Two equal literals would be two things to keep equal.
         */
        const val DEFAULT_EXPORT = dev.ziggle.vscript.lang.ANONYMOUS_DEFAULT
    }

    /** How an operator is written, for a message that quotes the source back. */
    private fun symbol(op: BinaryOp): String = when (op) {
        BinaryOp.ADD -> "+"
        BinaryOp.SUB -> "-"
        BinaryOp.MUL -> "*"
        BinaryOp.DIV -> "/"
        BinaryOp.MOD -> "%"
        BinaryOp.EQ -> "=="
        BinaryOp.NE -> "!="
        BinaryOp.LT -> "<"
        BinaryOp.LE -> "<="
        BinaryOp.GT -> ">"
        BinaryOp.GE -> ">="
        BinaryOp.AND_THEN -> "&&"
        BinaryOp.OR_ELSE -> "||"
    }
}

/** The one lookup that must not be repeated with a `!!` at every call site. */
private fun NativeTable.getValue(name: String): NativeFn =
    this[name] ?: error("no native '$name' — checked before this call")

/**
 * The operators a declaration may claim — see [dev.ziggle.vscript.lang.FnDecl.isOperator].
 *
 * **Closed on purpose.** An operator is syntax, and syntax any document could invent is syntax nobody can
 * read: `xs[i]` has to mean the same thing everywhere or the bracket stops being a bracket. Growing this
 * is adding a row here rather than a rule in the compiler, which is the whole point of moving operators
 * out of it.
 *
 * `get` and `set` are what the index form needs — `xs[i]` and `xs[i] = v`. The arithmetic ones can follow
 * when something wants them.
 */
internal val OPERATOR_NAMES = listOf("get", "set")
