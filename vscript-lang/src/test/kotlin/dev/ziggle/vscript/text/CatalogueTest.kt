package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.vm.HostKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The catalogue, as signatures.
 *
 * Adapted rather than retyped: a `NodeDescriptor` is already a signature wearing pin clothes, and the
 * 8,971 lines of declarations in `vscript-nodes` are not worth retyping to prove a point about pins.
 */
class CatalogueTest {

    private val natives = NodeCatalog().natives()

    @Test
    fun `the builtins come through as callable names`() {
        assertTrue(natives.all.isNotEmpty(), "the adapter produced nothing")
    }

    /** A node with no host is compiler-lowered, and a signature with nothing behind it is worse than none. */
    @Test
    fun `nodes with no host are left out`() {
        val names = dev.ziggle.vscript.lang.Names(NodeCatalog())
        val hostless = names.callable.filter { it.host == null }.map { names.textName(it.type) }
        for (n in hostless) {
            if (Intrinsic[n] != null) continue
            assertTrue(natives[n] == null, "'$n' has no host and should not be callable")
        }
    }

    /** The three verbs that take a function are emitted as loops, so they must not also be hosts. */
    @Test
    fun `the higher-order verbs stay intrinsic`() {
        for (i in Intrinsic.entries) {
            assertTrue(natives[i.fnName] == null, "'${i.fnName}' would be both a native and an intrinsic")
        }
    }

    @Test
    fun `a signature keeps its threading classification`() {
        val blocking = natives.all.filter { it.kind == HostKind.BLOCKING }
        // Not all catalogues have one — the builtins may be entirely inline — but if any exists it must
        // have come through as BLOCKING rather than being flattened to INLINE.
        for (fn in blocking) assertEquals(HostKind.BLOCKING, fn.kind)
    }

    /** A pin is named `Miss Percent` and written `missPercent`; both have to be the same argument. */
    @Test
    fun `a written label matches its pin loosely`() {
        val withParams = natives.all.firstOrNull { it.params.isNotEmpty() }
        assertNotNull(withParams, "no catalogue function takes an argument")
        val pin = withParams.params.first().name
        val written = pin.replaceFirstChar { it.lowercase() }.filterNot { it.isWhitespace() }
        val src = """
            graph "probe"

            on start {
                ${withParams.name}($written: ${'"'}x${'"'})
            }
        """.trimIndent()
        val errors = TextFrontEnd(natives).read(src).errors.map { it.message }
        assertTrue(
            errors.none { it.contains("has no parameter called") },
            "the written label did not match the pin: $errors",
        )
    }
}
