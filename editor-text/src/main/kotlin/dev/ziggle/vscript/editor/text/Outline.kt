package dev.ziggle.vscript.editor.text

import dev.ziggle.vscript.lang.ConstDecl
import dev.ziggle.vscript.lang.EntryDecl
import dev.ziggle.vscript.lang.EnumDecl
import dev.ziggle.vscript.lang.FnDecl
import dev.ziggle.vscript.lang.ImportDecl
import dev.ziggle.vscript.lang.Program
import dev.ziggle.vscript.lang.SingleDecl
import dev.ziggle.vscript.lang.TypeDecl
import dev.ziggle.vscript.lang.ValDecl
import dev.ziggle.vscript.lang.VarDecl

/**
 * What a document declares, in the order it declares it — one model, two views.
 *
 * ### Why the structure view and symbol search share this
 *
 * They ask the same question at different scopes. A structure view is "what is in THIS file"; a symbol
 * search is "what is in any file, filtered by what you typed". Building them separately is how the two end
 * up disagreeing about whether a `single` is a type or a value, or about which line a `fn` starts on — and
 * disagreeing about a line is worse than useless, because the whole point of both is to take you there.
 *
 * ### It is built from the AST, not from the resolution
 *
 * `Resolution` knows more — types, bindings, what everything means — and needs the file to have resolved.
 * A structure view has to work while the file is broken, which is most of the time. The AST survives a
 * failed parse (the parser resynchronises at declaration boundaries), so an outline built from it still
 * lists the declarations either side of whatever is half-typed. That is the same argument
 * `TextFrontEnd.analyse` makes one layer down, and it is why this takes a [Program] rather than a
 * `Resolution`.
 */
class Outline(val symbols: List<Symbol>) {

    /** What a symbol is, which decides its icon and how a search ranks it. */
    enum class Kind {
        FUNCTION,
        RECORD,
        ENUM,
        SINGLE,
        VARIABLE,
        CONSTANT,
        ENTRY,
        IMPORT,
    }

    /**
     * One declaration, and the line to jump to.
     *
     * [detail] is the part a reader wants beside the name but does not search on — a signature, a type, an
     * import's source. Kept separate from [name] for exactly that reason: typing `helper` should match a
     * function called `helper`, and should not match every function that happens to take a `helper: INT`.
     */
    class Symbol(
        val name: String,
        val kind: Kind,
        val line: Int,
        val detail: String = "",
        val exported: Boolean = false,
        /** Fields of a record, members of an enum — one level, because that is all vs nests. */
        val children: List<Symbol> = emptyList(),
    )

    val isEmpty: Boolean get() = symbols.isEmpty()

    /** Every symbol including children, flattened — what a search looks through. */
    fun flatten(): List<Symbol> = buildList {
        for (s in symbols) {
            add(s)
            addAll(s.children)
        }
    }

    companion object {

        /**
         * Read [program]'s declarations, using [lines] to turn each span into a line number.
         *
         * Declaration ORDER, not alphabetical. A structure view that sorts its entries stops being a map
         * of the file: an author knows roughly where in a document something is, and reordering throws
         * that away in exchange for an ordering they can get from a search box anyway.
         */
        fun of(program: Program, lines: LineIndex): Outline {
            val out = ArrayList<Symbol>(program.decls.size)
            for (d in program.decls) {
                val line = lines.lineAt(d.span.start)
                when (d) {
                    is FnDecl -> out += Symbol(
                        d.name, Kind.FUNCTION, line, signature(d), d.isExported,
                    )
                    is TypeDecl -> out += Symbol(
                        d.name, Kind.RECORD, line, "${d.fields.size} field(s)", d.isExported,
                        children = d.fields.map { Symbol(it.name, Kind.VARIABLE, line) },
                    )
                    is EnumDecl -> out += Symbol(
                        d.name, Kind.ENUM, line, "${d.members.size} member(s)", d.isExported,
                        children = d.members.map { Symbol(it.name, Kind.CONSTANT, line) },
                    )
                    is SingleDecl -> out += Symbol(
                        d.name, Kind.SINGLE, line, "${d.fields.size} field(s)", d.isExported,
                        children = d.fields.map { Symbol(it.name, Kind.VARIABLE, line) },
                    )
                    is VarDecl -> out += Symbol(d.name, Kind.VARIABLE, line, "", d.isExported)
                    is ValDecl -> out += Symbol(d.name, Kind.CONSTANT, line, "", d.isExported)
                    is ConstDecl -> out += Symbol(d.name, Kind.CONSTANT, line, "", d.isExported)
                    // Named by its KIND, because that is what an author calls it: nobody looks for the
                    // entry declaration, they look for "on start". A label distinguishes several of a kind.
                    is EntryDecl -> out += Symbol(
                        d.label?.let { "on ${entryWord(d)} \"$it\"" } ?: "on ${entryWord(d)}",
                        Kind.ENTRY, line,
                    )
                    // A star import has no alias; it is known by what it imports FROM.
                    is ImportDecl -> out += Symbol(d.alias ?: d.ref, Kind.IMPORT, line, d.ref)
                    else -> Unit
                }
            }
            return Outline(out)
        }

        /**
         * `start`, `stop`, `render`, `tick`, `wake`, `sleep` — from the kind itself.
         *
         * `BuiltinNodes.ENTRY_WORDS` is the language's spelling table but is keyed by NODE TYPE, which an
         * `EntryDecl` does not carry; going through it would mean a second mapping to keep in step. The
         * enum's own names already are the words, lowercased.
         */
        private fun entryWord(d: EntryDecl): String = d.kind.name.lowercase()

        /** `(n: INT) -> INT`, which is what tells two overloads apart at a glance. */
        private fun signature(d: FnDecl): String {
            val params = d.params.joinToString(", ") { it.name }
            val results = d.results.joinToString(", ") { it.name }
            val recv = d.receiver?.let { "" } ?: ""
            return if (results.isEmpty()) "$recv($params)" else "$recv($params) -> $results"
        }
    }
}
