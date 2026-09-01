package dev.ziggle.vscript.text

import dev.ziggle.vscript.compile.Severity
import dev.ziggle.vscript.lang.EntryDecl
import dev.ziggle.vscript.lang.EntryKind
import dev.ziggle.vscript.lang.Lexer
import dev.ziggle.vscript.lang.Parser
import dev.ziggle.vscript.vm.Chunk
import dev.ziggle.vscript.vm.ProgramBuilder

/**
 * Source text to a runnable chunk — the whole front end, as one call.
 *
 * The counterpart of `VsText` on the graph side, and deliberately the same shape: a **funnel, not a
 * pipeline**. A parse error means there is nothing to resolve, and a resolve error means there is nothing
 * worth emitting — running the next stage on the wreckage of the last produces a second wave of complaints
 * about damage the first one already explained.
 *
 * The one asymmetry with `VsText` is where the errors come from. Its stages speak in node ids and it has
 * to translate them back to lines; here every stage already holds a [Span], because the tree they all walk
 * is the one the parser built out of the source.
 */
// `@JvmOverloads` because the IntelliJ plugin is JAVA, deliberately, and Java does not see Kotlin's
// default arguments — adding a parameter here is a source-breaking change over there without it.
class TextFrontEnd @JvmOverloads constructor(
    private val natives: NativeTable,
    /**
     * The table every chunk shares — see `docs/TEXT_FRONTEND.md`, invariant 1.
     *
     * Injected rather than made per-read, so several documents compiled through one front end can call
     * each other later without anything being relinked.
     */
    val program: ProgramBuilder = ProgramBuilder(),
    /**
     * Where an `import` is looked up. [TextSource.NONE] is not "imports are broken" — it is "this document
     * imports nothing", which is what a caller with a single file means.
     */
    private val imports: TextSource = TextSource.NONE,
    /** Emit the debugger's apparatus — see `TextCompiler.debug`. */
    private val debug: Boolean = true,
    /**
     * What to call the document being READ — the one nothing imports and so nothing names.
     *
     * Every imported module is named by the reference it was reached through, and `TextCompiler` keys the
     * chunk table on that. The root arrives as bare text, so it has no reference to be named by and falls
     * back to `<root>`, which is fine for one document and wrong for two: this front end's `program` is
     * shared on purpose — "so several documents compiled through one front end can call each other later"
     * — and two roots both called `<root>` would collide in the chunk table exactly as two documents
     * called `graph "util"` used to.
     *
     * So a caller that knows where the root came from should say. The client knows (it opened the file);
     * a test usually does not, and does not need to.
     */
    private val rootRef: String = Resolution.ROOT,
) {

    /**
     * Where every authoring site of every document read through this front end is numbered.
     *
     * On the front end rather than on a read, for the same reason [program] is: an id has no chunk beside
     * it, so a run that spans several documents needs one numbering. A debugger asks this to turn "line
     * 42 of core/pace" into a breakpoint and to turn a stopped frame back into a line.
     */
    val sites: Sites = Sites()

    /**
     * What reading a source file produced.
     *
     * [chunk] is non-null only when the text is RUNNABLE: parsed, resolved and emitted clean. Warnings do
     * not withhold it.
     */
    class Read(
        val chunk: Chunk?,
        val resolution: Resolution?,
        val diagnostics: List<TextDiagnostic>,
        /**
         * The entry chunk of each handler this document declares, by kind.
         *
         * The runtime dispatches six groups (`on start`, `on stop`, `on render`, `on tick`, `on wake`,
         * `on sleep`) and a script may write any subset, so a run needs all of them compiled into the one
         * program before it spawns anything — not one read per handler, which would number the globals
         * six times.
         */
        val entries: Map<EntryKind, Chunk> = emptyMap(),
    ) {
        val ok: Boolean get() = chunk != null
        val errors: List<TextDiagnostic> get() = diagnostics.filter { it.severity == Severity.ERROR }
    }

    /**
     * Tokens, or the one honest complaint about why there are none.
     *
     * **The lexer THROWS rather than collecting**, because below the level of a statement there is nothing
     * to resynchronise to and the first bad character is the only thing worth saying. Every entry point
     * here has to catch it, and each of them forgetting differently is how an editor came to show no
     * diagnostics at all for a file with one stray character in it: the exception escaped to a caller
     * whose job was to keep working, and "keep working" looked exactly like "nothing is wrong".
     */
    private fun lex(source: String): Pair<List<dev.ziggle.vscript.lang.Token>?, TextDiagnostic?> = try {
        Lexer(source).lex() to null
    } catch (e: dev.ziggle.vscript.lang.VsSyntaxError) {
        null to TextDiagnostic(e.span, e.message.orEmpty())
    }

    /**
     * What an EDITOR can know about a half-typed file.
     *
     * [resolution] is best-effort and survives errors; [diagnostics] does not. That split is the whole
     * point of this class existing beside [resolve], so it is worth being exact about:
     *
     *  - **Diagnostics keep the funnel.** A parse error means there is no program worth resolving, so
     *    reporting what the resolver then thinks of the wreckage produces a second wave of complaints
     *    about damage the first one already explained. What is reported is the first failing stage's
     *    errors and nothing after it — the same rule `compile` and `resolve` follow, and the reason a
     *    broken file shows one red squiggle instead of forty.
     *  - **Semantics do not.** The parser resynchronises at declaration boundaries and hands back
     *    *"whatever was understood alongside the list of what was not"*. Throwing that away is right for a
     *    compiler and wrong for an editor, where the file is broken on almost every keystroke: it is
     *    exactly when somebody is mid-identifier that they want to know what is in scope.
     *
     * [complete] says which happened, so a caller can tell "this is what the file means" from "this is
     * the best I have while you type".
     */
    class Analysis(
        val resolution: Resolution?,
        val diagnostics: List<TextDiagnostic>,
        val complete: Boolean,
        /**
         * Comments, keyed by the offset of the token they introduce — straight from the parser.
         *
         * **This is the documentation.** The language has no `///`: `CLAUDE.md` says the comment above a
         * declaration IS its documentation, so a hover has nowhere else to read from. Keyed by the
         * FOLLOWING token rather than by line, because printing changes line numbers and does not change
         * which construct a comment introduces — which also makes the lookup exact for a caller holding a
         * declaration's span.
         */
        val comments: Map<Int, List<String>> = emptyMap(),
    ) {
        val errors: List<TextDiagnostic> get() = diagnostics.filter { it.severity == Severity.ERROR }
    }

    /**
     * Resolve as much of [source] as can be understood, whatever is wrong with the rest.
     *
     * Three recoveries, in the order the stages fail:
     *
     *  1. **The lexer throws on the first character it cannot read**, because below a statement there is
     *     nothing to resynchronise to. An unclosed quote is one keystroke away at all times, so the text
     *     up to that point is re-lexed instead — it is already known to be good, and cannot throw again.
     *     `CodeHighlighter` has been doing this for its colours; this is the same trick one layer down.
     *  2. **The parser already recovers**, at declaration granularity, counting braces so it does not land
     *     inside a broken block and invent a second error. Nothing to add.
     *  3. **The resolver is handed the recovered tree**, and is wrapped: it is written against a
     *     well-formed program, and a half-typed one may reach a state it does not expect. Best-effort
     *     means best-effort — a caller gets null rather than an exception out of a keystroke.
     */
    fun analyse(source: String): Analysis {
        var tokens = lex(source).first
        var lexError: TextDiagnostic? = null
        if (tokens == null) {
            val bad = lex(source).second!!
            lexError = bad
            // Re-lex the prefix that was already read cleanly. `span.start` is where the trouble is, so
            // everything before it lexed once and will lex again.
            tokens = runCatching { Lexer(source.substring(0, bad.span.start.coerceIn(0, source.length))).lex() }
                .getOrNull()
        }
        if (tokens == null) return Analysis(null, listOfNotNull(lexError), complete = false)

        val parsed = Parser(tokens, source).parse()
        val stageErrors = when {
            lexError != null -> listOf(lexError)
            !parsed.ok -> parsed.errors.map { TextDiagnostic(it.span, it.message) }
            else -> null
        }

        val set = ModuleSet(imports, natives)
        val resolution = runCatching {
            Resolver(natives, set, set.globals, rootRef).resolve(parsed.program)
        }.getOrNull()

        if (stageErrors != null || resolution == null) {
            return Analysis(resolution, stageErrors ?: emptyList(), complete = false, comments = parsed.commentsBefore)
        }
        if (!resolution.ok) {
            return Analysis(resolution, resolution.diagnostics, complete = false, comments = parsed.commentsBefore)
        }

        // **Emitted too, so this is a strict superset of `compile` for an editor.**
        //
        // A construct the resolver accepts and the emitter refuses is still a file that will not run, and
        // the author should hear about it while typing rather than at Run — which is the reason
        // `CodeBuffer` compiles on every keystroke instead of merely resolving. Doing it here means a
        // caller gets the verdict AND the resolution from one pass, rather than paying for the pipeline
        // twice to have both.
        val emitted = runCatching {
            val compiler = TextCompiler(resolution, program, sites = sites, debug = debug)
            for (kind in EntryKind.values()) {
                if (kind == EntryKind.TEST) continue
                compiler.compileEntries(kind)
            }
            null
        }.fold({ it }, { e ->
            (e as? TextCompileError)?.let { TextDiagnostic(it.span, it.message.orEmpty()) }
                ?: TextDiagnostic(dev.ziggle.vscript.lang.Span.NONE, e.message ?: "could not be emitted")
        })

        return Analysis(
            resolution,
            resolution.diagnostics + listOfNotNull(emitted),
            complete = emitted == null,
            comments = parsed.commentsBefore,
        )
    }

    /**
     * Resolve [source] without demanding anything runnable in it.
     *
     * **A library has no entry, and that is not an error.** `read` compiles a document to a chunk and so
     * needs an `on start`; asking that of `core/activity` reports a script with nothing wrong as broken,
     * which is exactly what the first corpus run did.
     */
    fun resolve(source: String): Read {
        val (tokens, bad) = lex(source)
        if (tokens == null) return Read(null, null, listOf(bad!!))
        val parsed = Parser(tokens, source).parse()
        if (!parsed.ok) {
            return Read(null, null, parsed.errors.map { TextDiagnostic(it.span, it.message) })
        }
        val set = ModuleSet(imports, natives)
        val resolution = Resolver(natives, set, set.globals, rootRef).resolve(parsed.program)
        return Read(null, resolution, resolution.diagnostics)
    }

    /**
     * Compile [source] and everything it imports, ready to run.
     *
     * [kind] is the handler that must be there — `read` is for a RUNNABLE document, and a script with no
     * `on start` is not one. Every OTHER handler it happens to declare is compiled too, into the same
     * program: the runtime dispatches all six groups and compiling them one read at a time would number
     * the closure's globals once per handler.
     */
    /**
     * What compiling a whole run produced — every handler of every kind, ready to spawn.
     *
     * [entries] is empty for a kind nothing declares, which is the normal case: most scripts are an
     * `on start` and nothing else.
     */
    class Compilation(
        val resolution: Resolution?,
        val entries: Map<EntryKind, List<TextEntry>>,
        val diagnostics: List<TextDiagnostic>,
        /** The whole run's starting values — see `GlobalSlots`. */
        val globals: List<Any?> = emptyList(),
        /**
         * Whether this was built with the debugger's apparatus — see `TextCompiler.debug`.
         *
         * Carried on the result rather than remembered by the caller, so a runtime handed a compilation
         * can say honestly whether a breakpoint in it will ever fire.
         */
        val debug: Boolean = true,
    ) {
        val errors: List<TextDiagnostic> get() = diagnostics.filter { it.severity == Severity.ERROR }
        val warnings: List<TextDiagnostic> get() = diagnostics.filter { it.severity == Severity.WARNING }
        val ok: Boolean get() = errors.isEmpty()
    }

    /**
     * Compile [source] and everything it imports, every handler, into one program.
     *
     * The counterpart of `GraphCompiler.compileEntries` called once per group, and the entry point a
     * *runtime* wants: [read] asks "is this document runnable", which needs a handler to name, and a
     * script whose whole job is an `on tick` watchdog has no `on start` to name. Refusing that one would
     * be the same mistake `handlersOnly` was added to fix on the graph side.
     *
     * Whether there is anything worth running is the caller's judgement, not this one's — an empty result
     * is a fact about the document, not an error in it.
     */
    /**
     * Compile the `test` declarations in [source], and nothing else.
     *
     * A separate entry point rather than a flag on [compile], because the two answer different questions
     * and a runtime must not be able to get tests by accident: [compile] is "what does this script RUN",
     * and a test is never run by the host. The resolver still walks tests on both paths — a test that has
     * stopped typechecking fails an ordinary check — so what this adds is only the bytecode.
     */
    fun compileTests(source: String): Compilation {
        val (tokens, bad) = lex(source)
        if (tokens == null) return Compilation(null, emptyMap(), listOf(bad!!), debug = debug)
        val parsed = Parser(tokens, source).parse()
        if (!parsed.ok) {
            return Compilation(null, emptyMap(), parsed.errors.map { TextDiagnostic(it.span, it.message) }, debug = debug)
        }
        val set = ModuleSet(imports, natives)
        val resolution = Resolver(natives, set, set.globals, rootRef).resolve(parsed.program)
        if (!resolution.ok) return Compilation(resolution, emptyMap(), resolution.diagnostics, debug = debug)
        // **Fakes apply to this compilation and to no other.** `compile` passes none, so a `fake` cannot
        // change what a script does when it is run for real — it is a thing that exists while tests run.
        val fakes = resolution.document.decls
            .filterIsInstance<dev.ziggle.vscript.lang.FnDecl>()
            .filter { it.fakes != null }
            .associate { it.fakes!! to TextCompiler.FakeBinding(resolution, it) }
        val compiler = TextCompiler(resolution, program, sites = sites, debug = debug, fakes = fakes)
        return try {
            val found = compiler.compileEntries(EntryKind.TEST)
            val entries = if (found.isEmpty()) emptyMap() else mapOf(EntryKind.TEST to found)
            Compilation(resolution, entries, resolution.diagnostics, resolution.globalDefaults, debug)
        } catch (e: TextCompileError) {
            Compilation(
                resolution, emptyMap(),
                resolution.diagnostics + TextDiagnostic(e.span, e.message.orEmpty()), debug = debug,
            )
        }
    }

    fun compile(source: String): Compilation {
        val (tokens, bad) = lex(source)
        if (tokens == null) return Compilation(null, emptyMap(), listOf(bad!!), debug = debug)
        val parsed = Parser(tokens, source).parse()
        if (!parsed.ok) {
            return Compilation(null, emptyMap(), parsed.errors.map { TextDiagnostic(it.span, it.message) }, debug = debug)
        }
        val set = ModuleSet(imports, natives)
        val resolution = Resolver(natives, set, set.globals, rootRef).resolve(parsed.program)
        if (!resolution.ok) return Compilation(resolution, emptyMap(), resolution.diagnostics, debug = debug)

        // One compiler for the run, so its functions, lambdas, globals and sites are numbered once
        // however many handlers reach them.
        val compiler = TextCompiler(resolution, program, sites = sites, debug = debug)
        return try {
            val entries = LinkedHashMap<EntryKind, List<TextEntry>>()
            for (kind in EntryKind.values()) {
                // **A test is resolved but not emitted.** The resolver walks every `EntryDecl` whatever its
                // kind, so a test that has stopped typechecking fails an ordinary `graph check` — which is
                // the point, and is what `#[cfg(test)]` gives up. What a normal run does not need is its
                // BYTECODE: nothing spawns this kind, so emitting it would put every test's code in every
                // script that ships. `compileTests` is how a runner asks for it.
                if (kind == EntryKind.TEST) continue
                val found = compiler.compileEntries(kind)
                if (found.isNotEmpty()) entries[kind] = found
            }
            Compilation(resolution, entries, resolution.diagnostics, resolution.globalDefaults, debug)
        } catch (e: TextCompileError) {
            Compilation(resolution, emptyMap(), resolution.diagnostics + TextDiagnostic(e.span, e.message.orEmpty()), debug = debug)
        }
    }

    fun read(source: String, kind: EntryKind = EntryKind.START): Read {
        val (tokens, bad) = lex(source)
        if (tokens == null) return Read(null, null, listOf(bad!!))
        val parsed = Parser(tokens, source).parse()
        if (!parsed.ok) {
            return Read(null, null, parsed.errors.map { TextDiagnostic(it.span, it.message) })
        }

        // One module set per read, so the closure's globals are numbered once and the imported documents
        // are resolved leaves-first before this one needs their signatures.
        val set = ModuleSet(imports, natives)
        val resolution = Resolver(natives, set, set.globals, rootRef).resolve(parsed.program)
        if (!resolution.ok) return Read(null, resolution, resolution.diagnostics)

        // One compiler for the whole document, so its functions, lambdas and sites are numbered once
        // however many handlers reach them.
        val compiler = TextCompiler(resolution, program, sites = sites, debug = debug)
        val declared = resolution.document.decls.filterIsInstance<EntryDecl>().map { it.kind }.toSet()
        return try {
            val entries = LinkedHashMap<EntryKind, Chunk>()
            // The demanded one first, so its absence is the error rather than a later handler's.
            entries[kind] = compiler.compileEntry(kind)
            for (other in declared) if (other != kind) entries[other] = compiler.compileEntry(other)
            Read(entries[kind], resolution, resolution.diagnostics, entries)
        } catch (e: TextCompileError) {
            // The compiler refusing something the resolver accepted is a HOLE, not an author's mistake:
            // the two disagree about what is supported. Reported as an ordinary diagnostic so it lands in
            // front of somebody, and phrased so it is obvious which half to go and look at.
            Read(null, resolution, resolution.diagnostics + TextDiagnostic(e.span, e.message.orEmpty()))
        }
    }

    /** Every diagnostic as one message, for a caller with nowhere to put a list. */
    fun describe(read: Read): String =
        if (read.ok) "ok" else read.errors.joinToString("\n") { "${it.span}: ${it.message}" }

}
