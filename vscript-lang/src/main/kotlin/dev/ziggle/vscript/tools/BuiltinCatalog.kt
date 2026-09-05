package dev.ziggle.vscript.tools

import dev.ziggle.vscript.model.NodeCatalog

/**
 * The builtins and nothing else — the catalogue of a project that embeds the language and adds no verbs.
 *
 * Genuinely useful on its own (a document doing arithmetic, control flow and `log` needs no host), and it
 * is what a build tool can point at to prove its wiring works before a real host exists. A host with verbs
 * supplies its own [CatalogProvider]; this is the floor, not a default anything should settle for.
 */
class BuiltinCatalog : CatalogProvider {
    override fun catalog(): NodeCatalog = NodeCatalog()
}
