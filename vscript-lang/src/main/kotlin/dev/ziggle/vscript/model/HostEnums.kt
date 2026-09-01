package dev.ziggle.vscript.model

/**
 * An enum the HOST declares — the client's own closed vocabulary, as a type a document can name.
 *
 * **The third kind of enum, and the one that was missing.** A document declares its own with `enum Phase
 * { … }`; the language has always had `Skill`, hand-wired as a [PinType] with a member list beside it. What
 * had no spelling at all was a *node library* wanting to say "this pin takes one of these fourteen things"
 * — the sidebar tabs, a drop pattern, a container. The only tool was [PinType.ENUM] with [PinSpec.options],
 * which is a STRING pin that the canvas happens to draw as a dropdown: in text you write `"Inventory"`, a
 * typo is a runtime surprise, and nothing can complete it.
 *
 * This is the same thing said as a type. A host enum resolves exactly where a document's does — see
 * `Resolver.enumNamed`, the one function both surfaces ask — so `Tab.Inventory` is member access, checked
 * at compile time, and `Tab.values()` is a list. Nothing new had to be taught to the parser, the VM or the
 * printer, because a member is its NAME at run time here as everywhere else.
 *
 * ### [type] is separate from [name], and that is load-bearing
 *
 * A new host enum wants `TypeRef.named("Tab")` and gets it by default, and `Skill` now does too: the pins
 * that used to be typed `PinType.SKILL` are typed `Skill` instead, so there is no second spelling for the
 * enum to have to match. The override remains for the case it was written for — attaching an enum to a
 * `PinType` that already exists — and nothing uses it today.
 */
class HostEnum(
    val name: String,
    /** In declaration order — what completion offers, and the order `values()` returns. */
    val members: List<String>,
    /** One line on what it is, for a tooltip and for the manifest. Empty is honest. */
    val describe: String = "",
    /**
     * How the rest of the system types a value of this.
     *
     * Defaults to a nominal type over [name], which is what a genuinely new enum wants. Override it only
     * to attach an enum to a [PinType] that already exists — see the note above.
     */
    val type: TypeRef = TypeRef.named(name),
)

/**
 * The host enum registry — every enum the CLIENT provides, in registration order.
 *
 * A registry rather than a list for [Types]' reason, and with the same boundary: what lives here is what
 * the host provides and is therefore the same in every document. An enum a *document* declares does not
 * belong here and must not be registered into it — those live on the document's own resolution, and are
 * looked up first, so a document that declares `enum Tab` shadows this one rather than colliding with it.
 *
 * **Registration replaces rather than refuses**, as [Types] does: a client that rebuilds its node library
 * — a hot reload, a second `NodeLibrary` — re-registers the same names, and refusing would mean the second
 * build silently kept the first one's members.
 */
object HostEnums {

    private val byKey = LinkedHashMap<String, HostEnum>()

    /**
     * Matched the way [Prelude] and the catalogue match names — underscores, dashes and case ignored.
     *
     * So `ITEM_REF` is reachable as `ItemRef`, and a host enum called `DROP_PATTERN` as `DropPattern`,
     * which is the only spelling anyone would write. Types have never had a reason to be stricter than
     * pins about this.
     */
    private fun key(name: String) =
        name.filterNot { it == '_' || it == '-' || it.isWhitespace() }.lowercase()

    /** Add one, or replace whatever is registered under the same name. */
    fun register(e: HostEnum) {
        byKey[key(e.name)] = e
    }

    fun registerAll(es: Iterable<HostEnum>) = es.forEach(::register)

    /** Every registered enum, registration order. */
    val all: List<HostEnum> get() = byKey.values.toList()

    /** By name, on [key]'s terms. Null when nothing is called that. */
    fun of(name: String?): HostEnum? = name?.trim()?.let { byKey[key(it)] }

    fun reset() {
        byKey.clear()
    }
}
